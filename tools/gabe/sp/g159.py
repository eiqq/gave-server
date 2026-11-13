"""G159 민들레 홀씨 게이브.

홀씨(모델 gave:gabe/g159): 손바닥만 한 _Gave 얼굴(0.3)이 가는 줄기 끝에 매달렸고, 줄기 꼭대기에 짧은 엔드 막대기 여섯이 솜털처럼 둥글게 퍼졌다
(솜털이 얼굴보다 커서 멀리서 민들레 홀씨로 읽힘). 상태 ""(솜털 일렁임 4자세). 몸 가운데 = 줄기 가운데. 사체 없음(터지거나 시듦).

꽃(모델 gave:gabe/g159_flower): 엔드 막대기 줄기(1.15) 위에 0.6 얼굴, 둘레에 작은 얼굴 꽃잎 여덟, 줄기 밑에 연두 잎 둘.
상태 "sprout"(땅에 연두 싹), "bud"(꽃잎 얼굴들이 가운데 얼굴을 감싸 오므린 봉오리), ""(핌: 꽃잎 까딱 4자세), "puff"(불기 1초 전 꽃잎이 오므라듦).
몸 가운데 = 줄기 가운데 높이, 바닥 = -0.8(Java hover 0.8).
"""
import math

import numpy as np

from gabe import faces, kit

GROUND = -0.8
TOP = 0.32          # 꽃 얼굴 가운데 높이
PETAL_R = 0.47
PETAL = faces.Face(eyes="squint", blush="pink")


def green_tex(n=8, seed=159):
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    base = np.array([150, 214, 92])
    out[..., :3] = np.clip(base[None, None, :] + rng.randint(-14, 15, (n, n, 1)), 0, 255)
    out[n // 2, :, :3] = (110, 170, 64)  # 잎맥
    out[..., 3] = 255
    return out


def spore(k):
    m = k.model(poses=4, period=24, mut2=False, torn=False, rag=False)
    s = m.state("")
    s.head(size=0.3, depth=0.24, at=(0, -0.36, 0), face="smile")
    s.rod((0, -0.22, 0), (0, 1, 0), length=0.36, segs=1, thick=0.045, base=False, toward=(0, 0, 1))
    top = (0, 0.14, 0)
    dirs = [(0, 1, 0)] + [(math.cos(2 * math.pi * i / 5) * 0.9, 0.55, math.sin(2 * math.pi * i / 5) * 0.9) for i in range(5)]
    for i, d in enumerate(dirs):
        w = kit.Wave(amp=7, lag=0, phase=i * 72, shape="sin")
        s.rod(top, d, length=0.44, segs=1, thick=0.045, base=False, wave=w, toward=(1, 0, 0) if i == 0 else (0, 1, 0), poses=4)


def petals(s, spread, tilt):
    """꽃잎 얼굴 여덟: spread 0(오므림)..1(활짝), tilt 까딱(도)"""
    for i in range(8):
        a = 2 * math.pi * i / 8
        r = PETAL_R * (0.35 + 0.65 * spread)
        x, y = math.cos(a) * r, TOP + math.sin(a) * r
        z = 0.12 - 0.2 * (1 - spread)
        s.head(size=0.22, depth=0.16, at=(x, y, z), face=PETAL, near=False, torn=False,
               rot=(tilt * math.sin(a), -tilt * math.cos(a), 0) if tilt else None)


def flower(k):
    m = k.model("g159_flower", poses=4, period=24, mut2=False, torn=False)
    lt = k.texture("leaf", green_tex())
    # 싹: 땅에 연두 새순 둘
    sp = m.state("sprout", poses=1)
    sp.box((-0.06, GROUND, -0.06), (0.06, GROUND + 0.22, 0.06), lt, uv=[0, 0, 4, 8])
    sp.quad((0.12, GROUND + 0.14, 0), (0.22, 0.12), "up", lt, rot=(0, 0, 25), origin=(0.02, GROUND + 0.14, 0))
    sp.quad((-0.12, GROUND + 0.14, 0), (0.22, 0.12), "up", lt, rot=(0, 0, -25), origin=(-0.02, GROUND + 0.14, 0))
    sp.front = (0, 0, 0)
    for name in ("bud", "", "puff"):
        s = m.state(name, poses=1 if name != "" else None)
        # 줄기(엔드 막대기)와 밑의 잎 둘
        s.rod((0, GROUND, 0), (0, 1, 0), length=TOP - GROUND - (0.28 if name else 0.2), segs=1, thick=0.09, base=True, rag=False, toward=(0, 0, 1))
        for side in (1, -1):
            s.quad((side * 0.2, GROUND + 0.12, 0.05), (0.36, 0.14), "up", lt, rot=(0, 20 * side, side * 18),
                   origin=(side * 0.03, GROUND + 0.12, 0.05))
        if name == "bud":
            s.head(size=0.42, depth=0.36, at=(0, TOP - 0.1, 0), face="smile")
            for i in range(8):
                a = 2 * math.pi * i / 8
                s.head(size=0.2, depth=0.14, at=(math.cos(a) * 0.2, TOP - 0.1 + math.sin(a) * 0.2, 0.2), face=PETAL, near=False,
                       torn=False)
        elif name == "puff":
            s.head(size=0.6, depth=0.44, at=(0, TOP, 0), face=faces.Face(eyes="squint", mouth="dot", blush="deep"))
            petals(s, 0.25, 0)
        else:
            s.head(size=0.6, depth=0.44, at=(0, TOP, 0), face="smile")
            for f in s.frames():
                with f:
                    petals(s, 1.0, 14 * math.sin(2 * math.pi * f.t))
    k.icon(model="g159_flower", state="")


def build(k: kit.Kit):
    spore(k)
    flower(k)


def sounds(k: kit.Kit):
    # 이 종의 신호: 홀씨가 터지는 '퐁'
    k.sample("pop", "tag:plop", "홀씨 터지는 소리", pitch=1.2, attenuation=16)
    # 꽃이 홀씨를 불어 날림
    k.sample("puff", [{"id": "oga_wind/wind3", "end": 1.0}], "꽃이 홀씨를 붊", pitch=1.5, attenuation=24, n=1)
    # 꽃잎 얼굴이 묾
    k.sample("bite", "oga_creature80/eat_0*", "꽃잎이 묾", pitch=1.5, volume=0.7, attenuation=12)
    # 싹이 밟혀 뭉개짐
    k.sample("squash", "tag:wet+slime", "싹이 뭉개짐", pitch=1.4, volume=0.7, attenuation=12)


def phrases():
    return {"cause_g159": "홀씨가 터졌다", "cause_g159_bite": "꽃밭에 물렸다"}
