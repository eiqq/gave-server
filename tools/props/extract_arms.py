"""받은 모드 jar 에서 무기 모델 표(tools/props/arms_table.py)의 부품 모델과 그 텍스처만 ref/mods/<이름>/extracted/ 로 꺼내고 _info.json 에 적는다.
압축만 읽고 안의 코드는 실행하지 않는다(받은 파일은 데이터). 모델은 부모 사슬과 텍스처까지 따라가서 같이 꺼낸다.

python -I -X utf8 tools/props/extract_arms.py <jar 를 받은 폴더(이름마다 하위 폴더, 그 안에 jar 와 _info.json)>
받는 도구: Modrinth 판 파일을 받아 sha512 를 맞춰 보고 _info.json 을 쓰는 짧은 스크립트(좀비런 tools/props/fetch_modrinth.py 와 같은 일).
이미 있는 extracted 파일은 덮어쓴다(같은 판이면 같은 바이트). 꺼낸 뒤 jar 는 지운다(디스크 부족, 다시 필요하면 file_url 에서 받고 sha512 로 맞춤).
2026-11-12 wpn12 는 새 무기 12종 부품을 손으로 적었고, wpn_max 부터는 무기 모델 표에서 부품을 모은다(표에 줄을 더하면 다시 돌릴 것).
"""
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REF = ROOT / "ref/mods"

# 이름(ref/mods 폴더) → 꺼낼 모델 id(부모, 텍스처는 따라감)와 그 밖의 파일. 무기 모델 표의 부품과 탄창 부품을 출처별로 모은다
sys.path.insert(0, str(Path(__file__).resolve().parent))
import arms_table  # noqa: E402

SRC_DIR = {"tac": "timeless-and-classics-guns", "jeg": "just-enough-guns", "sg2": "scorched-guns-2"}
WANT = {d: {"models": [], "files": ["META-INF/mods.toml"]} for d in SRC_DIR.values()}
WANT["timeless-and-classics-guns"]["files"] = []
for _r in arms_table.ROWS:
    if not _r.get("build", True):
        continue
    for _m in _r["parts"] + ([_r["mag"]] if _r.get("mag") else []):
        _lst = WANT[SRC_DIR[_r["src"]]]["models"]
        if _m not in _lst:
            _lst.append(_m)
# TaC 날아가는 로켓(build_arms.build_rocket)과 TAC GEAR 나무 상자는 표 밖(이미 꺼내 둠)


def model_path(mid):
    ns, _, p = mid.partition(":")
    return f"assets/{ns}/models/{p}.json"


def tex_path(tid):
    ns, _, p = tid.partition(":") if ":" in tid else ("minecraft", "", tid)
    return f"assets/{ns}/textures/{p}.png"


def main():
    src = Path(sys.argv[1])
    for name, want in WANT.items():
        d = src / name
        jars = sorted(d.glob("*.jar"))
        if not jars:
            print(f"{name}: jar 없음, 건너뜀")
            continue
        z = zipfile.ZipFile(jars[0])
        names = set(z.namelist())
        out = REF / name / "extracted"
        got = []

        def take(path):
            if path in got:
                return
            if path not in names:
                raise FileNotFoundError(f"{name}: {path}")
            f = out / path
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(z.read(path))
            got.append(path)

        for mid in want["models"]:
            cur = mid
            texs = {}
            chain = []
            while cur and not cur.startswith(("minecraft:", "item/", "block/", "builtin/")):
                if ":" not in cur:
                    cur = mid.partition(":")[0] + ":" + cur
                take(model_path(cur))
                j = json.loads(z.read(model_path(cur)).decode("utf-8-sig"))
                chain.append(j)
                cur = j.get("parent")
            for j in reversed(chain):
                texs.update(j.get("textures", {}))
            for k, v in texs.items():
                for _ in range(10):
                    if isinstance(v, str) and v.startswith("#"):
                        v = texs.get(v[1:], v)
                    else:
                        break
                if not isinstance(v, str) or v.startswith("#"):
                    continue
                if ":" not in v:
                    v = "minecraft:" + v
                if v.startswith("minecraft:"):
                    continue
                take(tex_path(v))
                if tex_path(v) + ".mcmeta" in names:
                    take(tex_path(v) + ".mcmeta")
        for f in want["files"]:
            take(f)
        info_p = REF / name / "_info.json"
        new = json.loads((d / "_info.json").read_text(encoding="utf-8"))
        info = json.loads(info_p.read_text(encoding="utf-8")) if info_p.exists() else {}
        if info and info.get("sha512") not in (None, new.get("sha512")):
            raise SystemExit(f"{name}: 이미 있는 _info.json 의 판(sha512)과 받은 jar 가 다름")
        for k, v in new.items():
            info.setdefault(k, v)
        old = list(info.get("extracted", []))
        info["extracted"] = sorted(set(old) | set(got))
        info["used"] = True
        info["re_extracted_wpn_max"] = (f"2026-11-12 wpn_max: file_url 에서 다시 받아(sha512 일치) 무기 모델 표의 부품 모델 {len(want['models'])}개와 "
                                        f"그 텍스처를 꺼내고 jar 는 지움")
        info_p.parent.mkdir(parents=True, exist_ok=True)
        info_p.write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{name}: {len(got)} 파일 꺼냄 → {out}")


if __name__ == "__main__":
    main()
