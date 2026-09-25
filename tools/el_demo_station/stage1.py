from st import *
X0,X1=1994,2036; Z0,Z1=1988,2024
# clear site, street
clear(X0,64,Z0,X1,90,Z1)
fill(X0,63,Z0,X1,63,Z1,'minecraft:gray_concrete')
fill(X0,63,1990,X1,63,1998,'minecraft:smooth_stone')   # north sidewalk
fill(X0,63,2015,X1,63,2023,'minecraft:smooth_stone')
# bents: columns + cross girder
for x in range(1998,2035,6):
    for z in (2004,2009):
        fill(x,64,z,x,71,z,'el_street_column')
    fill(x,72,1999,x,72,2014,'el_girder_plate[axis=z]')
# decks
fill(1996,73,2004,2034,73,2009,'el_track_deck[axis=x]')
fill(1996,73,2000,2034,73,2003,'el_plate_deck[axis=x]')
fill(1996,73,2010,2034,73,2013,'el_plate_deck[axis=x]')
# platforms x 1998..2032
for (zf0,zf1,ze,side) in ((2000,2002,2003,'south'),(2011,2013,2010,'north')):
    fill(1998,74,zf0,2032,74,zf1,'platform_concrete_floor_3')
    fill(1998,74,ze,2032,74,ze,f'platform_edge[facing={side}]')
