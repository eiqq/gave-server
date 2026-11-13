package kr.gave.trip;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.mob.SpeciesBan;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * 원정 라운드 종류(묶음 7, 원정.md 5절): 라운드마다 길 부품이 정한 풀 글({@code trip.ask("roundSpec")}: pool:plain, pool:hard, special, special:chase,
 * event, boss, launch ...)에서 종류 하나를 뽑아 감독 계획에 축 값을 입히고(종 갈래, 수와 박자, 나오는 방향, 날씨와 시야), 목표({@link Goals})와 장치
 * ({@link RoundFx})를 돌리고, 라운드 끝에 보상을 준다. 표는 jar 의 trip/rounds_final.json({@link RoundKind}).
 *
 * <p>감독(Director, 다른 작업자 파일)을 고치지 않는다. beforeRound 에서 쉬는 시간에 짠 다음 계획(director.nextPlan())을 고치고(종 몫, 총량, 안개, 밤,
 * 소리 없음, 땅 갈라짐, 물웅덩이 눈, 쏠림), 감독이 그 계획으로 웨이브를 연다. 그래서 mul("units") 는 답하지 않는다(답하면 감독이 계획을 다시 짜서
 * 고친 것이 사라짐). 쉬는 시간이 없던 라운드(1라운드, /gave 웨이브 n 건너뛰기)는 살아 있는 값(안개 bossFog, 배율, 나오는 자리, 목표, 장치)만 건다.
 * 나오는 방향은 감독이 막 낸 개체를 첫 틱에 옮긴다(Mobs.relocate, 안개 속에서 보간 없이). 끝없이 오는 라운드는 감독 대기열을 addQueue 로 채우고,
 * 고요와 파도는 delayAnnounce 로 내보내기를 멈춘다.
 *
 * <p>보스 라운드(10, 20, 30, 35)와 발사장(31~34: Launch 부품이 있으면)은 비켜 준다(아무것도 안 바꿈). 특수 라운드(roundSpec special:*)는 이름으로 찾은
 * {@link SpecialRound} 에게 넘기고(없으면 보통 라운드로 떨어짐), 그 동안의 TripPart 부름을 그것에 돌린다.
 *
 * <p>다른 부품이 읽는 것: ask roundKind(키), roundComp, roundTempo, roundDir, roundEnv, goal(목표 키), roundName(글귀 키), shopLocked(Boolean),
 * disp. mul hp, speed, dmg, reward, truckShare, truckDmg, price.ammo, price.auto_ammo, revive. hud 값 round.*, goal.*(아래 hud). 숫자는 {@link Tune}.
 */
public final class Rounds implements TripPart {

    /** 숫자 표 한곳(라운드 종류 장치와 목표). 처음 값이고 파워커브와 게임 안 시험이 맞춘다. */
    public static final class Tune {
        /** 라운드 이름 알림 띠(틱), 한 줄(틱) */
        public static int BANNER_TICKS = 0, LINE_TICKS = 50;
        /** 목표를 마친 뒤 떼가 물러나는 틱, 목표가 끝나지 않는 라운드의 안전 끝(초) */
        public static int RETREAT_TICKS = 60, GOAL_MAX_SECONDS = 300;
        /** 시간 안 처치 보상 배율, 사냥 금빛 표적 보상 배율 */
        public static double TIMED_MUL = 2.0, HUNT_GOLD_REWARD = 4.0;
        /** 자리 지키기: 원 반지름, 원이 서는 반지름 범위, 원마다 채울 초, 게이브가 많을 때 틱마다 빠지는 몫(틱) */
        public static double HOLD_R = 4.5, HOLD_RMIN = 18, HOLD_RMAX = 32, HOLD_SECONDS = 30, HOLD_DECAY = 0.5;
        /** 쓰러짐 고리 셰이더 판(fx_flat) 배율 1 의 고리 반지름(블록): 원 반지름 / 이 값 = 배율 */
        public static double RING_BASE_R = 0.4;
        /** 건조(발사장 부품이 없을 때): 지점이 모두 돌 때 막대가 차는 초 */
        public static double BUILD_SECONDS = 90;
        /** 줍기: 놓이는 반지름, 집는 거리, 트럭에 싣는 거리(발자국에서), 든 사람 걷기 느려짐(%) */
        public static double COLLECT_RMIN = 22, COLLECT_RMAX = 42, PICK_R = 2.4, DELIVER_R = 3.5, CARRY_SLOW = 30;
        /** 시동: 트럭 앞 보닛 둘레, 트럭이 맞을 때 깎이는 몫 */
        public static double HOOD_R = 4.0, START_KNOCK = 0.06;
        /** 끝없이(trickle): 처음 대기열 배율, 대기열을 이만큼(동시 수 목표 몫, 아래끝) 남게 채움, 한 번에 더하는 수 */
        public static double TRICKLE_START = 0.5, STREAM_SHARE = 0.5, STREAM_MIN = 6;
        public static int STREAM_STEP = 6;
        /** 마지막에 몰린다: 몇 몫을 낸 뒤 쉬는 틱, 그때 더하는 몫 */
        public static int FINALE_HOLD = 160;
        public static double FINALE_EXTRA = 0.3;
        /** 점점 빨리: 이 틱에 걸쳐 빠르기 오름이 다 참 */
        public static int RAMP_TICKS = 1800;
        /** 방향: 코앞 반지름, 지평선 반지름, 사람 곁(7~11), 트럭 둘레(6~12), 도는 쏠림 주기, 쏠림 호(도) */
        public static double NEAR_R = 28, FAR_R = 90, UNDER_R0 = 7, UNDER_R1 = 11, TRUCK_R0 = 6, TRUCK_R1 = 12, ARC_DEG = 25;
        public static int SPIN_EVERY = 400, RISE_HOLD = 30;
        /** 어둡게: 더 어두운 밤, 재 */
        public static double DARK_DIM = 0.5, ASH_DIM = 0.25;
        /** 깜빡이는 등: 주기, 꺼진 틱, 어둡게 몫 */
        public static int FLICKER_EVERY = 80, FLICKER_OFF = 20;
        public static double FLICKER_DIM = 0.9;
        /** 번개: 사이(틱), 예고(틱), 반지름, 게이브 피해(최대 체력 몫), 사람 피해, 번쩍여 맑은 틱 */
        public static int BOLT_MIN = 140, BOLT_MAX = 240, STRIKE_WARN = 30, FLASH = 40;
        public static double STRIKE_R = 3.5, STRIKE_MOB = 0.6, STRIKE_DMG = 6;
        /** 바람: 바람 쪽 게이브가 받는 피해(이 거리 넘게 떨어진 것), 빠르기 */
        public static double WIND_DMG = 0.6, WIND_NEAR = 12, WIND_SPEED = 1.2;
        /** 비: 젖은 벌 물 높이, 차오르는 물 끝 높이와 느려짐, 검은 물 높이 느려짐 초당 피해 */
        public static double RAIN_FLOOD = 0.35, TIDE_MAX = 0.85, TIDE_SLOW = 25, BLACKWATER_FLOOD = 0.6, BLACKWATER_SLOW = 30, BLACKWATER_DMG = 1.5;
        /** 진창 느려짐(%), 붙은 것: 잠복된 사람 둘레 반지름과 게이브 빠르기 */
        public static double MIRE_SLOW = 15, LATENT_R = 10, LATENT_SPEED = 1.3;
        /** 사슬: 간격, 걸린 틱, 거리, 당기는 세기 */
        public static int HOOK_EVERY = 120, HOOK_TICKS = 50;
        public static double HOOK_R = 14, HOOK_PULL = 0.35;
        /** 쪼개짐(조각 체력 몫), 터짐(반지름, 피해) */
        public static double SPLIT_HP = 0.45, BURST_R = 2.8, BURST_DMG = 5;
        /** 알: 수, 체력(감독 체력 배율 제곱근을 곱함), 맞는 반지름, 그림 배율, 깨어나는 틱(+퍼짐), 깨어날 때 떼 수 */
        public static int EGGS = 6, EGG_HATCH = 700, EGG_SPREAD = 200, EGG_BROOD = 5;
        public static double EGG_HP = 60, EGG_R = 0.9;
        public static float EGG_SCALE = 0.8f;
        /** 미끼: 수, 밟는 거리, 피해, 진짜 상자가 떨어지는 틱, 덮치는 수 */
        public static int BAITS = 5, BAIT_REAL_AT = 200, AMBUSH_N = 3;
        public static double BAIT_R = 2.2, BAIT_DMG = 4;
        /** 가짜 등불: 수, 다가가는 거리, 피해 */
        public static int LAMPS = 3;
        public static double LAMP_R = 3.5, LAMP_DMG = 3;
        /** 묶인 보급: 끄는 수, 처음 반지름, 끄는 빠르기(블록/틱, 넷 다 살면), 잃는 반지름 */
        public static int HAULERS = 4;
        public static double HAUL_START = 26, HAUL_SPEED = 0.03, HAUL_ESCAPE = 60;
        /** 불씨 땅: 처음 수, 늘어나는 틱, 가장 많은 수, 처음 반지름, 가장 큰 반지름, 초당 피해 */
        public static int EMBER_START = 4, EMBER_EVERY = 300, EMBER_MAX = 12;
        public static double EMBER_R0 = 2.5, EMBER_RMAX = 4.5, EMBER_DMG = 2;
        /** 조여 오는 고리: 바깥에 선 사람을 미는 세기, 초당 피해, 가장 작은 반지름(라운드 종류의 roamShrink 가 없을 때) */
        public static double SHRINK_PUSH = 0.45, SHRINK_DMG = 1.5, SHRINK_MIN = 24;
        /** 메아리 간격 */
        public static int ECHO_MIN = 30, ECHO_MAX = 70;
        /** 보는 동안: 보는 거리, 시선 원뿔(cos), 보이는 개체 빠르기, 안 보이는 개체 빠르기 */
        public static double GAZE_RANGE = 64, GAZE_COS = 0.92, GAZE_FROZEN = 0.05, GAZE_RUSH = 1.5;
        /** 시체 먹는 밤: 먹는 거리, 자랄 때 체력 배율, 몸 배율, 가장 많이 자람 */
        public static double FEED_R = 2.5, FEED_HP = 1.35, FEED_SCALE = 1.12;
        public static int FEED_MAX = 3;
        /** 정예 둘: 붙었다고 보는 거리, 초당 회복 몫, 붙은 동안 받는 피해 */
        public static double PAIR_R = 12, PAIR_REGEN = 0.03, PAIR_DMG = 0.5;
        /** 배움: 다시 세는 틱, 그 무기 몫이 이만큼 넘으면 배움, 배운 무기 피해 */
        public static int LEARN_EVERY = 300;
        public static double LEARN_SHARE = 0.4, LEARN_MUL = 0.6;
        /** 아는 얼굴(G50 수), 총 든 것(가장 많은 수, 다시 채우는 틱) */
        public static int FACES_MAX = 4, ARMED_MAX = 8, ARMED_EVERY = 600;
        /** 보급 비: 상자가 떨어지는 틱(라운드 시작에서) */
        public static int[] CRATE_AT = {300, 900, 1500};
        /** 질긴 것들: 안 맞은 지 이 틱이 지나면 초당 이 몫씩 참 */
        public static int REGEN_AFTER = 60;
        public static double REGEN = 0.045;
        /** 느린 벽 반지름, 버텨라 고리 반지름 */
        public static double WALL_R = 62, TIGHT_R = 16;
        /** 자폭: 발자국에서 터지는 거리, 트럭 피해(최대 체력 몫), 사람 피해 반지름과 피해 */
        public static double KAMIKAZE_R = 1.4, KAMIKAZE_TRUCK = 0.015, KAMIKAZE_BLAST = 3, KAMIKAZE_DMG = 4;
        /** 튀어 나감: 주기, 튀는 틱, 빠르기 */
        public static int LUNGE_EVERY = 60, LUNGE_TICKS = 10;
        public static double LUNGE_SPEED = 2.6;
        /** 떨어진 짐: 짐 체력(게이브 한 마리가 초당 위협 등급만큼 깎음), 짐을 노리는 몫 */
        public static double CARGO_HP = 120, CARGO_SHARE = 0.3;
        /** 탄약 줍기: 잡은 게이브가 떨굴 몫, 이만큼 잡도록 안 떨구면 꼭, 땅에 남은 것 상한 */
        public static double DROP_CHANCE = 0.25;
        public static int DROP_PITY = 6, DROP_OUT = 3;
        /** 거대 종으로 보는 체력(웨이브 1 기준) */
        public static double GIANT_HP = 150;
        /** 같은 종류를 다시 뽑지 않는 라운드 수 */
        public static int REUSE_GAP = 8;

