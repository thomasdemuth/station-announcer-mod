#!/usr/bin/env python3
"""Regenerates the LIRR M7 vehicle for MTR: properties and definitions.

Run from anywhere:  python3 tools/gen_m7_assets.py

    python3 tools/convert_openbve_m7.py     # body shell + textures (run FIRST)
    python3 tools/convert_m7_interior.py    # the saloon            (run SECOND)
    python3 tools/gen_m7_doors.py           # the door leaves       (run THIRD)
    python3 tools/gen_m7_assets.py          # what binds to them    (run FOURTH)
    python3 tools/convert_openbve_m7.py --check    # cross-checks every model
    python3 tools/convert_m7_interior.py --check   # the saloon's own checks
    python3 tools/gen_m7_sounds.py          # the BVE sound set (independent)

WHAT THIS PRODUCES
------------------
    (the vehicle index is composed by tools/gen_vehicle_index.py — see vehicles())
    assets/station_announcer/properties/vehicle/m7_*.json    group -> stage/type
    assets/station_announcer/properties/definition/m7*.json  where groups repeat

No MODEL is written here. Three other tools own the geometry and every `g`
group name this file binds:

    m7.obj           tools/convert_openbve_m7.py    the shell, floors, doorways
    m7_doors.bbmodel tools/gen_m7_doors.py          the four sliding leaves
    m7_interior.obj  tools/convert_m7_interior.py   the saloon and the seats

`convert_openbve_m7.py --check` cross-validates all three against these
properties files, both directions, PER MODEL: it reads the index written here
and pairs each `modelResource` with its own `modelPropertiesResource`, so a
name that belongs to one model cannot be covered by a group in another.

THREE MODELS PER VEHICLE, AND WHY
---------------------------------
MTR 4.0.5's .obj path cannot animate doors: it bakes a door part's position
offset into the mesh AND stores it on the part, so an animating leaf jumps by
its whole definition offset (7 blocks on this car), and .obj parts contribute
no DOORWAY boxes at all, which is what binds a leaf to one side of the car.
So the four leaves live in `m7_doors.bbmodel`, stacked into each vehicle's
`models` array against the SAME position definitions. The full reasoning, the
bytecode it came from and the box-UV layout are in the docstring of
`tools/gen_m7_doors.py` — read that before touching doors.

The saloon is a third model for a much duller reason: it is a separate donor
file (AInterior.csv) on its own texture set, and keeping it separate means the
shell and the interior can be regenerated independently. Everything it draws
is INTERIOR stage, and its definitions are POSITIONS-ONLY — the bodyside is
modelled for one side and mirrored by `positionsFlipped`, but the interior is
full width, so a flipped entry would draw a second copy inside the first.
NOTHING in the shell renders at INTERIOR stage any more: the placeholder
lining it used to carry (`window`, `door`, `end_cab*`, `end_gangway*`) is gone
from the model and from these properties. Its `*_floor` and `doorway` parts
stay — those are FLOOR/DOORWAY, not lining.

HOW MTR CUSTOM VEHICLES WORK (verified against MTR 4.0.5)
---------------------------------------------------------
`CustomResourceLoader` calls `readAllResources(mtr:mtr_custom_resources.json)` —
vanilla's "read this path from EVERY resource pack" — so this mod's own jar can
contribute vehicles without touching MTR. Resource strings inside are full
namespaced identifiers, so the model and textures live under `station_announcer:`
while only the index sits in the `mtr` namespace.

THE MODEL IS MODULAR, AND IT HAS TO BE
--------------------------------------
MTR does not want a whole 85 ft car. You model ONE bay of each kind, and
`properties/definition/*.json` repeats each named group at a list of z offsets.
That is what makes the 15-block "Mini" variant nearly free.

    end(74) | door(44) | window(30)x6 | door(44) | end(74)  = 416 units = 26 blk
      windows +-15/+-45/+-75    doors +-112   ends +-171 (span 134..208)
    mini: the OUTER 61 units of the same end bay, cropped at the same
          units/metre — end +-89.5, door +-37, one window at 0  = 240 = 15 blk

Every one of those numbers is DERIVED in tools/m7_layout.py, which both
converters share and which self-verifies on import. Change the car size there.

CAB ENDS vs GANGWAY ENDS — THE r179 STACKING PATTERN
-----------------------------------------------------
One model, one pair of definition files, and the per-variant difference is which
properties files the index stacks in a vehicle's `models` array. r179 does
exactly this and it is why a cab car needs no second model:

    trailer  common + end_1(gangway) + end_2(gangway)
    cab_1    common + cab_1          + end_2
    cab_2    common + end_1          + cab_2
    cab_3    common + cab_1          + cab_2

The end geometry is authored ONCE, facing outwards at its own local -z; `end1`
places it unflipped and `end2` places it flipped, which turns it to face the
other way. The MINI end pieces are separate groups (the outer 61 units of the
same bay), and the normal/mini split is done with EMPTY position lists
rather than a second set
of properties files — `r179_mini.json` proves `"positions": []` is legal.

THE FLIPPED-Z TRAP (measured, do not "fix" these signs)
--------------------------------------------------------
For an .obj model, `positionsFlipped` composes as R*(v+t) — translate, THEN
rotate — so an entry lands its group at MINUS z/16. The .bbmodel path composes
the other way round, R*v+t, so the SAME entry lands at PLUS z/16. Symmetric
position sets are immune; ours that are not:

    end2       positionsFlipped [{z:-171}]     .obj      ->      at +171
    doorFrontB positionsFlipped [{z:+112}]     .bbmodel  ->      at +112
    doorRearB  positionsFlipped [{z:-112}]     .bbmodel  ->      at -112

The four door definitions are deliberately ONE-SIDED — each leaf group appears
in `positions` or in `positionsFlipped`, never both — because that is what
pins a leaf to one side of the car. See tools/gen_m7_doors.py for the sign
table and its derivation, M7_CONVERSION_NOTES.md for the .obj measurements.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import m7_layout as L

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "src/main/resources")
MTR_NS = os.path.join(RES, "assets/mtr")
OUR_NS = os.path.join(RES, "assets/station_announcer")

MODEL_ID = "station_announcer:models/vehicle/m7.obj"
# Bound to the magic material name `default.png`, and kept pointing at a real
# file because DISPLAY parts read it. Every real surface takes its texture from
# m7.mtl instead — one .obj can carry many textures, one per usemtl.
TEXTURE_ID = "station_announcer:textures/vehicle/m7/default.png"

# The door leaves. A .bbmodel takes exactly one texture, and unlike the .obj it
# is a real Blockbench box-UV atlas — tools/gen_m7_doors.py writes both.
DOORS_MODEL_ID = "station_announcer:models/vehicle/m7_doors.bbmodel"
DOORS_TEXTURE_ID = "station_announcer:textures/vehicle/m7/doors_box.png"

# The saloon: walls, ceiling, partitions, glass and seats, converted from the
# donor's AInterior by tools/convert_m7_interior.py. It replaced the shell of
# synthesized lining the body model used to carry, which is why no part in this
# file renders the body at INTERIOR stage any more.
INTERIOR_MODEL_ID = "station_announcer:models/vehicle/m7_interior.obj"
BOGIE = "mtr:models/vehicle/bogie_1.bbmodel"
BOGIE_TEXTURE = "mtr:textures/vehicle/bogie_1.png"
BOGIE_PROPERTIES = "mtr:properties/vehicle/bogie_1.json"
ORIGIN_DEFINITION = "mtr:properties/definition/origin.json"

# Synthesized BVE-style M7 sound set (tools/gen_m7_sounds.py). The namespaced id
# makes BveVehicleSoundConfig read sounds/m7/sound.cfg + the power/brake CSVs from
# OUR namespace; setting it non-empty is also what disables the legacy sound path.
SOUND_BASE = "station_announcer:m7"

# Truck centres as a fraction of the coupler length. THE KNOB LIVES IN
# tools/m7_layout.py (with the prototype figure beside it) so the body and
# interior converters see the same number — tune it there, not here.
BOGIE_RATIO = L.BOGIE_RATIO

# --------------------------------------------------------------- the layout
#
# Bay centres and car lengths, in 1/16-block units, ALL of it derived in
# tools/m7_layout.py from the donor's own slice lengths — see Part 2 of
# M7_CONVERSION_NOTES.md. That module is shared with both converters and
# self-verifies on import, so this file never types a dimension.
#
#   normal 416 units = 26 blocks:  end(74) door(44) window(30)x6 door(44) end(74)
#          windows +-15/+-45/+-75      doors +-112      ends +-171
#   mini   240 units = 15 blocks:  end(61) door(44) window(30) door(44) end(61)
#          window 0                    doors +-37       ends +-89.5
WINDOWS = L.NORMAL.window.centres
DOORS = L.NORMAL.door.centres
END = -L.NORMAL_END_Z                  # the end bay is placed at -END, then flipped
MINI_WINDOWS = L.MINI.window.centres
MINI_DOORS = L.MINI.door.centres
MINI_END = -L.MINI_END_Z

BLOCKS_NORMAL = L.BLOCKS_NORMAL        # 26
BLOCKS_MINI = L.BLOCKS_MINI            # 15

# The leaf is 23 units wide and travels 1.45 m (23.5 units at this scale).
# R179's curve opens by |multiplier| - 0.5, so 24 opens exactly 23.5.
DOOR_MULTIPLIER = L.DOOR_MULTIPLIER


def part(names, definitions, **kw):
    out = {"names": list(names), "positionDefinitions": list(definitions)}
    out.update(kw)
    return out


def common_properties():
    """Everything that repeats along the car: windows, doors, roof, underframe."""
    return {
        "modelYOffset": 1,
        "parts": [
            # No INTERIOR-stage lining parts anywhere in this file: the saloon,
            # the vestibules and both end walls are the INTERIOR MODEL now
            # (m7_interior_*.json below). This model keeps the shell, the
            # floors and the doorway plates.
            part(["window_exterior"], ["window"], renderStage="EXTERIOR"),
            part(["roof"], ["window"], renderStage="EXTERIOR"),

            part(["door_exterior"], ["door"], renderStage="EXTERIOR"),
            part(["roof_door"], ["door"], renderStage="EXTERIOR"),
            # The sill strip from the saloon floor's edge out to the bodyside,
            # and the ONLY thing left of what used to be the shell's floor and
            # doorway parts. Those were dead weight twice over: an .obj feeds
            # MTR no FLOOR/DOORWAY boxes, and `ModelPropertiesPart.render`
            # switches on PartType with a case only for NORMAL and DISPLAY, so
            # FLOOR/DOORWAY/SEAT parts are never drawn either. The real
            # doorway and floor boxes are in m7_doors.bbmodel; the visible
            # floor is the interior model's. This one is NORMAL so it draws.
            part(["door_threshold"], ["door"], renderStage="EXTERIOR"),

            # The sliding leaves are NOT here: they are the .bbmodel stacked
            # in beside this file, because MTR's .obj path animates doors
            # wrongly. m7_doors.json below is their properties file.

            # The equipment raft: one 13 m elevation that does not tile, so it
            # is placed once per car rather than split across bays.
            part(["underframe"], ["underframe"], renderStage="EXTERIOR"),
            part(["underframe_mini"], ["underframeMini"], renderStage="EXTERIOR"),
        ],
    }


# The four leaves, and which way each one retracts. `flipped` is which of the
# two position lists the leaf's definition uses; a flipped instance negates the
# multiplier itself, so the stored sign is the one that makes the EFFECTIVE
# direction point at the leaf's own end of the car. Derivation and the bytecode
# it came from: tools/gen_m7_doors.py.
#
#   name          end   flipped   multiplier   effective slide
#   door_front_a  +z    no        +19          +z
#   door_front_b  +z    yes       -19          +z
#   door_rear_a   -z    no        -19          -z
#   door_rear_b   -z    yes       +19          -z
DOOR_LEAVES = (
    ("door_front_a", "doorFrontA", +DOOR_MULTIPLIER),
    ("door_front_b", "doorFrontB", -DOOR_MULTIPLIER),
    ("door_rear_a", "doorRearA", -DOOR_MULTIPLIER),
    ("door_rear_b", "doorRearB", +DOOR_MULTIPLIER),
)


def doors_properties():
    """The companion .bbmodel's four sliding leaves.

    Two parts per leaf, exactly as r179 does it: the solid box at INTERIOR so
    the face a rider sees is full-bright, and the flat skin 0.1 unit in front
    of it at EXTERIOR so the face the platform sees takes world light. Both
    carry the same multiplier, so the pair moves as one.
    """
    parts = []
    for group, definition, multiplier in DOOR_LEAVES:
        for name, stage in ((group, "INTERIOR"),
                            (group + "_exterior", "EXTERIOR")):
            parts.append(part([name], [definition], renderStage=stage,
                              doorZMultiplier=multiplier,
                              doorAnimationType="R179"))

    # THE ONLY FLOOR AND DOORWAY BOXES THE VEHICLE HAS. MTR reads them from a
    # .bbmodel and from nothing else — the .obj `writeCache` overload is not
    # even handed the two sets — so before these existed the game fell back to
    # synthesizing one car-length floor plus a doorway box every block along
    # BOTH sides, which is why passengers could board anywhere. The fallback
    # only fires when floors AND doorways are both empty, so these two must
    # always ship together; deleting either half silently deletes the other's
    # effect too.
    #
    # `doorway_box` rides the `door` definition, which lists both z entries in
    # BOTH lists — four placements, one per opening — so `mapDoors` can give
    # every leaf the box on its own side at its own end, which is what makes
    # only the platform side open.
    parts.append(part(["doorway_box"], ["door"], type="DOORWAY"))
    # The floors use bb* definitions rather than the .obj's: they are full
    # width, so they must not be mirrored by a flipped entry, and the end
    # plate is not symmetric, so its flipped entry needs the .bbmodel's own
    # sign. See position_definitions().
    parts.append(part(["floor_window"], ["bbWindow"], type="FLOOR"))
    parts.append(part(["floor_door"], ["bbDoor"], type="FLOOR"))
    parts.append(part(["floor_end"], ["bbEnd1", "bbEnd2"], type="FLOOR"))
    parts.append(part(["floor_end_mini"], ["bbEnd1Mini", "bbEnd2Mini"],
                      type="FLOOR"))
    # ⭐ The interior next-stop screens. They belong in THIS file, not in a
    # per-end one, because every car has vestibules — a trailer needs them as
    # much as a cab. `NEXT_STATION` is the plain variant: the KCR and MTR ones
    # run the string through `DisplayType.getHongKongNextStationString` and the
    # UK one through `getLondonNextStationString`, which dress it in a regional
    # format a Long Island commuter railroad has no business wearing.
    parts.append(part(["interior_next_station"], ["bbPartition"],
                      type="DISPLAY", displayType="NEXT_STATION",
                      displayOptions=list(DISPLAY_OPTIONS),
                      displayColor="FF9900",
                      displayDefaultText="",
                      displayXPadding=0.25, displayYPadding=0.2))
    # ⭐ The EXTERIOR SIDE DESTINATION SIGNS, in the letterboard beside every
    # door. Also in THIS file, and for the same reason: they are a property of
    # the bodyside, which every car has, not of a cab. `bbSideSign` carries
    # both door centres in BOTH position lists, so one authored element
    # becomes four signs — two per side — landing on the four housings the
    # bodyside paints. Amber on black and "Not In Service" when idle, exactly
    # as r179's own side pair does it.
    parts.append(part(["side_destination_display"], ["bbSideSign"],
                      type="DISPLAY", displayType="DESTINATION",
                      displayOptions=list(DISPLAY_OPTIONS),
                      displayColor="FF9900",
                      displayDefaultText="Not In Service",
                      displayXPadding=0.25, displayYPadding=0.25))
    return {"modelYOffset": 1, "parts": parts}


def interior_common_properties():
    """The saloon that repeats along the car.

    Everything is INTERIOR stage (CUTOUT_BRIGHT — full bright, so a rider is
    not sitting in the dark), and every definition it uses is POSITIONS-ONLY:
    the interior is modelled full width, so unlike the bodyside it must not be
    mirrored by a `positionsFlipped` entry or it would be drawn twice.

    `int_seat_zone` is the same geometry again as `type: SEAT` — that is what
    tells MTR where a passenger may sit, and it needs both row definitions
    because there is a seat in every row, facing whichever way that half of the
    car faces.
    """
    return {
        "modelYOffset": 1,
        "parts": [
            part(["int_window"], ["intWindow"], renderStage="INTERIOR"),
            part(["int_door"], ["intDoor"], renderStage="INTERIOR"),
            part(["int_seat_fwd"], ["intSeatFwd"], renderStage="INTERIOR"),
            part(["int_seat_rev"], ["intSeatRev"], renderStage="INTERIOR"),
            part(["int_seat_zone"], ["intSeatFwd", "intSeatRev"], type="SEAT"),
        ],
    }


def interior_end_properties(end, cab):
    """One end of the saloon: the cab bulkhead or the gangway end wall.

    Same stacking story as the body — the geometry is authored once facing its
    own local -z, `intEndN` places it and `intEndNMini` is the cropped variant
    — so a cab car and a trailer differ only in which of these two files the
    index stacks, exactly as they do for the shell.
    """
    kind = "cab" if cab else "gangway"
    parts = [
        part(["int_end_%s" % kind], ["intEnd%d" % end], renderStage="INTERIOR"),
        part(["int_end_%s_mini" % kind], ["intEnd%dMini" % end],
             renderStage="INTERIOR"),
    ]
    if cab:
        # The cab compartment itself, forward of the bulkhead. It is authored
        # in the SAME end-bay local frame as int_end_cab and butts straight up
        # against it (local z -34.20..-14.88 against -14.88..+37.00), so it
        # rides the same definitions and only ever appears on a cab end.
        parts.append(part(["int_cab"], ["intEnd%d" % end],
                          renderStage="INTERIOR"))
        parts.append(part(["int_cab_mini"], ["intEnd%dMini" % end],
                          renderStage="INTERIOR"))
    return {"modelYOffset": 1, "parts": parts}


def gangway_end_properties(end):
    """The diaphragm end — what a married pair couples through."""
    full, mini = "end%d" % end, "end%dMini" % end
    return {
        "modelYOffset": 1,
        "parts": [
            part(["end_gangway_exterior"], [full], renderStage="EXTERIOR"),
            part(["end_gangway_exterior_mini"], [mini], renderStage="EXTERIOR"),
        ],
    }


def cab_end_properties(end):
    """The cab end. Lamp conditions follow r179: end 1 is the forward end, so
    its headlights burn ON_ROUTE_FORWARDS and end 2's burn BACKWARDS."""
    full, mini = "end%d" % end, "end%dMini" % end
    forwards = "ON_ROUTE_FORWARDS" if end == 1 else "ON_ROUTE_BACKWARDS"
    backwards = "ON_ROUTE_BACKWARDS" if end == 1 else "ON_ROUTE_FORWARDS"
    parts = [
        part(["end_cab_exterior"], [full], renderStage="EXTERIOR"),
        part(["end_cab_exterior_mini"], [mini], renderStage="EXTERIOR"),
    ]
    for group, definition in (("headlights", full), ("headlights_mini", mini),
                              ("tail_lights", full), ("tail_lights_mini", mini)):
        head = group.startswith("headlights")
        parts.append(part([group], [definition],
                          condition=forwards if head else backwards,
                          renderStage="ALWAYS_ON_LIGHT"))
        if not head:
            # Markers also burn while the unit is stabled, exactly as r179 does.
            parts.append(part([group], [definition], condition="AT_DEPOT",
                              renderStage="ALWAYS_ON_LIGHT"))
    return {"modelYOffset": 1, "parts": parts}


