"""G76 머리 돋는 게이브: 1.4 블록 둥근 살 몸통 위에 짧은 엔드 막대기 목이 왕관처럼 빙 둘러 솟고, 목마다 0.55 블록 _Gave 얼굴.
몸통 앞면 아래에 작은 진짜 얼굴 하나가 반쯤 묻혀 있다. 짧은 막대기 다리 넷.

- gave:gabe/g76: 상태 "" 하나. 목 자리 1..9(빙 둘러, 1 이 앞, 반시계). 부위
  n<i>(머리 있음, 짧은 목), long<i>(그 머리가 목을 2.3 블록으로 늘여 덮침), cut<i>(머리가 떨어진 목 그루터기), burnt<i>(지진 목).
  자리 식은 Java G76.slot* 와 같게.
- gave:gabe/g76_drop(부품): 떨어진 머리(땅에서 웃다 꺼짐).
"""
import math

import numpy as np

from gabe import kit

SLOTS = 9
RING_R, RING_Y = 0.5, 0.62
SHORT_LEN, SHORT_TILT = 0.75, 40
LONG_LEN, LONG_TILT = 2.3, 80
HEAD = 0.55


def slot(i, long=False):
    """자리 i(0..8): (뿌리, 목 방향, 머리 가운데, 바깥 각도 도)"""
    a = 360.0 * i / SLOTS
    r = (math.sin(math.radians(a)), 0.0, math.cos(math.radians(a)))
    root = (r[0] * RING_R, RING_Y, r[2] * RING_R)
    tilt = math.radians(LONG_TILT if long else SHORT_TILT)
    d = (r[0] * math.sin(tilt), math.cos(tilt), r[2] * math.sin(tilt))
    ln = LONG_LEN if long else SHORT_LEN
    head = tuple(root[j] + d[j] * (ln + HEAD * 0.45) for j in range(3))
    return root, d, head, a


def burnt_tex(n=16, seed=76):
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    c = np.array((44, 30, 28), float)[None, None, :] + rng.randn(n, n, 1) * 8
    out[..., :3] = np.clip(np.round(c), 0, 255)
    ember = rng.rand(n, n) < 0.08
    out[ember, :3] = (196, 84, 40)
    out[..., 3] = 255
    return out


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.42, mut2=False, torn=False)
    s = m.state("")
    burnt = k.texture("burnt", burnt_tex())
    # 둥근 살 몸통(1.4)
    s.box((-0.7, -0.55, -0.7), (0.7, 0.55, 0.7), "flesh", uv=[0, 0, 16, 16], face=True)
    s.box((-0.55, -0.7, -0.55), (0.55, 0.72, 0.55), "flesh", uv=[0, 0, 16, 16], faces=("up", "down"), face=True)
    # 반쯤 묻힌 진짜 얼굴(몸통 앞 아래)
    s.head(size=0.5, depth=0.2, at=(0, -0.2, 0.66), face="half")
    # 짧은 다리 넷
    for x, z in ((0.45, 0.4), (-0.45, 0.4), (0.45, -0.4), (-0.45, -0.4)):
        s.rod((x, -0.6, z), (x * 0.5, -1.0, z * 0.5), length=0.32, segs=1, thick=0.16, base=False)
    for i in range(SLOTS):
        n = i + 1
        for long in (False, True):
            root, d, head, a = slot(i, long)
            cond = (f"on:n{n}", f"on:long{n}") if long else (f"on:n{n}", f"!on:long{n}")
            with s.when(*cond):
                s.rod(root, d, length=LONG_LEN if long else SHORT_LEN, segs=1, thick=0.12, base=False)
                # 덮치는 머리는 눈을 뜨고(너를 찍음), 짧은 목의 머리는 실눈
                face = "open" if long else ("smile" if i % 2 == 0 else "smile_a")
                s.head(size=HEAD, depth=0.42, at=head, face=face, near=False, open=False, rot=(0, a, 0))
        root, d, _, _ = slot(i)
        with s.when(f"on:cut{n}"):
            s.rod(root, d, length=0.28, segs=1, thick=0.16, base=False, tex="flesh")
        with s.when(f"on:burnt{n}"):
            s.rod(root, d, length=0.22, segs=1, thick=0.17, base=False, tex=burnt)
    s.front = (0, 0, 1)

    dm = k.model("g76_drop", poses=1, period=20, mut2=False, torn=False, rag=False)
    ds = dm.state("")
    ds.head(size=HEAD, depth=0.42, at=(0, 0, 0), face="smile", near=False)
    ds.rod((0, -0.1, -0.21), (0, -0.3, -1), length=0.2, segs=1, thick=0.12, base=False)
    # 아이콘은 머리 다섯이 돋은 모습(기본 겉모습에는 머리 부위가 꺼져 있음)
    im, _ = k.render(m, "", 0, look=kit.LookState(on=("n1", "n3", "n5", "n6", "n8")), view="front", size=64)
    k.icon(im)


def sounds(k: kit.Kit):
    k.sample("snarl", "tag:snarl", "여러 입 으르렁", pitch=0.9, attenuation=24)
    k.sample("bite", "tag:flesh+impact", "덥석", pitch=1.2, attenuation=16)
    k.sample("pop", "tag:plop", "뽁", pitch=1.1, attenuation=20)
    k.sample("sear", "tag:fire", "목 지짐", pitch=1.0, attenuation=20)


def phrases():
    return {"cause_g76": "돋은 머리들에 물렸다"}
