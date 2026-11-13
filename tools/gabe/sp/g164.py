"""G164 몰이 게이브: 몰이꾼 띠와 물가의 왜가리.

몰이꾼(모델 gave:gabe/g164): 납작 엎드린 0.5 블록 얼굴 뒤로 얼굴보다 넓은 웅크린 살 등(몸통)이 낮게 붙고, 짧은 엔드 막대기 둘을 북채처럼
양옆에 쥐어 땅을 번갈아 두드린다(2자세, 6틱 한 바퀴). 수십이 어깨를 맞대 얇은 활꼴 띠가 된다(Java).

왜가리(모델 gave:gabe/g164_heron, shrink 0.75): 키 3.2 블록 외발 새꼴. 엔드 막대기 다리(선 다리 하나, 접은 다리 하나), 얼굴 판을 접어 만든
날개 몸통, 곧게 세운 막대기 목, 그 끝에 부리처럼 길게 늘인 얼굴이 실눈으로 물을 내려다본다. 상태 ""(외발 서기), "cock"(찍기 0.5초 전 목이
뒤로 접힘), "strike"(목을 앞으로 뻗어 내리찍음).
"""
from gabe import kit

# 몰이꾼: 몸 중심(0, 0, 0) 은 땅에서 0.28 위(Java HOVER)
B_GROUND = -0.28
B_HEAD = 0.5
STICK = dict(length=0.56, segs=1, thick=0.09)

# 왜가리: 몸 중심은 땅에서 1.6 위(Java HERON_HOVER)
H_GROUND = -1.6


def beater(k, s):
    # 엎드린 얼굴(앞을 보며 살짝 들림)
    s.head(size=B_HEAD, depth=0.34, at=(0, 0.02, 0.2), face="smile", rot=(-12, 0, 0))
    # 웅크린 등(얼굴보다 넓고 길다: 멀리서 낮은 덩이로 읽힘)
    s.box((-0.36, B_GROUND, -0.85), (0.36, 0.12, 0.05), "flesh", face=True)
    s.box((-0.28, 0.12, -0.72), (0.28, 0.22, -0.12), "flesh", face=True)
    # 북채 둘: 번갈아 땅을 두드림
    for side in (1, -1):
        root = (side * 0.38, 0.02, -0.02)
        w = kit.Wave(amp=34, bias=-6, phase=0 if side > 0 else 180)
        s.rod(root, (side * 0.3, -0.25, 1.0), wave=w, toward=(0, 1, 0), poses=2, **STICK)


def heron(k, s, pose):
    # 선 다리(땅까지 곧게)와 접은 다리(무릎에서 뒤로 꺾임)
    s.rod((0.1, -0.3, 0.0), (0, -1, 0), length=1.3, segs=1, thick=0.1, toward=(0, 0, 1), rag=False)
    s.rod((-0.12, -0.3, 0.02), (0, -1, 0.7), length=0.52, segs=1, thick=0.09, base=False, rag=False)
    s.rod((-0.12, -0.72, 0.32), (0, 0.55, -1), length=0.42, segs=1, thick=0.08, base=False, rag=False)
    # 몸통: 살 덩이 + 양옆에 접어 붙인 얼굴 판 날개(얼굴이 바깥을 봄)
    s.box((-0.24, -0.34, -0.62), (0.24, 0.3, 0.48), "flesh", face=True)
    for side in (1, -1):
        s.head(size=1.12, height=0.62, depth=0.08, at=(side * 0.29, 0.0, -0.08), face="smile", near=False, torn=False,
               rot=(0, side * 90, side * -8))
    # 꽁지: 짧은 막대기 둘
    for side in (1, -1):
        s.rod((side * 0.1, 0.05, -0.6), (side * 0.25, -0.3, -1), length=0.42, segs=1, thick=0.07, base=False, rag=False)
    # 목과 부리 얼굴
    if pose == "":
        neck_root, neck_dir, neck_len = (0, 0.28, 0.4), (0, 1, 0.18), 0.92
        head_at, head_rot = (0, 1.32, 0.76), (52, 0, 0)
    elif pose == "cock":
        neck_root, neck_dir, neck_len = (0, 0.28, 0.4), (0, 1, -0.7), 0.82
        head_at, head_rot = (0, 0.98, -0.12), (78, 0, 0)
    else:
        neck_root, neck_dir, neck_len = (0, 0.18, 0.44), (0, -0.55, 1), 1.0
        head_at, head_rot = (0, -0.3, 1.5), (38, 0, 0)
    s.rod(neck_root, neck_dir, length=neck_len, segs=1, thick=0.1, rag=False)
    # 부리처럼 길게 늘인 얼굴(앞뒤로 긴 상자, 얼굴 면이 아래 앞을 봄)
    s.head(size=0.42, height=0.36, depth=0.82, at=head_at, face="smile", rot=head_rot)


def build(k: kit.Kit):
    m = k.model(poses=2, period=6, mut2=False, torn=False)
    beater(k, m.state(""))
    h = k.model("g164_heron", poses=1, shrink=0.75, mut2=False, torn=False)
    for pose in ("", "cock", "strike"):
        heron(k, h.state(pose), pose)
    k.icon(model="g164_heron", state="")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 막대기로 땅을 두드리는 딱딱딱(띠가 오는 쪽에서 크게)
    k.sample("beat", "tag:hammer", "딱딱 몰이 소리", pitch=1.05, volume=0.9, attenuation=40)
    # 왜가리 부리 찍기(진흙에 꽂음)
    k.sample("stab", "kenney_impact/impactsoft_heavy_00*", "부리 찍기", pitch=1.35, attenuation=24)
    # 목이 뒤로 접힘(찍기 0.5초 전)
    k.sample("cock", "kenney_rpg/cloth*", "목 접는 소리", pitch=0.7, attenuation=20)


def phrases():
    return {"cause_g164": "몰이에 밀려 부리에 꽂혔다"}