# The cab destination sign. Only reachable from a .bbmodel — MTR 4.0.5's .obj
# writeCache drops every part whose type is not NORMAL before it does anything,
# and `ModelDisplayPart` can only be built from a Blockbench element's
# from/to/origin (verified in bytecode, see M7_CONVERSION_NOTES.md). So these
# bind against m7_doors.bbmodel, which is already in every vehicle's models
# array — but through a properties file of their OWN, stacked only on the ends
# that actually have a cab. A trailer must never get one.
#
# ⭐ THE DESTINATION SCROLLS (user request, 2026-07-28). `SCROLL_NORMAL` is the
# option, and the working idiom is MTR's OWN: `s700_cab_*.json`'s
# `interior_display_1` declares `["SINGLE_LINE", "SCROLL_NORMAL"]` — the two go
# together, because scrolling a wrapped multi-line block is not a thing MTR
# does. SINGLE_LINE is therefore not optional here.
#
# UPPER_CASE and CYCLE_LANGUAGES are orthogonal and kept (r179 and every MTR
# destination ships them). ALIGN_CENTER is dropped: it is not actually a
# DisplayOption constant at all — MTR matches displayOptions as raw strings, so
# it always fell through to the default alignment — and alignment is meaningless
# once the text is a marquee.
#
# The 16x2 element is a wide, short LED strip, which is the shape this wants.
DISPLAY_OPTIONS = ["UPPER_CASE", "CYCLE_LANGUAGES",
                   "SINGLE_LINE", "SCROLL_NORMAL"]

