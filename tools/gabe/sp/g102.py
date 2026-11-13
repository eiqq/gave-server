"""G102 던져 올리는 손 게이브(2막, 거대한 손, 폭 7 블록).

손바닥 한가운데가 3 블록 _Gave 얼굴인 거대한 손. 손바닥을 앞(+Z)으로 세우고 뒤로 조금 젖힌 채, 굵은 손가락 다섯(두 마디, 마디가 위로 솟음)
끝으로 땅을 짚고 거미처럼 걷는다. 손목은 위 뒤쪽에서 흙먼지 속으로 끊긴 살덩이.

- gave:gabe/g102(shrink 0.3): 손바닥 가운데가 (0, 0, 0), 손가락 끝(땅) y = -3.2(Java hover 3.2).
  상태 ""(걷기: 손가락 둘, 셋이 번갈아 디딤, 16틱 자세 4), "reach"(떠 올리기 예고: 손을 치켜들고 손가락을 오므림),
  "grab"(오므려 떠 올림: 손가락 끝이 손바닥 아래 앞에 모임), "up"(던진 뒤: 손바닥 얼굴이 하늘을 보고 손가락이 꽃처럼 벌어짐).
  부위 cut0..cut4: 그 손가락이 끊김(그루터기).
소리 slap(손바닥 철썩, 48), curl(손가락 오므림, 24), laugh(하늘 보고 웃음, 40).
"""
import math

import numpy as np

from gabe import kit

S = 0.3
HOVER = 3.2
PALM_W, PALM_H, PALM_D = 3.4, 3.6, 0.9
TILT = -22          # 손바닥이 뒤로 젖힌 각(위가 -Z 로)
L1, L2, FT = 2.0, 2.0, 0.8
PERIOD, POSES = 16, 4
STRIDE = 1.9
LIFT = 0.9
# 손가락: (뿌리 x, y, z), 끝(땅) (x, z). 0 엄지(+X, 게이브 왼쪽), 1 검지 .. 4 새끼
FINGERS = (((1.75, -0.2, 0.15), (3.5, 1.4)),
           ((1.2, -1.75, 0.3), (2.05, 3.0)),
           ((0.4, -1.85, 0.35), (0.7, 3.5)),
           ((-0.4, -1.85, 0.35), (-0.7, 3.3)),
           ((-1.2, -1.7, 0.3), (-2.0, 2.7)))
