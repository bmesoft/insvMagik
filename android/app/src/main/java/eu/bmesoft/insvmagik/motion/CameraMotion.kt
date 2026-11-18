package eu.bmesoft.insvmagik.motion

import eu.bmesoft.insvmagik.insv.CameraInfo
import eu.bmesoft.insvmagik.insv.ImuSamples
import eu.bmesoft.insvmagik.view.Mat3
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The gyro and accelerometer in the rig's body axes (the world [eu.bmesoft.insvmagik.view.CameraRig]
 * renders in: x right, y down, z forward) on the video's clock: time 0 is video frame 0.
 * Accelerometer in g (at rest it reads +1 g pointing **up**), gyro in rad/s.
 */
class ImuTrack(val timesUs: LongArray, val accel: Array<DoubleArray>, val gyro: Array<DoubleArray>) {
    val count: Int get() = timesUs.size

    companion object {
        /**
         * [axes] turns the sensor's axes into the body's (per camera model, see [SensorAxes]).
         * Clock (telemetry-parser's reading, checked on both samples): video time = sensor time −
         * [CameraInfo.firstFrameTime] − [CameraInfo.gyroOffsetMs]; the first-frame time is in the
         * sensor clock's own unit (ms on the ONE X2, µs with raw 16-bit samples).
         */
        fun from(samples: ImuSamples, info: CameraInfo, axes: Mat3): ImuTrack? {
            val first = info.firstFrameTime ?: return null
            val firstUs = if (samples.format == ImuSamples.Format.F64_MS) first * 1000 else first
            val offsetUs = ((info.gyroOffsetMs ?: 0.0) * 1000).toLong()
            val (accScale, gyroScale) = when (samples.format) {
                ImuSamples.Format.F64_MS -> 1.0 to 1.0
                ImuSamples.Format.RAW16_US -> {
                    // ± range over ± 32768 counts; telemetry-parser's defaults are ± 16 g and ± 2000 °/s.
                    val (acc, gyro) = info.ranges ?: (16 to 2000)
                    acc / 32768.0 to Math.toRadians(gyro / 32768.0)
                }
            }
            val n = samples.count
            val t = LongArray(n) { samples.timesUs[it] - firstUs - offsetUs }
            val tmp = DoubleArray(3)
            val acc = Array(n) { i ->
                val v = samples.values[i]
                axes.apply(v[0] * accScale, v[1] * accScale, v[2] * accScale, tmp)
                tmp.copyOf()
            }
            val gyro = Array(n) { i ->
                val v = samples.values[i]
                axes.apply(v[3] * gyroScale, v[4] * gyroScale, v[5] * gyroScale, tmp)
                tmp.copyOf()
            }
            return ImuTrack(t, acc, gyro)
        }
    }
}

/**
 * How each camera's motion sensor sits in the rig's body axes (body = this · sensor). Measured on
 * the samples (docs/insv-format.md, "Motion"): the ONE X2 by stabilising its clip with all 24
 * axis-to-axis turns and keeping the one that holds the background stillest (`SampleMotionTest`);
 * the A1 by gravity alone (its sample sits still on a table): the turn that levels the horizon;
 * the X5 by the sun on a motorbike ride: the turn that keeps it fixed in a locked world.
 */
object SensorAxes {
    private val ONE_X2 = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0))
    private val A1 = Mat3(doubleArrayOf(0.0, 0.0, -1.0, 0.0, -1.0, 0.0, -1.0, 0.0, 0.0))
    private val X5 = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0))

    /** Null for a camera whose axes aren't known yet. */
    fun forModel(model: String?): Mat3? = when (model) {
        "Insta360 ONE X2" -> ONE_X2
        "Antigravity A1" -> A1
        "Insta360 X5" -> X5
        else -> null
    }
}

/**
 * The camera's orientation over time: body → world for each IMU sample. The world has y pointing
 * down (gravity) and z along the body's forward direction at video time 0, flattened to the horizon.
 */
