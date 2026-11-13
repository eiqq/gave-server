"""발사장 투광등 모델(trip_special_b): tools/make_trip.py 가 bake(layer) 를 부른다.

- 모드: TrafficCraft 1.21.1-beta-1.2.0+4 (MrJulsen, GPL-3.0-or-later). 쌍둥이 가로등 머리(double_street_lamp / lit_double_street_lamp)와
  표지판 기둥(traffic_sign_post)을 26.3 아이템 모델로 옮겨 높은 투광등 기둥 하나로 쌓는다(손으로 블록을 짜지 않음: 모드 요소 그대로, 크기와 자리만).
- 꺼낸 파일: ref/mods/trafficcraft-padlamp/extracted(이 도구 몫, 길 담당의 ref/mods/trafficcraft 와 따로), 근거 _info.json.
  jar 는 eternalmc/ref/mods/trafficcraft 의 것을 읽기만 한다(데이터로만 다룸, 실행 안 함).
- 아이템: gave:trip/pad_lamp(불 켜짐: 불빛 면 light_emission 15), gave:trip/pad_lamp_off(꺼짐: 모드의 lamp_light_off 그림).
  월드 소품 꼴(모델 (8,8,8) = 엔티티 자리, 바닥 = 엔티티 y - 0.5, fixed 표시 배율로 되돌림). 높이 약 11.3 블록.
- 텍스처: 모드의 anvil.png 는 바닐라 모루 그림과 같은 Mojang 그림이라 쓰지 않고 같은 모드의 asphalt.png 로 바꿔 끼운다(길 담당과 같은 규칙).
"""
import hashlib
import json
import math
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
REF = ROOT / "ref/mods/trafficcraft-padlamp"
SRC = REF / "extracted"
JAR = Path("C:/Users/PC/Desktop/클로드/eternalmc/ref/mods/trafficcraft/trafficcraft-neoforge-1.21.1-beta-1.2.0+4.jar")
NS = "gave"
TC = "assets/trafficcraft"
MODELS = ["double_street_lamp", "lit_double_street_lamp", "traffic_sign_post"]
TEXTURES = ["sign", "asphalt", "lamp_light", "lamp_light_off"]
TEX_SUB = {"trafficcraft:block/anvil": "trafficcraft:block/asphalt"}
# 기둥 높이(px), 머리 배율, 줄이는 배율(26.3 요소 범위 -16..32 에 들게: fixed 표시 배율로 되돌림)
POLE_PX = 160
HEAD_K = 2.0
FACTOR = 8.0


def needed():
    return [f"{TC}/models/block/{m}.json" for m in MODELS] + [f"{TC}/textures/block/{t}.png" for t in TEXTURES] + ["META-INF/neoforge.mods.toml"]


