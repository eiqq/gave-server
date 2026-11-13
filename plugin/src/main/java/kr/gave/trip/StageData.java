package kr.gave.trip;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 길 데이터(jar 의 trip/route.json: tools/trip/route.py 가 data/trip/stages_draft.json 을 굳힌 것)를 읽어 둔 표.
 * 스테이지 종류, 라운드 풀, 막별 무게, 규칙 숫자, 종류별 규칙과 보상(effects), 보스 길목 준비, 스테이지 카드, 사건, 소품 묶음.
 * 파일이 없거나 깨졌으면 전투 한 종류만 든 가장 작은 표로 떨어진다(판은 돈다).
 */
public final class StageData {

    /** 스테이지 종류(표지판에 보이는 것). */
    public static final class Type {
        public final String key, name, first, second, sign;
        public final int risk;
        public final List<String> reward;

        Type(String key, JsonObject o) {
            this.key = key;
            this.name = str(o, "name", key);
            this.risk = (int) num(o, "risk", 1);
            this.first = str(o, "first", "pool:plain");
            this.second = str(o, "second", "pool:plain");
            this.sign = str(o, "sign", key);
            this.reward = strs(o, "reward");
        }
    }

    /** 스테이지 카드(막마다 이름과 소품이 다른 같은 종류). */
    public static final class Card {
        public final String key, type, name, props, special;
        public final int act;

        Card(JsonObject o) {
            key = str(o, "key", "card");
            act = (int) num(o, "act", 1);
            type = str(o, "type", "fight");
            name = str(o, "name", key);
            props = str(o, "props", "none");
            special = str(o, "special", null);
        }
    }

    /** 보스 길목의 준비 셋 가운데 하나. */
    public static final class Gate {
        public final String key, name, icon, first;
        public final int risk;

        Gate(JsonObject o) {
            key = str(o, "key", "gate");
            name = str(o, "name", key);
            icon = str(o, "icon", "rest");
            first = str(o, "first", "pool:plain");
            risk = (int) num(o, "risk", 3);
        }
    }

    /** 사건(선택 없이 진행). ready 가 아닌 것은 굴리지 않는다. */
    public static final class Event {
        public final String key, line;
        public final int actFrom, actTo;
        public final boolean ready;
        public final Effect effect;

        Event(JsonObject o) {
            key = str(o, "key", "ev");
            line = str(o, "line", "");
            JsonArray a = o.has("acts") && o.get("acts").isJsonArray() ? o.getAsJsonArray("acts") : null;
            actFrom = a != null && a.size() > 0 ? a.get(0).getAsInt() : 1;
            actTo = a != null && a.size() > 1 ? a.get(1).getAsInt() : 3;
            ready = o.has("ready") && o.get("ready").getAsBoolean();
            effect = new Effect(o);
        }
    }

    /** 종류(또는 준비, 사건) 하나의 규칙과 보상: 때(arrive, round, end, boss) → 이름 → 값, 그리고 배율(mul). */
    public static final class Effect {
        public final Map<String, Double> arrive, round, end, mul, boss;
        /** arrive.mob 같은 글 값. */
        public final Map<String, String> text = new LinkedHashMap<>();

        Effect(JsonObject o) {
            arrive = nums(o, "arrive", text);
            round = nums(o, "round", text);
            end = nums(o, "end", text);
            mul = nums(o, "mul", text);
            boss = nums(o, "boss", text);
        }

        public boolean empty() {
            return arrive.isEmpty() && round.isEmpty() && end.isEmpty() && mul.isEmpty() && boss.isEmpty();
        }
    }

    /** 소품 묶음 하나(카드의 props). */
    public static final class Props {
        public final String key, layout;
        public final List<String> models;
        public final int count, spots;
        public final double gap;

        Props(String key, JsonObject o) {
            this.key = key;
            layout = str(o, "layout", "scatter");
            models = strs(o, "models");
            count = (int) num(o, "count", 8);
            spots = (int) num(o, "spots", 3);
            gap = num(o, "gap", 8);
        }
    }

