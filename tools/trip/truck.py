"""원정의 트럭(상자 화물차) 모델 층. 모델과 텍스처는 The Car Engine(MIT, ref/mods/the-car-engine, 받기 tools/trip/fetch_tce.py)의 것을
픽셀과 요소 그대로 옮긴다(손으로 만들거나 칠하지 않음). tools/make_trip.py 가 bake(layer) 를 부른다.

만드는 것(이름공간 gave):
- models/item/trip/truck.json        몸통(tmc-transbox 요소 그대로, 표시 변환은 인벤토리 그림 gui 만 남김)
- models/item/trip/truck_wreck.json  터진 뒤 뼈대: 짐칸 문(door_RL, door_RR)과 운전석 문(door_FL, door_FR), 보닛을 빼고 모든 면에 틴트 번호를 달아
                                      아이템 정의의 고정 틴트(그을린 색)로 어둡게 한다(텍스처는 그대로)
- models/item/trip/wheel.json        바퀴(wheel_1 요소 그대로)
- textures/item/trip/{truck, wheel, tmc_gray, tmc_chrome}.png  원본 바이트 그대로
- items/trip/truck.json              custom_model_data strings[0] 이 "wreck" 이면 뼈대, 아니면 몸통. 틴트 1(차체 색) = colors[0](없으면 흰색)
- items/trip/wheel.json              바퀴. strings[0] 이 "wreck" 이면 그을린 색
Java(kr.gave.trip.Truck)가 쓰는 치수는 치수() 가 돌려주고 make_trip 이 build/trip/truck_dims.json 에 적는다: Truck.java 의 상수와 같아야 한다.
"""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "ref/mods/the-car-engine/extracted"
MODEL = "assets/tce/models/vehicles/tmc-transbox.json"
WHEEL = "assets/tce/models/wheels/wheel_1.json"
TEX = {  # 모드 텍스처 id → (원본 경로, 팩 텍스처 id)
    "tce:vehicles/tmc-transbox": ("assets/tce/textures/vehicles/tmc-transbox.png", "gave:item/trip/truck"),
    "tce:badges/tmc_gray": ("assets/tce/textures/badges/tmc_gray.png", "gave:item/trip/tmc_gray"),
    "tce:badges/tmc_chrome": ("assets/tce/textures/badges/tmc_chrome.png", "gave:item/trip/tmc_chrome"),
    "tce:wheels/wheel_1": ("assets/tce/textures/wheels/wheel_1.png", "gave:item/trip/wheel"),
}
# 터진 뒤 날아가는 부품(모드 모델의 묶음 이름)
BLOWN = ("door_RL", "door_RR", "door_FL", "door_FR", "hood")
# 그을린 색(고정 틴트): 텍스처에 곱해져 어두운 잿빛 갈색이 된다
SCORCH = 0x4A423C

# 모드가 차를 놓는 값(data/thescarengine/function/cars/tmc-transbox/display.mcfunction, bone.mcfunction 에서 읽은 것):
# 몸통 디스플레이 배율 2, 옮김 (0, 0.73, 1.0), 바퀴 자리 (±0.95, 0.23, 앞 3.22 / 뒤 -0.5), 바퀴 배율 (0.95, 0.8, 0.8)
MOD_SCALE = 2.0
MOD_BODY_Y = 0.73
MOD_WHEEL = {"x": 0.95, "y": 0.23, "front": 3.22, "rear": -0.5, "scale": (0.95, 0.8, 0.8)}
MOD_BODY_Z = 1.0

LICENSES = {"LICENSE-MIT-THE-CAR-ENGINE.txt": SRC / "LICENSE"}


