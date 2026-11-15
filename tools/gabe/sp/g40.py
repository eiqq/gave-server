"""G40 짐승 탄 게이브: 0.4 블록 얼굴이 바닐라 돼지, 양, 소 머리 자리에 비뚤게 덮이고 엔드 막대기 둘이 고삐처럼 등에 꽂힌다.
모이면 얼굴 10개가 사람 키 인형이 된다(팔다리는 얼굴을 꼬치처럼 꿴 막대기, 팔꿈치가 둘, 가슴 얼굴 하나만 홍조가 짙고 두근거림).

짐승 몸은 바닐라 26.3 클라 jar 의 엔티티 텍스처(돼지, 소, 양)를 면마다 잘라 상자에 입힌다(아이템 모델은 엔티티 텍스처를 직접 못 가리킴).

- gave:gabe/g40(shrink 0.5, 떼 마리 하나 = 디스플레이 하나): 상태 "pig", "sheep", "cow"(게이브가 탄 짐승, 다리 네 자세 달리기),
  "pig_bare", "sheep_bare", "cow_bare"(버려진 짐승), "lod"(15블록 밖 무리 덩어리: 다섯 마리 한 장, 다리 두 자세). 모델 가운데 = 짐승 가운데 바닥 위 0.5.
- gave:gabe/g40_doll: 사람 키 인형. 상태 "walk"(기본, 사람 걸음), "swing"(꿴 팔 휘두름), "heart"(다시 쌓일 때 먼저 내려앉는 심장 얼굴).
- gave:gabe/g40_rider: 낱개 얼굴(조립 회오리, 흩어진 조각): 작은 얼굴 + 막대기 둘.
"""
import io
import math
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

from gabe import audio, faces, kit
from mcjar import client_jar  # 26.3 클라 jar 를 공식 런처 자리 말고도 찾는다(GV_CLIENT_JAR, fabric-loom 캐시. 공식 자리에 있으면 예전과 같음)

JAR = client_jar()  # tools/mcjar.py: 공식 런처로 26.3 을 켠 적 없는 PC 에서도 돌게
PX = 1 / 16
# 무리 대형(몸 기준 x 옆, z 앞)과 마리마다 짐승. Java G40.HOMES, KINDS 와 같게
HOMES = [(0.0, 0.9), (-1.15, 0.2), (1.15, 0.1), (-0.55, -1.0), (0.65, -1.1)]
KINDS = ["pig", "sheep", "cow", "pig", "sheep"]
_cache = {}


def vanilla(name):
    if name not in _cache:
        if not JAR.is_file():
            raise FileNotFoundError(f"바닐라 26.3 jar 없음: {JAR}")
        with zipfile.ZipFile(JAR) as z:
            _cache[name] = Image.open(io.BytesIO(z.read(f"assets/minecraft/textures/entity/{name}.png"))).convert("RGBA")
    return _cache[name]


def vbox(img, u, v, w, h, d):
    """바닐라 상자 UV(texOffs u, v, 크기 w h d) → 면 그림. front = 모델 앞(-Z), back, top, bottom, right(-X), left(+X)"""
    c = lambda x, y, ww, hh: img.crop((x, y, x + ww, y + hh))
    return {"top": c(u + d, v, w, d), "bottom": c(u + d + w, v, w, d), "right": c(u, v + d, d, h), "front": c(u + d, v + d, w, h),
            "left": c(u + d + w, v + d, d, h), "back": c(u + d + w + d, v + d, w, h)}


def opaque(im):
    a = np.array(im, np.uint8)
    a[..., 3] = np.where(a[..., 3] >= 128, 255, 0)
    return a


