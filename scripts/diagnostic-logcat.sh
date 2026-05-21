#!/usr/bin/env bash
# Tail logcat filtered to the in-match diagnostic stream — bucketed picks,
# chain-stored arrays at reveal, replay publishes, state transitions,
# and the "DIAGNOSTIC:" cell dump that fires when the parser sees a
# non-zero score but at least one regulation Vector parses as all zeros
# (the wrong-cell layout bug from 2026-05-20).
#
# Usage:
#   ./diagnostic-logcat.sh                # default: both emulators tagged
#   ./diagnostic-logcat.sh emulator-5554  # single device
#   ./diagnostic-logcat.sh emulator-5556  # single device
#
# Tip: pipe to `tee /tmp/kicks-diag.log` to keep the output for later:
#   ./diagnostic-logcat.sh | tee /tmp/kicks-diag.log

set -u

PATTERN='DIAGNOSTIC:|submitP1Picks:|submitP2Picks:|submitP1SdPick|submitP2SdPick|regulation reveal landed|publishRegulationReplay|publishSdRoundReplay|choicesLocked-(IN|OUT):|MatchManager: state:'

stream_one() {
  local serial="$1"
  local tag="$2"
  adb -s "$serial" logcat -v threadtime \
    | sed -u "s/^/[$tag] /"
}

if [ "$#" -ge 1 ]; then
  # Single-device mode — no per-line tag prefix; just stream + filter.
  adb -s "$1" logcat -v threadtime \
    | grep --line-buffered -E "$PATTERN"
else
  # Multi-device mode — tag each line with its emulator serial so the
  # two device streams stay legible when interleaved on stdout. The
  # `wait` keeps the script running until both adb processes exit.
  ( stream_one emulator-5554 5554 &
    stream_one emulator-5556 5556 &
    wait
  ) \
    | grep --line-buffered -E "$PATTERN"
fi
