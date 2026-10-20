"""Speaks the narration locally with Chatterbox, cloning the voice in a sample.

Usage: tts_local.py script.txt voice_sample.wav|- [out_prefix]
With "-" instead of a sample, Chatterbox's own built-in voice speaks.
EXAGGERATION and CFG (environment) tune the delivery; defaults 0.5 / 0.5.
Writes <prefix>.wav and <prefix>.json (each line's text, start and end in
seconds), the same format tts.py writes, so render.py can use either.
Each sentence is generated on its own and joined with a pause.
"""
import json
import os
import re
import sys
import wave

import librosa
import numpy as np
import torch
from chatterbox.tts import ChatterboxTTS

# Seconds of silence after each sentence, and after each script line. The
# pauses make the narration calmer than letting the model read on.
SENTENCE_PAUSE = float(os.environ.get("SENTENCE_PAUSE", "0.45"))
LINE_PAUSE = float(os.environ.get("LINE_PAUSE", "0.8"))
# Below 1 slows the speech itself down a little (pitch stays the same).
TEMPO = float(os.environ.get("TEMPO", "0.92"))
# Semitones to shift the voice; negative is deeper. Phase-vocoder based, so
# large shifts sound echoey; prefer VARISPEED.
PITCH = float(os.environ.get("PITCH", "0"))
# Below 1 plays the voice slower and deeper together, like a slowed tape
# (0.95: 5% slower, about one semitone lower). Clean, no echo.
VARISPEED = float(os.environ.get("VARISPEED", "1"))


def device():
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def main():
    script, sample = sys.argv[1], sys.argv[2]
    prefix = sys.argv[3] if len(sys.argv) > 3 else "local"
    lines = [l.strip() for l in open(script) if l.strip()]
    model = ChatterboxTTS.from_pretrained(device=device())
    sr = model.sr
    audio, out, t = [], [], 0.0
    for line in lines:
        start = t
        # One sentence at a time (a colon counts as a break too).
        sentences = [x for x in re.split(r"(?<=[.?!:])\s+", line) if x]
        for k, sentence in enumerate(sentences):
            torch.manual_seed(7)  # same take every run
            wav = model.generate(
                sentence,
                audio_prompt_path=None if sample == "-" else sample,
                exaggeration=float(os.environ.get("EXAGGERATION", "0.5")),
                cfg_weight=float(os.environ.get("CFG", "0.5")),
            )
            data = wav.squeeze().cpu().numpy().astype(np.float32)
            # Trim leading and trailing near-silence.
            loud = np.where(np.abs(data) > 0.01)[0]
            if len(loud):
                data = data[max(0, loud[0] - int(0.03 * sr)) : loud[-1] + int(0.08 * sr)]
            if PITCH:
                data = librosa.effects.pitch_shift(data, sr=sr, n_steps=PITCH).astype(np.float32)
            if VARISPEED != 1:
                data = librosa.resample(data, orig_sr=sr * VARISPEED, target_sr=sr).astype(np.float32)
            if TEMPO != 1:
                data = librosa.effects.time_stretch(data, rate=TEMPO).astype(np.float32)
            audio.append(data)
            t += len(data) / sr
            end = t
            pause = LINE_PAUSE if k == len(sentences) - 1 else SENTENCE_PAUSE
            audio.append(np.zeros(int(pause * sr), np.float32))
            t += pause
        out.append({"text": line, "start": start, "end": end})
        print(f"{start:6.2f} {end:6.2f}  {line}", flush=True)
    full = np.concatenate(audio)
    full = full / max(1e-6, np.abs(full).max()) * 0.9
    with wave.open(prefix + ".wav", "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes((full * 32767).astype("<i2").tobytes())
    json.dump(out, open(prefix + ".json", "w"), indent=1)


main()
