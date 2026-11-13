"""G179 다시 오는 게이브: 어깨 넓은 2.6 블록 덩치. 깨진 얼굴 판 여러 장을 굵은 실로 기워 몸통을 만들었고, 어깨에 엔드 막대기 둘이 멍에처럼
가로로 박혀 있다. 죽을 때마다 기운 자국이 한 줄씩 늘고(on:scar1..3), 다시 올 때마다 막대기 하나가 더 박힌다(on:rod3, on:rod4).
지난번에 죽은 무기의 자국이 가슴 덧판으로 남는다(on:pl_bullet 납빛, on:pl_pellet 곰보, on:pl_sniper 뚫린 구멍, on:pl_explosion 우그러짐,
on:pl_fire 그을림). 덧판은 도감의 부품 디스플레이 대신 같은 모델의 조건 부위로 했다(디스플레이 하나 아낌).

모델 gave:gabe/g179(shrink 0.75), 몸 중심은 땅 위 1.3(Java). 상태 ""(걷기 4자세). 들이받을 때 숙이는 것은 Java 가 몸 pitch 와 look.rate 로 한다.
"""
import math

import numpy as np

from gabe import faces, kit


def plate_tex(kind, seed=179):
    n = 32
    rng = np.random.RandomState(seed + sum(map(ord, kind)) % 97)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., 3] = 255
    yy, xx = np.mgrid[0:n, 0:n]
    if kind == "bullet":
        col = np.full((n, n, 3), (104, 108, 118), np.float32) + rng.randint(-8, 9, (n, n, 1))
        for _ in range(9):  # 박힌 탄 자국(밝은 테 + 검은 점)
            cx, cy = rng.randint(4, n - 4, 2)
            col[(xx - cx) ** 2 + (yy - cy) ** 2 <= 4] = (160, 164, 172)
            col[(xx - cx) ** 2 + (yy - cy) ** 2 <= 1] = (40, 40, 46)
    elif kind == "pellet":
        col = np.full((n, n, 3), (92, 90, 96), np.float32) + rng.randint(-8, 9, (n, n, 1))
        pits = rng.rand(n, n) < 0.09
        col[pits] = (36, 34, 38)
    elif kind == "sniper":
        col = np.full((n, n, 3), (120, 112, 104), np.float32) + rng.randint(-6, 7, (n, n, 1))
        r = np.hypot(xx - 15.5, yy - 15.5)
        col[(r > 3) & (r < 5)] = (170, 160, 150)
        col[r <= 3] = (18, 10, 10)
        for a in np.linspace(0, math.tau, 7, endpoint=False):  # 금
            for t in range(5, 14):
                x, y = int(15.5 + math.cos(a) * t), int(15.5 + math.sin(a) * t)
                col[y, x] = (60, 54, 50)
    elif kind == "explosion":
        col = np.full((n, n, 3), (84, 78, 72), np.float32)
        col += (np.sin(xx / 3.0) * np.cos(yy / 4.0) * 26)[..., None]  # 우그러진 굴곡
        col += rng.randint(-6, 7, (n, n, 1))
    else:  # fire
        col = np.full((n, n, 3), (34, 26, 22), np.float32) + rng.randint(-6, 7, (n, n, 1))
        ember = rng.rand(n, n) < 0.05
        col[ember] = (196, 92, 40)
        ash = rng.rand(n, n) < 0.06
        col[ash] = (96, 90, 86)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    return out


def thread_tex():
    out = np.zeros((4, 4, 4), np.uint8)
    out[..., :3] = (40, 22, 20)
    out[..., 3] = 255
    out[1, :, :3] = (70, 40, 34)
    return out


PLATES = ("bullet", "pellet", "sniper", "explosion", "fire")


