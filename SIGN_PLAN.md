# NYC-STYLE SIGN SYSTEM — PLAN (2026-09-06, not started)

Goal: MTA/Vignelli-style wayfinding signs for stations — modular black panels with
route bullets, white Helvetica-ish text and arrows — whose content can be
**dynamic** (pulled from MTR: station name, routes serving a platform, exits,
connected stations) or **custom** (free text), edited in our own FlatUi editor,
while piggybacking on MTR's railway-sign machinery wherever it is cheaper.

Status: BUILDING (2026-09-06). Thomas's decisions (override §7): content
modules inside one panel; font = MTR's `mtr:mtr` Noto Sans (no TTF of our own);
BOTH half- and full-height panels, wall + hanging + standing mounts; first types =
directional, exit (fed by MTR station exits, with a corner note), station plates,
line + destination; FULL editor from day one; migrate the existing text signs
onto the unified system. Build log at the end of this file.

---

## 1. Research — what real MTA signage is (the rules we copy)

Sources: NYCTA Graphics Standards Manual (Unimark: Vignelli + Noorda, 1970),
its 1989 revision (Helvetica made official, black panels), MTA sign standards
in use today, plus photo knowledge of the system.

**System rules**
- **White text on black** panels. The 1970 manual actually specified *white*
  panels with a **black band across the top** (the band was meant to hide the
  mounting channel); the 1989 revision inverted everything to black. Both eras
  coexist in stations today — worth offering both as a "style".
- **Typeface**: Helvetica Medium (1989+). The 1970 manual specified Standard
  Medium (Akzidenz-Grotesk) because Helvetica was not yet available for the
  sign shop. Text is **sentence case** ("Uptown & The Bronx", "Exit"), never
  all caps — all-caps is the pre-1970 mosaic/enamel look.
- **Modular grid**: every sign is assembled from standard-height *modules*
  stacked or run side by side — a bullet module, a text module (1 or 2 lines),
  an arrow module, an exit-letter module. Signs are as wide as their content
  and mounted hanging, on walls, on columns, or over stair openings.
- **Route bullets** (coloured disc, white letter/number; black text only on
  the yellow N/Q/R/W Broadway bullets — `RouteBullets.needsDarkText` already
  handles this by luma) always come **first**, then the text, then the arrow. Express service = **diamond** instead of a disc.
  Late-night/part-time service uses a white-outlined/hollow bullet on newer
  signs.
- **Arrows** sit at the panel edge on the side they point: left/up arrows on
  the left edge, right arrows on the right edge; down arrows (stairs) either
  side. One arrow per panel row. Diagonal arrows (up-left etc.) for ramps.
- **Direction wording** is fixed vocabulary: "Uptown & The Bronx", "Downtown &
  Brooklyn", "Manhattan", "Queens", "Uptown", "Downtown", "to <terminus>"
  used on platform entrances and hanging signs.
- **Colour bar**: 1970-era directional signs carried a thin strip in the
  line colour under the top band; today the bullets carry the colour and the
  bar is gone. Offer as an option in the "1970" style only.

**Sign types seen in the system** (candidates for us)

| # | type | where | content |
|---|---|---|---|
| A | Station-name plate | platform walls, every ~30 ft; also 2-4 wraps per column | station name (1-2 lines), sometimes bullets; black plate, white text, or enamel white w/ black band |
| B | Directional (hanging/wall) | stairs, mezzanines, passageways | bullets + "Uptown & The Bronx" + arrow; multiple rows stacked |
| C | Exit sign | overhead near stairs to street | "Exit" + arrow, optionally street corner "Exit / 42 St & 7 Av" and/or exit letter box |
| D | Transfer sign | mezzanine | "Transfer to" + bullets + arrow; also "to <route> trains" |
| E | Platform column/edge sign | columns on platform | "Downtown & Brooklyn" + bullets, or just station name |
| F | Entrance sign | street | "Subway" + bullets (exists: `entrance_railing_sign`, `el_entrance_sign`) |
| G | Information pictograms | anywhere | wheelchair, elevator, escalator, MetroCard/OMNY, police, no entry, no smoking, emergency exit (green) |
| H | Mosaic name tablets | walls (pre-1970) | all-caps tile letters (exists: mosaic band, `tile_tablet`) |
| I | Service-change posters | walls | exists (`service_poster`) |

