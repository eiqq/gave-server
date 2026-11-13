"""G32 웃음 옮는 게이브: 물뿌리개. 0.8 블록 얼굴(점 입)의 입에서 굵은 살 주둥이가 턱 밑으로 늘어졌다가 앞으로 휘고, 끝에 벌집처럼 구멍
일곱이 뚫린 둥근 살 꼭지가 앞을 본다(정면에서는 턱 밑에 매달린 둥근 꼭지). 구멍마다 분홍 살이 들썩인다(웃음을 뿌리고 다니는 물뿌리개). 얼굴 양옆 짧은 지느러미 막대기 둘(원형 DNA).

상태: ""(기본 펄럭임, 주둥이가 느리게 출렁, 20틱 한 바퀴), "swoop"(스치며 지나갈 때: 주둥이를 턱 밑으로 말아 올리고 지느러미를 뒤로 젖혀
같은 주기에 두 번 침).
실눈 뜸(표적), 입 찢어짐(변이 1), 변이 2 막대기는 키트가 저절로 붙임.
"""
import math

import numpy as np

from gabe import audio, faces, kit

SIZE, DEPTH = 0.8, 0.55
ROSE = 0.5  # 꼭지 지름(블록)
# 꼭지 앞면 구멍 자리(16 화소 그림, 가운데와 둘레 여섯)와 반지름
HOLES = [(7.5, 7.5, 1.6)] + [(7.5 + 4.2 * math.cos(math.radians(a)), 7.5 + 4.2 * math.sin(math.radians(a)), 1.25) for a in range(30, 390, 60)]
# 상태마다: 주둥이 처음 방향, 마디 휨(도), 물결 폭, 지느러미 젖힘, 빠르기 배
SPOUT = {"": ((0, -1.0, 0.12), (8, 34, 40), (5, 6, 7), -0.42, 1),
         "swoop": ((0, -1.0, 0.35), (44, 56, 52), (6, 8, 8), -0.95, 2)}


def rose_tex(n=16):
    """꼭지 앞면: 둥근 살판(가장자리 짙은 테), 벌집 구멍 일곱(검붉은 속, 아래쪽 반짝임). 둥근 판 밖은 투명"""
    im = np.zeros((n, n, 4), np.uint8)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    r = np.hypot(xx - 7.5, yy - 7.5)
    skin = np.array(faces.hexrgb(faces.PAL["S"][0]), float)
    col = skin * (0.95 - 0.12 * np.clip(r / 8, 0, 1))[..., None]
    im[..., :3] = np.clip(col, 0, 255)
    im[r < 7.7, 3] = 255
    im[(r > 6.4) & (r < 7.7), :3] = (206, 122, 126)
    for (cx, cy, hr) in HOLES:
        d = np.hypot(xx - cx, yy - cy)
        im[d < hr + 0.5, :3] = (176, 84, 92)
        im[d < hr, :3] = (66, 20, 26)
        im[(d < hr) & (yy > cy + hr * 0.3), :3] = (110, 36, 44)
    return im


def unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def can(s, k, name, fin, cycles):
    first, bend, amp, sweep, cyc = SPOUT[name]
    h = s.head(size=SIZE, depth=DEPTH, face="smile")
    for side in (1, -1):
        w = kit.Wave(amp=fin, lag=50, shape="flap", cycles=cycles, phase=0 if side > 0 else 30)
        s.rod(h.point(side * 0.92, -0.2, -0.55), (side * 1.0, 0.05, sweep), length=0.8, segs=2, thick=0.09, wave=w, poses=4)
    # 살 주둥이(입에서): 아래 앞으로 늘어졌다 앞으로 휘어 오름(마디마다 위로 휨), 죽어도 몸에 붙은 채(끝에 꼭지 판이 있어서)
    ch = s.rod(h.point(0, -0.5, 0.92), first, length=0.82, segs=3, thick=0.17, tex="flesh",
               wave=kit.Wave(amp=amp, lag=60, bias=bend, cycles=cyc), toward=(0, 1, 0), poses=4, rag=False)
    rose = k.texture("rose", rose_tex())
    flesh = k.part_tex("flesh")
    ep = 1.0 / 16
    for f in s.frames(4):
        with f:
            tip = np.array(ch.tip(f.k))
            d = unit(ch.dirs[f.k][-1])
            pitch = math.degrees(math.atan2(-d[1], d[2]))  # 앞(+Z)을 끝 방향 d 로 돌리는 x 회전
            c = tip + d * 0.03
            rot = dict(rot=(pitch, 0, 0), origin=tuple(c))
            s.box(tuple(c - (ROSE / 2, ROSE / 2, 0.04)), tuple(c + (ROSE / 2, ROSE / 2, 0.04)),
                  {"south": rose, "north": flesh, "east": flesh, "west": flesh, "up": flesh, "down": flesh},
                  uv={"south": [0, 0, 16, 16], "north": [0, 0, 16, 16], "east": [0, 0, 2, 16], "west": [0, 0, 2, 16], "up": [0, 0, 16, 2],
                      "down": [0, 0, 16, 2]}, face=True, **rot)
            # 구멍마다 들썩이는 분홍 살: 구멍 넷이 번갈아 볼록
            for i, (hx, hy, hr) in enumerate(HOLES):
                if (f.k + i * 3) % 4 >= 2:
                    continue
                u, v = (hx - 7.5) / 16 * ROSE, (7.5 - hy) / 16 * ROSE
                dd = hr * 2 / 16 * ROSE * (0.95 if (f.k + i) % 2 else 0.75)
                s.quad((c[0] + u, c[1] + v, c[2] + 0.04 + 0.3 * ep), (dd, dd), "south", flesh, uv=(2 + i, 3, 6 + i, 7), face=True, **rot)
    return h


def build(k: kit.Kit):
    m = k.model(poses=8, period=20)
    can(m.state(""), k, "", (20, 18), 1)
    can(m.state("swoop"), k, "swoop", (26, 22), 2)


def inhale(seed=0, dur=0.3):
    """히이: 구멍 여럿이 한꺼번에 들이쉬는 짧은 숨(높아지는 바람 소리 여러 줄기)"""
    n = audio.noise(dur, seed)
    t = np.linspace(0, 1, len(n), dtype=np.float32)
    shape = np.clip(t / 0.75, 0, 1) ** 1.5 * np.clip((1 - t) / 0.12, 0, 1)
    air = audio.bandpass(n, 1500, 5200) * shape
    pipes = sum(audio.tone(1250 + 310 * i + seed * 9, dur, glide=1750 + 380 * i) * 0.08 for i in range(3)) * shape
    return audio.normalize(audio.fade(air * 0.9 + pipes, 0.01, 0.02), 0.72)


def nip(seed=0):
    """덥석: 스치며 무는 짧고 젖은 소리"""
    a = audio.env(audio.bandpass(audio.noise(0.08, seed), 700, 3200), 0.001, 0.015, 0.2, 0.04)
    b = audio.env(audio.tone(310, 0.09, "tri", glide=150), 0.001, 0.02, 0.3, 0.04) * 0.7
    return audio.normalize(audio.fade(audio.mix(a, b), 0.001, 0.02), 0.85)


def sounds(k: kit.Kit):
    # 이 종의 신호: 날 때마다 구멍들에서 한꺼번에 나는 짧은 들숨
    k.sound("inhale", "히이", synth=lambda i: inhale(seed=320 + i), volume=0.85, attenuation=20)
    k.sound("bite", "덥석", synth=lambda i: nip(seed=324 + i), attenuation=16)
    # 물린 사람의 발소리, 총소리에 섞여 나는 작은 킥킥(그 사람 자리에서 남들에게 들림). 웃음 옮는 종이라 웃음 허용
    k.sound("giggle", "옮은 킥킥", synth=lambda i: audio.giggle(seed=328 + i, count=4 + i % 2, base=650 + 30 * i, rate=10.0), volume=0.7,
            attenuation=24)


def phrases():
    return {"cause_g32": "벌집 입에 물렸다", "notice_g32_laugh": "웃음이 옮았다"}
