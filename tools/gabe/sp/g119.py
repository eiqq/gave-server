"""G119 이름 연 게이브(3막 24라운드, 방해 + 정예): 가로 5, 세로 6블록 방패연 꼴 얼굴 판. 한가운데 둥근 구멍이 입이고, 엔드 막대기 셋이 연살
(가위표와 가로대)로 판을 받친다. 이마 자리에 표적 한 사람의 이름이 적힌다(이름은 Java 가 글 디스플레이로: G119Tag). 아래로 살 띠 연줄이
늘어지고 끝에 막대기 둘을 십자로 엮은 얼레가 땅 위 1블록에서 끌린다.

모델:
  gave:gabe/g119        연 판(가운데 = 판 가운데, 앞 +Z 가 얼굴, 판은 XY 면). Java 가 몸 pitch 로 아래를 보게 기울인다
      ""     실눈 웃음, 입 구멍
      "pull" 끌어 올리는 동안: 핏발 선 눈, 입 구멍이 더 큼
  gave:gabe/g119_line   연줄(살 띠, 뿌리 y 0 에서 아래로 길이 1). Java 가 길이만큼 늘리고 얼레 쪽으로 돌린다
  gave:gabe/g119_reel   얼레(막대기 둘 십자 + 감긴 살 띠 + 작은 얼굴)
"""
import math

import numpy as np

from gabe import faces, kit

PW, PH = 5.0, 6.0          # 판 폭, 높이
HOLE = (16.0, 19.5)        # 입 구멍 가운데(32 칸 그림)


def holed(face, r, back=False):
    """얼굴(64 칸으로 키움)에 둥근 구멍(투명) + 입술 테. back 이면 연 뒷면(바랜 살 종이 + 같은 구멍)"""
    if back:
        n = 64
        rng = np.random.RandomState(119)
        yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
        v = 0.82 + 0.06 * np.sin(xx * 0.45) + rng.rand(n, n) * 0.06
        im = np.zeros((n, n, 4), np.uint8)
        im[..., 0] = np.clip(v * 236, 0, 255)
        im[..., 1] = np.clip(v * 196, 0, 255)
        im[..., 2] = np.clip(v * 188, 0, 255).astype(np.uint8) & 0xF8
        im[..., 3] = 255
    else:
        im = np.array(faces.far(face), np.uint8)
        im = im.repeat(2, axis=0).repeat(2, axis=1)
        n = 64
    cx, cy = HOLE[0] * 2, HOLE[1] * 2
    for y in range(n):
        for x in range(n):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d < r * 2:
                im[y, x] = (0, 0, 0, 0)
            elif d < r * 2 + 2.2:
                c = (110, 32, 34) if not back else (150, 92, 88)
                im[y, x, 0], im[y, x, 1] = c[0], c[1]
                im[y, x, 2] = (c[2] & 0xF8) | 3  # 화소 부류 3(입)
                im[y, x, 3] = 255
    return faces._fill_rest(im)


def kite(s, k, pull):
    face = faces.Face(eyes="bloodshot" if pull else "squint", mouth="none", blush="deep" if pull else "pink")
    r = 5.2 if pull else 4.2
    front = k.texture("plate_pull" if pull else "plate", holed(face, r), classes=True)
    back = k.texture("back_pull" if pull else "back", holed(face, r, back=True), classes=True)
    s.quad((0, 0, 0.06), (PW, PH), "south", front, face=True)
    s.quad((0, 0, -0.06), (PW, PH), "north", back, face=True)
    # 가장자리 테(얇은 살 띠)
    for (a, b) in (((-PW / 2, PH / 2), (PW / 2, PH / 2)), ((-PW / 2, -PH / 2), (PW / 2, -PH / 2))):
        s.box((a[0], a[1] - 0.06, -0.08), (b[0], a[1] + 0.06, 0.08), "flesh", face=True, faces=("north", "south", "up", "down"))
    for x in (-PW / 2, PW / 2):
        s.box((x - 0.06, -PH / 2, -0.08), (x + 0.06, PH / 2, 0.08), "flesh", face=True, faces=("north", "south", "east", "west"))
    # 연살: 가위표 둘 + 가로대(뒤에 붙음)
    z = -0.16
    for sx in (1, -1):
        root = (-sx * (PW / 2 - 0.15), -(PH / 2 - 0.15), z)
        d = (sx * (PW - 0.3), PH - 0.3, 0)
        s.rod(root, d, length=math.hypot(d[0], d[1]), segs=3, thick=0.16, wave=None, toward=(0, 0, 1), base=False, rag=False)
    s.rod((-(PW / 2 - 0.1), PH / 2 - 0.9, z - 0.05), (1, 0, 0), length=PW - 0.2, segs=2, thick=0.16, wave=None, toward=(0, 0, 1), base=False,
          rag=False)
    # 연줄이 매이는 고리(아래 가운데)
    s.box((-0.18, -PH / 2 - 0.3, -0.18), (0.18, -PH / 2 + 0.05, 0.18), "flesh", face=True)
    s.front = (0, 0, 1)


def line(s, k):
    """연줄: 살 띠 두 장 십자(뿌리 y 0, 끝 y -1)"""
    fl = k.part_tex("flesh")
    w = 0.16
    s.box((-w, -1.0, 0), (w, 0, 0), fl, faces=("north", "south"), face=True)
    s.box((0, -1.0, -w), (0, 0, w), fl, faces=("east", "west"), face=True)


def reel(s, k):
    """얼레: 막대기 둘 십자(뼈대) + 가운데 감긴 살 띠 뭉치와 작은 얼굴"""
    for sx in (1, -1):
        s.rod((-sx * 0.7, -0.7, 0), (sx * 1.4, 1.4, 0), length=1.98, segs=1, thick=0.14, wave=None, toward=(0, 0, 1), base=False, rag=False)
    s.box((-0.35, -0.35, -0.3), (0.35, 0.35, 0.3), "flesh", face=True)
    s.head(size=0.5, depth=0.12, at=(0, 0, 0.36), face="half", near=False, torn=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.45, mut2=False, torn=False, rag=False)
    kite(m.state(""), k, False)
    kite(m.state("pull"), k, True)
    ln = k.model("g119_line", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    line(ln.state(""), k)
    rl = k.model("g119_reel", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    reel(rl.state(""), k)
    k.icon(model="g119", state="")


def sounds(k: kit.Kit):
    k.sample("hum", "tag:wind", "연 우는 소리", pitch=1.3, attenuation=48)
    k.sample("gulp", "tag:eat", "꿀꺽", pitch=0.6, attenuation=32)
    k.sample("snap", "tag:crack", "연줄 끊김", pitch=1.2, attenuation=48, n=2)


def phrases():
    return {"cause_g119": "이름 연에 끌려 올라갔다", "cause_g119_link": "내 이름 연이 맞았다", "cause_g119_reel": "얼레에 걸렸다"}
