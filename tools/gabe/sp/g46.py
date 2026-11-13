"""G46 골라 먹는 게이브: 상어 뼈. 0.9 블록 얼굴 하나(정예)가 머리이고, 뒤로 뼈 등줄기와 갈비 넷 쌍, 끝에 뼈 꼬리지느러미(V). 등에는 꼿꼿한 엔드
막대기 둘(원형 DNA) 사이에 살 막을 친 등지느러미가 선다: 펄럭이지 않고 꼿꼿한 막대기가 이 종의 신호다. 표적 둘레를 맴돌 때 옆모습이 상어다.
사람을 고르기 전에는 실눈, 고르면 실눈이 뜨여 동그란 적갈색 눈동자가 드러나고 그 안에서 작은 흰 반짝임이 천천히 돈다(자세 4, 40틱 한 바퀴).
고개 갸웃(15도)은 Java 가 몸 roll 로 준다. 등줄기가 뒤로 1.6 블록이라 0.75 로 줄여 굽는다(엔진이 Look.scale 로 되돌림).

상태: ""(조용히 떠 있음: 등지느러미 꼿꼿, 꼬리 가만), "lunge"(덮침: 이빨 고리 입이 벌어지고 등지느러미 막대기가 양옆으로 벌어져 빠르게 침,
꼬리 V 가 좌우로 휘저음). 실눈 뜸(찍음), 입 찢어짐(변이 1), 막대기 둘 더(변이 2)는 키트가 붙인다.
"""
import numpy as np

from gabe import audio, faces, kit

S, D = 0.9, 0.62
# 뜬 눈(32 판, 왼눈 x 4..11, y 11..18) 안에서 반짝임이 도는 자리(눈 칸 안 열, 행), 자세 4
GLINT = [(5, 2), (6, 4), (4, 5), (2, 4)]
FLARE = kit.Wave(amp=(28, 22), lag=50, bias=(6, 0), shape="flap", cycles=2)
SPINE = 1.6
RIBS = ((-0.6, 0.75), (-0.9, 0.7), (-1.2, 0.6), (-1.5, 0.45))  # (z, 길이): 앞 갈비는 얼굴보다 넓게(정면에서도 뼈 상어로)


def glint_tex():
    a = np.zeros((2, 2, 4), np.uint8)
    a[..., :3] = (250, 248, 248)
    a[..., 2] = (248 & 0xF8) | faces.CLS_SPARK
    a[..., 3] = 255
    return a


def body(k, s, mode):
    face = "smile" if mode == "" else faces.Face(mouth="teeth_ring")
    h = s.head(size=S, depth=D, face=face)
    px = S / 32
    z = D / 2 + px * 0.34
    tex = k.texture("glint", glint_tex(), classes=True)
    with s.when("open"):
        for f in s.frames(poses=4):
            with f:
                c, r = GLINT[f.k]
                for x in (4 + c, 20 + (7 - c)):
                    cx = (x + 0.5) * px - S / 2
                    cy = S / 2 - (11 + r + 0.5) * px
                    s.quad((cx, cy, z), (px * 1.1, px * 1.1), "south", tex)
    # 뼈 등줄기(뒤통수에서 뒤로)와 갈비 넷 쌍: 가만히, 죽어도 몸에 붙은 채
    s.rod((0, 0.02, -D / 2), (0, 0.02, -1), length=SPINE, segs=1, thick=0.1, tex="bone", base=False, rag=False)
    for (zz, L) in RIBS:
        for side in (1, -1):
            s.rod((side * 0.04, 0.0, zz), (side * 1.25, -1.0, 0.1), length=L, segs=1, thick=0.06, tex="bone", base=False)
    # 등지느러미(원형 DNA): 꼿꼿한 엔드 막대기 둘 사이 살 막. 덮칠 때만 벌어져 침
    if mode == "":
        a = s.rod((0, 0.05, -0.62), (0, 1.0, -0.55), length=0.8, segs=2, thick=0.1)
        b = s.rod((0, 0.05, -1.1), (0, 1.0, -0.05), length=0.45, segs=2, thick=0.1)
    else:
        a = s.rod((0, 0.05, -0.62), (0.3, 1.0, -0.7), length=0.8, segs=2, thick=0.1, wave=FLARE, poses=4)
        b = s.rod((0, 0.05, -1.1), (-0.3, 1.0, -0.4), length=0.45, segs=2, thick=0.1, wave=FLARE, poses=4)
    s.membrane(a, b, tex="flesh")
    # 뼈 꼬리지느러미 V: 조용할 때 가만, 덮칠 때 좌우로 휘저음(사체에서는 등줄기에 붙은 채: 끝이 모델 틀 가까이라)
    tw = kit.Wave(amp=(26,), lag=0, cycles=2) if mode == "lunge" else None
    for sg in (1, -1):
        s.rod((0, 0.02, -D / 2 - SPINE + 0.06), (0, sg * 0.8, -1.0), length=0.5, segs=1, thick=0.08, tex="bone", wave=tw, toward=(1, 0, 0),
              poses=4, base=False, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=40, shrink=0.75)
    body(k, m.state(""), "")
    body(k, m.state("lunge"), "lunge")


def _breath(seed):
    """'흡': 짧게 들이쉬는 숨(덮치기 직전에만)"""
    d = 0.32
    n = audio.bandpass(audio.noise(d, 460 + seed), 700, 3600)
    t = np.linspace(0, 1, len(n), dtype=np.float32)
    shape = (t ** 1.3) * (t < 0.85) + (t >= 0.85) * np.clip(1 - (t - 0.85) / 0.08, 0, 1)
    return audio.normalize(audio.fade(n * shape, 0.01, 0.03), 0.75)


def sounds(k: kit.Kit):
    # 이 종의 신호: 다른 게이브가 다 시끄러운데 혼자 조용하다가 덮치기 직전에만 짧은 숨소리
    k.sound("breath", "짧은 숨소리", vanilla=[("random/breath", 1.35)], synth=_breath, volume=0.9, attenuation=12)
    k.sound("bite", "콱", vanilla=[("mob/fox/bite1", 0.62), ("mob/fox/bite2", 0.6), ("mob/fox/bite3", 0.64)], volume=1.0, attenuation=16)


def phrases():
    return {"cause_g46": "무리에서 떨어졌다"}