class Beast:
    """짐승 하나의 치수(px, 바닥 0, 앞 +Z)와 면 텍스처"""

    def __init__(self, k, kind):
        self.k, self.kind = k, kind
        t = lambda name, im: k.texture(f"{kind}_{name}", opaque(im))
        if kind == "pig":
            img = vanilla("pig/pig_temperate")
            body, head, leg = vbox(img, 28, 8, 10, 16, 8), vbox(img, 0, 0, 8, 8, 8), vbox(img, 0, 16, 4, 6, 4)
            snout = vbox(img, 16, 16, 4, 3, 1)
            self.body = (-5, 6, -8, 5, 14, 8)
            self.head = (-4, 8, 6, 4, 16, 14)
            self.legs = (3, 6, 5, -7)  # 옆 가운데, 높이, 앞 다리 z, 뒷다리 z
            self.extra = [((-2, 9, 14, 2, 12, 15), {n: t("snout", snout["front"]) for n in ("south", "up", "down", "east", "west")})]
            self.mask_y, self.mask_z = 12.0, 14.0
        elif kind == "cow":
            img = vanilla("cow/cow_temperate")
            body, head, leg = vbox(img, 18, 4, 12, 18, 10), vbox(img, 0, 0, 8, 8, 6), vbox(img, 0, 16, 4, 12, 4)
            horn = vbox(img, 22, 0, 1, 3, 1)
            self.body = (-6, 12, -10, 6, 22, 8)
            self.head = (-4, 16, 8, 4, 24, 14)
            self.legs = (4, 12, 6, -7)
            ht = t("horn", horn["front"])
            self.extra = [((sx * 4.5 - 0.5, 22, 11, sx * 4.5 + 0.5, 25, 12), {n: ht for n in ("south", "north", "east", "west", "up")}) for sx in (1, -1)]
            self.mask_y, self.mask_z = 20.0, 14.0
        else:
            img, wool = vanilla("sheep/sheep"), vanilla("sheep/sheep_wool")
            body, head, leg = vbox(wool, 28, 8, 8, 16, 6), vbox(img, 0, 0, 6, 6, 8), vbox(img, 0, 16, 4, 12, 4)
            self.body = (-5.75, 10.25, -9.75, 5.75, 19.75, 9.75)
            self.head = (-3, 16, 6, 3, 22, 14)
            self.legs = (3, 12, 5, -7)
            self.extra = []
            self.mask_y, self.mask_z = 19.0, 14.0
        # 몸통은 바닐라 모델에서 X 로 90도 누운 상자: 등 = back, 배 = front, 앞 끝 = top, 뒤 끝 = bottom, 옆 = right/left 를 90도 돌린 것
        self.body_tex = {"up": t("b_up", body["back"].rotate(180)), "down": t("b_down", body["front"]), "south": t("b_front", body["top"]),
                         "north": t("b_rear", body["bottom"]), "east": t("b_left", body["left"].rotate(90, expand=True)),
                         "west": t("b_right", body["right"].rotate(-90, expand=True))}
        self.head_tex = {"south": t("h_front", head["front"]), "north": t("h_back", head["back"]), "up": t("h_top", head["top"]),
                         "down": t("h_bottom", head["bottom"]), "east": t("h_left", head["left"]), "west": t("h_right", head["right"])}
        lf, ls = t("l_front", leg["front"]), t("l_side", leg["right"])
        self.leg_tex = {"south": lf, "north": lf, "east": ls, "west": ls, "down": t("l_bottom", leg["bottom"])}


def b(p, ox=0.0, oz=0.0):
    """px(바닥 0) → 블록(모델 가운데 = 바닥 위 0.5), 자리 옮김"""
    return (p[0] * PX + ox, p[1] * PX - 0.5, p[2] * PX + oz)


def draw_beast(s, beast, ox=0.0, oz=0.0, rider=True, leg_poses=4, leg_faces=None, mask_near=True):
    bx = beast.body
    s.box(b(bx[:3], ox, oz), b(bx[3:], ox, oz), beast.body_tex, uv=[0, 0, 16, 16])
    hx = beast.head
    s.box(b(hx[:3], ox, oz), b(hx[3:], ox, oz), beast.head_tex, uv=[0, 0, 16, 16])
    for box, tex in beast.extra:
        s.box(b(box[:3], ox, oz), b(box[3:], ox, oz), tex, uv=[0, 0, 16, 16], faces=tuple(tex))
    lx, lh, zf, zr = beast.legs
    names = leg_faces or ("south", "north", "east", "west", "down")
    tex = {n: beast.leg_tex[n] for n in names}
    for fr in s.frames(leg_poses):
        with fr:
            a = 30 * math.sin(2 * math.pi * fr.t)
            for sx, z, sign in ((1, zf, 1), (-1, zf, -1), (1, zr, -1), (-1, zr, 1)):
                frm = b((sx * lx - 2, 0, z - 2), ox, oz)
                to = b((sx * lx + 2, lh, z + 2), ox, oz)
                hinge = b((sx * lx, lh, z), ox, oz)
                s.box(frm, to, tex, uv=[0, 0, 16, 16], faces=names, rot=(a * sign, 0, 0), origin=hinge)
    if not rider:
        return
    # 게이브: 짐승 얼굴 자리에 비뚤게 덮인 0.4 블록 얼굴, 고삐처럼 등에 꽂힌 막대기 둘
    my, mz = beast.mask_y * PX - 0.5, beast.mask_z * PX + 0.06
    h = s.head(size=0.4, depth=0.1, at=(ox, my, oz + mz), face="smile", rot=(0, 0, 9), near=mask_near, torn=False)
    if leg_poses >= 4:
        top = bx[4] * PX - 0.5
        for side in (1, -1):
            root = (ox + side * 0.17, my + 0.1, oz + mz - 0.06)
            # 뒤 위로 나갔다가 꺾여 등에 꽂힘(끝이 까딱임)
            s.rod(root, (side * 0.12, 0.42, -1.0), length=(mz + 0.25) * 1.05 + 0.25, segs=2, thick=0.07,
                  wave=kit.Wave(amp=(8, 10), lag=40, bias=(0, -42), phase=0 if side > 0 else 90), toward=(0, 1, 0), poses=4, base=False)


