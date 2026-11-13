"""G51 보고 배우는 게이브: 삼각대 위 얼굴 사진기. 0.8 블록 얼굴이 사진기 머리이고, 뒤에 작은 짙은 몸통, 아래로 기둥과 다리 셋이 땅까지 내려가
25~32블록 밖에 선다(머리 높이 2블록). 정수리에서 짧은 엔드 막대기 둘(원형 DNA)이 안테나처럼 솟는다(아주 조금만 까딱).
표적(가장 많이 쏘는 사람)을 찍으면 실눈이 크게 뜨이고(키트 open 오버레이) 눈 안에서 세로 동공이 위아래로 열렸다 닫히며 반짝임이 돈다.
배운 횟수만큼 이마에 세로로 찢어진 눈이 하나씩 열린다(부위 eye1..eye4).

상태: ""(지켜봄), "snap"(찰칵: 안테나가 빠르게 떨림). 부위: eye1, eye2, eye3, eye4.
"""
import math

import numpy as np

from gabe import audio, faces, kit

SIZE = 0.8
PX = SIZE / 32  # 얼굴 텍스처 한 화소(블록)
LEG = 1.25


def spark_tex():
    c = faces.Canvas(2)
    for y in range(2):
        for x in range(2):
            c.put(x, y, "W")
    return c.rgba()


def pupil_tex():
    """세로 동공: 가운데 짙고 둘레는 홍채"""
    c = faces.Canvas(4)
    for y in range(4):
        for x in range(4):
            c.put(x, y, "p" if 1 <= x <= 2 else "i")
    return c.rgba()


def brow_eye_tex():
    """이마에 찢어진 눈(세로): 뜬 눈 조각을 90도 돌린 것 + 찢어진 테(날살)"""
    rows = faces.EYES["open"]
    c = faces.Canvas(10)
    c.stamp(rows, 1, 1)
    a = c.rgba()
    # 테두리: 눈 화소 바깥 한 칸을 날살 빛으로
    alpha = a[..., 3] > 0
    grown = faces._dilate(alpha, 1) & ~alpha
    a[grown] = (150, 48, 60, 255)
    a[grown, 2] &= 0xF8
    return np.rot90(a).copy()


