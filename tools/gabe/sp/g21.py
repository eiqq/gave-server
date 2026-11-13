"""G21 노크하는 게이브: 하늘에서 내려온 듯 끝이 안 보이게 위로 길게 뻗은 엔드 막대기 둘에 거꾸로 매달린 얼굴(전장은 처마 없는 황야,
docs/모듈/맵변경_황야.md). 막대기 아래 마디를 팔꿈치처럼 꺾어 허공을 '똑, 똑' 두드린다.

모델 gave:gabe/g21(shrink 0.5) 상태:
  ""      매달림, 느린 노크: 20틱에 '똑, 똑' 한 번(자세 0 에서 왼 막대기, 자세 2 에서 오른 막대기가 꺾임, 나머지는 곧게). 자세 8장
  "fast"  아래로 사람이 지나감: 8틱 주기로 번갈아 계속 꺾음(자세 4장, 4틱에 한 번 '똑'), 얼굴이 아래를 내려다봄
  "drop"  막대기를 타고 미끄러져 내려옴(막대기는 곧게, 살짝 흔들림), 얼굴이 아래를 봄
  "ride"  사람 등에 올라탐(바로 선 얼굴, 막대기가 어깨 너머로 감아 꿈틀)
  "crawl" 떨어져 바닥을 기어 제자리 밑으로 돌아감(막대기가 다리)
모델 gave:gabe/g21_sky: 매달린 막대기의 위쪽(1블록짜리 곧은 막대기 둘). Java 가 부품 디스플레이로 붙여 세로로 길게 늘인다(끝이 하늘로 사라짐).

Java(G21) 와 맞춘 약속: 매달린 상태의 막대기는 몸 가운데 위 ROD_TOP(2.6) 블록까지 올라가 거기서 g21_sky 와 이어진다(x = ±ROD_X, z = 0).
노크 소리는 Java 가 자세 0, 2(빠를 땐 0, 2)가 시작하는 틱에 맞춰 울린다(개체 위상은 5틱 단위).
"""
import numpy as np

from gabe import audio, kit

HEAD = 0.6
ROD_X = 0.15  # 막대기 둘의 가로 자리
ROD_TOP = 2.6  # 모델 막대기가 끝나고 하늘 막대기(g21_sky)가 이어지는 높이
ROD_BOTTOM = 0.12  # 막대기 아래 끝(머리 속에 묻힘)
ELBOW = 1.0  # 팔꿈치 높이
THICK = 0.075


def arm(s, side, bend):
    """막대기 하나(위 끝 고정, 아래 끝은 머리 속): bend 0 은 곧게, 1 은 팔꿈치를 바깥 앞으로 꺾어 허공을 두드림. 마디 둘"""
    top = np.array([side * ROD_X, ROD_TOP, 0.0])
    bottom = np.array([side * ROD_X, ROD_BOTTOM, 0.0])
    elbow = np.array([side * (ROD_X + 0.24 * bend), ELBOW + 0.14 * bend, 0.26 * bend])
    for a, b in ((top, elbow), (elbow, bottom)):
        d = b - a
        s.rod(tuple(a), tuple(d), length=float(np.linalg.norm(d)), segs=1, thick=THICK, wave=None, toward=(0, 0, 1), base=False)


def hang(s, bends, tilt):
    """거꾸로 매달린 얼굴(rot z 180, tilt 만큼 아래를 봄)과 자세마다 꺾이는 막대기 둘. bends[k] = (왼쪽 꺾임, 오른쪽 꺾임)"""
    s.head(size=HEAD, face="smile", rot=(-tilt, 0, 180))
    for f in s.frames(len(bends)):
        with f:
            bl, br = bends[f.k]
            arm(s, 1, bl)
            arm(s, -1, br)


