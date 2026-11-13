"""G34 딱딱 씹는 게이브: 거대한 턱(1.6 배). 1.92 블록 얼굴이 눈 아래에서 가로로 갈라져 위(실눈, 머리 덩어리)와 아래(홍조, 점 입)가 캐스터네츠처럼
맞부딪힌다. 틈 안쪽은 검붉은 살, 가장자리에는 작은 실눈 얼굴들이 이빨처럼 한 줄 박혀 있다. 엔드 막대기는 둘(양옆)에서 시작해 다른 게이브를
먹을 때마다 하나씩 늘어 여섯까지 얼굴 뒤 둘레에 주름 도마뱀 목도리처럼 부채꼴로 펼쳐진다(부위 rod3 .. rod6: 먹을수록 목도리가 차오르는 것이
한눈에 보임). 여섯이 되면 얻은 성질 색 띠가 여섯째 막대기 뿌리에 감긴다(부위 t_burst 붉은 띠, t_shield 푸른 띠, t_swift 노란 띠).

상태(자세 넷, 12틱 한 바퀴. 자세 0 이 맞닿는 순간: Java 가 월드 시각 % 12 == 0 에 '딱'):
  ""     통통 튀며 딱딱(벌어졌다 닫힘)
  "chew" 씹는 중(같은 주기에 두 번 작게 오물오물)
  "gape" 크게 벌림(물기 직전, 벌어진 틈이 약점)
아래 반쪽은 제자리, 위 반쪽이 들린다. 실눈 뜸(open)은 위 반쪽 앞면을 뜬 눈 그림으로, 입 찢어짐(변이 1)은 아래 반쪽 앞면을 찢어진 입 그림으로
바꾼다. 변이 2 막대기는 끔(막대기 수가 곧 먹은 수라 헷갈리지 않게). 틀 ±1.5 블록을 넘으므로 0.6 으로 줄여 굽는다(엔진이 Look.scale 로 되돌림).
모델 틴트 칸이 80(한도)이라 새 조건(when)을 더하지 않는다.
"""
import math

import numpy as np

from gabe import audio, faces, kit

K = 1.6  # 옛 1.2 블록 턱에서 키운 배
S, D = 1.2 * K, 0.8 * K
ROW = 20                      # 이 화소 줄에서 갈라짐(32 화소 얼굴: 눈 아래, 홍조 위)
SPLIT = S / 2 - ROW / 32 * S   # 갈라진 줄 높이(블록, 얼굴 가운데 기준) = -0.24
VSPLIT = ROW / 2               # uv v(0..16)
GAPS = {"": (0.0, 0.16 * K, 0.26 * K, 0.12 * K), "chew": (0.0, 0.13 * K, 0.0, 0.13 * K), "gape": (0.34 * K, 0.37 * K, 0.34 * K, 0.31 * K)}
# 목도리 막대기 여섯: (부위, 둘레 각도(도), 위상). 처음 둘은 양옆, 먹을 때마다 대각선 넷이 하나씩 참
FRILL = [(None, 0, 0), (None, 180, 180), ("rod3", 40, 60), ("rod4", 140, 240), ("rod5", -40, 120), ("rod6", 220, 300)]
ROD_LEN, ROD_THICK = 0.95 * K, 0.1 * K
BANDS = {"t_burst": (206, 44, 66), "t_shield": (84, 132, 210), "t_swift": (240, 204, 66)}


def frill_root(deg):
    a = math.radians(deg)
    return (math.cos(a) * 0.42 * S, math.sin(a) * 0.42 * S, -D / 2 + 0.05), (math.cos(a), math.sin(a), -0.35)


def gum_tex(n=16, seed=34):
    """틈 안쪽 검붉은 살: 젖은 주름과 드문 반짝임"""
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.float32)
    for y in range(n):
        for x in range(n):
            ridge = np.sin(x * 1.1 + np.sin(y * 0.9) * 1.4)
            out[y, x, :3] = np.array([92, 20, 30], np.float32) * (0.8 + 0.2 * ridge) + rng.randn(3) * 3
            out[y, x, 3] = 255
    for (x, y) in ((3, 4), (10, 7), (6, 12), (13, 2)):
        out[y, x, :3] = (214, 120, 130)
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


def teeth_tex(count=6, gap=2, flip=False):
    """이빨처럼 박힌 작은 실눈 얼굴 한 줄(8 화소 원본 얼굴 여섯, 사이는 투명). flip 이면 위아래 뒤집음(아랫니)"""
    tile = faces.skin_side("front", 8)
    w = count * 8 + (count - 1) * gap
    out = np.zeros((8, w, 4), np.uint8)
    for i in range(count):
        t = tile.copy()
        if i % 2:
            t = t[:, ::-1].copy()
        out[:, i * (8 + gap):i * (8 + gap) + 8] = t
    if flip:
        out = out[::-1].copy()
    return out


