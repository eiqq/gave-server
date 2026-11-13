"""G82 발자국 게이브: 몸은 안 보이고 땅에 찍히는 발자국(발바닥 무늬가 실눈과 점 입)만 보인다. 발자국 위 허공에 가는 엔드 막대기 다리 둘이
점묘로 흐리게 비친다. 드러나면 0.6 블록 얼굴이 긴 죽마 다리 둘 위에 얹힌 꼴.

모델 g82 상태: "ghost"(점묘 다리만, 걷기 4자세), ""(드러남: 얼굴 + 죽마 다리, 걷기 4자세).
모델 g82_print 상태: ""(갓 찍힌 젖은 발자국), "dry"(마른 발자국). Java 가 효과 디스플레이에 실어 땅에 2초 남긴다.
"""
import numpy as np

from gabe import kit

FACE = 0.6
LEG = 1.1
TOP = -FACE / 2 + 0.02


def dotted_rod():
    """점묘 다리: 엔드 막대기 배치(옆 u 0..2, 끝 u 2..4)에 띄엄띄엄 찍힌 흰 점(나머지 투명)"""
    im = np.zeros((16, 16, 4), np.uint8)
    for v in range(16):
        for u in range(2):
            if (v * 3 + u * 2) % 5 == 0:
                im[v, u] = (236, 232, 246, 255)
    im[0, 2] = (236, 232, 246, 255)
    im[1, 3] = (236, 232, 246, 255)
    return im


def print_tex(wet):
    """발바닥 무늬: 앞꿈치가 넓고 뒤꿈치가 좁은 진흙 발자국, 그 안에 실눈 두 줄과 점 입이 옅게 눌려 있다(v 0 이 발끝)"""
    n = 16
    im = np.zeros((n, n, 4), np.uint8)
    mud = np.array((58, 44, 42) if wet else (112, 94, 84), float)
    rim = np.array((40, 30, 30) if wet else (92, 76, 68), float)
    for v in range(n):
        half = 6.2 - 2.6 * (v / (n - 1)) ** 1.4
        if v < 2:
            half -= (2 - v) * 1.6
        for u in range(n):
            dx = abs(u + 0.5 - n / 2)
            if dx <= half:
                edge = dx > half - 1.0 or v == 0 or v == n - 1
                c = rim if edge else mud * (0.94 + 0.06 * np.sin(u * 1.3 + v))
                im[v, u] = (*np.clip(c, 0, 255).astype(np.uint8), 255)
    line = (206, 150, 150, 255) if wet else (176, 136, 128, 255)
    # 실눈(^ ^) 두 줄: 발끝 쪽
    for x0 in (4, 9):
        im[5, x0] = line
        im[4, x0 + 1] = line
        im[4, x0 + 2] = line
        im[5, x0 + 3] = line
    # 점 입: 뒤꿈치 쪽
    im[11, 7:9] = line
    im[12, 7:9] = line
    if wet:
        # 젖은 반짝임 두 점
        im[3, 6] = (240, 226, 226, 255)
        im[9, 10] = (226, 208, 208, 255)
    # 판의 v 0 이 모델 뒤(-Z)로 가므로 뒤집어 발끝(실눈)이 앞(+Z)을 보게
    return im[::-1].copy()


def legs(s, tex, wave_amp):
    for side, ph in ((1, 0), (-1, 180)):
        s.rod((side * 0.13, TOP, 0.0), (side * 0.08, -1.0, 0.0), length=LEG, segs=2, thick=0.07, tex=tex, base=False,
              wave=kit.Wave(amp=(wave_amp, wave_amp * 0.6), lag=30, phase=ph), toward=(0, 0, 1), rag=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=12, mut2=False, torn=False)
    ghost = k.texture("ghost_leg", dotted_rod())
    s = m.state("")
    s.head(size=FACE, depth=0.5, face="smile")
    legs(s, "rod", 24)
    g = m.state("ghost")
    legs(g, ghost, 24)
    g.front = (0, 0, 1)

    p = k.model("g82_print", poses=1, mut2=False, open=False, torn=False, rag=False)
    for st, wet in (("", True), ("dry", False)):
        q = p.state(st)
        q.quad((0, 0.0, 0), (0.34, 0.56), "up", k.texture("print_wet" if wet else "print_dry", print_tex(wet)))
        q.front = (0, 0, 0)


def sounds(k: kit.Kit):
    # 내 발소리 뒤에 반 박자 늦게 붙는 젖은 '찰박'(이 종 신호): 발소리와 짧은 물 튀김을 낮게
    k.sample("step", ["kenney_impact/footstep_concrete_000", "oga_water40/splash_09", "oga_water40/splash_10"], "따라오는 발소리",
             pitch=0.8, attenuation=10)


def phrases():
    return {"cause_g82": "등 뒤 발자국이 덮쳤다"}