def credits():
    info = json.loads((ROOT / "ref/mods/the-car-engine/_info.json").read_text(encoding="utf-8"))
    ev = info["license_evidence"]
    return [
        "[원정 1] The Car Engine (데이터팩 + 리소스팩)",
        "  https://modrinth.com/datapack/tce , https://github.com/MCTCRS/thecarengine (커밋 " + info["commit"][:12] + ")",
        "  라이선스: MIT. " + ev["copyright_line"] + " (전문 LICENSE-MIT-THE-CAR-ENGINE.txt, 팩 맨 위)",
        "  모델 파일의 credit 줄: Made with Blockbench by melonapple (https://www.youtube.com/@melonapple5)",
        "  쓴 것: 상자 화물차 tmc-transbox(모델, 텍스처), 바퀴 wheel_1, 배지 텍스처 tmc_gray, tmc_chrome",
        "  바꾼 것(2026-11-12): 이름공간과 경로를 gave:item/trip/ 로 옮김, 표시 변환을 뺌, 터진 뒤 모습용으로 문과 보닛 묶음을 뺀 사본에",
        "  틴트 번호를 달아 어둡게 함(텍스처 픽셀은 그대로). 원작자가 이 팩을 만들거나 승인한 것이 아니다.",
    ]


def phrases():
    """HUD 글귀(data/phrases/trip.json 으로 모임). 짧은 것만."""
    return {
        "trip_truck_dying": "트럭이 터진다",
        "trip_all_gabe": "모두 게이브가 되었다",
        "trip_truck_hit": "트럭이 맞는다",
        "trip_repaired": "고쳤다",
        "cause_trip_truck": "트럭 폭발",
        "trip_dead_rule": "죽으면 저것이 된다",
        "trip_trapped": "갇혔다",
        "trip_revived": "꺼냈다",
    }


def _load(rel):
    return json.loads((SRC / rel).read_text(encoding="utf-8"))


def _group_elems(model, names):
    """묶음 이름들에 든 요소 번호"""
    out = set()

    def walk(g, inside):
        if isinstance(g, int):
            if inside:
                out.add(g)
            return
        hit = inside or g.get("name") in names
        for c in g.get("children", []):
            walk(c, hit)

    for g in model.get("groups", []):
        walk(g, False)
    return out


def _convert(model, drop=(), tint_all=False, keep_gui=False):
    """모드 모델 → 팩 모델(요소, uv 그대로). drop 요소는 빼고, tint_all 이면 틴트 번호 없는 면에 0 을 단다."""
    tex = {}
    for k, v in model["textures"].items():
        if v not in TEX:
            raise KeyError(f"모르는 텍스처 {v}")
        tex[k] = TEX[v][1]
    if "particle" not in tex:
        tex["particle"] = next(iter(tex.values()))
    els = []
    for i, e in enumerate(model["elements"]):
        if i in drop:
            continue
        for k in ("from", "to"):
            if not all(-16 <= v <= 32 for v in e[k]):
                raise ValueError(f"요소 {i} 의 {k} {e[k]} 가 26.3 범위(-16..32) 밖")
        ne = {"from": e["from"], "to": e["to"], "faces": {}}
        r = e.get("rotation")
        if r and r.get("angle"):
            ne["rotation"] = {"angle": r["angle"], "axis": r["axis"], "origin": r["origin"]}
            if r.get("rescale"):
                ne["rotation"]["rescale"] = True
        if "shade" in e:
            ne["shade"] = e["shade"]
        if "light_emission" in e:
            ne["light_emission"] = e["light_emission"]
        for fn, f in e["faces"].items():
            nf = {"uv": f["uv"], "texture": f["texture"]}
            if "rotation" in f:
                nf["rotation"] = f["rotation"]
            if "tintindex" in f:
                nf["tintindex"] = f["tintindex"]
            elif tint_all:
                nf["tintindex"] = 0
            ne["faces"][fn] = nf
        els.append(ne)
    out = {"credit": model.get("credit", ""), "textures": tex, "elements": els}
    # 표시 변환은 인벤토리 그림(gui)만 남긴다: 상점의 수리 칸이 이 모델을 아이콘으로 쓴다. 디스플레이 엔티티는 변환 없음(NONE)으로 그려 이것과 무관하다
    if keep_gui and "gui" in model.get("display", {}):
        out["display"] = {"gui": model["display"]["gui"]}
    if "texture_size" in model:
        out["texture_size"] = model["texture_size"]
    return out


def _bounds(model, drop=()):
    mn, mx = [1e9] * 3, [-1e9] * 3
    for i, e in enumerate(model["elements"]):
        if i in drop:
            continue
        for p in _corners(e):
            for a in range(3):
                mn[a] = min(mn[a], p[a])
                mx[a] = max(mx[a], p[a])
    return mn, mx


