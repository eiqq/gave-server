"""G42 알주머니 게이브: 거대한 하늘 갓(1.6 배). 2.56 블록 얼굴 아래로 엔드 막대기 넷(3.2 블록)이 우산살처럼 거미줄 날개막을 받치고(날개 폭 6.5),
막 아래 알주머니(2.3 블록)가 매달린다. 알주머니엔 개구리 알처럼 작은 얼굴 알이 다닥다닥 붙고 알마다 막대기 둘이 꼬물거린다.

반투명은 컷아웃으로 못 그리므로(반투명 화소 금지) 성긴 점(디더)으로 비쳐 보이게 한다: 평소(탄이 지나감)엔 얼굴, 막대기, 알주머니 겉막이 모두
바둑판 점으로 숭숭 뚫려 뒤가 비치고, '꿀렁' 뒤와 날개막을 펼 때만 꽉 찬 불투명 그림이 된다. 큰 몸이 비쳤다 굳었다 하는 것이 멀리서도 보인다.

- gave:gabe/g42(shrink 0.3): 상태 ""(비침, 처진 날개막), "solid"(불투명, 처진 날개막), "spread"(불투명, 날개막을 활짝 폄).
- gave:gabe/g42_sac(부품, shrink 0.6, Java 가 몸 아래 SAC_DOWN 3.0 에 매담): 상태 ""(겉막이 비쳐 속 알이 보임), "solid"(불투명 겉막).
  부위 d1..d4: 알을 쓴 만큼 그 묶음이 사라짐.
- gave:gabe/g42_baby: 갓 나온 새끼(작은 얼굴 + 짧은 막대기 둘).
"""
import math

import numpy as np

from gabe import audio, faces, kit

K = 1.6  # 옛 1.6 블록 얼굴에서 키운 배
FACE = 1.6 * K
DEPTH = 1.0 * K
RIB = dict(length=2.0 * K, segs=2, thick=0.16 * K)
# 우산살 뿌리(얼굴 아래 네 귀퉁이 안쪽)와 방향(처짐, 폄)
RIBS = [(1, 1), (-1, 1), (-1, -1), (1, -1)]
EGGS = [  # (x, y, z, 얼굴, 돌림 y) 알주머니 속 알 14개: 묶음 넷(4, 4, 3, 3). 옛 크기(K 배로 씀)
    (0.32, 0.22, 0.42, "smile", 10), (-0.3, 0.25, 0.44, "smile_a", -14), (0.02, -0.12, 0.52, "smile_c", 4), (0.36, -0.3, 0.3, "smile", 28),
    (-0.38, -0.28, 0.3, "smile_a", -30), (0.5, 0.05, -0.1, "smile_c", 80), (-0.5, 0.02, -0.08, "smile", -84), (0.05, 0.36, 0.1, "smile_a", 0),
    (0.3, 0.1, -0.44, "smile", 160), (-0.28, 0.12, -0.46, "smile_c", -165), (0.0, -0.36, -0.2, "smile_a", 180),
    (0.2, -0.42, 0.05, "smile", 40), (-0.22, -0.44, 0.02, "smile_c", -50), (0.0, -0.05, -0.55, "smile", 178),
]
GROUPS = ("d4", "d4", "d4", "d4", "d3", "d3", "d3", "d3", "d2", "d2", "d2", "d1", "d1", "d1")


def stipple(arr, phase=0):
    """바둑판 점으로 절반을 뚫음(비쳐 보이는 느낌). 알파는 0/255 만"""
    a = np.array(arr, np.uint8, copy=True)
    h, w = a.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    hole = ((xx + yy + phase) % 2) == 1
    a[..., 3] = np.where(hole, 0, a[..., 3])
    return a


