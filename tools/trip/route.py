"""원정의 길(trip_route): 길 데이터 굳히기와 길 모델, 이동 화면 글꼴 굽기. tools/make_trip.py 가 bake(layer) 를 부른다.

python -X utf8 tools/trip/route.py --data                 굳힌 길 데이터와 글귀만 다시 씀(아래 '데이터')
python -X utf8 tools/trip/route.py --layer <폴더>         그 폴더에 내 층만 구움(혼자 볼 때. 진짜 층은 make_trip 이 build/trip/pack_layer 로)
python -X utf8 tools/trip/route.py --preview <png>        표지판, 소품, 검은 화면 글 미리보기 그림
python -X utf8 tools/trip/route.py --extract              TrafficCraft jar 에서 쓸 것만 ref/mods/trafficcraft/extracted 로 꺼냄

데이터(손으로 고치지 않는 산출물):
- plugin/src/main/resources/trip/route.json   굳힌 길 데이터. 초안 data/trip/stages_draft.json(build/trip_spec 이 구움)에 구현이 읽는 칸을 더한 것.
  더하는 칸은 이 파일의 표(SIGNS, EFFECTS, RULES, EVENT_IMPL, SCENERY, REWARD_ICON) 한곳: 숫자를 바꾸려면 여기만.
  (make_trip 이 초안을 resources/trip/stages.json 으로 그대로 복사하므로 굳힌 것은 이름을 route.json 으로 따로 둔다.)
- plugin/src/main/resources/trip/loading.json  이동 화면의 글 한 줄들(이야기, 팁). 원본은 data/phrases/trip_loading.json({키: 글}, 손으로 씀):
  키 ld_s<n>_* 는 이야기(n: 0 모든 막, 1 은 1~2막, 2 는 2~3막, 3 은 3~4막, 4 는 4막), ld_t_* 는 팁.
- data/phrases/trip_route.json                 HUD 글귀(스테이지 종류, 카드, 준비, 사건 이름. 키 rt_*).

팩 층(이름공간 gave):
- 표지판 items/trip/sign.json(custom_model_data strings[0] = 종류 키) → models/item/trip/sign_<종류>.json: TrafficCraft 표지판 판 + 기둥 + 표지 그림.
  판은 모델 y 0..16(가운데 = 엔티티 자리), 기둥은 아래로 16 더: 배율 S 로 놓으면 판이 엔티티 y +-0.5 S, 기둥 밑이 -1.5 S.
- 길가 표지 items/trip/post_<종류>.json: 같은 판을 기둥 위에 올린 작은 것(월드 소품 꼴: 바닥 = 엔티티 y - 0.5, 높이 2 블록).
- 길 점선 items/trip/dash.json: 길 무늬(가운데 흰 줄) 납작한 판. 길가 소품 items/trip/{lamp, delineator, delineator_red, barrel, cone, barrier, fence, guardrail}.json.
  소품은 모두 월드 소품 꼴(모델 (8,8,8) = 엔티티 자리, 바닥 = 엔티티 y - 0.5, 앞 = 북(-Z), fixed 표시 변환, 큰 것은 줄여 넣고 표시 배율로 되돌림).
- 이동 화면 글꼴 font/trip.json + textures/font/trip/*.png + trip_loading.json: 화면을 덮는 검은 글리프 하나와 줄마다 Galmuri11 로 미리 그린 그림 글리프
  (240 텍셀보다 넓으면 낱말 사이에서 나눔). Java(Travel.black)가 바닐라 제목 글의 큰 제목에 검은 글리프, 부제에 줄 글리프를 싣는다.
모델과 표지 그림은 직접 그리지 않는다: TrafficCraft(MrJulsen, GPL-3.0-or-later, https://modrinth.com/mod/trafficcraft)의 것을 26.3 아이템 모델로 옮긴다.
jar 는 C:/Users/PC/Desktop/클로드/eternalmc/ref/mods/trafficcraft(읽기만)에 있고 쓸 것만 꺼내 둔다. 숫자, 해골, 보상 아이콘은 Java 가 바닐라 글꼴 숫자,
바닐라 아이템 모델, 상점 아이콘(gave:shop/*)으로 조합한다.
"""
import argparse
import hashlib
import json
import math
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DRAFT = ROOT / "data/trip/stages_draft.json"
RES = ROOT / "plugin/src/main/resources/trip"
LOADING_SRC = ROOT / "data/phrases/trip_loading.json"
# 이동 화면 글꼴로 함께 굽는 다른 줄(무작위로는 안 뜸: Travel.black 에 키를 줘서 띄움). 엔딩 다섯 줄(trip_core 의 글)
EXTRA_SRC = [ROOT / "data/phrases/trip_ending.json"]
PHRASES_OUT = ROOT / "data/phrases/trip_route.json"
REF = ROOT / "ref/mods/trafficcraft"
SRC = REF / "extracted"
JAR = Path("C:/Users/PC/Desktop/클로드/eternalmc/ref/mods/trafficcraft/trafficcraft-neoforge-1.21.1-beta-1.2.0+4.jar")
FONT_KO = ROOT / "art/fonts/Galmuri11-Bold.ttf"
NS = "gave"
MODIFIED = "2026-11-12"

TCE = ROOT / "ref/mods/the-car-engine/extracted"  # The Car Engine(MIT, trip_core 가 tools/trip/fetch_tce.py 로 받아 둔 것): 길가 폐차

LICENSES = {"LICENSE-GPL-3.0.txt": ROOT / "tools/props/licenses/LICENSE-GPL-3.0.txt"}
if (TCE / "LICENSE").is_file():
    LICENSES["LICENSE-MIT-THE-CAR-ENGINE.txt"] = TCE / "LICENSE"

# ---------------------------------------------------------------------------------------------------------------- 길 데이터