def _rot(e):
    r = e.get("rotation")
    if not r or not r.get("angle"):
        return None
    a = math.radians(r["angle"])
    c, s = math.cos(a), math.sin(a)
    if r["axis"] == "x":
        return [[1, 0, 0], [0, c, -s], [0, s, c]]
    if r["axis"] == "y":
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def _corners(e):
    f, t = e["from"], e["to"]
    pts = [[x, y, z] for x in (f[0], t[0]) for y in (f[1], t[1]) for z in (f[2], t[2])]
    R = _rot(e)
    if R is None:
        return pts
    o = e["rotation"]["origin"]
    out = []
    for p in pts:
        d = [p[i] - o[i] for i in range(3)]
        out.append([sum(R[i][j] * d[j] for j in range(3)) + o[i] for i in range(3)])
    return out


def dims():
    """Java(Truck)가 쓰는 치수. 모델 좌표는 px(16 = 한 블록), 앞은 -Z(north). Truck.java 의 상수와 같아야 한다."""
    m = _load(MODEL)
    mn, mx = _bounds(m)
    # 모드 배율(2)로 놓았을 때 크기(블록)
    size = [(mx[i] - mn[i]) / 16 * MOD_SCALE for i in range(3)]
    # 바퀴 자리(모델 px): 모드의 놓는 값에서 거꾸로. 디스플레이 좌표 z = -(mz - 8) / 16 * 배율 + 옮김 → mz = 8 - (z - 옮김) * 16 / 배율
    wz_front = 8 - (MOD_WHEEL["front"] - MOD_BODY_Z) * 16 / MOD_SCALE
    wz_rear = 8 - (MOD_WHEEL["rear"] - MOD_BODY_Z) * 16 / MOD_SCALE
    wy = 8 + (MOD_WHEEL["y"] - MOD_BODY_Y) * 16 / MOD_SCALE
    wx = MOD_WHEEL["x"] * 16 / MOD_SCALE
    ground = wy - MOD_WHEEL["scale"][1] * 16 / MOD_SCALE / 2  # 바퀴 밑(모델 px): 땅
    return {
        "bounds_min": [round(v, 4) for v in mn], "bounds_max": [round(v, 4) for v in mx],
        "center_x": round((mn[0] + mx[0]) / 2, 4), "center_z": round((mn[2] + mx[2]) / 2, 4),
        "ground_y": round(ground, 4), "size_at_mod_scale": [round(v, 3) for v in size],
        "mod_scale": MOD_SCALE,
        "wheel_px": {"x": round(wx, 4), "y": round(wy, 4), "front_z": round(wz_front, 4), "rear_z": round(wz_rear, 4)},
        "wheel_scale": list(MOD_WHEEL["scale"]),
        "elements": len(m["elements"]), "faces": sum(len(e["faces"]) for e in m["elements"]),
    }


def bake(layer: Path):
    body, wheel = _load(MODEL), _load(WHEEL)
    blown = _group_elems(body, BLOWN)
    put = {}
    put["assets/gave/models/item/trip/truck.json"] = _convert(body, keep_gui=True)
    put["assets/gave/models/item/trip/truck_wreck.json"] = _convert(body, drop=blown, tint_all=True)
    put["assets/gave/models/item/trip/wheel.json"] = _convert(wheel, tint_all=True, keep_gui=True)
    white, scorch = {"type": "minecraft:constant", "value": 0xFFFFFF}, {"type": "minecraft:constant", "value": SCORCH}
    body_tints = [white, {"type": "minecraft:custom_model_data", "index": 0, "default": 0xFFFFFF}]
    put["assets/gave/items/trip/truck.json"] = {"model": {
        "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
        "cases": [{"when": "wreck", "model": {"type": "minecraft:model", "model": "gave:item/trip/truck_wreck", "tints": [scorch, scorch]}}],
        "fallback": {"type": "minecraft:model", "model": "gave:item/trip/truck", "tints": body_tints}}}
    put["assets/gave/items/trip/wheel.json"] = {"model": {
        "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
        "cases": [{"when": "wreck", "model": {"type": "minecraft:model", "model": "gave:item/trip/wheel", "tints": [scorch, scorch]}}],
        "fallback": {"type": "minecraft:model", "model": "gave:item/trip/wheel", "tints": [white, white]}}}
    for rel, data in put.items():
        p = layer / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    n_tex = 0
    for src, tid in TEX.values():
        ns, path = tid.split(":")
        p = layer / f"assets/{ns}/textures/{path}.png"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes((SRC / src).read_bytes())
        n_tex += 1
    d = dims()
    return {"models": 3, "items": 2, "textures": n_tex, "elements": d["elements"], "faces": d["faces"], "blown": len(blown), "dims": d}


