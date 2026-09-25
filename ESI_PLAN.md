# ESI theme for the el station kit (2026-09-25)

Thomas: "next on the list is to have the same structure of blocks, but with the
ESI theme of modern stations". ESI = the MTA's **Enhanced Station Initiative**
(2017-2020). The elevated ESI stations are the Astoria line (N/W): 30 Av,
36 Av, 39 Av-Dutch Kills, Broadway, and Astoria Blvd (2020 ADA rebuild).

## What the photos show (research 2026-09-25, Wikimedia Commons sets + press)

**Main finding: ESI kept the 1917 steel skeleton and re-skinned it.** The riveted
columns, the Warren lattice frieze and the gable canopy frame all stay. That is why
the ESI kit is the classic kit's STRUCTURE with a new skin.

- **Canopy:**
  - A very shallow gable over the centre of each platform, with the ends open.
  - Light silver-grey standing-seam roof (~#A9AEB2), seams running down-slope, and a flat grey fascia.
  - The lattice frieze is kept, painted charcoal (~#2B2D30).
  - Warm honey **wood-plank soffit** (~#B9844F), planks along the platform, with one continuous **linear LED** on the centreline.
- **Windscreens:**
  - Centre bays: full-height **clear glass** from a ~1 ft black kick curb up to the lattice, with dark mullions ~4-5 ft apart. Some bays are **solid black** (behind benches and the info "dashboards").
  - Open ends: ~8-9 ft galvanized **welded-wire mesh**, 1 x 3 in openings, black cap rail.
- **Lamps:** a light-grey square pole cranked into an **L**, with a flat rectangular LED head. They rise from the fence posts.
- **Stairs:**
  - Black stringers.
  - Grey treads with open grating risers.
  - Glass balustrades and stainless handrails.
  - Street entrance canopies: flat black boxes with LED panels underneath.
- **Mezzanine:**
  - Glossy black walls and satin metal panels.
  - Black ceiling with recessed linear LEDs.
  - Mid-grey large tiles in running bond.
  - Stainless fare control.
  - Laminated **art glass** windows (30 Av: Westfall's geometric panels; 36 Av, Broadway, 39 Av, Astoria Blvd each have their own artwork).
- **Variant:** 39 Av and Astoria Blvd kept **green** steel under the new grey roofs.
- **Not established:** fully new canopy structures anywhere; real wood vs wood-look soffit; glass frit; the station-house exterior cladding.

Sources:
- en.wikipedia.org pages for 30th Avenue, 36th Avenue, 39th Avenue (Astoria Line), Broadway (Astoria Line) and Astoria Boulevard stations.
- forgotten-ny.com/2018/07/new-astoria-line-stations/
- amny.com/nyc-transit/astoria-subway-stations-1-19356502/
- grimshaw.global Enhanced Station Initiative
- Wikimedia Commons "…after ESI renovations" categories

## How it is built

`tools/gen_esi_theme.py` is called at the end of `gen_el2_assets.py` and `gen_stair_assets.py`. Never hand-edit its output.

- **Blocks:** every `esi_*` block derives from a classic `el_*` block:
  - Its blockstate is copied.
  - Every model it uses is copied into `models/block/esi/` with textures remapped (`DEFAULT_MAP` plus per-block overrides).
  - Most ESI textures are the classic ones RECOLOURED about their base colour (rivets and seams survive). Wood, glass, mesh, art glass, black panel, LED and tile are drawn fresh.
- **Java:** `mtr/EsiKit` registers the ESI blocks as the SAME classes as their classic counterparts, so placement, connections and shapes are identical. They live on their own creative tab, "Baker City: Modern Stations (ESI)". Recipe: classic piece + gray dye.
- **ESI-only geometry:**
  - `ElStackWallBlock` (`COURSE` single/bottom/middle/top): the glass and mesh bays read as ONE panel when stacked. The black curb sits on the bottom course, the cap on the top one, with no rails between.
  - The L-arm LED lamp head.
- **Stairs:** subway stairs gained `esi_stringer / esi_railing / esi_wall / esi_wall_fill` in-cell sides. `extend_stairs` duplicates every classic in-cell part; re-runs are idempotent.

| classic | ESI | look |
|---|---|---|
| el_roof | esi_roof | silver standing seam, wood soffit, charcoal lattice |
| el_post | esi_post | charcoal |
| el_railing | esi_railing | charcoal pickets |
| el_wall | esi_wall | glossy black bay |
| el_wall_glass | esi_wall_glass | full-height clear glass, black curb (stacks seamlessly) |
| — (el_wall geometry) | esi_wall_mesh | welded-wire mesh fence (stacks seamlessly) |
| el_wall_green | esi_wall_steel | satin steel panel |
| el_wall_doorway | esi_wall_doorway | charcoal |
| el_wall_window | esi_wall_art_glass | laminated art glass sashes |
| el_platform_lamp (+head) | esi_platform_lamp (+head) | grey pole, L arm, flat LED head |
| el_roof_light | esi_roof_light | linear LED strip |
| el_stair_* (railing/wall/glass/open/upper/roof) | esi_stair_* | charcoal / black / clear glass, silver + wood roof |
| el_landing_roof | esi_landing_roof | flat black entrance canopy, LED bars underneath |
| el_stair_landing | esi_stair_landing | charcoal |
| el_street_column(_lattice), el_girder_plate, el_track_deck, el_plate_deck | esi_* | charcoal structure |
| el_mezzanine_floor | esi_mezzanine_floor | grey running-bond tile |
| el_ceiling(_light) | esi_ceiling(_light) | black with linear LED slots |

Signs (el_sign, entrance sign, wall/railing signs, named posts) are shared: they are
already black boards, which is also what ESI stations use.

## Not built yet (ESI signature pieces from the research)

The black info dashboard, the Help Point column, the street totem, the glass
elevator tower (Astoria Blvd), the wood-slat bench, and an optional "Astoria
Blvd" sub-palette (green steel under the silver roof).
