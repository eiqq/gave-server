"""G188 사이렌 게이브(4막 31라운드, 지원 + 방해): 키 4.5블록 기둥.

엔드 막대기 셋을 묶어 세운 기둥(살 띠로 동임) 위에 지름 2블록의 여덟 모 통이 얹혀 있고, 통 옆면에 입을 크게 벌린 _Gave 얼굴 여덟이 빙 둘러
박혀 있다. 기둥 밑동은 짧은 발 셋으로 갈라져 뒤뚱거린다. 통 옆에 크랭크 손잡이(부품)가 혼자 돈다.

- gave:gabe/g188(shrink 0.5): 몸 가운데(0, 0, 0) = 엔진 pos, 바닥 = y -2.0(Java HOVER 2.0). 통 가운데 y DRUM_Y.
  상태 ""(걷기와 감기: 발 셋 뒤뚱 2자세, 통 얼굴 e1..e8 부위가 켜지면 그 얼굴이 눈을 뜸), "siren"(통이 돌아 얼굴이 번짐 4자세, 모두 눈 뜸).
- gave:gabe/g188_crank: 크랭크 손잡이(축이 +X, 4자세로 한 바퀴). Java 가 통 옆 (CRANK_X, DRUM_Y, 0) 에 붙이고 look.rate 로 빠르게.
소리 siren(사이렌, 96), crank(손잡이 감는 소리, 24), bump(들이받기, 24).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.5
FLOOR = -2.0
DRUM_Y = 1.45
DRUM_R = 1.0
DRUM_H = 1.0
CRANK_X = 1.12

SHUT = faces.Face(mouth="teeth_ring", blush="deep")
OPEN = faces.Face(eyes="open", mouth="teeth_ring", blush="deep")
WAIL = faces.Face(eyes="bloodshot", mouth="torn", blush="deep")


def stick(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    up = abs(d[1]) > 0.97 * L
    s.rod(tuple(a), tuple(d), length=L, segs=1, thick=thick, base=False, rag=False, tex=tex, toward=(0, 0, 1) if up else (0, 1, 0))


def pillar(s):
    """막대기 셋 묶음 기둥 + 살 띠 둘 + 통 받침"""
    for i in range(3):
        a = math.radians(i * 120 + 30)
        r0, r1 = 0.2, 0.13
        stick(s, (math.cos(a) * r0, FLOOR + 0.75, math.sin(a) * r0), (math.cos(a) * r1, DRUM_Y - DRUM_H / 2 + 0.05, math.sin(a) * r1), 0.16)
    for y in (-0.6, 0.35):
        s.box((-0.3, y - 0.12, -0.3), (0.3, y + 0.12, 0.3), "flesh", face=True)
    s.box((-0.55, DRUM_Y - DRUM_H / 2 - 0.12, -0.55), (0.55, DRUM_Y - DRUM_H / 2 + 0.02, 0.55), "flesh", face=True)


def feet(s, walk):
    """짧은 발 셋(밑동에서 갈라짐). walk 면 2자세로 번갈아 들림"""
    root = (0, FLOOR + 0.8, 0)
    s.box((-0.28, FLOOR + 0.62, -0.28), (0.28, FLOOR + 0.95, 0.28), "flesh", face=True)
    if not walk:
        for i in range(3):
            a = math.radians(i * 120 + 90)
            stick(s, root, (math.cos(a) * 0.62, FLOOR + 0.04, math.sin(a) * 0.62), 0.15)
        return
    for fr in s.frames(poses=2):
        with fr:
            for i in range(3):
                a = math.radians(i * 120 + 90)
                up = 0.22 if (i + fr.k) % 2 == 0 else 0.0
                stick(s, root, (math.cos(a) * 0.62, FLOOR + 0.04 + up, math.sin(a) * 0.62), 0.15)


def drum(s, k, spin):
    """여덟 모 통: 위 뚜껑, 아래 바닥, 옆에 얼굴 여덟. spin 이면 4자세로 돌아 번짐"""
    top, bot = DRUM_Y + DRUM_H / 2, DRUM_Y - DRUM_H / 2
    s.box((-0.82, top - 0.05, -0.82), (0.82, top + 0.12, 0.82), "flesh", face=True)
    s.box((-0.45, top + 0.12, -0.45), (0.45, top + 0.3, 0.45), "flesh", face=True)
    s.box((-0.86, bot - 0.04, -0.86), (0.86, bot + 0.06, 0.86), "flesh", face=True)
    s.box((-0.72, bot + 0.05, -0.72), (0.72, top - 0.05, 0.72), "wetskin", face=True)
    w = 2 * DRUM_R * math.tan(math.pi / 8) * 1.02
    if not spin:
        for i in range(8):
            a = i * 45.0
            r = math.radians(a)
            at = (math.sin(r) * DRUM_R * 0.86, DRUM_Y, math.cos(r) * DRUM_R * 0.86)
            with s.when(f"!on:e{i + 1}"):
                s.head(size=w, height=DRUM_H * 0.92, depth=0.28, at=at, face=SHUT, rot=(0, a, 0), near=False, open=False, torn=False)
            with s.when(f"on:e{i + 1}"):
                s.head(size=w, height=DRUM_H * 0.92, depth=0.28, at=at, face=OPEN, rot=(0, a, 0), near=False, open=False, torn=False)
        return
    for fr in s.frames(poses=4):
        with fr:
            for i in range(8):
                a = i * 45.0 + fr.t * 45.0
                r = math.radians(a)
                at = (math.sin(r) * DRUM_R * 0.86, DRUM_Y, math.cos(r) * DRUM_R * 0.86)
                s.head(size=w * 1.06, height=DRUM_H * 0.92, depth=0.28, at=at, face=WAIL, rot=(0, a, 0), near=False, open=False, torn=False)


def crank(k):
    m = k.model("g188_crank", poses=4, period=20, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    s.box((0.0, -0.1, -0.1), (0.42, 0.1, 0.1), "flesh", face=True)          # 축 뿌리(통에 박힘)
    for fr in s.frames(poses=4):
        with fr:
            a = fr.t * math.tau
            d = (0.0, math.cos(a), math.sin(a))
            stick(s, (0.36, 0, 0), (0.36, d[1] * 0.55, d[2] * 0.55), 0.11)
            e = np.array((0.36, d[1] * 0.55, d[2] * 0.55))
            stick(s, tuple(e), tuple(e + (0.32, 0, 0)), 0.1)
            s.head(size=0.24, depth=0.2, at=tuple(e + (0.42, 0, 0)), face="smile_a", rot=(0, 90, 0), near=False, open=False, torn=False)
    s.front = (0, 0, 0)


def build(k: kit.Kit):
    m = k.model(poses=4, period=20, shrink=S, mut2=False, open=False, torn=True)
    st = m.state("")
    pillar(st)
    feet(st, True)
    drum(st, k, False)
    sr = m.state("siren", period=4)
    pillar(sr)
    feet(sr, False)
    drum(sr, k, True)
    crank(k)
    k.icon(model="g188", state="")


def sounds(k: kit.Kit):
    k.sample("siren", ["oga_facility_siren/facility_breach_siren", {"id": "oga_sirens/siren", "start": 2.0, "end": 6.5}], "사이렌",
             pitch=0.82, attenuation=96, level=-8, n=2)
    k.sample("crank", ["kenney_rpg/creak1", "kenney_rpg/creak2", "kenney_rpg/creak3"], "손잡이 감는 소리", pitch=0.7, attenuation=24)
    k.sample("bump", "kenney_impact/impactpunch_heavy_001", "통 들이받기", pitch=0.75, attenuation=24)


def phrases():
    return {"cause_g188": "사이렌 통에 들이받혔다", "cause_g188_wail": "사이렌 울음에 귀가 터졌다", "notice_g188_stage": "사이렌이 울었다"}
