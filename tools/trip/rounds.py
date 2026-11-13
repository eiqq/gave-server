"""원정 라운드 종류 100 굳히기(trip_rounds, 원정.md 5절, 묶음 7).

python -X utf8 tools/trip/rounds.py            (혼자 돌려도 되고, tools/make_trip.py 가 bake(layer) 를 부른다)

- 입력: data/trip/rounds_draft.json(설계 초안 100줄: 손으로 고치지 않는 파일), 풀은 plugin/src/main/resources/trip/route.json 의 pools(길 부품 trip_route 가 굽는 것),
  특수 라운드 목록은 data/trip/specials_draft.json.
- 출력: plugin/src/main/resources/trip/rounds_final.json(플러그인 kr.gave.trip.RoundKind 가 jar 에서 읽음. make_trip 이 초안을 그대로 복사하는
  resources/trip/rounds.json 은 읽지 않는 자리), data/phrases/trip_rounds.json(HUD 글귀: 라운드 이름 rd_<키>, 한 줄 rd_<키>_l, 원인과 알림 글귀).
- 초안과 달라진 것은 아래 CHANGE 표 한곳(까닭 why 와 함께 rounds_final.json 의 각 줄 change 칸에 남는다). 숫자 손잡이는 Java 쪽 Rounds.Tune 한곳.
- 팩 층에는 아무것도 넣지 않는다(모델은 기존 것: 바퀴, 탄약통, 쓰러짐 고리, 연출 셰이더).
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
DRAFT = ROOT / "data/trip/rounds_draft.json"
ROUTE = ROOT / "plugin/src/main/resources/trip/route.json"
SPECIALS = ROOT / "data/trip/specials_draft.json"
OUT = ROOT / "plugin/src/main/resources/trip/rounds_final.json"
PHRASES = ROOT / "data/phrases/trip_rounds.json"

# 특수 라운드 클래스 이름(원정.md 5.3절 '새로 필요한 것' 칸): kr.gave.trip.special.<이름>
SPECIAL_CLASS = {
    "chase": "Chase", "breakdown": "Breakdown", "refuel": "Refuel", "bridge": "Bridge", "blackout": "Blackout",
    "sandstorm": "Sandstorm", "hush": "Hush", "airdrop": "Airdrop", "stampede": "Stampede", "hostage": "Hostage",
    "gallery": "Gallery", "scatter": "Scatter", "tide": "Tide", "callnames": "CallNames", "sinking": "Sinking",
    "ram": "Ram", "eyeson": "EyesOn", "facestorm": "FaceStorm", "lastrun": "LastRun", "litpad": "LitPad",
    "fuelfire": "FuelFire", "skyfall": "SkyFall",
}

# 4막 전용(발사장 31~34, trip_launch 가 고름). Rounds 는 Launch 부품이 있으면 비켜 주고, 없으면 건조를 '자리 지키기 꼴 건조'로 돌린다
LAUNCH = ["f_fuel", "f_frame", "f_power", "f_board", "f_lights", "f_pad", "f_count", "f_everyone"]

# 초안에서 바꾼 것(키 → 덮어쓸 칸, why. mods 는 더하고 _mods 는 통째로 바꿈). fx = Rounds 가 따로 돌리는 장치(RoundFx), dir/env 는 새 값을 더한 것도 있음
CHANGE = {
    # 재미 장치(현상금 게이브)가 취소되어 그 장치 자체인 라운드를 바꿈
    "r_bounty3": {"_replace": "r_supply"},
    "r_hunt_gold": {"fx": ["flee"], "mods": {"flee": 45},
                    "why": "재미 현상금(Bounty) 대신 Goals 의 사냥 표적이 금빛이 되고 사람을 피해 달아나며 45초 뒤 사라짐(재미 장치에 기대지 않음)"},
    "u_curse2": {"fx": ["curse2"], "_mods": {"money": 2.5},
                 "why": "재미 저주 카드(취소) 대신 이 라운드가 스스로 저주 둘을 뽑아 건다(빠름, 질김, 아픔, 안개, 어둠, 탄약값, 트럭 노림 가운데 둘)"},
    # 비슷한 둘을 가름(숫자만 다르지 않게)
    "r_sky": {"env": "base", "why": "r_clear 와 겹쳐 안개를 그대로 둠: 하늘 종이 흙먼지 위에서 내리꽂는다(맑음은 r_clear)"},
    "r_clear": {"fx": ["searchlight"], "mods": {"hp": 1.25},
                "why": "감독맵의 맑은 웨이브(하늘 종만, 체력 +25%, 탐조등)를 이 라운드로 옮김"},
    "r_storm": {"fx": ["strike", "flash"],
                "why": "번개가 실제로 떨어진다(1.5초 예고 원, 둘레 게이브 큰 피해, 사람 작은 피해). 번쩍일 때 2초 맑음"},
    "u_lightning": {"fx": ["flash"], "mods": {"dark": True, "dim": 1.0}, "env": "fog5",
                    "why": "r_storm 과 가름: 칠흑(안개 5 + 어둠 가득)이고 번개는 비추기만 함"},
    "u_under": {"dir": "under", "why": "r_near 와 가름: 사람 바로 곁(7~11블록)에서 솟는다(1.5초 땅 흔들림 예고)"},
    "u_hands": {"dir": "truck", "mods": {"truckShare": 0.7, "truckDmg": 1.5},
                "why": "바퀴를 잡는 손: 트럭 둘레 6~12블록 땅에서 솟고 트럭이 받는 피해 x1.5"},
    "u_chain": {"fx": ["hook"], "why": "하늘 종이 사슬(셰이더 광선)로 사람을 걸어 끌어올린다: 그 게이브를 맞히면 끊김"},
    "u_tide": {"acts": [2, 2], "fx": ["tide", "rain"],
               "why": "물 칸은 젖은 벌 땅에만 있어 2막 전용으로(물이 차오르며 마른 땅이 줄고, 물 칸에 선 사람은 느려짐)"},
    "u_blackwater": {"acts": [2, 2], "fx": ["blackwater"], "mods": {"holdAt": "road"},
                     "why": "물 칸은 젖은 벌 땅에만 있어 2막 전용. 검은 물에 선 사람은 초당 피해, 지킬 원은 길 둔덕 위"},
    "u_latent": {"fx": ["latent"],
                 "why": "판 전체 잠복 상한(config latentCap)은 Players 몫이라 못 바꿈: 잠복 종 위주 + 잠복 덮어쓰기 대신 붙은 사람이 있으면 그 둘레 게이브가 빨라짐"},
    "r_split": {"fx": ["split"], "why": "감독 변이는 판 내내 남아서 이 라운드에만 쪼개짐을 Rounds 가 직접 건다"},
    "r_pop": {"fx": ["burst"], "why": "감독 변이는 판 내내 남아서 이 라운드에만 터짐을 Rounds 가 직접 건다"},
    "r_riders": {"fx": ["riders"], "why": "감독 변이는 판 내내 남아서 이 라운드에만 올라타기를 Rounds 가 직접 건다"},
    "r_gold": {"fx": ["gold"]},
    "r_flicker": {"fx": ["flicker"]},
    "r_start": {"fx": ["flicker"], "goal": "start", "goalValue": 75,
                "why": "버티기 대신 시동 막대: 트럭 앞(보닛) 4블록 안에 사람이 있으면 차고, 트럭이 맞으면 깎인다"},
    "r_rain": {"fx": ["rain"]},
    "r_wind": {"fx": ["wind"], "why": "탄 사거리 대신 바람 쪽 게이브가 받는 피해 x0.6(12블록 넘게 떨어진 것), 바람 쪽 게이브 빠르기 x1.2"},
    "r_box": {"fx": ["carrier"], "why": "상자를 문 놈: 표적 하나가 금빛이고 늘 화살촉으로 보인다(잡으면 모두 무기 상자 공짜)"},
    "r_cargo": {"fx": ["cargo"]},
    "r_thin": {"fx": ["scarce"], "mods": {"ammoPrice": 2.0, "autoPrice": 2.0},
               "why": "r_plain 과 값만 달라서: 라운드가 열릴 때 모두의 예비탄이 반으로 줄고 탄약 칸이 두 배 값(끝나면 보상으로 가득)"},
    "r_ammo": {"mods": {"ammoPrice": 2.0, "autoPrice": 2.0}},
    "u_mire": {"fx": ["mire", "rain"]},
    "u_nest": {"fx": ["eggs"], "why": "알 무더기는 땅에 놓인 쏠 수 있는 알 여섯(알주머니 그림): 35초 안에 못 깨면 깨어나 떼가 쏟아진다"},
    "u_bound": {"fx": ["haul"], "why": "끄는 넷이 보급 상자를 바깥으로 끌고 간다: 넷을 다 잡으면 상자가 남고(모두 무기 상자), 바깥 60블록에 닿으면 잃음"},
    "u_echo": {"fx": ["echo"]},
    "u_faces": {"fx": ["faces"], "why": "죽은 적 있는 사람의 얼굴과 이름을 쓴 사람 탈 게이브(G50)가 섞임(없으면 흉내 종만)"},
    "u_names": {"fx": ["names"], "why": "사냥 표적 넷 머리 위에 판에 든 사람 이름(아무나: 원정.md 14절 9번)"},
    "u_learn": {"fx": ["learn"], "why": "판 전체 G51 배움과 따로, 이 라운드에 가장 많이 맞힌 무기 키가 x0.6(15초마다 다시 셈)"},
    "u_armed": {"fx": ["armed"], "why": "총 주운 게이브(G52)를 감독 대신 Rounds 가 직접 낸다(사람 수에 따라 2~8)"},
    "u_bait": {"fx": ["bait"], "why": "r_trap 과 가름: 탄약통 미끼 다섯(줍거나 다가가면 덮침) + 진짜 보급 상자 하나"},
    "u_blessing": {"fx": ["blessing"]},
    "u_ember": {"fx": ["ember"]},
    "u_ashfall": {"fx": ["shadow"], "mods": {"dim": 0.4}, "why": "그림자만 보인다: 게이브 얼굴이 빈 얼굴(PAL_BLANK)로, 재 안개"},
    "u_elite_pair": {"fx": ["pair"], "why": "정예 둘이 12블록 안에 붙어 있으면 서로 체력을 채우고 피해를 반만 받는다"},
    "u_no_shop": {"fx": ["shoplock", "flicker"]},
    "u_decoy": {"fx": ["decoy"]},
    "u_revenge": {"needs": "dead", "fx": ["revenge"], "why": "죽은 사람이 없으면 이 라운드는 뽑지 않음(되돌아올 동료가 없음)"},
    "u_stare": {"fx": ["gaze"]},
    "u_carrion": {"fx": ["feed"]},
    "u_close": {"fx": ["shrink"]},
    "u_hush": {"fx": ["noise"], "dir": "noise", "why": "쏜 사람 쪽(가운데에서 본 각)에서 다음 무리가 나온다"},
    # 숫자만 다른 짝을 장치로 가름(2026-11-12 trip_rounds: 100개 모두 다르게 놀게)
    "r_glass": {"comp": "charge", "mods": {"hp": 0.15, "dmg": 1.8, "speed": 1.15}, "fx": ["pale"],
                "why": "r_flood 와 숫자만 달라서: 한 방에 죽는 창백한 돌격 떼(떼 종 대신 달려드는 종)"},
    "r_thick": {"fx": ["regen"], "why": "r_few 와 숫자만 달라서: 3초 안 맞으면 초당 4.5% 씩 다시 찬다(몰아 쏴야 함)"},
    "r_slow": {"fx": ["wall"], "why": "r_few 와 가름: 모두 같은 반지름(62)에서 한 줄 벽으로 나와 같은 빠르기로 조여 온다"},
    "r_survive": {"fx": ["tight"], "why": "r_trickle 과 숫자만 달라서: 120초 동안 트럭 둘레 반지름 16 고리 안에서만(밖은 밀려나며 탐)"},
    "r_cargo": {"fx": ["cargo"], "why": "r_hold 와 가름: 원 가운데 짐에 체력이 있고 게이브 일부가 짐을 노린다(부서지면 놓침)"},
    "r_ammo": {"fx": ["drops"], "why": "r_collect 와 가름: 탄약통은 놓여 있지 않고 잡은 게이브가 떨군다(싸우는 곳에서 주워 와야 함)"},
    "r_objective": {"fx": ["kamikaze"], "why": "r_truck 과 숫자만 달라서: 트럭 노림 개체가 트럭에 닿으면 터진다(트럭 1.5%, 둘레 사람 피해)"},
    "r_fast": {"fx": ["lunge"], "why": "r_ramp 와 숫자만 달라서: 3초마다 반 초씩 2.6배로 튀어 나간다"},
    "f_fuel": {"fx": ["flicker"]},
    "f_lights": {"fx": ["flicker"]},
    "f_power": {"mods": {"dim": 0.5}},
}

# 새로 넣은 종류(바꾼 것의 자리)
NEW = {
    "r_supply": {"key": "r_supply", "name": "보급 비", "line": "상자가 쏟아진다", "acts": [1, 4], "comp": "swarm", "tempo": "flood", "dir": "ring",
                 "env": "base", "goal": "kill", "goalValue": 0, "reward": "base", "mods": {}, "fx": ["crates3"],
                 "why": "r_bounty3(현상금 셋: 재미 장치 현상금 게이브 그 자체, 사용자가 취소)을 바꿈. 진짜 팀 보급 상자 셋이 15, 45, 75초에 떨어지고(쏘면 부서짐), 떼가 쏟아진다"},
}

# HUD 글귀(라운드 이름과 한 줄 말고)
EXTRA_PHRASES = {
    "cause_trip_ember": "불씨에 탔다",
    "cause_trip_strike": "번개에 맞았다",
    "cause_trip_water": "검은 물에 잠겼다",
    "cause_trip_ring": "고리 밖에서 탔다",
    "cause_trip_bait": "미끼를 집었다",
    "cause_trip_lamp": "가짜 등불에 홀렸다",
    "gl_hold": "자리",
    "gl_carry": "운반",
    "gl_start": "시동",
    "gl_build": "건조",
    "rd_done": "해냈다",
    "rd_fail": "놓쳤다",
    "rd_escaped": "달아났다",
    "rd_hatch": "알이 깨어난다",
    "rd_lost_cargo": "짐을 잃었다",
    "rd_hooked": "끌려간다",
    "rd_shop_locked": "상점이 잠겼다",
    "rd_cv_fast": "빠르다",
    "rd_cv_thick": "질기다",
    "rd_cv_hurt": "아프다",
    "rd_cv_fog": "안개가 짙다",
    "rd_cv_dark": "더 어둡다",
    "rd_cv_thin": "탄약값 두 배",
    "rd_cv_truck": "트럭을 노린다",
    "rd_wind": "바람이 분다",
    "rd_spin": "쏠림이 돈다",
    "rd_learned": "배웠다",
    "cause_trip_kamikaze": "자폭에 휘말렸다",
    "rd_cargo_hit": "짐이 부서진다",
}

GOAL_ICON = {"kill": "kill", "survive": "survive", "truck": "truck", "hold": "hold", "collect": "collect", "hunt": "hunt", "timed": "timed",
             "build": "build", "start": "start"}


def load_json(p: Path):
    return json.loads(p.read_text(encoding="utf-8"))


def build():
    draft = load_json(DRAFT)
    kinds, why = [], {}
    alias = {}
    for r in draft["rounds"]:
        k = r["key"]
        ch = CHANGE.get(k, {})
        if "_replace" in ch:
            n = dict(NEW[ch["_replace"]])
            alias[k] = n["key"]
            n["change"] = n.pop("why")
            n["was"] = k
            kinds.append(n)
            continue
        row = dict(r)
        row["mods"] = dict(r.get("mods", {}))
        row["fx"] = []
        for f, v in ch.items():
            if f == "why":
                continue
            if f == "mods":
                row["mods"].update(v)
            elif f == "_mods":
                row["mods"] = dict(v)
            else:
                row[f] = v
        if "why" in ch:
            row["change"] = ch["why"]
        row["launch"] = k in LAUNCH
        kinds.append(row)
    keys = [x["key"] for x in kinds]
    assert len(keys) == 100 and len(set(keys)) == 100, len(keys)
    for x in kinds:
        x.setdefault("fx", [])
        x.setdefault("launch", False)
        x["icon"] = "ti_goal_" + GOAL_ICON.get(x["goal"], "kill")
        x["phrase"] = "rd_" + x["key"]
        x["linePhrase"] = "rd_" + x["key"] + "_l"
    # 풀: 길 부품의 route.json pools 를 그대로(바꾼 키는 새 키로)
    route = load_json(ROUTE)
    pools = {}
    for name, lst in route.get("pools", {}).items():
        pools[name] = [alias.get(k, k) for k in lst]
    pools["launch"] = list(LAUNCH)
    for name, lst in pools.items():
        for k in lst:
            if not k.startswith("special") and k not in keys:
                raise SystemExit(f"풀 {name} 에 모르는 키 {k}")
    unpooled = [k for k in keys if not any(k in l for l in pools.values())]
    if unpooled:
        raise SystemExit(f"어느 풀에도 없는 종류: {unpooled}")
    sp = load_json(SPECIALS)
    specials = []
    for s in sp["specials"]:
        specials.append({"key": s["key"], "name": s["name"], "acts": s["acts"], "line": s.get("line", ""),
                         "cls": "kr.gave.trip.special." + SPECIAL_CLASS.get(s["key"], s["key"].capitalize())})
    out = {
        "note": "원정 라운드 종류 100(보통 60 + 뒤 막 전용 40). tools/trip/rounds.py 가 data/trip/rounds_draft.json 에 CHANGE 표를 입혀 구움(손으로 고치지 않음). "
                "축 값 뜻은 axes, 바뀐 줄은 change 칸, 숫자 손잡이는 kr.gave.trip.Rounds.Tune",
        "axes": draft["axes"],
        "kinds": kinds,
        "pools": pools,
        "alias": alias,
        "launch": LAUNCH,
        "first": "r_plain",
        "specials": specials,
    }
    phrases = {}
    for x in kinds:
        phrases[x["phrase"]] = x["name"]
        phrases[x["linePhrase"]] = x["line"]
    phrases.update(EXTRA_PHRASES)
    for k, v in list(phrases.items()) + [("_", json.dumps(out, ensure_ascii=False))]:
        if "·" in v:
            raise SystemExit(f"가운뎃점: {k}")
    return out, phrases


def write(out, phrases):
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    PHRASES.write_text(json.dumps(phrases, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def bake(layer: Path):
    out, phrases = build()
    write(out, phrases)
    changed = sum(1 for x in out["kinds"] if "change" in x)
    return {"kinds": len(out["kinds"]), "changed": changed, "pools": len(out["pools"]), "phrases": len(phrases)}


if __name__ == "__main__":
    o, ph = build()
    write(o, ph)
    print(f"rounds_final.json {len(o['kinds'])} kinds, changed {sum(1 for x in o['kinds'] if 'change' in x)}, phrases {len(ph)} -> {OUT}, {PHRASES}")
    sys.exit(0)
