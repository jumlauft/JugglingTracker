"""A stick figure juggling a 5-ball cascade, drawn with PIL."""
import math
from PIL import Image, ImageDraw

BEAT = 1.0           # model time unit: one throw (hands alternate)
BALLS = 5
DWELL = 1.25 * BEAT  # a hand holds each ball this long
FLIGHT = BALLS * BEAT - DWELL
ORANGE = (245, 158, 11)
BALL_COLORS = [ORANGE] * 5


def hand_points(side, W, H):
    """Inner throw point and outer catch point for a hand (side -1 left, +1 right)."""
    cx, y = W / 2, H * 0.665
    return (cx + side * W * 0.06, y), (cx + side * W * 0.20, y + H * 0.01)


def state(t, W, H, last_throw=None, drop=False):
    """Ball positions and hand positions at time t (in beats). Ball b is first
    thrown at beat b; before that it waits in its hand, so t < 0 is the
    juggler standing ready with three balls in one hand and two in the other."""
    peak = H * 0.60  # height of the throws above the hands
    balls = []
    hands = {-1: None, 1: None}
    # Throw k happens at k*BEAT from hand side (-1 if k even else +1) with ball k % BALLS.
    k_now = math.floor(t / BEAT)
    waiting = []
    for b in range(BALLS):
        # Most recent throw of ball b at or before t.
        k = k_now - ((k_now - b) % BALLS)
        while last_throw is not None and k > last_throw:
            k -= BALLS
        if k < 0:
            # Not thrown yet: waiting in the hand that throws it first.
            waiting.append((b, -1 if b % 2 == 0 else 1))
            continue
        tk = k * BEAT
        side = -1 if k % 2 == 0 else 1
        throw_pt = hand_points(side, W, H)[0]
        catch_pt = hand_points(-side, W, H)[1]
        dt = t - tk
        if drop and k == last_throw:
            # The last throw goes wide, past the catching hand, and falls to the floor.
            miss_x = catch_pt[0] - side * W * 0.09
            u = dt / FLIGHT
            x = throw_pt[0] + (miss_x - throw_pt[0]) * u
            y = throw_pt[1] + (catch_pt[1] - throw_pt[1]) * u - 4 * peak * u * (1 - u)
            floor = H * 0.965
            if y > floor:
                # Resting on the floor where it landed.
                u_land = next(v / 200 for v in range(200, 400) if
                              throw_pt[1] + (catch_pt[1] - throw_pt[1]) * v / 200 - 4 * peak * v / 200 * (1 - v / 200) >= floor)
                x, y = throw_pt[0] + (miss_x - throw_pt[0]) * u_land, floor
            balls.append((x, y, BALL_COLORS[b]))
            continue
        if last_throw is not None and k + BALLS > last_throw and dt >= FLIGHT + DWELL:
            # Juggling has stopped: the ball stays in the hand that caught it.
            waiting.append((b, -side))
            continue
        if dt < FLIGHT:
            u = dt / FLIGHT
            x = throw_pt[0] + (catch_pt[0] - throw_pt[0]) * u
            y = throw_pt[1] + (catch_pt[1] - throw_pt[1]) * u - 4 * peak * u * (1 - u)
        else:
            # Held in the catching hand, scooping from outer catch to inner throw point.
            u = (dt - FLIGHT) / DWELL
            nxt = hand_points(-side, W, H)[0]
            x = catch_pt[0] + (nxt[0] - catch_pt[0]) * u
            y = catch_pt[1] + (nxt[1] - catch_pt[1]) * u + H * 0.05 * math.sin(math.pi * u)
            hands[-side] = (x, y)
        balls.append((x, y, BALL_COLORS[b]))
    for side in (-1, 1):
        if hands[side] is None and any(sd == side for _, sd in waiting):
            hands[side] = holding_hand(side, t, W, H, last_throw)
        elif hands[side] is None:
            # Empty: travels back from the inner throw point to the outer catch point.
            # The hand last threw at the latest beat of its parity.
            k = k_now if (k_now % 2 == 0) == (side == -1) else k_now - 1
            u = min(1, (t - k * BEAT) / (2 * BEAT - DWELL)) if k >= 0 else 0
            a, c = hand_points(side, W, H)
            x = a[0] + (c[0] - a[0]) * u
            y = a[1] + (c[1] - a[1]) * u - H * 0.03 * math.sin(math.pi * u)
            hands[side] = (x, y)
    for b, side in waiting:
        hx, hy = hands[side]
        slot = sum(1 for bb, sd in waiting if sd == side and bb < b)
        balls.append((hx + side * W * 0.03 * slot, hy - H * 0.028 * slot, BALL_COLORS[b]))
    return balls, hands


