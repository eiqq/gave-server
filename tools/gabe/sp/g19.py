"""G19 펼친 얼굴 방패 게이브: 얼굴 그림을 가로 4배, 세로 3배로 늘인 얇은 막(3.2 x 2.4 블록, 실눈이 귀까지 찢어진 듯)이 깃발처럼
물결치고, 엔드 막대기 넷이 막 뒤 본체(반 크기 얼굴)에서 네 모서리로 뻗어 장대처럼 꿰어 든다. 본체 뒤통수에서 짧은 막대기 둘이 펄럭인다
(원형 DNA). 모델이 ±1.5 블록을 넘어 shrink 0.5 로 굽는다.

모델 gave:gabe/g19 상태(표정 x 무늬 단계 0..3):
  "s0".."s3"  실눈(표적 없음),  "o0".."o3"  눈 뜸(표적을 찍음),  "w0".."w3"  찡그림(정면 탄이 박힌 직후)
  무늬 단계: 시체를 덮어 먹을 때마다 막에 작은 얼굴 무늬가 하나씩 늚(Java 가 크기도 키움). 기본 "s0".
부위(모서리 막대기 끊김, 막 귀퉁이가 처짐): "cut_a"(+X 위), "cut_b"(-X 위), "cut_c"(-X 아래), "cut_d"(+X 아래). +X = 게이브 자신의 왼쪽.
"""
import math

import numpy as np
from PIL import Image

from gabe import audio, faces, kit

W, H = 3.2, 2.4  # 막 크기(블록)
COLS, ROWS = 4, 2
BODY_Z = -0.55
CORNERS = {"cut_a": (1, 1), "cut_b": (-1, 1), "cut_c": (-1, -1), "cut_d": (1, -1)}
EXPR = {"s": faces.Face(), "o": faces.Face(eyes="open"), "w": faces.Face(tilt=-32.0, blush="deep")}
SPOTS = [(4, 64, 22, faces.Face(extras=("tears",))), (102, 64, 22, faces.Face(tilt=14.0)), (44, 74, 18, faces.Face(eyes="half"))]


