#!/usr/bin/env python3
"""Writes app/src/main/res/raw/listen_chime.ogg: the pause Listen plays between articles.

A soft drone that swells in and out like a breath, with a page turning as it blooms. The
quiet before and after it is part of the file, so the listener has time to let one article
go before the next begins: the next starts when the file ends.

The page turn is bookFlip1.ogg from Kenney's RPG Audio pack (https://kenney.nl/assets/rpg-audio),
which is CC0. The drone is made here. Needs numpy and ffmpeg.

    python3 tools/make-chime.py path/to/bookFlip1.ogg
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

RATE = 44100
# Quiet after the article, then the sound, the next article starting as it settles.
BEFORE = 0.8
SOUND = 3.2
# An open fifth (D3, A3) and a faint octave above: calm, and no strike to sound like an alert.
DRONE = [(146.8, 1.0), (220.0, 0.8), (440.6, 0.15)]
SWELL_IN, SWELL_HOLD, SWELL_OUT = 0.9, 0.2, 1.3
# Where the page turns, into the sound: as the drone blooms.
PAGE_AT = 0.6
# As heard against Kokoro, whose speech peaks near 0.66: well under it.
DRONE_PEAK, PAGE_PEAK = 0.2, 0.26


def swell(t):
    y = np.zeros_like(t)
    rise = (t >= 0) & (t < SWELL_IN)
    y[rise] = 0.5 - 0.5 * np.cos(np.pi * t[rise] / SWELL_IN)
    y[(t >= SWELL_IN) & (t < SWELL_IN + SWELL_HOLD)] = 1
    fall = t >= SWELL_IN + SWELL_HOLD
    y[fall] = np.maximum(0, 0.5 + 0.5 * np.cos(np.pi * np.minimum(1, (t[fall] - SWELL_IN - SWELL_HOLD) / SWELL_OUT)))
    return y


def reverb(x, mix):
    # A small Schroeder reverb: a room around the drone, so it fades rather than stops.
    wet = np.zeros_like(x)
    for delay, gain in [(1557, 0.80), (1617, 0.79), (1491, 0.78), (1422, 0.77)]:
        buf, y, k = np.zeros(delay), np.zeros_like(x), 0
        for i in range(len(x)):
            y[i] = buf[k]
            buf[k] = x[i] + y[i] * gain
            k = (k + 1) % delay
        wet += y / 4
    for delay, gain in [(225, 0.5), (556, 0.5)]:
        buf, y, k = np.zeros(delay), np.zeros_like(wet), 0
        for i in range(len(wet)):
            b = buf[k]
            y[i] = -wet[i] + b
            buf[k] = wet[i] + b * gain
            k = (k + 1) % delay
        wet = y
    return (1 - mix) * x + mix * wet


def read_mono(path):
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "page.wav")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-ac", "1", "-ar", str(RATE), wav], check=True)
        with wave.open(wav) as w:
            return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768


def main(page_path):
    t = np.arange(int(SOUND * RATE)) / RATE
    drone = swell(t) * sum(level * np.sin(2 * np.pi * f * t) for f, level in DRONE)
    drone = reverb(drone, 0.4)
    sound = DRONE_PEAK * drone / np.abs(drone).max()
    page = read_mono(page_path)
    at = int(PAGE_AT * RATE)
    sound[at:at + len(page)] += PAGE_PEAK * page[:len(sound) - at] / np.abs(page).max()
    # Faded to nothing by the end, where the next article begins.
    fade = int(0.6 * RATE)
    sound[-fade:] *= np.linspace(1, 0, fade)
    out = np.concatenate([np.zeros(int(BEFORE * RATE)), sound])
    path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw", "listen_chime.ogg")
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "chime.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes((np.clip(out, -1, 1) * 32767).astype("<i2").tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "4", path], check=True)
    print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
