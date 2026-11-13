"""G77 하늘 발 게이브(1막 8라운드, 돌격): 구름에서 내려온 길이 9, 폭 5블록 맨발 하나. 발바닥이 통째로 늘인 _Gave 얼굴이고(아래를 봄, 이마가
발가락 쪽), 발가락 다섯 끝마다 작은 얼굴이 발톱처럼 붙었다. 복사뼈 자리에 굵은 엔드 막대기 둘이 못처럼 박혔다. 발바닥에 검은 때 얼룩 여섯(작은
찡그린 얼굴). 발목 위 살 기둥은 구름 속으로 올라가 끝이 안 보인다.

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림):
  gave:gabe/g77         발(가운데 = 발 몸 가운데, 앞 +Z 가 발가락, 발바닥 얼굴은 아래 -Y)
      ""        발가락 꼼지락(손으로 짠 4자세)
      "splay"   발가락이 쫙 펴짐(밟기 직전, 밟고 비비는 동안). 때 얼룩의 작은 얼굴이 입을 벌림
      부위 c1..c6: 그 때 얼룩이 씻김(on 이면 얼룩이 사라짐)
  gave:gabe/g77_leg     발목 위 살 기둥(뿌리 = 발목, 위로 22블록) + 끝을 덮은 구름
  gave:gabe/g77_shadow  바닥 발바닥 그림자(발 모양 점묘, 컷아웃). 상태 s1..s4 짙기
소리는 받은 소리(k.sample). 사체 없음(올라가 사라짐).
"""
import math

import numpy as np

from gabe import faces, kit

# 발 크기(블록)
W, L = 5.0, 7.2         # 발바닥 얼굴 폭(X), 길이(Z)
SOLE_Y = -1.0           # 발바닥 아랫면
SOLE_Z = -0.6           # 발바닥 얼굴 가운데 z
# 발가락(+X 가 발 자신의 왼쪽 = 엄지): x, 폭, 길이
TOES = [(1.75, 1.25, 1.5), (0.55, 0.95, 1.35), (-0.4, 0.88, 1.2), (-1.25, 0.8, 1.05), (-2.0, 0.7, 0.9)]
TOE_Z0 = SOLE_Z + L / 2 - 0.15
# 때 얼룩 자리(x, z)와 크기
GRIME = [(1.2, 1.6, 1.25), (-1.1, 1.2, 1.15), (0.25, -0.4, 1.35), (1.35, -2.1, 1.1), (-1.2, -2.5, 1.2), (0.15, -3.6, 1.0)]
ANKLE = (0.0, 1.0, -1.6)  # 발목 기둥 뿌리(Java LEG_AT 와 같음)


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def grime_tex(open_mouth):
    """검은 때 얼룩: 찡그린 작은 얼굴을 검게 물들이고 가장자리를 얼룩지게(투명)"""
    f = faces.Face(eyes="bloodshot", mouth="teeth_ring" if open_mouth else "dot", blush="none", tufts=False, tilt=18.0)
    im = faces.far(f).astype(np.float32)
    rng = np.random.RandomState(77 + open_mouth)
    n = im.shape[0]
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    r = np.hypot(xx - 15.5, yy - 15.5) / 16.0 + rng.rand(n, n) * 0.18
    rgb = im[..., :3]
    dark = rgb * np.array([0.22, 0.18, 0.16]) + np.array([18, 12, 10])
    # 눈, 입(짙은 부분)은 더 검게, 흰자는 누렇게
    lum = rgb.mean(axis=2, keepdims=True)
    dark = np.where(lum > 200, rgb * np.array([0.55, 0.5, 0.35]), dark)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(dark + rng.randn(n, n, 1) * 5, 0, 255)
    out[..., 3] = np.where(r < 0.98, 255, 0)
    return faces._fill_rest(out)


