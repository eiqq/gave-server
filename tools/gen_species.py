"""data/bestiary.json 에서 kr.gave.mob.Species 열거형을 만든다.

바꿀 때: 아래 TUNE 표(높이 띠, 나오는 방식, 체력, 디스플레이 점수, 보상)를 고치고 다시 돌린다.
    python -X utf8 tools/gen_species.py
"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = json.loads((ROOT / "data/bestiary.json").read_text(encoding="utf-8"))
_ap = argparse.ArgumentParser()
_ap.add_argument("--out", default=str(ROOT / "plugin/src/main/java/kr/gave/mob/Species.java"), help="따로 컴파일할 때 build/wip/<이름>/... 로")
OUT = Path(_ap.parse_args().out)

ROLE = {"돌격": "CHARGE", "떼": "SWARM", "원거리": "RANGED", "함정": "TRAP", "방해": "DISRUPT", "방패": "SHIELD",
        "튼튼이": "TANK", "지원": "SUPPORT", "정예": "ELITE", "보상": "BONUS", "목표 압박": "OBJECTIVE", "보스": "BOSS"}

# 종: (높이 띠, 나오는 방식, 웨이브 1 기준 체력, 디스플레이 점수, 보상 원)
# 띠: GROUND 바닥, WAIST 허리, HEAD 머리 위, SKY 하늘. 같은 띠 = 같은 계열(지원 종이 돕는 범위).
# 방식: RING 가장자리 고리에서 몰려옴, SKY 하늘 가장자리, PLACED 경기장 안에 미리 깔림, DERIVED 다른 일의 결과로만 생김.
TUNE = {
    "G01": ("WAIST", "RING", 13, 1, 3), "G02": ("WAIST", "RING", 5, 26, 10), "G03": ("HEAD", "RING", 30, 1, 6),
    "G04": ("WAIST", "RING", 8, 1, 8), "G05": ("WAIST", "RING", 30, 1, 6), "G06": ("WAIST", "RING", 35, 1, 6),
    "G07": ("GROUND", "PLACED", 20, 1, 8), "G08": ("WAIST", "RING", 45, 1, 0), "G09": ("HEAD", "RING", 50, 1, 9),
    "G10": ("WAIST", "RING", 40, 1, 6), "G11": ("GROUND", "RING", 4, 26, 10), "G12": ("WAIST", "RING", 40, 1, 6),
    "G13": ("HEAD", "RING", 60, 1, 9), "G14": ("WAIST", "RING", 40, 1, 6), "G15": ("HEAD", "RING", 40, 1, 6),
    "G16": ("SKY", "SKY", 50, 1, 9), "G17": ("GROUND", "PLACED", 70, 2, 3), "G18": ("HEAD", "RING", 165, 1, 12),
    "G19": ("HEAD", "RING", 90, 1, 9), "G20": ("HEAD", "RING", 50, 4, 9), "G21": ("HEAD", "PLACED", 45, 2, 9),
    "G22": ("WAIST", "RING", 55, 3, 9), "G23": ("HEAD", "RING", 50, 1, 9), "G24": ("GROUND", "RING", 50, 1, 9),
    "G25": ("HEAD", "RING", 70, 3, 9), "G26": ("GROUND", "DERIVED", 6, 5, 0), "G27": ("HEAD", "RING", 195, 1, 24),
    "G28": ("SKY", "SKY", 70, 2, 9), "G29": ("WAIST", "RING", 20, 9, 18), "G30": ("GROUND", "RING", 300, 3, 12),
    "G31": ("GROUND", "RING", 70, 2, 9), "G32": ("WAIST", "RING", 50, 1, 9), "G33": ("HEAD", "RING", 90, 1, 9),
    "G34": ("WAIST", "RING", 195, 1, 24), "G35": ("GROUND", "PLACED", 150, 2, 12), "G36": ("HEAD", "RING", 60, 1, 9),
    "G37": ("HEAD", "RING", 60, 1, 9), "G38": ("WAIST", "DERIVED", 70, 2, 9), "G39": ("SKY", "SKY", 300, 3, 12),
    "G40": ("GROUND", "RING", 15, 13, 12), "G41": ("WAIST", "RING", 300, 1, 12), "G42": ("HEAD", "RING", 210, 2, 24),
    "G43": ("SKY", "PLACED", 110, 3, 12), "G44": ("GROUND", "PLACED", 195, 2, 24), "G45": ("SKY", "SKY", 30, 8, 12),
    "G46": ("HEAD", "RING", 210, 1, 24), "G47": ("HEAD", "PLACED", 35, 4, 12), "G48": ("GROUND", "PLACED", 150, 4, 12),
    "G49": ("GROUND", "PLACED", 800, 3, 15), "G50": ("GROUND", "DERIVED", 800, 1, 15), "G51": ("HEAD", "RING", 90, 1, 12),
    "G52": ("WAIST", "DERIVED", 195, 1, 24), "G53": ("SKY", "SKY", 5, 26, 15), "G54": ("GROUND", "RING", 1050, 1, 15),
    "G55": ("HEAD", "PLACED", 800, 1, 15),
}


def roles(text):
    out = []
    for part in re.split(r"[,，]\s*", text):
        part = part.strip()
        if part not in ROLE:
            raise SystemExit(f"모르는 역할: {part!r} ({text})")
        out.append("Role." + ROLE[part])
    return out


def jstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


rows = []
for v in DATA["species"]:
    sid = v["id"]
    # 처음 55종은 아래 TUNE 표, 그 뒤에 더한 종과 보스(2026-11-12)는 도감 항목의 band, spawn, hp, cost, rewardWon 칸
    band, spawn, hp, cost, reward = TUNE[sid] if sid in TUNE else (v["band"], v["spawn"], v["hp"], v["cost"], v["rewardWon"])
    rs = roles(v["role"])
    rows.append(f"    {sid}({jstr(v['name'])}, {v['tier']}, {v['threat']}, Band.{band}, Spawn.{spawn}, {hp}, {cost}, {reward}, {int(v.get('act', 1))}, "
                + ", ".join(rs) + ")")
assert len(rows) == len(DATA["species"]) == len({v["id"] for v in DATA["species"]})

src = f"""package kr.gave.mob;