# 표지판 그림(TrafficCraft textures/block/sign/<꼴>/<꼴><번호>.png): 스테이지 종류 → (판 꼴, 번호). 꼴과 색이 종류를 말한다.
SIGNS = {
    "fight": ("square", 25),     # 파란 네모, 곧은 화살표: 그냥 길
    "elite": ("triangle", 23),   # 빨간 세모 느낌표: 센 놈
    "cache": ("square", 20),     # 초록 네모 비행기: 보급
    "rest": ("square", 39),      # 파란 네모 붉은 십자: 정비, 꺼내기
    "event": ("diamond", 91),    # 노랑 검정 바둑 마름모: 무슨 일이 있다
    "rush": ("diamond", 46),     # 노란 마름모 미끄러지는 차: 험로(특수 라운드)
    "trap": ("circle", 36),      # 빨간 원 폭발: 함정
    "unknown": ("circle", 7),    # 빈 원: 미지(위험도 보상도 가림)
    "gate": ("circle", 6),       # 빨간 원 흰 가로줄: 보스 길목
    "launch": ("square", 20),    # 발사장(거리 표지)
}
# 길가에 세우는 작은 표지(월드 소품)로도 굽는 종류
POSTS = ("unknown", "fight", "launch", "elite")

# 스테이지 종류별 규칙과 보상(원정.md 3.1절). U = Trip.unit(). 숫자는 여기 한곳.
#   arrive: 도착할 때. ammo 모두 탄약 가득, truck 트럭 수리(최대 체력 비율), box 무기 상자 값만큼 돈(공짜 상자 대신)
#   round: 그 스테이지의 라운드를 열 때. crate 팀 보급 상자를 떨굼(라운드 시작 뒤 틱)
#   end: 스테이지의 둘째 라운드가 끝날 때. money 모두에게 x U, aug 모두 증강 카드 n 장, part 트럭 부품 제안(사건 routeReward 로 알림)
#   mul: 그 스테이지 동안의 배율(Trip.mul 키: price, revive, truckShare, reward, money ...)
#   boss: 보스 길목 질주를 끝까지 달렸을 때 보스 체력 배율(ask bossHp)
EFFECTS = {
    "fight": {"end": {"money": 1.0}},
    "elite": {"end": {"aug": 1}},
    "cache": {"arrive": {"ammo": 1}, "round": {"crate": 200}},
    "rest": {"arrive": {"truck": 0.30}, "mul": {"price": 0.8, "revive": 0.5}},
    "event": {},
    "rush": {"end": {"part": 1, "money": 5.0}},
    "trap": {"mul": {"truckShare": 2.0}, "end": {"aug": 2}},
    "gate_rest": {"arrive": {"truck": 0.30}, "mul": {"price": 0.8, "revive": 0.5}},
    "gate_arm": {"arrive": {"ammo": 1, "box": 1}},
    "gate_run": {"end": {"part": 1}, "boss": {"hp": 0.92}},
    "launch": {},
}

# 사건 가운데 지금 엔진으로 되는 것만 굴린다(ready). 나머지는 묶음 7, 8(라운드 종류, 특수)이 들어온 뒤에.
#   arrive.hitch: 손 든 사람(그 몫은 모두 2U, 아니면 사람 탈 게이브가 트럭 곁에서 나옴)
EVENT_IMPL = {
    "ev_radio": {"end": {"aug": 1}},
    "ev_hitch": {"arrive": {"hitch": 0.6, "money": 2.0, "mob": "G50", "mobs": 2}},
    "ev_storm": {"mul": {"reward": 1.3}},
    "ev_gift": {"round": {"crate": 100}, "arrive": {"box": 1}},
    "ev_quiet": {"mul": {"reward": 1.5}},
}

RULES = {
    # 쉬는 시간 틱: 투표 창은 open 에 열고 이동 시작(shop) close 틱 전에 닫는다. tie 는 동률일 때 번갈아 커지는 틱, hint 는 안 고른 사람에게 알릴 때.
    # reach 는 겨누는 거리, radius 는 시선이 판 가운데에서 이 안을 지나면 겨눈 것(블록)
    "vote": {"open": 40, "close": 60, "shop": 400, "tie": 40, "hint": 180, "reach": 60.0, "radius": 2.6},
    # 이동 연출 틱(보통, 막 넘김과 발사장 진입), 가둠 반지름, 연출 안개 단계, 흐름 빠르기(블록/틱)
    "travel": {"normal": 200, "act": 320, "confine": 8.0, "fog": 5, "speed": 1.2},
    # 갈림길을 열 때 정비가 꼭 끼는 문턱(트럭 체력 비율 아래, 죽은 사람 비율 이상)
    "rest": {"truck": 0.4, "dead": 0.2},
    # 위험 1 로 치는 종류(세 갈래 가운데 하나는 여기서, 하나는 위험 2 이상)
    "safe": ["fight", "cache", "rest"],
    # 미지가 드러나는 몫(막마다)
    "unknown": {
        "1": {"cache": 35, "event": 25, "trap": 30, "rest": 10},
        "2": {"cache": 35, "event": 25, "trap": 30, "rest": 10},
        "3": {"trap": 40, "cache": 25, "event": 25, "rest": 10},
    },
    # 아무도 험로를 안 골라도 끼는 특수 라운드: 막 → 그 스테이지 범위 가운데 하나의 첫 라운드를 바꿈(special 이 비면 그 막 특수에서 Rounds 가 고름)
    "forced": {"1": {"from": 3, "to": 4, "special": "chase"}, "2": {"from": 7, "to": 9, "special": ""}, "3": {"from": 12, "to": 14, "special": ""}},
}

