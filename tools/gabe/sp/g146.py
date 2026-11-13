"""G146 고리 문 게이브(4막, 목표 압박, 지원: 지름 3.8 블록 고리 둘이 한 짝).

땅에 세로로 선 고리. 테는 입술처럼 붉은 살(도톰한 살 토막을 둥글게 이음)이고 테 둘레에 실눈 얼굴 여덟이 박혔다(약점, 맞아 감기면 바늘땀
감긴 눈). 엔드 막대기 둘이 버팀목처럼 양옆에서 고리를 받치고, 꼭대기에 막대기 하나가 풍향계처럼 돈다(자세 4).
고리 안은 일렁이는 분홍 막(컷아웃 점묘 원판, 자세 4 로 일렁임) 너머로 줄 선 얼굴 떼가 비친다.

- gave:gabe/g146(shrink 0.6): 고리. 고리 가운데(0, 0, 0) = 엔진 pos(바닥 위 HOVER 2.0), 앞(+Z)이 막이 보는 쪽. 부위 e0..e7(그 얼굴이 감김).
  상태 ""(벌어짐).
- gave:gabe/g146_veil(shrink 0.7): 막. 가운데 (0, 0, 0). 막 뒤로 줄 선 얼굴 다섯이 멀어지며 비침. Java 가 불룩함을 z 배율로.
소리 open(문 열림, 64), beat(심장 박동, 48), shut(고리가 닫힘, 64), bite(테가 물음, 24), pass(무언가 넘어옴, 32), drag(막으로 끌려감, 32).
"""
import math

import numpy as np

from gabe import faces, kit

R = 1.6               # 테 반지름(지름 3.8 의 테 가운데)
EYES = 8
CLOSED = faces.Face(eyes="none", mouth="dot", extras=("stitch",), blush="deep")
RIM_FACES = ("smile", "smile_a", "smile_c", "smile", "half", "smile_a", "smile_c", "smile")