def ensure_extracted():
    names = needed()
    if all((SRC / n).is_file() for n in names) and (REF / "_info.json").is_file():
        return
    if not JAR.is_file():
        raise SystemExit(f"TrafficCraft jar 없음: {JAR}")
    with zipfile.ZipFile(JAR) as z:
        for n in names:
            dst = SRC / n
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(z.read(n))
    toml = (SRC / "META-INF/neoforge.mods.toml").read_text(encoding="utf-8", errors="replace")
    lic = next((l.strip() for l in toml.splitlines() if l.strip().startswith("license")), "")
    info = {
        "slug": "trafficcraft",
        "title": "TrafficCraft (MrJulsen) - 발사장 투광등 몫",
        "url": "https://modrinth.com/mod/trafficcraft",
        "source": "https://github.com/MisterJulsen/TrafficCraft",
        "version": "1.21.1-beta-1.2.0+4 (NeoForge 1.21.1)",
        "license": "GPL-3.0-or-later",
        "license_evidence": {
            "modrinth_api": "https://api.modrinth.com/v2/project/trafficcraft 의 license.id = GPL-3.0-or-later (길 담당이 2026-11-12 확인, ref/mods/trafficcraft/_info.json)",
            "jar_mods_toml": lic,
            "authors": "MrJulsen",
        },
        "note": "jar 는 eternalmc/ref/mods/trafficcraft 의 것을 읽기만 함. 쌍둥이 가로등 머리(켜짐, 꺼짐)와 표지판 기둥, 그 그림 넷만 꺼냄. "
                "anvil.png 는 바닐라 모루 그림과 같은 Mojang 에셋이라 꺼내지 않고 같은 모드의 asphalt.png 로 바꿔 끼움. GPL-3.0 전문은 팩 맨 위 LICENSE-GPL-3.0.txt.",
        "jar_file": JAR.name,
        "jar_path_when_extracted": str(JAR),
        "sha512": hashlib.sha512(JAR.read_bytes()).hexdigest(),
        "extracted": names,
    }
    (REF / "_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def _tex_id(v, texs):
    d = 0
    while isinstance(v, str) and v.startswith("#") and d < 10:
        v = texs.get(v[1:], v)
        d += 1
    if ":" not in v:
        v = "minecraft:" + v
    return TEX_SUB.get(v, v)


def _default_uv(face, lo, hi):
    if face in ("north", "south"):
        return [lo[0], 16 - hi[1], hi[0], 16 - lo[1]]
    if face in ("east", "west"):
        return [lo[2], 16 - hi[1], hi[2], 16 - lo[1]]
    return [lo[0], lo[2], hi[0], hi[2]]


def load(name):
    m = json.loads((SRC / f"{TC}/models/block/{name}.json").read_text(encoding="utf-8-sig"))
    texs = m.get("textures", {})
    out = []
    for e in m["elements"]:
        ne = {"from": [float(x) for x in e["from"]], "to": [float(x) for x in e["to"]], "faces": {}}
        r = e.get("rotation")
        if r and r.get("angle"):
            ne["rotation"] = {"angle": r["angle"], "axis": r["axis"], "origin": [float(x) for x in r["origin"]]}
        for fn, f in e["faces"].items():
            ne["faces"][fn] = {"uv": f["uv"] if "uv" in f else _default_uv(fn, ne["from"], ne["to"]), "texture": _tex_id(f["texture"], texs)}
            if "rotation" in f:
                ne["faces"][fn]["rotation"] = f["rotation"]
        out.append(ne)
    return out


def _corners(e):
    f, t = e["from"], e["to"]
    pts = [[x, y, z] for x in (f[0], t[0]) for y in (f[1], t[1]) for z in (f[2], t[2])]
    r = e.get("rotation")
    if not r:
        return pts
    a = math.radians(r["angle"])
    o = r["origin"]
    c, s = math.cos(a), math.sin(a)
    out = []
    for x, y, z in pts:
        x, y, z = x - o[0], y - o[1], z - o[2]
        if r["axis"] == "x":
            y, z = y * c - z * s, y * s + z * c
        elif r["axis"] == "y":
            x, z = x * c + z * s, -x * s + z * c
        else:
            x, y = x * c - y * s, x * s + y * c
        out.append([x + o[0], y + o[1], z + o[2]])
    return out


def bounds(els):
    pts = [p for e in els for p in _corners(e)]
    return [min(p[i] for p in pts) for i in range(3)], [max(p[i] for p in pts) for i in range(3)]


def moved(els, k=1.0, off=(0, 0, 0), about=(8, 0, 8)):
    """요소를 about 둘레로 k 배 하고 off 만큼 옮긴 사본."""
    out = []
    for e in els:
        ne = json.loads(json.dumps(e))
        for key in ("from", "to"):
            ne[key] = [(e[key][i] - about[i]) * k + about[i] + off[i] for i in range(3)]
        if "rotation" in ne:
            ne["rotation"]["origin"] = [(e["rotation"]["origin"][i] - about[i]) * k + about[i] + off[i] for i in range(3)]
        out.append(ne)
    return out


def pole(y0, y1, k=2.0):
    """표지판 기둥(traffic_sign_post 의 요소)을 굵기 k 배로 이어 쌓음."""
    e = load("traffic_sign_post")[0]
    out = []
    y = y0
    while y < y1:
        h = min(16, y1 - y)
        ne = json.loads(json.dumps(e))
        ne["from"][1], ne["to"][1] = y, y + h
        for fn, f in ne["faces"].items():
            if fn not in ("up", "down"):
                f["uv"] = [f["uv"][0], 0, f["uv"][2], h]
        out.append(ne)
        y += h
    return moved(out, 1.0) if k == 1.0 else [dict(x, **{"from": [8 - (8 - x["from"][0]) * k, x["from"][1], 8 - (8 - x["from"][2]) * k],
                                                     "to": [8 + (x["to"][0] - 8) * k, x["to"][1], 8 + (x["to"][2] - 8) * k]}) for x in out]


def lamp_elements(lit):
    head = load("lit_double_street_lamp" if lit else "double_street_lamp")
    if lit:
        for e in head:
            if any(f["texture"].endswith("lamp_light") for f in e["faces"].values()):
                e["light_emission"] = 15
    # 머리를 HEAD_K 배로 키워 기둥 꼭대기에(머리 받침 요소의 밑이 y 0 이라 그대로 올림)
    return pole(0, POLE_PX) + moved(head, HEAD_K, (0, POLE_PX, 0))


def write_model(layer, key, els, texmap):
    lo, hi = bounds(els)
    k = 1.0 / FACTOR
    off = [8 - k * (lo[0] + hi[0]) / 2, (8 - 8 / FACTOR) - k * lo[1], 8 - k * (lo[2] + hi[2]) / 2]
    els = moved(els, k, off, about=(0, 0, 0))
    vars_, out_els = {}, []
    for e in els:
        ne = {"from": [round(x, 4) for x in e["from"]], "to": [round(x, 4) for x in e["to"]], "faces": {}}
        for k2 in ("from", "to"):
            if not all(-16 <= x <= 32 for x in ne[k2]):
                raise ValueError(f"{key}: 요소 {k2} {ne[k2]} 가 26.3 범위(-16..32) 밖")
        if "rotation" in e:
            ne["rotation"] = {"angle": e["rotation"]["angle"], "axis": e["rotation"]["axis"], "origin": [round(x, 4) for x in e["rotation"]["origin"]]}
        if "light_emission" in e:
            ne["light_emission"] = e["light_emission"]
        for fn, f in e["faces"].items():
            tid = texmap[f["texture"]]
            var = vars_.setdefault(tid, f"t{len(vars_)}")
            nf = {"uv": f["uv"], "texture": "#" + var}
            if "rotation" in f:
                nf["rotation"] = f["rotation"]
            ne["faces"][fn] = nf
        out_els.append(ne)
    texs = {v: kk for kk, v in vars_.items()}
    texs["particle"] = next(iter(vars_))
    m = {"credit": "TrafficCraft (MrJulsen, GPL-3.0-or-later), double street lamp head on stacked sign posts, moved to a 26.3 item model",
         "textures": texs, "elements": out_els,
         "display": {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [FACTOR] * 3}}}
    p = layer / f"assets/{NS}/models/item/trip/{key}.json"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(m, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    it = layer / f"assets/{NS}/items/trip/{key}.json"
    it.parent.mkdir(parents=True, exist_ok=True)
    it.write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"{NS}:item/trip/{key}"}}, ensure_ascii=False, indent=1), encoding="utf-8")
    lo2, hi2 = bounds(out_els)
    return [round((hi2[i] - lo2[i]) / 16 * FACTOR, 2) for i in range(3)]


