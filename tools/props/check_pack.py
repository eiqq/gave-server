"""소품 층 검사(좀비런 tools/props/check_pack.py 를 이름공간만 바꿔 씀, 처음은 dongwon).

- 아이템 정의 → 모델, tints 형식
- 모델: 알려진 키만, 요소 from/to 범위(-16..32), 회전 형식, 면 texture 는 '#변수'이고 textures 표에 있음, uv 0..16, 면 rotation
- 텍스처: 파일 있음, item/ 또는 block/ 아래(26.3 아이템 아틀라스), 바닐라(minecraft:)는 26.3 클라 jar 에 있음,
  한 모델이 item/ 과 block/ 을 섞지 않음(26.3 클라가 'Multiple atlases used in model' 로 굽지 못함)
- 표시 변환: 문맥 이름, 배율 4 이하, 옮김 80 이하
- 장비: 층 이름, 텍스처 64x32
- 소리: sounds.json 이 가리키는 ogg 가 있고 모노, 자막 키가 ko_kr, en_us 에 있음. 바닐라(minecraft:) 소리는 26.3 자산 목록에 있고 모노인지,
  앞 무음이 0.3 초 넘는 파일은 알림만
- 소리 출처 흔적(경고만, 권리 미확인 자료는 개인 시험용으로 두기로 해서 오류 아님): 층 ogg 와 그 원본(props.json sound_files 의 from, mixed)의
  Vorbis 주석에서 vendor 의 Wwise, ww2ogg(게임 소리 묶음 추출), 태그(TITLE, ARTIST 등)의 상용 게임 이름, 유튜브 DASH 표식
  (major_brand=dash, iso6mp41), XMP 재료 파일 이름. 개수와 파일 목록을 모두 찍는다. 다시 부호화한 층 파일은 주석이 지워져 원본에만 남는다
- 층 맨 위 CREDITS.txt 와 라이선스 전문(LICENSE-*.txt, tools/props/licenses 와 바이트가 같은지): 없으면 오류
- props.json 과 층이 서로 맞음(소리 사건 목록과 변형 수 포함), 안 쓰는 텍스처, 층 전체 크기

python -X utf8 tools/props/check_pack.py [팩 층 폴더]
"""
import json
import os
import re
import struct
import sys
import zipfile
from pathlib import Path

from PIL import Image

import sound_info as SI

ROOT = Path(__file__).resolve().parents[2]
P = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/props/pack_layer"
NS = "gave"
A = P / "assets"
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # tools/mcjar.py 를 부르려고
from mcjar import client_jar  # noqa: E402  # 26.3 클라 jar 를 공식 런처 자리 말고도 찾는다(GV_CLIENT_JAR, fabric-loom 캐시. 공식 자리에 있으면 예전과 같음)
JAR26 = str(client_jar())
VANILLA = set(zipfile.ZipFile(JAR26).namelist()) if Path(JAR26).exists() else None
MC = Path(os.path.expandvars(r"%APPDATA%/.minecraft"))


def vanilla_sound_objects():
    """26.3 자산 목록(소리 파일은 jar 가 아니라 assets/objects 에 있다): 'minecraft/sounds/..ogg' → 해시. 못 읽으면 None"""
    try:
        v = json.loads((MC / "versions/26.3/26.3.json").read_text(encoding="utf-8"))
        return json.loads((MC / f"assets/indexes/{v['assetIndex']['id']}.json").read_text(encoding="utf-8"))["objects"]
    except (OSError, KeyError, ValueError):
        return None


VSOUND = vanilla_sound_objects()
errs, warns, notes = [], [], []

