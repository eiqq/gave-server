"""G86 소금쟁이 게이브: 0.4 블록 얼굴이 수면에 닿을 듯 낮게 매달리고, 가늘고 긴 엔드 막대기 다리 넷(무릎에서 꺾임, 다리 폭 1.6 블록)으로
물 위에 선다. 얼굴 뒤로 가는 살 몸통. 다리 끝이 닿은 물에 보조개 같은 잔물결 고리.

상태: ""(물 위: 다리는 가만히 있는 구조 막대기, 잔물결 고리가 두 크기로 번갈아 퍼짐), "land"(마른 땅에서 벌렁 자빠져 다리 넷을 허우적거림),
"cling"(정강이에 매달림: 다리를 앞으로 오므려 감싸 꿈틀).
"""
import numpy as np

from gabe import kit

FACE = 0.4
HIP = 0.17
# 다리: (무릎, 발) 블록 좌표(+X 쪽, -X 는 거울)
LEGS = (((0.42, 0.26, 0.36), (0.74, -0.25, 0.62)), ((0.46, 0.3, -0.3), (0.82, -0.25, -0.66)))


def ripple_tex():
    """잔물결 고리(가운데 빈 고리, 옅은 물빛)"""
    n = 16
    im = np.zeros((n, n, 4), np.uint8)
    for v in range(n):
        for u in range(n):
            r = np.hypot(u - 7.5, v - 7.5)
            if 5.6 <= r <= 7.3:
                im[v, u] = (196, 222, 236, 255) if r > 6.4 else (150, 186, 206, 255)
    return im


def body(s):
    s.head(size=FACE, depth=0.34, face="smile")
    s.box((-0.11, -0.09, -0.78), (0.11, 0.07, -0.17), "flesh", face=True)


def water_legs(s, ripple):
    for side in (1, -1):
        for (kx, ky, kz), (fx, fy, fz) in LEGS:
            root = (side * HIP, 0.04, kz * 0.25)
            knee = np.array((side * kx, ky, kz))
            foot = np.array((side * fx, fy, fz))
            up = knee - np.array(root)
            s.rod(root, tuple(up), length=float(np.linalg.norm(up)), segs=1, thick=0.05, base=False, toward=(0, 0, 1) if abs(up[2]) < 0.9 else (1, 0, 0))
            dn = foot - knee
            s.rod(tuple(knee), tuple(dn), length=float(np.linalg.norm(dn)), segs=1, thick=0.045, base=False, toward=(0, 0, 1))
    for f in s.frames(poses=2):
        with f:
            r = 0.26 if f.k == 0 else 0.42
            for side in (1, -1):
                for _, (fx, fy, fz) in LEGS:
                    s.quad((side * fx, fy + 0.01, fz), (r, r), "up", ripple)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    ripple = k.texture("ripple", ripple_tex())

    s = m.state("")
    body(s)
    water_legs(s, ripple)

    # 마른 땅: 배를 하늘로 벌렁 자빠져 다리 넷을 허우적거림
    l = m.state("land")
    l.head(size=FACE, depth=0.34, face="half", rot=(-70, 0, 0))
    l.box((-0.11, -0.12, -0.7), (0.11, 0.04, -0.12), "flesh", face=True)
    for i, (side, z) in enumerate(((1, 0.12), (-1, 0.12), (1, -0.18), (-1, -0.18))):
        l.rod((side * HIP, 0.02, z), (side * 0.8, 0.9, z * 2), length=0.75, segs=2, thick=0.05, base=False,
              wave=kit.Wave(amp=(34, 24), lag=50, phase=i * 90, cycles=2), toward=(0, 0, 1))

    # 매달림: 다리를 앞으로 오므려 정강이를 감쌈
    c = m.state("cling")
    body(c)
    for i, side in enumerate((1, -1, 1, -1)):
        y = 0.1 if i < 2 else -0.12
        c.rod((side * HIP, y, 0.05), (side * 0.25, 0.0, 1.0), length=0.55, segs=2, thick=0.05, base=False,
              wave=kit.Wave(amp=(16, 22), bias=(-side * 18, -side * 30), lag=40, phase=i * 90), toward=(-side, 0, 0))


def sounds(k: kit.Kit):
    # 물 튀기는 잰 '찰박찰박'(이 종 신호): 짧은 물 튀김을 높게
    k.sample("skate", ["oga_water40/splash_09", "oga_water40/splash_10", "oga_sfx100/splash_02"], "수면 걷는 소리", pitch=1.45,
             volume=0.8, attenuation=20)


def phrases():
    return {"cause_g86": "소금쟁이가 정강이를 물었다"}
