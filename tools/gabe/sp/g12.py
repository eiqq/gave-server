"""G12 얼굴 꿰는 게이브: 0.7 얼굴을 쇠사슬 고리(지름 1.25)가 앞면에서 둘러싸고(조문 화환 꼴), 주운 죽은 게이브 얼굴을 고리 꼭대기부터 양옆으로
하나씩 꿰어 단다(최대 다섯, 꿰인 얼굴은 진자처럼 까딱. 꿸수록 낮게 뜨므로 고리 아래쪽은 비워 땅에 끌리지 않게). 막대기 둘은 얼굴 옆에서 앞으로 굽은 집게(안쪽으로 오므렸다 폄, 원형 DNA).

상태: ""(빈 고리), "s1" .. "s5"(꿰인 얼굴 수). 꿸수록 낮고 느려지는 것과 다섯이 차서 녹는 것은 Java(높이, 찌그러짐).
"""
import math

import numpy as np

from gabe import audio, kit

STRUNG = ["half", "smile_a", "tears", "smile_c", "torn"]
PINCER = kit.Wave(amp=(10, 18), lag=60, bias=(10, 24))
RING_R, RING_Z, RING_N = 0.62, 0.34, 12
# 꿰는 차례(고리 위 각도, 도): 꼭대기부터 양옆으로(Java 가 다섯이면 hover 0.65 까지 낮춤: 아래쪽에 달면 땅에 박힘)
ORDER = [90, 45, 135, 0, 180]


def wreath(s, n):
    h = s.head(size=0.7, depth=0.5, face="smile")
    # 앞으로 굽은 집게 둘(안쪽으로 오므림: toward 가 몸 가운데 쪽)
    for side in (1, -1):
        s.rod(h.point(side * 0.95, -0.25, 0.3), (side * 0.6, -0.12, 1.0), length=0.7, segs=2, thick=0.09, wave=PINCER,
              toward=(-side, 0, 0.15), poses=4)
    # 쇠사슬 고리(가만히): 얼굴 앞면 둘레
    pts = [(RING_R * math.cos(2 * math.pi * i / RING_N), RING_R * math.sin(2 * math.pi * i / RING_N), RING_Z) for i in range(RING_N + 1)]
    for i in range(RING_N):
        s.chain(pts[i], pts[i + 1], links=2, sag=0.0, size=0.12)
    if n <= 0:
        return
    # 꿰인 얼굴: 고리 점에 매달려 진자처럼 까딱(자세 4)
    for fr in s.frames(4):
        with fr:
            for i in range(n):
                a = math.radians(ORDER[i])
                hang = (-1) ** i * 12 + 9 * math.sin(2 * math.pi * fr.t + i * 1.7)
                px, py = RING_R * math.cos(a), RING_R * math.sin(a)
                c = np.array((px, py - 0.08, RING_Z + 0.05))
                s.head(size=0.28, depth=0.18, at=tuple(c), face=STRUNG[i], near=False, open=False, torn=False, rot=(0, 0, hang))


def build(k: kit.Kit):
    m = k.model(poses=6, period=24, mut2=dict(poses=4, segs=2))
    wreath(m.state(""), 0)
    for n in range(1, 6):
        wreath(m.state(f"s{n}"), n)
    k.icon(model="g12", state="s3")


def grind(seed=0, dur=0.55):
    """뽀드득: 얼굴끼리 비벼지는 마른 마찰(높은 대역 잡음을 빠르게 끊었다 이음)"""
    n = audio.bandpass(audio.noise(dur, seed), 1800, 6500)
    t = np.arange(len(n), dtype=np.float32) / audio.SR
    am = (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * (34 + 8 * np.sin(2 * np.pi * 3 * t)) * t))).astype(np.float32)
    x = audio.env(n * am, 0.01, 0.1, 0.6, 0.12)
    sq = audio.env(audio.tone(1300 + 60 * seed % 200, dur, "saw", vib=0.08, vib_rate=11), 0.02, 0.1, 0.3, 0.1) * 0.25
    return audio.normalize(audio.mix(x, audio.highpass(sq, 900)), 0.7)


def clink(seed=0):
    """꿰는 소리: 쇠사슬 철컥 + 젖은 찍"""
    c = audio.env(audio.mix(audio.tone(2300, 0.12, "tri"), audio.tone(3170, 0.1, "sine"), audio.gain(audio.highpass(audio.noise(0.06, seed), 3000), 0.6)),
                  0.001, 0.02, 0.3, 0.08)
    return audio.normalize(audio.mix(c, audio.at(audio.squish(seed=seed + 3, dur=0.16), 0.08)), 0.75)


def sounds(k: kit.Kit):
    k.sound("grind", "뽀드득", synth=lambda i: grind(130 + i), volume=0.8, attenuation=16)
    # 꿰인 얼굴들의 킥킥(이 종은 도감이 웃음을 정함: 매달린 얼굴들)
    k.sound("giggle", "매달린 얼굴 킥킥", synth=lambda i: audio.giggle(seed=140 + i, count=5, base=610 + 30 * i, rate=11), volume=0.55,
            attenuation=16)
    k.sound("clink", "철컥 꿰는 소리", synth=lambda i: clink(150 + i), vanilla=[("block/chain/step1", 1.3)], attenuation=16)
    k.sound("melt", "얼굴들이 녹아 붙음", synth=lambda i: audio.normalize(audio.lowpass(audio.cat(audio.squish(seed=160 + i, dur=0.5),
                                                                                         audio.squish(seed=170 + i, dur=0.6)), 1600)),
            attenuation=24)


def phrases():
    return {"cause_g12": "꿰인 얼굴을 놔뒀다", "notice_g12_merge": "얼굴 다섯이 녹아 붙었다"}
