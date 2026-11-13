"""G03 막대기 곡예 게이브: 기본 얼굴 하나(0.8 블록), 뒤통수에 꽂힌 엔드 막대기 둘을 고개로 번갈아 머리 위 1.5 블록까지 쳐 올렸다 받는다.

모델 gave:gabe/g03(shrink 0.5: 곡예 포물선이 머리 위로 2 블록 넘게 나감)
  상태 ""(곡예: 20틱 한 바퀴, 자세 8. 앞 반은 막대기 a(게이브 왼쪽 +X), 뒤 반은 막대기 b 가 공중), "ouch"(같은 곡예, 뽑는 순간 찡그린 얼굴).
  부위 "gone_a", "gone_b": 그 막대기를 뽑아 던짐(막대기 숨김, 뒤통수에 빈 구멍). 박자 두 배는 Java 가 look.rate = 2.
모델 gave:gabe/g03_stick(던진 막대기, 부품 디스플레이): 상태 ""(날아감: 8틱에 한 바퀴 돎), "stuck"(땅에 꽂혀 꿈틀).
"""
import math

import numpy as np

from gabe import audio, faces, kit

STICK_L = 0.9      # 막대기 길이(블록)
STICK_T = 0.11     # 굵기
APEX = 1.45        # 머리 위 최고 높이(블록, 뿌리 기준)
REST_TILT = 32.0   # 쉴 때 뒤로 눕는 각(도)


def _unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def stick(s, center, d, length=STICK_L, bend=0.0, thick=STICK_T, flex_axis=(1, 0, 0)):
    """가운데 center, 방향 d 인 두 마디 막대기(가운데에서 bend 도 꺾임: 날며 휘청이는 엔드 막대기)"""
    d = _unit(d)
    half = length / 2
    ax = _unit(np.cross(d, flex_axis)) if np.linalg.norm(np.cross(d, flex_axis)) > 1e-6 else np.array([0.0, 0.0, 1.0])
    c = np.asarray(center, float)
    # 꺾임: 위 마디를 ax 둘레로 bend 만큼
    a = math.radians(bend)
    d2 = d * math.cos(a) + np.cross(ax, d) * math.sin(a)
    root = c - d * half
    tw = (1.0, 0.0, 0.0) if abs(d[0]) < 0.8 else (0.0, 0.0, 1.0)
    s.rod(tuple(root), tuple(d), length=half * 1.02, segs=1, thick=thick, base=False, toward=tw)
    tw2 = (1.0, 0.0, 0.0) if abs(d2[0]) < 0.8 else (0.0, 0.0, 1.0)
    s.rod(tuple(c), tuple(d2), length=half, segs=1, thick=thick, base=False, toward=tw2)


def rest_dir(side, wob=0.0):
    t = math.radians(REST_TILT + wob)
    return _unit((side * 0.32, math.cos(t), -math.sin(t)))


def body(k, s, face):
    h = s.head(size=0.8, depth=0.6, face=face)
    roots = {1: h.point(0.45, 0.72, -0.9), -1: h.point(-0.45, 0.72, -0.9)}
    hole = k.texture("hole", hole_tex())
    for side, part in ((1, "gone_a"), (-1, "gone_b")):
        with s.when("on:" + part):
            r = roots[side]
            # 뒤통수 빈 구멍(얇은 판 둘: 뒤, 위에서 보임). 뿌리는 머리 안쪽(뒤통수 면보다 0.03 안)이라 그 자리에 두면 머리 면에 가려 안 보인다:
            # 막대기가 빠져나오는 뒤통수 위 모서리에, 머리 겉면(위 0.4, 뒤 -0.3)보다 0.004 바깥으로 둔다
            top, back = 0.4, -0.3
            s.box((r[0] - 0.08, top - 0.13, back - 0.004), (r[0] + 0.08, top + 0.004, back + 0.12), hole, faces=("north", "up"),
                  uv=[0, 0, 16, 16])
    for f in s.frames():
        with f:
            for side, part, start in ((1, "gone_a", 0.0), (-1, "gone_b", 0.5)):
                with s.when("!on:" + part):
                    t = (f.t - start) % 1.0
                    root = np.asarray(roots[side])
                    if t < 0.5:
                        u = (t + 1 / 16) / 0.5          # 공중 0..1
                        lift = APEX * 4 * u * (1 - u)
                        drift = np.array([side * 0.18 * math.sin(math.pi * u), lift, 0.28 * math.sin(math.pi * u)])
                        ang = REST_TILT + 360.0 * u     # 한 바퀴 뒤집힘
                        tt = math.radians(ang)
                        d = _unit((side * 0.32 * math.cos(tt), math.cos(tt), -math.sin(tt)))
                        c = root + rest_dir(side) * (STICK_L / 2) + drift
                        stick(s, c, d, bend=18 * math.sin(2 * math.pi * u))
                    else:
                        # 뒤통수에 꽂혀 쉼: 끝이 조금씩 흔들림(펄럭임 DNA)
                        w = math.sin(2 * math.pi * (t - 0.5) / 0.5)
                        d = rest_dir(side, wob=10 * w)
                        c = root + d * (STICK_L / 2 - 0.08)
                        stick(s, c, d, bend=14 * w)
    return h