# 스테이지 소품 묶음(카드의 props → 놓는 법). 모델은 trip/<이름>(이 파일이 굽는 TrafficCraft 소품)과 소품 층의 월드 소품 키.
# 모델 후보를 못 구한 묶음은 여기 없다(그 스테이지는 소품 없이: 구운 route.json 의 sceneryMissing 에 묶음 이름이 남는다). layout: road 길 양옆 줄, jam 길 위, cluster 몇 무더기, scatter 흩뿌림
SCENERY = {
    "road_poles": {"layout": "road", "models": ["trip/lamp", "trip/delineator", "trip/delineator", "trip/delineator"], "count": 16, "gap": 9},
    "car_jam": {"layout": "jam", "models": ["trip/wreck_sedan", "trip/wreck_pickup", "trip/cone", "trip/wreck_sedan", "trip/barrier"], "count": 12},
    "spilled_cargo": {"layout": "cluster", "models": ["weapon_crate", "ammo_box", "weapon_crate", "trip/cone"], "count": 12, "spots": 3},
    "tire_stack": {"layout": "cluster", "models": ["trip/barrel", "trip/barrel", "trip/cone"], "count": 12, "spots": 3},
    "drums": {"layout": "cluster", "models": ["trip/barrel"], "count": 14, "spots": 4},
    "rail_posts": {"layout": "road", "models": ["trip/guardrail"], "count": 16, "gap": 5},
    "blank_signs": {"layout": "road", "models": ["trip/post_unknown", "trip/delineator"], "count": 12, "gap": 12},
    "marker_poles": {"layout": "road", "models": ["trip/delineator_red", "trip/delineator"], "count": 16, "gap": 6},
    "sandbags": {"layout": "cluster", "models": ["trip/barrier", "trip/barrier", "trip/fence"], "count": 12, "spots": 3},
    "lanterns": {"layout": "scatter", "models": ["trip/lamp"], "count": 6},
    "stakes": {"layout": "scatter", "models": ["trip/delineator_red"], "count": 16},
    "name_stakes": {"layout": "scatter", "models": ["trip/delineator"], "count": 12},
    "camp": {"layout": "cluster", "models": ["trip/barrel", "weapon_crate", "trip/cone", "trip/fence"], "count": 10, "spots": 2},
    "burnt_trucks": {"layout": "jam", "models": ["trip/burnt_pickup", "trip/burnt_sedan", "trip/burnt_pickup", "trip/barrier"], "count": 8},
}

# 표지판 위 보상 아이콘(보상 키 → 아이템 모델). gave:shop/* 는 상점 아이콘(make_shop_icons), minecraft:* 는 바닐라 아이템 모델
REWARD_ICON = {
    "money": "gave:shop/coin", "aug": "gave:shop/aug_roll", "box": "gave:weapon_box", "ammo": "gave:shop/ammo", "repair": "gave:shop/upgrade",
    "revive": "gave:shop/bandage", "part": "minecraft:iron_ingot", "boss": "minecraft:wither_skeleton_skull",
}

# 이동 화면 줄의 막 범위(키의 s<n>)
LINE_ACTS = {"0": [1, 4], "1": [1, 2], "2": [2, 3], "3": [3, 4], "4": [4, 4]}


def stages():
    d = json.loads(DRAFT.read_text(encoding="utf-8"))
    out = {"note": "굳힌 길 데이터(tools/trip/route.py 가 data/trip/stages_draft.json 에서 만듦, 손으로 고치지 않음). 설계 docs/모듈/원정.md 3절"}
    types = {}
    for k, t in d["types"].items():
        types[k] = {"name": t["name"], "risk": t["risk"], "reward": t["reward"], "first": t["first"], "second": t["second"], "sign": k}
    out["types"] = types
    out["pools"] = d["pools"]
    out["weights"] = d["weights"]
    rules = {"stages": d["rules"]["stages"], "votes": d["rules"]["votes"], "roundsPerStage": d["rules"]["roundsPerStage"],
             "bossRounds": d["rules"]["bossRounds"], "launchFrom": d["rules"]["launchFrom"]}
    rules.update(RULES)
    out["rules"] = rules
    out["effects"] = EFFECTS
    out["gates"] = [{"key": g["key"], "name": g["name"], "icon": g["icon"], "first": g["first"], "risk": g["risk"]} for g in d["gates"]]
    out["cards"] = [{k: c[k] for k in ("key", "act", "type", "name", "props", "special") if k in c} for c in d["cards"]]
    evs = []
    for e in d["events"]:
        row = {"key": e["key"], "acts": e["acts"], "line": e["line"], "ready": e["key"] in EVENT_IMPL}
        row.update(EVENT_IMPL.get(e["key"], {}))
        evs.append(row)
    out["events"] = evs
    out["scenery"] = SCENERY
    out["rewardIcon"] = REWARD_ICON
    out["sceneryMissing"] = sorted({c["props"] for c in d["cards"]} - set(SCENERY) - {"none"})
    return out


def route_phrases():
    """HUD 글귀(data/phrases/trip_route.json): 스테이지 종류, 카드, 보스 길목 준비, 사건 한 줄. 키는 rt_ 로 시작(trip.json 과 안 겹치게).
    make_trip 이 모으는 phrases() 로 내놓지 않는다(그러면 trip.json 과 이 파일에 같은 키가 둘 생겨 make_hud 가 멈춤)."""
    d = json.loads(DRAFT.read_text(encoding="utf-8"))
    p = {"rt_vote": "길을 골라라", "rt_hint_vote": "1 2 3 키로 길을 고른다", "rt_launch": "발사장"}
    for k, t in d["types"].items():
        p[f"rt_type_{k}"] = t["name"]
    for c in d["cards"]:
        p[f"rt_card_{c['key']}"] = c["name"]
    for g in d["gates"]:
        p[f"rt_{g['key']}"] = g["name"]
    for e in d["events"]:
        p[f"rt_{e['key']}"] = e["line"]
    return p


def loading_lines():
    """이동 화면 줄 [{key, text, kind, acts}] (data/phrases/trip_loading.json 에서)."""
    src = json.loads(LOADING_SRC.read_text(encoding="utf-8"))
    out = []
    for k, text in src.items():
        assert "\u00b7" not in text, k
        if k.startswith("ld_t_"):
            out.append({"key": k, "text": text, "kind": "tip", "acts": [1, 4]})
        elif k.startswith("ld_s") and k[4] in LINE_ACTS:
            out.append({"key": k, "text": text, "kind": "story", "acts": LINE_ACTS[k[4]]})
        else:
            raise SystemExit(f"data/phrases/trip_loading.json 의 키 {k}: ld_s<0..4>_* 나 ld_t_* 여야 한다")
    for f in EXTRA_SRC:
        if not f.is_file():
            continue
        for k, text in json.loads(f.read_text(encoding="utf-8")).items():
            if chr(0xB7) in text or any(l["key"] == k for l in out):
                raise SystemExit(f"{f.name} 의 키 {k}: 가운뎃점이 있거나 키가 겹침")
            out.append({"key": k, "text": text, "kind": "extra", "acts": [1, 4]})
    return out


def _write_if_changed(path: Path, text: str):
    if path.is_file() and path.read_text(encoding="utf-8") == text:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return True


