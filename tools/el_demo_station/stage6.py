"""Street entrance on the north sidewalk: stair x2000..2004 (y64..68) rising EAST, z1996..1997,
Van Siclen style sides (railing + open + open), sloped roof, flat hood + sign, landing into the doorway."""
from st import *
L0,L1=1996,1997
rp('subway_stairs',2000,63,L0,'up',0.5,1.0,0.5,'east')
for i in range(4):
    rp('subway_stairs',2000+i,64+i,L0,'up',0.75,1.0,0.5,'east')
for i in range(5):
    rp('subway_stairs',2000+i,64+i,L0,'south',0.5,0.25,1.0,'east')
# side courses: north beside cells (z1995) from lane L0, south (z1998) from lane L1
for i in range(5):
    x,y=2000+i,64+i
    for it in ('el_stair_railing','el_stair_open','el_stair_open'):
        rp(it,x,y,L0,'up',0.5,1.0,0.1,'east')
        rp(it,x,y,L1,'up',0.5,1.0,0.9,'east')
# roof: on top of the north top course, then across by side faces
for i in range(5):
    x,y=2000+i,64+i
    rp('el_stair_roof',x,y+2,1995,'up',0.5,1.0,0.5,'east')
    for z in (1995,1996,1997):
        rp('el_stair_roof',x,y+3,z,'south',0.5,0.9,1.0,'east')
# hood behind the foot + landing roof over the landing
for z in range(1995,1999):
    rp('el_landing_roof',2000,67,z,'west',0.0,0.9,0.5,'east')
for x in (2005,2006,2007):
    rp('el_landing_roof',x,72,2000,'north',0.5,0.5,0.0,'south')
    for z in (1999,1998,1997,1996):
        rp('el_landing_roof',x,72,z,'north',0.5,0.5,0.0,'south')
# landing edge railings (north + east) and the posts carrying the landing
for x in (2005,2006,2007):
    rp('el_railing',x,68,1996,'up',0.5,1.0,0.1,'north')
for z in (1996,1997,1998):
    rp('el_railing',2007,68,z,'up',0.9,1.0,0.5,'east')
for (x,z) in ((2007,1996),(2007,1999)):
    for y in (63,64,65,66):
        rp('el_post',x,y,z,'up',0.5,1.0,0.5,'east')
# entrance sign hung under the hood's street edge, facing west
for z in range(1995,1999):
    rp('el_entrance_sign',1999,67,z,'down',0.5,0.0,0.5,'east')
