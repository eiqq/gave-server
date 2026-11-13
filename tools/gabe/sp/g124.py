"""G124 도깨비불 게이브: 0.35블록 얼굴을 둘러싼 위가 뾰족한 불꽃(텍스처 입힌 판: 뒤에 선 큰 불꽃 + 머리 위로 엇갈린 불꽃, 4자세로 일렁임).
아래에 짧은 엔드 막대기 둘이 장작처럼 가위표로 엇갈려 받친다. 불꽃(키 0.95, 폭 0.62)이 얼굴보다 커서 멀리서 '불 붙은 작은 얼굴'로 읽힌다.

상태: ""(배부름: 푸른빛 도는 분홍 불), "hungry"(굶주림: 붉고 큰 불, 핏발 선 눈), "dive"(내리꽂힘: 불꽃이 뒤로 길게 끌림).
굶주림 단계의 크기는 Java 가 body.scale 로. 떼 종이라 변이 부위는 끈다.
"""
import math

import numpy as np

from gabe import faces, kit

HEAD = 0.35


def flame_tex(seed, hot, mid, tip, w=32, h=48, lean=0.0):
    """위가 뾰족한 불꽃(아래가 둥글고 넓음): 심은 hot, 가운데 mid, 가장자리와 끝은 tip. 가장자리는 잡음으로 들쭉날쭉(컷아웃)"""
    rng = np.random.RandomState(seed)
    noise = rng.rand(h // 3 + 2, w // 3 + 2)
    out = np.zeros((h, w, 4), np.uint8)
    hot, mid, tip = (np.array(c, float) for c in (hot, mid, tip))
    for y in range(h):
        t = 1 - y / (h - 1)        # 0 = 아래, 1 = 꼭대기
        # 눈물 꼴 폭: 아래 0.3 에서 가장 넓고 꼭대기로 뾰족
        width = (math.sin(math.pi * min(1.0, t / 0.3) / 2) if t < 0.3 else (1 - (t - 0.3) / 0.7) ** 1.3) * w / 2
        cx = w / 2 + lean * t * t * w * 0.3
        for x in range(w):
            n = noise[y // 3, x // 3]
            dx = abs(x + 0.5 - cx)
            if dx > width * (0.78 + 0.4 * n):
                continue
            core = max(0.0, 1 - dx / max(width, 0.5)) * (1 - t) ** 0.6
            col = tip + (mid - tip) * min(1.0, core * 1.8) + (hot - mid) * max(0.0, core - 0.55) * 2.0
            out[y, x, :3] = np.clip(col * (0.88 + 0.18 * n), 0, 255)
            out[y, x, 3] = 255
    return out


CALM = dict(hot=(255, 240, 250), mid=(240, 150, 214), tip=(140, 118, 222))     # 푸른빛 도는 분홍
HUNGRY = dict(hot=(255, 236, 200), mid=(250, 110, 120), tip=(170, 24, 40))     # 붉음


def body(s, k, flames, face, scale=1.0, stream=False):
    s.head(size=HEAD, depth=0.28, face=face)
    for fr in s.frames():
        with fr:
            tex = flames[fr.k % len(flames)]
            sway = 0.03 * math.sin(2 * math.pi * fr.t)
            hgt = (0.95 + 0.06 * math.cos(2 * math.pi * fr.t)) * scale
            wid = 0.62 * scale
            if stream:
                # 뒤로 길게 끌리는 불꽃(진행 쪽 +Z, 불은 -Z 로)
                c = (sway, 0.05, -0.5 * scale)
                s.quad(c, (wid, hgt * 1.4), "up", tex, uv=(0, 16, 16, 0))
                s.quad(c, (wid, hgt * 1.4), "down", tex, uv=(0, 16, 16, 0))
                s.quad(c, (hgt * 1.4, wid), "east", tex, uv=(0, 16, 16, 0), rot=(90, 0, 0), origin=c)
                s.quad(c, (hgt * 1.4, wid), "west", tex, uv=(0, 16, 16, 0), rot=(90, 0, 0), origin=c)
                continue
            c = (sway, -HEAD / 2 + hgt / 2 - 0.08, -0.16)
            s.quad(c, (wid, hgt), "south", tex)
            s.quad(c, (wid, hgt), "north", tex)
            c2 = (0.0, HEAD / 2 + hgt * 0.28 - 0.06, -0.02)
            s.quad(c2, (wid * 0.7, hgt * 0.62), "east", tex)
            s.quad(c2, (wid * 0.7, hgt * 0.62), "west", tex)
    # 장작: 엔드 막대기 둘이 가위표로
    for side in (1, -1):
        s.rod((side * 0.2, -HEAD / 2 - 0.02, -0.06), (-side * 1.0, -0.32, 0.25), length=0.46, segs=1, thick=0.06, base=False, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False)
    calm = [k.texture(f"calm{i}", flame_tex(124 + i, lean=(-0.3, 0.3)[i % 2], **CALM)) for i in range(2)]
    hungry = [k.texture(f"hungry{i}", flame_tex(224 + i, lean=(-0.4, 0.4)[i % 2], **HUNGRY)) for i in range(2)]
    body(m.state(""), k, calm, "smile")
    body(m.state("hungry"), k, hungry, faces.Face(eyes="bloodshot", blush="deep"), scale=1.15)
    body(m.state("dive"), k, hungry, "open", stream=True)
    k.icon(model="g124", state="", view="front")


def sounds(k: kit.Kit):
    # 장작 타는 타닥타닥(굶을수록 Java 가 자주 울림)
    k.sample("crackle", "tag:fire+crackle", "불 타는 소리", pitch=1.1, volume=0.8, attenuation=12)
    # 내리꽂혀 터짐
    k.sample("burst", "tag:explosion-boss", "불 터짐", pitch=1.3, volume=0.9, attenuation=24)
    # 배부름(줄어들며 푸르러짐)
    k.sample("hush", "tag:steam", "불이 잦아듦", pitch=1.6, volume=0.5, attenuation=10)


def phrases():
    return {"cause_g124": "도깨비불이 내리꽂혔다", "cause_g124_burn": "도깨비불에 탔다"}
