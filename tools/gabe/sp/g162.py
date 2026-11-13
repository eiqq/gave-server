"""G162 덮쳐 앉는 게이브: 0.7 블록 _Gave 얼굴 아래 얼굴보다 넓적한 분홍 방석 살, 엔드 막대기 다리 넷이 메뚜기 뒷다리처럼 ㄱ자로 접혔다.
웅크리면 다리가 얼굴 위로 솟아 왕관처럼 보이고, 뛰면 넷이 한꺼번에 쭉 펴진다.

모델 gave:gabe/g162: 상태 ""(깡충 2자세), "crouch"(다리 넷이 위로 솟음, 부르르 떨림 4자세), "leap"(다리 쭉 폄),
"sit"(엉덩방아, 다리를 허공에 버둥 2자세. 사람을 깔고 앉았을 때도 이 상태). 다리는 구조 막대기(자세마다 통째로).
모델 gave:gabe/g162_shadow: 떨어질 자리 둥근 그림자(바닥에 누운 판, Java 가 부품으로 세계 축에 놓음).
몸 가운데 = 얼굴 아래 방석 위, 바닥 = -0.5(Java hover 0.5).
"""
import math

import numpy as np

from gabe import kit

GROUND = -0.5
ROD_UV = [0, 0, 2, 15]
CORNERS = ((1, 1), (-1, 1), (1, -1), (-1, -1))


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


def shadow_tex():
    """32x32 점묘 원(가운데 빽빽, 둘레 고리 또렷): 반투명 대신 점으로 흐림"""
    n = 32
    out = np.zeros((n, n, 4), np.uint8)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    r = np.hypot(xx + 0.5 - n / 2, yy + 0.5 - n / 2) / (n / 2)
    rng = np.random.RandomState(162)
    dots = (rng.rand(n, n) < np.clip(0.8 - 0.5 * r ** 2, 0, 1)) & (r <= 0.9)
    ring = (r > 0.82) & (r <= 1.0)
    on = dots | ring
    out[..., :3] = (40, 16, 24)
    out[ring, :3] = (150, 40, 60)
    out[..., 3] = np.where(on, 255, 0)
    return out


def trunk(s, face="smile"):
    s.head(size=0.7, depth=0.55, at=(0, 0.2, 0.04), face=face)
    # 얼굴 아랫면 넓적한 분홍 방석 살(얼굴보다 넓음)
    s.box((-0.52, -0.22, -0.44), (0.52, -0.08, 0.46), "flesh", face=True)
    s.box((-0.44, -0.3, -0.36), (0.44, -0.22, 0.38), "flesh", face=True)


def legs(s, hip_y, knee_up, knee_out, foot_out, foot_y, jitter=0.0, kick=None):
    """다리 넷(허벅지 + 정강이). knee_up: 무릎 높이(방석 위), knee_out / foot_out: 바깥으로 벌린 정도"""
    for i, (sx, sz) in enumerate(CORNERS):
        j = jitter * (1 if (i + int(jitter * 100)) % 2 else -1)
        hip = (sx * 0.38, hip_y, sz * 0.32)
        knee = (sx * knee_out, knee_up + j, sz * (0.4 if sz > 0 else 0.46))
        if kick is not None:
            foot = (sx * foot_out, foot_y + kick[i], sz * 0.6)
        else:
            foot = (sx * foot_out, foot_y, sz * 0.5)
        seg(s, hip, knee, 0.08, "rod", ROD_UV)
        seg(s, knee, foot, 0.07, "rod", ROD_UV)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, mut2=False, torn=False)
    s = m.state("", poses=2, period=12)
    trunk(s)
    for f in s.frames():
        with f:
            # 깡충: 접혔다(0) 반쯤 폄(1)
            legs(s, -0.15, 0.55 if f.k == 0 else 0.42, 0.62, 0.66, GROUND)
    c = m.state("crouch", poses=4, period=4)
    trunk(c, face="half")
    for f in c.frames():
        with f:
            # 다리 넷이 얼굴 위로 솟아 왕관처럼(부르르)
            legs(c, -0.12, 0.92, 0.5, 0.7, GROUND, jitter=0.03 * (1 if f.k % 2 else -1))
    lp = m.state("leap", poses=1)
    trunk(lp)
    legs(lp, -0.2, -0.45, 0.42, 0.5, -1.15)
    st = m.state("sit", poses=2, period=8)
    trunk(st)
    for f in st.frames():
        with f:
            # 엉덩방아: 다리를 허공에 버둥
            legs(st, -0.1, 0.5, 0.62, 0.82, 0.72, kick=(0.15, -0.1, -0.1, 0.15) if f.k == 0 else (-0.1, 0.15, 0.15, -0.1))
    sh = k.model("g162_shadow", poses=1, mut2=False, open=False, torn=False, rag=False)
    ss = sh.state("")
    ss.quad((0, 0.02, 0), (1.4, 1.4), "up", k.texture("shadow", shadow_tex()))
    ss.front = (0, 0, 0)
    k.icon(model="g162", state="crouch")


def sounds(k: kit.Kit):
    # 이 종의 신호: 웅크릴 때 다리가 접히는 '끼이익 통'
    k.sample("leap", "tag:spring", "튀어 오르는 소리", pitch=1.0, attenuation=20)
    # 깔고 앉아 찧음, 빗나가 엉덩방아
    k.sample("thump", "kenney_impact/impactsoft_heavy_00*", "쿵 찧음", pitch=1.2, attenuation=16)
    # 깡충
    k.sample("hop", "kenney_impact/impactsoft_medium_00*", "깡충", pitch=1.3, volume=0.6, attenuation=12)


def phrases():
    return {"cause_g162": "깔고 앉혀 찧였다"}