def write_data(res: Path = RES):
    """굳힌 길 데이터, 이동 화면 줄, HUD 글귀를 쓴다(바뀐 것만). 요약을 돌려준다."""
    data = stages()
    lines = loading_lines()
    ph = route_phrases()
    assert not [k for k, v in ph.items() if "\u00b7" in v]
    changed = []
    if _write_if_changed(res / "route.json", json.dumps(data, ensure_ascii=False, indent=1) + "\n"):
        changed.append("route.json")
    if _write_if_changed(res / "loading.json", json.dumps({"lines": lines}, ensure_ascii=False, indent=1) + "\n"):
        changed.append("loading.json")
    if _write_if_changed(PHRASES_OUT, json.dumps(ph, ensure_ascii=False, indent=1) + "\n"):
        changed.append("data/phrases/trip_route.json")
    return {"types": len(data["types"]), "cards": len(data["cards"]), "events_ready": sum(1 for e in data["events"] if e["ready"]),
            "scenery": len(data["scenery"]), "scenery_missing": len(data["sceneryMissing"]), "lines": len(lines),
            "story": sum(1 for l in lines if l["kind"] == "story"), "tips": sum(1 for l in lines if l["kind"] == "tip"),
            "phrases": len(ph), "changed": ", ".join(changed) or "-"}


# ---------------------------------------------------------------------------------------------------------------- TrafficCraft 꺼내기

TC = "assets/trafficcraft"
MODELS = ["sign/square", "sign/triangle", "sign/diamond", "sign/circle", "traffic_sign_post", "lit_street_lamp", "white_delineator", "red_delineator",
          "traffic_barrel", "traffic_cone", "concrete_barrier", "road_barrier_fence", "guardrail"]
TEXTURES = ["sign", "sign_blank", "asphalt", "ger_delineator", "red_delineator", "guardrail", "concrete_barrier", "lamp_light", "patterns/1"]
# 텍스처 바꿔 끼우기: 모드가 바닐라 것을 가리키거나(26.3 은 한 모델에 아이템, 블록 아틀라스를 못 섞음) Mojang 그림의 사본인 것 → 같은 모드의 제 그림으로
TEX_SUB = {
    "minecraft:block/white_concrete": "trafficcraft:block/sign_blank",   # 흰 띠
    "trafficcraft:block/anvil": "trafficcraft:block/asphalt",            # 받침(모드의 anvil.png 는 바닐라 모루 그림과 화소가 같은 Mojang 에셋이라 안 씀)
}
# 틴트: 모드는 블록 색 공급자로 칠한다. 라바콘 기본 색은 클래스 Constants.TRAFFIC_CONE_BASE_COLOR(-3070171 = 0xD12725, 좀비런이 javap 로 읽은 값)
CONE = 0xD12725


def needed():
    out = [f"{TC}/models/block/{m}.json" for m in MODELS] + [f"{TC}/textures/block/{t}.png" for t in TEXTURES]
    out += sorted({f"{TC}/textures/block/sign/{shape}/{shape}{n}.png" for shape, n in SIGNS.values()})
    out.append("META-INF/neoforge.mods.toml")
    return out


