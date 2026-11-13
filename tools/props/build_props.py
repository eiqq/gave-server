"""게이브 소품 리소스팩 층 만들기: 모드에서 꺼낸 총 모델, 텍스처, 소리(ref/mods/*/extracted)를 26.3 바닐라 형식으로 바꿔
build/props/pack_layer/ (이름공간 gave) 와 tools/props.json 을 쓴다. 직접 칠하는 텍스처는 없다.

python -X utf8 tools/props/build_props.py
좀비런 tools/props/build_props.py 를 고쳐 쓴 것: 총 다섯(+ _nomag), 수류탄(손, 던진 것), 탄약통과 그 소리만 남기고 좀비 스킨, 장비 층,
도시 소품, 수류탄 상자는 뺐다. 입력 ref/mods/{timeless-and-classics-guns, just-enough-guns, tac-gear}/extracted 는 좀비런에서 복사해 왔다
(jar 를 다시 받아 꺼내는 도구는 좀비런 tools/props/fetch_modrinth.py, extract_sources.py).
"""
import json
import math
import shutil
import subprocess
from pathlib import Path

import imageio_ffmpeg
import numpy as np
from PIL import Image

import vmodel as V

ROOT = Path(__file__).resolve().parents[2]
REF = ROOT / "ref/mods"
OUT_FINAL = ROOT / "build/props/pack_layer"
# 새로 만드는 동안은 옆 폴더에 쓰고 끝에 바꿔치기(다른 사람의 팩 묶기가 반쯤 지워진 층을 읽지 않게, 2026-11-12)
OUT = ROOT / "build/props/pack_layer.new"
NS = "gave"
A = OUT / f"assets/{NS}"

# ------------------------------------------------------------------ 쓰기 도우미

written = {}


def put_json(rel, obj):
    p = OUT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    if "/models/" in rel:  # 모델은 요소가 많아 줄바꿈 없이
        p.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    else:
        p.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")
    written[rel] = p


def put_png(rel, img_or_bytes):
    p = OUT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(img_or_bytes, (bytes, bytearray)):
        p.write_bytes(img_or_bytes)
    else:
        img_or_bytes.save(p, "PNG", optimize=True)
    written[rel] = p


def item_def(key, tints=None):
    m = {"type": "minecraft:model", "model": f"{NS}:item/{key}"}
    if tints:
        m["tints"] = tints
    put_json(f"assets/{NS}/items/{key}.json", {"model": m})


def r(v, n=4):
    return [round(float(x), n) + 0.0 for x in v]

# ------------------------------------------------------------------ 모델 읽기(꺼내 둔 것만)


def tex_file(tid):
    """텍스처 id → 꺼내 둔 png 경로(없으면 None, 바닐라는 None)"""
    ns, _, p = tid.partition(":") if ":" in tid else ("minecraft", "", tid)
    if ns == "minecraft":
        return None
    for d in REF.glob(f"*/extracted/assets/{ns}/textures"):
        f = d / (p + ".png")
        if f.exists():
            return f
    return None


def model_file(mid):
    ns, _, p = mid.partition(":")
    for d in REF.glob(f"*/extracted/assets/{ns}/models"):
        f = d / (p + ".json")
        if f.exists():
            return f
    raise FileNotFoundError(mid)


def load_model(mid, keep_tint=False, tex_sub=None):
    """부모 사슬을 펴서 (요소, 면 texture 를 '@텍스처 id' 로 푼 것), display 를 돌려준다.
    tintindex 는 keep_tint 일 때만 남긴다(아이템 정의에 tints 가 없으면 뜻이 없음).
    tex_sub: 텍스처 id 바꿔 끼우기(바닐라 블록 텍스처 → 같은 모드 텍스처 등, 26.3 아틀라스 섞임 피하기)"""
    texs, display, els = {}, {}, None
    chain = []
    cur = mid
    while cur and not cur.startswith(("minecraft:", "item/", "block/", "builtin/")):
        j = json.loads(model_file(cur).read_text(encoding="utf-8-sig"))
        chain.append(j)
        cur = j.get("parent")
    for j in reversed(chain):
        texs.update(j.get("textures", {}))
        display.update(j.get("display", {}))
        if "elements" in j:
            els = j["elements"]

    def res(v, d=0):
        while isinstance(v, str) and v.startswith("#") and d < 10:
            v = texs.get(v[1:], v); d += 1
        if isinstance(v, str) and ":" not in v and not v.startswith("#"):
            v = "minecraft:" + v
        return v
    out = []
    for e in els or []:
        e2 = {k: json.loads(json.dumps(v)) for k, v in e.items() if k in ("from", "to", "rotation", "faces", "shade", "light_emission")}
        faces = {}
        for fn, f in e2.get("faces", {}).items():
            tid = res(f.get("texture", "#missing"))
            if tid.startswith("#"):
                continue  # 풀리지 않는 텍스처(missing) 면은 뺀다
            tid = (tex_sub or {}).get(tid, tid)
            f2 = {k: v for k, v in f.items() if k in (("uv", "rotation", "tintindex") if keep_tint else ("uv", "rotation"))}
            f2["texture"] = "@" + tid
            if "uv" not in f2:
                lo, hi = np.array(e2["from"], float), np.array(e2["to"], float)
                f2["uv"] = V.default_uv(fn, lo, hi)
            faces[fn] = f2
        if not faces:
            continue
        e2["faces"] = faces
        rot = e2.get("rotation")
        if rot and "axis" in rot and abs(rot.get("angle", 0)) < 1e-9:
            e2.pop("rotation")
        out.append(e2)
    return out, display


def parts(mids, offsets=None):
    els = []
    for i, m in enumerate(mids):
        e, _ = load_model(m)
        if offsets and offsets.get(m) is not None:
            e = V.transform(e, 1.0, offsets[m])
        els += e
    return els


UV_FIXED = {}


def fix_uv(key, elements):
    """26.3 은 그림 밖 uv 를 못 굽는다: 통째로 밖이면 16 단위로 밀어 넣고, 걸치면 자른다"""
    n = 0
    for e in elements:
        for f in e.get("faces", {}).values():
            u = list(map(float, f["uv"]))
            for ax in (0, 1):
                a, b = u[ax], u[ax + 2]
                lo, hi = min(a, b), max(a, b)
                if lo < -1e-9 or hi > 16 + 1e-9:
                    sh = -16 * np.floor(lo / 16) if hi - lo <= 16 else 0
                    if lo + sh < 0 or hi + sh > 16:
                        a2, b2 = min(max(a + sh, 0), 16), min(max(b + sh, 0), 16)
                    else:
                        a2, b2 = a + sh, b + sh
                    u[ax], u[ax + 2] = a2, b2
                    n += 1
            f["uv"] = [round(x, 5) + 0.0 for x in u]
    if n:
        UV_FIXED[key] = UV_FIXED.get(key, 0) + n
    return elements

# ------------------------------------------------------------------ 텍스처 다시 모으기(손실 없음)