A, B, C, D, E and G are the gap. **B is the heart of the request** — the
hanging/wall directional sign is what people picture when they say "NYC sign".

---

## 2. What we can piggyback on

### MTR (bytecode-verified by survey of mtr-src, 4.0 format)
- **Railway Sign** blocks (`railway_sign_2..7_odd/even` + pole) render a run of
  0.5-block slots, each slot = a *sign resource*. Sign resources are **pure
  JSON**: `assets/mtr/mtr_custom_resources.json` in ANY jar/pack is merged, with
  `"signs": [{id, textureResource, flipTexture, customText (translation key),
  flipCustomText, small, backgroundColor}]`. → We can add NYC **pictograms and
  static-text tiles** (G above, plus "Exit", "Uptown", "Downtown"...) to MTR's
  own sign screen with zero Java. Cheapest possible win.
- The four **dynamic** MTR tiles are hard-coded by id (`exit_letter`, `line`,
  `platform`, `station`) — JSON cannot add new dynamic behaviour, and MTR's sign
  BE/screen/packet cast to MTR's own BE type, so our blocks cannot host MTR's
  editor (EL_STATION_PLAN already noted this).
- `RenderRailwaySign.drawSign(...)` is public static: our own renderer can draw
  any registered MTR icon inside our panels. So one "icon" module in our sign
  = a reference to an MTR sign id, and every MTR/resource-pack icon (Signs+,
  IVR packs) becomes usable in our signs for free.
- `DynamicTextureCache` (`getRouteSquare`, `getDirectionArrow`, `getRouteMap`,
  `getExitSignLetter`, `getSignText`) — MTR's texture generators, available
  if we want its look. We probably do NOT want them (they look like MTR, not
  MTA) except maybe `getRouteMap` for a "route map strip" module later.
- **Font**: MTR ships `mtr:mtr` (Noto Sans SemiBold TTF) as an ordinary
  Identifier font — usable from our text via `Style.withFont(new
  Identifier("mtr","mtr"))`. Sans-serif, close-ish to Helvetica.
- Data (client-side only, as everything else we draw): `simplifiedRoutes`
  (routes + platforms + colours + interchange data), `stationIdMap`,
  `Station.savedRails`/exits, `InitClient.findStation/findClosePlatform`,
  `MtrDataCache` wrappers.

### Our own code (surveyed)
- `CanvasPainter` — the whole emissive 64-units-per-block text/quad pipeline
  (`textFitted`, `circleBullet`, `prohibitionBullet`, `wrap`, `quad3d`).
- `RouteBullets` — route NAME → colour/label bullet, 3 s memo, `NO_ENTRY`
  sentinel, `RoutePicker` GUI list. Signs store route names, so repaints follow.
- `PosterLayout` + `Surface` (`GuiSurface` / `WorldSurface` / `MeasureSurface`)
  — "one layout, two surfaces" so the editor preview IS the world render.
  **This is the pattern to copy** for `SignLayout`.
- `FlatUi` + `TextBox` — the poster editor's design-tool kit (structure /
  preview / inspector, hit-rect list). A sign editor is the poster editor with
  a different block vocabulary.
- `StationDecorBlockEntity` + `update_decor`/`update_railing_sign` packets +
  `GUI_OPENER` chain in `MtrPidsClient:181-195` — brush → our screen routing.
- `ServicePoster` record/JSON codec + `addon_update_poster` — the storage
  pattern for a structured document on a BE (JSON string in NBT, packet codec,
  Builder for the editor).
- Merge-run logic (railing sign / mosaic / departure board `rectangle()`) for
  multi-block panels; the departure board's origin-corner rule is documented.
