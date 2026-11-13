"""G13 바람개비 게이브: 0.7 얼굴 앞에서 막대기 둘이 일자로 이어져 바람개비처럼 돈다. 막대기 끝마다 작은 얼굴 하나.

상태: ""(느린 회전: 30도씩 5틱, 끝 얼굴은 늘 똑바로), "spin"(빠른 회전: 막대기 대신 분홍 원반. 원반 둘레에 끝 얼굴 잔상 열둘이
번져 찍힌 그림을 틱마다 돌림, 반투명은 쓸 수 없어 점무늬 구멍으로 흐림을 냄), "limp"(돌진 뒤 1초: 막대기가 흐물흐물 늘어지고
끝 얼굴은 헛구역질, 가운데 얼굴 눈은 빙글빙글 도는 소용돌이).
"""
import math

import numpy as np

from gabe import audio, faces, kit

HEAD = 0.7
HUB_Z = 0.36       # 바람개비 축(얼굴 앞)
ARM = 0.62         # 막대기 하나 길이
TIP = 0.2          # 끝 얼굴 크기
DISC_R = 0.78
PINK = (255, 132, 176)
PINK_D = (222, 84, 136)
SKIN = (255, 226, 219)


def disc_tex():
    """64x64 원반: 둘레로 갈수록 빽빽한 분홍 점무늬(흐림), 회전 줄무늬, 둘레 고리에 끝 얼굴 잔상 열둘(웃음이 번져 보임)"""
    n = 64
    out = np.zeros((n, n, 4), np.uint8)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    dx, dy = xx + 0.5 - n / 2, yy + 0.5 - n / 2
    r = np.hypot(dx, dy) / (n / 2)
    a = np.arctan2(dy, dx)
    rng = np.random.RandomState(13)
    dens = np.clip(0.15 + 0.75 * r ** 1.6, 0, 0.92)
    streak = (np.sin(a * 6 + r * 9) > 0.2)
    dens = np.where(streak, dens, dens * 0.55)
    on = (rng.rand(n, n) < dens) & (r <= 1.0) & (r >= 0.12)
    out[..., :3] = np.where(streak[..., None], np.array(PINK), np.array(PINK_D))
    out[..., 3] = np.where(on, 255, 0)
    # 끝 얼굴 잔상: 둘레 반지름 0.8 에 열둘, 하나마다 호를 따라 세 번 겹쳐 번짐
    for i in range(12):
        for j, fade in enumerate((0.0, -0.08, -0.16)):
            ang = 2 * math.pi * i / 12 + fade
            cx, cy = n / 2 + math.cos(ang) * 0.8 * n / 2, n / 2 + math.sin(ang) * 0.8 * n / 2
            for (ox, oy, col) in ((-2, -1, (60, 20, 20)), (2, -1, (60, 20, 20)), (0, 2, (90, 30, 30))):
                x, y = int(round(cx + ox)), int(round(cy + oy))
                if 0 <= x < n and 0 <= y < n and (j == 0 or rng.rand() < 0.7):
                    out[y, x, :3] = col
                    out[y, x, 3] = 255
            for oy in range(-3, 4):
                for ox in range(-3, 4):
                    x, y = int(round(cx + ox)), int(round(cy + oy))
                    if ox * ox + oy * oy <= 9 and 0 <= x < n and 0 <= y < n and out[y, x, 3] == 0 and rng.rand() < (0.8 if j == 0 else 0.45):
                        out[y, x, :3] = SKIN
                        out[y, x, 3] = 255
    return out


def spiral_tex():
    """16x16 소용돌이 눈(살빛 바탕에 검붉은 나선, 눈 칸을 덮음)"""
    n = 16
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = SKIN
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    dx, dy = xx + 0.5 - n / 2, yy + 0.5 - n / 2
    r = np.hypot(dx, dy)
    a = np.arctan2(dy, dx)
    ink = (np.abs(((r / 1.35 - a / (2 * math.pi)) % 1.0) - 0.5) > 0.3) & (r < 7.2)
    out[..., :3] = np.where(ink[..., None], np.array((89, 27, 27)), np.array(SKIN))
    out[..., 3] = np.where(r < 7.8, 255, 0)
    return out


def head_main(s, face="smile"):
    return s.head(size=HEAD, depth=0.5, face=face)


def hub(s, h):
    # 축: 얼굴 앞으로 나온 받침(엔드 막대기 받침 같은 꼴, 자세 없음)
    s.rod(h.point(0, 0, 1), (0, 0, 1), length=HUB_Z - 0.25 + 0.02, segs=1, thick=0.12, base=True)


