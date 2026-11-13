"""G78 돈 냄새 사냥개 게이브: 검게 탈색한 _Gave 얼굴을 머리로 단 사냥개(몸통 1.0 블록, 엔드 막대기 다리 넷, 막대기 꼬리 하나)에
0.5 블록 작은 _Gave 얼굴이 기수처럼 올라탔다. 기수는 엔드 막대기 하나를 고삐처럼 쥐고 있다. 셋이 한 무리(Java).
낮고 긴 네발 짐승 위에 작은 얼굴이 탄 '탈것' 실루엣.

모델 gave:gabe/g78(개, 원점 = 몸통 가운데, 발끝이 0.78 아래 땅) 상태 ""(달리기: 다리 넷이 앞뒤 엇갈려 질주, 4자세, 꼬리 흔듦).
부위 coin(훔친 동전을 입에 묾). 검은 얼굴은 부류 비트를 지킨 채 칠을 바꾼 먼 얼굴 텍스처(가까운 얼굴 없음)에 실눈 뜸 오버레이를 그대로 얹는다.
모델 gave:gabe/g78_rider(기수 부품과 내려서 뛰는 기수) 상태 ""(탄 채 고삐), "run"(내려서 맨발로 뜀, 4자세).
모델 gave:gabe/g78_coin(쫓기는 사람 머리 위 동전 표시, 부품).
소리 bark(사냥개 짖음), bite(물기), snatch(돈 훔침), kick(기수 걷어차기), 글귀 cause_g78.
"""
import numpy as np

from gabe import faces, kit

DARK = {0: (44, 34, 38), 2: (78, 42, 50), 6: (22, 16, 18), 1: (236, 206, 206), 3: (236, 206, 206), 4: (250, 240, 240), 5: (255, 255, 255),
        7: (240, 236, 228)}


def dark_face(f):
    """검게 탈색한 먼 얼굴(32): 살, 홍조, 머리 덩어리는 검게, 눈 줄과 입은 옅은 분홍으로 뒤집어 웃음이 보이게(부류 비트 그대로)"""
    a = faces.far(f).copy()
    cls = a[..., 2] & 7
    for c, rgb in DARK.items():
        m = (cls == c) & (a[..., 3] > 0)
        a[m, 0], a[m, 1] = rgb[0], rgb[1]
        a[m, 2] = (rgb[2] & 0xF8) | c
    return a


def solid(rgb):
    out = np.zeros((16, 16, 4), np.uint8)
    yy, xx = np.mgrid[0:16, 0:16]
    out[..., :3] = np.clip(np.array(rgb, float) * (0.85 + 0.15 * np.sin(xx * 0.8 + yy * 0.4))[..., None], 0, 255).astype(np.uint8)
    out[..., 3] = 255
    return out


def coin_tex():
    yy, xx = np.mgrid[0:16, 0:16].astype(float)
    r = np.hypot(xx - 7.5, yy - 7.5)
    out = np.zeros((16, 16, 4), np.uint8)
    col = np.array([238, 186, 58], float) * (0.85 + 0.15 * np.cos((xx + yy) * 0.5))[..., None]
    col[(r > 5.6)] = (178, 122, 32)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    hole = (np.abs(xx - 7.5) < 1.6) & (np.abs(yy - 7.5) < 1.6)
    out[..., 3] = np.where((r <= 7.2) & ~hole, 255, 0)
    return out


