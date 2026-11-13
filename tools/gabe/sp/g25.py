"""G25 셋 목말 게이브: 0.7 블록 얼굴 셋이 막대기로 서로 목말을 태운 사람 키 세로 탑. 디스플레이 둘.

- gave:gabe/g25(닻): 아래 얼굴 둘(아래 가운데 -0.7, 가운데 0)과 이음 막대기. 모델 원점 = 탑 가운데(바닥 위 1.05).
  상태 ""(비틀비틀 걷기: 탑이 좌우로 흔들리고 받쳐 든 막대기 팔이 버둥), "flail"(쓰러진 뒤 다시 쌓으려고 버둥: 두 배 빠르게, 얼굴이 들썩).
  원형 DNA: 가운데 얼굴 위 모서리에서 꼭대기를 받쳐 든 막대기 팔 둘이 펄럭인다.
- gave:gabe/g25_top(부품): 꼭대기 얼굴. 점 입이 동그랗게 벌어진 이빨 고리 입(얼굴 그림 teeth_ring + 뼈 이빨 고리 입체).
  상태 ""(기본), "bite"(물기: 이빨이 더 솟고 머리 위 짧은 막대기 둘이 빠르게 떪).
쓰러짐은 Java 가 몸 pitch(밑동을 축으로)로 한다.
"""
import math

from gabe import audio, kit
import numpy as np

S, D = 0.7, 0.55
ARM = kit.Wave(amp=(12, 14), lag=40, bias=(6, 8))
ARM_FAST = kit.Wave(amp=(30, 30), lag=60, bias=(-10, 10), shape="flap", cycles=2)


def lower(s, arm_wave, jitter):
    """아래 얼굴 둘. 자세마다 탑이 옆으로 흔들림(손으로 짠 자세), 막대기 팔은 물결"""
    for f in s.frames(poses=4):
        with f:
            a = math.sin(2 * math.pi * f.t)
            sway = a * 0.07 * jitter
            bob = abs(math.cos(2 * math.pi * f.t)) * 0.03 * jitter
            hb = s.head(size=S, depth=D, at=(sway * 0.3, -0.7 + bob, 0), face="smile_a", rot=(0, 0, -a * 4 * jitter), open=False)
            hm = s.head(size=S, depth=D, at=(sway, 0.0 + bob, 0.02), face="smile_c", rot=(0, 0, a * 6 * jitter), open=False)
            # 다리: 아래 얼굴 아래 모서리에서 바닥으로(짧게)
            for side in (1, -1):
                s.rod(hb.point(side * 0.6, -0.95, 0), (side * 0.25, -1, 0.1), length=0.36, segs=1, thick=0.09, base=False)
                # 아래 얼굴이 가운데 얼굴을 받쳐 든 막대기(위 모서리에서 가운데 얼굴 옆으로)
                s.rod(hb.point(side * 0.95, 0.7, 0), (side * 0.15, 1, 0), length=0.42, segs=1, thick=0.08, base=False)
    # 원형 DNA: 가운데 얼굴 위 모서리에서 꼭대기를 받쳐 든 팔 둘(자세 펄럭임)
    hm0 = kit.Head((0, 0, 0.02), S, S, D, np.eye(3))
    for side in (1, -1):
        s.rod(hm0.point(side * 0.92, 0.8, 0), (side * 0.55, 1, -0.1), length=0.62, segs=2, thick=0.09, wave=arm_wave,
              toward=(-side * 1.0, 0, 0), poses=6)


def top(s, bite):
    h = s.head(size=S, depth=D, face="teeth_ring")
    # 이빨 고리 입체: 얼굴 그림 입 자리(32 기준 17.5, 25) 둘레
    mx = -S / 2 + 17.5 / 32 * S
    my = S / 2 - 25.0 / 32 * S
    s.teeth((mx, my, D / 2 + 0.005), 0.085 if not bite else 0.1, count=10, axis=(0, 0, 1), length=0.05 if not bite else 0.09,
            size=0.03, inward=0.35)
    # 머리 위 짧은 막대기 둘(기다릴 땐 느리게, 물 땐 떪)
    w = kit.Wave(amp=(10, 12), lag=40) if not bite else kit.Wave(amp=(22, 20), lag=50, shape="twitch", cycles=2)
    for side in (1, -1):
        s.rod(h.point(side * 0.55, 1, -0.3), (side * 0.4, 1, -0.3), length=0.4, segs=2, thick=0.07, wave=w, poses=4)


def build(k: kit.Kit):
    # 변이 2 막대기: 가운데 얼굴 뒤통수 위에서(기본은 첫 얼굴 = 아래 얼굴이라 자리를 정해 줌)
    m = k.model(poses=8, period=24, mut2=dict(root=(0.24, 0.27, -0.25), dir=(0.45, 1.0, -0.7), length=0.7))
    lower(m.state(""), ARM, 1.0)
    lower(m.state("flail"), ARM_FAST, 2.2)
    t = k.model("g25_top", poses=8, period=24)
    top(t.state(""), False)
    top(t.state("bite"), True)


def _beat(seed):
    """'퉁': 몸통 울리는 낮은 쿵(탑 걷는 박자)"""
    return audio.normalize(audio.mix(audio.thump(seed=seed, dur=0.26, freq=96), audio.gain(audio.squish(seed=seed + 3, dur=0.2), 0.35)),
                           0.85)


def _whoosh(seed):
    """'휙': 탑이 앞으로 쓰러지는 바람 소리(대역 잡음이 위로 쓸림)"""
    d = 0.42
    n = audio.noise(d, 300 + seed)
    a = audio.bandpass(n, 300, 1400)
    b = audio.bandpass(n, 1200, 4200)
    t = np.linspace(0, 1, len(n), dtype=np.float32)
    x = a * (1 - t) + b * t
    return audio.normalize(audio.fade(audio.env(x, 0.06, 0.1, 0.8, 0.2), 0.01, 0.08), 0.9)


def sounds(k: kit.Kit):
    k.sound("beat", "퉁", synth=_beat, volume=0.9, attenuation=20)
    k.sound("whoosh", "휙", synth=_whoosh, volume=1.0, attenuation=20)
    k.sound("bite", "이빨 고리 물기", vanilla=[("mob/ravager/bite1", 1.35), ("mob/ravager/bite2", 1.4), ("mob/ravager/bite3", 1.3)],
            volume=0.9, attenuation=16)
    k.sound("spit", "실뭉치 토함", vanilla=[("block/honeyblock/break2", 0.6), ("block/honeyblock/slide1", 0.7),
                                      ("mob/slime/big4", 0.9)], volume=0.9, attenuation=16)


def phrases():
    return {"cause_g25": "목말 탑에 물렸다"}
