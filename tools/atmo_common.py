#!/usr/bin/env python3
"""Shared helpers for the atmosphere generators (ATMOSPHERE_PLAN.md):
gen_grime_assets.py, gen_litter_assets.py, gen_construction_assets.py,
gen_atmosphere_assets.py. Several of those may run close together, so the
shared files (lang, mineable tags) are UPSERTED with a quick
read-modify-write that only touches the generator's own keys and keeps the
rest of the file byte-for-byte.
"""

import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src/main/resources/assets/station_announcer")
DATA = os.path.join(ROOT, "src/main/resources/data")
MOD = "station_announcer"


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


def tex_path(name, kind="block"):
    return os.path.join(ASSETS, "textures", kind, name + ".png")


def upsert_lang(entries):
    """Insert or replace keys in en_us.json without reformatting the file."""
    path = os.path.join(ASSETS, "lang/en_us.json")
    with open(path) as fh:
        src = fh.read()
    missing = []
    for key, value in entries.items():
        k = json.dumps(key)
        v = json.dumps(value, ensure_ascii=False)
        pattern = re.compile(r'(^[ \t]*' + re.escape(k) + r'[ \t]*:[ \t]*)"(?:[^"\\]|\\.)*"', re.M)
        if pattern.search(src):
            src = pattern.sub(lambda m: m.group(1) + v, src, count=1)
        else:
            missing.append(f"  {k}: {v}")
    if missing:
        end = src.rstrip().rfind("}")
        src = src[:end].rstrip() + ",\n" + ",\n".join(missing) + "\n" + src[end:]
    json.loads(src)  # must still parse
    with open(path, "w") as fh:
        fh.write(src)


def insert_into_tag(rel_path, ids):
    """Add ids to a tag file under data/ (e.g. 'minecraft/tags/blocks/mineable/pickaxe.json')."""
    path = os.path.join(DATA, rel_path)
    if not os.path.exists(path):
        write_json(path, {"replace": False, "values": list(ids)})
        return
    with open(path) as fh:
        src = fh.read()
    missing = [i for i in ids if f'"{i}"' not in src]
    if not missing:
        return
    end = src.rfind("]")
    head = src[:end].rstrip()
    new = head + ",\n" + ",\n".join(f'    "{i}"' for i in missing) + "\n  " + src[end:]
    json.loads(new)
    with open(path, "w") as fh:
        fh.write(new)


def loot_self(name, condition=None):
    """Loot table dropping the block itself (optionally gated by a block_state_property dict)."""
    pool = {"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{name}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}]}
    if condition:
        pool["conditions"].append({"condition": "minecraft:block_state_property",
                                   "block": f"{MOD}:{name}", "properties": condition})
    write_json(os.path.join(DATA, MOD, "loot_tables/blocks", name + ".json"),
               {"type": "minecraft:block", "pools": [pool]})


def loot_nothing(name):
    write_json(os.path.join(DATA, MOD, "loot_tables/blocks", name + ".json"),
               {"type": "minecraft:block", "pools": []})


def item_flat(name, texture=None):
    """A flat generated item model using textures/item/<texture or name>."""
    write_json(os.path.join(ASSETS, "models/item", name + ".json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{texture or name}"}})


def item_block(name, model=None):
    write_json(os.path.join(ASSETS, "models/item", name + ".json"),
               {"parent": f"{MOD}:block/{model or name}"})
