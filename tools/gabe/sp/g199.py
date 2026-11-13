"""G199 눈 찾는 거인 게이브(4막 34라운드, 정예 + 목표 압박 + 보상): 키 약 15블록 거인.

몸은 엔드 막대기 뼈대(등뼈, 갈비, 골반)에 _Gave 얼굴 판을 느슨하게 걸친 사람꼴, 머리는 4블록 _Gave 얼굴인데 왼쪽(+X) 눈 자리가 움푹 파인 검은 구멍이고
그 둘레에 부러진 속눈썹 같은 엔드 막대기 넷이 비죽 솟았다. 구멍에서 검은 진물 줄기가 얼굴과 몸을 타고 흘러내린다(흐름 4자세). 남은 눈은 실눈.

- gave:gabe/g199(shrink 0.2): 몸 가운데(0, 0, 0) = 엔진 pos(바닥 + HOVER 9.0). 상태 ""(더듬어 걸음), "kneel"(눈을 받아 끼움: 몸을 숙이고 두 눈을 뜸, 진물 없음).
- gave:gabe/g199_leg(shrink 0.2): 뼈 다리(엉덩이 (0,0,0) 발 (0,-LEG_L,0)). gave:gabe/g199_arm(shrink 0.25): 팔(어깨 (0,0,0) 에서 아래로 6, 손은 얼굴 넷 덩이).
- gave:gabe/g199_pool: 진물 못 판(3 x 3, Java 가 반지름에 맞춰 키움, 끓음 2자세). gave:gabe/g199_eye: 잃어버린 눈 알(지름 1.5 얼굴 알, 눈 감음, "held" 눈 뜸).
소리 gurgle(눈구멍에서 끓는 소리, 96), step(땅울림, 96), sweep(손 쓸기, 48), throw(내던짐, 32), eye(눈을 끼움, 96).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.2
HOVER = 9.0
HIP_X, HIP_Y = 1.2, -2.4
LEG_L = 6.6
SHOULDER_X, SHOULDER_Y = 2.0, 2.4
HEAD = 4.0
HEAD_Y = 5.2
OOZE = (14, 10, 14)


def stick(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    up = abs(d[1]) > 0.97 * L
    s.rod(tuple(a), tuple(d), length=L, segs=1, thick=thick, base=False, rag=False, tex=tex, toward=(0, 0, 1) if up else (0, 1, 0))


def ooze_tex(seed, n=32):
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    v = 8 + rng.rand(n, n) * 10
    for x in range(n):
        y0 = rng.randint(0, n)
        out[(np.arange(y0, y0 + 6) % n), x, :3] = 34
    out[..., 0] = np.maximum(out[..., 0], v.astype(np.uint8))
    out[..., 1] = np.maximum(out[..., 1], (v * 0.8).astype(np.uint8))
    out[..., 2] = np.maximum(out[..., 2], (v * 0.9).astype(np.uint8)) & 0xF8
    out[..., 3] = 255
    return out


def body(s, k, kneel):
    tilt = 25 if kneel else 0
    rot = (tilt, 0, 0) if kneel else None
    piv = (0, HIP_Y, 0)
    ow = k.texture("ooze0", ooze_tex(199))
    # 골반, 등뼈, 갈비, 어깨 막대기
    s.box((-1.6, HIP_Y - 0.4, -0.7), (1.6, HIP_Y + 0.3, 0.7), "bone", face=True)
    stick(s, (0, HIP_Y, 0), (0, SHOULDER_Y + 0.4, 0.3 if kneel else 0), 0.45)
    for i in range(5):
        y = HIP_Y + 1.4 + i * 0.8
        for side in (1, -1):
            stick(s, (0, y, -0.2), (side * 1.4, y - 0.25, 0.6), 0.2)
    stick(s, (-SHOULDER_X, SHOULDER_Y, 0), (SHOULDER_X, SHOULDER_Y, 0), 0.4)
    # 느슨하게 걸친 얼굴 판(앞뒤로 기울어 늘어짐)
    rng = np.random.RandomState(199)
    for i in range(14):
        x = rng.uniform(-1.4, 1.4)
        y = rng.uniform(HIP_Y + 0.6, SHOULDER_Y - 0.2)
        z = 0.75 if i % 3 else -0.75
        f = ("smile", "half", "tears", "smile_a", "blank")[i % 5]
        s.quad((x, y, z), (1.1, 1.1), "south" if z > 0 else "north", k.face_tex(f), face=True,
               rot=(rng.uniform(-25, 10), 0, rng.uniform(-20, 20)), origin=(x, y, z))
    # 목, 머리(왼쪽 눈 자리가 빈 구멍)
    stick(s, (0, SHOULDER_Y, 0), (0, HEAD_Y - HEAD / 2 + 0.2, 0.3), 0.6)
    hy, hz = HEAD_Y - (0.6 if kneel else 0), 0.3 + (1.4 if kneel else 0)
    hrot = (35, 0, 0) if kneel else None
    face = faces.Face(eyes="open" if kneel else "squint", cut=None)
    s.head(size=HEAD, depth=3.0, at=(0, hy, hz), face=face, near=False, rot=hrot)
    if not kneel:
        # 빈 눈구멍(검은 판) + 부러진 속눈썹 막대기 넷 + 흘러내리는 진물 줄기(4자세)
        ex, ey, ez = 0.9, hy + 0.55, hz + 1.52
        s.box((ex - 0.55, ey - 0.45, ez - 0.02), (ex + 0.55, ey + 0.45, ez + 0.03), ow, faces=("south",))
        for j in range(4):
            a = math.radians(30 + j * 40)
            stick(s, (ex + math.cos(a) * 0.5, ey + math.sin(a) * 0.45, ez), (ex + math.cos(a) * 1.3, ey + math.sin(a) * 1.2, ez + 0.4), 0.14)
        for fr in s.frames(poses=4):
            with fr:
                for j, (dx, L) in enumerate(((-0.25, 2.2), (0.15, 3.4), (0.4, 1.6))):
                    sh = fr.t * 0.6 + j * 0.2
                    s.box((ex + dx - 0.12, ey - 0.3 - L - sh, ez + 0.02), (ex + dx + 0.12, ey - 0.3 - sh * 0.3, ez + 0.12), ow,
                          faces=("south", "east", "west"))
                s.box((0.3, HIP_Y + 1.0 - fr.t, 0.8), (0.7, SHOULDER_Y - fr.t * 0.5, 0.95), ow, faces=("south", "east", "west"))


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, shrink=S, mut2=False, torn=False, rag=False)
    body(m.state(""), k, False)
    body(m.state("kneel"), k, True)
    lg = k.model("g199_leg", poses=1, period=20, shrink=0.2, mut2=False, open=False, torn=False, rag=False)
    ls = lg.state("")
    ls.box((-0.5, -0.4, -0.5), (0.5, 0.3, 0.5), "bone", face=True)
    stick(ls, (0, 0, 0), (0, -LEG_L * 0.5, 0.3), 0.5)
    stick(ls, (0, -LEG_L * 0.5, 0.3), (0, -LEG_L + 0.3, 0), 0.45)
    ls.head(size=1.0, depth=0.5, at=(0, -LEG_L * 0.5, 0.65), face="half", near=False, open=False, torn=False)
    ls.box((-0.55, -LEG_L, -0.5), (0.55, -LEG_L + 0.45, 0.9), "bone", face=True)
    ls.front = (0, 0, 0)
    am = k.model("g199_arm", poses=1, period=20, shrink=0.22, mut2=False, open=False, torn=False, rag=False)
    a = am.state("")
    stick(a, (0, 0, 0), (0, -3.0, 0.2), 0.42)
    stick(a, (0, -3.0, 0.2), (0, -5.4, 0.5), 0.38)
    for j, (dx, dz) in enumerate(((-0.35, 0.3), (0.35, 0.3), (0, 0.75), (0, -0.1))):
        a.head(size=0.75, depth=0.6, at=(dx, -5.8, 0.5 + dz), face=("smile_a", "smile_c", "open", "half")[j], near=False, open=False, torn=False,
               rot=(70, 0, 0))
    a.front = (0, 0, 0)
    pl = k.model("g199_pool", poses=2, period=24, mut2=False, open=False, torn=False, rag=False)
    ps = pl.state("")
    pt = [k.texture(f"pool{i}", _pool(299 + i)) for i in range(2)]
    for fr in ps.frames(poses=2):
        with fr:
            ps.quad((0, 0, 0), (3.0, 3.0), "up", pt[fr.k])
    ps.front = (0, 0, 0)
    ey = k.model("g199_eye", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    ey.state("").head(size=1.4, depth=1.4, face=faces.Face(eyes="none", mouth="none", blush="none"), near=True, open=False, torn=False)
    ey.state("held").head(size=1.4, depth=1.4, face=faces.Face(eyes="open", mouth="none", blush="deep"), near=True, open=False, torn=False)
    k.icon(model="g199", state="")


def _pool(seed, n=64):
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    r = np.hypot(xx - c, yy - c) / (n / 2) * (1 + 0.06 * np.sin(np.arctan2(yy - c, xx - c) * 7 + seed))
    bay = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32
    keep = bay[yy.astype(int) % 4, xx.astype(int) % 4] < np.clip((1 - r) / 0.15, 0, 1)
    v = 10 + rng.rand(n, n) * 14
    out[..., 0] = v
    out[..., 1] = v * 0.85
    out[..., 2] = (v * 0.95).astype(np.uint8) & 0xF8
    for _ in range(10):
        bx, by = rng.uniform(8, n - 8, 2)
        d = np.hypot(xx - bx, yy - by)
        ring = (np.abs(d - rng.uniform(1.5, 3.5)) < 0.8)
        out[ring, :3] = (48, 44, 48)
    out[..., 3] = np.where(keep, 255, 0)
    return faces._fill_rest(out)


def sounds(k: kit.Kit):
    k.sample("gurgle", ["oga_creature80/burble_01", "oga_creature80/burble_02", "oga_loops30/water_boiling"], "눈구멍에서 끓는 소리", pitch=0.55,
             attenuation=96, level=-9)
    k.sample("step", "tag:quake", "거인 땅울림", pitch=0.7, attenuation=96, level=-10)
    k.sample("sweep", "kenney_impact/impactsoft_heavy_002", "손으로 땅을 쓺", pitch=0.6, attenuation=48, n=1)
    k.sample("throw", "kenney_impact/impactpunch_heavy_004", "사람을 내던짐", pitch=0.7, attenuation=32, n=1)
    k.sample("eye", "tag:boss_roar", "눈을 되찾음", pitch=0.6, attenuation=96, level=-9, n=1)


def phrases():
    return {"cause_g199": "검은 진물에 녹았다", "cause_g199_sweep": "더듬는 손에 쓸렸다", "cause_g199_throw": "눈구멍에 대어 보고 내던져졌다",
            "notice_g199_eye": "눈을 돌려주었다", "hint_g199": "눈 알을 들고 거인 발치로"}