def extract():
    """jar 에서 쓸 것만 꺼내고 _info.json 을 쓴다. 받은 파일은 데이터로만 다룬다(실행 안 함)."""
    if not JAR.is_file():
        raise SystemExit(f"TrafficCraft jar 없음: {JAR}")
    names = needed()
    with zipfile.ZipFile(JAR) as z:
        for n in names:
            dst = SRC / n
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(z.read(n))
    toml = (SRC / "META-INF/neoforge.mods.toml").read_text(encoding="utf-8", errors="replace")
    lic = next((l.strip() for l in toml.splitlines() if l.strip().startswith("license")), "")
    info = {
        "slug": "trafficcraft",
        "title": "TrafficCraft (MrJulsen)",
        "url": "https://modrinth.com/mod/trafficcraft",
        "source": "https://github.com/MisterJulsen/TrafficCraft",
        "version": "1.21.1-beta-1.2.0+4 (NeoForge 1.21.1)",
        "license": "GPL-3.0-or-later",
        "license_evidence": {
            "modrinth_api": "https://api.modrinth.com/v2/project/trafficcraft 의 license.id = GPL-3.0-or-later (2026-11-12 확인, 본문에 에셋 예외 문구 없음)",
            "jar_mods_toml": lic,
            "authors": "MrJulsen",
        },
        "note": "jar 는 eternalmc/ref/mods/trafficcraft 의 것을 읽기만 함(2026-11-01 Modrinth 에서 받은 것). 표지판 판과 기둥, 표지 그림(32x32), 길 무늬, 길가 소품만 꺼냄. "
                "모드의 textures/block/anvil.png 는 바닐라 block/anvil.png 와 화소가 같은 Mojang 에셋이라 꺼내지 않고 같은 모드의 asphalt.png 로 바꿔 끼움. "
                "바닐라 block/white_concrete 를 가리키는 면은 같은 모드의 sign_blank.png 로(26.3 은 한 모델에 아이템, 블록 아틀라스를 못 섞음). "
                "GPL-3.0 전문은 팩 맨 위 LICENSE-GPL-3.0.txt, 수정 고지는 CREDITS-TRIP.txt.",
        "jar_file": JAR.name,
        "jar_path_when_extracted": str(JAR),
        "sha512": hashlib.sha512(JAR.read_bytes()).hexdigest(),
        "extracted": names,
    }
    (REF / "_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return len(names)


def ensure_extracted():
    if all((SRC / n).is_file() for n in needed()) and (REF / "_info.json").is_file():
        return
    extract()


# ---------------------------------------------------------------------------------------------------------------- 모델 옮기기

def _tex_id(v, texs):
    d = 0
    while isinstance(v, str) and v.startswith("#") and d < 10:
        v = texs.get(v[1:], v)
        d += 1
    if ":" not in v:
        v = "minecraft:" + v
    return TEX_SUB.get(v, v)


def load(name):
    """모드 블록 모델 → 요소 목록(면 texture 는 텍스처 id 로 풀고, 틴트 번호는 남김)."""
    m = json.loads((SRC / f"{TC}/models/block/{name}.json").read_text(encoding="utf-8-sig"))
    texs = m.get("textures", {})
    out = []
    for e in m["elements"]:
        ne = {"from": [float(x) for x in e["from"]], "to": [float(x) for x in e["to"]], "faces": {}}
        r = e.get("rotation")
        if r and r.get("angle"):
            ne["rotation"] = {"angle": r["angle"], "axis": r["axis"], "origin": [float(x) for x in r["origin"]]}
        for fn, f in e["faces"].items():
            nf = {"uv": f["uv"] if "uv" in f else _default_uv(fn, ne["from"], ne["to"]), "texture": _tex_id(f["texture"], texs)}
            if "rotation" in f:
                nf["rotation"] = f["rotation"]
            if "tintindex" in f:
                nf["tintindex"] = f["tintindex"]
            ne["faces"][fn] = nf
        out.append(ne)
    return out


def _default_uv(face, lo, hi):
    if face in ("north", "south"):
        return [lo[0], 16 - hi[1], hi[0], 16 - lo[1]]
    if face in ("east", "west"):
        return [lo[2], 16 - hi[1], hi[2], 16 - lo[1]]
    return [lo[0], lo[2], hi[0], hi[2]]


def _corners(e):
    f, t = e["from"], e["to"]
    pts = [[x, y, z] for x in (f[0], t[0]) for y in (f[1], t[1]) for z in (f[2], t[2])]
    r = e.get("rotation")
    if not r:
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


def bounds(els):
    pts = [p for e in els for p in _corners(e)]
    return [min(p[i] for p in pts) for i in range(3)], [max(p[i] for p in pts) for i in range(3)]


def moved(els, k=1.0, off=(0, 0, 0)):
    """요소를 k 배 하고 off 만큼 옮긴 사본."""
    out = []
    for e in els:
        ne = json.loads(json.dumps(e))
        for key in ("from", "to"):
            ne[key] = [e[key][i] * k + off[i] for i in range(3)]
        if "rotation" in ne:
            ne["rotation"]["origin"] = [e["rotation"]["origin"][i] * k + off[i] for i in range(3)]
        out.append(ne)
    return out


def strip_tint(els):
    for e in els:
        for f in e["faces"].values():
            f.pop("tintindex", None)
    return els


class Layer:
    """굽는 층 하나(폴더). 텍스처는 gave:item/trip/rt_<이름> 으로 한 번씩만 복사한다."""

    def __init__(self, root: Path):
        self.root = root
        self.tex = {}
        self.models = {}
        self.items = []

    def texture(self, tid):
        if tid in self.tex:
            return self.tex[tid]
        ns, _, p = tid.partition(":")
        if ns == "trafficcraft":
            src = SRC / f"{TC}/textures/{p}.png"
            name = "rt_" + p.replace("block/", "").replace("/", "_")
        elif ns == "tce":
            src = TCE / f"assets/tce/textures/{p}.png"
            name = "rt_tce_" + p.replace("vehicles/", "").replace("/", "_").replace("-", "_")
        else:
            raise KeyError(f"모드 밖 텍스처 {tid}(TEX_SUB 에 바꿔 끼울 것을 적을 것)")
        if name.endswith(("_n", "_s", "_e")):
            name += "x"
        dst = self.root / f"assets/{NS}/textures/item/trip/{name}.png"
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(src.read_bytes())
        self.tex[tid] = f"{NS}:item/trip/{name}"
        return self.tex[tid]

    def model(self, key, els, factor=1.0, align=True, prescaled=False, credit=None):
        """요소를 모델로 쓴다. align: 월드 소품 꼴로 맞춤(가로 가운데, 바닥 = 엔티티 y - 0.5). factor: 줄여 넣고 fixed 표시 배율로 되돌림.
        prescaled: 모드가 이미 1/factor 크기로 만든 모델(차: 모드가 배율 2 로 놓음)이라 줄이지 않고 자리만 맞춘다."""
        if align:
            lo, hi = bounds(els)
            k = 1.0 if prescaled else 1.0 / factor
            off = [8 - k * (lo[0] + hi[0]) / 2, (8 - 8 / factor) - k * lo[1], 8 - k * (lo[2] + hi[2]) / 2]
            # 돌린 요소 때문에 가운데가 조금 밀려 from/to 가 범위를 넘으면 그만큼만 되민다(범위를 꽉 채운 긴 차)
            for i in range(3):
                raw_lo = min(min(e["from"][i], e["to"][i]) for e in els) * k + off[i]
                raw_hi = max(max(e["from"][i], e["to"][i]) for e in els) * k + off[i]
                if raw_lo < -16:
                    off[i] += -16 - raw_lo
                elif raw_hi > 32:
                    off[i] -= raw_hi - 32
            els = moved(els, k, off)
        vars_, out_els = {}, []
        for e in els:
            ne = {"from": [round(x, 4) for x in e["from"]], "to": [round(x, 4) for x in e["to"]], "faces": {}}
            for key2 in ("from", "to"):
                if not all(-16 <= x <= 32 for x in ne[key2]):
                    raise ValueError(f"{key}: 요소 {key2} {ne[key2]} 가 26.3 범위(-16..32) 밖(factor 를 키울 것)")
            if "rotation" in e:
                ne["rotation"] = {"angle": e["rotation"]["angle"], "axis": e["rotation"]["axis"], "origin": [round(x, 4) for x in e["rotation"]["origin"]]}
            for k2 in ("shade", "light_emission"):
                if k2 in e:
                    ne[k2] = e[k2]
            for fn, f in e["faces"].items():
                tid = self.texture(f["texture"])
                var = vars_.setdefault(tid, f"t{len(vars_)}")
                nf = {"uv": f["uv"], "texture": "#" + var}
                for k2 in ("rotation", "tintindex"):
                    if k2 in f:
                        nf[k2] = f[k2]
                ne["faces"][fn] = nf
            out_els.append(ne)
        texs = {v: k for k, v in vars_.items()}
        texs["particle"] = next(iter(vars_))
        m = {"credit": credit or "TrafficCraft (MrJulsen, GPL-3.0-or-later), moved to a 26.3 item model", "textures": texs, "elements": out_els,
             "display": {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [factor] * 3}}}
        p = self.root / f"assets/{NS}/models/item/trip/{key}.json"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(m, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        lo, hi = bounds(out_els)
        self.models[key] = {"elements": len(out_els), "size_blocks": [round((hi[i] - lo[i]) / 16 * factor, 2) for i in range(3)]}
        return m

    def item(self, key, model=None, tints=None):
        node = {"type": "minecraft:model", "model": f"{NS}:item/trip/{model or key}"}
        if tints:
            node["tints"] = [{"type": "minecraft:constant", "value": c} for c in tints]
        self.item_raw(key, node)

    def item_raw(self, key, node):
        p = self.root / f"assets/{NS}/items/trip/{key}.json"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps({"model": node}, ensure_ascii=False, indent=1), encoding="utf-8")
        self.items.append(key)


