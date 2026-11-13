"""G96 쇠 먹는 게이브: 1.2 블록 얼굴 뒤로 살 몸통, 등에는 쇠빛 비늘 판(작은 얼굴이 눌려 찍힌 탄피 꼴)이 기왓장처럼 겹쳤다.
짧은 엔드 막대기 다리 넷으로 기고, 꼬리 끝에 막대기 둘이 집게처럼 달렸다. 탄을 먹을수록 비늘이 늘고(부위 s1..s4) 엔진이 몸을 키운다.

- gave:gabe/g96: 상태 ""(기어 옴: 다리와 꼬리 걸음 4자세), "ball"(공처럼 말림: Java 가 pitch 로 굴림). 부위 "s1".."s4"(먹은 단계마다 더 돋는 비늘).
  기본 길이 2 블록을 0.6 으로 줄여 굽는다(엔진이 Look.scale 로 되돌리고 body.scale 로 3.8 블록까지 키움).
- gave:gabe/g96_scale(부품): 사방으로 쏘는 비늘 한 장.
"""
import numpy as np

from gabe import kit

IRON = (104, 110, 122)
IRON_D = (62, 66, 78)
IRON_L = (176, 184, 198)


def iron_tex(n=16, seed=96):
    """쇠빛 비늘: 겹친 반달 줄, 비늘마다 눌려 찍힌 작은 얼굴(실눈 둘, 점 입)"""
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    for y in range(n):
        for x in range(n):
            row = y // 8
            cx = (x + (4 if row % 2 else 0)) % 8
            cy = y % 8
            c = np.array(IRON, float) * (0.82 + 0.05 * cy) + rng.randn() * 3
            if cy == 7 or (cy >= 5 and cx in (0, 7)):
                c = np.array(IRON_D, float)
            if cy == 0:
                c = np.array(IRON_L, float)
            if (cy == 3 and cx in (2, 5)) or (cy == 5 and cx in (3, 4)):
                c = np.array((48, 30, 34), float)
            out[y, x, :3] = np.clip(np.round(c), 0, 255)
            out[y, x, 3] = 255
    return out


def plate_tex(n=16, seed=97):
    """비늘 한 장: 쇠빛 판에 작은 얼굴 하나"""
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    col = np.array(IRON, float)[None, None, :] * (0.9 + 0.1 * np.linspace(1, 0, n))[:, None, None] + rng.randn(n, n, 1) * 3
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    out[0, :, :3] = IRON_L
    out[-1, :, :3] = IRON_D
    out[:, 0, :3] = IRON_D
    out[:, -1, :3] = IRON_D
    for (x, y) in ((4, 6), (5, 6), (10, 6), (11, 6), (7, 10), (8, 10)):
        out[y, x, :3] = (48, 30, 34)
    return out


def plate(s, tex, x, y, z, w=0.36, d=0.42, tilt=22, th=0.06):
    """기왓장 비늘 하나(앞이 들린 얇은 판, 아랫면은 그리지 않음)"""
    s.box((x - w / 2, y, z - d / 2), (x + w / 2, y + th, z + d / 2), tex, uv=[0, 0, 16, 16],
          faces=("up", "north", "south", "east", "west"), rot=(tilt, 0, 0), origin=(x, y, z + d / 2))


