"""G15 박수 게이브: 손바닥 해바라기. 땅에서 솟은 살 줄기(1.7 블록, 잎 둘) 위에 0.7 얼굴(볼 홍조가 진하게 번진 신난 얼굴)이 꽃 가운데로 피고,
얼굴 둘레에 손바닥 꽃잎 열 장이 손가락을 바깥으로 벌리고 있다. 얼굴 양옆에서 막대기 둘(원형 DNA)이 팔처럼 나와 끝의 큰 손바닥이 얼굴 앞에서
맞부딪힌다. 무리 뒤 10~15블록에 선 키 큰 꽃이라 '뒤의 박수 게이브부터' 찾기 쉽다.

상태: ""(박자: 20틱에 한 번 짝), "fast"(셋 이상 모였을 때, 쓰러진 사람에게 보내는 갈채: 20틱에 두 번).
자세 0(그리고 fast 의 자세 3)이 손바닥이 맞닿는 순간이다: Java 는 월드 시각 % 20 == 0(fast 는 % 10 == 0)에 짝 소리를 낸다.
모든 개체가 위상 0 이라 맵의 박수 게이브가 한 박자로 친다. 칠 때마다 홍조가 크게 번졌다 줄어든다(자세마다 홍조 판 크기).
"""
import math

import numpy as np

from gabe import audio, faces, kit

HEAD = 0.7
DEPTH = 0.5
ARM = 0.72
# 자세마다 벌림(0 맞닿음 .. 1 활짝)과 홍조 번짐 크기(얼굴 폭 비율)
NORMAL = [(0.0, 0.62), (0.45, 0.56), (0.85, 0.5), (1.0, 0.46), (0.8, 0.44), (0.4, 0.46)]
FAST = [(0.0, 0.66), (0.6, 0.56), (0.95, 0.5), (0.0, 0.66), (0.6, 0.56), (0.95, 0.5)]
RED = (220, 52, 76)
RED_D = (176, 30, 56)
PETALS, PETAL_R = 10, 0.52
STEM = 1.7
PALM = (0.3, 0.34)  # 손바닥 너비, 높이(블록)


