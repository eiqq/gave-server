"""G41 가시 쏘는 게이브: 전갈. 점 입이 귀까지 찢어져 열렸다 닫히는 얼굴(0.8 블록) 뒤로 사마귀 살 마디 셋(배)이 이어지고, 그 끝에서 엔드 막대기
꼬리 둘(원형 DNA)이 머리 위로 높이 휘어 갈래 가시 끝이 정수리 앞 위에 매달린다. 두 꼬리는 머리 위에서 X 로 엇갈린다(정면에서 귀처럼 나란히
서지 않게). 멈출 때마다 꼬리 끝을 제 입으로 '뚝' 부러뜨려 고개를 휘둘러 튕겨 쏜다.

- gave:gabe/g41: 상태 ""(꼬리가 머리 위에서 까딱, 입이 열렸다 닫힘), "snap"(꼬리를 더 숙여 끝을 입 앞으로 가져옴).
  부위 "spent_l", "spent_r"(+X, -X 뿌리 꼬리 끝 가시를 방금 부러뜨림: 다시 자랄 때까지 갈래가 안 보임).
  꼬리가 뒤로 1.1 블록에서 솟아 머리 위로 넘어오므로 0.6 으로 줄여 굽는다(엔진이 Look.scale 로 되돌림).
- gave:gabe/g41_thorn(부품): 날아가는 가시(갈라진 막대기 토막).
- gave:gabe/g41_back: 가시 맞은 사람 등에서 돋는 막대기(남들 눈에만, Java 가 효과 디스플레이에 실어 키움). 상태 "s1"(끝 하나), "s2"(둘),
  "s3"(둘이 길게 날개처럼 퍼덕임).
- gave:gabe/g41_baby: 등에서 터져 나온 새끼 가오리(작은 얼굴 + 짧은 막대기 둘).
"""
import math

import numpy as np

from gabe import audio, faces, kit

# 배 마디 셋: (폭, 높이, 깊이, 가운데 z, 가운데 y)
SEGS = ((0.62, 0.46, 0.36, -0.46, -0.08), (0.52, 0.4, 0.3, -0.8, 0.0), (0.42, 0.34, 0.26, -1.08, 0.1))
# 꼬리: 상태마다 (처음 방향 x 기울기, 물결)
TAIL = {"": (0.22, kit.Wave(amp=(4, 5, 6, 7), lag=40, bias=(24, 42, 48, 44))),
        "snap": (0.12, kit.Wave(amp=(6, 8, 8, 10), lag=20, bias=(26, 46, 52, 50), cycles=2))}


def spike_tex():
    """가시 갈래: 뿌리(위)가 넓고 끝(아래)이 뾰족한 엔드 막대기 조각(컷아웃)"""
    im = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        half = 3.6 * (1 - y / 16.0) + 0.5
        for x in range(16):
            d = abs(x - 7.5)
            if d <= half:
                lit = 252 if x < 8 else 222
                im[y, x] = (lit, lit - 4 if x < 8 else lit - 8, 255 if x < 8 else 236, 255)
        if y % 5 == 3:
            for x in range(16):
                if im[y, x, 3]:
                    im[y, x, :3] = (208, 198, 224)
    return faces._fill_rest(im)


def shut_tex():
    """닫힌 입 조각(얼굴 아래 12줄): 찢어진 자리가 꿰맨 듯 한 줄로 다물린 그림. 먼 얼굴(32)의 19..30 줄을 덮는다"""
    base = faces.draw(faces.preset("blank").with_(tufts=True, blush="pink"))
    c = faces.Canvas(32)
    c.rgb[:], c.a[:], c.cls[:] = base.rgb, base.a, base.cls
    for x in range(1, 31):
        u = (x - 17.5) / 15.5
        yc = int(round(25.5 - 2.4 * u * u))
        c.put(x, yc, "M")
        if x % 3 == 0:
            c.put(x, yc - 1, "m")
    arr = c.rgba()[19:31].copy()
    return arr


