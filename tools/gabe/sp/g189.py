"""G189 꼭 맞는 구멍 게이브(4막 32라운드, 함정 + 방해): 사람 하나가 꼭 들어갈 지름 1.4블록의 구멍.

테두리는 도톰한 분홍 입술 고리(살 마디 열여섯), 안은 아래로 꺼져 보이는 검붉은 바닥 판(가운데로 갈수록 검게)이고, 그 위에 작은 _Gave 얼굴들이
나선으로 깔려 위를 올려다본다(가운데로 갈수록 작게: 깊어 보이게, 4자세로 천천히 돎). 입술 양옆에 짧은 엔드 막대기 둘이 문설주처럼 꽂혀 있다
(부위 lean 이면 임자 쪽(+Z)으로 기욺).

- gave:gabe/g189: 구멍 가운데 바닥(0, 0, 0) = 엔진 pos(바닥 + 0.02). 상태 ""(벌린 입술), "call"(입술이 오므라듦: 부름 0.5초 전과 부름 동안),
  "bite"(입술이 위로 말려 올라와 목까지 조임).
소리 call(구멍이 부르는 소리, 임자에게만 크게, 24), gulp(삼킴, 16), snap(발을 묾, 16).
"""
import math

import numpy as np

from gabe import faces, kit

R = 0.7
SPIRAL = (faces.Face(eyes="open"), faces.Face(eyes="half"), faces.Face(), faces.Face(eyes="open", mouth="torn"), faces.Face(extras=("tears",)))


def pit_tex(n=64):
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    r = np.hypot(xx - c, yy - c) / (n / 2)
    a = np.arctan2(yy - c, xx - c)
    inside = r < 1.0
    # 가장자리 검붉은 살에서 가운데 거의 검정으로, 나선 골
    v = np.clip(r, 0, 1) ** 1.6
    swirl = 0.5 + 0.5 * np.sin(a * 3 + r * 9)
    red = 18 + 90 * v * (0.75 + 0.25 * swirl)
    out[..., 0] = np.clip(red, 0, 255)
    out[..., 1] = np.clip(6 + 22 * v, 0, 255)
    out[..., 2] = (np.clip(10 + 26 * v, 0, 255).astype(np.uint8) & 0xF8)
    out[..., 3] = np.where(inside, 255, 0)
    return faces._fill_rest(out)


def lips(s, k, r, h, curl):
    """입술 고리: 살 마디 열여섯을 둘러 세움. curl 이면 위로 말려 안쪽으로 기욺"""
    n = 16
    seg = 2 * r * math.tan(math.pi / n) * 1.25
    for i in range(n):
        a = i * 360.0 / n
        rr = math.radians(a)
        cx, cz = math.sin(rr) * r, math.cos(rr) * r
        rot = (-curl, a, 0) if curl else (0, a, 0)
        s.box((cx - seg / 2, 0.0, cz - 0.11), (cx + seg / 2, h, cz + 0.11), "flesh", face=True, rot=rot, origin=(cx, 0.0, cz))


def pit(s, k, spin=True):
    s.quad((0, 0.03, 0), (2 * R * 0.98, 2 * R * 0.98), "up", k.texture("pit", pit_tex()), face=True)
    spots = []
    for j in range(9):
        t = j / 9
        spots.append((0.58 - 0.45 * t, j * 0.95, 0.27 - 0.17 * t, SPIRAL[j % len(SPIRAL)]))
    if not spin:
        for rr, a, sz, f in spots:
            s.quad((math.cos(a) * rr, 0.045, math.sin(a) * rr), (sz, sz), "up", k.face_tex(f), face=True, rot=(0, -math.degrees(a) - 90, 0),
                   origin=(math.cos(a) * rr, 0.045, math.sin(a) * rr))
        return
    for fr in s.frames(poses=4):
        with fr:
            for rr, a, sz, f in spots:
                a2 = a + fr.t * (math.tau / 9)
                c = (math.cos(a2) * rr, 0.045, math.sin(a2) * rr)
                s.quad(c, (sz, sz), "up", k.face_tex(f), face=True, rot=(0, -math.degrees(a2) - 90, 0), origin=c)


def posts(s):
    for side in (1, -1):
        root = (side * (R + 0.12), 0.0, 0.0)
        with s.when("!on:lean"):
            s.rod(root, (side * 0.05, 1, 0), length=0.75, segs=1, thick=0.1, base=False, wave=None, rag=False, toward=(0, 0, 1))
        with s.when("on:lean"):
            s.rod(root, (side * 0.05, 1, 0.75), length=0.75, segs=1, thick=0.1, base=False, wave=None, rag=False, toward=(1, 0, 0))
        s.box((side * (R + 0.12) - 0.09, 0.0, -0.09), (side * (R + 0.12) + 0.09, 0.12, 0.09), "flesh", face=True)


def build(k: kit.Kit):
    m = k.model(poses=4, period=48, mut2=False, open=False, torn=False)
    st = m.state("")
    pit(st, k)
    lips(st, k, R, 0.16, 0)
    posts(st)
    st.front = (0, 1, 0)
    ca = m.state("call")
    pit(ca, k)
    lips(ca, k, R * 0.82, 0.24, 25)
    posts(ca)
    ca.front = (0, 1, 0)
    bt = m.state("bite")
    pit(bt, k, spin=False)
    lips(bt, k, R * 0.55, 0.42, 40)
    posts(bt)
    bt.front = (0, 1, 0)
    k.icon(model="g189", state="", view="top")


def sounds(k: kit.Kit):
    k.sample("call", ["kenney_scifi/forcefield_000", "kenney_scifi/forcefield_002", "oga_generator/loop_generator_1"], "구멍이 부르는 소리",
             pitch=0.5, attenuation=24, level=-12)
    k.sample("gulp", "tag:eat", "삼키는 소리", pitch=0.6, attenuation=16)
    k.sample("snap", "oga_creature80/eat_02", "발을 무는 소리", pitch=0.9, attenuation=16, n=1)


def phrases():
    return {"cause_g189": "꼭 맞는 구멍에 빠졌다", "cause_g189_bite": "남의 구멍에 발을 물렸다"}
