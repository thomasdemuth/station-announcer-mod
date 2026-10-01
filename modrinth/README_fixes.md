# README.md: inaccuracies and proposed fixes

Checked against the source at 3.4.2 (commit a242cdd + working tree). README.md was NOT edited. Line numbers are README.md's.

## Wrong

### 1. Intro claims there is no MTR dependency and no mixins (L8–9)

> it does not depend on MTR and does not conflict with it (no mixins, no vanilla behavior overrides).

False. `fabric.mod.json` depends on `mtr >=4.0.0`, and `station_announcer.mixins.json` lists 14 common + 2 client mixins, all into MTR classes (Depot, Siding, Vehicle, Main, RenderLifts, VehicleExtension). The intro also describes only the PA system.

**Replace L3–9 with:**

> A station add-on for **[Minecraft Transit Railway (MTR)](https://modrinth.com/mod/minecraft-transit-railway) 4** on **Fabric 1.20.4**. It started as a public-address system: type an announcement, trigger it with redstone or `/announce`, and nearby players see `[PA] ...` and hear it **read aloud by their own computer's text-to-speech** after a chime. It now also has NYC and railroad departure displays, an MTA sign editor, working fare gates, an elevated-station building kit, gap fillers, builder tools, operations tools for MTR (hold rules, dwell, platform groups, interlining) and a web dispatch board with a journey-planning map.
>
> **Requires MTR 4.0.x and Fabric API.** The operations features use mixins into MTR's own classes; nothing in vanilla Minecraft is mixed into or overridden.

### 2. The install steps leave out MTR (L31–35)

**Replace step 2 with:**

> 2. Drop **Fabric API**, **Minecraft Transit Railway 4.0.x** and **station-announcer-\*.jar** into your `mods` folder, on the client and on the server.

### 3. The creative tab is "Baker City" or "Redstone" (L42–43, L407–408, L513–514)

There is no single "Baker City" tab, and no Redstone tab. There are three: **Baker City: Decoration**, **Baker City: Operations** (PA blocks, Speaker Link, every PIDS, holding lights, creators, gap fillers, markers, poster frame, ambience) and **Baker City: Modern Stations (ESI)**.

- L42–43: "live in the **Baker City** creative tab" → "live in the **Baker City: Operations** creative tab".
- L407–410: "All of this mod's blocks and items live in the **Baker City** creative tab (since 1.5; previously the Redstone tab). The **PA Station Announcer** block (named "Station Announcer" before 1.4) also drops itself and is fastest mined with a pickaxe)." The tab is wrong and the sentence is broken. Replace it with: "The **PA Station Announcer** is in the **Baker City: Operations** tab. It drops itself when broken and is mined fastest with a pickaxe."
- L513–514: "...and appear in the Redstone creative tab." → "...and appear in the **Baker City: Operations** creative tab."

### 4. The NYC PIDS brush opens MTR's config screen (L61–64)

> right-click with the MTR brush to open the standard PIDS config screen ... the config screen's message rows become the "Happening now" text

This is out of date: since 2.3 the brush opens the mod's own `NycPidsScreen` (the shared platform picker plus a single "Happening now" box), and the README's own "platform picker" section (L252–265) says so. **Replace it with:** "Right-click with the **MTR brush** to open the settings screen: tick platforms in the shared platform picker (see below) and, on the departure styles, type a "Happening now" message (leave it empty to hide the section)."

### 5. Pillar Creators are "ten variants" (L376–390)

> Ten variants in the Baker City tab, named **width x spacing**

The tab now holds one configurable **Pillar Creator** (width, spacing and style set in a settings screen with a 3D preview; right-click in the air to open it). The ten `pillar_creator_<w>x<s>` items are still registered so that old items keep working, but they are hidden from the tab (`MtrPillars` L84). **Replace the first two paragraphs with:** "One **Pillar Creator** (Operations tab). Right-click in the air to set the pillar width, spacing and style in its settings screen, which has a live 3D preview."

### 6. `maxLinkDistance` default is 128 (L498–499, L526)

The real default in `ServerConfig` is **500** (clamped to 8–1024). Fix both the prose ("default 500") and the JSON example (`"maxLinkDistance": 500`).

### 7. The `client.json` example is incomplete (L535–555)

It leaves out `chimeCategory` (default `"master"`; can be `master`/`blocks`/`ambient`/`voice`). Add the key to the JSON and this bullet:

> - `chimeCategory` — which volume slider the chime follows (`"master"` by default, so the chime still plays when the Voice slider is turned down).

Also mention the in-game settings screen: "Every client setting is also on the in-game settings screen: open it from **Mod Menu**, or bind the **Open settings** key (unbound by default). There is also an unbound **Mute spoken announcements** key."

### 8. "Hooking announcements to MTR events (future addon idea)" (L588–604)

> **Depend on MTR** (this mod stays independent)

False: the mod depends on MTR, and part of this idea already exists. Disruptions announce through the station PA automatically, and route announcement templates replace MTR's on-board wording. **Retitle it** "Driving announcements from other mods". Keep the `AnnouncerRegistry.trigger` / `/announce` advice, delete "this mod stays independent", and add a pointer to the wayfinding API (`com.stationannouncer.api`, `tools/build_api_jar.sh`).

### 9. Project layout (L606–610)

It lists only two packages. **Replace it with:** `com.stationannouncer` (PA core, blocks, networking), `.mtr` (MTR-backed blocks: PIDS, signs, fare gates, el kit, creators), `.mtraddon` (operations + dispatch web server), `.wayfinding` (markers, places, station layouts), `.api` (pure-Java wayfinding API for other mods), `.material` (any-texture ramps and stairs), `.mixin` (mixins into MTR only), `.client.*` (screens, renderers, TTS).

### 10. Speaker Link lines (L495–496)

> thin white lines are drawn between every control box and its linked speakers

Since the fix, they are pulsing white beams (`LinkLineRenderer`), and they are also drawn to linked PIDS displays. → "**pulsing white beams** connect every control box to its linked speakers and displays."

## Missing (documented nowhere in the README)

These are real, shipped features with no README section. Add short sections, or link to the Modrinth page:

- **Operations add-on**: hold rules + Holding Lights' hold-rule mode, per-route dwell, platform groups, depot groups / delays, Duplicate line, temporary stop changes, door obstructions, driving HUD (H key), multi-sided lift doors, station accessibility, route announcement templates, line bullet shapes. Only interlining has a section (L290–324).
- **Dispatch web UI + System Map+**: the URL (`http://<host>:<MTR webserverPort, 8888 by default>/dispatch/`), what it shows, `/navpair`, `/nav`, `/dispatch stats`, `/dispatch basemap scan|status`. **Security note**: no authentication, it shows player names and positions, and `dispatch.enabled` turns it off.
- **Wayfinding**: Exit Marker, Place Marker, `/place`, `/stationlayout`, the API.
- **Gap fillers**, **curved platform edges + Curved Platform Creator**, **material Ramp (ADA 1:12) / Stairs (any texture)**, **El Structure Creator**, **subway tile walls + name tablet**, **floors**, **zebra-board labels** (they have a section), **car stop markers**, **Do Not Cross Tracks signs/gates**, **departure boards** (Railroad Departure Board, Station Departure Board).
- **Bundled trains** (LIRR M7, NYCT R62).
- **Config files** other than server.json/client.json: `config/station-announcer-addon.json`, `config/station-announcer-addon-client.json`, `config/station_announcer/bridge_presets.json`, `sign_templates.json`, `sign_templates_hidden.json`, `material_recent.json`. Per world, under `<save>/station-announcer-addon/`: `data.json`, `gap_fillers.json`, `line_styles.json`, `wayfinding.json`, `nav-tokens.json`, plus `layouts/`, and `<save>/station_announcer/materials.json`.
- A **commands table**, covering `/announce`, `/bridge`, `/nav`, `/navpair`, `/place`, `/stationlayout` and `/dispatch`.

## Structure

- The **"Service change posters"** section (L619–637) comes after **License** at the very end. Move it up next to the sign or disruption material, and keep License last.
- "new in X.Y" version tags (1.5, 1.7, 2.0, 2.2, 2.3, 2.4.57, 3.2) and the "Changed in 1.2/1.3" migration notes mean nothing to first-time readers. Move them to a CHANGELOG or drop them.
- The licence section should also credit Minecraft Transit Railway and include the "not affiliated with the MTA/NYCT/LIRR" line.
