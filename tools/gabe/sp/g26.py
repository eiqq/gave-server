"""G26 실뭉치 게이브: 색이 바랜 분홍에 흰 줄무늬, 세로로 긴 작은 얼굴(실눈 선만 남음) 다섯이 실타래처럼 엉킨 덩이.
털처럼 짧은 막대기 끝들이 꼬물거리고, 위로 솟은 엔드 막대기 둘이 펄럭인다(원형 DNA). 무리(떼) 마리 하나 = 실타래 하나.

상태: "a", "b"(엉킨 모양 둘, 기본 "a"), "lod"(15블록 밖 무리 덩어리). 꿈틀거림은 자세 4장(손으로 짠 자세, 마리마다 위상 엇갈림은
엔진 look.phase). 탈색: 자세 면은 셰이더 팔레트를 받지 않으므로 얼굴, 실 텍스처를 처음부터 바랜 색으로 굽는다.
떼 마리라 오래 살면 변함에서 빠짐(변이 2 막대기, 입 찢어짐 끔).
"""
import math

import numpy as np

from gabe import audio, faces, kit

FACE = faces.Face(eyes="squint", mouth="none", blush="none", tufts=False)
FACE_B = faces.Face(eyes="squint", mouth="dot", blush="none", tufts=False, tilt=10.0)


def pale(arr, k=0.5, lift=18):
    """화소 부류(파랑 아래 3비트)를 지키며 색을 바래게(회색 쪽으로 k, 조금 밝게)"""
    a = np.array(arr, np.float32, copy=True)
    cls = arr[..., 2] & 7
    rgb = a[..., :3]
    g = rgb.mean(axis=-1, keepdims=True)
    rgb = rgb * (1 - k) + g * k + lift
    out = np.array(arr, np.uint8, copy=True)
    out[..., :3] = np.clip(np.round(rgb), 0, 255).astype(np.uint8)
    out[..., 2] = (out[..., 2] & 0xF8) | cls
    return out


