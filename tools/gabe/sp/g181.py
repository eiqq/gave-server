"""G181 잿빛 돌 게이브: 지름 3 블록의 금 간 잿빛 돌덩이. 돌 겉에 _Gave 얼굴 넷이 반쯤 묻혀 사방을 보고, 금 사이로 엔드 막대기 여럿이
수정 가시처럼 삐죽삐죽 돋았다(가장 긴 것 둘은 1.5 블록). 금에서 빛이 샌다(2자세).

모델 gave:gabe/g181(shrink 0.5, 몸 중심 = 돌 가운데 = 땅 위 1.1, 아래쪽은 땅에 박힘): 상태 ""(금 빛 2자세 16틱).
모델 gave:gabe/g181_ground(잿빛 땅 판, 지름 2 블록을 Java 가 키움, 가운데 = 땅 위 0.02): ""(잿빛 땅), "shadow"(떨어질 자리 그림자 원).
모델 gave:gabe/g181_spike(수정 가시 탄, +Z 로 길이 0.7): "".
소리 drone(낯선 울림, 32: 잿빛 땅 안의 사람에게만), fall(떨어져 박힘, 48), spike(가시 쏨, 24), crumble(부푼 것이 바스러짐, 20).
글귀 cause_g181, cause_g181_fall.
"""
import math

import numpy as np

from gabe import kit

R = 1.1  # 돌 반지름(블록)


def stone_tex(seed=181, n=32, crack=None):
    rng = np.random.RandomState(seed)
    col = np.full((n, n, 3), 128, np.float32) + rng.randint(-14, 15, (n, n, 1))
    col += np.kron(rng.randint(-10, 11, (n // 4, n // 4, 1)), np.ones((4, 4, 1)))
    # 금: 무작위 걷기 선 둘
    for c in range(2):
        x, y = rng.randint(4, n - 4), 0
        while y < n:
            col[y, x] = crack if crack is not None else (40, 38, 40)
            if 0 < x < n - 1 and crack is not None:
                col[y, x - 1] = np.array(crack) * 0.75
            y += 1
            x = int(np.clip(x + rng.randint(-1, 2), 1, n - 2))
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    out[..., 3] = 255
    return out


def disc_tex(rgb, ring=None, n=32, seed=1810):
    rng = np.random.RandomState(seed)
    ys, xs = np.mgrid[0:n, 0:n]
    d = np.hypot((xs + 0.5) / n * 2 - 1, (ys + 0.5) / n * 2 - 1)
    col = np.zeros((n, n, 3), np.float32) + rgb + rng.randint(-8, 9, (n, n, 1))
    if ring is not None:
        col[(d > 0.86) & (d <= 1.0)] = ring
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    # 가장자리는 들쭉날쭉(잿빛이 번진 땅)
    edge = 1.0 - (rng.rand(n, n) * 0.08 if ring is None else 0)
    out[..., 3] = np.where(d <= edge, 255, 0).astype(np.uint8)
    return out


SPIKES = [((0.45, 0.85, 0.3), 1.5), ((-0.6, 0.7, -0.4), 1.5), ((0.9, 0.3, -0.5), 0.9), ((-0.9, 0.35, 0.45), 0.8), ((0.1, 0.95, -0.85), 0.7),
          ((-0.3, 0.5, 0.95), 0.6), ((0.75, 0.55, 0.85), 0.7), ((-0.85, 0.6, -0.9), 0.6)]


def build(k: kit.Kit):
    m = k.model(poses=2, period=16, shrink=0.5, mut2=False, torn=False)
    s = m.state("")
    dim = k.texture("stone", stone_tex())
    lit = k.texture("stone_lit", stone_tex(crack=(232, 226, 210)))
    # 돌: 가운데 상자 + 여섯 면 덧댐(둥글게), 금 빛은 2자세로 번갈아
    for f in s.frames():
        with f:
            t = lit if f.k == 0 else dim
            s.box((-R * 0.8, -R * 0.8, -R * 0.8), (R * 0.8, R * 0.8, R * 0.8), t)
            for ax in range(3):
                for sg in (1, -1):
                    lo, hi = [-R * 0.6] * 3, [R * 0.6] * 3
                    lo[ax], hi[ax] = (R * 0.7, R) if sg > 0 else (-R, -R * 0.7)
                    s.box(tuple(lo), tuple(hi), t)
    # 반쯤 묻힌 얼굴 넷(사방)
    for yaw in (0, 90, 180, 270):
        a = math.radians(yaw)
        s.head(size=0.8, depth=0.4, at=(math.sin(a) * (R - 0.08), 0.05, math.cos(a) * (R - 0.08)), face="smile", rot=(0, yaw, 0))
    # 수정 가시(구조 막대기)
    for (x, y, z), ln in SPIKES:
        n = math.sqrt(x * x + y * y + z * z)
        root = (x / n * R * 0.85, y / n * R * 0.85, z / n * R * 0.85)
        s.rod(root, (x, y, z), length=ln, segs=1, thick=0.13 if ln > 1 else 0.1, base=False, rag=False)

    g = k.model("g181_ground", poses=1, mut2=False, open=False, torn=False, rag=False)
    s = g.state("")
    s.quad((0, 0, 0), (2, 2), "up", k.texture("ash", disc_tex((150, 148, 146))))
    s.front = (0, 0, 0)
    s = g.state("shadow")
    s.quad((0, 0, 0), (2, 2), "up", k.texture("shadow", disc_tex((34, 32, 34), ring=(190, 186, 180), seed=1811)))
    s.front = (0, 0, 0)

    sp = k.model("g181_spike", poses=1, mut2=False, open=False, torn=False, rag=False)
    s = sp.state("")
    crys = k.texture("crystal", stone_tex(seed=1812, n=16, crack=(250, 250, 255)))
    s.box((-0.06, -0.06, 0), (0.06, 0.06, 0.7), crys, rot=(0, 0, 45), origin=(0, 0, 0.35))
    s.box((-0.03, -0.03, 0.66), (0.03, 0.03, 0.82), crys)
    s.front = (0, 0, 0)
    k.icon(model="g181", state="")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 귀가 먹먹한 낮은 울림(잿빛 땅 안에서만, Java 가 그 사람에게만)
    k.sample("drone", "tag:alien", "낯선 울림", pitch=0.5, attenuation=32)
    k.sample("fall", "tag:quake", "돌이 떨어져 박힘", pitch=0.8, attenuation=48)
    k.sample("spike", "tag:glass", "수정 가시", pitch=1.4, attenuation=24)
    k.sample("crumble", "tag:breaking", "바스러짐", pitch=0.8, attenuation=20)


def phrases():
    return {"cause_g181": "수정 가시에 박혔다", "cause_g181_fall": "떨어진 잿빛 돌에 깔렸다"}
