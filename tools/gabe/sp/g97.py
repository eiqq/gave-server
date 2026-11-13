"""G97 올려다보면 삼키는 구름 게이브(2막 16라운드, 방해 + 원거리): 폭 12블록의 흐린 구름. 바랜 _Gave 얼굴 수십이 뭉게뭉게 겹쳐 구름 덩이를
이루고(Java 가 팔레트 탈색 PAL_PALE 을 건다), 아랫면 가운데는 바늘땀으로 꿰맨 듯 접힌 입이 닫혀 있다. 구름 가장자리에서 엔드 막대기 둘이
낚싯대처럼 아래로 늘어져 바람 없이 흔들린다.

모델:
  gave:gabe/g97          구름 덩이(가운데 = 구름 가운데). 상태 "" 입 닫힘, "gape" 입 벌림(핏발 선 눈, 이빨 고리)
  gave:gabe/g97_funnel   깔때기 살 관(뿌리 y 0 이 구름 아랫면, 아래로 길이 20, 끝에 아래를 보는 입 얼굴과 뼈 이빨). Java 가 늘이고 기울인다
  gave:gabe/g97_shadow   바닥 구름 그림자(점묘, 14 x 14). 상태 s1, s2
  gave:gabe/g97_hail     바랜 얼굴 우박(작은 얼굴과 짧은 막대기 둘)
  gave:gabe/g97_spot     우박 떨어질 자리 작은 그림자(지름 1.6)
"""
import math

import numpy as np

from gabe import faces, kit

# 구름 얼굴(x, y, z, 크기, 얼굴): 납작한 타원체 겉(폭 12, 높이 4.5, 깊이 10)
FACES = [faces.Face(), faces.Face(eyes="half"), faces.Face(extras=("tears",)), faces.Face(eyes="none", mouth="dot"),
         faces.Face(tilt=-14.0), faces.Face(eyes="half", extras=("stitch",)), faces.Face(tilt=14.0), faces.Face(blush="none")]


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def puffs(seed=97):
    """구름 겉 자리들(가운데에서 바깥을 보는 방향과 함께)"""
    rng = np.random.RandomState(seed)
    out = []
    # 아래 고리(아래 바깥을 봄), 가운데 고리(옆), 위(위 바깥)
    for ring, (ry, rr, n, size) in enumerate(((-1.5, 4.3, 9, 2.5), (0.1, 5.4, 10, 2.9), (1.6, 3.6, 6, 2.4))):
        for i in range(n):
            a = (i + 0.5 * ring) / n * math.tau + rng.uniform(-0.12, 0.12)
            x, z = math.cos(a) * rr, math.sin(a) * rr * 0.85
            y = ry + rng.uniform(-0.3, 0.3)
            out.append((x, y, z, size * rng.uniform(0.82, 1.12), a, ring))
    # 아랫면 안쪽(아래를 봄) 둘레
    for i in range(5):
        a = i / 5 * math.tau + 0.4
        out.append((math.cos(a) * 2.4, -2.0, math.sin(a) * 2.1, 2.2, a, 3))
    return out


def cloud_tex(n=32, seed=97, shade=1.0):
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    v = (0.78 + 0.1 * np.sin(xx * 0.45 + yy * 0.25) + rng.rand(n, n) * 0.07) * shade
    out = np.zeros((n, n, 4), np.uint8)
    out[..., 0] = np.clip(v * 206, 0, 255)
    out[..., 1] = np.clip(v * 196, 0, 255)
    out[..., 2] = np.clip(v * 204, 0, 255).astype(np.uint8) & 0xF8
    out[..., 3] = 255
    return out


def cloud(s, k, gape):
    ct = k.texture("cloud", cloud_tex(shade=1.12))
    cd = k.texture("cloud_dark", cloud_tex(seed=98, shade=0.95))
    # 속 덩이(얼굴 사이 틈을 메움)
    for (x, y, z, rx, ry, rz) in [(0, 0, 0, 4.6, 1.9, 3.9), (2.6, 0.6, 1.2, 2.8, 1.6, 2.6), (-2.8, 0.5, -1.0, 2.9, 1.6, 2.5),
                                  (0.8, 1.2, -2.2, 2.6, 1.4, 2.2), (-1.4, -0.4, 2.4, 2.6, 1.4, 2.0), (0, -1.6, 0, 3.6, 0.6, 3.0)]:
        rx, ry, rz = rx * 0.86, ry * 0.9, rz * 0.86
        s.box((x - rx, y - ry, z - rz), (x + rx, y + ry, z + rz), {"up": ct, "down": cd, "north": ct, "south": ct, "east": ct, "west": ct})
    for i, (x, y, z, size, a, ring) in enumerate(puffs()):
        yaw = -math.degrees(a) + 90  # 바깥을 보게
        if ring == 0:
            rot = (35, yaw, 0)
        elif ring == 1:
            rot = (0, yaw, 0)
        elif ring == 2:
            rot = (-50, yaw, 0)
        else:
            rot = (90, yaw, 0)
        s.head(size=size, depth=size * 0.55, at=(x, y, z), face=FACES[i % len(FACES)], rot=rot, near=False, torn=False)
    # 아랫면 가운데 입
    mouth = faces.Face(eyes="bloodshot", mouth="teeth_ring", blush="deep") if gape else faces.Face(eyes="half", mouth="dot", extras=("stitch",))
    s.head(size=3.0, depth=0.8, at=(0, -2.1, 0), face=mouth, rot=(90, 0, 0), near=False, torn=False)
    if gape:
        s.teeth((0, -2.55, 0), 1.25, count=12, axis=(0, -1, 0), length=0.5, size=0.2, inward=0.25)
    # 낚싯대 막대기 둘(가장자리에서 아래로, 느리게 흔들림)
    for sx in (1, -1):
        s.rod((sx * 5.6, -0.6, sx * 0.8), (sx * 0.12, -1, 0), length=5.2, segs=2, thick=0.2, toward=(0, 0, 1), poses=4,
              wave=kit.Wave(amp=(5, 7), lag=40, phase=0 if sx > 0 else 160), rag=False)


