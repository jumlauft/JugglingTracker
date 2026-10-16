"""Renders the promo video frames (1080x1920, 30 fps) and the voice-over track.

Run from the build folder (see README.md); it reads narration.json/.wav,
fr_watch/, counts.txt and shot_*.png there.
"""
import json
import math
import os
import sys
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

import juggler

W, H, FPS = 1080, 1920, 30
OUT = "frames_out"
FONT = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
# The hero image's palette: orange on black, with a dark orange trend line.
ORANGE = (245, 158, 11)
BROWN = (86, 55, 4)
SOFT = (196, 128, 12)

# The narration drives the timeline: narration.json holds each script line's
# start and end in narration.wav (or .mp3), which starts at OFFSET.
OFFSET = 0.4
LINES = json.load(open(os.environ.get("NARRATION", "narration") + ".json"))
L = [{"text": l["text"], "start": OFFSET + l["start"], "end": OFFSET + l["end"]} for l in LINES]
T_TITLE = L[1]["start"] - 0.25
T_SETUP = L[2]["start"] - 0.25
T_COUNT = L[3]["start"]
T_DETAILS = L[4]["start"] - 0.25
T_PLATEAU = L[5]["start"] - 0.25
T_END = L[6]["start"] - 0.25
END = L[6]["end"] + 1.3
FADE = 0.35


def caption_chunks():
    """Sentence-sized captions, timed by their share of each line's characters."""
    chunks = []
    for l in L:
        parts, cur = [], ""
        for word in l["text"].split():
            cur = (cur + " " + word).strip()
            if word[-1] in ".?!" and len(cur) > 25:
                parts.append(cur)
                cur = ""
        if cur:
            parts.append(cur)
        total = sum(len(p) for p in parts)
        t = l["start"]
        for p in parts:
            dur = (l["end"] - l["start"]) * len(p) / total
            chunks.append((t, t + dur, p))
            t += dur
    return chunks


CHUNKS = caption_chunks()


def font(size, weight="Bold"):
    return ImageFont.truetype(FONT, size)


F_TITLE = font(118, "Heavy")
F_SUB = font(52, "Medium")
F_HEAD = font(64, "Bold")
F_CAP = font(42, "Semibold")
F_SMALL = font(34, "Medium")


def background():
    return Image.new("RGB", (W, H), (0, 0, 0))


BG = background()


def centered(d, y, text, f, fill):
    w = d.textlength(text, font=f)
    d.text(((W - w) / 2, y), text, font=f, fill=fill)


def wrap(d, text, f, maxw):
    lines, cur = [], ""
    for word in text.split():
        trial = (cur + " " + word).strip()
        if d.textlength(trial, font=f) <= maxw:
            cur = trial
        else:
            lines.append(cur)
            cur = word
    lines.append(cur)
    return lines


def caption(img, t):
    for start, end, text in CHUNKS:
        if start - 0.05 <= t <= end + 0.25:
            d = ImageDraw.Draw(img, "RGBA")
            lines = wrap(d, text, F_CAP, W - 160)
            lh = 56
            y0 = H - 70 - lh * len(lines)
            d.rounded_rectangle([60, y0 - 22, W - 60, H - 50], radius=28, fill=(24, 16, 2, 230))
            for i, line in enumerate(lines):
                centered(d, y0 + i * lh, line, F_CAP, ORANGE)
            return


def paste_juggler(img, phase, box, last_throw=None):
    x, y, w, h = box
    j = juggler.draw(phase, w, h, last_throw=last_throw)
    img.paste(j, (x, y), j)


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius=radius, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def shadow(img, box, radius, blur=30, alpha=160):
    x0, y0, x1, y1 = box
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([x0, y0 + 20, x1, y1 + 20], radius=radius, fill=(0, 0, 0, alpha))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(blur)))


# --- Watch recording and the catch sync ------------------------------------
WATCH_SCALE = 1.25
WATCH_FRAMES = os.environ.get("WATCH_FRAMES", "fr_watch")
WATCH_LAST = len(os.listdir(WATCH_FRAMES)) - 1
def load_catches(path=os.environ.get("COUNTS", "counts.txt")):
    """Source times (s) of each watch-hand catch: one count step per line."""
    pts = [tuple(map(int, line.split())) for line in open(path)]
    return [f / FPS for f, _ in pts], pts[0][0]


CATCHES, FIRST_CATCH = load_catches()
# The tracker shows "Waiting" until just before the first catch; the run in the
# recording then plays at real speed and ends by itself.
SRC_WAIT = FIRST_CATCH - 36


