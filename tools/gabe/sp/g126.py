"""G126 줄 인형 부리는 게이브(3막 27라운드, 지원 + 방해): 14블록 높이에 뜬 폭 10블록의 열십자 조종대. 굵은 엔드 막대기 둘을 열십자로 엇건 것이고,
엇갈린 한가운데에 3블록 얼굴이 아래를 보고 엎드려 있다. 막대기 네 끝과 가운데에서 가는 줄 여섯이 땅까지 내려가고, 줄마다 게이브 사체가 인형으로
매달린다(인형은 그 종의 모델을 Java 가 부품으로 들고 다님).

모델:
  gave:gabe/g126         조종대(가운데 = 엇갈린 자리). 상태 "" 보통, "tilt" 내리찍기 직전(얼굴이 눈을 부릅뜨고 입을 벌림)
  gave:gabe/g126_string  가는 줄(뿌리 y 0, 아래로 길이 1: Java 가 늘이고 기울임)
  gave:gabe/g126_disc    바닥 원(반지름 1): "" 그림자 원(인형이 내리찍을 자리로 모임), "warn" 빨간 예고
  gave:gabe/g126_shadow  열십자 그림자(12 x 12, 점묘)
"""
import math

import numpy as np

from gabe import faces, kit

ARM = 5.0


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def bar(s, k, tilt):
    rod = dict(segs=2, thick=0.6, wave=None, toward=(0, 1, 0), base=False, rag=False)
    s.rod((-ARM, 0.3, 0), (1, 0, 0), length=ARM * 2, **rod)
    s.rod((0, 0.9, -ARM), (0, 0, 1), length=ARM * 2, **rod)
    # 끝 고리(줄이 매이는 살 혹)
    for (x, z) in ((ARM, 0), (-ARM, 0), (0, ARM), (0, -ARM)):
        y = 0.3 if z == 0 else 0.9
        s.box((x - 0.42, y - 0.42, z - 0.42), (x + 0.42, y + 0.42, z + 0.42), "flesh", face=True)
        s.box((x - 0.12, y - 0.9, z - 0.12), (x + 0.12, y - 0.4, z + 0.12), "bone")
    # 엎드린 얼굴(아래를 봄, 이마가 앞 +Z)
    face = faces.Face(eyes="bloodshot", mouth="teeth_ring", blush="deep") if tilt else faces.Face(eyes="half")
    s.head(size=3.0, depth=1.2, at=(0, -0.5, 0), face=face, rot=(90, 0, 0), torn=False)
    # 얼굴 뒤통수를 누르는 살 받침
    s.box((-1.0, 0.1, -1.0), (1.0, 0.65, 1.0), "flesh", face=True, faces=("north", "south", "east", "west", "up"))


def string_tex(n=8, h=64):
    rng = np.random.RandomState(126)
    out = np.zeros((h, n, 4), np.uint8)
    for y in range(h):
        for x in range(n):
            v = 0.85 + 0.1 * math.sin(y * 0.9 + x) + rng.rand() * 0.05
            out[y, x] = (int(240 * v), int(226 * v), int(206 * v) & 0xF8, 255)
    return out


def disc_tex(warn, n=48):
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r > 1:
                continue
            if warn:
                if r > 0.88:
                    out[y, x] = (214, 36, 40, 255)
                elif bay[y % 4, x % 4] < 0.4:
                    out[y, x] = (190, 28, 34, 255)
            elif bay[y % 4, x % 4] < 0.7 * min(1, (1 - r) / 0.3):
                out[y, x] = (26, 18, 24, 255)
    return faces._fill_rest(out)


def shadow_tex(dens, n=128):
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer()
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            px, py = (x - c) / c, (y - c) / c
            arm = (abs(py) < 0.07 and abs(px) < 0.92) or (abs(px) < 0.07 and abs(py) < 0.92)
            face = abs(px) < 0.28 and abs(py) < 0.28
            if (arm or face) and bay[y % 4, x % 4] < dens:
                out[y, x] = (24, 16, 22, 255)
    return faces._fill_rest(out)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.26, mut2=False, torn=False, rag=False)
    bar(m.state(""), k, False)
    bar(m.state("tilt"), k, True)
    st = k.model("g126_string", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    t = k.texture("string", string_tex())
    w = 0.07
    s = st.state("")
    s.box((-w, -1.0, 0), (w, 0, 0), t, faces=("north", "south"))
    s.box((0, -1.0, -w), (0, 0, w), t, faces=("east", "west"))
    ds = k.model("g126_disc", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    ds.state("").quad((0, 0, 0), (2, 2), "up", k.texture("disc", disc_tex(False)))
    ds.state("warn").quad((0, 0, 0), (2, 2), "up", k.texture("warn", disc_tex(True)))
    sh = k.model("g126_shadow", poses=1, period=20, shrink=0.2, mut2=False, open=False, torn=False, rag=False)
    sh.state("").quad((0, 0, 0), (12, 12), "up", k.texture("shadow", shadow_tex(0.6)))
    k.icon(model="g126", state="")


def sounds(k: kit.Kit):
    k.sample("creak", "tag:creak", "줄 당기는 소리", pitch=0.7, attenuation=48)
    k.sample("slam", "tag:slam", "인형이 내리찍힌다", pitch=0.7, attenuation=48)
    k.sample("snap", "tag:crack", "줄 끊김", pitch=1.3, attenuation=40, n=2)


def phrases():
    return {"cause_g126": "줄 인형에 들이받혔다", "cause_g126_slam": "줄 인형들이 내리찍었다", "cause_g126_line": "인형 줄에 걸렸다"}
