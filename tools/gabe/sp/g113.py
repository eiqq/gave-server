"""G113 성난 새장 게이브: 엔드 막대기 여덟이 창살로 둘러선 종 모양 새장(높이 2.6, 폭 1.8). 옆 창살 넷의 아래 끝이 다리.
꼭대기 고리에 작은 얼굴 하나가 종 추처럼 달렸고, 새장 안에는 1 블록 얼굴이 등불처럼 매달려 빛난다(부품).

- gave:gabe/g113(새장): 상태 ""(걸음 2자세), "rage"(앞 창살 둘이 문처럼 벌컥 열리고 다리가 두 배 빠르게), "pant"(주저앉아 헐떡임:
  창살 다리가 벌어지고 문은 반쯤 열림, 자세 없음).
- gave:gabe/g113_face(부품): 안의 얼굴 + 등불 테. 부위 hot(주황 테), rage(빨강 테), 없으면 노랑 테. 빨개지면 Java 가 look.mut=1(입 찢어짐).
"""
import math

import numpy as np

from gabe import kit

TOP_Y, MID_Y, BOT_Y = 1.0, 0.25, -1.3
TOP_R, MID_R, BOT_R = 0.28, 0.9, 0.86
ANGLES = [22.5 + 45 * i for i in range(8)]      # 0 도 = 앞(+Z). 22.5, 337.5 가 앞 문
LEGS = (67.5, 112.5, 247.5, 292.5)
DOOR = (22.5, 337.5)


def ring(a, r, y):
    t = math.radians(a)
    return (math.sin(t) * r, y, math.cos(t) * r)


def sub(a, b):
    return tuple(a[i] - b[i] for i in range(3))


def norm(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v), n


def tw(d):
    return (0, 0, 1) if abs(d[1]) > 0.8 else (0, 1, 0)


def glow_tex(rgb, seed):
    rng = np.random.RandomState(seed)
    out = np.zeros((16, 16, 4), np.uint8)
    c = np.array(rgb, float)[None, None, :] * (0.9 + 0.12 * rng.rand(16, 16, 1))
    out[..., :3] = np.clip(np.round(c), 0, 255)
    out[..., 3] = 255
    out[0, :, :3] = np.clip(np.array(rgb) * 1.15, 0, 255)
    return out


def cage(s, k, mode):
    """mode: "" 걸음, "rage" 문 열림과 빠른 걸음, "pant" 주저앉음"""
    walk = mode != "pant"
    cycles = 2 if mode == "rage" else 1
    for a in ANGLES:
        top = ring(a, TOP_R, TOP_Y)
        mid_r, bot_r, bot_y = MID_R, BOT_R, BOT_Y
        if mode == "pant":
            mid_r, bot_r, bot_y = 0.98, 1.25, -1.15
        aa = a
        if a in DOOR and mode in ("rage", "pant"):
            # 문: 앞 창살이 바깥으로 벌컥(rage), 반쯤(pant)
            aa = a + (48 if a < 180 else -48) * (1.0 if mode == "rage" else 0.5)
            mid_r *= 1.12
        mid = ring(aa, mid_r, MID_Y)
        bot = ring(aa, bot_r, bot_y)
        d, ln = norm(sub(mid, top))
        s.rod(top, d, length=ln, segs=1, thick=0.1, base=False, toward=tw(d))
        d2, ln2 = norm(sub(bot, mid))
        if a in LEGS and walk:
            ph = 0 if a in (67.5, 247.5) else 180
            s.rod(mid, d2, length=ln2, segs=1, thick=0.12, base=False, poses=2,
                  wave=kit.Wave(amp=16, phase=ph, cycles=cycles), toward=(0, 0, 1), rag=False)
        else:
            s.rod(mid, d2, length=ln2, segs=1, thick=0.1 if a not in LEGS else 0.12, base=False, toward=tw(d2))
    # 가운데 띠 고리(창살을 묶는 가로 막대기)
    for i, a in enumerate(ANGLES):
        if a in DOOR and mode in ("rage", "pant"):
            continue
        b = ANGLES[(i + 1) % 8]
        if b in DOOR and mode in ("rage", "pant"):
            continue
        p, q = ring(a, MID_R, MID_Y), ring(b, MID_R, MID_Y)
        d, ln = norm(sub(q, p))
        s.rod(p, d, length=ln, segs=1, thick=0.07, base=False, toward=tw(d))
    # 꼭대기 고리, 종 추 얼굴, 안의 얼굴을 매단 줄
    s.box((-0.32, TOP_Y - 0.04, -0.32), (0.32, TOP_Y + 0.1, 0.32), "rod", uv=[2, 0, 6, 16])
    s.rod((0, TOP_Y + 0.1, 0), (0, 1, 0), length=0.18, segs=1, thick=0.08, base=False, toward=(0, 0, 1))
    s.head(size=0.36, depth=0.3, at=(0, TOP_Y + 0.46, 0), face="smile_c", near=False)
    s.rod((0, TOP_Y - 0.04, 0), (0, -1, 0), length=0.32, segs=1, thick=0.05, base=False, toward=(0, 0, 1))
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=2, period=16, shrink=0.55, mut2=False, torn=False)
    cage(m.state(""), k, "")
    cage(m.state("rage"), k, "rage")
    cage(m.state("pant", poses=1), k, "pant")

    f = k.model("g113_face", poses=1, period=20, mut2=False, rag=False)
    fs = f.state("")
    fs.head(size=1.0, depth=0.7, at=(0, 0, 0), face="smile")
    tex = {"": k.texture("glow_y", glow_tex((236, 196, 64), 1)), "hot": k.texture("glow_o", glow_tex((238, 128, 40), 2)),
           "rage": k.texture("glow_r", glow_tex((214, 36, 30), 3))}
    for key, cond in (("", ("!on:hot", "!on:rage")), ("hot", ("on:hot", "!on:rage")), ("rage", ("on:rage",))):
        with fs.when(*cond):
            t = tex[key]
            # 등불 테: 얼굴 둘레 네 변 + 뒤판(얼굴 뒤에서 새어 나오는 빛)
            for (x0, y0, x1, y1) in ((-0.62, 0.5, 0.62, 0.62), (-0.62, -0.62, 0.62, -0.5), (-0.62, -0.5, -0.5, 0.5), (0.5, -0.5, 0.62, 0.5)):
                fs.box((x0, y0, -0.3), (x1, y1, 0.3), t, uv=[0, 0, 16, 4], faces=("north", "south", "east", "west", "up", "down"))
            fs.box((-0.55, -0.55, -0.42), (0.55, 0.55, -0.36), t, uv=[0, 0, 16, 16], faces=("north",))
    fs.front = (0, 0, 1)
    k.icon(model="g113", state="")


def sounds(k: kit.Kit):
    k.sample("bell", "tag:bell", "새장 종", pitch=0.9, attenuation=32)
    k.sample("ting", "tag:metal+click", "창살 튕김", pitch=1.3, volume=0.7, attenuation=20)
    k.sample("stomp", "tag:metal+impact", "창살 다리 찍기", pitch=0.8, attenuation=20)
    k.sample("swing", "tag:metal+heavy", "창살 휘두르기", pitch=0.9, attenuation=24)
    k.sample("pant", "tag:breath", "헐떡임", pitch=0.8, attenuation=20)


def phrases():
    return {"cause_g113": "성난 새장에 맞았다"}
