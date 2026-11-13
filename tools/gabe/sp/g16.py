"""G16 내리꽂는 게이브: 하늘을 도는 살 우산. 하늘을 보고 누운 갓 얼굴(얼굴이 위)에 젖은 살 천이 우산처럼 넓게 벌어지고, 천 밑으로 우산살 여덟,
가운데 손잡이 대와 그 끝의 굽은 갈고리 둘(원형 DNA 엔드 막대기, 까딱)이 달렸다. 올려다보면 갓 밑면은 분홍 살 주름, 그 둘레로 살 천과 우산살.
배운 신호가 늘 때마다 갓 위에 기워 붙인 가죽 조각이 는다(부위 patch1, patch2).

모델 gave:gabe/g16 상태:
  ""     선회(천이 숨 쉬듯 벌어졌다 오므라듦, 갈고리 까딱)
  "fold" 낙하(우산을 접음: 천을 아래로 모으고 살은 숨기고 대가 길어짐: 실루엣이 가는 송곳으로 바뀜)
  "hug"  머리를 덮음(천이 아래로 늘어져 머리 옆을 감싸고 짧은 살이 아래로, 대와 갈고리는 없음: Java 가 사람 눈 + 0.24 에 앉힘)
  "stuck" 빗나가 땅에 박힘(Java 가 pitch 180 으로 뒤집음: 얼굴이 땅, 대가 위로 솟은 거꾸로 꽂힌 우산, 갈고리가 움찔)
대 끝 갈고리 이음점이 1.5 블록 틀 밖이라 0.75 로 줄여 굽는다(엔진이 Look.scale 로 되돌림).
모델 gave:gabe/g16_shadow: 표적 발밑 분홍 그림자 원(판 하나, Java 가 연출 디스플레이에 실어 크기를 줄임).
덮개 g16_cap: 덮인 사람 화면(살 주름 + 코앞 실눈 얼굴).
"""
import math

import numpy as np
from PIL import Image

from gabe import audio, faces, kit

CAP = 0.9  # 갓 폭
DEPTH = 0.42  # 갓 두께(얼굴 상자 깊이, 회전 뒤 위아래)
BOT = -DEPTH / 2 - 0.004  # 갓 밑면
# 상태마다: 천(벌림, 길이, 물결), 우산살(벌림 또는 None, 길이), 대 길이(0 = 없음), 갈고리 물결
OPEN = kit.Wave(amp=(6, 10), lag=50, bias=(0, 6))
SPOKE = (1.0, 0.92)
CANOPY = {
    "": dict(skirt=(1.35, 0.72, OPEN), spoke=SPOKE, shaft=1.0, hook=kit.Wave(amp=(10, 14), lag=50, bias=(40, 70))),
    "fold": dict(skirt=(0.08, 0.5, None), spoke=None, shaft=1.25, hook=kit.Wave(amp=(4, 6), lag=50, bias=(30, 50))),
    "hug": dict(skirt=(0.22, 0.75, kit.Wave(amp=(4, 6), lag=30, bias=(0, 2), cycles=2)), spoke=(0.3, 0.45), shaft=0, hook=None),
    "stuck": dict(skirt=(1.2, 0.68, kit.Wave(amp=(6, 10), lag=30, shape="twitch", cycles=2)), spoke=SPOKE, shaft=1.0,
                  hook=kit.Wave(amp=(18, 26), lag=40, bias=(40, 70), shape="twitch", cycles=2)),
}


def _rgba(arr):
    return np.clip(np.round(arr), 0, 255).astype(np.uint8)


