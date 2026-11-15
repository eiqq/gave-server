"""상점 화면(상자 창) 꾸미기 자산: 어두운 판과 묶음 띠, 줄 이름, 칸 아이템(판 + 그림 + 가림막), 값 숫자, 돈 표시, 풍선 도움말 틀과 숫자 글꼴.

$PY -X utf8 tools/make_shop_ui.py [--sheet build/shopui/sheet.png]
(PY=build/pipeline/venv_pil/Scripts/python.exe, Pillow 11.1.0: 다른 팩 생성기와 같은 판으로 돌려 PNG 바이트를 고정한다. Pillow 12 로 돌려도 화소는 같다)

화면 짜임(kr.gave.game.Shop 과 같게): 54칸 상자 창의 위 여섯 줄 + 그 아래 플레이어 인벤토리 세 줄을 한 화면으로 쓴다(아홉 줄, 맨 아래 핫바는 그대로).
묶음마다 '그림 줄 + 그 아래 값 줄' 두 줄: 장비와 강화(줄 0, 1), 상자(2, 3), 소모품(4, 5), 팀(6, 7), 증강(8, 그림 줄만).
값은 그림 바로 아래 칸에 굵은 숫자로(999 까지 큰 글자, 네 자리는 작은 글자로 그대로, 만 원부터는 12k).

쓰는 곳(모두 pack_src, 이 스크립트만 쓰는 새 파일. 다른 생성기와 겹치는 것은 맨 아래 lang 두 파일뿐):
- assets/minecraft/textures/gui/container/generic_54.png  상자 창 바탕(27칸, 54칸 같이 씀): 어두운 단색 판, 플레이어 인벤토리 절반은 투명
- assets/minecraft/textures/gui/sprites/container/slot_highlight_{back,front}.png  커서 올린 칸(옅은 채움 + 흰 테두리)
- assets/gave/textures/gui/sprites/tooltip/shop_{background,frame}.png(+ .mcmeta)  풍선 도움말 틀(아이템 tooltip_style = gave:shop)
- assets/gave/font/shop.json, assets/gave/textures/font/shop/*.png  글꼴 gave:shop:
    창 제목에 싣는 글리프(상점 바탕: 왼쪽 띠, 묶음 띠, 아래 판 / 줄 이름 / 고르기 창 제목. U+E100 부터),
    풍선 도움말 숫자(Galmuri11 Bold 로 그린 0-9 + - / % . , x → 원. 그 글자 그대로의 코드), 나머지 글자는 기본 글꼴로 넘김
- assets/gave/items/shop/cell.json  칸 아이템 gave:shop/cell. custom_model_data:
    strings[0] 그림 키(shop_ui.json icons), strings[1] 판(shop_ui.json plates, 없으면 판 없음), flags[0] 가림막(못 삼, 잠김),
    floats[0..4] 큰 글자 줄(자리 i 는 x = 0.5 + 2.625 i, 글리프 번호 + 1, 0 = 없음), floats[5..8] 작은 글자 네 자리, floats[9] 오른쪽 위 표시 번호 + 1,
    floats[10..11] 오른쪽 아래 구석 두 글자(가진 수 x2), colors[0] 글자 색, colors[1] 표시 색. 값 칸은 그림 키와 판을 비우고 글자만 싣는다
- assets/gave/items/shop/wallet.json  돈 표시 gave:shop/wallet(oversized_in_gui: 걸린 칸에서 멀리 머리띠 오른쪽에 그림). floats[0..5] 오른쪽부터 글리프 번호 + 1
- assets/gave/models/item/shop/ui/*.json, assets/gave/textures/item/shop/ui/*.png  위 둘이 쓰는 판, 숫자, 표시(모두 16 x 16 안쪽: 아이템 아틀라스를 키우지 않게)
- assets/gave/shop_ui.json  플러그인(kr.gave.game.ShopUi)이 내보내는 팩에서 읽는 표: 제목 글, 아는 그림 키. 이 파일이 팩에 없으면 플러그인은 옛 모습 그대로 그린다
- assets/minecraft/lang/{ko_kr,en_us}.json 에 "container.inventory": "" 키만 합침(창 아래 '보관함' 글자 지움).
  이 두 파일은 tools/make_pack_src.py 가 통째로 다시 쓰므로 그 생성기에도 같은 키가 있어야 한다(없으면 그 뒤에 이 스크립트를 다시 돌린다)

그림은 모두 프로그램으로 그린 것(외부 그림 없음). 숫자는 Galmuri11 Bold 의 모양을 한 칸 폭(18)에 세 자리가 들어가게 6 x 11 로 다시 그렸다
(획 두 화소, 0 과 8, 1 과 7, 5 와 6, 3 과 8 이 갈리게). 총, 상자, 트럭 그림은 소품 층 모델을 그대로 가리킨다.
고친 뒤 시험 팩: GV_PACK_OUT=build/shopui/pack/Gave-pack.zip GV_PACK_SPLIT=1 $PY -X utf8 tools/build_pack.py(pack_src 를 그대로 묶음. GV_PACK_OUT 을 꼭 준다),
층이 구워져 있지 않은 PC 에서는 python -X utf8 tools/patch_pack.py --out build/shopui/pack <맨 끝에 찍히는 경로들>(릴리스 zip 에 갈아 끼움, 상점 파일은 같게 실림)
"""
import argparse
import json
import math
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "pack_src"
GAVE = PACK / "assets/gave"
MC = PACK / "assets/minecraft"
FONT_KO = ROOT / "art/fonts/Galmuri11-Bold.ttf"
PROPS = ROOT / "tools/props.json"
# patch_pack.py 에 줄 경로(이 스크립트가 쓰는 것 전부)
OUTPUTS = ["assets/minecraft/textures/gui/container/generic_54.png", "assets/minecraft/textures/gui/sprites/container",
           "assets/gave/textures/gui/sprites/tooltip", "assets/gave/font/shop.json", "assets/gave/textures/font/shop",
           "assets/gave/items/shop/cell.json", "assets/gave/items/shop/wallet.json", "assets/gave/models/item/shop/ui",
           "assets/gave/textures/item/shop/ui", "assets/gave/shop_ui.json", "assets/minecraft/lang/ko_kr.json", "assets/minecraft/lang/en_us.json"]

