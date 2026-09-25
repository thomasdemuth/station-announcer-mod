"""Station builder helpers: emit command lists, run through rcon."""
import subprocess, sys, os
HERE=os.path.dirname(os.path.abspath(__file__))
NS='station_announcer:'
CMDS=[]
DZ=0
COURSES={'platform':['el_wall','el_wall_glass','el_wall'],'open':['el_railing'],'mezz':['el_wall','el_wall_window','el_wall'],'door':['el_wall_doorway','el_wall_doorway','el_wall_glass']}
BMAP={}
def mb(b):
    base=b.split('[')[0]
    rest=b[len(base):]
    return BMAP.get(base,base)+rest

def c(s): CMDS.append(s)
def fill(x0,y0,z0,x1,y1,z1,b,mode=''):
    b=mb(b); z0+=DZ; z1+=DZ
    if ':' not in b.split('[')[0]: b=NS+b
    c(f'fill {x0} {y0} {z0} {x1} {y1} {z1} {b} {mode}'.strip())
def clear(x0,y0,z0,x1,y1,z1,b='minecraft:air'):
    z0+=DZ; z1+=DZ
    for x in range(x0,x1+1,16):
        for z in range(z0,z1+1,16):
            fill(x,y0,z-DZ,min(x+15,x1),y1,min(z+15,z1)-DZ,b)
def setb(x,y,z,b):
    b=mb(b); z+=DZ
    if ':' not in b.split('[')[0]: b=NS+b
    c(f'setblock {x} {y} {z} {b}')
YAW={'south':0,'west':90,'north':180,'east':-90}
def rp(item,x,y,z,face,hx,hy,hz,look='north',pitch=30,sneak=False):
    item=mb(item); z+=DZ
    if ':' not in item: item=NS+item
    yaw=YAW[look] if isinstance(look,str) else look
    c(f'rigplace Rig "{item}" {x} {y} {z} {face} {hx} {hy} {hz} {yaw} {pitch} {str(sneak).lower()}')
def tp(x,y,z,tx,ty,tz): c(f'tp Rig {x} {y} {z} facing {tx} {ty} {tz}')
def run(clear=True, show=False):
    import socket,struct
    s=socket.create_connection(('127.0.0.1',25575))
    def pkt(i,t,body):
        b=body.encode(); s.sendall(struct.pack('<iii',len(b)+10,i,t)+b+b'\x00\x00')
    def rd():
        def n(k):
            d=b''
            while len(d)<k: d+=s.recv(k-len(d))
            return d
        ln,=struct.unpack('<i',n(4)); d=n(ln); return d[8:-2].decode('utf-8','replace')
    pkt(1,3,'rigpass'); rd()
    out=[]
    if any(x.startswith('rigplace') for x in CMDS): CMDS.insert(0,'gamemode creative Rig'); CMDS.append('gamemode spectator Rig')
    for cmd in CMDS:
        pkt(2,2,cmd); r=rd()
        if r and (show or 'rigplace' in cmd and '-> SUCCESS' not in r and 'CONSUME' not in r) or ('rror' in r or 'nknown' in r or 'Incorrect' in r or 'Expected' in r or 'Invalid' in r):
            out.append(f'{cmd}\n   -> {r}')
    if clear: CMDS.clear()
    return out