# The route-colour bar takes no options — it is a solid colour block, and MTR's
# own `*_route_color_display` parts declare none either. Left exactly as it was
# (user: "keep the ROUTE_COLOR bar as-is"); only its y moved with the sign.
_DISPLAY = "m7_display_%d.json"


def display_end_properties(end):
    """The destination sign and route-colour bar on one cab end."""
    full, mini = "bbEnd%d" % end, "bbEnd%dMini" % end
    parts = []
    for group, definition in (("front_destination_display", full),
                              ("front_destination_display_mini", mini)):
        parts.append(part([group], [definition], type="DISPLAY",
                          displayType="DESTINATION",
                          displayOptions=list(DISPLAY_OPTIONS),
                          displayColor="FF9900",
                          displayDefaultText="Not In Service",
                          displayXPadding=0.25, displayYPadding=0.25))
    for group, definition in (("front_route_color", full),
                              ("front_route_color_mini", mini)):
        parts.append(part([group], [definition], type="DISPLAY",
                          displayType="ROUTE_COLOR",
                          displayXPadding=0, displayYPadding=0))
    return {"modelYOffset": 1, "parts": parts}


def position_definitions(normal):
    """Where each group repeats. Normal is the 26-block car; Mini is 15.

    A definition that belongs to the other length gets an EMPTY list rather
    than being left out, so one set of properties files drives both.
    """
    layout = L.NORMAL if normal else L.MINI
    windows = WINDOWS if normal else MINI_WINDOWS
    doors = DOORS if normal else MINI_DOORS
    front = doors[-1]

    def z(values):
        return [{"z": v} for v in values]

    def both(name, values):
        return {"name": name, "positions": z(values), "positionsFlipped": z(values)}

    full_only = z([-END]) if normal else []
    mini_only = [] if normal else z([-MINI_END])
    return {"positionDefinitions": [
        # Symmetric sets, so the flipped list is identical and immune to the
        # composition-order trap in the module docstring.
        both("window", windows),
        both("door", doors),
        # The leaves, one-sided on purpose: a group that appears in exactly
        # one list is pinned to one side of the car, which is what keeps the
        # platform side opening on its own. These are .bbmodel groups, and the
        # .bbmodel path lands a flipped entry at PLUS z (the .obj lands it at
        # minus), so the b-leaves carry their own end's z unnegated.
        {"name": "doorFrontA", "positions": z([front]), "positionsFlipped": []},
        {"name": "doorFrontB", "positions": [], "positionsFlipped": z([front])},
        {"name": "doorRearA", "positions": z([-front]), "positionsFlipped": []},
        {"name": "doorRearB", "positions": [], "positionsFlipped": z([-front])},
        # end1 unflipped at -end; end2 flipped, also written -end, which the
        # flip turns into +end facing the other way.
        {"name": "end1", "positions": full_only},
        {"name": "end2", "positionsFlipped": full_only},
        {"name": "end1Mini", "positions": mini_only},
        {"name": "end2Mini", "positionsFlipped": mini_only},
        {"name": "underframe", "positions": z([0]) if normal else []},
        {"name": "underframeMini", "positions": [] if normal else z([0])},

        # --- the door .bbmodel's floors -------------------------------
        # Same bays as the shell, but positions-only (a full-width plate must
        # not be mirrored) and, for the ends, with the sign the .bbmodel path
        # needs: it lands a flipped entry at PLUS z where the .obj lands it at
        # minus, so bbEnd2 carries +END while end2 carries -END. Get this
        # backwards and both end floors pile up on the same end of the car.
        {"name": "bbWindow", "positions": z(windows)},
        {"name": "bbDoor", "positions": z(doors)},
        # The next-stop screens. BOTH lists at both door centres, which is
        # what puts one on each of the FOUR vestibule partitions from a single
        # element: two partitions face the saloon at -z and two at +z, and the
        # flip is a 180-degree turn that supplies the second pair already
        # pointing the right way. See gen_m7_doors' interior_next_station.
        both("bbPartition", doors),
        # The exterior side signs. BOTH lists at both door centres again, but
        # for the opposite reason: here the element is deliberately NOT
        # x-symmetric — it is authored on the +x bodyside — so the flip is
        # what puts the second sign on the -x side, facing out. It also
        # reverses the sign's along-car offset within the bay, which is
        # exactly what the SHELL's own flipped door bay does to the painted
        # housing, so the two stay on top of each other. gen_m7_doors'
        # `verify_side_sign()` bakes all four and proves it.
        both("bbSideSign", doors),
        {"name": "bbEnd1", "positions": full_only},
        {"name": "bbEnd2", "positionsFlipped": z([END]) if normal else []},
        {"name": "bbEnd1Mini", "positions": mini_only},
        {"name": "bbEnd2Mini",
         "positionsFlipped": [] if normal else z([MINI_END])},

        # --- the interior model ---------------------------------------
        # POSITIONS ONLY, deliberately. The bodyside is modelled for one side
        # and mirrored by `positionsFlipped`; the interior is modelled full
        # width, so a flipped entry would draw a second copy inside the first.
        # These mirror tools/convert_m7_interior.py's own placements() — that
        # function is the render-verified one, and it reads the same
        # tools/m7_layout.py these do, so the two cannot drift apart silently.
        {"name": "intWindow", "positions": z(windows)},
        {"name": "intDoor", "positions": z(doors)},
        # Seats face the nearer end: the -z half faces +z and vice versa, so
        # the two halves are separate groups rather than one mirrored one.
        {"name": "intSeatFwd", "positions": z(layout.seat_rows_forward)},
        {"name": "intSeatRev", "positions": z(layout.seat_rows_reverse)},
        {"name": "intEnd1", "positions": full_only},
        {"name": "intEnd2", "positionsFlipped": full_only},
        {"name": "intEnd1Mini", "positions": mini_only},
        {"name": "intEnd2Mini", "positionsFlipped": mini_only},
    ]}


