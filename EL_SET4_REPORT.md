# El kit — ITEM SET 4 (STREET & ENTRANCE) rework

Reworked 2026-08-28 against the Prospect Av street-entrance reference in
`EL_STATION_PLAN.md` (teal stair flight with panelled/mesh sides in a distinct
frame, red standing-seam canopy sloping down the stairs, ornamental filigree
portal with the station sign on it, globe/goose-neck lamps).

Blocks touched: `el_stair_side`, `el_stair_canopy`, `el_portal_post`,
`el_portal_header`, `el_lamp_gooseneck`, `el_lamp_post`, `el_lamp_head`.
All assets are GENERATED — `python3 tools/gen_el_assets.py` (green, verify OK).
**No Java was changed and no block id was added.** Offline renders are in
`el_set4_renders/` (`render_set4.py`, `render_junction.py`,
`check_coplanar.py` — all re-runnable, no game or Gradle needed).

---

## 1. The 45° slope frame — the arithmetic, written down

This is the part that was wrong before and is now documented at the top of
`tools/gen_el_phase3.py`. `SLOPE` (+45 about x through (8,8,8), rescale) maps
an authored point to

```
world y = y_a − z_a + 8        world z = y_a + z_a − 8
```

Two coordinates fall out of it:

* **u = z_a − 8** — position ALONG the slope, *independent of y_a*. Each block
  covers u ∈ [−8, 8]; the neighbour one-up/one-−z is exactly Δu = −16. So any
  element spanning authored z 0..16 tiles a flight of any length with no gap
  and no overlap, at ANY authored y. **Chaining verified** in
  `set4_elev.png` / `set4_canopy_elev.png` over a 4-block flight.
* **v = y_a** — perpendicular height. Measured VERTICALLY at a fixed world z a
  point sits at `y = 2·v − z`, so **one authored y unit is two pixels of
  apparent height above the stairs**. This is what the old model got wrong.

Registered against `subway_stairs` (two 8 px steps, same ascent rule, read out
of `tools/gen_stair_assets.py`):

| line | authored v | world |
|---|---|---|
| stair soffit (re-entrant corners) | 8 | y = 16 − z |
| stair **nosing** (walking surface) | 12 | y = 24 − z |

so anything on the slope sits `2·(v − 12)` px above the treads.

**The old `el_stair_side` centred its panel on v = 8** (the soffit), reaching
only v 13.6 — i.e. its top rail was ~3 px above the tread line and its kick
dangled ~15 px below the stairs. From the side it was an ankle-high slab with a
huge apron (`git show HEAD -- …el_stair_side_model.json` for the before).

---

## 2. `el_stair_side` — rebuilt as a framed screen

| member | v range | reads as |
|---|---|---|
| stringer | 5.6 – 8.4 | beam under the steps, top ON the soffit line, 12.8 px (0.8 m) of it below the nosings |
| screen panel | 8.4 – 18.0 | corrugated sheet, x 6.9–9.1 |
| mid rail | 12.4 – 13.4 | proud rail right at tread level |
| handrail cap | 18.0 – 19.5 | **15 px ≈ 0.94 m above the nosings** (code handrail height) |
| pilaster | 6.0 – 19.2 | one per block, mid-block, 0.3 proud of the panel both faces |

