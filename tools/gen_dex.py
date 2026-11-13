"""게임 안 도감(kr.gave.dex.Dex)이 읽는 종 글을 data/bestiary.json 에서 뽑아 플러그인 자원으로 쓴다.

python -X utf8 tools/gen_dex.py [--out <경로>]

- 기본 출력 plugin/src/main/resources/dex/species.json (jar 안 dex/species.json). --out 이면 그 경로(wip 작업용).
- 갈래(group): G01 ~ G55 는 모든 막 공통(kr.gave.trip.Deck.Tune.COMMON_MAX 와 같게), 보스(역할에 '보스')는 boss,
  나머지는 bestiary act(1 ~ 4) 막 전용. 차례는 bestiary 차례 그대로.
- 칸: id, name, group, act, role, oneliner, look, attack, tell, counter. 가운뎃점은 쉼표로 바꾼다(가운뎃점 금지).
"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "data/bestiary.json"
OUT = ROOT / "plugin/src/main/resources/dex/species.json"
COMMON_MAX = 55  # kr.gave.trip.Deck.Tune.COMMON_MAX(2026-11-12 사용자 결정: 기존 55종이 모든 막 공통)
FIELDS = ("role", "oneliner", "look", "attack", "tell", "counter")


def clean(s):
    s = str(s or "").replace("\u00b7", ", ")
    return re.sub(r"\s+", " ", s).strip()


def group(sp):
    sid = sp["id"]
    if sid.startswith("B") or "보스" in str(sp.get("role", "")):
        return "boss"
    n = int(sid[1:])
    if n <= COMMON_MAX:
        return "common"
    return str(int(sp.get("act") or 1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=OUT)
    a = ap.parse_args()
    data = json.loads(SRC.read_text(encoding="utf-8"))
    rows = []
    for sp in data["species"]:
        if not sp.get("id"):
            continue
        row = {"id": sp["id"], "name": clean(sp.get("name")), "group": group(sp), "act": int(sp.get("act") or 0)}
        for k in FIELDS:
            row[k] = clean(sp.get(k))
        rows.append(row)
    text = json.dumps({"species": rows}, ensure_ascii=False, indent=1) + "\n"
    if "\u00b7" in text:
        raise SystemExit("가운뎃점이 남음")
    out = a.out if a.out.is_absolute() else ROOT / a.out
    out.parent.mkdir(parents=True, exist_ok=True)
    old = out.read_text(encoding="utf-8") if out.is_file() else None
    if old != text:
        out.write_text(text, encoding="utf-8", newline="\n")
    counts = {}
    for r in rows:
        counts[r["group"]] = counts.get(r["group"], 0) + 1
    print(f"도감 종 {len(rows)} {'씀' if old != text else '같음'} {out.relative_to(ROOT) if out.is_relative_to(ROOT) else out} 갈래 {counts}")


if __name__ == "__main__":
    main()
