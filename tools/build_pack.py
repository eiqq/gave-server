"""게이브 리소스팩 둘: pack_src/ 와 소품 층(build/props/pack_layer 모드에서 가져온 총, build/gabe/pack_layer 게이브 몹)을 묶어
모습 팩 build/Gave-pack.zip 과 소리 팩 build/Gave-sound.zip 으로 나눈다.

python -X utf8 tools/build_pack.py
- 소리 팩: 모든 이름공간의 assets/<이름공간>/sounds.json 과 assets/<이름공간>/sounds/ 아래 파일 전부(ogg 는 어디 있든 소리 팩).
  모습 팩: 나머지 전부(모델, 텍스처, 셰이더, 글꼴, 언어, 아이템 정의, music.json, gabe_looks.json 같은 데이터).
  두 팩에 같은 경로는 없다. 맨 위 파일(pack.mcmeta, CREDITS.txt, 라이선스 전문)만 둘 다에 들어가고, pack.mcmeta 는 pack_src 것에서
  설명 글만 '게이브 모습', '게이브 소리' 로 바꾼다.
- 플러그인(kr.gave.PackServer)은 모습 팩과 같은 폴더에 Gave-sound.zip 이 있으면 두 팩을 한 요청으로(모습 다음 소리) 보내고,
  없으면 Gave-pack.zip 하나만 보낸다(소리까지 든 옛 꼴의 팩 하나도 그대로 돈다).
- 환경 변수 GV_PACK_OUT 이 있으면 모습 팩을 그 경로로, 소리 팩은 그 옆 Gave-sound.zip 으로 내보낸다(tools/ingame.py 가
  build/<이름>/Gave-pack.zip 으로 씀). GV_PACK_OUT 이 있을 때는 GV_PACK_SPLIT=1 일 때만 나누고, 아니면 예전처럼 소리까지 하나로 묶는다
  (그때 그 옆에 남은 옛 Gave-sound.zip 은 지운다: 남으면 서버가 소리를 두 번 보냄). 시험 팩도 늘 나누려면 아래 SPLIT_TEST 를 True 로.
  GV_PACK_SPLIT=0 이면 어디로 내보내든 하나로 묶는다.
겹치는 경로는 뒤 층이 이긴다(소품 층 < 옮긴 총 층 build/gcb/pack_layer < 원정 층 < 게이브 몹 층 < pack_src). 층의 pack.mcmeta 는 쓰지 않는다. 없는 층은 건너뛴다.
단 assets/<이름공간>/sounds.json 과 lang/*.json 은 덮지 않고 키를 합친다(총 소리와 게이브 몹 소리가 같은 gave 이름공간).
묶은 zip(나누면 두 팩 모두) 맨 위에 CREDITS.txt(소품 층의 저작자 표시, CC BY-NC-SA 조건)와 라이선스 전문이 없으면 내보내지 않고
실패한다(종료 코드 1, 있던 팩은 그대로).

총(pistol, smg, rifle, shotgun, sniper)은 묶을 때마다 아이템 정의를 새로 만든다(소품 층 총 모델이 바뀌어도 맞게):
- 인벤토리, 상자 상점, 핫바(gui)에서는 총 몸통만(탄창 빠짐 깃발은 따르고 불꽃은 없음), 나머지 자리에서는 [총 모델 | 탄창 뺀 모델]
  + 총구 불꽃 모델(muzzle_<총>)을 겹친다. 좀비런은 gui 를 빈 모델로 숨겼지만 게이브는 상자 상점에서 총이 보여야 한다.
- custom_model_data 깃발 0 = 탄창 빠짐(재장전 중 <총>_nomag 모델), 색 0, 1, 2 = 남이 보는 총구 불꽃 앞면, 가로 옆면, 세로 옆면 도장
  ([종류 4][씨앗 8][쏜 틱 mod 4000, 12], pack_fx/zrfx.glsl 종류 11, 12, 13), 색 4, 5, 6 = 같은 불꽃의 1인칭 도장(씨앗에 반동 비트).
  색이 없으면 0 이라 불꽃이 안 보인다.
- 총구 자리는 총 모델 요소(회전 반영)에서 가장 앞(-z) 끝 단면의 가운데로 잡는다. 불꽃 모델은 총 모델 표시 변환을 옮겨 써
  표시 변환(1인칭, 3인칭, 바닥, 액자)이 늘 같다.
- hand_animation_on_swap 끔: 쏠 때마다 총 아이템 데이터가 바뀌어도 1인칭 총이 내려갔다 올라오지 않게.
- 1인칭 자리(firstperson_righthand, firstperson_lefthand)만 몸통을 모든 면에 tintindex 0 을 단 사본(models/item/kick/<총>,
  <총>_nomag)으로 그리고 틴트 = custom_model_data 색 3(기본 흰색), 불꽃 틴트 = 색 4..6. 쏜 뒤, 꺼낼 때, 재장전 동안 몸짓 도장이
  들어가 core/item.vsh(zr_kick)가 1인칭 총을 움직이고 색은 흰색으로 되돌린다. 3인칭, 바닥, 액자 자리는 원래 총 모델(틴트 없음)과
  불꽃 색 0..2(반동 비트 없음)라 남의 총은 내 화면 가까이 있어도 움직이지 않는다(아이템 데이터는 모두가 받으므로 자리로 가른다).
"""
import json
import math
import os
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(os.environ["GV_PACK_OUT"]) if os.environ.get("GV_PACK_OUT") else ROOT / "build/Gave-pack.zip"
# 소리 팩은 모습 팩 옆(PackServer 가 같은 폴더에서 이 이름을 찾는다)
SOUND_OUT = OUT.parent / "Gave-sound.zip"
# 시험 팩(GV_PACK_OUT, tools/ingame.py)도 기본으로 나눌까. 다른 작업자의 게임 안 시험이 흔들리지 않게 지금은 False(GV_PACK_SPLIT=1 일 때만 나눔)
SPLIT_TEST = False
_SPLIT_ENV = os.environ.get("GV_PACK_SPLIT", "")
SPLIT = _SPLIT_ENV == "1" if _SPLIT_ENV in ("0", "1") else (SPLIT_TEST if os.environ.get("GV_PACK_OUT") else True)
# 소리 팩에 갈 경로: 모든 이름공간의 sounds.json 과 sounds/ 아래 파일, 그리고 어디 있든 ogg. 나머지는 모습 팩, 맨 위 파일(경로에 / 없음)은 둘 다
SOUND_PATH = re.compile(r"assets/[^/]+/(sounds\.json|sounds/.+)|.+\.ogg")
PACK_DESC = ("게이브 모습", "게이브 소리")
LAYERS = [ROOT / "build/props/pack_layer", ROOT / "build/gcb/pack_layer", ROOT / "build/trip/pack_layer", ROOT / "build/gabe/pack_layer",
          ROOT / "pack_src"]  # 옮긴 총 층(tools/props/build_gcb.py: 소품 층의 weapon_icon.json 을 덮으므로 소품 층 바로 뒤), 원정 층(tools/make_trip.py: 트럭)
