"""G31 웅덩이 게이브: 얼굴이 녹아 퍼진 납작한 분홍 반죽 장판(지름 1 .. 4 블록). 눈 조각, 점 입, 홍조가 기름처럼 떠다니다 가끔 모여
원래 얼굴로 웃는다. 가장자리에서 엔드 막대기 둘이 지느러미처럼 솟아 펄럭인다(원형 DNA). 황야의 땅 갈라진 틈에서 스며 나온다.

디스플레이 둘(Java):
  gave:gabe/g31        닻: 장판(지름 3 블록으로 굽고 Java 가 지름 / 3 배) + 가장자리 지느러미 둘. 마르면 탈색(팔레트)
  gave:gabe/g31_eyes   떠다니는 이목구비(Java 가 장판 위에서 미끄러뜨림). 상태 ""(흩어져 작은 궤도로 떠돎), "face"(모여 원래 얼굴).
                       밟은 사람을 찍으면 실눈이 뜨인 조각으로 바뀜(open)
  gave:gabe/g31_tongue 반죽 혀: +Z 로 1 블록짜리 납작한 띠(Java 가 길이만큼 늘임, 뻗는 동안만 붙임)
  gave:gabe/g31_print  분홍 발자국 반죽(녹음이 절반 넘은 사람이 남김, 연출 디스플레이에 실음)
"""
import math

import numpy as np
from PIL import Image

from gabe import audio, faces, kit

MAT = 3.0   # 장판 모델 지름(블록)
FACE = 1.9  # 모인 얼굴 폭(블록): 32 화소 = 1.9 블록(멀리서도 이목구비가 읽히게 크게)
PX = FACE / 32

SKIN = np.array([255, 214, 212], np.float32)
ROSE = np.array([244, 136, 162], np.float32)
DEEP = np.array([92, 22, 46], np.float32)
RIM = np.array([214, 96, 130], np.float32)
BAYER = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0


def _rgba(a):
    return np.clip(np.round(a), 0, 255).astype(np.uint8)


def vnoise(n, cells, seed):
    """값 잡음 0..1(n x n)"""
    g = np.random.RandomState(seed).rand(cells + 1, cells + 1).astype(np.float32)
    im = Image.fromarray((g * 255).astype(np.uint8)).resize((n, n), Image.BICUBIC)
    return np.asarray(im, np.float32) / 255.0


def polar(n):
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    c = (n - 1) / 2
    dx, dy = (xx - c) / (n / 2), (yy - c) / (n / 2)
    return np.hypot(dx, dy), np.arctan2(dy, dx), xx, yy


def dough(n, seed):
    """반죽 살색: 살빛과 분홍이 소용돌이로 섞임 → n x n x 3"""
    r, a, xx, yy = polar(n)
    swirl = np.sin(a * 2 + r * 8.0 + np.sin(r * 5 + a * 1.0) * 1.6)
    t = (swirl * 0.5 + 0.5)[..., None]
    col = SKIN * (1 - 0.72 * t) + ROSE * (0.72 * t)
    col += (vnoise(n, 12, seed)[..., None] - 0.5) * 14
    return col, r, a, xx, yy


def mat_tex(n=96, seed=31):
    """장판: 들쭉날쭉한 가장자리(컷아웃), 가운데 쪽은 밑이 비치는 검붉은 깊은 곳과 젖은 반짝임"""
    col, r, a, xx, yy = dough(n, seed)
    edge = 0.86 + 0.07 * np.sin(3 * a + 0.8) + 0.045 * np.sin(5 * a + 2.1) + 0.03 * np.sin(9 * a + 4.0)
    deepn = vnoise(n, 7, seed + 1)
    depth = np.clip((deepn - 0.63) / 0.18, 0, 1) * np.clip((0.62 - r) / 0.25, 0, 1)
    col = col * (1 - depth[..., None]) + DEEP * depth[..., None]
    # 깊은 곳 속 먼 반짝임(끝없이 비치는 느낌): 드문 밝은 점
    rng = np.random.RandomState(seed + 2)
    spark = (rng.rand(n, n) < 0.02) & (depth > 0.8)
    col[spark] = (232, 150, 170)
    # 녹아 섞인 적갈색 머리 덩어리 줄
    streak = np.abs(np.sin(a * 3 + r * 14 + 1.0)) < 0.07
    streak &= (r > 0.35) & (r < edge - 0.08) & (vnoise(n, 5, seed + 3) > 0.55)
    col[streak] = col[streak] * 0.35 + np.array([104, 34, 34], np.float32) * 0.65
    rim = (r > edge - 0.06) & (r <= edge)
    col[rim] = col[rim] * 0.45 + RIM * 0.55
    wet = (rng.rand(n, n) < 0.012) & (r < edge - 0.1) & (depth < 0.3)
    col[wet] = (255, 246, 244)
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = col
    inside = r <= edge
    fringe = (r > edge) & (r <= edge + 0.05) & (BAYER[(yy.astype(int) % 4), (xx.astype(int) % 4)] < 0.3)
    out[..., 3] = np.where(inside | fringe, 255, 0)
    out[fringe, :3] = RIM
    return _rgba(out)


