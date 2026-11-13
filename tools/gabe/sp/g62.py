"""G62 부채 게이브: 활짝 편 접부채. 가는 엔드 막대기 아홉이 부챗살로 한 점(사북)에서 퍼지고 그 위에 얼굴이 반달 꼴로 늘여 발렸다(폭 1.4).

상태: ""(편 채, 사북 아래 살 띠 술만 흔들림), "fan"(부채질: 사북을 축으로 앞뒤로 젖히는 네 자세, 8틱 한 바퀴), "shut"(접힘: 막대기 한 묶음과
주름째 구겨진 얼굴 한 줄). 팔랑이며 떨어지는 흔들림과 접혀 찌르는 기울기는 Java 가 몸 회전으로 한다.
모델 gave:gabe/g62_shadow: 바닥에 지는 점묘 반달 그림자(판 하나, Java 가 효과 디스플레이에 실음).
떼 종이라 변이 부위(입 찢어짐, 막대기 둘 더)는 끈다. 실눈 뜸은 반달로 늘인 뜬 눈 얼굴로 바꿔 보인다.
"""
import math

import numpy as np

from gabe import faces, kit

PIVOT = (0.0, -0.5, 0.0)     # 사북
R0, R1 = 0.24, 0.80          # 종이가 붙은 안쪽, 바깥쪽 반지름
SPAN = 62.0                  # 가운데에서 한쪽으로 펼친 각(도)
RIBS = 9
QW, QH = 1.44, 0.72          # 종이 판(가로, 세로): 반달이 꼭 들어가는 2:1 상자
QCY = PIVOT[1] + R1 - QH / 2  # 판 가운데 y(윗변이 바깥 호 꼭대기)
TILT = (-18.0, 12.0, 40.0, 12.0)   # 부채질 네 자세(도, + 는 앞으로 숙임)


def fan_tex(src, w, h, pleat=0.8):
    """얼굴 그림(정사각)을 반달 부채꼴로 늘여 바른다(가장 가까운 화소로 옮겨 화소 부류 비트를 지킴). 부챗살 자리마다 접힌 주름 그늘."""
    n = src.shape[0]
    out = np.zeros((h, w, 4), np.uint8)
    px, py = w / 2.0, (PIVOT[1] - (QCY - QH / 2)) / QH * h   # 사북의 화소 자리(아래에서 위로 잰 y)
    for y in range(h):
        for x in range(w):
            bx = (x + 0.5 - px) / w * QW
            by = ((h - (y + 0.5)) - py) / h * QH
            r = math.hypot(bx, by)
            if not (R0 <= r <= R1):
                continue
            th = math.degrees(math.atan2(bx, by))
            if abs(th) > SPAN:
                continue
            u = (th + SPAN) / (2 * SPAN)
            v = (R1 - r) / (R1 - R0)
            c = src[min(n - 1, int(v * n)), min(n - 1, int(u * n))].copy()
            if c[3] == 0:
                continue
            # 주름: 부챗살 사이 한가운데가 밝고 살 자리가 어둡다
            ph = (th + SPAN) / (2 * SPAN) * (RIBS - 1)
            fold = abs(ph - round(ph))
            if fold < 0.12:
                cls = int(c[2]) & 7
                rgb = np.clip(np.round(c[:3].astype(np.float32) * pleat), 0, 255).astype(np.uint8)
                c[:3] = rgb
                c[2] = (int(c[2]) & 0xF8) | cls
            c[3] = 255
            out[y, x] = c
    return out


def shadow_tex(n=32):
    """점묘 반달 그림자(컷아웃이라 반투명 대신 디더)"""
    out = np.zeros((n, n, 4), np.uint8)
    bayer = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0
    for y in range(n):
        for x in range(n):
            bx, by = (x + 0.5 - n / 2) / (n / 2), (n - (y + 0.5)) / n * 1.25 - 0.1
            r = math.hypot(bx, by)
            if not (0.28 <= r <= 1.0) or by < 0 or abs(math.degrees(math.atan2(bx, by))) > 66:
                continue
            edge = min(r - 0.28, 1.0 - r) / 0.36
            if 0.25 + 0.7 * edge > bayer[y % 4, x % 4]:
                out[y, x] = (124, 44, 70, 255)
    return out


def rib_dir(i, tilt=0.0):
    a = math.radians(-SPAN + 2 * SPAN * i / (RIBS - 1))
    t = math.radians(tilt)
    return (math.sin(a), math.cos(a) * math.cos(t), math.cos(a) * math.sin(t))


def paper(s, tex, tilt=0.0):
    """종이 판: 앞(먼 얼굴, 가까운 얼굴 x 실눈, 뜬 눈)과 뒤. tilt 가 있으면 사북을 축으로 숙임"""
    rot = dict(rot=(tilt, 0, 0), origin=PIVOT) if tilt else {}
    c = (0, QCY, 0.03)
    for cond, key in (("!open", "shut"), ("open", "open")):
        with s.when(cond):
            s.quad(c, (QW, QH), "south", tex[key], dist="far", face=True, **rot)
            s.quad(c, (QW, QH), "south", tex[key + "_near"], dist="near", face=True, **rot)
    s.quad((0, QCY, -0.03), (QW, QH), "north", tex["back"], uv=(16, 0, 0, 16), face=True, **rot)


