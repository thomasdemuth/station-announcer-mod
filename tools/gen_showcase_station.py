#!/usr/bin/env python3
"""Generates the Baker Street showcase station as a datapack for the dev world.

Run:  python3 tools/gen_showcase_station.py
Then on the dev server:  /function showcase:build   (and /function showcase:clean
to wipe the old scattered test blocks near spawn).

WHY THIS EXISTS
---------------
Every block family in the mod was designed from its own reference photos and
verified alone. Nobody had ever seen the kit TOGETHER: whether the floor greys
sit right against the subway tile, whether the gate black clashes with the
railing green, whether the pole gauges agree. This station places every family
in a realistic context - a two-track island-platform station with a mezzanine,
fare control, and a street entrance - so one walkthrough judges the whole kit
and exposes whatever a real build turns out to be missing.

It is a FUNCTION, not a structure file, so it is versionable, diffable and
re-runnable after any block change.

LAYOUT (absolute coordinates, X 99..152, Z 99..126, Y 57..75)
-------------------------------------------------------------
  y75  street level on the roof: entrance pit, railings + sign, globe lamps
  y68  mezzanine: tile floors (clean bay west), fare-control line at x=127
       (grille | bars | turnstiles | emergency exit door | grille), fare
       machines, departure boards, PA control box, mosaics
  y61  island platform z107..118: concrete floors in all three slab widths
       (clean bay east), column line, benches, stop markers, holding lights,
       every PIDS, zebra boards, speakers
  y59  track trenches z101..105 and z120..124 (rails left for MTR wiring),
       subway-tiled trench walls with name tablets, alcoves and warning signs

/setblock does NOT call getPlacementState, so every connection property
(left/right/up/down/post, door halves, PIDS halves) is computed HERE, by the
same rules the blocks use. Blocks that recompute in getStateForNeighborUpdate
self-heal anyway; the explicit values cover the last-placed cell of each run.
"""

import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PACK = os.path.join(ROOT, "run/world/datapacks/showcase")
MOD = "station_announcer"

# The station box.
X0, X1 = 100, 151            # interior x span
ZN_TRENCH = (101, 105)       # north track trench
Z_PLAT = (107, 118)          # island platform
ZS_TRENCH = (120, 124)       # south track trench
FARE_X = 127                 # the fare-control line (unpaid west, paid east)

CMD = []


def c(command):
    CMD.append(command)


def fill(x1, y1, z1, x2, y2, z2, block):
    c(f"fill {x1} {y1} {z1} {x2} {y2} {z2} {block}")


def set_(x, y, z, block):
    c(f"setblock {x} {y} {z} {block}")


def merge(x, y, z, nbt):
    c(f"data merge block {x} {y} {z} {nbt}")


def pos_long(x, y, z):
    """BlockPos.asLong - for the speaker->control box links."""
    return ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)


# ---------------------------------------------------------------- shell

def shell():
    fill(99, 57, 99, 152, 75, 126, "minecraft:stone_bricks")
    fill(X0, 59, 100, X1, 74, 125, "minecraft:air")
    # solid base under everything
    fill(X0, 57, 100, X1, 58, 125, "minecraft:smooth_stone")


def levels():
    # trenches: gravel bed, open to under the mezzanine slab
    for z0, z1 in (ZN_TRENCH, ZS_TRENCH):
        fill(X0, 58, z0, X1, 58, z1, "minecraft:gravel")
        fill(X0, 59, z0, X1, 65, z1, "minecraft:air")
    # platform slab: stone core, floor layer goes on top later
    fill(X0, 59, Z_PLAT[0], X1, 59, Z_PLAT[1], "minecraft:smooth_stone")
    # platform edge walls, capped with the painted no-clearance band
    for z in (Z_PLAT[0] - 1, Z_PLAT[1] + 1):
        fill(X0, 59, z, X1, 59, z, "minecraft:smooth_stone")
        fill(X0, 60, z, X1, 60, z, f"{MOD}:no_clearance_stripe")
    # mezzanine slab + ceiling + roof
    fill(X0, 66, 100, X1, 66, 125, "minecraft:smooth_stone")
    fill(X0, 73, 100, X1, 73, 125, "minecraft:smooth_stone")
    fill(X0, 74, 100, X1, 74, 125, "minecraft:stone_bricks")


