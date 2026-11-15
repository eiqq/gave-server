"""무기 모델 덧붙임 표(2026-11-15 gunmodel): 모드 모델을 그대로 옮기면 어설픈 곳을 요소 단위로 고친다.
떠 있는 부품, 한 모델에 든 두 자루, 밋밋한 상자 덩어리, 한쪽 면만 있는 판, 총검 끝에서 나는 총구 불꽃 같은 것.

- 새 텍스처는 그리지 않는다: 더하는 요소는 같은 모델의 다른 요소(like) 면에서 같은 텍셀 밀도로 필요한 만큼만 잘라 쓴다.
  그래서 더하기만 한 총은 다시 모은 텍스처 그림이 전과 바이트까지 같다(그린 텍셀이 없다는 검사).
- build_arms.build_arm 과 build_props.build_gun 이 부품을 읽은 바로 뒤(recenter 앞, 모드 좌표 = 표의 G 와 같은 공간) apply 를 부른다.
  그 뒤(가운데 맞춤, 크기 맞춤, 표시 변환, _nomag, _dual, muzzle_<키>, 종류 키 사본, 옛 다섯의 kick/)는 덧붙인 요소 목록을 그대로 따른다.
- 표 숫자만 바꾸는 것(G, len, at, 부품 목록)은 arms_table.py 에 바로 적는다. 여기는 요소 번호로 가리키는 것만.
  표의 len 은 덧붙인 뒤의 길이로 배율을 정하므로 앞뒤로 길어지는 덧붙임(MGL 개머리판, 펄서 침)은 len 도 같이 올린다.

PATCH[키] = dict(
  expect=(요소 수, lo, hi): els + mag 의 요소 수와 보이는 범위(모드 좌표, 소수 둘째 자리). 요소 번호는 부품 목록과 모드 파일이 같을 때만 맞으므로
         이것이 다르면 멈춘다(부품 목록을 바꾸면 번호와 이 값을 다시 셀 것),
  drop=[번호, ...]: 뺄 요소. 번호는 els + mag 를 이은 차례(덧붙이기 전 팩 모델의 요소 번호와 같음),
  move=[([번호, ...], (dx, dy, dz)), ...]: 요소 옮기기(모드 좌표 단위),
  add=[(from, to, like, 면[, 회전]), ...]: 새 상자 요소(모드 좌표). like = 텍스처를 잘라 올 요소 번호, 면 = "neswud" 가운데 붙일 면,
       회전 = 모델 요소 회전 그대로({"origin": ..., "axis": ..., "angle": ...}).
       몸통 쪽(탄창 앞)에 붙으므로 _nomag 에도 남는다,
  muzzle_skip=[번호, ...]: 총구 불꽃 자리를 셀 때 빼는 요소(총열보다 앞에 나온 총검),
)
판 뒷면: 두께 0 인 판 요소에 마주 보는 두 면 가운데 하나만 있으면 다른 쪽을 같은 텍셀이 같은 자리에 오게 채운다(바닐라 아이템은 뒷면을
그리지 않아 반대쪽에서 판이 사라진다: SG2 방아쇠울, 레버 고리, 총검, JEG 활시위). TaC 모델의 한쪽 면 판(11 자루 51 면)은 홈 안쪽 벽이라
채워도 어느 쪽에서 봐도 화소가 안 바뀌어(2026-11-15 렌더러 대조) 채우지 않는다(부르는 쪽이 backface=False).
"""
import numpy as np
from PIL import Image

import vmodel as V

