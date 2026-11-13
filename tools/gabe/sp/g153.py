"""G153 하늘 찢는 게이브(4막 34라운드, 지원, 원거리, 16블록): 하늘 12~28블록 높이에 세로로 찢어진 틈(높이 16, 가장 벌어진 폭 5블록).
틈 속은 이쪽을 들여다보는 _Gave 얼굴들이 빽빽이 겹친 분홍 살빛이다(앞뒤 세 겹, 깊이마다 얼굴 크기가 다름). 틈의 양 가장자리를 2.5블록
얼굴 둘(문지기)이 하나씩 이로 물고 서로 반대쪽으로 당겨 벌리고 있고, 굵은 엔드 막대기 둘이 버팀목으로 틈 사이에 가로질러 끼워져 있다.

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 부품, 몸 자리 = 틈 가운데):
  gave:gabe/g153         틈(폭 5, 높이 16 의 렌즈꼴, 앞 +Z 와 뒤 -Z 양면, 안쪽 얼굴 세 겹 + 찢긴 가장자리). Java 가 폭(x)을 벌어진 몫으로 줄임
  gave:gabe/g153_keeper  문지기 얼굴(2.5, 이빨 고리 입으로 틈 가장자리 살을 물고 있음 + 당기는 뼈 팔 둘)
  gave:gabe/g153_brace   버팀목(굵은 엔드 막대기 가로 5블록 + 양 끝 살에 박힌 뿌리). Java 가 폭을 벌어진 몫으로
  gave:gabe/g153_line    바닥의 긴 그림자 줄(위를 보는 판 2x2: Java 가 늘임)
  gave:gabe/g153_drop    떨어지는 작은 얼굴(얼굴 비)
  gave:gabe/g153_spit    틈 속 얼굴들이 뱉는 덩이
소리는 받은 소리(k.sample). 사체 없음(틈이 닫히며 사라짐).
"""
import math

import numpy as np

from gabe import faces, kit

W, H = 5.0, 16.0


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def lens(u, v):
    """렌즈꼴 틈 안인가: u -1..1(가로), v -1..1(세로). 위아래 끝이 뾰족"""
    return abs(u) <= max(0.0, 1 - v * v) ** 0.9