class Attitude(
    val timesUs: LongArray,
    val orientations: Array<Quat>,
    /** The gyro's resting offset taken off before integrating, rad/s (body axes); null if the camera never sat still. */
    val bias: DoubleArray? = null,
) {
    /** Body → world at [tUs] (video clock), interpolated; held at the ends. */
    fun at(tUs: Long): Quat {
        val n = timesUs.size
        if (tUs <= timesUs[0]) return orientations[0]
        if (tUs >= timesUs[n - 1]) return orientations[n - 1]
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (timesUs[mid] <= tUs) lo = mid else hi = mid
        }
        val f = (tUs - timesUs[lo]).toDouble() / (timesUs[hi] - timesUs[lo])
        return Quat.slerp(orientations[lo], orientations[hi], f)
    }

    /** Where the body's forward (z) points, as a heading about the vertical, radians; 0 = world z. */
    fun heading(q: Quat, fallback: Double): Double {
        val f = DoubleArray(3)
        q.rotate(0.0, 0.0, 1.0, f)
        // Looking almost straight up or down the heading is meaningless: keep the last one.
        return if (hypot(f[0], f[2]) < 0.2) fallback else atan2(f[0], f[2])
    }

    companion object {
        private val UP = doubleArrayOf(0.0, -1.0, 0.0)

        /**
         * Integrates the gyro, with the accelerometer slowly pulling "up" back to true up (a
         * complementary filter in the style of Mahony's). [tiltSeconds] is how quickly a tilt error
         * fades; accelerations away from 1 g (a turn, a jolt) count less.
         */
        fun estimate(imu: ImuTrack, tiltSeconds: Double = 2.0): Attitude {
            val n = imu.count
            require(n >= 2)
            val bias = stillBias(imu)
            // Start level, from the first 0.1 s of the accelerometer.
            val a0 = DoubleArray(3)
            var k = 0
            while (k < n && imu.timesUs[k] - imu.timesUs[0] < 100_000) {
                for (j in 0..2) a0[j] += imu.accel[k][j]
                k++
            }
            val q = arrayOfNulls<Quat>(n)
            q[0] = Quat.between(a0, UP)
            val kp = 1.0 / tiltSeconds
            val v = DoubleArray(3)
            for (i in 0 until n - 1) {
                val cur = q[i]!!
                val dt = ((imu.timesUs[i + 1] - imu.timesUs[i]) / 1e6).coerceIn(0.0, 0.05)
                val g = imu.gyro[i]
                val a = imu.accel[i]
                val an = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
                var wx = g[0] - (bias?.get(0) ?: 0.0)
                var wy = g[1] - (bias?.get(1) ?: 0.0)
                var wz = g[2] - (bias?.get(2) ?: 0.0)
                if (an > 0.1) {
                    // Up as the body currently believes it, vs. up as the accelerometer reads it.
                    cur.conj().rotate(UP[0], UP[1], UP[2], v)
                    val ax = a[0] / an
                    val ay = a[1] / an
                    val az = a[2] / an
                    val trust = exp(-((an - 1) / 0.15).let { it * it }) * kp
                    wx += trust * (ay * v[2] - az * v[1])
                    wy += trust * (az * v[0] - ax * v[2])
                    wz += trust * (ax * v[1] - ay * v[0])
                }
                q[i + 1] = (cur * Quat.fromRotationVector(wx * dt, wy * dt, wz * dt)).normalized()
            }
            val att = Attitude(imu.timesUs, Array(n) { q[it]!! })
            // Turn the world about the vertical so the body faces world z at video time 0.
            val h0 = att.heading(att.at(0), 0.0)
            val turn = Quat.aboutY(-h0)
            return Attitude(imu.timesUs, Array(n) { (turn * q[it]!!).normalized() }, bias)
        }

        /**
         * The gyro's reading at rest, from every half second where the camera sat still (gyro and
         * accelerometer both steady); null if it never did. A drone on the ground, a camera on a
         * tripod: without this, a resting offset of a fraction of a degree per second turns a
         * locked view by several degrees a minute.
         */
        fun stillBias(imu: ImuTrack, windowUs: Long = 500_000): DoubleArray? {
            val sum = DoubleArray(3)
            var windows = 0
            var start = 0
            while (start < imu.count) {
                var end = start
                while (end < imu.count && imu.timesUs[end] - imu.timesUs[start] < windowUs) end++
                if (end - start >= 20 && end < imu.count) {
                    val mean = DoubleArray(3)
                    val sq = DoubleArray(3)
                    var accMin = Double.MAX_VALUE
                    var accMax = 0.0
                    for (i in start until end) {
                        for (j in 0..2) {
                            mean[j] += imu.gyro[i][j]
                            sq[j] += imu.gyro[i][j] * imu.gyro[i][j]
                        }
                        val a = imu.accel[i]
                        val an = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
                        accMin = minOf(accMin, an)
                        accMax = maxOf(accMax, an)
                    }
                    val m = end - start
                    val still = (0..2).all { j ->
                        val mu = mean[j] / m
                        sq[j] / m - mu * mu < STILL_GYRO_SD * STILL_GYRO_SD && kotlin.math.abs(mu) < 0.05
                    } && accMax - accMin < 0.1
                    if (still) {
                        for (j in 0..2) sum[j] += mean[j] / m
                        windows++
                    }
                }
                start = end
            }
            return if (windows == 0) null else DoubleArray(3) { sum[it] / windows }
        }

        /** Gyro spread (rad/s, ≈ 0.6 °/s) below which a half second counts as still. */
        private const val STILL_GYRO_SD = 0.01
    }
}

