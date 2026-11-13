"""G128 솟는 목 게이브(3막 27라운드, 원거리, 정예, 12블록): 땅에서 곧게 솟은 굵기 2블록, 높이 12블록의 목 기둥. 얼굴 가죽 고리를 층층이 쌓은
꼴이고, 마디(4블록)마다 위 끝에 엔드 막대기 넷이 테처럼 가로로 꿰여 있다(우물 정 자). 꼭대기에서 기역 자로 꺾여 3블록 얼굴이 아래를 내려다보고,
턱 밑에 나팔처럼 벌어진 살 깔때기가 달렸다. 솟기 전에는 땅에 막대기 끝 넷만 마른 덤불처럼 나와 있다.

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 부품, 몸 자리 = 목 뿌리(땅)):
  gave:gabe/g128_seg    목 마디 하나(뿌리 y 0, 높이 4, 가운데 축). 부위 broken(테가 부서져 막대기 넷이 사라지고 꺾인 살이 드러남)
  gave:gabe/g128        꼭대기(뿌리 = 꺾이는 곳: 앞 +Z 로 목이 꺾여 나가 얼굴이 아래를 봄, 턱 밑 나팔 깔때기). 상태 "" 실눈, "aim" 반쯤 뜬 눈,
                        "horn" 이빨 고리 입을 벌리고 깔때기를 폄
  gave:gabe/g128_tips   솟기 전 땅에 나온 막대기 끝 넷과 흙 둔덕
  gave:gabe/g128_aim    조준선(분홍 가는 줄, +Z 로 길이 1: Java 가 늘임, 엇갈린 판 둘 양면)
  gave:gabe/g128_shell  얼굴 포탄
소리는 받은 소리(k.sample). 사체 없음(무너져 땅속으로).
"""
import math

import numpy as np

from gabe import faces, kit

SEG_H, NECK_R = 4.0, 1.0


def ring_tex(seed):
    """얼굴 가죽 고리: 작은 얼굴을 위아래로 눌러 늘어놓은 살 띠, 위아래 이음 골"""
    rng = np.random.RandomState(seed)
    out = np.zeros((32, 32, 4), np.float32)
    looks = [faces.Face(), faces.Face(eyes="half"), faces.Face(tilt=-14.0), faces.Face(eyes="squint", blush="deep")]
    for j in range(2):
        f = faces.far(looks[(seed + j) % 4]).astype(np.float32)
        squeezed = f[::2]  # 32 x 16 → 위아래로 눌림
        out[j * 16:(j + 1) * 16, :, :3] = squeezed[:, :, :3] * 0.92
    out[(np.arange(32) % 16) < 1, :, :3] *= 0.45
    out[..., :3] += rng.randn(32, 32, 1) * 4
    out[..., 3] = 255
    return np.clip(out, 0, 255).astype(np.uint8)


def raw_tex():
    """꺾인 마디에 드러난 날살"""
    t = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            v = 0.8 + 0.2 * math.sin(x * 1.3 + y * 0.7)
            t[y, x] = (int(170 * v), int(40 * v), int(52 * v), 255)
    return t


def dirt_tex():
    rng = np.random.RandomState(4)
    t = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            v = 0.75 + rng.rand() * 0.25
            t[y, x] = (int(92 * v), int(70 * v), int(52 * v), 255)
    return t


def aim_tex():
    out = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            if 5 <= x <= 10:
                c = (255, 140, 190) if 6 <= x <= 9 else (255, 90, 150)
                out[y, x] = (*c, 255)
    return faces._fill_rest(out)


def octa(s, y0, y1, r, tex, faces_=("south", "north"), cx=0.0, cz=0.0, t=0.3):
    """팔각 띠: 판 여덟을 제 자리에 놓고 제 가운데로 돌림(가운데 cx, cz 둘레)"""
    w = 2 * r * math.tan(math.pi / 8) + 0.04
    ym = (y0 + y1) / 2
    for i in range(8):
        a = 45.0 * i + 22.5
        c = (cx + math.sin(math.radians(a)) * (r - t / 2), ym, cz + math.cos(math.radians(a)) * (r - t / 2))
        s.box((c[0] - w / 2, y0, c[2] - t / 2), (c[0] + w / 2, y1, c[2] + t / 2), tex, faces=faces_, rot=(0, a, 0), origin=c, face=True)


