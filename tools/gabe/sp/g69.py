"""G69 털 뭉치 게이브: 0.6 블록 얼굴 둘레에 짧은 엔드 막대기 털 열여섯이 사방으로 뻗은 보송한 털공. 털 끝마다 분홍 솜털 뭉치.
젖으면 털이 착 달라붙고 털 사이로 작은 얼굴 다섯이 비친다. 굴림은 Java(몸 pitch 를 계속 돌림).

모델 gave:gabe/g69: 상태 ""(마른 털공), "wet"(물웅덩이에서 젖음: 털이 몸에 달라붙고 작은 얼굴 다섯), "small"(갈라져 나온 작은 젖은 털공).
"""
import math

from gabe import kit

HEAD = 0.6
FUR = 16


def spread(n, seed_off=0.5):
    """구 위에 고르게(피보나치) n 방향, 얼굴 앞(+Z 가운데)은 비움"""
    golden = math.pi * (3 - math.sqrt(5))
    for m in range(n, n * 3):
        pts = []
        for i in range(m):
            y = 1 - (i + seed_off) / m * 2
            r = math.sqrt(max(0.0, 1 - y * y))
            a = golden * i
            d = (math.cos(a) * r, y, math.sin(a) * r)
            if d[2] > 0.55 and abs(d[0]) < 0.7 and abs(d[1]) < 0.7:
                continue
            pts.append(d)
        if len(pts) >= n:
            return pts[:n]
    return pts


def surface(d, half):
    """얼굴 상자(정육면체 반 변 half) 표면 위, d 쪽 점"""
    m = max(abs(d[0]), abs(d[1]), abs(d[2]))
    return tuple(c / m * half for c in d)


def fur(s, dirs, half, length, tip, wet):
    for d in dirs:
        p = surface(d, half * 0.92)
        if wet:
            # 젖은 털: 표면을 따라 뒤로 눕고 짧다
            back = (d[0] * 0.35, d[1] * 0.35 - 0.1, d[2] * 0.35 - 0.9)
            s.rod(p, back, length=length, segs=1, thick=0.07, base=False, rag=False)
        else:
            s.rod(p, d, length=length, segs=1, thick=0.08, base=False, rag=False)
            e = tuple(p[i] + d[i] * (length + tip * 0.3) for i in range(3))
            s.box(tuple(c - tip / 2 for c in e), tuple(c + tip / 2 for c in e), "flesh", face=True)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, mut2=False, torn=False)
    dirs = spread(FUR)
    s = m.state("")
    s.head(size=HEAD, depth=HEAD, face="smile")
    fur(s, dirs, HEAD / 2, 0.3, 0.13, False)

    w = m.state("wet")
    w.head(size=HEAD, depth=HEAD, face="half")
    fur(w, dirs, HEAD / 2, 0.2, 0.0, True)
    # 털 사이로 비치는 작은 얼굴 다섯(왼, 오른, 위, 아래, 뒤)
    for at, rot in (((0.31, 0.05, 0.02), (0, 90, 0)), ((-0.31, -0.04, 0.0), (0, -90, 0)), ((0.04, 0.31, -0.03), (-90, 0, 0)),
                    ((-0.05, -0.31, 0.02), (90, 0, 0)), ((0.02, 0.03, -0.31), (0, 180, 0))):
        w.head(size=0.18, depth=0.04, at=at, face="smile", near=False, open=False, torn=False, rot=rot)

    sm = m.state("small")
    sm.head(size=0.34, depth=0.34, face="smile_c")
    fur(sm, spread(8, 0.3), 0.17, 0.12, 0.0, True)


def sounds(k: kit.Kit):
    # 굴러올 때 털 비비는 부스스(이 종 전용 신호)
    k.sample("fluff", "kenney_rpg/cloth*", "털 비비는 소리", pitch=0.8, volume=0.9, attenuation=16)
    # 물웅덩이에서 젖어 갈라짐
    k.sample("split", "oga_water40/splash_0*", "퍽 젖어 갈라짐", pitch=0.9, attenuation=24)
    # 부딪혀 물기
    k.sample("bite", "oga_creature80/eat_0*", "물기", pitch=1.3, volume=0.8, attenuation=12)


def phrases():
    return {"cause_g69": "털 뭉치에 물렸다"}
