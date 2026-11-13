"""G200 하늘 아가리 게이브(4막 34라운드, 목표 압박, 방해, 30블록): 하늘 동쪽과 서쪽 끝에 길이 30블록의 활꼴 턱 두 쪽. 턱마다 아래로 굵은 엔드
막대기 이빨이 곧게 박혀 있고 잇몸 자리에 4블록 얼굴이 박혀 아래를 내려다본다(턱 하나에 판 둘, 판마다 얼굴 하나: 모두 넷). 맞물리면 하늘을
가로지르는 거대한 입. 땅에는 엔드 막대기 둘을 열십자로 묶은 제물 기둥, 꼭대기의 작은 얼굴이 턱을 올려다본다.

모델(블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 부품):
  gave:gabe/g200        턱 판 하나(길이 15, Z 축, 안쪽 = -X): 활꼴 잇몸 + 이빨 일곱(부위 t0..t6: 빠짐) + 얼굴 하나(아래를 봄, 부위 dead: 잡힘).
                        실눈 뜸(깨물기 3초 전)은 키트 오버레이(Java 가 판 부품 look.open)
  gave:gabe/g200_post   제물 기둥(세로 막대기 + 가로 팔 + 꼭대기 얼굴이 위를 봄). 상태 "" 실눈, "tied" 사람이 묶임(눈 뜨고 웃음)
  gave:gabe/g200_band   바닥 깨물 띠(위를 보는 판 2x2: Java 가 폭 8, 길이 100). 상태 "" 옅은 붉은 그늘, "hot" 짙게
  gave:gabe/g200_tooth  떨어지는 이빨. 상태 "" 붉게 달아오른 이빨, "dot" 떨어질 자리 그림자 점
소리는 받은 소리(k.sample).
"""
import math

import numpy as np

from gabe import faces, kit

LEN = 15.0
TEETH_Z = [-6.0, -4.0, -2.0, 0.0, 2.0, 4.0, 6.0]


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def gum_tex(seed):
    rng = np.random.RandomState(seed)
    out = np.zeros((32, 32, 4), np.uint8)
    for y in range(32):
        for x in range(32):
            v = 0.82 + 0.12 * math.sin(x * 0.7 + y * 0.25) + rng.rand() * 0.06
            out[y, x] = (int(min(255, 176 * v)), int(70 * v), int(84 * v), 255)
    return out


def hot_tex():
    out = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            out[y, x] = (255, 120 + (x * 7 + y * 3) % 40, 70, 255)
    return out


def band_tex(dens, n=64):
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            d = abs(x - c) / (n / 2)
            edge = d > 0.92
            if edge or dens * min(1, (1 - d) / 0.25) > _bayer()[y % 4, x % 4]:
                out[y, x] = (150, 18, 22, 255) if not edge else (230, 60, 60, 255)
    return faces._fill_rest(out)


def dot_tex(n=32):
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r < 1 and 0.85 * min(1, (1 - r) / 0.3) > _bayer()[y % 4, x % 4]:
                out[y, x] = (20, 6, 8, 255)
    return faces._fill_rest(out)


def jaw(s, k):
    gum = k.texture("gum", gum_tex(4))
    # 활꼴 잇몸: 가운데가 안쪽(-X)으로 조금 나온 토막 다섯
    for i in range(5):
        z0, z1 = -LEN / 2 + i * 3.0, -LEN / 2 + (i + 1) * 3.0
        zm = (z0 + z1) / 2
        bow = -0.9 * (1 - (zm / (LEN / 2)) ** 2)
        s.box((bow - 1.4, -0.6, z0 - 0.1), (bow + 1.6, 1.0, z1 + 0.1), gum, face=True)
    # 이빨: 안쪽 가장자리에서 아래로 곧게
    for i, z in enumerate(TEETH_Z):
        bow = -0.9 * (1 - (z / (LEN / 2)) ** 2)
        with s.when(f"!on:t{i}"):
            s.rod((bow - 1.0, -0.5, z), (0, -1, 0), length=2.8 + 0.5 * (i % 2), segs=2, thick=0.38, base=False, toward=(0, 0, 1), rag=False)
    # 잇몸 얼굴(4블록, 아래를 봄): 잡히면 찢긴 얼굴
    dead = faces.Face(eyes="bloodshot", mouth="torn", blush="deep", extras=("veins",))
    s.head(size=4.0, depth=1.4, at=(0.6, -0.1, 0), face="smile", rot=(90, 0, 0), near=False, torn=False, show=("!on:dead",))
    s.head(size=4.0, depth=1.4, at=(0.6, -0.1, 0), face=dead, rot=(90, 0, 0), near=False, open=False, torn=False, show=("on:dead",))


def post(s, face):
    s.rod((0, -2.0, 0), (0, 1, 0), length=3.6, segs=2, thick=0.3, base=False, toward=(0, 0, 1), rag=False)
    s.rod((-1.2, 0.8, 0), (1, 0, 0), length=2.4, segs=1, thick=0.24, base=False, toward=(0, 1, 0), rag=False)
    s.head(size=0.75, depth=0.6, at=(0, 2.0, 0), face=face, rot=(-90, 0, 0), near=False, open=False, torn=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.18, mut2=False, torn=False, rag=False)
    st = m.state("")
    st.front = (0, -1, 0)
    jaw(st, k)
    pm = k.model("g200_post", poses=1, period=20, shrink=0.45, mut2=False, open=False, torn=False, rag=False)
    for name, face in (("", "smile"), ("tied", faces.Face(eyes="open", mouth="torn"))):
        pst = pm.state(name)
        pst.front = (0, 1, 0)
        post(pst, face)
    bm = k.model("g200_band", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for name, dens in (("", 0.25), ("hot", 0.75)):
        bst = bm.state(name)
        bst.front = (0, 0, 0)
        bst.quad((0, 0, 0), (2, 2), "up", k.texture("band_" + (name or "warn"), band_tex(dens)))
    tm = k.model("g200_tooth", poses=1, period=20, shrink=0.6, mut2=False, open=False, torn=False, rag=False)
    tst = tm.state("")
    tst.front = (0, 0, 0)
    tst.rod((0, 1.2, 0), (0, -1, 0), length=2.0, segs=1, thick=0.38, base=False, toward=(0, 0, 1), rag=False)
    tst.box((-0.2, -1.1, -0.2), (0.2, -0.75, 0.2), k.texture("hot", hot_tex()), face=True)
    dst = tm.state("dot")
    dst.front = (0, 0, 0)
    dst.quad((0, 0, 0), (2, 2), "up", k.texture("dot", dot_tex()))
    k.icon(model="g200", state="", view="top")


def sounds(k: kit.Kit):
    k.sample("grind", "tag:colossus", "하늘이 이를 가는 소리", pitch=0.6, attenuation=96)
    k.sample("bite", ["oga_metalwood100/metal_slam_01", "oga_stomp/stomp", "kenney_impact/impactpunch_heavy_002"], "하늘이 깨문다", pitch=0.45,
             attenuation=128)
    k.sample("tooth", "tag:falling", "이빨이 빠진다", pitch=0.7, attenuation=48)


def phrases():
    return {
        "cause_g200": "하늘 아가리에 물렸다",
        "cause_g200_tooth": "떨어진 이빨에 맞았다",
        "cause_g200_post": "제물 기둥에서 버텼다",
        "notice_g200_gap": "하늘이 닫혀 온다",
        "notice_g200_post": "누군가 기둥에 서야 벌어진다",
    }
