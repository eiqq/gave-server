"""G91 빗줄기 커튼 게이브(2막 14라운드, 원거리 + 방해): 길이 16블록의 굵은 엔드 막대기 가로대 하나가 18블록 높이에 수평으로 떠 있다. 가로대에 엔드
막대기 술 스물넷이 발처럼 매달리고, 술 끝마다 작은 _Gave 얼굴 물방울이 달렸다. 가로대 한가운데에 2블록 얼굴 하나가 걸터앉듯 얹혀 하늘을 본다.
가로대 아래로 얼굴 물방울 비가 폭 3블록 띠로 쏟아진다.

모델:
  gave:gabe/g91        가로대(X 축, 길이 16) + 가운데 하늘 보는 얼굴. 앞 +Z 가 나아가는 쪽
  gave:gabe/g91_seg    비 띠 조각 하나(가로대 1/6 = 술 넷): 술 막대기 넷과 끝 얼굴 물방울, 그 아래 바닥(18블록 아래)까지 떨어지는 빗줄기(손으로 짠
                       4자세: 물방울이 흘러내림). 부위 t0..t3: 그 술이 끊김(술과 그 아래 비가 사라지고 그루터기만)
  gave:gabe/g91_shadow 2초 앞서 땅을 지나는 길쭉한 그림자 띠(16 x 3, 점묘)
"""
import math

import numpy as np

from gabe import faces, kit

