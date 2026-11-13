"""G43 탐조등 게이브: 거대한 새장 등불(2.2 배). 실눈이 완전히 뜨인 2.6 블록 얼굴(핏발 선 흰자, 바늘 동공, 점 입과 홍조는 그대로)을 짙은 쇠창살
새장(높이 3.4, 지름 3.2)이 둘러싸고(앞 가운데는 비워 눈과 빛이 나옴), 새장 위로 쇠사슬 고리가 매달려 하늘에 걸린 감옥 감시등처럼 보인다.
엔드 막대기 둘(원형 DNA)이 새장 밖에서 얼굴 양옆을 감싸는 등불 갓. 눈에서 연노랑 원뿔 빛이 뻗어 바닥을 쓴다.

- gave:gabe/g43: 상태 ""(눈 뜸, 빛 켜짐), "dark"(동공을 맞아 3초 눈 감음). 틀 ±1.5 블록을 넘으므로 0.5 로 줄여 굽는다(엔진이 Look.scale 로
  되돌림). 빛 시작(Java EYE_Z)과 동공 약점(PUPIL_*)은 이 얼굴 크기에 맞춘다.
- gave:gabe/g43_beam(부품): 빛 원뿔. 길이 1.5, 끝 반지름 0.5 짜리 단위 원뿔(축 +Z)이고 Java 가 길이와 굵기를 늘린다. 반투명 대신
  성긴 빛살 무늬(컷아웃)를 축을 지나는 판 넷에 입힌다.
- gave:gabe/g43_spot(부품): 바닥 빛 자리(지름 1 짜리 판, Java 가 늘림). 테두리 고리가 또렷해 어디까지가 빛인지 보인다.
- gave:gabe/g43_mask: 빛을 쬔 사람 머리에 씌우는 것(남들 눈에만, 머리 칸 모델). 상태 "s1"(얼굴에 작은 게이브 얼굴 조각), "s2"(얼굴을 다 덮음),
  "s3"(머리 전체가 게이브 얼굴). 모두 노란 빛 테두리. 머리 칸 아이템은 모델 -Z 가 사람 얼굴 쪽이라 얼굴을 북쪽 면에 둔다.
- 덮개 g43_v1, g43_v2: 빛을 쬔 본인 화면 가장자리부터 번지는 분홍 살(가운데는 비침).
"""
import math

import numpy as np
from PIL import Image

from gabe import audio, faces, kit

FACE = 2.6
DEPTH = 1.75
# 새장: 창살 고리 반지름(앞뒤는 0.85 배), 높이, 창살 굵기. 등불 갓 막대기 길이
CAGE_R, CAGE_H, BAR = 1.62, 3.4, 0.14
SHADE = 2.56
LIGHT = (255, 244, 170)
LIGHT_EDGE = (255, 226, 120)


def _hash(a, b, seed=0):
    v = math.sin(a * 127.1 + b * 311.7 + seed * 74.7) * 43758.5453
    return v - math.floor(v)


