# Turnstile / HEET v2 — from-scratch rebuild with animation (2026-09-06)

Thomas: "complete redo of the subway turnstiles and HEET, they don't look right,
and I'd love to add animation." Answers he gave (do not re-ask): research the
real hardware myself and show previews; everything was wrong (proportions,
flat textures, tripod geometry) — start from zero; animation = **turn AND
physically block until paid**; test on the headless rig, he looks at screenshots.

## Research (photos in the session scratchpad `ref/`, all Wikimedia Commons / turnstiles.us)

Sources: Commons "NYC Subway turnstile equipt with OMNY", "86 Street turnstiles vc",
"Union Sq turnstiles vc", "NYC Subway OMNY Turnstyle - closeup", "NYC Subway Turnstile
Exit Light", "Queens Plaza IND td (2023-06-26) 07", "Broad Channel IND td (2023-09-04) 09",
"33rd St IRT Lex td (2018-11-20) 14"; turnstiles.us "MTA Transit Turnstile" installation
page + its HEW/HET dimensioned drawing (Strocchia Iron Works 022117); dimensions.com
tripod turnstile (38.6 in tall, 23.6 in opening); patents US 5,056,261 / 8,905,218 /
6,044,586 (tripod axis inclined ~40–45° from vertical toward the lane, arms along the
edges of an equilateral pyramid, 120° apart, each ~135° to the axis).

What the real things are:

- **Low turnstile (Cubic AFC, 1994 → today).** Brushed stainless cabinet ~39 in tall,
  ~12 in wide, ~36 in long, advertising poster on the approach face. Top: MetroCard
  swipe track along the lane edge with arrows, green LCD, and since 2019 a black OMNY
  tablet on a slanted pedestal at the approach end (blue corner lights, "TAP HERE").
  Tripod hub on the lane side at the top corner; three ~20 in stainless arms. At the
  **paid end** a slim stainless **indicator pylon** ~5.5 ft tall: black display window,
  "Entry" label, two round lamps (green ✓ / red ✗) toward the unpaid side; the paid side
  carries the black "Exit" label and the green disc with an orange diagonal arrow.
  Stainless **grab-rail arches** (~2 in tube, ~7 ft high) spring from pylon to pylon
  over each lane. Exit-only lanes show a red no-entry roundel toward the unpaid side.
  The reader "always unlocks the turnstile to the LEFT of it" → cabinet on the rider's
  right, arm reaching left across the lane.