# 소리 출처 흔적(2026-11-10 권리 감사, 좀비런 docs/소품출처.md '받는 길에서 알게 된 것' 1). 오류가 아니라 경고로만 센다(2026-11-10 사용자 결정: 대체하지 않고 개인 시험용으로 둠)
GAMES = [  # (이름, 태그 값에서 찾는 정규식, 대소문자 무시)
    ("Half-Life", r"half[- ]?life"), ("Fortnite", r"fortnite"), ("Rust", r"\brust\b"),
    ("Call of Duty", r"call of duty|modern warfare|black ops|warzone"), ("Left 4 Dead", r"left ?(4|for) ?dead|\bl4d2?\b"),
    ("Counter-Strike", r"counter[- ]?strike|\bcs ?go\b"), ("Battlefield", r"battlefield"), ("PUBG", r"\bpubg\b|battlegrounds"),
    ("Apex Legends", r"apex legends"), ("Overwatch", r"overwatch"), ("Valorant", r"valorant"), ("Escape from Tarkov", r"tarkov"),
    ("Resident Evil", r"resident evil"), ("The Last of Us", r"last of us"), ("DOOM", r"\bdoom\b"), ("Halo", r"\bhalo\b"),
    ("Titanfall", r"titanfall"), ("Insurgency", r"insurgency"), ("Rainbow Six", r"rainbow ?six"), ("Payday", r"payday"),
    ("Grand Theft Auto", r"grand theft auto|\bgta(?: ?[v45])?\b"), ("Killing Floor", r"killing floor"), ("Dying Light", r"dying light"),
    ("World War Z", r"world war z"), ("Back 4 Blood", r"back ?4 ?blood"), ("Team Fortress", r"team fortress|\btf2\b"),
    ("Garry's Mod", r"garry'?s mod|\bgmod\b"), ("Dead Space", r"dead space"), ("Dead Island", r"dead island"), ("Fallout", r"fallout"),
    ("Far Cry", r"far ?cry"), ("S.T.A.L.K.E.R.", r"s\.t\.a\.l\.k\.e\.r|\bstalker 2\b"), ("Hunt: Showdown", r"hunt:? showdown"),
    ("DayZ", r"\bdayz\b"), ("Unturned", r"unturned"), ("Gears of War", r"gears of war"),
    ("Infinity Ward 게임 파일 이름", r"\biw\d+_\w+"),
]
GAME_RE = [(n, re.compile(p, re.I)) for n, p in GAMES]
MARK_KINDS = {"wwise": "Wwise 추출", "game": "게임 이름", "dash": "유튜브 DASH", "xmp": "XMP 재료"}


def ogg_comments(path):
    """ogg 첫 논리 흐름의 Vorbis 주석 머리 → (vendor, [(키, 값)]). Vorbis 가 아니거나 못 읽으면 None(읽기만)"""
    try:
        b = Path(path).read_bytes()
    except OSError:
        return None
    pk, cur, pos, serial = [], b"", 0, None
    while len(pk) < 2 and pos + 27 <= len(b) and b.startswith(b"OggS", pos):
        n = b[pos + 26]
        sn = struct.unpack_from("<I", b, pos + 14)[0]
        serial = sn if serial is None else serial
        data = pos + 27 + n
        for s in b[pos + 27:pos + 27 + n]:
            if sn == serial and len(pk) < 2:
                cur += b[data:data + s]
                if s < 255:  # 255 보다 짧은 조각이 패킷 끝
                    pk.append(cur)
                    cur = b""
            data += s
        pos = data
    if len(pk) < 2 or not pk[1].startswith(b"\x03vorbis"):
        return None
    c, i, tags = pk[1], 7, []
    try:
        vl = struct.unpack_from("<I", c, i)[0]
        vendor = c[i + 4:i + 4 + vl].decode("utf-8", "replace")
        i += 4 + vl
        n = struct.unpack_from("<I", c, i)[0]
        i += 4
        for _ in range(n):
            ln = struct.unpack_from("<I", c, i)[0]
            k, _, v = c[i + 4:i + 4 + ln].decode("utf-8", "replace").partition("=")
            tags.append((k, v))
            i += 4 + ln
    except struct.error:
        return None
    return vendor, tags


def sound_marks(path):
    """출처 흔적 [(종류, 설명)]: vendor 의 Wwise, ww2ogg / 유튜브 DASH 표식 / XMP 재료 파일 이름 / 태그와 XMP 재료의 상용 게임 이름"""
    got = ogg_comments(path)
    if got is None:
        return []
    vendor, tags = got
    out = []
    if re.search(r"wwise|ww2ogg", vendor, re.I):
        out.append(("wwise", f"Wwise 추출(vendor '{vendor}')"))
    dash = [f"{k}={v}" for k, v in tags if (k.lower() == "major_brand" and v.strip().lower() == "dash") or "iso6mp41" in v.lower()]
    if dash:
        out.append(("dash", f"유튜브 DASH 표식({', '.join(dash)})"))
    for k, v in tags:
        if "xmpMM:Ingredients" in v or "stRef:filePath" in v:  # XMP 묶음: 재료 파일 이름만 본다
            files = list(dict.fromkeys(re.findall(r'stRef:filePath(?:="|>)([^"<]+)', v)))
            if files:
                out.append(("xmp", f"XMP 재료({', '.join(files[:4])})"))
            hay, where = " | ".join(files), "XMP 재료"
        else:
            hay, where = v, k
        for name, rx in GAME_RE:
            m = rx.search(hay)
            if m:
                a, z = (0, len(hay)) if len(hay) <= 120 else (max(0, m.start() - 50), m.end() + 50)
                out.append(("game", f"게임 이름 {name}({where} '{'..' * (a > 0)}{hay[a:z].strip()}{'..' * (z < len(hay))}')"))
    return out
