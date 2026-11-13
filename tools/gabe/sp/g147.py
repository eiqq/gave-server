"""G147 저울 게이브(4막 33라운드, 방해): 하늘 22블록 높이에 걸린 길이 12블록 대저울. 굵은 엔드 막대기 하나가 저울대이고 그 한가운데를 매단 막대기
하나가 구름 속으로 곧게 올라간다. 한쪽 끝에는 3블록 얼굴이 추로 매달려 내려다보고, 다른 쪽 끝에는 쇠사슬 셋에 걸린 폭 5블록 살 접시가 드리웠다.
접시 밑바닥에도 납작하게 눌린 얼굴이 있다.

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 부품, 몸 자리 = 저울대 가운데 굴대):
  gave:gabe/g147         저울대(가운데 = 굴대, +X 끝 6 에 접시, -X 끝 -6 에 추를 거는 고리). 기울기는 Java 가 몸 roll 로
  gave:gabe/g147_rod     굴대에서 위로 20블록 매단 막대기(기울지 않음) + 구름 덩이
  gave:gabe/g147_weight  추 얼굴(뿌리 = 고리, 막대기 셋을 땋은 줄 아래 얼굴 가운데 -4.5, 아래를 봄). 상태 "" , "gape"(입을 더 벌림)
  gave:gabe/g147_pan     접시(뿌리 = 고리, 쇠사슬 셋 c1..c3 아래 -5 에 살 접시, 밑바닥에 눌린 얼굴). 부위 c1, c2, c3(그 사슬이 끊김)
  gave:gabe/g147_shadow  접시 그늘(위를 보는 점묘 원 + 테두리 선, 지름 2 로 굽고 Java 가 반지름 6 으로)
  gave:gabe/g147_mark    바닥 표시 판(위를 봄, 2x2): 상태 "warn"(추가 떨어질 빨간 원판), "lift"(떠오르는 사람 발밑 흰 고리)
소리는 받은 소리(k.sample). 사체 없음.
"""
import math

import numpy as np

from gabe import faces, kit

BEAM = 6.0
PAN_R, PAN_Y = 2.6, -5.0
W_FACE, W_Y = 3.0, -4.5


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def flesh_tex(seed, base=(198, 116, 120)):
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:32, 0:32].astype(np.float32)
    v = 0.86 + 0.1 * np.sin(xx * 0.6 + np.sin(yy * 0.4) * 1.5) + rng.rand(32, 32) * 0.05
    out = np.zeros((32, 32, 4), np.uint8)
    out[..., :3] = np.clip(np.stack([v * base[0], v * base[1], v * base[2]], axis=2), 0, 255)
    out[..., 3] = 255
    return out


def cloud_tex(n=32, seed=7):
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    v = 0.32 + 0.06 * np.sin(xx * 0.5 + yy * 0.3) + rng.rand(n, n) * 0.05
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(np.stack([v * 120, v * 110, v * 130], axis=2), 0, 255)
    out[..., 3] = 255
    return out


def disc_tex(kind, dens=0.6, n=96):
    """바닥 판: shadow 짙은 점묘 + 테두리 선, warn 빨간 원판(가운데 짙게), lift 흰 고리"""
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r >= 1:
                continue
            if kind == "shadow":
                if r > 0.955:
                    out[y, x] = (226, 216, 200, 255) if (x + y) % 2 == 0 else (0, 0, 0, 0)
                elif bay[y % 4, x % 4] < dens * min(1.0, (1 - r) / 0.25):
                    out[y, x] = (20, 12, 18, 255)
            elif kind == "warn":
                if r > 0.9 or bay[y % 4, x % 4] < 0.55 * (1 - r) + 0.12:
                    out[y, x] = (214, 34, 40, 255)
            else:
                if 0.72 < r < 0.98:
                    out[y, x] = (246, 244, 240, 255)
    return faces._fill_rest(out)


def beam(s, k):
    # 저울대: 굵은 엔드 막대기 하나(가로), 가운데 굴대 덩이, 양 끝 고리
    s.rod((-BEAM - 0.4, 0, 0), (1, 0, 0), length=2 * BEAM + 0.8, segs=2, thick=0.85, wave=None, toward=(0, 1, 0), base=False, rag=False)
    s.box((-0.9, -0.9, -0.9), (0.9, 0.9, 0.9), k.texture("hub", flesh_tex(2, (150, 90, 96))), face=True)
    s.head(size=1.4, depth=0.6, at=(0, 0, 0.95), face="half", near=False, torn=False)
    for x in (-BEAM, BEAM):
        s.box((x - 0.3, -0.9, -0.3), (x + 0.3, -0.35, 0.3), "bone", face=True)


def rod_up(s, k):
    s.rod((0, 0.4, 0), (0, 1, 0), length=20.0, segs=2, thick=0.6, wave=None, toward=(0, 0, 1), base=False, rag=False)
    cl = k.texture("cloud", cloud_tex())
    for (cx, cy, cz, rx, ry, rz) in [(0, 20.5, 0, 4.0, 1.4, 3.6), (1.8, 21.6, 1.2, 3.0, 1.4, 2.6), (-2.2, 21.2, -1.2, 3.1, 1.3, 2.9),
                                     (0.4, 22.6, -0.6, 3.4, 1.5, 3.0)]:
        s.box((cx - rx, cy - ry, cz - rz), (cx + rx, cy + ry, cz + rz), cl)


