"""G74 칙칙 수레 게이브: 녹슨 쇠틀 수레(바닥 판, 앞뒤 난간)에 0.6블록 얼굴이 쇠사슬 둘로 묶였다. 양옆에 살바퀴 둘(바퀴살은 엔드 막대기 셋이
굴대를 꿰어 여섯 살), 굴대도 엔드 막대기. 뒤통수에 꽂힌 굴뚝(막대기)에서 분홍 김이 칙칙 오른다. 폭 1.3, 길이 1.0 으로 얼굴보다 수레가 크다.

상태: ""(구름: 두 바퀴가 손으로 짠 4자세로 돎. 바퀴살 여섯이라 60도마다 같은 모양: 15도씩 4자세가 한 바퀴처럼 이어짐),
"spin"(제자리 돌기: 왼쪽 바퀴는 서고 오른쪽만 돎). 부위 "wheel_l", "wheel_r"(그 바퀴가 떨어져 나감: 맞으면 제자리를 맴돈다).
모델 gave:gabe/g74_gas: 터진 자리의 분홍 가스 구름(점묘 판 하나, Java 가 효과 디스플레이에 실어 사람을 보게 함).
몸 가운데 = 바닥 위 0.8(허리 띠). 떼 종이라 변이 부위는 끈다.
"""
import math

import numpy as np

from gabe import kit

FLOOR = -0.8
WR = 0.42                  # 바퀴 반지름
AXLE = FLOOR + WR          # 굴대 높이
WX = 0.56                  # 바퀴 자리(옆)
BED = (-0.36, AXLE - 0.04, -0.46, 0.36, AXLE + 0.12, 0.5)


def rust_tex(seed=74):
    """녹슨 쇠: 갈색, 주황 녹 얼룩, 검은 패임"""
    rng = np.random.RandomState(seed)
    base = np.array([96, 62, 46], float)
    out = np.zeros((16, 16, 4), np.uint8)
    n = rng.rand(16, 16)
    for y in range(16):
        for x in range(16):
            c = base * (0.8 + 0.35 * n[y, x])
            if rng.rand() < 0.22:
                c = np.array([168, 84, 38], float) * (0.85 + 0.3 * rng.rand())
            if rng.rand() < 0.06:
                c = np.array([40, 28, 24], float)
            if y in (0, 15) or x in (0, 15):
                c = c * 0.75
            out[y, x, :3] = np.clip(c, 0, 255)
            out[y, x, 3] = 255
    return out


