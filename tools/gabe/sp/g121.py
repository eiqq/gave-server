"""G121 채 가는 둥지 게이브(3막 25라운드, 방해 + 원거리): 엔드 막대기 수십 대를 나뭇가지처럼 얼기설기 엮은 폭 9블록 둥지가 하늘에 떠 있다. 둥지 테
둘레로 0.9블록 얼굴 다섯이 새끼처럼 고개를 내밀어 아래를 내려다보고, 둥지 바닥에는 탈색한 얼굴 가죽이 깃털처럼 깔렸다. 밑으로 살 띠가 줄처럼
곧게 드리우고, 테에서 뽑힌 막대기 가지가 창처럼 떨어진다.

모델:
  gave:gabe/g121         둥지(가운데 = 둥지 몸 가운데, 테는 위 y +1.2, 바닥 y -2). 테의 새끼 얼굴 다섯은 고개를 내밀었다 넣는 2자세
      ""      보통
      "open"  먹이를 기다림(새끼 얼굴이 입을 벌리고 더 내밂): 쓰러진 사람에게 내려앉을 때
  gave:gabe/g121_branch  떨어지는 가지 창(뿌리 y 0 이 위, 끝이 아래 -3.6)
  gave:gabe/g121_band    감아 올리는 살 띠(뿌리 y 0, 아래로 길이 1: Java 가 늘림)
  gave:gabe/g121_warn    가지 떨어질 자리 빨간 예고 원(반지름 1, Java 가 늘림). 상태 "" 옅음, "fill" 짙음
  gave:gabe/g121_shadow  얼기설기한 둥지 그림자(11 x 11, 점묘 + 가지 줄). 상태 s1, s2
"""
import math

import numpy as np

from gabe import faces, kit

R = 4.4         # 테 반지름
RIM_Y = 1.2
BOT_Y = -2.0
CHICKS = 5


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def pale_face(seed):
    """탈색한 얼굴 가죽: 얼굴 그림을 바래게(회백), 가장자리 너덜"""
    f = [faces.Face(), faces.Face(eyes="none"), faces.Face(extras=("stitch",))][seed % 3]
    im = faces.far(f).astype(np.float32)
    g = im[..., :3].mean(axis=2, keepdims=True)
    im[..., :3] = im[..., :3] * 0.35 + g * 0.65 + 18
    rng = np.random.RandomState(121 + seed)
    n = im.shape[0]
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    r = np.maximum(abs(xx - 15.5), abs(yy - 15.5)) / 16 + rng.rand(n, n) * 0.12
    out = np.clip(im, 0, 255).astype(np.uint8)
    out[..., 3] = np.where(r < 1.0, 255, 0)
    return faces._fill_rest(out)


def nest(s, k, gape):
    rng = np.random.RandomState(121)
    rod = dict(segs=1, thick=0.2, wave=None, toward=(0, 0, 1), base=False, rag=False)
    # 테: 고리 두 겹(조금씩 어긋나게)
    for ring, (rr, yy, n) in enumerate(((R, RIM_Y, 12), (R - 0.35, RIM_Y - 0.45, 10))):
        for i in range(n):
            a0 = (i + ring * 0.5) / n * math.tau
            a1 = a0 + math.tau / n * 1.25
            p0 = (math.cos(a0) * rr, yy + rng.uniform(-0.15, 0.15), math.sin(a0) * rr)
            p1 = (math.cos(a1) * rr, yy + rng.uniform(-0.15, 0.15), math.sin(a1) * rr)
            d = tuple(p1[j] - p0[j] for j in range(3))
            s.rod(p0, d, length=math.dist(p0, p1) + 0.3, **rod)
    # 옆구리: 테에서 바닥 쪽으로 비스듬히 엮은 가지(서로 엇갈림)
    for i in range(14):
        a = i / 14 * math.tau + rng.uniform(-0.1, 0.1)
        tw = 0.55 if i % 2 else -0.55
        p0 = (math.cos(a) * (R - 0.2), RIM_Y - 0.2, math.sin(a) * (R - 0.2))
        a1 = a + tw
        p1 = (math.cos(a1) * 1.4, BOT_Y + 0.1, math.sin(a1) * 1.4)
        d = tuple(p1[j] - p0[j] for j in range(3))
        s.rod(p0, d, length=math.dist(p0, p1), segs=1, thick=0.2, wave=None, toward=(0, 0, 1), base=False, rag=False)
    # 바닥 얼기설기
    for i in range(5):
        a = i / 5 * math.pi + 0.3
        p0 = (math.cos(a) * 1.8, BOT_Y, math.sin(a) * 1.8)
        s.rod(p0, (-math.cos(a), 0.02, -math.sin(a)), length=3.6, segs=1, thick=0.22, wave=None, toward=(0, 0, 1), base=False, rag=False)
    # 삐져나온 가지 끝(테 바깥으로, 창처럼)
    for i in range(7):
        a = i / 7 * math.tau + 0.2
        p0 = (math.cos(a) * R, RIM_Y - 0.1, math.sin(a) * R)
        s.rod(p0, (math.cos(a), 0.25 - (i % 3) * 0.25, math.sin(a)), length=1.3, segs=1, thick=0.16, wave=None, toward=(0, 1, 0) if i % 3 == 1 else (0, 0, 1),
              base=False, rag=False)
    # 바닥 깃털 = 탈색 얼굴 가죽(아래를 봄) + 속(살)
    for i in range(6):
        a = i / 6 * math.tau
        c = (math.cos(a) * 1.5, BOT_Y - 0.12, math.sin(a) * 1.5)
        s.quad(c, (1.8, 1.8), "down", k.texture(f"skin{i % 3}", pale_face(i)), rot=(0, i * 37, 0), origin=c)
    s.quad((0, BOT_Y - 0.14, 0), (1.9, 1.9), "down", k.texture("skin0", pale_face(0)), rot=(0, 15, 0), origin=(0, BOT_Y - 0.14, 0))
    s.box((-2.6, BOT_Y + 0.1, -2.6), (2.6, RIM_Y - 0.6, 2.6), "wetskin", faces=("up",), face=True)
    # 드리운 살 띠 뿌리(짧게)
    for i in range(4):
        a = i / 4 * math.tau + math.pi / 4
        s.box((math.cos(a) * 1.2 - 0.12, BOT_Y - 1.0, math.sin(a) * 1.2 - 0.12), (math.cos(a) * 1.2 + 0.12, BOT_Y, math.sin(a) * 1.2 + 0.12),
              "flesh", face=True, faces=("north", "south", "east", "west"))
    # 새끼 얼굴 다섯: 테에서 아래 바깥을 내려다봄, 2자세로 고개를 내밀었다 넣음
    chick = faces.Face(eyes="open", mouth="teeth_ring", blush="deep") if gape else faces.Face(eyes="half")
    for f in s.frames(poses=2):
        with f:
            for i in range(CHICKS):
                a = i / CHICKS * math.tau + 0.35
                out = R + (0.55 if f.k else 0.2) + (0.35 if gape else 0)
                c = (math.cos(a) * out, RIM_Y + 0.05 - (0.15 if f.k else 0), math.sin(a) * out)
                yaw = -math.degrees(a) + 90
                s.head(size=0.9, depth=0.7, at=c, face=chick, rot=(40 if not gape else 55, yaw, 0), near=False, torn=False)