def folds_tex(n=32, seed=3):
    """갓 밑면: 가운데로 모이는 분홍 살 주름(어두운 골, 밝은 능선, 젖은 반짝임)"""
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    c = (n - 1) / 2
    dx, dy = (xx - c) / c, (yy - c) / c
    r = np.hypot(dx, dy)
    a = np.arctan2(dy, dx)
    ridge = np.sin(a * 11 + np.sin(r * 7.0) * 1.6)
    base = np.array([196, 98, 108], np.float32)
    hi = np.array([236, 150, 152], np.float32)
    lo = np.array([120, 40, 52], np.float32)
    t = (ridge * 0.5 + 0.5)[..., None]
    col = lo * (1 - t) + hi * t
    col = col * 0.55 + base * 0.45
    col *= (1.05 - 0.35 * np.clip(r, 0, 1))[..., None]
    rng = np.random.RandomState(seed)
    col += rng.randn(n, n, 1) * 4
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = col
    out[..., 3] = 255
    # 가운데 입구(어두운 구멍)와 젖은 점
    out[r < 0.16, :3] = (70, 18, 26)
    for (x, y) in ((9, 11), (21, 8), (24, 22), (11, 24), (16, 5)):
        out[y, x, :3] = (255, 226, 226)
    return _rgba(out)


def patch_tex(face, seed):
    """기워 붙인 가죽 조각: 다른 게이브 얼굴 한 귀퉁이 + 가장자리 적갈색 바늘땀"""
    full = faces.far(face).astype(np.float32)
    rng = np.random.RandomState(seed)
    x0, y0 = int(rng.randint(2, 12)), int(rng.randint(8, 14))
    piece = full[y0:y0 + 16, x0:x0 + 16].copy()
    n = piece.shape[0]
    for i in range(n):
        for (x, y) in ((i, 0), (i, n - 1), (0, i), (n - 1, i)):
            if (i + seed) % 3 != 2:
                piece[y, x, :3] = (88, 30, 30)
        if i % 4 == 1:
            for (x, y) in ((i, 1), (i, n - 2), (1, i), (n - 2, i)):
                piece[y, x, :3] = (110, 40, 38)
    piece[..., 3] = 255
    return _rgba(piece)


def shadow_tex(n=32):
    """분홍 그림자 원: 가운데는 짙고 가장자리로 갈수록 성긴 점(컷아웃이라 반투명 대신 디더)"""
    out = np.zeros((n, n, 4), np.uint8)
    bayer = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r > 1.0:
                continue
            dens = 0.95 if r < 0.55 else max(0.0, 0.95 - (r - 0.55) / 0.45)
            ring = 0.82 < r < 0.94
            if dens > bayer[y % 4, x % 4] or ring:
                col = (232, 96, 136) if ring else (176, 62, 98)
                out[y, x] = (*col, 255)
    return out


def cover_img(W=480, H=270):
    """덮인 사람 화면: 사방에서 가운데로 모이는 살 주름, 코앞에 실눈 얼굴(가까운 얼굴), 가장자리 어둠"""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    dx, dy = (xx - W / 2) / (W / 2), (yy - H / 2) / (H / 2)
    r = np.hypot(dx, dy * 0.75)
    a = np.arctan2(dy, dx)
    ridge = np.sin(a * 18 + np.sin(r * 9.0) * 2.2 + r * 3.0)
    t = (ridge * 0.5 + 0.5)[..., None]
    col = np.array([110, 34, 46], np.float32) * (1 - t) + np.array([226, 128, 136], np.float32) * t
    col *= (0.55 + 0.45 * np.clip(1.25 - r, 0, 1))[..., None]
    img = Image.fromarray(_rgba(np.concatenate([col, np.full((H, W, 1), 255, np.float32)], -1)))
    face = Image.fromarray(faces.near(faces.preset("smile")))
    s = int(H * 0.9)
    face = face.resize((s, s), Image.BICUBIC)
    fa = np.asarray(face, np.float32)
    fy, fx = np.mgrid[0:s, 0:s].astype(np.float32)
    fr = np.hypot((fx - s / 2) / (s / 2), (fy - s / 2) / (s / 2))
    mask = np.clip((1.0 - fr) / 0.35, 0, 1)
    base = np.asarray(img, np.float32)
    ox, oy = (W - s) // 2, min(H - s, (H - s) // 2 + 8)
    region = base[oy:oy + s, ox:ox + s, :3]
    m = mask[..., None]
    base[oy:oy + s, ox:ox + s, :3] = region * (1 - m) + fa[..., :3] * m * 0.92 + region * m * 0.08
    vig = np.clip((r - 0.7) / 0.6, 0, 1)[..., None]
    base[..., :3] *= (1 - 0.75 * vig)
    base[..., 3] = 255
    return Image.fromarray(_rgba(base))


