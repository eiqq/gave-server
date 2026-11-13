"""G05 혹 터지는 게이브: 기본 얼굴(0.8 블록)이 조금 올라가 있고, 그 밑에 속에서 밀어 올린 손톱만 한 실눈 얼굴 혹들이 자루처럼 뭉쳐 매달렸다
(조롱박 꼴, 폭 1.2, 키 1.7). 짧은 막대기 둘은 정수리 양옆에서 더듬이처럼 빠르게 떤다(원형 DNA).

모델 gave:gabe/g05: 상태 ""(혹 여섯, 숨 쉬듯 꿈틀), "swell"(혹 여덟, 크게), "full"(혹 아홉, 얼굴보다 넓게 부풀어 곧 터짐), "skin"(터진 뒤 남은
얼굴 가죽: 위를 보고 누운 얇은 판, 시체가 납작해져도 보이게). 꿈틀은 자세 4장(모델 자세 6, 12틱 한 바퀴). Java 가 부풀 때 몸을 원점 기준으로
1.18 배까지 키우므로 자루 아래 끝은 그 배율에서도 땅(hover 0.85) 위에 오게 둔다.
모델 gave:gabe/g05_shard(터진 뒤 쏟아진 작은 얼굴 조각들, 보조 개체 G05Shards): 상태 ""(조각 여섯이 기어 모임), "puddle"(작은 분홍 웅덩이,
가장자리에 짧은 막대기 둘이 펄럭임).
덮개 g05_juice: 즙을 맞으면 화면 가장자리에 분홍 즙이 튐.
"""
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from gabe import audio, faces, kit

SKIN = np.array([255, 214, 206], float)
HOVER = 0.85
# 상태: (혹 수, 혹 크기, 씨앗, 얼굴 크기, 그 상태에서 Java 가 키우는 최대 배율)
SACS = (("", 6, 0.44, 11, 0.8, 1.0), ("swell", 8, 0.5, 12, 0.8, 1.09), ("full", 9, 0.58, 13, 0.88, 1.18))
SAC_C = (0.0, -0.36, 0.08)  # 자루 가운데
FEELER = kit.Wave(amp=(12, 16), lag=60, cycles=2)


def bulge_tex():
    """피부 밑에 비치는 작은 실눈 얼굴(살빛으로 흐림 + 늘어난 가장자리 붉은 기)"""
    f = faces.far("smile").astype(float)
    rgb = f[..., :3] * 0.45 + SKIN * 0.55
    yy, xx = np.mgrid[0:32, 0:32]
    r = np.hypot(xx - 15.5, yy - 15.5) / 16
    rim = np.clip((r - 0.55) / 0.45, 0, 1)[..., None]
    rgb = rgb * (1 - rim * 0.35) + np.array([214, 120, 128]) * rim * 0.35
    out = np.zeros((32, 32, 4), np.uint8)
    out[..., :3] = np.clip(rgb, 0, 255)
    out[..., 3] = 255
    return out


def puddle_tex(seed=5):
    rng = np.random.RandomState(seed)
    n = 32
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    ang = np.arctan2(yy - 15.5, xx - 15.5)
    wob = 1 + 0.12 * np.sin(ang * 3 + 0.7) + 0.08 * np.sin(ang * 5 + 2.1)
    r = np.hypot(xx - 15.5, yy - 15.5) / (15.0 * wob)
    out = np.zeros((n, n, 4), np.uint8)
    inside = r < 1.0
    base = np.array([236, 120, 142], float)
    shade = base * (0.82 + 0.18 * (1 - r))[..., None]
    spots = rng.rand(n, n) < 0.06
    shade[spots] = [255, 196, 206]
    shade[(r > 0.85) & inside] = [190, 70, 96]
    out[..., :3] = np.clip(shade, 0, 255)
    out[..., 3] = np.where(inside, 255, 0)
    return out


