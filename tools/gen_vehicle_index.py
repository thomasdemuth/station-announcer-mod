#!/usr/bin/env python3
"""Composes `assets/mtr/mtr_custom_resources.json` from every train we ship.

    python3 tools/gen_vehicle_index.py

WHY THIS FILE EXISTS
--------------------
`mtr_custom_resources.json` is a SINGLE file. MTR reads it once, from every
resource pack, and it holds every vehicle a pack contributes — so it cannot be
owned by one train's generator. It was, right up until the R62 arrived: the M7's
`gen_m7_assets.py` wrote the whole index itself, and the first run of the R62's
generator would have replaced eight M7 vehicles with two R62s, silently, with the
M7's properties files still sitting on disk pointing at nothing.

So the index moved here and each train's generator now exposes a `vehicles()`
provider instead. Each one still writes its OWN properties and definitions —
that ownership was never the problem — and each one's `main()` calls
`write()` here at the end, so running a single generator still leaves a correct
index. Adding a train is one line in `PROVIDERS`.

The index's other four top-level keys (`signs`, `rails`, `objects`, `lifts`) are
MTR's own vocabulary and this mod contributes nothing to them, so they ship as
empty lists — omitting them is not obviously safe and costs nothing to keep.

⭐ IDS MUST BE UNIQUE ACROSS THE WHOLE FILE. MTR logs
`resource contains duplicated id` and drops the loser, so `write()` refuses
rather than shipping a file where one train quietly eats another's entry.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "src/main/resources")
INDEX = os.path.join(RES, "assets/mtr/mtr_custom_resources.json")

# Every train that contributes vehicles, in the order they appear in the index.
# A provider is a module exposing `vehicles() -> [dict]` and nothing else is
# required of it; it must have no side effects, because this calls it purely to
# read.
PROVIDERS = (
    ("gen_m7_assets", "LIRR M7"),
    ("gen_r62_assets", "NYCT R62"),
)


def collect():
    """[(label, [vehicle dicts])] for every provider, imported on demand."""
    out = []
    for module_name, label in PROVIDERS:
        module = __import__(module_name)
        out.append((label, list(module.vehicles())))
    return out


def custom_resources():
    vehicles = []
    for _label, entries in collect():
        vehicles += entries
    return {"vehicles": vehicles, "signs": [], "rails": [], "objects": [],
            "lifts": []}


def write():
    """Write the index. Returns the number of vehicles in it."""
    data = custom_resources()
    ids = [v["id"] for v in data["vehicles"]]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        raise SystemExit("two providers claim the same vehicle id(s): %s — MTR "
                         "would log 'resource contains duplicated id' and drop "
                         "one of them" % ", ".join(duplicates))
    os.makedirs(os.path.dirname(INDEX), exist_ok=True)
    with open(INDEX, "w") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")
    return len(data["vehicles"])


def main():
    groups = collect()
    total = write()
    print("mtr_custom_resources.json: %d vehicles" % total)
    for label, entries in groups:
        print("  %-10s %d  (%s)" % (label, len(entries),
                                    ", ".join(v["id"] for v in entries)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
