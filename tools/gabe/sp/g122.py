"""G122 호리병 게이브: 위아래 두 얼굴이 잘록한 허리로 이어진 조롱박 꼴 1.8 블록 몸(위 얼굴은 작고 아래 얼굴은 크다). 허리에 살 띠 끈을 둘러
맸고, 위 얼굴 정수리에 짧은 목과 캄캄한 아가리, 엔드 막대기 하나가 아가리 마개로 비스듬히 꽂혔다. 다른 막대기 하나는 옆구리에 손잡이처럼 굽어
붙었다(위아래 두 마디).

- gave:gabe/g122: 상태 ""(마개 닫힘), "open"(마개를 뽑아 허리끈에 매달고 캄캄한 아가리가 열림, 마개가 대롱거림: 자세 2).
  Java 는 부를 때 몸을 앞으로 기울여(pitch) 아가리를 그 사람 쪽으로 향하고, 빨아들일 때 몸을 늘인다(stretch).
소리 call(병에서 나는 목소리, 12), pop(마개 뽑힘, 12), suck(빨아들임, 16), ram(뒤통수 박치기, 16). 글귀 cause_g122, notice_g122_call.
"""
import numpy as np

from gabe import faces, kit


def dark_tex(n=8):
    a = np.zeros((n, n, 4), np.uint8)
    for y in range(n):
        for x in range(n):
            d = max(abs(x - (n - 1) / 2), abs(y - (n - 1) / 2)) / (n / 2)
            a[y, x, :3] = (int(10 + 40 * d ** 3), int(4 + 16 * d ** 3), int(8 + 18 * d ** 3))
    a[..., 3] = 255
    return a


def gourd(s, k, opened):
    dark = k.texture("maw", dark_tex())
    # 아래 큰 얼굴, 위 작은 얼굴
    s.head(size=1.05, depth=0.95, at=(0, -0.4, 0), face="smile")
    s.head(size=0.62, depth=0.58, at=(0, 0.52, 0), face=faces.Face(eyes="half", mouth="dot", blush="pink"), near=False, torn=False)
    # 잘록한 허리(살)와 허리끈(살 띠를 네 면에 둘러 맴)
    s.box((-0.22, 0.08, -0.22), (0.22, 0.24, 0.22), "flesh", face=True)
    s.box((-0.27, 0.1, -0.27), (0.27, 0.19, 0.27), "wetskin", face=True)
    # 목과 아가리(정수리)
    s.box((-0.16, 0.83, -0.16), (0.16, 0.98, 0.16), "flesh", face=True)
    s.box((-0.12, 0.985, -0.12), (0.12, 0.985, 0.12), dark, uv=[0, 0, 16, 16], faces=("up",))
    if opened:
        # 뽑힌 마개: 허리끈에 매달려 대롱거림
        s.rod((0.27, 0.14, 0.12), (0.4, -1, 0.3), length=0.42, segs=1, thick=0.08, wave=kit.Wave(amp=18, cycles=1), toward=(0, 0, 1),
              poses=2, base=False, rag=False)
        # 아가리 테두리 이(뼈)
        s.teeth((0, 0.99, 0), 0.13, count=8, axis=(0, 1, 0), length=0.06, size=0.03, inward=0.5)
    else:
        # 마개: 아가리에 비스듬히 꽂힌 막대기
        s.rod((0.0, 0.9, 0.0), (0.45, 1, 0.2), length=0.42, segs=1, thick=0.1, toward=(0, 0, 1), base=False, rag=False)
    # 옆구리 손잡이: 위 얼굴 옆에서 굽어 아래 얼굴 옆으로(두 마디)
    s.rod((-0.3, 0.55, 0.0), (-0.7, -0.45, 0), length=0.82, segs=2, thick=0.09, toward=(1, 0, 0), wave=kit.Wave(amp=0, bias=(0, 48)),
          poses=1, base=True, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=2, period=12, mut2=dict(root=lambda side: (side * 0.36, -0.1, -0.3), dir=(0, -0.4, -1), length=0.45, segs=1, poses=2))
    gourd(m.state(""), k, False)
    gourd(m.state("open"), k, True)
    k.icon(model="g122", state="")


def sounds(k: kit.Kit):
    k.sample("call", ["oga_creature80b/human_03", "oga_creature80b/human_07", "oga_creature80b/human_01"], "병에서 나는 목소리", pitch=0.7,
             attenuation=12)
    k.sample("pop", "kenney_interface/click_00*", "마개 뽑히는 소리", pitch=0.6, attenuation=12)
    k.sample("suck", "tag:breath", "빨아들이는 숨", pitch=0.6, attenuation=16)
    k.sample("ram", "kenney_impact/impactpunch_heavy_00*", "뒤통수 박치기", pitch=0.9, attenuation=16)


def phrases():
    return {"cause_g122": "호리병에 빨려 들어갔다", "cause_g122_ram": "호리병에 뒤통수를 박혔다", "notice_g122_call": "거기 너"}
