# Station Announcer

**Everything a Minecraft Transit Railway station is missing.** Station Announcer started as a public-address system — type an announcement, and everyone on the platform sees `[PA] …` and hears it *spoken aloud* after a ding-dong chime — and grew into a full station kit for MTR 4: NYC-style countdown clocks and railroad departure boards, a modular MTA sign editor, animated fare gates that actually charge MTR fares, a complete elevated-station building kit, gap fillers, a bridge builder, operations tools that hold trains for connections and space shared lines evenly, and a live web dispatch board with a journey-planning network map.

<!-- GALLERY: hero shot — a finished el station platform at dusk: PIDS clock, MTA signs, canopy, a train in the platform -->

> ### Requirements
> | | |
> |---|---|
> | Minecraft | **1.20.4** |
> | Loader | **Fabric Loader 0.15+** |
> | Required mods | **[Fabric API](https://modrinth.com/mod/fabric-api)** and **[Minecraft Transit Railway](https://modrinth.com/mod/minecraft-transit-railway) 4.0.x** (tested on 4.0.1 and 4.0.5) |
> | Side | Install on **both client and server** |
> | Optional | [Mod Menu](https://modrinth.com/mod/modmenu) (settings screen) · [Sodium](https://modrinth.com/mod/sodium) works, no Indium needed |

Everything lives in three creative tabs: **Baker City: Decoration**, **Baker City: Operations** and **Baker City: Modern Stations (ESI)**. Most blocks are configured by right-clicking with **MTR's brush**.

---

## 📢 PA announcements & text-to-speech

- **PA Station Announcer** — a self-contained announcer: text (up to 512 characters), volume, radius (1–128 blocks), delay (0–60 s), tag, chat on/off, chime on/off.
- **PA Control Box** + **PA Speaker** — a station-wide PA network. The box holds a **pool of announcements** (separate them with `||`) played randomly or in order, with an optional **random auto-trigger**; speakers set their own volume and radius. A player in range of several speakers hears the announcement **once**, from the loudest.
- **Speaker Link** — wire speakers (and displays) to a box from either end; while held it draws pulsing beams between every box and its speakers. Links survive structure blocks / schematics.
- **Triggers:** a redstone **rising edge**, or `/announce tag=<tag>` (fires every loaded block with that tag — perfect for command blocks).
- **Spoken on each player's own computer** — Minecraft's built-in narrator voice, falling back to the OS voice (`say` on macOS, PowerShell System.Speech on Windows, `espeak` on Linux). Volume falls off with distance; a new announcement cuts off the old one instead of talking over it.
- **12 chimes** — the classic ding-dong plus 6 marimba and 5 synth chimes, picked from a dropdown with preview; the voice waits for the chime to finish. Resource packs can add more.
- **Linked displays:** link a PIDS to a control box and the hanging countdown clock scrolls each announcement across its bottom row, while departure screens rotate the box's messages in their "Happening now" panel.
- **Custom on-train announcements:** an **Announcement…** button on MTR's Edit Route screen replaces MTR's next-station wording with your own template (`{next}`, `{dest}`, `{route}`, `{interchanges}`, conditional "if transfers / if last stop" sections, NYC/Simple/UK presets, live spoken preview).

<!-- GALLERY: PA Control Box GUI next to a platform with linked speakers and the Speaker Link beams visible -->

## 🕒 PIDS & departure boards

All boards read live arrivals from MTR, auto-detect nearby platforms, and share one **platform picker** (every platform in the station, nearest first, with the lines that call there as coloured chips).

- **NYC countdown clocks (6):** *PIDS NYC Wall 1 (Route Map)* / *Wall 2 (Departures)*, *Standing 1 / 2* (double-sided totems), *Hanging B-Division* and *Hanging B-Division Mini*. Minute → second countdowns, a flashing row when the train is in, the route map with connecting-line bullets, marquee text where it doesn't fit, an optional "Happening now" message. The Mini has a **"Next train"** arrow mode pointing toward the arriving train's platform.
- **Railroad PIDS (Wall / Standing / Hanging)** — commuter-rail boards at double resolution: line + clock, departure bar, the stops ahead with **live connections** due within two minutes; the hanging case flips between *Next train* and a `TIME · DESTINATION · ETA · TRK` list (timed, pinned or automatic).
- **Railroad Departure Board** — portrait concourse screen with route numbers and a track column that stays blank until the track is announced (default 6 min before).
- **Station Departure Board (wall / hanging)** — place a rectangle of blocks and they **merge into one big screen**; a taller board simply lists more trains.
- **NYC PIDS Pole** — drop pole to hang clocks and boards lower.

<!-- GALLERY: NYC hanging countdown clock + Railroad hanging board side by side -->
<!-- GALLERY: a merged 4×2 Station Departure Board in a concourse -->

## 🪧 MTA sign system

- **Subway Sign** (full and half height) — wall, hanging or standing; side-by-side panels merge into one long sign.
- **Full sign editor** (right-click): rows of modular tiles — route **bullets**, text (up to three lines, inline bullets/pictograms), **arrows and pictograms** (wheelchair, elevator, escalator, stairs, ramp, no entry, information, bus, train), **station name**, **exit** (fed from MTR's station exits), **line + destination**, dividers, coloured badges ("LIRR", "M15 SBS"), spacers.
- Three-zone rows (left / centre / right), drag tiles on the canvas with snapping, free plate size and offset, a live **In-world** camera view, copy/paste, **15 built-in templates** plus your own saved templates.
- Station names, exits, line colours and destinations are stored by name and update when you change them in MTR. The step-free wheelchair symbol appears automatically (filled = fully step-free, outline = partially).
- Set in TeX Gyre Heros (a Helvetica-style face). Per-line **bullet shape** (circle / diamond / square) is set on MTR's Edit Route screen and used everywhere.
- The older name boards (entrance railing sign, el signs, named columns) use the same editor.

<!-- GALLERY: sign editor screen with the live preview and inspector -->

## 📄 Service changes & disruptions

- **Disruptions** board on MTR's dashboard: affected lines, a message the **station PA announces automatically** at every affected station (and pushes to linked displays).
- **Temporary stop changes** — skip a stop or add one to a line as a runtime overlay, with an expiry; saved routes are never edited.
- **Service change posters** — an MTA planned-work poster designer (header, timing line, dates, line bullets, headlines, body text, big arrows, inline bullet/diamond/wheelchair tokens) with live preview. Hang them in a **Service Change Poster** frame; the frame keeps its own copy after the disruption ends.

<!-- GALLERY: poster editor + a hung service change poster on a tile wall -->

## 🦓 Zebra boards & car stop markers

- **Zebra Board** / **Hanging Zebra Board** — merging striped berthing boards with bolted end plates, and brush-editable labels ("R-160", "8", "10 CAR").
- **Car Stop Marker** + **Stop Marker Pole** — stacks of 1–4 plates (black / white / yellow OPTO) with editable legends, mounted on ceiling, wall or floor, flush, on a bracket or as a blade.

## 🎫 Fare control

- **Turnstile**, **Exit-Only Turnstile**, **Turnstile Array End Panel** — animated tripod turnstiles that **physically block the lane until MTR charges your fare**, then turn as you walk through. Reader lamps show GO / STOP / WAIT; empty-hand right-click tells you your balance; overhead tubing bridges between units automatically.
- **High Entrance/Exit Turnstile (HEET)** — the full-height rotating cage, 2×2 blocks, same fare behaviour.
- **MetroCard Vending Machine** — emerald quick top-up, or opens MTR's ticket-machine screen.
- **Emergency Exit Door** — alarm with strobe (15 s), redstone-operable; entering the wrong way costs MTR's **$500** fare-evasion fine.
- **Iron Bar / Grille Dividing Walls** with corners and T-junctions, and **Employee Doors** (mesh / black / tiled white) that swing open and close themselves, with editable labels.

<!-- GALLERY: a turnstile bank mid-turn with GO lamp, HEET beside it -->

## 🏗️ Station architecture

- **NYC elevated station kit** (classic look + the modern **ESI** look on its own tab) — street columns, plate girders that frame into columns, track and platform decks, gable canopy roofs up to 8 wide with hips and valleys, canopy posts, windscreen walls (board, wired glass, cream, green, sash windows, doorways, name signs), railings, lamp poles, canopy lights, mezzanine floors and pressed-tin ceilings, stair side courses, stair and landing roofs. Every piece connects itself, by hand, `/fill` or schematic paste.
- **Platform Edge (Tactile Strip)** — opens MTR train doors like MTR's own platform; **Curved Platform Edge** follows curved track.
- **Subway stairs & handrails** — *Floating* and *Old Subway Stairs* (flights build by clicking, safety-yellow end treads), *Subway Stair Stringer*, and four **Subway Handrail** styles with straight, sloped, transition and corner shapes.
- **Subway tile walls** — white (grimy / fresh), station-colour band, painted black, track-wall alcoves, the no-clearance stripe, and a **Subway Wall Name Tablet** spelled out in real tiles.
- **Floors** — tiled and poured-concrete platform floors (1–4-block slabs, grimy and fresh) with randomised wear.
- **Mosaic Station Name Sign**, **Iron Platform Columns** (plain / station-colour tint / named), **Subway Entrance Globes** + **Globe Lamp Pole**, **Entrance Railing** + station-sign railing, **Platform Bench** (sit on it; benches merge), **Platform Barrier**, **Do Not Cross Tracks** signs and swinging warning gates.
- **Ramp (ADA 1:12)** and **Stairs (any texture)** — take the texture of *any* full block via a searchable picker; ramp runs continue themselves.
- **Station Ambience Block** — a looping station hum or air-vent sound with volume and radius.

<!-- GALLERY: classic el station from the street: stairs, canopy, globe lamps -->
<!-- GALLERY: ESI vs classic side-by-side -->
<!-- GALLERY: underground platform with tile walls, name tablet, mosaic, columns, bench -->

## 🚇 Gap fillers

- **Gap Filler (Union Square)** and **Gap Filler (South Ferry Loop)**, straight and curved — platform edge pieces that **extend to bridge the gap before the doors open** and retract before departure, interlocked with MTR's train doors. Per-platform extend/retract times and minimum dwell.

<!-- GALLERY: South Ferry loop fillers extended against a stopped train -->

## 🛠️ Builder tools

- **Bridge Creator** — click two rail nodes and get a full bridge or viaduct under the line: follows curves and grades, auto-detects parallel tracks, decks, girders, railings, piers, arches, a material per part, 9 built-in presets plus your own, a live 3D preview, and undo.
- **El Structure Creator** — builds the el kit's track deck, columns and cross girders under a stretch of track.
- **Pillar Creator**, **Railing Creator 3/5/7/9**, **Viaduct Creator 3/5/7** — quick supports, deck railings and whole viaducts along MTR rails.
- **Curved Platform Creator** — cuts a platform edge to follow curved track.

Creator tools are creative-mode items (no crafting recipe), like MTR's own.

<!-- GALLERY: Bridge Creator screen with 3D preview + the built curved 3-track viaduct -->

## 🚦 Operations (adds buttons to MTR's own screens)

- **Hold rules** — hold a train at a platform while a connecting train is approaching, with transfer windows. **Holding Light (Yellow / Green)** blocks show holds and departures.
- **Per-route dwell** — different dwell times per line at the same platform.
- **Platform groups** — trains rotate between alternative platforms (e.g. alternating terminal tracks).
- **Interlining** (Dashboard → Tools… → Interlining…) — finds every shared section, shows whether trains arrive evenly or in pairs, and suggests depot delays, platform holds and frequencies for even headways; apply in one click.
- **Depot delays & groups**, **Duplicate line**, **temporary stop changes**.
- **Random door obstructions** — a small chance something gets caught in the doors (default 3 %, 2–6 s).
- **Driving HUD** for manual driving — speed limit, signals, next stop, on-time status, doors (press **H** for settings).
- **Multi-sided lifts** — doors on more than one side of an MTR lift (Door sides… on the lift screen).
- **Station accessibility** — mark stations or individual platforms step-free (Accessibility… on MTR's station screen); used by signs, maps and routing.
- **`/dispatch stats`** — on-time %, headways, dwell and bunching alerts per line.

## 🗺️ Web dispatch board & System Map+

Served from MTR's built-in web server (the Dashboard gets **Dispatch** and **Map+** buttons):

- **Dispatch** — live trains on the real track map, stringline (time–distance) diagrams with scheduled-vs-actual, station panels, search, analytics, and the interlining tools with apply.
- **System Map+** — an always-schematic network map (lines drawn by colour, interchanges, terminal bullets), light and dark themes, a water/forest/satellite basemap scanned from your world, live trains and player positions.
- **Station pages** *(new in 3.4.3)* — double-click a station on the dispatch map for a full page: a **3D schematic** built from the layout scan (platforms in line colours, paid / unpaid concourse, stairs, escalators, lifts, fare gates, exits with step-free markers and walking routes; layer toggles and a cut-away slider), live departure boards per platform, scheduled vs actual headways with bunching, per-train delays, a walking-time matrix between platforms, and each exit's step-free verdict.
- **Journey planner** — live departures, transfers, walking links through station exits, **step-free routing**, places search. **Live tracking** follows you on board and offers faster routes; **Send to game** (after `/navpair`) puts directions in your HUD with a waypoint. Or use `/nav <station>` in game.

<!-- GALLERY: System Map+ dark theme with a planned journey -->
<!-- GALLERY: Dispatch board with stringline -->

## 🧭 Wayfinding

- **Exit Marker** — give MTR station exits real positions; edit, create and remove MTR exits from a flat editor. **Place Marker** / `/place` — named points of interest in 10 categories (landmark, park, food…), shown on Map+.
- Both markers are invisible unless you hold the MTR brush, a marker or a rail tool.
- **Station layout scans** (`/stationlayout` or the Layout tab) find walking paths, fare control, lifts and step-free routes inside your stations; Map+ routes walks through the right exit.
- **API for other mods** — a pure-Java wayfinding API (`com.stationannouncer.api`: places, exits, station layouts, enter/leave events).

---

## Commands

| Command | Who | What |
|---|---|---|
| `/announce tag=<tag> [tag=…]` | op (level 2, configurable) | Fire every loaded announcer / control box with those tags |
| `/bridge build <from> <to>` · `preset "<name>"` · `tracks build\|clear` · `undo` | op | Bridge Creator from the command line |
| `/nav <station>` · `/nav stepfree <station>` · `/nav stop` | everyone | In-game journey directions to a station |
| `/navpair` · `/navpair list` · `/navpair revoke <n\|all>` | everyone | Pair a browser with your player (Send to game, web apply) |
| `/place list [category]` · `near` · `info <place>` | everyone | Browse places |
| `/place add\|rename\|category\|describe\|radius\|hide\|show\|move\|tp\|remove …` | op | Manage places |
| `/stationlayout status` · `show <station>` · `hide` | everyone | Inspect scanned station layouts |
| `/stationlayout scan <station>` · `scanall` · `cancel` | op | Scan station layouts |
| `/dispatch stats [line]` · `basemap scan [margin]` · `basemap status` | op (configurable) | Timetable analytics; redraw the Map+ basemap |

## Configuration

| File | Side | Highlights |
|---|---|---|
| `config/station_announcer/server.json` | server | `announcePermissionLevel` (2), `maxTextLength` (512), `maxLinkDistance` (500) |
| `config/station_announcer/client.json` | client | `enableTts`, `enableChime`, `displayMode` (`chat`/`actionbar`), `ttsBackend` (`auto`/`narrator`/`system`), `voice`, `chimeCategory` (`master`) |
| `config/station-announcer-addon.json` | server | one section per operations feature, each with `enabled`; `editPermissionLevel` (2); `dispatch.enabled`, `dispatch.autoScan`; `doorObstruction` chance/duration; `disruptions`; `analytics` |
| `config/station-announcer-addon-client.json` | client | which buttons appear on MTR screens; driving HUD and navigation HUD options |

In-game: **Mod Menu** → Station Announcer, or bind the *Open settings* key (unbound by default; there is also an unbound *Mute spoken announcements* key).

> ### ⚠️ Server owners: the web dispatch board
> The Dispatch / Map+ pages are **on by default**, served by MTR's web server at `http://<server-address>:<port>/dispatch/` — the port is MTR's `webserverPort` in `config/mtr.json` (**8888** by default; MTR takes the next free port if it is busy).
> - The pages need **no login** and show your network, live trains and **every online player's name and position**. Anyone who can reach that port can see them.
> - Changes from the browser (interlining apply, Send to game) only work for a browser paired in game with `/navpair`, and applying needs the edit permission.
> - **To turn it off:** set `"dispatch": { "enabled": false }` in `config/station-announcer-addon.json` and restart the server. Or simply don't open the port in your firewall. Set `dispatch.autoScan` to `false` to skip the automatic basemap scan at startup.

## Compatibility

- **MTR 4.0.x on Fabric 1.20.4 only.** The operations features hook MTR's internals (mixins into MTR classes only — none into vanilla Minecraft), so other MTR versions may not load.
- **Sodium**: works without Indium (nothing uses the Fabric Renderer API).
- Doesn't replace or modify any vanilla or MTR block.

## FAQ

**I don't hear the voice.** Text-to-speech runs on your computer, not the server. With `ttsBackend: "auto"` the mod uses Minecraft's narrator voice and falls back to your OS voice: `say` (macOS), PowerShell System.Speech (Windows), or `espeak` (Linux — install it with your package manager). Set `ttsBackend` to `"system"` to choose a specific `voice`.

**I don't hear the chime.** Chimes follow the **Master** volume slider by default (`chimeCategory` in client.json). Check that the block's Chime toggle is on.

**Where is the web UI?** Open MTR's Dashboard and press **Dispatch** or **Map+**, or browse to `http://<server>:8888/dispatch/` (Map+ is `/dispatch/map.html`). In singleplayer that's `localhost`. On a server, the MTR web port must be reachable.

**Do players need the mod?** Yes — install it on both the server and every client.

**Can I add my own chime?** Yes, any sound from a resource pack — see the GitHub README.

## Credits & licence

- Code: **MIT** © Thomas Demuth and contributors.
- Sign typeface: **TeX Gyre Heros Bold** © GUST e-foundry (B. Jackowski, J. M. Nowacki), bundled unmodified under the **GUST Font License**.
- Built for and on top of [Minecraft Transit Railway](https://modrinth.com/mod/minecraft-transit-railway) by the MTR team.
- Not affiliated with or endorsed by the MTA, NYCT, LIRR or any transit agency.
