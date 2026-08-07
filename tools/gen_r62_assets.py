#!/usr/bin/env python3
"""Regenerates the NYCT R62 vehicle for MTR: properties and definitions.

    python3 tools/convert_openbve_r62.py    # shell + textures      (FIRST)
    python3 tools/gen_r62_doors.py          # leaves + displays     (SECOND)
    python3 tools/gen_r62_assets.py         # THIS                  (THIRD)
    python3 tools/convert_openbve_r62.py --check                    (LAST)

WHAT THIS PRODUCES
------------------
    assets/station_announcer/properties/vehicle/r62_*.json   group -> stage/type
    assets/station_announcer/properties/definition/r62.json  where groups repeat

⭐ IT DOES NOT WRITE THE INDEX. `assets/mtr/mtr_custom_resources.json` is ONE
file shared by every custom train this mod ships, so it is composed by
`tools/gen_vehicle_index.py` from each generator's `vehicles()` provider. `main()`
calls that composer at the end, so running this alone still leaves a correct
index — but two generators can no longer overwrite each other's entries, which
is what would have happened the moment the R62 arrived beside the M7.

No MODEL is written here. Two other tools own the geometry and every `g` group
and element name this file binds:

    r62.obj            tools/convert_openbve_r62.py   the shell
    r62_doors.bbmodel  tools/gen_r62_doors.py         leaves, floors, displays

`convert_openbve_r62.py --check` cross-validates BOTH against these properties
files in both directions, per model, by reading the index and pairing each
`modelResource` with its own `modelPropertiesResource`.

TWO MODELS PER VEHICLE, AND WHY
--------------------------------
MTR 4.0.5's .obj path cannot animate doors, contributes no FLOOR or DOORWAY
boxes, and drops DISPLAY parts silently. All three defects are established in
M7_CONVERSION_NOTES.md and are the reason the leaves, the floors, the doorway
boxes and every display live in a companion .bbmodel stacked into each vehicle's
`models` array against the SAME position definitions.

TWO VEHICLES, AND WHY THE SECOND ONE IS A FICTION
--------------------------------------------------
    r62         cab at BOTH ends — the real car. An R62 is a single unit.
    r62_middle  blind ends: a plain stainless end with the storm door, no
                windshield, no route roundel, no lamps.

No such car ever existed. It is here because MTR consists are built from
whatever vehicles you pick, and a ten-car train of double-ended cabs shows a cab
face at every coupling. `r62_middle` lets a builder put real cabs on the outer
ends and blind ends inside. The shell is IDENTICAL — same nose, same anticlimber,
same storm door — so this costs one extra texture and one extra group, not a
second model.

⭐ THE POSITION DEFINITIONS ARE WHERE THE ROTATIONAL SYMMETRY LIVES
--------------------------------------------------------------------
The R62 is 180-degree rotationally symmetric, so every bay group models BOTH
SIDES and a `positionsFlipped` entry supplies the other END, not the other side.
That inverts the M7's rule and it changes what each definition means:

    panel   ONE unflipped + ONE flipped  -> two panel bays, each with the
            rollsign on one side and a plain window on the other, diagonally
            opposite. A both-lists symmetric definition would draw each bay
            twice, inside itself.
    door    positions ONLY, three entries. The door bay IS 180-symmetric in
            itself, so a flipped copy would be a second bay in the same place.
    end     ONE unflipped + ONE flipped, exactly as r179 does its ends.

⭐ THE FLIPPED-Z TRAP (measured, do not "fix" these signs)
-----------------------------------------------------------
For an .obj, `positionsFlipped` composes as R*(v + t) — translate, THEN rotate —
so an entry lands its group at MINUS z/16. The .bbmodel path composes the other
way round, R*v + t, so the SAME entry lands at PLUS z/16. Every definition below
therefore exists TWICE where both models need it, and the `bb*` copy carries the
NEGATED z of its .obj twin:

    .obj      end2   positionsFlipped [{z:+108.75}]   ->  lands at -108.75
    .bbmodel  bbEnd2 positionsFlipped [{z:-108.75}]   ->  lands at -108.75
    .obj      panel  positionsFlipped [{z: +39.5}]    ->  lands at  -39.5
    .bbmodel  bbPanel positionsFlipped [{z: -39.5}]   ->  lands at  -39.5

Get one of those backwards and both ends of the car pile up at the same end, or
the side sign's text hangs on bare stainless at the wrong end. Nothing in game
reports it — which is why `convert_openbve_r62.py --check` bakes both models
through their own transform and MEASURES where each display lands.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r62_layout as L

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "src/main/resources")
OUR_NS = os.path.join(RES, "assets/station_announcer")

MODEL_ID = "station_announcer:models/vehicle/r62.obj"
# Bound to the magic material name `default.png`. Every real surface takes its
# texture from r62.mtl instead — one .obj can carry many textures, one per
# usemtl — but this has to point at a real file all the same.
TEXTURE_ID = "station_announcer:textures/vehicle/r62/default.png"

DOORS_MODEL_ID = "station_announcer:models/vehicle/r62_doors.bbmodel"
DOORS_TEXTURE_ID = "station_announcer:textures/vehicle/r62/doors_box.png"

INTERIOR_MODEL_ID = "station_announcer:models/vehicle/r62_interior.obj"
# Same story as TEXTURE_ID above: bound to the magic material name
# `default.png` and nothing else. Every interior surface takes its pixels from
# r62_interior.mtl, which points at the one atlas.
INTERIOR_TEXTURE_ID = ("station_announcer:textures/vehicle/r62/interior/"
                       "int_atlas.png")

BOGIE = "mtr:models/vehicle/bogie_1.bbmodel"
BOGIE_TEXTURE = "mtr:textures/vehicle/bogie_1.png"
BOGIE_PROPERTIES = "mtr:properties/vehicle/bogie_1.json"
ORIGIN_DEFINITION = "mtr:properties/definition/origin.json"

# ⭐ SOUNDS ARE BORROWED FROM MTR'S OWN R179, DELIBERATELY AND TEMPORARILY.
# A dedicated synthesized R62 set (GE SCM camshaft propulsion — a completely
# different noise from the R179's AC drive) is a later pass; the donor pack has
# 60 wav files that are analysis reference only, exactly as the M7's were.
# Until then the R179 is the closest thing MTR ships: a NYCT car, the same door
# chime family, and its legacy set is already in the jar so this costs nothing.
# ⭐⭐ THIS MUST BE A BARE PATH WITH NO NAMESPACE. Writing "mtr:r179" here is
# what made both trains strobe in motion and froze riders (2026-07-30).
# `legacySpeedSoundBaseResource` / `legacyDoorSoundBaseResource` are NOT run
# through `CustomResourceTools.formatIdentifier` — bytecode-verified, they are
# passed raw from `VehicleResource` to `LegacyVehicleSound`, which composes
#     new Identifier("mtr", base + "_acceleration_0a")   (playMotorSound)
#     new Identifier("mtr", base + "_door_open")         (playDoorSound)
# with the namespace HARDCODED. A namespaced base therefore yields the illegal
# path `mtr:mtr:r179_acceleration_0a` and throws InvalidIdentifierException
# INSIDE `RenderVehicles.render`, which escapes to MTR's catch-all and aborts
# the ENTIRE MTR render pass for that frame — every vehicle in view disappears.
# Parked trains were fine only because playMotorSound early-returns at speed 0.
# ⭐ Do NOT reason from `bveSoundBaseResource`, which is the opposite: that one
# is namespace-AWARE (`BveVehicleSoundConfig` does `if (base.contains(":"))`),
# which is why the M7's `station_announcer:m7` is correct and this must not be.
# All 482 legacy-sound values in MTR's own index are bare. Ours must match.
SOUND_BASE = "r179"
SOUND_COUNT = 66
DOOR_CLOSE_TIME = 1

DEFINITION = "r62.json"

# ------------------------------------------------------------- the layout
#
# Bay centres and the car length, in 1/16-block units, ALL derived in
# tools/r62_layout.py from the donor's own bay boundaries. That module is shared
# with both other tools and self-verifies on import, so nothing here is typed.
PANEL_Z = L.PANEL_Z                    # +39.5
DOORS = L.NORMAL.door.centres          # [-79, 0, +79]
END_Z = L.END_Z                        # +108.75


def part(names, definitions, **kw):
    out = {"names": list(names), "positionDefinitions": list(definitions)}
    out.update(kw)
    return out


def common_properties():
    """The two bays that repeat along the car, both sides at once."""
    return {
        "modelYOffset": 1,
        "parts": [
            part(["panel_exterior"], ["panel"], renderStage="EXTERIOR"),
            part(["door_exterior"], ["door"], renderStage="EXTERIOR"),
        ],
    }


# The two leaves. ⭐ EACH RIDES BOTH POSITION LISTS, which is what puts it on
# both sides of the car — the R62's leaf pair is rotationally symmetric, so a
# flipped instance IS the other side's leaf. The M7 needed four one-sided groups
# because its pair had no such symmetry. The multiplier's effective direction is
# sign(m) unflipped and -sign(m) flipped, and the leaf's own position flips with
# it, so ONE sign is right in both lists. Derivation: tools/gen_r62_doors.py.
DOOR_LEAVES = (
    ("door_narrow", +L.DOOR_MULTIPLIER_NARROW),
    ("door_wide", -L.DOOR_MULTIPLIER_WIDE),
)

# The side rollsign's four fields. ⭐ MTR HAS NO "ROUTE NAME" DISPLAY TYPE —
# verified in 4.0.5 bytecode: `ModelPropertiesPart.formatText` does compute the
# route's name, but only to feed `getLondonNextStationString`; the enum is
# DESTINATION, ROUTE_NUMBER, DEPARTURE_INDEX, NEXT_STATION{,_KCR,_MTR,_UK},
# ROUTE_COLOR, ROUTE_COLOR_ROUNDED and nothing else. `ROUTE_NUMBER` renders
# `VehicleExtraData.getThisRouteNumber()`, a free string an operator sets per
# route — usually the line's identity, which is exactly what the top line of an
# R62's side sign carries. So the top line is ROUTE_NUMBER and this note is the
# documentation the brief asked for.
#
# The bullet is the NYC roundel: a ROUTE_COLOR_ROUNDED disc with the same
# ROUTE_NUMBER over it. White, because an NYC bullet's numeral is white and the
# sign behind it is black — not MTR's amber default.
SCROLL = ["UPPER_CASE", "CYCLE_LANGUAGES", "SINGLE_LINE", "SCROLL_NORMAL"]

SIDE_DISPLAYS = (
    # (group, displayType, options, colour, padding, default text)
    ("side_bullet_color", "ROUTE_COLOR_ROUNDED", [], None, 0.0, None),
    ("side_bullet_number", "ROUTE_NUMBER", ["UPPER_CASE"], "FFFFFF", 0.0, ""),
    ("side_route_number", "ROUTE_NUMBER", SCROLL, "FFFFFF", 0.25, ""),
    ("side_destination", "DESTINATION", SCROLL, "FFFFFF", 0.25, "Not In Service"),
)

FRONT_DISPLAYS = (
    ("front_route_color", "ROUTE_COLOR_ROUNDED", [], None, 0.0, None),
    ("front_route_number", "ROUTE_NUMBER", ["UPPER_CASE"], "FFFFFF", 0.25, ""),
)

# ⭐ THE INTERIOR STRIP MAP'S LIVE INSERTS (user decision: a hybrid — painted
# housing, painted generic station ticks, live displays). Three fields, and one
# set per SIDE of the car: `_a` is authored on +x and `_b` on -x, and each rides
# `bbPanel`'s two lists, so a car carries four maps. A map you can only see from
# one seat is half a map.
#
# `NEXT_STATION` is the plain one of MTR's four next-station types: read out of
# 4.0.5's bytecode, its case in `ModelPropertiesPart.formatText` renders the
# STATION NAME alone — the current station while the doors are open, the next
# one otherwise — with no prefix and no interchange furniture. That is exactly
# what the LED insert in a New York strip map shows. The _KCR / _MTR / _UK
# variants all wrap the name in Hong Kong or London phrasing.
#
# Scrolling, because a station name is as long as the builder made it and the
# strip is 18 units wide: `SINGLE_LINE` + `SCROLL_NORMAL`, which is the pair
# MTR's own s700 interior display declares and the only scrolling display in
# the jar. The default text is EMPTY on purpose — an unlit LED window is what a
# parked train has, and it is what MTR's own s700 does.
INTERIOR_DISPLAYS = (
    ("int_map_bullet_color", "ROUTE_COLOR_ROUNDED", [], None, 0.0, None),
    ("int_map_bullet_number", "ROUTE_NUMBER", ["UPPER_CASE"], "FFFFFF", 0.0,
     ""),
    ("int_map_next", "NEXT_STATION", SCROLL, "FFFFFF", 0.25, ""),
)


def display_part(group, definitions, kind, options, colour, padding, default):
    out = part([group], definitions, type="DISPLAY", displayType=kind,
               displayXPadding=padding, displayYPadding=padding)
    if options:
        out["displayOptions"] = list(options)
    if colour:
        out["displayColor"] = colour
    if default is not None:
        out["displayDefaultText"] = default
    return out


def doors_properties():
    """The companion .bbmodel: leaves, floors, doorways and the side sign.

    Two parts per leaf, exactly as r179 does it: the inboard plane at INTERIOR
    so the face a rider sees is full bright, and the outboard one at EXTERIOR so
    the face the platform sees takes world light. Both carry the same multiplier
    so the pair moves as one.
    """
    parts = []
    for group, multiplier in DOOR_LEAVES:
        for name, stage in ((group, "INTERIOR"),
                            (group + "_exterior", "EXTERIOR")):
            parts.append(part([name], ["bbDoor"], renderStage=stage,
                              doorZMultiplier=multiplier,
                              doorAnimationType="R179"))

    # ⭐ THE ONLY FLOOR AND DOORWAY BOXES THE VEHICLE HAS, and they must always
    # ship TOGETHER: MTR synthesizes a fallback (one car-length floor plus a
    # doorway box every block down BOTH sides) whenever floors AND doorways are
    # both empty, so deleting one silently deletes the other's effect. They cost
    # nothing to draw because they are never drawn —
    # `ModelPropertiesPart.render` has a case only for NORMAL and DISPLAY.
    #
    # `doorway_box` rides `bbDoor`'s six entries — three openings in each list —
    # so `mapDoors` can give every leaf the box on its own side at its own
    # opening, which is what makes only the platform side open.
    parts.append(part(["doorway_box"], ["bbDoor"], type="DOORWAY"))
    parts.append(part(["floor_panel"], ["bbFloorPanel"], type="FLOOR"))
    parts.append(part(["floor_door"], ["bbFloorDoor"], type="FLOOR"))
    parts.append(part(["floor_end"], ["bbFloorEnd"], type="FLOOR"))

    # The side rollsign. It belongs in THIS file rather than in a per-end one
    # because every car has it — a blind-ended middle car shows its route on the
    # side exactly as a cab car does.
    for group, kind, options, colour, padding, default in SIDE_DISPLAYS:
        parts.append(display_part(group, ["bbPanel"], kind, options, colour,
                                  padding, default))

    # ...and the interior strip map's live inserts, one set per side. They are
    # here rather than in an interior properties file for the same reason the
    # side sign is: they live in the DOORS .bbmodel (displays cannot be on an
    # .obj at all), and a properties file binds groups within ONE model.
    for group, kind, options, colour, padding, default in INTERIOR_DISPLAYS:
        for suffix in ("_a", "_b"):
            parts.append(display_part(group + suffix, ["bbPanel"], kind,
                                      options, colour, padding, default))
    return {"modelYOffset": 1, "parts": parts}


def interior_common_properties():
    """The saloon's two repeating bays, both sides at once.

    ⭐ EVERY INTERIOR PART IS `INTERIOR`, NOT `EXTERIOR`. That render stage is
    CUTOUT_BRIGHT — full bright — which is what stops a saloon going black at
    night and in a tunnel, and it is what MTR's own stock uses inside. It also
    makes an alpha-0 pixel a real hole, which is how the lining's window
    apertures work.
    """
    return {
        "modelYOffset": 1,
        "parts": [
            part(["int_panel"], ["panel"], renderStage="INTERIOR"),
            part(["int_door"], ["door"], renderStage="INTERIOR"),
        ],
    }


def interior_end_properties(end, cab):
    """One end of the saloon: the bench, the storm door, and the half-cab.

    A blind end gets `int_end_blind`, which is the same room with the cab
    replaced by more lining and a second bench — the interior half of the
    `r62_middle` fiction.
    """
    group = "int_end_cab" if cab else "int_end_blind"
    return {
        "modelYOffset": 1,
        "parts": [part([group], ["end%d" % end], renderStage="INTERIOR")],
    }


def cab_end_properties(end):
    """A cab end: the shell, and lamps that follow the direction of travel.

    Lamp conditions follow r179: end 1 is the forward end, so its headlights
    burn ON_ROUTE_FORWARDS and end 2's burn BACKWARDS, with the red markers the
    inverse plus AT_DEPOT.
    """
    definition = "end%d" % end
    forwards = "ON_ROUTE_FORWARDS" if end == 1 else "ON_ROUTE_BACKWARDS"
    backwards = "ON_ROUTE_BACKWARDS" if end == 1 else "ON_ROUTE_FORWARDS"
    parts = [part(["end_exterior"], [definition], renderStage="EXTERIOR"),
             part(["headlights"], [definition], condition=forwards,
                  renderStage="ALWAYS_ON_LIGHT"),
             part(["tail_lights"], [definition], condition=backwards,
                  renderStage="ALWAYS_ON_LIGHT"),
             part(["tail_lights"], [definition], condition="AT_DEPOT",
                  renderStage="ALWAYS_ON_LIGHT")]
    return {"modelYOffset": 1, "parts": parts}


def blind_end_properties(end):
    """A blind end: the same shell with no cab features and no lamps."""
    return {
        "modelYOffset": 1,
        "parts": [part(["end_blind_exterior"], ["end%d" % end],
                       renderStage="EXTERIOR")],
    }


def display_end_properties(end):
    """The front route roundel on ONE cab end.

    Its own properties file, stacked in the index only for ends that have a cab
    — MTR's own idiom (r179 does exactly this for its head cars) and the only
    way to give a cab a sign when the shell is an .obj. This file contains no
    NORMAL part, so the doors .bbmodel it binds against draws nothing twice.
    """
    definition = "bbEnd%d" % end
    parts = []
    for group, kind, options, colour, padding, default in FRONT_DISPLAYS:
        parts.append(display_part(group, [definition], kind, options, colour,
                                  padding, default))
    return {"modelYOffset": 1, "parts": parts}


def position_definitions():
    """Where each group repeats. See the module docstring for the sign rules."""

    def z(values):
        return [{"z": v} for v in values]

    return {"positionDefinitions": [
        # --- the .obj shell -------------------------------------------
        # ONE unflipped + ONE flipped: the panel is not 180-symmetric in its
        # own content (rollsign on one side, plain window on the other), so
        # each placement is a DIFFERENT bay rather than a mirror of the same
        # one. A both-lists symmetric definition would draw each twice.
        {"name": "panel", "positions": z([PANEL_Z]),
         "positionsFlipped": z([PANEL_Z])},
        # The door bay IS 180-symmetric in itself, so it needs one list only.
        {"name": "door", "positions": z(DOORS)},
        {"name": "end1", "positions": z([END_Z])},
        {"name": "end2", "positionsFlipped": z([END_Z])},

        # --- the doors .bbmodel ----------------------------------------
        # Same bays, but every flipped entry carries the NEGATED z: the two
        # model formats compose a flip in opposite orders. See the docstring.
        #
        # `bbDoor` is in BOTH lists at all three openings, which is what puts a
        # leaf and a doorway box on each side of each opening — six of each.
        {"name": "bbDoor", "positions": z(DOORS), "positionsFlipped": z(DOORS)},
        {"name": "bbPanel", "positions": z([PANEL_Z]),
         "positionsFlipped": z([-PANEL_Z])},
        {"name": "bbEnd1", "positions": z([END_Z])},
        {"name": "bbEnd2", "positionsFlipped": z([-END_Z])},

        # The FLOOR plates are full width, so they must NOT ride a definition
        # that also carries a flipped entry for the other side — that would
        # stack two plates in the same place. They get their own, positions
        # only, except the end plate: it is z-asymmetric (the nose and the
        # storm-door recess eat its outer end) so it needs the flip to be
        # turned round at the other end of the car.
        {"name": "bbFloorPanel", "positions": z([PANEL_Z, -PANEL_Z])},
        {"name": "bbFloorDoor", "positions": z(DOORS)},
        {"name": "bbFloorEnd", "positions": z([END_Z]),
         "positionsFlipped": z([-END_Z])},
    ]}


# ------------------------------------------------------------------ index

_DISPLAY = "r62_display_%d.json"

# (id suffix, label, end 1 is a cab, end 2 is a cab)
KINDS = (
    ("", "", True, True),
    ("_middle", " (Blind End)", False, False),
)


def end_files(cab1, cab2):
    return ["r62_%s_1.json" % ("cab" if cab1 else "blind"),
            "r62_%s_2.json" % ("cab" if cab2 else "blind")]


def display_files(cab1, cab2):
    """Display properties for the ends that have a cab. A blind end has no cab
    front to put a route sign on — which is the whole point of it."""
    return ([_DISPLAY % 1] if cab1 else []) + ([_DISPLAY % 2] if cab2 else [])


def interior_files(cab1, cab2):
    """The saloon: the two repeating bays, plus one file per end.

    A blind end's saloon is a different ROOM, not a different dressing — no cab
    bulkhead, a second bench, lining all the way to the end wall — so it gets
    its own group and its own file, exactly as the shell's blind end does.
    """
    return ["r62_interior_common.json",
            "r62_interior_%s_1.json" % ("cab" if cab1 else "blind"),
            "r62_interior_%s_2.json" % ("cab" if cab2 else "blind")]


def vehicle(vehicle_id, name, cab1, cab2):
    def model_entry(properties, model=MODEL_ID, texture=TEXTURE_ID):
        return {
            "modelResource": model,
            "textureResource": texture,
            "modelPropertiesResource":
                "station_announcer:properties/vehicle/%s" % properties,
            "positionDefinitionsResource":
                "station_announcer:properties/definition/%s" % DEFINITION,
        }

    bogie = [{
        "modelResource": BOGIE,
        "textureResource": BOGIE_TEXTURE,
        "modelPropertiesResource": BOGIE_PROPERTIES,
        "positionDefinitionsResource": ORIGIN_DEFINITION,
    }]
    return {
        "id": vehicle_id,
        "name": name,
        "color": "A9ADB2",
        "transportMode": "TRAIN",
        "width": 2,
        # The anticlimbers ARE the coupler faces and they interleave on the
        # prototype — the donor's own consist spacing (15.3 m) is SHORTER than
        # the body (15.56 m) for exactly that reason — so no padding.
        "couplingPadding1": 0,
        "couplingPadding2": 0,
        "wikipediaArticle": "R62_(New_York_City_Subway_car)",
        "tags": ["family:r62", "iso_3166:US", "doors:6",
                 "type:%s" % ("cab" if cab1 and cab2 else "trailer")],
        # THREE models per vehicle, all on the same position definitions: the
        # shell (.obj, common + one end file per end), the doors/floors/
        # displays (.bbmodel), and the saloon interior (.obj again, common +
        # one end file per end). The doors model is stacked a SECOND time for a
        # cab end, carrying nothing but the front roundel's DISPLAY parts —
        # MTR's own idiom of one model with several properties files.
        "models": ([model_entry("r62_common.json")]
                   + [model_entry(p) for p in end_files(cab1, cab2)]
                   + [model_entry("r62_doors.json", DOORS_MODEL_ID,
                                  DOORS_TEXTURE_ID)]
                   + [model_entry(p, DOORS_MODEL_ID, DOORS_TEXTURE_ID)
                      for p in display_files(cab1, cab2)]
                   + [model_entry(p, INTERIOR_MODEL_ID, INTERIOR_TEXTURE_ID)
                      for p in interior_files(cab1, cab2)]),
        "bogie1Models": bogie,
        "bogie2Models": bogie,
        # ⭐ NO GANGWAY. An R62 does not have one: cars couple through open end
        # platforms with folding safety gates, which is what `hasBarrier` draws.
        # MTR's own r179 — the other NYCT car in the game — is set the same way.
        "hasGangway1": False,
        "hasGangway2": False,
        "hasBarrier1": True,
        "hasBarrier2": True,
        "legacySpeedSoundBaseResource": SOUND_BASE,
        "legacySpeedSoundCount": SOUND_COUNT,
        "legacyDoorSoundBaseResource": SOUND_BASE,
        "legacyDoorCloseSoundTime": DOOR_CLOSE_TIME,
        "length": float(L.BLOCKS),
        "bogie1Position": -L.bogie_position(),
        "bogie2Position": L.bogie_position(),
    }


def vehicles():
    """Every R62 vehicle, for tools/gen_vehicle_index.py to compose.

    This is the provider the shared index reads. Keep it free of side effects —
    the composer calls it without writing anything else.
    """
    return [vehicle("r62%s" % suffix, "NYCT R62%s" % label, cab1, cab2)
            for suffix, label, cab1, cab2 in KINDS]


# ------------------------------------------------------------------- main

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def main():
    props = os.path.join(OUR_NS, "properties/vehicle")
    write_json(os.path.join(props, "r62_common.json"), common_properties())
    write_json(os.path.join(props, "r62_doors.json"), doors_properties())
    write_json(os.path.join(props, "r62_interior_common.json"),
               interior_common_properties())
    for end in (1, 2):
        write_json(os.path.join(props, "r62_cab_%d.json" % end),
                   cab_end_properties(end))
        write_json(os.path.join(props, "r62_blind_%d.json" % end),
                   blind_end_properties(end))
        write_json(os.path.join(props, _DISPLAY % end),
                   display_end_properties(end))
        for cab in (True, False):
            write_json(os.path.join(props, "r62_interior_%s_%d.json"
                                    % ("cab" if cab else "blind", end)),
                       interior_end_properties(end, cab))
    write_json(os.path.join(OUR_NS, "properties/definition/" + DEFINITION),
               position_definitions())

    # Imported here, not at module scope: the composer imports THIS module for
    # its `vehicles()` provider, so a top-level import would be circular.
    import gen_vehicle_index
    total = gen_vehicle_index.write()
    print("R62 assets written: %d vehicles (index now holds %d)"
          % (len(vehicles()), total))


if __name__ == "__main__":
    main()
