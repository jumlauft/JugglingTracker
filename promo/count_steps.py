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
# Before the count starts the tracker shows "0" for a while: the first catch
# is the first change that follows a still stretch of 1.5 s or more and is
# followed by another change within 1.5 s (the screens before it change
# slowly). The last change is the run ending.
start = next(k for k in range(1, len(changes) - 1)
             if changes[k] - changes[k - 1] >= 45 and changes[k + 1] - changes[k] < 45)
steps, end = changes[start:-1], changes[-1]
open("counts.txt", "w").write("".join(f"{f} {k + 1}\n" for k, f in enumerate(steps)))
print(f"{len(steps)} catches, run ends at frame {end}")