def repack(faces, img, pad=1):
    """faces(면 dict 목록, uv 0..16)가 쓰는 사각형만 모아 작은 그림으로(픽셀 그대로, 테두리 pad 픽셀 같이). 면 uv 를 고쳐 쓰고 새 그림을 돌려준다"""
    img = img.convert("RGBA")
    W, H = img.size
    rects = {}
    for f in faces:
        u = f["uv"]
        x0, x1 = sorted([u[0] * W / 16, u[2] * W / 16])
        y0, y1 = sorted([u[1] * H / 16, u[3] * H / 16])
        fx0, fy0 = int(np.floor(x0 + 1e-6)), int(np.floor(y0 + 1e-6))
        rects[(fx0, fy0, max(int(np.ceil(x1 - 1e-6)), fx0 + 1), max(int(np.ceil(y1 - 1e-6)), fy0 + 1))] = None
    merged = []
    for k in sorted(rects, key=lambda k: (k[0], k[1])):
        merged.append(k)
    changed = True
    while changed:
        changed = False
        for i in range(len(merged)):
            for j in range(i + 1, len(merged)):
                a, b = merged[i], merged[j]
                if not (a[2] + pad <= b[0] - pad or a[0] - pad >= b[2] + pad or a[3] + pad <= b[1] - pad or a[1] - pad >= b[3] + pad):
                    merged[i] = (min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
                    merged.pop(j); changed = True; break
            if changed:
                break
    used = sum((m[2] - m[0] + 2 * pad) * (m[3] - m[1] + 2 * pad) for m in merged)
    items = sorted(merged, key=lambda m: (-(m[3] - m[1]), -(m[2] - m[0])))
    side = 16
    while side * side < used:
        side *= 2
    while True:
        pos, x, y, shelf, ok = {}, 0, 0, 0, True
        for m in items:
            w, h = m[2] - m[0] + 2 * pad, m[3] - m[1] + 2 * pad
            if w > side:
                ok = False; break
            if x + w > side:
                x, y, shelf = 0, y + shelf, 0
            if y + h > side:
                ok = False; break
            pos[m] = (x + pad, y + pad)
            x += w; shelf = max(shelf, h)
        if ok:
            break
        side *= 2
    Hn = side
    used_h = y + shelf
    while Hn // 2 >= used_h and Hn > 16:
        Hn //= 2
    if side * Hn >= W * H:  # 줄지 않으면 원본 그대로
        return img, False
    out = Image.new("RGBA", (side, Hn), (0, 0, 0, 0))
    for m, (px, py) in pos.items():
        sx0, sy0 = max(m[0] - pad, 0), max(m[1] - pad, 0)
        src = img.crop((sx0, sy0, min(m[2] + pad, W), min(m[3] + pad, H)))
        out.paste(src, (px - (m[0] - sx0), py - (m[1] - sy0)))

    def owner(x0, y0, x1, y1):
        for m in merged:
            if m[0] <= x0 + 1e-6 and m[1] <= y0 + 1e-6 and x1 <= m[2] + 1e-6 and y1 <= m[3] + 1e-6:
                return m
        raise RuntimeError("uv 사각형 못 찾음")
    for f in faces:
        u = f["uv"]
        ux = [u[0] * W / 16, u[2] * W / 16]
        uy = [u[1] * H / 16, u[3] * H / 16]
        x0 = int(np.floor(min(ux) + 1e-6)); y0 = int(np.floor(min(uy) + 1e-6))
        m = owner(x0, y0, max(max(ux), x0 + 1) if max(ux) - min(ux) < 1e-6 else max(ux), max(max(uy), y0 + 1) if max(uy) - min(uy) < 1e-6 else max(uy))
        px, py = pos[m]
        f["uv"] = [round((ux[0] - m[0] + px) * 16 / side, 5), round((uy[0] - m[1] + py) * 16 / Hn, 5),
                   round((ux[1] - m[0] + px) * 16 / side, 5), round((uy[1] - m[1] + py) * 16 / Hn, 5)]
    return out, True


REPACKED = {}


def finish_textures(key, elements, names=None, pack=True):
    """면 texture '@id' → '#변수'. 모드 텍스처는 쓰는 조각만 모아(손실 없음) textures/item/<key>/ 에 쓰고, 바닐라는 그대로 가리킨다"""
    ids = []
    for e in elements:
        for f in e["faces"].values():
            t = f["texture"][1:]
            if t not in ids:
                ids.append(t)
    textures = {}
    for i, tid in enumerate(ids):
        var = str(i)
        faces = [f for e in elements for f in e["faces"].values() if f["texture"] == "@" + tid]
        for f in faces:
            f["texture"] = "#" + var
        fp = tex_file(tid)
        if fp is None:
            if not tid.startswith("minecraft:"):
                raise FileNotFoundError(tid)
            textures[var] = tid  # 바닐라 블록 텍스처(팩에 넣지 않음)
            continue
        img = Image.open(fp)
        name = (names or {}).get(tid) or tid.split("/")[-1]
        before = img.size
        if pack and not Path(str(fp) + ".mcmeta").exists():
            img, did = repack(faces, img)
            if did:
                REPACKED[f"{key}/{name}"] = (before, img.size)
        put_png(f"assets/{NS}/textures/item/{key}/{name}.png", img)
        meta = Path(str(fp) + ".mcmeta")
        if meta.is_file():  # 움직이는 텍스처(애니메이션 띠): 모드 mcmeta 를 바이트 그대로(2026-11-12, 새 무기 냉동총, 레일건)
            mp = OUT / f"assets/{NS}/textures/item/{key}/{name}.png.mcmeta"
            mp.write_bytes(meta.read_bytes())
            written[f"assets/{NS}/textures/item/{key}/{name}.png.mcmeta"] = mp
        textures[var] = f"{NS}:item/{key}/{name}"
    textures["particle"] = textures["0"]
    # 26.3 클라는 한 모델이 아이템(item/)과 블록(block/) 아틀라스를 섞으면 굽지 못한다(Multiple atlases used in model)
    atlases = {t.partition(":")[2].split("/")[0] for t in textures.values()}
    if len(atlases) > 1:
        raise RuntimeError(f"{key}: 텍스처 아틀라스가 섞임 {textures}. load_model(tex_sub=...) 로 바닐라 블록 텍스처를 모드 텍스처로 바꿀 것")
    return textures


TEXMAP = {}  # 키 → (원래 텍스처 id → 변수, textures 표)


def write_model(key, elements, display, names=None, pack=True, tints=None, share_tex_of=None):
    els = fix_uv(key, V.clean(json.loads(json.dumps(elements))))
    if share_tex_of:  # 다른 키의 텍스처를 그대로 가리킨다(같은 그림을 두 번 넣지 않음, uv 도 다시 모은 그림 기준으로 바꿔 둔 것을 씀)
        raise RuntimeError("share_tex_of 는 write_variant 로")
    ids = []
    for e in els:
        for f in e["faces"].values():
            if f["texture"][1:] not in ids:
                ids.append(f["texture"][1:])
    tex = finish_textures(key, els, names, pack)
    TEXMAP[key] = ({tid: str(i) for i, tid in enumerate(ids)}, tex)
    m = {"textures": tex, "elements": els, "display": display}
    put_json(f"assets/{NS}/models/item/{key}.json", m)
    item_def(key, tints)
    return m


def write_variant(key, base_key, keep, display=None, transform=None):
    """base_key 모델(텍스처 다시 모은 뒤)의 요소 일부(keep(i) 가 참인 것)로 변종을 만든다. 텍스처는 base 것을 그대로 가리킨다"""
    base = json.loads((A / f"models/item/{base_key}.json").read_text(encoding="utf-8"))
    els = [e for i, e in enumerate(base["elements"]) if keep(i)]
    if transform:
        els = V.clean(transform(els))
    m = {"textures": base["textures"], "elements": els, "display": display or base["display"]}
    put_json(f"assets/{NS}/models/item/{key}.json", m)
    item_def(key)
    return m

# ------------------------------------------------------------------ 표시 변환 계산


def disp_euler(R):
    """R = Rx Ry Rz(표시 변환 순서) → (x, y, z) 도"""
    y = math.asin(max(-1.0, min(1.0, R[0, 2])))
    if abs(math.cos(y)) > 1e-6:
        x = math.atan2(-R[1, 2], R[2, 2])
        z = math.atan2(-R[0, 1], R[0, 0])
    else:
        x, z = math.atan2(R[2, 1], R[1, 1]), 0.0
    return [round(math.degrees(v), 3) + 0.0 for v in (x, y, z)]


def D(deg):
    return math.radians(deg)


FP_ORIGIN = np.array([0.56, -0.52, -0.72])
TP_HAND = np.array([0.0, -2.0, 1.5])  # 아이템 원점(손 앞 아래 모서리)에서 주먹 가운데까지(아이템 쪽 픽셀)


def tp_grip(G, s, R=None, nudge=(0, 0, 0)):
    """3인칭 오른손: 기본 '아이템 든 팔'(아래로, 앞 18도) 자세에서 총구(-Z)가 수평 앞, 위(+Y)가 위, 손잡이 G 가 주먹 안"""
    R = V.rx(D(72)) if R is None else R
    t = TP_HAND + np.array(nudge, float) - R @ (s * (np.array(G, float) - 8))
    return {"rotation": disp_euler(R), "translation": r(t, 3), "scale": [s, s, s]}


def fp_grip(G, s, at, yaw=4.0, pitch=0.0, roll=0.0):
    """1인칭 오른손: 손잡이 G 를 카메라 공간 at(블록)에 두고 총구를 화면 가운데 쪽으로 yaw 도"""
    R = V.rx(D(pitch)) @ V.ry(D(yaw)) @ V.rz(D(roll))
    t = 16 * (np.array(at, float) - FP_ORIGIN) - R @ (s * (np.array(G, float) - 8))
    return {"rotation": disp_euler(R), "translation": r(t, 3), "scale": [s, s, s]}


def fit_view(elements, R, size=15.0, nudge=(0, 0, 0), max_scale=4.0):
    """회전 R 뒤 모델 상자가 한 변 size 픽셀(칸 16)에 들어가게 배율을 정하고 가운데로"""
    P = np.concatenate([P for e in elements for _, P, _, _ in V.face_quads(e)]) - 8
    Q = P @ R.T
    lo, hi = Q.min(0), Q.max(0)
    s = min(size / max(hi[0] - lo[0], 1e-6), size / max(hi[1] - lo[1], 1e-6), max_scale)
    c = (lo + hi) / 2 * s
    t = -c + np.array(nudge, float)
    t[2] = 0.0
    return {"rotation": disp_euler(R), "translation": r(t, 3), "scale": [round(s, 4)] * 3}


def gun_display(els, G, tp_scale, fp_scale, fp_at, fp_yaw=4.0, fp_pitch=0.0, tp_nudge=(0, 0, 0)):
    L = V.vbounds(els)
    length = L[1][2] - L[0][2]
    d = {
        "thirdperson_righthand": tp_grip(G, tp_scale, nudge=tp_nudge),
        "firstperson_righthand": fp_grip(G, fp_scale, fp_at, fp_yaw, fp_pitch),
        # 인벤토리: 오른쪽 옆모습을 총구가 오른쪽 위로 45도
        "gui": fit_view(els, V.rz(D(45)) @ V.ry(D(-90)), 17.0),
        # 바닥: 바로 선 채 길이 0.55 블록쯤
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [round(min(9.0 / length, 1.0), 4)] * 3},
        # 액자: 액자가 반 바퀴 돌려 그리므로 Ry(90)이면 오른쪽 옆모습, 총구 오른쪽
        "fixed": fit_view(els, V.ry(D(90)), 15.0),
    }
    d["thirdperson_lefthand"] = d["thirdperson_righthand"]
    d["firstperson_lefthand"] = d["firstperson_righthand"]
    return d


