package eu.bmesoft.insvmagik.view

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How well two lenses agree where both see the same directions: the correlation of their
 * brightness over evenly spread directions in the overlap. About 0.75 on the A1 sample when the
 * rig is right, near 0 when it's wrong; a number to compare settings by, not an absolute grade.
 */
object SeamCheck {
    class Result(val correlation: Double, val samples: Int)

    private val directions: List<DoubleArray> by lazy { fibonacci(120_000) }

    fun run(rig: Rig, images: List<LensImage?>): Result? {
        if (rig.lenses.size < 2) return null
        val a = images.getOrNull(0) ?: return null
        val b = images.getOrNull(1) ?: return null
        val d = DoubleArray(3)
        val p = DoubleArray(2)
        val sa = ArrayList<Double>()
        val sb = ArrayList<Double>()
        for (v in directions) {
            val va = sample(rig.lenses[0], a, v, d, p) ?: continue
            val vb = sample(rig.lenses[1], b, v, d, p) ?: continue
            sa += va
            sb += vb
        }
        if (sa.size < 200) return Result(0.0, sa.size)
        val ma = sa.average()
        val mb = sb.average()
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in sa.indices) {
            ab += (sa[i] - ma) * (sb[i] - mb)
            aa += (sa[i] - ma) * (sa[i] - ma)
            bb += (sb[i] - mb) * (sb[i] - mb)
        }
        return Result(if (aa > 0 && bb > 0) ab / sqrt(aa * bb) else 0.0, sa.size)
    }

    private fun sample(lens: Rig.Lens, img: LensImage, v: DoubleArray, d: DoubleArray, p: DoubleArray): Double? {
        lens.toLens.apply(v[0], v[1], v[2], d)
        // Keep clear of the very rim, where the picture fades.
        if (acos(d[2].coerceIn(-1.0, 1.0)) > lens.model.maxAngle - 0.002) return null
        if (!lens.model.project(d[0], d[1], d[2], p)) return null
        val c = img.sample(p[0], p[1])
        return 0.299 * (c shr 16 and 0xff) + 0.587 * (c shr 8 and 0xff) + 0.114 * (c and 0xff)
    }

    /** [n] directions spread evenly over the sphere. */
    fun fibonacci(n: Int): List<DoubleArray> {
        val golden = Math.PI * (3 - sqrt(5.0))
        return (0 until n).map { i ->
            val y = 1 - 2 * (i + 0.5) / n
            val r = sqrt(1 - y * y)
            doubleArrayOf(cos(golden * i) * r, y, sin(golden * i) * r)
        }
    }
}
