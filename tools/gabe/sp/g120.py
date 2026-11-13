"""G120 사체 버섯 게이브.
포자: 0.4 블록 얼굴 위로 엔드 막대기 둘이 민들레 씨 자루처럼 솟고 끝에 솜털 갓. 버섯: 사체 위로 솟은 키 2.2 블록 버섯, 대는 엔드 막대기 둘을
꼰 것, 갓은 _Gave 얼굴을 우산처럼 둥글게 편 것(가운데 큰 얼굴 + 둘레 얼굴 여섯이 둥근 지붕), 갓 아랫면 주름마다 작은 실눈.

모델 gave:gabe/g120 상태:
  "seed" 구르는 포자(가운데 = 얼굴, Java hover 0.3)
  "grow" 솟는 중(짧은 대와 작은 갓)
  ""     버섯(가운데 = 키 한가운데, Java hover 1.1). 갓이 숨 쉬듯 오므렸다 펴짐(2자세)
모델 gave:gabe/g120_cloud: 포자 구름(분홍 솜털 덩이, 2자세 출렁임). Java 가 연출 디스플레이에 실어 날려 보냄.
소리 puff(포자 기침, 20), root(뿌리내림, 16), bite(포자 물기, 12).
"""
import math

import numpy as np

from gabe import faces, kit

GILL = faces.Face(eyes="squint", mouth="none", blush="none")


def _rgba(arr):
    return np.clip(np.round(arr), 0, 255).astype(np.uint8)


def fluff_tex(n=16, seed=120):
    """솜털: 흰 분홍 가는 털이 사방으로(컷아웃)"""
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.float32)
    c = (n - 1) / 2
    for i in range(22):
        a = rng.rand() * 2 * math.pi
        L = rng.uniform(0.45, 1.0) * c
        for t in np.linspace(0, 1, 12):
            x, y = int(round(c + math.cos(a) * L * t)), int(round(c + math.sin(a) * L * t))
            if 0 <= x < n and 0 <= y < n:
                out[y, x, :3] = (255, 236, 236) if t > 0.4 else (246, 200, 204)
                out[y, x, 3] = 255
    out[int(c) - 1:int(c) + 2, int(c) - 1:int(c) + 2, :3] = (226, 150, 160)
    out[int(c) - 1:int(c) + 2, int(c) - 1:int(c) + 2, 3] = 255
    return _rgba(out)


def gills_tex(n=32, seed=5):
    """갓 아랫면: 가운데로 모이는 분홍 살 주름"""
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    c = (n - 1) / 2
    dx, dy = (xx - c) / c, (yy - c) / c
    r = np.hypot(dx, dy)
    a = np.arctan2(dy, dx)
    ridge = np.sin(a * 14)
    lo, hi = np.array([150, 62, 76], np.float32), np.array([232, 156, 160], np.float32)
    t = (ridge * 0.5 + 0.5)[..., None]
    col = lo * (1 - t) + hi * t
    col *= (1.0 - 0.3 * np.clip(r, 0, 1))[..., None]
    col += np.random.RandomState(seed).randn(n, n, 1) * 4
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = col
    out[..., 3] = 255
    out[r < 0.18, :3] = (96, 30, 40)
    return _rgba(out)


def spore_tex(n=16, seed=9):
    """포자 덩이: 분홍 알갱이가 성글게(컷아웃, 사이로 비침)"""
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.float32)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    c = (n - 1) / 2
    r = np.hypot(xx - c, yy - c) / c
    m = (rng.rand(n, n) < 0.55 * (1.1 - r)) & (r < 1.0)
    shade = rng.rand(n, n)
    out[m, 0] = 236 - shade[m] * 40
    out[m, 1] = 150 - shade[m] * 50
    out[m, 2] = 170 - shade[m] * 40
    out[m, 3] = 255
    return _rgba(out)


def seed_state(s, k):
    fluff = k.texture("fluff", fluff_tex())
    h = s.head(size=0.4, depth=0.3, face="smile")
    sway = kit.Wave(amp=(8, 10), lag=40)
    for side, ph in ((1, 0), (-1, 180)):
        c = s.rod(h.point(side * 0.35, 1, 0), (side * 0.3, 1, 0), length=0.55, segs=2, thick=0.045, wave=kit.Wave(amp=(8, 10), lag=40, phase=ph),
                  toward=(1, 0, 0), poses=4, rag=False)
        # 끝 솜털 갓: 엇갈린 판 둘(자세마다 막대기 끝을 따라감)
        for f in s.frames(4):
            with f:
                tx, ty, tz = c.tip(f.k)
                s.quad((tx, ty + 0.1, tz), (0.36, 0.3), "south", fluff)
                s.quad((tx, ty + 0.1, tz), (0.36, 0.3), "east", fluff)


