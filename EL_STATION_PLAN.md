# NYC Elevated Station Kit — v2 rebuild (2026-09-03) + v1 history

## v2 (CURRENT): from-scratch rebuild, platform area first

Thomas judged the v1 kit (46 blocks below) "mostly garbage": built blind from a toy
renderer, five parallel agents, no station cross-section. v2 rules (his answers,
do not re-ask): ONE style — Dual-Contracts green steel, red standing-seam gable
roofs, cream board panels with a wired-glass band, Warren-truss friezes (photos:
Nereid Av, Prospect Av, 30 Av, 25 Av, Westchester Sq); green first, station tint
second, silver only if asked; side platforms 2-3 wide, islands 5-6; lamp posts
>= 3 tall by stacking; walls and railings are SEPARATE families; old `el_*` ids
are replaced as we go and deleted at the end of each section. Sections: PLATFORM
AREA (this pass) -> FARE CONTROL / STAIRS -> STRUCTURE.

**Every v2 block was placed and screenshotted in game on the dev rig before
being shown** (`run/commands.txt` dev hook feeds chat commands to the dev client
as the opped user `Rig`; `run/screenshot.flag` captures; scratchpad
`rig_restart.sh` / `shot.sh` drive it). Assets: `tools/gen_el2_assets.py`
(own element toolkit, mirror_z, verify) — never hand-edit its output.

### Platform-area blocks (all in game, 2026-09-03)

