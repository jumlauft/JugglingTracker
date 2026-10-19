"""Gives the narration a studio-recording sound, in place.

A voice-over chain: rumble filter, a little warmth and presence, gentle
compression, a gate that keeps the pauses silent, and loudness levelled to
about -16 LUFS (the usual target for social media) with a peak limit.
Usage: studio.py narration.wav
"""
import sys

import numpy as np
import soundfile as sf
from scipy import signal


def biquad_peak(f0, gain_db, q, sr):
    a = 10 ** (gain_db / 40)
    w = 2 * np.pi * f0 / sr
    alpha = np.sin(w) / (2 * q)
    b = [1 + alpha * a, -2 * np.cos(w), 1 - alpha * a]
    den = [1 + alpha / a, -2 * np.cos(w), 1 - alpha / a]
    return np.array(b) / den[0], np.array(den) / den[0]


def biquad_low_shelf(f0, gain_db, sr):
    a = 10 ** (gain_db / 40)
    w = 2 * np.pi * f0 / sr
    alpha = np.sin(w) / 2 * np.sqrt(2)
    cos = np.cos(w)
    b = [a * ((a + 1) - (a - 1) * cos + 2 * np.sqrt(a) * alpha),
         2 * a * ((a - 1) - (a + 1) * cos),
         a * ((a + 1) - (a - 1) * cos - 2 * np.sqrt(a) * alpha)]
    den = [(a + 1) + (a - 1) * cos + 2 * np.sqrt(a) * alpha,
           -2 * ((a - 1) + (a + 1) * cos),
           (a + 1) + (a - 1) * cos - 2 * np.sqrt(a) * alpha]
    return np.array(b) / den[0], np.array(den) / den[0]


def envelope(x, sr, attack, release):
    """Peak follower with separate attack and release times (seconds)."""
    ga, gr = np.exp(-1 / (attack * sr)), np.exp(-1 / (release * sr))
    env = np.zeros_like(x)
    level = 0.0
    for i, v in enumerate(np.abs(x)):
        g = ga if v > level else gr
        level = g * level + (1 - g) * v
        env[i] = level
    return env


def main():
    path = sys.argv[1]
    x, sr = sf.read(path)
    x = x.astype(np.float64)
    # Rumble and handling noise.
    x = signal.sosfiltfilt(signal.butter(4, 80, "highpass", fs=sr, output="sos"), x)
    # Warmth, a small dip in the boxy range, presence and air.
    for b, a in (biquad_low_shelf(150, 2.0, sr), biquad_peak(400, -2.0, 1.0, sr),
                 biquad_peak(3500, 3.0, 0.9, sr), biquad_peak(9000, 1.5, 0.7, sr)):
        x = signal.lfilter(b, a, x)
    # Compression: 3:1 above -24 dBFS, smooth attack and release.
    env = envelope(x, sr, 0.005, 0.12)
    db = 20 * np.log10(np.maximum(env, 1e-9))
    over = np.maximum(db + 24, 0)
    x = x * 10 ** (-(over * (1 - 1 / 3)) / 20)
    # Gate: pull room noise in the pauses down by 20 dB.
    env = envelope(x, sr, 0.002, 0.15)
    gate = np.clip((20 * np.log10(np.maximum(env, 1e-9)) + 55) / 10, 0, 1)
    x = x * (0.1 + 0.9 * signal.savgol_filter(gate, int(sr * 0.02) | 1, 2))
    # Loudness of the speech to about -16 LUFS (RMS of the voiced parts).
    voiced = x[np.abs(x) > 10 ** (-40 / 20)]
    rms = np.sqrt(np.mean(voiced ** 2)) if len(voiced) else 1
    x = x * (10 ** (-16 / 20) / rms)
    # Soft peak limit at -1 dBFS.
    ceiling = 10 ** (-1 / 20)
    x = np.tanh(x / ceiling) * ceiling
    sf.write(path, x.astype(np.float32), sr, subtype="PCM_16")


main()
