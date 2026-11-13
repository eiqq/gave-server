"""build_props.py 뒤쪽: 무기 모델 표(tools/props/arms_table.py)의 모델(모드에서 꺼낸 것)과 아이템 정의, 날아가는 로켓, 무기 상자 그림,
상점 무기 그림 정의. build_props.main 이 run(globals()) 으로 부른다(2026-11-12 wpn12 새 무기 12종, 같은 날 wpn_max 가 표로 바꿔 214 모델).

- 총 모델은 build_props.build_gun 과 같은 방식: 부품을 합치고 보이는 범위 가운데를 (8,8,8) 로, 손잡이 G 로 표시 변환(3인칭 쥠, 1인칭 자리,
  인벤토리 옆모습, 바닥, 액자). 배율은 '손에 든 길이(블록)'에서 계산한다. 탄창 부품이 있으면 <키>_nomag 변종(재장전 중 탄창 빠짐).
  모델이 26.3 좌표 범위(-16..32, 48 픽셀)보다 길면 모델 안에서 줄이고 표시 배율을 그만큼 키운다(보이는 크기는 같음).
- 모든 면 tintindex 0: 1인칭은 몸짓 데이터 틴트(custom_model_data 색 3, 셰이더 zr_kick), 그 밖은 흰색 고정 틴트(minecraft:constant)로
  같은 모델 파일 하나를 쓴다(옛 총 다섯은 tools/build_pack.py 가 1인칭 사본 kick/<키> 를 따로 만든다). 총구 불꽃 색 4..6(1인칭), 0..2(그 밖).
- 쌍권총(종류 DUAL): 1인칭은 <키>_dual(같은 권총 둘: 오른쪽은 늘 자리, 왼쪽 사본은 카메라 공간 왼쪽으로 옮김. 모델 안에서 반으로 줄이고
  표시 배율을 두 배로), 3인칭, 인벤토리는 권총 하나.
- 종류 키 아이템(새 종류 autoshotgun 등): 그 종류 기본 모델과 같은 그림(아이템 정의 + HUD 실루엣용 모델 사본 models/item/<종류 키>).
- 텍스처 이름 끝 _n, _s, _e 는 r 을 붙여 바꾼다(Iris 가 PBR 맵으로 보고 빼서 보라 검정이 됨).
- gave:weapon_icon: 상점 총 그림(뒤판 = 등급 strings[0], 총 = 모델 키 또는 종류 키 strings[1]). gave:weapon_box: 무기 상자 그림.
- gave:rocket_proj: 날아가는 로켓(TaC rpg7_rocket, 실제 크기, 가운데 (8,8,8) = 엔티티 자리, 앞 = 모델 -Z).
- 표의 모델마다 정보(종류, 이름, 성능 배율, 손잡이, 총구 자리, 길이)를 ARMS_INFO 로 모아 build_props.write_props_json 이 props.json "arms" 로 쓴다.
"""
import json
import re

import numpy as np

import arms_table
import vmodel as V

G = {}  # build_props 의 전역(run 에서 채움)

JEG = ("Just Enough Guns (MigaMi)", "https://modrinth.com/mod/just-enough-guns", "0.14.5 (Forge 1.20.1)",
       "GPL-2.0-or-later(Modrinth), jar mods.toml GNU GPLv3")
SG2 = ("Scorched Guns 2 (ribs)", "https://modrinth.com/mod/scorched-guns-2", "0.5.5 (Forge 1.20.1)",
       "GPL-3.0-or-later(Modrinth), jar mods.toml GNU GPLv3")
TACGEAR = ("TAC GEAR (GF2022)", "https://modrinth.com/mod/tac-gear", "0.7.1 (Forge 1.20.1)", "MIT")
OLD = ("pistol", "smg", "shotgun", "rifle", "sniper")
TIERS = ("common", "rare", "epic", "legendary")
FLASH_THIRD, BODY_TINT, FLASH_FIRST = 0, 3, 4  # custom_model_data 색 자리(Guns.visual, build_pack.py 와 같게)
WHITE = 0xFFFFFF
CONTEXTS = ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "head", "gui", "ground", "fixed",
            "on_shelf")
