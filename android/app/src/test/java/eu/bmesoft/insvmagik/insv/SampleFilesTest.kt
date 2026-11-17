package eu.bmesoft.insvmagik.insv

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Runs the parsers on real .insv files when the environment variable INSV_SAMPLES names a folder
 * of them (samples never go in the repo); skipped otherwise. Prints what it found.
 *
 *   $env:INSV_SAMPLES = "$env:USERPROFILE\Downloads"; .\gradlew.bat testDebugUnitTest
 */
class SampleFilesTest {
    @Test fun samples() {
        val dir = System.getenv("INSV_SAMPLES")?.let(::File)
        assumeTrue("INSV_SAMPLES not set", dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.name.endsWith(".insv", ignoreCase = true) }.orEmpty()
        assumeTrue("no .insv in $dir", files.isNotEmpty())
        for (f in files) {
            RandomAccessFile(f, "r").use { raf ->
                val src = ChannelSource(raf.channel)
                val tr = InsvTrailer.read(src)
                assertNotNull("${f.name}: no trailer", tr)
                val cam = CameraInfo.parse(tr!!.bytes(src, InsvTrailer.CAMERA_INFO)!!)
                val cals = cam.calibrations.mapNotNull { LensCalibration.parse(it.second) }
                val imu = tr.bytes(src, InsvTrailer.IMU)?.let(ImuSamples::parse)
                val exp = tr.bytes(src, InsvTrailer.EXPOSURE)?.let(Exposures::parse)
                println("${f.name}: ${cam.model} ${cam.firmware}, ${tr.layout}, ${tr.records.size} records, " +
                    "calibrations ${cals.map { it.kind }}, imu ${imu?.format} ${imu?.count} @ ${imu?.rateHz?.toInt()} Hz, " +
                    "exposure ${exp?.count} step ${exp?.frameStepUs} us")
                assertNotNull(cam.model)
                assertTrue("${f.name}: no calibration parsed", cals.isNotEmpty())
                assertNotNull("${f.name}: no gyro", imu)
                assertNotNull("${f.name}: no exposure", exp)
            }
        }
    }
}
