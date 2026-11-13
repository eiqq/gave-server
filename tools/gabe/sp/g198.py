"""G198 나란히 걷는 거인 게이브(4막 34라운드, 튼튼이 + 방패): 키 13블록 사람꼴 거인(일곱이 어깨를 맞대고 한 줄로 걷는다).

몸은 같은 웃는 _Gave 얼굴 판을 벽돌처럼 쌓은 기둥, 팔은 몸에 붙인 살 기둥, 머리는 3블록 _Gave 얼굴(일곱 모두 같은 얼굴, 같은 웃음, 실눈).
다리는 부품 둘(엉덩이에서 발까지 Java 가 돌리고 늘임): 엔드 막대기 셋 다발을 살 띠로 동인 장대, 무릎에 얼굴 하나(약점), 발은 살덩이.
이웃 거인끼리 어깨를 엔드 막대기 하나로 꿰어 묶는다(부품 g198_rod, 끊기면 'cut' 그루터기).

- gave:gabe/g198(shrink 0.28): 몸 가운데(0, 0, 0) = 엔진 pos(바닥 + HOVER 8.0). 엉덩이 이음점 (±HIP_X, HIP_Y, 0), 어깨 막대기 자리 (±SHOULDER_X, SHOULDER_Y, 0).
  상태 ""(정면 실눈), "look"(무릎을 맞아 발밑을 내려다보며 눈 뜸: 머리를 숙임).
- gave:gabe/g198_leg(shrink 0.22): 다리 하나, 엉덩이 (0, 0, 0) 발 (0, -LEG_L, 0). 부위 hurt(무릎 얼굴이 핏발 선 눈).
- gave:gabe/g198_rod(shrink 0.4): 어깨 막대기, (0, 0, 0) 에서 +X 로 길이 ROD_L. 상태 "", "cut"(양 끝 그루터기만).
소리 march(발맞춘 발소리, 96), fall(쓰러짐, 96), kick(발로 참, 32).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.28
HOVER = 8.0
HIP_X, HIP_Y = 1.0, -2.2
SHOULDER_X, SHOULDER_Y = 1.45, 1.75
TORSO = (2.6, 4.1, 1.8)        # 폭, 높이, 깊이(아래 y = HIP_Y + 0.1)
HEAD = 3.0
HEAD_Y = 3.75
LEG_S = 0.22
LEG_L = 6.0
KNEE_Y = -3.0
SPACING = 5.7
ROD_L = SPACING - 2 * SHOULDER_X
BRICK = ("smile", "smile", "smile", "smile_a", "smile_c", "smile")


def stick(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    up = abs(d[1]) > 0.97 * L
    s.rod(tuple(a), tuple(d), length=L, segs=1, thick=thick, base=False, rag=False, tex=tex, toward=(0, 0, 1) if up else (0, 1, 0))


def body(s, k, look):
    W, H, D = TORSO
    y0 = HIP_Y + 0.1
    y1 = y0 + H
    s.box((-W / 2, y0, -D / 2), (W / 2, y1, D / 2), "flesh", face=True)
    # 골반, 어깨 둔덕
    s.box((-1.5, HIP_Y - 0.35, -0.95), (1.5, HIP_Y + 0.45, 0.95), "flesh", face=True)
    s.box((-SHOULDER_X - 0.25, y1 - 0.55, -0.85), (SHOULDER_X + 0.25, y1 + 0.05, 0.85), "flesh", face=True)
    # 벽돌 얼굴 판(앞, 뒤, 옆): 줄마다 반 칸 어긋나게
    rng = np.random.RandomState(198)
    texs = [k.face_tex(f) for f in BRICK]
    q = 0.52
    for facing in ("south", "north", "east", "west"):
        span = W if facing in ("south", "north") else D
        cols = max(1, int(round(span / q)))
        rows = int(round(H / q))
        for r in range(rows):
            for c in range(cols + (1 if r % 2 else 0)):
                u = -span / 2 + (c + (0.0 if r % 2 else 0.5)) * span / cols
                if abs(u) > span / 2 - 0.12:
                    continue
                v = y0 + (r + 0.5) * H / rows
                lift = 0.025 + 0.012 * (r % 2)
                if facing == "south":
                    c3 = (u, v, D / 2 + lift)
                elif facing == "north":
                    c3 = (u, v, -D / 2 - lift)
                elif facing == "east":
                    c3 = (W / 2 + lift, v, u)
                else:
                    c3 = (-W / 2 - lift, v, u)
                s.quad(c3, (q * 0.98, q * 0.98), facing, texs[rng.randint(len(texs))], face=True)
    # 팔: 몸에 붙인 살 기둥(어깨에서 허벅지까지) + 주먹 얼굴
    for side in (1, -1):
        x = side * (W / 2 + 0.33)
        s.box((x - 0.3, HIP_Y + 0.6, -0.35), (x + 0.3, y1 - 0.2, 0.35), "flesh", face=True)
        s.head(size=0.7, depth=0.6, at=(x, HIP_Y + 0.35, 0.0), face="smile_a" if side > 0 else "smile_c", near=False, open=False, torn=False,
               rot=(0, 90 * side, 0))
    # 목, 머리(일곱 모두 같은 실눈 웃음). look 이면 고개를 숙여 발밑을 보며 눈을 뜸
    s.box((-0.6, y1 - 0.1, -0.5), (0.6, HEAD_Y - HEAD / 2 + 0.3, 0.5), "flesh", face=True)
    if look:
        s.head(size=HEAD, depth=2.2, at=(0, HEAD_Y - 0.15, 0.25), face="open", near=False, rot=(32, 0, 0))
    else:
        s.head(size=HEAD, depth=2.2, at=(0, HEAD_Y, 0.0), face="smile", near=False)


def leg(k):
    m = k.model("g198_leg", poses=1, period=20, shrink=LEG_S, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    s.box((-0.55, -0.45, -0.55), (0.55, 0.35, 0.55), "flesh", face=True)
    for i in range(3):
        a = math.radians(i * 120 + 30)
        top = (math.cos(a) * 0.32, -0.2, math.sin(a) * 0.32)
        bot = (math.cos(a) * 0.24, -LEG_L + 0.45, math.sin(a) * 0.24)
        stick(s, top, bot, 0.26)
    for y in (-1.1, -4.8):
        s.box((-0.48, y - 0.2, -0.48), (0.48, y + 0.2, 0.48), "flesh", face=True)
    s.box((-0.55, KNEE_Y - 0.55, -0.55), (0.55, KNEE_Y + 0.55, 0.55), "flesh", face=True)
    with s.when("!on:hurt"):
        s.head(size=1.0, depth=0.24, at=(0, KNEE_Y, 0.58), face="smile", near=False, open=False, torn=False)
    with s.when("on:hurt"):
        s.head(size=1.0, depth=0.24, at=(0, KNEE_Y, 0.58), face="bloodshot", near=False, open=False, torn=False)
    # 발: 살덩이(앞으로 길게)
    s.box((-0.6, -LEG_L, -0.6), (0.6, -LEG_L + 0.5, 0.95), "flesh", face=True)
    s.front = (0, 0, 0)


def rod(k):
    m = k.model("g198_rod", poses=1, period=20, shrink=0.4, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    s.rod((0, 0, 0), (1, 0, 0), length=ROD_L, segs=1, thick=0.3, base=False, wave=None, rag=False)
    c = m.state("cut")
    for x0, d in ((0.0, 1), (ROD_L, -1)):
        c.rod((x0, 0, 0), (d, -0.35, 0), length=0.7, segs=1, thick=0.3, base=False, wave=None, rag=False)
    s.front = c.front = (0, 0, 0)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=S, mut2=False, torn=False)
    body(m.state(""), k, False)
    lk = m.state("look")
    body(lk, k, True)
    leg(k)
    rod(k)
    k.icon(model="g198", state="")


def sounds(k: kit.Kit):
    k.sample("march", ["kenney_impact/impactsoft_heavy_000", "kenney_impact/impactsoft_heavy_001", "kenney_impact/impactsoft_heavy_002"],
             "발맞춘 발소리", pitch=0.45, attenuation=96, level=-8)
    k.sample("fall", "tag:quake", "거인이 쓰러짐", pitch=0.65, attenuation=96, level=-9)
    k.sample("kick", "kenney_impact/impactpunch_heavy_003", "거인이 발로 참", pitch=0.6, attenuation=32, n=1)


def phrases():
    return {"cause_g198": "나란히 걷는 거인에게 밟혔다", "cause_g198_kick": "거인 발에 차였다", "cause_g198_fall": "쓰러지는 거인에 깔렸다"}