def bake(layer: Path):
    ensure_extracted()
    texmap = {}
    for t in TEXTURES:
        src = SRC / f"{TC}/textures/block/{t}.png"
        name = "pl_" + t
        dst = layer / f"assets/{NS}/textures/item/trip/{name}.png"
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(src.read_bytes())
        texmap[f"trafficcraft:block/{t}"] = f"{NS}:item/trip/{name}"
    size_on = write_model(layer, "pad_lamp", lamp_elements(True), texmap)
    size_off = write_model(layer, "pad_lamp_off", lamp_elements(False), texmap)
    return {"pad_lamp": size_on, "pad_lamp_off": size_off}


def credits():
    return [
        "[원정 9] 발사장 투광등: TrafficCraft 1.21.1-beta-1.2.0+4 (NeoForge 1.21.1), MrJulsen",
        "  https://modrinth.com/mod/trafficcraft , https://github.com/MisterJulsen/TrafficCraft",
        "  라이선스: GPL-3.0-or-later. 전문 LICENSE-GPL-3.0.txt(팩 맨 위)",
        "  쓴 것: 쌍둥이 가로등 머리(켜짐, 꺼짐), 표지판 기둥, 그림 sign, asphalt, lamp_light, lamp_light_off",
        "  (assets/gave/models/item/trip/pad_lamp*, assets/gave/textures/item/trip/pl_*)",
        "  바꾼 것(2026-11-12): 블록 모델을 26.3 아이템 모델로 옮김, 기둥을 열 토막 이어 쌓고 두 배로 굵게, 머리를 두 배로 키워 꼭대기에 올림,",
        "  여덟 배로 줄여 넣고 표시 배율로 되돌림, 켜진 불빛 면에 빛 내기, 틴트 번호를 뗌, 모루 그림 면은 같은 모드의 asphalt.png 로.",
        "  고친 것도 GPL-3.0-or-later 로 나눈다. 원작자가 이 팩을 만들거나 승인한 것이 아니다.",
    ]
