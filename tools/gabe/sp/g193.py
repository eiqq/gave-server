"""G193 빛 쫓는 게이브(4막 33라운드, 돌격, 목표 압박, 8블록): 하늘을 달리는 네 발 짐승. 검게 그을린 2.5블록 얼굴이 머리, 몸통은 얼굴 판 갈비,
다리는 굵은 엔드 막대기 넷이라 달릴 때 허공을 찬다(달리기 4자세). 꼬리 자리에 불씨처럼 붉은 작은 얼굴. 빛을 삼킨 놈은 배가 등불처럼 속에서
빛난다(배는 따로 부품: Java 가 밝기 15).

모델(블록 그대로, 줄여 구워 엔진이 Look.scale 로 되돌림. 앞 +Z, Java 는 Body.carrier + 부품):
  gave:gabe/g193        짐승(머리, 얼굴 판 갈비 넷, 등뼈 막대기, 다리 넷 달리기 4자세, 꼬리 불씨 얼굴). 상태 "" 실눈, "open" 눈 뜸(내려오기 직전)
  gave:gabe/g193_belly  배(갈비 밑 살 주머니). 상태 "" 처진 살, "full" 삼킨 빛이 속에서 흔들림
  gave:gabe/g193_line   바닥 그림자 줄(위를 보는 판 2x2: Java 가 폭 4, 길이 60 으로 늘임)
소리는 받은 소리(k.sample).
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

HEAD = 2.5


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def scorched(face, k=0.42, tint=(30, 8, 4)):
    """그을린 얼굴: 얼굴 그림을 어둡게 + 붉은 그을음, 눈 줄과 흰자는 조금 덜 어둡게(멀리서도 얼굴로 읽히게)"""
    f = faces.far(face).astype(np.float32)
    rgb = f[..., :3]
    lum = rgb.mean(axis=2, keepdims=True)
    out = rgb * k + np.array(tint, np.float32)
    bright = lum > 200
    out = np.where(bright, rgb * 0.8, out)
    res = np.zeros((32, 32, 4), np.uint8)
    res[..., :3] = np.clip(out, 0, 255)
    res[..., 3] = 255
    return res


def ember(face):
    """꼬리 불씨 얼굴: 붉게 달아오른 얼굴"""
    f = faces.far(face).astype(np.float32)
    res = np.zeros((32, 32, 4), np.uint8)
    res[..., 0] = np.clip(f[..., 0] * 0.5 + 140, 0, 255)
    res[..., 1] = np.clip(f[..., 1] * 0.35 + 30, 0, 255)
    res[..., 2] = np.clip(f[..., 2] * 0.2 + 10, 0, 255)
    res[..., 3] = 255
    return res


def char_tex(seed):
    rng = np.random.RandomState(seed)
    out = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            v = 0.7 + rng.rand() * 0.3
            out[y, x] = (int(46 * v), int(30 * v), int(30 * v), 255)
    return out


def belly_tex(full):
    """배: 처진 살(어두운 분홍 주름), full 이면 살 속에서 노란 빛이 비침(점묘)"""
    out = np.zeros((32, 32, 4), np.uint8)
    for y in range(32):
        for x in range(32):
            v = 0.8 + 0.15 * math.sin(y * 0.8 + math.sin(x * 0.3) * 2)
            col = (int(120 * v), int(60 * v), int(66 * v))
            if full:
                r = math.hypot(x - 15.5, y - 15.5) / 16
                if (1.1 - r) > _bayer()[y % 4, x % 4] * 0.9:
                    col = (255, int(226 - 60 * r), int(130 - 80 * r))
            out[y, x] = (*col, 255)
    return out


def line_tex():
    """바닥 그림자 줄: 짙은 점묘 띠, 가장자리 성김"""
    w, h = 16, 64
    out = np.zeros((h, w, 4), np.uint8)
    for y in range(h):
        for x in range(w):
            d = abs(x - 7.5) / 8
            if 0.85 * (1 - d * d) > _bayer()[y % 4, x % 4]:
                out[y, x] = (10, 6, 12, 255)
    return faces._fill_rest(out)


def beast(s, k, eyes):
    face = faces.Face(eyes=eyes, mouth="torn", blush="deep")
    t = k.texture("head_" + eyes, scorched(face))
    side = k.texture("char", char_tex(1))
    # 머리: 앞(+Z) 얼굴, 옆은 그을린 살
    hz = 2.9
    s.box((-HEAD / 2, -HEAD / 2 + 0.3, hz - 0.9), (HEAD / 2, HEAD / 2 + 0.3, hz + 0.9), {"south": t, "north": side, "east": side, "west": side, "up": side,
                                                                                     "down": side}, uv=[0, 0, 16, 16], face=True)
    # 갈비: 얼굴 판 넷(양옆으로 보는 얼굴, 몸통 아래로 갈수록 좁아짐)
    rib = [k.texture(f"rib{i}", scorched(faces.Face(eyes=("squint", "half", "squint", "open")[i], mouth="dot", tilt=(-8, 8, 0, 4)[i]), 0.5, (40, 12, 8)))
           for i in range(4)]
    for i, z in enumerate((1.25, 0.05, -1.15, -2.35)):
        w = 1.0 - 0.06 * i
        s.box((-w, -0.9, z - 0.45), (w, 1.1, z + 0.45), {"east": rib[i], "west": rib[i], "up": side, "down": side}, faces=("east", "west", "up", "down"),
              uv=[0, 0, 16, 16], face=True)
    # 등뼈(곧은 막대기)
    s.rod((0, 1.25, 2.0), (0, 0, -1), length=5.4, segs=2, thick=0.22, base=False, toward=(0, 1, 0), rag=False)
    # 꼬리 불씨 얼굴
    s.head(size=0.8, depth=0.6, at=(0, 0.6, -3.7), face="smile", near=False, open=False, torn=False, rot=(0, 180, 0), show=())
    s.box((-0.42, 0.18, -4.02), (0.42, 1.02, -4.01), {"north": k.texture("ember", ember(faces.Face(eyes="half", mouth="dot")))}, faces=("north",),
          uv=[0, 0, 16, 16], face=True)
    # 다리 넷: 달리기 4자세(앞뒤 엇갈림, 허공을 참)
    for f in s.frames(poses=4):
        with f:
            sw = math.sin(f.t * 2 * math.pi)
            for (x, z, ph) in ((0.75, 1.5, 0), (-0.75, 1.5, 1), (0.75, -2.3, 1), (-0.75, -2.3, 0)):
                a = math.radians(38 * (sw if ph == 0 else -sw))
                d = (0, -math.cos(a), math.sin(a))
                s.rod((x, -0.7, z), d, length=2.8, segs=2, thick=0.3, base=False, toward=(0, 0, 1), rag=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, shrink=0.3, mut2=False, open=False, torn=False)
    for name, eyes in (("", "squint"), ("open", "open")):
        st = m.state(name)
        st.front = (0, 0, 1)
        beast(st, k, eyes)
    bm = k.model("g193_belly", poses=1, period=20, shrink=0.5, mut2=False, open=False, torn=False, rag=False)
    for name, full in (("", False), ("full", True)):
        st = bm.state(name)
        st.front = (0, 0, 0)
        st.box((-0.75, -0.55, -1.8), (0.75, 0.45, 1.8), k.texture("belly_full" if full else "belly", belly_tex(full)), uv=[0, 0, 16, 16], face=True)
    lm = k.model("g193_line", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    st = lm.state("")
    st.front = (0, 0, 0)
    st.quad((0, 0, 0), (2, 2), "up", k.texture("line", line_tex()))
    k.icon(img=Image.fromarray(scorched(faces.Face(eyes="open", mouth="torn", blush="deep"))).resize((64, 64), Image.NEAREST))


def sounds(k: kit.Kit):
    k.sample("howl", "tag:roar", "하늘의 울부짖음", pitch=0.6, attenuation=96)
    k.sample("gulp", ["oga_creature80/eat_02", {"id": "oga_creature80/burp_01", "pitch": 0.7}], "빛을 삼킨다", pitch=0.5, attenuation=64)
    k.sample("rush", "tag:wind", "하늘을 가르는 바람", pitch=0.8, attenuation=48)


def phrases():
    return {"cause_g193": "빛 쫓는 짐승에 휩쓸렸다", "cause_g193_drop": "빛 쫓는 짐승이 떨어뜨렸다"}
