"""G73 왕관 쓴 떼 게이브: 0.46 블록 얼굴 아래 얼굴보다 넓은 살 배, 짧은 엔드 막대기 셋을 세발 받침처럼 딛고 뒤뚱대는 작은 떼.
그 가운데 하나만 머리 위에 짧은 엔드 막대기 다섯을 둥글게 세운 작은 왕관을 쓰고 실눈을 반쯤 떴다(무리 묶기는 Java).

모델 gave:gabe/g73: 상태 "a", "b"(실눈 기울기 다른 마리 둘, 기본 "a"), "king"(왕관, 반쯤 뜬 금빛 홍조 눈). 세 다리 뒤뚱 4자세.
"""
import math

from gabe import faces, kit

HEAD, DEPTH = 0.46, 0.38
HEAD_Y = 0.16
BELLY = ((-0.28, -0.2, -0.21), (0.28, -0.05, 0.19))
LEG = dict(length=0.4, segs=1, thick=0.08)
KING = faces.Face(eyes="half", blush="gold")


def body(s, face, king):
    h = s.head(size=HEAD, depth=DEPTH, at=(0, HEAD_Y, 0), face=face)
    s.box(BELLY[0], BELLY[1], "flesh", face=True)
    # 세발 받침: 앞 왼, 앞 오른, 뒤. 번갈아 들썩이며 뒤뚱
    for i, (root, d) in enumerate((((0.17, -0.18, 0.1), (0.4, -1, 0.3)), ((-0.17, -0.18, 0.1), (-0.4, -1, 0.3)),
                                   ((0.0, -0.18, -0.14), (0, -1, -0.45)))):
        w = kit.Wave(amp=18, lag=0, bias=0, phase=i * 120)
        s.rod(root, d, wave=w, toward=(0, 0, 1), poses=4, **LEG)
    if king:
        top = HEAD_Y + HEAD / 2
        for j in range(5):
            a = 2 * math.pi * j / 5 + math.pi / 2
            x, z = math.cos(a) * 0.15, math.sin(a) * 0.13
            s.rod((x, top - 0.02, z), (x * 0.9, 1, z * 0.9), length=0.22, segs=1, thick=0.06, base=False, rag=False)
    return h


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, mut2=False, torn=False)
    body(m.state("a"), "smile_a", False)
    body(m.state("b"), "smile_c", False)
    body(m.state("king"), KING, True)
    m.default = "a"
    k.icon(model="g73", state="king")


def sounds(k: kit.Kit):
    # 왕관이 다른 머리로 옮겨 감(이 종 전용 신호)
    k.sample("crown", "kenney_impact/impactglass_light_00*", "왕관 옮김", pitch=1.35, attenuation=24)
    # 왕이 쓰러진 마리를 다시 뱉어 냄
    k.sample("gulp", "oga_creature80/eat_0*", "꿀꺽", pitch=0.8, attenuation=20)
    # 마리 물기
    k.sample("bite", "oga_creature80/eat_0*", "물기", pitch=1.4, volume=0.7, attenuation=12)


def phrases():
    return {"cause_g73": "왕관 쓴 떼에 둘러싸였다"}
