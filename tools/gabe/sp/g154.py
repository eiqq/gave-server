"""G154 여덟 머리 게이브(4막 34라운드, 정예, 튼튼이): 땅 밑에 하나의 몸이 있고(보이지 않음) 땅을 가르며 솟는 머리가 여덟이다. 머리마다 폭 3블록
얼굴에 살 기둥 목이 붙어 땅 위 6블록까지 솟고, 정수리에 엔드 막대기 뿔 둘. 여덟 얼굴의 표정이 조금씩 다르다(Java 가 머리마다 팔레트: 홍조 짙기,
탈색, 금빛). 머리가 들어간 자리에는 갈라진 흙 둔덕이 남는다.

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 머리 부품 여덟, 몸 자리 = 트럭 가운데 땅):
  gave:gabe/g154   머리 하나(뿌리 y 0 = 땅, 목 기둥 6블록 + 얼굴 + 뿔 둘). 상태
                   ""      솟아 노려봄(실눈)
                   "rear"  목을 젖히며 뿔이 섬(눈 뜸, 뿔이 곧추섬)
                   "bite"  내리꽂아 묾(이빨 고리 입)
                   "mound" 땅속: 갈라진 흙 둔덕과 그 틈으로 보이는 뿔 끝
소리는 받은 소리(k.sample). 사체 없음(머리들이 땅속으로 무너짐).
"""
import math

import numpy as np

from gabe import faces, kit

NECK_H, NECK_R, FACE = 6.0, 0.85, 3.0


def ring_tex(seed):
    """살 기둥 목: 작은 얼굴을 위아래로 눌러 쌓은 살빛 고리"""
    rng = np.random.RandomState(seed)
    out = np.zeros((32, 32, 4), np.float32)
    looks = [faces.Face(eyes="half"), faces.Face(blush="deep"), faces.Face(tilt=12.0), faces.Face(eyes="open")]
    for j in range(2):
        f = faces.far(looks[(seed + j) % 4]).astype(np.float32)
        out[j * 16:(j + 1) * 16, :, :3] = f[::2, :, :3] * np.array([0.95, 0.82, 0.84])
    out[(np.arange(32) % 16) < 1, :, :3] *= 0.5
    out[..., :3] += rng.randn(32, 32, 1) * 4
    out[..., 3] = 255
    return np.clip(out, 0, 255).astype(np.uint8)


def dirt_tex(seed):
    """갈라진 흙: 짙은 흙빛에 검은 금, 금 사이로 분홍 살"""
    rng = np.random.RandomState(seed)
    t = np.zeros((32, 32, 4), np.float32)
    for y in range(32):
        for x in range(32):
            v = 0.7 + rng.rand() * 0.3
            t[y, x] = (82 * v, 62 * v, 46 * v, 255)
    for _ in range(4):
        x, y = rng.randint(4, 28), rng.randint(4, 28)
        for _ in range(16):
            x = int(np.clip(x + rng.randint(-1, 2), 0, 31))
            y = int(np.clip(y + rng.randint(-1, 2), 0, 31))
            t[y, x, :3] = (20, 12, 10)
            if rng.rand() < 0.3:
                t[y, x, :3] = (180, 80, 90)
    return np.clip(t, 0, 255).astype(np.uint8)


def octa(s, y0, y1, r, tex, faces_=("south", "north")):
    w = 2 * r * math.tan(math.pi / 8) + 0.04
    ym = (y0 + y1) / 2
    for i in range(8):
        a = 45.0 * i + 22.5
        c = (math.sin(math.radians(a)) * (r - 0.15), ym, math.cos(math.radians(a)) * (r - 0.15))
        s.box((c[0] - w / 2, y0, c[2] - 0.15), (c[0] + w / 2, y1, c[2] + 0.15), tex, faces=faces_, rot=(0, a, 0), origin=c, face=True)


def head(s, k, mode):
    if mode == "mound":
        dt = k.texture("dirt", dirt_tex(1))
        for a, sc in ((0, 1.0), (45, 0.8)):
            s.box((-1.9 * sc, 0, -1.9 * sc), (1.9 * sc, 0.55 + 0.25 * sc, 1.9 * sc), dt, rot=(0, a, 0), origin=(0, 0, 0),
                  faces=("up", "north", "south", "east", "west"))
        for side in (1, -1):
            s.rod((side * 0.5, 0.4, -0.2), (side * 0.3, 1, -0.4), length=0.8, segs=1, thick=0.18, wave=None, toward=(0, 0, 1), base=False, rag=False)
        return
    rings = [k.texture(f"neck{i}", ring_tex(i)) for i in range(2)]
    for i in range(3):
        octa(s, i * 2.0, i * 2.0 + 2.05, NECK_R * (1.12 - i * 0.06), rings[i % 2])
    # 땅을 가르고 나온 흙 테
    dt = k.texture("dirt", dirt_tex(1))
    octa(s, 0, 0.5, NECK_R * 1.7, dt, faces_=("south", "up"))
    if mode == "rear":
        f = faces.Face(eyes="open", mouth="torn", blush="deep")
    elif mode == "bite":
        f = faces.Face(eyes="bloodshot", mouth="teeth_ring", blush="deep")
    else:
        f = faces.Face(eyes="squint", mouth="dot", blush="pink", tilt=-8.0)
    s.head(size=FACE, depth=FACE * 0.85, at=(0, NECK_H + FACE / 2 - 0.3, 0.35), face=f, near=False, torn=False)
    if mode == "bite":
        s.teeth((0, NECK_H + FACE / 2 - 1.1, 0.35 + FACE * 0.43), 0.6, count=10, axis=(0, 0, 1), length=0.3, size=0.1, inward=0.25)
    # 정수리 뿔 둘(가만히 있는 구조 막대기: rear 에서 곧추섬)
    up = 1.0 if mode == "rear" else 0.55
    for side in (1, -1):
        s.rod((side * 0.75, NECK_H + FACE - 0.4, -0.2), (side * 0.35, up, -0.6 if mode != "rear" else -0.25), length=2.2, segs=2, thick=0.26,
              wave=None, toward=(0, 0, 1), base=True, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.15, mut2=False, torn=False, rag=False)
    for mode in ("", "rear", "bite", "mound"):
        head(m.state(mode), k, mode)
    k.icon(model="g154", state="rear")


def sounds(k: kit.Kit):
    k.sample("quake", "tag:quake", "땅울림", pitch=0.75, attenuation=64)
    k.sample("roar", "tag:roar", "포효", pitch=0.65, attenuation=64)
    k.sample("bite", "tag:eat", "물어 끌고 들어간다", pitch=0.6, attenuation=32)


def phrases():
    return {"cause_g154": "여덟 머리에 물렸다", "cause_g154_rise": "땅을 가르고 솟은 머리"}
