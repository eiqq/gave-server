"""G175 태워 주는 게이브: 허리 높이의 네 발 짐승 꼴(길이 2.4 블록). 머리 자리에 0.8 블록 얼굴, 꼬리 자리에 작은 얼굴 하나, 엔드 막대기
다리 넷, 등에는 길쭉한 얼굴 판이 안장처럼 얹혀 하늘을 보고 웃는다(부품).

모델 gave:gabe/g175(몸, 몸 중심 = 땅 위 0.8):
- ""(걷기 4자세 12틱), "kneel"(앞무릎을 꿇고 등을 내밂, 자세 하나), "run"(내달림: 같은 주기에 두 번).
모델 gave:gabe/g175_saddle(등 안장 얼굴 부품, 가운데 = 등 위): ""(방긋), "grin"(입이 귀까지 찢어짐), "sticky"(찢어진 입 + 등이 끈적하게 번들거림).
Java 가 탄 사람 수만큼 부품을 앞뒤로 늘인다(등이 엿가락처럼).
소리 neigh(히힝 웃음, 24), kick(뒷발차기, 20), stick(손발이 들러붙음, 20), slam(내동댕이침, 24). 글귀 cause_g175, cause_g175_kick, cause_g175_slam.
"""
import numpy as np

from gabe import faces, kit

LEGS = [(0.3, 0.72), (-0.3, 0.72), (0.3, -0.78), (-0.3, -0.78)]
GRIN = faces.Face(eyes="open", mouth="torn")


def beast(s, waves, kneel=False):
    # 몸통 뼈대: 등을 받치는 막대기 둘(안장 아래)
    for side in (1, -1):
        s.rod((side * 0.22, 0.02, -0.95), (0, 0.02, 1), length=1.9, segs=2, thick=0.08, base=False, rag=False)
    s.box((-0.3, -0.16, -0.85), (0.3, 0.02, 0.85), "flesh", face=True)
    # 머리(0.8)와 꼬리 얼굴
    hy = -0.12 if kneel else 0.22
    s.head(size=0.8, depth=0.55, at=(0, hy, 1.08), face="smile", rot=(12 if kneel else 0, 0, 0))
    s.head(size=0.32, depth=0.24, at=(0, 0.12, -1.08), face="smile_c", near=False, rot=(0, 180, 0))
    # 다리 넷(앞다리는 꿇으면 접힘)
    for i, ((x, z), w) in enumerate(zip(LEGS, waves)):
        s.rod((x, -0.1, z), (0, -1, 0), length=0.74, segs=2, thick=0.08, wave=w, toward=(0, 0, 1),
              poses=4 if s.poses >= 4 else None, base=True)


def saddle_tex():
    """안장 얼굴이 번들거림: 젖은 살 위 흰 반짝임 줄(알파 0/255)"""
    n = 16
    out = np.zeros((n, n, 4), np.uint8)
    rng = np.random.RandomState(175)
    for y in range(n):
        for x in range(n):
            if (x + y * 2) % 7 == 0 or rng.rand() < 0.08:
                out[y, x] = (250, 236, 240, 255)
    return out


def build(k: kit.Kit):
    m = k.model(poses=4, period=12)
    walk = [kit.Wave(amp=(22, 14), lag=40, bias=(0, 8), phase=ph) for ph in (0, 180, 180, 0)]
    beast(m.state(""), walk)
    fold = kit.Wave(amp=0, bias=(70, -120))
    stand = kit.Wave(amp=0, bias=(0, 4))
    beast(m.state("kneel", poses=1), [fold, fold, stand, stand], kneel=True)
    run = [kit.Wave(amp=(38, 20), lag=40, bias=(0, 8), phase=ph, cycles=2) for ph in (0, 30, 180, 210)]
    beast(m.state("run"), run)

    sd = k.model("g175_saddle", poses=1, mut2=False, torn=False, rag=False)
    shine = k.texture("shine", saddle_tex())
    for name, face in (("", "smile"), ("grin", GRIN), ("sticky", GRIN)):
        s = sd.state(name)
        # 길쭉한 얼굴 판: 하늘을 보고 누움(이마가 머리 쪽)
        s.head(size=0.78, height=1.7, depth=0.16, at=(0, 0, 0), face=face, rot=(-90, 180, 0))
        if name == "sticky":
            s.quad((0, 0.09, 0), (0.78, 1.7), "up", shine, uv=(0, 0, 16, 16))
        s.front = (0, 1, 0)
    k.icon(model="g175", state="kneel")


def sounds(k: kit.Kit):
    # 이 종 전용 신호: 무릎 꿇을 때 콧소리 섞인 웃음
    k.sample("neigh", "tag:laugh-crowd", "히힝 웃음", pitch=1.25, attenuation=24)
    k.sample("kick", "kenney_impact/impactpunch_heavy_00*", "뒷발차기", pitch=0.8, attenuation=20)
    k.sample("stick", "tag:slime", "손발이 들러붙음", pitch=0.7, attenuation=20)
    k.sample("slam", "tag:slam", "내동댕이침", pitch=0.9, attenuation=24)


def phrases():
    return {"cause_g175": "태워 주는 게이브에 물렸다", "cause_g175_kick": "뒷발에 차였다", "cause_g175_slam": "떼 한복판에 내동댕이쳐졌다"}
