"""G39 사슬 늘어뜨린 게이브: 하늘의 거대한 뒤집힌 얼굴(2.2 블록, 옛 0.9 의 2.4 배, 우는 눈처럼 읽힘)이 떠서 아래를 내려다보고, 턱 가장자리에서
살이 녹아 흘러내리며(살 띠 여섯), 입에서 쇠사슬이 바닥 가까이 늘어진다. 사슬 마디마다 붉은 실핏줄이 감기고 끝에 주먹만 한 얼굴이 낚싯바늘처럼
매달려 빙글 돈다. 엔드 막대기 둘(원형 DNA, 3 블록 노)이 노 젓듯 앞뒤로 흔든다. 틀 ±1.5 블록을 넘으므로 몸은 0.45 로 줄여 굽는다.

- gave:gabe/g39: 상태 ""(노 젓기), "reel"(감아올림: 같은 주기에 두 번 저음).
- gave:gabe/g39_chain(부품, shrink 0.2): 세로 사슬 기둥. 상태 "c3", "c5", "c7", "c9"(기본), "c11", "c13" = 길이(블록). Java 가 지금 길이보다
  길거나 같은 가장 가까운 상태를 골라 세로로 조금 줄이고(마디가 덜 찌그러지게), 입 자리(MOUTH_Y, MOUTH_Z)에서 진자처럼 돌린다.
- gave:gabe/g39_hook(부품): 끝 얼굴. 상태 ""(실눈 웃음), "bite"(이빨 고리 입: 머리를 문 채).
"""
import math

import numpy as np

from gabe import audio, faces, kit

LENGTHS = (3, 5, 7, 9, 11, 13)
CHAIN_W = 0.34
K = 2.4  # 옛 0.9 블록 얼굴에서 키운 배
HEAD, DEPTH = 0.9 * K, 0.6 * K


def chain_tex(alt):
    """쇠사슬 한 블록 무늬(고리 둘: 앞에서 본 고리, 옆에서 본 고리) + 감긴 붉은 실핏줄. alt 면 반 칸 어긋난 판(엇갈린 두 판이 같은 높이에서
    한쪽은 고리 앞, 한쪽은 고리 옆을 그리게)"""
    im = np.zeros((16, 16, 4), np.uint8)

    def put(x, y, c):
        if 0 <= x < 16:
            im[y % 16, x] = (*c, 255)

    def ring(y0):
        for y in range(9):
            for x in range(16):
                dx, dy = (x - 7.5) / 3.6, (y - 4.0) / 4.7
                r = math.hypot(dx, dy)
                if 0.52 < r < 1.0:
                    lit = 0.5 + 0.5 * math.cos(math.atan2(dy, dx) + 2.2)
                    v = int(58 + 104 * lit)
                    put(x, y0 + y, (v, v, v + 8))

    def bar(y0):
        for y in range(9):
            put(7, y0 + y, (150, 150, 160))
            put(8, y0 + y, (84, 84, 94))

    if alt:
        bar(0)
        ring(8)
    else:
        ring(0)
        bar(8)
    # 실핏줄: 한 블록에 한 바퀴 감기는 붉은 실(두 판에서 90도 어긋남), 군데군데 맺힌 방울
    for y in range(16):
        a = 2 * math.pi * y / 16 + (math.pi / 2 if alt else 0)
        x = int(round(7.5 + 3.4 * math.sin(a)))
        if math.cos(a) > -0.35:  # 앞쪽으로 도는 동안만 보임
            put(x, y, (168, 28, 40) if y % 5 else (214, 60, 70))
    return faces._fill_rest(im)


