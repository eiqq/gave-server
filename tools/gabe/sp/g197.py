"""G197 수 채우는 안개 게이브(4막 34라운드, 정예 + 방해): 폭 16, 높이 5블록의 희게 빛나는 안개 더미와 그 속의 등불잡이 셋.

안개 더미는 점묘로 비치는 흰 판 겹(세로 판 여럿을 엇갈려 세우고 위에 납작한 판)이고, 겉에 흐린 _Gave 얼굴들이 떠올랐다 풀어진다(일렁임 4자세).
등불잡이는 키 3블록 엔드 막대기 지팡이(손잡이는 갈고리로 휨)를 짚은 1블록 얼굴이 지팡이 끝에 작은 얼굴 등롱을 매단 것. 사람을 가두면 'held'(붉은 등롱).

- gave:gabe/g197(shrink 0.15): 안개 가운데 바닥 위 2.5(Java HOVER) = 엔진 pos. 상태 "".
- gave:gabe/g197_bearer(shrink 0.8): 등불잡이(지팡이 밑 끝 (0, -1.6, 0) 이 땅, 얼굴 y 0.6). 상태 ""(등롱 흰빛), "held"(붉은 등롱, 얼굴 눈 뜸).
소리 whisper(안개 속 속삭임, 48), hook(갈고리 낚음, 24), strike(지팡이 후려침, 16).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.15
W, H, D = 16.0, 5.0, 12.0
FOG_FACES = (faces.Face(eyes="half"), faces.Face(), faces.Face(eyes="none"), faces.Face(extras=("tears",)), faces.Face(eyes="open", mouth="none"))


def fog_tex(seed, dens, n=48):
    rng = np.random.RandomState(seed)
    bay = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    c = (n - 1) / 2
    blobs = [(rng.uniform(-0.4, 0.4), rng.uniform(-0.3, 0.3), rng.uniform(0.45, 0.7)) for _ in range(5)]
    r = np.min([np.hypot((xx - c) / c - bx, (yy - c) / c - by) / br for bx, by, br in blobs], axis=0)
    d = dens * np.clip((1 - r) / 0.5, 0, 1)
    keep = bay[yy.astype(int) % 4, xx.astype(int) % 4] < d
    v = 214 + rng.rand(n, n) * 30
    out = np.zeros((n, n, 4), np.uint8)
    out[..., 0] = np.clip(v, 0, 255)
    out[..., 1] = np.clip(v + 4, 0, 255)
    out[..., 2] = np.clip(v + 8, 0, 255).astype(np.uint8) & 0xF8
    out[..., 3] = np.where(keep, 255, 0)
    return faces._fill_rest(out)


def fog(s, k):
    texs = [k.texture(f"fog{i}", fog_tex(197 + i, 0.55 + 0.1 * (i % 3))) for i in range(4)]
    rng = np.random.RandomState(197)
    # 세로 판(엇갈려 세움): 아래는 넓고 위로 갈수록 좁게
    for i in range(10):
        a = i * 18.0 + rng.uniform(-6, 6)
        w = rng.uniform(9, 15)
        h = rng.uniform(3.2, 4.8)
        x, z = rng.uniform(-2.5, 2.5), rng.uniform(-2.0, 2.0)
        c = (x, -2.5 + h / 2, z)
        s.quad(c, (w, h), "south", texs[i % 4], rot=(0, a, 0), origin=c)
        s.quad(c, (w, h), "north", texs[(i + 1) % 4], rot=(0, a, 0), origin=c)
    for j, (y, w) in enumerate(((-1.4, 15.0), (0.4, 12.0), (1.9, 8.0))):
        s.quad((0, y, 0), (w, w * 0.75), "up", texs[j], rot=(0, j * 30, 0), origin=(0, y, 0))
        s.quad((0, y, 0), (w, w * 0.75), "down", texs[(j + 2) % 4], rot=(0, j * 30, 0), origin=(0, y, 0))
    # 겉에 떠올랐다 풀어지는 흐린 얼굴들(4자세: 자세마다 다른 자리의 얼굴만)
    spots = [(rng.uniform(0, 360), rng.uniform(-1.6, 1.6), rng.uniform(0.8, 1.3), FOG_FACES[i % len(FOG_FACES)]) for i in range(16)]
    for fr in s.frames(poses=4):
        with fr:
            for i, (a, y, sz, f) in enumerate(spots):
                if (i + fr.k) % 4 > 1:
                    continue
                ra = math.radians(a)
                c = (math.sin(ra) * W * 0.33, y, math.cos(ra) * D * 0.33)
                s.quad(c, (sz, sz), "south", k.face_tex(f), face=True, rot=(0, a, 0), origin=c)
    s.front = (0, 0, 0)


def bearer(s, k, held):
    rot_hook = (0, 0, 0)
    s.rod((0.0, -1.6, 0.0), (0, 1, 0), length=3.0, segs=1, thick=0.12, base=False, wave=None, rag=False, toward=(0, 0, 1))
    s.rod((0.0, 1.4, 0.0), (0, 0.3, 1), length=0.5, segs=1, thick=0.1, base=False, wave=None, rag=False)
    s.rod((0.0, 1.55, 0.46), (0, -1, 0.2), length=0.3, segs=1, thick=0.09, base=False, wave=None, rag=False, toward=(0, 0, 1))
    s.head(size=1.0, depth=0.8, at=(-0.62, 0.55, 0.0), face="open" if held else "smile", near=True, open=False, torn=False)
    s.chain((0.0, 1.25, 0.52), (0.0, 0.75, 0.55), links=3, sag=0.05, size=0.07)
    lf = faces.Face(eyes="bloodshot", mouth="torn", blush="deep") if held else faces.Face(eyes="half", blush="gold")
    s.head(size=0.42, depth=0.42, at=(0.0, 0.5, 0.55), face=lf, near=False, open=False, torn=False)
    for dx in (-0.24, 0.24):
        for dz in (0.31, 0.79):
            s.box((dx - 0.03, 0.22, dz - 0.03), (dx + 0.03, 0.78, dz + 0.03), "bone", face=True)
    _ = rot_hook


def build(k: kit.Kit):
    m = k.model(poses=4, period=40, shrink=S, mut2=False, open=False, torn=False, rag=False)
    fog(m.state(""), k)
    b = k.model("g197_bearer", poses=1, period=20, shrink=0.8, mut2=False, open=False, torn=False, rag=False)
    bearer(b.state(""), k, False)
    bearer(b.state("held"), k, True)
    k.icon(model="g197_bearer", state="")


def sounds(k: kit.Kit):
    k.sample("whisper", ["oga_madness/a3_menacing#3", "oga_madness/a2_scared#2", "lrsf_horror/breath_scared_breath_scared_05"], "안개 속 속삭임",
             pitch=0.85, attenuation=48, level=-12)
    k.sample("hook", "lrsf_horror/gate_open_00", "갈고리가 낚아챔", pitch=0.8, attenuation=24, n=1)
    k.sample("strike", "kenney_impact/impactpunch_medium_000", "지팡이 후려침", pitch=0.8, attenuation=16, n=1)


def phrases():
    return {"cause_g197": "안개 속에서 숨이 막혔다", "cause_g197_hook": "등불잡이 갈고리에 낚였다", "cause_g197_staff": "등불잡이 지팡이에 맞았다",
            "notice_g197_count": "안개가 수를 채우러 왔다", "notice_g197_back": "안개가 붉은 등롱을 들고 돌아왔다"}
