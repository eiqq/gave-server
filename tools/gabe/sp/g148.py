"""G148 내려앉는 종 게이브(4막 33라운드, 돌격, 튼튼이): 아가리 지름 14, 높이 12블록의 큰 종이 하늘 40블록에서 트럭 위로 천천히 내려앉는다.
종 몸은 얼굴 가죽을 겹겹이 둘러 굳힌 쇳빛 띠 셋(위로 갈수록 좁아지는 계단), 아가리 안쪽 천장이 통째로 아래를 내려다보는 얼굴이다.
아가리 테를 따라 두꺼운 테 조각 여덟(e1..e8, 깎이면 떨어져 나감), 꼭대기 고리는 막대기 둘을 휘어 엮었고, 옆구리에 종 치는 통나무 막대기가 줄에 매달렸다.
테에서 부서진 작은 얼굴 가루가 줄줄 흘러내린다(손으로 짠 4자세).

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 부품):
  gave:gabe/g148         종 몸(가운데 = 종 높이 가운데, 아가리 아랫면 y = -6). 상태 "" , 부위 e1..e8(그 테 조각이 떨어짐)
  gave:gabe/g148_log     통나무 막대기와 매단 사슬 둘(뿌리 = 매단 곳, 통나무는 4블록 아래)
  gave:gabe/g148_shadow  바닥 그림자(위를 보는 점묘 원, 지름 2 로 굽고 Java 가 아가리 반지름으로 키움). 상태 s1..s4 짙기
소리는 받은 소리(k.sample). 사체 없음(부서져 떨어짐).
"""
import math

import numpy as np

from gabe import faces, kit

Y0 = -6.0                     # 아가리 아랫면(모델 y)
RIM_R, RIM_T, RIM_H = 7.35, 1.15, 1.45
# 종 옆모습(아가리에서 높이, 바깥 반지름): 벌어진 입술 → 허리 → 어깨 → 둥근 지붕
PROFILE = [(1.2, 6.8), (2.8, 6.05), (4.8, 5.45), (7.3, 5.15), (9.3, 4.95), (10.5, 4.35), (11.3, 3.3), (11.8, 1.9)]
BAND_T = 0.5
SIDES = 12
LOG_AT = (0.0, 6.6 + Y0, 6.2)   # 통나무 매단 곳(Java LOG_AT 과 같음): 앞(+Z) 어깨


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def iron_tex(seed, dark=0.0):
    """얼굴 가죽을 겹겹이 둘러 굳힌 쇳빛: 작은 _Gave 얼굴 2x2 를 쇳빛으로 물들여 이음매(검은 골)와 못 자국을 새김"""
    rng = np.random.RandomState(seed)
    out = np.zeros((64, 64, 4), np.float32)
    looks = [faces.Face(), faces.Face(eyes="half"), faces.Face(tilt=-12.0), faces.Face(eyes="open", blush="deep")]
    for j in range(2):
        for i in range(2):
            f = faces.far(looks[(i + 2 * j + seed) % 4]).astype(np.float32)
            lum = f[..., :3].mean(axis=2, keepdims=True)
            iron = lum * np.array([0.52, 0.50, 0.55]) + np.array([30, 27, 30])
            # 홍조와 입에 녹빛을 조금
            red = np.clip(f[..., 0:1] - f[..., 2:3], 0, 255) / 255.0
            iron = iron + red * np.array([40, 14, 6])
            out[j * 32:(j + 1) * 32, i * 32:(i + 1) * 32, :3] = iron
    yy, xx = np.mgrid[0:64, 0:64]
    seam = (xx % 32 < 1) | (yy % 32 < 1)
    out[seam, :3] *= 0.35
    for (x, y) in [(4, 4), (28, 4), (4, 28), (28, 28), (36, 36), (60, 36), (36, 60), (60, 60), (36, 4), (60, 4), (4, 36), (4, 60)]:
        out[y:y + 2, x:x + 2, :3] = (150, 146, 150)
    out[..., :3] += rng.randn(64, 64, 1) * 6
    out[..., :3] *= (1.0 - dark)
    out[..., 3] = 255
    return np.clip(out, 0, 255).astype(np.uint8)


