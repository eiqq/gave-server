"""G88 백눈 호저 게이브: 엔드 막대기 다리 넷으로 걷는 1.2 블록 몸통 얼굴, 등에 엔드 막대기 가시 열둘이 부채처럼 곧추서고
가시 끝마다 손톱만 한 _Gave 얼굴(깨어 있으면 눈 뜸, 자면 실눈). 세운 높이 2.6.

- gave:gabe/g88: 상태 ""(걷기 2자세, 가시 열둘은 자세 없음), "ball"(몸을 말아 구름: Java 가 pitch 로 굴림).
  부위 eye1..eye12(그 가시 얼굴 눈 뜸), dead1..dead12(꺾인 가시: 가시와 얼굴이 사라지고 꺾인 그루터기).
  가시 i(1..12)의 뿌리와 방향은 SPINES 표(Java G88.SPINE_* 와 같게).
- gave:gabe/g88_needle(부품): 날아가는 가시 바늘(앞 = 날아가는 쪽).
"""
import math

from gabe import kit

FACE = 1.2
SPINE_LEN = 1.1
TIP = 0.3


def spines():
    """가시 12개: (뿌리, 단위 방향). 몸 기준(+X 왼쪽, +Y 위, +Z 앞). 부채: -78..78 도"""
    out = []
    for i in range(12):
        th = math.radians(-78 + 156 * i / 11)
        root = (math.sin(th) * 0.42, 0.52 + math.cos(th) * 0.06, -0.35)
        d = (math.sin(th), math.cos(th), -0.3)
        n = math.sqrt(sum(c * c for c in d))
        out.append((root, tuple(c / n for c in d)))
    return out


SPINES = spines()


def body(s, k, walk=True):
    s.head(size=FACE, depth=0.75, at=(0, 0.0, 0.6), face="smile")
    s.box((-0.66, -0.5, -1.0), (0.66, 0.56, 0.24), "flesh", uv=[0, 0, 16, 16], face=True)
    # 다리 넷(걸음 2자세, 대각선끼리 엇갈림)
    for (x, z, ph) in ((0.5, 0.15, 0), (-0.5, 0.15, 180), (0.5, -0.75, 180), (-0.5, -0.75, 0)):
        side = 1 if x > 0 else -1
        s.rod((x, -0.42, z), (side * 0.3, -1.0, 0.0), length=0.58, segs=1, thick=0.15, base=False, poses=2,
              wave=kit.Wave(amp=24, phase=ph) if walk else None, toward=(0, 0, 1))


def fan(s, k):
    open_tex = k.face_tex("open")
    for i, (root, d) in enumerate(SPINES, start=1):
        tip = tuple(root[j] + d[j] * SPINE_LEN for j in range(3))
        with s.when(f"!on:dead{i}"):
            s.rod(root, d, length=SPINE_LEN, segs=1, thick=0.09, base=False)
            at = (tip[0], tip[1] + TIP * 0.3, tip[2] + 0.02)
            s.head(size=TIP, depth=0.16, at=at, face="smile", near=False, open=False, torn=False)
            with s.when(f"on:eye{i}"):
                s.quad((at[0], at[1], at[2] + 0.08 + 0.012), (TIP, TIP), "south", open_tex, face=True)
        with s.when(f"on:dead{i}"):
            # 꺾인 그루터기(뿌리에서 짧게, 옆으로 꺾임)
            bend = (d[0] + (0.8 if d[0] >= 0 else -0.8), d[1] * 0.3, d[2] - 0.4)
            s.rod(root, bend, length=0.32, segs=1, thick=0.09, base=False)


def build(k: kit.Kit):
    m = k.model(poses=2, period=12, shrink=0.7, mut2=False)
    s = m.state("")
    body(s, k)
    fan(s, k)

    # 말아 구름: 가시가 사방으로 선 둥근 덩어리
    b = m.state("ball", poses=1)
    b.box((-0.72, -0.72, -0.72), (0.72, 0.72, 0.72), "flesh", uv=[0, 0, 16, 16], face=True)
    b.head(size=0.9, depth=0.3, at=(0, 0, 0.66), face="smile")
    for i in range(12):
        a = math.radians(i * 30)
        for tilt in (-0.55, 0.55) if i % 2 else (0.0,):
            d = (math.cos(a), math.sin(a), tilt)
            root = tuple(c * 0.55 for c in d[:2]) + (tilt * 0.5,)
            b.rod(root, d, length=0.7, segs=1, thick=0.08, base=False)
    b.front = (0, 0, 1)

    nd = k.model("g88_needle", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    ns = nd.state("")
    ns.rod((0, 0, -0.35), (0, 0, 1), length=0.7, segs=1, thick=0.05, base=False)
    ns.front = (0, 0, 0)
    k.icon(model="g88", state="")


def sounds(k: kit.Kit):
    k.sample("needle", "tag:blade", "가시 바늘", pitch=1.2, attenuation=24)
    k.sample("blink", "tag:cloth", "눈 감는 소리", pitch=1.3, volume=0.8, attenuation=20)
    k.sample("bump", "tag:impact+heavy", "가시 덩이 부딪힘", pitch=0.9, attenuation=20)
    k.sample("snap", "tag:wood+breaking", "가시 꺾임", pitch=1.2, attenuation=20)


def phrases():
    return {"cause_g88": "가시 바늘에 꿰였다", "cause_g88_roll": "가시 덩이에 받혔다"}
