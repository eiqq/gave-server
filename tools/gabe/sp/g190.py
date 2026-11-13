"""G190 초침 게이브(4막 32라운드, 방해 + 정예): 한 사람 머리 위 9블록에 떠 있는 0.55블록 얼굴 하나와 그 둘레의 짧은 엔드 막대기 열둘(시계 눈금, 폭 0.9).

임자가 걸으면 눈금 막대기가 하나씩 뒤로 접혀(부위 t1..t12 = 그 눈금이 접힘) 정면에서 사라진다. 눈은 처음부터 뜨고 깜빡이지 않는다.
Java 가 몸을 아래로 숙여(pitch 90) 얼굴이 임자를 내려다본다.

- gave:gabe/g190: 얼굴 가운데(0, 0, 0) = 엔진 pos. 상태 ""(눈금 판), "dive"(눈금이 모두 뒤로 모여 화살촉 꼴, 내리꽂힐 때).
소리 tick(째깍 초침 소리, 임자에게만 크게, 12), bell(내리꽂기 0.5초 전 종소리, 24), dive(내리꽂힘, 24).
"""
import math

from gabe import faces, kit

FACE = faces.Face(eyes="open", mouth="dot", blush="deep")
R0, R1 = 0.24, 0.46


def dial(s, folded_cond=True):
    s.head(size=0.55, depth=0.36, face=FACE, near=True, open=False, torn=False)
    # 판 뒤 받침(눈금 뿌리가 박힌 살 고리)
    s.box((-0.31, -0.31, -0.2), (0.31, 0.31, -0.08), "flesh", face=True)
    for i in range(12):
        a = math.radians(i * 30)
        d = (math.sin(a), math.cos(a), 0.0)
        root = (d[0] * R0, d[1] * R0, -0.04)
        long = i % 3 == 0
        L = (R1 - R0) * (1.35 if long else 1.0)
        if not folded_cond:
            s.rod(root, (d[0] * 0.25, d[1] * 0.25, -1.0), length=L * 0.9, segs=1, thick=0.06, base=False, wave=None, rag=False)
            continue
        with s.when(f"!on:t{i + 1}"):
            s.rod(root, d, length=L, segs=1, thick=0.07 if long else 0.055, base=False, wave=None, rag=False, toward=(0, 0, 1))
        with s.when(f"on:t{i + 1}"):
            s.rod(root, (d[0] * 0.15, d[1] * 0.15, -1.0), length=L * 0.7, segs=1, thick=0.05, base=False, wave=None, rag=False)


def build(k: kit.Kit):
    m = k.model(poses=1, period=20, mut2=False, open=False, torn=True)
    dial(m.state(""))
    dial(m.state("dive"), folded_cond=False)
    k.icon(model="g190", state="")


def sounds(k: kit.Kit):
    k.sample("tick", ["kenney_interface/click_001", "kenney_interface/click_003", "kenney_interface/back_002"], "째깍 초침 소리", pitch=0.8,
             attenuation=24, level=-10)
    k.sample("bell", ["oga_sfx100/bell_01", "kenney_impact/impactbell_heavy_001"], "종소리", pitch=0.9, attenuation=24, n=2)
    k.sample("dive", "kenney_impact/impactpunch_heavy_002", "내리꽂힘", pitch=0.7, attenuation=24, n=1)


def phrases():
    return {"cause_g190": "초침이 다 갔다", "cause_g190_near": "내리꽂힌 초침 곁에 있었다"}
