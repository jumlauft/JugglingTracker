"""Cuts a 5-ball recording down to its first N samples and adds a few seconds
of a wrist held still, so the replayed run stops and the watch app ends it by
itself (RUN-6). Usage: make_replay.py <recording.csv> <samples> <out.csv>"""
import random
import sys

src, n, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
lines = open(src).read().splitlines()
head, data = lines[:2], lines[2:]
random.seed(1)
rest = [f"{100 + random.randint(-8, 8)},{210 + random.randint(-8, 8)},{-1000 + random.randint(-8, 8)}" for _ in range(200)]
open(out, "w").write("\n".join(head + data[:n] + rest) + "\n")
