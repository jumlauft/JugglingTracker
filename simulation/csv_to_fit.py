"""
Convert a recorded connectiq/data run (one file, x,y,z in milli-g at a fixed
sample rate) into a .fit activity file the Connect IQ simulator can replay
into Sensor.registerSensorDataListener() via Simulation > Fit Data >
Playback File. This lets the real recorded corpus drive the watch app's
actual detector inside the simulator, on any device profile, instead of
only checking that sensor registration succeeds.

Caveat: the .fit files this produces are well-formed, but in practice the
simulator's Fit Data playback did not reach a registered accelerometer
listener on the device profiles tried, so the app saw no samples. Replaying
the corpus through the real compiled detector was done instead by generating
a throwaway project that imports JugglingDetector.mc and feeds it hardcoded
sample arrays. Keep that in mind before spending time on this path.

Usage:
    python3 csv_to_fit.py <run.csv> <out.fit>
"""
import datetime
import sys

from fit_tool.fit_file_builder import FitFileBuilder
from fit_tool.profile.messages.accelerometer_data_message import (
    AccelerometerDataCalibratedAccelXField,
    AccelerometerDataCalibratedAccelYField,
    AccelerometerDataCalibratedAccelZField,
    AccelerometerDataMessage,
)
from fit_tool.profile.messages.event_message import EventMessage
from fit_tool.profile.messages.file_id_message import FileIdMessage
from fit_tool.profile.profile_type import Event, EventType, FileType, Manufacturer

from data_utils import parse_runs


def _set_calibrated_accel(msg: AccelerometerDataMessage, field_id: int, values: list) -> None:
    # fit_tool's generic Field.encode_value() does `int(value)` whenever
    # scale == 1 and offset == 0, which is true for these fields, truncating
    # every fractional g value to 0 regardless of the field's FLOAT32 base
    # type. set_encoded_value() bypasses that cast and stores the float
    # directly, which packs correctly since the base type is respected there.
    field = msg.get_field(field_id)
    for i, v in enumerate(values):
        field.set_encoded_value(i, v)

# Samples per AccelerometerDataMessage batch. The FIT profile caps this
# field's array length; 25 (one second at the corpus's 25 Hz) is well inside
# it and keeps sample_time_offset values small.
BATCH_SIZE = 25


def build_fit(run: dict, out_path: str) -> None:
    sample_rate = run["meta"]["sampleRate"]
    period_ms = round(1000 / sample_rate)

    xs, ys, zs = run["x"], run["y"], run["z"]
    n = min(len(xs), len(ys), len(zs))

    start = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    start_ms = round(start.timestamp() * 1000)

    builder = FitFileBuilder(auto_define=True, min_string_size=0)

    file_id = FileIdMessage()
    file_id.type = FileType.ACTIVITY
    file_id.manufacturer = Manufacturer.GARMIN.value
    file_id.product = 0
    file_id.time_created = start_ms
    file_id.serial_number = 0x1234
    builder.add(file_id)

    start_event = EventMessage()
    start_event.event = Event.TIMER
    start_event.event_type = EventType.START
    start_event.timestamp = start_ms
    builder.add(start_event)

    i = 0
    while i < n:
        batch_end = min(i + BATCH_SIZE, n)
        batch_start_ms = start_ms + round(i * period_ms)

        msg = AccelerometerDataMessage()
        msg.timestamp = batch_start_ms
        msg.timestamp_ms = 0
        msg.sample_time_offset = [round((j - i) * period_ms) for j in range(i, batch_end)]
        # Corpus values are milli-g; the FIT calibrated_accel_* fields are g.
        _set_calibrated_accel(msg, AccelerometerDataCalibratedAccelXField.ID, [v / 1000.0 for v in xs[i:batch_end]])
        _set_calibrated_accel(msg, AccelerometerDataCalibratedAccelYField.ID, [v / 1000.0 for v in ys[i:batch_end]])
        _set_calibrated_accel(msg, AccelerometerDataCalibratedAccelZField.ID, [v / 1000.0 for v in zs[i:batch_end]])
        builder.add(msg)

        i = batch_end

    end_ms = start_ms + round((n - 1) * period_ms)
    stop_event = EventMessage()
    stop_event.event = Event.TIMER
    stop_event.event_type = EventType.STOP
    stop_event.timestamp = end_ms
    builder.add(stop_event)

    fit_file = builder.build()
    fit_file.to_file(out_path)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)

    csv_path, out_path = sys.argv[1], sys.argv[2]
    runs = parse_runs(csv_path)
    if len(runs) != 1:
        print(f"{csv_path} holds {len(runs)} runs, expected exactly one")
        sys.exit(1)

    build_fit(runs[0], out_path)
    meta = runs[0]["meta"]
    print(
        f"Wrote {out_path}: run={meta.get('run')} balls={meta.get('balls')} "
        f"catches={meta.get('catches')} samples={len(runs[0]['x'])}"
    )


if __name__ == "__main__":
    main()
