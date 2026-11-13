"""G75 배불뚝이 게이브: 엔드 막대기 다리 둘 위에 비치는 분홍 배 자루(폭 2.3, 높이 1.9)가 얹혀 있고, 자루 속에 작은 얼굴들이 바깥을 보며
막에 눌러 붙어 꿈틀댄다. 자루 꼭대기에 진짜 머리인 0.5 블록 얼굴 하나가 꼭지처럼 붙어 있다. 큰 배 실루엣 위 작은 꼭지(키 3.3).
막은 반투명 대신 점묘(알파 0/255 의 64px 1px 바둑 구멍): 구멍 사이로 속 얼굴과 어두운 속살이 보인다.

모델 gave:gabe/g75(원점 = 배 가운데, 다리 끝이 1.85 아래 땅, shrink 0.75) 상태 ""(뒤뚱 걸음 4자세, 속 얼굴 꿈틀 2자세),
"squeeze"(배를 쥐어짬: 다리 굽힘, 꼭지 얼굴이 찡그림, 속 얼굴이 막에 더 눌림), "shrivel"(꼭지를 맞혀 깨끗이 죽을 때: 쭈그러든 빈 자루).
모델 gave:gabe/g75_brat(쏟아지는 새끼 얼굴, G41Baby 가 씀).
소리 slosh(배 출렁), burst(배 터짐), kick(발차기), peck(새끼 물기), 글귀 cause_g75.
"""
import numpy as np

from gabe import faces, kit

INNER = [(a, y) for a, y in ((0, -0.3), (55, 0.35), (110, -0.4), (165, 0.3), (220, -0.25), (275, 0.38), (320, -0.4), (30, 0.4))]
INNER_FACES = ("smile", "half", "smile_a", "tears", "smile_c", "half", "smile", "tears")


def membrane_tex(seed=75, n=64):
    """비치는 배 막(64px): 분홍 살 + 1px 바둑 구멍(투명, 절반), 실핏줄 몇 가닥. 구멍이 잘아 멀리서는 비치는 막으로 보인다"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(int)
    col = np.array([248, 176, 186], float) * (0.9 + 0.1 * np.sin(xx * 0.18 + yy * 0.08))[..., None] + rng.randn(n, n, 1) * 3
    vein = (np.abs((xx * 0.6 + yy) % 30 - 15) < 0.8)
    col[vein] = (196, 92, 108)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    hole = ((xx + yy) % 2 == 0) & ~vein
    out[..., 3] = np.where(hole, 0, 255)
    return out


def core_tex():
    """속살: 어둡고 젖은 붉은빛"""
    yy, xx = np.mgrid[0:16, 0:16].astype(float)
    col = np.array([118, 40, 56], float) * (0.75 + 0.25 * np.sin(xx * 0.9) * np.cos(yy * 0.7))[..., None]
    out = np.zeros((16, 16, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def sack(k, s, name):
    shrivel, squeeze = name == "shrivel", name == "squeeze"
    mem, core = k.texture("membrane", membrane_tex()), k.texture("core", core_tex())
    if shrivel:
        # 쭈그러든 빈 자루: 납작하게 처진 막, 속 얼굴 없음
        s.box((-0.9, -0.95, -0.85), (0.9, -0.35, 0.85), core, uv=[0, 0, 16, 16])
        s.box((-1.0, -0.98, -0.95), (1.0, -0.25, 0.95), mem, uv=[0, 0, 16, 16], face=True)
        s.head(size=0.5, depth=0.45, at=(0, -0.0, 0.1), face=faces.Face(eyes="none", mouth="torn", blush="none", extras=("tears",)))
        s.front = (0, 0, 1)
    else:
        w = 2.15 if squeeze else 2.3
        s.box((-0.7, -0.75, -0.7), (0.7, 0.75, 0.7), core, uv=[0, 0, 16, 16])
        for (bw, y0, y1) in ((1.7, -0.95, -0.55), (w, -0.6, 0.6), (1.7, 0.55, 0.95)):
            s.box((-bw / 2, y0, -bw / 2), (bw / 2, y1, bw / 2), mem, uv=[0, 0, 16, 16], face=True)
        # 속 얼굴: 막 안쪽에 바깥을 보며 눌러 붙음(두 자세로 꿈틀)
        r_in = 0.86 if not squeeze else 0.92
        for f in s.frames(poses=2):
            with f:
                for i, (a, y) in enumerate(INNER):
                    jit = 0.05 * (1 if (f.k + i) % 2 else -1)
                    th = np.radians(a)
                    at = (r_in * np.sin(th), y + jit, r_in * np.cos(th))
                    s.head(size=0.36, depth=0.22, at=at, rot=(0, a, 0), face=INNER_FACES[i], near=False)
        # 꼭지 얼굴(진짜 머리)
        s.head(size=0.5, depth=0.45, at=(0, 1.2, 0.08), face=faces.Face(eyes="half", mouth="torn", extras=("tears",)) if squeeze else "smile")
    # 다리 둘
    for side in (1, -1):
        if shrivel:
            s.rod((side * 0.5, -0.95, 0), (side * 0.9, -0.15, 0.3), length=0.85, segs=2, thick=0.12, toward=(0, 0, 1), rag=False)
        elif squeeze:
            s.rod((side * 0.5, -0.95, 0), (side * 0.25, -1.0, 0.35), length=0.75, segs=2, thick=0.13, toward=(0, 0, 1),
                  wave=kit.Wave(amp=(6, 6), lag=30, bias=(-30, 50), phase=0 if side > 0 else 180, cycles=2), poses=4)
        else:
            s.rod((side * 0.5, -0.95, 0), (side * 0.08, -1.0, 0.05), length=0.92, segs=2, thick=0.13, toward=(0, 0, 1),
                  wave=kit.Wave(amp=(22, 18), lag=50, bias=(0, 12), phase=0 if side > 0 else 180), poses=4)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=0.75, mut2=dict(root=(0.4, 1.3, -0.1), dir=(0.5, 1.0, -0.3), length=0.5, segs=1, poses=2))
    for name in ("", "squeeze", "shrivel"):
        sack(k, m.state(name), name)
    b = k.model("g75_brat", poses=4, period=8, mut2=False, torn=False)
    bs = b.state("")
    bs.head(size=0.34, depth=0.3, face="half", near=False)
    for side in (1, -1):
        bs.rod((side * 0.1, -0.15, 0), (side * 0.4, -1, 0.2), length=0.24, segs=1, thick=0.06, toward=(0, 0, 1), base=False,
               wave=kit.Wave(amp=30, phase=0 if side > 0 else 180))


def sounds(k: kit.Kit):
    k.sample("slosh", "tag:slime", "배 출렁", pitch=0.7, volume=0.9, attenuation=20)
    k.sample("burst", ["oga_water40/splash_05", "tag:flesh"], "배 터짐", pitch=0.75, attenuation=24)
    k.sample("kick", "tag:punch", "발차기", pitch=1.0, attenuation=16)
    k.sample("peck", "tag:bug", "새끼 물기", pitch=1.3, volume=0.8, attenuation=12)


def phrases():
    return {"cause_g75": "배불뚝이 새끼에 뜯겼다"}