SHRINK = 0.5  # 불꽃 모델은 총 좌표를 가운데로 SHRINK 배 줄이고 표시 배율을 1/SHRINK 로(build_pack.flash_model 과 같음)
SPAN = 47.5  # 모델 한 변 최대(픽셀, -16..32 에 여유)
# 손 자리 종류 → 1인칭 손잡이 자리(카메라 공간 블록). 표의 at 이 있으면 그것
AT = {"one": (0.30, -0.30, -0.60), "two": (0.33, -0.34, -0.58), "heavy": (0.35, -0.40, -0.60)}
# 종류 → 총구 불꽃(앞면 지름, 옆 불길 길이 블록). 없는 종류(광선, 번개, 냉기, 레일, 활, 소음기)는 불꽃 모델 없음(Guns.flashes 와 같게)
FLASH = {"SMG": (0.34, 0.30), "RIFLE": (0.35, 0.32), "BURST": (0.35, 0.32), "DMR": (0.40, 0.40), "SNIPER": (0.40, 0.40),
         "BOLTACTION": (0.40, 0.40), "ANTIMATERIEL": (0.55, 0.55), "MACHINEPISTOL": (0.30, 0.26), "DUAL": (0.30, 0.26),
         "REVOLVER": (0.34, 0.30), "SHOTGUN": (0.42, 0.36), "AUTOSHOT": (0.42, 0.36), "DOUBLE": (0.46, 0.40), "LMG": (0.36, 0.34),
         "MINIGUN": (0.36, 0.36), "LAUNCHER": (0.42, 0.36), "ROCKET": (0.50, 0.50), "FLAME": (0.34, 0.40), "MUSKET": (0.55, 0.45)}
NO_FLASH = {"LASER", "TESLA", "FREEZE", "RAILGUN", "BOW", "SILENCED"}
DUAL_GAP = 0.60  # 쌍권총 왼쪽 사본: 카메라 공간에서 왼쪽으로 옮기는 거리(블록)
SRC_NAME = {"tac": "TaC", "jeg": "Just Enough Guns", "sg2": "Scorched Guns 2"}

ARMS_INFO = {}  # 모델 키 → props.json arms 줄


def credit(src):
    return {"tac": G["TAC"], "jeg": JEG, "sg2": SG2}[src]


def safe_name(name, taken):
    """텍스처 파일 이름: Iris PBR 접미(_n, _s, _e)를 피하고 한 모델 안에서 겹치지 않게"""
    n = re.sub(r"[^a-z0-9_.-]", "_", name.lower())
    if re.search(r"_[nse]$", n):
        n += "r"
    base, i = n, 2
    while n in taken:
        n = f"{base}_{i}"
        i += 1
    taken.add(n)
    return n


def tex_names(els):
    taken, names = set(), {}
    for e in els:
        for f in e["faces"].values():
            tid = f["texture"][1:]
            if tid not in names:
                names[tid] = safe_name(tid.split("/")[-1], taken)
    return names


def tint_all(key):
    """모델 파일의 모든 면을 tintindex 0 으로(1인칭 몸짓 틴트, 그 밖은 흰색 고정 틴트)"""
    A = G["A"]
    p = A / f"models/item/{key}.json"
    m = json.loads(p.read_text(encoding="utf-8"))
    for e in m.get("elements", []):
        for f in e.get("faces", {}).values():
            f["tintindex"] = 0
    G["put_json"](f"assets/{G['NS']}/models/item/{key}.json", m)
    return m


def fit_span(els, G_):
    """모델이 48 픽셀 범위를 넘으면 (8,8,8) 둘레로 줄인다. (요소, 손잡이, 줄인 배)"""
    lo, hi = V.vbounds(els)
    span = float((hi - lo).max())
    k = min(1.0, SPAN / max(span, 1e-6))
    if k < 1.0:
        els = V.transform(els, k, 8 - 8 * k)
        G_ = 8 + k * (np.array(G_, float) - 8)
    return els, np.array(G_, float), k


def dual_model(key, G_, s_fp, at):
    """쌍권총 1인칭 모델 <키>_dual: 권총 모델(이미 쓴 <키>, 텍스처와 uv 그대로)을 반으로 줄여 둘. 왼쪽 사본은 카메라 공간 왼쪽 DUAL_GAP 블록"""
    k = 0.5
    base = json.loads((G["A"] / f"models/item/{key}.json").read_text(encoding="utf-8"))
    one = V.transform(json.loads(json.dumps(base["elements"])), k, 8 - 8 * k)
    Gk = 8 + k * (np.array(G_, float) - 8)
    s2 = round(s_fp / k, 4)
    R = V.ry(G["D"](4.0))  # fp_grip 의 yaw 4 도
    d = R.T @ (np.array([-DUAL_GAP, 0.0, 0.0]) * 16) / s2
    left = V.transform(json.loads(json.dumps(one)), 1.0, d)
    both = V.clean(one + left)
    lo, hi = V.vbounds(both)
    assert lo.min() >= -16 - 1e-6 and hi.max() <= 32 + 1e-6, (key, lo, hi)
    fp = G["fp_grip"](Gk, s2, at)
    disp = dict(base["display"])
    disp["firstperson_righthand"] = fp
    disp["firstperson_lefthand"] = fp
    G["put_json"](f"assets/{G['NS']}/models/item/{key}_dual.json", {"textures": base["textures"], "elements": both, "display": disp})


