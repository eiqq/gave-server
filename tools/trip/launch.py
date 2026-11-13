"""원정 발사장(31~35) 모델 층: 우주선, 발사대, 건조 지점 기계, 투광등, 그리고 4막 하늘의 흐린 달 테두리 지우기.

tools/make_trip.py 가 bake(layer) 를 부른다(받은 모드 파일을 읽으므로 -I 로). 받기는 tools/trip/fetch_stellaris.py.
모델은 모드에서 가져온다(손으로 블록을 조합하거나 칠하지 않음):
- 우주선: Stellaris 큰 로켓(BigRocketModel.java 의 ModelPart 정의를 글로 읽어 요소로, 텍스처 entity/rocket/big/default.png 픽셀 그대로).
  모드가 기본으로 숨기는 덧붙이 부품(연료통, 태양 가리개, 짐칸, 모터)은 뺀다. 건조 상태 넷은 같은 모델의 부품 묶음을 차례로 더한 것:
  base(아래 틀, 다리, 판, 날개) → hull(+ 몸통) → nose(+ 머리) → lit(머리까지 + 불 켜짐: 빛나는 면). 같은 좌표계라 Java 는 아이템만 바꾼다.
- 발사대: Stellaris RocketLaunchPadModel(판 Platform 과 탑 LStructure, RStructure, antenna). 판과 탑을 따로 둔다(판은 Java 가 납작하게 놓음).
- 건조 지점: Stellaris 블록 모델 연료 정제기(fuel_refinery, 켜짐 _on), 석탄 발전기(coal_generator, 켜짐 _on), 공학 작업대(engineering_station).
- 투광등은 여기서 굽지 않는다: trip_special_b 의 tools/trip/padlamp.py(TrafficCraft 쌍둥이 가로등, gave:trip/pad_lamp)와 Java PadLights 를 같이 쓴다.
- 달: 4막(새 달)의 서쪽 하늘에 바닐라 new_moon.png 의 어두운 픽셀이 흐린 네모로 남는다(trip_theme 보고). 그 그림을 완전히 투명한 같은 크기 그림으로 덮는다.

Java(kr.gave.trip.Launch)가 쓰는 치수는 plugin/src/main/resources/trip/launch.json 에 쓴다(모델 px 의 가운데, 바닥, 높이, 권하는 표시 배율).
"""
import json
import math
import re
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/props"))
import vmodel as V  # noqa: E402

REF = ROOT / "ref/mods/stellaris"
SRC = REF / "extracted"
JS = SRC / "common/src/main/java/org/exodusstudio/stellaris/client/renderers"
AS = SRC / "common/src/main/resources/assets/stellaris"
NS = "gave"
MODIFIED = "2026-11-12"
OUT_JSON = ROOT / "plugin/src/main/resources/trip/launch.json"

# 우주선 높이(블록)를 이만큼에 맞추는 표시 배율을 권한다(원정.md 6.1절 '높이 18~20블록'). 발사대는 모드 비율(로켓 0.8 배로 그림)을 지켜 같이 키운다
ROCKET_HEIGHT = 18.0
MOD_ROCKET_SCALE = 0.8

LICENSES = {"LICENSE-MIT-STELLARIS.txt": SRC / "LICENSE"}

# ---------------------------------------------------------------------------------------------------------------- 자바 모델 글 읽기

_NUM = r"-?\d+(?:\.\d+)?(?:[eE]-?\d+)?[fFdD]?"


def _num(s):
    return float(s.rstrip("fFdD"))


def _balanced(text, i):
    """text[i] == '(' 에서 짝이 맞는 ')' 다음 자리."""
    depth = 0
    for j in range(i, len(text)):
        c = text[j]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return j + 1
    raise ValueError("괄호 짝이 안 맞음")


def _split_top(s):
    out, depth, cur = [], 0, []
    for c in s:
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        if c == "," and depth == 0:
            out.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
    if cur:
        out.append("".join(cur).strip())
    return out


def _calls(expr):
    """'.name(args)' 차례(바깥 괄호만)."""
    out = []
    for m in re.finditer(r"\.(\w+)\(", expr):
        pass
    i = 0
    while True:
        m = re.compile(r"\.(\w+)\(").search(expr, i)
        if not m:
            break
        end = _balanced(expr, m.end() - 1)
        out.append((m.group(1), expr[m.end():end - 1]))
        i = end
    return out


