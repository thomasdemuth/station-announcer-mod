# MTR 4.0.1 Ticket System Research — improving the fare-gate family

Researched 2026-08-27 against the authoritative binary
`.gradle/loom-cache/remapped_mods/.../minecraft-transit-railway-FABRIC-4.0.1+1.20.4.jar`
(all `javap` / `javap -p -c` claims below are tagged **[verified-4.0.1-bytecode]**) and the
drifted reference sources at `mtr-src/mtr/fabric/src/main/java/org/mtr/mod/...`
(tagged **[drifted-source]** where the jar was not checked line-by-line).
For `TicketSystem`, `BlockTicketBarrier` and `BlockTicketProcessor` the drifted source was
compared member-by-member against the 4.0.1 bytecode and **matches exactly** — signatures,
constants, message keys, and control flow all line up, so the source can be read as ground
truth for those three classes.

Our blocks referenced throughout:
- `src/main/java/com/stationannouncer/mtr/TurnstileBlock.java` (+ `turnstile_exit` variant via `allowEntry=false`)
- `src/main/java/com/stationannouncer/mtr/TurnstileHeetBlock.java`
- `src/main/java/com/stationannouncer/mtr/EmergencyExitDoorBlock.java`
- `src/main/java/com/stationannouncer/mtr/TurnstileBaseBlock.java`, `TurnstileCapBlock.java`
- assets: `tools/gen_turnstile_assets.py`, `tools/gen_gate_assets.py`

---

## 1. `org.mtr.mod.data.TicketSystem` — complete map

### Public surface [verified-4.0.1-bytecode]

```java
public static final String BALANCE_OBJECTIVE = "mtr_balance";
public static final String BALANCE_OBJECTIVE_TITLE = "Balance";

public static void passThrough(World, BlockPos, PlayerEntity,
        boolean isEntrance, boolean isExit,
        SoundEvent entrySound, SoundEvent entrySoundConcessionary,
        SoundEvent exitSound,  SoundEvent exitSoundConcessionary,
        @Nullable SoundEvent failSound,
        boolean remindIfNoRecord,
        Consumer<EnumTicketBarrierOpen> callback);

public static int  getBalance(World, PlayerEntity);
public static void addBalance(World, PlayerEntity, int amount);

public enum EnumTicketBarrierOpen { CLOSED, PENDING, OPEN, OPEN_CONCESSIONARY }
```

Everything else is private. The entry-zone objective names are **private constants but
stable strings**: `mtr_entry_zone_1/2/3` (titles "Entry Zone 1/2/3"), plus `mtr_balance`
("Balance"). All are auto-created as DUMMY scoreboard objectives on first touch
(`getOrCreateScoreboardObjective`). [verified-4.0.1-bytecode]

### Hardcoded economics [verified-4.0.1-bytecode]

| Constant | Value | Where seen in bytecode |
|---|---|---|
| `BASE_FARE` | **2** | `ldc2_w 2l` at start of fare expression in `onExit` |
| `ZONE_FARE` | **1** | multiplier of the zone-distance sum |
| `EVASION_FINE` | **500** | `sipush -500` (double-entry) and `ldc2_w 500l` (exit with no record) |

There is **no config, no dashboard setting, no per-route fare** — these are compile-time
constants. The only tunable input is each station's three zone numbers (§2).

### `passThrough` step by step [verified-4.0.1-bytecode; flow identical in drifted source]

1. **Asynchronous station lookup.** Sends a `NEARBY_STATIONS` operation
   (`Init.sendMessageC2S(OperationProcessor.NEARBY_STATIONS, ..., new NearbyAreasRequest<>(pos, 0), callback, NearbyAreasResponse.class)`)
   with **radius 0** — the block must be inside the station's dashboard rectangle
   (AreaBase bounds span all Y, as we already learned). The rest of the method body runs
   in that response callback, which lands **on the server thread** (MTR's own barrier
   mutates blockstate from it).
2. **No station → `callback.accept(CLOSED)`** and *nothing else*: no sound, no message, no
   charge. A fare block outside any station silently does nothing but report CLOSED.
