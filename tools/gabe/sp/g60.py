"""G60 튀는 공 게이브: 공처럼 부푼 0.66 블록 얼굴(정육면체 + 옆, 위, 아래, 뒤에 덧댄 살 판으로 둥글게). 엔드 막대기 둘이 얼굴 한가운데를
X 자로 꿰뚫어 굴대처럼 박혀 있고 네 끝이 공 밖으로 짧게 삐져나와 튈 때마다 바퀴처럼 돈다.

모델 gave:gabe/g60: 상태 ""(굴대 회전 4자세, 8틱 한 바퀴), "fast"(같은 회전, 4틱 한 바퀴).
모델 gave:gabe/g60_shadow: 떨어질 자리에 깔리는 점묘 그림자 원(바닥에 누운 판 하나, Java 가 몸 부품으로 세계 축에 놓음).
"""
import math

import numpy as np

from gabe import kit

BALL = 0.66
PUFF = 0.46
PUFF_T = 0.07
AXLE = dict(length=1.24, segs=1, thick=0.1, base=False)


def shadow_tex():
    """32x32 점묘 원: 가운데는 빽빽하고 둘레로 갈수록 성긴 검붉은 점, 둘레 고리는 또렷하게(반투명 대신 점으로 흐림)"""
    n = 32
    out = np.zeros((n, n, 4), np.uint8)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    r = np.hypot(xx + 0.5 - n / 2, yy + 0.5 - n / 2) / (n / 2)
    rng = np.random.RandomState(60)
    dens = np.clip(0.85 - 0.55 * r ** 2, 0, 1)
    dots = (rng.rand(n, n) < dens) & (r <= 0.92)
    ring = (r > 0.84) & (r <= 1.0)
    on = dots | ring
    out[..., :3] = (58, 18, 20)
    out[ring, :3] = (120, 30, 34)
    out[..., 3] = np.where(on, 255, 0)
    return out


def ball(k, s):
    h = s.head(size=BALL, depth=BALL, face="smile")
    c, e = BALL / 2, PUFF / 2
    t = PUFF_T
    # 덧댄 판: 정육면체 모서리를 감춰 부푼 공처럼(옆면, 위, 아래, 뒤는 스킨 머리 그림 그대로)
    s.box((c, -e, -e), (c + t, e, e), k.skin_tex("left"), face=True)
    s.box((-c - t, -e, -e), (-c, e, e), k.skin_tex("right"), face=True)
    s.box((-e, c, -e), (e, c + t, e), k.skin_tex("top"), face=True)
    s.box((-e, -c - t, -e), (e, -c, e), k.skin_tex("bottom"), face=True)
    s.box((-e, -e, -c - t), (e, e, -c), k.skin_tex("back"), face=True)
    # 굴대 X: 얼굴 평면(가운데 z=0)에서 자세마다 22.5도씩 돌아감(네 끝이 공 밖으로 삐져나옴)
    for f in s.frames():
        with f:
            for base in (45.0, 135.0):
                a = math.radians(base + 90.0 * f.t)
                d = (math.cos(a), math.sin(a), 0.0)
                half = AXLE["length"] / 2
                s.rod((-d[0] * half, -d[1] * half, 0.0), d, toward=(0, 0, 1), rag=False, **AXLE)
    return h


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    ball(k, m.state(""))
    ball(k, m.state("fast", period=4))
    sh = k.model("g60_shadow", poses=1, mut2=False, open=False, torn=False, rag=False)
    st = sh.state("")
    st.quad((0, 0.02, 0), (1.3, 1.3), "up", k.texture("shadow", shadow_tex()))
    st.front = (0, 0, 0)
    k.icon(model="g60", state="")


def sounds(k: kit.Kit):
    # 튈 때마다 높아지는 퐁(이 종 전용 신호): 튄 횟수마다 사건 하나
    for i in range(5):
        k.sample(f"boing{i}", "oga_sfx100/plop_02", "퐁퐁", pitch=0.85 + 0.14 * i, attenuation=20, n=1)
    # 내리꽂혀 닿음
    k.sample("slam", "kenney_impact/impactsoft_heavy_00*", "쾅", pitch=1.2, volume=0.9, attenuation=16)


def phrases():
    return {"cause_g60": "튀는 공에 내리꽂혔다"}
