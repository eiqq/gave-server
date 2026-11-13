"""G81 눈싸움 게이브: 작은 얼굴 수십이 다닥다닥 뭉친 지름 3.6 블록 공(세 축 판을 겹친 둥근 덩어리 + 모서리마다 튀어나온 얼굴 여덟).
앞면 한가운데 얼굴들이 짙고 옅게 늘어서 커다란 눈동자 하나 꼴 무늬를 이루고, 그 둘레 엔드 막대기 열둘이 속눈썹처럼 바깥으로 뻗었다.
3.6 블록이라 shrink 0.5 로 굽는다.

상태: ""(속눈썹 누움), "glare"(속눈썹이 쫙 섬), "glare2"(눈동자 속 작은 얼굴 절반이 입을 벌림), "glare3"(모두 입을 벌림),
"blink"(눈을 감고 굳음, 속눈썹 처짐).
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

R = 1.8       # 공 반지름
S = 1.15      # 판 반 폭
LASH_R = 1.05


def tile(face):
    """16px 작은 얼굴 하나"""
    a = faces.far(faces.preset(face) if isinstance(face, str) else face)
    return np.array(Image.fromarray(a, "RGBA").resize((16, 16), Image.NEAREST), dtype=np.uint8)


def mosaic(seed, pick):
    """64x64: 16px 얼굴 4x4. pick(i, j) 가 그 칸의 얼굴(처방)을 고름"""
    out = np.zeros((64, 64, 4), np.uint8)
    cache = {}
    for j in range(4):
        for i in range(4):
            f = pick(i, j)
            key = f if isinstance(f, str) else repr(f.key())
            if key not in cache:
                cache[key] = tile(f)
            out[j * 16:(j + 1) * 16, i * 16:(i + 1) * 16] = cache[key]
    out[..., 3] = 255
    return out


def eye_tint(img, closed=False):
    """눈동자 무늬: 가운데는 거의 검은 동공, 그 둘레 짙은 홍채, 바깥은 하얗게 바랜 흰자(모두 작은 얼굴 그대로 색만)"""
    a = img.astype(float)
    n = a.shape[0]
    yy, xx = np.mgrid[0:n, 0:n]
    r = np.hypot(xx + 0.5 - n / 2, yy + 0.5 - n / 2) / (n / 2)
    if closed:
        # 감은 눈: 살빛 그대로, 가운데를 가로지르는 굵은 눈꺼풀 줄
        lid = (np.abs(yy - n / 2 - 2 + (xx - n / 2) ** 2 / 90.0) < 2.2) & (r < 0.95)
        a[lid, :3] = (88, 30, 34)
        return np.clip(a, 0, 255).astype(np.uint8)
    pupil, iris = r < 0.28, (r >= 0.28) & (r < 0.62)
    a[pupil, :3] *= 0.16
    a[iris, :3] = a[iris, :3] * np.array((0.55, 0.32, 0.3))
    white = r >= 0.62
    a[white, :3] = a[white, :3] * 0.5 + 255 * 0.5
    return np.clip(a, 0, 255).astype(np.uint8)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.5, mut2=False, torn=False)
    rng = np.random.RandomState(81)
    kinds = ["smile", "smile_a", "smile_c", "half", "smile"]
    skin = k.texture("lump", mosaic(81, lambda i, j: kinds[(i * 3 + j * 5) % len(kinds)]))
    torn = faces.Face(mouth="torn")
    eyes = {}
    for name, frac in (("eye0", 0.0), ("eye2", 0.5), ("eye3", 1.0)):
        sel = [(i, j) for j in range(4) for i in range(4)]
        rng.shuffle(sel)
        open_set = set(sel[:int(round(16 * frac))])
        eyes[name] = k.texture(name, eye_tint(mosaic(82, lambda i, j: torn if (i, j) in open_set else "smile")))
    shut = k.texture("eye_shut", eye_tint(mosaic(83, lambda i, j: "smile"), closed=True))

    def ball(s, front, lash):
        for lo, hi in (((-R, -S, -S), (R, S, S)), ((-S, -R, -S), (S, R, S))):
            s.box(lo, hi, skin)
        s.box((-S, -S, -R), (S, S, R), {"south": front, "north": skin, "east": skin, "west": skin, "up": skin, "down": skin}, face=True)
        # 모서리마다 튀어나온 얼굴(바깥을 봄)
        for sx in (1, -1):
            for sy in (1, -1):
                for sz in (1, -1):
                    c = (sx * 1.12, sy * 1.12, sz * 1.12)
                    yaw = math.degrees(math.atan2(sx, sz))
                    pitch = -sy * 35.0
                    s.head(size=0.72, depth=0.5, at=c, face=["smile", "half", "smile_a", "smile_c"][(sx + 2 * sy + 3 * sz) % 4],
                           rot=(pitch, yaw, 0), near=False)
        for i in range(12):
            a = 2 * math.pi * i / 12
            cx, cy = math.cos(a), math.sin(a)
            root = (cx * LASH_R, cy * LASH_R, R)
            if lash == "down":
                d = (cx * 0.9, cy * 0.9 - 0.7, 0.35)
            elif lash == "up":
                d = (cx * 0.55, cy * 0.55, 1.0)
            else:
                d = (cx, cy, 0.3)
            s.rod(root, d, length=0.8, segs=2, thick=0.1, base=False, toward=(0, 0, 1) if lash != "up" else (cx, cy, 0))
        s.front = (0, 0, 1)

    ball(m.state(""), eyes["eye0"], "flat")
    ball(m.state("glare"), eyes["eye0"], "up")
    ball(m.state("glare2"), eyes["eye2"], "up")
    ball(m.state("glare3"), eyes["eye3"], "up")
    ball(m.state("blink"), shut, "down")


def sounds(k: kit.Kit):
    # 찍으면 낮게 깔리는 울림(이 종 신호)
    k.sample("glare", {"id": "kenney_scifi/spaceenginelarge_000", "max_len": 3.0}, "노려보는 소리", pitch=0.6, attenuation=32, n=1)


def phrases():
    return {"cause_g81": "눈싸움에 졌다", "cause_g81_crush": "눈알 공에 깔렸다"}
