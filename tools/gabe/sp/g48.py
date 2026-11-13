"""G48 열매 단 나무 게이브: 엔드 막대기를 엮은 앙상한 4 블록 나무. 꼭대기에 얼굴 하나, 가지 여섯 끝마다 작은 얼굴이 열매처럼 매달려
폐처럼 부풀었다 오그라든다. 열매마다 작은 막대기 둘이 꼬물거린다. 체력 막대 없이 열매 수가 남은 정도.

- gave:gabe/g48(나무와 꼭대기 얼굴): 상태 "". 꼭대기 얼굴 위 막대기 둘이 펄럭임(원형 DNA). 실눈 뜸 = 빛기둥이 누군가를 조이는 중.
- gave:gabe/g48_fruit(부품, 열매 송이): 부위 "gone1" .. "gone6"(그 열매가 없어짐), "ripe1" .. "ripe6"(다 부풀어 곧 떨어짐: 눈 뜬 큰 열매).
- gave:gabe/g48_beam(부품, 머리 위 노란 빛기둥): 반지름 1, 높이 2.9 로 굽고 Java 가 반지름과 높이를 늘인다. 부위 "show" 일 때만 보임.
  반투명 대신 점묘(컷아웃), 디스플레이 밝기 15.
가지 끝 자리(TIPS)는 Java G48.FRUIT 와 같아야 한다(몸 가운데 기준, 블록).
"""
import math

import numpy as np

from gabe import audio, faces, kit

# 가지: (줄기 위 높이, 방위각 도, 길이, 솟는 정도)
BRANCH = [(-0.6, 20, 1.25, 0.45), (-0.2, 140, 1.35, 0.5), (0.2, 260, 1.3, 0.55), (0.55, 80, 1.15, 0.6), (0.9, 200, 1.1, 0.65),
          (1.2, 320, 1.0, 0.7)]
HANG = 0.38  # 가지 끝에서 열매 가운데까지 아래로
BAYER = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0 + 1 / 32


def branch_dir(az, rise):
    a = math.radians(az)
    v = np.array((math.sin(a), rise, math.cos(a)))
    return v / np.linalg.norm(v)


def tips():
    out = []
    for (y, az, ln, rise) in BRANCH:
        d = branch_dir(az, rise)
        out.append(tuple(np.array((0.0, y, 0.0)) + d * ln))
    return out


def tree(s):
    # 줄기: 엇갈려 엮은 막대기 둘(가만히)
    s.rod((-0.07, -2.0, 0.0), (0.05, 1, 0.02), length=3.3, segs=3, thick=0.17)
    s.rod((0.1, -2.0, 0.06), (-0.07, 1, -0.03), length=2.6, segs=2, thick=0.13)
    for (y, az, ln, rise) in BRANCH:
        s.rod((0.0, y, 0.0), tuple(branch_dir(az, rise)), length=ln, segs=2, thick=0.1)
    h = s.head(size=0.7, depth=0.5, at=(0, 1.75, 0), face="smile", torn=False)
    for side in (1, -1):
        s.rod(h.point(side * 0.55, 0.95, -0.2), (side * 0.55, 1, -0.25), length=0.75, segs=2, thick=0.09,
              wave=kit.Wave(amp=(16, 20), lag=55, phase=0 if side > 0 else 180), poses=4)


def fruits(s):
    for i, t in enumerate(tips()):
        c = (t[0], t[1] - HANG, t[2])
        az = BRANCH[i][1]
        with s.when(f"!on:gone{i + 1}"):
            # 꼭지(살 띠)
            s.band(t, (0, -1, 0), length=HANG - 0.12, width=0.07, segs=1, thick=0.03, toward=(0, 0, 1), tex="flesh", face=False)
            with s.when(f"!on:ripe{i + 1}"):
                # 숨 쉬는 열매: 자세 4 로 부풀었다 오그라듦(열매마다 박자가 어긋남)
                for f in s.frames(poses=4):
                    with f:
                        k = 0.3 + 0.07 * (0.5 - 0.5 * math.cos(2 * math.pi * (f.t + i / 6.0)))
                        s.head(size=k, depth=k * 0.8, at=c, face="smile", near=False, open=False, torn=False, rot=(0, az, 0))
            with s.when(f"on:ripe{i + 1}"):
                s.head(size=0.5, depth=0.42, at=c, face="open", near=False, open=False, torn=False, rot=(0, az, 0))
            # 작은 막대기 둘이 꼬물거림
            a = math.radians(az)
            side = np.array((math.cos(a), 0.0, -math.sin(a)))
            for sg in (1, -1):
                root = tuple(np.array(c) + side * 0.15 * sg + np.array((0, 0.1, 0)))
                d = tuple(side * 0.7 * sg + np.array((0, 0.7, 0)))
                s.rod(root, d, length=0.26, segs=1, thick=0.05, base=False, poses=2,
                      wave=kit.Wave(amp=22, lag=0, phase=(0 if sg > 0 else 180) + 60 * i), toward=(0, 0, 1) if abs(side[2]) < 0.9 else (1, 0, 0))


