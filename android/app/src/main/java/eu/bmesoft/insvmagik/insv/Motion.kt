package eu.bmesoft.insvmagik.insv

/**
 * Gyro + accelerometer samples (record 0x300). Two layouts are known (docs/insv-format.md):
 * - [Format.F64_MS] (ONE X2): 56 bytes: u64 time in ms, accel xyz (g), gyro xyz (rad/s) as f64. 500 Hz.
 * - [Format.RAW16_US] (Antigravity A1): 20 bytes: u64 time in µs, then 6 × u16 stored with +0x8000
 *   (raw sensor counts; the scale to g and rad/s isn't known yet, and the axis order is a guess). ~1 kHz.
 * Times are on the same clock as [Exposures], which is how frames and samples are matched.
 */
class ImuSamples(val format: Format, val timesUs: LongArray, val values: Array<DoubleArray>) {
    enum class Format { F64_MS, RAW16_US }

    val count: Int get() = timesUs.size
    val spanUs: Long get() = if (count < 2) 0 else timesUs.last() - timesUs.first()
    val rateHz: Double get() = if (spanUs == 0L) 0.0 else (count - 1) * 1e6 / spanUs

    companion object {
        fun parse(bytes: ByteArray): ImuSamples? {
            val b = bytes.littleEndian()
            if (bytes.size >= 56 && bytes.size % 56 == 0) {
                val n = bytes.size / 56
                val first = DoubleArray(6) { b.getDouble(8 + it * 8) }
                if (first.all { it.isFinite() && kotlin.math.abs(it) < 64 }) {
                    val t = LongArray(n) { b.getLong(it * 56) * 1000 }
                    val v = Array(n) { i -> DoubleArray(6) { b.getDouble(i * 56 + 8 + it * 8) } }
                    return ImuSamples(Format.F64_MS, t, v)
                }
            }
            if (bytes.size >= 20 && bytes.size % 20 == 0) {
                val n = bytes.size / 20
                val t = LongArray(n) { b.getLong(it * 20) }
                val v = Array(n) { i -> DoubleArray(6) { ((b.getShort(i * 20 + 8 + it * 2).toInt() and 0xffff) - 0x8000).toDouble() } }
                return ImuSamples(Format.RAW16_US, t, v)
            }
            return null
        }
    }
}

/**
 * Exposure per frame (record 0x400): 16-byte entries of u64 time + f64 exposure in seconds.
 * The time unit differs: ms on the ONE X2, µs on the A1; it's told apart by the frame step.
 * There are more entries than frames in the video (it starts before recording), see the plan.
 */
class Exposures(val timesUs: LongArray, val seconds: DoubleArray) {
    val count: Int get() = timesUs.size

    /** Median step between entries, µs. */
    val frameStepUs: Long
        get() = if (count < 2) 0 else (1 until count).map { timesUs[it] - timesUs[it - 1] }.sorted()[(count - 1) / 2]

    companion object {
        fun parse(bytes: ByteArray): Exposures? {
            val n = bytes.size / 16
            if (n < 2) return null
            val b = bytes.littleEndian()
            val raw = LongArray(n) { b.getLong(it * 16) }
            val s = DoubleArray(n) { b.getDouble(it * 16 + 8) }
            val steps = (1 until n).map { raw[it] - raw[it - 1] }.sorted()
            // 30 fps: ~33 in ms, ~33 333 in µs. Anything under 1000 per frame must be ms.
            val toUs = if (steps[(n - 1) / 2] < 1000) 1000 else 1
            return Exposures(LongArray(n) { raw[it] * toUs }, s)
        }
    }
}