- Mount handling precedent: `ElNameBoardBlock.MOUNT` (standing/wall/hanging
  from clicked face) and `ElExitSignBlock` (MOUNT + right-click cycling).

---

## 3. Design

### 3.1 The data model — a sign is a list of rows, a row is a list of tiles

```
Sign {
  style: BLACK | WHITE_BAND_1970          // panel colour scheme
  rows: [ Row { tiles: [Tile...] } ]     // 1-3 rows, each row = one module height
}
Tile (sealed):
  BULLETS   { routes: [routeName...], express: bool per route?, auto: bool }
            auto=true → routes serving the platform(s) this sign is assigned to
  TEXT      { lines: [String, String?], align, size: NORMAL|SMALL }
            text may contain inline tokens: {b:LINE} {d:LINE} {wc} {<}{>}{^}{v}
            and DYNAMIC tokens: {station} {exit:A} {to:LINE} {next:LINE}
  ARROW     { dir: 8 directions, side: LEFT|RIGHT }
  EXIT_LETTER { letter from MTR station exits, or literal }
  ICON      { mtrSignId }                 // any MTR sign resource (our JSON pictograms included)
  STATION_NAME { }                        // dynamic: findStation, custom override
  PLATFORM_ROUTES { platformIds }         // dynamic: bullets for a platform, like PlatformPicker
  SPACER    { width }
```

Persisted as JSON in the BE (`ServicePoster` codec pattern), synced by our own
C2S packet with the usual guards. Everything MTR-derived is stored as **names /
ids**, resolved at draw time (the RouteBullets rule) so renames and repaints
follow.

### 3.2 Layout — `SignLayout` on `PosterLayout.Surface`

- Canvas units: 64 per block, module height = 1 row. Proposed panel heights:
  1 row = 8 px (half block) of panel, 2 rows = 16 px. Width = the merged
  run of blocks (see 3.3); content is laid out left→right: arrows LEFT-side
  tiles pinned to the left edge, RIGHT-side arrows to the right edge, bullets
  then text fill the middle, `textFitted` shrinks, 2-line text wraps.
- Returns `Metrics{tileBounds}` so the editor preview supports click-to-select
  exactly like the poster editor.
- Both surfaces exist already; `WorldSurface` wraps `CanvasPainter`; icon
  tiles need a sixth `Surface` method `icon(mtrSignId, x, y, size)` (GUI →
  `drawSign(null,...)` GUI-space path; world → `drawSign(stored matrices,...)`).
- Font: see §7 Q2. Default plan = ship a Helvetica-alike TTF (Liberation Sans
  Bold / Nimbus Sans — check licence) as `station_announcer:sign`, selected via
  `Style.withFont`; `CanvasPainter` gains a font parameter so nothing else
  changes (all measurement already goes through `painter.width`). Fallback:
  `mtr:mtr`.

### 3.3 Blocks (all DECORATION tab, MTR module, `StationDecorBlockEntity`-style BE but its own class `SignBlockEntity`)

| id | mount | notes |
|---|---|---|
| `mta_sign` | HANGING / WALL / STANDING via MOUNT from clicked face (ElNameBoard precedent) | 1 block wide, ROWS 1-2 blockstate (panel 8 / 16 px tall). Same-facing neighbours along the run **merge** into one panel (railing-sign merge rules: first segment with data wins, only the origin draws, `rendersOutsideBoundingBox`). Hanging: ceiling stub matches `pids_pole` (7..9) so drop poles stack. |
| `mta_sign_column` | wraps a column | 4-face plate around a `column_iron*` or a full block; station name auto; the "E" type. Could be a MOUNT of the same block if the model allows. |
| `mta_station_plate` | wall | optional: the enamel white/black-band 1970 station name plate — or just `mta_sign` with `style=WHITE_BAND_1970` and a STATION_NAME tile. **Prefer the latter; no extra block.** |

