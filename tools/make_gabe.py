"""게이브 몹 팩 층: tools/gabe/sp/gxx.py 종 스크립트를 모두 불러 build/gabe/pack_layer/ 를 만든다.

python -X utf8 tools/make_gabe.py                 모든 종(바뀐 것만이 아니라 전부 다시)
python -X utf8 tools/make_gabe.py --only g05,g06  그 종만 다시 굽고 층을 다시 모음(다른 종은 지난 조각 그대로)
python -X utf8 tools/make_gabe.py --only g05 --preview   build/gabe/preview/g05.png 미리보기도(상태마다 정면, 옆, 위 + 자세 띠 + 변이)

종 스크립트 꼴(docs/모듈/종작성법.md):
    from gabe import kit
    def build(k): ...      모델, 상태, 텍스처, 아이콘, 덮개
    def sounds(k): ...     k.sound(...)
    def phrases(): return {"cause_g01": "..."}

나오는 것
- build/gabe/sp/<종>/        종마다 조각(팩 파일, looks, 소리, 자막, 아이콘, 덮개). --only 로 그 종만 새로 굽는다.
                             build/gabe/sp/common/ 은 엔진 공용 자산(tools/gabe/common.py: 소리 gabe.bite, gabe.split, 보탠 크리처 소리
                             gabe.cry_* 등(tools/gabe/flesh.py), 래그돌 사체의 막대기 마디 모델 gave:gabe/common_rod).
                             common.py 나 flesh.py 가 조각보다 새로우면 --only 일 때도 다시 굽는다.
                             data/gabe_voice.json(종마다 보탠 소리 별칭)을 고치면 종 전체를 다시 굽는다
- build/gabe/pack_layer/     모든 조각을 합친 층(build_pack.py 가 소품 층 다음, pack_src 앞에 얹음)
    assets/gave/items/gabe/<모델>.json, models/item/gabe/*.json, textures/item/gabe/**.png, sounds/gabe/**.ogg,
    sounds.json(소리 사건 gabe.<종>.<이름>), lang/ko_kr.json, en_us.json(자막), gabe_looks.json(모델별 틴트 칸 역할표, Look.java 가 읽음)
    래그돌 사체(docs/모듈/래그돌.md): 키트가 모델마다 저절로 사체 상태 <상태>.c(막대기 뺀 몸 + 누운 막대기 자세)를 더 굽고
    gabe_looks.json 의 rag 에 몸 상자, 흐물 막대기, 누운 자세를 적는다(종 스크립트는 고칠 것 없음, 요약 줄 끝 '사체 [...]')
- build/gabe/icons/<종>.png   HUD 아이콘 64x64(make_hud)
- build/gabe/covers/<키>.png, build/gabe/covers.json   화면 덮개(make_hud)
- data/phrases/<종 대문자>.json  종 글귀(make_hud)
한 종 스크립트가 실패해도 나머지로 층을 만들고 끝에 실패 목록과 함께 종료 코드 1. 여러 사람이 같이 돌려도 되게 운영체제 잠금으로 줄을 세운다.
"""
import argparse
import importlib
import json
import msvcrt
import os
import re
import shutil
import sys
import time
import traceback
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
sys.path.insert(0, str(TOOLS))

from gabe import audio, kit  # noqa: E402

OUT = ROOT / "build/gabe"
FRAG = OUT / "sp"
LAYER = OUT / "pack_layer"
ICONS = OUT / "icons"
COVERS = OUT / "covers"
PREVIEW = OUT / "preview"
PHRASES = ROOT / "data/phrases"
LOCK = ROOT / "build/locks/make_gabe.lock"
KEY = re.compile(r"^[a-z0-9_-]+$")
SPF = re.compile(r"^(g\d{2,3}|b[1-5]|horizon)$")  # 2026-11-12: 새 종 G56..G155, 보스 b1..b5, 압도감 지평선 떼(horizon, 종 아님)


def lock():
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(LOCK, os.O_RDWR | os.O_CREAT)
    t0 = time.time()
    while True:
        try:
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            return fd
        except OSError:
            if time.time() - t0 > 600:
                sys.exit("make_gabe 잠금을 10분 기다렸지만 못 얻음(build/locks/make_gabe.lock)")
            time.sleep(0.5)


def bestiary():
    d = json.loads((ROOT / "data/bestiary.json").read_text(encoding="utf-8"))
    return {s["id"]: s for s in d["species"]}


