"""B5 모두의 게이브(35웨이브 마지막 보스, docs/모듈/보스.md 16절): 쓰러뜨린 게이브와 쓰러진 동료의 머리를 모아 일어선 26블록 거인.

몸 배율 6.5(Java B5.S). 아래 모델은 모두 보통 크기로 굽고 Java 가 키운다. 다른 보스(B1 무더기, B3 고리 얼굴, B4 뿔과 눈)의 모델은 그 담당이
같은 때 만들고 있어 기대지 않고 같은 꼴을 여기서 따로 굽는다(이름은 모두 b5_ 로 시작).

- gave:gabe/b5(닻, 머리): head 1.6(세계 폭 10.4), 세로 흉터(stitch). 상태 ""(실눈: 판 내내 감겨 있다. 실눈 뜸 오버레이를 일부러 뺌: 표적을 찍어도
  안 뜬다), "open"(3단계 '눈을 떴다': 핏발 선 흰자가 활짝), "maw"(4단계: 턱이 떨어지고 입이 이빨 고리로 크게, 혀).
- gave:gabe/b5_heap(몸통 무더기, 부품 셋을 쌓음): 작은 얼굴 54개가 엉긴 덩어리 + 속살. 상태 ""(실눈 웃음들), "open"(3단계: 모두 눈을 뜸).
- gave:gabe/b5_arm(사슬 팔, 부품 둘): 작은 얼굴 여섯을 막대기 다섯으로 이은 사슬(어깨가 원점, -Y 로 3.38) + 끝 손가락 막대기 다섯.
  상태 ""(늘어짐), "raise"(치켜듦: 손가락 쫙), "slam"(내려찍음: 납작), "grab"(집음: 오므림), "cut"(끊김: 얼굴 둘과 날살 그루터기).
  큰 휘두름은 Java Part.pose(뿌리 축 회전), 손가락만 프레임 접기(자세 4).
- gave:gabe/b5_ring(허리 고리 얼굴, 부품 여덟): head 1.0 + 이웃과 맞잡는 손 막대기 둘 + 아래 처진 살 띠 둘. 상태 "", "clap"(손뼉: 손을 치켜듦).
- gave:gabe/b5_horn(뿔, 부품 둘): 휘어 오른 막대기 하나(둘째는 Java 가 반 바퀴 돌려 붙임).
- gave:gabe/b5_eye(이마 세 번째 눈): 세로로 선 눈 하나(가까운 얼굴 그림의 눈을 오려 세움). 상태 ""(감김), "open"(핏발 선 눈).
- gave:gabe/b5_drop(얼굴비로 떨어지는 작은 얼굴, 얼굴을 못 쓰는 사람의 머리 자리): head 0.4 + 짧은 막대기 둘.
- gave:gabe/b5_beam, b5_spot(이마 눈의 빛 원뿔과 바닥 빛 자리): G43 탐조등과 같은 꼴(컷아웃 빛살, 테두리 고리)을 따로 구움.
입속 작은 진짜 얼굴은 G01 모델(gave:gabe/g01)을 그대로 부품으로 붙인다(Java).
"""
import math

import numpy as np

from gabe import faces, kit

LIGHT = (250, 238, 150)
LIGHT_EDGE = (255, 214, 96)
# 무더기에 섞는 얼굴(실눈들), 3단계에 눈 뜬 얼굴들
HEAP_FACES = ["smile", "smile_a", "smile_c", "tears", "deep", "stitch", "torn", "smile", "blank", "smile_c"]
HEAP_OPEN = ["open", "bloodshot", "half", "open", "bloodshot", "open", "half", "bloodshot"]
ARM_FACES = ["smile", "tears", "smile_a", "torn", "smile_c", "deep"]
# 팔: 얼굴 크기, 잇는 막대기, 손가락(Java B5.ARM_LEN 과 같게: 6 x 0.28 + 5 x 0.25 + 0.45 = 3.38)
ARM_HEAD, ARM_LINK, ARM_FINGER = 0.28, 0.25, 0.45


