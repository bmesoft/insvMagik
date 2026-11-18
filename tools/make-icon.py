"""Generate the app icon (an adaptive icon: white background, teal drawing) from the owner's
sketch: a fisheye circle and a landscape frame laid over each other, solid where only one of
them covers (the circle's caps above and below the frame, the frame's ends left and right of
the circle), white where both do, with "360°" and "magik" in the white. Writes the Android
vector drawable and the same drawing as `art/icon.svg` (for anything outside the app).

  python -I tools/make-icon.py

Standard library only. Coordinates are the adaptive icon's 108 × 108 dp canvas; the frame's
corners, the outermost points, stay inside the 66 dp safe circle. The drawing is one colour, so
it also serves as the monochrome layer of themed icons.

The letters are the outlines of **Nunito ExtraBold** (Copyright 2014 The Nunito Project Authors,
SIL Open Font License 1.1, https://github.com/googlefonts/nunito): the variable font fixed at
weight 800 with fontTools' instancer, outlines taken with its SVGPathPen; font units, 1000 per
em, y up, baseline 0.
"""
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

TEAL = "#00AACF"
C = 54.0  # canvas centre
HALF_W, HALF_H = 28.7, 17.2  # the frame, half width and height: corners 33.46 from the centre
R = 25.8  # the circle: ~0.9 of the frame's half width, taller than the frame, as sketched
OUTLINE = 1.6  # both shapes also get an outline, like the marker strokes of the sketch

