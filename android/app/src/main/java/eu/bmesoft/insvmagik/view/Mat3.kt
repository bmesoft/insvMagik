package eu.bmesoft.insvmagik.view

import kotlin.math.cos
import kotlin.math.sin

/**
 * A 3×3 rotation, row-major. Every direction in this package uses the camera convention:
 * **x right, y down, z forward** (right-handed), so a fisheye image's x and y line up with x and y.
 */
class Mat3(private val m: DoubleArray) {
    init {
        require(m.size == 9)
    }

    operator fun get(row: Int, col: Int): Double = m[row * 3 + col]

    operator fun times(o: Mat3): Mat3 = Mat3(
        DoubleArray(9) { i ->
            val r = i / 3
            val c = i % 3
            m[r * 3] * o.m[c] + m[r * 3 + 1] * o.m[3 + c] + m[r * 3 + 2] * o.m[6 + c]
        },
    )

    fun transpose(): Mat3 = Mat3(DoubleArray(9) { i -> m[(i % 3) * 3 + i / 3] })

    /** Writes this · (x, y, z) into [out] (size ≥ 3). */
    fun apply(x: Double, y: Double, z: Double, out: DoubleArray) {
        out[0] = m[0] * x + m[1] * y + m[2] * z
        out[1] = m[3] * x + m[4] * y + m[5] * z
        out[2] = m[6] * x + m[7] * y + m[8] * z
    }

    override fun toString(): String = m.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) }

    companion object {
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** About x. Positive turns forward (z) towards up (−y): "look up". */
        fun rotX(deg: Double): Mat3 {
            val a = Math.toRadians(deg)
            val c = cos(a)
            val s = sin(a)
            return Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c))
        }

        /** About y. Positive turns forward (z) towards right (+x): "turn right". */
        fun rotY(deg: Double): Mat3 {
            val a = Math.toRadians(deg)
            val c = cos(a)
            val s = sin(a)
            return Mat3(doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c))
        }

        /** About z, the viewing axis: rolls the picture. */
        fun rotZ(deg: Double): Mat3 {
            val a = Math.toRadians(deg)
            val c = cos(a)
            val s = sin(a)
            return Mat3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        }
    }
}
