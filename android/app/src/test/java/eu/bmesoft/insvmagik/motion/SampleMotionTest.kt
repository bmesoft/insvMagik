package eu.bmesoft.insvmagik.motion

import eu.bmesoft.insvmagik.insv.CameraInfo
import eu.bmesoft.insvmagik.insv.ChannelSource
import eu.bmesoft.insvmagik.insv.ImuSamples
import eu.bmesoft.insvmagik.insv.InsvTrailer
import eu.bmesoft.insvmagik.insv.LensCalibration
import eu.bmesoft.insvmagik.view.CameraRig
import eu.bmesoft.insvmagik.view.LensImage
import eu.bmesoft.insvmagik.view.Mat3
import eu.bmesoft.insvmagik.view.Reframer
import eu.bmesoft.insvmagik.view.Rig
import eu.bmesoft.insvmagik.view.ViewParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Checks the motion sensor axes ([SensorAxes]) and the gyro ↔ video clock on the samples.
 *
 * ONE X2, from the video itself: for every way the sensor's axes could map onto the body (24
 * turns), stabilise the clip and measure how much a locked view of the distant background still
 * changes between frames 5 apart; the known axes must win, and shifting the gyro clock must not
 * help. Needs INSV_SAMPLES (the .insv) and INSV_FRAMES with `x2_seq384.rgb`, every lens-0 frame
 * at 384²:
 *
 *   ffmpeg -i VID_20231126_111301_00_004.insv -map 0:v:0 -vf scale=384:384 -f rawvideo -pix_fmt rgb24 x2_seq384.rgb
 *
 * Antigravity A1: its sample sits still, so only gravity checks it (and INSV_RENDER gets the
 * levelled sphere to look at).
 *
 * Insta360 X5 (a motorbike ride under a clear sky): the sun is a fixed point at infinity, so with
 * the right axes it stays put in a locked world. Finds the sun in every frame in the body's axes,
 * then for each of the 24 turns how far it wanders once stabilised. Needs every frame of both
 * lenses at 256² in INSV_FRAMES:
 *
 *   ffmpeg -i 1745586212-vid_20250416_143628_00_005.insv -map 0:v:0 -vf scale=256:256 -f rawvideo -pix_fmt rgb24 x5_seq256_lens0.rgb -map 0:v:1 -vf scale=256:256 -f rawvideo -pix_fmt rgb24 x5_seq256_lens1.rgb
 */
class SampleMotionTest {
    private val samples = System.getenv("INSV_SAMPLES")?.let(::File)
    private val frames = System.getenv("INSV_FRAMES")?.let(::File)

    @Test fun x2Axes() {
        val insv = samples?.let { File(it, "VID_20231126_111301_00_004.insv") }
        val seq = frames?.let { File(it, "x2_seq384.rgb") }
        assumeTrue("samples not set", insv?.isFile == true && seq?.isFile == true)
        val (info, imu) = read(insv!!)
        val imgs = loadSeq(seq!!, 384)
        val rig = CameraRig.from(info.calibrations.mapNotNull { LensCalibration.parse(it.second) }, 384)!!
        val step = 33_367L
        val known = SensorAxes.forModel(info.model)!!

        val none = score(rig, imgs, null, step, 0, 5)
        val results = turns().map { m -> m to score(rig, imgs, Attitude.estimate(ImuTrack.from(imu, info, m)!!), step, 0, 5) }.sortedBy { it.second }
        println("X2: no stabilisation %.2f; best turns: %s".format(none, results.take(3).joinToString { (m, s) -> "%.2f %s".format(s, m) }))
        assertEquals(known.toString(), results.first().first.toString())
        assertTrue("stabilised ${results.first().second} vs $none", results.first().second < 0.6 * none)

        // The clock from the camera info is right to within a few ms.
        val att = Attitude.estimate(ImuTrack.from(imu, info, known)!!)
        val shifts = (-20..20 step 5).associateWith { score(rig, imgs, att, step, it * 1000L, 5) }
        println("X2 clock shifts (ms → score): " + shifts.entries.joinToString { "%+d %.3f".format(it.key, it.value) })
        assertTrue(shifts.minByOrNull { it.value }!!.key in -5..5)

        // To look at: the sphere at a few moments, following the camera with a level horizon.
        val out = System.getenv("INSV_RENDER")?.let(::File) ?: return
        val stab = Stabilizer(att)
        for (k in listOf(0, 57, 114)) {
            val view = ViewParams(projection = ViewParams.Projection.SPHERE)
            save(File(out, "x2_follow_$k.ppm"), 1024, 512, Reframer.render(rig, listOf(imgs[k], null), view, 1024, 512, motion = stab.correction(k * step, Stabilize.FOLLOW)))
        }
    }