def parse_java(path):
    """createBodyLayer 의 PartDefinition 들 → (텍스처 너비, 높이, 부품 목록[{name, parent, pose, cubes}])."""
    text = Path(path).read_text(encoding="utf-8")
    body = text[text.index("createBodyLayer"):]
    root_var = re.search(r"PartDefinition\s+(\w+)\s*=\s*\w+\.getRoot\(\)", body).group(1)
    tex = re.search(r"LayerDefinition\.create\(\s*\w+\s*,\s*(\d+)\s*,\s*(\d+)\s*\)", body)
    W, H = int(tex.group(1)), int(tex.group(2))
    parts, var_index = [], {root_var: None}
    for m in re.finditer(r"PartDefinition\s+(\w+)\s*=\s*(\w+)\.addOrReplaceChild\(", body):
        end = _balanced(body, m.end() - 1)
        args = _split_top(body[m.end():end - 1])
        name = args[0].strip('"')
        builder, pose_s = args[1], args[2]
        cubes, uv, mirror = [], (0, 0), False
        for meth, a in _calls(builder):
            if meth == "texOffs":
                uv = tuple(int(_num(x)) for x in _split_top(a))
            elif meth == "mirror":
                mirror = a.strip() != "false"
            elif meth == "addBox":
                parts_a = _split_top(a)
                nums = [_num(x) for x in parts_a if re.fullmatch(_NUM, x)]
                grow = 0.0
                for x in parts_a:
                    g = re.search(r"CubeDeformation\(\s*(" + _NUM + r")", x)
                    if g:
                        grow = _num(g.group(1))
                cubes.append((uv[0], uv[1], *nums[:6], grow, mirror))
        pm = re.match(r"PartPose\.(\w+)\((.*)\)$", pose_s.strip(), re.S)
        if pose_s.strip() == "PartPose.ZERO":
            pose = (0.0,) * 6
        else:
            kind, pa = pm.group(1), [_num(x) for x in _split_top(pm.group(2))]
            if kind == "offset":
                pose = (*pa, 0.0, 0.0, 0.0)
            elif kind == "offsetAndRotation":
                pose = tuple(pa)
            elif kind == "rotation":
                pose = (0.0, 0.0, 0.0, *pa)
            else:
                raise ValueError(f"모르는 PartPose.{kind}")
        parent = var_index.get(m.group(2))
        if m.group(2) not in var_index:
            raise ValueError(f"부모 변수 {m.group(2)} 를 모름")
        parts.append({"name": name, "parent": parent, "pose": pose, "cubes": cubes})
        var_index[m.group(1)] = len(parts) - 1
    return W, H, parts


def _rot(a, b, c):
    return V.rz(c) @ V.ry(b) @ V.rx(a)  # ModelPart: rotationZYX(z, y, x)


def chain(parts, i):
    out = []
    while i is not None:
        out.append(parts[i]["name"])
        i = parts[i]["parent"]
    return out


