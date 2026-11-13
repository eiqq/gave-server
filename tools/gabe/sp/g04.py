"""G04 뭉텅이 떼 게이브: 얼굴 10개가 볼을 맞대고 짓눌린 지름 1.5 블록 공. 얼굴 사이로 엔드 막대기 끝이 털처럼 삐죽 나와 한꺼번에 펄럭인다.

모델 gave:gabe/g04(공, 디스플레이 하나): 상태 ""(얼굴 10), "n7"(다시 뭉친 7~8), "n5"(5~6). 굴림은 자세 8 장(앞으로 한 바퀴 = 주기 30틱,
Java 가 빠르기에 맞춰 look.rate, 멈추면 freeze). 공 얼굴은 멀리서만 보이므로(12블록 안에 들면 터짐) 가까운 얼굴은 낱개에만.
모델 gave:gabe/g04_m(터져 나온 낱개, 각자 디스플레이 하나): 상태 ""(실눈 a), "b"(실눈 c). 0.4 블록 얼굴 + 가오리 지느러미 같은 짧은 막대기 둘.
"""
import math

import numpy as np

from gabe import audio, kit

R = 0.56         # 공 반지름(얼굴 판 가운데까지)
FACE = (0.56, 0.48)


def _unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def fib(n, seed=0.37):
    pts = []
    ga = math.pi * (3 - math.sqrt(5))
    for i in range(n):
        y = 1 - 2 * (i + 0.5) / n
        r = math.sqrt(max(0.0, 1 - y * y))
        a = ga * i + seed
        pts.append(np.array([math.cos(a) * r, y, math.sin(a) * r]))
    return pts


def frame_for(n, roll):
    """+Z 가 n 을 보는 회전 행렬(roll 도 만큼 n 둘레로 비틂)"""
    n = _unit(n)
    up = np.array([0.0, 1.0, 0.0]) if abs(n[1]) < 0.9 else np.array([1.0, 0.0, 0.0])
    x = _unit(np.cross(up, n))
    y = np.cross(n, x)
    M = np.column_stack([x, y, n])
    return kit.rot_axis(n, roll) @ M


def ball(k, s, count, seed):
    faces_list = ["smile", "smile_a", "smile_c", "half", "smile"]
    rng = np.random.RandomState(seed)
    dirs = fib(count, seed * 0.7)
    rolls = rng.uniform(-35, 35, count)
    squash = rng.uniform(0.82, 1.0, count)
    fur = fib(8, seed * 0.7 + 1.1)
    fur = [_unit(f + 0.35 * dirs[i % count]) for i, f in enumerate(fur)]
    core = k.part_tex("flesh")
    rod = k.part_tex("rod")
    for f in s.frames():
        with f:
            Rr = kit.rot_axis((1, 0, 0), 360.0 * f.t)  # 앞으로 굴림(위가 +Z 로)
            # 속살(얼굴 사이로 보이는 살): 굴림과 같이 도는 상자 하나
            M = Rr
            ang = kit.euler_of(M)
            c = 0.3 * (0.8 + 0.2 * count / 10)
            s.box((-c, -c, -c), (c, c, c), core, uv=[0, 0, 16, 16], rot=ang, origin=(0, 0, 0), face=False)
            for i, d in enumerate(dirs):
                n = Rr @ d
                M = frame_for(n, rolls[i])
                Rk = R * (0.92 + 0.08 * count / 10)
                ctr = n * Rk
                w, h = FACE[0] * squash[i], FACE[1] * (2 - squash[i])
                e = s.box(tuple(ctr - np.array([w / 2, h / 2, 0.005])), tuple(ctr + np.array([w / 2, h / 2, 0.005])),
                          {"south": k.face_tex(faces_list[i % len(faces_list)])}, faces=("south",), uv=[0, 0, 16, 16],
                          rot=kit.euler_of(M), origin=tuple(ctr))
            for i, d in enumerate(fur):
                wav = 22 * math.sin(2 * math.pi * 2 * f.t + i * 0.9)
                tang = _unit(np.cross(d, (0, 1, 0)) if abs(d[1]) < 0.9 else np.cross(d, (1, 0, 0)))
                dd = kit.rot_axis(tang, wav) @ d
                n = Rr @ dd
                base = Rr @ (d * (R * 0.75))
                L = 0.42
                ctr = base + n * L / 2
                M = frame_for(n, 0)
                # 막대기 끝: 가는 네모 막대기(옆 네 면, 상자 +Y 를 n 으로)
                MY = np.column_stack([M[:, 0], n, np.cross(M[:, 0], n)])
                s.box(tuple(ctr - np.array([0.04, L / 2, 0.04])), tuple(ctr + np.array([0.04, L / 2, 0.04])), rod,
                      faces=("north", "south", "east", "west"), uv=[0, 0, 2, 15], rot=kit.euler_of(MY), origin=tuple(ctr))


def member(k, s, face):
    h = s.head(size=0.4, depth=0.3, face=face)
    for side in (1, -1):
        s.rod(h.point(side * 0.95, -0.4, -0.5), (side * 1.0, -0.05, -0.45), length=0.5, segs=2, thick=0.07,
              wave=kit.Wave(amp=(24, 22), lag=55, bias=(-6, 4), shape="flap", phase=0 if side > 0 else 25), poses=4)


def build(k: kit.Kit):
    m = k.model(poses=8, period=30, mut2=False, torn=False, open=False)
    ball(k, m.state(""), 10, 3)
    ball(k, m.state("n7"), 8, 5)
    ball(k, m.state("n5"), 6, 7)
    mm = k.model("g04_m", poses=4, period=12, mut2=False, torn=False)
    member(k, mm.state(""), "smile_a")
    member(k, mm.state("b"), "smile_c")
    k.icon(model="g04", state="")


def sounds(k: kit.Kit):
    # 꾸물꾸물: 얼굴끼리 짓눌리며 구르는 마찰음(공 하나에서)
    k.sound("rub", "꾸물꾸물", vanilla=[("block/honeyblock/slide1", 0.75), ("block/honeyblock/slide3", 0.7)],
            synth=lambda i: rub(seed=400 + i), volume=0.8, attenuation=20)
    # 퍽: 터져 흩어짐
    k.sound("pop", "퍽", vanilla=[("mob/slime/big1", 0.65), ("mob/pufferfish/blow_out1", 0.8)],
            synth=lambda i: audio.squish(seed=410 + i, dur=0.32), volume=1.0, attenuation=24)
    # 낱개 박치기
    k.sound("bump", "콩", vanilla=[("mob/slime/small2", 1.3), ("mob/slime/small4", 1.35)], volume=0.7, attenuation=12)


def rub(seed=0, dur=0.6):
    rng = np.random.RandomState(seed)
    parts = []
    t = 0.0
    while t < dur - 0.1:
        sq = audio.squish(seed=seed * 7 + int(t * 100), dur=0.09 + 0.05 * rng.rand())
        parts.append(audio.at(sq * (0.4 + 0.5 * rng.rand()), t, dur))
        t += 0.07 + 0.06 * rng.rand()
    return audio.normalize(audio.fade(audio.lowpass(audio.mix(*parts), 1600)), 0.75)


def phrases():
    return {"cause_g04": "얼굴 뭉텅이에 치였다"}
