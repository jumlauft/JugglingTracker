"""Finds the frames where the count on the recorded watch screen changes.
Writes counts.txt ("frame count" per catch) and prints the frame where the
run ends (the count goes back to 0). Usage: count_steps.py <frames dir>"""
import os
import sys

import numpy as np
from PIL import Image

d = sys.argv[1]
n = len(os.listdir(d))
prev, changes = None, []
for i in range(200, n):
    # The big number sits between y 150 and 390 of the 416x496 screen.
    a = np.asarray(Image.open(f"{d}/{i:05d}.jpg").convert("L"))[150:390, 30:386].astype(int)
    if prev is not None and np.abs(a - prev).mean() > 3:
        if not changes or i - changes[-1] > 3:
            changes.append(i)
    prev = a
# The first change is the tracker opening, the last one the run ending.
steps, end = changes[1:-1], changes[-1]
open("counts.txt", "w").write("".join(f"{f} {k + 1}\n" for k, f in enumerate(steps)))
print(f"{len(steps)} catches, run ends at frame {end}")