Placement conventions: same as PIDS/el boards — FACING = wall behind for wall
mount; hanging panel double-sided (both faces draw the same sign, or a
per-face sign in v2); standing = on a post.

### 3.4 Editor — `SignEditScreen` on FlatUi

Poster editor layout: STRUCTURE (rows → tiles, ↑↓×, "+ Add tile" menu) ·
PREVIEW (live, click-to-select) · INSPECTOR (tile fields: RoutePicker for
bullets, TextBox for text with Insert toolbar incl. dynamic tokens, 3x3 arrow
pad, MTR icon palette from `CustomResourceLoader.getSortedSignIds()`,
PlatformPicker for PLATFORM_ROUTES). Ctrl+S/Esc/Tab as the poster editor.

**Templates** (the "New sign" menu — the fixed MTA vocabulary as one click):
Uptown & The Bronx / Downtown & Brooklyn / Manhattan / Queens (bullets auto +
text + arrow), Exit (+ street), Exit letter, Transfer to …, Station name,
Blank. Wording is editable afterwards.

Brush right-click opens it (GUI_OPENER chain). Non-brush right-click: cycle
nothing (keep it simple) — or flip the arrow side? Decide at build time.

### 3.5 MTR piggyback deliverables (no Java)

`assets/mtr/mtr_custom_resources.json` in our jar with a `signs` array:
NYC pictograms (wheelchair, elevator, escalator up/down, MetroCard, OMNY tap,
police, no entry, no smoking, emergency exit green, "Exit", "Uptown",
"Downtown", "Subway", "Transfer") in the black-panel style, 32 px textures
generated by `tools/gen_sign_assets.py`. They show up in MTR's own railway
sign screen AND in our ICON tile palette. Verify on 4.0.1 that the merged-
resources loader exists there too (the survey flagged 4.0.5 drift in lifts/
objects, not signs).

---

## 4. Phases

1. **Pictogram JSON pack** (½ day): generator + `mtr_custom_resources.json`
   + textures. Verified by opening MTR's railway sign screen on the rig. Ships
   independently and de-risks the MTR loader on 4.0.1.
2. **Font** (½ day): pick + licence check, font JSON, `CanvasPainter` font
   parameter, one render test on the rig (sizes 6-13 units, mip readability).
3. **Data model + layout + world renderer** (1-2 days): `Sign` record + JSON
   codec, `SignLayout` on `Surface`, `mta_sign` block (MOUNT, ROWS, merge run,
   models/blockstates via generator), `SignBlockEntity`, S2C sync. Verified on
   the rig via `/data merge` of a JSON sign (keep payloads < 256 chars, or add
   a `#sign-load <file>` dev hook like `#poster-editor`).
4. **Editor** (1-2 days): `SignEditScreen` on FlatUi + templates + C2S packet.
   Headless-untestable clicks → Thomas play-tests.
5. **Dynamic tiles** (1 day): STATION_NAME, PLATFORM_ROUTES (auto bullets),
   EXIT_LETTER from `Station` exits, `{to:LINE}` terminus text from
   simplifiedRoutes, `{next:LINE}` countdown via `MtrDataCache.arrivals`
   (this is the "dynamic text" the request asks for; each is a small
   resolver in `SignLayout`).
6. **Column wrap + 1970 white-band style + polish** (1 day): column plate
   model, style toggle, luminance while lit, item icons, README section.

Total ≈ 5-7 working days of agent time, gated by Thomas's play passes after 1,
3 and 4.

---

## 5. Rules carried over (do not re-learn)
- Assets GENERATED (`tools/gen_sign_assets.py`), verify() for when-keys/uv.
- Two quads in the same debug-quad layer need ≥ 0.4 canvas units apart.
- Never hold a `VertexConsumer` across a text draw (boxes pass, then text pass).
- Store MTR names/ids, resolve colours at draw time. NO_ENTRY sentinel reuse.
- Screens sized for ~426x266 logical px; no vanilla widgets in the editor.
- Deploy only via `tools/deploy_jar.sh`; live loop = model/texture → copy into
  build/resources/main + F3+T; Java → wait for Thomas's "restart".