def rim_tex(seed):
    """두꺼운 테 조각: 쇳빛 덩이에 금(검은 갈라짐)과 떨어져 나간 얼굴 조각의 분홍 속살"""
    rng = np.random.RandomState(seed)
    base = iron_tex(seed + 7).astype(np.float32)[:32, :32] * 0.9
    for _ in range(3):
        x, y = rng.randint(2, 30), rng.randint(2, 30)
        for t in range(14):
            x = int(np.clip(x + rng.randint(-1, 2), 0, 31))
            y = int(np.clip(y + 1, 0, 31))
            base[y, x, :3] = (22, 16, 18)
    for _ in range(4):
        x, y = rng.randint(3, 29), rng.randint(3, 29)
        base[y - 1:y + 2, x - 1:x + 2, :3] = (196, 104, 110)
    base[..., 3] = 255
    return np.clip(base, 0, 255).astype(np.uint8)


def dust_tex(k):
    """흘러내리는 얼굴 가루(세로 띠, 컷아웃): 작은 살빛, 쇳빛 부스러기가 자세 k 만큼 아래로 밀림"""
    rng = np.random.RandomState(14)
    out = np.zeros((64, 16, 4), np.uint8)
    pts = [(rng.randint(1, 15), rng.randint(0, 64), rng.randint(0, 3)) for _ in range(46)]
    for x, y, c in pts:
        y = (y + k * 16) % 64
        col = [(255, 214, 205), (150, 146, 152), (205, 120, 126)][c]
        sz = 1 if rng.rand() < 0.6 else 2
        out[y:y + sz, x:x + sz] = (*col, 255)
    return faces._fill_rest(out)


def shadow_tex(dens, n=128):
    """그림자: 가운데가 짙고 가장자리로 성긴 점묘 원 + 테두리에 밝은 선(떨어질 자리가 그대로 보임)"""
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r >= 1:
                continue
            if r > 0.955:
                out[y, x] = (214, 196, 168, 255) if (x + y) % 2 == 0 else (0, 0, 0, 0)
                continue
            d = dens * min(1.0, (1 - r) / 0.25)
            if bay[y % 4, x % 4] < d:
                v = 0.85 + 0.15 * math.sin(x * 0.7 + y * 0.45)
                out[y, x] = (int(20 * v), int(12 * v), int(16 * v), 255)
    return faces._fill_rest(out)


def ring_panels(s, ya, ra, yb, rb, t, tex_out, tex_in, n=SIDES):
    """정 n 각 띠 하나: (ya, ra) 에서 (yb, rb) 로 기운 판 n 장. 판마다 제 자리에 놓고 제 가운데로 기울임(x) 뒤 돌림(y)"""
    h = math.hypot(yb - ya, rb - ra) + 0.08
    tilt = -math.degrees(math.atan2(ra - rb, yb - ya))
    ym, rm = (ya + yb) / 2, (ra + rb) / 2 - t / 2
    w = 2 * (rm + t / 2) * math.tan(math.pi / n) + 0.06
    for i in range(n):
        a = 360.0 * i / n
        c = (math.sin(math.radians(a)) * rm, ym, math.cos(math.radians(a)) * rm)
        s.box((c[0] - w / 2, c[1] - h / 2, c[2] - t / 2), (c[0] + w / 2, c[1] + h / 2, c[2] + t / 2), {"south": tex_out, "north": tex_in},
              faces=("south", "north"), rot=(tilt, a, 0), origin=c, face=True)


