"""G158 문턱 게이브: 무릎 높이 _Gave 얼굴 아래 얼굴보다 넓은 살 몸통, 가는 엔드 막대기 다리 둘로 까치발을 섰고, 몸통 앞에서 짧은 막대기
둘을 두 손처럼 모아 앞으로 내밀어 문을 두드린다. 모두 똑같이 생겼고 모두 실눈으로 웃는다.

모델 gave:gabe/g158: 상태 ""(종종걸음 2자세), "knock"(테두리 앞에 서서 허공을 두드림 2자세), "in"(문턱을 넘어 들어옴: 눈 뜸, 빠른 걸음).
몸 가운데 = 몸통 가운데. 바닥 = -0.48(Java hover 0.48).
"""
from gabe import faces, kit

IN = faces.Face(eyes="open", blush="deep")


def body(s, face, legs, hands):
    s.head(size=0.46, depth=0.38, at=(0, 0.2, 0.0), face=face)
    # 얼굴보다 넓은 몸통(살): 멀리서 줄지은 어깨가 보이게
    s.box((-0.31, -0.2, -0.2), (0.31, -0.01, 0.2), "flesh", face=True)
    for side in (1, -1):
        s.rod((side * 0.12, -0.18, 0.0), (0, -1, 0.04), length=0.3, segs=1, thick=0.07, toward=(0, 0, 1), rag=False,
              wave=kit.Wave(amp=legs, lag=0, phase=0 if side > 0 else 180), poses=2)
        s.rod((side * 0.1, -0.07, 0.18), (-side * 0.25, 0.35, 1), length=0.3, segs=1, thick=0.06, toward=(0, 1, 0), rag=False,
              wave=None if hands is None else kit.Wave(amp=hands, lag=0, bias=-hands, phase=0 if side > 0 else 180, shape="flap"),
              poses=2)


def build(k: kit.Kit):
    m = k.model(poses=2, period=8, mut2=False, torn=False)
    body(m.state(""), "smile", 26, None)
    body(m.state("knock", period=10), "smile", 0.001, 26)
    body(m.state("in", period=6), IN, 32, None)


def sounds(k: kit.Kit):
    # 이 종의 신호: 모인 수만큼 겹쳐 커지는 '똑똑'
    k.sample("knock", "tag:wood", "똑똑 두드림", pitch=1.25, volume=0.8, attenuation=24)
    # 누가 선을 넘어 틈이 벌어짐
    k.sample("gap", "tag:creak", "문턱이 열림", pitch=0.9, attenuation=32)
    # 정강이 물기, 막대기 찌르기
    k.sample("bite", "oga_creature80/eat_0*", "물기", pitch=1.4, volume=0.8, attenuation=12)


def phrases():
    return {"cause_g158": "문턱 떼에 물렸다"}
