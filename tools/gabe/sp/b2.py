"""B2 하늘 덮는 게이브(20웨이브 보스, docs/모듈/보스.md 13절). 아래를 보고 엎어진 28블록 얼굴이 하늘이 되어 내려다본다.

모델(크기는 Java kr.gave.mob.sp.B2 가 키움: 얼굴 x16, 기둥 x9.3, 떨어지는 얼굴 x3, 그림자 판은 블록 수 그대로):
  gave:gabe/b2        얼굴(앞면이 아래 -Y, 이마가 +Z). 가장자리는 젖은 천 띠 여덟이 처져 출렁인다.
      ""       실눈 웃음, 점 입
      "mouth"  입 벌림(이빨 고리 입 + 아래로 솟은 뼈 이빨): 얼굴비, 삼킨 동안
      "torn"   3단계: 벌집 구멍, 반쯤 뜬 눈, 짙은 홍조, 핏줄, 찢겨 늘어진 살 조각 셋
      "tmouth" 3단계에서 입 벌림
  gave:gabe/b2_pole   곧은 갈고리 기둥(뿌리 위, 끝 아래). 물결 없음(자세 1): 새장 창살, 왕관처럼 곧게 서서 오르내리기만 한다.
      ""       끝에 꺾인 갈고리 하나와 작은 실눈 얼굴
      "hook"   갈고리가 크게 벌어지고 끝 얼굴이 눈을 뜸(끌어 올리기)
      "slam"   땅에 박힌 동안(끝 얼굴이 운다: 약점)
  gave:gabe/b2_drop   떨어지는 작은 얼굴(얼굴비): 얼굴 + 짧은 막대기 둘
  gave:gabe/b2_shade  바닥 그림자 판(검은 점묘, 컷아웃). "" 얼굴 밑 큰 그림자(네모), "dark" 내려앉기 직전 짙음,
                      "spot" 떨어질 자리 작은 원, "spot_dark" 떨어지기 직전
소리는 모두 받은 소리(data/sfx_lib.json, k.sample). 글귀는 phrases().
"""
import math

import numpy as np
from PIL import Image

from gabe import faces, kit

HEAD, DEPTH = 1.75, 0.25
BOT = -DEPTH / 2  # 얼굴 앞면(아래)
# 입, 눈 자리(모델 블록, 돌린 뒤): 얼굴 그림 32 칸 기준 입 가운데 (17.5, 25), 눈 가운데 (8, 13), (24, 13)
MOUTH = ((17.5 / 32 - 0.5) * HEAD, BOT, (0.5 - 25.0 / 32) * HEAD)


