"""G36 실핏줄 잇는 게이브: 핏줄 성게. 바로 선 0.8 블록 얼굴의 정수리가 열려 그 안에서 적갈색 실핏줄 다발이 솟고, 얼굴 옆, 아래, 뒤에서도 붉은
실핏줄 가닥이 사방으로 가시처럼 뻗어 나부낀다(지름 2.4 블록의 붉은 가시공). 짧은 엔드 막대기 둘은 얼굴 아래 양옆으로 늘어져 느리게 흔들린다
(원형 DNA). 떼 위 4블록에 떠서 주변 게이브 뒤통수로 실핏줄을 뻗는다.

디스플레이(Java):
  gave:gabe/g36       닻: 얼굴 + 정수리 구멍 + 실핏줄 가시 열여섯(정수리 일곱 + 몸 둘레 아홉) + 늘어진 막대기 둘
  gave:gabe/g36_vein  이음줄 하나: +Z 로 1 블록짜리 핏줄(십자로 겹친 두 판, 가장자리는 들쭉날쭉한 컷아웃) + 가운데 맥박 매듭(자세마다 부풀었다
                      줄어듦) + 줄을 타고 가는 박동 알갱이. Java 가 정수리(CROWN 0.42)에서 이은 게이브 뒤통수까지 늘이고, 줄마다 위상을 달리해
                      박동이 어긋난다. 20틱 한 바퀴(심장 박동)
"""
import math

import numpy as np

from gabe import audio, kit

SIZE, DEPTH = 0.8, 0.6
VEIN = np.array([112, 30, 34], np.float32)
VEIN_HI = np.array([186, 70, 70], np.float32)


def _rgba(a):
    return np.clip(np.round(a), 0, 255).astype(np.uint8)


def crown_tex(n=16, seed=36):
    """열린 정수리: 검붉은 구멍, 찢긴 가장자리 살, 안쪽 핏줄 뿌리"""
    out = np.zeros((n, n, 4), np.float32)
    rng = np.random.RandomState(seed)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            a = math.atan2(y - c, x - c)
            edge = 0.78 + 0.1 * math.sin(a * 5 + 1.0)
            if r > edge + 0.14:
                continue
            if r > edge:
                col = np.array([226, 150, 150], np.float32)
            else:
                col = np.array([64, 14, 22], np.float32) * (0.7 + 0.6 * r) + rng.randn(3) * 3
                if abs(math.sin(a * 4 + r * 6)) < 0.12:
                    col = VEIN_HI * 0.8
            out[y, x, :3] = col
            out[y, x, 3] = 255
    return _rgba(out)


def strand_tex(w=6, h=24, seed=37):
    """실핏줄 한 가닥(위가 뿌리): 적갈색, 가운데 밝은 줄, 가끔 마디"""
    out = np.zeros((h, w, 4), np.float32)
    rng = np.random.RandomState(seed)
    x0 = w / 2
    for y in range(h):
        x0 += rng.uniform(-0.35, 0.35)
        x0 = min(max(x0, 1.5), w - 1.5)
        half = 1.3 - 0.6 * y / h + (0.5 if y % 7 == 3 else 0)
        for x in range(w):
            d = abs(x + 0.5 - x0)
            if d > half:
                continue
            col = VEIN_HI if d < 0.5 else VEIN
            out[y, x, :3] = col + rng.randn(3) * 3
            out[y, x, 3] = 255
    return _rgba(out)