# ---------------------------------------------------------------- 색(단색 평면. 읽혀야 할 것 = 그림과 숫자가 먼저 보이게 판은 어둡게, 칸은 한 단 밝게)
PANEL = (18, 17, 16, 255)  # 창 바탕(순검정이 아닌 아주 어두운 따뜻한 회색)
BAND = (40, 38, 35, 255)  # 묶음 띠(줄 이름 + 그 묶음 칸들)
TILE = (104, 100, 93, 255)  # 그림 칸 판(어두운 총 모델도 보이는 중간 밝기)
TILE_HI = (150, 143, 130, 255)  # 새로 뽑은 총 칸
LABEL = (238, 234, 226, 255)  # 줄 이름(밝게: 중간 회색 글자는 쓰지 않는다)
WHITE = (240, 238, 232, 255)
GOLD = (255, 197, 61, 255)
SHADOW = (12, 11, 10, 255)  # 글자 오른쪽 아래 그림자(틴트가 곱해져도 어둡게 남음)
TIER = {"common": (190, 190, 190), "rare": (79, 163, 255), "epic": (184, 107, 255), "legendary": (255, 197, 61)}  # Gun.Rarity 색과 같게

# ---------------------------------------------------------------- 창 치수(바닐라 ContainerScreen: 폭 176, 칸 18 간격, 제목 글 (8, 6))
W = 176
SLOT_X, SLOT_Y, PITCH = 8, 18, 18
INV_Y, HOTBAR_Y = 140, 198  # 54칸 창에서 플레이어 인벤토리 첫 줄, 핫바 줄
H = 222  # 54칸 창 높이
PAD_BOTTOM = 7  # 27칸 창(고르기)의 마지막 줄 아래 판 여백
TITLE_X, TITLE_Y = 8, 6
GUTTER = 30  # 줄 이름 띠 폭(창 왼쪽 밖)
LABEL_RIGHT = 3  # 줄 이름 오른쪽 끝(창 x)
# 묶음: (그림 줄의 y, 줄 수, 줄 이름들, 띠를 끊는 틈(그 칸 뒤): 한 묶음 안의 작은 갈래)
BANDS = [
    (SLOT_Y, 2, ["장비", "강화"], [1, 3, 4]),  # 지금 총, 그 강화 | 권총, 그 강화 | 뽑은 총 | 스탯 넷
    (SLOT_Y + 2 * PITCH, 2, ["상자"], []),
    (SLOT_Y + 4 * PITCH, 2, ["소모품"], [1]),  # 탄약, 자동 탄약 | 소모품 일곱
    (INV_Y, 2, ["팀"], [1, 7]),  # 트럭 수리, 부품 | 꺼내기 여섯 | 증강 뽑기
    (INV_Y + 2 * PITCH, 1, ["증강"], [7]),  # 가진 증강 | 고를 카드
]
PICK_LABELS = {"parts": "부품 고르기", "cards": "증강 고르기"}
KO_PX, KO_ROWS, KO_H = 12, 12, 9  # make_hud.py 와 같은 크기: 12 화소로 그려 높이 9 GUI(텍셀 1 = 3/4 GUI)
NUM_CHARS = "0123456789+-/%.,x→원"  # 풍선 도움말 숫자 글꼴(ShopUi.NUM 과 같게)

