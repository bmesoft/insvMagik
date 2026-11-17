package eu.bmesoft.insvmagik.view

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How one fisheye lens maps a direction to a pixel of its image. Directions are unit vectors in
 * the lens's own frame (x right and y down in the image, z out of the lens); pixels are in the
 * decoded image of that lens.
 */
interface LensModel {
    /** The largest angle from the lens axis that still lands inside the image circle, radians. */
    val maxAngle: Double

    /** Writes the pixel for (x, y, z) to out[0], out[1]; false when the lens doesn't see it. */
    fun project(x: Double, y: Double, z: Double, out: DoubleArray): Boolean
}

/**
 * The simplest fisheye: distance from the centre grows linearly with the angle from the axis, and
 * the image circle ([radius]) is at [maxAngle]. Good enough to start (plan, stage 1).
 */
class Equidistant(
    private val cx: Double,
    private val cy: Double,
    private val radius: Double,
    override val maxAngle: Double,
) : LensModel {
    private val pxPerRad = radius / maxAngle

    override fun project(x: Double, y: Double, z: Double, out: DoubleArray): Boolean {
        val theta = acos(z.coerceIn(-1.0, 1.0))
        if (theta > maxAngle) return false
        val s = hypot(x, y)
        if (s < 1e-12) {
            out[0] = cx
            out[1] = cy
            return true
        }
        val k = pxPerRad * theta / s
        out[0] = cx + k * x
        out[1] = cy + k * y
        return true
    }
}

/**
 * The unified omnidirectional camera model (Mei 2007; OpenCV `omnidir`): the direction moves [xi]
 * along the axis, is projected like a pinhole, then gets radial ([k1], [k2], [k3]) and tangential
 * ([p1], [p2]) distortion. The XI calibration string looks like this model (docs/insv-format.md).
 */
class Omnidirectional(
    val cx: Double,
    val cy: Double,
    val fx: Double,
    val fy: Double,
    val xi: Double,
    val k1: Double = 0.0,
    val k2: Double = 0.0,
    val k3: Double = 0.0,
    val p1: Double = 0.0,
    val p2: Double = 0.0,
    override val maxAngle: Double,
) : LensModel {
    override fun project(x: Double, y: Double, z: Double, out: DoubleArray): Boolean {
        if (acos(z.coerceIn(-1.0, 1.0)) > maxAngle) return false
        val zs = z + xi
        if (zs <= 1e-9) return false
        val mx = x / zs
        val my = y / zs
        val r2 = mx * mx + my * my
        val radial = 1 + r2 * (k1 + r2 * (k2 + r2 * k3))
        val dx = mx * radial + 2 * p1 * mx * my + p2 * (r2 + 2 * mx * mx)
        val dy = my * radial + p1 * (r2 + 2 * my * my) + 2 * p2 * mx * my
        out[0] = fx * dx + cx
        out[1] = fy * dy + cy
        return true
    }

    /** Distance from the centre (along x) at angle [theta]: used to find where the image circle ends. */
    fun radiusAt(theta: Double): Double {
        val out = DoubleArray(2)
        project(sin(theta), 0.0, cos(theta), out)
        return out[0] - cx
    }

    companion object {
        /** The angle at which [model]'s radius reaches [radius] (searched up to 120°). */
        fun angleAtRadius(xi: Double, fx: Double, k: DoubleArray, radius: Double): Double {
            val m = Omnidirectional(0.0, 0.0, fx, fx, xi, k.getOrElse(0) { 0.0 }, k.getOrElse(1) { 0.0 }, k.getOrElse(2) { 0.0 }, maxAngle = Math.PI)
            var lo = 0.0
            var hi = Math.toRadians(120.0)
            repeat(60) {
                val mid = (lo + hi) / 2
                val r = m.radiusAt(mid)
                if (r.isNaN() || r > radius) hi = mid else lo = mid
            }
            return lo
        }
    }
}
