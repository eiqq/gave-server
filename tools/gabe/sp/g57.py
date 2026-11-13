"""G57 종종걸음 게이브: 0.36 블록 얼굴 아래 얼굴보다 조금 넓은 살 엉덩이, 엔드 막대기 다리 둘이 죽마처럼 곧게 달렸다(무릎 없음).
20~40마리가 흩어져 온다. 셀 것(사체, 보급 상자)을 보면 멈춰 고개를 끄덕이며 센다(끄덕임은 Java 의 pitch).

모델 gave:gabe/g57: 상태 ""(걷기: 다리 둘이 번갈아 콕콕 4자세), "count"(세기: 다리를 모으고 섬, 반쯤 뜬 눈).
"""
from gabe import kit

HEAD, DEPTH = 0.36, 0.3
HEAD_Y = 0.16
HIP = ((-0.22, -0.16, -0.15), (0.22, -0.02, 0.15))
LEG = dict(length=0.46, segs=1, thick=0.08)


def body(s, walking):
    h = s.head(size=HEAD, depth=DEPTH, at=(0, HEAD_Y, 0), face="smile" if walking else "half")
    s.box(HIP[0], HIP[1], "flesh", face=True)
    for side in (1, -1):
        root = (side * 0.11, -0.15, 0)
        if walking:
            # 죽마 걸음: 곧은 다리가 앞뒤로 번갈아 찍음
            w = kit.Wave(amp=24, lag=0, bias=0, phase=0 if side > 0 else 180)
            s.rod(root, (0, -1, 0), wave=w, toward=(0, 0, 1), **LEG)
        else:
            s.rod(root, (side * 0.06, -1, 0), **LEG)
    return h


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    body(m.state(""), True)
    body(m.state("count"), False)


def sounds(k: kit.Kit):
    # 잔걸음: 마른 땅을 콕콕(무리 가운데서 드문드문)
    k.sample("step", "kenney_impact/footstep_concrete_00*", "콕콕", pitch=1.7, volume=0.6, attenuation=16)
    # 세는 중: 작은 딸깍 딸깍
    k.sample("count", "kenney_interface/click_00*", "딸깍 딸깍", pitch=0.8, volume=0.7, attenuation=12)
    # 정강이 찍기
    k.sample("peck", "kenney_rpg/chop", "콕", pitch=1.5, volume=0.8, attenuation=12, n=1)


def phrases():
    return {"cause_g57": "종종걸음에 정강이를 찍혔다"}