def vein_tex(w=10, h=64, seed=38):
    """이음줄(세로가 길이): 굵기가 들쭉날쭉한 핏줄 관, 가운데 밝은 줄, 곁가지 실핏줄 몇 가닥. 반투명 대신 가장자리 성긴 점"""
    out = np.zeros((h, w, 4), np.float32)
    rng = np.random.RandomState(seed)
    for y in range(h):
        cx = w / 2 + 0.9 * math.sin(y * 0.33) + 0.5 * math.sin(y * 0.9 + 1.0)
        half = 1.5 + 0.5 * math.sin(y * 0.21 + 2.0)
        for x in range(w):
            d = abs(x + 0.5 - cx)
            if d <= half:
                t = d / half
                col = VEIN_HI * (1 - t) + VEIN * t
                out[y, x, :3] = col + rng.randn(3) * 3
                out[y, x, 3] = 255
            elif d <= half + 1.0 and (x + y) % 2 == 0:
                out[y, x, :3] = VEIN * 0.85
                out[y, x, 3] = 255
        # 곁가지
        if y % 13 == 5:
            side = 1 if (y // 13) % 2 else -1
            for k in range(1, 4):
                xx = int(cx + side * (half + k))
                yy = y + k
                if 0 <= xx < w and yy < h:
                    out[yy, xx, :3] = VEIN
                    out[yy, xx, 3] = 255
    return _rgba(out)


def knot_tex(n=8, seed=39):
    """맥박 매듭: 부푼 검붉은 살 덩이, 젖은 반짝임"""
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.float32)
    for y in range(n):
        for x in range(n):
            out[y, x, :3] = np.array([168, 44, 58], np.float32) * (0.85 + 0.15 * math.sin(x + y * 0.7)) + rng.randn(3) * 4
            out[y, x, 3] = 255
    out[2, 2, :3] = (250, 200, 200)
    out[5, 6, :3] = (232, 150, 150)
    return _rgba(out)


def _unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def spines():
    """실핏줄 가시 열여섯: (뿌리, 방향, 마디 수, 길이). 정수리 일곱(위로 솟음, 두 마디) + 몸 둘레 아홉(옆, 아래, 뒤로 뻗음, 한 마디)"""
    rng = np.random.RandomState(36)
    top = SIZE / 2
    out = []
    for i in range(7):
        a = 2 * math.pi * i / 7
        out.append(((0.17 * math.cos(a), top - 0.02, 0.12 * math.sin(a)), (0.5 * math.cos(a), 1.0, 0.35 * math.sin(a)), 2,
                    0.75 + 0.3 * rng.rand()))
    for i in range(9):
        a = 2 * math.pi * i / 9 + 0.3
        y = -0.15 if i % 2 else 0.1
        dy = -0.45 if i % 3 == 0 else 0.15
        out.append(((0.42 * math.cos(a), y, 0.3 * math.sin(a)), (math.cos(a), dy, math.sin(a)), 1, 0.7 + 0.3 * rng.rand()))
    return out, rng


def build(k: kit.Kit):
    m = k.model(poses=4, period=40)
    s = m.state("")
    h = s.head(size=SIZE, depth=DEPTH, face="smile")
    top = SIZE / 2
    # 열린 정수리(윗면 위에 덮는 판): 이음줄이 여기서 뻗는다(Java CROWN)
    s.quad((0, top + 0.004, 0), (SIZE * 0.92, DEPTH * 0.92), "up", k.texture("crown", crown_tex()), face=True)
    # 실핏줄 가시: 뿌리에서 끝으로 늦게 퍼지는 물결, 가시마다 위상이 달라 어긋나게 나부낌
    strand = k.texture("strand", strand_tex())
    sp, rng = spines()
    for (root, d, segs, length) in sp:
        w = kit.Wave(amp=(14, 22)[:segs], lag=60, bias=(0, 6)[:segs], phase=float(rng.randint(0, 360)))
        dd = _unit(d)
        tw = np.cross(dd, (0, 1, 0)) if abs(dd[1]) < 0.9 else np.array((1.0, 0, 0))
        s.band(root, tuple(dd), length=length, width=0.14 if segs == 2 else 0.16, segs=segs, thick=0.02, wave=w, toward=tuple(tw), tex=strand, face=False)
    # 늘어진 짧은 막대기 둘(원형 DNA): 얼굴 아래 양옆에서 옆 아래로, 느리게 흔들림
    for side in (1, -1):
        w = kit.Wave(amp=(10, 14), lag=60, phase=0 if side > 0 else 140)
        s.rod(h.point(side * 0.8, -0.8, -0.2), (side * 0.7, -1.0, -0.1), length=0.5, segs=2, thick=0.09, wave=w, toward=(side, 0, 0))

    # ---- 이음줄: 길이 1 블록(+Z), 두 판을 십자로 + 가운데 매듭(박동) + 줄을 타고 가는 알갱이
    v = k.model("g36_vein", poses=4, period=20, mut2=False, open=False, torn=False)
    vs = v.state("")
    tv = k.texture("vein_v", vein_tex())
    th = k.texture("vein_h", np.ascontiguousarray(np.transpose(vein_tex(seed=40), (1, 0, 2))))
    half = 0.085
    vs.box((-half, 0.0, 0.0), (half, 0.0, 1.0), {"up": tv, "down": tv}, faces=("up", "down"), uv=[0, 0, 16, 16], face=True)
    vs.box((0.0, -half, 0.0), (0.0, half, 1.0), {"east": th, "west": th}, faces=("east", "west"), uv=[0, 0, 16, 16], face=True)
    knot = k.texture("knot", knot_tex())
    # 매듭은 Java 가 길이만큼 늘이므로 모델에서는 짧게(4블록 줄에서 0.28 블록쯤)
    for f in vs.frames():
        with f:
            r = (0.075, 0.125, 0.1, 0.085)[f.k]
            vs.box((-r, -r, 0.5 - 0.035), (r, r, 0.5 + 0.035), knot, uv=[0, 0, 16, 16], face=False)
            z = (0.12, 0.36, 0.64, 0.88)[f.k]
            vs.box((-0.045, -0.045, z - 0.012), (0.045, 0.045, z + 0.012), knot, uv=[0, 0, 16, 16], face=False)


