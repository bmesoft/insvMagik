package eu.bmesoft.insvmagik.insv

/** One block of the trailer: its type, where its data starts in the file, and how long it is. */
data class TrailerRecord(val id: Int, val offset: Long, val size: Int)

/**
 * The data block Insta360 cameras append after the MP4 (docs/insv-format.md): camera info, lens
 * calibration, gyro, exposure times, previews. It ends with a fixed 72-byte footer:
 * 32 reserved bytes, u32 trailer size, u32 version, and the ASCII magic [MAGIC].
 *
 * Two layouts are known:
 * - [Layout.CHAIN] (ONE X2): records back to back, each followed by a 6-byte header
 *   `[u16 id][u32 size]` (little-endian), read backwards from the footer.
 * - [Layout.INDEX] (Antigravity A1): the last record has id 0 and is an index of 10-byte entries
 *   `[u16 id, big-endian][u32 size][u32 offset from trailer start]`; records are padded apart.
 */
class InsvTrailer(
    val version: Int,
    /** Where the trailer starts in the file (= where the MP4 ends). */
    val start: Long,
    val size: Long,
    val layout: Layout,
    val records: List<TrailerRecord>,
) {
    enum class Layout { CHAIN, INDEX }

    fun record(id: Int): TrailerRecord? = records.firstOrNull { it.id == id }

    fun bytes(src: ByteSource, id: Int): ByteArray? = record(id)?.let { src.read(it.offset, it.size) }

    companion object {
        const val MAGIC = "8db42d694ccc418790edff439fe026bf"
        const val FOOTER = 72

        const val CAMERA_INFO = 0x101
        const val PREVIEW = 0x200
        const val IMU = 0x300
        const val EXPOSURE = 0x400

        /** The trailer of [src], or null when the file has none (not an .insv, or cut short). */
        fun read(src: ByteSource): InsvTrailer? {
            if (src.size < FOOTER) return null
            val foot = src.read(src.size - FOOTER, FOOTER)
            if (String(foot, 40, 32, Charsets.US_ASCII) != MAGIC) return null
            val b = foot.littleEndian()
            val size = b.getInt(32).toLong() and 0xffffffffL
            val version = b.getInt(36)
            if (size < FOOTER || size > src.size) return null
            val start = src.size - size
            val end = src.size - FOOTER

            val (lastId, lastSize) = header(src, end, start) ?: return InsvTrailer(version, start, size, Layout.CHAIN, emptyList())
            return if (lastId == 0) {
                InsvTrailer(version, start, size, Layout.INDEX, index(src.read(end - 6 - lastSize, lastSize), start, size))
            } else {
                InsvTrailer(version, start, size, Layout.CHAIN, chain(src, start, end))
            }
        }

        private fun index(idx: ByteArray, start: Long, size: Long): List<TrailerRecord> {
            val b = idx.littleEndian()
            val out = mutableListOf<TrailerRecord>()
            var i = 0
            while (i + 10 <= idx.size) {
                val id = ((idx[i].toInt() and 0xff) shl 8) or (idx[i + 1].toInt() and 0xff)
                val len = b.getInt(i + 2)
                val off = b.getInt(i + 6).toLong() and 0xffffffffL
                if (len > 0 && off + len <= size) out += TrailerRecord(id, start + off, len)
                i += 10
            }
            return out
        }

        private fun chain(src: ByteSource, start: Long, footerStart: Long): List<TrailerRecord> {
            val out = mutableListOf<TrailerRecord>()
            var end = footerStart
            while (true) {
                val (id, len) = header(src, end, start) ?: break
                val dataStart = end - 6 - len
                if (len <= 0 || dataStart < start) break
                out += TrailerRecord(id, dataStart, len)
                end = dataStart
            }
            return out
        }

        /** The `[u16 id][u32 size]` header that ends at [end], if it lies inside the trailer. */
        private fun header(src: ByteSource, end: Long, start: Long): Pair<Int, Int>? {
            if (end - 6 < start) return null
            val h = src.read(end - 6, 6).littleEndian()
            return (h.getShort(0).toInt() and 0xffff) to h.getInt(2)
        }
    }
}