# ---------------------------------------------------------------- 칸 글자(값 칸): 6 x 11 굵은 숫자를 텍셀 1 = 3/4 GUI 로(큰 글자), 1/2 GUI 로(작은 글자)
Z_PLATE, Z_VEIL, Z_TEXT = -1.0, 1.25, 1.5  # 층 깊이(블록, 앞이 +)
BIG, SMALL = 0.75, 0.5
BIG_X0, BIG_STEP, BIG_SLOTS = 0.5, 2.625, 5  # 큰 글자 자리(글자 하나 = 자리 둘 = 5.25 GUI). 세 글자면 0, 2, 4 자리, 두 글자 1, 3, 한 글자 2
SMALL_X0, SMALL_STEP, SMALL_SLOTS = 1.25, 3.5, 4
CORNER_SLOTS = 2
TEXT_TOP = 15  # 큰 글자 위 끝(칸 아래에서 잰 높이: 칸 위에서 1 내려옴)
GLYPHS = "0123456789k+vl-x"  # 글리프 차례(v 확인, l 자물쇠). ShopUi.GLYPHS 와 같게
BADGES = ["auto", "bang"]  # 오른쪽 위 표시: 도는 화살표(자동), 느낌표(새것). ShopUi.BADGE_* 와 같게
ART = {
    "0": [".####.", "######", "##..##", "##..##", "##..##", "##..##", "##..##", "##..##", "##..##", "######", ".####."],
    "1": ["..##..", ".###..", ".###..", "..##..", "..##..", "..##..", "..##..", "..##..", "..##..", "..##..", "..##.."],
    "2": [".####.", "######", "##..##", "##..##", "....##", "...###", "..###.", ".###..", "###...", "######", "######"],
    "3": [".####.", "######", "##..##", "....##", "..####", "..###.", "....##", "....##", "##..##", "######", ".####."],
    "4": ["...###", "..####", "..####", ".##.##", ".##.##", "##..##", "##..##", "######", "######", "....##", "....##"],
    "5": ["######", "######", "##....", "##....", "#####.", "######", "....##", "....##", "##..##", "######", ".####."],
    "6": [".####.", "######", "##..##", "##....", "#####.", "######", "##..##", "##..##", "##..##", "######", ".####."],
    "7": ["######", "######", "....##", "...###", "...##.", "..###.", "..##..", ".###..", ".##...", ".##...", ".##..."],
    "8": [".####.", "######", "##..##", "##..##", "######", ".####.", "##..##", "##..##", "##..##", "######", ".####."],
    "9": [".####.", "######", "##..##", "##..##", "##..##", "######", ".#####", "....##", "##..##", "######", ".####."],
    "k": ["##....", "##....", "##....", "##..##", "##.###", "#####.", "####..", "#####.", "##.###", "##..##", "##..##"],
    "+": ["......", "......", "......", "..##..", "..##..", "######", "######", "..##..", "..##..", "......", "......"],
    "x": ["......", "......", "......", "##..##", "######", ".####.", ".####.", "######", "##..##", "......", "......"],
    "-": ["......", "......", "......", "......", "......", "######", "######", "......", "......", "......", "......"],
    # 넓은 글리프(혼자 가운데에 놓임): 확인 표시, 자물쇠
    "v": ["..........", "........##", ".......###", "......###.", "##...###..", "###.###...", ".#####....", "..###.....", "...#......",
          "..........", ".........."],
    "l": ["..####..", ".##..##.", ".##..##.", ".##..##.", "########", "########", "###..###", "###..###", "########", "########", "........"],
}
BADGE_ART = {
    "auto": [".####.", "#....#", "#..###", "#...#.", "#.....", ".####."],
    "bang": ["..##..", "..##..", "..##..", "..##..", "......", "..##.."],
}
WALLET_SLOTS, WALLET_STEP = 6, 7  # 돈 표시: 글자 하나 6 x 11 GUI(텍셀 1 = 1 GUI), 자리 간격 7
WALLET_ANCHOR = 13  # 돈 표시 아이템이 걸리는 칸(Shop.SLOT_MONEY: 뽑은 총 아래 칸). 여기서 머리띠 오른쪽 끝까지 옮겨 그린다
NEW_SLOT = 4  # 뽑은 총 칸(Shop.SLOT_PENDING)
WALLET_RIGHT, WALLET_TOP = 168, 2  # 숫자 오른쪽 끝, 위 끝(창 좌표)

# 그림 키 → 모델. 총 모델 키(tools/props.json arms)는 그대로 그림 키다(상자 칸은 갈래 대표 총 키: Shop.Crate.icon)
FLAT = ["upgrade", "stat_hp", "stat_armor", "stat_move", "stat_reload", "flare", "molotov", "rattle", "cleanse", "raincoat", "bandage", "aug_roll",
        "ammo", "gnawed", "eye", "coin"]