def crawl(s, k):
    iron = k.texture("iron", iron_tex())
    pl = k.texture("plate", plate_tex())
    h = s.head(size=1.2, depth=0.6, at=(0, -0.08, 0.75), face="smile")
    # 살 몸통(얼굴보다 넓고 높게: 앞에서도 얼굴 둘레로 살과 쇠 등이 보임) + 볼록한 쇠 등
    s.box((-0.78, -0.5, -1.0), (0.78, 0.72, 0.45), "flesh", uv=[0, 0, 16, 16], face=True)
    s.box((-0.58, 0.72, -0.85), (0.58, 0.92, 0.38), iron, uv=[0, 0, 16, 16], faces=("up", "north", "south", "east", "west"))
    # 비늘: 기본 두 줄, 단계마다 더 돋음
    for z in (0.15, -0.3, -0.75):
        for x in (-0.24, 0.24):
            plate(s, pl, x, 0.92, z)
    with s.when("on:s1"):
        for z in (0.1, -0.35, -0.8):
            for side in (1, -1):
                s.box((side * 0.78 - 0.03, -0.05, z - 0.2), (side * 0.78 + 0.03, 0.62, z + 0.2), pl, uv=[0, 0, 16, 16],
                      faces=("east", "west", "north", "south", "up"), rot=(0, 0, -side * 14), origin=(side * 0.78, 0.62, z))
    with s.when("on:s2"):
        for x in (-0.48, 0, 0.48):
            plate(s, pl, x, 0.98, 0.5, w=0.44, d=0.36, tilt=38)
        plate(s, pl, 0, 1.0, -0.1, w=0.5, d=0.5, tilt=30)
    with s.when("on:s3"):
        for z in (-1.05, -1.3):
            plate(s, pl, 0, 0.35, z, w=0.6, d=0.34, tilt=30)
        for side in (1, -1):
            plate(s, pl, side * 0.45, 0.75, -1.0, w=0.3, d=0.4, tilt=34)
    with s.when("on:s4"):
        for z in (0.2, -0.2, -0.6, -1.0):
            s.box((-0.04, 0.92, z - 0.16), (0.04, 1.4, z + 0.16), pl, uv=[0, 0, 16, 16], faces=("east", "west", "north", "south", "up"),
                  rot=(-18, 0, 0), origin=(0, 0.92, z))
    # 다리 넷(짧은 구조 막대기, 걸음 2자세: 대각선끼리 엇갈림)
    for (x, z, ph) in ((0.72, 0.25, 0), (-0.72, 0.25, 180), (0.72, -0.75, 180), (-0.72, -0.75, 0)):
        side = 1 if x > 0 else -1
        s.rod((x, -0.35, z), (side * 0.55, -1.0, 0.0), length=0.55, segs=1, thick=0.16, base=False, poses=2, rag=False,
              wave=kit.Wave(amp=22, phase=ph), toward=(0, 0, 1))
    # 꼬리(살 막대기)와 끝의 집게 둘
    tail = s.rod((0, -0.05, -0.98), (0, 0.25, -1.0), length=0.8, segs=2, thick=0.24, tex="flesh", base=False, poses=2, rag=False,
                 wave=kit.Wave(amp=(10, 14), lag=60), toward=(1, 0, 0))
    for f in s.frames(2):
        with f:
            tip = np.array(tail.tip(f.k))
            d = np.array(tail.dirs[f.k][-1])
            for sg in (1, -1):
                pd = d + np.array((sg * 0.55, 0.0, 0.0))
                s.rod(tuple(tip - d * 0.05), tuple(pd), length=0.5, segs=1, thick=0.1, base=False, toward=(0, 1, 0))
    return h


def ball(s, k):
    iron = k.texture("iron", iron_tex())
    pl = k.texture("plate", plate_tex())
    # 말린 공: 쇠 비늘로 덮인 둥근 덩어리(계단 셋) + 틈으로 보이는 얼굴과 분홍 배
    s.box((-0.6, -0.6, -0.6), (0.6, 0.6, 0.6), iron, uv=[0, 0, 16, 16])
    s.box((-0.4, -0.78, -0.4), (0.4, 0.78, 0.4), iron, uv=[0, 0, 16, 16], faces=("up", "down", "north", "south", "east", "west"))
    s.box((-0.78, -0.4, -0.4), (0.78, 0.4, 0.4), iron, uv=[0, 0, 16, 16])
    s.box((-0.4, -0.4, -0.78), (0.4, 0.4, 0.5), iron, uv=[0, 0, 16, 16], faces=("up", "down", "north", "east", "west"))
    s.head(size=0.7, depth=0.3, at=(0, 0.0, 0.62), face="smile")
    s.box((-0.5, -0.5, 0.6), (0.5, 0.5, 0.64), "flesh", uv=[0, 0, 16, 16], faces=("south",), face=True)
    with s.when("on:s2"):
        for a in (0, 90, 180, 270):
            s.box((-0.3, 0.78, -0.04), (0.3, 0.98, 0.04), pl, uv=[0, 0, 16, 16], faces=("north", "south", "east", "west", "up"),
                  rot=(a, 0, 0), origin=(0, 0, 0))
    with s.when("on:s4"):
        for a in (45, 135, 225, 315):
            s.box((-0.04, 0.72, -0.3), (0.04, 1.02, 0.3), pl, uv=[0, 0, 16, 16], faces=("north", "south", "east", "west", "up"),
                  rot=(0, 0, a), origin=(0, 0, 0))
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=4, period=20, shrink=0.6)
    crawl(m.state(""), k)
    ball(m.state("ball", poses=1), k)

    # 쏘는 비늘 한 장(얇은 판, 앞 = 날아가는 쪽)
    sc = k.model("g96_scale", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    ss = sc.state("")
    pl = k.texture("plate", plate_tex())
    ss.box((-0.2, -0.03, -0.25), (0.2, 0.03, 0.25), pl, uv=[0, 0, 16, 16])
    ss.front = (0, 0, 0)
    k.icon(model="g96", state="")


def sounds(k: kit.Kit):
    k.sample("munch", "tag:eat", "씹는 소리", pitch=0.85, attenuation=24)
    k.sample("bristle", "tag:metal+sheet", "비늘 곤두섬", pitch=0.8, attenuation=24)
    k.sample("ram", "tag:metal+heavy-bell", "쇠 몸통 박치기", pitch=0.7, attenuation=20)
    k.sample("burst", "tag:metal+spring", "비늘 쏘기", pitch=0.75, attenuation=32)
    k.sample("pinch", "tag:blade", "꼬리 집게", pitch=0.9, attenuation=16)


def phrases():
    return {"cause_g96": "쇠 먹는 게이브에 받혔다", "cause_g96_scale": "비늘에 꿰였다"}
