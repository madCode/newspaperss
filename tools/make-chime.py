#!/usr/bin/env python3
"""Writes app/src/main/res/raw/listen_chime.ogg: the soft two-note chime Listen plays between articles.

Made here rather than taken from a sound library, so there's no licence to track. Needs ffmpeg.
    python3 tools/make-chime.py
"""
import math
import os
import struct
import subprocess
import tempfile
import wave

RATE = 44100
# A rising fourth, G5 then C6: a "next" sound rather than an alert.
NOTES = [(0.0, 784.0), (0.16, 1046.5)]
LENGTH = 1.4
# Partials of a small bell, quieter as they go up, so it rings rather than beeps.
PARTIALS = [(1.0, 1.0), (2.76, 0.25), (5.4, 0.08)]
DECAY = 0.35
# Well under the voice, which it sits between: about -14 dBFS at its peak.
PEAK = 0.2


def sample(t):
    total = 0.0
    for start, pitch in NOTES:
        s = t - start
        if s < 0:
            continue
        attack = min(1.0, s / 0.004)
        for ratio, level in PARTIALS:
            total += level * attack * math.exp(-s / (DECAY / ratio ** 0.5)) * math.sin(2 * math.pi * pitch * ratio * s)
    return total


samples = [sample(i / RATE) for i in range(int(LENGTH * RATE))]
scale = PEAK / max(abs(s) for s in samples)
out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw", "listen_chime.ogg")
with tempfile.TemporaryDirectory() as tmp:
    wav = os.path.join(tmp, "chime.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(s * scale * 32767)) for s in samples))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "4", out], check=True)
print("wrote", os.path.normpath(out))