def build(k: kit.Kit):
    smile, wide = faces.preset("smile"), faces.preset("open")
    tex = {
        "shut": k.texture("fan", fan_tex(faces.far(smile), 64, 32), classes=True),
        "shut_near": k.texture("fan_near", fan_tex(faces.near(smile), 192, 96), classes=True),
        "open": k.texture("fan_open", fan_tex(faces.far(wide), 64, 32), classes=True),
        "open_near": k.texture("fan_open_near", fan_tex(faces.near(wide), 192, 96), classes=True),
        "back": k.texture("fan_back", fan_tex(faces.skin_side("back"), 64, 32, pleat=0.72), classes=True),
    }
    m = k.model(poses=4, period=8, mut2=False, torn=False, open=False)

    # ---------------------------------------------------------------- 편 채(부챗살은 가만히 있는 구조 막대기)
    s = m.state("", period=20)
    paper(s, tex)
    for i in range(RIBS):
        s.rod(PIVOT, rib_dir(i), length=R1 + 0.07, segs=1, thick=0.04, base=False, toward=(0, 0, 1))
    s.box((-0.07, PIVOT[1] - 0.07, -0.07), (0.07, PIVOT[1] + 0.07, 0.07), "rod", uv=[2, 2, 6, 6])
    s.band((0, PIVOT[1] - 0.05, 0), (0, -1, 0), length=0.3, width=0.09, segs=2, thick=0.02,
           wave=kit.Wave(amp=(16, 24), lag=70), toward=(0, 0, 1), tex="flesh", poses=4)
    s.front = (0, 0, 1)

    # ---------------------------------------------------------------- 부채질(사북을 축으로 앞뒤로 네 자세)
    f = m.state("fan")
    f.box((-0.07, PIVOT[1] - 0.07, -0.07), (0.07, PIVOT[1] + 0.07, 0.07), "rod", uv=[2, 2, 6, 6])
    for fr in f.frames():
        with fr:
            t = TILT[fr.k]
            paper(f, tex, t)
            for i in range(RIBS):
                f.rod(PIVOT, rib_dir(i, t), length=R1 + 0.07, segs=1, thick=0.04, base=False, toward=(1, 0, 0) if i == (RIBS - 1) // 2 else (0, 0, 1))
    f.band((0, PIVOT[1] - 0.05, 0), (0, -1, 0), length=0.3, width=0.09, segs=2, thick=0.02,
           wave=kit.Wave(amp=(30, 40), lag=50), toward=(0, 0, 1), tex="flesh", poses=4)
    f.front = (0, 0, 1)

    # ---------------------------------------------------------------- 접힘(막대기 한 묶음, 주름째 구겨진 얼굴)
    c = m.state("shut", period=20)
    for dx in (-0.075, -0.025, 0.025, 0.075):
        c.rod((dx, PIVOT[1], 0.0), (dx * 0.3, 1, 0), length=R1 + 0.06, segs=1, thick=0.05, base=False, toward=(0, 0, 1))
    for cond, face in (("!open", smile), ("open", wide)):
        with c.when(cond):
            c.quad((0, PIVOT[1] + (R0 + R1) / 2, 0.04), (0.21, R1 - R0), "south", k.face_tex(face), face=True)
    c.quad((0, PIVOT[1] + (R0 + R1) / 2, -0.04), (0.21, R1 - R0), "north", k.skin_tex("back"), face=True)
    c.box((-0.07, PIVOT[1] - 0.07, -0.07), (0.07, PIVOT[1] + 0.07, 0.07), "rod", uv=[2, 2, 6, 6])
    c.band((0, PIVOT[1] - 0.05, 0), (0, -1, 0), length=0.3, width=0.09, segs=2, thick=0.02,
           wave=kit.Wave(amp=(10, 16), lag=70), toward=(0, 0, 1), tex="flesh", poses=4)
    c.front = (0, 0, 1)

    sh = k.model("g62_shadow", poses=1, mut2=False, open=False, torn=False, rag=False)
    sh.state("").quad((0, 0, 0), (1.0, 1.0), "up", k.texture("shadow", shadow_tex()))

    k.icon(model="g62", state="", view="front")


def sounds(k: kit.Kit):
    # 접부채 펴고 접는 '촤락': 종이 소리를 낮게(위에서 내려오는 신호, 부채질)
    k.sample("fan", "tag:paper", "부채 소리", pitch=0.8, attenuation=24)
    # 착 접힘(찌르기 직전)
    k.sample("snap", "tag:paper", "부채 접힘", pitch=1.45, volume=0.9, attenuation=16, n=2)


def phrases():
    return {"cause_g62": "부채에 찔렸다"}
