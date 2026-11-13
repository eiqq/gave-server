"""G178 머리 따로 게이브: 몸은 멀리 꿇어앉아 기다리고 머리만 날아와 사람들을 스쳐 문다.

몸(모델 gave:gabe/g178): 키 2.6 블록, 엔드 막대기 뼈대(등뼈, 갈비, 팔다리 뼈)에 분홍 살을 얇게 입힌 사람꼴. 목 자리에 막대기 둘이 받침
갈래처럼 벌어져 빈 접시(뼈 판)를 받친다. 몸 중심(0, 0, 0)은 땅 위 1.3(Java).
상태 "walk"(서서 걸어 들어옴, 다리 4자세), ""(무릎 꿇고 앉음, 손은 무릎에), "swing"(꿇은 채 팔 막대기를 휘둘러 후려침, 4자세).

머리(모델 gave:gabe/g178_head): 0.9 블록 얼굴 하나, 목 밑동에서 짧은 살 끈 하나가 달랑거림(2자세). 머리는 Java 가 따로 움직이는 개체다
(몸의 부품이 아님: 몸에서 36 블록 떨어져 날아다녀도 컬링에 안 걸리게).
"""
import math

from gabe import kit

BONE = dict(segs=1, thick=0.09, base=False, rag=False)


def skeleton_upper(k, s, arms):
    # 몸통: 얇은 살(등뼈 막대기가 앞뒤로 비침)
    s.box((-0.3, -0.25, -0.15), (0.3, 0.62, 0.13), "flesh", face=True)
    s.rod((0, -0.3, -0.17), (0, 1, 0), length=1.0, toward=(0, 0, 1), **BONE)  # 등뼈
    for y in (0.1, 0.3, 0.5):  # 갈비 막대기(앞으로 휘어 가슴에 드러남)
        for side in (1, -1):
            s.rod((0, y, 0.14), (side, -0.1, 0.25), length=0.36, **BONE)
    # 목 갈래 받침과 빈 접시
    for side in (1, -1):
        s.rod((0, 0.6, 0.0), (side * 0.55, 1, 0), length=0.5, **BONE)
    s.box((-0.36, 1.02, -0.3), (0.36, 1.07, 0.3), "bone")
    # 어깨뼈
    s.rod((-0.42, 0.56, 0.0), (1, 0, 0), length=0.84, **BONE)
    if arms == "knee":
        # 팔: 어깨에서 내려와 무릎에 손을 얹음
        for side in (1, -1):
            s.rod((side * 0.4, 0.56, 0.0), (side * 0.15, -1, 0.35), length=0.62, **BONE)
            s.rod((side * 0.45, -0.02, 0.2), (0, -0.7, 1), length=0.5, **BONE)
    elif arms == "walk":
        for side in (1, -1):
            s.rod((side * 0.4, 0.56, 0.0), (side * 0.12, -1, 0.0), length=1.1, **BONE)


def legs_kneel(k, s):
    # 넓적다리(앞으로 눕힘)와 정강이(땅에 붙음): 살 + 뼈
    for side in (1, -1):
        x = side * 0.16
        s.box((x - 0.12, -1.3, -0.62), (x + 0.12, -1.12, 0.1), "flesh", face=True)
        s.box((x - 0.13, -1.12, -0.18), (x + 0.13, -0.88, 0.52), "flesh", face=True)
        s.rod((x, -1.0, -0.1), (0, 0, 1), length=0.6, **BONE)
    s.box((-0.3, -0.92, -0.2), (0.3, -0.25, 0.12), "flesh", face=True)  # 엉덩이


def build_walk(k, s):
    skeleton_upper(k, s, "walk")
    s.box((-0.3, -0.45, -0.15), (0.3, -0.24, 0.13), "flesh", face=True)
    for f in s.frames():
        with f:

            for side in (1, -1):
                ph = f.t * math.tau + (0 if side > 0 else math.pi)
                swing = 0.35 * math.sin(ph)
                s.rod((side * 0.16, -0.45, 0.0), (0, -1, swing), length=0.86, segs=1, thick=0.1, base=False, toward=(0, 0, 1), rag=False)
    s.front = (0, 0, 1)


def build_kneel(k, s):
    skeleton_upper(k, s, "knee")
    legs_kneel(k, s)
    s.front = (0, 0, 1)


def build_swing(k, s):
    skeleton_upper(k, s, None)
    legs_kneel(k, s)
    # 팔 막대기 둘을 크게 휘둘러 앞을 후려침(손끝까지 1.4 블록)
    for side in (1, -1):
        w = kit.Wave(amp=60, bias=-20, phase=0 if side > 0 else 180, shape="flap")
        s.rod((side * 0.4, 0.56, 0.0), (side * 0.3, 0.1, 1), length=1.3, segs=2, thick=0.1, wave=w, toward=(0, 1, 0), base=False)
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, mut2=False, torn=False)
    build_walk(k, m.state("walk"))
    build_kneel(k, m.state(""))
    build_swing(k, m.state("swing"))
    h = k.model("g178_head", poses=2, period=8, mut2=False)
    st = h.state("")
    st.head(size=0.9, depth=0.72, face="smile")
    st.band((0, -0.45, -0.05), (0, -1, -0.15), length=0.42, width=0.1, segs=2, thick=0.05,
            wave=kit.Wave(amp=(18, 22), lag=60), toward=(0, 0, 1), tex="flesh", poses=2)
    k.icon(model="g178_head", state="")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 머리가 지나갈 때 귓가를 스치는 웃음
    k.sample("giggle", "tag:evil", "머리 웃음소리", pitch=1.15, volume=0.8, attenuation=24)
    # 스쳐 묾
    k.sample("bite", "kenney_impact/impactpunch_medium_00*", "스쳐 묾", pitch=1.2, attenuation=16)
    # 몸이 팔 막대기로 후려침
    k.sample("swing", "oga_metalwood100/wood_hammer_0*", "후려침", pitch=0.7, attenuation=20)


def phrases():
    return {"cause_g178": "날아온 머리에 물렸다"}
