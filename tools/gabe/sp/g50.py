"""G50 사람 탈 게이브: 방금 죽은 사람 모습(마네킹 엔티티, Java)으로 걸어와 등 뒤에서 머리를 맞대고 들러붙는다.

몸은 마네킹(그 사람 스킨)이고 팩 모델은 그 위에 얹는 조각들이다(마네킹에 태운 디스플레이, 머리 가운데가 모델 가운데):
  gave:gabe/g50       탈을 벗고 튀어 달아나는 얼굴 하나(0.5 블록, 엔드 막대기 둘이 빠르게 파닥). 탈을 쓴 동안은 안 그림
  gave:gabe/g50_mask  덧그림: 그 사람 얼굴 위에 얹는 홍조 하나와 입 옆 작은 점 입(멀리서도 보이는 신호)
  gave:gabe/g50_face  5블록 안 사람에게만 보이는 게이브 얼굴(마네킹 머리를 통째로 덮음)
  gave:gabe/g50_back  등에서 삐져나온 막대기 끝 둘(까딱)
덮개 g50_latch: 들러붙은 사람 화면(양옆에서 밀고 들어온 얼굴 둘).
"""
import numpy as np
from PIL import Image

from gabe import audio, faces, kit

HEAD = 0.47  # 마네킹 머리 폭(블록)
HAT = 0.272  # 모자 층 바로 앞(머리 가운데에서)


def mask_tex():
    """덧그림: 투명 바탕에 홍조 하나와 작은 점 입(화소 부류 유지)"""
    c = faces.Canvas(16)
    c.stamp(faces.BLUSH, 4, 5)
    c.stamp(faces.MOUTH_DOT, 9, 10)
    return c.rgba()


def cover_img(W=480, H=270):
    """들러붙은 사람 화면: 살빛 어둠, 양옆에서 반쯤 밀고 들어온 실눈 얼굴 둘(왼쪽은 내 뒤통수에 붙은 것, 오른쪽은 그 거울)"""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    dx, dy = (xx - W / 2) / (W / 2), (yy - H / 2) / (H / 2)
    r = np.hypot(dx, dy)
    base = np.zeros((H, W, 4), np.float32)
    ridge = np.sin(xx * 0.09 + np.sin(yy * 0.05) * 2.0)
    col = np.array([96, 34, 44], np.float32) + ridge[..., None] * np.array([18, 8, 8], np.float32)
    base[..., :3] = col * (0.75 - 0.35 * np.clip(r - 0.4, 0, 1))[..., None]
    base[..., 3] = 255
    face = Image.fromarray(faces.near(faces.preset("smile")))
    s = int(H * 1.35)
    big = np.asarray(face.resize((s, s), Image.BICUBIC), np.float32)
    oy = (H - s) // 2
    for side in (0, 1):
        # 얼굴의 안쪽 절반만 화면 가장자리에서 가운데 쪽으로 44% 까지
        ox = int(-s * 0.56) if side == 0 else int(W - s * 0.44)
        tile = big if side == 0 else big[:, ::-1]
        x0, x1 = max(0, ox), min(W, ox + s)
        y0, y1 = max(0, oy), min(H, oy + s)
        part = tile[y0 - oy:y1 - oy, x0 - ox:x1 - ox, :3]
        fx = np.linspace(x0, x1 - 1, x1 - x0, dtype=np.float32)
        inner = (x1 - 1 - fx) if side == 0 else (fx - x0)  # 가운데 쪽 끝에서 거리(화소): 그쪽으로 부드럽게 녹아듦
        edge = np.clip(inner / 70.0, 0, 1)[None, :, None]
        base[y0:y1, x0:x1, :3] = base[y0:y1, x0:x1, :3] * (1 - edge) + part * edge * 0.95 + base[y0:y1, x0:x1, :3] * edge * 0.05
    vig = np.clip((np.abs(dy) - 0.6) / 0.4, 0, 1)[..., None]
    base[..., :3] *= (1 - 0.6 * vig)
    return Image.fromarray(np.clip(np.round(base), 0, 255).astype(np.uint8))