/** How the view moves with the camera. */
enum class Stabilize {
    /** The view is fixed to the camera body: every shake shows (stage 2's export). */
    OFF,

    /** Level horizon; the view turns with the camera's smoothed heading. */
    FOLLOW,

    /** Level horizon; the view keeps pointing at the same place in the world. */
    LOCKED,
}

/**
 * Per frame, the rotation to put between the view and the rig: view → world comes from the view's
 * yaw/pitch/roll, and this turns the world into the camera body at that moment.
 */
class Stabilizer(private val attitude: Attitude, followSeconds: Double = 1.0) {
    private val stepUs = 10_000L
    private val startUs = attitude.timesUs.first()
    private val smoothHeading: DoubleArray

    init {
        // Heading on an even 10 ms grid, unwrapped, then a Gaussian blur (σ = followSeconds / 2).
        val n = ((attitude.timesUs.last() - startUs) / stepUs + 1).toInt().coerceAtLeast(1)
        val raw = DoubleArray(n)
        var last = 0.0
        for (i in 0 until n) {
            var h = attitude.heading(attitude.at(startUs + i * stepUs), last)
            while (h - last > Math.PI) h -= 2 * Math.PI
            while (h - last < -Math.PI) h += 2 * Math.PI
            raw[i] = h
            last = h
        }
        val sigma = (followSeconds / 2 * 1e6 / stepUs).coerceAtLeast(1e-3)
        val half = (3 * sigma).toInt()
        val kernel = DoubleArray(2 * half + 1) { exp(-0.5 * ((it - half) / sigma).let { x -> x * x }) }
        smoothHeading = DoubleArray(n) { i ->
            var s = 0.0
            var w = 0.0
            for (j in -half..half) {
                val v = raw[(i + j).coerceIn(0, n - 1)]
                s += v * kernel[j + half]
                w += kernel[j + half]
            }
            s / w
        }
    }

    private fun headingAt(tUs: Long): Double {
        val f = ((tUs - startUs).toDouble() / stepUs).coerceIn(0.0, smoothHeading.size - 1.0)
        val i = f.toInt().coerceAtMost(smoothHeading.size - 2).coerceAtLeast(0)
        if (smoothHeading.size == 1) return smoothHeading[0]
        return smoothHeading[i] + (f - i) * (smoothHeading[i + 1] - smoothHeading[i])
    }

    /** World → body at video time [tUs] for [mode]; goes between the rig and the view rotation. */
    fun correction(tUs: Long, mode: Stabilize): Mat3 = when (mode) {
        Stabilize.OFF -> Mat3.IDENTITY
        Stabilize.LOCKED -> attitude.at(tUs).conj().toMat3()
        Stabilize.FOLLOW -> (attitude.at(tUs).conj() * Quat.aboutY(headingAt(tUs))).toMat3()
    }

    companion object {
        /** True when the gyro covers the video: it should start before frame 0. */
        fun covers(imu: ImuTrack, durationUs: Long): Boolean =
            imu.count > 1 && imu.timesUs.first() <= 50_000 && imu.timesUs.last() >= durationUs - 50_000
    }
}
