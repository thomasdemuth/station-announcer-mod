#!/usr/bin/env python3
"""Synthesises the train-crew control sounds (manual driving).

Run from anywhere:   python3 tools/gen_crew_sounds.py
Check only:          python3 tools/gen_crew_sounds.py --check

GENERATED ASSETS -- NEVER HAND-EDIT THE .ogg FILES. Change the synthesis here
and re-run. Deterministic (fixed RNG seed), so a re-run reproduces the files.

Writes to src/main/resources/assets/station_announcer/sounds/ (mono, 44.1 kHz,
Ogg Vorbis, peak about -1 dBFS):

  crew_buzzer.ogg          one press of the driver/guard cab buzzer (0.30 s)
  crew_vigilance_warn.ogg  vigilance (deadman) warning, seamless 1.0 s LOOP:
                           two short high beeps, then silence
  crew_vigilance_trip.ogg  vigilance trip: falling warble alarm + brake-dump
                           hiss (1.5 s, one shot)
  horn_nyc.ogg             NYC subway two-note air horn, seamless 2.0 s LOOP
  horn_lirr.ogg            commuter-rail 3-chime air horn, seamless 2.0 s LOOP
  horn_euro.ogg            European two-tone horn, seamless 2.0 s LOOP
  horn_steam.ogg           steam chime whistle, seamless 2.0 s LOOP

HOW THE HORN LOOPS STAY SEAMLESS
--------------------------------
Every horn is built to be EXACTLY periodic in its file length L: each partial's
frequency is quantised to a multiple of 1/L (0.5 Hz for L = 2 s), vibrato and
flutter rates are integer cycles per L, and the noise is made in the frequency
domain on the circular N-sample buffer (so it wraps too). Saturation and
normalisation are memoryless, so the result is still periodic: the jump from the
last sample back to the first is just an ordinary adjacent-sample step. The
horns have no attack or decay of their own -- the client fades them in/out.
The vigilance warning loop starts and ends in silence instead.
"""

import os
import sys

import numpy as np
import soundfile as sf

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SOUNDS = os.path.join(ROOT, "src/main/resources/assets/station_announcer/sounds")

RATE = 44100
PEAK_DBFS = -1.6   # Vorbis overshoots ~0.5 dB; decoded peaks land near -1
SEED = 0x7A1C
NYQ_SAFE = 0.45 * RATE   # partials above this are dropped (no aliasing)

LOOPS = ("crew_vigilance_warn", "horn_nyc", "horn_lirr", "horn_euro", "horn_steam")


# ---------------------------------------------------------------- helpers

def times(seconds):
    return np.arange(int(round(seconds * RATE))) / RATE


def normalize(x, dbfs=PEAK_DBFS):
    peak = np.max(np.abs(x))
    return x * (10 ** (dbfs / 20) / peak) if peak > 0 else x


def cap_rms(x, dbfs=-10.0):
    """Peak-normalised saturated tones are LOUD; keep the average level at or
    below `dbfs` so horns heard at distance stay pleasant (peak drops too)."""
    rms = np.sqrt(np.mean(x ** 2))
    limit = 10 ** (dbfs / 20)
    return x * (limit / rms) if rms > limit else x


def ramp(n):
    """Raised-cosine 0->1 over n samples."""
    return 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, n))


def quant(f, length):
    """Snap a frequency to a whole number of cycles in `length` seconds."""
    return round(f * length) / length


def harmonic_tone(phase_fn, f0, amps, t):
    """Band-limited additive tone. phase_fn(t) is the fundamental's phase
    (radians); harmonic n gets n * phase. amps[n-1] = amplitude of harmonic n."""
    base = phase_fn(t)
    out = np.zeros_like(t)
    for n, a in enumerate(amps, start=1):
        if a == 0 or n * f0 > NYQ_SAFE:
            continue
        out += a * np.sin(n * base)
    return out


