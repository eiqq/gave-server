"""G47 문틀에 말린 게이브(황야판: 굴러다니는 회전초): 얼굴을 밀대로 민 듯 1 x 4.8 블록으로 편 막 네 장(마디)이 이어진 띠.
거의 투명해(성긴 점묘) 무늬만 어룽거리고 엔드 막대기 둘만 또렷하다. 평소에는 돌돌 말려 황야를 구른다.

- gave:gabe/g47: 상태 "roll"(말린 공: 마디마다 한 겹, 굴림 축 = 몸 좌우. 기본), "open"(풀려 앞으로 뻗어 펄럭이는 띠),
  "wrap"(사람을 감싼 네 면 기둥, 조였다 풀었다), 그리고 조명탄에 무늬가 드러난 "roll_lit", "open_lit", "wrap_lit"(촘촘한 점묘).
  부위 "gone1" .. "gone4"(그 마디가 없어짐).
  미리 깔리는 종이라 변이 부위는 끔.
- gave:gabe/g47_tips: 가짜 동료(마네킹) 등에 삐져나온 엔드 막대기 끝 둘(원형 DNA, 이것으로 가짜를 알아본다). G49 가짜 동료도 씀.
- 덮개 g47_wrap: 감싸인 사람 화면(늘어난 얼굴 막 너머로 흐릿하게 보임).
"""
import math

import numpy as np
from PIL import Image

from gabe import audio, faces, kit

W = 1.0  # 막 폭
SEG = 1.2  # 마디 길이
BAYER = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32
FLUTTER = kit.Wave(amp=(10, 16, 20, 24), lag=70, bias=(-4, 2, 4, 6))


def membrane_tex(skin, line, n=64):
    """늘인 얼굴 막: 얼굴 그림(32)을 n 으로 키워 점묘로 성기게. skin 살 화소 밀도, line 눈, 입, 머리 덩어리 화소 밀도"""
    far = faces.far(faces.preset("smile"))
    cls = far[..., 2] & 7
    k = n // 32
    out = np.zeros((n, n, 4), np.uint8)
    for y in range(n):
        for x in range(n):
            sy, sx = y // k, x // k
            dens = skin if cls[sy, sx] in (faces.CLS_SKIN, faces.CLS_BLUSH) else line
            if BAYER[y % 4, x % 4] < dens:
                out[y, x, :3] = far[sy, sx, :3]
                out[y, x, 3] = 255
    return out


def strip(i):
    """마디 i(0..3)의 세로 조각 uv(얼굴을 네 토막)"""
    return [0, 4 * i, 16, 4 * (i + 1)]


def rods(s, where):
    """엔드 막대기 둘(또렷함). where: 뿌리 자리 함수(side) → (root, dir)"""
    for side in (1, -1):
        root, d = where(side)
        s.rod(root, d, length=0.75, segs=2, thick=0.09, wave=kit.Wave(amp=(14, 18), lag=60, phase=0 if side > 0 else 180), poses=4)


def roll(s, tex):
    """말린 공: 마디마다 한 겹(반지름 0.42, 0.34, 0.26, 0.18), 여덟 면. 굴림 축은 X(몸 좌우)"""
    for i in range(4):
        r = 0.42 - 0.08 * i
        side = 2 * r * math.tan(math.pi / 8)

        def layer(tx, i=i, r=r, side=side):
            for j in range(8):
                a = 2 * math.pi * j / 8
                c = (0, math.sin(a) * r, math.cos(a) * r)
                u = strip(i)
                h = side * 1.02
                s.box((c[0] - W / 2, c[1] - h / 2, c[2]), (c[0] + W / 2, c[1] + h / 2, c[2]), tx, faces=("north", "south"),
                      uv=[2 * j, u[1], 2 * j + 2, u[3]], rot=(-math.degrees(a), 0, 0), origin=c)

        with s.when(f"!on:gone{i + 1}"):
            layer(tex)
    rods(s, lambda side: ((side * 0.5, 0, 0), (side * 1.0, 0.25, -0.2)))


def ribbon(s, tex):
    """풀린 띠: 몸 가운데를 중심으로 뒤(-2.4)에서 앞(+2.4, +Z)으로 네 마디, 위아래로 펄럭임(자세 6)"""
    for f in s.frames(poses=6):
        with f:
            p = np.array((0.0, 0.0, -2 * SEG))
            cum = 0.0
            for i in range(4):
                cum += FLUTTER.angle(i, f.t)
                d = np.array((0.0, math.sin(math.radians(cum)), math.cos(math.radians(cum))))
                c = tuple(p + d * SEG / 2)

                def one(tx, c=c, cum=cum, i=i):
                    # 눕힌 판(위아래 면): 길이 SEG 가 Z, 폭이 X
                    s.box((c[0] - W / 2, c[1], c[2] - SEG * 0.51), (c[0] + W / 2, c[1], c[2] + SEG * 0.51), tx, faces=("up", "down"),
                          uv=strip(i), rot=(-cum, 0, 0), origin=c)

                with s.when(f"!on:gone{i + 1}"):
                    one(tex)
                p = p + d * SEG
    rods(s, lambda side: ((side * 0.5, 0.04, 0.0), (side * 1.0, 0.35, -0.3)))


