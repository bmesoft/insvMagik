package eu.bmesoft.insvmagik.motion

import eu.bmesoft.insvmagik.view.Mat3
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rotation as a unit quaternion (w, x, y, z), in the same axes as [Mat3]: x right, y down,
 * z forward. `a * b` rotates by b first, then a, like matrices.
 */
class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conj() = Quat(w, -x, -y, -z)

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return Quat(w / n, x / n, y / n, z / n)
    }

    /** Writes this rotation applied to ([vx], [vy], [vz]) into [out]. */
    fun rotate(vx: Double, vy: Double, vz: Double, out: DoubleArray) {
        // v + 2w (u × v) + 2 u × (u × v), u = (x, y, z)
        val tx = 2 * (y * vz - z * vy)
        val ty = 2 * (z * vx - x * vz)
        val tz = 2 * (x * vy - y * vx)
        out[0] = vx + w * tx + (y * tz - z * ty)
        out[1] = vy + w * ty + (z * tx - x * tz)
        out[2] = vz + w * tz + (x * ty - y * tx)
    }

    fun toMat3(): Mat3 = Mat3(
        doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
            2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
            2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
        ),
    )

    override fun toString() = "Quat(%.4f, %.4f, %.4f, %.4f)".format(w, x, y, z)

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        /** A turn of |r| radians about the axis r. */
        fun fromRotationVector(rx: Double, ry: Double, rz: Double): Quat {
            val a = sqrt(rx * rx + ry * ry + rz * rz)
            if (a < 1e-12) return Quat(1.0, rx / 2, ry / 2, rz / 2).normalized()
            val s = sin(a / 2) / a
            return Quat(cos(a / 2), rx * s, ry * s, rz * s)
        }

        /** About y by [rad], the same sense as [Mat3.rotY] ("turn right"). */
        fun aboutY(rad: Double) = Quat(cos(rad / 2), 0.0, sin(rad / 2), 0.0)

        /** The shortest turn that takes direction [a] to direction [b] (need not be unit length). */
        fun between(a: DoubleArray, b: DoubleArray): Quat {
            val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)
            val cx = (a[1] * b[2] - a[2] * b[1]) / (na * nb)
            val cy = (a[2] * b[0] - a[0] * b[2]) / (na * nb)
            val cz = (a[0] * b[1] - a[1] * b[0]) / (na * nb)
            if (dot < -0.999999) {
                // Opposite: any axis at right angles to a.
                val ax = if (kotlin.math.abs(a[0]) < 0.9) doubleArrayOf(0.0, -a[2], a[1]) else doubleArrayOf(-a[2], 0.0, a[0])
                val n = sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2])
                return Quat(0.0, ax[0] / n, ax[1] / n, ax[2] / n)
            }
            return Quat(1 + dot, cx, cy, cz).normalized()
        }

        /** Spherical interpolation, [t] in 0..1, along the shorter way. */
        fun slerp(a: Quat, b: Quat, t: Double): Quat {
            var d = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            val s = if (d < 0) -1.0 else 1.0
            d *= s
            if (d > 0.9995) {
                return Quat(
                    a.w + t * (s * b.w - a.w), a.x + t * (s * b.x - a.x),
                    a.y + t * (s * b.y - a.y), a.z + t * (s * b.z - a.z),
                ).normalized()
            }
            val th = acos(d)
            val ka = sin((1 - t) * th) / sin(th)
            val kb = s * sin(t * th) / sin(th)
            return Quat(ka * a.w + kb * b.w, ka * a.x + kb * b.x, ka * a.y + kb * b.y, ka * a.z + kb * b.z)
        }
    }
}
