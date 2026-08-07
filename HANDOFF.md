# HANDOFF — Station Announcer Mod (2026-07-24)

Quick-resume notes for the next session. Deep technical detail lives in
[CLAUDE.md](CLAUDE.md) (read "Current state" + the v1.7.0 iteration notes and the
v2.0.0 audit section); this file is the short version of where things stand and
what to do next.

## Where we are

**v2.1.0 is built and in `releases/station-announcer-2.1.0+1.20.4.jar`** (MC 1.20.4,
Fabric, MTR 4.0.5 required). Jar naming is Modrinth-style: `mod_version=2.1.0+1.20.4`
in gradle.properties → `station-announcer-2.1.0+<mcver>.jar`. Clean-built and
boot-verified (1.767 s, zero mod errors). v2.0.0 is kept alongside it in `releases/`.
Not uploaded anywhere yet.

What 2.0.0 contains on top of 1.7.0:

- Station trash can **removed** entirely; `leaning_bar` **renamed to `platform_barrier`**
  and remodelled from the user's NYC photos: woven wire mesh (the mod's first cutout
  block — BlockRenderLayerMap in StationAnnouncerClient), stainless frame, octagonal
  full-width top rail so runs read as one continuous barrier, posts at both block edges
  with bolted foot plates (joints show doubled members like the real thing). Recipe
  III/BBB (iron bars) → 4. Old placed leaning bars do NOT migrate (id changed).
- Blocks that were never in the docs (added earlier, audited this session): **turnstiles**
  (`turnstile` + `turnstile_cap`, 2-tall lanes charging via MTR TicketSystem.passThrough),
  **pipes** (1–4 packed per block, 3 sizes × 5 colors, brush repaints/resizes whole
  segments), **holding lights** (yellow arrival / green departure, timed off live MTR
  arrivals, brush platform picker; + `holding_light_pole`), and **`pids_pole`**.
- **Full code audit (2026-07-23), all fixes verified** (build green, server boots clean,
  PA link + /announce smoke-tested, client loads with zero mod resource warnings):
  removed dead `side=middle/single` blockstate variants (were 16 load exceptions per
  reload); static per-position client maps now cleared on disconnect and the turnstile
  cooldown map on server stop; fixed a TTS stop/start process race; guarded a
  theoretical modulo-by-zero in the marquee scroller. Everything else checked clean —
  networking validation, BE lifecycle, multiblock loot gating, full asset consistency.

## Conduit pipes reworked — shipped in v2.1.0 (2026-07-24)

**Latest pass**: pipes now connect ACROSS surfaces (ceiling -> wall -> floor), `size` and
the colour `black` are gone, and connections left the blockstate entirely — the client
builds the block from pre-baked pieces at mesh time (`client/mtr/PipeModel`, a Fabric
BlockStateResolver). **96 blockstates, no multipart, no `blockstates/pipe.json`.** That
is 40x fewer states than the mod ever had; server boot is back to 1.23 s (baseline was
1.17 s). Full detail and the measurement table are in CLAUDE.md.


- Pipes now **hug the surface they were placed against** (ceiling / wall / floor) via a
  new `mount` property; **junction boxes appear only at turns and branches**, dead ends
  get caps, and all five z-fighting sources are gone.
- **`size` was removed** — one gauge (the old size 1). Sneak-brush no longer resizes; it
  re-seats a segment onto the next surface.
  and the blockstate. Never hand-edit those files.
- **Performance**: a straight-run block went 54 -> 24 quads (56% fewer). Careful, though:
  an intermediate version of this work multiplied the blockstates to 23,040 and froze the
  client — see the table in CLAUDE.md. Current is 7,680 states / 2.70 s server boot vs
  1.17 s originally. **Always measure blockstate changes with `runServer`, never the
  client** — the server builds every state with no GPU involved.
- Existing placed pipes were reset to defaults (the `size` property is gone).
- CLAUDE.md records the known next optimisation (move connections out of the blockstate
  -> 120 states, zero multipart) with all the Fabric APIs pre-verified, if it is wanted.

## Multi-version ports: ON HOLD (user paused; task list has them pending)

The user asked for 2.0.0 on **1.20.4, 1.21.4, 1.18.2, 1.16.5**, then paused before any
porting happened. Groundwork already done:

- MTR exists for all four (Modrinth `maven.modrinth:minecraft-transit-railway`):
  FABRIC-4.0.5+1.20.4 / +1.18.2 / +1.16.5, and **FABRIC-4.1.0-beta.2+1.21.4** (beta!).