def build(k: kit.Kit):
    # 미리 깔리는 종이라 오래 살면 변함에서 빠진다: 변이 막대기, 찢어진 입 면을 싣지 않는다
    m = k.model(poses=8, period=20, shrink=0.5, mut2=False, torn=False)
    # 느린 노크: '똑'(왼), '똑'(오른), 쉼
    hang(m.state(""), [(1, 0), (0.45, 0), (0, 1), (0, 0.45), (0, 0), (0, 0), (0, 0), (0, 0)], tilt=14)
    # 빠른 노크: 번갈아 쉬지 않고
    hang(m.state("fast", poses=4, period=8), [(1, 0), (0.4, 0.2), (0, 1), (0.2, 0.4)], tilt=34)
    # 막대기를 타고 내려옴: 곧은 막대기 둘(위 끝 고정, 아래 끝은 머리 속에서 살짝 흔들림)
    s = m.state("drop")
    s.head(size=HEAD, face="smile", rot=(-34, 0, 180))
    for side in (1, -1):
        s.rod((side * ROD_X, ROD_TOP, 0.0), (0, -1, 0), length=ROD_TOP - ROD_BOTTOM, segs=2, thick=THICK,
              wave=kit.Wave(amp=(1.5, 3.0), lag=60, phase=0 if side > 0 else 180), toward=(0, 0, 1), base=False)
    # 등에 올라탐: 바로 선 얼굴, 막대기가 앞(사람 어깨) 너머로 감아 꿈틀
    s = m.state("ride")
    h = s.head(size=HEAD, face="smile")
    for side in (1, -1):
        s.rod(h.point(side * 0.9, 0.3, -0.6), (side * 0.5, 0.2, -1.0), length=0.75, segs=3, thick=THICK,
              wave=kit.Wave(amp=(10, 16, 22), lag=60, bias=(0, 10, 20), cycles=2, phase=0 if side > 0 else 120), toward=(0, -1, 0))
    # 바닥을 기어감: 막대기가 다리
    s = m.state("crawl")
    h = s.head(size=HEAD, face="smile")
    for side in (1, -1):
        s.rod(h.point(side * 0.8, -0.6, 0), (side * 0.6, -1.0, 0.2), length=0.6, segs=2, thick=THICK,
              wave=kit.Wave(amp=(18, 14), lag=50, cycles=2, phase=0 if side > 0 else 180), toward=(0, 0, 1))
    # 하늘로 뻗은 막대기 둘(1블록, Java 가 세로로 늘임)
    sky = k.model("g21_sky", poses=1, mut2=False, open=False, torn=False)
    st = sky.state("")
    for side in (1, -1):
        st.rod((side * ROD_X, 0.0, 0.0), (0, 1, 0), length=1.0, segs=1, thick=THICK, wave=None, toward=(0, 0, 1), base=False)
    k.icon(model="g21", state="")


def knock_snd(seed=0):
    """'똑': 손가락 마디로 허공의 문을 두드리는 짧은 한 번(Java 가 박자에 맞춰 한 번씩 울림)"""
    body = audio.env(audio.bandpass(audio.noise(0.06, seed), 300, 2200), 0.0008, 0.012, 0.15, 0.03)
    tone = audio.env(audio.tone(380 + seed % 5 * 20, 0.06, "tri", glide=260), 0.0005, 0.01, 0.2, 0.03) * 0.8
    return audio.normalize(audio.fade(audio.mix(body, tone), 0.001, 0.02), 0.85)


def slide(seed=0, dur=0.6):
    """'스르륵': 막대기를 타고 미끄러져 내려옴"""
    n = audio.bandpass(audio.noise(dur, seed), 1200, 5000)
    t = np.linspace(0, 1, len(n), dtype=np.float32)
    return audio.normalize(audio.fade(n * (0.4 + 0.6 * np.sin(t * np.pi)) * (0.7 + 0.3 * np.sin(t * 70))), 0.6)


def sounds(k: kit.Kit):
    # 이 종의 신호(이 종 전용): 허공에서 '똑, 똑'. 박자는 Java 가 울리는 간격으로 빨라짐
    k.sound("knock", "똑, 똑", synth=lambda i: knock_snd(seed=90 + i), volume=0.9, attenuation=20)
    k.sound("slide", "스르륵 내려옴", synth=lambda i: slide(seed=94 + i), attenuation=16)
    k.sound("ride", "등에 덥석", vanilla=[("mob/slime/big1", 0.9), ("mob/slime/big3", 0.85)], synth=lambda i: audio.squish(seed=97 + i),
            attenuation=16)


def phrases():
    return {"cause_g21": "등에 올라타 하늘을 보게 했다"}
