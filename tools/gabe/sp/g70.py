"""G70 풍선 게이브: 바람 넣어 팽팽한 0.9블록 공 얼굴(상자 셋을 겹쳐 둥근 풍선 꼴, 분홍 살갗), 아래 꼭지 매듭에 짧은 엔드 막대기 둘이 가위표로 꿰였다.

상태: ""(둥실: 꼭지 막대기만 살랑, 높이 흔들림은 Java), "sag"(바람이 빠져 쭈그러짐: 떨어지는 동안), "crawl"(땅에서 쭈그러진 채 꼭지 막대기 둘로 기어 옴).
모델 gave:gabe/g70_shadow: 바닥에 지는 점묘 그림자 점(판 하나, Java 가 효과 디스플레이에 실음).
떼 종이라 변이 부위(입 찢어짐, 막대기 둘 더)는 끈다.
"""
import math

import numpy as np

from gabe import kit


def shadow_tex(n=32):
    """점묘 둥근 그림자 점(컷아웃이라 반투명 대신 디더: 가운데가 짙음)"""
    out = np.zeros((n, n, 4), np.uint8)
    bayer = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0
    for y in range(n):
        for x in range(n):
            r = math.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) / (n / 2)
            if r > 1.0:
                continue
            if 0.95 - 0.75 * r > bayer[y % 4, x % 4]:
                out[y, x] = (110, 36, 58, 255)
    return out


def balloon(s, k, face, sx=1.0, sy=1.0, sz=1.0, y=0.0):
    """둥근 풍선: 얼굴 상자(앞면 얼굴) + 가로, 세로로 불룩한 살 상자 둘(모서리를 깎아 둥글게 보임)"""
    s.head(size=0.72 * sx, height=0.8 * sy, depth=0.9 * sz, at=(0, y, 0), face=face)
    side = ("north", "east", "west", "up", "down")
    s.box((-0.47 * sx, y - 0.28 * sy, -0.37 * sz), (0.47 * sx, y + 0.28 * sy, 0.37 * sz), "flesh", faces=side + ("south",), face=True)
    s.box((-0.28 * sx, y - 0.5 * sy, -0.37 * sz), (0.28 * sx, y + 0.5 * sy, 0.37 * sz), "flesh", faces=side + ("south",), face=True)
    # 꼭지 매듭
    s.box((-0.07, y - 0.5 * sy - 0.12, -0.07), (0.07, y - 0.5 * sy + 0.01, 0.07), "flesh", face=True)
    return y - 0.5 * sy - 0.08


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, mut2=False, torn=False)

    # ---------------------------------------------------------------- 둥실(하늘): 꼭지 막대기 둘이 가위표로 살랑
    s = m.state("")
    knot = balloon(s, k, "smile")
    for side in (1, -1):
        s.rod((side * 0.16, knot + 0.06, 0.0), (-side * 0.55, -1, 0.05), length=0.5, segs=1, thick=0.06, base=False, poses=4,
              wave=kit.Wave(amp=10, lag=0, phase=0 if side > 0 else 180), toward=(0, 0, 1), rag=False)

    # ---------------------------------------------------------------- 쭈그러짐(떨어지는 중): 납작하게 찌그러지고 막대기가 벌어짐
    g = m.state("sag")
    knot = balloon(g, k, "half", sx=1.12, sy=0.62, sz=0.9, y=0.05)
    for side in (1, -1):
        g.rod((side * 0.12, knot + 0.04, 0.0), (side * 0.9, -0.6, 0.1), length=0.48, segs=1, thick=0.06, base=False, poses=4,
              wave=kit.Wave(amp=24, lag=0, phase=0 if side > 0 else 180), toward=(0, 1, 0), rag=False)

    # ---------------------------------------------------------------- 기기(땅): 쭈그러진 채 꼭지 막대기 둘을 다리 삼아 엇갈려 걸음
    c = m.state("crawl")
    knot = balloon(c, k, "half", sx=1.1, sy=0.55, sz=0.95, y=0.1)
    for side in (1, -1):
        c.rod((side * 0.13, knot + 0.05, 0.05), (side * 0.35, -1, 0.45), length=0.52, segs=2, thick=0.07, base=False, poses=4,
              wave=kit.Wave(amp=(28, 18), lag=40, phase=0 if side > 0 else 180), toward=(0, 0, 1))

    sh = k.model("g70_shadow", poses=1, mut2=False, open=False, torn=False, rag=False)
    sh.state("").quad((0, 0, 0), (1.0, 1.0), "up", k.texture("shadow", shadow_tex()))

    k.icon(model="g70", state="", view="front")


def sounds(k: kit.Kit):
    # 높은 데서 고무 비비는 '끼익'(무리에서 하나씩 드물게)
    k.sample("rub", "tag:creak-metal", "풍선 소리", pitch=1.35, volume=0.8, attenuation=40)
    # 떨어지기 직전 바람 새는 소리
    k.sample("hiss", "tag:hiss", "바람 새는 소리", pitch=1.3, volume=0.9, attenuation=16)
    # 터짐
    k.sample("pop", "tag:bang", "풍선 터짐", pitch=1.55, volume=0.9, attenuation=32)


def phrases():
    return {"cause_g70": "머리 위에서 풍선이 터졌다", "cause_g70_poke": "쭈그러진 풍선에 찔렸다"}