def beam_tex(w=32, h=128):
    """빛 원뿔 한 판: 위(v=0)가 눈 쪽 꼭짓점, 아래로 갈수록 넓어짐. 가운데는 촘촘하고 가장자리로 갈수록 성긴 세로 빛살(짧은 줄), 가장자리 선"""
    im = np.zeros((h, w, 4), np.uint8)
    c = (w - 1) / 2
    for y in range(h):
        hw = 1.2 + (c - 0.2) * (y / (h - 1)) ** 0.92
        for x in range(w):
            d = abs(x - c) / hw
            if d > 1.0:
                continue
            dens = 0.36 * (1 - d * d) ** 0.6 + 0.05
            edge = d > 0.88
            # 빛살: 세로로 6화소 묶음이 같이 켜짐(열마다 어긋남)
            n = _hash(x, (y + x * 5) // 6, 3)
            if n < dens or (edge and _hash(x, y // 3, 9) < 0.55):
                col = LIGHT_EDGE if edge else LIGHT
                im[y, x] = (*col, 255)
    return faces._fill_rest(im)


def spot_tex(n=64):
    """바닥 빛 자리: 또렷한 테두리 고리 + 안쪽 성긴 점"""
    im = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    bayer = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r > 1.0:
                continue
            if r > 0.9:
                if r > 0.93 or (x + y) % 2 == 0:
                    im[y, x] = (*LIGHT_EDGE, 255)
                continue
            dens = 0.14 + 0.16 * (1 - r)
            if dens > bayer[y % 4, x % 4]:
                im[y, x] = (*LIGHT, 255)
    return faces._fill_rest(im)


def plain(rgb):
    im = np.zeros((4, 4, 4), np.uint8)
    im[...] = (*rgb, 255)
    return im


def vignette(r0, W=480, H=270, seed=5):
    """화면 가장자리부터 번지는 분홍 살: 가운데(r < r0)는 비치고 가장자리로 갈수록 짙은 살 주름"""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    dx, dy = (xx - W / 2) / (W / 2), (yy - H / 2) / (H / 2)
    r = np.sqrt(dx * dx * 0.8 + dy * dy)
    a = np.arctan2(dy, dx)
    ridge = np.sin(a * 16 + np.sin(r * 8.0) * 2.0 + r * 4.0)
    t = (ridge * 0.5 + 0.5)[..., None]
    col = np.array([150, 60, 74], np.float32) * (1 - t) + np.array([246, 170, 172], np.float32) * t
    # 살 안쪽 가장자리는 따뜻한 노란 빛이 스밈
    warm = np.clip(1 - (r - r0) / 0.25, 0, 1)[..., None]
    col = col * (1 - 0.35 * warm) + np.array([255, 236, 150], np.float32) * 0.35 * warm
    rng = np.random.RandomState(seed)
    wob = 0.03 * np.sin(a * 7 + 1.3) + 0.02 * np.sin(a * 13)
    alpha = np.clip((r + wob - r0) / max(0.05, (1.12 - r0) * 0.55), 0, 1) ** 1.3
    out = np.zeros((H, W, 4), np.float32)
    out[..., :3] = col + rng.randn(H, W, 1) * 3
    out[..., 3] = alpha * 255
    return Image.fromarray(np.clip(np.round(out), 0, 255).astype(np.uint8))


def bar_tex(n=16, seed=43):
    """새장 쇠창살: 짙은 쇠 빛(세로 결), 군데군데 녹 반점과 밝은 모서리"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    col = np.array([82, 76, 86], float) * (0.85 + 0.15 * np.cos(xx * 1.4))[..., None] + rng.randn(n, n, 1) * 4
    col[:, 0] = (128, 122, 134)
    rust = rng.rand(n, n) < 0.07
    col[rust] = (110, 56, 40)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def cage(s, bar):
    """얼굴 둘레 쇠창살 새장: 세로 창살 여덟(앞 가운데 둘은 비워 눈과 빛이 나옴), 위아래 고리 각 10 토막, 위에 매단 쇠사슬"""
    R, Y = CAGE_R, CAGE_H / 2
    t = BAR / 2
    pts = []
    for i in range(10):
        a = 2 * math.pi * i / 10
        pts.append((R * math.sin(a), R * math.cos(a) * 0.85))
    for (x, z) in pts:
        if z > 0.9 and abs(x) < 0.9:
            continue  # 앞 가운데(눈, 빛)는 비움
        s.box((x - t, -Y, z - t), (x + t, Y, z + t), bar, uv=[0, 0, 4, 16])
    for y in (-Y, Y):
        for i in range(10):
            p0, p1 = np.array((pts[i][0], y, pts[i][1])), np.array((pts[(i + 1) % 10][0], y, pts[(i + 1) % 10][1]))
            c = (p0 + p1) / 2
            L = float(np.linalg.norm(p1 - p0)) + BAR
            yaw = math.degrees(math.atan2(p1[0] - p0[0], p1[2] - p0[2]))
            s.box(tuple(c - (t, t, L / 2)), tuple(c + (t, t, L / 2)), bar, uv=[0, 0, 4, 16], rot=(0, yaw, 0), origin=tuple(c))
    s.chain((0, Y, 0), (0, Y + 0.7, 0), links=3, sag=0.0, size=0.16)


def build(k: kit.Kit):
    # 미리 깔리는 종은 오래 살면 변함 규칙에서 빠진다: 변이 부위를 끔. 눈은 늘 떠 있어 실눈 뜸 오버레이도 없음
    m = k.model(poses=4, period=40, mut2=False, open=False, torn=False, shrink=0.5)
    bar = k.texture("bar", bar_tex())
    for name, face in (("", "bloodshot"), ("dark", "smile")):
        s = m.state(name)
        h = s.head(size=FACE, depth=DEPTH, face=face)
        cage(s, bar)
        # 등불 갓(원형 DNA): 뒤통수 위 양옆에서 옆으로 나와 새장 밖에서 얼굴 옆을 감싸 앞으로 굽는다(끝이 까딱임)
        for side in (1, -1):
            s.rod(h.point(side * 0.7, 0.75, -0.8), (side * 1.0, 0.12, 0.05), length=SHADE, segs=3, thick=0.2,
                  wave=kit.Wave(amp=(4, 7, 10), lag=45, bias=(0, 52, 44), phase=0 if side > 0 else 60), toward=(0, 0, 1))

    b = k.model("g43_beam", poses=1, period=20, mut2=False, open=False, torn=False)
    bt = k.texture("beam", beam_tex())
    bs = b.state("")
    for ang in (0, 45, 90, 135):
        # 세로 판(폭 1, 길이 1.5)을 X 로 눕혀 +Z 축에 놓고 축 둘레로 돌림. v 를 뒤집어 꼭짓점(v=0)이 눈 쪽
        bs.box((-0.5, 0, 0), (0.5, 1.5, 0), bt, uv=[0, 16, 16, 0], faces=("south", "north"), rot=(90, 0, ang), origin=(0, 0, 0), shade=False)

    sp = k.model("g43_spot", poses=1, period=20, mut2=False, open=False, torn=False)
    sp.state("").box((-0.5, 0, -0.5), (0.5, 0, 0.5), k.texture("spot", spot_tex()), uv=[0, 0, 16, 16], faces=("up",), shade=False)

    mk = k.model("g43_mask", poses=1, period=20, mut2=False, open=False, torn=False)
    rim = k.texture("rim", plain(LIGHT_EDGE))
    face32 = k.face_tex("smile")

    def frame(s, size, z):
        t = 0.05
        for (cx, cy, w, hh) in ((0, size / 2 + t / 2, size + 2 * t, t), (0, -size / 2 - t / 2, size + 2 * t, t),
                                (size / 2 + t / 2, 0, t, size), (-size / 2 - t / 2, 0, t, size)):
            s.box((cx - w / 2, cy - hh / 2, z), (cx + w / 2, cy + hh / 2, z), rim, uv=[0, 0, 4, 4], faces=("north",), shade=False)

    for name, size, z in (("s1", 0.44, -0.415), ("s2", 0.74, -0.425)):
        s = mk.state(name)
        s.box((-size / 2, -size / 2, z), (size / 2, size / 2, z), face32, uv=[0, 0, 16, 16], faces=("north",), face=True)
        frame(s, size, z)
    s3 = mk.state("s3")
    s3.head(size=0.9, depth=0.9, face="smile", rot=(0, 180, 0), near=False)
    frame(s3, 0.9, -0.46)
    mk.default = "s1"

    k.cover("g43_v1", vignette(0.78))
    k.cover("g43_v2", vignette(0.5))
    k.icon(model="g43", state="")


def _hum(seed):
    """낮은 형광등 웅웅: 100 Hz 와 배음, 가늘게 떨리는 잡음(멀리 들림)"""
    d = 2.0
    f = 100 + seed
    a = audio.tone(f, d, "saw") * 0.25 + audio.tone(f * 2, d, "square") * 0.08 + audio.tone(f * 3, d) * 0.12
    a = audio.lowpass(a, 900)
    flick = 0.82 + 0.18 * np.sign(np.sin(np.linspace(0, 2 * math.pi * 50, len(a), dtype=np.float32)))
    buzz = audio.bandpass(audio.noise(d, 470 + seed), 3000, 6000) * 0.04
    return audio.normalize(audio.fade(a * flick + buzz, 0.15, 0.25), 0.7)


def _birds(seed):
    """새소리(빛이 지날 때 따뜻한 햇살처럼 들리는 것이 위험 신호): 짧게 솟는 높은 지저귐 여럿"""
    rng = np.random.RandomState(475 + seed)
    d = 1.6
    out = audio.silence(d)
    t = 0.05
    while t < d - 0.2:
        f = 2400 + rng.rand() * 1600
        n = 2 + rng.randint(0, 3)
        for i in range(n):
            c = audio.env(audio.tone(f, 0.055, glide=f * (1.25 + 0.2 * rng.rand()), vib=0.02, vib_rate=40), 0.004, 0.015, 0.5, 0.025)
            out = audio.mix(out, audio.at(c * 0.45, t + i * 0.075, d))
        t += 0.3 + rng.rand() * 0.35
    return audio.normalize(audio.fade(out, 0.01, 0.1), 0.6)


def _bake(seed):
    """다 차서 굳음: 달궈지는 높은 울림이 솟다 뚝 끊김"""
    a = audio.tone(520, 0.7, glide=1500, vib=0.01) * 0.4
    b = audio.env(audio.bandpass(audio.noise(0.7, 480 + seed), 2000, 6000), 0.3, 0.1, 0.8, 0.02) * 0.25
    return audio.normalize(audio.fade(audio.env(audio.mix(a, b), 0.1, 0.1, 0.9, 0.02), 0.02, 0.01), 0.8)


def sounds(k: kit.Kit):
    # 이 종의 신호: 멀리서도 들리는 낮은 형광등 웅웅
    k.sound("hum", "형광등 웅웅", synth=_hum, volume=0.9, attenuation=72)
    # 빛 안에 든 본인에게만: 새소리(편안한 것이 위험 신호)
    k.sound("birds", "새소리", synth=_birds, volume=0.7, attenuation=12)
    k.sound("off", "빛이 꺼짐", vanilla=[("block/beacon/deactivate", 1.6)], volume=0.8, attenuation=48)
    k.sound("on", "빛이 켜짐", vanilla=[("block/beacon/power1", 1.5), ("block/beacon/power2", 1.5)], volume=0.7, attenuation=48)
    k.sound("bake", "빛에 굳음", synth=_bake, volume=0.9, attenuation=24)


def phrases():
    return {"cause_g43": "빛을 너무 오래 쬐었다", "notice_g43_full": "빛에 굳었다"}
