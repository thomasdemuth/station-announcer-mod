# Station Announcer

A public-address system for Minecraft train stations, built for **Fabric on Minecraft 1.20.4**.
Place announcer blocks around a station, type an announcement, and trigger it with redstone or
`/announce` — nearby players see the message as `[PA] ...` and hear it **read aloud by their own
computer's text-to-speech**, preceded by a classic ding-dong chime.

Built as an addon for [Minecraft Transit Railway (MTR)](https://www.minecrafttransitrailway.com/) 4.x,
which it requires. Beyond the PA system it adds station decoration, PIDS, dispatch tools and manual
driving. The MTR integrations use mixins into MTR's simulation and rendering; vanilla gameplay is
left alone.

- **Mod ID:** `station_announcer`
- **Environment:** works on singleplayer, LAN, and dedicated servers (announcements are fully
  server-authoritative; TTS only ever runs on clients)

## Building

Requires a JDK 17 (or newer) — nothing else; the Gradle wrapper downloads everything.

```sh
./gradlew build
```

The finished mod is at `build/libs/station-announcer-<version>.jar`.

> If `java` is not on your PATH, point `JAVA_HOME` at any JDK 17+. The Minecraft launcher's own
> runtime works, e.g. on macOS:
> `export JAVA_HOME="$HOME/Library/Application Support/minecraft/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"`

To test in a development environment: `./gradlew runClient` or `./gradlew runServer`.

## Installing

1. Install the [Fabric loader](https://fabricmc.net/use/) for Minecraft 1.20.4.
2. Drop **Fabric API** and **station-announcer-\*.jar** into your `mods` folder
   (client and, for multiplayer, server).

## NYC PIDS displays (requires MTR, new in 1.5)

> **Since 1.7, [Minecraft Transit Railway 4.x](https://modrinth.com/mod/minecraft-transit-railway)
> is a required dependency** — the mod won't load without it.

Six MTA-countdown-clock-style Passenger Information Displays live in the **Baker City**
creative tab:

| Block | What it shows |
|---|---|
| PIDS NYC Wall 1 | Route map of the next arriving train — big countdown on top, then the line strip with every onward stop. Wall-mounted, 1 px thin, screen floating 0.5–2.5 blocks above the floor. |
| PIDS NYC Wall 2 | List of the next four departures, plus an optional custom "Happening now" section. Same wall form factor. |
| PIDS NYC Standing 1 | The route map as a free-standing, double-sided totem: half-block legs, 3 px thin panel. |
| PIDS NYC Standing 2 | The departure list on the same standing totem. |
| PIDS NYC Hanging B-Division | Ceiling-hung double-sided countdown clock, two blocks wide: next departure on top, the second below. |
| PIDS NYC Hanging B-Division Mini | Shorter hanging clock showing only the next departure. |

All six are two-block multiblocks (the partner block places itself) and can be broken from
either end. Text that doesn't fit scrolls marquee-style instead of overlapping, route bullets
are proper MTA circles, and the route map lists each station's connecting routes as small
bullets under its name. With **no platforms configured, nearby platforms are auto-detected**
exactly like MTR's own PIDS (within 5 blocks) — the brush config is only needed to override
that.

They behave like MTR's own PIDS: **right-click with the MTR brush** to open the standard PIDS
config screen — select the platforms/tracks to watch there. On the departure displays the
config screen's message rows become the "Happening now" text (leave them empty and the section
is hidden). Countdowns show minutes, switch to seconds under one minute, and when a train is at
the platform its row **inverts and flashes white**. All arrival data comes live from MTR
(server-authoritative, same data the vanilla MTR PIDS use); station names in the route map come
from MTR's route data.

### Station decor & ambience (new in 1.7)

Furniture and dressing for Baker City stations, all in the Baker City tab:

- **Subway Entrance Globes** (green/red) + **Globe Lamp Pole** — the iconic cast-iron
  lamps: stack poles to any height, cap with a globe (leafy crown collar, glowing amber
  underside, light level 14).
- **Entrance Railing** — the classic green entrance surround, 1.5 blocks tall: baluster
  panels with a serrated top rail on a black concrete curb. Connects like a fence, with
  chunky paneled posts appearing automatically at ends, corners and junctions — and under
  any globe lamp pole, so lamps rise out of a proper post.
- **Entrance Railing (Station Sign)** — a railing segment carrying the black station-name
  panel, readable from both sides. The name follows the MTR station (falls back to
  "Subway"); right-click **with the MTR brush** to set custom text. Place several sign
  segments in a row and they **merge into one wide panel** for long station names.
- **Platform Bench** — the classic worn wooden bench: seat slab, low backrest, armrest
  dividers, solid end panels. Right-click to **sit down** (sneak to get up); benches placed
  side by side with the same facing **merge into one long bench** (end panels only at the
  free ends).
- **Platform Barrier** — the stainless platform barrier: woven wire mesh panel in a steel
  frame, rounded top hand rail and bolted foot plates. Placed in a row with the same
  facing the segments line up into one continuous run (the top rail carries straight
  through, frame members double up at each joint like the real thing).
- **MetroCard Vending Machine** — a two-block-tall MetroCard machine, 8 px deep so it
  sits flush against the wall behind it. And it actually works: right-click holding an
  **emerald** for a quick $10 top-up, or right-click with anything else to open **MTR's
  own ticket-machine screen** — bulk emerald purchases with MTR's growing bonus rates,
  balance shown on screen.
- **Station Ambience Block** — a vent grille that quietly loops a **station hum** or **air
  vent** sound around itself. Right-click to pick the loop, volume and radius (4–48 blocks).

These read the station you place them in (name and color come live from the MTR station
area; right-click the named ones to type a custom name instead):

- **Mosaic Station Name Sign** — the classic NYC tiled frieze painted straight onto the
  wall behind it, auto-sized to the name, border in the station color.
- **Iron Platform Columns ×4** — riveted I-beam columns that know their place in a stack:
  the bottom block grows the diagonal base foot, the top block flares into a plate that
  meets the ceiling. The station-color variants are **tinted** in the line color, so the
  rivets and shading show through (repaint updates when the chunk redraws). The named
  variants carry the station name on a black board (place one at eye height in a stack of
  plain ones).

The **mini PIDS "Next train" mode** now points an arrow (←/→/↓) toward the arriving
train's platform while lit, like the real mezzanine signs.

### Fare control & back-of-house (new in 2.0)

- **Turnstile + Exit Turnstile + Turnstile End Cap** — two-block-tall stainless fare
  lanes with tripod arms, angled MetroCard readers and the overhead tubing arcing
  between units (it bridges automatically, caps a free row end with a curled drop, and
  ends against a wall with a mounting collar). Walking through charges your MTR ticket
  balance through MTR's own system, with distinct entry/exit reader beeps; a pictogram
  sign on the pillar shows green GO / red STOP / amber while the fare check runs, lit
  at night. Right-click a lane empty-handed to hear your balance. Riders who can't
  afford entry get an instant red STOP. The exit-only lane processes everyone as
  leaving and permanently shows the red no-entry bar on its wrong-way side.
- **High Entrance/Exit Turnstile (HEET)** — the full-height rotating cage gate, with a
  comb rotor, lit lintel signs and the same fare behaviour.
- **Emergency Exit Door & Gate Walls** — the alarmed exit door (fare evasion the wrong
  way costs MTR's own $500 fine; the strobe throws real light while sounding), iron-bar
  and grille dividing walls with shared posts, corners, and **Employees Only doors** in
  three finishes that swing open on right-click and pull themselves shut after a few
  seconds (label editable with the MTR brush).
- **Holding Lights** (yellow/green, + hanging pole) — the platform dispatch lights, timed
  off live MTR arrivals: yellow lights up 5 s before the train arrives and holds while
  it dwells (going dark 3 s before departure); green blinks from departure until 15 s
  after the train is gone. Platform auto-detects, or pick one with the MTR brush.
- **PIDS Drop Pole** — plain steel pole for hanging the PIDS clocks lower from tall
  ceilings. Same 2 px gauge as the hanging PIDS's own ceiling stub, so a stack of poles
  continues it without a step.

### NYC elevated station kit

Everything to build an el station from the sidewalk to the platform, in two
looks: **classic** (Dual-Contracts green steel, red standing-seam gables,
cream board walls with wired glass, Warren lattice friezes) and **ESI**, the
modern MTA Enhanced Station Initiative skin (charcoal steel, silver roof over a
wood soffit with a linear LED, full-height clear glass and welded mesh, L-arm
LED lamps, black-and-stainless mezzanines with art glass). ESI pieces are on
their own creative tab and behave exactly like their classic counterparts
(craft: classic piece + gray dye).

Every piece is modular — runs, stacks and surfaces of any size connect
themselves, whether placed by hand, with `/fill`, pastes or Axiom:

- **Structure** — street columns (solid / lattice) that stack to any height,
  plate girders that grow knee braces over columns and frame into columns
  and crossing girders, open tie decks and plate decks (straight or diagonal).
- **Platform** — platform edge (opens MTR train doors) on the concrete floors,
  gable roofs of any length and width up to 8 with hips and valleys, posts,
  wall courses (board / wired glass / cream / green / sash windows / doorway /
  name sign) and railings that stand just outside the platform edge (click the
  far third of a floor block), lamp poles, roof lights, name boards.
- **Mezzanine / station house** — mezzanine floor (a steel-framed slab for any
  elevated floor), the same wall courses for its walls (stack two doorways for
  a full-height door), and a pressed-tin ceiling with or without lights.
- **Stairs** — subway stairs build a flight by clicking: the top of a step's
  upper half continues it up, the lower half down. Side courses beside the
  flight (railing / wall / glass / posts-only, stacked by clicking the tread's
  outer third), or on the stair's own edge (sneak-click), upper wall cells to
  carry the walls up through a slab, sloped stair roofs, flat landing roofs
  and hoods, steel landings and the lit entrance sign.

`tools/el_demo_station/` builds a complete example station with both looks;
`EL_STATION_PLAN.md` has the recipes (e.g. a stair rising through the platform
into the mezzanine).

### Subway stairs & handrails

- **Subway Stairs** (modern floating / old concrete) — two 8 px steps per block; runs
  detect their own top and bottom and paint the safety-yellow end treads themselves.
  The modern stair's item right-click-cycles between the floating and closed builds.
- **Stair Divider** — the black stringer beam between stair lanes, with floor feet,
  landing caps and wings that adopt the neighbouring stairs.
- **Subway Handrails** (wall / standing / double / floating) — 2 px steel tube rails.
  Right-click **in the air** to cycle the shape: straight, 45° stair run, the two
  stair-to-floor transitions, and both corners. On the floating and standing styles,
  **where you click across the block picks the rail's side** — left third, middle, or
  right third — so a rail can hug the edge of a stair lane. The wall style mounts on
  the wall you click, and its sloped runs attach to whichever side of the block
  actually has a wall.

### Subway signs (requires MTR, new in 2.4.57)

The black MTA-style wayfinding signs, built from modules the way the real
Vignelli/Unimark system is: route bullets, text, an arrow pinned to the edge it
points at, and live tiles the station fills in.

- **Subway Sign** (full block height) and **Subway Sign (half height)**, both
  in three mounts picked by where you click: on a wall, hanging from a ceiling,
  or standing on a rail. Side-by-side panels with the same mount merge into one
  long sign. Hanging and standing signs are double-sided.
- **Right-click** a sign to open the editor: rows of tiles on the left, a live
  preview in the middle (click it to select), the selected tile's settings on
  the right. **Templates…** fills a face with the standard wordings ("Uptown &
  The Bronx →", "Exit →", "Line + destination", a station plate…).
- Tiles: **Bullets** (pick lines, or "all lines at this station"; express
  service draws a diamond), **Text** (with inline bullets, wheelchair and small
  arrows; Enter makes a new line, up to three), **Arrow** (eight directions,
  left edge / inline / right edge), **Station** (the MTR station's name, or an
  override; long names break onto two lines, or force "Stacked" for the
  "14 / Street" column-plate look; shows the wheelchair symbol when the station
  is marked step-free in its MTR station screen — a filled tile when every
  platform is step-free, an outline tile when only some are), **Exit** (an exit from MTR's station settings:
  "Exit" on the red field, its street names one per line, a corner note you
  type, and optionally the exit's name in a box), **Line + destination** (a route's bullet with "to <terminus>", its
  direction wording, or your own text), **Space**.
- Styles: black with the thin white line (today's standard), the 1970 white
  panel with a black band, or plain black.
- Free geometry: the Sign inspector sets the panel's width, height, offset
  and content scale (so a sign can outgrow or float off its block, for column
  plates and the like), every tile has its own scale and nudge, and a Space
  tile's width is a number. All of these are sliders with a number box.
- Everything is stored by name: rename a line, repaint it, change an exit's
  streets or a route's terminus in MTR and every sign follows.
- The older text signs — the entrance railing sign, the el entrance sign, the
  el station boards and the named column boards — now use the same editor and
  typeface. Old placements keep their content.

### Railroad PIDS (requires MTR, new in 2.3)

Commuter-railroad departure boards, drawn at twice the resolution of the subway PIDS so
they carry roughly twice the text. Three shapes, all configured with the MTR brush:

- **Railroad PIDS (Wall)** and **(Standing)** — tall portrait boards whose screen spans
  0.5–2.5 blocks, like the subway units. The line name and a live clock sit across the top,
  then the departure time and destination on a bar in the line's colour, then the route
  ahead as a station list. Stations show a **connecting train** only when one is genuinely
  due within two minutes of your train getting there — the board never advertises a
  connection it cannot stand behind. A route too long for the screen ends in
  "Continues to …" naming the terminus.
- **Railroad PIDS (Hanging)** — a deep LED case slung under the ceiling on two drop poles
  (the same 2 px `pids_pole` stacks onto it). It carries **two screens** and alternates:

  - *Next train* — arrival time, destination and "in 7min" on a bar in the line's colour,
    over the remaining stops as a wrapped list that rolls when the route is long.
  - *Departures* — `TIME · DESTINATION · ETA · TRK` for the next four trains, each
    destination on a chip in its line's colour, with the track taken from MTR's platform name.

  The brush screen picks the behaviour: flip on a timer you set, pin it to either screen, or
  **Automatic** — the next train while one is close, the departure list the rest of the time.

### The platform picker (new in 2.3)

**Every PIDS in the mod now shares one settings screen**, opened with the MTR brush — the
railroad boards and the NYC subway displays alike. It lists **every platform of the station**
the display stands in, not just the ones within reach, so a concourse board can announce
tracks it is nowhere near. Each row is a checkbox with the **routes that actually call there**
shown as chips in their own line colours, and the list is ordered **nearest-first**, so the
track you are standing over is always at the top. Picking nothing leaves the display on
auto-detect, like MTR's own PIDS.

The railroad boards add per-line-class connection toggles (Train, Ferry, Cable Car, Plane)
and, on the hanging unit, the screen-flip controls. The NYC displays get one "Happening now"
box on the departure styles — it wraps to at most five lines on screen, and a message longer
than four lines takes the fourth departure's slot rather than being clipped.

### Zebra Boards (new in 2.2)

The striped board a conductor points at to confirm the train is berthed correctly, in two
variants:

- **Zebra Board** — bolted flat to the wall you place it against.
- **Hanging Zebra Board** — slung under the ceiling on a PIDS drop pole.

Both **extend**: place more boards end to end and they merge into one continuous beam —
the striped panel runs straight through, and the bolted end plates appear only at the free
ends. On the hanging variant the end blocks also carry the hanger pole, so a run reads as
one long beam on two poles. Stack **PIDS Drop Poles** above an end block to hang the run
lower from a tall ceiling. Wall and hanging boards never merge with each other (they sit at
different depths, so a mixed run would step).

**Labels (3.2.5):** right-click any board block with the **MTR brush** to put a label on it —
"R-160", "8", "5 Car", up to 16 characters. It is drawn as a white plate with black
lettering over the stripes, sized to the text, and a label wider than one block spills onto
the neighbouring boards. **Position** puts the plate centred on the block or flush with its
left / right edge (flush against the end plate on a run's last block). A label never runs
past the run's end plates — one longer than the whole run is squeezed to fit. Hanging boards
show the label on both faces. Clear the text to remove it.

### Interlining: depot delays and shared-section headways (requires MTR)

Where two or more lines share track, their trains should arrive evenly spaced, not in
pairs. Open MTR's dashboard, press **Tools…**, then **Interlining…**:

- **Sections**: every stretch where lines stop at the same platforms in the same order is
  found for you, one entry per direction, drawn as a little line map. A strip of ticks, one per
  train reaching the section, shows at a glance whether trains arrive evenly or in pairs.
- **Suggest**: *Even spacing* (keep today's frequencies) or *Target headway* (e.g. `2:00`;
  frequencies are suggested in the steps MTR's depot sliders can hold). Choose *Balance both*
  directions, *This way*, *Other way* or *Options*, and what it may **adjust**:
  - *Depot delays* slide a depot's whole timetable.
  - *Platform holds* add dwell at one platform, such as the stop before the section or the
    terminal where a line turns back. That moves one line in one direction, which is what
    fixes a depot whose trains pass the same section twice (out and back).
  - *Both*.

  Lines with different frequencies can't be spaced perfectly: the suggestion then gives the
  shortest longest wait and offers one-click "match frequencies" alternatives.
  **Apply** writes the delays, the frequencies (all 24 hours, through MTR's own depot update)
  and the holds, then rewrites the departures straight away.
- **Depots**: delay any depot's departures by a fixed amount (`45`, `1:30`, `1m30s`).
- **Groups**: name depots that belong together and pick a group in the suggestion form to let
  it move only those depots.

The **dispatch web UI** has the same tools in its **Interlining** panel (key `I`). The selected
section lights up on the live map (real track, stations, and pulsing markers where holds would
go), and a timetable stringline underneath shows every train through the section now (dashed)
and suggested (solid). To apply from the browser, pair it once: run `/navpair` in game and type
the code into the panel. Only players with the addon's edit permission can apply;
`depotGroups.webApply` turns it off.

Depots on a real-time timetable or with continuous movement (cable cars) can't be delayed.
Suggestions use scheduled times, so holds, door obstructions and gap-filler dwells still show up
in the measured headway.

### Driving: crew, cab HUD and timetable runs (requires MTR)

Driving a train by hand gets a proper crew, a cab display, real timetable runs and a score.
MTR's rules still apply: you can only drive a train from a **manual siding**.

**Crew roles.** What you hold when you board decides your role:

- An MTR **driver key** makes you the **driver**. A driver with no guard aboard is **OPTO**
  (one-person operation) and works the doors as well as driving.
- MTR's **guard key** makes you the **guard**.

A train has one driver and one guard, first come first served. A role only counts while you are
aboard, and you lose it when you leave the train or disconnect. Door obstructions and the
incidents below only happen while a player is driving or crewing the train, never on
automatic trains.

**Keys** (all rebindable under *Station Announcer* in Controls):

| Key | Default | What it does |
|---|---|---|
| Cab buzzer | `B` | Buzz the other end of the train. Presses are grouped into codes on their HUD |
| Train horn (hold) | `G` | Driver only. Sounds while held and fades out on release |
| Vigilance acknowledge | `V` | Resets the vigilance timer and releases a vigilance brake once stopped |
| Cab HUD detail | `J` | Cycles the panels: default → all expanded → all collapsed |
| Cab menu | `K` | Start up, pick a service, set the horn |
| Cab seat: stand up / sit down | `N` | While driving you sit at the front of the leading car. Stand up to walk about |
| Lean out of the cab window (hold) | `Left Alt` | Eases the camera out of the side window, looking along the train, on the platform side |
| Driving HUD Settings | `H` | HUD style and the crew settings below |

**Cab HUD.** While you drive or guard, the cab HUD replaces MTR's driving overlay, its
action-bar line and its next-station chat lines (the spoken announcement stays). The middle of
the screen stays clear:

- **Top left**: line bullet, destination, next stop and its distance. Expanded, it adds the stop
  after that.
- **Bottom left (drive)**: the next signal (Clear / Caution / Danger) and its distance beside your
  speed, the current speed limit, and the next limit with where it starts. Expanded, it adds the
  stop marker (counts down to 0 m as you brake for a platform), the power/brake ladder
  (EB, B7–B1, N, P1–P7) and lamps for doors, guard ready and hold. A guard sees a doors and buzzer
  panel here instead.
- **Bottom right (run)**: how early or late you are, the clock, the next two stops (scheduled →
  expected) and a departure countdown at platforms. Expanded, it lists the whole run. On a
  timetable run it also shows your live score.
- **Top centre**: one line, only when something needs you (leave the yard, start up, wait for a
  buzz, vigilance, incidents).

*HUD style* in the settings (`H`) picks **Cab (km/h)**, **Cab (mph)** or **Classic** (the older
text panel plus a small crew panel).

**Cab menu (`K`)** has three tabs:

- **Start up**: key in, battery on, then a brake test (apply the brake to B7). The train won't
  take power until this is done, once per driver per train (unless the server turns
  `startupRequired` off). **Skip** does it at once, and *Skip
  startup* in the settings always skips it.
- **Service**: pick a timetable slot (see below), or **Give back** one you have not started yet.
- **Train**: choose the horn for this train type: NYC subway (the default), commuter rail
  three-chime, European two-tone or steam whistle. **Test** plays it, **Use** saves it for every
  train of this type. Operators only (the addon's `editPermissionLevel`). `/crew horn
  <nyc|lirr|euro|steam>` does the same while you ride the train.

**Timetable runs.** From a manual siding's train, while it is still in its siding, the Service tab
lists the next departures (up to 12) you can take over. They come from automatic sidings in the
same depot, or in any depot that runs exactly the same routes (a depot group of such depots works
too). For another depot, the stops must also be at the same platforms. Each row shows the
departure and when you need to **leave the yard**, corrected for your own siding's run to the first
platform. Departures less than about 20 seconds from their yard time are not offered. Take one and:

- the automatic train for that departure stays in its depot, and your train runs it in its place;
- every board keeps showing the departure, **late when you are late**. Very late runs are capped
  on the boards just under MTR's limit (about 9 minutes on the default day), but the cab HUD
  always shows the real figure;
- if you have not left the yard **2 minutes** after your yard time, the slot goes back and the
  automatic train runs it, late;
- if MTR's manual→automatic timeout fires mid-run (the siding's own setting), the train keeps the
  slot and drives on automatically, trying to catch up.

Only one driver can take a slot. Taken slots are not saved across a server restart.

**Interlocks and vigilance.** With both a driver and a guard aboard:

- the **guard owns the doors**. The driver's door key is ignored;
- the **driver waits for the guard**. At a stop where the doors opened, power stays at neutral
  until the guard has closed the doors and buzzed;
- optionally the **guard waits for the driver**. The guard can't close the doors until the driver
  has buzzed at this stop (off by default).

**Vigilance** (deadman): while the train moves, any driver control or the vigilance key resets a
timer. A warning sounds for the last few seconds, then the emergency brake is applied and held
until the train has stopped and you press the vigilance key. The server decides whether each driver
picks (default), it is always on, or it is off.

**Incidents** (crewed trains only, small delays that never break the timetable):

- **Door fault**: a door won't lock. Press the door key twice to cut it out.
- **Held by control**: held at a platform for 8–25 s.
- **Sick passenger**: the doors stay open for 30–60 s.
- **Passenger alarm**: the emergency brake goes on while moving. It is released 10 s after the
  train stops.

**Passengers.** While you drive or guard, people wait on the platforms of your next stop and the
one you stand at, mostly where the train will stop. When the doors open some get off and walk away,
and the rest walk to the nearest door and board. A few are waiting for another line and stay put.
Anyone who wanted your train and is still on the platform when you depart counts as left behind.
The guard (or an OPTO driver) sees "N passengers still boarding" while the doors are open. The crowd
exists only on your screen: it is cosmetic, nothing is sent to the server, and a driver and guard
on the same train see the same people. Turn it off or change the cap (default 40) in `H`.

**Score.** A timetable run is scored as you drive:

- **Departures**: +10 for each departure within 30 s of time. Late beyond that costs −1 per
  10 s (at most −10 a stop). More than 15 s early is −5, and a skipped stop is −10.
- **Stopping**: +2 for stopping within half a metre of the mark, −3 for overrunning it by more
  than 2 m.
- **Other**: −5 per emergency brake (yours, vigilance or a passenger alarm), −1 per passenger
  left behind.

At the end of the run a **report card** opens: on-time departures, latest departure, perfect stops,
overruns, emergency brakes and passengers left behind, plus a line per event. Your last 50 runs
are kept in `config/station_announcer/run_history.json`.

**`/crew status`** prints your train, your role, who is aboard, the ready lamps, vigilance and
the horn.

**Server config**: the `crew` block of `config/station-announcer-addon.json`:

```json
"crew": {
  "enabled": true,
  "driverWaitsForGuard": true,
  "guardWaitsForDriver": false,
  "guardOwnsDoors": true,
  "vigilance": "player",
  "vigilanceDefaultSeconds": 45,
  "vigilanceWarningSeconds": 6,
  "startupRequired": true,
  "incidents": true,
  "doorFaultPercent": 2,
  "heldPercent": 3,
  "heldMinSeconds": 8,
  "heldMaxSeconds": 25,
  "sickPassengerPercent": 1,
  "sickPassengerMinSeconds": 30,
  "sickPassengerMaxSeconds": 60,
  "passengerAlarmPerHour": 0.5,
  "hornRange": 160
}
```

- `enabled`: master switch for crews. Off means no roles are accepted.
- `driverWaitsForGuard` / `guardWaitsForDriver` / `guardOwnsDoors`: the interlocks above.
- `vigilance`: `"player"` (each driver chooses), `"required"` (always on, at the driver's
  interval or `vigilanceDefaultSeconds` if they turned it off) or `"off"`.
  `vigilanceWarningSeconds` is the warning before the brake.
- `startupRequired`: hold a manual train at neutral until its driver has started it up (players
  can still skip it on their side).
- `incidents`: master switch for incidents. The `*Percent` values are chances: a door fault per
  door close at a stop, held and sick passenger per platform arrival. The `*Seconds` pairs set the
  durations. `passengerAlarmPerHour` is the expected alarms per hour of driving.
- `hornRange`: how far a horn carries, in blocks (at least 16).

Horn choices per train type are stored in `<world>/station-announcer-addon/crew_horns.json`.
Door obstruction chances stay in the separate `doorObstruction` block.

**Client settings** (`config/station-announcer-addon-client.json`, mostly through `H`):
`hudStyle` (`"cab"` or `"classic"`), `cabMph` (false), `cabScale` (1, a size multiplier on
the GUI scale), `crewSkipStartup` (false), `crewVigilanceEnabled` (true) and `crewVigilanceSeconds`
(45, 10–300), `cabSeatEnabled` (true), `cabSeatInset` (0.6 blocks behind the front of the floor),
`cabSeatLateral` (0), `cabSeatEyeOffset` (0), `cabSeatTrains` (per-train-type seat overrides),
`cabLeanEnabled` (true), `cabLeanMax` (1.8), `cabPassengers` (true), `cabPassengersMax` (40, the
crowd cap) and `crewPanelEnabled` (true, the Classic style's crew panel).

### Bridge Creator (requires MTR, new in 3.2)

One configurable tool that replaces juggling the pillar, railing and viaduct creators: it
builds a **whole bridge or viaduct under a stretch of track**, follows curves and grades,
and sizes itself to however many parallel tracks the line has. Find it in the Operations
tab.

- **Right-click in the air** for the settings screen: a parts list on the left (tracks,
  deck, girders, railing, piers, arches), a live 3D preview in the middle (built by the
  same code that builds in the world; drag to turn) and the selected part's settings on
  the right. Every part has its own **material** — pick from the searchable block grid, or
  press **Pick** and sneak-click a block in the world to use its exact state (stairs,
  slabs, logs keep their properties). **Presets** at the top: nine built-in (stone viaduct,
  brick arches, steel girder, concrete box girder, modern viaduct, wooden trestle, NYC
  steel el, piers only, deck + railing only) plus **Save as…** for your own, kept in
  `config/station_announcer/bridge_presets.json`.
- **Parts**: deck (thickness, overhang beyond the outer tracks, an optional edge material
  for a kerb or walkway), girders (under the edges, under each track, both, or a solid box
  the full width; depth), railing (any fence, wall, pane or block; height; inset), piers
  (under the edges, one in the centre, under or beside each track, or a solid wall pier;
  spacing and thickness along the track; optional cap beam across the deck and a widened
  footing at the ground) and arches between the piers (filled spandrel walls or open ribs;
  rise). Logs, pillars and the el kit's decks and girders turn to follow the track.
- **Tracks**: in **Auto** mode click two nodes of one track and every parallel track within
  the reach (default 8 blocks sideways) is bridged too, so a river crossing on a 2-track
  line gets a 2-track bridge and a 3-track line a 3-track one. **Single** bridges only the
  clicked track. **Manual** lets you click two nodes of each track you want (again to
  remove it), then press **Build** in the screen. The deck always spans from the outermost
  track to the outermost track plus the overhang.
- Piers pass through whatever the same build placed and stop at the first real block —
  ground, riverbed or an existing structure. Nothing existing is ever replaced, and the
  rail cell plus four blocks of clearance above every track are never built in.
- **Undo last build** (screen button or `/bridge undo`) puts back every block the last
  build replaced. `/bridge build <x1 y1 z1> <x2 y2 z2>` builds between two node
  coordinates without clicking; `/bridge preset "<name>"` loads a built-in preset into
  the held creator; `/bridge tracks build|clear` drives the manual selection.

### Railing & Viaduct Creators (requires MTR, new in 1.7)

Two more rail-following tools alongside the pillar creators, used the same way (sneak-click
a block to pick the material, then click two connected rail nodes). All creator items
**glint while armed** — after the first node click, until the second completes the build:

- **Railing Creator 3/5/7/9** — lays the chosen block (iron bars, walls, fences...) along
  **both edges of a bridge deck** at rail height. Match the number to the MTR bridge creator
  width you used. Existing blocks are never replaced.
- **Viaduct Creator 3/5/7** — a whole elevated line in one pass: bridge deck under the rail,
  girders beneath the deck edges, and pillar pairs down to the ground every 8 blocks
  (single centered pillars for the 3-wide).

### Pillar Creators (requires MTR, new in 1.6)

Companions to MTR's bridge/tunnel/wall creators for building viaduct supports. Ten variants
in the Baker City tab, named **width x spacing**:

- **Width** — `1` builds a single pillar centered under the rail; `3`/`5` build a pair of
  pillars that many blocks apart, straddling the track.
- **Spacing** — a pillar set every that many blocks of rail (4, 6, 8, or 10 for the 5-wide).

Usage is identical to the bridge creator: **sneak-right-click any block** to choose the
pillar material, then **right-click two connected rail nodes**. Columns of the chosen block
are built straight down along the existing rail, starting one block below it, until they
reach the ground (they pass through water and through up to three blocks of bridge decking
directly under the rail, so build the bridge first and then add pillars). Pillar sets start
half a spacing in from each node, keeping station platforms clear.

### Linking PIDS to a PA Control Box (new in 1.6)

Every PIDS can be linked to a **PA Control Box** with the **Speaker Link** — exactly like
linking speakers: right-click the box, then right-click the display (or the other way
around); sneak-right-click a display to unlink it. Once linked:

- **Hanging B-Division** — whenever the box plays an announcement, the clock's bottom row
  turns into the announcement **scrolling across once**, just like a real MTA platform sign,
  then goes back to the second departure. The **Mini is unaffected** by design.
- **Wall 2 / Standing 2 (departures)** — the "Happening now" section always mirrors what the
  control box is saying: the box's announcement pool, rotating through the entries every
  8 seconds. When unlinked it falls back to the brush-configured message rows.

## Using an announcer block

All of this mod's blocks and items live in the **Baker City** creative tab (since 1.5;
previously the Redstone tab). The **PA Station Announcer** block (named "Station Announcer"
before 1.4) also drops itself and is
fastest mined with a pickaxe).

**Right-click** the block to open its editor (the same flat editor as the PA Control Box —
see [The PA editor](#the-pa-editor) below; the announcer simply has one message card):

| Setting | Meaning |
|---|---|
| Announcement | The text to display and speak (up to 512 characters). Supports `{station}`, `{time}` and `{shown\|spoken}` — see [Announcement text](#announcement-text-tokens-and-pronunciation). |
| Volume | 0–100 %. Scales chime and speech loudness; farther players hear it quieter. |
| Delay | Seconds to wait after being triggered before the announcement plays (0–60 s). |
| Radius | Only players within this many blocks (1–128) receive the announcement. |
| Tag | Freeform identifier used by `/announce`. Many blocks may share one tag, so a single command can fire a whole station. |
| Chat Text | ON/OFF — whether this block shows the `[PA]` message in chat/action bar. Turn OFF for audio-only announcers. |
| Chime | ON/OFF — whether this block plays a chime before speaking. |
| Chime Sound | Built-in chimes (since 1.6): the classic Ding-Dong plus six Marimba and five Synth chimes — click one in the picker to hear it. The voice always waits for the selected chime to finish before speaking. |

> **Changed in 1.2:** the message pool (Random/In Order) and the random auto-trigger moved
> from the Station Announcer to the **PA Control Box**. Old announcer blocks in existing
> worlds load fine — the removed settings are simply ignored.
>
> **Changed in 1.3:** pool announcements are separated with `||` instead of line breaks
> (so a single announcement may span several lines). Pools written before 1.3 that relied
> on one-message-per-line play as one combined announcement until you re-open the Control
> Box and add `||` between them.

Press **Save** (or Ctrl+S) to store the settings in the block, **Cancel**/Escape to discard.

### Triggering

- **Redstone:** on a rising edge (signal turns on) the block fires after its configured delay.
  A signal that simply stays on does not retrigger it; it must turn off and on again.
- **Command:**

  ```
  /announce tag=<tag> [tag=<tag> ...]
  ```

  Triggers every *loaded* announcer block carrying any of the given tags, e.g.
  `/announce tag=7_train_southbound tag=7_train_delay`. Tab-completion suggests the tags of all
  currently loaded announcers. Bare tags without the `tag=` prefix are accepted too.
  Works from the server console, command blocks, and players with sufficient permission.

### What players experience

Everyone inside the radius when the announcement fires (including players who joined or walked
into range during the delay):

1. sees **`[PA] <text>`** in chat (or on the action bar, per client config),
2. hears the **ding-dong chime** from the block's position,
3. hears the text **spoken by text-to-speech on their own machine** — never on the server.

Volume falls off linearly with distance from the block. An announcer with empty text does nothing,
and breaking a block cancels any announcement it had pending.

**Announcements never talk over each other** (since 1.4): if a new announcement reaches a player
while an earlier one is still playing — chime or voice, from any announcer or PA network — the
older audio is cut off immediately and the newest one plays. Chat lines still all appear.

## The PA network: Control Box + Speakers (new in 1.2)

For a whole station, use one **PA Control Box** driving many **PA Speakers** instead of separate
announcer blocks.

- **PA Control Box** — the brains. Same triggering as the Station Announcer (redstone
  rising edge, tags/`/announce`, delay) **plus** a list of messages (each firing plays one,
  Random or In Order) and automatic playback every *n*–*m* minutes. It plays no sound
  itself: when triggered it broadcasts through every linked speaker. Its editor is described
  in [The PA editor](#the-pa-editor) below, including the **Network** tab that lists every
  linked speaker and display.
- **PA Speaker** — where the sound comes out. Right-click to set its **volume** and **sound
  radius**. The speaker screen sits low on the screen so you can see a **cyan ring** in the
  world showing exactly how far it carries at your height, updating as you drag the radius;
  it says how loud *you* would hear it from where you stand, **▶ Test here** plays the chime
  at that volume, and **Copy / Paste** carry volume + radius from one speaker to the next.
  A player in range of *any* linked speaker hears the announcement exactly **once** (the
  loudest speaker for that player wins), so overlapping speaker ranges are safe.
- **Speaker Link** (item) — how you wire them up. Linking works in **both directions**;
  select either end first (the tool remembers it, and the tooltip shows the selection):
  - **Box first:** right-click a PA Control Box, then right-click each Speaker to link it.
    The box stays selected, so you can wire a whole platform in one sweep.
  - **Speaker first:** right-click a Speaker, then right-click a PA Control Box — that
    speaker is linked and the selection clears.
  - Linking a speaker that already belongs to another box **moves** it (both boxes update).
  - **Link a whole area:** with a box selected, **sneak-right-click two ordinary blocks** at
    opposite corners (up to 128 blocks a side). Every speaker and PIDS display inside is
    linked at once; the action bar sums it up ("Linked 12 speakers and 2 displays (3 moved
    from another box)"). While the first corner is set, an amber box follows your crosshair
    and marks every speaker and display that would be linked. Sneak-right-click air cancels.
  - **Sneak-right-click a Speaker** to unlink it. **Sneak-right-click air** to clear the
    selection. Clicking another box/speaker just reselects.

  While you hold the Speaker Link, **thin white lines are drawn between every control box
  and its linked speakers** nearby, so you can see the whole network at a glance — and
  **reach rings** show how far sound carries at your height: bright for the speaker or
  announcer under your crosshair, dim for every speaker of the selected (or looked-at) box.

  Links are limited to the same dimension and `maxLinkDistance` blocks (server config,
  default 128). Every action gives action-bar feedback.

Details worth knowing:

- Speakers store their control box position in plain block NBT, so **structure blocks,
  Litematica and Create schematics preserve links** — a pasted speaker re-registers with the
  box at the stored position once both chunks are loaded. If nothing is there, its GUI shows
  "Link broken" (the position is kept, so pasting the box afterwards heals the link).
- A triggered control box only needs its own chunk loaded; **speakers in unloaded chunks
  simply stay silent** (never force-loaded) and entries pointing at removed speakers are
  cleaned up automatically.
- Breaking a speaker removes it from its box; breaking a box leaves speakers showing
  "Link broken".

All four items are craftable (iron/redstone/note block-based recipes; the Speaker Link is
redstone + iron ingot + stick in a column) and appear in the Redstone creative tab.

### The PA editor

Right-clicking a PA Control Box or a Station Announcer opens one flat editor:

- **Messages** (left) — one card per announcement. Click a card to edit it; **Enter** starts
  the next one. Each card has a **switch** (turn a message off without deleting it — off
  messages are never played or shown on PIDS), **↑ ↓** to reorder, **×** to delete, and
  **▶** to hear it right now **on your machine only** (chime + voice, nobody else hears it).
  Under the card, **Reads:** and **Says:** show the finished text when it uses tokens.
  The box holds up to 64 messages / 4096 characters; each message up to 512.
- **Settings** (right) — Random / In order, show in chat, chime first + chime picker (click to
  hear), delay, **Play by itself** every *n* to *m* minutes (box), volume and radius
  (announcer), and the tag with the matching `/announce` command spelled out.
- **Network** (box only) — every linked speaker and display with its live state from the
  server (green OK, amber not loaded, red missing), distance, volume and radius. Click a
  speaker to change its volume/radius, **×** to unlink it (click again to undo), **All
  speakers → Apply to all**, and **Unlink all**. Nothing changes until **Save**.
- **▶ Fire** (top bar) — broadcasts to every player in range right now, ignoring the delay:
  the selected card (even unsaved), or the next saved message when no card is selected.
- **Templates** (top bar) — built-in station lines ("Stand clear", "Mind the gap",
  "Welcome to {station}", a whole "Station loop"…) plus your own. **Add** appends a
  template's messages, **Replace** loads them (and the settings saved with them). Save the
  whole box or just the selected message under a name. Templates are stored on your machine
  in `config/station_announcer/pa_templates.json`, so they follow you to every world and
  server; built-ins you delete can be restored.

### Announcement text: tokens and pronunciation

| Write | Becomes |
|---|---|
| `{station}` | The MTR station the box / announcer stands in (falls back to "this station"). |
| `{time}` | Each listener's wall clock, e.g. `14:05` (the same clock the railroad PIDS show). |
| `{Bklyn\|Brooklyn}` | Shown as "Bklyn" in chat and on PIDS, **spoken** as "Brooklyn". Either side may be empty: `{\|on the left}` is only spoken, `{Exit A\|}` only shown. |

The editor's **+ {station}**, **+ {time}** and **Say as…** buttons insert these
(select a word first and Say as… wraps it, ready for you to type the pronunciation).
Anything else in braces is left as you wrote it.

## Configuration

Config files are created on first run under `config/station_announcer/`.

### `server.json` (server side)

```json
{
  "announcePermissionLevel": 2,
  "maxTextLength": 512,
  "maxLinkDistance": 128
}
```

- `announcePermissionLevel` — permission level required for `/announce` (default 2 = ops/command
  blocks, like most vanilla commands). Set to 0 to let everyone use it.
- `maxTextLength` — hard cap applied when a player saves announcement text.
- `maxLinkDistance` — farthest (in blocks) a Speaker may be from the PA Control Box it links to.

### `client.json` (each player's side)

```json
{
  "enableTts": true,
  "enableChime": true,
  "displayMode": "chat",
  "ttsBackend": "auto",
  "voice": ""
}
```

- `enableTts` / `enableChime` — mute the voice or the chime entirely on this client.
- `displayMode` — `"chat"` or `"actionbar"`.
- `ttsBackend` — `"auto"` (Minecraft's built-in narrator, falling back to the OS voice),
  `"narrator"`, or `"system"`.
- `voice` — voice name for the `system` backend (`""` = OS default). Voice names are
  OS-specific — list them with `say -v ?` (macOS), PowerShell
  `Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices().VoiceInfo.Name`
  (Windows), or `espeak --voices` (Linux). To use a specific voice, set `ttsBackend` to
  `"system"` (the narrator library has no voice-selection API).

### Text-to-speech backends

- **narrator** — `com.mojang.text2speech.Narrator`, the library Minecraft's own narrator uses;
  ships with the game on every platform. It has no volume API, so block volume acts as an
  audibility threshold.
- **system** — the operating system's voice, with true volume scaling and selectable voices:
  `say` on macOS, PowerShell `System.Speech` on Windows, `espeak` on Linux
  (install it with your package manager if missing).

## Custom chime sounds (resource packs)

Announcement audio plays on each listener's client, so custom sounds are distributed the
Minecraft way: a resource pack (use a **server resource pack** to push it to everyone
automatically). To add your own chime:

1. Put an Ogg Vorbis file in the pack, e.g. `assets/mypack/sounds/my_chime.ogg`.
2. Register it in `assets/mypack/sounds.json`:

   ```json
   { "my_chime": { "sounds": ["mypack:my_chime"] } }
   ```

3. Set the id on the block with `/data merge block <x> <y> <z> {ChimeSound:"mypack:my_chime"}`
   (since 1.6 the GUI offers a dropdown of the built-in chimes; a pack-provided id set this
   way shows up there as "Custom" and is preserved).

Any existing sound event works too (e.g. `minecraft:block.bell.use`). If a client doesn't have
the sound, it falls back to the built-in ding-dong. You can also replace the default chime for
a whole pack by overriding `assets/station_announcer/sounds/chime.ogg` (or any of the 1.6
built-ins, `chime_marimba1`–`6` / `chime_synth1`–`5`).

## Hooking announcements to MTR events (future addon idea)

This mod is deliberately trigger-agnostic: anything that can run a command or invoke
`AnnouncerRegistry.trigger(server, tag)` can drive a station's PA. To announce MTR train arrivals,
a small companion mod could:

1. **Depend on MTR** (this mod stays independent) and listen to its train/siding callbacks —
   MTR 4.x exposes train state through its `Railway Data`/train listener API, or poll each
   platform's arrival schedule (the same data MTR's PIDS displays consume).
2. **Map MTR platforms to announcer tags** with a naming convention, e.g. tag announcers
   `arrival_<route>_<platform>` and have the addon fire
   `AnnouncerRegistry.trigger(server, "arrival_" + routeId + "_" + platformId)` when a train is
   N seconds out — or simply execute `/announce tag=...` through the server's command manager,
   which keeps the addon decoupled from this mod's internals too.
3. Alternatively, with **no code at all**: MTR PIDS custom messages plus command blocks wired to
   MTR's redstone-outputting blocks can pulse an announcer block directly — rising-edge
   triggering and per-block delays were designed with exactly this in mind.

## Project layout

- `com.stationannouncer` — common: block, block entity, `/announce`, tag registry, networking, server config.
- `com.stationannouncer.client` — client only: config screen, TTS backends, client config.
  Screen and TTS classes are `@Environment(CLIENT)` and are never classloaded on dedicated servers.

## License

MIT — see `LICENSE`.

The sign typeface is **TeX Gyre Heros Bold** (© GUST e-foundry), bundled unmodified under
the GUST Font License — see `assets/station_announcer/font/gust-font-license.txt`.

### Service change posters

Every disruption can carry any number of **service change posters** in the style of the
MTA's planned-work sheets. From the dashboard's Disruptions board click **Posters** on a
row, then **New poster** (pre-filled from the disruption) or edit an existing one. The
editor is a small design tool: the poster's **structure** on the left (header, one card per
body block, footer), the **live poster** in the middle (click any part of it to select it),
and an **inspector** on the right for whatever is selected — title bar and logo, the big
timing line ("All Times", "Weekends"), two date lines, the affected-line bullets, the
category, or a body block: headline, text, subhead, a big directional arrow, a rule or a
spacer. Text blocks are edited in a wrapping text box; the **Insert at cursor** buttons
drop a line bullet, an express diamond, the wheelchair symbol or a small arrow where the
caret is (stored as tokens such as `{b:4}`, `{d:4}`, `{wc}`, `{>}`). Long bodies shrink
their text to fit the sheet; Ctrl+S saves.

To hang one, place a **Service Change Poster** frame (Operations tab, a two-block wall
plate) and right-click it to pick a poster. The frame keeps its own copy, so the poster
stays up even after the disruption is deleted or expires; while the disruption exists,
edits show on every frame immediately.