| block | class | what it does |
|---|---|---|
| `platform_edge` | `mtr/PlatformEdgeBlock` | Concrete + proud yellow tactile strip on TRACK_SIDE; **opens MTR train doors** (`implements org.mtr.mod.block.PlatformHelper` — MTR's only door test, `RenderVehicleHelper.canOpenDoors`, bytecode-verified). Adopts SLAB (1-4) + CLEAN from any neighbouring concrete floor / edge, reuses the floor models, recomputes world-grid joints. Also new 1-block concrete floors. |
| `el_roof` | `block/ElRoofBlock` | Gable canopy: AXIS = ridge; each cell walks its crosswise neighbours -> SIDE (neg/pos/crown) + LEVEL (0..2); any length, width up to 7 (32 px ceiling). 22.5° slabs via rotation+rescale; **thickness parity** (+0.1 px on odd levels) kills the coplanar overlap at cell joints; ridge half-caps for even widths; lattice frieze + fascia on level-0 eaves; truss end panels on open run ends; rafter tie at x/z 7..9 on every underside (pids_pole / signs land on it). **z-ridge rule:** the y=90 turn maps model -z to world +x, so crossNeg(Z)=EAST. |
| `el_post`, `el_post_named` | `mtr/ColumnBlock`, `StationColumnBlock` | 4 px I-section, base plate at stack bottom, cap beam + 45° knee struts at stack top (beam top flush with the block top -> sits under the roof tie). Named: 8 px black plates both flange faces, renderer paints the station name (boardOffset 0.1625). |
| `el_railing`, `el_railing_sign` | `block/ElRailingBlock`, `mtr/ElRailingSignBlock` | 20 px picket railing, fence-style N/E/S/W + post at bends/ends (RailingBlock logic, own family only); sign segment = RailingSignBlock behaviour (merging runs, MTR brush for custom text). |
| `el_wall`, `el_wall_glass`, `el_wall_sign` | `block/ElWallBlock`, `mtr/ElWallSignBlock` | Thin windscreen courses, same fence logic, own family: cream board panel / wired-glass band (cutout, mullion per block) / name band. Stack any order (each course self-framed). |
| `el_platform_lamp`, `el_platform_lamp_head` | `mtr/ColumnBlock`, `DecorBlock` | Black pole (foot at stack bottom) + cone-hat head, luminance 14. (`el_lamp_head` was TAKEN by v1 — hence the ids.) |
| `el_roof_light` | `FacingDecorBlock` | Fluorescent fixture placed in the block under a roof cell, rods up to the tie, luminance 15. |
| `el_sign` | `mtr/ElNameBoardBlock` (2nd instance) | Black name board: hanging (centre rod onto the roof tie) / standing / wall; renderer table unchanged. |

Rig lessons this pass: chat commands from the hook must be throttled (one per
25 ticks) or the server kicks for spam; the rig user must be in `run/ops.json`
(offline UUID of "Rig"); `/forceload` + teleport BEFORE fills; a hidden chat
overlay hides the view — `sendCommandFeedback false`; in zsh `$R[...]` is an
array subscript, write `${R}[...]` in scene generators.

### Round 2 (2026-09-03 evening, from Thomas's first play pass; shipped 2.4.42)

Thomas: "fantastic start", scale good, hitboxes odd (later). Fixed:
- **Walls are at the block EDGE now** (`ElWallBlock` is a FacingDecorBlock run:
  FACING = placer's look reversed, panel at model z 13.6..16 facing the placer,
  LEFT/RIGHT merge along facing.rotateYClockwise(), left post always + right
  post at run ends, v1's plane-flip rule). Any course joins any course. Under a
  level-0 roof eave the frieze sits straight on the wall's top rail - no gap.
  The gap Thomas saw was a wall under an INTERIOR roof row (roof one wider than
  the walled platform): put the roof eave over the wall row.
- **Wall sign**: `ElWallSignBlock extends ElWallBlock` + BE; renderer
  `paintWallSign` draws one black board across the run on the RIDER (facing)
  side only, at 14.2/16 - 0.5 local z. `el_railing_sign` now sets its faces at
  placement (front = the side the placer stood on, back off) - rider side only.
- **Lamp = railing extension**: `ElLampPoleBlock extends ColumnBlock`
  (`connectsDown` hook) counts an el railing below as connected (no foot), and
  `RailingBlock.lampAbove` grows the railing post under it; pole + head now GREEN.
- **Post capital compact** (head plate z 2.5..13.5, short 45° struts) so it
  never pokes past an eave; name plate 6 x 3 px, renderer board 24 x 12 units
  (`StationColumnBlock` boardWidth/boardHeight).
- **Roof light runs**: `ElRoofLightBlock` FRONT/BACK join along the facing
  axis, continuous housing + tube, caps only at run ends. Lights cannot share a
  cell with a post - place the row beside the posts.
- **Roof profile transitions**: a cell whose along-ridge neighbour has a
  different (side, level) profile draws its truss end panel too, so a 3-wide
  section meeting a 2-wide one closes the step.
- **Deprecated v1 el items are HIDDEN from the creative tab** (registered,
  `registerBlock(..., null)`), so placed ones survive but only v2 shows.

### Round 3 (shipped 2.4.43): cream soffit; WALL CORNERS — `CORNER_LEFT/RIGHT`
on ElWallBlock: the block IN FRONT (toward facing) belonging to a perpendicular
run (facing == our clockwise dir -> return on our model-west edge, counter-
clockwise -> east) draws a return panel along that edge, so an L of walls is
closed at the corner cell. Verified in game. KNOWN GAP: two ROOFS at right
angles do not join (different AXIS = different roof); each ends with its truss
panel and they interpenetrate at the corner - L-shaped canopies need a hip/
valley cell, not built yet.

### Round 4 (shipped 2.4.44): `block/EdgeRunBlock` is now the shared base
(FACING run, LEFT/RIGHT, CORNER_LEFT/RIGHT, planeFacing) for ElWallBlock AND
ElRailingBlock — railings sit at the block edge with corner returns like the
walls; `el_railing_sign` is an ElRailingBlock + BE (renderer `paintEdgeSign`,
shared with the wall sign, rider side only). Railing LAMP property: an
ElLampPoleBlock above draws a mid-run post; the lamp pole/foot/head models
moved onto the edge plane (z 13.6..16) and the head is a FacingDecorBlock, so
the lamp is literally the railing post continued. Sign courses (wall + railing)
draw posts only at run ends so the board is unbroken. Roof: rafter half-ties
at both block edges (2 px tie on the block-edge grid = over the wall posts) +
a purlin along the row centre (pids_pole still lands on steel); 2 px eave
overhang (level-0 deck to z 18, fascia at 17.2..18.2) and 2 px gable overhang
strips in the end-panel models. Roof light: single centre drop rod.

### Round 5 (shipped 2.4.45): the "holes in the ceiling" were ATLAS BLEED —
the 18 px overhang slab's down-face uv ran to v=18 and, with that face's
90-degree rotation, the overflow (the wired-glass sprite next door in the
atlas) drew as a diagonal slit along every cell edge. All el uvs are now
clamped to 0..16 and an audit over every el_* model reports zero out-of-range
faces — RE-RUN THAT AUDIT whenever an element leaves 0..16. Roof cells and
posts render with ambientocclusion:false so the capital no longer sits in an
AO pocket under the deck.

### Round 6 (shipped 2.4.46): WALLS AND RAILINGS LIVE IN THE CELL OUTSIDE THE
PLATFORM, panel on the NEAR side (model z 0..2.4) — one block per cell means a
bench could never share the wall's cell, so the wall moved out and the panel
stays on the platform edge line. `item/EdgeRunItem`: click the far third of a
floor block's top and the block lands in the cell beyond the edge (if free and
unsupported). Convex corners need no corner cell: the two runs' panels meet at
the platform corner point; `CORNER_RIGHT` (perpendicular run's end cell
diagonally ahead-right) drops this cell's right post. Returns are gone. Roof:
END THE ROOF ON THE LAST PLATFORM CELL — the 3 px eave overhang (deck to z 19,
fascia 18.2..19.2) reaches over the wall line, frieze chord just inside it.
(`el_post_base` was built here and REMOVED in round 7 — Thomas meant the
windscreen, not the columns.) HANGING SIGNS: use MTR's own Railway Sign (+
Railway Sign Pole) under the roof — the purlin at every cell centre is what a
pole lands on. MTR's sign BE type only accepts MTR blocks and its config
screen/packet cast to that BE, so our own tile-sign block would need its own
picker screen; `RenderRailwaySign.drawSign` is public static if that is ever
wanted. `el_sign` stays as the plain station-name board.