PATCH = {
    # 위즈뱅어: 총 왼쪽 0.30 블록에 따로 선 망치 덩어리(main 끝 넷, 1인칭 화면 아래 가운데의 검은 기둥)
    "sg_whizzbanger": dict(expect=(22, (-1.0, -0.69, 10.39), (9.24, 8.3, 20.14)), drop=[18, 19, 20, 21]),
    # 쥐왕 부부: 한 모델에 든 두 자루 가운데 x -1.52..0.22 쪽 한 자루(main 뒤 19, stan_barrel 뒤 둘, receiver_2 전부, 탄창 뒤 둘)
    "sg_rat_king_and_queen": dict(expect=(62, (-1.52, 0.14, 5.1), (8.88, 7.0, 16.64)),
                                  drop=list(range(19, 38)) + [40, 41] + list(range(50, 58)) + [60, 61]),
    # 소음 마이크로 우지(가늠자 부품은 표에서 뺌): 총열 위 6.3, 뒤 8 단위에 떠 있던 소음기를 총열 끝에 1 단위 물리게
    "tac_micro_uzi_sd": dict(expect=(250, (5.26, -9.41, -9.0), (10.34, 19.16, 19.31)), move=[(range(232, 240), (0.0, -6.3, -8.03))]),
    # MGL(유탄발사기 기본): 개머리판(위 대, 아래 대, 어깨 받침, 받침 윗단), 가늠자 두 기둥, 방아쇠울 앞 기둥.
    # like 16 = 뒤 몸통 밝은 회색, 14 = 앞 손잡이 어두운 회색, 21 = 방아쇠 둘레 중간 회색
    "tac_grenade_launcher": dict(expect=(43, (5.5, -1.17, 4.2), (10.5, 5.7, 17.14)), add=[
        ((7.6, 4.0, 16.2), (8.4, 4.5, 20.2), 16, "ewud"),
        ((7.7, 2.25, 16.2), (8.3, 2.65, 20.2), 21, "ewud"),
        ((7.3, 1.66, 20.2), (8.7, 4.86, 20.9), 14, "neswud"),
        ((7.3, 4.86, 20.0), (8.7, 5.16, 20.9), 14, "neswu"),
        ((7.55, 4.4, 15.27), (7.8, 5.0, 15.67), 14, "neswu"),
        ((8.2, 4.4, 15.27), (8.45, 5.0, 15.67), 14, "neswu"),
        ((7.6, 0.85, 13.0), (8.4, 2.25, 13.2), 21, "newd"),
    ]),
    # AA-12(자동 산탄총 기본): 윗면에서 1.05 단위 떠 있던 가늠자 부품(aa_12_sight 20개)을 내려 붙이고, 탄창 옆에 따로 뜬 조각 하나를 뺀다
    "tac_aa_12": dict(expect=(293, (6.88, -0.0, -14.38), (9.0, 15.39, 30.75)), move=[(range(259, 279), (0.0, -1.05, 0.0))], drop=[169]),
    # 총검이 총열보다 앞에 나온 셋: 불꽃 자리를 셀 때 총검 판을 빼서 총열 끝에서 나게
    "sg_earths_corpse": dict(expect=(65, (2.27, -0.76, -15.8), (10.5, 7.92, 26.15)), muzzle_skip=[18]),
    "sg_locust": dict(expect=(55, (6.25, -0.5, -11.75), (9.75, 6.75, 21.77)), muzzle_skip=[7]),
    # 슈퍼 샷건: 위 둘처럼 총열 아래 총검 판(0)을 불꽃 자리에서 빼고, 가늠쇠(like 6 = 총열)와 약실 뒤 공이치기 둘(like 9 = 약실 위 덮개)
    "sg_super_shotgun": dict(expect=(17, (5.66, 0.01, -7.5), (10.34, 5.35, 16.33)), muzzle_skip=[0], add=[
        ((7.75, 4.71, -2.25), (8.25, 5.21, -1.75), 6, "neswu"),
        ((7.15, 4.71, 11.55), (7.65, 5.46, 12.05), 9, "neswu"),
        ((8.35, 4.71, 11.55), (8.85, 5.46, 12.05), 9, "neswu"),
    ]),
    # 붐스틱(주황 상자와 회색 상자): 가늠쇠, 가늠자 두 기둥, 공이치기 둘, 총구 띠 넷, 약실 띠 셋(like 2 = 총열 위 어두운 쇠 띠)
    "sg_boomstick": dict(expect=(21, (6.0, -1.54, 1.91), (10.0, 5.68, 23.4)), add=[
        ((7.75, 5.17, 2.41), (8.25, 5.67, 2.91), 2, "neswu"),
        ((7.38, 5.5, 14.91), (7.75, 6.0, 15.66), 2, "neswu"),
        ((8.25, 5.5, 14.91), (8.62, 6.0, 15.66), 2, "neswu"),
        ((6.5, 5.0, 15.41), (7.25, 5.75, 16.16), 2, "neswu"),
        ((8.75, 5.0, 15.41), (9.5, 5.75, 16.16), 2, "neswu"),
        ((5.75, 5.17, 2.41), (10.25, 5.42, 3.41), 2, "neswu"),
        ((5.75, 2.92, 2.41), (10.25, 3.17, 3.41), 2, "neswd"),
        ((5.75, 3.17, 2.41), (6.0, 5.17, 3.41), 2, "nsw"),
        ((10.0, 3.17, 2.41), (10.25, 5.17, 3.41), 2, "nse"),
        ((5.75, 5.17, 9.91), (10.25, 5.42, 10.91), 2, "neswu"),
        ((5.75, 3.17, 9.91), (6.0, 5.17, 10.91), 2, "nsw"),
        ((10.0, 3.17, 9.91), (10.25, 5.17, 10.91), 2, "nse"),
    ]),
    # 플라운더개트(검은 쌍열 통): 총열 사이 가늠쇠(like 19 = 가운데 심), 총구 금테(14, 15)와 같은 꼴의 띠를 총열 가운데에 하나
    "sg_floundergat": dict(expect=(29, (5.42, 0.78, 1.05), (10.58, 5.32, 19.43)), add=[
        ((7.75, 3.75, 1.75), (8.25, 5.0, 2.25), 19, "neswu"),
        ((8.21, 2.24, 6.3), (9.71, 4.74, 6.8), 14, "neswud", {"origin": [8.9, 3.80082, 6.55], "axis": "z", "angle": 45}),
        ((6.29, 2.24, 6.3), (7.79, 4.74, 6.8), 15, "neswud", {"origin": [7.1, 3.80082, 6.55], "axis": "z", "angle": -45}),
    ]),
    # 빅 보어(굵은 관 하나): 총열에 총구 토막(16)과 같은 단면의 띠 둘, 가늠쇠 받침, 가늠자 두 기둥
    "sg_big_bore": dict(expect=(26, (5.88, -0.23, -16.0), (10.12, 7.3, 19.23)), add=[
        ((6.76, 3.02, 0.0), (9.76, 6.02, 0.75), 16, "neswud", {"origin": [7.82322, 4.33492, 0.32378], "axis": "z", "angle": 45}),
        ((6.76, 3.02, 7.0), (9.76, 6.02, 7.75), 16, "neswud", {"origin": [7.82322, 4.33492, 0.32378], "axis": "z", "angle": 45}),
        ((7.75, 6.6, -15.25), (8.25, 7.35, -14.75), 23, "neswu"),
        ((7.5, 6.0, 9.5), (7.75, 7.0, 10.0), 23, "neswu"),
        ((8.25, 6.0, 9.5), (8.5, 7.0, 10.0), 23, "neswu"),
    ]),
    # 로켓 소총(통과 손잡이): 통 가운데 띠(앞 고리 19 와 같은 꼴), 가늠쇠, 가늠자 두 기둥(지금 것은 0.15 단위로 안 보임)
    "sg_rocket_rifle": dict(expect=(26, (6.59, -0.09, 0.43), (10.47, 6.2, 17.45)), add=[
        ((7.22, 3.71, 5.18), (8.72, 5.21, 5.68), 19, "neswud", {"origin": [7.89393, 4.38701, 2.35233], "axis": "z", "angle": -45}),
        ((7.75, 5.6, 1.0), (8.25, 6.35, 1.5), 8, "neswu"),
        ((7.5, 5.65, 13.75), (7.75, 6.4, 14.25), 8, "neswu"),
        ((8.25, 5.65, 13.75), (8.5, 6.4, 14.25), 8, "neswu"),
    ]),
    # 펄서(상자 둘과 손잡이): 앞판에서 앞으로 나온 방전 침 둘(like 2 = 앞 코일). 몸통 띠는 노랑 검정 줄무늬(이미 코일)를 가려서 안 더함.
    # 길이가 1.5 단위 늘어 표의 len 을 0.42 → 0.5(몸통 크기 그대로)
    "sg_pulsar": dict(expect=(25, (6.75, -0.07, 8.1), (9.25, 6.22, 16.16)), add=[
        ((7.0, 4.5, 6.65), (7.5, 5.0, 8.15), 2, "neswud"),
        ((8.5, 4.5, 6.65), (9.0, 5.0, 8.15), 2, "neswud"),
    ]),
    # 블래스퍼미(화로 덩어리 화염방사기): 몸통 뒤 아래 권총 손잡이(뒤로 22.5 도 기울임, like 9 = 앞 아래 어두운 쇠), 그 앞 방아쇠,
    # 앞 분사구 앞에 짧은 관(like 11 = 분사구). 손잡이 G 와 크기는 표에서.
    # 앞은 모드대로 -Z(얼굴과 분사구 쪽, 모드 총 자료의 불꽃 자리도 거기)이고 뒤 끝(요소 0 의 뒷면, 쏘는 사람 쪽)에 구멍 그림과
    # 나팔 날개가 있다. 1인칭에서 그 구멍이 나를 겨눈 총구로 읽혀(2026-11-15 fix3) 뒷면 바로 뒤에 몸통 쇠판(like 6)을 한 장 대 막는다(날개는 그대로).
    # 표의 at 은 앞 분사구가 1인칭에서 불길 나는 자리(Arsenal.muzzle, 모든 화염방사기 같음)에 오게 잡았다
    "sg_blasphemy": dict(expect=(12, (4.25, -0.62, 3.04), (11.75, 6.26, 14.57)), add=[
        ((7.25, -3.62, 9.73), (8.75, -0.12, 11.23), 9, "neswd", {"origin": [8.0, -0.62, 10.48], "axis": "x", "angle": -22.5}),
        ((7.25, 1.56, 0.54), (8.75, 3.06, 3.04), 11, "neswud"),
        ((7.75, -1.62, 8.73), (8.25, -0.62, 9.23), 11, "nesw"),
        ((6.125, 1.125, 13.98), (9.875, 4.875, 14.08), 6, "sewud"),
    ]),
    # 봄 랜스(막대기 창): 자루 아래 권총 손잡이와 방아쇠(like 9 = 머리 아래 어두운 쇠). 창날 판 뒷면은 판 뒷면 채우기가 함
    "sg_bomb_lance": dict(expect=(15, (5.47, -1.59, -8.25), (10.53, 5.85, 32.0)), add=[
        ((7.5, -1.75, 15.5), (8.5, 1.25, 16.5), 9, "neswd", {"origin": [8.0, 0.75, 16.0], "axis": "x", "angle": -22.5}),
        ((7.75, -0.25, 14.75), (8.25, 0.75, 15.0), 9, "nesw"),
    ]),
}
BACKFACE = True  # 끄면 판 뒷면 채우기를 하지 않음(고치기 전 생성기와의 회귀 대조용)