    @Test fun x5Axes() {
        val insv = samples?.let { File(it, "1745586212-vid_20250416_143628_00_005.insv") }
        val l0 = frames?.let { File(it, "x5_seq256_lens0.rgb") }
        val l1 = frames?.let { File(it, "x5_seq256_lens1.rgb") }
        assumeTrue("samples not set", insv?.isFile == true && l0?.isFile == true && l1?.isFile == true)
        val (info, imu) = read(insv!!)
        val rig = CameraRig.from(info.calibrations.mapNotNull { LensCalibration.parse(it.second) }, 256)!!
        val seq0 = loadSeq(l0!!, 256)
        val seq1 = loadSeq(l1!!, 256)
        val step = 40_000L
        val sun = seq0.indices.map { k -> sunDirection(rig, seq0[k], seq1[k]) }
        val found = sun.count { it != null }
        println("X5: sun found in $found of ${sun.size} frames")
        assumeTrue(found > sun.size / 2)

        val results = turns().map { m ->
            val att = Attitude.estimate(ImuTrack.from(imu, info, m)!!)
            m to sunSpread(att, sun, step)
        }.sortedBy { it.second }
        println("X5 sun spread (degrees): no stabilisation %.1f; best turns: %s".format(
            sunSpread(null, sun, step), results.take(4).joinToString { (m, s) -> "%.1f %s".format(s, m) },
        ))
        SensorAxes.forModel(info.model)?.let { known ->
            assertEquals(known.toString(), results.first().first.toString())
            val att = Attitude.estimate(ImuTrack.from(imu, info, known)!!)
            val shifts = (-20..20 step 5).associateWith { sunSpread(att, sun, step, it * 1000L) }
            println("X5 clock shifts (ms → spread): " + shifts.entries.joinToString { "%+d %.2f".format(it.key, it.value) })
        }

        // To look at: the levelled sphere at a few moments for the two best turns.
        val out = System.getenv("INSV_RENDER")?.let(::File) ?: return
        for ((rank, pair) in results.take(2).withIndex()) {
            val stab = Stabilizer(Attitude.estimate(ImuTrack.from(imu, info, pair.first)!!))
            for (k in listOf(0, 120, 240, 360, 480, 600)) {
                val view = ViewParams(projection = ViewParams.Projection.SPHERE)
                save(File(out, "x5_r${rank}_$k.ppm"), 512, 256, Reframer.render(rig, listOf(seq0[k], seq1[k]), view, 512, 256, motion = stab.correction(k * step, Stabilize.FOLLOW)))
            }
        }
    }

    /**
     * The sun's height above the levelled horizon over the X5 clip. It hardly moves in 24 s, so
     * any wobble is a levelling error (along the sun's direction; where the clip was shot isn't
     * known, so its true height isn't either). The forward filter keeps it within ~2.3° (RMS);
     * a two-sided long-window gravity estimate drifted 18° → 38° (the X5's gyro offset, never
     * measurable on a moving bike, piles up), and field 31 taken as that offset made it worse.
     */
    @Test fun x5SunHeight() {
        val insv = samples?.let { File(it, "1745586212-vid_20250416_143628_00_005.insv") }
        val l0 = frames?.let { File(it, "x5_seq256_lens0.rgb") }
        val l1 = frames?.let { File(it, "x5_seq256_lens1.rgb") }
        assumeTrue("samples not set", insv?.isFile == true && l0?.isFile == true && l1?.isFile == true)
        val (info, imu) = read(insv!!)
        val rig = CameraRig.from(info.calibrations.mapNotNull { LensCalibration.parse(it.second) }, 256)!!
        val seq0 = loadSeq(l0!!, 256)
        val seq1 = loadSeq(l1!!, 256)
        val att = Attitude.estimate(ImuTrack.from(imu, info, SensorAxes.forModel(info.model)!!)!!)
        val heights = seq0.indices.mapNotNull { k ->
            val s = sunDirection(rig, seq0[k], seq1[k]) ?: return@mapNotNull null
            val w = DoubleArray(3)
            att.at(k * 40_000L).rotate(s[0], s[1], s[2], w)
            k to Math.toDegrees(kotlin.math.asin(-w[1].coerceIn(-1.0, 1.0)))
        }
        val hs = heights.map { it.second }
        val mean = hs.average()
        val sd = kotlin.math.sqrt(hs.sumOf { (it - mean) * (it - mean) } / hs.size)
        println("X5 sun height: mean %.1f°, spread %.1f°, range %.1f..%.1f; per 2 s: %s".format(
            mean, sd, hs.min(), hs.max(),
            heights.groupBy { it.first / 50 }.entries.joinToString { (b, v) -> "${b * 2}s %.1f".format(v.map { it.second }.average()) },
        ))
        assertTrue("sun height spread $sd°", sd < 4.0)
    }

