"""G61 설 짐승 게이브(1막, 돌격, 튼튼이): 2.6블록 얼굴 탈 뒤로 살 띠 천 몸통이 4블록 길게 이어진 6블록 짐승(키 3블록).

천 속에 작은 게이브 둘이 들어 탈을 쓰고 있다: 천 밑으로 엔드 막대기 다리 넷(앞 둘, 뒤 둘)이 따로 논다.
탈 둘레에 살 띠 술 갈기, 이마에 막대기 뿔 둘, 아래턱이 따로 덜컥거린다.

디스플레이 넷(Java 가 부품으로 잇는다):
- gave:gabe/g61(shrink 0.4): 탈 머리. 몸 가운데 = 탈 가운데. 상태 ""(걷기: 갈기 흔들림, 턱 덜컥 자세 2), "charge"(머리를 0.45 낮추고
  갈기를 뒤로 눕힘, 뿔이 앞으로), "scared"(갈기가 바깥으로 곤두섬, 턱을 벌림).
- gave:gabe/g61_cloth(shrink 0.4): 천 몸통(가운데 기준 앞뒤 ±2, 위 1.0, 아래 자락 -1.0). 위 판과 옆 판 둘이 물결로 출렁(자세 4), 뒤 자락.
- gave:gabe/g61_legs: 다리 한 쌍(앞이든 뒤든 같은 모델). 엉덩이 y 0.8, 발끝 y -0.9. 상태 ""(걸음 자세 4), "scrape"(한 다리로 땅 긁기),
  "broken"(주저앉아 벌어진 다리).
- gave:gabe/g61_kid: 죽으면 튀어나오는 천 속 작은 게이브(0.6 얼굴 + 다리 막대기 둘).
소리 step(거대한 발걸음, 48), growl(으르렁, 32), clack(턱 딱딱, 24), ram(들이받음, 32).
"""
import math

import numpy as np

from gabe import kit

S = 0.4
MANE = 12


def mask(s, k, mode):
    """탈 머리. mode: walk | charge | scared"""
    dy = -0.45 if mode == "charge" else 0.0
    h = s.head(size=2.4, height=2.6, depth=1.3, at=(0, 0.15 + dy, 0), face="smile")
    # 뿔 둘: 이마 위에서 위로(돌진 때 앞으로 숙임)
    for side in (1, -1):
        d = (side * 0.3, 0.55, 1.0) if mode == "charge" else (side * 0.3, 1.0, 0.35)
        s.rod(h.point(side * 0.45, 0.92, 0.2), d, length=0.95, segs=1, thick=0.15, base=True, rag=False)
    # 아래턱: 탈 아래 뒤 경첩에서 덜컥(자세 2: 닫힘, 벌어짐)
    hinge = (0.0, -1.18 + dy, -0.15)
    for f in s.frames(poses=2):
        with f:
            ang = (35 if f.k else 12) if mode == "scared" else (18 if f.k else 0)
            rot = (ang, 0, 0)
            s.box((-0.92, -1.62 + dy, -0.15), (0.92, -1.18 + dy, 0.62), "flesh", rot=rot, origin=hinge, face=True)
            s.box((-0.8, -1.2 + dy, 0.5), (0.8, -1.1 + dy, 0.6), "bone", rot=rot, origin=hinge)
    # 갈기: 탈 둘레(위와 양옆)에서 바깥 뒤로 살 띠 술
    for i in range(MANE):
        t = i / (MANE - 1)
        a = math.pi * (-0.15 + 1.3 * t)  # 오른쪽 아래 → 위 → 왼쪽 아래
        u, v = -math.cos(a), math.sin(a)
        root = h.point(u * 1.02, v * 1.02, -0.55)
        out = np.array((u * 1.0, v * 1.0, 0.0))
        if mode == "charge":
            d = out * 0.35 + np.array((0, 0, -1.0))
            w = kit.Wave(amp=(6, 8), lag=50, phase=i * 40, cycles=2)
        elif mode == "scared":
            d = out * 1.0 + np.array((0, 0, -0.15))
            w = kit.Wave(amp=(10, 12), lag=30, phase=i * 70, cycles=2)
        else:
            d = out * 0.7 + np.array((0, -0.35, -0.6))
            w = kit.Wave(amp=(14, 18), lag=55, phase=i * 30)
        # 띠 넓은 면이 앞(+Z)을 보게: toward 를 앞으로(돌진 때는 갈기가 뒤로 누워 위를 보게)
        d = d / np.linalg.norm(d)
        tw = (0.0, 0.0, 1.0) if abs(d[2]) < 0.8 else (0.0, 1.0, 0.0)
        s.band(root, tuple(d), 0.95, 0.26, segs=2, thick=0.03, wave=w, toward=tw, tex="flesh", poses=4)


