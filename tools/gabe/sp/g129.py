"""G129 가로지르는 장대 거인 게이브(3막, 다리로 걷는 것: 키 16 블록).

엔드 막대기 여섯을 다발로 묶은 장대 다리 둘(부품) 위에, _Gave 얼굴 판 수백 장이 비늘처럼 덮은 살 기둥 몸통과 3 블록 얼굴.
무릎(장대 한가운데)마다 작은 얼굴 둘(앞, 뒤)이 박혔다. 몸통 등에는 무릎을 꿇어야 드러나는 큰 얼굴 하나.

- gave:gabe/g129(shrink 0.27): 몸통 + 머리. 몸 가운데(0, 0, 0) = 엔진 pos, 바닥에서 HOVER 11.2(Java G129.HOVER). 엉덩이 이음점 (±HIP_X, HIP_Y, 0).
  상태 ""(서서 걷기: 다리는 Java 가 부품으로), "kneel"(엉덩이를 축으로 몸통이 앞으로 40도 숙임: 등 얼굴이 하늘을 봄).
- gave:gabe/g129_leg(shrink 0.16): 장대 다리 하나. 엉덩이가 (0, 0, 0), 발이 (0, -LEG_L, 0). Java 가 엉덩이에서 발 쪽으로 돌리고 길이를 맞춰 늘인다.
  부위 "broke": 무릎 얼굴이 깨짐(핏발, 찢어진 입).
소리 stride(장대 걸음, 96), kneel(무릎 꺾임, 64), fall(쓰러짐, 96).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.27
HOVER = 11.2
HIP_X, HIP_Y = 1.3, -2.45
TORSO = (2.6, 4.2, 2.0)           # 폭, 높이, 깊이(아래 y = -2.3)
TORSO_Y0 = -2.3
HEAD = 3.0
HEAD_Y = 3.35
KNEEL = 40.0                       # 무릎 꿇을 때 숙이는 각(도)
LEG_S = 0.16
LEG_L = 9.0
KNEE_Y = -4.5
SCALE_FACES = ("smile", "smile_a", "smile_c", "half", "smile", "open")


def xf(R, pivot, p):
    p = np.array(p, float)
    return R @ (p - pivot) + pivot


def tbox(s, R, pivot, frm, to, tex, **kw):
    if R is None:
        return s.box(frm, to, tex, **kw)
    return s.box(frm, to, tex, rot=kit.euler_of(R), origin=tuple(pivot), **kw)


def tquad(s, R, pivot, c, size, facing, tex, tilt_axis, tilt):
    T = kit.rot_axis(tilt_axis, tilt)
    M = T if R is None else R @ T
    c2 = c if R is None else xf(R, pivot, c)
    s.quad(tuple(c2), size, facing, tex, face=True, rot=kit.euler_of(M), origin=tuple(c2))


def body(s, k, R=None):
    """몸통(비늘 얼굴), 골반, 목, 머리, 등 얼굴. R: 엉덩이 축 회전(숙임)"""
    pivot = np.array((0.0, HIP_Y, 0.0))
    W, H, D = TORSO
    y0, y1 = TORSO_Y0, TORSO_Y0 + H
    flesh = "flesh"
    tbox(s, R, pivot, (-W / 2, y0, -D / 2), (W / 2, y1, D / 2), flesh, face=True)
    # 골반(다리가 붙는 가로 살덩이), 엉덩이 마디
    tbox(s, R, pivot, (-1.85, HIP_Y - 0.45, -1.0), (1.85, HIP_Y + 0.45, 1.0), flesh, face=True)
    # 목(살), 어깨 둔덕
    tbox(s, R, pivot, (-0.7, y1 - 0.1, -0.55), (0.7, HEAD_Y - HEAD / 2 + 0.3, 0.55), flesh, face=True)
    tbox(s, R, pivot, (-W / 2 - 0.25, y1 - 0.8, -D / 2 - 0.1), (W / 2 + 0.25, y1 - 0.1, D / 2 + 0.1), flesh, face=True)
    # 비늘: 몸통 네 면을 덮는 얼굴 판(조금씩 기울여 겹침)
    texs = [k.face_tex(f) for f in SCALE_FACES]
    rng = np.random.RandomState(129)
    q = 0.5
    n = 0
    for side, (facing, axis) in enumerate((("south", (0, 0, 1)), ("north", (0, 0, 1)), ("east", (1, 0, 0)), ("west", (1, 0, 0)))):
        span = W if side < 2 else D
        cols = max(1, int(round(span / 0.42)))
        rows = int(round(H / 0.42))
        for r in range(rows):
            for c in range(cols):
                u = -span / 2 + (c + 0.5) * span / cols + (0.1 if r % 2 else -0.1)
                v = y0 + (r + 0.5) * H / rows
                if side == 1 and abs(u) < 0.95 and HIP_Y + 0.9 < v < HIP_Y + 3.1:
                    continue  # 등 얼굴 자리
                lift = 0.03 + 0.02 * (r % 2)
                if facing == "south":
                    c3 = (u, v, D / 2 + lift)
                elif facing == "north":
                    c3 = (u, v, -D / 2 - lift)
                elif facing == "east":
                    c3 = (W / 2 + lift, v, u)
                else:
                    c3 = (-W / 2 - lift, v, u)
                tquad(s, R, pivot, np.array(c3), (q, q), facing, texs[rng.randint(len(texs))], axis, float(rng.uniform(-14, 14)))
                n += 1
    # 머리: 흙먼지에 반쯤 잠긴 3 블록 얼굴(멀리서만 보여 가까운 얼굴 없음)
    hc = np.array((0.0, HEAD_Y, 0.15))
    hr = None if R is None else kit.euler_of(R)
    s.head(size=HEAD, depth=2.3, at=tuple(hc if R is None else xf(R, pivot, hc)), face="smile", near=False, rot=hr)
    # 등 얼굴(무릎을 꿇으면 하늘을 봄): 뒤(-Z)를 보게 반 바퀴
    bc = np.array((0.0, HIP_Y + 2.0, -D / 2 - 0.12))
    Rb = kit.rot_axis((0, 1, 0), 180) if R is None else R @ kit.rot_axis((0, 1, 0), 180)
    s.head(size=1.75, height=1.9, depth=0.22, at=tuple(bc if R is None else xf(R, pivot, bc)), face="half", near=False, rot=kit.euler_of(Rb),
           open=False, torn=False)
    return n


def leg(k):
    m = k.model("g129_leg", poses=1, period=20, shrink=LEG_S, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    # 엉덩이 마디
    s.box((-0.48, -0.42, -0.48), (0.48, 0.38, 0.48), "flesh", face=True)
    # 엔드 막대기 여섯 다발(아래로 조금 모임), 두 마디
    for i in range(6):
        a = math.radians(i * 60 + 15)
        r0, r1 = 0.33, 0.22
        top = np.array((math.cos(a) * r0, -0.2, math.sin(a) * r0))
        bot = np.array((math.cos(a) * r1, -LEG_L + 0.35, math.sin(a) * r1))
        d = bot - top
        s.rod(tuple(top), tuple(d), length=float(np.linalg.norm(d)), segs=2, thick=0.2, base=False, toward=(0, 0, 1), rag=False)
    # 묶은 살 띠 셋
    for y in (-1.0, -7.6):
        s.box((-0.46, y - 0.18, -0.46), (0.46, y + 0.18, 0.46), "flesh", face=True)
    # 무릎: 굵은 살 매듭 + 앞뒤 작은 얼굴 둘
    s.box((-0.5, KNEE_Y - 0.55, -0.5), (0.5, KNEE_Y + 0.55, 0.5), "flesh", face=True)
    broke = faces.Face(eyes="bloodshot", mouth="torn", extras=("stitch",))
    for rot, z in ((None, 0.52), ((0, 180, 0), -0.52)):
        with s.when("!on:broke"):
            s.head(size=0.95, depth=0.22, at=(0, KNEE_Y, z), face="smile", near=False, rot=rot)
        with s.when("on:broke"):
            s.head(size=0.95, depth=0.22, at=(0, KNEE_Y, z * 0.9), face=broke, near=False, rot=(rot[0], rot[1], 18) if rot else (0, 0, -18))
    # 발: 막대기 끝이 땅에서 벌어짐
    for i in range(4):
        a = math.radians(i * 90 + 45)
        s.rod((0, -LEG_L + 0.45, 0), (math.cos(a), -0.55, math.sin(a)), length=0.75, segs=1, thick=0.17, base=False, rag=False)
    s.front = (0, 0, 0)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=S, mut2=False, torn=True)
    st = m.state("")
    body(st, k)
    kn = m.state("kneel")
    body(kn, k, kit.rot_axis((1, 0, 0), KNEEL))
    kn.front = (0, 1, 0.3)
    leg(k)
    k.icon(model="g129", state="")


def sounds(k: kit.Kit):
    k.sample("stride", "tag:giant", "장대 걸음", pitch=0.5, attenuation=96, level=-10)
    k.sample("kneel", ["oga_breaking75/bfh1_rock_breaking_01", "lrsf_horror/bonecrack_03"], "장대 무릎 꺾임", pitch=0.55, attenuation=64)
    k.sample("fall", "tag:quake", "장대 거인 쓰러짐", pitch=0.7, attenuation=96, level=-10)


def phrases():
    return {"cause_g129": "장대 거인에게 밟혔다", "cause_g129_wave": "장대 걸음 충격파에 휩쓸렸다", "cause_g129_fall": "쓰러지는 장대 거인에 깔렸다"}
