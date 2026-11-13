"""게이브 얼굴 그림: 32x32 먼 얼굴, 128x128 가까운 얼굴(3블록 안), 실눈 뜸, 입 찢어짐 오버레이, 부품 텍스처(엔드 막대기, 살, 사슬, 거미줄, 뼈).

원본은 ref/_Gave_skin.png(64x64 스킨, 얼굴 앞 (8,8)-(16,16) + 모자 층 (40,8)-(48,16)). 8x8 합친 얼굴을 4배로 옮겨 그리고
눈, 입, 홍조를 손으로 그린 조각(아스키 그림)으로 바꿔 그린다(픽셀 느낌 유지, 신호는 2픽셀 넘게 굵게).

화소 부류(셰이더 팔레트가 읽음, 파랑 아래 3비트에 굳힘. 아이템 아틀라스는 밉맵이 없어 화소 값이 그대로 남는다):
 0 살(그 밖) 1 눈 줄, 홍채, 동공 2 홍조 3 입 4 흰자 5 반짝임 6 머리 덩어리(모자 층) 7 이
팔레트(Look.PAL_*): 1 흰 눈(1, 5 를 흰자로), 2 탈색, 4 짙은 홍조(2), 8 무표정(1, 3, 4, 5, 7 을 살로), 16 금빛 홍조(2).
"""
import hashlib
import math
from dataclasses import dataclass, field, replace
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
SKIN_PNG = ROOT / "ref/_Gave_skin.png"

CLS_SKIN, CLS_EYE, CLS_BLUSH, CLS_MOUTH, CLS_WHITE, CLS_SPARK, CLS_HAIR, CLS_TEETH = range(8)


def hexrgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255)


# 글자 → (색, 부류)
PAL = {
    "s": (0xffe2db, CLS_SKIN), "S": (0xffdad1, CLS_SKIN), "h": (0xffeae6, CLS_SKIN), "H": (0x5a1c1c, CLS_HAIR),
    "J": (0x521919, CLS_HAIR), "G": (0x692020, CLS_HAIR), "E": (0x591b1b, CLS_EYE), "e": (0x613939, CLS_EYE),
    "i": (0x7a2a2a, CLS_EYE), "p": (0x240a0a, CLS_EYE), "W": (0xf6f1f1, CLS_SPARK), "w": (0xf1ebe8, CLS_WHITE),
    "r": (0xc4504a, CLS_WHITE), "B": (0xffd3c7, CLS_BLUSH), "b": (0xffc6b8, CLS_BLUSH), "M": (0x692020, CLS_MOUTH),
    "m": (0x421414, CLS_MOUTH), "T": (0xf0e6d2, CLS_TEETH), "t": (0xd8c8ae, CLS_TEETH), "x": (0xcfe9ff, CLS_SKIN),
    "X": (0xa9d4f5, CLS_SKIN), "#": (0x3a1818, CLS_SKIN), "f": (0xc8505a, CLS_SKIN), "F": (0x96303c, CLS_SKIN),
    "g": (0xf2c96a, CLS_BLUSH), "o": (0xe2b04a, CLS_BLUSH), "d": (0xd04452, CLS_BLUSH), "D": (0xa82838, CLS_BLUSH),
}

TUFT = [  # 모자 층 위 모서리 적갈색 덩어리(왼쪽, 오른쪽은 거울)
    "HHHHHHHH",
    "HHHJHHHH",
    "HHJHHHHG",
    "GHHHHHGG",
    "GGHHHHGG",
    ".GGHHGG.",
    "..GGGG..",
]
BLUSH = [
    ".BBBBBB.",
    "BBbbbbBB",
    "BBbbbbBB",
    ".BBBBBB.",
]
EYES = {  # 왼쪽 눈(8칸 폭, 오른쪽 끝이 얼굴 가운데 쪽). 오른쪽 눈은 거울
    "squint": [
        "........",
        "........",
        "..EEEE..",
        ".EEEEEEW",
        "EEe..eEE",
        "e......e",
        "........",
        "........",
    ],
    "half": [
        "........",
        "........",
        ".EEEEEE.",
        "EEEEEEEE",
        "wwiippWw",
        ".wiippw.",
        "..wiiw..",
        "........",
    ],
    "open": [
        "..EEEE..",
        ".EwwwwE.",
        "Ewwiiiww",
        "wwiWppiw",
        "wwippiiw",
        "wwiiiiww",
        ".wwiiww.",
        "..wwww..",
    ],
    "bloodshot": [
        "..EEEEEE..",
        ".EwwwwwwE.",
        "Ewrrwwwwrw",
        "wwwrwwwrww",
        "wwwwwpwwww",
        "wrwwwwwwww",
        "wwrrwwwrrw",
        ".wwwwwwww.",
        "..wwwwww..",
    ],
    "none": [],
}
MOUTH_DOT = [
    ".MM.",
    "MmmM",
    "MmmM",
    ".MM.",
]
# 눈 칸(32 기준): 왼쪽 x 4..11, 오른쪽 20..27, 위 y 11. 입 x 16..19, y 24..27(원본 점 입 자리). 홍조 x 12..19, y 20..23
EYE_X, EYE_Y = (4, 20), 11
MOUTH_XY = (16, 24)
BLUSH_XY = (12, 20)