def skin_tex(solid, seed=42):
    """알주머니 겉막: 분홍빛 젖은 막, 핏줄. solid 가 아니면 성긴 점으로 뚫림"""
    n = 32
    rng = np.random.RandomState(seed)
    im = np.zeros((n, n, 4), np.float32)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    base = np.array([236, 170, 176], np.float32)
    shade = 0.9 + 0.1 * np.sin(xx * 0.45 + np.sin(yy * 0.3) * 1.5)
    im[..., :3] = base * shade[..., None] + rng.randn(n, n, 1) * 3
    # 핏줄
    for k2 in range(5):
        x = rng.randint(2, n - 2)
        for y in range(n):
            x += rng.randint(-1, 2)
            if 0 <= x < n:
                im[y, x, :3] = (176, 70, 96)
    # 젖은 반짝임
    for _ in range(10):
        im[rng.randint(0, n), rng.randint(0, n), :3] = (255, 236, 236)
    im[..., 3] = 255
    out = np.clip(np.round(im), 0, 255).astype(np.uint8)
    return out if solid else stipple(out)


def plain_tex(rgb):
    im = np.zeros((4, 4, 4), np.uint8)
    im[...] = (*rgb, 255)
    im[0, :, :3] = [min(255, c + 12) for c in rgb]
    return im


def head_ghost(s, k):
    """비치는 얼굴(직접 짠 상자: 먼 얼굴 그림과 스킨 옆면을 점으로 뚫음. 가까운 얼굴, 오버레이 없음)"""
    lo, hi = (-FACE / 2, -FACE / 2, -DEPTH / 2), (FACE / 2, FACE / 2, DEPTH / 2)
    tex = {"south": k.texture("face_ghost", stipple(faces.far("smile")), classes=True)}
    for name, which in (("north", "back"), ("east", "left"), ("west", "right"), ("up", "top"), ("down", "bottom")):
        tex[name] = k.texture("head_ghost_" + which, stipple(faces.skin_side(which), 1), classes=True)
    s.box(lo, hi, tex, uv=[0, 0, 16, 16], face=True)
    return kit.Head((0, 0, 0), FACE, FACE, DEPTH, np.eye(3))


def canopy(s, k, mode):
    """mode: ghost | solid | spread"""
    ghost = mode == "ghost"
    if ghost:
        h = head_ghost(s, k)
        s.heads.append(h)
        rod_tex = k.texture("rod_ghost", stipple(faces.PARTS["rod"]()))
    else:
        h = s.head(size=FACE, depth=DEPTH, face="smile")
        rod_tex = "rod"
    spread = mode == "spread"
    chains = []
    for i, (sx, sz) in enumerate(RIBS):
        root = h.point(sx * 0.7, -1.0, sz * 0.6)
        d = (sx * 1.0, 0.05 if spread else -0.55, sz * 1.0)
        w = kit.Wave(amp=(7, 10) if spread else (10, 14), lag=50, bias=(0, -6 if spread else 8), phase=i * 90, cycles=2 if spread else 1)
        chains.append(s.rod(root, d, wave=w, toward=(0, 1, 0), tex=rod_tex, poses=4, **RIB))
    for i in range(4):
        s.membrane(chains[i], chains[(i + 1) % 4], tex="web")
    # 막 가운데에서 알주머니로 내려가는 살 줄기(알주머니 부품과 이어짐)
    s.band((0, -FACE / 2, 0), (0, -1, 0), length=0.5 * K, width=0.34 * K, segs=1, thick=0.03, tex="flesh", toward=(0, 0, 1), face=True)


