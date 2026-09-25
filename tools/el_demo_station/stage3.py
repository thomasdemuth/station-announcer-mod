from st import *
import st
MX0,MX1,MZ0,MZ1=2005,2019,2000,2013
# floor (columns stay), plus the landing strip + doorway threshold on the north
fill(MX0,68,MZ0,MX1,68,MZ1,'el_mezzanine_floor[axis=z]','replace minecraft:air')
fill(2005,68,1996,2007,68,1999,'el_mezzanine_floor[axis=z]','replace minecraft:air')
# walls: north z=1999 (except doorway x 2006..2007 and landing floor below), south z=2014, west x=2004, east x=2020
def wallcol(x,z,face_hit,look,items):
    fx,fz=face_hit
    for it in items:
        rp(it,x,68,z,'up',fx,1.0,fz,look)
STACK=st.COURSES['mezz']
for x in range(MX0,MX1+1):
    if x in (2005,2006,2007):
        continue
    wallcol(x,2000,(0.5,0.1),'north',STACK)
    wallcol(x,MZ1,(0.5,0.9),'south',STACK)
for z in range(MZ0,MZ1+1):
    wallcol(MX0,z,(0.1,0.5),'west',STACK)
    wallcol(MX1,z,(0.9,0.5),'east',STACK)
# north wall over the landing strip: landing floor is at z 1996..1999, so the wall line
# for x 2005..2007 stands at z=1999 ON the floor: set by hand (a player clicks the wall line)
for x in (2005,2006,2007):
    kinds = STACK if x==2005 else st.COURSES['door']
    for i,k in enumerate(kinds):
        # standing ON the landing strip: a plain click on the floor top (or the course below)
        rp(k,x,68+i,1999,'up',0.5,1.0,0.5,'north')
# ceiling + lights
fill(MX0,72,MZ0,MX1,72,MZ1,'el_ceiling','replace minecraft:air')
for x in range(MX0+1,MX1,3):
    for z in (2002,2006,2011):
        if x not in (2006,2012,2018):
            setb(x,72,z,'el_ceiling_light')