def beat(seed=0):
    """쿵: 심장 박동 한 번(낮은 두 겹 울림)"""
    a = audio.env(audio.tone(62 + seed, 0.16, glide=44), 0.002, 0.04, 0.3, 0.09)
    b = audio.at(audio.env(audio.tone(78 + seed, 0.12, glide=52), 0.002, 0.03, 0.25, 0.07) * 0.6, 0.11)
    n = audio.env(audio.lowpass(audio.noise(0.2, seed), 300), 0.002, 0.03, 0.1, 0.08) * 0.3
    return audio.normalize(audio.fade(audio.mix(a, b, n), 0.002, 0.04), 0.9)


def shriek(seed=0, dur=0.6):
    """이어진 게이브가 맞을 때: 박동 하나가 비명처럼 늘어짐"""
    x = audio.tone(170 + seed * 8, dur, "tri", vib=0.05, vib_rate=13, glide=640)
    y = audio.tone(84, dur, glide=60) * 0.5
    return audio.normalize(audio.fade(audio.env(audio.bandpass(audio.mix(x, y), 120, 3200), 0.01, 0.1, 0.8, 0.2), 0.005, 0.08), 0.75)


def snap(seed=0):
    """핏줄이 끊김: 젖은 것이 뚝 끊어지는 소리"""
    c = audio.env(audio.bandpass(audio.noise(0.06, seed), 500, 4000), 0.0005, 0.01, 0.2, 0.03)
    t = audio.env(audio.tone(300, 0.1, "tri", glide=90), 0.001, 0.02, 0.3, 0.05) * 0.7
    return audio.normalize(audio.fade(audio.mix(c, t), 0.001, 0.02), 0.85)


def sounds(k: kit.Kit):
    # 이 종의 신호: 이어진 수만큼 서로 어긋난 심장 박동
    k.sound("beat", "쿵, 쿵쿵", synth=lambda i: beat(seed=i * 3), volume=0.9, attenuation=24)
    k.sound("shriek", "늘어지는 박동", synth=lambda i: shriek(seed=364 + i), attenuation=24)
    k.sound("snap", "핏줄이 끊김", synth=lambda i: snap(seed=368 + i), attenuation=16)
    k.sound("link", "핏줄이 꽂힘", vanilla=[("mob/slime/small1", 0.7), ("mob/slime/small3", 0.65)], attenuation=16)


def phrases():
    return {"cause_g36": "실핏줄을 놔뒀다", "notice_g36_link": "실핏줄이 떼를 잇는다"}
