"""G72 불꼬리 운석 게이브: 얼굴 여섯이 서로 볼을 맞대 눌린 1블록 공(여섯 면이 모두 얼굴 + 45도 돌린 얼굴 상자 하나로 모서리가 깎인 덩어리).
짧은 엔드 막대기 셋이 공에 깊이 박혀 꽁지처럼 뒤로 나오고, 그 뒤로 분홍 불꼬리(텍스처 입힌 판 넷, 4자세 일렁임)가 끌린다.

상태: "fly"(날아옴: 굳은 공 + 불꼬리, 기본), "crawl"(땅에 박힌 뒤 갈라져 막대기 다리 셋으로 기기, 자세 4).
모델 gave:gabe/g72_ring: 떨어질 자리의 점묘 붉은 원(판 하나, Java 가 효과 디스플레이에 실어 줄임).
떼 종이라 변이 부위는 끈다. 진행 방향(+Z)은 Java 가 yaw, pitch 로 맞춘다(꼬리가 늘 뒤).
"""
import math

import numpy as np

from gabe import faces, kit

BALL = 0.9


def tail_tex(seed, w=16, h=48):
    """불꼬리: 공 쪽(아래 v=h)이 희고 분홍, 끝(위)으로 갈수록 짙은 분홍 빨강, 가장자리는 잡음으로 들쭉날쭉(컷아웃)"""
    rng = np.random.RandomState(seed)
    noise = rng.rand(h // 4 + 2, w // 2 + 2)
    out = np.zeros((h, w, 4), np.uint8)
    for y in range(h):
        t = y / (h - 1)            # 0 = 끝(위), 1 = 공 쪽(아래)
        half = (0.15 + 0.85 * t ** 0.7) * w / 2
        for x in range(w):
            n = noise[y // 4, x // 2]
            dx = abs(x + 0.5 - w / 2)
            if dx > half * (0.75 + 0.5 * n):
                continue
            core = max(0.0, 1 - dx / max(half, 0.5)) * t
            c0 = np.array([150, 30, 70], float)     # 짙은 분홍 빨강
            c1 = np.array([255, 120, 160], float)   # 분홍
            c2 = np.array([255, 236, 228], float)   # 흰 심
            col = c0 + (c1 - c0) * min(1, t * 1.4) + (c2 - c1) * max(0.0, core - 0.35) * 1.4
            out[y, x, :3] = np.clip(col * (0.85 + 0.15 * n), 0, 255)
            out[y, x, 3] = 255
    return out


def ring_tex(n=64):
    """점묘 붉은 원: 바깥 테가 짙고 안은 성긴 점(컷아웃이라 디더)"""
    out = np.zeros((n, n, 4), np.uint8)
    bayer = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0
    for y in range(n):
        for x in range(n):
            r = math.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) / (n / 2)
            if r > 1.0:
                continue
            v = 0.95 if r > 0.86 else 0.2 + 0.25 * r
            if v > bayer[y % 4, x % 4]:
                out[y, x] = (200, 24, 30, 255) if r > 0.86 else (150, 20, 26, 255)
    return out


def ball(s, k, face="smile"):
    """눌린 얼굴 덩어리: 여섯 면이 얼굴인 상자 + z 축으로 45도 돌린 얼굴 상자(모서리를 깎음)"""
    side = k.face_tex(faces.preset("half"))
    s.head(size=BALL, depth=BALL, face=face, sides=side)
    t2 = {n: k.face_tex(faces.preset(f)) for n, f in (("east", "smile_a"), ("west", "smile_c"), ("up", "open"), ("down", "half"),
                                                      ("north", "smile"))}
    s.box((-0.4, -0.4, -0.42), (0.4, 0.4, 0.42), t2, faces=("east", "west", "up", "down", "north"), uv=[0, 0, 16, 16], rot=(0, 0, 45),
          face=True)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    tails = [k.texture(f"tail{i}", tail_tex(72 + i)) for i in range(2)]

    # ---------------------------------------------------------------- 날아옴: 굳은 공, 뒤로 박힌 막대기 셋, 일렁이는 불꼬리
    f = m.state("fly")
    ball(f, k)
    for a in (90, 210, 330):
        r = math.radians(a)
        f.rod((math.cos(r) * 0.18, math.sin(r) * 0.18, -0.25), (math.cos(r) * 0.25, math.sin(r) * 0.25, -1), length=0.6, segs=1, thick=0.08,
              base=False, rag=False)
    for fr in f.frames():
        with fr:
            tex = tails[fr.k % 2]
            uv = (0, 0, 16, 16) if fr.k < 2 else (16, 0, 0, 16)
            ln = 0.95 + 0.1 * math.sin(2 * math.pi * fr.t)
            c = (0, 0, -BALL / 2 - ln / 2 + 0.05)
            wd = 0.62 + 0.06 * math.cos(2 * math.pi * fr.t)
            for facing in ("up", "down"):
                f.quad(c, (wd, ln), facing, tex, uv=uv)
            for facing in ("east", "west"):
                f.quad(c, (ln, wd), facing, tex, uv=uv, rot=(90, 0, 0), origin=c)
    m.default = "fly"

    # ---------------------------------------------------------------- 기기: 쩍 갈라진 공이 막대기 셋을 다리 삼아
    c = m.state("crawl")
    ball(c, k, face="open")
    for i, a in enumerate((90, 210, 330)):
        r = math.radians(a)
        c.rod((math.cos(r) * 0.3, -0.38, math.sin(r) * 0.3), (math.cos(r) * 0.7, -1, math.sin(r) * 0.7), length=0.55, segs=2, thick=0.08,
              poses=4, wave=kit.Wave(amp=(22, 14), lag=40, phase=i * 120), toward=(0, 1, 0))
    c.box((-0.46, 0.02, -0.02), (0.46, 0.06, 0.47), "flesh", faces=("up", "south"), face=True)  # 갈라진 틈(살)

    rg = k.model("g72_ring", poses=1, mut2=False, open=False, torn=False, rag=False)
    rg.state("").quad((0, 0, 0), (1.0, 1.0), "up", k.texture("ring", ring_tex()))

    k.icon(model="g72", state="fly", view="front")


def sounds(k: kit.Kit):
    # 안개 너머 먼 발사(이 종 전용, 멀리까지)
    k.sample("launch", "tag:cannon", "먼 곳의 발사", pitch=0.7, attenuation=96)
    # 떨어지는 휘파람
    k.sample("whistle", "tag:whistle", "떨어지는 소리", pitch=0.75, volume=0.8, attenuation=48, n=1)
    # 땅에 박힘
    k.sample("impact", "tag:boom-low", "쿵", pitch=0.9, attenuation=40)
    # 공이 쩍 갈라짐
    k.sample("crack", "tag:breaking+stone", "쩍", pitch=1.2, volume=0.8, attenuation=16)


def phrases():
    return {"cause_g72": "불꼬리 운석에 맞았다", "cause_g72_fire": "운석 불에 탔다", "cause_g72_bite": "갈라진 운석에 물렸다"}