- Sodium: no Fabric renderer API — geometry differences go in blockstates.

---

## 6. Risks
- **Font licence / look**: a TTF via vanilla's `ttf` provider renders through
  the font atlas at one oversample; small sign text (size 6-8 units) may blur.
  Test before committing; fallback is the tile-tablet style metric hacks on
  the vanilla font.
- **MTR icon drawing inside our canvas**: `drawSign` wants MTR's
  `StoredMatrixTransformations`/`GraphicsHolder`; bridging from our
  `MatrixStack` may be awkward. Fallback: draw the icon texture ourselves as a
  textured quad (we know the Identifier from `SignResource`).
- **Merged-run + hanging double-sided + MOUNT** is the biggest blockstate/
  renderer surface; keep v1 to one sign per run drawn on both faces.
- Scope creep: the tile vocabulary above is already 8 kinds; ship 5 in v1
  (BULLETS, TEXT, ARROW, ICON, STATION_NAME) and add the rest in phase 5.

---

## 7. Open questions for Thomas
1. **"Sign tiles"** — did you mean (a) modular content tiles inside one panel
   (this plan's design), (b) MTR-style per-block sign slots you place one by
   one, or (c) mosaic/ceramic tile signs like the tablet? The plan assumes (a)
   with icons also exposed to MTR's own per-slot signs.
2. **Font**: ship a Helvetica-alike TTF (best look, new asset, licence check),
   reuse MTR's Noto Sans (`mtr:mtr`, free), or stay on the vanilla font with
   metric tricks (cheapest, least authentic)?
3. **Sign size**: one row = half a block tall (8 px, current el/PIDS scale) or a
   full block? Real hanging signs are ~1 ft tall on a ~8 ft clearance.
4. **Which types first**: directional hanging/wall (B) + exit (C) + station
   plates (A)? Column wrap (E) and 1970 white-band style deferred?
5. **Editor scope**: full poster-style editor from day one, or templates-only
   (pick a template, fill text/routes/arrow) first?
6. Should the existing `el_sign`/`el_wall_sign`/`entrance_railing_sign` migrate
   onto `SignLayout` later (one renderer for all text signs), or stay as is?


---

## 8. Build log

- 2026-09-06: `mtr/sign/SignSpec` + `SignFaces` (data model, JSON, packet, Draft);
  `client/mtr/SignLayout` (rows/tiles layout on `PosterLayout.Surface`, MTR font
  via `CanvasPainter.withFont` and the surfaces' new font parameter), `SignContext`
  (station name / exits / lines / route destination, 1 s cache), `MtaSignPainter`
  (run merging, double-sided), `SignEditScreen` + `SignTemplates` (FlatUi editor),
  `mtr/MtaSignBlock` (`mta_sign`, `mta_sign_half`; MOUNT wall/hanging/standing,
  LEFT/RIGHT merge), `tools/gen_sign_assets.py`, `update_sign` C2S, `Sign` JSON on
  `StationDecorBlockEntity`, dev hooks `#sign-editor x y z` / `#sign-load x y z
  <file>` / `#sign-close`. ICON tiles deferred (need an MTR drawSign bridge).
- 2026-09-07: rig rounds 1-3 (screenshots in the session scratchpad). Fixed from
  Thomas's first look: text sat high (font metrics recalibrated to 0.76 cap per
  size, cap line at draw y), the white stripe under the top edge, Exit on a red
  field. Row fitting is now condense → wrap to two lines → condense → trim.
  Legacy signs (entrance railing, el entrance, el boards, el wall/railing sign
  courses) migrated: `LegacySigns` derives a sign from the old fields; brush →
  `SignEditScreen`; `RailingSignScreen` deleted. Shipped in 2.4.57 (deployed to both play profiles 2026-09-07; the version had moved on under a parallel Map+ session).