def periodic_noise(n, rng, lo, hi, tilt=0.0):
    """Noise that wraps seamlessly over n samples: random-phase spectrum with
    energy only between lo..hi Hz (soft edges), optional 1/f^tilt slope."""
    freqs = np.fft.rfftfreq(n, 1 / RATE)
    mag = np.zeros_like(freqs)
    band = (freqs >= lo * 0.7) & (freqs <= hi * 1.4)
    f = freqs[band]
    # soft bandpass: smooth shoulders an octave-ish wide
    shape = 1 / (1 + (lo / np.maximum(f, 1)) ** 4) / (1 + (f / hi) ** 4)
    mag[band] = shape / np.maximum(f, 1) ** (tilt / 2)
    spec = mag * np.exp(1j * rng.uniform(0, 2 * np.pi, len(freqs)))
    x = np.fft.irfft(spec, n)
    return x / (np.std(x) + 1e-12)


def fft_bandpass(x, lo, hi):
    """Non-circular-safe bandpass (zero-padded) for one-shot sounds."""
    n = len(x)
    m = 1 << (2 * n - 1).bit_length()
    spec = np.fft.rfft(x, m)
    freqs = np.fft.rfftfreq(m, 1 / RATE)
    spec *= 1 / (1 + (lo / np.maximum(freqs, 1)) ** 4) / (1 + (freqs / hi) ** 4)
    return np.fft.irfft(spec, m)[:n]


def fft_lowpass_circular(x, cutoff):
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / RATE)
    spec *= 1 / (1 + (freqs / cutoff) ** 4)
    return np.fft.irfft(spec, len(x))


# ---------------------------------------------------------------- sounds

def crew_buzzer(rng):
    """Relay buzzer: rich ~240 Hz saw/square, armature flutter, 25 ms release."""
    dur = 0.30
    t = times(dur)
    f0 = 240.0
    # slightly unsteady pitch like a vibrating armature
    wobble = 1 + 0.006 * np.sin(2 * np.pi * 17 * t)
    phase = lambda tt: 2 * np.pi * np.cumsum(f0 * wobble) / RATE
    amps = []
    for n in range(1, 60):
        saw = 1.0 / n
        sq = (1.0 / n) if n % 2 else 0.0
        amps.append(0.55 * saw + 0.45 * sq)
    x = harmonic_tone(phase, f0, amps, t)
    # relay chatter: amplitude flutter at the mains-ish rate, plus grit
    flutter = 0.72 + 0.28 * np.sign(np.sin(2 * np.pi * 60 * t)) * (
        0.6 + 0.4 * np.sin(2 * np.pi * 120 * t))
    x *= flutter
    x += 0.10 * fft_bandpass(rng.standard_normal(len(t)), 1500, 6000)
    x = np.tanh(1.6 * x / np.max(np.abs(x)))
    # tame the very top so it is harsh but not painful
    x = fft_bandpass(x, 120, 5500)
    env = np.ones_like(t)
    a = int(0.002 * RATE)
    r = int(0.025 * RATE)
    env[:a] = ramp(a)
    env[-r:] = ramp(r)[::-1]
    return cap_rms(normalize(x * env, -1.5), -9.0)


def crew_vigilance_warn(rng):
    """Seamless 1.0 s loop: beep (90 ms), gap (120 ms), beep (90 ms), silence.
    Starts after 20 ms of silence and ends in silence, so the seam is silent."""
    dur = 1.0
    t = times(dur)
    x = np.zeros_like(t)
    f = 1600.0
    beep_len = 0.090
    edge = int(0.005 * RATE)
    for start in (0.020, 0.020 + beep_len + 0.120):
        i0 = int(start * RATE)
        n = int(beep_len * RATE)
        tt = np.arange(n) / RATE
        # piezo: fundamental + a bit of odd harmonics
        b = (np.sin(2 * np.pi * f * tt) + 0.30 * np.sin(2 * np.pi * 3 * f * tt)
             + 0.10 * np.sin(2 * np.pi * 5 * f * tt))
        env = np.ones(n)
        env[:edge] = ramp(edge)
        env[-edge:] = ramp(edge)[::-1]
        x[i0:i0 + n] += b * env
    return normalize(x, -2.0)


