"""G71 해 가리는 날개 게이브(1막 6라운드, 방해 + 원거리): 날개 폭 16블록의 뼈대만 남은 큰 날개 한 쌍. 날개마다 굵은 엔드 막대기 둘이 날개 뼈
(어깨에서 날개 끝으로)이고 그 사이에 _Gave 얼굴 수십 장을 기워 붙인 날개 막이 팽팽하다. 두 날개가 만나는 자리에 3블록 얼굴이 아래를 보고, 그 뒤로
엔드 막대기 꽁지 하나가 길게 뻗어 작은 얼굴 깃 다섯이 달렸다. 몸통은 없다.

모델(크기는 블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림):
  gave:gabe/g71         가운데 얼굴(앞면이 아래 -Y, 이마가 앞 +Z) + 어깨 막대기 + 꽁지(뒤 -Z)와 얼굴 깃 다섯. 날개는 없음
  gave:gabe/g71_wing    날개 하나(가운데 = 어깨 관절). 상태 "l"(+X 쪽, 게이브 자신의 왼쪽), "r"(-X 쪽 거울)
                        Java 가 Part.pose 로 어깨에서 돌려 날갯짓한다(자세 없음)
  gave:gabe/g71_shadow  바닥 날개 그림자(점묘 컷아웃, 18 x 9). 상태 s1 옅음, s2 짙음(낮게 내려올 때)
  gave:gabe/g71_gust    돌풍 흰 바람 띠(단위 판 1 x 1, Java 가 길이 24 x 폭 3 으로 늘림). 상태 "" 옅음(일어남), "go" 짙음(밀려옴)
  gave:gabe/g71_strip   낮게 긁기 예고 그림자 띠(단위 판, 앞 +Z 로 길이 1). 상태 "" 옅음, "dark" 짙음
"""
import math

import numpy as np

from gabe import faces, kit

FACE = 3.0
# 날개 뼈(왼쪽 날개, 어깨 관절 기준): (뿌리, 끝)
BONE_A = ((0.25, 0.0, 0.55), (8.2, 1.0, -0.3))
BONE_B = ((0.25, -0.05, -0.35), (6.7, 0.35, -3.9))
PATCH = [faces.Face(), faces.Face(eyes="half"), faces.Face(extras=("stitch",)), faces.Face(eyes="open", blush="deep"),
         faces.Face(extras=("tears",)), faces.Face(tilt=-14.0, extras=("stitch",)), faces.Face(mouth="torn"), faces.Face(blush="gold")]


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def _lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def wing(s, k, side):
    """날개 하나: 뼈 둘(굵은 막대기, 펄럭이지 않음) + 그 사이를 기운 얼굴 막(아래, 위 두 면)"""
    sx = 1 if side == "l" else -1
    m = lambda p: (p[0] * sx, p[1], p[2])  # noqa: E731
    for part, (root, tip) in (("cut_a", BONE_A), ("cut_b", BONE_B)):
        d = tuple(tip[i] - root[i] for i in range(3))
        ln = math.sqrt(sum(c * c for c in d))
        with s.when(f"!on:{part}"):
            s.rod(m(root), m(d), length=ln, segs=2, thick=0.42, wave=None, toward=(0, 0, 1), rag=False)
        with s.when(f"on:{part}"):
            # 끊긴 뼈: 뿌리 쪽 그루터기만
            s.rod(m(root), m(d), length=ln * 0.3, segs=1, thick=0.42, wave=None, toward=(0, 0, 1), rag=False)
    texs = [k.face_tex(f) for f in PATCH]
    i = 0
    for u in (0.1, 0.22, 0.34, 0.46, 0.58, 0.7, 0.82, 0.93):
        a = _lerp(BONE_A[0], BONE_A[1], u)
        b = _lerp(BONE_B[0], BONE_B[1], min(1.0, u * 1.02))
        span = math.dist(a, b)
        n = max(1, round(span / 1.25))
        for j in range(n):
            v = (j + 0.5) / n
            c = _lerp(a, b, v)
            size = max(1.3, min(2.2, span / n * 1.55)) * (1.0 - 0.2 * u)
            ry = ((i * 47) % 50 - 25) * sx
            slope = math.degrees(math.atan2(BONE_A[1][1], BONE_A[1][0])) * sx
            t = texs[i % len(texs)]
            s.quad(m(c), (size, size), "down", t, face=True, rot=(0, ry, slope), origin=m(c))
            s.quad(m((c[0], c[1] + 0.03, c[2])), (size, size), "up", texs[(i + 3) % len(texs)], face=True, rot=(0, ry, slope),
                   origin=m(c))
            i += 1
    # 뒤 가장자리(뼈 B 뒤로 처진 막): 작은 얼굴 넷
    for u in (0.25, 0.45, 0.65, 0.85):
        b = _lerp(BONE_B[0], BONE_B[1], u)
        c = (b[0] - 0.3, b[1] - 0.05, b[2] - 0.75)
        sz = 1.25 - 0.35 * u
        s.quad(m(c), (sz, sz), "down", texs[(i + 5) % len(texs)], face=True, rot=(0, -20 * sx, 0), origin=m(c))
        s.quad(m((c[0], c[1] + 0.03, c[2])), (sz, sz), "up", texs[i % len(texs)], face=True, rot=(0, -20 * sx, 0), origin=m(c))
        i += 1


