"""Stellaris(st0x0ef, github.com/st0x0ef/stellaris-2.0)에서 발사장 모델 원본만 낱개로 받아 ref/mods/stellaris/extracted/ 에 둔다.

python -I -X utf8 tools/trip/fetch_stellaris.py [--check]

- 받는 것: 큰 로켓 자바 모델 소스(BigRocketModel.java, ModelPart 정의 글만 읽음), 발사대 자바 모델 소스(RocketLaunchPadModel.java),
  그 텍스처 둘, 건조 지점에 놓을 기계 블록 모델(연료 정제기, 석탄 발전기, 공학 작업대)과 텍스처, 라이선스 전문.
  저장소 압축이나 jar 를 받지 않는다(로그인 없음, 공개 raw 주소, 고정한 커밋). 받은 파일은 데이터로만 다룬다(자바 소스는 글로 읽기만, 컴파일하거나 실행하지 않음).
- 라이선스는 받을 때마다 다시 확인한다: GitHub 저장소 API license.spdx_id 가 MIT, LICENSE 첫 줄 'MIT License', Modrinth 프로젝트(slug stellaris)
  license.id 가 CC-BY-NC-SA-4.0(엇갈림: 둘 다 허용 목록이라 두 조건을 모두 지킨다), Modrinth 본문에 에셋 예외 문구(all rights reserved 따위)가 없어야 한다.
  하나라도 어긋나면 멈춘다(아무것도 안 씀).
- ref/mods/stellaris/_info.json 에 주소, 커밋, 라이선스 근거, 파일마다 sha256 을 적는다. --check 는 받지 않고 있는 파일의 sha256 만 맞춰 본다.
"""
import hashlib
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "ref/mods/stellaris"
REPO = "st0x0ef/stellaris-2.0"
SLUG = "stellaris"
UA = {"User-Agent": "mod-asset-fetch/1.0"}
# 2026-11-12 에 본 커밋(브랜치 26.1-stable). 바꾸려면 새 커밋으로 받고 미리보기를 다시 본다
COMMIT = "30994c98ea90d63a56c3885767400917480cde47"

