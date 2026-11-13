"""G17 우는 아기 게이브: 0.35 얼굴, 실눈 아래 눈물 줄, 엔드 막대기 대신 뭉툭한 분홍 막대 둘이 아기 팔처럼 파닥인다.

상태(부품 디스플레이 하나에 싣는다: 사람마다 다른 모습을 보여야 해서 몸은 빈 닻 + 부품):
  ""     진짜 아기: 눈물이 아래로 흐름(눈물방울이 자세마다 아래로)
  "fake" 가짜 아기: 눈물이 이마 쪽으로 거꾸로 흐름(방울이 위로)
  "cute" 어깨에 붙은 가짜를 붙은 본인만 보는 모습(짙은 홍조, 눈물 없음)
  "lump1" .. "lump3" 어깨에 붙은 가짜를 동료들이 보는 본모습: 작은 실눈과 점 입 얼굴 혹이 단계마다 더 돋음
덮개 g17_lump1 .. 3: 붙은 본인 화면 가장자리 혹 글리프 3단계.
"""
import math

import numpy as np
from PIL import Image, ImageDraw

from gabe import audio, faces, kit

HEAD = 0.35
DEPTH = 0.3
TEAR = (207, 233, 255)
TEAR_D = (169, 212, 245)
ARMS = kit.Wave(amp=(34, 26), lag=50, bias=(10, 6), shape="flap")
LINES = (5, 10, 21, 26)  # 눈물 줄 x(32 기준)


def tears_tex(up, n=32):
    """눈물 줄 덧그림(나머지 투명). up 이면 눈 위에서 이마 쪽으로"""
    k = n // 32
    out = np.zeros((n, n, 4), np.uint8)
    for x0 in LINES:
        x = x0
        ys = range(10, -1, -1) if up else range(18, 32)
        for y in ys:
            if faces._h("tear", x0, y, up) < 0.18:
                x += 1 if faces._h("tw", x0, y, up) < 0.5 else -1
            out[y * k:(y + 1) * k, x * k:(x + 1) * k, :3] = TEAR
            out[y * k:(y + 1) * k, x * k:(x + 1) * k, 3] = 255
        end = 0 if up else 31
        out[end * k:(end + 1) * k, x * k:(x + 1) * k, :3] = TEAR_D
    if k > 1:
        # 가까운 그림: 줄 가운데 밝은 물빛, 가장자리 한 화소 그늘
        a = out[..., 3] > 0
        edge = a & ~np.roll(a, 1, axis=1)
        out[edge, :3] = TEAR_D
    return out


def drop_tex():
    out = np.zeros((4, 4, 4), np.uint8)
    out[..., :3] = TEAR
    out[1:3, 1:3, :3] = (240, 250, 255)
    out[..., 3] = 255
    return out


def face_xy(px, py):
    """얼굴 그림 화소(32 기준) → 모델 좌표(x, y)"""
    return -HEAD / 2 + (px + 0.5) / 32 * HEAD, HEAD / 2 - (py + 0.5) / 32 * HEAD


def baby(s, face, tears=None, up=False, drop=None, arms=True):
    h = s.head(size=HEAD, depth=DEPTH, face=face)
    ep = 1.0 / 16
    z = DEPTH / 2 + 0.34 * ep
    if tears is not None:
        far, near = tears
        s.quad((0, 0, z), (HEAD, HEAD), "south", far, dist="far", face=True)
        s.quad((0, 0, z + 0.04 * ep), (HEAD, HEAD), "south", near, dist="near", face=True)
        # 흐르는 방울: 자세마다 줄을 따라 한 칸씩(진짜는 아래로, 가짜는 위로)
        for fr in s.frames(4):
            with fr:
                for i, x0 in enumerate(LINES):
                    t = (fr.t + i * 0.37) % 1.0
                    py = (10 - t * 10) if up else (18 + t * 13)
                    x, y = face_xy(x0, py)
                    s.quad((x, y, z + 0.08 * ep), (HEAD / 16, HEAD / 16), "south", drop)
    if arms:
        for side in (1, -1):
            s.rod(h.point(side * 0.98, -0.45, 0.1), (side * 1.0, 0.25, 0.55), length=0.24, segs=2, thick=0.085, wave=ARMS, toward=(0, 1, 0),
                  tex="flesh", base=False)
    return h