def _hash(a, b, seed=0):
    v = math.sin(a * 127.1 + b * 311.7 + seed * 74.7) * 43758.5453
    return v - math.floor(v)


def plain(rgb, n=4):
    im = np.zeros((n, n, 4), np.uint8)
    im[...] = (*rgb, 255)
    return im


def maw_tex(n=32, seed=5):
    """입속: 검붉은 어둠(가운데가 더 검다)"""
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    r = np.hypot(xx - (n - 1) / 2, yy - (n - 1) / 2) / (n / 2)
    t = np.clip(r, 0, 1)[..., None]
    col = np.array([14, 4, 8], np.float32) * (1 - t) + np.array([74, 18, 30], np.float32) * t + rng.randn(n, n, 1) * 2.5
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(np.round(col), 0, 255)
    out[..., 3] = 255
    return out


def eye_tex(kind):
    """이마 눈: 가까운 얼굴(128)의 왼쪽 눈 둘레 12 x 12 칸(32 기준 x 2..14, y 9..21)을 오려 90도 돌려 세운다(부류 비트 그대로)"""
    f = faces.Face(eyes=kind, mouth="none", blush="none", tufts=False)
    a = faces.near(f)
    k = a.shape[0] // 32
    crop = a[9 * k:21 * k, 2 * k:14 * k]
    return np.ascontiguousarray(np.rot90(crop))