def build(k: kit.Kit):
    # 얼굴(하늘에서 내려다봄: Java 가 pitch 로 숙임). 노: 양옆에서 옆 아래로, 앞뒤로 저음
    m = k.model(poses=6, period=40, shrink=0.45, mut2=dict(length=1.6, thick=0.2))
    for name, cycles in (("", 1), ("reel", 2)):
        s = m.state(name)
        h = s.head(size=HEAD, depth=DEPTH, face="flipped")
        for side in (1, -1):
            oar = kit.Wave(amp=(30, 24, 18), lag=45, bias=(-6, -4, 0), cycles=cycles)
            s.rod(h.point(side * 0.95, -0.1, -0.2), (side * 1.0, -0.3, 0.0), length=1.25 * K, segs=3, thick=0.11 * K, wave=oar,
                  toward=(0, 0, 1))
        # 턱 가장자리에서 녹아 흘러내리는 살 띠 여섯(뒤집힌 얼굴이라 턱은 아래 가장자리)
        for i, u in enumerate((-0.8, -0.45, -0.1, 0.25, 0.6, 0.9)):
            s.band(h.point(u, -1.0, 0.4 + 0.3 * (i % 2)), (0, -1, 0.1), length=0.5 + 0.25 * (i % 3), width=0.24, segs=2, thick=0.03,
                   wave=kit.Wave(amp=(4, 6), lag=40, phase=i * 60, cycles=cycles), toward=(0, 0, 1), tex="flesh", poses=4)
        # 입에서 삐져나온 사슬 첫 마디(부품 사슬과 이어짐: 끝이 Java MOUTH_Z). 뒤집힌 얼굴이라 입은 위쪽
        s.chain(h.point(0, 0.56, 1.0), h.point(0, 0.56, 1.75), links=2, sag=0.0, size=CHAIN_W * 0.8 * K,
                tex=k.texture("chain_a", chain_tex(False)))

    c = k.model("g39_chain", poses=1, period=20, shrink=0.2, mut2=False, open=False, torn=False)
    ta, tb = k.texture("chain_a", chain_tex(False)), k.texture("chain_b", chain_tex(True))
    for L in LENGTHS:
        s = c.state(f"c{L}")
        for i in range(L):
            y0, y1 = L / 2 - i - 1, L / 2 - i
            s.box((-CHAIN_W / 2, y0, 0), (CHAIN_W / 2, y1, 0), ta, uv=[0, 0, 16, 16], faces=("south", "north"))
            s.box((0, y0, -CHAIN_W / 2), (0, y1, CHAIN_W / 2), tb, uv=[0, 0, 16, 16], faces=("east", "west"))
    c.default = "c9"

    hk = k.model("g39_hook", poses=4, period=20, mut2=False, torn=False)
    for name, face in (("", "smile"), ("bite", "teeth_ring")):
        s = hk.state(name)
        h = s.head(size=0.42, depth=0.34, face=face)
        # 턱 밑 갈고리 둘(작은 막대기, 까딱임)
        for side in (1, -1):
            s.rod(h.point(side * 0.55, -1.0, 0.2), (side * 0.55, -0.7, 0.45), length=0.26, segs=1, thick=0.06,
                  wave=kit.Wave(amp=14, phase=90 * side), toward=(0, 0, 1), base=False)
        # 정수리에 물린 사슬 고리 하나
        s.chain(h.point(0, 1.0, 0), h.point(0, 1.9, 0), links=1, sag=0.0, size=CHAIN_W * 0.8, tex=ta)
    k.icon(model="g39", state="")


def _gurgle(seed):
    """'꼴꼴': 목구멍에서 올라오는 방울 소리(낮은 음이 짧게 솟는 방울 여럿) + 젖은 숨"""
    rng = np.random.RandomState(390 + seed)
    d = 1.1
    out = audio.silence(d)
    t = 0.02
    while t < d - 0.15:
        f = 150 + rng.rand() * 120
        b = audio.env(audio.tone(f, 0.075, glide=f * (1.7 + 0.5 * rng.rand())), 0.004, 0.02, 0.5, 0.03)
        out = audio.mix(out, audio.at(b * (0.5 + 0.5 * rng.rand()), t, d))
        t += 0.07 + rng.rand() * 0.09
    wet = audio.env(audio.bandpass(audio.noise(d, 395 + seed), 250, 900), 0.1, 0.2, 0.4, 0.4) * 0.25
    return audio.normalize(audio.fade(audio.mix(out, wet), 0.01, 0.15), 0.8)


