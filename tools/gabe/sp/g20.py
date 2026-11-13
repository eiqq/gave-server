"""G20 얼굴 곡예 게이브: 얼굴 없이 X자로 엇갈린 엔드 막대기 둘이 몸이고, 그 위 공중에서 똑같은 얼굴 셋이 세로 바퀴를 돈다(저글링).
막대기 위 끝이 손처럼 번갈아 튕겨 받는다(20틱에 한 번씩, 좌우 반 바퀴 어긋남: 얼굴이 아래를 지날 때마다 한 손).

모델 gave:gabe/g20: X 막대기 몸(기본 상태 "" 하나). 얼굴은 부품 디스플레이 셋(Java 가 바퀴 자리로 옮김).
모델 gave:gabe/g20_face: 저글링 얼굴 하나(실눈 웃음, 진짜는 표적을 찍으면 눈을 뜸). 던진 가짜 얼굴도 이 모델.
모델 gave:gabe/g20_web: 가짜 얼굴이 터진 자리 바닥 거미줄(판 하나, Java 가 연출 디스플레이로 퍼뜨림).
덮개 g20_smear: 가짜를 쏜 사람 화면의 분홍 잔상(1초).
"""
import numpy as np
from PIL import Image, ImageFilter

from gabe import audio, kit


def smear_img(W=480, H=270, seed=5):
    """분홍 잔상: 가운데 번진 분홍 얼룩 몇 개(반투명), 가장자리는 비움"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    a = np.zeros((H, W), np.float32)
    for _ in range(7):
        cx, cy = W / 2 + rng.randn() * 70, H / 2 + rng.randn() * 40
        rx, ry = 40 + rng.rand() * 70, 30 + rng.rand() * 50
        a = np.maximum(a, np.clip(1.2 - ((xx - cx) / rx) ** 2 - ((yy - cy) / ry) ** 2, 0, 1))
    # 웃는 입 모양 잔상(가로 호)
    arc = np.exp(-((yy - (H * 0.62 - 0.0012 * (xx - W / 2) ** 2)) / 6.0) ** 2) * (np.abs(xx - W / 2) < 90)
    a = np.clip(a * 0.75 + arc * 0.5, 0, 1)
    im = np.zeros((H, W, 4), np.uint8)
    im[..., 0] = 250
    im[..., 1] = 120
    im[..., 2] = 160
    im[..., 3] = np.clip(a * 190, 0, 255).astype(np.uint8)
    return Image.fromarray(im).filter(ImageFilter.GaussianBlur(3))


def build(k: kit.Kit):
    # X 막대기 몸: 몸 가운데(0, 0, 0)에서 엇갈림. 위 끝이 손(바퀴 아래 양옆), 아래 끝은 바닥 쪽
    m = k.model(poses=8, period=20, mut2=dict(root=lambda side: (side * 0.1, -0.15, -0.05), dir=(0.9, 0.2, -0.6), length=0.7))
    s = m.state("")
    for side in (1, -1):
        root = (-side * 0.62, -1.05, 0.0)
        d = (side * 1.24, 1.6, 0.0)
        # 위 마디가 손처럼 위로 튕김(flap: 빨리 치고 천천히 내림), 좌우 반 바퀴 어긋나 번갈아
        w = kit.Wave(amp=(2, 6, 26), lag=40, bias=(0, -4, -10), shape="flap", phase=0 if side > 0 else 180)
        s.rod(root, d, length=2.05, segs=3, thick=0.12, wave=w, toward=(-side * 0.5, 0.4, 0.0))
    fm = k.model("g20_face", poses=1, mut2=False)
    fm.state("").head(size=0.46, depth=0.4, face="smile")
    wm = k.model("g20_web", poses=1, mut2=False, open=False, torn=False)
    wm.state("").quad((0, 0, 0), (1.0, 1.0), "up", "web")
    k.cover("g20_smear", smear_img())
    k.icon(model="g20_face")


def tok(seed=0, dur=0.09):
    """'톡': 막대기 끝에 얼굴이 가볍게 튕기는 소리"""
    x = audio.env(audio.tone(900 + seed * 37, dur, "tri", glide=520), 0.001, 0.02, 0.2, 0.05)
    n = audio.env(audio.highpass(audio.noise(dur, seed), 1500), 0.0005, 0.01, 0.1, 0.03) * 0.4
    return audio.normalize(audio.fade(audio.mix(x, n), 0.001, 0.01), 0.7)


def chalbak(seed=0, dur=0.3):
    """'찰박': 진짜 얼굴을 받을 때만 나는 젖은 소리"""
    return audio.squish(seed=seed, dur=dur)


def pop(seed=0):
    """웃으며 터짐: 짧은 킥킥 뒤 퍽(이 종의 몇 안 되는 웃음)"""
    g = audio.giggle(seed=seed, count=3, base=600, rate=11)
    b = audio.thump(seed=seed + 1, dur=0.25, freq=140)
    return audio.normalize(audio.cat(g, b), 0.85)


def sounds(k: kit.Kit):
    # 이 종의 신호: 공중 얼굴이 아래를 지날 때마다 '톡 톡 톡', 진짜만 젖은 '찰박'
    k.sound("tok", "톡", synth=lambda i: tok(seed=80 + i), volume=0.6, attenuation=20)
    k.sound("chalbak", "찰박", synth=lambda i: chalbak(seed=84 + i), volume=0.7, attenuation=20)
    k.sound("toss", "휙 던짐", vanilla=[("random/bow", 0.7), ("random/bow", 0.8)], volume=0.7, attenuation=24)
    k.sound("pop", "킥킥 퍽", synth=lambda i: pop(seed=88 + i), attenuation=24)


def phrases():
    return {"cause_g20": "던진 얼굴이 터졌다"}