def doll(s, k, mode):
    """사람 키 인형(가운데 = 바닥 위 0.95). mode: walk | swing"""
    swing = mode == "swing"
    kw = dict(near=False, open=False, torn=False)
    s.head(size=0.4, depth=0.3, at=(0, 0.72, 0), face="smile", near=False, torn=False)
    s.head(size=0.34, depth=0.26, at=(0, -0.02, 0), face="smile_a", rot=(0, 0, -7), **kw)
    s.head(size=0.3, depth=0.24, at=(0, -0.32, 0), face="smile_c", rot=(0, 0, 8), **kw)
    # 가슴 얼굴(심장): 홍조가 짙고 두근거림(두 자세로 커졌다 작아짐)
    for fr, sz in zip(s.frames(2), (0.36, 0.41)):
        with fr:
            s.head(size=sz, depth=0.28, at=(0, 0.32, 0.02), face="deep", **kw)
    limb_face = ("smile", "smile_a", "smile_c", "smile")
    # 팔: 어깨에서 아래로, 마디 셋(팔꿈치 둘), 마디마다 꿴 얼굴
    for i, side in enumerate((1, -1)):
        if swing and side < 0:
            w = kit.Wave(amp=(34, 16, 12), lag=30, bias=(78, 6, 6), cycles=2)
        else:
            w = kit.Wave(amp=(24, 14, 10), lag=30, bias=(0, 10, 12), phase=0 if side > 0 else 180)
        ch = s.rod((side * 0.27, 0.44, 0), (side * 0.22, -1.0, 0.0), length=0.92, segs=3, thick=0.06, wave=w, toward=(0, 0, 1), poses=4)
        for fr in s.frames(4):
            with fr:
                for j in (1, 2):
                    p = ch.hinges[fr.k][j]
                    s.head(size=0.2, depth=0.16, at=p, face=limb_face[(i * 2 + j) % 4], rot=(0, 0, side * 20 * j), **kw)
    # 다리: 골반에서 발까지 마디 둘, 무릎에 꿴 얼굴
    for i, side in enumerate((1, -1)):
        w = kit.Wave(amp=(26, 20), lag=40, bias=(0, -14), phase=180 if side > 0 else 0)
        ch = s.rod((side * 0.12, -0.42, 0), (side * 0.04, -1.0, 0.0), length=0.52, segs=2, thick=0.07, wave=w, toward=(0, 0, 1), poses=4)
        for fr in s.frames(4):
            with fr:
                s.head(size=0.2, depth=0.16, at=ch.hinges[fr.k][1], face=limb_face[i + 1], rot=(0, 0, -side * 12), **kw)


