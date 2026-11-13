"""B4 지평선 게이브(보스, 30웨이브. docs/모듈/보스.md 15절): 지평선 너머에서 솟은 산만 한 _Gave 얼굴(얼굴 1.6 블록을 Java 가 32배 = 폭 51블록).
빈 닻 + 세계 축 부품으로 그린다(모든 모델은 틀 ±1.5 블록 안의 보통 크기, 부품 배율로 키움).

- gave:gabe/b4(부품 배율 32): 얼굴 판. 상태 ""(실눈 감음), "l"(게이브 왼쪽 = +X 눈 뜸), "r"(오른쪽 눈 뜸), "lr"(두 눈 뜸), "sink"(눈물, 죽음 연출).
  뜬 눈은 핏발 선 흰자에 바늘 동공(지름 4화소 = 6.4블록, 가운데가 얼굴 기준 (±0.35, 0, 앞면): Java PUPIL_*). 가까운 얼굴(128)은 없다(3블록 안에 설 수 없음).
- gave:gabe/b4_finger(배율 18): 손가락 기둥. 엔드 막대기 두 마디(길이 1.4 = 25블록, 굵기 0.19 = 3.4블록: 아래 마디는 곧게 서서 밑동의 뼈 블록 기둥
  3 x 3 을 감싼다), 위 마디는 앞(+Z)으로 12도 굽고 움찔한다. 끝에 손톱 자리 작은 얼굴(실눈 뜸 오버레이: 찍을 때 눈을 뜸).
  상태 ""(움찔), "lift"(뽑혀 꿈틀), "broken"(끊긴 그루터기).
- gave:gabe/b4_horn(배율 28): 뒤통수에서 하늘로 휘는 엔드 막대기(길이 1.5 = 42블록, 네 마디가 +X 로 휨). 큰 흔들림은 Java Part.pose.
- gave:gabe/b4_shade: 손가락 그늘(폭 1, 길이 1 이 +Z 로 뻗는 바닥 판, 어두운 점묘). Java 가 (폭, 1, 길이)로 늘림.
- gave:gabe/b4_beam: 눈빛 원뿔(길이 1.5, 끝 반지름 0.5, 축 +Z. G43 과 같은 꼴). gave:gabe/b4_spot: 바닥 빛 자리(지름 1 판). 상태 ""(테두리 + 성긴 점),
  "rim"(테두리만: 눈 뜨기 전 예고), "flood"(온 눈뜸: 3 x 3 블록 판에 성긴 점 타일 6 x 6, Java 가 34배).
- gave:gabe/b4_mask: 눈빛에 굳어 가는 사람 머리에 씌우는 것(남들 눈에만, 머리 칸 모델). 상태 "s1", "s2", "s3"(G43 과 같은 꼴).
"""
import math

import numpy as np

from gabe import faces, kit

FACE, DEPTH = 1.6, 0.6
LIGHT = (255, 244, 170)
LIGHT_EDGE = (255, 226, 120)
FINGER = dict(length=1.4, segs=2, thick=0.19)
BEND = 12.0


def _hash(a, b, seed=0):
    v = math.sin(a * 127.1 + b * 311.7 + seed * 74.7) * 43758.5453
    return v - math.floor(v)


# ---------------------------------------------------------------------------------------------- 얼굴 텍스처(키트 얼굴을 고쳐 씀)

def _open_canvas():
    """핏발 선 흰자 얼굴(키트 처방)에서 바늘 동공만 지름 4화소로 키운 것(51블록 얼굴에서 지름 6.4블록: 맞는 구 반지름 3 과 맞춤)"""
    c = faces.draw("bloodshot")
    for x0 in (7, 21):
        for dy in range(4):
            for dx in range(4):
                if dx in (0, 3) and dy in (0, 3):
                    continue
                c.put(x0 + dx, 14 + dy, "p")
    return c


def _eyes(open_left, open_right):
    """실눈 얼굴에 뜬 눈 반쪽을 옮겨 붙임. 그림의 오른쪽 절반(x >= 16)이 게이브 자신의 왼쪽(+X) 눈"""
    c = faces.draw("smile")
    o = _open_canvas()
    for want, x0, x1 in ((open_left, 16, 32), (open_right, 0, 16)):
        if not want:
            continue
        for arr_c, arr_o in ((c.rgb, o.rgb), (c.a, o.a), (c.cls, o.cls)):
            arr_c[9:22, x0:x1] = arr_o[9:22, x0:x1]
    return c.rgba()