FACE_UV = {"up": (0, 2), "down": (0, 2), "north": (0, 1), "south": (0, 1), "east": (2, 1), "west": (2, 1)}  # 면 → (u 쪽 축, v 쪽 축)
NAMES = {n[0]: n for n in FACE_UV}
THIN_PAIR = (("west", "east"), ("down", "up"), ("north", "south"))
_SIZE = {}


def tex_size(tex_file, tid):
    if tid not in _SIZE:
        _SIZE[tid] = Image.open(tex_file(tid)).size
    return _SIZE[tid]


def box(frm, to, src, tex_file, faces="neswud"):
    """새 요소. 면마다 src 요소의 같은 이름 면(없으면 가장 넓은 면) uv 에서 같은 텍셀 밀도로 필요한 만큼만 잘라 쓴다"""
    s_size = np.abs(np.array(src["to"], float) - np.array(src["from"], float))
    size = np.abs(np.array(to, float) - np.array(frm, float))
    out = {}
    for ch in faces:
        fn = NAMES[ch]
        sn = fn if fn in src["faces"] else max(src["faces"], key=lambda k: abs((lambda u: (u[2] - u[0]) * (u[3] - u[1]))(src["faces"][k]["uv"])))
        sf = src["faces"][sn]
        W, H = tex_size(tex_file, sf["texture"][1:])
        u = sf["uv"]
        px = [u[0] / 16 * W, u[1] / 16 * H, u[2] / 16 * W, u[3] / 16 * H]
        au, av = FACE_UV[sn]
        turned = int(sf.get("rotation", 0)) % 180 == 90
        du, dv = abs(px[2] - px[0]), abs(px[3] - px[1])
        dens_u = (dv if turned else du) / max(s_size[au], 1e-6)  # 면의 u 쪽 축 단위당 텍셀
        dens_v = (du if turned else dv) / max(s_size[av], 1e-6)
        bu, bv = FACE_UV[fn]
        need_u, need_v = max(1.0, round(size[bu] * dens_u)), max(1.0, round(size[bv] * dens_v))
        if turned:
            need_u, need_v = need_v, need_u
        wu, wv = min(need_u, du), min(need_v, dv)
        su, sv = (1 if px[2] >= px[0] else -1), (1 if px[3] >= px[1] else -1)
        x0, y0 = px[0], px[1]
        f = {"uv": [round(x0 * 16 / W, 5), round(y0 * 16 / H, 5), round((x0 + su * wu) * 16 / W, 5), round((y0 + sv * wv) * 16 / H, 5)],
             "texture": sf["texture"]}
        if sf.get("rotation"):
            f["rotation"] = sf["rotation"]
        out[fn] = f
    return {"from": [float(v) for v in frm], "to": [float(v) for v in to], "faces": out}


