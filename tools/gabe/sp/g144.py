"""G144 소리 먹는 게이브(4막, 원거리, 방해): 얼굴 가죽을 깔때기 꼴로 말아 벌린 지름 2.6 블록 나팔. 나팔 안쪽 벽에 작은 얼굴들이 귀처럼 빙 둘러
붙었고 목구멍 깊숙이 0.8 블록 진짜 얼굴이 박혀 있다. 엔드 막대기 셋이 삼발이 다리로 나팔을 받치고(키 3.4), 막대기 둘이 나팔 테에 손잡이처럼
붙었다. 소리를 먹을수록 나팔 목(뒤 살 주머니)이 불룩해지고 테가 붉어진다(부위 fill1..fill3, 넷째 단계는 곧 내지름).

모델 gave:gabe/g144(shrink 0.5) 상태:
  ""      걷기: 삼발이 다리 셋 4자세
  "limp"  축 늘어짐: 나팔이 처지고 목구멍 얼굴이 주둥이 앞까지 밀려 나옴(눈 뜸)
  부위 fill1, fill2, fill3: 목 주머니가 불룩(단계마다 한 겹), 테에 붉은 고리
  부위 leg0, leg1, leg2: 그 다리가 꺾임(그루터기)
모델 gave:gabe/g144_fan: 바닥에 눕는 붉은 부채꼴 예고(뒤 끝 z = 0 에서 +Z 로 길이 1, 끝 반 폭 0.225 = 20 블록에서 4.5). Java 부품이 길이 20 으로 키움.
가운데(몸 자리) = 나팔 가운데, 바닥 위 2.2(Java HOVER 2.2).
소리 suck1..suck4(소리 빨리는 소리, 48, 단계마다 높아짐), blast(나팔 터지는 소리, 64), kick(다리 걷어차기, 16), limp(바람 빠지는 숨, 24).
"""
import math

import numpy as np

from gabe import kit

MOUTH_R = 1.3     # 주둥이 반지름
THROAT_R = 0.42   # 목구멍 반지름
LEN = 1.5         # 나팔 길이(목구멍 z = -0.9 에서 주둥이 z = 0.6)
Z0, Z1 = -0.9, 0.6
FOOT = -2.2
PANELS = 8


def red_tex(n=16, seed=144):
    rng = np.random.default_rng(seed)
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = np.array([196, 48, 52], np.float32) + rng.normal(0, 10, (n, n, 1))
    out[..., 3] = 255
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


