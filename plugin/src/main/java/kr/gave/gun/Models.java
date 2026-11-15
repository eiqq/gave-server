package kr.gave.gun;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import kr.gave.gun.Guns.Kind;

/**
 * 무기 모델 표(2026-11-12 wpn_max): 종류(쏘는 방식, {@link Kind}) 아래 모델 하나하나(이름, 모델 키, 성능 배율, 총소리 사건).
 * 표는 tools/props/arms_table.py 가 만들어 jar 안 props.json 의 "arms", "arms_kinds" 로 들어온다(build_props.py write_props_json).
 * 모델 키는 팩 아이템 gave:&lt;키&gt;, 소리 gave:gun.&lt;키&gt;.fire(부품 소리 gave:gun.&lt;키&gt;.&lt;부품&gt; 은 팩에 있을 때만, Guns.partEvent). 성능 배율은 그 종류 바탕 값
 * (Guns.SPECS)에 곱한다(연사, 탄창, 재장전 시간, 퍼짐, 사거리. 표의 피해 배율은 쓰지 않는다: 한 발 피해는 Guns.effective 가 같은 종류 같은 배율의
 * 바탕 총과 한 대상 지속 화력이 같아지게 정함), 관통은 더한다. props.json 이 없거나 낡으면 종류마다 모델 하나(키 = 종류 키)로 돌아간다.
 */
public final class Models {
    private Models() {
    }

    /** 모델 하나. sound 는 그 모델 총소리 사건(없으면 null: 종류 사건) */
    public record Model(String key, Kind kind, String name, String src, double dmg, double rps, double mag, double reload, double spread, double range,
        int pierce, String sound) {

        /**
         * 이 모델의 성능 배율(등급, 강화, 증강 배율과 한 번에 곱해 한 번만 반올림: Guns.effective). 예비탄은 탄창 배율을 따른다.
         * 피해는 1(표의 dmg 는 안 씀): effective 가 반올림 뒤의 값으로 화력을 맞춘다
         */
        public GunMods mods() {
            return new GunMods(1, rps, mag, reload, spread, 1, range, pierce, 0, 0, mag);
        }
    }

    private static final Map<String, Model> BY_KEY = new LinkedHashMap<>();
    private static final Map<Kind, List<Model>> BY_KIND = new EnumMap<>(Kind.class);
    private static final Map<Kind, String> DEFAULT = new EnumMap<>(Kind.class);

    static {
        // 한 줄이 틀려도(모르는 종류, 숫자가 아닌 칸) 그 줄만 건너뛰고 기록에 남긴다: 전에는 한 칸만 틀려도 말없이 모든 모델이 사라졌다
        int bad = 0;
        String first = null;
        try (InputStream in = Models.class.getResourceAsStream("/props.json")) {
            JsonObject o = in == null ? null : new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            if (o != null && o.has("arms")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("arms").entrySet()) {
                try {
                    JsonObject m = e.getValue().getAsJsonObject();
                    Kind k = Kind.valueOf(m.get("kind").getAsString());
                    Model md = new Model(e.getKey(), k, str(m, "name", e.getKey()), str(m, "src", ""), num(m, "dmg"), num(m, "rps"), num(m, "mag"),
                        num(m, "reload"), num(m, "spread"), num(m, "range"), (int) Math.round(num(m, "pierce", 0)), m.has("sound") ? m.get("sound").getAsString() : null);
                    BY_KEY.put(md.key(), md);
                    BY_KIND.computeIfAbsent(k, x -> new ArrayList<>()).add(md);
                } catch (RuntimeException ex) {
                    bad++;
                    if (first == null) first = e.getKey() + ": " + ex;
                }
            }
            if (o != null && o.has("arms_kinds")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("arms_kinds").entrySet()) {
                try {
                    JsonObject kj = e.getValue().getAsJsonObject();
                    if (kj.has("default")) DEFAULT.put(Kind.valueOf(e.getKey()), kj.get("default").getAsString());
                } catch (RuntimeException ex) {
                    // 모르는 종류는 건너뜀
                }
            }
        } catch (Exception e) {
            first = "props.json: " + e;
            bad++;
        }
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("Gave");
        if (bad > 0) log.warning("[총] 무기 모델 표(props.json arms)에서 못 읽은 줄 " + bad + "개(첫 줄 " + first + "). 읽은 모델 " + BY_KEY.size());
        else if (BY_KEY.isEmpty()) log.warning("[총] 무기 모델 표가 비어 있음(props.json arms): 종류마다 기본 모델 하나로 돌아감");
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
    }

    private static double num(JsonObject o, String k) {
        return num(o, k, 1);
    }

    private static double num(JsonObject o, String k, double d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : d;
    }

    /** 키로 찾은 모델(종류 키만 아는 옛 칸은 null) */
    public static Model of(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** 그 종류의 모델 전부(표에 없으면 빈 목록) */
    public static List<Model> list(Kind k) {
        return Collections.unmodifiableList(BY_KIND.getOrDefault(k, List.of()));
    }

    /** 표에 든 모델 전부(시험용) */
    public static List<Model> all() {
        return List.copyOf(BY_KEY.values());
    }

    /** 그 종류의 기본 모델 키(표 default, 없으면 종류 키) */
    public static String defaultKey(Kind k) {
        String d = DEFAULT.get(k);
        return d != null && BY_KEY.containsKey(d) ? d : Guns.SPECS.get(k).key();
    }

    /** 그 종류의 모델 하나를 고르게 고름(표에 없으면 종류 키) */
    public static String pick(Kind k, Random rnd) {
        List<Model> l = BY_KIND.get(k);
        return l == null || l.isEmpty() ? Guns.SPECS.get(k).key() : l.get(rnd.nextInt(l.size())).key();
    }
}