def umbrella(s, k, name, folds, patches):
    """우산 하나: 얼굴(위를 봄), 밑면 살 주름, 살 천(옆 넷 + 귀퉁이 넷), 우산살 여덟, 손잡이 대와 갈고리 둘(원형 DNA), 기운 가죽 조각"""
    c = CANOPY[name]
    h = s.head(size=CAP, depth=DEPTH, face="smile", rot=(-90, 0, 0))
    s.box((-CAP / 2 + 0.01, BOT, -CAP / 2 + 0.01), (CAP / 2 - 0.01, BOT + 0.002, CAP / 2 - 0.01), {"down": folds}, faces=("down",),
          uv=[0, 0, 16, 16], face=True)
    flare, length, wave = c["skirt"]
    wide = flare > 0.5
    # 살 천: 갓 옆 넷(넓게)과 귀퉁이 넷(벌렸을 때만, 옆 천 사이 틈을 메움)
    panels = [((1, 0), CAP * 0.98), ((-1, 0), CAP * 0.98), ((0, 1), CAP * 0.98), ((0, -1), CAP * 0.98)]
    if wide:
        panels += [((1, 1), CAP * 0.62), ((-1, 1), CAP * 0.62), ((-1, -1), CAP * 0.62), ((1, -1), CAP * 0.62)]
    for i, ((sx, sz), width) in enumerate(panels):
        root = (sx * CAP / 2 * (0.94 if sx and sz else 1), -DEPTH / 2 + 0.06, sz * CAP / 2 * (0.94 if sx and sz else 1))
        n = math.hypot(sx, sz)
        d = (sx / n * flare, -1.0, sz / n * flare)
        w = None if wave is None else kit.Wave(amp=wave.amp, lag=wave.lag, bias=wave.bias, phase=i * 90 + (45 if i >= 4 else 0),
                                                  shape=wave.shape, cycles=wave.cycles)
        s.band(root, d, length=length * (1.04 if i >= 4 else 1.0), width=width, segs=2, thick=0.02, wave=w, toward=(sx / n, 0.2, sz / n),
               tex="wetskin", poses=4)
    # 우산살 여덟(가만히): 천 밑을 따라 바깥 아래로, 끝이 천 가장자리에 살짝 나옴
    if c["spoke"] is not None:
        out, L = c["spoke"]
        for i in range(8):
            a = 2 * math.pi * i / 8 + math.pi / 8
            d = (math.cos(a) * out, -0.62 if out > 0.5 else -1.0, math.sin(a) * out)
            s.rod((math.cos(a) * 0.12, BOT, math.sin(a) * 0.12), d, length=L, segs=1, thick=0.05, base=False)
    # 손잡이: 가운데 대(가만히) + 끝 굽은 갈고리 둘(원형 DNA, 까딱)
    if c["shaft"]:
        L = c["shaft"]
        s.rod((0, BOT, 0), (0, -1, 0), length=L, segs=1, thick=0.085, base=False, toward=(0, 0, 1))
        hw = c["hook"]
        for side in (1, -1):
            w = kit.Wave(amp=hw.amp, lag=hw.lag, bias=tuple(side * b for b in hw.bias), phase=0 if side > 0 else 120, shape=hw.shape,
                         cycles=hw.cycles)
            s.rod((0, BOT - L, 0), (side * 0.25, -1.0, 0.0), length=0.36, segs=2, thick=0.075, wave=w, toward=(0, 0, 1), poses=4)
    # 기운 가죽 조각(배운 신호마다 하나)
    for pname, (cx, cz, rot), tex in zip(("patch1", "patch2"), ((0.2, 0.18, 12), (-0.22, -0.16, -18)), patches):
        with s.when("on:" + pname):
            s.box((cx - 0.17, DEPTH / 2 + 0.004, cz - 0.17), (cx + 0.17, DEPTH / 2 + 0.012, cz + 0.17), {"up": tex}, faces=("up",),
                  uv=[0, 0, 16, 16], rot=(0, rot, 0), face=True)
    return h


