"""G35 엎드린 척 게이브: 평소엔 쓰러진 동료 표시(바닥 원, 이름, 남은 초: Java 가 진짜와 같은 부품으로 그림) 밑에 엎드린 얼굴. 분홍 뒤통수만
보이고 엔드 막대기 둘은 몸 밑에 접혀 끝만 양옆으로 살짝 비친다(흐느끼듯 가끔 움찔). 드러나면 점 입이 귀까지 찢어진 뜬 눈 얼굴.

상태:
  ""      엎드림(얼굴이 바닥 쪽, 뒤통수가 위. 막대기 끝이 가끔 움찔)
  "flip"  드러남: 얼굴을 돌려 세움(찢어진 입, 뜬 눈), 막대기가 양옆으로 펴져 펄럭임
  "latch" 사람 얼굴에 달라붙음: 막대기가 앞으로 오므려 머리를 끌어안고 파르르
덮개 g35_face: 달라붙은 사람 화면(코앞의 찢어진 입 얼굴).
미리 깔리는 종이라 오래 살면 변함에서 빠진다(변이 부위 끔).
"""
import numpy as np

from gabe import audio, faces, kit

SIZE, DEPTH = 0.6, 0.46
TORN = faces.Face(eyes="open", mouth="torn")


def build(k: kit.Kit):
    m = k.model(poses=8, period=20, mut2=False, open=False, torn=False)
    # 엎드림: 머리를 x 로 90도 눕혀 얼굴이 바닥을 봄. 모델 가운데 = 머리 가운데(Java 가 바닥 위 깊이 절반 높이에 둠)
    s = m.state("")
    s.head(size=SIZE, depth=DEPTH, face="smile", rot=(90, 0, 0))
    for side in (1, -1):
        w = kit.Wave(amp=(5, 8), lag=30, shape="twitch", phase=30 if side > 0 else 210)
        s.rod((side * 0.1, -DEPTH / 2 + 0.05, 0.05), (side * 1.0, 0.0, 0.3), length=0.52, segs=2, thick=0.09, wave=w, toward=(0, 0, 1),
              base=False, poses=4)
    # 드러남: 얼굴을 세우고 막대기를 펴 펄럭임
    f = m.state("flip")
    h = f.head(size=SIZE, depth=DEPTH, face=TORN)
    for side in (1, -1):
        w = kit.Wave(amp=(24, 20, 16), lag=50, bias=(-6, 2, 5), shape="flap", cycles=2, phase=0 if side > 0 else 40)
        f.rod(h.point(side * 0.9, -0.3, -0.5), (side * 1.0, 0.1, -0.35), length=0.8, segs=3, thick=0.09, wave=w)
    # 달라붙음: 막대기가 앞으로 오므려 끌어안음
    la = m.state("latch")
    h = la.head(size=SIZE, depth=DEPTH, face=TORN)
    for side in (1, -1):
        w = kit.Wave(amp=(5, 7, 9), lag=70, bias=(-14, -22, -26), cycles=2, phase=0 if side > 0 else 90)
        la.rod(h.point(side * 0.95, 0.0, 0.2), (side * 0.75, 0.0, 0.7), length=0.8, segs=3, thick=0.09, wave=w, toward=(side * 1.0, 0.0, 0.0))
    k.cover_face("g35_face", TORN, zoom=1.9, vignette=0.9)
    k.icon(model="g35", state="flip")


def sob(seed=0):
    """흐느낌: 떨리며 내려가는 짧은 울음 두 번 + 들이마시는 숨"""
    def one(f, d, sd):
        x = audio.tone(f, d, "tri", vib=0.035, vib_rate=9, glide=f * 0.8) * 0.6 + audio.tone(f * 2.02, d, glide=f * 1.6) * 0.15
        return audio.env(audio.bandpass(x + audio.noise(d, sd) * 0.05, 250, 2600), 0.02, 0.05, 0.7, 0.1)
    a = one(430 + seed * 6, 0.26, seed)
    b = one(390 + seed * 6, 0.34, seed + 1)
    breath = audio.env(audio.bandpass(audio.noise(0.18, seed + 2), 900, 3200), 0.03, 0.05, 0.5, 0.06) * 0.25
    return audio.normalize(audio.fade(audio.mix(a, audio.at(b, 0.33), audio.at(breath, 0.72)), 0.01, 0.05), 0.6)


def snicker(seed=0):
    """듣기(웅크리기)로 들리는 소리: 흐느낌 끝에 킥 웃음이 섞임"""
    g = audio.giggle(seed=seed + 4, count=3, base=610, rate=11.0) * 0.55
    return audio.normalize(audio.mix(sob(seed), audio.at(g, 0.55)), 0.65)


def flip(seed=0):
    """홱: 목이 반 바퀴 돌아가는 마른 소리"""
    parts = []
    for i in range(3):
        c = audio.env(audio.highpass(audio.noise(0.04, seed + i), 1600), 0.0004, 0.006, 0.12, 0.02)
        t = audio.env(audio.tone(250 - 30 * i, 0.05, "square", glide=130), 0.0005, 0.01, 0.15, 0.02) * 0.4
        parts.append(audio.at(audio.mix(c, t), 0.045 * i))
    return audio.normalize(audio.mix(*parts), 0.85)


def sounds(k: kit.Kit):
    # 이 종의 신호(미끼): 쓰러진 사람처럼 흐느낌
    k.sound("sob", "흐느낌", synth=lambda i: sob(seed=350 + i), volume=0.8, attenuation=16)
    k.sound("snicker", "흐느낌 속 웃음", synth=lambda i: snicker(seed=354 + i), volume=0.8, attenuation=8)
    k.sound("flip", "홱 돌아감", synth=lambda i: flip(seed=358 + i), attenuation=20)
    k.sound("latch", "철썩 달라붙음", vanilla=[("mob/slime/attack1", 0.8), ("mob/slime/attack2", 0.75)],
            synth=lambda i: audio.squish(seed=362 + i), attenuation=16)


def phrases():
    return {"cause_g35": "쓰러진 척에 다가갔다"}