J = "common/src/main/java/org/exodusstudio/stellaris/client/renderers"
A = "common/src/main/resources/assets/stellaris"
FILES = [
    "LICENSE",
    f"{J}/rockets/models/BigRocketModel.java",
    f"{J}/rockets/models/RocketModel.java",
    f"{J}/launchpad/RocketLaunchPadModel.java",
    f"{A}/textures/entity/rocket/big/default.png",
    f"{A}/textures/block/rocket_launch_pad.png",
    f"{A}/models/block/fuel_refinery.json",
    f"{A}/models/block/fuel_refinery_on.json",
    f"{A}/models/block/coal_generator.json",
    f"{A}/models/block/coal_generator_on.json",
    f"{A}/models/block/engineering_station.json",
    f"{A}/textures/block/machines/fuel_refinery_bottom.png",
    f"{A}/textures/block/machines/fuel_refinery_front.png",
    f"{A}/textures/block/machines/fuel_refinery_front_on.png",
    f"{A}/textures/block/machines/fuel_refinery_side.png",
    f"{A}/textures/block/machines/fuel_refinery_top.png",
    f"{A}/textures/block/machines/coal_generator/coal_generator_bottom.png",
    f"{A}/textures/block/machines/coal_generator/coal_generator_front.png",
    f"{A}/textures/block/machines/coal_generator/coal_generator_front_on.png",
    f"{A}/textures/block/machines/coal_generator/coal_generator_front_on.png.mcmeta",
    f"{A}/textures/block/machines/coal_generator/coal_generator_side.png",
    f"{A}/textures/block/machines/coal_generator/coal_generator_top.png",
    f"{A}/textures/block/machines/engineering_station.png",
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
    lic = get(f"https://raw.githubusercontent.com/{REPO}/{COMMIT}/LICENSE").decode("utf-8")
    proj = json.loads(get(f"https://api.modrinth.com/v2/project/{SLUG}"))
    mlic = (proj.get("license") or {}).get("id")
    first = lic.strip().splitlines()[0].strip()
    copyright_line = next((ln.strip() for ln in lic.splitlines() if ln.strip().lower().startswith("copyright")), "")
    print(f"GitHub license={spdx} 커밋={COMMIT}\nLICENSE 첫 줄='{first}' 저작권 줄='{copyright_line}'\nModrinth license={mlic} source={proj.get('source_url')}")
    if spdx != "MIT" or first != "MIT License" or not copyright_line:
        sys.exit("저장소 라이선스가 MIT 가 아니다: 받지 않음")
    if mlic not in ("CC-BY-NC-SA-4.0", "MIT"):
        sys.exit(f"Modrinth 라이선스 {mlic} 가 허용 목록 밖: 받지 않음")
    if (proj.get("source_url") or "").rstrip("/") != f"https://github.com/{REPO}":
        sys.exit("Modrinth 의 소스 주소가 저장소와 다르다: 받지 않음(사람이 확인할 것)")
    body = (proj.get("body") or "").lower()
    for word in ("all rights reserved", "assets are not", "not include assets", "only the code", "code only"):
        if word in body:
            sys.exit(f"Modrinth 본문에 에셋 예외로 보이는 문구 '{word}': 받지 않음(사람이 읽고 판단할 것)")
    files, missing = {}, []
    for rel in FILES:
        try:
            data = get(f"https://raw.githubusercontent.com/{REPO}/{COMMIT}/{rel}")
        except urllib.error.HTTPError as e:
            missing.append(f"{rel} ({e.code})")
            continue
        p = OUT / "extracted" / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        files[rel] = sha256(data)
        print(f"  {rel} {len(data)}")
    info = {
        "slug": "stellaris",
        "title": proj.get("title"),
        "url": "https://modrinth.com/mod/stellaris",
        "source": f"https://github.com/{REPO}",
        "commit": COMMIT,
        "pushed_at": repo.get("pushed_at"),
        "license": "MIT(저장소 LICENSE) / CC-BY-NC-SA-4.0(Modrinth 칸): 엇갈려 두 조건을 모두 지킨다",
        "license_evidence": {
            "github_api_spdx": spdx,
            "license_file": f"https://github.com/{REPO}/blob/{COMMIT}/LICENSE",
            "license_first_line": first,
            "copyright_line": copyright_line,
            "modrinth_license_id": mlic,
            "modrinth_source_url": proj.get("source_url"),
            "asset_exception_found": False,
            "checked": "tools/trip/fetch_stellaris.py 가 받을 때마다 다시 확인(Modrinth 본문에 에셋 예외 문구 없음, 저장소에 README 없음)",
        },
        "files": files,
        "missing": missing,
        "used": [f"{J}/rockets/models/BigRocketModel.java", f"{A}/textures/entity/rocket/big/default.png",
                 f"{J}/launchpad/RocketLaunchPadModel.java", f"{A}/textures/block/rocket_launch_pad.png",
                 "기계 블록 모델 셋과 텍스처"],
        "note": "저장소 압축과 jar 를 받지 않고 쓸 파일만 낱개로 받음(지울 jar, zip 없음). 자바 모델 소스는 tools/trip/launch.py 가 글로 읽어(ModelPart 정의) "
                "26.3 아이템 모델 요소로 옮긴다. 소리는 받지 않음(Modrinth 칸 CC BY-NC-SA 라 소리 허용 목록 밖, 발사 소리는 이미 받은 trip.rocket_* 를 씀).",
    }
    (OUT / "_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"받음 {len(files)}개 → {OUT / 'extracted'}" + (f", 없음 {missing}" if missing else ""))


if __name__ == "__main__":
    main()
