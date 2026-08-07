#!/usr/bin/env python3
"""Generate the LIRR M7 vehicle sound set for MTR (BVE-style).

Everything here is SYNTHESISED from scratch with numpy — no donor audio is read,
resampled or embedded.  The openBVE M7 pack was measured (durations, spectral
peaks, chime timing) and those measurements are recorded as comments next to the
constants they informed; the waveforms themselves are built from oscillators and
shaped noise.

    python3 tools/gen_m7_sounds.py

Outputs (all regenerated from scratch every run):

    src/main/resources/assets/station_announcer/sounds/m7/*.ogg
    src/main/resources/assets/station_announcer/sounds/m7/sound.cfg
    src/main/resources/assets/station_announcer/sounds/m7/{power,brake}{vol,freq}.csv
    src/main/resources/assets/station_announcer/sounds.json   (m7_* keys only)

Wire it to a vehicle with   "bveSoundBaseResource": "station_announcer:m7".

------------------------------------------------------------------ how MTR uses this
(All verified against minecraft-transit-railway-FABRIC-4.0.5+1.20.4.jar with javap.)

`BveVehicleSoundConfig(baseName)` parses the string as an Identifier — WITH a
namespace if it contains a colon, else namespace "mtr".  From it:

    config  file   <ns>:sounds/<path>/sound.cfg
    motor tables   <ns>:sounds/<path>/{power,brake}{vol,freq}.csv   (MotorNoiseDataType 5)
                   <ns>:sounds/<path>/train.dat                     (MotorNoiseDataType 4)
    sound events   <ns>:<path>_<value>        e.g. station_announcer:m7_motor0

`<value>` is the sound.cfg value lowercased with ".wav", whitespace and any
leading directory stripped.  Each event still has to exist in a sounds.json, so
we emit `m7_*` keys into our own assets/station_announcer/sounds.json.

Looping (pitch + volume driven every frame): motor0..N, run0, flange0, noise, shoe,
compressor loop.  One-shot: joint, air/airZero/airHigh, emergency, door open/close,
brake-handle apply/release, compressor attack/release.

Pitch/volume laws MTR applies, which is what the numbers below are tuned against
(re-verified against BveVehicleSound.playMotorSound / VehicleSoundBase 2026-07-28):

    run     volume = min(1, v_ms * 0.04)    pitch = v_ms * 0.04
            -> run0.ogg is authored to sound correct at 25 m/s (90 km/h).
    motor_i volume = powerVolume[i](v_kmh) * |output| * MotorVolumeMultiply
            pitch  = powerFrequency[i](v_kmh)          (brake* tables when decelerating)
    shoe    pitch  = 1 + 1/(v_ms + 1)
            volume = 0 unless DECELERATING and v_ms < RegenerationLimit; then it
            ramps in over 0 -> 1.39 m/s as 1.5552 v^2 - 0.746496 v^3 (exactly 1.0 at
            1.39 m/s).  So `rub` is the friction brake on the last few seconds only.
    noise   volume = 1 while on route, pitch 1
    flange  volume = 0  <-- HARD-CODED TO SILENT IN MTR 4.0.5.  flange0.ogg is
            shipped anyway so the set is complete if a later MTR revives it.

`output` is signum(speedChange), or MotorOutputAtCoast when the speed is steady.
Three further behaviours fall straight out of the bytecode and shape everything below:

  * **Braking below RegenerationLimit m/s forces the motor output to 0** — every
    motor voice goes abruptly silent.  The brake volume tables therefore have to
    fade to nothing just above that speed or the cut is audible as a click.
  * **`air.wav` is never played.**  MTR only fires `airZero` (when the brake is
    released below RegenerationLimit, i.e. the hiss at the stand) and `airHigh`
    (when power is taken below 0.3 m/s, i.e. the release before departure).
  * **MotorOutputAtCoast must be 0 or 1, nothing in between.**  Every frame MTR does
    `output = signum(output) * (0.3 + 0.7*|output|)`, whose only fixed points are
    0 and ±1; a fractional coast value therefore never matches the raw table value,
    which retriggers the breaker timer forever and chops the motor stack into
    BreakerDelay-spaced blips.  We use 0: a coasting M7 is wheels and auxiliaries,
    and the whine returns the moment power or brake is taken.

Minecraft clamps playback pitch to [0.5, 2.0], so every value in the frequency
tables stays inside that window — which caps ONE voice at a 4:1 frequency span and
is the reason the speed range is split across several voices.
"""

import json
import os

import numpy as np
import soundfile as sf

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src/main/resources")
OUR_NS = os.path.join(RES, "assets/station_announcer")

# The folder name under sounds/, and therefore the second half of every sound id.
SET_NAME = "m7"
NAMESPACE = "station_announcer"
SOUND_DIR = os.path.join(OUR_NS, "sounds", SET_NAME)
SOUNDS_JSON = os.path.join(OUR_NS, "sounds.json")

# ------------------------------------------------------------------ global audio

SR = 22050              # matches the donor pack's own rate; half the bytes of 44.1k
ATTENUATION = 32        # blocks, the value MTR uses for all of its own train sounds

# Loop lengths, seconds.  Short tonal loops keep the jar small; they are built
# bin-exact (see periodic_noise/tone) so they wrap without a click.
LEN_MOTOR = 2.0
LEN_RUN = 4.0
LEN_FLANGE = 3.0
LEN_NOISE = 6.0
LEN_SHOE = 3.0
LEN_COMPRESSOR = 2.5

