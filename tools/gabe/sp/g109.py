"""G109 몰리면 변하는 떼 게이브: 0.4 블록 얼굴 뒤에 살 배(가슴과 배 마디)가 붙고 짧은 엔드 막대기 다리 여섯(양옆 셋씩)으로 걷는 벌레 꼴.

상태: "calm"(기본, 연한 분홍, 다리 4자세), "swarm"(떼 꼴: 얼굴과 배에 검은 줄무늬, 짙은 홍조, 다리가 같은 주기에 두 번 침).
값싼 떼라 변이 부위와 흐물 사체는 끈다(얼어붙은 몸이 통째로 구름).
"""
import numpy as np

from gabe import faces, kit

HEAD, DEPTH = 0.4, 0.3
LEG = dict(length=0.3, segs=1, thick=0.05, base=False, poses=4)


def band_tex(striped):
    """배 마디 겉: 분홍 살(순한 꼴) 또는 검은 줄무늬가 돋은 짙은 살(떼 꼴)"""
    im = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            v = 0.92 + 0.08 * np.sin(x * 1.1 + y * 0.6)
            col = np.array([255, 205, 200]) * v
            if striped:
                col = np.array([236, 120, 132]) * v
                if (x // 3) % 2 == 0:
                    col = np.array([34, 18, 24])
            im[y, x] = (*np.clip(col, 0, 255).astype(np.uint8), 255)
    return im


def ink_tex():
    im = np.zeros((4, 4, 4), np.uint8)
    im[...] = (30, 16, 22, 255)
    return im


def body(k, s, striped, cycles):
    face = faces.Face(blush="deep") if striped else "smile"
    h = s.head(size=HEAD, depth=DEPTH, face=face)
    skin = k.texture("belly_swarm" if striped else "belly", band_tex(striped))
    # 가슴 마디와 배 마디: 얼굴 뒤로 이어져 벌레 몸통이 얼굴보다 길다
    s.box((-0.15, -0.15, -0.42), (0.15, 0.1, -0.15), skin, face=True)
    s.box((-0.19, -0.17, -0.8), (0.19, 0.14, -0.42), skin, face=True)
    if striped:
        ink = k.texture("ink", ink_tex())
        z = DEPTH / 2 + 0.02
        for y in (0.155, -0.165):
            s.quad((0, y, z), (HEAD, 0.05), "south", ink, face=True)
        for x in (-0.175, 0.175):
            s.quad((x, 0.0, z), (0.05, 0.16), "south", ink, face=True)
    for i, zz in enumerate((-0.2, -0.42, -0.66)):
        for side in (1, -1):
            # 세 발씩 엇갈려 딛는다(왼쪽 앞, 뒤 + 오른쪽 가운데)
            ph = 0 if (i + (side > 0)) % 2 == 0 else 180
            s.rod((side * 0.15, -0.1, zz), (side * 1.0, -0.85, 0.0), wave=kit.Wave(amp=26, phase=ph, cycles=cycles),
                  toward=(0, 0, 1), **LEG)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, mut2=False, torn=False, rag=False)
    body(k, m.state("calm"), False, 1)
    body(k, m.state("swarm"), True, 2)
    m.default = "calm"


def sounds(k: kit.Kit):
    # 떼 꼴로 바뀔 때 갑자기 커지는 떼 울음(이 종 신호)
    k.sample("swarm", "tag:bug", "떼 울음", pitch=0.85, attenuation=24)


def phrases():
    return {"cause_g109": "벌레 떼에 뜯겼다"}
