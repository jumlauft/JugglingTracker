"""Speaks the narration locally with Chatterbox, cloning the voice in a sample.

Usage: tts_local.py script.txt voice_sample.wav|- [out_prefix]
With "-" instead of a sample, Chatterbox's own built-in voice speaks.
EXAGGERATION and CFG (environment) tune the delivery; defaults 0.5 / 0.5.
Writes <prefix>.wav and <prefix>.json (each line's text, start and end in
seconds), the same format tts.py writes, so render.py can use either.
Each line is generated on its own and joined with a short pause.
"""
import json
import os
import sys
import wave

import numpy as np
import torch
from chatterbox.tts import ChatterboxTTS

GAP = 0.35  # seconds of silence between lines


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
        torch.manual_seed(7)  # same take every run
        wav = model.generate(
            line,
            audio_prompt_path=None if sample == "-" else sample,
            exaggeration=float(os.environ.get("EXAGGERATION", "0.5")),
            cfg_weight=float(os.environ.get("CFG", "0.5")),
        )
        data = wav.squeeze().cpu().numpy().astype(np.float32)
        # Trim leading and trailing near-silence.
        loud = np.where(np.abs(data) > 0.01)[0]
        if len(loud):
            data = data[max(0, loud[0] - int(0.03 * sr)) : loud[-1] + int(0.08 * sr)]
        dur = len(data) / sr
        out.append({"text": line, "start": t, "end": t + dur})
        audio += [data, np.zeros(int(GAP * sr), np.float32)]
        t += dur + GAP
        print(f"{out[-1]['start']:6.2f} {out[-1]['end']:6.2f}  {line}", flush=True)
    full = np.concatenate(audio)
    full = full / max(1e-6, np.abs(full).max()) * 0.9
    with wave.open(prefix + ".wav", "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes((full * 32767).astype("<i2").tobytes())
    json.dump(out, open(prefix + ".json", "w"), indent=1)


main()
