"""G130 따라 하는 거인 게이브(3막, 거인 인형: 키 12 블록 헝겊 인형).

얼굴 가죽을 기운 자루 몸통(작은 얼굴 조각이 솜처럼 울퉁불퉁), 3 블록 _Gave 머리, 정수리에 태엽 손잡이 막대기 둘(천천히 돎).
팔 둘, 다리 둘은 부품(Java 가 걸음, 주먹 내지르기, 손바닥 쓸기를 자세로). 관절마다 뼈대 엔드 막대기 끝이 살을 뚫고 나왔다.

- gave:gabe/g130(shrink 0.25): 몸통 + 머리 + 태엽 손잡이. 몸 가운데(0, 0, 0) = 엔진 pos, 바닥에서 HOVER 7.5(Java G130.HOVER).
  엉덩이 (±HIP_X, HIP_Y, 0), 어깨 (±SH_X, SH_Y, 0). 상태 ""(태엽이 돎, 40틱 자세 4), "daze"(태엽이 멈추고 고개가 꺾임, 무표정).
  부위 "cut0", "cut1": 그 태엽 손잡이가 부서짐(그루터기).
- gave:gabe/g130_arm(shrink 0.26): 어깨 (0, 0, 0), 손 끝 (0, -ARM_L, 0). 손바닥(+Z)에 얼굴.
- gave:gabe/g130_leg(shrink 0.26): 엉덩이 (0, 0, 0), 발바닥 (0, -LEG_L, 0), 발끝 +Z.
- gave:gabe/g130_doll: 찍힌 사람 머리 위 작은 인형(머리 칸 모델, -Z 가 얼굴 쪽).
소리 wind(태엽 튕기는 소리, 64), step(헝겊 거인 걸음, 64), punch(주먹, 48), sweep(손바닥 쓸기, 48).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.25
HOVER = 7.5
HIP_X, HIP_Y = 1.0, -2.5
SH_X, SH_Y = 2.05, 1.15
LEG_L = 5.0
ARM_L = 5.5
HEAD = 3.0
HEAD_Y = 3.15
KNOB_X = 0.62


def patch(s, k, rng, c, size, facing, faces_):
    """기운 얼굴 조각 하나(조금 기울임)"""
    axis = (0, 0, 1) if facing in ("south", "north") else (1, 0, 0) if facing in ("east", "west") else (0, 1, 0)
    s.quad(tuple(c), (size, size), facing, k.face_tex(faces_[rng.randint(len(faces_))]), face=True,
           rot=kit.euler_of(kit.rot_axis(axis, float(rng.uniform(-22, 22)))), origin=tuple(c))


def bone(s, a, d, length=0.6, thick=0.14):
    """살을 뚫고 나온 엔드 막대기 끝"""
    d = np.array(d, float)
    tw = (0, 0, 1) if abs(d[1]) > 0.9 * np.linalg.norm(d) else (0, 1, 0)
    s.rod(tuple(a), tuple(d), length=length, segs=1, thick=thick, base=False, toward=tw, rag=False)


def torso(s, k, daze=False):
    rng = np.random.RandomState(130)
    flesh = "flesh"
    s.box((-1.8, -2.95, -1.15), (1.8, 1.55, 1.15), flesh, face=True)
    # 솜처럼 불룩한 덩이(조금씩 삐져나옴)
    for (x, y, z, w) in ((1.1, -0.6, 0.9, 1.0), (-1.0, 0.6, 0.85, 0.9), (0.2, -2.0, 0.95, 1.1), (-1.3, -1.6, -0.9, 1.0), (1.2, 0.8, -0.9, 0.8),
                         (1.6, -1.2, 0.2, 0.9), (-1.6, 0.0, -0.3, 1.0)):
        s.box((x - w / 2, y - w / 2, z - w / 2), (x + w / 2, y + w / 2, z + w / 2), flesh, face=True)
    # 기운 얼굴 조각
    fl = ("stitch", "smile", "half", "stitch", "smile_a", "tears")
    for i in range(7):
        patch(s, k, rng, (rng.uniform(-1.4, 1.4), rng.uniform(-2.5, 1.2), 1.17 + 0.01 * i), rng.uniform(0.7, 1.1), "south", fl)
        patch(s, k, rng, (rng.uniform(-1.4, 1.4), rng.uniform(-2.5, 1.2), -1.17 - 0.01 * i), rng.uniform(0.7, 1.1), "north", fl)
    for i in range(4):
        patch(s, k, rng, (1.82 + 0.01 * i, rng.uniform(-2.5, 1.2), rng.uniform(-0.8, 0.8)), rng.uniform(0.6, 0.9), "east", fl)
        patch(s, k, rng, (-1.82 - 0.01 * i, rng.uniform(-2.5, 1.2), rng.uniform(-0.8, 0.8)), rng.uniform(0.6, 0.9), "west", fl)
    # 어깨 덩이, 골반, 목
    for side in (1, -1):
        s.box((side * SH_X - 0.65, SH_Y - 0.6, -0.65), (side * SH_X + 0.65, SH_Y + 0.6, 0.65), flesh, face=True)
        bone(s, (side * (SH_X + 0.3), SH_Y + 0.3, 0), (side * 0.6, 1, -0.2), 0.75)
        bone(s, (side * (HIP_X + 0.35), HIP_Y - 0.1, -0.3), (side * 1, -0.2, -0.6), 0.6)
    s.box((-1.6, HIP_Y - 0.55, -1.0), (1.6, HIP_Y + 0.4, 1.0), flesh, face=True)
    s.box((-0.7, 1.4, -0.6), (0.7, HEAD_Y - HEAD / 2 + 0.2, 0.6), flesh, face=True)
    # 머리(멍하면 옆으로 꺾임)
    rot = (8, 0, 24) if daze else None
    s.head(size=HEAD, depth=2.6, at=(0.3 if daze else 0, HEAD_Y - (0.2 if daze else 0), 0.1), face="blank" if daze else "smile", near=False,
           rot=rot)


def knobs(s, k, turning=True):
    """정수리 태엽 손잡이 둘: 막대기 기둥 + 가로대(돈다). 부서지면 그루터기"""
    top = HEAD_Y + HEAD / 2 - 0.1
    for i, x in enumerate((KNOB_X, -KNOB_X)):
        with s.when(f"!on:cut{i}"):
            bone(s, (x, top, 0), (0, 1, 0), 1.25, 0.2)
            if turning:
                for f in s.frames(poses=4):
                    with f:
                        a = f.k * 45 + (22 if i else 0)
                        c = (x, top + 1.25, 0)
                        s.box((x - 0.75, top + 1.15, -0.1), (x + 0.75, top + 1.35, 0.1), "rod", rot=(0, a, 0), origin=c)
            else:
                s.box((x - 0.75, top + 1.15, -0.1), (x + 0.75, top + 1.35, 0.1), "rod", rot=(0, 30, 0), origin=(x, top + 1.25, 0))
        with s.when(f"on:cut{i}"):
            bone(s, (x, top, 0), (0.3, 1, 0.2), 0.4, 0.2)


def arm(k):
    m = k.model("g130_arm", poses=1, period=20, shrink=0.26, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    rng = np.random.RandomState(31)
    s.box((-0.55, -2.7, -0.55), (0.55, 0.2, 0.55), "flesh", face=True)
    s.box((-0.48, -4.5, -0.48), (0.48, -2.5, 0.48), "flesh", face=True)
    s.box((-0.62, -2.95, -0.62), (0.62, -2.35, 0.62), "flesh", face=True)
    bone(s, (0, -2.65, -0.5), (0, -0.3, -1), 0.6)
    # 벙어리장갑 손 + 손바닥 얼굴
    s.box((-0.8, -ARM_L, -0.4), (0.8, -4.3, 0.4), "flesh", face=True)
    s.quad((0, -4.95, 0.42), (1.3, 1.1), "south", k.face_tex("open"), face=True)
    for i in range(2):
        patch(s, k, rng, (0.0, -1.0 - 1.6 * i, 0.57), 0.6, "south", ("stitch", "half"))
    s.front = (0, 0, 0)


def leg(k):
    m = k.model("g130_leg", poses=1, period=20, shrink=0.26, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    rng = np.random.RandomState(32)
    s.box((-0.62, -2.65, -0.62), (0.62, 0.2, 0.62), "flesh", face=True)
    s.box((-0.56, -4.55, -0.56), (0.56, -2.45, 0.56), "flesh", face=True)
    s.box((-0.68, -2.85, -0.68), (0.68, -2.3, 0.68), "flesh", face=True)
    bone(s, (0, -2.55, 0.6), (0, 0.2, 1), 0.6)
    s.box((-0.62, -LEG_L, -0.65), (0.62, -4.45, 1.15), "flesh", face=True)
    patch(s, k, rng, (0, -1.2, 0.64), 0.7, "south", ("stitch", "smile"))
    s.front = (0, 0, 0)


def doll(k):
    """찍힌 사람 머리 위 작은 인형(머리 칸 모델: -Z 가 얼굴 쪽, 머리 가운데 (0, 0, 0), 머리 윗면 y 0.5 남짓)"""
    m = k.model("g130_doll", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    y0 = 0.62
    s.box((-0.16, y0, -0.1), (0.16, y0 + 0.32, 0.1), "flesh", face=True)
    s.head(size=0.36, depth=0.3, at=(0, y0 + 0.5, 0), face="smile", near=False, open=False, torn=False, rot=(0, 180, 0))
    for side in (1, -1):
        s.box((side * 0.16, y0 + 0.08, -0.05), (side * 0.16 + side * 0.08, y0 + 0.28, 0.05), "flesh")
        s.box((side * 0.1 - 0.04, y0 - 0.12, -0.05), (side * 0.1 + 0.04, y0, 0.05), "flesh")
    s.rod((0.07, y0 + 0.68, 0), (0, 1, 0), length=0.14, segs=1, thick=0.04, base=False, toward=(0, 0, 1), rag=False)
    s.rod((-0.07, y0 + 0.68, 0), (0, 1, 0), length=0.14, segs=1, thick=0.04, base=False, toward=(0, 0, 1), rag=False)
    s.front = (0, 0, 0)


def build(k: kit.Kit):
    m = k.model(poses=4, period=40, shrink=S, mut2=False, torn=True)
    st = m.state("")
    torso(st, k)
    knobs(st, k, True)
    dz = m.state("daze", poses=1)
    torso(dz, k, daze=True)
    knobs(dz, k, False)
    arm(k)
    leg(k)
    doll(k)
    k.icon(model="g130", state="")


def sounds(k: kit.Kit):
    k.sample("wind", "tag:spring", "태엽 튕기는 소리", pitch=0.55, attenuation=64)
    k.sample("step", "tag:giant", "헝겊 거인 걸음", pitch=0.6, attenuation=64)
    k.sample("punch", ["kenney_impact/impactpunch_heavy_002", "kenney_impact/impactpunch_heavy_003"], "거인 주먹", pitch=0.55, attenuation=48)
    k.sample("sweep", "tag:wind", "손바닥 쓸기", pitch=0.7, attenuation=48)


def phrases():
    return {"cause_g130": "따라 하는 거인에게 밟혔다", "cause_g130_punch": "따라 하는 거인의 주먹에 맞았다",
            "cause_g130_sweep": "따라 하는 거인의 손바닥에 쓸렸다", "cause_g130_stomp": "따라 하는 거인의 발 구르기에 휩쓸렸다",
            "notice_g130": "거인이 너를 따라 한다"}