def scripts():
    return sorted(p.stem for p in (TOOLS / "gabe/sp").glob("*.py") if SPF.match(p.stem))


def write_fragment(key, info, preview=False):
    """종 하나를 구워 build/gabe/sp/<종>/ 에 조각으로 → 요약 글"""
    mod = importlib.import_module("gabe.common" if key == "common" else f"gabe.sp.{key}")
    importlib.reload(mod)
    k = kit.Kit(key, info)
    if hasattr(mod, "build"):
        mod.build(k)
    if hasattr(mod, "sounds"):
        mod.sounds(k)
    phr = mod.phrases() if hasattr(mod, "phrases") else None
    files, looks, icon = k.finish()
    tmp = FRAG / f"{key}.new"
    shutil.rmtree(tmp, ignore_errors=True)
    (tmp / "files").mkdir(parents=True)
    for rel, data in files.items():
        p = tmp / "files" / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, np.ndarray):
            audio.write_ogg(data, p)
        else:
            p.write_bytes(data)
    (tmp / "looks.json").write_text(json.dumps(looks, ensure_ascii=False, indent=1), encoding="utf-8")
    (tmp / "sounds.json").write_text(json.dumps(k.sounds_json, ensure_ascii=False, indent=1), encoding="utf-8")
    (tmp / "lang.json").write_text(json.dumps(k.lang, ensure_ascii=False, indent=1), encoding="utf-8")
    if icon is not None:
        icon.save(tmp / "icon.png")
    if k.covers:
        (tmp / "covers").mkdir()
        for ck, im in k.covers.items():
            im.save(tmp / "covers" / f"{ck}.png")
    if phr is not None:
        bad = [kk for kk in phr if not KEY.match(kk)]
        if bad:
            raise ValueError(f"글귀 키는 소문자, 숫자, _, - 만: {bad}")
        if any(kit.MIDDLE_DOT in v for v in phr.values()):
            raise ValueError("글귀에 가운뎃점 금지")
        (tmp / "phrases.json").write_text(json.dumps(phr, ensure_ascii=False, indent=1), encoding="utf-8")
    old = FRAG / key
    shutil.rmtree(old, ignore_errors=True)
    tmp.rename(old)
    if phr is not None:
        PHRASES.mkdir(parents=True, exist_ok=True)
        (PHRASES / f"{key.upper()}.json").write_text(json.dumps(phr, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    if preview and k.models:
        write_preview(k)
    if key == "common":
        return f"common: 소리 {len(k.sounds_json)}"
    n_slots = sum(len(v["slots"]) for v in looks.values())
    # 요약은 종이 지은 상태만(키트가 저절로 만든 래그돌 사체 상태 <상태>.r, <상태>.c 는 '사체' 에 따로 셈)
    own = {m: [n for n in v["states"] if "." not in n] for m, v in looks.items()}
    n_el = sum(len(s.elems) for m in k.models.values() for n, s in m.states.items() if "." not in n)
    quads = ", ".join(f"{m}:{n or '-'} {v['quads'][n]}" for m, v in looks.items() for n in own[m])
    rag = [(len(e.get("faces", ())), len(e.get("rods", ()))) for v in looks.values() for e in v.get("rag", {}).values() if e.get("corpse")]
    corpse = f"사체 [상태 {len(rag)}" + (f", 누운 자세 {min(r[0] for r in rag)}, 흐물 막대기 {max(r[1] for r in rag)}" if rag else "") + "]"
    return f"{key}: 모델 {len(looks)}, 상태 {sum(len(s) for s in own.values())}, 요소 {n_el}, 틴트 칸 {n_slots}, " \
           f"면 [{quads}], 텍스처 {len(k.textures)}, 소리 {len(k.sounds_json)}, 덮개 {len(k.covers)}, {corpse}"


def write_preview(k):
    """build/gabe/preview/<종>.png: 모델마다 [상태마다 정면, 옆, 위 + 자세 띠] + [기본 상태 변이: 실눈 뜸, 변이 1, 변이 2, 가까운 얼굴]
    + (래그돌 사체 상태가 있으면) [기본 상태의 사체: 얼굴이 하늘을 볼 때 위에서, 바로 섰을 때 위에서, 구르는 동안(막대기 뺀 몸) 정면
    + 누운 자세 띠]"""
    from gabe import render
    rows = []
    for m in k.models.values():
        for sname, s in m.states.items():
            if "." in sname:
                continue
            big = []
            for view in ("front", "side", "top"):
                els = []
                for kk in range(s.poses):
                    els += k.visible(m, sname, kk)
                fit = render.bounds(els, view)
                im, _ = k.render(m, sname, 0, view=view, size=192, fit=fit, bg=(46, 60, 46, 255))
                big.append(im)
            fit = render.bounds(sum((k.visible(m, sname, kk) for kk in range(s.poses)), []), "front")
            strip = [k.render(m, sname, kk, view="front", size=96, fit=fit, bg=(56, 70, 56, 255))[0] for kk in range(s.poses)]
            rows.append((big, strip))
        looks = [kit.LookState(open=True), kit.LookState(mut=1), kit.LookState(mut=2)]
        var = [k.render(m, m.default, 0, look=lk, view="front", size=192, bg=(60, 46, 46, 255))[0] for lk in looks]
        var.append(k.render(m, m.default, 0, view="front", size=192, near=True, bg=(60, 46, 60, 255))[0])
        rows.append((var, []))
        rag = m.rag_info.get(m.default, {})
        if rag.get("faces"):
            cs, faces_ = rag["corpse"], rag["faces"]
            # 누운 자세마다 위에서 내려다본 그림: 면 2(얼굴이 하늘) = 정면에서, 면 0(바로 섬) = 위에서. 그 밖의 면은 옆에서(참고만)
            view_of = {2: "front", 0: "top", 3: "back"}
            lie = [kit.LookState(freeze=True, pose=vi) for vi in range(len(faces_) + 1)]
            big = [k.render(m, cs, vi, look=lie[vi], view=view_of.get(f, "front"), size=192, bg=(40, 40, 60, 255))[0]
                   for vi, f in enumerate(faces_[:2])]
            big.append(k.render(m, cs, len(faces_), look=lie[-1], view="front", size=192, bg=(40, 40, 60, 255))[0])
            strip = [k.render(m, cs, vi, look=lie[vi], view=view_of.get(f, "side"), size=96, bg=(50, 50, 70, 255))[0]
                     for vi, f in enumerate(faces_)]
            rows.append((big, strip))
    W = max(sum(i.width + 4 for i in big) + max(1, (len(strip) + 1) // 2) * 100 for big, strip in rows)
    H = sum(196 for _ in rows)
    sheet = Image.new("RGBA", (W, H), (24, 24, 24, 255))
    y = 0
    for big, strip in rows:
        x = 0
        for im in big:
            sheet.alpha_composite(im, (x, y))
            x += im.width + 4
        for i, im in enumerate(strip):
            sheet.alpha_composite(im, (x + (i // 2) * 100, y + (i % 2) * 98))
        y += 196
    PREVIEW.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW / f"{k.key}.png")


def assemble():
    """조각을 모두 합쳐 pack_layer, icons, covers 를 새로 쓴다(바뀌치기)"""
    keys = [p.name for p in sorted(FRAG.iterdir()) if p.is_dir() and (SPF.match(p.name) or p.name == "common")] if FRAG.is_dir() else []
    alive = set(scripts()) | {"common"}
    keys = [k for k in keys if k in alive]
    files, sources = {}, {}
    sounds, lang, looks, covers = {}, {}, {}, {}
    problems = []
    for key in keys:
        d = FRAG / key
        for f in sorted((d / "files").rglob("*")):
            if not f.is_file():
                continue
            rel = f.relative_to(d / "files").as_posix()
            data = f.read_bytes()
            if rel in files and files[rel] != data:
                problems.append(f"{rel}: {sources[rel]} 과 {key} 의 내용이 다름(공용 텍스처는 같은 그림이어야 함)")
                continue
            files[rel] = data
            sources.setdefault(rel, key)
        for name, dst in (("sounds.json", sounds), ("lang.json", lang), ("looks.json", looks)):
            p = d / name
            if p.is_file():
                part = json.loads(p.read_text(encoding="utf-8"))
                dup = set(part) & set(dst)
                if dup:
                    problems.append(f"{key} {name}: 다른 종과 같은 키 {sorted(dup)[:3]}")
                if name == "looks.json":
                    old = [m for m, v in part.items() if v.get("v") != kit.LOOKS_VERSION]
                    if old:
                        problems.append(f"{key}: 옛 키트로 구운 조각({', '.join(old)}), --only {key} 로 다시 구울 것")
                dst.update(part)
        if (d / "covers").is_dir():
            for c in sorted((d / "covers").glob("*.png")):
                if c.stem in covers:
                    problems.append(f"덮개 키 {c.stem} 가 {covers[c.stem]['sp']} 와 {key} 에 둘 다")
                    continue
                im = Image.open(c)
                covers[c.stem] = {"sp": key.upper(), "file": f"covers/{c.name}", "w": im.width, "h": im.height, "src": c}
    files["assets/gave/sounds.json"] = json.dumps(sounds, ensure_ascii=False, indent=1).encode("utf-8")
    for lg in ("ko_kr", "en_us"):
        files[f"assets/gave/lang/{lg}.json"] = json.dumps(lang, ensure_ascii=False, indent=1).encode("utf-8")
    files["assets/gave/gabe_looks.json"] = json.dumps({"version": kit.LOOKS_VERSION, "periods": list(kit.PERIODS), "models": looks},
                                                      ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    new = OUT / "pack_layer.new"
    shutil.rmtree(new, ignore_errors=True)
    for rel, data in files.items():
        p = new / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
    _swap(new, LAYER)
    # 아이콘, 덮개
    icons_new = OUT / "icons.new"
    shutil.rmtree(icons_new, ignore_errors=True)
    icons_new.mkdir(parents=True)
    for key in keys:
        ic = FRAG / key / "icon.png"
        if ic.is_file():
            shutil.copy2(ic, icons_new / f"{key}.png")
    _swap(icons_new, ICONS)
    covers_new = OUT / "covers.new"
    shutil.rmtree(covers_new, ignore_errors=True)
    covers_new.mkdir(parents=True)
    for ck, c in covers.items():
        shutil.copy2(c.pop("src"), covers_new / f"{ck}.png")
    _swap(covers_new, COVERS)
    (OUT / "covers.json").write_text(json.dumps(covers, ensure_ascii=False, indent=1), encoding="utf-8")
    return keys, len(files), problems


def _swap(new, dst):
    old = dst.with_name(dst.name + f".old{os.getpid()}")
    for attempt in range(20):
        try:
            if dst.exists():
                dst.rename(old)
            new.rename(dst)
            break
        except OSError:
            if old.exists() and not dst.exists():
                old.rename(dst)
            time.sleep(0.5)
    else:
        raise SystemExit(f"{dst} 를 바꿔치지 못함(다른 프로그램이 잡고 있음)")
    shutil.rmtree(old, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="쉼표로 종 번호(g05,g06). 없으면 전부")
    ap.add_argument("--preview", action="store_true", help="build/gabe/preview/<종>.png 도")
    a = ap.parse_args()
    fd = lock()
    t0 = time.time()
    info = bestiary()
    all_keys = scripts()
    only = [s.strip().lower() for s in a.only.split(",")] if a.only else all_keys
    missing = [s for s in only if s not in all_keys]
    if missing:
        sys.exit(f"종 스크립트 없음: {missing} (tools/gabe/sp/<종>.py)")
    fails = []
    FRAG.mkdir(parents=True, exist_ok=True)
    # 엔진 공용 자산(tools/gabe/common.py: 공용 소리, 래그돌 막대기 마디 모델): 전부 구울 때, 조각이 없거나 common.py 보다 낡았을 때
    common_out = FRAG / "common" / "looks.json"
    newest = max((TOOLS / "gabe" / f).stat().st_mtime for f in ("common.py", "flesh.py"))
    if not a.only or not common_out.is_file() or common_out.stat().st_mtime < newest:
        only = ["common"] + only
    for key in only:
        try:
            print(write_fragment(key, info.get(key.upper(), {}), a.preview), flush=True)
        except Exception:
            fails.append(key)
            print(f"{key}: 실패\n" + traceback.format_exc(), flush=True)
            shutil.rmtree(FRAG / f"{key}.new", ignore_errors=True)
    keys, n, problems = assemble()
    for p in problems:
        print("문제:", p)
    print(f"게이브 층: 종 {len(keys)}({','.join(keys)}), 파일 {n}개 → {LAYER} ({time.time() - t0:.1f}초)")
    os.close(fd)
    if fails or problems:
        print("실패한 종:", ",".join(fails) or "없음")
        sys.exit(1)


if __name__ == "__main__":
    main()