def lumps(s, count, seed):
    """머리 둘레에 작은 얼굴 혹(실눈, 점 입)을 바깥을 보게 돋움"""
    rng = np.random.RandomState(seed)
    fl = ["smile", "smile_a", "smile_c", "half"]
    golden = math.pi * (3 - math.sqrt(5))
    for i in range(count):
        yv = 1 - 2 * (i + 0.5) / count
        r = math.sqrt(max(0.0, 1 - yv * yv))
        a = golden * i + rng.rand() * 0.4
        nrm = np.array((math.cos(a) * r, yv, math.sin(a) * r))
        if nrm[2] > 0.82:
            continue  # 얼굴 앞면은 비움(아기 얼굴이 보이게)
        size = 0.07 + rng.rand() * 0.05
        c = nrm * (HEAD * 0.52 + size * 0.25)
        yaw = math.degrees(math.atan2(nrm[0], nrm[2]))
        pitch = -math.degrees(math.asin(max(-1, min(1, nrm[1]))))
        tex = {"south": s.model.kit.face_tex(fl[i % len(fl)]), "north": s.model.kit.skin_tex("back"), "east": s.model.kit.skin_tex("left"),
               "west": s.model.kit.skin_tex("right"), "up": s.model.kit.skin_tex("top"), "down": s.model.kit.skin_tex("bottom")}
        hs = size / 2
        s.box(tuple(c - hs), tuple(c + hs), tex, uv=[0, 0, 16, 16], rot=(pitch, yaw, 0), origin=tuple(c), face=True)


def cover_img(stage):
    """붙은 본인 화면 가장자리 혹(480x270): 살빛 혹마다 작은 실눈과 점 입. 단계마다 더 안쪽까지"""
    W, H = 480, 270
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rng = np.random.RandomState(170 + stage)
    count = (14, 30, 52)[stage - 1]
    reach = (0.10, 0.16, 0.24)[stage - 1]
    for i in range(count):
        side = rng.randint(4) if stage > 1 else rng.choice([0, 2])
        corner = stage == 1
        if side in (0, 2):
            x = rng.rand() * W if not corner else (rng.rand() * W * 0.22 if rng.rand() < 0.5 else W - rng.rand() * W * 0.22)
            y = rng.rand() * H * reach if side == 0 else H - rng.rand() * H * reach
        else:
            y = rng.rand() * H
            x = rng.rand() * W * reach * 0.7 if side == 3 else W - rng.rand() * W * reach * 0.7
        r = 9 + rng.rand() * (10 + 5 * stage)
        d.ellipse((x - r, y - r, x + r, y + r), fill=(238, 196, 184, 255), outline=(176, 120, 112, 255), width=2)
        d.ellipse((x - r * 0.75, y - r * 0.8, x + r * 0.55, y + r * 0.35), fill=(250, 214, 204, 255))
        # 실눈 둘, 점 입
        for ex in (-0.38, 0.38):
            cx, cy = x + ex * r, y - 0.12 * r
            d.arc((cx - r * 0.24, cy - r * 0.2, cx + r * 0.24, cy + r * 0.2), 200, 340, fill=(89, 27, 27, 255), width=2)
        d.ellipse((x - r * 0.1, y + r * 0.3, x + r * 0.1, y + r * 0.5), fill=(66, 20, 20, 255))
    return im


def build(k: kit.Kit):
    m = k.model(poses=6, period=16, mut2=False, torn=False)
    drop = k.texture("drop", drop_tex())
    down = (k.texture("tears_down", tears_tex(False)), k.texture("tears_down_n", tears_tex(False, 128)))
    up = (k.texture("tears_up", tears_tex(True)), k.texture("tears_up_n", tears_tex(True, 128)))
    baby(m.state(""), "smile", down, False, drop)
    baby(m.state("fake"), "smile", up, True, drop)
    baby(m.state("cute"), faces.Face(blush="deep"), None)
    for n, cnt in ((1, 10), (2, 20), (3, 32)):
        s = m.state(f"lump{n}")
        baby(s, "smile", up, True, drop, arms=n < 3)
        lumps(s, cnt, 17 + n)
    for n in (1, 2, 3):
        k.cover(f"g17_lump{n}", cover_img(n))
    k.icon(model="g17", state="")


# ---------------------------------------------------------------- 소리(모두 합성)