def blush_tex():
    """번지는 홍조 판(가로로 긴 타원, 가장자리는 점무늬로 흐림, 가운데는 비움: 점 입과 눈은 그대로)"""
    w, h = 48, 16
    out = np.zeros((h, w, 4), np.uint8)
    rng = np.random.RandomState(15)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    for cx in (11.0, 37.0):
        r = np.hypot((xx + 0.5 - cx) / 10.5, (yy + 0.5 - h / 2) / 7.0)
        on = (r < 0.65) | ((r < 1.0) & (rng.rand(h, w) < (1.0 - r) * 2.2))
        out[on, :3] = RED
        out[on & (r < 0.4), :3] = RED_D
        out[on, 3] = 255
    sq = np.zeros((w, w, 4), np.uint8)
    sq[(w - h) // 2:(w - h) // 2 + h] = out
    return sq


def palm_tex(back=False):
    """손바닥 꽃잎(16x16, 손가락 넷이 위, 엄지는 옆): 얼굴 살빛, 손금(앞) 또는 손등 마디(뒤). 나머지 투명"""
    skin = faces.hexrgb(faces.PAL["S"][0])
    line = (214, 150, 146) if not back else (226, 176, 168)
    im = np.zeros((16, 16, 4), np.uint8)
    for y in range(7, 16):
        for x in range(3, 13):
            im[y, x] = (*skin, 255)
    for i, x0 in enumerate((3, 6, 9, 11)):
        top = (3, 1, 1, 3)[i]
        for y in range(top, 8):
            for x in range(x0, min(x0 + 2, 13)):
                im[y, x] = (*skin, 255)
        im[top, x0] = (*line, 255)
    for y in range(9, 13):
        for x in range(13, 15):
            im[y, x] = (*skin, 255)
    if back:
        for x0 in (3, 6, 9, 11):
            im[8, x0] = (*line, 255)
    else:
        for x in range(4, 12):
            im[11 + (x > 7), x] = (*line, 255)
        for y in range(9, 14):
            im[y, 8] = (*line, 255)
    return im


def arm_dirs(side, a):
    closed = np.array((-side * 0.34, -0.12, 0.72))
    opened = np.array((side * 1.0, 0.25, 0.18))
    d = closed * (1 - a) + opened * a
    return tuple(d / np.linalg.norm(d))


def flower(s, k, table, blush, palm, palm_b):
    h = s.head(size=HEAD, depth=DEPTH, face="deep")
    flesh = k.part_tex("flesh")
    ep = 1.0 / 16
    for fr in s.frames():
        with fr:
            a, spread = table[fr.k]
            for side in (1, -1):
                root = h.point(side * 1.06, -0.55, 0.35)
                d = np.array(arm_dirs(side, a))
                s.rod(root, tuple(d), length=ARM, segs=2, thick=0.09, base=False)
                # 끝의 큰 손바닥(안쪽 면이 손바닥, 맞닿는 순간 얼굴 앞에서 서로 마주 봄)
                c = np.array(root) + d * (ARM + 0.02)
                lo, hi = c - (0.03, PALM[1] / 2, PALM[0] / 2), c + (0.03, PALM[1] / 2, PALM[0] / 2)
                inner, outer = ("west", "east") if side > 0 else ("east", "west")
                s.box(tuple(lo), tuple(hi), {inner: palm, outer: palm_b, "up": flesh, "down": flesh, "north": flesh, "south": flesh},
                      uv={inner: [0, 0, 16, 16], outer: [16, 0, 0, 16], "up": [0, 0, 2, 4], "down": [0, 0, 2, 4], "north": [0, 0, 2, 4],
                          "south": [0, 0, 2, 4]}, face=True)
            w = HEAD * spread * 1.6
            s.quad((0, -HEAD * 0.2, DEPTH / 2 + 0.36 * ep), (w, w), "south", blush, face=True)
    # 받침(어깨): 자세 없음
    for side in (1, -1):
        s.rod(h.point(side * 1.0, -0.55, 0.35), (side, 0, 0.2), length=0.05, segs=1, thick=0.1, base=True)
    # 손바닥 꽃잎 열(가만히): 얼굴 둘레 바깥으로 손가락을 벌림(앞은 손바닥, 뒤는 손등)
    for i in range(PETALS):
        a = 2 * math.pi * i / PETALS + math.pi / PETALS
        c = (PETAL_R * math.cos(a), PETAL_R * math.sin(a), -0.02)
        rot = (0, 0, math.degrees(a) - 90)
        s.quad(c, (0.24, 0.34), "south", palm, rot=rot, origin=c, face=True)
        s.quad(c, (0.24, 0.34), "north", palm_b, rot=rot, origin=c, face=True)
    # 줄기(땅까지, 죽으면 흐물)와 잎 둘
    s.rod((0, -HEAD / 2 + 0.02, -0.08), (0, -1.0, -0.04), length=STEM, segs=2, thick=0.13, tex="flesh", rag=True, toward=(0, 0, 1))
    for side, y in ((1, -0.9), (-1, -1.25)):
        s.band((0, y, -0.08), (side * 1.0, 0.35, 0), length=0.38, width=0.2, segs=1, thick=0.02, tex="flesh", toward=(0, 1, 0))


def build(k: kit.Kit):
    m = k.model(poses=6, period=20)
    blush = k.texture("blush", blush_tex())
    palm = k.texture("palm", palm_tex())
    palm_b = k.texture("palm_back", palm_tex(back=True))
    flower(m.state(""), k, NORMAL, blush, palm, palm_b)
    flower(m.state("fast"), k, FAST, blush, palm, palm_b)
    k.icon(model="g15", state="")


def clap(seed=0):
    """짝: 마른 손뼉(짧은 넓은 대역 터짐 + 작은 울림). 멀리서도 들리게 또렷이(이 종 전용)"""
    n = audio.noise(0.12, seed)
    crack = audio.env(audio.bandpass(n, 900, 9000), 0.0005, 0.012, 0.15, 0.05)
    body_ = audio.env(audio.bandpass(audio.noise(0.12, seed + 1), 400, 1600), 0.0005, 0.02, 0.1, 0.06) * 0.6
    room = audio.at(audio.gain(audio.lowpass(crack, 2500), 0.25), 0.045)
    return audio.normalize(audio.mix(crack, body_, room), 0.9)


def applause(seed=0):
    """갈채: 짝 여럿이 거의 같은 박자로(쓰러진 사람 쪽)"""
    rng = np.random.RandomState(seed)
    parts = [audio.at(audio.gain(clap(seed * 10 + i), 0.5 + 0.5 * rng.rand()), i * 0.25 + rng.rand() * 0.03) for i in range(6)]
    return audio.normalize(audio.mix(*parts), 0.85)


def sounds(k: kit.Kit):
    k.sound("clap", "짝", synth=lambda i: clap(220 + i), attenuation=48)
    k.sound("applause", "쓰러진 사람에게 박수", synth=lambda i: applause(230 + i), attenuation=64)


def phrases():
    return {"cause_g15": "박수 소리를 놔뒀다", "notice_g15_applause": "쓰러진 사람에게 박수가 쏟아진다"}
