# El item set 3 — WALLS & SCREENS rework

Windscreens, railings and the station-house walls/windows/soffit, reworked
against the photo research in `EL_STATION_PLAN.md`. Everything is generated:
`python3 tools/gen_el_assets.py` (which calls `tools/gen_el_phase3.py`) —
never hand-edit the textures/models/blockstates. Renders that drove the pass
are in `el_set3_renders/`; the renderer that made them is described at the
end. **Nothing here has been seen in game.**

## 1. What changed

### Textures

| file | before | now |
|---|---|---|
| `el_windscreen` (32 px) | 3 zones, batten = a 1-px line, glazing with a mullion every 4 px | re-zoned **panel rows 0..16 / glass 16..28 / green frame 28..32**; boards are 4 px wide with a 1-px batten and the **hard shadow it casts** (that shadow, not the batten's own colour, is what makes it read proud); glass gets 8-texel wire diamonds, glazing beads, a glare streak and **two panes per block**; the frame zone is real stock now — the transom rail is cut from it, bolt heads included |
| `el_corrugated` | `lit → base → dark → shadow` then a jump back to `lit` — a **sawtooth**, which reads as painted stripes | a true **wave** per 2-px flute: crest, falling flank, trough, rising flank, one dulled crest per 8 px as weathering |
| `el_glass` | **rendered 100 % invisible** — every pixel was alpha 60–90 and the cutout layer discards `a < 0.5`, so only the frame posts ever showed | fully opaque pane border (the vanilla-glass trick, which doubles as the pane division of a glazed screen) + vertical sheen columns; field empty. **Vertical**, not diagonal: both faces of a pane draw, and through the holes you see the far face mirrored, so a diagonal streak paints an X on every pane |
| `el_mesh` | 16 px, 1-px straps on a 4-px period (= 24 cm diamonds) | 32 px, same 4-texel period = **12 cm diamonds**, two strand tones with the darker one broken at each crossing so the weave reads over/under |
| `el_house_green` / `_cream` | batten = 2 flat lit columns + 1 shadow, board 5 | batten 1 px with a hard cast shadow and a wide, near-flat board that brightens back toward the next batten, plus board-to-board grain |
| `el_glazing` (window) | 16 px, 8-px wire | 32 px, 4-px wire in two tones + glare runs; still binary alpha |
| `el_soffit` | 16 px, a line every 4 px | 32 px **rounded beads** (groove / rise / highlight / fall), 12 cm boards |

### Models

**The z-scheme is now explicit and shared** (`POST_Z`/`CAP_Z`/`TRANSOM_Z`/
`PANEL_Z`/`GLAZE_Z`/`PANE_Z` at the top of the platform section). The rule:
*posts are the outermost thing on a screen and nothing else may reach their
planes.* Panel inside the posts, glazing inside the panel plane, rail and
kick 0.05 inside the posts so a post's open top and foot are buried under
them at a stack's ends while no two faces are ever coplanar.

- **The classic windscreen is now two models picked by `UP`.** `up=true`
  (any course below the top) draws `el_windscreen_panel_base`, a solid beige
  panel; `up=false` (the top course, and the whole of a 1-high screen) draws
  `el_windscreen_panel` — beige apron, proud green transom rail, glazed band.
  A 2-high run is therefore solid to 1.46 m and glazed above, which is how
  Marcy / Bay Pkwy are built. Before, every block drew beige-then-glass, so a
  2-high screen had a glass stripe through its middle.
- **The stacked joint is seamless.** The head's apron shares the base panel's
  plane *exactly* (both `PANEL_Z`), so the two boxes read as one sheet with
  no step and no open ends. Previously the panel's own two elements were
  7.3..8.7 and 7.5..8.5 — a 0.2 step whose ledge had no `up` face, i.e. a
  see-through sliver at every glazing line and at every stack boundary.
- Rail and kick moved from 7.0..9.0 / 7.05..8.95 to `CAP_Z` = 6.95..9.05, so
  they cover the posts' open ends to within 0.05 px; the rail also gained a
  proper lid slice instead of stretching the rail slice over its top face.
- Corrugated / glass / mesh panels re-centred on the shared planes so every
  screen type stacks flush and every panel's ends bury inside the same posts.
- **Both railings read ROUND now** — `round_bar_x` / `round_post_y` build a
  core box plus a 45°-rotated twin about the run axis (the turnstile-tubing
  trick); the twin is inset 0.08 along the run so its end caps can never be
  coplanar with the core's. Pipe railing: two round rails, a round post
  slimmer than the rails (so its top buries inside the top rail rather than
  sharing a plane with it) and an octagonal foot flange with `cullface: down`.
  Modern railing: round top rail, flat bottom rail, and a **heavier post at
  the block centre** — one post per metre — replacing the two centre pickets.
  Pickets stopped sampling the steel sheet's vertical rivet ladder (which
  stippled every picket); they take a clean-band slice now.
