"""G100 가마 게이브(2막, 탈것: 가마채 끝까지 길이 4.6 블록).

얼굴 넷을 벽 삼아 짠 폭 2.6 블록 가마. 지붕은 엎은 얼굴, 앞에는 살 띠 발이 드리웠다(흔들림). 긴 엔드 막대기 둘이 가마채로 앞뒤로 뻗고,
가마꾼 넷(0.7 블록 얼굴에 막대기 다리 둘, 부품)이 가마채를 정수리에 얹고 종종걸음으로 간다. 길은 바닥에 그려지는 흰 줄(g100_lane).

- gave:gabe/g100(shrink 0.42): 가마 가운데(0, 0, 0) = 엔진 pos, 바닥에서 HOVER 2.1(Java G100.HOVER). 가마채 (±POLE_X, POLE_Y, z ±2.3).
  상태 ""(발이 드리움), "grab"(발이 걷히고 안에서 긴 살 띠가 나와 끌어들임), "down"(내려앉음: 발이 없고 앞 벽이 열림, 우는 얼굴).
- gave:gabe/g100_bearer: 가마꾼 하나(얼굴 가운데 (0, 0, 0), 발바닥 y -1.25). 상태 ""(종종걸음 8틱 자세 4).
- gave:gabe/g100_lane: 바닥 흰 길(폭 1, 길이 1: 뒤 끝 z = 0 에서 +Z 로 뻗음, 윗면만). Java 가 (4, 1, 길이) 로 늘임.
소리 chant(가마 메는 소리, 40), grab(끌어들임, 24), drop(내려앉음, 32).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.42
HOVER = 2.1
BOX = (2.6, 2.3, 2.6)
POLE_X, POLE_Y, POLE_Z = 1.45, -0.55, 2.3
BEARER = ((1.45, -0.95, 1.95), (-1.45, -0.95, 1.95), (1.45, -0.95, -1.95), (-1.45, -0.95, -1.95))


def lane_img(n=32):
    out = np.zeros((n, n, 4), np.uint8)
    yy, xx = np.mgrid[0:n, 0:n]
    edge = (xx < 3) | (xx >= n - 3)
    dash = ((yy // 4) % 2 == 0) & (np.abs(xx - n / 2 + 0.5) < 2)
    m = edge | dash
    out[m] = (238, 234, 226, 255)
    # 옅은 점(가운데 바닥이 비쳐 보이게 듬성듬성)
    dots = ((xx * 7 + yy * 13) % 17 == 0) & ~m
    out[dots] = (238, 234, 226, 255)
    return out


def palanquin(s, k, mode):
    W, H, D = BOX
    wall = {"south": None, "north": None, "east": None, "west": None}
    s.box((-W / 2, -H / 2, -D / 2), (W / 2, H / 2, D / 2), "wetskin", face=True)
    # 얼굴 벽 넷(앞 벽은 내려앉으면 우는 얼굴)
    ff = {"south": "tears" if mode == "down" else "smile", "north": "half", "east": "stitch", "west": "smile_c"}
    for facing, (c, sz) in {"south": ((0, 0.05, D / 2 + 0.02), (W - 0.3, H - 0.3)), "north": ((0, 0.05, -D / 2 - 0.02), (W - 0.3, H - 0.3)),
                            "east": ((W / 2 + 0.02, 0.05, 0), (D - 0.3, H - 0.3)), "west": ((-W / 2 - 0.02, 0.05, 0), (D - 0.3, H - 0.3))}.items():
        s.quad(c, sz, facing, k.face_tex(ff[facing]), face=True)
    # 지붕: 엎은 얼굴(위를 봄) + 처마
    s.box((-W / 2 - 0.25, H / 2, -D / 2 - 0.25), (W / 2 + 0.25, H / 2 + 0.25, D / 2 + 0.25), "flesh", face=True)
    s.quad((0, H / 2 + 0.27, 0), (W, D), "up", k.face_tex("flipped"), face=True)
    s.box((-0.35, H / 2 + 0.25, -0.35), (0.35, H / 2 + 0.6, 0.35), "flesh", face=True)
    # 가마채 둘(엔드 막대기, 앞뒤로 뻗음) + 가로대
    for x in (POLE_X, -POLE_X):
        s.rod((x, POLE_Y, -POLE_Z), (0, 0, 1), length=2 * POLE_Z, segs=2, thick=0.2, base=False, rag=False)
    s.box((-POLE_X, POLE_Y - 0.08, -0.9), (POLE_X, POLE_Y + 0.08, -0.7), "rod")
    s.box((-POLE_X, POLE_Y - 0.08, 0.7), (POLE_X, POLE_Y + 0.08, 0.9), "rod")
    # 앞 발(살 띠 셋): 드리움 / 걷힘 / 없음
    if mode == "":
        for x in (-0.75, 0.0, 0.75):
            s.band((x, H / 2 - 0.05, D / 2 + 0.12), (0, -1, 0.05), length=H - 0.3, width=0.6, segs=2, thick=0.02,
                   wave=kit.Wave(amp=(6, 10), lag=50, phase=x * 90), toward=(0, 0, 1), poses=4)
    elif mode == "grab":
        for x in (-0.75, 0.0, 0.75):
            s.band((x, H / 2 - 0.05, D / 2 + 0.12), (0, -0.35, 1), length=0.6, width=0.6, segs=1, thick=0.02, toward=(0, 1, 0))
        # 안에서 나오는 긴 살 띠(사람을 감아 끌어들임)
        s.band((0, -0.2, D / 2), (0, -0.5, 1), length=2.2, width=0.5, segs=3, thick=0.05, wave=kit.Wave(amp=(20, 25, 30), lag=40, cycles=2),
               toward=(0, 1, 0), poses=4)
    else:
        dark = np.zeros((8, 8, 4), np.uint8)
        dark[...] = (34, 14, 16, 255)
        s.quad((0, -0.1, D / 2 + 0.05), (1.2, 1.5), "south", k.texture("inside", dark))


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=S, mut2=False, torn=True)
    for st in ("", "grab", "down"):
        s = m.state(st)
        palanquin(s, k, st)
    # 가마꾼(종종걸음)
    bm = k.model("g100_bearer", poses=4, period=8, mut2=False, torn=False, rag=False)
    bs = bm.state("")
    bs.head(size=0.7, depth=0.6, face="smile_a", near=True)
    bs.box((-0.32, 0.33, -0.25), (0.32, 0.4, 0.25), "flesh", face=True)
    for f in bs.frames():
        with f:
            for i, x in enumerate((0.16, -0.16)):
                ph = f.t * 2 * math.pi + (math.pi if i else 0)
                swing = 0.3 * math.sin(ph)
                lift = max(0.0, 0.18 * math.sin(ph))
                foot = np.array((x, -1.25 + lift, swing))
                hip = np.array((x * 0.8, -0.3, 0.0))
                d = foot - hip
                bs.rod(tuple(hip), tuple(d), length=float(np.linalg.norm(d)), segs=1, thick=0.1, base=False, toward=(0, 0, 1), rag=False)
    bs.front = (0, 0, 1)
    # 바닥 흰 길
    lm = k.model("g100_lane", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    ls = lm.state("")
    ls.quad((0, 0.01, 0.5), (1, 1), "up", k.texture("lane", lane_img()))
    ls.front = (0, 0, 0)
    k.icon(model="g100", state="")


def sounds(k: kit.Kit):
    k.sample("chant", "tag:voice+grunt", "가마 메는 소리", pitch=0.8, attenuation=40)
    k.sample("grab", ["oga_creature80/burble_01", "oga_creature80/burble_02"], "가마 안으로 끌어들임", pitch=0.7, attenuation=24)
    k.sample("drop", ["kenney_impact/impactsoft_heavy_000", "kenney_rpg/creak2"], "가마가 내려앉음", pitch=0.6, attenuation=32)


def phrases():
    return {"cause_g100": "가마에 태워져 갔다", "cause_g100_step": "가마꾼에게 밟혔다"}
