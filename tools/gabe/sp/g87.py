"""G87 세 바람 게이브(2막, 떼, 방해): 0.5 블록 얼굴을 살 띠가 나선으로 감아 올린 작은 회오리 셋이 한 조.

한 모델에 연장 셋을 조건으로: on:rake(첫째: 엔드 막대기 둘이 갈퀴처럼 아래로, 끝에 짧은 갈퀴 살), on:sickle(둘째: 막대기 둘이 낫처럼
옆으로 굽음), on:brush(셋째: 막대기 둘 끝에 젖은 살 솔). 얼굴도 셋이 다르다(반쯤 뜬, 핏발, 웃음).
회오리: 아래가 좁고 위가 넓은 깔때기를 살 띠 두 가닥이 감아 오르며 돈다(자세 4, 8틱 한 바퀴의 반: 두 가닥이 맞물려 이어짐).

- gave:gabe/g87 상태 ""(회오리 돎). 떼 역할이라 변이 막대기, 입 찢어짐 끔.
- gave:gabe/g87_hand: 숨은 피해를 바른 사람의 뒤통수에 남들만 보는 분홍 손자국(머리 칸 모델, +Z 가 뒤통수 쪽).
소리 whoosh(세 번 스치는 바람, 20), slash(베임, 16), burst(숨은 상처 터짐, 16).
"""
import math

import numpy as np
from PIL import Image, ImageDraw

from gabe import kit

FACE = 0.5
Y0, Y1 = -0.7, 0.62
R0, R1 = 0.1, 0.52
TURNS = 1.6
SEGS = 13


def hand_img():
    """분홍 손자국(손바닥 + 손가락 다섯)"""
    im = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    col = (232, 120, 150, 255)
    d.ellipse((9, 14, 23, 28), fill=col)
    for x, y0, h in ((8, 6, 10), (12, 3, 12), (16, 2, 13), (20, 4, 11)):
        d.rounded_rectangle((x, y0, x + 3, y0 + h), radius=1, fill=col)
    d.rounded_rectangle((22, 15, 27, 18), radius=1, fill=col)
    return im


def helix(s, k):
    """나선 살 띠 두 가닥: 자세마다 45도씩 돎(두 가닥이라 4자세 = 반 바퀴로 이어짐)"""
    for f in s.frames():
        with f:
            for strand in (0, 1):
                pts = []
                for i in range(SEGS + 1):
                    t = i / SEGS
                    th = 2 * math.pi * (TURNS * t + strand * 0.5) + math.pi / 4 * f.k
                    r = R0 + (R1 - R0) * t
                    pts.append(np.array((r * math.sin(th), Y0 + (Y1 - Y0) * t, r * math.cos(th))))
                for i in range(SEGS):
                    a, b = pts[i], pts[i + 1]
                    d = b - a
                    radial = np.array((a[0], 0.0, a[2]))
                    if np.linalg.norm(radial) < 1e-6:
                        radial = np.array((1.0, 0.0, 0.0))
                    s.band(tuple(a), tuple(d), float(np.linalg.norm(d)) * 1.08, 0.13 + 0.07 * i / SEGS, segs=1, thick=0.02,
                           toward=tuple(radial), tex="flesh")


def tools(s):
    # 첫째: 갈퀴(아래로 막대기 둘 + 끝에 짧은 갈퀴 살)
    with s.when("on:rake"):
        s.head(size=FACE, depth=0.36, face="half")
        for side in (1, -1):
            root = (side * 0.16, -0.25, 0.12)
            d = np.array((side * 0.25, -1.0, 0.35))
            s.rod(root, tuple(d), length=0.62, segs=1, thick=0.06, base=True, rag=False)
            tip = np.array(root) + d / np.linalg.norm(d) * 0.62
            for j in (-1, 0, 1):
                s.rod(tuple(tip), (j * 0.35, -0.3, 1.0), length=0.16, segs=1, thick=0.035, base=False, rag=False)
    # 둘째: 낫(옆으로 뻗다 앞으로 굽음)
    with s.when("on:sickle"):
        s.head(size=FACE, depth=0.36, face="bloodshot")
        for side in (1, -1):
            root = np.array((side * 0.27, 0.0, -0.02))
            d1 = np.array((side * 1.0, 0.15, -0.15))
            d1 /= np.linalg.norm(d1)
            s.rod(tuple(root), tuple(d1), length=0.42, segs=1, thick=0.06, base=True, rag=False)
            e = root + d1 * 0.42
            d2 = np.array((side * 0.35, -0.2, 1.0))
            s.rod(tuple(e), tuple(d2), length=0.36, segs=1, thick=0.05, base=False, rag=False)
    # 셋째: 솔(아래 앞으로 막대기 둘, 끝에 젖은 살 솔)
    with s.when("on:brush"):
        s.head(size=FACE, depth=0.36, face="smile")
        for side in (1, -1):
            root = np.array((side * 0.15, -0.22, 0.1))
            d = np.array((side * 0.2, -0.75, 0.75))
            d /= np.linalg.norm(d)
            s.rod(tuple(root), tuple(d), length=0.5, segs=1, thick=0.055, base=True, rag=False)
            t = root + d * 0.55
            s.box(tuple(t - 0.09), tuple(t + 0.09), "wetskin", face=True)
            s.box((t[0] - 0.06, t[1] - 0.17, t[2] - 0.06), (t[0] + 0.06, t[1] - 0.07, t[2] + 0.06), "wetskin", face=True)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    s = m.state("")
    helix(s, k)
    tools(s)
    # 아무 연장도 안 켜졌을 때(시험 소환 등) 보이는 얼굴
    with s.when("!on:rake", "!on:sickle", "!on:brush"):
        s.head(size=FACE, depth=0.36, face="smile_a")

    h = k.model("g87_hand", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    hs = h.state("")
    ht = k.texture("hand", hand_img())
    hs.quad((0.0, -0.12, 0.52), (0.6, 0.6), "south", ht)
    hs.front = (0, 0, 0)


def sounds(k: kit.Kit):
    k.sample("whoosh", [{"id": "oga_wind/wind3", "start": 0.25, "end": 0.75, "pitch": 1.9}, "kenney_rpg/drawknife2", "kenney_rpg/drawknife3"],
             "세 번 스치는 바람", pitch=1.3, attenuation=20)
    k.sample("slash", ["kenney_rpg/knifeslice", "kenney_rpg/knifeslice2"], "베임", pitch=1.1, attenuation=16)
    k.sample("burst", "kenney_impact/impactpunch_heavy_00*", "숨은 상처가 터짐", pitch=0.75, attenuation=16)


def phrases():
    return {"cause_g87": "세 바람에 베였다", "cause_g87_hidden": "숨은 상처가 한꺼번에 터졌다"}
