"""G184 펼친 책 게이브(3막 29라운드, 지원, 방해, 10블록): 하늘 16블록에 펼쳐져 아래를 향한 폭 10블록의 책. 책등은 굵은 엔드 막대기 둘을 나란히
묶은 것이고 책장은 길게 늘인 _Gave 얼굴 종이 아홉 장이다. 책등 한가운데 1.5블록 얼굴이 아래를 내려다보고, 책갈피처럼 가는 엔드 막대기 하나가
책장 사이에서 아래로 길게 늘어져 읽는 줄을 짚는다. 넘어간 책장 얼굴은 눈을 뜨고 읽힌 사람의 얼룩 윤곽이 남는다.

모델(블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. 책등은 Z 축, 책장은 아래를 봄. Java 는 Body.carrier + 부품):
  gave:gabe/g184         책등(막대기 둘 + 묶은 띠) + 책등 얼굴(아래를 봄) + 책갈피 막대기(부위 mark_cut: 꺾임). 상태 "" 실눈, "read" 눈 뜸(빛줄기 내리는 중)
  gave:gabe/g184_r       오른쪽 절반(+X): 표지 + 아직 안 넘긴 책장 아홉(장마다 !on:p1..p9)
  gave:gabe/g184_l       왼쪽 절반(+X 로 굽고 Java 가 반 바퀴 돌림): 표지 + 넘긴 책장(on:p1..p9, 눈 뜸 + 얼룩 윤곽)
  gave:gabe/g184_page    넘어가는 책장 한 장(책등 경첩, 아래 얼굴 + 위 뒷면). Java 가 Z 축으로 0 → 180도 돌림
  gave:gabe/g184_beam    창백한 빛줄기(엇갈린 판 넷, 반지름 0.5 높이 1, 위 = 0). Java 가 늘림, 밝기 15
  gave:gabe/g184_ring    바닥 창백한 원(위를 보는 판 2x2). 상태 "" 예고(옅게), "on" 빛 안
소리는 받은 소리(k.sample). 사체 없음(덮이며 하늘로 사라짐).
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

HALF_W, LEN = 4.9, 7.0
PAGES = 9
PALE = (232, 236, 246)
PALE_EDGE = (255, 255, 255)


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def _big(face, n=64):
    return np.array(Image.fromarray(faces.far(face)).resize((n, n), Image.NEAREST))


def page_tex(face, stain, seed):
    """책장 한 장: 누렇게 바랜 종이 테두리 안에 늘인 얼굴. stain 이면 아래쪽에 사람 윤곽 얼룩(머리와 어깨)"""
    n = 64
    f = _big(face, n).astype(np.float32)
    paper = np.array([226, 212, 186], np.float32)
    out = np.zeros((n, n, 4), np.uint8)
    rng = np.random.RandomState(seed)
    for y in range(n):
        for x in range(n):
            edge = x < 3 or x >= n - 3 or y < 3 or y >= n - 3
            if edge:
                col = paper * (0.82 + 0.1 * rng.rand())
            else:
                col = f[y, x, :3] * 0.85 + paper * 0.15
            if stain:
                # 사람 윤곽(가운데 아래): 머리 원 + 어깨 반원, 짙은 갈색 얼룩(점묘)
                hx, hy = (x - 32) / 7.0, (y - 40) / 7.0
                sx, sy = (x - 32) / 15.0, (y - 58) / 9.0
                inside = hx * hx + hy * hy < 1 or (sx * sx + sy * sy < 1 and y < 60)
                if inside and _bayer()[y % 4, x % 4] < 0.62:
                    col = np.array([92, 58, 44], np.float32)
            out[y, x, :3] = np.clip(col, 0, 255)
            out[y, x, 3] = 255
    return out


def cover_tex(seed):
    """표지(겉): 거무튀튀한 살 가죽에 꿰맨 자국"""
    rng = np.random.RandomState(seed)
    out = np.zeros((32, 32, 4), np.uint8)
    for y in range(32):
        for x in range(32):
            v = 0.85 + 0.1 * math.sin(x * 0.5 + y * 0.2) + rng.rand() * 0.05
            c = (118 * v, 70 * v, 74 * v)
            if (x == 4 or x == 27) and y % 4 < 2:
                c = (220, 200, 190)
            out[y, x] = (*[int(min(255, a)) for a in c], 255)
    return out


def beam_tex(w=32, h=128):
    """창백한 빛줄기: 세로 빛살(점묘), 가운데 촘촘, 가장자리 하얀 선"""
    im = np.zeros((h, w, 4), np.uint8)
    c = (w - 1) / 2
    rng = np.random.RandomState(5)
    cols = rng.rand(w)
    for y in range(h):
        for x in range(w):
            d = abs(x - c) / (c + 0.5)
            if d > 1:
                continue
            edge = d > 0.86
            dens = 0.55 * (1 - d * d) + 0.08
            streak = ((y // 5 + int(cols[x] * 7)) % 3) != 0
            if edge and (y // 2) % 2 == 0 or (not edge and streak and _bayer()[y % 4, x % 4] < dens):
                im[y, x] = (*(PALE_EDGE if edge else PALE), 255)
    return faces._fill_rest(im)


def ring_tex(dens, n=64):
    """바닥 창백한 원: 또렷한 테 + 안쪽 점묘"""
    im = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r > 1:
                continue
            if r > 0.88:
                im[y, x] = (*PALE_EDGE, 255)
            elif dens > _bayer()[y % 4, x % 4] * (0.7 + 0.6 * r):
                im[y, x] = (*PALE, 255)
    return faces._fill_rest(im)


def page_quad(s, i, tex):
    """i 번째 책장(0 이 가장 아래): 경첩(x=0.3)에서 바깥으로, 아래를 봄. 바깥 가장자리가 조금씩 아래로 처져 부채꼴로 장 수가 보인다"""
    tilt = 3.0 + 2.2 * (PAGES - 1 - i)
    y = 0.08 - 0.035 * (PAGES - 1 - i)
    s.box((0.3, y, -LEN / 2 + 0.25), (HALF_W - 0.15, y, LEN / 2 - 0.25), tex, faces=("down",), uv=[0, 0, 16, 16], rot=(0, 0, -tilt),
          origin=(0.3, y, 0), face=True)


def half_body(s, k, turned):
    cov = k.texture("cover", cover_tex(3))
    s.box((0.3, 0.12, -LEN / 2), (HALF_W + 0.1, 0.32, LEN / 2), cov, face=True)
    for i in range(PAGES):
        if turned:
            tex = k.texture(f"page_read{i % 3}", page_tex(faces.Face(eyes="open", mouth="dot", blush="deep"), True, 10 + i % 3))
            cond = f"on:p{i + 1}"
        else:
            tex = k.texture(f"page{i % 3}", page_tex(("smile", "smile_a", "smile_c")[i % 3], False, i % 3))
            cond = f"!on:p{i + 1}"
        with s.when(cond):
            page_quad(s, i if not turned else PAGES - 1 - i, tex)


def spine_body(s, k, reading):
    for x in (-0.24, 0.24):
        s.rod((x, 0.42, -LEN / 2 - 0.3), (0, 0, 1), length=LEN + 0.6, segs=2, thick=0.36, base=False, toward=(0, 1, 0), rag=False)
    ring = k.texture("band", cover_tex(9))
    for z in (-2.4, 0, 2.4):
        s.box((-0.6, 0.18, z - 0.2), (0.6, 0.7, z + 0.2), ring, face=True)
    # 책등 얼굴: 아래를 봄(이마 +Z)
    s.head(size=1.5, depth=0.7, at=(0, -0.25, 0), face="open" if reading else "smile", rot=(90, 0, 0), near=False, open=False, torn=False)
    # 책갈피: 책장 사이에서 아래로 길게(읽는 줄을 짚음), 끝에 작은 술
    with s.when("!on:mark_cut"):
        s.rod((0.05, -0.1, 2.2), (0.06, -1, -0.05), length=5.0, segs=2, thick=0.09, base=False, toward=(0, 0, 1), rag=False)
        s.box((-0.12, -5.25, 1.75), (0.28, -4.85, 2.15), "flesh", face=True)
    with s.when("on:mark_cut"):
        s.rod((0.05, -0.1, 2.2), (0.3, -1, 0.2), length=1.1, segs=1, thick=0.09, base=False, toward=(0, 0, 1), rag=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.28, mut2=False, open=False, torn=False, rag=False)
    for name, reading in (("", False), ("read", True)):
        st = m.state(name)
        st.front = (0, -1, 0)
        spine_body(st, k, reading)
    for name, turned in (("g184_r", False), ("g184_l", True)):
        hm = k.model(name, poses=1, period=20, shrink=0.28, mut2=False, open=False, torn=False, rag=False)
        st = hm.state("")
        st.front = (0, -1, 0)
        half_body(st, k, turned)
    pg = k.model("g184_page", poses=1, period=20, shrink=0.28, mut2=False, open=False, torn=False, rag=False)
    st = pg.state("")
    st.front = (0, -1, 0)
    pt = k.texture("page_turn", page_tex(faces.Face(eyes="half", mouth="dot"), False, 21))
    st.box((0.3, 0, -LEN / 2 + 0.25), (HALF_W - 0.15, 0, LEN / 2 - 0.25), {"down": pt, "up": k.texture("page_back", cover_tex(12))},
           faces=("down", "up"), uv=[0, 0, 16, 16], face=True)
    bm = k.model("g184_beam", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    bt = k.texture("beam", beam_tex())
    bs = bm.state("")
    bs.front = (0, 0, 0)
    for ang in (0, 45, 90, 135):
        bs.box((-0.5, -1, 0), (0.5, 0, 0), bt, uv=[0, 0, 16, 16], faces=("south", "north"), rot=(0, ang, 0), origin=(0, 0, 0), shade=False)
    rm = k.model("g184_ring", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for name, dens in (("", 0.12), ("on", 0.4)):
        st = rm.state(name)
        st.front = (0, 0, 0)
        st.quad((0, 0, 0), (2, 2), "up", k.texture("ring_" + (name or "warn"), ring_tex(dens)), face=False)
    k.icon(img=Image.fromarray(page_tex("smile", False, 0)).resize((64, 64), Image.NEAREST))


def sounds(k: kit.Kit):
    k.sample("page", "tag:book", "책장 넘기는 소리", pitch=0.6, attenuation=64)
    k.sample("slam", ["kenney_rpg/bookclose", "oga_metalwood100/wood_slam_02"], "책이 탁 덮인다", pitch=0.45, attenuation=96)
    k.sample("call", "tag:whisper", "되살아나는 속삭임", pitch=0.7, attenuation=32, n=2)
    k.sample("snap", "tag:breaking", "책갈피가 꺾인다", pitch=0.9, attenuation=48)


def phrases():
    return {"cause_g184": "펼친 책의 빛에 타 버렸다", "cause_g184_wind": "덮이는 책 바람에 날아갔다"}
