"""G24 땅밑 얼굴 게이브: 0.9 블록 얼굴이 땅속에 묻혀 이마 위쪽(적갈색 덩어리 둘)과 귀 줄 둘만 흙 위로 내놓고, 뒤통수 양옆의
엔드 막대기 둘이 흙 위에 지느러미처럼 눕는다.

모델 원점 = 땅 높이(Java 가 몸 중심을 바닥에 둠: hover 0). 땅속에 있는 면은 바닥 블록에 가려 안 보인다.
상태: ""(땅속을 기어 옴: 이마 3분의 1만 위로, 귀 줄이 흙을 가름, 막대기 느린 지느러미),
      "peek"(발밑 맴돌기: 얼굴이 하늘을 보고 앞면이 바닥과 같은 높이, 웃는 눈이 올려다봄, 막대기는 양옆에서 위로 솟아 퍼덕),
      "rise"(솟구침, 발목 잡기, 박치기: 얼굴 전체가 땅 위로, 막대기 두 배 빠르게).
실눈 뜸(표적), 입 찢어짐(변이 1), 변이 2 막대기는 키트가 저절로 붙임.
"""
from gabe import audio, kit
import numpy as np

SIZE, DEPTH = 0.9, 0.7
FIN = kit.Wave(amp=(14, 12, 10), lag=50, bias=(-4, 2, 4))
FIN_FAST = kit.Wave(amp=(26, 22, 18), lag=55, bias=(-6, 2, 4), shape="flap", cycles=2)
PEEK_FIN = kit.Wave(amp=(18, 16, 12), lag=45, bias=(4, 6, 6), shape="flap")
EAR = kit.Wave(amp=(6, 8), lag=40)


def crawl(s):
    # 얼굴 가운데가 땅 아래 0.17: 위 0.28 만 흙 위(이마 덩어리), 눈과 입은 땅속
    h = s.head(size=SIZE, depth=DEPTH, at=(0, -0.17, 0), face="smile")
    for side in (1, -1):
        # 귀 줄: 머리 위 옆에서 위로 솟은 얇은 살 띠(상어 지느러미처럼 흙을 가름)
        s.band(h.point(side * 0.72, 0.92, 0.1), (0, 1, -0.55), length=0.36, width=0.26, segs=2, thick=0.03, wave=EAR,
               toward=(1, 0, 0), tex="flesh")
        # 막대기 지느러미: 뒤통수 위 옆에서 뒤로, 흙 위에 거의 눕게
        s.rod(h.point(side * 0.55, 0.88, -0.95), (side * 0.55, 0.12, -1.0), length=1.05, segs=3, thick=0.1, wave=FIN)


def peek(s):
    # 하늘을 보는 얼굴: 머리 회전 x -90(앞면이 +Y), 앞면이 바닥 위 0.03
    h = s.head(size=SIZE, depth=DEPTH, at=(0, 0.03 - DEPTH / 2, 0), face="smile", rot=(-90, 0, 0))
    for side in (1, -1):
        # 막대기: 얼굴 옆 가장자리(땅 높이)에서 위로 솟아 퍼덕
        s.rod(h.point(side * 1.0, 0.25, 0.92), (side * 0.7, 1.0, 0.0), length=0.95, segs=3, thick=0.1, wave=PEEK_FIN,
              toward=(-side * 1.0, 0.0, 0.0))
        # 귀 줄: 머리 위 가장자리(지금은 뒤쪽 바닥)에서 위로
        s.band(h.point(side * 0.6, 1.0, 0.85), (0, 1, -0.35), length=0.3, width=0.24, segs=2, thick=0.03, wave=EAR,
               toward=(1, 0, 0), tex="flesh")


def rise(s):
    # 얼굴 전체가 땅 위(아래 끝이 바닥 위 0.1)
    h = s.head(size=SIZE, depth=DEPTH, at=(0, 0.55, 0), face="smile")
    for side in (1, -1):
        s.band(h.point(side * 0.72, 0.92, 0.1), (0, 1, -0.55), length=0.36, width=0.26, segs=2, thick=0.03, wave=EAR,
               toward=(1, 0, 0), tex="flesh")
        s.rod(h.point(side * 0.9, 0.2, -0.7), (side * 1.0, 0.35, -0.6), length=1.05, segs=3, thick=0.1, wave=FIN_FAST)
    # 흙 묻은 아래 가장자리(젖은 천처럼 늘어짐)
    s.band(h.point(0, -1, 0.9), (0, -1, -0.1), length=0.2, width=0.8, segs=2, thick=0.025, tex="wetskin")


def build(k: kit.Kit):
    m = k.model(poses=8, period=20)
    crawl(m.state(""))
    peek(m.state("peek"))
    rise(m.state("rise"))
    k.icon(model="g24", state="rise")


def _rumble(seed):
    d = 1.1
    t = np.arange(int(d * audio.SR), dtype=np.float32) / audio.SR
    low = audio.tone(38, d, vib=0.08, vib_rate=3) * 0.7 + audio.tone(57, d, "tri") * 0.25
    trem = 0.55 + 0.45 * np.sin(2 * np.pi * (7 + seed) * t)
    grit = audio.lowpass(audio.noise(d, 40 + seed), 160) * 0.9
    return audio.normalize(audio.fade(audio.env(audio.mix(low * trem, grit), 0.12, 0.2, 0.8, 0.35), 0.05, 0.2), 0.9)


def sounds(k: kit.Kit):
    # 땅을 가르는 소리(기어 올 때 자주): 흙 밟는 소리를 낮게
    k.sound("dig", "흙 가르는 소리", vanilla=[("block/rooted_dirt/step1", 0.62), ("block/rooted_dirt/step3", 0.58),
                                       ("dig/gravel2", 0.66)], volume=0.7, attenuation=16)
    # 대상 한 사람에게만, 그 사람 자리에서 나는 낮은 진동(위치 없음)
    k.sound("rumble", "발밑 진동", synth=_rumble, volume=1.0, attenuation=8)
    # 솟구칠 때 흙 터짐
    k.sound("burst", "흙이 터짐", vanilla=[("block/rooted_dirt/break2", 0.55), ("dig/gravel4", 0.5), ("block/mud/break3", 0.6)],
            volume=1.0, attenuation=20)
    # 발목 잡힘(젖은 손아귀)
    k.sound("grab", "발목 잡힘", vanilla=[("mob/slime/attack1", 0.62), ("mob/slime/attack2", 0.58)], volume=0.9, attenuation=16)
    # 박치기
    k.sound("butt", "아래서 들이받음", synth=lambda i: audio.thump(seed=240 + i, freq=82), volume=1.0, attenuation=16)


def phrases():
    return {"cause_g24": "발밑에서 붙잡혔다"}
