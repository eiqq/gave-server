"""G37 보자기 게이브: 얼굴을 양 날개 끝 엔드 막대기 둘 사이에 가로 3, 세로 2 블록으로 얇게 늘린 넓적한 막. 실눈은 가로로 찢어진 선,
점 입은 손바닥만 한 구멍(뚫려 있음). 막이 날개 끝부터 가운데로 물결친다. 옆에서 보면 얇은 선.

모델은 반으로 줄여 굽는다(shrink 0.5, 엔진이 두 배로 되돌림).
상태:
  ""     펼침: 세로 띠 여섯이 자세마다 앞뒤로 물결(날개 끝이 크게, 가운데로 갈수록 늦게), 양 끝 막대기는 위에서 아래로 늘어져 흔들림
  "wrap" 보따리: 사람을 싼 자루(앞면에 늘어난 얼굴), 위에서 오므려 매듭, 매듭에서 막대기 둘이 X 로 솟아 파르르. 자루가 숨 쉬듯 부풂
표적을 찍으면(open) 찢어진 선이 벌어져 눈이 드러난다. 덮개 g37_cloth: 싸인 사람 화면(분홍 천, 입 구멍으로 바깥이 비침).
"""
import math

import numpy as np
from PIL import Image

from gabe import audio, kit

W, H = 3.0, 2.0
COLS = 6
SKIN = np.array([255, 226, 219], np.float32)
SKIN_D = np.array([246, 204, 196], np.float32)
TUFT = np.array([92, 30, 30], np.float32)
LINE = np.array([98, 30, 32], np.float32)
BLUSH = np.array([255, 190, 180], np.float32)
INNER = np.array([226, 150, 156], np.float32)
MOUTH = (0.5, 0.76)   # 입 구멍 가운데(가로, 세로 비율)
MOUTH_R = 0.105       # 세로 비율 반지름


def _rgba(a):
    return np.clip(np.round(a), 0, 255).astype(np.uint8)