# ----------------------------------------------------------------- index

# id suffix -> (label, properties for end 1, properties for end 2)
# id suffix -> (label, whether end 1 is a cab, whether end 2 is a cab). The
# shell and the interior both stack a per-end file, and both have to agree
# about which end is a cab, so the pair is derived from these two flags rather
# than listing four filenames.
KINDS = (
    ("trailer", "Trailer", False, False),
    ("cab_1", "Cab (Forwards)", True, False),
    ("cab_2", "Cab (Backwards)", False, True),
    ("cab_3", "Cab (Double)", True, True),
)


def end_files(cab1, cab2):
    """(shell properties, interior properties) for each end, in order."""
    shell = ["m7_%s_1.json" % ("cab" if cab1 else "end"),
             "m7_%s_2.json" % ("cab" if cab2 else "end")]
    interior = ["m7_interior_%s_1.json" % ("cab" if cab1 else "end"),
                "m7_interior_%s_2.json" % ("cab" if cab2 else "end")]
    return shell, interior


def display_files(cab1, cab2):
    """Display properties for the ends that have a cab, in order. Empty for a
    trailer — a car with no cab has no cab front to put a sign on."""
    return ([_DISPLAY % 1] if cab1 else []) + ([_DISPLAY % 2] if cab2 else [])