def back_face(e, have, miss):
    """have 면과 같은 텍셀이 같은 자리에 오는 miss 면(uv 순서와 회전을 맞춰 고름)"""
    plain = {"from": e["from"], "to": e["to"]}
    _, P, U, _ = V.face_quads({**plain, "faces": {have: e["faces"][have]}})[0]
    want = {tuple(np.round(P[i], 4)): tuple(U[i]) for i in range(4)}
    u = e["faces"][have]["uv"]
    for uv in ([u[2], u[1], u[0], u[3]], [u[0], u[3], u[2], u[1]], u, [u[2], u[3], u[0], u[1]]):
        for rot in (0, 90, 180, 270):
            f = {k: v for k, v in e["faces"][have].items() if k != "rotation"}
            f["uv"] = list(uv)
            if rot:
                f["rotation"] = rot
            _, P2, U2, _ = V.face_quads({**plain, "faces": {miss: f}})[0]
            if all(want.get(tuple(np.round(P2[i], 4))) == tuple(U2[i]) for i in range(4)):
                return f
    raise RuntimeError(f"판 뒷면 uv 를 못 맞춤 {e}")


def fill_back(els):
    """한쪽 면만 있는 판의 뒷면을 채운 목록과 채운 수"""
    out, n = [], 0
    for e in els:
        size = np.abs(np.array(e["to"], float) - np.array(e["from"], float))
        thin = np.nonzero(size < 1e-6)[0]
        if len(thin) == 1:
            a, b = THIN_PAIR[thin[0]]
            for have, miss in ((a, b), (b, a)):
                if have in e["faces"] and miss not in e["faces"]:
                    e = {**e, "faces": {**e["faces"], miss: back_face(e, have, miss)}}
                    n += 1
        out.append(e)
    return out, n


