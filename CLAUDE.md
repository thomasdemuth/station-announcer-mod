# CLAUDE.md — Station Announcer Mod

## What this project is

A Fabric mod for **Minecraft 1.20.4** (mod id: `station_announcer`) that adds a PA/public-address
system for train stations. Placeable "Station Announcer" blocks store an announcement; when
triggered (redstone rising edge or `/announce` command), every player within the block's radius
sees `[PA] <text>` and hears it spoken by **client-side text-to-speech**, preceded by a ding-dong
chime.

## End goal

A polished, standalone station PA mod that sits alongside **Minecraft Transit Railway (MTR) 4.x**
without depending on or conflicting with it (no mixins, no vanilla overrides), and that a future
companion addon can drive from MTR train events. The intended endgame integration paths
(documented in README "Hooking announcements to MTR events"):

1. A separate addon depending on MTR that maps platforms→tags and calls
   `AnnouncerRegistry.trigger(server, tag)` or executes `/announce` when trains arrive.
2. Zero-code wiring: MTR redstone outputs pulsing announcer blocks directly (this is why
   rising-edge triggering and per-block delays exist).

## IN PROGRESS — LIRR M7 custom train: read `M7_HANDOFF.md` + `M7_CONVERSION_NOTES.md` first

Custom MTR rolling stock (LIRR M7, then NYCT R143 and R62). **The exterior-first pass is
BUILT (2026-07-28)**: real geometry converted from the openBVE donor
(`tools/convert_openbve_m7.py` → m7.obj, 31 groups + 27 processed textures), single-leaf
sliding doors with empirically-verified flip/animation math, r179-style stacked properties
(cab vs gangway ends per variant), and a fully synthesized BVE sound set
(`tools/gen_m7_sounds.py`, `bveSoundBaseResource: station_announcer:m7`). Log-verified on
the dev rig: 423 → 431 vehicles, door validation clean, zero model/resource errors; offline
renders (tools/render_obj.py) read as an M7. **Awaiting Thomas's in-game verdict before the
version bump** — open risks and the remaining passes (interiors, R143/R62) are listed in
M7_HANDOFF.md §1.

**`M7_HANDOFF.md` has the decisions and status; `M7_CONVERSION_NOTES.md` has the
empirically-verified MTR OBJ-loader spec and donor inventory (do NOT re-derive either —
units are BLOCKS not 1/16, x/z are negated vs bbmodel, flipped entries compose R·(v+t),
R179 door travel is |m|−0.5, etc.).** Pipeline order: convert_openbve_m7.py →
gen_m7_assets.py → convert_openbve_m7.py --check. Assets are generated — never hand-edit.

**Rig hazard learned this session:** while background agents run heavy Java work, the
rig's Gradle wrappers (and sometimes the JVMs) get SIGKILLed — forbid agents from Gradle
entirely and check rig health after each one finishes; an orphaned JVM keeps serving but
loses the console fifo.

### v2.4.1 — addon release (2026-08-07)