    public final Map<String, Type> types = new LinkedHashMap<>();
    public final Map<String, List<String>> pools = new LinkedHashMap<>();
    /** 막(1..3) → 종류 → 무게. */
    public final Map<Integer, Map<String, Integer>> weights = new LinkedHashMap<>();
    /** 막(1..3) → 미지가 드러나는 종류 → 무게. */
    public final Map<Integer, Map<String, Integer>> unknown = new LinkedHashMap<>();
    public final Map<String, Effect> effects = new LinkedHashMap<>();
    public final List<Gate> gates = new ArrayList<>();
    public final List<Card> cards = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public final Map<String, Props> scenery = new LinkedHashMap<>();
    public final Map<String, String> rewardIcon = new LinkedHashMap<>();
    public final List<String> safe = new ArrayList<>();
    /** 막 → {from, to}(스테이지), 막 → 특수 키: 아무도 험로를 안 골라도 끼는 특수 라운드. */
    public final Map<Integer, int[]> forced = new LinkedHashMap<>();
    public final Map<Integer, String> forcedSpecial = new LinkedHashMap<>();

    /** 투표 틱: 열림, 이동 시작 몇 틱 전에 닫나, 상점과 투표 길이(이동 시작), 동률 깜빡임, 안 고른 사람 알림. 겨누는 거리와 맞는 반지름(블록). */
    public int voteOpen = 40, voteClose = 60, voteShop = 400, voteTie = 40, voteHint = 180;
    public double voteReach = 60, voteRadius = 2.6;
    /** 이동 틱(보통, 막 넘김), 가둠 반지름, 안개 단계, 흐름 빠르기(블록/틱), 가려지는 때와 드러나는 때(이동 시작 뒤, 끝 앞 틱). */
    public int travelNormal = 200, travelAct = 320, travelFog = 5, travelHide = 40, travelReveal = 40;
    public double travelConfine = 8, travelSpeed = 1.2;
    /** 정비 보장 문턱(트럭 체력 비율 아래, 죽은 사람 비율 이상). */
    public double restTruck = 0.4, restDead = 0.2;
    /** 읽기 결과 한 줄(서버 기록). */
    public String note = "";

    private static final Gson GSON = new Gson();

    public static StageData load(ClassLoader cl, Logger log) {
        StageData d = new StageData();
        try (InputStream in = cl.getResourceAsStream("trip/route.json")) {
            if (in == null) throw new IllegalStateException("jar 에 trip/route.json 없음");
            JsonObject o = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            d.read(o);
            d.note = "종류 " + d.types.size() + " 카드 " + d.cards.size() + " 사건 " + d.events.size() + " 소품 묶음 " + d.scenery.size();
        } catch (Exception e) {
            d = new StageData();
            d.fallback();
            d.note = "route.json 읽기 실패(" + e + "): 전투만으로 돈다";
            if (log != null) log.warning("[길] " + d.note);
        }
        return d;
    }

    private void fallback() {
        JsonObject f = new JsonObject();
        f.addProperty("name", "전투");
        types.put("fight", new Type("fight", f));
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("fight", 1);
        for (int act = 1; act <= 3; act++) weights.put(act, w);
        safe.add("fight");
    }

