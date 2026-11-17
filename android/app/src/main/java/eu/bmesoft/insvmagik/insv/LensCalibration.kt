package eu.bmesoft.insvmagik.insv

/**
 * A lens calibration string from the camera-info record ("offset" in Insta360 terms):
 * `<lens count>_<numbers per lens…>_<trailing…>`, all joined with `_`. Positions are pixels on a
 * calibration grid of both lenses side by side ([gridWidth] × [gridHeight], each lens a
 * [gridHeight] square; lens 1's centre x is offset by one square). The video stores each lens at
 * its own size (e.g. 2880 for a 3040 grid square), so scale by `videoLensSize / gridHeight`.
 *
 * Three shapes are seen (docs/insv-format.md); what the numbers mean is partly guessed:
 * - [Kind.BASIC]: 6 per lens: radius (image circle, px), cx, cy, yaw, pitch, roll (degrees);
 *   then grid width, height and one more number.
 * - [Kind.POLY]: 16 per lens: the 6 above, translation xyz, 4 coefficients (the first is 1.0:
 *   likely a radial polynomial), grid width, height, a type number; then one trailing number.
 * - [Kind.XI]: 19 per lens: xi (≈1.5–1.7, like the unified "omnidirectional" camera model), fx, fy,
 *   cx, cy, yaw, pitch, roll, translation xyz, 5 distortion coefficients, grid width, height, a type
 *   number; then one trailing number.
 */
data class LensCalibration(
    val kind: Kind,
    val lenses: List<Lens>,
    val gridWidth: Int,
    val gridHeight: Int,
    val raw: String,
) {
    enum class Kind { BASIC, POLY, XI }

    data class Lens(
        val cx: Double,
        val cy: Double,
        val yaw: Double,
        val pitch: Double,
        val roll: Double,
        /** BASIC and POLY: the first number, about the image circle's radius in grid pixels. */
        val radius: Double? = null,
        /** XI only. */
        val xi: Double? = null,
        val fx: Double? = null,
        val fy: Double? = null,
        val translation: List<Double> = emptyList(),
        val coefficients: List<Double> = emptyList(),
    )

    /** Lens [i]'s centre within its own square (lens 1 sits one square to the right). */
    fun localCenter(i: Int): Pair<Double, Double> = (lenses[i].cx - i * gridHeight) to lenses[i].cy

    companion object {
        private val SHAPE = Regex("""^\d+(_-?\d+(\.\d+)?)+$""")

        fun looksLike(s: String): Boolean = SHAPE.matches(s)

        /** Null if [s] isn't one of the known shapes. */
        fun parse(s: String): LensCalibration? {
            if (!looksLike(s)) return null
            val t = s.split('_')
            val n = t[0].toIntOrNull()?.takeIf { it in 1..4 } ?: return null
            val v = t.drop(1).map { it.toDoubleOrNull() ?: return null }
            return when (v.size) {
                6 * n + 3 -> LensCalibration(
                    Kind.BASIC,
                    (0 until n).map { i ->
                        val o = i * 6
                        Lens(cx = v[o + 1], cy = v[o + 2], yaw = v[o + 3], pitch = v[o + 4], roll = v[o + 5], radius = v[o])
                    },
                    v[6 * n].toInt(), v[6 * n + 1].toInt(), s,
                )
                16 * n + 1 -> LensCalibration(
                    Kind.POLY,
                    (0 until n).map { i ->
                        val o = i * 16
                        Lens(
                            cx = v[o + 1], cy = v[o + 2], yaw = v[o + 3], pitch = v[o + 4], roll = v[o + 5], radius = v[o],
                            translation = v.subList(o + 6, o + 9), coefficients = v.subList(o + 9, o + 13),
                        )
                    },
                    v[13].toInt(), v[14].toInt(), s,
                )
                19 * n + 1 -> LensCalibration(
                    Kind.XI,
                    (0 until n).map { i ->
                        val o = i * 19
                        Lens(
                            xi = v[o], fx = v[o + 1], fy = v[o + 2], cx = v[o + 3], cy = v[o + 4],
                            yaw = v[o + 5], pitch = v[o + 6], roll = v[o + 7],
                            translation = v.subList(o + 8, o + 11), coefficients = v.subList(o + 11, o + 16),
                        )
                    },
                    v[16].toInt(), v[17].toInt(), s,
                )
                else -> null
            }
        }
    }
}
