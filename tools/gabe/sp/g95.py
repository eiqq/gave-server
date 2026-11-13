"""G95 두 번째 그림자 게이브: 바닥에 납작 붙은 검은 얼굴 그림자(두께 없음, 1.2 블록). 검은 판에 _Gave 의 실눈과 점 입만 흐린 분홍으로 비치고,
그림자 뒤 가장자리에 검게 칠한 짧은 엔드 막대기 둘이 꼬리처럼 끌린다.

상태: "slide"(기본: 동그란 그림자, 꼬리 둘이 좌우로 끌림 4자세), "stick"(사람 발밑에서 비스듬히 늘어난 그림자: 모델 가운데가 발, +Z 로 뻗음).
사체는 없다(Java 가 죽으면 바로 지움).
"""
import numpy as np
from PIL import Image

from gabe import kit

FLOOR = 0.01


def shadow_tex(n=32, stretch=1.0):
    """검은 그림자 얼굴: 둥근 가장자리(조금 들쭉날쭉), 흐린 분홍 실눈 두 줄과 점 입"""
    rng = np.random.RandomState(95)
    im = np.zeros((n, n, 4), np.uint8)
    for v in range(n):
        for u in range(n):
            dx, dy = (u + 0.5 - n / 2) / (n / 2), (v + 0.5 - n / 2) / (n / 2)
            r = np.hypot(dx, dy) + rng.rand() * 0.06
            if r <= 0.96:
                k = 0.9 + 0.1 * rng.rand()
                im[v, u] = (int(22 * k), int(14 * k), int(20 * k), 255)
    pink = (128, 82, 94, 255)
    # 실눈(^ ^): 위쪽(앞)
    for x0 in (8, 19):
        for i, (dx, dy) in enumerate(((0, 2), (1, 1), (2, 0), (3, 0), (4, 1), (5, 2))):
            im[11 + dy, x0 + dx] = pink
            im[12 + dy, x0 + dx] = pink
    im[20:23, 15:17] = pink
    return im


def black_rod():
    im = np.zeros((16, 16, 4), np.uint8)
    im[:, 0] = (30, 22, 28, 255)
    im[:, 1] = (18, 12, 16, 255)
    im[0:2, 2:4] = (30, 22, 28, 255)
    return im


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, mut2=False, torn=False, open=False, rag=False)
    face = shadow_tex()
    tex = k.texture("shadow", face)
    rod = k.texture("tail", black_rod())

    s = m.state("slide")
    s.quad((0, FLOOR, 0), (1.2, 1.2), "up", tex)
    for side, ph in ((1, 0), (-1, 180)):
        s.rod((side * 0.18, FLOOR + 0.02, -0.5), (side * 0.25, 0, -1), length=0.5, segs=2, thick=0.05, tex=rod, base=False,
              wave=kit.Wave(amp=(20, 26), lag=40, phase=ph), toward=(1, 0, 0), poses=4)
    s.front = (0, 1, 0)

    # 붙음: 발(가운데)에서 +Z 로 비스듬히 늘어난 그림자
    t = m.state("stick")
    t.quad((0, FLOOR, 0.72), (0.9, 1.44), "up", tex)
    for side, ph in ((1, 0), (-1, 180)):
        t.rod((side * 0.4, FLOOR + 0.02, 1.12), (side * 1.0, 0, 0.45), length=0.4, segs=2, thick=0.05, tex=rod, base=False,
              wave=kit.Wave(amp=(14, 18), lag=40, phase=ph), toward=(1, 0, 0), poses=4)
    t.front = (0, 1, 0)
    m.default = "slide"

    icon = Image.fromarray(face, "RGBA").resize((64, 64), Image.NEAREST)
    k.icon(icon)


def sounds(k: kit.Kit):
    # 바닥을 스치는 '스르륵'(이 종 신호)
    k.sample("slither", "tag:slime", "그림자 스르륵", pitch=0.75, volume=0.8, attenuation=12)


def phrases():
    return {"cause_g95": "발밑 그림자가 살을 갉았다"}