# ------------------------------------------------------------------ motor voices
#
# WHAT THE M7 ACTUALLY IS (researched 2026-07-28 — the earlier note in this file
# said "Alstom ONIX", which is a common but wrong assumption and produces the wrong
# sound entirely):
#
#   propulsion  Mitsubishi Electric MAP-202-75VD92 IGBT-VVVF, 750 V DC third rail
#   motors      Mitsubishi MB-5088-A, 3-phase asynchronous, 265 hp, 8 per pair
#   ratings     2.0 mph/s to a documented 35 mph corner, 3.0 mph/s full service brake
#   braking     wired for regeneration, but LIRR's substations are NOT receptive:
#               the energy is chopped into on-board resistor grids
#   sources     en.wikipedia.org/wiki/M7_(railcar);
#               turner-engineering.com/projects/lirr-m7-m8-and-m9-propulsion-and-cds/
#               railroad.net/m7-sounds-t1314.html   (LIRR M-7 propulsion class)
#               railroad.net/openbve-bombardier-m7-emu-development-update-thread-t158341.html
#               github.com/VvvfGeeks/YAML-Files    (Mitsubishi vs Alstom ladders)
#
# Why the vendor matters: the ALSTOM idiom is a fixed low carrier followed by a
# ladder of seven discrete synchronous pulse modes — literally gear changes, which
# is what the R142/R160 sound like.  The MITSUBISHI idiom is the opposite: ONE long
# continuously-ramping asynchronous carrier, roughly 200 Hz at a stand up to about
# 1 kHz at base speed, dithered with random PWM (±150-200 Hz, 0.2 ms update) so it
# reads as a breathy hiss-backed whine rather than a pure whistle, and only THEN a
# handover to synchronous/one-pulse operation.  The best first-hand description of
# the M7 (railroad.net, 2004) is exactly that — a smooth "whhheeeEEE" — and the
# same poster attributes the stepped "accelerating through a few gears" sound to
# the R143, not to the M7.  So: no stage ladder here.  One rising whine.
#
# The one real discontinuity is at BASE SPEED, ~35 mph = 56 km/h (documented: the
# M7 holds 2.0 mph/s "continuous, unlimited" to 35 mph, which is where inverter
# voltage saturates).  There the carrier stops rising, the random-PWM hiss stops
# with it, and the sound drops to the motor's own order tones, which from then on
# track road speed directly all the way to 100 mph.
#
# Independent corroboration from the donor pack (parsed, ANALYSIS ONLY — no donor
# audio is used): its BVE2 Train.dat crosses its two motor channels over at
# **54.4 km/h** on power and **60.4 km/h** on brake, i.e. the pack author put the
# hand-over at base speed too, and put the brake hand-over HIGHER — which is what
# the Mitsubishi brake ladders also do.  Its high-speed voice sweeps 137%->441%
# over 54->164 km/h, a frequency ratio of 3.22 against a speed ratio of 3.01, so
# that voice is very nearly proportional to speed: one-pulse operation, confirmed.
# Measured native spectra of the donor motor files, used here only for TIMBRE:
#
#   Motor2.wav  899 / 1793 / 2676 / 3138 / 3607 / 4490 / 5399 Hz  (3rd is loudest)
#   Motor1.wav  1934 Hz dominant, 2871 secondary, low comb 100..908
#   Motor0.wav  92 / 223 / 291 / 450 / 528 / 643 / 724 / 1270 Hz  (223 dominant)
#   Motor3.wav  97 / 164 / 248 / 323 / 409 / 495 / 649 / 740 Hz   (248 dominant)
#
# ---- the carrier trajectory we implement ----------------------------------------
#
# Enthusiast listening reports (uninstrumented, but consistent with each other and
# with the donor files) put the asynchronous carrier sweep at roughly
#
#       450 Hz  ->  970 Hz     in a PLATEAU - RAMP - PLATEAU shape,
#
# and name two "constant tones" at 900 Hz and 1920 Hz.  Those two are simply 2*fc
# at the bottom and the top of that same sweep — and they are also, to within a
# percent, the dominant lines measured in the donor's own Motor2.wav (899 Hz) and
# Motor1.wav (1934 Hz).  Three independent things agreeing is as close to a
# measurement as this vehicle gets, so that is the trajectory:
#
#       v (km/h)      0        6        48       56 and up
#       fc (Hz)     450      450      970      970, then the handover
#
# 970/450 = 2.16:1, which fits inside Minecraft's [0.5, 2.0] pitch clamp in ONE
# loop.  So the whole asynchronous rise is a single voice with a single continuous
# pitch ramp — no crossfade, no staircase, which is exactly what every M7-specific
# description asks for.  fc and 2*fc are both partials of that one loop, so the
# 900 Hz and 1920 Hz lines come along for free and stay locked to it.
#
#   motor0   gear/motor-order hum bed, always under everything
#   motor1   THE asynchronous carrier: fc + 2fc + neighbours + random-PWM hiss.
#            Power 0-60 km/h, brake 9-64 km/h.  This is "the whine".
#   motor2   the standstill growl: at a stand f1 is a few Hz, so the m*fc +/- k*f1
#            comb collapses into a tight beating cluster instead of a whine.
#            Power 0-14 km/h; a little on brake below 16 km/h before cut-out.
#   motor3   the RESISTOR-GRID BRAKE CHOPPER, ~230 Hz, near-constant frequency,
#            BRAKE ONLY.  LIRR's substations are not receptive, so an M7 dumps its
#            braking energy into the grids and hums the whole way down — the single
#            biggest reason a braking M7 is not an accelerating one reversed, and a
#            signature the M8/M9 do not have.  It is also why deceleration reads
#            LOUDER than acceleration, which the brake volumes below reflect.
#   motor4   synchronous / one-pulse motor orders, 50-200 km/h, NO hiss, hard tone
#
# The hiss lives on motor1 and is ZERO on motor4: random PWM belongs to the
# asynchronous region and stops dead at the handover.  An earlier revision of this
# file had it the other way round, which is the opposite of the real machine.

MOTOR_HUM_F0 = 62.0          # traction/gear-mesh bed, always under the carriers
MOTOR_HUM_PARTIALS = [       # (harmonic, amplitude)
    (1, 1.00), (2, 0.62), (3, 0.40), (4, 0.30), (6, 0.22), (9, 0.14), (13, 0.08),
]
MOTOR_HUM_NOISE = 0.22       # underfloor rumble mixed under the hum

CARRIER_SIDEBAND_HZ = 23.0   # sideband offset, gives the metallic beat.  (How much
                             # low motor body bleeds into a carrier — so the
                             # hand-overs blend rather than hard-switch — is the
                             # per-voice `body` column below.)

CARRIER_F0 = 660.0           # motor1 authored here: fc = 450 Hz at pitch 0.682 and
                             # fc = 970 Hz at pitch 1.470, both well inside the clamp
CHOPPER_F0 = 230.0           # reported brake-chopper hum

# (name, f0, [(ratio, amp), ...] or None for the hum, hiss, body, sideband)
MOTOR_VOICES = [
    # ---- 0: the always-there traction / gear-mesh hum
    ("motor0", MOTOR_HUM_F0, None, 0.00, 0.00, 0.10),
    # ---- 1: the asynchronous carrier.  1.0 = fc, 2.0 = the louder 2*fc line the
    #         reports call the "constant tone"; 0.5/1.5/3.0 are its neighbours.
    ("motor1", CARRIER_F0,
     ((0.5, 0.22), (1.0, 0.80), (1.5, 0.26), (2.0, 1.00), (3.0, 0.20), (4.0, 0.08)),
     0.34, 0.16, 0.15),
    # ---- 2: the standstill growl — same carrier, comb collapsed, beating hard.
    ("motor2", 450.0,
     ((1, 1.00), (2, 0.72), (3, 0.40), (4, 0.22), (6, 0.12)),
     0.14, 0.24, 0.40),
    # ---- 3: the resistor-grid chopper.  A square-ish chopper buzz, odd-harmonic
    #         heavy, with grid roar under it.  Brake only.
    ("motor3", CHOPPER_F0,
     ((1, 1.00), (2, 0.34), (3, 0.42), (4, 0.14), (5, 0.18), (6, 0.10), (7, 0.08)),
     0.34, 0.30, 0.20),
    # ---- 4: synchronous one-pulse.  Motor orders only, no switching noise at all.
    #         Ratios are the donor Motor3.wav's measured stack — nearly a pure tone.
    #         f0 630 Hz makes the pitch law exactly v/100, matching the donor's own
    #         high-speed voice (137% at 54.4 km/h -> 441% at 164, i.e. f ~ v).
    ("motor4", 630.0,
     ((0.391, 0.08), (0.661, 0.03), (1.000, 1.00), (1.302, 0.12), (1.649, 0.02),
      (1.996, 0.23), (2.617, 0.03), (2.984, 0.03)),
     0.00, 0.12, 0.10),
]

# ------------------------------------------------------------------ motor tables
#
# Speed axis is km/h (MTR passes speed * 3600, i.e. m/ms -> km/h).
# 160 km/h = 100 mph is the M7's service maximum; tables run past it so the
# spline never flat-lines at the top.
#
# Each entry: name -> ((speed, value), ...).  Blank cells in the CSV are legal and
# mean "this column has no knot here", so the columns are independent.
#
# frequency = playback pitch, so multiply by the voice's authored f0 above to get
# the Hz the player hears.  Everything stays inside Minecraft's [0.5, 2.0] clamp
# (asserted at the end of the run).

MOTOR_NAMES = [v[0] for v in MOTOR_VOICES]