def crowd_tex(seed, cells, w=64, h=192, dark=0.0, edge=True):
    """틈 속 얼굴 무리: 분홍 살빛 바탕에 _Gave 얼굴을 빽빽이 겹쳐 그림, 렌즈 밖은 투명(컷아웃)"""
    rng = np.random.RandomState(seed)
    out = np.zeros((h, w, 4), np.float32)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    base = np.stack([200 + 20 * np.sin(yy * 0.07 + xx * 0.1), 116 + 14 * np.sin(yy * 0.05), 124 + 10 * np.cos(xx * 0.2)], axis=2)
    out[..., :3] = base
    looks = [faces.Face(eyes="open"), faces.Face(eyes="half", blush="deep"), faces.Face(eyes="bloodshot", mouth="torn"), faces.Face(),
             faces.Face(eyes="open", mouth="teeth_ring", blush="deep")]
    tiles = [faces.far(f).astype(np.float32) for f in looks]
    for _ in range(cells):
        t = tiles[rng.randint(len(tiles))]
        sz = rng.randint(12, 26)
        x0, y0 = rng.randint(-4, w - sz + 4), rng.randint(-4, h - sz + 4)
        idx = (np.arange(sz) * 32 // sz)
        tt = t[idx][:, idx]
        for j in range(sz):
            for i in range(sz):
                x, y = x0 + i, y0 + j
                if 0 <= x < w and 0 <= y < h and tt[j, i, 3] > 0:
                    out[y, x, :3] = tt[j, i, :3]
    out[..., :3] *= (1 - dark)
    for y in range(h):
        for x in range(w):
            u, v = (x + 0.5) / w * 2 - 1, (y + 0.5) / h * 2 - 1
            if not lens(u, v):
                continue
            out[y, x, 3] = 255
            if edge and not lens(u * 1.12, v * 1.03):
                out[y, x, :3] = (96 + 30 * math.sin(y * 0.9), 18, 26)
    return faces._fill_rest(np.clip(out, 0, 255).astype(np.uint8))


def keeper(s, k):
    s.head(size=2.5, depth=2.0, face=faces.Face(eyes="bloodshot", mouth="teeth_ring", blush="deep", tilt=12.0), near=False, torn=False)
    # 물고 있는 가장자리 살 조각(입에서 안쪽으로)
    s.box((-1.6, -0.9, 0.95), (0.2, -0.3, 1.3), "flesh", face=True)
    # 당기는 뼈 팔 둘(얼굴 뒤에서 바깥쪽 아래로 버팀)
    for y in (0.6, -0.6):
        s.rod((0.9, y, -0.4), (1, -0.35 if y < 0 else 0.35, -0.6), length=1.6, segs=1, thick=0.24, wave=None, tex="bone", rag=False)


def brace(s, k):
    s.rod((-W / 2 - 0.3, 0, 0), (1, 0, 0), length=W + 0.6, segs=1, thick=0.8, wave=None, toward=(0, 1, 0), base=False, rag=False)
    for x in (-W / 2, W / 2):
        s.box((x - 0.5, -0.6, -0.6), (x + 0.5, 0.6, 0.6), "flesh", face=True)


def line_tex(n=32, h=160):
    out = np.zeros((h, n, 4), np.uint8)
    bay = _bayer()
    for y in range(h):
        t = y / h
        for x in range(n):
            u = abs((x + 0.5) / n * 2 - 1)
            if u < 0.9 * (1 - t * 0.7) and bay[y % 4, x % 4] < 0.85 - 0.5 * t:
                out[y, x] = (14, 6, 12, 255)
    return faces._fill_rest(out)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.18, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    back = k.texture("crowd_back", crowd_tex(1, 70, dark=0.35, edge=False))
    mid = k.texture("crowd_mid", crowd_tex(2, 40, dark=0.15, edge=False))
    front = k.texture("crowd_front", crowd_tex(3, 26, edge=True))
    for z, t, sc in ((-0.9, back, 0.86), (-0.3, mid, 0.94), (0.3, front, 1.0)):
        s.box((-W / 2 * sc, -H / 2 * sc, z), (W / 2 * sc, H / 2 * sc, z), t, faces=("south",), uv=[0, 0, 16, 16], face=True)
        s.box((-W / 2 * sc, -H / 2 * sc, -z), (W / 2 * sc, H / 2 * sc, -z), t, faces=("north",), uv=[16, 0, 0, 16], face=True)
    kp = k.model("g153_keeper", poses=1, period=20, shrink=0.5, mut2=False, torn=False, rag=False)
    keeper(kp.state(""), k)
    br = k.model("g153_brace", poses=1, period=20, shrink=0.25, mut2=False, open=False, torn=False, rag=False)
    brace(br.state(""), k)
    ln = k.model("g153_line", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    ln.state("").quad((0, 0, 0), (2, 2), "up", k.texture("line", line_tex()))
    dr = k.model("g153_drop", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    dr.state("").head(size=0.9, depth=0.7, face="tears", near=False)
    sp = k.model("g153_spit", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    sp.state("").head(size=1.1, depth=1.0, face=faces.Face(eyes="open", mouth="torn", blush="deep"), near=False)
    k.icon(model="g153", state="")


def sounds(k: kit.Kit):
    k.sample("tear", "tag:breaking", "하늘 찢어지는 소리", pitch=0.45, attenuation=128, n=2, level=-10.0)
    k.sample("spit", "tag:spit", "틈이 뱉는다", pitch=0.7, attenuation=48)
    k.sample("drop", "tag:slime", "얼굴이 떨어진다", pitch=0.8, attenuation=32)
    k.sample("close", "tag:slam", "틈이 닫힌다", pitch=0.55, attenuation=96, n=2)


def phrases():
    return {"cause_g153": "하늘 틈이 뱉은 덩이", "cause_g153_under": "하늘 틈 아래", "cause_g153_drop": "하늘에서 떨어진 얼굴"}
