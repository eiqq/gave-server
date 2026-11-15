"""릴리스 팩에 pack_src 의 바뀐 파일만 갈아 끼운 시험 팩을 만든다(전체 빌드 tools/build_all.py 를 못 돌리는 PC 용: ref/ 원본 없음).

python -X utf8 tools/patch_pack.py --out build/<영역>/pack [--src build] [--all | 경로 ...]

- <src>/Gave-pack.zip(모습), <src>/Gave-sound.zip(소리)을 읽어 <out>/ 에 같은 이름으로 새로 쓴다(원본 zip 은 그대로).
- 경로: pack_src 기준 파일이나 폴더(예: assets/gave/font/shop.json assets/gave/textures/font/shop). --all 이면 pack_src 의 모든 파일.
  아무것도 안 주면 원본을 그대로 복사한다.
- tools/build_pack.py 와 같은 규칙: pack_src 가 맨 위 층이라 같은 경로는 pack_src 것이 이긴다. 단 lang/*.json 과 sounds.json 은 키를 합친다
  (pack_src 키가 이김). 소리 경로(sounds.json, sounds/ 아래, ogg)는 소리 팩으로, 나머지는 모습 팩으로. pack.mcmeta 는 건드리지 않는다.
- zip 시각을 고정해 같은 내용이면 같은 sha1(클라 캐시가 그대로 쓰임).
시험: GV_PACK_DIR=<out 절대 경로> GV_SLOT=<n> bash tools/test_server.sh start (켜진 서버는 /gave 팩 으로 다시 읽음).
"""
import argparse
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK_SRC = ROOT / "pack_src"
# tools/build_pack.py 의 SOUND_PATH, MERGE 와 같게
SOUND_PATH = re.compile(r"assets/[^/]+/(sounds\.json|sounds/.+)|.+\.ogg")
MERGE = re.compile(r"assets/[a-z0-9_.-]+/(sounds\.json|lang/[a-z_]+\.json)")
NAMES = ("Gave-pack.zip", "Gave-sound.zip")


def read_zip(path):
    with zipfile.ZipFile(path) as z:
        return {n: z.read(n) for n in z.namelist() if not n.endswith("/")}


def write_zip(path, files):
    tmp = path.with_suffix(".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(files):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, files[name])
    tmp.replace(path)


def picked(paths, everything):
    if everything:
        return sorted(f for f in PACK_SRC.rglob("*") if f.is_file())
    out = []
    for p in paths:
        f = PACK_SRC / p
        if f.is_dir():
            out += sorted(x for x in f.rglob("*") if x.is_file())
        elif f.is_file():
            out.append(f)
        else:
            sys.exit(f"pack_src 에 없음: {p}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="새 팩을 둘 폴더(GV_PACK_DIR 로 줄 폴더)")
    ap.add_argument("--src", default="build", help="릴리스 팩이 있는 폴더(기본 build)")
    ap.add_argument("--all", action="store_true", help="pack_src 의 모든 파일을 얹음")
    ap.add_argument("paths", nargs="*", help="pack_src 기준 파일이나 폴더")
    a = ap.parse_args()
    src, out = (ROOT / a.src).resolve(), (ROOT / a.out).resolve()
    if src == out:
        sys.exit("--out 이 원본 폴더와 같음(원본 zip 은 그대로 둔다)")
    if not (src / NAMES[0]).is_file():
        sys.exit(f"{src / NAMES[0]} 없음")
    out.mkdir(parents=True, exist_ok=True)
    packs = [read_zip(src / n) if (src / n).is_file() else None for n in NAMES]
    changed = [0, 0]
    for f in picked(a.paths, a.all):
        rel = f.relative_to(PACK_SRC).as_posix()
        if rel == "pack.mcmeta":
            continue
        i = 1 if SOUND_PATH.fullmatch(rel) and packs[1] is not None else 0
        data = f.read_bytes()
        old = packs[i].get(rel)
        if old is not None and MERGE.fullmatch(rel):
            merged = json.loads(old.decode("utf-8"))
            merged.update(json.loads(data.decode("utf-8")))
            data = json.dumps(merged, ensure_ascii=False, indent=1).encode("utf-8")
        if old != data:
            packs[i][rel] = data
            changed[i] += 1
    bad = [n for p in packs if p for n in p if re.search(r"_[nse]\.png$", n)]
    if bad:
        sys.exit("텍스처 이름이 _n, _s, _e 로 끝남(Iris 에서 깨짐): " + ", ".join(bad[:5]))
    for name, files, n in zip(NAMES, packs, changed):
        if files is None:
            continue
        if n:
            write_zip(out / name, files)
        else:
            shutil.copy2(src / name, out / name)
        print(f"{out / name}: 파일 {len(files)}개, 바꾼 것 {n}개")


if __name__ == "__main__":
    main()
