"""G103 국자 게이브(2막, 땅에서 솟는 것: 물웅덩이에서 눈 아래까지, 폭 6 블록).

황야의 얕은 물웅덩이에서 솟은 폭 6 블록 얼굴(물 위 높이 4, 입은 물 아래). 젖어 번들거리고 실눈 위로 물이 흘러내린다(눈물 얼굴 + 이마에서 흘러내리는
젖은 살 띠). 왼쪽(+X)에는 엔드 막대기 하나를 지팡이처럼 짚었고, 오른쪽(-X) 물속에서 긴 엔드 막대기 자루 끝에 살로 빚은 국자를 쥐었다(국자는 부품).

- gave:gabe/g103(shrink 0.25): 얼굴 가운데가 (0, 0, 0), 물 높이(땅) y = -1(Java hover 1). 상태 ""(실눈), "open"(국자를 들 때 눈을 뜸).
- gave:gabe/g103_ladle(부품, shrink 0.16): 자루 뿌리가 (0, 0, 0), 자루가 +Y 로 7, 국자 그릇이 끝에서 앞(+Z)으로. 상태 ""(분홍 물이 찬 그릇),
  "empty"(빈 그릇), "holed"(바닥이 뚫린 그릇). Java 가 자루 쪽을 겨누고 거리에 맞춰 통째로 키운다.
- gave:gabe/g103_flood: 트럭 둘레를 메우는 분홍 물 판(반지름 1, Java 가 12 배), 작은 얼굴들이 떠다님(자세 4).
소리 splash(물 퍼붓는 소리, 64), bubble(웅덩이가 끓음, 48), rise(머리가 솟음, 64), crack(국자 바닥 뚫림, 32).
"""
import math

import numpy as np

from gabe import kit

S = 0.25
HOVER = 1.0
HEAD = 6.0


def limb(s, a, b, thick, tex="rod"):
    a, b = np.array(a, float), np.array(b, float)
    d = b - a
    up = abs(d[1]) > 0.97 * np.linalg.norm(d)
    return s.rod(tuple(a), tuple(d), length=float(np.linalg.norm(d)), segs=1, thick=thick, base=False, tex=tex, rag=False,
                 toward=(0, 0, 1) if up else (0, 1, 0))


def water_tex(seed, n=32, ring=False):
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n]
    rr = np.hypot(xx - (n - 1) / 2, yy - (n - 1) / 2) / (n / 2)
    col = np.array([206, 112, 140], float)[None, None, :] * (0.8 + 0.3 * rng.rand(n, n, 1))
    # 물결 무늬
    wave = np.sin(rr * 18 + rng.rand() * 6) > 0.75
    col[wave] = (236, 170, 190)
    img = np.zeros((n, n, 4), np.uint8)
    img[..., :3] = np.clip(col, 0, 255)
    if ring:
        img[..., 3] = np.where((rr <= 1.0) & (rr >= 0.62), 255, 0)
    else:
        edge = 1.0 - 0.05 * np.sin(np.arctan2(yy - n / 2, xx - n / 2) * 9)
        img[..., 3] = np.where(rr <= edge, 255, 0)
    return img


