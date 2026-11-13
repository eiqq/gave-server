"""G108 죽은 척 게이브: 래그돌 사체처럼 하늘을 보고 누운 0.8 블록 얼굴(진짜 사체도 얼굴이 하늘을 보게 굳는다), 엔드 막대기 둘은 팔다리처럼
아무렇게나 벌려 땅에 늘어져 있고, 얼굴 뒤로 살 몸통이 납작 깔렸다. 등이 아주 느리게 오르내린다.
깨면 막대기 넷으로 땅을 짚고 벌떡 일어나 네 발로 달린다.

상태: "sleep"(기본: 누움, 숨쉬기 2자세), "up"(네 발 달리기 4자세).
"""
from gabe import kit

FACE = 0.8
DEPTH = 0.6


def build(k: kit.Kit):
    m = k.model(poses=4, period=40, mut2=False, torn=False)

    s = m.state("sleep")
    # 숨쉬기: 몸통과 얼굴이 아주 조금 오르내림(2자세, 40틱 한 바퀴)
    for f in s.frames(poses=2):
        with f:
            dy = 0.035 * f.k
            s.head(size=FACE, depth=DEPTH, at=(0, dy, 0), face="smile", rot=(-90, 0, 0))
            s.box((-0.3, -0.26, -0.95 + 0.0), (0.3, -0.04 + dy, -0.4), "flesh", face=True)
    # 늘어진 막대기 둘(팔다리처럼 아무렇게나)
    s.rod((0.36, -0.2, -0.1), (1.0, -0.05, 0.35), length=0.75, segs=2, thick=0.1)
    s.rod((-0.3, -0.2, -0.55), (-0.8, -0.05, -0.6), length=0.7, segs=2, thick=0.1)
    s.front = (0, 1, 0)

    u = m.state("up")
    u.head(size=FACE, depth=DEPTH, at=(0, 0.1, 0.12), face="smile")
    u.box((-0.3, -0.18, -0.95), (0.3, 0.2, -0.18), "flesh", face=True)
    for i, (sx, z) in enumerate(((1, 0.05), (-1, 0.05), (1, -0.75), (-1, -0.75))):
        ph = 0 if i in (0, 3) else 180
        u.rod((sx * 0.3, -0.15, z), (sx * 0.35, -1.0, 0.1), length=0.62, segs=2, thick=0.1,
              wave=kit.Wave(amp=(30, 20), lag=40, phase=ph, cycles=4), toward=(0, 0, 1))
    m.default = "sleep"


def sounds(k: kit.Kit):
    # 웅크려 들으면 들리는 코 고는 소리(이 종 신호)
    k.sample("snore", "tag:snore", "코 고는 소리", pitch=0.9, attenuation=12, n=2)
    # 깨어 벌떡 일어남
    k.sample("wake", "tag:snarl", "벌떡 일어나는 소리", pitch=1.1, attenuation=16)


def phrases():
    return {"cause_g108": "죽은 척하던 것이 물었다"}
