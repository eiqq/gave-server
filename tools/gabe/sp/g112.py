"""G112 허물 벗는 게이브(3막, 거인 인형: 허물 벗으며 커짐, 6 블록까지).

첫 꼴은 1 블록 얼굴이 엔드 막대기 넷으로 엎드려 기는 우스운 꼴. 둘째 꼴은 3 블록, 살 몸통에 막대기 다리 둘로 구부정하게 선다.
셋째 꼴은 6 블록, 2.5 블록 얼굴에 긴 막대기 팔다리와 등줄기 따라 솟은 막대기 등뼈. 벗은 허물(납작한 얼굴 껍질)이 온 길에 남는다.

- gave:gabe/g112(첫 꼴, shrink 1): 얼굴 가운데 (0, 0, 0), 다리 끝 y = -0.45(Java hover 0.45). 상태 ""(기기, 자세 4), "molt"(등이 갈라짐).
- gave:gabe/g112_two(둘째 꼴, shrink 0.5): 몸 가운데 (0, 0, 0), 발 y = -1.5. 상태 ""(걷기), "slam"(두 팔을 머리 위로), "molt".
- gave:gabe/g112_three(셋째 꼴, shrink 0.25): 몸 가운데 (0, 0, 0), 발 y = -3.0. 상태 ""(성큼성큼 걷기, 팔 흔들기), "sweep"(오른팔이 앞을
  가로질러 휩쓸기, 12틱 자세 4: 자세 2 가 앞을 지남).
- gave:gabe/g112_skin: 벗은 허물(바닥에 눕는 납작한 얼굴 껍질, 폭 1).
소리 molt(허물 벗는 소리, 48), slam(내려침, 24), sweep(팔 휩쓸기, 24), pop(허물 터짐, 16).
"""
import math

import numpy as np