def sign_elements(kind):
    """표지판 판(y 0..16) + 기둥 + 앞면 표지 그림. 앞 = 북(-Z)."""
    shape, n = SIGNS[kind]
    els = strip_tint(load(f"sign/{shape}"))
    pict = f"trafficcraft:block/sign/{shape}/{shape}{n}"
    # 모드는 판 앞면(z 6.5)에 고른 표지 그림을 그린다(네모 판은 그 자리에 두께 없는 요소가 따로 있음): 그 요소를 버리고 살짝 앞에 그림 판을 둔다
    els = [e for e in els if not (e["from"][2] == e["to"][2] and len(e["faces"]) == 1)]
    els.append({"from": [0, 0, 6.3], "to": [16, 16, 6.3], "shade": False,
                "faces": {"north": {"uv": [0, 0, 16, 16], "texture": pict}}})
    return els


def pole(y0, y1):
    """표지판 기둥 한 토막(traffic_sign_post 의 요소를 그 높이로)."""
    e = strip_tint(load("traffic_sign_post"))[0]
    out = []
    y = y0
    while y < y1:
        h = min(16, y1 - y)
        ne = json.loads(json.dumps(e))
        ne["from"][1], ne["to"][1] = y, y + h
        for fn, f in ne["faces"].items():
            if fn not in ("up", "down"):
                f["uv"] = [f["uv"][0], 0, f["uv"][2], h]
        out.append(ne)
        y += h
    return out


def tinted(els):
    """틴트 번호가 달린 면이 있나."""
    return any("tintindex" in f for e in els for f in e["faces"].values())


def bake_models(L: Layer):
    # 표지판(투표용 큰 것): 판 y 0..16, 기둥은 아래로 16 더
    cases = []
    for kind in SIGNS:
        L.model(f"sign_{kind}", sign_elements(kind) + pole(-16, 0), align=False)
        cases.append({"when": kind, "model": {"type": "minecraft:model", "model": f"{NS}:item/trip/sign_{kind}"}})
    L.item_raw("sign", {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0, "cases": cases,
                        "fallback": {"type": "minecraft:model", "model": f"{NS}:item/trip/sign_fight"}})
    # 길가 표지(작은 것): 기둥 0..16 위에 판 16..32
    for kind in POSTS:
        L.model(f"post_{kind}", moved(sign_elements(kind), 1.0, (0, 16, 0)) + pole(0, 16))
        L.item(f"post_{kind}")
    # 길 점선: 길 무늬 1(가운데 흰 줄) 납작한 판
    dash = [{"from": [0, 0, 0], "to": [16, 0.1, 16], "shade": False,
             "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "trafficcraft:block/patterns/1"},
                       "down": {"uv": [0, 0, 16, 16], "texture": "trafficcraft:block/patterns/1"}}}]
    L.model("dash", dash)
    L.item("dash")
    # 가로등: 기둥 넷 + 머리(불 켜진 것). 불빛 면은 스스로 빛남
    head = strip_tint(load("lit_street_lamp"))
    for e in head:
        if any(f["texture"].endswith("lamp_light") for f in e["faces"].values()):
            e["light_emission"] = 15
    L.model("lamp", pole(0, 64) + moved(head, 1.0, (0, 64, 0)), factor=3.0)
    L.item("lamp")
    # 그대로 옮기는 것(틴트 번호가 있으면 라바콘 색)
    for key, src, tint in (("delineator", "white_delineator", None), ("delineator_red", "red_delineator", None), ("barrel", "traffic_barrel", CONE),
                           ("cone", "traffic_cone", CONE), ("barrier", "concrete_barrier", None), ("fence", "road_barrier_fence", CONE),
                           ("guardrail", "guardrail", None)):
        els = load(src)
        if tint is None:
            strip_tint(els)
        L.model(key, els)
        L.item(key, tints=[tint] if tint is not None and tinted(els) else None)


# 길가 폐차(The Car Engine 의 차 몸통, 바퀴 없이 땅에 앉힘): 키 → (모드 모델, 틴트 0, 틴트 1 = 차체 색, 모든 면에 틴트를 다는가)
DUSTY, RUST, SCORCH = 0xB9AC98, 0x8A5A44, 0x4A423C
WRECKS = {
    "wreck_sedan": ("sedan", 0xFFFFFF, DUSTY, False),
    "wreck_pickup": ("pickup_double_cab", 0xFFFFFF, RUST, False),
    "burnt_sedan": ("sedan", SCORCH, SCORCH, True),
    "burnt_pickup": ("pickup_double_cab", SCORCH, SCORCH, True),
}
CAR_SCALE = 2.0  # 모드가 차 몸통을 놓는 배율(display.mcfunction 의 scale 2)


def load_car(name, tint_all=False):
    """The Car Engine 차 모델 → 요소(면 texture 는 텍스처 id). tint_all: 틴트 번호 없는 면에 0 을 단다(그을린 색을 통째로 입힘)."""
    m = json.loads((TCE / f"assets/tce/models/vehicles/{name}.json").read_text(encoding="utf-8-sig"))
    texs = m.get("textures", {})
    out = []
    for e in m["elements"]:
        ne = {"from": [float(x) for x in e["from"]], "to": [float(x) for x in e["to"]], "faces": {}}
        r = e.get("rotation")
        if r and r.get("angle"):
            ne["rotation"] = {"angle": r["angle"], "axis": r["axis"], "origin": [float(x) for x in r["origin"]]}
        for fn, f in e["faces"].items():
            v = f["texture"]
            d = 0
            while v.startswith("#") and d < 10:
                v = texs.get(v[1:], v)
                d += 1
            if v.startswith("#"):
                continue
            nf = {"uv": f["uv"] if "uv" in f else _default_uv(fn, ne["from"], ne["to"]), "texture": v}
            if "rotation" in f:
                nf["rotation"] = f["rotation"]
            if "tintindex" in f:
                nf["tintindex"] = f["tintindex"]
            elif tint_all:
                nf["tintindex"] = 0
            ne["faces"][fn] = nf
        if ne["faces"]:
            out.append(ne)
    return out