POWER_VOLUME = {
    # the gear/motor-order bed: in from a crawl, never quite goes away
    "motor0": ((0, 0.00), (2, 0.34), (35, 0.46), (110, 0.52), (200, 0.50)),
    # THE whine.  One continuous presence from the moment power is taken until the
    # base-speed handover; it does not step, it just gets brighter as it rises.
    "motor1": ((0, 0.00), (1, 0.62), (12, 0.80), (44, 0.86), (52, 0.72),
               (58, 0.24), (62, 0.00)),
    # the standstill growl, gone by the time the whine is properly singing
    "motor2": ((0, 0.58), (5, 0.52), (10, 0.24), (15, 0.00)),
    # grid chopper: brake only
    "motor3": ((0, 0.00), (200, 0.00)),
    # synchronous one-pulse, in at the 56 km/h corner and audible all the way up
    "motor4": ((50, 0.00), (57, 0.52), (66, 0.70), (100, 0.64), (140, 0.50),
               (200, 0.42)),
}
POWER_FREQUENCY = {
    "motor0": ((0, 0.62), (45, 1.00), (120, 1.55), (200, 1.95)),
    # fc: 450 Hz plateau to 6 km/h, ramp to 970 Hz at 48, plateau to the handover.
    # 450/660 = 0.682, 970/660 = 1.470.
    "motor1": ((0, 0.682), (6, 0.682), (48, 1.470), (62, 1.470)),
    # the growl rides the same fc, so it climbs with it until it fades out
    "motor2": ((0, 1.00), (6, 1.00), (15, 1.22)),
    "motor3": ((0, 1.00), (200, 1.00)),
    # one-pulse: the tone tracks road speed, so pitch is simply v/100
    "motor4": ((50, 0.50), (200, 2.00)),
}

# BRAKING is the same sequence in reverse — synchronous, then asynchronous with the
# carrier descending — plus two things that make it its own sound rather than
# acceleration played backwards:
#
#   * the resistor-grid chopper (motor3) hums underneath the whole way down, at a
#     near-constant ~230 Hz, because LIRR cannot take the regenerated power back;
#   * it is LOUDER overall than power, which is what listeners report.
#
# MTR force-mutes every motor voice below RegenerationLimit m/s, so all of these
# must already be at zero by ~9 km/h or the cut would click.  That is also where
# the friction brake (`rub`) takes over, which is the hand-over the real thing
# makes audible.
BRAKE_VOLUME = {
    "motor0": ((8, 0.00), (16, 0.34), (45, 0.44), (120, 0.48), (200, 0.48)),
    "motor1": ((8, 0.00), (14, 0.72), (44, 0.92), (54, 0.78), (60, 0.30),
               (66, 0.00)),
    # a short growl right before the inverter drops out
    "motor2": ((8, 0.00), (11, 0.34), (16, 0.26), (22, 0.00)),
    # the grids: on the whole way down, and the loudest single brake element
    "motor3": ((8, 0.00), (12, 0.62), (40, 0.70), (120, 0.66), (200, 0.60)),
    # the brake hand-over sits HIGHER than the power one (donor: 60.4 vs 54.4 km/h)
    "motor4": ((56, 0.00), (64, 0.58), (74, 0.74), (110, 0.68), (200, 0.50)),
}
BRAKE_FREQUENCY = {
    "motor0": ((8, 0.58), (45, 0.94), (120, 1.46), (200, 1.84)),
    # same carrier trajectory, a touch lower: the Mitsubishi brake ladders sit on a
    # lower carrier floor than the power ones (~170-195 Hz vs 197 in the published
    # recreations), so the whole curve is scaled by 0.96.
    "motor1": ((8, 0.655), (48, 1.411), (66, 1.411)),
    "motor2": ((8, 0.96), (22, 1.17)),
    # 230 Hz chopper -> pitch 1.0, dead constant, exactly as a chopper behaves
    "motor3": ((8, 1.00), (200, 1.00)),
    "motor4": ((56, 0.56), (200, 2.00)),
}

# ------------------------------------------------------------------ sound.cfg knobs

DOOR_CLOSE_SOUND_LENGTH = 1.0   # doorcls fires when the door-open fraction first
                                # drops BELOW this value.  MTR's door value tops out
                                # at 1.0, so 1.0 means "the instant the leaves start
                                # to close" — the earliest the chime can possibly
                                # lead the movement.  Nothing smaller would help; a
                                # smaller value would only delay the chime.
DOOR_MOVE_SECONDS = 3.2         # MTR's Vehicle.DOOR_MOVE_TIME (3200 ms), verified by
                                # javap.  Both door files are laid out against it.
BREAKER_DELAY = 0.25            # seconds of motor silence when power/brake flips
MOTOR_OUTPUT_AT_COAST = 0.0     # MUST be 0 or 1 — see the header.  0 = coasting is
                                # wheels and auxiliaries, whine only under power/brake
REGENERATION_LIMIT = 2.2        # m/s = 7.9 km/h = 4.9 mph.  GUESS, flagged as such:
                                # no source gives the M7's real inverter cut-out
                                # speed, and ~5 mph is the usual figure for an AC
                                # drive losing retarding torque.  Braking below it,
                                # MTR force-mutes every motor voice, fades the
                                # friction brake (`rub`) in, and fires `airzero` on
                                # release.  All four brake volume tables are already
                                # at zero by 8-9 km/h so the mute is inaudible and
                                # the hand-over to friction is what you hear.
MOTOR_VOLUME_MULTIPLY = 1.0
MOTOR_NOISE_DATA_TYPE = 5       # 5 = the four-CSV spline tables; 4 would want a
                                # BVE4 train.dat instead

# ------------------------------------------------------------------ running gear

RUN_REFERENCE_MS = 25.0     # run0 plays at pitch 1.0 at this speed (MTR: v*0.04)
RUN_RESONANCES = [          # (Hz, Q, gain) — muffled bogie/car-body rumble
    (62, 1.6, 1.00), (128, 2.2, 0.72), (255, 2.4, 0.46),
    (430, 3.0, 0.30), (760, 3.5, 0.16),
]
RUN_HISS_GAIN = 0.10        # rail/air hiss on top of the rumble
RUN_JOINT_COUNT = 3         # rail-joint clacks folded into the 4 s loop
RUN_JOINT_LEVEL = 0.20      # relative to the rumble — deliberately subtle
RUN_JOINT_F0 = 74.0         # thump pitch
RUN_JOINT_DECAY = 0.055     # seconds

FLANGE_TONES = [(1410, 34, 1.00), (2130, 30, 0.55), (3260, 26, 0.22)]  # Hz, Q, gain
FLANGE_WOBBLE_HZ = 3.5      # slow scrape wobble
FLANGE_WOBBLE_DEPTH = 0.45

NOISE_HUM_HZ = 60.0         # HVAC / auxiliary converter hum (US 60 Hz)
NOISE_HUM_PARTIALS = [(1, 0.55), (2, 1.00), (4, 0.30), (6, 0.12)]
NOISE_BLOWER_LOW = 180.0    # saloon blower band
NOISE_BLOWER_HIGH = 3200.0

SHOE_BAND = (420.0, 2600.0)  # brake-shoe rub, band-limited scrape
SHOE_ROUGHNESS_HZ = 27.0     # amplitude roughness of the rub
SHOE_ROUGHNESS_DEPTH = 0.5
# The last few seconds of an M7 stop are tread brake on wheel: a scrape with a
# squeal sitting on it, and the truck creaking as the car settles.  MTR rises this
# loop's pitch as the train slows (1 + 1/(v+1)), so the squeal climbs into the stop.
SHOE_SQUEAL = ((980.0, 26, 0.55), (1520.0, 30, 0.30))   # Hz, Q, gain.  Authored
                             # low because MTR raises this loop's pitch to 2.0 as
                             # the train stops, which lands them near 2 and 3 kHz.
SHOE_CREAK_HZ = 0.55         # slow groan of the truck settling
SHOE_CREAK_DEPTH = 0.30

# ------------------------------------------------------------------ one-shots

JOINT_LEN = 0.40            # donor Point.wav is 2.25 s of several joints; MTR fires
                            # one per rail node, so ours is a single two-axle clack
JOINT_AXLE_GAP = 0.075      # seconds between the leading and trailing axle
JOINT_RING_HZ = 1650.0      # metallic ring on the clack

AIR_LEN = 0.75              # [Brake] BC Release       — short chuff
AIRHIGH_LEN = 1.15          # [Brake] BC Release High  — release before departure
AIRZERO_LEN = 1.65          # [Brake] BC Release Full  — the big PSHHH at a stop
                            # (donor air.wav 1.42 s, airzero.wav 1.35 s)
AIR_PEAK_HZ = 2600.0        # hiss centre; sweeps down as the pressure drops
AIR_END_HZ = 900.0

