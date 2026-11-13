"""G98 물러나는 초롱 게이브: 사람 머리 높이에 뜬 금빛 작은 얼굴 초롱(0.5 블록, 금빛 홍조, 가는 막대기 창살 넷과 뚜껑)이 까딱인다.
초롱 아래에서 엔드 막대기 하나가 낚싯대처럼 활 모양으로 뒤로 휘어 땅속으로 들어가 있고, 땅속에는 3.5 블록 얼굴 입이 묻혀 따라온다(땅 위로는
흙 두둑과 등지느러미처럼 비죽 나온 둘째 막대기만).

- gave:gabe/g98(닻 = 초롱, shrink 0.8, Java hover 1.6): 상태 "lure"(낚싯대 휘청, 자세 4, 기본), "still"(멈춤: 낚싯대가 굳음).
- gave:gabe/g98_mouth(부품, shrink 0.5, 세계 축, 원점 = 두둑 밑 바닥): 상태 "mound"(흙 두둑 + 등 막대기), "bite"(솟은 입: 하늘을 보는
  3.5 블록 얼굴, 이빨 고리 입, 둘레 흙덩이).
소리 jingle(짤랑이는 초롱, 24), rise(땅이 솟음, 32), bite(24), slap(초롱 후려치기, 16), snap(낚싯대 끊김, 24). 글귀 cause_g98, cause_g98_slap,
notice_g98_cut.
"""
import numpy as np

from gabe import faces, kit

LANTERN = 0.5


def soil_tex(seed=98, n=16):
    rng = np.random.RandomState(seed)
    base = np.array([112, 86, 62], float)
    col = base[None, None, :] * (0.8 + 0.32 * rng.rand(n, n, 1))
    col[rng.rand(n, n) < 0.1] = (78, 58, 42)
    col[rng.rand(n, n) < 0.05] = (150, 122, 92)
    a = np.zeros((n, n, 4), np.uint8)
    a[..., :3] = np.clip(np.round(col), 0, 255)
    a[..., 3] = 255
    return a


def brass_tex(n=8):
    rng = np.random.RandomState(7)
    a = np.zeros((n, n, 4), np.uint8)
    a[..., :3] = np.clip(np.array([196, 150, 62], float)[None, None, :] * (0.85 + 0.25 * rng.rand(n, n, 1)), 0, 255)
    a[..., 3] = 255
    return a


def lantern(s, k, wave):
    brass = k.texture("brass", brass_tex())
    s.head(size=LANTERN, depth=LANTERN, face="gold")
    # 초롱 창살 넷(가는 막대기)과 뚜껑, 바닥 받침
    for x in (-0.3, 0.3):
        for z in (-0.3, 0.3):
            s.rod((x, -0.34, z), (0, 1, 0), length=0.68, segs=1, thick=0.05, toward=(1, 0, 0), base=False, rag=False)
    s.box((-0.36, 0.33, -0.36), (0.36, 0.39, 0.36), brass)
    s.box((-0.12, 0.39, -0.12), (0.12, 0.47, 0.12), brass)
    s.box((-0.34, -0.4, -0.34), (0.34, -0.34, 0.34), brass)
    # 낚싯대: 초롱 밑에서 뒤로 솟았다가 활처럼 휘어 땅속으로(끝은 Java 의 입 두둑)
    s.rod((0, -0.4, -0.05), (0, 0.35, -1), length=2.7, segs=3, thick=0.1, toward=(0, -1, 0), wave=wave, poses=4 if wave.amp else 1,
          base=False, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, shrink=0.8, mut2=False)
    lantern(m.state("lure"), k, kit.Wave(amp=(3, 5, 7), lag=50, bias=(0, 55, 60)))
    lantern(m.state("still"), k, kit.Wave(amp=0, bias=(0, 55, 60)))
    m.default = "lure"

    soil = k.texture("soil", soil_tex())
    mo = k.model("g98_mouth", poses=4, period=20, shrink=0.5, mut2=False, torn=False, rag=False)
    s = mo.state("mound")
    s.box((-0.9, -0.1, -0.6), (0.9, 0.22, 0.6), soil)
    s.box((-0.6, 0.2, -0.4), (0.6, 0.4, 0.4), soil)
    s.box((-0.3, 0.38, -0.2), (0.3, 0.5, 0.2), soil)
    # 흙 틈으로 살짝 보이는 살빛 이마
    s.box((-0.35, 0.36, 0.38), (0.35, 0.44, 0.44), "flesh", face=True)
    # 등지느러미처럼 비죽 나온 둘째 막대기(땅을 가르며 까딱)
    s.rod((0, 0.42, -0.25), (0, 1, -0.7), length=1.0, segs=2, thick=0.1, toward=(0, 0, 1), wave=kit.Wave(amp=(8, 6), lag=40), poses=4)
    s.front = (0, 0, 0)

    b = mo.state("bite")
    b.head(size=3.4, depth=1.3, at=(0, 0.45, 0), face=faces.Face(eyes="open", mouth="teeth_ring", blush="deep"), rot=(-90, 0, 0))
    b.teeth((0, 1.12, 0), 1.25, count=14, axis=(0, 1, 0), length=0.22, size=0.1, inward=0.4)
    for i, (x, z) in enumerate(((1.9, 0.3), (-1.9, -0.2), (0.3, 1.9), (-0.4, -1.9), (1.4, 1.4), (-1.4, 1.3))):
        b.box((x - 0.3, -0.1, z - 0.3), (x + 0.3, 0.32, z + 0.3), soil, rot=(0, 25 * i, 12))
    b.rod((0, 0.9, -1.3), (0, 1, -0.5), length=1.0, segs=2, thick=0.1, toward=(0, 0, 1), wave=kit.Wave(amp=(14, 10), lag=40, cycles=2),
          poses=4)
    b.front = (0, 1, 0)
    mo.default = "mound"
    k.icon(model="g98", state="lure")


def sounds(k: kit.Kit):
    k.sample("jingle", ["kenney_rpg/handlecoins", "kenney_rpg/handlecoins2", {"id": "kenney_rpg/handlecoins", "pitch": 1.25}],
             "짤랑이는 초롱", pitch=1.1, attenuation=24)
    k.sample("rise", ["oga_breaking75/bfh1_rock_breaking_01", "oga_breaking75/bfh1_rock_breaking_02", {"id": "oga_quake/shake", "end": 1.6}],
             "발밑이 솟음", pitch=0.7, attenuation=32)
    k.sample("bite", "tag:eat", "큰 입이 물었다", pitch=0.6, attenuation=24)
    k.sample("slap", "tag:punch", "초롱이 후려침", pitch=1.1, attenuation=16)
    k.sample("snap", "oga_breaking75/bfh1_wood_breaking_0*", "낚싯대가 끊김", pitch=1.2, attenuation=24)


def phrases():
    return {"cause_g98": "초롱 밑 입에 물렸다", "cause_g98_slap": "초롱에 낚였다", "notice_g98_cut": "초롱을 떨궜다"}
