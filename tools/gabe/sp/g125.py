"""G125 갈비 우리 거인 게이브(3막, 거인 인형(뼈대와 가슴 우리): 키 9 블록).

3 블록 _Gave 얼굴 아래 가슴이 통째로 우리다: 굵은 엔드 막대기 열두 대가 갈비 창살로 둥글게 휘어 섰고 안이 비었다. 등 쪽 등뼈 한가운데에 붉은 심장
얼굴(뛴다). 팔다리는 살 없는 뼈 막대기(부품), 손가락 다섯도 막대기. 가둔 사람은 창살 안에 웅크린 모습으로 보인다(부위 in1..in3).

- gave:gabe/g125(shrink 0.3): 가슴 가운데(0, 0, 0) = 엔진 pos, 바닥에서 HOVER 5(Java G125.HOVER). 엉덩이 (±HIP_X, HIP_Y, 0), 어깨 (±SH_X, SH_Y, SH_Z).
  상태 ""(서기, 심장이 뜀 16틱 자세 2), "bend"(엉덩이를 축으로 35도 숙이고 앞 창살이 벌어짐: 집기).
  부위 r0..r11: 그 갈비 창살이 부러짐(그루터기), in1..in3: 우리 안에 갇힌 사람 모습.
- gave:gabe/g125_arm(shrink 0.35): 어깨 (0, 0, 0), 손끝 (0, -ARM_L, 0). 상태 ""(손가락 벌림), "grip"(오므림).
- gave:gabe/g125_leg(shrink 0.4): 엉덩이 (0, 0, 0), 발바닥 (0, -LEG_L, 0).
소리 step(거대한 발걸음, 64), breath(숨소리, 32), creak(창살 벌어짐, 32), snap(창살 부러짐, 32).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.3
HOVER = 5.0
HIP_X, HIP_Y = 0.75, -1.5
SH_X, SH_Y, SH_Z = 1.55, 1.15, -0.25
LEG_L = 3.6
ARM_L = 4.0
BEND = 35.0
RIBS = 12
HEART = (0.0, 0.15, -1.15)
CAGE = ((-1.15, 0.72), (0.1, 1.32), (1.35, 0.72))   # (y, 반지름) 아래, 가운데, 위
CAPTIVE = ((0.45, -0.55, 0.15), (-0.45, -0.6, -0.1), (0.0, 0.35, 0.05))


def xf(R, p, pivot=(0.0, HIP_Y, 0.0)):
    p, pv = np.array(p, float), np.array(pivot, float)
    return p if R is None else R @ (p - pv) + pv


def seg(s, a, b, thick, tex="rod", R=None):
    a, b = xf(R, a), xf(R, b)
    d = b - a
    n = float(np.linalg.norm(d))
    tw = (0, 0, 1) if abs(d[1]) > 0.95 * n else (0, 1, 0)
    return s.rod(tuple(a), tuple(d), length=n, segs=1, thick=thick, base=False, tex=tex, toward=tw, rag=False)


def rib_pts(k, open_=False):
    a = math.radians(k * 360.0 / RIBS + 15)
    front = math.sin(a) > 0.2
    pts = []
    for j, (y, r) in enumerate(CAGE):
        rr = r * (1.35 if open_ and front and j == 1 else 1.0)
        aa = a + (math.copysign(0.35, math.cos(a)) if open_ and front and j == 1 else 0)
        pts.append((rr * math.cos(aa), y + (0.15 if open_ and front and j == 1 else 0), rr * math.sin(aa) - 0.05))
    return pts, front


def torso(s, k, bend=False):
    R = kit.rot_axis((1, 0, 0), BEND) if bend else None
    bone = "bone"
    # 골반, 등뼈, 어깨뼈, 목
    seg(s, (-0.95, HIP_Y, -0.3), (0.95, HIP_Y, -0.3), 0.42, bone, R)
    seg(s, (0, HIP_Y - 0.1, -0.85), (0, 1.75, -0.85), 0.34, "rod", R)
    seg(s, (-SH_X - 0.2, SH_Y, SH_Z - 0.1), (SH_X + 0.2, SH_Y, SH_Z - 0.1), 0.3, bone, R)
    seg(s, (0, 1.6, -0.6), (0, 2.05, -0.2), 0.3, "rod", R)
    # 갈비 창살 열둘(두 마디, 앞 여섯은 집을 때 벌어짐). 부러지면 아래 그루터기만
    for i in range(RIBS):
        pts, front = rib_pts(i, open_=bend)
        with s.when(f"!on:r{i}"):
            seg(s, pts[0], pts[1], 0.19, "rod", R)
            seg(s, pts[1], pts[2], 0.19, "rod", R)
        with s.when(f"on:r{i}"):
            mid = (np.array(pts[0]) + np.array(pts[1])) / 2
            seg(s, pts[0], tuple(mid + (0.05, 0.1, 0)), 0.19, "rod", R)
    # 우리 위아래 고리(뼈 테 네 토막씩)
    for y, r in (CAGE[0], CAGE[2]):
        for i in range(4):
            a0, a1 = math.radians(i * 90 + 15), math.radians(i * 90 + 105)
            seg(s, (r * math.cos(a0), y, r * math.sin(a0) - 0.05), (r * math.cos(a1), y, r * math.sin(a1) - 0.05), 0.16, bone, R)
    # 심장 얼굴(등뼈 한가운데, 뒤를 봄): 두 자세로 뜀
    heart = faces.Face(eyes="bloodshot", mouth="teeth_ring", blush="deep")
    hc = xf(R, HEART)
    Rb = kit.rot_axis((0, 1, 0), 180) if R is None else R @ kit.rot_axis((0, 1, 0), 180)
    if bend:
        s.head(size=0.85, depth=0.3, at=tuple(hc), face=heart, near=False, rot=kit.euler_of(Rb), open=False, torn=False)
    else:
        for f in s.frames(poses=2):
            with f:
                sz = 0.85 if f.k == 0 else 0.98
                s.head(size=sz, depth=0.3, at=tuple(hc), face=heart, near=False, rot=kit.euler_of(Rb), open=False, torn=False)
    # 갇힌 사람(웅크린 작은 모습)
    for i, (x, y, z) in enumerate(CAPTIVE):
        with s.when(f"on:in{i + 1}"):
            c = np.array((x, y, z))
            lo, hi = c + (-0.24, -0.32, -0.18), c + (0.24, 0.18, 0.18)
            if R is None:
                s.box(tuple(lo), tuple(hi), "wetskin", face=True)
            else:
                s.box(tuple(lo), tuple(hi), "wetskin", face=True, rot=kit.euler_of(R), origin=(0.0, HIP_Y, 0.0))
            s.head(size=0.36, depth=0.32, at=tuple(xf(R, c + (0, 0.36, 0))), face="tears", near=False, open=False, torn=False,
                   rot=None if R is None else kit.euler_of(R))
    # 머리: 처음부터 끝까지 같은 표정
    s.head(size=3.0, depth=2.4, at=tuple(xf(R, (0, 3.05, 0.1))), face="smile", near=False, rot=None if R is None else kit.euler_of(R))


def arm(k):
    m = k.model("g125_arm", poses=1, period=20, shrink=0.35, mut2=False, open=False, torn=False, rag=False)
    for st, curl in (("", 0.0), ("grip", 1.0)):
        s = m.state(st)
        s.box((-0.3, -0.3, -0.3), (0.3, 0.3, 0.3), "bone")
        seg(s, (0, 0, 0), (0, -1.75, 0.05), 0.24, "rod")
        s.box((-0.24, -1.98, -0.2), (0.24, -1.55, 0.26), "bone")
        seg(s, (0, -1.75, 0.05), (0, -3.2, 0.0), 0.22, "rod")
        s.box((-0.36, -3.45, -0.14), (0.36, -3.1, 0.14), "bone")
        # 손가락 다섯(막대기): 벌림 / 오므림
        for i in range(5):
            x = -0.32 + 0.16 * i
            a = (x * 0.4, -3.4, 0.0 if i else 0.12)
            if curl:
                b = (x * 0.3, -3.85, 0.45)
                c = (x * 0.1, -3.7, 0.75)
                seg(s, a, b, 0.11, "rod")
                seg(s, b, c, 0.1, "rod")
            else:
                b = (x * 1.3, -ARM_L + 0.05, 0.12 + (0.25 if i == 0 else 0))
                seg(s, a, b, 0.11, "rod")
        s.front = (0, 0, 0)


def leg(k):
    m = k.model("g125_leg", poses=1, period=20, shrink=0.4, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    s.box((-0.32, -0.3, -0.32), (0.32, 0.3, 0.32), "bone")
    seg(s, (0, 0, 0), (0, -1.8, 0.1), 0.28, "rod")
    s.box((-0.3, -2.05, -0.22), (0.3, -1.6, 0.36), "bone")
    seg(s, (0, -1.8, 0.1), (0, -3.35, 0.0), 0.26, "rod")
    s.box((-0.3, -3.6, -0.3), (0.3, -3.25, 0.3), "bone")
    for x in (-0.22, 0.0, 0.22):
        seg(s, (x, -LEG_L + 0.12, 0.1), (x * 1.4, -LEG_L + 0.06, 0.95), 0.12, "rod")
    s.front = (0, 0, 0)


def build(k: kit.Kit):
    m = k.model(poses=2, period=16, shrink=S, mut2=False, torn=True)
    torso(m.state(""), k)
    b = m.state("bend", poses=1)
    torso(b, k, bend=True)
    b.front = (0, 0.5, 1)
    arm(k)
    leg(k)
    k.icon(model="g125", state="")


def sounds(k: kit.Kit):
    k.sample("step", "tag:giant", "거대한 발걸음", pitch=0.55, attenuation=64)
    k.sample("breath", "tag:breath", "숨소리", pitch=0.5, attenuation=32)
    k.sample("creak", ["kenney_rpg/creak1", "kenney_rpg/creak2"], "갈비 창살 벌어짐", pitch=0.5, attenuation=32)
    k.sample("snap", ["lrsf_horror/bonecrack_03", "oga_breaking75/bfh1_rock_breaking_01"], "갈비 창살 부러짐", pitch=0.8, attenuation=32)


def phrases():
    return {"cause_g125": "갈비 우리 거인에게 집혀 갔다", "cause_g125_step": "갈비 우리 거인에게 밟혔다",
            "cause_g125_sweep": "갈비 우리 거인의 손에 휩쓸렸다", "cause_g125_drop": "우리에서 밀려 나온 사람에 깔렸다"}
