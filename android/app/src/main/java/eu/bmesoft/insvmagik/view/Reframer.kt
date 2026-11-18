package eu.bmesoft.insvmagik.view

import java.util.stream.IntStream
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** One decoded lens picture, ARGB pixels row by row. */
class LensImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size >= width * height)
    }

    /** Bilinear sample at pixel position ([x], [y]), pixel centres at +0.5; edges clamp. */
    fun sample(x: Double, y: Double): Int {
        val fx = (x - 0.5).coerceIn(0.0, width - 1.0)
        val fy = (y - 0.5).coerceIn(0.0, height - 1.0)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val ax = fx - x0
        val ay = fy - y0
        val p00 = argb[y0 * width + x0]
        val p10 = argb[y0 * width + x1]
        val p01 = argb[y1 * width + x0]
        val p11 = argb[y1 * width + x1]
        var r = 0.0
        var g = 0.0
        var b = 0.0
        fun add(p: Int, w: Double) {
            r += (p shr 16 and 0xff) * w
            g += (p shr 8 and 0xff) * w
            b += (p and 0xff) * w
        }
        add(p00, (1 - ax) * (1 - ay))
        add(p10, ax * (1 - ay))
        add(p01, (1 - ax) * ay)
        add(p11, ax * ay)
        return rgb(r, g, b)
    }

    companion object {
        fun rgb(r: Double, g: Double, b: Double): Int =
            (0xff shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)

        private fun clamp8(v: Double) = (v + 0.5).toInt().coerceIn(0, 255)
    }
}

/**
 * The camera's lenses: for each, the rotation from the world (the camera body) into the lens's
 * frame and how that lens maps directions to its pixels.
 */
class Rig(val lenses: List<Lens>) {
    class Lens(val toLens: Mat3, val model: LensModel)
}

/** Where the output looks. Angles in degrees; see [Mat3] for the axes. */
data class ViewParams(
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    /** Field of view of the flat output along its longer side, degrees. */
    val fov: Double = 100.0,
    val projection: Projection = Projection.FLAT,
    /** Width of the cross-fade between lenses where both see the same spot. */
    val blendDeg: Double = 3.0,
) {
    enum class Projection {
        /** A normal (rectilinear) camera view: what the converter exports. */
        FLAT,

        /** The whole sphere as an equirectangular picture: shows the seam everywhere at once. */
        SPHERE,
    }

    /** View frame → world. */
    fun rotation(): Mat3 = Mat3.rotY(yaw) * Mat3.rotX(pitch) * Mat3.rotZ(roll)
}

/**
 * Renders a view of the sphere from the lens pictures, one output pixel at a time, on the CPU.
 * Stage 1 previews with it and checks the seam; the export (stage 2) does the same maths in a GL
 * shader. Each output pixel: its direction in the view → world → each lens's frame; every lens that
 * sees it contributes, weighted so the lens looking most directly at it wins, with a [ViewParams.blendDeg]
 * cross-fade where they're close.
 */
object Reframer {
    /** [motion]: world → camera body at this frame (stabilisation, `motion/Stabilizer`); identity = none. */
    fun render(
        rig: Rig, images: List<LensImage?>, view: ViewParams, width: Int, height: Int,
        out: IntArray = IntArray(width * height), motion: Mat3 = Mat3.IDENTITY,
    ): IntArray {
        val toLens = rig.lenses.map { it.toLens * motion * view.rotation() }
        val blend = Math.toRadians(max(view.blendDeg, 0.01))
        // The field of view spans the longer side (the same framing in landscape and portrait).
        val focal = max(width, height) / 2.0 / tan(Math.toRadians(view.fov.coerceIn(1.0, 179.0)) / 2)
        IntStream.range(0, height).parallel().forEach { py ->
            val dir = DoubleArray(3)
            val d = DoubleArray(3)
            val px = DoubleArray(2)
            val theta = DoubleArray(rig.lenses.size)
            for (x in 0 until width) {
                when (view.projection) {
                    ViewParams.Projection.FLAT -> {
                        val vx = x + 0.5 - width / 2.0
                        val vy = py + 0.5 - height / 2.0
                        val n = 1 / Math.sqrt(vx * vx + vy * vy + focal * focal)
                        dir[0] = vx * n
                        dir[1] = vy * n
                        dir[2] = focal * n
                    }
                    ViewParams.Projection.SPHERE -> {
                        val lon = (x + 0.5) / width * 2 * Math.PI - Math.PI
                        val lat = (py + 0.5) / height * Math.PI - Math.PI / 2
                        dir[0] = cos(lat) * sin(lon)
                        dir[1] = sin(lat)
                        dir[2] = cos(lat) * cos(lon)
                    }
                }
                out[py * width + x] = shade(rig, images, toLens, blend, dir, d, px, theta)
            }
        }
        return out
    }

    private fun shade(
        rig: Rig, images: List<LensImage?>, toLens: List<Mat3>, blend: Double,
        dir: DoubleArray, d: DoubleArray, px: DoubleArray, theta: DoubleArray,
    ): Int {
        var best = Double.MAX_VALUE
        for (i in rig.lenses.indices) {
            toLens[i].apply(dir[0], dir[1], dir[2], d)
            theta[i] = acos(d[2].coerceIn(-1.0, 1.0))
            if (images.getOrNull(i) != null && theta[i] <= rig.lenses[i].model.maxAngle) best = min(best, theta[i])
        }
        if (best == Double.MAX_VALUE) return BLACK
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var total = 0.0
        for (i in rig.lenses.indices) {
            val img = images.getOrNull(i) ?: continue
            val lens = rig.lenses[i]
            if (theta[i] > lens.model.maxAngle) continue
            // Prefer the lens looking most directly; also fade out near each lens's own edge. The
            // sum is normalised, so a spot only one lens sees keeps its full colour.
            val prefer = 1 - (theta[i] - best) / blend
            if (prefer <= 0) continue
            val edge = ((lens.model.maxAngle - theta[i]) / blend).coerceIn(1e-3, 1.0)
            val w = prefer * edge
            toLens[i].apply(dir[0], dir[1], dir[2], d)
            if (!lens.model.project(d[0], d[1], d[2], px)) continue
            val p = img.sample(px[0], px[1])
            r += (p shr 16 and 0xff) * w
            g += (p shr 8 and 0xff) * w
            b += (p and 0xff) * w
            total += w
        }
        return if (total > 0) LensImage.rgb(r / total, g / total, b / total) else BLACK
    }

    const val BLACK = 0xff000000.toInt()
}