def membrane_tex(face, stage, near=False):
    """늘인 얼굴: 32 그림을 128 x 96(가까운 것은 128 그림을 256 x 192)으로, 무늬 단계만큼 작은 얼굴을 찍음. 정사각 판의 위 3/4"""
    if near:
        src = Image.fromarray(faces.near(face))
        big = src.resize((256, 192), Image.BICUBIC)
        n, k = 256, 2
    else:
        src = Image.fromarray(faces.far(face))
        big = src.resize((128, 96), Image.NEAREST)
        n, k = 128, 1
    canvas = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    canvas.paste(big, (0, 0))
    for (x, y, s, f) in SPOTS[:stage]:
        sm = Image.fromarray(faces.near(f) if near else faces.far(f)).resize((s * k, s * k), Image.BICUBIC if near else Image.NEAREST)
        arr = np.asarray(sm).copy()
        arr[..., 3] = np.where(arr[..., 3] > 0, 255, 0)
        canvas.alpha_composite(Image.fromarray(arr), (x * k, y * k))
    out = np.asarray(canvas).copy()
    # 아래 1/4 은 쓰지 않는 칸: 살색으로 채워 둠(밉맵 없음)
    out[n * 3 // 4:, :, :3] = out[n * 3 // 4 - 1, :, :3]
    out[..., 3] = 255
    return out


def cell_geom(c, r, t):
    """칸 (c 열, r 행)의 가운데, 크기, y 회전(물결): 자세 시각 t 의 깃발 물결"""
    cw, ch = W / COLS, H / ROWS
    x0 = -W / 2 + c * cw
    x1 = x0 + cw
    yc = H / 2 - (r + 0.5) * ch

    def zc(x):
        return 0.11 * math.sin(2 * math.pi * t + x * 1.5) + 0.04 * math.sin(4 * math.pi * t - x * 2.3)

    z0, z1 = zc(x0), zc(x1)
    ang = -math.degrees(math.asin(max(-1.0, min(1.0, (z1 - z0) / cw))))
    return ((x0 + x1) / 2, yc, (z0 + z1) / 2), (cw * 1.02, ch), ang


def quadrant(c, r):
    sx = 1 if c >= COLS // 2 else -1
    sy = 1 if r == 0 else -1
    return next(n for n, (a, b) in CORNERS.items() if a == sx and b == sy)


def uv(c, r):
    u0 = 16.0 * c / COLS
    v0 = 12.0 * r / ROWS
    return (u0, v0, u0 + 16.0 / COLS, v0 + 12.0 / ROWS)


def build(k: kit.Kit):
    flesh = "flesh"
    tex = {}
    for e, f in EXPR.items():
        for st in range(4):
            tex[(e, st)] = (k.texture(f"mem_{e}{st}", membrane_tex(f, st), classes=True),
                            k.texture(f"mem_{e}{st}_n", membrane_tex(f, st, near=True), classes=True))
    m = k.model(poses=4, period=24, shrink=0.5,
                mut2=dict(root=lambda side: (side * 0.18, 0.2, BODY_Z - 0.1), dir=(0.5, 1.0, -0.6), length=0.6, segs=2, poses=2))
    for e in EXPR:
        for st in range(4):
            s = m.state(f"{e}{st}")
            far, near = tex[(e, st)]
            # 막: 칸마다 앞(먼, 가까운 얼굴)과 뒤(분홍 살), 자세마다 깃발 물결
            for fr in s.frames():
                with fr:
                    for c in range(COLS):
                        for r in range(ROWS):
                            q = quadrant(c, r)
                            (cx, cy, cz), size, ang = cell_geom(c, r, fr.t)
                            with s.when("!on:" + q):
                                for dist, tx in (("far", far), ("near", near)):
                                    s.quad((cx, cy, cz), size, "south", tx, uv=uv(c, r), dist=dist, face=True, rot=(0, ang, 0),
                                           origin=(cx, cy, cz))
                                    s.quad((cx, cy, cz - 0.01), size, "north", flesh, uv=(0, 0, 16, 16), dist=dist, rot=(0, ang, 0),
                                           origin=(cx, cy, cz))
            # 처진 귀퉁이(모서리 막대기가 끊김): 위 귀퉁이는 앞으로 접혀 늘어지고 아래 귀퉁이는 뒤로 처짐(자세 없음)
            for c in range(COLS):
                for r in range(ROWS):
                    q = quadrant(c, r)
                    (cx, cy, cz), size, _ = cell_geom(c, r, 0)
                    rx = 150 if r == 0 else 38
                    with s.when("on:" + q):
                        for dist, tx in (("far", far), ("near", near)):
                            s.quad((cx, cy, 0), size, "south", tx, uv=uv(c, r), dist=dist, face=True, rot=(rx, 0, 0), origin=(cx, 0, 0))
                        s.quad((cx, cy, -0.01), size, "north", flesh, uv=(0, 0, 16, 16), rot=(rx, 0, 0), origin=(cx, 0, 0))
            # 본체: 반 크기 얼굴(막 뒤, 손잡이처럼)
            h = s.head(size=0.42, depth=0.32, at=(0, 0, BODY_Z), face="smile")
            # 장대 넷: 본체에서 네 모서리로(끊기면 그루터기)
            for q, (sx, sy) in CORNERS.items():
                root = (sx * 0.15, sy * 0.15, BODY_Z + 0.1)
                tip = (sx * W / 2 * 0.97, sy * H / 2 * 0.97, 0.0)
                d = (tip[0] - root[0], tip[1] - root[1], tip[2] - root[2])
                L = math.sqrt(sum(v * v for v in d))
                with s.when("!on:" + q):
                    s.rod(root, d, length=L, segs=1, thick=0.1, toward=(0, 0, 1))
                with s.when("on:" + q):
                    s.rod(root, d, length=0.35, segs=1, thick=0.1, toward=(0, 0, 1), base=False)
            # 원형 DNA: 본체 뒤통수에서 짧은 막대기 둘이 펄럭
            for side in (1, -1):
                s.rod(h.point(side * 0.8, 0.2, -1), (side * 0.6, 0.3, -1), length=0.7, segs=2, thick=0.08,
                      wave=kit.Wave(amp=(22, 18), lag=55, phase=0 if side > 0 else 180), toward=(0, 1, 0))
    m.default = "s0"
    # 아이콘: 늘인 얼굴 막 전체(4:3)를 64 칸 가운데에
    mem = Image.fromarray(membrane_tex(EXPR["s"], 0)).crop((0, 0, 128, 96)).resize((64, 48), Image.NEAREST)
    icon = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    icon.paste(mem, (0, 8))
    k.icon(icon)


def stretch(seed=0, dur=0.6):
    """'쭈욱': 젖은 고무를 잡아당기는 끽끽 + 끈적한 잡음"""
    t = np.linspace(0, 1, int(dur * audio.SR), dtype=np.float32)
    f = 140 + 220 * t + 30 * np.sin(t * 40 + seed)
    ph = 2 * np.pi * np.cumsum(f) / audio.SR
    squeak = np.sign(np.sin(ph)) * 0.25 * (0.6 + 0.4 * np.sin(t * 90 + seed) ** 2)
    squeak = audio.lowpass(squeak.astype(np.float32), 1800)
    wet = audio.bandpass(audio.noise(dur, seed), 400, 2400) * 0.35
    return audio.normalize(audio.fade(audio.env(audio.mix(squeak, wet), 0.05, 0.1, 0.8, 0.15)), 0.8)


def sounds(k: kit.Kit):
    # 이 종의 신호: 젖은 고무 당기는 '쭈욱'
    k.sound("stretch", "쭈욱", synth=lambda i: stretch(seed=70 + i), attenuation=24)
    # 정면 탄이 막에 박힘(살처럼 움푹)
    k.sound("absorb", "막에 박히는 퍽", vanilla=[("block/mud/break1", 0.8), ("block/mud/break2", 0.85), ("block/mud/break3", 0.75)],
            volume=0.8, attenuation=20)
    k.sound("wrap", "막이 덮침", synth=lambda i: audio.wet_flap(seed=74 + i, dur=0.45, low=120), attenuation=16)
    k.sound("snap", "모서리 뚝", vanilla=[("random/break", 0.6), ("random/break", 0.7)], volume=0.9, attenuation=20)
    k.sound("gulp", "시체를 덮어 삼킴", synth=lambda i: audio.squish(seed=78 + i, dur=0.4), attenuation=20)


def phrases():
    return {"cause_g19": "펼친 얼굴에 감싸였다"}
