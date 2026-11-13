"""G114 개미 무덤 게이브: 높이 3.6, 밑지름 3.6 블록의 울퉁불퉁한 흙빛 둔덕(밑이 넓고 위가 좁은 무덤 꼴). _Gave 얼굴 열여섯 장이 흙에 반쯤
묻혀 굳었고 얼굴마다 점 입이 구멍으로 뚫렸다. 꼭대기에 큰 구멍 하나, 옆구리에 엔드 막대기 둘이 버팀목처럼 비스듬히 박혔다.

모델 gave:gabe/g114(shrink 0.68: 둔덕이 1.5 블록 틀을 넘음) 상태:
  ""     둔덕(구멍 둘레 흙이 들썩이는 2자세)
  "plug" 꼭대기 구멍이 막혀 주저앉음(윗단 둘이 무너져 낮은 둔덕, 구멍 자리에 그을린 흙)
솟음은 Java 가 hover 를 땅 밑에서 올림. 가운데(몸 자리) = 둔덕 높이 한가운데(Java hover 1.8: 밑이 땅에 닿음).
모델 gave:gabe/g114_ant: 개미 줄(얼굴 개미 넷이 한 줄, 개미마다 막대기 조각 다리 여섯, 다리 2자세). 줄 하나가 개체 하나(G114Ant).
소리 scratch(무덤 속 긁는 소리, 24), spew(줄기 뿜음, 20), bite(개미 물기, 12).
"""
import math

import numpy as np

from gabe import faces, kit

H = 1.8  # 반 높이
HOLE_FACE = faces.Face(eyes="squint", mouth="teeth_ring", blush="pink")


def _rgba(arr):
    return np.clip(np.round(arr), 0, 255).astype(np.uint8)


