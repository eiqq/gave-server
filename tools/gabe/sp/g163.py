"""G163 프로펠러 모자 게이브: 0.5 블록 _Gave 얼굴 정수리에 짧은 굴대가 서 있고 그 위에서 엔드 막대기 둘이 십자로 엇갈려 프로펠러처럼
돈다(날개 폭이 얼굴보다 넓어 멀리서 프로펠러로 읽힘). 턱 밑에 짧은 분홍 집게발 둘.

모델 gave:gabe/g163: 상태 ""(날기: 프로펠러 4자세, 집게 벌림), "cling"(사람 어깨에 붙음: 집게 다묾, 프로펠러가 두 배 빨리).
프로펠러 자세 k 의 각 = 22.5 x k 도(십자라 90도마다 같은 모양). 몸 가운데 = 얼굴 가운데.
"""
import math

import numpy as np

from gabe import kit

HUB = (0.0, 0.47, 0.0)
BLADE = 0.52
ROD_UV = [0, 0, 2, 15]


def seg(s, a, b, wid, tex, uv, face=False):
    """a 에서 b 로 가는 네모 막대 하나(옆 네 면만)"""
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    d /= L
    ref = np.array([0.0, 0.0, 1.0]) if abs(d[2]) < 0.9 else np.array([1.0, 0.0, 0.0])
    f = np.cross(d, ref)
    f /= np.linalg.norm(f)
    z = np.cross(f, d)
    lim = 1.5 / s.model.shrink - 1e-3
    up = a[1] + L <= lim
    R = np.column_stack([f, d, z]) if up else np.column_stack([-f, -d, z])
    y0, y1 = (a[1], a[1] + L) if up else (a[1] - L, a[1])
    s.box((a[0] - wid / 2, y0, a[2] - wid / 2), (a[0] + wid / 2, y1, a[2] + wid / 2), tex, uv=uv,
          faces=("north", "south", "east", "west"), rot=kit.euler_of(R), origin=tuple(a), face=face)


def body(s, closed):
    s.head(size=0.5, depth=0.42, at=(0, 0, 0), face="smile")
    # 정수리 굴대
    s.rod((0, 0.24, 0), (0, 1, 0), length=0.24, segs=1, thick=0.07, base=False, toward=(0, 0, 1), rag=False)
    # 턱 밑 집게발 둘(살): 벌림 / 다묾
    for side in (1, -1):
        root = (side * 0.13, -0.24, 0.12)
        tip = (side * 0.035, -0.45, 0.22) if closed else (side * 0.25, -0.45, 0.2)
        seg(s, root, tip, 0.08, "flesh", [0, 0, 16, 16], face=True)
    # 프로펠러: 막대기 둘이 십자, 자세마다 22.5도씩 돎
    for f in s.frames():
        with f:
            for j in (0, 1):
                a = math.radians(22.5 * f.k + 90 * j + 10)
                d = np.array([math.cos(a), 0.0, math.sin(a)])
                hub = np.array(HUB)
                seg(s, hub - d * BLADE, hub + d * BLADE, 0.075, "rod", ROD_UV)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    body(m.state(""), False)
    body(m.state("cling", period=4), True)
    k.icon(model="g163", state="")


def sounds(k: kit.Kit):
    # 이 종의 신호: '부우웅' 날개 소리(둘이 붙으면 한 음 높게: Java 가 lift 사건)
    k.sample("buzz", [{"id": "kenney_scifi/enginecircular_002", "end": 1.2}], "붕붕 날개 소리", pitch=1.6, volume=0.8,
             attenuation=20, n=1)
    k.sample("lift", [{"id": "kenney_scifi/enginecircular_002", "end": 1.6}], "붕붕 날개 소리", pitch=2.1, attenuation=24, n=1)
    # 집게로 묾
    k.sample("pinch", "oga_creature80/eat_0*", "집게가 묾", pitch=1.6, volume=0.7, attenuation=12)
    # 떨어뜨린 사람이 땅에 부딪힘
    k.sample("drop", "kenney_impact/impactsoft_heavy_00*", "쿵 떨어짐", pitch=0.8, attenuation=20)


def phrases():
    return {"cause_g163": "프로펠러가 들어 떨어뜨렸다", "cause_g163_pinch": "프로펠러 집게에 물렸다"}