def funnel(s, k):
    """깔때기 살 관: 위(구름 아랫면, y 0)는 넓고 아래로 갈수록 좁아짐, 끝에 아래를 보는 입 얼굴과 뼈 이빨 고리"""
    fl = k.part_tex("flesh")
    wet = k.part_tex("wetskin")
    y = 0.0
    rings = [(2.5, 2.2), (2.1, 2.4), (1.8, 2.6), (1.55, 2.6), (1.35, 2.6), (1.2, 2.6), (1.1, 2.4), (1.25, 1.6)]
    for i, (r, h) in enumerate(rings):
        t = wet if i % 2 else fl
        s.box((-r, y - h, -r), (r, y, r), t, face=True, faces=("north", "south", "east", "west"))
        y -= h
    s.head(size=2.4, depth=0.6, at=(0, y - 0.2, 0), face=faces.Face(eyes="bloodshot", mouth="teeth_ring", blush="deep"), rot=(90, 0, 0),
           near=False, torn=False)
    s.teeth((0, y - 0.55, 0), 1.0, count=10, axis=(0, -1, 0), length=0.45, size=0.18, inward=0.25)
    s.front = (0, -1, 0)


def shadow_tex(dens, n=112, seed=97):
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    blobs = [(0, 0, 0.62), (0.32, 0.1, 0.42), (-0.3, -0.15, 0.44), (0.1, -0.35, 0.36), (-0.1, 0.36, 0.38)]
    for y in range(n):
        for x in range(n):
            px, py = (x - c) / c, (y - c) / c
            r = min(math.hypot(px - bx, py - by) / br for bx, by, br in blobs)
            if r >= 1:
                continue
            d = dens * min(1.0, (1 - r) / 0.45)
            if bay[y % 4, x % 4] < d:
                out[y, x] = (26, 22, 30, 255)
    return faces._fill_rest(out)


def spot_tex(n=24):
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r < 1 and bay[y % 4, x % 4] < 0.75 * min(1, (1 - r) / 0.3):
                out[y, x] = (30, 20, 26, 255)
    return faces._fill_rest(out)


def build(k: kit.Kit):
    m = k.model(poses=4, period=48, shrink=0.19, mut2=False, torn=False, rag=False)
    cloud(m.state(""), k, False)
    cloud(m.state("gape"), k, True)
    fn = k.model("g97_funnel", poses=1, period=20, shrink=0.066, mut2=False, open=False, torn=False, rag=False)
    funnel(fn.state(""), k)
    sh = k.model("g97_shadow", poses=1, period=20, shrink=0.2, mut2=False, open=False, torn=False, rag=False)
    for i, dens in enumerate((0.3, 0.6), start=1):
        sh.state(f"s{i}").quad((0, 0, 0), (14, 14), "up", k.texture(f"shadow{i}", shadow_tex(dens)))
    sh.default = "s1"
    hl = k.model("g97_hail", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    h = hl.state("")
    h.head(size=0.62, depth=0.5, face=faces.Face(eyes="half"), near=False, torn=False)
    for sx in (1, -1):
        h.rod((sx * 0.2, 0.25, -0.2), (sx * 0.4, 1, -0.3), length=0.45, segs=1, thick=0.07, wave=None, base=False, rag=False)
    sp = k.model("g97_spot", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    sp.state("").quad((0, 0, 0), (1.6, 1.6), "up", k.texture("spot", spot_tex()))
    k.icon(model="g97", state="")


def sounds(k: kit.Kit):
    k.sample("rumble", "tag:rumble", "구름 우르릉", pitch=0.5, attenuation=96)
    k.sample("gulp", "tag:eat", "꿀꺽", pitch=0.45, attenuation=48)
    k.sample("hail", "tag:plop", "우박", pitch=0.8, attenuation=32)


def phrases():
    return {"cause_g97": "올려다보다 구름에 삼켜졌다", "cause_g97_hail": "얼굴 우박에 맞았다"}
