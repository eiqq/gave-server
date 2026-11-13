"""G180 송곳 게이브(3막, 함정, 방해: 땅에서 솟는 높이 7 블록 송곳).

평소에는 땅속(흙 두둑 한 줄만 보임). 솟을 때: _Gave 얼굴 판을 나선으로 꼬아 올린 송곳 기둥(아래 굵고 위로 가늘어짐, 돌며 솟음),
꼭대기에 엔드 막대기 셋이 드릴 날처럼 꼬여 모였고, 밑동에 1.4 블록 얼굴이 박혀 위를 올려다본다. 빠져나간 뒤에는 속 빈 잿빛 허물 기둥이 남는다.

- gave:gabe/g180(shrink 0.35): 몸 가운데(0, 0, 0) = 엔진 pos, 바닥에서 HOVER 3.0(밑동 y -3). 상태 ""(4 자세로 돎). 밑동 얼굴은 돌지 않음.
- gave:gabe/g180_husk(shrink 0.35): 허물 기둥(같은 꼴, 잿빛, 자세 없음, 밑동 얼굴 자리는 빈 구멍). 같은 종의 다른 꼴 개체가 쓴다.
- gave:gabe/g180_mound: 땅속을 헤엄치는 동안 땅 위에 보이는 흙 두둑(바닥 y 0, 앞 +Z 로 길쭉).
- gave:gabe/g180_crack: 바닥 예고 판(반지름 1 판, 나선 금 사이 붉은 불씨 빛).
소리 rumble(땅 밑 우르릉, 48), drill(솟는 송곳, 48), shed(허물 벗음, 32), crumble(허물 무너짐, 32).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.35
HOVER = 3.0
LEVELS = 12
Y0, Y1 = -3.0, 2.7
R_BOT, R_TOP = 1.35, 0.28
FACE_SEQ = ("smile", "half", "smile_a", "tears", "smile_c", "stitch", "smile", "deep")
ASH = np.array((150, 146, 140), float)


def tint(img, rgb, keep=0.1, gain=1.2):
    a = np.asarray(img).astype(float)
    lum = (0.3 * a[..., 0] + 0.59 * a[..., 1] + 0.11 * a[..., 2]) / 255.0
    out = a.copy()
    for c in range(3):
        out[..., c] = np.clip(lum * rgb[c] * gain * (1 - keep) + a[..., c] * keep, 0, 255)
    out[..., 3] = 255
    return out.astype(np.uint8)


def column(s, k, spin, ash=None):
    """나선으로 꼬인 얼굴 판 기둥: 층마다 네모 고리(옆 넷이 얼굴), 층마다 22.5 도씩 더 꼬임. spin 은 전체 돌림(도)"""
    for i in range(LEVELS):
        t = i / (LEVELS - 1)
        y = Y0 + (Y1 - Y0) * t
        h = (Y1 - Y0) / (LEVELS - 1) + 0.06
        r = R_BOT + (R_TOP - R_BOT) * t ** 0.85
        a = i * 22.5 + spin
        if ash is None:
            fs = [k.face_tex(FACE_SEQ[(i + j) % len(FACE_SEQ)]) for j in range(4)]
        else:
            fs = [ash[(i + j) % len(ash)] for j in range(4)]
        tex = {"south": fs[0], "east": fs[1], "north": fs[2], "west": fs[3], "up": ash[0] if ash else k.skin_tex("top")}
        s.box((-r, y - h / 2, -r), (r, y + h / 2, r), tex, uv=[0, 0, 16, 16], faces=("south", "east", "north", "west", "up"), rot=(0, a, 0),
              origin=(0, y, 0), face=True)


def bit(s, spin):
    """꼭대기 드릴 날: 엔드 막대기 셋이 꼬여 한 점으로 모임"""
    for j in range(3):
        a = math.radians(j * 120 + spin)
        root = (0.32 * math.cos(a), Y1 + 0.2, 0.32 * math.sin(a))
        tw = math.radians(j * 120 + spin + 70)
        d = (-0.3 * math.cos(a) + 0.25 * math.cos(tw), 1.0, -0.3 * math.sin(a) + 0.25 * math.sin(tw))
        s.rod(root, d, length=1.35, segs=1, thick=0.16, base=False, toward=(1, 0, 0), rag=False)


def drill(k):
    m = k.model(poses=4, period=8, shrink=S, mut2=False, torn=False)
    s = m.state("")
    for f in s.frames():
        with f:
            column(s, k, f.k * 22.5)
            bit(s, f.k * 22.5)
    # 밑동 얼굴(돌지 않음): 위를 올려다봄
    s.box((-1.5, -3.0, -1.5), (1.5, -2.55, 1.5), "flesh", faces=("up", "north", "south", "east", "west"), face=True)
    s.head(size=1.4, depth=0.9, at=(0, -2.15, 1.3), face="smile", near=True, rot=(-38, 0, 0))
    s.front = (0, 0, 1)


def husk(k):
    ash = [k.texture(f"ash{i}", tint(faces.far(faces.preset(n).with_(eyes="none")), ASH)) for i, n in enumerate(("smile", "half", "stitch", "tears"))]
    hole = k.texture("ash_hole", tint(faces.far(faces.preset("blank")), np.array((60, 54, 50), float), keep=0.0, gain=1.0))
    m = k.model("g180_husk", poses=1, period=20, shrink=S, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    column(s, k, 0, ash=ash)
    for j in range(3):
        a = math.radians(j * 120)
        s.rod((0.3 * math.cos(a), Y1 + 0.2, 0.3 * math.sin(a)), (-0.3 * math.cos(a), 1.0, -0.3 * math.sin(a)), length=0.9, segs=1, thick=0.15,
              base=False, toward=(1, 0, 0), rag=False)
    s.box((-1.5, -3.0, -1.5), (1.5, -2.55, 1.5), ash[0], uv=[0, 0, 16, 16], faces=("up", "north", "south", "east", "west"), face=True)
    # 빠져나간 얼굴 자국(움푹 팬 빈 구멍)
    s.box((-0.72, -2.85, 0.9), (0.72, -1.45, 1.42), {"south": hole, "east": ash[1], "west": ash[2], "up": ash[3]}, uv=[0, 0, 16, 16],
          faces=("south", "east", "west", "up"), rot=(-38, 0, 0), origin=(0, -2.15, 1.3), face=True)
    s.front = (0, 0, 0)


def mound(k):
    dirt = k.texture("dirt", tint(faces.skin_side("top"), np.array((92, 64, 46), float), keep=0.15, gain=1.0))
    m = k.model("g180_mound", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    for (z0, z1, w, h) in ((-1.4, -0.6, 0.55, 0.22), (-0.7, 0.4, 0.8, 0.38), (0.3, 1.1, 0.65, 0.3)):
        s.box((-w, 0.0, z0), (w, h, z1), dirt, uv=[0, 0, 16, 8], faces=("up", "north", "south", "east", "west"))
    s.front = (0, 0, 0)


def crack_texture(n=128):
    """나선 금 셋 + 짧은 갈래 금: 금 속은 검붉고 가장자리는 불씨 빛. 바깥은 투명"""
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    u = (xx + 0.5) / n * 2 - 1
    v = (yy + 0.5) / n * 2 - 1
    rho = np.hypot(u, v)
    ang = np.arctan2(v, u)
    out = np.zeros((n, n, 4), np.uint8)
    core = np.zeros((n, n), bool)
    edge = np.zeros((n, n), bool)
    rng = np.random.RandomState(180)
    for arm in range(3):
        a0 = arm * 2 * math.pi / 3
        # 나선: rho = 0.08 + 0.9 * frac, frac = (ang - a0 + 2 pi k) / (2 pi x 1.2)
        for kk in range(3):
            frac = ((ang - a0) % (2 * math.pi) + 2 * math.pi * kk) / (2 * math.pi * 1.25)
            target = 0.06 + 0.92 * frac
            wob = 0.012 * np.sin(ang * 9 + arm)
            d = np.abs(rho - target - wob)
            ok = (frac <= 1.0) & (rho < 0.98)
            core |= ok & (d < 0.016 + 0.01 * frac)
            edge |= ok & (d < 0.034 + 0.016 * frac)
    for i in range(10):
        a = rng.uniform(0, 2 * math.pi)
        r0 = rng.uniform(0.3, 0.75)
        l = rng.uniform(0.12, 0.25)
        px = np.cos(a) * u + np.sin(a) * v
        py = -np.sin(a) * u + np.cos(a) * v
        seg = (px > r0) & (px < r0 + l) & (np.abs(py) < 0.012 + 0.01 * (r0 + l - px) / l)
        core |= seg
        edge |= (px > r0 - 0.02) & (px < r0 + l + 0.02) & (np.abs(py) < 0.03)
    out[edge] = (255, 112, 40, 255)
    out[core] = (34, 12, 10, 255)
    return out


def build(k: kit.Kit):
    drill(k)
    husk(k)
    mound(k)
    cm = k.model("g180_crack", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    cs = cm.state("")
    cs.quad((0, 0, 0), (2, 2), "up", k.texture("crack", crack_texture()))
    cs.front = (0, 0, 0)
    k.icon(model="g180", state="")


def sounds(k: kit.Kit):
    k.sample("rumble", "tag:quake", "땅 밑 우르릉", pitch=0.7, attenuation=48)
    k.sample("drill", "tag:machine+loop", "솟는 송곳", pitch=0.5, attenuation=48)
    k.sample("shed", "tag:stone", "송곳이 허물을 벗음", pitch=0.6, attenuation=32)
    k.sample("crumble", "tag:breaking", "허물 기둥이 무너짐", pitch=0.6, attenuation=32)


def phrases():
    return {"cause_g180": "땅에서 솟은 송곳에 꿰였다", "cause_g180_near": "솟는 송곳에 휩쓸렸다"}