def thread_tex():
    """바랜 분홍 살에 흰 줄무늬(실타래)"""
    im = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            stripe = (x + y // 3) % 5 < 2
            base = (236, 214, 216) if stripe else (214, 170, 176)
            j = int((math.sin(x * 1.7 + y * 0.9) * 0.5 + 0.5) * 8)
            im[y, x] = (min(255, base[0] + j), min(255, base[1] + j), min(255, base[2] + j), 255)
    return im


class Pale:
    """바랜 얼굴 한 벌(먼 32, 가까운 128, 실눈 뜸 오버레이, 옆면) 텍스처 id"""

    def __init__(self, k, f, name):
        self.far = k.texture(f"{name}", pale(faces.far(f)), classes=True)
        self.near = k.texture(f"{name}_n", pale(faces.near(f)), classes=True)
        ov = faces.overlay(f, "open")
        self.ov = None
        if ov is not None:
            bb, a, b = ov
            self.ov = (bb, k.texture(f"{name}_o", pale(a), classes=True), k.texture(f"{name}_on", pale(b), classes=True))
        self.side = {n: k.texture(f"side_{n}", pale(faces.skin_side(w)), classes=True)
                     for n, w in (("north", "back"), ("east", "left"), ("west", "right"), ("up", "top"), ("down", "bottom"))}


def pale_head(s, p: Pale, c, w, h, d, yaw=0.0, roll=0.0):
    """세로로 긴 작은 얼굴 상자(바랜 텍스처). 앞(+Z) 먼 얼굴 + 3블록 안 가까운 얼굴 + 실눈 뜸 오버레이"""
    c = np.array(c, float)
    lo, hi = c - (w / 2, h / 2, d / 2), c + (w / 2, h / 2, d / 2)
    rot = (0, yaw, roll) if (abs(yaw) > 1e-6 or abs(roll) > 1e-6) else None
    kw = dict(rot=rot, origin=tuple(c)) if rot else {}
    s.box(tuple(lo), tuple(hi), {"south": p.far}, faces=("south",), uv=[0, 0, 16, 16], dist="far", face=True, **kw)
    s.box(tuple(lo), tuple(hi), p.side, faces=("north", "east", "west", "up", "down"), uv=[0, 0, 16, 16], face=True, **kw)
    ep = 1.0 / 16
    z = hi[2]
    s.quad((c[0], c[1], z + 0.08 * ep), (w, h), "south", p.near, dist="near", face=True, **kw)
    if p.ov:
        (bx0, by0, bx1, by1), o32, o128 = p.ov
        cx = lo[0] + (bx0 + bx1) / 2 / 32 * w
        cy = hi[1] - (by0 + by1) / 2 / 32 * h
        sz = ((bx1 - bx0) / 32 * w, (by1 - by0) / 32 * h)
        with s.when("open"):
            s.quad((cx, cy, z + 0.16 * ep), sz, "south", o32, dist="far", face=True, **kw)
            s.quad((cx, cy, z + 0.2 * ep), sz, "south", o128, dist="near", face=True, **kw)


def tangle(s, pa: Pale, pb: Pale, thread, seed):
    rng = np.random.RandomState(seed)
    # 얼굴 다섯 자리(실타래 안, 바깥을 봄)
    spots = []
    for i in range(5):
        a = 2 * math.pi * i / 5 + rng.uniform(-0.3, 0.3)
        r = 0.13 + rng.uniform(0, 0.06)
        spots.append((a, r, rng.uniform(0.02, 0.16), rng.uniform(0, 2 * math.pi), rng.uniform(-18, 18)))
    bristles = [(rng.uniform(0, 2 * math.pi), rng.uniform(0.04, 0.22), rng.uniform(0, 2 * math.pi)) for _ in range(4)]
    for f in s.frames(poses=4):
        with f:
            for i, (a, r, y, ph, roll) in enumerate(spots):
                wob = math.sin(2 * math.pi * f.t + ph)
                aa = a + wob * 0.12
                c = (math.sin(aa) * r, y + wob * 0.025, math.cos(aa) * r)
                yaw = math.degrees(aa)
                pale_head(s, pa if i % 2 == 0 else pb, c, 0.15, 0.24, 0.1, yaw=yaw, roll=roll + wob * 8)
            # 실(바랜 분홍 흰 줄): 얼굴 사이를 감는 얇은 띠 둘
            for j in range(2):
                b0 = 2 * math.pi * (j / 2 + f.t * 0.25)
                root = (math.sin(b0) * 0.2, 0.03 + 0.06 * j, math.cos(b0) * 0.2)
                d = (math.cos(b0), 0.35 - 0.3 * j, -math.sin(b0))
                s.band(root, d, length=0.36, width=0.06, segs=2, thick=0.02, tex=thread, face=False)
            # 털처럼 짧은 막대기 끝(꼬물)
            for (a, y, ph) in bristles:
                wob = math.sin(2 * math.pi * f.t + ph)
                root = (math.sin(a) * 0.2, y, math.cos(a) * 0.2)
                d = (math.sin(a + wob * 0.6), 0.6 + 0.3 * wob, math.cos(a + wob * 0.6))
                s.rod(root, d, length=0.12, segs=1, thick=0.035, base=False)
    # 원형 DNA: 위로 솟은 엔드 막대기 둘(펄럭)
    for side in (1, -1):
        s.rod((side * 0.06, 0.16, -0.04), (side * 0.6, 1.0, -0.25), length=0.36, segs=2, thick=0.05,
              wave=kit.Wave(amp=(22, 24), lag=60, phase=0 if side > 0 else 150), toward=(-side * 1.0, 0.2, 0), poses=4)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, mut2=False, torn=False)
    pa = Pale(k, FACE, "pale_a")
    pb = Pale(k, FACE_B, "pale_b")
    thread = k.texture("thread", thread_tex())
    tangle(m.state("a"), pa, pb, thread, seed=5)
    tangle(m.state("b"), pb, pa, thread, seed=11)
    m.default = "a"
    lod = m.state("lod")
    lod.lump(count=12, radius=(0.9, 0.18, 0.9), size=0.2, faces_list=["blank", "smile"], wobble=0.04, seed=4)
    k.icon(model="g26", state="a")
    k.cover("g26_thread", cover())


