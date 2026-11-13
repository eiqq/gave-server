"""G14 상점 갉는 게이브: 개만 한 쥐 꼴. 0.7 얼굴(점 입이 앞니 두 개만큼 넓어진 그림)이 머리이고, 뒤로 털가죽 몸통(뒤통수 머리카락 무늬),
짧은 살 다리 넷이 버둥거리며, 엉덩이에서 긴 꼬리 둘(원형 DNA 엔드 막대기)이 채찍질한다. 사람을 무시하고 상점으로 곧장 달리므로 사람은
주로 옆모습(긴 몸과 꼬리)을 본다. 허리 높이.

상태: ""(달림: 다리 넷이 대각선끼리 엇갈려 버둥, 꼬리 둘이 빠르게 채찍질), "gnaw"(상자에 얼굴을 박고 갉음: 얼굴이 6틱에 한 번 앞뒤로 콕콕,
다리는 버티며 움찔, 꼬리는 느리게).
앞니는 얼굴 위 덧그림(먼 32, 가까운 128, 화소 부류 입 3 과 이 7 을 새겨 무표정 팔레트가 먹게). 꼬리 이음점이 1.5 블록 틀 밖이라 0.75 로 줄여
굽는다(엔진이 Look.scale 로 되돌림).
"""
import numpy as np

from gabe import audio, faces, kit

HEAD = 0.7
DEPTH = 0.5
HEAD_Z = 0.22  # 머리를 몸 가운데보다 앞으로(몸통은 뒤로)
# 다리 넷: (옆, 앞뒤, 위상) 대각선끼리 같은 위상
LEGS = ((0.24, -0.15, 0), (-0.24, -0.15, 180), (0.24, -0.9, 180), (-0.24, -0.9, 0))


def teeth_canvas(n=32):
    """넓어진 입과 앞니 둘(32 기준 그림을 n 으로 키움). 나머지 투명"""
    c = faces.Canvas(32)
    for x in range(13, 23):
        for y in range(23, 27):
            edge = x in (13, 22) or y == 23
            c.put(x, y, "M" if edge else "m")
    for x0 in (15, 18):
        for x in range(x0, x0 + 3):
            for y in range(24, 30):
                c.put(x, y, "T" if (y < 28 or x != x0 + 2) else "t")
        for y in range(24, 30):
            c.put(x0 + 2 if x0 == 15 else x0, y, "t")
    c.put(17, 29, "m")
    c.put(18, 29, "t")
    a = c.rgba()
    if n == 32:
        return a
    k = n // 32
    big = np.repeat(np.repeat(a, k, axis=0), k, axis=1).copy()
    # 가까운 그림: 이 사이 틈, 잇몸 선, 이 끝 그늘
    for y in range(24 * k, 30 * k):
        for x in (18 * k - 1, 18 * k):
            big[y, x, :3] = (66, 20, 20)
            big[y, x, 2] = (big[y, x, 2] & 0xF8) | 3
    for x in range(15 * k, 21 * k):
        y = 24 * k
        if big[y, x, 3]:
            big[y, x, :3] = (176, 84, 92)
            big[y, x, 2] = (big[y, x, 2] & 0xF8) | 3
    return big


def body(s, k, teeth32, teeth128, at=(0, 0, 0), scrape=None):
    h = s.head(size=HEAD, depth=DEPTH, at=at, face="smile")
    ep = 1.0 / 16
    z = at[2] + DEPTH / 2 + 0.34 * ep
    s.quad((at[0], at[1], z), (HEAD, HEAD), "south", teeth32, dist="far", face=True)
    s.quad((at[0], at[1], z + 0.04 * ep), (HEAD, HEAD), "south", teeth128, dist="near", face=True)
    return h


def rat(s, k, gnawing):
    """털가죽 몸통 + 다리 넷 + 꼬리 둘(머리는 상태마다 따로)"""
    hair = k.skin_tex("back")
    flesh = k.part_tex("flesh")
    s.box((-0.3, -0.34, -1.05), (0.3, 0.16, -0.02), {"up": hair, "east": hair, "west": hair, "north": flesh, "down": flesh},
          faces=("up", "east", "west", "north", "down"), uv=[0, 0, 16, 16], face=True)
    for (x, z, ph) in LEGS:
        if gnawing:
            w = kit.Wave(amp=(5,), lag=0, shape="twitch", phase=ph)
        else:
            w = kit.Wave(amp=(30,), lag=0, phase=ph, cycles=2)
        s.rod((x, -0.3, z), (0.3 if x > 0 else -0.3, -1.0, 0.1), length=0.48, segs=1, thick=0.09, tex="flesh", wave=w, toward=(0, 0, 1),
              poses=4)
    for side in (1, -1):
        w = kit.Wave(amp=(18, 24), lag=60, phase=0 if side > 0 else 180, cycles=1 if gnawing else 2)
        s.rod((side * 0.1, -0.08, -1.04), (side * 0.35, 0.1, -1.0), length=1.15, segs=2, thick=0.07, wave=w, toward=(1, 0, 0))


def build(k: kit.Kit):
    m = k.model(poses=6, period=12, shrink=0.75)
    m.mut2 = dict(root=(0.2, 0.3, -0.5), dir=(0.4, 1.0, -0.6), length=0.5)
    t32 = k.texture("teeth", teeth_canvas(32), classes=True)
    t128 = k.texture("teeth_n", teeth_canvas(128), classes=True)

    s = m.state("")
    body(s, k, t32, t128, at=(0, 0, HEAD_Z))
    rat(s, k, False)

    g = m.state("gnaw", poses=4, period=6)
    for fr in g.frames():
        with fr:
            dz = (0.0, 0.05, 0.09, 0.04)[fr.k]
            dy = (0.0, -0.015, -0.03, -0.01)[fr.k]
            body(g, k, t32, t128, at=(0, dy, HEAD_Z + dz))
    rat(g, k, True)
    k.icon(model="g14", state="")


def gnaw(seed=0, dur=0.6):
    """사각사각: 마른 나무를 앞니로 빠르게 긁는 소리"""
    rng = np.random.RandomState(seed)
    parts = []
    t = 0.0
    while t < dur:
        c = audio.env(audio.bandpass(audio.noise(0.035, seed * 50 + int(t * 1000)), 2200, 7500), 0.002, 0.012, 0.3, 0.015)
        parts.append(audio.at(audio.gain(c, 0.6 + 0.4 * rng.rand()), t))
        t += 0.055 + rng.rand() * 0.025
    wood = audio.env(audio.lowpass(audio.noise(dur, seed + 9), 700), 0.02, 0.1, 0.3, 0.1) * 0.25
    return audio.normalize(audio.mix(wood, *parts), 0.7)


def scurry(seed=0):
    return audio.normalize(audio.lowpass(audio.chitter(seed=seed, dur=0.35, rate=18, base=700), 2600), 0.5)


def sounds(k: kit.Kit):
    k.sound("gnaw", "상점에서 사각사각", synth=lambda i: gnaw(200 + i), attenuation=32)
    k.sound("scurry", "다다닥 달림", synth=lambda i: scurry(210 + i), vanilla=[("mob/silverfish/step1", 0.8)], volume=0.6, attenuation=16)


def phrases():
    return {"cause_g14": "상점을 비워 뒀다", "notice_g14_gnawed": "상점 칸을 갉았다"}
