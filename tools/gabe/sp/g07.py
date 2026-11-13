"""G07 재화 흉내 게이브: 바닥 재화(금화)인 척 빙글 도는 동전. 실제로는 얼굴을 반으로 접어 감추고 막대기 둘을 칭칭 감은 꼴.

상태: ""(동전: 세운 금화, 둘레에 막대기 둘이 감겨 아주 작게 움찔, 앞뒤 무늬는 감은 눈꺼풀. 3블록 안에서는 금빛 살결과 속눈썹이 보임),
"open"(펴짐: 0.55 얼굴, 뒤통수와 옆은 금빛 껍데기, 막대기 둘이 발목 쪽으로 뻗어 퍼덕). 실눈 뜸(표적)은 키트가 붙임.
"real"(진짜 금화: 같은 그림, 감긴 막대기 무늬가 움찔하지 않음), "half"(겨눠 쏘아 반으로 깨진 진짜 금화). 진짜는 Java G07Coin 이 쓴다.
미리 깔리는 종이라 오래 살면 변함 규칙에서 빠지므로 입 찢어짐, 변이 2 막대기는 끔.
"""
import math

import numpy as np
from PIL import Image

from gabe import audio, faces, kit

R = 0.27        # 동전 반지름(블록)
T = 0.035       # 동전 반두께
GOLD = (222, 172, 58)
GOLD_D = (168, 118, 30)
GOLD_L = (250, 214, 112)
LID = (120, 80, 18)


def _disc_mask(n, inner=0.0):
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    r = np.hypot(xx + 0.5 - n / 2, yy + 0.5 - n / 2) / (n / 2)
    return r