def beam_tex(w=32, h=128):
    """빛 원뿔 한 판: 위(v=0)가 눈 쪽 꼭짓점, 아래로 넓어짐. 성긴 세로 빛살(컷아웃)과 가장자리 선"""
    im = np.zeros((h, w, 4), np.uint8)
    c = (w - 1) / 2
    for y in range(h):
        hw = 1.2 + (c - 0.2) * (y / (h - 1)) ** 0.92
        for x in range(w):
            d = abs(x - c) / hw
            if d > 1.0:
                continue
            dens = 0.3 * (1 - d * d) ** 0.6 + 0.05
            edge = d > 0.88
            n = _hash(x, (y + x * 5) // 6, 3)
            if n < dens or (edge and _hash(x, y // 3, 9) < 0.55):
                im[y, x] = (*(LIGHT_EDGE if edge else LIGHT), 255)
    return faces._fill_rest(im)


def spot_tex(n=64):
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
                if r > 0.93 or (x + y) % 2 == 0:
                    im[y, x] = (*LIGHT_EDGE, 255)
                continue
            dens = 0.12 + 0.14 * (1 - r)
            if dens > bayer[y % 4, x % 4]:
                im[y, x] = (*LIGHT, 255)
    return faces._fill_rest(im)


def head(k, m):
    stitch = faces.Face(extras=("stitch",))
    wide = faces.Face(eyes="bloodshot", extras=("stitch",))
    gape = faces.Face(eyes="bloodshot", mouth="none", extras=("stitch",))
    dark = k.texture("maw", maw_tex())
    m.state("").head(size=1.6, depth=1.2, face=stitch)
    m.state("open").head(size=1.6, depth=1.2, face=wide)
    s = m.state("maw")
    h = s.head(size=1.6, depth=1.2, face=gape)
    z = 0.6
    # 입: 앞면 아래 절반을 덮는 검붉은 구멍, 떨어진 아래턱(살 상자), 이빨 고리 스물, 혀
    s.box((-0.5, -0.86, z + 0.004), (0.5, -0.16, z + 0.004), dark, uv=[0, 0, 16, 16], faces=("south",), shade=False)
    s.box((-0.56, -1.14, z - 0.5), (0.56, -0.8, z + 0.05), "flesh", face=True)
    s.box((-0.5, -0.8, z + 0.05), (0.5, -0.8, z - 0.45), dark, uv=[0, 0, 16, 16], faces=("up",), shade=False)
    s.teeth((0, -0.5, z + 0.01), 0.42, count=20, axis=(0, 0, 1), length=0.11, size=0.055, inward=0.35)
    s.band((0, -0.78, z - 0.1), (0, -0.25, 1), length=0.42, width=0.46, segs=2, thick=0.05, tex="flesh")
    del h


def heap(m):
    for name, fl, seed in (("", HEAP_FACES, 5), ("open", HEAP_OPEN, 5)):
        s = m.state(name)
        s.box((-0.62, -0.42, -0.62), (0.62, 0.42, 0.62), "flesh", face=True)
        s.lump(count=54, radius=(1.0, 0.72, 1.0), size=0.4, faces_list=fl, seed=seed)
        s.front = (0, 0, 0)


def arm_body(s, n_heads, fingers, face_shift=0):
    """어깨(원점)에서 -Y 로 내려가는 사슬 팔. fingers: None(없음) 또는 (벌림 도, 물결, 앞으로 굽힘)"""
    y = 0.0
    for i in range(n_heads):
        s.head(size=ARM_HEAD, depth=ARM_HEAD * 0.85, at=(0, y - ARM_HEAD / 2, 0), face=ARM_FACES[(i + face_shift) % len(ARM_FACES)],
               torn=False)
        y -= ARM_HEAD
        if i < 5:
            s.rod((0, y + 0.02, 0), (0, -1, 0), length=ARM_LINK + 0.04, segs=1, thick=0.09, base=False, rag=False, toward=(0, 0, 1))
            y -= ARM_LINK
    if fingers is None:
        return y
    spread, wave, curl = fingers
    for j in range(5):
        a = math.radians((j - 2) * spread)
        d = (math.sin(a), -math.cos(a), curl)
        s.rod((0.05 * (j - 2), y + 0.03, 0), d, length=ARM_FINGER, segs=2, thick=0.06, base=False, poses=4, rag=False,
              wave=wave if wave is None else kit.Wave(amp=wave.amp, lag=wave.lag, bias=wave.bias, phase=wave.phase + j * 50, shape=wave.shape),
              toward=(0, 0, 1))
    return y


def arm(m):
    arm_body(m.state(""), 6, (16, kit.Wave(amp=(5, 8), lag=40, bias=(4, 10)), 0.15))
    arm_body(m.state("raise"), 6, (32, kit.Wave(amp=(3, 4), lag=20, bias=(-14, -10), shape="twitch"), -0.1))
    arm_body(m.state("slam"), 6, (30, None, 0.0))
    arm_body(m.state("grab"), 6, (10, kit.Wave(amp=(6, 8), lag=30, bias=(34, 40)), 0.3))
    s = m.state("cut")
    y = arm_body(s, 2, None)
    s.rod((0, y + ARM_LINK + 0.02, 0), (0.1, -1, 0.05), length=0.2, segs=1, thick=0.09, base=False, rag=False, toward=(0, 0, 1))
    for side in (1, -1):
        s.band((side * 0.04, y + ARM_LINK, 0), (side * 0.3, -1, 0.1), length=0.22, width=0.12, segs=2, thick=0.02, tex="flesh")


def ring(m):
    for name, up in (("", 0.0), ("clap", 0.9)):
        s = m.state(name)
        h = s.head(size=1.0, depth=0.75, face="smile", torn=False)
        for side in (1, -1):
            s.rod(h.point(side * 0.92, -0.25, -0.2), (side, up, 0.1), length=0.6, segs=2, thick=0.08, poses=4, rag=False,
                  wave=kit.Wave(amp=(5, 7), lag=40, bias=(0, 6), phase=0 if side > 0 else 90))
            s.band(h.point(side * 0.45, -1, 0.3), (side * 0.12, -1, -0.1), length=0.34, width=0.3, segs=2, thick=0.025, tex="wetskin",
                   wave=kit.Wave(amp=(6, 9), lag=45, phase=0 if side > 0 else 120), toward=(0, 0, 1), poses=4)


def build(k: kit.Kit):
    # 닻(머리): 보스는 판 규칙(변이)에서 빠지고, 실눈 뜸 오버레이는 일부러 없다(눈은 3단계 상태 open 에서만 뜬다)
    head(k, k.model(poses=1, period=20, mut2=False, open=False, torn=False, rag=False))
    heap(k.model("b5_heap", poses=1, period=20, mut2=False, open=False, torn=False, rag=False))
    arm(k.model("b5_arm", poses=4, period=40, shrink=0.45, mut2=False, torn=False, rag=False))
    ring(k.model("b5_ring", poses=4, period=40, mut2=False, torn=False, rag=False))

    hn = k.model("b5_horn", poses=4, period=60, mut2=False, open=False, torn=False, rag=False)
    hn.state("").rod((0, 0, 0), (0.55, 1, -0.15), length=1.5, segs=4, thick=0.075, rag=False,
                     wave=kit.Wave(amp=(2, 3, 4, 5), lag=30, bias=(0, 16, 16, 16)), toward=(1, 0, 0))

    ey = k.model("b5_eye", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for name, kind in (("", "squint"), ("open", "bloodshot")):
        s = ey.state(name)
        s.box((-0.22, -0.26, 0), (0.22, 0.26, 0), k.texture("eye_" + (name or "shut"), eye_tex(kind), classes=True), uv=[0, 0, 16, 16],
              faces=("south",), face=True)
        s.front = (0, 0, 1)

    dr = k.model("b5_drop", poses=4, period=20, mut2=False, torn=False, rag=False)
    s = dr.state("")
    h = s.head(size=0.4, depth=0.3, face="smile", torn=False)
    for side in (1, -1):
        s.rod(h.point(side * 0.9, -0.4, -0.5), (side, 0.3, -0.4), length=0.3, segs=2, thick=0.05, poses=4, rag=False,
              wave=kit.Wave(amp=(16, 14), lag=50, shape="flap", phase=0 if side > 0 else 30))

    b = k.model("b5_beam", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    bt = k.texture("beam", beam_tex())
    bs = b.state("")
    for ang in (0, 45, 90, 135):
        # 세로 판(폭 1, 길이 1.5)을 X 로 눕혀 +Z 축에 놓고 축 둘레로 돌림. v 를 뒤집어 꼭짓점(v=0)이 눈 쪽
        bs.box((-0.5, 0, 0), (0.5, 1.5, 0), bt, uv=[0, 16, 16, 0], faces=("south", "north"), rot=(90, 0, ang), origin=(0, 0, 0), shade=False)
    bs.front = (0, 0, 0)

    sp = k.model("b5_spot", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    ss = sp.state("")
    ss.box((-0.5, 0, -0.5), (0.5, 0, 0.5), k.texture("spot", spot_tex()), uv=[0, 0, 16, 16], faces=("up",), shade=False)
    ss.front = (0, 0, 0)

    k.icon(model="b5", state="")


def sounds(k: kit.Kit):
    # 받은 소리만(data/sfx_lib.json). 반경은 Java(B5.java 의 sound, sfx)와 같게
    # 낮은 합창: 말 없는 유령 허밍(oga_humming, CC0). 판 내내 실눈이던 거인이 부르는 것
    k.sample("choir", [{"id": "oga_humming/ghostly_humming#2", "pitch": 0.6}, {"id": "oga_humming/ghostly_humming#8", "pitch": 0.6},
                       {"id": "oga_humming/ghostly_humming#11", "pitch": 0.55}], "낮은 합창", volume=0.8, attenuation=192, level=-14.0)
    # 속삭임: 여러 목소리(oga_madness, CC0. 읽는 글은 뜻 없는 흉내 글이라 실제 말이 아님, 낮추고 작게). 몸에 박힌 죽은 사람들
    k.sample("whisper", [{"id": "oga_madness/a3_menacing#15", "pitch": 0.85}, {"id": "oga_madness/a4_scared#4", "pitch": 0.9},
                         {"id": "oga_madness/a2_scared#3", "pitch": 0.8}], "속삭임", volume=0.7, attenuation=48, level=-20.0)
    k.sample("heart", ["oga_heartbeat/heartbeat_slow_0", "oga_heartbeat/heartbeat_slow_reverb"], "두근", pitch=0.7, attenuation=256)
    k.sample("roar", [{"id": "lrsf_horror/monster_03", "pitch": 0.5}, {"id": "lrsf_horror/monster_04", "pitch": 0.55},
                      {"id": "oga_horn/theircoming3_0", "pitch": 0.7}], "겹친 포효", attenuation=256)
    k.sample("slam", ["kenney_scifi/lowfrequency_explosion_001", "kenney_scifi/explosioncrunch_004", "kenney_scifi/explosioncrunch_000"],
             "콰앙", pitch=0.7, attenuation=128)
    k.sample("gulp", ["oga_creature80/burp_01", "oga_creature80/burp_02", "oga_creature80/eat_02"], "꿀꺽", pitch=0.6, attenuation=64)
    k.sample("inhale", [{"id": "lrsf_horror/breath_scared_breath_scared_13", "pitch": 0.4}, {"id": "lrsf_horror/breath_scared_breath_scared_08",
                                                                                               "pitch": 0.38}], "크게 들이쉼", attenuation=160)
    k.sample("exhale", [{"id": "oga_wind/wind3", "pitch": 0.7}, {"id": "kenney_scifi/lowfrequency_explosion_000", "pitch": 0.8}], "후욱",
             attenuation=160)
    k.sample("open", ["oga_sfx100/gong_01", "oga_sfx100/gong_02"], "눈을 떴다", pitch=0.45, attenuation=256)
    k.sample("giggle", [{"id": "oga_giggle/group_giggling", "start": 0.0, "end": 0.9}, {"id": "oga_giggle/group_giggling", "start": 2.2, "end": 3.1}],
             "킥", pitch=1.3, volume=0.8, attenuation=24)
    k.sample("click", "kenney_interface/switch_003", "딸깍", pitch=0.6, attenuation=256)
    # 엔딩 새벽(바탕 BossDirector 가 gave:gabe.b5.dawn 을 부름): 받은 새벽 새소리(oga_birds, oga_morning, CC0)
    k.sample("dawn", [{"id": "oga_birds/birds_isaiah658", "start": 0.0, "end": 10.0}, {"id": "oga_birds/birds_isaiah658", "start": 11.0, "end": 21.0},
                      {"id": "oga_morning/amb_morning", "start": 4.0, "end": 14.0}], "새소리", attenuation=256, level=-18.0)
    # 맵 장치: 투광등 깨짐, 다시 켜짐, 엔진 분사, 관제 경보
    k.sample("lamp", ["kenney_impact/impactglass_heavy_000", "kenney_impact/impactglass_heavy_002"], "등이 깨졌다", pitch=0.8, attenuation=96)
    k.sample("relight", "kenney_interface/switch_005", "등이 켜졌다", pitch=0.9, attenuation=64)
    k.sample("engine", ["kenney_scifi/thrusterfire_001", "kenney_scifi/thrusterfire_003"], "엔진 분사", pitch=0.8, attenuation=192)
    k.sample("alarm", "kenney_digital/lowthreetone", "관제 경보", pitch=0.8, volume=0.8, attenuation=96)


def phrases():
    return {
        "bs_sub_b5": "모두 여기 있다",
        "bs_b5_p2": "두른다",
        "bs_b5_p3": "눈을 떴다",
        "bs_b5_p4": "마지막 얼굴",
        "cause_b5_arm": "팔에 맞았다",
        "cause_b5_gaze": "이마의 눈이 봤다",
        "cause_b5_breath": "숨에 휩쓸렸다",
        "cause_b5_hug": "모두에게 안겼다",
        "cause_b5_clap": "손뼉 파도를 못 넘었다",
        "cause_b5_rain": "떨어진 얼굴에 맞았다",
        "cause_b5_ring": "고리에 부딪혔다",
        "cause_b5_fire": "분사에 휩쓸렸다",
        "hint_b5_lamp": "꺼진 등 밑에 서라",
        "hint_b5_engine": "초록 고리를 지켜 분사",
        "hint_b5_cover": "더미 뒤로",
    }
