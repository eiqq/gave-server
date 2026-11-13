"""G06 침 뱉는 게이브: 쪼그려 앉은 두꺼비 꼴. 얼굴(0.75 블록, 한쪽 볼만 부풂)이 앞 위에 있고, 그 뒤 아래로 얼굴보다 넓은 사마귀 살 등,
턱밑에 부푸는 목주머니, 짧은 살 앞다리 둘, 엉덩이 옆에서 앞으로 나와 무릎에서 꺾이는 뒷다리 둘(원형 DNA 엔드 막대기, 숨 쉬듯 움찔).

모델 gave:gabe/g06: 상태 ""(웅크림: 뒷다리가 천천히 들썩), "puff"(양 볼과 목주머니가 부풂, 입을 오므림), "spit"(뱉는 순간 입 벌림, 목주머니 꺼짐).
  젖힘(pitch -30)과 숙임(+25)은 Java 가 몸 전체를 기울인다(두꺼비가 상체를 들었다 숙이는 꼴).
모델 gave:gabe/g06_glob(침 덩이, 부품 디스플레이): 상태 ""(날아가는 분홍 덩이, 출렁), "splat"(떨어진 자리 끈적이 원판 지름 1.8).
  (이름이 g06_spit 이면 몸 모델의 상태 "spit" 과 모델 파일 이름 g06_spit.json 이 겹쳐 뱉는 순간 몸이 침 덩이로 그려진다. 그래서 glob.)
덮개 g06_drip: 맞으면 화면 가장자리로 분홍 침 방울이 흘러내림.
"""
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from gabe import audio, faces, kit

# 상태: (얼굴, 볼(쪽, 크기), 목주머니 폭, 뒷다리 물결 폭, 빠르기 배)
TOAD = {
    "": ("smile", [(-1, 0.3)], 0.3, (4, 6), 1),
    "puff": (faces.Face(eyes="half", mouth="dot", blush="deep", name="g06_puff"), [(-1, 0.42), (1, 0.34)], 0.56, (5, 7), 2),
    "spit": (faces.Face(eyes="squint", mouth="teeth_ring", name="g06_spit"), [(-1, 0.22)], 0.2, (3, 4), 1),
}


def goo_tex(seed=7, base=(240, 132, 160)):
    rng = np.random.RandomState(seed)
    out = np.zeros((16, 16, 4), np.uint8)
    yy, xx = np.mgrid[0:16, 0:16]
    shade = np.array(base, float) * (0.86 + 0.14 * np.cos((xx + yy) / 5.0))[..., None]
    hi = (rng.rand(16, 16) < 0.08)
    shade[hi] = (255, 214, 224)
    shade[(xx - 4) ** 2 + (yy - 4) ** 2 < 5] = (255, 230, 236)
    out[..., :3] = np.clip(shade, 0, 255)
    out[..., 3] = 255
    return out


def splat_tex(seed=9):
    rng = np.random.RandomState(seed)
    n = 32
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    ang = np.arctan2(yy - 15.5, xx - 15.5)
    wob = 1 + 0.16 * np.sin(ang * 4 + 1.3) + 0.1 * np.sin(ang * 7 + 0.4)
    r = np.hypot(xx - 15.5, yy - 15.5) / (14.0 * wob)
    out = np.zeros((n, n, 4), np.uint8)
    inside = r < 1.0
    # 튄 방울
    for _ in range(9):
        a = rng.rand() * 2 * math.pi
        d = rng.uniform(11, 15)
        cx, cy = 15.5 + math.cos(a) * d, 15.5 + math.sin(a) * d
        inside |= (xx - cx) ** 2 + (yy - cy) ** 2 < rng.uniform(1.5, 4)
    shade = np.array([244, 140, 168], float) * (0.85 + 0.15 * (1 - np.clip(r, 0, 1)))[..., None]
    shade[(rng.rand(n, n) < 0.07)] = (255, 220, 230)
    out[..., :3] = np.clip(shade, 0, 255)
    out[..., 3] = np.where(inside, 255, 0)
    return out