def stick(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    up = abs(d[1]) > 0.97 * L
    s.rod(tuple(a), tuple(d), length=L, segs=1, thick=thick, base=False, rag=False, tex=tex, toward=(0, 0, 1) if up else (0, 1, 0))


def eye_angle(i):
    """얼굴 i 의 테 위 각(라디안, 0 = 몸 왼쪽 +X, 반시계). Java G146.eyeAngle 과 같게"""
    return 2 * math.pi * (i + 0.5) / EYES


def membrane_tex():
    """분홍 막: 원 밖 투명, 안은 점묘(2 px 칸 엇갈림) + 가장자리 진한 살"""
    n = 64
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r > 1.0:
                continue
            edge = r > 0.86
            dot = ((x // 2) + (y // 2)) % 2 == 0 or ((x // 2) % 3 == 0 and (y // 2) % 2 == 1)
            if not edge and not dot:
                continue
            col = (0xC8, 0x4E, 0x5F) if edge else ((0xF2, 0x9A, 0xA8) if (x + y) % 5 else (0xE0, 0x6F, 0x82))
            out[y, x, :3] = col
            out[y, x, 3] = 255
    return out


def ring(s, k):
    # 입술 테: 살 토막 열여섯을 둥글게(앞뒤로 도톰)
    n = 16
    for i in range(n):
        a = 2 * math.pi * i / n
        c = (math.cos(a) * R, math.sin(a) * R, 0)
        rz = math.degrees(a) + 90
        seg = 2 * math.pi * R / n * 1.12
        s.box((c[0] - seg / 2, c[1] - 0.26, -0.32), (c[0] + seg / 2, c[1] + 0.26, 0.32), "flesh", rot=(0, 0, rz), origin=c, face=True)
    # 안쪽 입술 가장자리(진한 살 띠 둘레)
    for i in range(n):
        a = 2 * math.pi * (i + 0.5) / n
        c = (math.cos(a) * (R - 0.3), math.sin(a) * (R - 0.3), 0.22)
        rz = math.degrees(a) + 90
        seg = 2 * math.pi * (R - 0.3) / n * 1.1
        s.box((c[0] - seg / 2, c[1] - 0.08, -0.1), (c[0] + seg / 2, c[1] + 0.08, 0.1), "wetskin", rot=(0, 0, rz), origin=c, face=True)
    # 테 얼굴 여덟(앞을 봄, 테를 따라 돌려 붙임): 감기면 바늘땀 감긴 얼굴
    for i in range(EYES):
        a = eye_angle(i)
        c = (math.cos(a) * R, math.sin(a) * R, 0.15)
        rz = math.degrees(a) - 90
        with s.when(f"!on:e{i}"):
            s.head(size=0.78, depth=0.4, at=c, face=RIM_FACES[i], near=True, rot=(0, 0, rz), torn=False)
        with s.when(f"on:e{i}"):
            s.head(size=0.7, depth=0.36, at=(c[0], c[1], 0.1), face=CLOSED, near=False, rot=(0, 0, rz), open=False, torn=False)
    # 버팀목 둘: 양옆 테에서 바깥 아래로 땅까지
    for side in (1, -1):
        stick(s, (side * (R + 0.15), 0.1, -0.1), (side * (R + 1.0), -2.0, -0.6), 0.2)
        stick(s, (side * (R + 0.05), -0.6, 0.1), (side * (R + 0.75), -2.0, 0.6), 0.18)
    # 풍향계: 꼭대기 막대기가 수직 축으로 돎(자세 4)
    stick(s, (0, R + 0.25, 0), (0, R + 0.95, 0), 0.14)
    for f in s.frames():
        with f:
            a = 2 * math.pi * f.t
            d = np.array((math.cos(a), 0.15, math.sin(a)))
            top = np.array((0, R + 0.95, 0))
            stick(s, top - d * 0.6, top + d * 0.75, 0.12)
    s.front = (0, 0, 1)


def veil(s, k, mtex):
    for f in s.frames():
        with f:
            a = 2 * math.pi * f.t
            s.quad((0, 0, 0.02 * math.sin(a)), (2.9, 2.9), "south", mtex, rot=(5 * math.sin(a), 5 * math.cos(a), 0), origin=(0, 0, 0))
    # 막 너머 줄 선 얼굴 다섯(멀어지며 작아짐)
    for j, (x, y, z, sz, fc) in enumerate(((0.0, -0.2, -0.3, 0.8, "smile"), (0.45, 0.0, -0.75, 0.6, "smile_a"), (-0.4, 0.15, -1.15, 0.5, "half"),
                                           (0.2, 0.25, -1.55, 0.42, "smile_c"), (-0.15, 0.3, -1.9, 0.35, "smile"))):
        s.quad((x, y, z), (sz, sz), "south", k.face_tex(fc), face=True)
    s.front = (0, 0, 0)


def build(k: kit.Kit):
    m = k.model(poses=4, period=20, shrink=0.6, mut2=False, torn=False)
    ring(m.state(""), k)
    mtex = k.texture("membrane", membrane_tex())
    vm = k.model("g146_veil", poses=4, period=16, shrink=0.7, mut2=False, open=False, torn=False, rag=False)
    veil(vm.state(""), k, mtex)
    k.icon(model="g146", state="")


def sounds(k: kit.Kit):
    k.sample("open", ["tag:gate", "tag:door"], "문 열림", pitch=0.6, attenuation=64)
    k.sample("beat", "tag:heartbeat", "심장 박동", pitch=0.7, attenuation=48)
    k.sample("shut", "tag:door", "고리 문이 닫힘", pitch=0.55, attenuation=64)
    k.sample("bite", "tag:eat", "고리 테가 물음", pitch=0.8, attenuation=24)
    k.sample("pass", "tag:wet+slime", "무언가 넘어옴", pitch=0.7, attenuation=32)
    k.sample("drag", "tag:wind", "막으로 끌려감", pitch=0.6, attenuation=32, n=2)


def phrases():
    return {"cause_g146": "고리 문 막에 끌려 떼 한복판에 떨어졌다", "cause_g146_bite": "고리 문 테에 물렸다", "notice_g146_open": "트럭 곁에 문이 열렸다"}