from gabe import kit


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def limb(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    up = abs(d[1]) > 0.97 * np.linalg.norm(d)
    return s.rod(tuple(a), tuple(d), length=float(np.linalg.norm(d)), segs=1, thick=thick, base=False, tex=tex, rag=False,
                 toward=(0, 0, 1) if up else (0, 1, 0))


def peel(s, w, h, z, y0=0.0):
    """허물 벗는 중: 등과 정수리에서 젖은 껍질 띠가 벌어져 늘어짐"""
    for x in (-w * 0.35, 0.0, w * 0.35):
        s.band((x, y0 + h / 2, z), (x * 0.6, 0.4, -1), length=h * 0.55, width=w * 0.28, segs=2, thick=0.03, tex="wetskin", toward=(0, 1, 0),
               wave=kit.Wave(amp=(14, 18), lag=50, cycles=2), poses=4)


def form_one(k):
    m = k.model(poses=4, period=12, shrink=1.0, mut2=False)
    for name in ("", "molt"):
        s = m.state(name)
        h = s.head(size=1.0, depth=0.8, face="smile" if not name else "stitch", rot=(14, 0, 0))
        # 엎드려 기는 막대기 다리 넷(앞 둘, 뒤 둘이 엇갈려 허우적)
        for side in (1, -1):
            for front, ph in ((1, 0), (-1, 180)):
                root = (side * 0.45, -0.25, front * 0.3)
                s.rod(root, (side * 0.9, -0.45, front * 0.45), length=0.62, segs=2, thick=0.08, toward=(0, 0, front),
                      wave=kit.Wave(amp=(28, 18), lag=50, phase=ph + (0 if side > 0 else 180), cycles=1 if not name else 2), poses=4)
        if name:
            peel(s, 1.0, 1.0, -0.3)


def form_two(k):
    m = k.model("g112_two", poses=4, period=16, shrink=0.5, mut2=False)
    for name in ("", "slam", "molt"):
        s = m.state(name)
        # 구부정한 살 몸통(앞으로 숙임) + 위 앞의 얼굴
        s.box((-1.0, -0.6, -0.6), (1.0, 1.0, 0.6), "flesh", rot=(22, 0, 0), origin=(0, -0.6, 0), face=True)
        s.head(size=1.3, depth=1.0, at=(0, 1.15, 0.7), face="smile" if name != "molt" else "stitch", rot=(10, 0, 0))
        # 막대기 다리 둘(걷기 물결)
        for side in (1, -1):
            w = kit.Wave(amp=(24, 16), lag=70, bias=(6, -12), phase=0 if side > 0 else 180) if name == "" else None
            s.rod((side * 0.5, -0.6, 0.0), (side * 0.05, -1, 0.05), length=0.95, segs=2, thick=0.22, wave=w, toward=(0, 0, 1),
                  rag=None if w else False)
        # 짧은 살 팔 둘(slam 은 머리 위로)
        for side in (1, -1):
            sh = np.array((side * 1.05, 0.6, 0.4))
            if name == "slam":
                limb(s, sh, sh + _u((side * 0.15, 1, 0.3)) * 1.3, 0.3, "flesh")
            else:
                limb(s, sh, sh + _u((side * 0.25, -1, 0.35)) * 1.2, 0.3, "flesh")
        if name == "molt":
            peel(s, 1.6, 1.4, -0.6, 0.2)


def form_three(k):
    m = k.model("g112_three", poses=6, period=24, shrink=0.25, mut2=False)
    for name in ("", "sweep"):
        s = m.state(name, poses=6 if name == "" else 4, period=24 if name == "" else 12)
        # 몸통(살, 가슴이 넓음) + 목 + 2.5 블록 얼굴(젖어 번들거림: 눈물 얼굴)
        s.box((-1.65, -0.9, -0.8), (1.65, 1.5, 0.8), "flesh", rot=(12, 0, 0), origin=(0, -0.9, 0), face=True)
        s.box((-1.1, -1.45, -0.65), (1.1, -0.8, 0.65), "flesh", face=True)
        limb(s, (0, 1.3, 0.3), (0, 1.75, 0.55), 0.6, "flesh")
        s.head(size=2.3, depth=1.7, at=(0, 2.6, 0.8), face="tears", rot=(8, 0, 0))
        # 등줄기 막대기 등뼈(위로 갈수록 길게)
        for i, (y, ln) in enumerate(((-0.6, 0.6), (-0.1, 0.8), (0.4, 1.0), (0.9, 1.1))):
            z = -0.72 - 0.12 * i
            limb(s, (0, y, z), (0, y + ln * 0.55, z - ln * 0.85), 0.2)
        # 긴 막대기 다리 둘
        for side in (1, -1):
            w = kit.Wave(amp=(26, 18), lag=70, bias=(5, -14), phase=0 if side > 0 else 180) if name == "" else None
            s.rod((side * 0.65, -1.3, 0.0), (side * 0.06, -1, 0.04), length=1.75, segs=2, thick=0.36, wave=w, toward=(0, 0, 1),
                  rag=None if w else False)
        # 긴 막대기 팔: 왼팔(+X)은 늘 걷기 흔들기, 오른팔(-X)은 sweep 에서 앞을 가로지름
        sh_l, sh_r = np.array((1.8, 1.15, 0.3)), np.array((-1.8, 1.15, 0.3))
        if name == "":
            for side, sh, ph in ((1, sh_l, 180), (-1, sh_r, 0)):
                s.rod(tuple(sh), (side * 0.3, -1, 0.1), length=3.2, segs=2, thick=0.3, toward=(0, 0, 1),
                      wave=kit.Wave(amp=(22, 10), lag=60, bias=(4, 8), phase=ph))
        else:
            limb(s, sh_l, sh_l + _u((0.3, -1, 0.1)) * 3.2, 0.3)
            for f in s.frames():
                with f:
                    yaw = (-120, -55, 5, 60)[f.k]
                    pitch = (10, 15, 22, 35)[f.k]
                    y, p = math.radians(yaw), math.radians(pitch)
                    d = _u((math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p)))
                    e = sh_r + d * 1.6
                    limb(s, sh_r, e, 0.32)
                    limb(s, e, e + d * 1.7, 0.28)


def build(k: kit.Kit):
    form_one(k)
    form_two(k)
    form_three(k)
    sk = k.model("g112_skin", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    s = sk.state("")
    s.quad((0, 0.02, 0), (1.0, 1.0), "up", k.face_tex("blank"), face=True)
    for x, z, w, d in ((-0.62, 0, 0.3, 1.1), (0.62, 0, 0.3, 1.1), (0, 0.6, 1.0, 0.25), (0, -0.62, 1.2, 0.3)):
        s.quad((x, 0.015, z), (w, d), "up", k.part_tex("wetskin"))
    s.front = (0, 0, 0)
    k.icon(model="g112_three", state="")


def sounds(k: kit.Kit):
    k.sample("molt", ["oga_creature80b/slime_02", "lrsf_horror/bonecrack_02", "kenney_scifi/slime_000"], "허물 벗는 소리", pitch=0.7, attenuation=48)
    k.sample("slam", ["kenney_impact/impactpunch_heavy_002", "oga_sfx100/slam_03"], "내려침", pitch=0.8, attenuation=24)
    k.sample("sweep", "tag:flap", "팔 휩쓸기", pitch=0.55, attenuation=24)
    k.sample("pop", "tag:slime", "허물 터짐", pitch=1.1, attenuation=16)


def phrases():
    return {"cause_g112": "허물 벗은 거인에게 휩쓸렸다", "cause_g112_bite": "기어 오던 얼굴에 물렸다", "cause_g112_slam": "허물 벗은 것에 내려찍혔다",
            "cause_g112_stomp": "허물 벗은 거인에게 밟혔다"}
