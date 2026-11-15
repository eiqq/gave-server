package kr.gave.world;

import static kr.gave.world.Plaza.FLOOR_Y;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * 황야 생성기(2026-11-11 사용자 결정 "구조물 없는 황야 중앙 원"). 끝없이 평평한 땅: 바닥 윗면은 어디나 FLOOR_Y(완전 평지)이고 그 아래는
 * 기반암까지 돌로 꽉 차 있다. 열마다 (x, z) 만의 순수 함수로 짓는다(청크 순서와 무관). 배치 표(틈, 물웅덩이)는 {@link Plaza} 에 있다.
 *
 * <p>구조물은 하나도 없다(건물, 정자, 지붕, 기둥, 벽, 폐허, 터널, 가로등, 놀이터, 파라솔, 환풍구, 쇠창살, 가마솥 모두 없음). 바닥 위에 놓이는 것은
 * 한가운데 보급 상자와 등불, 원 안의 보이지 않는 빛 블록, 드문 마른 덤불(충돌 없음)뿐이다. 높이가 낮아지는 블록(흙길, 진흙, 반 블록)은 쓰지 않는다.
 *
 * <p>땅 무늬: 갈라진 마른 흙. 불규칙한 판(보로노이)마다 재질이 하나(다진 진흙, 거친 흙, 흙, 뿌리 흙, 테라코타 얼룩)이고 판 사이에 어두운 금이
 * 간다. 그 위에 바람에 쓸린 모래 자국과 자갈밭이 알갱이로 흩어진다. 중앙 원 안은 갈라진 마른 돌판, 테두리는 방해석 한 줄.
 *
 * <p>원정의 네 막(docs/모듈/원정.md 2.1, 3.5절): 겉면 한 겹만 막마다 다르다({@link #surface}). 높이, 틈({@link Plaza#fissure}), 물웅덩이
 * ({@link Plaza#puddle}), 판(보로노이) 자리는 모든 막이 같다(걷기 격자와 흐름장, 틈과 웅덩이를 쓰는 종이 그대로 돈다). 막 0 은 원정이 꺼진 판의
 * 옛 땅, 1 마른 길(옛 땅 + 남북으로 뻗은 길), 2 젖은 벌(젖은 진흙, 얕은 물, 길은 자갈 둔덕), 3 재의 땅(검은 재, 금 사이 불씨), 4 발사장(콘크리트 판과
 * 노란 선). 생성기는 바탕 막({@link #baseAct})으로 짓고, 판 중에 막이 바뀌면 kr.gave.trip.Repaint 가 같은 함수로 겉면을 다시 칠한다.
 */
public final class PlazaGen extends ChunkGenerator {

    private static final Map<String, BlockData> BD = new ConcurrentHashMap<>();

    public static BlockData bd(String id) {
        return BD.computeIfAbsent(id, k -> Bukkit.createBlockData(k.indexOf(':') >= 0 ? k : "minecraft:" + k));
    }

    /** 한 열 쓰기 도우미(청크 안 좌표). */
    private static final class Col {
        ChunkData d;
        int lx, lz;

        void set(int y, String id) {
            d.setBlock(lx, y, lz, bd(id));
        }

        void fill(int y0, int y1, String id) {
            BlockData b = bd(id);
            for (int y = y0; y <= y1; y++) d.setBlock(lx, y, lz, b);
        }
    }

    /** 중앙 원 테두리 선(멀리서도 읽히는 밝은 블록 한 가지). 발사장은 노란 선. */
    static final String RING = "calcite", RING_PAD = "yellow_concrete";
    /** 큰 금(틈) 속, 판 사이 금, 젖은 흙. */
    static final String FISSURE = "black_concrete", CRACK = "soul_soil", CRACK2 = "brown_terracotta";
    /** 바람 자국(예전 모래): 어두운 흙빛 두 가지. */
    static final String STREAK = "brown_terracotta", STREAK2 = "coarse_dirt";
    /** 물웅덩이: 물이 든 보이지 않는 꽉 찬 블록(물 위를 그대로 걸어 바닥 높이가 변하지 않는다). 그 밑은 진흙. */
    public static final String WATER = "barrier[waterlogged=true]";
    /** 물 칸 밑(바닥 두 칸 아래). */
    public static final String UNDER_WATER = "mud";
    /** 길 가운데 점선, 젖은 벌의 진흙(꽉 찬 블록: 진흙 블록은 낮아서 못 씀), 재의 땅 금 사이 불씨(바닐라 피해는 Players 가 모두 막는다). */
    static final String LINE = "white_concrete_powder", MUD = "muddy_mangrove_roots", EMBER = "magma_block";
    /** 원 안 빛 블록 밝기(바닥 한 칸 위, 세 칸 간격). */
    static final int LIGHT = 11;
    /** 갈라진 판 크기(블록): 황야, 중앙 원. */
    static final int PLATE = 8, PLATE_IN = 5;
    /** 막의 수(1..4), 원정이 꺼진 판의 옛 땅은 0. */
    public static final int ACT_OLD = 0, ACTS = 4;
    /** 발사장 콘크리트 판의 끝(가운데에서, 가장자리는 들쭉날쭉). 그 밖은 그을린 흙. */
    static final int PAD_R = 86;

    private static volatile int baseAct = -1;

    /**
     * 바탕 막: 원정이 켜져 있으면(설정 trip.enabled, 기본 true) 1 마른 길, 꺼져 있으면 0 옛 땅. 생성기가 이 막으로 짓고, 판이 끝나거나 서버를 다시 켜면
     * 땅이 이 막으로 돌아간다.
     */
    public static int baseAct() {
        int b = baseAct;
        if (b >= 0) return b;
        synchronized (PlazaGen.class) {
            if (baseAct >= 0) return baseAct;
            int v;
            try {
                v = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(PlazaGen.class).getConfig().getBoolean("trip.enabled", true) ? 1 : 0;
            } catch (RuntimeException | LinkageError e) {
                v = 1;
            }
            baseAct = v;
            return v;
        }
    }

    /** 바탕 막을 바꿈(시험 훅 '원정 x 켬|끔'). 땅은 kr.gave.trip.Repaint 가 따로 칠한다. */
    public static void baseAct(int act) {
        baseAct = Math.max(0, Math.min(1, act));
    }

    @Override
    public void generateNoise(WorldInfo info, Random random, int cx, int cz, ChunkData d) {
        int bx = cx * 16, bz = cz * 16, minY = d.getMinHeight();
        int act = baseAct();
        // 기반암 위부터 바닥 밑까지 돌로 빈틈없이(빈 곳에 사람이 서는 일이 없게)
        d.setRegion(0, minY, 0, 16, minY + 1, 16, Material.BEDROCK);
        d.setRegion(0, minY + 1, 0, 16, FLOOR_Y - 1, 16, Material.STONE);
        Col c = new Col();
        c.d = d;
        int[] buf = new int[4];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = bx + lx, z = bz + lz;
                c.lx = lx;
                c.lz = lz;
                if (Plaza.cageRoom && Plaza.inLobby(x, z)) {
                    lobby(c, x, z);
                    continue;
                }
                String top = surface(x, z, act, 0, buf);
                c.set(FLOOR_Y - 1, top);
                if (WATER.equals(top)) c.set(FLOOR_Y - 2, UNDER_WATER);
                String on = deco(x, z, act, 0, top, buf);
                if (on != null) c.set(FLOOR_Y, on);
                String up = above(x, z);
                if (up != null) c.set(FLOOR_Y + 1, up);
            }
        }
    }

    private static double k2(int x, int z) {
        return Plaza.rnd(x, z, 58);
    }

    private static String pick(int x, int z, int salt, String... w) {
        // w = id, 무게, id, 무게 ...
        int total = 0;
        for (int i = 1; i < w.length; i += 2) total += Integer.parseInt(w[i]);
        int r = Math.floorMod(Plaza.hash(x, z, salt), total);
        for (int i = 0; i < w.length; i += 2) {
            r -= Integer.parseInt(w[i + 1]);
            if (r < 0) return w[i];
        }
        return w[0];
    }

    // ------------------------------------------------------------------ 바닥 윗면(y = FLOOR_Y - 1)

    /**
     * 갈라진 판: (x, z) 칸이 든 판과 그다음 가까운 판을 buf 에 {판 i, 판 j, 이웃 판 i, 이웃 판 j} 로 적고, 판 사이 금까지의 여유(이웃 판 씨앗까지 거리 -
     * 내 판 씨앗까지 거리)를 돌려준다. 0 에 가까우면 금 위.
     */
    static double plate(int x, int z, int size, int salt, int[] buf) {
        int gi = Math.floorDiv(x, size), gj = Math.floorDiv(z, size);
        double best = 1e9, second = 1e9;
        int bi = 0, bj = 0, si = 0, sj = 0;
        for (int i = gi - 1; i <= gi + 1; i++) {
            for (int j = gj - 1; j <= gj + 1; j++) {
                double px = (i + 0.15 + 0.7 * Plaza.rnd(i, j, salt)) * size, pz = (j + 0.15 + 0.7 * Plaza.rnd(i, j, salt + 1)) * size;
                double dx = x + 0.5 - px, dz = z + 0.5 - pz, dd = Math.sqrt(dx * dx + dz * dz);
                if (dd < best) {
                    second = best;
                    si = bi;
                    sj = bj;
                    best = dd;
                    bi = i;
                    bj = j;
                } else if (dd < second) {
                    second = dd;
                    si = i;
                    sj = j;
                }
            }
        }
        buf[0] = bi;
        buf[1] = bj;
        buf[2] = si;
        buf[3] = sj;
        return second - best;
    }

    /** 바탕 막의 바닥 윗면 블록(생성기가 짓는 땅). */
    static String top(int x, int z, int[] buf) {
        return surface(x, z, baseAct(), 0, buf);
    }

    /** 길 위인가(남북으로 뻗은 폭 5 의 띠. 막 1 부터 있다). */
    public static boolean road(int x) {
        return Math.abs(x - Plaza.CX) <= Plaza.ROAD_HALF;
    }

    /**
     * 막 act(0 옛 땅, 1..4)의 바닥 윗면 블록. (x, z, act, wet) 만의 순수 함수. wet 은 젖은 벌의 물 높이 0..1(0 기본, 올리면 낮은 곳부터 물에 잠긴다:
     * 밀물 같은 라운드용. 다른 막은 안 봄). buf 는 길이 4 의 작업 칸.
     */
    public static String surface(int x, int z, int act, double wet, int[] buf) {
        double r = Math.hypot(x - Plaza.CX, z - Plaza.CZ);
        if (r < Plaza.R_SAFE + 0.5) return circle(x, z, r, act, buf);
        int f = Plaza.fissure(x, z);
        if (f == 1) return act == 3 ? EMBER : FISSURE;
        int pd = Plaza.puddle(x, z);
        if (pd == 1) return WATER;
        boolean rim = f == 2 || pd == 2;
        switch (act) {
            case 1:
                return road(x) ? asphalt(x, z) : wasteland(x, z, rim, buf);
            case 2:
                return road(x) ? causeway(x, z) : marsh(x, z, rim, wet, buf);
            case 3:
                return road(x) ? burntRoad(x, z) : ash(x, z, rim, true, buf);
            case 4:
                return pad(x, z, r, rim, buf);
            default:
                return wasteland(x, z, rim, buf);
        }
    }

    /**
     * 다시 칠할 때 이 열이 보여 줄 막: 칠하는 끝({@link Plaza#R_PAINT}) 안쪽 열 칸은 바탕 막 base 의 땅과 점점 섞는다(칠한 끝이 줄로 보이지 않게).
     * 그보다 안쪽은 act 그대로.
     */
    public static int blendAct(int x, int z, int act, int base) {
        if (act == base) return act;
        double r = Math.hypot(x - Plaza.CX, z - Plaza.CZ), in = Plaza.R_PAINT - 10;
        if (r <= in) return act;
        return Plaza.rnd(x, z, 98) < (r - in) / 10.0 ? base : act;
    }

    /** 중앙 원: 테두리 선 한 줄, 안쪽은 갈라진 돌판(막마다 재질이 다르고 길이 꿰뚫는다). */
    private static String circle(int x, int z, double r, int act, int[] buf) {
        if (r >= Plaza.R_SAFE - 0.5) return act == 4 ? RING_PAD : RING;
        if (x == Plaza.CX && z == Plaza.CZ) return "stone";
        if (act == 4) return padCircle(x, z);
        if (act >= 1 && road(x)) return act == 1 ? asphalt(x, z) : act == 2 ? causeway(x, z) : burntRoad(x, z);
        double edge = plate(x, z, PLATE_IN, 40, buf);
        double s = Plaza.rnd(x, z, 42);
        if (act == 2) {
            if (edge < 0.8 && s > 0.1) return "mossy_cobblestone";
            double t = Plaza.rnd(buf[0], buf[1], 41);
            if (s < 0.07) return "moss_block";
            return t < 0.5 ? "stone" : t < 0.85 ? "andesite" : "tuff";
        }
        if (act == 3) {
            if (edge < 0.8 && s > 0.1) return "blackstone";
            double t = Plaza.rnd(buf[0], buf[1], 41);
            if (s < 0.07) return "black_concrete_powder";
            return t < 0.5 ? "cobbled_deepslate" : t < 0.85 ? "smooth_basalt" : "tuff";
        }
        if (edge < 0.8 && s > 0.1) return "cobblestone";
        double t = Plaza.rnd(buf[0], buf[1], 41);
        if (s < 0.06) return "cobblestone";
        return t < 0.5 ? "stone" : t < 0.85 ? "andesite" : "tuff";
    }

    /** 황야: 갈라진 마른 흙. wet 이면 틈 둘레나 물가(어둡게 젖은 흙이 섞임). */
    private static String wasteland(int x, int z, boolean wet, int[] buf) {
        double edge = plate(x, z, PLATE, 50, buf);
        int ci = buf[0], cj = buf[1];
        int pair = Plaza.hash(ci, cj, 51) ^ Plaza.hash(buf[2], buf[3], 51);
        double s = Plaza.rnd(x, z, 52);
        if (wet && s < 0.7) return CRACK;
        // 판 사이 금: 판 쌍의 78% 에만, 줄마다 재질 하나, 군데군데 끊김
        if (edge < 0.9 && Math.floorMod(pair >>> 4, 100) < 78 && s > 0.12) return Math.floorMod(pair >>> 12, 10) < 7 ? CRACK : CRACK2;
        // 알갱이로 흩어지는 것: 바람에 쓸린 흙 자국(동서로 김, 어두운 구운 흙빛. 밝은 모래는 위에서 보면 눈처럼 보여 쓰지 않음), 자갈밭
        double sand = Plaza.noise(x / 30.0, z / 9.0, 54), rough = Plaza.noise(x / 19.0, z / 19.0, 55);
        if (sand > 0.66 && s < (sand - 0.66) * 3.0) return k2(x, z) < 0.6 ? STREAK : STREAK2;
        if (rough < 0.3 && s > 1 - (0.3 - rough) * 4.0) return "gravel";
        // 판 재질: 테라코타 얼룩 지역이면 구운 흙빛 판, 아니면 마른 흙 판
        double stain = Plaza.noise(ci * PLATE / 11.0, cj * PLATE / 11.0, 53), t = Plaza.rnd(ci, cj, 56), k = Plaza.rnd(x, z, 57);
        if (stain > 0.76) return k < 0.08 ? "coarse_dirt" : t < 0.75 ? "terracotta" : "brown_terracotta";
        if (t < 0.5) return k < 0.07 ? "coarse_dirt" : k < 0.1 ? "rooted_dirt" : "packed_mud";
        if (t < 0.74) return k < 0.1 ? "dirt" : k < 0.15 ? "gravel" : "coarse_dirt";
        if (t < 0.9) return k < 0.12 ? "coarse_dirt" : k < 0.17 ? "rooted_dirt" : "dirt";
        return k < 0.2 ? "dirt" : "rooted_dirt";
    }

    // ------------------------------------------------------------------ 1막 마른 길: 옛 땅 + 길

    /** 닳은 아스팔트: 짙은 회색 겉면, 들쭉날쭉한 가장자리, 팬 곳, 가운데 점선(세 칸 긋고 네 칸 띔, 군데군데 지워짐). */
    private static String asphalt(int x, int z) {
        int dx = x - Plaza.CX;
        double k = Plaza.rnd(x, z, 90), worn = Plaza.noise(x / 7.0, z / 13.0, 91);
        if (Math.abs(dx) == Plaza.ROAD_HALF && Plaza.rnd(x, Math.floorDiv(z, 2), 92) < 0.28) return k < 0.5 ? "coarse_dirt" : "gravel";
        if (dx == 0 && Math.floorMod(z, 7) < 3 && Plaza.rnd(7, Math.floorDiv(z, 7), 93) > 0.18 && k > 0.12) return LINE;
        if (worn > 0.74 && k < 0.6) return "gravel";
        if (worn < 0.24 && k < 0.5) return "gray_concrete_powder";
        return k < 0.14 ? "gray_concrete_powder" : k < 0.22 ? "cyan_terracotta" : "gray_concrete";
    }

    // ------------------------------------------------------------------ 2막 젖은 벌

    /** 길은 물 위로 올라온 자갈 둔덕(높이는 같다: 마른 겉면). */
    private static String causeway(int x, int z) {
        double k = Plaza.rnd(x, z, 94);
        if (Math.abs(x - Plaza.CX) == Plaza.ROAD_HALF) return k < 0.4 ? "rooted_dirt" : k < 0.75 ? "coarse_dirt" : "gravel";
        return k < 0.55 ? "gravel" : k < 0.85 ? "coarse_dirt" : "packed_mud";
    }

    /** 낮은 곳 0..1(젖은 벌에서 물이 고이는 자리). */
    static double low(int x, int z) {
        return Plaza.noise(x / 23.0, z / 23.0, 81) * 0.75 + Plaza.noise(x / 9.0, z / 9.0, 82) * 0.25;
    }

    /** 젖은 벌: 젖은 진흙 판, 낮은 곳의 판 사이 금은 물길, 가장 낮은 곳은 얕은 물. 길 기슭 한 칸은 마른 채. */
    private static String marsh(int x, int z, boolean rim, double wet, int[] buf) {
        double edge = plate(x, z, PLATE, 50, buf);
        int ci = buf[0], cj = buf[1];
        int pair = Plaza.hash(ci, cj, 51) ^ Plaza.hash(buf[2], buf[3], 51);
        double s = Plaza.rnd(x, z, 52), lv = low(x, z) + wet * 0.6;
        boolean foot = Math.abs(x - Plaza.CX) <= Plaza.ROAD_HALF + 1;
        if (!foot) {
            if (lv > 0.72) return WATER;
            if (lv > 0.53 && edge < 0.9 && s > 0.08) return WATER;
        }
        if (rim && s < 0.7) return MUD;
        if (foot) return s < 0.5 ? "coarse_dirt" : s < 0.8 ? "rooted_dirt" : MUD;
        // 물가: 이끼와 찰흙
        if (lv > 0.65) return s < 0.3 ? "moss_block" : s < 0.45 ? "clay" : MUD;
        // 물이 안 든 금: 짙은 흙
        if (edge < 0.9 && Math.floorMod(pair >>> 4, 100) < 78 && s > 0.12) return CRACK;
        double t = Plaza.rnd(ci, cj, 56), k = Plaza.rnd(x, z, 57);
        if (t < 0.5) return k < 0.08 ? "brown_concrete_powder" : k < 0.12 ? "clay" : MUD;
        if (t < 0.74) return k < 0.12 ? MUD : "soul_soil";
        if (t < 0.9) return k < 0.15 ? MUD : "podzol";
        return k < 0.3 ? "podzol" : "moss_block";
    }

    // ------------------------------------------------------------------ 3막 재의 땅

    /** 그을린 길: 검게 탄 아스팔트, 반쯤 지워진 점선. */
    private static String burntRoad(int x, int z) {
        int dx = x - Plaza.CX;
        double k = Plaza.rnd(x, z, 90), worn = Plaza.noise(x / 7.0, z / 13.0, 91);
        if (Math.abs(dx) == Plaza.ROAD_HALF && Plaza.rnd(x, Math.floorDiv(z, 2), 92) < 0.28) return k < 0.5 ? "black_concrete_powder" : "blackstone";
        if (dx == 0 && Math.floorMod(z, 7) < 3 && Plaza.rnd(7, Math.floorDiv(z, 7), 93) > 0.5 && k > 0.3) return "light_gray_concrete_powder";
        if (worn > 0.74 && k < 0.6) return "blackstone";
        return k < 0.3 ? "black_concrete_powder" : k < 0.4 ? "coal_block" : "gray_concrete";
    }

    /**
     * 재의 땅: 검은 재 판, 그을린 돌과 흙, 재 더미. glow 면 판 사이 금의 3분의 1 이 붉게 탄다(불씨: 밟아도 피해 없음). 판 자리는 1막과 같아 같은 금이
     * 그대로 탄다.
     */
    private static String ash(int x, int z, boolean rim, boolean glow, int[] buf) {
        double edge = plate(x, z, PLATE, 50, buf);
        int ci = buf[0], cj = buf[1];
        int pair = Plaza.hash(ci, cj, 51) ^ Plaza.hash(buf[2], buf[3], 51);
        double s = Plaza.rnd(x, z, 52);
        if (rim && s < 0.7) return "blackstone";
        if (edge < 0.9 && Math.floorMod(pair >>> 4, 100) < 78 && s > 0.12) {
            if (glow && Math.floorMod(pair >>> 20, 100) < 34) return s < 0.7 ? EMBER : "blackstone";
            return Math.floorMod(pair >>> 12, 10) < 7 ? "blackstone" : "coal_block";
        }
        double drift = Plaza.noise(x / 30.0, z / 9.0, 54), rough = Plaza.noise(x / 19.0, z / 19.0, 55);
        if (drift > 0.66 && s < (drift - 0.66) * 3.0) return k2(x, z) < 0.6 ? "gray_concrete_powder" : "light_gray_concrete_powder";
        if (rough < 0.3 && s > 1 - (0.3 - rough) * 4.0) return "cobbled_deepslate";
        double t = Plaza.rnd(ci, cj, 56), k = Plaza.rnd(x, z, 57);
        if (t < 0.45) return k < 0.1 ? "gray_concrete_powder" : "black_concrete_powder";
        if (t < 0.72) return k < 0.12 ? "black_concrete_powder" : k < 0.2 ? "tuff" : "smooth_basalt";
        if (t < 0.9) return k < 0.15 ? "coarse_dirt" : "soul_soil";
        return k < 0.2 ? "black_concrete_powder" : "cobbled_deepslate";
    }

    // ------------------------------------------------------------------ 4막 발사장

    /** 노란 점선(길이 방향 좌표 a 를 따라 두 칸 긋고 두 칸 띔). */
    private static boolean dash(int a) {
        return Math.floorMod(a, 4) < 2;
    }

    /** 발사장: 콘크리트 판(여덟 칸 격자 이음매), 얼룩과 금, 노란 유도선(남북 축, 동서 축, 고리 둘). 판 끝 밖은 그을린 흙. */
    private static String pad(int x, int z, double r, boolean rim, int[] buf) {
        double ragged = PAD_R + (Plaza.noise(x / 6.0, z / 6.0, 95) - 0.5) * 7;
        if (r > ragged) return ash(x, z, rim, false, buf);
        int dx = x - Plaza.CX, dz = z - Plaza.CZ;
        double k = Plaza.rnd(x, z, 96);
        if (rim) return k < 0.6 ? "gray_concrete_powder" : "gray_concrete";
        if (r > ragged - 1.5 && k < 0.5) return "gravel";
        if (k > 0.07) {
            if (dx == 0 && dash(dz)) return RING_PAD;
            if (dz == 0 && dash(dx)) return RING_PAD;
            if ((Math.abs(r - 30) < 0.5 || Math.abs(r - 60) < 0.5) && dash((int) Math.floor(Math.atan2(dz, dx) * r))) return RING_PAD;
        }
        if (Math.floorMod(x + 4, 8) == 0 || Math.floorMod(z + 4, 8) == 0) return k < 0.86 ? "gray_concrete" : "gray_concrete_powder";
        // 금 간 판(판 쌍의 5분의 1), 기름 얼룩
        double edge = plate(x, z, PLATE, 50, buf);
        int pair = Plaza.hash(buf[0], buf[1], 51) ^ Plaza.hash(buf[2], buf[3], 51);
        if (edge < 0.9 && Math.floorMod(pair >>> 4, 100) < 20 && k > 0.2) return "gray_concrete";
        double stain = Plaza.noise(x / 9.0, z / 9.0, 97);
        if (stain > 0.72 && k < (stain - 0.72) * 5) return "gray_concrete_powder";
        return k < 0.05 ? "light_gray_concrete_powder" : "light_gray_concrete";
    }

    /** 발사장의 중앙 원 안: 콘크리트, 네 칸 격자 이음매, 노랑과 검정 빗금 테두리(가운데에서 일곱 칸), 축 유도선. */
    private static String padCircle(int x, int z) {
        int dx = x - Plaza.CX, dz = z - Plaza.CZ, d = Math.max(Math.abs(dx), Math.abs(dz));
        if (d == 7) return Math.floorMod(dx + dz, 2) == 0 ? RING_PAD : "black_concrete";
        if (d > 7 && ((dx == 0 && dash(dz)) || (dz == 0 && dash(dx)))) return RING_PAD;
        if (Math.floorMod(x + 2, 4) == 0 || Math.floorMod(z + 2, 4) == 0) return "gray_concrete";
        return Plaza.rnd(x, z, 96) < 0.06 ? "light_gray_concrete_powder" : "light_gray_concrete";
    }

    // ------------------------------------------------------------------ 바닥 위(y = FLOOR_Y, FLOOR_Y + 1)

    /** 바탕 막의 발 높이 칸(생성기). */
    static String deco(int x, int z, String top) {
        return deco(x, z, baseAct(), 0, top, new int[4]);
    }

    /**
     * 막 act 의 발 높이 칸: 한가운데 보급 상자, 원 밖의 드문 풀(충돌 없음, 시야를 가리지 않는 한 칸 높이). 옛 땅과 1막은 마른 덤불과 마른 풀, 2막은 물가의
     * 갈대(키 큰 마른 풀)와 고사리, 3막은 그을린 덤불, 4막은 판 밖에만 드문 덤불. 없으면 null. top 은 그 칸의 바닥 윗면({@link #surface}).
     */
    public static String deco(int x, int z, int act, double wet, String top, int[] buf) {
        if (x == Plaza.CX && z == Plaza.CZ) return "barrel[facing=up]";
        if (Math.hypot(x - Plaza.CX, z - Plaza.CZ) < Plaza.R_SAFE + 3) return null;
        if (Plaza.fissure(x, z) != 0 || Plaza.puddle(x, z) != 0) return null;
        double k = Plaza.rnd(x, z, 60);
        switch (act) {
            case 2: {
                boolean soil = switch (top) {
                    case MUD, "soul_soil", "podzol", "moss_block", "clay", "brown_concrete_powder", "rooted_dirt", "coarse_dirt" -> true;
                    default -> false;
                };
                if (!soil) return null;
                boolean shore = WATER.equals(surface(x + 1, z, 2, wet, buf)) || WATER.equals(surface(x - 1, z, 2, wet, buf))
                        || WATER.equals(surface(x, z + 1, 2, wet, buf)) || WATER.equals(surface(x, z - 1, 2, wet, buf));
                if (shore) return k < 0.13 ? "tall_dry_grass" : k < 0.19 ? "short_dry_grass" : k < 0.23 ? "fern" : null;
                return k < 0.006 ? "short_grass" : k < 0.014 ? "short_dry_grass" : null;
            }
            case 3:
            case 4: {
                boolean soil = switch (top) {
                    case "black_concrete_powder", "gray_concrete_powder", "soul_soil", "coarse_dirt" -> true;
                    default -> false;
                };
                if (!soil || (act == 4 && Math.hypot(x - Plaza.CX, z - Plaza.CZ) < PAD_R + 4)) return null;
                return k < 0.011 ? "dead_bush" : null;
            }
            default: {
                boolean soil = switch (top) {
                    case "coarse_dirt", "dirt", "rooted_dirt", "terracotta", "brown_terracotta" -> true;
                    default -> false;
                };
                if (!soil || (act >= 1 && road(x))) return null;
                return k < 0.004 ? "dead_bush" : k < 0.012 ? "short_dry_grass" : null;
            }
        }
    }

    /** 다시 칠할 때 지워도 되는 발 높이 풀(내가 놓는 것만. 그 밖의 블록은 남의 것이라 안 건드린다). */
    public static boolean isDeco(Material m) {
        return m == Material.DEAD_BUSH || m == Material.SHORT_DRY_GRASS || m == Material.TALL_DRY_GRASS || m == Material.SHORT_GRASS || m == Material.FERN;
    }

    /** 발 위 칸: 보급 상자 위 등불, 원 안의 보이지 않는 빛 블록(밤에도 원 안이 보이게). 없으면 null. */
    public static String above(int x, int z) {
        if (x == Plaza.CX && z == Plaza.CZ) return "lantern[hanging=false]";
        if (Math.floorMod(x, 3) == 0 && Math.floorMod(z, 3) == 0 && Math.hypot(x - Plaza.CX, z - Plaza.CZ) <= Plaza.R_SAFE - 1) return "light[level=" + LIGHT + "]";
        return null;
    }

    // ------------------------------------------------------------------ 옛 대기실 방(설정 cage 를 켰을 때만)

    private void lobby(Col c, int x, int z) {
        boolean wx = Math.abs(x) == Plaza.LOBBY_HX, wz = z == Plaza.LOBBY_Z0 || z == Plaza.LOBBY_Z1;
        if (wx || wz) {
            boolean pillar = (wx && Math.floorMod(z - Plaza.LOBBY_Z0, 5) == 0) || (wz && Math.floorMod(x, 5) == 0);
            c.fill(FLOOR_Y - 1, Plaza.LOBBY_TOP, pillar ? "quartz_pillar[axis=y]" : "white_concrete");
            return;
        }
        c.set(Plaza.LOBBY_TOP, Math.floorMod(x, 4) == 0 && Math.floorMod(z, 4) == 0 ? "sea_lantern" : "smooth_quartz");
        if (z == Plaza.GLASS_Z) {
            c.set(FLOOR_Y - 1, "white_concrete");
            c.fill(FLOOR_Y, Plaza.LOBBY_TOP - 1, Math.floorMod(x + 3, 6) == 0 ? "quartz_pillar[axis=y]" : "glass");
            return;
        }
        if (z < Plaza.GLASS_Z) {
            c.set(FLOOR_Y - 1, pick(x, z, 71, "moss_block", "12", "rooted_dirt", "3", "packed_mud", "2"));
            if (Plaza.rnd(x, z, 72) < 0.12) c.set(FLOOR_Y, "moss_carpet");
            return;
        }
        boolean edge = Math.abs(x) == Plaza.LOBBY_HX - 1 || z == Plaza.GLASS_Z + 1 || z == Plaza.LOBBY_Z1 - 1;
        c.set(FLOOR_Y - 1, edge ? "white_concrete" : "polished_diorite");
        if (z == Plaza.LOBBY_Z0 + 18 && Math.abs(x) >= 3 && Math.abs(x) <= 6) c.set(FLOOR_Y, "smooth_quartz_stairs[facing=south]");
    }

    // ------------------------------------------------------------------ 바닐라 생성 끔

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of();
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return Plaza.lobby(world);
    }

    /**
     * 창백한 정원 바이옴 하나: 안개 빛이 흙먼지빛(#817770), 하늘이 잿빛(#b9b9b9), 물빛이 흐린 청회색이고 바닐라 배경 음악이 없다. 흙먼지 안개의
     * 거리는 데이터팩 gave_env 의 시계 타임라인이 정한다(Fog).
     */
    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo w, int x, int y, int z) {
                return Biome.PALE_GARDEN;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo w) {
                return List.of(Biome.PALE_GARDEN);
            }
        };
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