@dataclass(frozen=True)
class Face:
    """얼굴 그림 처방. 이름 있는 처방은 PRESETS, 없으면 내용 해시가 파일 이름.

    eyes: squint(실눈, 기본) | half | open | bloodshot | none
    mouth: dot(점 입) | torn(귀까지 찢어짐) | teeth_ring(이빨 고리) | honeycomb(벌집 구멍) | none
    blush: pink | gold | deep | none,  tufts: 위 모서리 덩어리,  extras: tears, stitch, veins
    cut: None | "left" | "right"(그쪽 반을 잘라 투명, 자른 면은 날살), flip: 위아래 뒤집기, tilt: 눈 기울기(도, + 는 안쪽 끝이 올라감)
    """
    eyes: str = "squint"
    mouth: str = "dot"
    blush: str = "pink"
    tufts: bool = True
    extras: tuple = ()
    cut: str | None = None
    flip: bool = False
    tilt: float = 0.0
    name: str | None = field(default=None, compare=False)

    def key(self):
        if self.name:
            return self.name
        for n, f in PRESETS.items():
            if f == self:
                return n
        h = hashlib.sha1(repr((self.eyes, self.mouth, self.blush, self.tufts, tuple(self.extras), self.cut, self.flip,
                                round(self.tilt, 2))).encode()).hexdigest()[:8]
        return "f_" + h

    def with_(self, **kw):
        return replace(self, name=None, **kw)


PRESETS = {
    "smile": Face(),
    "half": Face(eyes="half"),
    "open": Face(eyes="open"),
    "bloodshot": Face(eyes="bloodshot"),
    "torn": Face(mouth="torn"),
    "tears": Face(extras=("tears",)),
    "blank": Face(eyes="none", mouth="none"),
    "gold": Face(blush="gold"),
    "deep": Face(blush="deep"),
    "stitch": Face(extras=("stitch",)),
    "half_v": Face(cut="right"),
    "flipped": Face(flip=True),
    "teeth_ring": Face(mouth="teeth_ring"),
    "honeycomb": Face(mouth="honeycomb"),
    "smile_a": Face(tilt=-14.0),
    "smile_c": Face(tilt=14.0),
}


def preset(f):
    """문자열(PRESETS 이름) 또는 Face → Face"""
    if isinstance(f, Face):
        return f
    if f not in PRESETS:
        raise KeyError(f"얼굴 처방 {f!r} 없음(있는 것: {', '.join(PRESETS)})")
    return PRESETS[f]


def _h(*a):
    """정해진 0..1 난수(화소 자리, 씨앗)"""
    v = hashlib.blake2b(repr(a).encode(), digest_size=4).digest()
    return int.from_bytes(v, "little") / 2 ** 32


class Canvas:
    """RGBA + 부류 판. put/stamp 는 화소 단위"""

    def __init__(self, n=32):
        self.n = n
        self.rgb = np.zeros((n, n, 3), np.float32)
        self.a = np.zeros((n, n), np.uint8)
        self.cls = np.zeros((n, n), np.uint8)

    def put(self, x, y, ch_or_rgb, cls=None):
        if not (0 <= x < self.n and 0 <= y < self.n):
            return
        if isinstance(ch_or_rgb, str):
            col, cls = PAL[ch_or_rgb]
            col = hexrgb(col)
        else:
            col = ch_or_rgb
        self.rgb[y, x] = col
        self.a[y, x] = 255
        self.cls[y, x] = cls or 0

    def stamp(self, rows, x0, y0, mirror=False, swap=None):
        for dy, row in enumerate(rows):
            w = len(row)
            for dx, ch in enumerate(row):
                if ch == ".":
                    continue
                if swap and ch in swap:
                    ch = swap[ch]
                x = x0 + (w - 1 - dx if mirror else dx)
                self.put(x, y0 + dy, ch)

    def rgba(self):
        """부류를 파랑 아래 3비트에 굳힌 uint8 RGBA"""
        out = np.zeros((self.n, self.n, 4), np.uint8)
        rgb = np.clip(np.round(self.rgb), 0, 255).astype(np.uint8)
        out[..., :3] = rgb
        out[..., 2] = (rgb[..., 2] & 0xF8) | (self.cls & 7)
        out[..., 3] = self.a
        return out