        private Tune() {
        }
    }

    public final Game game;
    public final Trip trip;
    final RoundKind.Table table;
    private final Goals goals;
    private final RoundFx fx;
    private final Random rnd = new Random();

    /** 지금 라운드 종류(없으면 null: 보스, 발사장, 특수, 원정 꺼짐). */
    private RoundKind cur;
    private int curRound;
    private String spec = "-", aside;
    /** 계획을 미리(쉬는 시간 계획) 고쳤나, 그 계획. */
    private boolean pre;
    private Director.Plan applied;
    /** 이 라운드가 정한 안개 단계(-1 정하지 않음). */
    private int planFog = -1;
    private long beginAt, bannerUntil;
    private String forced;
    // 특수
    private SpecialRound special;
    private String specialKey;
    private final Map<String, SpecialRound> specials = new HashMap<>();
    private final Set<String> noSpecial = new HashSet<>();
    /** 설정 trip.noSpecials 로 막은 특수 키({@link #blockedSpecials}). */
    private final Set<String> blocked;
    // 뽑은 차례
    private final Map<String, Integer> usedAt = new HashMap<>();
    private final List<String> history = new ArrayList<>();
    // 감독이 낸 개체
    private final Set<Gabe> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Gabe> tracked = new ArrayList<>();
    private final EnumSet<Species> seenSpecies = EnumSet.noneOf(Species.class);
    // 박자, 방향
    private int pulsesDone;
    private boolean finaleDone;
    private double dirA, spinA, backA;
    private long spinAt;
    private int relocated, topped;
    // 보상
    private RoundKind ended;
    private boolean endedOk;
    private int reviveHalfRound = -1;
    private final Map<String, Integer> given = new LinkedHashMap<>();
    /** 감독이 계획을 짤 때 roundPlan 사건을 쏘나(한 번이라도 받으면 true): 그러면 갈래를 일찍 알려 덱이 그 갈래로 뽑게 한다. */
    private boolean planHook;
    /** 이 라운드 계획이 감독에게 넘어갔나(beginRound 부터 쉬는 시간까지). 그 전에는 갈래를 답하지 않는다(덱 표지가 바뀌면 감독이 고친 계획을 버림). */
    private boolean roundOpen;

    public Rounds(Game game, Trip trip) {
        this.game = game;
        this.trip = trip;
        this.table = RoundKind.table();
        this.goals = new Goals(game, trip, this);
        this.fx = new RoundFx(game, trip, this);
        this.blocked = blockedSpecials(game);
        game.plugin.getLogger().info("[원정] 라운드 종류 " + table.kinds.size() + "개(" + table.source + ") 풀 " + table.pools.keySet() + " 특수 " + table.specials.size()
            + (blocked.isEmpty() ? "" : " 막은 특수 " + blocked));
    }

    /**
     * 설정 trip.noSpecials(특수 키 목록, 칸이 없으면 [chase]: 2026-11-12 사용자 "추격전이 안 나오게"): 고르는 길(풀, 막 특수, 정한 특수 글, 길의 보장과
     * 보스 길목 질주, 험로 카드)에서 뺀다. 시험 훅 '특수 x 키 시작'(발사장의 4막 특수도 이 길)은 그대로 연다. 빈 목록이면 막는 것 없음.
     */
    public static Set<String> blockedSpecials(Game game) {
        org.bukkit.configuration.file.FileConfiguration c = game.plugin.getConfig();
        List<String> l = c.isList("trip.noSpecials") ? c.getStringList("trip.noSpecials") : List.of("chase", "sandstorm");
        Set<String> out = new HashSet<>();
        for (String s : l) if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return Collections.unmodifiableSet(out);
    }

    /** 고르는 길에서 막은 특수인가(설정 trip.noSpecials). */
    public boolean blocked(String key) {
        return key != null && blocked.contains(key);
    }

    // ------------------------------------------------------------------ 공개(특수, 발사장, 덱이 씀)

    /** 목표 엔진(특수 라운드가 막대와 표시 원, 줍기, 표적을 씀). */
    public Goals goals() {
        return goals;
    }

    /** 지금 라운드 종류(보스, 발사장, 특수면 null). */
    public RoundKind kind() {
        return cur;
    }

    /** 지금 특수 라운드 키(없으면 null). */
    public String specialKey() {
        return special == null ? null : specialKey;
    }

    /** 표(라운드 종류 100, 풀, 특수 목록). */
    public RoundKind.Table table() {
        return table;
    }

    /**
     * 라운드를 끝냄(목표를 마침 ok, 놓침 !ok): 남은 떼가 물러난 뒤 쉬는 시간이 열린다. 특수 라운드가 끝 조건을 스스로 정할 때도 쓴다.
     */
    public void finish(boolean ok) {
        if (ok) goals.succeed();
        else goals.fail();
    }

    /**
     * 발사장 부품(Launch)이 4막 전용 종류의 축 값만 빌려 쓸 때: 그 종류로 이 라운드를 연다(목표는 Launch 가 쥠: build 는 Goals 대신 Launch).
     * 계획은 이미 열렸으니 살아 있는 값(배율, 나오는 자리, 장치)만. 키가 없으면 false.
     */
    public boolean use(String key, boolean withGoal) {
        RoundKind k = table.get(key);
        if (k == null) return false;
        cur = k;
        aside = null;
        curRound = game.wave;
        postApply(k);
        if (withGoal) goals.begin(k, game.wave);
        dirBegin(k);
        fx.begin(k, game.wave, planFog);
        tempoBegin(k);
        banner(k);
        return true;
    }

    /** 감독이 낸 것처럼 고리(가운데에서 각 ang 라디안, 반지름 r)에 그 종 하나를 놓음(체력, 보상 배율을 곱함). 예산이나 클래스가 없으면 null. */
    public Gabe spawnRing(Species sp, double ang, double r) {
        Vector at = new Vector(Plaza.CX + 0.5 + Math.cos(ang) * r, 0, Plaza.CZ + 0.5 + Math.sin(ang) * r);
        return spawnAt(sp, at);
    }

