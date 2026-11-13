"""G18 겹겹 덧댄 게이브: 거대한 코끼리 탁자. 2.6 블록 얼굴(사람 둘 키, 머리 위 막대기까지 4.6 블록) 위에 다른 게이브 얼굴 가죽 5장이 비늘처럼
겹겹이 기워져 있고(적갈색 바늘땀), 굵은 살기둥 다리 넷이 네 귀퉁이에서 땅을 짚어 쿵쿵 걷는다. 등 위 뒤 모서리에서 엔드 막대기 둘(원형 DNA)이
솟아 까딱인다. 남은 가죽 장수가 곧 체력(상태 s5 .. s0). 맨 마지막엔 가죽 없는 분홍 살 얼굴.

모델 gave:gabe/g18 상태 "s5", "s4", "s3", "s2", "s1", "s0"(기본 s5). 가장 바깥 가죽(s0 은 살 얼굴)은 표적을 찍으면 눈을 뜬다(open).
  틀 ±1.5 블록을 넘으므로 0.5 로 줄여 굽는다(엔진이 Look.scale 로 되돌림). 다리 넷은 죽으면 흐물거린다(사체가 바로 선 채 굳지 않게).
모델 gave:gabe/g18_skin 상태 "a" .. "e": 벗겨져 떨어진 가죽 한 장(바닥에서 꿈틀, 자세 4, 1.36 블록). Java 가 연출 디스플레이에 실어 떨어뜨린다.
"""
import numpy as np
from PIL import Image

from gabe import audio, faces, kit

SIZE = 2.6
DEPTH = 1.15
K = SIZE / 1.6  # 옛 1.6 블록 얼굴에서 키운 배
HOVER = 2.6
# 가죽 다섯 장: (가운데 x, y, 크기, 기울기 도, 얼굴), 차례 = 안쪽부터(5번이 가장 바깥, 먼저 벗겨짐)
SKINS = [
    ((-0.4 * K, 0.4 * K), 0.98 * K, -6, faces.Face(eyes="half")),
    ((0.4 * K, 0.4 * K), 0.98 * K, 7, faces.Face(extras=("tears",))),
    ((-0.4 * K, -0.38 * K), 0.98 * K, 5, faces.Face(tilt=-14.0)),
    ((0.4 * K, -0.38 * K), 0.98 * K, -8, faces.Face(blush="deep")),
    ((0.0, 0.02 * K), 0.9 * K, 3, faces.Face(tilt=14.0)),
]
SKIN_NAMES = "abcde"
RAW = faces.Face(extras=("veins",), blush="deep")
FLESH = np.array([206, 104, 112], np.float32)
# 다리 넷: (x, z, 위상) 대각선끼리 같은 위상(코끼리 걸음)
LEGS = ((0.85, 0.3, 0), (-0.85, -0.3, 0), (-0.85, 0.3, 180), (0.85, -0.3, 180))
LEG_TOP = -SIZE / 2 + 0.12


def _u8(a):
    return np.clip(np.round(a), 0, 255).astype(np.uint8)


def stitched(img, k=1, seed=0):
    """가장자리에 적갈색 바늘땀, 살은 조금 바래게(벗겨 온 가죽). 부류 비트는 그대로"""
    a = img.astype(np.float32).copy()
    n = a.shape[0]
    cls = img[..., 2] & 7
    skin = cls == 0
    a[skin, :3] = a[skin, :3] * 0.92 + np.array([238, 206, 196]) * 0.08
    rng = np.random.RandomState(seed)
    step = 3 * k
    for i in range(0, n, step):
        for (x, y) in ((i, 0), (i, n - 1), (0, i), (n - 1, i)):
            for d in range(k):
                for e in range(k):
                    xx = min(n - 1, x + d) if x < n - 1 else x - d
                    yy = min(n - 1, y + e) if y < n - 1 else y - e
                    a[yy, xx, :3] = (96, 34, 32)
        o = int(rng.randint(0, 2)) * k
        for (x, y) in ((i + o, k), (i + o, n - 1 - k), (k, i + o), (n - 1 - k, i + o)):
            if 0 <= x < n and 0 <= y < n:
                a[y, x, :3] = (120, 46, 42)
    out = _u8(a)
    out[..., 2] = (out[..., 2] & 0xF8) | cls
    return out


def raw(img):
    """가죽 없는 분홍 살 얼굴: 살 부류 화소를 날살 색으로(눈, 입, 홍조는 그대로)"""
    a = img.astype(np.float32).copy()
    cls = img[..., 2] & 7
    skin = cls == 0
    n = a.shape[0]
    rng = np.random.RandomState(n)
    lum = a[..., :3].mean(-1, keepdims=True) / 230.0
    a[skin, :3] = (FLESH * lum[skin] * (0.92 + 0.12 * rng.rand(int(skin.sum()), 1)))
    out = _u8(a)
    out[..., 2] = (out[..., 2] & 0xF8) | cls
    return out


def face_pair(k, name, face, fn):
    """(먼 32, 가까운 128) 텍스처 id"""
    far = k.texture(name, fn(faces.far(face), 1), classes=True)
    near = k.texture(name + "_n", fn(faces.near(face), 4), classes=True)
    return far, near


def skin_quad(s, center, size, rot, tex, z, show=()):
    far, near = tex
    s.quad((center[0], center[1], z), (size, size), "south", far, dist="far", face=True, rot=(0, 0, rot), origin=(center[0], center[1], z),
           show=show)
    s.quad((center[0], center[1], z + 0.004), (size, size), "south", near, dist="near", face=True, rot=(0, 0, rot),
           origin=(center[0], center[1], z), show=show)