3. **Direction decision.** If `isEntrance && isExit` (a bidirectional gate — what our
   normal turnstile passes), direction is derived from the rider's record:
   `isEntering = !entered(zone1Score, zone2Score, zone3Score)` where
   `entered(a,b,c)` is **`a != 0 && b != 0 && c != 0`** — *all three* must be nonzero.
   Otherwise `isEntering = isEntrance` (a dedicated entry gate always enters, a dedicated
   exit gate always exits).
4. **Entry path (`onEnter`)**:
   - Already entered + `remindIfNoRecord=true` → action-bar `gui.mtr.already_entered`
     ("Station already entered"), returns **false** (gate stays closed, no charge).
   - Already entered + `remindIfNoRecord=false` → zones wiped to 0 and **balance −500**
     (double-entry treated as evasion), then falls through to the normal entry check.
   - Normal entry: allowed iff **`balance >= 0`** (yes — a rider at exactly $0 may enter;
     the balance is not debited at entry at all). On success the three zone scores are set
     to `encodeZone(station.getZone1/2/3())` and the rider sees `gui.mtr.enter_barrier`
     ("Entered %s - Balance: $%s"). On failure: `gui.mtr.insufficient_balance`
     ("Insufficient balance ($%s)"), returns false.
5. **Exit path (`onExit`)**:
   - No record + `remindIfNoRecord=true` → `gui.mtr.already_exited` ("No entry record
     found"), returns **false**, no charge. (This is why our `turnstile_exit` passes
     `remindIfNoRecord=true` — walk-ups get the reminder, not the fine.)
   - Otherwise: `fare = 2 + 1 * (|z1_here − z1_entry| + |z2_here − z2_entry| + |z3_here − z3_entry|)`.
     If the rider had **no record** (and no reminder was requested) the charge is the
     **500 evasion fine** instead. Concessionary riders pay `ceil(fare / 2)`.
     Zones are wiped, balance is decremented (**can go negative — exit is never denied**),
     rider sees `gui.mtr.exit_barrier` ("Exited %s - Fare: $%s - Balance: $%s"), returns true.
