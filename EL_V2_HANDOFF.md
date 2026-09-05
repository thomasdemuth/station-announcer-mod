# EL KIT v2 — HANDOFF (2026-09-04, mid FARE CONTROL / STAIRS section)

Read this first, then `EL_STATION_PLAN.md` (v2 section at the top: platform rounds 1-8,
then "FARE CONTROL / STAIRS" rounds 1-6 with every decision), then the generators
(`tools/gen_el2_assets.py` = platform kit + roof corners, `tools/gen_el2_stairs.py` =
stair family, landing roof, steel landing, entrance sign, icons). Every model/texture/
blockstate/icon is GENERATED — never hand-edit the output; `python3 tools/gen_el2_assets.py`
regenerates everything and runs `verify()` (blockstate keys vs property tables, model
refs, uv ranges).

## 1. Where things stand

- **Play profiles have 2.4.52** (deployed via `tools/deploy_jar.sh`). The source tree is
  AHEAD of it: rounds 5-6 (icons, hitboxes, corner posts, doorway course, stairwell-rim
  placement, post stubs, stacked-course fixes) are compiled into the DEV world only.
  **First job: bump `mod_version` to 2.4.53, `./gradlew build`, `tools/deploy_jar.sh`**
  (refuses while Thomas's real game is open — ask him to close it).
- **Working tree is uncommitted** (Thomas never asked for a commit; commit only when he does).
- **Thomas is testing live in the dev world** (see §6). He sends screenshots + one-line
  asks; the loop is: fix → if model/texture only, copy into `build/resources/main/...`
  and tell him **F3+T**; if Java, wait for his word "restart", then `rig_restart.sh`.
  NEVER run Gradle while the dev world is up (kills both JVMs = his session).
- **Last Java batch is NOT yet compiled** (rig was up): stacked courses inherit
  FACING/SIDE + LEVEL from the course below; `ElStairSideItem` FAILs when the outside
  column is blocked; `ElWallDoorwayBlock`; `EdgeRunItem` stairwell-rim placement. Expect
  to compile these at the next restart — if `rig_restart.sh` prints SERVER FAILED, read
  its server.log for the javac error.

## 2. Thomas's rules (do not re-ask)

One style: Dual-Contracts green steel, red standing-seam roofs, cream board panels with
a wired-glass band, Warren-truss friezes. Green first, station tint second, silver only
if asked. Platforms are >= 3 blocks tall (three wall courses). Stairs: reuse
`subway_stairs`; sides are open (railing + posts-only course) on street stairs like Van
Siclen Av, SOLID where a stair cuts through the platform ("not see through"); the roof
flattens at the foot into a hood carrying the entrance sign (bullet + station name,
hanging under the hood with a 2 px drop, two straps per run). Windows must be framed on
all four sides. Posts must visibly reach the roof; roof/roof and roof/landing joins must
be seamless; hitboxes must not block walking. Cream course = cream rails AND posts.
Landings use the thin steel deck, not concrete. Every block gets placed and screenshotted
on the rig before he sees it; stop and ping him at any wall. Delete deprecated v1 `el_*`
ids only at the END of a section, after asking (46 of them are still registered, hidden).

## 3. The v2 blocks (all rig-verified unless marked)

Platform area (rounds 1-8, see the plan): `platform_edge` (opens MTR doors via
`PlatformHelper`), `el_roof` (gable, AXIS/SIDE/LEVEL, now with `JOIN`
none/hip_pos/hip_neg/valley_pos/valley_neg/peak for L-shaped canopies of EQUAL width —
split the corner square along its diagonal; hips/peaks are strip-built true slopes with
a 2 px sawtooth), `el_post[_named]`, `el_wall`/`_glass`/`_sign`/`_cream`/`_green`/
`_doorway` (EdgeRunBlock, cell OUTSIDE the platform edge, panel on the near side),
`el_railing[_sign]`, `el_platform_lamp[_head]`, `el_roof_light`, `el_sign`.

Stair family (`block/ElStairSideBlock`, `ElStairRoofBlock`, `ElLandingRoofBlock`,
`item/ElStairSideItem`, `mtr/ElEntranceSignBlock`):

| id | what |
|---|---|
| `el_stair_railing` / `el_stair_wall` / `el_stair_wall_glass` / `el_stair_open` | sloped courses in the cell BESIDE each stair block (SIDE = which side the stair is on, looking uphill); chain one up + one forward, stack at a 16 px pitch (v 12..20 in the slope frame); BOTTOM adds stringer + kick; TOP runs the post to y 32; LEVEL (top of flight) levels the rail at 16 px over the floor with an end post + newel; END_UP/END_DOWN cut the 45° members. `open` = posts only |
| `el_stair_roof` | red sloped roof, FACING = ascent, EDGE_LEFT/RIGHT hang the frieze (solid-backed lattice, v 12..18) + eave + a post stub; UP/DOWN ∈ run/end/landing; collision steps along the deck underside |
| `el_landing_roof` | flat roof, deck y 20..23.2 = the stair roof's edge height; joins a stair roof at the foot (same row, behind it) and at the top (one cell up); EDGE_* per side |
| `el_stair_landing` | 1 px diamond-plate deck flush with the top tread, joists below, shape y 11..16 |
| `el_entrance_sign` | lit black box under the hood, LEFT/RIGHT merge, brush → `RailingSignScreen` (name + per-face bullets), painted by `StationDecorRenderer.paintEntranceSign` |

**Recipes Thomas uses:** street stair = `subway_stairs` flight + railing course + open
course (+ open) + `el_stair_roof` row (edge cells over the courses), `el_landing_roof`
row behind the foot cell = the hood, sign cells under the hood's front edge. Turn-back =
two flights + `el_stair_landing` floor + landing roof one cell above the lower flight's
top roof. Stair house on the platform = wall courses stood on the well rim (click the
rim floor's far third; the item puts them in the rim cell facing the well), doorway
course, `el_roof` on top.

## 4. Hard-won rules (new ones first)

- **Slope frame**: rescaled +45° about x through (8,8,8) maps (v, z_a) → y = v − z_a + 8,
  z = v + z_a − 8; at fixed world z a member at v sits at y = 2v − z and over z ∈ [v−8,
  v+8] — high members shift DOWNHILL, so run ends need cuts (`member(up=, down=)`) and
  world-vertical pickets belong to the uphill cell. Authored coords must stay in −16..32:
  that is why the stair rail is 16 px over the nosings (a 20 px level-off would exceed 32).
- **`ambientocclusion:false` samples light per face direction** — a post beside a solid
  floor went black. Use default AO for anything touching floors/walls; AO off only for
  roof slabs.
- **Rig cameras: spectator mode** (`cam.sh`), else the player falls before the shot; stay
  inside the dug pit (natural terrain there is at y ~106).
- Atlas bleed: every face uv inside 0..16 (`verify()` enforces). Elements may span
  −16..32; VoxelShapes may exceed 16. Sodium: blockstates only, never the renderer API.
- Roof width changes along a ridge are closed by truss panels; hips/valleys need EQUAL
  widths. A perpendicular roof cell "contaminates" the crosswise count — profiles are
  copied along the ridge from the nearest clean cell.
- Item models: flat `minecraft:item/generated` sprites (3D block models filled half the
  screen in hand). Icons live in `icon_assets()`.

## 5. Open items / what Thomas may ask next

1. Deploy 2.4.53 (see §1) and confirm the not-yet-compiled batch builds.
2. Stair house in-game pass: rim-wall placement feel, doorway, roof on the house; the
   sides of a stair cutting through the slab should be solid (`el_stair_wall`).
3. Mezzanine windows in cream board (a cream course with a sash-window pair) — offered,
   not built. Mezzanine walls otherwise = the wall family.
4. The stair rail (16 px) meets a platform railing (20 px) 4 px low at landings.
5. Different-width roofs at a corner, glass-course corner returns, four-way wall cross.
6. Then the STRUCTURE section (columns, girders, mezzanine under the tracks), and
   deleting the 46 v1 ids.

## 6. The dev world / rig (scratchpad of this session; recreate if gone)

Scratchpad: `/private/tmp/claude-501/-Users-thomasdemuth-Documents-Coding-Projects-Station-Announcer-Mod/<session>/scratchpad/`
with `rig_restart.sh` (kills JVMs, `./gradlew runServer` bg, `runClient
--quickPlayMultiplayer localhost:25565 --username Rig`), `shot.sh scene.txt [settle]`
(copies to `run/commands.txt`, one chat command per 25 ticks, waits 1.3 s/line, touches
`run/screenshot.flag`, prints the PNG path), `cam.sh name cx cy cz tx ty tz` (spectator
+ look-at), `lookat.py`. Scenes: `st4.txt` (scissor stair + landing, x 334-343 z 301-315),
`st6.txt` (Van Siclen entrance, x 319-322 z 307-314), `st5.txt` (L roofs x 300-312 z
300-318, wall test rows). `Rig` is opped (`run/ops.json`). Thomas plays IN this client
window; the world persists in `run/world`. Command errors show in chat and can leave the
chat panel open over screenshots — keep scene files free of stale property names.
Model/texture-only changes: regenerate, copy the changed files into
`build/resources/main/assets/station_announcer/...`, tell Thomas F3+T. Java changes: ask
for "restart".