# ---------------------------------------------------------------- floors

def floors():
    z0, z1 = Z_PLAT
    # all three slab widths side by side, then the clean bay
    fill(X0, 60, z0, 105, 60, z1, f"{MOD}:platform_concrete_floor_2")
    fill(106, 60, z0, 139, 60, z1, f"{MOD}:platform_concrete_floor_3")
    fill(140, 60, z0, 145, 60, z1, f"{MOD}:platform_concrete_floor_4")
    fill(146, 60, z0, X1, 60, z1, f"{MOD}:platform_concrete_floor_3_clean")
    # mezzanine: dirty tile, with a fresh bay around the street entrance
    fill(X0, 67, 101, X1, 67, 124, f"{MOD}:platform_tile_floor")
    fill(101, 67, 105, 112, 67, 113, f"{MOD}:platform_tile_floor_clean")


# ------------------------------------------------------- trench walls

def trench_walls():
    """Subway tile on the outer walls the passengers stare at across the track."""
    for z, facing in ((100, "south"), (125, "north")):
        fill(X0, 59, z, X1, 60, z, f"{MOD}:subway_tile_dark")
        fill(X0, 61, z, X1, 63, z, f"{MOD}:subway_tile_wall")
        fill(X0, 64, z, X1, 64, z, f"{MOD}:subway_tile_band")
        fill(X0, 65, z, X1, 65, z, f"{MOD}:subway_tile_wall")
        # a freshly retiled bay for comparison
        fill(144, 61, z, 148, 63, z, f"{MOD}:subway_tile_wall_clean")
        set_(144, 65, z, f"{MOD}:subway_tile_wall_clean")
    # walk-in alcoves and the station name tablets on the north wall
    for x in (115, 116):
        for y in (61, 62):
            set_(x, y, 100, f"{MOD}:subway_tile_alcove[facing=south]")
    set_(122, 63, 100, f"{MOD}:subway_name_tablet[row=1,facing=south]")
    merge(122, 63, 100, '{Text:"BAKER"}')
    set_(123, 63, 100, f"{MOD}:subway_name_tablet[row=1,facing=south]")
    merge(123, 63, 100, '{Text:"ST"}')
    # do-not-cross plates on the trench end walls
    for z in (103, 122):
        set_(X1, 62, z, f"{MOD}:track_warning_sign_wall[facing=west]")
        set_(X0, 62, z, f"{MOD}:track_warning_sign_wall[facing=east]")
    # mosaic name band on the south wall, drawn as one merged run
    for x in range(108, 116):
        set_(x, 63, 124, f"{MOD}:station_name_mosaic[facing=north]")


# ---------------------------------------------------------------- columns

def columns():
    kinds = {102: "column_iron", 110: "column_iron_named_station",
             118: "column_iron_named", 126: "column_iron_named_station",
             134: "column_iron_named", 142: "column_iron_named_station",
             150: "column_iron"}
    for x in range(102, 151, 4):
        kind = kinds.get(x, "column_iron_station")
        for y in range(61, 66):
            down = "true" if y > 61 else "false"
            up = "true" if y < 65 else "false"
            set_(x, y, 112, f"{MOD}:{kind}[facing=north,up={up},down={down}]")


# ------------------------------------------------------ platform kit

def run_lr(xs, place):
    """Places a left/right merging run along +X, computing the flags.

    For a NORTH-facing block RIGHT is rotateYClockwise(north) = east = +X,
    so the +X neighbour is the RIGHT one. South-facing runs mirror; the
    callers here all face north or south and pass their own mapping.
    """
    for i, x in enumerate(xs):
        has_minus = i > 0
        has_plus = i < len(xs) - 1
        place(x, has_minus, has_plus)


