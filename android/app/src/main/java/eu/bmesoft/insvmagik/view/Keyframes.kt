package eu.bmesoft.insvmagik.view

import kotlin.math.abs

/** Where the view looks at one moment of the clip (video time, µs). */
data class Keyframe(val timeUs: Long, val yaw: Double, val pitch: Double, val roll: Double, val fov: Double)

/**
 * The view over time from a few keyframes (stage 5): the clip starts with the starting view (the
 * one set without keyframes) unless a keyframe sits at 0 s, moves through each keyframe in turn
 * and holds the last. Each of yaw, pitch, roll and field of view follows a monotone cubic
 * (Fritsch–Carlson): it eases through the keyframes and never overshoots them, so a pan that ends
 * on a keyframe stops there instead of swinging past and back. Yaw takes the short way round.
 */
object ViewPath {
    /**
     * [base] is the starting view (and supplies what keyframes don't: projection, blend); with no
     * keyframes it is the view throughout.
     */
    fun at(keys: List<Keyframe>, tUs: Long, base: ViewParams): ViewParams {
        if (keys.isEmpty()) return base
        val sorted = keys.sortedBy { it.timeUs }
        val k = if (sorted.first().timeUs > 0) listOf(Keyframe(0, base.yaw, base.pitch, base.roll, base.fov)) + sorted else sorted
        val t = k.map { it.timeUs.toDouble() }
        val x = tUs.toDouble()
        fun channel(values: List<Double>) = monotone(t, values, x)
        // Unwrap yaw so each step is at most half a turn.
        val yaws = ArrayList<Double>(k.size)
        for (key in k) {
            var y = key.yaw
            yaws.lastOrNull()?.let { prev ->
                while (y - prev > 180) y -= 360
                while (y - prev < -180) y += 360
            }
            yaws += y
        }
        var yaw = channel(yaws) % 360
        if (yaw > 180) yaw -= 360
        if (yaw <= -180) yaw += 360
        return base.copy(
            yaw = yaw,
            pitch = channel(k.map { it.pitch }),
            roll = channel(k.map { it.roll }),
            fov = channel(k.map { it.fov }),
        )
    }

    /** Monotone cubic Hermite through ([t], [v]) at [x]; held flat outside. */
    fun monotone(t: List<Double>, v: List<Double>, x: Double): Double {
        val n = t.size
        if (n == 1 || x <= t[0]) return v[0]
        if (x >= t[n - 1]) return v[n - 1]
        val d = DoubleArray(n - 1) { (v[it + 1] - v[it]) / (t[it + 1] - t[it]).coerceAtLeast(1.0) }
        // Tangents: flat at the ends and where the direction changes; otherwise the harmonic mean
        // of the neighbouring slopes, which keeps the curve between its keyframes.
        val m = DoubleArray(n)
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0) 0.0 else {
                val w1 = 2 * (t[i + 1] - t[i]) + (t[i] - t[i - 1])
                val w2 = (t[i + 1] - t[i]) + 2 * (t[i] - t[i - 1])
                (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
            }
        }
        var i = 0
        while (i < n - 2 && x > t[i + 1]) i++
        val h = (t[i + 1] - t[i]).coerceAtLeast(1.0)
        val s = ((x - t[i]) / h).coerceIn(0.0, 1.0)
        val s2 = s * s
        val s3 = s2 * s
        return (2 * s3 - 3 * s2 + 1) * v[i] + (s3 - 2 * s2 + s) * h * m[i] +
            (-2 * s3 + 3 * s2) * v[i + 1] + (s3 - s2) * h * m[i + 1]
    }

    /** The keyframe within [toleranceUs] of [tUs], if any (the frame on screen "is" a keyframe). */
    fun near(keys: List<Keyframe>, tUs: Long, toleranceUs: Long): Keyframe? =
        keys.filter { abs(it.timeUs - tUs) <= toleranceUs }.minByOrNull { abs(it.timeUs - tUs) }
}

/** The output picture's shape. The field of view is measured along the longer side. */
enum class Aspect(val width: Int, val height: Int, val label: String, val suffix: String) {
    LANDSCAPE(1920, 1080, "16:9", ""),
    PORTRAIT(1080, 1920, "9:16", "_9x16"),
    SQUARE(1080, 1080, "1:1", "_1x1"),
}
