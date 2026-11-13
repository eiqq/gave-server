package kr.gave.trip;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * 라운드 종류 하나(원정.md 5절: 축 값의 조합 + 이름). 표는 jar 의 trip/rounds_final.json(tools/trip/rounds.py 가 초안 data/trip/rounds_draft.json 에
 * 바꾼 것을 입혀 구움). 그 파일이 없으면 초안 사본 trip/rounds.json 을 읽는다(바뀐 것 없이).
 *
 * <p>축: comp(종 갈래), tempo(수와 박자), dir(나오는 방향), env(날씨와 시야), goal(목표) + goalValue, reward(라운드 끝 보상), mods(덧붙는 값),
 * fx(Rounds 가 따로 돌리는 장치 이름: RoundFx), needs(뽑는 조건: dead = 죽은 사람이 있어야), launch(4막 발사장 전용).
 */
public final class RoundKind {

    public final String key, name, line, comp, tempo, dir, env, goal, reward, needs, phrase, linePhrase, icon, change;
    public final int act0, act1;
    public final double goalValue;
    public final boolean launch;
    private final Map<String, JsonElement> mods;
    public final Set<String> fx;

    RoundKind(JsonObject o) {
        key = str(o, "key", "r_plain");
        name = str(o, "name", key);
        line = str(o, "line", "");
        comp = str(o, "comp", "mix");
        tempo = str(o, "tempo", "normal");
        dir = str(o, "dir", "ring");
        env = str(o, "env", "base");
        goal = str(o, "goal", "kill");
        reward = str(o, "reward", "base");
        needs = str(o, "needs", null);
        phrase = str(o, "phrase", "rd_" + key);
        linePhrase = str(o, "linePhrase", "rd_" + key + "_l");
        icon = str(o, "icon", "ti_goal_" + goal);
        change = str(o, "change", null);
        JsonArray a = o.has("acts") && o.get("acts").isJsonArray() ? o.getAsJsonArray("acts") : null;
        act0 = a != null && a.size() > 0 ? a.get(0).getAsInt() : 1;
        act1 = a != null && a.size() > 1 ? a.get(1).getAsInt() : 4;
        goalValue = o.has("goalValue") && o.get("goalValue").isJsonPrimitive() ? o.get("goalValue").getAsDouble() : 0;
        launch = o.has("launch") && o.get("launch").getAsBoolean();
        Map<String, JsonElement> m = new HashMap<>();
        if (o.has("mods") && o.get("mods").isJsonObject()) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("mods").entrySet()) m.put(e.getKey(), e.getValue());
        mods = Collections.unmodifiableMap(m);
        Set<String> f = new LinkedHashSet<>();
        if (o.has("fx") && o.get("fx").isJsonArray()) for (JsonElement e : o.getAsJsonArray("fx")) f.add(e.getAsString());
        fx = Collections.unmodifiableSet(f);
    }

    /** 막 a 에서 나올 수 있나. */
    public boolean in(int a) {
        return a >= act0 && a <= act1;
    }

    public boolean has(String fxName) {
        return fx.contains(fxName);
    }

    /** mods 의 숫자(없거나 숫자가 아니면 def. true 는 1). */
    public double num(String k, double def) {
        JsonElement e = mods.get(k);
        if (e == null || !e.isJsonPrimitive()) return def;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isNumber()) return p.getAsDouble();
        if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
        return def;
    }

    /** mods 의 참거짓(숫자는 0 이 아니면 참). */
    public boolean flag(String k) {
        return num(k, 0) != 0;
    }

    /** mods 의 글(없으면 def). */
    public String str(String k, String def) {
        JsonElement e = mods.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : def;
    }

    public Set<String> modKeys() {
        return mods.keySet();
    }

    @Override
    public String toString() {
        return key + "(" + comp + "/" + tempo + "/" + dir + "/" + env + "/" + goal + (goalValue > 0 ? ":" + (int) goalValue : "") + "/" + reward + (fx.isEmpty() ? "" : "/" + fx)
            + ")";
    }

    // ------------------------------------------------------------------ 표 전체

    /** 박자 축 값(rounds axes.tempo): 총량, 체력, 고요 틱, 파도 수와 사이, 빠르기 오름, 마지막 몫. */
    public static final class Tempo {
        public final String key;
        public final double units, hp, speedUp, finalShare, finalGoal, rate;
        public final int hold, pulses, gap;

        Tempo(String key, JsonObject o) {
            this.key = key;
            units = d(o, "units", 1);
            hp = d(o, "hp", 1);
            speedUp = d(o, "speedUp", 0);
            finalShare = d(o, "finalShare", 0);
            finalGoal = d(o, "finalGoal", 1);
            rate = d(o, "rate", 0);
            hold = (int) d(o, "hold", 0);
            pulses = (int) d(o, "pulses", 0);
            gap = (int) d(o, "gap", 0);
        }

        private static double d(JsonObject o, String k, double def) {
            return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber() ? o.get(k).getAsDouble() : def;
        }
    }

    /** 특수 라운드 한 줄(specials: 키, 이름, 막, 클래스 이름). */
    public static final class SpecialDef {
        public final String key, name, cls;
        public final int act0, act1;

        SpecialDef(JsonObject o) {
            key = RoundKind.str(o, "key", "chase");
            name = RoundKind.str(o, "name", key);
            cls = RoundKind.str(o, "cls", "kr.gave.trip.special." + Character.toUpperCase(key.charAt(0)) + key.substring(1));
            JsonArray a = o.has("acts") && o.get("acts").isJsonArray() ? o.getAsJsonArray("acts") : null;
            act0 = a != null && a.size() > 0 ? a.get(0).getAsInt() : 1;
            act1 = a != null && a.size() > 1 ? a.get(1).getAsInt() : 4;
        }

        public boolean in(int a) {
            return a >= act0 && a <= act1;
        }
    }

    /** 표 전체(한 번 읽어 둠). */
    public static final class Table {
        public final Map<String, RoundKind> kinds = new LinkedHashMap<>();
        public final Map<String, List<String>> pools = new LinkedHashMap<>();
        public final Map<String, String> alias = new HashMap<>();
        public final Map<String, Tempo> tempo = new HashMap<>();
        public final List<String> launch = new ArrayList<>();
        public final List<SpecialDef> specials = new ArrayList<>();
        public String first = "r_plain";
        /** 어느 파일을 읽었나(rounds_final, rounds 초안, 없음). */
        public String source = "없음";

        public RoundKind get(String key) {
            if (key == null) return null;
            RoundKind k = kinds.get(key);
            return k != null ? k : kinds.get(alias.get(key));
        }

        public Tempo tempo(String key) {
            Tempo t = tempo.get(key);
            return t != null ? t : tempo.getOrDefault("normal", new Tempo("normal", new JsonObject()));
        }

        public SpecialDef special(String key) {
            for (SpecialDef s : specials) if (s.key.equals(key)) return s;
            return null;
        }
    }

    private static final Gson GSON = new Gson();
    private static Table table;

    /** 표(처음 부를 때 jar 에서 읽음). 못 읽으면 빈 표. */
    public static synchronized Table table() {
        if (table == null) table = load();
        return table;
    }

    /** 시험 훅 '라운드종류 x 다시': 다시 읽음. */
    public static synchronized Table reload() {
        table = load();
        return table;
    }

    private static Table load() {
        Table t = new Table();
        ClassLoader cl = RoundKind.class.getClassLoader();
        JsonObject root = null;
        for (String path : new String[] {"trip/rounds_final.json", "trip/rounds.json"}) {
            try (InputStream in = cl.getResourceAsStream(path)) {
                if (in == null) continue;
                root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                t.source = path;
                break;
            } catch (Exception e) {
                root = null;
            }
        }
        if (root == null) return t;
        JsonArray rows = root.has("kinds") ? root.getAsJsonArray("kinds") : root.has("rounds") ? root.getAsJsonArray("rounds") : new JsonArray();
        for (JsonElement e : rows) {
            if (!e.isJsonObject()) continue;
            RoundKind k = new RoundKind(e.getAsJsonObject());
            t.kinds.put(k.key, k);
        }
        if (root.has("pools") && root.get("pools").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("pools").entrySet()) {
                List<String> l = new ArrayList<>();
                for (JsonElement x : e.getValue().getAsJsonArray()) l.add(x.getAsString());
                t.pools.put(e.getKey(), l);
            }
        }
        if (root.has("alias") && root.get("alias").isJsonObject())
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("alias").entrySet()) t.alias.put(e.getKey(), e.getValue().getAsString());
        JsonObject axes = root.has("axes") && root.get("axes").isJsonObject() ? root.getAsJsonObject("axes") : null;
        if (axes != null && axes.has("tempo") && axes.get("tempo").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : axes.getAsJsonObject("tempo").entrySet())
                if (e.getValue().isJsonObject()) t.tempo.put(e.getKey(), new Tempo(e.getKey(), e.getValue().getAsJsonObject()));
        }
        if (root.has("launch") && root.get("launch").isJsonArray()) for (JsonElement e : root.getAsJsonArray("launch")) t.launch.add(e.getAsString());
        else for (RoundKind k : t.kinds.values()) if (k.key.startsWith("f_")) t.launch.add(k.key);
        if (root.has("specials") && root.get("specials").isJsonArray())
            for (JsonElement e : root.getAsJsonArray("specials")) if (e.isJsonObject()) t.specials.add(new SpecialDef(e.getAsJsonObject()));
        t.first = str(root, "first", "r_plain");
        // 초안만 읽었으면 풀이 없다: 4막 전용 빼고 모두를 plain 하나로
        if (t.pools.isEmpty()) {
            List<String> all = new ArrayList<>();
            for (RoundKind k : t.kinds.values()) if (!t.launch.contains(k.key)) all.add(k.key);
            t.pools.put("plain", all);
            t.pools.put("launch", new ArrayList<>(t.launch));
        }
        return t;
    }

    static String str(JsonObject o, String k, String def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }
}