def recenter(els, extra_points=()):
    """보이는 범위 가운데를 (8,8,8) 로(배율 그대로). 옮긴 양을 돌려준다"""
    lo, hi = V.vbounds(els)
    off = 8 - (lo + hi) / 2
    return V.transform(els, 1.0, off), off

# ------------------------------------------------------------------ 출처 기록


SOURCES = {}  # 키 → 출처


def src(key, mod, url, version, license_, detail, **kw):
    SOURCES[key] = dict(mod=mod, url=url, version=version, license=license_, detail=detail, **kw)


TAC = ("Timeless and Classics Guns (Serene Wave Studio, ClumsyAlien)", "https://modrinth.com/mod/timeless-and-classics-guns", "0.3.14.2 (Forge 1.16.5)",
       "에셋 CC BY-NC-SA 4.0(Modrinth 본문), 코드 GPL-3.0-or-later")

# ------------------------------------------------------------------ 총


def tac(n):
    return f"tac:special/{n}"


GUNS = {
    # 키: 부품, 탄창 부품, 손잡이 자리 G(원래 TaC 좌표), 3인칭 배율, 1인칭 배율, 1인칭 손잡이 자리(카메라 공간 블록), 설명
    "rifle": dict(parts=["m4", "m4_bolt", "m4_muzzle_default", "m4_rail_default", "m4_carry", "m4_stock_light"], mag="m4_mag_standard",
                  G=(8, 6.8, 16.8), tp=0.44, fp=0.42, at=(0.33, -0.33, -0.58), real="M4A1 카빈(0.84 m)",
                  detail="M4A1(몸통 + 노리쇠 + 기본 총구 + 기본 총열 덮개 + 운반 손잡이 + 가벼운 개머리판 + 30발 탄창). 반사 빛 판(m4_sight, m4_bolt_handle) 과 부착물은 뺌"),
    "shotgun": dict(parts=["m870_classic", "m870_classic_pump"], mag=None,
                    G=(8, 6.8, 18.5), tp=0.40, fp=0.40, at=(0.33, -0.33, -0.58), real="레밍턴 M870 펌프 산탄총(1.0 m)",
                    detail="M870 클래식(나무 개머리판) + 펌프 손잡이. 탄창 없음(관형)"),
    "pistol": dict(parts=["glock_17", "glock_17_slide"], mag="glock_17_mag_standard",
                   G=(8, 5.5, 13.6), tp=0.33, fp=0.34, at=(0.30, -0.30, -0.60), real="글록 17(0.19 m)",
                   detail="글록 17(몸통 + 슬라이드 + 17발 탄창)"),
    "smg": dict(parts=["hk_mp5a5", "hk_mp5a5_bolt", "hk_mp5a5_bolt_handle", "hk_mp5a5_stock_default", "hk_mp5a5_rail_default"], mag="hk_mp5a5_mag_standard",
                G=(8, 6.8, 14.6), tp=0.56, fp=0.50, at=(0.32, -0.32, -0.58), real="H&K MP5A5(0.68 m)",
                detail="MP5A5(몸통 + 노리쇠 + 장전 손잡이 + 기본 개머리판 + 기본 총열 덮개 + 30발 탄창)"),
    "sniper": dict(parts=["ai_awp", "ai_awp_bolt"], mag="ai_awp_mag_standard", scope=True,
                   G=(8, 4.8, 20.0), tp=0.46, fp=0.40, at=(0.33, -0.33, -0.56), real="AI AWP(1.18 m) + 8 배 조준경",
                   detail="AI AWP(몸통 + 노리쇠 + 탄창) + TaC 8x_scope(Raythean 8 배 조준경, 받침째) 를 기관부 위에 얹음. 가늠쇠(ai_awp_sight) 는 조준경과 겹쳐 뺌"),
}


