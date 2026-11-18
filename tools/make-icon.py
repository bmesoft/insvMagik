"""Generate the app icon (an adaptive icon: white background, teal line drawing) from the owner's
sketch: a fisheye circle, the hatched part outside the reframed picture, and the flat frame
with "360°" and "MAGIK" in it. Writes the Android vector drawable and the same drawing as
`art/icon.svg` (for anything outside the app: a README, a store listing later).

  python -I tools/make-icon.py

Standard library only. Everything is strokes (no fills), so the same drawing serves as the
monochrome layer of themed icons. Coordinates are the adaptive icon's 108 × 108 dp canvas;
keep the drawing inside the 66 dp safe circle around the centre.
"""
import math
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

TEAL = "#00AACF"
C = 54.0  # canvas centre
R = 31.5  # the fisheye circle
RECT = (31.0, 39.5, 77.0, 68.5)  # left, top, right, bottom of the reframed picture

# Letters as strokes in a unit box (height 1, y down): (width, path).
LETTERS = {
    "3": (0.82, "M0.04,0.16 C0.18,-0.04 0.74,-0.06 0.74,0.25 C0.74,0.44 0.52,0.48 0.34,0.48 "
                "M0.34,0.48 C0.62,0.48 0.82,0.58 0.82,0.76 C0.82,1.06 0.2,1.08 0.0,0.86"),
    "6": (0.84, "M0.74,0.06 C0.38,-0.04 0.04,0.24 0.04,0.62 C0.04,0.9 0.24,1.0 0.44,1.0 "
                "C0.68,1.0 0.84,0.86 0.84,0.68 C0.84,0.48 0.66,0.38 0.44,0.38 C0.24,0.38 0.08,0.5 0.05,0.64"),
    "0": (0.84, "M0.42,0 C0.14,0 0,0.26 0,0.5 C0,0.78 0.14,1 0.42,1 C0.7,1 0.84,0.78 0.84,0.5 "
                "C0.84,0.22 0.7,0 0.42,0 Z"),
    "°": (0.3, "M0.03,0.13 A0.12,0.12 0 1,0 0.27,0.13 A0.12,0.12 0 1,0 0.03,0.13 Z"),
    "M": (0.92, "M0,1 L0.06,0 L0.46,0.66 L0.86,0 L0.92,1"),
    "A": (0.8, "M0,1 L0.4,0 L0.8,1 M0.15,0.64 L0.65,0.64"),
    "G": (0.84, "M0.8,0.18 C0.66,0.03 0.52,0 0.42,0 C0.14,0 0,0.26 0,0.5 C0,0.78 0.14,1 0.42,1 "
                "C0.64,1 0.82,0.86 0.84,0.6 L0.46,0.6"),
    "I": (0.12, "M0.06,0 L0.06,1"),
    "K": (0.8, "M0,0 L0,1 M0.76,0 L0.06,0.62 M0.3,0.42 L0.8,1"),
}


def text(s, height, top, gap, stroke):
    """Groups that draw [s] centred on the canvas, [height] tall from [top]."""
    widths = [LETTERS[ch][0] for ch in s]
    total = (sum(widths) + gap * (len(s) - 1)) * height
    x = C - total / 2
    out = []
    for ch, w in zip(s, widths):
        out.append((x, top, height, LETTERS[ch][1], stroke / height))
        x += (w + gap) * height
    return out