def gas_tex(n=64, seed=740):
    """점묘 분홍 가스 구름: 잡음 덩이를 디더로(컷아웃), 가운데가 짙음"""
    rng = np.random.RandomState(seed)
    g = rng.rand(n // 8 + 2, n // 8 + 2)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    # 거친 잡음을 겹선형으로 부드럽게
    gx, gy = xx / 8.0, yy / 8.0
    x0, y0 = np.floor(gx).astype(int), np.floor(gy).astype(int)
    fx, fy = gx - x0, gy - y0
    v = (g[y0, x0] * (1 - fx) * (1 - fy) + g[y0, x0 + 1] * fx * (1 - fy) + g[y0 + 1, x0] * (1 - fx) * fy + g[y0 + 1, x0 + 1] * fx * fy)
    r = np.hypot(xx + 0.5 - n / 2, yy + 0.5 - n / 2) / (n / 2)
    dens = np.clip((1.0 - r) * 1.5 + (v - 0.5) * 0.9, 0, 1)
    bayer = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0
    th = np.tile(bayer, (n // 4, n // 4))
    out = np.zeros((n, n, 4), np.uint8)
    on = dens * 0.85 > th
    col = np.stack([226 + 20 * v, 120 + 40 * v, 160 + 30 * v], -1)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    out[..., 3] = np.where(on, 255, 0).astype(np.uint8)
    return out


def wheel(s, side, ang):
    """바퀴 하나: 굴대 둘레 지름 막대기 셋(살 여섯), ang = 돈 각(도)"""
    for i in range(3):
        a = math.radians(ang + i * 60)
        d = (0.0, math.cos(a), math.sin(a))
        root = (side * WX, AXLE - d[1] * WR, -d[2] * WR)
        s.rod(root, d, length=2 * WR, segs=1, thick=0.07, base=False, rag=False, toward=(1, 0, 0))


def cart(s, k, rust, steam_rate=1):
    """수레 몸: 바닥 판, 난간, 굴대, 얼굴, 쇠사슬 둘, 굴뚝과 김"""
    x0, y0, z0, x1, y1, z1 = BED
    s.box((x0, y0, z0), (x1, y1, z1), rust)
    s.box((x0, y1, z1 - 0.07), (x1, y1 + 0.22, z1), rust, faces=("north", "south", "up", "east", "west"))
    s.box((x0, y1, z0), (x1, y1 + 0.3, z0 + 0.07), rust, faces=("north", "south", "up", "east", "west"))
    s.rod((-WX - 0.06, AXLE, 0.0), (1, 0, 0), length=2 * WX + 0.12, segs=1, thick=0.08, base=False, rag=False)
    h = s.head(size=0.6, depth=0.5, at=(0, y1 + 0.3, 0.02), face="smile")
    top = y1 + 0.6
    # 얼굴을 묶은 쇠사슬: 앞으로 늘어진 것 하나, 대각선 하나
    s.chain((x0 - 0.02, y1 + 0.02, 0.3), (x1 + 0.02, y1 + 0.02, 0.3), links=5, sag=-0.35, size=0.07)
    s.chain((x0 - 0.02, top - 0.05, 0.28), (x1 + 0.02, y1 + 0.05, 0.28), links=5, sag=0.05, size=0.07)
    # 굴뚝(뒤통수에 꽂힌 막대기)
    s.rod((0.0, top - 0.15, -0.22), (0.0, 1.0, -0.25), length=0.42, segs=1, thick=0.12, base=False, rag=False)
    cx, cy, cz = 0.0, top + 0.26, -0.33
    for fr in s.frames():
        with fr:
            t = (fr.t * steam_rate) % 1.0
            sz = 0.16 + 0.22 * t
            c = (cx, cy + 0.05 + 0.45 * t, cz - 0.08 * t)
            s.quad(c, (sz, sz), "south", "flesh", face=True)
            s.quad(c, (sz, sz), "east", "flesh", face=True)
    return h


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    rust = k.texture("rust", rust_tex())

    s = m.state("")
    cart(s, k, rust)
    for side, part in ((1, "wheel_l"), (-1, "wheel_r")):
        with s.when("!on:" + part):
            for fr in s.frames():
                with fr:
                    wheel(s, side, 15 * fr.k)

    g = m.state("spin")
    cart(g, k, rust, steam_rate=2)
    with g.when("!on:wheel_l"):
        wheel(g, 1, 0)
    with g.when("!on:wheel_r"):
        for fr in g.frames():
            with fr:
                wheel(g, -1, 15 * fr.k)

    gas = k.model("g74_gas", poses=1, mut2=False, open=False, torn=False, rag=False)
    gas.state("").quad((0, 0, 0), (1.0, 1.0), "south", k.texture("gas", gas_tex()))

    k.icon(model="g74", state="", view="front")


def sounds(k: kit.Kit):
    # 쇠 바퀴 덜컹(구를 때 드문드문)
    k.sample("cart", "tag:metal+heavy-bell", "수레 소리", pitch=0.75, volume=0.8, attenuation=24)
    # 굴뚝 김 칙칙
    k.sample("steam", "tag:steam", "칙칙", pitch=1.35, volume=0.6, attenuation=16)
    # 제자리에서 끼익 돎
    k.sample("screech", "tag:creak+metal", "끼익", pitch=0.9, volume=0.8, attenuation=16, n=1)
    # 터짐(가스)
    k.sample("burst", "tag:bang", "수레 터짐", pitch=0.8, attenuation=32)


def phrases():
    return {"cause_g74": "칙칙 수레에 치였다", "cause_g74_gas": "분홍 가스를 마셨다"}