MODEL_KEYS = {"parent", "textures", "elements", "display", "gui_light", "ambientocclusion"}
EL_KEYS = {"from", "to", "rotation", "faces", "shade", "light_emission"}
FACE_KEYS = {"uv", "texture", "rotation", "cullface", "tintindex"}
CTX = {"thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "gui", "head", "ground", "fixed", "on_shelf"}


def tex_path(tid, kind="textures"):
    ns, _, p = tid.partition(":") if ":" in tid else ("minecraft", "", tid)
    return ns, p, A / ns / kind / (p + ".png")


def num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


mc = json.loads((P / "pack.mcmeta").read_text(encoding="utf-8"))
if mc.get("pack", {}).get("min_format") != [97, 1]:
    errs.append(f"pack.mcmeta min_format {mc.get('pack', {}).get('min_format')}")

# 층 맨 위 출처 표시와 라이선스 전문: 팩 맨 위에 늘 넣는 라이선스 조건(docs/운영.md '배포할 때'). build_props.py write_credits 가
# CREDITS.txt 를 쓰고 전문은 tools/props/licenses/ 에서 바이트 그대로 복사한다(build_pack.py 가 팩 맨 위로 옮김)
LICENSES = Path(__file__).resolve().parent / "licenses"
TOP_FILES = ["CREDITS.txt", "LICENSE-GPL-3.0.txt", "LICENSE-MIT-TAC-GEAR.txt"]
lic_src = {f.name: f for f in LICENSES.glob("*.txt")} if LICENSES.is_dir() else {}
top = list(dict.fromkeys(TOP_FILES + sorted(lic_src)))
for name in top:
    f = P / name
    if not f.is_file() or f.stat().st_size == 0:
        errs.append(f"층 맨 위 {name} 없음(python -X utf8 tools/props/build_props.py 의 write_credits 가 만듦)")
    elif name in lic_src and f.read_bytes() != lic_src[name].read_bytes():
        errs.append(f"층 맨 위 {name} 가 tools/props/licenses/{name} 와 바이트가 다름(층이 낡음)")
credits_txt = (P / "CREDITS.txt").read_text(encoding="utf-8") if (P / "CREDITS.txt").is_file() else ""
for name in top:
    if credits_txt and name != "CREDITS.txt" and name not in credits_txt:
        errs.append(f"CREDITS.txt 가 라이선스 전문 {name} 를 적지 않음")
notes.append(f"층 맨 위 {', '.join(n for n in top if (P / n).is_file())}")

used = set()
total_el = 0


def leaves(m):
    """아이템 정의 모델 나무의 잎(minecraft:model). select, composite, condition 을 걷는다(새 총, 상점 그림 정의, 2026-11-12)"""
    ty = m.get("type")
    if ty == "minecraft:model":
        yield m
    elif ty == "minecraft:composite":
        for x in m.get("models", []):
            yield from leaves(x)
    elif ty == "minecraft:select":
        for c in m.get("cases", []):
            yield from leaves(c.get("model", {}))
        if "fallback" in m:
            yield from leaves(m["fallback"])
    elif ty == "minecraft:condition":
        yield from leaves(m.get("on_true", {}))
        yield from leaves(m.get("on_false", {}))
    elif ty != "minecraft:empty":
        yield {"type": ty}


checked_models = set()
for item in sorted((A / f"{NS}/items").glob("*.json")):
    j = json.loads(item.read_text(encoding="utf-8"))
    for m in leaves(j.get("model", {})):
        key = item.stem
        if m.get("type") != "minecraft:model":
            errs.append(f"{key}: items 정의에 모르는 type {m.get('type')}"); continue
        for t in m.get("tints", []):
            if t.get("type") == "minecraft:custom_model_data":
                if not isinstance(t.get("index"), int) or not isinstance(t.get("default", 0), int):
                    errs.append(f"{key}: tints 형식 {t}")
            elif t.get("type") != "minecraft:constant" or not isinstance(t.get("value"), int) or not (0 <= t["value"] <= 0xFFFFFF):
                errs.append(f"{key}: tints 형식 {t}")
        if m["model"] in checked_models:
            continue
        checked_models.add(m["model"])
        ns, _, mp = m["model"].partition(":")
        if mp != f"item/{key}":
            key = f"{key}>{mp.split('/', 1)[-1]}"
        mpath = A / ns / "models" / (mp + ".json")
        if not mpath.exists():
            if (ROOT / "pack_src/assets" / ns / "models" / (mp + ".json")).is_file():
                continue  # pack_src 층 모델(상점 뒤판 frame_* 같은 것): 그 층 몫
            errs.append(f"{key}: 모델 파일 없음 {mpath}"); continue
        mj = json.loads(mpath.read_text(encoding="utf-8"))
        extra = set(mj) - MODEL_KEYS
        if extra:
            errs.append(f"{key}: 모델에 모르는 키 {extra}")
        texs = mj.get("textures", {})
        atlases = {tex_path(t)[1].split("/")[0] for t in texs.values() if not t.startswith("#")}
        if len(atlases) > 1:
            errs.append(f"{key}: 한 모델이 아틀라스 {sorted(atlases)} 를 섞음(26.3 클라 'Multiple atlases used in model', 그려지지 않음) {texs}")
        for var, tid in texs.items():
            if tid.startswith("#"):
                continue
            tns, tp_, tp = tex_path(tid)
            if not (tp_.startswith("item/") or tp_.startswith("block/")):
                errs.append(f"{key}: 텍스처 {tid} 가 item/ 이나 block/ 아래가 아님(26.3 아이템 아틀라스)")
            if tns == "minecraft":
                if VANILLA is not None and f"assets/minecraft/textures/{tp_}.png" not in VANILLA:
                    errs.append(f"{key}: 바닐라 텍스처 {tid} 가 26.3 jar 에 없음")
                continue
            if not tp.exists():
                if (ROOT / "pack_src/assets" / tns / "textures" / (tp_ + ".png")).is_file():
                    continue  # pack_src 층 텍스처(총구 불꽃 표식 fx_marker 같은 것)
                errs.append(f"{key}: 텍스처 '{var}' → {tid} 파일 없음"); continue
            used.add(tp.resolve())
        els = mj.get("elements")
        if mj.get("parent"):
            if "layer0" not in texs:
                errs.append(f"{key}: generated 인데 layer0 없음")
            n_el = 0
        else:
            if not els:
                errs.append(f"{key}: 요소 없음"); continue
            n_el = len(els)
            total_el += n_el
            tinted = False
            for i, e in enumerate(els):
                if set(e) - EL_KEYS:
                    errs.append(f"{key}#{i}: 요소에 모르는 키 {set(e) - EL_KEYS}")
                f, t = e.get("from"), e.get("to")
                if not (isinstance(f, list) and isinstance(t, list) and len(f) == 3 and len(t) == 3 and all(map(num, f + t))):
                    errs.append(f"{key}#{i}: from/to 형식"); continue
                for a in range(3):
                    if not (-16 <= f[a] <= 32 and -16 <= t[a] <= 32):
                        errs.append(f"{key}#{i}: from/to 범위 밖 {f} {t}"); break
                    if f[a] > t[a] + 1e-9:
                        errs.append(f"{key}#{i}: from > to {f} {t}"); break
                r = e.get("rotation")
                if r:
                    if "origin" not in r or len(r["origin"]) != 3:
                        errs.append(f"{key}#{i}: rotation origin")
                    if "axis" in r:
                        if r["axis"] not in ("x", "y", "z") or not num(r.get("angle")):
                            errs.append(f"{key}#{i}: rotation axis/angle")
                    elif not all(num(r.get(a, 0)) for a in "xyz"):
                        errs.append(f"{key}#{i}: rotation x,y,z")
                le = e.get("light_emission")
                if le is not None and not (isinstance(le, int) and 0 <= le <= 15):
                    errs.append(f"{key}#{i}: light_emission {le}")
                if not e.get("faces"):
                    warns.append(f"{key}#{i}: 면 없음")
                for fn, fc in e.get("faces", {}).items():
                    if fn not in ("north", "south", "east", "west", "up", "down"):
                        errs.append(f"{key}#{i}: 면 이름 {fn}")
                    if set(fc) - FACE_KEYS:
                        errs.append(f"{key}#{i}.{fn}: 면에 모르는 키 {set(fc) - FACE_KEYS}")
                    tv = fc.get("texture", "")
                    if not tv.startswith("#"):
                        errs.append(f"{key}#{i}.{fn}: texture 가 #변수 아님 ({tv})")
                    elif tv[1:] not in texs:
                        errs.append(f"{key}#{i}.{fn}: {tv} 가 textures 표에 없음")
                    uv = fc.get("uv")
                    if uv is not None and (len(uv) != 4 or not all(num(x) and -1e-6 <= x <= 16 + 1e-6 for x in uv)):
                        errs.append(f"{key}#{i}.{fn}: uv 그림 밖 {uv}")
                    if fc.get("rotation", 0) not in (0, 90, 180, 270):
                        errs.append(f"{key}#{i}.{fn}: 면 rotation {fc.get('rotation')}")
                    if "tintindex" in fc:
                        tinted = True
            if tinted and not m.get("tints"):
                warns.append(f"{key}: tintindex 면이 있는데 아이템 정의에 tints 없음(흰색으로 보임)")
        for ctx, d in mj.get("display", {}).items():
            if ctx not in CTX:
                errs.append(f"{key}: display 문맥 이름 {ctx}")
            sc = d.get("scale", [1, 1, 1]); tr = d.get("translation", [0, 0, 0]); ro = d.get("rotation", [0, 0, 0])
            if len(ro) != 3 or len(sc) != 3 or len(tr) != 3:
                errs.append(f"{key}: display {ctx} 길이"); continue
            if any(abs(x) > 4 for x in sc):
                errs.append(f"{key}: display {ctx} 배율 4 넘음 {sc}")
            if any(abs(x) > 80 for x in tr):
                errs.append(f"{key}: display {ctx} 옮김 80 넘음 {tr}")
        sizes = []
        for tid in sorted(set(v for v in texs.values() if not v.startswith("#") and not v.startswith("minecraft:"))):
            _, _, tp = tex_path(tid)
            if tp.exists():
                im = Image.open(tp); sizes.append(f"{tp.name} {im.size[0]}x{im.size[1]}")
        notes.append(f"아이템 {key:<15} 요소 {n_el:>4}  {', '.join(sizes)}")

# 장비
eqkeys = []
for eq in sorted((A / f"{NS}/equipment").glob("*.json")):
    eqkeys.append(eq.stem)
    j = json.loads(eq.read_text(encoding="utf-8"))
    for layer, lst in j.get("layers", {}).items():
        if layer not in ("humanoid", "humanoid_leggings"):
            errs.append(f"장비 {eq.stem}: 층 {layer}")
        for L in lst:
            ns, _, n = L["texture"].partition(":")
            tp = A / ns / "textures/entity/equipment" / layer / (n + ".png")
            if not tp.exists():
                errs.append(f"장비 {eq.stem}: {tp} 없음")
            else:
                im = Image.open(tp)
                if im.size != (64, 32):
                    errs.append(f"장비 {eq.stem}: {layer} 크기 {im.size}")
                if im.mode != "RGBA":
                    warns.append(f"장비 {eq.stem}: {layer} 모드 {im.mode}")
    notes.append(f"장비 {eq.stem:<16} 층 {', '.join(j.get('layers', {}))}")

# 소리
sj = json.loads((A / f"{NS}/sounds.json").read_text(encoding="utf-8"))
ko = json.loads((A / f"{NS}/lang/ko_kr.json").read_text(encoding="utf-8"))
en = json.loads((A / f"{NS}/lang/en_us.json").read_text(encoding="utf-8"))
sound_files = set()
vanilla_refs = {}
for ev, d in sj.items():
    for s in d.get("sounds", []):
        name = s["name"] if isinstance(s, dict) else s
        if s.get("type", "file") == "event" if isinstance(s, dict) else False:
            continue
        ns, _, p = name.partition(":")
        f = A / ns / "sounds" / (p + ".ogg")
        if ns == "minecraft":
            vanilla_refs.setdefault(p, []).append(ev)
            continue
        if not f.exists():
            errs.append(f"소리 {ev}: {f} 없음"); continue
        sound_files.add(f.resolve())
    sub = d.get("subtitle")
    if sub and (sub not in ko or sub not in en):
        errs.append(f"소리 {ev}: 자막 {sub} 가 lang 에 없음")
long_lead = []
for f in sorted(sound_files):
    i = SI.info(f)
    if i["layout"] != "mono":
        errs.append(f"소리 파일 {f.name}: {i['layout']}(위치 소리는 모노여야 함)")
    ld = SI.lead(f)
    if ld > 0.3:
        long_lead.append(f"{f.relative_to(A / NS / 'sounds').as_posix()} {ld:.2f}초")
for f in (A / f"{NS}/sounds").rglob("*.ogg"):
    if f.resolve() not in sound_files:
        warns.append(f"안 쓰는 소리 파일 {f.relative_to(A)}")
for p, evs in sorted(vanilla_refs.items()):
    key = f"minecraft/sounds/{p}.ogg"
    if VSOUND is None:
        warns.append(f"바닐라 소리 {p}: 26.3 자산 목록을 못 읽어 확인 못 함")
    elif key not in VSOUND:
        errs.append(f"바닐라 소리 {p}({', '.join(evs)}): 26.3 자산 목록에 없음")
    else:
        h = VSOUND[key]["hash"]
        obj = MC / "assets/objects" / h[:2] / h
        if obj.exists() and SI.info(obj)["layout"] != "mono":
            warns.append(f"바닐라 소리 {p}: 스테레오라 위치 없이 들림({', '.join(evs)})")
notes.append(f"소리 사건 {len(sj)}, 파일 {len(sound_files)}(모두 모노 확인), 바닐라 참조 {len(vanilla_refs)}"
             + (f"({', '.join(sorted(vanilla_refs))})" if vanilla_refs else ""))
if long_lead:
    notes.append("앞 무음 0.3초 넘는 파일(알림): " + ", ".join(long_lead))

# props.json 과 맞추기
props = json.loads((ROOT / "tools/props.json").read_text(encoding="utf-8"))
for k, e in props["items"].items():
    if not (A / f"{NS}/items/{k}.json").exists():
        errs.append(f"props.json 아이템 {k}: 아이템 정의 없음")
    if e["item_model"] != f"{NS}:{k}":
        errs.append(f"props.json 아이템 {k}: item_model {e['item_model']}")
for k, e in props["equipment"].items():
    if k not in eqkeys:
        errs.append(f"props.json 장비 {k}: 장비 json 없음")
for ev in props["sounds"]:
    if ev.split(":", 1)[1] not in sj:
        errs.append(f"props.json 소리 {ev}: sounds.json 에 없음")
for ev, d in sj.items():  # Java 는 팩을 읽기 전까지 props.json 의 사건과 변형 수(files)로 소리를 고른다
    pe = props["sounds"].get(f"{NS}:{ev}")
    if pe is None:
        errs.append(f"sounds.json 사건 {ev}: props.json sounds 에 없음")
    elif len(pe.get("files", [])) != len(d.get("sounds", [])):
        errs.append(f"소리 {ev}: props.json files {len(pe.get('files', []))}개, sounds.json 변형 {len(d.get('sounds', []))}개")
for item in (A / f"{NS}/items").glob("*.json"):
    if item.stem not in props["items"]:
        warns.append(f"props.json 에 없는 아이템 {item.stem}")

# 소리 출처 흔적: 층 파일과 그 원본(props.json sound_files 의 from, 섞은 것은 mixed). 바이트 그대로 복사한 파일은 둘이 같고,
# 다시 부호화한 파일은 주석이 지워져 원본에만 남는다
SND = (A / NS / "sounds").resolve()
by_file = {e["file"]: e for e in props.get("sound_files", []) if not e.get("vanilla")}
prov, no_src = {}, []
for f in sorted(sound_files):
    rel = f.relative_to(SND).as_posix()
    lay = sound_marks(f)
    seen = {d for _, d in lay}
    e = by_file.get(rel)
    from_src = []
    for s in ([e["from"]] + e.get("mixed", [])) if e else []:
        mod, _, r = s.partition(": ")
        sp = ROOT / "ref/mods" / mod / "extracted" / r
        if not sp.is_file():
            no_src.append(f"{rel}({s})")
            continue
        for kind, d in sound_marks(sp):
            if d not in seen:
                seen.add(d)
                from_src.append((kind, d, s))
    if e is None:
        no_src.append(f"{rel}(props.json sound_files 에 없음)")
    if lay or from_src:
        prov[rel] = (lay, from_src)
prov_lines = []
for rel, (lay, from_src) in prov.items():
    parts = ["층 파일에 " + "; ".join(d for _, d in lay)] if lay else []
    for s in dict.fromkeys(x[2] for x in from_src):
        parts.append(f"원본 {s} 에 " + "; ".join(d for _, d, ss in from_src if ss == s))
    prov_lines.append(f"  {rel}: " + " / ".join(parts))
prov_count = {k: sum(1 for lay, fs in prov.values() if any(x[0] == k for x in lay + fs)) for k in MARK_KINDS}
if no_src:
    notes.append("원본을 못 찾아 층 파일 주석만 본 소리(출처 흔적): " + ", ".join(no_src))

# 안 쓰는 텍스처, 크기
for tp in (A / f"{NS}/textures/item").rglob("*.png"):
    if tp.resolve() not in used:
        warns.append(f"안 쓰는 아이템 텍스처 {tp.relative_to(A)}")
total = sum(f.stat().st_size for f in P.rglob("*") if f.is_file())
by = {}
for f in P.rglob("*"):
    if f.is_file():
        k = f.suffix
        by[k] = by.get(k, 0) + f.stat().st_size
notes.append(f"층 전체 {total / 1024 / 1024:.2f} MB ({', '.join(f'{k} {v / 1024:.0f}KB' for k, v in sorted(by.items()))}), 모델 요소 합 {total_el}")
# 팩은 zip(deflate)으로 내려가니 압축한 크기로 잰다(모델 json 이 길어도 잘 눌림). 풀었을 때 크기는 위 notes 에 적음
import io, zipfile
_zb = io.BytesIO()
with zipfile.ZipFile(_zb, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as _z:
    for f in P.rglob("*"):
        if f.is_file():
            _z.write(f, f.relative_to(P).as_posix())
# 아이템 아틀라스 넓이: gave/textures/item 의 텍스처 화소 합(바닐라 items 아틀라스는 한 변 최대 하드웨어 한도, 보통 16384, 넉넉히 8192 로 봄)
try:
    from PIL import Image as _Im
    _px, _big = 0, 0
    for tp in (A / f"{NS}/textures/item").rglob("*.png"):
        with _Im.open(tp) as _im:
            _w, _h = _im.size
        _px += _w * _h
        _big += (_w > 512 or _h > 512)
    _side = int(_px ** 0.5)
    notes.append(f"아이템 텍스처 {_px / 1e6:.1f} 백만 화소(정사각으로 펴면 한 변 약 {_side}, 밉맵 여백 별도), 512 넘는 것 {_big}장")
    if _side * 1.15 > 8192:
        warns.append(f"아이템 텍스처 합이 한 변 {_side} 쯤(8192 에 가까움)")
except Exception as _e:
    notes.append(f"텍스처 넓이 못 셈 {_e}")
notes.append(f"층 압축 {len(_zb.getvalue()) / 1024 / 1024:.2f} MB")
if len(_zb.getvalue()) > 15 * 1024 * 1024:
    errs.append(f"층이 압축해서 15 MB 넘음 {len(_zb.getvalue())}")

print("\n".join(notes))
if prov:
    print(f"\n소리 출처 흔적 경고 {len(prov)}개 파일(층 소리 파일 {len(sound_files)}개 중. "
          + ", ".join(f"{MARK_KINDS[k]} {v}" for k, v in prov_count.items() if v)
          + "). 개인 시험용으로 두기로 해서 오류가 아님, 공개 전에는 바꿀 것(좀비런 docs/소품출처.md '권리 감사', 게이브 docs/운영.md):")
    print("\n".join(prov_lines))
print(f"\n오류 {len(errs)}, 경고 {len(warns) + len(prov)}" + (f"(소리 출처 흔적 {len(prov)} 포함)" if prov else ""))
for e in errs[:80]:
    print("  오류:", e)
for w in warns[:40]:
    print("  경고:", w)
sys.exit(1 if errs else 0)
