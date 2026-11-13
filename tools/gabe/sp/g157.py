"""G157 새끼 딸린 게이브: 둥근 _Gave 얼굴 밑에 얼굴보다 넓은 살 배, 짧고 굵은 엔드 막대기 다리 둘로 뒤뚱뒤뚱 걷고 배 양옆의 짧은 막대기
날개 둘이 걸음마다 파닥인다. 무리 가운데 하나는 절반 크기 금빛 새끼.

모델 gave:gabe/g157: 상태 ""(어미, 뒤뚱 4자세), "rage"(성남: 배를 깔고 엎드려 미끄러짐, 짙은 홍조에 눈 뜸, 날개를 뒤로 쭉 폄, 2자세),
"calf"(새끼: 절반 크기, 금빛 홍조, 뒤뚱 4자세). 몸 가운데 = 배 가운데. 바닥: 어미와 새끼 -0.52 / -0.3(Java hover 0.52, 0.3), 성남 -0.3.
"""
from gabe import faces, kit

LEG = kit.Wave(amp=24, lag=0)
WING = kit.Wave(amp=30, lag=0, bias=-10, shape="flap")
RAGE = faces.Face(eyes="open", blush="deep")


def walker(s, k_size, face):
    """어미와 새끼(크기 k_size 배): 배 가운데가 (0, 0, 0), 바닥 = -0.52 x k"""
    c = k_size
    s.head(size=0.8 * c, depth=0.62 * c, at=(0, 0.5 * c, 0.06 * c), face=face)
    # 얼굴보다 넓은 둥근 배(살)
    s.box((-0.52 * c, -0.27 * c, -0.4 * c), (0.52 * c, 0.12 * c, 0.42 * c), "flesh", face=True)
    s.box((-0.4 * c, 0.12 * c, -0.32 * c), (0.4 * c, 0.2 * c, 0.34 * c), "flesh", face=True)
    for side in (1, -1):
        # 짧고 굵은 다리(앞뒤로 번갈아)
        w = kit.Wave(amp=LEG.amp, lag=0, phase=0 if side > 0 else 180)
        s.rod((side * 0.22 * c, -0.25 * c, 0.02 * c), (0, -1, 0.05), length=0.27 * c, segs=1, thick=0.15 * c, wave=w,
              toward=(0, 0, 1), poses=4, rag=False)
        # 배 양옆 날개(짧은 막대기, 파닥)
        ww = kit.Wave(amp=WING.amp, lag=0, bias=WING.bias, shape="flap", phase=0 if side > 0 else 180)
        s.rod((side * 0.5 * c, 0.02 * c, 0.0), (side * 1.0, -0.55, -0.1), length=0.36 * c, segs=1, thick=0.1 * c, wave=ww,
              toward=(0, 1, 0), poses=4)


def slider(s):
    """성남: 배를 깔고 엎드려 앞으로 미끄러짐. 얼굴은 앞을 봄, 다리는 뒤로, 날개는 뒤로 쭉"""
    s.head(size=0.8, depth=0.62, at=(0, 0.14, 0.36), face=RAGE)
    s.box((-0.52, -0.3, -0.75), (0.52, 0.06, 0.12), "flesh", face=True)
    for side in (1, -1):
        s.rod((side * 0.22, -0.18, -0.72), (side * 0.1, 0.1, -1), length=0.3, segs=1, thick=0.15, rag=False)
        w = kit.Wave(amp=8, lag=0, bias=0, phase=0 if side > 0 else 180)
        s.rod((side * 0.5, -0.02, -0.2), (side * 0.45, 0.08, -1), length=0.5, segs=1, thick=0.1, wave=w, toward=(0, 1, 0), poses=2)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, mut2=False, torn=False)
    walker(m.state(""), 1.0, "smile")
    slider(m.state("rage", poses=2, period=6))
    walker(m.state("calf"), 0.55, "gold")


def sounds(k: kit.Kit):
    # 이 종의 신호: 새끼의 가는 울음(무리보다 먼저 들림)
    k.sample("cry", "tag:cute", "새끼 우는 소리", pitch=1.35, attenuation=24)
    # 새끼가 맞자 무리가 한꺼번에 성남
    k.sample("rage", "tag:growl-boss_roar-far_roar", "무리가 성남", pitch=1.25, attenuation=32)
    # 부딪치기, 배 미끄럼 들이받기
    k.sample("bump", "kenney_impact/impactsoft_heavy_00*", "쿵", pitch=1.0, attenuation=16)


def phrases():
    return {"cause_g157": "성난 무리에 깔렸다", "notice_g157_calm": "새끼가 달아났다"}