def cloth(w, h, opened=False, back=False, seed=37):
    """늘어난 얼굴 천(w x h). back = 안쪽 면(분홍 살, 같은 구멍과 꿰맨 눈 줄)"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    u, v = (xx + 0.5) / w, (yy + 0.5) / h
    rng = np.random.RandomState(seed)
    fold = 0.955 + 0.045 * np.sin(u * 2 * math.pi * 6 + 0.6)
    base = INNER if back else SKIN
    col = np.broadcast_to(base, (h, w, 3)).copy() * fold[..., None]
    if w >= 96:
        col += (rng.rand(h, w, 1) - 0.5) * 7  # 가까운 그림: 살결
    alpha = np.full((h, w), 255, np.float32)
    if not back:
        edge = (u < 0.05) | (u > 0.95) | (v > 0.94)
        col[edge] = SKIN_D * fold[edge][..., None]
        # 모자 층 적갈색 덩어리(위 양 모서리, 늘어남)
        for cx in (0.2, 0.8):
            tuft = (np.abs(u - cx) < 0.11) & (v < 0.2 - 0.9 * (u - cx) ** 2 * 4)
            col[tuft] = TUFT
        # 홍조
        bl = ((u - 0.5) / 0.14) ** 2 + ((v - 0.585) / 0.05) ** 2 <= 1
        col[bl] = BLUSH
    # 눈: 가로로 찢어진 선(끝이 가늘어짐). 뜨면 아몬드 꼴로 벌어져 흰자와 세로로 긴 동공
    for cx in (0.25, 0.75):
        t = np.clip(1 - ((u - cx) / 0.14) ** 2, 0, 1)
        half = (0.065 if opened and not back else 0.022) * np.sqrt(t)
        d = np.abs(v - 0.42)
        slit = (t > 0) & (d <= np.maximum(half, 0.5 / h))
        if opened and not back:
            col[slit] = (241, 235, 232)
            iris = slit & (((u - cx) / 0.035) ** 2 + ((v - 0.42) / 0.07) ** 2 <= 1)
            col[iris] = (122, 42, 42)
            pupil = slit & (((u - cx) / 0.014) ** 2 + ((v - 0.42) / 0.05) ** 2 <= 1)
            col[pupil] = (36, 10, 10)
            lid = (t > 0) & (d > half - 1.2 / h) & (d <= half + 0.4 / h)
            col[lid] = LINE
        else:
            col[slit] = LINE
    # 입 구멍(뚫림)과 짙은 테
    asp = w / h
    r = np.hypot((u - MOUTH[0]) * asp, v - MOUTH[1])
    col[(r > MOUTH_R) & (r <= MOUTH_R + 1.3 / h)] = (105, 32, 32)
    alpha[r <= MOUTH_R] = 0
    out = np.zeros((h, w, 4), np.float32)
    out[..., :3] = col
    out[..., 3] = alpha
    return _rgba(out)


def cover_img(Wp=480, Hp=270, seed=37):
    """싸인 사람 화면: 분홍 천이 눈앞을 덮고(주름 그늘, 천 결), 입 구멍 자리로 바깥이 또렷이, 나머지는 흐리게 비친다"""
    yy, xx = np.mgrid[0:Hp, 0:Wp].astype(np.float32)
    u, v = xx / Wp, yy / Hp
    rng = np.random.RandomState(seed)
    fold = 0.82 + 0.18 * np.sin(u * 2 * math.pi * 5 + np.sin(v * 5) * 1.2)
    weave = 0.96 + 0.04 * (((xx // 2) + (yy // 2)) % 2)
    col = np.broadcast_to(np.array([238, 156, 170], np.float32), (Hp, Wp, 3)).copy() * (fold * weave)[..., None]
    col += (rng.rand(Hp, Wp, 1) - 0.5) * 8
    alpha = np.full((Hp, Wp), 208, np.float32)
    # 가장자리는 짙게
    edge = np.clip((np.hypot((u - 0.5) / 0.5, (v - 0.5) / 0.5) - 0.75) / 0.5, 0, 1)
    alpha += 40 * edge
    col *= (1 - 0.35 * edge)[..., None]
    # 입 구멍: 바깥이 또렷이 보이는 둥근 구멍(테는 짙은 살)
    r = np.hypot((xx - Wp * 0.5) / 1.0, (yy - Hp * 0.68) / 1.0)
    hole = np.clip((r - 44) / 6, 0, 1)
    alpha = alpha * hole + 18 * (1 - hole)
    rim = (r > 44) & (r < 54)
    col[rim] = col[rim] * 0.5 + np.array([105, 32, 32], np.float32) * 0.5
    # 찢어진 눈 줄 둘(안에서 본 가는 빛줄기)
    for cx in (0.25, 0.75):
        t = np.clip(1 - ((u - cx) / 0.15) ** 2, 0, 1)
        slit = (t > 0) & (np.abs(v - 0.34) <= 0.012 * np.sqrt(t) + 0.002)
        alpha[slit] = 60
    out = np.zeros((Hp, Wp, 4), np.float32)
    out[..., :3] = col
    out[..., 3] = alpha
    return Image.fromarray(_rgba(out), "RGBA")


def strip_z(i_edge, t):
    """세로 띠 경계 i_edge(0..COLS)의 앞뒤 자리: 날개 끝이 크게, 가운데로 갈수록 작고 늦게(끝에서 가운데로 가는 물결)"""
    x = -W / 2 + W * i_edge / COLS
    a = 0.06 + 0.2 * abs(x) / (W / 2)
    return a * math.sin(2 * math.pi * t - 2.4 * (W / 2 - abs(x)))


def sheet(s, tex):
    """펼친 막: 세로 띠 여섯(자세마다 앞뒤로 물결) + 양 끝 막대기"""
    for f in s.frames():
        with f:
            for i in range(COLS):
                x0, x1 = -W / 2 + W * i / COLS, -W / 2 + W * (i + 1) / COLS
                z0, z1 = strip_z(i, f.t), strip_z(i + 1, f.t)
                cz = (z0 + z1) / 2
                ang = math.degrees(math.atan2(z0 - z1, x1 - x0))  # y 둘레: +X 끝이 뒤로 가면 양수
                wid = math.hypot(x1 - x0, z1 - z0)
                cx = (x0 + x1) / 2
                c = (cx, 0.0, cz)
                lo, hi = (cx - wid / 2, -H / 2, cz), (cx + wid / 2, H / 2, cz)
                u0, u1 = 16.0 * i / COLS, 16.0 * (i + 1) / COLS
                for name, cond in (("far", "!open"), ("far_open", "open")):
                    s.box(lo, hi, {"south": tex[name]}, faces=("south",), uv=[u0, 0, u1, 16], rot=(0, ang, 0), origin=c, dist="far",
                          face=True, show=(cond,))
                for name, cond in (("near", "!open"), ("near_open", "open")):
                    s.box(lo, hi, {"south": tex[name]}, faces=("south",), uv=[u0, 0, u1, 16], rot=(0, ang, 0), origin=c, dist="near",
                          face=True, show=(cond,))
                s.box(lo, hi, {"north": tex["back"]}, faces=("north",), uv=[u1, 0, u0, 16], rot=(0, ang, 0), origin=c, face=True)
    for side in (1, -1):
        w = kit.Wave(amp=(5, 8, 11), lag=45, bias=(0, 2, 3), phase=0 if side > 0 else 25)
        s.rod((side * (W / 2 + 0.03), H / 2 + 0.06, strip_z(0, 0) * 0.3), (0.0, -1.0, 0.0), length=H + 0.3, segs=3, thick=0.13, wave=w,
              toward=(0.0, 0.0, 1.0), poses=4)


def sack(s, tex):
    """보따리: 사람을 싼 자루(숨 쉬듯 부풂), 위에서 오므린 목, 매듭, 매듭에서 X 로 솟은 막대기 둘"""
    for f in s.frames():
        with f:
            b = 1.0 + 0.05 * math.sin(2 * math.pi * f.t)
            hx, hz = 0.6 * b, 0.6 * b
            y0, y1 = -0.95, 0.85
            lo, hi = (-hx, y0, -hz), (hx, y1, hz)
            for name, cond in (("far", "!open"), ("far_open", "open")):
                s.box(lo, hi, {"south": tex[name]}, faces=("south",), uv=[0, 0, 16, 16], dist="far", face=True, show=(cond,))
            for name, cond in (("near", "!open"), ("near_open", "open")):
                s.box(lo, hi, {"south": tex[name]}, faces=("south",), uv=[0, 0, 16, 16], dist="near", face=True, show=(cond,))
            s.box(lo, hi, tex["back"], faces=("north", "east", "west", "down", "up"), uv=[2, 0, 9, 9], face=True)
            # 오므린 목
            s.box((-0.3 * b, y1, -0.3 * b), (0.3 * b, y1 + 0.3, 0.3 * b), tex["back"], faces=("north", "south", "east", "west"),
                  uv=[2, 0, 6, 3], face=True)
    # 매듭(자세 없음)과 X 로 솟은 막대기 둘
    s.box((-0.2, 1.1, -0.2), (0.2, 1.42, 0.2), "flesh", face=True)
    for side in (1, -1):
        w = kit.Wave(amp=(7, 10), lag=70, bias=(0, 4), cycles=2, phase=0 if side > 0 else 120)
        s.rod((side * 0.06, 1.3, 0.0), (side * 0.75, 0.7, 0.0), length=1.0, segs=2, thick=0.13, wave=w, toward=(0.0, 0.0, 1.0), poses=4)


def build(k: kit.Kit):
    m = k.model(poses=6, period=30, shrink=0.5, torn=False, open=False,
                mut2=dict(root=lambda side: (side * 0.5, -H / 2 + 0.05, 0.0), dir=(0.35, -1.0, -0.2), length=0.8, segs=2, thick=0.11, poses=4,
                          toward=(0.0, 0.0, 1.0)))
    tex = {
        "far": k.texture("cloth", cloth(48, 32)),
        "far_open": k.texture("cloth_open", cloth(48, 32, opened=True)),
        "near": k.texture("cloth_n", cloth(192, 128)),
        "near_open": k.texture("cloth_open_n", cloth(192, 128, opened=True)),
        "back": k.texture("cloth_back", cloth(48, 32, back=True)),
    }
    sheet(m.state(""), tex)
    sack(m.state("wrap"), tex)
    k.cover("g37_cloth", cover_img())
    # 아이콘: 펼친 막 정면
    ic = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    ic.alpha_composite(Image.fromarray(cloth(48, 32)).resize((60, 40), Image.NEAREST), (2, 12))
    k.icon(ic)


def puff(seed=0, dur=0.9):
    """푸웅: 넓은 비닐이 바람을 먹고 부푸는 소리(낮게 부풀어 오르는 바람 + 팽팽해지는 떨림)"""
    n = audio.noise(dur, seed)
    t = np.linspace(0, 1, len(n), dtype=np.float32)
    swell = np.sin(np.clip(t / 0.6, 0, 1) * math.pi / 2) ** 2 * np.clip((1 - t) / 0.3, 0, 1)
    air = audio.bandpass(n, 140, 950) * swell
    drum = audio.tone(92 + seed * 2, dur, vib=0.06, vib_rate=17, glide=128) * swell * 0.5
    flap = audio.bandpass(audio.noise(dur, seed + 5), 900, 2600) * swell * (0.5 + 0.5 * np.sin(2 * math.pi * 21 * t)) * 0.25
    return audio.normalize(audio.fade(audio.mix(air, drum, flap), 0.02, 0.08), 0.8)


def swish(seed=0):
    """천이 휘감기는 소리 둘"""
    def one(sd, d):
        x = audio.bandpass(audio.noise(d, sd), 700, 4200)
        tt = np.linspace(0, 1, len(x), dtype=np.float32)
        return x * np.sin(tt * math.pi) ** 2
    return audio.normalize(audio.fade(audio.mix(one(seed, 0.22), audio.at(one(seed + 3, 0.26), 0.17)), 0.005, 0.03), 0.7)


def sounds(k: kit.Kit):
    # 이 종의 신호: 하늘의 넓은 천이 바람 먹는 소리
    k.sound("puff", "푸웅", synth=lambda i: puff(seed=370 + i), attenuation=32)
    k.sound("wrap", "보자기에 싸임", synth=lambda i: swish(seed=374 + i), attenuation=16)
    k.sound("drop", "털썩 떨어뜨림", synth=lambda i: audio.thump(seed=378 + i, freq=88), attenuation=16)


def phrases():
    return {"cause_g37": "보자기에 싸여 갔다", "notice_g37_carry": "보자기가 사람을 싸 간다"}