def _scene(wreck=False):
    """미리보기와 아이콘이 같이 쓰는 요소와 텍스처(tools/gabe/render.py 의 꼴)"""
    import numpy as np
    from PIL import Image

    class F:
        def __init__(self, uv, tex):
            self.uv, self.tex = uv, tex

    class E:
        pass

    body = _load(MODEL)
    drop = _group_elems(body, BLOWN) if wreck else set()
    texs = {}
    for k, v in body["textures"].items():
        arr = np.array(Image.open(SRC / TEX[v][0]).convert("RGBA"))
        if wreck:
            arr = arr.copy()
            for c in range(3):
                arr[..., c] = (arr[..., c].astype(np.float32) * ((SCORCH >> (16 - 8 * c)) & 255) / 255).astype(np.uint8)
        texs["#" + k] = arr
    elems = []
    for i, e in enumerate(body["elements"]):
        if i in drop:
            continue
        o = E()
        o.frm, o.to = e["from"], e["to"]
        R = _rot(e)
        o.rot_m = np.array(R) if R is not None else None
        o.origin = e["rotation"]["origin"] if R is not None else None
        o.faces = {fn: F(f["uv"], f["texture"]) for fn, f in e["faces"].items()}
        elems.append(o)
    return elems, texs


def side_image(max_w, max_h):
    """옆모습 RGBA 그림을 max_w x max_h 화소 안에 맞춰 돌려줌(HUD 트럭 아이콘: tools/make_hud.py truck_icon). 앞이 오른쪽"""
    import sys
    from PIL import Image
    sys.path.insert(0, str(ROOT / "tools"))
    from gabe import render
    elems, texs = _scene()
    im, _ = render.render(elems, texs, view="side", size=256, margin=2, ss=2)
    box = im.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox()
    im = im.crop(box)
    k = min(max_w / im.width, max_h / im.height)
    return im.resize((max(1, round(im.width * k)), max(1, round(im.height * k))), Image.LANCZOS)


def preview(out: Path, wreck=False):
    """정사영 미리보기(옆, 앞, 위, 뒤). 게임 화면과 같지는 않다(tools/gabe/render.py 를 빌려 씀)."""
    import sys
    import numpy as np
    from PIL import Image
    sys.path.insert(0, str(ROOT / "tools"))
    from gabe import render

    class F:
        def __init__(self, uv, tex):
            self.uv, self.tex = uv, tex

    class E:
        pass

    body = _load(MODEL)
    drop = _group_elems(body, BLOWN) if wreck else set()
    texs = {}
    for k, v in body["textures"].items():
        arr = np.array(Image.open(SRC / TEX[v][0]).convert("RGBA"))
        if wreck:
            arr = arr.copy()
            for c in range(3):
                arr[..., c] = (arr[..., c].astype(np.float32) * ((SCORCH >> (16 - 8 * c)) & 255) / 255).astype(np.uint8)
        texs["#" + k] = arr
    elems = []
    for i, e in enumerate(body["elements"]):
        if i in drop:
            continue
        o = E()
        o.frm, o.to = e["from"], e["to"]
        R = _rot(e)
        o.rot_m = np.array(R) if R is not None else None
        o.origin = e["rotation"]["origin"] if R is not None else None
        o.faces = {fn: F(f["uv"], f["texture"]) for fn, f in e["faces"].items()}
        elems.append(o)
    views = ["side", "front", "top", "back"]
    size = 360
    sheet = Image.new("RGBA", (size * len(views), size), (58, 52, 46, 255))
    for i, v in enumerate(views):
        im, _ = render.render(elems, texs, view=v, size=size, margin=8, ss=2)
        sheet.alpha_composite(im, (i * size, 0))
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.convert("RGB").save(out)
    return out