# 핫바, 인벤토리(gui 자리)에서 숨길 아이템 키 앞머리. 좀비런은 총, 수류탄, 능력 아이템을 모두 숨겼지만 게이브는 상자 상점과 인벤토리에
# 총, 수류탄, 상점 그림이 보여야 해서 비워 둔다. 꼭 숨길 아이템이 생기면 여기에 더한다(손, 진열대, 땅에서는 그대로 보임).
# 주의: 핫바 틀 그림은 make_pack_src 가 투명하게 하므로 핫바 칸의 아이템 그림은 화면 아래에 틀 없이 그려진다
GUI_HIDE = ()
# 여러 층에 같은 경로가 있으면 덮지 않고 키를 합치는 파일(소리 사건, 자막 번역)
MERGE = re.compile(r"assets/[a-z0-9_.-]+/(sounds\.json|lang/[a-z_]+\.json)")
GUNS = ("pistol", "smg", "rifle", "shotgun", "sniper")
# 총구 불꽃 크기(블록, 1인칭 표시 배율로 모델 단위로 바꿈): 앞면 지름, 옆 불길 길이
FLASH = {"pistol": (0.27, 0.22), "smg": (0.29, 0.25), "rifle": (0.35, 0.32), "shotgun": (0.5, 0.44), "sniper": (0.44, 0.44)}


