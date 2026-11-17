"""Inspect an Insta360 .insv file on the PC: its video/audio tracks and the data block that the
camera appends after the video (the "trailer": camera info, lens calibration, gyro, exposure).

The same parsing as the app's insv/ package, for looking at new samples quickly. Read-only.
Standard library only.

  python -I tools/insv-probe.py VID_20231126_111301_00_004.insv [more files...]

See docs/insv-format.md for what the fields mean (and what is still guesswork).
"""
import os
import re
import struct
import sys

MAGIC = b"8db42d694ccc418790edff439fe026bf"
FOOTER = 72  # 32 reserved bytes, u32 trailer size, u32 version, 32 bytes magic
NAMES = {0x101: "camera info", 0x200: "preview (H.264)", 0x300: "gyro + accelerometer",
         0x400: "exposure per frame", 0x500: "preview 2 (H.264)"}
CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl"}


def tracks(f, size):
    """(kind, codec, width, height, duration s, samples) per track, from the MP4 boxes."""
    out, cur = [], {}

    def walk(off, end):
        nonlocal cur
        while off + 8 <= end:
            f.seek(off)
            sz, typ = struct.unpack(">I4s", f.read(8))
            hl = 8
            if sz == 1:
                sz, hl = struct.unpack(">Q", f.read(8))[0], 16
            elif sz == 0:
                sz = end - off
            if sz < 8:
                return
            body = off + hl
            if typ == b"trak":
                cur = {}
                walk(body, off + sz)
                out.append(cur)
            elif typ in CONTAINERS:
                walk(body, off + sz)
            elif typ == b"mdhd":
                f.seek(body)
                d = f.read(32)
                ts, dur = struct.unpack(">II", d[12:20]) if d[0] == 0 else struct.unpack(">IQ", d[20:32])
                cur["duration"] = dur / ts if ts else 0
            elif typ == b"hdlr":
                f.seek(body + 8)
                cur["kind"] = f.read(4).decode("latin1")
            elif typ == b"stsd":
                f.seek(body + 8)
                e = f.read(40)
                cur["codec"] = e[4:8].decode("latin1")
                if cur.get("kind") == "vide":
                    cur["size"] = "%dx%d" % struct.unpack(">HH", e[32:36])
                if cur.get("kind") == "soun":
                    cur["size"] = "%d ch %d Hz" % (struct.unpack(">H", e[24:26])[0], struct.unpack(">I", e[32:36])[0] >> 16)
            elif typ == b"stsz":
                f.seek(body + 8)
                cur["samples"] = struct.unpack(">I", f.read(4))[0]
            off += sz

    walk(0, size)
    return out


def trailer(f, size):
    """(version, trailer start, layout, [(id, absolute offset, size)]) or None."""
    if size < FOOTER:
        return None
    f.seek(size - FOOTER)
    foot = f.read(FOOTER)
    if foot[40:] != MAGIC:
        return None
    tsize, version = struct.unpack("<II", foot[32:40])
    start, end = size - tsize, size - FOOTER
    f.seek(end - 6)
    rid, rsz = struct.unpack("<HI", f.read(6))
    recs = []
    if rid == 0:  # newer cameras: an index record (id big-endian, size, offset from trailer start)
        f.seek(end - 6 - rsz)
        idx = f.read(rsz)
        for i in range(0, len(idx) - 9, 10):
            (rid,) = struct.unpack(">H", idx[i:i + 2])
            rsz, roff = struct.unpack("<II", idx[i + 2:i + 10])
            if rsz and roff + rsz <= tsize:
                recs.append((rid, start + roff, rsz))
        return version, start, "index", recs
    while end - 6 >= start:  # older cameras: [data][u16 id][u32 size] chained back from the footer
        f.seek(end - 6)
        rid, rsz = struct.unpack("<HI", f.read(6))
        if rsz <= 0 or end - 6 - rsz < start:
            break
        recs.append((rid, end - 6 - rsz, rsz))
        end -= 6 + rsz
    return version, start, "chain", recs