def crew_vigilance_trip(rng):
    """1.5 s: fast falling warble alarm, brake-dump 'psssh' in the second half."""
    dur = 1.5
    t = times(dur)
    # falling base pitch, warbled at 11 Hz
    base = 1500.0 * (700.0 / 1500.0) ** (t / dur)
    inst = base * (1 + 0.09 * np.sin(2 * np.pi * 11 * t))
    phase_arr = 2 * np.pi * np.cumsum(inst) / RATE
    alarm = np.zeros_like(t)
    for n, a in ((1, 1.0), (2, 0.25), (3, 0.35), (5, 0.12)):
        alarm += a * np.sin(n * phase_arr)
    # chopped into urgent pulses (on 70 %), 9 per second
    gate = 0.5 + 0.5 * np.tanh(6 * np.sin(2 * np.pi * 9 * t + 0.4))
    alarm *= 0.25 + 0.75 * gate
    a_env = np.ones_like(t)
    a_env[:int(0.004 * RATE)] = ramp(int(0.004 * RATE))
    fade = int(0.35 * RATE)
    a_env[-fade:] = ramp(fade)[::-1]
    alarm *= a_env

    # pneumatic dump: bandpassed noise, sharp onset at 0.72 s, long decay
    hiss = fft_bandpass(rng.standard_normal(len(t)), 1800, 9000)
    hiss /= np.std(hiss)
    h_env = np.zeros_like(t)
    on = t >= 0.72
    th = t[on] - 0.72
    h_env[on] = (1 - np.exp(-th / 0.012)) * np.exp(-th / 0.38)
    # little pressure flutter in the hiss
    h_env *= 1 + 0.15 * np.sin(2 * np.pi * 23 * t)
    tail = int(0.03 * RATE)
    h_env[-tail:] *= ramp(tail)[::-1]
    x = 0.85 * alarm / np.max(np.abs(alarm)) + 0.45 * hiss * h_env
    return normalize(x)


def _air_horn(rng, notes, length, harm_rolloff, odd_boost, drive, air, lp,
              chorus=0.0, flutter_hz=2.0, flutter=0.03):
    """Shared exactly-periodic air-horn bell: per-note additive tone, soft
    saturation (brass + intermodulation beating), air noise, lowpass."""
    t = times(length)
    n = len(t)
    x = np.zeros(n)
    for i, f in enumerate(notes):
        detunes = [0.0] if chorus == 0 else [0.0, chorus, -chorus]
        for d in detunes:
            fq = quant(f + d, length)
            amps = []
            for h in range(1, 40):
                a = h ** -harm_rolloff
                if h % 2:
                    a *= odd_boost
                amps.append(a)
            ph0 = rng.uniform(0, 2 * np.pi)
            tone = harmonic_tone(lambda tt: 2 * np.pi * fq * tt + ph0, fq, amps, t)
            x += tone / (1 if d == 0 else 2.2)
    x /= np.max(np.abs(x))
    x = np.tanh(drive * x)
    # slow steady flutter of the air column (integer cycles per loop)
    fl = quant(flutter_hz, length)
    x *= 1 + flutter * np.sin(2 * np.pi * fl * t)
    x += air * periodic_noise(n, rng, 600, 7000, tilt=0.5) * np.std(x)
    x = fft_lowpass_circular(x, lp)
    return cap_rms(normalize(x))


def horn_nyc(rng):
    """NYC subway: two brassy, slightly sour notes ~311 + 370 Hz, strong odd
    harmonics, hard-driven so the pair growls against each other."""
    return _air_horn(rng, notes=(311.0, 370.0), length=2.0, harm_rolloff=0.85,
                     odd_boost=1.8, drive=2.6, air=0.06, lp=4200,
                     flutter_hz=3.0, flutter=0.025)


def horn_lirr(rng):
    """Commuter-rail 3-chime (K3LA-style ~311/370/440 Hz), fuller: a little
    chorus per bell and a sub-octave body under the low bell."""
    x = _air_horn(rng, notes=(311.0, 370.0, 440.0, 155.5), length=2.0,
                  harm_rolloff=0.75, odd_boost=1.4, drive=2.0, air=0.05,
                  lp=5000, chorus=0.5, flutter_hz=2.0, flutter=0.03)
    return x


def horn_euro(rng):
    """European two-tone: clear reedy tones a major third apart
    (~370 + 466 Hz), brighter and much less distorted than the NYC horn."""
    return _air_horn(rng, notes=(370.0, 466.0), length=2.0, harm_rolloff=1.15,
                     odd_boost=1.15, drive=1.1, air=0.03, lp=7000,
                     flutter_hz=1.0, flutter=0.015)


