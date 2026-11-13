"""The Car Engine(MIT, github.com/MCTCRS/thecarengine)에서 차 모델과 텍스처, 라이선스 전문만 받아 ref/mods/the-car-engine/extracted/ 에 둔다.

python -I -X utf8 tools/trip/fetch_tce.py [--check]

- 저장소 압축을 통째로 받지 않고 쓸 파일만 낱개로 받는다(로그인 없음, 공개 raw 주소, 고정한 커밋). 받은 파일은 데이터로만 다룬다(실행하지 않음).
- 라이선스는 받을 때마다 다시 확인한다: GitHub 저장소 API 의 license.spdx_id 가 MIT 이고, LICENSE 파일 첫 줄이 'MIT License' 이고, Modrinth 프로젝트
  (slug tce)의 license.id 가 MIT 여야 한다. 하나라도 다르면 멈춘다(아무것도 안 씀).
- ref/mods/the-car-engine/_info.json 에 주소, 커밋, 라이선스 근거, 파일마다 sha256 을 적는다. --check 는 받지 않고 있는 파일의 sha256 만 맞춰 본다.
"""
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "ref/mods/the-car-engine"
REPO = "MCTCRS/thecarengine"
SLUG = "tce"
UA = {"User-Agent": "mod-asset-fetch/1.0"}

# 받을 파일(저장소 경로). 차 모델 일곱과 바퀴 둘, 그 텍스처, 상자 화물차의 놓는 값(디스플레이 변환, 바퀴 자리: 읽기만 하는 데이터)
FILES = [
    "LICENSE",
    "README.md",
    "pack.mcmeta",
    "assets/tce/models/vehicles/tmc-transbox.json",
    "assets/tce/models/vehicles/tmc-pickup.json",
    "assets/tce/models/vehicles/tmc-pickup_4door.json",
    "assets/tce/models/vehicles/pickup_double_cab.json",
    "assets/tce/models/vehicles/sedan.json",
    "assets/tce/models/vehicles/tmc-clarion.json",
    "assets/tce/models/vehicles/chestler-lr.json",
    "assets/tce/models/wheels/wheel_1.json",
    "assets/tce/models/wheels/wheel_2.json",
    "assets/tce/textures/vehicles/tmc-transbox.png",
    "assets/tce/textures/vehicles/tmc-pickup.png",
    "assets/tce/textures/vehicles/tmc-pickup_4door.png",
    "assets/tce/textures/vehicles/pickup_double_cab.png",
    "assets/tce/textures/vehicles/sedan.png",
    "assets/tce/textures/vehicles/tmc-clarion.png",
    "assets/tce/textures/vehicles/chestler-lr.png",
    "assets/tce/textures/badges/tmc_gray.png",
    "assets/tce/textures/badges/tmc_chrome.png",
    "assets/tce/textures/wheels/wheel_1.png",
    "assets/tce/textures/wheels/wheel_2.png",
    "data/thescarengine/function/cars/tmc-transbox/display.mcfunction",
    "data/thescarengine/function/cars/tmc-transbox/settings.mcfunction",
    "data/thescarengine/function/cars/tmc-transbox/bone.mcfunction",
]


def get(url, accept=None):
    h = dict(UA)
    if accept:
        h["Accept"] = accept
    with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=60) as r:
        return r.read()


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def check():
    info = json.loads((OUT / "_info.json").read_text(encoding="utf-8"))
    bad = 0
    for rel, want in info["files"].items():
        p = OUT / "extracted" / rel
        if not p.is_file() or sha256(p.read_bytes()) != want:
            print("다름:", rel)
            bad += 1
    print(f"파일 {len(info['files'])}개, 다른 것 {bad}개, 커밋 {info['commit']}")
    return bad == 0


def main():
    if "--check" in sys.argv:
        sys.exit(0 if check() else 1)
    repo = json.loads(get(f"https://api.github.com/repos/{REPO}", "application/vnd.github+json"))
    spdx = (repo.get("license") or {}).get("spdx_id")
    branch = repo["default_branch"]
    commit = json.loads(get(f"https://api.github.com/repos/{REPO}/commits/{branch}", "application/vnd.github+json"))["sha"]
    lic = get(f"https://raw.githubusercontent.com/{REPO}/{commit}/LICENSE").decode("utf-8")
    proj = json.loads(get(f"https://api.modrinth.com/v2/project/{SLUG}"))
    mlic = (proj.get("license") or {}).get("id")
    first = lic.strip().splitlines()[0].strip()
    copyright_line = next((ln.strip() for ln in lic.splitlines() if ln.strip().lower().startswith("copyright")), "")
    print(f"GitHub license={spdx} 커밋={commit}\nLICENSE 첫 줄='{first}' 저작권 줄='{copyright_line}'\nModrinth license={mlic} source={proj.get('source_url')}")
    if spdx != "MIT" or first != "MIT License" or mlic != "MIT" or not copyright_line:
        sys.exit("라이선스 표기가 MIT 가 아니거나 엇갈린다: 받지 않음")
    readme = get(f"https://raw.githubusercontent.com/{REPO}/{commit}/README.md").decode("utf-8", "replace")
    body = (proj.get("body") or "")
    # 에셋 예외 문구(코드만 MIT, 에셋은 따로) 찾기: 있으면 멈춤
    for name, text in (("README", readme), ("Modrinth 본문", body)):
        low = text.lower()
        for word in ("all rights reserved", "assets are not", "not include assets", "only the code", "code only", "arr"):
            if word == "arr":
                continue
            if word in low:
                sys.exit(f"{name} 에 에셋 예외로 보이는 문구 '{word}': 받지 않음(사람이 읽고 판단할 것)")
    files = {}
    for rel in FILES:
        data = get(f"https://raw.githubusercontent.com/{REPO}/{commit}/{rel}")
        p = OUT / "extracted" / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        files[rel] = sha256(data)
        print(f"  {rel} {len(data)}")
    info = {
        "slug": "the-car-engine",
        "title": proj.get("title"),
        "url": "https://modrinth.com/datapack/tce",
        "source": f"https://github.com/{REPO}",
        "commit": commit,
        "pushed_at": repo.get("pushed_at"),
        "license": "MIT",
        "license_evidence": {
            "github_api_spdx": spdx,
            "license_file": f"https://github.com/{REPO}/blob/{commit}/LICENSE",
            "license_first_line": first,
            "copyright_line": copyright_line,
            "modrinth_license_id": mlic,
            "modrinth_source_url": proj.get("source_url"),
            "asset_exception_found": False,
            "checked": "tools/trip/fetch_tce.py 가 받을 때마다 다시 확인(README 와 Modrinth 본문에 에셋 예외 문구 없음)",
        },
        "readme": readme.strip(),
        "files": files,
        "used": ["assets/tce/models/vehicles/tmc-transbox.json", "assets/tce/textures/vehicles/tmc-transbox.png",
                 "assets/tce/models/wheels/wheel_1.json", "assets/tce/textures/wheels/wheel_1.png"],
        "note": "압축을 통째로 받지 않고 쓸 파일만 낱개로 받음(지울 jar, zip 없음). 차 모델 일곱 가운데 상자 화물차(tmc-transbox)가 원정의 트럭, "
                "나머지는 길가 폐차 소품 후보(원정.md 4.2절). 팩에 넣을 때 맨 위에 LICENSE-MIT-THE-CAR-ENGINE.txt(전문)와 CREDITS-TRIP.txt 를 같이 넣는다.",
    }
    (OUT / "_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"받음 {len(files)}개 → {OUT / 'extracted'}")


if __name__ == "__main__":
    main()
