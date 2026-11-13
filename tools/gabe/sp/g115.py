"""G115 진드기 덮인 거인 게이브(3막, 거인 인형: 키 7.4 블록 사람꼴).

미끈한 분홍 살의 사람꼴 거인. 2.5 블록 _Gave 머리, 온몸(몸통, 머리, 어깨)에 손톱만 한 얼굴 진드기 스물넷(엔드 막대기 다리 넷)이 다닥다닥 붙어 꿈틀댄다.
등에서 굵은 엔드 막대기 둘이 등뼈처럼 솟았다. 팔은 몸 모델 안 자세(걷기 흔듦, 양팔 내려치기), 다리 둘은 부품(Java 가 걸음).

- gave:gabe/g115(shrink 0.32): 가슴 가운데(0, 0, 0) = 엔진 pos, 바닥에서 HOVER 4(Java G115.HOVER). 엉덩이 (±HIP_X, HIP_Y, 0).
  상태 ""(걷기: 팔 앞뒤로 흔듦, 24틱 자세 4), "slam"(양팔을 치켜들었다 앞 바닥으로 내려침, 20틱 자세 4: 자세 2 에 닿음).
  부위 t1..t4: 진드기 무리 하나씩 떨어져 나감(t4 까지 켜지면 매끈한 살만 남음).
- gave:gabe/g115_leg(shrink 0.5): 엉덩이 (0, 0, 0), 발바닥 (0, -LEG_L, 0).
- gave:gabe/g115_tick: 떨어진 진드기(얼굴 0.3 + 막대기 다리 넷, 기어 감 8틱 자세 2).
소리 shed(진드기 떨어짐, 24), step(거인 걸음, 48), slam(양팔 내려치기, 48), bite(진드기 물기, 12).
"""
import math

import numpy as np

from gabe import kit

S = 0.32
HOVER = 4.0
HIP_X, HIP_Y = 0.72, -1.25
SH_X, SH_Y = 1.5, 0.8
LEG_L = 2.8
TORSO = (2.6, 2.5, 1.5)
TORSO_Y0 = -1.45
HEAD, HEAD_Y = 2.5, 2.25
ARM = (1.6, 1.5)   # 위팔, 아래팔 길이
TICK_FACES = ("smile", "smile_a", "smile_c", "half")


def ticks(s, k):
    """몸에 붙은 진드기 스물넷을 네 무리로(t1..t4 가 켜지면 그 무리가 사라짐). 꿈틀(자세 2)"""
    rng = np.random.RandomState(115)
    W, H, D = TORSO
    spots = []
    # 몸통 앞 10, 뒤 6, 옆 4, 머리 4
    for _ in range(10):
        spots.append(((rng.uniform(-1.1, 1.1), TORSO_Y0 + rng.uniform(0.2, 2.3), D / 2 + 0.03), "south"))
    for _ in range(6):
        spots.append(((rng.uniform(-1.1, 1.1), TORSO_Y0 + rng.uniform(0.2, 2.3), -D / 2 - 0.03), "north"))
    for side in (1, -1):
        for _ in range(2):
            spots.append(((side * (W / 2 + 0.03), TORSO_Y0 + rng.uniform(0.3, 2.2), rng.uniform(-0.5, 0.5)), "east" if side > 0 else "west"))
    for _ in range(4):
        spots.append(((rng.uniform(-0.9, 0.9), HEAD_Y + rng.uniform(-1.0, 0.2), 1.0 + 0.04), "south"))
    order = rng.permutation(len(spots))
    for gi in range(4):
        with s.when(f"!on:t{gi + 1}"):
            for f in s.frames(poses=2):
                with f:
                    for j in order[gi * 6:(gi + 1) * 6]:
                        (x, y, z), facing = spots[j]
                        wig = 0.04 if f.k else -0.04
                        tex = k.face_tex(TICK_FACES[j % len(TICK_FACES)])
                        sz = 0.36
                        if facing in ("south", "north"):
                            c = (x + wig, y, z)
                            s.quad(c, (sz, sz), facing, tex, face=True)
                            s.box((x - 0.2, y - 0.025, z - 0.01), (x + 0.2, y + 0.025, z + 0.01), "rod", faces=(facing,), rot=(0, 0, 30 + 12 * f.k),
                                  origin=c)
                            s.box((x - 0.2, y - 0.025, z - 0.01), (x + 0.2, y + 0.025, z + 0.01), "rod", faces=(facing,), rot=(0, 0, -30 - 12 * f.k),
                                  origin=c)
                        else:
                            c = (x, y + wig, z)
                            s.quad(c, (sz, sz), facing, tex, face=True)
                            s.box((x - 0.01, y - 0.025, z - 0.2), (x + 0.01, y + 0.025, z + 0.2), "rod", faces=(facing,), rot=(30 + 12 * f.k, 0, 0),
                                  origin=c)
                            s.box((x - 0.01, y - 0.025, z - 0.2), (x + 0.01, y + 0.025, z + 0.2), "rod", faces=(facing,), rot=(-30 - 12 * f.k, 0, 0),
                                  origin=c)


