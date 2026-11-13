"""G156 줄넘기 게이브: 0.6 블록 얼굴 둘이 3.5 블록 떨어져 땅에 반쯤 박힌 채 고개만 내밀고 비스듬히 마주 본다. 저마다 입에 엔드 막대기
손잡이 하나를 물어 세웠고, 두 손잡이 끝 사이에 분홍 살 줄이 늘어져 줄넘기처럼 크게 돈다. 줄 한가운데에 주먹만 한 얼굴 매듭 하나.

모델 gave:gabe/g156(폭 3.6 이라 shrink 0.68): 상태 ""(줄이 도는 8자세, 주기 20틱 고정: Java 가 같은 식으로 줄 자리를 셈),
"whip"(줄이 끊긴 뒤 한쪽 끝 얼굴 하나가 끊긴 줄을 끌고 옴, 몸 가운데 = 그 얼굴).
줄 자세 k 의 각(도, 0 = 땅, 180 = 머리 위)은 ANGLES. 꼭대기 근처에 자세를 몰아 두어 매듭이 거기서 멈춘 듯 느리고, 꼭대기 자세(4)에서는
매듭 얼굴이 눈을 뜨고 금빛으로 반짝인다(반 박자 뒤 땅을 침). Java G156.ANGLES, AXIS_Y, ROPE_R, GROUND 와 같게.
"""
import math

import numpy as np

from gabe import faces, kit

GROUND = -0.55            # 모델 바닥(몸 중심 기준): Java hover 0.55
END_X = 1.75              # 얼굴 둘의 가로 자리
AXIS_X, AXIS_Y = 1.6, 0.6  # 손잡이 끝(줄이 도는 굴대)
ROPE_R = 1.12             # 줄 가운데가 굴대에서 떨어지는 거리(땅에 닿음, 위로는 2.2 블록)
ANGLES = (0, 60, 115, 155, 180, 205, 245, 300)
ROPE_SEGS = 6
ROPE_W = 0.09
KNOT = faces.Face(eyes="squint", blush="pink")
KNOT_TOP = faces.Face(eyes="open", blush="gold")
WHIP_WAVE = kit.Wave(amp=(14, 18, 22), lag=55, shape="sin")


def seg(s, a, b, wid, tex, uv, face=False):
    """a 에서 b 로 가는 네모 막대 하나(옆 네 면만): 상자를 a 에서 위(또는 아래)로 세워 a 둘레로 돌린다"""
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    d /= L
    ref = np.array([0.0, 0.0, 1.0]) if abs(d[2]) < 0.9 else np.array([1.0, 0.0, 0.0])
    f = np.cross(d, ref)
    f /= np.linalg.norm(f)
    z = np.cross(f, d)
    lim = 1.5 / s.model.shrink - 1e-3
    up = a[1] + L <= lim
    R = np.column_stack([f, d, z]) if up else np.column_stack([-f, -d, z])
    y0, y1 = (a[1], a[1] + L) if up else (a[1] - L, a[1])
    frm = (a[0] - wid / 2, y0, a[2] - wid / 2)
    to = (a[0] + wid / 2, y1, a[2] + wid / 2)
    s.box(frm, to, tex, uv=uv, faces=("north", "south", "east", "west"), rot=kit.euler_of(R), origin=tuple(a), face=face)


def rope_point(u, ang):
    """줄 위 점(u 0..1, 왼끝 -X 에서 오른끝 +X), 줄이 각 ang(도) 만큼 돈 자세"""
    x = -AXIS_X + 2 * AXIS_X * u
    r = ROPE_R * 4 * u * (1 - u)
    a = math.radians(ang)
    return (x, AXIS_Y - r * math.cos(a), r * math.sin(a))


def pair(k, s):
    # 땅에 반쯤 박힌 얼굴 둘(비스듬히 마주 보고 앞을 봄), 입에서 굴대까지 손잡이 막대기
    for side in (1, -1):
        s.head(size=0.6, depth=0.5, at=(side * END_X, GROUND + 0.12, 0), face="smile", rot=(0, -side * 50, 0))
        seg(s, (side * 1.68, GROUND + 0.1, 0.16), (side * AXIS_X, AXIS_Y + 0.04, 0), 0.1, "rod", [0, 0, 2, 15])
    # 도는 줄과 매듭(자세마다 통째로)
    for f in s.frames():
        with f:
            ang = ANGLES[f.k]
            pts = [rope_point(i / ROPE_SEGS, ang) for i in range(ROPE_SEGS + 1)]
            for i in range(ROPE_SEGS):
                seg(s, pts[i], pts[i + 1], ROPE_W, "flesh", [0, 0, 16, 16])
            kx, ky, kz = rope_point(0.5, ang)
            s.head(size=0.26, depth=0.22, at=(kx, ky, kz), face=KNOT_TOP if f.k == 4 else KNOT, near=False, torn=False)


def whip(k, s):
    # 한쪽 끝: 얼굴(반쯤 박힘), 입의 손잡이, 뒤로 끌리는 끊긴 줄(땅을 쓸며 좌우로 휘청)
    s.head(size=0.6, depth=0.5, at=(0, 0, 0), face="smile")
    seg(s, (0.05, 0.0, 0.22), (0.12, 0.62, 0.05), 0.1, "rod", [0, 0, 2, 15])
    s.band((0.12, 0.6, 0.0), (0.0, -0.55, -1.0), length=1.5, width=ROPE_W, thick=ROPE_W, segs=3, wave=WHIP_WAVE,
           toward=(1, 0, 0), tex="flesh", poses=4)


def build(k: kit.Kit):
    m = k.model(poses=8, period=20, shrink=0.68, mut2=False, torn=False)
    pair(k, m.state(""))
    whip(k, m.state("whip", poses=4, period=12))
    k.icon(model="g156", state="")


def sounds(k: kit.Kit):
    # 이 종의 신호: 1초마다 줄이 땅을 치는 '탁'
    k.sample("slap", "tag:slam", "줄이 땅을 치는 소리", pitch=1.3, attenuation=24)
    # 매듭이 끊김
    k.sample("snap", "kenney_impact/impactpunch_medium_00*", "줄이 끊김", pitch=1.4, attenuation=20)


def phrases():
    return {"cause_g156": "줄넘기 줄에 걸렸다"}
