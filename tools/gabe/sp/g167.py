"""G167 뽑기 집게 게이브(2막 16라운드, 방해, 지원, 목표 압박, 7블록): 하늘 12블록에 뜬 2.5블록 얼굴 상자에서 굵은 엔드 막대기 기둥 하나가
아래로 내려오고, 그 끝에 엔드 막대기 집게발 셋이 벌어져 있다(다 벌리면 폭 7블록). 집게발 끝마다 주먹만 한 얼굴. 얼굴 상자 옆구리에 동전 구멍처럼
찢어진 입.

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 부품, 몸 자리 = 얼굴 상자 가운데):
  gave:gabe/g167         얼굴 상자(앞이 아래로 35도 숙음) + 옆구리 동전 구멍 입 둘 + 기둥 받침. 실눈 뜸(찍음)은 키트 오버레이
  gave:gabe/g167_pole    기둥(위 +1.5 에서 아래 -1.5, 길이 3: Java 가 y 만 늘임)
  gave:gabe/g167_claw    집게(원점 = 기둥이 붙는 바퀴통). 상태 "" 벌림, "shut" 다묾(빈 손), "full" 게이브 한 움큼, "man" 사람을 집음(끝 얼굴이 눈 뜸),
                         "cut" 기둥이 끊겨 떨어지는 집게(벌어진 채 끝 얼굴 눈물)
  gave:gabe/g167_shadow  바닥 집게 그림자(위를 보는 판 2x2: Java 가 집게 폭으로 늘임). 상태 "" 옅게, "dark" 내려오기 직전
소리는 받은 소리(k.sample). 사체: 얼굴 상자는 떨어져 구름(래그돌).
"""
import math

import numpy as np

from gabe import faces, kit

HEAD = 2.5
CLAW_UP, CLAW_FINGER = 2.8, 1.8


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def slot_tex():
    """동전 구멍 입: 세로로 길게 찢어진 검붉은 틈, 가장자리는 이빨처럼 흰 점"""
    w, h = 8, 32
    out = np.zeros((h, w, 4), np.uint8)
    for y in range(h):
        for x in range(w):
            v = (y + 0.5) / h
            half = 0.5 * math.sin(math.pi * v) ** 0.6
            d = abs((x + 0.5) / w - 0.5)
            if d > half:
                continue
            if half - d < 0.12 and y % 3 == 0:
                col = (236, 228, 220)
            elif half - d < 0.12:
                col = (130, 48, 56)
            else:
                col = (30, 8, 14)
            out[y, x] = (*col, 255)
    return faces._fill_rest(out)


def shadow_tex(dens, n=64):
    """세 갈래 집게 그림자: 가운데 둥근 그늘 + 바깥으로 뻗은 세 갈래 발(점묘)"""
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            dx, dy = (x - c) / (n / 2), (y - c) / (n / 2)
            r = math.hypot(dx, dy)
            if r > 1:
                continue
            a = math.atan2(dy, dx)
            # 세 갈래: 각 0, 120, 240 둘레의 좁은 부채 + 끝 갈고리
            arm = min(abs(math.remainder(a - k * 2 * math.pi / 3, 2 * math.pi)) for k in range(3))
            on = r < 0.28 or (arm < 0.16 * (1.1 - r) + 0.05 and r < 0.95) or (0.78 < r < 0.95 and arm < 0.3)
            if on and dens * (1.15 - 0.5 * r) > bay[y % 4, x % 4]:
                out[y, x] = (14, 10, 16, 255)
    return faces._fill_rest(out)


def head_body(s, k):
    s.head(size=HEAD, depth=2.0, face="smile", rot=(35, 0, 0), near=False)
    slot = k.texture("slot", slot_tex())
    for side in (1, -1):
        # 옆구리(±X 면) 동전 구멍: 머리와 같이 숙임
        s.quad((side * (HEAD / 2 + 0.01), 0.05, 0.1), (0.42, 1.5), "east" if side > 0 else "west", slot, rot=(35, 0, 0), origin=(0, 0, 0),
               face=True)
    # 기둥 받침(턱 밑 살 덩이)
    s.box((-0.45, -1.75, -0.45), (0.45, -1.15, 0.45), "flesh", face=True)