def band_tex(rgb):
    out = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        k = 1.0 if y % 4 < 2 else 0.8
        out[y, :, :3] = [int(c * k) for c in rgb]
    out[..., 3] = 255
    return out


def halves(s, k, gaps, gum, teeth_up, teeth_dn):
    """위아래 반쪽(자세마다 위 반쪽이 들림)과 이빨 줄"""
    skin = {n: k.skin_tex(w) for n, w in (("north", "back"), ("east", "left"), ("west", "right"), ("up", "top"), ("down", "bottom"))}
    hs, hd = S / 2, D / 2
    ep = 1.0 / 16
    for f in s.frames():
        with f:
            g = gaps[f.k]
            # ---- 위 반쪽: 앞면은 실눈 / 뜬 눈(open), 먼 얼굴 / 가까운 얼굴
            lo, hi = (-hs, SPLIT + g, -hd), (hs, hs + g, hd)
            for face, cond in (("smile", "!open"), ("open", "open")):
                with s.when(cond):
                    s.box(lo, hi, {"south": k.face_tex(face)}, faces=("south",), uv=[0, 0, 16, VSPLIT], dist="far", face=True)
                    s.box((lo[0], lo[1], hd + 0.08 * ep), (hi[0], hi[1], hd + 0.08 * ep), {"south": k.face_tex(face, near=True)},
                          faces=("south",), uv=[0, 0, 16, VSPLIT], dist="near", face=True)
            up = dict(skin)
            up["down"] = gum
            s.box(lo, hi, up, faces=("north", "east", "west", "up", "down"),
                  uv={"north": [0, 0, 16, VSPLIT], "east": [0, 0, 16, VSPLIT], "west": [0, 0, 16, VSPLIT], "up": [0, 0, 16, 16],
                      "down": [0, 0, 16, 16]}, face=True)
            # ---- 아래 반쪽(제자리): 앞면은 점 입 / 찢어진 입(변이 1)
            lo2, hi2 = (-hs, -hs, -hd), (hs, SPLIT, hd)
            for face, cond in (("smile", "mut<1"), ("torn", "mut>=1")):
                with s.when(cond):
                    s.box(lo2, hi2, {"south": k.face_tex(face)}, faces=("south",), uv=[0, VSPLIT, 16, 16], dist="far", face=True)
                    s.box((lo2[0], lo2[1], hd + 0.08 * ep), (hi2[0], hi2[1], hd + 0.08 * ep), {"south": k.face_tex(face, near=True)},
                          faces=("south",), uv=[0, VSPLIT, 16, 16], dist="near", face=True)
            dn = dict(skin)
            dn["up"] = gum
            s.box(lo2, hi2, dn, faces=("north", "east", "west", "up", "down"),
                  uv={"north": [0, VSPLIT, 16, 16], "east": [0, VSPLIT, 16, 16], "west": [0, VSPLIT, 16, 16], "up": [0, 0, 16, 16],
                      "down": [0, 0, 16, 16]}, face=True)
            # ---- 틈 안쪽 벽(뒤통수 쪽에 검붉은 살: 앞에서 보면 벌어진 틈이 뚫려 보이지 않고 입속처럼 보임)
            if g > 0.01:
                s.quad((0, SPLIT + g / 2, -hd + 0.02), (S * 0.98, g), "south", gum, face=True)
            # ---- 이빨 줄: 윗니는 위 반쪽 아래 가장자리에 매달리고 아랫니는 아래 반쪽 위 가장자리에서 솟음
            z = hd + 0.3 * ep
            s.quad((0, SPLIT + g - 0.06 * K, z), (S * 0.94, 0.16 * K), "south", teeth_up, face=True)
            s.quad((0, SPLIT + 0.06 * K, z + 0.05 * ep), (S * 0.94, 0.16 * K), "south", teeth_dn, face=True)


