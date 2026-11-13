package kr.gave.game;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 증강 데이터(jar 안 trip/augments_final.json, 원본 plugin/src/main/resources/trip/augments_final.json: build/trip_aug/make_augments.py 가 구움.
 * 같은 폴더의 augments.json 은 tools/make_trip.py 가 초안 data/trip/augments_draft.json 을 그대로 복사하는 자리라 쓰지 않는다): 이름, 한 줄, 등급, 갈래, 묶임(무기 종류 키),
 * 아이콘, 필요한 부품(needs), 겹침 한도(max), 뽑기 무게(w), 숫자(n). Augments.Aug 생성자가 키로 찾아 읽는다(파일이 없거나 키가 없으면 그 증강은
 * 꺼진 채로 남아 뽑기에 안 나옴). 숫자는 모두 이 파일 한곳에서 바꾼다(파워커브 작업자용 표는 build/trip_aug/표.md).
 */
final class AugData {

    static final class Meta {
        String name, line, tier, cat, bind, icon, needs;
        int max = 1;
        double w = 1;
        final Map<String, Double> n = new HashMap<>();
    }

    static final Map<String, Meta> META = new LinkedHashMap<>();
    /** 기본 판매(staples): 키 → 값(name, line 은 글, 나머지는 숫자) */
    static final Map<String, Meta> STAPLES = new LinkedHashMap<>();
    /** 뽑기 규칙 숫자(roll): risk = 한 뽑기에 위험 카드가 끼는 확률, base = 옛 증강 무게, weapon = 무기 증강 무게 */
    static final Map<String, Double> ROLL = new HashMap<>();
    static String error;

    static {
        load();
    }

    private AugData() {
    }

    static Meta of(String key) {
        return META.get(key);
    }

    static double roll(String key, double def) {
        Double v = ROLL.get(key);
        return v == null ? def : v;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static Meta meta(JsonObject o) {
        Meta m = new Meta();
        m.name = str(o, "name");
        m.line = str(o, "line");
        m.tier = str(o, "tier");
        m.cat = str(o, "cat");
        m.bind = str(o, "bind");
        m.icon = str(o, "icon");
        m.needs = str(o, "needs");
        if (o.has("max")) m.max = o.get("max").getAsInt();
        if (o.has("w")) m.w = o.get("w").getAsDouble();
        if (o.has("n") && o.get("n").isJsonObject())
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("n").entrySet()) m.n.put(e.getKey(), e.getValue().getAsDouble());
        return m;
    }

    static void load() {
        META.clear();
        STAPLES.clear();
        ROLL.clear();
        error = null;
        // 굳힌 파일 augments_final.json 을 먼저. 없으면 augments.json(tools/make_trip.py 가 초안을 그대로 복사해 두는 자리)
        InputStream first = AugData.class.getResourceAsStream("/trip/augments_final.json");
        try (InputStream in = first != null ? first : AugData.class.getResourceAsStream("/trip/augments.json")) {
            if (in == null) {
                error = "trip/augments_final.json 없음: 새 증강은 꺼진 채로 돈다";
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            // 굳힌 파일에는 roll 이 있다. 없으면 초안(data/trip/augments_draft.json)이 그대로 복사된 것: 읽을 수 있는 만큼 읽고 알림
            if (!root.has("roll")) error = "굳힌 trip/augments_final.json 이 없어 초안 trip/augments.json 을 읽었다(python -X utf8 build/trip_aug/make_augments.py "
                + "plugin/src/main/resources/trip/augments_final.json 로 굽기). 새 증강 일부가 꺼지고 그림과 숫자는 코드 기본값으로 돈다";
            if (root.has("roll")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("roll").entrySet()) ROLL.put(e.getKey(), e.getValue().getAsDouble());
            if (root.has("staples")) for (JsonElement e : root.getAsJsonArray("staples")) {
                JsonObject o = e.getAsJsonObject();
                STAPLES.put(str(o, "key"), meta(o));
            }
            if (root.has("augments")) for (JsonElement e : root.getAsJsonArray("augments")) {
                JsonObject o = e.getAsJsonObject();
                META.put(str(o, "key"), meta(o));
            }
        } catch (Exception e) {
            error = "trip/augments.json 읽기 실패: " + e;
        }
    }
}
