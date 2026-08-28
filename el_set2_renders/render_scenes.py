#!/usr/bin/env python3
"""Builds the el ITEM-SET-2 (roofs & canopies) preview scenes.

It does NOT hand-pick models: it reads the generated blockstate JSON and
evaluates the multipart selectors against the connection state each cell
would really have in game, so a wrong `when` shows up in the render.
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
import render_blockmodel as R

ASSETS = os.path.join(HERE, "..", "src/main/resources/assets/station_announcer")
BS = os.path.join(ASSETS, "blockstates")

FAMILY = {"el_canopy_flat": "flat", "el_canopy_flat_silver": "flat",
          "el_canopy_gable": "gable"}


def matches(when, state):
    if "OR" in when:
        return any(matches(w, state) for w in when["OR"])
    for k, v in when.items():
        if state.get(k) not in v.split("|"):
            return False
    return True


def parts_for(block, state):
    data = json.load(open(os.path.join(BS, block + ".json")))
    out = []
    if "variants" in data:
        for key, entry in data["variants"].items():
            s = dict(p.split("=") for p in key.split(",")) if key else {}
            if all(state.get(k) == v for k, v in s.items()):
                out.append((entry["model"].split("/")[-1], entry.get("y", 0) // 90))
        return out
    for part in data["multipart"]:
        if matches(part.get("when", {}), state):
            e = part["apply"]
            out.append((e["model"].split("/")[-1], e.get("y", 0) // 90))
    return out


def canopy_scene(cells, axis="x"):
    """cells: {(cx, cy, cz): block_id}. Returns render entries."""
    entries = []
    for (cx, cy, cz), block in cells.items():
        fam = FAMILY[block]
        st = {"axis": axis}
        for side, (dx, dz) in (("north", (0, -1)), ("south", (0, 1)),
                               ("east", (1, 0)), ("west", (-1, 0))):
            nb = cells.get((cx + dx, cy, cz + dz))
            st[side] = "true" if nb and FAMILY.get(nb) == fam else "false"
        for mdl, k in parts_for(block, st):
            entries.append((mdl, (cx * 16, cy * 16, cz * 16), k))
    return entries


def posts(at, height=2, block="el_canopy_post"):
    """A stack of canopy posts; the top block grows the brackets."""
    out = []
    cx, cy, cz = at
    for i in range(height):
        st = {"facing": "north", "up": "true" if i < height - 1 else "false",
              "down": "true" if i else "false"}
        for mdl, k in parts_for(block, st):
            out.append((mdl, (cx * 16, (cy + i) * 16, cz * 16), k))
    return out


def slope_run(n, block="el_stair_canopy", facing="north", start=(0, 0, 0)):
    out = []
    for i in range(n):
        for mdl, k in parts_for(block, {"facing": facing}):
            out.append((mdl, ((start[0]) * 16, (start[1] + i) * 16,
                              (start[2] - i) * 16), k))
    return out


def main():
    out = HERE
    # 1. flat canopy field 3 wide x 5 long on post rows
    cells = {(x, 1, z): "el_canopy_flat" for x in range(5) for z in range(3)}
    scene = canopy_scene(cells, axis="x")
    for x in (0, 2, 4):
        for z in (0, 2):
            scene += posts((x, -1, z), 2)
    R.render(scene, os.path.join(out, "flat_field_3x5.png"), yaw=35, pitch=25)
    R.render(scene, os.path.join(out, "flat_field_3x5_under.png"), yaw=210, pitch=-18)

    # 2. gable, one row (the classic single-row canopy)
    cells = {(x, 0, 1): "el_canopy_gable" for x in range(4)}
    R.render(canopy_scene(cells), os.path.join(out, "gable_1wide.png"), yaw=32, pitch=20)

    # 3. gable, 2 rows
    cells = {(x, 0, z): "el_canopy_gable" for x in range(4) for z in range(2)}
    R.render(canopy_scene(cells), os.path.join(out, "gable_2wide.png"), yaw=32, pitch=20)

    # 4. gable, 3 rows over posts — the money shot
    cells = {(x, 1, z): "el_canopy_gable" for x in range(5) for z in range(3)}
    scene = canopy_scene(cells)
    for x in (0, 2, 4):
        for z in (0, 2):
            scene += posts((x, -1, z), 2)
    R.render(scene, os.path.join(out, "gable_3wide.png"), yaw=32, pitch=22)
    R.render(scene, os.path.join(out, "gable_3wide_end.png"), yaw=88, pitch=6)

    # 4b. the same 3-wide roof placed on the OTHER axis (ridge along z)
    cells = {(x, 0, z): "el_canopy_gable" for x in range(3) for z in range(5)}
    R.render(canopy_scene(cells, axis="z"), os.path.join(out, "gable_3wide_axis_z.png"),
             yaw=32, pitch=22)

    # 5. gable 4 wide (graceful degradation)
    cells = {(x, 0, z): "el_canopy_gable" for x in range(3) for z in range(4)}
    R.render(canopy_scene(cells), os.path.join(out, "gable_4wide.png"), yaw=32, pitch=22)

    # 6. gable beside flat (families must not merge)
    cells = {}
    for x in range(3):
        for z in range(2):
            cells[(x, 0, z)] = "el_canopy_gable"
        for z in (2, 3):
            cells[(x, 0, z)] = "el_canopy_flat"
    R.render(canopy_scene(cells), os.path.join(out, "gable_meets_flat.png"),
             yaw=32, pitch=20)

    # 7. stair canopy run
    R.render(slope_run(4), os.path.join(out, "stair_canopy_run.png"), yaw=40, pitch=14)
    R.render(slope_run(3) + slope_run(3, start=(1, 0, 0)),
             os.path.join(out, "stair_canopy_under.png"), yaw=200, pitch=-22)

    # 7b. the gable's underside (truss chords + purlins a PIDS can hang from)
    cells = {(x, 0, z): "el_canopy_gable" for x in range(3) for z in range(3)}
    R.render(canopy_scene(cells), os.path.join(out, "gable_3wide_under.png"),
             yaw=200, pitch=-20)

    # 7c. eye-level: 3-wide gable on 3-post stacks (≈3 m to the eave)
    cells = {(x, 2, z): "el_canopy_gable" for x in range(5) for z in range(3)}
    scene = canopy_scene(cells)
    for x in (0, 2, 4):
        for z in (0, 2):
            scene += posts((x, -1, z), 3)
    R.render(scene, os.path.join(out, "gable_3wide_eye.png"), yaw=24, pitch=4)

    # 8. post + bracket close-up
    R.render(posts((0, 0, 0), 2) + [("el_canopy_flat_slab", (0, 32, 0))],
             os.path.join(out, "post_bracket.png"), yaw=30, pitch=10)


if __name__ == "__main__":
    main()
