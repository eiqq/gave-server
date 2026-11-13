"""G27 겹얼굴 게이브: 거대한 덩어리(1.5 배). 얼굴 2~5개가 녹은 밀랍처럼 서로 반쯤 파묻힌 혹 덩어리(폭 2.1, 키 2.4). 겹친 자리는 눈이 넷,
거꾸로 박힌 얼굴은 입이 이마에 온다. 이음매에 분홍 살 띠가 흘러내리고(맥박: 자세 4), 덩어리 아래로 녹아 흘러내린 촛농 같은 엔드 막대기
둘(원형 DNA)이 늘어져 흔들린다.

- gave:gabe/g27: 상태 ""(얼굴 둘), "f3", "f4", "f5"(얼굴 수). 본체 얼굴(앞 가운데, +Z)만 눈을 뜨고 있다: 늘 반쯤 뜬 눈이고(도감 '단 하나만 뜬 눈',
  어느 얼굴이 본체인지 알아보는 신호), 표적을 찍으면 다 뜬다(실눈 뜸 오버레이는 본체에만).
  나머지 얼굴은 모두 실눈: 뒤집힌 얼굴(입이 이마), 바늘땀, 눈물, 기운 실눈. 틀 ±1.5 블록을 넘으므로 0.6 으로 줄여 굽는다(엔진이 Look.scale 로
  되돌림). 머리 높이 1.8(Java HEAD_H)에서 촛농 막대기 끝이 바닥 위.
- gave:gabe/g27_eaten(부품): 웃지 않는 얼굴(먹힌 사람 자리, 감은 눈에 입 없음). Java 가 그 사람 머리(플레이어 머리 아이템)로 바꿔 싣고,
  '내 얼굴 쓰지 않기'면 이 그림 그대로.
"""
import math

from gabe import audio, faces, kit
import numpy as np

K = 1.5  # 옛 덩어리에서 키운 배
CORE = 0.74 * K
SEAM = kit.Wave(amp=(10, 14, 16), lag=50, bias=(8, 12, 14))
DRIP = kit.Wave(amp=(10, 16), lag=50, bias=(0, 8))
# 덧붙는 얼굴: (자리, 크기, 회전, 처방)(옛 크기, K 배로 씀)
EXTRA = [
    ((0.36, 0.22, -0.16), 0.66, (8, 38, 18), "flipped"),
    ((-0.38, -0.16, -0.12), 0.62, (-6, -40, -22), "stitch"),
    ((0.02, 0.4, -0.3), 0.58, (-38, 8, 10), "tears"),
    ((-0.08, -0.42, -0.3), 0.6, (36, -12, -8), "smile_c"),
]
SEAMS = [((0.33, 0.3, 0.33), (0.15, -1, 0.25)), ((-0.34, -0.04, 0.31), (-0.15, -1, 0.3)), ((0.02, 0.37, 0.22), (0.05, -1, 0.5))]


def lump(s, n):
    core = s.head(size=CORE, depth=0.56 * K, at=(0, 0, 0.12 * K), face="half")
    for i in range(n - 1):
        c, sz, rot, fc = EXTRA[i]
        s.head(size=sz * K, depth=sz * 0.75 * K, at=tuple(np.array(c) * K), face=fc, rot=rot, open=False, torn=False)
    # 이음매 살 띠(흘러내림, 맥박)
    for j in range(min(n, 3)):
        root, d = SEAMS[j]
        s.band(tuple(np.array(root) * K), d, length=0.46 * K, width=0.14 * K, segs=3, thick=0.03, wave=SEAM, toward=(0, 0, 1), tex="flesh",
               poses=4)
    # 원형 DNA: 덩어리 아래로 녹아 흘러내린 촛농 막대기 둘(느리게 흔들림)
    for side in (1, -1):
        w = kit.Wave(amp=DRIP.amp, lag=DRIP.lag, bias=DRIP.bias, phase=0 if side > 0 else 120)
        s.rod(core.point(side * 0.55, -1.0, -0.6), (side * 0.25, -1.0, -0.2), length=0.9, segs=2, thick=0.12, wave=w, toward=(side, 0, 0),
              poses=6)


def build(k: kit.Kit):
    m = k.model(poses=6, period=24, shrink=0.6, mut2=dict(length=1.0, thick=0.11))
    for name, n in (("", 2), ("f3", 3), ("f4", 4), ("f5", 5)):
        lump(m.state(name), n)
    e = k.model("g27_eaten", poses=1, period=20, mut2=False, open=False, torn=False)
    e.state("").head(size=0.5, depth=0.5, face=faces.Face(eyes="squint", mouth="none", blush="none", tufts=False, tilt=-14.0))
    k.icon(model="g27", state="f4")


def _chorus(seed):
    """어긋난 웃음 합창: 낮은 '하하' 셋이 음높이를 조금씩 달리해 겹침(정예 한 종만 웃음)"""
    d = 1.2
    out = audio.silence(d)
    rng = np.random.RandomState(700 + seed)
    for v, base in enumerate((300, 330, 362)):
        t = 0.02 * v
        while t < d - 0.2:
            f = base * (1 + 0.03 * rng.randn())
            h = audio._vowel(f, 0.11, ((650, 1.0), (1100, 0.5), (2500, 0.2)), seed + v * 7 + int(t * 50))
            h = audio.env(h, 0.006, 0.03, 0.5, 0.05)
            out = audio.mix(out, audio.at(h * (0.8 - 0.15 * v), t, d))
            t += 0.16 + 0.02 * v
    return audio.normalize(audio.fade(out, 0.01, 0.15), 0.8)


def _cry(seed):
    """맞으면 우는 소리: 흐느끼는 모음이 아래로 미끄러짐"""
    d = 0.7
    f = 420 + seed * 30
    v = audio.tone(f, d, "saw", vib=0.04, vib_rate=9, glide=f * 0.62)
    out = audio.mix(audio.bandpass(v, 500, 900), audio.gain(audio.bandpass(v, 1000, 1500), 0.5))
    sob = 0.6 + 0.4 * np.abs(np.sin(np.linspace(0, 3 * math.pi, len(out), dtype=np.float32)))
    return audio.normalize(audio.fade(audio.env(out * sob, 0.02, 0.1, 0.7, 0.25), 0.01, 0.15), 0.85)


def _melt(seed):
    """녹아 붙음: 끈적한 찌그러짐이 길게"""
    a = audio.squish(seed=730 + seed, dur=0.5)
    b = audio.env(audio.lowpass(audio.noise(0.6, 740 + seed), 500), 0.05, 0.1, 0.5, 0.3)
    return audio.normalize(audio.fade(audio.mix(a, audio.gain(b, 0.5))), 0.85)


def sounds(k: kit.Kit):
    k.sound("chorus", "겹친 웃음 합창", synth=_chorus, volume=0.9, attenuation=24)
    k.sound("hurt", "우는 소리", synth=_cry, volume=0.9, attenuation=16)
    k.sound("melt", "녹아 붙는 소리", synth=_melt, volume=1.0, attenuation=16)
    k.sound("lean", "혹이 한쪽으로 쏠림", vanilla=[("block/honeyblock/slide2", 0.7), ("block/honeyblock/slide3", 0.65)], volume=0.9,
            attenuation=16)


def phrases():
    return {"cause_g27": "녹아 붙었다"}
