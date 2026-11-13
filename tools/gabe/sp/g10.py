"""G10 쪼개지는 게이브: 얼굴 한가운데를 세로로 가르는 적갈색 바늘땀 이음선(멀리서도 보임). 얼굴 밑에 엔드 막대기 다리 둘(원형 DNA)이 반대 위상으로
뒤뚱뒤뚱 걷는다. 맞으면 이음선을 따라 반쪽 얼굴 둘(실눈 하나, 다리 하나로 깡충), 반쪽이 다시 맞으면 눈 반쪽만 남은 살 조각 둘씩(한 마리 최대 넷).

모델 gave:gabe/g10: 상태 ""(온전: 두 다리 걸음), "half_l"(게이브 왼쪽 +X 반쪽), "half_r", "chunk_a"(오른눈 조각), "chunk_b"(왼눈 조각, 자벌레처럼
기며 막대기 그루터기가 꿈틀). 반쪽과 조각의 앞면도 실눈 뜸(open)에 따라 다른 그림, 3블록 안에서는 사람 얼굴 그림.
"""
import numpy as np

from gabe import audio, faces, kit

SEAM = "stitch"
SEAM_OPEN = faces.Face(eyes="open", extras=("stitch",), name="g10_open")
# 걸음(반대 위상), 반쪽의 외다리 깡충
WALK = kit.Wave(amp=(26, 30), lag=40, bias=(0, -14))
HOP = kit.Wave(amp=(10, 30), lag=30, shape="flap", cycles=2)


def half_head(k, s, side, size=0.8, depth=0.6):
    """반쪽 머리(폭 size/2, 가운데가 개체 자리). side 1 = 게이브 왼쪽(+X) 반(앞면 그림 오른쪽 반 u 8..16)"""
    w, h, d = size / 2, size, depth
    lo, hi = (-w / 2, -h / 2, -d / 2), (w / 2, h / 2, d / 2)
    flesh = k.part_tex("flesh")
    front_u = [8, 0, 16, 16] if side > 0 else [0, 0, 8, 16]
    back_u = [0, 0, 8, 16] if side > 0 else [8, 0, 16, 16]
    tex = {"north": k.skin_tex("back"), "up": k.skin_tex("top"), "down": k.skin_tex("bottom")}
    uv = {"north": back_u, "up": front_u, "down": front_u}
    if side > 0:
        tex["east"], uv["east"] = k.skin_tex("left"), [0, 0, 16, 16]
        tex["west"], uv["west"] = flesh, [0, 0, 16, 16]
    else:
        tex["west"], uv["west"] = k.skin_tex("right"), [0, 0, 16, 16]
        tex["east"], uv["east"] = flesh, [0, 0, 16, 16]
    s.box(lo, hi, tex, uv=uv, faces=("north", "east", "west", "up", "down"), face=True)
    z = d / 2 + 0.002
    for cond, f in (("!open", SEAM), ("open", SEAM_OPEN)):
        with s.when(cond):
            s.quad((0, 0, z), (w, h), "south", k.face_tex(f), uv=front_u, dist="far", face=True)
            s.quad((0, 0, z + 0.004), (w, h), "south", k.face_tex(f, near=True), uv=front_u, dist="near", face=True)
    # 외다리 하나(엔드 막대기): 반쪽 밑에서 바깥 아래로, 깡충
    s.rod((0, -h * 0.47, 0), (side * 0.15, -1.0, 0.0), length=0.47, segs=2, thick=0.1, wave=HOP, toward=(0, 0, 1))


def chunk(k, s, side):
    """눈 반쪽만 남은 살 조각(위가 얼굴 조각, 자벌레처럼 늘었다 줄었다)"""
    flesh = k.part_tex("flesh")
    u = [0, 3, 8, 11] if side < 0 else [8, 3, 16, 11]
    for f in s.frames():
        with f:
            ph = 2 * np.pi * f.t
            ln = 0.3 * (1 + 0.22 * np.sin(ph))
            ht = 0.2 * (1 - 0.18 * np.sin(ph))
            lo, hi = (-0.15, -ht / 2, -ln / 2), (0.15, ht / 2, ln / 2)
            s.box(lo, hi, flesh, faces=("north", "east", "west", "up", "down"), uv=[0, 0, 16, 16])
            for cond, fc in (("!open", SEAM), ("open", SEAM_OPEN)):
                with s.when(cond):
                    s.quad((0, 0, ln / 2 + 0.002), (0.3, ht), "south", k.face_tex(fc), uv=u)
    s.rod((side * -0.05, 0.06, -0.12), (side * -0.3, 0.6, -1.0), length=0.3, segs=2, thick=0.06,
          wave=kit.Wave(amp=(18, 24), lag=70, shape="flap"), poses=4)


def build(k: kit.Kit):
    m = k.model(poses=8, period=20)
    whole = m.state("")
    whole.head(size=0.8, depth=0.6, at=(0, 0.1, 0), face=SEAM)
    for side in (1, -1):
        w = kit.Wave(amp=WALK.amp, lag=WALK.lag, bias=WALK.bias, phase=0 if side > 0 else 180)
        whole.rod((side * 0.2, -0.28, -0.02), (side * 0.12, -1.0, 0.0), length=0.58, segs=2, thick=0.11, wave=w, toward=(0, 0, 1))
    half_head(k, m.state("half_l"), 1)
    half_head(k, m.state("half_r"), -1)
    chunk(k, m.state("chunk_a", poses=4, period=12), -1)
    chunk(k, m.state("chunk_b", poses=4, period=12), 1)
    m.mut2 = dict(root=(0.15, 0.4, -0.2), dir=(0.5, 1.0, -0.6), length=0.55)


def sounds(k: kit.Kit):
    # 지이익: 이음선을 따라 얼굴이 열림
    k.sound("zip", "지이익", vanilla=[("block/bamboo_wood/break2", 0.7)], synth=lambda i: zipper(seed=1000 + i), volume=1.0, attenuation=20)
    # 끼익: 조각이 서로를 더듬어 꿰매 붙음(실 조이는 소리)
    k.sound("creak", "끼익", synth=lambda i: creak(seed=1010 + i), volume=0.85, attenuation=16)
    k.sound("bite", "덥석", vanilla=[("mob/slime/attack1", 1.2), ("mob/slime/attack2", 1.25)], volume=0.8, attenuation=14)


def zipper(seed=0, dur=0.55):
    rng = np.random.RandomState(seed)
    parts = []
    t = 0.0
    rate = 30.0
    while t < dur - 0.02:
        c = audio.env(audio.highpass(audio.noise(0.012, seed + int(t * 1000)), 1800), 0.0005, 0.003, 0.3, 0.006)
        parts.append(audio.at(c * (0.5 + 0.5 * rng.rand()), t, dur))
        t += 1.0 / rate
        rate = min(80.0, rate * 1.06)
    tear = audio.env(audio.bandpass(audio.noise(dur, seed + 5), 900, 4000), 0.05, 0.1, 0.5, 0.15) * 0.35
    return audio.normalize(audio.fade(audio.mix(*parts, tear)), 0.8)


def creak(seed=0, dur=0.5):
    rng = np.random.RandomState(seed)
    f = 420 + 60 * rng.rand()
    x = audio.tone(f, dur, "saw", vib=0.08, vib_rate=23 + 6 * rng.rand(), glide=f * 1.45)
    x = audio.bandpass(x, 500, 2600)
    return audio.normalize(audio.fade(audio.env(x, 0.02, 0.08, 0.7, 0.12)), 0.65)


def phrases():
    return {"cause_g10": "쪼개진 얼굴에 물렸다"}