def platform_kit():
    # benches back to back between two columns
    def bench(facing, z, flip):
        def place(x, minus, plus):
            left, right = (plus, minus) if flip else (minus, plus)
            set_(x, 61, z, f"{MOD}:bench[facing={facing},"
                           f"left={str(left).lower()},right={str(right).lower()}]")
        return place
    run_lr(range(119, 122), bench("north", 111, flip=False))
    run_lr(range(119, 122), bench("south", 114, flip=True))

    # hanging zebra board over the north platform edge (ends grow the poles)
    def zebra(x, minus, plus):
        set_(x, 65, 107, f"{MOD}:zebra_board_hanging[facing=north,"
                         f"left={str(minus).lower()},right={str(plus).lower()}]")
    run_lr(range(141, 144), zebra)
    # wall zebra on the trench wall
    def zebra_wall(x, minus, plus):
        set_(x, 64, 101, f"{MOD}:zebra_board_wall[facing=south,"
                         f"left={str(plus).lower()},right={str(minus).lower()}]")
    run_lr(range(134, 137), zebra_wall)

    # stop markers: ceiling plate, blade on its pole, flush + bracket on a column
    set_(128, 65, 107, f"{MOD}:stop_marker[mount=ceiling,standoff=false,blade=false,facing=north]")
    set_(124, 65, 107, f"{MOD}:stop_marker_pole[axis=y]")
    set_(124, 64, 107, f"{MOD}:stop_marker[mount=ceiling,standoff=false,blade=true,facing=north]")
    set_(118, 63, 111, f"{MOD}:stop_marker[mount=wall,standoff=false,blade=false,facing=north]")
    set_(122, 63, 111, f"{MOD}:stop_marker[mount=wall,standoff=true,blade=false,facing=north]")

    # holding lights hung at both platform ends
    for x, kind in ((103, "holding_light_yellow"), (148, "holding_light_green")):
        set_(x, 65, 107, f"{MOD}:holding_light_pole")
        set_(x, 64, 107, f"{MOD}:{kind}[facing=north]")

    # platform barriers fencing the mezzanine stair openings
    for z in (108, 112, 113, 117):
        facing = "south" if z in (108, 113) else "north"
        fill(133, 68, z, 139, 68, z, f"{MOD}:platform_barrier[facing={facing}]")
    for z0, z1 in ((109, 111), (114, 116)):
        fill(132, 68, z0, 132, 68, z1, f"{MOD}:platform_barrier[facing=east]")


# ------------------------------------------------------------- displays

def displays():
    # NYC wall PIDS across the north track, like the real mounting
    for x, block in ((109, "pids_nyc_wall_1"), (112, "pids_nyc_wall_2")):
        set_(x, 62, 101, f"{MOD}:{block}[facing=south,half=lower]")
        set_(x, 63, 101, f"{MOD}:{block}[facing=south,half=upper]")
    set_(145, 62, 101, f"{MOD}:railroad_pids_wall[facing=south,half=lower]")
    set_(145, 63, 101, f"{MOD}:railroad_pids_wall[facing=south,half=upper]")

    # standing units on the platform
    for x, block in ((105, "railroad_pids_standing"), (116, "pids_nyc_standing_1")):
        set_(x, 61, 115, f"{MOD}:{block}[facing=east,half=lower]")
        set_(x, 62, 115, f"{MOD}:{block}[facing=east,half=upper]")

    # hanging NYC pair + mini over the platform, on their drop poles
    for xl, block in ((132, "pids_nyc_hanging"), (137, "pids_nyc_hanging_mini")):
        set_(xl, 64, 112, f"{MOD}:{block}[facing=north,side=left]")
        set_(xl + 1, 64, 112, f"{MOD}:{block}[facing=north,side=right]")
        set_(xl, 65, 112, f"{MOD}:pids_pole")
        set_(xl + 1, 65, 112, f"{MOD}:pids_pole")

    # mezzanine: NYC pair on the north wall
    for x, block in ((105, "pids_nyc_wall_1"), (108, "pids_nyc_wall_2")):
        set_(x, 68, 101, f"{MOD}:{block}[facing=south,half=lower]")
        set_(x, 69, 101, f"{MOD}:{block}[facing=south,half=upper]")

    # the small railroad departure board, titled
    set_(143, 68, 101, f"{MOD}:railroad_departure_wall[facing=south,half=lower]")
    set_(143, 69, 101, f"{MOD}:railroad_departure_wall[facing=south,half=upper]")
    merge(143, 68, 101, '{Title:"Trains to Baker City"}')

    # the big concourse board: a 4x2 merged rectangle on the south wall
    for x in range(132, 136):
        for y in (69, 70):
            set_(x, y, 124, f"{MOD}:departure_board_wall[facing=north]")
            merge(x, y, 124, '{Title:"Baker Street Departures"}')

    # hanging big board (3 wide) and the railroad hanging clock, on poles
    for x in range(138, 141):
        set_(x, 70, 112, f"{MOD}:departure_board_hanging[facing=north]")
        merge(x, 70, 112, '{Title:"Departures"}')
    set_(139, 71, 112, f"{MOD}:pids_pole")
    set_(139, 72, 112, f"{MOD}:pids_pole")
    set_(146, 70, 112, f"{MOD}:railroad_pids_hanging[facing=north,side=left]")
    set_(147, 70, 112, f"{MOD}:railroad_pids_hanging[facing=north,side=right]")
    set_(147, 71, 112, f"{MOD}:pids_pole")
    set_(147, 72, 112, f"{MOD}:pids_pole")

    # mezzanine mosaics
    for x in range(112, 120):
        set_(x, 70, 101, f"{MOD}:station_name_mosaic[facing=south]")


