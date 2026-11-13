package kr.gave.trip;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.papermc.paper.datacomponent.DataComponentTypes;
import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.mob.boss.Boss;
import kr.gave.mob.boss.BossDirector;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

/**
 * 원정의 발사장(docs/모듈/원정.md 6절, 31~35라운드)과 엔딩. 부품 이름 Launch(차례 Rounds 다음).
 *
 * <p>4막에 들어서면(발사장 이동이 화면을 다 가린 때, 아니면 31라운드 앞) 트럭 앞(북쪽)에 발사대와 우주선, 건조 지점 셋(연료 정제기, 발전기, 공학 작업대),
 * 투광등 넷을 세운다. 모델은 모두 모드에서 옮긴 것(Stellaris, TrafficCraft: tools/trip/launch.py, 치수는 jar 의 trip/launch.json).
 *
 * <p>건조(31~34): 라운드마다 4막 전용 종류 하나의 축 값만 빌리고(Rounds.use(키, false): 이름 띠, 배율, 나오는 자리, 장치) 목표는 여기서 쥔다.
 * 지점마다 반지름 {@link Tune#POINT_RING} 원에 선 사람이 원 안 게이브보다 많으면 그 지점이 돈다. 도는 지점 수 k 에 따라 그 라운드 몫(25%)이
 * {@link Tune#BUILD_SECONDS} x 3 / k 초에 찬다(3지점 90초, 2지점 135초, 1지점 270초, 0 이면 멈춤). 몫이 차기 전에는 감독이 끝났다 해도 라운드가
 * 안 끝나고 떼가 끝없이 온다(대기열 채우기). 우주선은 25% 마다 모델이 바뀐다(아래 틀 → 몸통 → 머리 → 불 켜짐).
 *
 * <p>35: 건조 100%, 보스 B5. B5 가 쓰러지면(보스 감독의 엔딩 0~100틱: 세상 멈춤, 딸깍, 무너짐 물결) 탑승 → 초읽기 10 → 점화 → 상승 → '떠났다'/'남았다'
 * → 검은 화면에 엔딩 다섯 줄(ending_1..5, 사용자가 정한 글) → 판 끝(설정 trip.afterLaunch 가 켜져 있으면 36라운드로 이어 감).
 * 다섯 줄 뒤에는 참가자 이름 크레딧(2026-11-12 사용자 결정, 설정 trip.credits 기본 켬): 그 판에 들어온 사람 이름만, 검은 화면 위 HUD 로만(hud 값
 * credits.lines, 쪽마다 {@link Tune#CREDIT_ROWS} 줄씩 바꿔 가며), 곡 cue credits. 이름은 메모리에만 두고 판 정리에 지운다(파일, 기록에 안 모음).
 *
 * <p>투광등 넷은 trip_special_b 의 {@link PadLights}(판마다 한 벌, Mobs.Light, 빛 블록, 발전기, 꺼진 사분면 어둠)를 발사장에 들어설 때 이 부품이 세우고
 * ({@code PadLights.of(game).raise(this)}) 발사장을 치울 때 내린다. B5 의 등 끄기, 투광등 라운드(litpad)는 그 한 벌을 끄고 켠다.
 */
public final class Launch implements TripPart {

    /** 숫자 표 한곳(켠 채 바꾸기 '/gave 시험 발사값 x [이름 [값]]'). */
    public static final class Tune {
        /** 발사대 가운데(트럭 가운데에서 옮김, 블록): 트럭 앞(북쪽) */
        public static double PAD_X = 0, PAD_Z = -9.5;
        /** 발사대 판을 납작하게(모드 두께 x 이 값: 사람 발이 판에 덜 묻히게) */
        public static double PAD_FLAT = 0.35;
        /** 건조 지점: 발사대에서 반지름, 첫 지점 각(도, 0 = 동 +x, 90 = 남 +z), 원 반지름, 기계를 원 바깥쪽으로 민 거리, 기계 배율 */
        public static double POINT_R = 18, POINT_A0 = -90, POINT_RING = 7, MACHINE_OUT = 2.6, MACHINE_SCALE = 1.8;
        /** 세 지점이 다 돌 때 한 라운드 몫(BUILD_PER_ROUND)이 차는 초. 라운드 몫, 그 라운드가 이 초를 넘으면 몫을 채워 끝냄(판이 멈추지 않게) */
        public static double BUILD_SECONDS = 90, BUILD_PER_ROUND = 0.25, BUILD_MAX_SECONDS = 600;
        /** 건조 중 떼 채우기: 대기열이 이만큼(동시 수 목표 몫, 아래끝) 남게, 한 번에 더하는 수 */
        public static double STREAM_SHARE = 0.5, STREAM_MIN = 6;
        public static int STREAM_STEP = 6;
        /** 목표를 마친 뒤 떼가 물러나는 틱(Rounds 가 없을 때) */
        public static int RETREAT_TICKS = 60;
        /** 꺼지는 등 라운드(f_lights): 켜진 등 하나를 부수는 간격(틱). 다시 켜는 것은 PadLights 발전기(밑동에 서 있기) */
        public static int LIGHTS_EVERY = 500;
        /** B5 가 제 블록 등(B5Land: 같은 R 30 대각선)을 세우는 동안 발사장 투광등을 내림(B5 가 PadLights 를 쓰게 되면 false) */
        public static boolean B5_OWN_LAMPS = false;
        /** 로켓 밝기(디스플레이 블록 빛): 짓는 중, 불 켜짐 */
        public static int ROCKET_LIGHT = 9, ROCKET_LIT = 15;
        /** 엔딩 차례(틱): B5 가 쓰러진 뒤 탑승까지(보스 감독의 엔딩 연출 0~100), 탑승, 초읽기(10초), 점화, 상승, 그 뒤, 검은 화면이 짙어지는 틱 */
        public static int BOSS_WAIT = 100, BOARD_TICKS = 1200, COUNT_TICKS = 200, IGNITE_TICKS = 40, RISE_TICKS = 160, AFTER_TICKS = 60, FADE_TICKS = 30,
            END_HOLD = 40;
        /** 엔딩 다섯 줄: 줄마다 틱, 마지막 줄 뒤 머무는 틱 */
        public static int LINE_TICKS = BossDirector.ENDING_LINE_TICKS, LINE_HOLD = 60;
        /** 빨리(시험 '발사 x 빨리'): 탑승, 초읽기 틱 */
        public static int FAST_BOARD = 120, FAST_COUNT = 100;
        /** 상승 높이(블록), 점화 불길 반지름, 탑승 원 반지름, 탑승 원 자리(발사대 가운데에서 남쪽으로) */
        public static double RISE_HEIGHT = 160, FIRE_R = 20, BOARD_R = 3, BOARD_Z = 3.6;
        /** 좌석: 가운데에서 반지름, 첫 층 높이, 층 사이, 한 층 사람 수 */
        public static double SEAT_R = 0.55, SEAT_Y0 = 3.0, SEAT_DY = 1.1;
        public static int SEAT_RING = 4;
        /** 남은 사람 화면: 발사 뒤 밀려드는 떼 수 */
        public static int LEFT_HORDE = 24;
        /** 초읽기 큰 숫자를 바닐라 제목 글로도(기본 꺼짐: 알림은 HUD 로만. 숫자는 hud 값 launch.count 를 HUD 가 그림) */
        public static boolean TITLE_COUNT = false;
        /** 크레딧: 한 쪽 줄 수(HUD 칸 넷), 한 줄 이름 수(사람이 쪽 x 줄보다 많을 때만 둘), 한 쪽 틱, 앞 검은 틱, 끝 검은 틱 */
        public static int CREDIT_ROWS = 4, CREDIT_PER_ROW = 2, CREDIT_PAGE = 70, CREDIT_HEAD = 20, CREDIT_TAIL = 30;
        /** 4막 특수 라운드로 돌릴 라운드(그 특수 클래스가 있을 때만: litpad, fuelfire, skyfall 차례로). 끝나면 건조 몫을 채운다 */
        public static int[] SPECIAL_ROUNDS = {32, 34};

        private Tune() {
        }
    }