def build(k: kit.Kit):
    m = k.model(poses=6, period=30)
    disc = k.texture("disc", disc_tex())
    spiral = k.texture("spiral", spiral_tex())

    # ---------------------------------------------------------------- 느린 회전
    s = m.state("")
    h = head_main(s)
    hub(s, h)
    for fr in s.frames():
        with fr:
            th = math.radians(180.0 * fr.t + 15)
            for side in (1, -1):
                d = (side * math.cos(th), side * math.sin(th), 0.0)
                s.rod((0, 0, HUB_Z), d, length=ARM, segs=2, thick=0.09, base=False)
                tip = (d[0] * (ARM + TIP * 0.45), d[1] * (ARM + TIP * 0.45), HUB_Z)
                s.head(size=TIP, depth=0.12, at=tip, face="smile_a" if side > 0 else "smile_c", near=False, open=False, torn=False)

    # ---------------------------------------------------------------- 빠른 회전(분홍 원반)
    sp = m.state("spin")
    h = head_main(sp)
    hub(sp, h)
    for fr in sp.frames(4):
        with fr:
            ang = 7.5 * fr.k
            sp.quad((0, 0, HUB_Z + 0.02), (2 * DISC_R, 2 * DISC_R), "south", disc, rot=(0, 0, ang), origin=(0, 0, HUB_Z + 0.02))
            sp.quad((0, 0, HUB_Z + 0.01), (2 * DISC_R, 2 * DISC_R), "north", disc, uv=(16, 0, 0, 16), rot=(0, 0, -ang),
                    origin=(0, 0, HUB_Z + 0.01))

    # ---------------------------------------------------------------- 늘어짐(돌진 뒤 1초)
    li = m.state("limp")
    h = head_main(li)
    hub(li, h)
    sick = faces.Face(eyes="half", mouth="torn", blush="pink")
    sway = kit.Wave(amp=(10, 16), lag=70, bias=(0, 0))
    for side in (1, -1):
        ch = li.rod((side * 0.04, 0, HUB_Z), (side * 0.35, -1.0, 0.12), length=ARM, segs=2, thick=0.09, base=False, wave=sway.scaled(1.0 if side > 0 else -1.0),
                    toward=(side, 0, 0))
        for kk, fr in enumerate(li.frames()):
            with fr:
                tip = ch.hinges[kk][-1]
                li.head(size=TIP, depth=0.12, at=(tip[0], tip[1] - TIP * 0.4, tip[2]), face=sick, near=False, open=False, torn=False)
    # 소용돌이 눈(자세마다 90도씩 돌림)
    ep = 1.0 / 16
    for fr in li.frames(4):
        with fr:
            for ex in (8, 24):
                cx = -HEAD / 2 + ex / 32 * HEAD
                cy = HEAD / 2 - 15 / 32 * HEAD
                z = 0.25 + 0.5 * ep
                li.quad((cx, cy, z), (0.21, 0.21), "south", spiral, rot=(0, 0, 90 * fr.k), origin=(cx, cy, z))
    k.icon(model="g13", state="")


def whirr(seed=0, dur=1.1):
    """위이잉: 날개 도는 낮은 윙윙이 점점 높아짐(Java 가 거리에 따라 높이를 더 올림)"""
    t = np.arange(int(dur * audio.SR), dtype=np.float32) / audio.SR
    f = 70 + 160 * (t / dur) ** 1.5
    ph = 2 * np.pi * np.cumsum(f) / audio.SR
    blade = np.sign(np.sin(ph * 2)) * 0.4 + np.sin(ph) * 0.6
    air = audio.bandpass(audio.noise(dur, seed), 300, 2400) * (0.5 + 0.5 * np.sin(ph * 2)) * 0.6
    x = audio.env((blade * 0.5 + air).astype(np.float32), 0.15, 0.2, 0.9, 0.2)
    return audio.normalize(audio.lowpass(x, 3000), 0.75)


def ting(seed=0):
    """팅: 튕긴 탄(맑은 금속음)"""
    x = audio.mix(audio.tone(2900 + 120 * seed % 300, 0.35, "sine"), audio.gain(audio.tone(4410, 0.25, "sine"), 0.4))
    return audio.normalize(audio.env(x, 0.001, 0.05, 0.25, 0.25), 0.7)


def retch(seed=0):
    """작은 헛구역질: 끝 얼굴들의 꺽꺽(낮게 거친 짧은 소리 둘)"""
    parts = []
    for i in range(2):
        v = audio.env(audio.bandpass(audio.noise(0.16, seed * 3 + i), 250, 1300), 0.01, 0.05, 0.5, 0.06)
        g = audio.env(audio.tone(160 - 20 * i, 0.16, "saw", vib=0.1, vib_rate=30), 0.01, 0.05, 0.4, 0.06) * 0.4
        parts.append(audio.cat(audio.mix(v, audio.lowpass(g, 900)), audio.silence(0.09)))
    return audio.normalize(audio.cat(*parts), 0.6)


def sounds(k: kit.Kit):
    k.sound("whirr", "위이잉", synth=lambda i: whirr(180 + i), attenuation=24)
    k.sound("ting", "탄이 튕김", synth=lambda i: ting(i), vanilla=[("random/anvil_land", 2.0)], volume=0.7, attenuation=24)
    k.sound("grind", "원반에 갈림", synth=lambda i: audio.normalize(audio.bandpass(audio.noise(0.3, 190 + i), 600, 5000) * 0.8),
            vanilla=[("item/axe/strip1", 1.4)], attenuation=16)
    k.sound("retch", "끝 얼굴 헛구역질", synth=lambda i: retch(i), volume=0.7, attenuation=12)


def phrases():
    return {"cause_g13": "바람개비 원반에 갈렸다"}
