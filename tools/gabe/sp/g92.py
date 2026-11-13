"""G92 팔 뻗는 게이브: 키 2.6 블록의 가는 살 몸에 1 블록 얼굴. 왼쪽 어깨 위로 접자처럼 접힌 막대기 팔(나란히 선 막대기 다섯과 가로대:
등에 진 사다리로 읽힘)이 머리 위로 솟고 그 꼭대기에 주먹만 한 얼굴(손)이 얹혀 있다. 오른쪽은 짧은 막대기 지팡이로 땅을 짚는다.
다리는 짧은 막대기 둘(걸음).

- gave:gabe/g92: 상태 ""(걷기: 다리 자세 4). 부위 "out"(팔이 나감: 접힌 팔과 손끝 얼굴이 몸에서 사라짐, 어깨 그루터기만).
- gave:gabe/g92_arm(부품, shrink 0.1): 곧게 뻗은 팔. 상태 "a3", "a6" .. "a24" = 길이(블록), 1.5 블록 마디마다 이음 받침. Java 가 지금 길이보다
  길거나 같은 가장 가까운 상태를 골라 길이 쪽(+Z)으로 조금 줄인다. 모델 가운데가 팔 가운데.
- gave:gabe/g92_hand(부품): 손끝 얼굴 0.5. 상태 ""(실눈, 눈 뜸은 look.open), "bite"(이빨 고리 입: 문 채).
소리 unfold(마디 펴지는 소리, 32), bite(24), slam(지팡이, 16), snap(팔 끊김, 24). 글귀 cause_g92, cause_g92_cane, cause_g92_drag.
"""
from gabe import kit

ARM_LENGTHS = (3, 6, 9, 12, 15, 18, 21, 24)
SEG = 1.5
HEAD_Y = 0.8  # 얼굴 가운데(몸 중심에서 위로)
SHOULDER = (0.4, 0.3, -0.05)  # 왼쪽 어깨(팔이 나오는 곳). Java SHOULDER_* 와 같게
FRONT = (0, 0, 1)


def build(k: kit.Kit):
    m = k.model(poses=4, period=16, mut2=dict(root=lambda side: (side * 0.22, 0.25, -0.12), dir=(0, 0.4, -1), length=0.45, segs=1, poses=2))
    s = m.state("")
    s.head(size=1.0, depth=0.7, at=(0, HEAD_Y, 0), face="smile")
    # 가는 살 몸통(목에서 골반까지), 골반 가로대
    s.box((-0.2, -0.72, -0.13), (0.2, 0.32, 0.13), "flesh", face=True)
    s.box((-0.28, -0.82, -0.11), (0.28, -0.66, 0.11), "wetskin", face=True)
    # 다리: 짧은 막대기 둘(걸음)
    for side in (1, -1):
        s.rod((side * 0.17, -0.76, 0), (side * 0.04, -1, 0), length=0.54, segs=1, thick=0.1,
              wave=kit.Wave(amp=24, phase=0 if side > 0 else 180), toward=FRONT, poses=4, rag=False)
    # 오른쪽: 어깨에서 내려온 짧은 팔과 지팡이(땅을 짚음)
    s.rod((-0.24, 0.24, 0), (-0.3, -0.6, 0.45), length=0.5, segs=1, thick=0.09, base=False)
    s.rod((-0.48, -0.08, 0.34), (0.0, -1, 0.06), length=1.2, segs=1, thick=0.1)
    # 왼쪽 어깨 그루터기(늘 보임)
    s.rod(SHOULDER, (0.4, 0.4, -1), length=0.26, segs=1, thick=0.13)
    with s.when("!on:out"):
        # 접힌 팔: 왼쪽 어깨 위로 솟은 나란한 막대기 다섯(등에 진 사다리), 위, 가운데, 아래 가로대
        for i in range(5):
            x = 0.36 + i * 0.15
            s.rod((x, -0.7, -0.42), (0, 1, 0), length=2.12, segs=1, thick=0.1, toward=FRONT, base=(i % 2 == 0))
        for y in (1.36, 0.42, -0.62):
            s.box((0.3, y - 0.03, -0.46), (1.0, y + 0.03, -0.38), "rod", uv=[2, 2, 6, 6])
        # 사다리 꼭대기의 손끝 얼굴
        s.head(size=0.5, depth=0.4, at=(0.66, 1.2, -0.3), face="smile", near=False, torn=False)

    arm = k.model("g92_arm", poses=1, period=20, shrink=0.1, mut2=False, open=False, torn=False, rag=False)
    for L in ARM_LENGTHS:
        a = arm.state(f"a{L}")
        n = int(round(L / SEG))
        for i in range(n):
            a.rod((0, 0, -L / 2 + i * SEG), (0, 0, 1), length=SEG, segs=1, thick=0.13, base=True)
        a.front = (0, 0, 0)
    arm.default = "a24"

    hand = k.model("g92_hand", poses=1, period=20, mut2=False, torn=False, rag=False)
    for name, face in (("", "smile"), ("bite", "teeth_ring")):
        h = hand.state(name)
        h.head(size=0.5, depth=0.4, face=face)
        # 손목: 뒤통수에서 팔로 이어지는 받침
        h.rod((0, 0, -0.2), (0, 0, -1), length=0.2, segs=1, thick=0.13)
    k.icon(model="g92", state="")


def sounds(k: kit.Kit):
    k.sample("unfold", ["kenney_interface/click_001", "kenney_interface/back_002", "kenney_interface/back_004"], "마디 펴지는 소리",
             pitch=0.7, attenuation=32)
    k.sample("bite", "tag:eat", "손끝 얼굴이 물었다", pitch=0.9, attenuation=24)
    k.sample("slam", "kenney_impact/impactwood_heavy_00*", "지팡이 내려치기", pitch=0.85, attenuation=16)
    k.sample("snap", "oga_breaking75/bfh1_wood_breaking_0*", "팔이 끊김", pitch=1.1, attenuation=24)


def phrases():
    return {"cause_g92": "뻗은 팔에 물렸다", "cause_g92_cane": "지팡이에 맞았다", "cause_g92_drag": "팔에 끌려갔다"}
