"""G117 등 맞댄 게이브: 사람 키만 한 인형 둘이 등을 맞대고 붙어 있다(각자 0.9 블록 얼굴, 살 몸통, 막대기 팔 하나, 막대기 다리 둘).
한쪽 얼굴은 바로, 다른 쪽은 좌우를 뒤집은 거울 꼴. 붙어 있을 때는 엔드 막대기 둘이 두 등을 꼬치처럼 꿰뚫어 잇는다.

- gave:gabe/g117: 인형 하나(앞 +Z, 등은 z=0 평면). 상태 "l"(바로, 팔은 +X, 꼬치 막대기 둘을 그림: 맞은편 인형까지 꿰뚫음),
  "r"(거울: 얼굴 좌우 뒤집음, 팔은 -X, 꼬치 없음). 부위 split(떨어짐: l 의 꼬치를 숨김). 엔진 Body.carrier 에 부품 둘로 싣는다.
- gave:gabe/g117_rod(부품): 떨어졌거나 되살릴 때 두 인형을 잇는 막대기(+Z 로 1 블록, Java 가 길이만큼 늘임).
"""
import numpy as np

from gabe import faces, kit

HEAD_Y, HEAD = 0.8, 0.9


def doll(s, k, mirror):
    s.head(size=HEAD, depth=0.5, at=(0, HEAD_Y, 0.26), face="smile")
    if mirror:
        # 거울 꼴: 앞면 위에 좌우를 뒤집은 얼굴을 한 겹 더(먼 32, 가까운 128)
        f = faces.preset("smile")
        far = k.texture("mirror", np.ascontiguousarray(np.fliplr(faces.far(f))), classes=True)
        near = k.texture("mirror_near", np.ascontiguousarray(np.fliplr(faces.near(f))), classes=True)
        z = 0.26 + 0.25
        ep = 1.0 / 16
        s.quad((0, HEAD_Y, z + 0.05 * ep), (HEAD, HEAD), "south", far, dist="far", face=True)
        s.quad((0, HEAD_Y, z + 0.1 * ep), (HEAD, HEAD), "south", near, dist="near", face=True)
    s.box((-0.46, -0.42, 0.02), (0.46, 0.4, 0.48), "flesh", uv=[0, 0, 16, 16], face=True)
    side = -1 if mirror else 1
    # 막대기 팔 하나(바깥쪽), 다리 둘
    s.rod((side * 0.48, 0.26, 0.26), (side * 0.45, -1.0, 0.35), length=0.95, segs=1, thick=0.11, base=True)
    for x in (0.18, -0.18):
        s.rod((x, -0.4, 0.24), (x * 0.3, -1.0, 0.0), length=0.84, segs=1, thick=0.13, base=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, mut2=False)
    m.default = "l"
    l = m.state("l")
    doll(l, k, False)
    with l.when("!on:split"):
        # 짝은 옆으로 0.56 어긋나 붙는다(Java JOIN_SIDE x2): 꼬치는 두 몸통이 겹치는 쪽(-X)으로
        for x in (-0.18, -0.38):
            l.rod((x, 0.12, -0.62), (0, 0, 1), length=1.24, segs=1, thick=0.09, base=False)
    r = m.state("r")
    doll(r, k, True)

    rod = k.model("g117_rod", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    rs = rod.state("")
    rs.rod((0, 0, 0), (0, 0, 1), length=1.0, segs=1, thick=0.1, base=False)
    rs.front = (0, 0, 0)
    k.icon(model="g117", state="l")


def sounds(k: kit.Kit):
    k.sample("clap", "tag:slam", "손뼉", pitch=1.4, attenuation=20)
    k.sample("creak", "tag:creak", "막대기 끼익", pitch=0.9, attenuation=24)
    k.sample("snap", "tag:wood+breaking", "막대기 끊김", pitch=1.1, attenuation=20)
    k.sample("strike", "tag:punch", "내려침", pitch=0.9, attenuation=16)


def phrases():
    return {"cause_g117": "등 맞댄 짝 사이에 끼었다"}