def sac(s, k, solid):
    skin = k.texture("sac_solid" if solid else "sac_ghost", skin_tex(solid))
    # 겉막: 조금 길쭉한 주머니
    s.box((-0.72 * K, -0.62 * K, -0.72 * K), (0.72 * K, 0.62 * K, 0.72 * K), skin, uv=[0, 0, 16, 16], face=True)
    s.box((-0.5 * K, 0.62 * K, -0.5 * K), (0.5 * K, 0.8 * K, 0.5 * K), skin, uv=[0, 0, 12, 3], faces=("north", "south", "east", "west"), face=True)
    if solid:
        return
    leg = k.texture("egg_leg", plain_tex((232, 224, 240)))
    for (x, y, z, face, ry), grp in zip(EGGS, GROUPS):
        with s.when("!on:" + grp):
            hd = s.head(size=0.26 * K, depth=0.2 * K, at=(x * K, y * K, z * K), face=face, near=False, open=False, torn=False, rot=(0, ry, 0))
            # 알마다 꼬물거리는 막대기 둘(납작한 띠, 면을 아낌)
            for side in (1, -1):
                root = hd.point(side * 0.9, -0.5, -0.8)
                d = hd.axis((side * 0.8, -0.5, -0.4))
                tw = hd.axis((0, 1, 0))
                s.band(root, d, length=0.18 * K, width=0.05 * K, segs=1, thick=0.012, tex=leg, toward=tw, face=False, poses=2,
                       wave=kit.Wave(amp=28, phase=(x * 400 + z * 300 + side * 90) % 360))


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, shrink=0.3, mut2=dict(length=1.3 * K, thick=0.13 * K))
    canopy(m.state(""), k, "ghost")
    canopy(m.state("solid"), k, "solid")
    canopy(m.state("spread"), k, "spread")

    sc = k.model("g42_sac", poses=4, period=16, shrink=0.6, mut2=False, open=False, torn=False)
    sac(sc.state(""), k, False)
    sac(sc.state("solid"), k, True)

    b = k.model("g42_baby", poses=4, period=10, mut2=False, torn=False)
    bs = b.state("")
    hh = bs.head(size=0.3, depth=0.22, face="smile_a")
    for side in (1, -1):
        bs.rod(hh.point(side * 0.9, -0.4, -0.6), (side * 1.0, -0.1, -0.5), length=0.42, segs=2, thick=0.055,
               wave=kit.Wave(amp=(26, 24), lag=50, shape="flap"), base=False)
    k.icon(model="g42", state="solid")


def _gulp(seed):
    """'꿀렁': 무거운 물주머니가 출렁이는 낮은 소리(이 종의 신호)"""
    d = 0.7
    f = 74 + 5 * seed
    a = audio.tone(f, d, glide=f * 0.72, vib=0.12, vib_rate=5.5)
    w = audio.env(audio.lowpass(audio.noise(d, 450 + seed), 420), 0.05, 0.15, 0.5, 0.3) * 0.5
    blob = audio.env(audio.tone(210, 0.12, glide=120), 0.004, 0.03, 0.4, 0.06) * 0.5
    return audio.normalize(audio.fade(audio.mix(audio.env(a, 0.04, 0.12, 0.7, 0.3), w, audio.at(blob, 0.22)), 0.01, 0.2), 0.9)


def _pour(seed):
    """'뽀록뽀록': 알이 연달아 빠져나오는 방울 소리"""
    rng = np.random.RandomState(455 + seed)
    d = 1.0
    out = audio.silence(d)
    t = 0.0
    while t < d - 0.1:
        f = 300 + rng.rand() * 260
        p = audio.env(audio.tone(f, 0.06, glide=f * 1.9), 0.003, 0.015, 0.4, 0.03)
        out = audio.mix(out, audio.at(p * (0.5 + 0.5 * rng.rand()), t, d))
        t += 0.085 + rng.rand() * 0.05
    return audio.normalize(audio.fade(out, 0.005, 0.08), 0.8)


def sounds(k: kit.Kit):
    k.sound("gulp", "꿀렁", synth=_gulp, volume=1.0, attenuation=32)
    k.sound("pour", "뽀록뽀록 쏟아짐", synth=_pour, volume=0.9, attenuation=32)
    # 갓 나온 새끼들의 첫 킥킥(쏟을 때 한 번, 작게)
    k.sound("giggle", "갓 나온 킥킥", synth=lambda i: audio.giggle(seed=460 + i, count=4, base=700 + 30 * i, rate=10), volume=0.5, attenuation=16)
    k.sound("peck", "새끼가 쫌", vanilla=[("mob/slime/small2", 1.7), ("mob/slime/small4", 1.8)], volume=0.7, attenuation=12)
    k.sound("shrivel", "알주머니가 쪼그라듦", vanilla=[("mob/slime/big2", 0.5)], synth=lambda i: audio.squish(seed=465 + i, dur=0.45), attenuation=24)


def phrases():
    return {"cause_g42": "쏟아진 새끼에 쪼였다"}
