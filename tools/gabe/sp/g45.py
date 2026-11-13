"""G45 줄지어 나는 게이브: 0.62 블록 얼굴 여섯이 앞 뒤통수에 다음 이마를 쇠사슬과 분홍 살 띠로 이어 붙인 행렬(마디 = 떼 마리 하나,
디스플레이 하나). 얼굴마다 엔드 막대기 둘(앞 막대기, 뒤 막대기)에 젖은 피막을 친 날개가 양옆으로 펼쳐져 퍼덕인다. 앞에서 뒤로
퍼지는 파도는 Java 가 마디마다 위상을 4틱씩 늦춰 만든다.

- gave:gabe/g45(마디): 상태 "f0" .. "f5"(표정 점층: 실눈 웃음 → 반쯤 뜬 눈 → 다 뜬 눈 → 핏발 → 부릅뜨고 비명 입, 기본 "f0"), "lod".
  부위 "tail": 행렬의 맨 끝 마디(뒤로 가는 이음줄 없음). 실눈 뜸(표적을 찍음)은 맨 앞 얼굴 f0 에만.
  떼 마리라 변이 2 막대기, 입 찢어짐은 끔.
- gave:gabe/g45_shadow(부품, 내려올 길을 먼저 지나가는 길쭉한 바닥 그림자): 1 x 1 로 굽고 Java 가 폭 1.8, 길이 7 로 늘인다.
  부위 "show" 가 켜질 때만 보임. 반투명 대신 성긴 점묘(컷아웃).
"""
import math

import numpy as np

from gabe import audio, faces, kit

S, D = 0.62, 0.42
GAP = 1.15  # 마디 사이 거리(Java SPACING 과 같게)
LEAD = kit.Wave(amp=(24, 20, 16), lag=50, bias=(4, -2, -6), shape="flap")
TRAIL = kit.Wave(amp=(20, 18), lag=50, bias=(2, -4), phase=-35, shape="flap")
BASES = ["smile", "smile_a", "half", "open", "bloodshot", "bloodshot"]


def _mouth(a, cx, cy, rx, ry, teeth):
    """벌어진 입: 타원 안 어둠 + 테두리, teeth 면 윗니 한 줄. a 는 32 또는 128 얼굴 배열(부류 비트 유지)"""
    k = a.shape[0] // 32
    for yy in range(int((cy - ry - 1) * k), int((cy + ry + 1) * k)):
        for xx in range(int((cx - rx - 1) * k), int((cx + rx + 1) * k)):
            if not (0 <= xx < a.shape[1] and 0 <= yy < a.shape[0]):
                continue
            dx = ((xx + 0.5) / k - cx) / rx
            dy = ((yy + 0.5) / k - cy) / ry
            r = dx * dx + dy * dy
            if r > 1.0:
                continue
            inner = r <= 0.62
            col = (36, 8, 12) if inner else (105, 32, 32)
            cls = faces.CLS_MOUTH
            if teeth and inner and dy < -0.45 and int(xx / k) % 2 == 0:
                col, cls = (236, 226, 208), faces.CLS_TEETH
            a[yy, xx, :3] = col
            a[yy, xx, 2] = (col[2] & 0xF8) | cls
            a[yy, xx, 3] = 255
    return a


def face_img(i, near):
    """마디 i 의 얼굴(먼 32, 가까운 128). 4 는 입이 살짝 벌어지고 5 는 비명처럼 길게 벌어짐"""
    f = faces.preset(BASES[i])
    a = (faces.near(f) if near else faces.far(f)).copy()
    if i == 4:
        _mouth(a, 18.0, 26.4, 2.4, 2.6, False)
    elif i == 5:
        _mouth(a, 18.0, 26.6, 3.4, 4.6, True)
    return a


BAYER = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def shadow_tex(n=64):
    """길쭉한 그림자(늘이기 전 원): 가운데 짙고 가장자리로 성긴 점"""
    im = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r >= 1:
                continue
            d = 0.72 * (1 - r ** 2.2)
            if BAYER[y % 4, x % 4] < d:
                im[y, x] = (14, 8, 12, 255)
    return im