import java.lang.reflect.Constructor;
import java.util.EnumSet;
import java.util.Set;

import kr.gave.game.Game;

/**
 * 게이브 종(보스 포함)의 기본 값. tools/gen_species.py 가 data/bestiary.json 과 그 안의 TUNE 표로 만든다(손으로 고치지 말 것).
 * 종 행동은 kr.gave.mob.sp.<번호>(예: kr.gave.mob.sp.G01) 클래스, 생성자 (Game, Species).
 */
public enum Species {{
{(","+chr(10)).join(rows)};

    /** 나오는 방식: RING 가장자리 고리에서 몰려옴, SKY 하늘 가장자리, PLACED 경기장 안에 미리 깔림, DERIVED 다른 일의 결과로만 생김. */
    public enum Spawn {{ RING, SKY, PLACED, DERIVED }}

    /** 한국어 이름(HUD 글귀 키 name_g01 로도 그려짐). */
    public final String ko;
    /** 처음 나올 수 있는 웨이브. */
    public final int tier;
    /** 위협 1..5. */
    public final int threat;
    /** 높이 띠, 같은 띠 = 같은 계열. */
    public final Band band;
    public final Spawn spawn;
    /** 웨이브 1 기준 체력(감독이 웨이브 배율을 곱함). */
    public final double hp;
    /** 화면 디스플레이 점수 어림값(대기열 예산). */
    public final int cost;
    /** 잡으면 주는 원(떼는 무리 합계, 0 이면 특별 보상이나 없음). */
    public final int reward;
    /** 막(1 마른 길, 2 젖은 벌, 3 재의 땅, 4 발사장): 그 막의 라운드에 나온다(docs/모듈/원정.md 2절). */
    public final int act;
    public final Set<Role> roles;

    Species(String ko, int tier, int threat, Band band, Spawn spawn, double hp, int cost, int reward, int act, Role... roles) {{
        this.ko = ko;
        this.tier = tier;
        this.threat = threat;
        this.band = band;
        this.spawn = spawn;
        this.hp = hp;
        this.cost = cost;
        this.reward = reward;
        this.act = act;
        EnumSet<Role> set = EnumSet.noneOf(Role.class);
        for (Role r : roles) set.add(r);
        this.roles = set;
    }}

    public boolean is(Role r) {{
        return roles.contains(r);
    }}

    /** 팩, 소리, 글귀 키에 쓰는 소문자 번호(g01). */
    public String key() {{
        return name().toLowerCase(java.util.Locale.ROOT);
    }}

    private Constructor<? extends Gabe> ctor;
    private boolean looked;

    /** 이 종 클래스가 있으면 true(아직 안 만든 종은 감독이 뽑지 않음). */
    public boolean implemented() {{
        return ctor() != null;
    }}

    @SuppressWarnings("unchecked")
    private Constructor<? extends Gabe> ctor() {{
        if (!looked) {{
            looked = true;
            try {{
                Class<?> c = Class.forName("kr.gave.mob.sp." + name());
                ctor = (Constructor<? extends Gabe>) c.getConstructor(Game.class, Species.class);
            }} catch (ReflectiveOperationException | ClassCastException e) {{
                ctor = null;
            }}
        }}
        return ctor;
    }}

    /** 새 개체(아직 세상에 안 놓음). 클래스가 없으면 null. */
    public Gabe create(Game game) {{
        Constructor<? extends Gabe> c = ctor();
        if (c == null) return null;
        try {{
            return c.newInstance(game, this);
        }} catch (ReflectiveOperationException e) {{
            throw new IllegalStateException(name() + " 생성 실패", e);
        }}
    }}

    public static Species of(String s) {{
        try {{
            return valueOf(s.toUpperCase(java.util.Locale.ROOT));
        }} catch (IllegalArgumentException e) {{
            for (Species sp : values()) if (sp.ko.equals(s)) return sp;
            return null;
        }}
    }}
}}
"""
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(src, encoding="utf-8")
print("Species.java", len(rows), "종")