def _skin(c: Canvas):
    """원본 8x8 합친 얼굴의 살 바탕을 32 로(가장자리 그늘, 이마 밝은 곳, 2x2 손맛 흔들림)"""
    sk, sh, hi = hexrgb(0xffe2db), hexrgb(0xffdad1), hexrgb(0xffeae6)
    for y in range(32):
        for x in range(32):
            col = sk
            if x < 4 or x >= 28 or y >= 29:
                col = sh
            # 이마: 원본 1행 가운데 둘, 2행 가운데 넷
            if (4 <= y < 8 and 12 <= x < 20) or (8 <= y < 12 and 8 <= x < 24):
                col = hi
            j = (_h("sk", x // 2, y // 2) - 0.5) * 5
            c.put(x, y, (col[0], col[1] + j, col[2] + j), CLS_SKIN)
    # 가장자리와 이마 경계를 한 화소 섞음(손으로 그린 듯)
    for y in range(32):
        for x in (3, 28):
            if _h("edge", x, y) < 0.5:
                c.put(x, y, tuple((a + b) / 2 for a, b in zip(sk, sh)), CLS_SKIN)


def _eye(c: Canvas, kind, tilt):
    """눈 둘(오른쪽은 거울). 기울기는 세로 밀기(열마다 위아래로 옮김, 굵은 줄이 끊기지 않게)"""
    rows = EYES[kind]
    if not rows:
        return
    w = len(rows[0])
    slope = math.tan(math.radians(tilt))
    for side, ex in enumerate(EYE_X):
        x0 = ex - (w - 8) // 2
        for dy, row in enumerate(rows):
            for dx, ch in enumerate(row):
                if ch == ".":
                    continue
                sx = w - 1 - dx if side else dx
                # 안쪽(얼굴 가운데) 끝이 tilt 만큼 올라감: 왼쪽 눈은 x 가 클수록, 오른쪽 눈은 작을수록 안쪽
                inward = (sx - (w - 1) / 2) * (1 if side == 0 else -1)
                c.put(x0 + sx, EYE_Y + dy - int(round(slope * inward)), ch)


def _mouth(c: Canvas, kind):
    mx, my = MOUTH_XY
    if kind == "dot":
        c.stamp(MOUTH_DOT, mx, my)
    elif kind == "torn":
        # 귀까지 찢어진 입: 가운데가 가장 벌어지고 양 끝은 금처럼 가늘게 위로
        for x in range(1, 31):
            u = (x - 17.5) / 15.5
            half = max(0.0, 2.6 * (1 - u * u)) + (_h("torn", x) - 0.5) * 0.9
            yc = 25.5 - 2.4 * u * u
            top, bot = int(math.floor(yc - half)), int(math.ceil(yc + half))
            if half < 0.6:
                c.put(x, int(round(yc)), "M")
                continue
            for y in range(top, bot + 1):
                edge = y in (top, bot)
                c.put(x, y, "M" if edge else "m")
            if x % 2 == 0 and half > 1.4:
                c.put(x, top + 1, "T" if x % 4 == 0 else "t")
                if half > 2.0:
                    c.put(x, bot - 1, "t")
    elif kind == "teeth_ring":
        cx, cy = 17.5, 25.0
        for y in range(19, 32):
            for x in range(10, 26):
                r = math.hypot(x - cx, (y - cy) * 1.1)
                ang = math.atan2(y - cy, x - cx)
                if r < 2.6:
                    c.put(x, y, "m")
                elif r < 4.4:
                    tooth = (math.cos(ang * 9) > -0.2)
                    c.put(x, y, ("T" if r < 3.6 else "t") if tooth else "m")
                elif r < 5.2:
                    c.put(x, y, "M")
    elif kind == "honeycomb":
        # 벌집 구멍 입: 크기가 조금씩 다른 둥근 구멍 일곱(피 없이)
        holes = [(17.5, 25.5, 1.3), (13.3, 23.2, 1.0), (21.7, 23.2, 1.1), (13.3, 27.8, 0.9), (21.7, 27.8, 1.0), (17.5, 20.9, 0.8),
                 (17.5, 30.1, 0.8)]
        for hx, hy, hr in holes:
            for y in range(int(hy - hr - 2), int(hy + hr + 3)):
                for x in range(int(hx - hr - 2), int(hx + hr + 3)):
                    d = math.hypot(x + 0.5 - hx, y + 0.5 - hy)
                    if d < hr:
                        c.put(x, y, "m")
                    elif d < hr + 0.75:
                        c.put(x, y, "M")
    elif kind != "none":
        raise KeyError(f"입 {kind!r} 없음")


def _extras(c: Canvas, extras):
    for ex in extras:
        if ex == "tears":
            for ex0 in (5, 10, 21, 26):
                x = ex0
                for y in range(18, 31):
                    if _h("tear", ex0, y) < 0.18:
                        x += 1 if _h("tw", ex0, y) < 0.5 else -1
                    c.put(x, y, "x")
                c.put(x, 31, "X")
                c.put(x + 1, 30, "X")
        elif ex == "stitch":
            for y in range(32):
                c.put(16, y, "#")
                if y % 4 == 2:
                    for x in (14, 15, 17, 18):
                        c.put(x, y, "#")
        elif ex == "veins":
            for k in range(6):
                x, y = int(_h("vx", k) * 32), int(_h("vy", k) * 32)
                for s in range(7):
                    c.put(x, y, (190, 90, 120), CLS_SKIN)
                    x += 1 if _h("vs", k, s) < 0.5 else -1
                    y += 1
        else:
            raise KeyError(f"덧그림 {ex!r} 없음(tears, stitch, veins)")


def draw(f) -> Canvas:
    f = preset(f)
    c = Canvas(32)
    _skin(c)
    if f.tufts:
        c.stamp(TUFT, 4, 0)
        c.stamp(TUFT, 20, 0, mirror=True)
    if f.blush != "none":
        swap = {"pink": None, "gold": {"B": "g", "b": "o"}, "deep": {"B": "d", "b": "D"}}[f.blush]
        c.stamp(BLUSH, *BLUSH_XY, swap=swap)
    _eye(c, f.eyes, f.tilt)
    _mouth(c, f.mouth)
    _extras(c, f.extras)
    if f.cut:
        xs = range(17, 32) if f.cut == "right" else range(0, 15)
        for y in range(32):
            for x in xs:
                c.a[y, x] = 0
                c.cls[y, x] = 0
            e = 16 if f.cut == "right" else 15
            c.put(e, y, "f" if _h("cut", y) < 0.6 else "F")
            if _h("cut2", y) < 0.4:
                c.put(e + (-1 if f.cut == "right" else 1), y, "f")
    if f.flip:
        c.rgb = c.rgb[::-1, ::-1].copy()
        c.a = c.a[::-1, ::-1].copy()
        c.cls = c.cls[::-1, ::-1].copy()
    return c


def far(f) -> np.ndarray:
    """32x32 RGBA(부류 비트 들어감)"""
    return draw(f).rgba()


# ---------------------------------------------------------------------------------------------- 가까운 얼굴(128)


def _blur(a, r):
    if r <= 0:
        return a
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8) if a.dtype != np.uint8 else a)
    return np.asarray(im.filter(ImageFilter.GaussianBlur(r)), np.float32)


