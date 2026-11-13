"""G131 묻힌 얼굴 게이브(3막, 땅에 묻힌 것: 폭 9, 이마까지 높이 7 블록 돌빛 얼굴).

땅에 턱까지 묻힌 석상 _Gave 얼굴(금이 가고 얼룩진 돌빛). 정수리에 굵은 엔드 막대기 둘이 향처럼 꽂혀 끝에서 분홍 연기가 오른다(자세 4).
눈은 실눈으로 감겨 있고, 뜰 때 눈동자가 위나 아래로 쏠린다. 체력이 줄면 턱 밑 돌기둥 다리 둘(부품)로 일어선다.

- gave:gabe/g131(shrink 0.19): 보이는 얼굴 상자 가운데(0, 0, 0) = 엔진 pos, 바닥에서 HOVER 3.5(Java G131.HOVER). 앞(+Z)이 얼굴.
  상태 ""(감은 눈), "up"(눈 뜸, 눈동자 위로), "down"(눈 뜸, 눈동자 아래로). 부위 inc0, inc1: 그 향 막대기가 꺾임(그루터기, 연기 없음),
  risen: 일어섬(땅 갈라진 흙 테가 사라짐).
- gave:gabe/g131_leg(shrink 0.34): 돌기둥 다리. 엉덩이 (0, 0, 0), 발바닥 (0, -LEG_L, 0).
소리 gong(징, 96), rumble(땅울림, 96), crack(향 꺾임, 48), step(돌 걸음, 64).
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

S = 0.19
HOVER = 3.5
W, H, D = 9.0, 7.0, 6.5
INC_X, INC_Z = 1.4, -0.6
LEG_L = 4.0

EYE_UP = [
    "..EEEE..",
    ".EwiiwE.",
    "Ewippiww",
    "wwiippww",
    "wwwiiwww",
    "wwwwwwww",
    ".wwwwww.",
    "..wwww..",
]
EYE_DOWN = [
    "..EEEE..",
    ".EwwwwE.",
    "Ewwwwwww",
    "wwwwwwww",
    "wwwiiwww",
    "wwiippww",
    ".wippiw.",
    "..wiiw..",
]


def stone(rgb, seed):
    """살빛 그림 → 금 가고 얼룩진 돌빛(회갈색). rgb: (n, n, 3) 0..255"""
    n = rgb.shape[0]
    lum = rgb[..., 0] * 0.3 + rgb[..., 1] * 0.59 + rgb[..., 2] * 0.11
    rng = np.random.RandomState(seed)
    noise = rng.rand(n // 4 + 1, n // 4 + 1)
    noise = np.kron(noise, np.ones((4, 4)))[:n, :n]
    g = lum * 0.62 + 18 + (noise - 0.5) * 22
    out = np.stack([g * 1.04 + 6, g * 1.0 + 3, g * 0.93], -1)
    # 얼룩(짙은 물때)
    yy, xx = np.mgrid[0:n, 0:n]
    for _ in range(5):
        cx, cy, r = rng.uniform(0, n), rng.uniform(n * 0.3, n), rng.uniform(n * 0.08, n * 0.2)
        m = np.hypot(xx - cx, (yy - cy) * 0.6) < r
        out[m] *= 0.82
    return out


def cracks(out, seed, count=7):
    n = out.shape[0]
    rng = np.random.RandomState(seed)
    for _ in range(count):
        x, y = rng.uniform(0, n), rng.uniform(0, n * 0.4) if rng.rand() < 0.6 else rng.uniform(0, n)
        a = rng.uniform(0.6, 2.5)
        for _ in range(int(n * 0.45)):
            ix, iy = int(x), int(y)
            if 0 <= ix < n and 0 <= iy < n:
                out[iy, ix] = (64, 58, 54)
                if ix + 1 < n:
                    out[iy, ix + 1] = out[iy, ix + 1] * 0.7
            a += rng.uniform(-0.5, 0.5)
            x += math.cos(a)
            y += abs(math.sin(a)) + 0.2


def face_img(eyes, seed):
    c = faces.draw(faces.Face(eyes="none" if eyes != "squint" else "squint"))
    if eyes in ("up", "down"):
        rows = EYE_UP if eyes == "up" else EYE_DOWN
        for side, ex in enumerate(faces.EYE_X):
            c.stamp(rows, ex, faces.EYE_Y, mirror=bool(side))
    a = c.rgba()
    big = np.kron(a, np.ones((2, 2, 1))).astype(np.float64)  # 64x64(금을 가늘게)
    rgb = stone(big[..., :3], seed)
    # 눈 뜬 흰자와 눈동자는 또렷하게(멀리서 쏠림이 보이게)
    cls = np.kron(c.cls, np.ones((2, 2), np.uint8))
    white = cls == faces.CLS_WHITE
    rgb[white] = (222, 214, 196)
    pupil = (big[..., 0] < 60) & (cls == faces.CLS_EYE)
    rgb[pupil] = (24, 8, 8)
    iris = (cls == faces.CLS_EYE) & ~pupil & (big[..., 0] > 100)
    rgb[iris] = (120, 30, 26)
    cracks(rgb, seed + 1, 4)
    out = np.zeros((64, 64, 4), np.uint8)
    out[..., :3] = np.clip(rgb, 0, 255)
    out[..., 3] = 255
    return out


def plain_img(seed, n=32):
    base = np.full((n, n, 3), 200.0)
    rgb = stone(base, seed)
    cracks(rgb, seed + 3, 4)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(rgb, 0, 255)
    out[..., 3] = 255
    return out


def dirt_img(seed=131, n=16):
    rng = np.random.RandomState(seed)
    base = np.array((92, 64, 44), float)
    rgb = base[None, None, :] * (0.7 + 0.5 * rng.rand(n, n, 1))
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(rgb, 0, 255)
    out[..., 3] = 255
    return out


def smoke_img(n=16):
    yy, xx = np.mgrid[0:n, 0:n]
    r = np.hypot(xx - 7.5, yy - 7.5) / 8
    out = np.zeros((n, n, 4), np.uint8)
    out[..., 0], out[..., 1], out[..., 2] = 255, 160, 196
    out[..., 3] = np.where(r < 0.85 - 0.12 * ((xx + yy) % 3 == 0), 255, 0)
    return out


def face(s, k, ftex, side, top, dirt, smoke):
    # 얼굴 상자: 앞은 돌 얼굴, 옆과 위는 금 간 돌, 아래(땅속)는 안 그림
    s.box((-W / 2, -H / 2, -D / 2), (W / 2, H / 2, D / 2), {"south": ftex, "east": side, "west": side, "north": side, "up": top},
          faces=("south", "east", "west", "north", "up"), uv=[0, 0, 16, 16])
    # 땅 갈라진 흙 테(묻힌 동안만)
    with s.when("!on:risen"):
        for i in range(10):
            a = math.radians(i * 36)
            r = 5.2 if i % 2 else 4.6
            cx, cz = math.cos(a) * r * 1.05, math.sin(a) * r * 0.85
            s.box((cx - 1.1, -H / 2 - 0.05, cz - 0.9), (cx + 1.1, -H / 2 + 0.35 + 0.25 * (i % 3), cz + 0.9), dirt, rot=(0, -i * 36, 8 if i % 2 else -6),
                  origin=(cx, -H / 2, cz))
    # 향 막대기 둘 + 분홍 연기(자세 4: 오르며 흩어짐)
    for i, x in enumerate((INC_X, -INC_X)):
        with s.when(f"!on:inc{i}"):
            s.rod((x, H / 2 - 0.2, INC_Z), (0.08 * (1 if i else -1), 1, 0), length=2.3, segs=1, thick=0.42, base=True, toward=(0, 0, 1), rag=False)
            for f in s.frames(poses=4):
                with f:
                    for j in range(3):
                        u = (f.t + j / 3.0) % 1.0
                        y = H / 2 + 2.3 + u * 1.6
                        sz = 0.4 + 0.7 * u
                        s.quad((x + 0.15 * math.sin(6.28 * u + i), y, INC_Z), (sz, sz), "south", smoke)
                        s.quad((x + 0.15 * math.sin(6.28 * u + i), y, INC_Z), (sz, sz), "east", smoke)
        with s.when(f"on:inc{i}"):
            s.rod((x, H / 2 - 0.2, INC_Z), (0.3, 1, 0.2), length=0.5, segs=1, thick=0.42, base=True, rag=False)


def build(k: kit.Kit):
    side = k.texture("stone_side", plain_img(131))
    top = k.texture("stone_top", plain_img(132))
    dirt = k.texture("dirt", dirt_img())
    smoke = k.texture("smoke", smoke_img())
    m = k.model(poses=4, period=40, shrink=S, mut2=False, open=False, torn=False, rag=True)
    for st, eyes in (("", "squint"), ("up", "up"), ("down", "down")):
        ftex = k.texture("face_" + (st or "shut"), face_img(eyes, 131))
        s = m.state(st)
        face(s, k, ftex, side, top, dirt, smoke)
        s.front = (0, 0, 1)
    lm = k.model("g131_leg", poses=1, period=20, shrink=0.34, mut2=False, open=False, torn=False, rag=False)
    ls = lm.state("")
    ls.box((-0.9, -LEG_L + 0.6, -0.9), (0.9, 0.2, 0.9), {n: side for n in ("north", "south", "east", "west", "up", "down")}, uv=[0, 0, 16, 16])
    ls.box((-1.15, -LEG_L, -1.15), (1.15, -LEG_L + 0.7, 1.4), {n: side for n in ("north", "south", "east", "west", "up", "down")}, uv=[0, 0, 16, 16])
    ls.front = (0, 0, 0)
    k.icon(model="g131", state="up")


def sounds(k: kit.Kit):
    k.sample("gong", "tag:gong", "징", pitch=0.55, attenuation=128, level=-10)
    k.sample("rumble", "tag:quake", "땅울림", pitch=0.6, attenuation=96)
    k.sample("crack", ["oga_breaking75/bfh1_rock_breaking_01", "kenney_impact/impactmining_000"], "향 막대기 꺾임", pitch=0.7, attenuation=48)
    k.sample("step", "kenney_impact/impactmining_00*", "돌 걸음", pitch=0.5, attenuation=64)


def phrases():
    return {"cause_g131": "묻힌 얼굴과 눈이 마주쳤다", "cause_g131_step": "일어선 돌 얼굴에 밟혔다",
            "mark_g131_up": "웅크려", "mark_g131_down": "일어서"}
