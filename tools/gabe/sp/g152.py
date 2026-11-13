"""G152 하늘 시계추 게이브(4막 34라운드, 돌격, 방해, 26블록): 하늘 32블록 높이의 한 점(흙먼지와 어둠에 가려 매단 곳은 안 보임)에서 굵은 엔드
막대기 둘이 나란히 내려와 한 줄기 추대가 되고, 그 끝에 5블록 _Gave 얼굴이 추로 달려 있다. 얼굴은 늘 지나가는 쪽을 본다(Java 가 끝에서 돌림).
턱 밑에 반달 칼날처럼 납작하게 늘인 얼굴 판이 하나 붙어 있다(지나가는 쪽으로 세운 판).

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 부품, 몸 자리 = 매단 점):
  gave:gabe/g152         추 얼굴(가운데 = 얼굴 가운데, 앞 +Z = 지나가는 쪽) + 턱 밑 반달 칼날 판(아래로 5.5 까지)
  gave:gabe/g152_arm     추대(막대기 둘 + 묶은 띠, 아래 끝 y = -17 이 얼굴 정수리에 붙고 위로 34블록). 부위 cut_l, cut_r(그 막대기가 끊김)
  gave:gabe/g152_band    바닥 띠(위를 보는 판 2x2: Java 가 폭 5, 길이 30 으로 늘임). 상태 "now"(지금 길), "next"(곧 돌 길)
  gave:gabe/g152_shadow  추 그림자(점묘 원 2x2)
  gave:gabe/g152_lump    끝에서 떨구는 얼굴 덩이
소리는 받은 소리(k.sample). 사체 없음(떨어져 부서짐).
"""
import math

import numpy as np

from gabe import faces, kit

FACE, DEPTH = 5.0, 3.2
ARM_LEN, ARM_HALF = 34.0, 17.0


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def blade_tex():
    """반달 칼날: 옆으로 늘인 얼굴 그림을 반달(아래가 둥근 초승달)로 오려 냄, 가장자리는 하얗게 갈린 날"""
    f = faces.far(faces.Face(eyes="half", mouth="torn", blush="deep", tufts=False)).astype(np.float32)
    w, h = 64, 24
    out = np.zeros((h, w, 4), np.uint8)
    for y in range(h):
        for x in range(w):
            u, v = (x + 0.5) / w * 2 - 1, (y + 0.5) / h
            # 위는 곧은 변, 아래는 둥근 호: 반달
            edge = math.sqrt(max(0.0, 1 - u * u))
            if v > edge:
                continue
            sx, sy = int((u * 0.5 + 0.5) * 31), int(min(31, v * 22))
            col = f[sy, sx, :3]
            if edge - v < 0.08:
                col = np.array([236, 230, 238])
            out[y, x, :3] = np.clip(col, 0, 255)
            out[y, x, 3] = 255
    return faces._fill_rest(out)


def band_tex(kind):
    """바닥 띠: 옅은 점묘 띠 + 두 가장자리 선. now 는 흰 잿빛, next 는 붉은 띠끝 표시가 섞인 연분홍"""
    w, h = 32, 160
    out = np.zeros((h, w, 4), np.uint8)
    bay = _bayer()
    col = (196, 188, 178) if kind == "now" else (232, 150, 160)
    for y in range(h):
        for x in range(w):
            edge = x < 2 or x >= w - 2
            tick = (y % 20) < 2
            if edge or (tick and kind == "next"):
                out[y, x] = (*col, 255)
            elif bay[y % 4, x % 4] < 0.16:
                out[y, x] = (*col, 255)
    return faces._fill_rest(out)


def shadow_tex(n=64):
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r < 1 and bay[y % 4, x % 4] < 0.7 * min(1, (1 - r) / 0.3):
                out[y, x] = (18, 10, 16, 255)
    return faces._fill_rest(out)


def band_ring_tex():
    """추대를 묶은 띠(살 띠)"""
    t = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            v = 0.85 + 0.15 * math.sin(x * 0.9 + y * 0.4)
            t[y, x] = (int(186 * v), int(96 * v), int(104 * v), 255)
    return t


def bob(s, k):
    s.head(size=FACE, depth=DEPTH, face=faces.Face(eyes="squint", mouth="dot", blush="deep", tilt=6.0), near=False)
    t = k.texture("blade", blade_tex())
    # 턱 밑 반달 칼날(지나가는 쪽 앞뒤로 길게, 아래로 반달): 옆에서 보이는 판(동, 서 두 면)
    s.box((0, -FACE / 2 - 3.0, -4.2), (0, -FACE / 2 + 0.2, 4.2), {"east": t, "west": t}, faces=("east", "west"), uv=[0, 0, 16, 16], face=True)
    # 칼날 뿌리(턱에 박힌 살)
    s.box((-0.5, -FACE / 2 - 0.4, -2.0), (0.5, -FACE / 2 + 0.1, 2.0), "flesh", face=True)
    # 정수리에 추대가 박힌 받침
    s.box((-1.2, FACE / 2 - 0.1, -1.0), (1.2, FACE / 2 + 0.6, 1.0), "flesh", face=True, faces=("north", "south", "east", "west", "up"))


def arm(s, k):
    rt = k.texture("arm_band", band_ring_tex())
    for side, part in ((1, "cut_l"), (-1, "cut_r")):
        with s.when(f"!on:{part}"):
            s.rod((side * 0.6, -ARM_HALF, 0), (0, 1, 0), length=ARM_LEN, segs=2, thick=0.88, wave=None, toward=(0, 0, 1), base=False, rag=False)
    # 둘을 한 줄기로 묶은 살 띠(4블록마다)
    y = -ARM_HALF + 3.0
    while y < ARM_HALF - 2:
        s.box((-1.35, y, -0.6), (1.35, y + 0.7, 0.6), rt, faces=("north", "south", "east", "west"), face=True)
        y += 4.0


def lump(s, k):
    s.head(size=1.4, depth=1.1, face="bloodshot", near=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.26, mut2=False, torn=False, rag=False)
    bob(m.state(""), k)
    a = k.model("g152_arm", poses=1, period=20, shrink=0.085, mut2=False, open=False, torn=False, rag=False)
    arm(a.state(""), k)
    b = k.model("g152_band", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for kind in ("now", "next"):
        b.state(kind).quad((0, 0, 0), (2, 2), "up", k.texture(f"band_{kind}", band_tex(kind)))
    b.default = "now"
    sh = k.model("g152_shadow", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    sh.state("").quad((0, 0, 0), (2, 2), "up", k.texture("shadow", shadow_tex()))
    lp = k.model("g152_lump", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    lump(lp.state(""), k)
    k.icon(model="g152", state="")


def sounds(k: kit.Kit):
    k.sample("tick", "tag:click", "하늘의 똑딱", pitch=0.45, attenuation=96, n=2, level=-8.0)
    k.sample("swoosh", "tag:blade", "추가 스쳐 간다", pitch=0.5, attenuation=48)
    k.sample("snap", "tag:breaking", "추대가 끊어진다", pitch=0.6, attenuation=64)
    k.sample("crash", "tag:quake", "추가 떨어진다", pitch=0.65, attenuation=96, n=2)


def phrases():
    return {"cause_g152": "하늘 시계추에 쓸려 갔다", "cause_g152_dust": "시계추가 일으킨 흙먼지", "cause_g152_lump": "시계추가 떨군 얼굴 덩이"}
