package eu.bmesoft.insvmagik.view

import eu.bmesoft.insvmagik.insv.LensCalibration
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * Renders the sample clips' frames on the PC and checks the seam. Needs lens pictures as raw
 * RGB24 squares in the folder named by INSV_FRAMES (samples never go in the repo); skipped
 * otherwise. Writes PPM pictures to INSV_RENDER when it's set.
 *
 *   ffmpeg -ss 4 -i VID_20261109_174428_032.insv -map 0:v:0 -frames:v 1 -f rawvideo -pix_fmt rgb24 a1_lens0.rgb
 *   ffmpeg -ss 4 -i VID_20261109_174428_032.insv -map 0:v:1 -frames:v 1 -f rawvideo -pix_fmt rgb24 a1_lens1.rgb
 *   ffmpeg -ss 2 -i VID_20231126_111301_00_004.insv -map 0:v:0 -frames:v 1 -f rawvideo -pix_fmt rgb24 x2_lens0.rgb
 */
class SampleRenderTest {
    private val frames = System.getenv("INSV_FRAMES")?.let(::File)
    private val renders = System.getenv("INSV_RENDER")?.let(::File)

    @Test fun a1() {
        assumeTrue("INSV_FRAMES not set", frames != null && File(frames, "a1_lens0.rgb").isFile)
        val imgs = listOf(load(File(frames, "a1_lens0.rgb")), load(File(frames, "a1_lens1.rgb")))
        val cals = listOf(SampleCalibrations.A1_BASIC, SampleCalibrations.A1_XI).map { LensCalibration.parse(it)!! }
        val rig = CameraRig.from(cals, imgs[0].width)!!
        val seam = SeamCheck.run(rig, imgs)!!
        println("A1 seam correlation %.4f over %d directions".format(seam.correlation, seam.samples))
        assertTrue("seam correlation ${seam.correlation}", seam.correlation > 0.7)
        render("a1", rig, imgs)
    }

    @Test fun x2LensZero() {
        assumeTrue("INSV_FRAMES not set", frames != null && File(frames, "x2_lens0.rgb").isFile)
        val imgs = listOf(load(File(frames, "x2_lens0.rgb")), null)
        val cals = listOf(SampleCalibrations.X2_BASIC, SampleCalibrations.X2_XI).map { LensCalibration.parse(it)!! }
        render("x2", CameraRig.from(cals, imgs[0]!!.width)!!, imgs)
    }

    private fun render(name: String, rig: Rig, imgs: List<LensImage?>) {
        val dir = renders ?: return
        save(File(dir, "${name}_sphere.ppm"), 2048, 1024, Reframer.render(rig, imgs, ViewParams(projection = ViewParams.Projection.SPHERE), 2048, 1024))
        for (yaw in listOf(0, 90, 180, 270)) {
            save(File(dir, "${name}_flat_$yaw.ppm"), 960, 540, Reframer.render(rig, imgs, ViewParams(yaw = yaw.toDouble(), fov = 110.0), 960, 540))
        }
    }

    private fun load(f: File): LensImage {
        val bytes = f.readBytes()
        val side = sqrt(bytes.size / 3.0).toInt()
        val argb = IntArray(side * side) { i ->
            (0xff shl 24) or ((bytes[i * 3].toInt() and 0xff) shl 16) or ((bytes[i * 3 + 1].toInt() and 0xff) shl 8) or (bytes[i * 3 + 2].toInt() and 0xff)
        }
        return LensImage(side, side, argb)
    }

    /** Binary PPM; `ffmpeg -i x.ppm x.jpg` to view. */
    private fun save(f: File, w: Int, h: Int, argb: IntArray) {
        f.parentFile?.mkdirs()
        val out = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            out[i * 3] = (argb[i] shr 16).toByte()
            out[i * 3 + 1] = (argb[i] shr 8).toByte()
            out[i * 3 + 2] = argb[i].toByte()
        }
        f.outputStream().use {
            it.write("P6\n$w $h\n255\n".toByteArray())
            it.write(out)
        }
    }
}
