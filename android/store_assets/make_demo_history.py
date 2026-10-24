"""Writes the made-up history the Play screenshots show, as sessions.jsonl.

The same three months of practice as the iPhone app's screenshot mode
(apple/JugglingTracker/App/ScreenshotMode.swift, demoSessions), with the same
seeded random numbers, so both stores show the same sessions. The newest
session is yesterday evening. See android/PUBLISHING.md for how to load it
into the emulator.

    python3 make_demo_history.py > sessions.jsonl
"""

import datetime
import json
import math

MASK = (1 << 64) - 1


class SeededRandom:
    """SplitMix64, as in ScreenshotMode.swift."""

    def __init__(self, seed):
        self.state = seed

    def next(self):
        self.state = (self.state + 0x9E3779B97F4A7C15) & MASK
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK
        return z ^ (z >> 31)

    def between(self, low, high):
        return low + (high - low) * ((self.next() >> 11) / float(1 << 53))


def swift_round(x):
    """Swift's rounded(): halves away from zero."""
    return int(math.floor(abs(x) + 0.5)) * (1 if x >= 0 else -1)


# balls, sessions, first day (days ago), average from, average to, ms per catch
PLANS = [(3, 16, 88, 9, 38, 500), (4, 12, 62, 4, 16, 560), (5, 9, 34, 2, 8, 620)]


def demo_sessions(today):
    random = SeededRandom(2026)
    sessions = []
    for balls, count, first_days_ago, average_from, average_to, catch_ms in PLANS:
        for i in range(count):
            progress = i / (count - 1)
            curve = 1 - (1 - progress) * (1 - progress)
            mean = (average_from + (average_to - average_from) * curve) * random.between(0.85, 1.15)
            runs = [max(1, swift_round(mean * random.between(0.35, 1.65)))
                    for _ in range(int(random.between(8, 16)))]
            durations = [r * catch_ms + int(random.between(-150, 150)) for r in runs]
            days_ago = max(1, swift_round(first_days_ago * (1 - progress)))
            day = today - datetime.timedelta(days=days_ago)
            timestamp = day.timestamp() + random.between(17.5, 20.5) * 3600
            average = sum(runs) / len(runs)
            spread = math.sqrt(sum((r - average) ** 2 for r in runs) / len(runs)) if len(runs) > 1 else 0.0
            sessions.append({
                "timestamp": int(timestamp * 1000),
                "ballCount": balls,
                "runCount": len(runs),
                "avgThrows": average,
                "stdDevThrows": spread,
                "bestRun": max(runs),
                "totalThrows": sum(runs),
                "runHistory": runs,
                "durationSeconds": sum(durations) // 1000 + len(runs) * 25,
                "runDurationsMillis": durations,
                "shapeConsistency": int(58 + 28 * progress + random.between(-4, 4)),
            })
    # SessionRepository keeps the file oldest first.
    return sorted(sessions, key=lambda s: s["timestamp"])


if __name__ == "__main__":
    now = datetime.datetime.now()
    for session in demo_sessions(datetime.datetime(now.year, now.month, now.day)):
        print(json.dumps(session))
