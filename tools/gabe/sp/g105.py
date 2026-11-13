"""G105 병풍 게이브: 폭 0.9, 높이 3 블록 판 네 폭이 지그재그로 이어진 병풍. 폭마다 세로로 늘인 _Gave 얼굴이 먹으로 그린 그림처럼 납작하고
(종이 바탕, 먹 선), 폭 사이 경첩 셋이 세로 엔드 막대기(기둥), 폭 밑마다 짧은 막대기 발. 뒷면은 텅 빈 살빛. 틀 ±1.5 를 넘어 0.5 로 줄여 굽는다.

상태(손으로 짠 접힘 넷):
- ""(접힘: 아코디언처럼 겹쳐 선 채 막대기 발로 옆걸음, 정면에 맨 앞 폭 그림이 보임)
- "open"(폄: 넓게 지그재그로 펼친 벽, 밀어낼 때와 둘러싸기 직전)
- "wrap"(둘러쌈: 네 폭이 0.9 블록 네모로 사람을 감쌈, 그림은 바깥)
- "shut"(다 접힘: 갇힌 사람의 그림자가 맨 앞 폭에 납작하게 비침)
부위 h1, h2, h3(경첩 막대기 끊김). 실눈 뜸(open)은 그림 얼굴이 눈을 뜸.
소리 fold(병풍 접히는 소리, 24), swish(촤악 펴짐, 24), shove(판으로 밀침, 16), snap(경첩 끊김, 24). 글귀 cause_g105, cause_g105_shove.
"""
import math

import numpy as np

from gabe import faces, kit

W, TH = 0.9, 0.06          # 폭 하나의 너비, 두께
Y0, Y1 = -1.25, 1.75       # 판 아래, 위(몸 중심 기준, Java HOVER 1.55 면 발끝이 바닥)
FOOT = 0.3


def ink_tex(face):
    """먹으로 그린 얼굴: 종이 바탕(크림), 어두운 이목구비는 먹, 홍조는 옅은 분홍 물, 종이 결"""
    f = faces.far(faces.preset(face)).astype(float)
    rng = np.random.RandomState(105)
    lum = f[..., :3].mean(axis=2)
    r, g, b = f[..., 0], f[..., 1], f[..., 2]
    paper = np.array([232, 222, 198], float)
    out = np.zeros((32, 32, 4), np.uint8)
    col = np.tile(paper, (32, 32, 1)) * (0.97 + 0.03 * rng.rand(32, 32, 1))
    blush = (r - b > 48) & (lum > 150)
    col[blush] = (226, 178, 168)
    ink = lum < 150
    col[ink] = (38, 32, 34)
    mid = (lum >= 150) & (lum < 190) & ~blush
    col[mid] = (150, 136, 124)
    # 위 가장자리 먹 테두리(표구), 아래 가장자리 낙관 비슷한 붉은 점 하나
    col[0, :] = col[31, :] = (92, 64, 48)
    col[:, 0] = col[:, 31] = (92, 64, 48)
    col[27:29, 25:27] = (160, 40, 36)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def plain(rgb, n=8, seed=1, jitter=6):
    rng = np.random.RandomState(seed)
    a = np.zeros((n, n, 4), np.uint8)
    a[..., :3] = np.clip(np.array(rgb, float)[None, None, :] + rng.randn(n, n, 1) * jitter, 0, 255)
    a[..., 3] = 255
    return a


def shadow_tex():
    """갇힌 사람의 그림자: 머리, 어깨, 몸, 다리 실루엣(납작), 나머지는 투명"""
    a = np.zeros((32, 32, 4), np.uint8)
    yy, xx = np.mgrid[0:32, 0:32].astype(float)
    head = ((xx - 15.5) / 3.2) ** 2 + ((yy - 5) / 3.6) ** 2 <= 1
    torso = (abs(xx - 15.5) <= 5.5 - np.clip((yy - 10) * 0.08, 0, 2)) & (yy >= 9) & (yy <= 20)
    arms = (abs(abs(xx - 15.5) - 7.2) <= 1.4) & (yy >= 10) & (yy <= 19)
    legs = (abs(abs(xx - 15.5) - 2.4) <= 1.8) & (yy > 20) & (yy <= 31)
    m = head | torso | arms | legs
    a[m] = (34, 22, 30, 255)
    return a


def zig(phis):
    """폭마다 방향 각(도, +X 에서 +Z 쪽으로)으로 경첩 점들. 가운데를 원점으로 옮김"""
    pts = [np.zeros(3)]
    for p in phis:
        r = math.radians(p)
        pts.append(pts[-1] + np.array([math.cos(r), 0, math.sin(r)]) * W)
    c = (np.min(pts, axis=0) + np.max(pts, axis=0)) / 2
    return [p - c for p in pts]


