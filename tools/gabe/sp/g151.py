"""G151 꿰뚫는 작살 게이브: 길이 2.2 블록 엔드 막대기 하나가 작살 자루. 앞끝에 미늘 둘 달린 쇳빛 촉, 뒤끝에 0.4 블록 얼굴이 꿰여 있다
(얼굴을 옆으로 눕혀 꿰어 실눈이 세로로 길다). 얼굴 뒤로 분홍 살 줄 한 가닥이 끌리고 줄 끝에 짧은 막대기 하나가 매듭처럼 묶였다.

모델 gave:gabe/g151(shrink 0.8) 상태 ""(끌리는 줄 2자세). 앞 +Z 가 촉.
모델 gave:gabe/g151_rope: Java 가 연출 디스플레이에 실어 늘이는 줄(+Z 로 길이 1 블록, along 행렬로 늘임)
  ""     팽팽한 살 줄(지나간 선 위, 가슴 높이)
  "line" 겨누는 선(땅에 그어지는 가는 분홍 띠)
가운데(몸 자리) = 자루 가운데(Java hover 1.2).
소리 ring(찡, 48), pierce(꿰뚫음, 32), reel(줄 감는 소리, 16).
"""
import numpy as np

from gabe import faces, kit

SLIT = faces.Face(eyes="squint", mouth="dot", blush="pink")


def _rgba(arr):
    return np.clip(np.round(arr), 0, 255).astype(np.uint8)


def steel_tex(n=8):
    out = np.zeros((n, n, 4), np.float32)
    g = np.linspace(150, 210, n)[None, :, None]
    out[..., :3] = np.concatenate([g, g + 4, g + 10], axis=2)
    out[0, :, :3] = (235, 238, 242)
    out[..., 3] = 255
    return _rgba(out)


def line_tex(n=8):
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = (255, 96, 150)
    out[:, 3:5, :3] = (255, 200, 220)
    out[..., 3] = 255
    return _rgba(out)


def build(k: kit.Kit):
    m = k.model(poses=2, period=12, shrink=0.8, mut2=False)
    s = m.state("")
    st = k.texture("steel", steel_tex())
    # 자루(구조 막대기)
    s.rod((0, 0, -1.1), (0, 0, 1), length=2.2, segs=2, thick=0.1, base=False, rag=False)
    # 촉: 가운데 송곳 + 미늘 둘
    s.box((-0.07, -0.07, 1.08), (0.07, 0.07, 1.42), st, uv=[0, 0, 4, 8], rot=(0, 0, 45), origin=(0, 0, 1.25))
    s.box((-0.03, -0.03, 1.4), (0.03, 0.03, 1.5), st, uv=[0, 0, 2, 2])
    for side in (1, -1):
        s.box((side * 0.05 - 0.025, -0.025, 0.95), (side * 0.05 + 0.025, 0.025, 1.22), st, uv=[0, 0, 2, 8], rot=(0, side * -32, 0),
              origin=(side * 0.04, 0, 1.2))
    # 꿰인 얼굴(옆으로 눕혀 실눈이 세로)
    s.head(size=0.42, depth=0.3, at=(0, 0, -0.78), face=SLIT, rot=(0, 0, 90))
    # 끌리는 살 줄(2자세) + 끝의 매듭 막대기
    ch = s.band((0, -0.04, -0.95), (0, -0.25, -1), length=0.9, width=0.08, segs=2, thick=0.04, tex="flesh",
                wave=kit.Wave(amp=(10, 16), lag=60), toward=(0, 1, 0))
    for f in s.frames(2):
        with f:
            tx, ty, tz = ch.tip(f.k)
            s.box((tx - 0.16, ty - 0.035, tz - 0.035), (tx + 0.16, ty + 0.035, tz + 0.035), "rod", uv=[0, 0, 4, 1])

    r = k.model("g151_rope", poses=1, mut2=False, open=False, torn=False, rag=False)
    rs = r.state("")
    rs.box((-0.05, -0.05, 0), (0.05, 0.05, 1), "flesh", uv=[0, 0, 2, 16])
    rs.front = (0, 0, 0)
    ln = r.state("line")
    ln.box((-0.07, 0, 0), (0.07, 0.01, 1), {"up": k.texture("line", line_tex())}, faces=("up",), uv=[0, 0, 8, 8])
    ln.front = (0, 0, 0)


def sounds(k: kit.Kit):
    k.sample("ring", "tag:glass", "찡", pitch=1.6, attenuation=48)
    k.sample("pierce", "kenney_impact/impactmetal_heavy_00*", "꿰뚫는 소리", pitch=0.7, attenuation=32)
    k.sample("reel", "tag:scratch", "줄 감는 소리", pitch=0.8, attenuation=16)


def phrases():
    return {"cause_g151": "작살에 꿰뚫렸다", "cause_g151_rope": "작살 줄에 걸렸다"}
