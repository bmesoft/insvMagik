# The .insv format, as far as we know it

Insta360 doesn't document `.insv`. This is what the two samples show, checked with
[`tools/insv-probe.py`](../tools/insv-probe.py) and the app's parsers (`android/app/src/main/java/eu/bmesoft/insvmagik/insv/`).
**Measured** means read from the samples; **guess** means it fits the numbers but isn't confirmed.

| Sample | Camera | Firmware | Layout |
|---|---|---|---|
| `VID_20231126_111301_00_004.insv` (43.7 MB, 3.8 s) | Insta360 ONE X2 | v1.0.62_build2 | lens 0 only; lens 1 is in `…_10_004.insv` (not in hand yet) |
| `VID_20261109_174428_032.insv` (199.8 MB, 9.0 s) | Antigravity A1 (360° drone) | 10.0.397.3_5.5 | both lenses in one file |
| `VID_20220625_140410_10_008.insv` (356.5 MB, 47 s) | Insta360 ONE RS 1-inch 360 | – | lens 1 only, **no trailer** (below) |
| `1745586212-vid_20250416_143628_00_005.insv` (608 MB, 24.4 s) | Insta360 X5 | v0.8.50_build1 | both lenses in one file despite `_00_` in the name; a motorbike ride |

Samples stay out of the repo (`.gitignore`): they are big and carry the camera's serial number.

## The MP4 part

An `.insv` is an ordinary MP4 (`ftyp`, `mdat`, `moov`) that Android's `MediaExtractor` reads like
any video, whatever the extension. The Insta360 data block (the *trailer*) follows after it.

| | ONE X2, 5.7K mode | Antigravity A1, 8K mode |
|---|---|---|
| Video | 1 track per file, H.264, **2880×2880**, 29.97 fps | **2 tracks** in one file, H.264, **3840×3840** each, 29.97 fps |
| Each track holds | one lens, a round fisheye image | one lens each |
| Audio | AAC stereo 48 kHz | none |
| Other | – | a `text` track, one sample per frame (content not looked at yet) |

The X5 sample: **2 tracks of HEVC** (`hvc1`), 3840×3840 each, 25 fps, AAC stereo, index-layout
trailer like the A1's. Track 0 is lens 0 (the seam lines up that way; camera-info field 80, "multi
track order" in telemetry-parser, is 1 on the X5 and 2 on the A1, both in that order).

File names (Insta360 manuals): `VID_<date>_<time>_00_<seq>.insv` + `…_10_…` are the two lenses
of one clip (ONE X2, X3); the A1 writes one `VID_<date>_<time>_<seq>.insv`. The X5 writes both
lenses into one file but keeps `_00_` in its name, so the app goes by the number of video tracks.
A share or upload may prefix the name (`1745586212-vid_…`, lower case).
Low-res proxies: `LRV_…` / `.lrv`, with `01` / `11` in the lens slot. A phone browser may save
a download as `….insv.3gpp` or `….insv (1).3gpp` (extension from the MIME type); the app reads
those names too.

**A file without a trailer** (ONE RS 1-inch 360, measured once): `ftyp`, `mdat`, a complete
`moov` (3072×3072 H.264 at 24 fps, AAC, an empty 10 Hz `mov_text` track, a `udta` with an
Ambarella `AMBA` box), then a `free` box of leftover bytes that pads the file to exactly 340 MiB.
No trailer magic anywhere: no calibration, no gyro, so nothing to reframe it with. Why is a guess
(the camera reserves space in chunks; a recording that ended badly keeps its video and loses the
trailer). Its `_00_` file wasn't checked.

## The trailer

**Footer** (measured, both): the file's last 72 bytes are 32 zero bytes, `u32` trailer size
(including the footer), `u32` version (3 in both), and the ASCII magic
`8db42d694ccc418790edff439fe026bf`. All numbers little-endian.

**Records**, two layouts (measured):

- **Chain** (ONE X2): records back to back; after each one a 6-byte header `[u16 id][u32 size]`.
  Read backwards from the footer until the trailer start.
- **Index** (A1): the record right before the footer has id 0 and is an index of 10-byte entries
  `[u16 id][u32 size][u32 offset from trailer start]`, where **the id is big-endian** (`03 00` =
  0x300). Records sit at padded offsets; empty entries are all zeros.

telemetry-parser reads the same two bytes as `[u8 format][u8 id]` (chain) and `[u8 id][u8 format]`
(index), so its record ids are ours divided by 0x100 (gyro = 3, exposure = 4).