def dirt_tex(n=32, seed=114, dark=0.0):
    """흙빛: 굵은 얼룩 + 잔 알갱이 + 군데군데 분홍 살이 비치는 점(얼굴을 덮은 흙)"""
    rng = np.random.RandomState(seed)
    coarse = rng.rand(n // 4 + 1, n // 4 + 1)
    coarse = np.kron(coarse, np.ones((4, 4)))[:n, :n]
    fine = rng.rand(n, n)
    base = np.array([112, 82, 62], np.float32) * (1.0 - dark)
    col = base[None, None, :] * (0.78 + 0.3 * coarse[..., None] + 0.12 * fine[..., None])
    col[fine > 0.95] = np.array([150, 116, 92]) * (1.0 - dark)
    col[(fine < 0.03)] = np.array([62, 44, 34]) * (1.0 - dark)
    if dark == 0.0:
        col[(fine > 0.4) & (fine < 0.415)] = (214, 160, 150)
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = col
    out[..., 3] = 255
    return _rgba(out)


def hole_tex(n=16):
    """꼭대기 구멍: 가운데로 갈수록 짙은 어둠"""
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    c = (n - 1) / 2
    r = np.hypot(xx - c, yy - c) / c
    col = np.array([20, 10, 12], np.float32)[None, None, :] + (np.clip(r, 0, 1) ** 2)[..., None] * np.array([70, 44, 34], np.float32)
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = col
    out[..., 3] = 255
    return _rgba(out)


# 단(아래에서 위로): 아래 y, 위 y, 반 폭
TIERS = ((-H, -0.95, 1.75), (-0.95, -0.1, 1.4), (-0.1, 0.75, 1.02), (0.75, 1.45, 0.66), (1.45, 1.66, 0.48))


def mound(s, k, tiers, rim=True, scorched=False, lift=0.0):
    dirt = k.texture("dirt", dirt_tex())
    dirt2 = k.texture("dirt_dark", dirt_tex(seed=7, dark=0.25))
    for i, (y0, y1, w) in enumerate(tiers):
        tex = dirt if i % 2 == 0 else dirt2
        s.box((-w, y0 + lift, -w), (w, y1 + lift, w), tex, uv=[0, 0, 16, 16])
        # 45도 돌린 상자(팔각 꼴로 둥글게): 모서리가 덜 각져 보이게
        w2 = w * 0.92
        s.box((-w2, y0 + lift + 0.02, -w2), (w2, y1 + lift - 0.02, w2), dirt2 if i % 2 == 0 else dirt, uv=[0, 0, 16, 16], rot=(0, 45, 0),
              origin=(0, 0, 0))
    top = tiers[-1][1] + lift
    if rim:
        hole = k.texture("hole", hole_tex())
        s.quad((0, top + 0.004, 0), (0.62, 0.62), "up", hole)
    if scorched:
        soot = k.texture("soot", dirt_tex(seed=31, dark=0.6))
        s.box((-0.55, top - 0.05, -0.55), (0.55, top + 0.08, 0.55), soot, uv=[0, 0, 16, 16])


def buried_faces(s, tiers, picks, lift=0.0):
    """흙에 반쯤 묻힌 얼굴: 단 옆면(곧은 상자는 0, 90 .. 도, 45도 돌린 상자는 45, 135 .. 도)에 바깥을 보고 위로 젖혀(구멍 입을 '오' 하고 벌림).
    picks: (단 번호, 각도 차례 0..7, 크기, 젖힘)"""
    for (ti, j, size, tilt) in picks:
        y0, y1, w = tiers[ti]
        a = j * 45.0
        plane = w if j % 2 == 0 else w * 0.92
        r = plane + size * 0.06
        y = min(y1 - size * 0.32, (y0 + y1) / 2 + size * 0.12) + lift
        ar = math.radians(a)
        s.head(size=size, depth=size * 0.42, at=(math.sin(ar) * r, y, math.cos(ar) * r), face=HOLE_FACE, torn=False, rot=(-tilt, a, 0))


FACES_FULL = [(0, j, 0.64, 16) for j in range(8)] + [(1, j, 0.56, 22) for j in (1, 3, 5, 7)] + [(1, j, 0.5, 22) for j in (0, 4)] +     [(2, j, 0.48, 28) for j in (0, 2, 4, 6)] + [(3, j, 0.4, 34) for j in (1, 5)]


def braces(s):
    # 옆구리에 비스듬히 박힌 엔드 막대기 둘(버팀목, 자세 없음)
    for side in (1, -1):
        s.rod((side * 1.25, -0.55, -0.35), (side * 0.75, 0.6, -0.35), length=1.5, segs=2, thick=0.13, base=False, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=2, period=20, shrink=0.68, mut2=False, torn=False)
    s = m.state("")
    mound(s, k, TIERS)
    buried_faces(s, TIERS, FACES_FULL)
    braces(s)
    # 구멍 둘레 흙이 들썩임(2자세)
    dirt = k.texture("dirt", dirt_tex())
    for f in s.frames(2):
        with f:
            h = 0.06 if f.k == 0 else 0.16
            top = TIERS[-1][1]
            for i in range(4):
                a = math.radians(i * 90 + 45)
                c = (math.sin(a) * 0.4, top, math.cos(a) * 0.4)
                s.box((c[0] - 0.12, top, c[2] - 0.12), (c[0] + 0.12, top + h, c[2] + 0.12), dirt, uv=[0, 0, 4, 4])
    s.front = (0, 1, 0)

    # 막혀서 주저앉음: 아래 두 단만, 얼굴은 위로 더 젖혀짐
    p = m.state("plug", poses=1)
    low = ((-H, -1.0, 1.75), (-1.0, -0.35, 1.42), (-0.35, 0.05, 0.9))
    mound(p, k, low, rim=False, scorched=True)
    buried_faces(p, low, [(0, j, 0.64, 30) for j in range(8)] + [(1, j, 0.56, 40) for j in (1, 3, 5, 7)])
    for side in (1, -1):
        p.rod((side * 1.3, -0.9, -0.35), (side * 1.0, 0.35, -0.35), length=1.4, segs=2, thick=0.13, base=False, rag=False)
    p.front = (0, 1, 0)

    ant(k)


def ant(k: kit.Kit):
    """개미 줄: 얼굴 개미 넷이 한 줄(앞 +Z), 개미마다 막대기 조각 다리 여섯(2자세로 번갈아 디딤)"""
    m = k.model("g114_ant", poses=2, period=6, mut2=False, torn=False, rag=False)
    s = m.state("")
    dirt = k.texture("dirt", dirt_tex())
    for i, z in enumerate((0.54, 0.18, -0.18, -0.54)):
        y = 0.0 if i % 2 == 0 else 0.03
        s.head(size=0.24, depth=0.16, at=(0, y, z), face="smile_a" if i % 2 else "smile", near=False, torn=False)
        # 얼굴 뒤 작은 흙빛 배
        s.box((-0.07, y - 0.06, z - 0.2), (0.07, y + 0.05, z - 0.08), dirt, uv=[0, 0, 4, 4])
        for side in (1, -1):
            for j, dz in enumerate((0.05, -0.04, -0.13)):
                ph = 0 if (j + (side > 0)) % 2 == 0 else 180
                s.rod((side * 0.09, y - 0.07, z + dz), (side, -0.7, 0.15 - j * 0.15), length=0.17, segs=1, thick=0.03, base=False,
                      wave=kit.Wave(amp=24, phase=ph), toward=(0, 0, 1), poses=2)
    s.front = (0, 1, 0)


def sounds(k: kit.Kit):
    k.sample("scratch", "tag:scratch", "무덤 속 긁는 소리", pitch=0.7, attenuation=24)
    k.sample("spew", "tag:bug", "개미 줄기", pitch=0.8, attenuation=20)
    k.sample("bite", "tag:bug", "개미 물기", pitch=1.3, volume=0.7, attenuation=12)


def phrases():
    return {"cause_g114": "개미 줄에 뜯겼다"}