EMERGENCY_LEN = 2.0         # a full, hard air dump
BRAKE_HANDLE_LEN = 0.35     # valve click + puff

COMPRESSOR_CHUG_HZ = 23.0   # reciprocating compressor stroke rate
COMPRESSOR_MOTOR_HZ = 292.0 # its drive motor whine
COMPRESSOR_START_LEN = 0.9
COMPRESSOR_END_LEN = 1.1

# ------------------------------------------------------------------ doors
#
# Re-measured from the user's reference recordings on 2026-07-28 (ANALYSIS ONLY):
#   .../6 Car M7/Sound/DoorOpn.wav   3.887 s, 22050 Hz
#   .../6 Car M7/Sound/DoorCls.wav   5.218 s, 22050 Hz
# Method: 20 ms RMS envelopes overall / 60-300 Hz / 600-900 Hz, 4096-point STFT,
# and per-band excess over the recordings' own ambience tail.
#
# DoorOpn — the M7's door engine is ELECTRIC, not pneumatic.  There is no hiss;
# the whole event is a lock clack followed by a tonal motor whine.
#
#   0.000-0.295  ambience only (recording head, -14 dB)
#   0.300-0.450  UNLATCH CLACK, the file's 0 dB peak.  Content clusters at
#                764/794/837/902/935/963/1150 Hz over a 178 Hz body; decays with
#                tau ~= 50 ms
#   0.450-1.150  quiet dwell (-14 dB) — the lock releases well before the leaf moves
#   1.200-2.650  LEAF RUN: a tonal line starting at 673 Hz, rising to 732 Hz by
#                t=2.0 and settling 710-732 Hz, second harmonic at 1438 Hz.  Band
#                excess over ambience +19 dB at the start tapering to +10 dB.
#                Travel bumps at 1.60 s and a strong one at 1.80 s
#   2.650-3.887  decay back to ambience (tail -25.7 dB)
#
# DoorCls — a warning buzzer over a much quieter version of the same leaf run:
#
#   buzzer      3014.0 Hz, essentially a pure tone (companion at 2960 Hz, -20 dB;
#               no harmonic at 6 kHz).  Onsets 0.170 0.993 1.821 2.644 3.472 4.290,
#               i.e. SIX beeps, period 0.8241 s (1.213 Hz), 0.485 s on, duty 0.59.
#               Last beep ends 4.799 s
#   leaf run    560-960 Hz excess climbs from 0 to +6..+10 dB over 1.55-3.35 s,
#               same ~710-735 Hz voice as the opener but far quieter
#   latch       a +22 dB event at 813 Hz around 3.70 s
#
# What we ship, and why it is not a literal copy of those clocks: MTR starts the
# leaves moving on the same frame it plays the sound and takes DOOR_MOVE_SECONDS
# to finish, so the donor's 0.90 s pre-movement dwell and its 1.2 s of ambience
# tail would simply desynchronise.  The clack, the dwell, the whine's pitch
# trajectory, the bumps, the buzzer's frequency, on-time, period and count are all
# kept exactly; only the dwell is compressed and the ambience padding dropped.

DOOR_OPEN_LEN = 3.20        # == DOOR_MOVE_SECONDS
DOOR_OPEN_CLACK_AT = 0.02   # donor 0.300, minus its ambience head
DOOR_OPEN_CLACK_LEN = 0.26
DOOR_OPEN_RUN_AT = 0.62     # donor dwell 0.90 s, compressed to 0.40 s
DOOR_OPEN_RUN_LEN = 2.28    # donor 1.45 s, stretched to cover the whole travel
DOOR_OPEN_BUMPS = (0.28, 0.41)   # donor bumps at 1.60/1.80 s -> fraction of the run

DOOR_CLOSE_HEAD = 0.05      # first buzzer onset
DOOR_CLOSE_TAIL = 0.42      # donor tail after the last beep
DOOR_CLOSE_RUN_AT = 0.30
DOOR_CLOSE_RUN_LEN = 2.55   # covers MTR's 3.2 s close, ending in the latch
DOOR_CLOSE_RUN_LEVEL = 0.34 # measured +6..+10 dB vs the opener's +19 dB

# Door engine: a tonal whine, harmonics only, no hiss.
DOOR_MOTOR_F0_START = 673.0
DOOR_MOTOR_F0_END = 726.0   # measured 710-732 over the second half
DOOR_MOTOR_PARTIALS = ((1, 1.00), (2, 0.42), (3, 0.14), (4, 0.06))
DOOR_MOTOR_RIPPLE_HZ = 31.0   # commutation roughness riding on the whine
DOOR_MOTOR_RIPPLE = 0.28
DOOR_MOTOR_RUSTLE = 0.22      # the leaf running in its track (broadband, quiet)
DOOR_MOTOR_BAND = (900.0, 6500.0)

DOOR_CLACK_TONES = (764.0, 794.0, 837.0, 902.0, 935.0, 963.0, 1150.0)  # measured
DOOR_CLACK_BODY_HZ = 178.0    # measured low component of the clack
DOOR_CLACK_TAU = 0.050        # measured decay
DOOR_THUNK_HZ = 118.0

# The buzzer, exactly as measured.
CHIME_HZ = 3014.0
CHIME_COMPANION_HZ = 2960.0
CHIME_COMPANION = 0.10      # measured -20 dB
CHIME_NOTE_LEN = 0.485
CHIME_PERIOD = 0.8241
CHIME_REPEATS = 6
CHIME_LEVEL = 0.62

# ------------------------------------------------------------------ mix levels
# Peak level each finished file is normalised to.  Loops that MTR layers together
# (motor stack) are kept below the one-shots so a full-power departure does not
# clip against the chime or the air release.

PEAK = {
    # motor1 is THE whine and has to sit on top of the stack; motor3 (the brake
    # chopper) is a bed, so it is deliberately the quietest of the carriers.
    "motor0": 0.55, "motor1": 0.72, "motor2": 0.56, "motor3": 0.46,
    "motor4": 0.62,
    "run0": 0.70, "flange0": 0.55, "loop": 0.26, "rub": 0.50,
    "point": 0.85, "air": 0.62, "airhigh": 0.70, "airzero": 0.80,
    "emrbrake": 0.90, "brake_apply": 0.45, "brake_release": 0.45,
    "cp_start": 0.40, "cp_loop": 0.20, "cp_end": 0.40,
    "dooropn": 0.62, "doorcls": 0.78,
}
# cp_loop is deliberately quiet: MTR's main-reservoir model adds
# int(frameDuration/20 * 5) per frame, which truncates to 0 at any sane frame rate,
# so `isCompressorActive` never clears and the compressor loop plays CONTINUOUSLY.
# It has to work as a soft underfloor presence, not a feature.


# ===================================================================== dsp helpers
#
# Every looping file is built so that it is EXACTLY periodic over its own length:
#
#   * tonal content uses only frequencies that land on an FFT bin of the loop
#     (`bin_hz`), so the waveform meets itself at the wrap point;
#   * noise beds are generated as N samples, shaped in the frequency domain and
#     inverse-transformed (`periodic_noise`) — circular convolution, so the result
#     is periodic by construction;
#   * transients (rail-joint clacks) are added with wrap-around indexing.
#
# That is stronger than crossfading the ends: a crossfade can partially cancel
# tonal content at the seam, whereas this leaves nothing to fade.  Verified by
# tiling each loop twice and confirming the largest sample-to-sample step at the
# join is below the file's own 99.9th-percentile interior step.


def n_samples(seconds):
    return int(round(seconds * SR))


def bin_hz(freq, n):
    """Snap a frequency to the nearest exact FFT bin of an n-sample loop."""
    k = max(1, int(round(freq * n / SR)))
    return k * SR / n


def t_axis(n):
    return np.arange(n) / SR


def tone(n, freq, amp=1.0, phase=0.0, exact=True):
    f = bin_hz(freq, n) if exact else freq
    return amp * np.sin(2 * np.pi * f * t_axis(n) + phase)


