#!/bin/zsh
# Narration, frames and the final MP4. Run from the build folder after
# capture_watch.sh and capture_phone.sh. TTS_PY is the Python that has
# chatterbox-tts installed (see README.md); PY needs numpy and Pillow.
set -e
REPO=${0:A:h:h}
PY=${PY:-python3}
TTS_PY=${TTS_PY:-python3}
export PYTHONPATH=$REPO/promo
# The checked-in video: built-in voice, calm, 4% slower like a slowed tape.
VARISPEED=${VARISPEED:-0.96} TEMPO=${TEMPO:-1} EXAGGERATION=${EXAGGERATION:-0.45} CFG=${CFG:-0.3} $TTS_PY $REPO/promo/tts_local.py $REPO/promo/script.txt ${VOICE:--} narration
$TTS_PY $REPO/promo/studio.py narration.wav
[ -x encode ] || swiftc -O $REPO/promo/encode.swift -o encode
rm -rf frames_out
$PY $REPO/promo/render.py audio
afconvert -f m4af -d aac@44100 -b 128000 voiceover.wav voiceover.m4a
N=$($PY -c "import render as r; print(int(r.END * 30))")
S=$(( (N + 7) / 8 ))
for k in 0 1 2 3 4 5 6 7; do $PY $REPO/promo/render.py $((k * S)) $(((k + 1) * S)) & done
wait
./encode frames_out $N 30 voiceover.m4a JugglingTracker_promo.mp4
echo "wrote $PWD/JugglingTracker_promo.mp4"
