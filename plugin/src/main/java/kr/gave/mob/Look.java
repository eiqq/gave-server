package kr.gave.mob;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bukkit.Color;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 게이브 모델 틴트 부호. tools/gabe/kit.py 와 비트 하나까지 같다(tools/gabe/tests/look_parity.py 가 같은 입력에 같은 색인지 본다).
 *
 * <p>모델마다 틴트 칸 역할표가 팩의 assets/gave/gabe_looks.json 에 있고(make_gabe.py 가 씀), PackServer 가 팩을 읽을 때
 * {@link #load(byte[], Logger)} 로 불러 둔다. {@link #colors(String, String, LookState, long)} 는 그 표와 상태 이름, {@link LookState}
 * 로 custom_model_data.colors 에 실을 색 목록을 만든다. 셰이더(pack_fx/gabe.glsl)가 같은 비트를 읽어 프레임 접기(지금 자세가 아닌 면을
 * 숨김), 3블록 안 사람 얼굴, 부위 켜고 끄기, 팔레트, 맞은 번쩍임을 한다.
 *
 * <p>색 하나(24비트) = [1110][검사 2][내용 18]. 내용 비트 17 자세 면, 16 숨김, 15..14 거리(0 늘, 1 먼 면만, 2 가까운 면만).
 * 자세 면: 13..11 자세 k, 10..8 자세 수 N-1, 7..4 주기 칸({@link #PERIODS}, 15 멈춤), 3..0 위상(주기 16분의 몇. 멈춤이면 셰이더가
 * 자세 = 위상 x N / 16 을 보이므로 {@link LookState#pose} 를 {@link #posePhase} 로 싣는다).
 * 보통 면: 13..9 팔레트, 8..0 맞은 번쩍임 끝 틱 mod 480(511 없음). 검사 = (1 + 내용 2비트 묶음 9개 합) & 3.
 *
 * <p>상태(strings[0])끼리 자세 칸을 나눠 쓰고 주기는 상태마다 다르다(표 periods). 그래서 상태 이름을 주는
 * {@link #colors(String, String, LookState, long)} 를 써야 상태별 주기가 맞고, 그 상태 모델이 안 쓰는 칸은 기본값으로 두어 잘라 보낸다.
 * 상태 없는 {@link #colors(String, LookState, long)} 는 칸마다 기본 상태 주기로 부호한다(주기가 다른 상태는 펄럭임 빠르기가 틀림).
 * 틴트 칸 차례는 기본 상태가 쓰는 칸 먼저, 그 안에서 보통 면(얼굴, 살) 먼저. 끝의 기본값(기본 look)과 같은 칸은 잘라 보낸다.
 *
 * <p>맞은 번쩍임 도장은 끝 틱 mod 480 이라 같은 값이 480틱(24초)마다 다시 맞는다. 엔진은 아이템을 다시 실을지 비교하기 전에 매 틱
 * {@link #settle} 을 불러 hurtUntil 이 지났으면 지우고 다시 실어야 한다(Body.move, Body.Part.sync, Swarm.Member.sync).
 */
public final class Look {

    public static final int PAL_NONE = 0;
    /** 눈을 흰자로(올라탄 숙주). */
    public static final int PAL_WHITE_EYES = 1;
    /** 탈색(실뭉치, 오래된 것). */
    public static final int PAL_PALE = 2;
    /** 홍조를 짙은 빨강으로(이마 글자, 강화). */
    public static final int PAL_DEEP_BLUSH = 4;
    /** 무표정(웃음 지움). */
    public static final int PAL_BLANK = 8;
    /** 금빛 홍조. */
    public static final int PAL_GOLD = 16;

    /** 셰이더 주기 칸(틱, 모두 24000 의 약수). kit.py PERIODS, gabe.glsl gabe_period 와 같음. */
    public static final int[] PERIODS = {4, 6, 8, 10, 12, 15, 16, 20, 24, 30, 40, 48, 60, 80, 120};
    public static final int STOPPED = 15;
    public static final int HURT_NONE = 511, HURT_MOD = 480, HURT_MAX = 8;
    public static final int DIST_ANY = 0, DIST_FAR = 1, DIST_NEAR = 2;
    public static final String LOOKS_JSON = "assets/gave/gabe_looks.json";
    /** 읽을 줄 아는 gabe_looks.json 꼴(1: 상태별 표 없음, 2: periods, uses). kit.py LOOKS_VERSION. */
    public static final int VERSION = 2;

    /** 틴트 칸 하나의 역할. show 는 모두 맞아야 보이는 조건(open, !open, mut&gt;=N, mut&lt;N, mut==N, on:이름, !on:이름). period 는 기본 상태 주기. */
    public record Slot(boolean posed, int dist, boolean face, String[] show, int k, int n, int period) {
    }

    /**
     * 래그돌 사체의 흐물 막대기 하나(모델 좌표, 블록). root 뿌리, dir 뻗는 쪽, len 길이, thick 굵기, part 공용 마디 텍스처(rod, flesh,
     * wetskin, bone), show 보이는 조건(모두 맞아야), segs 흐물거리는 마디 수, lie[누운 자세][이음점] = 굳은 사체에서 누운 이음점 자리.
     */
    public record RagRod(float[] root, float[] dir, float len, float thick, String part, String[] show, int segs, float[][][] lie) {
    }

    /**
     * 상태 하나의 래그돌 정보(tools/gabe/kit.py 가 굽는다). box = 몸 상자(가운데 x, y, z, 반 크기 x, y, z), corpse = 사체 상태 이름
     * (막대기 뺀 몸 + 누운 막대기 자세, 흐물 막대기가 없는 상태면 null), faces = 누운 자세마다 바닥에 닿는 면(0 아래 1 위 2 뒤 3 앞 4 -X 5 +X.
     * 멈춤 자세 번호 = 이 배열의 차례, faces.length 는 '누운 막대기 없음'), rods = 흐물 막대기, front = 얼굴이 보는 방향(몸 틀의 단위
     * 벡터. 얼굴 없는 상태는 0 벡터, 얼굴 방향을 굽지 않은 옛 조각은 null): 사체가 굳을 때 이 방향이 바닥에 깔리는 면을 피한다.
     */
    public record Rag(float[] box, String corpse, int[] faces, RagRod[] rods, float[] front) {
        public Rag(float[] box, String corpse, int[] faces, RagRod[] rods) {
            this(box, corpse, faces, rods, null);
        }
    }

    /**
     * 모델 하나(아이템 정의 gave:gabe/xxx)의 역할표. defaults = 기본 look 의 색(아이템 정의 tints default 와 같음).
     * periods = 상태마다 펄럭임 주기(틱), uses = 상태마다 그 상태 모델이 쓰는 칸(없으면 모두), quads = 상태마다 면 수,
     * rag = 상태마다 래그돌 사체 정보(옛 키트로 구운 모델은 비어 있음).
     */
    public record Table(String sp, float scale, List<String> states, String defaultState, Set<String> parts, Slot[] slots, int[] defaults,
            Map<String, Integer> periods, Map<String, boolean[]> uses, Map<String, Integer> quads, Map<String, Rag> rag) {

        /** 아이템 정의 select 와 같게 모르는 상태 이름은 기본 상태. */
        public String stateOf(String state) {
            String s = state == null ? "" : state;
            return states.contains(s) ? s : defaultState;
        }
    }

    private static final LookState DEFAULT = new LookState();
    private static volatile Map<String, Table> tables = Map.of();
    private static volatile List<String> warnings = List.of();

    private Look() {
    }

    // ------------------------------------------------------------------ 읽기

    /** 팩 zip 에서 assets/gave/gabe_looks.json 을 읽는다(없거나 null 이면 표를 비움: 모든 모델이 아이템 정의 기본값). */
    public static void load(byte[] zip) {
        load(zip, null);
    }

    /**
     * {@link #load(byte[])} 와 같고, 결과(모델 수, 건너뛴 모델과 까닭)를 log 에 남긴다(null 이면 안 남김). 읽은 모델 수, 파일을 못 읽었으면 -1.
     * 모델 하나가 깨져도 그 모델만 건너뛴다(그 모델은 아이템 정의 기본값으로 그려짐).
     */
    public static int load(byte[] zip, Logger log) {
        int n;
        List<String> warn = new ArrayList<>();
        if (zip == null) {
            tables = Map.of();
            n = 0;
            warn.add("팩 없음: 게이브 겉모습 표 비움");
        } else {
            n = -1;
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
                for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                    if (e.getName().equals(LOOKS_JSON)) {
                        n = parse(new String(z.readAllBytes(), StandardCharsets.UTF_8), warn);
                        break;
                    }
                }
                if (n < 0) {
                    tables = Map.of();
                    n = 0;
                    warn.add(LOOKS_JSON + " 가 팩에 없음: 게이브는 아이템 정의 기본값으로만 그려짐");
                }
            } catch (IOException | RuntimeException ex) {
                tables = Map.of();
                n = -1;
                warn.add(LOOKS_JSON + " 를 읽지 못함: " + ex);
            }
        }
        warnings = List.copyOf(warn);
        if (log != null) {
            int noRag = 0;
            for (Table t : tables.values()) if (t.rag().isEmpty()) noRag++;
            if (n >= 0) log.info("게이브 겉모습 표: 모델 " + n + "개" + (warn.isEmpty() ? "" : ", 경고 " + warn.size())
                    + (noRag == 0 ? "" : ", 래그돌 정보 없는 모델 " + noRag + "개(옛 키트로 구운 조각이면 make_gabe --only 로 다시: 사체가 얼어붙은 몸 통째로 구름)")
                    + (tables.containsKey(Ragdoll.ROD_MODEL) || n <= 0 ? "" : ", 막대기 마디 모델 없음(make_gabe 를 전부 다시 구울 것)"));
            for (String w : warn) log.warning("게이브 겉모습 표: " + w);
        }
        return n;
    }

    /** gabe_looks.json 글을 읽어 표를 통째로 바꾼다. 읽은 모델 수(깨진 모델은 건너뛰고 {@link #warnings()} 에 까닭). 글 전체가 JSON 이 아니면 예외. */
    public static int loadJson(String json) {
        List<String> warn = new ArrayList<>();
        int n = parse(json, warn);
        warnings = List.copyOf(warn);
        return n;
    }

    /** 마지막 load / loadJson 의 경고(건너뛴 모델, 모르는 꼴 번호 등). */
    public static List<String> warnings() {
        return warnings;
    }

    private static int parse(String json, List<String> warn) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int ver = root.has("version") ? root.get("version").getAsInt() : 1;
        if (ver < 1 || ver > VERSION) warn.add("gabe_looks.json 꼴 " + ver + " 을 모름(아는 꼴 1.." + VERSION + "), 읽을 수 있는 만큼 읽음");
        JsonObject models = root.getAsJsonObject("models");
        Map<String, Table> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> me : models.entrySet()) {
            try {
                out.put(me.getKey(), table(me.getValue().getAsJsonObject()));
            } catch (RuntimeException ex) {
                warn.add(me.getKey() + " 건너뜀: " + ex);
            }
        }
        tables = Map.copyOf(out);
        return out.size();
    }

    private static Table table(JsonObject m) {
        JsonArray sa = m.getAsJsonArray("slots");
        Slot[] slots = new Slot[sa.size()];
        int[] defs = new int[sa.size()];
        for (int i = 0; i < slots.length; i++) {
            JsonObject s = sa.get(i).getAsJsonObject();
            JsonArray sh = s.getAsJsonArray("show");
            String[] show = new String[sh.size()];
            for (int j = 0; j < show.length; j++) show[j] = sh.get(j).getAsString();
            int n = s.get("n").getAsInt(), k = s.get("k").getAsInt(), dist = s.get("dist").getAsInt();
            if (n < 1 || n > 8 || k < 0 || k >= n || dist < 0 || dist > 2) throw new IllegalArgumentException("칸 " + i + " 값 범위 밖");
            slots[i] = new Slot(s.get("posed").getAsInt() != 0, dist, s.get("face").getAsInt() != 0, show, k, n, s.get("period").getAsInt());
            defs[i] = encodeSlot(slots[i], DEFAULT, 0);
        }
        List<String> states = new ArrayList<>();
        for (JsonElement s : m.getAsJsonArray("states")) states.add(s.getAsString());
        Set<String> parts = new LinkedHashSet<>();
        if (m.has("parts")) for (JsonElement s : m.getAsJsonArray("parts")) parts.add(s.getAsString());
        Map<String, Integer> periods = new HashMap<>();
        if (m.has("periods")) for (Map.Entry<String, JsonElement> e : m.getAsJsonObject("periods").entrySet()) periods.put(e.getKey(), e.getValue().getAsInt());
        Map<String, boolean[]> uses = new HashMap<>();
        if (m.has("uses")) {
            for (Map.Entry<String, JsonElement> e : m.getAsJsonObject("uses").entrySet()) {
                boolean[] mask = new boolean[slots.length];
                for (JsonElement ix : e.getValue().getAsJsonArray()) mask[ix.getAsInt()] = true;
                uses.put(e.getKey(), mask);
            }
        }
        Map<String, Integer> quads = new HashMap<>();
        if (m.has("quads")) for (Map.Entry<String, JsonElement> e : m.getAsJsonObject("quads").entrySet()) quads.put(e.getKey(), e.getValue().getAsInt());
        String def = m.has("default") ? m.get("default").getAsString() : "";
        Map<String, Rag> rag;
        try {
            rag = rags(m, states);
        } catch (RuntimeException ex) {
            // 래그돌 정보만 깨졌으면 그것만 버림(그 모델은 얼어붙은 몸이 통째로 구름)
            rag = Map.of();
        }
        return new Table(m.has("sp") ? m.get("sp").getAsString() : "", m.has("scale") ? m.get("scale").getAsFloat() : 1f, List.copyOf(states), def,
                Collections.unmodifiableSet(parts), slots, defs, Map.copyOf(periods), Map.copyOf(uses), Map.copyOf(quads), rag);
    }

    private static Map<String, Rag> rags(JsonObject m, List<String> states) {
        if (!m.has("rag")) return Map.of();
        Map<String, Rag> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : m.getAsJsonObject("rag").entrySet()) {
            JsonObject r = e.getValue().getAsJsonObject();
            float[] box = floats(r.getAsJsonArray("box"));
            if (box.length != 6) continue;
            String corpse = r.has("corpse") ? r.get("corpse").getAsString() : null;
            if (corpse != null && !states.contains(corpse)) corpse = null;
            int[] faces = new int[0];
            if (corpse != null && r.has("faces")) {
                JsonArray fa = r.getAsJsonArray("faces");
                faces = new int[fa.size()];
                for (int i = 0; i < faces.length; i++) faces[i] = fa.get(i).getAsInt();
            }
            List<RagRod> rods = new ArrayList<>();
            if (corpse != null && r.has("rods")) {
                for (JsonElement re : r.getAsJsonArray("rods")) {
                    JsonObject o = re.getAsJsonObject();
                    JsonArray sh = o.getAsJsonArray("show");
                    String[] show = new String[sh.size()];
                    for (int i = 0; i < show.length; i++) show[i] = sh.get(i).getAsString();
                    JsonArray la = o.getAsJsonArray("lie");
                    float[][][] lie = new float[la.size()][][];
                    for (int i = 0; i < lie.length; i++) {
                        JsonArray na = la.get(i).getAsJsonArray();
                        lie[i] = new float[na.size()][];
                        for (int j = 0; j < lie[i].length; j++) lie[i][j] = floats(na.get(j).getAsJsonArray());
                    }
                    rods.add(new RagRod(floats(o.getAsJsonArray("root")), floats(o.getAsJsonArray("dir")), o.get("len").getAsFloat(),
                            o.get("thick").getAsFloat(), o.has("part") && !o.get("part").isJsonNull() ? o.get("part").getAsString() : "rod", show,
                            Math.max(1, Math.min(3, o.get("segs").getAsInt())), lie));
                }
            }
            float[] front = r.has("front") && r.get("front").isJsonArray() ? floats(r.getAsJsonArray("front")) : null;
            if (front != null && front.length != 3) front = null;
            out.put(e.getKey(), new Rag(box, corpse, faces, rods.toArray(new RagRod[0]), front));
        }
        return Map.copyOf(out);
    }

    private static float[] floats(JsonArray a) {
        float[] o = new float[a.size()];
        for (int i = 0; i < o.length; i++) o[i] = a.get(i).getAsFloat();
        return o;
    }

    private static String key(String model) {
        int c = model.indexOf(':');
        return c >= 0 ? model.substring(c + 1) : model;
    }

    public static boolean has(String model) {
        return tables.containsKey(key(model));
    }

    /** 역할표(없으면 null). */
    public static Table table(String model) {
        return tables.get(key(model));
    }

    public static Set<String> models() {
        return new TreeSet<>(tables.keySet());
    }

    /** 모델을 줄여 구웠으면 그 배율(디스플레이 transformation 에 곱할 값, 보통 1). */
    public static float scale(String model) {
        Table t = table(model);
        return t == null ? 1f : t.scale();
    }

    /** 모델의 상태 state 의 래그돌 사체 정보(모르는 상태 이름은 기본 상태). 표가 없거나 옛 키트로 구운 모델이면 null. */
    public static Rag rag(String model, String state) {
        Table t = table(model);
        return t == null ? null : t.rag().get(t.stateOf(state));
    }

    // ------------------------------------------------------------------ 색

    /**
     * 상태를 모를 때의 색 목록: 모든 칸을 칸의 주기(기본 상태 주기)로 부호. 끝의 기본값과 같은 칸은 잘라낸다(표가 없으면 빈 목록 = 모두 기본값).
     * 상태마다 주기가 다른 모델(예: G01 charge)은 {@link #colors(String, String, LookState, long)} 를 써야 빠르기가 맞다.
     */
    public static List<Color> colors(String model, LookState st, long worldTime) {
        return toColors(trimmed(model, st, worldTime));
    }

    /**
     * model(이름공간 뺀 아이템 정의 이름, 예: gabe/g01) 의 state(strings[0]) 모델에 실을 색 목록. 자세 칸은 그 상태 주기로, 그 상태 모델이
     * 안 쓰는 칸은 기본값으로 두고 끝의 기본값 칸을 잘라낸다(표가 없으면 빈 목록). Body.item 이 이것을 부른다.
     */
    public static List<Color> colors(String model, String state, LookState st, long worldTime) {
        return toColors(trimmed(model, state, st, worldTime));
    }

    private static List<Color> toColors(int[] c) {
        List<Color> out = new ArrayList<>(c.length);
        for (int v : c) out.add(Color.fromRGB(v));
        return out;
    }

    /** 모든 칸의 색(자르지 않음, 칸 주기). 표가 없으면 빈 배열. */
    public static int[] encode(String model, LookState st, long worldTime) {
        Table t = table(model);
        if (t == null) return new int[0];
        int[] out = new int[t.slots().length];
        for (int i = 0; i < out.length; i++) out[i] = encodeSlot(t.slots()[i], st, worldTime);
        return out;
    }

    /** 상태를 알 때 모든 칸의 색(자르지 않음). kit.encode_state 와 같다. */
    public static int[] encode(String model, String state, LookState st, long worldTime) {
        Table t = table(model);
        if (t == null) return new int[0];
        String s = t.stateOf(state);
        Integer per = t.periods().get(s);
        boolean[] used = t.uses().get(s);
        int[] out = new int[t.slots().length];
        for (int i = 0; i < out.length; i++) {
            Slot sl = t.slots()[i];
            if (used != null && !used[i]) out[i] = t.defaults()[i];
            else out[i] = encodeSlot(sl, st, worldTime, sl.posed() && per != null ? per : sl.period());
        }
        return out;
    }

    /** 보낼 색(상태 모름, 끝에서부터 기본값과 같은 칸을 잘라냄). kit.colors 와 같다. */
    public static int[] trimmed(String model, LookState st, long worldTime) {
        Table t = table(model);
        if (t == null) return new int[0];
        return trim(t, encode(model, st, worldTime));
    }

    /** 보낼 색(상태를 앎). kit.colors_state 와 같다. */
    public static int[] trimmed(String model, String state, LookState st, long worldTime) {
        Table t = table(model);
        if (t == null) return new int[0];
        return trim(t, encode(model, state, st, worldTime));
    }

    private static int[] trim(Table t, int[] full) {
        int n = full.length;
        while (n > 0 && full[n - 1] == t.defaults()[n - 1]) n--;
        return Arrays.copyOf(full, n);
    }

    public static int encodeSlot(Slot s, LookState st, long worldTime) {
        return encodeSlot(s, st, worldTime, s.period());
    }

    /** 칸 하나의 색. period 는 자세 칸의 주기(틱, 상태별 주기). */
    public static int encodeSlot(Slot s, LookState st, long worldTime, int period) {
        boolean hide = false;
        for (String c : s.show()) {
            if (!cond(c, st)) {
                hide = true;
                break;
            }
        }
        int pay = (hide ? 1 << 16 : 0) | (s.dist() << 14);
        if (s.posed()) {
            int pi, ph;
            if (st.freeze) {
                pi = STOPPED;
                ph = posePhase(st.pose, s.n());
            } else {
                pi = periodIndex(period, st.rate);
                int p = PERIODS[pi];
                ph = Math.floorMod(st.phase, p) * 16 / p;
            }
            pay |= (1 << 17) | (s.k() << 11) | ((s.n() - 1) << 8) | (pi << 4) | ph;
        } else {
            int pal = s.face() ? st.palette & 31 : 0;
            int hurt = HURT_NONE;
            if (worldTime < st.hurtUntil) {
                long end = Math.min(st.hurtUntil, worldTime + HURT_MAX);
                hurt = (int) Math.floorMod(end, (long) HURT_MOD);
            }
            pay |= (pal << 9) | hurt;
        }
        return 0xE00000 | (checksum(pay) << 18) | pay;
    }

    /** 멈춘 자세 번호 → 위상 칸(셰이더는 멈춤일 때 자세 = 위상 x n / 16 을 보임): 자세 0 은 0, 그 밖은 ceil(16 x 자세 / n). kit.pose_phase 와 같다. */
    public static int posePhase(int pose, int n) {
        int k = Math.max(0, Math.min(pose, n - 1));
        return k == 0 ? 0 : Math.min(15, (16 * k + n - 1) / n);
    }

    public static boolean cond(String c, LookState st) {
        if (c.equals("open")) return st.open;
        if (c.equals("!open")) return !st.open;
        try {
            if (c.startsWith("mut>=")) return st.mut >= Integer.parseInt(c.substring(5));
            if (c.startsWith("mut<")) return st.mut < Integer.parseInt(c.substring(4));
            if (c.startsWith("mut==")) return st.mut == Integer.parseInt(c.substring(5));
        } catch (NumberFormatException e) {
            return false;
        }
        if (c.startsWith("on:")) return st.on.contains(c.substring(3));
        if (c.startsWith("!on:")) return !st.on.contains(c.substring(4));
        return false;
    }

    /** 기본 주기 / 빠르기 배율에 가장 가까운 주기 칸(비율 max(P/t, t/P) 가 가장 작은 것, 같으면 앞 칸). */
    public static int periodIndex(int period, double rate) {
        double target = rate > 0 ? period / rate : period;
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < PERIODS.length; i++) {
            double p = PERIODS[i];
            double d = Math.max(p / target, target / p);
            if (d < bd) {
                best = i;
                bd = d;
            }
        }
        return best;
    }

    public static int checksum(int pay) {
        int s = 1;
        for (int i = 0; i < 9; i++) s += (pay >> (2 * i)) & 3;
        return s & 3;
    }

    // ------------------------------------------------------------------ 번쩍임 정리

    /** 색이 스스로 바뀌는(번쩍임이 끝나는) 다음 월드 틱, 없으면 -1. */
    public static long refreshAt(LookState st, long worldTime) {
        return st.hurtUntil > worldTime ? st.hurtUntil : -1;
    }

    /** hurtUntil 이 지났으면 0 으로 지우고 true(엔진이 그 뒤 아이템을 다시 실어야 번쩍임 도장이 480틱 뒤에 다시 켜지지 않음). */
    public static boolean settle(LookState st, long worldTime) {
        if (st.hurtUntil != 0 && worldTime >= st.hurtUntil) {
            st.hurtUntil = 0;
            return true;
        }
        return false;
    }
}
