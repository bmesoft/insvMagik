package eu.bmesoft.insvmagik.insv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TrailerTest {
    private val video = ByteArray(100) { 7 } // stands in for the MP4 before the trailer

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun footer(trailerSize: Int, version: Int = 3): ByteArray =
        ByteArray(32) + le(8) { putInt(trailerSize); putInt(version) } + InsvTrailer.MAGIC.toByteArray()

    @Test fun chainLayout() {
        val info = "info".toByteArray()
        val imu = ByteArray(56) { it.toByte() }
        val body = ByteArrayOutputStream().apply {
            write(info); write(le(6) { putShort(0x101); putInt(info.size) })
            write(imu); write(le(6) { putShort(0x300); putInt(imu.size) })
        }.toByteArray()
        val file = video + body + footer(body.size + InsvTrailer.FOOTER)

        val tr = InsvTrailer.read(ArraySource(file))!!
        assertEquals(InsvTrailer.Layout.CHAIN, tr.layout)
        assertEquals(3, tr.version)
        assertEquals(video.size.toLong(), tr.start)
        assertEquals(listOf(0x300, 0x101), tr.records.map { it.id })
        assertEquals("info", String(tr.bytes(ArraySource(file), 0x101)!!))
        assertEquals(imu.toList(), tr.bytes(ArraySource(file), 0x300)!!.toList())
    }

    @Test fun indexLayout() {
        // Records padded apart, then an index (id 0) whose ids are big-endian.
        val info = "camera".toByteArray()
        val exposure = ByteArray(32) { 1 }
        val body = ByteArray(64)
        info.copyInto(body, 4)
        exposure.copyInto(body, 20)
        val index = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x01, 0x01)); write(le(8) { putInt(info.size); putInt(4) })
            write(byteArrayOf(0x04, 0x00)); write(le(8) { putInt(exposure.size); putInt(20) })
            write(ByteArray(10)) // empty slot
        }.toByteArray()
        val trailer = body + index + le(6) { putShort(0); putInt(index.size) }
        val file = video + trailer + footer(trailer.size + InsvTrailer.FOOTER)

        val tr = InsvTrailer.read(ArraySource(file))!!
        assertEquals(InsvTrailer.Layout.INDEX, tr.layout)
        assertEquals(listOf(0x101, 0x400), tr.records.map { it.id })
        assertEquals("camera", String(tr.bytes(ArraySource(file), InsvTrailer.CAMERA_INFO)!!))
        assertEquals(32, tr.record(InsvTrailer.EXPOSURE)!!.size)
    }

    @Test fun plainMp4HasNoTrailer() {
        assertNull(InsvTrailer.read(ArraySource(ByteArray(500))))
        assertNull(InsvTrailer.read(ArraySource(ByteArray(10))))
    }
}