    private void read(JsonObject o) {
        for (Map.Entry<String, JsonElement> e : obj(o, "types").entrySet()) types.put(e.getKey(), new Type(e.getKey(), e.getValue().getAsJsonObject()));
        if (!types.containsKey("fight")) throw new IllegalStateException("types 에 fight 없음");
        for (Map.Entry<String, JsonElement> e : obj(o, "pools").entrySet()) {
            List<String> l = new ArrayList<>();
            for (JsonElement x : e.getValue().getAsJsonArray()) l.add(x.getAsString());
            pools.put(e.getKey(), l);
        }
        ints(obj(o, "weights"), weights);
        JsonObject r = obj(o, "rules");
        ints(obj(r, "unknown"), unknown);
        JsonObject v = obj(r, "vote");
        voteOpen = (int) num(v, "open", voteOpen);
        voteClose = (int) num(v, "close", voteClose);
        voteShop = (int) num(v, "shop", voteShop);
        voteTie = (int) num(v, "tie", voteTie);
        voteHint = (int) num(v, "hint", voteHint);
        voteReach = num(v, "reach", voteReach);
        voteRadius = num(v, "radius", voteRadius);
        JsonObject t = obj(r, "travel");
        travelNormal = (int) num(t, "normal", travelNormal);
        travelAct = (int) num(t, "act", travelAct);
        travelFog = (int) num(t, "fog", travelFog);
        travelHide = (int) num(t, "hide", travelHide);
        travelReveal = (int) num(t, "reveal", travelReveal);
        travelConfine = num(t, "confine", travelConfine);
        travelSpeed = num(t, "speed", travelSpeed);
        JsonObject rest = obj(r, "rest");
        restTruck = num(rest, "truck", restTruck);
        restDead = num(rest, "dead", restDead);
        safe.addAll(strs(r, "safe"));
        if (safe.isEmpty()) safe.add("fight");
        for (Map.Entry<String, JsonElement> e : obj(r, "forced").entrySet()) {
            JsonObject f = e.getValue().getAsJsonObject();
            int act = Integer.parseInt(e.getKey());
            forced.put(act, new int[] {(int) num(f, "from", 2), (int) num(f, "to", 4)});
            forcedSpecial.put(act, str(f, "special", ""));
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "effects").entrySet()) effects.put(e.getKey(), new Effect(e.getValue().getAsJsonObject()));
        if (o.has("gates")) for (JsonElement x : o.getAsJsonArray("gates")) gates.add(new Gate(x.getAsJsonObject()));
        if (o.has("cards")) for (JsonElement x : o.getAsJsonArray("cards")) cards.add(new Card(x.getAsJsonObject()));
        if (o.has("events")) for (JsonElement x : o.getAsJsonArray("events")) events.add(new Event(x.getAsJsonObject()));
        for (Map.Entry<String, JsonElement> e : obj(o, "scenery").entrySet()) scenery.put(e.getKey(), new Props(e.getKey(), e.getValue().getAsJsonObject()));
        for (Map.Entry<String, JsonElement> e : obj(o, "rewardIcon").entrySet()) rewardIcon.put(e.getKey(), e.getValue().getAsString());
    }

    // ------------------------------------------------------------------ 찾기

    public Type type(String key) {
        Type t = types.get(key);
        return t != null ? t : types.get("fight");
    }

    public Gate gate(String key) {
        for (Gate g : gates) if (g.key.equals(key)) return g;
        return null;
    }

    public Card card(String key) {
        for (Card c : cards) if (c.key.equals(key)) return c;
        return null;
    }

    public Event event(String key) {
        for (Event e : events) if (e.key.equals(key)) return e;
        return null;
    }

    /** 그 막, 그 종류의 카드들(없으면 빈 목록). */
    public List<Card> cards(int act, String type) {
        List<Card> out = new ArrayList<>();
        for (Card c : cards) if (c.act == act && c.type.equals(type)) out.add(c);
        return out;
    }

    /** 그 막에 굴릴 수 있는 사건(준비된 것만). */
    public List<Event> events(int act) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) if (e.ready && act >= e.actFrom && act <= e.actTo) out.add(e);
        return out;
    }

    /** 그 막의 종류 무게(1..3 밖은 가장 가까운 막). */
    public Map<String, Integer> weights(int act) {
        Map<String, Integer> w = weights.get(Math.max(1, Math.min(3, act)));
        return w != null ? w : Collections.singletonMap("fight", 1);
    }

    public Map<String, Integer> unknown(int act) {
        Map<String, Integer> w = unknown.get(Math.max(1, Math.min(3, act)));
        return w != null && !w.isEmpty() ? w : Collections.singletonMap("fight", 1);
    }

    public Effect effect(String key) {
        return key == null ? null : effects.get(key);
    }

    public boolean safe(String type) {
        return safe.contains(type);
    }

    // ------------------------------------------------------------------ JSON 도우미

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private static String str(JsonObject o, String k, String def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static double num(JsonObject o, String k, double def) {
        try {
            return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static List<String> strs(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o != null && o.has(k) && o.get(k).isJsonArray()) for (JsonElement x : o.getAsJsonArray(k)) out.add(x.getAsString());
        return out;
    }

    private static Map<String, Double> nums(JsonObject o, String k, Map<String, String> text) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : obj(o, k).entrySet()) {
            if (!e.getValue().isJsonPrimitive()) continue;
            if (e.getValue().getAsJsonPrimitive().isNumber()) out.put(e.getKey(), e.getValue().getAsDouble());
            else if (e.getValue().getAsJsonPrimitive().isBoolean()) out.put(e.getKey(), e.getValue().getAsBoolean() ? 1.0 : 0.0);
            else text.put(k + "." + e.getKey(), e.getValue().getAsString());
        }
        return out;
    }

    private static void ints(JsonObject o, Map<Integer, Map<String, Integer>> into) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            Map<String, Integer> w = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> x : e.getValue().getAsJsonObject().entrySet()) w.put(x.getKey(), x.getValue().getAsInt());
            try {
                into.put(Integer.parseInt(e.getKey()), w);
            } catch (NumberFormatException ex) {
                // 막 번호가 아닌 키는 건너뜀
            }
        }
    }
}