def corners(e):
    """요소 상자 꼭짓점 8개(모델 회전 반영)"""
    f, t = e["from"], e["to"]
    pts = [[x, y, z] for x in (f[0], t[0]) for y in (f[1], t[1]) for z in (f[2], t[2])]
    r = e.get("rotation")
    if not r or not r.get("angle"):
        return pts
    a = math.radians(r["angle"])
    o = r["origin"]
    c, s = math.cos(a), math.sin(a)
    out = []
    for x, y, z in pts:
        x, y, z = x - o[0], y - o[1], z - o[2]
        if r["axis"] == "x":
            y, z = y * c - z * s, y * s + z * c
        elif r["axis"] == "y":
            x, z = x * c + z * s, -x * s + z * c
        else:
            x, y = x * c - y * s, x * s + y * c
        out.append([x + o[0], y + o[1], z + o[2]])
    return out


def muzzle(model):
    """총구 끝(모델 단위): 가장 앞 z 에서 0.06 안에 걸친 요소들(총열 끝 단면) 상자의 가운데"""
    boxes = [corners(e) for e in model.get("elements", [])]
    if not boxes:
        return None
    zmin = min(p[2] for b in boxes for p in b)
    front = [p for b in boxes if min(q[2] for q in b) < zmin + 0.06 for p in b]
    xs, ys = [p[0] for p in front], [p[1] for p in front]
    return (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, zmin


def face(uv):
    return {"uv": uv, "texture": "#fx", "tintindex": 0}


CONTEXTS = ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "head", "gui", "ground",
            "fixed", "on_shelf")
# 불꽃 모델은 총 모델 좌표를 가운데(8, 8, 8)로 SHRINK 배 줄이고 표시 배율을 1/SHRINK 배로 키운다: 그려지는 자리는 총과 같고,
# 긴 총(총구 z -15)의 앞으로 뻗은 불길도 모델 좌표 한계(-16..32) 안에 들어간다
SHRINK = 0.5


def flash_model(gun, model):
    """총구 불꽃: 총구 앞 정면 판(색 0) + 총열 따라 앞으로 뻗은 가로 판(색 1), 세로 판(색 2). 표시 변환은 총 모델 것을 옮겨 씀"""
    m = muzzle(model)
    if m is None:
        return None
    mx, my, mz = m
    disp = model.get("display", {})
    fp = disp.get("firstperson_righthand", {}).get("scale", [0.4])[0] or 0.4
    size, length = (v * 16 / fp for v in FLASH[gun])
    half, wide = size / 2, length * 0.32
    z0 = mz - 0.25
    # 옆 판 UV: 가로 판 위, 아래 면은 v = 16 이 총구(z 큰 쪽), 세로 판 동, 서 면은 u = 16 이 총구가 되게 뒤집어 둔다(zrfx 12, 13)
    horiz = {"up": face([0, 0, 16, 16]), "down": face([0, 16, 16, 0])}
    vert = {"east": face([16, 0, 0, 16]), "west": face([0, 0, 16, 16])}
    for f in horiz.values():
        f["tintindex"] = 1
    for f in vert.values():
        f["tintindex"] = 2
    els = [
        {"from": [mx - half, my - half, z0], "to": [mx + half, my + half, z0], "shade": False,
         "faces": {"north": face([0, 0, 16, 16]), "south": face([0, 0, 16, 16])}},
        {"from": [mx - wide, my, mz - length], "to": [mx + wide, my, mz + 0.4], "shade": False, "faces": horiz},
        {"from": [mx, my - wide, mz - length], "to": [mx, my + wide, mz + 0.4], "shade": False, "faces": vert},
    ]
    for e in els:
        for k in ("from", "to"):
            e[k] = [round(8 + (v - 8) * SHRINK, 4) for v in e[k]]
            assert all(-16 <= v <= 32 for v in e[k]), (gun, e[k])
    display = {}
    for ctx in CONTEXTS:
        d = dict(disp.get(ctx, {}))
        s = d.get("scale", [1, 1, 1])
        d["scale"] = [round(v / SHRINK, 5) for v in s]
        assert all(abs(v) <= 4 for v in d["scale"]), (gun, ctx, d["scale"])
        display[ctx] = d
    return {"textures": {"fx": "gave:item/fx_marker", "particle": "gave:item/fx_marker"}, "elements": els, "display": display}