def build_arm(row):
    key, src = row["key"], row["src"]
    els = G["parts"](row["parts"])
    mag = G["parts"]([row["mag"]]) if row.get("mag") else []
    full = els + mag
    lo, hi = V.vbounds(full)
    if row.get("height"):
        s_tp = row["height"] * 16 / max(hi[1] - lo[1], 1e-6)
    else:
        s_tp = row["len"] * 16 / max(hi[2] - lo[2], 1e-6)
    s_tp = round(min(s_tp, 3.5), 4)
    # 1인칭: TaC 총은 3인칭의 0.95 배. JEG, Scorched Guns 2 총은 두툼해서(부품이 굵음) 0.82 배(2026-11-12 wpn12 게임 안 사진)
    s_fp = round(s_tp * (0.95 if src == "tac" else 0.82), 4)
    full, off = G["recenter"](full)
    grip = np.array(row["G"], float) + off
    full, grip, k = fit_span(full, grip)
    lo2, hi2 = V.vbounds(full)
    assert lo2.min() >= -16 - 1e-6 and hi2.max() <= 32 + 1e-6, (key, lo2, hi2)
    at = row.get("at") or AT[row.get("grip", "two")]
    disp = G["gun_display"](full, grip, round(s_tp / k, 4), round(s_fp / k, 4), at)
    G["write_model"](key, full, disp, names=tex_names(full))
    detail = (f"{SRC_NAME[src]} {', '.join(p.split(':')[1].replace('special/', '') for p in row['parts'])}"
              + (f" + 탄창 {row['mag'].split(':')[1].replace('special/', '')}" if row.get("mag") else "") + f", 요소 {len(full)}")
    G["src"](key, *credit(src), detail, real=row["name"], grip=G["r"](grip, 3))
    m = tint_all(key)
    if mag:
        n = len(els)
        G["write_variant"](f"{key}_nomag", key, lambda i: i < n)
        G["src"](f"{key}_nomag", *credit(src), f"{key} 에서 탄창 부품만 뺀 것(재장전 연출용, 표시 변환과 좌표 같음, 같은 텍스처), 요소 {n}",
                 variant_of=key)
    dual = row["kind"] == "DUAL"
    if dual:
        dual_model(key, grip, round(s_fp / k, 4), at)
    flash = row.get("flash", FLASH.get(row["kind"])) if row["kind"] not in NO_FLASH else None
    gun_item(key, flash, dual)
    mz = muzzle_point(m)
    ARMS_INFO[key] = dict(len=row.get("len"), height=row.get("height"), grip=row.get("grip", "two"), G=G["r"](grip, 2),
                          muzzle=None if mz is None else [round(float(x), 2) for x in mz], scale_tp=s_tp, shrink=round(k, 3), elements=len(full),
                          flash=flash, dual=dual)
    return len(full)


# ------------------------------------------------------------------ 총 아이템 정의

def corners(e):
    f, t = e["from"], e["to"]
    pts = np.array([[x, y, z] for x in (f[0], t[0]) for y in (f[1], t[1]) for z in (f[2], t[2])], float)
    R, o = V.element_matrix(e)
    return (pts - o) @ R.T + o


def muzzle_point(model):
    boxes = [corners(e) for e in model.get("elements", [])]
    if not boxes:
        return None
    zmin = min(b[:, 2].min() for b in boxes)
    front = np.concatenate([b for b in boxes if b[:, 2].min() < zmin + 0.06])
    return (front[:, 0].min() + front[:, 0].max()) / 2, (front[:, 1].min() + front[:, 1].max()) / 2, zmin


def fface(uv, tint=0):
    return {"uv": uv, "texture": "#fx", "tintindex": tint}