def wart_tex(n=16, seed=6):
    """두꺼비 등: 얼굴 살빛 바탕에 도드라진 사마귀(밝은 꼭지와 아래 그늘), 사이사이 붉은 골"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    base = np.array([238, 188, 178], float)
    col = base * (0.9 + 0.1 * np.cos(xx * 0.9 + yy * 0.55))[..., None] + rng.randn(n, n, 1) * 3
    for y, x in ((2, 3), (5, 10), (9, 5), (12, 12), (13, 2), (3, 14), (8, 13), (10, 9)):
        r = np.hypot(xx - x, yy - y)
        col[r < 1.8] = (206, 128, 128)
        col[r < 1.1] = (226, 156, 150)
        col[(r < 0.8) & (yy < y)] = (252, 222, 214)
        col[(r > 1.6) & (r < 2.2) & (yy > y)] = (176, 96, 104)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def sac_tex(n=16, seed=7):
    """턱밑 목주머니: 얇게 늘어난 연분홍 살, 가로 주름, 젖은 반짝임"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    col = np.array([255, 214, 212], float) * (0.92 + 0.08 * np.sin(yy * 1.2))[..., None] + rng.randn(n, n, 1) * 2
    col[(yy.astype(int) % 5 == 2)] = (238, 176, 178)
    for (x, y) in ((4, 3), (11, 9), (7, 12)):
        col[y, x] = (255, 240, 238)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def cheek(s, h, side, size, k):
    c = np.array(h.point(side * 0.82, -0.38, 0.55))
    hs = size / 2
    blush = k.texture("cheek", cheek_tex())
    skin = k.skin_tex("left" if side > 0 else "right")
    s.box(tuple(c - hs), tuple(c + hs), {"south": blush, "east": blush, "west": blush, "up": skin, "down": skin},
          faces=("south", "east", "west", "up", "down"), uv=[0, 0, 16, 16], face=True)


def cheek_tex():
    out = np.zeros((16, 16, 4), np.uint8)
    yy, xx = np.mgrid[0:16, 0:16]
    r = np.hypot(xx - 7.5, yy - 9.0) / 8
    base = np.array([255, 218, 210], float)
    blush = np.array([255, 170, 160], float)
    t = np.clip(1.2 - r, 0, 1)[..., None]
    rgb = base * (1 - t) + blush * t
    rgb[(xx - 5) ** 2 + (yy - 4) ** 2 < 3] = (255, 236, 232)
    out[..., :3] = np.clip(rgb, 0, 255)
    out[..., 3] = 255
    return out


def body(k, s, name):
    """두꺼비 하나: 얼굴(앞 위) + 볼 + 사마귀 등 + 목주머니 + 앞다리 둘(가만히) + 뒷다리 둘(원형 DNA, 무릎에서 접힘)"""
    face, cheeks, sac, amp, cycles = TOAD[name]
    h = s.head(size=0.75, depth=0.56, at=(0, 0.06, 0.14), face=face)
    for side, size in cheeks:
        cheek(s, h, side, size, k)
    wart = k.texture("wart", wart_tex())
    flesh = k.part_tex("flesh")
    # 등: 얼굴 뒤 아래로 얼굴보다 넓게 납작한 몸
    s.box((-0.62, -0.42, -0.86), (0.62, 0.02, -0.1), {"up": wart, "north": wart, "east": wart, "west": wart, "south": wart, "down": flesh},
          faces=("up", "north", "east", "west", "south", "down"), uv=[0, 0, 16, 16], face=True)
    # 목주머니(턱밑): 크흡에 부풀고 퉤에 꺼짐
    s.box((-sac / 2, -0.36 - sac * 0.35, -0.05), (sac / 2, -0.3, -0.05 + sac * 0.85), k.texture("sac", sac_tex()), uv=[0, 0, 16, 16],
          face=True)
    # 앞다리(가만히): 턱 양옆에서 앞 아래로 짚음
    for side in (1, -1):
        s.rod((side * 0.26, -0.3, 0.02), (side * 0.2, -1.0, 0.25), length=0.5, segs=1, thick=0.09, tex="flesh", toward=(0, 0, 1))
    # 뒷다리(원형 DNA): 엉덩이 옆에서 앞으로 허벅지, 무릎에서 뒤 아래로 정강이(Z 로 접힘), 숨 쉬듯 움찔
    for side in (1, -1):
        w = kit.Wave(amp=amp, lag=40, bias=(0, -140), cycles=cycles, phase=0 if side > 0 else 60)
        s.rod((side * 0.6, -0.22, -0.7), (side * 1.0, -0.05, 0.55), length=0.8, segs=2, thick=0.12, wave=w, toward=(0, 1, 0), poses=4)
    return h