def stalk(s, top, bottom=-1.1, twist=1.0):
    """꼰 대: 엔드 막대기 둘이 서로 감기며 올라감(자세 없음)"""
    L = top - bottom
    for side in (1, -1):
        s.rod((side * 0.1, bottom, 0), (-side * 0.12 * twist, 1, side * 0.06), length=L * 0.52, segs=1, thick=0.13, base=False, rag=False)
        s.rod((-side * 0.03, bottom + L * 0.5, side * 0.03), (side * 0.12 * twist, 1, -side * 0.06), length=L * 0.52, segs=1, thick=0.12,
              base=False, rag=False)


def cap(s, k, y, scale, tilt, gills=True):
    """얼굴 갓: 가운데 하늘을 보는 큰 얼굴 + 둘레 얼굴 여섯이 바깥 아래로 젖혀 둥근 지붕, 아랫면 주름과 작은 실눈들"""
    s.head(size=1.0 * scale, depth=0.22 * scale, at=(0, y + 0.12 * scale, 0), face="smile", rot=(-90, 0, 0))
    r = 0.62 * scale
    for i in range(6):
        a = i * 60.0 + 30
        ar = math.radians(a)
        at = (math.sin(ar) * r, y - math.sin(math.radians(tilt)) * 0.22 * scale, math.cos(ar) * r)
        s.head(size=0.62 * scale, depth=0.16 * scale, at=at, face="smile_a" if i % 2 else "smile", near=False, rot=(-(90 - tilt), a, 0))
    if gills:
        g = k.texture("gills", gills_tex())
        w = 0.82 * scale
        s.box((-w, y - 0.16 * scale, -w), (w, y - 0.1 * scale, w), {"down": g}, faces=("down",), uv=[0, 0, 16, 16], face=True)
        for i in range(6):
            a = i * 60.0
            ar = math.radians(a)
            s.head(size=0.16 * scale, depth=0.06, at=(math.sin(ar) * 0.45 * scale, y - 0.2 * scale, math.cos(ar) * 0.45 * scale), face=GILL,
                   near=False, torn=False, rot=(90, a, 0))


def build(k: kit.Kit):
    m = k.model(poses=2, period=30, mut2=False)
    s = m.state("seed", poses=4, period=24)
    seed_state(s, k)

    g = m.state("grow", poses=1)
    stalk(g, -0.25)
    cap(g, k, -0.2, 0.6, 50, gills=False)
    g.front = (0, 1, 0)

    b = m.state("")
    stalk(b, 0.62)
    for f in b.frames(2):
        with f:
            # 0 = 펼침(얕게 젖힘), 1 = 오므림(깊게 젖혀 가장자리가 대를 감쌈)
            cap(b, k, 0.66 if f.k == 0 else 0.6, 1.0, 34 if f.k == 0 else 62)
    b.front = (0, 1, 0)

    cloud(k)


def cloud(k: kit.Kit):
    m = k.model("g120_cloud", poses=2, period=12, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    sp = k.texture("spore", spore_tex())
    rng = np.random.RandomState(12)
    spots = []
    while len(spots) < 9:
        p = rng.uniform(-1, 1, 3)
        if p @ p <= 1:
            spots.append(p * np.array([0.9, 0.5, 0.9]))
    for f in s.frames(2):
        with f:
            for i, p in enumerate(spots):
                sz = 0.55 + 0.25 * ((i * 7) % 3) / 2 + (0.08 if (f.k + i) % 2 else 0)
                c = p * (1.0 if f.k == 0 else 1.12)
                s.box(tuple(c - sz / 2), tuple(c + sz / 2), sp, uv=[0, 0, 16, 16])
    s.front = (0, 0, 0)


def sounds(k: kit.Kit):
    k.sample("puff", "tag:cough", "포자 기침", pitch=0.85, attenuation=20)
    k.sample("root", "tag:wet+slime", "뿌리내림", pitch=0.7, attenuation=16)
    k.sample("bite", "tag:wet+slime", "포자 물기", pitch=1.3, volume=0.7, attenuation=12)


def phrases():
    return {"cause_g120": "버섯 포자를 들이마셨다"}
