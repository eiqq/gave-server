"""원정 특수 라운드 묶음 8(trip_special_a)의 팩 모델: 추격전 트레일러(make_trip.py 가 bake(layer) 를 부름).

- 트레일러: MrCrayfish's Vehicle Mod(MrCrayfish, GPL-3.0: 원본 저장소 src/main/resources/META-INF/mods.toml license="GNU General Public License 3.0",
  MOD-LICENSE.txt 첫 줄 'GNU GENERAL PUBLIC LICENSE Version 3') 의 assets/vehicle/models/vehicle/trailer_body.json.
  받은 곳: raw.githubusercontent.com/MrCrayfish/MrCrayfishVehicleMod/1.16.X-dev (ref/mods/mrcrayfish-vehicle/_info.json). 압축을 받지 않고 쓸 파일만 낱개로.
- 모델의 텍스처는 바닐라 블록 그림 이름(minecraft:block/white_concrete, anvil, light_gray_concrete)을 가리킬 뿐이라 그림 파일을 옮기지 않는다
  (팩에 바닐라 그림을 넣지 않음: 클라가 제 것을 씀). 요소는 그대로(26.3 범위 -16..32 안: x -8..24, y -0.5..8, z -10..32), 면 texture 는 #변수.
- 아이템 gave:trip/trailer. 놓는 쪽(kr.gave.trip.special.Chase)이 판 크기에 맞춰 축마다 늘린다(바닥 판 24 x 32 px, 바닥 윗면 y 2 px, 연결 고리는 +Z 쪽).
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REF = ROOT / "ref/mods/mrcrayfish-vehicle"
SRC = REF / "extracted/assets/vehicle/models/vehicle/trailer_body.json"
NS = "gave"
VANILLA = {"minecraft:block/white_concrete", "minecraft:block/anvil", "minecraft:block/light_gray_concrete"}


def _model():
    m = json.loads(SRC.read_text(encoding="utf-8-sig"))
    texs = dict(m.get("textures", {}))
    for k, v in texs.items():
        if v not in VANILLA:
            raise ValueError(f"트레일러 텍스처 {k}={v} 가 바닐라 블록 그림이 아님(옮길 그림 권리를 따로 볼 것)")
    els = []
    for e in m["elements"]:
        for key in ("from", "to"):
            if not all(-16 <= float(x) <= 32 for x in e[key]):
                raise ValueError(f"트레일러 요소 {key} {e[key]} 가 26.3 범위 밖")
        ne = {"from": [round(float(x), 4) for x in e["from"]], "to": [round(float(x), 4) for x in e["to"]], "faces": {}}
        r = e.get("rotation")
        if r and r.get("angle"):
            ne["rotation"] = {"angle": r["angle"], "axis": r["axis"], "origin": [round(float(x), 4) for x in r["origin"]]}
        for fn, f in e["faces"].items():
            t = f["texture"]
            if not t.startswith("#") or t[1:] not in texs:
                continue
            nf = {"texture": t}
            if "uv" in f:
                nf["uv"] = f["uv"]
            if "rotation" in f:
                nf["rotation"] = f["rotation"]
            ne["faces"][fn] = nf
        if ne["faces"]:
            els.append(ne)
    texs["particle"] = texs.get("frame", next(iter(texs.values())))
    return {"credit": "MrCrayfish's Vehicle Mod (MrCrayfish, GPL-3.0), trailer_body moved to a 26.3 item model", "textures": texs, "elements": els,
            "display": {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}}


def bake(layer: Path):
    if not SRC.is_file():
        return {"trailer": "건너뜀(ref/mods/mrcrayfish-vehicle 없음)"}
    m = _model()
    p = layer / f"assets/{NS}/models/item/trip/trailer.json"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(m, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    it = layer / f"assets/{NS}/items/trip/trailer.json"
    it.parent.mkdir(parents=True, exist_ok=True)
    it.write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"{NS}:item/trip/trailer"}}, ensure_ascii=False, indent=1), encoding="utf-8")
    return {"trailer": len(m["elements"])}


def credits():
    return [
        "[원정 특수] 추격전 트레일러",
        "MrCrayfish's Vehicle Mod by MrCrayfish, GNU General Public License 3.0 (full text: LICENSE-GPL-3.0.txt).",
        "Source: https://github.com/MrCrayfish/MrCrayfishVehicleMod (branch 1.16.X-dev), assets/vehicle/models/vehicle/trailer_body.json.",
        "Changed: copied the elements into gave:item/trip/trailer for Minecraft 26.3; textures are vanilla block names, no image copied.",
    ]


LICENSES = {"LICENSE-GPL-3.0.txt": ROOT / "tools/props/licenses/LICENSE-GPL-3.0.txt"}