def _reel(seed):
    """'드르륵': 줄 감기는 톱니 소리(짧은 딸깍이 빠르게 이어짐)"""
    rng = np.random.RandomState(400 + seed)
    d = 0.42
    out = audio.silence(d)
    t = 0.0
    while t < d - 0.03:
        c = audio.env(audio.bandpass(audio.noise(0.018, 401 + seed + int(t * 500)), 1400, 5200), 0.0005, 0.004, 0.3, 0.01)
        k2 = audio.env(audio.tone(520 + 60 * rng.rand(), 0.016, "square"), 0.0005, 0.004, 0.2, 0.008) * 0.3
        out = audio.mix(out, audio.at(audio.mix(c, k2), t, d))
        t += 0.032 + rng.rand() * 0.006
    return audio.normalize(audio.fade(out, 0.005, 0.03), 0.75)


def _moan(seed):
    """끌려 올라가는 사람의 신음 흉내(낮게 떨리는 '으으', 합성)"""
    f = 190 + 14 * seed
    v = audio.tone(f, 0.9, "saw", vib=0.06, vib_rate=6, glide=f * 0.82)
    out = audio.mix(audio.bandpass(v, 300, 620), audio.gain(audio.bandpass(v, 900, 1300), 0.3))
    return audio.normalize(audio.fade(audio.env(out, 0.08, 0.15, 0.7, 0.3), 0.02, 0.2), 0.7)


def _gulp(seed):
    """꿀꺽 삼킴: 아래로 미끄러지는 낮은 음 둘"""
    a = audio.env(audio.tone(260, 0.12, glide=120), 0.005, 0.03, 0.5, 0.05)
    b = audio.env(audio.tone(170, 0.2, glide=70), 0.005, 0.05, 0.5, 0.1)
    w = audio.env(audio.bandpass(audio.noise(0.4, 410 + seed), 200, 1200), 0.01, 0.1, 0.3, 0.2) * 0.4
    return audio.normalize(audio.fade(audio.mix(audio.cat(a, audio.silence(0.05), b), w)), 0.85)


def sounds(k: kit.Kit):
    # 이 종의 신호: 머리 위에서 끊이지 않는 꼴꼴(멀리 들림)
    k.sound("gurgle", "머리 위 꼴꼴", synth=_gurgle, volume=0.9, attenuation=32)
    k.sound("reel", "줄 감기는 드르륵", synth=_reel, volume=0.9, attenuation=24)
    k.sound("bite", "덥석 물림", vanilla=[("mob/slime/attack1", 0.7), ("mob/slime/attack2", 0.66)], synth=lambda i: audio.squish(seed=415 + i),
            attenuation=24)
    k.sound("moan", "끌려가는 신음", synth=_moan, volume=0.9, attenuation=32)
    k.sound("snap", "사슬이 끊김", vanilla=[("block/chain/break1", 0.7), ("block/chain/break3", 0.75), ("random/break", 0.6)], attenuation=24)
    k.sound("gulp", "꿀꺽 삼킴", synth=_gulp, volume=1.0, attenuation=24)
    k.sound("murmur", "꼴꼴 중얼거림", synth=lambda i: audio.mix(_gurgle(20 + i), audio.gain(_moan(3 + i), 0.5)), volume=0.8, attenuation=32)


def phrases():
    return {"cause_g39": "사슬 끝 얼굴에 물렸다", "cause_g39_fall": "끌려 올라가다 떨어졌다", "notice_g39_ate": "사슬에 끌려 올라갔다",
            "notice_g39_murmur": "중얼거림이 들린다"}