# char: (ink left, ink right, outline) in Nunito ExtraBold font units.
GLYPHS = {
    "3": (37, 547, "M283 -11Q230 -11 175 1Q121 14 81 37Q55 51 45 70Q36 90 38 109Q41 128 54 143Q67 157 88 161Q108 165 133 153Q172 134 207 126Q242 118 277 118Q317 118 344 128Q371 139 385 159Q399 179 399 209Q399 251 370 272Q341 293 285 293H226Q193 293 176 309Q158 326 158 355Q158 385 176 402Q193 418 226 418H270Q304 418 327 428Q351 439 364 458Q378 477 378 505Q378 545 350 566Q323 587 271 587Q239 587 208 579Q177 572 141 553Q119 542 100 546Q81 550 69 564Q56 578 53 597Q49 615 58 634Q66 653 89 665Q132 690 182 703Q233 716 285 716Q361 716 415 693Q469 670 498 628Q527 585 527 525Q527 484 511 450Q495 416 466 392Q438 369 397 359V372Q468 360 508 313Q548 266 548 195Q548 132 516 85Q484 39 424 14Q365 -11 283 -11Z"),
    "6": (45, 565, "M327 -11Q237 -11 174 31Q111 73 78 152Q45 231 45 343Q45 432 66 501Q88 570 128 618Q168 666 227 691Q285 716 358 716Q401 716 445 704Q489 692 527 668Q547 658 554 639Q562 621 559 602Q556 583 544 569Q532 554 513 550Q495 545 471 556Q440 573 411 580Q383 587 356 587Q303 587 266 563Q229 539 210 491Q192 443 192 372V307H178Q187 355 211 388Q236 422 274 440Q312 458 358 458Q418 458 465 428Q511 399 538 347Q565 296 565 230Q565 160 535 106Q505 51 451 20Q398 -11 327 -11ZM319 110Q350 110 373 124Q397 138 410 164Q422 190 422 224Q422 258 410 283Q397 309 373 323Q350 338 319 338Q288 338 264 323Q241 309 228 283Q215 258 215 224Q215 190 228 164Q241 138 264 124Q288 110 319 110Z"),
    "0": (37, 562, "M300 -11Q172 -11 105 84Q37 180 37 355Q37 472 67 553Q98 633 156 674Q215 716 300 716Q429 716 496 623Q563 531 563 355Q563 238 533 157Q502 75 444 32Q386 -11 300 -11ZM300 115Q358 115 386 173Q414 230 414 355Q414 480 386 535Q358 590 300 590Q242 590 214 535Q186 480 186 355Q186 230 214 173Q242 115 300 115Z"),
    "°": (30, 353, "M192 399Q146 399 109 420Q72 440 51 476Q30 512 30 558Q30 603 51 638Q72 674 109 694Q146 715 192 715Q239 715 275 695Q311 674 332 639Q354 603 354 558Q354 512 332 476Q311 440 275 419Q239 399 192 399ZM192 480Q224 480 245 502Q266 524 266 558Q266 590 245 612Q224 635 192 635Q160 635 139 612Q118 590 118 558Q118 524 139 502Q160 480 192 480Z"),
    "m": (60, 831, "M136 -9Q99 -9 80 11Q60 31 60 68V423Q60 460 80 480Q99 499 134 499Q170 499 189 480Q208 460 208 423V359L197 399Q217 445 259 473Q301 501 358 501Q415 501 453 474Q491 447 507 391H493Q515 442 561 472Q606 501 665 501Q722 501 758 479Q795 456 813 410Q831 365 831 295V68Q831 31 812 11Q792 -9 756 -9Q719 -9 700 11Q680 31 680 68V290Q680 339 665 362Q649 384 612 384Q569 384 545 354Q521 324 521 269V68Q521 31 502 11Q482 -9 446 -9Q410 -9 390 11Q370 31 370 68V290Q370 339 354 362Q339 384 302 384Q259 384 235 354Q211 324 211 269V68Q211 -9 136 -9Z"),
    "a": (36, 501, "M224 -11Q170 -11 127 10Q84 31 60 67Q36 103 36 148Q36 202 64 233Q92 265 155 278Q218 292 322 292H375V214H323Q272 214 241 208Q209 203 196 190Q182 177 182 154Q182 126 202 108Q221 90 258 90Q287 90 309 103Q332 117 345 140Q358 164 358 194V309Q358 353 338 371Q318 390 270 390Q243 390 211 383Q180 377 142 362Q120 352 103 358Q86 363 77 378Q68 392 68 410Q68 427 78 444Q88 460 111 468Q158 487 199 494Q241 501 276 501Q353 501 402 478Q452 456 477 409Q502 363 502 290V68Q502 31 484 11Q466 -9 432 -9Q398 -9 379 11Q361 31 361 68V105L368 99Q362 65 342 41Q323 16 293 3Q263 -11 224 -11Z"),
    "g": (33, 556, "M294 -191Q240 -191 191 -182Q141 -173 103 -155Q80 -145 70 -129Q61 -113 63 -95Q64 -77 74 -63Q85 -49 101 -43Q117 -38 135 -46Q179 -66 217 -72Q254 -78 281 -78Q345 -78 377 -49Q409 -20 409 40V116H418Q403 70 356 41Q309 12 252 12Q186 12 137 43Q88 73 61 128Q34 184 34 257Q34 312 49 357Q65 402 94 434Q122 466 163 484Q203 501 252 501Q311 501 357 473Q402 444 417 398L407 366V423Q407 460 427 480Q446 499 482 499Q518 499 537 480Q556 460 556 423V57Q556 -66 489 -128Q421 -191 294 -191ZM297 125Q331 125 356 141Q380 157 394 186Q408 216 408 257Q408 319 377 354Q347 388 297 388Q263 388 238 373Q213 357 199 327Q186 298 186 257Q186 195 216 160Q246 125 297 125Z"),
    "i": (47, 219, "M134 -8Q97 -8 78 14Q58 35 58 74V416Q58 456 78 477Q97 499 134 499Q170 499 190 477Q209 456 209 416V74Q209 35 190 14Q171 -8 134 -8ZM134 581Q92 581 70 600Q47 620 47 656Q47 693 70 712Q92 732 134 732Q176 732 198 712Q220 693 220 656Q220 620 198 600Q176 581 134 581Z"),
    "k": (58, 525, "M134 -9Q97 -9 78 11Q58 31 58 68V637Q58 675 78 694Q97 714 134 714Q170 714 190 694Q209 675 209 637V288H211L356 449Q378 474 396 486Q415 499 446 499Q477 499 494 483Q510 467 511 445Q511 422 491 399L338 228V285L508 89Q527 66 525 43Q522 20 504 6Q486 -9 458 -9Q424 -9 404 4Q383 17 362 44L211 212H209V68Q209 -9 134 -9Z"),
}