**SHIPPED to `releases/station-announcer-2.4.1+1.20.4.jar`**: the five dispatch/lift/
driving features (incl. the hold-rule transfer window + doors-open fix) AND the Dispatch
web UI (backend on MTR's Jetty + /dispatch/ frontend; schema in PROGRESS.md). Release
copy stripped of the in-flux M7/R62 train content per the 2.4.0 procedure (now also
r62_* sound keys and properties/vehicle+definition dirs; 14 chime keys kept; the six
remaining "vehicle"-named entries are addon mixin/dispatch classes — they stay). Source
tree untouched; 2.5.0 ships the trains by not stripping. Addon compile-verified + full
build green; frontend browser-verified in ?demo=1 mode; NOT in-game tested.

## MTR DISPATCH ADDON (2026-08-07) — read ARCHITECTURE.md + PROGRESS.md first

Five dispatch/lift/driving features built INSIDE this mod (additive, user decision),
targeting **MTR 4.0.1** (gradle.properties downgraded from 4.0.5 to match the Baker City
play profile; existing code compiles unchanged against it). The repo is now a **git repo**
(baseline commit = pre-addon v2.4.0 state; one commit per feature). The mod now **has
mixins** (`station_announcer.mixins.json`, package `com.stationannouncer.mixin`) — the
old "no mixins" rule is relaxed for the addon only. New packages:
`com.stationannouncer.mtraddon` (config/store/snapshots/networking/engines) and
`client.mtraddon` (GUI injection via Fabric ScreenEvents into MTR screens, HUD, client
mirrors). MTR reference sources cloned at `mtr-src/` (gitignored; TSC is master and has
DRIFTED from 4.0.1 — always javap the 4.0.1 jar in loom-cache before touching MTR members).
Features: 1 platform hold rules (Vehicle.startUp HEAD-cancel), 2 per-route dwell overrides
(PathData dwell rewrite at generation), 3 multi-sided lift doors (client render takeover +
AddonModelLift — side-door yaw signs were inverted so ±X doors rendered at the cab CENTRE;
fixed 2026-08-17, then 2026-08-18: doorway boxes X-flipped vs the model matrix, per-side
landing checks tightened, displays moved to doorless walls, corner z-fighting nudged out —
details in PROGRESS.md), 4 driving HUD (client-only, H key settings), 5 platform groups
(generation-time rotation via DepotMixin; runtime switching deliberately not implemented —
see PROGRESS.md). All compile-verified + full build green; NOT in-game tested yet —
Thomas is play-testing and will report fixes. ARCHITECTURE.md is the authoritative
internals doc (TSC threading, hook points, per-feature designs); PROGRESS.md has each
agent's files/limitations.

**Holding lights now use the shared `PlatformPicker`** (one-of mode) instead of a
platform cycling button, and YELLOW ones have a **Hold rules** button (Ignored / Flash
while held / Only when held) driven by a new `addon_hold_state` S2C packet —
`HoldRuleEngine` records which platforms are actively holding a train, `AddonInit`'s
ticker broadcasts the set only when it changes, `ClientHoldState` mirrors it, and the
renderer flashes the lenses. Green lights are unchanged (see PROGRESS.md's known gap).

### INSTANT-DEPLOY CLOCK REWIND WEDGES SIM-CLOCK STATE (2026-08-19) — found via a "permanently stuck" train

**Thomas's doors-obstructed train that never departed was NOT an expiry bug in the
engine** — headless repro at chancePercent=100 showed every train self-heals. The real
cause: **`Simulator.instantDeployDepots` (dashboard "Instant Deploy" / `/mtr
instantDeploy`) fast-forwards `currentMillis` through a FULL DAY of simulation in 1 s
slices, then REWINDS the clock.** Every `startUp` during the replay runs our mixins, so
DoorObstructionEngine rolled deadlines up to 24 h in the future; after the rewind,
`stuckUntil > now` held for hours → startUp cancelled every tick, doors re-asserted
open, HUD alert re-pinned (its wall-clock deadline was recomputed from the huge
remaining every tick). Reproduced on the dev rig: `/mtr instantDeploy` froze BOTH test
trains (136 s+, zero departures); with the fix they cycle straight through it.

**The rule: any addon state stamped with `data.getCurrentMillis()` can be poisoned by
instant deploy.** Fix pattern (in DoorObstructionEngine + HoldRuleEngine): a state
whose timestamp is > 30 s AHEAD of the sim clock (`FUTURE_SLACK_MILLIS`) is deploy
debris — drop it and start the stop afresh. DoorObstructionEngine also gained a hard
backstop (`maxSeconds + 10 s` past the roll → force-release + WARN log), mirroring the
hold engine's deadlock cap. Anything future that timestamps against the sim clock
(analytics is fine — it stamps events but nothing gates on them) MUST either guard
like this or tolerate the rewind. Verified: dedicated-server repro pre-fix wedges,
post-fix departs; obstruction dwell shows up in analytics as dwell ≈ schedDwell + roll.

Also: the shipped obstruction default is 2–6 s at 3 % — Thomas believed 30–60 s;
if he wants longer delays, bump `doorObstruction.minSeconds/maxSeconds` in
`config/station-announcer-addon.json`.

### NYC SUBWAY STAIRS + HANDRAILS (2026-08-23) — 7 new common blocks over 3 feedback rounds, SHIPPED in 2.4.13

Built from Thomas's four reference photos (user chose: 2 steps/block profile, sloped
handrail variants IN the right-click cycle, manual divider block). Assets GENERATED by
`tools/gen_stair_assets.py` — never hand-edit; verify() enforces when-key/uv contracts.

- `subway_stairs` / `subway_stairs_old` (`block/SubwayStairBlock`): vanilla-stair
  silhouette read as TWO 8 px steps; modern = diamond-plate treads with a subtle
  serrated grip edge (1-in-4 tick — a 2:2 near-black zebra read as a barcode) and
  CUTOUT mesh risers (registered getCutoutMipped); old = dirty brown-grey concrete.
  TOP/BOTTOM booleans paint the run's first/last step nosing+riser safety yellow via
  FOUR BAKED MODELS (plain/bottom/top/both — never overlay quads). Run continuity is
  DIAGONAL (up+forward / down+back), which vanilla neighbor updates never fire for:
  onPlaced/onStateReplaced explicitly recompute the two diagonal cells. Up faces map
  v toward +z, so tread textures draw the grip at the BOTTOM of the sampled window.
- `subway_stair_divider` (`block/StairDividerBlock`): the photos' black stringer
  beam between lane pairs — one 45°-rotated rescaled plate element, thin full-height
  collision wall, FACING = ascent. Placed manually between stair columns.
- HANDRAILS, SECOND SHAPE (Thomas's round-3 design, shipped 2.4.13): FOUR blocks —
  `subway_handrail_{wall,standing,double,floating}` (one per mounting style, like
  MSD splits its railing family; the single `subway_handrail` id from the few-hour
  2.4.12 window is GONE — placed ones become missing blocks). Each block's item
  right-click-cycles SIX VARIANTS (`HandrailBlock.Variant`, stored in item NBT,
  read in getPlacementState; action-bar + tooltip readout): flat / slope (45°
  stairs) / slope_bottom / slope_top (stair-to-floor transitions; on DOUBLE these
  U-CAP the twin rails around the run end) / corner_left / corner_right.
  **Slope arithmetic**: everything is authored at rail center y 14.5 and slopes
  rotate 45° rescale:true about (·, 14.5, 8), so a center-line point maps to
  y = 22.5 − z — full slopes run 6.5→22.5 (chains one block up per block forward)
  and the transitions' half-slopes meet the flat rail at the block middle EXACTLY.
  WALL: flat/corners hug the wall FACING points into (clicked face); sloped
  variants run beside the wall with brackets at mapped rail heights, MIRROR
  (picked from where the player stands) swapping sides via mirror_x'd models.
  corner_right models are mirror_x(corner_left). Slope tubes are the core box only
  (no compound rotations; at 2 px the missing octagon twin is invisible).
  Collision = thin wall / L for corners (rails block movement).
- REFINEMENT ROUND (Thomas's in-game feedback, same day, shipped 2.4.12): modern
  stairs rebuilt as FLOATING 1 px construction — tread pans + mesh riser panels,
  fully open behind/underneath (see-through from the back; collision keeps the
  solid stair boxes so walking is unchanged; old stays solid concrete). Yellow end
  treads now painted across the WHOLE tread ("extend to the edge"). The stringer
  gained TOP/BOTTOM (diagonal detection + refreshDiagonals like the stairs; the
  full 45° rescaled beam overshoots its block ±3.5 px at the corners — mid-run
  that bridges neighbors, at ends it read as a floating blade, so bottom clips
  the beam flush to the floor + vertical foot, top clips flush under a level
  landing cap — an authored z-range [z0,z1] rotated about (8,8,8) lands on
  exactly that stretch of the diagonal, mapped y = 8+(a-b)) and LEFT/RIGHT
  ({none,modern,old}: a same-facing stair beside the plate grows that stair's
  steps as WINGS to the plate — the stair element builders are parameterized by
  x-range, wings are x 0..7.25 + mirror_x). Divider blockstate is multipart now.
- Verified on the dev rig (screenshots): see-through floating modern lanes from
  behind, full-width yellow, stringer foot/cap ends, wings on both a
  modern|modern and a modern|old divider; wall/standing/double/floating rails.
  NOT yet play-tested: real placement flows (auto TOP/BOTTOM, item cycling, wall
  clicking) — headless setblock cannot exercise getPlacementState.
  A top-down camera made the standing rails look flattened once — eye-level shots
  disproved it; don't chase that again.

### 2.4.17 (2026-08-27) — door orientation/opening, edge-aligned handrails, MVM rename; NOT yet deployed/play-tested

Four fixes/features from Thomas's feedback round (his answers, do not re-ask):
- **Both 2-tall doors place by LOOK DIRECTION now**: `facing = getHorizontalPlayerFacing()`
  (was `.getOpposite()` = signs faced away) and the adopt-the-neighbouring-run's-facing
  loop is DELETED from EmergencyExitDoorBlock + EmployeeDoorBlock — the player's look
  is authoritative (Thomas: it "ignored" him). A door reversed inside a run shows
  doubled posts at its joints (its own frame) — accepted trade-off, joins() still
  requires exact facing match.
- **Employee doors open**: OPEN property, right-click (non-brush) swings both halves
  (iron-door sound, NO alarm), collision empty while open, auto-close after
  `AUTO_CLOSE_TICKS` = 80 (4 s) via scheduledTick on the lower half. Open models are
  `swing()` of the closed elements (exit door's hinge x3 z8) —
  `employee_door_{style}_open_{half}` in gen_gate_assets.py; blockstate leaf parts
  gained open=true/false conditions + contract sets updated. The BER label plate
  swings with the leaf (StationDecorRenderer: translate/rotate90/untranslate about
  local x −0.3125 when OPEN).
- **Handrails**: new `ALIGN` EnumProperty (left/center/right) on all four styles;
  floating+standing non-corner variants get `_left`/`_right` models (same elements
  translate_x'd ±5.5 px — ±5.5 keeps rail/post/foot clear of block boundaries, flush
  would z-fight the adjacent lane's opposite-aligned rail). Picked from WHERE IN THE
  BLOCK you click (thirds across the run); double/wall/corners stay center. Blockstate
  keys per style are uniform: floating/standing = facing×variant×align (mirror
  wildcard), double = facing×variant, wall unchanged. WALL FIXES: sloped mirror was
  INVERTED vs the click (comment said stand-position, code used hit-pos with the wrong
  sign) — now the rail sits on the side that actually HAS a solid wall
  (isSideSolidFullSquare), tie → the clicked side; flat/corner clicked on a
  floor/ceiling now searches look/right/left/behind for a real wall. Wall-slope
  OUTLINES now hug the correct side (were centered). Item tooltip gained a
  side-hint line (floating/standing only).
- fare_machine renamed "MetroCard Vending Machine" (lang only).
- **TICKET_RESEARCH.md**: deep dive on MTR 4.0.1 TicketSystem (all bytecode-verified):
  fares = $2 + $1×Manhattan zone distance (Station.getZone1/2/3), entry allowed iff
  balance ≥ 0, exit never denied (can go negative), MTR's evasion fine is $500 (ours
  $100), our turnstile `hasEntryRecord` uses ANY-of-3 zone scores where MTR's
  entered() needs ALL — one-line exactness bug. No dry-run API but getBalance +
  scoreboards replicate the deny decision synchronously. BlockTicketBarrier blocks
  with an invisible 24px z7..9 collision wall (CLOSED+PENDING), opens in callback,
  40-tick re-close — complete recipe for an optional blocking mode. P1–P12
  prioritized proposals at the end; P10 (BER rotor spin) + P11 (fine → $500) are
  flagged [ask Thomas]. Nothing implemented from it yet.
- SECOND BATCH same session (Thomas picked from the P-list: small wins + P8 + P11;
  declined the visual pass, blocking gates, rotor spin):
  - `hasEntryRecord` now requires ALL THREE zone scores nonzero (MTR's entered()).
  - Entry pre-check: would-be entry (allowEntry && !wasInside) with balance < 0 →
    instant STOP lamp + TICKET_PROCESSOR_FAIL + MTR's own `gui.mtr.insufficient_balance`
    key, passThrough skipped.
  - `Indicator.WAIT` (amber) set before passThrough, replaced by GO/STOP in the
    callback; 100-tick safety clear. Lamp sprite re-zoned to FOUR 4-row bands
    (off/go/stop/wait) + `*_lamp_wait` models; blockstate loops + PROPS updated.
  - Sounds: low turnstile now uses TICKET_PROCESSOR_ENTRY/EXIT(_CONCESSIONARY)
    via overridable entrySound()/etc; TurnstileHeetBlock overrides back to the
    TICKET_BARRIER clunk.
  - Empty-hand right-click on turnstile/HEET = balance enquiry (entry beep +
    msg.station_announcer.fare.balance).
  - Fare machine empty-hand now OPENS MTR's ticket-machine screen
    (`Init.REGISTRY.sendPacketToClient(new ServerPlayerEntity(player), new
    PacketOpenTicketMachineScreen(balance))`); emerald quick top-up kept.
  - Exit-door EVASION_FINE 100 → 500 (matches MTR's own evasion charge).
- THIRD BATCH same session — FARE-GATE MODEL REBUILD (Thomas: "the model itself
  needs improvement"; scope he picked: low turnstile + HEET + tubing/signs, all
  four complaints: no real tripod / too blocky / proportions / flat textures):
  - TRIPOD (write_arm rebuilt): cast collar + 45°-TILTED SPINDLE BOSS (box
    authored along y, rotated +45 about x — the real NYC axis direction) +
    horizontal octagonal blocking arm at hub height y13 (was y11ish) + ONE
    through-bar rotated −45 about x whose ends are the down-forward and
    up-back arms (120° spacing impossible with single-axis rotations; the
    perpendicular collapse reads right). Rubber tips on all three ends
    (shared RUBBER_UV). Java outline arm box widened to (3,7,3)-(15,16,13.5).
  - PILLAR: two angled swipe-reader heads (±22.5° about x) flanking the riser
    foot on the cap — the MetroCard reader tilt; tilted top carries the slot
    art. The 2px lens is GONE, replaced by a pictogram STATUS PLATE per
    approach face (green up-arrow GO / red bar STOP / amber dot WAIT / dark
    off), new `turnstile_picto` sprite (32px, four 8-texel zones — NOTE uv is
    in 16-UNIT SPACE: zone tops 0/4/8/12, a texel-coord window was the first
    bug). All lamp/picto elements shade:false.
  - HEET: five 1px cage bars on a 2px pitch (was four 1.2px), proud waist
    rail band on the lower half (wider than the bars so they bury inside it),
    rotor = octagonal drum (core + 45° y-twin) + comb prongs every 2.4px with
    rubber cap end faces, lit lintel pictogram signs on the roof fascia
    (north+south) via the heet lamp models.
  - TUBING: signboard bigger (10px wide, centred on the LANE centre x10.5)
    hung on two visible straps burying into the rail; bordered sign texture.
  - TEXTURES: brushed() wider tonal range + deep seams (still per-column
    only); pillar face got recessed bezel + scanlined screen, lit slot lip,
    concentric tap rings.
  - Java luminance: TURNSTILE/EXIT/HEET use fareGateSettings() = luminance 7
    while INDICATOR != OFF (MTR-barrier precedent); cap stays plain.
  - VERIFIED OFFLINE via scratchpad render_blockmodel.py (tiny isometric
    block-model renderer, painter-sorted, handles element rotations —
    worth recreating for future model work): tripod side view shows the
    45° bar + perpendicular boss, HEET shows cage/comb/lintel. NOT in-game.
- FOURTH BATCH — audit fixes (Thomas: "fix these", from the what-else-is-buggy review):
  - EXIT-LANE PLATE: turnstile_exit gets its OWN lamp model set
    (`turnstile_exit_lamp_*`, lamp_prefix param on turnstile_blockstate) —
    the wrong-way (north) plate is a FIXED red no-entry bar in every state
    (lamp_models plates accept a 6th fixed-state element); the dynamic plate
    had covered the painted roundel and would have flashed GO at the
    no-entry side.
  - LAMP RACE: INDICATOR_DEADLINE static map (upper-pos → due time);
    scheduledTick only clears once the deadline passed, so a stale WAIT
    clear can no longer wipe a fresh GO early. Cleared in clearAttempts().
  - WALL END COLLARS: new `WALL_RIGHT` property on TurnstileBaseBlock
    (withRow() computes RIGHT + wall via isSideSolidFullSquare toward
    rightDirection; old placements default false and self-heal on neighbor
    update). Blockstate: right=false splits on wall_right —
    `turnstile_tube_wall_end` (straight rail into a collar flange, 0.05
    inset off the boundary) vs the curled elbow. Cap included via shared
    row_end_parts(); HEET unaffected (joinsRow false). PROPS updated.
  - Item icons redrawn to the tripod silhouette; sign hanger straps now
    sample the tube steel (were sign-coloured); exit-door strobe: shade
    false on the lit lamp + luminance 10 while ALARM (MtrStationDecor).
  - Upper outline now includes the riser pipe (union to y24, fence-style).
  - README caught up: MetroCard machine + MTR screen, tripod/HEET/exit-door
    text, NEW subway stairs & handrails section (the family was never
    documented). Label-swing math re-derived and CONFIRMED correct (point
    (15,·,8) → (3,·,−4) matches the +90° local transform) — no change.
- Build green at 2.4.17; deploy_jar.sh REFUSED (game open) — deploy + in-game test
  still pending: door placement feel, open/close + label swing, handrail thirds
  clicking, wall-detection placement, WAIT/STOP lamp flow, processor beeps,
  ticket-machine screen from the fare machine, and the whole fare-gate
  model rebuild (tripod look, reader heads, pictogram plates day+night,
  HEET comb/lintel, signboard straps).

### SERVICE CHANGE POSTERS (2026-09-05) — per-disruption MTA-style posters + a hangable frame block; 2.4.54

Built from Thomas's two poster mock-ups. Every disruption can own any number of
**service change posters** (`mtraddon/disruption/ServicePoster` — plain record, JSON +
packet codec, `Builder` for the editor), stored in AddonStore (`posters`, cascade-deleted
with their disruption on delete AND expiry), synced wholesale by `addon_posters` S2C
(`ClientPosters` mirror), edited via `addon_update_poster` C2S (op-gated,
`disruptions.maxPosters` cap, default 64). Entry point: the **Posters** action on each
row of `DisruptionsScreen` → `PosterListScreen` (edit / copy / delete / New poster from a
template pre-filled with the disruption's lines + message) → `PosterEditScreen`:
scrolling form on the left (title bar + logo, big timing line, two date lines, header
line bullets as removable chips, category, ordered BODY BLOCKS — headline / text /
subhead / big arrow (8 directions) / rule / space — with ↑↓x, footer left/right) and a
LIVE PREVIEW on the right. Inline tokens go into any text field from the fixed
**Insert** toolbar at the cursor: `{b:LINE}` route bullet, `{d:LINE}` express diamond,
`{wc}` wheelchair, `{<} {^} {v} {>}` small arrows — LINE is the line name (before MTR's
`||`), resolved to colour at draw time from simplifiedRoutes, then dashboard routes,
else grey (`PosterLayout.lineBullet`).

**ONE layout, two surfaces.** `client/mtraddon/PosterLayout` paints onto a `Surface`
interface; `GuiSurface` (DrawContext, scanline discs/polygons) draws the editor + picker
previews, `WorldSurface` (debug quads + polygon-offset text via CanvasPainter) draws
the block — so the preview IS what hangs in the world. Canvas 128×212 units (the sheet
fills the frame's 15×25 px plate, ≈11×17 in.). Fitting = `measure()`: body text shrinks
through 1.0/0.9/0.8/0.7/0.6 until the blocks fit above the footer (Thomas's request
after the first cut clipped long posters; an earlier grow-downward variant left a dark
plate band under short sheets — full-height sheet with white space is what real posters
do). Bold = second text pass offset size/16.

**UI OVERHAUL (same day, Thomas: "quite bad because it's using minecraft buttons… don't
worry about it feeling minecrafty").** The three poster screens are now drawn entirely with
`client/mtraddon/FlatUi` — a flat dark design-tool kit (panes, flat/primary/danger/ghost
buttons, chips, segmented control, scroll thumbs) plus `FlatUi.TextBox`, a from-scratch text
input (word wrap, caret, shift/drag selection, ctrl+A/C/X/V, click-to-place, Enter = newline
in multi-line boxes; explicit `\n` is honoured by PosterLayout.flow). NO vanilla widgets
remain: hit-testing is a list of rectangles recorded while drawing (`hits`), so click targets
always match what was last on screen. Editor = STRUCTURE pane (Header / block cards with type
tag + snippet, ↑↓× on the selected card, "+ Add block" tile menu) · PREVIEW (live poster; click
a region to select it — `PosterLayout.Metrics.blockBounds`; selection outlined in accent) ·
INSPECTOR (header fields + line chips, text block = type segmented + wrapping textarea +
Insert-at-cursor toolbar, arrow = 3x3 direction pad, footer fields, Delete block). Ctrl+S
saves, Esc unfocuses then cancels, Tab cycles boxes. **Screens are sized for ~426x266
logical px** (a Mac at auto GUI scale — the rig client is 2560x1600 → scale 6): pane widths
drop to 104/172 when width < 560. Dev-rig hooks: lines starting `#poster-` in run/commands.txt
open the screens headlessly (`#poster-editor <id> [header|footer|<block>]`,
`#poster-list <disruptionId>`, `#poster-picker x y z`, `#poster-close`) via `PosterDevHooks`.
The rig's server gradle wrapper can die while the JVM keeps serving, after which server.log
stops updating — client_restart.sh now waits on client.log's first `[CHAT]` line instead.

**Frame block** `service_poster` (`mtr/ServicePosterBlock`, OPERATIONS tab): door-style
2-tall wall plate (lower y 1..16, upper y 0..10, z 15..16, FACING = wall behind, lower =
data half, loot half=lower). Right-click either half → `PosterSignPickerScreen` (every
poster grouped by disruption, hover preview, "Take poster down") → `addon_set_poster_sign`
C2S (distance + canPlayerModifyAt) → the BE stores the poster ID **and a JSON SNAPSHOT**
(`ServicePosterBlockEntity`, NBT `PosterId` + `Poster`). Renderer prefers the live
mirror, falls back to the snapshot — so a hung poster survives its disruption being
deleted/expiring. `PosterSignRegistry` (weak set, server) pushes edits into every loaded
frame. Verified on the rig (screenshots): empty-frame hint, snapshot-only frame,
two live posters incl. bullets/diamond/wheelchair/inline+big arrows/shrink-to-fit.
**NOT verified: every GUI** (list, editor, insert toolbar, line popup, picker) — headless
rig cannot click. Bullets rendered GREY on the rig because its routes have no
simplified routes and the dashboard data was not synced; in a real world they colour.
Rig gotcha: a chat command over 256 chars kicks the client ("String too big") — keep
`/data merge` payloads short.

### EL KIT v2 — FARE CONTROL / STAIRS in progress (2026-09-04) — READ `EL_V2_HANDOFF.md` FIRST

Stair family rounds 1-6 built with Thomas testing LIVE in the dev world (rig client as
`Rig`): stair side courses beside `subway_stairs` (railing / cream wall / glass / posts-only),
sloped stair roof, flat landing roof + steel landing deck, entrance sign under the hood,
cream/green/doorway windscreen courses, `el_roof` hip/valley/peak corners, flat item icons,
sloped hitboxes, corner posts, stairwell-rim wall placement. Play profiles are at 2.4.52;
the source tree is ahead (deploy 2.4.53 first — see handoff §1). Round detail and every
rule Thomas set are in `EL_STATION_PLAN.md` "FARE CONTROL / STAIRS" rounds 1-6. Live-test
loop: model/texture change → copy into build/resources/main + F3+T; Java change → wait for
his "restart" (Gradle kills his session).

### EL KIT v2 — PLATFORM AREA (2026-09-03) — READ `EL_V2_HANDOFF.md` FIRST — 14 blocks, ALL screenshotted in game on the rig; deployed 2.4.48 (rounds 2-8; walls/railings now in the cell OUTSIDE the platform edge after Thomas's pass: walls at the block edge, rider-side signs, lamp on railing, light runs, roof transitions, v1 items hidden — see EL_STATION_PLAN.md)

Thomas called the 46-block v1 el kit "mostly garbage" and approved a from-scratch
rebuild: **read the v2 section at the top of `EL_STATION_PLAN.md`** (his rules,
the block table, the rig recipe). Highlights: `platform_edge` OPENS MTR TRAIN
DOORS by `implements org.mtr.mod.block.PlatformHelper` (MTR's only door test,
bytecode-verified) and adopts slab/clean from neighbouring concrete floors;
`el_roof` is a self-computing gable (SIDE/LEVEL from crosswise neighbours, width
up to 7, thickness-parity trick against coplanar cell joints, crossNeg(Z)=EAST
because y=90 maps model -z to world +x); `el_post[_named]`, `el_railing[_sign]`,
`el_wall[_glass|_sign]`, `el_platform_lamp[_head]`, `el_roof_light`, `el_sign`.
Generator: `tools/gen_el2_assets.py` (never hand-edit output). v1 `el_*` ids are
still registered — delete them at the end of the section, after Thomas's pass.
**Headless rig recipe (new):** dev client hook consumes `run/commands.txt` as
chat commands (one per 25 ticks; user `Rig` is in run/ops.json) +
`run/screenshot.flag`; `./gradlew runServer` in the background then `runClient
--quickPlayMultiplayer localhost:25565 --username Rig`; forceload + tp BEFORE
fills; `sendCommandFeedback false` so chat does not hide the view. NOT yet
play-tested by Thomas: door opening with a real train, placement feel, brush
text on the signs, the hanging `el_sign` (same renderer as the standing one).

### SYSTEM MAP+ (2026-08-29) — SHIPPED 2.4.37 after four feedback rounds; NOT in-game tested

**Rounds 2–4 (read PROGRESS.md "System Map+" entries for the detail).** Thomas's
play-test feedback drove three reworks. THE RULES HE SET (do not re-ask): COLOUR
ALONE defines a line; ALWAYS schematic (tracks are reference, not truth); a
selected journey hides every other station's label; dark mode is MTA-Live; walk
speed is Minecraft's (4.3 m/s default, slider); a better route during tracking is
OFFERED in a popup, never auto-switched; scans cover the WHOLE GENERATED WORLD.

- **Schematic engine** (map.js): drawn segment = (colour, station-part pair);
  same-colour legs AVERAGE into one centreline; `truncateApproaches` cuts balloon
  turning loops BEFORE averaging (else the pair folds into a bowtie);
  `alignEndTangents` gives every arrival at a part a shared axis via rigid
  rotation (fixes the "line folds back on itself" hooks); express duplicates
  suppressed at 24 blocks/0.8; PARALLEL-OF-REFERENCE bundling — companions redraw
  as the reference's shape at their own lateral offset (literal collapse was tried
  and REJECTED: it dog-legs at stations only one line serves); `canonicalOffsetSign`
  pins a colour to one side; shade-drift merging (per-channel ≤16 → one line);
  service labels normalise number→name→raw ("IN"+"Kransfield Loop" → "Kra").
- **Whole-world scans** (Dynmap technique): chunk existence from REGION-FILE
  HEADERS (first 4 KiB, 256 B/region bitmaps, background thread) — discovery loads
  nothing, sampling never generates. Auto-runs 300 ticks after SERVER_STARTED
  (`dispatch.autoScan`), terrain then satellite, sequential; terrain skips on bbox
  containment (+64 slack), satellite diffs missing TILES only and publishes every
  64 so long scans show progress and resume across restarts. Manual
  `/dispatch satellite scan` stays a FULL refresh. Nether uses a ceiling probe.
- **Planner**: time-dependent multi-criteria label-correcting search, states are
  (platform, routeAboard); `throughRuns` (server-emitted from depot.routes order)
  lets a rider stay seated across a collapsed terminus — no wait, no transfer;
  street transfers between stations ≤120 blocks EXCLUDING adjacent-stop pairs
  (else it tells riders to walk the trunk); point-to-point via a copy-on-write
  graph overlay; step-free is a hard constraint on board/alight/transfer.
- **Live journey tracking**: `trackReduce` pure reducer + vehicle correlation
  (12→24 block hysteresis, two-tick confirm), guidance banner, loud alight alert,
  faster-route popup. GPS = player positions in the SSE frames (4 Hz);
  `?player=<name>` from the dashboard button picks the self dot.
- **Test rigs**: `scratchpad/harness.mjs` (417 checks) + `planner.mjs` (81) run
  map.js in a vm with a stub DOM — RECREATE THESE for any map work; also
  `scratchpad/render_scene.py` rasterises screen-space polylines exported from the
  live page, which is how the geometry was verified without a browser.
- **Browser-pane gotcha**: a hidden pane composites ONE FRAME LATE — a "blank"
  screenshot is stale, not a bug. Take two, or verify numerically.

### SYSTEM MAP+ v1 (2026-08-29) — the original five phases, SHIPPED 2.4.32

All five phases done in one day (read PROGRESS.md "System Map+" for details).
Mock v2 approved by Thomas (tools/nextmap_mock/, :8792) → server data
(`/dispatch/api/mapdata` + `/dispatch/api/terrain` in DispatchApiServlet;
DispatchMapData legs are PAIR-KEYED off the baked pathMainRoute because the
cycle is ROTATED — never assume it starts at a route's first stop; TerrainScanner
= `/dispatch terrain scan`, 2 ms/tick server-thread water scan, verified with a
placed pond) → frontend `dispatch/map.html|js|css` (light paper theme, merged/
offset ribbons, split-station parts, direction-aware label declutter, selection
fade+glow, ?demo=1 acceptance harness + scratchpad vm harnesses) → planner
(single label-correcting multi-criteria search, (platform,routeAboard) states,
live-vehicle departure projection → headway phase → 5 min estimate; step-free
HARD on board/alight/transfer; ride edges DIRECTED — return trips need return
routes) → entry points (dashboard row 3-way split incl. "Map+"; dispatch topbar
link). Deployed to both profiles; awaiting Thomas's play pass: dashboard button,
real-network mapdata joins, `/dispatch terrain scan` on Baker City, river
accuracy, live journeys.

### NYC ELEVATED STATION KIT (2026-08-28) — 28 new blocks, both phases; NOT in-game tested

**FIVE-AGENT QUALITY PASS MERGED (2026-08-28 late): all five el item sets
reworked in parallel opus worktrees, merged in order (structure/roofs/walls/
street/signage), one generator conflict (stair_canopy owned by two briefs —
set 4's version won), compile green first try. Read EL_SET{1..5}_REPORT.md
for per-block placement rules + proposals. Highlights: girder got real
flange/web/stiffener anatomy + knee-brace bug fixed (was buried inside the
column); BRACED instanceof bug grew gussets on canopy/portal posts — now an
explicit list; ElDeckBlock outline + cross-type culling fixed; GABLE is now
multi-width (slope/wing/crown profiles by crosswise neighbors — 3 rows = one
Marcy roof; flat-vs-gable no longer merge across families); flat fascia hangs
under the eave; post brackets actually reach the roof; glass windscreen WAS
INVISIBLE (all alpha < cutout threshold) — rebuilt; classic screen is
course-aware (solid below, glazing on the top course via UP); stack-boundary
sliver fixed; railings read round; house window is a real double-hung;
stair side screen re-registered to the actual stair profile (rail was 3px
above treads); stair canopy has a flashing tongue for the platform-canopy
joint; portal post 3.6px with symmetric brackets (post+header orientations
now agree); name board got MOUNT standing/wall/hanging with renderer plane
math updated; EXIT sheet 64px readable arrows; tactile edge = staggered
domes + overhang lip; SILVER LAMPS registered (3 new ids, 46 el blocks).
Renderer in tools/render_blockmodel.py now has a depth buffer. NOTHING
in-game tested — placement conventions are documented per block in the five
reports and need Thomas's play pass.**

**Read EL_STATION_PLAN.md** — photo research (image sweeps: 125 St viaduct,
Jamaica Ave, Marcy Av, W 8th St, Bay Pkwy, Astoria rebuild), Thomas's decisions
(hand-placed blocks only, no creator tool; green + galvanized + station-tint +
station-named; all four windscreens; PREVIEWS BEFORE CODE — he required render
previews of every model/texture before anything landed, approved both phases
from them, asked for beefier braces + texture depth + modularity for any
platform size + PIDS-hangability + concrete floors as the walking surface).
All assets by `tools/gen_el_assets.py` — never hand-edit; verify() enforces
props/uv/rotation contracts. STRUCTURE: el columns (solid + see-through
lattice; ColumnBlock UP/DOWN reuse: street base / gusset cap at stack ends;
named variants ride StationColumnBlock + a new per-block boardOffset — the
renderer's column board plane is no longer hardcoded 0.25), plate girder +
lattice truss (ElGirderBlock: AXIS + BRACED — over any ColumnBlock the model
grows 3-step 45° knee-brace gussets reaching into the column block), tie deck
(see-through, deep stringers to y0 so it SITS on girders, top-slab collision)
+ full-cube plate deck (ElDeckBlock overrides isSideInvisible, glass rule).
PLATFORM: 4 windscreens + 2 railings (ElScreenBlock left/right shared-post
runs), stacking canopy posts (brackets only at stack top), flat/gable
canopies (ElCanopyBlock AXIS + NSEW: fascia girders on open edges, gable end
plates on open ridge ends; authored LOW so they land on posts; centre
purlin/tie chord = steel for hanging PIDS stubs), el_name_board (BE +
paintElNameBoard, auto station name). Tint providers + cutouts registered in
MtrPidsClient; 5 tinted blocks. The scratchpad offline renderer
(render_blockmodel.py + render_el*.py) drove the whole preview loop —
recreate it for future model families. NOT verified in game: everything
(placement connectivity, cutout layers, tint, name boards, collision feel).
PHASE 3 same day (previews approved; +16 blocks, 44 el total): street stairs
(ElSlopeBlock 45° rescale pieces), entrance portal (post+header), station
house walls/windows/soffit, wood platform, tactile
platform edge, EXIT sign (ElExitSignBlock mount+arrow cycle), goose-neck +
lamppost (pole+head blocks), gable end upgraded to open truss. QUALITY
PASS same day (Thomas: "half-baked"): agent booth REMOVED, window walls
rebuilt as real see-through framed glass (el_glazing cutout, nonOpaque),
windscreens FULL-HEIGHT + STACKABLE (ElScreenBlock UP/DOWN, rail/kick
models at stack ends), gable rebuilt at 45° pitch (rotation sign rule:
+45 about x descends toward +z — see EL_STATION_PLAN.md), stale models
deleted, full asset audit clean. Generator
module tools/gen_el_phase3.py called from gen_el_assets.build(); PROPS
merged for verify. ALSO: the purple-texture postmortem — pngtool.write_png
emits px[:4] bytes vs an RGBA header, so RGB 3-tuples corrupt every PNG;
gen_el_assets.wpng() normalizes at the single write point. Validate PNGs by
IDAT length, NOT pngtool.read_png (limited decoder, false negatives).

### TURNSTILE ROUND 2 (2026-08-19 evening) — fare feedback + photo-grade tubing; NOT yet deployed

**Fare feedback**: passing a turnstile now action-bars the rider
"Entering <Station> — Balance: $X" / "Leaving <Station> — Fare: $Y · Balance: $X"
(user picked the full format). Direction = MTR's entry-zone scoreboards
(`mtr_entry_zone_1..3`, read via vanilla Scoreboard.getScore BEFORE passThrough — it
rewrites them); fare = balance delta; station = our own NEARBY_STATIONS
`Init.sendMessageC2S` request in the fare callback (same call passThrough uses, so the
name always matches what MTR charged; radius 0, callback lands on the server thread).
MTR names are "Eng|Other" — firstLang() before display. Verified END-TO-END on the dev
rig: turnstile placed inside a station's AreaBase bounds (station corners read from
run/world/mtr msgpack — `pip3 --user msgpack`), dev-client player tp'd through both
lanes, screenshots show both messages. Station bounds span ALL y — a sky platform
inside the x/z rectangle is inside the station.

**Tubing rebuilt from the 86 St photos** (user: "examine real photos"): slim 2 px
OCTAGONAL pipe — every tube segment is a core box plus a 45°-rotated twin (element
`rotation`, the standard round-pole trick; rotated faces cut the core's corners so
nothing is coplanar) — square cast-elbow FITTINGS at every corner (slightly fatter
than the pipe, like real pipework), a mounting collar at each riser foot (riser down
face omitted against the pillar cap — vanilla-fence precedent), and the photos' lane
signboard hanging under the rail: green ENTRY↓ on `turnstile_tube_bridge`, red EXIT↓
on the new `turnstile_tube_bridge_exit` (exit blockstate only). Riser/cap poles
standardised at x 2..4 so bridge overshoots bury inside the next unit's fitting with
0.05 cross-section insets (no coplanar planes across blocks). Sign textures are 32×32
— **MC rejects non-square block textures** (32×16 failed the sniff test before it ever
loaded). All in gen_turnstile_assets.py; regenerate, never hand-edit.

**Deploy state**: shipped 2026-08-23 together with the subway stairs (deploy_jar.sh
ran clean once the game was closed; both profiles cmp-verified). Version stayed
2.4.11 across three successively fatter jars — the cmp check is what makes that
safe; bump the version next time anyway.

### TURNSTILE OVERHAUL + CRASH POSTMORTEM (2026-08-19) — v2.4.11 SHIPPED to both profiles

**Two 2026-08-18 crashes were NOT code bugs**: both were `ZipException: invalid LOC
header` from jars swapped into the Essential mods folder while the game was running
(TurnstileBlock$Crossing on first turnstile collision; PlatformPicker on first
PIDS-screen click). Fix = `tools/deploy_jar.sh` (see deploy rule above). The 2.4.11 jar
was deployed once Thomas's game closed (the guard refused while it was open — working
as designed).

**Turnstile family reworked** (user chose: indicator lights + feedback, polish pass,
new variants — explicitly NO animated arm; keep the current design language).
Assets now GENERATED by `tools/gen_turnstile_assets.py` (textures 32px, models,
blockstates, item icons, loot, recipes — never hand-edit; verify() enforces the
purple-box when-key contract + explicit-uv-on-every-face + on-sprite windows):
- `TurnstileBlock` gained `INDICATOR` (off/go/stop EnumProperty) + an `allowEntry`
  ctor flag. The fare callback (MTR calls it back on the server thread — its own
  barrier sets blockstate there) lights a lens on the UPPER half's reader pillar,
  schedules a tick to clear it (MTR's set-in-callback + scheduled-tick-reset pattern),
  and action-bars the rider's balance (`gui.station_announcer.turnstile_balance`).
  Lamps are three separate models (off/go/stop) picked by blockstate — the upper
  model carries no lens, so nothing z-fights.
- `turnstile_exit` = same class, `allowEntry=false`: everyone is processed as exiting;
  `remindIfNoRecord=true`, so a walk-up with no entry record gets MTR's reminder + red
  STOP light + TICKET_PROCESSOR_FAIL, NOT a fare-evasion fine. Its pillar's approach
  face carries a white-on-red no-entry bar (`turnstile_face_exit`); FACING = unpaid side.
- `turnstile_heet` (`TurnstileHeetBlock`) = full-height "iron maiden": two-block cage
  (posts + vertical bars, inset 0.25 from block edges), 4-wing comb rotor, roof plate
  sitting DIRECTLY on the frame (a floated plate showed a daylight band — everything
  under the plate omits its up face), lenses on both approach faces of both posts.
  It `joinsRow() == false` (new TurnstileBaseBlock hook, connectsRight is now an
  instance method honouring it) so neighbouring tube runs cap against it instead of
  bridging in. Only the side frames carry collision — the lane stays walk-through.
- Cabinet polish: proud dark plinth (body's down face omitted against it), stepped
  dome deck (dome inner faces omitted where buried under the lid), brushed-steel
  32px texture, rubber tips on both arm bars, reader pillar with screen/card
  slot/yellow tap target on both front strips.
- VERIFIED on the scratchpad rig (fifo server + dev client screenshots): all three
  blocks + cap render clean at all lamp states, tubes bridge/end correctly, exit bar
  reads, loot drops exactly one from either half, upper↔lower break propagation,
  HEET row isolation. NOT yet verified live: the callback-driven lamp + balance
  action bar (needs a real MTR station walk-through — first thing to watch in game).
  A "floating wireframe" above the HEET in test screenshots was just the vanilla
  selection outline (crosshair on the block) — not an artifact; don't chase it again.

## Current state: v2.4.0 shipped (station suite, trainless); M7 continues toward 2.5.0

### BUG-FIX + QOL SESSION (2026-08-09..11) — signs, markers, subway walls, config, dispatch fixes

**THE STALE-JAR TRAP BIT US.** Another session bumped mod_version 2.4.6→2.4.8 mid-work
and this session's `cp build/libs/station-announcer-2.4.6...` kept shipping an Aug-9 jar
for hours. **Before copying a jar to the play profile, read mod_version from
gradle.properties and copy THAT file** — never a hardcoded name.

**DEPLOY ONLY WITH `tools/deploy_jar.sh` — NEVER `cp` a jar into a mods folder by hand.**
The script reads mod_version, deploys to BOTH play profiles (official-launcher
"1.20.4 Fabric Essential" + ModrinthApp "Baker City 1.20.4"), byte-verifies with cmp,
deletes other-version duplicates, and — the part that crashed Thomas TWICE on 2026-08-18 —
**refuses to run while Minecraft is open**: replacing the jar under a running JVM leaves
the zip central directory stale, so the next lazily-loaded class (TurnstileBlock$Crossing
at 21:31, PlatformPicker at 23:09) dies with `ZipException: invalid LOC header` and takes
the game down. If the script refuses, ask Thomas to close the game — do not override it.

**Entrance railing sign reworked** (`RailingSignScreen` + `RouteBullets`/`RoutePicker`,
`update_railing_sign` C2S). The panel is a SOLID black slab (the old two facing quads let
balusters show through between them = "floating text"); slab lids overshoot y by ~0.3
canvas units because the balusters end exactly at model y6/y18 (coplanar lids z-fight).
Each face is independently on/off and carries its own ordered route-bullet list (stored
as MTR route NAMES — repainted/renamed lines follow along; unknown names draw grey).
`RailingSignBlock.frontOf(state)` owns the face-direction rule; faces are named by
compass direction in the GUI. Merged runs: a face off on ANY segment turns it off for the
panel; first segment with bullets wins (the custom-name rule).

**ATLAS-BLEED (PINK LINE) LESSON — and an audit for it.** A face whose auto-UV window is
a sliver (<1 texel) pressed against the sprite edge (outside texel centres 0.5/15.5)
samples the NEIGHBOURING atlas sprite: a 0.1 px-deep wall pad drew a 1 px magenta line
with zero log warnings. Fixed with explicit interior UV slices; same latent bug fixed in
`railing_post`/`railing_item` (uv [0,0,6,0.5] → v 8..8.5). An audit replicating vanilla's
auto-UV rules over every block model reports 0 sliver faces — re-run the idea when adding
models with thin elements.

**Stop markers**: the wall bracket floated with one plate because the arm sat at y 7..9
while plates hang from the TOP (y 12..16) — the arm now holds the top plate (y 13..15),
which always exists. New BLADE style (third `Style` beside flush/bracket; `BLADE`
BooleanProperty kept SEPARATE from `standoff` so old markers keep their value): plates
turned 90°, centred on the block's centre line in BOTH axes (renderer rotates the frame
90° — no second geometry; stub = exactly a pole's cross-section x/y 7..9), legends drawn
on BOTH faces, stack centred so 4 plates fill the block exactly. `stop_marker_pole` is
now a `block/PoleBlock` (AXIS property like a log; placement takes the clicked face's
axis; old placements default axis=y).

**Globe lamps fixed** (`tools/gen_globe_assets.py` — generated, never hand-edit): the
green/red tops rendered yellow because auto-UV maps horizontal faces by FOOTPRINT (u=x,
v=z), so every up/down face of the 5-box orb sampled rows 4..12 regardless of height.
Every horizontal face now takes an explicit one-row slice AT ITS OWN HEIGHT (row 16−y,
matching what auto-UV gives the side faces), texel-centre to texel-centre. Textures are a
vertical gradient: colour above, white-yellow lamp glow below; edge shading confined to
the outer 2 columns which the slices never touch.

**SUBWAY TILE WALLS** (`tools/gen_subway_wall.py` — generated; registration self-contained
in `mtr/SubwayWalls` + `client/mtr/SubwayWallsClient`, one call each from
MtrStationDecor/MtrPidsClient). Grid: FOUR TILES PER BLOCK at 32 px (8 px tile, 1 px
grout — 16 px would force a heavy quarter-tile line). 8 blocks: white wall (+fresh),
station-colour band, painted black, 2 alcoves, no-clearance stripe, name tablet.
- Band = THREE stacked elements (white tile row / 2 tinted rows / white tile row) because
  a quad has one tintindex; tint reuses `MtrPidsClient.stationColor` (now package-visible,
  `FALLBACK_STATION_COLOR`).
- Name tablet (`TileTabletBlock`+BE+renderer+screen, `update_tile_tablet` C2S): letters
  drawn as INDIVIDUAL black tiles inset by the grout (2 canvas units = the texture's 1 px
  line), one letter per tile, word centred on the block, snapped to the grid, ROW 0-3
  blockstate property picks the tile row. Font: size derived from CAP height (vanilla ink
  is 7 of the 8-unit box), centred on INK not the type box (measured width includes the
  spacing column), and the whole alphabet widened 7/5 (a capital is 5×7 ink) so standard
  capitals get equal margins all round; narrow glyphs stay narrow.
- Alcove: back+jambs only, open top/bottom (stack 2 = walk-in niche), half-block deep,
  tiled + black variants. No-clearance stripe: model 15 px tall but FULL-CUBE collision
  (reads as a set-in painted band, still spaces the block); stripes 16 px period (divides
  32), true 45° verified per-pixel, NO shading (any banding breaks the diagonal).

**Config screen + Mod Menu + keybinds**: `StationAnnouncerConfigScreen` (all client
settings; working copies, nothing written till Done; links to DrivingHudScreen and
KeybindsScreen — yarn 1.20.4 spells it `KeybindsScreen`), `modmenu` entrypoint
(`modCompileOnly maven.modrinth:modmenu` — jar stays standalone, Fabric only loads the
entrypoint when Mod Menu is present), `StationAnnouncerKeys` (settings + TTS-mute keys,
both UNBOUND on purpose). ClientConfig gained `persist()` and `chimeCategory`.

**CHIMES WERE SILENT AT RANGE — two stacked bugs, not the ogg files** (all 11 decode
fine): (1) the chime played as a positional sound at the block, so vanilla's ~16-block
rolloff attenuated ON TOP of the server's radius-based volume — beyond ~16 blocks of a
128-radius PA, silence; now AttenuationType.NONE at the listener at exactly the server's
volume (the ambience block's pattern). (2) it was on the VOICE slider (players turn it
down; the dropdown preview used MASTER, which is why previews worked) — now a config
choice, default master.

**Holding lights are binary** (user rule: solid on or solid off — no flashing anywhere).
`HoldIndicator.FLASH` renamed `HELD` (ordinal preserved — the mode is stored by ordinal,
so renaming is safe, REORDERING would re-point every placed light).

**HOLD RULES: one hold per stop.** The engine re-asked "anything approaching?" every tick,
so on a busy watched platform the hold chained connection-to-connection until the 120 s
deadlock cap. Now the ONE arrival to wait for is chosen when the hold starts
(`HOLD_STATE` = {started, lastSeen, platformId, awaitedArrival, served}); release at
awaited+transfer, then the stop is SERVED and cannot re-hold until the vehicle has been
away (different platform = instant reset, same platform = 30 s gap). Cap stays as
backstop for cancelled connections.

**PLATFORM GROUPS: the empty-PIDS one-direction bug (root cause + fix).** MTR's trip
builder (`Siding.generatePathDistancesAndTimeSegments`, bytecode-verified) walks the
baked path's dwell segments IN LOCKSTEP with the depot's cached `platformsInRoute`,
matching platform ids — ONE disagreement and every stop after it gets no
platformTripStopTimes entry: arrivals dead for the rest of the route cycle (= one whole
direction) on every PIDS, trains unaffected. Cache/path drift had two sources: rotation
advanced + choice recorded at generation START (an aborted generation recorded a choice
for a path never baked), and the cache re-apply re-checked collision guards and silently
fell back to originals. Fix in `PlatformGroupEngine`: (1) choices+counters stage in
PENDING, committed at the departures writer (only callers: finishGeneratingPath + init —
success only; also = failed generations no longer skip rotation members); (2) re-apply is
VERBATIM (guards belong to generation only); (3) re-apply verifies each choice against
the platforms the baked path actually dwells at (`SidingPathAccessor` on
`Siding.pathMainRoute`) and drops stale ones — self-heals data.json written before this.
Also: collapsed terminus stops carry BOTH attributions (inbound-last + swallowed
outbound-first) so a group configured on either side of the route boundary works.

**Terminal alternation recipe** (per-train choice is impossible — one shared path per
depot; this is the faithful version): two depots, same route, sidings split between them,
both in a DEPOT GROUP (member 2 shifts half a headway), platform group of the two
terminal tracks on the terminus stop. Per-depot rotation lands them on different members,
deterministically (sorted by depot id). **The stagger derives from FREQUENCY SLIDERS** —
offset is 0 (lockstep timetables, paired PIDS times) for real-time departures,
continuous movement, or no frequency. New **Refresh Offsets** button on the depot-groups
screen (`REFRESH_DEPOT_GROUPS_C2S`, permission-gated) recomputes + rewrites departures
NOW — needed after frequency edits, which the addon cannot see.

**Session audit also fixed**: no_clearance_stripe missing loot+item model (the
generator's BLOCKS list drives both — a block left off it drops NOTHING); missing
`railroad_pids.platforms_hint` lang key; per-frame allocation in the new renderers
(RouteBullets memoizes finished bullets, TileTabletRenderer memoizes upper-casing).
Diagnosed via world data at `<save>/station-announcer-addon/data.json` — reading it
directly beats guessing (found the stale platform-group choice, the never-expiring
disabled stop, the active disruption).

**NOT play-tested**: entrance-sign faces/bullets in game, blade markers against a
horizontal pole after the centring fix, subway walls (all of it), tablet font, config
screen, Mod Menu entry, chime fix at range, one-hold-per-stop, the platform-group fix
under rotation (user confirmed groups OFF cures the dead direction; the FIX itself is
unverified), Refresh Offsets button. User confirmed working: depot-group stagger +
alternating terminal tracks, platform groups as culprit.

### TRACKSIDE / ENTRANCE SIGNAGE (2026-08-09)

**REBUILT FROM THE PHOTOS (2026-08-10).** Both variants were one 16x16 box with
`pids_frame` stretched over every edge (`uv [0,0,16,3]`) — a floating panel, not a sign.
Now:
- The plate texture carries a **black top cap** (rows 0..5), and the box edges take REAL
  SLICES of that same plate: a vertical strip from its left margin is black across the cap
  and red below, which is exactly what the side of the box looks like in the photo. One
  64 px texture skins the front, both sides and the cap — no separate edge texture.
- **Wall variant** is a 14x14 plate standing **1 px** proud of the wall (2026-08-17, user:
  "should be only one pixel fat"; it was 3.5 px, which read as a cabinet), `cullface:
  south` on the back.
- **Gate variant** is a real hinged gate: a **2 px** steel post (was 3 px), two steel
  hinges, and a leaf that SWINGS on right-click. `block/TrackWarningGateBlock` (common —
  no MTR needed) with FACING + OPEN + **HINGE** (vanilla `Properties.DOOR_HINGE`);
  it implements `GateSection` so it drops into an ironwork run.
  **The hinge side is a placement decision** (2026-08-17): the post goes on the half of
  the block the player clicked, so two gates can meet in the middle of an opening. Model
  side: `mirror_x()` in the generator produces the right-hand closed/open models from the
  left ones — geometry mirrors and east/west face NAMES swap, but the uv stays AS
  AUTHORED: face uv is screen-oriented, not geometry-tied, so flipping the u range is
  exactly what MIRRORS the lettering (the first cut flipped u; the in-game right gate
  read "RETNE TON OD" until 2026-08-18).
  Java side: the model's local +X is `facing.rotateYClockwise()`, and the outline's post
  box is rotated by the SAME y rotation the blockstate applies (`mapXZ`: y90 → (16−z, x),
  y180 → (16−x, 16−z), y270 → (z, 16−x)) — a hand-picked per-facing cuboid gets this wrong.
  Also: `verify_blockstates()` now runs at the END of `main()`; it used to run inside
  `write_models()`, i.e. before the warning-sign blockstates were rewritten, so it only
  ever checked the PREVIOUS run's file. Closed it blocks the walkway; open, collision drops to nothing (the post
  keeps only its outline). The open model is a true 90° rotation via `swing_about()`, a
  generalised version of the exit door's swing that takes the hinge line as an argument —
  the leaf pokes ~4.5 px into the next block, which is what an open gate does.
- The blockstate contract check now covers plain `variants` files too, not just multipart.

- `track_warning_sign_wall` / `_gate` — the red "Do not enter or cross tracks" plate.
  The GATE variant sits on the block CENTRE plane (z 6..10), the same plane
  `GateWallBlock` uses, so it drops into a run of dividing walls and reads as part of the
  fence; the wall variant hugs the wall behind (z 15..16, one pixel deep). Texture generated by
  `tools/gen_gate_assets.py`. The roundel is a RING with a slash, not a filled disc with a
  hole — at 64 px a hole closes up in the mip chain.

- **The no-entry tile** (`RouteBullets.NO_ENTRY = "!no_entry"`). Signs persist plain route
  NAMES, so the roundel rides the existing storage and packets with **no schema change** —
  the `!` prefix cannot collide with an MTR route name. `bulletFor` returns the MTA red
  with an EMPTY label (a letter on the bar would read as a route bullet in the wrong
  colour); `CanvasPainter.prohibitionBullet` draws disc + white bar; `RoutePicker` offers
  it first and everywhere (a no-entry sign is most useful exactly where the sign is NOT in
  a station area), and its list row draws the bar rather than a blank red disc.
  Putting "No Entry" on a station entrance sign is then just: pick the tile, and set the
  custom name to the wording you want.

### EMPLOYEE DOORS + WALL CORNERS (2026-08-18) — built, compile-verified, NOT play-tested

**Employee doors** (`mtr/EmployeeDoorBlock`, three blocks: `employee_door_mesh` /
`_black` / `_white`): fixed 2-tall staff doors that NEVER open (no OPEN state, solid
collision — "a locked door is a wall with hinges"). GateSection, so they drop into
bar/grille runs with the shared-post rhythm (unconditional left post + closing right
post, like the exit door). Mesh = wire panels top AND bottom (cutout, registered in
MtrPidsClient beside the exit door); black = solid; white = off-white paint
(`employee_door_paint` texture, tile-wall tones, white posts too so it blends into a
tiled wall). **The label rides the existing decor machinery**: the blocks are in
DECOR_BLOCK_ENTITY, the BE is a plain StationDecorBlockEntity on the LOWER half, so the
MTR brush → GUI_OPENER → StationSignScreen → update_decor packet all work UNCHANGED.
onPlaced writes "EMPLOYEES ONLY" into CustomName; clearing it = blank door (the
renderer's door branch returns before the station lookup, so "" means blank, not
auto-station-name). Label plate drawn by StationDecorRenderer.paintDoorLabel at eye
level on both faces, plate+border+text fitted. Assets generated by gen_gate_assets.py;
loot half=lower, recipes iron_door + bars/black_dye/white_dye.

**Wall corners — fence logic** (user request): `GateWallBlock` gained
`BEND` = none|front|back. A perpendicular GateSection against a cell's front/back turns
that cell into HALF-ARMS meeting at a square centre post (`gate_corner_post`,
POST_Z² footprint; arm models authored toward WEST — `gate_arm_rail_*`,
`gate_arm_bars_infill` (one upright at pitch 4), `gate_arm_grille_infill` — rotated
+0/+90/+180/+270 for left/front(bend)/right/back, vanilla y-rotation steps
west→north→east→south). LEFT/RIGHT now also count a perpendicular bend cell whose arm
points back at us (`runContinues`), so the straight neighbour keeps its panel to the
boundary instead of closing with a post; convergence happens over the normal
neighbour-update cascade in either placement order. T-junctions work (left+right+bend);
a 4-way cross keeps only the front arm (enum can't say both — known limit). Outline for
bend cells is the L-union. **Migration: zero** — every old property still exists, BEND
defaults to none, straight runs render exactly as before. Multipart: every straight part
gained `bend:none`; ~30 arm selectors added per wall.

### FARE-CONTROL GATES (2026-08-09) — BUILT; alarm/stacking server-verified, visuals NOT seen

Three blocks from the user's photos: `emergency_exit_door` (MTR module — it needs
TicketSystem), plus `gate_scroll` and `gate_grille` (common). All in the DECORATION tab.
Assets generated by `tools/gen_gate_assets.py` — never hand-edit.

**COMPLETENESS PASS (2026-08-10, self-audit) — all server-verified in one run:**
- **Loot dupe fixed**: the door's loot table predated the two-half rewrite and dropped one
  item PER HALF; now gated `half=lower` like every other 2-block multiblock. Verified:
  breaking the lower half drops exactly one item (scoreboard-counted).
- **Redstone = vanilla iron-door semantics**: power OPENS the door (which sounds the
  alarm, since opening always does), losing power closes it; either half's power counts.
  The old behaviour (alarm without opening) read as broken to anyone wiring it.
- **POWERED is now the redstone input; the alarm rides a new ALARM property**, and the
  blockstate finally shows it: a strobe lamp on the hinge post above the door (dark
  maroon at rest, blazing + hot core while sounding). The lamp is on the POST piece, so
  it stays put when the leaf swings.
- **Flat 2D item icon** for the door (the 3D lower-half model read as half a door in the
  slot); fine now plays ENTITY_VILLAGER_NO beside the action-bar message.
- The blockstate contract check now runs INSIDE gen_gate_assets.py on every regen.
- **2.4.8 doors do NOT survive into 2.4.9** (POWERED semantics changed, ALARM added —
  states reset; 2.4.7 stacked doors were already lost to the HALF/OPEN rewrite).
- Ships as 2.4.9. Repo pushed to a PRIVATE GitHub repo (thomasdemuth) this session.

**THE PURPLE-BOX BUG (2026-08-10, found by the user in game).** When the grille gained
its every-third-block POST property, the shared multipart builder put a `post=true`
condition on the hinge post for ALL sections — but the DOOR has no `post` property, and
one when-condition naming a property the block lacks makes the client reject the ENTIRE
blockstate file: every door rendered as the purple missing-model checkerboard. The fix is
one line (the door's post is unconditional), but the lesson is the check: the asset
verifier now asserts every when-key/variant-key in a gate blockstate against the Java
block's declared properties — model-file existence alone missed this completely. Ships in
2.4.8 (bumped so the stale-jar trap can't hide it).

**THIRD PASS — the door rebuilt as a material assembly (2026-08-10, Fable taking over
after two failed door models).** The painted-leaf approach was the mistake both times: a
flat box with the whole design on one 64 px texture reads as a sticker no matter how the
geometry around it improves. The door is now built the way the photos are built:

- **Lower half:** solid stainless kick panel (turnstile_steel), iron rail behind the bar
  zone, the red PUSH BAR sign plate proud, and the crash bar outermost on two brackets.
- **Upper half:** wire-mesh window (gate_grille texture, cutout — the door now needs
  getCutoutMipped, registered in MtrPidsClient), red EMERGENCY EXIT header plate proud,
  top rail. Stiles both sides on both halves.
- **Signs face SOUTH (the paid side)** — you read them as you push your way out; FACING
  is the unpaid side and the leaf swings that way.
- **Sign plates carry their own textures** (gate_door_sign_header 128x40, _pushbar
  128x32, `sign_plate()` in the generator) so the FULL wording fits — "PUSH BAR FOR
  EMERGENCY EXIT / ALARM WILL SOUND" — where the old shared 64 px plate could only fit
  "PUSH BAR / ALARM SOUNDS". The painted emergency_exit_door.png is deleted.
- **The open model is a real 90° swing** of the closed elements (`swing()` in the
  generator: point map about the hinge at the shared post, faces remapped
  north→west→south→east). The previous "open" model was a separately-authored slab of
  different proportions — that is what read as the door warping. Swung boxes leave the
  0..16 range, which is legal but REQUIRES the explicit uv every door face now carries.
- **THE COLLISION BUG:** getCollisionShape was never overridden, so the door was solid
  even when open — nobody could walk through, and onEntityCollision (the fare-evasion
  detection) could never fire, silently. Closed → slab, open → empty. Verified live: an
  armor stand summoned inside the open doorway stays put; all four state tests pass.
- Leaf spans 0.5..15.5 so its ends bury INSIDE the 4.5..11.5-deep posts (a 1..15 leaf
  left a visible 0.2 px gap; a leaf ending at 0.8 would be coplanar and z-fight).
- Ten orphaned models from the stacking era deleted (they referenced the deleted texture
  and would have warned on every resource load).

**SECOND PASS after the user's screenshot ("still looks super sloppy"):**
- **Uniform bar spacing.** Uprights sit on a pitch of 4 (which divides 16) and the boundary
  one STRADDLES x=0 at -0.8..0.8. Sitting it at 0..1.6 instead makes the joint gap 1.6
  against 2.4 inside a block; the first version's bars ran 4.2..13.6 and gapped **6.6 at a
  joint against 1.0 inside**, which is what read as sloppy. Bars are 1.6 px, gaps 2.4 px
  everywhere - asserted in the generator, not eyeballed.
- The post is the SAME WIDTH as the bars (a fatter post pinches the gaps beside it and the
  rhythm breaks) and is distinguished by DEPTH instead - 7 px front to back against 2.5.
- **Mesh spans the full block** (0..16, was 3..13, which left a 3 px hole at each end) and
  the grille posts only every THIRD block via a POST property computed from distance along
  the run, so the mesh runs unbroken for up to three blocks. The bar fence posts every
  block, because there the post is part of the bar rhythm.
- **The door is a fixed 2-block door again**, HALF + OPEN, right-click to swing, breaking
  either half takes the other. Stacking let the leaf art warp over however many cells it
  was given. Opening it is itself what sets the alarm off. The crash bar swings with it.

**REBUILT IN 3D (user: "they are horrible, try again from scratch, both 3D").** The first
cut was a 4 px slab with the ironwork PAINTED on two faces — it read as a sticker. Now the
frame is real geometry and the textures only skin it:

- **Posts** 3x3x6 px at the block edges, **rails** 2 px deep at top and bottom, **balusters**
  four 1.6 px uprights on a shallower plane than the posts. The frame standing proud of the
  infill is what makes it read as structure rather than a printed panel.
- The **ornate scrollwork was dropped** (user decision): curves at this scale quantise into
  blobs whichever way they are drawn. `gate_scroll` is now a plain iron BAR fence — the id
  is unchanged, only the geometry and its lang name ("Iron Bar Dividing Wall").
- **SHARED POSTS.** Every section draws its LEFT post and only a right-hand end closes the
  run, so a joint shows one post, not two jammed together. That needed horizontal LEFT/RIGHT
  properties the first version lacked, and a `block/GateSection` marker interface so the
  door (MTR package, it charges fares) and the walls (common package) can see each other.
  Any section joins any other, so bars → door → grille is one continuous fence.
- **Only the push bar stands proud** of the door leaf (user's choice), on two brackets.
- **Multipart, not one model per state**: the frame is five independent decisions, and
  baking all 16 combinations per variant would be 32 near-identical models. Cost is
  1,280 selector-states per wall and 4,608 for the door — nothing next to the pipe's 16.6M.
- Vertical-only shading on `gate_iron` (a horizontal band repeats on every stacked block —
  the globe-pole lesson).

Verified on a live server: a wall|door|wall run resolves left/right correctly at both ends
and across both joints; boot 1.42 s.

**Behaviour, all verified on a live server:** stacking resolves UP/DOWN correctly across a
3-tall run (bottom/middle/top); a redstone rising edge starts the alarm; POWERED
propagates to the whole stack; it times out after 15 s and clears the whole stack. Only
the BOTTOM cell keeps time and plays the sound, so a 4-tall door is one alarm, not four.
The 2 s loop is re-issued every 38 ticks so there is no gap between repeats.
**The alarm being audible is NOT verified** — a dedicated server does not log playSound.

Exit-only: the door faces the unpaid side, so velocity along the facing is an exit
(alarm only) and against it is fare evasion (alarm + a $100 TicketSystem fine, action-bar
message). Never blocks movement — the turnstile's precedent.

What exists:

- `textures/block/emergency_exit_door.png` (64px), `gate_scroll.png`, `gate_grille.png`
  (64px), all CUTOUT — they need `getCutoutMipped()` at registration like the platform
  barrier. Generated by `tools/gen_gate_assets.py`, never hand-edit.
- `sounds/gate_alarm.ogg` — 2.0 s seamless LOOP, played repeatedly rather than baked to
  length. Rapid 5/sec pulses ALTERNATING 1180 Hz / 880 Hz (the user asked for beeping and
  a hi-lo whoop at once), odd-harmonic piezo tone. Still needs a sounds.json entry and a
  `SoundEvent`. **The user has not heard it yet.**

**CONFIRMED DESIGN (do not re-ask):**
- All three STACK: one unit per block, auto-merging into a run like the entrance railing.
  The door draws its push bar and panel on the bottom cell and the EMERGENCY EXIT header
  on the top; the walls cap their top cell. The two walls share a frame (posts at the
  block edges, rails at matching heights) so scroll and grille runs meet cleanly.
- Alarm: trips when a player passes through, runs ~15 s then stops on its own, also
  fireable by redstone. **Heard by everyone nearby**, not just whoever tripped it.
- The door is **exit only** and does NOT physically block movement (the turnstile's
  precedent — walking through is what triggers the behaviour).
- **Fare evasion:** a player entering the WRONG way (unpaid side -> paid side) is fined
  through MTR's `TicketSystem`, the same API the fare machine and turnstile already use.
  Exiting the intended way sounds the alarm but costs nothing.
  ASSUMPTION TO CONFIRM: fine amount. MTR's own rate is 1 emerald = $10; a real NYC
  evasion fine is $100, so $100 unless the user says otherwise.
- These are DECORATION-tab blocks: furniture you build with, same call as the turnstile.

**TEXTURE LESSONS (three passes to get the scrollwork right):**
1. At 32 px the arcs quantise into blobs — the wall textures are 64 px.
2. Centre the ring motif on the block EDGES as well as the middle. Halves complete against
   the neighbour so a run reads as continuous ironwork; the whole motif in the middle
   stamps an identical medallion on every block and reads as wallpaper.
3. Keep the decorative band CLEAR of the uprights. Running them through chopped every arc
   into dashes and the panel read as brambles.
4. The 3x5 font is 4 px per character, so 16 characters is an entire 64 px texture with no
   margin — the door's "Push Bar for Emergency Exit / Alarm Will Sound" had to become
   "PUSH BAR / ALARM SOUNDS". Drawing it as renderer text on a canvas would allow the full
   wording and stay crisp close up; not done.

### DEPARTURE BOARDS (2026-08-09) — 3 new PIDS blocks from the user's Penn/Moynihan photos

- `railroad_departure_wall` — the small portrait concourse screen. Same door-style
  two-block envelope as `railroad_pids_wall` and it REUSES that block's models and
  blockstate geometry; it is its own class only so it can carry its own BE type (a type
  holds one renderer). Editable title + live clock, one departure per row on a chevron in
  the route colour, **route number instead of MTR's "peak" badge** — `ArrivalResponse
  .getRouteNumber()` is a real field in 4.0.1, so no name-parsing heuristic. Stopping
  pattern spelled out for the **top two only**. The **track column stays blank until the
  board announces it** (default 6 min, per-board slider, 0 = always show).
- `departure_board_wall` / `departure_board_hanging` — the big concourse board. Place a
  RECTANGLE of blocks facing the same way and they merge into one screen; row height is
  fixed in canvas units so a taller board simply lists more trains. Rows are filled in the
  route colour, station name along the bottom. Hanging is double-sided and sits at z 5..11
  to line up under a `pids_pole`; its case is full block height because cells stack.

**THE ORIGIN-CORNER TRAP.** `DepartureBoardBlock.screenRight` is
`facing.rotateYCounterclockwise()`, NOT clockwise. After the renderer's
`180 - facing.asRotation()` turn the viewer is on local -Z, so their LEFT hand is toward
local +X, and the painter's canvas x grows from there — for a north-facing board the
canvas runs east→west, and the origin (canvas x=0) is the EASTMOST, lowest cell. Get it
backwards and the screen is built off the end of the blocks.

`rectangle()` walks to that corner, measures each axis, then shrinks the height to the
tallest FULL rectangle so an L-shape draws a whole screen rather than a torn one. Only the
origin cell draws; every other returns immediately (the mosaic's pattern). **Settings live
on EVERY cell** and the server writes the whole rectangle on save — keeping them on the
origin alone would strand them the moment the board grew to the left.

`DepartureBoardData` is a second data assembler beside `RailroadRouteData`, because that
one's `Departure` carries no route number and resolves stops for the next train only;
here every row needs its own stop list, looked up per arrival via `simplifiedRouteIdMap`.

The railroad packet gained title + trackRevealSeconds; `RailroadPidsScreen` shows those
two controls only on the departure board but ALWAYS writes both, echoing its own values
back untouched, so no shape silently resets a field it does not display.

### CREATIVE TABS SPLIT IN TWO (2026-08-09, user request) — the mod outgrew one tab

`BAKER_CITY` / `BAKER_CITY_ENTRIES` are **gone**. There are now two groups registered as
`station_announcer:decoration` ("Baker City: Decoration", 44 items, bench icon) and
`station_announcer:operations` ("Baker City: Operations", 18 items, control-box icon),
each with its own `ModContent.*_ENTRIES` list.

**The rule:** OPERATIONS holds everything that is not a block you place by hand to build
scenery — the PA network + link item, every NYC and Railroad PIDS, holding lights, the
ambience block, and (user decision 2026-08-09) **all 18 pillar/railing/viaduct creator
tools**, which are items rather than decor and were crowding the decoration tab.
DECORATION is the rest, including the fare machine and turnstiles: functional, but
furniture you build with. **Mounting poles go with what they hold up**, so
`pids_pole`/`holding_light_pole` are OPERATIONS while `globe_lamp_pole`/
`stop_marker_pole` are DECORATION.

`MtrStationDecor.registerBlock` now takes the target list as a third argument — that file
registers blocks for BOTH tabs. `MtrPids` and `MtrPillars` → OPERATIONS.
Audited: all 56 registered items land in exactly one tab, none in both or neither.

### STATION FLOORS (2026-08-09) — 8 blocks, assets GENERATED by `tools/gen_floor_assets.py`

Two NYC floors from the user's photos, each grimy and fresh. **Never hand-edit the
textures/models/blockstates/loot/recipes — re-run the generator.** Built, dedicated-server
verified (boots clean in 1.28 s, zero errors), **NOT yet seen in a client** (the user was
on his machine; rendering is the one unverified part).

- `platform_tile_floor[_clean]` — 6×6 greyish ceramic/marble tiles per block (~17 cm).
- `platform_concrete_floor_{2,3,4}[_clean]` — poured slabs 2/3/4 BLOCKS across. Three
  widths are separate blocks so the slab size is picked per area; they share every
  texture and model. The user chose "pure world grid, no editing" — grid phase is fixed
  to world coordinates, aligned by choosing where the floor starts.

**Randomisation — everything is plain vanilla weighted blockstate variants:**
- Tiles: 5 dirty / 3 fresh textures × 4 y-rotations, weights 5/4/4/2/1.
- Concrete: `block/ConcreteFloorBlock` carries `joint_north`/`joint_west` BooleanProperties
  computed from `floorMod(z,N)==0` / `floorMod(x,N)==0` in **`getPlacementState` AND
  `onBlockAdded`** — the latter is what fixes `/fill`, `/setblock`, structure pastes and
  WorldEdit, which never call `getPlacementState`. Each of the 4 combos then lists its
  grime variants with weights (4/2/1/1 dirty, 1/1 fresh). Joints are drawn inside the
  NORTH and WEST edges only — one groove per boundary, never doubled.

> **SODIUM BREAKS THE FABRIC RENDERER API — the mod must not depend on it.**
> The first cut of the concrete floor was a Fabric `BakedModel` + `BlockStateResolver`
> reading the `BlockPos` at mesh time (the old conduit pipe's trick, zero blockstates).
> Thomas plays with **Sodium 0.5.8 and no Indium**, and Sodium does not implement the
> Fabric Renderer API: it never calls `emitBlockQuads` and falls back to `getQuads`,
> which has no position — so every slab drew piece 0, i.e. no joints and no grime
> variation at all. Anything position- or context-dependent in this mod's rendering has
> to go through blockstate properties, not `emitBlockQuads`. (Check the play profile's
> mods folder before reaching for the renderer API again:
> `~/Library/Application Support/ModrinthApp/profiles/Baker City 1.20.4/mods`.)

**TEXTURE LESSONS (each one cost a visible artifact):**
1. **A rotated block shifts the grid by one pixel.** Rotation maps column x to SIZE-1-x,
   i.e. cell offset k to PITCH-1-k, so grout at offset {0} lands at {PITCH-1} when
   rotated — floors came out covered in stair-stepped dark seams. The grout offsets must
   equal their own mirror; a **centred pair** `{PITCH/2-1, PITCH/2}` is the narrowest set
   that does. That forces 2 px grout, which is why the canvas is **96 px** and not 48 —
   2 of an 8 px cell reads as a waffle, 2 of a 16 px cell reads as tilework.
2. **Keep the lowest noise octave quiet.** An octave whose features span the canvas gives
   every block its own overall tone, so a floor of mixed variants reads as a quilt of
   shaded squares. Weight the mid octaves; and include a FINE octave too, or the bilinear
   value-noise grid itself shows up as a lattice of diamonds.
3. **Grout must not ride the per-variant wash.** The grout line on a shared boundary is
   drawn entirely by the east/south block, so if it varies per variant that one block's
   tone outlines every block.
4. **Gum must NOT wrap** (everything else should). Wrapping keeps a texture seamless
   against *itself*, but the neighbour usually draws a different variant, so a wrapped
   splat became two half-blobs stranded either side of a boundary. Gum is kept a full
   radius clear of the edges; soft smears still wrap through `put()`.
5. Artifact counts come from **per-variant profiles**, never a random range — a range puts
   gum on every block, which read as measles.
6. **Gum is not a disc and not black** (`gum_splat`). Discs — even a disc with satellite
   discs bolted on — read as a drawn dot; the outline is a radius modulated by three
   angular harmonics plus a squash, so each splat is a different lopsided blob. And it is
   *blended* at ~0.7, not written, over a core lifted well off black (84,80,74), so the
   floor's own grain shows through it. Earlier passes gave black circles that punched
   holes in the floor, and before that two offset discs that looked like cherries.

`tools/gen_floor_assets.py --preview DIR` writes 8×8-block field previews and a labelled
contact sheet of all six floors — judge the art there, not by restarting a client.

**CONDUIT PIPE REMOVED (2026-07-29, user decision).** The `pipe` block is gone entirely —
`mtr/PipeBlock`, `client/mtr/PipeModel`, `tools/gen_pipe_assets.py`, all 44 pipe models,
both textures, the item model, loot table, recipe, lang key and pickaxe-tag entry. The
v2.0.0 / v2.1.0 pipe sections below are kept as HISTORY (the z-fighting, blockstate-cost
and BlockStateResolver lessons still apply to other blocks) — do not treat them as
current. Pipes already placed in existing worlds become missing blocks; vanilla skips
unknown block ids silently on load, so worlds still open.

### v2.4.0 — station-suite release without the M7 (2026-07-28)

**SHIPPED to `releases/station-announcer-2.4.0+1.20.4.jar` (user request: everything new
except the in-flux train).** Built normally at mod_version 2.4.0, then the M7 was stripped
from a COPY of the jar (source tree untouched — M7 agents were mid-work): removed
`assets/mtr/mtr_custom_resources.json`, all of models/vehicle, textures/vehicle,
properties/ (all-M7 dirs), sounds/m7/, and the 20 `m7_*` keys from sounds.json (14 chime
keys kept). 84 entries stripped, 514 kept; Railroad PIDS + all v2.3 content verified
present; fabric.mod.json version 2.4.0. NOT boot-tested standalone (delta from the tested
dev build is removed-resources-only; no Java references them). mod_version stays 2.4.0 in
gradle.properties; the M7 ships later (2.5.0) by simply not stripping.

## Earlier state: v2.3.0 — Railroad PIDS + a shared platform picker

### v2.3.0 (2026-07-27) — shipped to `releases/`

**Railroad PIDS** (`MtrPids`): `railroad_pids_wall`, `railroad_pids_standing`,
`railroad_pids_hanging`. Unlike the NYC PIDS these are **plain vanilla blocks with our own
block entity and our own brush screen** — they do NOT extend `BlockPIDSBase`.
Wall/standing are door-style vertical multiblocks (`RailroadPidsBlock`, FACING +
DOUBLE_BLOCK_HALF) reusing the subway PIDS's 0.5–2.5-block screen envelope; hanging is a
2-wide horizontal multiblock (`RailroadPidsHangingBlock`, own `EnumProperty<Side>`) in a
deep case with 2 px ceiling stubs so `pids_pole` stacks onto it.

- Wall/standing: line name + live clock, a departure bar in the line colour, then the route
  ahead as a station list with **live 2-minute connections**.
- Hanging: two screens — next train (arrival time · destination · "in 7min", over the
  remaining stops) and a four-departure `TIME/DESTINATION/ETA/TRK` list. Flip on a timer,
  pin to either, or **Automatic** (next train while one is <2 min out).
- `RailroadPidsBlockEntity` is shared by all three but registered as **two BE types** — a BE
  type carries only one renderer and hanging draws a different screen. The constructor picks
  its type from the block.

**THE CONNECTION PIPELINE** (`RailroadRouteData`) — the genuinely novel bit.
`SimplifiedRoute` carries NO timings, so a downstream arrival time cannot be derived from it.
Instead: collect every platform of every stop ahead (`stationIdMap` → `Station.savedRails`,
which IS populated client-side by `ClientData.sync()`), ask MTR for all of them in ONE
batched `requestArrivals`, then find **our own train** among the results by
`routeId + departureIndex` — the pair identifying one specific run. Cached per display for
500 ms. Board data is plain values, no MTR types, which is what let the renderer be
exercised without a railway at all.

**`PlatformPicker`** — the platform chooser shared by every PIDS settings screen, drawn by
hand rather than out of vanilla widgets: checkbox rows, the routes calling at each platform
as coloured chips, ordered **nearest-first**, scrolling, and `fitTo()` so it shrinks rather
than pushing Done/Cancel off a short window. Route data comes from `simplifiedRoutes`
(clients never get the full route graph) inverted into a platform→routes map.

**`NycPidsScreen`** — the brush now opens OUR screen for the NYC PIDS too, replacing MTR's
two-level filter dialog. Offers only what those displays read: platforms, one "Happening
now" box (departure styles only), and the mini's Next-train toggle. MTR's hide-arrival flags
and display page are read off the BE server-side and handed straight back to `setData`, so
nothing we do not offer is silently reset.

**Departures board message behaviour:** a "Happening now" message wrapping past 4 lines
drops the 4th departure and moves the block up; it is capped at 5 lines; and each line is
trimmed with an ellipsis — `CanvasPainter.wrap` only breaks on whitespace, so one very long
token used to run straight off the panel.

**RENDERING LESSONS**
- Canvas is **256 units over the 14 px wall screen** (double the subway PIDS). The hanging
  board derives its canvas from the same units-per-pixel constant, so a given text size is
  one physical size across the whole family.
- `CanvasPainter.textFitted` — shrink to fit, marquee only as a last resort. Headline text
  (destination, line name) must never scroll.
- **Z-FIGHTING, new cause:** on the hanging board the case is drawn by the RENDERER, in the
  same debug-quad layer as the screen. The wall boards get away with a 0.001-block standoff
  because their panel is block-model geometry in the *solid* pass; two quads in the *same*
  layer 0.001 apart z-fight at grazing angles — a vertical band at the unit centre whose
  pixels were a uniform x0.6 of their neighbours. Fix: `SCREEN_STANDOFF = 0.008` **and**
  `DEPTH_STRETCH = 6` on the matrix z scale (a canvas unit is ~1/300 block).
- Right-aligned columns must reserve their own text width — the departure chip clamped to
  the ETA column's edge still ran under the right-aligned ETA text.

## Earlier state: v2.2.0 — zebra boards, stop markers, adjustable holding lights

### v2.2.0 — car stop markers + holding-light timing (2026-07-24)

**SHIPPED to `releases/station-announcer-2.2.0+1.20.4.jar`.**

- **Holding lights are now adjustable with the brush.** `StationDecorBlockEntity` gained
  `LightOnSeconds`/`LightOffSeconds` (−1 = "use this light's default"), the shared
  `update_decor` packet carries both as plain ints (−1 rules out varints), and
  `HoldingLightScreen` got two sliders whose far-left notch is Auto. Defaults moved onto
  `HoldingLightBlock.defaultOn/OffSeconds()` — yellow 5 s before arrival / 6 s before
  departure, green 3 s before departure / 15 s after. `StationSignScreen` shares the
  channel, so it echoes both values back untouched. `IntSlider` is now public.
- **New blocks: `stop_marker` + `stop_marker_pole`** (`mtr/StopMarkerBlock`,
  `StopMarkerBlockEntity`, `client/mtr/StopMarkerRenderer`, `StopMarkerScreen`).
  1–4 plates of 4x4 model px stack downward; each plate has a colour
  (black/white/yellow) and a ≤4-char legend, edited with the MTR brush. Plate colour
  and legend live in the block entity; **MOUNT (ceiling/wall/floor) and STANDOFF are
  blockstate properties** because the pole and bracket are geometry (24 states).
  Ceiling markers hang at the BOTTOM of their block so stacking poles above lowers
  them; floor and wall markers sit at the top. Legends are front-only, drawn on a
  32-unit canvas per plate (8 canvas units per model pixel), with corner bolts and —
  on yellow OPTO boards — the black keyline from the photos.
- **CRASH LESSON (cost a client crash, and I had already written the rule down during
  the perf audit):** never hold a `VertexConsumer` across a text draw. Drawing text
  switches render layer, which flushes the quad buffer, and the next write into the
  cached reference dies with `IllegalStateException: Not filled all elements of the
  vertex`. `StopMarkerRenderer` now draws every plate box in one pass, then all the
  legends in a second pass. `CanvasPainter.quad` re-fetching per call is exactly why
  it is safe — do not "optimize" that.
- **Verified in game** (dev client, screenshots read back): a 4-plate ceiling stack on
  a drop pole reading 4/6/8 + yellow S (matches the reference photo), a single "10"
  ceiling plate, a floor post, a flush white "10" wall plate with bolts, and a
  bracket-mounted yellow OPTO/S pair. Server boots clean at 1.38 s.
- **NOT verified:** the brush GUI itself (add/remove plate, colour cycling, the
  flush/bracket toggle, the holding-light sliders) — those need a real right-click,
  which the headless rig cannot do. Legend sizing was capped at 22 units right at the
  end because a lone "S" was crowding the keyline; that last tweak is in the shipped
  jar but was NOT re-screenshotted.

## Earlier state: v2.2.0 — zebra boards, slimmed PIDS pole

### v2.2.0 — zebra boards (from user photos — 2026-07-24)

Two new COMMON blocks (no MTR data needed, registered in ModContent):
`zebra_board_wall` and `zebra_board_hanging`, both `block/ZebraBoardBlock extends
FacingDecorBlock` with bench-style LEFT/RIGHT booleans. **Assets are GENERATED by
`tools/gen_zebra_assets.py`** (textures + 9 models + 2 item models + 2 blockstates) —
never hand-edit them.

- **Merging run**: neighbours along `facing.rotateYClockwise()` merge when
  `neighbor.isOf(this)` **and** same facing — so wall and hanging boards never join
  (they sit at different depths and would step). Free ends draw a bolted end plate;
  on the hanging variant the free ends also draw the hanger pole, via a single
  multipart selector `{"OR":[{"left":"false"},{"right":"false"}]}` — two selectors
  would stack two identical pole models on a lone board and z-fight.
- **Geometry** (FACING=north frame, run along x): bottom rail y5..6, striped panel
  y6..12, top rail y12..13, end plate x0..1 / 15..16 at y4..14, pole x/z 7..9 y13..16.
  Hanging depths z 6..10 / 6.5..9.5 / 5..11; the wall variant runs everything back to
  z=16 with `cullface: south`. `ZebraBoardBlock` unions the pole box into the outline
  only on end blocks (the pole box is centred, so it needs no rotation).
- **Z-fighting**: beam parts span the full block and therefore NEVER draw their
  east/west end faces (connected → same plane as the neighbour's; unconnected → the
  plate covers them) — the conduit pipe's rule 2, reused. The panel's up/down faces are
  omitted too (always buried under the deeper rails), and the pole's down face (sits on
  the top rail). Only the end plate touches x=0/16 — the platform-barrier "only the
  posts" rule.
- **STRIPE TEXTURE LESSONS** (three in-game iterations to get right):
  1. Horizontal period must divide 16 or the diagonal breaks at every block seam.
     Period 4 it is; shading may vary VERTICALLY only, never horizontally.
  2. Slope must be a true 45° (1 px per row). A shallower slope (1 px per 2 rows,
     closer to the real photos) steps only every other row and reads as a chunky
     zigzag, not a stripe.
  3. **1 px of black per 4 px period, not 2.** Minecraft shades vertical faces down,
     so an even black/white split renders as a *black* board with white flecks — the
     photos are a *white* board with black slashes. Preview candidates offline by
     rendering the panel's texture row band upscaled; it is far cheaper than a
     client restart per attempt.
- **`pids_pole` slimmed 3 px → 2 px** (x/z 6.5..9.5 → 7..9, model + `MtrStationDecor`
  shape), matching `pids_nyc_hanging`'s own ceiling stub at 7..9 so a drop pole
  continues it without a step (user-reported: "too fat"). Model switched to auto-UV,
  which now samples the texture's sheen column as a lit edge.
- Verified on the live dev server + client: 5-block runs of both variants render
  continuous with no seams or z-fighting, plates and bolts read, hanger poles land on
  the ceiling, item icons render, drop poles line up with a hanging PIDS, zero
  station_announcer resource warnings. Merge logic exercised by console:
  middle block both-connected, ends one-connected, mixed variants refuse to merge,
  breaking the middle disconnects both sides. NOT yet user play-tested.
- **Test-rig gotcha (new):** a dev server started from a *foreground* Bash call gets
  SIGKILLed (exit 137) when that call returns. Start it with `run_in_background`
  instead — it survives across calls. And when a server is killed, its JVM can keep
  the world's `session.lock`; kill leftover `KnotServer` processes before rebooting.

## Earlier state: v2.0.0 — turnstiles, conduit pipes, holding lights, entrance kit

### IMPORTANT policy change (2026-07-22, user decision): MTR is now a REQUIRED dependency

`fabric.mod.json` now `depends` on `mtr >=4.0.0` (was `suggests`). Do NOT run no-MTR boot
tests anymore; the mod is allowed to assume MTR everywhere. The isModLoaded guards and the
common/mtr package split remain in the code (harmless, and they keep client classes off the
server) — but new features may treat MTR as always present. modCompileOnly+modLocalRuntime
in build.gradle unchanged.

### v2.1.0 follow-up — pipes connect across surfaces, black removed (2026-07-24)

The first cut of the mount feature refused to connect pipes whose mounts differed,
so a run that went along the ceiling, down a wall and onto the floor broke into three
capped stubs. Fixing that properly meant the model had to see the NEIGHBOUR's mount,
which a blockstate cannot — so connections moved out of the blockstate entirely.

- **`size` and the colour `black` are gone** (user decisions). One gauge, four colours.
- **Connections are no longer blockstate properties.** `client/mtr/PipeModel` is a Fabric
  `BlockStateResolver` + custom `BakedModel`; `emitBlockQuads` receives the
  `BlockRenderView`, so it reads the six neighbours as the chunk meshes and assembles
  the block from pre-baked pieces. **There is no `blockstates/pipe.json` any more** —
  do not recreate one; the resolver owns every state.
- **Cross-surface runs work**: same colour always connects. Where the mount differs both
  blocks draw `pipe_junction_n*`, a deep box reaching to the block centre; the two halves
  meet across the shared face and swallow the step between tube planes that can never
  line up. Same-mount blocks keep the shallow `pipe_core_n*` at turns/branches only.
- Placing a pipe no longer rewrites its six neighbours (`getStateForNeighborUpdate` is
  gone), so a placement is one block update instead of seven.

**The performance arc, measured on the dedicated server (no GPU) at every step:**

| version | blockstates | selectors | product | boot |
|---|---|---|---|---|
| original (3 sizes, box everywhere) | 3,840 | 84 | 322 k | 1.17 s |
| first mount attempt | 23,040 | 720 | 16.6 M | 4.61 s — froze the client |
| size removed | 7,680 | 240 | 1.84 M | 1.77 s |
| **connections out of the blockstate** | **96** | **0** | **0** | **1.23 s** |

96 states is **40x fewer than the mod ever had**, and model baking is gone entirely.
Verified in game: a ceiling run turning down a wall and continuing over the floor is
continuous end to end, with corner boxes at both transitions and zero model warnings.

**Rules for anyone touching this again:** measure blockstate changes with `runServer`,
never the client — and note `Done (Ns)` includes spawn-area chunk prep, so read the
`Time elapsed: N ms` line and subtract it. Never run a second Gradle task against this
project while `runServer`/`runClient` is up; it kills the running one.

### v2.1.0 — conduit pipe rework (surface mounting + performance — 2026-07-24)

**Assets are now GENERATED — never hand-edit them.** `tools/gen_pipe_assets.py` writes
all 40 pipe models plus `blockstates/pipe.json`. Change geometry there and re-run it.

- **`size` removed entirely** (user decision): one gauge, the old size 1 (diameter 2 px).
  At the 4 px pitch that leaves a 2 px gap, so tubes in a bank can never share a face.
  The brush's sneak gesture no longer resizes — it now **re-seats a whole segment onto
  the next surface** (how you fix a mis-mounted run in place).
- **New `mount` DirectionProperty**: the face the run is bolted to. Placement takes it
  from the clicked face (`context.getSide().getOpposite()`); clicking an existing pipe
  inherits that run's mount instead. Pipes only connect when mount, colour (and the
  block) all match — runs on different surfaces sit at different offsets and would meet
  at a visible step, so they deliberately stay apart.
- **All models are authored in the CEILING frame and rotated per mount by the
  blockstate** (x/y rotation verified against vanilla `end_rod`). Consequence worth
  remembering: a wall mount turns the in-plane arms into a **vertical wall-hugging run**,
  so wall drops are straight and jointless for free.
- **Junction boxes now render only where the run spans more than one axis** (a turn or a
  branch) plus the isolated no-connection block. Expressed in multipart as an OR of the
  12 cross-axis pairs — no extra blockstate property needed. Dead ends get a cap model.

**THE FIVE Z-FIGHTING SOURCES (all fixed; the generator's docstring lists them):**
tubes touching within a bank; an arm's face at the block boundary meeting the
neighbour's arm face in the same world plane; an arm's inner end face meeting the
opposite arm's; every surface-flush face being coplanar with the mounting block's own
face; and box/cap lids sharing the arms' flush plane. Fixes: gap the tubes, **omit both
end faces on every arm** (arms only exist for connected directions, so those faces are
always buried), `cullface` on surface-flush faces, and inset box/cap lids by 0.03.

**PERFORMANCE — the expensive lesson (this froze the user's machine once):**
model cost scales as *blockstates x multipart selectors*, and both explode fast.

| version | blockstates | selectors | product | server boot |
|---|---|---|---|---|
| original (3 sizes, always-on box) | 3,840 | 84 | 322 k | 1.17 s |
| **first attempt** (+mount, +size) | 23,040 | 720 | **16.6 M** | **4.61 s — froze the client** |
| shipped (mount, no size) | 7,680 | 240 | 1.84 M | 2.70 s |

Rules learned: (1) never multiply a connecting block's 64 connection combinations by
another 6-value property without checking the product; (2) **measure on the dedicated
server** — it builds every blockstate with zero rendering, so it isolates state cost
from GPU cost and never risks the user's machine; (3) quads matter too — omitting the
two end faces and dropping the always-on junction box cut a straight run block from
54 quads to 24 (**56% fewer**).
- **DONE (2026-07-24): connections are no longer blockstate properties.**
  `client/mtr/PipeModel` registers a Fabric `BlockStateResolver` for the block and
  reads the neighbours from the `BlockRenderView` in `emitBlockQuads`, assembling
  the block from pre-baked pieces (`Baker.bake(id, ModelRotation.get(x, y))`, one
  bake per mount rotation, 44 JSON pieces reused as authored). **There is no
  blockstates/pipe.json any more** — deleting it is what makes the resolver the
  single source of truth, and `tools/gen_pipe_assets.py` no longer writes one.

  | | blockstates | selectors | product | server boot |
  |---|---|---|---|---|
  | shipped 2.1.0 | 7,680 | 240 | 1.84 M | 2.65 s |
  | **resolver** | **120** | **0** | **0** | **1.65 s** |

  A full second off dedicated-server boot (and the server no longer builds 7,560
  dead states); on the client the 1.8 M multipart predicate evaluations at bake
  time are gone entirely, replaced by 264 cached `Baker.bake` calls. Placing a
  pipe also no longer rewrites the blockstate of its six neighbours — vanilla
  already re-meshes the sections around a block change, which is what makes
  mesh-time connections correct.
- **Existing pipes survive the property removal**: unknown properties in a chunk
  palette are skipped silently, so old placements keep mount/pipes/colour and the
  connections (now computed) come back on their own.
- **Behaviour that changed with it (deliberate):** `connects()` now matches on
  colour ALONE, so a run can turn from the ceiling onto a wall; where the mount
  differs both blocks draw the previously-unused deep `pipe_junction` box, whose
  halves meet across the shared face and swallow the step between two tube planes
  that can never line up. The brush follows suit: **repaint walks the run by
  colour** (what the player sees as one run), while **sneak re-seat still stops at
  the mount boundary** so only the mis-seated leg moves — and each gesture now
  writes only its own property, since forcing both would flatten a repainted
  corner onto one surface.
- **Verified in game** (dev client on the dev server, screenshots read back):
  straight runs box-free, corner/tee boxes, end caps, a vertical drop, a 4-pipe
  red bank beside a 1-pipe black run that correctly does NOT join it, and the new
  ceiling→wall junction reading as a real conduit corner. Zero station_announcer
  model warnings in the client log; the only exceptions there are MTR's own
  `EntityRendering` cast bug, with no station_announcer frame in any stack.
  Headless test bonus: `/setblock` no longer needs connection properties.
- Verified in game on all three mounts (ceiling / wall / floor): straight runs are
  continuous and box-free, boxes appear only at the tee and the corner, dead ends are
  capped, wall runs and their vertical drops hug the wall, zero z-fighting, and the
  client logs zero station_announcer model warnings.

### v2.1.0 performance pass (full optimization audit — 2026-07-24)

Behavior-preserving only; no feature, asset or protocol changes. **The theme: work
that ran per frame per block now runs a few times a second, shared.**

- **THE BIG ONE — three MTR client lookups were being called per frame, per block**
  (verified against MTR bytecode): `InitClient.findStation` streams over EVERY
  station; `findClosePlatform` streams over EVERY platform and sorts by an
  approximate distance that hits the data store; `ArrivalsCacheClient
  .requestArrivals` walks the ENTIRE arrival cache (all platforms) and allocates a
  new list per call. A station full of columns/PIDS/holding lights was doing
  hundreds of full scans per frame. New `client/mtr/MtrDataCache` holds them by
  block position — station 1 s, platform 2 s, arrivals 200 ms — cleared on
  disconnect. Countdowns stay smooth because they are computed from the arrival's
  absolute timestamps, not from fetch time. Re-requesting at 200 ms keeps MTR's
  platform queue warm: `ArrivalsCache` drops a platform only after 5 of its own
  (multi-second) request cycles, confirmed in the bytecode.
- **Holding lights no longer do the station lookup at all** — they draw no text, so
  the name/colour it produced was thrown away every frame.
- **Column tint provider** (runs per tinted quad on chunk-meshing worker threads,
  i.e. every chunk rebuild near a station) now reads a 5 s `ConcurrentHashMap`
  cache instead of a full station scan. Concurrent + immutable entries because of
  the worker threads; MTR needs a chunk rebuild to show a colour change anyway.
- **Speaker Link beams** rescanned 13x13 chunks of block entities EVERY FRAME while
  the tool was held; now cached 250 ms (and re-scanned when the player changes chunk).
- **PIDS**: arrivals + "Happening now" gathered once per render instead of once per
  face (double-sided screens did everything twice); pool split cached 500 ms;
  interchange bullets cached 3 s; clock built without `String.format`; `routeLabel`
  scans for digits instead of compiling a regex per station row per frame.
- **CanvasPainter marquees** built `looped+looped` / `" ".repeat(n)+text` strings and
  then called `font.getWidth(String.valueOf(ch))` for EVERY character, EVERY frame.
  They now walk the source in place with memoized glyph widths (cleared on resource
  reload). **Verified equivalent over 268,800 generated cases** (both marquee kinds,
  8 strings x 7 sizes x 6 widths x 400 phases) — 0 mismatches. Note the vanilla font
  rounds per call (`getWidth` ceils once), so per-character measuring must stay
  per-character to match; only the space padding may be summed, since a space
  advance is exactly 4.0.
- **`BlockPidsNyc.getOutlineShape2` built a fresh VoxelShape on every call** (every
  raycast, collision test and block outline). Precomputed per facing x half in the
  constructor. Also `.simplify()` on every precomputed union shape (facing-decor
  rotations, railings, turnstile, pipes, bench) — one-time cost at startup, cheaper
  collision/raycast forever after.
- Smaller: turnstile fare cooldown keyed by a record instead of building
  `pos + ":" + uuid` per collision tick; `Direction.values()`/`PipeColor.values()`
  hoisted (enum `values()` clones its array — the brush BFS called it per queue
  entry); `splitMessages` pattern precompiled; `fire()` no longer allocates a Vec3d
  per player x speaker; ambience scan uses squared distances with no Vec3d;
  client tick handler returns immediately when no speech is queued; control box GUI
  rebuilds its speaker line 4x/s instead of per frame.
- **Verified:** build green; dedicated server boots clean in **2.651 s** (2.70 s
  before, so the added startup shape work is free); live smoke test — control box +
  speaker placed by console, speaker self-registered
  (`Speakers: [L; 825183488933958L]`), `/announce` fired it, the 3-message pool
  rotated 0→1→2→0 in order, auto-trigger ticked, zero exceptions.
- **Follow-up, now DONE:** the pipe's 7,680 states x 240 multipart selectors were
  this mod's biggest client loading cost. The `BlockStateResolver` rewrite landed
  the same day — see "conduit pipe rework" above for the measurements (120 states,
  zero multipart, server boot 2.65 s → 1.65 s).

### v2.0.0 audit pass (full code audit — 2026-07-23)

- **Version is now 2.3.0; jars are named `station-announcer-<ver>+<mcver>.jar`**
  (mod_version carries the `+1.20.4` build-metadata suffix, Modrinth-style).
  `releases/station-announcer-2.0.0+1.20.4.jar` includes everything below.
  Multi-version ports (1.21.4 / 1.18.2 / 1.16.5) are scaffolded in `versions/`
  but ON HOLD per the user — MTR exists for all four targets (1.21.4 only as
  4.1.0-beta.2, built with Loom 1.16.3, needs a toolchain bump in that folder).
- **Blocks added since iteration 8 that the docs never recorded** (registered in
  MtrStationDecor, audited OK): `turnstile` + `turnstile_cap` (2-tall fare lanes
  charging via TicketSystem.passThrough, RIGHT-connection for the overhead tube),
  `pipe` (1–4 packed pipes, 3 sizes × 5 colors, brush repaint/resize of whole
  segments, sea-pickle stacking), `holding_light_yellow/green` (+`holding_light_pole`)
  timed off ArrivalsCacheClient with a brush platform picker (platform id stored in
  the decor BE's CustomName), and `pids_pole`.
- **Audit fixes (all verified: build green, server boots clean, PA link + /announce
  smoke-tested, client loads with ZERO station_announcer resource warnings):**
  1. pids_nyc_hanging(+_mini) blockstates carried dead `side=middle/single`
     variants → 16 "Exception loading blockstate definition" per resource load. Removed.
  2. Static per-position client maps never cleared across worlds
     (StationDecorRenderer.LAST_DEPARTURE — unbounded — and
     PidsNycRenderer.NEXT_TRAIN_STATE): now cleared on client DISCONNECT
     (hook in MtrPidsClient); TurnstileBlock.LAST_ATTEMPT cleared on
     SERVER_STOPPED (hook in MtrStationDecor.register).
  3. SystemTtsBackend stop/start race (a speech process launched concurrently
     with stop() could survive): generation counter, stale tasks kill their own process.
  4. CanvasPainter.textScrolling: guarded a theoretical modulo-by-zero.
- **Audit found NOT broken (checked thoroughly, don't re-litigate):** networking
  validation (distance + canPlayerModifyAt + length caps everywhere), BE lifecycle
  (registry add/remove, markRemoved vs onStateReplaced), multiblock loot gating
  (fare machine/turnstiles half=lower, PIDS side=left / half=lower, pipe drops its
  packed count), assets fully consistent (every block has blockstate/models/
  textures/item model/lang/loot/mineable tag — bench is in mineable/axe, everything
  else pickaxe), sounds.json ↔ ogg files, GUI opener chaining, client/server split.
- **Test-rig gotchas learned:** `/data merge` longs need the `L` suffix
  (`{ControlBox:123L}`) — a bare big number parses as a STRING tag and reads back 0;
  the screenshot.flag hook needs the client window visible (occluded window =
  stale framebuffer, log-based checks still fine); kill leftovers with
  `lsof -ti :25565 | xargs kill -9` before rebooting the dev server.

### v1.7.0 iteration 9 (platform barriers, trash can removed — 2026-07-23)

- **Station trash can deleted** (ModContent block/item/registration/tab entry, blockstate,
  both models, texture, loot table, recipe, pickaxe tag, lang). No replacement.
- **`leaning_bar` renamed to `platform_barrier`** and remodelled from the user's NYC
  platform photos: woven wire mesh panel in a stainless frame (bottom rail + top frame
  rail), octagonal top hand rail spanning the full block so runs read as one continuous
  rail, square posts at BOTH block edges with bolted foot plates — so a joint between two
  segments shows the doubled frame member and merged plate pair exactly like the photo.
  No connection blockstate properties at all (posts at every edge is the authentic look
  and avoids the /setblock property gotcha). Recipe: III over BBB (iron bars) → 4.
  Old placements do NOT migrate — the registry id changed.
- Three model-space textures: `platform_barrier_steel` (32², cylinder shading in the tube
  rows, polished band in the top-face rows), `platform_barrier_post` (32², vertical sheen
  at the post centre columns), `platform_barrier_mesh` (**64²**, 4-texel weave cell with a
  2×2 alpha hole, strands alternating over/under). Mesh needs
  `BlockRenderLayerMap.INSTANCE.putBlock(..., getCutoutMipped())` in StationAnnouncerClient
  — the mod's first cutout block.
- **Z-FIGHTING LESSON (user-reported, then fixed):** in a multi-element model never let two
  elements share an exact plane, and never put a face exactly on the block boundary if a
  neighbour block can sit there. The three offenders here: posts using the tube's z planes
  (6.6/9.4), post bottoms landing exactly on the foot-plate top (y 0.8), and the tube's top
  face at y=16 fighting the block above. Fixes: shrink the posts to z 6.7..9.3, start them
  at y 0.4 (buried inside the plate), and cap the tube at y 15.9.
- **Z-FIGHTING ROUND 2 (2026-07-24, user screenshot of a run END):** the x=0 / x=16 planes
  were the real offender — mesh, both frame rails, both tube slabs, the post AND the foot
  plate all ended exactly on the block edge, so at the end of a run every one of those end
  faces was coplanar with the post's visible end face (center stipple = mesh weave, top and
  bottom hatching = rails). Joints were never the problem: coplanar faces sandwiched between
  two solid blocks are occluded and can't flicker. **Rule: only the posts may touch x=0/16.**
  Panel parts now stop short so their ends bury inside the posts (mesh 0.1, bottom rail 0.05,
  top rail 0.07, wide tube slab 0.05); the tall tube slab instead OVERSHOOTS to -0.05..16.05
  so neighbouring hand rails interpenetrate (continuity kept, no gap) — overshooting past
  x=16 needs explicit uv or it bleeds the atlas, same as the y>16 lesson above. Left foot
  plate overshoots, right plate stops at 15.95 so merged pads butt instead of overlapping.
  Also closed the 0.1px slit between top rail (was y13.5) and tube (y13.6) by raising the
  rail to 13.7, and added `cullface: down` to the foot plates so they stop fighting the floor
  block's top face. Checked with a throwaway python script that enumerates every element face
  and reports coplanar pairs with overlapping footprints — 0 within a block, and the 8
  cross-block hits are all buried between two solids.
- **Texture-region collision lesson:** with auto-UV, two different faces can sample the same
  texels. The foot-plate top face and the post front face share columns 0..3 / 29..32, so
  bolt pixels painted for the plate showed up as notches halfway up every post — they were
  dropped. When two parts need contradictory shading, give them separate textures (that is
  why posts/rails/mesh are three files).
- Verified via dev-client screenshots (front, angled, 7-segment run + a lone segment):
  continuous rail, aligned mesh, plate pairs at joints, no artifacts.

### v2.0.0 (turnstiles, conduit pipes, holding lights, poles — 2026-07-23)

- **Turnstiles** (`mtr/TurnstileBaseBlock` + `TurnstileBlock` + `TurnstileCapBlock`):
  2-tall door-style multiblocks (FACING/HALF/RIGHT). The lane never blocks movement (user
  decision: no animation/barrier) — walking through calls MTR's
  `TicketSystem.passThrough(world,pos,player, true,true, 4×barrier sounds, null, false,
  callback)` (arg order verified from BlockTicketBarrier bytecode); entry AND exit allowed,
  MTR picks from the travel record and charges `mtr_balance`. One charge per crossing via
  a pos+UUID cooldown map (40 ticks). RIGHT (facing.rotateYClockwise neighbor, same half +
  facing) drives the overhead tubing: riser always, tube_bridge when RIGHT, end elbow when
  not; cap block shares the bridge/end models. Cabinet left of lane, black reader pillar
  with green go-light. VERIFIED: balance 49→41 crossing a lane on the live server; user
  play-tested. (An earlier OPEN/collision design ejected players upward when the arm
  closed mid-pass — removed entirely.)
- **Conduit pipes** (`mtr/PipeBlock`): SIZE 1-3 (Ø2/3/4px) × COLOR enum (off_white/black/
  gray/red/blue, tinted via state-based color provider over a grayscale texture) × PIPES
  1-4 (sea-pickle canReplace stacking) × 6 connection bools. Fresh placement inherits
  size+color from any neighboring pipe, else random; connects only same-size+color.
  MTR brush repaints the whole connected segment (BFS ≤128; sneak = cycle size). Ceiling-
  mounted (tubes flush at y16), junction/coupling box at the core spanning the bank,
  collar-capped open ends, vertical drops via up/down arms. 84 generated models +
  multipart blockstate; no collision. Loot drops 1-4 via block_state_property alternatives.
- **Holding lights** (`mtr/HoldingLightBlock` yellow/green + StationDecorBlockEntity):
  renderer-timed off ArrivalsCacheClient like the PIDS. YELLOW lit from arrival−5 s until
  departure−3 s (held trains keep it lit because MTR slides departure). GREEN blinks
  (400 ms) from departure until +15 s (LAST_DEPARTURE map keyed by pos survives the
  arrival entry vanishing). Platform: auto (findClosePlatform r=8) or brush-picked —
  `HoldingLightScreen` cycles nearby platforms (r=16), stores the platform id STRING in
  the shared CustomName field via UPDATE_DECOR_C2S (GUI_OPENER dispatches on the BE's
  block: HoldingLightBlock → picker, else StationSignScreen). Lenses = painter circles on
  both faces (bright + hot core when active, near-black when off).
- **Poles:** `holding_light_pole` (junction box + conduit stem) and `pids_pole` (dark
  steel drop pole so hanging PIDS can mount lower). Plain DecorBlocks.
- User added their own block this release: `PLATFORM_BARRIER` (ModContent, cutout render
  layer in StationAnnouncerClient) with complete assets — leave it theirs.
- Verified via screenshots on the live rig: pipe banks/corners/junction boxes/drops/caps
  in four tints (user personally placed a random-black bank via click-stacking), holding
  lights + pole assembly, PIDS pole. Turnstile fare charge verified numerically earlier.

### v1.7.0 iteration 8b (mosaic border fix — 2026-07-23)

- User reported the mosaic border "looks buggy": joint marks overhung the panel edges
  (stray slivers), side borders had no joints, band width wasn't tile-aligned so the
  pattern drifted. paintMosaic now snaps bandWidth UP to a multiple of 8 canvas units and
  draws the border as REAL inset tiles (7.2-unit tiles with 0.8 grout gaps) over a
  darkened-color grout backing, on all four sides; field unchanged (cream + grout grid).
  Verified: TEST1 sign — clean tile frame, consistent joints, nothing past the edges.

### v1.7.0 iteration 8 (entrance railings + merging station sign — 2026-07-23)

- **`entrance_railing`** (common, `block/RailingBlock`): 1.5 blocks tall (models/shapes to
  y24), green balusters + serrated top rail on a 2px blackish concrete curb. Fence-style
  NORTH/EAST/SOUTH/WEST props + POST prop: post renders at ends/corners/junctions/
  standalone AND when a globe lamp/pole sits directly above (lampAbove check) — poles are
  6..10 wide, post shaft 4.5..11.5, so the lamp grows out of the post seamlessly.
  Precomputed VoxelShape[32] (n|e|s|w|post bits). Multipart blockstate: post model +
  4 rotated half-arm models (arm = curb strip, bottom/top rails, 3 spikes, 2 balusters).
- **`entrance_railing_sign`** (MTR module, `mtr/RailingSignBlock extends RailingBlock` +
  BE): the black station-name panel in the railing. ONLY the MTR brush opens the text
  screen (`Items.BRUSH.get().data`); name auto-follows the station, falls back "Subway".
  Panel plane follows the run axis (EAST|WEST connected → faces N/S, else E/W) — no
  facing property. **Adjacent sign segments MERGE**: renderer draws one panel from the
  run's min-coordinate segment across up to 8 segments (others return early); a custom
  name on ANY segment wins for the whole run; 1-line size 12, else 2-line wrap size 9,
  else shrink. Both sides readable (faces at ±3/16, inside the post width).
- **CRITICAL modeling lesson:** auto-UV only works for elements within y 0..16 — faces of
  elements above y=16 sample OUTSIDE the sprite and bleed neighboring ATLAS textures
  (the railing tops rendered stone-gray). Any element crossing/above y16 needs explicit
  face-sized uv slices (uv [0,0,w,h], clamp 16 — uniform textures hide the stretch).
- Verified via screenshots: L-run with corner post + globe lamp on top, "Subway" default,
  3-segment merged panel with "Baker City Central Station" on one line, 2-line wrap on
  narrow panels, connections/post logic (headless setblock needs explicit
  north/east/south/west/post props as usual).

### v1.7.0 iteration 7 (globe lamp rework, from user photo — 2026-07-23)

- **New block `globe_lamp_pole`** (common, DecorBlock, no facing): slim fluted cast-iron
  post (core 6..10 + four thin ribs), stackable to any height, dark NYC green. Pole
  texture is PURE vertical fluting on all 16 rows — any horizontal band in the texture
  repeats on every stacked block (first attempt had the crown rows bleeding in).
- **Globes remodeled as the stack topper:** pole stub (y 0..4, same 6..10 footprint so it
  continues the pole seamlessly) → leafy crown flare → 5-layer voxel orb (y 7..16, core
  12px wide). Crown/stub sample a dedicated `globe_crown` texture (rim v9..10, leaf band
  v10..12, fluting continuation v12..16).
- **Globe textures** (`globe_green`/`globe_red`): shaded sphere with specular patch on
  top, warm amber lamp-glow underside like the lit photo. KEY: the color transition is
  painted INSIDE the core layer's row range (v4..6) so the gradient crosses within one
  box — putting it exactly at a layer seam makes the orb read like a mushroom cap.
- Globe outline shapes widened to the orb (3.5..12.5); loot/recipe (iron+dye → 6 poles)/
  lang/tag/tab wired. Old `lamp_post` texture deleted.
- Verified via screenshots: seamless 2-pole+globe stacks in both colors, no repeating
  bands, crown collar reads, glow blends. User's earlier "stretched plates" report was a
  stale client jar (their screenshots matched pre-iteration-6 art).
- NEXT UP (user): railings and entryway blocks.

### v1.7.0 iteration 6 (texture-quality pass: auto-UV everywhere)

- **KEY LESSON: stop stamping `uv: [0,0,16,16]` on every model face.** That habit (copied
  from the flat PIDS frame fix) stretches the whole texture onto faces of any size. For
  3D furniture, OMIT the uv — vanilla auto-UV samples the texture at the element's own
  model coordinates → 1:1 texels, and textures get designed in model space (draw details
  at the texel coords the elements actually cover). The explicit-UV approach remains
  right only for "one texture = one whole face" cases (PIDS frames, fare machine fronts).
- Column shaft textures redrawn model-aligned (rivets at texture x 4-5/10-11 inside the
  flange region 3..13, flange edge shading at cols 3/12); NEW `column_plate(_tint)`
  smooth bolted-plate texture for foot/cap (edge vignette, bevel, 4 corner bolts) so
  tops/bottoms no longer smear rivets. Bench wood redrawn (2 plank seams + grain flecks,
  no dense banding when benches tile). All column/bench models regenerated with auto-UV.
- Verified via screenshots: caps read as distinct steel plates on plain + tinted stacks,
  rivet rows land inside the flanges, bench grain reads cleanly across a merged 3-bench.

### v1.7.0 iteration 5 (bench sit-height/dividers/frame + MVM texel fix)

- **Sit height calibrated numerically** (user sat too low): measured via `/execute
  positioned … summon station_announcer:seat run ride @p mount @s` (plain /ride fails —
  an empty seat discards itself on its first tick) + `data get entity @p Pos`, compared
  with minecart reference: passenger rides 0.6 below the seat entity, visual seat point
  ~0.475 below player pos → seat spawn = benchY + 0.6875 puts the butt exactly on the
  seat slab (9/16). Constant in BenchBlock.onUse.
- **Dividers moved to block EDGES** (x 0..1.5 / 14.5..16, y 9..12) so merged benches get
  them BETWEEN seats (halves combine at boundaries; free ends hide inside the arm
  panels) — previously center-of-block, i.e. inside the sitting player.
- **Black under-frame T removed:** no more frame rail + center front leg; just one
  recessed dark leg at the back (z 10..12, y 0..6) that reads as shadow.
- **Fare machine faces redrawn at 1:1 texels** (user: "don't just stretch"): dedicated
  16x16 `fare_machine_front_top` (art rows 4..15, face uv [1,4,15,16] — 14x12 face) and
  `fare_machine_front_bottom` (uv [1,0,15,16] — 14x16 face); side texture uses cols 0..7
  for the 8px-deep faces (uv [0,y,8,y']), spare right half for back/top. No non-square
  texel stretching anywhere. Old 32x32 front replaced.
- Verified via dev-client screenshots: boundary dividers on a 3-bench row, no T, crisp
  MVM face. Sit height verified numerically (visual pose can't be checked first-person).

### v1.7.0 iteration 4 (column rework, from user photos)

- **Station color is now a real TINT, not overlay quads:** ColorProviderRegistry.BLOCK
  (registered in MtrPidsClient) multiplies `InitClient.findStation(pos).getColor()` over the
  texture — rivets/shading stay visible (vanilla grass-tint mechanism). Tinted models use a
  LIGHT grayscale texture (`column_iron_tint`, base ~196) with `tintindex: 0` on every face;
  provider try/caught because it runs on chunk-meshing worker threads; item tint =
  fallback dark green. Renderer no longer paints any column color (only name boards).
  Caveat: chunk rebuild needed to refresh after a station color change.
- **Column block classes restructured:** `ColumnBlock extends FacingDecorBlock` (new
  up/down BooleanProperties, computed from vertical ColumnBlock neighbors — facing
  ignored) for column_iron AND column_iron_station (no BE anymore!);
  `StationColumnBlock extends ColumnBlock implements BlockEntityProvider` for the two
  named variants; `StationDecorBlock` is now mosaic-only (Kind enum gone). BE type spans
  mosaic + 2 named columns.
- **Stack ends detected via multipart blockstate:** column_shaft always; `down=false` adds
  column_foot (base collar + stepped-diagonal channel caps, like the photo's base);
  `up=false` adds column_cap (two stepped plates that plateau into the ceiling). Tinted
  model variants `_tinted` for the station blocks. Item models = combined
  column_iron_item(_tinted) (shaft+foot+cap).
- **Texture v4:** two vertical rivet rows (2px bolts, highlight+shadow, every 4px) like the
  14 St photo, center sheen band, edge shading.
- Headless gotcha reminder: setblock needs explicit `[facing=..,down=..,up=..]` since
  placement-time connection computation only runs for real placements/neighbor updates.
- Verified via dev-client screenshots: plain + tinted 3-block stacks with foot and ceiling
  cap, rivets clearly visible through the green fallback tint; fare machines from iteration
  3 survived the reload (multiblock persistence).

### v1.7.0 iteration 3 (bench + MetroCard machine rework, from user photos)

- **Bench remodeled after the NYC wooden bench photo:** worn dark-walnut textures (`bench`
  + new `bench_frame`, no dither), bench_seat model = thick seat slab (y 6–9) + low
  backrest (z 12–14, y 9–15) + one armrest divider per segment + dark under-frame rail with
  recessed center leg; bench_leg_left/right are now solid end panels (x 2 thick, floor to
  y 13). Outline shape is a union (seat box + backrest box) so seated players aren't inside
  collision. Merge/multipart/sitting logic unchanged.
- **Fare machine is a real 2-block multiblock now:** door-style (Properties.DOUBLE_BLOCK_HALF,
  lower = data half; getPlacementState requires replaceable above, onPlaced sets the upper,
  getStateForNeighborUpdate breaks the pair, loot gated half=lower). Footprint 14 px wide ×
  8 px deep, flush against the wall behind (z 8..16 in the north model — same wall-side
  convention as the PIDS). Front art is one 32x32 texture split across the halves by UV
  (upper body 12 px → uv [0,0,16,6]; lower 16 px → uv [0,8,16,16]); item is a flat icon
  (2-tall models don't fit inventory slots). FareHandler/TicketSystem behavior unchanged,
  works on both halves.
- **Test-server gotcha:** `/setblock` does NOT call onPlaced — multiblock partners (PIDS
  halves, fare machine upper) must be setblock'd explicitly with the right half/side
  property when testing headless. Real item placement does it automatically.
- Verified via dev-client screenshots: 3-segment merged bench against a wall (photo-like:
  slab, backrest, dividers, end panels, dark frame), two fare machines flush on the wall
  with the full MetroCard face split correctly across both halves.

### v1.7.0 iteration 2 (user feedback pass)

- **Textures redone without 1px dither** (read as "missing textures" in game): column_iron
  is now riveted blue-gray steel, leaning_bar brushed steel, fare machine has real 32x32
  MetroCard-machine pixel art from the user's photo (marquee, green screen, yellow stickers,
  blue strip, red change flap; block textures may be any square size). Unique numbered icons
  for railing_creator_3/5/7/9 and viaduct_creator_3/5/7 (3x5 pixel digit font).
- **Station-color columns paint the WHOLE column** (3 padded boxes over flanges+web) in
  `Station.getColor()` — fallback dark green outside station areas.
- **next_train_sign block REMOVED** (code + assets + lang + tag). The arrow lives in the
  mini PIDS "Next train" mode instead: when lit it shows "← Next train"/"Next train →"/
  "↓ Next train ↓" toward the arriving train's platform
  (`platformIdMap.get(arrival.getPlatformId()).getMidPosition()`, viewer-relative via
  screenFacing — paintScreen now takes the per-side facing since hanging screens are
  double-sided and the arrow must flip with the side).
- **Mosaic fix:** block model is now EMPTY (particle-only), band painted at local z
  +0.5−0.01 (directly on the wall behind, no z-fighting, no floating plate); shape thinned
  to z 15..16; item uses a flat mosaic icon.
- **Benches merge and are sittable:** BenchBlock has left/right BooleanProperties computed
  from same-facing neighbors (multipart blockstate: seat always, leg models only on
  unconnected ends). Right-click sits the player on an invisible `entity/SeatEntity`
  (EntityType `seat`, EmptyEntityRenderer client-side, discards itself when empty or the
  bench is gone; getPassengerRidingPos returns the seat pos — 1.20.4 renamed the old
  mounted-height-offset API).
- **Fare machine is functional with MTR:** `FareMachineBlock.FARE_HANDLER` hook (common
  block, no MTR imports) installed by MtrStationDecor: emerald in hand → decrement 1,
  `TicketSystem.addBalance(world, player, 10)` (MTR's own 1-emerald=$10 rate, verified from
  PacketAddBalance bytecode: EMERALD_TO_DOLLAR=10), XP-orb sound, actionbar with new
  balance; empty hand → balance readout via `TicketSystem.getBalance`.
- **Creators flash when armed:** hasGlint2 override on pillar/railing/viaduct creators —
  enchant glint while item NBT has `ItemBlockClickingBase.TAG_POS` ("pos", set between the
  first and second node click by MTR's base class).
- Verified via dev-client screenshots: 3-bench merge (legs at ends only), MetroCard art,
  riveted column, full-green fallback color columns, "23 ST" name board, mosaic band flush
  with block back. NOT yet user-tested: sitting feel/height, fare machine balance flow on
  their server, glint, mini-PIDS arrow with live trains.

- **Shared groundwork:** PidsNycRenderer's inner Painter extracted to
  `client/mtr/CanvasPainter` (public: quad/text/textScrolling/textScrollOnce/textCentered/
  textRight/circleBullet/wrap/width + static box/quad3d, behavior unchanged; announcement
  scroll speed const lives there, 2.5). Pillar creator's rail walk + buildColumn extracted to
  `mtr/RailBuildHelper` (walk(railMath, start, step, consumer-with-perpendicular),
  buildColumn with the ≤3-solid deck-skip, placeIfReplaceable).
- **Common decor (work without MTR, in ModContent):** globe_lamp_green/red (luminance 14),
  bench, platform_barrier, fare_machine (24px-tall model — block models may extend to
  32px, no multiblock needed), via `block/DecorBlock` (fixed shape) and
  `block/FacingDecorBlock` (HORIZONTAL_FACING + shape rotated from the NORTH definition;
  appendProperties adds FACING unconditionally — never read instance fields there, vanilla
  has the same super-constructor gotcha as MTR).
- **Ambience block** (common): `AmbienceBlock(+Entity)` — Sound (hum|vent), Volume, Radius
  4–48, synced NBT; `update_ambience` C2S validated like the others; `AmbienceScreen` GUI.
  Client `client/sound/AmbienceSoundManager`: END_CLIENT_TICK scan every 10 ticks, ±4 chunks,
  one repeating MovingSoundInstance per block (AttenuationType.NONE, volume computed per tick
  with linear falloff ×0.6, setDone when block gone/reconfigured/player beyond radius+8).
  Loops synthesized with numpy (10s, ends crossfaded, "stream": true in sounds.json):
  ambience_hum (50/100/150 Hz + low noise), ambience_vent (band-passed noise swell).
- **MTR station decor** (`MtrStationDecor`, behind isModLoaded): `StationDecorBlockEntity`
  (one synced field CustomName, "" = auto) + `StationDecorBlock` (Kind MOSAIC/COLUMN/
  NEXT_TRAIN + stationColor/named flags) + `client/mtr/StationDecorRenderer`. Blocks:
  station_name_mosaic (auto-sizing cream tile band, station-color border, name centered;
  right-click → StationSignScreen custom-name override), column_iron (plain FacingDecorBlock,
  NO BE) + column_iron_station/named/named_station (renderer overlays: station-color box
  around the web, black name boards on both flange faces), next_train_sign ("NEXT TRAIN" +
  ←/→/↓ arrow toward the platform with the soonest arrival + countdown; findClosePlatform
  radius 8, Platform.getMidPosition() for direction). Station name/color via
  `InitClient.findStation(BlockPos)` + Station.getName()/getColor() — same per-frame lookup
  MTR's own RenderStationNameBase does. `update_decor` C2S registered in
  MtrStationDecor.register() (NOT AnnouncerNetworking — keeps common code free of mtr-package
  references).
- **New creators** (same ItemNodeModifierSelectableBlockBase base as pillars, registered in
  MtrPillars): railing_creator_3/5/7/9 (saved block at ±width/2 at y=floor(railY) on the
  deck edge cells, walk step 0.3, dedup set, replaceable-only) and viaduct_creator_3/5/7
  (deck row at y−1 across ±r, girders at y−2 at ±r only, pillar pair at ±(r−1) — single
  centered for width 3 — every 8 blocks starting at 4; buildColumn's deck-skip passes
  through the fresh deck+girder).
- **Renderer coordinate cheat sheet** (worked out from the PIDS conventions): placement
  facing = playerFacing.getOpposite(); renderer rotate = 180 − facing.asRotation(); after
  translate(0.5,0,0.5)+rotate, model-NORTH-variant coords map to local as model/16 − 0.5
  with the viewer on local −Z; draw canvases at panelFrontLocalZ − 0.002 with
  translate(+w/2, top, z) + scale(−u,−u,u), 64 canvas units per block.
- **Dev tooling (big quality-of-life wins):** `modLocalRuntime` MTR in build.gradle — the
  dev client/server now RUN with MTR (visual testing!) while the shipped jar stays
  standalone. Dev-only screenshot hook in StationAnnouncerClient (isDevelopmentEnvironment
  gated): `touch run/screenshot.flag` while runClient is up → vanilla screenshot lands in
  run/screenshots/ — Read the PNG to SEE renderers headlessly (macOS screencapture/osascript
  are permission-blocked for this terminal). Verified this way: mosaic band (tile grid,
  border, "CITY HALL" custom name), column flanges + green fallback web, NEXT TRAIN box
  ("--" with no platforms), hanging PIDS regression after the CanvasPainter refactor.
- Verified: MTR server — all 13 new blocks/items place & round-trip NBT (CustomName,
  Sound/Volume/Radius), creator ids parse; no-MTR server — decor + ambience functional,
  zero mod errors. NOT yet in-game tested by the user: creator build flows, ambience
  loudness/feel, arrow sign with real platforms, mosaic/column look at real stations.

### v1.6.0 state — chime dropdown, PIDS↔control-box linking, live announcements

- **Chime dropdown:** the free-text "custom chime sound id" fields in the announcer and
  control box GUIs are now a shared dropdown (`client/gui/ChimeDropdown`) over the 11 sounds
  converted from the project-root `chimes/` folder (Marimba1–6, Synth1–5, wav → mono ogg via
  python soundfile) plus the built-in ding-dong. Catalog lives in
  `ModContent.CHIME_OPTIONS` (id, label, leadTicks); the client delays TTS by the selected
  chime's `leadTicks` so the sound finishes before speech starts (46/48/97 ticks vs 22 for
  the default). Unknown ids from old NBT/resource packs surface as a "Custom" entry, and the
  wire format is unchanged (still the chimeSound string).
- **PIDS displays link to PA Control Boxes** via the Speaker Link, through two new common
  interfaces so common code never touches MTR classes: `block/PaDisplay` (BE: box pos +
  showPaAnnouncement) and `block/PaDisplayBlock` (block: routes clicks on either multiblock
  half to the data half). `BlockPidsNyc.onUse2` returns PASS while the link item is held so
  `SpeakerLinkItem.useOnBlock` handles PIDS like speakers (select/link/relink/sneak-unlink,
  display-first or box-first). Box keeps a `Displays` long-array (NBT) beside `Speakers`;
  `AbstractPaBlockEntity.fire()` calls a new `onFired(world, message)` hook BEFORE the
  empty-sources check (displays update even with no speakers in range), which pushes the
  picked message to each loaded linked display (`live_message` + `live_start` epoch-ms NBT,
  synced) and prunes stale entries. Unlink All clears displays too; `onBreak2` +
  fire-time pruning handle broken displays; LinkLineRenderer draws beams to displays.
- **Hanging PIDS** now shows only the next two trains (no rotation, the number badge from
  iteration 4 is gone). While `live_message` is fresh, the bottom row becomes the
  announcement scrolling once across (Painter.textScrollOnce — one-shot whole-char marquee,
  text enters right/exits left, speed 1.5·size units/s; "fresh" = phase < maxWidth + text
  width, computed from client clock vs the synced epoch start, skew-tolerant). Mini
  unaffected.
- **"Happening now"** on wall/standing departures mirrors the linked box's message pool at
  all times: renderer reads the (client-synced) `ControlBoxBlockEntity.getText()` via
  `splitMessages` and rotates entries every 8 s; falls back to the brush-configured MTR
  message rows when unlinked or the box is out of client range.
- Verified on a fresh dedicated server WITH MTR (scratchpad pattern below): box+speaker+
  hanging PIDS placed by console, Displays/control_box merged, `/announce` → `live_message`/
  `live_start` observed on the PIDS, pool rotated in order on the second fire, speaker
  self-registered from ControlBox NBT, breaking one PIDS half removed the other, next fire
  pruned the stale Displays entry. No-MTR boot: zero mod warnings. NOT yet tested in-game:
  link item on PIDS halves, the scroll/rotation look, chime dropdown feel/lead timing.
- **Chime follow-up (user reported "chimes not working"):** proven NOT an asset problem —
  all 11 oggs decode with LWJGL STBVorbis (MC's exact decoder; test harness pattern: javap
  the launcher's lwjgl jars under ~/Library/Application Support/minecraft/libraries), and a
  live dev client (`./gradlew runClient --args="--quickPlayMultiplayer localhost:25565"`
  against the scratchpad server) logged `Playing sound station_announcer:chime_marimba1`
  after /announce. Mitigations added: cycling the chime dropdown now PLAYS A PREVIEW
  (PositionedSoundInstance.master → master category, ignores the Voice/Speech slider that
  gates real announcements) and resolveChime logs a warning when an id is missing
  client-side (= stale client jar / missing pack). Announcement scroll speed raised
  1.5→2.5 units/s per size after "a little too slow" feedback. Suspected real cause: stale
  client jar on the user's play machine (their client logs are NOT on this Mac; the
  ModrinthApp "Baker City 1.20.4" profile here has no station-announcer jar at all).
- **Pillar Creators (same release):** 10 items `pillar_creator_<width>x<spacing>` (1/3/5 ×
  4/6/8, plus 5x10) in `mtr/ItemPillarCreator` + `MtrPillars`. Extend MTR's
  `ItemNodeModifierSelectableBlockBase` — the exact base the bridge creator uses — so
  sneak-right-click saves the material into item NBT (base handles it + tooltip), clicking
  two connected rail nodes yields `onConnect(Rail, player, stack, radius, height)` (we pass
  super(true, 0, 0) and keep our own width/spacing; base's radius/height args unused).
  MTR's RailActionModule has only fixed bridge/tunnel/wall actions (no extension point), so
  we build directly in onConnect: walk `rail.railMath.getPosition(distance, false)` from
  spacing/2 to getLength() stepping spacing (offset start keeps nodes/platforms clear);
  pillar pair offsets = ±width/2 along the local perpendicular
  (`new Vector(Δx,0,Δz).normalize().rotateY(PI/2)` — same as RailAction bytecode); columns
  go from floor(railY)−1 down, replacing `isReplaceable()` blocks (air/water/plants), pass
  through ≤3 solid blocks directly under the rail (bridge decks) until first block is
  placed, then stop at the first solid = ground. All vanilla world access via holder
  `.data`. Verified: MTR server boots, items registered (`/give` parses them), tooltip line
  via TextHelper.translatable. NOT yet in-game tested: actual node-click build flow, pillar
  alignment on curves, deck pass-through feel.
- **Test-server gotchas:** piping the whole command list into the server's stdin races
  startup ("An unexpected error occurred" for every command). Use a fifo:
  `mkfifo cmdpipe; tail -f cmdpipe | java -jar fabric-server.jar nogui` then echo commands
  into the pipe one at a time. In dev runs, `net.minecraft` loggers are capped at INFO by
  loom's log4j (debug.log included) — relaunch with `JAVA_TOOL_OPTIONS=-Dfabric.log.level=debug`
  to see SoundSystem "Playing sound" lines.

### v1.5.0 state — NYC PIDS blocks (MTR integration) + Baker City tab

- **Toolchain changed in 1.5:** Gradle **8.14** + Loom **1.10.5** (upgraded from 8.7/1.6.12
  because the MTR jar is built with Loom 1.10.5 and older Loom refuses to remap it). Same
  JAVA_HOME trick still applies.
- MTR is a **compile-only, runtime-optional** dependency
  (`modCompileOnly maven.modrinth:minecraft-transit-railway:FABRIC-4.0.5+1.20.4` via the
  Modrinth maven, plus `compileOnly jsr305` for its annotations). All MTR-touching code lives
  in `com.stationannouncer.mtr` / `com.stationannouncer.client.mtr`, reached only behind
  `FabricLoader.isModLoaded("mtr")`. PIDS recipes/loot use `fabric:load_conditions`
  (all_mods_loaded mtr) and the pickaxe tag uses `required:false` entries, so a no-MTR boot is
  warning-free (verified).
- **Key MTR insight:** MTR's mapping layer classes ARE vanilla classes underneath
  (BlockExtension extends Block, BlockEntityExtension extends BlockEntity with sync built in),
  so plain yarn `Registry.register` and a plain vanilla `BlockEntityRenderer` work. Explore
  MTR APIs by `javap` against the downloaded Modrinth jar (raw jar shows intermediary names;
  the loom-remapped dev jar shows yarn). Do NOT extend BlockPIDSHorizontalBase — it is a
  two-block multiblock; extend BlockPIDSBase directly (single block, own FACING via
  DirectionHelper, shapes via IBlock.getVoxelShapeByDirection, placement state via
  `getDefaultState2().with(new Property<>(FACING.data), facing.data)`).
- Verified on a real dedicated server WITH MTR 4.0.5: blocks register/place, BE serializes
  through MTR's BlockEntityBase (platform_ids/message*/hide_arrival* NBT observed live).
  Verified WITHOUT MTR: zero errors, everything else works.
- **In-game confirmed by the user (2026-07-21):** screens render with live MTR data — route
  map, countdown, bullets, orientation and ETA math all correct. Iteration 2 changes from
  that feedback: LED-marquee **scrolling text** (Painter.textScrolling, whole-char window, no
  clipping needed) wherever text could overlap; **circular** route bullets (16-segment fan);
  **two-part multiblocks** so units break from either end (wall/standing = half=lower/upper
  vertical, data+BE on lower; hanging = side=left/right horizontal, two blocks wide, data on
  left; partner auto-placed in onPlaced2, break propagation via getStateForNeighborUpdate2 →
  Blocks.getAirMapped(), loot gated on the data half like vanilla doors); wall/standing
  screens now float 0.5–2.5 blocks above floor (models split into _lower/_upper pieces since
  JSON elements cap at y=32). Break propagation + BE placement verified on the live MTR
  server via setblock/execute.
- **Gotcha:** addBlockProperties runs from the Block super-constructor — it must not read
  instance fields (caused an ExceptionInInitializerError). Property sets that differ per
  variant need subclass overrides (BlockPidsNyc.Hanging).
- Still needs an in-game look: auto-placement of partner halves (player placement path),
  scrolling speed/feel, hanging panel proportions, unique item icons.
- Iteration 4 (2026-07-21, after in-game screenshots — route map + connections confirmed
  working): route-map pill now marks the CURRENT station (subList(index, …), highlight i==0);
  scroll speed size*2.5→1.5 units/s; mini "Next train" mode — right-click mini WITHOUT brush
  opens MiniPidsScreen (one toggle; GUI_OPENER chained in MtrPidsClient, onUse2 override
  falls through when super returns non-SUCCESS), synced via update_mini C2S handled in
  MtrPids.register(), stored as next_train_mode bool NBT on PidsBlockEntity
  (readCompoundTag/writeCompoundTag overrides — verified round-trip on live server). Lit
  logic client-side in PidsNycRenderer.paintNextTrain: lit while remaining ≤60 s, dark from
  5 s after 0, 10 s relight cooldown, state kept per pos in NEXT_TRAIN_STATE. Also fixed
  latent bug: PidsBlockEntity now passes the real isPrimary/primaryPos hooks to MTR's
  BlockEntityBase (was (w,p)->true / identity).
- Iteration 3 (same day): sixth block **pids_nyc_hanging_mini** (PidsStyle.HANGING_MINI,
  BlockPidsNyc.Hanging(style) — shares pole model/blockstate pattern; panel y 4..12 px, single
  departure row, ~27-unit canvas). Route map now shows **connecting routes** as small circle
  bullets under station names (connectionsAt(): scans MinecraftClientData routes via
  RoutePlatformData.platform.area station match, 3 s cache, right-margin capped; rows grow
  +11 units when connections exist — dynamic truncation). **Auto platform detection** like
  MTR's PIDS: when getPlatformIds() is empty, InitClient.findClosePlatform(pos, 5, consumer)
  supplies platforms (same call MTR's RenderPIDS makes); "no platforms" hints now say
  NO NEARBY PLATFORM. pids_frame texture is now flat 26,26,28 with explicit uv [0,0,16,16]
  on every model face (grey bars on backs/sides were texture pixels + auto-UV stretch).
  Mini verified on the live MTR server (registration, BE, both-way break propagation).
- **CRITICAL MTR data lesson (took 3 attempts):** normal clients do NOT get the full
  `Data.routes` object graph — only `simplifiedRoutes` is synced. And for interchanges,
  don't scan anything at all: `SimplifiedRoutePlatformSchema extends
  InterchangeColorsForStationName`, i.e. **every SimplifiedRoutePlatform (stop) carries its
  own baked interchange data** — `stop.forEach((color, routeNamesForColor) -> ...)` with
  the current line already excluded server-side. This is exactly what MTR's own
  RouteMapGenerator uses (found by grepping its bytecode for data-class calls). Attempt 1
  (scan Data.routes) found nothing — empty on clients; attempt 2 (cross-scan
  simplifiedRouteIdMap) also failed in-game; attempt 3 (embedded data) is the correct one.
  Display label: no route number field — first-number-else-first-letter heuristic on the
  route name (PidsNycRenderer.routeLabel).

### v1.4.0 state

- v1.4.0: client-side playback interruption — a newly received announcement clears pending
  TTS tasks, stops speech (narrator clear / kill the OS TTS process) and stops the chime
  (now played as a tracked PositionedSoundInstance via SoundManager.play/stop instead of
  world.playSound) before playing. Latest packet wins; purely client-side, no protocol
  change. Block display name is now "PA Station Announcer" (lang-only rename; the id
  `announcer_block` and the mod name are unchanged). Compile-verified; interruption
  behavior needs an in-game listen.

### v1.3.0 state

- v1.3.0 change: control box pool announcements split on `||` (MESSAGE_SEPARATOR) instead of
  newlines; verified on the prod server (MessageIndex advanced 0→2 over two in-order triggers
  of a 3-message `a || b || c` pool). Pre-1.3 newline-separated pools are NOT auto-migrated —
  they play as one message until the user adds `||`.
- **`releases/` at the project root keeps one jar per released version** (1.2.0, 1.3.0, ...).
  When shipping a new version: build, then `cp build/libs/station-announcer-<v>.jar releases/`.
  Never delete old entries — the user wants prior versions preserved.

### v1.2.0 state (all still true)

- `./gradlew clean build` succeeds; **distributable jar at `build/libs/station-announcer-<version>.jar`**
  (remapped to intermediary mappings — this is the one that goes in a `mods` folder; the
  yarn-named jar in `build/devlibs/*-dev.jar` is dev-environment-only and won't work in production).
- Verified on a real production Fabric 1.20.4 server (official launcher + fabric-api in mods/):
  v1.2.0 functional test via console commands — setblock control box + speaker, `/data merge`
  the link, **speaker self-registered into the box's Speakers list** (validated the
  structure-paste path; needs a *ticking* chunk — use `forceload add` when testing headless,
  command-loaded chunks don't tick BEs), `/announce` triggered the box, breaking the speaker
  emptied the list, and an announcer with old v1.1 NBT (RandomOrder/AutoMinSeconds/...)
  loaded + triggered fine. Test-server pattern: fabric server launcher + eula + mods/ in the
  scratchpad, boot with piped console commands, grep the log.
- Not covered headless: Speaker Link item interactions and the three GUIs (need a real player;
  they share the same server-side add/remove/apply methods the console test exercised).
- Not yet done: interactive client testing of the GUI screen and TTS/chime feel
  (`./gradlew runClient`), no automated tests, en_us is the only language.

## Build environment (IMPORTANT)

This Mac has **no system Java or Gradle**. Before any `./gradlew` command:

```sh
export JAVA_HOME="$HOME/Library/Application Support/minecraft/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
```

That is the Minecraft launcher's bundled JDK 17 (full JDK, has javac). Homebrew exists at
/opt/homebrew if a newer JDK is ever needed.

### Pinned stack (gradle.properties / build.gradle)

| Component | Version | Note |
|---|---|---|
| Minecraft | 1.20.4 | |
| Yarn mappings | 1.20.4+build.3 | |
| Fabric Loader | 0.19.3 | fabric.mod.json requires >=0.15.0 |
| Fabric API | 0.97.3+1.20.4 | last major line for 1.20.4 |
| fabric-loom | 1.10.5 | required by the MTR dep (built with Loom 1.10.5) |
| Gradle | 8.14 (wrapper) | Loom 1.10 needs ≥8.13 |
| Java | 17 (`options.release = 17`) | |
| MTR (optional) | FABRIC-4.0.5+1.20.4 | modCompileOnly, Modrinth maven |

When unsure about a 1.20.4 API, don't guess — `javap` against the mapped jar:
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/...-named jar` (and remapped
Fabric API jars under `.gradle/loom-cache/remapped_mods/`). All APIs used here were verified that
way (e.g. `Narrator.say(String, boolean)` has **no volume param**; `BlockWithEntity` requires a
`MapCodec` override since 1.20.3; classic `Identifier`+`PacketByteBuf` Fabric networking is still
current in this Fabric API version).

## Architecture

```
com.stationannouncer              (common — safe on dedicated servers)
├── StationAnnouncer              ModInitializer; GUI_OPENER Consumer<BlockEntity> indirection
├── ModContent                    3 blocks + 4 items + 3 BE types + sound + Redstone tab
├── AnnouncerRegistry             weak set of loaded AbstractPaBlockEntity (announcers AND
│                                 control boxes) → tag lookup, /announce trigger
├── block/AbstractPaBlockEntity   shared PA-source base: Text/DelaySeconds/Tag/ShowChat/
│                                 PlayChime/ChimeSound NBT, trigger()/tickPending delayed fire,
│                                 fire() = pickMessage + collectSources + per-player
│                                 loudest-source dedup broadcast, registry lifecycle, sync()
├── block/AnnouncerBlock(+Entity) standalone announcer: own Volume/Radius, single message;
│                                 POWERED rising-edge redstone in neighborUpdate
├── block/ControlBoxBlock(+Entity) PA network brains: message pool (one per line,
│                                 RandomOrder/MessageIndex), auto-trigger (AutoMin/MaxSeconds),
│                                 Speakers long-array NBT; collectSources reads loaded linked
│                                 speakers (skips unloaded chunks, prunes stale entries);
│                                 unlinkAllSpeakers; POWERED redstone like announcer;
│                                 onUse PASSes when holding Speaker Link
├── block/SpeakerBlock(+Entity)   playback endpoint: Volume/Radius + ControlBox (long) NBT;
│                                 one-shot serverTick link validation (re-registers with box
│                                 after structure paste; readNbt re-arms it); synced computed
│                                 LinkState byte (NONE/OK/BROKEN/NOT_LOADED) for the GUI;
│                                 onStateReplaced removes itself from its box's list
├── item/SpeakerLinkItem          bidirectional: selection in item NBT (SelectedPos long +
│                                 SelectedDim + SelectedType box|speaker; legacy SelectedBox
│                                 still read); box-first keeps box selected, speaker-first
│                                 clears after linking; link/relink/unlink/clear + actionbar
│                                 feedback; same-dim + maxLinkDistance checks in linkPair()
├── net/AnnouncerNetworking       S2C "announce"; C2S "update_announcer" / "update_control_box"
│                                 / "update_speaker" / "unlink_all" (all validated: ≤64 blocks,
│                                 canPlayerModifyAt, length caps)
├── command/AnnounceCommand       /announce, tag= tokens, tab-complete from AnnouncerRegistry
└── config/ServerConfig           announcePermissionLevel, maxTextLength, maxLinkDistance

com.stationannouncer.client       (all @Environment(CLIENT))
├── StationAnnouncerClient        GUI_OPENER dispatches by BE type to the three screens;
│                                 S2C receiver → chat/actionbar + chime + TTS (22-tick lead)
├── ClientConfig                  enableTts, enableChime, displayMode, ttsBackend, voice
├── gui/IntSlider                 shared labeled int slider
├── gui/AnnouncerScreen           announcer settings (no pool/auto since 1.2)
├── gui/ControlBoxScreen          pool + delay/tag + auto + toggles + order/chime sound +
│                                 speakers status line + Unlink All / Done / Cancel
├── gui/SpeakerScreen             volume + radius sliders + link status line
├── render/LinkLineRenderer       WorldRenderEvents.AFTER_TRANSLUCENT: pulsing white beams
│                                 (crossed translucent quads on RenderLayer.getDebugQuads,
│                                 MTR lift-link style) box↔speaker while Speaker Link is held.
│                                 NOTE: vanilla RenderLayer.getLines() was tried first and
│                                 rendered buggily (line-shader normal expansion) — keep quads.
│                                 Scans BEs in chunks ±6 around player, dedups from both ends
└── tts/                          TtsManager → NarratorBackend → SystemTtsBackend
```

### Key invariants — keep these when changing code

- **Server-authoritative**: the server computes who is in radius and per-player volume
  (linear falloff, 25% at the edge); clients only present. TTS must never run server-side.
- **Client classes are never classloaded on dedicated servers**: the only path from common code
  to the screen is the `StationAnnouncer.GUI_OPENER` consumer, invoked strictly under
  `world.isClient`. Preserve this pattern for any new client feature.
- **Rising edge only**: `POWERED` blockstate property tracks the previous signal;
  `getPlacementState` seeds it so placing next to a hot wire doesn't fire.
- **Lifecycle safety**: `markRemoved()` cancels pending announcements and deregisters from
  `AnnouncerRegistry` (covers both block break and chunk unload); the registry weakly references
  BEs and validates entries on every query. Recipients are computed at fire time, so players
  joining mid-delay are included.
- **Caps**: text ≤512 chars (`AnnouncerBlockEntity.MAX_TEXT_LENGTH`, further capped by
  `ServerConfig.maxTextLength`), tag ≤64, delay ≤60 s, radius 1–128. Enforced in setters and at
  packet read (`buf.readString(max)`).
- Empty announcement text = do nothing (checked at trigger and at fire).
- Per-block presentation flags (`ShowChat`, `PlayChime`, `ChimeSound`) ride in both packets;
  NBT read defaults the booleans to true for pre-1.0.0-era blocks (`!nbt.contains(...) || ...`).
- Custom chime ids resolve client-side via `Identifier.tryParse` + `SoundManager.get(id)` null
  check, falling back to the built-in `station_announcer:chime`; custom sound *files* are
  distributed by resource packs (audio plays per-client — there is no runtime file upload).
- TTS voice selection exists only on the system backend (client config `voice`); Mojang's
  narrator lib has no voice/volume API.
- Multi-message (control box only since 1.2): `text` stays one NBT string; messages are split
  on `||` (since 1.3; newline-based in 1.1–1.2), trimmed, blanks dropped, chosen at fire time
  (RandomOrder=true → random pick; false → cycle via persisted MessageIndex; pickMessage runs
  before collectSources, so the rotation advances even if no speakers end up in range). The
  S2C packet carries only the chosen message.
- Auto-trigger (control box only since 1.2): `AutoMinSeconds` 0 = off; next firing scheduled at
  random `nextBetween(min, max(min,max)) * 20` ticks (transient `autoTicks`, rescheduled on
  settings change/reload). Runs in the server ticker, so only while the chunk is ticking; goes
  through `trigger()` and therefore respects the Delay setting and the empty-text guard.
- v1.1→1.2 migration: announcers silently ignore leftover RandomOrder/AutoMin/AutoMax/
  MessageIndex NBT (verified on a live server). Never crash on unknown/legacy keys.
- Link integrity rules: speaker→box is the persisted source of truth on the speaker
  (ControlBox long); box→speakers list is persisted too but self-heals — speaker's one-shot
  tick re-registers after structure paste, `collectSources` prunes entries whose loaded pos has
  no speaker, `onStateReplaced` (not markRemoved — that fires on unload too!) removes broken
  speakers from their box. Unloaded chunks are never force-loaded; unloaded speakers stay
  silent and unloaded boxes just can't be validated yet (LINK_NOT_LOADED).
- Per-player dedup lives in AbstractPaBlockEntity.fire(): one packet per player, loudest
  in-range source wins (its pos is the chime origin).
- Speaker Link interactions only work because SpeakerBlock/ControlBoxBlock.onUse return PASS
  when the held stack is the link item — keep that if adding held-item behaviors.

## Assets (all placeholders, generated — fine to replace with real art)

- Textures + icon: generated by a pure-Python PNG writer (speaker grille look).
- `assets/station_announcer/sounds/chime.ogg`: real Ogg Vorbis, ~1.1 s two-tone bell,
  synthesized with python soundfile/numpy (installed via pip --user). If regenerating assets,
  scratch scripts were session-local; rewriting them is trivial.
- Sound event `station_announcer:chime` registered via `sounds.json`; chime length is why
  `StationAnnouncerClient.CHIME_LEAD_TICKS = 22`.

## Everything done so far (chronological)

1. Scaffolded Gradle/Loom project from scratch (wrapper jar fetched from gradle/gradle v8.7.0
   tag; versions pinned from Fabric meta + Modrinth APIs); first build green.
2. Verified all uncertain 1.20.4 APIs via javap before coding (Narrator, EditBoxWidget,
   SliderWidget, validateTicker, markForUpdate, ClientWorld.playSound, networking signatures,
   AbstractBlock.createCodec).
3. Implemented block + block entity with NBT persistence and client sync.
4. Implemented tag registry, S2C/C2S networking, GUI screen, redstone edge triggering,
   /announce command with suggestions, server + client Gson configs, TTS manager with
   narrator/system backends, chime + textures + lang + loot table + pickaxe tag.
5. Booted dedicated server end-to-end (`run/` dir, eula pre-accepted) and exercised /announce.
6. Wrote README (build/install/usage/config/MTR notes), LICENSE (MIT), .gitignore.
7. This file.
8. (2026-07-20) Added per-block Chat Text and Chime ON/OFF toggles + optional custom chime
   sound id (GUI, NBT, both packets), client-config `voice` for the system TTS backend;
   rebuilt and re-verified on the production Fabric server.
9. (2026-07-20) v1.1.0: random auto-trigger (Auto min/max sliders, 0 = off, up to 600 s) and
   multiple messages per block (one per line, Random/In Order toggle). GUI relaid out to fit
   (rows at ROW=24, buttons end ~230).
10. (2026-07-20) v1.2.0: multi-block PA network. Extracted AbstractPaBlockEntity from the
    announcer; added PA Control Box (all announcer features minus own playback + pool/auto
    moved here from the announcer), PA Speaker (per-speaker volume/radius, NBT-stored link,
    LinkState GUI status), Speaker Link item (select/link/relink/unlink/clear), per-player
    loudest-speaker dedup, 4 recipes, new textures, split C2S channels per block type,
    maxLinkDistance server config. Functionally tested on the production server via console
    (link validation, trigger, break-cleanup, v1.1 NBT migration).
11. (2026-07-20) Speaker Link made bidirectional (select either end first; speaker-first
    clears selection after linking, box-first keeps the box selected) and added
    LinkLineRenderer (white lines box↔speaker while the tool is held). Line rendering is
    compile-verified only — needs an in-game look.
12. (2026-07-20) v1.3.0: pool separator changed from newlines to `||`; added `releases/`
    folder preserving one jar per version (per user request, never delete old versions).
13. (2026-07-20) v1.4.0: latest-wins audio interruption in StationAnnouncerClient
    (interruptPlayback: clear TASKS + TtsManager.stop + SoundManager.stop(currentChime));
    chime switched to tracked PositionedSoundInstance. Renamed block display name to
    "PA Station Announcer".
14. (2026-07-20) Link visuals rewritten after in-game feedback ("really buggy" with
    RenderLayer.getLines): now MTR-style pulsing translucent quad beams (double-sided crossed
    ribbons, BEAM_RADIUS 0.05, alpha 0.3–0.8 sine pulse) on RenderLayer.getDebugQuads.
    (2026-07-21) STILL buggy in-game at AFTER_TRANSLUCENT with translucent quads (no depth
    write + 4 overlapping alpha layers = shimmer). Third iteration: moved to
    WorldRenderEvents.AFTER_ENTITIES (the same phase/pipeline as the PIDS BER quads, which
    render rock-solid) and made the beams fully OPAQUE, pulsing brightness (0.7–1.0 white)
    instead of alpha. Lesson: for world overlays prefer the entity phase + opaque colors;
    translucent debug quads outside the BER pipeline flicker.
15. (2026-07-21) v1.5.0: five MTA-style NYC PIDS blocks (wall route-map/departures ×2-tall
    thin panels, standing double-sided ×2, hanging B-Division rotating clock) extending MTR's
    BlockPIDSBase — brush opens MTR's own PIDS config screen (platform selection; message rows
    double as "Happening now"). One shared PidsBlockEntity + PidsNycRenderer (emissive
    debug-quad + text canvases; min→sec countdown, invert+flash at 0, SimplifiedRoute route
    map, 4s rotating bottom row on hanging). New "Baker City" creative tab holds ALL mod
    items (ModContent.BAKER_CITY_ENTRIES, MTR module appends). Toolchain: Gradle 8.14 +
    Loom 1.10.5. MTR names use "Eng|Other" format — always firstLang() before display.
16. (2026-07-22) v1.6.0: chime dropdown (11 chimes converted from chimes/, per-chime TTS
    lead so the sound finishes before speech starts); hanging PIDS trimmed to the next two
    trains (list-number badge and 4 s rotation removed); PIDS displays linkable to PA
    Control Boxes with the Speaker Link (PaDisplay/PaDisplayBlock common interfaces,
    Displays NBT list, onFired hook); live announcements scroll once across the hanging
    clock's bottom row (mini unaffected); wall/standing "Happening now" mirrors the linked
    box's pool, rotating entries every 8 s. Functionally verified on a fresh dedicated MTR
    server via console commands.
17. (2026-07-22, same 1.6.0 release) Chime dropdown preview-on-cycle + missing-chime client
    log warning after "chimes not working" report (assets proven fine — see Current state);
    announcement scroll speed 1.5→2.5. Ten Pillar Creator items (1/3/5 wide × 4/6/8/10
    spacing) extending MTR's ItemNodeModifierSelectableBlockBase: sneak-click picks the
    material, clicking two connected nodes builds pillar columns down to the ground along
    the rail every <spacing> blocks (details in Current state).
18. (2026-07-22) v1.7.0: station decor + ambience + structure creators. CanvasPainter/
    RailBuildHelper extractions; 6 common decor blocks + ambience block with synthesized
    hum/vent loops; MTR station decor (mosaic name band, 4 iron columns, next-train arrow
    sign) driven by InitClient.findStation; railing creators 3/5/7/9 + viaduct creators
    3/5/7. Dev tooling: modLocalRuntime MTR + screenshot.flag hook → first ever headless
    VISUAL verification of renderers (screenshots read back as PNGs). Full details in
    Current state.

22. (2026-07-27) v2.3.0 shipped: also the shared PlatformPicker (checkbox rows, route
    chips, nearest-first) now used by BOTH the railroad boards and the NYC PIDS — the
    brush opens our own NycPidsScreen instead of MTR's filter dialog — plus the
    departures board's message rules (drops the 4th departure past 4 lines, caps at 5,
    ellipsis-trims over-wide lines).

21. (2026-07-27) v2.3.0: Railroad PIDS — wall/standing portrait boards (line name +
    clock, colour departure bar, station list with live 2-minute connections) and a
    ceiling-hung two-screen board (next train / four-departure TIME-DESTINATION-ETA-TRK
    list) with timed, pinned or content-aware flipping. Own block entity, own brush
    screen with a station-wide platform picker. Full details in Current state.

20. (2026-07-24) v2.2.0: zebra boards (wall + hanging variants, merging runs, bolted
    end plates, hanger poles on the end blocks) and the PIDS drop pole slimmed to the
    hanging PIDS's own 2 px gauge. Full details in Current state.

19. (2026-07-23) v2.0.0: MTR-charging turnstiles (walk-through fare checkpoints, entry+
    exit via TicketSystem.passThrough, dynamic overhead tubing, end caps); conduit pipes
    (3 sizes x 5 tinted colors x 1-4 per block, segment inherit, brush repaint/resize,
    junction boxes, caps); holding lights yellow/green timed off live arrivals with brush
    platform picker + hanging pole; PIDS drop poles. Also this era: globe lamp rework,
    entrance railings + merging station sign, mosaic border fix, user-added platform
    barrier. Full details in Current state.

## Conventions

- Package root `com.stationannouncer`; ids via `StationAnnouncer.id(path)`.
- Translation keys: `gui.station_announcer.*`, `commands.station_announcer.announce.*`,
  `subtitles.station_announcer.chime`.
- A git repository since 2026-08-07 (baseline = pre-addon v2.4.0; one commit per
  feature). No CI. Mixins exist but ONLY for the dispatch addon
  (`station_announcer.mixins.json`, package `com.stationannouncer.mixin`) — the base
  mod's blocks/renderers stay mixin-free.