SOLID = {"crate": ("weapon_crate", 0), "ammo_box": ("ammo_box", 0), "grenade": ("grenade", 0), "truck": ("trip/truck", 2), "wheel": ("trip/wheel", 2)}


def dump(path: Path, obj, compact=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(obj, ensure_ascii=False, separators=(",", ":")) if compact else json.dumps(obj, ensure_ascii=False, indent=1)
    path.write_text(text + "\n", encoding="utf-8", newline="\n")


def save(path: Path, img):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)


def slot_xy(slot):
    """창 좌표: 위 54칸은 여섯 줄, 그 뒤 27칸은 플레이어 인벤토리 세 줄"""
    r, c = divmod(slot, 9)
    return SLOT_X + c * PITCH, (SLOT_Y + r * PITCH) if r < 6 else (INV_Y + (r - 6) * PITCH)


# ---------------------------------------------------------------- 창 바탕, 커서 칸, 풍선 도움말

def base_texture():
    """generic_54.png: 위 조각(행 0..rows*18+16)과 아래 조각(행 126..)을 바닐라가 이어 붙인다. 27칸 창도 같은 그림의 위 71행 + 아래 조각.
    아래 조각(플레이어 인벤토리)은 닫는 여백만 두고 투명: 상점은 제목 글리프가 아래 판까지 그리고, 고르기 창은 아래가 비어 보인다"""
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, W - 1, 125), fill=PANEL)
    d.rectangle((0, 126, W - 1, 126 + PAD_BOTTOM - 1), fill=PANEL)
    save(MC / "textures/gui/container/generic_54.png", img)


def highlight():
    """커서 올린 칸(24 x 24, 칸은 4..19): 뒤 = 옅은 흰 채움, 앞 = 칸 둘레 1화소 흰 테두리"""
    back = Image.new("RGBA", (24, 24), (0, 0, 0, 0))
    ImageDraw.Draw(back).rectangle((4, 4, 19, 19), fill=(255, 255, 255, 40))
    front = Image.new("RGBA", (24, 24), (0, 0, 0, 0))
    ImageDraw.Draw(front).rectangle((3, 3, 20, 20), outline=(255, 255, 255, 255))
    save(MC / "textures/gui/sprites/container/slot_highlight_back.png", back)
    save(MC / "textures/gui/sprites/container/slot_highlight_front.png", front)


def tooltip():
    """풍선 도움말 틀 gave:shop(바닐라와 같은 100 x 100 아홉 조각, 글 상자는 8..91): 어두운 단색 + 밝은 테두리 한 줄"""
    bg = Image.new("RGBA", (100, 100), (0, 0, 0, 0))
    ImageDraw.Draw(bg).rectangle((8, 8, 91, 91), fill=(16, 15, 14, 252))
    fr = Image.new("RGBA", (100, 100), (0, 0, 0, 0))
    ImageDraw.Draw(fr).rectangle((8, 8, 91, 91), outline=(150, 144, 132, 255))
    out = GAVE / "textures/gui/sprites/tooltip"
    save(out / "shop_background.png", bg)
    save(out / "shop_frame.png", fr)
    dump(out / "shop_background.png.mcmeta", {"gui": {"scaling": {"type": "nine_slice", "width": 100, "height": 100, "border": 9}}})
    dump(out / "shop_frame.png.mcmeta",
         {"gui": {"scaling": {"type": "nine_slice", "width": 100, "height": 100, "border": 10, "stretch_inner": True}}})


# ---------------------------------------------------------------- 제목 글꼴(상점 바탕, 줄 이름), 풍선 도움말 숫자

def ko_mask(text):
    """Galmuri11 Bold 12 화소(계조 없이). 한글 잉크 맨 위를 첫 줄로, 높이 KO_ROWS - 1"""
    font = ImageFont.truetype(str(FONT_KO), KO_PX)
    mask = Image.new("L", (int(math.ceil(font.getlength(text))) + 4, KO_ROWS + 6), 0)
    d = ImageDraw.Draw(mask)
    d.fontmode = "1"
    d.text((0, -1), text, font=font, fill=255)
    box = mask.getbbox()
    return mask.crop((box[0], 0, box[2], KO_ROWS - 1))


def ko_image(text, color):
    """글 그림 + 오른쪽 아래 1 화소 그림자. 높이 KO_ROWS 텍셀"""
    mask = ko_mask(text)
    img = Image.new("RGBA", (mask.width + 1, KO_ROWS), (0, 0, 0, 0))
    img.paste(Image.new("RGBA", mask.size, SHADOW), (1, 1), mask)
    img.paste(Image.new("RGBA", mask.size, color), (0, 0), mask)
    return img