def build(k: kit.Kit):
    m = k.model(poses=4, period=40)
    m.mut2 = dict(root=(0.25, 0.42, -0.3), dir=(0.4, 1.0, -0.7), length=0.6)
    for name in TOAD:
        body(k, m.state(name), name)

    sp = k.model("g06_glob", poses=4, period=8, mut2=False, open=False, torn=False)
    blob = sp.state("")
    goo = k.texture("goo", goo_tex())
    for f in blob.frames():
        with f:
            w = math.sin(2 * math.pi * f.t)
            for (c, r, ph) in (((0, 0, 0), 0.17, 0.0), ((0.08, 0.07, -0.1), 0.11, 1.7), ((-0.07, -0.05, -0.16), 0.09, 3.1)):
                rr = r * (1 + 0.15 * math.sin(2 * math.pi * f.t + ph))
                sy = 1 - 0.18 * w
                cc = np.array(c, float)
                blob.box(tuple(cc - np.array([rr, rr * sy, rr / sy])), tuple(cc + np.array([rr, rr * sy, rr / sy])), goo,
                         uv=[0, 0, 16, 16])
    sp_splat = sp.state("splat", poses=1)
    st = k.texture("splat", splat_tex())
    sp_splat.box((-0.9, 0.0, -0.9), (0.9, 0.025, 0.9), {"up": st}, faces=("up",), uv=[0, 0, 16, 16])
    sp_splat.box((-0.18, 0.0, -0.12), (0.12, 0.09, 0.18), goo, faces=("up", "north", "south", "east", "west"), uv=[0, 0, 16, 16])

    k.cover("g06_drip", drip_cover())


def drip_cover(seed=4, size=(480, 270)):
    W, H = size
    rng = np.random.RandomState(seed)
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for i in range(18):
        side = rng.randint(3)
        if side == 0:
            x, y = rng.uniform(0, W), rng.uniform(-8, 18)
        elif side == 1:
            x, y = rng.uniform(-6, 40), rng.uniform(0, H * 0.7)
        else:
            x, y = rng.uniform(W - 40, W + 6), rng.uniform(0, H * 0.7)
        r = rng.uniform(7, 18)
        col = (246, 150 + rng.randint(25), 176 + rng.randint(20), 205)
        d.ellipse((x - r, y - r, x + r, y + r), fill=col)
        L = rng.uniform(30, 110)
        d.rounded_rectangle((x - r * 0.3, y, x + r * 0.3, y + L), radius=max(1, int(r * 0.3)), fill=col)
        d.ellipse((x - r * 0.45, y + L - r * 0.45, x + r * 0.45, y + L + r * 0.45), fill=col)
        d.ellipse((x - r * 0.25, y + L * 0.3 - r * 0.25, x - r * 0.05, y + L * 0.3), fill=(255, 236, 242, 220))
    return im.filter(ImageFilter.GaussianBlur(1.0))


def sounds(k: kit.Kit):
    # 크흡: 고개를 젖혀 볼을 부풀림(뱉기 예고)
    k.sound("inhale", "크흡", vanilla=[("mob/breeze/inhale1", 1.35), ("mob/pufferfish/blow_up1", 1.2)],
            synth=lambda i: inhale(seed=600 + i), volume=0.9, attenuation=24)
    # 퉤: 뱉음
    k.sound("spit", "퉤", vanilla=[("mob/llama/spit1", 0.9), ("mob/llama/spit2", 0.85)], volume=1.0, attenuation=24)
    # 철퍽: 침이 떨어짐
    k.sound("splat", "철퍽", vanilla=[("mob/sulfur_cube/ball/sticky_hit1", 1.0), ("mob/sulfur_cube/ball/sticky_hit2", 1.05),
                                      ("mob/slime/small1", 0.8)], volume=0.8, attenuation=16)
    # 폴짝: 가까이 오면 다리로 차고 뒤로 뜀('크흡'은 뱉기 예고에만 쓰도록 따로 둠)
    k.sound("hop", "폴짝", vanilla=[("mob/slime/small2", 1.5), ("mob/slime/small4", 1.6)], volume=0.6, attenuation=12)


def inhale(seed=0, dur=0.42):
    n = audio.noise(dur, seed)
    t = np.arange(len(n), dtype=np.float32) / audio.SR
    sweep = audio.bandpass(n, 700, 3500)
    e = np.clip(t / dur, 0, 1) ** 1.6
    x = sweep * e
    x[-int(0.04 * audio.SR):] *= np.linspace(1, 0, int(0.04 * audio.SR), dtype=np.float32)
    return audio.normalize(audio.fade(x, 0.01, 0.01), 0.75)


def phrases():
    return {"cause_g06": "분홍 침을 맞았다"}
