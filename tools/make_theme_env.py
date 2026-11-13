"""원정 네 막의 테마(하늘, 안개 색, 빛, 날씨)를 굽는다: 데이터팩 gave_env 의 시계와 타임라인, 플러그인이 읽는 trip/theme.json.

python -X utf8 tools/make_theme_env.py [--datapack <폴더>] [--theme <파일>] [--check]

- 숫자는 이 파일의 ACTS, MORNING 한곳에만 있다(색, 빛, 세계 시각, 비, 번개, 기본 안개 단계). 고친 뒤 다시 돌린다.
- 데이터팩(기본 data/datapack/gave_env): 시계 gave:theme, gave:dim, gave:rain 과 그 타임라인, 차원 타임라인 태그. 안개 시계 gave:fog 와
  타임라인 fog.json 은 건드리지 않는다. 데이터팩은 월드를 읽을 때 정해지므로 바꾼 뒤에는 서버를 다시 켜야 먹는다(새 월드는 ingame.py --fresh-world,
  실서버는 tools/make_live.py 가 깐다. 플러그인 Fog.install 만으로는 그다음 켤 때부터).
- theme.json(기본 plugin/src/main/resources/trip/theme.json): 굳힌 테마 표(kr.gave.trip.Theme 가 읽음: 세계 시각, 비, 번개, 기본 안개 단계. 색은 설명용).
  같은 폴더의 acts.json 은 tools/make_trip.py 가 data/trip/acts_draft.json 에서 복사하는 파일이라 건드리지 않는다(그 안의 themeClock 은 설계 초안 값).
- --check: 아무것도 쓰지 않고 표만 찍는다.

테마 시계 틱 = 막 x 1000 + 800 x 막 안 진행(0, 0.25, 0.5, 0.75, 1). 0..900 은 '옛 모습'(알파 0: 원정이 꺼진 판, 대기실), 5000..5800 은 발사 뒤 아침.
타임라인은 alpha_blend 로 덮는다(알파 0 이면 바닐라 낮밤과 안개 타임라인의 옛 색 그대로, 255 면 여기 색). 사이 틱은 선형으로 이어진다.
비(날씨 층)는 테마 뒤에 곱해진다: 안개 색 x (0.5, 0.5, 0.6), 하늘빛 세기는 0.24 쪽으로 31%. 그래서 비가 오는 칸의 색은 그만큼 밝게 적는다.
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STEP, SPAN = 1000, 800
LEGACY_END = 900
MORNING_AT = 5000
FRACS = (0.0, 0.25, 0.5, 0.75, 1.0)

# 막마다: 값이 둘이면 (처음, 끝)을 선형으로, 다섯이면 진행 0, 0.25, 0.5, 0.75, 1 의 값.
#   time 세계 시각(해, 달, 노을. 17000 부터는 지평선 그림자 떼(Horizon)가 사방 모두 밝은 얼굴로 선다: 그 앞은 서쪽이 검은 그림자 벽),
#   fog 안개 색, sky 하늘색, light 하늘빛 세기(땅과 사람의 밝기), lightColor 하늘빛 색,
#   ambient 주변광(가장 어두운 곳의 색, None 이면 바닐라), cloud 구름에 곱하는 색(#aarrggbb, 알파 0 이면 구름 없음),
#   star 별 밝기(None 이면 바닐라), moon 달 모양, particles 날리는 입자, rainFrom 이 진행부터 비, lightningFrom 이 진행부터 먼 번개,
#   fogBase 감독 안개 기본 단계(0 흙먼지 끝 64, 1 맑음 끝 160)
ACTS = [
    dict(act=1, key="road", name="마른 길", time=(12600, 13400),
         fog=("#7c5236", "#4c3226"), sky=("#93603f", "#4b2a24"),
         light=(0.60, 0.40), lightColor=("#ffd6ae", "#f5b89c"), ambient=None,
         cloud=("#ffffd2b0", "#ffffb8a0"), star=None, moon="full_moon", particles=[],
         rainFrom=None, lightningFrom=None, fogBase=0),
    dict(act=2, key="marsh", name="젖은 벌", time=(13600, 17000, 17150, 17300, 17450),
         # 진행 0(스테이지 6)은 비가 오기 전, 0.25 부터는 비에 곱해질 것을 미리 밝게 적은 값(화면 색은 #3b4652 → #1d2530 쯤)
         fog=("#47525e", "#768c89", "#607475", "#4c5e61", "#3a4a50"),
         sky=("#3d4c66", "#404e68", "#343f56", "#272f42", "#1a2230"),
         light=(0.36, 0.37, 0.33, 0.28, 0.24), lightColor=("#b8c6dc", "#a0b0cc"), ambient=None,
         cloud=("#ffa8b4c4", "#ff8894a8"), star=0.0, moon="full_moon", particles=[],
         rainFrom=0.25, lightningFrom=0.5, fogBase=0),
    dict(act=3, key="ash", name="재의 땅", time=(17500, 18500),
         fog=("#3c1e19", "#200e0c"), sky=("#42140f", "#160605"),
         light=(0.25, 0.17), lightColor=("#ff9c7c", "#ff8a6c"), ambient="#1a0c0a",
         cloud=("#ffff7060", "#ffc04038"), star=(0.12, 0.0), moon="new_moon",
         particles=[{"particle": {"type": "minecraft:ash"}, "probability": 0.02},
                    {"particle": {"type": "minecraft:crimson_spore"}, "probability": 0.004}],
         rainFrom=None, lightningFrom=None, fogBase=0),
    dict(act=4, key="pad", name="발사장", time=(18500, 21500),
         fog=("#0d0d12", "#050507"), sky=("#08080d", "#000000"),
         light=(0.13, 0.09), lightColor=("#c4cce0", "#b4bcd4"), ambient="#0b0d12",
         cloud=("#00ffffff", "#00ffffff"), star=0.0, moon="new_moon", particles=[],
         rainFrom=None, lightningFrom=None, fogBase=1),
]
MORNING = dict(key="morning", name="아침", time=(23000, 24000),
               fog=("#c9b8a0", "#e8d8c0"), sky=("#f0b070", "#9cc8f0"),
               light=(0.8, 1.0), lightColor=("#ffe8d0", "#ffffff"), ambient=None,
               cloud=("#ffffffff", "#ffffffff"), star=0.0, moon="full_moon", particles=[], fogBase=1)
# 어둡게(시계 gave:dim 0..100): 감독의 밤 사건, 큰 물결의 해질녘, 트럭 폭발 같은 '한 단계 더 어둡게'. 안개와 하늘은 많이, 땅 빛은 조금.
DIM = dict(fog="#404040", sky="#404040", light=0.7, cloud="#ff404040")
# 비(시계 gave:rain 0..100 = 비 세기): 클라가 비 세기만큼 안개 시작을 160 당기는 것을 되돌린다.
RAIN_FOG_START = 160.0


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in range(0, len(h), 2))


def hexs(c):
    return "#" + "".join(f"{max(0, min(255, round(v))):02x}" for v in c)


def five(v):
    """(처음, 끝)이나 다섯 값을 진행 다섯 칸의 값으로."""
    if v is None:
        return None
    if isinstance(v, (int, float, str)):
        return [v] * 5
    if len(v) == 5:
        return list(v)
    a, b = v
    if isinstance(a, str):
        ca, cb = rgb(a), rgb(b)
        return [hexs(tuple(x + (y - x) * f for x, y in zip(ca, cb))) for f in FRACS]
    return [round(a + (b - a) * f, 4) for f in FRACS]


def argb(color, alpha):
    return "#" + f"{round(alpha * 255):02x}" + color.lstrip("#")


def slots():
    """(시작 틱, 테마) 차례: 막 넷과 아침."""
    out = [(a["act"] * STEP, a) for a in ACTS]
    out.append((MORNING_AT, MORNING))
    return out


def keyframes(prop, legacy, conv):
    """옛 모습 두 칸 + 자리마다 다섯 칸. conv(값, 알파) 가 키프레임 값을 만든다. 값이 None 인 자리는 알파 0."""
    first = None
    frames = []
    for at, th in slots():
        vals = five(th.get(prop))
        for i, f in enumerate(FRACS):
            if vals is None:
                frames.append((at + round(SPAN * f), None))
            else:
                frames.append((at + round(SPAN * f), vals[i]))
                if first is None:
                    first = vals[i]
    out = [{"ticks": 0, "value": conv(first if legacy is None else legacy, 0.0)},
           {"ticks": LEGACY_END, "value": conv(first if legacy is None else legacy, 0.0)}]
    last = first
    for t, v in frames:
        if v is None:
            out.append({"ticks": t, "value": conv(last, 0.0)})
        else:
            out.append({"ticks": t, "value": conv(v, 1.0)})
            last = v
    return out


def step_frames(prop, legacy):
    """섞이지 않는 값(달 모양, 입자): 자리의 처음과 끝에 같은 값을 둔다(사이 틱이 어느 쪽으로 떨어져도 같게)."""
    out = [{"ticks": 0, "value": legacy}, {"ticks": LEGACY_END, "value": legacy}]
    for at, th in slots():
        out.append({"ticks": at, "value": th[prop]})
        out.append({"ticks": at + SPAN, "value": th[prop]})
    return out


def theme_timeline():
    tracks = {}
    tracks["minecraft:visual/fog_color"] = {"modifier": "alpha_blend", "keyframes": keyframes("fog", None, lambda v, a: argb(v, a))}
    tracks["minecraft:visual/sky_color"] = {"modifier": "alpha_blend", "keyframes": keyframes("sky", None, lambda v, a: argb(v, a))}
    tracks["minecraft:visual/sky_light_color"] = {"modifier": "alpha_blend", "keyframes": keyframes("lightColor", None, lambda v, a: argb(v, a))}
    tracks["minecraft:visual/sky_light_factor"] = {"modifier": "alpha_blend",
                                                   "keyframes": keyframes("light", None, lambda v, a: {"value": float(v), "alpha": a})}
    tracks["minecraft:visual/ambient_light_color"] = {"modifier": "alpha_blend", "keyframes": keyframes("ambient", "#0a0a0a", lambda v, a: argb(v, a))}
    tracks["minecraft:visual/star_brightness"] = {"modifier": "alpha_blend",
                                                  "keyframes": keyframes("star", 0.0, lambda v, a: {"value": float(v), "alpha": a})}
    # 구름은 곱하기(옛 모습은 흰색 = 그대로). 알파 0 이면 구름이 안 보인다
    cloud = [{"ticks": 0, "value": "#ffffffff"}, {"ticks": LEGACY_END, "value": "#ffffffff"}]
    for at, th in slots():
        for i, f in enumerate(FRACS):
            a, b = th["cloud"]
            ca, cb = rgb(a), rgb(b)
            cloud.append({"ticks": at + round(SPAN * f), "value": hexs(tuple(x + (y - x) * f for x, y in zip(ca, cb)))})
    tracks["minecraft:visual/cloud_color"] = {"modifier": "multiply", "keyframes": cloud}
    tracks["minecraft:visual/moon_phase"] = {"keyframes": step_frames("moon", "full_moon")}
    tracks["minecraft:visual/ambient_particles"] = {"modifier": "append", "keyframes": step_frames("particles", [])}
    return {"clock": "gave:theme", "tracks": tracks}


def dim_timeline():
    return {"clock": "gave:dim", "tracks": {
        "minecraft:visual/fog_color": {"modifier": "multiply", "keyframes": [{"ticks": 0, "value": "#ffffff"}, {"ticks": 100, "value": DIM["fog"]}]},
        "minecraft:visual/sky_color": {"modifier": "multiply", "keyframes": [{"ticks": 0, "value": "#ffffff"}, {"ticks": 100, "value": DIM["sky"]}]},
        "minecraft:visual/cloud_color": {"modifier": "multiply", "keyframes": [{"ticks": 0, "value": "#ffffffff"}, {"ticks": 100, "value": DIM["cloud"]}]},
        "minecraft:visual/sky_light_factor": {"modifier": "multiply", "keyframes": [{"ticks": 0, "value": 1.0}, {"ticks": 100, "value": DIM["light"]}]},
    }}


def rain_timeline():
    return {"clock": "gave:rain", "tracks": {
        "minecraft:visual/fog_start_distance": {"modifier": "add", "keyframes": [{"ticks": 0, "value": 0.0}, {"ticks": 100, "value": RAIN_FOG_START}]},
    }}


def clock_table():
    """플러그인이 읽는 themeClock(색은 설명용, 플러그인은 time, rainFrom, lightningFrom, fogBase 만 쓴다)."""
    def one(th):
        d = {k: th[k] for k in ("key", "name") if k in th}
        if "act" in th:
            d["act"] = th["act"]
        d["time"] = [int(v) for v in five(th["time"])]
        for k in ("fog", "sky", "light", "lightColor"):
            d[k] = five(th[k])
        d["ambient"] = th.get("ambient")
        d["cloud"] = list(th["cloud"])
        d["star"] = five(th.get("star"))
        d["moon"] = th["moon"]
        d["particles"] = [p["particle"]["type"] + " " + str(p["probability"]) for p in th.get("particles", [])]
        d["rainFrom"] = th.get("rainFrom")
        d["lightningFrom"] = th.get("lightningFrom")
        d["fogBase"] = th.get("fogBase", 0)
        return d
    return {
        "clock": "gave:theme", "dimClock": "gave:dim", "rainClock": "gave:rain",
        "step": STEP, "span": SPAN, "legacyEnd": LEGACY_END, "morningAt": MORNING_AT, "fracs": list(FRACS),
        "acts": [one(a) for a in ACTS], "morning": one(MORNING),
        "dim": DIM, "rainFogStart": RAIN_FOG_START,
        "note": "tools/make_theme_env.py 가 구움(손으로 고치지 않음). 틱 = 막 x 1000 + 800 x 막 안 진행. 0..900 옛 모습, 5000..5800 발사 뒤 아침. "
                "안개 거리는 감독의 안개 단계(시계 gave:fog)만 정하고 이 시계는 색, 빛, 별, 달, 날리는 재만 바꾼다. 게이브 몸은 밝기 고정",
    }


GROUND = {1: "갈라진 마른 흙 + 남북으로 뻗은 닳은 아스팔트 길(폭 5, 가운데 점선)이 중앙 원을 꿰뚫음",
          2: "젖은 진흙 판, 낮은 곳의 판 사이 금은 물길, 가장 낮은 곳은 얕은 물(물 칸 14% 쯤), 물가의 갈대와 이끼, 길은 자갈 둔덕",
          3: "검은 재 판과 그을린 돌, 재 더미, 판 사이 금의 3분의 1 과 큰 틈이 붉게 탄다(불씨, 피해 없음), 그을린 덤불",
          4: "콘크리트 판(여덟 칸 격자 이음매, 얼룩과 금)과 노란 유도선(남북, 동서 축, 고리 둘), 중앙 원 안 노랑 검정 빗금 테두리. 반지름 86 밖은 그을린 흙"}
SKY = {1: "해 질 녘. 세계 시각 12600 → 13400, 주황 먼지에서 붉은 갈색으로", 2: "초저녁(13600)에서 비 오는 밤(17000 → 17450), 푸른 잿빛에서 짙은 남색으로, 먼 번개",
       3: "한밤. 17500 → 18500, 검붉은 하늘, 달 없음, 별이 꺼져 감, 재가 날림", 4: "새벽 전. 18500 → 21500, 구름도 별도 달도 없는 검정. 발사 뒤 아침(23000 → 24000)"}
FOGTXT = {1: "따뜻한 흙빛 먼지, 감독 기본 단계 0(끝 64)", 2: "푸른 잿빛 물안개, 기본 단계 0(끝 64). 비가 와도 안개 거리는 그대로",
          3: "검붉은 연기, 기본 단계 0(끝 64: 거리로 어둡게 하지 않는다)", 4: "맑음(단계 1, 끝 160): 지평선 끝까지 다 보이고 그 너머는 검정"}
WEATHER = {1: "맑음", 2: "스테이지 7부터 비, 스테이지 8부터 먼 번개", 3: "재와 붉은 포자가 날림(바닐라 날씨는 맑음)", 4: "바람 없음(입자 없음)"}


def theme_json():
    d = clock_table()
    for a in d["acts"]:
        n = a["act"]
        a["ground"], a["skyText"], a["fogText"], a["weather"] = GROUND[n], SKY[n], FOGTXT[n], WEATHER[n]
    return d


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, indent=2) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--datapack", default=str(ROOT / "data/datapack/gave_env"))
    ap.add_argument("--theme", default=str(ROOT / "plugin/src/main/resources/trip/theme.json"))
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    files = {
        "data/gave/world_clock/theme.json": "{}\n",
        "data/gave/world_clock/dim.json": "{}\n",
        "data/gave/world_clock/rain.json": "{}\n",
        "data/gave/timeline/theme.json": dump(theme_timeline()),
        "data/gave/timeline/dim.json": dump(dim_timeline()),
        "data/gave/timeline/rain.json": dump(rain_timeline()),
        # 차례가 층 차례다: 안개(거리, 옛 색) → 테마(색을 덮음) → 어둡게(곱함) → 비(안개 시작을 되돌림)
        "data/minecraft/tags/timeline/in_overworld.json": dump({"values": ["gave:fog", "gave:theme", "gave:dim", "gave:rain"]}),
        "pack.mcmeta": dump({"pack": {"description": "게이브 환경: 안개 짙기 시계, 원정 테마 시계", "min_format": 121, "max_format": 121}}),
    }
    for th in ACTS + [MORNING]:
        f, s, l = five(th["fog"]), five(th["sky"]), five(th["light"])
        print(f"{th['name']}: 시각 {th['time'][0]}→{th['time'][-1]} 안개 {f[0]}→{f[-1]} 하늘 {s[0]}→{s[-1]} 빛 {l[0]}→{l[-1]}"
              f" 비 {th.get('rainFrom')} 번개 {th.get('lightningFrom')} 안개단계 {th.get('fogBase', 0)}")
    if a.check:
        return
    dp = Path(a.datapack)
    for rel, text in files.items():
        p = dp / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        if not p.exists() or p.read_text(encoding="utf-8") != text:
            p.write_text(text, encoding="utf-8", newline="\n")
            print("씀", p)
    if not (dp / "data/gave/timeline/fog.json").is_file():
        print("주의: 안개 타임라인 fog.json 이 없는 폴더다(시험용 폴더면 진짜 것을 복사해 넣을 것)", file=sys.stderr)
    ap_ = Path(a.theme)
    text = dump(theme_json())
    ap_.parent.mkdir(parents=True, exist_ok=True)
    if not ap_.exists() or ap_.read_text(encoding="utf-8") != text:
        ap_.write_text(text, encoding="utf-8", newline="\n")
        print("씀", ap_)


if __name__ == "__main__":
    main()