def src_of(t):
    """Source frame of the watch recording shown at output time t."""
    return SRC_WAIT + max(0.0, t - T_COUNT) * FPS


def t_of(src_s):
    """Output time at which source time src_s (seconds) is shown."""
    return T_COUNT + (src_s * FPS - SRC_WAIT) / FPS


# The juggler's watch hand catches throw 2i at beat 2i + FLIGHT, so each count
# step is pinned to that beat; between steps the tempo is interpolated.
_CT = [t_of(c) for c in CATCHES]
_PH = [2 * i + juggler.FLIGHT for i in range(len(_CT))]
_LEAD = (_CT[4] - _CT[0]) / 8  # seconds per beat at the start
# After the last counted catch the other hand makes one more throw, then
# every ball comes to rest in the hands.
LAST_THROW = 2 * (len(_CT) - 1) + 1


def juggle_phase(t):
    if t < _CT[0]:
        return _PH[0] - (_CT[0] - t) / _LEAD
    if t >= _CT[-1]:
        return _PH[-1] + (t - _CT[-1]) / _LEAD
    return float(np.interp(t, _CT, _PH))


def watch_frame(src_index):
    src_index = max(0, min(WATCH_LAST, int(src_index)))
    return Image.open(f"{WATCH_FRAMES}/{src_index:05d}.jpg").convert("RGB")