### Round 7 (shipped 2.4.47): EdgeRunBlock `BOTTOM` (nothing of the family
below) — the bottom course of a wall or railing draws post STUBS 8 px below
the block on the panel plane with a bolted bracket plate against the platform's
concrete edge face (`el_stub_left/right/mid`, in the railing and wall
blockstates under the same post conditions). Elements reach y -8 and z 17.2;
uvs stay in range.

### Round 8 (shipped 2.4.48): HITBOXES — `el_roof` outline/collision now
follow the profile (level-0: y 0..high edge; inner rows: just under the low
edge..high edge, up to 28.4 px; crown: to the peak) instead of a full cube;
`el_roof_light` outline spans the full run length. JUNCTIONS — an edge run
continues into ANY EdgeRunBlock with the same facing (wall<->railing shows one
post). INNER CORNERS — `INNER_LEFT/RIGHT` (the cell BEHIND holds a
perpendicular family run) draw a return panel along that side edge with a
corner post at its far end (`el_wall[_glass]_return_*`, `el_railing_return_*`
are back, mirrored to the near side). All verified on the rig.

### FARE CONTROL / STAIRS, round 1 (2026-09-03 night, 2.4.49): the stair family

Thomas's brief: lightweight stairs street <-> mezzanine <-> platform, side
enclosures that meet the roof structure, slanted roofs, thin windscreen-style
walls for the mezzanine "under the tracks"; reuse the earlier stairs. Built
(all rig-screenshotted, two flights: 2-wide railing+wall+roof, 1-wide
railing+wall+glass+roof) with generator module `tools/gen_el2_stairs.py`
(called from gen_el2_assets.main(); verify() covers its blockstates):

- TREADS = the existing `subway_stairs` (2 x 8 px steps, FACING = ascent,
  TOP/BOTTOM). The top stair sits at the SAME y as the platform floor (its
  upper tread flush with the floor top, vanilla-stair convention).