    /** Where the sun is in the body's axes: the middle of the biggest saturated blob, or null. */
    private fun sunDirection(rig: Rig, a: LensImage, b: LensImage): DoubleArray? {
        val w = 512
        val h = 256
        val px = Reframer.render(rig, listOf(a, b), ViewParams(projection = ViewParams.Projection.SPHERE), w, h)
        val bright = BooleanArray(w * h) { luma(px[it]) >= 250 }
        val seen = BooleanArray(w * h)
        var best: List<Int> = emptyList()
        for (start in bright.indices) {
            if (!bright[start] || seen[start]) continue
            val blob = ArrayList<Int>()
            val stack = ArrayDeque<Int>().apply { add(start) }
            seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                blob += i
                val x = i % w
                val y = i / w
                for ((nx, ny) in listOf((x + 1) % w to y, (x + w - 1) % w to y, x to y + 1, x to y - 1)) {
                    if (ny !in 0 until h) continue
                    val j = ny * w + nx
                    if (bright[j] && !seen[j]) { seen[j] = true; stack.add(j) }
                }
            }
            if (blob.size > best.size) best = blob
        }
        if (best.size < 8 || best.size > 2000) return null
        val d = DoubleArray(3)
        for (i in best) {
            val lon = ((i % w) + 0.5) / w * 2 * Math.PI - Math.PI
            val lat = ((i / w) + 0.5) / h * Math.PI - Math.PI / 2
            d[0] += kotlin.math.cos(lat) * kotlin.math.sin(lon)
            d[1] += kotlin.math.sin(lat)
            d[2] += kotlin.math.cos(lat) * kotlin.math.cos(lon)
        }
        val n = kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        return doubleArrayOf(d[0] / n, d[1] / n, d[2] / n)
    }

    /**
     * RMS angle (degrees) of the sun around its mean direction in the stabilised world, within
     * 2-second windows (a slow drift of the locked heading doesn't count), averaged.
     */
    private fun sunSpread(att: Attitude?, sun: List<DoubleArray?>, step: Long, shiftUs: Long = 0): Double =
        sun.indices.chunked(50).map { idx -> spreadOf(att, idx.map { it to sun[it] }, step, shiftUs) }.filter { !it.isNaN() }.average()

    private fun spreadOf(att: Attitude?, sun: List<Pair<Int, DoubleArray?>>, step: Long, shiftUs: Long): Double {
        val world = sun.mapNotNull { (k, s) ->
            s ?: return@mapNotNull null
            val out = DoubleArray(3)
            (att?.at(k * step + shiftUs) ?: Quat.IDENTITY).rotate(s[0], s[1], s[2], out)
            out
        }
        if (world.size < 10) return Double.NaN
        val m = DoubleArray(3)
        world.forEach { for (j in 0..2) m[j] += it[j] }
        val n = kotlin.math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        val sq = world.sumOf { v ->
            val c = ((v[0] * m[0] + v[1] * m[1] + v[2] * m[2]) / n).coerceIn(-1.0, 1.0)
            Math.toDegrees(kotlin.math.acos(c)).let { it * it }
        }
        return kotlin.math.sqrt(sq / world.size)
    }

    @Test fun a1Level() {
        val insv = samples?.let { File(it, "VID_20261109_174428_032.insv") }
        assumeTrue("samples not set", insv?.isFile == true)
        val (info, imu) = read(insv!!)
        val att = Attitude.estimate(ImuTrack.from(imu, info, SensorAxes.forModel(info.model)!!)!!)
        // The drone sits on a table: the body's up is ~10° off the world's, and the world comes out level.
        val up = DoubleArray(3)
        att.at(4_000_000).conj().rotate(0.0, -1.0, 0.0, up)
        println("A1: world up in the body %s, resting gyro offset %s rad/s".format(up.joinToString { "%.3f".format(it) }, att.bias?.joinToString { "%.4f".format(it) }))
        assertTrue(up[1] < -0.97 && up[1] > -0.995)
        assertTrue(att.bias != null)

        val out = System.getenv("INSV_RENDER")?.let(::File) ?: return
        if (frames?.let { File(it, "a1_lens0.rgb").isFile } != true) return
        val imgs = listOf(loadSeq(File(frames, "a1_lens0.rgb"), 3840)[0], loadSeq(File(frames, "a1_lens1.rgb"), 3840)[0])
        val rig = CameraRig.from(info.calibrations.mapNotNull { LensCalibration.parse(it.second) }, 3840)!!
        val view = ViewParams(projection = ViewParams.Projection.SPHERE)
        save(File(out, "a1_sphere_body.ppm"), 1024, 512, Reframer.render(rig, imgs, view, 1024, 512))
        save(File(out, "a1_sphere_level.ppm"), 1024, 512, Reframer.render(rig, imgs, view, 1024, 512, motion = att.at(4_000_000).conj().toMat3()))
    }

    private fun save(f: File, w: Int, h: Int, argb: IntArray) {
        f.parentFile?.mkdirs()
        val bytes = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            bytes[i * 3] = (argb[i] shr 16).toByte()
            bytes[i * 3 + 1] = (argb[i] shr 8).toByte()
            bytes[i * 3 + 2] = argb[i].toByte()
        }
        f.outputStream().use { it.write("P6 $w $h 255\n".toByteArray()); it.write(bytes) }
    }

    /**
     * Mean luma change between frames [lag] apart, in locked views aimed at the distant background
     * of lens 0 (the lift tower near the top of the circle, the trees near the bottom; the rider
     * in the middle moves with the camera and would only add noise).
     */
    private fun score(rig: Rig, imgs: List<LensImage>, att: Attitude?, step: Long, shiftUs: Long, lag: Int): Double =
        listOf(75.0 to -95.0, 78.0 to 85.0).sumOf { (theta, phi) -> scoreAt(rig, imgs, att, step, shiftUs, lag, theta, phi) } / 2

    /** [theta]: degrees from lens 0's axis; [phi]: direction in the lens picture (0 = right, −90 = up). */
    private fun scoreAt(rig: Rig, imgs: List<LensImage>, att: Attitude?, step: Long, shiftUs: Long, lag: Int, theta: Double, phi: Double): Double {
        val t = Math.toRadians(theta)
        val p = Math.toRadians(phi)
        val axis = DoubleArray(3)
        rig.lenses[0].toLens.transpose().apply(kotlin.math.sin(t) * kotlin.math.cos(p), kotlin.math.sin(t) * kotlin.math.sin(p), kotlin.math.cos(t), axis)
        val world = DoubleArray(3)
        (att?.at(shiftUs) ?: Quat.IDENTITY).rotate(axis[0], axis[1], axis[2], world)
        val view = ViewParams(
            yaw = Math.toDegrees(atan2(world[0], world[2])),
            pitch = Math.toDegrees(atan2(-world[1], hypot(world[0], world[2]))),
            fov = 45.0,
        )
        val w = 192
        val h = 108
        val renders = imgs.mapIndexed { k, img ->
            val motion = att?.at(k * step + shiftUs)?.conj()?.toMat3() ?: Mat3.IDENTITY
            Reframer.render(rig, listOf(img, null), view, w, h, motion = motion)
        }
        var sum = 0.0
        var count = 0L
        for (k in lag until renders.size) {
            val px = renders[k]
            val q = renders[k - lag]
            for (i in px.indices) {
                if (px[i] == Reframer.BLACK || q[i] == Reframer.BLACK) continue
                sum += abs(luma(px[i]) - luma(q[i]))
                count++
            }
        }
        return if (count == 0L) Double.NaN else sum / count
    }

    private fun luma(p: Int) = 0.299 * (p shr 16 and 0xff) + 0.587 * (p shr 8 and 0xff) + 0.114 * (p and 0xff)

    /** The 24 turns that map axes onto axes (signed permutations with determinant +1). */
    private fun turns(): List<Mat3> {
        val perms = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
        val out = mutableListOf<Mat3>()
        for (p in perms) for (s in 0 until 8) {
            val m = DoubleArray(9)
            for (r in 0..2) m[r * 3 + p[r]] = if (s shr r and 1 == 1) -1.0 else 1.0
            val mat = Mat3(m)
            val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
            if (det > 0) out += mat
        }
        return out
    }

    private fun read(f: File): Pair<CameraInfo, ImuSamples> = RandomAccessFile(f, "r").use { raf ->
        val src = ChannelSource(raf.channel)
        val tr = InsvTrailer.read(src)!!
        CameraInfo.parse(tr.bytes(src, InsvTrailer.CAMERA_INFO)!!) to ImuSamples.parse(tr.bytes(src, InsvTrailer.IMU)!!)!!
    }

    private fun loadSeq(f: File, side: Int): List<LensImage> {
        val bytes = f.readBytes()
        val per = side * side * 3
        return List(bytes.size / per) { k ->
            LensImage(side, side, IntArray(side * side) { i ->
                val o = k * per + i * 3
                (0xff shl 24) or ((bytes[o].toInt() and 0xff) shl 16) or ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o + 2].toInt() and 0xff)
            })
        }
    }
}
