# The .insv format, as far as we know it

Insta360 doesn't document `.insv`. This is what the two samples show, checked with
[`tools/insv-probe.py`](../tools/insv-probe.py) and the app's parsers (`android/app/src/main/java/eu/bmesoft/insvmagik/insv/`).
**Measured** means read from the samples; **guess** means it fits the numbers but isn't confirmed.

| Sample | Camera | Firmware | Layout |
|---|---|---|---|
| `VID_20231126_111301_00_004.insv` (43.7 MB, 3.8 s) | Insta360 ONE X2 | v1.0.62_build2 | lens 0 only; lens 1 is in `…_10_004.insv` (not in hand yet) |
| `VID_20261109_174428_032.insv` (199.8 MB, 9.0 s) | Antigravity A1 (360° drone) | 10.0.397.3_5.5 | both lenses in one file |

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

File names (Insta360 manuals): `VID_<date>_<time>_00_<seq>.insv` + `…_10_…` are the two lenses
of one clip (ONE X2, X3); X4 and newer write one `VID_<date>_<time>_<seq>.insv` (the A1 too).
Low-res proxies: `LRV_…` / `.lrv`, with `01` / `11` in the lens slot.

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
small coefficients look like a radial polynomial over the angle; XI (`xi` 1.73 / 1.50, `fx`
2323 / 3758) looks like the unified omnidirectional camera model (Mei; OpenCV `omnidir`) with
`k1 k2 p1 p2` + one more. Angles are degrees; lens 0 of the X2 has roll ≈ −179°, the A1's lenses
have roll ≈ 89° and yaw ≈ 180°: the sensors are mounted differently, so axis conventions must
come from the numbers, not be assumed.

### Gyro + accelerometer (0x300)

| | ONE X2 (measured) | A1 (measured layout, values not decoded) |
|---|---|---|
| Entry | 56 B: `u64` time **ms**, accel xyz (g), gyro xyz (rad/s), all `f64` | 20 B: `u64` time **µs**, 6 × `u16` stored as value + 0x8000 |
| Rate | 500 Hz | ~1000 Hz |
| Span | 5.34 s for a 3.84 s video (starts ~1.5 s early) | 9.56 s for 8.98 s |

The X2's accel vector is about 0.93 long in the first samples, so its unit is g. The A1's scale (counts per g and
per rad/s) and axis order are unknown; telemetry-parser (MIT/Apache-2.0, used by Gyroflow) reads
Insta360 gyro data and may know them.

### Exposure (0x400)

16-byte entries: `u64` time + `f64` exposure in seconds (measured). Time in **ms** on the X2,
**µs** on the A1 (same clock as the gyro). There are more entries than frames (130 for 115 on the
X2, 287 for 269 on the A1); the X2's list has a ~1 s gap after its first two entries. Which entry
belongs to video frame 0 is still open (plan, stage 4).