def vehicle(vehicle_id, name, length, definition, doors, kind, cab1, cab2):
    def model_entry(properties, model=MODEL_ID, texture=TEXTURE_ID):
        return {
            "modelResource": model,
            "textureResource": texture,
            "modelPropertiesResource": "station_announcer:properties/vehicle/%s" % properties,
            "positionDefinitionsResource": "station_announcer:properties/definition/%s" % definition,
        }

    shell_ends, interior_ends = end_files(cab1, cab2)
    bogie = [{
        "modelResource": BOGIE,
        "textureResource": BOGIE_TEXTURE,
        "modelPropertiesResource": BOGIE_PROPERTIES,
        "positionDefinitionsResource": ORIGIN_DEFINITION,
    }]
    return {
        "id": vehicle_id,
        "name": name,
        "color": "B9BDC2",
        "transportMode": "TRAIN",
        "width": 2,
        "couplingPadding1": 0,
        "couplingPadding2": 0,
        "wikipediaArticle": "M7_(railcar)",
        "tags": ["family:m7", "iso_3166:US", "doors:%d" % doors, "type:%s" % kind],
        # THREE models per vehicle, all on the same position definitions:
        # the shell (.obj, common + one end file per end), the sliding door
        # leaves (.bbmodel — MTR's .obj path animates doors wrongly, see the
        # module docstring) and the saloon (.obj, again common + per end).
        # Cab and trailer differ only in which end files get stacked.
        # The doors .bbmodel is stacked a second time for a cab end, carrying
        # nothing but the DISPLAY parts — MTR's own idiom of one model with
        # several properties files (r179 does exactly this for its head cars),
        # and the only way to give a cab a destination sign when the shell is
        # an .obj. Those entries contain no NORMAL part, so nothing is drawn
        # twice.
        "models": ([model_entry("m7_common.json")]
                   + [model_entry(p) for p in shell_ends]
                   + [model_entry("m7_doors.json", DOORS_MODEL_ID,
                                  DOORS_TEXTURE_ID)]
                   + [model_entry(p, DOORS_MODEL_ID, DOORS_TEXTURE_ID)
                      for p in display_files(cab1, cab2)]
                   + [model_entry("m7_interior_common.json", INTERIOR_MODEL_ID)]
                   + [model_entry(p, INTERIOR_MODEL_ID)
                      for p in interior_ends]),
        "bogie1Models": bogie,
        "bogie2Models": bogie,
        "hasGangway1": True,
        "hasGangway2": True,
        "hasBarrier1": True,
        "hasBarrier2": True,
        "bveSoundBaseResource": SOUND_BASE,
        "length": float(length),
        # Kept as a proportion of the car so the Mini variant's trucks sit
        # correctly too, rather than a fixed inset that only suits the long one.
        "bogie1Position": -length * BOGIE_RATIO / 2.0,
        "bogie2Position": length * BOGIE_RATIO / 2.0,
    }


