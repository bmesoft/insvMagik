package eu.bmesoft.insvmagik.motion

import eu.bmesoft.insvmagik.insv.CameraInfo
import eu.bmesoft.insvmagik.insv.ImuSamples
import eu.bmesoft.insvmagik.insv.Protobuf
import eu.bmesoft.insvmagik.view.Mat3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI

class MotionMathTest {
    private val v = DoubleArray(3)
    private val w = DoubleArray(3)

    @Test fun quaternionsMatchTheMatrices() {
        val q = Quat.aboutY(Math.toRadians(30.0)) * Quat.fromRotationVector(0.2, -0.1, 0.4)
        val m = Mat3.rotY(30.0) * Quat.fromRotationVector(0.2, -0.1, 0.4).toMat3()
        q.rotate(0.3, -0.5, 0.8, v)
        m.apply(0.3, -0.5, 0.8, w)
        assertArrayEquals(w, v, 1e-12)
        q.toMat3().apply(0.3, -0.5, 0.8, w)
        assertArrayEquals(w, v, 1e-12)
        // between() takes one direction onto the other.
        Quat.between(doubleArrayOf(1.0, 2.0, 0.5), doubleArrayOf(0.0, -1.0, 0.0)).rotate(1.0, 2.0, 0.5, v)
        assertEquals(0.0, v[0], 1e-12)
        assertTrue(v[1] < 0)
        assertEquals(0.0, v[2], 1e-12)
        // Half-way between no turn and 90° about y is 45° about y.
        Quat.slerp(Quat.IDENTITY, Quat.aboutY(PI / 2), 0.5).rotate(0.0, 0.0, 1.0, v)
        assertEquals(Math.sqrt(0.5), v[0], 1e-12)
    }

    /** A synthetic track: constant turn [rate] (rad/s, body axes) for [seconds], gravity read as [up]. */
    private fun track(rate: DoubleArray, seconds: Double, up: DoubleArray = doubleArrayOf(0.0, -1.0, 0.0), hz: Int = 500): ImuTrack {
        val n = (seconds * hz).toInt() + 1
        val start = -200_000L
        return ImuTrack(LongArray(n) { start + it * 1_000_000L / hz }, Array(n) { up.copyOf() }, Array(n) { rate.copyOf() })
    }

    @Test fun aTiltedCameraAtRestComesOutLevel() {
        // Body rolled 30°: the accelerometer reads up tilted in the body.
        val r = Math.toRadians(30.0)
        val att = Attitude.estimate(track(doubleArrayOf(0.0, 0.0, 0.0), 2.0, doubleArrayOf(Math.sin(r), -Math.cos(r), 0.0)))
        att.at(1_000_000).rotate(Math.sin(r), -Math.cos(r), 0.0, v)
        assertArrayEquals(doubleArrayOf(0.0, -1.0, 0.0), v, 1e-9)
    }

    @Test fun aSteadyPanIsUndoneByTheLockedView() {
        // 30 °/s to the right (about body y, "down"): after 1 s the body faces 30° right.
        val rate = Math.toRadians(30.0)
        val att = Attitude.estimate(track(doubleArrayOf(0.0, rate, 0.0), 2.0, hz = 1000))
        att.at(1_000_000).rotate(0.0, 0.0, 1.0, v)
        assertEquals(Math.toDegrees(Math.atan2(v[0], v[2])), 30.0, 0.1)
        val stab = Stabilizer(att, followSeconds = 0.5)
        // Locked: world forward seen from the body at 1 s is 30° to the left.
        stab.correction(1_000_000, Stabilize.LOCKED).apply(0.0, 0.0, 1.0, v)
        assertEquals(-30.0, Math.toDegrees(Math.atan2(v[0], v[2])), 0.1)
        // Follow: a steady pan is followed, so the view stays on the body's forward (the
        // blur of a straight line is the line, away from the ends).
        stab.correction(1_000_000, Stabilize.FOLLOW).apply(0.0, 0.0, 1.0, v)
        assertEquals(0.0, Math.toDegrees(Math.atan2(v[0], v[2])), 0.2)
        assertEquals(Mat3.IDENTITY.toString(), stab.correction(1_000_000, Stabilize.OFF).toString())
    }