def rods(s, bands):
    """목도리: 얼굴 뒤 둘레에서 바깥(조금 뒤)으로 부채처럼 펄럭(먹을수록 rod3..rod6 이 켜짐)"""
    for part, deg, ph in FRILL:
        root, d = frill_root(deg)
        w = kit.Wave(amp=(16, 14), lag=50, bias=(-2, 4), phase=ph)
        if part is None:
            s.rod(root, d, length=ROD_LEN, segs=2, thick=ROD_THICK, wave=w, toward=(0, 0, -1), poses=4)
        else:
            with s.when("on:" + part):
                s.rod(root, d, length=ROD_LEN * 0.95, segs=2, thick=ROD_THICK, wave=w, toward=(0, 0, -1), poses=4)
    # 여섯째 막대기 뿌리에 감긴 성질 색 띠(자세 없음)
    root, d = frill_root(FRILL[5][1])
    for part, tex in bands.items():
        with s.when("on:" + part):
            s.rod(root, d, length=0.24 * K, segs=1, thick=0.19 * K, base=False, tex=tex, toward=(0, 0, -1))


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, mut2=False, open=False, torn=False, shrink=0.6)
    gum = k.texture("gum", gum_tex())
    tu = k.texture("teeth_up", teeth_tex(), classes=True)
    td = k.texture("teeth_dn", teeth_tex(flip=True), classes=True)
    bands = {part: k.texture(part, band_tex(rgb)) for part, rgb in BANDS.items()}
    for name, gaps in GAPS.items():
        s = m.state(name)
        halves(s, k, gaps, gum, tu, td)
        rods(s, bands)
    k.icon(model="g34", state="gape")


def growl(seed=0, dur=1.3):
    """꼬르륵: 빈 배에서 굴러 내려가는 낮은 울림(떨리는 낮은 음 + 굵은 방울 몇 개). 이 소리를 3초 들으면 찍힌다"""
    rng = np.random.RandomState(seed)
    t = np.arange(int(dur * audio.SR), dtype=np.float32) / audio.SR
    f = 58 + 22 * np.sin(2 * np.pi * 1.7 * t + seed) + 10 * np.sin(2 * np.pi * 9 * t)
    ph = 2 * np.pi * np.cumsum(f) / audio.SR
    low = (np.sin(ph) + 0.4 * np.sin(2 * ph)) * (0.55 + 0.45 * np.sin(2 * np.pi * 8.5 * t) ** 2)
    parts = [audio.env(low.astype(np.float32), 0.08, 0.2, 0.8, 0.35)]
    x = 0.15
    while x < dur - 0.2:
        fb = 110 + 90 * rng.rand()
        b = audio.env(audio.tone(fb, 0.12, glide=fb * 0.55), 0.004, 0.04, 0.4, 0.05) * (0.4 + 0.4 * rng.rand())
        parts.append(audio.at(b, x, dur))
        x += 0.16 + 0.2 * rng.rand()
    return audio.normalize(audio.fade(audio.lowpass(audio.mix(*parts), 900), 0.02, 0.1), 0.85)


def crunch(seed=0, n=4):
    """오독오독: 단단한 것을 씹는 마른 소리 여럿"""
    rng = np.random.RandomState(seed)
    parts = []
    for i in range(n):
        c = audio.env(audio.bandpass(audio.noise(0.06, seed * 7 + i), 700, 5200), 0.0006, 0.012, 0.25, 0.03)
        k2 = audio.env(audio.tone(180 + 60 * rng.rand(), 0.05, "square", glide=110), 0.0006, 0.01, 0.2, 0.02) * 0.35
        parts.append(audio.at(audio.mix(c, k2), i * 0.15 + rng.rand() * 0.02))
    return audio.normalize(audio.fade(audio.mix(*parts), 0.001, 0.03), 0.85)


def clack(seed=0):
    """딱: 위아래가 맞부딪힘"""
    c = audio.env(audio.bandpass(audio.noise(0.05, seed), 900, 4200), 0.0004, 0.008, 0.12, 0.02)
    t = audio.env(audio.tone(430 + 20 * seed % 60, 0.06, "tri", glide=260), 0.0005, 0.012, 0.2, 0.03) * 0.7
    return audio.normalize(audio.fade(audio.mix(c, t), 0.0005, 0.012), 0.8)


def gulp(seed=0):
    """꿀꺽: 삼킴"""
    a = audio.env(audio.tone(330, 0.12, glide=150), 0.004, 0.03, 0.5, 0.05)
    b = audio.at(audio.env(audio.tone(140, 0.14, glide=210), 0.004, 0.04, 0.5, 0.06), 0.1)
    return audio.normalize(audio.fade(audio.mix(a, b), 0.002, 0.03), 0.8)


def sounds(k: kit.Kit):
    # 이 종의 신호: 꼬르륵 뒤 오독오독
    k.sound("growl", "꼬르륵", synth=lambda i: growl(seed=340 + i), attenuation=24)
    k.sound("crunch", "오독오독", synth=lambda i: crunch(seed=344 + i), attenuation=24)
    k.sound("clack", "딱", synth=lambda i: clack(seed=348 + i), volume=0.7, attenuation=16)
    k.sound("gulp", "꿀꺽", synth=lambda i: gulp(seed=352 + i), attenuation=16)


def phrases():
    return {"cause_g34": "꼬르륵 소리를 들었다"}
