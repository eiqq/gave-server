"""G135 일곱 게이브: 2.4 블록 엔드 막대기 장대 하나가 몸이고, 꼭대기 가로대에 0.6 블록 젖은 얼굴이 빨래처럼 축 늘어져 걸렸다.
얼굴 아래로 젖은 천이 길게 늘어져 물이 듣는다. 똑같은 것이 꼭 일곱, 한 줄로 서고 맨 앞 하나만 짧은 지팡이를 짚고 눈을 뜬다.

모델 gave:gabe/g135 상태 ""(하나). 부위 "staff"(맨 앞 개체의 지팡이). 실눈 뜸은 키트 자동(open).
가운데(몸 자리) = 장대 한가운데(Java hover 1.3: 밑동이 땅에 닿음). 장대는 죽으면 흐물거려 사체 몸 상자가 얼굴과 가로대만 남는다.
소리 tap(장대 밑동 '콩', 일곱 번 차례로), slap(젖은 얼굴 후려침), rise(새것이 솟는 꾸르륵).
"""
from gabe import faces, kit

POLE = 2.4
TOP = POLE / 2  # 장대 꼭대기(가운데 기준)
WET = faces.Face(extras=("tears",))


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, mut2=False)
    s = m.state("")
    # 장대(몸): 꼭대기에서 밑동까지 곧게. 구조 막대기(자세 없음), 죽으면 흐물
    s.rod((0, TOP, 0), (0, -1, 0), length=POLE, segs=2, thick=0.13, toward=(0, 0, 1), base=False, rag=True)
    # 가로대(빨랫대): 꼭대기 바로 아래 좌우로
    s.rod((-0.5, TOP - 0.1, 0), (1, 0, 0), length=1.0, segs=1, thick=0.09, base=False, rag=False)
    # 젖은 얼굴: 가로대에 걸려 앞으로 숙여 늘어짐
    s.head(size=0.6, depth=0.16, at=(0, TOP - 0.46, 0.14), face=WET, rot=(16, 0, 0))
    # 얼굴 아래로 늘어진 젖은 천(빨래 자락): 숨 쉬듯 앞뒤로 출렁
    s.band((0, TOP - 0.76, 0.2), (0, -1, 0.04), length=1.0, width=0.56, segs=3, thick=0.025,
           wave=kit.Wave(amp=(5, 7, 9), lag=45, bias=(2, 2, 3)), toward=(0, 0, 1), tex="wetskin")
    # 가로대 양끝에서 듣는 젖은 자락 둘(짧게, 자세 없음)
    for side in (1, -1):
        s.band((side * 0.44, TOP - 0.14, 0.02), (0, -1, 0), length=0.34, width=0.1, segs=1, thick=0.02, toward=(0, 0, 1), tex="wetskin")
    # 맨 앞 개체의 지팡이: 오른쪽 앞에서 땅까지 짚은 짧은 막대기 + 굽은 손잡이
    with s.when("on:staff"):
        s.rod((-0.42, -0.1, 0.34), (0.04, -1, 0.06), length=1.2, segs=1, thick=0.09, toward=(0, 0, 1), base=False, rag=False)
        s.rod((-0.42, -0.1, 0.34), (0.5, 0.3, -0.5), length=0.3, segs=1, thick=0.09, base=False, rag=False)


def sounds(k: kit.Kit):
    k.sample("tap", "kenney_impact/impactwood_heavy_00*", "일곱 번 찍는 소리", pitch=0.72, attenuation=24)
    k.sample("slap", "tag:wet+slime", "젖은 얼굴 철썩", pitch=0.9, attenuation=16)
    k.sample("rise", "tag:wet+slime", "솟는 꾸르륵", pitch=0.6, attenuation=24)


def phrases():
    return {"cause_g135": "일곱에게 맞았다"}