def _bayer(n):
    b = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16.0
    return np.tile(b, (n // 4 + 1, n // 4 + 1))[:n, :n]


def shade_tex(n, dens, square, seed=0):
    """검은 점묘 그림자(컷아웃이라 반투명 대신 디더): 가운데는 dens, 가장자리로 갈수록 성기게. square 면 둥근 네모(얼굴 발자국)"""
    out = np.zeros((n, n, 4), np.uint8)
    bay = _bayer(n)
    c = (n - 1) / 2
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    dx, dy = np.abs(xx - c) / (n / 2), np.abs(yy - c) / (n / 2)
    if square:
        r = (dx ** 6 + dy ** 6) ** (1 / 6.0)
    else:
        r = np.hypot(dx, dy)
    d = np.where(r < 0.7, dens, np.clip(dens * (1.0 - r) / 0.3, 0, 1))
    rng = np.random.RandomState(seed)
    on = (d > bay) & (r <= 1.0)
    col = np.array([10, 8, 12], np.float32) + rng.rand(n, n, 1) * 6
    out[..., :3] = np.clip(col, 0, 255)
    out[..., 3] = np.where(on, 255, 0)
    return out


def face_body(s, face, mouth, torn):
    """얼굴 하나: 엎어진 머리, 가장자리 젖은 천 띠 여덟, (입 벌림) 뼈 이빨 고리, (3단계) 찢겨 늘어진 살 조각"""
    s.head(size=HEAD, depth=DEPTH, face=face, rot=(90, 0, 0), torn=False)
    # 가장자리: 옆마다 띠 둘(폭이 옆을 따라), 아래로 처지고 바깥 안쪽으로 느리게 출렁
    i = 0
    for sx, sz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        for half in (-1, 1):
            off = half * HEAD * 0.245
            root = (sx * HEAD / 2 * 0.985 + (off if sz else 0), BOT + 0.02, sz * HEAD / 2 * 0.985 + (off if sx else 0))
            w = kit.Wave(amp=(5, 8, 11), lag=40, bias=(2, 4, 6), phase=i * 77)
            s.band(root, (sx * 0.18, -1.0, sz * 0.18), length=0.2 if not torn else 0.26, width=HEAD * 0.47, segs=3, thick=0.012, wave=w,
                   toward=(sx, 0, sz), tex="wetskin", poses=4)
            i += 1
    if mouth:
        # 벌어진 입 둘레 뼈 이빨: 아래로 솟고 안으로 기욺
        s.teeth((MOUTH[0], BOT - 0.004, MOUTH[2]), radius=0.2, count=14, axis=(0, -1, 0), length=0.085, size=0.045, inward=0.35)
    if torn:
        # 찢긴 살 조각 셋(볼과 이마 밑): 짧고 넓게
        for j, (x, z, wd) in enumerate(((-0.52, -0.2, 0.3), (0.5, 0.05, 0.26), (-0.1, 0.55, 0.34))):
            w = kit.Wave(amp=(7, 12), lag=50, bias=(0, 5), phase=j * 120)
            s.band((x, BOT - 0.002, z), (0.05, -1.0, 0.1), length=0.2, width=wd, segs=2, thick=0.012, wave=w, toward=(0, 0, 1), tex="flesh",
                   poses=4)


def pole_body(s, face, hook):
    """기둥 하나: 곧은 대(위 1.45 에서 아래 -1.3), 끝에 꺾인 갈고리와 작은 얼굴. hook 이면 갈고리가 크게 벌어짐"""
    s.rod((0, 1.45, 0), (0, -1, 0), length=2.75, segs=2, thick=0.085, base=False, toward=(0, 0, 1))
    a, b = (0.4, 0.36) if hook else (0.3, 0.26)
    ch = s.rod((0, -1.1, 0), (0.9, -0.42, 0.0), length=a, segs=1, thick=0.07, base=False, toward=(0, 0, 1))
    tip = ch.tip()
    s.rod(tip, (0.3 if hook else 0.2, 1.0, 0.0), length=b, segs=1, thick=0.06, base=False, toward=(0, 0, 1))
    s.head(size=0.3, depth=0.24, at=(0, -1.33, 0), face=face, torn=False)


def build(k: kit.Kit):
    m = k.model(poses=4, period=20, mut2=False, torn=False)
    torn_face = faces.Face(eyes="half", mouth="honeycomb", blush="deep", extras=("veins",))
    torn_mouth = faces.Face(eyes="half", mouth="teeth_ring", blush="deep", extras=("veins",))
    face_body(m.state(""), "smile", False, False)
    face_body(m.state("mouth"), "teeth_ring", True, False)
    face_body(m.state("torn"), torn_face, False, True)
    face_body(m.state("tmouth"), torn_mouth, True, True)

    pm = k.model("b2_pole", poses=1, mut2=False, torn=False, open=False)
    pole_body(pm.state(""), "smile", False)
    pole_body(pm.state("hook"), "open", True)
    pole_body(pm.state("slam"), "tears", False)

    dm = k.model("b2_drop", poses=1, mut2=False, torn=False, open=False, rag=False)
    s = dm.state("")
    h = s.head(size=0.4, depth=0.3, face="smile", torn=False)
    for side in (1, -1):
        s.rod(h.point(side * 0.9, -0.4, -0.5), (side, 0.25, -0.45), length=0.36, segs=1, thick=0.06, base=False)

    sm = k.model("b2_shade", poses=1, mut2=False, torn=False, open=False, rag=False)
    for name, tex in (("", shade_tex(128, 0.42, True, 1)), ("dark", shade_tex(128, 0.86, True, 2)), ("spot", shade_tex(32, 0.5, False, 3)),
                      ("spot_dark", shade_tex(32, 0.92, False, 4))):
        st = sm.state(name)
        st.front = (0, 0, 0)
        st.quad((0, 0, 0), (1.0, 1.0), "up", k.texture("shade_" + (name or "big"), tex))

    # 아이콘: 올려다본 하늘 얼굴(얼굴 그림 그대로)
    k.icon(img=Image.fromarray(faces.far(faces.preset("smile"))).resize((64, 64), Image.NEAREST))


def sounds(k: kit.Kit):
    k.sample("wind", ["oga_wind/wind", "oga_wind/wind2"], "하늘 바람", pitch=0.7, attenuation=128)
    k.sample("gurgle", ["oga_creature80/burble_01", "oga_creature80/burble_02", {"id": "oga_creature80/burp_02", "pitch": 0.8}], "꿀렁", pitch=0.5,
             attenuation=128)
    k.sample("drop", ["oga_sfx100/plop_01", "oga_sfx100/plop_02"], "뚝", pitch=0.8, attenuation=32)
    k.sample("splat", ["oga_sfx100/splash_01", "oga_sfx100/splash_02", "oga_creature80b/slime_02"], "철퍽", pitch=0.9, attenuation=24)
    k.sample("slam", ["oga_stomp/stomp", "oga_creature80b/stomp_01", "kenney_impact/impactpunch_heavy_002"], "쿵", pitch=0.7, attenuation=96)
    k.sample("creak", ["lrsf_horror/gate_open_00", "kenney_rpg/creak1", "kenney_rpg/creak2"], "끼이익", pitch=0.5, attenuation=64)
    k.sample("gulp", ["oga_creature80/eat_02", {"id": "oga_creature80/burp_01", "pitch": 0.9}], "꿀꺽", pitch=0.6, attenuation=96)
    k.sample("drone", ["kenney_scifi/spaceenginelow_002", "kenney_scifi/spaceenginelow_001"], "우우웅", pitch=0.7, attenuation=160)
    k.sample("roar", ["lrsf_horror/monster_05", "lrsf_horror/monster_00", "oga_creature80/roar_02"], "하늘이 운다", pitch=0.5, attenuation=192)
    k.sample("thunder", ["oga_thunder/rain_thunder#2", "oga_thunder/rain_thunder#4", "oga_thunder/rain_thunder#9"], "천둥", pitch=0.9,
             attenuation=192)
    k.sample("fall", ["oga_thunder/rain_thunder#7", {"id": "oga_horn/theircoming3_0", "pitch": 0.8}], "하늘이 떨어진다", pitch=0.6, attenuation=192)
    k.sample("snap", ["kenney_impact/impactwood_heavy_000", "kenney_impact/impactwood_heavy_002"], "우지끈", pitch=0.6, attenuation=96)


def phrases():
    return {
        "bs_sub_b2": "하늘이 웃는다",
        "bs_b2_p2": "기둥이 내려온다",
        "bs_b2_p3": "하늘이 내려앉는다",
        "cause_b2_rain": "떨어진 얼굴에 맞았다",
        "cause_b2_pole": "기둥에 찍혔다",
        "cause_b2_press": "하늘에 눌렸다",
        "cause_b2_bolt": "번개에 맞았다",
    }
