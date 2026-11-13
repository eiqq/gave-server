"""G161 순찰 병정 게이브(1막, 원거리, 튼튼이: 키 8.4 블록 구릿빛 장난감 병정).

네모난 몸통과 팔다리는 구릿빛으로 칠한 얼굴 판을 이어 붙였고, 머리는 2 블록 _Gave 얼굴(칠하지 않은 맨얼굴)에 높은 원통 모자.
어깨에 엔드 막대기 둘을 총처럼 메고, 왼발 뒤꿈치에 엔드 막대기 못이 깊이 박혀 분홍 즙이 샌다. 무릎이 안 굽는 곧은 다리.

- gave:gabe/g161(shrink 0.29): 몸통 + 머리 + 모자 + 어깨 총. 몸 가운데(0, 0, 0) = 엔진 pos(몸통 가운데), 바닥에서 HOVER 4.0.
  엉덩이 (±HIP_X, HIP_Y, 0), 어깨 (±SH_X, SH_Y, 0). 상태 "" 하나(모자 까딱은 Java 가 몸 pitch 로).
- gave:gabe/g161_leg(shrink 0.5): 엉덩이 (0, 0, 0), 발바닥 (0, -LEG_L, 0), 발끝 +Z. 상태 ""(오른다리), "nail"(왼다리, 뒤꿈치 못),
  "pulled"(못이 뽑힌 왼다리, 즙 구멍).
- gave:gabe/g161_arm(shrink 0.5): 어깨 (0, 0, 0), 손 (0, -ARM_L, 0).
- gave:gabe/g161_lump: 발밑에서 뽑아 던지는 얼굴 덩이(1 블록).
소리 march(쇠 병정 걸음, 48), lift(덩이 뽑음, 32), toss(던짐, 40), splat(덩이 터짐, 32), pop(못 뽑힘, 48).
"""
import numpy as np

from gabe import faces, kit

S = 0.29
HOVER = 4.0
HIP_X, HIP_Y = 0.62, -1.2
LEG_L = 2.8
SH_X, SH_Y = 1.55, 0.95
ARM_L = 2.3
HEAD = 1.9
HEAD_Y = 2.3

COPPER = np.array((196, 118, 58), float)
COPPER_DARK = np.array((104, 56, 30), float)
BRASS = np.array((222, 172, 78), float)


def tint(img, rgb, keep=0.18, gain=1.25):
    """얼굴, 스킨 그림을 구릿빛으로 칠한 모습(밝기만 남기고 색을 입힘, 원래 색 조금)"""
    a = np.asarray(img).astype(float)
    lum = (0.3 * a[..., 0] + 0.59 * a[..., 1] + 0.11 * a[..., 2]) / 255.0
    out = a.copy()
    for c in range(3):
        out[..., c] = np.clip(lum * rgb[c] * gain * (1 - keep) + a[..., c] * keep, 0, 255)
    out[..., 3] = 255
    return out.astype(np.uint8)


def textures(k):
    t = {}
    t["face"] = k.texture("cu_face", tint(faces.far(faces.preset("smile")), COPPER))
    t["face_half"] = k.texture("cu_face_half", tint(faces.far(faces.preset("half")), COPPER))
    t["side"] = k.texture("cu_side", tint(faces.skin_side("left"), COPPER))
    t["back"] = k.texture("cu_back", tint(faces.skin_side("back"), COPPER))
    t["top"] = k.texture("cu_top", tint(faces.skin_side("top"), COPPER))
    t["dark"] = k.texture("cu_dark", tint(faces.skin_side("top"), COPPER_DARK, keep=0.05, gain=1.0))
    t["brass"] = k.texture("cu_brass", tint(faces.skin_side("bottom"), BRASS, keep=0.05, gain=1.15))
    return t


def cbox(s, t, frm, to, front="side", **kw):
    """구릿빛 상자: 앞(+Z)은 front 텍스처, 옆은 칠한 스킨, 위아래는 윗면 스킨"""
    tex = {"south": t[front], "north": t["back"], "east": t["side"], "west": t["side"], "up": t["top"], "down": t["top"]}
    return s.box(frm, to, tex, uv=[0, 0, 16, 16], face=True, **kw)