- **The house window is a real double-hung.** The wall still gives the
  opening its sill / lintel / jambs, but inside the reveal there is now a
  SASH — two stiles, a head rail, a bottom rail and the **meeting rail**
  across the middle — with the glass behind it in two lights. Depth:
  reveal 0..16 → sash 6.5..9.5 → glass 7.4..8.6, so every glass edge is
  buried inside the sash and every sash end inside the reveal. Before it was
  one bare pane in a hole.

### Blockstate / Java

- `platform_screen_blockstate` takes an optional `panel_base`; the classic
  windscreen's file is 24 parts (was 20) — `up=false → head`,
  `up=true → base`, plus the unchanged post/rail/kick parts.
- `ElScreenBlock.getPlacementState` now runs the facing through
  `planeFacing()`: it keeps the plane the player picked and flips only the
  **invisible 180°** when a screen of the same kind already stands in that
  plane on the run axis or directly above/below. Rationale in §3.
- `PROPS` for the two railings gained `up`/`down` (the Java block has always
  declared them; the table was just out of date).

## 2. Verification

- `python3 tools/gen_el_assets.py` → `verify: el blockstates + models OK`
  (every when-key against the Java properties, every uv on-sprite, every
  rotation ±45/±22.5).
- **Coplanar audit** (`audit_set3.py`, scratchpad): enumerates every face of
  every model that can render in the same cell *plus* the next cell along the
  run and the course above, and reports same-direction coplanar pairs with
  overlapping footprints. **0 hits** for all four screens, both railings, the
  window alone and a window-under-wall stack, and a soffit run.
- All 8 rewritten PNGs validated by IDAT length against `h*(1+w*bpp)`.
- Renders in `el_set3_renders/`: 4-long × 2-high runs of all four screens
  with posts/rail/kick, both railings, a 3×2 house wall with windows under a
  soffit, a window close-up, a stack-joint close-up and a texture sheet.
- **Not verified:** anything in game — placement flow, cutout layers, the
  tint providers, collision feel, and how the classic screen's `UP` split
  behaves when a stack is built top-down.

## 3. Placement rule, per block

`FACING` is the player's look direction **reversed**
(`FacingDecorBlock.getPlacementState`), so:

> **The panel plane always stands ACROSS your view.** You face where the
> screen should go and it runs left-to-right in front of you. The run
> extends along `FACING.rotateYClockwise()`.

That axis is the x axis of the authored NORTH frame, and the blockstate
rotates it y = 0/90/180/270 for north/east/south/west. Those rotations map
`(x,z) → (16−z, x)` per 90°, which is exactly the map
`FacingDecorBlock.rotateClockwise` applies to the outline shape — model and
shape can therefore never disagree. Confirmed by reading both.

| block | FACING means | run / stack |
|---|---|---|
| `el_windscreen`, `_corrugated`, `_glass`, `_mesh` | the panel's normal (both faces identical) | joins `LEFT`/`RIGHT` along `FACING.rotateYClockwise()` and `UP`/`DOWN` vertically, all requiring same block + same facing. Shared post: LEFT post always, RIGHT post only at a run end. Rail at `up=false`, kick at `down=false`; classic also swaps its panel on `UP` |
| `el_railing_pipe`, `el_railing_modern` | the railing's normal | blockstate keys on facing alone — a post every block IS the look, so there is nothing to share at a joint. `LEFT`/`RIGHT`/`UP`/`DOWN` are computed but unused |
| `el_house_wall_*`, `el_soffit` | no facing at all (single variant, full cube / top slab) | — |