6. **Sounds** (played at the block, category BLOCKS, vol/pitch 1): on success the entry or
   exit sound (concessionary variant if applicable); on failure the `failSound` **only if
   non-null** (MTR's barrier passes null; its processors pass `TICKET_PROCESSOR_FAIL`).
7. **Callback** receives `OPEN_CONCESSIONARY` / `OPEN` on success, `CLOSED` on failure.
   `PENDING` is **never produced by TicketSystem** — it exists only for barrier blocks to
   display while the async lookup is in flight (MTR's barrier sets it itself before
   calling passThrough).

### Concessionary = creative mode [verified-4.0.1-bytecode]

`isConcessionary(player)` is literally `player.isCreative()`. Creative players pay half
fare (rounded up), trigger the `*_concessionary` sounds, and produce
`OPEN_CONCESSIONARY`.

### Zone encoding [verified-4.0.1-bytecode]

`encodeZone(z) = z >= 0 ? z + 1 : z` and `decodeZone(z) = z > 0 ? z − 1 : z`. The +1 shift
exists so that zone 0 stores as 1, guaranteeing every entry record is nonzero on all three
objectives (negative zones are already nonzero). **Consequence for our code:** after any
MTR entry, *all three* `mtr_entry_zone_*` scores are nonzero; after any exit all three are
0. MTR's `entered()` requires ALL three nonzero, while our
`TurnstileBlock.hasEntryRecord` returns true if ANY is nonzero. They agree on every state
MTR itself produces, but diverge on hand-edited scoreboards — worth aligning to the AND
semantics for exactness (one-line fix).

### Fines / evasion

There is no dedicated fine API. The 500-fare "fine" is just the two code paths above.
Our emergency-exit door's `addBalance(world, player, −100)` is the correct (and only)
way to levy a custom fine. Note MTR's own fine is **$500**, not $100 — CLAUDE.md's older
"1 emerald = $10 so $100" note underestimates MTR's severity; whether to match $500 is
Thomas's call.

---

## 2. Zones and transfers

- **Where zones live:** `org.mtr.core.data.Station` has `getZone1/getZone2/getZone3()`
  (long) and matching setters [verified-4.0.1-bytecode]. They are set per station in the
  MTR dashboard ("Fare Zone" — lang keys `gui.mtr.zone` = "Fare Zone", `gui.mtr.zone_number`
  = "Zone %s" exist in the 4.0.1 jar [verified-4.0.1-bytecode]). Three independent zone
  axes let a network price by, e.g., ring + branch + special surcharge simultaneously.
- **Fare formula:** Manhattan distance across the three zone axes, times $1, plus $2 base
  (§1). A same-zone trip costs $2. There is **no distance-based, per-route, or per-line
  fare** anywhere in the jar — the only fare inputs are the two stations' zone triples.
- **Transfers / timed re-entry: none.** The entry record is a single zone triple with no
  timestamp; there is no out-of-system transfer window, no re-entry grace, no daily cap.
  Exiting always closes the trip. (Anything like a NYC-style free transfer would have to
  be built by us on top: e.g. remember `(station, time)` at exit ourselves and refund the
  base fare on re-entry within N minutes — MTR will neither help nor interfere, since all
  state is plain scoreboards we can read and write.)
- **What our gates need to do to honor zones properly: nothing more than they do.**
  `passThrough` handles all zone bookkeeping internally. Our pre-read of
  `mtr_entry_zone_1..3` for direction/fare display is exactly the right pattern; the only
  correction is the ANY-vs-ALL nonzero check noted in §1. The displayed fare
  (`balanceBefore − balanceAfter`) is computed from the balance delta, which is robust to
  every path above including the evasion fine.

---

## 3. Deny-on-no-fare: what a dry run looks like, and how MTR physically blocks

### Is there a dry-run API? No — but the pieces are public.

`TicketSystem` exposes no `canPass`/`isEntering` predicate. However every input to its
decision is readable without side effects:

- **Entry would be denied iff `TicketSystem.getBalance(world, player) < 0`**
  [verified-4.0.1-bytecode: the only entry gate is `balance >= 0`]. `getBalance` is public
  and pure (it only auto-creates the objective). A rider at $0 passes.
- **Entry record ("is this rider inside the system?")** = read the three
  `mtr_entry_zone_*` scoreboard objectives, all-three-nonzero (our `hasEntryRecord`
  already reads them; tighten to AND). The names are private constants but fixed strings;
  they have been stable across MTR 3→4 and are effectively API.
- **Exit is never denied** when a record exists — it charges whatever it charges, negative
  balance allowed. The only deniable exit is the no-record walk-up with
  `remindIfNoRecord=true`.
- The **only station-membership check** is the async NEARBY_STATIONS lookup; we can (and
  our fare callback already does) issue the same `Init.sendMessageC2S(NEARBY_STATIONS, ...)`
  request ourselves for a dry run, but it is async — fine for pre-arming a gate as a
  player approaches, not for a same-tick decision.

So a complete synchronous dry-run for a bidirectional gate is:

```java
boolean inside  = enteredAllThree(scoreboard);          // exit path → always allowed
boolean allowed = inside || TicketSystem.getBalance(w, p) >= 0;  // entry path
```

Only the "is there a station here at all" part must be async (or cached — see proposal P2).

### How MTR's own barrier physically blocks [verified-4.0.1-bytecode]

`BlockTicketBarrier` (constructor flag `isEntrance`; property
`OPEN : EnumTicketBarrierOpen`, luminance 5):

- **Collision** (`getCollisionShape2`): always the cabinet slab `(15,0,0)→(16,24,16)`;
  when state is *not* OPEN/OPEN_CONCESSIONARY (i.e. CLOSED **and PENDING**) it unions an
  invisible **2-px paddle wall across the lane at z 7..9, 24 px tall**
  (`(0,0,7)→(16,24,9)`). That wall is the entire "gate" — there is no paddle geometry in
  the models at all (§4).
- **`onEntityCollision2`** (fires while the player pushes against the collision wall or
  stands in the outline): rotates the player's offset into the block frame.
  - Approaching side (`z < 0`) + CLOSED → set **PENDING**, call `passThrough(...,
    isEntrance, !isEntrance, barrier sounds ×4, failSound=null, remindIfNoRecord=false,
    callback)`. The callback writes the resulting state back and, if it opened, schedules
    a block tick in **40 ticks** which snaps it back to CLOSED (`scheduledTick2`).
  - Far side (`z > 0`) + OPEN → immediately CLOSED (the gate shuts behind you; that is
    also what re-arms it for the next rider before the 40-tick timeout).
- Denial is therefore **purely physical**: state stays CLOSED, the wall keeps standing,
  the player bumps into it. No fail sound (null), only the action-bar message from
  TicketSystem. The 24-px-tall wall means you cannot jump it.
- Because the player is *outside* the wall plane when denied, the collision toggle never
  traps or ejects anyone — the wall only ever appears/disappears in front of the rider,
  never around them. **This is the pattern to copy if we add a blocking mode** (our old
  ejection bug came from closing collision around a player mid-lane).

`BlockTicketProcessor` (the standalone card reader) never blocks: `onUse2` on the upper
half calls `passThrough(canEnter, canExit, processor sounds, TICKET_PROCESSOR_FAIL,
remindIfNoRecord=true, cb)` and the callback drives the `LIGHTS` property
(NONE/RED/YELLOW_GREEN/GREEN), reset to NONE by a 20-tick scheduled tick.
[verified-4.0.1-bytecode: signatures and property; behavior from matching drifted source]

### Exact API recipe for a "deny" mode on our blocks

1. Add an `OPEN`-like enum property (or reuse `INDICATOR`) plus a conditional collision
   shape: closed → thin wall across the lane center (only across the walkway, 24 px tall);
   open → current shape.
2. In `onEntityCollision` on the approach side: synchronous pre-checks first (record from
   scoreboard; if it would be an entry, `getBalance() >= 0`). If the pre-check fails,
   light STOP and *don't* call passThrough (saves the async round trip and gives an
   instant red). If it passes, set PENDING-equivalent, call `passThrough`, open on
   OPEN/OPEN_CONCESSIONARY in the callback, schedule the re-close.