- `el_stair_railing` / `el_stair_wall` / `el_stair_wall_glass`
  (`block/ElStairSideBlock`, item `ElStairSideItem`): sloped side courses in
  the cell BESIDE each stair block, panel plane on the stair's edge (x 0..2.4,
  mirrored per SIDE). Props: FACING (ascent), SIDE (which side the stair is
  on, looking uphill), BOTTOM (adds stringer v 4.4..8.4 + kick plate to the
  bottom rail), END_UP/END_DOWN (diagonal run ends, refreshed explicitly like
  the stairs), LEVEL (the stair beside has TOP -> the cell levels its rails
  over the last tread at floor height and closes with an end post). Courses
  stack at a 16 px pitch (band v 12..20; rail 16 px above the nosings). Item:
  click a tread in its outer third -> lands beside the stair.
- `el_stair_roof` (`block/ElStairRoofBlock`): frieze v 12..16 + deck v
  16..17.6 (sits on the top course's rail), EDGE_LEFT/RIGHT hang lattice
  frieze + 2 px eave with fascia, END_DOWN = vertical foot fascia (deck
  overhangs to world z 20), END_UP = plate perpendicular to the slope.
- SLOPE FRAME (module docstring has the full derivation): rescaled +45 about
  x through (8,8,8): world y = v - z_a + 8, z = v + z_a - 8; at fixed world z
  a member at v is at y = 2v - z, and it sits over world z in [v-8, v+8] -
  HIGH MEMBERS ARE SHIFTED DOWNHILL, so run ends need cuts (`member(up=,
  down=)`: contain / cover / overhang) and vertical pickets/mullions are
  drawn by the cell UPHILL of the floor they stand over (keeps authored y
  inside -16..32). The level-off tops out at exactly y 32, which is why the
  stair rail is 16 px over the nosings, not the platform railing's 20.
- LIGHTING LESSON: with ambientocclusion:false every quad samples light from
  the neighbour in its face direction (flat path), so a post beside a solid
  floor block rendered BLACK - side courses use default AO now; only roofs
  and posts keep ao=false.
- Open questions for Thomas (not built): landings/turn-backs (a flight that
  turns 180 at a mid landing needs a landing railing rule), the stair house
  / mezzanine walls under the tracks (el_wall family should serve - needs a
  DOORWAY course), entrance portal + lit "XX Avenue Station" sign with route
  bullets (entrance_railing_sign has the bullet machinery), yellow street
  curb, handrail down the middle of wide flights (subway_handrail_double
  may already do), stair-top join to a platform railing (4 px height
  mismatch), roof gable vs flat-across.

### FARE CONTROL / STAIRS, round 2 (2026-09-04 early, 2.4.50): Thomas's first feedback

Thomas: scale not good (platforms are >= 3 blocks tall, stair roof needs to be
taller), gaps in textures, weird transition, wants roof corners + transitions
between roof sizes, landings, all-cream ridged + all-green windscreen courses,
entrance blocks/signs; then: landings should have a thin STEEL floor, not
concrete. Built + rig-verified (scissor stair with landing, two L-roofs):

- Stair roof band deeper: frieze v 12..18 (solid-backed lattice sprite
  `el2_lattice_solid` - the cutout web read as holes), deck v 18..19.6; END_UP
  is a vertical fascia over the frieze zone (y 24..32), the deck edge above
  stays open to run under a landing/house roof. LEVEL cells gained a newel
  post at world z 6.8..9.2 hiding the sloped/level seam. Recipe for
  headroom: three side courses (railing + 2 walls) -> roof at stair y+3.
- `el_landing_roof` (`block/ElLandingRoofBlock`): flat deck y 20..23.2,
  frieze 8..20, per-side EDGE flags; joins a stair roof at the FOOT of a
  flight in the same row and at the TOP from one cell below (stair roof
  END_UP/END_DOWN are false toward a landing roof; `refreshAround`).
- `el_stair_landing` (DecorBlock, shape y 11..16): 1 px diamond-plate deck
  (`el2_plate`) flush with the top tread + two green edge joists per cell.
- `el_wall_cream` (ridged cream, rails cream too) and `el_wall_green`
  (plain green): ElWallBlock instances, shared green posts, `wall_arm(rail=)`.
- `el_roof` CORNERS: `JOIN` property none/hip_pos/hip_neg/valley_pos/
  valley_neg/peak. Profile inside an L's corner square is COPIED from the
  nearest clean cell along the ridge (crosswise counting stops at a
  perpendicular roof = "contaminated"). Joins are read off the perpendicular
  cell BESIDE us: crown+crown = PEAK (pyramid of 4 square rings); same level
  and our run ends along its descent direction = HIP (min surface as four
  L-shaped flat steps, `hip_steps`); same level and the other roof stands in
  our run against its descent = VALLEY (both slabs drawn = max surface, via
  the blockstate applying the perpendicular slab model at y+90). Hip cells
  draw no truss panel; a perpendicular neighbour along the ridge is never an
  END. RULE FOR BUILDERS: split the corner square along the anti-diagonal
  (cells with (dx + dz) < width-1 belong to the ridge coming from the west/
  north, the diagonal itself either), and the two roofs must have the SAME
  WIDTH for the diagonal to line up - different widths only work along a
  ridge (truss-panel step, unchanged).
- Rig lessons: spectator mode for cameras (creative falls before the shot),
  `cam.sh name cx cy cz tx ty tz` aims via lookat.py; cameras must stay inside
  the dug pit (natural terrain is at y ~106 there).

### FARE CONTROL / STAIRS, round 3 (2026-09-04 morning, 2.4.51): joins + Van Siclen entrance

Thomas (with his Van Siclen Av photo): transitions still off (a wedge under the
landing roof where a stair roof met it; the hip/peak steps read as stairs),
cream course needs cream bars; entrances are OPEN - railing + slim posts to the
roof, no walls - and the roof goes FLAT at the foot to carry the sign.
- `el_stair_roof` UP/DOWN are now enums run/end/landing: `landing` extends the
  deck + frieze to the cell boundary uphill (cut `cover0`) and cuts them at the
  boundary downhill (`contain`), no fascias - the wedge is gone. `end` also
  reaches the boundary (deck to the vertical fascia).
- Hip and peak rebuilt from 2 px sloped STRIPS (`hip_steps`/`peak_steps` in
  gen_el2_assets: +z slab as x-strips covering z >= x, +x slab as z-strips via
  `deck_slab_x` = rotation about z, -A descends +x; 2 px flat squares on the
  diagonals). Only a 2 px sawtooth remains on the hip line. mirror_x/mirror_z
  now handle z-axis rotations.
- `el_wall_cream` has cream posts (`el_wall_cream_post_left/right`) and cream
  return posts.
- `el_stair_open`: side course with nothing but the post (railing below, roof
  above, open air between - the photo). Level cell = end post + newel only.
- Entrance recipe (rig-verified): railing + open + open courses, roof at +3,
  `el_landing_roof` row behind the foot cell (flat hood) and one above the
  top cell. Sign block for the hood fascia still to build.

### FARE CONTROL / STAIRS, round 4 (2026-09-04, 2.4.52): entrance sign + flight-top wedge

- `el_entrance_sign` (`mtr/ElEntranceSignBlock`, FacingDecorBlock + StationDecorBlockEntity):
  lit black box (model x 0..16, y 12..22, z 2..5 on the FACING side - a 2 px drop under the
  hood's frieze chord at y 24; Thomas: not lower) with LEFT/RIGHT join flags so a merged run
  hangs on exactly two straps (run ends only). Place it in the cell under the hood's front edge,
  facing the street. Data = the entrance railing sign's (custom name, per-face on/off,
  RoutesFront/RoutesBack); the MTR brush opens RailingSignScreen (frontOf = FACING);
  `StationDecorRenderer.paintEntranceSign` paints both faces (bullets left, name right,
  centred name without bullets; front canvas at local z -0.379, back at +0.1835 after
  the 180 turn). luminance 12. Adjacent sign cells with the same facing MERGE into one
  panel (full-width box model, first cell of the run paints 64*run-4 units; custom name
  from any segment, routes from the first non-empty). NOT play-tested: brush screen.
- Stair roof `cover0` friezes (up = end/landing) fill the wedge between the sloped frieze
  bottom (16 -> 24 over z 8..0) and the course's leveled-off rail (y 16) with a solid
  panel - Thomas's "these kinds of gaps".

### FARE CONTROL / STAIRS, rounds 5-6 (2026-09-04, in-game with Thomas; NOT yet deployed past 2.4.52)

Thomas built in the dev world (rig client as `Rig`; `rig_restart.sh` restarts it - Gradle
kills the JVMs, so Java changes wait for his "restart", model/texture changes are copied
into build/resources/main and reloaded with F3+T). Fixed from his live feedback:
- ITEM ICONS: every v2 el item is a flat 32 px sprite now (`icon_*` in gen_el2_stairs.py,
  textures/item/el_*.png, item models = minecraft:item/generated). The 3D item models
  filled half the screen in hand.
- HITBOXES: stair roof collision = four steps tracking the deck underside (36 - z); landing
  roof = deck only unless a side is an edge; side courses = four steps on the 45-degree band
  (bottom course down to the floor). A full-cell roof box had blocked the flight.
- PLACEMENT: `ElStairSideItem`/`EdgeRunItem` climb existing courses in the outside column
  (click the tread / floor far-third again to stack); the stair item FAILS instead of
  dumping a course onto the tread when the column is blocked; stacked courses inherit
  FACING/SIDE from the course below and LEVEL propagates upward. `EdgeRunItem` also
  stands a wall ON A STAIRWELL RIM (the cell beyond the edge is the well over a stair):
  panel toward the well, facing = look direction (state flipped after placement).
- POSTS TO THE ROOF: side-course `TOP` (nothing of the family above) runs the post to
  y 32; every stair-roof edge cell adds a post stub (y 0..10.8 at z 13.6..16) up into the
  frieze chord, so the post visibly carries the roof and the flat hood's beam.
- CORNERS (EdgeRunBlock): `CORNER_RIGHT` now EXTENDS the right post 2.4 px past the cell
  into the uncovered corner square (`*_post_right_ext`), only when the diagonal cell is
  empty; a block placed IN the diagonal corner cell (`CORNER_CELL` left/right: the
  perpendicular run stands in FRONT of it) draws only a corner post at the corner point.
- WINDOWS: `el_wall_glass` panes carry their own stiles (x 0.2..1.4 / 14.6..15.8, inside
  the post footprint) + the mullion, so a pane is framed all round whatever the posts do.
- `el_wall_cream` posts are cream (`el_wall_cream_post_*`, Thomas: cream bars).
- `el_entrance_sign`: box y 12..22 (2 px under the hood chord), LEFT/RIGHT joins, straps
  only at run ends, merged canvas across a run.
- `el_wall_doorway` (`block/ElWallDoorwayBlock`): posts + header beam, no collision - the
  way into a stair house; stack a course above for the transom.
- `el_stair_open`: posts-only course (Van Siclen Av: open sides, occasional posts to the
  roof). Recipe: railing + open (+ open) + roof, landing roof behind the foot = the hood.
STILL OPEN: Thomas's "stairs cut through the platform" stair house needs an in-game pass
with the rim placement; deploy 2.4.53 after his next restart.

Still to do in the platform section: Thomas's in-game pass (door opening with a
real train, placement feel, sign text via brush), then delete the v1 ids that
these replace. Then fare control / stairs.