def tongue_tex(w=20, h=32, seed=35):
    """반죽 혀(위가 뿌리, 아래가 끝): 끝이 둥글고 가장자리 짙은 띠"""
    out = np.zeros((h, w, 4), np.float32)
    rng = np.random.RandomState(seed)
    for y in range(h):
        v = y / (h - 1)
        half = (w / 2 - 1.0) * (1.0 if v < 0.7 else math.sqrt(max(0.0, 1 - ((v - 0.7) / 0.3) ** 2)))
        half *= 0.86 + 0.14 * math.sin(v * 9.0)
        for x in range(w):
            d = abs(x + 0.5 - w / 2)
            if d > half:
                continue
            t = 0.5 + 0.5 * math.sin(x * 0.9 + y * 0.5)
            c = SKIN * (1 - 0.6 * t) + ROSE * (0.6 * t) + rng.randn(3) * 3
            if d > half - 1.2:
                c = c * 0.45 + RIM * 0.55
            out[y, x, :3] = c
            out[y, x, 3] = 255
    return _rgba(out)


def print_tex(n=16):
    """분홍 발자국 반죽: 발바닥 덩이 + 발가락 점 셋"""
    out = np.zeros((n, n, 4), np.float32)
    for y in range(n):
        for x in range(n):
            q = ((x - 7.5) / 3.4) ** 2 + ((y - 9.5) / 4.8) ** 2
            sole = q <= 1
            toes = any((x - tx) ** 2 + (y - ty) ** 2 <= 1.7 for tx, ty in ((4.5, 2.5), (7.5, 1.5), (10.5, 2.5)))
            if sole or toes:
                out[y, x, :3] = RIM if (sole and q > 0.62) else ROSE
                out[y, x, 3] = 255
    out[8, 7, :3] = (255, 240, 240)
    return _rgba(out)


def piece(face, box, keep):
    """32 얼굴 그림의 조각(x0, y0, x1, y1): keep 부류 화소만 남기고 투명(부류 비트 그대로)"""
    im = faces.far(face)[box[1]:box[3], box[0]:box[2]].copy()
    cls = im[..., 2] & 7
    im[..., 3] = np.where(np.isin(cls, keep), 255, 0)
    return im


def melt_face(face, seed=36):
    """녹은 반죽 위로 떠오른 얼굴: 가장자리가 흘러내린 둥근 조각(컷아웃)"""
    im = faces.far(face).copy()
    r, a, xx, yy = polar(32)
    edge = 0.92 + 0.06 * np.sin(4 * a + 1.3) + 0.04 * np.sin(7 * a + seed)
    im[..., 3] = np.where(r <= edge, 255, 0)
    return im


# 이목구비 조각: 이름 → (그림 상자, 남길 부류). 얼굴 좌표(32 화소)
PIECES = {
    "eye_l": ((4, 11, 12, 19), (faces.CLS_EYE, faces.CLS_WHITE, faces.CLS_SPARK)),
    "eye_r": ((20, 11, 28, 19), (faces.CLS_EYE, faces.CLS_WHITE, faces.CLS_SPARK)),
    "mouth": ((16, 24, 20, 28), (faces.CLS_MOUTH,)),
    "blush": ((12, 20, 20, 24), (faces.CLS_BLUSH,)),
}
# 흩어졌을 때 자리(x, z 블록), 궤도 위상, 도는 각
SCATTER = {"eye_l": (-0.72, 0.28, 0.0, 25), "eye_r": (0.65, -0.48, 2.1, -40), "mouth": (0.17, 0.72, 4.0, 70), "blush": (-0.28, -0.72, 1.2, 150)}


def size(name):
    (x0, y0, x1, y1), _ = PIECES[name]
    return (x1 - x0) * PX, (y1 - y0) * PX


