"""G170 똬리 게이브(2막, 함정, 방해: 지름 15 블록 똬리).

얼굴 마디가 이어진 굵기 1.2 블록 긴 몸이 땅에 달팽이 껍데기처럼 두 바퀴 감겨 누웠다. 마디마다 _Gave 얼굴이 박혀 모두 한가운데를 보고
(안쪽 옆면), 마디 사이로 엔드 막대기 가시가 하나씩 비스듬히 누워 감긴 방향(가운데 쪽)을 가리킨다. 한가운데에 2.4 블록 얼굴(머리)이
위를 보고 누웠고, 꼬리 끝은 바깥 고리에서 가늘어진다.

- gave:gabe/g170(shrink 0.5): 머리. 몸 가운데(0, 0, 0) = 엔진 pos = 똬리 가운데, 마디 높이의 가운데(바닥에서 HOVER 0.6).
  상태 ""(다문 입), "bite"(이빨 고리 입을 벌리고 눈 뜸).
- gave:gabe/g170_in, g170_out(shrink 0.18): 나선 r = R0 + B x th(th 0..TH, 수학 각 th: x = r cos th, z = r sin th)의 안쪽 반과
  바깥쪽 반. 부품 가운데 = 똬리 가운데. Java 가 y 둘레로 돌린다(Ry(-d) 가 마디를 각 +d 로 옮김). 상태 ""(가시가 누움), "up"(가시가 곤두섬).
소리 coil(똬리 감기는 소리, 40), bite(머리가 묾, 24), cross(마디가 묾, 16), squeeze(조이기, 48).
"""
import math

import numpy as np

from gabe import faces, kit

R0, R1, TURNS = 2.1, 7.0, 2.0
TH = TURNS * 2 * math.pi
B = (R1 - R0) / TH
THICK, HALF_H = 1.2, 0.6
SEG = 1.45
HEAD = 2.4
SPLIT = 0.5   # 안쪽 부품이 맡는 길이 몫


def spiral():
    """(th, r) 마디 가운데 목록: 길이 SEG 마다"""
    out = []
    th = 0.25
    while th < TH:
        r = R0 + B * th
        out.append((th, r))
        th += SEG / math.hypot(r, B)
    return out


def point(th, r):
    return np.array((r * math.cos(th), 0.0, r * math.sin(th)))


def tangent(th, r):
    t = np.array((B * math.cos(th) - r * math.sin(th), 0.0, B * math.sin(th) + r * math.cos(th)))
    return t / np.linalg.norm(t)


