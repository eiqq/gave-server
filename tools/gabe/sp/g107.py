"""G107 솟구쳐 덮는 게이브(2막, 땅 밑과 하늘을 오가는 큰 물고기, 길이 16 블록).

앞 6 블록이 통째로 _Gave 얼굴인 물고기 꼴, 뒤로 살 몸통이 가늘어진다. 등에 엔드 막대기 다섯이 부채꼴 등지느러미 살(높이 3)로 섰고,
양옆 가슴지느러미는 막대기 살 넷에 얼굴 가죽 막을 친 날개(편 폭 7). 꼬리지느러미 가시마다 작은 얼굴이 꿰였다. 배에는 웃는 얼굴들이 박혀
솟구치면 하늘이 통째로 웃는 배가 된다. 땅속에서는 등지느러미 막대기 다섯만 땅 위로 보인다(Java hover -2.4).

- gave:gabe/g107(shrink 0.135): 몸 가운데가 (0, 0, 0), 앞(+Z)이 머리, 등 위 y = 2.0.
  상태 ""(헤엄: 가슴지느러미를 몸에 붙임), "spread"(솟구침: 가슴지느러미를 활짝 폄), "tail"(꼬리만 땅 위로 쳐듦: 꼬리가 위로 꺾임).
  부위 fin0..fin4: 그 등지느러미 막대기가 꺾임(그루터기).
소리 deep(멀리서 울리는 포효, 96), breach(땅울림, 64), slap(꼬리 후려침, 32), snap(등지느러미 꺾임, 24).
"""
import math

import numpy as np

from gabe import kit

S = 0.135
DORSAL_Z = (3.0, 1.5, 0.0, -1.5, -3.0)
BACK_Y = 2.0


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def limb(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    up = abs(d[1]) > 0.97 * np.linalg.norm(d)
    return s.rod(tuple(a), tuple(d), length=float(np.linalg.norm(d)), segs=1, thick=thick, base=False, tex=tex, rag=False,
                 toward=(0, 0, 1) if up else (0, 1, 0))


def body(s, k, fins, tail_up=False):
    flesh = k.part_tex("flesh")
    s.head(size=5.2, height=4.6, depth=6.0, at=(0, 0, 5.0), face="smile", sides=flesh)
    for (z0, z1, w, h) in ((-2.2, 2.05, 4.6, 4.1), (-5.2, -2.0, 3.5, 3.2), (-7.8, -5.0, 2.3, 2.2)):
        s.box((-w / 2, -h / 2, z0), (w / 2, h / 2, z1), "flesh", face=True)
    # 배의 웃는 얼굴들(아래를 봄)
    for (x, z, sz, f) in ((0, 4.2, 4.0, "smile_a"), (0.2, -0.1, 3.2, "smile"), (-0.1, -3.6, 2.4, "smile_c")):
        y = -2.05 if z > -2 else (-1.6 if z > -5 else -1.1)
        s.quad((x, y - 0.03, z), (sz, sz), "down", k.face_tex(f), face=True)
    # 옆구리 얼굴 둘(가슴지느러미 뒤)
    for side in (1, -1):
        s.quad((side * 2.32, 0.2, -0.6), (2.2, 2.2), "east" if side > 0 else "west", k.face_tex("half"), face=True)
    # 등지느러미: 막대기 다섯 부채(꺾이면 그루터기)
    for i, z in enumerate(DORSAL_Z):
        rake = -0.35 - 0.1 * i
        top = np.array((0, BACK_Y + 3.0, z + rake * 3.0))
        root = np.array((0, BACK_Y + (0.3 if z > 2 else 0.0), z))
        with s.when(f"!on:fin{i}"):
            limb(s, root, top, 0.3)
        with s.when(f"on:fin{i}"):
            limb(s, root, root + (0, 0.5, -0.1), 0.3)
    # 가슴지느러미: 막대기 살 넷 + 얼굴 가죽 막
    for side in (1, -1):
        sh = np.array((side * 2.2, -0.6, 2.4))
        if fins == "spread":
            dirs = ((side, 0.12, 0.45, 6.4), (side, 0.18, 0.0, 6.9), (side, 0.12, -0.45, 6.2), (side, 0.02, -1.0, 5.2))
        else:
            dirs = ((side * 0.32, -0.05, -1, 4.2), (side * 0.36, -0.12, -1, 4.6), (side * 0.38, -0.2, -1, 4.4), (side * 0.36, -0.28, -1, 3.8))
        chains = []
        for dx, dy, dz, ln in dirs:
            d = _u((dx, dy, dz))
            chains.append(s.rod(tuple(sh), tuple(d), length=ln, segs=1, thick=0.24, base=False, rag=False))
        for a, b in zip(chains, chains[1:]):
            s.membrane(a, b, tex=k.face_tex("tears"), face=True)
    # 꼬리: 가시 여섯 부채, 끝마다 작은 얼굴
    tail = np.array((0, 0, -7.8))
    lift = 55 if tail_up else 0
    R = kit.rot_axis((1, 0, 0), lift)
    for j in range(6):
        a = math.radians(-50 + j * 20)
        d = R @ _u((0, math.sin(a), -math.cos(a)))
        tip = tail + d * 2.8
        limb(s, tail, tip, 0.22, "bone")
        s.quad(tuple(tip + d * 0.1), (0.8, 0.8), "east", k.face_tex("smile_a"), face=True, rot=(0, 0, 0))
        s.quad(tuple(tip + d * 0.1), (0.8, 0.8), "west", k.face_tex("smile_a"), face=True)
    if tail_up:
        # 쳐든 꼬리 밑동(살)
        s.box((-0.9, -0.9, -9.0), (0.9, 0.9, -7.6), "flesh", rot=(lift, 0, 0), origin=(0, 0, -7.8), face=True)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=S, mut2=False, torn=True)
    body(m.state(""), k, "fold")
    body(m.state("spread"), k, "spread")
    body(m.state("tail"), k, "fold", tail_up=True)
    k.icon(model="g107", state="spread")


def sounds(k: kit.Kit):
    k.sample("deep", "tag:far_roar", "멀리서 울리는 포효", pitch=0.5, attenuation=96)
    k.sample("breach", "tag:quake", "땅울림", pitch=0.75, attenuation=64)
    k.sample("slap", ["kenney_impact/impactpunch_heavy_001", "oga_sfx100/slam_02"], "꼬리 후려침", pitch=0.6, attenuation=32)
    k.sample("snap", ["oga_breaking75/bfh1_rock_breaking_02", "lrsf_horror/bonecrack_03"], "등지느러미 꺾임", pitch=0.75, attenuation=24)


def phrases():
    return {"cause_g107": "솟구친 물고기에 깔렸다", "cause_g107_tail": "땅속 꼬리에 후려맞았다", "cause_g107_rain": "물고기 배에서 쏟아진 얼굴 비"}