def bake_wrecks(L: Layer):
    """폐차 넷. The Car Engine 을 못 찾으면 건너뛴다(그 소품만 빠짐)."""
    if not (TCE / "assets/tce/models/vehicles/sedan.json").is_file():
        return 0
    done = {}
    for key, (name, c0, c1, tint_all) in WRECKS.items():
        mk = f"car_{name}" + ("_all" if tint_all else "")
        if mk not in done:
            L.model(mk, load_car(name, tint_all), factor=CAR_SCALE, prescaled=True,
                    credit="The Car Engine (The_CommanderRS, MIT; model made with Blockbench by melonapple), body without wheels as a wreck")
            done[mk] = True
        L.item(key, model=mk, tints=[c0, c1])
    return len(WRECKS)


# ---------------------------------------------------------------------------------------------------------------- 이동 화면 글꼴

FONT_PX = 11          # Galmuri11 의 제 크기(화소 글꼴이라 계조 없이)
LINE_H = 32           # 줄 그림 높이(위 절반에 글, 아래는 비움: 부제 자리에서 화면 가운데에 오게)
LINE_UNITS = 16       # 글꼴 높이 값(그림 2 화소 = 1 단위: 부제 배율 2 에서 그림 1 화소 = GUI 1 화소)
PIECE_MAX = 240       # 한 글리프 최대 폭(텍셀)
CH_BLACK, CH_BACK, CH_LINE0 = 0xE000, 0xE001, 0xE100


def render_line(text, font):
    """글 한 줄 → 흰 글자 그림(RGBA, 높이 LINE_H, 폭 짝수). 글은 위 16 화소 가운데."""
    from PIL import Image, ImageDraw
    w = int(math.ceil(font.getlength(text))) + 4
    w += w % 2
    m = Image.new("L", (w, LINE_H), 0)
    d = ImageDraw.Draw(m)
    d.fontmode = "1"
    top, bottom = font.getbbox("가Ag")[1], font.getbbox("가Ag")[3]
    d.text((1, 8 - (top + bottom) / 2), text, font=font, fill=255)
    img = Image.new("RGBA", (w, LINE_H), (255, 255, 255, 0))
    img.putalpha(m.point(lambda v: 255 if v > 127 else 0))
    return img


def cut_points(img):
    """PIECE_MAX 보다 넓은 줄을 나눌 자리(짝수 열). 낱말 사이 빈 열에서만 자른다."""
    w = img.width
    if w <= PIECE_MAX:
        return []
    alpha = img.getchannel("A")
    blank = [alpha.crop((x, 0, x + 1, img.height)).getbbox() is None for x in range(w)]
    n = math.ceil(w / PIECE_MAX)
    cuts = []
    for i in range(1, n):
        want = w * i // n
        best = None
        for x in range(2, w - 2, 2):
            if blank[x - 1] and blank[x] and blank[x + 1]:
                if best is None or abs(x - want) < abs(best - want):
                    best = x
        if best is None:
            best = want - want % 2
        cuts.append(best)
    cuts = sorted(set(cuts))
    prev = 0
    for c in cuts + [w]:
        if c - prev > PIECE_MAX:
            raise SystemExit(f"줄을 {PIECE_MAX} 텍셀 아래로 못 나눔(낱말 사이가 모자람): 폭 {w}, 자리 {cuts}")
        prev = c
    return cuts


def bake_font(L: Layer):
    """검은 글리프와 줄 그림 글리프, 글꼴 정의, 줄 키 → 글리프 표(assets/gave/trip_loading.json)."""
    from PIL import Image, ImageFont
    font = ImageFont.truetype(str(FONT_KO), FONT_PX)
    tex = L.root / f"assets/{NS}/textures/font/trip"
    tex.mkdir(parents=True, exist_ok=True)
    # 검은 글리프: 128x64 검정을 높이 512 단위로 키움(큰 제목 배율 4 에서 가로 4096, 세로 2048 GUI 화소: 어떤 화면도 덮음). 가운데가 화면 가운데에 오게 ascent 256
    Image.new("RGBA", (128, 64), (0, 0, 0, 255)).save(tex / "black.png")
    providers = [
        {"type": "space", "advances": {chr(CH_BACK): -1}},
        {"type": "bitmap", "file": f"{NS}:font/trip/black.png", "height": 512, "ascent": 256, "chars": [chr(CH_BLACK)]},
    ]
    table = {}
    ch = CH_LINE0
    pieces = 0
    widest = 0
    for line in loading_lines():
        img = render_line(line["text"], font)
        widest = max(widest, img.width)
        cuts = cut_points(img)
        glyphs = []
        prev = 0
        for i, c in enumerate(cuts + [img.width]):
            name = f"{line['key']}_{i}"
            img.crop((prev, 0, c, LINE_H)).save(tex / f"{name}.png")
            providers.append({"type": "bitmap", "file": f"{NS}:font/trip/{name}.png", "height": LINE_UNITS, "ascent": LINE_UNITS, "chars": [chr(ch)]})
            glyphs.append(chr(ch))
            ch += 1
            pieces += 1
            prev = c
        # 글리프마다 1 단위 틈이 붙는다: 조각 사이는 -1 로 메운다
        table[line["key"]] = chr(CH_BACK).join(glyphs)
    p = L.root / f"assets/{NS}/font/trip.json"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps({"providers": providers}, ensure_ascii=True, indent=1), encoding="utf-8")
    (L.root / f"assets/{NS}/trip_loading.json").write_text(
        json.dumps({"font": f"{NS}:trip", "black": chr(CH_BLACK), "lines": table}, ensure_ascii=True, indent=1), encoding="utf-8")
    return {"lines": len(table), "pieces": pieces, "widest_px": widest}


