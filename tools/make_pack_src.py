"""게이브 리소스팩 바탕(pack_src)을 만든다(좀비런 make_pack_src.py 를 옮김).

python -X utf8 tools/make_pack_src.py
- 바닐라 HUD 그림(하트, 배고픔, 갑옷, 공기, 경험치, 점프 막대, 핫바와 선택 틀)을 같은 크기 투명 그림으로 덮는다(칸 바꾸기는 그대로).
- 보스 막대 그림(모든 색, 눈금)도 투명하게: 보스바는 화면 HUD 를 실어 나르는 데만 쓴다(plugin kr.gave.hud.HudScreen, 제목 글리프만 보임).
- 보이지 않는 아이템 모델 gave:empty(장비 그림만 보일 아이템, 머리 칸 카메라 덮개용).
- pack.mcmeta(26.3, min_format 97).
십자선, 효과 아이콘은 그대로 둔다. 화면 HUD 글꼴과 core/text 셰이더는 tools/make_hud.py 가 만든다.
"""
import io
import json
import zipfile
from pathlib import Path

from PIL import Image

from mcjar import client_jar  # 26.3 클라 jar 를 공식 런처 자리 말고도 찾는다(GV_CLIENT_JAR, fabric-loom 캐시. 공식 자리에 있으면 예전과 같음)

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "pack_src"
JAR = client_jar()  # tools/mcjar.py: 공식 런처로 26.3 을 켠 적 없는 PC 에서도 돌게
SPRITES = "assets/minecraft/textures/gui/sprites/"
HIDE = tuple("hud/" + p for p in ("air", "armor_", "experience_bar_", "food_", "hotbar", "jump_bar_", "heart/")) + ("boss_bar/",)


def main():
    n = 0
    with zipfile.ZipFile(JAR) as z:
        for name in z.namelist():
            if not name.startswith(SPRITES) or not name.endswith(".png"):
                continue
            if not name[len(SPRITES):].startswith(HIDE):
                continue
            w, h = Image.open(io.BytesIO(z.read(name))).size
            dst = OUT / name
            dst.parent.mkdir(parents=True, exist_ok=True)
            Image.new("RGBA", (w, h), (0, 0, 0, 0)).save(dst)
            n += 1
    items = OUT / "assets/gave/items"
    items.mkdir(parents=True, exist_ok=True)
    (items / "empty.json").write_text(json.dumps({"model": {"type": "minecraft:empty"}}, indent=1), encoding="utf-8")
    (OUT / "pack.mcmeta").write_text(json.dumps({"pack": {"description": "게이브 (총은 모드에서, CREDITS.txt, 개인 시험용)",
                                                         "min_format": [97, 1], "max_format": 97}}, ensure_ascii=False, indent=1),
                                     encoding="utf-8")
    # 바닐라 글을 비움: 쓰러짐 좌석에 태울 때의 'Shift 로 내리기' 안내(build_pack 이 lang 은 키를 합침)
    lang = OUT / "assets/minecraft/lang"
    lang.mkdir(parents=True, exist_ok=True)
    for lg in ("ko_kr", "en_us"):
        (lang / f"{lg}.json").write_text(json.dumps({"mount.onboard": ""}, indent=1), encoding="utf-8")
    print(f"투명 HUD 그림 {n}개(보스 막대 포함), empty 모델, pack.mcmeta, 바닐라 글 비움 1개")


if __name__ == "__main__":
    main()