def elements(parts, W, H, S, offset, tex="#0", keep=lambda names: True):
    """부품 → 26.3 요소. 블록 좌표(px) = S * (F @ 부품 공간 px) + offset, F = diag(-1, -1, 1)(엔티티 모델은 y 가 아래)."""
    F = np.diag([-1.0, -1.0, 1.0])
    offset = np.array(offset, float)

    def world(i):
        p = parts[i]
        x, y, z, a, b, c = p["pose"]
        R, o = _rot(a, b, c), np.array([x, y, z], float)
        if p["parent"] is None:
            return R, o
        PR, Po = world(p["parent"])
        return PR @ R, Po + PR @ o

    out = []
    for i, p in enumerate(parts):
        if not keep(chain(parts, i)):
            continue
        R, o = world(i)
        Rb = F @ R @ F
        ob = S * (F @ o) + offset
        for u, v, x, y, z, w, h, d, g, mir in p["cubes"]:
            lo = np.array([x - g, y - g, z - g])
            hi = np.array([x + w + g, y + h + g, z + d + g])
            a, b = S * (F @ lo), S * (F @ hi)
            fr, to = ob + np.minimum(a, b), ob + np.maximum(a, b)
            wi, hi_, di = math.floor(w), math.floor(h), math.floor(d)
            l_, n, o_, pp, q, r = u, u + di, u + di + wi, u + di + wi + wi, u + di + wi + di, u + di + wi + di + wi
            m, s, t = v, v + di, v + di + hi_
            faces = {"north": [n, s, o_, t], "south": [q, s, r, t], "east": [l_, s, n, t], "west": [o_, s, q, t],
                     "up": [o_, s, n, m], "down": [pp, m, o_, s]}
            if mir:
                faces["east"], faces["west"] = faces["west"], faces["east"]
                faces = {k: [f[2], f[1], f[0], f[3]] for k, f in faces.items()}
            sz = np.array([w, h, d])
            drop = set()
            if sz[0] == 0:
                drop |= {"north", "south", "up", "down"}
            if sz[1] == 0:
                drop |= {"north", "south", "east", "west"}
            if sz[2] == 0:
                drop |= {"east", "west", "up", "down"}
            el = {"from": [round(float(c), 4) for c in fr], "to": [round(float(c), 4) for c in to],
                  "faces": {k: {"uv": [round(min(max(f[0] * 16 / W, 0), 16), 4), round(min(max(f[1] * 16 / H, 0), 16), 4),
                                       round(min(max(f[2] * 16 / W, 0), 16), 4), round(min(max(f[3] * 16 / H, 0), 16), 4)],
                                "texture": tex} for k, f in faces.items() if k not in drop or (sz == 0).sum() == 0},
                  "_part": p["name"], "_chain": chain(parts, i)}
            ang = V.to_euler_zyx(Rb)
            if any(abs(a_) > 1e-3 for a_ in ang):
                el["rotation"] = {"origin": [round(float(c), 4) for c in ob], "x": ang[0], "y": ang[1], "z": ang[2]}
            out.append(el)
    return out


def raw_bounds(els):
    lo = np.array([min(min(e["from"][i], e["to"][i]) for e in els) for i in range(3)])
    hi = np.array([max(max(e["from"][i], e["to"][i]) for e in els) for i in range(3)])
    return lo, hi


def fit(parts, W, H, keep, margin=0.25):
    """모든 요소(돌린 뒤와 돌리기 전 상자 모두)가 -16..32 에 들도록 배율 S 와 옮김을 정한다: 가로 가운데 8, 바닥 -16."""
    els = elements(parts, W, H, 1.0, (0, 0, 0), keep=keep)
    lo, hi = V.bounds(els)
    rlo, rhi = raw_bounds(els)
    lo, hi = np.minimum(lo, rlo), np.maximum(hi, rhi)
    span = max(hi - lo)
    S = (48 - 2 * margin) / span
    cx, cz = (lo[0] + hi[0]) / 2, (lo[2] + hi[2]) / 2
    off = np.array([8 - S * cx, -16 + margin - S * lo[1], 8 - S * cz])
    return S, off


def clean(els):
    out = []
    for e in els:
        ne = {k: v for k, v in e.items() if not k.startswith("_")}
        for k in ("from", "to"):
            if not all(-16 <= x <= 32 for x in ne[k]):
                raise ValueError(f"요소 {k} {ne[k]} 가 26.3 범위(-16..32) 밖({e.get('_part')})")
        out.append(ne)
    return out


# ---------------------------------------------------------------------------------------------------------------- 층

class Layer:
    def __init__(self, root: Path):
        self.root = root
        self.tex = {}
        self.items = []
        self.models = {}
        self.animated = []

    def texture(self, src: Path, name: str, mcmeta: Path = None):
        if name.endswith(("_n", "_s", "_e")):
            name += "x"
        tid = f"{NS}:item/trip/{name}"
        if tid in self.tex.values():
            return tid
        dst = self.root / f"assets/{NS}/textures/item/trip/{name}.png"
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(src.read_bytes())
        if mcmeta is not None and mcmeta.is_file():
            (dst.parent / (dst.name + ".mcmeta")).write_bytes(mcmeta.read_bytes())
        else:
            # 모드에 움직임 설명 없이 들어 있는 세로 띠(fuel_refinery_front_on 16 x 144): 그대로면 한 칸에 아홉 칸이 눌려 그려진다. 같은 그림을 칸마다 넘기게
            from PIL import Image
            with Image.open(src) as im:
                w, h = im.size
            if h > w and h % w == 0:
                (dst.parent / (dst.name + ".mcmeta")).write_text(json.dumps({"animation": {"frametime": 4}}), encoding="utf-8")
                self.animated.append(name)
        self.tex[str(src)] = tid
        return tid

    def model(self, key, textures, els, credit, extra=None):
        m = {"credit": credit, "textures": textures, "elements": els}
        if extra:
            m.update(extra)
        p = self.root / f"assets/{NS}/models/item/trip/{key}.json"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(m, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        lo, hi = V.bounds(els)
        self.models[key] = {"elements": len(els), "lo": [round(float(x), 3) for x in lo], "hi": [round(float(x), 3) for x in hi]}

    def item(self, key, model=None):
        p = self.root / f"assets/{NS}/items/trip/{key}.json"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"{NS}:item/trip/{model or key}"}}, ensure_ascii=False, indent=1),
                     encoding="utf-8")
        self.items.append(key)