def resonator(fr, f0, q, gain):
    """Magnitude response of a simple resonant peak at f0."""
    x = np.maximum(fr, 1e-6)
    return gain / np.sqrt(1.0 + (q * (x / f0 - f0 / x)) ** 2)


def lowpass(fr, fc, order=2):
    return 1.0 / np.sqrt(1.0 + (np.maximum(fr, 1e-6) / fc) ** (2 * order))


def highpass(fr, fc, order=2):
    x = np.maximum(fr, 1e-6) / fc
    return x ** order / np.sqrt(1.0 + x ** (2 * order))


def bandpass(fr, lo, hi, order=2):
    return highpass(fr, lo, order) * lowpass(fr, hi, order)


def periodic_noise(n, shape, seed):
    """Noise of exactly n samples, spectrally shaped — loops with no seam."""
    rng = np.random.default_rng(seed)
    spec = np.fft.rfft(rng.standard_normal(n))
    fr = np.fft.rfftfreq(n, 1.0 / SR)
    spec *= shape(fr)
    out = np.fft.irfft(spec, n)
    return out / (np.max(np.abs(out)) + 1e-12)


def plain_noise(n, shape, seed):
    """Same shaping for one-shots, where periodicity does not matter."""
    return periodic_noise(n, shape, seed)


def add_wrapped(buf, piece, start):
    """Add `piece` into `buf` at `start`, wrapping past the end — keeps loops periodic."""
    n = len(buf)
    idx = (np.arange(len(piece)) + start) % n
    np.add.at(buf, idx, piece)


def env_exp(n, tau):
    return np.exp(-t_axis(n) / tau)