| Id | ONE X2 | A1 | What |
|---|---|---|---|
| 0x101 | 1852 B | 1804 B | camera info, protobuf (below) |
| 0x200 | 820 KB | 74 KB | starts with an H.264 start code: a preview (guess) |
| 0x300 | 150 KB | 190 KB | gyro + accelerometer (below) |
| 0x400 | 2 KB | 4.6 KB | exposure per frame (below) |
| 0x500 | 752 KB | – | another H.264 preview (guess) |
| 0x700, 0x900, 0xa00, 0xb00, 0xe00, 0x1700, 0x2000, 0x2100, 0x2500, 0x2600, 0x3800 | some | some | not looked at; the A1 has many more (drone telemetry, GPS? guess) |

### Camera info (0x101)

A protobuf message without a schema. Fields seen (measured): **1** serial number, **2** model
("Insta360 ONE X2", "Antigravity A1"), **3** firmware, **5** and **17** a basic lens calibration,
**53–56** longer calibrations (53 = 55 and 54 = 56 in both samples), **22** "vivid" on the X2
(colour profile, guess).

Motion fields; names from telemetry-parser (MIT/Apache-2.0, `src/insta360/extra_info.rs`),
values from the samples:

| Field | What | ONE X2 | A1 |
|---|---|---|---|
| 24 | first frame's time on the gyro clock, in the gyro clock's unit | 153 676 (ms) | 84 367 315 (µs) |
| 25 | rolling-shutter readout, ms | 23.5 | 21.25 |
| 28 (+ 29 = 1) | extra gyro time offset, ms | −6.0 | 3.7 |
| 62 | gyro record holds raw 16-bit counts | – | 1 |
| 65 | sensor ranges: `{1: ± g, 2: ± °/s}` | – | 32, 2000 |
| 31 | "gyro calibration": 6 × f64 + u64 | small numbers | small numbers, **not** the A1's resting gyro offset; unused |
| 60 | 9 × f32, a turn close to 180° about z (not in telemetry-parser) | present | – |
| 27 | "window crop": `{1, 2: source size, 3, 4: kept size, 5, 6: offset}` | 3040 → 2880 | 5248, no crop (X5: 5376 → 5312) |
| 69, 91 | 4 floats / 4 doubles, unit quaternions (not in telemetry-parser) | – | present |

