"""G84 오르골 게이브: 0.9 블록 나무빛 오르골 상자. 뚜껑이 _Gave 얼굴이고, 그 위 핀에 손바닥만 한 작은 얼굴 하나가 발레리나처럼 빙글 돈다.
옆구리(+X)의 엔드 막대기 하나는 태엽 손잡이로 돌고, 상자 밑에 엔드 막대기 다리 넷이 접혀 있다.

상태: "box"(기본: 작은 얼굴 회전, 태엽 손잡이 돎, 다리 접힘, 4자세), "open"(뚜껑 얼굴이 뒤로 젖혀져 눈을 뜨고, 다리 넷이 펴져 달림, 4자세).
"""
import numpy as np

from gabe import kit

BW = 0.9           # 상자 폭
BOT, TOP = -0.45, 0.05
LID = 0.34


def wood_tex(seed=84):
    """칠한 오르골 나무: 붉은 갈색 결 + 금빛 테"""
    rng = np.random.RandomState(seed)
    n = 16
    im = np.zeros((n, n, 4), np.uint8)
    for v in range(n):
        for u in range(n):
            g = 0.88 + 0.12 * np.sin(v * 0.9 + np.sin(u * 0.4) * 2)
            c = np.array((150, 82, 60)) * g + rng.randn(3) * 2
            if v in (1, 14):
                c = np.array((214, 176, 92))
            im[v, u] = (*np.clip(c, 0, 255).astype(np.uint8), 255)
    return im


def box(s, wood):
    s.box((-BW / 2, BOT, -BW / 2), (BW / 2, TOP, BW / 2), wood)


def crank(s):
    """태엽 손잡이: 옆구리 축 + 도는 손잡이(자세마다 90도)"""
    s.rod((BW / 2, -0.2, 0), (1, 0, 0), length=0.16, segs=1, thick=0.07, base=False, toward=(0, 1, 0))
    for f in s.frames():
        with f:
            a = f.t * 2 * np.pi
            s.rod((BW / 2 + 0.15, -0.2, 0), (0, np.cos(a), np.sin(a)), length=0.3, segs=1, thick=0.07, base=False,
                  toward=(1, 0, 0))


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, mut2=False, torn=False)
    wood = k.texture("wood", wood_tex())

    # 닫힌 상자: 뚜껑 얼굴, 도는 작은 얼굴, 태엽, 접힌 다리
    s = m.state("box")
    box(s, wood)
    s.head(size=BW - 0.04, depth=BW - 0.04, height=LID, at=(0, TOP + LID / 2, 0), face="smile", sides=wood)
    s.rod((0, TOP + LID, 0), (0, 1, 0), length=0.16, segs=1, thick=0.05, base=False, toward=(0, 0, 1))
    for f in s.frames():
        with f:
            s.head(size=0.22, depth=0.16, at=(0, TOP + LID + 0.27, 0), face="smile", near=False, open=False, torn=False,
                   rot=(0, f.t * 360, 0))
    crank(s)
    for sx in (1, -1):
        for sz in (1, -1):
            s.rod((sx * 0.3, BOT + 0.02, sz * 0.3), (-sx, 0, -sz * 0.2), length=0.34, segs=1, thick=0.06, base=False)

    # 열림: 뚜껑 얼굴이 뒤 경첩에서 젖혀지고 눈을 뜸, 다리 넷이 펴져 달림
    o = m.state("open")
    box(o, wood)
    o.head(size=BW - 0.04, depth=BW - 0.04, height=LID, at=(0, TOP + LID / 2, 0), face="open", sides=wood,
           rot=(-58, 0, 0))
    crank(o)
    for i, (sx, sz) in enumerate(((1, 1), (-1, 1), (1, -1), (-1, -1))):
        ph = 0 if i in (0, 3) else 180
        o.rod((sx * 0.36, BOT + 0.02, sz * 0.36), (sx * 0.55, -1.0, sz * 0.25), length=0.7, segs=2, thick=0.07, base=False,
              wave=kit.Wave(amp=(28, 18), lag=40, phase=ph, cycles=2), toward=(0, 0, 1))
    m.default = "box"


def sounds(k: kit.Kit):
    # 다가오는 동안 흐르는 오르골 곡(받은 짧은 곡을 높여 장난감처럼)
    k.sample("tune", "tag:jingle", "오르골 곡", pitch=1.3, volume=0.7, attenuation=24)
    # 곡이 끝난 뒤 '철컥'(이 종 신호)
    k.sample("latch", "tag:spring", "오르골 태엽", pitch=0.9, attenuation=24)


def phrases():
    return {"cause_g84": "오르골에 물렸다"}
