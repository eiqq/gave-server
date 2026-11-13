"""B1 산더미 게이브(보스, 10웨이브. docs/모듈/보스.md 12절) + 다섯 보스가 같이 쓰는 공용 에셋(6.1, 9, 11.11절).

공용(약속은 kr.gave.mob.boss.Tele 머리말): 바닥에 눕는 얇은 판 모델 다섯. 가운데 (0, 0, 0), 윗면만 그림(컷아웃 점묘, 반투명 없음).
  gabe/b1_warn_disc   빨간 원, 반지름 1.   ""  = 테두리 + 옅은 점묘,  "fill" = 짙은 점묘 + 밝은 테두리(가운데에서 차오름)
  gabe/b1_warn_strip  빨간 띠, 폭 1 길이 1(뒤 끝 z = 0 에서 +Z 로).  "" = 테두리 + 화살 무늬,  "fill" = 짙은 점묘 + 밝은 앞 끝
  gabe/b1_shadow      검은 그림자 원, 반지름 1.  "" 옅음,  "dark" 짙음
  gabe/b1_safe        초록 안전 고리, 반지름 1
  gabe/b1_wave        흰 파도 고리, 반지름 1(둘레 띠만)
공용 소리(받은 소리, 합성 없음): gave:boss.wave(무너짐 물결 쿵), boss.weak(약점 칭), boss.immune(안 먹힘 퉁), boss.next(다음이 보스: 먼 땅울림).
레터박스 덮개는 make_hud 가 만든다(키 letterbox).

B1 몸(Java kr.gave.mob.sp.B1, 몸 배율 4.5):
  gabe/b1        닻. 얼굴 상자(폭 1.6, 깊이 1.2 → 7.2 x 5.4블록). 상태 ""(실눈 웃음), "crack"(바늘땀 이음선 + 찢어진 입), "split"(두 쪽으로
                 벌어지고 속 얼굴: 핏발 선 눈, 이빨 고리 입). 뒤통수 가운데 기둥 끝의 네 얼굴 작은 머리(약점: 부위 "back" 이 켜지면 눈을 뜸)
  gabe/b1_heap   부품(세계 축). 작은 얼굴 24개가 엉긴 무더기(지름 12, 높이 4블록). 상태 "", "shake"(크게 출렁)
  gabe/b1_rod    부품 넷. 곧은 엔드 막대기 하나(길이 1.1, 부품 배율 2 → 10블록). 상태 "", "cut"(그루터기). 큰 젓기는 Java 가 부품 보간으로
막대기는 늘어뜨리지 않는다: 뒤통수 위에 선 돛대 넷(왕관), 약점을 받친 기둥 하나.
"""
import math

import numpy as np

from gabe import faces, kit

HEAD_W, HEAD_D = 1.6, 1.2
# 뒤통수 기둥 끝 작은 머리의 가운데(몸 틀, Java B1.BACK_Z 와 같게)
BACK_POST, BACK_HALF = 0.42, 0.24
BACK_Z = -(HEAD_D / 2 + BACK_POST + BACK_HALF - 0.04)
# 갈라짐: 반쪽이 옆으로 벌어지는 거리, 문처럼 열리는 각(도), 속 얼굴 크기와 자리(Java B1 의 속 눈 자리와 같게)
SPLIT_GAP, SPLIT_TILT = 0.5, 14.0
INNER, INNER_AT = 1.2, (0.0, -0.04, 0.0)


# ============================================================================================== 공용: 바닥 예고 판