- Toolchain per target: yarn 1.21.4+build.8 / 1.18.2+build.4 / 1.16.5+build.10;
  fabric-api 0.119.4+1.21.4 / 0.77.0+1.18.2 / 0.42.0+1.16.
- `versions/1.21.4/` scaffold exists (full project copy, gradle.properties/build.gradle/
  fabric.mod.json adjusted). First blocker hit: **MTR 4.1-beta needs Loom ≥1.16.3**
  (and likely a newer Gradle wrapper in that folder). Java 21 is available at
  `~/Library/Application Support/minecraft/runtime/java-runtime-delta/...` (JDK 21.0.7
  with javac; epsilon = JDK 25). 1.21.4 port also means: Identifier.of, CustomPayload
  networking, item components instead of NBT, Item/Block registryKey settings, new onUse
  signature. 1.18.2/1.16.5 are big backports (Registry/util.registry, LiteralText,
  MatrixStack screens, no EditBoxWidget; 1.16.5 additionally BE ctor without pos/state,
  Tickable, Java 8 target). Plan: one standalone project per version under `versions/`.

## Needs the user's in-game pass (unchanged, nothing blocks)

1. Bench sit height feel — if off, one constant in `BenchBlock.onUse`
2. Fare machine balance flow on the real server; turnstile charging feel
3. Mini-PIDS arrow + holding lights at a real platform with live trains
4. Railing/viaduct creator build flow on real track; glint feel
5. Ambience loudness/radius defaults; mosaic + tinted columns in real station areas
6. Platform barrier look on their server (verified in dev screenshots only)

## Next up

1. **User decides**: ship 2.0.0 for 1.20.4 only, or resume the multi-version ports
   (newest-first: 1.21.4 → 1.18.2 → 1.16.5).
2. **Entryway blocks** — remaining half of "railings and entryway blocks": entrance
   canopy/kiosk pieces, stair surround, tiled stair walls (ask for references).

## Idea backlog (agreed candidates, not started)

- **Auto train announcements** (top pick, closes the README promise): control box /
  announcer watches a platform and fires templated TTS ("{route} to {destination}
  arriving") — server side has full arrival data; PIDS code shows the client math
- Tunnel light creator (lights every N blocks along a rail)
- Wayfinding/exit signs with route bullets; service status board fed by control box pool
- Scheduled (clock-based) announcements; in-train announcements would need an MTR addon

## How to work on this project (fast path)

```sh
export JAVA_HOME="$HOME/Library/Application Support/minecraft/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
./gradlew build     # jar -> build/libs/, copy releases/station-announcer-<v>.jar (never delete old)
```

- **Dev server WITH MTR** (easier than the scratchpad rig): fifo into gradle —
  `mkfifo $SP/cmdpipe; tail -f $SP/cmdpipe | ./gradlew runServer --console=plain > $SP/server.log &`
  then echo commands into the pipe one at a time. Kill leftovers first:
  `lsof -ti :25565 | xargs kill -9` (a zombie server = BindException on boot).
- **Dev client**: `./gradlew runClient --args="--quickPlayMultiplayer localhost:25565"`.
  run/options.txt already has pauseOnLostFocus:false + gamma 1.0.
- **Headless screenshots**: `touch run/screenshot.flag` → PNG in `run/screenshots/`
  (dev-only hook). The client window must be VISIBLE — occluded/minimized gives a stale
  framebuffer; log-based checks still work. macOS screencapture is permission-blocked.
- `/setblock` never runs placement hooks: multiblock partners (PIDS halves, fare machine
  and turnstile uppers) and connection props need explicit blockstate properties.
- `/data merge` longs need the `L` suffix (`{ControlBox:123L}`) — a bare big number
  parses as a STRING tag and silently reads back as 0.
- Model JSON rule: omit `uv` (auto-UV = 1:1 texels, textures drawn in model space); the
  explicit-uv style is only for whole-face screens (PIDS frames, fare machine fronts).
  Never let two elements share an exact plane (z-fighting — see barrier lesson).
- javap against `.gradle/loom-cache/remapped_mods/...minecraft-transit-railway...jar`
  before using any unverified MTR API.

## Version bookkeeping

- gradle.properties `mod_version=2.1.0+1.20.4`; next feature batch bumps to 2.2.0
  (ports carry their own `+<mcver>` suffix). Fresh jars go to `releases/`, keep all
  old ones — 1.2.0 through 2.1.0 are all preserved there.
- Keep CLAUDE.md's "Current state" + chronological log updated per release — it is the
  real memory of this project.