def member(k, s, i):
    h = s.head(size=S, depth=D, face=BASES[i], open=(i == 0), torn=False)
    if i >= 4:
        z = D / 2 + 0.006
        s.quad((0, 0, z), (S, S), "south", k.texture(f"f{i}", face_img(i, False), classes=True), dist="far", face=True)
        s.quad((0, 0, z + 0.003), (S, S), "south", k.texture(f"f{i}_n", face_img(i, True), classes=True), dist="near", face=True)
    # 날개: 앞 막대기(세 마디)와 뒤 막대기(두 마디) 사이에 젖은 피막
    for side in (1, -1):
        lead = s.rod(h.point(side * 0.9, 0.2, -0.3), (side * 1.0, 0.12, -0.1), length=1.05, segs=3, thick=0.09, wave=LEAD, poses=6)
        trail = s.rod(h.point(side * 0.9, -0.35, -0.8), (side * 0.8, -0.05, -0.6), length=0.8, segs=2, thick=0.07, wave=TRAIL,
                      poses=6, base=False)
        s.membrane(lead, trail, tex="wetskin")
    # 뒤 마디로 가는 이음줄: 쇠사슬과 분홍 살 띠(맨 끝 마디는 없음)
    with s.when("!on:tail"):
        back = -D / 2
        s.chain((0, 0.07, back), (0, 0.07, back - (GAP - D)), links=4, sag=0.06, size=0.08)
        s.band((0, -0.06, back), (0, 0, -1), length=GAP - D, width=0.16, segs=2, thick=0.03, tex="flesh")


def build(k: kit.Kit):
    m = k.model(poses=6, period=24, mut2=False, torn=False)
    for i in range(6):
        member(k, m.state(f"f{i}"), i)
    m.default = "f0"
    lod = m.state("lod")
    lod.lump(count=6, radius=(0.25, 0.2, 1.3), size=0.42, faces_list=["smile", "half", "open"], seed=45)
    sh = k.model("g45_shadow", poses=1, period=20, mut2=False, open=False, torn=False)
    with sh.state("").when("on:show"):
        sh.state("").quad((0, 0, 0), (1, 1), "up", k.texture("shadow", shadow_tex()))
    k.icon(model="g45", state="f0")


def _flutter(seed):
    """'푸드득': 젖은 피막 여러 장이 겹쳐 치는 소리(짧은 퍼덕 셋을 어긋나게)"""
    a = audio.wet_flap(seed=seed, dur=0.26, low=210)
    b = audio.wet_flap(seed=seed + 7, dur=0.24, low=260)
    c = audio.wet_flap(seed=seed + 13, dur=0.22, low=320)
    return audio.normalize(audio.fade(audio.mix(a, audio.at(b, 0.07), audio.gain(audio.at(c, 0.15), 0.8)), 0.003, 0.05), 0.9)


def sounds(k: kit.Kit):
    # 이 종의 신호: 넓은 피막이 겹쳐 치는 푸드득(하늘에서 멀리 들림)
    k.sound("flap", "푸드득", vanilla=[("mob/phantom/flap1", 0.8), ("mob/phantom/flap3", 0.85), ("mob/phantom/flap5", 0.78)],
            synth=lambda i: _flutter(450 + i), volume=1.0, attenuation=48)
    k.sound("hook", "꼬리가 낚아챔", vanilla=[("mob/phantom/bite1", 0.7), ("mob/phantom/bite2", 0.75)], volume=1.0, attenuation=32)
    k.sound("snap", "행렬이 끊김", vanilla=[("block/chain/break1", 0.7), ("block/chain/break3", 0.75)], volume=1.0, attenuation=32)


def phrases():
    return {"cause_g45": "행렬에 훑였다", "notice_g45_hook": "꼬리에 낚였다"}