def build(k: kit.Kit):
    m = k.model(poses=6, period=24, shrink=0.5, mut2=dict(length=1.4, thick=0.14))
    skins = []
    for i, (c, size, rot, f) in enumerate(SKINS):
        shut = face_pair(k, f"skin_{SKIN_NAMES[i]}", f, lambda im, kk, i=i: stitched(im, kk, i))
        opened = face_pair(k, f"skin_{SKIN_NAMES[i]}_o", f.with_(eyes="open"), lambda im, kk, i=i: stitched(im, kk, i))
        skins.append((c, size, rot, shut, opened))
    raw_shut = face_pair(k, "raw", RAW, lambda im, kk: raw(im))
    raw_open = face_pair(k, "raw_o", RAW.with_(eyes="open"), lambda im, kk: raw(im))
    front = DEPTH / 2
    for n in range(5, -1, -1):
        s = m.state(f"s{n}")
        h = s.head(size=SIZE, depth=DEPTH, face="smile", open=False, torn=False)
        # 바탕: 날살 얼굴(가죽이 다 벗겨지면 보임). 맨 바깥이면 표적을 찍을 때 눈을 뜸
        z0 = front + 0.012
        if n == 0:
            skin_quad(s, (0, 0), SIZE, 0, raw_shut, z0, show=("!open",))
            skin_quad(s, (0, 0), SIZE, 0, raw_open, z0, show=("open",))
        else:
            skin_quad(s, (0, 0), SIZE, 0, raw_shut, z0)
        for i in range(n):
            c, size, rot, shut, opened = skins[i]
            z = front + 0.03 + 0.03 * i
            if i == n - 1:
                skin_quad(s, c, size, rot, shut, z, show=("!open",))
                skin_quad(s, c, size, rot, opened, z, show=("open",))
            else:
                skin_quad(s, c, size, rot, shut, z)
        # 굵은 살기둥 다리 넷: 네 귀퉁이에서 땅까지(hover 2.6), 대각선끼리 같이 들썩(쿵쿵 걸음). 죽으면 흐물
        for (x, z, ph) in LEGS:
            w = kit.Wave(amp=(7, 9), lag=40, bias=(0, -4), phase=ph)
            s.rod((x, LEG_TOP, z), (0, -1, 0), length=HOVER + LEG_TOP, segs=2, thick=0.45, tex="flesh", wave=w, toward=(0, 0, 1), poses=4,
                  base=False, rag=True)
        # 원형 DNA: 등 위 뒤 모서리에서 솟아 까딱이는 짧은 막대기 둘
        for side in (1, -1):
            w = kit.Wave(amp=(12, 16), lag=50, phase=0 if side > 0 else 90)
            s.rod(h.point(side * 0.6, 0.95, -0.9), (side * 0.4, 1.0, -0.6), length=0.8, segs=2, thick=0.16, wave=w, poses=4)
    m.default = "s5"
    # 떨어진 가죽 한 장: 바닥에서 꿈틀(자세 4). 연출 디스플레이에 실리므로 실제 크기로 굽는다
    fm = k.model("g18_skin", poses=4, period=12, mut2=False, open=False, torn=False)
    sz = 0.85 * K
    for i, (c, size, rot, shut, opened) in enumerate(skins):
        s = fm.state(SKIN_NAMES[i])
        for f in s.frames():
            with f:
                ang = np.sin(f.t * 2 * np.pi) * 18
                curl = np.cos(f.t * 2 * np.pi) * 10
                s.quad((0, 0.02 + 0.04 * abs(np.sin(f.t * 2 * np.pi)), 0), (sz, sz), "up", shut[0], rot=(ang, curl, 0),
                       origin=(0, 0, 0))
                s.quad((0, 0.01, 0), (sz, sz), "down", shut[0], rot=(ang, curl, 0), origin=(0, 0, 0))
    fm.default = "a"
    k.icon(model="g18", state="s5")


def peel(seed=0, dur=0.55):
    """'쩌억': 끈적하게 떨어지는 가죽(찢기는 잡음 + 미끄러지는 낮은 음)"""
    rip = audio.env(audio.bandpass(audio.noise(dur, seed), 600, 3600), 0.003, 0.08, 0.55, 0.25)
    t = np.linspace(0, 1, len(rip), dtype=np.float32)
    rip = rip * (0.6 + 0.4 * np.sin(t * 60 + seed) ** 2)
    low = audio.env(audio.tone(180, dur, "tri", glide=70), 0.01, 0.1, 0.4, 0.2) * 0.6
    return audio.normalize(audio.fade(audio.mix(rip, low)), 0.85)


def sounds(k: kit.Kit):
    # 이 종의 신호: 느린 '쿵, 쿵' 미는 걸음
    k.sound("stomp", "쿵, 쿵", synth=lambda i: audio.thump(seed=60 + i, dur=0.32, freq=62), vanilla=[("mob/ravager/step1", 0.6)], volume=1.0,
            attenuation=24)
    k.sound("peel", "쩌억 벗겨짐", synth=lambda i: peel(seed=64 + i), attenuation=24)
    k.sound("shove", "퍽 밀침", vanilla=[("mob/slime/big2", 0.55), ("mob/slime/big4", 0.5)], synth=lambda i: audio.thump(seed=68 + i, freq=90),
            attenuation=16)


def phrases():
    return {"cause_g18": "겹친 얼굴에 떠밀렸다"}
