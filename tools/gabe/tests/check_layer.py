"""build/gabe/pack_layer 검사: 모델 요소 좌표(-16..32), 면 텍스처 변수, 텍스처 파일, 틴트 칸 수, 아이템 정의 상태와 gabe_looks.json,
소리 파일, 자막, 가운뎃점, 반투명 화소, 상태 모델마다 면 수(한도 budget, 숨긴 자세 면도 셈), 상태마다 쓰는 칸(uses)과 꼴 번호(v).
문제가 있으면 종료 1.

python -X utf8 tools/gabe/tests/check_layer.py
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
L = ROOT / "build/gabe/pack_layer"
A = L / "assets/gave"
bad = []


def err(m):
    bad.append(m)


looks = json.loads((A / "gabe_looks.json").read_text(encoding="utf-8"))["models"]
for item in sorted((A / "items/gabe").glob("*.json")):
    name = "gabe/" + item.stem
    d = json.loads(item.read_text(encoding="utf-8"))
    lk = looks.get(name)
    if lk is None:
        err(f"{name}: gabe_looks.json 에 없음")
        continue
    m = d["model"]
    refs = [(c["when"], c["model"]) for c in m.get("cases", [])] + [("<fallback>", m["fallback"])] if m["type"] == "minecraft:select" else [("", m)]
    if m["type"] == "minecraft:select":
        whens = {c["when"] for c in m["cases"]}
        if whens | {lk["default"]} != set(lk["states"]):
            err(f"{name}: 상태 {sorted(whens)} + 기본 {lk['default']!r} 이 looks {lk['states']} 와 다름")
    if lk.get("v") != 2:
        err(f"{name}: looks 꼴 {lk.get('v')} (옛 키트로 구움, make_gabe --only 로 다시)")
    case_state = {c["when"]: c["when"] for c in m.get("cases", [])}
    budget = lk.get("budget", 400)
    quads = []
    for when, r in refs:
        state = case_state.get(when, lk["default"])
        if len(r["tints"]) != len(lk["slots"]):
            err(f"{name} {when}: tints {len(r['tints'])} != slots {len(lk['slots'])}")
        mp = A / "models" / (r["model"].split(":", 1)[1] + ".json")
        mj = json.loads(mp.read_text(encoding="utf-8"))
        tex = mj["textures"]
        for t in tex.values():
            if ":" not in t:
                err(f"{mp.name}: 이름공간 없는 텍스처 {t!r}(minecraft: 로 풀려 빠진 텍스처가 됨)")
                continue
            ns, path = t.split(":", 1)
            if not (L / f"assets/{ns}/textures/{path}.png").is_file():
                err(f"{mp.name}: 텍스처 파일 없음 {t}")
        nq = sum(len(e["faces"]) for e in mj["elements"])
        quads.append(f"{state or '-'} {nq}")
        if nq > budget:
            err(f"{mp.name}: 면 {nq} > 한도 {budget}")
        used = set(lk.get("uses", {}).get(state, range(len(lk["slots"]))))
        tis = {f.get("tintindex", 0) for e in mj["elements"] for f in e["faces"].values()}
        if tis - used:
            err(f"{mp.name}: uses[{state!r}] 에 없는 tintindex {sorted(tis - used)[:5]}")
        for e in mj["elements"]:
            for v in e["from"] + e["to"]:
                if not -16 <= v <= 32:
                    err(f"{mp.name}: 좌표 밖 {e['from']} {e['to']}")
            for fn, f in e["faces"].items():
                if f["texture"][1:] not in tex:
                    err(f"{mp.name}: 면 변수 {f['texture']} 없음")
                if not 0 <= f.get("tintindex", 0) < len(lk["slots"]):
                    err(f"{mp.name}: tintindex {f.get('tintindex')} 범위 밖")
    # 래그돌 사체 정보(rag): 사체 상태가 상태 목록에 있고, 흐물 막대기마다 누운 자세 수와 이음점 수가 맞는지
    for st, rg in lk.get("rag", {}).items():
        if st not in lk["states"] or len(rg.get("box", ())) != 6:
            err(f"{name}: rag[{st!r}] 상태나 상자가 이상함")
        fr = rg.get("front")
        if fr is not None and (len(fr) != 3 or abs(sum(v * v for v in fr) ** 0.5 - 1) > 0.01 and any(fr)):
            err(f"{name}: rag[{st!r}] 얼굴 방향 {fr} 이 단위 벡터도 0 벡터도 아님")
        cs = rg.get("corpse")
        if cs is None:
            continue
        if cs not in lk["states"]:
            err(f"{name}: rag[{st!r}] 사체 상태 {cs!r} 가 상태 목록에 없음")
        for r in rg.get("rods", ()):
            if len(r["lie"]) != len(rg.get("faces", ())) or any(len(v) != r["segs"] + 1 for v in r["lie"]):
                err(f"{name}: rag[{st!r}] 누운 자세 수나 이음점 수가 안 맞음")
    quads = [q for q in quads if "." not in q.split(" ")[0]] + [f"사체 상태 {sum(1 for q in quads if '.' in q.split(' ')[0])}"]
    print(f"{name}: 칸 {len(lk['slots'])}, 면(상태별, 한도 {budget}) " + ", ".join(quads))
for png in (A / "textures").rglob("*.png"):
    a = np.asarray(Image.open(png).convert("RGBA"))[..., 3]
    if ((a > 0) & (a < 255)).any():
        err(f"{png.relative_to(L)}: 반투명 화소")
snd = json.loads((A / "sounds.json").read_text(encoding="utf-8"))
lang = json.loads((A / "lang/ko_kr.json").read_text(encoding="utf-8"))
for ev, d in snd.items():
    if d.get("subtitle") not in lang:
        err(f"소리 {ev}: 자막 키 없음")
    for s in d["sounds"]:
        n = s["name"]
        if n.startswith("gave:") and not (A / f"sounds/{n[5:]}.ogg").is_file():
            err(f"소리 {ev}: 파일 없음 {n}")
for p in L.rglob("*.json"):
    if chr(0xB7) in p.read_text(encoding="utf-8"):
        err(f"{p.relative_to(L)}: 가운뎃점")
print(f"모델 {len(looks)}, 소리 {len(snd)}, 텍스처 {len(list((A / 'textures').rglob('*.png')))}: " + ("문제 없음" if not bad else f"문제 {len(bad)}"))
for b in bad[:30]:
    print(" ", b)
sys.exit(1 if bad else 0)
