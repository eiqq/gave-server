"""G177 우는 장막 게이브: 키 3.4 블록의 마른 기둥 몸을 바닥까지 끌리는 긴 장막이 덮는다. 장막은 눈물 자국이 줄줄 난 _Gave 얼굴을 세로로
길게 늘인 천(앞 세 폭에 얼굴이 세로로 나뉘어 그려짐, 뒤 세 폭은 젖은 천)이고, 꼭대기에 우는 얼굴이 있다. 장막 아래로 엔드 막대기 발 둘이
죽마처럼 보이고, 머리 꼭대기에서 장막을 받치는 막대기 끝이 뿔처럼 비죽 솟아 있다.

모델 gave:gabe/g177(shrink 0.6, 몸 중심 = 땅 위 1.75, 위아래 ±1.7):
- ""(장막이 끌리며 흔들림 4자세 16틱), "swell"(휘두르기 직전 부풀어 오름), "sweep"(크게 휘두름: 같은 주기에 두 번, 넓게).
덮개 g177_veil(끌려 들어간 사람: 젖은 천 안쪽, 늘어진 얼굴의 안쪽이 웃음).
소리 sob(흐느낌, 64: Java 가 사람마다 거리로 볼륨을 정해 한 사람에게만), drag(장막이 땅에 끌리는 소리, 12), sweep(휘두름, 24),
spit(뱉음, 20). 글귀 cause_g177, cause_g177_wrap.
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

CRY = faces.Face(eyes="squint", mouth="dot", extras=("tears",))
H_TOP, H_BOT = 0.9, -1.6  # 장막 뿌리와 끝(블록)
R = 0.42  # 장막 고리 반지름


def strip_tex(i, n=3):
    """우는 얼굴(32)을 세로로 n 폭으로 나눈 i 번째 폭을 32x32 로 늘임(눈물 줄이 길게)"""
    a = faces.far(CRY)
    x0, x1 = round(32 * i / n), round(32 * (i + 1) / n)
    im = Image.fromarray(a[:, x0:x1]).resize((32, 32), Image.NEAREST)
    return np.array(im)


def cloth_tex(seed=177):
    n = 16
    rng = np.random.RandomState(seed)
    col = np.full((n, n, 3), (214, 168, 160), np.float32) + rng.randint(-10, 11, (n, n, 1))
    for x in range(0, n, 4):
        col[:, x] *= 0.86  # 세로 주름
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    out[..., 3] = 255
    return out


def veil(s, k, sway, flare):
    """기둥, 꼭대기 얼굴, 뿔 막대기, 장막 여섯 폭, 죽마 발 둘"""
    # 기둥(장막 속, 위아래로 꿰는 막대기)과 뿔처럼 비죽 솟은 끝
    s.rod((0, -1.2, 0), (0, 1, 0), length=2.9, segs=2, thick=0.12, toward=(0, 0, 1), base=False, rag=False)
    s.rod((0.05, 1.45, -0.05), (0.15, 1, -0.1), length=0.32, segs=1, thick=0.09, base=False, rag=False)
    s.rod((-0.12, 1.4, 0.0), (-0.45, 1, 0.1), length=0.22, segs=1, thick=0.07, base=False, rag=False)
    # 꼭대기 우는 얼굴(실눈, 휘두르기 직전 Java 가 눈 뜸)
    s.head(size=0.78, depth=0.6, at=(0, 1.08, 0.04), face=CRY)
    front = [k.texture(f"veil{i}", strip_tex(i), classes=True) for i in range(3)]
    back = k.texture("cloth", cloth_tex())
    # 장막 여섯 폭: 앞 세 폭(얼굴 폭 왼쪽부터), 뒤 세 폭(젖은 천). 각도 90 이 앞(+Z)
    for j, ang in enumerate((150, 90, 30, 330, 270, 210)):
        a = math.radians(ang)
        ox, oz = math.cos(a), math.sin(a)
        tex = front[j] if j < 3 else back
        wave = kit.Wave(amp=(sway, sway * 1.4, sway * 1.8), lag=50, bias=(flare, flare * 0.4, flare * 0.3), phase=j * 60,
                        cycles=2 if flare > 30 else 1)
        s.band((ox * R, H_TOP, oz * R), (ox * 0.12, -1, oz * 0.12), length=H_TOP - H_BOT, width=0.5, segs=3, thick=0.02, tex=tex,
               wave=wave, toward=(ox, 0, oz), poses=4)
    # 죽마 발 둘(장막 아래로 보임)
    for side, ph in ((1, 0), (-1, 180)):
        s.rod((side * 0.16, -1.15, 0), (side * 0.08, -1, 0), length=0.58, segs=1, thick=0.08,
              wave=kit.Wave(amp=10, lag=0, phase=ph), toward=(0, 0, 1), base=False, poses=4)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, shrink=0.6, mut2=False)
    veil(m.state(""), k, sway=6, flare=4)
    veil(m.state("swell"), k, sway=4, flare=26)
    veil(m.state("sweep"), k, sway=14, flare=58)
    # 덮개: 끌려 들어간 사람이 보는 장막 안쪽(늘어진 얼굴의 안쪽이 웃는다)
    k.cover_face("g177_veil", face="smile", zoom=2.2, vignette=0.95)


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 길게 흐느끼는 울음. Java 가 사람마다 볼륨을 거리와 거꾸로 줘서 한 사람에게만 보낸다(팩 반경은 넉넉히 64)
    k.sample("sob", "tag:scared", "흐느낌", pitch=0.85, attenuation=64)
    k.sample("drag", "tag:cloth", "장막 끌리는 소리", pitch=0.6, volume=0.6, attenuation=12)
    k.sample("sweep", "kenney_rpg/cloth*", "장막 휘두름", pitch=0.5, attenuation=24, n=2)
    k.sample("spit", "tag:wet+slime", "뱉음", pitch=0.7, attenuation=20)


def phrases():
    return {"cause_g177": "장막에 휩쓸렸다", "cause_g177_wrap": "장막 속에서 짓눌렸다"}
