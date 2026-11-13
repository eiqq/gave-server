"""G192 초승달 조각 게이브(4막 33라운드, 떼, 돌격, 0.8블록): 얼굴의 대부분이 어둠에 깎여 초승달 꼴 가장자리만 남은 얇은 얼굴 조각. 남은 조각에
실눈 하나와 입꼬리 하나. 초승달 두 끝을 짧은 엔드 막대기 둘이 뿔처럼 이어 옆에서 보면 낫 같다. 밝은 데서는 거의 안 보이고(얇고 어두움),
어두워지면 가장자리가 희게 빛난다(Java 가 밝기만 올림: 발광 테두리 아님).

모델(개체마다 디스플레이 하나, Java G192):
  gave:gabe/g192        초승달 판(앞뒤 두 면) + 뿔 막대기 둘(휘어짐 2자세). 상태 "" 어두운 빛깔, "lit" 가장자리 흰 테
  gave:gabe/g192_shade  일식 그늘(위를 보는 짙은 점묘 원 2x2 + 옅은 테). Java 가 반경 4 로 늘임(연출 예산 디스플레이)
소리는 받은 소리(k.sample).
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

SIZE = 0.8


def _bayer():
    return np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def crescent_tex(lit):
    """얼굴 32 를 바깥 원(16, 16, r15) 빼기 안쪽 원(25, 12, r13) 으로 깎은 초승달. 실눈 하나(왼쪽)와 입꼬리가 남는다.
    lit 이 아니면 어둡게(밤하늘에 거의 안 보이게), lit 이면 깎인 가장자리 두 화소가 희게"""
    f = faces.far(faces.Face(eyes="squint", mouth="torn", blush="deep")).astype(np.float32)
    n = 32
    out = np.zeros((n, n, 4), np.uint8)
    for y in range(n):
        for x in range(n):
            ro = math.hypot(x + 0.5 - 16, y + 0.5 - 16)
            ri = math.hypot(x + 0.5 - 25, y + 0.5 - 12)
            if ro > 15 or ri < 13:
                continue
            col = f[y, x, :3]
            rim = ri < 15.0 or ro > 13.6
            if lit:
                if rim:
                    col = np.array([250, 248, 255], np.float32)
            else:
                col = col * 0.32 + np.array([10, 8, 16], np.float32)
                if rim:
                    col = np.array([58, 54, 70], np.float32)
            out[y, x, :3] = np.clip(col, 0, 255)
            out[y, x, 3] = 255
    return faces._fill_rest(out)


def shade_tex(n=64):
    """일식 그늘: 가운데 짙은 점묘, 가장자리 옅은 흰 테(코로나)"""
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    bay = _bayer()
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r > 1:
                continue
            if 0.9 < r <= 1.0 and (x + y) % 3 != 0:
                out[y, x] = (196, 196, 214, 255)
            elif 0.92 * min(1, (0.95 - r) / 0.25 + 0.3) > bay[y % 4, x % 4]:
                out[y, x] = (6, 4, 10, 255)
    return faces._fill_rest(out)


def body(s, k, lit):
    t = k.texture("crescent_lit" if lit else "crescent", crescent_tex(lit))
    h = SIZE / 2
    s.box((-h, -h, 0), (h, h, 0), {"south": t, "north": t}, faces=("south", "north"), uv=[0, 0, 16, 16], face=True, shade=False)
    # 뿔: 초승달 두 끝(오른쪽 위와 아래)에서 앞으로 나와 서로를 향해 굽음(낫)
    tips = ((0.13, 0.37), (0.13, -0.37))
    for f in s.frames(poses=2):
        with f:
            bend = 18 + 14 * f.k
            for side, (x, y) in zip((1, -1), tips):
                a = math.radians(bend)
                s.rod((x, y, 0.02), (0.35, -side * math.sin(a), math.cos(a)), length=0.36, segs=1, thick=0.05, base=False,
                      toward=(0, 1, 0), rag=False)


def build(k: kit.Kit):
    m = k.model(poses=2, period=12, mut2=False, open=False, torn=False)
    body(m.state(""), k, False)
    body(m.state("lit"), k, True)
    sh = k.model("g192_shade", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    st = sh.state("")
    st.front = (0, 0, 0)
    st.quad((0, 0, 0), (2, 2), "up", k.texture("shade", shade_tex()))
    k.icon(img=Image.fromarray(crescent_tex(True)).resize((64, 64), Image.NEAREST))


def sounds(k: kit.Kit):
    k.sample("screech", "tag:chitter", "끼익 떼 울음", pitch=1.15, attenuation=48)
    k.sample("swish", "tag:blade", "베고 지나감", pitch=1.3, attenuation=16)


def phrases():
    return {"cause_g192": "초승달 조각에 베였다"}
