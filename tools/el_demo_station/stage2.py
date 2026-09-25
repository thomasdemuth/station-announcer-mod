from st import *
import st
RX0,RX1=2004,2026   # roofed section
for x in range(1998,2033):
    roofed = RX0<=x<=RX1
    for (zc,hz,look) in ((2000,0.1,'north'),(2013,0.9,'south')):
        courses = st.COURSES['platform'] if roofed else st.COURSES['open']
        for item in courses:
            rp(item,x,74,zc,'up',0.5,1.0,hz,look)
# posts every 6 (between bents), 3 high
for x in range(2005,2027,6):
    for zc in (2001,2012):
        for y in (74,75,76):
            rp('el_post',x,y,zc,'up',0.5,1.0,0.5,'east')
# roofs
fill(RX0,78,2000,RX1,78,2003,'el_roof[axis=x]')
fill(RX0,78,2010,RX1,78,2013,'el_roof[axis=x]')
# roof lights row (cell under roof), skip post columns
for x in range(RX0,RX1+1):
    for zc in (2002,2011):
        setb(x,77,zc,'el_roof_light[facing=east]')
# lamps on the open-end railings / fences: pole on top of the open courses, head at y78
for x in (2000, 2030):
    for zc in (1999, 2014):
        f = "south" if zc == 1999 else "north"
        for y in range(75 + len(st.COURSES['open']), 78):
            setb(x, y, zc, f'el_platform_lamp[facing={f}]')
        setb(x, 78, zc, f'el_platform_lamp_head[facing={f}]')
