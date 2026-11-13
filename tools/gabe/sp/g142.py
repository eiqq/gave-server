"""G142 담 치는 지네 게이브(4막, 방패, 튼튼이: 길이 28 블록).

지름 1.6 블록 얼굴 마디 열여덟이 한 줄로 이은 지네. 마디마다 굵은 엔드 막대기 다리 한 쌍이 땅을 짚고, 마디 등에는 굳은 얼굴 껍질(뼈 판 위
흰 얼굴)이 덮였다. 마디 양옆에 얼굴이 박혀 지나가며 옆 사람을 본다. 머리 마디는 2.4 블록 얼굴에 막대기 더듬이 둘(원형 DNA),
턱(굵은 막대기 둘)이 좌우로 벌어진다.

- gave:gabe/g142(shrink 0.45): 머리. 몸 가운데(0, 0, 0) = 엔진 pos(바닥 위 HOVER 1.3). 상태 ""(턱 천천히 딱딱, 더듬이 흔들, 앞다리 걸음),
  "bite"(턱 크게 벌렸다 닫음 10틱).
- gave:gabe/g142_seg(shrink 0.6): 몸통 토막 하나 = 마디 셋(z +1.55, 0, -1.55), 토막 가운데가 (0, 0, 0)(바닥 위 0.8). 상태 ""(다리 걸음 자세 4,
  12틱), 부위 crack0, crack1, crack2(그 마디 껍질 깨짐: 껍질 판이 갈라지고 등 얼굴이 핏발).
- gave:gabe/g142_tail(shrink 0.6): 꼬리(작아지는 마디 둘 + 꼬리 가시 둘).
- gave:gabe/g142_bit(shrink 1): 다리 사이에서 떨어지는 작은 지네 조각(0.7 얼굴 + 다리 둘씩).
소리 legs(지네 발소리, 64), bite(머리 물기, 32), tink(껍질 튕김, 24), crack(껍질 깨짐, 32), cut(마디 끊김, 48).
"""
import math

import numpy as np

from gabe import faces, kit