def pole_body(s):
    s.rod((0, 1.5, 0), (0, -1, 0), length=3.0, segs=1, thick=0.34, base=False, toward=(0, 0, 1), rag=False)


def claw_body(s, mode):
    """집게 하나. mode: open | shut | full | man | cut"""
    s.box((-0.42, -0.5, -0.42), (0.42, 0.1, 0.42), "flesh", face=True)
    if mode in ("open", "cut"):
        up_a, fin_a = 55, 20
    else:
        up_a, fin_a = 22, -24
    tip_face = {"open": "smile", "shut": "smile", "full": "half", "man": "open", "cut": "tears"}[mode]
    for i in range(3):
        a = math.radians(90 + i * 120)
        ca, sa = math.cos(a), math.sin(a)
        root = (0.38 * ca, -0.35, 0.38 * sa)
        u = math.radians(up_a)
        d1 = (ca * math.sin(u), -math.cos(u), sa * math.sin(u))
        ch = s.rod(root, d1, length=CLAW_UP, segs=1, thick=0.2, base=False, toward=(0, 1, 0), rag=False)
        knee = ch.tip()
        f = math.radians(fin_a)
        d2 = (ca * math.sin(f), -math.cos(f), sa * math.sin(f))
        ch2 = s.rod(knee, d2, length=CLAW_FINGER, segs=1, thick=0.17, base=False, toward=(0, 1, 0), rag=False)
        tip = ch2.tip()
        # 끝 얼굴(주먹만 함): 안쪽을 봄
        yaw = math.degrees(math.atan2(-ca, -sa))
        s.head(size=0.7, depth=0.55, at=tip, face=tip_face, near=False, rot=(25, yaw, 0), open=False, torn=False)
    if mode == "full":
        # 한 움큼 집힌 게이브들(작은 얼굴 다섯)
        for j, (x, y, z) in enumerate(((0, -2.3, 0), (0.55, -2.1, 0.3), (-0.5, -2.2, 0.35), (0.2, -2.6, -0.5), (-0.3, -1.85, -0.4))):
            s.head(size=0.62, depth=0.5, at=(x, y, z), face=("smile", "smile_a", "smile_c", "half", "smile")[j], near=False,
                   rot=(10 * j, 70 * j, 0), open=False, torn=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.55, mut2=False, torn=False)
    head_body(m.state(""), k)
    pm = k.model("g167_pole", poses=1, period=20, shrink=0.45, mut2=False, open=False, torn=False, rag=False)
    pole_body(pm.state(""))
    cm = k.model("g167_claw", poses=1, period=20, shrink=0.28, mut2=False, open=False, torn=False, rag=False)
    for name, mode in (("", "open"), ("shut", "shut"), ("full", "full"), ("man", "man"), ("cut", "cut")):
        st = cm.state(name)
        claw_body(st, mode)
        st.front = (0, 0, 0)
    sh = k.model("g167_shadow", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for name, dens in (("", 0.42), ("dark", 0.9)):
        st = sh.state(name)
        st.front = (0, 0, 0)
        st.quad((0, 0, 0), (2, 2), "up", k.texture("shadow_" + (name or "light"), shadow_tex(dens)))
    k.icon(model="g167_claw", state="")


def sounds(k: kit.Kit):
    k.sample("tune", "tag:electronic+beep", "삐리릭 집게 소리", pitch=0.9, attenuation=48)
    k.sample("clank", "tag:metal+slam", "덜컹", pitch=0.7, attenuation=40)
    k.sample("snap", "tag:breaking", "기둥이 끊어진다", pitch=0.7, attenuation=48)
    k.sample("drop", "tag:falling", "툭 떨어뜨림", pitch=0.8, attenuation=40)


def phrases():
    return {"cause_g167": "집게에 들려 떼 속에 버려졌다", "cause_g167_slam": "내려찍는 집게에 맞았다"}
