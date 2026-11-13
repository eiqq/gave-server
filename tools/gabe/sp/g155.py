"""G155 말뚝에 묶인 거인 게이브(4막, 목표 압박, 보상: 길이 11 블록).

땅에 엎드려 누운 사람꼴 거인. 3 블록 얼굴은 한쪽 볼을 땅에 대고 앞(트럭 쪽)을 본다. 분홍 살 밧줄이 몸을 가로질러 일곱 군데에서 굵은
엔드 막대기 말뚝(높이 2 블록)에 묶였고, 말뚝 꼭대기마다 작은 얼굴 매듭. 등에는 작은 얼굴 수십이 따개비처럼 붙었다.

- gave:gabe/g155(shrink 0.18): 몸 가운데(0, 0, 0) = 엔진 pos.
  누운 상태(바닥 위 HOVER 1.0, 배 밑 y = -1): ""(묶여 누움, 등 따개비 얼굴 들썩), "throw"(한 팔로 흙덩이를 들어 던짐, 20틱 자세 4),
  "crawl"(두 팔로 앞을 짚고 기어 옴, 24틱 자세 4), "rise"(윗몸을 일으켜 두 팔을 머리 위로).
  "stand"(일어선 거인, 바닥 위 6.0, 걸음 자세 4), "sit"(트럭 위에 주저앉음: 엉덩이 y = -1, Java 가 트럭 지붕 높이에 맞춤).
  부위 s0..s6: 그 말뚝이 뽑힘(말뚝, 매듭 얼굴, 밧줄이 사라짐). 말뚝 자리(몸 틀, 블록)는 Java G155.STAKES 와 같아야 한다.
- gave:gabe/g155_worker: 일꾼(손톱만 한 얼굴 0.45 + 갉는 이빨, 다리 넷). gave:gabe/g155_clod: 던지는 흙덩이(얼굴 박힌 흙).
소리 stake(말뚝 뽑히는 징 소리, 128), roar(거인 포효, 96), throw(흙덩이 던짐, 32), slam(내려치기, 64), cheer(떼의 환호, 96), gnaw(갉음, 16).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.18
STAKES = ((3.3, 3.2), (-3.3, 3.0), (3.5, 0.2), (-3.5, -0.2), (3.2, -2.8), (-3.2, -3.0), (0.0, -7.3))   # (옆 x, 앞 z), 바닥 y = -1
STAKE_H = 2.0
BARNACLES = ("smile", "smile_a", "smile_c", "half", "tears", "smile", "deep", "smile_a", "stitch", "smile_c")


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def stick(s, a, b, thick, tex="flesh", max_len=5.5):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    up = abs(d[1]) > 0.97 * L
    n = max(1, int(math.ceil(L / max_len)))
    for i in range(n):
        s.rod(tuple(a + d * (i / n)), tuple(d), length=L / n, segs=1, thick=thick, base=False, rag=False, tex=tex,
              toward=(0, 0, 1) if up else (0, 1, 0))


def stakes(s, k):
    """말뚝 일곱(부위 s0..s6): 엔드 막대기 말뚝 + 꼭대기 매듭 얼굴 + 몸 위로 넘어가는 살 밧줄"""
    for i, (x, z) in enumerate(STAKES):
        with s.when(f"!on:s{i}"):
            stick(s, (x, -1.05, z), (x * 1.04, -1.05 + STAKE_H, z), 0.32, "rod")
            s.head(size=0.6, depth=0.5, at=(x * 1.04, -1.05 + STAKE_H + 0.25, z), face="smile_a" if i % 2 else "smile_c", near=False,
                   rot=(0, 90 if x > 0 else (-90 if x < 0 else 0), 0), open=False, torn=False)
            # 밧줄: 말뚝 꼭대기에서 몸 등 위 가운데로(꼬리 말뚝은 발목으로)
            top = np.array((x * 1.02, -1.05 + STAKE_H - 0.15, z))
            to = np.array((0.0, 0.75, z * 0.95)) if x != 0 else np.array((0.0, -0.3, -6.2))
            d = to - top
            s.band(tuple(top), tuple(d), length=float(np.linalg.norm(d)), width=0.4, segs=1, thick=0.04, tex="flesh",
                   toward=(0, 0, 1) if x != 0 else (1, 0, 0))


def barnacles(s, k, bob):
    """등 따개비 얼굴(위를 봄). bob 이면 자세마다 들썩"""
    rng = np.random.RandomState(155)
    spots = [(rng.uniform(-1.5, 1.5), rng.uniform(-2.2, 2.0), BARNACLES[i % len(BARNACLES)], rng.uniform(0.45, 0.7)) for i in range(14)]
    if not bob:
        for x, z, f, sz in spots:
            s.quad((x, 0.64, z), (sz, sz), "up", k.face_tex(f), face=True, rot=(0, float(rng.uniform(-30, 30)), 0), origin=(x, 0.64, z))
        return
    for fr in s.frames(poses=2):
        with fr:
            for j, (x, z, f, sz) in enumerate(spots):
                h = 0.08 if (j + fr.k) % 2 else 0.0
                s.quad((x, 0.64 + h, z), (sz, sz), "up", k.face_tex(f), face=True)


def prone(s, k, arms="side", rise=0.0, bob=True, with_stakes=True):
    """엎드려 누운 몸. arms: side(몸 옆에 묶임), none(팔은 따로 자세로). rise: 윗몸을 일으킨 각(도, 엉덩이 축)"""
    R = kit.rot_axis((1, 0, 0), -rise) if rise else None
    piv = np.array((0.0, -0.6, -2.4))

    def T(p):
        p = np.array(p, float)
        return tuple(R @ (p - piv) + piv) if R is not None else tuple(p)

    rot = (-rise, 0, 0) if rise else None
    # 몸통(배 밑 y = -1), 엉덩이, 다리 둘(뒤로 뻗음)
    s.box((-1.8, -1.0, -2.5), (1.8, 0.6, 2.4), "flesh", face=True, rot=rot, origin=tuple(piv) if rise else None)
    s.box((-1.5, -1.0, -3.6), (1.5, 0.3, -2.3), "flesh", face=True)
    for side in (1, -1):
        stick(s, (side * 0.85, -0.45, -3.4), (side * 1.0, -0.5, -7.0), 1.0)
        s.box((side * 1.0 - 0.55, -1.0, -7.4), (side * 1.0 + 0.55, -0.1, -6.6), "flesh", face=True)
    # 머리: 볼을 땅에 대고 앞을 봄(z 축으로 80도 누움)
    hc = T((0.2, 0.55, 4.0))
    s.head(size=3.0, depth=2.2, at=hc, face="smile", near=True, rot=(-rise, 0, 80) if rise else (0, 0, 80))
    # 목
    stick(s, T((0, -0.1, 2.2)), T((0.1, 0.2, 3.0)), 1.2)
    if not rise:
        barnacles(s, k, bob)
    if arms == "side":
        for side in (1, -1):
            sh = np.array((side * 2.0, -0.35, 1.9))
            el = np.array((side * 2.35, -0.6, -0.4))
            ha = np.array((side * 2.2, -0.7, -2.4))
            stick(s, sh, el, 0.85)
            stick(s, el, ha, 0.75)
            s.box(tuple(ha - 0.5), tuple(ha + 0.5), "flesh", face=True)
    if with_stakes:
        stakes(s, k)


def arm(s, sh, el, ha):
    stick(s, sh, el, 0.85)
    stick(s, el, ha, 0.75)
    ha = np.array(ha, float)
    s.box(tuple(ha - 0.5), tuple(ha + 0.5), "flesh", face=True)


def upright(s, k, legs_t=None, sit=False):
    """일어선 몸(바닥 위 6.0, 발바닥 y = -6) 또는 주저앉은 몸(엉덩이 y = -1, 다리는 앞 아래로)"""
    if sit:
        s.box((-1.9, -1.0, -1.2), (1.9, 3.4, 1.2), "flesh", face=True)
        for side in (1, -1):
            stick(s, (side * 1.0, -0.6, 0.3), (side * 1.3, -0.9, 3.6), 1.05)
            stick(s, (side * 1.3, -0.9, 3.6), (side * 1.4, -3.3, 4.2), 0.95)
        s.head(size=3.0, depth=2.2, at=(0, 5.0, 0.4), face="smile", near=True)
        for side in (1, -1):
            arm(s, (side * 2.2, 2.8, 0.2), (side * 2.9, 4.6, 1.4), (side * 2.4, 6.2, 2.4))
        rng = np.random.RandomState(56)
        for j in range(10):
            x, y = rng.uniform(-1.5, 1.5), rng.uniform(-0.5, 3.0)
            s.quad((x, y, -1.22), (0.55, 0.55), "north", k.face_tex(BARNACLES[j % len(BARNACLES)]), face=True)
        return
    s.box((-1.9, -1.2, -1.1), (1.9, 3.2, 1.1), "flesh", face=True)
    s.box((-1.6, -2.0, -0.9), (1.6, -1.0, 0.9), "flesh", face=True)
    s.head(size=3.0, depth=2.2, at=(0, 4.8, 0.3), face="smile", near=True)
    rng = np.random.RandomState(57)
    for j in range(10):
        x, y = rng.uniform(-1.5, 1.5), rng.uniform(-1.0, 2.8)
        s.quad((x, y, -1.12), (0.55, 0.55), "north", k.face_tex(BARNACLES[j % len(BARNACLES)]), face=True)
    for fr in s.frames():
        with fr:
            a = math.sin(2 * math.pi * fr.t)
            for side in (1, -1):
                sw = a * side * 0.9
                hip = np.array((side * 0.9, -1.8, 0.0))
                knee = np.array((side * 1.0, -4.0, 0.3 + sw))
                foot = np.array((side * 1.0, -5.85 + max(0.0, side * a) * 0.5, sw * 1.4))
                stick(s, hip, knee, 1.05)
                stick(s, knee, foot, 0.95)
                s.box((foot[0] - 0.55, foot[1] - 0.15, foot[2] - 0.5), (foot[0] + 0.55, foot[1] + 0.35, foot[2] + 0.9), "flesh", face=True)
                arm(s, (side * 2.15, 2.6, 0.0), (side * 2.5, 0.7, -sw * 0.8), (side * 2.4, -1.0, -sw * 1.4 + 0.3))


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, shrink=S, mut2=False)
    prone(m.state(""), k)
    # 던지기: 오른팔(-X)이 흙덩이를 들어 머리 위로 넘겨 던짐
    th = m.state("throw", period=20)
    prone(th, k, arms="none", bob=False)
    arm(th, (2.0, -0.35, 1.9), (2.35, -0.6, -0.4), (2.2, -0.7, -2.4))
    angles = (10, 90, 160, 60)
    for f in th.frames():
        with f:
            a = math.radians(angles[f.k])
            sh = np.array((-2.0, -0.2, 1.9))
            d = _u((-0.35, math.sin(a), -math.cos(a)))
            el = sh + d * 2.4
            ha = el + _u((-0.1, math.sin(a + 0.4), -math.cos(a + 0.4))) * 2.1
            arm(th, sh, el, ha)
    # 기어 옴: 두 팔을 번갈아 앞으로 짚음
    cr = m.state("crawl")
    prone(cr, k, arms="none", bob=False)
    for f in cr.frames():
        with f:
            for side in (1, -1):
                ph = 2 * math.pi * f.t + (0 if side > 0 else math.pi)
                reach = 2.6 + 1.4 * math.sin(ph)
                lift = max(0.0, math.cos(ph)) * 0.8
                sh = np.array((side * 2.0, -0.2, 1.9))
                ha = np.array((side * 3.0, -0.6 + lift, 1.9 + reach + 1.6))
                el = (sh + ha) / 2 + np.array((side * 0.9, 0.7 + lift * 0.5, 0))
                arm(cr, sh, el, ha)
    # 윗몸 일으킴(두 팔 머리 위)
    ri = m.state("rise")
    prone(ri, k, arms="none", rise=38, bob=False)
    for side in (1, -1):
        arm(ri, (side * 2.0, 1.4, 1.2), (side * 2.6, 3.4, 1.9), (side * 1.8, 5.2, 2.8))
    st = m.state("stand")
    upright(st, k)
    st.front = (0, 0, 1)
    si = m.state("sit", poses=1)
    upright(si, k, sit=True)
    si.front = (0, 0, 1)

    wk = k.model("g155_worker", poses=4, period=8, mut2=False, torn=False)
    s = wk.state("")
    h = s.head(size=0.45, depth=0.38, face=faces.Face(eyes="half", mouth="teeth_ring"), near=False)
    for f in s.frames():
        with f:
            for j, z in enumerate((0.1, -0.12)):
                for side in (1, -1):
                    ph = 2 * math.pi * (f.t + j * 0.5 + (0.5 if side < 0 else 0))
                    lift = max(0.0, math.cos(ph)) * 0.08
                    stick(s, (side * 0.2, -0.08, z), (side * 0.42, 0.06 + lift, z + 0.08 * math.sin(ph)), 0.05, "rod")
                    stick(s, (side * 0.42, 0.06 + lift, z + 0.08 * math.sin(ph)), (side * 0.52, -0.25 + lift, z + 0.12 * math.sin(ph)), 0.05, "rod")
    cl = k.model("g155_clod", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    s = cl.state("")
    s.box((-0.45, -0.4, -0.45), (0.45, 0.4, 0.45), "flesh", face=True)
    s.quad((0, 0, 0.46), (0.7, 0.7), "south", k.face_tex("tears"), face=True)
    s.quad((0, 0.41, 0), (0.6, 0.6), "up", k.face_tex("smile_a"), face=True)
    s.front = (0, 0, 0)
    k.icon(model="g155", state="")


def sounds(k: kit.Kit):
    k.sample("stake", "tag:gong", "말뚝 뽑히는 징 소리", pitch=0.7, attenuation=128, level=-10)
    k.sample("roar", "tag:boss_roar", "묶인 거인의 포효", pitch=0.6, attenuation=96, level=-10)
    k.sample("throw", "tag:grunt", "흙덩이 던짐", pitch=0.55, attenuation=32)
    k.sample("slam", "tag:slam", "거인 내려치기", pitch=0.6, attenuation=64)
    k.sample("cheer", "tag:horde_cry", "떼의 환호", pitch=0.9, attenuation=96)
    k.sample("gnaw", "tag:eat", "일꾼이 매듭을 갉음", pitch=1.3, attenuation=16)


def phrases():
    p = {"cause_g155": "말뚝에 묶인 거인에게 맞았다", "cause_g155_sit": "트럭 위에 주저앉는 거인에 깔렸다", "cause_g155_worker": "일꾼 게이브에 물렸다",
         "notice_g155_free": "거인이 일어섰다"}
    for n in range(7):
        p[f"notice_g155_left_{n}"] = f"남은 말뚝 {n}"
    return p
