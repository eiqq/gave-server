"""G33 토끼귀 게이브: 1 블록 얼굴, 모자 층 적갈색 덩어리 자리에서 엔드 막대기 둘이 토끼 귀처럼 쫑긋 솟는다(원형 DNA). 배울수록 귀가 길어진다.

상태(귀 길이 단계 0, 1, 2 마다 셋):
  "", "l1", "l2"        듣는 중: 두 귀가 번갈아 '쫑긋쫑긋'(40틱 한 바퀴: 왼쪽 귀는 처음, 오른쪽 귀는 반 바퀴 뒤에 두 번 까딱)
  "aim", "aim1", "aim2"  표적을 겨눔: 두 귀가 앞으로 곧게 뻗어 가늘게 떪
  "dash", "dash1", "dash2" 돌진: 귀를 뒤로 눕히고 파르르
귀 길이는 상태로 바꾼다(부위로 하면 자세마다 덧마디 면이 늘어 한도를 넘음). 같은 자세 칸을 모든 상태가 나눠 쓴다.
실눈 뜸(표적), 입 찢어짐(변이 1), 변이 2 막대기(얼굴 양옆 아래로 짧게)는 키트가 붙임.
"""
import numpy as np

from gabe import audio, kit

SIZE, DEPTH = 1.0, 0.7
LEN = (0.9, 1.2, 1.5)


def ears(s, length, kind):
    h = s.head(size=SIZE, depth=DEPTH, face="smile")
    for side in (1, -1):
        root = h.point(side * 0.53, 1.0, -0.15)
        if kind == "listen":
            d = (side * 0.14, 1.0, -0.05)
            w = kit.Wave(amp=(16, 12, 10), lag=20, bias=(0, 2, 2), shape="twitch", phase=20 if side > 0 else 200)
            tw = (side * 1.0, 0.0, 0.25)
        elif kind == "aim":
            d = (side * 0.1, 0.72, 0.7)
            w = kit.Wave(amp=(2.5, 2.5, 3), lag=90, cycles=4, phase=0 if side > 0 else 90)
            tw = (side * 1.0, 0.0, 0.0)
        else:
            d = (side * 0.16, 0.22, -1.0)
            w = kit.Wave(amp=(7, 9, 12), lag=60, bias=(2, 3, 4), cycles=2, phase=0 if side > 0 else 120)
            tw = (0.0, 1.0, 0.0)
        s.rod(root, d, length=length, segs=3, thick=0.12, wave=w, toward=tw)


def build(k: kit.Kit):
    m = k.model(poses=8, period=40,
                mut2=dict(root=lambda side: (side * 0.52, -0.3, -0.2), dir=(1.0, -0.35, -0.5), length=0.7, segs=2, poses=4,
                          toward=(0.0, 1.0, 0.0)))
    for lv, length in enumerate(LEN):
        suf = "" if lv == 0 else str(lv)
        ears(m.state("" if lv == 0 else "l" + suf), length, "listen")
        ears(m.state("aim" + suf), length, "aim")
        ears(m.state("dash" + suf), length, "dash")
    k.icon(model="g33", state="aim1")


def hum(seed=0):
    """콧노래: 입 다물고 흥얼거리는 짧은 가락(세 음, 가는 떨림). 귀가 표적을 겨누면 뚝 끊긴다"""
    notes = [(392, 0.28), (440, 0.22), (349, 0.34)] if seed % 2 == 0 else [(349, 0.24), (392, 0.24), (330, 0.36)]
    parts = []
    t = 0.0
    for f, d in notes:
        x = audio.tone(f * (1 + 0.01 * seed), d, vib=0.012, vib_rate=5.5) * 0.7 + audio.tone(f * 2, d) * 0.12
        parts.append(audio.at(audio.env(x, 0.03, 0.05, 0.75, 0.07), t))
        t += d * 0.92
    return audio.normalize(audio.fade(audio.lowpass(audio.mix(*parts), 1400), 0.01, 0.05), 0.5)


def twitch(seed=0):
    """쫑긋쫑긋: 털이 스치는 짧은 소리 두 번"""
    def swish(sd):
        return audio.env(audio.bandpass(audio.noise(0.07, sd), 2200, 7000), 0.004, 0.02, 0.3, 0.03)
    return audio.normalize(audio.fade(audio.mix(swish(seed), audio.at(swish(seed + 5), 0.13)), 0.002, 0.02), 0.6)


def lock(seed=0):
    """착: 두 귀가 한 번에 겨눠짐(짧고 마른 소리)"""
    c = audio.env(audio.highpass(audio.noise(0.04, seed), 1500), 0.0004, 0.006, 0.12, 0.02)
    t = audio.env(audio.tone(520, 0.05, "tri", glide=300), 0.0005, 0.01, 0.2, 0.02) * 0.6
    return audio.normalize(audio.fade(audio.mix(c, t), 0.0005, 0.01), 0.8)


def sounds(k: kit.Kit):
    k.sound("hum", "콧노래", synth=lambda i: hum(seed=330 + i), volume=0.6, attenuation=20)
    # 이 종의 신호: 귀 돌릴 때 털 스치는 소리
    k.sound("twitch", "쫑긋쫑긋", synth=lambda i: twitch(seed=334 + i), volume=0.8, attenuation=16)
    k.sound("lock", "귀가 겨눠짐", synth=lambda i: lock(seed=338 + i), attenuation=24)
    k.sound("dash", "쌩", vanilla=[("mob/phantom/swoop1", 1.5), ("mob/phantom/swoop2", 1.6)], attenuation=32)
    k.sound("bump", "쿵", synth=lambda i: audio.thump(seed=342 + i, freq=95), attenuation=16)


def phrases():
    return {"cause_g33": "쏜 소리를 들켰다"}