    @Test fun followKeepsTheHorizonLevelWhileTheCameraRolls() {
        // Rolling at 20 °/s about the forward axis: the view must not roll with it.
        val att = Attitude.estimate(track(doubleArrayOf(0.0, 0.0, Math.toRadians(20.0)), 2.0, hz = 1000), tiltSeconds = 1e6)
        val corr = Stabilizer(att).correction(1_000_000, Stabilize.FOLLOW)
        // The view's "down" in the world is straight down: body down after the correction…
        val bodyDown = DoubleArray(3)
        corr.apply(0.0, 1.0, 0.0, bodyDown)
        // …is where world down sits in the body at that moment.
        att.at(1_000_000).conj().rotate(0.0, 1.0, 0.0, v)
        assertArrayEquals(v, bodyDown, 1e-6)
    }

    @Test fun aRestingGyroOffsetIsMeasuredAndTakenOff() {
        val att = Attitude.estimate(track(doubleArrayOf(0.004, -0.002, 0.001), 3.0))
        assertNotNull(att.bias)
        assertArrayEquals(doubleArrayOf(0.004, -0.002, 0.001), att.bias, 1e-9)
        // Nothing turns once the offset is gone.
        att.at(2_500_000).rotate(0.0, 0.0, 1.0, v)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 1.0), v, 1e-6)
        // A camera that keeps turning never counts as still.
        assertNull(Attitude.estimate(track(doubleArrayOf(0.0, 0.5, 0.0), 2.0)).bias)
    }

    @Test fun cameraInfoMotionFieldsAndTheVideoClock() {
        // Fields 24 (first frame, varint), 25 (readout, double), 28 + 29 (gyro offset), 62, 65.
        val pb = ByteArrayOutputStream().apply {
            fun varint(x: Long) {
                var v = x
                while (v >= 0x80) { write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
                write(v.toInt())
            }
            fun double(field: Int, d: Double) {
                varint((field shl 3 or 1).toLong())
                val bits = java.lang.Double.doubleToLongBits(d)
                for (i in 0 until 8) write((bits ushr (8 * i)).toInt() and 0xff)
            }
            varint(24 shl 3 or 0); varint(84_367_315)
            double(25, 21.25)
            double(28, 3.7)
            varint(29 shl 3 or 0); varint(1)
            varint(62 shl 3 or 0); varint(1)
            varint(65 shl 3 or 2); varint(5); write(byteArrayOf(0x08, 0x20, 0x10, 0xd0.toByte(), 0x0f))
        }.toByteArray()
        val info = CameraInfo(emptyList(), Protobuf.fields(pb)!!)
        assertEquals(84_367_315L, info.firstFrameTime)
        assertEquals(21.25, info.readoutMs!!, 0.0)
        assertEquals(3.7, info.gyroOffsetMs!!, 0.0)
        assertTrue(info.rawGyro)
        assertEquals(32 to 2000, info.ranges)

        // Raw counts: 1024 per g with ± 32 g; 16.384 per °/s with ± 2000 °/s.
        val raw = ImuSamples(
            ImuSamples.Format.RAW16_US, longArrayOf(84_367_315L + 3_700, 84_368_315L + 3_700),
            Array(2) { doubleArrayOf(0.0, 1024.0, 0.0, 16.384 * 90, 0.0, 0.0) },
        )
        val imu = ImuTrack.from(raw, info, Mat3.IDENTITY)!!
        assertEquals(0L, imu.timesUs[0])
        assertEquals(1000L, imu.timesUs[1])
        assertEquals(1.0, imu.accel[0][1], 1e-12)
        assertEquals(PI / 2, imu.gyro[0][0], 1e-9)
    }
}