def flash_model(model, size_len):
    """총구 불꽃: 총구 앞 정면 판(색 0) + 총열 따라 뻗은 가로 판(색 1), 세로 판(색 2). 표시 변환은 총 모델 것을 옮겨 씀"""
    m = muzzle_point(model)
    if m is None:
        return None
    mx, my, mz = m
    disp = model.get("display", {})
    fp = disp.get("firstperson_righthand", {}).get("scale", [0.4])[0] or 0.4
    size, length = (v * 16 / fp for v in size_len)
    half, wide = size / 2, length * 0.32
    z0 = mz - 0.25
    els = [
        {"from": [mx - half, my - half, z0], "to": [mx + half, my + half, z0], "shade": False,
         "faces": {"north": fface([0, 0, 16, 16]), "south": fface([0, 0, 16, 16])}},
        {"from": [mx - wide, my, mz - length], "to": [mx + wide, my, mz + 0.4], "shade": False,
         "faces": {"up": fface([0, 0, 16, 16], 1), "down": fface([0, 16, 16, 0], 1)}},
        {"from": [mx, my - wide, mz - length], "to": [mx, my + wide, mz + 0.4], "shade": False,
         "faces": {"east": fface([16, 0, 0, 16], 2), "west": fface([0, 0, 16, 16], 2)}},
    ]
    for e in els:
        for k in ("from", "to"):
            e[k] = [round(8 + (v - 8) * SHRINK, 4) for v in e[k]]
            e[k] = [min(32.0, max(-16.0, v)) for v in e[k]]
    display = {}
    for ctx in CONTEXTS:
        d = dict(disp.get(ctx, {}))
        s = d.get("scale", [1, 1, 1])
        d["scale"] = [round(min(4.0, v / SHRINK), 5) for v in s]
        display[ctx] = d
    return {"textures": {"fx": "gave:item/fx_marker", "particle": "gave:item/fx_marker"}, "elements": els, "display": display}


def mdl(name, tints):
    return {"type": "minecraft:model", "model": f"{G['NS']}:item/{name}", "tints": tints}


def cond(on_true, on_false):
    return {"type": "minecraft:condition", "property": "minecraft:custom_model_data", "index": 0, "on_true": on_true, "on_false": on_false}


def gun_item_def(key, flash, dual=False):
    """아이템 정의(gui 몸통만, 1인칭은 몸짓 틴트 + 불꽃 색 4..6, 그 밖은 흰색 고정 틴트 + 불꽃 색 0..2). key 의 모델들을 가리킨다"""
    A, NS = G["A"], G["NS"]
    first_t = [{"type": "minecraft:custom_model_data", "index": BODY_TINT, "default": WHITE}]
    other_t = [{"type": "minecraft:constant", "value": WHITE}]
    nomag = (A / f"models/item/{key}_nomag.json").is_file()
    first = mdl(f"{key}_dual" if dual else key, first_t)
    third = mdl(key, other_t)
    if nomag:
        if not dual:
            first = cond(mdl(f"{key}_nomag", first_t), first)
        third = cond(mdl(f"{key}_nomag", other_t), third)
    body = mdl(key, other_t)
    first, third = [first], [third]
    if flash and (A / f"models/item/muzzle_{key}.json").is_file():
        for lst, at in ((first, FLASH_FIRST), (third, FLASH_THIRD)):
            lst.append({"type": "minecraft:model", "model": f"{NS}:item/muzzle_{key}",
                        "tints": [{"type": "minecraft:custom_model_data", "index": at + i, "default": 0} for i in range(3)]})
    return {"hand_animation_on_swap": False,
            "model": {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": "gui", "model": body},
                                {"when": ["firstperson_righthand", "firstperson_lefthand"], "model": {"type": "minecraft:composite", "models": first}}],
                      "fallback": {"type": "minecraft:composite", "models": third}}}


def gun_item(key, flash, dual=False):
    A, NS = G["A"], G["NS"]
    model = json.loads((A / f"models/item/{key}.json").read_text(encoding="utf-8"))
    fm = flash_model(model, flash) if flash else None
    if fm is not None:
        G["put_json"](f"assets/{NS}/models/item/muzzle_{key}.json", fm)
    G["put_json"](f"assets/{NS}/items/{key}.json", gun_item_def(key, flash, dual))