- **HEET / "iron maiden".** 304 stainless, 7 ft tall, footprint 5'6" × 3'8". Rotor of
  **three wings** (drawing's overhead view), each ~14 curved 1 in bars at 5.09 in
  pitch, arm arc R32 in; fixed comb bars on the frame post interleave with the wings;
  a curved **perforated sheet** (16 ga, 1/4 in holes) cage the rider slides along;
  round **drum canopy** on top carrying the black "MetroCard Entry ↓" band; indicator
  pylon (display + circles + "Entry") on the frame post; OMNY pedestal beside it.

## Scale and frame

1 block = 1 m → 1 px = 6.25 cm (≈2.5 in). Generator (`tools/gen_turnstile_assets.py`)
and renderer share the **NORTH frame**: an ENTERING rider walks toward −z, the unpaid
side is +z (south), cabinet on the rider's RIGHT at x 11..16, lane x 0..11, the next
unit's cabinet closes the lane at x = 0. `FACING` = the entering rider's direction
(placed from where the placer looks — `getHorizontalPlayerFacing()`, NOT its opposite
like the v1 family). Lane-side neighbour = `FACING.rotateYCounterclockwise()`.

## Blocks (ids unchanged; every v1 placement's blockstate is invalid and resets)

| id | class | properties | cells |
|---|---|---|---|
| `turnstile` / `turnstile_exit` | `TurnstileBlock` (BE on lower) | facing, half, **join**, **open**, indicator(off/go/stop/wait) | lower = cabinet + tripod (BER); upper = hood + OMNY + pylon (+ `ts_arch` when join) |
| `turnstile_cap` | `TurnstileCapBlock` | facing, half | end panel + post + arch riser; place on the last lane's lane side |
| `turnstile_heet` | `TurnstileHeetBlock` (BE on lane_lower) | facing, **part**(lane_lower/lane_upper/comb_lower/comb_upper), open, indicator | 2 wide × 1 deep × 2 tall; comb cell on the rider's right; drum canopy drawn by lane_upper up to y 24 (world 2.5 blocks) |

Models `ts_*` (19), textures `ts_*` (10, 32 px steel / 64 px pylon + sign), all
generated — never hand-edit. `verify()` checks when-keys against the Java property sets
(PROPS at the bottom of the generator) and every face's explicit uv. `face_uv()`
reproduces vanilla auto-UV and wraps it onto the sprite, so elements outside 0..16
(arch to x −2.5, drum to y 24, HEET beam across two cells) are safe from atlas bleed.
gen_gate_assets.py's door kick panel and the track warning gate used the old
`turnstile_steel` texture → repointed to `ts_steel`.

Lamps: pylon lamp plates + display strips are separate models per indicator state
(`ts_lamp_{go,stop,wait}`, `ts_lamp_exit_*` on the paid face for exit lanes,
`ts_heet_lamp_*` on the comb_upper pylon). INDICATOR lives on the turnstile UPPER half /
HEET comb_upper cell; `TurnstileBlock.INDICATOR_DEADLINE` guards the clear tick.

## Blocking + animation design

- `FareLane` (shared): `onEntityCollision` on a LOCKED lane → cooldown → MTR
  `TicketSystem.passThrough` (unchanged semantics: MTR picks entry/exit, charges, sounds;
  exit-only lanes remind, never fine). Travel direction = which side of the barrier
  plane the rider stands on. GO → `Host.setOpen(true)` on the lane cells (collision wall
  x 0..11 / z 7..9 both halves drops) + `TurnstileBlockEntity.unlock(now, dir, uuid)`;
  STOP → `deny(now)` (rattle). WAIT/GO/STOP lamps as before.
- `FareLane.tickLock` (BE ticker, server): re-lock once the unlocking rider's centre is
  > 0.45 blocks past the plane in their direction, or 5 s after an unused unlock — and
  ONLY when no entity box overlaps the plane (this is what the 2026-07 blocking design
  lacked; it ejected players upward). Open with no record (restart mid-pass) closes when
  clear.
- Anti-hop: the wall spans both cells (2 blocks) — the arch is at 2.1 m so nobody jumps
  it. Full-height block was my call; ask Thomas if he'd rather allow turnstile-hopping.
- `TurnstileBlockEntity` syncs `TurnStart` (game time), `TurnDir` (±1), `DenyStart`;
  `openedAt`/`rider` are server-only. Client draws from the world clock (no client tick).
- `client/mtr/TurnstileRenderer`: textured cuboids on `RenderLayer.getSolid()` from the
  block atlas (`Sprite.getFrameU/V` take a **0..1 fraction** — bytecode-verified after the
  first build sampled hazard stripes off a neighbouring sprite), shaded by world-space
  normal (0.6 x² + 0.8 z² + y² × 1.0/0.5). Tripod: apex (9.45, 12.95, 8), axis
  normalize(−1,−1,0), arm k = rotate((−1,0,0), axis, θ + 120k) — one arm horizontal
  across the lane, two down-forward/down-back; θ>0 moves the blocking arm toward −z
  (Rodrigues: axis × (−1,0,0) = (0,0,−0.707)). Turn 8 ticks smoothstep, rattle 6°.
  Rotor: hub (16, ·, 8), three wings at 180 + 120k + ψ (φ from +x toward +z; +120° for a
  rider going −z), 12 bars/wing at pitch 2.4 from y 3.6, each 3 straight segments on a
  R 12 arc sweeping 50° (tip radius ≈ 10 px), spindle octagon r 1.5, 10-tick turn.
  Rest pose puts a wing into the cage (−x) and the other two at ±60° toward the comb;
  the compartment at 120° opens to the unpaid side. Comb bars sit at half-pitch offset.
  Rotor rotation on the matrix is avoided — positions are computed with trig, so the
  sign convention is explicit.

## Rig recipe (this session)

Server: `./gradlew runServer` in a background Bash (stdout → scratchpad/server.log;
world is `run/world_baker`, a Baker City copy — Embankment station covers x −489..−465
z 108..153 at ALL y, so the sky platform at y 150 there is inside a station for fare
tests). Client: `./gradlew runClient --args="--quickPlayMultiplayer localhost:25565
--username Rig"`. Any Gradle task while they run kills them — stop both, compile,
restart. `shot.sh scene.txt [settle]` copies chat commands into `run/commands.txt`
(one per 25 ticks), touches `run/screenshot.flag`, prints the PNG; `cam.sh x y z yaw
pitch`. `/gamerule sendCommandFeedback false` keeps chat off the shots. Mid-animation
pose: `/tick freeze` then `execute store result block <pos> TurnStart long 1 run
scoreboard players get Rig rig` with the score = gametime − 4 (client world time freezes
with the server's tick manager in 1.20.3+). Scene: turnstiles at x −478/−479(exit)/−480
+ cap −481, y 151/152, z 130, facing north; HEET lane −474 / comb −473.

## Round 2 (2026-09-07) — low unit rebuilt to Thomas's references

Thomas approved the HEET and rejected the low turnstile ("textures and geometry wonky"),
supplying a 3D render, a 3D-printed model and a photo of a new array. What they showed
that round 1 missed: the cabinet and the indicator column are ONE L-shaped unit; the lane
side is a deep dark recess under the tripod; the front-top corner is chamfered; the
column top is a chisel; readers sit mid-lid; the arches are smooth round pipes rising from
collars. Implemented as such (see CLAUDE.md "Round 2"). The arch moved into the renderer.

## Status / open

See the bottom of the CLAUDE.md turnstile v2 section for what was verified and what
Thomas still needs to judge.
