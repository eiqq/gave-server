"""G111 제 머리 든 게이브: 엔드 막대기를 뼈대로 살을 입힌 키 2.4 블록 사람꼴 몸. 어깨가 넓고 목 자리에는 막대기 끝이 그루터기처럼
삐죽 솟았다. 한 손(-X, 오른손)으로 제 머리를 등불 들듯 들고(머리는 부품), 다른 손(+X, 왼손)에는 엔드 막대기 곤봉.

- gave:gabe/g111(몸): 상태 ""(carry: 머리를 앞으로 내밀어 들고 걸음 2자세), "tuck"(머리를 겨드랑이에 끼고 달림, 두 배 빠른 걸음),
  "smash"(곤봉을 머리 위로 치켜듦, 자세 없음), "grope"(머리 없이 두 팔을 앞으로 내밀고 더듬더듬, 느린 걸음), "set"(두 손을 목에 올림, 자세 없음).
- gave:gabe/g111_head(부품): 0.9 블록 머리(목 그루터기 구멍이 아래). 손, 겨드랑이, 목, 땅 자리는 Java 가 Part.local 로.
"""
import math

from gabe import kit

SH_Y = 0.72      # 어깨 높이
SH_X = 0.62


def legs(s, cycles=1, walk=True):
    for x, ph in ((0.24, 0), (-0.24, 180)):
        s.rod((x, -0.22, 0), (x * 0.15, -1.0, 0), length=1.0, segs=1, thick=0.16, base=False, poses=2,
              wave=kit.Wave(amp=22, phase=ph, cycles=cycles) if walk else None, toward=(0, 0, 1), rag=False)


def trunk(s):
    # 넓은 어깨 살 몸통(얼굴보다 크게), 갈비 막대기 둘, 목 그루터기
    s.box((-0.56, -0.24, -0.26), (0.56, 0.82, 0.26), "flesh", uv=[0, 0, 16, 16], face=True)
    s.box((-0.66, 0.56, -0.24), (0.66, 0.84, 0.24), "flesh", uv=[0, 0, 16, 8], face=True)
    s.rod((0, 0.84, 0), (0.05, 1.0, -0.05), length=0.26, segs=1, thick=0.13, base=True, toward=(0, 0, 1))
    for x in (0.3, -0.3):
        s.rod((x, 0.5, 0.27), (0, -1.0, 0.05), length=0.6, segs=1, thick=0.06, base=False, toward=(0, 0, 1))


def arm(s, side, d, length=0.95):
    """side 1 = +X(왼손), -1 = -X(오른손). d: 팔 방향"""
    s.rod((side * SH_X, SH_Y, 0), d, length=length, segs=1, thick=0.12, base=True, toward=(0, 0, 1) if abs(d[1]) > 0.8 else (0, 1, 0))


def club(s, hand, d):
    s.rod(hand, d, length=1.0, segs=1, thick=0.2, base=False, toward=(0, 0, 1) if abs(d[1]) > 0.8 else (0, 1, 0))


def norm(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v)


def tip(root, d, ln):
    d = norm(d)
    return tuple(root[i] + d[i] * ln for i in range(3))


def body(s, mode):
    walk = mode in ("", "tuck", "grope")
    legs(s, cycles=2 if mode == "tuck" else 1, walk=walk)
    trunk(s)
    if mode == "":
        # 오른손: 머리를 앞으로 내밀어 듦. 왼손: 곤봉을 늘어뜨림
        d = (-0.25, -0.15, 1.0)
        arm(s, -1, d)
        d2 = (0.25, -1.0, 0.25)
        arm(s, 1, d2)
        club(s, tip((SH_X, SH_Y, 0), d2, 0.95), (0.1, -0.6, 1.0))
    elif mode == "tuck":
        arm(s, -1, (-0.3, -1.0, -0.2), 0.7)
        d2 = (0.3, -0.4, 1.0)
        arm(s, 1, d2)
        club(s, tip((SH_X, SH_Y, 0), d2, 0.95), (0.0, 0.5, 1.0))
    elif mode == "smash":
        arm(s, -1, (-0.3, -1.0, -0.2), 0.7)
        d2 = (0.15, 1.0, -0.15)
        arm(s, 1, d2)
        club(s, tip((SH_X, SH_Y, 0), d2, 0.95), (0.0, 0.6, -1.0))
    elif mode == "grope":
        arm(s, -1, (-0.1, -0.2, 1.0), 1.0)
        d2 = (0.1, -0.2, 1.0)
        arm(s, 1, d2, 1.0)
        club(s, tip((SH_X, SH_Y, 0), d2, 1.0), (0.2, -1.0, 0.2))
    elif mode == "set":
        arm(s, -1, (0.45, 0.8, 0.35), 0.75)
        d2 = (-0.45, 0.8, 0.35)
        arm(s, 1, d2, 0.75)
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=2, period=18, shrink=0.75, mut2=False)
    for mode in ("", "tuck", "grope"):
        body(m.state(mode), mode)
    for mode in ("smash", "set"):
        body(m.state(mode, poses=1), mode)

    hm = k.model("g111_head", poses=1, period=20, mut2=False, rag=False)
    hs = hm.state("")
    hs.head(size=0.9, depth=0.75, at=(0, 0, 0), face="smile")
    # 아래 목 구멍(날살)
    hs.box((-0.18, -0.47, -0.18), (0.18, -0.45, 0.18), "flesh", uv=[0, 0, 8, 8], faces=("down",), face=True)
    k.icon(model="g111", state="")


def sounds(k: kit.Kit):
    k.sample("neck", "tag:bone", "목뼈 뚜둑", pitch=0.9, attenuation=24)
    k.sample("smash", "tag:impact+heavy", "곤봉 내려침", pitch=0.85, attenuation=20)
    k.sample("roll", "tag:wood+impact", "머리 구름", pitch=1.3, volume=0.8, attenuation=16)
    k.sample("laugh", "tag:laugh", "굴러가며 웃음", pitch=1.1, volume=0.8, attenuation=20)


def phrases():
    return {"cause_g111": "제 머리 든 게이브의 곤봉", "cause_g111_head": "굴러온 머리에 물렸다"}