def _noise(n, scale, seed):
    """값 잡음(0..1), scale 화소마다 격자"""
    g = max(2, int(n / scale) + 2)
    rng = np.random.RandomState(seed)
    grid = rng.rand(g, g).astype(np.float32)
    im = Image.fromarray((grid * 255).astype(np.uint8)).resize((int(g * scale), int(g * scale)), Image.BICUBIC)
    return np.asarray(im, np.float32)[:n, :n] / 255.0


def near(f, size=128) -> np.ndarray:
    """3블록 안에서 쓰는 사람 같은 얼굴(같은 이목구비): 부드럽게 키우고 살결 잡음, 솜털, 땀구멍, 젖은 눈꺼풀 반사, 점 입 안의 이.
    무섭게, 그러나 피 철철은 아님. 결과 RGBA(부류 비트 들어감). 같은 얼굴의 잡음은 자리마다 같아(씨앗 고정) 오버레이를 잘라 붙여도 이음매가 없다.
    뜬 눈(open, half, bloodshot)은 128 에서 직접 그린다(흰자 그늘, 홍채 결, 젖은 반사 둘, 속눈썹)"""
    f = preset(f)
    if f.eyes in ("open", "half", "bloodshot"):
        out = _near_core(f.with_(eyes="none", flip=False), size)
        _near_eyes(out, f, size)
    else:
        out = _near_core(f.with_(flip=False), size)
    if f.flip:
        out = out[::-1, ::-1].copy()
    return out


def _near_eyes(out, f, size):
    """뜬 눈을 128 판에 직접 그림(out 을 고침, 부류 비트 포함)"""
    k = size / 32
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    rgb = out[..., :3].astype(np.float32)
    cls = (out[..., 2] & 7).astype(np.uint8)
    alive = out[..., 3] > 0
    rng = np.random.RandomState(41)
    a = math.radians(f.tilt)
    for side, ex in ((0, EYE_X[0]), (1, EYE_X[1])):
        cx, cy = (ex + 4) * k, (EYE_Y + 4.2) * k
        dx, dy = xx - cx, yy - cy
        if side:
            dx = -dx
        u = dx * math.cos(a) + dy * math.sin(a)
        v = -dx * math.sin(a) + dy * math.cos(a)
        W = 3.9 * k
        H = {"open": 2.9, "half": 2.6, "bloodshot": 3.2}[f.eyes] * k
        t = np.clip(1 - (u / W) ** 2, 0, 1)
        top = -H * t ** 0.7
        if f.eyes == "half":
            top = np.maximum(top, -0.15 * k)
        bot = H * 0.9 * t ** 0.9
        inside = (v > top) & (v < bot) & (np.abs(u) < W) & alive
        # 흰자: 위 눈꺼풀 그늘, 가운데 쪽 분홍 눈구석
        depth = np.clip((v - top) / (0.9 * k), 0, 1)
        sclera = np.stack([238 - 30 * (1 - depth), 231 - 40 * (1 - depth), 226 - 40 * (1 - depth)], -1)
        inner = np.clip((u / W - 0.65) / 0.35, 0, 1)
        sclera = sclera * (1 - 0.5 * inner[..., None]) + np.array([214, 140, 140], np.float32) * (0.5 * inner[..., None])
        ir = {"open": 2.1, "half": 1.9, "bloodshot": 1.3}[f.eyes] * k
        pr = {"open": 0.95, "half": 0.85, "bloodshot": 0.22}[f.eyes] * k
        iu, iv = 0.3 * k, (0.35 if f.eyes == "half" else 0.1) * k
        r = np.hypot(u - iu, v - iv)
        ang = np.arctan2(v - iv, u - iu)
        stri = 0.82 + 0.18 * np.sin(ang * 17 + np.sin(ang * 5) * 2)
        iris = np.array([104, 38, 36], np.float32) * stri[..., None]
        iris = np.where((r > ir - 0.35 * k)[..., None], iris * 0.6, iris)
        col = np.where((r < ir)[..., None], iris, sclera)
        col = np.where((r < pr)[..., None], np.array([18, 8, 8], np.float32), col)
        c2 = np.where(r < ir, CLS_EYE, CLS_WHITE)
        # 젖은 반사 둘
        for (hu, hv, hr) in ((-0.55, -0.55, 0.55), (0.55, 0.45, 0.28)):
            hm = np.hypot(u - iu - hu * k, v - iv - hv * k) < hr * k
            col = np.where(hm[..., None], np.array([255, 250, 250], np.float32), col)
            c2 = np.where(hm, CLS_SPARK, c2)
        if f.eyes == "bloodshot":
            veins = Image.new("L", (size, size), 0)
            vd = ImageDraw.Draw(veins)
            for n in range(9):
                th = rng.rand() * 2 * math.pi
                px, py = math.cos(th) * W * 0.95, math.sin(th) * H * 0.8
                for s in range(7):
                    nx = px * 0.82 + rng.randn() * 1.2
                    ny = py * 0.82 + rng.randn() * 1.2
                    sx = -1 if side else 1
                    vd.line([(cx + sx * px, cy + py), (cx + sx * nx, cy + ny)], fill=200, width=1)
                    px, py = nx, ny
            vm = np.asarray(veins, np.float32)[..., None] / 255.0 * (r >= ir)[..., None]
            col = col * (1 - vm * 0.8) + np.array([190, 40, 44], np.float32) * (vm * 0.8)
        rgb = np.where(inside[..., None], col, rgb)
        cls = np.where(inside, c2, cls).astype(np.uint8)
        # 위 눈꺼풀 줄(굵고 어둡게) + 속눈썹, 아래 젖은 테
        lid = (v <= top) & (v > top - 0.6 * k) & (np.abs(u) < W * 1.05) & alive
        rgb = np.where(lid[..., None], np.array([74, 24, 26], np.float32), rgb)
        cls = np.where(lid, CLS_EYE, cls).astype(np.uint8)
        rim = (v >= bot) & (v < bot + 0.3 * k) & (np.abs(u) < W * 0.95) & alive
        rgb = np.where(rim[..., None], rgb * 0.55 + np.array([236, 176, 176], np.float32) * 0.45, rgb)
        lash = Image.new("L", (size, size), 0)
        ld = ImageDraw.Draw(lash)

        def w2s(p):
            pu, pv = p
            wx = pu * math.cos(a) - pv * math.sin(a)
            wy = pu * math.sin(a) + pv * math.cos(a)
            return (cx + (-wx if side else wx), cy + wy)

        for n in range(14):
            uu = -W + (n + 0.5) * 2 * W / 14
            tt = max(0.0, 1 - (uu / W) ** 2)
            tv = -H * tt ** 0.7 if f.eyes != "half" else max(-H * tt ** 0.7, -0.15 * k)
            ln = (0.7 + 0.5 * tt) * k
            out_dir = -0.35 if uu < 0 else 0.35
            ld.line([w2s((uu, tv - 0.3 * k)), w2s((uu + out_dir * ln, tv - 0.3 * k - ln))], fill=255, width=1)
        lm = (np.asarray(lash, np.float32) / 255.0) * alive
        rgb = rgb * (1 - lm[..., None] * 0.8) + np.array([50, 16, 18], np.float32) * (lm[..., None] * 0.8)
        cls = np.where((lm > 0.5) & (cls == CLS_SKIN), CLS_EYE, cls).astype(np.uint8)
    q = np.clip(np.round(rgb), 0, 255).astype(np.uint8)
    out[..., :3] = q
    out[..., 2] = (q[..., 2] & 0xF8) | (cls & 7)