# 큰 로켓: 모드가 기본으로 숨기는 덧붙이 부품
ROCKET_HIDDEN = {"tank_upgrade", "sunflare_protection", "storage_upgrade", "motor_upgrade"}
# 건조 상태: 부품 맨 위 묶음 이름
ROCKET_STATES = {
    "base": {"Bottom", "Wings"},
    "hull": {"Bottom", "Wings", "MainBody"},
    "nose": {"Bottom", "Wings", "MainBody", "Roof"},
    "lit": {"Bottom", "Wings", "MainBody", "Roof"},
}
# 불 켜짐: 빛나게 할 묶음(머리 꼭대기와 아래 틀). light_emission 은 셰이더 없는 클라의 대비, 밝기는 Java 디스플레이 밝기가 낸다
ROCKET_GLOW = {"RoofTop", "BottomFrame"}


def bake_rocket(L: Layer, dims: dict):
    W, H, parts = parse_java(JS / "rockets/models/BigRocketModel.java")
    tex = L.texture(AS / "textures/entity/rocket/big/default.png", "lc_rocket")
    every = lambda names: not (set(names) & ROCKET_HIDDEN)  # noqa: E731
    S, off = fit(parts, W, H, every)
    credit = "Stellaris big rocket (st0x0ef, MIT / CC BY-NC-SA 4.0), ModelPart moved to a 26.3 item model"
    for state, tops in ROCKET_STATES.items():
        keep = lambda names, tops=tops: every(names) and names[-1] in tops  # noqa: E731
        els = elements(parts, W, H, S, off, keep=keep)
        if state == "lit":
            for e in els:
                if set(e["_chain"]) & ROCKET_GLOW:
                    e["light_emission"] = 15
        L.model(f"lc_rocket_{state}", {"0": tex, "particle": tex}, clean(els), credit)
        L.item(f"lc_rocket_{state}")
    full = elements(parts, W, H, S, off, keep=every)
    lo, hi = V.bounds(full)
    rocket_px = float(hi[1] - lo[1])
    # 모드 px 하나 = S 모델 px. 우주선 높이를 ROCKET_HEIGHT 블록으로: 표시 배율 k(모델 px 16 = 한 블록 x k)
    k = ROCKET_HEIGHT * 16 / rocket_px
    dims["rocket"] = {"cx": round(float((lo[0] + hi[0]) / 2), 4), "cz": round(float((lo[2] + hi[2]) / 2), 4), "ground": round(float(lo[1]), 4),
                      "top": round(float(hi[1]), 4), "width": round(float(max(hi[0] - lo[0], hi[2] - lo[2])), 4), "scale": round(k, 4),
                      "modPx": round(S, 6), "elements": len(full)}
    # 모드 비율: 발사대는 로켓을 0.8 배로 그린 것과 같은 1 배 → 발사대 모드 px 한 개의 블록 = (k * S / 16) / 0.8
    dims["_padBlockPerModPx"] = k * S / 16 / MOD_ROCKET_SCALE
    return k


