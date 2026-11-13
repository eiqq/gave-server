"""G172 잿가루 게이브: 쌀알만 한 잿빛 얼굴 열두 개가 한 줌으로 모여 재 위를 흐르고, 총알에 맞으면 둘레 줌이 몰려와 사람 꼴로 뭉쳐 일어선다.

모델 gave:gabe/g172(잿빛은 Java 가 팔레트 PAL_PALE 로 탈색):
- ""(한 줌): 0.15 블록 얼굴 열하나가 사르르 흐르는 4자세(8틱 한 바퀴) + 앞에 깨알 막대기 더듬이 둘을 꽂은 길잡이 알갱이 하나.
- "kneel"(무릎 꿇은 사람 꼴, 1.2 블록): 재 덩이 몸에 알갱이 얼굴이 볼을 붙여 박혔고, 등에 막대기 더듬이들이 등뼈처럼 돋았다.
- "stand"(두 팔을 벌린 선 사람 꼴, 2.4 블록).
몸 중심(0, 0, 0) 높이: 한 줌 땅 위 0.1, kneel 0.6, stand 1.2(Java 가 hover 를 바꿈). 얼굴 알갱이의 실눈 뜸은 kneel, stand 의 얼굴 자리만.
"""
import math

import numpy as np

from gabe import kit

G = 0.15


