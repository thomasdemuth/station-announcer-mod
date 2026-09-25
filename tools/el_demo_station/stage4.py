"""Platform stairs: mezzanine (walk 69) -> side platforms (walk 75), through the slab.
Rising EAST: foot x2014 (y69, entered from the paid mezzanine at x2013), top x2019 (y74, platform level)."""
from st import *
def flight(zl, zr):
    fill(2015,72,zl,2019,74,zr,'minecraft:air')          # the well: ceiling, deck, platform slab
    fill(2017,75,zl,2017,77,zr,'minecraft:air')          # a platform post stood over the well
    rp('subway_stairs',2014,68,zl,'up',0.5,1.0,0.5,'east')
    for i in range(5):
        rp('subway_stairs',2014+i,69+i,zl,'up',0.75,1.0,0.5,'east')   # upper step: continue up
    for i in range(6):
        rp('subway_stairs',2014+i,69+i,zl,'south' if zr>zl else 'north',0.5,0.25,1.0 if zr>zl else 0.0,'east')
flight(2000,2001)
flight(2013,2012)