def kind_alias(kind_key, model_key):
    """새 종류 키 아이템(gave:<종류 키>): 기본 모델 아이템 정의를 그대로 + HUD 실루엣(tools/make_hud.py GUNS)이 읽는 모델 사본"""
    A, NS = G["A"], G["NS"]
    if kind_key == model_key:
        return
    for suf in ("", "_nomag"):
        p = A / f"models/item/{model_key}{suf}.json"
        if p.is_file():
            G["put_json"](f"assets/{NS}/models/item/{kind_key}{suf}.json", json.loads(p.read_text(encoding="utf-8")))
    G["put_json"](f"assets/{NS}/items/{kind_key}.json", json.loads((A / f"items/{model_key}.json").read_text(encoding="utf-8")))
    s = G["SOURCES"][model_key]
    G["src"](kind_key, s["mod"], s["url"], s["version"], s["license"], f"종류 키 그림: {model_key} 모델 사본(HUD 실루엣, 종류 키 아이템)",
             variant_of=model_key)


# ------------------------------------------------------------------ 로켓, 상자, 상점 그림 정의

def tac(n):
    return f"tac:special/{n}"


def build_rocket():
    els = G["parts"]([tac("rpg7_rocket")])
    els, _ = G["recenter"](els)
    lo, hi = V.vbounds(els)
    k = 0.8 * 16 / max(hi[2] - lo[2], 1e-6)
    t_els = V.transform(els, k, 8 - 8 * k)
    disp = {"gui": G["fit_view"](t_els, V.rz(G["D"](45)) @ V.ry(G["D"](-90)), 15.0),
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    G["write_model"]("rocket_proj", t_els, disp)
    G["src"]("rocket_proj", *G["TAC"], f"TaC rpg7_rocket(PG-7 로켓) 날아가는 것. 길이 0.8 블록 실제 크기, 가운데 (8,8,8) = 엔티티 자리, 앞 = 모델 -Z, 요소 {len(els)}",
             world=True, center=True)


def build_box():
    els, _ = G["load_model"]("tac_gear:block/wooden_crate")
    size = G["world_prop"]("weapon_crate", els)
    G["src"]("weapon_crate", *TACGEAR, f"TAC GEAR block/wooden_crate(나무 보급 상자), 요소 {len(els)}, 무기 랜덤 상자 그림", world=True, size_blocks=size)
    NS = G["NS"]
    G["put_json"](f"assets/{NS}/items/weapon_box.json", {"model": {"type": "minecraft:composite", "models": [
        {"type": "minecraft:model", "model": f"{NS}:item/shop/frame_roll"}, {"type": "minecraft:model", "model": f"{NS}:item/weapon_crate"}]}})
    G["src"]("weapon_box", *TACGEAR, "상점 무기 랜덤 상자 칸: pack_src 뒤판 frame_roll + weapon_crate 모델(아이템 정의만)")


def build_icon(keys):
    NS = G["NS"]
    frames = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
              "cases": [{"when": t, "model": {"type": "minecraft:model", "model": f"{NS}:item/shop/frame_{t}"}} for t in TIERS],
              "fallback": {"type": "minecraft:model", "model": f"{NS}:item/shop/frame_none"}}
    kinds = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 1,
             "cases": [{"when": k, "model": {"type": "minecraft:model", "model": f"{NS}:item/{k}", "tints": [{"type": "minecraft:constant", "value": WHITE}]}}
                       for k in keys],
             "fallback": {"type": "minecraft:model", "model": f"{NS}:item/pistol", "tints": [{"type": "minecraft:constant", "value": WHITE}]}}
    G["put_json"](f"assets/{NS}/items/weapon_icon.json", {"model": {"type": "minecraft:composite", "models": [frames, kinds]}})
    G["src"]("weapon_icon", *G["TAC"], "상점 총 그림: strings[0] 등급 뒤판(pack_src frame_*), strings[1] 모델 키 또는 종류 키 총 모델(이 층, gui 표시 변환 그대로). "
             "아이템 정의만")


def run(g):
    G.update(g)
    made = {}
    for row in arms_table.ROWS:
        if row.get("build", True):
            made[row["key"]] = build_arm(row)
    for kind, (kkey, _, default) in arms_table.KINDS.items():
        kind_alias(kkey, default)
    icon_keys = list(OLD) + [r["key"] for r in arms_table.ROWS if r["key"] not in OLD]
    icon_keys += [k for k, _, _ in arms_table.KINDS.values() if k not in icon_keys]
    build_rocket()
    build_box()
    build_icon(icon_keys)
    g["ARMS_INFO"] = ARMS_INFO  # build_props 전역으로(write_props_json 이 props.json "arms" 에 씀)
    print(f"무기 모델 {len(made)}(요소 {sum(made.values())})")
