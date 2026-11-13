"""G49 축복 주는 게이브: 땅에서 솟은 엔드 막대기 촉수 셋, 끝마다 눈을 감고 입꼬리만 자로 잰 듯 고르게 올라간 밀랍 인형 같은 평온한 얼굴.
촉수에는 작은 얼굴들이 막대기 팔로 서로 손을 잡고 줄줄이 매달려 있다(팔이 꼬물거림 = 원형 DNA).

- gave:gabe/g49(촉수 하나, 부품 셋으로 씀): 상태 ""(평온), "give"(축복을 줄 때: 작은 얼굴들의 팔이 빠르게 흔들림).
  밑동이 (0, -1.5, 0), 끝 얼굴이 (0, 1.3, 0.45). 흔들림과 사람 쪽 기울임은 Java 가 부품 자세로 준다.
- gave:gabe/g49_mask(축복받은 사람의 머리 칸 덧그림, 남들에게 보임): 상태 "m1"(홍조), "m2"(홍조 + 점 입), "m3"(홍조 + 점 입 + 실눈 + 머리 덩어리).
  머리 칸에서는 모델 -Z(north) 가 사람 얼굴 앞이다.
- 덮개 g49_bless1 .. 3: 축복받은 본인 화면 가장자리 홍조(단계마다 짙어짐).
"""
import math

import numpy as np
from PIL import Image

from gabe import audio, faces, kit

TIP = (0.0, 1.3, 0.45)  # 끝 얼굴 가운데(Java G49.TIP 과 같게)
FACE = 0.62
BASE = faces.Face(eyes="none", mouth="none", blush="none")
EYE_COL, MOUTH_COL = (0x59, 0x1b, 0x1b), (0x69, 0x20, 0x20)
ARM = kit.Wave(amp=22, lag=0)
ARM_FAST = kit.Wave(amp=30, lag=0, cycles=2)


def serene(near):
    """평온한 얼굴(먼 32, 가까운 128): 감은 눈(아래로 둥근 선 둘), 양 끝만 고르게 올라간 곧은 입"""
    a = (faces.near(BASE) if near else faces.far(BASE)).copy()
    k = a.shape[0] // 32
    half = 0.55 if k == 1 else 0.42
    for yy in range(a.shape[0]):
        for xx in range(a.shape[1]):
            gx, gy = (xx + 0.5) / k, (yy + 0.5) / k
            hit = None
            for ex in (8.0, 24.0):
                u = (gx - ex) / 3.2
                if abs(u) <= 1.0:
                    cy = 14.2 + 1.5 * (1 - u * u)
                    if abs(gy - cy) <= half:
                        hit = (EYE_COL, faces.CLS_EYE)
            # 입: 가운데는 자로 그은 수평선, 양 끝 두 칸만 똑같이 올라감
            if 12.0 <= gx <= 20.0 and abs(gy - 26.0) <= half:
                hit = (MOUTH_COL, faces.CLS_MOUTH)
            for sx, sg in ((12.0, -1), (20.0, 1)):
                t = (gx - sx) * sg
                if 0.0 <= t <= 2.4 and abs(gy - (26.0 - t * 0.75)) <= half:
                    hit = (MOUTH_COL, faces.CLS_MOUTH)
            if hit:
                col, cls = hit
                a[yy, xx, :3] = col
                a[yy, xx, 2] = (col[2] & 0xF8) | cls
                a[yy, xx, 3] = 255
    return a


def tentacle(k, s, arm):
    # 줄기: 느슨한 S 자로 굽은 막대기 셋(가만히, 흔들림은 Java)
    pts = [(0.0, -1.5, 0.0), (0.14, -0.6, 0.08), (-0.08, 0.3, 0.2), (0.0, 1.02, 0.36)]
    for i in range(3):
        a, b = np.array(pts[i]), np.array(pts[i + 1])
        s.rod(tuple(a), tuple(b - a), length=float(np.linalg.norm(b - a)) * 1.04, segs=1, thick=0.14 - 0.02 * i, base=(i == 0))
    # 끝 얼굴: 살짝 숙임
    h = s.head(size=FACE, depth=0.46, at=TIP, face=BASE, open=False, torn=False, rot=(14, 0, 0))
    # 줄여 굽는 모델(shrink 0.5)은 키트의 가까운 얼굴 판이 0.01 블록 앞에 있으므로 그보다 앞에 둔다
    z = 0.46 / 2 + 0.008
    far = k.texture("serene", serene(False), classes=True)
    nr = k.texture("serene_n", serene(True), classes=True)
    s.quad((TIP[0], TIP[1], TIP[2] + z), (FACE, FACE), "south", far, dist="far", face=True, rot=(14, 0, 0), origin=TIP)
    s.quad((TIP[0], TIP[1], TIP[2] + z + 0.01), (FACE, FACE), "south", nr, dist="near", face=True, rot=(14, 0, 0), origin=TIP)
    # 줄줄이 매달린 작은 얼굴 셋, 막대기 팔로 손을 잡음
    minis = [(0.3, -0.95, 0.12), (-0.28, -0.15, 0.2), (0.26, 0.6, 0.34)]
    for i, c in enumerate(minis):
        side = 1 if c[0] > 0 else -1
        s.head(size=0.22, depth=0.17, at=c, face="smile", near=False, open=False, torn=False, rot=(0, 25 * side, 0))
        for up in (1, -1):
            root = (c[0] - side * 0.05, c[1] + up * 0.1, c[2])
            s.rod(root, (-side * 0.75, up * 0.65, 0.0), length=0.36, segs=1, thick=0.05, base=False, poses=4,
                  wave=kit.Wave(arm.amp, arm.lag, 0, 90 * i + (0 if up > 0 else 180), "sin", arm.cycles), toward=(0, 0, 1))