def build(k: kit.Kit):
    beasts = {kind: Beast(k, kind) for kind in ("pig", "sheep", "cow")}
    # 떼 마리는 오래 살면 변함 규칙에서 빠진다(변이 부위 끔)
    m = k.model(poses=4, period=8, shrink=0.5, mut2=False, torn=False)
    for kind, bt in beasts.items():
        draw_beast(m.state(kind), bt)
        draw_beast(m.state(kind + "_bare"), bt, rider=False)
    m.default = "pig"
    lod = m.state("lod", poses=2)
    for (hx, hz), kind in zip(HOMES, KINDS):
        draw_beast(lod, beasts[kind], ox=hx, oz=hz, leg_poses=2, leg_faces=("south", "east", "west"), mask_near=False)

    d = k.model("g40_doll", poses=4, period=20, mut2=False, torn=False)
    doll(d.state("walk"), k, "walk")
    doll(d.state("swing"), k, "swing")
    hs = d.state("heart", poses=2)
    for fr, sz in zip(hs.frames(2), (0.36, 0.42)):
        with fr:
            hs.head(size=sz, depth=0.28, at=(0, -0.95 + 0.21, 0), face="deep", near=False, open=False, torn=False)
    for side in (1, -1):
        hs.rod((side * 0.16, -0.95 + 0.2, -0.12), (side * 0.8, 0.5, -0.4), length=0.3, segs=1, thick=0.05, wave=kit.Wave(amp=20, phase=90 * side),
               poses=2, base=False)
    d.default = "walk"

    r = k.model("g40_rider", poses=4, period=10, mut2=False, open=False, torn=False)
    rs = r.state("")
    hh = rs.head(size=0.3, depth=0.22, face="smile", near=False)
    for side in (1, -1):
        rs.rod(hh.point(side * 0.9, -0.3, -0.7), (side * 0.9, 0.1, -0.6), length=0.5, segs=2, thick=0.06,
               wave=kit.Wave(amp=(26, 22), lag=50, shape="flap"), base=False)
    k.icon(model="g40_doll", state="walk")


def _bleat(seed):
    """짐승 울음 끝이 웃음으로 꺾이는 소리: 떨리는 '매애'가 끝에서 킥킥으로 넘어감(이 종의 신호)"""
    f = (300, 210, 150)[seed % 3]
    d = 0.55
    v = audio.tone(f, d, "saw", vib=0.09, vib_rate=11 if seed % 3 == 0 else 7, glide=f * 0.92)
    out = audio.mix(audio.bandpass(v, 500, 1100), audio.gain(audio.bandpass(v, 1300, 2400), 0.5))
    out = audio.env(out, 0.03, 0.08, 0.8, 0.06)
    tail = audio.giggle(seed=500 + seed, count=4, base=f * 2.1, rate=9)
    return audio.normalize(audio.fade(audio.cat(out, audio.gain(tail, 0.8)), 0.01, 0.1), 0.85)


def _heart(seed):
    a = audio.thump(seed=505 + seed, dur=0.16, freq=58)
    c = audio.thump(seed=507 + seed, dur=0.18, freq=50)
    return audio.normalize(audio.cat(a, audio.silence(0.1), audio.gain(c, 0.8), audio.silence(0.15)), 0.85)


def _whirl(seed):
    """회오리치며 조립: 솟구치는 바람과 파닥임"""
    d = 0.9
    n = audio.bandpass(audio.noise(d, 510 + seed), 500, 2800)
    t = np.linspace(0, 1, len(n), dtype=np.float32)
    sweep = audio.tone(240, d, glide=900) * 0.25
    flaps = audio.silence(d)
    for i in range(6):
        flaps = audio.mix(flaps, audio.at(audio.wet_flap(seed=512 + seed + i, dur=0.14, low=240) * 0.5, 0.1 + i * 0.12, d))
    return audio.normalize(audio.fade(n * t * 0.6 + sweep * t + flaps, 0.02, 0.1), 0.85)


def sounds(k: kit.Kit):
    k.sound("bleat", "울음 끝이 웃음으로 꺾임", synth=_bleat, volume=0.9, attenuation=32)
    k.sound("hoof", "떼 지어 달리는 발굽", vanilla=[("mob/horse/gallop1", 1.2), ("mob/horse/gallop2", 1.25), ("mob/horse/gallop3", 1.15),
                                              ("mob/horse/gallop4", 1.3)], volume=0.8, attenuation=32)
    k.sound("ram", "쿵 들이받음", synth=lambda i: audio.thump(seed=515 + i, freq=90), attenuation=16)
    k.sound("whirl", "회오리치며 쌓임", synth=_whirl, volume=0.9, attenuation=24)
    k.sound("heart", "가슴 두근", synth=_heart, volume=0.9, attenuation=16)
    k.sound("scatter", "와르르 흩어짐", vanilla=[("block/bone_block/break1", 0.8), ("block/bone_block/break3", 0.75), ("block/bone_block/break5", 0.85)],
            volume=0.9, attenuation=24)
    k.sound("swing", "꿴 팔을 휘두름", vanilla=[("mob/phantom/flap1", 0.8), ("mob/phantom/flap3", 0.85)], synth=lambda i: audio.wet_flap(seed=520 + i),
            volume=0.9, attenuation=16)


def phrases():
    return {"cause_g40": "짐승에 들이받혔다", "cause_g40_arm": "꿴 팔에 맞았다"}