def segments(s, k, segs, up, i0, n_all):
    tex_top, tex_out = k.part_tex("flesh"), k.skin_tex("back")
    closed, opened = k.face_tex("smile"), k.face_tex("open")
    shut = [k.face_tex(f) for f in ("smile", "smile_a", "half", "smile_c", "tears")]
    for j, (th, r) in enumerate(segs):
        idx = i0 + j
        c = point(th, r)
        t = tangent(th, r)
        phi = math.degrees(math.atan2(-t[2], t[0]))
        # 꼬리: 마지막 넷은 가늘어짐
        taper = min(1.0, max(0.45, (n_all - 1 - idx) / 4.0 + 0.3))
        hh = HALF_H * taper
        hw = THICK / 2 * taper
        hl = SEG / 2 + 0.12
        lo = (c[0] - hl, -HALF_H, c[2] - hw)
        hi = (c[0] + hl, -HALF_H + 2 * hh, c[2] + hw)
        # 상자 앞(+Z, south)은 Ry(phi) 뒤 안쪽을 봄: 윗면 살, 바깥 옆면 뒤통수, 안쪽 옆면은 얼굴 판이 덮음
        s.box(lo, hi, {"up": tex_top, "north": tex_out, "south": tex_out}, faces=("up", "north", "south"), uv=[0, 0, 16, 16],
              rot=(0, phi, 0), origin=tuple(c), face=True)
        fc = (c[0], -HALF_H + hh, c[2] + hw + 0.02)
        size = (min(2 * hl - 0.25, 1.2) * taper, 2 * hh - 0.1)
        with s.when("!open"):
            s.quad(fc, size, "south", shut[idx % len(shut)], face=True, rot=(0, phi, 0), origin=tuple(c))
        with s.when("open"):
            s.quad(fc, size, "south", opened if idx % 3 else k.face_tex("bloodshot"), face=True, rot=(0, phi, 0), origin=tuple(c))
        # 마디 사이 가시: 둘에 하나, 윗면 바깥쪽 끝에서 가운데 쪽(감긴 방향, th 가 줄어드는 쪽)을 가리켜 누움. up 이면 곤두섬
        if idx % 2 == 0 and taper > 0.6:
            inward = -c / max(1e-6, np.linalg.norm(c))
            root = c + t * (-hl + 0.1) + np.array((0, HALF_H - 0.05, 0)) - inward * 0.25
            if up:
                d = -t * 0.25 + inward * 0.15 + np.array((0, 1.0, 0))
                L = 1.25
            else:
                d = -t * 1.0 + inward * 0.15 + np.array((0, 0.32, 0))
                L = 1.15
            s.rod(tuple(root), tuple(d), length=L, segs=1, thick=0.14, base=False, toward=(0, 1, 0) if not up else tuple(t), rag=False)
    # 꼬리 끝 막대기 둘(땅을 쓸며 끌림)
    if i0 + len(segs) == n_all and segs:
        th, r = segs[-1]
        c = point(th, r)
        t = tangent(th, r)
        for side in (1, -1):
            nrm = np.array((-t[2], 0, t[0])) * side
            s.rod(tuple(c + t * 0.6 + np.array((0, -0.3, 0))), tuple(t + nrm * 0.35 + np.array((0, -0.05, 0))), length=1.4, segs=1, thick=0.13,
                  base=False, toward=(0, 1, 0), rag=False)


def head(s, bite):
    f = faces.Face(eyes="open", mouth="teeth_ring", blush="deep") if bite else "smile"
    # 얼굴이 하늘(+Y)을 봄: x 둘레 -90 도
    s.head(size=HEAD, depth=1.5, at=(0, 0.15, 0), face=f, near=False, rot=(-90, 0, 0))
    # 머리 둘레 살 깃(마디가 머리에 이어짐)
    for a in range(0, 360, 60):
        r = math.radians(a)
        c = (1.45 * math.cos(r), -0.25, 1.45 * math.sin(r))
        s.box((c[0] - 0.45, -0.6, c[2] - 0.45), (c[0] + 0.45, 0.1, c[2] + 0.45), "flesh", faces=("up", "north", "south", "east", "west"),
              rot=(0, -a, 0), origin=c, face=True)
    s.front = (0, 1, 0)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.5, mut2=False, torn=False)
    head(m.state(""), False)
    head(m.state("bite"), True)
    segs = spiral()
    n = len(segs)
    cut = 0
    total = sum(1 for _ in segs)
    acc = 0.0
    for i, (th, r) in enumerate(segs):
        acc += 1
        if acc >= total * SPLIT:
            cut = i + 1
            break
    for name, part, i0 in (("g170_in", segs[:cut], 0), ("g170_out", segs[cut:], cut)):
        pm = k.model(name, poses=1, period=20, shrink=0.18, mut2=False, open=True, torn=False, rag=False)
        for st, up in (("", False), ("up", True)):
            s = pm.state(st)
            segments(s, k, part, up, i0, n)
            s.front = (0, 0, 0)
    k.icon(model="g170", state="bite")


def sounds(k: kit.Kit):
    k.sample("coil", "tag:wind", "똬리 감기는 소리", pitch=0.8, attenuation=40)
    k.sample("bite", "tag:eat", "똬리 머리가 묾", pitch=0.7, attenuation=24)
    k.sample("cross", "tag:eat", "마디 얼굴이 묾", pitch=1.15, attenuation=16)
    k.sample("squeeze", "tag:creak", "똬리가 조임", pitch=0.6, attenuation=48)


def phrases():
    return {"cause_g170": "똬리 한가운데 머리에 물렸다", "cause_g170_cross": "똬리 마디를 넘다 물렸다", "cause_g170_grind": "똬리 한가운데서 갈렸다"}