def kick_copy(files, name):
    """1인칭 총 몸통 모델 사본: 모든 면 tintindex 0(몸짓 데이터 틴트 = 색 3). 사본 모델 키"""
    model = json.loads(files[f"assets/gave/models/item/{name}.json"].decode("utf-8"))
    for e in model.get("elements", []):
        for f in e.get("faces", {}).values():
            f["tintindex"] = 0
    files[f"assets/gave/models/item/kick/{name}.json"] = json.dumps(model, separators=(",", ":")).encode("utf-8")
    return {"type": "minecraft:model", "model": f"gave:item/kick/{name}",
            "tints": [{"type": "minecraft:custom_model_data", "index": BODY_TINT, "default": 16777215}]}


def plain(name):
    """3인칭, 바닥, 액자 자리 총 몸통: 원래 모델 그대로(틴트 없음 = 흰색, 셰이더가 움직이지 않음)"""
    return {"type": "minecraft:model", "model": f"gave:item/{name}"}


# custom_model_data 색 자리(Guns.visual 과 같게): 남이 보는 불꽃 0..2, 1인칭 몸짓 3, 1인칭 불꽃 4..6
FLASH_THIRD, BODY_TINT, FLASH_FIRST = 0, 3, 4
FIRST_PERSON = ["firstperson_righthand", "firstperson_lefthand"]


def gun_items(files):
    """총 아이템 정의와 총구 불꽃 모델을 files(묶을 경로 → 바이트)에 넣는다. 만든 총 이름 목록"""
    made = []
    for gun in GUNS:
        mpath = f"assets/gave/models/item/{gun}.json"
        if mpath not in files:
            continue
        model = json.loads(files[mpath].decode("utf-8"))
        flash = flash_model(gun, model)
        first, third = kick_copy(files, gun), plain(gun)
        if f"assets/gave/models/item/{gun}_nomag.json" in files:
            first = {"type": "minecraft:condition", "property": "minecraft:custom_model_data", "index": 0,
                     "on_true": kick_copy(files, f"{gun}_nomag"), "on_false": first}
            third = {"type": "minecraft:condition", "property": "minecraft:custom_model_data", "index": 0,
                     "on_true": plain(f"{gun}_nomag"), "on_false": third}
        body = third  # gui(인벤토리, 상자 상점, 핫바): 몸통만, 불꽃 모델은 붙이지 않음
        first, third = [first], [third]
        if flash is not None:
            files[f"assets/gave/models/item/muzzle_{gun}.json"] = json.dumps(flash, indent=1).encode("utf-8")
            for parts, at in ((first, FLASH_FIRST), (third, FLASH_THIRD)):
                parts.append({"type": "minecraft:model", "model": f"gave:item/muzzle_{gun}",
                              "tints": [{"type": "minecraft:custom_model_data", "index": at + i, "default": 0} for i in range(3)]})
        d = {"hand_animation_on_swap": False,
             "model": {"type": "minecraft:select", "property": "minecraft:display_context",
                       "cases": [{"when": "gui", "model": body},
                                 {"when": FIRST_PERSON, "model": {"type": "minecraft:composite", "models": first}}],
                       "fallback": {"type": "minecraft:composite", "models": third}}}
        files[f"assets/gave/items/{gun}.json"] = json.dumps(d, indent=1).encode("utf-8")
        made.append(gun)
    return made


def split_packs(files):
    """files(묶을 경로 → 바이트)를 (모습 팩, 소리 팩) 둘로 가른다. 맨 위 파일(CREDITS.txt, 라이선스 전문, pack.mcmeta)은 둘 다에 넣고
    pack.mcmeta 는 pack_src 것에서 설명 글만 바꾼다(26.3 형식 칸은 그대로)"""
    look, sound = {}, {}
    for name, data in files.items():
        if "/" not in name:
            look[name] = sound[name] = data
        elif SOUND_PATH.fullmatch(name):
            sound[name] = data
        else:
            look[name] = data
    if "pack.mcmeta" in files:
        for part, desc in zip((look, sound), PACK_DESC):
            meta = json.loads(files["pack.mcmeta"].decode("utf-8"))
            meta.setdefault("pack", {})["description"] = desc
            part["pack.mcmeta"] = json.dumps(meta, ensure_ascii=False, indent=1).encode("utf-8")
    return look, sound