3. Keep the far-side "close behind the rider" check — it is what makes tailgating hard
   and is two lines of vector math (see MTR's rotateY trick).

---

## 4. How MTR's barrier & processor look — worth borrowing

All from the 4.0.1 jar's JSON assets [verified-4.0.1-bytecode/jar-assets]:

- **There is no animated paddle.** The barrier's three models
  (`ticket_barrier_*_{closed,open,open_concessionary}`) are texture-swap children of one
  `ticket_barrier_1_base` model. The visible unit is only the cabinet (x 12..16 — the lane
  is the other 12 px of the block), a floor plinth, a yellow top strip, a
  ticket-target decal on the deck, and a **rear sign panel**. The "gate" is the invisible
  collision wall. MTR shipped a fare gate with zero moving parts — our no-animated-arm
  decision has precedent.
- **State is communicated by pictogram swap**: the sign texture slot is
  `mtr:block/transparent` when closed and `mtr:block/sign/arrow_up` (green up arrow) when
  open; the entrance/exit variants use directional arrow decals (`arrow_down_left` etc.)
  on the rear panel. The lit-sign elements carry `"shade": false` so they read bright from
  any angle (not truly emissive, but flat-lit).
- **Indicator strip as texture-slot toggle**: the base model stacks two coincident
  elements for the yellow strip — one textured by `#yellow_glass_off`, one by
  `#yellow_glass_on` — and each state model maps one of them to `transparent`. Same trick
  our lamp uses via three whole models; the slot-swap version needs one model per state
  but shares all geometry via `parent`, which is cheaper to maintain than our three
  hand-built lamp models.
- **Processors** are slim totem poles (two-block, `BlockDirectionalDoubleBlockBase`) with
  a 4-state `lights` property; the enquiry variant reuses the same block with all flags
  false and overrides `onUse` to just print the balance with the entry beep.
- **Sound set** (all in `assets/mtr/sounds/`): `ticket_barrier(.concessionary)`,
  `ticket_processor_entry/exit(_concessionary)`, `ticket_processor_fail`. We already use
  barrier + fail; the entry/exit/concessionary processor set is unused by us and gives
  distinct entry-vs-exit audio for free.
- Barrier blocks have **luminance 5** (`createDefaultBlockSettings(true, s -> 5)`) so the
  lamps read at night. Our turnstiles currently emit no light.

---

## 5. Visual improvements for our fare blocks

Current state (from `tools/gen_turnstile_assets.py` + CLAUDE.md "TURNSTILE ROUND 2"/
"TURNSTILE OVERHAUL"): brushed-steel cabinet with proud plinth and dome deck, black
reader pillar (screen/card-slot/yellow tap target), 3-model GO/STOP/OFF lens, octagonal
2-px tubing with cast elbows/collars, ENTRY↓/EXIT↓ signboards under the rail, HEET
two-block cage with 4-wing rotor and roof plate, emergency-exit door as a material
assembly with strobe lamp. Constraints honored: vanilla JSON + BER quads/text, square
textures, no Fabric Renderer API, **no animated arm (settled)** — though BER animation of
*other* elements (rotor spin, lamp pulse) was never explicitly ruled out; flagged below
as an open question for Thomas.

Concrete, buildable upgrades:

1. **Pictogram state signs, MTR-style** — put a green ⬆/⭘ GO pictogram and a red ✖/⛔
   STOP pictogram on the reader pillar's approach face (and on the HEET's lintel),
   swapped by the existing `INDICATOR` property instead of (or in addition to) the tiny
   lens. Pure generator work: two more 32×32 sprites + `shade: false` elements in the
   go/stop lamp models. This is the single highest-visibility win; real NYC turnstiles
   lead with the pictogram, not a lens. (S)
