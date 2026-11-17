package eu.bmesoft.insvmagik.insv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MotionAndFilesTest {
    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun imuF64InMilliseconds() {
        val b = le(56 * 3)
        for (i in 0 until 3) {
            b.putLong(1000L + i * 2) // 500 Hz
            repeat(6) { b.putDouble(0.5) }
        }
        val s = ImuSamples.parse(b.array())!!
        assertEquals(ImuSamples.Format.F64_MS, s.format)
        assertEquals(1_000_000L, s.timesUs[0])
        assertEquals(500.0, s.rateHz, 1e-9)
    }

    @Test fun imuRaw16InMicroseconds() {
        val b = le(20 * 3)
        for (i in 0 until 3) {
            b.putLong(84_100_000L + i * 1000) // 1 kHz
            repeat(6) { b.putShort((0x8000 + 100).toShort()) }
        }
        val s = ImuSamples.parse(b.array())!!
        assertEquals(ImuSamples.Format.RAW16_US, s.format)
        assertEquals(1000.0, s.rateHz, 1e-9)
        assertEquals(100.0, s.values[0][0], 0.0)
    }

    @Test fun exposureUnitsFromStep() {
        fun entries(step: Long) = le(16 * 4).apply { for (i in 0 until 4) { putLong(5000 + i * step); putDouble(0.001) } }.array()
        assertEquals(33_000L, Exposures.parse(entries(33))!!.frameStepUs) // ms clock (ONE X2)
        assertEquals(33_370L, Exposures.parse(entries(33_370))!!.frameStepUs) // µs clock (A1)
    }

    @Test fun fileNames() {
        assertEquals(ClipFiles.Name("20231126_111301_004", 0, false), ClipFiles.parse("VID_20231126_111301_00_004.insv"))
        assertEquals(ClipFiles.Name("20231126_111301_004", 1, false), ClipFiles.parse("VID_20231126_111301_10_004.insv"))
        assertEquals(ClipFiles.Name("20231126_111301_004", 0, true), ClipFiles.parse("LRV_20231126_111301_01_004.lrv"))
        assertEquals(ClipFiles.Name("20261109_174428_032", null, false), ClipFiles.parse("VID_20261109_174428_032.insv"))
        assertNull(ClipFiles.parse("holiday.mp4"))
    }

    @Test fun pairing() {
        assertEquals(emptyList<String>(), ClipFiles.check(listOf("VID_20231126_111301_00_004.insv", "VID_20231126_111301_10_004.insv")))
        assertEquals(emptyList<String>(), ClipFiles.check(listOf("VID_20261109_174428_032.insv")))
        assertTrue(ClipFiles.check(listOf("VID_20231126_111301_00_004.insv")).single().contains("_10_"))
        assertTrue(ClipFiles.check(listOf("VID_20231126_111301_00_004.insv", "VID_20261109_174428_032.insv")).any { "different clips" in it })
    }
}