def scope_offset(gun_els, scope_els):
    """조준경 받침 바닥을 기관부 윗면에, 받침 가운데를 기관부 위 가운데에"""
    top = []
    for e in gun_els:
        for _, P, _, _ in V.face_quads(e):
            top.append(P)
    P = np.concatenate(top)
    ymax = P[:, 1].max()
    near = P[P[:, 1] > ymax - 0.3]
    zc = (near[:, 2].min() + near[:, 2].max()) / 2
    S = np.concatenate([P2 for e in scope_els for _, P2, _, _ in V.face_quads(e)])
    s_lo = S[:, 1].min()
    s_base = S[S[:, 1] < s_lo + 1.2]
    sz = (s_base[:, 2].min() + s_base[:, 2].max()) / 2
    sx = (S[:, 0].min() + S[:, 0].max()) / 2
    return np.array([8 - sx, ymax - s_lo, zc - sz])


def build_gun(key, spec):
    els = parts([tac(n) for n in spec["parts"]])
    if spec.get("scope"):
        sc = parts(["tac:item/8x_scope"])
        off = scope_offset(els, sc)
        sc = V.transform(sc, 1.0, off)
        els += sc
    mag = parts([tac(spec["mag"])]) if spec.get("mag") else []
    full = els + mag
    full, off = recenter(full)
    G = np.array(spec["G"], float) + off
    disp = gun_display(full, G, spec["tp"], spec["fp"], spec["at"])
    m = write_model(key, full, disp)
    detail = spec["detail"] + f", 요소 {len(full)}"
    src(key, *TAC, detail, real=spec["real"], grip=r(G, 3))
    if mag:
        n = len(els)
        write_variant(f"{key}_nomag", key, lambda i: i < n)
        src(f"{key}_nomag", *TAC, f"{key} 에서 탄창 부품만 뺀 것(재장전 연출용, 표시 변환과 좌표 같음, 같은 텍스처), 요소 {n}", variant_of=key)
    return m


GRENADE_DETAIL = "TaC light_grenade(Mk2 파인애플 세열 수류탄, 안전손잡이와 고리)"


