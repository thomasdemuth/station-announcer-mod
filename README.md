# Station Announcer

A public-address system for Minecraft train stations, built for **Fabric on Minecraft 1.20.4**.
Place announcer blocks around a station, type an announcement, and trigger it with redstone or
`/announce` — nearby players see the message as `[PA] ...` and hear it **read aloud by their own
computer's text-to-speech**, preceded by a classic ding-dong chime.

Designed to sit alongside [Minecraft Transit Railway (MTR)](https://www.minecrafttransitrailway.com/) —
it does not depend on MTR and does not conflict with it (no mixins, no vanilla behavior overrides).

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

The steel bones and platform furniture of a New York el, in classic
Dual-Contracts green, galvanized silver, and **station-color tinted** paints:

- **El Columns** (solid riveted / see-through lattice) — slim 6 px columns
  that stack to any height: the bottom block grows the street base plates,
  the top flares the gusset cap. Named variants carry the station name on a
  black board, live from MTR.
- **Plate Girder & Lattice Truss** — axis-placed like logs; set one directly
  over any column and it grows the curved knee braces down the column's
  sides, so a bent assembles itself.
- **Tie Deck & Plate Deck** — the track deck: open ties you can see daylight
  through from the street, or solid riveted plate; walkable on top with
  clearance beneath.
- **Windscreens ×4** (panel + wired glazing, corrugated, modern glass, mesh)
  and **Railings** (green pipe / galvanized picket) — merging runs sharing
  one post per joint, any length.
- **Canopy Posts + Canopies** (flat corrugated / red standing-seam gable) —
  posts stack with brackets at the top; canopies tile to any platform
  footprint, hanging riveted fascias on open edges and closing gable ends
  automatically, with purlins underneath for hanging PIDS and signs.
- **El Station Name Board** — the black Bay Parkway-style board, name live
  from the MTR station, right-click for a custom name.

The concrete and tile platform floors above are the intended walking surface.

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
  arrows; Enter makes a second line), **Arrow** (eight directions, left edge /
  inline / right edge), **Station** (the MTR station's name, or an override),
  **Exit** (an exit from MTR's station settings: the word "Exit", its street
  names, the exit's name in a box, plus a corner note you type — white on the
  MTA red), **Line + destination** (a route's bullet with "to <terminus>", its
  direction wording, or your own text), **Space**.
- Styles: black with the thin white line (today's standard), the 1970 white
  panel with a black band, or plain black.
- Everything is stored by name: rename a line, repaint it, change an exit's
  streets or a route's terminus in MTR and every sign follows.
- The older text signs — the entrance railing sign, the el entrance sign and
  the el station boards — now use the same editor and typeface. Old placements
  keep their content.

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

**Right-click** the block to open its settings screen:

| Setting | Meaning |
|---|---|
| Announcement | The text to display and speak (up to 512 characters). |
| Volume | 0–100 %. Scales chime and speech loudness; farther players hear it quieter. |
| Delay | Seconds to wait after being triggered before the announcement plays (0–60 s). |
| Radius | Only players within this many blocks (1–128) receive the announcement. |
| Tag | Freeform identifier used by `/announce`. Many blocks may share one tag, so a single command can fire a whole station. |
| Chat Text | ON/OFF — whether this block shows the `[PA]` message in chat/action bar. Turn OFF for audio-only announcers. |
| Chime | ON/OFF — whether this block plays a chime before speaking. |
| Chime Sound | Dropdown of built-in chimes (since 1.6): the classic Ding-Dong plus six Marimba and five Synth chimes. The voice always waits for the selected chime to finish before speaking. |

> **Changed in 1.2:** the message pool (Random/In Order) and the random auto-trigger moved
> from the Station Announcer to the **PA Control Box**. Old announcer blocks in existing
> worlds load fine — the removed settings are simply ignored.
>
> **Changed in 1.3:** pool announcements are separated with `||` instead of line breaks
> (so a single announcement may span several lines). Pools written before 1.3 that relied
> on one-message-per-line play as one combined announcement until you re-open the Control
> Box and add `||` between them.

Press **Done** to save (settings are stored in the block and survive world reloads),
**Cancel**/Escape to discard.

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

- **PA Control Box** — the brains. Same triggering as the Station Announcer (GUI, redstone
  rising edge, tags/`/announce`, delay) **plus** the message pool and the random auto-trigger
  (Auto min/max, up to 600 s). Separate announcements with a double pipe — e.g.
  `Mind the gap || Train approaching || Stand clear` — and each firing plays one of them
  (Random or In Order playback). It plays
  no sound itself: when triggered it broadcasts through every linked speaker. With zero linked
  speakers it does nothing (its GUI shows "No speakers linked"). The GUI lists linked speakers
  (count + positions) and has an **Unlink All** button.
- **PA Speaker** — where the sound comes out. Right-click to set its **volume** and **sound
  radius**; its GUI also shows the link status. A player in range of *any* linked speaker
  hears the announcement exactly **once** (the loudest speaker for that player wins), so
  overlapping speaker ranges are safe.
- **Speaker Link** (item) — how you wire them up. Linking works in **both directions**;
  select either end first (the tool remembers it, and the tooltip shows the selection):
  - **Box first:** right-click a PA Control Box, then right-click each Speaker to link it.
    The box stays selected, so you can wire a whole platform in one sweep.
  - **Speaker first:** right-click a Speaker, then right-click a PA Control Box — that
    speaker is linked and the selection clears.
  - Linking a speaker that already belongs to another box **moves** it (both boxes update).
  - **Sneak-right-click a Speaker** to unlink it. **Sneak-right-click air** to clear the
    selection. Clicking another box/speaker just reselects.

  While you hold the Speaker Link, **thin white lines are drawn between every control box
  and its linked speakers** nearby, so you can see the whole network at a glance.

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