    /** at(높이는 바닥 + 띠 높이로 맞춤)에 그 종 하나(체력, 보상 배율). */
    public Gabe spawnAt(Species sp, Vector at) {
        if (sp == null || !sp.implemented() || !game.mobs.budgetFor(game.mobs.costOf(sp))) return null;
        Vector v = at.clone();
        double floor;
        try {
            floor = game.mobs.flow.floor(v.getX(), v.getZ());
        } catch (RuntimeException e) {
            floor = Plaza.FLOOR_Y;
        }
        v.setY(floor + sp.band.hover);
        Gabe g;
        try {
            g = game.mobs.spawn(sp, v, false);
        } catch (RuntimeException e) {
            return null;
        }
        if (g == null) return null;
        g.maxHp *= game.director.hpMul(sp);
        g.hp = g.maxHp;
        double rm = game.director.rewardMul();
        if (g.reward > 0) g.reward = (int) Math.max(1, Math.round(g.reward * rm));
        return g;
    }

    /** 이 개체를 트럭 노림으로 찍음(트럭 부품이 있을 때). */
    public boolean seekTruck(Gabe g) {
        Truck t = trip.truck();
        return t != null && t.mark(g);
    }

    /** 이 라운드 나오는 방향이 정한 각(쏠림, 두 쪽 가운데 하나, 도는 쪽, 등 뒤). 없으면 무작위. */
    public double dirAngle() {
        if (cur == null) return rnd.nextDouble() * Math.PI * 2;
        return switch (cur.dir) {
            case "lean" -> dirA;
            case "two" -> rnd.nextBoolean() ? dirA : dirA + Math.PI;
            case "spin" -> spinA;
            case "back", "noise" -> backA;
            case "road" -> rnd.nextBoolean() ? -Math.PI / 2 : Math.PI / 2;
            default -> rnd.nextDouble() * Math.PI * 2;
        };
    }

    /** 이번 판에 나온 적 있는 종. */
    public Set<Species> seenSpecies() {
        return Collections.unmodifiableSet(seenSpecies);
    }

    /** 이 라운드에 쓸 수 있는 종(덱 부품이 'deckSpecies' 로 답하면 그것, 아니면 감독 묶음 + 지금 막의 종) 가운데 조건에 맞는 것 하나(없으면 null). */
    public Species pick(Predicate<Species> f) {
        List<Species> c = new ArrayList<>();
        for (Species sp : candidates(Math.max(1, game.wave), trip.act())) if (f.test(sp)) c.add(sp);
        Director.Plan p = game.director.plan();
        if (p != null) for (Species sp : p.counts.keySet()) if (f.test(sp) && !c.contains(sp) && ok(sp)) c.add(0, sp);
        return c.isEmpty() ? null : c.get(rnd.nextInt(Math.min(c.size(), 4)));
    }

    /** 값싼 떼 종 하나(알이 깨어날 때, 덮침). */
    public Species cheapSwarm() {
        Species s = pick(sp -> sp.is(Role.SWARM) && sp.threat <= 2 && sp.spawn == Species.Spawn.RING);
        if (s == null) s = pick(sp -> sp.threat <= 2 && sp.spawn == Species.Spawn.RING);
        return s != null ? s : Species.G01.implemented() ? Species.G01 : null;
    }

    // ------------------------------------------------------------------ 허브 고리

    @Override
    public void startGame() {
        endRound(false);
        usedAt.clear();
        history.clear();
        seenSpecies.clear();
        given.clear();
        ended = null;
        reviveHalfRound = -1;
        // forced(시험 훅으로 정한 다음 라운드)는 남김: 대기실에서 정하고 '/gave 웨이브 n' 으로 판을 열 때 쓰려고
        rnd.setSeed(trip.seed * 7919);
    }

    @Override
    public void beforeRound(int n) {
        endRound(false);
        reviveHalfRound = -1;
        spec = trip.askStr("roundSpec", null);
        if (spec == null) spec = defaultSpec(n);
        aside = null;
        cur = null;
        pre = false;
        applied = null;
        planFog = -1;
        rnd.setSeed(trip.seed * 7919 + n * 104729L);
        String f = forced;
        forced = null;
        if (f != null) {
            if (f.startsWith("special:")) {
                if (!openSpecial(f.substring(8))) cur = pickFrom("plain", n);
            } else cur = table.get(f);
            if (cur == null && special == null) cur = pickFrom("plain", n);
        } else if (n == 1 && !spec.startsWith("special")) {
            cur = table.get(table.first);
        } else {
            cur = choose(n, spec);
        }
        Director.Plan p = game.director.nextPlan();
        boolean planOk = p != null && p.wave == n;
        if (cur != null && planOk) {
            try {
                applyPlan(p, cur, n);
                pre = true;
                applied = p;
            } catch (RuntimeException e) {
                trip.log("라운드 " + n + " 계획 고치기 오류(살아 있는 값만 씀): " + e);
            }
        }
        if (special != null && planOk) special.plan(p);
        if (cur != null) {
            usedAt.put(cur.key, n);
            history.add(n + ":" + cur.key);
        } else if (special != null) history.add(n + ":special:" + specialKey);
        else if (aside != null) history.add(n + ":" + aside);
        trip.log("라운드 " + n + " spec=" + spec + " 종류=" + (cur != null ? cur : special != null ? "special:" + specialKey : "비켜 줌(" + aside + ")")
            + (pre ? " 계획 고침" : planOk ? "" : " 쉬는 시간 계획 없음"));
    }

    @Override
    public void beginRound(int n) {
        curRound = n;
        roundOpen = true;
        beginAt = game.now();
        seen.clear();
        tracked.clear();
        pulsesDone = 0;
        finaleDone = false;
        relocated = topped = 0;
        if (special != null) {
            boolean ok;
            try {
                ok = special.open(n, specialKey);
            } catch (RuntimeException | LinkageError e) {
                trip.log("특수 " + specialKey + " open 오류: " + e);
                ok = false;
            }
            if (ok) return;
            trip.log("특수 " + specialKey + " 이 못 열어 보통 라운드로");
            closeSpecial();
            cur = pickFrom("plain", n);
        }
        if (cur == null) return;
        if (!pre) postApply(cur);
        goals.begin(cur, n);
        dirBegin(cur);
        fx.begin(cur, n, planFog);
        tempoBegin(cur);
        banner(cur);
    }

    @Override
    public boolean roundDone(boolean done) {
        if (special != null) return special.roundDone(done);
        if (cur == null) return done;
        return goals.done(done);
    }

    @Override
    public String lost() {
        return special != null ? special.lost() : null;
    }

    @Override
    public void beginBreak(int n) {
        if (special != null) {
            try {
                special.beginBreak(n);
            } catch (RuntimeException | LinkageError e) {
                trip.log("특수 " + specialKey + " beginBreak 오류: " + e);
            }
        }
        RoundKind k = cur;
        boolean ok = k != null && !goals.failed() && (goals.killLike() || goals.met());
        endRound(false);
        ended = k;
        endedOk = ok;
        if (k != null && ok) reward(k, n);
        if (k != null) trip.log("라운드 " + n + " 끝 " + k.key + (ok ? " 성공" : " 놓침") + " 준 것 " + given);
    }

    @Override
    public void tick() {
        if (special != null) special.tick();
        if (game.state != Game.State.WAVE || cur == null) return;
        long now = game.now();
        watchMobs(now);
        tempoTick(now);
        dirTick(now);
        goals.tick();
        fx.tick();
    }

    @Override
    public void gameOver() {
        if (special != null) special.gameOver();
        endRound(false);
    }

    @Override
    public void reset() {
        if (special != null) special.reset();
        endRound(true);
        forced = null;
    }

    @Override
    public void enabled(boolean on) {
        if (!on) endRound(true);
    }

    @Override
    public double mul(String key) {
        double m = 1;
        if (special != null) {
            double v = special.mul(key);
            if (!Double.isNaN(v)) m *= v;
        }
        if (key.equals("revive") && reviveHalfRound >= 0 && game.state != Game.State.WAVE) m *= 0.5;
        if (cur == null) return m;
        RoundKind k = cur;
        RoundKind.Tempo t = table.tempo(k.tempo);
        switch (key) {
            case "hp" -> m *= k.num("hp", 1) * t.hp;
            case "speed" -> {
                m *= k.num("speed", 1);
                if (t.speedUp > 0) m *= 1 + t.speedUp * Math.min(1, (game.now() - beginAt) / (double) Tune.RAMP_TICKS);
            }
            case "dmg" -> m *= k.num("dmg", 1);
            case "reward" -> m *= k.num("money", 1) * (k.reward.equals("money") ? 1.5 : 1) * goals.rewardMul();
            case "truckShare" -> {
                double base = baseShare(), want = k.num("truckShare", 0);
                if (want > 0) m *= want / Math.max(0.01, base);
                else if (k.goal.equals("truck")) m *= 3;
            }
            case "truckDmg" -> m *= k.num("truckDmg", 1);
            case "price.ammo" -> m *= k.num("ammoPrice", 1);
            case "price.auto_ammo" -> m *= k.num("autoPrice", 1);
            default -> {
            }
        }
        return m * fx.mul(key);
    }

    /** 트럭 노림 바탕 몫(Truck.share 와 같은 셈, 배율 빼고). */
    private double baseShare() {
        return Math.min(Trip.Tune.TRUCK_SHARE_MAX, Trip.Tune.TRUCK_SHARE + Trip.Tune.TRUCK_SHARE_PER_WAVE * Math.max(0, game.wave - 1));
    }

