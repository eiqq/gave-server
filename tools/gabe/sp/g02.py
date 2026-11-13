"""G02 새끼 가오리 떼(본보기 떼 종): 0.35 블록 얼굴에 짧은 막대기 꼬리 하나. 마리마다 디스플레이 하나.

상태: "a", "b", "c"(실눈 각도가 조금씩 다른 거의 같은 얼굴 셋, 기본은 "a"), "lod"(15블록 밖 무리 덩어리 하나, 작은 얼굴 16개, 출렁임 자세 4).
떼 마리는 오래 살면 변함 규칙에서 빠지므로 입 찢어짐, 변이 2 막대기는 끄고 실눈 뜸만 둔다.
"""
from gabe import audio, kit

TAIL = kit.Wave(amp=(26, 34), lag=70)


def build(k: kit.Kit):
    m = k.model(poses=6, period=12, mut2=False, torn=False)
    for name, face in (("a", "smile_a"), ("b", "smile"), ("c", "smile_c")):
        s = m.state(name)
        h = s.head(size=0.35, depth=0.22, face=face)
        # 꼬리: 뒤통수 가운데 조금 아래에서 뒤로, 좌우로 흔듦(toward = +X)
        s.rod(h.point(0, -0.3, -1), (0, -0.15, -1), length=0.42, segs=2, thick=0.06, wave=TAIL, toward=(1, 0, 0))
    m.default = "a"
    lod = m.state("lod")
    lod.lump(count=16, radius=(1.1, 0.45, 1.1), size=0.3, faces_list=["smile_a", "smile", "smile_c"], wobble=0.06, seed=2)


def sounds(k: kit.Kit):
    # 킥킥 파도: 무리 중심 한 곳에서, 같은 박자로 겹쳐 기계처럼(합성, 권리 깨끗). 떼 종이라 웃음 허용
    k.sound("giggle", "킥킥", synth=lambda i: audio.giggle(seed=30 + i, count=6 + i, base=540 + 25 * i), volume=0.8, attenuation=24)
    # 파닥: 작은 날갯짓(마리 하나)
    k.sound("flutter", "파닥", vanilla=[("mob/phantom/flap4", 1.7), ("mob/phantom/flap5", 1.8)], volume=0.35, attenuation=16)


def phrases():
    return {"cause_g02": "새끼 떼에 뒤덮였다"}
