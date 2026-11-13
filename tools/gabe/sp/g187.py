"""G187 테두리 갉는 게이브(4막 31라운드, 떼 + 목표 압박): 갉는 무리 하나 = 폭 3.6블록의 납작한 띠.

손톱만 한 검은 _Gave 얼굴(살 화소를 검게 바꾼 얼굴, 실눈은 붉은 줄) 수십이 띠로 뭉쳐 땅에 엎드려 우글거리고(4자세), 띠 양 끝에 엔드 막대기 경계 말뚝이
하나씩 꽂혀 있다. 'rise' 면 띠가 사람 쪽으로 벽처럼 일어서며 모든 얼굴이 눈을 뜬다.
무리들이 지나간 바깥 땅은 고리 판 하나(g187_ring: 가운데가 뚫린 새까만 판, 안쪽 반지름 = 판 반폭의 RING_IN 배)로 덮인다. Java 가 고리 반지름에 맞춰 키운다.

- gave:gabe/g187(shrink 0.5): 띠 가운데(0, 0, 0) = 엔진 pos(바닥 + 0.1). 띠는 X 로 3.6, 앞(+Z) = 싸움터 가운데 쪽. 상태 ""(갉음), "rise".
- gave:gabe/g187_ring: 고리 판(3 x 3 블록 판, 안쪽 반지름 1.5 x RING_IN).
소리 gnaw(갉아 먹는 소리, 64), stake(말뚝 박히는 딱, 32), rise(일어나는 우글거림, 24).
"""
import math

import numpy as np

from gabe import faces, kit

S = 0.5
W, D = 3.6, 1.1
RING_IN = 0.2
BLACK = (faces.Face(), faces.Face(eyes="half"), faces.Face(tilt=-14.0), faces.Face(tilt=14.0), faces.Face(mouth="teeth_ring"))
BLACK_OPEN = (faces.Face(eyes="open"), faces.Face(eyes="bloodshot"), faces.Face(eyes="open", mouth="teeth_ring"))


def blacken(arr):
    """살 화소(부류 0)를 거의 검정으로, 눈 줄과 입은 검붉게, 흰자는 흐린 잿빛. 부류 비트 그대로"""
    a = np.array(arr, np.uint8, copy=True)
    cls = a[..., 2] & 7
    rgb = a[..., :3].astype(np.float32)
    lum = rgb.mean(axis=2) / 255.0
    out = np.zeros_like(rgb)
    skin = (cls == 0) | (cls == 2) | (cls == 6)
    out[skin] = np.stack([22 + 14 * lum[skin], 17 + 8 * lum[skin], 22 + 12 * lum[skin]], axis=1)
    line = (cls == 1) | (cls == 3) | (cls == 7)
    out[line] = np.stack([120 + 60 * lum[line], 18 + 20 * lum[line], 24 + 20 * lum[line]], axis=1)
    white = (cls == 4) | (cls == 5)
    out[white] = np.stack([170 * lum[white] + 40, 160 * lum[white] + 30, 160 * lum[white] + 34], axis=1)
    a[..., :3] = np.clip(out, 0, 255).astype(np.uint8)
    a[..., 2] = (a[..., 2] & 0xF8) | cls
    return a


def ring_tex(n=512, seed=187):
    """가운데 반지름 RING_IN(판 반폭 몫) 안은 비고 밖은 새까만 판. 안쪽 가장자리는 갉힌 듯 들쭉날쭉(점묘)"""
    rng = np.random.RandomState(seed)
    c = (n - 1) / 2
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    ang = np.arctan2(yy - c, xx - c)
    r = np.hypot(xx - c, yy - c) / (n / 2)
    bite = 0.012 * np.sin(ang * 37) + 0.008 * np.sin(ang * 91 + 1.3) + 0.006 * np.sin(ang * 173 + 2.1)
    edge = RING_IN + bite
    bay = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32
    soft = np.clip((r - edge) / 0.012, 0, 1)
    keep = bay[yy.astype(int) % 4, xx.astype(int) % 4] < soft
    v = 10 + 6 * rng.rand(n, n) + 5 * np.clip(1 - (r - edge) / 0.05, 0, 1)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., 0] = np.clip(v + 2, 0, 255)
    out[..., 1] = np.clip(v, 0, 255)
    out[..., 2] = np.clip(v + 3, 0, 255).astype(np.uint8) & 0xF8
    out[..., 3] = np.where(keep, 255, 0)
    return faces._fill_rest(out)


