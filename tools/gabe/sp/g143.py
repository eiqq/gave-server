"""G143 갈아 먹는 톱니 게이브(4막, 튼튼이, 보상: 처음 지름 3, 다 자라면 9 블록).

나란히 맞물려 도는 얼굴 톱니바퀴 둘. 바퀴 테는 _Gave 얼굴을 둥글게 이어 붙인 것이고 얼굴마다 턱(뼈 이)이 톱니처럼 바깥으로 튀어나왔다.
바퀴살은 엔드 막대기 여섯, 두 바퀴 굴대를 잇는 굵은 엔드 막대기 굴대가 바퀴 뒤에 가로질러 있다(약점: 뒤나 옆에서 보임).
두 바퀴가 맞물리는 틈 뒤에 이빨 고리 입이 있고, 그 뒤로 갈려 나온 분홍 반죽이 띠처럼 흘러나온다.

- gave:gabe/g143(shrink 0.6, 지름 3 크기로 구움): 닻. 몸 가운데(0, 0, 0) = 엔진 pos(바닥 위 1.5 x 배율). 굴대, 굴대 버팀, 입, 반죽 띠,
  굴대 끝 가시 막대기 둘(원형 DNA, 살짝 흔들림). 상태 ""(반죽 띠 출렁), "broke"(굴대가 부러져 벌어짐: 굴대가 두 동강으로 꺾임).
- gave:gabe/g143_wheel(shrink 0.62): 바퀴 하나. 바퀴 가운데가 (0, 0, 0), 앞(+Z)을 보는 원판(반지름 1.5). Java 가 굴대 자리로 옮기고 z 축으로 돌림.
  상태 ""(테 얼굴 8), "f10", "f12", "f14", "f16", "f18"(테 얼굴 수 = 크기 단계).
소리 grind(톱니 갈리는 소리, 48), gulp(갈아 먹음, 24), suck(빨아들이는 바람, 32), snap(굴대 부러짐, 32), swell(터지기 전 부풂, 64), burst(터짐, 64).
"""
import math

import numpy as np

from gabe import faces, kit

RG = 1.5              # 바퀴 반지름(지름 3 크기)
HUB_X = 1.42          # 굴대 자리(맞물림)
AXLE_Z = -0.75
RIM_FACES = ("smile", "smile_a", "smile_c", "half", "tears", "smile", "deep", "stitch", "smile_a")
STAGES = (("", 8), ("f10", 10), ("f12", 12), ("f14", 14), ("f16", 16), ("f18", 18))


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def stick(s, a, b, thick, tex="rod", max_len=1.8):
    """a 에서 b 까지 곧은 막대기(길면 max_len 씩 끊어 이음점이 틀 안에 들게)"""
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    up = abs(d[1]) > 0.97 * L
    n = max(1, int(math.ceil(L / max_len)))
    for i in range(n):
        s.rod(tuple(a + d * (i / n)), tuple(d), length=L / n, segs=1, thick=thick, base=False, rag=False, tex=tex,
              toward=(0, 0, 1) if up else (0, 1, 0))


def wheel(s, k, n):
    """바퀴: 가운데 살 굴통, 바퀴살 여섯, 테 얼굴 n(앞을 봄, 테를 따라 돌려 붙임) + 얼굴마다 턱 이빨(바깥으로)"""
    s.box((-0.32, -0.32, -0.3), (0.32, 0.32, 0.3), "flesh", face=True)
    for i in range(6):
        a = 2 * math.pi * i / 6 + 0.26
        stick(s, (math.cos(a) * 0.25, math.sin(a) * 0.25, 0), (math.cos(a) * (RG - 0.32), math.sin(a) * (RG - 0.32), 0), 0.13)
    fs = min(0.62, 2 * math.pi * RG / n * 0.98)
    for i in range(n):
        a = 2 * math.pi * i / n
        c = (math.cos(a) * RG, math.sin(a) * RG, 0)
        # 얼굴 위쪽이 바깥(반지름 방향)을 보게: z 축으로 a - 90 도
        rz = math.degrees(a) - 90
        name = RIM_FACES[i % len(RIM_FACES)]
        s.head(size=fs, depth=0.42, at=c, face=name, near=False, rot=(0, 0, rz), torn=False)
        # 턱 이빨: 얼굴 바깥 가장자리에서 반지름 방향으로 튀어나온 뼈 이(면 넷)
        t = (math.cos(a) * (RG + fs * 0.5 + 0.16), math.sin(a) * (RG + fs * 0.5 + 0.16), 0)
        s.box((t[0] - fs * 0.22, t[1] - 0.18, -0.16), (t[0] + fs * 0.22, t[1] + 0.18, 0.16), "bone", rot=(0, 0, rz), origin=t,
              faces=("north", "south", "east", "west"))
    s.front = (0, 0, 1)