def holding_hand(side, t, W, H, last_throw):
    """A hand that still holds balls it has not thrown yet (at the start, or
    after juggling stops). It swings into each of its throws like a juggling
    hand does, so no ball leaves a hand that stands still."""
    a, c = hand_points(side, W, H)
    parity = 0 if side == -1 else 1
    kn = math.ceil(t / BEAT)  # this hand's next throw beat
    if kn % 2 != parity:
        kn += 1
    kp = kn - 2
    stopped = last_throw is not None and kn > last_throw
    if not stopped and kn >= 0 and t >= kn * BEAT - DWELL:
        u = (t - (kn * BEAT - DWELL)) / DWELL
        if kp < 0:
            # First throw: a dip from rest and up into the throw.
            return a[0], a[1] + H * 0.05 * math.sin(math.pi * u)
        # Scoop from the outer catch point into the inner throw point.
        return c[0] + (a[0] - c[0]) * u, c[1] + (a[1] - c[1]) * u + H * 0.05 * math.sin(math.pi * u)
    if kp >= 0 and not (last_throw is not None and kp > last_throw):
        if stopped:
            # Last throw done: come back to rest.
            return a
        # Back out to the catch point after a throw.
        u = min(1, (t - kp * BEAT) / (2 * BEAT - DWELL))
        return a[0] + (c[0] - a[0]) * u, a[1] + (c[1] - a[1]) * u - H * 0.03 * math.sin(math.pi * u)
    return a


def watch_box(t, W, H, last_throw=None, scale=2):
    """Where draw() puts the watch on the wrist: (x0, y0, x1, y1) in the W x H image."""
    w, h = W * scale, H * scale
    _, hands = state(t, w, h, last_throw)
    lw = int(w * 0.03)
    hx, hy = hands[1]
    return tuple(v / scale for v in (hx - lw * 1.1, hy - lw * 2.6, hx + lw * 1.1, hy - lw * 1.0))


def draw(t, W, H, fg=ORANGE, scale=2, last_throw=None, watch=(255, 236, 200), drop=False, face=None):
    """RGBA image W x H of the juggler at time t in beats (supersampled)."""
    w, h = W * scale, H * scale
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    balls, hands = state(t, w, h, last_throw, drop)
    lw = int(w * 0.03)
    cx = w / 2
    head_r = w * 0.065
    head_c = (cx, h * 0.43)
    neck = (cx, head_c[1] + head_r * 1.15)
    hip = (cx, h * 0.80)
    for side in (-1, 1):
        knee = (cx + side * w * 0.05, h * 0.89)
        foot = (cx + side * w * 0.08, h * 0.985)
        d.line([hip, knee, foot], fill=fg, width=lw, joint="curve")
    d.line([neck, hip], fill=fg, width=lw)
    d.ellipse([head_c[0] - head_r, head_c[1] - head_r, head_c[0] + head_r, head_c[1] + head_r], fill=fg)
    if face:
        # Eyes and a mouth in black; "sad" turns the smile upside down.
        er = head_r * 0.13
        for ex in (-0.38, 0.38):
            c = (head_c[0] + ex * head_r, head_c[1] - 0.2 * head_r)
            d.ellipse([c[0] - er, c[1] - er, c[0] + er, c[1] + er], fill=(0, 0, 0))
        mw, mh = head_r * 0.5, head_r * 0.32
        my = head_c[1] + (0.22 if face == "happy" else 0.38) * head_r
        box = [head_c[0] - mw, my - mh, head_c[0] + mw, my + mh]
        if face == "happy":
            d.arc(box, 20, 160, fill=(0, 0, 0), width=int(head_r * 0.12))
        else:
            d.arc([box[0], box[1] + mh, box[2], box[3] + mh], 200, 340, fill=(0, 0, 0), width=int(head_r * 0.12))
    shoulder_y = neck[1] + h * 0.03
    for side in (-1, 1):
        sh = (cx + side * w * 0.06, shoulder_y)
        hx, hy = hands[side]
        el = (cx + side * w * 0.115 + (hx - cx - side * w * 0.14) * 0.3, h * 0.60)
        d.line([(cx, shoulder_y), sh, el, (hx, hy)], fill=fg, width=lw, joint="curve")
        r = lw * 0.9
        d.ellipse([hx - r, hy - r, hx + r, hy + r], fill=fg)
    # Watch on the juggler's left wrist (the viewer's right).
    if watch:
        hx, hy = hands[1]
        d.rounded_rectangle([hx - lw * 1.1, hy - lw * 2.6, hx + lw * 1.1, hy - lw * 1.0], radius=lw * 0.4, fill=watch)
    br = w * 0.036
    for x, y, col in balls:
        d.ellipse([x - br, y - br, x + br, y + br], fill=col)
    return img.resize((W, H), Image.LANCZOS)


if __name__ == "__main__":
    strip = Image.new("RGB", (6 * 400, 500), (20, 24, 40))
    for i in range(6):
        im = draw(10 + i * 1.2, 400, 500, last_throw=11, drop=True, face="happy" if i < 4 else "sad")
        strip.paste(im, (i * 400, 0), im)
    strip.save("juggler_test.png")
