"""G106 숨 쉬는 땅 게이브(2막, 바닥에 깔리는 막, 지름 14 블록).

두께 0.3 의 납작한 등딱지. 겉은 황야 흙과 거의 같은 갈라진 마른 흙 무늬인데 금 사이로 분홍 살이 비치고, 금들이 모여 등 전체에 흐릿한 거대한
_Gave 얼굴(감은 실눈 둘과 점 입, 옅은 홍조)을 그린다. 가장자리 두 곳에 엔드 막대기 숨구멍 대롱이 0.6 솟는다. 아랫면은 분홍 살에 이빨 고리
입들이 박혀 열렸다 닫힌다(뒤집히면 위를 봄: Java 가 몸을 앞축으로 180도 돌림).

- gave:gabe/g106(shrink 0.18): 등딱지 가운데가 (0, 0, 0), 윗면 y = 0.15, 아랫면 y = -0.15. 상태 ""(엎드림, 아랫면 입들이 숨 쉬듯 열렸다
  닫힘, 자세 4). 부위 "tube_l", "tube_r": 그 숨구멍 대롱이 부서짐(그루터기).
소리 breathe(땅이 숨 쉬는 소리, 32), flip(뒤집힘, 64), crack(대롱 부서짐, 24).
"""
import math

import numpy as np

from gabe import kit

S = 0.18
R = 7.0
T = 0.15
TUBES = ((1, 5.6, -2.6), (-1, -5.2, 3.4))   # (쪽, x, z): tube_l(+X), tube_r(-X)