2. **Luminance + emissive lenses** — give the turnstile upper block a conditional-free
   luminance (MTR uses a flat 5) so lamps read at night, and mark lens/pictogram elements
   `shade: false`. If true emissivity is wanted, the BER route (CanvasPainter quads, same
   as PIDS) can overdraw the lit lens only while `INDICATOR != OFF`. (S)
3. **PENDING/amber state** — add a fourth `Indicator.WAIT` shown between collision and
   callback (the async NEARBY_STATIONS hop is real latency on busy servers). Mirrors
   MTR's PENDING. Mostly Java + one lamp model. (S)
4. **Live reader screen via BER** — the pillar screen is currently painted pixels. A tiny
   BER canvas (we own CanvasPainter) could show "$23" balance of the last rider for a
   second, or "TAP" idle text, exactly like the fare machine's action-bar but in-world.
   Question for Thomas: is a text-drawing BER on the turnstile worth the extra renderer,
   given the mod's per-frame budget lessons? (M)
5. **HEET rotor detail pass** — real HEETs have 3 stacked comb arms per wing and a
   center drum; the generator's rotor is a plain 4-wing. Add comb teeth (thin horizontal
   bars at 3 heights per wing) and a drum core; also give the HEET its own red/green
   pictogram boxes above the lane (photos show lit ENTER / NO ENTRY signs on the lintel).
   Pure generator geometry. (M)
6. **[Open question] BER rotor spin** — a slow constant spin, or a 90° step on each GO,
   would *not* violate the no-animated-arm decision (that was about the low turnstile's
   tripod arm blocking the lane) but it is renderer-side rotation of a JSON-shaped
   element, i.e. a small BER that re-draws the rotor and hides the static one per state.
   Needs Thomas's sign-off before anyone builds it. (M)
7. **Distinct entry/exit audio** — wire `TICKET_PROCESSOR_ENTRY/EXIT(_CONCESSIONARY)`
   into the passThrough call instead of passing the barrier clunk for both directions
   (we already pass 4 identical sounds; the slots exist — zero new assets). The barrier
   clunk can stay for the HEET, which really does clunk. (S)
8. **Fare-array end caps against walls** — cosmetic known gap: a lane ending at a wall
   currently caps with the elbow; a wall-collar variant (reuse the riser collar) would
   finish banks placed against tiled walls. (S)
9. **Emergency exit door**: paint the strobe's lit state with `shade:false` + luminance
   while `ALARM` (it currently relies on texture tone alone), and add the NYC
   "EMERGENCY EXIT / ALARM WILL SOUND" red header glow when alarming. (S)

---

## 6. Ticket-domain features we are not using

- **`Init.REGISTRY.sendPacketToClient(ServerPlayerEntity, new PacketOpenTicketMachineScreen(balance))`**
  [verified-4.0.1-bytecode: public ctor `PacketOpenTicketMachineScreen(int)`; exactly how
  `BlockTicketMachine.onUse2` opens the screen]. Our fare machine could open **MTR's own
  top-up screen** (10 buttons) instead of the emerald-in-hand flow — or in addition to it.
