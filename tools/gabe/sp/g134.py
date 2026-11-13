"""G134 북 게이브(4막, 방해, 지원): 1.3 블록 살 북통을 옆으로 뉘었고 양쪽 북면이 다 얼굴(앞은 바로, 뒤는 뒤집힘). 엔드 막대기 다리 둘로 걷고,
북통 위에 솟은 막대기 둘이 북채로 제 양 볼을 번갈아 친다. 북통 둘레 테에 작은 얼굴 방울이 달렸다.

모델 gave:gabe/g134(shrink 0.7) 상태:
  ""      걷기: 다리 둘 4자세, 북채가 느리게 번갈아 침
  "roll"  연타: 다리 가만, 북채가 두 배 빠르게(cycles 2)
  부위 stick_l, stick_r: 그쪽 북채가 부러짐(그루터기만)
가운데(몸 자리) = 북통 가운데, 바닥 위 1.0(Java HOVER 1.0).
소리 drum(북소리, 48), bells(방울, 32), muffle(먹먹한 북, 24).
"""
import math

from gabe import kit

R = 0.58      # 북통 반 높이(위아래), 반 폭
L = 0.66      # 북통 반 길이(앞뒤 = 북면)
FOOT = -1.0


def body(s, k, mode):
    fl = k.part_tex("flesh")
    # 북통(살) + 테 둘(막대기 고리)
    s.box((-R, -R, -L + 0.06), (R, R, L - 0.06), fl, faces=("up", "down", "east", "west"), face=True)
    s.head(size=2 * R + 0.02, depth=0.12, at=(0, 0, L - 0.06), face="smile")
    s.head(size=2 * R + 0.02, depth=0.12, at=(0, 0, -L + 0.06), face="flipped", rot=(0, 180, 0), near=False, open=False, torn=False)
    for z in (L - 0.16, -L + 0.16):
        for (x0, y0), (x1, y1) in (((-R, R), (R, R)), ((R, R), (R, -R)), ((R, -R), (-R, -R)), ((-R, -R), (-R, R))):
            s.rod((x0 * 1.04, y0 * 1.04, z), (x1 - x0, y1 - y0, 0), length=2 * R * 1.04, segs=1, thick=0.06, base=False, rag=False,
                  toward=(0, 0, 1))
    # 방울: 옆 테에 작은 얼굴 넷
    for side, face in ((1, "smile_a"), (-1, "smile_c")):
        for z in (0.28, -0.28):
            s.quad((side * (R + 0.03), -R - 0.12, z), (0.22, 0.22), "east" if side > 0 else "west", k.face_tex(face), face=True)
    # 다리 둘
    for side in (1, -1):
        w = kit.Wave(amp=24, phase=0 if side > 0 else 180) if mode == "walk" else None
        s.rod((side * 0.32, -R + 0.05, 0), (side * 0.12, -1, 0), length=-FOOT - R + 0.05, segs=2, thick=0.11, wave=w, toward=(0, 0, 1),
              poses=4 if w else None, rag=w is not None)
    # 북채 둘: 북통 위에서 솟아 앞으로 굽어 제 볼을 침(번갈아)
    cyc = 2 if mode == "roll" else 1
    for side, part in ((1, "stick_l"), (-1, "stick_r")):
        root = (side * 0.34, R, L - 0.3)
        with s.when("!on:" + part):
            w = kit.Wave(amp=(34, 20), bias=(-10, 30), lag=30, phase=0 if side > 0 else 180, shape="flap", cycles=cyc)
            s.rod(root, (side * 0.35, 1, 0.25), length=1.0, segs=2, thick=0.09, wave=w, toward=(0, 0, 1), poses=4)
        with s.when("on:" + part):
            s.rod(root, (side * 0.4, 1, 0.1), length=0.25, segs=1, thick=0.09, base=False, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=20, shrink=0.7, mut2=False)
    body(m.state(""), k, "walk")
    body(m.state("roll"), k, "roll")


def sounds(k: kit.Kit):
    k.sample("drum", ["kenney_impact/impactsoft_heavy_000", "kenney_impact/impactsoft_heavy_002", "kenney_impact/impactpunch_heavy_001"],
             "북소리", pitch=0.5, attenuation=48)
    k.sample("bells", "kenney_impact/impactbell_heavy_00*", "방울", pitch=1.9, volume=0.45, attenuation=32)
    k.sample("muffle", "kenney_impact/impactsoft_medium_00*", "먹먹한 북", pitch=0.45, volume=0.7, attenuation=24)


def phrases():
    return {"cause_g134": "북소리 충격파에 맞았다", "cause_g134_dry": "북소리에 탄약이 다 빠졌다"}