def build_grenade():
    els, tdisp = load_model("tac:item/light_grenade")
    els, off = recenter(els)
    lo, hi = V.vbounds(els)
    h = hi[1] - lo[1]
    G = np.array([8, 8, 8.0])
    s_tp = round(0.25 * 16 / h, 4)  # 손에 든 높이 0.25 블록(실물 11 cm 보다 크게, 주먹 크기)
    disp = {
        # 3인칭: 총과 같은 [72,0,0] 이면 모델 위(+Y)가 위. 주먹 앞면 바로 앞에 쥔 모습(주먹 안에 묻히지 않게 앞으로 3, 아래로 2 픽셀)
        "thirdperson_righthand": tp_grip((8, 8, 8), s_tp, nudge=(0, 3.0, -2.0)),
        "firstperson_righthand": fp_grip(G, round(0.22 * 16 / h, 4), (0.36, -0.28, -0.64), yaw=-20, pitch=0),
        "gui": fit_view(els, V.rx(D(15)) @ V.ry(D(-35)), 14.0),
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [round(0.25 * 16 / h, 4)] * 3},
        "fixed": fit_view(els, V.ry(D(180)), 13.0),
        "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    }
    disp["thirdperson_lefthand"] = disp["thirdperson_righthand"]
    disp["firstperson_lefthand"] = disp["firstperson_righthand"]
    write_model("grenade", els, disp)
    src("grenade", *TAC, GRENADE_DETAIL + f" 손에 드는 것, 요소 {len(els)}", real="Mk2 수류탄(11 cm, 손에서는 약 0.17 블록)")
    # 던진 수류탄: item_display(none) 에 그대로. 높이 0.22 블록, 가운데 (8,8,8)
    k = 0.22 * 16 / h
    t_els = V.transform(els, k, 8 - 8 * k)
    tdisp2 = {"gui": fit_view(t_els, V.rx(D(15)) @ V.ry(D(-35)), 14.0),
              "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
              "ground": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    write_variant("grenade_thrown", "grenade", lambda i: True, tdisp2, transform=lambda e: V.transform(e, k, 8 - 8 * k))
    src("grenade_thrown", *TAC, GRENADE_DETAIL + " 날아가는 것. 높이 0.22 블록 실제 크기, 가운데 (8,8,8) = 엔티티 자리",
        world=True, center=True, variant_of="grenade")

# ------------------------------------------------------------------ 월드 소품 공통


WORLD_DISPLAY_BASE = {
    "gui": None, "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.3, 0.3, 0.3]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.3, 0.3, 0.3]},
}


def world_prop(key, els, factor=1.0, front_rot=None, gui_rot=None, tints=None, names=None):
    """실제 크기 월드 소품. factor: 모델을 1/factor 로 줄여 넣고 fixed 표시 변환 배율 factor 로 되돌림(범위 -16..32 를 넘는 큰 것).
    모델 (8,8,8) = 엔티티 자리, 가로 가운데, 바닥 = 엔티티 y - 0.5(실제 크기에서). 앞 = 북(-Z)"""
    if front_rot is not None:
        els = V.rotate_all(els, front_rot, (8, 8, 8))
    lo, hi = V.vbounds(els)
    k = 1.0 / factor
    c = (lo + hi) / 2
    # 실제 크기 바닥 y_real = -0.5 블록 → 모델 y = 8 - 8/factor
    off = np.array([8 - k * c[0], (8 - 8 * k) - k * lo[1], 8 - k * c[2]])
    els = V.transform(els, k, off)
    lo2, hi2 = V.vbounds(els)
    assert lo2.min() >= -16 - 1e-6 and hi2.max() <= 32 + 1e-6, (key, lo2, hi2)
    disp = {k_: v for k_, v in WORLD_DISPLAY_BASE.items() if v}
    disp["fixed"] = {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [factor] * 3}
    disp["gui"] = fit_view(els, gui_rot if gui_rot is not None else V.rx(D(25)) @ V.ry(D(-145)), 15.0)
    for kk in ("thirdperson", "firstperson"):
        disp[f"{kk}_lefthand"] = disp[f"{kk}_righthand"]
    write_model(key, els, disp, names=names, tints=tints)
    size = (hi2 - lo2) / 16 * factor
    return [round(float(x), 2) for x in size]


def build_ammo_box():
    els, _ = load_model("tac:item/nato_556_box_block")
    size = world_prop("ammo_box", els)
    src("ammo_box", *TAC, f"TaC nato_556_box_block(5.56 mm 탄약통 블록, 올리브 철제 탄약통, 손잡이), 요소 {len(els)}, 모드 블록 크기 그대로",
        world=True, size_blocks=size)


# ------------------------------------------------------------------ 본체


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    put_json("pack.mcmeta", {"pack": {"description": "gave 소품 층(모드 에셋 변환, CREDITS.txt)", "min_format": [97, 1], "max_format": 97}})
    for k, spec in GUNS.items():
        build_gun(k, spec)
    build_grenade()
    build_ammo_box()
    import build_arms  # 새 무기 12종, 로켓, 무기 상자, 상점 무기 그림, 새 총 아이템 정의(2026-11-12 wpn12)
    build_arms.run(globals())
    import build_more
    build_more.run(globals())
    write_props_json()
    write_credits()
    old = OUT_FINAL.with_name("pack_layer.old")
    if old.exists():
        shutil.rmtree(old)
    if OUT_FINAL.exists():
        OUT_FINAL.rename(old)
    OUT.rename(OUT_FINAL)
    shutil.rmtree(old, ignore_errors=True)
    print(len(written), "파일 →", OUT_FINAL)
    print("그림 밖 uv 고침:", UV_FIXED)
    print("텍스처 다시 모음:", {k: f"{a[0]}x{a[1]}→{b[0]}x{b[1]}" for k, (a, b) in REPACKED.items()})


# ------------------------------------------------------------------ 출처 표시(CREDITS.txt)와 라이선스 전문
# 근거와 확인 절차: 좀비런 docs/소품출처.md '권리 감사'(2026-11-10, 같은 모드 파일을 그대로 가져옴). 이 팩은 아직 공개 배포 금지(개인 시험용, docs/운영.md)

LICENSES = Path(__file__).resolve().parent / "licenses"  # 라이선스 전문. 빌드가 층 맨 위로 바이트 그대로 복사한다
LICENSE_FILES = {  # 파일 → 누구 몫. 하나라도 없으면 빌드를 멈춘다(CREDITS.txt 가 가리키므로)
    "LICENSE-GPL-3.0.txt": "GNU General Public License 3.0 전문(https://www.gnu.org/licenses/gpl-3.0.txt 와 같은 바이트). [1] TaC 몫",
    "LICENSE-MIT-TAC-GEAR.txt": "MIT 전문, Copyright (c) GF2022. [2] TAC GEAR 몫(수류탄 소리)",
}
MODIFIED = "2026-11-12"  # 모드 에셋을 고친 날(수정 고지). 고치는 방식을 바꾸면 이 날짜도 바꿀 것(2026-11-12 wpn_max: 무기 모델 표 214 모델)
BY_NC_SA = "https://creativecommons.org/licenses/by-nc-sa/4.0/"
CC0 = "https://creativecommons.org/publicdomain/zero/1.0/"
CREDIT_GROUPS = [  # SOURCES 의 mod 앞머리 → CREDITS 절. 새 출처를 더하면 여기와 write_credits 에 절을 더할 것
    ("Timeless and Classics Guns", "tac"), ("Just Enough Guns", "jeg"), ("Scorched Guns 2", "sg2"), ("TAC GEAR", "tacgear")]
# 다른 묶음(소리 담당 등)이 덧붙일 CREDITS 절: 이 폴더의 *.txt 를 이름 차례로 맨 끝에 그대로 붙인다(파일 하나 = 절 하나, '[번호] 제목' 줄로 시작)
CREDITS_EXTRA = Path(__file__).resolve().parent / "credits_extra"
GALMURI = "Galmuri11 Bold(Lee Minseo, https://github.com/quiple/galmuri), SIL Open Font License 1.1"
# 출처가 확인된 소리: props.json sound_files 의 from → (원본 sha256, 근거). 원본 바이트가 다르거나 다른 소리를 섞었으면 확인 안 된 것으로 센다.
# 나머지는 모드의 CC0, MIT 표기를 믿을 근거가 없어 '출처 미확인'
SOUND_OK = {
    "just-enough-guns: assets/jeg/sounds/item/grenade/pin.ogg": (
        "5684695e043db78fffa29f6389ee23ead50b4bf5dc10553436688823b29b06a9",
        "MrCrayfish's Gun Mod(MrCrayfish) 원본 assets/cgm/sounds/item/grenade/pin.ogg 와 바이트 같음 https://github.com/MrCrayfish/MrCrayfishGunMod , "
        "CGM sounds/SOUND-LICENSE.txt 'All sounds are licensed under Creative Commons 0'"),
    "just-enough-guns: assets/jeg/sounds/item/pistol/fire.ogg": (
        "a762c6dc820b1db5369960c0f609ed5ee9757abbe13920faca1bea80a963a15b",
        "MrCrayfish's Gun Mod 원본 assets/cgm/sounds/item/pistol/fire.ogg 와 바이트 같음, CGM CC0"),
}
SOUND_MOD = {  # 소리 모드 → (짧은 이름, 이름과 주소). build_more.SRC_NAME 의 'CC0 소리' 표기는 믿을 수 없어 쓰지 않는다
    "timeless-and-classics-guns": ("TaC", "Timeless and Classics Guns 0.3.14.2([1]) https://modrinth.com/mod/timeless-and-classics-guns"),
    "just-enough-guns": ("Just Enough Guns", "Just Enough Guns 0.14.5(MigaMi, jar mods.toml credits 'MrCrayfish, Bomb787, NineZero', 모드 라이선스 표기 "
                                             "Modrinth GPL-2.0-or-later, jar GNU GPLv3) https://modrinth.com/mod/just-enough-guns"),
    "tac-gear": ("TAC GEAR", "TAC GEAR 0.7.1(GF2022, [2]) https://modrinth.com/mod/tac-gear"),
}
SOUND_DOUBT = {  # 모드별 '출처 미확인' 근거
    "timeless-and-classics-guns": "jar sounds/SOUND-LICENSE.txt(CC0)는 MrCrayfish's Gun Mod 문구를 물려받은 것('Cgm -> TaC' 커밋 때 생김)이다. "
                                  "animation 소리 대부분의 XMP 재료(xmpMM:Ingredients)에 날짜 시각 이름의 mp4 가 남아 있고, glock17_reload_empty 에는 "
                                  "Call of Duty: Modern Warfare(2019) 파일 이름 iw8_phys_mag_drop_ak_metal_dirt_05.wav 가 재료로 적혀 있다",
    "just-enough-guns": "jar sounds/SOUND-LICENSE.txt(CC0)는 MrCrayfish's Gun Mod 의 같은 파일과 바이트가 같다(물려받은 문구). 주석(TITLE, ARTIST)에 "
                        "Half-Life 2 Remastered, Fortnite, Rust 같은 게임 이름과 유튜브 효과음 채널 이름이, 여러 파일에 유튜브 DASH 표식(iso6mp41)이 남아 있다",
    "tac-gear": "모드 표기는 MIT 지만 grenade_pinpull, grenade_spooneject 의 Vorbis vendor 가 'converted from Audiokinetic Wwise by ww2ogg 0.22'"
                "(상용 게임의 Wwise 소리 묶음에서 뽑은 흔적)이고, grenade_explode_1 은 출처 기록이 없다",
}


def write_credits():
    """팩 층 맨 위 CREDITS.txt(배포 금지 표시, 저작자 표시, 라이선스 주소, 수정 고지)와 라이선스 전문(LICENSES 의 파일을 바이트 그대로).
    게임은 이 파일들을 읽지 않는다. build_pack.py 는 층 파일을 모두 팩에 넣는다(층 pack.mcmeta 만 뺌)"""
    import hashlib
    for name in LICENSE_FILES:
        f = LICENSES / name
        if not f.is_file():
            raise FileNotFoundError(f"라이선스 전문 {f} 가 없다(CREDITS.txt 가 가리킴)")
        shutil.copyfile(f, OUT / name)
        written[name] = OUT / name
    groups, loose = {}, []
    for k, s_ in SOURCES.items():
        if s_.get("sound"):
            continue
        g = next((gid for pre, gid in CREDIT_GROUPS if s_["mod"].startswith(pre)), None)
        (groups.setdefault(g, []) if g else loose).append(k)
    sound_log = globals().get("SOUND_LOG", [])
    ok, doubt = [], {}
    for e in sound_log:
        if e.get("vanilla"):
            continue
        mod, _, rel = e.get("from", "").partition(": ")
        hit = SOUND_OK.get(e.get("from", ""))
        f = REF / mod / "extracted" / rel
        if mod.startswith("sfx/weapons/"):
            # 직접 받은 CC0 소리(2026-11-12 wpn12): 출처 폴더 _info.json 의 파일 sha256 과 라이선스로 확인
            src_dir = ROOT / "ref" / mod
            info = json.loads((src_dir / "_info.json").read_text(encoding="utf-8")) if (src_dir / "_info.json").is_file() else {}
            fp = src_dir / rel
            if info.get("license") == "CC0-1.0" and fp.is_file() and info.get("files", {}).get(rel) == hashlib.sha256(fp.read_bytes()).hexdigest():
                ok.append((e, f"{info.get('title')}, {info.get('author')}, CC0 1.0 {info.get('url')} ({info.get('license_note', '')})"))
            else:
                doubt.setdefault(mod, []).append(e)
        elif hit and "섞음" not in e.get("process", "") and f.is_file() and hashlib.sha256(f.read_bytes()).hexdigest() == hit[0]:
            ok.append((e, hit[1]))
        else:
            doubt.setdefault(mod, []).append(e)
    n_files = len(ok) + sum(len(v) for v in doubt.values())
    n_doubt = n_files - len(ok)

    def keys(g, note=None):
        out = [f"    - {k}: {SOURCES[k]['detail']}" + (f". {note[k]}" if note and k in note else "") for k in groups.get(g, [])]
        return out or ["    - (이 층에 없음)"]

    L = ["게이브 리소스팩 출처와 라이선스(CREDITS.txt)",
         f"고친 날 {MODIFIED}. tools/props/build_props.py 가 만드는 파일이라 손으로 고치지 말 것. 운영과 배포 규칙은 docs/운영.md.",
         "",
         "=" * 72,
         "알림(2026-11-11): 서버 운영자의 결정으로 이 팩을 공개 저장소에 올린다. 팬이 만든 비상업 팩이며 Mojang, Microsoft 와 관계없다.",
         "- 게이브 몹 얼굴은 마인크래프트 플레이어 _Gave 의 스킨 얼굴을 본뜬 것이다([3]).",
         f"- 팩에 든 총, 수류탄 소리 파일 {n_files}개 중 {n_doubt}개는 출처가 확인되지 않았다([5]). 권리자가 요청하면 내린다.",
         "- 음악은 Kevin MacLeod(incompetech.com), CC BY 4.0([7]).",
         "Notice: fan-made, non-commercial resource pack, not affiliated with Mojang or Microsoft. The Gave mob face is based on the",
         "Minecraft skin of player _Gave. The provenance of most gun sounds (taken from the mods listed below) is not confirmed;",
         "content will be removed at a rights holder's request.",
         "=" * 72,
         "",
         "라이선스 전문(팩 맨 위, tools/props/licenses/ 에서 바이트 그대로 복사):"]
    L += [f"- {n}: {d}" for n, d in LICENSE_FILES.items()]
    L += [f"- CC BY-NC-SA 4.0 은 주소로 알린다: {BY_NC_SA} (법적 전문 {BY_NC_SA}legalcode)",
          f"- CC0 1.0: {CC0}",
          "",
          f"수정 고지: 아래 모드 에셋은 원본 그대로가 아니다. {MODIFIED} 게이브(좀비런의 변환 도구를 이어 씀)가 Minecraft 26.3 리소스팩 형식"
          "(아이템 모델, 아이템 정의, sounds.json)으로 옮기며 바꿨다.",
          "- 모델: 부품을 골라 합침, 크기 맞춤(26.3 좌표 범위 -16..32 안으로), 가운데와 앞 방향을 옮김, 표시 변환을 새로 적음,",
          "  26.3 이 못 굽는 그림 밖 uv 를 고침, 탄창 부품을 뺀 변종(*_nomag)을 더함. 팩을 묶을 때(tools/build_pack.py) 1인칭 몸짓용 사본",
          "  (models/item/kick/*)과 총구 불꽃 모델(muzzle_*)을 더함.",
          "- 텍스처: 쓰는 조각만 모아 다시 배치(픽셀 그대로, 크기만 줄임).",
          "- 소리: 스테레오를 모노로 바꿈, 긴 꼬리와 앞 무음을 자름, libvorbis q7 다시 부호화(파일마다 [5]).",
          "- HUD(pack_src): 총, 수류탄 모델의 옆모습 실루엣을 만듦([4]).",
          "바꾼 것도 원본과 같은 조건으로만 나눈다. 원작자가 이 팩을 만들거나 승인한 것이 아니다.",
          "",
          "[1] Timeless and Classics Guns(TaC) 0.3.14.2 (Forge 1.16.5)",
          "  https://modrinth.com/mod/timeless-and-classics-guns",
          "  https://www.curseforge.com/minecraft/mc-mods/timeless-and-classic-guns-tac",
          "  https://github.com/ClumsyAlien/TimelessandClassics_Reforged",
          "  제작: Modrinth 'created by Timeless Squad and creator NekoCrane', jar mods.toml authors 'Serene Wave Studio'.",
          "  CurseForge(TheRealClumsyAlien) 크레딧, Primary developers: ClumsyAlien (Java Dev / Founder), NekoCrane (Lead Artist / Founder),",
          "  Chris Linefield (Java Dev Lead), Receke (Artist), Mayday (Animation Dev/Java Dev), alex_n_italy (Artist), Hatt (Artist), Mecha (Artist),",
          "  Pos_2333 (Artist), Spacebar (Lead Animation Designer), Mr_K (Animation Designer), IronCurtain (Sound Engineer), Gaint_Salted_Fish (Java Dev),",
          "  TaoismDeepLake (Java Dev), Fiz (Java Dev/Data Scientist). Contributors: Kacper (Sound Engineer / Assistant Modeler), OddlyDelta (Animator),",
          '  Bomb787 (Java dev/Gun "Expert"), Danny (Modeler), DarkSoul38 (Modeler), LesRaisins Studio (Addon dev).',
          f"  라이선스 표기가 엇갈린다: Modrinth 본문 'All the assets are licensed under the terms of the CC BY-NC-SA 4.0'({BY_NC_SA}),",
          '  Modrinth 라이선스 칸 GPL-3.0-or-later, CurseForge "GNU General Public License version 3 (GPLv3)", jar mods.toml license="GPL3".',
          "  두 조건을 모두 지킨다: CC BY-NC-SA 4.0(저작자 표시, 비상업, 바꾼 것도 같은 라이선스로)과 GPL-3.0(전문 LICENSE-GPL-3.0.txt,",
          "  수정 고지와 날짜, 바꾼 것도 GPL-3.0 으로, 고칠 수 있는 형식인 JSON, PNG 그대로).",
          "  이 층의 키(모델, 텍스처):"]
    L += keys("tac")
    L += ["  pack_src HUD 글리프 중 총 실루엣 19장과 수류탄 1장도 TaC 2차 저작물이다([4]). TaC 소리는 [5].",
          "",
          "[2] TAC GEAR 0.7.1 (Forge 1.20.1), GF2022",
          "  https://modrinth.com/mod/tac-gear",
          "  라이선스 MIT(Modrinth, jar mods.toml). Copyright (c) GF2022, 전문 LICENSE-MIT-TAC-GEAR.txt.",
          "  이 팩은 TAC GEAR 의 수류탄 소리와 나무 상자 모델, 텍스처(무기 랜덤 상자 그림)를 쓴다. 소리는 모드의 MIT 표기를 믿을 근거가 없다([5]).",
          "  이 층의 키(모델, 텍스처):"]
    L += keys("tacgear")
    L += ["",
          "[3] 게이브 몹 얼굴(게이브 몹 층 build/gabe/pack_layer, tools/make_gabe.py 가 만듦)",
          "  마인크래프트 플레이어 _Gave 의 스킨 얼굴(8x8)을 본떠 만든 모델과 텍스처다. 2026-11-11 서버 운영자의 결정으로 공개 팩에 넣었다.",
          "  본인이 원하지 않으면 얼굴을 다른 자료로 바꾼다.",
          "",
          "[4] pack_src HUD 글리프(tools/make_hud.py 가 이 층을 읽어 그림, assets/gave/textures/font/hud/)",
          "  - gun_l_*, gun_m_*: 소품 층 총 모델([1] TaC, [8] Just Enough Guns, [9] Scorched Guns 2, *_nomag 은 탄창 뺀 것)을 옆에서 그린",
          "    실루엣. 그 모델의 조건 그대로.",
          "  - nade: [1] TaC light_grenade 의 옆모습 실루엣. [1] 조건 그대로.",
          f"  - 한국어 글귀 그림(data/phrases 의 글귀, 종 이름): {GALMURI} 글꼴로 그린 글자 그림이다.",
          "    글꼴 파일은 팩에 넣지 않는다(OFL 1.1 은 글꼴로 만든 문서, 그림에 조건을 걸지 않는다). 글꼴 Copyright (c) 2019-2025 Lee Minseo.",
          "  - 그 밖의 HUD 글리프(막대, 고리, 적중 표시, 점, 무한, 판, 줄, 칸)는 make_hud.py 가 그린 신호 그림이다.",
          "",
          f"[5] 소리(assets/gave/sounds, 팩에 든 파일 {n_files}개)",
          f"  출처가 확인된 것 {len(ok)}개:"]
    for e, why in ok:
        mod, _, rel = e["from"].partition(": ")
        L.append(f"    - {e['file']}: {SOUND_MOD.get(mod, (mod,))[0]} {rel}. {why}. 처리: {e.get('process', '')}")
    L.append("  (새 무기 소리 sfx/weapons 는 [10] 절의 CC0 묶음에서 직접 받은 것)")
    L.append(f"  출처 미확인 {n_doubt}개(모드의 CC0, MIT 표기를 믿을 근거가 없음, 2026-11-11 운영자 결정으로 공개 팩에 넣음, 권리자가 요청하면 내림):")
    for mod, lst in doubt.items():
        L.append(f"  - {SOUND_MOD.get(mod, (mod, mod))[1]} 에서 {len(lst)}개: {SOUND_DOUBT.get(mod, '출처 기록 없음')}.")
        L += [f"    - {e['file']}: {e.get('from', '').partition(': ')[2]}. 처리: {e.get('process', '')}" for e in lst]
    van = [e["file"] for e in sound_log if e.get("vanilla")]
    L += ["",
          "[6] Minecraft 바닐라 에셋(Mojang)",
          "  - 바닐라 소리 참조(팩에 파일 없음, sounds.json 이 가리키기만 함): " + (", ".join(van) if van else "없음") + ".",
          "  - pack_src: core 셰이더 item.vsh, item.fsh, text.vsh, text.fsh 는 바닐라 26.3 셰이더를 고친 것. HUD 글자는 바닐라 font/ascii.png 를",
          "    가리키기만 한다. 숨긴 바닐라 HUD 그림(하트, 배고픔, 갑옷, 공기, 경험치 막대, 핫바, 보스 막대)은 모두 투명 그림이다.",
          "  Minecraft 에셋은 Mojang 것이다(Minecraft EULA, 사용 지침). NOT AN OFFICIAL MINECRAFT PRODUCT.",
          "  NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."]
    # [7] 배경 음악(CC BY 곡 저작자 표시): tools/make_bgm.py 가 같은 줄을 만든다(그 도구는 이 층의 CREDITS.txt 를 바로 고쳐 쓰기도 함)
    import importlib.util
    spec = importlib.util.spec_from_file_location("make_bgm", ROOT / "tools/make_bgm.py")
    bgm = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bgm)
    L += [""] + bgm.music_credit_lines()
    L += ["",
          "[8] Just Enough Guns 0.14.5 (Forge 1.20.1), MigaMi",
          "  https://modrinth.com/mod/just-enough-guns  https://github.com/MigaMiDev/Just-Enough-Guns",
          "  jar mods.toml credits 'MrCrayfish, Bomb787, NineZero'(MrCrayfish's Gun Mod 를 이어 만든 모드).",
          "  라이선스: Modrinth GPL-2.0-or-later, jar mods.toml license=\"GNU GPLv3\". GPL-3.0 으로 따른다(전문 LICENSE-GPL-3.0.txt).",
          "  모델, 텍스처(models/special/gun, textures/item)를 위 수정 고지대로 옮겼다. 소리는 [5].",
          "  이 층의 키(모델, 텍스처):"]
    L += keys("jeg")
    L += ["",
          "[9] Scorched Guns 2 0.5.5 (Forge 1.20.1), ribs",
          "  https://modrinth.com/mod/scorched-guns-2  https://github.com/ribs498/Scorched-Guns-1.20.1",
          "  jar mods.toml authors 'ribs', credits 'MrCrayfish, Ribs'. 라이선스: Modrinth GPL-3.0-or-later, jar mods.toml license=\"GNU GPLv3\"",
          "  (전문 LICENSE-GPL-3.0.txt). 모델, 텍스처(models/special/<총>/ 부품)를 위 수정 고지대로 옮겼다. 소리는 쓰지 않는다.",
          "  이 층의 키(모델, 텍스처):"]
    L += keys("sg2")
    sfx_root = ROOT / "ref/sfx/weapons"
    L += ["",
          "[10] 새 무기 소리(2026-11-12, ref/sfx/weapons/<출처>/ 에서 직접 받은 CC0 1.0 소리. 자르고 모노 44.1 kHz libvorbis 로 다시 부호화)",
          f"  CC0 1.0: {CC0} . 저작자 표시 의무는 없지만 만든 이를 적는다:"]
    for d in sorted(sfx_root.glob("*/_info.json")) if sfx_root.is_dir() else []:
        inf = json.loads(d.read_text(encoding="utf-8"))
        L.append(f"  - {inf.get('title')}: {inf.get('author')}, {inf.get('url')}")
    L += ["  - 활 소리(gun.bow.fire)는 바닐라 석궁 소리를 가리키기만 한다([6])."]
    if CREDITS_EXTRA.is_dir():
        for f in sorted(CREDITS_EXTRA.glob("*.txt")):
            L += [""] + f.read_text(encoding="utf-8").rstrip("\n").split("\n")
    if loose:
        L += ["", "[?] 출처 절을 정하지 않은 키(build_props.py CREDIT_GROUPS 와 write_credits 에 절을 더할 것):"]
        L += [f"    - {k}: {SOURCES[k]['mod']} {SOURCES[k]['version']} ({SOURCES[k]['license']}) {SOURCES[k]['url']}. {SOURCES[k]['detail']}" for k in loose]
    p = OUT / "CREDITS.txt"
    p.write_text("\n".join(L) + "\n", encoding="utf-8")
    written["CREDITS.txt"] = p