def camera_tex(n=8):
    """사진기 몸통: 짙은 적갈색(머리 덩어리 빛) 가죽, 위 가장자리 밝은 줄, 가운데 가로 홈"""
    im = np.zeros((n, n, 4), np.uint8)
    hair = faces.hexrgb(faces.PAL["H"][0])
    im[...] = (*hair, 255)
    im[0, :, :3] = (122, 58, 56)
    im[n // 2, :, :3] = (44, 14, 14)
    for x in range(0, n, 3):
        im[n - 2, x, :3] = (96, 40, 40)
    return im


def body(s, k, ant, tex):
    h = s.head(size=SIZE, depth=0.56, face="smile")
    # 안테나 둘(원형 DNA): 정수리 양옆에서 곧게 위로, 짧게
    for side, ph in ((1, 0), (-1, 180)):
        w = kit.Wave(amp=ant.amp, lag=ant.lag, bias=ant.bias, phase=ant.phase + ph, shape=ant.shape, cycles=ant.cycles)
        s.rod(h.point(side * 0.5, 1.0, -0.25), (side * 0.05, 1.0, -0.03), length=0.6, segs=2, thick=0.085, wave=w, toward=(side, 0, 0), poses=4)
    z = 0.56 / 2
    ep = 1.0 / 16
    # 눈 뜬 동안: 세로 동공이 열렸다 닫히고 반짝임이 눈 안을 돈다(자세마다 판 하나씩)
    for ex in (faces.EYE_X[0] + 4, faces.EYE_X[1] + 4):
        cx = (ex / 32 - 0.5) * SIZE
        cy = (0.5 - (faces.EYE_Y + 4.2) / 32) * SIZE
        for f in s.frames():
            with f, s.when("open"):
                open_h = 2.2 + 3.0 * abs(math.sin(math.pi * f.t))
                s.quad((cx + 0.2 * PX, cy, z + 0.30 * ep), (2.2 * PX, open_h * PX), "south", tex["pupil"], face=True)
                a = 2 * math.pi * f.t
                s.quad((cx + math.cos(a) * 1.5 * PX, cy + math.sin(a) * 1.3 * PX, z + 0.36 * ep), (1.3 * PX, 1.3 * PX), "south",
                       tex["spark"], face=True)
    # 이마 눈 넷(배운 횟수만큼)
    for i, u in enumerate((-0.2, 0.2, -0.5, 0.5)):
        with s.when("on:eye%d" % (i + 1)):
            s.quad((u * SIZE / 2, 0.31 * SIZE, z + 0.32 * ep), (5 * PX, 7 * PX), "south", tex["brow"], face=True)
    # 사진기 몸통(뒤통수 뒤), 기둥, 삼각대 다리 셋(가만히, 죽으면 흐물: 바로 선 채 굳지 않게)
    s.box((-0.3, -0.22, -0.62), (0.3, 0.2, -0.27), tex["cam"], uv=[0, 0, 16, 16], faces=("north", "east", "west", "up", "down"), face=True)
    s.rod((0, -0.4, -0.12), (0, -1, 0), length=0.55, segs=1, thick=0.1, base=False, rag=True, toward=(0, 0, 1))
    for i in range(3):
        a = 2 * math.pi * i / 3 + math.pi / 2
        s.rod((0, -0.92, -0.12), (math.cos(a) * 0.55, -1.0, math.sin(a) * 0.55), length=LEG, segs=2, thick=0.08, base=False, rag=True)
    return h


def build(k: kit.Kit):
    tex = {"spark": k.texture("spark", spark_tex(), classes=True), "pupil": k.texture("pupil", pupil_tex(), classes=True),
           "brow": k.texture("brow", brow_eye_tex(), classes=True), "cam": k.texture("cam", camera_tex())}
    m = k.model(poses=8, period=40)
    body(m.state(""), k, kit.Wave(amp=(2.0, 3.0), lag=35, bias=(0, 0)), tex)
    # 찰칵: 같은 주기에 네 번 떨림(셔터 순간)
    body(m.state("snap"), k, kit.Wave(amp=(7, 11), lag=30, cycles=4), tex)


def shutter(seed=0):
    """찰칵: 마른 딸깍 둘(셔터가 열렸다 닫힘) + 짧은 기계 울림"""
    rng = np.random.RandomState(510 + seed)
    d = 0.34
    a = audio.env(audio.highpass(audio.noise(0.03, 511 + seed), 1800), 0.0005, 0.006, 0.25, 0.015)
    b = audio.env(audio.highpass(audio.noise(0.04, 513 + seed), 1200), 0.0005, 0.008, 0.3, 0.02)
    ring = audio.env(audio.tone(2100 + 60 * rng.randn(), 0.09, "tri", glide=1500), 0.001, 0.02, 0.2, 0.05) * 0.25
    low = audio.env(audio.tone(180, 0.05, glide=120), 0.001, 0.01, 0.3, 0.03) * 0.5
    out = audio.mix(audio.at(a, 0.0, d), audio.at(low, 0.0, d), audio.at(b, 0.11 + 0.01 * seed, d), audio.at(ring, 0.12 + 0.01 * seed, d))
    return audio.normalize(audio.fade(out, 0.001, 0.03), 0.85)


def sounds(k: kit.Kit):
    # 찰칵: 이 종의 신호(멀리 있으니 넓게 들림). 지켜보다 적을 때 작게, 배울 때 크게(Java 가 두 번 겹침)
    k.sound("shutter", "찰칵", synth=shutter, volume=1.0, attenuation=64)
    # 물러날 때 안테나가 접혔다 펴지는 떨림
    k.sound("flinch", "안테나가 떨림", vanilla=[("random/break", 1.5), ("random/break", 1.7)], volume=0.5, attenuation=32)


def phrases():
    return {"cause_g51": "배운 대로 당했다", "notice_g51_pellet": "게이브가 산탄을 배웠다", "notice_g51_sniper": "게이브가 저격을 배웠다",
            "notice_g51_explosion": "게이브가 폭발을 배웠다", "notice_g51_fire": "게이브가 불을 배웠다",
            "notice_g51_forgot": "게이브가 배운 것을 잊었다"}
