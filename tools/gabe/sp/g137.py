"""G137 하늘 원반 게이브(4막 31라운드, 목표 압박, 원거리): 지름 20블록의 납작한 원반이 사람이 모인 곳 22블록 위에 멈춰 그림자로 덮고,
6초 동안 아무것도 하지 않다가 테두리 막대기 스물넷이 다 켜지면 입 구멍을 벌려 그림자 전체에 빛기둥을 내리꽂는다.
아랫면에 _Gave 얼굴이 동심원 세 겹으로 빽빽이 박혀 있고(겹마다 서로 반대로 돈다: Java 가 가운데와 바깥 부채꼴을 반대로 돌림), 테두리를 따라
엔드 막대기 스물넷이 바퀴살 끝처럼 바깥으로 짧게 솟아 톱니 테를 이룬다. 한가운데 지름 2블록 입 구멍. 윗면은 매끈한 맨살.

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 부품):
  gave:gabe/g137         가운데(안쪽 얼굴 겹 여덟 + 입 구멍 + 윗면 혹). 상태 "" 실눈, "wake" 실눈이 벌어짐, "open" 입 구멍이 벌어짐(쏘기 직전)
  gave:gabe/g137_q       바깥 부채꼴 하나(90도: 가운데 겹 다섯, 바깥 겹 일곱 얼굴, 테두리 막대기 여섯). 넷을 90도씩 돌려 놓는다.
                         상태 "" , "wake". 부위 l1(막대기 앞 셋 켜짐), l2(뒤 셋 켜짐)
  gave:gabe/g137_shadow  바닥 그림자(위를 보는 점묘 원 + 테두리 밝은 선, 지름 2 로 굽고 Java 가 반지름 10 으로). 상태 s1..s4 짙기
  gave:gabe/g137_beam    빛기둥(반지름 1, 높이 2 의 12각 기둥 + 가운데 엇갈린 판 셋, 안팎 두 면. Java 가 반지름 10, 높이 22 로 늘임)
  gave:gabe/g137_lump    떨어뜨리는 얼굴 덩이(얼굴 1.3 + 짧은 막대기 둘)
소리는 받은 소리(k.sample). 사체 없음(기울며 떨어져 부서짐).
"""
import math

import numpy as np

from gabe import faces, kit