def torso(s, k, t):
    # 몸통(네모), 앞뒤에 칠한 얼굴 판 넷씩
    cbox(s, t, (-1.2, -1.2, -0.7), (1.2, 1.2, 0.7), front="side")
    for x in (-0.6, 0.6):
        for y, f in ((0.6, "face"), (-0.72, "face_half")):
            s.quad((x, y, 0.715), (1.08, 1.08), "south", t[f], face=True)
            s.quad((x, y, -0.715), (1.08, 1.08), "north", t[f], face=True)
    # 허리띠(짙은 구리) + 놋쇠 버클
    s.box((-1.24, -0.32, -0.74), (1.24, -0.02, 0.74), t["dark"], uv=[0, 0, 16, 4], faces=("north", "south", "east", "west"), face=True)
    s.box((-0.22, -0.34, 0.72), (0.22, 0.0, 0.8), t["brass"], uv=[0, 0, 8, 8], face=True)
    # 견장
    for side in (1, -1):
        x0, x1 = (1.15, 1.55) if side > 0 else (-1.55, -1.15)
        s.box((x0, 0.9, -0.5), (x1, 1.28, 0.5), t["brass"], uv=[0, 0, 16, 8], face=True)
    # 목
    s.box((-0.45, 1.2, -0.4), (0.45, 1.38, 0.4), t["dark"], uv=[0, 0, 16, 4], face=True)
    # 어깨에 멘 엔드 막대기 총 둘(등 뒤에서 비스듬히 솟음, 가만히)
    for side in (1, -1):
        s.rod((side * 0.75, 0.55, -0.78), (side * 0.18, 1, -0.32), length=2.1, segs=1, thick=0.17, base=False, toward=(1, 0, 0), rag=False)
        s.box((side * 0.75 - 0.12, 0.3, -0.9), (side * 0.75 + 0.12, 0.62, -0.7), t["dark"], uv=[0, 0, 8, 8], face=True)
    # 머리(칠하지 않은 _Gave 얼굴)
    s.head(size=HEAD, depth=1.7, at=(0, HEAD_Y, 0), face="smile", near=False)
    # 높은 원통 모자: 네모 기둥 셋을 30 도씩 돌려 열두모 기둥처럼, 놋쇠 띠, 앞 챙, 위 깃 막대기
    y0 = HEAD_Y + HEAD / 2 - 0.05
    y1 = y0 + 1.25
    for a in (0, 30, 60):
        s.box((-0.68, y0, -0.68), (0.68, y1, 0.68), {n: t["dark"] for n in ("north", "south", "east", "west", "up")}, uv=[0, 0, 16, 16],
              faces=("north", "south", "east", "west", "up"), rot=(0, a, 0), origin=(0, y0, 0), face=True)
    for a in (0, 45):
        s.box((-0.72, y0 + 0.08, -0.72), (0.72, y0 + 0.3, 0.72), t["brass"], uv=[0, 0, 16, 4], faces=("north", "south", "east", "west"),
              rot=(0, a, 0), origin=(0, y0, 0), face=True)
    s.box((-0.6, y0 - 0.02, 0.55), (0.6, y0 + 0.08, 1.12), t["dark"], uv=[0, 0, 16, 8], face=True)
    s.box((-0.18, y0 + 0.75, 0.66), (0.18, y0 + 1.05, 0.74), t["brass"], uv=[0, 0, 8, 8], face=True)
    s.rod((0, y1, 0.32), (0, 1, 0.12), length=0.55, segs=1, thick=0.14, base=False, toward=(1, 0, 0), rag=False)


