"""G169 물그림자 게이브(2막 18라운드, 방해, 정예, 8블록): 하늘 14블록에 폭 8블록의 커다란 얼굴이 빗속 연기처럼 흐릿하게 떠 있고(탄이 지나감),
턱 아래로 엔드 막대기 넷이 처마 고드름처럼 곧게 늘어져 땅을 가리킨다. 그 아래 웅덩이 수면에 같은 얼굴이 거꾸로 비치는데, 비친 얼굴만 실눈과 입이
또렷하고 수면 위로 고드름 끝 넷이 거꾸로 솟아 있다. 물에 비친 것이 진짜 몸이다.

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 부품):
  gave:gabe/g169        물그림자(몸 자리 = 수면 가운데): 위를 보는 거꾸로 된 얼굴 판(지름 6) + 물결 테 + 솟은 고드름 끝 넷.
                        상태 "" 고요, "swirl" 소용돌이(물기 1초 전), "bite" 입 벌림(이빨 고리), "gone" 흩어짐(조명탄)
  gave:gabe/g169_sky    하늘 얼굴(아래를 보는 점묘 연기 얼굴 8블록, 일렁임 4자세) + 턱 밑 고드름 막대기 넷(곧게)
소리는 받은 소리(k.sample). 사체 없음(흩어짐).
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

POOL = 6.0
SKY = 8.0


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def _big(face, n=64):
    """먼 얼굴 32 를 n 으로 키움(화소 느낌 그대로)"""
    return np.array(Image.fromarray(faces.far(face)).resize((n, n), Image.NEAREST))


def pool_tex(face, kind):
    """물그림자 판: 둥근 수면(짙은 물빛) 안에 거꾸로 된 얼굴이 비침, 가장자리 물결 고리. kind: calm | swirl | bite | gone"""
    n = 64
    f = _big(face, n).astype(np.float32)
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    bay = _bayer()
    for y in range(n):
        for x in range(n):
            dx, dy = (x - c) / (n / 2), (y - c) / (n / 2)
            r = math.hypot(dx, dy)
            if r > 1:
                continue
            a = math.atan2(dy, dx)
            water = np.array([34, 52, 70], np.float32)
            if kind == "swirl":
                # 소용돌이: 각도와 반지름이 꼬인 밝은 띠
                band = math.sin(a * 3 + r * 14)
                water = water + (60 if band > 0.6 else 0)
            # 얼굴은 수면 안쪽 0.86 까지, 물빛과 섞음(물에 비침)
            if r < 0.86 and kind != "gone":
                fx = int((x - c) / 0.86 + c)
                fy = int((y - c) / 0.86 + c)
                fx, fy = max(0, min(n - 1, fx)), max(0, min(n - 1, fy))
                fc = f[fy, fx, :3]
                k = 0.72 if kind != "swirl" else 0.5
                col = fc * k + water * (1 - k)
            else:
                col = water * (0.8 if kind == "gone" else 1.0)
            # 물결 고리: 가장자리 두 겹
            if 0.9 < r <= 1.0 or (kind != "gone" and abs(r - 0.94) < 0.02):
                col = np.array([150, 176, 196], np.float32)
            if kind == "gone" and bay[y % 4, x % 4] > 0.55:
                continue
            out[y, x, :3] = np.clip(col, 0, 255)
            out[y, x, 3] = 255
    return faces._fill_rest(out)


def smoke_tex(seed):
    """하늘 얼굴: 아래에서 올려다본 연기 얼굴(점묘 컷아웃, 테두리로 갈수록 성김). 씨앗마다 구멍 자리가 달라 일렁인다"""
    n = 64
    f = _big(faces.Face(eyes="half", mouth="dot", blush="pink"), n).astype(np.float32)
    rng = np.random.RandomState(seed)
    noise = rng.rand(n // 4 + 1, n // 4 + 1)
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            dx, dy = abs(x - c) / (n / 2), abs(y - c) / (n / 2)
            r = (dx ** 4 + dy ** 4) ** 0.25
            if r > 1:
                continue
            dens = 0.78 * min(1, (1.02 - r) / 0.35) * (0.7 + 0.3 * noise[y // 4, x // 4])
            if dens <= _bayer()[(y + seed) % 4, (x + seed * 3) % 4]:
                continue
            col = f[y, x, :3] * 0.75 + np.array([150, 150, 162]) * 0.25
            out[y, x, :3] = np.clip(col, 0, 255)
            out[y, x, 3] = 255
    return faces._fill_rest(out)


def pool_body(s, k, kind):
    face = {"calm": faces.Face(flip=True), "swirl": faces.Face(eyes="open", flip=True), "bite": faces.Face(eyes="open", mouth="teeth_ring",
                                                                                                     flip=True),
            "gone": faces.Face(eyes="none", mouth="none", flip=True)}[kind]
    s.front = (0, 1, 0)
    s.quad((0, 0.02, 0), (POOL, POOL), "up", k.texture("pool_" + kind, pool_tex(face, kind)), face=True)
    if kind == "gone":
        return
    # 거꾸로 솟은 고드름 끝 넷(수면 위로 짧게, 하늘 고드름이 비친 것)
    for i, x in enumerate((-1.8, -0.6, 0.6, 1.8)):
        s.rod((x, 0, -1.9), (0, 1, 0.08), length=0.9 + 0.25 * (i % 2), segs=1, thick=0.16, base=False, toward=(0, 0, 1), rag=False)


def sky_body(s, k):
    s.front = (0, -1, 0)
    for f in s.frames(poses=4):
        with f:
            t = k.texture(f"smoke{f.k}", smoke_tex(f.k * 7 + 1))
            dy = 0.12 * math.sin(f.t * 2 * math.pi)
            s.quad((0, dy, 0), (SKY, SKY), "down", t, face=True)
            s.quad((0, dy + 0.01, 0), (SKY, SKY), "up", t, face=True)
    # 턱(앞 +Z 가장자리) 밑 고드름 막대기 넷: 곧게 땅을 가리킴
    for i, x in enumerate((-1.8, -0.6, 0.6, 1.8)):
        s.rod((x, -0.05, SKY / 2 - 0.6), (0, -1, -0.04), length=3.2 + 0.5 * (i % 2), segs=2, thick=0.2, base=False, toward=(0, 0, 1),
              rag=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.45, mut2=False, open=False, torn=False, rag=False)
    for name, kind in (("", "calm"), ("swirl", "swirl"), ("bite", "bite"), ("gone", "gone")):
        pool_body(m.state(name), k, kind)
    sm = k.model("g169_sky", poses=4, period=24, shrink=0.3, mut2=False, open=False, torn=False, rag=False)
    sky_body(sm.state(""), k)
    k.icon(img=Image.fromarray(pool_tex(faces.Face(flip=True), "calm")).resize((64, 64), Image.NEAREST))


def sounds(k: kit.Kit):
    k.sample("ripple", "tag:splash", "물그림자 출렁", pitch=0.7, attenuation=32)
    k.sample("gulp", ["oga_creature80/eat_02", "oga_water40/splash_04"], "물속으로 끌어당김", pitch=0.6, attenuation=32)
    k.sample("laugh", "tag:laugh", "하늘 얼굴이 웃는다", pitch=0.6, attenuation=64, n=2)


def phrases():
    return {"cause_g169": "물그림자에 끌려 들어갔다"}