R_DISC = 10.0
ROD_L, ROD_T = 1.9, 0.38


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def skin_tex(seed, tone=(232, 196, 186)):
    """원반 윗면, 판 맨살: 옅은 살빛에 넓은 주름 결"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:32, 0:32].astype(np.float32)
    v = 0.9 + 0.07 * np.sin(xx * 0.35 + np.sin(yy * 0.2) * 2) + rng.rand(32, 32) * 0.04
    out = np.zeros((32, 32, 4), np.uint8)
    out[..., :3] = np.clip(np.stack([v * tone[0], v * tone[1], v * tone[2]], axis=2), 0, 255)
    out[..., 3] = 255
    return out


def under_tex(seed):
    """얼굴 사이 아랫면 살: 짙은 살에 얼굴을 박은 자리 둘레 골"""
    t = skin_tex(seed, (168, 104, 108)).astype(np.float32)
    yy, xx = np.mgrid[0:32, 0:32]
    t[((xx + yy) % 11 == 0), :3] *= 0.7
    return np.clip(t, 0, 255).astype(np.uint8)


def rod_tex(lit):
    """테두리 막대기: 꺼짐은 잿빛 막대, 켜짐은 하얗게 달아오른 막대(끝이 분홍)"""
    out = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            if lit:
                c = (255, 246, 250) if (x + y) % 5 else (255, 200, 222)
            else:
                c = (92, 86, 98) if (x + y) % 5 else (70, 64, 76)
            out[y, x] = (*c, 255)
    return out


def mouth_tex(open_):
    """입 구멍(아래를 보는 둥근 구멍, 컷아웃): 닫힘은 검붉은 구멍과 주름 테, 벌어짐은 이빨 고리와 달아오른 분홍 속"""
    n = 32
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r > 1:
                continue
            if open_:
                if r > 0.86:
                    col = (236, 226, 214) if int(math.degrees(math.atan2(y - c, x - c)) / 15) % 2 == 0 else (120, 40, 50)
                else:
                    g = 1 - r
                    col = (int(255 * (0.75 + 0.25 * g)), int(150 + 90 * g), int(170 + 80 * g))
            else:
                col = (120, 60, 66) if r > 0.8 else (int(30 + 30 * r), 10, 16)
            out[y, x] = (*col, 255)
    return faces._fill_rest(out)


def shadow_tex(dens, n=160):
    """그림자: 가운데가 짙고 가장자리로 성긴 점묘 + 테두리에 밝은 선(빛기둥이 내리꽂힐 경계)"""
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r >= 1:
                continue
            if r > 0.965:
                out[y, x] = (238, 222, 236, 255)
                continue
            d = dens * min(1.0, (1 - r) / 0.2)
            if bay[y % 4, x % 4] < d:
                v = 0.85 + 0.15 * math.sin(x * 0.5 + y * 0.35)
                out[y, x] = (int(16 * v), int(10 * v), int(18 * v), 255)
    return faces._fill_rest(out)


def beam_tex(seed):
    """빛기둥: 하얀 분홍 세로 빛살(컷아웃 점묘로 성기게)"""
    rng = np.random.RandomState(seed)
    n = 32
    out = np.zeros((64, n, 4), np.uint8)
    cols = rng.rand(n) * 0.7 + 0.3
    bay = np.tile(_bayer(), (16, 8))
    for y in range(64):
        for x in range(n):
            d = cols[x] * (0.65 + 0.35 * math.sin(y * 0.4 + x))
            if bay[y, x] < d:
                k = 0.85 + 0.15 * cols[x]
                out[y, x] = (255, int(232 * k), int(244 * k), 255)
    return faces._fill_rest(out)


def ring_faces(s, k, r, n, a0, a1, size, face):
    """반지름 r 둘레 a0..a1 도에 아래를 보는 얼굴 판 n 장(이마가 바깥쪽)"""
    tid = k.face_tex(face)
    for i in range(n):
        a = a0 + (a1 - a0) * (i + 0.5) / n
        x, z = math.sin(math.radians(a)) * r, math.cos(math.radians(a)) * r
        s.quad((x, -0.62, z), (size, size), "down", tid, rot=(0, a + 180, 0), origin=(x, -0.62, z), face=True)


def center(s, k, mode):
    """가운데: 안쪽 얼굴 겹 여덟, 입 구멍, 아랫판, 윗면 혹"""
    skin = k.texture("top", skin_tex(3))
    under = k.texture("under", under_tex(4))
    s.box((-4.1, -0.6, -4.1), (4.1, 0.55, 4.1), {"up": skin, "down": under, "north": skin, "south": skin, "east": skin, "west": skin},
          rot=(0, 22.5, 0), origin=(0, 0, 0), face=True)
    s.box((-2.4, 0.5, -2.4), (2.4, 1.3, 2.4), skin, faces=("up", "north", "south", "east", "west"), rot=(0, 45, 0), origin=(0, 0, 0), face=True)
    wake = mode != ""
    f = faces.Face(eyes="open" if wake else "squint", blush="deep" if mode == "open" else "pink", mouth="teeth_ring" if mode == "open" else "dot")
    ring_faces(s, k, 2.95, 8, 0, 360, 2.2, f)
    s.quad((0, -0.66, 0), (2.2, 2.2), "down", k.texture("mouth_open" if mode == "open" else "mouth", mouth_tex(mode == "open")))
    if mode == "open":
        s.teeth((0, -0.7, 0), 1.05, count=12, axis=(0, -1, 0), length=0.35, size=0.12, inward=0.2)


def quadrant(s, k, mode):
    """바깥 부채꼴(0..90 도): 판 셋, 가운데 겹 다섯 얼굴, 바깥 겹 일곱 얼굴, 테두리 막대기 여섯(l1, l2 묶음)"""
    skin = k.texture("top", skin_tex(3))
    under = k.texture("under", under_tex(4))
    for a in (15, 45, 75):
        w = 2 * R_DISC * math.tan(math.radians(15)) + 0.1
        s.box((-w / 2, -0.55, 3.6), (w / 2, 0.42, R_DISC), {"up": skin, "down": under, "south": skin}, faces=("up", "down", "south"),
              rot=(0, a, 0), origin=(0, 0, 0), face=True)
    wake = mode == "wake"
    eyes = "open" if wake else "squint"
    ring_faces(s, k, 5.55, 5, 0, 90, 2.6, faces.Face(eyes=eyes, tilt=-10.0))
    ring_faces(s, k, 8.35, 7, 0, 90, 2.75, faces.Face(eyes="bloodshot" if wake else "half", blush="deep"))
    lit, dark = k.texture("rod_lit", rod_tex(True)), k.texture("rod_dark", rod_tex(False))
    for i in range(6):
        a = 7.5 + 15 * i
        grp = "l1" if i < 3 else "l2"
        box = ((-ROD_T / 2, -ROD_T / 2, R_DISC - 0.3), (ROD_T / 2, ROD_T / 2, R_DISC + ROD_L))
        with s.when(f"!on:{grp}"):
            s.box(*box, dark, rot=(0, a, 0), origin=(0, 0, 0), faces=("south", "east", "west", "up", "down"))
        with s.when(f"on:{grp}"):
            s.box(*box, lit, rot=(0, a, 0), origin=(0, 0, 0), faces=("south", "east", "west", "up", "down"))


def beam(s, k):
    t = k.texture("beam", beam_tex(9))
    n = 12
    w = 2 * math.tan(math.pi / n) + 0.02
    for i in range(n):
        a = 360.0 * i / n
        x, z = math.sin(math.radians(a)), math.cos(math.radians(a))
        s.box((x - w / 2, -1, z), (x + w / 2, 1, z), t, faces=("south", "north"), rot=(0, a, 0), origin=(x, 0, z))
    for a in (0, 60, 120):
        s.box((-0.95, -1, 0), (0.95, 1, 0), t, faces=("south", "north"), rot=(0, a, 0), origin=(0, 0, 0))


def lump(s, k):
    s.head(size=1.3, depth=1.0, face="bloodshot", near=False)
    for side in (1, -1):
        s.rod((side * 0.45, 0.5, -0.2), (side * 0.6, 1, -0.3), length=0.7, segs=1, thick=0.12, wave=None, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.3, mut2=False, torn=False, rag=False)
    for mode in ("", "wake", "open"):
        center(m.state(mode), k, mode)
    q = k.model("g137_q", poses=1, period=20, shrink=0.125, mut2=False, open=False, torn=False, rag=False)
    for mode in ("", "wake"):
        quadrant(q.state(mode), k, mode)
    sh = k.model("g137_shadow", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for i, dens in enumerate((0.22, 0.4, 0.6, 0.8), start=1):
        sh.state(f"s{i}").quad((0, 0, 0), (2, 2), "up", k.texture(f"shadow{i}", shadow_tex(dens)))
    sh.default = "s1"
    b = k.model("g137_beam", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    beam(b.state(""), k)
    lp = k.model("g137_lump", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    lump(lp.state(""), k)
    k.icon(model="g137_q", state="wake", view="top")


def sounds(k: kit.Kit):
    k.sample("hum", "tag:drone", "머리 위의 웅웅", pitch=0.55, attenuation=96, n=2)
    k.sample("lit", "tag:click", "테두리 불이 켜진다", pitch=0.6, attenuation=48)
    k.sample("fire", "tag:boom", "빛기둥", pitch=0.55, attenuation=128, n=2)
    k.sample("fizzle", "tag:breaking", "빛기둥이 꺼진다", pitch=0.6, attenuation=64)


def phrases():
    return {"cause_g137": "하늘 원반의 빛기둥", "cause_g137_lump": "원반이 떨군 얼굴 덩이"}
