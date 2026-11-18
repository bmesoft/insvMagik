package eu.bmesoft.insvmagik.insv

/**
 * The camera-info record (0x101) is a protobuf message without a published schema. Field numbers
 * seen in the samples (ONE X2, Antigravity A1): 1 serial, 2 model, 3 firmware, 5 / 17 / 53–56
 * lens calibration strings (see [LensCalibration]). The motion fields' meanings come from
 * telemetry-parser (MIT/Apache-2.0, `src/insta360/extra_info.rs`) and are checked against the
 * samples in docs/insv-format.md.
 */
class CameraInfo(val strings: List<Pair<String, String>>, val fields: List<Protobuf.Field> = emptyList()) {
    val serial: String? get() = field("1")
    val model: String? get() = field("2")
    val firmware: String? get() = field("3")

    /** Field 24: when video frame 0 was taken, on the gyro's clock and in its unit (ms or µs). */
    val firstFrameTime: Long? get() = number(24)?.varint

    /** Field 25: the rolling shutter's readout time, ms. */
    val readoutMs: Double? get() = number(25)?.double

    /** Field 28, when field 29 says it's there: a further gyro time offset, ms. */
    val gyroOffsetMs: Double? get() = if (number(29)?.varint == 1L) number(28)?.double else null

    /** Field 62: the gyro record holds raw 16-bit counts (scaled by [ranges]). */
    val rawGyro: Boolean get() = number(62)?.varint == 1L

    /** Field 65: the sensor ranges, (accelerometer ± g, gyro ± °/s); null when absent. */
    val ranges: Pair<Int, Int>?
        get() = number(65)?.bytes?.let { Protobuf.fields(it) }?.let { sub ->
            val acc = sub.firstOrNull { it.number == 1 }?.varint ?: return@let null
            val gyro = sub.firstOrNull { it.number == 2 }?.varint ?: return@let null
            acc.toInt() to gyro.toInt()
        }

    fun number(n: Int): Protobuf.Field? = fields.firstOrNull { it.number == n }

    /** Every calibration string, with its field path, in file order. */
    val calibrations: List<Pair<String, String>> get() = strings.filter { LensCalibration.looksLike(it.second) }

    fun field(path: String): String? = strings.firstOrNull { it.first == path }?.second

    companion object {
        fun parse(bytes: ByteArray): CameraInfo = CameraInfo(Protobuf.strings(bytes) ?: emptyList(), Protobuf.fields(bytes) ?: emptyList())
    }
}

/** Just enough protobuf to list a message's printable strings without its schema. */
object Protobuf {
    /** One top-level field: a varint, an 8- or 4-byte value (raw bits), or length-delimited bytes. */
    class Field(val number: Int, val wire: Int, val bits: Long, val bytes: ByteArray?) {
        val varint: Long? get() = if (wire == 0) bits else null
        val double: Double? get() = if (wire == 1) java.lang.Double.longBitsToDouble(bits) else null
        val float: Float? get() = if (wire == 5) java.lang.Float.intBitsToFloat(bits.toInt()) else null
    }

    /** The top-level fields of [bytes] in order; null if it isn't protobuf. */
    fun fields(bytes: ByteArray): List<Field>? {
        val out = mutableListOf<Field>()
        var i = 0
        while (i < bytes.size) {
            val (tag, tagLen) = varint(bytes, i) ?: return null
            i += tagLen
            val number = (tag ushr 3).toInt()
            if (number == 0) return null
            when (val wire = (tag and 7L).toInt()) {
                0 -> {
                    val (v, len) = varint(bytes, i) ?: return null
                    i += len
                    out += Field(number, wire, v, null)
                }
                1, 5 -> {
                    val n = if (wire == 1) 8 else 4
                    if (i + n > bytes.size) return null
                    var v = 0L
                    for (k in n - 1 downTo 0) v = (v shl 8) or (bytes[i + k].toLong() and 0xff)
                    i += n
                    out += Field(number, wire, v, null)
                }
                2 -> {
                    val (len, lenLen) = varint(bytes, i) ?: return null
                    i += lenLen
                    if (len < 0 || i + len > bytes.size) return null
                    out += Field(number, wire, len, bytes.copyOfRange(i, i + len.toInt()))
                    i += len.toInt()
                }
                else -> return null
            }
        }
        return out
    }

    /**
     * Printable ASCII strings in [bytes] with their field paths ("1", "5.2"). A length-delimited
     * field that isn't printable is tried as a nested message. Null if [bytes] isn't protobuf.
     */
    fun strings(bytes: ByteArray, prefix: String = "", depth: Int = 0): List<Pair<String, String>>? {
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < bytes.size) {
            val (tag, tagLen) = varint(bytes, i) ?: return null
            i += tagLen
            val field = (tag ushr 3).toInt()
            if (field == 0) return null
            when ((tag and 7L).toInt()) {
                0 -> i += (varint(bytes, i) ?: return null).second
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val (len, lenLen) = varint(bytes, i) ?: return null
                    i += lenLen
                    if (len < 0 || i + len > bytes.size) return null
                    val sub = bytes.copyOfRange(i, i + len.toInt())
                    i += len.toInt()
                    val path = if (prefix.isEmpty()) "$field" else "$prefix.$field"
                    if (sub.isNotEmpty() && sub.all { it.toInt() in 0x20..0x7e }) {
                        out += path to String(sub, Charsets.US_ASCII)
                    } else if (depth < 3) {
                        strings(sub, path, depth + 1)?.let { out += it }
                    }
                }
                else -> return null
            }
            if (i > bytes.size) return null
        }
        return out
    }

    private fun varint(b: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < b.size && shift < 64) {
            val v = b[i].toInt() and 0xff
            result = result or ((v and 0x7f).toLong() shl shift)
            i++
            if (v and 0x80 == 0) return result to (i - start)
            shift += 7
        }
        return null
    }
}