def fan_tex(w=64, h=128):
    """부채꼴(아래쪽이 앞 +Z, 위쪽이 뒤 끝): 테두리 + 옅은 점묘, 컷아웃"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    zf = (yy + 0.5) / h                         # 0 뒤 끝 .. 1 앞 끝
    half = (0.065 + (0.225 - 0.065) * zf) / 0.225 * (w / 2)
    dx = np.abs(xx + 0.5 - w / 2)
    inside = dx <= half
    edge = inside & ((dx >= half - 2.2) | (yy >= h - 3) | (yy <= 1))
    dots = inside & (((xx.astype(int) // 3 + yy.astype(int) // 3) % 2) == 0) & (((xx.astype(int) % 3) == 1) & ((yy.astype(int) % 3) == 1))
    out = np.zeros((h, w, 4), np.uint8)
    out[dots] = (170, 30, 28, 255)
    out[edge] = (228, 44, 36, 255)
    return out


def horn(s, k, droop=0.0):
    """깔때기: 판 여덟(살) + 안쪽 귀 얼굴 + 테 막대기 + 손잡이 둘. droop 은 처지는 각(도)"""
    fl = k.part_tex("flesh")
    rim = []
    for i in range(PANELS):
        a = 2 * math.pi * i / PANELS
        a2 = 2 * math.pi * (i + 1) / PANELS
        # 판 하나: 목구멍 원 위 점에서 주둥이 원 위 점까지(두 점 사이 가운데 각)
        am = (a + a2) / 2
        r_in, r_out = THROAT_R, MOUTH_R
        p_in = np.array((math.cos(am) * r_in, math.sin(am) * r_in, Z0))
        p_out = np.array((math.cos(am) * r_out, math.sin(am) * r_out, Z1))
        d = p_out - p_in
        L = float(np.linalg.norm(d))
        wid = 2 * r_out * math.sin(math.pi / PANELS) * 1.08
        c = (p_in + p_out) / 2
        # 판: z 쪽 길이 L, 가로 wid, 두께 0.06. 바깥으로 벌어지는 각만큼 돌림
        tilt = math.degrees(math.atan2(r_out - r_in, LEN))
        roll = math.degrees(am) + 90
        s.box((c[0] - wid / 2, c[1] - 0.03, c[2] - L / 2), (c[0] + wid / 2, c[1] + 0.03, c[2] + L / 2), fl, rot=(tilt + droop, 0, roll), origin=tuple(c),
              face=True)
        rim.append((math.cos(a) * r_out * 1.02, math.sin(a) * r_out * 1.02, Z1))
    # 안쪽 벽의 귀 얼굴(작은 얼굴 여섯, 안쪽을 봄)
    for i in range(6):
        a = 2 * math.pi * (i + 0.5) / 6
        r = (THROAT_R + MOUTH_R) / 2 * 0.86
        c = (math.cos(a) * r, math.sin(a) * r, (Z0 + Z1) / 2 + 0.1)
        s.quad(c, (0.36, 0.36), "south", k.face_tex(("smile_a", "smile", "smile_c")[i % 3]), face=True)
    # 테: 막대기 고리(조각 여덟), 붉은 고리(단계마다 한 겹 더)
    for i in range(PANELS):
        a, b = np.array(rim[i]), np.array(rim[(i + 1) % PANELS])
        s.rod(tuple(a), tuple(b - a), length=float(np.linalg.norm(b - a)), segs=1, thick=0.07, base=False, rag=False, toward=(0, 0, 1))
    red = k.texture("rim_red", red_tex())
    for j, part in enumerate(("fill1", "fill2", "fill3")):
        with s.when("on:" + part):
            rr = MOUTH_R * (1.05 + 0.05 * j)
            for i in range(j % 2, PANELS, 2):
                a, a2 = 2 * math.pi * i / PANELS, 2 * math.pi * (i + 1) / PANELS
                p, q = np.array((math.cos(a) * rr, math.sin(a) * rr, Z1 - 0.02 * j)), np.array((math.cos(a2) * rr, math.sin(a2) * rr, Z1 - 0.02 * j))
                s.rod(tuple(p), tuple(q - p), length=float(np.linalg.norm(q - p)), segs=1, thick=0.09, base=False, rag=False, toward=(0, 0, 1),
                      tex=red)
    # 손잡이 둘(테 위 양옆)
    for side in (1, -1):
        s.rod((side * MOUTH_R * 0.7, MOUTH_R * 0.72, Z1 - 0.1), (side * 0.3, 0.6, -1), length=0.7, segs=1, thick=0.08, base=False, rag=False)


def neck(s, k):
    """목 주머니(나팔 뒤): 기본 한 겹 + 단계마다 불룩"""
    fl = k.part_tex("flesh")
    s.box((-0.4, -0.4, Z0 - 0.7), (0.4, 0.4, Z0 + 0.05), fl, face=True)
    for j, part in enumerate(("fill1", "fill2", "fill3")):
        r = 0.5 + 0.13 * j
        with s.when("on:" + part):
            s.box((-r, -r, Z0 - 0.6 - 0.1 * j), (r, r, Z0 - 0.05), fl, face=True)


def legs(s, walk):
    for i in range(3):
        a = math.radians(90 + 120 * i)
        root = (math.cos(a) * 0.35, -0.45, Z0 - 0.2 + math.sin(a) * 0.35)
        foot = (math.cos(a) * 1.25, FOOT, Z0 - 0.2 + math.sin(a) * 1.25)
        d = np.array(foot) - np.array(root)
        part = f"leg{i}"
        with s.when("!on:" + part):
            w = kit.Wave(amp=16, phase=120 * i) if walk else None
            s.rod(root, tuple(d), length=float(np.linalg.norm(d)), segs=1, thick=0.12, wave=w, toward=(0, 0, 1) if abs(d[2]) < 0.9 * np.linalg.norm(d) else (1, 0, 0),
                  poses=4 if walk else None, rag=walk)
        with s.when("on:" + part):
            s.rod(root, tuple(d), length=0.6, segs=1, thick=0.12, base=False, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, shrink=0.5, mut2=False)
    s = m.state("")
    horn(s, k)
    neck(s, k)
    s.head(size=0.8, depth=0.5, at=(0, 0, Z0 + 0.1), face="smile")
    legs(s, True)

    lp = m.state("limp", poses=1)
    horn(lp, k)
    neck(lp, k)
    lp.head(size=1.0, depth=0.5, at=(0, -0.1, Z1 - 0.15), face="torn")
    legs(lp, False)

    f = k.model("g144_fan", poses=1, mut2=False, open=False, torn=False, rag=False)
    fs = f.state("")
    fs.quad((0, 0, 0.5), (0.45, 1.0), "up", k.texture("fan", fan_tex()))
    fs.front = (0, 0, 0)


def sounds(k: kit.Kit):
    for i, p in enumerate((0.6, 0.78, 0.98, 1.25)):
        k.sample(f"suck{i + 1}", "oga_steam/steam_hisses_marker_*", "소리 빨리는 소리", pitch=p, attenuation=48)
    k.sample("blast", "tag:boom", "나팔 터지는 소리", pitch=0.6, attenuation=64)
    k.sample("kick", "kenney_impact/impactpunch_heavy_00*", "다리 걷어차기", pitch=0.8, attenuation=16)
    k.sample("limp", "lrsf_horror/breath_scared_breath_scared_0*", "바람 빠지는 숨", pitch=0.55, attenuation=24)


def phrases():
    return {"cause_g144": "나팔이 내지른 소리에 날아갔다", "cause_g144_kick": "삼발이 다리에 걷어차였다"}