    @Override
    public Object ask(String key) {
        if (special != null) {
            Object o = special.ask(key);
            if (o != null) return o;
        }
        switch (key) {
            case "disp" -> {
                return goals.displays() + fx.displays();
            }
            case "shopLocked" -> {
                return fx.shopLocked() ? Boolean.TRUE : null;
            }
            case "latentCap" -> {
                return cur != null && cur.num("latentCap", 0) > 0 ? (Object) (int) cur.num("latentCap", 0) : null;
            }
            default -> {
            }
        }
        if (cur == null) return null;
        return switch (key) {
            case "roundKind" -> cur.key;
            // 덱(Deck.comp)이 읽는다. 감독이 쉬는 시간에 '갈래 없이' 짠 계획을 Rounds 가 이미 고쳤으므로, 감독이 계획 사건을 쏘지 않는 동안은
            // 웨이브가 열리기 전에 갈래를 알려 주지 않는다(알려 주면 덱 표지가 바뀌어 감독이 계획을 다시 짜고 고친 것이 사라짐)
            case "roundComp" -> planHook || roundOpen && game.state == Game.State.WAVE ? cur.comp : null;
            case "roundTempo" -> cur.tempo;
            case "roundDir" -> cur.dir;
            case "roundEnv" -> cur.env;
            case "goal" -> goals.kind.key();
            case "roundName" -> cur.phrase;
            default -> null;
        };
    }

    @Override
    public boolean help(Player p) {
        if (special != null && special.help(p)) return true;
        if (cur == null || game.state != Game.State.WAVE) return false;
        return fx.help(p) || goals.help(p);
    }

    /** 감독이 웨이브를 열며 계획을 새로 짰을 때 쏘는 사건(감독 고리 요청: Director.beginWave 에서 plan 을 정한 직후 trip.fire(EV_PLAN, plan)). */
    public static final String EV_PLAN = "roundPlan";

    @Override
    public void on(String event, Object arg) {
        if (special != null) special.on(event, arg);
        // 쉬는 시간 계획이 없던 라운드(1라운드, 건너뛰기, 다시 짠 계획): 감독이 알려 주면 그 계획에 축 값을 입힌다(아직 대기열을 만들기 전)
        if (EV_PLAN.equals(event)) planHook = true;
        if (EV_PLAN.equals(event) && arg instanceof Director.Plan p && cur != null && p.wave == game.wave && p != applied) {
            try {
                applyPlan(p, cur, p.wave);
                pre = true;
                applied = p;
            } catch (RuntimeException e) {
                trip.log("라운드 " + p.wave + " 계획 고치기 오류(감독 사건): " + e);
            }
            if (special != null) special.plan(p);
        }
        goals.on(event, arg);
    }

    @Override
    public void hud(Player p, Map<String, Object> out) {
        if (special != null) special.hud(p, out);
        if (cur == null) return;
        out.put("round.key", cur.key);
        out.put("round.name", cur.phrase);
        out.put("round.line", cur.linePhrase);
        out.put("round.goal", goals.kind.key());
        long left = bannerUntil - game.now();
        if (left > 0) out.put("round.show", (int) left);
        goals.hud(p, out);
    }