def horn_steam(rng):
    """Steam chime whistle: A major chord ~440/554/659 Hz, nearly pure tones
    with a little 2nd harmonic, gentle 5 Hz vibrato, breathy band noise."""
    length = 2.0
    t = times(length)
    n = len(t)
    vib = quant(5.0, length)           # 10 cycles per loop
    x = np.zeros(n)
    for f, amp in ((440.0, 1.0), (554.0, 0.85), (659.0, 0.7)):
        fq = quant(f, length)
        beta = fq * 0.004 / vib        # +-0.4 % pitch vibrato (FM index)
        ph0 = rng.uniform(0, 2 * np.pi)
        vph = rng.uniform(0, 0.3)
        phase = lambda tt: (2 * np.pi * fq * tt + ph0
                            + beta * np.sin(2 * np.pi * vib * tt + vph))
        x += amp * harmonic_tone(phase, fq, (1.0, 0.22, 0.06), t)
        # breath around each pipe's pitch
        x += 0.18 * amp * periodic_noise(n, rng, fq * 0.9, fq * 1.12)
    x /= np.max(np.abs(x))
    # broadband steam rush, gently pulsing with the vibrato
    rush = periodic_noise(n, rng, 1200, 9000, tilt=0.3)
    x += 0.07 * rush * (1 + 0.3 * np.sin(2 * np.pi * vib * t))
    x *= 1 + 0.04 * np.sin(2 * np.pi * vib * t + 1.0)
    x = fft_lowpass_circular(x, 9000)
    return normalize(x)


SOUNDS_TO_MAKE = (crew_buzzer, crew_vigilance_warn, crew_vigilance_trip,
                  horn_nyc, horn_lirr, horn_euro, horn_steam)


# ---------------------------------------------------------------- check

def check():
    ok = True
    for fn in SOUNDS_TO_MAKE:
        name = fn.__name__
        path = os.path.join(SOUNDS, name + ".ogg")
        x, sr = sf.read(path)
        if x.ndim > 1:
            x = x[:, 0]
        peak = np.max(np.abs(x))
        rms = np.sqrt(np.mean(x ** 2))
        line = (f"{name:22s} {len(x) / sr:6.3f} s  peak {20 * np.log10(peak):6.2f} dBFS"
                f"  rms {20 * np.log10(rms):6.1f} dBFS")
        if peak >= 0.999:
            ok = False
            line += "  CLIPS"
        if name in LOOPS:
            k = int(0.010 * sr)
            jump = abs(x[-1] - x[0])
            steps = np.abs(np.diff(x))
            typ = np.percentile(steps, 99)
            rms_head = np.sqrt(np.mean(x[:k] ** 2))
            rms_tail = np.sqrt(np.mean(x[-k:] ** 2))
            # curvature across the seam vs. everywhere else: a click shows
            # up as a second difference far outside the file's own range
            d2 = np.abs(np.diff(x, 2))
            seam_d2 = max(abs(x[-2] - 2 * x[-1] + x[0]), abs(x[-1] - 2 * x[0] + x[1]))
            line += f"  seam d2 {seam_d2:.4f} (p99 {np.percentile(d2, 99):.4f})"
            if seam_d2 > max(np.percentile(d2, 99), 0.002) * 1.2:
                ok = False
                line += "  CURVE?"
            line += (f"  seam jump {jump:.4f} (p99 step {typ:.4f})"
                     f"  rms head {rms_head:.4f} tail {rms_tail:.4f}")
            if jump > max(typ, 0.002) * 1.2:
                ok = False
                line += "  CLICK?"
        print(line)
    return ok


def main():
    if "--check" not in sys.argv:
        for i, fn in enumerate(SOUNDS_TO_MAKE):
            rng = np.random.default_rng(SEED + i)
            x = fn(rng).astype(np.float32)
            sf.write(os.path.join(SOUNDS, fn.__name__ + ".ogg"), x, RATE,
                     format="OGG", subtype="VORBIS")
    sys.exit(0 if check() else 1)


if __name__ == "__main__":
    main()
