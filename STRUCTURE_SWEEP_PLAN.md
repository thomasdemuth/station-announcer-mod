# STRUCTURE SWEEP — plan (2026-10-21) — PHASE 1 BUILT (§7), REDESIGNED + DEPLOYED as 3.4.17 (§8)

Build a structure once on straight, flat track; select it; click any track (curved, graded,
multi-track) and the mod bends the whole thing along it. Plus two dedicated builders —
**truss** and **suspension** bridges — that feed the same engine.

§5 holds the open questions. Thomas said "go ahead and build" without answering them, so
**every default in §5 is the decision** (2026-10-21), with the refinement noted at Q3.

---

## 1. Thomas's answers so far (do not re-ask)

| Question | Answer |
|---|---|
| What to sweep | Everything: viaducts/els, tunnels/cuts, trackside detail, stations/platforms — mostly complex bridges and patterns with custom arches |
| Extra builders | A suspension bridge builder and a truss bridge builder |
| On curves | **Bend everything** — the whole template warps along the track |
| On slopes | **Everything follows the rail** |
| Making templates | **Build + select in game** (wand) AND a **paint editor** screen |

---

## 2. The core idea — "inverse mapping"

A **template** is a box of blocks with a marked **axis**: the rail line that runs through it
on the straight test track. Every template cell has three coordinates:

- `s` — distance along the axis (0 … length L)
- `u` — sideways offset from the axis (− left, + right)
- `v` — height above the rail top

To sweep, the engine walks every **world column** in a corridor around the target track,
finds the nearest point on the track (that gives `s` and `u`), and for each height takes
`v = y − railY(s)`. It then copies the template block at `(s mod L, u, v)`.

Why this way round (world → template, not template → world):

- **No holes and no doubles.** Walking world cells guarantees every cell is filled exactly
  once. Pushing template blocks out onto a curve leaves gaps on the outside of the curve and
  stacks blocks on the inside. This is "bend everything": the outer side stretches, the
  inner side squeezes, and arches stay continuous.
- **Slopes come free.** `v` is measured from the rail at that `s`, so the whole structure
  steps up with the grade ("everything follows the rail").
- The bridge creator already does the first half (`BridgeBuilder.sample()`: tangent +
  perpendicular per 0.25-block sample along `Path`). The sweep reuses `Path`, `Sink`,
  companion detection, rail protection and the sliced apply + undo from
  `EarthworksService`.

### Repeat modes

- **Repeat** — tile the template every L blocks (bays, lamp posts, catenary masts).
- **Stretch to fit** — scale `s` slightly so a whole number of repeats fills the selected
  stretch exactly. This makes one custom arch, or N arches, land node to node.
- **Once** — a single copy stretched over the whole selection (one big arch over a river).
- **Ends** (phase 2) — optional START and END sections (abutments, tunnel portals, station
  ends) placed once at each end, with the repeating body between them.

### Blocks that face a direction

Stairs, slabs, doors, logs, fences and signs are turned to match the track's heading,
snapped to the nearest 90° (`BlockState.rotate`). Our el blocks with diagonal axes reuse
`BridgeBuilder.orient()` (`ElRun.fromDirection`). Fences, walls and panes re-connect after
placement through a shape update pass.

---

## 3. Pieces to build

