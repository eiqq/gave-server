"""G65 빨랫줄 게이브: 엔드 막대기 장대 둘(키 3)이 3.6 블록 벌려 서고, 그 사이 살 띠 줄에 납작하게 눌린 웃는 얼굴 천 다섯 장이 빨래처럼 널렸다.
장대 꼭대기에는 작은 얼굴이 집게처럼 물려 있다. 멀리서 '줄에 널린 것'으로 읽히는 넓은 실루엣(폭 3.6, 키 3).

모델 gave:gabe/g65(닻: 줄과 천, shrink 0.5, 원점 = 줄 높이) 상태 ""(천이 바람도 없이 한 방향으로 펄럭임, 4자세), "taut"(줄이 팽팽, 천이 한꺼번에
앞으로 섬: 훑기 예고), "sag"(장대 하나가 부서져 천이 축 늘어짐). 부위 c1..c5(그 천이 떨어져 없음, 1 이 게이브 오른쪽 끝 -X).
모델 gave:gabe/g65_pole(부품: 장대 하나, 원점 = 줄 높이, 아래 1.5 블록이 땅, 위 1.5 블록 꼭대기에 집게 얼굴).
소리 snap(빨래 터는 소리: 훑기 예고), whip(줄이 훑고 지나감), crack(장대 부러짐), 글귀 cause_g65.
"""
import numpy as np

from gabe import kit

CLOTH_X = (-1.44, -0.72, 0.0, 0.72, 1.44)
CLOTH_W, CLOTH_H = 0.62, 0.8
CLOTH_FACES = ("smile", "half", "smile_a", "tears", "smile_c")
LINE_HALF = 1.8


def cloth(k, s, i, angle, face):
    """천 하나: 줄(y=0)에 위 가장자리가 걸리고 아래로 늘어진 얇은 판. angle 은 줄을 축으로 앞(+Z)으로 들린 각도(도)"""
    x = CLOTH_X[i]
    hw = CLOTH_W / 2
    tex = {"south": k.face_tex(face), "north": "wetskin", "east": "wetskin", "west": "wetskin", "down": "wetskin"}
    rot = (-angle, 0, 0) if abs(angle) > 0.01 else None
    kw = dict(rot=rot, origin=(x, 0, 0)) if rot else {}
    s.box((x - hw, -CLOTH_H, -0.012), (x + hw, -0.02, 0.012), tex, faces=("south", "north"), uv=[0, 0, 16, 16], face=True, **kw)
    # 빨래집게(작은 살 조각 둘)
    for dx in (-hw * 0.6, hw * 0.6):
        s.box((x + dx - 0.04, -0.09, -0.05), (x + dx + 0.04, 0.07, 0.05), "flesh", faces=("south", "north", "up"), face=True)


def line(s, sag=0.0):
    """줄: 살 띠(가운데가 sag 만큼 처짐, 세 토막)"""
    xs = (-LINE_HALF, -0.6, 0.6, LINE_HALF)
    ys = (0.0, -sag, -sag, 0.0)
    for a in range(3):
        x0, x1, y0, y1 = xs[a], xs[a + 1], ys[a], ys[a + 1]
        ang = np.degrees(np.arctan2(y1 - y0, x1 - x0))
        mx, my = (x0 + x1) / 2, (y0 + y1) / 2
        half = np.hypot(x1 - x0, y1 - y0) / 2
        rot = (0, 0, ang) if abs(ang) > 0.01 else None
        kw = dict(rot=rot, origin=(mx, my, 0)) if rot else {}
        s.box((mx - half, my - 0.035, -0.035), (mx + half, my + 0.035, 0.035), "flesh", faces=("up", "down", "south", "north"), **kw)


def build(k: kit.Kit):
    m = k.model(poses=4, period=20, shrink=0.5, mut2=False)
    # 기본: 천이 한 방향(앞)으로 바람 없이 펄럭임, 끝으로 갈수록 늦게
    s = m.state("")
    line(s)
    for f in s.frames():
        with f:
            for i in range(5):
                with s.when(f"!on:c{i + 1}"):
                    cloth(k, s, i, 14 + 12 * np.sin(2 * np.pi * f.t - i * 0.7), CLOTH_FACES[i])
    # 팽팽: 줄이 곧고 천이 한꺼번에 앞으로 섬(가만히)
    t = m.state("taut")
    line(t)
    for i in range(5):
        with t.when(f"!on:c{i + 1}"):
            cloth(k, t, i, 72, CLOTH_FACES[i])
    # 처짐: 장대 하나가 부서져 줄이 처지고 천이 축 늘어짐(가만히)
    g = m.state("sag")
    line(g, sag=0.35)
    for i in range(5):
        with g.when(f"!on:c{i + 1}"):
            cloth(k, g, i, 0, CLOTH_FACES[i])

    # 장대: 아래 1.5 땅, 위 1.5 꼭대기. 가만히 선 엔드 막대기 기둥과 발 가시 둘, 꼭대기에 집게처럼 물린 작은 얼굴
    p = k.model("g65_pole", poses=1, period=20, shrink=0.75, mut2=False, torn=False)
    ps = p.state("")
    ps.rod((0, -1.5, 0), (0, 1, 0), length=2.9, segs=2, thick=0.14, toward=(0, 0, 1), rag=False)
    for sx in (1, -1):
        ps.rod((0, -1.2, 0), (sx * 0.5, -1, 0.15), length=0.4, segs=1, thick=0.09, base=False, rag=False)
    ps.box((-0.12, -0.08, -0.12), (0.12, 0.08, 0.12), "flesh", face=True)
    ps.head(size=0.42, depth=0.32, at=(0, 1.32, 0.02), face="smile")


def sounds(k: kit.Kit):
    k.sample("snap", "tag:cloth", "빨래 터는 소리", pitch=0.7, volume=1.0, attenuation=24)
    k.sample("whip", ["kenney_rpg/cloth1", "kenney_rpg/cloth3"], "줄이 훑음", pitch=0.55, attenuation=20)
    k.sample("crack", "tag:bone", "장대 부러짐", pitch=0.7, attenuation=20)


def phrases():
    return {"cause_g65": "빨랫줄에 목이 걸렸다"}