def beam_tex(w=32, h=128):
    """빛 원뿔 한 판: 위(v=0)가 눈 쪽 꼭짓점, 아래로 갈수록 넓어짐. 가운데는 촘촘하고 가장자리로 갈수록 성긴 세로 빛살, 가장자리 선"""
    im = np.zeros((h, w, 4), np.uint8)
    c = (w - 1) / 2
    for y in range(h):
        hw = 1.2 + (c - 0.2) * (y / (h - 1)) ** 0.92
        for x in range(w):
            d = abs(x - c) / hw
            if d > 1.0:
                continue
            dens = 0.3 * (1 - d * d) ** 0.6 + 0.04
            edge = d > 0.88
            n = _hash(x, (y + x * 5) // 6, 4)
            if n < dens or (edge and _hash(x, y // 3, 9) < 0.55):
                col = LIGHT_EDGE if edge else LIGHT
                im[y, x] = (*col, 255)
    return faces._fill_rest(im)


def spot_tex(n=64, rim=True, fill=True):
    """바닥 빛 자리: 또렷한 테두리 고리 + 안쪽 성긴 점"""
    im = np.zeros((n, n, 4), np.uint8)
    c = (n - 1) / 2
    bayer = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0
    for y in range(n):
        for x in range(n):
            r = math.hypot(x - c, y - c) / (n / 2)
            if r > 1.0:
                continue
            if r > 0.9:
                if rim and (r > 0.93 or (x + y) % 2 == 0):
                    im[y, x] = (*LIGHT_EDGE, 255)
                continue
            if not fill:
                continue
            dens = 0.14 + 0.16 * (1 - r)
            if dens > bayer[y % 4, x % 4]:
                im[y, x] = (*LIGHT, 255)
    return faces._fill_rest(im)


def flood_tex(n=32):
    """온 눈뜸 바닥 빛(타일): 고르게 성긴 점"""
    im = np.zeros((n, n, 4), np.uint8)
    bayer = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0
    for y in range(n):
        for x in range(n):
            if 0.2 > bayer[y % 4, x % 4]:
                im[y, x] = (*LIGHT, 255)
    return faces._fill_rest(im)


def shade_tex(w=24, h=96):
    """손가락 그늘: 손가락 밑동(v=0)에서 짙고 끝으로 갈수록 성긴 어두운 점묘, 가장자리는 들쭉날쭉"""
    im = np.zeros((h, w, 4), np.uint8)
    c = (w - 1) / 2
    for y in range(h):
        t = y / (h - 1)
        for x in range(w):
            d = abs(x - c) / (w / 2)
            dens = (0.92 - 0.45 * t) * (1.0 if d < 0.7 else max(0.0, 1 - (d - 0.7) / 0.3) * 0.9 + 0.1)
            if _hash(x, y, 15) < dens:
                k = 16 + int(_hash(x, y, 17) * 14)
                im[y, x] = (k + 8, k, k + 6, 255)
    return faces._fill_rest(im)


def plain(rgb):
    im = np.zeros((4, 4, 4), np.uint8)
    im[...] = (*rgb, 255)
    return im


# ---------------------------------------------------------------------------------------------- 모델

def face_box(k, s, front):
    """거대한 얼굴 판: 앞면은 front 텍스처(32), 옆, 뒤, 위, 아래는 스킨 머리. 가까운 얼굴, 실눈 뜸 오버레이 없음(눈은 상태로 바꿈)"""
    lo, hi = (-FACE / 2, -FACE / 2, -DEPTH / 2), (FACE / 2, FACE / 2, DEPTH / 2)
    s.box(lo, hi, {"south": front}, faces=("south",), uv=[0, 0, 16, 16], face=True)
    sides = {"north": k.skin_tex("back"), "east": k.skin_tex("left"), "west": k.skin_tex("right"), "up": k.skin_tex("top"),
             "down": k.skin_tex("bottom")}
    s.box(lo, hi, sides, faces=("north", "east", "west", "up", "down"), uv=[0, 0, 16, 16], face=True)
    s.front = (0, 0, 1)


def finger(m, name, wave):
    """손가락 하나: 두 마디 엔드 막대기(뿌리가 모델 가운데 = 땅 닿는 자리, 위로), 끝마디 앞면에 손톱 얼굴(자세마다 끝마디를 따라감)"""
    s = m.state(name)
    ch = s.rod((0, 0, 0), (0, 1, 0), wave=wave, toward=(0, 0, 1), base=False, poses=4, rag=False, **FINGER)
    for f in s.frames(4):
        tip, d = np.array(ch.tip(f.k)), np.array(ch.dirs[f.k][-1])
        ang = math.degrees(math.atan2(d[2], d[1]))
        nrm = np.array((0.0, -math.sin(math.radians(ang)), math.cos(math.radians(ang))))
        at = tip - d * 0.19 + nrm * (FINGER["thick"] / 2 + 0.035)
        with f:
            s.head(size=0.27, depth=0.09, at=tuple(at), face="smile", near=False, torn=False, rot=(ang, 0, 0))
    s.front = (0, 0, 0)


def build(k: kit.Kit):
    # 손가락(부품 1~6). 실눈 뜸 오버레이는 손톱 얼굴에만(찍을 때 Java 가 look.open)
    fm = k.model("b4_finger", poses=4, period=40, mut2=False, torn=False, rag=False)
    finger(fm, "", kit.Wave(amp=(0, 3), bias=(0, BEND), phase=22.5, shape="twitch"))
    finger(fm, "lift", kit.Wave(amp=(0, 7), bias=(0, BEND), lag=0))
    st = fm.state("broken")
    st.rod((0, 0, 0), (0, 1, 0), length=0.1, segs=1, thick=FINGER["thick"], toward=(0, 0, 1), base=False, rag=False)
    st.front = (0, 0, 0)

    # 뿔(부품 7~8): 네 마디가 +X 로 휘어 올라감(무지개 꼴). 막대기 안 물결은 작게, 큰 흔들림은 Java
    hm = k.model("b4_horn", poses=4, period=60, mut2=False, open=False, torn=False, rag=False)
    hs = hm.state("")
    hs.rod((0, -0.2, 0), (0, 1, 0), length=1.5, segs=4, thick=0.06, wave=kit.Wave(amp=(2, 3, 4, 5), lag=30, bias=(0, 16, 16, 16)),
           toward=(1, 0, 0), poses=4, rag=False)
    hs.front = (0, 0, 0)

    # 얼굴(부품 9)
    m = k.model(poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    face_box(k, m.state(""), k.face_tex("smile"))
    face_box(k, m.state("l"), k.texture("face_l", _eyes(True, False), classes=True))
    face_box(k, m.state("r"), k.texture("face_r", _eyes(False, True), classes=True))
    face_box(k, m.state("lr"), k.texture("face_lr", _eyes(True, True), classes=True))
    face_box(k, m.state("sink"), k.face_tex("tears"))

    # 손가락 그늘(부품 10~15): 뒤 끝(z = 0)에서 +Z 로 뻗는 폭 1, 길이 1 판
    sh = k.model("b4_shade", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    ss = sh.state("")
    ss.box((-0.5, 0, 0), (0.5, 0, 1), k.texture("shade", shade_tex()), uv=[0, 0, 16, 16], faces=("up",), shade=False)
    ss.front = (0, 0, 0)

    # 눈빛 원뿔(축 +Z, 길이 1.5, 끝 반지름 0.5): 축을 지나는 판 넷에 성긴 빛살
    b = k.model("b4_beam", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    bt = k.texture("beam", beam_tex())
    bs = b.state("")
    for ang in (0, 45, 90, 135):
        bs.box((-0.5, 0, 0), (0.5, 1.5, 0), bt, uv=[0, 16, 16, 0], faces=("south", "north"), rot=(90, 0, ang), origin=(0, 0, 0), shade=False)
    bs.front = (0, 0, 0)

    # 바닥 빛 자리
    sp = k.model("b4_spot", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for name, tex in (("", k.texture("spot", spot_tex())), ("rim", k.texture("spot_rim", spot_tex(fill=False)))):
        s = sp.state(name)
        s.box((-0.5, 0, -0.5), (0.5, 0, 0.5), tex, uv=[0, 0, 16, 16], faces=("up",), shade=False)
        s.front = (0, 0, 0)
    fl = sp.state("flood")
    ft = k.texture("flood", flood_tex())
    for ix in range(6):
        for iz in range(6):
            fl.box((-1.5 + ix * 0.5, 0, -1.5 + iz * 0.5), (-1.0 + ix * 0.5, 0, -1.0 + iz * 0.5), ft, uv=[0, 0, 16, 16], faces=("up",),
                   shade=False)
    fl.front = (0, 0, 0)

    # 눈빛에 굳어 가는 사람 머리(남들 눈에만, 머리 칸 모델): 머리 칸 아이템은 모델 -Z 가 사람 얼굴 쪽이라 얼굴을 북쪽 면에 둔다
    mk = k.model("b4_mask", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    rim = k.texture("rim", plain(LIGHT_EDGE))
    face32 = k.face_tex("smile")

    def frame(s, size, z):
        t = 0.05
        for (cx, cy, w, hh) in ((0, size / 2 + t / 2, size + 2 * t, t), (0, -size / 2 - t / 2, size + 2 * t, t),
                                (size / 2 + t / 2, 0, t, size), (-size / 2 - t / 2, 0, t, size)):
            s.box((cx - w / 2, cy - hh / 2, z), (cx + w / 2, cy + hh / 2, z), rim, uv=[0, 0, 4, 4], faces=("north",), shade=False)

    for name, size, z in (("s1", 0.44, -0.415), ("s2", 0.74, -0.425)):
        s = mk.state(name)
        s.box((-size / 2, -size / 2, z), (size / 2, size / 2, z), face32, uv=[0, 0, 16, 16], faces=("north",), face=True)
        frame(s, size, z)
    s3 = mk.state("s3")
    s3.head(size=0.9, depth=0.9, face="smile", rot=(0, 180, 0), near=False)
    frame(s3, 0.9, -0.46)
    mk.default = "s1"

    k.icon(model="b4", state="l")


def sounds(k: kit.Kit):
    # 받은 소리(data/sfx_lib.json, CC0 와 CC BY)만. 반경은 Java 와 같게(경기장 어디서나)
    k.sample("bell", ["oga_sfx100/gong_01", "oga_sfx100/gong_02", {"id": "kenney_impact/impactbell_heavy_001", "pitch": 1.2}], "뎅", pitch=0.55,
             attenuation=256)
    k.sample("hum", ["kenney_scifi/forcefield_001", "kenney_scifi/forcefield_000"], "웅웅", pitch=0.6, volume=0.9, attenuation=192)
    k.sample("lid", ["oga_creature80b/slime_02", "oga_creature80b/slime_08", "oga_creature80/eat_02"], "질척", pitch=0.45, attenuation=192)
    k.sample("stab", ["kenney_scifi/explosioncrunch_000", "kenney_scifi/lowfrequency_explosion_001", "oga_sfx100/slam_02"], "콰앙", pitch=0.7,
             attenuation=128)
    k.sample("lift", ["oga_breaking75/bfh1_rock_breaking_01", "oga_breaking75/bfh1_rock_breaking_02", "lrsf_horror/bonecrack_03"], "뿌드득",
             pitch=0.6, attenuation=96)
    k.sample("tear", ["lrsf_horror/bloop_drip_blood_drip_01", "lrsf_horror/bloop_drip_blood_drip_09", "oga_sfx100/plop_02"], "뚝", pitch=0.6,
             attenuation=64)
    k.sample("sigh", ["lrsf_horror/breath_scared_breath_scared_13", "lrsf_horror/breath_scared_breath_scared_08", "oga_wind/wind3"], "후우우",
             pitch=0.42, attenuation=256)
    k.sample("learn", ["oga_creature80/alien_02", "oga_creature80/alien_03", "oga_creature80b/alien_07"], "알았다", pitch=0.5, attenuation=192)
    k.sample("sink", ["oga_quake/shake", "kenney_scifi/lowfrequency_explosion_000"], "가라앉음", pitch=0.6, attenuation=256)


def phrases():
    return {
        "bs_sub_b4": "눈을 뜨려 한다",
        "bs_b4_p2": "다가온다",
        "bs_b4_p3": "넘어온다",
        "bs_b4_learn_bullet": "총알이 덜 먹힌다",
        "bs_b4_learn_pellet": "산탄이 덜 먹힌다",
        "bs_b4_learn_sniper": "저격이 덜 먹힌다",
        "bs_b4_learn_explosion": "폭발이 덜 먹힌다",
        "bs_b4_learn_fire": "불이 덜 먹힌다",
        "notice_b4_full": "눈빛에 굳었다",
        "cause_b4_gaze": "지평선이 봤다",
        "cause_b4_stab": "손가락에 찍혔다",
        "cause_b4_tear": "눈물에 맞았다",
        "cause_b4_ember": "불씨 땅을 밟았다",
    }