def back_tex(n=128, seed=106):
    """마른 흙 + 분홍 살이 비치는 금 + 금이 그린 흐릿한 얼굴(감은 실눈 둘, 점 입, 홍조)"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    u, v = (xx + 0.5) / n * 2 - 1, (yy + 0.5) / n * 2 - 1     # -1..1, v 아래로
    rr = np.hypot(u, v)
    base = np.array([128, 98, 70], float)
    col = base[None, None, :] * (0.84 + 0.24 * rng.rand(n, n, 1))
    # 큰 얼룩(흙 결)
    blot = np.zeros((n, n))
    for _ in range(14):
        cx, cy, r = rng.rand() * 2 - 1, rng.rand() * 2 - 1, 0.1 + rng.rand() * 0.25
        blot += np.exp(-((u - cx) ** 2 + (v - cy) ** 2) / (r * r))
    col *= (0.92 + 0.12 * np.tanh(blot - 0.5))[..., None]
    crack = np.zeros((n, n), bool)
    # 마구 갈라진 금(무작위 걸음)
    for _ in range(40):
        x, y = rng.rand() * n, rng.rand() * n
        a = rng.rand() * 2 * math.pi
        for _ in range(rng.randint(8, 26)):
            a += rng.randn() * 0.5
            x, y = x + math.cos(a), y + math.sin(a)
            if 0 <= x < n and 0 <= y < n:
                crack[int(y), int(x)] = True
    # 얼굴: 감은 실눈 둘(위로 볼록한 호), 점 입, 홍조
    face = np.zeros((n, n), bool)
    for ex in (-0.38, 0.38):
        d = np.hypot(u - ex, (v + 0.02) * 1.4)
        face |= (np.abs(d - 0.2) < 0.025) & (v < -0.02)
    face |= np.hypot(u, v - 0.38) < 0.05
    blush = (np.hypot(u - 0.55, v - 0.18) < 0.13) | (np.hypot(u + 0.55, v - 0.18) < 0.13)
    col[blush] = col[blush] * 0.7 + np.array([206, 126, 120]) * 0.3
    col[crack] = (88, 58, 46)
    # 금 한가운데 분홍 살이 비침
    pink = crack & (rng.rand(n, n) < 0.55)
    col[pink] = (184, 116, 112)
    col[face] = (196, 124, 124)
    edge = (rr > 0.94) & (rr <= 1.0)
    col[edge] = col[edge] * 0.75
    img = np.zeros((n, n, 4), np.uint8)
    img[..., :3] = np.clip(col, 0, 255)
    img[..., 3] = np.where(rr <= 1.0, 255, 0)
    return img


def belly_tex(n=64, seed=61):
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    u, v = (xx + 0.5) / n * 2 - 1, (yy + 0.5) / n * 2 - 1
    rr = np.hypot(u, v)
    col = np.array([206, 122, 132], float)[None, None, :] * (0.85 + 0.22 * rng.rand(n, n, 1))
    rings = (np.sin(rr * 30) > 0.8)
    col[rings] = (170, 86, 100)
    img = np.zeros((n, n, 4), np.uint8)
    img[..., :3] = np.clip(col, 0, 255)
    img[..., 3] = np.where(rr <= 1.0, 255, 0)
    return img


def soil_tex(seed=16, n=16):
    rng = np.random.RandomState(seed)
    col = np.array([116, 88, 62], float)[None, None, :] * (0.8 + 0.3 * rng.rand(n, n, 1))
    img = np.zeros((n, n, 4), np.uint8)
    img[..., :3] = np.clip(col, 0, 255)
    img[..., 3] = 255
    return img


def build(k: kit.Kit):
    back = k.texture("back", back_tex())
    belly = k.texture("belly", belly_tex())
    soil = k.texture("soil", soil_tex())
    m = k.model(poses=4, period=80, shrink=S, mut2=False, open=False, torn=False)
    s = m.state("")
    s.quad((0, T, 0), (2 * R, 2 * R), "up", back, face=True)
    s.quad((0, -T, 0), (2 * R, 2 * R), "down", belly, face=True)
    # 둘레 테(흙): 16 토막
    nseg = 16
    for i in range(nseg):
        a = 2 * math.pi * i / nseg
        c = (R * 0.97 * math.cos(a), 0, R * 0.97 * math.sin(a))
        L = 2 * math.pi * R / nseg * 1.05
        s.box((c[0] - L / 2, -T, c[2] - 0.2), (c[0] + L / 2, T, c[2] + 0.2), soil, rot=(0, -math.degrees(a) + 90, 0), origin=c,
              faces=("north", "south", "east", "west"))
    # 숨구멍 대롱 둘(가장자리, 0.6 솟음). 부서지면 그루터기
    for side, x, z in TUBES:
        part = "tube_l" if side > 0 else "tube_r"
        with s.when("!on:" + part):
            s.rod((x, T - 0.05, z), (0.12 * side, 1, 0.05), length=0.75, segs=1, thick=0.28, rag=False)
        with s.when("on:" + part):
            s.box((x - 0.18, T - 0.05, z - 0.18), (x + 0.18, T + 0.12, z + 0.18), "rod")
    # 아랫면 입들(아래를 봄, 뒤집히면 위): 이빨 고리 입이 열렸다 닫힘
    mouths = [(0, 0, 2.2)] + [(4.2 * math.cos(a), 4.2 * math.sin(a), 1.6) for a in np.linspace(0, 2 * math.pi, 7)[:-1]]
    bite, shut = k.face_tex("teeth_ring"), k.face_tex("smile_a")
    for f in s.frames():
        with f:
            for j, (x, z, sz) in enumerate(mouths):
                o = math.sin(2 * math.pi * (f.t + j / 7))
                s.quad((x, -T - 0.03, z), (sz, sz), "down", bite if o > 0 else shut, face=True)
    s.front = (0, 1, 0)
    k.icon(model="g106", state="", view="top")


def sounds(k: kit.Kit):
    k.sample("breathe", ["lrsf_horror/zombie_00", "lrsf_horror/zombie_02", "oga_creature80/burble_02"], "땅이 숨 쉬는 소리", pitch=0.45,
             attenuation=32)
    k.sample("flip", ["oga_quake/shake", "kenney_scifi/lowfrequency_explosion_001"], "땅이 뒤집힘", pitch=0.7, attenuation=64)
    k.sample("crack", ["oga_breaking75/bfh1_rock_breaking_01", "lrsf_horror/bonecrack_03"], "숨구멍 부서짐", pitch=0.8, attenuation=24)


def phrases():
    return {"cause_g106": "숨 쉬는 땅에 튕겨 떨어졌다", "cause_g106_bite": "뒤집힌 땅의 입에 물렸다", "cause_g106_sink": "숨 쉬는 땅에 발이 빠졌다"}
