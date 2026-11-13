"""G127 머리 탑 게이브(3막, 지원, 목표 압박): 1블록 얼굴 상자 다섯을 층층이 쌓은 5.5블록 탑.

층마다 얼굴이 다른 방향을 보고(72도씩), 층 사이를 엔드 막대기가 꼬치처럼 가로로 꿰었다. 꼭대기에 긴 막대기 안테나 둘이 V자로 섰고
끝이 분홍으로 깜빡인다(손으로 짠 자세 2: 켜짐, 꺼짐).

- gave:gabe/g127(shrink 0.3): 몸 가운데가 (0, 0, 0), 바닥 y = -2.75(Java hover 2.75). 상태는 남은 층 수 x 찍기:
  ""(다섯, 얼굴 흩어짐), "snap"(다섯, 모든 얼굴이 앞 +Z), "l4", "l4s", "l3", "l3s", "l2", "l2s", "l1", "l1s",
  "buried"(솟기 전: 땅 밑 작은 상자 하나, 놓이는 틱에 탑이 번쩍 보이지 않게).
  층이 부서지면 남은 층이 바닥부터 내려앉은 꼴. 부위 ant_l, ant_r: 그 안테나가 꺾임(짧은 그루터기).
- gave:gabe/g127_pin: 찍힌 사람 머리 위 분홍 표시(머리 칸 모델, 남들만 봄).
소리 beep(신호음, 48), click(딸깍, 48), zap(안테나 번쩍임, 24), rise(땅을 뚫고 솟음, 48), crumble(층이 무너짐, 32).
"""
import math

import numpy as np
from PIL import Image

from gabe import kit

FLOOR = -2.75
LAYER = 1.0
GAP = 0.1
SPREAD = (0, 72, 144, 216, 288)
FACES = ("smile", "half", "bloodshot", "smile_c", "open")


def pink_img(bright):
    n = 16
    out = np.zeros((n, n, 4), np.uint8)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    r = np.hypot(xx - 7.5, yy - 7.5) / 8
    base = np.array((255, 120, 190) if bright else (150, 70, 110), np.float32)
    col = base * (1.15 - 0.35 * r)[..., None]
    out[..., :3] = np.clip(col, 0, 255)
    out[..., 3] = 255
    return Image.fromarray(out, "RGBA")


def tower(s, k, n, snap, tip_on, tip_off):
    """n 층(바닥부터), snap 이면 모든 얼굴이 앞, 꼭대기 안테나 둘(깜빡임 자세 2)"""
    y = FLOOR
    for i in range(n):
        c = (0.0, y + LAYER / 2, 0.0)
        a = 0 if snap else SPREAD[i]
        s.head(size=LAYER, depth=LAYER, at=c, face=FACES[i], rot=(0, a, 0) if a else None)
        y += LAYER
        if i < n - 1:
            # 층 사이 꼬치: 가로로 꿴 엔드 막대기 둘(엇갈림)
            ym = y + GAP / 2
            for d in ((1, 0, 0.35), (0.35, 0, -1)):
                dv = np.array(d, float)
                dv /= np.linalg.norm(dv)
                p0 = np.array((0.0, ym, 0.0)) - dv * 0.78
                s.rod(tuple(p0), tuple(dv), length=1.56, segs=1, thick=0.1, base=False, rag=False)
            y += GAP
    top = y
    # 안테나 V(꺾이면 그루터기)
    for side, part in ((1, "ant_l"), (-1, "ant_r")):
        root = (side * 0.22, top - 0.05, 0.0)
        d = np.array((side * 0.45, 1.0, 0.0))
        d /= np.linalg.norm(d)
        with s.when("!on:" + part):
            s.rod(root, tuple(d), length=1.8, segs=2, thick=0.09, base=True, rag=False, toward=(0, 0, 1))
            tip = np.array(root) + d * 1.86
            for f in s.frames(poses=2):
                with f:
                    t = tip_on if f.k == 0 else tip_off
                    sz = 0.17 if f.k == 0 else 0.12
                    s.box(tuple(tip - sz), tuple(tip + sz), t)
        with s.when("on:" + part):
            s.rod(root, tuple(d), length=0.35, segs=1, thick=0.09, base=True, rag=False, toward=(0, 0, 1))


def build(k: kit.Kit):
    tip_on = k.texture("tip_on", pink_img(True))
    tip_off = k.texture("tip_off", pink_img(False))
    m = k.model(poses=2, period=10, shrink=0.3, mut2=False, rag=False)
    for n in (5, 4, 3, 2, 1):
        base = "" if n == 5 else f"l{n}"
        tower(m.state(base), k, n, False, tip_on, tip_off)
        tower(m.state(base + "s" if base else "snap"), k, n, True, tip_on, tip_off)

    # 묻힘: 솟기 전(감독이 놓은 틱부터) 아무것도 안 보이게 땅 밑 작은 상자 하나만
    bu = m.state("buried", poses=1)
    bu.box((-0.2, FLOOR - 0.5, -0.2), (0.2, FLOOR - 0.3, 0.2), "flesh")
    bu.front = (0, 0, 0)

    pin = k.model("g127_pin", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    s = pin.state("")
    s.box((-0.14, 0.85, -0.14), (0.14, 1.13, 0.14), tip_on, rot=(0, 45, 45), origin=(0, 0.99, 0))
    s.box((-0.05, 0.62, -0.05), (0.05, 0.82, 0.05), tip_on)
    s.front = (0, 0, 0)
    k.icon(model="g127", state="snap")


def sounds(k: kit.Kit):
    k.sample("beep", ["kenney_digital/highup", "kenney_digital/pepsound1", "kenney_digital/pepsound3"], "신호음", pitch=1.3, attenuation=48)
    k.sample("click", ["kenney_interface/back_002", "kenney_interface/back_004"], "딸깍", pitch=0.7, volume=1.0, attenuation=48)
    k.sample("zap", ["oga_electric/shieldhit"], "안테나 번쩍임", pitch=1.2, attenuation=24)
    k.sample("rise", ["kenney_scifi/lowfrequency_explosion_001", "oga_breaking75/bfh1_rock_breaking_01"], "땅을 뚫고 솟음", pitch=0.8,
             attenuation=48)
    k.sample("crumble", ["oga_breaking75/bfh1_rock_breaking_02", "oga_breaking75/bfh1_rock_breaking_03"], "층이 무너짐", pitch=0.8,
             attenuation=32)


def phrases():
    return {"cause_g127": "머리 탑의 안테나에 지졌다", "notice_g127_marked": "탑이 너를 찍었다"}