def move(n):
    """펜을 n GUI 만큼 옮기는 글자들(+2^i = U+E000+i, -2^i = U+E010+i)"""
    out, v = "", abs(int(n))
    for i in range(10):
        if v >> i & 1:
            out += chr((0xE010 if n < 0 else 0xE000) + i)
    return out


def advance(img, height):
    """비트맵 글리프의 펜 이동(바닐라: 오른쪽 끝 불투명 열까지의 폭 x 배율을 반올림 + 1)"""
    return int(0.5 + img.getbbox()[2] * height / img.height) + 1


def band_span(y, rows):
    """묶음 띠의 위, 아래 끝(그림 줄 위 3 부터 값 숫자 아래 1 까지. 그림 줄만 있으면 그림 아래 2 까지). 띠 사이는 3 비운다"""
    return y - 3, y + (PITCH + 11 if rows == 2 else 18)


def shop_background():
    """상점 화면 바탕 한 장(왼쪽 띠부터 창 오른쪽 끝, 창 높이): 판 + 묶음 띠(안의 작은 갈래는 띠를 끊어 가름)"""
    img = Image.new("RGBA", (GUTTER + W, H), PANEL)
    d = ImageDraw.Draw(img)
    for y, rows, _, cuts in BANDS:
        top, bottom = band_span(y, rows)
        d.rectangle((2, top, GUTTER + W - 3, bottom), fill=BAND)
        for c in cuts:
            x = GUTTER + SLOT_X + c * PITCH + 16
            d.rectangle((x, top, x + 1, bottom), fill=PANEL)
    # 뽑은 총 자리는 띠를 비워 둔다(총과 그 강화 | 빈 자리 | 스탯으로 갈리고, 새 총이 오면 그 자리에 밝은 칸이 뜬다)
    x, y = slot_xy(NEW_SLOT)
    top, bottom = band_span(y, 2)
    d.rectangle((GUTTER + x, top, GUTTER + x + 15, bottom), fill=PANEL)
    return img


def font():
    tex = GAVE / "textures/font/shop"
    shutil.rmtree(tex, ignore_errors=True)
    providers = [{"type": "space", "advances": {" ": 3, **{chr(0xE000 + i): 1 << i for i in range(10)},
                                                **{chr(0xE010 + i): -(1 << i) for i in range(10)}}}]
    code = 0xE100

    def glyph(name, img, height, top):
        """글리프 하나: 그림, 높이(GUI), 창 기준 위쪽 y. 글자와 펜 이동을 돌려줌"""
        nonlocal code
        save(tex / f"{name}.png", img)
        ch = chr(code)
        code += 1
        # 제목 글은 창 (8, 6) 에 그려지고 글리프 위 끝 = 6 + 7 - ascent
        providers.append({"type": "bitmap", "file": f"gave:font/shop/{name}.png", "height": height, "ascent": TITLE_Y + 7 - top, "chars": [ch]})
        return ch, advance(img, height)

    titles = {}
    # 상점: 바탕 한 장 + 줄 이름(오른쪽 맞춤, 묶음 띠의 세로 가운데. 이름이 둘이면 그림 줄과 값 줄에 하나씩)
    ch, adv = glyph("shop", shop_background(), H, 0)
    pen = -GUTTER
    s = move(pen - TITLE_X) + ch
    pen += adv
    n = 0
    for y, rows, names, _ in BANDS:
        mid = sum(band_span(y, rows)) / 2
        for i, name in enumerate(names):
            img = ko_image(name, LABEL)
            w = int(0.5 + img.width * KO_H / KO_ROWS)
            top = round(mid - KO_H / 2 + (i - (len(names) - 1) / 2) * (KO_H + 3))
            ch, adv = glyph(f"row{n}", img, KO_H, top)
            n += 1
            x = LABEL_RIGHT - w
            s += move(x - pen) + ch
            pen = x + adv
    titles["shop"] = s
    # 고르기 창(27칸): 머리띠 왼쪽에 제목
    for key, name in PICK_LABELS.items():
        ch, adv = glyph(f"pick_{key}", ko_image(name, LABEL), KO_H, 5)
        titles[key] = ch
    # 풍선 도움말 숫자: Galmuri 글리프를 한 줄 판에(칸 12 x 12, 그림자는 바닐라가 그림). 글자 그대로의 코드
    sheet = Image.new("RGBA", (12 * len(NUM_CHARS), KO_ROWS), (0, 0, 0, 0))
    fnt = ImageFont.truetype(str(FONT_KO), KO_PX)
    for i, c in enumerate(NUM_CHARS):
        mask = Image.new("L", (12, KO_ROWS + 4), 0)
        dr = ImageDraw.Draw(mask)
        dr.fontmode = "1"
        dr.text((0, -1), c, font=fnt, fill=255)
        mask = mask.crop((0, 0, 12, KO_ROWS))
        sheet.paste(Image.new("RGBA", (12, KO_ROWS), (255, 255, 255, 255)), (12 * i, 0), mask)
    save(tex / "num.png", sheet)
    providers.append({"type": "bitmap", "file": "gave:font/shop/num.png", "height": KO_H, "ascent": 8, "chars": [NUM_CHARS]})
    # 그 밖의 글자(한글, 영문)는 기본 글꼴 그대로
    providers.append({"type": "reference", "id": "minecraft:default"})
    dump(GAVE / "font/shop.json", {"providers": providers})
    return titles