def vehicles():
    """Every M7 vehicle, for tools/gen_vehicle_index.py to compose.

    ⭐ THIS FILE NO LONGER WRITES THE INDEX (2026-07-28, when the R62 arrived).
    `mtr_custom_resources.json` is ONE file holding every custom train the mod
    ships, so one train's generator cannot own it: the first run of the R62's
    generator would have replaced these eight entries with its own. The index is
    composed by `tools/gen_vehicle_index.py` from each train's `vehicles()`
    provider, and `main()` below calls that composer at the end so running this
    alone still leaves a correct index.

    Keep this free of side effects — the composer calls it purely to read.
    """
    out = []
    # The M7 runs in married pairs with a cab at each car's outer end, so a
    # prototypical consist is cab_1 + cab_2. Trailer and the double-ended cab
    # are registered too, exactly as MTR does for the R211.
    for length, definition, suffix, label in (
            (BLOCKS_NORMAL, "m7.json", "", "Normal"),
            (BLOCKS_MINI, "m7_mini.json", "_mini", "Mini")):
        doors = 4
        for kind, kind_label, cab1, cab2 in KINDS:
            out.append(vehicle(
                "m7%s_%s" % (suffix, kind),
                "LIRR M7 %s (%s)" % (kind_label, label),
                length, definition, doors, kind, cab1, cab2))
    return out


