"""G149 안 보이는 거인 게이브(4막, 정예, 돌격: 키 10 블록).

평소에는 보이지 않는다. 보이는 것은 등과 어깨에 꽂힌 채 허공에 떠서 걸어가는 엔드 막대기 여섯뿐이다(Java 가 발 디딘 자리에 흙먼지 고리).
번쩍이면(조명탄, 폭발, 불, 곁 총구 불빛) 다리 넷에 목이 긴 짐승 꼴 몸 전체가 드러난다. 몸은 온통 얼굴 가죽(얼굴 판이 비늘처럼 덮음),
목 끝에 2.5 블록 얼굴. Java 가 드러나는 순간 맞은 번쩍임을 길게 걸어 흰 윤곽으로 보이게 한다.

- gave:gabe/g149(shrink 0.2): 몸 가운데(0, 0, 0) = 엔진 pos(바닥 위 HOVER 5.0, 발바닥 y = -5).
  상태 ""(안 보임: 막대기 여섯만, 걸음에 맞춰 출렁), "seen"(드러남: 몸 전체, 목을 든 자세), "low"(드러남: 목을 숙여 앞 아래를 무는 자세).
소리 pulse(빨라지는 두근거림, 48), step(보이지 않는 발걸음, 64), bite(물어 올림, 32), roar(드러남 울음, 64).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.2
HOVER = 5.0
TORSO = ((-1.6, -0.4, -3.0), (1.6, 1.8, 2.6))
LEGS = ((1.3, 0.0, 2.1), (-1.3, 0.0, 2.1), (1.3, 0.0, -2.3), (-1.3, 0.0, -2.3))
NECK_ROOT = (0.0, 1.2, 2.3)
HEAD = 2.5
HIGH = (0.0, 4.1, 4.9)        # 목을 든 머리 가운데
LOW = (0.0, -1.4, 6.2)        # 목을 숙인 머리 가운데
RODS = (((1.0, 1.8, -2.2), (0.5, 1.0, -0.4)), ((-1.0, 1.8, -2.2), (-0.6, 1.0, -0.2)), ((1.25, 1.8, 0.1), (0.8, 1.0, 0.3)),
        ((-1.25, 1.8, -0.1), (-0.7, 1.0, 0.1)), ((0.9, 1.7, 1.9), (0.4, 1.0, 0.6)), ((-0.9, 1.7, 1.9), (-0.5, 1.0, 0.5)))
SKIN = ("smile", "smile_a", "smile_c", "half", "blank", "tears", "smile", "stitch")


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def stick(s, a, b, thick, tex="flesh", max_len=6.0):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    up = abs(d[1]) > 0.97 * L
    n = max(1, int(math.ceil(L / max_len)))
    for i in range(n):
        s.rod(tuple(a + d * (i / n)), tuple(d), length=L / n, segs=1, thick=thick, base=False, rag=False, tex=tex,
              toward=(0, 0, 1) if up else (0, 1, 0))


def rods(s, bob):
    """등과 어깨에 꽂힌 막대기 여섯(원형 DNA: 살짝 흔들림). bob: 걸음 출렁 높이(0 이면 가만히)"""
    for i, (root, d) in enumerate(RODS):
        s.rod(root, d, length=2.3, segs=2, thick=0.14, base=False,
              wave=kit.Wave(amp=(7 + bob * 10, 5), lag=40, phase=i * 60, cycles=1), toward=(1, 0, 0), poses=4)


def body(s, k, head_at):
    lo, hi = TORSO
    s.box(lo, hi, "flesh", face=True)
    # 얼굴 가죽: 몸통 옆, 위, 앞뒤를 덮는 얼굴 판(조금씩 기울여 겹침)
    rng = np.random.RandomState(149)
    texs = [k.face_tex(f) for f in SKIN]
    for facing, axis in (("east", (1, 0, 0)), ("west", (1, 0, 0)), ("up", (0, 1, 0))):
        if facing == "up":
            us, vs = np.linspace(lo[0] + 0.55, hi[0] - 0.55, 3), np.linspace(lo[2] + 0.6, hi[2] - 0.6, 6)
        else:
            us, vs = np.linspace(lo[2] + 0.6, hi[2] - 0.6, 6), np.linspace(lo[1] + 0.55, hi[1] - 0.55, 2)
        for a in us:
            for b in vs:
                if facing == "east":
                    c = (hi[0] + 0.02, b, a)
                elif facing == "west":
                    c = (lo[0] - 0.02, b, a)
                else:
                    c = (a, hi[1] + 0.02, b)
                tilt = float(rng.uniform(-15, 15))
                rot = (tilt, 0, 0) if facing != "up" else (0, tilt, 0)
                s.quad(c, (1.05, 1.05), facing, texs[rng.randint(len(texs))], face=True, rot=rot, origin=c)
    # 꼬리 쪽 얼굴(뒤를 봄)
    s.quad((0, 0.7, lo[2] - 0.02), (2.0, 2.0), "north", k.face_tex("half"), face=True)
    # 다리 넷: 굵은 살 기둥(무릎에서 조금 꺾임)
    for x, y, z in LEGS:
        knee = (x * 1.25, -2.6, z + (0.5 if z > 0 else -0.5))
        foot = (x * 1.2, -HOVER + 0.15, z + (0.2 if z > 0 else -0.2))
        stick(s, (x, y, z), knee, 0.8)
        stick(s, knee, foot, 0.7)
        s.box((foot[0] - 0.55, -HOVER, foot[2] - 0.6), (foot[0] + 0.55, -HOVER + 0.4, foot[2] + 0.6), "flesh", face=True)
    # 목: 몸통 앞 위에서 머리까지 굵은 살(두 토막)
    hc = np.array(head_at)
    root = np.array(NECK_ROOT)
    mid = root + (hc - root) * 0.5 + np.array((0, 0.4, -0.2))
    stick(s, root, mid, 1.0)
    stick(s, mid, hc - _u(hc - mid) * 0.9, 0.85)
    s.head(size=HEAD, depth=1.8, at=tuple(hc), face="smile", near=True)
    rods(s, 0)
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, shrink=S, mut2=False)
    # 안 보임: 막대기 여섯만(걸음에 맞춰 위아래로 출렁)
    un = m.state("")
    for f in un.frames():
        with f:
            dy = 0.35 * math.sin(2 * math.pi * f.t)
            for i, (root, d) in enumerate(RODS):
                r = np.array(root) + np.array((0, dy * (1 if i % 2 else -1), 0))
                stick(un, r, r + _u(d) * 2.3, 0.14, "rod")
    un.front = (0, 0, 0)
    body(m.state("seen"), k, HIGH)
    body(m.state("low"), k, LOW)
    k.icon(model="g149", state="seen")


def sounds(k: kit.Kit):
    k.sample("pulse", "tag:heartbeat", "빨라지는 두근거림", pitch=0.8, attenuation=48)
    k.sample("step", "tag:giant", "보이지 않는 발걸음", pitch=0.6, attenuation=64, level=-11)
    k.sample("bite", ["kenney_impact/impactpunch_heavy_000", "tag:growl"], "거인이 물어 올림", pitch=0.6, attenuation=32)
    k.sample("roar", "tag:far_roar", "드러난 거인의 울음", pitch=0.6, attenuation=64)


def phrases():
    return {"cause_g149": "보이지 않는 발에 밟혔다", "cause_g149_slam": "보이지 않는 앞발에 찍혔다", "cause_g149_bite": "보이지 않는 것에게 물려 내던져졌다"}