def weight(s, k, mode):
    # 땋은 줄(막대기 셋), 그 아래 아래를 보는 3블록 얼굴(추)
    for i in range(3):
        a = math.radians(120 * i)
        s.rod((math.cos(a) * 0.2, -0.2, math.sin(a) * 0.2), (0, -1, 0), length=3.2, segs=1, thick=0.22, wave=None,
              toward=(0, 0, 1), base=False, rag=False)
    f = faces.Face(eyes="open", mouth="teeth_ring" if mode == "gape" else "torn", blush="deep", tilt=10.0)
    s.head(size=W_FACE, depth=W_FACE * 0.9, at=(0, W_Y, 0), face=f, rot=(90, 0, 0), near=False, torn=False)
    if mode == "gape":
        s.teeth((0, W_Y - W_FACE * 0.45 - 0.05, 0.2), 0.7, count=10, axis=(0, -1, 0), length=0.4, size=0.14, inward=0.25)


def pan(s, k):
    fl = k.texture("pan", flesh_tex(5))
    # 사슬 셋(끊기면 사라짐): 고리에서 접시 테 세 점으로
    for i in range(3):
        a = math.radians(120 * i + 90)
        rim = (math.cos(a) * (PAN_R - 0.2), PAN_Y + 0.35, math.sin(a) * (PAN_R - 0.2))
        with s.when(f"!on:c{i + 1}"):
            s.chain((0, -0.3, 0), rim, links=7, sag=0.05, size=0.2)
    # 살 접시: 팔각 판 둘(45 도 엇갈림) + 낮은 테
    for a in (0, 45):
        s.box((-PAN_R, PAN_Y - 0.25, -PAN_R * 0.42), (PAN_R, PAN_Y + 0.15, PAN_R * 0.42), fl, rot=(0, a, 0), origin=(0, 0, 0), face=True,
              faces=("up", "down", "east", "west"))
        s.box((-PAN_R * 0.42, PAN_Y - 0.25, -PAN_R), (PAN_R * 0.42, PAN_Y + 0.15, PAN_R), fl, rot=(0, a, 0), origin=(0, 0, 0), face=True,
              faces=("up", "down", "north", "south"))
    for i in range(8):
        a = 45.0 * i
        w = 2 * PAN_R * math.tan(math.pi / 8) + 0.05
        s.box((-w / 2, PAN_Y + 0.1, PAN_R - 0.25), (w / 2, PAN_Y + 0.65, PAN_R), fl, rot=(0, a, 0), origin=(0, 0, 0), face=True,
              faces=("south", "north", "up"))
    # 밑바닥에 납작하게 눌린 얼굴(아래를 봄)
    s.head(size=3.4, depth=0.2, at=(0, PAN_Y - 0.36, 0), face=faces.Face(eyes="half", mouth="dot", blush="deep", tilt=-16.0), rot=(90, 0, 0),
           near=False, torn=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.16, mut2=False, torn=False, rag=False)
    beam(m.state(""), k)
    r = k.model("g147_rod", poses=1, period=20, shrink=0.062, mut2=False, open=False, torn=False, rag=False)
    rod_up(r.state(""), k)
    w = k.model("g147_weight", poses=1, period=20, shrink=0.22, mut2=False, torn=False, rag=False)
    for mode in ("", "gape"):
        weight(w.state(mode), k, mode)
    p = k.model("g147_pan", poses=1, period=20, shrink=0.2, mut2=False, open=False, torn=False, rag=False)
    pan(p.state(""), k)
    sh = k.model("g147_shadow", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    sh.state("").quad((0, 0, 0), (2, 2), "up", k.texture("shadow", disc_tex("shadow")))
    mk = k.model("g147_mark", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    mk.state("warn").quad((0, 0, 0), (2, 2), "up", k.texture("warn", disc_tex("warn")))
    mk.state("lift").quad((0, 0, 0), (2, 2), "up", k.texture("lift", disc_tex("lift")))
    mk.default = "warn"
    k.icon(model="g147_weight", state="gape")


def sounds(k: kit.Kit):
    k.sample("tilt", "tag:metal+creak", "저울 기우는 소리", pitch=0.6, attenuation=64, n=1)
    k.sample("chain", "tag:metal+impact", "사슬이 끊어진다", pitch=0.7, attenuation=48)
    k.sample("slam", "tag:slam", "추가 떨어진다", pitch=0.6, attenuation=96, n=2)
    k.sample("gulp", "tag:eat", "추가 삼킨다", pitch=0.6, attenuation=48)


def phrases():
    return {"cause_g147": "저울 추에 찍혔다", "cause_g147_fall": "저울 접시에서 떨어졌다", "cause_g147_pan": "저울 접시에 실렸다"}
