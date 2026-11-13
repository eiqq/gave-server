"""G29 손잡고 도는 게이브: 0.55 블록 얼굴 여섯이 엔드 막대기 팔을 맞잡은 고리(마디 = 떼 마리 하나, 디스플레이 하나).
얼굴은 고리 안쪽(+Z)을 보고, 왼팔(+X)과 오른팔(-X)이 이웃 쪽으로 60도 기울어 뻗어 고리 반지름에 맞는 길이로 맞닿는다.

- gave:gabe/g29(마리): 상태 "r5", "r4", "r3", "r2"(고리 반지름 5, 4, 3, 2.4 블록에 맞는 팔 길이, 기본 "r5"), "lod".
  부위 "cut_l", "cut_r": 그쪽 팔을 쏴서 2초 열림(짧은 그루터기만). 팔은 원형 DNA 막대기(느리게 출렁).
  떼 마리라 변이 2 막대기, 입 찢어짐은 끔(무리 몸이 받음).
- gave:gabe/g29_ring(부품, 바닥 살 띠 고리): 반지름 1 블록으로 굽고 Java 가 고리 반지름만큼 키운다. 부위 "ring" 이 켜질 때만 보임
  (고리가 닫혔을 때). 맥박 자세 4.
"""
import math

from gabe import audio, kit
import numpy as np

S, D = 0.55, 0.42
RADII = {"r5": 5.0, "r4": 4.0, "r3": 3.0, "r2": 2.4}
ARM = kit.Wave(amp=(5, 7, 8), lag=45, bias=(0, 0, 0))


def member(s, R):
    h = s.head(size=S, depth=D, face="smile")
    # 팔: 얼굴 옆 가운데 조금 아래에서 이웃 쪽(안쪽에서 60도)으로. 길이 = 반지름의 반 - 얼굴 옆 거리
    L = max(0.4, R / 2 - 0.24)
    for side, part in ((1, "cut_l"), (-1, "cut_r")):
        root = h.point(side * 1.0, -0.25, -0.1)
        d = (side * math.sin(math.radians(60)), -0.02, math.cos(math.radians(60)))
        with s.when("!on:" + part):
            s.rod(root, d, length=L, segs=3, thick=0.1, wave=ARM.scaled(1.0 if R > 3 else 1.4), toward=(0, 1, 0), poses=6)
        with s.when("on:" + part):
            s.rod(root, d, length=0.3, segs=1, thick=0.1)


def build(k: kit.Kit):
    m = k.model(poses=6, period=24, shrink=0.5, mut2=False, torn=False)
    for name, R in RADII.items():
        member(m.state(name), R)
    m.default = "r5"
    lod = m.state("lod")
    lod.lump(count=6, radius=(0.6, 0.2, 0.6), size=0.45, faces_list=["smile"], seed=29)
    ring = k.model("g29_ring", poses=4, period=20, mut2=False, open=False, torn=False)
    s = ring.state("")
    flesh = k.part_tex("flesh")
    n = 28
    with s.when("on:ring"):
        for f in s.frames():
            with f:
                pulse = 1.0 + 0.35 * math.sin(2 * math.pi * f.t)
                for i in range(n):
                    a = 2 * math.pi * (i + 0.5) / n
                    c = (math.sin(a) * 1.0, 0.0, math.cos(a) * 1.0)
                    seg = 2 * math.pi / n * 1.06
                    wid = 0.05 * pulse
                    s.box((c[0] - seg / 2, 0, c[2] - wid / 2), (c[0] + seg / 2, 0.002, c[2] + wid / 2), flesh, faces=("up",),
                          uv=[0, 0, 16, 4], rot=(0, math.degrees(a) + 90, 0), origin=c)
    k.icon(model="g29", state="r5")


def _ha(seed):
    """'하': 짧고 밝은 숨 섞인 웃음 한 마디(놀이 구호처럼 주고받음). Java 가 얼굴마다 음높이를 바꿔 울림"""
    f = 330 + seed * 25
    v = audio._vowel(f, 0.2, ((800, 1.0), (1300, 0.6), (2600, 0.25)), 900 + seed)
    breath = audio.bandpass(audio.noise(0.2, 910 + seed), 1200, 5000) * 0.25
    return audio.normalize(audio.fade(audio.env(audio.mix(v, breath), 0.008, 0.05, 0.5, 0.1), 0.004, 0.05), 0.8)


def sounds(k: kit.Kit):
    k.sound("ha", "하", synth=_ha, volume=0.9, attenuation=20)
    k.sound("shove", "고리가 밀쳐냄", vanilla=[("mob/slime/big3", 0.85), ("entity/player/attack/knockback2", 0.7)], volume=1.0,
            attenuation=16)
    k.sound("snap", "맞잡은 손이 풀림", vanilla=[("random/break", 0.75), ("random/break", 0.85)], volume=0.9, attenuation=20)
    k.sound("close", "고리가 닫힘", vanilla=[("block/honeyblock/slide4", 0.6), ("mob/slime/big1", 0.6)], volume=1.0, attenuation=20)


def phrases():
    return {"cause_g29": "손잡은 고리에 밀려났다"}
