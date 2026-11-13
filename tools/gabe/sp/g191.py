"""G191 포도송이 게이브(4막 32라운드, 튼튼이 + 목표 압박): 높이 3.5블록의 포도송이.

굵은 엔드 막대기 둘을 A자 꼴로 맞세운 받침대(뒤뚱 2자세)가 걷고, 그 꼭지에 검보라 _Gave 얼굴 알 서른이 송이로 매달려 바닥에 끌린다
(송이는 부품: Java 가 꼭지를 축으로 걸음에 맞춰 흔든다). 알 얼굴은 실눈, 'swell'(죽기 직전)이면 모두 부풀어 핏발 선 눈을 뜬다.
검보라 얼굴은 _Gave 얼굴 그림의 살 화소만 검보라로 바꾼 것(부류 비트 그대로).

- gave:gabe/g191(shrink 0.5): 몸 가운데(0, 0, 0) = 엔진 pos(바닥 + 1.5, Java HOVER). 발 y -1.5, 꼭지 (0, APEX_Y, 0).
  상태 ""(걷기, 2자세), "sit"(다리 하나가 꺾여 주저앉음: 꼭지가 낮아짐). 부위 cut_l, cut_r(그 다리 꺾임).
- gave:gabe/g191_bunch(shrink 0.4): 송이(꼭지 이음점이 (0, 0, 0), 아래로 3.2). 상태 "", "swell".
- gave:gabe/g191_egg: 던지는 알 하나. gave:gabe/g191_pool: 즙 못(바닥 판 3 x 3, Java 가 반지름에 맞춰 키움, 끓음 2자세). 상태 "", "drop"(방울 자국).
소리 drip(뚝뚝 떨어지는 즙, 24), throw(알 던짐, 24), burst(터짐, 48), splat(알이 깨짐, 16).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.5
FLOOR = -1.5
APEX_Y = 1.9
LEG_X = 1.3
EGG_FACES = (faces.Face(), faces.Face(eyes="half"), faces.Face(tilt=-14.0), faces.Face(tilt=14.0), faces.Face(extras=("tears",)))
SWELL_FACE = faces.Face(eyes="bloodshot", mouth="torn", blush="deep")
# 송이 줄: (y, 고리 반지름, 알 수, 알 크기) 꼭지에서 아래로
ROWS = ((-0.45, 0.5, 5, 0.6), (-0.95, 0.82, 7, 0.62), (-1.5, 0.8, 7, 0.62), (-2.05, 0.6, 6, 0.6), (-2.55, 0.36, 4, 0.56), (-2.98, 0.0, 1, 0.52))


def purple(arr):
    """살 화소(부류 0)를 검보라로, 나머지(눈, 입, 홍조)는 어둡게. 부류 비트는 그대로"""
    a = np.array(arr, np.uint8, copy=True)
    cls = a[..., 2] & 7
    rgb = a[..., :3].astype(np.float32)
    lum = rgb.mean(axis=2)
    out = rgb * np.array([0.55, 0.3, 0.6], np.float32)
    skin = cls == 0
    out[skin, 0] = lum[skin] * 0.5
    out[skin, 1] = lum[skin] * 0.2
    out[skin, 2] = lum[skin] * 0.58
    a[..., :3] = np.clip(out, 0, 255).astype(np.uint8)
    a[..., 2] = (a[..., 2] & 0xF8) | cls
    return a


class Tex:
    def __init__(self, k):
        self.front = [k.texture(f"egg{i}", purple(faces.far(f)), classes=True) for i, f in enumerate(EGG_FACES)]
        self.swell = k.texture("egg_swell", purple(faces.far(SWELL_FACE)), classes=True)
        self.side = k.texture("egg_side", purple(faces.skin_side("left")), classes=True)
        self.top = k.texture("egg_top", purple(faces.skin_side("top")), classes=True)


def egg(s, t, at, size, front, rot=None):
    c = np.array(at, float)
    h = size * 0.5
    lo, hi = tuple(c - (h, h * 1.1, h * 0.8)), tuple(c + (h, h * 1.1, h * 0.8))
    kw = dict(rot=rot, origin=tuple(c)) if rot else {}
    s.box(lo, hi, {"south": front}, faces=("south",), uv=[0, 0, 16, 16], face=True, **kw)
    s.box(lo, hi, {"north": t.side, "east": t.side, "west": t.side, "up": t.top, "down": t.top}, faces=("north", "east", "west", "up", "down"),
          uv=[0, 0, 16, 16], face=True, **kw)


def stick(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    up = abs(d[1]) > 0.97 * L
    s.rod(tuple(a), tuple(d), length=L, segs=1, thick=thick, base=False, rag=False, tex=tex, toward=(0, 0, 1) if up else (0, 1, 0))


def frame(s, sit=False):
    """A자 받침대: 다리 둘(부위 cut 이면 꺾인 그루터기), 꼭지 매듭"""
    apex = (0, APEX_Y - (1.1 if sit else 0), 0)
    s.box((apex[0] - 0.3, apex[1] - 0.22, -0.3), (apex[0] + 0.3, apex[1] + 0.3, 0.3), "flesh", face=True)
    s.box((-0.12, apex[1] + 0.3, -0.12), (0.12, apex[1] + 0.62, 0.12), "wetskin", face=True)
    for side, part in ((1, "cut_l"), (-1, "cut_r")):
        foot = (side * (LEG_X + (0.5 if sit else 0)), FLOOR + 0.05, 0.0)
        if sit:
            stick(s, foot, apex, 0.24)
            continue
        with s.when(f"!on:{part}"):
            for fr in s.frames(poses=2):
                with fr:
                    up = 0.28 if (fr.k == 0) == (side > 0) else 0.0
                    stick(s, (foot[0], foot[1] + up, foot[2] + (0.12 if up else 0)), apex, 0.24)
        with s.when(f"on:{part}"):
            mid = (side * LEG_X * 0.55, APEX_Y * 0.45, 0.0)
            stick(s, apex, mid, 0.24)
            stick(s, mid, (side * (LEG_X + 0.9), FLOOR + 0.1, 0.4), 0.22)


def bunch(s, t, swell):
    rng = np.random.RandomState(191)
    s.box((-0.12, -0.35, -0.12), (0.12, 0.05, 0.12), "wetskin", face=True)   # 줄기
    i = 0
    for (y, r, n, size) in ROWS:
        for j in range(n):
            a = (j + 0.5 * (i % 2)) / n * 360.0 + rng.uniform(-12, 12)
            ra = math.radians(a)
            at = (math.sin(ra) * r, y + rng.uniform(-0.08, 0.08), math.cos(ra) * r)
            sz = size * (1.22 if swell else 1.0) * rng.uniform(0.92, 1.06)
            front = t.swell if swell else t.front[i % len(t.front)]
            tilt = 25 if y < -2.3 else 8
            egg(s, t, at, sz, front, rot=(tilt, a, 0))
            i += 1
    s.front = (0, 0, 1)


def pool_tex(seed, n=64):
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    ang = np.arctan2(yy - c, xx - c)
    r = np.hypot(xx - c, yy - c) / (n / 2) * (1 + 0.07 * np.sin(ang * 5 + seed))
    v = 0.55 + 0.25 * np.sin(xx * 0.4 + seed) * np.sin(yy * 0.33) + rng.rand(n, n) * 0.12
    out[..., 0] = np.clip(70 * v + 20, 0, 255)
    out[..., 1] = np.clip(18 * v + 4, 0, 255)
    out[..., 2] = np.clip(88 * v + 28, 0, 255).astype(np.uint8) & 0xF8
    bay = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32
    edge = np.clip((1 - r) / 0.18, 0, 1)
    keep = bay[(yy.astype(int)) % 4, (xx.astype(int)) % 4] < edge
    out[..., 3] = np.where(keep, 255, 0)
    # 끓는 거품 고리(밝은 보라)
    for _ in range(9):
        bx, by = rng.uniform(10, n - 10, 2)
        br = rng.uniform(2.0, 4.5)
        d = np.hypot(xx - bx, yy - by)
        ring = (np.abs(d - br) < 0.9) & keep
        out[ring, 0] = 150
        out[ring, 1] = 70
        out[ring, 2] = 168
    return faces._fill_rest(out)


def build(k: kit.Kit):
    t = Tex(k)
    m = k.model(poses=2, period=20, shrink=S, mut2=False, open=False, torn=False, rag=False)
    frame(m.state(""))
    frame(m.state("sit"), sit=True)
    b = k.model("g191_bunch", poses=1, period=20, shrink=0.4, mut2=False, open=False, torn=False, rag=False)
    bunch(b.state(""), t, False)
    bunch(b.state("swell"), t, True)
    e = k.model("g191_egg", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    es = e.state("")
    egg(es, t, (0, 0, 0), 0.55, t.front[0])
    es.box((-0.05, 0.3, -0.05), (0.05, 0.5, 0.05), "wetskin", face=True)
    p = k.model("g191_pool", poses=2, period=20, mut2=False, open=False, torn=False, rag=False)
    ps = p.state("")
    tex = [k.texture(f"pool{i}", pool_tex(191 + i)) for i in range(2)]
    for fr in ps.frames(poses=2):
        with fr:
            ps.quad((0, 0, 0), (3.0, 3.0), "up", tex[fr.k])
    ps.front = (0, 0, 0)
    dr = p.state("drop")
    dr.quad((0, 0, 0), (3.0, 3.0), "up", tex[0])
    dr.front = (0, 0, 0)
    k.icon(model="g191_bunch", state="")


def sounds(k: kit.Kit):
    k.sample("drip", ["lrsf_horror/bloop_drip_blood_drip_00", "lrsf_horror/bloop_drip_blood_drip_01", "lrsf_horror/bloop_drip_blood_drip_04"],
             "뚝뚝 떨어지는 즙", pitch=0.75, attenuation=24)
    k.sample("throw", ["oga_creature80b/slime_02", "oga_creature80b/slime_03"], "알을 떼어 던짐", pitch=0.8, attenuation=24, n=2)
    k.sample("splat", ["oga_sfx100/splash_01", "oga_sfx100/splash_02"], "알이 깨짐", pitch=0.9, attenuation=16, n=2)
    k.sample("burst", ["oga_water40/splash_04", "oga_creature80b/slime_02", "kenney_impact/impactsoft_heavy_001"], "송이가 터짐", pitch=0.6,
             attenuation=48, level=-9)


def phrases():
    return {"cause_g191": "포도알에 맞아 독이 올랐다", "cause_g191_burst": "터진 포도송이에 휩쓸렸다", "cause_g191_pool": "보랏빛 즙 못에 빠졌다"}
