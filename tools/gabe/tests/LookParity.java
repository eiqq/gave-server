import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import kr.gave.mob.Look;
import kr.gave.mob.LookState;

/**
 * kr.gave.mob.Look 과 tools/gabe/kit.py 의 부호기가 같은 입력에 같은 색을 내는지(look_parity.py 가 만든 벡터 파일을 읽음).
 * java LookParity <벡터.json> → "같음 N" 이면 종료 0, 다르면 처음 몇 개를 찍고 종료 1.
 */
public final class LookParity {
    public static void main(String[] args) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8)).getAsJsonObject();
        int models = Look.loadJson(root.get("looks").toString());
        if (!Look.warnings().isEmpty() || models != root.getAsJsonObject("looks").getAsJsonObject("models").size()) {
            System.out.println("표 읽기 경고 " + Look.warnings());
            System.exit(1);
        }
        int n = 0, bad = 0;
        for (JsonElement ce : root.getAsJsonArray("cases")) {
            JsonObject c = ce.getAsJsonObject();
            String model = c.get("model").getAsString();
            JsonObject s = c.getAsJsonObject("look");
            LookState st = new LookState();
            st.palette = s.get("palette").getAsInt();
            st.open = s.get("open").getAsBoolean();
            st.mut = s.get("mut").getAsInt();
            st.freeze = s.get("freeze").getAsBoolean();
            st.phase = s.get("phase").getAsInt();
            st.rate = s.get("rate").getAsDouble();
            for (JsonElement o : s.getAsJsonArray("on")) st.on.add(o.getAsString());
            st.hurtUntil = s.get("hurtUntil").getAsLong();
            if (s.has("pose")) st.pose = s.get("pose").getAsInt();
            long wt = c.get("worldTime").getAsLong();
            int[] full = Look.encode(model, st, wt);
            int[] trim = Look.trimmed(model, st, wt);
            int[] wantFull = ints(c.getAsJsonArray("full"));
            int[] wantTrim = ints(c.getAsJsonArray("trimmed"));
            n++;
            if (!Arrays.equals(full, wantFull) || !Arrays.equals(trim, wantTrim)) {
                if (bad++ < 5) {
                    System.out.println("다름 " + model + " look=" + s + " wt=" + wt);
                    System.out.println("  java   " + Arrays.toString(full) + " / " + trim.length);
                    System.out.println("  python " + Arrays.toString(wantFull) + " / " + wantTrim.length);
                }
            }
            if (Look.colors(model, st, wt).size() != trim.length) {
                bad++;
                System.out.println("colors 길이 다름 " + model);
            }
            // 상태를 아는 부호(Look.colors(model, state, look, wt)): 상태별 주기, 안 쓰는 칸은 기본값
            String state = c.get("state").getAsString();
            int[] fullS = Look.encode(model, state, st, wt);
            int[] trimS = Look.trimmed(model, state, st, wt);
            if (!Arrays.equals(fullS, ints(c.getAsJsonArray("fullState"))) || !Arrays.equals(trimS, ints(c.getAsJsonArray("trimmedState")))
                    || Look.colors(model, state, st, wt).size() != trimS.length) {
                if (bad++ < 5) {
                    System.out.println("상태 부호 다름 " + model + " state=" + state + " look=" + s + " wt=" + wt);
                    System.out.println("  java   " + Arrays.toString(fullS) + " / " + trimS.length);
                    System.out.println("  python " + c.getAsJsonArray("fullState") + " / " + c.getAsJsonArray("trimmedState").size());
                }
            }
        }
        // 깨진 모델 하나는 그 모델만 건너뜀(다른 모델 표는 남음), 모르는 꼴 번호는 경고
        JsonObject broken = root.getAsJsonObject("looks").deepCopy();
        broken.addProperty("version", 99);
        JsonObject bm = broken.getAsJsonObject("models");
        int before = bm.size();
        JsonObject dead = new JsonObject();
        dead.add("slots", new JsonArray());
        bm.add("gabe/broken", dead);
        int got = Look.loadJson(broken.toString());
        if (got != before || Look.warnings().size() != 2 || Look.has("gabe/broken") || !Look.has("gabe/g01")) {
            bad++;
            System.out.println("깨진 모델 처리 다름: 읽음 " + got + " / " + before + ", 경고 " + Look.warnings());
        } else {
            System.out.println("깨진 모델 건너뜀: " + Look.warnings());
        }
        System.out.println(bad == 0 ? "같음 " + n : "다름 " + bad + " / " + n);
        System.exit(bad == 0 ? 0 : 1);
    }

    private static int[] ints(JsonArray a) {
        int[] o = new int[a.size()];
        for (int i = 0; i < o.length; i++) o[i] = a.get(i).getAsInt();
        return o;
    }
}