Fields 60, 69 and 91 look like sensor or attitude turns; none was needed (field 60 on top of
the measured X2 axes doesn't stabilise better).

**Field 27 doesn't apply to the XI calibration** (measured). telemetry-parser crops each grid
square to the kept size around its centre before scaling to the video. With the XI string that
is worse on both cameras that crop: on the X5 the seam's distant sectors match at 0.85 / 0.87 /
0.81 (three moments) when the whole grid square is scaled to the video, and 0.74 / 0.80 / 0.71
with the centred crop (0.78 / 0.85 / 0.75 cropped from the corner); on the ONE X2 the
stabilised background moves more with the crop (3.23 vs 2.64). So the app scales the whole
square, as before.

### Lens calibration ("offset")

Text: `<lens count>_<numbers>_…`. Positions are pixels on a grid of both lenses side by side:
6080×3040 on the X2 (each lens a 3040 square, stored in the video as 2880), 10496×5248 on the A1
(5248 squares, stored as 3840). Lens 1's centre x includes the left square (4562 = 3040 + 1522).
Scale to the video with `videoLensSize / gridHeight`.

| Shape | Numbers per lens | Seen in field |
|---|---|---|
| BASIC | `radius cx cy yaw pitch roll`, then `gridW gridH n` once | 5, 17 |
| POLY | `radius cx cy yaw pitch roll  tx ty tz  c0 c1 c2 c3  gridW gridH type`, then one number | 53, 55 |
| XI | `xi fx fy cx cy yaw pitch roll  tx ty tz  d0 d1 d2 d3 d4  gridW gridH type`, then one number | 54, 56 |

Guesses: *radius* is the image circle (1482 px in a 3040 square); POLY's `c0 = 1.0` and three
small coefficients look like a radial polynomial over the angle. Angles are degrees.

**XI, measured on the A1 sample** (stage 1, `SampleRenderTest`): it is the unified
omnidirectional model (Mei; OpenCV `omnidir`) with distortion **`k1 k2 k3 p1 p2`** in that order,
in calibration-grid pixels:
`m = (x, y) / (z + xi)`, `m · (1 + k1 r² + k2 r⁴ + k3 r⁶)` plus tangential `p1 p2`, then
`fx · m + cx`. The image circle (BASIC radius) is reached at about **97° from the axis**: each
lens sees ~194°, so the two overlap by a few degrees around the seam.

**Rotation, measured on the A1 sample:** world → lens i = `Rz(roll) · Ry(−yaw) · Rx(pitch) · B`
with the XI string's angles, `B` = identity for lens 0 and a 180° turn about x for lens 1
(axes: x right, y down, z forward). Every reading of the angles was tried; this one lines the
seam up (brightness correlation 0.76 across the overlap) and puts the ground-facing lens along
+y (down), the sky lens up. This world is the **camera body**, not level: the A1 sample comes
out ~10° tilted (the drone stands tilted on its table), the ONE X2 sample ~80° (held sideways).
The gyro and accelerometer level it (below, "Motion"). BASIC uses a different angle convention (A1: yaw 180, roll 89 where XI says
pitch 90, roll −90), not worked out since XI is present on both cameras.

### Gyro + accelerometer (0x300)

| | ONE X2 (measured) | A1 (measured) |
|---|---|---|
| Entry | 56 B: `u64` time **ms**, accel xyz (g), gyro xyz (rad/s), all `f64` | 20 B: `u64` time **µs**, accel xyz then gyro xyz as `u16` stored as count + 0x8000 |
| Scale | as stored | ± range / 32768 per count, ranges from field 65: 1024 counts per g, 16.384 per °/s |
| Rate | 500 Hz | ~1000 Hz |
| Span | 5.34 s for a 3.84 s video (starts 1.34 s before frame 0) | 9.56 s for 8.98 s (starts 0.27 s before) |

Accel first, then gyro, in both (telemetry-parser, and the 1 g at rest fits).

## Motion

**Clock** (telemetry-parser's rule, checked): video time = gyro time − field 24 − field 28 (ms).
On the X2 clip, stabilising with the gyro shifted by −20…+20 ms is best at 0 (±5 ms). On the A1
the exposure entry at field 24's time is entry 8, 22 µs away. Video timestamps start at 0 in
both samples.

**Axes** (body = rig's world: x right, y down, z forward; the accelerometer reads +1 g **up**):

| Camera | body x | body y | body z | How it was found |
|---|---|---|---|---|
| ONE X2 | sensor x | sensor z | −sensor y | from the video: of all 24 axis-to-axis turns, this one stabilises the background best (frame-to-frame change 2.64 vs 5.27 unstabilised and 3.49 for the next best) |
| Antigravity A1 | −sensor z | −sensor y | −sensor x | from gravity: the only turn of the four that put the sensor's up (its +y) up which levels the horizon and stands the walls upright; gyro assumed on the accelerometer's axes (the sample doesn't move) |
| Insta360 X5 | sensor x | −sensor z | sensor y | from the sun on a motorbike ride: in a locked world the sun stays within 1.9° (RMS over 2 s windows) vs 6.0° unstabilised and 3.0° for the next best turn, which turns the world on its side once levelled |

The X5's clock fields work the same as the A1's (raw 16-bit gyro, µs; field 24 matches exposure
entry 5 to 15 µs); its sun test can't tell ±20 ms apart, the X2's clip can (0 ± 5 ms).

**Resting gyro offset:** the A1 at rest reads (−0.0043, 0.0019, −0.0003) rad/s in its own axes,
~0.25 °/s; field 31 doesn't match it, so the app measures it from the still moments of the clip.
On the X5 (a motorbike that never stops) the offset can't be measured, and field 31's gyro part
taken as the offset makes the levelling worse (the sun's height over the clip spreads 3.4°
instead of 2.3°), so it stays unused there too.

**Levelling, checked with the sun** (X5): the sun's height above the levelled horizon should not
change in 24 s. With the forward complementary filter (2 s) it stays within 2.3° RMS
(`SampleMotionTest.x5SunHeight`). An offline two-sided estimate (gyro over ±5 s, gravity averaged
over the same window) drifted 18° → 38°: without a measured gyro offset, trusting the gyro over
longer spans is worse than correcting tilt often.

### Exposure (0x400)

16-byte entries: `u64` time + `f64` exposure in seconds (measured). Time in **ms** on the X2,
**µs** on the A1 (same clock as the gyro). There are more entries than frames (130 for 115 on the
X2, 287 for 269 on the A1); the X2's list has a ~1 s gap after its first two entries. Which entry
belongs to video frame 0 is still open (plan, stage 4).
