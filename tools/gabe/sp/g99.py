"""G99 붓 게이브(2막, 함정): 굵은 엔드 막대기 하나가 2블록 붓대로 곧게 섰고 그 아래 끝에 0.8블록 얼굴이 꿰였다.
턱 아래로 먹에 젖은 살 띠 붓털이 뭉쳐 뾰족하게 모였다.

- gave:gabe/g99(shrink 0.45): 붓대(자세 없는 굵은 막대기, 위 끝 고리 마디), 얼굴, 붓털(살 띠 여섯, 아래로 갈수록 먹빛, 흔들림 자세 4).
  상태 ""(흔들림), "aim"(내려찍기 직전: 붓털이 곧게 모임). 모델 가운데가 붓대 아래쪽, 붓끝은 y -2.05.
- gave:gabe/g99_ink: 옆에 떠서 따라다니는 먹(짧고 굵은 검은 막대기, 한쪽에 얼굴 도장).
- gave:gabe/g99_ring(shrink 0.5): 바닥 먹 원(반지름 2.5) 호 조각 열둘 on:s0..s11(그리는 차례로 켬, 지워진 조각은 끔). 상태 ""(젖은 먹), "dry"(마른 먹).
소리 stroke(붓 긋는 소리, 20), close(먹 원이 닫힘, 24), stamp(붓 내려찍기, 24).
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

R = 2.5
TIP = -2.05


def hair_img():
    """붓털: 위는 살빛, 아래로 갈수록 검붉은 먹(띠 길이를 따라 v 방향)"""
    n = 32
    rng = np.random.RandomState(99)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    t = yy / (n - 1)
    flesh = np.array((236, 176, 168), np.float32)
    ink = np.array((48, 14, 16), np.float32)
    k = np.clip((t - 0.15) / 0.6, 0, 1)[..., None]
    col = flesh * (1 - k) + ink * k
    col = col * (0.85 + 0.15 * np.abs(np.sin(xx * 1.7))[..., None]) * (0.92 + rng.rand(n, n, 1) * 0.1)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255)
    out[..., 3] = 255
    return Image.fromarray(out, "RGBA")


def ink_img(dry):
    """바닥 먹 줄: 가운데 짙고 가장자리 거친 붓 자국(컷아웃)"""
    n = 32
    rng = np.random.RandomState(990 + int(dry))
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    edge = 6 + 3 * np.sin(xx * 0.45 + 1.3) + rng.rand(n, n) * 2.5
    inside = (yy > edge) & (yy < n - 1 - edge * 0.8)
    base = np.array((92, 26, 30) if dry else (52, 10, 14), np.float32)
    streak = 0.85 + 0.25 * np.abs(np.sin(yy * 1.3 + xx * 0.05))
    col = base * streak[..., None] * (0.9 + rng.rand(n, n, 1) * 0.15)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255)
    out[..., 3] = np.where(inside, 255, 0)
    return Image.fromarray(out, "RGBA")


def stick_img():
    """먹 막대기 옆면: 검은 먹, 옅은 금빛 테"""
    n = 16
    rng = np.random.RandomState(991)
    out = np.zeros((n, n, 4), np.uint8)
    col = np.array((30, 26, 30), np.float32) * (0.85 + rng.rand(n, n, 1) * 0.3)
    col[1, :] = col[-2, :] = (150, 120, 60)
    out[..., :3] = np.clip(col, 0, 255)
    out[..., 3] = 255
    return Image.fromarray(out, "RGBA")


def brush(s, hair, gather):
    """붓대 + 얼굴 + 붓털. gather 1 이면 붓털이 곧게 모임(흔들림 없음)"""
    # 붓대: 얼굴 위에서 위로 2블록(자세 없는 굵은 막대기), 위 끝 고리 마디
    s.rod((0, -0.25, 0), (0, 1, 0), length=1.95, segs=2, thick=0.3, base=False, rag=False, toward=(0, 0, 1))
    s.rod((0, 1.62, 0), (0, 1, 0), length=0.14, segs=1, thick=0.44, base=False, rag=False, toward=(0, 0, 1))
    s.rod((0, -0.3, 0), (0, 1, 0), length=0.18, segs=1, thick=0.42, base=False, rag=False, toward=(0, 0, 1))
    # 얼굴: 붓대 아래 끝에 꿰임(붓대가 정수리로 들어감)
    s.head(size=0.8, depth=0.62, at=(0, -0.68, 0), face="half")
    # 붓털: 턱 아래 둘레에서 붓끝으로 모임
    n = 8
    for i in range(n):
        a = 2 * math.pi * (i + 0.5) / n
        root = (0.4 * math.cos(a), -1.04, 0.32 * math.sin(a))
        tip = (0.0, TIP, 0.03)
        d = np.array(tip) - np.array(root)
        side = (-math.sin(a), 0.0, math.cos(a))
        if gather:
            s.band(root, tuple(d), float(np.linalg.norm(d)) * 1.03, 0.26, segs=2, thick=0.03, toward=side, tex=hair)
        else:
            w = kit.Wave(amp=(6, 11), lag=50, phase=i * 45)
            s.band(root, tuple(d), float(np.linalg.norm(d)) * 1.03, 0.26, segs=2, thick=0.03, wave=w, toward=side, tex=hair, poses=4)


def build(k: kit.Kit):
    hair = k.texture("hair", hair_img())
    m = k.model(poses=4, period=12, shrink=0.45, mut2=False)
    brush(m.state(""), hair, False)
    brush(m.state("aim", poses=1), hair, True)

    st = k.texture("stick", stick_img())
    ink = k.model("g99_ink", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    s = ink.state("")
    s.box((-0.09, -0.28, -0.07), (0.09, 0.28, 0.07), st)
    s.quad((0, 0.05, 0.072), (0.14, 0.14), "south", k.face_tex("smile"), face=True)
    s.front = (0, 0, 0)

    ring = k.model("g99_ring", poses=1, period=20, shrink=0.5, mut2=False, open=False, torn=False, rag=False)
    for name, dry in (("", False), ("dry", True)):
        t = k.texture("ink_dry" if dry else "ink_wet", ink_img(dry))
        rs = ring.state(name)
        for i in range(12):
            a0, a1 = 2 * math.pi * i / 12 - 0.04, 2 * math.pi * (i + 1) / 12 + 0.04
            p0 = np.array((R * math.cos(a0), 0.0, R * math.sin(a0)))
            p1 = np.array((R * math.cos(a1), 0.0, R * math.sin(a1)))
            d = p1 - p0
            with rs.when(f"on:s{i}"):
                rs.band(tuple(p0), tuple(d), float(np.linalg.norm(d)), 0.42, segs=1, thick=0.01, toward=(0, 1, 0), tex=t, face=False)
        rs.front = (0, 0, 0)
    k.icon(model="g99", state="aim")


def sounds(k: kit.Kit):
    k.sample("stroke", ["kenney_rpg/cloth1", "kenney_rpg/cloth3", "kenney_scifi/slime_000"], "붓 긋는 소리", pitch=1.15, attenuation=20)
    k.sample("close", ["oga_metalwood100/wood_slam_01", "oga_metalwood100/wood_slam_03"], "먹 원이 닫힘", pitch=1.5, attenuation=24)
    k.sample("stamp", "kenney_impact/impactpunch_heavy_00*", "붓 내려찍기", pitch=0.7, attenuation=24)


def phrases():
    return {"cause_g99": "붓끝에 찍혔다", "cause_g99_dry": "마르는 먹 원에 조였다", "notice_g99_ring": "먹 원에 갇혔다"}