def dark_head(k, s, size, at, depth):
    """검은 얼굴 머리 상자 + 실눈 뜸 오버레이(찍으면 눈이 뜨임)"""
    f = faces.preset("smile")
    front = k.texture("dark_face", dark_face(f), classes=True)
    side = k.texture("dark_skin", solid((40, 30, 34)))
    c = np.array(at, float)
    lo, hi = c - (size / 2, size / 2, depth / 2), c + (size / 2, size / 2, depth / 2)
    s.box(tuple(lo), tuple(hi), {"south": front}, faces=("south",), uv=[0, 0, 16, 16], face=True)
    s.box(tuple(lo), tuple(hi), side, faces=("north", "east", "west", "up", "down"), uv=[0, 0, 16, 16], face=True)
    ov = k.overlay_tex(f, "open")
    if ov is not None:
        (bx0, by0, bx1, by1), id32, _ = ov
        cx = lo[0] + (bx0 + bx1) / 2 / 32 * size
        cy = hi[1] - (by0 + by1) / 2 / 32 * size
        with s.when("open"):
            s.quad((cx, cy, hi[2] + 0.012), ((bx1 - bx0) / 32 * size, (by1 - by0) / 32 * size), "south", id32, face=True)
    s.front = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=4, period=8, shrink=0.75, mut2=dict(root=(0, 0.2, -0.3), dir=(0.3, 1.0, -0.6), length=0.5, segs=1, poses=2),
                torn=False)
    s = m.state("")
    hide = k.texture("dark_hide", solid((36, 28, 32)))
    s.box((-0.28, -0.2, -0.55), (0.28, 0.22, 0.45), hide, uv=[0, 0, 16, 16], face=True)
    s.box((-0.2, -0.05, 0.4), (0.2, 0.25, 0.58), hide, uv=[0, 0, 16, 16], face=True)  # 목
    dark_head(k, s, 0.62, (0, 0.14, 0.76), 0.5)
    with s.when("on:coin"):
        s.box((-0.14, -0.3, 1.0), (0.14, -0.02, 1.02), k.texture("coin", coin_tex()), faces=("south", "north"), uv=[0, 0, 16, 16])
    # 다리 넷: 앞 둘과 뒤 둘이 엇갈려 질주
    for (x, z, ph) in ((0.2, 0.3, 0), (-0.2, 0.3, 40), (0.2, -0.42, 180), (-0.2, -0.42, 220)):
        s.rod((x, -0.15, z), (0, -1, 0.05), length=0.64, segs=2, thick=0.1, toward=(0, 0, 1),
              wave=kit.Wave(amp=(28, 18), lag=45, bias=(0, -10 if z > 0 else 14), phase=ph), poses=4)
    # 꼬리
    s.rod((0, 0.15, -0.55), (0, 0.6, -1), length=0.48, segs=2, thick=0.08, toward=(1, 0, 0), wave=kit.Wave(amp=(20, 26), lag=40, cycles=2),
          poses=4)

    r = k.model("g78_rider", poses=4, period=8, mut2=False, torn=False)
    rs = r.state("")
    rs.head(size=0.5, depth=0.42, at=(0, 0.1, 0), face="smile", near=False)
    rs.rod((0.1, -0.12, 0.18), (0.05, -0.5, 1.0), length=0.62, segs=1, thick=0.06, toward=(0, 1, 0), base=False, rag=False)  # 고삐
    for side in (1, -1):
        rs.rod((side * 0.18, -0.16, 0), (side * 0.6, -1, 0.2), length=0.32, segs=1, thick=0.07, toward=(0, 0, 1), base=False, rag=False)
    rr = r.state("run")
    rr.head(size=0.5, depth=0.42, at=(0, 0.1, 0), face="half", near=False)
    rr.rod((0.1, -0.12, 0.18), (0.3, -0.2, 1.0), length=0.5, segs=1, thick=0.06, toward=(0, 1, 0), base=False, rag=False)
    for side in (1, -1):
        rr.rod((side * 0.12, -0.15, 0), (0, -1, 0), length=0.45, segs=2, thick=0.08, toward=(0, 0, 1),
               wave=kit.Wave(amp=(30, 20), lag=40, phase=0 if side > 0 else 180), poses=4)

    c = k.model("g78_coin", poses=1, period=20, mut2=False, torn=False, open=False, rag=False)
    cs = c.state("")
    cs.box((-0.22, -0.22, -0.02), (0.22, 0.22, 0.02), k.texture("coin", coin_tex()), faces=("south", "north"), uv=[0, 0, 16, 16])
    cs.front = (0, 0, 0)


def sounds(k: kit.Kit):
    k.sample("bark", "tag:bark", "사냥개 짖음", pitch=0.85, volume=1.0, attenuation=32)
    k.sample("bite", "tag:eat", "물기", pitch=0.9, attenuation=16)
    k.sample("snatch", "tag:coin", "돈 훔침", pitch=1.2, attenuation=16)
    k.sample("kick", "tag:punch", "걷어차기", pitch=1.15, attenuation=16)


def phrases():
    return {"cause_g78": "돈 냄새 사냥개에 물렸다"}