**The 180° adoption.** A panel is symmetric about its own plane, so north and
south look identical — but they are different states and `joins()` demands an
exact match, so a run built while walking down one side and then the other
refused to merge and closed *both* segments with their own posts (visibly
doubled at the joint). Since the two facings are visually indistinguishable,
placement now adopts a neighbouring run's facing when that neighbour is the
reverse of ours. This is deliberately **not** the door rule (Thomas: the doors
"ignored" him): a door's facing decides which way its signs read, so the look
must win; a windscreen's facing decides nothing a player can see, so the only
observable effect of adopting it is that the run merges. The plane the player
chose is always honoured — only the invisible flip is taken.

## 4. Sizes (1 block = 1 m)

| thing | model | real | verdict |
|---|---|---|---|
| screen, 2 high | 2.00 m | NYC windscreens 2.1–2.4 m | right at 2 high; 3 high (3 m) is too tall |
| classic split, 2-high run | solid to 1.46 m, transom 1.46–1.55 m, glass 1.55–2.00 m | solid to chest, glazed above | matches the photos |
| kick plate | 1.3 px = 8 cm | 10–15 cm | fine |
| screen top rail | 14.5–16 px of the top course | capping channel | fine |
| pipe railing, top rail centre | 14.3 px = **0.89 m** | 1.07 m (42″) NYC standard | capped by the block: `EL_RAILING_SHAPE` is only 15 px tall, and a 1-block block cannot hold 1.07 m. 0.89 m clears the 0.90 m code minimum by a hair — see proposal P1 |
| pipe railing, mid rail | 0.47 m | ~half height | correct |
| picket gaps | 6.9 cm (0.9 px pickets on a 2 px pitch) | ≤ 10 cm | correct |
| house window opening | 0.69 × 0.62 m, glass 0.28–0.78 m above its own course floor | sash ~0.9 × 1.5 m, sill ~0.9 m | **put window blocks in the SECOND course** of a wall — the glass then sits 1.28–1.78 m, i.e. eye height. A window in the ground course has a 22 cm sill |
| soffit slab | 3 px = 19 cm | dropped beadboard ceiling | fine |

## 5. Proposals (need new ids / files outside this set's scope)

- **P1 — raise `EL_RAILING_SHAPE`** (`MtrStationDecor`, not touched here)
  from 15.0 to 15.4 px. The round top rail's 45° twin reaches 15.15, so the
  outline currently clips 0.15 px under the rail; and it would let the rail
  itself go to ~0.95 m.
- **P2 — a green board-and-batten windscreen block.** `EL_STATION_PLAN.md`
  lists five windscreen materials from the photos; only four exist
  (classic / corrugated / glass / mesh). The house wall's texture and the
  screen frame are already the right green — a fifth `el_windscreen_board`
  would be a texture swap on the existing panel geometry.
- **P3 — railing end caps.** `ElScreenBlock` computes `LEFT`/`RIGHT` for the
  railings and the blockstate ignores them. Two extra models (an end post
  with rail caps) at `left=false` / `right=false` would close run ends, which
  currently show the open pipe ends.
- **P4 — a windscreen return/corner.** A run that turns a corner today shows
  two closing posts meeting at 90°. A `bend` property like `GateWallBlock`'s
  would give it a proper corner post.
- **P5 — a taller-glass variant of the classic head**, for 3-high screens
  under a canopy: `up=false, down=true, and the block below also down=true`
  is not expressible, so a 3-high run repeats the solid panel twice. A
  `GLAZED` boolean set at placement would let a builder choose.

## 6. The renderer

`render_el_set3.py` (scratchpad) is a texture-sampling version of
`render_blockmodel.py`: it parses the block-model JSON, projects
isometrically, and **samples the real generated texture through each face's
uv** (affine inverse over the parallelogram), discarding texels with
`a < 128` so cutout holes are visible. Textures come straight from the
generator functions — no PNG decoding, so it can never disagree with what
shipped. Two things it cost to learn: in this projection a **larger depth is
NEARER** (the camera views the south side), so a z-buffer must keep the
largest value — with the test inverted, proud geometry like the frame posts
silently vanished behind the panel it stands in front of. Worth recreating
for the next model family; `audit_set3.py` beside it is the coplanar check.