# ------------------------------------------------------------ fare line

def fare_line():
    """The full fare array at x=127, facing west (unpaid side is west)."""
    x = FARE_X

    def gate_members():
        # (z, block, postEvery) for the two GateSection runs; turnstiles and
        # the cap break the runs, exactly as in a real build
        run = {}
        for z in range(101, 108):
            run[z] = ("gate_grille", 3)
        run[108] = ("gate_scroll", 1)
        for z in (114, 115, 117, 118, 119):
            run[z] = ("gate_scroll", 1)
        run[116] = ("door", 0)
        for z in range(120, 125):
            run[z] = ("gate_grille", 3)
        return run

    members = gate_members()

    def joined(z):
        return z in members

    # HIGH-Z first: the post-spacing recompute walks toward +Z, so placing a
    # block re-derives its already-placed +Z neighbour. Building in reverse
    # means every recompute sees a complete run and agrees with the explicit
    # values; building ascending let each placement stamp post=false onto the
    # cell before it.
    for z, (kind, post_every) in sorted(members.items(), reverse=True):
        # facing west: RIGHT is rotateYClockwise(west) = north = -Z
        right = str(joined(z - 1)).lower()
        left = str(joined(z + 1)).lower()
        if kind == "door":
            set_(x, 68, z, f"{MOD}:emergency_exit_door[facing=west,half=lower,"
                           f"left={left},right={right}]")
            set_(x, 69, z, f"{MOD}:emergency_exit_door[facing=west,half=upper,"
                           f"left={left},right={right}]")
            continue
        # distance to the LEFT end of the run (+Z), the post-spacing rule
        dist = 0
        scan = z
        while joined(scan + 1):
            scan += 1
            dist += 1
        post = str(dist % post_every == 0).lower() if post_every else "true"
        for y, up, down in ((68, "true", "false"), (69, "false", "true")):
            set_(x, y, z, f"{MOD}:{kind}[facing=west,up={up},down={down},"
                          f"left={left},right={right},post={post}]")

    # the turnstile bank: four lanes and the end cap
    for z in range(109, 113):
        right = str(z > 109).lower()   # RIGHT is -Z; lane 109's -Z neighbour is the bar wall
        for y, half in ((68, "lower"), (69, "upper")):
            set_(x, y, z, f"{MOD}:turnstile[facing=west,half={half},right={right}]")
    for y, half in ((68, "lower"), (69, "upper")):
        set_(x, y, 113, f"{MOD}:turnstile_cap[facing=west,half={half},right=true]")

    # fare machines against the unpaid west wall
    for z in (108, 109, 110):
        set_(101, 68, z, f"{MOD}:fare_machine[facing=east,half=lower]")
        set_(101, 69, z, f"{MOD}:fare_machine[facing=east,half=upper]")

    # a one-high bar fence on the paid side carrying the gate warning sign
    for gx in range(140, 147):
        right = str(gx > 140).lower()
        left = str(gx < 146).lower()
        if gx == 143:
            set_(gx, 68, 120, f"{MOD}:track_warning_sign_gate[facing=north]")
            continue
        set_(gx, 68, 120, f"{MOD}:gate_scroll[facing=north,up=false,down=false,"
                          f"left={left},right={right},post=true]")


# ----------------------------------------------------------------- stairs

