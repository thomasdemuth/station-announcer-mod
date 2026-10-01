# Modrinth listing — form fields

Checked against Modrinth's live API (2026-10-01): MTR slug `minecraft-transit-railway` (project id `XKPAmI6u`), Fabric API slug `fabric-api` (`P7dR8mSH`); the slug `station-announcer` is currently free.

## Project

### Name (title, max 64 chars)

**Station Announcer** (Thomas's decision, 2026-10-01). Slug `station-announcer` (free as of 2026-10-01).

### Summary (Modrinth's short description, max 256 chars)

Recommended (162 chars):

> PA announcements with text-to-speech, NYC countdown clocks, MTA signs, working fare gates, an el station kit and a live dispatch map — a station add-on for MTR 4.

Alternative (149 chars):

> The station kit for Minecraft Transit Railway: spoken PA announcements, PIDS, MTA signs, fare gates, el stations, gap fillers and a web dispatch map.

### Categories

Modrinth mod categories (live list): adventure, cursed, decoration, economy, equipment, food, game-mechanics, library, magic, management, minigame, mobs, optimization, social, storage, technology, transportation, utility, worldgen.

- **Primary (max 3, shown on the card):** `transportation`, `decoration`, `utility`
- **Additional:** `management` (dispatch / web board), `technology`, `economy` (fare system)

### Environment

- Client: **Required**
- Server: **Required**

(The mod registers blocks and items, so both sides need it; TTS and rendering are client-only, announcements and fares are server-side.)

### License

- **MIT** (SPDX `MIT`) — matches `LICENSE` and `fabric.mod.json`.

### Links

- Source: https://github.com/thomasdemuth/station-announcer-mod — **currently a PRIVATE repo; make it public (or drop the link) before submitting, or Modrinth reviewers and users will hit a 404.**
- Issues: https://github.com/thomasdemuth/station-announcer-mod/issues (same caveat)
- Wiki: leave empty for now (the GitHub README is the manual)
- Discord: optional

### Icon

The jar icon is 128×128 (`assets/station_announcer/icon.png`: a red "1" bullet on white tile). Fine for the project icon; Modrinth accepts up to 256 KiB.

---

## Version 3.4.4+1.20.4

| Field | Value |
|---|---|
| Version number | `3.4.4+1.20.4` |
| Version title | `3.4.4 — first public release (1.20.4)` |
| Release channel | **Release** (or **Beta** if you want room for first-week fixes — several features were never play-tested; see the report) |
| Loaders | Fabric |
| Game versions | 1.20.4 |
| File | `modrinth/station-announcer-3.4.4+1.20.4.jar` — 3.4.3 (commit 85883b2) + fabric.mod.json description/contact links, built in a clean worktree (`../sa-release-3.4.4`), M7/R62 trains stripped (134 entries + m7_* sound keys). Unstripped build: `releases/station-announcer-3.4.4+1.20.4.jar`. |

### Dependencies

| Project | Slug | Type | Version |
|---|---|---|---|
| Fabric API | `fabric-api` | Required | any 1.20.4 build (dev uses 0.97.3+1.20.4) |
| Minecraft Transit Railway | `minecraft-transit-railway` | Required | `FABRIC-4.0.1+1.20.4` and `FABRIC-4.0.5+1.20.4` — both boot-tested 2026-10-01 on a clean Fabric 1.20.4 server with the release jar (blocks place, `/announce` fires, dispatch web UI starts, no mixin errors). |
| Mod Menu | `modmenu` | Optional | any 1.20.4 build |

### Changelog (first public version)

```markdown
**First public release.** Station Announcer is a station add-on for Minecraft Transit Railway 4 on Fabric 1.20.4.

What's in it:
- **PA system** — announcer blocks, control box + speaker networks, Speaker Link, 12 chimes, text-to-speech on each player's machine, redstone and `/announce` triggers, custom on-train announcement templates.
- **Displays** — six NYC countdown clocks, three Railroad PIDS, the railroad departure board and merging station departure boards, all with a shared platform picker.
- **MTA sign system** with a full editor, templates and an in-world preview; service change posters; disruptions that the PA announces.
- **Fare control** — animated, blocking turnstiles and HEETs that charge MTR fares, MetroCard machine, alarmed emergency exit door, employee doors, dividing walls.
- **Station architecture** — the NYC el kit (classic + ESI), subway stairs and handrails, tile walls, floors, columns, globes, railings, benches, any-texture ramps and stairs, curved platform edges, gap fillers.
- **Builder tools** — Bridge Creator, El Structure Creator, pillar/railing/viaduct creators, Curved Platform Creator.
- **Operations** — hold rules + holding lights, per-route dwell, platform groups, interlining, depot delays, temporary stop changes, door obstructions, driving HUD, multi-sided lifts, station accessibility.
- **Web Dispatch board and System Map+** with journey planning, step-free routing, live tracking and 3D station pages (on by default — see the server-owner note on the project page).
- **Wayfinding** — exit and place markers, `/place`, station layout scans, a wayfinding API for other mods.

Requires Fabric API and MTR 4.0.x (tested on 4.0.1 and 4.0.5). Install on client and server.
```