def branch(s, k):
    """떨어지는 가지 창: 곧은 굵은 막대기 + 잔가지 둘 + 뾰족한 끝(뼈)"""
    s.rod((0, 0, 0), (0.0, -1, 0.04), length=3.4, segs=2, thick=0.24, wave=None, toward=(0, 0, 1), base=False, rag=False)
    for sx in (1, -1):
        s.rod((0, -1.0 - sx * 0.3, 0), (sx * 0.8, 0.6, 0.1), length=0.8, segs=1, thick=0.1, wave=None, toward=(0, 0, 1), base=False, rag=False)
    s.box((-0.09, -3.9, -0.09), (0.09, -3.35, 0.09), "bone")
    s.front = (0, 0, 0)


def band(s, k):
    fl = k.part_tex("flesh")
    w = 0.2
    s.box((-w, -1.0, 0), (w, 0, 0), fl, faces=("north", "south"), face=True)
    s.box((0, -1.0, -w), (0, 0, w), fl, faces=("east", "west"), face=True)


def warn_tex(fill, n=64):
    """빨간 예고 원: 또렷한 테두리 + 안쪽 점묘(fill 이면 짙게)"""
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r > 1.0:
                continue
            if r > 0.9:
                out[y, x] = (214, 36, 40, 255)
            elif bay[y % 4, x % 4] < (0.62 if fill else 0.18):
                out[y, x] = (190, 28, 34, 255)
    return faces._fill_rest(out)


def shadow_tex(dens, n=128, seed=121):
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    # 가지 줄(엇갈린 선)
    lines = []
    for i in range(22):
        a = rng.uniform(0, math.tau)
        r0 = rng.uniform(0.2, 0.9)
        lines.append((math.cos(a) * r0, math.sin(a) * r0, rng.uniform(0, math.pi)))
    for y in range(n):
        for x in range(n):
            px, py = (x - c) / c, (y - c) / c
            r = math.hypot(px, py)
            if r > 1.08:
                continue
            twig = any(abs((px - lx) * math.sin(la) - (py - ly) * math.cos(la)) < 0.025 and math.hypot(px - lx, py - ly) < 0.5
                       for lx, ly, la in lines)
            d = dens * (0.55 if r < 0.85 else max(0.0, (1.08 - r) / 0.23) * 0.55)
            if twig and r < 1.05 or bay[y % 4, x % 4] < d:
                out[y, x] = (24, 18, 22, 255)
    return faces._fill_rest(out)


def build(k: kit.Kit):
    m = k.model(poses=2, period=24, shrink=0.22, mut2=False, torn=False, rag=False)
    nest(m.state(""), k, False)
    nest(m.state("open"), k, True)
    br = k.model("g121_branch", poses=1, period=20, shrink=0.35, mut2=False, open=False, torn=False, rag=False)
    branch(br.state(""), k)
    bd = k.model("g121_band", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    band(bd.state(""), k)
    wn = k.model("g121_warn", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    wn.state("").quad((0, 0, 0), (2, 2), "up", k.texture("warn", warn_tex(False)))
    wn.state("fill").quad((0, 0, 0), (2, 2), "up", k.texture("warn_fill", warn_tex(True)))
    sh = k.model("g121_shadow", poses=1, period=20, shrink=0.2, mut2=False, open=False, torn=False, rag=False)
    for i, dens in enumerate((0.35, 0.7), start=1):
        sh.state(f"s{i}").quad((0, 0, 0), (11, 11), "up", k.texture(f"shadow{i}", shadow_tex(dens)))
    sh.default = "s1"
    k.icon(model="g121", state="")


def sounds(k: kit.Kit):
    k.sample("caw", "tag:bird", "둥지 우는 소리", pitch=0.55, attenuation=96)
    k.sample("stab", "tag:wood", "가지 내리꽂힘", pitch=0.6, attenuation=48)
    k.sample("wrap", "tag:wet", "살 띠가 감긴다", pitch=0.7, attenuation=32)


def phrases():
    return {"cause_g121": "둥지 가지에 꿰였다"}