def bell(s, k):
    iron = [k.texture(f"iron{i}", iron_tex(i)) for i in range(3)]
    inner = k.texture("iron_in", iron_tex(5, dark=0.45))
    for i in range(len(PROFILE) - 1):
        (ya, ra), (yb, rb) = PROFILE[i], PROFILE[i + 1]
        ring_panels(s, ya + Y0, ra, yb + Y0, rb, BAND_T, iron[i % 3], inner)
    # 꼭대기 받침
    yt, rt = PROFILE[-1]
    s.box((-rt, yt - 0.3 + Y0, -rt), (rt, yt + 0.5 + Y0, rt), iron[2], face=True, faces=("north", "south", "east", "west", "up"))
    # 꼭대기 고리: 막대기 둘을 휘어 엮음(가만히 있는 구조 막대기, 두 면으로 엇갈린 아치)
    top = yt + 0.5 + Y0
    for ax in ((1, 0, 0), (0, 0, 1)):
        for side in (1, -1):
            root = (ax[0] * side * 1.1, top - 0.1, ax[2] * side * 1.1)
            d1 = (ax[0] * side * 0.25, 1.0, ax[2] * side * 0.25)
            s.rod(root, d1, length=1.5, segs=1, thick=0.34, wave=None, toward=(ax[2], 0, ax[0]), base=False, rag=False)
        s.rod((-ax[0] * 1.5, top + 1.45, -ax[2] * 1.5), (ax[0], 0.0, ax[2]), length=3.0, segs=1, thick=0.34, wave=None,
              toward=(0, 1, 0), base=False, rag=False)
    # 아가리 테 조각 여덟(떨어져 나가는 부위)
    for i in range(8):
        a = 360.0 * i / 8 + 22.5
        w = 2 * RIM_R * math.tan(math.pi / 8) + 0.05
        tex = k.texture(f"rim{i % 3}", rim_tex(30 + i % 3))
        with s.when(f"!on:e{i + 1}"):
            s.box((-w / 2, Y0 - 0.15, RIM_R - RIM_T), (w / 2, Y0 + RIM_H, RIM_R), tex, rot=(0, a, 0), origin=(0, 0, 0), face=True,
                  faces=("south", "north", "up", "down"))
    # 아가리 안쪽 천장 = 아래를 내려다보는 얼굴(앞면이 아래)
    s.head(size=6.8, depth=0.5, at=(0, 9.5 + Y0, 0), face=faces.Face(eyes="half", mouth="teeth_ring", blush="deep", tilt=8.0),
           rot=(90, 0, 0), near=False, sides=inner, torn=False)
    # 천장 둘레를 막는 어두운 판(띠 셋 안쪽 위가 하늘로 뚫려 보이지 않게)
    s.box((-4.6, 10.25 + Y0, -4.6), (4.6, 10.35 + Y0, 4.6), inner, faces=("down",), face=True)
    # 흘러내리는 얼굴 가루(테 아래로 늘어진 세로 띠 여섯, 4자세로 아래로 흐름)
    for f in s.frames(poses=4):
        with f:
            t = k.texture(f"dust{f.k}", dust_tex(f.k))
            for i in range(6):
                a = math.radians(60.0 * i + 10)
                x, z = math.sin(a) * (RIM_R - 0.4), math.cos(a) * (RIM_R - 0.4)
                s.quad((x, Y0 - 1.35, z), (0.9, 2.7), "south", t, rot=(0, 60.0 * i + 10, 0), origin=(x, Y0 - 1.35, z))


def log(s, k):
    """종 치는 통나무: 굵은 엔드 막대기(옆으로 누움) + 매단 사슬 둘. 뿌리(0,0,0) = 매단 곳, 통나무는 4블록 아래"""
    s.rod((-3.0, -4.0, 0.0), (1, 0, 0), length=6.0, segs=1, thick=0.95, wave=None, toward=(0, 1, 0), base=False, rag=False)
    for x in (-2.2, 2.2):
        s.chain((x * 0.6, 0.0, 0.0), (x, -3.6, 0.0), links=6, sag=0.05, size=0.22)
    # 통나무 머리에 작은 얼굴 하나(치면 운다)
    s.head(size=1.0, depth=0.95, at=(3.25, -4.0, 0.0), face="bloodshot", rot=(0, 90, 0), near=False, torn=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=0.17, mut2=False, torn=False, rag=False)
    bell(m.state(""), k)
    lg = k.model("g148_log", poses=1, period=20, shrink=0.3, mut2=False, open=False, torn=False, rag=False)
    log(lg.state(""), k)
    sh = k.model("g148_shadow", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for i, dens in enumerate((0.25, 0.42, 0.6, 0.82), start=1):
        t = k.texture(f"shadow{i}", shadow_tex(dens))
        sh.state(f"s{i}").quad((0, 0, 0), (2, 2), "up", t)
    sh.default = "s1"
    k.icon(model="g148", state="")


def sounds(k: kit.Kit):
    k.sample("toll", "tag:bell+low", "하늘의 종소리", pitch=0.5, attenuation=96, n=2)
    k.sample("ring", "kenney_impact/impactbell_heavy_001", "종 몸이 울린다", pitch=0.6, attenuation=64, n=1)
    k.sample("crash", "tag:quake", "종이 내려앉는다", pitch=0.6, attenuation=96, n=2)
    k.sample("crack", "tag:breaking", "테가 떨어져 나간다", pitch=0.7, attenuation=48)


def phrases():
    return {"cause_g148": "내려앉은 종에 깔렸다", "cause_g148_dust": "종에서 흘러내린 얼굴 가루", "meter_g148": "종이 내려온다"}
