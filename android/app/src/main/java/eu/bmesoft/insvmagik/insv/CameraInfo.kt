package eu.bmesoft.insvmagik.insv

/**
 * The camera-info record (0x101) is a protobuf message without a published schema. Field numbers
 * seen in the samples (ONE X2, Antigravity A1): 1 serial, 2 model, 3 firmware, 5 / 17 / 53–56
 * lens calibration strings (see [LensCalibration]).
 */
class CameraInfo(val strings: List<Pair<String, String>>) {
    val serial: String? get() = field("1")
    val model: String? get() = field("2")
    val firmware: String? get() = field("3")

    /** Every calibration string, with its field path, in file order. */
    val calibrations: List<Pair<String, String>> get() = strings.filter { LensCalibration.looksLike(it.second) }

    fun field(path: String): String? = strings.firstOrNull { it.first == path }?.second

    companion object {
        fun parse(bytes: ByteArray): CameraInfo = CameraInfo(Protobuf.strings(bytes) ?: emptyList())
    }
}

/** Just enough protobuf to list a message's printable strings without its schema. */
object Protobuf {
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