def beam_tex(dens, n=32, top=0.15):
    """빛기둥 옆면: 아래가 짙고 위로 갈수록 옅어지는 노란 점묘, 세로 결"""
    out = np.zeros((n, n, 4), np.uint8)
    for y in range(n):
        v = 1.0 - y / (n - 1)  # 1 위 .. 0 아래
        d = dens * (top + (1 - top) * (1 - v) ** 0.8)
        for x in range(n):
            streak = 0.75 + 0.25 * math.sin(x * 1.9)
            if BAYER[y % 4, x % 4] < d * streak:
                out[y, x] = (255, 232 + (x * 7) % 16, 150, 255)
    return out


def ring_tex(n=64):
    """바닥 빛 고리: 가장자리 고리와 안쪽 옅은 점묘"""
    out = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r > 1.0:
                continue
            d = 0.85 if r > 0.86 else 0.16
            if BAYER[y % 4, x % 4] < d:
                out[y, x] = (255, 236, 150, 255)
    return out


def beam(k, s):
    side = k.texture("beam", beam_tex(0.5))
    ring = k.texture("ring", ring_tex())
    H = 2.9
    with s.when("on:show"):
        for j in range(8):
            a = 2 * math.pi * j / 8
            r = math.cos(math.pi / 8)
            w = 2 * math.sin(math.pi / 8) * 1.01
            c = (math.sin(a) * r, 0, math.cos(a) * r)
            s.box((c[0] - w / 2, -H / 2, c[2]), (c[0] + w / 2, H / 2, c[2]), side, faces=("north", "south"), uv=[2 * j, 0, 2 * j + 2, 16],
                  rot=(0, math.degrees(a), 0), origin=c, shade=False)
        s.quad((0, -H / 2 + 0.01, 0), (2, 2), "up", ring)


def build(k: kit.Kit):
    m = k.model(poses=4, period=20, shrink=0.5, mut2=False, torn=False)
    tree(m.state(""))
    fr = k.model("g48_fruit", poses=4, period=40, shrink=0.5, mut2=False, open=False, torn=False)
    fruits(fr.state(""))
    bm = k.model("g48_beam", poses=1, period=20, mut2=False, open=False, torn=False)
    beam(k, bm.state(""))
    k.icon(model="g48", state="")


def _child(seed):
    """아이 웃음: 높고 짧은 '히히' 몇 마디(아이는 없고 소리만)"""
    return audio.giggle(seed=480 + seed, count=4 + seed, base=760 + 40 * seed, rate=6.5)


def _wheeze(seed):
    """'쌕쌕': 가늘게 들이쉬고 내쉬는 숨 두 번"""
    parts = []
    for i in range(2):
        d = 0.42
        n = audio.bandpass(audio.noise(d, 485 + seed * 4 + i), 1400 + 500 * i, 5200)
        whistle = audio.tone(1700 + 220 * i + 60 * seed, d, vib=0.03, vib_rate=11) * 0.12
        parts.append(audio.at(audio.env(audio.mix(n * 0.6, whistle), 0.12, 0.1, 0.6, 0.15), i * 0.5))
    return audio.normalize(audio.fade(audio.mix(*parts), 0.01, 0.06), 0.7)


def sounds(k: kit.Kit):
    # 이 종의 신호: 아이 웃음(아이는 없음)과 열매의 쌕쌕 숨소리
    k.sound("giggle", "아이 웃음", synth=_child, volume=0.8, attenuation=24)
    k.sound("wheeze", "쌕쌕", synth=_wheeze, volume=0.8, attenuation=12)
    k.sound("beam", "빛기둥이 조여듦", vanilla=[("block/beacon/power1", 1.5), ("block/beacon/power2", 1.6)], volume=0.9, attenuation=16)
    k.sound("drop", "열매가 떨어짐", vanilla=[("mob/slime/small2", 0.8), ("mob/slime/small4", 0.75)], volume=1.0, attenuation=16)


def phrases():
    return {"cause_g48": "쉼터에 오래 머물렀다"}
