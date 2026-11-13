"""G110 긁는 게이브: 0.7 블록 얼굴이 하늘을 보고 바닥에 납작 누웠다(두께 0.24). 몸은 없다. 이마 쪽(앞, +Z) 두 귀퉁이에서
엔드 막대기 둘이 팔꿈치처럼 꺾여 앞 땅을 찍고, 턱 쪽(뒤)으로 찢긴 살 띠가 짧게 끌린다.

모델 gave:gabe/g110: 상태 ""(찍고 당김 4자세: 막대기 둘이 앞을 찍었다 뒤로 젖혀짐, 8틱 한 바퀴). 표적 발밑에 닿으면 눈을 뜬다(look.open).
"""
from gabe import kit

HEAD, DEPTH = 0.7, 0.24
ARM = dict(length=0.86, segs=2, thick=0.1)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    s = m.state("")
    # 얼굴이 하늘(+Y), 이마가 앞(+Z): x 로 -90 눕힌 뒤 y 로 반 바퀴
    s.head(size=HEAD, depth=DEPTH, at=(0, 0, 0), face="smile", rot=(-90, 180, 0))
    top = DEPTH / 2
    for side in (1, -1):
        root = (side * 0.24, top - 0.04, HEAD / 2 - 0.06)
        # 첫 마디는 위로 꺾여 들리고 둘째 마디가 앞 땅으로 내리찍음(팔꿈치), 자세마다 앞을 찍었다 뒤로 젖혀짐
        w = kit.Wave(amp=(22, 26), lag=50, bias=(42, -104), phase=0 if side > 0 else 30)
        s.rod(root, (side * 0.18, 0, 1), wave=w, toward=(0, 1, 0), **ARM)
    # 턱 쪽으로 끌리는 찢긴 살 띠
    s.band((0, -0.02, -HEAD / 2 + 0.02), (0, -0.1, -1), length=0.38, width=0.42, segs=2, thick=0.03,
           wave=kit.Wave(amp=(8, 12), lag=40, bias=(-4, -6)), toward=(0, 1, 0), tex="flesh")


def sounds(k: kit.Kit):
    # 마른 땅을 긁는 득, 득(가까울수록 자주: Java)
    k.sample("scrape", "kenney_interface/scratch_00*", "긁는 소리", pitch=0.62, volume=0.9, attenuation=28)
    # 발목 베기
    k.sample("slash", "lrsf_horror/stab_knife_0*", "베는 소리", pitch=1.1, attenuation=16)


def phrases():
    return {"cause_g110": "발밑에서 발목을 베였다"}