def hatch(spacing=5.2, inset=3.2, margin=3.0):
    """'/' lines (x + y = c) inside the circle, outside the picture; as (x1, y1, x2, y2)."""
    r = R - inset
    l, t, rr, b = RECT[0] - margin, RECT[1] - margin, RECT[2] + margin, RECT[3] + margin
    segs = []
    k = math.floor(-r * math.sqrt(2) / spacing)
    while k * spacing <= r * math.sqrt(2):
        d = k * spacing  # distance of the line from the centre, along (1, 1) / √2
        k += 1
        if abs(d) >= r:
            continue
        half = math.sqrt(r * r - d * d)
        # Points: centre + d·n + s·u, n = (1, 1)/√2, u = (1, −1)/√2.
        def point(s):
            return (C + (d + s) / math.sqrt(2), C + (d - s) / math.sqrt(2))
        # Where the line crosses the rectangle, in s.
        lo, hi = -half, half
        cut = []
        s_vals = []
        for (x0, y0, x1, y1) in [(l, t, rr, t), (l, b, rr, b), (l, t, l, b), (rr, t, rr, b)]:
            # Solve for s where the point lies on this edge.
            if y0 == y1:
                s = (d - (y0 - C) * math.sqrt(2))
                px, _ = point(s)
                if x0 - 1e-9 <= px <= x1 + 1e-9:
                    s_vals.append(s)
            else:
                s = (x0 - C) * math.sqrt(2) - d
                _, py = point(s)
                if y0 - 1e-9 <= py <= y1 + 1e-9:
                    s_vals.append(s)
        if len(s_vals) >= 2:
            cut = [min(s_vals), max(s_vals)]
        parts = [(lo, hi)] if not cut else [(lo, min(hi, cut[0])), (max(lo, cut[1]), hi)]
        for a, bnd in parts:
            if bnd - a > 1.0:
                x1, y1 = point(a)
                x2, y2 = point(bnd)
                segs.append((x1, y1, x2, y2))
    return segs


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def build():
    hatch_path = " ".join(f"M{f(a)},{f(b)} L{f(c)},{f(d)}" for a, b, c, d in hatch())
    circle_path = f"M{f(C - R)},{f(C)} A{f(R)},{f(R)} 0 1,0 {f(C + R)},{f(C)} A{f(R)},{f(R)} 0 1,0 {f(C - R)},{f(C)} Z"
    l, t, r, b = RECT
    rect_path = f"M{f(l)},{f(t)} L{f(r)},{f(t)} L{f(r)},{f(b)} L{f(l)},{f(b)} Z"
    letters = text("360°", 10.0, 43.0, 0.24, 2.1) + text("MAGIK", 7.6, 57.4, 0.26, 1.9)
    return hatch_path, circle_path, rect_path, letters


def vector(hatch_path, circle_path, rect_path, letters):
    def p(d, w):
        return (f'    <path android:pathData="{d}" android:strokeColor="{TEAL}" android:strokeWidth="{f(w)}"\n'
                f'        android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" />\n')
    s = ('<?xml version="1.0" encoding="utf-8"?>\n'
         '<!-- Generated by tools/make-icon.py from the owner\'s sketch; edit the script, not this file. -->\n'
         '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
         '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n')
    s += p(hatch_path, 1.5) + p(circle_path, 2.8) + p(rect_path, 2.3)
    for x, y, h, d, w in letters:
        s += (f'    <group android:translateX="{f(x)}" android:translateY="{f(y)}" android:scaleX="{f(h)}" android:scaleY="{f(h)}">\n'
              f'    {p(d, w).strip()}\n    </group>\n')
    return s + "</vector>\n"


def svg(hatch_path, circle_path, rect_path, letters, size=512):
    def p(d, w):
        return f'<path d="{d}" stroke="{TEAL}" stroke-width="{f(w)}" stroke-linecap="round" stroke-linejoin="round" fill="none"/>'
    body = p(hatch_path, 1.5) + p(circle_path, 2.8) + p(rect_path, 2.3)
    for x, y, h, d, w in letters:
        body += f'<g transform="translate({f(x)} {f(y)}) scale({f(h)})">{p(d, w)}</g>'
    # The full 108 canvas on white, like the launcher's square mask.
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">'
            f'<rect width="108" height="108" fill="#ffffff"/>{body}</svg>\n')


def main():
    parts = build()
    os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
    os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
    os.makedirs(os.path.join(RES, "values"), exist_ok=True)
    with open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(vector(*parts))
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(RES, "mipmap-anydpi-v26", name), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(adaptive)
    with open(os.path.join(RES, "values", "ic_launcher_colors.xml"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                 '    <color name="ic_launcher_background">#FFFFFF</color>\n</resources>\n')
    art = os.path.join(ROOT, "art")
    os.makedirs(art, exist_ok=True)
    with open(os.path.join(art, "icon.svg"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(svg(*parts))
    print("wrote", os.path.normpath(RES), "and", os.path.normpath(os.path.join(art, "icon.svg")))


if __name__ == "__main__":
    main()