def bake_pad(L: Layer, dims: dict):
    W, H, parts = parse_java(JS / "launchpad/RocketLaunchPadModel.java")
    tex = L.texture(AS / "textures/block/rocket_launch_pad.png", "lc_pad")
    credit = "Stellaris rocket launch pad (st0x0ef, MIT / CC BY-NC-SA 4.0), ModelPart moved to a 26.3 item model"
    for key, tops in (("lc_pad_base", {"Platform"}), ("lc_pad_tower", {"LStructure", "RStructure", "antenna"})):
        keep = lambda names, tops=tops: names[-1] in tops  # noqa: E731
        S, off = fit(parts, W, H, keep)
        els = elements(parts, W, H, S, off, keep=keep)
        L.model(key, {"0": tex, "particle": tex}, clean(els), credit)
        L.item(key)
        lo, hi = V.bounds(els)
        # 같은 세계 크기로: 모드 px 하나가 _padBlockPerModPx 블록 → 표시 배율 = 그 값 * 16 / S
        k = dims["_padBlockPerModPx"] * 16 / S
        # 판 가운데(모드 좌표 x 0, z 0 = 블록 가운데)가 모델 px 어디인가: 판의 가로 가운데(대칭)
        plat = elements(parts, W, H, S, off, keep=lambda n: n[-1] == "Platform")
        plo, phi = V.bounds(plat)
        dims[key] = {"cx": round(float((plo[0] + phi[0]) / 2), 4), "cz": round(float((plo[2] + phi[2]) / 2), 4), "ground": round(float(plo[1]), 4),
                     "top": round(float(hi[1]), 4), "platTop": round(float(phi[1]), 4), "half": round(float((phi[0] - plo[0]) / 2), 4),
                     "scale": round(k, 4), "elements": len(els)}


def block_model(src: Path):
    return json.loads(src.read_text(encoding="utf-8"))


def bake_machines(L: Layer, dims: dict):
    credit = "Stellaris machine block (st0x0ef, MIT / CC BY-NC-SA 4.0), moved to a 26.3 item model"
    for key, name in (("lc_fuel", "fuel_refinery"), ("lc_fuel_on", "fuel_refinery_on"), ("lc_gen", "coal_generator"), ("lc_gen_on", "coal_generator_on"),
                      ("lc_station", "engineering_station")):
        m = block_model(AS / f"models/block/{name}.json")
        texs = {}
        for var, tid in m["textures"].items():
            p = tid.split(":", 1)[1]
            src = AS / f"textures/{p}.png"
            texs[var] = L.texture(src, "lc_" + p.split("/")[-1], AS / f"textures/{p}.png.mcmeta")
        els = []
        for e in m["elements"]:
            ne = {"from": e["from"], "to": e["to"], "faces": {}}
            if "rotation" in e:
                ne["rotation"] = e["rotation"]
            if "shade" in e:
                ne["shade"] = e["shade"]
            for fn, f in e["faces"].items():
                nf = {k: v for k, v in f.items() if k in ("uv", "texture", "rotation", "tintindex")}
                ne["faces"][fn] = nf
            if key.endswith("_on"):
                ne["light_emission"] = 10
            els.append(ne)
        extra = {"texture_size": m["texture_size"]} if "texture_size" in m else None
        L.model(key, texs, els, credit, extra)
        L.item(key)
    dims["machine"] = {"cx": 8, "cz": 8, "ground": 0, "top": 16}


def bake_moon(L: Layer):
    """4막 새 달: 바닐라 new_moon.png(32 x 32, 어두운 픽셀이 더하기로 그려져 검은 하늘에 흐린 네모가 남음)를 같은 크기의 투명 그림으로."""
    from PIL import Image
    p = L.root / "assets/minecraft/textures/environment/celestial/moon/new_moon.png"
    p.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGBA", (32, 32), (0, 0, 0, 0)).save(p)


def bake(layer: Path):
    for need in (JS / "rockets/models/BigRocketModel.java", JS / "launchpad/RocketLaunchPadModel.java"):
        if not need.is_file():
            raise FileNotFoundError(f"{need} 가 없다: python -I -X utf8 tools/trip/fetch_stellaris.py 로 받을 것")
    L = Layer(layer)
    dims = {}
    bake_rocket(L, dims)
    bake_pad(L, dims)
    bake_machines(L, dims)
    bake_moon(L)
    dims.pop("_padBlockPerModPx", None)
    dims["note"] = "tools/trip/launch.py 가 굽는 파일(손으로 고치지 말 것). 모델 px(16 = 한 블록 x scale): cx, cz 가로 가운데, ground 바닥, top 꼭대기, scale 권하는 표시 배율"
    dims["models"] = L.models
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(dims, ensure_ascii=False, indent=1), encoding="utf-8")
    return {"models": len(L.models), "items": len(L.items), "textures": len(L.tex), "rocket_scale": dims["rocket"]["scale"],
            "rocket_elements": dims["rocket"]["elements"], "pad_scale": dims["lc_pad_base"]["scale"]}