def build(k: kit.Kit):
    m = k.model(poses=4, period=24, shrink=S, mut2=False, torn=True)
    foam = k.texture("foam", water_tex(103, ring=True))
    for name, face in (("", "tears"), ("open", "bloodshot")):
        s = m.state(name, poses=1 if name else 4)
        h = s.head(size=HEAD, height=HEAD, depth=4.4, face=face)
        # 이마에서 실눈 위로 흘러내리는 젖은 살 띠(물줄기)
        for x, ln in ((-1.6, 1.4), (-0.4, 1.0), (0.9, 1.7), (2.0, 1.1)):
            s.band((x, HEAD / 2 + 0.02, HEAD * 0.37), (0, -1, 0.06), length=ln, width=0.45, segs=1, thick=0.02, tex="wetskin", toward=(0, 0, 1))
        # 물 위 둘레 거품 고리(얼굴이 물을 가르고 솟은 자리)
        s.quad((0, -HOVER + 0.04, 0), (HEAD + 3.2, HEAD + 3.2), "up", foam)
        # 지팡이: 왼쪽(+X) 물속에서 비스듬히 솟은 엔드 막대기
        limb(s, (HEAD / 2 + 0.9, -HOVER - 0.3, 0.8), (HEAD / 2 + 2.0, 5.4, 1.6), 0.34)
        s.box((HEAD / 2 + 1.75, 5.2, 1.35), (HEAD / 2 + 2.25, 5.7, 1.85), "rod")
        # 오른쪽(-X) 국자 자루가 나오는 물속 손목 살덩이(자루는 부품)
        s.box((-HEAD / 2 - 1.6, -HOVER - 0.4, -0.2), (-HEAD / 2 - 0.4, -HOVER + 0.7, 1.4), "flesh", face=True)
    # 국자(부품): 자루 + 그릇
    lm = k.model("g103_ladle", poses=1, period=20, shrink=0.16, mut2=False, open=False, torn=False, rag=False)
    pink = k.texture("water", water_tex(31))
    for name in ("", "empty", "holed"):
        s = lm.state(name)
        limb(s, (0, -0.3, 0), (0, 7.0, 0), 0.3)
        # 그릇: 자루 끝에서 앞으로, 바닥 + 네 벽(살)
        cx, cy, cz, w, d = 0.0, 7.0, 1.05, 2.1, 0.8
        if name == "holed":
            for frm, to in (((cx - w / 2, cy - 0.15, cz - w / 2), (cx - 0.35, cy, cz + w / 2)),
                            ((cx + 0.35, cy - 0.15, cz - w / 2), (cx + w / 2, cy, cz + w / 2)),
                            ((cx - 0.35, cy - 0.15, cz - w / 2), (cx + 0.35, cy, cz - 0.35)),
                            ((cx - 0.35, cy - 0.15, cz + 0.35), (cx + 0.35, cy, cz + w / 2))):
                s.box(frm, to, "flesh", face=True)
        else:
            s.box((cx - w / 2, cy - 0.15, cz - w / 2), (cx + w / 2, cy, cz + w / 2), "flesh", face=True)
        t = 0.14
        for frm, to in (((cx - w / 2, cy, cz - w / 2), (cx + w / 2, cy + d, cz - w / 2 + t)),
                        ((cx - w / 2, cy, cz + w / 2 - t), (cx + w / 2, cy + d, cz + w / 2)),
                        ((cx - w / 2, cy, cz - w / 2), (cx - w / 2 + t, cy + d, cz + w / 2)),
                        ((cx + w / 2 - t, cy, cz - w / 2), (cx + w / 2, cy + d, cz + w / 2))):
            s.box(frm, to, "flesh", face=True)
        if name == "":
            s.quad((cx, cy + d - 0.12, cz), (w - 0.25, w - 0.25), "up", pink)
            # 그릇 속 작은 얼굴 하나(떠 있음)
            s.quad((cx + 0.3, cy + d - 0.08, cz - 0.2), (0.6, 0.6), "up", k.face_tex("smile_a"), face=True)
        s.front = (0, 0, 0)
    # 트럭 둘레를 메우는 물 판
    fm = k.model("g103_flood", poses=4, period=40, mut2=False, open=False, torn=False, rag=False)
    s = fm.state("")
    s.quad((0, 0, 0), (2, 2), "up", k.texture("flood", water_tex(55, n=64)))
    face = k.face_tex("smile")
    spots = [(0.55, 0.2), (-0.4, 0.5), (0.1, -0.6), (-0.65, -0.3), (0.35, 0.68), (-0.15, 0.05), (0.72, -0.35), (-0.5, 0.75)]
    for f in s.frames():
        with f:
            for j, (x, z) in enumerate(spots):
                a = 2 * math.pi * (f.t + j / len(spots))
                s.quad((x + 0.03 * math.cos(a), 0.004 + 0.003 * math.sin(a), z + 0.03 * math.sin(a)), (0.05, 0.05), "up", face, face=True,
                       rot=(0, 25 * math.sin(a), 0))
    s.front = (0, 0, 0)
    k.icon(model="g103", state="")


def sounds(k: kit.Kit):
    k.sample("splash", ["oga_water40/splash_01", "oga_water40/splash_05", "oga_sfx100/splash_02"], "물 퍼붓는 소리", pitch=0.65,
             attenuation=64)
    k.sample("bubble", ["oga_loops30/water_boiling", "oga_water40/loop_bubbles_1"], "웅덩이가 끓음", pitch=0.6, attenuation=48)
    k.sample("rise", ["oga_water40/splash_04", "oga_quake/shake"], "머리가 솟음", pitch=0.5, attenuation=64)
    k.sample("crack", ["oga_metalwood100/wood_slam_02", "lrsf_horror/bonecrack_03"], "국자 바닥 뚫림", pitch=0.8, attenuation=32)


def phrases():
    return {"cause_g103": "국자 물을 뒤집어썼다", "cause_g103_flood": "차오른 물에 잠겼다", "cause_g103_rod": "국자 자루에 맞았다"}
