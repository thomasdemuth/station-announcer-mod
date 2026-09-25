"""Sides of the platform flights: in-cell walls, upper cells to the platform, rim railings (rising EAST)."""
from st import *
def sides(outer, inner, outer_hz, inner_hz, rim_z, rim_hz, rim_look):
    for i in range(6):
        x, y = 2014 + i, 69 + i
        if i < 5:
            rp('el_stair_wall', x, y, inner, 'up', 0.5, 1.0, inner_hz, 'east', sneak=True)
        else:
            rp('el_stair_wall', x, y, outer, 'up', 0.5, 1.0, outer_hz, 'east', sneak=True)
        for _ in range(74 - y):
            rp('el_stair_upper', x, y, inner, 'up', 0.5, 1.0, inner_hz, 'east')
        for _ in range(74 - y):
            rp('el_stair_upper', x, y, outer, 'up', 0.5, 1.0, outer_hz, 'east')
    for x in range(2015, 2020):
        rp('el_railing', x, 74, rim_z, 'up', 0.5, 1.0, rim_hz, rim_look)
    for z in (outer, inner):
        rp('el_railing', 2014, 74, z, 'up', 0.9, 1.0, 0.5, 'east')
sides(2000, 2001, 0.1, 0.9, 2002, 0.1, 'north')
sides(2013, 2012, 0.9, 0.1, 2011, 0.9, 'south')
# the building wall carried up through the band the well cuts (ceiling / deck / slab levels):
# click the top of the course below, three times per column
for (wz, look) in ((1999, 'north'), (2014, 'south')):
    for x in range(2015, 2020):
        for y in (71, 72, 73):
            rp('el_wall', x, y, wz, 'up', 0.5, 1.0, 0.5, look)
# the top end of each well: the band above the mezzanine's end wall at ceiling level
for z in (2000, 2001, 2012, 2013):
    rp('el_wall', 2020, 71, z, 'up', 0.5, 1.0, 0.5, 'east')