def seg_tex(n=16, seed=41):
    """배 마디: 얼굴 살빛 껍질에 가로 마디 골과 붉은 반점(사마귀 살)"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    col = np.array([234, 182, 176], float) * (0.9 + 0.08 * np.cos(xx * 0.8 + yy * 0.4))[..., None] + rng.randn(n, n, 1) * 3
    col[(yy.astype(int) % 6 == 0)] = (186, 112, 116)
    spots = rng.rand(n, n) < 0.1
    col[spots] = (200, 120, 124)
    col[(yy.astype(int) % 6 == 1) & (xx.astype(int) % 3 == 0)] = (250, 220, 212)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def body(s, k, snap):
    h = s.head(size=0.8, depth=0.55, face="torn", torn=False)
    # 입이 열렸다 닫힘: 주기의 앞 절반은 다문 입 조각이 덮는다(먼 얼굴에만. 3블록 안에서는 늘 벌린 사람 입)
    if not snap:
        for f in s.frames(2):
            if f.k == 0:
                with f:
                    s.quad((0, 0.4 - (19 + 6) / 32 * 0.8, 0.275 + 0.004), (0.8, 12 / 32 * 0.8), "south", k.texture("shut", shut_tex(), classes=True),
                           dist="far")
    # 배 마디 셋(얼굴 뒤로 조금씩 높아짐)
    seg = k.texture("seg", seg_tex())
    flesh = k.part_tex("flesh")
    for (w_, h_, d_, z, y) in SEGS:
        s.box((-w_ / 2, y - h_ / 2, z - d_ / 2), (w_ / 2, y + h_ / 2, z + d_ / 2),
              {"up": seg, "east": seg, "west": seg, "north": seg, "down": flesh}, faces=("up", "east", "west", "north", "down"),
              uv=[0, 0, 16, 16], face=True)
    spike = k.texture("spike", spike_tex())
    tilt, tw = TAIL["snap" if snap else ""]
    for side, part in ((1, "spent_l"), (-1, "spent_r")):
        # 꼬리(원형 DNA): 마지막 마디 위에서 솟아 머리 위로 넘어옴(안쪽으로 기울여 X 로 엇갈림)
        w = kit.Wave(amp=tw.amp, lag=tw.lag, bias=tw.bias, cycles=tw.cycles, phase=0 if side > 0 else 40)
        ch = s.rod((side * 0.2, 0.22, -1.12), (-side * tilt, 1.0, -0.15 if not snap else -0.1), length=1.9, segs=4, thick=0.1, wave=w,
                   toward=(0, 0, 1), poses=6)
        # 꼬리 끝 갈래 둘(자세마다 끝을 따라감)
        with s.when("!on:" + part):
            for f in s.frames(6):
                with f:
                    tip = np.array(ch.tip(f.k))
                    d = np.array(ch.dirs[f.k][-1])
                    up = np.array((0.0, 1.0, 0.0)) if abs(d[1]) < 0.9 else np.array((1.0, 0.0, 0.0))
                    side_v = np.cross(d, up)
                    side_v /= np.linalg.norm(side_v)
                    for sg in (1, -1):
                        pd = d * math.cos(math.radians(30)) + side_v * sg * math.sin(math.radians(30))
                        s.band(tuple(tip - d * 0.04), tuple(pd), length=0.3, width=0.09, segs=1, thick=0.02, tex=spike,
                               toward=tuple(np.cross(pd, side_v)), face=False)


def build(k: kit.Kit):
    m = k.model(poses=6, period=30, torn=False, shrink=0.6)
    body(m.state(""), k, False)
    body(m.state("snap"), k, True)

    # 날아가는 가시: 막대기 토막(앞 = 날아가는 쪽) + 갈래 둘
    t = k.model("g41_thorn", poses=1, period=20, mut2=False, open=False, torn=False)
    ts = t.state("")
    ts.rod((0, 0, -0.22), (0, 0, 1), length=0.44, segs=1, thick=0.07, base=False)
    spike = k.texture("spike", spike_tex())
    for sg in (1, -1):
        ts.band((0, 0, 0.2), (sg * 0.5, 0, 0.87), length=0.26, width=0.08, segs=1, thick=0.02, tex=spike, toward=(0, 1, 0), face=False)

    # 등에서 돋는 막대기(모델 가운데 = 사람 발. 어깨뼈 높이 1.15, 등 뒤 0.17). 남들 눈에만 보임
    b = k.model("g41_back", poses=4, period=12, mut2=False, open=False, torn=False)
    for name, n, length, amp in (("s1", 1, 0.3, 6), ("s2", 2, 0.5, 12), ("s3", 2, 0.85, 26)):
        s = b.state(name)
        for i, side in enumerate((1, -1)[:n]):
            s.rod((side * 0.12, 1.15, -0.17), (side * 0.75, 0.45, -0.6), length=length, segs=2, thick=0.07,
                  wave=kit.Wave(amp=(amp, amp * 0.8), lag=50, shape="flap", phase=0), base=False)
            # 피부를 뚫고 나온 자리(살 조각)
            s.box((side * 0.12 - 0.06, 1.15 - 0.06, -0.19), (side * 0.12 + 0.06, 1.15 + 0.06, -0.15), "flesh", uv=[0, 0, 4, 4],
                  faces=("north", "up", "down", "east", "west"))
    b.default = "s1"

    # 새끼 가오리
    y = k.model("g41_baby", poses=4, period=10, mut2=False, torn=False)
    ys = y.state("")
    hh = ys.head(size=0.34, depth=0.24, face="smile")
    for side in (1, -1):
        ys.rod(hh.point(side * 0.9, -0.4, -0.6), (side * 1.0, -0.05, -0.4), length=0.5, segs=2, thick=0.06,
               wave=kit.Wave(amp=(24, 22), lag=50, shape="flap"), base=False)
    k.icon(model="g41", state="")


def _snap(seed):
    """'뚝': 마른 막대기가 부러지는 짧고 딱딱한 소리"""
    c = audio.env(audio.bandpass(audio.noise(0.05, 420 + seed), 900, 6000), 0.0005, 0.01, 0.15, 0.03)
    t = audio.env(audio.tone(700 + 60 * seed, 0.05, "square", glide=260), 0.0005, 0.01, 0.2, 0.03) * 0.5
    return audio.normalize(audio.fade(audio.mix(c, t), 0.001, 0.02), 0.9)


def _flick(seed):
    """'픽': 고개를 휘둘러 튕기는 높은 바람 소리"""
    w = audio.tone(900 + 80 * seed, 0.16, glide=2400, vib=0.0) * 0.4
    n = audio.bandpass(audio.noise(0.16, 425 + seed), 1800, 7000) * 0.5
    return audio.normalize(audio.fade(audio.env(audio.mix(w, n), 0.004, 0.03, 0.5, 0.08), 0.002, 0.04), 0.8)


def _heart(seed):
    """두근(감염된 본인에게만): 낮은 쿵 둘"""
    a = audio.thump(seed=430 + seed, dur=0.14, freq=62)
    b = audio.thump(seed=433 + seed, dur=0.16, freq=54)
    return audio.normalize(audio.cat(a, audio.silence(0.09), audio.gain(b, 0.8), audio.silence(0.2)), 0.85)


def _burst(seed):
    """등에서 터져 나옴: 찢어지는 젖은 소리 + 파닥"""
    r = audio.env(audio.bandpass(audio.noise(0.3, 436 + seed), 500, 3500), 0.005, 0.05, 0.4, 0.15)
    s = audio.squish(seed=438 + seed, dur=0.3)
    return audio.normalize(audio.fade(audio.mix(r, s, audio.at(audio.wet_flap(seed=440 + seed, dur=0.22, low=260) * 0.6, 0.12)), 0.002, 0.05), 0.9)


def sounds(k: kit.Kit):
    # 이 종의 신호: 혼자 꼬리를 입으로 부러뜨리는 '뚝', 튕기는 '픽'
    k.sound("snap", "뚝 부러뜨림", synth=_snap, volume=1.0, attenuation=24)
    k.sound("flick", "픽 튕김", synth=_flick, volume=0.9, attenuation=24)
    k.sound("stick", "가시가 박힘", vanilla=[("random/bowhit1", 1.3), ("random/bowhit2", 1.35)], volume=0.7, attenuation=16)
    # 감염자 등에서 나는 작은 퍼덕(둘레 사람에게 위치 소리), 본인에게만 들리는 두근
    k.sound("flutter", "등에서 나는 작은 퍼덕", vanilla=[("mob/phantom/flap4", 1.9), ("mob/phantom/flap5", 2.0)], volume=0.4, attenuation=10)
    k.sound("heart", "두근", synth=_heart, volume=0.8, attenuation=8)
    k.sound("burst", "등에서 터져 나옴", synth=_burst, volume=1.0, attenuation=24)
    k.sound("peck", "새끼가 쫌", vanilla=[("mob/slime/small2", 1.6), ("mob/slime/small4", 1.7)], volume=0.7, attenuation=12)


def phrases():
    return {"cause_g41": "가시에 맞았다", "cause_g41_back": "등에서 가시가 자랐다"}