def wrap(s, tex):
    """사람을 감싼 네 면 기둥(가운데 = 사람 몸통). 조였다 풀었다(자세 4)"""
    H = 1.9
    for f in s.frames(poses=4):
        with f:
            r = 0.5 - 0.1 * (0.5 - 0.5 * math.cos(2 * math.pi * f.t))
            for i in range(4):
                a = math.pi / 2 * i
                c = (math.sin(a) * r, 0.05, math.cos(a) * r)

                def one(tx, c=c, a=a, i=i):
                    s.box((c[0] - W / 2, c[1] - H / 2, c[2]), (c[0] + W / 2, c[1] + H / 2, c[2]), tx, faces=("north", "south"),
                          uv=[0, 0, 16, 16], rot=(0, math.degrees(a), 0), origin=c)

                with s.when(f"!on:gone{i + 1}"):
                    one(tex)
    rods(s, lambda side: ((side * 0.4, 1.0, 0), (side * 0.7, 1.0, -0.2)))


def cover_img(W_=480, H_=270):
    """감싸인 사람 화면: 옆으로 늘어난 얼굴 막(반투명) 너머로 바깥이 흐릿하게, 가장자리는 짙은 살빛 주름"""
    near = Image.fromarray(faces.near(faces.preset("smile"))).convert("RGBA").resize((W_ * 2, H_), Image.BICUBIC)
    a = np.asarray(near, np.float32)[:, W_ // 2:W_ // 2 + W_].copy()
    yy, xx = np.mgrid[0:H_, 0:W_].astype(np.float32)
    r = np.hypot((xx - W_ / 2) / (W_ / 2), (yy - H_ / 2) / (H_ / 2))
    wr = 0.5 + 0.5 * np.sin(yy * 0.35 + np.sin(xx * 0.02) * 3)
    out = np.zeros((H_, W_, 4), np.float32)
    out[..., :3] = a[..., :3] * (0.82 + 0.1 * wr[..., None])
    out[..., 3] = 96 + 120 * np.clip((r - 0.35) / 0.65, 0, 1) + 22 * wr
    return Image.fromarray(np.clip(np.round(out), 0, 255).astype(np.uint8))


def build(k: kit.Kit):
    faint = k.texture("film", membrane_tex(0.2, 0.55))
    lit = k.texture("film_lit", membrane_tex(0.7, 0.95))
    # 풀린 띠가 4.8 블록이라 반으로 줄여 굽는다(엔진이 Look.scale 로 되돌림)
    m = k.model(poses=6, period=24, shrink=0.5, mut2=False, open=False, torn=False)
    for suffix, tex in (("", faint), ("_lit", lit)):
        roll(m.state("roll" + suffix, poses=4), tex)
        ribbon(m.state("open" + suffix), tex)
        wrap(m.state("wrap" + suffix, poses=4), tex)
    m.default = "roll"
    tips = k.model("g47_tips", poses=4, period=20, mut2=False, open=False, torn=False)
    t = tips.state("")
    for side in (1, -1):
        t.rod((side * 0.1, 0, 0), (side * 0.4, 0.55, -0.75), length=0.5, segs=2, thick=0.07,
              wave=kit.Wave(amp=(14, 18), lag=60, phase=0 if side > 0 else 180))
    k.cover("g47_wrap", cover_img())
    k.icon(model="g47", state="roll", view="side")


def _rustle(seed):
    """'바스락': 마른 종이, 마른 풀이 구겨지는 짧은 소리 여럿"""
    rng = np.random.RandomState(470 + seed)
    parts = []
    t = 0.0
    for i in range(5 + seed):
        d = 0.03 + rng.rand() * 0.05
        n = audio.env(audio.bandpass(audio.noise(d, 471 + seed * 10 + i), 2200, 9000), 0.001, 0.01, 0.4, 0.02)
        parts.append(audio.at(audio.gain(n, 0.5 + rng.rand() * 0.5), t))
        t += 0.04 + rng.rand() * 0.09
    return audio.normalize(audio.fade(audio.mix(*parts), 0.002, 0.03), 0.8)


def _buzz(seed):
    """'지지직': 형광등이 깜빡이며 떠는 소리(이 종 전용 신호)"""
    d = 0.7
    hum = audio.tone(100, d, "square") * 0.25 + audio.tone(200, d, "saw") * 0.12
    n = audio.bandpass(audio.noise(d, 480 + seed), 3000, 8000)
    t = np.arange(len(n), dtype=np.float32) / audio.SR
    rng = np.random.RandomState(481 + seed)
    gate = np.zeros_like(n)
    for _ in range(9):
        a = rng.rand() * (d - 0.06)
        gate[(t >= a) & (t < a + 0.02 + rng.rand() * 0.04)] = 1.0
    return audio.normalize(audio.fade(audio.mix(audio.highpass(hum, 80) * (0.4 + 0.6 * gate), n * gate * 0.7), 0.004, 0.05), 0.75)


def sounds(k: kit.Kit):
    k.sound("rustle", "바스락", vanilla=[("block/deadbush/sandblock_version/bushrustle1", 1.2), ("block/deadbush/sandblock_version/bushrustle2", 1.25)],
            synth=_rustle, volume=0.9, attenuation=20)
    k.sound("buzz", "지지직", synth=_buzz, volume=0.8, attenuation=16)
    k.sound("wrap", "막이 감쌈", vanilla=[("block/honeyblock/slide2", 1.3), ("block/honeyblock/slide4", 1.2)], volume=1.0, attenuation=16)


def phrases():
    return {"cause_g47": "말린 막에 감싸였다"}