def mask_img(stage):
    """머리 칸 덧그림(32, 투명 바탕): 단계마다 게이브 얼굴의 홍조, 점 입, 실눈과 머리 덩어리가 드러남"""
    far = faces.far(faces.preset("smile"))
    cls = far[..., 2] & 7
    keep = {faces.CLS_BLUSH}
    if stage >= 2:
        keep.add(faces.CLS_MOUTH)
    if stage >= 3:
        keep |= {faces.CLS_EYE, faces.CLS_HAIR, faces.CLS_SPARK}
    out = np.zeros_like(far)
    for c in keep:
        out[cls == c] = far[cls == c]
    out[..., 3] = np.where(out[..., 3] > 0, 255, 0)
    return out


def cover_img(stage, W=480, H=270):
    """축복받은 본인 화면: 가장자리 분홍 홍조, 3 단계는 아래 양쪽에 짙은 볼 홍조"""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    r = np.hypot((xx - W / 2) / (W / 2), (yy - H / 2) / (H / 2))
    out = np.zeros((H, W, 4), np.float32)
    out[..., 0], out[..., 1], out[..., 2] = 255, 176, 190
    strength = (0.4, 0.62, 0.82)[stage - 1]
    a = strength * np.clip((r - 0.5) / 0.5, 0, 1) ** 1.4
    if stage >= 3:
        for cx in (W * 0.16, W * 0.84):
            b = np.clip(1 - np.hypot((xx - cx) / (W * 0.16), (yy - H * 0.82) / (H * 0.2)), 0, 1)
            a = np.maximum(a, 0.7 * b)
    out[..., 3] = np.clip(a, 0, 1) * 235
    return Image.fromarray(np.clip(np.round(out), 0, 255).astype(np.uint8))


def build(k: kit.Kit):
    m = k.model(poses=4, period=40, shrink=0.5, mut2=False, open=False, torn=False)
    tentacle(k, m.state(""), ARM)
    tentacle(k, m.state("give"), ARM_FAST)
    mask = k.model("g49_mask", poses=1, period=20, mut2=False, open=False, torn=False)
    for st in (1, 2, 3):
        tex = k.texture(f"mask{st}", mask_img(st))
        # 사람 머리(0.5 블록)는 머리 칸에서 모델 0.8 블록. 얼굴 앞(-Z)에서 모자 층보다 조금 앞
        mask.state(f"m{st}").quad((0, 0, -0.445), (0.8, 0.8), "north", tex)
    mask.default = "m1"
    for st in (1, 2, 3):
        k.cover(f"g49_bless{st}", cover_img(st))
    k.icon(model="g49", state="")


def _chant(seed):
    """'같이 가자': 여러 목소리가 낮게 겹쳐 중얼거리는 네 마디(가까이서만 들림)"""
    syll = [((700, 1.0), (1220, 0.5), (2600, 0.2)), ((300, 1.0), (2300, 0.5), (3000, 0.2)), ((700, 1.0), (1220, 0.5), (2600, 0.2)),
            ((650, 1.0), (1100, 0.5), (2500, 0.2))]
    voices = []
    for v, f0 in enumerate((165 + seed * 6, 208, 262 - seed * 5)):
        parts = []
        for i, fm in enumerate(syll):
            d = 0.2 if i < 3 else 0.34
            x = audio._vowel(f0 * (1.0 if i != 1 else 1.06), d, fm, 490 + seed * 9 + v * 3 + i)
            parts.append(audio.env(x, 0.03, 0.05, 0.7, 0.08))
            parts.append(audio.silence(0.035))
        voices.append(audio.at(audio.cat(*parts), 0.03 * v))
    return audio.normalize(audio.fade(audio.lowpass(audio.mix(*voices), 2800), 0.01, 0.08), 0.6)


def sounds(k: kit.Kit):
    # 이 종의 신호: 가까이서만 들리는 여러 목소리의 '같이 가자'
    k.sound("chant", "같이 가자", synth=_chant, volume=0.8, attenuation=8)
    k.sound("bless", "축복이 스며듦", vanilla=[("block/amethyst/shimmer", 0.8), ("block/beacon/power1", 1.2)], volume=0.9, attenuation=16)
    k.sound("wake", "가짜 동료가 일어남", vanilla=[("mob/slime/big1", 0.6), ("mob/slime/big3", 0.62)], volume=1.0, attenuation=24)


def phrases():
    return {"cause_g49": "축복이 끝났다", "cause_g49_fake": "가짜 동료에게 맞았다", "notice_g49_bless": "누군가 축복을 받았다",
            "notice_g49_fake": "가짜 동료가 생겼다"}
