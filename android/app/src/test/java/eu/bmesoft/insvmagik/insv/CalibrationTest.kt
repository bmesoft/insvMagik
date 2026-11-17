package eu.bmesoft.insvmagik.insv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Strings copied from the two sample files (ONE X2 and Antigravity A1). */
class CalibrationTest {
    private val x2Basic = "2_1482.830_1533.470_1512.940_-0.674_-0.935_-178.735_1482.860_4562.080_1522.540_0.713_-1.115_-0.187_6080_3040_3113"
    private val x2Poly = "2_1481.480_1532.720_1513.060_-0.654_-1.032_-178.798_0.000000_0.000000_0.000000_1.00000000_-0.15671770_0.27667934_-0.11344107_6080_3040_41_1481.690_4562.780_1522.590_0.703_-1.243_-0.124_0.000524_-0.001171_-0.022042_1.00000000_-0.15269160_0.27088678_-0.11151635_6080_3040_41_134144"
    private val x2Xi = "2_1.728590_2323.540_2323.480_1533.620_1509.970_-0.739_-1.139_-178.917_0.000000_0.000000_0.000000_0.21823317_-0.07998786_-1.07372952_0.00110727_-0.00043525_6080_3040_41_1.728590_2323.380_2323.270_4562.850_1518.730_0.651_-1.343_-0.009_0.000163_-0.000778_-0.021640_0.24729535_-0.27422050_-0.74841899_0.00132278_-0.00016728_6080_3040_41_199680"
    private val a1Basic = "2_2569.084_2600.496_2600.192_179.658_-0.291_88.881_2569.970_7847.152_2599.376_179.943_-0.424_89.686_10496_5248_11368"

    @Test fun basic() {
        val c = LensCalibration.parse(x2Basic)!!
        assertEquals(LensCalibration.Kind.BASIC, c.kind)
        assertEquals(6080, c.gridWidth)
        assertEquals(3040, c.gridHeight)
        assertEquals(2, c.lenses.size)
        assertEquals(1482.83, c.lenses[0].radius!!, 1e-9)
        assertEquals(-178.735, c.lenses[0].roll, 1e-9)
        // Lens 1 sits in the right half of the grid.
        assertEquals(4562.08 - 3040, c.localCenter(1).first, 1e-9)
    }

    @Test fun poly() {
        val c = LensCalibration.parse(x2Poly)!!
        assertEquals(LensCalibration.Kind.POLY, c.kind)
        assertEquals(3040, c.gridHeight)
        assertEquals(listOf(1.0, -0.1567177, 0.27667934, -0.11344107), c.lenses[0].coefficients)
        assertEquals(1522.59, c.lenses[1].cy, 1e-9)
    }

    @Test fun xi() {
        val c = LensCalibration.parse(x2Xi)!!
        assertEquals(LensCalibration.Kind.XI, c.kind)
        assertEquals(1.72859, c.lenses[0].xi!!, 1e-9)
        assertEquals(2323.54, c.lenses[0].fx!!, 1e-9)
        assertEquals(5, c.lenses[1].coefficients.size)
        assertEquals(4562.85, c.lenses[1].cx, 1e-9)
    }

    @Test fun droneGrid() {
        val c = LensCalibration.parse(a1Basic)!!
        assertEquals(10496, c.gridWidth)
        assertEquals(5248, c.gridHeight)
        assertEquals(88.881, c.lenses[0].roll, 1e-9)
    }

    @Test fun notCalibration() {
        assertNull(LensCalibration.parse("vivid"))
        assertNull(LensCalibration.parse("2_1_2_3"))
    }

    @Test fun cameraInfoFields() {
        fun field(n: Int, s: String) = byteArrayOf(((n shl 3) or 2).toByte(), s.length.toByte()) + s.toByteArray()
        val nested = field(1, "inner")
        val msg = field(1, "IXSE33") + field(2, "Insta360 ONE X2") + field(3, "v1.0.62_build2") +
            byteArrayOf((4 shl 3).toByte(), 0x96.toByte(), 0x01) + // varint 150
            field(5, x2Basic) +
            byteArrayOf(((9 shl 3) or 2).toByte(), nested.size.toByte()) + nested
        val c = CameraInfo.parse(msg)
        assertEquals("Insta360 ONE X2", c.model)
        assertEquals("v1.0.62_build2", c.firmware)
        assertEquals(listOf("5" to x2Basic), c.calibrations)
        assertEquals("inner", c.field("9.1"))
    }
}
