"""G194 바닥 밑 게이브(4막, 돌격, 목표 압박: 지름 7 블록 가시 왕관 + 3 블록 얼굴이 땅에서 솟음).

솟았을 때: 지름 5 블록 고리로 엔드 막대기 가시 열둘이 왕관처럼 땅에서 비스듬히 바깥으로 솟고(끝은 지름 7 블록), 한가운데에 3 블록 _Gave 얼굴이
턱까지 올라온다. 얼굴 정수리에 깨진 바닥 조각이 모자처럼 얹혔다. 땅속에 있을 때는 트럭 둘레 바닥이 볼록 솟았다 꺼지는 혹만 보인다.

- gave:gabe/g194(shrink 0.4): 몸 가운데(0, 0, 0) = 엔진 pos = 얼굴 가운데, 바닥 y -1.5(Java HOVER 1.5). 상태 ""(가시 펼침), "fold"(가시를 안으로 접음).
  부위 nocap(바닥 조각이 깨져 없음).
- gave:gabe/g194_bump: 볼록 솟은 바닥(바닥 y 0, 지름 2.4, 가운데 (0, 0, 0)).
소리 knock(바닥 밑 쿵쿵, 32), rise(가시 왕관이 솟음, 48), sweep(가시 쓸기, 32), bite(얼굴이 묾, 24), ram(트럭 들이받음, 40), crack(바닥 조각 깨짐, 32).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.4
GROUND = -1.5
RING = 2.5
SPIKES = 12
HEAD = 3.0


def tint(img, rgb, keep=0.12, gain=1.1):
    a = np.asarray(img).astype(float)
    lum = (0.3 * a[..., 0] + 0.59 * a[..., 1] + 0.11 * a[..., 2]) / 255.0
    out = a.copy()
    for c in range(3):
        out[..., c] = np.clip(lum * rgb[c] * gain * (1 - keep) + a[..., c] * keep, 0, 255)
    out[..., 3] = 255
    return out.astype(np.uint8)


def crown(s, fold):
    for i in range(SPIKES):
        a = 2 * math.pi * i / SPIKES
        ca, sa = math.cos(a), math.sin(a)
        root = (RING * ca, GROUND + 0.05, RING * sa)
        if fold:
            d = (-ca * 0.55, 1.0, -sa * 0.55)
            L = 2.3
        else:
            d = (ca * 0.5, 1.0, sa * 0.5)
            L = 2.6 if i % 2 == 0 else 2.1
        s.rod(root, d, length=L, segs=1, thick=0.2, base=True, toward=(0, 1, 0) if abs(d[1]) < 0.9 else (-sa, 0, ca), rag=False)


def body(s, k, cap_tex, fold):
    crown(s, fold)
    # 고리 밑동: 갈라진 바닥 테(살)
    for i in range(SPIKES):
        a = 2 * math.pi * (i + 0.5) / SPIKES
        c = (RING * math.cos(a), GROUND + 0.12, RING * math.sin(a))
        s.box((c[0] - 0.55, GROUND, c[2] - 0.32), (c[0] + 0.55, GROUND + 0.24, c[2] + 0.32), "flesh", faces=("up", "north", "south", "east", "west"),
              rot=(0, -math.degrees(a) + 90, 0), origin=c, face=True)
    s.head(size=HEAD, depth=2.3, at=(0, 0.0, 0), face="smile", near=False)
    with s.when("!on:nocap"):
        s.box((-1.35, HEAD / 2, -1.2), (1.25, HEAD / 2 + 0.38, 1.1), cap_tex, uv=[0, 0, 16, 16], rot=(7, 12, -6), origin=(0, HEAD / 2, 0), face=True)
        s.box((0.3, HEAD / 2 + 0.3, -0.5), (1.0, HEAD / 2 + 0.55, 0.3), cap_tex, uv=[0, 0, 8, 8], rot=(-12, 30, 10), origin=(0.6, HEAD / 2 + 0.4, 0),
              face=True)
    with s.when("on:nocap"):
        s.box((-0.5, HEAD / 2, -0.4), (0.4, HEAD / 2 + 0.15, 0.5), cap_tex, uv=[0, 0, 8, 8], rot=(0, 25, 8), origin=(0, HEAD / 2, 0), face=True)
    s.front = (0, 0, 1)


def bump(k, cap_tex):
    m = k.model("g194_bump", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    for (r, h) in ((1.25, 0.18), (0.9, 0.36), (0.5, 0.52)):
        for a in (0, 45):
            s.box((-r, 0, -r), (r, h, r), cap_tex, uv=[0, 0, 16, 16], faces=("up", "north", "south", "east", "west"), rot=(0, a, 0), origin=(0, 0, 0))
    # 갈라진 틈으로 비치는 살
    s.box((-0.12, 0.5, -0.7), (0.12, 0.56, 0.7), "flesh", faces=("up",), rot=(0, 30, 0), origin=(0, 0.5, 0))
    s.front = (0, 0, 0)


def build(k: kit.Kit):
    cap_tex = k.texture("slab", tint(faces.skin_side("top"), np.array((128, 124, 118), float)))
    m = k.model(poses=1, period=20, shrink=S, mut2=False, torn=True)
    body(m.state(""), k, cap_tex, False)
    body(m.state("fold"), k, cap_tex, True)
    bump(k, cap_tex)
    k.icon(model="g194", state="")


def sounds(k: kit.Kit):
    k.sample("knock", "tag:punch", "바닥 밑 쿵쿵", pitch=0.55, attenuation=32)
    k.sample("rise", "tag:quake", "가시 왕관이 솟음", pitch=0.7, attenuation=48)
    k.sample("sweep", "tag:blade", "가시 쓸기", pitch=0.6, attenuation=32)
    k.sample("bite", "tag:monster+eat", "바닥 밑 얼굴이 묾", pitch=0.55, attenuation=24)
    k.sample("ram", "tag:metal+heavy", "우주선을 들이받음", pitch=0.6, attenuation=40)
    k.sample("crack", "tag:stone", "바닥 조각이 깨짐", pitch=0.8, attenuation=32)


def phrases():
    return {"cause_g194": "바닥 밑에서 솟은 가시 왕관에 튕겨 나갔다", "cause_g194_sweep": "바닥 밑 게이브의 가시에 쓸렸다",
            "cause_g194_bite": "바닥 밑 게이브에게 물렸다", "notice_g194": "발밑에서 두드리는 소리가 난다"}