def core(s, k):
    """가운데 얼굴(아래를 봄) + 어깨 막대기 + 꽁지와 얼굴 깃 다섯"""
    s.head(size=FACE, height=FACE, depth=1.3, at=(0, 0, 0), face="smile", rot=(90, 0, 0), torn=False)
    for sx in (1, -1):
        s.rod((sx * 0.9, 0.4, 0.3), (sx * 1.0, 0.05, 0.1), length=1.2, segs=1, thick=0.5, wave=None, toward=(0, 0, 1), rag=False)
    # 꽁지: 얼굴 뒤에서 뒤로 길게(곧게, 조금 처짐)
    s.rod((0, 0.1, -1.45), (0, -0.08, -1), length=6.6, segs=3, thick=0.34, wave=None, toward=(1, 0, 0), rag=False)
    feather = [faces.Face(eyes="half"), faces.Face(), faces.Face(eyes="open"), faces.Face(extras=("tears",)), faces.Face(blush="deep")]
    for n in range(5):
        t = 0.25 + n * 0.17
        z = -1.45 - 6.6 * t
        y = 0.1 - 0.08 * 6.6 * t
        sz = 0.85 - n * 0.08
        sx = 1 if n % 2 == 0 else -1
        s.head(size=sz, depth=0.25, at=(sx * (sz / 2 + 0.12), y - 0.15, z), face=feather[n], rot=(90, sx * 15, 0), near=False, torn=False)


def shadow_tex(dens, w=192, h=96):
    """날개 그림자(위를 보는 판, 그림 왼쪽 = -X, 위 = -Z): 두 날개 막(뼈 A, B 사이 + 뒤 가장자리) + 가운데 얼굴 + 꽁지"""
    out = np.zeros((h, w, 4), np.uint8)
    bay = _bayer()
    ax, az = BONE_A[1][0], BONE_A[1][2]
    bx, bz = BONE_B[1][0], BONE_B[1][2]
    for y in range(h):
        for x in range(w):
            X = (x + 0.5) / w * 18.0 - 9.0
            Z = (y + 0.5) / h * 9.0 - 6.0
            ux = abs(X)
            inside = 0.0
            if ux <= 1.6 and -1.6 <= Z <= 1.6:
                inside = 1 - max(ux, abs(Z)) / 1.6 * 0.3
            elif ux < 0.35 and -8.2 < Z < -1.4:
                inside = 0.8
            elif ux <= ax:
                t = ux / ax
                za = 0.55 + (az - 0.55) * t
                zb = -0.35 + (bz + 0.35) * min(1.0, ux / bx) - 0.9 * math.sin(math.pi * min(1.0, ux / bx))
                if ux > bx:
                    zb = za - (ax - ux) / (ax - bx) * (za - bz)
                if zb <= Z <= za:
                    edge = min(Z - zb, za - Z)
                    inside = min(1.0, edge / 0.9)
            if inside <= 0:
                continue
            d = dens * inside
            if bay[y % 4, x % 4] < d:
                v = 0.85 + 0.15 * math.sin(x * 0.5 + y * 0.7)
                out[y, x] = (int(28 * v), int(18 * v), int(26 * v), 255)
    return faces._fill_rest(out)