# ---------------------------------------------------------------- 칸 아이템

def trs(translation, scale=(1, 1, 1)):
    return {"translation": [round(float(v), 5) for v in translation], "left_rotation": [0, 0, 0, 1],
            "scale": [round(float(v), 5) for v in scale], "right_rotation": [0, 0, 0, 1]}


def quad(name, texture, size=(16, 16), tint=None):
    """평면 한 장(남쪽 면, 그늘 없음, 왼쪽 아래가 칸 왼쪽 아래). 자리와 층 깊이는 아이템 정의의 변환으로"""
    face = {"uv": [0, 0, 16, 16], "texture": "#f"}
    if tint is not None:
        face["tintindex"] = tint
    dump(GAVE / f"models/item/shop/ui/{name}.json",
         {"textures": {"f": texture, "particle": texture}, "gui_light": "front",
          "elements": [{"from": [0, 0, 8], "to": [size[0], size[1], 8], "shade": False, "faces": {"south": face}}]})


def art_image(rows):
    """글리프 그림: 흰 잉크 + 오른쪽 아래 그림자(틴트로 색을 입힘). 크기 = 잉크 + 1"""
    w, h = max(len(r) for r in rows), len(rows)
    img = Image.new("RGBA", (w + 1, h + 1), (0, 0, 0, 0))
    px = img.load()
    for layer, c in ((1, SHADOW), (0, (255, 255, 255, 255))):
        for y, row in enumerate(rows):
            for x, v in enumerate(row):
                if v == "#":
                    px[x + layer, y + layer] = c
    return img


def cell_assets():
    tex = GAVE / "textures/item/shop/ui"
    shutil.rmtree(tex, ignore_errors=True)
    shutil.rmtree(GAVE / "models/item/shop/ui", ignore_errors=True)
    # 판: 단색 16 x 16. 총 칸은 등급 색 1 화소 테두리(_hi = 새로 뽑은 총: 밝은 판), gold = 강조(고를 것이 있음)
    plates = {"tile": (TILE, None), "gold": (TILE, GOLD[:3])}
    for tier, c in TIER.items():
        plates[tier] = (TILE, c)
        plates[tier + "_hi"] = (TILE_HI, c)
    for name, (fill, edge) in plates.items():
        img = Image.new("RGBA", (16, 16), fill)
        if edge:
            ImageDraw.Draw(img).rectangle((0, 0, 15, 15), outline=edge + (255,))
        save(tex / f"plate_{name}.png", img)
        quad(f"plate_{name}", f"gave:item/shop/ui/plate_{name}")
    save(tex / "veil.png", Image.new("RGBA", (16, 16), (10, 9, 8, 118)))  # 못 사는 칸: 무엇인지는 보이게 반쯤만 어둡게
    quad("veil", "gave:item/shop/ui/veil")
    # 글자 글리프: 잉크 w x 11 을 그림(w + 1) x 12 에. 판(쿼드)은 큰 글자 크기(텍셀 x 3/4), 작은 글자는 아이템 정의에서 2/3 로 줄임
    sizes = {}
    for i, ch in enumerate(GLYPHS):
        img = art_image(ART[ch])
        save(tex / f"g{i}.png", img)
        sizes[ch] = (img.width * BIG, img.height * BIG)
        quad(f"g{i}", f"gave:item/shop/ui/g{i}", sizes[ch], tint=0)
    for name in BADGES:
        img = art_image(BADGE_ART[name])
        save(tex / f"badge_{name}.png", img)
        quad(f"badge_{name}", f"gave:item/shop/ui/badge_{name}", (img.width, img.height), tint=0)
    # 카드 위아래 갈래 색 띠(흰 두 줄, 틴트로 색): bar_down = 칸 아래쪽(카드 위 칸), bar_up = 칸 위쪽(카드 아래 칸)
    for name, y in (("bar_down", 14), ("bar_up", 0)):
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        ImageDraw.Draw(img).rectangle((0, y, 15, y + 1), fill=(255, 255, 255, 255))
        save(tex / f"{name}.png", img)
        quad(name, f"gave:item/shop/ui/{name}", tint=0)
    return sorted(plates), sizes


