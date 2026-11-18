# insvMagik

An Android tool that turns 360° recordings from Insta360 cameras (`.insv`) into normal, flat
**reframed MP4** videos right on the phone: pick the clip's files, choose where to look, export
to the gallery, ready for any video editor. No Insta360 app, no PC.

**Status:** stage 5 of the [plan](docs/plan.md): a probe that reads the files and measures whether
the phone can decode them, and a view tab that shows a reframed frame and exports the whole clip
as an MP4 (16:9, 9:16 or 1:1), stabilised by the camera's gyro with a level horizon (ONE X2,
Antigravity A1 and X5 so far), the view fixed or moving between keyframes. A test version:
reports welcome.

**Download:** [insvMagik.apk](https://github.com/bmesoft/insvMagik/releases/latest/download/insvMagik.apk)
(the latest release, Android 10+). How to install it, get clips onto the phone and use the app:
the [tester guide](docs/tester-guide.md). Reports and feedback: **office@bmesoft.eu** (the app's
**Email report** button fills it in).

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
| [docs/tester-guide.md](docs/tester-guide.md) | for testers: download, install, clips onto the phone, use, reports |
| [.github/workflows/](.github/workflows/) | `build.yml`: tests + a test APK on every PR and push; `release.yml`: the signed APK as the latest release |
| [tools/install.ps1](tools/install.ps1) | build, install on the USB-connected phone, launch |
| [tools/insv-probe.py](tools/insv-probe.py) | the same file analysis on the PC (Python, standard library) |

Kotlin, Jetpack Compose, `MediaExtractor` / `MediaCodec`. minSdk 29 (Android 10), targetSdk 36.


## Build and try

```powershell
.\tools\install.ps1             # build, install on the phone (USB debugging), start
.\tools\install.ps1 -ApkOnly    # just the APK: android\app\build\outputs\apk\debug\app-debug.apk
```

In the app, **Files** tab: **Choose files** (both `…_00_…` and `…_10_…` of a clip, or the single
file of newer cameras), read the report, **Decode test**, **Copy** and paste the report where it's
needed. **View & export** tab: the middle of the clip as a flat view; the time slider picks
another frame, the sliders look around, **Whole sphere** shows the panorama. **◇ Keyframe here**
pins the view at this moment; with a few keyframes the view moves smoothly between them (the
sliders then set the view at the frame on screen). **Motion** picks the stabilisation
(**Follow**: level horizon, the view turns smoothly with the camera; **Locked**: level, always
the same direction; **Off**: fixed to the camera body), **Shape** the output (16:9, 9:16, 1:1),
**Export** writes the clip to `Movies/insvMagik`, **Copy report** gives the settings and the
export result. **Seam tools** (folded) are for checking a new camera.

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

## Releases

Actions → **Release** → **Run workflow** (on `main`) tags `release/<date>.<n>`, runs the tests,
builds the APK signed with the release key and publishes it as the latest release; the download
link above always gives the newest. The key lives only in the repo secrets
`SIGNING_KEYSTORE_BASE64` / `SIGNING_PASSWORD` and with the maintainer: every release must use the
same key, or phones refuse the update.

## Licence

[GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0). Use, study, change and share it
freely; anyone who distributes a changed version, or offers it to users over a network, must
publish their source code under the same licence. For other terms, write to office@bmesoft.eu.

The app icon's letters are outlines of Nunito ExtraBold (© 2014 The Nunito Project Authors,
SIL Open Font License 1.1). The `.insv` format notes draw on
[telemetry-parser](https://github.com/AdrianEddy/telemetry-parser) (MIT / Apache-2.0); no code is
copied from it. Not affiliated with Insta360 or Antigravity; the app reads `.insv` files.