def _wring(seed):
    """젖은 실 꼬이는 소리: 짧은 찌걱 여러 번"""
    rng = np.random.RandomState(500 + seed)
    d = 0.55
    out = audio.silence(d)
    t = 0.0
    while t < d - 0.08:
        g = audio.env(audio.bandpass(audio.noise(0.06, 510 + seed + int(t * 100)), 700, 3200), 0.003, 0.02, 0.4, 0.03)
        g = g * (0.5 + 0.5 * rng.rand())
        out = audio.mix(out, audio.at(g, t, d))
        t += 0.05 + rng.rand() * 0.07
    sq = audio.gain(audio.squish(seed=520 + seed, dur=0.2), 0.3)
    return audio.normalize(audio.fade(audio.mix(out, sq)), 0.7)


def _murmur(seed):
    """먹힌 사람의 낮은 웅얼거림(듣기): 낮은 모음이 막힌 듯(저역만), 말이 되지 않는 억양"""
    d = 1.6
    rng = np.random.RandomState(600 + seed)
    out = audio.silence(d)
    t = 0.05
    while t < d - 0.3:
        f = 110 + rng.rand() * 50
        L = 0.18 + rng.rand() * 0.2
        v = audio.tone(f, L, "saw", vib=0.03, vib_rate=5, glide=f * (0.85 + rng.rand() * 0.3))
        v = audio.lowpass(v, 520)
        v = audio.env(v, 0.03, 0.05, 0.7, 0.08)
        out = audio.mix(out, audio.at(v, t, d))
        t += L + 0.04 + rng.rand() * 0.12
    return audio.normalize(audio.fade(out, 0.02, 0.2), 0.75)


def sounds(k: kit.Kit):
    k.sound("wring", "젖은 실 꼬이는 소리", synth=_wring, volume=0.8, attenuation=16)
    k.sound("murmur", "먹힌 사람의 웅얼거림", synth=_murmur, volume=0.9, attenuation=8)
    k.sound("squash", "실이 짓이겨짐", vanilla=[("block/honeyblock/break1", 0.9), ("block/mud/break4", 1.1)], volume=0.8,
            attenuation=16)
    k.sound("die", "실뭉치 터짐", vanilla=[("block/honeyblock/break3", 0.8), ("mob/slime/small2", 0.7)], volume=0.8, attenuation=24)


def cover():
    """화면 아래에 차오르는 흰 실(덮개 그림, 480x270): 아래 3분의 1에 엉킨 흰 실, 위로 갈수록 성김"""
    from PIL import Image, ImageDraw
    W, H = 480, 270
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    rng = np.random.RandomState(26)
    for i in range(260):
        y0 = H - rng.exponential(42)
        if y0 < H * 0.45:
            continue
        x0 = rng.uniform(-20, W + 20)
        pts = [(x0, y0)]
        a = rng.uniform(0, 2 * math.pi)
        for _ in range(6):
            a += rng.uniform(-1.2, 1.2)
            x0 += math.cos(a) * rng.uniform(8, 22)
            y0 += math.sin(a) * rng.uniform(4, 12)
            pts.append((x0, y0))
        tone = int(rng.uniform(205, 245))
        col = (tone, int(tone * 0.93), int(tone * 0.94), int(rng.uniform(150, 235)))
        dr.line(pts, fill=col, width=int(rng.choice([1, 2, 2, 3])))
    return im


def phrases():
    return {"cause_g26": "실뭉치에 발이 감겼다"}
