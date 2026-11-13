"""G53 점으로 짠 얼굴 떼: 손바닥만 한 얼굴(0.36 블록) 24개가 하늘에서 큰 _Gave 얼굴의 픽셀(실눈 획, 점 입, 홍조, 머리 덩어리, 귀 줄)을 짠다.

모델 gave:gabe/g53(큰 덩어리를 한 모델에 담으려고 1/4 로 줄여 구움, 엔진이 Look.scale 로 되돌림):
  "a", "b"  마리 하나(얼굴 0.36 + 작은 엔드 막대기 둘이 반짝이듯 파닥). 기본 "a"
  "lod"     15블록 밖에서 보는 큰 얼굴 하나(디스플레이 1개): 픽셀 24개를 구워 넣음. 픽셀마다 부위 dNN(그 마리가 죽으면 Java 가 켜서 숨김),
            눈 픽셀 10개는 실눈 획 자리(!open)와 눈 뜬 고리 자리(open, 찍었다) 둘을 굽고 셰이더 조건으로 바꿈.
            얼굴 양옆에 큰 엔드 막대기 둘이 귀 줄처럼 펄럭(원형 DNA).
픽셀 자리표 PIXELS 는 Java G53.PIXELS 와 글자까지 같아야 한다(얼굴 평면 가로 u, 세로 v 블록).
"""
import math

from PIL import Image

from gabe import audio, faces, kit

# (u, v): 0~4 왼눈 실눈 획, 5~9 오른눈, 10~11 점 입, 12~15 홍조, 16~19 머리 덩어리, 20~23 귀 줄
PIXELS = [(-2.2, 0.05), (-1.85, 0.4), (-1.5, 0.55), (-1.15, 0.4), (-0.8, 0.05),
          (0.8, 0.05), (1.15, 0.4), (1.5, 0.55), (1.85, 0.4), (2.2, 0.05),
          (-0.2, -1.7), (0.2, -1.7),
          (-0.75, -1.0), (-0.25, -1.0), (0.25, -1.0), (0.75, -1.0),
          (-2.4, 1.75), (-1.9, 1.95), (1.9, 1.95), (2.4, 1.75),
          (-2.95, -0.2), (-2.95, -0.7), (2.95, -0.2), (2.95, -0.7)]
EYE_C = [(-1.5, 0.25), (1.5, 0.25)]
EYE_R = 0.5
FACES = ("smile_a", "smile", "smile_c")
SIZE = 0.36


def open_spot(i):
    """눈 픽셀 i(0..9)의 눈 뜬 자리: 눈 가운데 둘레 고리"""
    cx, cy = EYE_C[i // 5]
    a = math.radians(90 + 72 * (i % 5))
    return (cx + math.cos(a) * EYE_R, cy + math.sin(a) * EYE_R)


def pixel(s, k, u, v, face, show):
    hs = SIZE / 2
    tex = {"south": k.face_tex(face), "north": k.skin_tex("back"), "east": k.skin_tex("left"), "west": k.skin_tex("right"),
           "up": k.skin_tex("top"), "down": k.skin_tex("bottom")}
    with s.when(*show):
        s.box((u - hs, v - hs, -hs * 0.7), (u + hs, v + hs, hs * 0.7), tex, uv=[0, 0, 16, 16], face=True)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, shrink=0.25, mut2=False, torn=False, open=False)
    for name, face, ph in (("a", "smile_a", 0), ("b", "smile_c", 90)):
        s = m.state(name)
        h = s.head(size=SIZE, depth=SIZE * 0.7, face=face, near=False)
        for side in (1, -1):
            s.rod(h.point(side * 1.0, 0.1, -0.4), (side * 1.0, 0.35, -0.5), length=0.28, segs=1, thick=0.05,
                  wave=kit.Wave(amp=34, phase=ph + (0 if side > 0 else 180)), poses=4)
    m.default = "a"
    lod = m.state("lod", period=24)
    for i, (u, v) in enumerate(PIXELS):
        part = "!on:d%02d" % i
        if i < 10:
            pixel(lod, k, u, v, FACES[i % 3], ("!open", part))
            ou, ov = open_spot(i)
            pixel(lod, k, ou, ov, FACES[i % 3], ("open", part))
        else:
            pixel(lod, k, u, v, FACES[i % 3], (part,))
    # 귀 줄: 얼굴 양옆에서 뒤로 뻗어 펄럭이는 큰 엔드 막대기 둘
    for side in (1, -1):
        lod.rod((side * 3.3, 0.9, -0.1), (side * 1.0, 0.2, -0.35), length=1.7, segs=3, thick=0.15,
                wave=kit.Wave(amp=(16, 14, 12), lag=50, bias=(-4, 2, 4), shape="flap", phase=0 if side > 0 else 180), poses=4)
    k.icon(icon_img())


def icon_img():
    """HUD 아이콘: 픽셀 자리마다 작은 얼굴 하나(64x64 에서 읽히게 크게)"""
    out = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    for i, (u, v) in enumerate(PIXELS):
        tile = Image.fromarray(faces.far(FACES[i % 3])).resize((8, 8), Image.LANCZOS)
        out.alpha_composite(tile, (int(round(32 + u * 9.4 - 4)), int(round(34 - v * 9.4 - 4))))
    return out


def sounds(k: kit.Kit):
    # 겹친 키득거림: 눈 픽셀이 자리를 바꿀 때(찍었다). 떼 종이라 웃음 허용, 하늘에서 넓게
    k.sound("titter", "하늘에서 키득", synth=lambda i: audio.giggle(seed=530 + i, count=8 + i, base=610 + 30 * i, rate=11), volume=0.9,
            attenuation=48)
    # 몰려오는 날갯짓(쏟아질 때)
    k.sound("rain", "쏟아지는 날갯짓", vanilla=[("mob/phantom/flap1", 1.5), ("mob/phantom/flap3", 1.6), ("mob/phantom/flap5", 1.7)],
            synth=lambda i: audio.chitter(seed=534 + i, dur=0.7, rate=26, base=1500), volume=0.8, attenuation=48)
    k.sound("flutter", "파닥", vanilla=[("mob/phantom/flap4", 1.8), ("mob/phantom/flap6", 1.9)], volume=0.35, attenuation=24)


def phrases():
    return {"cause_g53": "얼굴 비를 맞았다"}