def screen(s, k, phis, outward=None, feet=False, shadow=False):
    ink = k.texture("ink", ink_tex("smile"))
    ink_open = k.texture("ink_open", ink_tex("open"))
    back = k.part_tex("flesh")
    frame = k.texture("frame", plain((96, 66, 50), seed=3))
    pts = zig(phis)
    centers = []
    for i, p in enumerate(phis):
        h = pts[i]
        deg = -p  # 오른손 Ry: +X 가 (cos p, 0, sin p) 로
        R = kit.euler_m(0, deg, 0)
        n_s = R @ np.array([0.0, 0.0, 1.0])  # south 면이 보는 쪽
        mid = h + (pts[i + 1] - h) / 2
        centers.append(mid)
        if outward is None:
            front_south = n_s[2] >= 0  # 앞(+Z) 쪽을 보는 면에 그림
        else:
            front_south = float(np.dot(n_s, mid - outward)) >= 0  # 바깥을 보는 면에 그림
        fn, bn = ("south", "north") if front_south else ("north", "south")
        tex = {fn: ink, bn: back, "east": frame, "west": frame, "up": frame, "down": frame}
        frm = (h[0], Y0, h[2] - TH / 2)
        to = (h[0] + W, Y1, h[2] + TH / 2)
        s.box(frm, to, tex, uv={fn: [0, 0, 16, 16], bn: [0, 0, 16, 16], "east": [0, 0, 2, 16], "west": [0, 0, 2, 16],
                                "up": [0, 0, 16, 2], "down": [0, 0, 16, 2]}, rot=(0, deg, 0), origin=tuple(h), face=True)
        # 눈 뜸: 그림 얼굴 앞에 눈 뜬 그림을 겹침
        with s.when("open"):
            z = TH / 2 + 0.012
            zz = h[2] + (z if front_south else -z)
            s.box((h[0], Y0, zz), (h[0] + W, Y1, zz), ink_open, uv=[0, 0, 16, 16], faces=(fn,), rot=(0, deg, 0), origin=tuple(h), face=True)
        if feet:
            s.rod((mid[0], Y0 + 0.02, mid[2]), (0, -1, 0), length=FOOT, segs=1, thick=0.09, toward=(n_s[0], 0, n_s[2]),
                  wave=kit.Wave(amp=26, phase=90 * i), poses=4, base=False, rag=False)
        elif i in (0, 3):
            s.rod((mid[0], Y0 + 0.02, mid[2]), (0, -1, 0), length=FOOT, segs=1, thick=0.09, base=False, toward=(1, 0, 0), rag=False)
    # 경첩 셋: 세로 엔드 막대기(기둥)
    for j in (1, 2, 3):
        with s.when(f"!on:h{j}"):
            h = pts[j]
            s.rod((h[0], Y0 - 0.05, h[2]), (0, 1, 0), length=Y1 - Y0 + 0.15, segs=1, thick=0.12, toward=(1, 0, 0), base=False, rag=False)
    if shadow:
        # 맨 앞(가장 +Z) 폭 앞에 갇힌 사람 그림자
        i = int(np.argmax([c[2] for c in centers]))
        c = centers[i]
        s.quad((c[0], (Y0 + Y1) / 2 - 0.1, c[2] + 0.12), (0.72, 2.3), "south", k.texture("shadow", shadow_tex()))
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=0.5, mut2=False, torn=False)
    screen(m.state(""), k, (12, 168, 12, 168), feet=True)
    screen(m.state("open"), k, (35, -35, 35, -35))
    screen(m.state("wrap"), k, (0, 90, 180, 270), outward=np.zeros(3))
    screen(m.state("shut"), k, (8, 172, 8, 172), shadow=True)
    k.icon(model="g105", state="open")


def sounds(k: kit.Kit):
    k.sample("fold", "tag:creak+wood", "병풍 접히는 소리", pitch=0.8, attenuation=24)
    k.sample("swish", "tag:cloth", "촤악 펴짐", pitch=0.9, attenuation=24)
    k.sample("shove", "oga_metalwood100/wood_slam_0*", "판으로 밀침", pitch=0.9, attenuation=16)
    k.sample("snap", "oga_breaking75/bfh1_wood_breaking_0*", "경첩이 끊김", pitch=1.0, attenuation=24)


def phrases():
    return {"cause_g105": "병풍에 갇혔다", "cause_g105_shove": "병풍에 떠밀렸다"}