def build(k: kit.Kit):
    folds = k.texture("folds", folds_tex())
    patches = [k.texture("patch_a", patch_tex(faces.preset("tears"), 4)), k.texture("patch_b", patch_tex(faces.preset("deep"), 9))]
    # 변이 2 막대기: 갓 위 가장자리에서 위로(뒤집힌 우산의 더듬이)
    m = k.model(poses=6, period=30, shrink=0.75,
                mut2=dict(root=lambda side: (side * 0.32, DEPTH / 2, -0.3), dir=(0.35, 1.0, -0.5), length=0.8))
    for name in CANOPY:
        umbrella(m.state(name), k, name, folds, patches)

    sh = k.model("g16_shadow", poses=1, mut2=False, open=False, torn=False)
    sh.state("").quad((0, 0, 0), (1.0, 1.0), "up", k.texture("shadow", shadow_tex()))

    k.cover("g16_cap", cover_img())
    k.icon(view="top")


def inhale(seed=0, dur=1.0):
    """'후웁': 목구멍 깊이 빨아들이는 숨(점점 세지다 뚝 끊김) + 아래 웅웅"""
    n = audio.noise(dur, seed)
    breath = audio.bandpass(n, 500, 2600)
    t = np.linspace(0, 1, len(breath), dtype=np.float32)
    shape = (t ** 1.6) * (t < 0.9) + (t >= 0.9) * np.clip(1 - (t - 0.9) / 0.04, 0, 1)
    low = audio.tone(70 + seed * 3, dur, glide=95) * 0.25 * shape
    return audio.normalize(audio.fade(breath * shape + low, 0.01, 0.01), 0.8)


def whistle(seed=0, dur=0.7):
    """떨어지는 '휘익': 높은 데서 낮게 미끄러지는 바람 소리"""
    w = audio.tone(1400 + seed * 60, dur, glide=420, vib=0.02, vib_rate=11) * 0.35
    n = audio.bandpass(audio.noise(dur, seed), 700, 3000) * 0.4
    return audio.normalize(audio.fade(audio.env(audio.mix(w, n), 0.05, 0.1, 0.8, 0.2), 0.02, 0.05), 0.75)


def sounds(k: kit.Kit):
    # 표적 본인에게만(Java 가 그 사람에게만 보냄): 내려꽂기 3초 전 들이쉼. 이 종의 신호 소리
    k.sound("inhale", "후웁 들이쉼", synth=lambda i: inhale(seed=40 + i), volume=1.0, attenuation=16)
    k.sound("dive", "휘익 떨어짐", synth=lambda i: whistle(seed=44 + i), volume=0.9, attenuation=32)
    k.sound("plop", "철퍽 덮침", vanilla=[("mob/slime/big1", 0.6), ("mob/slime/big3", 0.55)], synth=lambda i: audio.squish(seed=48 + i),
            attenuation=16)
    k.sound("thud", "퍽 박힘", synth=lambda i: audio.thump(seed=52 + i, freq=80), attenuation=16)
    k.sound("drift", "하늘에서 펄럭", vanilla=[("mob/phantom/flap1", 0.5), ("mob/phantom/flap2", 0.48)], volume=0.6, attenuation=32)


def phrases():
    return {"cause_g16": "하늘이 머리를 덮었다"}
