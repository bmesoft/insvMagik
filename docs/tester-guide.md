# insvMagik: guide for testers

insvMagik turns a 360° clip from the camera or drone into a normal flat video, right on your
phone, with no camera app and no computer. You choose where the picture looks, the app keeps the
horizon level, and the result lands in your gallery. This is a test version: please send us what
you find (see [Send us your reports](#send-us-your-reports)).

## What you need

- **An Android phone**, Android 10 or newer. A fast phone helps: a Pixel 8 converts an 8K clip in
  about real time (1 minute of video in about 1 minute).
- **Clips (`.insv` files) from** an Antigravity A1, Insta360 X5 or Insta360 ONE X2. Other cameras
  may open, but without stabilisation.
- **A way to get the files onto the phone:** a USB-C card reader for the camera's microSD card,
  or a USB-C cable from the camera to the phone.
- **Free space:** the clip itself (about 1.5 GB per minute of 8K) plus the result (about 120 MB
  per minute).

## Download and install

The app isn't in the Play Store; it comes from this project's GitHub page.

1. On the phone, open this link in the browser:
   **<https://github.com/bmesoft/insvMagik/releases/latest/download/insvMagik.apk>**
   (or open [the releases page](https://github.com/bmesoft/insvMagik/releases/latest) and tap
   `insvMagik.apk` under **Assets**). The browser downloads the file.
2. Open the downloaded `insvMagik.apk` (from the download notification, or the **Files** app →
   **Downloads**).
3. Android asks to allow installing apps from the browser: tap **Settings**, turn on **Allow from
   this source**, go back, tap **Install**.
4. Play Protect may warn about an unknown app: tap **More details**, then **Install anyway**.
5. Open **insvMagik**: a blue circle-and-frame icon. It has two tabs, **Files** and
   **View & export**.

**Updates:** download from the same link again and install; it installs over the old version.
If the phone says *App not installed* or *conflicts with an existing package*, an earlier test
copy came from somewhere else: uninstall insvMagik first (long-press the icon → **Uninstall**),
then install again.

## Get the clips onto the phone

The surest way is the microSD card in a USB-C card reader; then copy the clip's `.insv` file to
the phone.

1. Switch the drone or camera off and take out the microSD card.
2. Put it in a USB-C card reader and plug that into the phone. Any microSD reader works;
   Antigravity and Insta360 also sell their own "Quick Reader". If nothing happens, turn on
   **OTG** in the phone's settings (some phones have it).
3. Open the phone's **Files** app, tap the card (USB drive), open the `DCIM` folder and its
   `Camera01` folder.
4. Long-press the clip's file(s), tap **⋮ → Copy to**, pick **Downloads**, then **Copy here**.
   Big clips take a while to copy.
5. Put the card back in the drone or camera.

Which files to copy:

| Camera | Files of one clip | Example |
| --- | --- | --- |
| Antigravity A1 | one `.insv` file | `VID_20261109_174428_032.insv` |
| Insta360 X5 | one `.insv` file (it still says `_00_`) | `VID_20250416_143628_00_005.insv` |
| Insta360 ONE X2 | two files, `_00_` and `_10_`, same time and number | `VID_20231126_111301_00_004.insv` + `VID_20231126_111301_10_004.insv` |

Skip the `LRV_….lrv` files (small previews) and `.insp` files (photos). Clips can also be picked
straight from the card reader, but then the reader must stay plugged in until the export ends,
and it's slower.

The camera can also be connected with a USB-C cable in its **USB drive / U-Disk mode**; Insta360
documents that mode for computers. Whether a phone sees the files that way isn't confirmed: try
it, and tell us whether it worked.

## Files tab: pick the clip

1. Tap **Choose files**. The phone's file picker opens: go to **Downloads** (or the card reader)
   and select the clip's file. For a ONE X2 clip, select both of its files (long-press the first,
   then tap the second).
2. A report appears: the camera, the video tracks, and notes. A note like "pick its _10_ file too"
   means a file of the clip is missing.
3. The first time with a new camera or on a new phone, tap **Decode test** (about 10 seconds). Its
   last line says how fast this phone converts the clip:
    - **1.0× or more:** a minute of video takes a minute or less.
    - **Below 1×:** it still works, just slower (0.5× = 2 minutes per minute).
    - **An error, or "size no" on every decoder:** this phone can't open the clip; send the report.
4. Tap **Report on GitHub** to send it to us (see [Send us your reports](#send-us-your-reports)),
   or **Copy** to paste it elsewhere.

Then switch to the **View & export** tab: it shows the middle of the clip after a moment.

## View & export: choose the shot and export

The picture at the top is what the video will show at the moment on the **Time** slider. Drag the
slider and let go to see another moment; it takes a second or two to appear.

| Control | What it does |
| --- | --- |
| **Yaw** | turn left / right (0 = straight ahead, ±180 = behind) |
| **Pitch** | look up / down |
| **Roll** | tilt the picture, e.g. to follow the lean of a bike or a banking drone |
| **Field of view** | zoom: smaller = closer, larger = wider |
| **Reset view** | back to straight ahead, level, 100° |
| **Whole sphere** | shows all around at once, to find what to look at; the export is always the normal view |
| **Motion: Follow** | the horizon stays level, the view turns smoothly with the drone (start with this) |
| **Motion: Locked** | level, and always looking the same way however the drone turns; can drift slowly sideways on long clips |
| **Motion: Off** | fixed to the drone: every shake and tilt shows |
| **Shape** | 16:9 (landscape), 9:16 (phone / reels) or 1:1 (square) |

Tap **Export**. Keep the app open: the screen stays on, the progress shows in %, and **Cancel**
stops it (the unfinished file is deleted). A minute of video takes about a minute on a fast phone.

The video lands in the gallery, folder **Movies / insvMagik**, named after the clip plus the
motion and shape, e.g. `…_follow_9x16.mp4`. Exporting again doesn't overwrite: the new one gets
` (1)` in its name.

## Keyframes: make the view move

A keyframe says where to look at one moment of the clip. The video starts with the view set on the
sliders (the starting view), turns smoothly to each keyframe in turn, then stays on the last one.
Without keyframes the whole video keeps the starting view.

Example: the drone flies along a coast. You want to start looking ahead, swing left to the cliff at
10 s, then come back ahead and zoom in at 20 s.

1. **Starting view:** with no keyframes yet, set the sliders for the start; here **Reset view**
   (straight ahead). The start needs no keyframe.
2. Drag **Time** to 10 s, tap **◇ Keyframe here**, then move **Yaw** to −90 (left).
3. Drag **Time** to 20 s, tap **◇ Keyframe here**, set **Yaw** to 0 and **Field of view** to 70.
4. Tap **Export**. The video looks ahead, turns left between 0 and 10 s, turns back and zooms in
   between 10 and 20 s, then holds.

Good to know:

- Each keyframe shows as a chip (◇ 10.0 s). Tap one to jump there; ◆ marks the one on screen.
  Moving the sliders there changes it, and **◆ Remove keyframe** deletes it.
- Once there is a keyframe, moving a slider at any moment adds a keyframe there. To change the
  start later, go to 0 s and move the sliders.
- Moves ease in and out and never swing past a keyframe. A few seconds between keyframes looks
  calm; one second makes a fast whip.
- **Clear all** goes back to one fixed view.
- Keyframes are lost when the app is closed: export before leaving it.

## Send us your reports

Reports go to the project's **[GitHub issues](https://github.com/bmesoft/insvMagik/issues)**. You
need a free GitHub account: sign up once at [github.com/signup](https://github.com/signup).

After a decode test or an export, tap **Report on GitHub**: the browser opens a new issue with the
report filled in. Write a line or two under **What I saw**, then tap **Create** (or **Submit new
issue**). If the form comes up empty (after signing in, for example), paste: the app has copied
the report too. Without the app at hand, open [a new issue](https://github.com/bmesoft/insvMagik/issues/new/choose)
and pick **Test report**.

Issues are public: anyone can read them. The report holds the phone model, the clip's file name,
the settings, the keyframes and the export speed; no location and no video. Don't attach clips
or write anything you'd rather keep private.

Most useful to us:

- [ ] The **Decode test** report from your phone (Files tab).
- [ ] A clip where the drone **turns or banks**: does the horizon stay level with **Follow**?
      Which way is it off, and when?
- [ ] A **keyframed** export: does the move look smooth?
- [ ] Anything that fails: the message on screen, and the report.

Known limits of this test version:

- Only the A1, X5 and ONE X2 are stabilised; other cameras export without it.
- Close objects (props, legs, the stick) can show a small step where the two lenses meet.
- Keyframes aren't saved, and the app can't play the move before exporting yet.
