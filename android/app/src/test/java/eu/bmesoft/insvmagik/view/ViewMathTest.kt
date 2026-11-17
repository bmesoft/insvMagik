package eu.bmesoft.insvmagik.view

import eu.bmesoft.insvmagik.insv.LensCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ViewMathTest {
    private val v = DoubleArray(3)

    @Test fun rotationsTurnTheWayTheirNamesSay() {
        Mat3.rotY(90.0).apply(0.0, 0.0, 1.0, v) // turn right: forward goes to +x
        assertEquals(1.0, v[0], 1e-12)
        Mat3.rotX(90.0).apply(0.0, 0.0, 1.0, v) // look up: forward goes to −y
        assertEquals(-1.0, v[1], 1e-12)
        val r = Mat3.rotZ(30.0) * Mat3.rotX(-20.0) * Mat3.rotY(75.0)
        (r * r.transpose()).apply(0.3, -0.4, 0.5, v)
        assertEquals(0.3, v[0], 1e-12)
        assertEquals(-0.4, v[1], 1e-12)
        assertEquals(0.5, v[2], 1e-12)
    }

    @Test fun equidistantPutsTheAxisInTheCentreAndTheMaxAngleOnTheCircle() {
        val m = Equidistant(100.0, 120.0, 90.0, Math.toRadians(95.0))
        val p = DoubleArray(2)
        assertTrue(m.project(0.0, 0.0, 1.0, p))
        assertEquals(100.0, p[0], 1e-9)
        assertEquals(120.0, p[1], 1e-9)
        val a = Math.toRadians(95.0)
        assertTrue(m.project(sin(a), 0.0, cos(a), p))
        assertEquals(190.0, p[0], 1e-6)
        assertFalse(m.project(0.0, 0.0, -1.0, p))
    }

    @Test fun eachLensSeesALittleMoreThanAHalfSphere() {
        val pairs = listOf(
            SampleCalibrations.A1_XI to SampleCalibrations.A1_BASIC,
            SampleCalibrations.X2_XI to SampleCalibrations.X2_BASIC,
        )
        for ((xiS, basicS) in pairs) {
            val xi = LensCalibration.parse(xiS)!!
            val basic = LensCalibration.parse(basicS)!!
            val l = xi.lenses[0]
            val c = l.coefficients
            val deg = Math.toDegrees(Omnidirectional.angleAtRadius(l.xi!!, l.fx!!, doubleArrayOf(c[0], c[1], c[2]), basic.lenses[0].radius!!))
            assertTrue("$deg", deg in 92.0..105.0)
        }
    }

    @Test fun theDronesLensZeroLooksDownAndLensOneUp() {
        val cals = listOf(SampleCalibrations.A1_XI, SampleCalibrations.A1_BASIC).map { LensCalibration.parse(it)!! }
        val rig = CameraRig.from(cals, 3840)
        assertNotNull(rig)
        // A lens's axis in the world is the third row of its world → lens rotation; y is down.
        assertEquals(1.0, rig!!.lenses[0].toLens[2, 1], 0.01)
        assertEquals(-1.0, rig.lenses[1].toLens[2, 1], 0.01)
    }

    @Test fun noXiCalibrationNoRig() {
        assertNull(CameraRig.from(listOf(LensCalibration.parse(SampleCalibrations.A1_BASIC)!!), 3840))
    }

    @Test fun eachViewSamplesTheLensItLooksAt() {
        // Two lenses back to back; lens 0 all red, lens 1 all blue.
        val m = Equidistant(50.0, 50.0, 50.0, Math.toRadians(95.0))
        val rig = Rig(listOf(Rig.Lens(Mat3.IDENTITY, m), Rig.Lens(Mat3.rotX(180.0), m)))
        val red = LensImage(100, 100, IntArray(100 * 100) { 0xffff0000.toInt() })
        val blue = LensImage(100, 100, IntArray(100 * 100) { 0xff0000ff.toInt() })
        val imgs = listOf(red, blue)
        assertEquals(0xffff0000.toInt(), Reframer.render(rig, imgs, ViewParams(fov = 60.0), 9, 9)[4 * 9 + 4])
        assertEquals(0xff0000ff.toInt(), Reframer.render(rig, imgs, ViewParams(yaw = 180.0, fov = 60.0), 9, 9)[4 * 9 + 4])
        // Straight along the seam both lenses count equally: purple.
        val side = Reframer.render(rig, imgs, ViewParams(yaw = 90.0, fov = 60.0), 9, 9)[4 * 9 + 4]
        assertTrue("%08x".format(side), (side shr 16 and 0xff) in 100..155 && (side and 0xff) in 100..155)
    }
}
