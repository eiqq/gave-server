"""G54 올라타는 게이브: 반 크기(0.4 블록) 웃지 않는 얼굴(반쯤 뜬 눈, 입과 홍조 없음). 엔드 막대기 둘이 거미 다리처럼 옆으로 나와 아래로 굽는다.

상태: ""(혼자 바닥을 김: 두 다리가 번갈아 빠르게 디딤), "ride"(숙주 정수리를 움켜쥠: 다리가 아래로 더 말리고 가끔 움찔),
"hop"(뛰어오름: 다리를 뒤로 쭉 폄). 숙주의 흰자(팔레트)는 Java 가 숙주 look 에 건다.
"""
import numpy as np

from gabe import audio, faces, kit

FACE = faces.Face(eyes="half", mouth="none", blush="none")


def body(s, bias, wave_kw, root_v=0.0, out=(0.8, 0.55, 0.0)):
    h = s.head(size=0.4, depth=0.3, face=FACE)
    for side, ph in ((1, 0), (-1, 180)):
        w = kit.Wave(bias=bias, phase=wave_kw.get("phase", 0) + ph, **{k: v for k, v in wave_kw.items() if k != "phase"})
        s.rod(h.point(side * 1.0, root_v, 0.0), (side * out[0], out[1], out[2]), length=0.78, segs=3, thick=0.06, wave=w, toward=(0, 1, 0))
    return h


def build(k: kit.Kit):
    m = k.model(poses=8, period=12, mut2=dict(length=0.4, thick=0.05))
    # 기어감: 뿌리에서 끝으로 퍼지는 디딤(12틱에 두 걸음)
    body(m.state(""), (-18, -58, -46), dict(amp=(12, 14, 10), lag=60, cycles=2))
    # 올라탐: 아래로 더 말려 움켜쥠, 가끔 움찔
    body(m.state("ride"), (-34, -70, -52), dict(amp=(3, 5, 7), lag=40, shape="twitch", cycles=2), root_v=-0.2)
    # 뛰어오름: 뒤로 쭉
    body(m.state("hop"), (-6, -12, -10), dict(amp=(4, 6, 8), lag=30, cycles=2), out=(0.55, -0.2, -0.8))


def ticktock(seed=0):
    """똑딱: 마른 나무 두드림 둘(높게, 낮게)"""
    d = 0.24
    out = audio.silence(d)
    for i, (f, t) in enumerate(((1250, 0.0), (930, 0.11))):
        c = audio.env(audio.tone(f * (1 + 0.03 * seed), 0.05, "tri", glide=f * 0.8), 0.0005, 0.01, 0.2, 0.03)
        n = audio.env(audio.highpass(audio.noise(0.012, 540 + seed + i), 2500), 0.0005, 0.004, 0.2, 0.006) * 0.5
        out = audio.mix(out, audio.at(audio.mix(c, n), t, d))
    return audio.normalize(audio.fade(out, 0.001, 0.02), 0.8)


def sounds(k: kit.Kit):
    # 똑딱 발소리: 이 종의 신호(길 때 6틱마다)
    k.sound("tick", "똑딱 발소리", synth=ticktock, volume=0.8, attenuation=24)
    # 숙주 정수리를 움켜쥠
    k.sound("clamp", "정수리를 움켜쥠", vanilla=[("mob/spider/step1", 0.6), ("mob/spider/step3", 0.55)],
            synth=lambda i: audio.squish(seed=545 + i, dur=0.2), volume=0.9, attenuation=24)
    k.sound("hop", "뛰어오름", vanilla=[("mob/slime/small2", 1.6), ("mob/slime/small4", 1.7)], volume=0.7, attenuation=16)


def phrases():
    return {"cause_g54": "올라탄 것이 부렸다", "notice_g54_ride": "정수리에 올라탔다"}