    /** 4막 전용 라운드 종류 가운데 건조로 돌리는 것(f_count 는 버티기라 뺌). */
    static final String[] BUILD_KINDS = {"f_fuel", "f_frame", "f_power", "f_board", "f_lights", "f_pad", "f_everyone"};
    static final String[] PAD_SPECIALS = {"litpad", "fuelfire", "skyfall"};
    static final String TAG = "gave_trip_launch";
    /** 건조 지점 기계(꺼짐, 켜짐). */
    static final String[][] MACHINES = {{"trip/lc_fuel", "trip/lc_fuel_on"}, {"trip/lc_gen", "trip/lc_gen_on"}, {"trip/lc_station", "trip/lc_station"}};
    static final String[] ROCKET_STATES = {"base", "hull", "nose", "lit"};
    /** 시험 훅 '발사 x 이름 n' 의 이름(에일리언 연작 인물: 시험 이름은 실제 작품 속 이름). */
    static final String[] TEST_NAMES = {"Dallas", "Kane", "Lambert", "Parker", "Brett", "Ash", "Hicks", "Hudson", "Vasquez", "Gorman", "Apone", "Drake", "Frost",
        "Dietrich", "Ferro", "Spunkmeyer", "Burke", "Newt", "Bishop", "Wierzbowski", "Crowe", "Jones", "Call", "Johner", "Vriess", "Christie", "Distephano", "Wren",
        "Gediman", "Elgyn", "Purvis"};

    public enum Phase { NONE, BOSS, BOARD, COUNT, IGNITE, RISE, AFTER, LINES, CREDITS, DONE }

    /** 모델 치수 하나(모델 px: 가로 가운데 cx, cz, 바닥, 꼭대기, 권하는 표시 배율, 발사대 판 윗면). */
    record Dims(double cx, double cz, double ground, double top, double scale, double platTop) {
        double block(double px) {
            return px * scale / 16.0;
        }
    }

    public final Game game;
    public final Trip trip;
    private final Random rnd = new Random();
    private final Map<String, Dims> dims = new HashMap<>();

    // 발사장
    private boolean built;
    private ItemDisplay rocket, padBase, padTower, boardRing;
    private final ItemDisplay[] machines = new ItemDisplay[3];
    private final ItemDisplay[] rings = new ItemDisplay[3];
    private final int[] ringShown = {-1, -1, -1};
    private final boolean[] machineOn = new boolean[3];
    private String rocketShown = "";
    private double lift;
    private boolean lampsHidden;

    // 건조
    private double build;
    private int buildRound = -1;
    private double roundProg;
    private long roundAt, retreatUntil = -1;
    private boolean roundMet, goalsOwned;
    private String kindKey;
    private final List<String> usedKinds = new ArrayList<>();
    private final List<String> usedSpecials = new ArrayList<>();
    private int specialRound = -1;
    private String specialKey;
    private final int[] people = new int[3], mobsIn = new int[3];
    private long lightsAt = -1;

    // 엔딩
    private Phase phase = Phase.NONE;
    private long phaseAt, bossDownAt = -1;
    private boolean fast, thereShown, storyMine;
    private final Map<UUID, Integer> seats = new LinkedHashMap<>();
    private int boardedCount, leftCount;
    private long storyAt = -1;
    private int storyLen;
    /** 이 판에 들어온 사람 이름(들어온 차례, 크레딧에만 씀: 판 정리에 지움). 크레딧 쪽(줄 목록). */
    private final java.util.LinkedHashSet<String> joined = new java.util.LinkedHashSet<>();
    private final List<List<String>> creditPages = new ArrayList<>();

    public Launch(Game game, Trip trip) {
        this.game = game;
        this.trip = trip;
        loadDims();
    }

    // ------------------------------------------------------------------ 치수

