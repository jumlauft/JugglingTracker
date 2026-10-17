# Promo video

A vertical (1080 × 1920, 30 fps) social-media video of about 45 seconds: a
problem, the app as the solution, the watch counting a real 5-ball run in step
with an animated juggler, the phone statistics, and an end card. Everything
here regenerates it from the repo: the narration, the watch and phone footage
and every frame.

## Storyline

Each scene follows one line of [script.txt](script.txt); the timing of the
spoken narration decides when each scene starts (see "Timing" below).

| # | Narration (script.txt) | Picture |
|---|---|---|
| 1 | "Are you a juggler trying to get better, but you're never quite sure if you're actually making progress?" | Heading "Trying to get / better at juggling?". The juggler (no watch yet) is already juggling 5 balls with a smile; after about two seconds one throw goes wide and falls to the floor, the juggler stops holding the other four, the face turns sad and an orange "?" pulses beside it. |
| 2 | "Meet Juggling Tracker: the app that makes your juggling progress visible." | Title card: "Juggling Tracker" at the top, the juggler standing (now with a watch) exactly where it stands in the next scene, the brown zig-zag trend line drawing itself below the juggler; its arrowhead appears only when the line is complete. |
| 3 | "Strap on your smartwatch and start juggling." | The trend line fades out and the watch fades in under the unmoving juggler. Heading "Wear it while you juggle". The watch shows the app's Juggle screen, "Waiting", count 0. |
| 4 | "The watch's motion sensor detects the start of a run and counts your catches live, right on your wrist. No buttons to press, no counting in your head. When you stop juggling, the run ends by itself, and the next one starts when you do." | Heading "Juggle. It counts.". The juggler starts juggling; every catch of the watch hand lands on the frame where the watch count goes up by one (real speed, no fast-forward). The juggler stops around "When you stop juggling", holds the balls, and the watch ends the run by itself and goes back to "Waiting". |
| 5 | "Once you finish your session, transfer it to your phone, where you keep track of all your statistics, including a regularity index measuring how clean your pattern is." | Heading "Every run, every stat". The phone slides up once from the bottom and then stays at rest, showing the 5-ball history; after about 2 s the screen dims and the Session Details sheet (5 balls) slides up. |
| 6 | "And on those days when you feel stuck on a plateau, the numbers show what you can't feel." | Heading crossfades to "Progress you can see"; the sheet slides back down, an orange frame highlights the 5-ball trend graph and an orange arrow draws across it. |
| 7 | "Juggling Tracker. Counts your catches, accelerates your progress." | End card: "Juggling Tracker" at the top, the juggler, the trend line with its arrow, "Counts your catches / Accelerates your progress", then "Android · iPhone" and "Garmin · Wear OS · Apple Watch". |

Every line is also shown as a caption at the bottom (sentence-sized pieces).
The video fades in from and out to black.

Rules that came out of review:

- Name no Apple product in the narration ("smartwatch", "phone"). The end card lists the platforms.
- The phone always shows 5-ball statistics, like the run on the watch.
- No fast-forward; the run is cut short instead (see "Watch footage").
- Nothing jumps at a scene change: during a crossfade the incoming scene is drawn at the real time.
- A ball only leaves a hand that moves (hands holding balls swing into each throw).

## Style

Taken from the Garmin store hero image (`connectiq/resources/images/garmin_hero_image.png`): black
background, orange `#F59E0B` (245, 158, 11) text in Arial Bold, an orange
stick-figure juggler with orange balls, and the dark orange `#563704`
(86, 55, 4) zig-zag trend line. Secondary text is `#C4800C`. Captions are
orange on a dark box. The real app screens keep their own colours.

## Files

| File | What it does |
|---|---|
| `script.txt` | The narration, one scene per line. |
| `tts_local.py` | Speaks the script sentence by sentence (8% slowed, with pauses) with [Chatterbox](https://github.com/resemble-ai/chatterbox) (MIT-licensed, runs locally). `-` uses its built-in voice; a WAV sample clones that voice. Writes `narration.wav` and `narration.json` (each line's start and end). |
| `capture_watch.sh` | Builds the watch app, cuts the replay recording, films the watch simulator replaying it (`-replay`, `-replayBalls 5`), extracts frames (`fr_watch/`) and finds the count steps (`counts.txt`). |
| `make_replay.py`, `count_steps.py` | Helpers of `capture_watch.sh`. |
| `capture_phone.sh` | Builds the iPhone app and takes `shot_overview5.png` and `shot_details.png` with its screenshot mode. |
| `juggler.py` | The stick-figure juggler and its 5-ball cascade (positions in beats). |
| `render.py` | Draws every frame and mixes the narration track. |
| `frames.swift`, `encode.swift` | AVFoundation tools: movie → JPEG frames; frames + audio → H.264/AAC MP4 (no ffmpeg needed). |
| `build.sh` | Narration, frames and the MP4. |

## Watch footage

The watch simulator has no accelerometer, so the watch app replays a real
recording instead (`apple/Watch/ReplaySource.swift`): `-replay <csv>` feeds it
at 25 Hz in 0.2 s batches, so the count rises one catch at a time, and
`-replayBalls 5` steps through Juggle → 5 balls → Start by itself. The source is
Jonas's 5-ball run `connectiq/data/20260924_170352.csv`. `make_replay.py`
keeps only its first N samples and appends a still wrist, so the run stops and
the app ends it about two seconds later. N = 240 gives 10 catches (the current narration), 212 gives 9, 182 gives 7.
Pick N so the last catch lands near "When you stop juggling" (slower narration
needs more catches).

## Timing

- `narration.json` sets the scene boundaries: each scene starts 0.25 s before its line.
- The juggling starts so that the first catch comes 1.2 s after line 4 starts; the watch recording then plays at real speed.
- The juggler's tempo follows the watch: the watch-hand catch of throw 2i is pinned to beat 2i + 3.75 (the flight time), and the beats between catches are interpolated. After the last counted catch the other hand throws once more, then all balls come to rest.

## Regenerating

On a Mac with Xcode, XcodeGen, the iOS 27 and watchOS 27 simulator runtimes
and Python 3 with numpy and Pillow:

```bash
# One-time: Chatterbox needs an arm64 Python 3.11 (not Anaconda's Intel build).
brew install python@3.11
/opt/homebrew/opt/python@3.11/bin/python3.11 -m venv ~/promo-tts
~/promo-tts/bin/pip install chatterbox-tts "setuptools<81"

mkdir -p promo/build && cd promo/build
../capture_watch.sh 240
../capture_phone.sh
TTS_PY=~/promo-tts/bin/python ../build.sh
```

The first Chatterbox run downloads its model (a few GB). Generating the
narration takes several minutes on an M1. To use a recorded voice instead of
the built-in one, pass `VOICE=sample.wav` (15 s of clean speech is enough).
`promo/build/` is ignored by git.