def _near_core(f, size=128) -> np.ndarray:
    c = draw(f)
    k = size // 32
    src = c.rgb.astype(np.float32)
    big = np.stack([np.asarray(Image.fromarray(np.clip(src[..., i], 0, 255).astype(np.uint8)).resize((size, size), Image.LANCZOS),
                               np.float32) for i in range(3)], -1)
    cls = np.kron(c.cls, np.ones((k, k), np.uint8))
    alpha = np.kron(c.a, np.ones((k, k), np.uint8))
    # 부류 경계를 살짝 부드럽게(사람 살처럼), 그러나 눈 줄, 입은 또렷이. 살은 크게 번지게(가장자리 띠가 틀처럼 보이지 않게)
    skin0 = np.isin(cls, (CLS_SKIN,))
    fill = np.where(skin0[..., None], big, np.array([255, 226, 219], np.float32))
    soft = _blur(big, 1.2)
    soft = np.where(skin0[..., None], _blur(fill, 3.5), soft)
    feat = np.isin(cls, (CLS_EYE, CLS_MOUTH, CLS_SPARK, CLS_TEETH, CLS_WHITE))
    featd = _blur(feat.astype(np.float32) * 255, 1.5) / 255.0
    big = soft * (1 - featd[..., None]) + big * featd[..., None]
    skin = np.isin(cls, (CLS_SKIN, CLS_BLUSH))
    # 홍조는 더 넓게 번짐
    bl = (cls == CLS_BLUSH).astype(np.float32) * 255
    blr = _blur(bl, 5.0) / 255.0
    big = big * (1 - 0.55 * blr[..., None]) + np.array([255, 196, 184], np.float32) * (0.55 * blr[..., None])
    # 살결: 큰 얼룩(붉은기), 잔 알갱이
    mott = _noise(size, 18, 11) - 0.5
    grain = (np.random.RandomState(12).rand(size, size).astype(np.float32) - 0.5)
    sk = skin[..., None].astype(np.float32)
    big += sk * (mott[..., None] * np.array([2, -9, -8], np.float32) + grain[..., None] * 5.0)
    # 땀구멍
    rng = np.random.RandomState(13)
    for _ in range(size * size // 70):
        x, y = rng.randint(1, size - 1), rng.randint(1, size - 1)
        if skin[y, x]:
            big[y, x] *= 0.94
            big[y, x + 1] *= 0.975
    # 솜털: 짧고 옅은 밝은 줄(아래, 바깥으로)
    fuzz = Image.new("L", (size, size), 0)
    dr = ImageDraw.Draw(fuzz)
    for _ in range(size * 3):
        x, y = rng.rand() * size, rng.rand() * size
        ang = math.radians(90 + (x - size / 2) / size * 70 + rng.randn() * 15)
        ln = 2.5 + rng.rand() * 3
        dr.line([(x, y), (x + math.cos(ang) * ln, y + math.sin(ang) * ln)], fill=int(40 + rng.rand() * 50), width=1)
    fz = np.asarray(fuzz, np.float32)[..., None] / 255.0 * sk
    big = big * (1 - fz * 0.35) + 255 * fz * 0.35
    # 머리 덩어리: 가닥
    hair = cls == CLS_HAIR
    strands = _noise(size, 3, 14)
    big[hair] *= (0.8 + 0.4 * strands[hair])[..., None]
    # 눈: 쌍꺼풀 주름(눈 줄 위 4화소 위로 옅은 그늘), 속눈썹, 젖은 반사
    eye = (cls == CLS_EYE)
    shift_up = np.zeros_like(eye)
    shift_up[:-int(1.2 * k)] = eye[int(1.2 * k):]
    crease = _blur(shift_up.astype(np.float32) * 255, 2.0) / 255.0
    crease = np.clip(crease - _blur(eye.astype(np.float32) * 255, 1.0) / 255.0, 0, 1)
    big *= (1 - 0.16 * crease * sk[..., 0])[..., None]
    lash = Image.new("L", (size, size), 0)
    ld = ImageDraw.Draw(lash)
    ys, xs = np.nonzero(eye)
    for y, x in zip(ys, xs):
        if y + 1 < size and not eye[y + 1, x] and cls[y + 1, x] != CLS_WHITE and rng.rand() < 0.35:
            out = -1 if x < size / 2 else 1
            ox = (x - (size * 0.25 if x < size / 2 else size * 0.75)) / (size * 0.15)
            ld.line([(x, y), (x + out * (0.6 + abs(ox)) * 2.4, y + 2.5 + rng.rand() * 2.5)], fill=255, width=1)
    lm = np.asarray(lash, np.float32) / 255.0
    big = big * (1 - lm[..., None] * 0.75) + np.array([60, 20, 22], np.float32) * (lm[..., None] * 0.75)
    cls = np.where((lm > 0.5) & (cls == CLS_SKIN), CLS_EYE, cls).astype(np.uint8)
    # 젖은 반사: 눈 줄 위쪽 경계 한 화소 아래에 밝은 줄
    top_edge = eye & ~np.vstack([np.zeros((1, size), bool), eye[:-1]])
    wet = np.zeros_like(eye)
    wet[1:] = top_edge[:-1]
    wet &= eye
    big[wet] = big[wet] * 0.5 + np.array([255, 228, 228], np.float32) * 0.5
    cls = np.where(wet, CLS_SPARK, cls).astype(np.uint8)
    # 흰자: 실핏줄 살짝, 둥근 그늘
    white = cls == CLS_WHITE
    big[white] *= (0.93 + 0.07 * _noise(size, 4, 16)[white])[..., None]
    # 입: 점 입 안에 사람 이(위 이 한 줄, 잇몸)
    mouth = cls == CLS_MOUTH
    if mouth.any() and f.mouth in ("dot", "torn"):
        ys, xs = np.nonzero(mouth)
        # 입 덩어리마다(연결 성분 대신 행 단위 단순 처리): 입 윗부분 1/3 에 이
        lab = _components(mouth)
        for lid in range(1, lab.max() + 1):
            m = lab == lid
            yy, xx = np.nonzero(m)
            if len(yy) < 6:
                continue
            y0, y1, x0, x1 = yy.min(), yy.max(), xx.min(), xx.max()
            h = y1 - y0 + 1
            inner = m.copy()
            inner &= _erode(m)
            ty0, ty1 = y0 + max(1, h // 5), y0 + max(2, h * 2 // 5)
            tw = max(2, k)
            for x in range(x0 + 1, x1):
                col = (x - x0) % (tw + 1)
                for y in range(ty0, ty1 + 1):
                    if inner[y, x] and col != tw:
                        shade = 0.85 + 0.15 * (1 - (y - ty0) / max(1, ty1 - ty0))
                        big[y, x] = np.array([240, 230, 210], np.float32) * shade
                        cls[y, x] = CLS_TEETH
                if inner[ty0 - 1, x] if ty0 - 1 >= 0 else False:
                    big[ty0 - 1, x] = np.array([176, 70, 80], np.float32)
            # 입 안 깊이: 아래로 갈수록 어둡게
            for y in range(ty1 + 1, y1 + 1):
                sel = inner[y] & (cls[y] == CLS_MOUTH)
                big[y, sel] *= 0.8
    # 가장자리 그늘(입체감)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    rr = np.maximum(np.abs(xx - size / 2 + 0.5), np.abs(yy - size / 2 + 0.5)) / (size / 2)
    big *= (1 - 0.04 * np.clip((rr - 0.8) / 0.2, 0, 1))[..., None]
    out = np.zeros((size, size, 4), np.uint8)
    rgb = np.clip(np.round(big), 0, 255).astype(np.uint8)
    out[..., :3] = rgb
    out[..., 2] = (rgb[..., 2] & 0xF8) | (cls & 7)
    out[..., 3] = np.where(alpha > 127, 255, 0)
    return out


def _dilate(m, n):
    """둘레로 n 칸 넓힘(가장자리에서 감싸 돌지 않음)"""
    m = m.copy()
    for _ in range(n):
        g = m.copy()
        g[1:] |= m[:-1]
        g[:-1] |= m[1:]
        g[:, 1:] |= m[:, :-1]
        g[:, :-1] |= m[:, 1:]
        m = g
    return m


def _erode(m):
    e = m.copy()
    e[1:] &= m[:-1]
    e[:-1] &= m[1:]
    e[:, 1:] &= m[:, :-1]
    e[:, :-1] &= m[:, 1:]
    return e


def _components(m):
    lab = np.zeros(m.shape, np.int32)
    n = 0
    for y, x in zip(*np.nonzero(m)):
        if lab[y, x]:
            continue
        n += 1
        stack = [(y, x)]
        lab[y, x] = n
        while stack:
            cy, cx = stack.pop()
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                ny, nx = cy + dy, cx + dx
                if 0 <= ny < m.shape[0] and 0 <= nx < m.shape[1] and m[ny, nx] and not lab[ny, nx]:
                    lab[ny, nx] = n
                    stack.append((ny, nx))
    return lab


# ---------------------------------------------------------------------------------------------- 오버레이


def overlay(f, kind):
    """기본 얼굴 f 위에 겹칠 오버레이: kind = "open"(실눈 뜸) | "torn"(입 찢어짐, 변이 1).
    → (bbox32 (x0, y0, x1, y1) 끝 미포함, 32 그림 RGBA, 128 그림 RGBA) 또는 바뀐 곳이 없으면 None.
    오버레이 처방은 f 의 눈(open) 또는 입(torn)만 바꾼 것이고, 바뀐 화소에서 3칸 넓힌 곳만 남긴다(가까운 얼굴의 이음매 없게)"""
    f = preset(f)
    g = f.with_(eyes="open") if kind == "open" else f.with_(mouth="torn")
    if g == f.with_():
        return None
    a, b = far(f), far(g)
    diff = np.any(a != b, axis=-1)
    if not diff.any():
        return None
    m = _dilate(diff, 3)
    ys, xs = np.nonzero(m)
    x0, y0, x1, y1 = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
    small = b.copy()
    small[..., 3] = np.where(m & (b[..., 3] > 0), 255, 0)
    nb = near(g)
    k = nb.shape[0] // 32
    mk = np.kron(m.astype(np.uint8), np.ones((k, k), np.uint8)).astype(bool)
    nb = nb.copy()
    nb[..., 3] = np.where(mk & (nb[..., 3] > 0), 255, 0)
    return (int(x0), int(y0), int(x1), int(y1)), _fill_clear(small[y0:y1, x0:x1]), _fill_clear(nb[y0 * k:y1 * k, x0 * k:x1 * k])


def _fill_clear(img):
    """투명 화소 색을 이웃 불투명 색으로 메움(컷아웃 가장자리에 검은 테가 끼지 않게)"""
    img = img.copy()
    a = img[..., 3] > 0
    if a.all() or not a.any():
        return img
    rgb = img[..., :3].astype(np.float32)
    filled = a.copy()
    for _ in range(8):
        acc = np.zeros_like(rgb)
        cnt = np.zeros(a.shape, np.float32)
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            sh = np.roll(np.roll(filled, dy, 0), dx, 1)
            acc += np.roll(np.roll(rgb, dy, 0), dx, 1) * sh[..., None]
            cnt += sh
        new = (~filled) & (cnt > 0)
        rgb[new] = acc[new] / cnt[new][..., None]
        filled |= new
        if filled.all():
            break
    img[..., :3] = np.clip(np.round(rgb), 0, 255).astype(np.uint8)
    # 투명 화소는 부류 0(아무것도 아님)
    img[..., 2] = np.where(a, img[..., 2], img[..., 2] & 0xF8)
    return img


# ---------------------------------------------------------------------------------------------- 머리 옆, 뒤, 위(스킨에서)


def skin_side(which, size=32):
    """스킨 머리 옆면(모자 층 합침)을 32 로: right(캐릭터 오른쪽 = 모델 -X 면), left(+X 면), back(-Z), top(+Y), bottom(-Y)"""
    sk = np.asarray(Image.open(SKIN_PNG).convert("RGBA"))
    box = {"right": (0, 8), "front": (8, 8), "left": (16, 8), "back": (24, 8), "top": (8, 0), "bottom": (16, 0)}[which]
    x, y = box
    base = sk[y:y + 8, x:x + 8].copy()
    hat = sk[y:y + 8, x + 32:x + 40]
    m = hat[..., 3] > 0
    base[m] = hat[m]
    base[..., 3] = 255
    k = size // 8
    big = np.kron(base, np.ones((k, k, 1), np.uint8)).astype(np.float32)
    for yy in range(size):
        for xx in range(size):
            j = (_h("side", which, xx // 2, yy // 2) - 0.5) * 6
            big[yy, xx, 1:3] += j
    out = np.clip(np.round(big), 0, 255).astype(np.uint8)
    # 부류: 어두운 덩어리(모자 층 적갈색)는 머리 덩어리 6, 나머지 살 0
    dark = out[..., 0] < 160
    out[..., 2] = (out[..., 2] & 0xF8) | np.where(dark, CLS_HAIR, CLS_SKIN).astype(np.uint8)
    return out


# ---------------------------------------------------------------------------------------------- 부품 텍스처(16x16)


def rod():
    """엔드 막대기를 새로 그린 것(바닐라 그림을 옮기지 않음, 생김새만 같게): 막대 u 0..2 v 0..15(옆), 끝 u 2..4 v 0..2,
    받침 윗면 u 2..6 v 2..6, 받침 옆 u 2..6 v 6..7"""
    im = np.zeros((16, 16, 4), np.uint8)
    for v in range(16):
        im[v, 0] = (252, 250, 255, 255)
        im[v, 1] = (226, 218, 236, 255)
        if v % 5 == 3:
            im[v, 1] = (214, 204, 228, 255)
    im[0:2, 2:4] = (255, 253, 255, 255)
    im[0, 3] = (236, 230, 244, 255)
    for v in range(2, 6):
        for u in range(2, 6):
            edge = u in (2, 5) or v in (2, 5)
            im[v, u] = (198, 190, 206, 255) if edge else (232, 226, 238, 255)
    for u in range(2, 6):
        im[6, u] = (176, 168, 186, 255)
    return _fill_rest(im)


def flesh():
    """분홍 살 띠: 붉은 살, 밝은 주름 능선, 어두운 골, 푸른 핏줄 조금, 젖은 반짝임"""
    n = 16
    rng = np.random.RandomState(31)
    base = np.array([201, 106, 114], np.float32)
    im = np.zeros((n, n, 4), np.float32)
    for y in range(n):
        for x in range(n):
            ridge = math.sin((x * 0.9 + y * 0.35) + math.sin(y * 0.8) * 1.2)
            col = base * (0.86 + 0.14 * ridge) + rng.randn(3) * 4
            im[y, x, :3] = col
            im[y, x, 3] = 255
    for (x, y) in ((3, 4), (11, 9), (7, 13)):
        im[y, x, :3] = (250, 214, 214)
    vx = 2
    for y in range(n):
        vx += rng.randint(-1, 2)
        if 0 <= vx < n:
            im[y, vx, :3] = im[y, vx, :3] * 0.6 + np.array([110, 90, 150]) * 0.4
    return np.clip(np.round(im), 0, 255).astype(np.uint8)


def wetskin():
    """젖은 천처럼 늘어진 얼굴 아래 가장자리: 살색, 아래로 갈수록 짙고 젖은 세로 반사, 맨 아래는 물방울처럼 늘어진 들쭉날쭉한 끝(컷아웃)"""
    n = 16
    im = np.zeros((n, n, 4), np.float32)
    drip = [11 + int(_h("drip", x) * 3) + (3 if x % 5 == 2 else 0) for x in range(n)]
    for y in range(n):
        for x in range(n):
            t = y / (n - 1)
            col = np.array([255, 218, 209], np.float32) * (1 - 0.2 * t) + np.array([-4, -8, -6]) * t
            if x % 5 == 2 and _h("wet", x, y) < 0.8:
                col = col * 0.7 + np.array([255, 245, 245]) * 0.3
            if x % 5 == 4:
                col *= 0.94
            if y == drip[x] - 1:
                col = col * 0.82
            im[y, x, :3] = col
            im[y, x, 3] = 255 if y < min(drip[x], 16) else 0
    return _fill_rest(np.clip(np.round(im), 0, 255).astype(np.uint8))


def chain():
    """쇠사슬 고리(컷아웃): 어두운 쇠 고리 무늬"""
    im = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            dx, dy = (x - 7.5) / 5.5, (y - 7.5) / 7.0
            r = math.hypot(dx, dy)
            if 0.62 < r < 1.0:
                lit = 0.5 + 0.5 * math.cos(math.atan2(dy, dx) + 2.2)
                v = int(70 + 110 * lit)
                im[y, x] = (v, v, v + 8, 255)
    return _fill_rest(im)


def web():
    """거미줄 막(컷아웃): 방사 실 + 동심 실, 흰 회색"""
    im = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            r = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            radial = abs(math.sin(a * 4)) < 0.12 + 0.2 / (r + 1)
            ring = abs((r % 3.2) - 1.6) < 0.35
            if radial or ring:
                im[y, x] = (232, 230, 236, 255)
    return _fill_rest(im)


def bone():
    """뼈 이빨: 상아색, 금"""
    im = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            v = 0.9 + 0.1 * math.sin(x * 1.3 + y * 0.4)
            im[y, x] = (int(232 * v), int(220 * v), int(192 * v), 255)
    for y in range(3, 13):
        im[y, 5 + (y % 3 == 0)] = (160, 144, 118, 255)
    return im


def _fill_rest(im):
    """빈 화소(알파 0)는 이웃 색으로(컷아웃 가장자리)"""
    if (im[..., 3] == 0).any() and (im[..., 3] > 0).any():
        return _fill_clear(im)
    return im


def _class0(fn):
    """부품 텍스처는 모두 화소 부류 0(살): 파랑 아래 3비트를 지워 팔레트(무표정, 흰 눈, 홍조 바꾸기)가 얼룩을 남기지 않게"""
    def make():
        im = np.array(fn(), np.uint8, copy=True)
        im[..., 2] &= 0xF8
        return im
    make.__doc__ = fn.__doc__
    return make


PARTS = {name: _class0(fn) for name, fn in
         {"rod": rod, "flesh": flesh, "wetskin": wetskin, "chain": chain, "web": web, "bone": bone}.items()}


def preview(names, out, scale=8):
    """얼굴 처방들을 가로로 늘어놓은 미리보기(먼 얼굴 + 가까운 얼굴)"""
    tiles = []
    for n in names:
        a = Image.fromarray(far(n)).resize((32 * scale // 2, 32 * scale // 2), Image.NEAREST)
        b = Image.fromarray(near(n)).resize((32 * scale // 2, 32 * scale // 2), Image.NEAREST)
        t = Image.new("RGBA", (a.width, a.height * 2), (40, 70, 40, 255))
        t.alpha_composite(a, (0, 0))
        t.alpha_composite(b, (0, a.height))
        tiles.append(t)
    W = sum(t.width for t in tiles) + 4 * len(tiles)
    sheet = Image.new("RGBA", (W, tiles[0].height), (40, 70, 40, 255))
    x = 0
    for t in tiles:
        sheet.alpha_composite(t, (x, 0))
        x += t.width + 4
    sheet.save(out)
