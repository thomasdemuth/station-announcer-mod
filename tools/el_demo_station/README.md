# El demo station — the kit's end-to-end test

Builds one complete NYC el station from the street up using ONLY kit blocks,
twice: classic at z 1988..2024 and the ESI theme 50 blocks south. Everything
that a player would place by hand is placed through the REAL item code path
(`/rigplace`, a dev-only command that runs a right-click as a named player),
so the course items' far-edge / stair-tread / stacking rules are exercised,
not bypassed; bulk pieces (floors, decks, ceilings) use `/fill`, which the kit
blocks settle from (`SelfSettle`, onBlockAdded).

Needs the dev rig: `./gradlew runServer` (RCON on 25575 / `rigpass`, see
run/server.properties) and a client logged in as `Rig`, site forceloaded and
cleared (x 1950..2079, z 1940..2079, ground y 63).

    python3 build_classic.py      # classic station
    python3 build_esi.py          # the same station, ESI blocks, DZ = 50

Stages: 1 street + bents + decks + platforms, 2 platform walls/railings, posts,
roofs, lights, lamps, 3 mezzanine (floor, walls with sash windows, doorway,
ceiling + lights), 4 platform stairs through the slab (well cut, flights by
clicking the upper step), 5 their sides (in-cell walls, upper courses, rim
railings, the building wall carried up through the slab band), 6 street
entrance (stair, railing + posts-only courses, sloped roof, flat hood, sign,
landing into the mezzanine doorway).

The layout, top to bottom: platform floor y74 (walk 75) on plate decks y73,
cross girders y72 on street columns every 6 blocks, mezzanine ceiling y72
between the girders, mezzanine y69..71 on the mezzanine floor y68, street y63.