def check(key, full, expect):
    n, lo, hi = expect
    got_lo, got_hi = V.vbounds(full)
    if len(full) != n or np.abs(got_lo - lo).max() > 0.011 or np.abs(got_hi - hi).max() > 0.011:
        raise SystemExit(f"arms_patch {key}: 모드 모델이 표를 만들 때와 다르다(요소 {len(full)}, 범위 {np.round(got_lo, 2)}..{np.round(got_hi, 2)}, "
                         f"기대 {n}, {lo}..{hi}). 번호를 다시 셀 것")


def apply(key, els, mag, tex_file, backface=True):
    """덧붙임을 건 (els, mag). 불꽃 자리에서 뺄 요소에는 '_nomuzzle' 표시(V.clean 이 지움)"""
    p = PATCH.get(key)
    if p:
        full = [dict(e) for e in els + mag]
        check(key, full, p["expect"])
        for idx, d in p.get("move", ()):
            for i in idx:
                full[i] = V.transform([full[i]], 1.0, d)[0]
        for i in p.get("muzzle_skip", ()):
            full[i]["_nomuzzle"] = True
        new = []
        for frm, to, like, faces, *rot in p.get("add", ()):
            new.append(box(frm, to, full[like], tex_file, faces))
            if rot:
                new[-1]["rotation"] = dict(rot[0])
        drop = set(p.get("drop", ()))
        n = len(els)
        els = [e for i, e in enumerate(full[:n]) if i not in drop] + new
        mag = [e for i, e in enumerate(full[n:], n) if i not in drop]
    if BACKFACE and backface:
        els, _ = fill_back(els)
        mag, _ = fill_back(mag)
    return els, mag