def cloth(s):
    w = kit.Wave(amp=(3, 4, 5, 6), lag=70)
    # 위 판(앞에서 뒤로), 옆 판 둘(세로로 넓게), 뒤 자락
    s.band((0, 1.0, 2.0), (0, 0, -1), 4.0, 2.6, segs=4, thick=0.03, wave=w, toward=(0, 1, 0), tex="flesh")
    for side in (1, -1):
        ws = kit.Wave(amp=(4, 6, 8, 9), lag=65, phase=0 if side > 0 else 120)
        s.band((side * 1.3, 0.0, 2.0), (0, 0, -1), 4.0, 2.0, segs=4, thick=0.03, wave=ws, toward=(side, 0, 0), tex="flesh")
    s.band((0, 1.0, -2.0), (0, -1, -0.15), 2.0, 2.5, segs=2, thick=0.03, wave=kit.Wave(amp=(6, 9), lag=60), toward=(0, 0, -1),
           tex="flesh")
    # 등줄기: 천을 받친 엔드 막대기(가만히)
    s.rod((0, 1.05, 1.9), (0, 0, -1), length=3.8, segs=2, thick=0.12, base=False, rag=False)
    s.front = (0, 0, 0)


def legs(s, mode):
    for side in (1, -1):
        root = (side * 0.55, 0.8, 0.0)
        if mode == "broken":
            s.rod(root, (side * 0.9, -0.5, 0.3), length=1.6, segs=2, thick=0.16, base=True, rag=False)
            continue
        if mode == "scrape" and side > 0:
            w = kit.Wave(amp=(40, 25), lag=40, bias=(-10, 15), cycles=2)
        elif mode == "scrape":
            w = None
        else:
            w = kit.Wave(amp=(26, 18), lag=70, bias=(0, -10), phase=0 if side > 0 else 180)
        s.rod(root, (0, -1, 0.05), length=1.7, segs=2, thick=0.16, base=True, wave=w, toward=(0, 0, 1), rag=False)
    s.front = (0, 0, 0)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=S, mut2=False)
    mask(m.state(""), k, "walk")
    mask(m.state("charge"), k, "charge")
    mask(m.state("scared"), k, "scared")

    c = k.model("g61_cloth", poses=4, period=24, shrink=S, mut2=False, open=False, torn=False, rag=False)
    cloth(c.state(""))

    lg = k.model("g61_legs", poses=4, period=16, mut2=False, open=False, torn=False, rag=False)
    legs(lg.state(""), "walk")
    legs(lg.state("scrape"), "scrape")
    legs(lg.state("broken", poses=1), "broken")

    kd = k.model("g61_kid", poses=4, period=8, mut2=False)
    s = kd.state("")
    h = s.head(size=0.6, depth=0.5, face="smile_a")
    for side in (1, -1):
        s.rod(h.point(side * 0.5, -0.9, 0), (side * 0.15, -1, 0.1), length=0.45, segs=2, thick=0.08,
              wave=kit.Wave(amp=(30, 20), lag=50, phase=0 if side > 0 else 180, cycles=2), toward=(0, 0, 1), poses=4)
    k.icon(model="g61", state="")


def sounds(k: kit.Kit):
    k.sample("step", "tag:giant", "거대한 발걸음", pitch=0.7, attenuation=48)
    k.sample("growl", "tag:growl", "으르렁", pitch=0.75, attenuation=32)
    k.sample("clack", ["oga_metalwood100/wood_slam_01", "oga_metalwood100/wood_slam_04"], "턱 딱딱", pitch=1.6, attenuation=24)
    k.sample("ram", "kenney_impact/impactpunch_heavy_00*", "들이받음", pitch=0.55, attenuation=32)


def phrases():
    return {"cause_g61": "설 짐승에 들이받혔다", "cause_g61_bite": "설 짐승 턱에 물렸다", "cause_g61_kid": "천 속 작은 게이브에 박혔다"}