GROUP = (0, 1, 0, 1, 0)


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def limb(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    up = abs(d[1]) > 0.97 * np.linalg.norm(d)
    return s.rod(tuple(a), tuple(d), length=float(np.linalg.norm(d)), segs=1, thick=thick, base=False, tex=tex, rag=False,
                 toward=(0, 0, 1) if up else (0, 1, 0))


def tilt_pt(p, deg=TILT):
    return kit.rot_axis((1, 0, 0), deg) @ np.array(p, float)


def knee(h, f, out, l1=L1, l2=L2):
    d = f - h
    n = min(np.linalg.norm(d), l1 + l2 - 1e-3)
    al = d / np.linalg.norm(d)
    a = (l1 * l1 - l2 * l2 + n * n) / (2 * n)
    hh = math.sqrt(max(0.0, l1 * l1 - a * a))
    p = np.array(out, float)
    p = p - al * np.dot(p, al)
    p = p / np.linalg.norm(p)
    return h + al * a + p * hh


def finger(s, i, tip, cond=True, tilt=TILT):
    base = tilt_pt(FINGERS[i][0], tilt)
    out = np.array((base[0] * 0.6, 0.7, 1.3))
    k = knee(base, np.array(tip, float), out)
    if cond:
        with s.when(f"!on:cut{i}"):
            limb(s, base, k, FT)
            limb(s, k, tip, FT * 0.85)
    else:
        limb(s, base, k, FT)
        limb(s, k, tip, FT * 0.85)


def stumps(s, tilt=TILT):
    for i in range(5):
        base = tilt_pt(FINGERS[i][0], tilt)
        with s.when(f"on:cut{i}"):
            limb(s, base, base + _u((base[0] * 0.3, -1, 0.5)) * 0.7, FT)


def palm(s, k, tilt=TILT, face="smile"):
    flesh = k.part_tex("flesh")
    s.head(size=PALM_W, height=PALM_H, depth=PALM_D, face=face, sides=flesh, rot=(tilt, 0, 0))
    # 손목: 위 뒤쪽으로 뻗다 끊긴 살덩이, 끊긴 끝에서 뼈 막대기 둘이 삐져나옴
    w0 = tilt_pt((0, PALM_H / 2 - 0.3, -0.2), tilt)
    s.box(tuple(w0 + (-1.25, -0.5, -1.7)), tuple(w0 + (1.25, 0.75, 0.2)), "flesh", rot=(tilt - 35, 0, 0), origin=tuple(w0), face=True)
    cut = w0 + kit.rot_axis((1, 0, 0), tilt - 35) @ np.array((0, 0.1, -1.7))
    for x in (-0.45, 0.4):
        limb(s, cut + (x, 0, 0.1), cut + kit.rot_axis((1, 0, 0), tilt - 35) @ np.array((x * 1.2, 0.1, -0.75)), 0.24, "bone")


def gait(t, g):
    u = (t - 0.5 * g) % 1.0
    if u < 0.5:
        w = u * 2
        return -STRIDE / 2 + STRIDE * w, LIFT * math.sin(math.pi * w)
    w = (u - 0.5) * 2
    return STRIDE / 2 - STRIDE * w, 0.0


def tip(i, dz=0.0, lift=0.0):
    x, z = FINGERS[i][1]
    return np.array((x, -HOVER + lift, z + dz))


def build(k: kit.Kit):
    m = k.model(poses=POSES, period=PERIOD, shrink=S, mut2=False)
    w = m.state("")
    palm(w, k)
    stumps(w)
    for f in w.frames():
        with f:
            for i in range(5):
                dz, lift = gait(f.t + 0.5 / POSES, GROUP[i])
                finger(w, i, tip(i, dz, lift))
    # 예고: 손을 치켜들고(손가락 끝이 땅에서 뜸) 손가락을 안으로 오므리기 시작
    r = m.state("reach", poses=1)
    palm(r, k, tilt=-10)
    stumps(r, -10)
    for i in range(5):
        x, z = FINGERS[i][1]
        finger(r, i, np.array((x * 0.7, -HOVER + 1.0, z * 0.85 + 0.4)), tilt=-10)
    # 떠 올림: 손가락 끝이 손바닥 아래 앞으로 모임(사람을 손 안에 떠 담음)
    g = m.state("grab", poses=1)
    palm(g, k, tilt=-5)
    stumps(g, -5)
    for i in range(5):
        x, _ = FINGERS[i][1]
        finger(g, i, np.array((x * 0.25, -HOVER + 0.6, 2.4)), tilt=-5)
    # 던진 뒤: 손바닥이 하늘을 봄(뒤로 80도 젖힘), 손가락은 둘레로 벌어져 위로 휨
    u = m.state("up", poses=1)
    palm(u, k, tilt=-80, face="half")
    stumps(u, -80)
    for i in range(5):
        x, z = FINGERS[i][1]
        a = math.atan2(z - 0.5, x)
        finger(u, i, np.array((math.cos(a) * 3.3, -HOVER + 0.15, math.sin(a) * 2.2 - 0.5)), tilt=-80)
    u.front = (0, 1, 0)
    k.icon(model="g102", state="")


def sounds(k: kit.Kit):
    k.sample("slap", ["oga_sfx100/slam_02", "oga_sfx100/slam_03", "kenney_impact/impactpunch_heavy_000"], "손바닥 철썩", pitch=0.65,
             attenuation=48)
    k.sample("curl", "tag:creak", "손가락 오므림", pitch=0.6, attenuation=24)
    k.sample("laugh", "tag:laugh", "하늘 보고 웃음", pitch=0.7, attenuation=40)


def phrases():
    return {"cause_g102": "하늘로 던져졌다 떨어졌다", "cause_g102_slap": "거대한 손바닥에 깔렸다"}
