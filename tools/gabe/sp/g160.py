"""G160 엉뚱 달리기 게이브: 0.5 블록 _Gave 얼굴 밑에 작은 살 몸통, 제 키보다 긴 엔드 막대기 팔다리 넷(두 마디, 무릎과 팔꿈치가 거꾸로
꺾임)을 활짝 벌리고 네 발로 엎드려 달린다(폭 1.4). 얼굴은 늘 옆을 보고 살 혀를 내밀었다.

모델 gave:gabe/g160: 상태 ""(네 발 달리기 4자세, 팔다리는 자세마다 통째로), "throw"(물구나무 뒤집기 3자세: 몸을 뒤로 젖힘 → 거꾸로 섬
→ 넘김). 몸 가운데 = 몸통 가운데, 바닥 = -0.42(Java hover 0.42).
"""
import math

import numpy as np

from gabe import kit

GROUND = -0.42
HEAD_AT = (0.0, 0.16, 0.24)
HEAD_ROT = (0, 68, 0)          # 늘 옆(게이브 왼쪽)을 봄
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


def limb(s, root, knee, foot):
    seg(s, root, knee, 0.075, "rod", ROD_UV)
    seg(s, knee, foot, 0.075, "rod", ROD_UV)


def rot_x(p, deg, c=(0.0, 0.0, 0.0)):
    a = math.radians(deg)
    y, z = p[1] - c[1], p[2] - c[2]
    return (p[0], c[1] + y * math.cos(a) - z * math.sin(a), c[2] + y * math.sin(a) + z * math.cos(a))


def run_pose(t):
    """네 발 달리기 한 자세의 팔다리(뿌리, 무릎, 발) 넷. 대각선 짝(앞왼 + 뒤오른)이 번갈아 앞으로"""
    out = []
    for i, (sx, sz) in enumerate(((1, 1), (-1, 1), (1, -1), (-1, -1))):
        pair = 0 if (sx > 0) == (sz > 0) else 1
        ph = 2 * math.pi * t + pair * math.pi
        swing = 0.28 * math.sin(ph)
        lift = max(0.0, math.cos(ph)) * 0.16
        root = (sx * 0.16, -0.02, sz * 0.18)
        foot = (sx * 0.68, GROUND + lift, sz * 0.42 + swing)
        # 무릎과 팔꿈치가 거꾸로: 바깥 위로 솟음
        knee = (sx * 0.5, 0.36 + lift * 0.5, sz * 0.26 + swing * 0.4)
        out.append((root, knee, foot))
    return out


def figure(s, pose_limbs, tilt=0.0, center=(0.0, -0.05, 0.0), trunk=True):
    rx = (lambda p: rot_x(p, tilt, center)) if tilt else (lambda p: p)
    if trunk:
        trunk_of(s, rx, tilt, center)
    for root, knee, foot in pose_limbs or ():
        limb(s, rx(root), rx(knee), rx(foot))


def trunk_of(s, rx, tilt, center):
    hx, hy, hz = rx(HEAD_AT)
    s.head(size=0.5, depth=0.42, at=(hx, hy, hz), face="smile", rot=(tilt, HEAD_ROT[1], 0))
    # 작은 살 몸통(머리 밑)
    lo, hi = (-0.2, -0.14, -0.26), (0.2, 0.06, 0.2)
    if tilt:
        s.box(lo, hi, "flesh", rot=(tilt, 0, 0), origin=center, face=True)
    else:
        s.box(lo, hi, "flesh", face=True)
    # 내민 혀(입에서 옆 아래로)
    t0 = rx((HEAD_AT[0] + 0.24, HEAD_AT[1] - 0.12, HEAD_AT[2] + 0.12))
    t1 = rx((HEAD_AT[0] + 0.4, HEAD_AT[1] - 0.3, HEAD_AT[2] + 0.2))
    seg(s, t0, t1, 0.09, "flesh", [0, 0, 16, 16], face=True)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    s = m.state("")
    figure(s, None)
    for f in s.frames():
        with f:
            figure(s, run_pose(f.t), trunk=False)
    th = m.state("throw", poses=3, period=12)
    # 몸을 뒤로 젖힘 → 거꾸로 섬(팔다리가 하늘로) → 넘김
    for f in th.frames():
        with f:
            tilt = (-40, -150, -230)[f.k]
            figure(th, run_pose(0.25), tilt=tilt)
    k.icon(model="g160", state="")


def sounds(k: kit.Kit):
    # 이 종의 신호: 막대기 팔다리가 땅을 긁는 다다닥
    k.sample("scamper", "tag:scratch-metal", "다다닥 엉뚱한 발소리", pitch=1.2, attenuation=20)
    # 깔깔 웃음(떼 종이라 허용, 드물게)
    k.sample("laugh", [{"id": "oga_cackle/witch_cackle_1_0", "pitch": 1.5}], "깔깔 웃음", attenuation=24, n=1)
    # 사람을 머리 위로 넘겨 던짐, 떨어짐
    k.sample("throw", "kenney_impact/impactpunch_heavy_00*", "휙 던짐", pitch=1.15, attenuation=20)


def phrases():
    return {"cause_g160": "머리 위로 내던져졌다"}