def fade_edges(x, seconds=0.006):
    f = min(n_samples(seconds), len(x) // 2)
    if f < 2:
        return x
    w = np.linspace(0.0, 1.0, f)
    x[:f] *= w
    x[-f:] *= w[::-1]
    return x


def normalise(x, peak):
    m = np.max(np.abs(x))
    return x * (peak / m) if m > 1e-9 else x


# ===================================================================== voices


def make_motor_hum(name):
    """motor0 — the always-there traction hum, the bed the carriers sit on."""
    n = n_samples(LEN_MOTOR)
    out = np.zeros(n)
    for h, amp in MOTOR_HUM_PARTIALS:
        out += tone(n, MOTOR_HUM_F0 * h, amp, phase=0.6 * h)
    # gear-mesh flutter: a slow, bin-exact tremolo so the hum is not dead flat
    out *= 1.0 + 0.10 * tone(n, 3.0, 1.0)
    out += MOTOR_HUM_NOISE * periodic_noise(
        n, lambda fr: bandpass(fr, 45.0, 900.0, 2) * (1.0 + 1.4 * resonator(fr, 190.0, 2.0, 1.0)), 101
    )
    return normalise(out, PEAK[name])


def make_carrier(name, f0, partials, hiss, body, sideband, seed):
    """motor1..4 — one steady inverter voice; MTR sweeps its pitch.

    `partials` are (ratio, amplitude) pairs: the magnetic-force lines this voice
    carries, relative to its authored f0.  `sideband` sets how strongly each line
    is flanked — a real m*fc +/- k*f1 comb.  At a stand f1 is only a few Hz so the
    comb collapses onto the carrier and beats (motor2 uses a big value for exactly
    that), while under way it spreads out into the familiar metallic edge.

    `hiss` is the random-PWM dither: Mitsubishi drives randomise the switching
    instant by +/-150-200 Hz through the whole asynchronous region, which is what
    turns a pure whistle into a breathy whine.  It is authored as a noise skirt
    around the carrier lines, so pitching the loop carries it with them.  The
    synchronous voice gets none at all.
    """
    n = n_samples(LEN_MOTOR)
    out = np.zeros(n)
    nyq = SR * 0.45
    for i, (ratio, amp) in enumerate(partials):
        f = f0 * ratio
        if f >= nyq:
            continue
        out += tone(n, f, amp, phase=0.31 * (i + 1))
        out += tone(n, f + CARRIER_SIDEBAND_HZ, amp * sideband, phase=1.1 * i)
        out += tone(n, max(20.0, f - CARRIER_SIDEBAND_HZ), amp * sideband, phase=2.2 * i)
    # a little of the low motor body under every voice, so hand-overs blend
    if body > 0:
        out += body * tone(n, MOTOR_HUM_F0, 1.0)
        out += body * 0.55 * tone(n, MOTOR_HUM_F0 * 2, 1.0)
    # random-PWM dither: a skirt hugging the two loudest lines, plus a wider bed
    if hiss > 0:
        loud = sorted(partials, key=lambda p: -p[1])[:2]
        def shape(fr, loud=loud, f0=f0):
            s = np.zeros_like(fr)
            for ratio, amp in loud:
                f = f0 * ratio
                s += amp * bandpass(fr, f * 0.72, min(f * 1.45, nyq), 3)
            s += 0.30 * bandpass(fr, f0 * 0.5, min(f0 * 3.5, nyq), 2)
            return s
        out += hiss * 2.4 * periodic_noise(n, shape, seed)
    return normalise(out, PEAK[name])


def make_run():
    """run0 — muffled bogie rumble with the odd rail-joint clack folded in."""
    n = n_samples(LEN_RUN)

    def shape(fr):
        s = np.zeros_like(fr)
        for f0, q, g in RUN_RESONANCES:
            s += resonator(fr, f0, q, g)
        s *= lowpass(fr, 1500.0, 2)
        s += RUN_HISS_GAIN * bandpass(fr, 1200.0, 6500.0, 2)
        return s

    out = periodic_noise(n, shape, 202)
    # slow level breathing, bin-exact so the loop still wraps
    out *= 1.0 + 0.16 * tone(n, 0.75, 1.0) + 0.09 * tone(n, 1.75, 1.0, phase=1.4)

    # rail joints: a two-axle clack, wrapped so periodicity survives
    clack_n = n_samples(0.22)
    clack = (
        tone(clack_n, RUN_JOINT_F0, 1.0, exact=False)
        + 0.5 * tone(clack_n, RUN_JOINT_F0 * 2.7, 1.0, exact=False)
    ) * env_exp(clack_n, RUN_JOINT_DECAY)
    clack += 0.7 * plain_noise(clack_n, lambda fr: bandpass(fr, 700.0, 5000.0, 2), 203) * env_exp(
        clack_n, 0.018
    )
    clack = normalise(clack, RUN_JOINT_LEVEL)
    rng = np.random.default_rng(204)
    for i in range(RUN_JOINT_COUNT):
        base = int(n * (i + 0.5) / RUN_JOINT_COUNT)
        jitter = int(rng.integers(-n // 40, n // 40))
        add_wrapped(out, clack, base + jitter)
        add_wrapped(out, clack * 0.8, base + jitter + n_samples(JOINT_AXLE_GAP))

    return normalise(out, PEAK["run0"])


def make_flange():
    """flange0 — curve squeal.  MTR 4.0.5 plays this at volume 0; shipped for completeness."""
    n = n_samples(LEN_FLANGE)
    wob = 1.0 + FLANGE_WOBBLE_DEPTH * tone(n, FLANGE_WOBBLE_HZ, 1.0)
    out = np.zeros(n)
    for i, (f0, q, g) in enumerate(FLANGE_TONES):
        band = periodic_noise(n, lambda fr, f0=f0, q=q: resonator(fr, f0, q, 1.0), 300 + i)
        out += g * band
    out *= wob
    out += 0.25 * periodic_noise(n, lambda fr: bandpass(fr, 900.0, 7000.0, 2), 310)
    return normalise(out, PEAK["flange0"])


def make_noise_loop():
    """loop — the constant background: HVAC hum plus saloon blower."""
    n = n_samples(LEN_NOISE)
    out = np.zeros(n)
    for h, amp in NOISE_HUM_PARTIALS:
        out += tone(n, NOISE_HUM_HZ * h, amp, phase=0.9 * h)
    out *= 0.5
    out += 1.3 * periodic_noise(
        n, lambda fr: bandpass(fr, NOISE_BLOWER_LOW, NOISE_BLOWER_HIGH, 2), 401
    )
    out *= 1.0 + 0.08 * tone(n, 0.35, 1.0)
    return normalise(out, PEAK["loop"])


def make_shoe():
    """rub — tread brake on wheel, the last few seconds of every M7 stop."""
    n = n_samples(LEN_SHOE)
    out = periodic_noise(
        n,
        lambda fr: bandpass(fr, SHOE_BAND[0], SHOE_BAND[1], 2)
        * (1.0 + 1.1 * resonator(fr, 840.0, 3.0, 1.0)),
        501,
    )
    # squeal: narrow resonant bands sitting on the scrape
    for i, (f0, q, g) in enumerate(SHOE_SQUEAL):
        out += g * periodic_noise(n, lambda fr, f0=f0, q=q: resonator(fr, f0, q, 1.0),
                                  510 + i)
    rough = 1.0 - SHOE_ROUGHNESS_DEPTH * (0.5 + 0.5 * tone(n, SHOE_ROUGHNESS_HZ, 1.0))
    out *= rough
    # truck creak as the car settles onto the brakes
    out *= 1.0 + SHOE_CREAK_DEPTH * tone(n, SHOE_CREAK_HZ, 1.0)
    out *= 1.0 + 0.2 * tone(n, 1.3, 1.0, phase=0.4)
    return normalise(out, PEAK["rub"])


def make_joint():
    """point — one rail joint under one bogie: two axles, a beat apart."""
    n = n_samples(JOINT_LEN)
    out = np.zeros(n)
    axle_n = n_samples(0.20)
    for k, level in ((0, 1.0), (1, 0.82)):
        hit = (
            tone(axle_n, 88.0, 1.0, exact=False)
            + 0.55 * tone(axle_n, 176.0, 1.0, exact=False)
            + 0.30 * tone(axle_n, JOINT_RING_HZ, 1.0, exact=False)
        ) * env_exp(axle_n, 0.045)
        hit += 0.9 * plain_noise(axle_n, lambda fr: bandpass(fr, 900.0, 7000.0, 2), 600 + k) * env_exp(
            axle_n, 0.012
        )
        start = n_samples(0.02 + k * JOINT_AXLE_GAP)
        out[start:start + axle_n] += level * hit
    return normalise(fade_edges(out), PEAK["point"])


def air_release(length, name, seed, thump=True):
    """A brake air release: sharp attack, hiss whose centre sinks as pressure drops."""
    n = n_samples(length)
    bright = plain_noise(n, lambda fr: bandpass(fr, 700.0, 8000.0, 2), seed)
    dark = plain_noise(n, lambda fr: bandpass(fr, 200.0, AIR_END_HZ * 2.0, 2), seed + 1)
    # crossfade bright -> dark so the hiss "falls" the way real air does
    mix = np.linspace(0.0, 1.0, n) ** 1.4
    out = bright * (1.0 - mix) + dark * mix
    peaked = plain_noise(n, lambda fr: resonator(fr, AIR_PEAK_HZ, 2.5, 1.0), seed + 2)
    out += 0.35 * peaked * (1.0 - mix)
    env = env_exp(n, length * 0.42)
    env[: n_samples(0.012)] *= np.linspace(0.0, 1.0, n_samples(0.012))
    out *= env
    if thump:
        # the valve itself knocking open
        k = n_samples(0.08)
        out[:k] += 0.5 * tone(k, 140.0, 1.0, exact=False) * env_exp(k, 0.02)
    return normalise(fade_edges(out), PEAK[name])


def make_emergency():
    """emrbrake — everything dumps at once, then a long decaying roar."""
    n = n_samples(EMERGENCY_LEN)
    out = plain_noise(n, lambda fr: bandpass(fr, 120.0, 9000.0, 2), 700)
    out *= env_exp(n, EMERGENCY_LEN * 0.5)
    out += 0.6 * plain_noise(n, lambda fr: resonator(fr, 320.0, 2.0, 1.0), 701) * env_exp(n, 0.35)
    k = n_samples(0.12)
    out[:k] += 0.8 * tone(k, 96.0, 1.0, exact=False) * env_exp(k, 0.035)
    return normalise(fade_edges(out), PEAK["emrbrake"])


def brake_handle(name, seed, rising):
    """A brake-handle detent: a click plus a short breath of air."""
    n = n_samples(BRAKE_HANDLE_LEN)
    out = plain_noise(n, lambda fr: bandpass(fr, 400.0, 6000.0, 2), seed)
    env = env_exp(n, 0.10) if not rising else np.linspace(0.4, 1.0, n) * env_exp(n, 0.16)
    out *= env
    k = n_samples(0.03)
    out[:k] += 0.7 * tone(k, 900.0, 1.0, exact=False) * env_exp(k, 0.008)
    return normalise(fade_edges(out), PEAK[name])


def compressor_body(n, level_env):
    """Shared compressor voicing: stroke chug + drive-motor whine + air."""
    out = np.zeros(n)
    chug_period = n_samples(1.0 / COMPRESSOR_CHUG_HZ)
    stroke_n = min(chug_period, n_samples(0.030))
    stroke = (
        tone(stroke_n, 105.0, 1.0, exact=False) + 0.4 * tone(stroke_n, 260.0, 1.0, exact=False)
    ) * env_exp(stroke_n, 0.010)
    for start in range(0, n, chug_period):
        add_wrapped(out, stroke, start)
    out += 0.35 * np.sin(2 * np.pi * COMPRESSOR_MOTOR_HZ * t_axis(n))
    out += 0.18 * np.sin(2 * np.pi * COMPRESSOR_MOTOR_HZ * 2 * t_axis(n))
    out += 0.30 * plain_noise(n, lambda fr: bandpass(fr, 300.0, 4500.0, 2), 800)
    return out * level_env


def make_compressor_loop():
    n = n_samples(LEN_COMPRESSOR)
    # bin-exact chug rate so the stroke pattern wraps cleanly
    strokes = max(1, int(round(COMPRESSOR_CHUG_HZ * LEN_COMPRESSOR)))
    period = n // strokes
    out = np.zeros(n)
    stroke_n = min(period, n_samples(0.030))
    stroke = (
        tone(stroke_n, 105.0, 1.0, exact=False) + 0.4 * tone(stroke_n, 260.0, 1.0, exact=False)
    ) * env_exp(stroke_n, 0.010)
    for i in range(strokes):
        add_wrapped(out, stroke, i * period)
    # The drive-motor tone is kept WELL down.  MTR never clears isCompressorActive
    # (see the PEAK note), so this loop is playing all the time — a 292 Hz sine at
    # any real level becomes the loudest tone in the whole set at a stand, which it
    # measurably was before this was cut back.  Chug and air carry it instead.
    out += 0.16 * tone(n, COMPRESSOR_MOTOR_HZ, 1.0)
    out += 0.07 * tone(n, COMPRESSOR_MOTOR_HZ * 2, 1.0, phase=0.8)
    out += 0.42 * periodic_noise(n, lambda fr: bandpass(fr, 300.0, 4500.0, 2), 801)
    return normalise(out, PEAK["cp_loop"])


def make_compressor_start():
    n = n_samples(COMPRESSOR_START_LEN)
    ramp = np.linspace(0.0, 1.0, n) ** 1.5
    out = compressor_body(n, ramp)
    # motor spin-up: the whine rises into the loop's steady pitch
    sweep = np.cumsum(np.linspace(COMPRESSOR_MOTOR_HZ * 0.35, COMPRESSOR_MOTOR_HZ, n)) / SR
    out += 0.3 * np.sin(2 * np.pi * sweep) * ramp
    return normalise(fade_edges(out), PEAK["cp_start"])


def make_compressor_end():
    n = n_samples(COMPRESSOR_END_LEN)
    decay = env_exp(n, COMPRESSOR_END_LEN * 0.30)
    out = compressor_body(n, decay)
    sweep = np.cumsum(np.linspace(COMPRESSOR_MOTOR_HZ, COMPRESSOR_MOTOR_HZ * 0.25, n)) / SR
    out += 0.3 * np.sin(2 * np.pi * sweep) * decay
    # unloader valve blows off at the end
    k = n_samples(0.35)
    puff = plain_noise(k, lambda fr: bandpass(fr, 800.0, 7000.0, 2), 802) * env_exp(k, 0.09)
    out[-k:] += 0.8 * puff
    return normalise(fade_edges(out), PEAK["cp_end"])


def door_chime(n, start):
    """The door-closing buzzer: one steady tone, on/off, exactly as measured.

    3014 Hz with a 2960 Hz companion 20 dB down, 0.485 s on every 0.8241 s, six
    times.  It is a buzzer, not a chime — it does not decay within a beep, it
    just switches, with short shaped edges so it does not click.
    """
    out = np.zeros(n)
    note_n = n_samples(CHIME_NOTE_LEN)
    period = n_samples(CHIME_PERIOD)
    edge = n_samples(0.008)
    gate = np.ones(note_n)
    gate[:edge] = np.linspace(0.0, 1.0, edge)
    gate[-edge:] = np.linspace(1.0, 0.0, edge)
    # a shallow decay across the beep, as a real piezo driver sags a little
    gate *= 1.0 - 0.18 * np.linspace(0.0, 1.0, note_n)
    note = (
        tone(note_n, CHIME_HZ, 1.00, exact=False)
        + tone(note_n, CHIME_COMPANION_HZ, CHIME_COMPANION, phase=0.7, exact=False)
    ) * gate
    for rep in range(CHIME_REPEATS):
        at = start + rep * period
        k = min(note_n, n - at)
        if k > 0:
            out[at:at + k] += note[:k]
    m = np.max(np.abs(out))
    return out * (CHIME_LEVEL / m) if m > 1e-9 else out


def door_clack(length, seed):
    """The lock releasing: a hard mid-band clack over a low body."""
    k = n_samples(length)
    out = np.zeros(k)
    for i, f in enumerate(DOOR_CLACK_TONES):
        out += (0.9 ** i) * tone(k, f, 1.0, phase=0.7 * i, exact=False)
    out /= len(DOOR_CLACK_TONES)
    out *= env_exp(k, DOOR_CLACK_TAU)
    out += 0.55 * tone(k, DOOR_CLACK_BODY_HZ, 1.0, exact=False) * env_exp(k, DOOR_CLACK_TAU * 1.8)
    out += 0.8 * plain_noise(k, lambda fr: bandpass(fr, 700.0, 6000.0, 2), seed) * env_exp(k, 0.014)
    out[: n_samples(0.002)] *= np.linspace(0.0, 1.0, n_samples(0.002))
    return out


def door_motor(length, seed, f_start=None, f_end=None):
    """The electric door engine: a tonal whine that climbs a little as it runs.

    No hiss — the reference shows the energy concentrated in a ~673-732 Hz line
    and its second harmonic, over a quiet broadband rustle of the leaf in its
    track.  The frequency sweep is baked in here (it is a one-shot, so unlike the
    motor loops it may contain its own glide).
    """
    k = n_samples(length)
    f0 = f_start if f_start is not None else DOOR_MOTOR_F0_START
    f1 = f_end if f_end is not None else DOOR_MOTOR_F0_END
    # measured: rises over the first ~60% of the run, then holds
    ramp = np.clip(np.linspace(0.0, 1.0 / 0.6, k), 0.0, 1.0)
    track = f0 + (f1 - f0) * ramp
    out = np.zeros(k)
    for h, amp in DOOR_MOTOR_PARTIALS:
        phase = 2 * np.pi * np.cumsum(track * h) / SR
        out += amp * np.sin(phase + 0.5 * h)
    out *= 1.0 + DOOR_MOTOR_RIPPLE * np.sin(2 * np.pi * DOOR_MOTOR_RIPPLE_HZ * t_axis(k))
    out += DOOR_MOTOR_RUSTLE * 2.0 * plain_noise(
        k, lambda fr: bandpass(fr, DOOR_MOTOR_BAND[0], DOOR_MOTOR_BAND[1], 2), seed
    )
    # start-up surge, then a long taper (measured +19 dB -> +10 dB across the run)
    env = np.ones(k)
    a = n_samples(0.05)
    env[:a] = np.linspace(0.0, 1.0, a)
    env *= np.linspace(1.0, 0.42, k)
    env[:n_samples(0.18)] *= np.linspace(1.35, 1.0, n_samples(0.18))
    return out * env


def door_thunk(seed, level=1.0):
    """End of travel: the leaf reaching its stop."""
    tk = n_samples(0.16)
    out = (
        tone(tk, DOOR_THUNK_HZ, 1.0, exact=False)
        + 0.4 * tone(tk, DOOR_THUNK_HZ * 3.1, 1.0, exact=False)
    ) * env_exp(tk, 0.035)
    out += 0.5 * plain_noise(tk, lambda fr: bandpass(fr, 600.0, 5000.0, 2), seed) * env_exp(tk, 0.012)
    return out * level


def make_door_open():
    """Clack, dwell, then the engine runs the leaf the length of the travel."""
    n = n_samples(DOOR_OPEN_LEN)
    out = np.zeros(n)

    clack = door_clack(DOOR_OPEN_CLACK_LEN, 903)
    at = n_samples(DOOR_OPEN_CLACK_AT)
    out[at:at + len(clack)] += clack

    run = door_motor(DOOR_OPEN_RUN_LEN, 900)
    # the two travel bumps the reference has part-way along
    for i, frac in enumerate(DOOR_OPEN_BUMPS):
        bump = door_thunk(920 + i, 0.5 if i == 0 else 0.8)
        b = int(frac * len(run))
        run[b:b + len(bump)] += bump[: len(run) - b]
    start = n_samples(DOOR_OPEN_RUN_AT)
    k = min(len(run), n - start)
    out[start:start + k] += 0.62 * run[:k]

    end = door_thunk(930, 0.9)
    e = start + k
    if e + len(end) <= n:
        out[e:e + len(end)] += end
    return normalise(fade_edges(out), PEAK["dooropn"])


def make_door_close():
    """Buzzer over a quiet leaf run — the reference's own balance."""
    total = DOOR_CLOSE_HEAD + (CHIME_REPEATS - 1) * CHIME_PERIOD + CHIME_NOTE_LEN + DOOR_CLOSE_TAIL
    n = n_samples(total)
    out = door_chime(n, n_samples(DOOR_CLOSE_HEAD))

    run = door_motor(DOOR_CLOSE_RUN_LEN, 910)
    start = n_samples(DOOR_CLOSE_RUN_AT)
    k = min(len(run), n - start)
    out[start:start + k] += DOOR_CLOSE_RUN_LEVEL * run[:k]

    latch = door_thunk(940, 1.0)
    e = start + k
    if e + len(latch) <= n:
        out[e:e + len(latch)] += DOOR_CLOSE_RUN_LEVEL * 1.6 * latch
    return normalise(fade_edges(out), PEAK["doorcls"])


# ===================================================================== data files


def csv_table(table):
    """Render one BVE5 motor-noise CSV.  Blank cells are legal and mean 'no knot'."""
    speeds = sorted({s for name in MOTOR_NAMES for s, _ in table[name]})
    header_cells = "," * len(MOTOR_NAMES)
    lines = [
        "bvets motor noise table 0.01" + header_cells,
        "#," + ",".join(str(i) for i in range(len(MOTOR_NAMES))),
    ]
    for speed in speeds:
        cells = []
        for name in MOTOR_NAMES:
            hit = [v for s, v in table[name] if s == speed]
            cells.append(("%g" % hit[0]) if hit else "")
        lines.append("%g," % speed + ",".join(cells))
    return "\n".join(lines) + "\n"


def sound_cfg():
    motors = "\n".join("%d = %s.wav" % (i, n) for i, n in enumerate(MOTOR_NAMES))
    return """BveTs Vehicle Sound 3.00

; LIRR M7 — generated by tools/gen_m7_sounds.py.  Do not hand-edit.
; Values are lowercased by MTR and become sound ids of the form
;   {ns}:{set}_<value>   e.g. {ns}:{set}_motor0

[MTR]
MotorNoiseDataType = {mnd}
MotorVolumeMultiply = {mvm:g}
DoorCloseSoundLength = {dcl:g}
BreakerDelay = {bd:g}
MotorOutputAtCoast = {moc:g}
RegenerationLimit = {rl:g}

; Pitched and mixed from {{power,brake}}{{vol,freq}}.csv against speed in km/h.
[Motor]
{motors}

; Rolling noise. MTR drives pitch and volume from speed directly (v_ms * 0.04),
; so run0 is authored to sit at pitch 1.0 at {ref:g} m/s.
[Run]
0 = run0.wav

; One shot as each bogie passes a rail node.
[Switch]
0 = point.wav

; Curve squeal. NOTE: MTR 4.0.5 plays the flange loop at volume 0 unconditionally.
[Flange]
0 = flange0.wav

[Brake]
BC Release = air.wav
BC Release Full = airzero.wav
BC Release High = airhigh.wav
Emergency = emrbrake.wav

[BrakeHandle]
Apply = brake_apply.wav
Release = brake_release.wav

[Compressor]
Attack = cp_start.wav
Loop = cp_loop.wav
Release = cp_end.wav

[Door]
Open = dooropn.wav
Close = doorcls.wav

[Others]
Noise = loop.wav
Shoe = rub.wav
""".format(
        ns=NAMESPACE,
        set=SET_NAME,
        mnd=MOTOR_NOISE_DATA_TYPE,
        mvm=MOTOR_VOLUME_MULTIPLY,
        dcl=DOOR_CLOSE_SOUND_LENGTH,
        bd=BREAKER_DELAY,
        moc=MOTOR_OUTPUT_AT_COAST,
        rl=REGENERATION_LIMIT,
        motors=motors,
        ref=RUN_REFERENCE_MS,
    )


def update_sounds_json(names):
    """Merge m7_* entries into our sounds.json, leaving every other key alone."""
    with open(SOUNDS_JSON) as handle:
        data = json.load(handle)
    prefix = SET_NAME + "_"
    data = {k: v for k, v in data.items() if not k.startswith(prefix)}
    for name in names:
        data[prefix + name] = {
            "sounds": [
                {
                    "name": "%s:%s/%s" % (NAMESPACE, SET_NAME, name),
                    "attenuation_distance": ATTENUATION,
                }
            ]
        }
    with open(SOUNDS_JSON, "w") as handle:
        json.dump(data, handle, indent=2)
        handle.write("\n")


# ===================================================================== driver


def build():
    voices = {}
    for i, (name, f0, partials, hiss, body, sideband) in enumerate(MOTOR_VOICES):
        if partials is None:
            voices[name] = make_motor_hum(name)
        else:
            voices[name] = make_carrier(name, f0, partials, hiss, body, sideband,
                                        1000 + i)
    voices["run0"] = make_run()
    voices["flange0"] = make_flange()
    voices["loop"] = make_noise_loop()
    voices["rub"] = make_shoe()
    voices["point"] = make_joint()
    voices["air"] = air_release(AIR_LEN, "air", 1100)
    voices["airhigh"] = air_release(AIRHIGH_LEN, "airhigh", 1110)
    voices["airzero"] = air_release(AIRZERO_LEN, "airzero", 1120)
    voices["emrbrake"] = make_emergency()
    voices["brake_apply"] = brake_handle("brake_apply", 1130, rising=True)
    voices["brake_release"] = brake_handle("brake_release", 1140, rising=False)
    voices["cp_start"] = make_compressor_start()
    voices["cp_loop"] = make_compressor_loop()
    voices["cp_end"] = make_compressor_end()
    voices["dooropn"] = make_door_open()
    voices["doorcls"] = make_door_close()
    return voices


def _spline(knots, speed):
    """MTR's FloatSplines.getValue: linear between knots, clamped outside."""
    lo = max((s for s, _ in knots if s <= speed), default=None)
    hi = min((s for s, _ in knots if s >= speed), default=None)
    table = dict(knots)
    if lo is None:
        return table[hi]
    if hi is None or lo == hi:
        return table[lo]
    return table[lo] + (table[hi] - table[lo]) * (speed - lo) / (hi - lo)


def selfcheck():
    """Assert the invariants this file's design depends on.  Runs every build."""
    assert abs(DOOR_OPEN_LEN - DOOR_MOVE_SECONDS) < 1e-9, \
        "dooropn must be laid out against MTR's 3.2 s door travel"
    assert MOTOR_OUTPUT_AT_COAST in (0.0, 1.0), \
        "a fractional MotorOutputAtCoast makes MTR chop the motor stack into blips"
    assert DOOR_CLOSE_SOUND_LENGTH == 1.0, "doorcls must fire as the leaves start"
    names = set(MOTOR_NAMES)
    for label, vol, freq in (("power", POWER_VOLUME, POWER_FREQUENCY),
                             ("brake", BRAKE_VOLUME, BRAKE_FREQUENCY)):
        assert set(vol) == names and set(freq) == names, label
        for name in MOTOR_NAMES:
            for speed in (x * 0.25 for x in range(0, 801)):
                v = _spline(vol[name], speed)
                assert 0.0 <= v <= 1.0, (label, name, speed, v)
                if v <= 0.02:
                    continue
                p = _spline(freq[name], speed)
                assert 0.5 <= p <= 2.0, \
                    "%s %s is audible at %g km/h with pitch %g, outside Minecraft's " \
                    "[0.5, 2.0] clamp" % (label, name, speed, p)
    # every voice must earn its place in the jar
    for name in MOTOR_NAMES:
        loudest = max(max(v for _, v in POWER_VOLUME[name]),
                      max(v for _, v in BRAKE_VOLUME[name]))
        assert loudest > 0.02, "%s is never audible" % name
    # braking below RegenerationLimit is force-muted by MTR: be silent already
    mute_kmh = REGENERATION_LIMIT * 3.6
    for name in MOTOR_NAMES:
        v = _spline(BRAKE_VOLUME[name], mute_kmh)
        assert v < 0.02, "%s brake volume %g at the %g km/h mute would click" % (
            name, v, mute_kmh)


def main():
    selfcheck()
    os.makedirs(SOUND_DIR, exist_ok=True)
    voices = build()

    for name, data in sorted(voices.items()):
        path = os.path.join(SOUND_DIR, name + ".ogg")
        sf.write(path, data.astype(np.float32), SR, format="OGG", subtype="VORBIS")
        print("%-16s %6.2f s  %7d bytes" % (name, len(data) / SR, os.path.getsize(path)))

    for filename, table in (
        ("powervol.csv", POWER_VOLUME),
        ("powerfreq.csv", POWER_FREQUENCY),
        ("brakevol.csv", BRAKE_VOLUME),
        ("brakefreq.csv", BRAKE_FREQUENCY),
    ):
        with open(os.path.join(SOUND_DIR, filename), "w") as handle:
            handle.write(csv_table(table))
        print("wrote", filename)

    with open(os.path.join(SOUND_DIR, "sound.cfg"), "w") as handle:
        handle.write(sound_cfg())
    print("wrote sound.cfg")

    update_sounds_json(sorted(voices))
    print("merged %d m7_* entries into sounds.json" % len(voices))
    print('\nvehicle index field:  "bveSoundBaseResource": "%s:%s"' % (NAMESPACE, SET_NAME))


if __name__ == "__main__":
    main()
