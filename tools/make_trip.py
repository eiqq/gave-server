"""원정 층 굽기: tools/trip/*.py 를 모두 돌려 build/trip/pack_layer(소품 층과 같은 꼴)를 만든다. tools/build_pack.py 가 이 층을 넣는다.

python -I -X utf8 tools/make_trip.py [--only truck,route] [--preview] [--no-data] [--data-force]

- 부품 모듈 약속(tools/trip/<부품>.py, 작업자마다 자기 파일): bake(layer: Path) -> dict(요약). 있으면 쓰는 것:
  credits() -> [줄...](CREDITS-TRIP.txt 의 한 절, 첫 줄은 '[원정 n] 제목'), LICENSES = {팩 맨 위 파일 이름: 원본 경로}(라이선스 전문, 바이트 그대로),
  phrases() -> {키: 글}(HUD 글귀: data/phrases/trip.json 으로 모음. 키가 겹치면 멈춤), preview(out: Path)(--preview 일 때 build/trip/preview/<부품>.png).
  bake 가 없는 파일(fetch_*.py 같은 도우미)은 건너뛴다.
- 받은 모드 파일(ref/mods)을 읽으므로 -I 로 돌린다. 받은 파일은 데이터로만 다룬다.
- 층 맨 위에 CREDITS-TRIP.txt 와 라이선스 전문을 쓴다(팩 맨 위로 들어감: 공개 배포되는 팩이라 MIT 고지가 꼭 따라가야 한다).
- 데이터: data/trip/*_draft.json 을 plugin/src/main/resources/trip/<이름>.json 으로 처음 한 번만 복사(그 자리에 파일이 없을 때. 부품 주인이
  굳힌 파일을 덮지 않게, 2026-11-12 trip_aug 요청). --data-force 면 다르면 덮음. augments 는 늘 건너뜀(증강 담당이 augments_final.json 으로 굳힘).
  --no-data 면 안 함. 플러그인 부품이 jar 에서 읽는다.
- 층은 통째로 새로 만든다(임시 폴더에 굽고 바꿔치기). 한 모듈이 실패하면 멈추고 있던 층은 그대로 둔다.
"""
import importlib.util
import json
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRIP = ROOT / "tools/trip"
OUT = ROOT / "build/trip"
LAYER = OUT / "pack_layer"
PHRASES = ROOT / "data/phrases/trip.json"
DATA_SRC = ROOT / "data/trip"
DATA_DST = ROOT / "plugin/src/main/resources/trip"
# 복사하지 않는 데이터(주인이 따로 굳힌 것): augments → resources/trip/augments_final.json(trip_aug)
DATA_KEEP = {"augments"}


