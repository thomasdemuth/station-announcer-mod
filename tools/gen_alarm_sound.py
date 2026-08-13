#!/usr/bin/env python3
"""Synthesises the emergency-exit gate alarm.

Run from anywhere:  python3 tools/gen_alarm_sound.py

WHAT IT IS
----------
The MTA emergency-exit door alarm, from the user's description: rapid beeping
AND a two-tone whoop at once. So it is neither a plain buzzer nor a plain
beeper — it is a fast on/off pulse train whose pitch alternates between a high
and a low tone every pulse, which is what gives that nagging hi-lo-hi-lo
urgency you cannot ignore across a mezzanine.

Built to LOOP: exactly 2.0 s holding a whole number of pulse pairs, so the
looping sound instance repeats without a seam or a phase jump. The block plays
it repeatedly for the alarm's lifetime rather than baking a 15 s file.

The tone is a square-ish wave (odd harmonics, rolled off) rather than a sine —
a sine reads as a doorbell, and the real thing is a cheap piezo sounder.
"""

import numpy as np
import os
import soundfile as sf

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SOUNDS = os.path.join(ROOT, "src/main/resources/assets/station_announcer/sounds")

RATE = 44100
HIGH_HZ = 1180.0          # piercing, but under the range that just hurts
LOW_HZ = 880.0
PULSES_PER_SECOND = 5.0   # "rapid beeping"
DUTY = 0.62               # on for most of each pulse; the gaps are what make it nag
LOOP_SECONDS = 2.0


def piezo(freq, samples, phase):
    """A cheap sounder: odd harmonics at 1/n, which lands between square and sine."""
    t = (np.arange(samples) / RATE) + phase
    wave = np.zeros(samples)
    for harmonic, gain in ((1, 1.0), (3, 0.34), (5, 0.16), (7, 0.08)):
        wave += gain * np.sin(2 * np.pi * freq * harmonic * t)
    return wave / 1.58


def main():
    pulse_samples = int(RATE / PULSES_PER_SECOND)
    total = int(RATE * LOOP_SECONDS)
    pulses = total // pulse_samples
    # A whole number of PAIRS, so the hi/lo alternation is continuous at the seam.
    if pulses % 2:
        pulses -= 1
    total = pulses * pulse_samples

    out = np.zeros(total)
    on_samples = int(pulse_samples * DUTY)
    # 4 ms edges: enough to kill the click, short enough to stay percussive.
    edge = int(RATE * 0.004)
    envelope = np.ones(on_samples)
    envelope[:edge] = np.linspace(0.0, 1.0, edge)
    envelope[-edge:] = np.linspace(1.0, 0.0, edge)

    phase = 0.0
    for i in range(pulses):
        freq = HIGH_HZ if i % 2 == 0 else LOW_HZ
        start = i * pulse_samples
        out[start:start + on_samples] = piezo(freq, on_samples, phase) * envelope
        # Keep advancing the phase clock so successive pulses of the same tone
        # stay coherent; a reset per pulse adds a faint chirp.
        phase += pulse_samples / RATE

    out *= 0.72
    sf.write(os.path.join(SOUNDS, "gate_alarm.ogg"), out.astype(np.float32), RATE, format="OGG")
    print(f"gate_alarm.ogg: {total / RATE:.2f} s, {pulses} pulses ({pulses // 2} hi/lo pairs)")


if __name__ == "__main__":
    main()
