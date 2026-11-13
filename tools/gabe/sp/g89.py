"""G89 조개 게이브: 폭 1.8 블록 두 짝 껍데기. 겉은 굳은 얼굴 가죽이 물결무늬로 겹친 돌빛, 윗짝 한가운데에 눌린 실눈 웃음이 돋을새김.
뒤쪽 경첩이 굵은 엔드 막대기 둘(구조 막대기, 자세 없음). 닫힌 틈으로 분홍 살이 삐져나왔고, 열리면 안에 눈을 뜬 1블록 얼굴이 통째로.

상태: ""(닫힘, 미끄러지며 살짝 여닫는 2자세), "open"(활짝 벌림, 안쪽 얼굴 눈 뜸), "bite"(다리를 물고 매달림: 반쯤 다묾),
"broken"(경첩이 끊겨 벌어진 채 비뚤어짐). 부위 "h1"(+X 경첩 끊김), "h2"(-X 경첩 끊김).
"""
import math

import numpy as np

from gabe import faces, kit

W = 0.9          # 반 폭
ZB, ZF = -0.7, 0.8   # 뒤(경첩), 앞(입)
TH = 0.3         # 한 짝 두께
HINGE_Z = ZB


def shell_tex(n=32, seed=89):
    """돌빛 껍데기: 경첩에서 퍼지는 물결 골(겹친 얼굴 가죽)"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    r = np.hypot(xx - n / 2, yy - n * 1.15)
    ridge = np.sin(r * 1.25 + np.sin(xx * 0.5) * 0.8)
    base = np.array([168, 156, 150], float)
    col = base[None, None, :] * (0.86 + 0.14 * ridge)[..., None] + rng.randn(n, n, 1) * 3
    col[ridge < -0.82] = (112, 98, 98)
    col[(ridge > 0.9) & (rng.rand(n, n) < 0.5)] = (206, 190, 182)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def emboss_tex():
    """윗짝 돋을새김: 실눈 웃음 얼굴을 돌빛으로 눌러 찍음(어두운 이목구비는 골, 살은 돌)"""
    f = faces.far(faces.preset("smile")).astype(float)
    lum = f[..., :3].mean(axis=2)
    stone = shell_tex(32, 90).astype(float)
    dark = lum < 150
    out = stone.copy()
    out[..., :3] = np.where(dark[..., None], np.array([92, 78, 80], float), stone[..., :3] * 1.06)
    # 골 아래 한 칸은 밝게(돋을새김 그림자)
    lit = np.zeros_like(dark)
    lit[1:, :] = dark[:-1, :] & ~dark[1:, :]
    out[..., :3] = np.where(lit[..., None], np.array([224, 210, 202], float), out[..., :3])
    out[..., 3] = 255
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


def valve(s, k, top, ang, roll=0.0):
    """껍데기 한 짝. top 이면 윗짝(ang 도만큼 경첩에서 들림), 아니면 아랫짝"""
    shell = k.texture("shell", shell_tex())
    emb = k.texture("emboss", emboss_tex())
    flesh = k.part_tex("flesh")
    sg = 1 if top else -1
    rot = dict(rot=(-ang if top else 0.0, 0.0, roll), origin=(0, 0, HINGE_Z)) if (top and (ang or roll)) else {}
    # 넓은 판(틈 쪽 면은 분홍 살)
    y0, y1 = (0.02, TH) if top else (-TH, -0.02)
    inner = "down" if top else "up"
    outer = "up" if top else "down"
    tex = {n: shell for n in ("north", "south", "east", "west", outer)}
    tex[inner] = flesh
    s.box((-W, y0, ZB), (W, y1, ZF), tex, uv=[0, 0, 16, 16], **rot)
    # 볼록한 둥근 등(계단 둘): 실루엣이 상자 하나로 읽히지 않게
    for i, (wx, zb, zf, hh) in enumerate(((0.7, ZB + 0.12, ZF - 0.2, 0.14), (0.45, ZB + 0.28, ZF - 0.45, 0.1))):
        a = TH + sum(h for (_, _, _, h) in ((0.7, 0, 0, 0.14), (0.45, 0, 0, 0.1))[:i])
        b = a + hh
        lo, hi = (a, b) if top else (-b, -a)
        t2 = {n: shell for n in ("north", "south", "east", "west")}
        t2[outer] = emb if (top and i == 1) else shell
        s.box((-wx, lo, zb), (wx, hi, zf), t2, faces=("north", "south", "east", "west", outer), uv=[0, 0, 16, 16], **rot)
    # 가장자리 이빨 같은 물결 골(앞 가장자리 아래로 살짝 내려온 턱)
    if top:
        s.box((-W + 0.1, -0.04, ZF - 0.06), (W - 0.1, 0.06, ZF + 0.03), shell, uv=[0, 0, 16, 3], **rot)


def hinges(s):
    for side, part in ((1, "h1"), (-1, "h2")):
        with s.when("!on:" + part):
            s.rod((side * 0.45, 0, HINGE_Z + 0.1), (0, 0, -1), length=0.42, segs=1, thick=0.2, toward=(0, 1, 0))
        with s.when("on:" + part):
            s.rod((side * 0.45, 0, HINGE_Z + 0.1), (side * 0.3, -0.5, -1), length=0.16, segs=1, thick=0.2, toward=(0, 1, 0), base=False)


def inside(s, k, tilt):
    """안쪽 얼굴(1블록, 눈 뜸)과 분홍 살 깔개"""
    s.head(size=1.0, depth=0.5, at=(0, 0.42, 0.0), face="open", open=False, rot=(-tilt, 0, 0))
    s.box((-0.8, -0.02, ZB + 0.1), (0.8, 0.05, ZF - 0.05), "flesh", uv=[0, 0, 16, 16], face=True)


def build(k: kit.Kit):
    m = k.model(poses=2, period=20, mut2=False)
    # 닫힘: 윗짝이 0도, 5도로 살짝 여닫힘. 틈으로 분홍 살
    s = m.state("")
    valve(s, k, False, 0)
    for f in s.frames(2):
        with f:
            valve(s, k, True, 0 if f.k == 0 else 5)
    s.box((-0.78, -0.03, ZF - 0.5), (0.78, 0.04, ZF + 0.02), "flesh", uv=[0, 0, 16, 2], face=True)
    hinges(s)
    s.front = (0, 1, 0)

    # 활짝 벌림
    o = m.state("open", poses=1)
    valve(o, k, False, 0)
    valve(o, k, True, 68)
    inside(o, k, 12)
    hinges(o)

    # 물고 매달림: 반쯤 다묾
    b = m.state("bite", poses=1)
    valve(b, k, False, 0)
    valve(b, k, True, 30)
    inside(b, k, 30)
    hinges(b)

    # 경첩이 끊겨 벌어진 채 비뚤어짐
    br = m.state("broken", poses=1)
    valve(br, k, False, 0)
    valve(br, k, True, 82, roll=9)
    inside(br, k, 8)
    hinges(br)
    k.icon(model="g89", state="open")


def sounds(k: kit.Kit):
    k.sample("clack", "tag:switch", "껍데기 딱", pitch=0.7, attenuation=20)
    k.sample("creak", "tag:creak", "껍데기 끼익", pitch=0.8, attenuation=20)
    k.sample("snap", "tag:slam", "덥석", pitch=1.3, attenuation=16)
    k.sample("ting", "tag:metal+click", "탄이 튕김", pitch=1.4, volume=0.7, attenuation=20)


def phrases():
    return {"cause_g89": "조개에 물렸다"}
