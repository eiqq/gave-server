"""G08 금빛 홍조 게이브(보상 종): 0.6 블록 얼굴, 홍조만 금빛(Java 가 팔레트 비트 PAL_GOLD 를 켬: 먼 얼굴, 가까운 얼굴 모두),
엔드 막대기 둘이 몸보다 길어(1.35 블록) 꼬리처럼 뒤로 끌린다.

모델 gave:gabe/g08: 상태 ""(가장자리를 따라 헤엄치듯), "rush"(사람이 다가오면 빨리: 같은 주기에 두 번 침).
소리 jingle(딸랑, 나올 때 맵 전체), 그 밖은 엔진 기본(hurt, die).
"""
from gabe import audio, kit

import numpy as np


def body(s, wave):
    h = s.head(size=0.6, depth=0.45, face="smile")
    for side in (1, -1):
        s.rod(h.point(side * 0.7, -0.35, -0.9), (side * 0.32, 0.05, -1.0), length=1.35, segs=3, thick=0.09,
              wave=wave if side > 0 else kit.Wave(wave.amp, wave.lag, wave.bias, wave.phase + 140, wave.shape, wave.cycles),
              toward=(0, 1, 0))
    return h


def build(k: kit.Kit):
    m = k.model(poses=8, period=24)
    m.mut2 = dict(root=(0.2, 0.25, -0.18), dir=(0.5, 1.0, -0.8), length=0.5)
    body(m.state(""), kit.Wave(amp=(10, 16, 24), lag=70, bias=(-8, 2, 4)))
    body(m.state("rush"), kit.Wave(amp=(14, 22, 30), lag=75, bias=(-10, 0, 4), cycles=2))
    # HUD 아이콘: 금빛 홍조 얼굴(게임에서는 팔레트 비트로 금빛이라 기본 그림은 분홍)
    from PIL import Image
    from gabe import faces
    face = Image.fromarray(faces.far("gold")).resize((48, 48), Image.NEAREST)
    icon = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    icon.alpha_composite(face, (8, 8))
    k.icon(icon)


def sounds(k: kit.Kit):
    # 딸랑: 나올 때 한 번, 맵 전체에 들림(작은 방울)
    k.sound("jingle", "딸랑", vanilla=[("block/bell/bell_use01", 1.9), ("note/bell", 1.5)],
            synth=lambda i: bell(seed=800 + i), volume=1.0, attenuation=160)


def bell(seed=0, dur=1.1):
    rng = np.random.RandomState(seed)
    f = 1650 + 80 * rng.rand()
    parts = []
    for i, k in enumerate((0.0, 0.11, 0.21)):
        x = audio.mix(audio.tone(f, dur - k), audio.tone(f * 2.76, dur - k) * 0.35, audio.tone(f * 5.4, dur - k) * 0.12)
        parts.append(audio.at(audio.env(x, 0.002, 0.08, 0.25, dur - k - 0.12) * (0.9 - 0.25 * i), k, dur))
    return audio.normalize(audio.fade(audio.mix(*parts)), 0.7)


def phrases():
    return {
        "notice_g08": "금빛 홍조가 나타났다",
        "notice_g08_got": "금빛 홍조를 잡았다: 모두 증강 뽑기",
        "notice_g08_gone": "금빛 홍조가 달아났다",
    }