Everything but the pilaster spans authored z 0..16, so the run chains. The
panel butts the stringer's lid and the rail's soffit exactly and carries no
up/down face of its own — those two members' lids are the only quads in those
planes, so there is nothing coplanar and nothing gaps. Run-end caps were added
to stringer / panel / both rails: an authored z=0 face lands exactly on the
neighbour's z=16 face with the opposite normal, i.e. buried between two solids
inside a run, so a flight now closes at top and bottom instead of showing a
hollow slot. (The mid rail deliberately has no cap — its own would have
overlapped the panel's in that plane.)

Still inside `EL_SLOPE_WALL_SHAPE` (x 5.9–10.1) in x, so the declared outline
and collision are unchanged. The model now reaches ~12.5 px above its own cell
(the parapet), the way `entrance_railing` does — see §7.

## 3. `el_stair_canopy` — standing seam, finished underside, closed joint

* **plane** v 7.2–8.8 — red seam sheet over the green corrugated soffit.
* **standing seams** v 8.8–10.0, running DOWN the slope: one straddling each x
  boundary plus a mid seam (a 0.5 m pitch). The old model put an edge strip at
  x 0 *and* x 14.8 of every block, so a 2+-wide roof grew a doubled ridge at
  every joint and read as separate strips.
* **rafter** x 0–1.6, v 5.4–7.2 — under-rib at the x boundary.
* **cross purlin / foot eave** z 14.9–16, v 5.6–7.2 — a purlin at every joint
  down the flight AND, on the last block, the fascia at the foot of the stairs.
  Rafters + purlins give the coffered underside the street sees
  (`set4_canopy_under.png`).
* **up-slope flashing tongue** z −2..0, inset 0.2 in v and x. Inside a run it is
  buried in the next block's plane; at the top of the flight it pokes 2 px
  up-slope and **tucks under an `el_canopy_flat` placed one up / one −z**,
  closing the wedge that used to be an open hole
  (`set4_canopy_junction.png`, `set4_canopy_junction_elev.png`).
* Rake and end faces are now drawn (coincident with the neighbour's, opposite
  normals) so the roof no longer shows a 1.6 px hollow slot along its edges —
  visible in the before/after of `set4_canopy_elev.png`.

## 4. `el_portal_post` / `el_portal_header`

* **Shaft 2.8 px → 3.6 px** — deliberately beefier than the 2.8 px
  `el_canopy_post`, and it is *exactly* the footprint `EL_POST_SHAPE`
  (6.2–9.8) already declared; the model was thinner than its own outline.
  Four proud corner beads give the cast-iron arris.
* **New `el_portal_post_foot`** on `down=false` (the property already existed,
  only the bracket used it): a two-step street plinth. Wired as a multipart
  part, item model includes it.
* **Bracket rebuilt as delicate lace**: collar, then a PAIR of brackets — a
  1.2 px 45° knee brace (rotated about z, authored 8.8 long because a 45° turn
  without rescale projects to 6.2 px = post face → arm end), a slim top arm
  under the header, and a cutout lattice spandrel in the corner. The old one
  was a 2.2 × 1.8 × 9 chunk plus a lattice square.
* **Header**, inside its declared 5.4–15.6 × z 6.8–9.2 envelope:
  cornice 13.9–15.6 (widest) / frieze 11.2–13.9 / lattice band 6.6–11.4 /
  chord 5.4–6.8. End caps added so a run no longer shows hollow ends.
* **`el_name_board` relationship (checked, and it is not what the brief
  assumed):** the board is a *standing* sign — its plate is y 6–13 with two
  legs at y 0–6 BELOW it (built for the windscreen top rail, per the plan). So
  it goes in the cell **ABOVE** the header and stands on the cornice; the
  cornice at 15.6 leaves a 0.4 px (2.5 cm) shadow gap under the legs. Hanging
  it below the header instead would leave the two legs dangling in mid-air.
  The header's bottom chord is the steel a future *hanging* board variant
  should meet — see §7.

## 5. Lamps — parameterized paint + cone shades

Both builders now take a paint ref (`body` green / `galv` silver) and
`_cone_shade()` is shared: three rings widening downward, each overlapping
0.2 up into the one above so no two lids are coplanar, with the lit
`shade:false` bulb buried in the rim.

* **`el_lamp_gooseneck`** — ceiling flange, stem, a real 45° elbow (leaves the
  stem at v 13.4/z 8.0, arrives at the arm centre), the horizontal reach, then
  the cone. Fixture depth 9.8 px = **0.61 m below the ceiling**. Inside
  `EL_GOOSENECK_SHAPE`.
* **`el_lamp_head`** — shaft stub, moulded collar, a −45 elbow rising out of it
  (−45 about x ascends toward +z), horizontal arm, cone. Inside
  `EL_LAMP_HEAD_SHAPE` (top 8.8 ≤ 9).
* **`el_lamp_post`** — the base plate became a slim 0.8 px moulded ring: the
  block has no UP/DOWN state, so whatever is on it repeats on every block of a
  stack (the globe-pole lesson); a ring reads as a cast joint, a 1.4 px base
  plate read as banding.
* **Silver models written** (unreferenced by any blockstate for now, which is
  fine — `verify()` only walks models a blockstate names):
  `el_lamp_gooseneck_model_silver`, `el_lamp_post_pole_silver`,
  `el_lamp_post_head_silver`, `el_lamp_post_item_silver`.

---

## 6. Orientation audit — the rules, per block

`FacingDecorBlock.getPlacementState` sets `FACING = playerFacing.getOpposite()`;
`ElSlopeBlock` overrides it to `FACING = getHorizontalPlayerFacing()` (the look
direction). The blockstates map facing north→y 0, east→90, south→180, west→270,
i.e. **the authored model is the facing=north variant**, and a y rotation sends
a north-pointing feature east.

| block | authored | rule for the builder | verdict |
|---|---|---|---|
| `el_stair_side`, `el_stair_canopy` | ascends toward −z (north) | FACING = ascent = **your look direction**; identical to `SubwayStairBlock`, so the screen/canopy and the stairs are placed looking the same way | **correct — no Java change** |
| `el_portal_post` | bracket PAIR along ±x | FACING = look.getOpposite(), so the bracket axis is always **perpendicular to your look**: stand facing the portal and it spans left–right | changed to a symmetric pair (was one arm toward +z) |
| `el_portal_header` | run spans x | same: run axis is perpendicular to your look | unchanged, now **consistent with the post** |
| `el_lamp_gooseneck`, `el_lamp_head` | arm toward +z | the arm reaches **away from you, in your look direction** | unchanged, sensible |
| `el_lamp_post` | symmetric | facing irrelevant | fine |

The old post bracket was a single arm along +z while the header runs along x,
so the two blocks needed look directions 90° apart — placing a portal meant
facing one way for the header and another for each post. The pair also matches
`el_canopy_post`, which already draws both brackets and lets FACING pick the
axis.

## 7. Sizes (1 block = 1 m)

| thing | value | photo target |
|---|---|---|
| portal clear opening (2-post stack + header chord at y 5.4) | **2.34 m** | 2.2–2.5 m ✓ |
| portal post section | 3.6 px = 22.5 cm (canopy post 17.5 cm) | ✓ |
| stair handrail above nosings | 15 px = 0.94 m | 0.86–0.96 m ✓ |
| stringer below nosings | 12.8 px = 0.8 m | ✓ |
| stair canopy: place it **3 blocks above** the matching stair block | 2.40 m headroom over the nosings (2.20 m to the eave purlin) | ✓ (2 blocks gives 1.2 m — too low) |
| lamppost, 2 poles + head | 2.5 m (platform) | ✓ |
| lamppost, 3 poles + head | 3.5 m (street) | ✓ |
| gooseneck drop below the ceiling | 0.61 m | ✓ |

**Placement cheat-sheet**

```
stairs           subway_stairs at level L, looking up the flight
side screens     el_stair_side either side, SAME level L, same look
stair canopy     el_stair_canopy at level L+3, same look
top of flight    el_canopy_flat one up / one further along the ascent from the
                 last stair-canopy block — the flashing tongue tucks under it
portal           2-high el_portal_post stacks + el_portal_header over them,
                 all placed standing in front of the portal
station sign     el_name_board in the cell ABOVE a header (it stands on it)
```

## 8. Silver lamp variants — registration proposal (Java, for you to wire)

Models already exist; this is all that is left.

1. `MtrStationDecor` — three blocks beside the greens:
   ```java
   public static final FacingDecorBlock EL_LAMP_GOOSENECK_SILVER =
           new FacingDecorBlock(lampSettings(), EL_GOOSENECK_SHAPE);
   public static final FacingDecorBlock EL_LAMP_POST_SILVER = new FacingDecorBlock(
           settings(), Block.createCuboidShape(5.6, 0.0, 5.6, 10.4, 16.0, 10.4));
   public static final FacingDecorBlock EL_LAMP_HEAD_SILVER =
           new FacingDecorBlock(lampSettings(), EL_LAMP_HEAD_SHAPE);
   ```
   plus `registerBlock("el_lamp_gooseneck_silver", …, ModContent.DECORATION_ENTRIES)`
   for each (DECORATION, next to the greens). No cutout and no tint provider —
   the shades sample the opaque `el_steel_silver` sheet.
2. `tools/gen_el_phase3.py` — add the three ids to `BLOCKS3` (loot) and to
   `PROPS3` (`{"facing": FACINGS4}` each); in `build_final` add
   `("el_lamp_gooseneck_silver", "el_lamp_gooseneck_model_silver")`,
   `("el_lamp_post_silver", "el_lamp_post_pole_silver")`,
   `("el_lamp_head_silver", "el_lamp_post_head_silver")` to the
   `facing_variants` loop and the item-model dict (post item →
   `el_lamp_post_item_silver`); `RECIPES3` gets the green↔silver shapeless
   conversions the rest of the family uses (silver + green_dye → green).
3. `en_us.json` — three lang keys.

## 9. Known limits / follow-ups (deliberately not done here)

* **`ElSlopeBlock` has no end detection.** A true drip fascia at the foot of a
  canopy run, or a newel cap on the screens, needs a `TOP`/`BOTTOM` boolean
  computed diagonally the way `SubwayStairBlock.refreshDiagonals` does. The
  buried-overshoot tricks above (tongue, boundary purlin) are what is possible
  without it. Ascent convention itself is correct, so `ElSlopeBlock.java` was
  left untouched.
* **`el_stair_side`'s outline** is still `EL_SLOPE_WALL_SHAPE` (a full-height
  thin wall, x 5.9–10.1). Correct for collision — it stops you walking off the
  side — but the parapet now stands ~12.5 px above the cell, so the selection
  box under-reports it. `entrance_railing` solves the same thing by taking the
  shape to y 24; worth doing if it bothers you in game.
* **`el_lamp_post`** would want UP/DOWN (i.e. `ColumnBlock`) so a stack could
  grow a real street base at the bottom instead of a repeating ring.
* **`el_name_board`** is a standing sign; a hanging variant (plate with straps
  above it) would let it hang under the header chord. That block is owned by
  `gen_el_assets.build_platform`, so it was out of scope here.
* Nothing is in-game tested — offline renders + a coplanarity audit only.
  `el_set4_renders/check_coplanar.py` reports **0** same-direction coplanar
  overlaps across all nine set-4 models.
