"""G94 솥 다리 게이브(2막, 다리로 걷는 것: 세 다리 솥, 키 7.5 블록).

7 블록 엔드 막대기 장대 다리 셋 위에 살로 빚은 2.6 블록 솥. 솥 둘레에 얼굴 셋이 바깥을 보고 박혔고(사람 쪽 얼굴만 눈을 뜸: 부위 eye0..2),
솥 귀 둘도 막대기. 솥 안에 분홍 즙이 끓는다(거품 자세). 다리는 무릎이 높이 솟은 두 마디(손으로 짠 걸음: 한 번에 한 다리씩 들어 옮김).

- gave:gabe/g94(shrink 0.18): 솥 가운데가 (0, 0, 0), 발바닥 y = -7(Java hover 7).
  상태 ""(걷기: 36틱에 세 다리가 차례로 한 걸음씩, 자세 6. 디딤 동안 발이 뒤로 STRIDE 만큼 밀려 몸이 0.09 블록/틱으로 가면 발이 땅에 붙음),
  "stand"(다리를 벌리고 섬, 즙이 끓음), "pour"(솥이 앞으로 40도 기울고 입에서 즙 줄기가 땅까지, 자세 4),
  "fallen"(다리 하나가 꺾여 솥이 옆으로 엎어져 땅에 깔림, 다리들이 늘어짐. Java hover 1.3).
  부위 "eye0", "eye1", "eye2": 그 얼굴이 눈을 뜸(사람 쪽 얼굴만).
- gave:gabe/g94_pool: 쏟아진 즙 웅덩이(반지름 1 판, Java 가 4 배로), 상태 ""(거품 얼굴이 떠올랐다 꺼짐, 자세 4).
소리 boil(끓는 소리, 40), pour(즙 쏟아짐, 40), snap(다리 꺾임, 32), crash(솥 엎어짐, 48).
"""
import math

import numpy as np

from gabe import kit

