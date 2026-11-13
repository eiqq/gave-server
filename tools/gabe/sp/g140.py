"""G140 항아리 게이브(4막, 지원, 정예): 배가 부른 높이 2.6 블록 살 독. 옹기 배에 _Gave 얼굴이 통째로 그려졌고 뚜껑은 엎은 작은 얼굴. 긴 엔드
막대기 둘을 A 자로 엮은 지게에 얹혀 있고 지게 다리 둘이 번갈아 땅을 짚어 걷는다(지게까지 키 3.6). 금 간 틈으로 안에 뒤엉킨 얼굴들이 보인다.

모델 gave:gabe/g140(shrink 0.5) 상태:
  ""        걷기: 지게 다리 둘 4자세, 뚜껑 닫힘
  "open"    뚜껑을 들어 올림(빨아들이기, 내놓기, 사람 덮기): 다리 가만
  "rattle"  걷기 + 뚜껑이 들썩(열리기 2초 전)
  부위 crack1..crack3: 금이 한 줄씩 더(체력), fed: 금 틈으로 안의 얼굴들이 보임(먹은 것이 있을 때)
가운데(몸 자리) = 독 배 가운데, 바닥 위 1.9(Java HOVER 1.9: 지게 다리 끝이 땅).
소리 gnaw(독 속에서 나는 소리, 32), suck(빨아들임, 24), lid(뚜껑, 16), out(튀어나옴, 32), crack(독이 깨짐, 32).
"""
import math

import numpy as np

from gabe import kit

FOOT = -1.9


def dark_tex(n=8):
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = (44, 12, 14)
    out[..., 3] = 255
    return out


def jar(s, k):
    fl = k.part_tex("flesh")
    s.box((-0.62, -1.15, -0.62), (0.62, -0.65, 0.62), fl, face=True)
    s.box((-1.0, -0.68, -0.95), (1.0, 0.62, 0.95), fl, faces=("east", "west", "north", "up", "down"), face=True)
    s.head(size=2.0, height=1.3, depth=0.1, at=(0, -0.03, 0.9), face="deep")
    s.box((-0.78, 0.6, -0.75), (0.78, 0.98, 0.75), fl, face=True)
    s.box((-0.5, 0.96, -0.5), (0.5, 1.22, 0.5), fl, face=True)
    dk = k.texture("crack", dark_tex())
    cracks = (((0.95, -0.4), (0.55, 0.2)), ((-0.96, 0.1), (-0.5, 0.45)), ((0.2, 0.55), (0.75, -0.1)))
    for i, ((x0, y0), (x1, y1)) in enumerate(cracks):
        with s.when("on:crack%d" % (i + 1)):
            a, b = np.array((x0, y0, 0.965)), np.array((x1, y1, 0.965))
            d = b - a
            s.rod(tuple(a), tuple(d), length=float(np.linalg.norm(d)), segs=1, thick=0.06, base=False, rag=False, tex=dk, toward=(0, 0, 1))
    # 금 틈 안쪽의 얼굴들(먹은 것이 있을 때): 옆 금 사이로 작은 얼굴 둘
    with s.when("on:fed"):
        s.quad((1.005, -0.15, 0.3), (0.4, 0.4), "east", k.face_tex("tears"), face=True)
        s.quad((-1.005, 0.2, -0.25), (0.4, 0.4), "west", k.face_tex("bloodshot"), face=True)


def lid(s, k, lift=0.0, tilt=0.0):
    s.head(size=0.95, height=0.42, depth=0.95, at=(0, 1.42 + lift, -0.1 * (tilt / 40)), face="flipped", rot=(-tilt, 0, 0), near=False, open=False,
           torn=False)


def jige(s, k, walk):
    """A 자 지게: 다리 둘(아래, 걸음) + 독 뒤로 솟아 교차하는 장대 둘 + 가로대"""
    for side in (1, -1):
        hip = (side * 0.55, -0.9, -1.05)
        w = kit.Wave(amp=18, phase=0 if side > 0 else 180) if walk else None
        s.rod(hip, (side * 0.25, -1, 0.02), length=-FOOT - 0.9, segs=1, thick=0.13, wave=w, toward=(0, 0, 1), poses=4 if walk else None, rag=walk)
        top = (-side * 0.25, 1.75, -1.05)
        d = np.array(top) - np.array(hip)
        s.rod(hip, tuple(d), length=float(np.linalg.norm(d)), segs=1, thick=0.13, base=False, rag=False, toward=(0, 0, 1))
    s.rod((-0.75, -0.4, -1.05), (1, 0, 0), length=1.5, segs=1, thick=0.1, base=False, rag=False, toward=(0, 0, 1))
    s.rod((-0.5, 0.6, -1.05), (1, 0, 0), length=1.0, segs=1, thick=0.1, base=False, rag=False, toward=(0, 0, 1))


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, shrink=0.5, mut2=False)
    s = m.state("")
    jar(s, k)
    lid(s, k)
    jige(s, k, True)

    o = m.state("open", poses=1)
    jar(o, k)
    lid(o, k, 0.55, 40)
    jige(o, k, False)

    r = m.state("rattle")
    jar(r, k)
    for f in r.frames():
        with f:
            lid(r, k, 0.12 * abs(math.sin(math.pi * 2 * f.t)), 10 * math.sin(math.pi * 2 * f.t))
    jige(r, k, True)


def sounds(k: kit.Kit):
    k.sample("gnaw", ["oga_creature80/eat_01", "oga_creature80/eat_04", {"id": "kenney_rpg/metalpot1", "pitch": 0.6}], "독 속에서 나는 소리",
             pitch=0.7, attenuation=32)
    k.sample("suck", "oga_steam/steam_hisses_marker_*", "빨아들임", pitch=0.5, attenuation=24)
    k.sample("lid", "kenney_rpg/metalpot*", "뚜껑", pitch=0.7, attenuation=16)
    k.sample("out", "tag:growl", "튀어나옴", pitch=0.7, attenuation=32)
    k.sample("crack", "kenney_impact/impactplate_heavy_00*", "독이 깨짐", pitch=0.6, attenuation=32)


def phrases():
    return {"cause_g140": "항아리에 들이받혔다", "cause_g140_jar": "항아리 속에서 뜯겼다"}
