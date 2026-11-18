# insvMagik

An Android tool that turns 360° recordings from Insta360 cameras (`.insv`) into normal, flat
**reframed MP4** videos right on the phone: pick the clip's files, choose where to look, export
to the gallery (and edit further in mobiCut). No Insta360 app, no PC.

**Status:** stage 4 of the [plan](docs/plan.md): a probe that reads the files and measures whether
the phone can decode them, and a Frame tab that shows a reframed view of one frame and exports
the whole clip through it as a 1080p MP4, stabilised by the camera's gyro with a level horizon
(ONE X2, Antigravity A1 and X5 so far). Internal APK only; this repo is private for now and may go public if the converter works out.

Part of bmesoft; the repos sit side by side:

```
bmesoft/
  mobicut/      Android video editor (bmesoft/mobicut)
  insvMagik/    this repo (bmesoft/insvMagik)
  www/          bmesoft.eu
  tools/        local tools portal
```

## Layout

| Path | What |
|---|---|
| [docs/plan.md](docs/plan.md) | the converter plan: pipeline, stages, slow phones, open questions |
| [docs/insv-format.md](docs/insv-format.md) | what we know about `.insv` (from the samples) |
| `android/` | the app, a self-contained Gradle project (open this folder in Android Studio) |
| `android/app/src/main/java/eu/bmesoft/insvmagik/insv/` | the file format: trailer, camera info, calibration, gyro, exposure (pure Kotlin, unit-tested) |
| `…/view/` | the reframing maths: rotations, lens models, the camera rig from its calibration, the CPU renderer, the seam check (pure Kotlin, unit-tested) |
| `…/motion/` | stabilisation: the gyro in the rig's axes and on the video clock, the orientation per frame, follow / locked views (pure Kotlin, unit-tested) |
| `…/media/` | Android media: tracks, decoder limits, the decode test, one-frame grabbing, the export (GL shader + encoder) |
| `…/ui/` | Compose screen |
| [tools/install.ps1](tools/install.ps1) | build, install on the USB-connected phone, launch |
| [tools/insv-probe.py](tools/insv-probe.py) | the same file analysis on the PC (Python, standard library) |

Kotlin, Jetpack Compose, `MediaExtractor` / `MediaCodec`. minSdk 29 (Android 10), targetSdk 36.
Same Gradle plugin versions as mobiCut.

## Build and try

```powershell
.\tools\install.ps1             # build, install on the phone (USB debugging), start
.\tools\install.ps1 -ApkOnly    # just the APK: android\app\build\outputs\apk\debug\app-debug.apk
```

In the app, **Probe** tab: **Choose files** (both `…_00_…` and `…_10_…` of a clip, or the single
file of newer cameras), read the report, **Decode test**, **Copy** and paste the report where it's
needed. **Frame** tab: the middle of the clip as a flat view; the time slider picks another frame,
the sliders look around, **Sphere** shows the whole panorama, **Motion** picks the stabilisation
(**Follow**: level horizon, the view turns smoothly with the camera; **Locked**: level, always
the same direction; **Off**: fixed to the camera body), **Export this view (1080p)** writes the
clip to `Movies/insvMagik`, **Copy** gives the settings and the export result.

The `.insv` must be on the phone first: USB mass-storage mode of the camera or a card reader
(see the plan's open questions).

## Tests

```powershell
cd android
.\gradlew.bat testDebugUnitTest                                   # parsers, synthetic data
$env:INSV_SAMPLES = "$env:USERPROFILE\Downloads"; .\gradlew.bat testDebugUnitTest   # + real samples
# + render real frames, check the seam and the motion sensor's axes (make the frames with ffmpeg,
#   see SampleRenderTest.kt and SampleMotionTest.kt):
$env:INSV_FRAMES = "C:\path\to\frames"; $env:INSV_RENDER = "C:\path\to\out"; .\gradlew.bat testDebugUnitTest
python -I ..\tools\insv-probe.py "$env:USERPROFILE\Downloads\VID_20261109_174428_032.insv"
```

Sample videos never go in the repo (`.gitignore`): they are big and carry the camera's serial.
