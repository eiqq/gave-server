"""G38 업은 게이브: 가오리 게이브 꼴(0.8 블록 얼굴, 엔드 막대기 둘)인데 점 입이 귀까지 찢어져 활짝 웃고, 두 막대기를 뒤 위로 벌려
그 사이에 거미줄 그물을 치고 방금 죽은 사람 머리를 동여매 업는다.

모델 gave:gabe/g38 상태: ""(펄럭임), "charge"(박치기 돌진: 같은 주기에 두 번 침). 부위 "load"(머리를 업음: 머리를 감싼 거미줄 바구니가 보임).
모델 gave:gabe/g38_head(부품): 업힌 머리 자리. Java 가 죽은 사람 머리(플레이어 머리 아이템)로 바꿔 싣고, '내 얼굴 쓰지 않기'거나 주인이 없으면
이 그림(이목구비가 바랜 게이브 얼굴)을 탈색 팔레트로 그대로 쓴다.
"""
import math

import numpy as np

from gabe import audio, faces, kit

FIN = dict(length=1.15, segs=3, thick=0.11)
# 업힌 머리 가운데(몸 기준: 뒤통수 위 뒤). Java G38.HEAD_AT 와 같게
HEAD_AT = (0.0, 0.5, -0.6)
CAGE = 0.29


def body(s, cycles):
    # 이미 귀까지 찢어진 입이라 변이 1 오버레이(입 찢어짐)는 끈다
    h = s.head(size=0.8, depth=0.55, face="torn", torn=False)
    chains = []
    for side in (1, -1):
        root = h.point(side * 0.9, 0.2, -0.7)
        d = (side * 0.72, 0.6, -0.5)
        w = kit.Wave(amp=(14, 14, 16) if cycles == 1 else (22, 20, 20), lag=50, bias=(-4, 2, 4), shape="flap", cycles=cycles)
        chains.append(s.rod(root, d, wave=w, **FIN))
    # 두 막대기 사이 거미줄(등에 친 그물, 막대기를 따라 출렁임)
    s.membrane(chains[0], chains[1], tex="web")
    # 머리를 동여맨 거미줄 바구니(머리를 업었을 때만): 옆 넷과 위, 아래
    x, y, z = HEAD_AT
    with s.when("on:load"):
        s.box((x - CAGE, y - CAGE, z - CAGE), (x + CAGE, y + CAGE, z + CAGE), "web", uv=[0, 0, 16, 16],
              faces=("north", "south", "east", "west", "up"))
        # 얼굴 뒤통수에서 바구니로 이어진 동여맨 줄 둘
        for side in (1, -1):
            s.band(h.point(side * 0.5, 0.9, -0.9), (-side * 0.35, 0.55, -0.75), length=0.42, width=0.1, segs=1, thick=0.02, tex="web",
                   toward=(0, 0, 1), face=False)


def build(k: kit.Kit):
    m = k.model(poses=8, period=20, torn=False)
    body(m.state(""), 1)
    body(m.state("charge"), 2)
    hd = k.model("g38_head", poses=1, period=20, mut2=False, open=False, torn=False)
    hd.state("").head(size=0.5, depth=0.5, face=faces.Face(eyes="half", mouth="none", blush="none", tufts=False), near=False)
    k.icon(model="g38", state="")


def _sob(seed):
    """'흐흑': 숨이 끊기며 우는 소리 둘(높게 들이쉬고, 떨며 내려감)"""
    rng = np.random.RandomState(380 + seed)
    f = 430 + 25 * seed + rng.randint(-10, 10)
    a = audio.tone(f * 1.12, 0.16, "saw", vib=0.03, vib_rate=11, glide=f * 1.2)
    b = audio.tone(f, 0.42, "saw", vib=0.05, vib_rate=9, glide=f * 0.62)
    def voice(x, s):
        v = audio.mix(audio.bandpass(x, 500, 950), audio.gain(audio.bandpass(x, 1100, 1700), 0.45))
        return audio.mix(v, audio.gain(audio.bandpass(audio.noise(len(x) / audio.SR, s), 1800, 4200), 0.12))
    a = audio.env(voice(a, seed), 0.02, 0.04, 0.6, 0.06)
    b = voice(b, seed + 5)
    shake = 0.65 + 0.35 * np.abs(np.sin(np.linspace(0, 2.5 * math.pi, len(b), dtype=np.float32)))
    b = audio.env(b * shake, 0.02, 0.08, 0.7, 0.22)
    out = audio.cat(a, audio.silence(0.05), b)
    return audio.normalize(audio.fade(out, 0.01, 0.12), 0.8)


def _whimper(seed):
    """멀리서 들리는 흐느낌(업힌 머리가 쉬지 않고 우는 낮은 소리 셋)"""
    parts = []
    t = 0.0
    total = 1.5
    for i in range(3):
        f = 330 + 18 * seed - 22 * i
        v = audio.tone(f, 0.26, "saw", vib=0.05, vib_rate=8, glide=f * 0.8)
        v = audio.mix(audio.bandpass(v, 420, 800), audio.gain(audio.bandpass(v, 950, 1400), 0.4))
        parts.append(audio.at(audio.env(v, 0.03, 0.06, 0.6, 0.14), t, total))
        t += 0.42 + 0.05 * i
    return audio.normalize(audio.fade(audio.mix(*parts), 0.02, 0.2), 0.7)


def _lift(seed):
    """머리를 들어 올려 동여맴: 젖은 것이 끌려 올라가고 줄이 조여짐"""
    a = audio.squish(seed=385 + seed, dur=0.4)
    r = audio.env(audio.bandpass(audio.noise(0.5, 388 + seed), 900, 2600), 0.08, 0.1, 0.5, 0.25)
    creak = audio.env(audio.tone(180, 0.35, "tri", vib=0.2, vib_rate=31, glide=260), 0.05, 0.05, 0.5, 0.15) * 0.35
    return audio.normalize(audio.fade(audio.mix(a, audio.at(audio.gain(r, 0.5), 0.15), audio.at(creak, 0.3))), 0.85)


def sounds(k: kit.Kit):
    # 이 종의 신호: 맞을 때마다 업힌 머리가 '흐흑' 운다(엔진이 울리는 hurt 사건을 이 소리로)
    k.sound("hurt", "흐흑 우는 소리", synth=_sob, volume=1.0, attenuation=16)
    # 떠도는 동안 업힌 머리의 흐느낌(멀리서 방향을 알림)
    k.sound("whimper", "업힌 머리의 흐느낌", synth=_whimper, volume=0.85, attenuation=32)
    k.sound("lift", "머리를 업는 소리", synth=_lift, volume=0.9, attenuation=24)
    k.sound("bump", "철썩 박치기", vanilla=[("mob/slime/big1", 0.8), ("mob/slime/big3", 0.75)], synth=lambda i: audio.squish(seed=390 + i),
            attenuation=16)


def phrases():
    return {"cause_g38": "업은 게이브에 박혔다", "notice_g38_carry": "머리를 업고 갔다"}