def build(k: kit.Kit):
    # 튀어 달아나는 얼굴(엔진 몸): 눈물 맺힌 실눈 얼굴에 짧은 막대기 둘
    m = k.model(poses=6, period=12)
    s = m.state("")
    h = s.head(size=0.5, depth=0.36, face="tears")
    for side, ph in ((1, 0), (-1, 180)):
        s.rod(h.point(side * 0.9, -0.3, -0.6), (side * 1.0, 0.1, -0.5), length=0.6, segs=2, thick=0.075,
              wave=kit.Wave(amp=(26, 22), lag=55, bias=(-4, 4), shape="flap", phase=ph))
    # 덧그림(자세 없음): 얼굴 앞 판 하나
    mk = k.model("g50_mask", poses=1, mut2=False, open=False, torn=False)
    mk.state("").quad((0, 0, HAT), (HEAD, HEAD), "south", k.texture("mask", mask_tex(), classes=True), face=True)
    # 가까이서 보이는 게이브 얼굴: 마네킹 머리와 모자 층을 덮는 상자
    fc = k.model("g50_face", poses=1, mut2=False, torn=False)
    fc.state("").head(size=0.56, depth=0.56, face="smile")
    # 등 막대기 끝 둘
    bk = k.model("g50_back", poses=6, period=24, mut2=False, open=False, torn=False)
    b = bk.state("")
    for side, ph in ((1, 0), (-1, 150)):
        b.rod((side * 0.07, 0.0, 0.0), (side * 0.3, 0.6, -1.0), length=0.34, segs=2, thick=0.05,
              wave=kit.Wave(amp=(10, 16), lag=40, shape="twitch", phase=ph), toward=(0, 1, 0.3))
    k.cover("g50_latch", cover_img())
    k.icon(icon_img())


def icon_img():
    """HUD 아이콘: 게이브 얼굴에 홍조와 점 입이 하나 더 얹힌 것(입 옆 작은 입)"""
    c = faces.draw("smile")
    c.stamp(faces.BLUSH, 21, 15)
    c.stamp(faces.MOUTH_DOT, 24, 20)
    return Image.fromarray(c.rgba()).resize((64, 64), Image.NEAREST)


def mixed(seed=0):
    """맞으면: 친구 목소리 대신 게이브 웃음과 울음이 섞여 나옴"""
    g = audio.giggle(seed=500 + seed, count=4, base=560 + 20 * seed, rate=10)
    d = len(g) / audio.SR
    f = 400 + 25 * seed
    v = audio.tone(f, d, "saw", vib=0.05, vib_rate=8, glide=f * 0.6)
    cry = audio.env(audio.mix(audio.bandpass(v, 500, 900), audio.gain(audio.bandpass(v, 1000, 1500), 0.5)), 0.02, 0.1, 0.7, 0.2)
    return audio.normalize(audio.fade(audio.mix(audio.gain(g, 0.7), audio.gain(cry, 0.8)), 0.005, 0.08), 0.85)


def inside(seed=0):
    """듣기(웅크리기): 속에서 새는 웃음 섞인 울음(작게)"""
    return audio.gain(audio.lowpass(mixed(seed + 3), 1800), 0.8)


def press(seed=0):
    """머리를 맞댐: 젖은 살이 눌어붙는 소리 + 낮은 웅웅"""
    a = audio.squish(seed=505 + seed, dur=0.45)
    hum = audio.env(audio.tone(92 + 4 * seed, 0.6, "tri", vib=0.03, vib_rate=5), 0.05, 0.1, 0.7, 0.25) * 0.5
    return audio.normalize(audio.fade(audio.mix(a, hum), 0.005, 0.1), 0.85)


def sounds(k: kit.Kit):
    k.sound("hurt", "웃음 섞인 울음", synth=mixed, volume=0.9, attenuation=16)
    k.sound("inside", "속에서 새는 웃음", synth=inside, volume=0.6, attenuation=8)
    # 쓰러진 자리에서 일어남: 이 종의 신호(전장에서 일어난 사람은 가짜)
    k.sound("rise", "쓰러진 자리에서 일어남", vanilla=[("item/armor/equip_leather1", 0.6), ("item/armor/equip_leather3", 0.55)],
            synth=lambda i: audio.wet_flap(seed=507 + i, dur=0.4, low=120), volume=1.0, attenuation=32)
    k.sound("latch", "머리를 맞댐", synth=press, volume=1.0, attenuation=24)
    k.sound("collapse", "몸이 무너짐", vanilla=[("mob/slime/big1", 0.55), ("mob/slime/big3", 0.5)], volume=1.0, attenuation=24)


def phrases():
    return {"cause_g50": "죽은 동료가 들러붙었다"}
