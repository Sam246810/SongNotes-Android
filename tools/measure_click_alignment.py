#!/usr/bin/env python3
"""
Measures how consistently transients in a recorded take line up with the
expected metronome beat grid — a concrete way to check Phase 2's Done
criterion ("across 5 takes, transients sit at a constant offset from
gridlines with spread < 3ms") instead of eyeballing a waveform.

Usage:
    python measure_click_alignment.py <file.f32> <sample_rate> <bpm> [--beats-per-bar N]

<file.f32> is a raw mono float32 PCM file as written by the Android engine
(head-skip already applied, so frame 0 is the true downbeat, not the
pre-roll). Pull it off the device first, e.g.:

    adb pull /data/data/com.songnotes.android/files/takes/phase2_test.f32 .

No dependencies beyond the Python standard library — this deliberately
doesn't need numpy/scipy so it runs anywhere Python does, no venv setup.

What counts as a "transient" here: whatever real acoustic event ends up in
the recording near each expected beat. If you recorded via the phone's own
speaker+mic (no headphones), that's mostly the metronome click bleeding
back into the mic — which is actually a clean, convenient way to test the
engine's own timing consistency without needing a human to tap accurately.
If you actually clapped/tapped along, this measures the full engine+human
round trip instead. Either is a legitimate read on the Done criterion;
know which one you ran before drawing conclusions from the numbers.

Run once per take, then compare the printed "mean offset" and "spread"
across 5 runs by hand — that comparison IS the Done criterion. A mean
offset that's roughly the same across takes is fine (Phase 3's calibration
corrects a constant offset); a spread that blows past a few ms, or a mean
offset that jumps around between takes, is the actual bug to chase.
"""
import argparse
import array
import statistics
import sys


def load_f32_mono(path):
    with open(path, "rb") as f:
        data = f.read()
    samples = array.array("f")
    samples.frombytes(data)
    return samples


def find_peak_near(samples, sample_rate, center_frame, window_seconds):
    """Returns the frame index of the loudest ~1ms window within
    +/- window_seconds of center_frame. A short energy envelope rather than
    a single-sample peak, so an isolated noise spike doesn't win over a real
    transient's sustained energy."""
    half_window = int(window_seconds * sample_rate)
    lo = max(0, center_frame - half_window)
    hi = min(len(samples), center_frame + half_window)
    if lo >= hi:
        return None

    hop = max(1, int(0.001 * sample_rate))  # 1ms energy windows
    best_frame = None
    best_energy = -1.0
    start = lo
    while start < hi:
        end = min(start + hop, hi)
        window = samples[start:end]
        if not window:
            break
        rms = (sum(s * s for s in window) / len(window)) ** 0.5
        if rms > best_energy:
            best_energy = rms
            best_frame = start + (end - start) // 2
        start += hop
    return best_frame


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("file", help="raw mono float32 PCM take file (.f32)")
    parser.add_argument("sample_rate", type=int, help="e.g. 48000 — read off the Diagnostics screen")
    parser.add_argument("bpm", type=float)
    parser.add_argument(
        "--max-beats", type=int, default=32,
        help="stop checking after this many beats even if the file is longer",
    )
    parser.add_argument(
        "--search-window-ms", type=float, default=120.0,
        help="how far around each expected beat frame to look for a transient",
    )
    args = parser.parse_args()

    samples = load_f32_mono(args.file)
    sample_rate = args.sample_rate
    beat_interval_frames = round(sample_rate * 60.0 / args.bpm)
    duration_frames = len(samples)
    num_beats = min(args.max_beats, duration_frames // beat_interval_frames)

    if num_beats < 2:
        print("File too short for the given BPM — need at least 2 beats.", file=sys.stderr)
        sys.exit(1)

    offsets_ms = []
    for beat_index in range(num_beats):
        expected_frame = beat_index * beat_interval_frames
        found_frame = find_peak_near(
            samples, sample_rate, expected_frame, args.search_window_ms / 1000.0
        )
        if found_frame is None:
            continue
        offset_ms = (found_frame - expected_frame) * 1000.0 / sample_rate
        offsets_ms.append(offset_ms)
        print(
            f"beat {beat_index:3d}: expected frame {expected_frame:8d}, "
            f"transient at {found_frame:8d}, offset {offset_ms:+7.2f} ms"
        )

    if len(offsets_ms) < 2:
        print("Not enough detected transients to compute spread.", file=sys.stderr)
        sys.exit(1)

    mean_offset = statistics.mean(offsets_ms)
    spread = max(offsets_ms) - min(offsets_ms)
    stdev = statistics.pstdev(offsets_ms)

    print()
    print(f"mean offset:  {mean_offset:+7.2f} ms  (Phase 3 calibration is what corrects this)")
    print(f"spread:       {spread:7.2f} ms  (max - min; the plan's Done criterion wants < 3 ms)")
    print(f"stdev:        {stdev:7.2f} ms")


if __name__ == "__main__":
    main()