def _cry(f0, dur, seed, wob=6.0, breath=0.15):
    t = np.arange(int(dur * audio.SR), dtype=np.float32) / audio.SR
    f = f0 * (1 + 0.06 * np.sin(2 * np.pi * wob * t) + 0.12 * np.sin(np.pi * t / dur))
    ph = 2 * np.pi * np.cumsum(f) / audio.SR
    src = (np.sin(ph) * 0.6 + np.sign(np.sin(ph)) * 0.15 + np.sin(2 * ph) * 0.3).astype(np.float32)
    v = audio.mix(audio.gain(audio.bandpass(src, 700, 1500), 1.0), audio.gain(audio.bandpass(src, 2400, 3600), 0.5))
    v = audio.mix(v, audio.gain(audio.bandpass(audio.noise(dur, seed), 1500, 5000), breath))
    return audio.env(v, 0.04, 0.1, 0.8, 0.15)


def sob(seed=0):
    """흐느낌: 짧게 끊기는 울음 셋과 들숨 딸꾹"""
    rng = np.random.RandomState(seed)
    parts = []
    for i in range(3):
        parts.append(_cry(430 + rng.rand() * 60, 0.22 + rng.rand() * 0.1, seed * 5 + i, wob=9))
        parts.append(audio.silence(0.06))
    gasp = audio.env(audio.bandpass(audio.noise(0.14, seed + 9), 2000, 6000), 0.02, 0.04, 0.5, 0.05) * 0.5
    return audio.normalize(audio.cat(*parts, gasp), 0.7)


def wail(seed=0):
    """크게 울음: 길게 터지는 울음(증원을 부름)"""
    return audio.normalize(audio.cat(_cry(470, 0.9, seed, wob=5, breath=0.2), audio.silence(0.05), _cry(520, 1.1, seed + 1, wob=4)), 0.95)


def laugh_low(seed=0):
    """듣기: 울음 밑에 깔린 어른 목소리 웃음(낮은 헤헤)"""
    parts = []
    for i in range(4):
        t = np.arange(int(0.13 * audio.SR), dtype=np.float32) / audio.SR
        ph = 2 * np.pi * (118 + 6 * i) * t
        v = (np.sin(ph) * 0.5 + np.sign(np.sin(ph)) * 0.2).astype(np.float32)
        v = audio.env(audio.bandpass(v, 200, 1100), 0.01, 0.04, 0.6, 0.05)
        parts.append(audio.cat(v, audio.silence(0.07)))
    under = audio.gain(sob(seed + 3), 0.35)
    return audio.normalize(audio.mix(audio.cat(*parts), under), 0.6)


def coo(seed=0):
    """옹알이: 달랜 진짜 아기(높아지는 짧은 소리 둘)"""
    a = _cry(520, 0.18, seed, wob=3, breath=0.05)
    b = _cry(640, 0.22, seed + 1, wob=3, breath=0.05)
    return audio.normalize(audio.cat(a, audio.silence(0.05), b), 0.6)


def heart(seed=0):
    """심장 소리(붙은 본인만): 두 번 쿵쿵"""
    a = audio.thump(seed=seed, dur=0.16, freq=62)
    b = audio.thump(seed=seed + 1, dur=0.18, freq=55)
    return audio.normalize(audio.lowpass(audio.cat(a, audio.silence(0.08), b), 400), 0.9)


def scream(seed=0):
    """맞았을 때 아기 비명"""
    return audio.normalize(_cry(780 + 40 * seed, 0.45, seed + 30, wob=14, breath=0.3), 0.9)


def sounds(k: kit.Kit):
    k.sound("sob", "아기 흐느낌", synth=lambda i: sob(240 + i), volume=0.8, attenuation=32)
    k.sound("wail", "아기가 크게 운다", synth=lambda i: wail(250 + i), attenuation=64)
    k.sound("laugh_low", "울음 밑의 어른 웃음", synth=lambda i: laugh_low(260 + i), volume=0.7, attenuation=8)
    k.sound("coo", "옹알이", synth=lambda i: coo(270 + i), volume=0.7, attenuation=12)
    k.sound("heart", "심장 소리", synth=lambda i: heart(280 + i), attenuation=4)
    k.sound("latch", "어깨에 철썩", synth=lambda i: audio.squish(seed=290 + i, dur=0.25), attenuation=16)
    k.sound("hurt", "아기 비명", synth=lambda i: scream(i), attenuation=16)
    k.sound("die", "울음이 끊김", synth=lambda i: audio.normalize(audio.cat(_cry(600, 0.18, 300 + i, wob=20), audio.squish(seed=310 + i, dur=0.2))),
            attenuation=24)


def phrases():
    return {"cause_g17": "아기를 달랬다", "notice_g17_cry": "아기 울음에 몰려온다"}