def line(s, scale, baseline, tracking=0.0):
    """(x, baseline, scale, outline) per character: [s] centred on the canvas by its ink, at
    [scale] dp per font unit, letters [tracking] font units further apart."""
    xs = []
    x = 0.0
    for i, ch in enumerate(s):
        left, right, _ = GLYPHS[ch]
        if i:
            x += tracking
        xs.append(x - left)  # where this glyph's origin goes so its ink starts at x
        x += right - left
    start = C - x * scale / 2
    return [(start + ox * scale, baseline, scale, GLYPHS[ch][2]) for ch, ox in zip(s, xs)]


def f(v):
    return f"{v:.4f}".rstrip("0").rstrip(".")


def build():
    l, t, r, b = C - HALF_W, C - HALF_H, C + HALF_W, C + HALF_H
    circle = f"M{f(C - R)},{f(C)} A{f(R)},{f(R)} 0 1,0 {f(C + R)},{f(C)} A{f(R)},{f(R)} 0 1,0 {f(C - R)},{f(C)} Z"
    frame = f"M{f(l)},{f(t)} L{f(r)},{f(t)} L{f(r)},{f(b)} L{f(l)},{f(b)} Z"
    # Even-odd fill of both: solid where exactly one covers.
    shapes = f"{circle} {frame}"
    letters = line("360°", 0.0145, C - 1.9, tracking=30) + line("magik", 0.0135, C + 9.7, tracking=25)
    return shapes, letters


def vector(shapes, letters):
    s = ('<?xml version="1.0" encoding="utf-8"?>\n'
         '<!-- Generated by tools/make-icon.py from the owner\'s sketch; edit the script, not this file.\n'
         '     Letters: Nunito ExtraBold outlines, (c) 2014 The Nunito Project Authors, SIL Open Font License 1.1. -->\n'
         '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
         '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
         f'    <path android:pathData="{shapes}" android:fillColor="{TEAL}" android:fillType="evenOdd"\n'
         f'        android:strokeColor="{TEAL}" android:strokeWidth="{f(OUTLINE)}" android:strokeLineJoin="round" />\n')
    for x, y, k, d in letters:
        s += (f'    <group android:translateX="{f(x)}" android:translateY="{f(y)}" android:scaleX="{f(k)}" android:scaleY="{f(-k)}">\n'
              f'        <path android:pathData="{d}" android:fillColor="{TEAL}" />\n'
              '    </group>\n')
    return s + "</vector>\n"


def svg(shapes, letters, size=512):
    body = (f'<path d="{shapes}" fill="{TEAL}" fill-rule="evenodd" stroke="{TEAL}" '
            f'stroke-width="{f(OUTLINE)}" stroke-linejoin="round"/>')
    for x, y, k, d in letters:
        body += f'<path transform="translate({f(x)} {f(y)}) scale({f(k)} {f(-k)})" d="{d}" fill="{TEAL}"/>'
    # The full 108 canvas on white, like the launcher's square mask.
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">'
            f'<!-- insvMagik icon, generated by tools/make-icon.py. Letters: Nunito ExtraBold, (c) 2014 The Nunito Project Authors, SIL OFL 1.1. -->'
            f'<rect width="108" height="108" fill="#ffffff"/>{body}</svg>\n')


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def main():
    shapes, letters = build()
    write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), vector(shapes, letters))
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        write(os.path.join(RES, "mipmap-anydpi-v26", name), adaptive)
    write(os.path.join(RES, "values", "ic_launcher_colors.xml"),
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          '    <color name="ic_launcher_background">#FFFFFF</color>\n</resources>\n')
    art = os.path.join(ROOT, "art", "icon.svg")
    write(art, svg(shapes, letters))
    print("wrote", os.path.normpath(RES), "and", os.path.normpath(art))


if __name__ == "__main__":
    main()