def _halftone(w, h, cell, dens):
    """점묘(벌집 격자, 칸 cell 화소): 점 가운데 (x, y) 화소의 짙기 dens(x, y) 0..1 → 그 점의 넓이. bool 마스크(h, w)"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    rh = cell * 0.866
    row = np.floor(yy / rh)
    odd = (row % 2) * cell / 2
    cx = (np.floor((xx - odd) / cell) + 0.5) * cell + odd
    cy = (row + 0.5) * rh
    d = np.hypot(xx + 0.5 - cx, yy + 0.5 - cy)
    rad = cell * np.sqrt(np.clip(dens(cx, cy), 0, 0.68) * 0.866 / math.pi)
    return d <= rad


def _paint(w, h, layers):
    """[(마스크, (r, g, b))...] 를 차례로 덧칠(뒤가 위) → RGBA(알파 0 또는 255)"""
    out = np.zeros((h, w, 4), np.uint8)
    for mask, rgb in layers:
        out[mask, 0], out[mask, 1], out[mask, 2], out[mask, 3] = rgb[0], rgb[1], rgb[2], 255
    return out


def _radial(n):
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float64)
    return np.hypot((xx + 0.5) / n * 2 - 1, (yy + 0.5) / n * 2 - 1)


RED, RED_DIM, RED_HOT, RED_EDGE = (228, 44, 36), (170, 30, 28), (255, 96, 44), (255, 168, 84)
BLACK = (14, 12, 12)
GREEN, GREEN_DIM = (72, 228, 108), (48, 168, 80)
WHITE, WHITE_DIM = (242, 238, 224), (206, 200, 186)


def _rn(n):
    """점 가운데(화소)에서 판 가운데까지 거리 0..1 을 주는 함수"""
    return lambda cx, cy: np.hypot(cx / n * 2 - 1, cy / n * 2 - 1)


def _plate(k, name, states, strip=False):
    """판 모델 하나: 윗면 하나(두께 0). states = {상태: 텍스처 id}"""
    m = k.model(name, poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for st, tex in states.items():
        s = m.state(st)
        if strip:
            s.quad((0, 0, 0.5), (1, 1), "up", tex)
        else:
            s.quad((0, 0, 0), (2, 2), "up", tex)
        s.front = (0, 0, 0)
    return m


def plates(k):
    n = 128
    r = _radial(n)
    rn = _rn(n)
    # 빨간 원: 테두리 고리 + 옅은 점묘. fill 은 가운데에서 커져 테두리에 닿는 순간이 맞는 때
    base = _paint(n, n, [(_halftone(n, n, 6, lambda x, y: np.where(rn(x, y) < 0.88, 0.2, 0)), RED_DIM), ((r >= 0.925) & (r <= 1.0), RED)])
    fill = _paint(n, n, [(_halftone(n, n, 6, lambda x, y: np.where(rn(x, y) < 0.9, 0.62, 0)), RED_HOT), ((r >= 0.9) & (r <= 1.0), RED_EDGE)])
    _plate(k, "b1_warn_disc", {"": k.texture("warn_disc", base), "fill": k.texture("warn_disc_fill", fill)})

    # 검은 그림자: 가장자리가 풀리는 점묘. dark 는 더 촘촘하고 짙게
    lo = _paint(n, n, [(_halftone(n, n, 6, lambda x, y: 0.4 * np.clip((1 - rn(x, y)) / 0.3, 0, 1) ** 0.7), BLACK)])
    hi = _paint(n, n, [(_halftone(n, n, 4, lambda x, y: 0.68 * np.clip((1 - rn(x, y)) / 0.16, 0, 1) ** 0.5), BLACK)])
    _plate(k, "b1_shadow", {"": k.texture("shadow", lo), "dark": k.texture("shadow_dark", hi)})

    # 초록 안전 고리: 굵은 테두리 + 안쪽 끊긴 고리(안은 비워 땅이 보이게)
    ang = np.arctan2(*np.mgrid[0:n, 0:n][::-1].astype(np.float64) - (n - 1) / 2)
    dash = (np.floor(ang / (2 * math.pi) * 24) % 2) == 0
    safe = _paint(n, n, [((r >= 0.78) & (r <= 0.82) & dash, GREEN_DIM), ((r >= 0.9) & (r <= 1.0), GREEN)])
    _plate(k, "b1_safe", {"": k.texture("safe", safe)})

    # 흰 파도 고리: 앞마루(둘레) 실선 + 뒤따르는 점묘 띠. 반지름으로 키우므로 띠를 가늘게(256 화소)
    n2 = 256
    r2 = _radial(n2)
    rn2 = _rn(n2)
    wave = _paint(n2, n2, [(_halftone(n2, n2, 5, lambda x, y: np.where((rn2(x, y) > 0.84) & (rn2(x, y) < 0.955),
                                                                      0.6 * np.clip((rn2(x, y) - 0.84) / 0.1, 0, 1), 0)), WHITE_DIM),
                           ((r2 >= 0.95) & (r2 <= 1.0), WHITE)])
    _plate(k, "b1_wave", {"": k.texture("wave", wave)})

    # 빨간 띠(폭 32 x 길이 128 화소, 아래쪽이 앞 +Z): 옆 테두리 + 앞을 가리키는 화살 무늬. fill 은 뒤 끝에서 자라 앞 끝에 닿는 순간이 맞는 때
    w, h = 32, 128
    yy, xx = np.mgrid[0:h, 0:w]
    side = (xx < 2) | (xx >= w - 2)
    ends = (yy < 2) | (yy >= h - 2)
    chev = (((yy + np.abs(xx - w / 2 + 0.5).astype(int)) % 32) < 3) & ~side
    sbase = _paint(w, h, [(_halftone(w, h, 6, lambda x, y: np.full_like(x, 0.16)) & ~side & ~ends, RED_DIM), (chev, RED), (side | ends, RED)])
    sfill = _paint(w, h, [(_halftone(w, h, 6, lambda x, y: np.full_like(x, 0.62)) & ~side, RED_HOT), (side, RED_EDGE), (yy >= h - 5, RED_EDGE)])
    _plate(k, "b1_warn_strip", {"": k.texture("warn_strip", sbase), "fill": k.texture("warn_strip_fill", sfill)}, strip=True)


# ============================================================================================== B1 몸


def _tower(k, s):
    """뒤통수 가운데에서 뒤로 선 기둥과 그 끝의 네 얼굴 작은 머리(약점). 엎어지면 기둥이 곧게 서서 사방에서 보인다.
    작은 머리는 몸 -Z 를 위로 삼게 돌려(rot x -90) 네 옆면에 얼굴을 바로 세운다: 부위 back 이 켜지면 뜬 눈"""
    s.rod((0, 0, -HEAD_D / 2 + 0.03), (0, 0, -1), length=BACK_POST, segs=1, thick=0.11, toward=(0, 1, 0))
    c = (0.0, 0.0, BACK_Z)
    lo, hi = tuple(v - BACK_HALF for v in c), tuple(v + BACK_HALF for v in c)
    for cond, face in (("!on:back", "smile"), ("on:back", "open")):
        ft = k.face_tex(face)
        with s.when(cond):
            s.box(lo, hi, {n: ft for n in ("south", "north", "east", "west")}, faces=("south", "north", "east", "west"),
                  uv=[0, 0, 16, 16], rot=(-90, 0, 0), origin=c, face=True)
    s.box(lo, hi, {"up": k.skin_tex("top"), "down": k.skin_tex("bottom")}, faces=("up", "down"), uv=[0, 0, 16, 16], rot=(-90, 0, 0),
          origin=c, face=True)


def _half(k, s, side, face):
    """갈라진 얼굴의 한쪽(side +1 = 게이브 왼쪽 +X). SPLIT_GAP 만큼 옆으로 벌어지고 바깥 모서리를 축으로 문처럼 SPLIT_TILT 도 열린다"""
    w, hh, d = HEAD_W / 2, HEAD_W / 2, HEAD_D / 2
    x0, x1 = (SPLIT_GAP, SPLIT_GAP + w) if side > 0 else (-SPLIT_GAP - w, -SPLIT_GAP)
    f = face.with_(cut="left" if side > 0 else "right")
    front, back = ([8, 0, 16, 16], [0, 0, 8, 16]) if side > 0 else ([0, 0, 8, 16], [8, 0, 16, 16])
    hinge = (x1 if side > 0 else x0, 0, 0)
    rot = (0, side * SPLIT_TILT, 0)
    outer, inner = ("east", "west") if side > 0 else ("west", "east")
    tex = {"south": k.face_tex(f), "north": k.skin_tex("back"), outer: k.skin_tex("left" if side > 0 else "right"),
           inner: k.part_tex("flesh"), "up": k.skin_tex("top"), "down": k.skin_tex("bottom")}
    uv = {"south": front, "north": back, outer: [0, 0, 16, 16], inner: [0, 0, 16, 16], "up": front, "down": front}
    s.box((x0, -hh, -d), (x1, hh, d), {"south": tex["south"]}, faces=("south",), uv=uv, dist="far", face=True, rot=rot, origin=hinge)
    s.box((x0, -hh, -d), (x1, hh, d), {n: tex[n] for n in ("north", outer, inner, "up", "down")}, faces=("north", outer, inner, "up", "down"),
          uv=uv, face=True, rot=rot, origin=hinge)
    ep = 1.0 / 16
    s.quad(((x0 + x1) / 2, 0, d + 0.08 * ep), (w, HEAD_W), "south", k.face_tex(f, near=True), uv=front, dist="near", face=True, rot=rot,
           origin=hinge)


def body(k):
    m = k.model(poses=4, period=40, mut2=False, torn=False, rag=False)
    s = m.state("")
    s.head(size=HEAD_W, depth=HEAD_D, face="smile")
    _tower(k, s)

    cracked = faces.Face(mouth="torn", extras=("stitch",))
    s = m.state("crack")
    s.head(size=HEAD_W, depth=HEAD_D, face=cracked)
    _tower(k, s)

    s = m.state("split")
    for side in (1, -1):
        _half(k, s, side, cracked)
    inner = faces.Face(eyes="bloodshot", mouth="teeth_ring", blush="deep")
    h = s.head(size=INNER, depth=1.0, at=INNER_AT, face=inner, open=False)
    # 입 둘레 뼈 이빨 고리(얼굴 그림의 입 자리: 32 화소 기준 x 16..19, y 24..27)
    s.teeth(h.point((17.5 / 32 - 0.5) * 2, (0.5 - 25.5 / 32) * 2, 1.02), radius=0.15, count=12, axis=(0, 0, 1), length=0.11, size=0.05)
    _tower(k, s)
    s.front = (0, 0, 1)
    return m


def heap(k):
    """무더기: 작은 얼굴 24개가 살덩이 둘레에 엉겨 바깥을 본다(아랫단 13, 윗단 11). 자세 2 장으로 꿈틀, shake 는 크게 출렁"""
    m = k.model("b1_heap", poses=2, period=40, mut2=False, open=False, torn=False, rag=False)
    names = ["smile", "smile_a", "smile_c", "half"]
    rng = np.random.RandomState(11)
    spots = []
    for ring, (count, rad, y, tilt) in enumerate(((13, 1.13, -0.2, 6.0), (11, 0.86, 0.1, 24.0))):
        for i in range(count):
            a = 2 * math.pi * (i + 0.5 * ring + rng.uniform(-0.18, 0.18)) / count
            rr = rad + rng.uniform(-0.07, 0.05)
            spots.append((rr * math.sin(a), y + rng.uniform(-0.06, 0.06), rr * math.cos(a), math.degrees(a) + rng.uniform(-16, 16),
                          tilt + rng.uniform(-8, 8), names[len(spots) % len(names)], rng.uniform(0, 2 * math.pi)))
    hs = 0.17
    for name, wob in (("", 0.035), ("shake", 0.12)):
        s = m.state(name)
        s.lumped = True
        s.front = (0, 0, 0)
        # 가운데 살덩이(얼굴 사이 틈을 메움)
        s.box((-0.86, -0.42, -0.86), (0.86, 0.12, 0.86), "flesh", face=True)
        for fr in s.frames(2):
            with fr:
                for (x, y, z, yaw, tilt, fc, ph) in spots:
                    up = math.sin(2 * math.pi * fr.t + ph) * wob
                    out = 1 + math.cos(2 * math.pi * fr.t + ph) * wob * 0.5
                    c = (x * out, y + up, z * out)
                    tex = {"south": k.face_tex(fc), "north": k.skin_tex("back"), "east": k.skin_tex("left"), "west": k.skin_tex("right"),
                           "up": k.skin_tex("top"), "down": k.skin_tex("bottom")}
                    s.box(tuple(v - hs for v in c), tuple(v + hs for v in c), tex, uv=[0, 0, 16, 16], rot=(-tilt, yaw, 0), origin=c, face=True)
    return m


def rod(k):
    """돛대 막대기 하나(부품 넷이 같은 모델): 뿌리 (0, 0, 0) 에서 위로 1.1. 마디 물결은 아주 작게(곧은 돛대가 삐걱이는 만큼만)"""
    m = k.model("b1_rod", poses=4, period=20, mut2=False, open=False, torn=False, rag=False)
    s = m.state("")
    s.rod((0, 0, 0), (0, 1, 0), length=1.1, segs=3, thick=0.07, wave=kit.Wave(amp=(3.5, 3, 2.5), lag=40), toward=(0, 0, 1), poses=4)
    s.front = (0, 0, 0)
    c = m.state("cut")
    c.rod((0, 0, 0), (0, 1, 0), length=0.2, segs=1, thick=0.07, toward=(0, 0, 1))
    c.front = (0, 0, 0)
    return m


def build(k: kit.Kit):
    plates(k)
    body(k)
    heap(k)
    rod(k)
    k.icon(model="b1")


# ============================================================================================== 소리(받은 소리만, 합성 없음)


def sounds(k: kit.Kit):
    # ---- 공용(다섯 보스가 같이 씀, 사건 gave:boss.*). 사람마다 그 사람 자리에서 울린다
    k.sample("wave", [{"id": "kenney_scifi/lowfrequency_explosion_001", "pitch": 0.8},
                      {"id": "kenney_impact/impactsoft_heavy_003", "pitch": 0.55}], "쿵", attenuation=32, event="boss.wave")
    k.sample("weak", ["kenney_impact/impactmetal_light_001", "kenney_impact/impactmetal_light_002", "kenney_impact/impactmetal_light_004"],
             "칭", pitch=1.45, volume=0.9, attenuation=16, event="boss.weak")
    k.sample("immune", [{"id": "kenney_impact/impactplate_light_000", "pitch": 0.55}, {"id": "kenney_impact/impactplate_light_002", "pitch": 0.6},
                        {"id": "kenney_impact/impactsoft_medium_001", "pitch": 1.1}], "퉁", volume=0.9, attenuation=16, event="boss.immune")
    k.sample("next", [{"id": "oga_quake/shake", "pitch": 0.7}, {"id": "kenney_scifi/lowfrequency_explosion_000", "pitch": 0.6}],
             "먼 땅울림", volume=0.8, attenuation=128, event="boss.next")

    # ---- B1(사건 gave:gabe.b1.*, attenuation = Java 반경)
    k.sample("step", [{"id": "oga_creature80b/stomp_01", "pitch": 0.6}, {"id": "oga_stomp/stomp", "pitch": 0.6},
                      {"id": "kenney_impact/impactsoft_heavy_001", "pitch": 0.55}, {"id": "kenney_impact/impactsoft_heavy_004", "pitch": 0.5}],
             "쿵", attenuation=96)
    k.sample("inhale", [{"id": "lrsf_horror/breath_scared_breath_scared_13", "pitch": 0.45},
                        {"id": "lrsf_horror/breath_scared_breath_scared_08", "pitch": 0.45}], "크게 들이쉼", attenuation=64)
    k.sample("slam", [{"id": "kenney_scifi/explosioncrunch_004", "pitch": 0.7}, {"id": "kenney_scifi/explosioncrunch_000", "pitch": 0.6},
                      {"id": "kenney_impact/impactpunch_heavy_000", "pitch": 0.45}], "철퍼덕", attenuation=128)
    k.sample("roll", [{"id": f"kenney_impact/impactmining_00{i}", "pitch": 0.6} for i in (0, 2, 4)], "드르륵", attenuation=96)
    k.sample("shake", [{"id": "oga_breaking75/bfh1_rock_falling_01", "pitch": 0.75}, {"id": "oga_breaking75/bfh1_rock_falling_09", "pitch": 0.75},
                       {"id": "oga_breaking75/bfh1_falling_04", "pitch": 0.7}], "후드득", attenuation=64)
    # 무더기 킥킥(무더기는 떼라 웃음 허용): 여럿이 킥킥대는 녹음을 세 토막으로
    k.sample("giggle", [{"id": "oga_giggle/group_giggling", "start": a, "end": b, "pitch": 1.2} for a, b in ((0.0, 1.7), (1.8, 3.5), (3.6, 5.4))],
             "킥킥", volume=0.9, attenuation=64)
    k.sample("ring", [{"id": "kenney_scifi/lowfrequency_explosion_000", "pitch": 0.75}, {"id": "oga_quake/shake", "end": 2.6, "pitch": 0.85}],
             "땅이 운다", attenuation=96)
    k.sample("roar", [{"id": "oga_creature80/roar_02", "pitch": 0.5}, {"id": "oga_creature80b/roar_05", "pitch": 0.5},
                      {"id": "oga_creature80b/roar_06", "pitch": 0.5}, {"id": "oga_creature80b/roar_04", "pitch": 0.45}], "포효", attenuation=160)
    k.sample("crack", [{"id": "lrsf_horror/bonecrack_03", "pitch": 0.5}, {"id": "oga_breaking75/bfh1_wood_breaking_03", "pitch": 0.45},
                       {"id": "kenney_rpg/creak2", "pitch": 0.4}], "쩌억", attenuation=96)
    k.sample("fall", [{"id": "kenney_scifi/explosioncrunch_003", "pitch": 0.5}, {"id": "kenney_scifi/explosioncrunch_001", "pitch": 0.5},
                      {"id": "oga_breaking75/bfh1_rock_breaking_01", "pitch": 0.45}], "무너짐", attenuation=160)
    # 엔진이 모든 종에 울리는 맞음, 죽음 소리(기본은 작은 슬라임 소리라 산더미에 안 맞음)
    k.sample("hurt", [{"id": "kenney_impact/impactpunch_medium_000", "pitch": 0.7}, {"id": "kenney_impact/impactpunch_medium_002", "pitch": 0.7}],
             "산더미 움찔", volume=0.8, attenuation=16)
    k.sample("die", [{"id": "kenney_impact/impactsoft_heavy_002", "pitch": 0.5}], "산더미 철퍽", attenuation=24)


def phrases():
    return {"bs_sub_b1": "땅이 걸어온다", "bs_b1_p2": "얼굴이 갈라진다", "bs_b1_p3": "속이 드러났다",
            "cause_b1_slam": "산더미에 깔렸다", "cause_b1_roll": "굴러온 얼굴에 치였다", "cause_b1_ring": "땅울림을 못 넘었다",
            "cause_b1_hop": "뛰어 찍혔다", "cause_b1_step": "밟혔다", "cause_b1_rain": "떨어진 얼굴에 맞았다",
            "hint_b1_pit": "꺼진 땅에 숨어라"}