def body(k, s, charge):
    thread = k.texture("thread", thread_tex())
    lean = 18 if charge else 0
    rot = dict(rot=(lean, 0, 0), origin=(0, -0.3, 0)) if lean else {}
    # 몸통: 살 덩이 위에 깨진 얼굴 판 여러 장을 덧대 기움(판마다 다른 얼굴, 조금씩 기움)
    s.box((-0.62, -0.35, -0.38), (0.62, 0.92, 0.36), "flesh", face=True, **rot)
    patches = ((-0.32, 0.55, "smile", 0.62, 6), (0.33, 0.6, "half", 0.56, -8), (-0.3, 0.0, "stitch", 0.58, -5),
               (0.3, 0.02, "flipped", 0.6, 9))
    for x, y, f, sz, tilt in patches:
        s.head(size=sz, depth=0.08, at=(x, y + 0.02 * lean / 18, 0.38), face=f, near=False, open=False, torn=False,
               rot=(lean, 0, tilt))
    # 가운데 큰 얼굴(웃는 입이 기워짐): 실눈 뜸은 Java mark
    s.head(size=0.72, depth=0.5, at=(0, 0.42 + 0.05 * lean / 18, 0.25), face=faces.Face(extras=("stitch",)), rot=(lean, 0, 0))
    # 굵은 실(판 사이 이음매)
    s.box((-0.03, -0.32, 0.37), (0.03, 0.9, 0.42), thread, **rot)
    s.box((-0.6, 0.28, 0.37), (0.6, 0.33, 0.42), thread, **rot)
    # 기운 자국(죽을 때마다 한 줄씩): 가슴을 가로지르는 굵은 실
    for i, (y, a) in enumerate(((0.75, 14), (-0.15, -12), (0.2, 28))):
        with s.when(f"on:scar{i + 1}"):
            s.box((-0.58, y - 0.03, 0.43), (0.58, y + 0.03, 0.47), thread, rot=(lean, 0, a), origin=(0, y, 0.45))
    # 가슴 덧판(지난번에 죽은 무기)
    for kind in PLATES:
        with s.when(f"on:pl_{kind}"):
            s.box((-0.3, -0.25, 0.42), (0.3, 0.2, 0.5), k.texture(f"plate_{kind}", plate_tex(kind)), **rot)
    # 어깨 멍에 막대기(가로로 박힘): 둘, 다시 오면 셋째, 넷째
    s.rod((-1.1, 0.78, 0.05), (1, 0, 0), length=2.2, segs=1, thick=0.12, base=False, rag=False)
    s.rod((-1.0, 0.62, -0.2), (1, 0.04, 0.0), length=2.0, segs=1, thick=0.11, base=False, rag=False)
    with s.when("on:rod3"):
        s.rod((-0.95, 0.4, 0.1), (1, 0.3, 0.1), length=1.9, segs=1, thick=0.11, base=False, rag=False)
    with s.when("on:rod4"):
        s.rod((-0.9, 0.95, -0.25), (1, -0.35, 0.05), length=1.8, segs=1, thick=0.11, base=False, rag=False)
    # 굵은 팔(살)과 걷는 다리
    for side in (1, -1):
        lo, hi = sorted((side * 0.6, side * 0.9))
        s.box((lo, -0.55, -0.18), (hi, 0.75, 0.18), "flesh", face=True, **rot)
    for f in s.frames():
        with f:
            for side in (1, -1):
                ph = f.t * math.tau + (0 if side > 0 else math.pi)
                dz = 0.18 * math.sin(ph)
                lift = 0.08 * max(0.0, math.cos(ph))
                x = side * 0.3
                s.box((x - 0.2, -1.3 + lift, -0.2 + dz), (x + 0.2, -0.35 + lift, 0.2 + dz), "flesh", face=True)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=0.75, mut2=False, torn=False)
    body(k, m.state(""), False)
    k.icon(model="g179", state="")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 실밥 터지는 듯한 끙끙
    k.sample("groan", "tag:groan", "기운 자국 끙끙", pitch=0.7, attenuation=32)
    # 어깨 박치기, 내려찍기
    k.sample("ram", "kenney_impact/impactpunch_heavy_00*", "어깨 박치기", pitch=0.7, attenuation=20)
    k.sample("slam", "kenney_impact/impactsoft_heavy_00*", "내려찍기", pitch=0.55, attenuation=24)
    # 안 먹히는 무기에 맞음(덧판이 튕김)
    k.sample("clank", "tag:metal+impact", "덧판이 튕김", pitch=0.9, volume=0.8, attenuation=20)


def phrases():
    return {"cause_g179": "다시 온 것에 짓밟혔다"}