def leg_model(k, t):
    m = k.model("g161_leg", poses=1, period=20, shrink=0.5, mut2=False, open=False, torn=False, rag=False)
    for st in ("", "nail", "pulled"):
        s = m.state(st)
        cbox(s, t, (-0.44, -1.45, -0.44), (0.44, 0.0, 0.44), front="side")
        cbox(s, t, (-0.4, -2.35, -0.4), (0.4, -1.4, 0.4), front="side")
        s.quad((0, -0.72, 0.445), (0.8, 0.8), "south", t["face"], face=True)
        s.quad((0, -1.88, 0.405), (0.72, 0.72), "south", t["face_half"], face=True)
        # 무릎 놋쇠 띠, 장화
        s.box((-0.47, -1.5, -0.47), (0.47, -1.35, 0.47), t["brass"], uv=[0, 0, 16, 3], faces=("north", "south", "east", "west"), face=True)
        s.box((-0.46, -LEG_L, -0.52), (0.46, -2.3, 0.66), t["dark"], uv=[0, 0, 16, 16], face=True)
        if st == "nail":
            # 뒤꿈치에 깊이 박힌 엔드 막대기 못 + 이음매에서 새는 분홍 즙
            s.rod((0, -2.56, -0.5), (0, 0.42, -1), length=1.0, segs=1, thick=0.16, base=True, toward=(1, 0, 0), rag=False)
            s.box((-0.2, -2.72, -0.6), (0.2, -2.42, -0.5), "flesh", face=True)
            s.box((-0.06, -2.8, -0.64), (0.06, -2.66, -0.54), "flesh", face=True)
        elif st == "pulled":
            s.box((-0.24, -2.74, -0.56), (0.24, -2.38, -0.5), "flesh", face=True)
            s.box((-0.14, -2.8, -0.6), (0.14, -2.6, -0.52), "flesh", face=True)
        s.front = (0, 0, 0)


def arm_model(k, t):
    m = k.model("g161_arm", poses=1, period=20, shrink=0.5, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    cbox(s, t, (-0.34, -1.22, -0.34), (0.34, 0.18, 0.34), front="side")
    cbox(s, t, (-0.32, -2.02, -0.32), (0.32, -1.18, 0.32), front="side")
    s.quad((0, -0.55, 0.345), (0.62, 0.62), "south", t["face"], face=True)
    s.box((-0.37, -1.32, -0.37), (0.37, -1.16, 0.37), t["brass"], uv=[0, 0, 16, 3], faces=("north", "south", "east", "west"), face=True)
    s.box((-0.3, -ARM_L, -0.3), (0.3, -1.98, 0.3), "flesh", face=True)
    s.front = (0, 0, 0)


def lump_model(k):
    m = k.model("g161_lump", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    s.lump(count=7, radius=(0.36, 0.36, 0.36), size=0.5, faces_list=["smile", "half", "tears", "smile_a", "open", "smile_c", "smile"], seed=161)
    s.front = (0, 0, 0)


def build(k: kit.Kit):
    t = textures(k)
    m = k.model(poses=1, period=20, shrink=S, mut2=False, torn=True)
    torso(m.state(""), k, t)
    m.state("").front = (0, 0, 1)
    leg_model(k, t)
    arm_model(k, t)
    lump_model(k)
    k.icon(model="g161", state="")


def sounds(k: kit.Kit):
    k.sample("march", "tag:machine", "쇠 병정 걸음", pitch=0.55, attenuation=48)
    k.sample("lift", "tag:stone", "얼굴 덩이 뽑음", pitch=0.7, attenuation=32)
    k.sample("toss", "tag:grunt", "병정이 덩이를 던짐", pitch=0.6, attenuation=40)
    k.sample("splat", "tag:wet+slime", "얼굴 덩이 터짐", pitch=0.7, attenuation=32)
    k.sample("pop", ["kenney_impact/impactmetal_heavy_000", "kenney_impact/impactbell_heavy_002"], "뒤꿈치 못이 뽑힘", pitch=0.8, attenuation=48)


def phrases():
    return {"cause_g161": "순찰 병정에게 밟혔다", "cause_g161_lump": "순찰 병정이 던진 얼굴 덩이에 맞았다"}