# ------------------------------------------------------------------- main

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def main():
    props = os.path.join(OUR_NS, "properties/vehicle")
    write_json(os.path.join(props, "m7_common.json"), common_properties())
    write_json(os.path.join(props, "m7_doors.json"), doors_properties())
    write_json(os.path.join(props, "m7_interior_common.json"),
               interior_common_properties())
    for end in (1, 2):
        write_json(os.path.join(props, "m7_interior_end_%d.json" % end),
                   interior_end_properties(end, cab=False))
        write_json(os.path.join(props, "m7_interior_cab_%d.json" % end),
                   interior_end_properties(end, cab=True))
    write_json(os.path.join(props, "m7_end_1.json"), gangway_end_properties(1))
    write_json(os.path.join(props, "m7_end_2.json"), gangway_end_properties(2))
    write_json(os.path.join(props, "m7_cab_1.json"), cab_end_properties(1))
    write_json(os.path.join(props, "m7_cab_2.json"), cab_end_properties(2))
    for end in (1, 2):
        write_json(os.path.join(props, _DISPLAY % end),
                   display_end_properties(end))
    write_json(os.path.join(OUR_NS, "properties/definition/m7.json"),
               position_definitions(True))
    write_json(os.path.join(OUR_NS, "properties/definition/m7_mini.json"),
               position_definitions(False))

    # The placeholder shell the .obj replaced. Removing them here keeps the
    # generators the single source of truth for what ships.
    for stale in ("models/vehicle/m7.bbmodel", "textures/vehicle/m7.png"):
        path = os.path.join(OUR_NS, stale)
        if os.path.exists(path):
            os.remove(path)
            print("removed placeholder %s" % stale)

    # The index is SHARED with every other custom train (see `vehicles()`), so
    # it is composed rather than written here. Imported inside main() because
    # the composer imports this module for its provider.
    import gen_vehicle_index
    total = gen_vehicle_index.write()
    print("M7 assets written: %d vehicles (index now holds %d)"
          % (len(vehicles()), total))


if __name__ == "__main__":
    main()