def credits():
    info = json.loads((REF / "_info.json").read_text(encoding="utf-8"))
    ev = info["license_evidence"]
    return [
        "[원정 10] Stellaris (st0x0ef, 모드), 발사장의 우주선, 발사대, 기계",
        "  https://modrinth.com/mod/stellaris , https://github.com/st0x0ef/stellaris-2.0 (커밋 " + info["commit"][:12] + ")",
        "  라이선스: 저장소 LICENSE 는 MIT(" + ev["copyright_line"] + ", 전문 LICENSE-MIT-STELLARIS.txt, 팩 맨 위),",
        "  Modrinth 라이선스 칸은 CC BY-NC-SA 4.0(https://creativecommons.org/licenses/by-nc-sa/4.0/). 표기가 엇갈려 두 조건을 모두 지킨다:",
        "  저작자 표시, 비상업, 바꾼 것도 같은 조건(CC BY-NC-SA 4.0)으로 나눔, MIT 저작권 줄과 허락 문구.",
        "  쓴 것: 큰 로켓(BigRocketModel 부품 정의, 텍스처 entity/rocket/big/default.png), 발사대(RocketLaunchPadModel, 텍스처 block/rocket_launch_pad.png),",
        "  연료 정제기, 석탄 발전기(켜짐 포함), 공학 작업대 블록 모델과 텍스처",
        "  (assets/gave/models/item/trip/lc_*, assets/gave/textures/item/trip/lc_*)",
        f"  바꾼 것({MODIFIED}): 자바 엔티티 모델 부품을 26.3 아이템 모델 요소로 옮김(크기를 줄여 넣고 표시 배율로 되돌림), 로켓의 덧붙이 부품을 빼고 건조 단계마다",
        "  부품 묶음을 나눔, 발사대의 판과 탑을 나눔, 블록 모델을 아이템 모델로 옮기고 켜진 기계에 빛 세기를 더함. 텍스처 픽셀은 그대로",
        "  (움직임 설명 없이 든 세로 띠 그림 fuel_refinery_front_on 에는 칸을 넘기는 .png.mcmeta 를 더함).",
        "  원작자가 이 팩을 만들거나 승인한 것이 아니다.",
        "  덧붙임: assets/minecraft/textures/environment/celestial/moon/new_moon.png 는 바닐라 그림 자리에 둔 완전히 투명한 32 x 32 그림이다(새 달의 흐린 테두리를 지움).",
    ]


def phrases():
    """HUD 글귀(data/phrases/trip.json 으로 모임). make_hud 를 다시 돌려야 HUD 가 그린다(그 전에는 Java 가 hasPhrase 로 건너뜀)."""
    return {
        "trip_there": "저기다",
        "trip_hold": "지켜라",
        "trip_board": "우주선에 올라라",
        "trip_boarded": "탔다",
        "trip_gone": "떠났다",
        "trip_left_behind": "남았다",
        "trip_all_aboard": "모두 탔다",
        "trip_lamp_out": "등이 꺼졌다",
        "trip_ignite": "점화",
    }


def preview(out: Path, layer: Path = None):
    """미리보기: 로켓 상태 넷, 발사대, 기계 셋."""
    from PIL import Image
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    layer = Path(layer) if layer else ROOT / "build/trip/pack_layer"
    keys = ["lc_rocket_base", "lc_rocket_hull", "lc_rocket_nose", "lc_rocket_lit", "lc_pad_base", "lc_pad_tower", "lc_fuel_on", "lc_gen", "lc_station"]
    rows = []
    for k in keys:
        m = json.loads((layer / f"assets/{NS}/models/item/trip/{k}.json").read_text(encoding="utf-8"))
        texs = {}
        for var, tid in m["textures"].items():
            ns, _, p = tid.partition(":")
            png = layer / f"assets/{ns}/textures/{p}.png"
            im = Image.open(png).convert("RGBA")
            if im.height > im.width:  # 움직이는 그림: 첫 칸만
                im = im.crop((0, 0, im.width, im.width))
            texs["#" + var] = V.tex_array(im)
        rows.append(V.render_views(m["elements"], texs, views=("front", "side", "iso"), cell=200, label=k))
    sheet = Image.new("RGBA", (max(r.width for r in rows) * 2, sum(r.height for r in rows[::2]) + 4), (40, 42, 50, 255))
    y = 0
    for i in range(0, len(rows), 2):
        sheet.paste(rows[i], (0, y))
        if i + 1 < len(rows):
            sheet.paste(rows[i + 1], (rows[i].width, y))
        y += rows[i].height
    sheet.save(out)
    return out