def coin_far():
    """32x32 동전 앞면: 금빛 바탕, 둘레 테, 가운데 감은 눈꺼풀 둘과 점 입(재화 무늬처럼 보이는 얼굴)"""
    n = 32
    r = _disc_mask(n)
    out = np.zeros((n, n, 4), np.uint8)
    rgb = np.zeros((n, n, 3), np.float32)
    rgb[:] = GOLD
    yy, xx = np.mgrid[0:n, 0:n]
    # 위 왼쪽이 밝은 금, 아래 오른쪽 어두운 금
    shade = ((xx + yy) / (2 * n) - 0.5) * -60
    rgb += shade[..., None]
    rim = (r > 0.80) & (r <= 1.0)
    rgb[rim] = GOLD_D
    rgb[(r > 0.86) & (r <= 0.92)] = GOLD_L
    # 감은 눈꺼풀(실눈 줄을 금화 무늬처럼): faces.EYES squint 를 줄여 찍음
    eyes = faces.EYES["squint"]
    for side, ex in enumerate((7, 17)):
        for dy, row in enumerate(eyes):
            for dx, ch in enumerate(row):
                if ch == ".":
                    continue
                sx = 7 - dx if side else dx
                x, y = ex + sx, 11 + dy
                if 0 <= x < n and 0 <= y < n and r[y, x] < 0.8:
                    rgb[y, x] = LID if ch in "Ee" else GOLD_L
    for (x, y) in ((15, 21), (16, 21), (15, 22), (16, 22)):
        rgb[y, x] = LID
    out[..., :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    out[..., 3] = np.where(r <= 1.0, 255, 0).astype(np.uint8)
    return out


def coin_near():
    """128x128 가까운 동전: 금빛으로 물든 사람 살결(솜털, 땀구멍)과 감은 눈꺼풀, 테두리는 같은 금 테"""
    n = 128
    face = faces.near(faces.Face(eyes="squint", blush="none", tufts=False)).astype(np.float32)
    lum = face[..., :3].mean(axis=2, keepdims=True) / 255.0
    tint = np.array(GOLD, np.float32)[None, None, :]
    rgb = np.clip(tint * (0.45 + 0.75 * lum), 0, 255)
    r = _disc_mask(n)
    rgb[(r > 0.86) & (r <= 1.0)] = GOLD_D
    rgb[(r > 0.9) & (r <= 0.95)] = GOLD_L
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = rgb.astype(np.uint8)
    out[..., 3] = np.where(r <= 1.0, 255, 0).astype(np.uint8)
    return out


def rim_tex():
    n = 16
    out = np.zeros((n, n, 4), np.uint8)
    for y in range(n):
        for x in range(n):
            c = GOLD_D if (x + y) % 5 else GOLD_L
            out[y, x, :3] = c
            out[y, x, 3] = 255
    return out


def shell_tex():
    """펴진 얼굴의 뒤통수, 옆: 금빛 껍데기(동전이던 겉)에 감은 눈꺼풀 무늬가 비침"""
    n = 32
    out = np.zeros((n, n, 4), np.uint8)
    yy, xx = np.mgrid[0:n, 0:n]
    rgb = np.zeros((n, n, 3), np.float32)
    rgb[:] = GOLD
    rgb += (((xx * 7 + yy * 3) % 11) / 11.0 - 0.5)[..., None] * 30
    for x in range(4, 28):
        y = int(16 + 3 * math.sin((x - 4) / 24 * math.pi))
        rgb[y, x] = LID
    out[..., :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    out[..., 3] = 255
    return out


def _curl(s, side, a0, z, twitch):
    """동전 둘레에 감긴 막대기 하나(원을 따라 마디마다 꺾임). twitch 면 가끔 움찔(가짜), 아니면 같은 꼴로 가만히(진짜)"""
    a = math.radians(a0)
    root = (R * 0.98 * math.cos(a), R * 0.98 * math.sin(a), z)
    tang = (-math.sin(a) * side, math.cos(a) * side, 0.0)
    inward = (-math.cos(a), -math.sin(a), 0.0)
    seg = 0.16
    turn = math.degrees(seg / R)
    bias = (turn * 0.5, turn, turn)
    if twitch:
        s.rod(root, tang, length=seg * 3, segs=3, thick=0.05, base=False, toward=inward,
              wave=kit.Wave(amp=(6, 8, 10), lag=40, bias=bias, shape="twitch", phase=0 if side > 0 else 180), poses=4)
        return
    f = kit.unit(np.cross(kit.v3(tang), kit.v3(inward)))
    pt = kit.v3(root)
    cum = 0.0
    for i, bi in enumerate(bias):
        cum += bi
        d = kit.rot_axis(f, cum) @ kit.unit(tang)
        s.rod(tuple(pt), tuple(d), length=seg * (1.04 if i < 2 else 1.0), segs=1, thick=0.05, base=False, toward=(0, 0, 1))
        pt = pt + d * seg


def _coin(s, far, near, rim, twitch):
    """세운 금화: 앞뒤 무늬(먼 32, 가까운 128), 옆 테, 둘레에 감긴 막대기 둘(원형 DNA)"""
    s.box((-R, -R, -T), (R, R, T), {"south": far, "north": far}, faces=("south", "north"),
          uv={"south": [0, 0, 16, 16], "north": [16, 0, 0, 16]}, dist="far", face=True)
    s.box((-R * 0.97, -R * 0.97, -T), (R * 0.97, R * 0.97, T), rim, faces=("east", "west", "up", "down"), uv=[0, 0, 16, 2])
    ep = 1.0 / 16
    s.quad((0, 0, T + 0.1 * ep), (2 * R, 2 * R), "south", near, dist="near", face=True)
    s.quad((0, 0, -T - 0.1 * ep), (2 * R, 2 * R), "north", near, uv=(16, 0, 0, 16), dist="near", face=True)
    for side, a0, z in ((1, 200.0, T + 0.03), (-1, 20.0, -T - 0.03)):
        _curl(s, side, a0, z, twitch)


def _half(s, far, near, rim):
    """반으로 깨진 금화: 왼쪽 반만 남고 깨진 면은 톱니처럼 어긋난 두 조각"""
    ep = 1.0 / 16
    for (y0, y1, x1) in ((-R, 0.0, 0.03), (0.0, R, -0.04)):
        v0, v1 = 8 - y1 / R * 8, 8 - y0 / R * 8
        u1 = 8 + x1 / R * 8
        s.box((-R, y0, -T), (x1, y1, T), {"south": far, "north": far}, faces=("south", "north"),
              uv={"south": [0, v0, u1, v1], "north": [u1, v0, 0, v1]}, dist="far", face=True)
        s.box((-R * 0.97, y0, -T), (x1, y1, T), rim, faces=("east", "west", "up", "down"), uv=[0, 0, 16, 2])
        w = x1 + R
        s.quad((-R + w / 2, (y0 + y1) / 2, T + 0.1 * ep), (w, y1 - y0), "south", near, uv=(0, v0, u1, v1), dist="near", face=True)
        s.quad((-R + w / 2, (y0 + y1) / 2, -T - 0.1 * ep), (w, y1 - y0), "north", near, uv=(u1, v0, 0, v1), dist="near", face=True)


def build(k: kit.Kit):
    m = k.model(poses=6, period=24, mut2=False, torn=False)
    far = k.texture("coin", coin_far())
    near = k.texture("coin_n", coin_near())
    rim = k.texture("rim", rim_tex())
    shell = k.texture("shell", shell_tex())

    # ---------------------------------------------------------------- 동전(접힘), 진짜 금화, 깨진 금화
    _coin(m.state(""), far, near, rim, True)
    _coin(m.state("real"), far, near, rim, False)
    _half(m.state("half"), far, near, rim)

    # ---------------------------------------------------------------- 펴짐(얼굴이 부채처럼 펴지고 막대기를 뻗음)
    o = m.state("open")
    h = o.head(size=0.55, depth=0.3, face="smile", sides=shell)
    # 접혔던 금빛 반쪽 둘이 얼굴 뒤에서 부채처럼 펼쳐짐(뒤쪽만 금, 앞쪽은 살)
    for side in (1, -1):
        o.box((-R, -0.02, -0.02), (R, 0.02, 0.02), rim, faces=("up", "down", "north", "south"), uv=[0, 0, 16, 2],
              rot=(0, 0, side * 62), origin=(0, -0.05, -0.17))
    for side in (1, -1):
        root = h.point(side * 0.85, -0.7, 0.2)
        o.rod(root, (side * 0.7, -0.75, 0.55), length=0.66, segs=3, thick=0.07,
              wave=kit.Wave(amp=(26, 22, 18), lag=55, bias=(-8, 4, 10), shape="flap", phase=0 if side > 0 else 90),
              toward=(0, 1, 0.3))
    k.icon(model="g07", state="")


def breath(seed=0):
    """쌕쌕: 거친 숨 두 번(높은 대역 잡음)"""
    a = audio.env(audio.bandpass(audio.noise(0.22, seed), 1400, 5200), 0.03, 0.08, 0.5, 0.1)
    b = audio.env(audio.bandpass(audio.noise(0.28, seed + 7), 1100, 4200), 0.04, 0.1, 0.45, 0.14)
    return audio.normalize(audio.cat(a, audio.silence(0.12), b), 0.55)


def snicker(seed=0):
    """듣기(웅크리기): 쌕쌕 밑에 낮게 깔린 킥킥"""
    g = audio.giggle(seed=seed, count=4, base=300 + 20 * seed, rate=7.0)
    return audio.normalize(audio.mix(audio.gain(g, 0.7), audio.gain(breath(seed + 3), 0.8)), 0.6)


def unfold(seed=0):
    """좌르륵: 부채살 펴지는 빠른 딸깍 열둘 + 마지막에 젖은 철퍽"""
    rng = np.random.RandomState(seed)
    parts = []
    for i in range(12):
        c = audio.env(audio.bandpass(audio.noise(0.018, seed * 31 + i), 1800 + 120 * i, 6000), 0.001, 0.008, 0.2, 0.006)
        parts.append(audio.cat(c, audio.silence(0.012 + rng.rand() * 0.008)))
    zip_ = audio.cat(*parts)
    return audio.normalize(audio.mix(zip_, audio.at(audio.squish(seed=seed + 40, dur=0.2), 0.3)), 0.85)


def chew(seed=0):
    """오물오물: 발목을 문 채 씹는 젖은 소리"""
    x = audio.cat(audio.squish(seed=seed, dur=0.14), audio.silence(0.05), audio.squish(seed=seed + 1, dur=0.12))
    return audio.normalize(audio.lowpass(x, 2200), 0.7)


def sounds(k: kit.Kit):
    k.sound("breath", "쌕쌕 숨소리", synth=lambda i: breath(70 + i), volume=0.7, attenuation=6)
    k.sound("laugh", "숨 밑의 웃음", synth=lambda i: snicker(i), volume=0.6, attenuation=8)
    k.sound("unfold", "좌르륵 펴짐", synth=lambda i: unfold(80 + i), attenuation=16)
    k.sound("chew", "발목을 씹음", synth=lambda i: chew(90 + i), vanilla=[("random/eat1", 0.6)], volume=0.8, attenuation=12)
    # 진짜 금화(G07Coin): 주움, 겨눠 쏘아 깨짐
    k.sound("pickup", "재화를 주움", vanilla=[("random/orb", 1.25)], volume=0.7, attenuation=12)
    k.sound("crack", "재화가 반으로 깨짐", vanilla=[("random/glass1", 1.7), ("random/glass3", 1.8)], volume=0.6, attenuation=16)


def phrases():
    return {"cause_g07": "재화를 주우려 했다"}
