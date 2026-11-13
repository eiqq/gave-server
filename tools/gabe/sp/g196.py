"""G196 하늘 아치 게이브(4막 34라운드, 지원, 원거리, 40블록 넘음): 지평선에서 지평선까지 하늘에 걸린 무지개 꼴 몸. 지름 2.5블록 얼굴 마디가 끝없이
이어져 가운데가 트럭 위 22블록을 지나고 양 끝은 흙먼지 속으로 사라진다. 마디 배 쪽에 엔드 막대기 장대가 하나씩 접혀 붙어 있다가 땅까지 곧게
내려온다. 마디 얼굴들은 실눈으로 지나가다 장대를 내리는 토막만 눈을 뜬다(키트 실눈 뜸 오버레이: Java 가 토막 부품 look.open).

모델(블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. Java 는 Body.carrier + 부품, 토막 여섯을 원호를 따라 Part.local 로):
  gave:gabe/g196_seg   아치 토막 하나: 얼굴 마디 여섯(아래 -Y 를 봄, X 축을 따라 2.9 간격, 길이 17.4) + 마디 사이 살 + 배에 접힌 장대.
                       부위 b0..b5(그 마디가 깨짐: 핏발 선 찢긴 얼굴로 바뀜)
  gave:gabe/g196_pole  장대(위 0 에서 아래 -3, Java 가 y 만 늘임) + 끝 뼈 발
  gave:gabe/g196_band  바닥 그늘(위를 보는 판 2x2). 상태 "" 아치 그늘 띠(Java 가 폭 6, 길이 90), "dot" 장대가 꽂힐 그림자 점
소리는 받은 소리(k.sample). 사체 없음(끊어져 걷혀 올라감).
"""
import math

import numpy as np

from gabe import faces, kit

FACE, GAP = 2.5, 2.9
XS = [(-2.5 + i) * GAP for i in range(6)]


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def band_tex(kind, n=64):
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            if kind == "dot":
                r = math.hypot(x - c, y - c) / (n / 2)
                if r < 1 and 0.85 * min(1, (1 - r) / 0.3) > _bayer()[y % 4, x % 4]:
                    out[y, x] = (16, 8, 12, 255)
            else:
                d = abs(x - c) / (n / 2)
                if 0.5 * min(1, (1 - d) / 0.3) > _bayer()[y % 4, x % 4]:
                    out[y, x] = (14, 10, 18, 255)
    return faces._fill_rest(out)


def seg(s):
    torn = faces.Face(eyes="bloodshot", mouth="torn", blush="deep", extras=("veins",))
    for j, x in enumerate(XS):
        face = ("smile", "smile_a", "half", "smile_c", "smile", "half")[j]
        s.head(size=FACE, depth=1.1, at=(x, 0, 0), face=face, rot=(90, 0, 0), near=False, torn=False, show=(f"!on:b{j}",))
        s.head(size=FACE, depth=1.1, at=(x, 0, 0), face=torn, rot=(90, 0, 0), near=False, open=False, torn=False, show=(f"on:b{j}",))
        # 배에 접힌 장대(X 축을 따라 눕힘)
        s.rod((x - 1.0, -0.75, 0.55), (1, 0, 0), length=2.0, segs=1, thick=0.2, base=False, toward=(0, 1, 0), rag=False)
    # 마디 사이 살(등이 이어져 보이게)
    for j in range(5):
        x = (XS[j] + XS[j + 1]) / 2
        s.box((x - 0.5, -0.35, -1.0), (x + 0.5, 0.45, 1.0), "flesh", face=True)
    # 양 끝 이음(옆 토막과 겹침)
    for x in (XS[0] - 1.6, XS[-1] + 1.6):
        s.box((x - 0.35, -0.35, -0.9), (x + 0.35, 0.4, 0.9), "flesh", face=True)


def build(k: kit.Kit):
    m = k.model("g196_seg", poses=1, period=20, shrink=0.16, mut2=False, torn=False, rag=False)
    st = m.state("")
    st.front = (0, -1, 0)
    seg(st)
    pm = k.model("g196_pole", poses=1, period=20, shrink=0.45, mut2=False, open=False, torn=False, rag=False)
    st = pm.state("")
    st.front = (0, 0, 0)
    st.rod((0, 0, 0), (0, -1, 0), length=3.0, segs=2, thick=0.4, base=False, toward=(0, 0, 1), rag=False)
    st.box((-0.35, -3.25, -0.35), (0.35, -2.9, 0.35), "bone")
    bm = k.model("g196_band", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for name, kind in (("", "band"), ("dot", "dot")):
        st = bm.state(name)
        st.front = (0, 0, 0)
        st.quad((0, 0, 0), (2, 2), "up", k.texture("band_" + kind, band_tex(kind)))
    k.icon(model="g196_seg", state="", view="top")


def sounds(k: kit.Kit):
    k.sample("moan", "tag:far_roar", "하늘 가로지르는 울음", pitch=0.55, attenuation=96)
    k.sample("slam", ["oga_stomp/stomp", "oga_creature80b/stomp_01"], "장대가 꽂힌다", pitch=0.7, attenuation=64)
    k.sample("snap", "tag:breaking", "장대가 꺾인다", pitch=0.6, attenuation=64)
    k.sample("drip", "tag:drip", "침이 떨어진다", pitch=0.7, attenuation=24)


def phrases():
    return {"cause_g196": "하늘 아치의 장대에 꽂혔다", "cause_g196_spit": "하늘 아치가 떨군 침"}