def write_props_json():
    items, equip, sounds = {}, {}, {}
    for k, s_ in SOURCES.items():
        if s_.get("equipment"):
            continue
        if s_.get("sound"):
            continue
        e = {"item_model": f"{NS}:{k}", "source": f"{s_['mod']} {s_['version']} {s_['url']}", "license": s_["license"], "notes": s_["detail"]}
        for extra in ("real", "variant_of", "size_blocks", "display_scale", "tints_note", "grip"):
            if s_.get(extra) is not None:
                e[extra] = s_[extra]
        if k in GUNS or s_.get("variant_of") in GUNS:
            e["display_notes"] = ("3인칭: 기본 '아이템 든 팔'(팔 아래로, 앞 18도) 자세에서 총구가 수평 앞, 손잡이가 주먹 안. "
                                  "1인칭: 화면 오른쪽 아래, 총구가 조준점 쪽(4도 안으로). gui: 오른쪽 옆모습 총구 오른쪽 위 45도. 액자: 옆모습 가로")
        elif k == "grenade":
            e["display_notes"] = "손에 드는 수류탄. 3인칭 높이 약 0.17 블록, 1인칭 오른쪽 아래, gui 비스듬히"
        elif s_.get("world"):
            if s_.get("center"):
                e["display_notes"] = "item_display(none) 에 그대로 쓰는 실제 크기. 모델 가운데 (8,8,8) = 엔티티 자리. 돌리기는 플러그인이 변환으로"
            else:
                f = s_.get("display_scale", 1)
                e["display_notes"] = (("실제 크기(16 단위 = 1 블록). item_display 는 fixed 로 쓰거나 none 이면 변환 배율 1" if f == 1 else
                                       f"모델을 1/{f} 로 줄여 넣었다(범위 -16..32). item_display 를 fixed 로 쓰면 실제 크기(표시 변환 배율 {f}), none 이면 변환 배율 {f} 를 줄 것")
                                      + ". 모델 (8,8,8) = 엔티티 자리, 가로 가운데, 바닥 = 엔티티 y - 0.5. 모델 앞 = 북(-Z) 이고 아이템 디스플레이는 모델을 y 로 반 바퀴 돌려 그리므로 회전 0 이면 앞이 남(+Z)을 봄")
        items[k] = e
    for k, s_ in SOURCES.items():
        if s_.get("equipment"):
            equip[k] = s_["equipment"]
        if s_.get("sound"):
            sounds[s_["sound"]["event"]] = {k: v for k, v in s_["sound"].items() if k != "event"}
    # 무기 모델 표(2026-11-12 wpn_max): Java kr.gave.gun.Models 가 읽는다. 성능 배율은 그 종류 바탕 값(Guns.SPECS)에 곱함
    import arms_table
    import build_more
    info = globals().get("ARMS_INFO", {})
    arms = {}
    for r_ in arms_table.ROWS:
        a = {"kind": r_["kind"], "name": r_["name"], "src": r_["src"]}
        for f_, j_ in (("dmg", "dmg"), ("rps", "rps"), ("magx", "mag"), ("reload", "reload"), ("spread", "spread"), ("range", "range"),
                       ("pierce", "pierce")):
            if f_ in r_:
                a[j_] = r_[f_]
        if r_["key"] in build_more.MODEL_FIRE:
            a["sound"] = build_more.MODEL_FIRE[r_["key"]]
        if r_["key"] in info:
            a.update(info[r_["key"]])
        arms[r_["key"]] = a
    kinds = {k_: {"key": kk, "name": kn, "default": kd, "models": [r_["key"] for r_ in arms_table.ROWS if r_["kind"] == k_]}
             for k_, (kk, kn, kd) in arms_table.KINDS.items()}
    obj = {"namespace": NS, "items": items, "equipment": equip, "sounds": sounds, "arms": arms, "arms_kinds": kinds,
           "sound_files": globals().get("SOUND_LOG", []),
           "sound_notes": "모든 사건은 sounds.json 에 자막(ko_kr, en_us) 과 함께. 총소리, 폭발은 attenuation_distance 로 멀리 들림. "
                          "원본이 스테레오면 모노로 다시 부호화(libvorbis q7), 모노면 바이트 그대로 복사. 연사 총 소리는 꼬리를 잘라 겹침을 줄임(sound_files.process). "
                          "call 이 있는 사건: Java(Props.sound, soundAround)는 바닐라 대체 소리에 맞춘 호출 pitch 를 모드 소리에도 곱하므로 "
                          "sounds.json pitch(entry_pitch) = final_pitch / 호출 pitch 로 되돌려 둠. Java 호출 pitch 를 바꾸면 build_more.py CALL 도 바꿀 것. "
                          "vanilla_reference 인 사건은 팩에 파일 없이 바닐라 소리를 가리킴(files 의 minecraft: 경로)"}
    p = ROOT / "tools/props.json"
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
