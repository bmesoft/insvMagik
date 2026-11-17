package eu.bmesoft.insvmagik.view

import eu.bmesoft.insvmagik.insv.LensCalibration

/**
 * Builds a [Rig] from the camera's own calibration (docs/insv-format.md, "Lens calibration").
 *
 * Found on the Antigravity A1 sample by trying every reading of the angles and keeping the ones
 * where both lenses agree along the seam (`SampleRenderTest`), then picking the one that also gives
 * a level horizon:
 * - **Lens model:** the XI string is the unified omnidirectional model with distortion
 *   `k1 k2 k3 p1 p2`, in calibration-grid pixels. The image circle ends at the BASIC radius.
 * - **Rotation** (world → lens i) = `Rz(roll) · Ry(−yaw) · Rx(pitch) · B`, with the XI string's
 *   angles, and `B` = identity for lens 0, a 180° turn about x for lens 1. On the A1 this world is
 *   level: y points down, the sky is up.
 */
object CameraRig {
    /** A hand correction for lens 1 on top of the calibration, degrees (the debug sliders). */
    data class Tweak(val yaw: Double = 0.0, val pitch: Double = 0.0, val roll: Double = 0.0)

    /**
     * Null when [calibrations] has no XI shape. [lensSize] is the decoded picture's side in pixels
     * (the video's lens size, or smaller for a preview).
     */
    fun from(calibrations: List<LensCalibration>, lensSize: Int, tweak: Tweak = Tweak()): Rig? {
        val xi = calibrations.firstOrNull { it.kind == LensCalibration.Kind.XI } ?: return null
        val basic = calibrations.firstOrNull { it.kind == LensCalibration.Kind.BASIC && it.lenses.size == xi.lenses.size }
        val scale = lensSize.toDouble() / xi.gridHeight
        return Rig(
            xi.lenses.mapIndexed { i, l ->
                val c = l.coefficients + List(5) { 0.0 }
                val radius = basic?.lenses?.get(i)?.radius ?: (xi.gridHeight / 2.0)
                val maxAngle = Omnidirectional.angleAtRadius(l.xi!!, l.fx!!, doubleArrayOf(c[0], c[1], c[2]), radius)
                val (cx, cy) = xi.localCenter(i)
                val model = Omnidirectional(
                    cx * scale, cy * scale, l.fx * scale, l.fy!! * scale, l.xi,
                    k1 = c[0], k2 = c[1], k3 = c[2], p1 = c[3], p2 = c[4], maxAngle = maxAngle,
                )
                val base = if (i == 0) Mat3.IDENTITY else Mat3.rotX(180.0)
                var r = Mat3.rotZ(l.roll) * Mat3.rotY(-l.yaw) * Mat3.rotX(l.pitch) * base
                if (i == 1) r = Mat3.rotZ(tweak.roll) * Mat3.rotX(tweak.pitch) * Mat3.rotY(tweak.yaw) * r
                Rig.Lens(r, model)
            },
        )
    }
}