BAR = 16.0
ALT = 18.0                 # 가로대 높이(Java ALT 와 같음): 비는 그 아래 바닥까지
SEG = BAR / 6              # 조각 폭
TASSEL = 2.3               # 술 길이
XS = [(-1.5 + j) * SEG / 4 for j in range(4)]   # 조각 안 술 자리


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def rain_tex(k_shift, w=8, h=256, seed=91, n=3):
    """빗줄기 한 줄(세로로 긴 그림): 분홍 물방울(작은 실눈 둘)들이 세로로 늘어서고, 자세 k 마다 1/4 주기씩 아래로 밀림. 빈 곳은 투명"""
    rng = np.random.RandomState(seed)
    out = np.zeros((h, w, 4), np.uint8)
    period = 32
    drops = [(rng.randint(0, period), rng.randint(1, w - 3)) for _ in range(n)]
    for base in range(0, h, period):
        for (dy, dx) in drops:
            y0 = (base + dy + k_shift * period // 4) % h
            for yy in range(5):
                for xx in range(3):
                    y = (y0 + yy) % h
                    edge = (yy in (0, 4)) and xx != 1
                    if edge:
                        continue
                    c = (246, 168, 176) if yy < 4 else (226, 128, 140)
                    out[y, dx + xx] = (*c, 255)
            # 실눈 둘(물방울 얼굴)
            out[(y0 + 2) % h, dx] = (120, 40, 44, 255)
            out[(y0 + 2) % h, dx + 2] = (120, 40, 44, 255)
            # 꼬리 줄
            for t in range(1, 7):
                if rng.rand() < 0.6:
                    out[(y0 - t) % h, dx + 1] = (250, 196, 202, 255)
    return faces._fill_rest(out)


def strip_tex(dens, w=96, h=24):
    out = np.zeros((h, w, 4), np.uint8)
    bay = _bayer()
    for y in range(h):
        for x in range(w):
            ex = abs((x + 0.5) / w * 2 - 1)
            ey = abs((y + 0.5) / h * 2 - 1)
            d = dens * min(1.0, (1 - ey) / 0.5) * min(1.0, (1 - ex) / 0.08)
            if bay[y % 4, x % 4] < d:
                out[y, x] = (32, 22, 32, 255)
    return faces._fill_rest(out)


def bar(s, k):
    s.rod((-BAR / 2, 0, 0), (1, 0, 0), length=BAR, segs=4, thick=0.55, wave=None, toward=(0, 0, 1), base=False, rag=False)
    # 끝 마개(살 덩이)
    for sx in (1, -1):
        s.box((sx * BAR / 2 - 0.45, -0.45, -0.45), (sx * BAR / 2 + 0.45, 0.45, 0.45), "flesh", face=True)
    # 걸터앉은 얼굴(하늘을 봄) + 받침 살
    s.box((-0.7, 0.2, -0.6), (0.7, 0.55, 0.6), "flesh", face=True)
    s.head(size=2.0, depth=0.9, at=(0, 1.0, 0), face="half", rot=(-90, 0, 0), torn=False)


def seg(s, k):
    """조각 하나: 술 넷(끊기면 그루터기) + 끝 얼굴 물방울 + 그 아래 빗줄기(4자세)"""
    drop_faces = [faces.Face(eyes="half", blush="deep"), faces.Face(), faces.Face(extras=("tears",)), faces.Face(eyes="open")]
    rains = [k.texture(f"rain{i}", rain_tex(i)) for i in range(4)]
    wide = [k.texture(f"rainw{i}", rain_tex(i, w=32, seed=92, n=9)) for i in range(4)]
    top = -TASSEL - 0.3
    for j, x in enumerate(XS):
        with s.when(f"!on:t{j}"):
            s.rod((x, -0.2, 0), (0.0, -1, 0.02), length=TASSEL, segs=1, thick=0.12, wave=None, toward=(0, 0, 1), base=False, rag=False)
            s.head(size=0.5, depth=0.4, at=(x, -TASSEL - 0.15, 0), face=drop_faces[j], near=False, open=False, torn=False)
        with s.when(f"on:t{j}"):
            s.rod((x, -0.2, 0), (0.0, -1, 0.02), length=0.5, segs=1, thick=0.12, wave=None, toward=(0, 0, 1), base=False, rag=False)
    h = ALT - TASSEL - 0.3
    for f in s.frames(poses=4):
        with f:
            for j, x in enumerate(XS):
                with s.when(f"!on:t{j}"):
                    cy = top - h / 2
                    t = rains[(f.k + j) % 4]
                    tw = wide[(f.k + j) % 4]
                    s.quad((x, cy, 1.1), (0.62, h), "south", t, uv=(0, 0, 16, 16))
                    s.quad((x, cy, -1.1), (0.62, h), "north", t, uv=(0, 0, 16, 16))
                    s.quad((x, cy, 0), (2.2, h), "east", tw, uv=(0, 0, 16, 16))
                    s.quad((x, cy, 0), (2.2, h), "west", tw, uv=(0, 0, 16, 16))


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.17, mut2=False, torn=False, rag=False)
    bar(m.state(""), k)
    sg = k.model("g91_seg", poses=4, period=8, shrink=0.08, mut2=False, open=False, torn=False, rag=False)
    seg(sg.state(""), k)
    sh = k.model("g91_shadow", poses=1, period=20, shrink=0.17, mut2=False, open=False, torn=False, rag=False)
    sh.state("").quad((0, 0, 0), (16, 3), "up", k.texture("shadow", strip_tex(0.7)))
    k.icon(icon_img(k))


def icon_img(k):
    from PIL import Image, ImageDraw
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((2, 12, 61, 16), fill=(220, 214, 230, 255), outline=(150, 140, 160, 255))
    for i in range(10):
        x = 5 + i * 6
        d.line((x, 17, x, 24), fill=(220, 214, 230, 255), width=1)
        d.ellipse((x - 2, 24, x + 2, 28), fill=(246, 168, 176, 255))
        for y in range(32, 62, 7):
            d.ellipse((x - 1, y + (i % 3) * 2, x + 1, y + 3 + (i % 3) * 2), fill=(246, 168, 176, 255))
    face = Image.fromarray(faces.far("half")).resize((14, 14), Image.NEAREST)
    im.alpha_composite(face, (25, 0))
    return im


def sounds(k: kit.Kit):
    k.sample("rain", "tag:rain", "얼굴 빗줄기", attenuation=64, pitch=0.9)
    k.sample("snap", "tag:crack", "술 끊김", pitch=1.3, attenuation=40, n=2)


def phrases():
    return {"cause_g91": "얼굴 빗줄기에 젖었다"}