    private void loadDims() {
        try (InputStream in = game.plugin.getResource("trip/launch.json")) {
            if (in == null) {
                trip.log("발사장 치수 trip/launch.json 이 jar 에 없다(tools/make_trip.py 로 굽기): 기본값");
                return;
            }
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String k : new String[] {"rocket", "lc_pad_base", "lc_pad_tower", "machine", "lamp"}) {
                if (!o.has(k)) continue;
                JsonObject d = o.getAsJsonObject(k);
                dims.put(k, new Dims(num(d, "cx", 8), num(d, "cz", 8), num(d, "ground", 0), num(d, "top", 16), num(d, "scale", 1), num(d, "platTop", 0)));
            }
        } catch (Exception e) {
            trip.log("발사장 치수 읽기 오류: " + e);
        }
    }

    private static double num(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    private Dims dim(String k) {
        Dims d = dims.get(k);
        if (d != null) return d;
        return switch (k) {
            case "rocket" -> new Dims(8, 8, -15.75, 31.75, 6.06, 0);
            case "lc_pad_base" -> new Dims(8, 8, -15.75, -12.78, 3.28, -12.78);
            case "lc_pad_tower" -> new Dims(8, 8, -15.75, 31.75, 5.96, -14.12);
            case "lamp" -> new Dims(8, 8, -16, 32, 3.0, 0);
            default -> new Dims(8, 8, 0, 16, 1, 0);
        };
    }

    // ------------------------------------------------------------------ 자리

    /** 발사대 가운데(바닥). */
    public Vector pad() {
        return new Vector(Truck.OX + Tune.PAD_X, Plaza.FLOOR_Y, Truck.OZ + Tune.PAD_Z);
    }

    /** 발사대 판 윗면 높이(바닥에서 블록). */
    double padTop() {
        Dims b = dim("lc_pad_base");
        return Math.max(0, b.block(b.platTop - b.ground) * Tune.PAD_FLAT);
    }

    /** 건조 지점 i(0 연료, 1 발전기, 2 작업대)의 원 가운데. */
    public Vector point(int i) {
        double a = Math.toRadians(Tune.POINT_A0 + i * 120);
        Vector c = pad();
        return new Vector(c.getX() + Math.cos(a) * Tune.POINT_R, Plaza.FLOOR_Y, c.getZ() + Math.sin(a) * Tune.POINT_R);
    }

    Vector boardSpot() {
        Vector c = pad();
        return new Vector(c.getX(), Plaza.FLOOR_Y, c.getZ() + Tune.BOARD_Z);
    }

    private Location loc(Vector v, double up) {
        return new Location(game.world, v.getX(), v.getY() + up, v.getZ());
    }

    // ------------------------------------------------------------------ 공개(다른 부품, 보스, 특수 라운드)

    /** 4막 발사장이 서 있나. */
    public boolean built() {
        return built;
    }

    /** 건조 막대(0..1, 31~34 합). */
    public double build() {
        return build;
    }

    /** 건조를 깎음(B5 감싸기 같은 것). 지금 라운드 몫 안에서만 깎인다. */
    public void damageBuild(double frac, String cause) {
        if (frac <= 0) return;
        double floor = buildRound > 0 ? (buildRound - Trip.LAST_ROAD_ROUND - 1) * Tune.BUILD_PER_ROUND : 0;
        double before = build;
        build = Math.max(floor, build - frac);
        if (buildRound > 0) roundProg = Math.max(0, (build - floor) / Tune.BUILD_PER_ROUND);
        trip.log(String.format(Locale.ROOT, "건조 깎임 %.3f → %.3f (%s)", before, build, cause));
        showRocket();
    }

    /** 발사장 투광등 한 벌(trip_special_b 의 PadLights: 발사장이 서 있는 동안 이 부품이 세운 것). */
    public PadLights lights() {
        return PadLights.of(game);
    }

    /** 엔딩 차례(NONE 이면 안 함). */
    public Phase phase() {
        return phase;
    }

    /** 이 사람이 우주선에 탔나. */
    public boolean boarded(Player p) {
        return seats.containsKey(p.getUniqueId());
    }

    // ------------------------------------------------------------------ 세우기, 치우기

    private static ItemStack item(String model) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(Props.NS, model));
        return it;
    }

    private ItemDisplay spawn(Location at, String model, Matrix4f m, float w, float h, int light) {
        return game.world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setItemStack(item(model));
            d.setTransformationMatrix(m);
            d.setBrightness(new Display.Brightness(light, light));
            d.setViewRange(4.0f);
            d.setDisplayWidth(w);
            d.setDisplayHeight(h);
            d.setTeleportDuration(0);
        });
    }

    /** 발사장을 세운다(이미 섰으면 그대로). */
    public void ensureBuilt() {
        if (built) return;
        for (Entity e : game.world.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        built = true;
        Vector c = pad();
        // 발사대 판(납작하게)과 탑
        Dims b = dim("lc_pad_base");
        float kb = (float) b.scale, fl = (float) Tune.PAD_FLAT;
        padBase = spawn(loc(c, 0.01), "trip/lc_pad_base", new Matrix4f().translate(0f, (float) (b.block(8 - b.ground) * fl), 0f).rotateY((float) Math.PI)
            .scale(kb, kb * fl, kb), (float) b.block(48) + 1f, 1.5f, 10);
        Dims t = dim("lc_pad_tower");
        float kt = (float) t.scale;
        // 탑은 판 밑면(모드에서 땅)에서 선다: 탑 모델 좌표의 판 밑면(ground)을 땅에
        padTower = spawn(loc(c, 0.01), "trip/lc_pad_tower", new Matrix4f().translate(0f, (float) t.block(8 - t.ground), 0f).rotateY((float) Math.PI).scale(kt),
            (float) t.block(48) + 1f, (float) t.block(t.top - t.ground) + 1f, 10);
        lift = 0;
        rocketShown = "";
        showRocket();
        // 건조 지점 기계
        Dims m = dim("machine");
        for (int i = 0; i < 3; i++) {
            Vector p = point(i), out = p.clone().subtract(c).setY(0).normalize();
            Vector at = p.clone().add(out.multiply(Tune.MACHINE_OUT));
            float km = (float) Tune.MACHINE_SCALE;
            float yaw = (float) Math.atan2(out.getX(), out.getZ());
            machines[i] = spawn(loc(at, 0.01), MACHINES[i][0], new Matrix4f().translate(0f, (float) (km * (8 - m.ground) / 16.0), 0f).rotateY(yaw)
                .rotateY((float) Math.PI).scale(km), 2.5f, 2.5f, 10);
            machineOn[i] = false;
        }
        // 투광등(PadLights 한 벌: 모델, 빛 블록, 발전기)
        PadLights.of(game).raise(this);
        lampsHidden = false;
        trip.log(String.format(Locale.ROOT, "발사장 섬: 발사대 (%.1f, %.1f), 지점 셋 R %.0f, 투광등 %s", c.getX(), c.getZ(), Tune.POINT_R,
            PadLights.of(game).status()));
    }

    /** 우주선 모델(건조 상태)과 자리(오른 높이). */
    private void showRocket() {
        if (!built) return;
        String state = rocketState();
        Dims r = dim("rocket");
        Vector c = pad();
        Location at = loc(c, padTop() + lift + 0.01);
        if (rocket == null || !rocket.isValid()) {
            float k = (float) r.scale;
            rocket = spawn(at, "trip/lc_rocket_" + state, new Matrix4f().translate(0f, (float) r.block(8 - r.ground), 0f).rotateY((float) Math.PI).scale(k),
                8f, (float) r.block(r.top - r.ground) + 2f, state.equals("lit") ? Tune.ROCKET_LIT : Tune.ROCKET_LIGHT);
            rocketShown = state;
            return;
        }
        if (!state.equals(rocketShown)) {
            rocket.setItemStack(item("trip/lc_rocket_" + state));
            int lv = state.equals("lit") ? Tune.ROCKET_LIT : Tune.ROCKET_LIGHT;
            rocket.setBrightness(new Display.Brightness(lv, lv));
            if (!rocketShown.isEmpty()) {
                Props.soundAround(at.clone().add(0, 4, 0), "trip.metal_groan", Sound.BLOCK_ANVIL_USE, 1f, 0.1f, 0.6f, 96, -1);
                game.horizon.shake(0.2, 10);
            }
            rocketShown = state;
        }
    }

    /** 건조 몫에 맞는 우주선 상태(25% 마다 바뀜). 엔딩 차례에는 불 켜짐. */
    private String rocketState() {
        if (phase != Phase.NONE || build >= 0.75 + 1e-6) return "lit";
        if (build >= 0.5) return "nose";
        if (build >= 0.25) return "hull";
        return "base";
    }

    private void clearSite() {
        for (ItemDisplay d : new ItemDisplay[] {rocket, padBase, padTower, boardRing}) if (d != null) d.remove();
        rocket = padBase = padTower = boardRing = null;
        for (int i = 0; i < 3; i++) {
            if (machines[i] != null) machines[i].remove();
            machines[i] = null;
        }
        clearRings();
        PadLights pl = PadLights.peek(game);
        if (pl != null) pl.lower(this);
        lampsHidden = false;
        for (Entity e : game.world.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        built = false;
        rocketShown = "";
        lift = 0;
    }

    private void clearRings() {
        for (int i = 0; i < 3; i++) {
            if (rings[i] != null) rings[i].remove();
            rings[i] = null;
            ringShown[i] = -1;
        }
    }

    /** 바닥 원(쓰러짐 고리 셰이더의 초록 판, 몫만큼 호). Goals 의 원과 같은 꼴. */
    private ItemDisplay ring(ItemDisplay old, Vector at, double r, double frac, int idx) {
        int q = (int) Math.round(Math.max(0, Math.min(1, frac)) * 63);
        if (old != null && old.isValid() && idx >= 0 && q == ringShown[idx]) return old;
        if (idx >= 0) ringShown[idx] = q;
        ItemStack it = game.shotFx.downRing(frac, true);
        if (old != null && old.isValid()) {
            old.setItemStack(it);
            return old;
        }
        float s = (float) (r / Rounds.Tune.RING_BASE_R);
        return game.world.spawn(new Location(game.world, at.getX(), Plaza.FLOOR_Y + 0.05, at.getZ()), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformationMatrix(new Matrix4f().scale(s));
            d.setDisplayWidth((float) (r * 2.2));
            d.setDisplayHeight(1f);
            d.setViewRange(2.0f);
        });
    }

    private void setMachine(int i, boolean on) {
        if (machineOn[i] == on || machines[i] == null || !machines[i].isValid()) return;
        machineOn[i] = on;
        machines[i].setItemStack(item(MACHINES[i][on ? 1 : 0]));
        machines[i].setBrightness(new Display.Brightness(on ? 14 : 10, on ? 14 : 10));
        if (on) Props.soundAround(loc(point(i), 1), i == 0 ? "trip.pump" : i == 1 ? "trip.generator" : "trip.pad_machine", Sound.BLOCK_PISTON_EXTEND, 0.8f,
            0.1f, 0.8f, 32, -1);
    }

    // ------------------------------------------------------------------ 허브 고리

    @Override
    public void startGame() {
        resetState();
        noteNames();
    }

    /** 판에 든 사람 이름을 모음(크레딧용, 메모리에만). 나간 사람도 남긴다(그 판에 들어온 사람). */
    private void noteNames() {
        for (Player p : game.players.inPlay()) joined.add(p.getName());
    }

    @Override
    public void beforeRound(int n) {
        if (n > Trip.LAST_ROAD_ROUND) {
            ensureBuilt();
            // 건너뛴 라운드(시험 /gave 웨이브 n): 앞 라운드 몫은 채운 것으로
            double floor = Math.min(1, (n - Trip.LAST_ROAD_ROUND - 1) * Tune.BUILD_PER_ROUND);
            if (build < floor) build = floor;
            showRocket();
        }
    }

    @Override
    public void beginRound(int n) {
        buildRound = -1;
        roundMet = false;
        retreatUntil = -1;
        goalsOwned = false;
        if (n <= Trip.LAST_ROAD_ROUND || n > Trip.FINAL_ROUND || phase != Phase.NONE) return;
        if (n == Trip.FINAL_ROUND) {
            build = 1;
            showRocket();
            return;
        }
        buildRound = n;
        roundProg = 0;
        roundAt = game.now();
        lightsAt = -1;
        // 4막 특수 라운드(투광등, 연료 화재, 하늘이 내려온다)도 건조는 돈다: 끝(건조 한 칸)은 이 부품이 정하고(특수가 roundDone 에 false 를 넘김),
        // 빠르기는 mul("build"), 뒤로 돌리기는 사건 buildDrain(trip_special_b 약속)
        boolean special = n == specialRound;
        // 보스 라운드(33 B3: 고리가 발사장을 둘러싸고 조여 옴): 건조는 그대로 돌고, Rounds 는 보스에게 비켜 주므로 종류를 빌리지 않는다
        boolean boss = Trip.bossRound(n);
        kindKey = boss ? "boss" : special ? "special:" + specialKey : pickKind(n);
        Rounds r = trip.part(Rounds.class);
        if (r != null && kindKey != null && !special && !boss) {
            try {
                if (r.use(kindKey, false)) {
                    r.goals().beginCustom("ti_goal_build");
                    goalsOwned = true;
                }
            } catch (RuntimeException e) {
                trip.log("발사장 라운드 종류 " + kindKey + " 빌리기 오류: " + e);
            }
        }
        if ("f_lights".equals(kindKey)) lightsAt = game.now() + Tune.LIGHTS_EVERY / 2;
        if (n == Trip.LAST_ROAD_ROUND + 1 && Hud.hasPhrase("trip_hold")) {
            Bukkit.getScheduler().runTaskLater(game.plugin, () -> {
                if (buildRound == n) game.hud.notice("trip_hold", 60);
            }, 70);
        }
        for (int i = 0; i < 3; i++) game.hud.point(null, point(i).clone().setY(Plaza.FLOOR_Y + 1), 100);
        trip.log(String.format(Locale.ROOT, "발사장 라운드 %d 건조 %.2f 종류 %s%s", n, build, kindKey, goalsOwned ? "" : "(목표 막대는 Launch 혼자)"));
    }

    /** 이 라운드에 빌릴 4막 종류(판 씨앗, 안 쓴 것 먼저). */
    private String pickKind(int n) {
        Random r = trip.rng("launch" + n);
        List<String> free = new ArrayList<>();
        for (String k : BUILD_KINDS) if (!usedKinds.contains(k)) free.add(k);
        if (free.isEmpty()) free.addAll(List.of(BUILD_KINDS));
        String k = free.get(r.nextInt(free.size()));
        usedKinds.add(k);
        return k;
    }

    @Override
    public boolean roundDone(boolean done) {
        if (buildRound != game.wave || buildRound < 0) return done;
        if (!roundMet) return false;
        // 보스 라운드에서 보스가 살아 있을 때 몫을 채웠으면 물러나지 않았다(retreatUntil < 0): 끝은 보스 감독(무너짐 물결, 보상)이 정한다
        if (!goalsOwned) return retreatUntil >= 0 ? game.now() >= retreatUntil : done;
        return done;
    }

    @Override
    public void beginBreak(int n) {
        if (n > Trip.LAST_ROAD_ROUND && n < Trip.FINAL_ROUND) {
            // 몫을 못 채운 채 끝났으면(시험 건너뛰기, 특수 라운드) 그 라운드 몫까지 채움
            double want = Math.min(1, (n - Trip.LAST_ROAD_ROUND) * Tune.BUILD_PER_ROUND);
            if (build < want) build = want;
            showRocket();
            trip.log(String.format(Locale.ROOT, "발사장 라운드 %d 끝 건조 %.2f", n, build));
        }
        buildRound = -1;
        clearRings();
        for (int i = 0; i < 3; i++) setMachine(i, false);
        // 다음 라운드를 4막 특수로(그 클래스가 있을 때만)
        specialRound = -1;
        int next = n + 1;
        if (next > Trip.LAST_ROAD_ROUND && next < Trip.FINAL_ROUND && phase == Phase.NONE) {
            for (int sr : Tune.SPECIAL_ROUNDS) if (sr == next) openPadSpecial(next);
        }
    }

    /** 4막 특수 하나를 다음 라운드로(클래스가 있는 것, 안 쓴 것). Rounds 에 공개 메서드가 생길 때까지 그 시험 훅 길(특수 x 키 시작)을 쓴다. */
    private void openPadSpecial(int n) {
        Rounds r = trip.part(Rounds.class);
        if (r == null) return;
        for (String key : PAD_SPECIALS) {
            if (usedSpecials.contains(key)) continue;
            RoundKind.SpecialDef d = r.table().special(key);
            if (d == null || d.cls == null) continue;
            try {
                Class.forName(d.cls, false, Launch.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            String res = r.test(null, new String[] {"", "특수", "x", key, "시작"});
            usedSpecials.add(key);
            specialRound = n;
            specialKey = key;
            trip.log("발사장 라운드 " + n + " 를 특수 " + key + " 로: " + res);
            return;
        }
    }

    /** 특수 라운드가 건조를 뒤로 돌림(trip_special_b 약속: 사건 buildDrain, arg 몫). */
    public static final String EV_DRAIN = "buildDrain";

    @Override
    public void on(String event, Object arg) {
        if (EV_DRAIN.equals(event) && arg instanceof Number d && buildRound > 0 && !roundMet) damageBuild(d.doubleValue(), "buildDrain");
        // 발사장 진입 이동이 화면을 다 가린 동안 세운다(트럭이 도착하면 이미 서 있게)
        if (Travel.EV_HIDDEN.equals(event) && trip.stage() >= Trip.STAGES) ensureBuilt();
    }

    @Override
    public int breakTicks(int ticks) {
        if (phase == Phase.NONE) return ticks;
        // 엔딩 차례 동안은 쉬는 시간을 붙잡는다
        return (int) Math.max(ticks, game.stateTicks() + 100);
    }

    @Override
    public void tick() {
        if (game.state != Game.State.LOBBY && game.now() % 20 == 0 && phase.ordinal() < Phase.CREDITS.ordinal()) noteNames();
        if (!built && phase == Phase.NONE) {
            bossWatch();
            return;
        }
        long now = game.now();
        bossWatch();
        if (built) {
            lampsTick(now);
            if (buildRound > 0 && game.state == Game.State.WAVE && game.wave == buildRound) buildTick(now);
        }
        if (phase != Phase.NONE) endingTick(now);
    }

    @Override
    public void gameOver() {
        for (Player p : game.players.inPlay()) if (seats.containsKey(p.getUniqueId())) game.players.unride(p);
    }

    @Override
    public void reset() {
        for (Player p : Bukkit.getOnlinePlayers()) if (seats.containsKey(p.getUniqueId())) game.players.unride(p);
        if (storyMine) game.hud.storyStop();
        clearSite();
        resetState();
    }

    private void resetState() {
        build = 0;
        buildRound = -1;
        roundProg = 0;
        roundMet = false;
        retreatUntil = -1;
        usedKinds.clear();
        usedSpecials.clear();
        specialRound = -1;
        kindKey = null;
        phase = Phase.NONE;
        phaseAt = 0;
        bossDownAt = -1;
        fast = false;
        thereShown = false;
        storyMine = false;
        storyAt = -1;
        storyLen = 0;
        joined.clear();
        creditPages.clear();
        seats.clear();
        boardedCount = leftCount = 0;
    }

    @Override
    public void enabled(boolean on) {
        if (!on) reset();
    }

    // ------------------------------------------------------------------ 건조

    private void buildTick(long now) {
        if (roundMet) return;
        int on = 0;
        for (int i = 0; i < 3; i++) {
            Vector p = point(i);
            int ppl = 0, mob = 0;
            for (Player pl : game.players.standing()) if (horiz(pl.getLocation().toVector(), p) <= Tune.POINT_RING) ppl++;
            for (Gabe g : game.mobs.all()) if (!g.dead && !g.flying && horiz(g.pos, p) <= Tune.POINT_RING) mob++;
            people[i] = ppl;
            mobsIn[i] = mob;
            boolean active = ppl > 0 && ppl > mob;
            if (active) on++;
            setMachine(i, active);
        }
        roundProg += on / (3.0 * Tune.BUILD_SECONDS * 20) * Math.max(0, trip.mul("build"));
        double floor = (buildRound - Trip.LAST_ROAD_ROUND - 1) * Tune.BUILD_PER_ROUND;
        build = Math.min(1, floor + Math.min(1, roundProg) * Tune.BUILD_PER_ROUND);
        showRocket();
        for (int i = 0; i < 3; i++) rings[i] = ring(rings[i], point(i), Tune.POINT_RING, roundProg, i);
        Rounds r = goalsOwned ? trip.part(Rounds.class) : null;
        if (r != null) r.goals().custom("ti_goal_build", build, Math.round(build * 100) + "%");
        if (now % 10 == 0) {
            for (Player p : game.players.standing()) {
                for (int i = 0; i < 3; i++) if (horiz(p.getLocation().toVector(), point(i)) <= Tune.POINT_RING) game.hud.meter(p, "gl_build", build, 14);
            }
        }
        if (now % 20 == 0) stream();
        boolean timeUp = now - roundAt > Tune.BUILD_MAX_SECONDS * 20;
        if (roundProg >= 1 || timeUp) {
            roundProg = 1;
            build = Math.min(1, floor + Tune.BUILD_PER_ROUND);
            roundMet = true;
            showRocket();
            for (int i = 0; i < 3; i++) rings[i] = ring(rings[i], point(i), Tune.POINT_RING, 1, i);
            Props.soundAround(loc(pad(), 3), "trip.goal", Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.1f, 1.4f, 64, -1);
            if (r != null) r.goals().succeed();
            else if (!game.director.bossBlocking()) retreat();
            trip.log(String.format(Locale.ROOT, "발사장 라운드 %d 건조 끝 %.2f%s (%d초)", buildRound, build, timeUp ? " 시간 넘어 채움" : "", (now - roundAt) / 20));
        }
    }

    /** 목표를 마침(Rounds 가 없을 때): 대기열을 버리고 떼가 바깥으로 물러난 뒤 라운드 끝. */
    private void retreat() {
        retreatUntil = game.now() + Tune.RETREAT_TICKS;
        game.director.dropQueue();
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.sp.is(Role.BOSS)) continue;
            double a = Math.atan2(g.pos.getZ() - Plaza.CZ - 0.5, g.pos.getX() - Plaza.CX - 0.5);
            game.mobs.lure(g, new Vector(Plaza.CX + 0.5 + Math.cos(a) * 95, g.pos.getY(), Plaza.CZ + 0.5 + Math.sin(a) * 95), Tune.RETREAT_TICKS + 40);
        }
        if (Hud.hasPhrase("rd_done")) game.hud.notice("rd_done", 60);
    }

    /** 건조 중에는 떼가 끝없이 온다: 감독 대기열을 이번 계획의 몰려오는 종으로 채움. */
    private void stream() {
        Director dr = game.director;
        double want = Math.max(Tune.STREAM_MIN, dr.aliveGoal() * Tune.STREAM_SHARE);
        int q = dr.queued();
        if (q >= want) return;
        int n = (int) Math.min(Tune.STREAM_STEP, Math.ceil(want - q));
        Director.Plan p = dr.plan();
        List<Species> sps = new ArrayList<>();
        List<Integer> w = new ArrayList<>();
        int sum = 0;
        if (p != null) for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            Species sp = e.getKey();
            if (sp.spawn == Species.Spawn.PLACED || !sp.implemented() || sp.is(Role.BOSS) || e.getValue() <= 0) continue;
            sps.add(sp);
            w.add(e.getValue());
            sum += e.getValue();
        }
        if (sps.isEmpty()) {
            sps.add(Species.G01);
            w.add(1);
            sum = 1;
        }
        for (int i = 0; i < n; i++) {
            int x = rnd.nextInt(sum);
            for (int j = 0; j < sps.size(); j++) {
                x -= w.get(j);
                if (x < 0) {
                    dr.addQueue(sps.get(j), 1);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ 투광등 틱

    private void lampsTick(long now) {
        PadLights pl = PadLights.of(game);
        // B5 가 제 블록 등을 같은 자리에 세우는 동안은 내림(겹쳐 서지 않게), 쓰러지면 다시 세움
        Boss b = game.director.bosses.boss();
        boolean b5 = Tune.B5_OWN_LAMPS && b != null && b.sp == Species.B5 && !b.dying && !b.dead && !b.removed;
        if (b5 != lampsHidden) {
            lampsHidden = b5;
            if (b5) pl.lower(this);
            else pl.raise(this);
            trip.log("발사장 투광등 " + (b5 ? "내림(B5 가 제 등을 세움)" : "다시 세움"));
        }
        if (lampsHidden || !pl.raised()) return;
        // 꺼지는 등(f_lights): 켜진 등 하나를 부숨(밑동 발전기로 다시 켬)
        if (lightsAt >= 0 && now >= lightsAt && buildRound == game.wave && !roundMet) {
            lightsAt = now + Tune.LIGHTS_EVERY;
            List<PadLights.Lamp> on = new ArrayList<>();
            for (PadLights.Lamp l : pl.lamps()) if (l.lit()) on.add(l);
            if (on.size() > 1) {
                PadLights.Lamp l = on.get(rnd.nextInt(on.size()));
                pl.smash(l, "f_lights");
                if (Hud.hasPhrase("trip_lamp_out")) game.hud.notice("trip_lamp_out", 50);
                Props.soundAround(loc(l.base, 8), "trip.power_off", Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.1f, 1f, 64, -1);
            }
        }
    }

    // ------------------------------------------------------------------ 보스 보기(30 '저기다', 35 엔딩)

    private void bossWatch() {
        Boss b = game.director.bosses.boss();
        if (b != null && b.sp == Species.B4 && (b.dying || b.dead) && game.wave == Trip.LAST_ROAD_ROUND && !thereShown) {
            thereShown = true;
            if (Hud.hasPhrase("trip_there")) Bukkit.getScheduler().runTaskLater(game.plugin, () -> game.hud.notice("trip_there", 80), 60);
        }
        if (phase == Phase.NONE && b != null && b.sp == Species.B5 && (b.dying || b.dead) && game.state != Game.State.OVER && game.state != Game.State.LOBBY) {
            bossDownAt = game.now();
            fast = false;
            enter(Phase.BOSS);
            trip.log("B5 쓰러짐: 발사 차례 시작");
        }
    }

    // ------------------------------------------------------------------ 엔딩

    /** 시험: 지금 발사 차례를 연다(B5 를 잡은 것처럼, 보스 연출 없이 바로 탑승). 웨이브 중이면 떼를 치워 쉬는 시간으로. */
    public String launchNow(boolean quick) {
        if (game.state == Game.State.LOBBY || game.state == Game.State.OVER) return "판 중이 아님";
        ensureBuilt();
        build = 1;
        fast = quick;
        if (game.state == Game.State.WAVE) {
            game.director.dropQueue();
            for (Gabe g : new ArrayList<>(game.mobs.all())) {
                if (g.dead || g.sp.is(Role.BOSS)) continue;
                g.noReward = true;
                g.reward = 0;
                g.die(new Damage(g.hp + 1, DamageType.OTHER, null, g.pos.clone(), null, "test", 0));
            }
        }
        enter(Phase.BOARD);
        return "[발사장] 발사 차례 시작" + (quick ? "(빨리)" : "") + " state=" + game.state;
    }

    private int boardTicks() {
        return fast ? Tune.FAST_BOARD : Tune.BOARD_TICKS;
    }

    private int countTicks() {
        return fast ? Tune.FAST_COUNT : Tune.COUNT_TICKS;
    }

    private void enter(Phase p) {
        phase = p;
        phaseAt = game.now();
        Theme theme = trip.part(Theme.class);
        switch (p) {
            case BOSS -> {
            }
            case BOARD -> {
                ensureBuilt();
                build = 1;
                showRocket();
                if (theme != null) theme.morning(0, 200);
                game.director.bosses.fog(1);
                boardRing = ring(boardRing, boardSpot(), Tune.BOARD_R, 1, -1);
                for (Player pl : game.players.inPlay()) {
                    Props.sound(pl, "gabe.b5.dawn", Sound.ENTITY_PARROT_AMBIENT, 1f, 0.1f, 1f);
                }
                // 엔딩 곡: B5 를 잡은 길은 Music 이 쓰러진 뒤 100틱에 저절로 튼다. 시험 길(보스 없이 바로 탑승)만 여기서
                if (bossDownAt < 0) game.music.ending();
                String k = Hud.hasPhrase("trip_board") ? "trip_board" : Hud.hasPhrase("rd_f_board") ? "rd_f_board" : null;
                if (k != null) game.hud.notice(k, 80);
                game.hud.point(null, boardSpot().clone().setY(Plaza.FLOOR_Y + 1), 120);
                Props.soundAround(loc(pad(), 6), "trip.siren_far", Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0f, 0.5f, 200, -1);
                trip.log("탑승 " + boardTicks() + "틱");
            }
            case COUNT -> {
                // 서 있는 사람은 모두 탄다(못 내림), 죽은 채인 사람은 땅에 남는다
                for (Player pl : game.players.standing()) board(pl);
                game.director.bosses.fog(1);
                Props.soundAround(loc(pad(), 6), "trip.siren", Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0f, 0.5f, 200, -1);
                trip.log("초읽기 탄 사람 " + seats.size());
            }
            case IGNITE -> ignite();
            case RISE -> {
                Props.soundAround(loc(pad(), 4), "trip.rocket_launch", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0f, 0.5f, 256, -1);
                if (leftBehind() > 0) leftHorde();
            }
            case AFTER -> {
                boardedCount = 0;
                leftCount = 0;
                for (Player pl : game.players.inPlay()) {
                    boolean on = seats.containsKey(pl.getUniqueId());
                    if (on) boardedCount++;
                    else leftCount++;
                    String k = on ? "trip_gone" : "trip_left_behind";
                    if (Hud.hasPhrase(k)) game.hud.notice(pl, k, 70);
                }
                if (leftCount == 0 && Hud.hasPhrase("trip_all_aboard")) Bukkit.getScheduler().runTaskLater(game.plugin, () -> game.hud.notice("trip_all_aboard", 60), 30);
                if (theme != null) theme.morning(1, 120);
                trip.log("발사 탄 사람 " + boardedCount + " 남은 사람 " + leftCount);
                game.records.noteEscape(boardedCount, leftCount);
            }
            case LINES -> {
                Travel tr = trip.part(Travel.class);
                if (tr != null) tr.black(game.players.inPlay(), Tune.FADE_TICKS, 0, 0, "");
                storyAt = game.now() + Tune.FADE_TICKS;
            }
            case CREDITS -> {
                buildCredits();
                game.music.cue("credits", creditTicks() + Tune.END_HOLD);
                trip.log("크레딧 이름 " + joined.size() + " 쪽 " + creditPages.size() + " " + creditTicks() + "틱");
            }
            case DONE -> {
                if (Tune.END_HOLD > 0) game.hud.blackout(Tune.END_HOLD + 5);
                Bukkit.getScheduler().runTaskLater(game.plugin, this::finishLaunch, Math.max(1, Tune.END_HOLD));
            }
            default -> {
            }
        }
    }

    private void endingTick(long now) {
        long t = now - phaseAt;
        switch (phase) {
            case BOSS -> {
                if (t >= Tune.BOSS_WAIT) enter(Phase.BOARD);
            }
            case BOARD -> {
                // 탑승 원에 들어선 사람은 바로 탄다
                Vector spot = boardSpot();
                for (Player pl : game.players.standing()) {
                    if (!seats.containsKey(pl.getUniqueId()) && horiz(pl.getLocation().toVector(), spot) <= Tune.BOARD_R) board(pl);
                }
                seatTick(0);
                if (t % 40 == 0) game.director.bosses.fog(1);
                if (t >= boardTicks()) enter(Phase.COUNT);
            }
            case COUNT -> {
                seatTick(0);
                long left = countTicks() - t;
                if (t % 20 == 0 && left > 0) {
                    int n = (int) Math.ceil(left / 20.0);
                    countdown(n);
                }
                if (t >= countTicks()) enter(Phase.IGNITE);
            }
            case IGNITE -> {
                seatTick(0);
                if (t % 2 == 0) exhaust(1.0);
                if (t >= Tune.IGNITE_TICKS) enter(Phase.RISE);
            }
            case RISE -> {
                double f = Math.min(1, (double) t / Tune.RISE_TICKS);
                lift = Tune.RISE_HEIGHT * f * f;
                if (t % 2 == 0 && rocket != null && rocket.isValid()) {
                    rocket.setTeleportDuration(2);
                    rocket.teleport(loc(pad(), padTop() + lift + 0.01));
                }
                if (t % 3 == 0 && lift < 40) exhaust(Math.max(0.2, 1 - lift / 40));
                seatTick(lift);
                if (t >= Tune.RISE_TICKS) enter(Phase.AFTER);
            }
            case AFTER -> {
                seatTick(lift);
                if (t >= Tune.AFTER_TICKS) enter(Phase.LINES);
            }
            case CREDITS -> {
                seatTick(lift);
                if (t >= creditTicks()) enter(Phase.DONE);
            }
            case LINES -> {
                seatTick(lift);
                if (storyAt >= 0 && now >= storyAt && !storyMine) {
                    storyMine = true;
                    storyLen = game.hud.story(BossDirector.ENDING_LINES, Tune.LINE_TICKS, Tune.LINE_HOLD);
                    trip.log("엔딩 다섯 줄 " + (storyLen > 0 ? "HUD " + storyLen + "틱" : "HUD 글귀 없음: 이동 화면 글꼴(제목 글)로"));
                }
                // HUD 글귀가 없으면(make_hud 전) 이동 화면의 검은 글리프 + 미리 그린 줄(route.py 가 같이 굽는 ending_1..5)로 한 줄씩
                if (storyMine && storyLen == 0 && now >= storyAt && (now - storyAt) % Tune.LINE_TICKS == 0) {
                    int i = (int) ((now - storyAt) / Tune.LINE_TICKS);
                    Travel tr = trip.part(Travel.class);
                    if (tr != null && i < BossDirector.ENDING_LINES.size()) {
                        boolean last = i == BossDirector.ENDING_LINES.size() - 1;
                        tr.black(game.players.inPlay(), 0, last ? Tune.LINE_TICKS + Tune.LINE_HOLD : Tune.LINE_TICKS, 0, BossDirector.ENDING_LINES.get(i));
                    }
                }
                if (storyAt >= 0 && now >= storyAt + (long) BossDirector.ENDING_LINES.size() * Tune.LINE_TICKS + Tune.LINE_HOLD) enter(credits() ? Phase.CREDITS
                    : Phase.DONE);
                else if (storyLen > 0 && now > storyAt + 2 && !game.hud.storyActive()) game.hud.blackout(5);
            }
            default -> {
            }
        }
    }

    /** 초읽기 큰 숫자 n(10..1): HUD 값 launch.count + (HUD 가 그리기 전까지) 바닐라 제목 글, 삑 소리. */
    private void countdown(int n) {
        for (Player pl : game.players.inPlay()) {
            Props.sound(pl, "trip.countdown", Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0f, n <= 3 ? 1.6f : 1.2f);
            if (Tune.TITLE_COUNT) {
                pl.showTitle(Title.title(Component.text(Integer.toString(n), n <= 3 ? NamedTextColor.GOLD : NamedTextColor.WHITE), Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(850), Duration.ofMillis(150))));
            }
        }
    }

    /** 사람 하나를 우주선 좌석에(서 있는 사람만, 못 내림). */
    private boolean board(Player p) {
        PState s = game.players.get(p);
        if (s == null || s.life != Life.ALIVE) return false;
        if (seats.containsKey(p.getUniqueId())) return true;
        int idx = seats.size();
        if (!game.players.ride(p, seatAt(idx, lift), 20)) return false;
        seats.put(p.getUniqueId(), idx);
        if (Hud.hasPhrase("trip_boarded")) game.hud.notice(p, "trip_boarded", 40);
        Props.sound(p, "trip.revived", Sound.BLOCK_IRON_DOOR_CLOSE, 0.8f, 0.1f, 1f);
        return true;
    }

    private Vector seatAt(int idx, double up) {
        Vector c = pad();
        int level = idx / Tune.SEAT_RING, k = idx % Tune.SEAT_RING;
        double a = k * Math.PI * 2 / Tune.SEAT_RING + level * 0.6;
        double y = Plaza.FLOOR_Y + padTop() + up + Tune.SEAT_Y0 + level * Tune.SEAT_DY;
        return new Vector(c.getX() + Math.cos(a) * Tune.SEAT_R, y, c.getZ() + Math.sin(a) * Tune.SEAT_R);
    }

    /** 탄 사람 좌석을 우주선과 같이(매 틱 늘림). 나가거나 쓰러진 사람은 빠짐. */
    private void seatTick(double up) {
        for (Map.Entry<UUID, Integer> e : seats.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) continue;
            game.players.ride(p, seatAt(e.getValue(), up), 20);
        }
    }

    private int leftBehind() {
        int n = 0;
        for (Player p : game.players.inPlay()) if (!seats.containsKey(p.getUniqueId())) n++;
        return n;
    }

    /** 점화: 발사대 둘레 불길과 폭발, 둘레 게이브는 불에 쓰러짐, 큰 흔들림. */
    private void ignite() {
        Vector c = pad();
        Props.soundAround(loc(c, 2), "trip.rocket_ignite", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0f, 0.6f, 160, -1);
        if (Hud.hasPhrase("trip_ignite")) game.hud.notice("trip_ignite", 40);
        game.horizon.shake(1.2, 50);
        // 점화부터 엔딩 검은 화면까지 지평선 그림자 숨김
        game.horizon.hide(Tune.IGNITE_TICKS + Tune.RISE_TICKS + Tune.AFTER_TICKS + Tune.FADE_TICKS + 200);
        for (int i = 0; i < 4; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 2 + rnd.nextDouble() * 4;
            game.shotFx.explosion(loc(c.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r)), 0.5));
        }
        for (int i = 0; i < 18; i++) {
            double a = i * Math.PI * 2 / 18 + rnd.nextDouble() * 0.2, r = 4 + rnd.nextDouble() * (Tune.FIRE_R - 4);
            game.shotFx.flame(loc(c.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r)), 0.6), 2.2f + (float) rnd.nextDouble(), 40,
                i < 6 ? ShotFx.KEY : ShotFx.NORMAL);
        }
        int burnt = 0;
        for (Gabe g : new ArrayList<>(game.mobs.all())) {
            if (g.dead || g.sp.is(Role.BOSS) || horiz(g.pos, c) > Tune.FIRE_R) continue;
            g.noReward = true;
            g.reward = 0;
            Vector out = g.pos.clone().subtract(c).setY(0);
            if (out.lengthSquared() < 1e-6) out = new Vector(1, 0, 0);
            g.die(new Damage(g.hp + 1, DamageType.FIRE, null, g.pos.clone(), out.normalize().add(new Vector(0, 0.5, 0)), "trip_ignite", 0));
            burnt++;
        }
        trip.log("점화: 불에 쓰러진 게이브 " + burnt);
    }

    /** 분사구 불꽃(우주선 밑). */
    private void exhaust(double k) {
        Vector c = pad();
        Location at = loc(c, padTop() + lift + 0.3);
        game.shotFx.flame(at.clone().add(rnd.nextGaussian() * 0.6, 0, rnd.nextGaussian() * 0.6), (float) (2.4 * k), 16, ShotFx.KEY);
        if (lift < 4) game.shotFx.flame(at.clone().add(rnd.nextGaussian() * 2.5, -0.2, rnd.nextGaussian() * 2.5), (float) (1.8 * k), 20, ShotFx.NORMAL);
    }

    /** 남은 사람 화면: 우주선이 떠난 자리로 떼가 밀려든다. */
    private void leftHorde() {
        Vector c = pad();
        int made = 0;
        for (int i = 0; i < Tune.LEFT_HORDE; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            Vector at = new Vector(Plaza.CX + 0.5 + Math.cos(a) * 46, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * 46);
            Gabe g = game.director.spawnLike(Species.G01, at);
            if (g == null) continue;
            g.noReward = true;
            game.mobs.lure(g, c.clone().add(new Vector(rnd.nextGaussian() * 4, 0, rnd.nextGaussian() * 4)), 400);
            made++;
        }
        trip.log("남은 사람에게 밀려드는 떼 " + made);
    }

    /** 설정 trip.credits(기본 켬, 2026-11-12 사용자 결정). */
    private boolean credits() {
        return game.plugin.getConfig().getBoolean("trip.credits", true);
    }

    /** 크레딧 쪽을 짠다: 이름(들어온 차례)을 줄마다 하나(많으면 CREDIT_PER_ROW), 쪽마다 CREDIT_ROWS 줄. 이름이 없으면 쪽 없음. */
    private void buildCredits() {
        creditPages.clear();
        noteNames();
        List<String> names = new ArrayList<>(joined);
        if (names.isEmpty()) return;
        int per = names.size() > Tune.CREDIT_ROWS * 4 ? Math.max(1, Tune.CREDIT_PER_ROW) : 1;
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < names.size(); i += per) rows.add(String.join("    ", names.subList(i, Math.min(names.size(), i + per))));
        for (int i = 0; i < rows.size(); i += Tune.CREDIT_ROWS) creditPages.add(new ArrayList<>(rows.subList(i, Math.min(rows.size(), i + Tune.CREDIT_ROWS))));
    }

    /** 크레딧 길이(앞 검정 + 쪽 x 쪽 틱 + 끝 검정). */
    private int creditTicks() {
        return Tune.CREDIT_HEAD + creditPages.size() * Tune.CREDIT_PAGE + Tune.CREDIT_TAIL;
    }

    /** 지금 보일 크레딧 쪽(앞뒤 검정이면 null). */
    private List<String> creditPage() {
        if (phase != Phase.CREDITS) return null;
        long t = game.now() - phaseAt - Tune.CREDIT_HEAD;
        if (t < 0) return null;
        int i = (int) (t / Tune.CREDIT_PAGE);
        // 쪽 사이 6틱은 비움(바뀌는 것이 보이게)
        if (i >= creditPages.size() || t % Tune.CREDIT_PAGE >= Tune.CREDIT_PAGE - 6) return null;
        return creditPages.get(i);
    }

    /** 다섯 줄 뒤: 판 끝(승리) 또는 설정 trip.afterLaunch 면 36라운드로. */
    private void finishLaunch() {
        if (phase != Phase.DONE) return;
        trip.log(String.format(Locale.ROOT, "발사 끝 탄 %d 남은 %d 웨이브 %d", boardedCount, leftCount, game.wave));
        if (trip.afterLaunch) {
            for (Player p : game.players.inPlay()) if (seats.containsKey(p.getUniqueId())) game.players.unride(p);
            seats.clear();
            game.hud.storyStop();
            storyMine = false;
            phase = Phase.NONE;
            lift = 0;
            showRocket();
            if (rocket != null && rocket.isValid()) rocket.teleport(loc(pad(), padTop() + 0.01));
            return;
        }
        // 판 끝(허브가 부품을 도는 틱 밖, 스케줄러에서). 판 끝 연출(떼가 원을 메움)과 '판 끝' 검은 화면은 없이 바로 대기실로
        if (game.state == Game.State.LOBBY) return;
        trip.endCause(Trip.END_LAUNCH);
        game.gameOver();
        game.reset();
    }

    // ------------------------------------------------------------------ HUD, 물음, 상태

    @Override
    public void hud(Player p, Map<String, Object> out) {
        if (!built) return;
        out.put("launch.on", Boolean.TRUE);
        out.put("launch.rocket", Boolean.TRUE);
        out.put("launch.build", build);
        if (phase == Phase.CREDITS || phase == Phase.DONE) {
            // 검은 화면 위 크레딧(HUD 가 credits.lines 를 가운데 칸 넷에 그림)
            out.put("screen.black", Boolean.TRUE);
            List<String> page = creditPage();
            if (page != null) out.put("credits.lines", page);
        }
        if (phase != Phase.NONE) {
            out.put("launch.phase", phase.name().toLowerCase(Locale.ROOT));
            if (seats.containsKey(p.getUniqueId())) out.put("launch.boarded", Boolean.TRUE);
        }
        long t = game.now() - phaseAt;
        if (phase == Phase.COUNT) {
            long left = countTicks() - t;
            if (left > 0) out.put("launch.count", (int) Math.ceil(left / 20.0));
        }
        // 탑승 마감 막대(쉬는 시간이라 Rounds 가 목표 값을 안 내놓음): 줄어드는 시간
        if (phase == Phase.BOARD) {
            int all = boardTicks();
            out.put("goal.kind", "board");
            out.put("goal.frac", Math.max(0, 1 - (double) t / all));
            out.put("goal.text", Integer.toString((int) Math.ceil(Math.max(0, all - t) / 20.0)));
            out.put("goal.fill", Boolean.FALSE);
            out.put("goal.icon", "ti_goal_survive");
        } else if (buildRound > 0 && buildRound == game.wave && !goalsOwned) {
            out.put("goal.kind", "build");
            out.put("goal.frac", build);
            out.put("goal.text", Math.round(build * 100) + "%");
            out.put("goal.fill", Boolean.TRUE);
            out.put("goal.icon", "ti_goal_build");
        }
    }

    @Override
    public Object ask(String key) {
        return switch (key) {
            case "disp" -> displays();
            case "launchBuild" -> built ? (Object) build : null;
            case "launchPhase" -> phase == Phase.NONE ? null : phase.name();
            default -> null;
        };
    }

    int displays() {
        int n = 0;
        for (ItemDisplay d : new ItemDisplay[] {rocket, padBase, padTower, boardRing}) if (d != null && d.isValid()) n++;
        for (ItemDisplay d : machines) if (d != null && d.isValid()) n++;
        for (ItemDisplay d : rings) if (d != null && d.isValid()) n++;
        PadLights pl = PadLights.peek(game);
        if (pl != null && pl.owner() == this) n += pl.displays();
        return n;
    }

    private int lampsLit() {
        PadLights pl = PadLights.peek(game);
        return pl == null ? 0 : pl.litCount();
    }

    @Override
    public String status() {
        if (!built && phase == Phase.NONE) return null;
        StringBuilder sb = new StringBuilder("launch=").append(phase.name().toLowerCase(Locale.ROOT));
        sb.append(String.format(Locale.ROOT, " build=%.2f", build)).append(" rocket=").append(rocketShown);
        if (buildRound > 0) {
            sb.append(" kind=").append(kindKey).append(String.format(Locale.ROOT, " rprog=%.2f", roundProg)).append(roundMet ? ":met" : "").append(" pts=");
            for (int i = 0; i < 3; i++) sb.append(people[i]).append('v').append(mobsIn[i]).append(i < 2 ? "," : "");
        }
        PadLights pl = PadLights.peek(game);
        sb.append(" lamps=").append(lampsLit()).append(lampsHidden ? "(b5)" : "").append(' ').append(pl == null ? "pad=-" : pl.status());
        if (phase != Phase.NONE) sb.append(" t=").append(game.now() - phaseAt).append(" boarded=").append(seats.size()).append(String.format(Locale.ROOT, " lift=%.1f", lift));
        sb.append(" ldisp=").append(displays());
        return sb.toString();
    }

    // ------------------------------------------------------------------ 시험 훅

    @Override
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        String act = a[1];
        Player who = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null;
        if (who == null) who = p;
        switch (act) {
            case "발사장" -> {
                String sub = a.length > 3 ? a[3] : "상태";
                switch (sub) {
                    case "상태" -> {
                        return "[발사장] " + status() + " pad=" + fmt(pad()) + " pts=" + fmt(point(0)) + "|" + fmt(point(1)) + "|" + fmt(point(2));
                    }
                    case "세우기" -> {
                        ensureBuilt();
                        return "[발사장] 섬 " + status();
                    }
                    case "치우기" -> {
                        clearSite();
                        return "[발사장] 치움";
                    }
                    case "점", "등", "탑승원" -> {
                        if (who == null) return "사람 없음";
                        int i = a.length > 4 ? Integer.parseInt(a[4]) : 0;
                        Vector v = sub.equals("점") ? point(i) : sub.equals("등") ? PadLights.of(game).lamp(i).base.clone() : boardSpot();
                        Location l = loc(v, 0.05);
                        l.setYaw(who.getLocation().getYaw());
                        l.setPitch(who.getLocation().getPitch());
                        who.teleport(l);
                        return "[발사장] " + who.getName() + " → " + sub + " " + i + " " + fmt(v);
                    }
                    default -> {
                        return "발사장 x <상태|세우기|치우기|점 i|등 i|탑승원>";
                    }
                }
            }
            case "건조" -> {
                if (a.length < 4) return "[발사장] build=" + build;
                double v = Math.max(0, Math.min(1, Double.parseDouble(a[3])));
                ensureBuilt();
                build = v;
                if (buildRound > 0) {
                    double floor = (buildRound - Trip.LAST_ROAD_ROUND - 1) * Tune.BUILD_PER_ROUND;
                    roundProg = Math.max(0, Math.min(1, (v - floor) / Tune.BUILD_PER_ROUND));
                }
                showRocket();
                return "[발사장] " + status();
            }
            case "발사" -> {
                String sub = a.length > 3 ? a[3] : "";
                if (sub.equals("끝")) {
                    for (Player pl : game.players.inPlay()) if (seats.containsKey(pl.getUniqueId())) game.players.unride(pl);
                    seats.clear();
                    phase = Phase.NONE;
                    lift = 0;
                    if (storyMine) game.hud.storyStop();
                    storyMine = false;
                    showRocket();
                    if (rocket != null) rocket.teleport(loc(pad(), padTop() + 0.01));
                    return "[발사장] 발사 차례 끔";
                }
                if (sub.equals("상태")) return "[발사장] " + status();
                if (sub.equals("이름")) {
                    // 시험: 크레딧 쪽 넘김을 보려고 실제 작품(에일리언 연작) 인물 이름을 이 판 참가자 목록에 더함(메모리에만)
                    int n = a.length > 4 ? Integer.parseInt(a[4]) : 30;
                    for (int i = 0; i < Math.min(n, TEST_NAMES.length); i++) joined.add(TEST_NAMES[i]);
                    return "[발사장] 크레딧 이름 " + joined.size();
                }
                if (sub.equals("다음")) {
                    if (phase == Phase.NONE || phase == Phase.DONE) return "발사 차례가 아님";
                    enter(Phase.values()[phase.ordinal() + 1]);
                    return "[발사장] " + status();
                }
                return launchNow(sub.equals("빨리"));
            }
            case "투광등" -> {
                PadLights pl = PadLights.of(game);
                if (!pl.active()) return "투광등 없음(발사장 x 세우기)";
                boolean on = a.length > 4 && a[4].equals("켬");
                String which = a.length > 3 ? a[3] : "모두";
                for (PadLights.Lamp l : pl.lamps()) {
                    if (!which.equals("모두") && !which.equals(Integer.toString(l.index))) continue;
                    if (on) pl.relight(l);
                    else pl.smash(l, "test");
                }
                return "[발사장] " + pl.status() + " lit(가운데)=" + game.mobs.lit(pad()) + " dark(점0)=" + game.mobs.dark(point(0));
            }
            case "탑승" -> {
                if (who == null) return "사람 없음";
                return "[발사장] 탑승 " + who.getName() + " " + board(who) + " seats=" + seats.size();
            }
            case "발사값" -> {
                if (a.length < 4) return "[발사장] " + tunes();
                if (a.length < 5) {
                    String v = tune(a[3], null);
                    return v == null ? "모르는 이름: " + a[3] : "[발사장] " + a[3] + "=" + v;
                }
                String v = tune(a[3], a[4]);
                return v == null ? "모르는 이름이거나 못 읽는 값: " + a[3] + " " + a[4] : "[발사장] " + a[3] + "=" + v;
            }
            default -> {
                return null;
            }
        }
    }

    private static String fmt(Vector v) {
        return String.format(Locale.ROOT, "(%.1f,%.1f)", v.getX(), v.getZ());
    }

    static double horiz(Vector a, Vector b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 숫자 표 값 하나를 읽거나(value == null) 바꿈. 모르는 이름, 못 읽는 값이면 null. */
    public static String tune(String name, String value) {
        try {
            Field f = Tune.class.getField(name);
            if (!Modifier.isStatic(f.getModifiers())) return null;
            Class<?> t = f.getType();
            if (t == double.class) {
                if (value != null) f.setDouble(null, Double.parseDouble(value));
                return String.valueOf(f.getDouble(null));
            }
            if (t == int.class) {
                if (value != null) f.setInt(null, Integer.parseInt(value));
                return String.valueOf(f.getInt(null));
            }
            if (t == boolean.class) {
                if (value != null) f.setBoolean(null, Boolean.parseBoolean(value));
                return String.valueOf(f.getBoolean(null));
            }
            if (t == int[].class) {
                if (value != null) {
                    String[] ps = value.isEmpty() ? new String[0] : value.split(",");
                    int[] v = new int[ps.length];
                    for (int i = 0; i < v.length; i++) v[i] = Integer.parseInt(ps[i].trim());
                    f.set(null, v);
                }
                return java.util.Arrays.toString((int[]) f.get(null));
            }
            return null;
        } catch (ReflectiveOperationException | NumberFormatException e) {
            return null;
        }
    }

    private static String tunes() {
        StringBuilder sb = new StringBuilder();
        for (Field f : Tune.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(f.getName()).append('=').append(tune(f.getName(), null));
        }
        return sb.toString();
    }
}
