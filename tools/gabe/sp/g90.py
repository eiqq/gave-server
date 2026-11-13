"""G90 등불 나방 게이브(2막, 떼, 목표 압박): 0.4 블록 얼굴 양옆에 세모꼴 날개 판 둘, 날개마다 _Gave 얼굴이 눈알 무늬처럼 하나씩.

날개 판은 얼굴과 같은 쪽(+Z 앞)을 보고 몸 옆 뿌리를 축으로 위아래로 친다: 앞에서 보면 얼굴 셋이 나란하다.
이마에 짧은 엔드 막대기 더듬이 둘(곧게, 자세 없음), 뒤로 짧은 살 배.

- gave:gabe/g90 상태 ""(날갯짓 자세 4, 8틱 한 바퀴), "sit"(날개를 뒤로 접어 등에 붙임, 자세 없음).
  떼 역할이라 오래 살면 변함에서 빠짐: 변이 2 막대기, 입 찢어짐 끔.
- 덮개 g90_dust: 얼굴 앞을 맴돌 때 화면 가장자리에 끼는 날개 가루.
소리 flutter(날개 파르르, 16).
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

FACE = 0.4
WING_W, WING_H = 0.56, 0.5
ROOT_X = 0.2
DUST = (214, 188, 170)


def wing_img(mirror):
    """세모 날개(뿌리 쪽 세로 변 + 바깥 위로 솟은 끝)에 얼굴 무늬. mirror 면 좌우를 뒤집음(오른 날개)"""
    n = 32
    rng = np.random.RandomState(90 + int(mirror))
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    # 세모: (0, 0) 뿌리 위, (0, 31) 뿌리 아래, (31, 8) 끝(바깥, 조금 위)
    a = np.array((0.0, 0.0)), np.array((0.0, 31.0)), np.array((31.0, 8.0))

    def side(p, q):
        return (xx - q[0]) * (p[1] - q[1]) - (p[0] - q[0]) * (yy - q[1])
    s1, s2, s3 = side(a[0], a[1]), side(a[1], a[2]), side(a[2], a[0])
    inside = ((s1 <= 0) & (s2 <= 0) & (s3 <= 0)) | ((s1 >= 0) & (s2 >= 0) & (s3 >= 0))
    out = np.zeros((n, n, 4), np.uint8)
    base = np.array(DUST, np.float32) * (0.88 + rng.rand(n, n, 1) * 0.14)
    # 날개 맥: 뿌리에서 퍼지는 짙은 줄
    ang = np.arctan2(yy - 15.5, xx + 0.5)
    vein = (np.abs(np.sin(ang * 7.0)) < 0.12) & (xx > 3)
    base = np.where(vein[..., None], base * 0.62, base)
    # 가장자리 짙은 테
    edge = inside & ~(np.roll(inside, 1, 0) & np.roll(inside, -1, 0) & np.roll(inside, 1, 1) & np.roll(inside, -1, 1))
    base = np.where(edge[..., None], base * 0.45, base)
    out[..., :3] = np.clip(base, 0, 255)
    out[..., 3] = np.where(inside, 255, 0)
    # 눈알 무늬 = _Gave 얼굴(먼 얼굴 32 를 13 칸으로)
    fs = 15
    f = Image.fromarray(faces.far("smile")).resize((fs, fs), Image.NEAREST)
    fa = np.array(f)
    oy, ox = 7, 3
    m = (fa[..., 3] > 0) & inside[oy:oy + fs, ox:ox + fs]
    reg = out[oy:oy + fs, ox:ox + fs]
    reg[m] = fa[m]
    out[oy:oy + fs, ox:ox + fs] = reg
    if mirror:
        out = out[:, ::-1].copy()
    return Image.fromarray(out, "RGBA")


def dust_cover():
    """화면 가장자리 날개 가루(가운데는 비움)"""
    w, h = 480, 270
    rng = np.random.RandomState(9090)
    out = np.zeros((h, w, 4), np.uint8)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    # 가장자리에서 가운데로 갈수록 드물게
    dx = np.minimum(xx, w - 1 - xx) / (w * 0.5)
    dy = np.minimum(yy, h - 1 - yy) / (h * 0.5)
    d = np.minimum(dx, dy)
    p = np.clip(0.55 - d * 1.6, 0, 1) ** 1.6
    speck = rng.rand(h, w) < p * 0.5
    # 알갱이를 2x2 로 굵게
    big = speck | np.roll(speck, 1, 0) | np.roll(speck, 1, 1)
    col = np.array(DUST, np.float32) * (0.7 + rng.rand(h, w, 1) * 0.4)
    out[..., :3] = np.clip(col, 0, 255)
    out[..., 3] = np.where(big, 255, 0)
    return Image.fromarray(out, "RGBA")


def wings(s, tl, tr, ang, sweep=0.0, droop=0.0):
    """날개 둘: 뿌리(몸 옆 세로 변)를 축으로 ang 도 위로 침. sweep 은 뒤로 접는 각, droop 은 아래로 처짐"""
    for side, tex in ((1, tl), (-1, tr)):
        root = (side * ROOT_X, 0.0, -0.02)
        c = (side * (ROOT_X + WING_W / 2), 0.0, -0.02)
        rot = (0.0, side * (8 + sweep), side * ang - side * droop)
        for facing in ("south", "north"):
            s.quad(c, (WING_W, WING_H), facing, tex, rot=rot, origin=root, face=False)


def body(s, k):
    h = s.head(size=FACE, depth=0.3, face="smile")
    # 배: 뒤로 짧은 살 덩이 둘
    s.box((-0.1, -0.12, -0.42), (0.1, 0.08, -0.15), "flesh", face=True)
    s.box((-0.07, -0.1, -0.62), (0.07, 0.04, -0.42), "flesh", face=True)
    # 더듬이: 이마에서 위 앞으로 곧게(자세 없음)
    for side in (1, -1):
        s.rod(h.point(side * 0.45, 0.9, 0.5), (side * 0.45, 1.0, 0.55), length=0.4, segs=1, thick=0.03, base=False, rag=False)
    return h


def build(k: kit.Kit):
    tl = k.texture("wing_l", wing_img(False))
    tr = k.texture("wing_r", wing_img(True))
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    s = m.state("")
    body(s, k)
    angles = (38, 12, -24, 6)
    for f in s.frames():
        with f:
            wings(s, tl, tr, angles[f.k])
    sit = m.state("sit", poses=1)
    body(sit, k)
    wings(sit, tl, tr, 8, sweep=62, droop=8)
    k.cover("g90_dust", dust_cover())


def sounds(k: kit.Kit):
    k.sample("flutter", "tag:flap", "날개 파르르", pitch=1.5, volume=0.8, attenuation=16)


def phrases():
    return {"cause_g90": "나방이 얼굴을 뒤덮었다", "notice_g90_lamp": "등불이 가려진다", "notice_g90_out": "등불이 꺼졌다"}