def draw_watch(img, src_index, cx, top):
    scr = watch_frame(src_index)
    sw, sh = int(scr.width * WATCH_SCALE), int(scr.height * WATCH_SCALE)
    scr = rounded(scr.resize((sw, sh), Image.LANCZOS), 70)
    bez = 34
    x0, y0 = cx - sw // 2 - bez, top
    x1, y1 = x0 + sw + 2 * bez, y0 + sh + 2 * bez
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([cx - sw // 2 + 40, y0 - 60, cx + sw // 2 - 40, y1 + 60], radius=60, fill=(48, 54, 74))
    shadow(img, (x0, y0, x1, y1), 120)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([x0, y0, x1, y1], radius=120, fill=(20, 20, 24), outline=(90, 94, 110), width=6)
    d.rounded_rectangle([x1 - 4, y0 + 150, x1 + 26, y0 + 260], radius=12, fill=(120, 124, 140))
    d.rounded_rectangle([x1 - 4, y0 + 330, x1 + 14, y0 + 470], radius=8, fill=(90, 94, 110))
    img.alpha_composite(scr, (x0 + bez, y0 + bez))
    return y1


# --- Phone -----------------------------------------------------------------
# All phone screens show 5 balls, like the run on the watch.
SHOTS = {k: Image.open(f"shot_{k}.png").convert("RGB") for k in ("overview5", "details")}
SHEET_AT = 2.2  # seconds into the phone scene when the session details open


SHEET_TOP = 1243  # where the details sheet starts in shot_details.png
_SHEET = SHOTS["details"].crop((0, SHEET_TOP, SHOTS["details"].width, SHOTS["details"].height))
_DIM = SHOTS["details"].crop((0, 0, SHOTS["details"].width, SHEET_TOP))


def sheet_screen(k):
    """The history screen with the session-details sheet slid up by k (0..1),
    as the app opens it: the screen dims and the sheet rises from the bottom."""
    ov = SHOTS["overview5"]
    if k <= 0:
        return ov
    scr = ov.copy()
    dim = Image.blend(ov.crop((0, 0, ov.width, SHEET_TOP)), _DIM, k)
    scr.paste(Image.blend(ov, Image.eval(ov, lambda v: int(v * 0.8)), k))
    scr.paste(dim, (0, 0))
    y = int(SHEET_TOP + (1 - k) * (ov.height - SHEET_TOP))
    scr.paste(_SHEET, (0, y))
    return scr


def draw_phone(img, shot, cx, top, height, offset_y=0):
    s = SHOTS[shot] if isinstance(shot, str) else shot
    w = int(s.width * height / s.height)
    scr = rounded(s.resize((w, height), Image.LANCZOS), 70)
    bez = 18
    x0, y0 = cx - w // 2 - bez, top + offset_y
    box = (x0, y0, x0 + w + 2 * bez, y0 + height + 2 * bez)
    shadow(img, box, 88)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(box, radius=88, fill=(16, 16, 20), outline=(100, 104, 120), width=5)
    img.alpha_composite(scr, (x0 + bez, y0 + bez))


# --- Scenes ----------------------------------------------------------------
def ease(u):
    u = max(0.0, min(1.0, u))
    return u * u * (3 - 2 * u)


def trend_line(img, box, progress=1.0):
    """The hero image's rising zig-zag trend line, drawn up to `progress`."""
    x0, y0, w, h = box
    pts = [(0, 0.85), (0.22, 0.72), (0.44, 0.92), (0.66, 0.42), (0.85, 0.58), (1.0, 0.0)]
    pts = [(x0 + px * w, y0 + py * h) for px, py in pts]
    d = ImageDraw.Draw(img)
    n = progress * (len(pts) - 1)
    shown = pts[: int(n) + 1]
    if int(n) < len(pts) - 1:
        a, b = pts[int(n)], pts[int(n) + 1]
        f = n - int(n)
        shown.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
    d.line(shown, fill=BROWN, width=22, joint="curve")
    for p in shown[:-1]:
        d.ellipse([p[0] - 18, p[1] - 18, p[0] + 18, p[1] + 18], fill=BROWN)
    # An arrowhead once the line has reached its end.
    (ax, ay), (bx, by) = shown[-2], shown[-1]
    if progress >= 1:
        ang = math.atan2(by - ay, bx - ax)
        hl = 62
        d.polygon([(bx + math.cos(ang) * 22, by + math.sin(ang) * 22),
                   (bx - hl * math.cos(ang - 0.55), by - hl * math.sin(ang - 0.55)),
                   (bx - hl * math.cos(ang + 0.55), by - hl * math.sin(ang + 0.55))], fill=BROWN)


DROP_THROW = 15        # the throw that goes wide in the opening scene
PROBLEM_BEAT = 0.36    # seconds per throw in the opening scene


def scene_problem(t):
    img = BG.copy().convert("RGBA")
    phase = 8 + t / PROBLEM_BEAT  # already juggling when the video starts
    sad = phase >= DROP_THROW + juggler.FLIGHT
    x, y, w, h = 140, 420, 800, 900
    # No smartwatch yet: that comes with the app.
    j = juggler.draw(phase, w, h, last_throw=DROP_THROW, drop=True, face="sad" if sad else "happy", watch=None)
    img.paste(j, (x, y), j)
    d = ImageDraw.Draw(img)
    centered(d, 170, "Trying to get", F_TITLE, ORANGE)
    centered(d, 310, "better at juggling?", F_TITLE, ORANGE)
    if sad:
        k = 0.5 + 0.5 * math.sin(t * 3)
        d.text((800, 640), "?", font=font(int(230 + 30 * k)), fill=ORANGE)
    return img


def scene_title(t, start, end_card=False):
    """The juggler stands exactly where it stands over the watch in the next
    scene; the trend line and title sit where the watch will appear. On the
    end card the name moves to the top and the rest moves up a little."""
    img = BG.copy().convert("RGBA")
    d = ImageDraw.Draw(img)
    if end_card:
        trend_line(img, (130, 900, 820, 300), ease((t - start) / 1.8))
        centered(d, 110, "Juggling Tracker", F_TITLE, ORANGE)
        centered(d, 1300, "Counts your catches", F_SUB, ORANGE)
        centered(d, 1365, "Accelerates your progress", F_SUB, ORANGE)
        centered(d, 1460, "Android \u00b7 iPhone", F_SMALL, SOFT)
        centered(d, 1508, "Garmin \u00b7 Wear OS \u00b7 Apple Watch", F_SMALL, SOFT)
    else:
        trend_line(img, (130, 960, 820, 300), ease((t - start) / 1.8))
        centered(d, 110, "Juggling Tracker", F_TITLE, ORANGE)
    paste_juggler(img, -1, JUGGLER_BOX)
    return img


JUGGLER_BOX = (245, 250, 590, 640)


def scene_watch(t):
    img = BG.copy().convert("RGBA")
    d = ImageDraw.Draw(img)
    head = "Wear it while you juggle" if t < T_COUNT else "Juggle. It counts."
    centered(d, 90, head, F_HEAD, ORANGE)
    paste_juggler(img, juggle_phase(t), JUGGLER_BOX, LAST_THROW)
    draw_watch(img, src_of(t), W // 2, 960)
    return img


def scene_phone(t, plateau):
    img = BG.copy().convert("RGBA")
    d = ImageDraw.Draw(img)
    if plateau:
        # The sheet closes again to show the trend graph.
        shot = sheet_screen(1 - ease((t - T_PLATEAU) / 0.5))
    else:
        # The history first, then the newest session's details.
        shot = sheet_screen(ease((t - T_DETAILS - SHEET_AT) / 0.5))
    # The phone stays on screen into the plateau part; only the heading
    # crossfades.
    k = ease((t - T_PLATEAU) / 0.35)
    ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
    o = ImageDraw.Draw(ov)
    for text, a in (("Every run, every stat", 1 - k), ("Progress you can see", k)):
        if a > 0:
            w = o.textlength(text, font=F_HEAD)
            o.text(((W - w) / 2, 90), text, font=F_HEAD, fill=ORANGE + (int(255 * a),))
    img.alpha_composite(ov)
    # The phone slides up once, then stays put; the plateau scene only
    # changes what is on its screen.
    start = T_PLATEAU if plateau else T_DETAILS
    # The slide starts with the crossfade into this scene.
    slide = (1 - ease((t - (T_DETAILS - FADE)) / 0.8)) * 300
    drift = 0
    top, height = 210, 1480
    draw_phone(img, shot, W // 2, top, height, int(slide + drift))
    if plateau:
        # Highlight the trend graph and draw a rising arrow across it.
        s = height / SHOTS["overview5"].height
        w = SHOTS["overview5"].width * s
        x0 = W / 2 - w / 2
        y0 = top + 18 + slide + drift
        cx0, cy0, cx1, cy1 = x0 + 40 * s, y0 + 1255 * s, x0 + 1166 * s, y0 + 1800 * s
        u = ease((t - start - 0.8) / 1.6)
        if u > 0:
            ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
            o = ImageDraw.Draw(ov)
            o.rounded_rectangle([cx0 - 8, cy0 - 8, cx1 + 8, cy1 + 8], radius=30, outline=(245, 158, 11, int(230 * u)), width=8)
            ax0, ay0 = cx0 + 80 * s, cy1 - 120 * s
            ax1, ay1 = cx1 - 160 * s, cy0 + 150 * s
            ex, ey = ax0 + (ax1 - ax0) * u, ay0 + (ay1 - ay0) * u
            o.line([ax0, ay0, ex, ey], fill=(245, 158, 11, 235), width=14)
            ang = math.atan2(ey - ay0, ex - ax0)
            hl = 46
            o.polygon([(ex + math.cos(ang) * 10, ey + math.sin(ang) * 10),
                       (ex - hl * math.cos(ang - 0.5), ey - hl * math.sin(ang - 0.5)),
                       (ex - hl * math.cos(ang + 0.5), ey - hl * math.sin(ang + 0.5))], fill=(245, 158, 11, 235))
            img.alpha_composite(ov)
    return img


def scene_at(t, at=None):
    """The scene for time `at` (default t), drawn at time t. During a
    crossfade the incoming scene is drawn at the real time, so nothing in it
    jumps when the fade ends."""
    at = t if at is None else at
    if at < T_TITLE:
        return scene_problem(t)
    if at < T_SETUP:
        return scene_title(t, T_TITLE)
    if at < T_DETAILS:
        return scene_watch(t)
    if at < T_END:
        return scene_phone(t, plateau=at >= T_PLATEAU)
    return scene_title(t, T_END, end_card=True)


def frame(t):
    img = scene_at(t)
    for edge in (T_TITLE, T_SETUP, T_DETAILS, T_END):
        if edge - FADE <= t < edge:
            nxt = scene_at(t, at=edge + 0.001)
            img = Image.blend(img, nxt, ease((t - (edge - FADE)) / FADE))
    k = min(1.0, t / 0.4, (END - t) / 0.5)
    if k < 1:
        img = Image.blend(Image.new("RGBA", img.size, (0, 0, 0, 255)), img, max(0.0, k))
    caption(img, t)
    return img.convert("RGB")


def audio(path="voiceover.wav", rate=44100):
    """The narration placed at OFFSET, padded to the video length."""
    src = os.environ.get("NARRATION", "narration") + ".wav"
    with wave.open(src) as a:
        r = a.getframerate()
        data = np.frombuffer(a.readframes(a.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
    if r != rate:
        data = np.interp(np.arange(0, len(data), r / rate), np.arange(len(data)), data)
    total = np.zeros(int(END * rate), dtype=np.float32)
    i = int(OFFSET * rate)
    total[i : i + len(data)] = data[: len(total) - i]
    total = np.clip(total / max(1e-6, np.abs(total).max()) * 0.9, -1, 1)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes((total * 32767).astype("<i2").tobytes())


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    if sys.argv[1] == "preview":
        for t in map(float, sys.argv[2:]):
            frame(t).save(f"preview_{t:05.2f}.jpg", quality=90)
    elif sys.argv[1] == "audio":
        audio()
    else:
        lo, hi = int(sys.argv[1]), int(sys.argv[2])
        for i in range(lo, min(hi, int(END * FPS))):
            frame(i / FPS).save(f"{OUT}/{i:05d}.jpg", quality=92)