- **`PacketAddBalance` economics** [verified-4.0.1-bytecode]: button `index` 0..9 removes
  `2^index` emeralds and adds `ceil(2^index × (10 + index))` dollars — i.e. a growing
  bulk bonus ($10 for 1 emerald, $22 for 2, $48 for 4, … index 9 = 512 emeralds → $9,728).
  Our fare machine's flat 1-emerald=$10 matches only button 0; if we keep our own GUI we
  could mirror the bonus curve for parity.
- **Enquiry interaction**: `BlockTicketProcessorEnquiry.onUse` = `getBalance` → action-bar
  `gui.mtr.balance` + entry beep. Trivially replicable as a right-click on our reader
  pillar (empty hand), matching our fare machine's existing readout. [drifted-source,
  flow; getBalance call verified-4.0.1-bytecode]
- **Concessionary tier** exists for free (creative players): half fare, distinct sounds,
  `OPEN_CONCESSIONARY`. Our lamp treats it as GO; we could tint it (HK gates show a
  different light for concession cards) — cosmetic only.
- **No fare-gate S2C packet exists** — barrier state is plain blockstate replication;
  the only ticket packets in 4.0.1 are `PacketAddBalance` (C2S) and
  `PacketOpenTicketMachineScreen` (S2C). Nothing else to hook. [verified-4.0.1-bytecode:
  jar class listing]
- **No per-route fares / fare tables / fare config** — confirmed absent from the jar; the
  entire economy is the three per-station zone longs + three hardcoded constants.
- Lang keys our messages could align with (jar `en_us.json`): `gui.mtr.enter_barrier`,
  `gui.mtr.exit_barrier`, `gui.mtr.insufficient_balance`, `gui.mtr.already_entered`,
  `gui.mtr.already_exited`, `gui.mtr.balance`, `gui.mtr.add_balance_for_emeralds`.

---

## Prioritized proposals

| # | Proposal | Why | Effort |
|---|---|---|---|
| P1 | **Align `hasEntryRecord` with MTR's `entered()`** (ALL three zones nonzero, not ANY) in `TurnstileBlock` | Exactness; one-line; prevents mislabeled Entering/Leaving on hand-edited boards | S |
| P2 | **Insufficient-balance STOP pre-check**: on the entry path, synchronously check `getBalance() < 0` (+ no-record) before `passThrough`; instant red lamp + skip the async hop when it can't succeed | Makes the STOP lamp honest and immediate; groundwork for any blocking mode | S |
| P3 | **Pictogram GO/STOP signs + luminance + `shade:false`** on turnstile/HEET (proposal 5.1/5.2) | Biggest visual payoff; matches both MTR precedent and NYC photos | S–M |
| P4 | **Distinct entry/exit/concessionary sounds** via the unused processor sound set | Free assets, real feedback difference | S |
| P5 | **Optional blocking mode ("gated" variant or config)**: MTR-pattern collision wall across the lane, PENDING amber, open-on-callback, 40-tick re-close, close-behind-rider | The one behavioral capability MTR has that we lack; the barrier bytecode is a complete recipe and its wall-in-front-never-around-player geometry avoids our old ejection bug | M |
| P6 | **Right-click reader = balance enquiry** (empty hand on pillar → `gui.mtr.balance`-style action bar + beep) | Two lines of behavior, real station realism | S |
| P7 | **HEET rotor + lintel-sign detail pass** in the generator | Closes the visual gap vs photos | M |
| P8 | **Fare machine opens MTR's TicketMachineScreen** via `sendPacketToClient(new PacketOpenTicketMachineScreen(balance))` (keep emerald shortcut) | Bulk top-ups + bonus curve for free; zero GUI code of ours | S |
| P9 | **Amber WAIT indicator state** while the passThrough callback is in flight | Honest UX on busy servers; needed anyway by P5 | S |
| P10 | **[ask Thomas] BER rotor spin on the HEET** (90° step per GO) — not the banned lane arm, but it is animation | Delight feature; cheap once decided | M |
| P11 | **[ask Thomas] Raise our evasion fine toward MTR's $500**, or make it configurable in `station-announcer-addon.json` | Consistency with MTR's own $500 evasion charge | S |
| P12 | **Timed transfer window (our own)**: remember exit station+time, refund base fare on re-entry within N min | MTR has nothing here; scoreboards are open; genuinely novel NYC-style feature | L |

*Not proposed*: touching MTR's fare constants (compile-time private), per-route fares
(no hook exists), or any animated tripod arm (settled).