# ---------------------------------------------------------------------------------------------------------------- make_trip 약속

def bake(layer: Path):
    """내 층을 그 폴더에 굽는다(make_trip 이 부름). 굳힌 길 데이터와 글귀도 함께 쓴다(바뀐 것만)."""
    ensure_extracted()
    L = Layer(Path(layer))
    bake_models(L)
    wrecks = bake_wrecks(L)
    font = bake_font(L)
    data = write_data()
    return {"models": len(L.models), "items": len(L.items), "textures": len(L.tex), "wrecks": wrecks, "font_lines": font["lines"], "font_pieces": font["pieces"],
            "widest_line_px": font["widest_px"], "data_changed": data["changed"], "lines_story": data["story"], "lines_tips": data["tips"],
            "sizes": {k: v["size_blocks"] for k, v in L.models.items()}}


def credits():
    return [
        "[원정 2] TrafficCraft 1.21.1-beta-1.2.0+4 (NeoForge 1.21.1), MrJulsen",
        "  https://modrinth.com/mod/trafficcraft , https://github.com/MisterJulsen/TrafficCraft",
        "  라이선스: GPL-3.0-or-later(Modrinth 라이선스 칸, jar neoforge.mods.toml license = \"GNU General Public License v3.0\"). 전문 LICENSE-GPL-3.0.txt(팩 맨 위)",
        "  쓴 것: 표지판 판(square, triangle, diamond, circle)과 기둥, 표지 그림 9장, 길 무늬 1, 가로등 머리, 시선 유도 말뚝 둘, 교통 통, 라바콘, 방호벽,",
        "  차단 울타리, 가드레일(assets/gave/models/item/trip/{sign_*, post_*, dash, lamp, delineator*, barrel, cone, barrier, fence, guardrail},",
        "  assets/gave/textures/item/trip/rt_*)",
        f"  바꾼 것({MODIFIED}): 블록 모델을 26.3 아이템 모델로 옮김(이름공간과 경로 gave:item/trip/, 가운데와 바닥 맞춤, 가로등은 기둥 넷 위에 머리를 올려",
        "  3분의 1 로 줄이고 표시 배율로 되돌림), 표지판 앞에 표지 그림 면을 더함(모드는 블록 엔티티로 그림), 기둥을 이어 붙임, 틴트 번호를 고정 색으로",
        "  (라바콘 색 0xD12725), 모드가 가리키는 바닐라 흰 콘크리트 면은 같은 모드의 sign_blank.png 로, 모루 그림 면은 같은 모드의 asphalt.png 로 바꿈.",
        "  고친 것도 GPL-3.0-or-later 로 나눈다. 원작자가 이 팩을 만들거나 승인한 것이 아니다.",
        "  길가 폐차(assets/gave/models/item/trip/car_*, 아이템 정의 trip/wreck_*, trip/burnt_*)는 [원정 1] The Car Engine(MIT, 전문 LICENSE-MIT-THE-CAR-ENGINE.txt)의",
        "  sedan, pickup_double_cab 몸통을 바퀴 없이 옮긴 것이다(가운데와 바닥 맞춤, 차체 틴트를 고정 색으로, 그을린 것은 모든 면에 틴트 번호를 닮. 텍스처 픽셀은 그대로).",
        "  이동 화면 글(assets/gave/textures/font/trip/ld_*.png)은 Galmuri11 Bold(Lee Minseo, SIL Open Font License 1.1)로 그린 그림이다(글꼴 파일은 안 들어감).",
    ]


def preview(out: Path):
    """미리보기: 표지판 그림 셋과 검은 화면 글(480x270 GUI 를 두 배로)."""
    from PIL import Image, ImageDraw, ImageFont
    ensure_extracted()
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    kinds = list(SIGNS)
    cell = 96
    sheet = Image.new("RGB", (max(cell * len(kinds), 960), cell + 24 + 540 + 8), (58, 62, 70))
    d = ImageDraw.Draw(sheet)
    for i, k in enumerate(kinds):
        shape, n = SIGNS[k]
        t = Image.open(SRC / f"{TC}/textures/block/sign/{shape}/{shape}{n}.png").convert("RGBA").resize((cell - 8, cell - 8), Image.NEAREST)
        sheet.paste(t, (i * cell + 4, 4), t)
        d.text((i * cell + 6, cell), k, fill=(255, 255, 255))
    # 검은 화면: 가장 긴 줄과 본보기 줄
    font = ImageFont.truetype(str(FONT_KO), FONT_PX)
    lines = loading_lines()
    longest = max(lines, key=lambda l: font.getlength(l["text"]))
    screen = Image.new("RGB", (480, 270), (0, 0, 0))
    for j, l in enumerate((lines[0], longest)):
        img = render_line(l["text"], font)
        img = img.crop((0, 0, img.width, 16))
        x = 240 - img.width // 2
        y = 135 - 8 + (j * 40 - 20)
        sh = Image.new("RGBA", img.size, (63, 63, 63, 255))
        screen.paste(sh, (x + 1, y + 1), img)
        screen.paste(img, (x, y), img)
    sheet.paste(screen.resize((960, 540), Image.NEAREST), (0, cell + 24))
    sheet.save(out)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", action="store_true")
    ap.add_argument("--res", help="--data 가 route.json, loading.json 을 쓸 폴더(기본 plugin/src/main/resources/trip)")
    ap.add_argument("--layer")
    ap.add_argument("--preview")
    ap.add_argument("--extract", action="store_true")
    a = ap.parse_args()
    if a.extract:
        print("꺼낸 파일", extract())
    if a.data:
        print("데이터", write_data(Path(a.res) if a.res else RES))
    if a.layer:
        layer = Path(a.layer)
        if layer.exists():
            shutil.rmtree(layer)
        res = bake(layer)
        print("층", layer, {k: v for k, v in res.items() if k != "sizes"})
        print("크기(블록)", res["sizes"])
    if a.preview:
        print("미리보기", preview(Path(a.preview)))
    if not (a.extract or a.data or a.layer or a.preview):
        ap.print_help()


if __name__ == "__main__":
    main()