    @Override
    public String status() {
        StringBuilder sb = new StringBuilder("round=");
        if (special != null) sb.append("special:").append(specialKey);
        else if (cur != null) sb.append(cur.key);
        else sb.append(aside == null ? "-" : "(" + aside + ")");
        sb.append(" rspec=").append(spec).append(pre ? " plan=pre" : cur != null ? " plan=live" : "");
        if (cur != null) {
            sb.append(' ').append(goals.status());
            String f = fx.status();
            if (f != null && !f.isEmpty()) sb.append(' ').append(f);
            if (relocated > 0) sb.append(" moved=").append(relocated);
            if (topped > 0) sb.append(" topped=").append(topped);
        }
        if (special != null) {
            String s = special.status();
            if (s != null && !s.isEmpty()) sb.append(' ').append(s);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 고르기

    /** 길 부품이 없을 때의 풀 글. */
    private String defaultSpec(int n) {
        if (Trip.bossRound(n)) return "boss";
        if (n > Trip.LAST_ROAD_ROUND) return "launch";
        return n % 4 == 0 ? "pool:hard" : "pool:plain";
    }

    /** 풀 글에서 종류 하나(특수면 special 을 열고 null, 보스와 발사장은 aside 를 적고 null). */
    private RoundKind choose(int n, String s) {
        if (s.equals("boss") || Trip.bossRound(n) && !s.startsWith("special")) {
            aside = "boss";
            return null;
        }
        if (s.equals("launch")) {
            if (trip.has("Launch")) {
                aside = "launch";
                return null;
            }
            return pickFrom("launch", n);
        }
        if (s.startsWith("special")) {
            String key = specialFor(s, trip.act(), rnd);
            if (key != null && openSpecial(key)) return null;
            return pickFrom("hard", n);
        }
        if (s.startsWith("pool:")) return pickFrom(s.substring(5), n);
        return pickFrom("plain", n);
    }

    /**
     * 풀 글 s(special, special:키, special:actN, special_or_hard)에서 열 특수 키(지금 막 act). null 이면 hard 풀로. 막 특수는 r 로 고르고(그 막에만 있는 것 먼저),
     * 막은 특수(설정 trip.noSpecials)를 정한 글은 같은 막의 다른 특수로 바꾼다. 고르기(choose)와 모의(시험 훅 '길 x 특수모의')가 같이 쓴다.
     */
    String specialFor(String s, int act, Random r) {
        if (s == null || !s.startsWith("special")) return null;
        String key = s.contains(":") ? s.substring(s.indexOf(':') + 1) : null;
        if (key != null && key.contains("(")) key = key.substring(0, key.indexOf('(')).trim();
        if (s.startsWith("special_or_hard")) return r.nextBoolean() ? actSpecial(act, r) : null;
        if (key == null || key.isEmpty() || key.startsWith("act")) {
            int a = act;
            if (key != null && key.length() > 3) {
                try {
                    a = Integer.parseInt(key.substring(3));
                } catch (NumberFormatException e) {
                    a = act;
                }
            }
            return actSpecial(a, r);
        }
        return blocked(key) ? actSpecial(act, r) : key;
    }

    /** 풀 pool 에서 pickFrom 이 열 수 있는 특수 키(막 act, 모의용). */
    List<String> poolSpecials(String pool, int act) {
        List<String> out = new ArrayList<>();
        for (String key : table.pools.getOrDefault(pool, table.pools.getOrDefault("plain", List.of()))) {
            if (key.startsWith("special:") && pickable(table.special(key.substring(8)), act)) out.add(key.substring(8));
        }
        return out;
    }

    /** 풀에서 뽑음: 지금 막에 들고, 조건이 맞고, 최근 REUSE_GAP 라운드 안에 안 쓴 것. 뒤 막 전용이 그 막에 처음 올 때 더 자주. */
    RoundKind pickFrom(String pool, int n) {
        int act = trip.act();
        List<String> keys = table.pools.getOrDefault(pool, table.pools.get("plain"));
        if (keys == null || keys.isEmpty()) return table.get(table.first);
        for (int pass = 0; pass < 3; pass++) {
            List<Object> c = new ArrayList<>();
            List<Double> w = new ArrayList<>();
            double sum = 0;
            for (String key : keys) {
                if (key.startsWith("special:")) {
                    String sk = key.substring(8);
                    RoundKind.SpecialDef d = table.special(sk);
                    if (pass > 0 || !pickable(d, act)) continue;
                    c.add(key);
                    w.add(1.0);
                    sum += 1;
                    continue;
                }
                RoundKind k = table.get(key);
                if (k == null || !k.in(act) && pass < 2) continue;
                if (k.launch && !pool.equals("launch")) continue;
                if ("dead".equals(k.needs) && !anyDead()) continue;
                Integer at = usedAt.get(k.key);
                if (pass == 0 && at != null && n - at < Tune.REUSE_GAP) continue;
                double wt = 1;
                if (k.act0 > 1 && k.act0 == act) wt *= 2;
                if (at == null) wt *= 1.5;
                c.add(k);
                w.add(wt);
                sum += wt;
            }
            if (c.isEmpty()) continue;
            double r = rnd.nextDouble() * sum;
            for (int i = 0; i < c.size(); i++) {
                r -= w.get(i);
                if (r <= 0 || i == c.size() - 1) {
                    Object o = c.get(i);
                    if (o instanceof RoundKind k) return k;
                    if (openSpecial(((String) o).substring(8))) return null;
                    break;
                }
            }
        }
        RoundKind f = table.get(table.first);
        return f != null ? f : table.kinds.values().iterator().next();
    }

    private boolean anyDead() {
        for (PState s : game.players.all()) if (s.life == Life.DEAD) return true;
        return false;
    }

    // ------------------------------------------------------------------ 특수

    private Class<?> specialClass(RoundKind.SpecialDef d) {
        try {
            Class<?> c = Class.forName(d.cls, true, Rounds.class.getClassLoader());
            return SpecialRound.class.isAssignableFrom(c) ? c : null;
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** 고르는 길이 열 수 있는 특수인가: 그 막에 들고, 못 만든 적 없고, 막지 않았고(trip.noSpecials), 클래스가 있음. */
    private boolean pickable(RoundKind.SpecialDef d, int act) {
        return d != null && d.in(act) && !noSpecial.contains(d.key) && !blocked(d.key) && specialClass(d) != null;
    }

    /** 그 막의 특수 가운데 고를 수 있는 것 하나의 키(그 막에만 있는 것 먼저, 없으면 null). */
    private String actSpecial(int a, Random r) {
        List<RoundKind.SpecialDef> c = new ArrayList<>();
        for (RoundKind.SpecialDef d : table.specials) if (pickable(d, a)) c.add(d);
        List<RoundKind.SpecialDef> only = new ArrayList<>();
        for (RoundKind.SpecialDef d : c) if (d.act0 == a && d.act1 == a) only.add(d);
        List<RoundKind.SpecialDef> from = only.isEmpty() ? c : only;
        if (from.isEmpty()) return null;
        return from.get(r.nextInt(from.size())).key;
    }

    /** 특수 key 를 만들어 이 라운드에 씀(클래스가 없으면 false). */
    private boolean openSpecial(String key) {
        if (key == null || noSpecial.contains(key)) return false;
        SpecialRound s = specials.get(key);
        if (s == null) {
            RoundKind.SpecialDef d = table.special(key);
            String cls = d != null ? d.cls : "kr.gave.trip.special." + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            try {
                Class<?> c = Class.forName(cls, true, Rounds.class.getClassLoader());
                if (!SpecialRound.class.isAssignableFrom(c)) throw new ClassNotFoundException(cls + " 이 SpecialRound 가 아님");
                Object o;
                try {
                    Constructor<?> k3 = c.getConstructor(Game.class, Trip.class, Rounds.class);
                    o = k3.newInstance(game, trip, this);
                } catch (NoSuchMethodException e) {
                    o = c.getConstructor(Game.class, Trip.class).newInstance(game, trip);
                }
                s = (SpecialRound) o;
                specials.put(key, s);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                noSpecial.add(key);
                if (!(e instanceof ClassNotFoundException)) trip.log("특수 " + key + " 를 못 만듦: " + e);
                return false;
            }
        }
        special = s;
        specialKey = key;
        return true;
    }

    private void closeSpecial() {
        if (special == null) return;
        try {
            special.close();
        } catch (RuntimeException | LinkageError e) {
            trip.log("특수 " + specialKey + " close 오류: " + e);
        }
        special = null;
        specialKey = null;
    }

    // ------------------------------------------------------------------ 계획 고치기(쉬는 시간 계획, 감독이 웨이브를 열기 전)

    /** 단위 값(감독 Director.unit 과 같은 셈: 총량을 나눌 때 한 개체가 차지하는 몫). */
    static double unit(Species sp) {
        double u = sp.is(Role.SWARM) ? 4 : switch (sp.threat) {
            case 1 -> 1.0;
            case 2 -> 1.5;
            case 3 -> 2.0;
            case 4 -> 3.0;
            default -> 4.0;
        };
        if (sp.is(Role.ELITE)) u *= 1.5;
        if (Director.Tune.UNIT_HP > 0 && !sp.is(Role.SWARM)) u = Math.max(u, sp.hp / Director.Tune.UNIT_HP);
        return u;
    }

    /** 감독이 따로 넣는 종(보상, 탐조등, 땅 갈라짐의 웅덩이)인가. */
    private static boolean special(Director.Plan p, Species sp) {
        return sp.is(Role.BONUS) || sp == Species.G43 && p.searchlights > 0 || sp == Species.G31 && p.flood;
    }

    /** 라운드와 특수가 고를 수 있는 종: 클래스가 있고, 다른 일의 결과로만 생기는 종, 보스, 보상 종, 탐조등이 아니고, 설정 bannedSpecies 로 막지 않음(SpeciesBan). */
    private boolean ok(Species sp) {
        return sp.implemented() && sp.spawn != Species.Spawn.DERIVED && !sp.is(Role.BOSS) && !sp.is(Role.BONUS) && sp != Species.G43 && !SpeciesBan.listed(sp);
    }

    /** 이 라운드에 쓸 수 있는 종: 덱 부품이 'deckSpecies' 로 답하면 그것, 아니면 감독 묶음(등급이 맞는 것) + 지금 막의 종. */
    List<Species> candidates(int n, int act) {
        List<Species> out = new ArrayList<>();
        Object deck = trip.ask("deckSpecies");
        if (deck instanceof Collection<?> col) {
            for (Object o : col) if (o instanceof Species sp && ok(sp)) out.add(sp);
            if (!out.isEmpty()) {
                Collections.shuffle(out, rnd);
                return out;
            }
        }
        Deck dk = trip.part(Deck.class);
        if (dk != null && dk.active()) {
            // 막 덱: 이 막에서 이미 나온 종 먼저(섞어서), 그다음 덱에 남은 종(뽑힐 차례대로). 감독이 확정할 때 Deck.planned 가 더한 종을 덱에서 뺀다
            List<Species> seenAct = new ArrayList<>();
            for (Species sp : dk.seen(act)) if (ok(sp)) seenAct.add(sp);
            Collections.shuffle(seenAct, rnd);
            out.addAll(seenAct);
            for (Species sp : dk.left(act)) if (ok(sp) && !out.contains(sp)) out.add(sp);
            if (!out.isEmpty()) return out;
        }
        for (Species sp : Species.values()) {
            if (!ok(sp)) continue;
            if (game.director.inBundle(sp) && sp.tier <= Math.max(n, 3) || trip.enabled && sp.act == act && sp.tier <= n + 12) out.add(sp);
        }
        Collections.shuffle(out, rnd);
        return out;
    }

    /** 종 갈래 조건. */
    static Predicate<Species> match(String comp) {
        if (!comp.equals("ground") && !comp.equals("giant")) return sp -> Deck.matches(sp, comp);
        return switch (comp) {
            case "swarm" -> sp -> sp.is(Role.SWARM);
            case "charge" -> sp -> sp.is(Role.CHARGE);
            case "ranged" -> sp -> sp.is(Role.RANGED);
            case "sky" -> sp -> sp.spawn == Species.Spawn.SKY;
            case "ground" -> sp -> sp.band == kr.gave.mob.Band.GROUND && sp.spawn == Species.Spawn.RING;
            case "tank" -> sp -> sp.is(Role.TANK) || sp.is(Role.SHIELD);
            case "trap" -> sp -> sp.is(Role.TRAP) || sp.spawn == Species.Spawn.PLACED;
            case "mimic" -> sp -> Director.MIMIC.contains(sp) || sp.is(Role.DISRUPT);
            case "elite" -> sp -> sp.is(Role.ELITE);
            case "support" -> sp -> sp.is(Role.SUPPORT);
            case "eater" -> sp -> Director.EATERS.contains(sp) || sp.is(Role.DISRUPT) && sp.threat >= 3;
            case "latent" -> sp -> Director.LATENT.contains(sp);
            case "giant" -> sp -> Deck.matches(sp, "giant") || sp.hp >= Tune.GIANT_HP || sp.is(Role.TANK) && sp.threat >= 4;
            case "objective" -> sp -> sp.is(Role.OBJECTIVE);
            default -> sp -> true;
        };
    }

    /** 쉬는 시간 계획 p 에 종류 k 의 축 값을 입힌다(감독이 이 계획으로 웨이브를 연다). */
    void applyPlan(Director.Plan p, RoundKind k, int n) {
        int act = trip.act();
        p.breather = false;
        // 감독이 굴린 쏠림 덤은 쏠림 종류에서만 남김
        if (!k.dir.equals("lean") && p.lean >= 0) {
            for (Map.Entry<Species, Integer> e : p.leanCounts.entrySet()) p.counts.computeIfPresent(e.getKey(), (sp, c) -> Math.max(1, c - e.getValue()));
            p.leanCounts.clear();
            p.leanExtra = 0;
            p.lean = -1;
        }
        comp(p, k, n, act);
        // 수(박자): 몰려오는 종 몫에 곱함
        RoundKind.Tempo t = table.tempo(k.tempo);
        double u = k.tempo.equals("trickle") ? Tune.TRICKLE_START : t.units;
        if (Math.abs(u - 1) > 1e-6) for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            Species sp = e.getKey();
            if (sp.spawn == Species.Spawn.PLACED || special(p, sp)) continue;
            e.setValue(Math.max(1, (int) Math.round(e.getValue() * u)));
        }
        // 정예, 깔린 종, 처음 보는 종 배율
        double el = k.num("elites", 1), pl = k.num("placed", 1), fr = k.num("fresh", 1);
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            Species sp = e.getKey();
            double m = 1;
            if (sp.is(Role.ELITE)) m *= el;
            if (sp.spawn == Species.Spawn.PLACED) m *= pl;
            if (!seenSpecies.contains(sp)) m *= fr;
            if (m != 1) e.setValue(Math.max(1, (int) Math.round(e.getValue() * m)));
        }
        int species = (int) k.num("species", 0);
        if (species > 0) widen(p, n, act, species);
        // 쏠림
        if (k.dir.equals("lean") && p.lean < 0) {
            p.lean = rnd.nextInt(4);
            for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
                Species sp = e.getKey();
                if (sp.spawn == Species.Spawn.PLACED || special(p, sp)) continue;
                int extra = (int) Math.round(e.getValue() * Director.Tune.LEAN_EXTRA);
                if (extra <= 0) continue;
                p.leanCounts.put(sp, extra);
                e.setValue(e.getValue() + extra);
                p.leanExtra += extra;
            }
            if (p.leanExtra == 0) p.lean = -1;
        }
        env(p, k);
        // 넘침은 체력으로(감독과 같은 규칙)
        int placedTot = 0, moveTot = 0;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            if (e.getKey().spawn == Species.Spawn.PLACED) placedTot += e.getValue();
            else moveTot += e.getValue();
        }
        int room = Math.max(Director.Tune.MAX_ENTRIES / 2, Director.Tune.MAX_ENTRIES - placedTot);
        if (moveTot > room) {
            double kk = (double) moveTot / room;
            for (Map.Entry<Species, Integer> e : p.counts.entrySet())
                if (e.getKey().spawn != Species.Spawn.PLACED) e.setValue(Math.max(1, (int) Math.ceil(e.getValue() / kk)));
            p.hpBonus *= Math.min(Director.Tune.HP_OVERFLOW_MAX, kk);
        }
        if (p.fresh != null && !p.counts.containsKey(p.fresh)) p.fresh = null;
        int e2 = 0;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) if (e.getKey().is(Role.ELITE)) e2 += e.getValue();
        p.elites = e2;
        if (p.lean >= 0) dirA = leanAngle(p.lean);
    }

    /** 종 갈래(comp): 그 갈래 종이 몫의 대부분을 차지하게 몫을 다시 나눈다. 계획에 그 갈래가 모자라면 쓸 수 있는 종에서 더한다. */
    private void comp(Director.Plan p, RoundKind k, int n, int act) {
        String c = k.comp;
        if (c.equals("mix")) return;
        List<Species> moving = new ArrayList<>();
        double units = 0;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            Species sp = e.getKey();
            if (sp.spawn == Species.Spawn.PLACED || special(p, sp)) continue;
            moving.add(sp);
            units += e.getValue() * unit(sp);
        }
        if (moving.isEmpty() || units <= 0) return;
        List<Species> cand = candidates(n, act);
        switch (c) {
            case "new" -> {
                // 이 막에서 아직 못 본 종 둘을 더함(처음 보는 종 몫 두 배는 mods.fresh 가 applyPlan 에서)
                int added = 0;
                for (Species sp : cand) {
                    if (added >= 2) break;
                    if (seenSpecies.contains(sp) || p.counts.containsKey(sp) || sp.spawn == Species.Spawn.PLACED || banned(p, sp)) continue;
                    p.counts.put(sp, Math.max(1, (int) Math.round(units * 0.12 / unit(sp))));
                    added++;
                }
                return;
            }
            case "return" -> {
                // 앞에서 본 종만(새 종은 하나씩)
                List<Species> old = new ArrayList<>();
                for (Species sp : moving) if (seenSpecies.contains(sp)) old.add(sp);
                for (Species sp : cand) if (old.size() < 4 && seenSpecies.contains(sp) && !old.contains(sp) && sp.spawn != Species.Spawn.PLACED && !banned(p, sp)) old.add(sp);
                if (old.isEmpty()) return;
                for (Species sp : moving) if (!old.contains(sp)) p.counts.put(sp, 1);
                share(p, old, units * 0.92);
                return;
            }
            case "mono" -> {
                Species one = null;
                for (Species sp : moving) if (sp.is(Role.SWARM) || sp.is(Role.CHARGE)) one = one == null || p.counts.get(sp) > p.counts.get(one) ? sp : one;
                if (one == null) one = moving.get(0);
                final Species keep = one;
                p.counts.keySet().removeIf(sp -> sp != keep && !special(p, sp));
                p.counts.put(keep, Math.max(1, (int) Math.round(units / unit(keep))));
                p.fresh = null;
                return;
            }
            case "duo" -> {
                Species a = p.lead != null && moving.contains(p.lead) ? p.lead : moving.get(0);
                Species b = p.base != null && p.base != a && moving.contains(p.base) ? p.base : null;
                if (b == null) for (Species sp : moving) if (sp != a) {
                    b = sp;
                    break;
                }
                if (b == null) for (Species sp : cand) if (sp != a && sp.spawn != Species.Spawn.PLACED && !Director.bannedTogether(a, sp)) {
                    b = sp;
                    break;
                }
                final Species x = a, y = b;
                p.counts.keySet().removeIf(sp -> sp != x && sp != y && !special(p, sp));
                p.counts.put(x, Math.max(1, (int) Math.round(units * 0.6 / unit(x))));
                if (y != null) p.counts.put(y, Math.max(1, (int) Math.round(units * 0.4 / unit(y))));
                p.fresh = null;
                return;
            }
            default -> {
            }
        }
        Predicate<Species> m = match(c);
        boolean only = c.equals("sky");
        double s = switch (c) {
            case "sky" -> 1.0;
            case "swarm" -> 0.7;
            case "support" -> 0.25;
            default -> 0.6;
        };
        List<Species> hit = new ArrayList<>();
        for (Species sp : moving) if (m.test(sp)) hit.add(sp);
        if (c.equals("trap")) {
            // 함정 갈래: 깔리는 종을 더함(몫은 placed 배율이 늘림)
            int addedPlaced = 0;
            for (Species sp : cand) {
                if (addedPlaced >= 2) break;
                if (sp.spawn != Species.Spawn.PLACED || p.counts.containsKey(sp) || !m.test(sp) || banned(p, sp)) continue;
                p.counts.put(sp, Math.min(Director.placedCap(sp, trip.people()), 2));
                addedPlaced++;
            }
        }
        for (Species sp : cand) {
            if (hit.size() >= 2) break;
            if (hit.contains(sp) || !m.test(sp) || sp.spawn == Species.Spawn.PLACED || banned(p, sp)) continue;
            hit.add(sp);
        }
        if (hit.isEmpty()) return;
        List<Species> rest = new ArrayList<>();
        for (Species sp : moving) if (!hit.contains(sp)) rest.add(sp);
        share(p, hit, units * s);
        if (only) {
            p.counts.keySet().removeIf(sp -> rest.contains(sp));
            if (p.fresh != null && !p.counts.containsKey(p.fresh)) p.fresh = null;
        } else if (!rest.isEmpty()) share(p, rest, units * (1 - s));
    }

    /** units 를 종들에 나눔(이미 있는 몫 비율대로, 새 종은 고르게). */
    private void share(Director.Plan p, List<Species> sps, double units) {
        double w = 0;
        Map<Species, Double> ws = new LinkedHashMap<>();
        for (Species sp : sps) {
            Integer c = p.counts.get(sp);
            double v = c == null ? 1.0 / sps.size() : c * unit(sp);
            ws.put(sp, v);
            w += v;
        }
        if (w <= 0) return;
        for (Map.Entry<Species, Double> e : ws.entrySet()) {
            Species sp = e.getKey();
            p.counts.put(sp, Math.max(1, (int) Math.round(units * e.getValue() / w / unit(sp))));
        }
    }

    /** 종 수를 n 까지 늘림(전부, 모두 온다): 쓸 수 있는 종을 차례로 하나씩 더함. 총량은 그대로 나눔. */
    private void widen(Director.Plan p, int round, int act, int want) {
        List<Species> cand = candidates(round, act);
        double units = 0;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) if (e.getKey().spawn != Species.Spawn.PLACED) units += e.getValue() * unit(e.getKey());
        int have = p.counts.size();
        for (Species sp : cand) {
            if (have >= want) break;
            if (p.counts.containsKey(sp) || banned(p, sp) || sp.spawn == Species.Spawn.PLACED) continue;
            p.counts.put(sp, Math.max(1, (int) Math.round(units / want / unit(sp))));
            have++;
        }
    }

    private static boolean banned(Director.Plan p, Species sp) {
        int latent = 0;
        for (Species c : p.counts.keySet()) {
            if (Director.bannedTogether(sp, c)) return true;
            if (Director.LATENT.contains(c)) latent++;
        }
        return Director.LATENT.contains(sp) && latent >= 2;
    }

    /** 날씨와 시야(env): 감독 사건을 계획에 켬. 웨이브 번호에 묶인 감독 사건(안개, 밤, 조용, 땅 갈라짐, 눈)은 이 라운드가 정한 것으로 바꾼다. */
    private void env(Director.Plan p, RoundKind k) {
        p.fog = 0;
        p.night = false;
        p.silent = false;
        p.flood = false;
        p.puddleEye = false;
        p.dotDir = -1;
        if (p.searchlights > 0 && !k.has("searchlight")) {
            p.counts.remove(Species.G43);
            p.searchlights = 0;
            p.lightDir = -1;
        }
        switch (k.env) {
            case "fog3" -> p.fog = 3;
            case "fog4" -> p.fog = 4;
            case "fog5" -> p.fog = 5;
            case "clear" -> p.fog = 1;
            case "ash" -> p.fog = 2;
            case "storm" -> p.fog = 4;
            case "dark" -> {
                p.night = true;
                p.dotDir = rnd.nextInt(4);
            }
            case "silent" -> p.silent = true;
            case "eye" -> p.puddleEye = true;
            case "crack" -> {
                if (Species.G31.implemented()) {
                    p.flood = true;
                    p.counts.merge(Species.G31, Math.max(3, p.total() / 4), Integer::sum);
                }
            }
            default -> {
            }
        }
        if (k.flag("dark")) {
            p.night = true;
            if (p.dotDir < 0) p.dotDir = rnd.nextInt(4);
        }
        if (k.has("echo") || k.has("noise")) p.silent = true;
        if (k.has("searchlight") && Species.G43.implemented()) {
            p.fog = 1;
            p.counts.keySet().removeIf(sp -> Director.bannedTogether(sp, Species.G43));
            p.searchlights = 2 + rnd.nextInt(2);
            p.lightDir = rnd.nextInt(4);
            p.counts.put(Species.G43, p.searchlights);
        }
        planFog = p.fog > 0 ? p.fog : -1;
    }

    /** 쉬는 시간 계획이 없던 라운드: 살아 있는 값만(안개). 나머지(배율, 방향, 목표, 장치)는 그대로 돈다. */
    private void postApply(RoundKind k) {
        int fog = switch (k.env) {
            case "fog3" -> 3;
            case "fog4", "storm" -> 4;
            case "fog5" -> 5;
            case "clear" -> 1;
            case "ash" -> 2;
            default -> -1;
        };
        if (k.has("searchlight")) fog = 1;
        planFog = fog;
        if (fog > 0) game.director.bossFog(fog);
        Director.Plan p = game.director.plan();
        if (p != null && p.lean >= 0) dirA = leanAngle(p.lean);
    }

    /** 감독 방향 번호(0 북 -Z, 1 동 +X, 2 남 +Z, 3 서 -X) → 가운데에서 본 각(라디안, 0 = +X). */
    static double leanAngle(int dir) {
        return switch (dir & 3) {
            case 0 -> -Math.PI / 2;
            case 1 -> 0;
            case 2 -> Math.PI / 2;
            default -> Math.PI;
        };
    }

    // ------------------------------------------------------------------ 라운드 중

    private void banner(RoundKind k) {
        bannerUntil = game.now() + Tune.BANNER_TICKS + Tune.LINE_TICKS;
        if (Tune.BANNER_TICKS > 0 && Hud.hasPhrase(k.phrase)) game.hud.notice(k.phrase, Tune.BANNER_TICKS);
        if (Hud.hasPhrase(k.linePhrase)) game.hud.notice(k.linePhrase, Tune.LINE_TICKS);
    }

    /** 감독이 낸 개체를 첫 틱에 보고(방향 옮기기, 장치, 표적), 추적하던 개체의 죽음을 장치에 알림. */
    private void watchMobs(long now) {
        for (Gabe g : new ArrayList<>(game.mobs.all())) {
            if (g.dead || !seen.add(g)) continue;
            seenSpecies.add(g.sp);
            if (!game.director.fromDirector(g)) continue;
            tracked.add(g);
            if (g.age <= 2) relocate(g);
            fx.onSpawn(g);
            goals.onSpawn(g);
        }
        for (Iterator<Gabe> it = tracked.iterator(); it.hasNext();) {
            Gabe g = it.next();
            if (!g.dead && !g.removed) continue;
            it.remove();
            if (g.dead) {
                fx.onDeath(g, !g.noReward);
                if (!g.noReward) goals.onKill(g);
            }
        }
        if (seen.size() > 4000) seen.removeIf(g -> g.dead || g.removed);
    }

    private void dirBegin(RoundKind k) {
        if (!k.dir.equals("lean") || dirA == 0) dirA = rnd.nextDouble() * Math.PI * 2;
        Director.Plan p = game.director.plan();
        if (k.dir.equals("lean") && p != null && p.lean >= 0) dirA = leanAngle(p.lean);
        // 두 자리 지키기는 두 원 쪽에서
        if (k.dir.equals("two") && goals.circles.size() >= 2) {
            Vector c0 = goals.circles.get(0).at;
            dirA = Math.atan2(c0.getZ() - Plaza.CZ - 0.5, c0.getX() - Plaza.CX - 0.5);
        }
        spinA = dirA;
        spinAt = game.now();
        backA = backAngle();
        switch (k.dir) {
            case "two" -> game.hud.waveFrom("ev_dir", 80, yawOf(dirA), yawOf(dirA + Math.PI));
            case "road" -> game.hud.waveFrom("ev_dir", 80, yawOf(-Math.PI / 2), yawOf(Math.PI / 2));
            case "spin" -> game.hud.waveFrom("rd_spin", 80, yawOf(spinA));
            case "lean" -> {
                if (!pre) game.hud.waveFrom("ev_dir", 80, yawOf(dirA));
            }
            default -> {
            }
        }
    }

    private void dirTick(long now) {
        if (cur.dir.equals("spin") && now - spinAt >= Tune.SPIN_EVERY) {
            spinAt = now;
            spinA += Math.PI / 2;
            game.hud.waveFrom("rd_spin", 60, yawOf(spinA));
        }
        if ((cur.dir.equals("back") || cur.dir.equals("noise")) && now % 20 == 0) backA = backAngle();
    }

    /** 사람들이 가장 안 보는 쪽(서 있는 사람 시선 평균의 반대, 사람이 없으면 무작위). */
    private double backAngle() {
        double x = 0, z = 0;
        for (Player p : game.players.standing()) {
            Vector d = p.getLocation().getDirection().setY(0);
            if (d.lengthSquared() < 1e-6) continue;
            d.normalize();
            x += d.getX();
            z += d.getZ();
        }
        if (x * x + z * z < 1e-4) return rnd.nextDouble() * Math.PI * 2;
        return Math.atan2(-z, -x);
    }

    private double jitter(double deg) {
        return (rnd.nextDouble() - 0.5) * 2 * Math.toRadians(deg);
    }

    /** 감독이 막 낸 개체를 이 라운드 방향으로 옮김(고리와 하늘에서 오는 종만, 보간 없이). */
    private void relocate(Gabe g) {
        String d = cur.dir;
        if (d.equals("ring") && !cur.has("wall") || g.sp.is(Role.BOSS) || g.sp.is(Role.BONUS) || g.sp == Species.G43 || g.sp == Species.G31 && game.director.flood()) return;
        if (g.sp.spawn != Species.Spawn.RING && g.sp.spawn != Species.Spawn.SKY) return;
        double ox = g.pos.getX() - Plaza.CX - 0.5, oz = g.pos.getZ() - Plaza.CZ - 0.5;
        double a0 = Math.atan2(oz, ox), r0 = Math.sqrt(ox * ox + oz * oz), a = a0, r = r0;
        Vector to = null;
        boolean rise = false;
        if (cur.has("wall")) d = "wall";
        switch (d) {
            case "wall" -> r = Tune.WALL_R;
            case "lean" -> {
                if (pre || rnd.nextDouble() >= 0.4) return;
                a = dirA + jitter(30);
            }
            case "two" -> a = (rnd.nextBoolean() ? dirA : dirA + Math.PI) + jitter(Tune.ARC_DEG);
            case "road" -> a = (rnd.nextBoolean() ? -Math.PI / 2 : Math.PI / 2) + jitter(Tune.ARC_DEG);
            case "spin" -> {
                if (rnd.nextDouble() >= 0.65) return;
                a = spinA + jitter(30);
            }
            case "back" -> a = backA + jitter(40);
            case "noise" -> {
                double na = fx.noiseAngle();
                a = (Double.isNaN(na) ? backA : na) + jitter(30);
            }
            case "near" -> {
                if (g.flying) return;
                r = Tune.NEAR_R + rnd.nextDouble() * 4;
                rise = true;
            }
            case "far" -> r = Tune.FAR_R;
            case "under" -> {
                if (g.flying) return;
                List<Player> ps = game.players.standing();
                if (ps.isEmpty()) return;
                Location l = ps.get(rnd.nextInt(ps.size())).getLocation();
                double b = rnd.nextDouble() * Math.PI * 2, rr = Tune.UNDER_R0 + rnd.nextDouble() * (Tune.UNDER_R1 - Tune.UNDER_R0);
                to = new Vector(l.getX() + Math.cos(b) * rr, 0, l.getZ() + Math.sin(b) * rr);
                if (Plaza.radius(to.getX(), to.getZ()) > Plaza.R_ROAM) return;
                rise = true;
            }
            case "truck" -> {
                if (g.flying) return;
                Vector c = Truck.center();
                double b = rnd.nextDouble() * Math.PI * 2, rr = Tune.TRUCK_R0 + rnd.nextDouble() * (Tune.TRUCK_R1 - Tune.TRUCK_R0);
                to = new Vector(c.getX() + Math.cos(b) * rr, 0, c.getZ() + Math.sin(b) * rr);
                rise = true;
            }
            default -> {
                return;
            }
        }
        if (to == null) to = new Vector(Plaza.CX + 0.5 + Math.cos(a) * r, 0, Plaza.CZ + 0.5 + Math.sin(a) * r);
        double floorOld, floorNew;
        try {
            floorOld = game.mobs.flow.floor(g.pos.getX(), g.pos.getZ());
            floorNew = game.mobs.flow.floor(to.getX(), to.getZ());
        } catch (RuntimeException e) {
            floorOld = floorNew = Plaza.FLOOR_Y;
        }
        to.setY(floorNew + (g.pos.getY() - floorOld));
        if (!game.mobs.relocate(g, to)) return;
        relocated++;
        if (rise) {
            Location l = g.loc();
            game.shotFx.hit(l.clone().add(0, 0.1, 0), org.bukkit.block.BlockFace.UP, org.bukkit.Material.DIRT, new Vector(0, 1, 0), true);
            game.mobs.lure(g, g.pos.clone(), Tune.RISE_HOLD);
            Props.soundAround(l, "trip.rise", Sound.BLOCK_ROOTED_DIRT_BREAK, 1f, 0.2f, 0.6f, 24, -1);
        }
    }

    private static double yawOf(double ang) {
        return Math.toDegrees(Math.atan2(-Math.cos(ang), Math.sin(ang)));
    }

    private void tempoBegin(RoundKind k) {
        RoundKind.Tempo t = table.tempo(k.tempo);
        if (t.hold > 0) game.director.delayAnnounce(t.hold);
    }

    /** 박자: 파도 사이 멈춤, 마지막 몰아치기, 끝없이 채우기. */
    private void tempoTick(long now) {
        RoundKind.Tempo t = table.tempo(cur.tempo);
        Director dr = game.director;
        int total = Math.max(1, dr.total()), sp = dr.spawned();
        if (t.pulses > 1 && pulsesDone < t.pulses - 1 && sp >= total * (pulsesDone + 1) / t.pulses) {
            pulsesDone++;
            dr.delayAnnounce(t.gap);
        }
        if (t.finalShare > 0 && !finaleDone && sp >= total * (1 - t.finalShare)) {
            finaleDone = true;
            dr.delayAnnounce(Tune.FINALE_HOLD);
            addStream((int) Math.round(total * Tune.FINALE_EXTRA));
        }
        if (goals.streaming() && now % 20 == 0) {
            double want = Math.max(Tune.STREAM_MIN, dr.aliveGoal() * Tune.STREAM_SHARE);
            int q = dr.queued();
            if (q < want) addStream((int) Math.min(Tune.STREAM_STEP, Math.ceil(want - q)));
        }
    }

    /** 감독 대기열 끝에 이번 계획의 몰려오는 종을 몫대로 n 더함. */
    private void addStream(int n) {
        if (n <= 0) return;
        Director.Plan p = game.director.plan();
        List<Species> sps = new ArrayList<>();
        List<Integer> w = new ArrayList<>();
        int sum = 0;
        if (p != null) for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            Species sp = e.getKey();
            if (sp.spawn == Species.Spawn.PLACED || special(p, sp) || !sp.implemented()) continue;
            sps.add(sp);
            w.add(e.getValue());
            sum += e.getValue();
        }
        if (sps.isEmpty()) {
            Species s = cheapSwarm();
            if (s == null) return;
            sps.add(s);
            w.add(1);
            sum = 1;
        }
        for (int i = 0; i < n; i++) {
            int r = rnd.nextInt(Math.max(1, sum));
            for (int j = 0; j < sps.size(); j++) {
                r -= w.get(j);
                if (r < 0 || j == sps.size() - 1) {
                    game.director.addQueue(sps.get(j), 1);
                    break;
                }
            }
        }
        topped += n;
    }

    // ------------------------------------------------------------------ 보상, 끝

    private void reward(RoundKind k, int n) {
        List<Player> up = new ArrayList<>();
        for (Player p : game.players.inPlay()) {
            Life l = game.players.get(p).life;
            if (l == Life.ALIVE || l == Life.DOWNED) up.add(p);
        }
        switch (k.reward) {
            case "box" -> {
                for (Player p : up) game.shop.giveFreeBox(p, 1);
                note("box", up.size());
            }
            case "aug" -> {
                for (Player p : up) game.augments.offer(p, 1);
                note("aug", up.size());
            }
            case "repair" -> {
                Truck t = trip.truck();
                if (t != null) {
                    t.repairFrac(0.08);
                    note("repair", 1);
                }
            }
            case "ammo" -> {
                for (Player p : up) if (game.players.get(p).life == Life.ALIVE) game.guns.refillAll(p);
                note("ammo", up.size());
            }
            case "revive" -> {
                reviveHalfRound = n;
                note("revive", 1);
            }
            case "part" -> {
                Truck t = trip.truck();
                if (t != null) {
                    t.grantPart();
                    note("part", 1);
                }
            }
            default -> {
            }
        }
        if (goals.inTime()) note("timed", 1);
    }

    private void note(String k, int v) {
        given.merge(k, v, Integer::sum);
    }

    /** 라운드 장치와 목표를 치움(all 이면 특수 인스턴스까지 닫음). */
    private void endRound(boolean all) {
        closeSpecial();
        goals.stop();
        fx.stop();
        cur = null;
        roundOpen = false;
        bannerUntil = 0;
        tracked.clear();
        if (all) {
            seen.clear();
            reviveHalfRound = -1;
        }
    }

    // ------------------------------------------------------------------ 시험 훅

    @Override
    public String test(Player p, String[] a) {
        if (special != null) {
            String r = special.test(p, a);
            if (r != null) return r;
        }
        switch (a[1]) {
            case "라운드종류" -> {
                String sub = a.length > 3 ? a[3] : "상태";
                switch (sub) {
                    case "상태" -> {
                        return "[라운드] " + status() + " last=" + (ended == null ? "-" : ended.key + (endedOk ? ":ok" : ":fail")) + " given=" + given
                            + " seen=" + seenSpecies.size() + " src=" + table.source + " n=" + table.kinds.size();
                    }
                    case "목록" -> {
                        int act = a.length > 4 ? Integer.parseInt(a[4]) : trip.act();
                        StringBuilder sb = new StringBuilder("[라운드] 막 " + act + ":");
                        for (RoundKind k : table.kinds.values()) if (k.in(act)) sb.append(' ').append(k.key);
                        return sb.toString();
                    }
                    case "기록" -> {
                        return "[라운드] " + history;
                    }
                    case "다시" -> {
                        RoundKind.reload();
                        return "[라운드] 표 다시 읽음(다음 판부터: 지금 Rounds 는 만들 때 읽은 표) " + RoundKind.table().kinds.size();
                    }
                    case "끔" -> {
                        forced = null;
                        return "[라운드] 정한 것 지움";
                    }
                    case "보기" -> {
                        RoundKind k = a.length > 4 ? table.get(a[4]) : cur;
                        return k == null ? "모르는 종류" : "[라운드] " + k + " acts=" + k.act0 + ".." + k.act1 + " mods=" + k.modKeys() + (k.change == null ? "" : " 바뀜=" + k.change);
                    }
                    default -> {
                        boolean now = a.length > 4 && a[4].equals("지금");
                        if (!sub.startsWith("special:") && table.get(sub) == null) return "모르는 종류 " + sub + "(라운드종류 x 목록)";
                        if (now && game.state == Game.State.WAVE && !sub.startsWith("special:")) {
                            endRound(false);
                            RoundKind k = table.get(sub);
                            cur = k;
                            pre = false;
                            spec = "test";
                            beginRound(game.wave);
                            return "[라운드] 지금부터 " + k + " (계획은 살아 있는 값만)";
                        }
                        forced = sub;
                        return "[라운드] 다음 라운드 " + sub;
                    }
                }
            }
            case "라운드목표" -> {
                String sub = a.length > 3 ? a[3] : "상태";
                if (sub.equals("상태")) return "[라운드] " + goals.status();
                if (sub.equals("알")) return "[라운드] " + fx.breakEgg() + " " + goals.status();
                if (sub.equals("가기")) {
                    // 그 사람을 목표 자리로(원, 주울 것, 알, 등불, 미끼 차례)
                    if (p == null) return "사람 없음";
                    Vector to = null;
                    for (Goals.Circle c : goals.circles) if (!c.done) {
                        to = c.at.clone();
                        break;
                    }
                    if (to == null) for (Goals.Pickup it : goals.pickups) if (!it.delivered && it.carrier == null) {
                        to = it.at.clone();
                        break;
                    }
                    if (to == null) to = fx.spot();
                    if (to == null && a.length > 4 && a[4].equals("트럭")) to = Truck.center().clone().add(new Vector(0, 0, 4.5));
                    if (to == null) return "갈 곳 없음";
                    p.teleport(new Location(game.world, to.getX(), Plaza.FLOOR_Y + 0.1, to.getZ(), p.getLocation().getYaw(), 20));
                    return String.format(Locale.ROOT, "[라운드] 감 %.1f %.1f", to.getX(), to.getZ());
                }
                if (sub.equals("트럭")) {
                    if (p == null) return "사람 없음";
                    Vector to = Truck.center().clone().add(new Vector(0, 0, 4.2));
                    p.teleport(new Location(game.world, to.getX(), Plaza.FLOOR_Y + 0.1, to.getZ(), 180, 10));
                    return "[라운드] 트럭 뒤로 감 " + goals.status();
                }
                String r = goals.testStep(sub);
                return r == null ? "라운드목표 <이름|x> <상태|채움|싣기|표적|성공|실패|알|가기|트럭>" : "[라운드] " + r;
            }
            case "라운드줍기" -> {
                // 줍기 시험: 그 사람이 가장 가까운 것을 집음(돕기와 같은 길)
                if (p == null) return "사람 없음";
                for (Goals.Pickup it : goals.pickups) {
                    if (it.delivered || it.carrier != null) continue;
                    p.teleport(new Location(game.world, it.at.getX() + 0.5, Plaza.FLOOR_Y + 0.1, it.at.getZ(), p.getLocation().getYaw(), 0));
                    return "[라운드] 집음=" + goals.help(p);
                }
                return "주울 것 없음";
            }
            case "특수" -> {
                if (a.length < 5) return "특수 x <키> <시작|끝>";
                String key = a[3], what = a[4];
                if (what.equals("끝")) {
                    closeSpecial();
                    return "[라운드] 특수 닫음";
                }
                forced = "special:" + key;
                RoundKind.SpecialDef d = table.special(key);
                boolean has = d != null && specialClass(d) != null;
                return "[라운드] 다음 라운드 특수 " + key + (has ? "" : " (클래스 없음: 보통 라운드로 떨어짐)");
            }
            default -> {
                return null;
            }
        }
    }
}