def pack_zip(path, files):
    """files 를 path 옆 임시 파일(.tmp)로 묶어 그 경로를 돌려준다(바꿔치기는 부르는 쪽이 모두 묶은 뒤에). 시각을 고정해 같은 내용이면 같은 sha1"""
    tmp = path.with_suffix(".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(files):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, files[name])
    return tmp


def main():
    files = {}
    for layer in LAYERS:
        if not layer.is_dir():
            continue
        for f in sorted(layer.rglob("*")):
            if not f.is_file():
                continue
            rel = f.relative_to(layer).as_posix()
            if layer != ROOT / "pack_src" and rel == "pack.mcmeta":
                continue
            data = f.read_bytes()
            if rel in files and MERGE.fullmatch(rel):
                # 같은 이름공간 gave 의 소리 사건(총은 소품 층, 게이브 몹은 몹 층)과 자막은 덮지 않고 합친다. 같은 키는 뒤 층이 이김
                old, new = json.loads(files[rel].decode("utf-8")), json.loads(data.decode("utf-8"))
                both = sorted(set(old) & set(new))
                if both:
                    print(f"{rel}: {layer.relative_to(ROOT).as_posix()} 가 같은 키 {len(both)}개를 덮음({', '.join(both[:5])}"
                          + (" ..." if len(both) > 5 else "") + ")")
                old.update(new)
                data = json.dumps(old, ensure_ascii=False, indent=1).encode("utf-8")
            files[rel] = data
    guns = gun_items(files)
    # GUI_HIDE 에 든 아이템만 gui 자리에서 숨긴다(지금은 없음): 손, 진열대, 땅에서는 그대로
    for name in list(files):
        if not GUI_HIDE or not (name.startswith("assets/gave/items/") and name.endswith(".json")):
            continue
        key = name.rsplit("/", 1)[1][:-5]
        if not key.startswith(GUI_HIDE) or key in GUNS:
            continue
        d = json.loads(files[name].decode("utf-8"))
        m = d.get("model")
        if not m or m.get("property") == "minecraft:display_context":
            continue
        d["model"] = {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": "gui", "model": {"type": "minecraft:empty"}}], "fallback": m}
        files[name] = json.dumps(d, indent=1).encode("utf-8")
    # 팩은 접속자 모두에게 가므로 소품 라이선스의 저작자 표시와 라이선스 전문이 맨 위에 꼭 있어야 한다(check_pack.py TOP_FILES 와 같은 목록).
    # 나누면 두 팩 모두에(맨 위 파일은 둘 다에 들어감)
    top = ["CREDITS.txt", "LICENSE-GPL-3.0.txt", "LICENSE-MIT-TAC-GEAR.txt"]
    top += sorted(p.name for p in (ROOT / "tools/props/licenses").glob("*.txt") if p.name not in top)
    packs = list(zip((OUT, SOUND_OUT), split_packs(files))) if SPLIT else [(OUT, files)]
    for _, part in packs:
        missing = [n for n in top if not part.get(n)]
        if missing:
            print(f"팩 묶기 실패: 묶은 zip 맨 위에 {', '.join(missing)} 가 없거나 비었음(소품 층 build/props/pack_layer/ 맨 위, "
                  f"python -X utf8 tools/props/build_props.py). {' 와 '.join(str(p) for p, _ in packs)} 는 그대로 둠", file=sys.stderr)
            sys.exit(1)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    tmps = [(pack_zip(path, part), path) for path, part in packs]
    for tmp, path in tmps:
        tmp.replace(path)
    if SPLIT:
        look, sound = packs[0][1], packs[1][1]
        ogg = sum(1 for n in sound if n.endswith(".ogg"))
        print(f"모습 팩: {OUT} ({OUT.stat().st_size} 바이트, 파일 {len(look)}개, 총 정의 {len(guns)}: {','.join(guns)})")
        print(f"소리 팩: {SOUND_OUT} ({SOUND_OUT.stat().st_size} 바이트, 파일 {len(sound)}개, ogg {ogg}개)")
        return
    if SOUND_OUT.exists():
        # 하나로 묶었는데 옆에 나눈 때의 소리 팩이 남으면 서버가 둘 다 보내 소리가 두 번 든다
        SOUND_OUT.unlink()
        print(f"옛 소리 팩을 지움: {SOUND_OUT} (이번 팩은 소리까지 하나로 묶음)")
    print(f"팩: {OUT} ({OUT.stat().st_size} 바이트, 파일 {len(files)}개, 총 정의 {len(guns)}: {','.join(guns)})")


if __name__ == "__main__":
    main()