def ash_tex(seed=172):
    """32x32 재 덩이: 잿빛 잡음, 검은 그을음 점과 밝은 재 점"""
    n = 32
    rng = np.random.RandomState(seed)
    col = np.full((n, n, 3), 118, np.float32) + rng.randint(-16, 17, (n, n, 1))
    col += np.kron(rng.randint(-10, 11, (n // 2, n // 2, 1)), np.ones((2, 2, 1)))
    dark = rng.rand(n, n) < 0.08
    col[dark] = (52, 48, 46)
    light = rng.rand(n, n) < 0.05
    col[light] = (196, 190, 184)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    out[..., 3] = 255
    return out


def grain(s, at, size=G, open_=False, rot=None):
    return s.head(size=size, depth=size * 0.8, at=at, face="smile", near=False, open=open_, torn=False, rot=rot)


def handful(k, s):
    # 길잡이 알갱이(앞)와 깨알 더듬이 둘: 자세 없이
    grain(s, (0, -0.02, 0.36))
    for side in (1, -1):
        s.rod((side * 0.04, 0.04, 0.4), (side * 0.5, 0.8, 0.6), length=0.2, segs=1, thick=0.035, base=False, rag=False)
    rng = np.random.RandomState(172)
    spots = []
    for i in range(11):
        a = rng.rand() * math.tau
        r = 0.12 + 0.3 * math.sqrt(rng.rand())
        spots.append((math.cos(a) * r, math.sin(a) * r * 1.2 - 0.06, rng.rand() * math.tau))
    for f in s.frames():
        with f:
            for x, z, ph in spots:
                # 앞으로 흐르는 물결: 자세마다 조금씩 앞뒤, 옆으로
                dz = 0.05 * math.sin(f.t * math.tau + ph)
                dx = 0.03 * math.cos(f.t * math.tau + ph * 1.7)
                dy = 0.025 * max(0.0, math.sin(f.t * math.tau * 2 + ph))
                grain(s, (x + dx, -0.03 + dy, z + dz))


def kneel(k, s):
    ash = k.texture("ash", ash_tex())
    # 재 덩이 몸(무릎 꿇음): 정강이, 넓적다리, 몸통, 팔, 머리
    s.box((-0.26, -0.6, -0.5), (0.26, -0.44, -0.12), ash, face=True)
    s.box((-0.27, -0.58, -0.16), (0.27, -0.32, 0.38), ash, face=True)
    s.box((-0.25, -0.34, -0.2), (0.25, 0.2, 0.12), ash, face=True)
    for side in (1, -1):
        s.box((side * 0.25, -0.3, -0.12), (side * 0.38, 0.14, 0.06), ash, face=True)
    s.box((-0.17, 0.18, -0.16), (0.17, 0.52, 0.12), ash, face=True)
    # 얼굴 자리: 알갱이 여섯(실눈 뜸이 차례로 보이게 모두 open 오버레이)
    for i, (x, y) in enumerate(((-0.08, 0.44), (0.08, 0.44), (-0.08, 0.3), (0.08, 0.3), (0.0, 0.37), (0.0, 0.23))):
        grain(s, (x, y, 0.16), size=0.13, open_=True)
    # 가슴과 무릎에 볼을 붙인 알갱이
    for x, y, z in ((-0.12, 0.05, 0.15), (0.12, 0.05, 0.15), (0.0, -0.12, 0.15), (-0.12, -0.28, 0.15), (0.12, -0.28, 0.15),
                    (-0.14, -0.38, 0.41), (0.14, -0.38, 0.41)):
        grain(s, (x, y, z), size=0.14)
    # 등뼈처럼 돋은 더듬이 막대기
    for j, y in enumerate((-0.25, -0.05, 0.15)):
        s.rod((0, y, -0.2), (0.0, 0.6, -1.0), length=0.32 - j * 0.04, segs=1, thick=0.05, base=False, rag=False)


def stand(k, s):
    ash = k.texture("ash", ash_tex())
    # 다리 둘, 몸통, 두 팔을 옆으로 벌림, 머리
    for side in (1, -1):
        s.box((side * 0.05, -1.2, -0.13), (side * 0.27, -0.12, 0.13), ash, face=True)
    s.box((-0.32, -0.15, -0.18), (0.32, 0.72, 0.16), ash, face=True)
    for side in (1, -1):
        lo, hi = sorted((side * 0.3, side * 1.3))
        s.box((lo, 0.5, -0.1), (hi, 0.72, 0.1), ash, face=True)
    s.box((-0.22, 0.72, -0.18), (0.22, 1.18, 0.16), ash, face=True)
    # 얼굴 자리 알갱이 아홉(차례로 눈 뜸)
    for x in (-0.12, 0.0, 0.12):
        for y in (1.06, 0.94, 0.82):
            grain(s, (x, y, 0.2), size=0.13, open_=True)
    # 몸통, 팔, 다리에 박힌 알갱이
    for x in (-0.18, 0.0, 0.18):
        for y in (0.55, 0.35, 0.15, -0.05):
            grain(s, (x, y, 0.19), size=0.15)
    for side in (1, -1):
        for x in (0.55, 0.85, 1.15):
            grain(s, (side * x, 0.61, 0.13), size=0.14)
        for y in (-0.4, -0.8):
            grain(s, (side * 0.16, y, 0.16), size=0.14)
    # 등뼈 더듬이
    for j, y in enumerate((-0.05, 0.2, 0.45, 0.7)):
        s.rod((0, y, -0.18), (0.0, 0.5, -1.0), length=0.42 - j * 0.04, segs=1, thick=0.06, base=False, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    handful(k, m.state(""))
    kneel(k, m.state("kneel"))
    stand(k, m.state("stand"))
    k.icon(model="g172", state="stand")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 재 쏟아지는 사르르(뭉칠 때 크게)
    k.sample("sift", "tag:paper", "사르르 재 소리", pitch=0.7, attenuation=20)
    # 뭉쳐 일어섬
    k.sample("merge", ["oga_sfx100/paper_02", "oga_sfx100/paper_04"], "재가 뭉침", pitch=0.5, volume=1.0, attenuation=28, n=2)
    # 들이받기, 끌어안기
    k.sample("ram", "kenney_impact/impactsoft_heavy_00*", "재 사람이 들이받음", pitch=0.9, attenuation=16)


def phrases():
    return {"cause_g172": "재 사람에게 깔렸다"}
