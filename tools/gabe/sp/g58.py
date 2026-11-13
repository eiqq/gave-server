"""G58 비명 횡대 게이브: 0.6 블록 비명 얼굴(입이 귀까지 찢어짐, 실눈은 꽉 감김) 아래 얼굴보다 넓은 살 몸통, 짧은 엔드 막대기 다리 둘,
몸통 어깨에서 엔드 막대기 둘을 만세하듯 머리 위로 곧게 쳐들었다. 10~16마리가 어깨를 맞대고 한 줄로 선다(줄은 Java).

모델 gave:gabe/g58: 상태 ""(질주: 다리 4자세, 쳐든 막대기는 자세 없음), "halt"(멈춰 비명: 다리 멈춤, 쳐든 막대기가 떨림 4자세),
"calm"(꿰뚫고 지나가 돌아선 뒤: 비명이 끊기고 다시 웃는 얼굴, 모두 멈춤).
"""
from gabe import faces, kit

HEAD, DEPTH = 0.6, 0.48
HEAD_Y = 0.22
TORSO = ((-0.35, -0.42, -0.22), (0.35, -0.08, 0.22))
SCREAM = faces.Face(eyes="squint", mouth="torn")
LEG = dict(length=0.5, segs=2, thick=0.1)
ARM = dict(length=1.05, segs=1, thick=0.1)


def body(s, mode):
    face = "smile" if mode == "calm" else SCREAM
    h = s.head(size=HEAD, depth=DEPTH, at=(0, HEAD_Y, 0), face=face)
    s.box(TORSO[0], TORSO[1], "flesh", face=True)
    for side in (1, -1):
        hip = (side * 0.16, -0.4, 0)
        if mode == "":
            w = kit.Wave(amp=(34, 26), lag=60, bias=(0, -14), phase=0 if side > 0 else 180)
            s.rod(hip, (0, -1, 0), wave=w, toward=(0, 0, 1), **LEG)
        else:
            s.rod(hip, (0, -1, 0.04), **LEG)
        shoulder = (side * 0.3, -0.12, 0)
        up = (side * 0.34, 1.0, 0.0)
        if mode == "halt":
            w = kit.Wave(amp=7, lag=0, bias=0, phase=0 if side > 0 else 90, cycles=2)
            s.rod(shoulder, up, wave=w, toward=(side, 0, 0), poses=4, **ARM)
        else:
            s.rod(shoulder, up, **ARM)
    return h


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    body(m.state(""), "")
    body(m.state("halt"), "halt")
    body(m.state("calm"), "calm")


def sounds(k: kit.Kit):
    # 줄의 가운데 한 마리만 울린다: 겹겹이 쌓인 비명(이 종 전용 신호, 멀리서 먼저 들림)
    k.sample("scream", "tag:scream", "비명 떼", pitch=1.12, attenuation=48)
    # 지나가며 몸통 박치기
    k.sample("bump", "kenney_impact/impactpunch_heavy_00*", "쿵", pitch=1.15, volume=0.8, attenuation=16)


def phrases():
    return {"cause_g58": "비명 횡대에 치였다"}