SEG = 1.55
SEG_W, SEG_H, SEG_D = 1.6, 1.4, 1.45
LEG_ROOT = (0.78, -0.05)
LEG_KNEE = (1.75, 0.55)
LEG_FOOT_Y = -0.8          # 토막 가운데 기준 바닥(가운데가 바닥 위 0.8)
SIDE_FACES = ("smile", "smile_a", "half", "smile_c", "tears", "smile")
CRACKED = faces.Face(eyes="bloodshot", mouth="torn", extras=("stitch",))


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def stick(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    up = abs(d[1]) > 0.97 * L
    s.rod(tuple(a), tuple(d), length=L, segs=1, thick=thick, base=False, rag=False, tex=tex, toward=(0, 0, 1) if up else (0, 1, 0))


def leg_pair(s, z, t, phase, dy=0.0, foot_y=LEG_FOOT_Y):
    """마디 하나의 다리 한 쌍: 뿌리 → 무릎(위로 솟음) → 발(바닥). t: 걸음 자리 0..1, 좌우는 반 바퀴 엇갈림. dy: 뿌리, 무릎 높이 옮김"""
    for side in (1, -1):
        ph = 2 * math.pi * (t + phase + (0.5 if side < 0 else 0))
        dz = 0.45 * math.sin(ph)
        lift = max(0.0, math.cos(ph)) * 0.35
        root = (side * LEG_ROOT[0], LEG_ROOT[1] + dy, z)
        knee = (side * LEG_KNEE[0], LEG_KNEE[1] + dy + lift, z + dz * 0.5)
        foot = (side * 2.25, foot_y + lift, z + dz)
        stick(s, root, knee, 0.2)
        stick(s, knee, foot, 0.17)


def segment(s, k, z, i, side_tex):
    """마디 하나: 살 몸통 + 양옆 얼굴 + 등 껍질(깨지면 갈라진 판과 핏발 얼굴)"""
    s.box((-SEG_W / 2, -SEG_H / 2, z - SEG_D / 2), (SEG_W / 2, SEG_H / 2, z + SEG_D / 2), "flesh", face=True)
    # 양옆 얼굴(지나가며 옆 사람을 봄)
    for side, facing in ((1, "east"), (-1, "west")):
        s.quad((side * (SEG_W / 2 + 0.01), 0.05, z), (1.25, 1.2), facing, side_tex[(i + (side > 0)) % len(side_tex)], face=True)
    part = f"crack{i}"
    with s.when(f"!on:{part}"):
        # 굳은 껍질: 뼈 판 + 위를 보는 흰 얼굴
        s.box((-SEG_W / 2 - 0.08, SEG_H / 2 - 0.05, z - SEG_D / 2 - 0.05), (SEG_W / 2 + 0.08, SEG_H / 2 + 0.22, z + SEG_D / 2 + 0.05), "bone",
              faces=("up", "north", "south", "east", "west"))
        s.quad((0, SEG_H / 2 + 0.23, z), (1.3, 1.3), "up", k.face_tex("blank"), face=True)
    with s.when(f"on:{part}"):
        # 깨진 껍질: 두 쪽으로 갈라져 벌어진 판 + 드러난 핏발 얼굴
        for sx in (1, -1):
            c = (sx * 0.5, SEG_H / 2 + 0.12, z)
            s.box((c[0] - 0.42, c[1] - 0.09, z - SEG_D / 2), (c[0] + 0.42, c[1] + 0.09, z + SEG_D / 2), "bone", rot=(0, 0, sx * 28),
                  origin=c)
        s.quad((0, SEG_H / 2 + 0.03, z), (1.2, 1.2), "up", k.face_tex(CRACKED), face=True)


def build(k: kit.Kit):
    side_tex = [k.face_tex(f) for f in SIDE_FACES]

    # 머리
    m = k.model(poses=4, period=12, shrink=0.45, mut2=False)
    for name, jaw_amp, cycles in (("", 10, 1), ("bite", 38, 2)):
        s = m.state(name, period=12 if name == "" else 10)
        h = s.head(size=2.4, depth=1.9, face="smile", at=(0, 0.1, 0.25))
        # 목 살(토막과 이음)
        s.box((-0.85, -0.7, -1.6), (0.85, 0.6, -0.6), "flesh", face=True)
        # 더듬이 둘(원형 DNA): 머리 위 앞에서 앞 위로 뻗어 흔들림
        for side in (1, -1):
            s.rod(h.point(side * 0.55, 0.95, 0.4), (side * 0.45, 0.8, 0.7), length=2.1, segs=2, thick=0.12,
                  wave=kit.Wave(amp=(16, 12), lag=50, phase=0 if side > 0 else 160, cycles=cycles), toward=(1, 0, 0), poses=4)
        # 턱: 아래 앞 모서리에서 앞으로, 좌우로 벌어졌다 닫힘(손으로 짠 자세)
        for f in s.frames():
            with f:
                a = jaw_amp * (0.5 + 0.5 * math.sin(2 * math.pi * cycles * f.t))
                for side in (1, -1):
                    root = np.array(h.point(side * 0.55, -0.75, 0.9))
                    yaw = math.radians(side * (12 + a))
                    d1 = _u((math.sin(yaw), -0.15, math.cos(yaw)))
                    mid = root + d1 * 0.9
                    d2 = _u((-side * 0.55, -0.05, 0.8))
                    stick(s, root, mid, 0.24, "bone")
                    stick(s, mid, mid + d2 * 0.75, 0.18, "bone")
                # 머리 밑 앞다리 한 쌍
                leg_pair(s, -1.1, f.t, 0.0, dy=-0.5, foot_y=-1.3)
        s.front = (0, 0, 1)

    # 몸통 토막(마디 셋)
    sg = k.model("g142_seg", poses=4, period=12, shrink=0.6, mut2=False, open=False, torn=False)
    s = sg.state("")
    for i, z in enumerate((SEG, 0.0, -SEG)):
        segment(s, k, z, i, side_tex)
    # 마디 사이 이음 살
    for z in (SEG / 2, -SEG / 2):
        s.box((-0.55, -0.45, z - 0.2), (0.55, 0.45, z + 0.2), "flesh", face=True)
    for f in s.frames():
        with f:
            for i, z in enumerate((SEG, 0.0, -SEG)):
                leg_pair(s, z, f.t, i * 0.33)
    s.front = (1, 0, 0)

    # 꼬리
    tm = k.model("g142_tail", poses=4, period=20, shrink=0.6, mut2=False, open=False, torn=False)
    s = tm.state("")
    s.box((-0.65, -0.55, 0.3), (0.65, 0.55, 1.5), "flesh", face=True)
    s.quad((0.66, 0, 0.9), (1.0, 1.0), "east", side_tex[2], face=True)
    s.quad((-0.66, 0, 0.9), (1.0, 1.0), "west", side_tex[3], face=True)
    s.box((-0.45, -0.4, -0.9), (0.45, 0.4, 0.3), "flesh", face=True)
    s.box((-0.72, 0.5, 0.25), (0.72, 0.68, 1.55), "bone", faces=("up", "north", "south", "east", "west"))
    for side in (1, -1):
        s.rod((side * 0.25, 0.1, -0.85), (side * 0.35, 0.35, -1), length=1.6, segs=2, thick=0.13, base=False,
              wave=kit.Wave(amp=(14, 10), lag=45, phase=0 if side > 0 else 180), toward=(0, 1, 0), poses=4)
    s.front = (1, 0, 0)

    # 떨어지는 작은 조각
    bm = k.model("g142_bit", poses=4, period=8, shrink=1.0, mut2=False)
    s = bm.state("")
    s.head(size=0.7, depth=0.6, face="smile_a", near=False)
    s.box((-0.36, 0.33, -0.32), (0.36, 0.42, 0.32), "bone", faces=("up", "north", "south", "east", "west"))
    for f in s.frames():
        with f:
            for j, z in enumerate((0.15, -0.2)):
                for side in (1, -1):
                    ph = 2 * math.pi * (f.t + j * 0.5 + (0.5 if side < 0 else 0))
                    dz = 0.15 * math.sin(ph)
                    lift = max(0.0, math.cos(ph)) * 0.1
                    root = (side * 0.33, -0.1, z)
                    knee = (side * 0.62, 0.12 + lift, z + dz * 0.5)
                    foot = (side * 0.8, -0.38 + lift, z + dz)
                    stick(s, root, knee, 0.07)
                    stick(s, knee, foot, 0.06)
    k.icon(model="g142", state="")


def sounds(k: kit.Kit):
    k.sample("legs", "tag:chitter", "지네 발소리", pitch=0.6, attenuation=64, level=-11)
    k.sample("bite", ["kenney_impact/impactpunch_heavy_000", "tag:bone"], "지네 머리 물기", pitch=0.7, attenuation=32)
    k.sample("tink", "tag:metal+hit", "껍질에 튕김", pitch=1.2, attenuation=24, n=2)
    k.sample("crack", "tag:crack", "껍질 깨짐", pitch=0.75, attenuation=32)
    k.sample("cut", "tag:breaking", "마디 끊김", pitch=0.6, attenuation=48)


def phrases():
    return {"cause_g142": "지네 머리에 물렸다", "cause_g142_legs": "지네 다리에 찍혔다", "cause_g142_bit": "지네 조각에 물렸다"}