def anchor(s, k, broke):
    # 굴대: 두 바퀴 굴통을 잇는 굵은 엔드 막대기(바퀴 뒤), 굴통에서 굴대로 버팀 둘
    if not broke:
        stick(s, (-HUB_X - 0.2, 0, AXLE_Z), (HUB_X + 0.2, 0, AXLE_Z), 0.36)
    else:
        stick(s, (-HUB_X - 0.2, 0, AXLE_Z), (-0.15, -0.35, AXLE_Z - 0.3), 0.36)
        stick(s, (HUB_X + 0.2, 0, AXLE_Z), (0.15, 0.4, AXLE_Z - 0.25), 0.36)
    for side in (1, -1):
        stick(s, (side * HUB_X, 0, -0.25), (side * HUB_X, 0, AXLE_Z - 0.05), 0.3)
        s.box((side * HUB_X - 0.3, -0.3, AXLE_Z - 0.3), (side * HUB_X + 0.3, 0.3, AXLE_Z + 0.3), "flesh", face=True)
    # 틈 뒤 입: 이빨 고리 입 얼굴(앞을 봄)
    s.head(size=1.0, depth=0.5, at=(0, -0.15, -0.35), face=faces.Face(eyes="half", mouth="teeth_ring", blush="deep"), near=False)
    # 갈려 나온 분홍 반죽 띠(뒤로 흘러 땅에 끌림)
    s.band((0, -0.55, -0.65), (0, -0.55, -1), length=1.7, width=0.7, segs=3, thick=0.05,
           wave=kit.Wave(amp=(8, 10, 12), lag=40, bias=(10, 8, 4)), toward=(0, 1, 0), tex="wetskin", poses=4)
    # 굴대 끝 가시(원형 DNA): 굴통 뒤에서 위로 솟아 살짝 흔들림
    for side in (1, -1):
        s.rod((side * (HUB_X + 0.1), 0.25, AXLE_Z), (side * 0.25, 1, -0.2), length=1.1, segs=2, thick=0.12, base=False,
              wave=kit.Wave(amp=(9, 7), lag=40, phase=0 if side > 0 else 180), toward=(0, 0, 1), poses=4)
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=4, period=20, shrink=0.6, mut2=False)
    anchor(m.state(""), k, False)
    anchor(m.state("broke"), k, True)

    wm = k.model("g143_wheel", poses=1, period=20, shrink=0.62, mut2=False, torn=False, rag=False)
    for name, n in STAGES:
        wheel(wm.state(name), k, n)
    k.icon(model="g143", state="")


def sounds(k: kit.Kit):
    k.sample("grind", "tag:machine", "톱니 갈리는 소리", pitch=0.7, attenuation=48, level=-12)
    k.sample("gulp", "tag:eat", "톱니가 갈아 먹음", pitch=0.7, attenuation=24)
    k.sample("suck", "tag:wind", "빨아들이는 바람", pitch=0.8, attenuation=32, n=2)
    k.sample("snap", "tag:breaking", "굴대 부러짐", pitch=0.7, attenuation=32)
    k.sample("swell", "tag:groan", "톱니가 부풂", pitch=0.55, attenuation=64)
    k.sample("burst", "tag:explosion", "톱니가 터짐", pitch=0.7, attenuation=64, level=-10)


def phrases():
    return {"cause_g143": "톱니에 갈렸다", "cause_g143_roll": "구르는 톱니에 치였다", "cause_g143_burst": "터지는 톱니에 휩쓸렸다"}