---

# v1 history (2026-08-28) — superseded, kept for the lessons


Researched 2026-08-28 from photo references (DuckDuckGo image sweeps of: 125 St
Riverside viaduct, Jamaica Ave J/Z structure, Marcy Av, W 8th St, Bay Parkway,
Astoria Blvd rebuild). Thomas's decisions (2026-08-28, do not re-ask):
**hand-placed blocks only** (no creator tool); paints = **classic dark green +
galvanized silver + station-color TINTED + a station-NAMED variant**;
**all four windscreen materials** wanted; build order = **structure first**,
platform furniture second.

## What the photos show (the catalog)

STRUCTURE (street → deck):
- Thin built-up steel columns at the curb line: either SOLID riveted box
  columns (Jamaica) or X-LACED LATTICE columns you can see through (125 St,
  Marcy). Concrete/steel base at the street, flared gusset cap at the girder.
- Transverse cross girders (bent caps) over the columns with CURVED KNEE
  BRACES flaring down each column side — the signature el silhouette.
- Longitudinal riveted PLATE GIRDERS (rivet rows, panel-joint stiffeners) and
  open lattice TRUSS girders (X diamonds between chords, Marcy platform edge).
- OPEN TIE DECK: you see sky between the ties from the street below.
- Paint: dark green (Dual Contracts) or teal (Brighton); rebuilt = galvanized.