def model(path, transformation=None, tints=None):
    m = {"type": "minecraft:model", "model": f"gave:item/{path}"}
    if transformation:
        m["transformation"] = transformation
    if tints:
        m["tints"] = tints
    return m


EMPTY = {"type": "minecraft:empty"}


def dispatch(index, entries):
    return {"type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": index,
            "entries": [{"threshold": g + 1, "model": m} for g, m in enumerate(entries)], "fallback": EMPTY}


def cell_item(plates, sizes):
    white = [{"type": "minecraft:constant", "value": 0xFFFFFF}]
    tint0 = [{"type": "minecraft:custom_model_data", "index": 0, "default": 0xFFFFFF}]
    icons = {name: model(f"shop/{name}") for name in FLAT}
    # 증강 카드 그림(make_shop_icons.py 의 aug/<키>): 그림 키 aug_<키>
    for f in sorted((GAVE / "textures/item/shop/aug").glob("*.png")):
        icons[f"aug_{f.stem}"] = model(f"shop/aug/{f.stem}")
    for name, (path, tints) in SOLID.items():
        icons[name] = model(path, None, white * tints)
    # 꺼내기: 죽은 동료 머리(아이템의 프로필 스킨). 바닐라 player_head 정의 그대로
    icons["head"] = {"type": "minecraft:special", "base": "minecraft:item/template_skull", "model": {"type": "minecraft:player_head"},
                     "transformation": {"translation": [0.5, 0, 0.5], "left_rotation": [1, 0, 0, 0], "scale": [1, 1, 1],
                                        "right_rotation": [0, 0, 0, 1]}}
    for name in ("bar_down", "bar_up"):
        icons[name] = model(f"shop/ui/{name}", None, tint0)
    # 총 그림: 소품 층 총 모델(모델 키와 종류 키 사본), 새 모델은 모든 면 tintindex 0 이라 흰색 고정
    props = json.loads(PROPS.read_text(encoding="utf-8"))
    for key in dict.fromkeys(list(props["arms"]) + [k["key"] for k in props["arms_kinds"].values()]):
        assert key not in icons, f"그림 키가 총 모델 키와 겹침: {key}"
        icons[key] = model(key, None, white)
    layers = [
        {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 1,
         "cases": [{"when": p, "model": model(f"shop/ui/plate_{p}", trs([0, 0, Z_PLATE]))} for p in plates], "fallback": EMPTY},
        {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
         "cases": [{"when": k, "model": m} for k, m in icons.items()], "fallback": EMPTY},
        {"type": "minecraft:condition", "property": "minecraft:custom_model_data", "index": 0,
         "on_true": model("shop/ui/veil", trs([0, 0, Z_VEIL])), "on_false": EMPTY},
    ]
    # 큰 글자: 자리 i 의 왼쪽 = BIG_X0 + BIG_STEP i. 넓은 글리프(확인, 자물쇠)는 혼자 쓰이므로 칸 가운데에 맞춘다
    for i in range(BIG_SLOTS):
        entries = []
        for g, ch in enumerate(GLYPHS):
            w, h = sizes[ch]
            x = BIG_X0 + BIG_STEP * i if len(ART[ch][0]) <= 6 else (16 - w) / 2
            entries.append(model(f"shop/ui/g{g}", trs([x / 16, (TEXT_TOP - h) / 16, Z_TEXT]), tint0))
        layers.append(dispatch(i, entries))
    # 작은 글자(네 자리 값): 같은 글리프를 2/3 로 줄여 큰 글자와 세로 가운데를 맞춘다
    k = SMALL / BIG
    for j in range(SMALL_SLOTS):
        entries = []
        for g, ch in enumerate(GLYPHS):
            w, h = sizes[ch]
            entries.append(model(f"shop/ui/g{g}", trs([(SMALL_X0 + SMALL_STEP * j) / 16, (TEXT_TOP - h / 2 - h * k / 2) / 16, Z_TEXT], [k, k, 1]),
                                 tint0))
        layers.append(dispatch(BIG_SLOTS + j, entries))
    # 구석 글자(오른쪽 아래 두 글자: 가진 수 x2). 큰 글자 크기로 그림 위에 겹친다
    for j in range(CORNER_SLOTS):
        entries = []
        for g, ch in enumerate(GLYPHS):
            x = 16 - 0.25 - (CORNER_SLOTS - j) * 2 * BIG_STEP + 0.75
            entries.append(model(f"shop/ui/g{g}", trs([x / 16, 0.5 / 16, Z_TEXT]), tint0))
        layers.append(dispatch(BIG_SLOTS + SMALL_SLOTS + 1 + j, entries))
    tint1 = [{"type": "minecraft:custom_model_data", "index": 1, "default": 0xFFFFFF}]
    layers.append(dispatch(BIG_SLOTS + SMALL_SLOTS, [
        model(f"shop/ui/badge_{name}", trs([(16 - len(BADGE_ART[name][0]) - 1) / 16, (16 - len(BADGE_ART[name]) - 1) / 16, Z_TEXT]), tint1)
        for name in BADGES]))
    dump(GAVE / "items/shop/cell.json", {"model": {"type": "minecraft:composite", "models": layers}}, compact=True)
    return sorted(icons)


