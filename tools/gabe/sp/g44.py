"""G44 꾸러미 게이브: 팀 보급 상자(모드 탄약통 모델 gave:ammo_box)로 위장해 빙글 돈다. 뚜껑 이음매에 손톱만 한 실눈 얼굴들이 경첩처럼 박혀 있고,
엔드 막대기 둘이 경첩 축처럼 양옆에서 까딱인다. 열리면 그 얼굴들이 이빨이 되어 맞물리고 안쪽은 분홍 살에 짧은 막대기들이 꼼지락거린다.

- gave:gabe/g44: 상태 ""(위장: 이음매 얼굴과 경첩 막대기만. 상자 몸은 Java 가 모드 탄약통 모델을 부품으로 겹침. 부위 "own" = 모드 모델이 없을 때
  이 모델이 상자 몸도 그림), "open"(열린 입: 뚜껑이 위아래로 덥석거림), "full"(사람을 삼킴: 입을 다물고 불룩).
- 상자 겉 텍스처는 모드 탄약통 텍스처에서 잘라 쓴다(같은 팩 안 파생, 없으면 같은 빛깔 민무늬).
- 덮개 g44_maw: 갇힌 사람 화면(분홍 살 주름, 가장자리에 맞물린 작은 얼굴 이빨).
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image

from gabe import audio, faces, kit

ROOT = Path(__file__).resolve().parents[3]
MOD_TEX = ROOT / "build/props/pack_layer/assets/gave/textures/item/ammo_box/nato_556_box.png"
# 상자 치수(블록, 모델 가운데 기준): 모드 탄약통과 같은 크기(가로 0.82, 높이 0.5 + 뚜껑, 깊이 0.38)
HX, HZ = 0.41, 0.19
Y0, SEAM, LID = -0.33, 0.17, 0.045
TEETH_X = (-0.3, -0.15, 0.0, 0.15, 0.3)


def can_tex():
    """(옆면 글씨 있는 그림, 민무늬) 모드 탄약통 텍스처에서 자른 것. 없으면 같은 빛깔로 그린다"""
    if MOD_TEX.is_file():
        im = Image.open(MOD_TEX).convert("RGBA")
        side = im.crop((0, 2, 38, 27)).resize((32, 16), Image.NEAREST)
        flat = im.crop((40, 4, 56, 20))
        a, b = np.array(side), np.array(flat)
    else:
        a = np.zeros((16, 32, 4), np.uint8)
        a[...] = (168, 152, 120, 255)
        a[3:13, 4:28, :3] = (150, 136, 108)
        b = np.zeros((16, 16, 4), np.uint8)
        b[...] = (168, 152, 120, 255)
    a[..., 3] = 255
    b[..., 3] = 255
    return a, b


def plain(rgb):
    im = np.zeros((4, 4, 4), np.uint8)
    im[...] = (*rgb, 255)
    im[0, :, :3] = [min(255, c + 14) for c in rgb]
    return im


def maw_cover(W=480, H=270):
    """갇힌 사람 화면: 사방에서 조여 오는 분홍 살 주름, 위아래 가장자리에 맞물린 작은 실눈 얼굴 이빨 줄"""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    dx, dy = (xx - W / 2) / (W / 2), (yy - H / 2) / (H / 2)
    r = np.hypot(dx * 0.9, dy)
    ridge = np.sin(dx * 16 + np.sin(dy * 7.0) * 2.4) * 0.6 + np.sin(r * 22.0) * 0.4
    t = (ridge * 0.5 + 0.5)[..., None]
    col = np.array([104, 30, 44], np.float32) * (1 - t) + np.array([230, 132, 140], np.float32) * t
    col *= (0.5 + 0.5 * np.clip(1.15 - r, 0, 1))[..., None]
    base = np.concatenate([col, np.full((H, W, 1), 255, np.float32)], -1)
    img = Image.fromarray(np.clip(np.round(base), 0, 255).astype(np.uint8))
    tooth = Image.fromarray(faces.near(faces.preset("smile"))).resize((54, 54), Image.BICUBIC)
    flipped = tooth.transpose(Image.FLIP_TOP_BOTTOM)
    for i in range(9):
        x = 6 + i * 53
        img.alpha_composite(flipped, (x, -18))
        img.alpha_composite(tooth, (x - 26, H - 36))
    arr = np.asarray(img, np.float32).copy()
    vig = np.clip((r - 0.75) / 0.5, 0, 1)[..., None]
    arr[..., :3] *= (1 - 0.6 * vig)
    arr[..., 3] = 255
    return Image.fromarray(np.clip(np.round(arr), 0, 255).astype(np.uint8))


def hinge_rods(s, cycles=1):
    """경첩 축 막대기 둘: 뚜껑 이음매 양 끝에서 옆으로, 까딱임(대부분 가만히 있다가 움찔)"""
    for side in (1, -1):
        s.rod((side * (HX + 0.02), SEAM - 0.02, -HZ + 0.03), (side * 1.0, 0.25, -0.1), length=0.46, segs=2, thick=0.07,
              wave=kit.Wave(amp=(16, 22), lag=40, shape="twitch", phase=0 if side > 0 else 150, cycles=cycles), poses=4)


def seam_faces(s, k, z_front=True, y=SEAM, rot=None, origin=None, size=0.11):
    """이음매에 박힌 손톱만 한 실눈 얼굴 줄(앞, 뒤)"""
    f = k.face_tex("smile")
    kw = dict(rot=rot, origin=origin) if rot else {}
    for x in TEETH_X:
        s.box((x - size / 2, y - size / 2, HZ + 0.012), (x + size / 2, y + size / 2, HZ + 0.012), f, uv=[0, 0, 16, 16], faces=("south",),
              face=True, **kw)
        if not rot:
            s.box((x - size / 2, y - size / 2, -HZ - 0.012), (x + size / 2, y + size / 2, -HZ - 0.012), f, uv=[0, 0, 16, 16],
                  faces=("north",), face=True)


def jaw(s, k, side_tex, flat_tex, flesh_top=True):
    """아래턱(상자 몸): 옆은 탄약통 겉, 윗면은 분홍 살"""
    tex = {"north": side_tex, "south": side_tex, "east": flat_tex, "west": flat_tex, "down": flat_tex, "up": "flesh" if flesh_top else flat_tex}
    s.box((-HX, Y0, -HZ), (HX, SEAM, HZ), tex, uv=[0, 0, 16, 16])


def lid(s, k, flat_tex, deg, teeth=True):
    """뚜껑(윗턱): 뒤 모서리를 축으로 deg 도 열림. 밑면은 분홍 살, 앞 가장자리에 얼굴 이빨"""
    o = (0, SEAM, -HZ)
    tex = {"north": flat_tex, "south": flat_tex, "east": flat_tex, "west": flat_tex, "up": flat_tex, "down": "flesh"}
    s.box((-HX - 0.02, SEAM, -HZ - 0.02), (HX + 0.02, SEAM + LID, HZ + 0.02), tex, uv=[0, 0, 16, 4], rot=(-deg, 0, 0), origin=o)
    if teeth:
        f = k.face_tex("smile")
        size = 0.11
        for x in TEETH_X:
            # 윗니: 뚜껑 앞 가장자리 밑에 거꾸로 매달린 얼굴
            s.box((x + 0.075 - size / 2, SEAM - size, HZ + 0.014), (x + 0.075 + size / 2, SEAM, HZ + 0.014), f, uv=[16, 16, 0, 0],
                  faces=("south",), face=True, rot=(-deg, 0, 0), origin=o)


def build(k: kit.Kit):
    side_arr, flat_arr = can_tex()
    side_tex, flat_tex = k.texture("can_side", side_arr), k.texture("can_flat", flat_arr)
    leg = k.texture("leg", plain((232, 224, 240)))
    m = k.model(poses=4, period=20, mut2=False, open=False, torn=False)

    # 위장: 이음매 얼굴 + 경첩 막대기(+ 모드 모델이 없을 때만 상자 몸)
    s = m.state("")
    seam_faces(s, k)
    hinge_rods(s)
    with s.when("on:own"):
        jaw(s, k, side_tex, flat_tex, flesh_top=False)
        lid(s, k, flat_tex, 0, teeth=False)

    # 열린 입: 뚜껑이 덥석거림(자세 4: 20, 48, 64, 40 도), 안쪽 살과 꼼지락거리는 짧은 막대기
    s = m.state("open")
    jaw(s, k, side_tex, flat_tex)
    hinge_rods(s, cycles=2)
    f = k.face_tex("smile")
    for x in TEETH_X:  # 아랫니
        s.box((x - 0.055, SEAM, HZ + 0.012), (x + 0.055, SEAM + 0.11, HZ + 0.012), f, uv=[0, 0, 16, 16], faces=("south",), face=True)
    for fr, deg in zip(s.frames(4), (20, 48, 64, 40)):
        with fr:
            lid(s, k, flat_tex, deg)
    for i in range(8):
        x = -0.32 + (i % 4) * 0.21 + (0.05 if i >= 4 else 0)
        z = -0.08 if i < 4 else 0.08
        s.band((x, SEAM, z), (0.15 * math.sin(i * 1.7), 1.0, 0.1 * math.cos(i * 2.3)), length=0.2, width=0.05, segs=1, thick=0.012, tex=leg,
               toward=(1, 0, 0) if i % 2 else (0, 0, 1), face=False, poses=4, wave=kit.Wave(amp=30, phase=(i * 97) % 360, cycles=2))

    # 삼킴: 입을 다물고 불룩(이빨이 맞물림), 틈으로 살이 비어져 나옴
    s = m.state("full")
    jaw(s, k, side_tex, flat_tex)
    hinge_rods(s, cycles=2)
    for x in TEETH_X:
        s.box((x - 0.055, SEAM, HZ + 0.012), (x + 0.055, SEAM + 0.11, HZ + 0.012), f, uv=[0, 0, 16, 16], faces=("south",), face=True)
    s.box((-HX + 0.03, SEAM, -HZ + 0.03), (HX - 0.03, SEAM + 0.1, HZ - 0.01), "flesh", uv=[0, 0, 16, 4], faces=("north", "south", "east", "west"),
          face=True)
    for fr, deg in zip(s.frames(4), (12, 16, 12, 9)):
        with fr:
            lid(s, k, flat_tex, deg)

    k.cover("g44_maw", maw_cover())
    k.icon(model="g44", state="open")


def _grind(seed):
    """'빠드득': 이음매의 작은 이들이 갈리는 소리"""
    rng = np.random.RandomState(480 + seed)
    d = 0.5
    out = audio.silence(d)
    t = 0.0
    while t < d - 0.04:
        c = audio.env(audio.bandpass(audio.noise(0.03, 481 + seed + int(t * 300)), 900, 3200), 0.001, 0.008, 0.3, 0.015)
        k2 = audio.env(audio.tone(240 + rng.rand() * 80, 0.03, "saw"), 0.001, 0.008, 0.3, 0.015) * 0.5
        out = audio.mix(out, audio.at(audio.mix(c, k2), t, d))
        t += 0.045 + rng.rand() * 0.02
    return audio.normalize(audio.fade(out, 0.005, 0.05), 0.7)


def _scream(seed):
    """'살려줘' 비명 흉내(합성한 목소리 같은 세 마디: 높게 치솟다 떨며 꺼짐). 정해 둔 소리이고 실제 사람 목소리가 아니다"""
    f0 = 520 + 30 * seed
    parts = []
    spec = (((950, 1.0), (1500, 0.6), (2900, 0.3)), ((560, 1.0), (1900, 0.7), (2700, 0.3)), ((480, 1.0), (980, 0.7), (2500, 0.25)))
    durs = (0.2, 0.2, 0.5)
    glides = ((1.0, 1.12), (1.12, 1.2), (1.2, 0.8))
    t = 0.0
    total = 1.1
    for form, du, (g0, g1) in zip(spec, durs, glides):
        src = audio.tone(f0 * g0, du, "saw", vib=0.05, vib_rate=12, glide=f0 * g1) * 0.6 + audio.noise(du, 485 + seed) * 0.1
        v = np.zeros_like(src)
        for fc, g in form:
            v += audio.bandpass(src, fc * 0.8, fc * 1.25) * g
        parts.append(audio.at(audio.env(v, 0.015, 0.03, 0.8, 0.08), t, total))
        t += du + 0.03
    out = audio.mix(*parts)
    out = np.tanh(out * 2.2)
    return audio.normalize(audio.fade(out, 0.005, 0.2), 0.85)


def _snicker(seed):
    """듣기(웅크리기)로 들리는 속 소리: 상자 속 낮은 킥킥(흉내 종은 웃음이 섞여 정체가 드러남)"""
    return audio.gain(audio.lowpass(audio.giggle(seed=490 + seed, count=4, base=300, rate=8), 1400), 0.9)


def sounds(k: kit.Kit):
    # 이 종의 신호: 곁에서 이음매가 빠드득 이를 간다
    k.sound("grind", "빠드득 이 가는 소리", synth=_grind, volume=0.8, attenuation=8)
    k.sound("snap", "덥석", vanilla=[("block/chest/close1", 0.6), ("block/chest/close2", 0.62)], synth=lambda i: audio.squish(seed=495 + i),
            attenuation=24)
    k.sound("thud", "쿵 뛰는 소리", synth=lambda i: audio.thump(seed=498 + i, dur=0.26, freq=64), volume=1.0, attenuation=24)
    k.sound("scream", "살려줘 비명", synth=_scream, volume=1.0, attenuation=48)
    k.sound("spit", "퉤 뱉음", vanilla=[("mob/slime/big3", 0.9)], synth=lambda i: audio.squish(seed=501 + i, dur=0.22), attenuation=24)
    k.sound("snicker", "상자 속 킥킥", synth=_snicker, volume=0.7, attenuation=8)


def phrases():
    return {"cause_g44": "꾸러미에 먹혔다", "notice_g44_trap": "꾸러미가 삼켰다"}
