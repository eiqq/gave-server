"""G28 짓누르는 게이브: 4 블록 넓적한 얼굴(옛 2 블록의 두 배)이 아래를 보고 엎어져 하늘 15블록을 떠다니고, 네 귀퉁이 엔드 막대기 넷(2.6 블록,
원형 DNA)이 위로 솟아 낙하산처럼 펄럭인다. 깔린 얼굴(4블록)이 짓누름 반경(4)의 절반이라 바닥 그림자와 같이 읽힌다. 모델은 얼굴이 +Z 를 보게
굽고(막대기는 뒤 -Z 쪽), Java 가 몸 pitch 90 으로 얼굴을 아래로 돌린다. 틀 ±1.5 블록을 넘으므로 0.25 로 줄여 굽는다(엔진이 Look.scale 로 되돌림).

- gave:gabe/g28: 상태 ""(떠다님, 펄럭), "fold"(막대기를 접어 떨어짐), "splat"(바닥에 깔림: 막대기가 축 늘어져 사방으로).
- gave:gabe/g28_shadow(부품, 바닥 그림자): 상태 "s1".."s4" 짙기(아래 사람 수). 반투명 대신 가장자리로 갈수록 성긴 점묘(컷아웃)로
  짙기를 낸다. 지름 2 블록으로 굽고 Java 가 반지름 4 블록(피해 반경)이 되게 키운다.
"""
import math

import numpy as np

from gabe import audio, kit

W, D = 4.0, 0.5
ROD = dict(length=2.6, thick=0.24)
CHUTE = kit.Wave(amp=(16, 14), lag=55, bias=(4, 8), shape="flap")
CORNERS = [(1, 1), (-1, 1), (1, -1), (-1, -1)]


def body(s, mode):
    h = s.head(size=W, depth=D, face="smile")
    for (sx, sy) in CORNERS:
        root = h.point(sx * 0.93, sy * 0.93, -1)
        if mode == "":
            s.rod(root, (sx * 0.45, sy * 0.45, -1), length=ROD["length"], segs=2, thick=ROD["thick"], wave=CHUTE,
                  toward=(sx * 1.0, sy * 1.0, 0.2))
        elif mode == "fold":
            s.rod(root, (-sx * 0.12, -sy * 0.12, -1), length=ROD["length"] * 0.96, segs=2, thick=ROD["thick"])
        else:
            s.rod(root, (sx * 1.0, sy * 1.0, -0.12), length=ROD["length"] * 0.92, segs=2, thick=ROD["thick"])


BAYER = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def shadow_tex(density, n=128):
    im = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r >= 1:
                continue
            d = density * (1 - r ** 3) ** 0.7
            if BAYER[y % 4, x % 4] < d:
                v = 0.85 + 0.15 * math.sin(x * 0.7 + y * 0.45)
                im[y, x] = (int(46 * v), int(22 * v), int(32 * v), 255)
    return im


def build(k: kit.Kit):
    m = k.model(poses=6, period=24, shrink=0.25, mut2=dict(length=2.0, thick=0.2))
    for mode in ("", "fold", "splat"):
        body(m.state(mode), mode)
    sh = k.model("g28_shadow", poses=1, period=20, mut2=False, open=False, torn=False)
    for i, dens in enumerate((0.22, 0.4, 0.58, 0.78), start=1):
        t = k.texture(f"shadow{i}", shadow_tex(dens))
        sh.state(f"s{i}").quad((0, 0, 0), (2, 2), "up", t)
    sh.default = "s1"
    k.icon(model="g28", state="")


def _drone(seed):
    """'우우웅': 낮은 울림(음높이는 Java 가 내려올수록 낮춤)"""
    d = 1.4
    t = np.arange(int(d * audio.SR), dtype=np.float32) / audio.SR
    x = audio.tone(72 + seed * 3, d, "saw", vib=0.015, vib_rate=4)
    x = audio.lowpass(x, 380)
    beat = 0.7 + 0.3 * np.sin(2 * np.pi * 2.5 * t)
    hum = audio.tone(144 + seed * 6, d, vib=0.02, vib_rate=5) * 0.25
    return audio.normalize(audio.fade(audio.env(audio.mix(x * beat, hum), 0.25, 0.2, 0.85, 0.4), 0.05, 0.25), 0.9)


def _splat(seed):
    a = audio.thump(seed=800 + seed, dur=0.35, freq=64)
    b = audio.squish(seed=810 + seed, dur=0.4)
    c = audio.env(audio.bandpass(audio.noise(0.5, 820 + seed), 200, 1600), 0.002, 0.08, 0.3, 0.25)
    return audio.normalize(audio.fade(audio.mix(a, audio.gain(b, 0.8), audio.gain(c, 0.6))), 0.95)


def sounds(k: kit.Kit):
    k.sound("drone", "우우웅", synth=_drone, volume=1.0, attenuation=40)
    k.sound("fold", "막대기 접힘", vanilla=[("mob/phantom/flap1", 0.5), ("mob/phantom/flap2", 0.45)], volume=1.0, attenuation=32)
    k.sound("splat", "철퍼덕", synth=_splat, volume=1.0, attenuation=32)


def phrases():
    return {"cause_g28": "엎어진 얼굴에 깔렸다"}