def wallet_item(sizes):
    """돈 표시: 머리띠 오른쪽 끝에 오른쪽 맞춤으로 동전 + 숫자(텍셀 1 = 1 GUI: 화면에서 가장 큰 숫자). 걸린 칸 자체는 비어 보인다"""
    ax, ay = slot_xy(WALLET_ANCHOR)
    gold = [{"type": "minecraft:constant", "value": GOLD[0] << 16 | GOLD[1] << 8 | GOLD[2]}]
    k = 1 / BIG
    layers = []
    for i in range(WALLET_SLOTS):
        # 자리 i 의 잉크 오른쪽 끝 = WALLET_RIGHT - STEP i. 모델 y 는 칸 아래가 0 이라 위 끝 = 16 + (칸 위에서 올라간 높이)
        left = WALLET_RIGHT - WALLET_STEP * i - 6 - ax
        bottom = 16 + (ay - WALLET_TOP) - 12
        entries = [model(f"shop/ui/g{g}", trs([left / 16, bottom / 16, 0], [k, k, 1]), gold) for g in range(10)]
        # 동전(make_shop_icons.py 그림 그대로, 11 x 11): 숫자 왼쪽에 2 띄움
        entries.append(model("shop/ui/coin", trs([(left - 7) / 16, bottom / 16, 0])))
        layers.append(dispatch(i, entries))
    quad("coin", "gave:item/shop/coin", (11, 11))
    dump(GAVE / "items/shop/wallet.json", {"model": {"type": "minecraft:composite", "models": layers}, "oversized_in_gui": True}, compact=True)


def lang():
    """창 아래 플레이어 인벤토리 이름을 비움(키만 합침: 이 파일은 make_pack_src.py 것이라 그 생성기와 같은 꼴로 쓴다.
    그쪽 사전에 같은 키가 들어가면 두 생성기의 결과가 바이트까지 같다)"""
    for lg in ("ko_kr", "en_us"):
        path = MC / f"lang/{lg}.json"
        data = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
        data["container.inventory"] = ""
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, indent=1), encoding="utf-8")


def sheet(path):
    """만든 그림을 한 장에(확인용): 글자 글리프는 실제 크기의 배수로 크게"""
    files = sorted((GAVE / "textures/item/shop/ui").glob("*.png"), key=lambda f: (len(f.stem), f.stem)) + sorted(
        (GAVE / "textures/font/shop").glob("*.png"))
    out = Image.new("RGBA", (1200, 760), (40, 38, 35, 255))
    x = y = 8
    row = 0
    for f in files:
        im = Image.open(f).convert("RGBA")
        k = 6 if im.width <= 16 else 3 if im.height < 20 else 2
        big = im.resize((im.width * k, im.height * k), Image.NEAREST)
        if x + big.width > 1190:
            x, y, row = 8, y + row + 8, 0
        out.alpha_composite(big, (x, y))
        x += big.width + 8
        row = max(row, big.height)
    path.parent.mkdir(parents=True, exist_ok=True)
    out.save(path)
    print("미리보기", path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", help="만든 그림을 한 장에 모은 png")
    a = ap.parse_args()
    base_texture()
    highlight()
    tooltip()
    titles = font()
    plates, sizes = cell_assets()
    icons = cell_item(plates, sizes)
    wallet_item(sizes)
    lang()
    dump(GAVE / "shop_ui.json", {"v": 2, "titles": titles, "icons": icons, "plates": plates, "glyphs": GLYPHS, "badges": BADGES, "num": NUM_CHARS})
    bad = [p.name for p in PACK.rglob("*.png") if p.stem.endswith(("_n", "_s", "_e"))]
    if bad:
        raise SystemExit("텍스처 이름이 _n, _s, _e 로 끝남: " + ", ".join(bad))
    area = sum(Image.open(f).size[0] * Image.open(f).size[1] for f in (GAVE / "textures/item/shop/ui").glob("*.png"))
    print(f"상점 화면: 그림 키 {len(icons)}, 판 {len(plates)}, 제목 {', '.join(titles)}, 아이템 아틀라스에 더한 넓이 {area} 화소 -> {PACK.relative_to(ROOT)}")
    print("시험 팩: python -X utf8 tools/patch_pack.py --out build/shopui/pack " + " ".join(OUTPUTS))
    if a.sheet:
        sheet(ROOT / a.sheet)


if __name__ == "__main__":
    main()