| Piece | What it is |
|---|---|
| `sweep/SweepTemplate` | Palette + packed cells + axis + length L + optional start/end sections + per-cell flags (see §5 Q1, Q3). Gzipped NBT. |
| `sweep/SweepTemplateStore` | Saves and loads templates (where: §5 Q10). |
| **Template Wand** item | Click corner 1, corner 2, then the rail (or a block on the axis line). The start of the box = `s = 0`. Shows the box + axis while held. Name prompt → saved. |
| `sweep/SweepMapper` | Pure engine: `Path` + template + mode → plan of (pos, state). No world access, so it can be unit-tested like `LayoutSolverTest` / `TrackTest`. |
| `sweep/SweepService` | Server: finds rails (BridgeService's way), plans, applies 8000 blocks/tick, one undo per player, "busy" while running. |
| **Structure Sweeper** item | Like the bridge creator: right-click air = FlatUi screen (template list, mode, offsets, preview); click two rail nodes = build. |
| `client/sweep/SweepScreen` | Template picker with 3D thumbnails, mode, lateral/vertical offset, mirror, Before/After preview over generated curved + graded track (CreatorPreview). |
| `client/sweep/SweepPaintScreen` | Paint editor (phase 3). |
| `/sweep` command | `template list|delete|export`, `build <from> <to> <template>`, `line …` (no rail, for rig tests), `undo`. |

---

## 4. Phases

**Phase 1 — capture + sweep (the core).**
Template format, Template Wand, store, `SweepMapper` (Repeat / Stretch to fit / Once,
follow-rail, orientation, rail cells + clearance protected), `SweepService`, Structure
Sweeper item + screen + preview, `/sweep`. Unit test for the mapper (curve continuity, no
holes, arch lands node to node in Stretch mode).
Rig test: the curved 3-track river line the bridge creator used
(`-2460 74 2028 → -2321 74 1892`) plus a graded test line.

**Phase 2 — ends, multi-track, in-world preview.**
Start/end sections (abutments, portals). Multi-track lane matching (§5 Q7). A ghost
preview drawn along the real track in the world before you confirm, not just in the screen.

**Phase 3 — paint editor.**
FlatUi screen editing the same template format. Views: side elevation (`s × v`),
cross-section (`u × v`), top (`s × u`), each with a slice scrubber, plus a live 3D view.
Tools: pencil, fill, line, rectangle, **extrude** (draw a cross-section once and stretch it
along the length), **arch** (pick span + rise + thickness and get a circular, elliptical or
segmental arch), mirror left/right, copy a slice. Block picker = the bridge creator's
material popup.

**Phase 4 — truss bridge builder.**
A generator that outputs a template, so curves and slopes come from the sweep for free.
Types: Warren, Pratt, Howe, K-truss; through / deck / pony; panel length, depth, member
blocks, portal bracing, floor beams. On curves, real trusses are straight panels set at
angles (chorded). Bending would curve the members, so this needs its own option (§5 Q12).

**Phase 5 — suspension bridge builder.**
Parametric, span-aware: two towers (height, style, material), main cables on a parabola
between them, anchorages into the ground at each end, hangers every N blocks, stiffening
deck. Cable-stayed as a variant (fan or harp stays). The template is one whole span, used
with **Once** so towers land where you clicked.

**Phase 6 — polish.**
Grade smoothing (stairs/slabs on the stepped top of a sloped deck). Following rail cant from
the track editor. Import of vanilla structure-block `.nbt` files, then WorldEdit `.schem`.
Template sharing.

---

## 5. Open questions — my default in **bold**

1. **Pillar feet on slopes.** "Everything follows the rail" leaves pillar feet floating at
   the low end and buried at the high end. **Columns touching the template's floor grow down
   to real ground (like the bridge creator's legs), with a per-template switch to turn that
   off.** Alternative: purely rigid, feet float.
2. **Default repeat mode.** **Stretch to fit** (arches always land node to node) vs plain
   Repeat (exact bay length, last bay cut off).
3. **What template air does.** Tunnels need air to dig. **Air in the template clears the
   world; vanilla `structure_void` means "leave the world alone".** It never removes MTR
   blocks, block entities, or our own blocks. REFINED while building: air digs only at rail
   level and above by default ("Above rail"), so a viaduct swept over a hill does not dig a
   trench under itself; "All" and "Never" are the other settings (per tool, not per template).
4. **Overwriting existing blocks.** **Solid template blocks replace only replaceable cells
   and natural terrain** (the earthworks rules), so builds never chew through your
   buildings. Alternative: overwrite everything except protected blocks.
5. **Tight curves.** On the inside of a sharp curve the template squeezes and can fold over
   itself. **Warn in the screen when the template is wider than the curve radius allows**,
   but still build.
6. **Rail tilt (cant).** The track editor can tilt rails. **Ignore tilt in phase 1**; later
   an option to lean the cross-section with it.
7. **Multi-track spacing.** A template captured on 2 tracks 4 apart, swept onto 2 tracks 5
   apart. **Phase 1: sweep around one reference line (a clicked rail, or the centre of the
   selected tracks).** Phase 2: stretch the lanes so each template track lands on a real
   track.
8. **Diagonal track.** At 45° headings, stairs and other facing blocks can only snap to
   90°. **Acceptable**, or should 45° stretches use something special?
9. **Template size limit.** **96 long × 64 wide × 96 tall.**
10. **Where templates live.** **On the server, per world** (`<save>/station-announcer-addon/sweep_templates/`)
    so everyone on the server shares them, plus **Export / Import to a file** in
    `config/station_announcer/` for moving them between worlds.
11. **Suspension bridges on curves.** Real ones are straight. **Straight spans only**
    (refuse, or build towers + cables only on the straight part), or bend anyway?
12. **Trusses on curves.** **Chorded** (straight panels at angles, like real ones) vs bent
    like everything else?
13. **Bridge creator.** **Keep it.** Its presets could also be offered as ready-made
    templates later.
14. **Who can use it.** **Ops + creative** (like `/bridge`)? Or survival too, consuming
    blocks from your inventory?
15. **Paint editor scope.** **Slices + extrude + arch tool + 3D view** (above), or a full
    free 3D voxel editor where you click blocks in the 3D view?

---

## 6. Risks / notes

- Planning a large corridor (500 blocks long × 30 wide × 60 tall ≈ 900k cells) must not
  freeze the server. Map columns once (each column = one nearest-point query), then loop
  heights. Do the world checks on the server thread and apply in slices.
- MTR rails come from `simulator.railIdMap` inside `simulator.run(...)` (BridgeService
  pattern). `MtrSimulators` is null at SERVER_STARTED, so retry.
- Tracks edited with the track editor follow a saved `TrackPath`. Sample through MTR's
  RailMath (which `RailMathMixin` already redirects), never the raw nodes.
- Rig rules from CLAUDE.md apply: scratch rig, no Gradle while another session's rig runs,
  stop the server via RCON.

---

## 7. BUILT — phase 1 (2026-10-21)

Code (all `com.stationannouncer.mtr` unless noted):

| File | What |
|---|---|
| `SweepGeometry` | PURE JAVA: world column → (s, u, tangent) by nearest normal; Repeat / Stretch / Once index maths; quarter turns. Test `tools/sweep_test/SweepGeometryTest.java` (684 checks: straight, diagonal, 90° arcs both ways with grade — no holes, exact s/u; stretch copies node to node). |
| `SweepTemplate` | Canonical frame (s → east, u → south, v = height over rail cell), palette + int cells, gzipped NBT. Capture rotates states into the frame (`SweepMapper.rotateAny` — most of OUR blocks don't implement `rotate`, so facing/axis properties are turned by hand; `ElRun` axes via vectors). MTR blocks → structure_void. Max 96 × 64 × 96. |
| `SweepSpec` | Item NBT `Sweep`: template, mode, flip, side/height offset, phase, legs, leg depth, air mode. |
| `SweepMapper` | The plan: per column/height decides place / dig / keep (earthworks predicates: only air, fluids, plants, snow, natural ground and natural leaves are overwritten; block entities + mtr/station_announcer/msd never), orients states per column (quarter turn of the tangent; el runs follow the exact tangent), legs from a solid template floor down to real ground (skipped over a void). |
| `SweepService` | Path from the FIRST node clicked (MTR's rail has its own direction: `ItemStructureSweeper` copies MTR's `pos` tag into `SweepFrom` before the second click is handled), plan, hand to `EarthworksService.submit`. |
| `EarthworksService` | Applier generalised: `submit(owner UUID, …, fixShapes)`, `undo(owner, world, feedback)`, `isBusy(UUID)`; console builds use `Util.NIL_UUID`. `fixShapes` = after placing, `Block.postProcessState` on every placed block (fences/walls/panes/stairs re-connect; never to air). Progress text is now "Building: …". |
| `SweepTemplateStore` | `<save>/station-announcer-addon/sweep_templates/*.nbt`, export/import via `config/station_announcer/sweep_templates/`. |
| `ItemSweepWand` (`sweep_wand`) | Corner, corner, rail cell (facing = forward) → server opens the save screen (S2C). Sneak-right-click air clears. |
| `ItemStructureSweeper` (`structure_sweeper`) | MTR node tool like the earthworks; right-click air = `SweepScreen`. |
| `Sweep` | Registration (from `MtrPillars`), packets, `/sweep` (op): `template list|save|capture c1 c2 rail facing name|delete|export|import`, `use`, `build/line <from> <to> ["template"] [mode= flip= legs= air= side= height= phase= depth=]` (works from the console too — `BridgeService.findRail` looks the rail up without a player), `undo`. Tools need creative or op. |
| client `SweepClient` | Template list / cells cache (S2C), wand outline (box white, rail cell cyan, forward arrow) via `RenderLayer.getLines`, dev hooks `#sweep-editor [view]`, `#sweep-save`, `#sweep-close`. |
| client `SweepSaveScreen`, `SweepScreen` | FlatUi: save (size, forward N/E/S/W, name); sweeper (template list with delete-confirm, live 3D preview of the real mapper over Straight / Curve / Hill test track, mode, offsets, phase, flip, legs, empty-cells-dig). |
| `tools/gen_sweep_assets.py` | Icons, item models, recipes, lang. |

**Verified 2026-10-21** (whole-tree javac; scratch server = APFS clone of `run/world_baker2`,
ports 25571 / rcon 25581 / MTR web 8899, `max-tick-time=-1` — the watchdog killed the first boot
while MTR loaded files on a machine at load 25; scripts in the session scratchpad `rig/`):
capture of an 8×7×15 test bay (122 blocks); straight sweep (40 blocks, 5 copies, 801 blocks in
6 ms: piers, legs to the ground, deck, stairs/log orientation, connected walls, lanterns —
11/11 probes); westbound sweep (stairs turned west, logs along); side offset 2; tunnel through a
stone hill (dug above the rail only up to the template top, hand-placed planks kept, deck
replaced stone, 4 copies at 94%); console undo restored all 1,165 changes; REAL curved 3-track
river rail `-2460 74 2028 → -2321 74 1892` (216.6 blocks, 27 copies at 100.3%, 26 ms; layer counts
match a straight line of the same length — no holes; 87 pre-existing non-natural blocks kept);
delete / export / import; templates reload from disk after a restart.
**NOT verified (no client was run — Thomas was playing on this machine):** the wand clicks +
outline, the save screen, the sweeper screen and its preview, the item icons, node-click order
(first → second), the fence/wall re-shape pass on curves (ran without errors, not looked at).

Not built (later phases): start/end sections, lane matching for multi-track, in-world ghost
preview, paint editor, truss + suspension builders, grade smoothing, cant, schematic import,
block-entity NBT in templates (signs/PIDS come out blank).

---

## 8. ROUND 2 — "totally broken and hard to use" (2026-10-22 → 27) — 3.4.16 → 3.4.17, DEPLOYED

Thomas's first try (3.4.16): both saves refused with "The rail cell must be inside the box" — corners
could only be clicked on blocks, so the box stopped at the track bed while the rail node sat one block
above. His answer to "how should capture work": **"select any structure no matter what, then pin it to
the rails with a preview and parameters to get the best results."** Redesign:

- **Capture = two corners only.** Right-click a block, or the AIR = the block you stand in. Live
  outline from corner 1 to where you aim. Save screen opens after corner 2: size, block count, **Trim
  empty space** (default on — the box shrinks to its blocks), name. No rail cell, no facing.
  `/sweep template capture <c1> <c2> trim|full <name>`.
- **Template = a plain box** (`SweepTemplate` format 2: s/w/h, PinW/PinH). The longer horizontal side
  becomes the length. Suggested rail position (`autoPins`): median MTR (void) cell if a rail node was
  inside the box, else the centre column on top of its most common "floor" (solid with ≥2 open cells
  above), else on top of its most common top block. Old format-1 templates map UMin/VMin → pins.
  `rotated(q)` turns the template (states + el runs) — "Turn 0/90/180/270".
- **Sweep = pick the track first.** Two node clicks (or `/sweep pick <from> <to>`) no longer build:
  `SweepService.pickRail` samples the rail every 0.5 block from the FIRST node (`SweepGeometry.Polyline`),
  keeps it as the player's pending track and sends it to the client (`sweep_track` S2C). The client runs
  the SAME `SweepMapper` against its own world (`SweepPreview`) and draws every planned block as a
  see-through full-bright ghost + the cyan track line with an arrow at the first node
  (`SweepGhostRenderer`, AFTER_TRANSLUCENT, entity-translucent layer with an alpha-scaling consumer,
  buried cells and covered faces skipped, ≤12000 nearest). **Build** sends the spec (`sweep_build` C2S)
  and the server builds along the stored polyline — preview == build. Cancel = `sweep_cancel`.
- **`SweepScreen` is a side panel over the live world**: template dropdown (delete-confirm), Fit (turn,
  rail column / rail level with Auto, side / height offset, flip), Length (repeat/stretch/once, phase),
  World (legs, empty cells dig), live summary; footer Look (close, ghost stays while holding the
  sweeper) / Cancel / Build. Without a track the left side shows the old test-track 3D preview.
  Closing always keeps the settings. Spec gained `Turn`, `RailW`, `RailH` (−1 = auto); console options
  `turn= railw= railh=`. Dev hooks `#sweep-open`, `#sweep-build`, `#sweep-set key N`.

**Verified 2026-10-27** (scratch clone of world_baker2, server 25571 / rcon 25581, client at GUI 427 px
wide, screenshots): loose-box capture trims to 8×7×12 / 122 blocks with auto rail column 3 level 10;
the 11 straight-sweep block probes pass exactly as with the old hand-picked rail cell; picking the real
curved river rail opens the panel and draws the ghost on the rails (seen clearly at height +8); Build
from the panel built 2,737 + 88 leg blocks along it (27 copies, 100.3%), screenshot of the finished
viaduct; the save screen and the white wand box. NOT verified: real mouse clicks in the panel, the live
corner-1 box while aiming, Turn 90/270 on a real track, the ghost's look at night (it is full-bright).
Rig note: the scratchpad temp folder was purged between sessions (scripts, mods, configs and the world
copy's region files) — rebuild the rig from scratch if it is gone again.