def hole_tex():
    a = np.zeros((16, 16, 4), np.uint8)
    yy, xx = np.mgrid[0:16, 0:16]
    r = np.hypot(xx - 7.5, yy - 7.5)
    a[..., 3] = 255
    a[..., :3] = (236, 196, 188)
    ring = r < 7.5
    a[ring, :3] = (150, 52, 58)
    core = r < 5.2
    a[core, :3] = (46, 10, 14)
    a[r < 2.2, :3] = (20, 4, 6)
    return a


def build(k: kit.Kit):
    m = k.model(poses=8, period=20, shrink=0.5)
    m.mut2 = dict(root=(0.36, -0.18, -0.18), dir=(1.0, -0.25, -0.35), length=0.75)
    body(k, m.state(""), "smile")
    ouch = faces.Face(eyes="squint", tilt=-24, mouth="torn", extras=("tears",), name="g03_ouch")
    body(k, m.state("ouch"), ouch)

    st = k.model("g03_stick", poses=8, period=8, mut2=False, open=False, torn=False)
    fly = st.state("")
    for f in fly.frames():
        with f:
            a = 2 * math.pi * f.t
            d = (0.0, math.cos(a), math.sin(a))
            stick(fly, (0, 0, 0), d, bend=12 * math.sin(4 * math.pi * f.t))
    stuck = st.state("stuck", period=20)
    stuck.rod((0, -0.02, 0), (0.18, 1.0, -0.35), length=0.75, segs=3, thick=STICK_T, base=False,
              wave=kit.Wave(amp=(6, 12, 18), lag=70, shape="twitch", cycles=2), toward=(1, 0, 0))


def sounds(k: kit.Kit):
    # 통: 고개로 막대기를 쳐 올리고 받는 박자(속 빈 나무 통)
    k.sound("tong", "통", vanilla=[("block/bamboo_wood/step1", 1.35), ("block/bamboo_wood/step3", 1.4)],
            synth=lambda i: audio.thump(seed=300 + i, dur=0.12, freq=420 + 30 * i), volume=0.7, attenuation=20)
    # 푹: 돌아온 막대기가 뒤통수에 다시 꽂힘(뽑을 때도)
    k.sound("puk", "푹", vanilla=[("item/trident/pierce1", 1.35), ("item/trident/pierce2", 1.3)],
            synth=lambda i: audio.squish(seed=310 + i, dur=0.2), volume=0.9, attenuation=16)
    # 휘휘: 날아가거나 돌아오는 막대기(방향을 알려 줌)
    k.sound("whirl", "휘휘", synth=lambda i: whirl(seed=320 + i), volume=0.75, attenuation=20)


def whirl(seed=0, dur=0.55, rate=13.0):
    t = np.arange(int(dur * audio.SR), dtype=np.float32) / audio.SR
    n = audio.bandpass(audio.noise(dur, seed), 500, 2400)
    am = (0.5 + 0.5 * np.sin(2 * np.pi * rate * t)) ** 2
    return audio.normalize(audio.fade(audio.env(n * am, 0.03, 0.1, 0.8, 0.15)), 0.7)


def phrases():
    return {"cause_g03": "날아온 막대기에 맞았다"}