def sac_spots(n, big, seed, grow):
    """자루를 이루는 혹 자리: 위는 좁고 가운데가 넓은 조롱박(위 셋, 가운데 고리, 맨 아래 하나). 아래 끝은 Java 배율에서도 땅 위"""
    rng = np.random.RandomState(seed)
    floor = -HOVER / grow + 0.1  # 이보다 아래로 내려가면 부풀 때 땅에 박힘
    spots = []
    for i in range(n):
        if i == n - 1:
            a, r, y = rng.uniform(0, 2 * math.pi), 0.05, SAC_C[1] - 0.2
        elif i < 3:
            a, r, y = 2 * math.pi * i / 3 + rng.uniform(-0.4, 0.4), rng.uniform(0.12, 0.2), SAC_C[1] + 0.1
        else:
            a, r, y = 2 * math.pi * (i - 3) / (n - 4) + rng.uniform(-0.3, 0.3), rng.uniform(0.26, 0.38), SAC_C[1] - 0.05
        sz = big * rng.uniform(0.8, 1.05)
        y = max(y + rng.uniform(-0.05, 0.05), floor + sz / 2 * 1.1)
        x, z = math.cos(a) * r, SAC_C[2] + math.sin(a) * r * 0.7
        spots.append((x, y, z, sz, rng.rand() * 2 * math.pi, rng.uniform(-22, 22), rng.uniform(-14, 14)))
    return spots


def sac_body(k, s, n, big, seed, hsize, grow):
    """얼굴(조금 올라감) + 밑에 매달린 혹 자루(자세 4로 숨 쉬듯 부풂) + 정수리 더듬이 둘"""
    h = s.head(size=hsize, depth=0.6, at=(0, 0.24, 0.06), face="smile")
    tex = k.texture("bulge", bulge_tex())
    flesh = k.part_tex("flesh")
    sides = {"south": tex, "east": flesh, "west": flesh, "down": flesh, "north": flesh}
    spots = sac_spots(n, big, seed, grow)
    for f in s.frames(poses=4):
        with f:
            for (x, y, z, sz, ph, yaw, roll) in spots:
                w = math.sin(2 * math.pi * f.t + ph)
                hs = sz / 2 * (1 + 0.1 * w)
                c = np.array((x, y, z))
                s.box(tuple(c - hs), tuple(c + hs), sides, faces=("south", "east", "west", "down", "north"), uv=[0, 0, 16, 16],
                      rot=(0, yaw, roll), origin=tuple(c), face=True)
    for side in (1, -1):
        w = kit.Wave(amp=FEELER.amp, lag=FEELER.lag, cycles=FEELER.cycles, phase=0 if side > 0 else 90)
        s.rod(h.point(side * 0.75, 0.9, -0.4), (side * 0.55, 1.0, -0.35), length=0.45, segs=2, thick=0.08, wave=w, toward=(side, 0, 0),
              poses=4)
    return h


def build(k: kit.Kit):
    m = k.model(poses=6, period=12)
    m.mut2 = dict(root=(0.3, 0.6, -0.25), dir=(0.5, 1.0, -0.6), length=0.6)
    for name, n, big, seed, hsize, grow in SACS:
        sac_body(k, m.state(name), n, big, seed, hsize, grow)
    # 터진 뒤 남은 얼굴 가죽(위를 봄, 가장자리 날살)
    sk = m.state("skin")
    face_far = k.face_tex("torn")
    face_near = k.face_tex("torn", near=True)
    sk.box((-0.42, -0.02, -0.42), (0.42, 0.0, 0.42), {"up": face_far}, faces=("up",), uv=[0, 0, 16, 16], dist="far", face=True)
    sk.box((-0.42, -0.02, -0.42), (0.42, 0.0, 0.42), {"up": face_near}, faces=("up",), uv=[0, 0, 16, 16], dist="near", face=True)
    flesh = k.part_tex("flesh")
    for i, (x0, z0, x1, z1) in enumerate(((-0.5, -0.5, 0.5, -0.42), (-0.5, 0.42, 0.5, 0.5), (-0.5, -0.42, -0.42, 0.42), (0.42, -0.42, 0.5, 0.42))):
        sk.box((x0, -0.025, z0), (x1, -0.005, z1), flesh, faces=("up",), uv=[0, 0, 16, 2])
    for side in (1, -1):
        sk.rod((side * 0.3, 0.0, -0.4), (side * 0.6, 0.15, -1.0), length=0.4, segs=2, thick=0.08)

    sh = k.model("g05_shard", poses=4, period=8, mut2=False, torn=False)
    crawl = sh.state("")
    rng = np.random.RandomState(21)
    pieces = []
    for i in range(6):
        a = i / 6 * 2 * math.pi + rng.uniform(-0.3, 0.3)
        r = rng.uniform(0.35, 0.8)
        pieces.append((math.cos(a) * r, math.sin(a) * r, rng.uniform(0, 2 * math.pi), rng.uniform(-60, 60)))
    ft = k.face_tex("smile")
    for f in crawl.frames():
        with f:
            for (x, z, ph, yaw) in pieces:
                w = math.sin(2 * math.pi * f.t + ph)
                ln = 0.2 * (1 + 0.25 * w)
                c = np.array([x, 0.035, z])
                # 눈 반쪽이 남은 얼굴 조각(위를 봄), 자세마다 늘었다 줄었다(자벌레)
                crawl.box(tuple(c - np.array([0.1, 0.03, ln / 2])), tuple(c + np.array([0.1, 0.03, ln / 2])),
                          {"up": ft, "north": flesh, "south": flesh, "east": flesh, "west": flesh},
                          faces=("up", "north", "south", "east", "west"), uv=[2, 4, 8, 10], rot=(0, yaw, 0), origin=tuple(c))
    pud = sh.state("puddle")
    pt = k.texture("puddle", puddle_tex())
    pud.box((-0.8, 0.0, -0.8), (0.8, 0.03, 0.8), {"up": pt}, faces=("up",), uv=[0, 0, 16, 16], face=True)
    for (x, z) in ((0.25, -0.15), (-0.3, 0.2)):
        pud.box((x - 0.12, 0.0, z - 0.12), (x + 0.12, 0.05, z + 0.12), {"up": k.face_tex("smile")}, faces=("up",), uv=[0, 0, 16, 16],
                face=True)
    for side in (1, -1):
        pud.rod((side * 0.62, 0.02, -0.35), (side * 0.6, 0.55, -0.5), length=0.45, segs=2, thick=0.07,
                wave=kit.Wave(amp=(18, 22), lag=60, shape="flap", phase=0 if side > 0 else 120), toward=(-side, 0.3, 0))

    k.cover("g05_juice", juice_cover())


