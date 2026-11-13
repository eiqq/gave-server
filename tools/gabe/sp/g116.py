"""G116 수레바퀴 게이브(3막, 굴렁쇠: 지름 5 블록).

나무 테 수레바퀴. 엔드 막대기 바퀴살 여덟이 굴대에서 테두리로 뻗고, 테두리에 작은 얼굴 여덟이 바깥을 보고 줄지어 박혀 구를 때마다 차례로 땅에 눌린다.
테두리를 따라 분홍 불꽃이 인다(자세 2). 굴대 자리 얼굴은 따로 부품이라 바퀴가 돌아도 늘 수평으로 옆을 본다.

- gave:gabe/g116(shrink 0.4): 굴대 가운데(0, 0, 0) = 엔진 pos, 바닥에서 HOVER 2.5(Java G116.HOVER). 바퀴는 YZ 평면(앞 +Z 로 구름: Java 가 pitch 로 돌림).
- gave:gabe/g116_hub: 굴대 얼굴(1.6 블록, 앞 +Z). Java 가 follow = false 로 굴대 옆에 수평으로 붙임.
소리 roll(불바퀴 소리, 40), spark(불똥, 24), ram(트럭 들이받음, 48).
"""
import math

import numpy as np

from gabe import kit

S = 0.4
R = 2.4
FACES = 8
SPOKES = 8


def wood_img(seed=116, n=16):
    rng = np.random.RandomState(seed)
    out = np.zeros((n, n, 4), np.uint8)
    base = np.array((112, 74, 44), float)
    for y in range(n):
        g = 0.8 + 0.25 * math.sin(y * 1.3 + rng.rand()) + 0.1 * rng.rand()
        out[y, :, :3] = np.clip(base * g * (0.9 + 0.2 * rng.rand(n, 1)), 0, 255)
    out[..., 3] = 255
    return out


def flame_img(k, n=16):
    yy, xx = np.mgrid[0:n, 0:n]
    h = 1 - yy / (n - 1)
    w = 0.45 * (1 - h) + 0.05 + 0.12 * np.sin(xx * 1.7 + k * 2.1) * h
    m = np.abs(xx - 7.5) / 8 < w * 1.6
    out = np.zeros((n, n, 4), np.uint8)
    col = np.where(h[..., None] < 0.35, (255, 236, 244), np.where(h[..., None] < 0.7, (255, 140, 190), (230, 70, 140)))
    out[..., :3] = col
    out[..., 3] = np.where(m, 255, 0)
    return out


def build(k: kit.Kit):
    wood = k.texture("wood", wood_img())
    fl = [k.texture(f"flame{i}", flame_img(i)) for i in range(2)]
    m = k.model(poses=2, period=6, shrink=S, mut2=False, torn=True)
    s = m.state("")
    # 굴대(가로 X) + 양옆 마개
    s.box((-0.55, -0.45, -0.45), (0.55, 0.45, 0.45), wood)
    s.box((-0.7, -0.3, -0.3), (0.7, 0.3, 0.3), "rod")
    # 바퀴살 여덟(엔드 막대기)
    for i in range(SPOKES):
        a = math.radians(i * 360 / SPOKES + 22.5)
        d = (0, math.sin(a), math.cos(a))
        s.rod((0, 0.4 * d[1], 0.4 * d[2]), d, length=R - 0.55, segs=1, thick=0.2, base=False, toward=(1, 0, 0), rag=False)
    # 테두리: 나무 토막 열여섯(접선 방향)
    n = 16
    for i in range(n):
        a0, a1 = math.radians(i * 360 / n), math.radians((i + 1) * 360 / n)
        am = (a0 + a1) / 2
        c = np.array((0, R * math.sin(am), R * math.cos(am)))
        L = 2 * R * math.sin(math.pi / n) + 0.08
        # 상자는 Z 로 길게 만든 뒤 X 축으로 돌려 접선에 눕힘(접선 = (0, cos am, -sin am))
        s.box(tuple(c + (-0.55, -0.26, -L / 2)), tuple(c + (0.55, 0.26, L / 2)), wood, rot=(90 - math.degrees(am), 0, 0), origin=tuple(c))
    # 테두리 얼굴 여덟(바깥을 봄: 구르면 차례로 땅에 눌림)
    for i in range(FACES):
        a = math.radians(i * 360 / FACES)
        c = np.array((0, (R + 0.22) * math.sin(a), (R + 0.22) * math.cos(a)))
        s.head(size=1.0, depth=0.3, at=tuple(c), face="smile" if i % 2 else "smile_a", near=False, rot=(-math.degrees(a), 0, 0), open=True,
               torn=(i % 3 == 0))
    # 분홍 불꽃(양옆, 자세 2 로 일렁임)
    for f in s.frames():
        with f:
            for i in range(12):
                a = math.radians(i * 30 + 15 * f.k)
                c = np.array((0, (R + 0.55) * math.sin(a), (R + 0.55) * math.cos(a)))
                for side in (0.58, -0.58):
                    s.quad(tuple(c + (side, 0, 0)), (0.9, 1.3), "east" if side > 0 else "west", fl[(i + f.k) % 2], rot=(90 - math.degrees(a), 0, 0),
                           origin=tuple(c + (side, 0, 0)))
                # 앞뒤에서도 보이게 접선 쪽을 보는 불꽃(굴러오는 바퀴를 정면에서 볼 때 테두리 위아래로 불길)
                s.quad(tuple(c), (1.0, 1.3), "south", fl[(i + f.k + 1) % 2], rot=(90 - math.degrees(a), 0, 0), origin=tuple(c))
    s.front = (1, 0, 0)
    # 굴대 얼굴(부품)
    hm = k.model("g116_hub", poses=1, period=20, mut2=False, torn=True, rag=False)
    hs = hm.state("")
    hs.head(size=1.6, depth=0.45, face="smile", near=True)
    k.icon(model="g116", state="", view="side")


def sounds(k: kit.Kit):
    k.sample("roll", ["kenney_rpg/creak1", "kenney_rpg/creak2", "kenney_rpg/creak3"], "불바퀴 소리", pitch=0.55, attenuation=40)
    k.sample("spark", "oga_fire_crackle/fire_1", "불똥", pitch=1.2, attenuation=24)
    k.sample("ram", ["kenney_impact/impactpunch_heavy_001", "kenney_impact/impactmining_002"], "바퀴가 들이받음", pitch=0.6, attenuation=48)


def phrases():
    return {"cause_g116": "불바퀴에 깔렸다", "cause_g116_spark": "옆 사람이 본 불바퀴의 불똥에 탔다"}
