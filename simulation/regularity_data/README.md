# Regularity recordings

Four 3-ball runs by Jonas Umlauft (watch on the left wrist), recorded on
2026-09-30 on purpose as regular or messy juggling. The `regularity=` field
at the end of each header is that label; the rest of the file is exactly as
exported.

| Run | Label |
|---|---|
| 20260930_184904 | regular, low throws |
| 20260930_185035 | regular, high throws |
| 20260930_185258 | very messy |
| 20260930_185440 | medium messy |

They are kept out of `connectiq/data` because they are for the shape
consistency metric (`../shape_consistency.py`), not the catch-count corpus.
`test_detection.py` pins their scores in `EXPECTED_SHAPE`, and the Wear OS
`ShapeConsistencyTest` replays them through the Kotlin port.