def body(s, k):
    W, H, D = TORSO
    s.box((-W / 2, TORSO_Y0, -D / 2), (W / 2, TORSO_Y0 + H, D / 2), "flesh", face=True)
    s.box((-1.15, HIP_Y - 0.4, -0.7), (1.15, HIP_Y + 0.35, 0.7), "flesh", face=True)
    s.box((-0.55, TORSO_Y0 + H - 0.1, -0.45), (0.55, HEAD_Y - HEAD / 2 + 0.2, 0.45), "flesh", face=True)
    s.head(size=HEAD, depth=2.0, at=(0, HEAD_Y, 0.05), face="smile", near=True)
    # 등뼈처럼 솟은 굵은 막대기 둘
    for x in (0.45, -0.45):
        s.rod((x, 0.4, -D / 2 + 0.1), (x * 0.3, 1, -0.45), length=1.8, segs=2, thick=0.22, base=True, toward=(0, 0, 1), rag=False)
    ticks(s, k)


def arm_at(s, side, a_sh, a_el):
    """팔 하나: 어깨에서 a_sh 도(앞으로 + 이면 앞으로 듦), 팔꿈치 a_el 도 더 굽힘. 상자 셋(위팔, 아래팔, 손)"""
    sh = np.array((side * SH_X, SH_Y, 0.0))
    R1 = kit.rot_axis((1, 0, 0), -a_sh)
    el = sh + R1 @ np.array((0, -ARM[0], 0))
    R2 = R1 @ kit.rot_axis((1, 0, 0), -a_el)
    hand = el + R2 @ np.array((0, -ARM[1], 0))
    e1 = kit.euler_of(R1)
    e2 = kit.euler_of(R2)
    s.box(tuple(sh + (-0.42, -ARM[0], -0.42)), tuple(sh + (0.42, 0.25, 0.42)), "flesh", face=True, rot=e1, origin=tuple(sh))
    s.box(tuple(el + (-0.38, -ARM[1], -0.38)), tuple(el + (0.38, 0.1, 0.38)), "flesh", face=True, rot=e2, origin=tuple(el))
    s.box(tuple(hand + (-0.5, -0.55, -0.42)), tuple(hand + (0.5, 0.15, 0.42)), "flesh", face=True, rot=e2, origin=tuple(hand))


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, shrink=S, mut2=False, torn=True)
    w = m.state("")
    body(w, k)
    for f in w.frames():
        with f:
            sw = 22 * math.sin(2 * math.pi * f.t)
            arm_at(w, 1, sw, 10)
            arm_at(w, -1, -sw, 10)
    sl = m.state("slam", poses=4, period=20)
    body(sl, k)
    for f, (a, e) in zip(sl.frames(), ((150, 10), (165, 0), (60, 10), (40, 20))):
        with f:
            arm_at(sl, 1, a, e)
            arm_at(sl, -1, a, e)
    # 다리
    lm = k.model("g115_leg", poses=1, period=20, shrink=0.5, mut2=False, open=False, torn=False, rag=False)
    ls = lm.state("")
    ls.box((-0.55, -1.45, -0.55), (0.55, 0.3, 0.55), "flesh", face=True)
    ls.box((-0.48, -2.55, -0.48), (0.48, -1.3, 0.48), "flesh", face=True)
    ls.box((-0.55, -LEG_L, -0.6), (0.55, -2.45, 0.85), "flesh", face=True)
    ls.front = (0, 0, 0)
    # 떨어진 진드기
    tm = k.model("g115_tick", poses=2, period=8, mut2=False, open=True, torn=False, rag=False)
    ts = tm.state("")
    ts.head(size=0.32, depth=0.24, at=(0, 0, 0), face="smile", near=False, torn=False)
    for f in ts.frames():
        with f:
            for i, (x, z) in enumerate(((0.13, 0.08), (-0.13, 0.08), (0.13, -0.08), (-0.13, -0.08))):
                lift = 0.06 if (i + f.k) % 2 else 0.0
                side = 1 if x > 0 else -1
                ts.rod((x, -0.05, z), (side, -0.7 + lift * 5, z * 2), length=0.26, segs=1, thick=0.04, base=False, rag=False)
    k.icon(model="g115", state="")


def sounds(k: kit.Kit):
    k.sample("shed", "tag:bug", "진드기 떨어짐", pitch=0.9, attenuation=24)
    k.sample("step", "tag:giant", "거인의 쿵 걸음", pitch=0.65, attenuation=48)
    k.sample("slam", ["kenney_impact/impactpunch_heavy_000", "kenney_impact/impactpunch_heavy_004"], "양팔 내려치기", pitch=0.5, attenuation=48)
    k.sample("bite", "tag:bug", "진드기 물기", pitch=1.3, attenuation=12)


def phrases():
    return {"cause_g115": "진드기 덮인 거인에게 내리찍혔다", "cause_g115_tick": "얼굴 진드기에게 물렸다"}
