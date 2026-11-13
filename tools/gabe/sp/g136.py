"""G136 총 엉키는 덩치 게이브(4막, 방해, 튼튼이, 거대): 키 4 블록의 땅딸막한 살 덩어리 몸. 몸 앞면에 2 블록 _Gave 얼굴, 등에 굵은 엔드 막대기
둘이 박혔고, 짧은 살 다리 둘로 무겁게 걷는다. 양팔 끝은 살 주먹. 머리 위로 깨진 얼굴 조각 열두 장이 엔드 막대기 고리 둘(지름 6)을 따라 후광처럼
돈다(부품 둘, Java 가 돌림).

모델 gave:gabe/g136(shrink 0.4) 상태:
  ""       걷기: 다리 둘 4자세, 팔은 늘어뜨림
  "pound"  내려치기: 양팔이 머리 위로 올라갔다 앞 땅을 내려침(4자세, 16틱 한 바퀴: 자세 3 이 땅에 닿는 때)
모델 gave:gabe/g136_halo(shrink 0.4): 반지름 3 막대기 고리 + 깨진 얼굴 조각 여섯(고리 가운데가 (0, 0, 0), XZ 면). 부위 s0..s5 = 그 조각이 깨짐(3초 뒤 다시 참)
가운데(몸 자리) = 몸통 가운데, 바닥 위 2.0(Java HOVER 2.0).
소리 static(지지직 잡음, 32), slam(내려치기, 32), step(무거운 걸음, 24), shard(조각 깨짐, 24).
"""
import math

import numpy as np

from gabe import faces, kit

FOOT = -2.0
HALO_R = 3.0


def limb(s, a, b, thick, tex="flesh", wave=None, poses=None, toward=None):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    tw = toward or ((0, 0, 1) if abs(d[2]) < 0.9 * L else (1, 0, 0))
    return s.rod(tuple(a), tuple(d), length=L, segs=1, thick=thick, base=False, tex=tex, rag=False, wave=wave, poses=poses, toward=tw)


def torso(s, k):
    fl = k.part_tex("flesh")
    # 덩어리 몸(얼굴보다 크게): 아래 넓고 위로 좁아지는 두 덩이
    s.box((-1.5, -1.25, -1.15), (1.5, 0.9, 1.0), fl, face=True)
    s.box((-1.2, 0.85, -0.95), (1.2, 1.85, 0.8), fl, face=True)
    # 앞 얼굴 2 블록
    s.head(size=2.0, depth=0.3, at=(0, 0.35, 1.08), face="smile")
    # 등의 굵은 막대기 둘(위로 비스듬히)
    for side in (1, -1):
        s.rod((side * 0.55, 0.9, -1.0), (side * 0.25, 1, -0.45), length=1.7, segs=1, thick=0.22, base=False, rag=False)


def legs(s, walk):
    for side in (1, -1):
        w = kit.Wave(amp=16, phase=0 if side > 0 else 180) if walk else None
        s.rod((side * 0.75, -1.2, 0), (0, -1, 0.02), length=-FOOT - 1.2, segs=1, thick=0.75, tex="flesh", wave=w, toward=(0, 0, 1),
              poses=4 if walk else None, rag=walk)


def arms(s, k, theta):
    """theta: 0 늘어뜨림, 90 앞, 180 머리 위"""
    fl = k.part_tex("flesh")
    t = math.radians(theta)
    for side in (1, -1):
        sh = np.array((side * 1.55, 1.2, 0.2))
        d = np.array((side * 0.25, -math.cos(t), math.sin(t)))
        d /= np.linalg.norm(d)
        hand = sh + d * 2.1
        limb(s, sh, hand, 0.55)
        s.box(tuple(hand - 0.42), tuple(hand + 0.42), fl, face=True)


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, shrink=0.4, mut2=False)
    s = m.state("")
    torso(s, k)
    legs(s, True)
    arms(s, k, 12)

    p = m.state("pound", poses=4, period=16)
    torso(p, k)
    legs(p, False)
    for f in p.frames():
        with f:
            arms(p, k, (110, 175, 150, 55)[f.k])

    h = k.model("g136_halo", poses=1, shrink=0.4, mut2=False, open=False, torn=False, rag=False)
    hs = h.state("")
    n = 12
    pts = [(HALO_R * math.cos(2 * math.pi * i / n), 0.0, HALO_R * math.sin(2 * math.pi * i / n)) for i in range(n)]
    for i in range(n):
        a, b = np.array(pts[i]), np.array(pts[(i + 1) % n])
        hs.rod(tuple(a), tuple(b - a), length=float(np.linalg.norm(b - a)), segs=1, thick=0.09, base=False, rag=False)
    shard_faces = [faces.Face(cut="left"), faces.Face(cut="right", eyes="open"), faces.Face(cut="left", mouth="torn"),
                   faces.Face(cut="right"), faces.Face(cut="left", eyes="half"), faces.Face(cut="right", eyes="bloodshot")]
    for i in range(6):
        a = 2 * math.pi * i / 6
        c = (HALO_R * math.cos(a), 0.0, HALO_R * math.sin(a))
        yaw = -math.degrees(a) + 90
        with hs.when("!on:s%d" % i):
            hs.quad(c, (0.95, 0.95), "south", k.face_tex(shard_faces[i]), rot=(0, yaw, 8 * (1 if i % 2 else -1)), origin=c, face=True)
            hs.quad(c, (0.95, 0.95), "north", k.face_tex(shard_faces[(i + 3) % 6]), rot=(0, yaw, 8 * (1 if i % 2 else -1)), origin=c, face=True)
    hs.front = (0, 0, 0)


def sounds(k: kit.Kit):
    k.sample("static", [{"id": "kenney_scifi/computernoise_000", "end": 1.4}, {"id": "kenney_scifi/computernoise_002", "start": 1.0, "end": 2.4},
                        "oga_sfx100/noise_01"], "지지직 잡음", attenuation=32)
    k.sample("slam", "kenney_impact/impactpunch_heavy_00*", "내려치기", pitch=0.45, attenuation=32)
    k.sample("step", "kenney_impact/footstep_concrete_00*", "무거운 걸음", pitch=0.4, attenuation=24)
    k.sample("shard", "kenney_impact/impactglass_light_00*", "얼굴 조각 깨짐", pitch=1.2, attenuation=24)


def phrases():
    return {"cause_g136": "덩치 주먹에 내려찍혔다"}
