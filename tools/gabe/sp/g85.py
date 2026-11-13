"""G85 땅 금 입 게이브: 땅 위로는 앞장서는 0.8 블록 쐐기 얼굴 하나(이마를 앞으로 내밀어 숙인 채 땅에 반쯤 묻힘, 엔드 막대기 둘이 양옆 뒤로
비스듬히 땅에 박혀 쐐기 날개처럼 흙을 가름, 얼굴 앞에 갈라져 솟은 흙덩이)만 보인다. 그 뒤로 길이 8 블록, 폭 1.5 블록의 갈라진 금이 따라오고
금의 양 벽에 _Gave 얼굴들이 이빨처럼 줄지어 박혀 입을 벌렸다 다문다.

- gave:gabe/g85: 쐐기 얼굴. 상태 ""(흙을 가름: 날개 막대기가 떨림, 자세 4). Java hover 0.15 면 얼굴 아래 절반이 땅속.
- gave:gabe/g85_crack(부품, shrink 0.2, 세계 축): 금 띠. 모델 가운데가 금 가운데, +Z 가 쐐기 쪽(앞), 길이 8(-4..4). 상태 ""(벌어진 금: 양 벽의
  얼굴들이 금을 따라 물결처럼 입을 벌렸다 다묾, 자세 4), "shut"(맞물림: 양 벽 얼굴이 솟아 가운데서 맞닿음). Java 가 길이 쪽(+Z)으로 줄인다.
소리 crack(땅이 쩍, 32), crunch(맞물림, 24), ram(들이받기, 16). 글귀 cause_g85(금이 닫힘), cause_g85_ram.
"""
import math

import numpy as np

from gabe import faces, kit

CRACK_L = 8.0
GAP = 0.36       # 가운데 캄캄한 틈 반 폭
RIM = 0.75       # 금 바깥 가장자리 반 폭(폭 1.5)
TEETH = 10       # 한 벽의 얼굴 수
FACE = 0.62


def soil_tex(seed=85, n=16):
    rng = np.random.RandomState(seed)
    base = np.array([120, 92, 66], float)
    col = base[None, None, :] * (0.82 + 0.3 * rng.rand(n, n, 1))
    col[rng.rand(n, n) < 0.12] = (84, 62, 44)
    col[rng.rand(n, n) < 0.06] = (156, 128, 96)
    a = np.zeros((n, n, 4), np.uint8)
    a[..., :3] = np.clip(np.round(col), 0, 255)
    a[..., 3] = 255
    return a


def gap_tex(n=16):
    """캄캄한 틈: 가운데로 갈수록 검고 가장자리에 붉은 살 기운"""
    a = np.zeros((n, n, 4), np.uint8)
    for x in range(n):
        d = abs(x - (n - 1) / 2) / (n / 2)
        a[:, x, :3] = (int(18 + 70 * d ** 2), int(8 + 18 * d ** 2), int(10 + 20 * d ** 2))
    a[..., 3] = 255
    return a


def build(k: kit.Kit):
    soil = k.texture("soil", soil_tex())
    # 쐐기 얼굴: 이마를 앞으로 숙여(머리 x 회전) 흙을 가름
    m = k.model(poses=4, period=8, mut2=dict(root=lambda side: (side * 0.3, 0.3, -0.25), dir=(0, 1, -0.6), length=0.4, segs=1, poses=2))
    s = m.state("")
    h = s.head(size=0.8, depth=0.6, face="smile", rot=(20, 0, 0))
    for side in (1, -1):
        # 쐐기 날개: 얼굴 옆에서 뒤, 아래로 비스듬히 땅에 박힘(흙을 가르며 떨림)
        s.rod((side * 0.42, 0.0, -0.1), (side * 0.75, -0.55, -0.8), length=1.25, segs=2, thick=0.12,
              wave=kit.Wave(amp=(5, 4), lag=40, phase=90 * side, cycles=2), toward=(side, 0, 0), poses=4)
    # 얼굴 앞에 갈라져 솟은 흙덩이와 양옆으로 밀려난 흙 두둑
    s.box((-0.5, -0.46, 0.34), (0.5, -0.22, 0.66), soil, rot=(-20, 0, 0))
    for side in (1, -1):
        s.box((side * 0.45 - 0.2, -0.3, -0.9), (side * 0.45 + 0.2, -0.05, 0.25), soil, rot=(0, side * 14, side * 18))
    s.front = (0, 0.55, 0.85)

    # 금 띠 부품
    c = k.model("g85_crack", poses=4, period=12, shrink=0.2, mut2=False, open=False, torn=False, rag=False)
    gap = k.texture("gap", gap_tex())
    bite = k.face_tex(faces.preset("teeth_ring"))
    smile = k.face_tex(faces.preset("smile"))

    def base(st):
        st.box((-GAP, -0.02, -CRACK_L / 2), (GAP, -0.02, CRACK_L / 2), gap, uv=[0, 0, 16, 16], faces=("up",))
        for side in (1, -1):
            # 갈라져 들린 가장자리 흙(바깥으로 갈수록 낮게)
            x0, x1 = sorted((side * GAP, side * RIM))
            st.box((x0, -0.08, -CRACK_L / 2), (x1, 0.06, CRACK_L / 2), soil, uv=[0, 0, 16, 16],
                   rot=(0, 0, -side * 9), origin=(side * GAP, 0.06, 0))
        st.front = (0, 1, 0)

    def tooth(st, side, i, tilt, lift, tex):
        z = -CRACK_L / 2 + (i + 0.5) * CRACK_L / TEETH
        x = side * (GAP + 0.08)
        # 위를 보는 얼굴 판을 금 쪽으로 기울임(tilt 가 클수록 서서 가운데로 다묾)
        st.quad((x + side * FACE / 2 * math.cos(math.radians(tilt)), 0.04 + lift + FACE / 2 * math.sin(math.radians(tilt)), z), (FACE, FACE),
                "up", tex, rot=(0, 0, side * tilt), face=True)

    s0 = c.state("")
    base(s0)
    for f in s0.frames():
        with f:
            for side in (1, -1):
                for i in range(TEETH):
                    # 금을 따라 앞(+Z)에서 뒤로 지나가는 물결: 얼굴이 솟으며 금 쪽으로 서다(입 벌림) 눕다
                    w = 0.5 + 0.5 * math.sin(2 * math.pi * f.t - i * 0.85 + (0.6 if side > 0 else 0))
                    tooth(s0, side, i, 18 + 34 * w, 0.06 * w, bite if w > 0.55 else smile)
    s1 = c.state("shut")
    base(s1)
    for side in (1, -1):
        for i in range(TEETH):
            tooth(s1, side, i, 74, 0.1, bite)
    c.default = ""
    k.icon(model="g85", state="")


def sounds(k: kit.Kit):
    k.sample("crack", ["oga_breaking75/bfh1_rock_breaking_01", "oga_breaking75/bfh1_rock_breaking_02", "lrsf_horror/bonecrack_03"], "땅이 쩍",
             pitch=0.7, attenuation=32)
    k.sample("crunch", "tag:eat", "땅 속 얼굴들이 맞물림", pitch=0.7, attenuation=24)
    k.sample("ram", "oga_breaking75/bfh1_rock_falling_0*", "흙덩이 들이받음", pitch=0.8, attenuation=16)


def phrases():
    return {"cause_g85": "땅 금에 물렸다", "cause_g85_ram": "쐐기 얼굴에 받혔다"}