def pb_strings(b, prefix="", depth=0):
    """Printable strings in a protobuf message with their field paths; None if it isn't protobuf."""
    out, i = [], 0

    def varint(i):
        r = shift = 0
        while i < len(b) and shift < 64:
            v = b[i]
            r |= (v & 0x7F) << shift
            i += 1
            if not v & 0x80:
                return r, i
            shift += 7
        return None, i

    while i < len(b):
        tag, i = varint(i)
        if tag is None or tag >> 3 == 0:
            return None
        field, wire = tag >> 3, tag & 7
        if wire == 0:
            v, i = varint(i)
            if v is None:
                return None
        elif wire == 1:
            i += 8
        elif wire == 5:
            i += 4
        elif wire == 2:
            n, i = varint(i)
            if n is None or i + n > len(b):
                return None
            sub, i = b[i:i + n], i + n
            path = f"{prefix}.{field}" if prefix else str(field)
            if sub and all(0x20 <= c <= 0x7E for c in sub):
                out.append((path, sub.decode("ascii")))
            elif depth < 3:
                out += pb_strings(sub, path, depth + 1) or []
        else:
            return None
        if i > len(b):
            return None
    return out


CALIB = re.compile(r"^\d+_-?\d")


def imu_summary(data):
    if len(data) % 56 == 0:  # ms timestamp + accel xyz (g) + gyro xyz (rad/s), all f64
        rows = [struct.unpack("<Q6d", data[i:i + 56]) for i in range(0, len(data), 56)]
        if rows and all(abs(v) < 64 for v in rows[0][1:]):
            span = (rows[-1][0] - rows[0][0]) / 1000
            return f"{len(rows)} samples, f64, ms clock, {span:.2f} s, {(len(rows) - 1) / span:.0f} Hz" if span else ""
    if len(data) % 20 == 0:  # us timestamp + 6 raw int16 stored with +0x8000 (scale unknown)
        rows = [struct.unpack("<Q6H", data[i:i + 20]) for i in range(0, len(data), 20)]
        span = (rows[-1][0] - rows[0][0]) / 1e6
        return f"{len(rows)} samples, raw int16, us clock, {span:.2f} s, {(len(rows) - 1) / span:.0f} Hz" if span else ""
    return "unknown layout"


def exposure_summary(data):
    rows = [struct.unpack("<Qd", data[i:i + 16]) for i in range(0, len(data) - 15, 16)]
    if len(rows) < 2:
        return "too short"
    gaps = sorted(b[0] - a[0] for a, b in zip(rows, rows[1:]))
    step = gaps[len(gaps) // 2]
    unit = "ms" if step < 1000 else "us"
    exp = sorted(r[1] for r in rows)
    return f"{len(rows)} frames, {unit} clock, frame step {step} {unit}, exposure 1/{1 / exp[-1]:.0f}..1/{1 / exp[0]:.0f} s"


def main(paths):
    for p in paths:
        size = os.path.getsize(p)
        print(f"== {os.path.basename(p)}  ({size / 1e6:.1f} MB)")
        with open(p, "rb") as f:
            for t in tracks(f, size):
                print(f"   track {t.get('kind', '?')} {t.get('codec', '?')} {t.get('size', '')} "
                      f"{t.get('duration', 0):.2f} s, {t.get('samples', 0)} samples")
            tr = trailer(f, size)
            if not tr:
                print("   no Insta360 trailer")
                continue
            version, start, layout, recs = tr
            print(f"   trailer v{version}, {size - start} bytes, {layout} layout, {len(recs)} records")
            for rid, off, rsz in sorted(recs):
                f.seek(off)
                data = f.read(rsz)
                extra = ""
                if rid == 0x300:
                    extra = imu_summary(data)
                elif rid == 0x400:
                    extra = exposure_summary(data)
                print(f"     0x{rid:04x} {rsz:>9} B  {NAMES.get(rid, '')}  {extra}")
                if rid == 0x101:
                    for path, s in pb_strings(data) or []:
                        label = "calibration" if CALIB.match(s) else ""
                        print(f"        field {path:6} {label:11} {s if len(s) < 120 else s[:117] + '...'}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
