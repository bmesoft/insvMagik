package eu.bmesoft.insvmagik.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewPathTest {
    private val base = ViewParams(yaw = 5.0, pitch = -3.0, fov = 90.0)
    private fun key(s: Double, yaw: Double, pitch: Double = 0.0, fov: Double = 100.0) = Keyframe((s * 1e6).toLong(), yaw, pitch, 0.0, fov)

    @Test fun noKeyframesIsTheFixedView() {
        assertEquals(base, ViewPath.at(emptyList(), 1_000_000, base))
    }

    @Test fun heldBeforeTheFirstAndAfterTheLast() {
        val keys = listOf(key(1.0, 10.0), key(3.0, 50.0))
        assertEquals(10.0, ViewPath.at(keys, 0, base).yaw, 1e-9)
        assertEquals(50.0, ViewPath.at(keys, 9_000_000, base).yaw, 1e-9)
        // Through every keyframe exactly, and the base keeps what keyframes don't set.
        assertEquals(10.0, ViewPath.at(keys, 1_000_000, base).yaw, 1e-9)
        assertEquals(50.0, ViewPath.at(keys, 3_000_000, base).yaw, 1e-9)
        assertEquals(base.blendDeg, ViewPath.at(keys, 2_000_000, base).blendDeg, 0.0)
    }

    @Test fun easesInAndOutWithoutOvershoot() {
        val keys = listOf(key(0.0, 0.0), key(2.0, 90.0), key(4.0, 90.0), key(6.0, 0.0))
        val samples = (0..600).map { ViewPath.at(keys, it * 10_000L, base).yaw }
        assertTrue("never past the keyframes", samples.all { it in -1e-9..90.0 + 1e-9 })
        // Halfway between equal keyframes it stays put: a hold, not a bump.
        assertEquals(90.0, ViewPath.at(keys, 3_000_000, base).yaw, 1e-9)
        // Slow at the start (eases in), faster in the middle.
        val early = samples[10] - samples[0]
        val middle = samples[105] - samples[95]
        assertTrue("$early < $middle", early < middle)
        // Smooth: no jump between neighbouring samples.
        assertTrue(samples.zipWithNext().all { (a, b) -> kotlin.math.abs(b - a) < 1.0 })
    }

    @Test fun yawTakesTheShortWayRound() {
        val keys = listOf(key(0.0, 170.0), key(2.0, -170.0))
        val mid = ViewPath.at(keys, 1_000_000, base).yaw
        assertTrue("$mid", kotlin.math.abs(kotlin.math.abs(mid) - 180.0) < 1e-6)
        assertTrue((0..20).all { kotlin.math.abs(ViewPath.at(keys, it * 100_000L, base).yaw) >= 169.999 })
    }

    @Test fun zoomMovesToo() {
        val keys = listOf(key(0.0, 0.0, fov = 120.0), key(1.0, 0.0, fov = 60.0))
        val f = ViewPath.at(keys, 500_000, base).fov
        assertTrue("$f", f in 60.0..120.0 && f != 120.0 && f != 60.0)
    }

    @Test fun findsTheKeyframeOnThisFrame() {
        val keys = listOf(key(1.0, 0.0), key(2.0, 0.0))
        assertEquals(keys[1], ViewPath.near(keys, 2_010_000, 16_000))
        assertNull(ViewPath.near(keys, 1_500_000, 16_000))
    }
}