def build(k: kit.Kit):
    # ---- 닻: 장판 + 지느러미 둘
    m = k.model(poses=8, period=20, mut2=False, open=False, torn=False)
    s = m.state("")
    # 얼굴(장판 그림)은 위를 본다: 래그돌이 뒤집혀 굳으면 굴러 넘어가 얼굴이 하늘을 보게(키트 기본은 +Z)
    s.front = (0, 1, 0)
    s.quad((0, 0, 0), (MAT, MAT), "up", k.texture("mat", mat_tex()), face=True)
    for side in (1, -1):
        fin = kit.Wave(amp=(18, 16, 14), lag=50, bias=(-4, 4, 6), shape="flap", phase=0 if side > 0 else 70)
        s.rod((side * 1.02, 0.0, -0.12), (side * 0.3, 1.0, -0.12), length=0.95, segs=3, thick=0.11, wave=fin,
              toward=(side * 1.0, 0.0, 0.0))
    # ---- 떠다니는 이목구비
    e = k.model("g31_eyes", poses=8, period=40, mut2=False, open=False, torn=False)
    tex = {}
    for name, (box, keep) in PIECES.items():
        tex[name] = k.texture(name, piece("smile", box, keep), classes=True)
        if name.startswith("eye"):
            tex[name + "_open"] = k.texture(name + "_open", piece("open", box, keep), classes=True)
    sc = e.state("")
    sc.front = (0, 1, 0)
    for f in sc.frames():
        with f:
            for name, (bx, bz, ph, spin) in SCATTER.items():
                w, h = size(name)
                ang = 2 * math.pi * f.t + ph
                c = (bx + 0.16 * math.cos(ang), 0.012, bz + 0.1 * math.sin(ang * 2))
                rot = (0, spin + 12 * math.sin(ang), 0)
                if name.startswith("eye"):
                    sc.quad(c, (w, h), "up", tex[name], face=True, rot=rot, origin=c, show=("!open",))
                    sc.quad(c, (w, h), "up", tex[name + "_open"], face=True, rot=rot, origin=c, show=("open",))
                else:
                    sc.quad(c, (w, h), "up", tex[name], face=True, rot=rot, origin=c)
    fc = e.state("face")
    fc.front = (0, 1, 0)
    fc.quad((0, 0.012, 0), (FACE, FACE), "up", k.texture("meltface", melt_face("smile"), classes=True), face=True, show=("!open",))
    fc.quad((0, 0.012, 0), (FACE, FACE), "up", k.texture("meltface_open", melt_face("open"), classes=True), face=True, show=("open",))
    # ---- 혀, 발자국
    t = k.model("g31_tongue", poses=1, mut2=False, open=False, torn=False)
    t.state("").box((-0.32, 0.006, 0.0), (0.32, 0.006, 1.0), {"up": k.texture("tongue", tongue_tex())}, faces=("up",), uv=[0, 0, 16, 16],
                    face=True)
    p = k.model("g31_print", poses=1, mut2=False, open=False, torn=False)
    p.state("").quad((0, 0, 0), (0.42, 0.56), "up", k.texture("print", print_tex()))
    # ---- 아이콘: 장판 위에 떠오른 얼굴
    ic = Image.fromarray(mat_tex(64, 31)).convert("RGBA")
    fa = Image.fromarray(melt_face("smile")).resize((40, 40), Image.NEAREST)
    ic.alpha_composite(fa, (12, 12))
    k.icon(ic)


def gurgle(seed=0, dur=0.95):
    """꾸르륵: 진득한 반죽 속에서 공기 방울이 올라옴(낮은 음이 위로 미끄러지는 방울 여럿 + 젖은 바닥 소리)"""
    rng = np.random.RandomState(seed)
    parts = []
    t = 0.0
    while t < dur - 0.14:
        f = 80 + 150 * rng.rand()
        b = audio.env(audio.tone(f, 0.1, glide=f * (1.5 + rng.rand())), 0.004, 0.03, 0.45, 0.045) * (0.5 + 0.5 * rng.rand())
        parts.append(audio.at(b, t, dur))
        t += 0.05 + 0.1 * rng.rand()
    wet = audio.env(audio.lowpass(audio.noise(dur, seed + 3), 480), 0.05, 0.2, 0.5, 0.3) * 0.35
    return audio.normalize(audio.fade(audio.mix(wet, *parts), 0.01, 0.08), 0.8)


def crackle(seed=0, dur=0.5):
    """바삭: 반죽이 말라 갈라짐"""
    rng = np.random.RandomState(seed)
    parts = []
    for i in range(9):
        c = audio.env(audio.highpass(audio.noise(0.03, seed + i), 2200), 0.0005, 0.006, 0.15, 0.015) * (0.3 + 0.7 * rng.rand())
        parts.append(audio.at(c, rng.rand() * (dur - 0.05), dur))
    return audio.normalize(audio.fade(audio.mix(*parts), 0.001, 0.03), 0.7)


def sounds(k: kit.Kit):
    # 이 종의 신호: 번져 오며 꾸르륵
    k.sound("gurgle", "꾸르륵", synth=lambda i: gurgle(seed=310 + i), volume=0.9, attenuation=24)
    # 밟힘, 혀 뻗기: 질척
    k.sound("squelch", "질척", vanilla=[("mob/slime/small2", 0.6), ("mob/slime/small4", 0.55)],
            synth=lambda i: audio.squish(seed=314 + i, dur=0.34), attenuation=16)
    # 쪼개짐
    k.sound("split", "반죽이 쪼개짐", vanilla=[("mob/slime/big1", 0.85), ("mob/slime/big3", 0.9)], attenuation=16)
    # 작은 조각이 말라 죽음
    k.sound("dry", "바삭 마름", synth=lambda i: crackle(seed=318 + i), attenuation=16)


def phrases():
    return {"cause_g31": "반죽을 밟고 서 있었다"}