def juice_cover(seed=3, size=(480, 270)):
    W, H = size
    rng = np.random.RandomState(seed)
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for i in range(26):
        edge = rng.randint(4)
        if edge == 0:
            x, y = rng.uniform(0, W), rng.uniform(-10, 40)
        elif edge == 1:
            x, y = rng.uniform(0, W), rng.uniform(H - 50, H + 10)
        elif edge == 2:
            x, y = rng.uniform(-10, 50), rng.uniform(0, H)
        else:
            x, y = rng.uniform(W - 50, W + 10), rng.uniform(0, H)
        r = rng.uniform(10, 30)
        col = (232, 110 + rng.randint(30), 140 + rng.randint(30), 215)
        d.ellipse((x - r, y - r, x + r, y + r), fill=col)
        if edge in (0, 2, 3) and rng.rand() < 0.7:
            L = rng.uniform(25, 80)
            d.rounded_rectangle((x - r * 0.25, y, x + r * 0.25, y + L), radius=int(r * 0.25), fill=col)
            d.ellipse((x - r * 0.35, y + L - r * 0.35, x + r * 0.35, y + L + r * 0.35), fill=col)
    return im.filter(ImageFilter.GaussianBlur(1.2))


def sounds(k: kit.Kit):
    # 끽끽: 피부가 비닐처럼 늘어나는 소리(멈추면 커지고 빨라짐)
    k.sound("squeak", "끽끽", vanilla=[("block/honeyblock/slide2", 1.7), ("block/honeyblock/slide4", 1.8)],
            synth=lambda i: squeak(seed=500 + i), volume=0.85, attenuation=20)
    # 터짐: 즙이 퍼지는 '퍽'
    k.sound("burst", "혹이 터짐", vanilla=[("mob/slime/big3", 0.6), ("mob/pufferfish/blow_out2", 0.7)],
            synth=lambda i: audio.squish(seed=510 + i, dur=0.45), volume=1.0, attenuation=24)
    # 조각이 밟힘
    k.sound("stomp", "철벅", vanilla=[("mob/slime/small3", 0.9), ("block/honeyblock/step2", 0.8)], volume=0.7, attenuation=12)


def squeak(seed=0, dur=0.32):
    rng = np.random.RandomState(seed)
    f0 = 700 + 120 * rng.rand()
    x = audio.tone(f0, dur, "saw", vib=0.06, vib_rate=31, glide=f0 * 1.6)
    x = audio.bandpass(x, 600, 3200)
    n = audio.bandpass(audio.noise(dur, seed), 1500, 5000) * 0.25
    return audio.normalize(audio.fade(audio.env(audio.mix(x * 0.7, n), 0.01, 0.05, 0.7, 0.08)), 0.7)


def phrases():
    return {"cause_g05": "혹이 터진 즙에 녹았다"}
