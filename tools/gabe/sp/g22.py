"""G22 뒷걸음 게이브: 늘 뒤통수를 앞으로 하고 뒷걸음질로 온다. 뒤통수 한가운데에 세로로 찢어진 외눈 하나가 떠서 나를 본다. 얼굴과 다리 사이를
분홍 살 띠 목이 잇고, 엔드 막대기 둘은 아래로 뻗은 다리(원형 DNA, 걸음마다 흔들림).

디스플레이 셋(Java):
  gave:gabe/g22       닻: 엉덩이 살덩이 + 다리 막대기 둘(걷기 물결, 16틱 한 걸음 바퀴)
  gave:gabe/g22_head  머리: 앞얼굴이 -Z(뒤), 뒤통수(+Z, 몸 앞쪽)에 세로 외눈. 상태 ""(외눈 뜸, 표적을 찍으면 크게), "shut"(외눈 감김: 목을 비틀어
                      앞얼굴이 돌아올 때). 앞얼굴은 표적을 찍으면 실눈이 뜨임(open)
  gave:gabe/g22_neck  목: +Z 로 1 블록짜리 분홍 살 관(Java 가 목 뿌리에서 머리까지 늘임)
"""
import numpy as np

from gabe import audio, faces, kit

HEAD = 0.62


def back_eye(open_wide, n=32):
    """뒤통수(스킨 뒷면) 한가운데 세로로 찢어진 외눈: 핏발 선 흰자에 세로 동공. 감기면 꿰맨 듯한 세로 금"""
    im = faces.skin_side("back").astype(np.float32)
    cx = 15.5
    for y in range(6, 27):
        v = (y - 16.5) / 10.0
        half = max(0.0, 1 - v * v) * (4.2 if open_wide == 2 else 2.6 if open_wide == 1 else 0.0)
        for x in range(n):
            d = abs(x + 0.5 - (cx + 0.5))
            if open_wide == 0:
                if d < 0.8 and 8 <= y <= 25:
                    im[y, x, :3] = (88, 28, 30)
                    if y % 3 == 0:
                        im[y, x - 1, :3] = (110, 38, 38)
                        im[y, x + 1, :3] = (110, 38, 38)
                continue
            if d <= half + 0.9 and d > half - 0.1 and half > 0:
                im[y, x, :3] = (92, 26, 28)  # 찢어진 가장자리(젖은 살)
            elif d <= half:
                white = (238, 230, 226)
                if (x * 7 + y * 3) % 11 == 0:
                    white = (196, 70, 70)  # 핏발
                im[y, x, :3] = white
                if d <= max(0.6, half * 0.32) and abs(v) < 0.75:
                    im[y, x, :3] = (40, 10, 12)  # 세로 동공
                if d <= 0.5 and -0.45 < v < -0.3:
                    im[y, x, :3] = (250, 246, 246)  # 젖은 반짝임
    im[..., 3] = 255
    out = np.clip(np.round(im), 0, 255).astype(np.uint8)
    out[..., 2] &= 0xF8
    return out


def build(k: kit.Kit):
    # 닻: 엉덩이와 다리(뒷걸음: 다리가 몸 앞(+Z) 쪽으로 차올렸다 내림)
    m = k.model(poses=8, period=16, mut2=dict(root=lambda side: (side * 0.1, 0.08, -0.08), dir=(0.5, 0.5, -0.8), length=0.5, poses=4))
    s = m.state("")
    s.box((-0.17, -0.1, -0.13), (0.17, 0.12, 0.13), "flesh", face=True)
    for side in (1, -1):
        w = kit.Wave(amp=(24, 18), lag=70, bias=(0, -10), phase=0 if side > 0 else 180)
        s.rod((side * 0.12, -0.06, 0), (side * 0.12, -1.0, 0), length=1.0, segs=2, thick=0.1, wave=w, toward=(0, 0, 1))
    # 머리: 앞얼굴이 뒤(-Z), 뒤통수가 앞(+Z)
    hm = k.model("g22_head", poses=1, mut2=False)
    for name, wide_closed, wide_open in (("", 1, 2), ("shut", 0, 0)):
        st = hm.state(name)
        st.head(size=HEAD, face="smile", rot=(0, 180, 0))
        tex_a = k.texture(f"backeye_{wide_closed}", back_eye(wide_closed))
        tex_b = k.texture(f"backeye_{wide_open}", back_eye(wide_open))
        zz = HEAD * 0.75 / 2 + 0.006
        st.quad((0, 0, zz), (HEAD, HEAD), "south", tex_a, face=True, show=("!open",))
        st.quad((0, 0, zz), (HEAD, HEAD), "south", tex_b, face=True, show=("open",))
    hm.default = ""
    # 목: 길이 1 블록 분홍 살 관(+Z), 굵기 0.13
    nm = k.model("g22_neck", poses=1, mut2=False, open=False, torn=False)
    nm.state("").box((-0.065, -0.065, 0.0), (0.065, 0.065, 1.0), "flesh", faces=("north", "south", "east", "west", "up", "down"),
                     uv=[0, 0, 16, 16], face=True)
    k.icon(model="g22_head", state="")


def crack(seed=0):
    """'뚝': 마른 관절이 꺾이는 짧은 소리"""
    c = audio.env(audio.highpass(audio.noise(0.05, seed), 1800), 0.0003, 0.006, 0.1, 0.02)
    t = audio.env(audio.tone(220, 0.05, "square", glide=120), 0.0003, 0.008, 0.1, 0.02) * 0.4
    return audio.normalize(audio.fade(audio.mix(c, t), 0.0005, 0.01), 0.7)


def squeak(seed=0, dur=0.55):
    """'끼익': 목이 고무줄처럼 늘어남"""
    t = np.linspace(0, 1, int(dur * audio.SR), dtype=np.float32)
    f = 260 + 900 * t ** 1.5
    ph = 2 * np.pi * np.cumsum(f) / audio.SR
    x = (np.sin(ph) + 0.3 * np.sign(np.sin(ph * 2))) * (0.5 + 0.5 * np.sin(t * 50 + seed) ** 2)
    x = audio.lowpass(x.astype(np.float32), 2600)
    return audio.normalize(audio.fade(audio.env(x, 0.02, 0.1, 0.8, 0.12)), 0.75)


def snap(seed=0):
    """'우두둑': 목뼈가 연달아 꺾임"""
    parts = [audio.at(crack(seed + i), 0.05 * i + 0.01 * (i % 2), 0.4) for i in range(5)]
    return audio.normalize(audio.mix(*parts), 0.85)


def sounds(k: kit.Kit):
    # 이 종의 신호: 뒷걸음 걸음마다 '뚝'
    k.sound("step", "뚝", synth=lambda i: crack(seed=100 + i), vanilla=[("mob/skeleton/step1", 0.6)], volume=0.7, attenuation=16)
    k.sound("stretch", "끼익", synth=lambda i: squeak(seed=104 + i), attenuation=20)
    k.sound("bite", "콱", synth=lambda i: audio.squish(seed=108 + i, dur=0.2),
            attenuation=16)
    k.sound("snap", "우두둑", synth=lambda i: snap(seed=112 + i * 7), attenuation=20)


def phrases():
    return {"cause_g22": "뒤통수 눈에 물렸다"}