PLATFORM (phase 2, not yet built):
- Canopies: gabled standing-seam roof with open truss in the gable end
  (Marcy), or flat corrugated deck on slim square posts with curved gussets
  (W 8th). Wood-deck underside with rafter tails (Bay Pkwy).
- Windscreens (merging runs): panel + wired-glass glazing band (classic),
  green board-and-batten wood, corrugated sheet, modern full glass, and
  diamond-mesh for platform ends.
- Pipe railing (two-rail, green) where there is no windscreen; modern
  galvanized picket railing.
- Black station NAME BOARD with white letters on the windscreen top rail;
  black EXIT signs with white arrows; goose-neck / stanchion platform lamps.
- Modern rebuilds: glass stair enclosures, glass elevator towers.

## Phase 1 (BUILT this session): the structure family

All assets generated by `tools/gen_el_assets.py` — never hand-edit. Paints:
`green` / `silver` / `station` (grayscale + tint provider, iron-column
mechanism). 16 blocks, DECORATION tab:

- `el_column[_silver|_station]` + `el_column_named[_station]` — slim 6 px
  solid riveted column (4 proud corner angles + rivet strips), reuses
  ColumnBlock UP/DOWN stack detection: bottom of a stack grows the street
  base plates, top flares the gusset cap. Named = StationColumnBlock with the
  renderer board (board offset now PER-BLOCK — the el column is slimmer than
  the iron column's flange plane).
- `el_lattice_column[_silver|_station]` — same bones but the four faces are
  X-diamond lattice CUTOUT panels between the corner angles (see-through).
- `el_girder[_silver|_station]` — full-height riveted plate girder: web,
  top/bottom flanges, panel-joint stiffeners at block ends. AXIS block
  (log-style); when the block directly BELOW is any el column, the model
  grows the two curved knee braces (stepped arcs reaching down into the
  column's block) — bents assemble themselves.
- `el_truss[_silver|_station]` — same envelope, open lattice web (cutout)
  between chords; same knee-brace behaviour.
- `el_deck_ties` — open tie deck: ties + stringers at the top of the block,
  see-through from below, top-slab collision (walkable, clearance below).
- `el_deck_plate` — solid riveted plate deck, same collision.

## Phase 2 (BUILT same session): platform furniture — 12 blocks

- `el_windscreen[_corrugated|_glass|_mesh]` — merging runs (ElScreenBlock:
  FACING + LEFT/RIGHT, zebra rule; multipart draws each segment's LEFT post
  always + a closing RIGHT post at run ends — one shared post per joint).
  Classic panel+glazing and corrugated wear green frames; glass and mesh are
  galvanized CUTOUT panes.
- `el_railing_pipe` (green two-rail + centre post) / `el_railing_modern`
  (galvanized pickets) — same class, facing-rotated.
- `el_canopy_post[_silver]` — ColumnBlock stack detection: smooth shaft,
  curved 45° brackets only on the stack's TOP block.
- `el_canopy_flat[_silver]` + `el_canopy_gable` — ElCanopyBlock: AXIS +
  N/S/E/W same-family connections, tiles to ANY platform footprint. Flat
  hangs its riveted fascia girder on every open edge; gable closes open
  ridge ends with a stepped end plate. Authored LOW in the block so a canopy
  directly above posts touches them; eave ribs + CENTRE PURLIN (flat) and
  ridge TIE CHORD (gable) give hanging blocks (NYC PIDS 2 px ceiling stub at
  x/z 7..9) steel to land on. Concrete/tile platform floors are the intended
  walking surface — nothing special needed, they are full blocks.
- `el_name_board` — ElNameBoardBlock + StationDecorBlockEntity: black board,
  white name painted by StationDecorRenderer.paintElNameBoard on both faces,
  auto MTR station name, right-click = custom name screen.

## Phase 3 (BUILT 2026-08-28, previews approved): mezzanine + street — 16 blocks

Generator: `tools/gen_el_phase3.py` (module invoked from gen_el_assets.build();
its --out mode still writes a standalone preview tree). Photo refs added:
Prospect Av street stairs (red canopy over teal flight, ornamental portal,
globe lamp post), station cutaway, Jefferson St headhouse sign.

- `el_stair_side` / `el_stair_canopy` — 45° pieces on the handrail-slope
  rescale math (ElSlopeBlock: FACING = ascent from player look; centre
  y = 16 − z chains block-per-block to ANY stair length). Side = corrugated
  screen + sloped rail + kick; canopy = red seam roof, green underside,
  edge fascias.
- `el_portal_post` (ColumnBlock stack, lattice scroll bracket at stack top)
  + `el_portal_header` (frieze beam + cutout lattice band, ends omitted so
  runs merge; posts cap run ends) — spans any opening; hang el_name_board
  under it for the sign.
- Station house: `el_house_wall_{green,cream}[_window]` — full OPAQUE cubes
  (vanilla culling; opaqueSettings without nonOpaque), board-and-batten +
  wired-glass window band; `el_soffit` (top-slab beadboard ceiling);
  `el_booth` (ElBoothBlock 2-tall multiblock, loot gated half=lower).
- `el_wood_platform` (opaque plank cube), `el_platform_edge` (opaque cube:
  tactile-dome strip top + riveted fascia toward FACING).
- `el_exit_sign` (ElExitSignBlock: MOUNT ceiling/wall from clicked face,
  right-click cycles ARROW none/right/left/down — 8 models, back faces flip
  u so lettering reads both sides).
- Lamps: `el_lamp_gooseneck` (ceiling-hung, luminance 14, shade:false bulb),
  `el_lamp_post` + `el_lamp_head` (stack the head on the pole; head lum 14).
- Gable canopy's end plate UPGRADED to the open truss triangle (bottom
  chord + 22.5° chords + lattice web); el_canopy_gable is cutout now, as
  are portal post/header.

## Quality pass (2026-08-28, Thomas: "half-baked / wrong sizes / not modular")

- Agent booth REMOVED (block+class+assets). House WINDOW blocks rebuilt as
  REAL windows: wall sill/lintel/jambs + recessed see-through wired-glass
  pane (el_glazing, cutout, vanilla-glass style); blocks now nonOpaque.
- WINDSCREENS were 14.6px fences → panels now FULL block height and STACK:
  ElScreenBlock gained UP/DOWN (same block+facing above/below); multipart
  adds el_screen_rail{,_silver} at up=false and el_screen_kick{,_silver} at
  down=false; posts extended to y16. Two-high = real windscreen.
- GABLE roof was a 4.6px-high kink → rebuilt at 45° pitch (eave boards y~1,
  planes to ridge cap y~9.9); gable_end truss re-derived at 45° with a tall
  lattice web. ROTATION SIGN RULE (this bit twice): rotating +45 about x
  DESCENDS toward +z from the origin (stair-slope convention) — an eave-
  origin plane rising toward +z needs −45.
- Post shafts sample a wider brushed window (u 12.4..15.4) so they stop
  reading flat. Stale models deleted (4 gate_scroll stacking-era + 2
  pre-split canopy post); full asset audit script now part of the workflow:
  every blockstate model exists, every face ref resolves, every PNG valid
  by IDAT length (RGB and RGBA both legal; non-square is legal too — the
  door signs prove it in production).

Still unbuilt ideas: glass stair enclosures/elevator towers (modern rebuild
kit), X-brace panels between columns, 125 St arch braces, cable troughs,
track bumpers, catwalks.