def gust_tex(dens, w=128, h=24, seed=71):
    """흰 바람 띠: 가로로 긴 바람 결(짧은 줄 무리), 가운데 짙고 앞뒤 가장자리 성김"""
    rng = np.random.RandomState(seed)
    out = np.zeros((h, w, 4), np.uint8)
    for _ in range(int(140 * dens)):
        y = int(np.clip(rng.normal(h / 2, h / 4.2), 0, h - 1))
        x0 = rng.randint(0, w)
        ln = rng.randint(6, 26)
        for x in range(x0, min(w, x0 + ln)):
            c = 236 + rng.randint(0, 19)
            out[y, x] = (c, c, min(255, c + 6), 255)
    return faces._fill_rest(out)


def strip_tex(dens, w=24, h=96):
    """긁기 예고 그림자 띠: 세로로 긴 점묘(폭 쪽 가장자리 성김)"""
    out = np.zeros((h, w, 4), np.uint8)
    bay = _bayer()
    for y in range(h):
        for x in range(w):
            e = abs((x + 0.5) / w * 2 - 1)
            d = dens * min(1.0, (1 - e) / 0.4)
            if bay[y % 4, x % 4] < d:
                out[y, x] = (34, 18, 26, 255)
    return faces._fill_rest(out)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, shrink=0.18, mut2=False, torn=False, rag=False)
    core(m.state(""), k)
    w = k.model("g71_wing", poses=1, period=20, shrink=0.18, mut2=False, open=False, torn=False, rag=False)
    for side in ("l", "r"):
        wing(w.state(side), k, side)
    w.default = "l"
    sh = k.model("g71_shadow", poses=1, period=20, shrink=0.16, mut2=False, open=False, torn=False, rag=False)
    for i, dens in enumerate((0.34, 0.66), start=1):
        sh.state(f"s{i}").quad((0, 0, 0), (18, 9), "up", k.texture(f"shadow{i}", shadow_tex(dens)))
    sh.default = "s1"
    gu = k.model("g71_gust", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    gu.state("").quad((0, 0, 0), (1, 1), "up", k.texture("gust1", gust_tex(0.5)))
    gu.state("go").quad((0, 0, 0), (1, 1), "up", k.texture("gust2", gust_tex(1.0, seed=72)))
    st = k.model("g71_strip", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    st.state("").quad((0, 0, 0.5), (1, 1), "up", k.texture("strip1", strip_tex(0.4)))
    st.state("dark").quad((0, 0, 0.5), (1, 1), "up", k.texture("strip2", strip_tex(0.85)))
    k.icon(icon_img(k))


def icon_img(k):
    """HUD 아이콘: 위에서 본 날개 실루엣에 가운데 얼굴"""
    from PIL import Image
    sh = Image.fromarray(shadow_tex(1.0)).resize((64, 32), Image.NEAREST)
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = np.array(sh)
    px[..., :3] = np.where(px[..., 3:4] > 0, np.array([236, 190, 182]), 0)
    im.alpha_composite(Image.fromarray(px.astype(np.uint8)), (0, 14))
    face = Image.fromarray(faces.far("smile")).resize((18, 18), Image.NEAREST)
    im.alpha_composite(face, (23, 16))
    return im


def sounds(k: kit.Kit):
    k.sample("gust", "tag:wind", "거대한 날개 바람", pitch=0.6, attenuation=128)
    k.sample("flap", "tag:flap", "거대한 날갯짓", pitch=0.45, attenuation=96)
    k.sample("scrape", "tag:scratch", "꽁지가 땅을 긁는다", pitch=0.6, attenuation=48)


def phrases():
    return {"cause_g71": "날개 바람에 휩쓸렸다", "cause_g71_tail": "날개 꽁지에 긁혔다"}
