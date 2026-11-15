package kr.gave.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * 전장 배치(2026-11-11 사용자 결정 "구조물 없는 황야 중앙 원", docs/모듈/맵변경_황야.md). 생성기(PlazaGen)와 이 클래스의 점 목록이 같은
 * 배치 표를 쓴다(열마다 (x, z) 만의 순수 함수). 걷기 격자(바닥 높이, 막힘)는 서버가 켤 때 {@link #scan(World)} 가 월드를 한 번 읽어 채운다
 * (크리에이티브로 고친 것도 반영).
 *
 * <p>끝까지 평평한 황야(바닥 윗면 FLOOR_Y, 완전 평지, 구조물 없음). 한가운데(CX, CZ) 칸에 보급 상자 하나(상점, 높이 1블록, 그 칸만 막힘)와
 * 그 위 등불. 그 둘레 R_SAFE 까지 중앙 원(마른 돌바닥, 테두리 선 한 줄, 보이지 않는 빛 블록). 사람은 R_ROAM 에서 되밀린다({@link Roam}).
 * 옅은 흙먼지 안개 너머 R_SPAWN_IN..R_SPAWN_OUT 고리에서 게이브가 사방 360도로 걸어 나오고, 몹은 R_EDGE 밖으로 못 나간다. 원 밖에는
 * 땅 갈라진 틈({@link #grates()}, 예전 하수구 자리)과 얕은 물웅덩이({@link #puddles()})가 있다.
 *
 * <p>정자, 처마, 문틀, 지붕, 가마솥, 터널은 없다: {@link #eaves()}, {@link #doorFrames()}, {@link #doorDecor()}, {@link #cauldrons()},
 * {@link #roofs()} 는 빈 목록이고 PAV, EAVE_Y, R_PLAZA, R_OUTER, LOBBY_* 같은 이름은 호환용 값만 남았다.
 *
 * <p>방향 번호: 0 북(-Z), 1 동(+X), 2 남(+Z), 3 서(-X).
 */
public final class Plaza {

    public static final String WORLD = "gave_plaza";
    public static final int CX = 0, CZ = 0;
    /** 바닥에 선 발 높이(바닥 블록 윗면). 황야 전체가 이 높이다. */
    public static final int FLOOR_Y = 64;
    /**
     * R_SHOP 보급 상자 둘레 상점 반경(웨이브 중 상점을 여는 곳), R_SAFE 중앙 원, R_ROAM 사람이 갈 수 있는 끝, R_SPAWN_IN..R_SPAWN_OUT 게이브가
     * 나오는 고리, R_EDGE 몹이 못 나가는 끝.
     */
    public static final int R_SHOP = 5, R_SAFE = 12, R_ROAM = 48, R_SPAWN_IN = 72, R_SPAWN_OUT = 84, R_EDGE = 96;
    /** 호환용(광장, 바깥 고리가 없다. 값은 예전 그대로). R_OUTER 는 죽어서 날며 지켜보는 사람의 경계로 쓰인다(Players). */
    public static final int R_PLAZA = 36, R_OUTER = 62;
    /** 호환용(생성기는 이제 끝없이 짓는다). */
    public static final int R_WORLD = R_EDGE;
    /** 길(원정): 가운데를 남북으로 꿰뚫는 띠의 반폭(폭 5). 트럭이 이 방향으로 서고 이동 연출이 북에서 남으로 흐른다. 원정이 꺼진 판(막 0)에는 길이 없다. */
    public static final int ROAD_HALF = 4; // 2026-11-12 사용자: 추격전 도로 양옆 폭 1.8배(폭 5 → 9)
    /** 다시 칠하는 끝(원정의 막 넘김: 가운데에서 이 반지름 안 겉면만 바뀐다). 걷기 격자와 같다. */
    public static final int R_PAINT = 100;
    /** 걷기 격자 범위(포함). R_EDGE 를 덮는다. */
    public static final int MIN = -100, MAX = 100;
    /** 옛 대기실 방 가운데(설정 cage 를 켰을 때만 지어지고 쓰인다). */
    public static final int LOBBY_X = 0, LOBBY_Y = 64, LOBBY_Z = 400;

    // ------------------------------------------------------------------ 방향

    public static final int NORTH = 0, EAST = 1, SOUTH = 2, WEST = 3;
    /** 방향 한국어 이름(로그, 시험 글). */
    public static final String[] DIR_KO = {"북", "동", "남", "서"};
    /** 방향 글귀 키 조각(notice_dir_ne 처럼). */
    public static final String[] DIR_KEY = {"n", "e", "s", "w"};
    private static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};

    /** 방향 단위 벡터(y 0). */
    public static Vector dirVec(int d) {
        return new Vector(DX[d & 3], 0, DZ[d & 3]);
    }

    /** 가운데에서 본 (x, z) 의 가장 가까운 방향. */
    public static int dirOf(double x, double z) {
        double dx = x - CX, dz = z - CZ;
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? EAST : WEST;
        return dz >= 0 ? SOUTH : NORTH;
    }

    /** 방향 d 를 기준으로 한 (반지름 s, 옆 t) → 세계 x. 옆 +t 는 그 방향을 보고 오른쪽. */
    public static double gx(int d, double s, double t) {
        return CX + DX[d & 3] * s - DZ[d & 3] * t;
    }

    public static double gz(int d, double s, double t) {
        return CZ + DZ[d & 3] * s + DX[d & 3] * t;
    }

    // ------------------------------------------------------------------ 호환용(정자가 있던 때의 이름)

    /** 호환용: 정자가 없다. 0 은 한가운데 보급 상자 칸을 뜻한다(예전 기둥 자리 = 지금 보급 상자). */
    public static final int PAV = 0, PAV_ROOF = 0, PAV_DOOR = 0;
    /** 호환용: 처마가 없다. 허공에 매달리는 종의 매달림 높이(바닥 위 3블록)로만 쓴다. */
    public static final int EAVE_Y = FLOOR_Y + 3;

    // ------------------------------------------------------------------ 옛 대기실 방과 게이브 우리(설정 cage, 기본 꺼짐)

    /**
     * 설정 cage(기본 false). 켜면 생성기가 옛 대기실 방(LOBBY_*)을 짓고 {@link #lobby} 가 그 방을 주며 감독이 그 안 게이브 우리를 돌린다.
     * 꺼져 있으면 대기실은 중앙 원이고 방과 우리는 없다. Gave 가 월드를 읽기 전에 정한다.
     */
    public static volatile boolean cageRoom;

    /** 옛 대기실 안쪽 x 반폭, z 범위(벽 포함 LOBBY_Z0..LOBBY_Z1), 천장 y. */
    public static final int LOBBY_HX = 11, LOBBY_Z0 = 389, LOBBY_Z1 = 411, LOBBY_TOP = 71;
    /** 게이브 우리 안쪽 z 범위(칸), 유리벽 z. */
    public static final int CAGE_Z0 = 390, CAGE_Z1 = 397, GLASS_Z = 398;

    /** 옛 대기실 방 자리인가(좌표만 봄. 방은 cage 를 켰을 때만 있다). */
    public static boolean inLobby(double x, double z) {
        return x >= -LOBBY_HX && x < LOBBY_HX + 1 && z >= LOBBY_Z0 && z < LOBBY_Z1 + 1;
    }

    /** 우리 안(몹이 설 수 있는 칸). */
    public static boolean inCage(double x, double z) {
        return x >= -LOBBY_HX + 1 && x < LOBBY_HX && z >= CAGE_Z0 && z < CAGE_Z1 + 1;
    }

    /** 우리 안 무작위 점(벽에서 1.2 블록 띄움). */
    public static Vector cagePoint(Random r, double y) {
        double x = -LOBBY_HX + 2.2 + r.nextDouble() * (2 * LOBBY_HX - 3.4);
        double z = CAGE_Z0 + 1.2 + r.nextDouble() * (CAGE_Z1 - CAGE_Z0 - 1.4);
        return new Vector(x, y, z);
    }

    // ------------------------------------------------------------------ 배치 표(생성기와 목록이 같이 씀)

    /**
     * 땅 갈라진 틈(예전 하수구 쇠창살 자리, 원 밖 R 20~60): {가운데 x, z, 길이 반(블록), 뻗는 방향(0..7, 45도씩, 0 은 +x)}. 눈에 보이는 어두운 금.
     * 땅 갈라짐 사건 때 웅덩이 게이브가 스며 나온다.
     */
    static final int[][] GRATES = {{23, 6, 4, 1}, {31, 31, 5, 3}, {8, 30, 3, 0}, {-14, 54, 5, 2}, {-16, 16, 4, 1}, {-45, 12, 5, 2},
        {-35, -9, 4, 3}, {-41, -41, 5, 1}, {-7, -26, 3, 0}, {11, -40, 4, 2}, {23, -23, 4, 3}, {50, -13, 5, 2}};
    /** 얕은 물웅덩이(R 25~45): {가운데 x, z, 반지름 x10}. */
    static final int[][] PUDDLES = {{33, 19, 22}, {0, 42, 26}, {-29, 17, 20}, {-23, -14, 19}, {-5, -38, 24}, {28, -12, 21}, {16, 21, 18}};

    static int hash(int x, int z, int salt) {
        int h = x * 374761393 + z * 668265263 + salt * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    /** 0..1 */
    static double rnd(int x, int z, int salt) {
        return (hash(x, z, salt) & 0xffffff) / (double) 0x1000000;
    }

    /** 부드러운 잡음 0..1(격자 무작위 값을 매끄럽게 이음). */
    static double noise(double x, double z, int salt) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = rnd(x0, z0, salt), b = rnd(x0 + 1, z0, salt), c = rnd(x0, z0 + 1, salt), d = rnd(x0 + 1, z0 + 1, salt);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** 틈 칸: 1 = 금(검은 속), 2 = 둘레(어두운 흙이 섞임). */
    private static final Map<Long, Byte> FISSURE = new HashMap<>();

    static {
        for (int i = 0; i < GRATES.length; i++) {
            int[] g = GRATES[i];
            double ang = g[3] * Math.PI / 4, ux = Math.cos(ang), uz = Math.sin(ang);
            double p1 = rnd(i, 3, 91) * 6.28, p2 = rnd(i, 5, 92) * 6.28;
            for (double t = -g[2]; t <= g[2] + 1e-6; t += 0.25) {
                // 가운데는 곧고 끝으로 갈수록 삐뚤어진 금
                double w = (0.8 * Math.sin(t * 0.9 + p1) + 0.45 * Math.sin(t * 2.1 + p2)) * Math.min(1, Math.abs(t) / 1.5);
                double px = g[0] + 0.5 + ux * t - uz * w, pz = g[1] + 0.5 + uz * t + ux * w;
                FISSURE.put(key((int) Math.floor(px), (int) Math.floor(pz)), (byte) 1);
                // 가운데 3분의 1 은 두 칸 폭
                if (Math.abs(t) <= g[2] / 3.0) FISSURE.put(key((int) Math.floor(px - uz * 0.8), (int) Math.floor(pz + ux * 0.8)), (byte) 1);
            }
        }
        List<int[]> core = new ArrayList<>();
        for (long k : FISSURE.keySet()) core.add(new int[] {(int) (k >> 32), (int) k});
        for (int[] c : core) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) FISSURE.putIfAbsent(key(c[0] + dx, c[1] + dz), (byte) 2);
        }
    }

    /** (x, z) 가 틈이면 1(금), 틈 둘레면 2, 아니면 0. */
    static int fissure(int x, int z) {
        Byte b = FISSURE.get(key(x, z));
        return b == null ? 0 : b;
    }

    /** (x, z) 가 물웅덩이 물 칸이면 1, 물가(젖은 흙)면 2, 아니면 0. */
    static int puddle(int x, int z) {
        for (int[] p : PUDDLES) {
            double dd = Math.hypot(x - p[0], z - p[1]), rr = p[2] / 10.0 + (rnd(x, z, 3) - 0.5) * 0.7;
            if (dd <= rr) return 1;
            if (dd <= rr + 1.2) return 2;
        }
        return 0;
    }

    // ------------------------------------------------------------------ 점 목록(종 담당이 씀)

    private static final List<Vector> GRATE_PTS, PUDDLE_PTS, LAMP_PTS;
    private static final List<List<Vector>> GATE_PTS;

    static {
        List<Vector> g = new ArrayList<>();
        for (int[] a : GRATES) g.add(new Vector(a[0] + 0.5, FLOOR_Y, a[1] + 0.5));
        GRATE_PTS = Collections.unmodifiableList(g);
        List<Vector> p = new ArrayList<>();
        for (int[] a : PUDDLES) p.add(new Vector(a[0] + 0.5, FLOOR_Y - 0.4, a[1] + 0.5));
        PUDDLE_PTS = Collections.unmodifiableList(p);
        LAMP_PTS = List.of(new Vector(CX + 0.5, FLOOR_Y + 1.5, CZ + 0.5));
        // 방향마다 나오는 고리 한가운데(반지름 78) 위 점들: 그 방향 좌우 40도, 4도마다
        List<List<Vector>> gl = new ArrayList<>();
        double rr = (R_SPAWN_IN + R_SPAWN_OUT) / 2.0;
        for (int d = 0; d < 4; d++) {
            List<Vector> l = new ArrayList<>();
            double a0 = Math.atan2(DZ[d], DX[d]);
            for (int k = -10; k <= 10; k++) {
                double a = a0 + Math.toRadians(k * 4);
                l.add(new Vector(CX + 0.5 + Math.cos(a) * rr, FLOOR_Y, CZ + 0.5 + Math.sin(a) * rr));
            }
            gl.add(Collections.unmodifiableList(l));
        }
        GATE_PTS = Collections.unmodifiableList(gl);
    }

    private static List<Vector> copy(List<Vector> l) {
        List<Vector> out = new ArrayList<>(l.size());
        for (Vector v : l) out.add(v.clone());
        return out;
    }

    /** 호환용: 처마가 없어 늘 빈 목록(노크하는 게이브는 중앙 원 테두리 위 허공에 매달린다: 감독 placeFor). */
    public static List<Vector> eaves() {
        return new ArrayList<>();
    }

    /** 호환용: 문틀이 없어 늘 빈 목록. */
    public static List<Vector> doorFrames() {
        return new ArrayList<>();
    }

    /** 호환용: 문틀이 없어 늘 0. */
    public static int doorFacing(int i) {
        return 0;
    }

    /** 호환용: 문 위 장식 자리가 없어 늘 빈 목록(상점 오염은 메뉴 속 실눈 글리프만). */
    public static List<Vector> doorDecor() {
        return new ArrayList<>();
    }

    public static int doorDecorFacing(int i) {
        return 0;
    }

    /** 땅 갈라진 틈 가운데(바닥 높이, 원 밖 R 20~60). 땅 갈라짐 사건(예전 배관 범람)의 웅덩이 게이브가 스며 나오는 곳. */
    public static List<Vector> grates() {
        return copy(GRATE_PTS);
    }

    /** 호환용: 가마솥이 없어 늘 빈 목록. */
    public static List<Vector> cauldrons() {
        return new ArrayList<>();
    }

    /** 물웅덩이 가운데(물속 높이, 원 밖 R 25~45). 물웅덩이 속 눈. */
    public static List<Vector> puddles() {
        return copy(PUDDLE_PTS);
    }

    /** 물웅덩이 반지름(블록), puddles() 와 같은 차례. */
    public static double puddleRadius(int i) {
        return PUDDLES[i][2] / 10.0;
    }

    /** 불 자리: 보급 상자 위 등불 하나뿐. */
    public static List<Vector> lamps() {
        return copy(LAMP_PTS);
    }

    /** 지붕 밑 걷는 칸 가운데. 지붕이 없어 새 맵에서는 빈 목록(크리에이티브로 지붕을 지으면 scan 이 찾음). */
    public static List<Vector> roofs() {
        return copy(roofList);
    }

    /** 방향 d 쪽 나오는 고리 위 점들(바닥 높이, 반지름 78, 그 방향 좌우 40도). 터널은 없다. */
    public static List<Vector> gates(int d) {
        return copy(GATE_PTS.get(d & 3));
    }

    /** 방향 d 쪽 나오는 고리 안쪽 끝 가운데(바닥 높이). */
    public static Vector gate(int d) {
        return new Vector(gx(d, R_SPAWN_IN + 1, 0) + 0.5, FLOOR_Y, gz(d, R_SPAWN_IN + 1, 0) + 0.5);
    }

    /** 호환용: 터널이 없어 늘 false. */
    public static boolean nearGate(double x, double z, double margin) {
        return false;
    }

    // ------------------------------------------------------------------ 자리

    public static Location center(World w) {
        return new Location(w, CX + 0.5, FLOOR_Y, CZ + 0.5);
    }

    /** 가운데(보급 상자 칸 가운데)에서 (x, z) 까지 수평 거리. */
    public static double radius(double x, double z) {
        return Math.hypot(x - (CX + 0.5), z - (CZ + 0.5));
    }

    /** 대기실 자리: 중앙 원 안, 보급 상자 남쪽 6블록에서 상자를 봄(설정 cage 를 켰으면 옛 대기실 방에서 우리를 봄). 접속 자리이자 월드 스폰. */
    public static Location lobby(World w) {
        if (cageRoom) return new Location(w, LOBBY_X + 0.5, LOBBY_Y, LOBBY_Z + 2.5, 180, 0);
        return new Location(w, CX + 0.5, FLOOR_Y, CZ + 6.5, 180, 0);
    }

    /** 대기실 자리를 사람마다 흩어서: 중앙 원 안(가운데에서 4.5~9.5블록) 아무 데서 보급 상자를 봄. 여럿이 같이 들어와도 겹치지 않게. */
    public static Location lobbySpot(World w) {
        if (cageRoom) return lobby(w);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double a = r.nextDouble() * Math.PI * 2, rr = 4.5 + r.nextDouble() * 5;
        double dx = Math.cos(a) * rr, dz = Math.sin(a) * rr;
        float yaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
        return new Location(w, CX + 0.5 + dx, FLOOR_Y, CZ + 0.5 + dz, yaw, 0);
    }

    /** 보급 상자 앞(부활 자리, 상자 남쪽 R_SHOP + 1 블록에서 상자를 봄). */
    public static Location shopFront(World w) {
        return new Location(w, CX + 0.5, FLOOR_Y, CZ + R_SHOP + 1.5, 180, 0);
    }

    // ------------------------------------------------------------------ 지금 땅(원정의 막)

    /** 지금 땅의 막(0 옛 땅, 1 마른 길, 2 젖은 벌, 3 재의 땅, 4 발사장). kr.gave.trip.Repaint 가 칠하기 시작할 때 새 막으로 바꾼다. 아직 모르면 -1(바탕 막으로 봄). */
    public static volatile int groundAct = -1;
    private static final java.util.BitSet WET = new java.util.BitSet();
    private static boolean wetReady;

    /** 지금 땅의 막(모르면 바탕 막: 원정이 켜져 있으면 1, 꺼져 있으면 0). */
    public static int groundAct() {
        int a = groundAct;
        return a < 0 ? PlazaGen.baseAct() : a;
    }

    /**
     * 물 칸인가(물이 든 보이지 않는 꽉 찬 블록: 걷는 높이는 같다). 막마다 다르다(젖은 벌은 물길과 얕은 물이 많다). 다시 칠하는 동안에는 칠한 데까지만
     * 새 막이다. 격자 밖은 고정 웅덩이만. 메인 스레드에서만 부른다.
     */
    public static boolean wet(int x, int z) {
        int c = cell(x, z);
        if (c < 0) return puddle(x, z) == 1;
        if (!wetReady) fillWet(groundAct());
        return WET.get(c);
    }

    /** 다시 칠하기가 칸마다 알림(물이 됐나). */
    public static void wet(int x, int z, boolean on) {
        int c = cell(x, z);
        if (c < 0) return;
        if (!wetReady) fillWet(groundAct());
        WET.set(c, on);
    }

    /** 물 칸 격자를 막 act 의 땅으로 다시 채움(다시 칠하기를 건너뛰고 땅을 맞췄을 때). */
    public static void fillWet(int act) {
        int[] buf = new int[4];
        WET.clear();
        for (int z = MIN; z <= MAX; z++) for (int x = MIN; x <= MAX; x++) {
            if (PlazaGen.WATER.equals(PlazaGen.surface(x, z, act, 0, buf))) WET.set(cell(x, z));
        }
        wetReady = true;
    }

    /** 격자 안 물 칸 수(시험). */
    public static int wetCount() {
        if (!wetReady) fillWet(groundAct());
        return WET.cardinality();
    }

    // ------------------------------------------------------------------ 걷기 격자

    static final int W = MAX - MIN + 1;
    private static final float[] floorY = new float[W * W];
    private static final boolean[] open = new boolean[W * W], roofed = new boolean[W * W];
    private static volatile boolean scanned;
    private static List<Vector> roofList = List.of();
    private static String lastScan = "아직 안 읽음";

    private static int cell(int x, int z) {
        int ix = x - MIN, iz = z - MIN;
        if (ix < 0 || iz < 0 || ix >= W || iz >= W) return -1;
        return iz * W + ix;
    }

    /** 이 칸의 걷는 바닥 높이(설 수 있는 면의 y). 황야는 어디나 FLOOR_Y. */
    public static double floor(double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int c = cell(bx, bz);
        if (c >= 0 && scanned) return floorY[c];
        if (inLobby(x, z)) return LOBBY_Y;
        return FLOOR_Y;
    }

    /** 땅 몹, 사람이 설 수 있는 칸인가(사람 키 두 칸 안에 단단한 블록 없음). 새 맵에서는 보급 상자 칸만 막힌다. 격자 밖은 R_EDGE 안만. */
    public static boolean walkable(int x, int z) {
        int c = cell(x, z);
        if (c >= 0 && scanned) return open[c];
        if (inLobby(x, z)) return inCage(x + 0.5, z + 0.5);
        long dx = x - CX, dz = z - CZ;
        return dx * dx + dz * dz <= (long) R_EDGE * R_EDGE;
    }

    /** 머리 위(바닥 + 2 위)에 막는 블록이 있나. 지붕이 없어 새 맵에서는 늘 false. 격자 밖은 false. */
    public static boolean roofed(int x, int z) {
        int c = cell(x, z);
        return c >= 0 && scanned && roofed[c];
    }

    public static boolean scanned() {
        return scanned;
    }

    /** 서버가 켤 때 한 번(월드를 읽어 격자를 채움). 다시 불러도 된다(맵을 고친 뒤). */
    public static void scan(World w) {
        long t0 = System.nanoTime();
        int walk = 0, blocked = 0, roofs = 0, uneven = 0;
        List<Vector> rl = new ArrayList<>();
        for (int z = MIN; z <= MAX; z++) {
            for (int x = MIN; x <= MAX; x++) {
                int c = cell(x, z);
                double[] fb = column(w, x, z);
                floorY[c] = (float) fb[0];
                open[c] = fb[1] > 0;
                boolean rf = false;
                if (open[c]) {
                    int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
                    rf = top >= fb[0] + 1.9 && top < fb[0] + 40;
                    walk++;
                    if (Math.abs(fb[0] - FLOOR_Y) > 1e-3) uneven++;
                } else blocked++;
                roofed[c] = rf;
                if (rf) {
                    rl.add(new Vector(x + 0.5, fb[0], z + 0.5));
                    roofs++;
                }
            }
        }
        roofList = Collections.unmodifiableList(rl);
        scanned = true;
        lastScan = String.format(Locale.ROOT, "walk=%d blocked=%d roofed=%d uneven=%d ms=%.0f", walk, blocked, roofs, uneven, (System.nanoTime() - t0) / 1e6);
    }

    /** 한 열: {바닥 높이, 걸을 수 있으면 1}. */
    private static double[] column(World w, int x, int z) {
        double floor = Double.NaN;
        boolean ok = true;
        // 발 높이 블록(FLOOR_Y): 낮은 것(반 블록 넘지 않는 것)은 올라선 바닥, 높은 것은 막힘
        Block b64 = w.getBlockAt(x, FLOOR_Y, z);
        double[] s64 = shape(b64);
        if (s64 != null) {
            double top = FLOOR_Y + s64[1];
            if (s64[1] <= 0.6) floor = top;
            else return new double[] {top, 0};
        } else {
            for (int y = FLOOR_Y - 1; y >= FLOOR_Y - 4; y--) {
                double[] s = shape(w.getBlockAt(x, y, z));
                if (s != null) {
                    floor = y + s[1];
                    break;
                }
            }
            if (Double.isNaN(floor)) return new double[] {FLOOR_Y - 4, 0};
        }
        // 사람 키(1.8) 안에 걸리는 블록이 있으면 막힘
        int y0 = (int) Math.floor(floor), y1 = (int) Math.floor(floor + 1.79);
        for (int y = y0; y <= y1; y++) {
            Block b = w.getBlockAt(x, y, z);
            double[] s = shape(b);
            if (s == null) continue;
            double lo = y + s[0], hi = y + s[1];
            if (hi > floor + 0.01 && lo < floor + 1.8) {
                ok = false;
                break;
            }
        }
        return new double[] {floor, ok ? 1 : 0};
    }

    /** 블록 충돌 상자의 {아래, 위}(블록 안 0..1, 울타리는 1.5). 충돌 없으면 null(공기, 물, 빛 블록, 마른 덤불). 배리어(물웅덩이 바닥)는 꽉 찬 블록. */
    private static double[] shape(Block b) {
        Material m = b.getType();
        if (m.isAir() || m == Material.WATER || m == Material.LAVA || m == Material.LIGHT) return null;
        if (m == Material.BARRIER) return new double[] {0, 1};
        var boxes = b.getCollisionShape().getBoundingBoxes();
        if (boxes.isEmpty()) return null;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (BoundingBox bb : boxes) {
            lo = Math.min(lo, bb.getMinY());
            hi = Math.max(hi, bb.getMaxY());
        }
        return new double[] {lo, hi};
    }

    /** 사람이 읽을 구역 이름(맵점 시험). */
    public static String zone(double x, double z) {
        if (cageRoom && inLobby(x, z)) return inCage(x, z) ? "우리" : "대기실";
        if ((int) Math.floor(x) == CX && (int) Math.floor(z) == CZ) return "보급상자";
        double r = radius(x, z);
        if (r <= R_SHOP + 0.5) return "상점둘레";
        if (r <= R_SAFE + 0.5) return "중앙원";
        if (r <= R_ROAM) return "황야";
        if (r < R_SPAWN_IN) return "바깥";
        if (r <= R_SPAWN_OUT) return "고리" + DIR_KO[dirOf(x, z)];
        if (r <= R_EDGE) return "가장자리";
        return "밖";
    }

    // ------------------------------------------------------------------ 시험 훅

    /** 맵검사, 맵점 &lt;이름&gt;, 맵목록 &lt;종류&gt;. 아니면 null. */
    public static String test(Player p, String[] a) {
        if (a.length < 2) return null;
        switch (a[1]) {
            case "맵검사" -> {
                return "[맵] " + lastScan + " scanned=" + scanned + " grates=" + GRATE_PTS.size() + " puddles=" + PUDDLE_PTS.size() + " lamps=" + LAMP_PTS.size()
                        + " eaves=0 frames=0 cauldrons=0 buildings=0 shop=" + walkable(CX, CZ) + "/" + walkable(CX + 1, CZ) + " exits=" + GATE_PTS.size()
                        + " R=" + R_SHOP + "/" + R_SAFE + "/" + R_ROAM + "/" + R_SPAWN_IN + "-" + R_SPAWN_OUT + "/" + R_EDGE + " grid=" + MIN + ".." + MAX
                        + " cage=" + cageRoom + " act=" + groundAct() + " wet=" + wetCount();
            }
            case "맵점" -> {
                if (p == null) return "[맵] 사람 없음";
                Location l = p.getLocation();
                int x = l.getBlockX(), z = l.getBlockZ();
                return String.format(Locale.ROOT, "[맵] %d,%d floor=%.2f walk=%b roof=%b zone=%s r=%.1f", x, z, floor(l.getX(), l.getZ()), walkable(x, z),
                        roofed(x, z), zone(l.getX(), l.getZ()), radius(l.getX(), l.getZ()));
            }
            case "맵목록" -> {
                String k = a.length > 3 ? a[3] : "";
                List<Vector> l = switch (k) {
                    case "처마", "문틀", "장식", "가마솥" -> List.of();
                    case "창살", "틈" -> GRATE_PTS;
                    case "웅덩이" -> PUDDLE_PTS;
                    case "지붕" -> roofList;
                    case "등불" -> LAMP_PTS;
                    case "북", "동", "남", "서" -> GATE_PTS.get(List.of(DIR_KO).indexOf(k));
                    default -> null;
                };
                if (l == null) return "[맵] 맵목록 틈(창살)|웅덩이|등불|북|동|남|서 (처마, 문틀, 장식, 가마솥, 지붕은 빈 목록)";
                StringBuilder sb = new StringBuilder("[맵] " + k + " " + l.size());
                for (int i = 0; i < Math.min(6, l.size()); i++) {
                    Vector v = l.get(i);
                    sb.append(String.format(Locale.ROOT, " (%.1f,%.1f,%.1f)", v.getX(), v.getY(), v.getZ()));
                }
                return sb.toString();
            }
            default -> {
                return null;
            }
        }
    }

    private Plaza() {
    }
}
