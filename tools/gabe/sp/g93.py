"""G93 피리 부는 게이브: 엔드 막대기 몸통과 팔다리로 된 키 3.5 블록 막대기 사람, 머리는 위아래로 길게 늘인 _Gave 얼굴. 점 입에 엔드 막대기 하나를
피리처럼 물고 두 팔로 받쳐 든다. 뒤로 0.5 블록 얼굴 여섯이 발맞춰 한 줄로 따른다(각자 막대기 다리 둘, 따로 디스플레이).
틀 ±1.5 를 넘어 몸은 0.5 로 줄여 굽는다. Java hover 1.75 면 발끝이 바닥.

- gave:gabe/g93: 상태 "walk"(기본: 성큼 걸음, 자세 4), "play"(피리 붊: 머리 까딱, 피리 떨림, 발 박자).
- gave:gabe/g93_kid: 따르는 얼굴. 상태 ""(종종걸음), "bite"(이빨 고리 입, 달려듦).
소리 pipe(피리 소리, 32), swing(막대기 휘두름, 16), bite(따르는 것이 묾, 16). 글귀 cause_g93(따르는 것), cause_g93_swing.
"""
import math

from gabe import kit

FRONT = (0, 0, 1)


def piper(s, play):
    # 머리: 위아래로 길게 늘인 얼굴(놀 때는 박자에 맞춰 까딱: 자세마다 머리를 따로)
    if play:
        for f in s.frames(poses=4):
            with f:
                tilt = 10 * math.sin(2 * math.pi * f.t)
                s.head(size=0.72, height=1.0, depth=0.6, at=(0, 1.25, 0), face="smile", rot=(tilt, 0, 0))
    else:
        s.head(size=0.72, height=1.0, depth=0.6, at=(0, 1.25, 0), face="smile")
    # 몸통(척추), 어깨, 골반: 가만히 있는 구조 막대기
    s.rod((0, -0.5, 0), (0, 1, 0), length=1.27, segs=1, thick=0.13, toward=FRONT, base=False, rag=False)
    s.rod((-0.48, 0.72, 0), (1, 0, 0), length=0.96, segs=1, thick=0.11, base=False, rag=False)
    s.rod((-0.26, -0.5, 0), (1, 0, 0), length=0.52, segs=1, thick=0.11, base=False, rag=False)
    # 팔: 어깨에서 앞으로 내려와 피리를 받침(팔꿈치에서 꺾인 두 마디)
    for side in (1, -1):
        s.rod((side * 0.48, 0.72, 0), (side * 0.15, -0.8, 0.4), length=0.62, segs=1, thick=0.09, base=False, rag=False)
        s.rod((side * 0.58, 0.17, 0.27), (-side * 0.52, 0.58, 0.48), length=0.9, segs=1, thick=0.08, base=False, rag=False)
    # 피리: 점 입에서 앞 아래로(놀 때는 떨림)
    pipe = kit.Wave(amp=7, shape="twitch", cycles=2) if play else None
    s.rod((0, 1.02, 0.3), (0, -0.55, 1), length=0.85, segs=1, thick=0.07, wave=pipe, toward=(0, 1, 0), base=False, poses=4 if play else None,
          rag=False)
    # 다리: 걷기는 성큼성큼, 놀 때는 한 발로 박자
    for side in (1, -1):
        if play:
            w = kit.Wave(amp=(10 if side > 0 else 0), bias=4, shape="twitch")
        else:
            w = kit.Wave(amp=28, phase=0 if side > 0 else 180)
        s.rod((side * 0.22, -0.5, 0), (side * 0.12, -1, 0), length=1.25, segs=2, thick=0.11, wave=w, toward=FRONT, poses=4, rag=False)
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=0.5, mut2=dict(root=lambda side: (side * 0.25, 1.55, -0.1), dir=(0, 1, -0.2), length=0.5, segs=1,
                                                       poses=2))
    piper(m.state("walk"), False)
    piper(m.state("play"), True)
    m.default = "walk"

    kid = k.model("g93_kid", poses=4, period=10, mut2=False, torn=False)
    for name, face in (("", "smile"), ("bite", "teeth_ring")):
        s = kid.state(name)
        s.head(size=0.5, depth=0.4, face=face)
        for side in (1, -1):
            s.rod((side * 0.12, -0.24, 0), (side * 0.15, -1, 0), length=0.34, segs=1, thick=0.07,
                  wave=kit.Wave(amp=34 if name == "" else 12, phase=0 if side > 0 else 180), toward=FRONT, poses=4, rag=False)
    kid.default = ""
    k.icon(model="g93", state="play")


def sounds(k: kit.Kit):
    k.sample("pipe", [{"id": "lrsf_horror/jingle_lose_00", "pitch": 1.35}, {"id": "lrsf_horror/jingle_achievement_00", "pitch": 1.2},
                      {"id": "lrsf_horror/jingle_lose_00", "pitch": 1.5}], "피리 소리", attenuation=32)
    k.sample("swing", "kenney_impact/impactwood_light_00*", "막대기 휘두름", pitch=0.9, attenuation=16)
    k.sample("bite", "tag:eat", "작은 얼굴이 물었다", pitch=1.4, attenuation=16)


def phrases():
    return {"cause_g93": "피리 따라가다 물렸다", "cause_g93_swing": "피리 부는 것에 맞았다"}
