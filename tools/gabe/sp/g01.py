"""G01 가오리 게이브(본보기 종): 0.8 블록 얼굴, 뒤통수 아래 양옆에서 세 마디 엔드 막대기 둘이 가오리 지느러미처럼 1.2 블록씩,
얼굴 아래 가장자리는 젖은 천처럼 늘어져 날갯짓 뒤에 따라 출렁인다.

상태: ""(기본 펄럭임, 20틱 한 바퀴), "charge"(돌진: 10틱 한 바퀴(20틱에 두 번), 폭 넓게). 부위: "cut_l"(게이브 왼쪽 = +X 막대기 끊김), "cut_r".
끊긴 쪽은 짧은 그루터기(자세 없음: 틴트 칸을 아낌)만 남는다. 실눈 뜸, 입 찢어짐(변이 1), 막대기 둘 더(변이 2)는 키트가 저절로 붙임.
"""
from gabe import audio, kit

FIN = dict(length=1.2, segs=3, thick=0.11)


def body(s, fin, drape):
    """한 상태의 몸: 얼굴, 지느러미 둘(끊기면 그루터기), 젖은 천 가장자리"""
    h = s.head(size=0.8, depth=0.55, face="smile")
    for side, part in ((1, "cut_l"), (-1, "cut_r")):
        root = h.point(side * 0.92, -0.5, -0.55)
        d = (side * 1.0, -0.06, -0.42)
        with s.when("!on:" + part):
            s.rod(root, d, wave=fin, **FIN)
        with s.when("on:" + part):
            s.rod(root, (side * 1.0, -0.55, -0.3), length=0.3, segs=1, thick=0.11)
    # 아래 가장자리: 앞면 아래 모서리에서 아래로, 앞뒤로 출렁(날갯짓보다 늦게)
    s.band(h.point(0, -1, 0.92), (0, -1, -0.12), length=0.3, width=0.74, segs=3, thick=0.025, wave=drape, toward=(0, 0, 1),
           tex="wetskin")


def build(k: kit.Kit):
    m = k.model(poses=8, period=20)
    body(m.state(""), kit.Wave(amp=(20, 18, 16), lag=50, bias=(-6, 2, 5), shape="flap"),
         kit.Wave(amp=(8, 10, 12), lag=40, bias=(4, 6, 6), phase=-100))
    # 돌진: 같은 20틱 주기 안에서 두 번 침(cycles=2, 10틱 한 바퀴). 주기가 같아 두 상태가 자세 칸을 나눠 쓰고(칸 40),
    # 상태 이름을 모르는 Look.colors 로 실어도 빠르기가 맞다
    body(m.state("charge"), kit.Wave(amp=(30, 26, 22), lag=55, bias=(-4, 2, 4), shape="flap", cycles=2),
         kit.Wave(amp=(12, 14, 16), lag=40, bias=(6, 8, 8), phase=-100, cycles=2))


def sounds(k: kit.Kit):
    # 퍼덕: 젖은 천 터는 소리(바닐라 팬텀 날갯짓을 낮게 + 합성 젖은 퍼덕), 돌진 직전에는 Java 가 간격을 좁혀 자주 울림
    k.sound("flap", "퍼덕", vanilla=[("mob/phantom/flap1", 0.62), ("mob/phantom/flap2", 0.6), ("mob/phantom/flap3", 0.66)],
            synth=lambda i: audio.wet_flap(seed=10 + i), volume=0.9, attenuation=20)
    # 박치기: 젤리처럼 찌그러지는 철썩
    k.sound("bump", "철썩", vanilla=[("mob/slime/big1", 0.8), ("mob/slime/big3", 0.75)], synth=lambda i: audio.squish(seed=20 + i),
            attenuation=16)
    # 막대기 끊김: 마른 가지 꺾이듯 뚝
    k.sound("cut", "뚝", vanilla=[("random/break", 0.55), ("random/break", 0.65)], volume=0.8, attenuation=16)


def phrases():
    return {"cause_g01": "가오리에 박혔다"}