def stairs():
    """Two platform stairwells up to the paid mezzanine, one street stair."""
    for z0, z1 in ((109, 111), (114, 116)):
        fill(133, 66, z0, 139, 67, z1, "minecraft:air")
        for i in range(7):
            xx = 131 + i
            if i > 0:
                fill(xx, 61, z0, xx, 60 + i, z1, "minecraft:smooth_stone")
            fill(xx, 61 + i, z0, xx, 61 + i, z1,
                 "minecraft:polished_andesite_stairs[facing=east]")
    # street stair: mezzanine up to the roof, rising west
    fill(104, 73, 107, 108, 73, 109, "minecraft:air")
    fill(104, 74, 107, 107, 74, 109, "minecraft:air")
    for i in range(7):
        xx = 110 - i
        if i > 0:
            fill(xx, 68, 107, xx, 67 + i, 109, "minecraft:smooth_stone")
        fill(xx, 68 + i, 107, xx, 68 + i, 109,
             "minecraft:polished_andesite_stairs[facing=west]")


def street_entrance():
    """Railing ring, station sign and globe lamps around the stair pit."""
    def railing(xx, z, east, west, post, sign=False):
        block = "entrance_railing_sign" if sign else "entrance_railing"
        set_(xx, 75, z, f"{MOD}:{block}[north=false,south=false,"
             f"east={str(east).lower()},west={str(west).lower()},post={str(post).lower()}]")
    for z in (106, 110):
        for xx in range(103, 109):
            railing(xx, z, east=xx < 108, west=xx > 103,
                    post=xx in (103, 108), sign=(z == 110 and xx == 105))
    # globe lamps grow out of the corner posts: green = entrance, red = exit side
    set_(103, 76, 106, f"{MOD}:globe_lamp_green")
    set_(108, 76, 106, f"{MOD}:globe_lamp_pole")
    set_(108, 77, 106, f"{MOD}:globe_lamp_green")
    set_(103, 76, 110, f"{MOD}:globe_lamp_red")
    set_(108, 76, 110, f"{MOD}:globe_lamp_red")


# --------------------------------------------------------------------- PA

def pa_system():
    box = (150, 68, 102)
    set_(*box, f"{MOD}:pa_control_box")
    merge(*box, '{Text:"Welcome to Baker Street station.||Please stand clear of the '
                'closing doors.||This station is a showcase of every Station Announcer block.",'
                'AutoMinSeconds:45,AutoMaxSeconds:90}')
    set_(150, 68, 104, f"{MOD}:announcer_block")
    merge(150, 68, 104, '{Text:"Attention passengers: this is the Baker Street showcase station."}')
    link = f"{pos_long(*box)}L"
    for x, y, z in ((106, 65, 110), (122, 65, 113), (142, 65, 110),
                    (110, 72, 110), (125, 72, 118), (145, 72, 105)):
        set_(x, y, z, f"{MOD}:speaker")
        merge(x, y, z, "{ControlBox:" + link + "}")
    # station ambience, tucked into the slabs
    set_(120, 66, 112, f"{MOD}:ambience_block")
    set_(125, 73, 108, f"{MOD}:ambience_block")


# ------------------------------------------------------------------ main

def build():
    shell()
    levels()
    floors()
    trench_walls()
    columns()
    platform_kit()
    displays()
    fare_line()
    stairs()
    street_entrance()
    pa_system()
    c('tellraw @a {"text":"Baker Street showcase built. Mezzanine: /tp 125 69 112","color":"green"}')


def write_pack():
    fn_dir = os.path.join(PACK, "data/showcase/functions")
    os.makedirs(fn_dir, exist_ok=True)
    with open(os.path.join(PACK, "pack.mcmeta"), "w") as fh:
        fh.write('{"pack": {"pack_format": 26, "description": "Baker Street showcase station"}}\n')
    build()
    with open(os.path.join(fn_dir, "build.mcfunction"), "w") as fh:
        fh.write("\n".join(CMD) + "\n")
    # the old scattered test debris around spawn, from months of headless tests
    with open(os.path.join(fn_dir, "clean.mcfunction"), "w") as fh:
        fh.write("fill -16 99 -16 16 103 16 minecraft:air\n"
                 "kill @e[type=item]\n"
                 "kill @e[type=armor_stand]\n")
    print(f"{len(CMD)} commands -> {fn_dir}/build.mcfunction")


if __name__ == "__main__":
    write_pack()
