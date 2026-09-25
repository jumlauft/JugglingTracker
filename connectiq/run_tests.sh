#!/bin/bash
# Builds and runs the Connect IQ unit tests in test/ against the simulator.
#
# These are real tests: they compile the actual source in source/ and execute
# it, so they fail when behaviour changes rather than when text moves around.
# See REQUIREMENTS.md for what each one secures.
#
# Usage: ./run_tests.sh [device]        (default device: fr245)
set -euo pipefail

DEVICE="${1:-fr245}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

SDK_ROOT="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks"
SDK_BIN="$(ls -d "$SDK_ROOT"/connectiq-sdk-mac-*/bin 2>/dev/null | sort | tail -1)"
if [ -z "$SDK_BIN" ]; then
    echo "No Connect IQ SDK found under $SDK_ROOT" >&2
    exit 1
fi

if [ ! -f developer_key.der ]; then
    echo "developer_key.der is missing; it is required to build and is not in git." >&2
    exit 1
fi

echo "Building tests for $DEVICE..."
"$SDK_BIN/monkeyc" -f test.jungle -d "$DEVICE" -o build/test.prg \
    -y developer_key.der -t 2>&1 | grep -v "launcher icon" || true

# monkeydo needs the simulator running; starting it twice is harmless.
if ! pgrep -f "ConnectIQ.app" >/dev/null; then
    echo "Starting simulator..."
    open -a "$SDK_BIN/ConnectIQ.app"
    sleep 12
fi

echo "Running tests..."
# monkeydo's exit code is useless here: it returns 1 even when every test
# passes. The "|| true" keeps set -e from aborting on that, and the summary
# line it prints is what actually decides the verdict.
OUTPUT="$("$SDK_BIN/monkeydo" build/test.prg "$DEVICE" -t 2>&1 || true)"
echo "$OUTPUT"

if echo "$OUTPUT" | grep -q "^FAILED"; then
    exit 1
fi
if ! echo "$OUTPUT" | grep -q "^PASSED"; then
    echo "No PASSED/FAILED summary in the test output; treating as failure." >&2
    exit 1
fi
echo "All tests passed."