def seg(s, k):
    rings = [k.texture(f"ring{i}", ring_tex(i)) for i in range(2)]
    raw = k.texture("raw", raw_tex())
    octa(s, 0.0, 2.05, NECK_R, rings[0])
    octa(s, 1.95, 3.6, NECK_R * 0.96, rings[1])
    # 위 끝 테: 막대기 넷을 우물 정 자로 가로 꿰기(부서지면 사라지고 날살 고리)
    with s.when("!on:broken"):
        for (x, z, ax) in ((0.55, 0, "z"), (-0.55, 0, "z"), (0, 0.55, "x"), (0, -0.55, "x")):
            d = (0, 0, 1) if ax == "z" else (1, 0, 0)
            root = (x - d[0] * 1.9, 3.75, z - d[2] * 1.9)
            s.rod(root, d, length=3.8, segs=1, thick=0.28, wave=None, toward=(0, 1, 0), base=False, rag=False)
        octa(s, 3.55, 4.0, NECK_R * 1.08, "bone", faces_=("south", "up"))
    with s.when("on:broken"):
        octa(s, 3.55, 4.0, NECK_R * 0.9, raw, faces_=("south", "up"))


def top(s, k, mode):
    rings = k.texture("ring0", ring_tex(0))
    # 꺾이는 목(뿌리에서 앞으로 2.2)
    s.box((-NECK_R * 0.9, -0.8, -NECK_R * 0.9), (NECK_R * 0.9, 1.0, 2.4), rings, faces=("north", "east", "west", "up", "down"), face=True)
    # 아래를 보는 얼굴(가운데 = 앞 2.6, 아래 -0.3)
    if mode == "aim":
        f = faces.Face(eyes="half", mouth="dot", blush="deep", tilt=-18.0)
    elif mode == "horn":
        f = faces.Face(eyes="open", mouth="teeth_ring", blush="deep")
    else:
        f = faces.Face(eyes="squint", blush="deep")
    s.head(size=3.0, depth=2.2, at=(0, -0.3, 2.9), face=f, rot=(90, 0, 0), near=False, torn=False)
    # 턱 밑 나팔 깔때기: 얼굴 아래로 벌어지는 살 고리 셋(깔때기 끝 = 포구)
    flesh = k.part_tex("flesh")
    spread = 1.35 if mode == "horn" else 1.0
    for i, (dy, r) in enumerate(((-1.75, 0.55), (-2.35, 0.8), (-2.85, 1.12))):
        rr = r * (spread if i == 2 else 1.0)
        octa(s, -0.3 + dy - 0.35, -0.3 + dy + 0.35, rr, flesh, faces_=("south", "north"), cx=0.0, cz=2.9, t=0.22)
    if mode == "horn":
        s.teeth((0, -3.25, 2.9), 1.0, count=12, axis=(0, -1, 0), length=0.3, size=0.12, inward=0.25)


def build(k: kit.Kit):
    sm = k.model("g128_seg", poses=1, period=20, shrink=0.35, mut2=False, open=False, torn=False, rag=False)
    seg(sm.state(""), k)
    m = k.model(poses=1, period=20, shrink=0.32, mut2=False, torn=False, rag=False)
    for mode in ("", "aim", "horn"):
        st = m.state(mode)
        top(st, k, mode)
    tp = k.model("g128_tips", poses=1, period=20, shrink=0.5, mut2=False, open=False, torn=False, rag=False)
    t = tp.state("")
    for (x, z, dx, dz) in ((0.5, 0.3, 0.3, 0.2), (-0.4, 0.5, -0.25, 0.3), (0.2, -0.6, 0.15, -0.3), (-0.6, -0.3, -0.3, -0.2)):
        t.rod((x, 0, z), (dx, 1, dz), length=0.9, segs=1, thick=0.16, wave=None, toward=(0, 0, 1), base=False, rag=False)
    dt = k.texture("dirt", dirt_tex())
    t.box((-1.2, 0, -1.2), (1.2, 0.18, 1.2), dt, faces=("up", "north", "south", "east", "west"))
    am = k.model("g128_aim", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    at = k.texture("aim", aim_tex())
    a = am.state("")
    a.box((-0.06, 0, 0), (0.06, 0, 1), at, faces=("up", "down"), uv=[0, 0, 16, 16])
    a.box((0, -0.06, 0), (0, 0.06, 1), at, faces=("east", "west"), uv=[0, 0, 16, 16])
    sh = k.model("g128_shell", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    sh.state("").head(size=1.3, depth=1.1, face=faces.Face(eyes="open", mouth="teeth_ring", blush="deep"), near=False)
    k.icon(model="g128", state="horn")


def sounds(k: kit.Kit):
    k.sample("horn", "tag:horn", "땅속에서 솟은 나팔", pitch=0.6, attenuation=128, n=2)
    k.sample("beep", "tag:beep", "조준음", pitch=1.1, attenuation=48)
    k.sample("fire", "tag:cannon", "얼굴 포", pitch=0.7, attenuation=96)
    k.sample("rumble", "tag:rumble", "땅이 불룩거린다", pitch=0.7, attenuation=64)
    k.sample("snap", "tag:breaking", "마디가 꺾인다", pitch=0.6, attenuation=48)


def phrases():
    return {"cause_g128": "솟는 목의 얼굴 포", "cause_g128_butt": "솟는 목이 들이받았다", "cause_g128_rise": "발밑에서 목이 솟았다"}