S = 0.18
HOVER = 7.0
POT_W, POT_H = 2.9, 2.0
HIP_R, HIP_Y = 1.05, -0.85
FOOT_R = 3.5
L1, L2 = 4.1, 4.1
LEG_T = 0.34
ANG = (90.0, 210.0, 330.0)     # 다리 자리(도, +X 에서 +Z 쪽으로): 0 번이 앞
PERIOD, POSES = 36, 6
STRIDE = 3.2                   # 한 주기에 몸이 가는 거리(0.09 x 36 남짓)
LIFT = 1.4
FACE_ANG = (90.0, 210.0, 330.0)  # 솥 얼굴 셋(다리 위: 0 번이 앞)


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def limb(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    up = abs(d[1]) > 0.97 * np.linalg.norm(d)
    return s.rod(tuple(a), tuple(d), length=float(np.linalg.norm(d)), segs=1, thick=thick, base=False, tex=tex, rag=False,
                 toward=(0, 0, 1) if up else (0, 1, 0))


def hip(i):
    a = math.radians(ANG[i])
    return np.array((HIP_R * math.cos(a), HIP_Y, HIP_R * math.sin(a)))


def foot(i, dz=0.0, lift=0.0, r=FOOT_R):
    a = math.radians(ANG[i])
    return np.array((r * math.cos(a), -HOVER + lift, r * math.sin(a) + dz))


def knee(h, f, out):
    """두 마디 다리의 무릎: 엉덩이 h, 발 f, 무릎이 솟는 쪽 out(바깥 위)"""
    d = f - h
    n = np.linalg.norm(d)
    n = min(n, L1 + L2 - 1e-3)
    al = d / np.linalg.norm(d)
    a = (L1 * L1 - L2 * L2 + n * n) / (2 * n)
    hh = math.sqrt(max(0.0, L1 * L1 - a * a))
    p = np.array(out, float)
    p = p - al * np.dot(p, al)
    p = p / np.linalg.norm(p)
    return h + al * a + p * hh


def leg(s, i, f, knob=True):
    h = hip(i)
    a = math.radians(ANG[i])
    out = (math.cos(a), 1.2, math.sin(a))
    k = knee(h, f, out)
    limb(s, h, k, LEG_T)
    limb(s, k, f, LEG_T * 0.85)
    # 무릎 마디 받침(엔드 막대기 받침 모양의 작은 상자, 걸음 자세에서는 면을 아끼려고 뺌)
    if knob:
        s.box(tuple(k - 0.22), tuple(k + 0.22), "rod")


def gait(t, i):
    """걸음: 주기 t(0..1)에서 다리 i 의 (앞뒤 dz, 들림). 다리 i 는 [i/3, (i+1)/3) 동안 공중에서 앞으로, 나머지 동안 디딤으로 뒤로"""
    u = (t - i / 3.0) % 1.0
    if u < 1 / 3:
        w = u * 3
        return -STRIDE / 2 + STRIDE * w, LIFT * math.sin(math.pi * w)
    w = (u - 1 / 3) * 1.5
    return STRIDE / 2 - STRIDE * w, 0.0


def broth_tex(seed=94, n=16):
    rng = np.random.RandomState(seed)
    a = np.zeros((n, n, 4), np.uint8)
    base = np.array([232, 120, 150], float)
    col = base[None, None, :] * (0.85 + 0.25 * rng.rand(n, n, 1))
    yy, xx = np.mgrid[0:n, 0:n]
    for _ in range(7):
        cx, cy, r = rng.randint(2, n - 2), rng.randint(2, n - 2), rng.randint(1, 3)
        ring = np.abs(np.hypot(xx - cx, yy - cy) - r) < 0.6
        col[ring] = (255, 196, 210)
    a[..., :3] = np.clip(col, 0, 255)
    a[..., 3] = 255
    return a


def pot(s, k, broth, tilt=0.0):
    """솥(살 그릇 + 볼록한 배 + 테 + 즙 + 귀 막대기 둘 + 둘레 얼굴 셋). tilt: 앞으로 기운 각(도)"""
    rot = (tilt, 0, 0) if tilt else None
    o = (0, 0, 0)
    kw = dict(rot=rot, origin=o) if rot else {}
    s.box((-POT_W / 2, -POT_H / 2, -POT_W / 2), (POT_W / 2, POT_H / 2, POT_W / 2), "flesh", face=True, **kw)
    s.box((-POT_W / 2 - 0.22, -0.45, -POT_W / 2 - 0.22), (POT_W / 2 + 0.22, 0.35, POT_W / 2 + 0.22), "flesh", face=True, **kw)
    s.box((-POT_W / 2 + 0.25, -POT_H / 2 - 0.25, -POT_W / 2 + 0.25), (POT_W / 2 - 0.25, -POT_H / 2, POT_W / 2 - 0.25), "flesh",
          face=True, **kw)
    # 테(입술): 네 변 살 띠
    t = 0.2
    y0, y1 = POT_H / 2 - 0.05, POT_H / 2 + 0.18
    w = POT_W / 2 + 0.1
    for frm, to in (((-w, y0, -w), (w, y1, -w + t)), ((-w, y0, w - t), (w, y1, w)), ((-w, y0, -w), (-w + t, y1, w)), ((w - t, y0, -w), (w, y1, w))):
        s.box(frm, to, "flesh", face=True, **kw)
    s.quad((0, POT_H / 2 - 0.12, 0), (POT_W - 0.3, POT_W - 0.3), "up", broth, **kw)
    # 귀 막대기 둘(옆에서 위로 휘어 솟음)
    for side in (1, -1):
        r = kit.rot_axis((1, 0, 0), tilt) if tilt else np.eye(3)
        a = r @ np.array((side * (POT_W / 2 + 0.2), 0.5, 0))
        b = r @ np.array((side * (POT_W / 2 + 0.75), 1.35, 0))
        limb(s, a, b, 0.16)
    # 얼굴 셋: 솥 옆구리에 바깥을 보게(사람 쪽 하나만 눈을 뜸)
    for i, fa in enumerate(FACE_ANG):
        a = math.radians(fa)
        r = kit.rot_axis((1, 0, 0), tilt) if tilt else np.eye(3)
        c = r @ np.array((math.cos(a) * (POT_W / 2 + 0.24), -0.05, math.sin(a) * (POT_W / 2 + 0.24)))
        yaw = 90 - fa  # 얼굴 앞(+Z)을 바깥 (cos a, 0, sin a) 쪽으로
        hr = kit.euler_of(kit.rot_axis((1, 0, 0), tilt) @ kit.rot_axis((0, 1, 0), yaw))
        with s.when(f"!on:eye{i}"):
            s.head(size=1.55, depth=0.3, at=tuple(c), face="smile", rot=hr, open=False)
        with s.when(f"on:eye{i}"):
            s.head(size=1.55, depth=0.3, at=tuple(c), face="open", rot=hr, open=False)


def bubbles(s, k, n=4):
    """즙 위 거품 얼굴(떠올랐다 꺼짐)"""
    face = k.face_tex("smile_a")
    pts = ((0.45, 0.3), (-0.5, -0.2), (0.0, -0.55), (-0.2, 0.5))
    for f in s.frames(poses=n):
        with f:
            for j, (x, z) in enumerate(pts):
                h = math.sin(2 * math.pi * (f.t + j / len(pts)))
                if h < 0.1:
                    continue
                s.quad((x, POT_H / 2 - 0.1 + 0.18 * h, z), (0.45 * h + 0.1, 0.45 * h + 0.1), "up", face, face=True)


def build(k: kit.Kit):
    broth = k.texture("broth", broth_tex())
    m = k.model(poses=POSES, period=PERIOD, shrink=S, mut2=False, torn=True)
    # 걷기
    w = m.state("")
    pot(w, k, broth)
    for f in w.frames():
        with f:
            for i in range(3):
                dz, lift = gait(f.t + 0.5 / POSES, i)
                leg(w, i, foot(i, dz, lift), knob=False)
    # 서기(다리를 넓게)
    st = m.state("stand", poses=4)
    pot(st, k, broth)
    bubbles(st, k)
    for i in range(3):
        leg(st, i, foot(i, r=FOOT_R + 0.6))
    # 쏟기: 솥이 앞으로 40도, 입에서 땅까지 즙 줄기(넓은 띠, 흔들림)
    pr = m.state("pour", poses=4, period=12)
    pot(pr, k, broth, tilt=40)
    for i in range(3):
        leg(pr, i, foot(i, r=FOOT_R + 0.6))
    lip = kit.rot_axis((1, 0, 0), 40) @ np.array((0, POT_H / 2 + 0.1, POT_W / 2))
    pr.band(tuple(lip), (0, -1, 0.08), length=-lip[1] + HOVER - 0.1, width=0.9, segs=3, thick=0.02, tex=broth,
            wave=kit.Wave(amp=(4, 6, 8), lag=60, cycles=2), toward=(0, 0, 1), face=False)
    # 엎어짐: 솥이 옆으로 누워(앞으로 85도) 땅에 깔리고, 다리는 땅에 늘어짐(하나는 꺾여 짧음). 솥 가운데가 땅 위 1.3
    fl = m.state("fallen", poses=1)
    pot(fl, k, broth, tilt=85)
    for i in range(3):
        a = math.radians(ANG[i] + 25)
        h = np.array((math.cos(a) * 1.0, -0.6, math.sin(a) * 1.0))
        kn = h + np.array((math.cos(a) * 3.2, -0.65, math.sin(a) * 3.2))
        ft = kn + np.array((math.cos(a + 0.7) * (1.6 if i == 0 else 3.8), -0.05, math.sin(a + 0.7) * (1.6 if i == 0 else 3.8)))
        limb(fl, h, kn, LEG_T)
        limb(fl, kn, ft, LEG_T * 0.85)
    fl.front = (0, 0, 0)

    # 즙 웅덩이(반지름 1 판, 바닥에 깔림): 거품 얼굴이 떠오름
    pm = k.model("g94_pool", poses=4, period=16, mut2=False, open=False, torn=False, rag=False)
    ps = pm.state("")
    yy, xx = np.mgrid[0:32, 0:32]
    rr = np.hypot(xx - 15.5, yy - 15.5) / 16
    img = np.zeros((32, 32, 4), np.uint8)
    rng = np.random.RandomState(7)
    col = np.array([214, 96, 128], float)[None, None, :] * (0.85 + 0.25 * rng.rand(32, 32, 1))
    edge = (rr > 0.86) & (rr <= 1.0)
    col[edge] = (150, 52, 80)
    img[..., :3] = np.clip(col, 0, 255)
    img[..., 3] = np.where(rr <= 1.0 - 0.06 * np.sin(np.arctan2(yy - 15.5, xx - 15.5) * 7), 255, 0)
    pool = k.texture("pool", img)
    ps.quad((0, 0.01, 0), (2, 2), "up", pool)
    face = k.face_tex("smile")
    spots = ((0.4, 0.2), (-0.45, 0.35), (0.1, -0.5), (-0.3, -0.25), (0.55, -0.4))
    for f in ps.frames():
        with f:
            for j, (x, z) in enumerate(spots):
                h = math.sin(2 * math.pi * (f.t + j / len(spots)))
                if h < 0.15:
                    continue
                ps.quad((x, 0.03 + 0.02 * h, z), (0.22 * h + 0.06, 0.22 * h + 0.06), "up", face, face=True)
    ps.front = (0, 0, 0)
    k.icon(model="g94", state="stand")


def sounds(k: kit.Kit):
    k.sample("boil", ["oga_loops30/water_boiling", "oga_creature80/burble_01", "oga_creature80/burble_02"], "끓는 소리", pitch=0.7,
             attenuation=40)
    k.sample("pour", ["oga_water40/splash_01", "oga_water40/splash_04", "oga_sfx100/splash_01"], "즙 쏟아지는 소리", pitch=0.6,
             attenuation=40)
    k.sample("snap", ["oga_breaking75/bfh1_rock_breaking_01", "lrsf_horror/bonecrack_03"], "다리 꺾임", pitch=0.7, attenuation=32)
    k.sample("crash", "tag:quake", "솥 엎어짐", pitch=0.8, attenuation=48)


def phrases():
    return {"cause_g94": "솥에서 쏟아진 즙을 뒤집어썼다", "cause_g94_step": "솥 다리에 밟혔다"}