def band(s, k, tex_flat, tex_open, rise):
    rng = np.random.RandomState(187)
    cols, rows = 12, 3
    sp = []
    for r in range(rows):
        for c in range(cols):
            x = -W / 2 + (c + 0.5) * W / cols + rng.uniform(-0.05, 0.05)
            z = -D / 2 + (r + 0.5) * D / rows + rng.uniform(-0.05, 0.05)
            sp.append((x, z, rng.uniform(0.3, 0.38), rng.randint(0, 99), rng.uniform(-25, 25)))
    # 바닥 살 깔개(검은 띠)
    s.box((-W / 2, -0.1, -D / 2), (W / 2, -0.04, D / 2), k.texture("mat", blacken(faces.skin_side("top"))), face=True)
    for fr in s.frames(poses=4):
        with fr:
            for i, (x, z, sz, pick, yaw) in enumerate(sp):
                ph = (i * 0.37 + fr.t) * math.tau
                if rise:
                    # 사람 쪽(+Z)으로 일어선 벽: 아래 줄은 낮게, 뒤 줄은 높게
                    y = 0.25 + (z + D / 2) / D * 1.4 + 0.06 * math.sin(ph)
                    c = (x, y, 0.15 - (z + D / 2) * 0.25)
                    tex = tex_open[pick % len(tex_open)]
                    s.quad(c, (sz * 1.2, sz * 1.2), "south", tex, face=True, rot=(-12 + 8 * math.sin(ph), yaw * 0.3, 0), origin=c)
                else:
                    # 엎드려 갉음: 앞(가운데 쪽)을 보며 뒤로 눕힌 얼굴들이 계단처럼(뒤 줄이 조금 높게) 우글거림
                    y = 0.08 + (z + D / 2) / D * 0.32 + 0.05 * (1 + math.sin(ph))
                    c = (x + 0.03 * math.cos(ph), y, z)
                    tex = tex_flat[pick % len(tex_flat)]
                    s.quad(c, (sz * 1.1, sz * 1.1), "south", tex, face=True, rot=(-58 + 10 * math.sin(ph), yaw * 0.4, 0), origin=c)
    # 경계 말뚝 둘(띠 양 끝)
    for side in (1, -1):
        s.rod((side * (W / 2 + 0.05), -0.08, 0.0), (0, 1, 0), length=1.5, segs=1, thick=0.13, base=True, wave=None, rag=False, toward=(0, 0, 1))
    s.front = (0, 1, 0) if not rise else (0, 0, 1)


def build(k: kit.Kit):
    flat = [k.texture(f"black{i}", blacken(faces.far(f)), classes=True) for i, f in enumerate(BLACK)]
    opn = [k.texture(f"blacko{i}", blacken(faces.far(f)), classes=True) for i, f in enumerate(BLACK_OPEN)]
    m = k.model(poses=4, period=12, shrink=S, mut2=False, open=False, torn=False, rag=False)
    band(m.state(""), k, flat, opn, False)
    band(m.state("rise"), k, flat, opn, True)
    rg = k.model("g187_ring", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    rs = rg.state("")
    rs.quad((0, 0, 0), (3.0, 3.0), "up", k.texture("ring", ring_tex()))
    rs.front = (0, 0, 0)
    k.icon(model="g187", state="rise")


def sounds(k: kit.Kit):
    k.sample("gnaw", "tag:eat", "갉아 먹는 소리", pitch=0.7, attenuation=64, level=-10, n=4)
    k.sample("stake", ["lrsf_horror/bonecrack_01", "lrsf_horror/bonecrack_03"], "말뚝이 한 뼘 옮겨 박힘", pitch=0.8, attenuation=32, level=-10, n=2)
    k.sample("rise", ["oga_creature80/bug_02", "oga_creature80/bug_04", "oga_creature80b/bug_05"], "갉던 무리가 일어남", pitch=0.6, attenuation=24)


def phrases():
    return {"cause_g187": "갉는 무리에 덮였다", "cause_g187_edge": "갉아 먹힌 땅에 섰다", "notice_g187": "땅이 끝에서부터 갉아 먹힌다"}