def cloud_tex(n=32, seed=7):
    """흐린 구름 덩이(회백, 점묘 없이 불투명, 결만)"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    v = 0.8 + 0.12 * np.sin(xx * 0.5 + yy * 0.3) + rng.rand(n, n) * 0.08
    col = np.stack([v * 214, v * 206, v * 210], axis=2)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255)
    out[..., 3] = 255
    return out


def shadow_tex(dens, w=96, h=160):
    """발바닥 꼴 그림자(위를 보는 판: 그림 왼쪽 = -X, 위쪽 = -Z 뒤꿈치): 발바닥 타원 + 발가락 동그라미 다섯, 가장자리로 성긴 점묘"""
    out = np.zeros((h, w, 4), np.uint8)
    bay = _bayer()
    for y in range(h):
        for x in range(w):
            bx = (x + 0.5) / w * 6.0 - 3.0
            bz = (y + 0.5) / h * 10.0 - 5.0
            sx = 2.6 + 0.25 * max(0.0, bz) / 4
            r = math.sqrt((bx / sx) ** 2 + ((bz - SOLE_Z + 0.2) / 4.05) ** 2)
            for tx, tw, tl in TOES:
                cz = TOE_Z0 + tl * 0.7
                r = min(r, math.hypot(bx - tx, (bz - cz) * 0.8) / (tw * 0.62))
            if r >= 1:
                continue
            d = dens * min(1.0, (1 - r) / 0.35)
            if bay[y % 4, x % 4] < d:
                v = 0.85 + 0.15 * math.sin(x * 0.7 + y * 0.45)
                out[y, x] = (int(30 * v), int(16 * v), int(22 * v), 255)
    return faces._fill_rest(out)


def icon_img():
    """HUD 아이콘: 늘인 얼굴 발바닥 + 발가락 다섯(위에서 본 실루엣)"""
    from PIL import Image, ImageDraw
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    sole = Image.fromarray(faces.far("smile")).resize((34, 46), Image.NEAREST)
    mask = Image.new("L", (34, 46), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, 33, 45), fill=255)
    im.paste(sole, (15, 16), mask)
    d = ImageDraw.Draw(im)
    for i, (tx, tw, tl) in enumerate(TOES):
        cx = 32 + tx * 6.5
        r = tw * 3.6
        d.ellipse((cx - r, 13 - r - (4 - i) * 0.6, cx + r, 13 + r - (4 - i) * 0.6), fill=(255, 210, 200, 255), outline=(150, 70, 70, 255))
    return im


def toe(s, i, bend, spread, nail):
    """발가락 하나: 뿌리(발바닥 앞 끝)에서 +Z 로 뻗은 살 상자 + 끝 얼굴(발톱). bend + 는 위로 젖힘, spread 는 옆으로 벌림(도)"""
    tx, tw, tl = TOES[i]
    root = (tx, SOLE_Y + 0.05, TOE_Z0)
    h = 0.82 - i * 0.06
    rot = (-bend, spread, 0)
    s.box((tx - tw / 2, SOLE_Y, TOE_Z0), (tx + tw / 2, SOLE_Y + h, TOE_Z0 + tl), "flesh", rot=rot, origin=root, face=True,
          faces=("south", "east", "west", "up", "down"))
    s.quad((tx, SOLE_Y + h / 2, TOE_Z0 + tl + 0.02), (tw * 0.92, h * 0.95), "south", nail, face=True, rot=rot, origin=root)


def foot(s, k, mode):
    flesh = k.part_tex("flesh")
    # 발바닥 = 늘인 얼굴(앞면이 아래, 이마가 발가락 쪽)
    s.head(size=W, height=L, depth=1.2, at=(0, SOLE_Y + 0.6, SOLE_Z), face="smile", rot=(90, 0, 0), sides=flesh, torn=False)
    # 발등, 뒤꿈치, 발목 뿌리
    s.box((-2.3, -0.4, -3.9), (2.3, 0.75, 2.4), flesh, face=True, faces=("north", "south", "east", "west", "up"))
    s.box((-1.85, 0.75, -3.4), (1.85, 1.55, 0.4), flesh, face=True, faces=("north", "south", "east", "west", "up"))
    s.box((-2.05, SOLE_Y + 0.1, -4.55), (2.05, 0.5, -4.05), flesh, face=True, faces=("north", "east", "west", "up", "down"))
    s.box((-1.45, 1.55, -2.95), (1.45, 2.6, -0.3), flesh, face=True, faces=("north", "south", "east", "west"))
    # 복사뼈 못(구조 막대기, 펄럭이지 않음)
    for side in (1, -1):
        s.rod((side * 1.55, 1.25, -1.6), (side * 1.0, 0.45, -0.1), length=1.7, segs=1, thick=0.32, wave=None, rag=False)
    # 때 얼룩(씻기면 사라짐)
    gt = k.texture("grime_open" if mode == "splay" else "grime", grime_tex(mode == "splay"))
    for i, (gx, gz, gs) in enumerate(GRIME, start=1):
        with s.when(f"!on:c{i}"):
            s.quad((gx, SOLE_Y - 0.03, gz), (gs, gs), "down", gt, rot=(0, 25 * ((i * 37) % 7 - 3), 0), origin=(gx, SOLE_Y - 0.03, gz))
    nail = k.face_tex(faces.Face(eyes="half", blush="deep", tufts=False))
    if mode == "splay":
        for i in range(5):
            toe(s, i, 22, (i - 1.6) * 7, nail)
    else:
        for f in s.frames(poses=4):
            with f:
                for i in range(5):
                    toe(s, i, 10 + 14 * math.sin(2 * math.pi * f.t + i * 1.1), 0, nail)


def leg(s, k):
    """발목 기둥: 마디마다 굵기가 조금씩 다른 살 상자(주름), 끝은 구름 덩이에 묻힘"""
    flesh = k.part_tex("flesh")
    y = 0.0
    segs = [(1.45, 3.4), (1.32, 3.6), (1.38, 3.8), (1.25, 3.6), (1.3, 3.8), (1.2, 3.8)]
    for i, (r, h) in enumerate(segs):
        dx = 0.12 * math.sin(i * 1.7)
        s.box((dx - r, y, -r), (dx + r, y + h, r), flesh, face=True, faces=("north", "south", "east", "west"))
        y += h
    cl = k.texture("cloud", cloud_tex())
    for (cx, cy, cz, rx, ry, rz) in [(0, 16.5, 0, 3.6, 1.6, 3.4), (1.8, 18.0, 1.2, 2.8, 1.8, 2.6), (-2.0, 17.6, -1.0, 2.9, 1.7, 2.8),
                                     (0.4, 19.6, -0.4, 3.2, 1.8, 3.0), (-0.6, 21.0, 1.4, 2.6, 1.4, 2.4)]:
        s.box((cx - rx, cy - ry, cz - rz), (cx + rx, cy + ry, cz + rz), cl)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=0.25, mut2=False, torn=False, rag=False)
    for mode in ("", "splay"):
        foot(m.state(mode), k, mode)
    lg = k.model("g77_leg", poses=1, period=20, shrink=0.064, mut2=False, open=False, torn=False, rag=False)
    leg(lg.state(""), k)
    sh = k.model("g77_shadow", poses=1, period=20, shrink=0.28, mut2=False, open=False, torn=False, rag=False)
    for i, dens in enumerate((0.25, 0.42, 0.6, 0.82), start=1):
        t = k.texture(f"shadow{i}", shadow_tex(dens))
        sh.state(f"s{i}").quad((0, 0, 0), (6, 10), "up", t)
    sh.default = "s1"
    k.icon(icon_img())


def sounds(k: kit.Kit):
    k.sample("horn", "tag:horn", "하늘이 울린다", pitch=0.55, attenuation=64)
    k.sample("stomp", "tag:stomp", "쿵쿵", pitch=0.55, attenuation=64)
    k.sample("rub", "tag:quake", "땅 비비는 소리", pitch=0.8, attenuation=40, n=2)


def phrases():
    return {"cause_g77": "하늘 발에 밟혔다", "cause_g77_quake": "하늘 발 땅울림"}