def load(path: Path):
    spec = importlib.util.spec_from_file_location("trip_" + path.stem, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def check_layer(layer: Path):
    """층 검사: 가운뎃점, Iris 가 PBR 로 보는 텍스처 이름(_n, _s, _e), 모델이 가리키는 텍스처와 아이템 정의가 가리키는 모델이 층 안에 있는가, 요소 범위."""
    errs = []
    for f in sorted(layer.rglob("*")):
        if not f.is_file():
            continue
        rel = f.relative_to(layer).as_posix()
        if f.suffix == ".png" and f.stem.endswith(("_n", "_s", "_e")):
            errs.append(f"{rel}: 텍스처 이름이 _n, _s, _e 로 끝남")
        if f.suffix in (".json", ".txt", ".mcmeta"):
            if "·" in f.read_text(encoding="utf-8"):
                errs.append(f"{rel}: 가운뎃점")
    def tex_file(tid):
        ns, _, p = tid.partition(":")
        return layer / f"assets/{ns}/textures/{p}.png"
    def model_file(mid):
        ns, _, p = mid.partition(":")
        return layer / f"assets/{ns}/models/{p}.json"
    for f in sorted(layer.glob("assets/*/models/**/*.json")):
        m = json.loads(f.read_text(encoding="utf-8"))
        rel = f.relative_to(layer).as_posix()
        for k, v in m.get("textures", {}).items():
            if not v.startswith("#") and v.startswith("gave:") and not tex_file(v).is_file():
                errs.append(f"{rel}: 텍스처 {v} 가 층에 없음")
        for i, e in enumerate(m.get("elements", [])):
            for k in ("from", "to"):
                if not all(-16 <= x <= 32 for x in e[k]):
                    errs.append(f"{rel}: 요소 {i} {k} {e[k]} 범위 밖")
            for fn, fc in e.get("faces", {}).items():
                if not str(fc.get("texture", "")).startswith("#"):
                    errs.append(f"{rel}: 요소 {i} {fn} 면 texture 가 #변수가 아님")
    def models_of(node):
        if isinstance(node, dict):
            if node.get("type") == "minecraft:model" and isinstance(node.get("model"), str):
                yield node["model"]
            for v in node.values():
                yield from models_of(v)
        elif isinstance(node, list):
            for v in node:
                yield from models_of(v)
    for f in sorted(layer.glob("assets/*/items/**/*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        for mid in models_of(d):
            if mid.startswith("gave:") and not model_file(mid).is_file():
                errs.append(f"{f.relative_to(layer).as_posix()}: 모델 {mid} 가 층에 없음")
    return errs


def main():
    args = sys.argv[1:]
    only = None
    if "--only" in args:
        only = set(args[args.index("--only") + 1].split(","))
    want_preview = "--preview" in args
    t0 = time.monotonic()
    tmp = OUT / "pack_layer.tmp"
    if tmp.exists():
        shutil.rmtree(tmp)
    tmp.mkdir(parents=True)
    credits, licenses, phrases, summary = [], {}, {}, {}
    mods = []
    for path in sorted(TRIP.glob("*.py")):
        if path.name.startswith(("_", "fetch_")):
            continue
        mod = load(path)
        if not hasattr(mod, "bake"):
            continue
        mods.append((path.stem, mod))
    for name, mod in mods:
        # --only 여도 층은 통째로 새로 만들므로 모든 모듈을 굽는다(--only 는 미리보기와 요약만 그 모듈로 줄임)
        try:
            res = mod.bake(tmp)
        except Exception as e:  # noqa: BLE001
            shutil.rmtree(tmp, ignore_errors=True)
            print(f"{name}: 굽기 실패: {type(e).__name__}: {e}", file=sys.stderr)
            raise
        summary[name] = res
        if hasattr(mod, "credits"):
            credits.append(mod.credits())
        for fname, src in getattr(mod, "LICENSES", {}).items():
            licenses[fname] = Path(src)
        if hasattr(mod, "phrases"):
            for k, v in mod.phrases().items():
                if k in phrases:
                    sys.exit(f"글귀 키 {k} 가 겹침({name})")
                if "·" in v:
                    sys.exit(f"글귀 {k} 에 가운뎃점")
                phrases[k] = v
        if only is None or name in only:
            small = {k: v for k, v in (res or {}).items() if not isinstance(v, (dict, list))}
            print(f"{name}: {small}")
    # 맨 위 파일: 저작자 표시와 라이선스 전문
    lines = ["게이브 리소스팩 원정 층 출처와 라이선스(CREDITS-TRIP.txt)",
             "tools/make_trip.py 가 만드는 파일이라 손으로 고치지 말 것. 팩 전체의 출처는 CREDITS.txt.",
             "아래 모드 에셋은 원본 그대로가 아니다(절마다 바꾼 것을 적음). 바꾼 것도 원본과 같은 조건으로 나눈다.", ""]
    for sec in credits:
        lines += list(sec) + [""]
    (tmp / "CREDITS-TRIP.txt").write_text("\n".join(lines), encoding="utf-8")
    for fname, src in licenses.items():
        data = src.read_bytes()
        if not data.strip():
            sys.exit(f"라이선스 전문 {src} 가 비었다")
        (tmp / fname).write_bytes(data)
    errs = check_layer(tmp)
    if errs:
        shutil.rmtree(tmp, ignore_errors=True)
        print("층 검사 실패:\n  " + "\n  ".join(errs), file=sys.stderr)
        sys.exit(1)
    if LAYER.exists():
        shutil.rmtree(LAYER)
    tmp.rename(LAYER)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    # 글귀
    old = PHRASES.read_text(encoding="utf-8") if PHRASES.is_file() else None
    new = json.dumps(dict(sorted(phrases.items())), ensure_ascii=False, indent=1) + "\n"
    if phrases and new != old:
        PHRASES.write_text(new, encoding="utf-8")
        print(f"글귀 {len(phrases)}개 → {PHRASES.relative_to(ROOT).as_posix()} (HUD 에 보이려면 make_hud.py 와 hud promote)")
    # 데이터 복사
    if "--no-data" not in args and DATA_SRC.is_dir():
        n = 0
        force = "--data-force" in args
        for src in sorted(DATA_SRC.glob("*.json")):
            name = src.stem.replace("_draft", "")
            if name in DATA_KEEP:
                continue
            dst = DATA_DST / (name + ".json")
            data = src.read_bytes()
            if not dst.is_file() or force and dst.read_bytes() != data:
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(data)
                n += 1
        print(f"데이터 {len(list(DATA_SRC.glob('*.json')))}개 가운데 바뀐 {n}개 → {DATA_DST.relative_to(ROOT).as_posix()}")
    if want_preview:
        for name, mod in mods:
            if (only is None or name in only) and hasattr(mod, "preview"):
                p = mod.preview(OUT / "preview" / f"{name}.png")
                print(f"미리보기 {p}")
    files = [f for f in LAYER.rglob("*") if f.is_file()]
    print(f"원정 층: {LAYER} 파일 {len(files)}개, {sum(f.stat().st_size for f in files)} 바이트, {time.monotonic() - t0:.1f}초")


if __name__ == "__main__":
    main()
