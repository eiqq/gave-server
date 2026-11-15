package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

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
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.trip.Goals;
import kr.gave.trip.Rounds;
import kr.gave.trip.Scenery;
import kr.gave.trip.SpecialRound;
import kr.gave.trip.Theme;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * 특수 라운드 바탕(원정.md 5.3, 5.4절, 묶음 8 trip_special_a). 특수 한 가지는 이것을 이어받아 {@link #begin()}, {@link #step(long)}, {@link #end()} 와
 * 필요한 덮어쓰기(끝 조건 {@link #check(boolean)}, 감독 계획 {@link #plan(Director.Plan)}, 배율 {@link #mulFor(String)}, 감독이 낸 개체 {@link #spawned(Gabe)},
 * HUD 값 {@link #hudMore}, 시험 {@link #testStep})만 쓴다.
 *
 * <p>바탕이 하는 일: 이름 띠(글귀 sp_&lt;키&gt;, sp_&lt;키&gt;_l), 목표 막대(Goals.beginCustom: 막대 값은 HUD 값 goal.* 로, Rounds 의 보통 라운드와 같은 키),
 * 끝(finish: 남은 떼가 물러나고 RETREAT_TICKS 뒤 쉬는 시간), 끝없이 채우기(streaming: 감독 대기열), 놓은 디스플레이와 건 갈고리 치우기, 안개 단계 되돌리기,
 * 막마다 조금씩 더 어둡게(Tune.DIM_ACT), 막마다 더 센 게이브(Tune.HP_ACT, DMG_ACT, SPEED_ACT), 시험 훅 '특수시험'.
 * 알림은 모두 HUD(글귀 키)로: 액션바, 타이틀을 쓰지 않는다. 글귀는 data/phrases/trip_special_a.json.
 */
public abstract class Special implements SpecialRound {

    /** 숫자 한곳(특수 12가지, 처음 값: 파워커브와 게임 안 시험이 맞춘다). 배열은 막 1..4 차례. */
    public static final class Tune {
        // ---------------------------------------------------------------- 모두
        /** 특수 라운드 동안 게이브 체력, 사람이 받는 피해, 빠르기 배율(막마다). */
        public static double[] HP_ACT = {1.10, 1.25, 1.40, 1.60}, DMG_ACT = {1.10, 1.20, 1.30, 1.45}, SPEED_ACT = {1.05, 1.08, 1.12, 1.15};
        /** 특수 라운드 동안 테마를 한 단계 더 어둡게(막마다: 뒤 막일수록 어둡게). */
        public static double[] DIM_ACT = {0.0, 0.12, 0.25, 0.40};
        /** 끝 조건이 안 오면 놓침(초). */
        public static int MAX_SECONDS = 300;
        /** 끝없이 채우기: 감독 대기열에 동시 수 목표의 이 몫이 늘 남게(20틱마다 STREAM_STEP 까지). */
        public static double STREAM_SHARE = 0.6;
        public static int STREAM_MIN = 8, STREAM_STEP = 6;
        /** 이름 띠 틱, 한 줄 틱. */
        public static int BANNER_TICKS = 50, LINE_TICKS = 60;

        // ---------------------------------------------------------------- 1 추격전
        /** 달리는 초(막마다), 흐름 빠르기(블록/틱), 안개 단계. */
        public static int[] CHASE_SECONDS = {100, 100, 110, 130};
        public static double CHASE_SPEED = 1.2;
        public static int CHASE_FOG = 4;
        /** 판(트레일러 둘): 트럭 뒤 변에서 길이, 폭의 반(가운데 x = 트럭 가운데). */
        public static double PLAT_LEN = 22, PLAT_HALF = 4.5; // 2026-11-12 사용자: 30명 기준 넓힘(폭 5 → 9)
        /** 트레일러 모델(trip/trailer) 높이 배율, 도는 각(도: 연결 고리가 트럭 쪽). */
        public static double TRAILER_SY = 2.2, TRAILER_YAW = 180;
        /** 매달림: 판 바깥 변에서 이 거리 안에 들어온 땅 몹, 자리 수, 기어오르기까지 틱, 매달린 동안 초당 트럭 비율 피해. */
        public static double HANG_EDGE = 1.1, HANG_TRUCK = 0.004;
        public static int HANG_SLOTS = 16, HANG_TICKS = 40;
        /** 길 사건 간격과 예고(틱). */
        public static int EVENT_EVERY = 300, EVENT_WARN = 80;
        /** 얼굴 벽: 마리 수(막마다), 다가오는 틱, 못 부순 만큼 트럭 비율 피해(x 남은 몫 x 범퍼 ramMul). */
        public static int[] WALL_N = {7, 9, 11, 13};
        public static int WALL_TICKS = 120;
        public static double WALL_FRAC = 0.06, WALL_FROM = 30;
        /** 끊긴 다리: 떠오르는 높이, 트럭 비율 피해, 가장자리에서 넘어지는 거리, 묶이는 틱. */
        public static double GAP_LIFT = 1.2, GAP_FRAC = 0.02, GAP_EDGE = 0.9;
        public static int GAP_ROOT = 30;
        /** 굽은 길: 흐름이 도는 각(도), 옆으로 미는 힘(틱마다), 틱. 좁은 굴: 안개 단계, 틱. */
        public static double BEND_DEG = 20, BEND_PUSH = 0.07;
        public static int BEND_TICKS = 90, TUNNEL_FOG = 5, TUNNEL_TICKS = 60;
        /** 떨어진 사람: 끈 게이브를 이 틱 안에 잡으면 돌아옴, 아니면 죽음. */
        public static int FALL_TICKS = 120;
        /** 달리는 게이브 빠르기 배율, 감독 계획 수 배율, 뒤쪽 호 반각(도), 나오는 반지름. */
        public static double CHASE_MOB_SPEED = 1.3, CHASE_UNITS = 0.85, CHASE_ARC = 35, CHASE_R0 = 30, CHASE_R1 = 36;

        // ---------------------------------------------------------------- 2 고장
        /** 한 사람 초당 수리 몫, 겹치는 사람 수, 보닛 반지름, 트럭이 맞을 때 깎이는 몫, 등이 깜빡이며 돌아오는 몫, 트럭 노림 배율. */
        public static double FIX_RATE = 0.014, HOOD_R = 3.0, FIX_KNOCK = 0.012, FIX_LIGHT = 0.5, BREAK_SHARE = 2.0;
        public static int FIX_MAX = 3;

        // ---------------------------------------------------------------- 3 주유
        /** 통 수, 놓이는 반지름, 줍는 거리, 붓는 거리, 붓는 틱, 든 사람 느려짐(%), 터지는 반경, 터짐 피해(게이브만), 다시 놓이는 틱, 든 사람을 노리는 반경. */
        public static int FUEL_N = 4, FUEL_POUR_TICKS = 60, FUEL_RESPAWN = 400;
        public static double FUEL_R0 = 34, FUEL_R1 = 44, FUEL_PICK_R = 2.4, FUEL_DELIVER_R = 3.8, FUEL_SLOW = 30, FUEL_BOOM_R = 5, FUEL_BOOM_DMG = 140,
            FUEL_FORCE_R = 20, FUEL_HIT_R = 0.7;

        // ---------------------------------------------------------------- 4 좁은 다리
        /** 띠 폭의 반(길 방향 x), 떨어지는 사람 피해, 감독 계획 수 배율, 난간 간격. */
        public static double BRIDGE_HALF = 7, BRIDGE_FALL_DMG = 4, BRIDGE_UNITS = 1.3, RAIL_GAP = 6;

        // ---------------------------------------------------------------- 5 정전된 밤
        /** 어둡게(막마다), 공짜 조명탄, 조명탄 값 배율, 총구 불꽃이 비추는 반지름과 틱, 체력 배율. */
        public static double[] BLACK_DIM = {0.85, 0.9, 0.95, 1.0};
        public static int FLARE_FREE = 1, MUZZLE_TICKS = 6;
        public static double FLARE_PRICE = 0.5, MUZZLE_R = 7, BLACK_HP = 1.15;
        /** 어둠 속 몸 밝기(블록, 하늘). */
        public static int DARK_BLOCK = 1, DARK_SKY = 0;

        // ---------------------------------------------------------------- 6 모래폭풍
        /** 안개 단계, 바람이 바뀌는 틱, 마주 봄 각(도), 바람 쪽 게이브 빠르기, 바람을 마주 보고 멀리 쏜 피해 배율과 거리, (SAND_STING 그늘 밖 초당 피해는 2026-11-13 사용자 '모래폭풍 1뎀씩 주는 그거는 삭제해'로 안 씀), 바람 그늘 길이와 폭의 반, 맞바람 느려짐(%). */
        public static int SAND_FOG = 4, WIND_EVERY = 500;
        public static double WIND_ARC = 60, WIND_SPEED = 1.35, SAND_DMG = 0.55, SAND_NEAR = 10, SAND_STING = 0.6, SHADE_LEN = 7, SHADE_HALF = 5.0, SAND_SLOW = 18;

        // ---------------------------------------------------------------- 7 숨죽이기
        /** 지나가는 초(막마다), 소음: 한 발, 달리기(틱마다), 폭발, 초당 줄어듦, 웅크린 사람마다 초당 더 줄어듦, 가득. 가득 찰 때 트럭 비율 피해. */
        public static int[] HUSH_SECONDS = {60, 60, 70, 80};
        public static double NOISE_SHOT = 1.0, NOISE_SPRINT = 0.25, NOISE_BLAST = 12, NOISE_DECAY = 1.5, NOISE_CROUCH = 0.8, NOISE_MAX = 100, NOISE_TRUCK = 0.05;
        /** 감독 계획 수, 체력 배율, 거대한 것 배율과 높이. */
        public static double HUSH_UNITS = 0.6, HUSH_HP = 0.7, GIANT_SCALE = 16, GIANT_ALT = 24;

        // ---------------------------------------------------------------- 8 보급 낙하
        /** 상자 수, 간격(틱), 가짜 차례(0부터), 떨어지는 반지름, 떼가 상자로 쏠리는 반지름. */
        public static int DROP_N = 6, DROP_EVERY = 400;
        public static int[] DROP_FAKE = {1, 4};
        public static double DROP_R0 = 16, DROP_R1 = 38, DROP_LURE_R = 26;

        // ---------------------------------------------------------------- 9 떼몰이
        /** 지나가는 수, 예고 틱, 지나간 뒤 쉬는 틱, 마리 수(차례마다, 막마다 + HERD_ACT), 띠 폭, 앞줄 수, 빠르기(블록/틱), 받힘 피해, 트럭 비율 피해, 갈라질 때 옆으로 비키는 거리. */
        public static int HERD_PASSES = 3, HERD_WARN = 160, HERD_GAP = 200, HERD_HEADS = 12, HERD_ACT = 8;
        public static int[] HERD_N = {36, 52, 68};
        public static double[] HERD_W = {8, 12, 16};
        public static double HERD_SPEED = 0.55, HERD_DMG = 9, HERD_TRUCK = 0.08, HERD_SPLIT = 13, HERD_FROM = 70;

        // ---------------------------------------------------------------- 10 끌려간 동료
        /** 끌려가는 몫(사람 수), 많아야, 고치 반지름, 초, 고치 체력(막마다), 지키는 무리 수(+ 막). */
        public static double HOST_SHARE = 0.1, HOST_R0 = 40, HOST_R1 = 46, COCOON_R = 0.95;
        public static int HOST_MAX = 3, HOST_SECONDS = 90, GUARDS = 5;
        public static double[] COCOON_HP = {300, 450, 600, 800};

        // ---------------------------------------------------------------- 11 사격장
        /** 초, 줄 간격(틱), 줄 마리, 붉은 실눈 차례(넷에 하나), 줄 반지름, 달리는 빠르기, 금빛 보상 배율, 덤빌 때 빠르기와 피해, 감독 계획 수. */
        public static int GALLERY_SECONDS = 60, LINE_EVERY = 120, LINE_N = 6, LINE_RED = 4;
        public static double LINE_R0 = 20, LINE_R1 = 34, RUN_SPEED = 0.34, GOLD_REWARD = 3.0, MAD_SPEED = 1.5, MAD_DMG = 1.6, GALLERY_UNITS = 0.35;

        // ---------------------------------------------------------------- 12 흩어짐
        /** 흩어지는 반지름, 안개 단계, 경적 간격, 모였다고 보는 반지름, 혼자인 사람(이 반지름 안에 동료 없음), 혼자를 노리는 반지름. */
        public static double SCATTER_R0 = 30, SCATTER_R1 = 44, GATHER_R = 12, LONER_R = 9, LONER_FORCE_R = 22;
        public static int SCATTER_FOG = 5, HORN_EVERY = 200;
        /** 모였다고 치는 몫(서 있는 사람 가운데 원 안에 든 몫)과 기다리는 끝(초): 원 밖에서 멈춘 한 사람이 라운드를 붙잡지 않게. */
        public static double GATHER_SHARE = 0.8;
        public static int GATHER_SECONDS = 60;

        private Tune() {
        }

        static double act(double[] a, int act) {
            return a[Math.max(0, Math.min(a.length - 1, act - 1))];
        }

        static int act(int[] a, int act) {
            return a[Math.max(0, Math.min(a.length - 1, act - 1))];
        }
    }

    protected final Game game;
    protected final Trip trip;
    protected final Rounds rounds;
    protected final Random rnd = new Random();

    /** 이 특수 키(chase ...), 라운드, 막, 연 틱. */
    protected String key;
    protected int round, act = 1;
    protected long openAt;
    protected boolean opened;
    /** 끝남(finish), 성공, 물러남이 끝나는 틱. */
    protected boolean ended, endedOk;
    private long endAt = -1;
    /** 감독 대기열을 끝없이 채우나(끝 조건이 시간, 목표인 특수). */
    protected boolean streaming;
    /** 끝을 특수가 쥠(시간, 거리, 목표): 감독이 다 잡았다 해도 끝나지 않는다(그동안 streaming 이 채움). */
    protected boolean ownEnd;
    /** 이 특수가 더 원하는 어둡기(0..1, 막 어둡기와 큰 쪽). */
    protected double dimWant;
    private long bannerUntil;
    private int fogBefore = -1;
    private boolean fogSet;
    private String cued;
    private final List<Display> displays = new ArrayList<>();
    private final List<Object> scaleKeys = new ArrayList<>(), forceKeys = new ArrayList<>(), lampKeys = new ArrayList<>();
    private final List<Gabe> own = new ArrayList<>();
    private final Set<Gabe> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<kr.gave.mob.Mobs.Shootable> shootables = new ArrayList<>();

    protected Special(Game game, Trip trip, Rounds rounds) {
        this.game = game;
        this.trip = trip;
        this.rounds = rounds;
    }

    // ------------------------------------------------------------------ 이어받는 곳

    /** 연다(맵에 놓고 갈고리를 검). false 면 못 엶(보통 라운드로). */
    protected abstract boolean begin();

    /** 웨이브 중 매 틱(끝나기 전에만). */
    protected abstract void step(long now);

    /** 치움(놓은 것은 바탕이 치우니 따로 건 것만). 여러 번 불려도 된다. */
    protected void end() {
    }

    /** 끝난 뒤 물러나는 동안 매 틱(흐름을 느리게 멈추기 같은 것). */
    protected void after(long now) {
    }

    /** 끝 판정(끝나기 전, 매 틱). done = 감독 판정(다 잡음). 기본은 다 잡기. true 면 성공으로 끝. */
    protected boolean check(boolean done) {
        return !ownEnd && done;
    }

    /** 감독이 낸 개체를 첫 틱에 봄(옮기기, 표시). */
    protected void spawned(Gabe g) {
    }

    /** 이 특수만의 배율(모르면 1). 바탕의 막 배율에 곱해진다. */
    protected double mulFor(String k) {
        return 1;
    }

    /** 이 특수만의 HUD 값. */
    protected void hudMore(Player p, Map<String, Object> out) {
    }

    /** 상태 글 덧붙임. */
    protected String detail() {
        return "";
    }

    /** 시험 훅 '특수시험 x &lt;행동&gt;'(바탕이 모르는 행동). */
    protected String testStep(Player p, String what, String[] a) {
        return null;
    }

    /** 목표 아이콘(HUD goal.icon). */
    protected String icon() {
        return "ti_goal_custom";
    }

    // ------------------------------------------------------------------ SpecialRound

    @Override
    public final boolean open(int n, String k) {
        close();
        key = k;
        round = n;
        act = Math.max(1, Math.min(4, trip.act()));
        openAt = game.now();
        ended = endedOk = false;
        endAt = -1;
        streaming = false;
        ownEnd = false;
        fogSet = false;
        dimWant = 0;
        seen.clear();
        rnd.setSeed(trip.seed * 6151 + n * 31L + k.hashCode());
        fogBefore = game.director.fog();
        rounds.goals().beginCustom(icon());
        boolean ok;
        try {
            ok = begin();
        } catch (RuntimeException e) {
            cleanup();
            throw e;
        }
        if (!ok) {
            cleanup();
            rounds.goals().stop();
            return false;
        }
        opened = true;
        bannerUntil = game.now() + Tune.BANNER_TICKS + Tune.LINE_TICKS;
        say("sp_" + key, Tune.BANNER_TICKS);
        say("sp_" + key + "_l", Tune.LINE_TICKS);
        trip.log("특수 " + key + " 엶 라운드 " + n + " 막 " + act);
        return true;
    }

    @Override
    public final void close() {
        if (!opened && displays.isEmpty() && own.isEmpty() && scaleKeys.isEmpty() && forceKeys.isEmpty() && lampKeys.isEmpty() && shootables.isEmpty()) return;
        try {
            end();
        } finally {
            cleanup();
            if (opened) trip.log("특수 " + key + " 닫음" + (ended ? endedOk ? " 성공" : " 놓침" : ""));
            opened = false;
        }
    }

    private void cleanup() {
        for (Display d : displays) if (d != null && d.isValid()) d.remove();
        displays.clear();
        for (Object k : scaleKeys) game.mobs.unscale(k);
        scaleKeys.clear();
        for (Object k : forceKeys) game.mobs.unforce(k);
        forceKeys.clear();
        Truck t = trip.truck();
        for (Object k : lampKeys) if (t != null) t.lampOff(k, false);
        lampKeys.clear();
        for (kr.gave.mob.Mobs.Shootable s : shootables) game.mobs.removeShootable(s);
        shootables.clear();
        for (Gabe g : own) if (!g.dead && !g.removed) g.remove();
        own.clear();
        seen.clear();
        Theme th = trip.part(Theme.class);
        if (th != null && opened) th.dim(0, 1);
        // 웨이브 중에 닫히면 연 때의 단계로, 그 밖(쉬는 시간, 판 끝, 정리)에는 막의 기본으로: 연 때의 감독 안개 사건이 쉬는 시간에 남지 않게
        if (fogBefore >= 0 && fogSet) game.director.bossFog(game.state == Game.State.WAVE ? fogBefore : th != null ? th.fogBase() : 0);
        fogSet = false;
        fogBefore = -1;
        if (cued != null && cued.equals(game.music.cue())) game.music.uncue();
        cued = null;
        streaming = false;
    }

    @Override
    public boolean roundDone(boolean done) {
        if (!opened) return done;
        long now = game.now();
        if (ended) return now >= endAt;
        if (now - openAt > Tune.MAX_SECONDS * 20L) {
            finish(false);
            return false;
        }
        if (check(done)) {
            // 다 잡기 꼴: 남은 떼가 없으니 물러남 없이 바로 끝(완료 알림은 Late 바탕의 특수와 같이)
            say("rd_done", 60);
            ended = endedOk = true;
            endAt = now;
            return true;
        }
        return false;
    }

    @Override
    public void beginBreak(int n) {
        if (opened && endedOk) reward(n);
    }

    @Override
    public void tick() {
        if (!opened || game.state != Game.State.WAVE) return;
        long now = game.now();
        for (Gabe g : game.mobs.all()) {
            if (g.dead || !seen.add(g)) continue;
            if (g.age <= 2 && game.director.fromDirector(g)) {
                try {
                    spawned(g);
                } catch (RuntimeException e) {
                    trip.log("특수 " + key + " spawned 오류: " + e);
                }
            }
        }
        if (seen.size() > 4000) seen.removeIf(g -> g.dead || g.removed);
        own.removeIf(g -> g.dead || g.removed);
        if (!ended) {
            step(now);
            if (streaming && now % 20 == 0) topUp();
        } else after(now);
        if (now % 20 == 0) {
            Theme th = trip.part(Theme.class);
            double d = Math.max(Tune.act(Tune.DIM_ACT, act), dimWant);
            if (th != null && d > 0) th.dim(d, 40);
        }
    }

    @Override
    public void gameOver() {
        close();
    }

    @Override
    public void reset() {
        close();
    }

    @Override
    public double mul(String k) {
        double m = mulFor(k);
        if (!opened) return m;
        switch (k) {
            case "hp" -> m *= Tune.act(Tune.HP_ACT, act);
            case "dmg" -> m *= Tune.act(Tune.DMG_ACT, act);
            case "speed" -> m *= Tune.act(Tune.SPEED_ACT, act);
            default -> {
            }
        }
        return m;
    }

    @Override
    public Object ask(String k) {
        return switch (k) {
            case "goal" -> "custom";
            case "disp" -> displays();
            case "specialKey" -> key;
            default -> null;
        };
    }

    @Override
    public void hud(Player p, Map<String, Object> out) {
        if (!opened) return;
        out.put("sp.key", key);
        out.put("sp.name", "sp_" + key);
        out.put("sp.line", "sp_" + key + "_l");
        // 보통 라운드와 같은 키(라운드 이름 띠: trip_special_b 와 같은 꼴)
        out.put("round.key", "sp_" + key);
        out.put("round.name", "sp_" + key);
        out.put("round.line", "sp_" + key + "_l");
        long left = bannerUntil - game.now();
        if (left > 0) {
            out.put("sp.show", (int) left);
            out.put("round.show", (int) left);
        }
        Goals g = rounds.goals();
        out.put("goal.kind", "custom");
        double f = g.frac();
        if (f >= 0) out.put("goal.frac", f);
        String t = g.text();
        if (t != null) out.put("goal.text", t);
        out.put("goal.fill", g.filling());
        out.put("goal.icon", icon());
        if (g.met()) out.put("goal.met", true);
        if (g.failed()) out.put("goal.failed", true);
        hudMore(p, out);
    }

    @Override
    public String status() {
        if (!opened) return null;
        Goals g = rounds.goals();
        StringBuilder sb = new StringBuilder("sp=").append(key).append(ended ? endedOk ? ":ok" : ":fail" : ":on");
        double f = g.frac();
        if (f >= 0) sb.append(String.format(Locale.ROOT, " spgoal=%.2f", f));
        String t = g.text();
        if (t != null) sb.append(':').append(t);
        String d = detail();
        if (d != null && !d.isEmpty()) sb.append(' ').append(d);
        sb.append(" spdisp=").append(displays());
        return sb.toString();
    }

    @Override
    public String test(Player p, String[] a) {
        if (!a[1].equals("특수시험")) return null;
        String what = a.length > 3 ? a[3] : "상태";
        if (!opened) return "[특수] 열린 특수 없음";
        switch (what) {
            case "상태" -> {
                return "[특수] " + status();
            }
            case "성공" -> {
                finish(true);
                return "[특수] 성공 " + status();
            }
            case "실패" -> {
                finish(false);
                return "[특수] 놓침 " + status();
            }
            default -> {
                String r = testStep(p, what, a);
                return r == null ? "[특수] 모르는 행동 " + what + "(" + key + ")" : "[특수] " + r + " | " + status();
            }
        }
    }

    // ------------------------------------------------------------------ 끝, 보상

    /** 끝냄: 남은 떼가 물러나고(Goals) RETREAT_TICKS 뒤 쉬는 시간. ok 면 성공 알림, 아니면 놓침 알림. */
    protected void finish(boolean ok) {
        if (ended) return;
        ended = true;
        endedOk = ok;
        endAt = game.now() + Rounds.Tune.RETREAT_TICKS;
        streaming = false;
        rounds.finish(ok);
        try {
            finished(ok);
        } catch (RuntimeException e) {
            trip.log("특수 " + key + " finished 오류: " + e);
        }
        trip.log("특수 " + key + (ok ? " 성공" : " 놓침"));
    }

    /** 끝난 직후(놓은 것 거두기 같은 것). */
    protected void finished(boolean ok) {
    }

    /** 성공한 라운드의 쉬는 시간이 열릴 때. */
    protected void reward(int n) {
    }

    // ------------------------------------------------------------------ 끝없이 채우기, 계획

    /** 감독 대기열이 동시 수 목표의 STREAM_SHARE 아래면 이번 계획의 몰려오는 종으로 채움. */
    private void topUp() {
        Director dr = game.director;
        double want = Math.max(Tune.STREAM_MIN, dr.aliveGoal() * Tune.STREAM_SHARE);
        int q = dr.queued();
        if (q >= want) return;
        int n = (int) Math.min(Tune.STREAM_STEP, Math.ceil(want - q));
        List<Species> sps = new ArrayList<>();
        Director.Plan p = dr.plan();
        if (p != null) for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            Species sp = e.getKey();
            if (sp.spawn == Species.Spawn.PLACED || sp.is(Role.BONUS) || !sp.implemented() || sp.spawn == Species.Spawn.DERIVED) continue;
            for (int i = 0; i < Math.min(6, e.getValue()); i++) sps.add(sp);
        }
        if (sps.isEmpty()) {
            Species s = rounds.cheapSwarm();
            if (s == null) return;
            sps.add(s);
        }
        for (int i = 0; i < n; i++) dr.addQueue(sps.get(rnd.nextInt(sps.size())), 1);
    }

    /** 계획의 몰려오는 종 수에 곱함(미리 깔리는 종, 보상 종 빼고). */
    protected static void scaleUnits(Director.Plan p, double m) {
        if (p == null || Math.abs(m - 1) < 1e-6) return;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            Species sp = e.getKey();
            if (sp.spawn == Species.Spawn.PLACED || sp.is(Role.BONUS)) continue;
            e.setValue(Math.max(1, (int) Math.round(e.getValue() * m)));
        }
    }

    // ------------------------------------------------------------------ 도우미: 사람, 자리

    protected List<Player> standing() {
        return game.players.standing();
    }

    protected boolean alive(Player p) {
        PState s = game.players.get(p);
        return s != null && s.life == Life.ALIVE;
    }

    protected static Vector center() {
        return new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
    }

    /** 가운데에서 각 a(라디안, 0 = 동 +x, PI/2 = 남 +z), 반지름 r 인 바닥 자리. */
    protected static Vector ring(double a, double r) {
        return new Vector(Plaza.CX + 0.5 + Math.cos(a) * r, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * r);
    }

    protected static double angleOf(double x, double z) {
        return Math.atan2(z - Plaza.CZ - 0.5, x - Plaza.CX - 0.5);
    }

    /** HUD 화살촉 yaw(마크 yaw: 0 남, 90 서, 180 북, 270 동)로. */
    protected static double yawOf(double a) {
        return Math.toDegrees(Math.atan2(-Math.cos(a), Math.sin(a)));
    }

    protected static double wrap(double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }

    protected Location loc(Vector v, double up) {
        return new Location(game.world, v.getX(), Plaza.FLOOR_Y + up, v.getZ());
    }

    /** 보간 없이 옮김(땅 몹 높이는 바닥 차이만큼, 하늘 몹은 그대로). */
    protected boolean move(Gabe g, Vector to) {
        double fo, fn;
        try {
            fo = game.mobs.flow.floor(g.pos.getX(), g.pos.getZ());
            fn = game.mobs.flow.floor(to.getX(), to.getZ());
        } catch (RuntimeException e) {
            fo = fn = Plaza.FLOOR_Y;
        }
        Vector v = to.clone();
        v.setY(fn + (g.pos.getY() - fo));
        return game.mobs.relocate(g, v);
    }

    /** 그 종 하나를 at 에(감독이 낸 것처럼 체력, 보상 배율. 이 특수가 닫힐 때 살아 있으면 치움: own 이면). */
    protected Gabe spawn(Species sp, Vector at, boolean own) {
        Gabe g = rounds.spawnAt(sp, at);
        if (g != null && own) this.own.add(g);
        return g;
    }

    /** 이 특수에 쓸 값싼 땅 몹(디스플레이 하나쯤, 위협 2 아래). 없으면 값싼 떼, 그것도 없으면 G01. */
    protected Species cheapRunner() {
        Species s = rounds.pick(sp -> sp.spawn == Species.Spawn.RING && sp.band != kr.gave.mob.Band.SKY && !sp.is(Role.SWARM) && sp.threat <= 2 && sp.cost <= 2
            && !sp.is(Role.BOSS) && !sp.is(Role.BONUS));
        if (s == null) s = rounds.pick(sp -> sp.spawn == Species.Spawn.RING && sp.band != kr.gave.mob.Band.SKY && sp.threat <= 2 && !sp.is(Role.BOSS));
        if (s == null) s = Species.G01.implemented() ? Species.G01 : rounds.cheapSwarm();
        return s;
    }

    // ------------------------------------------------------------------ 도우미: HUD, 소리

    /** 모두에게 알림 띠(글귀가 구워졌을 때만). */
    protected void say(String phrase, int ticks) {
        if (Hud.hasPhrase(phrase)) game.hud.notice(phrase, ticks);
    }

    protected void say(Player p, String phrase, int ticks) {
        if (Hud.hasPhrase(phrase)) game.hud.notice(p, phrase, ticks);
    }

    protected void alert(String phrase, int level, int ticks) {
        if (Hud.hasPhrase(phrase)) game.hud.alert(phrase, level, ticks);
    }

    protected void hint(Player p, String phrase, int ticks) {
        if (Hud.hasPhrase(phrase)) game.hud.hint(p, phrase, ticks);
    }

    /** 조준점 아래 게이지(글귀가 없어도 게이지는 그린다: 키는 그 칸 이름일 뿐). */
    protected void meter(Player p, String phrase, double frac, int ticks) {
        game.hud.meter(p, phrase, frac, ticks);
    }

    protected void sound(Location at, String event, Sound fallback, float vol, float fbPitch, double range) {
        Props.soundAround(at, event, fallback, vol, 0.1f, fbPitch, range, -1);
    }

    protected Location truckLoc(double up) {
        Vector c = Truck.center();
        return new Location(game.world, c.getX(), Plaza.FLOOR_Y + up, c.getZ());
    }

    /** 이 라운드 곡(Music cue: chase 추격전, last_stand 버티기). 닫힐 때 그 곡이 아직 걸려 있으면 푼다. */
    protected void cue(String music, int ticks) {
        game.music.cue(music, ticks);
        cued = music;
    }

    // ------------------------------------------------------------------ 도우미: 갈고리(닫힐 때 바탕이 뗌)

    protected void scale(Object k, kr.gave.mob.Mobs.DamageScale s) {
        game.mobs.scale(k, s);
        if (!scaleKeys.contains(k)) scaleKeys.add(k);
    }

    protected void force(Object k, Player p, double r, int ticks) {
        game.mobs.force(k, p, r, ticks);
        if (!forceKeys.contains(k)) forceKeys.add(k);
    }

    protected void unforce(Object k) {
        game.mobs.unforce(k);
        forceKeys.remove(k);
    }

    protected void lampOff(Object k, boolean off) {
        Truck t = trip.truck();
        if (t == null || off == lampKeys.contains(k)) return;
        t.lampOff(k, off);
        if (off && !lampKeys.contains(k)) lampKeys.add(k);
        if (!off) lampKeys.remove(k);
    }

    protected void shootable(kr.gave.mob.Mobs.Shootable s) {
        game.mobs.addShootable(s);
        shootables.add(s);
    }

    protected void unshootable(kr.gave.mob.Mobs.Shootable s) {
        game.mobs.removeShootable(s);
        shootables.remove(s);
    }

    /** 안개 단계(감독 bossFog). 닫힐 때 연 때의 단계로 되돌린다. */
    protected void fog(int level) {
        game.director.bossFog(level);
        fogSet = true;
    }

    /** 안개를 연 때의 단계로. */
    protected void fogBack() {
        if (fogBefore >= 0) game.director.bossFog(fogBefore);
    }

    // ------------------------------------------------------------------ 도우미: 디스플레이(닫힐 때 바탕이 치움)

    protected <T extends Display> T keep(T d) {
        if (d != null) displays.add(d);
        return d;
    }

    protected void drop(Display d) {
        if (d == null) return;
        displays.remove(d);
        if (d.isValid()) d.remove();
    }

    protected int displays() {
        int n = 0;
        for (Display d : displays) if (d != null && d.isValid()) n++;
        return n;
    }

    /** 팩의 월드 소품 꼴 모델(trip/barrel 처럼) 하나. 팩에 없으면 null. */
    protected ItemDisplay prop(String model, Location at, float yaw, float scale) {
        if (!Scenery.has(game, model)) return null;
        return keep(Scenery.spawn(game, model, at, yaw, scale, 1.6f));
    }

    /** 연출 판(fx_flat 같은 셰이더 모델) 하나: 표시 변환 없이, 바닥에 눕힘. */
    protected ItemDisplay flat(Location at, ItemStack it, float scale, int light) {
        return keep(game.world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(light, light));
            d.setTransformationMatrix(new Matrix4f().scale(scale));
            d.setDisplayWidth(scale * 1.1f);
            d.setDisplayHeight(1f);
            d.setViewRange(2.0f);
        }));
    }

    /** 바닥의 가득 찬 빨간 고리(반지름 r): 예고 띠, 표시. */
    protected ItemDisplay redRing(Vector at, double r) {
        return flat(loc(at, 0.02), game.shotFx.fx("fx_flat", ShotFx.DOWN_RING, 0), (float) (r / Rounds.Tune.RING_BASE_R), 15);
    }

    /** 바닥의 채우는 고리(초록 = 채우는 중, 빨강 = 남은 몫): 몫이 바뀌면 다시 싣는다. */
    protected void ringFrac(ItemDisplay d, double frac, boolean green) {
        if (d != null && d.isValid()) d.setItemStack(game.shotFx.downRing(frac, green));
    }

    // ------------------------------------------------------------------ 도우미: 폭발(게이브만 다침)

    /** 그 자리 폭발 연출 + 반경 안 게이브에 피해(사람과 트럭은 안 다침). by = 터뜨린 사람(보상, 기여). 맞은 수. */
    protected int blast(Location at, double r, double dmg, Player by) {
        game.shotFx.explosion(at);
        int n = 0;
        List<Object> hit = game.mobs.inRadius(at, r);
        for (Object o : hit) {
            if (!(o instanceof Gabe g) || g.dead || g.sp.is(Role.BOSS)) continue;
            Vector c = game.mobs.center(g);
            Vector dir = c == null ? null : c.clone().subtract(at.toVector());
            if (dir != null && dir.lengthSquared() > 1e-6) dir.normalize();
            game.mobs.damage(g, new Damage(dmg, DamageType.EXPLOSION, by, c, dir, "explosive", 0));
            n++;
        }
        return n;
    }

    /** 몸 디스플레이 밝기(정전된 밤): 바뀔 때만 보냄. */
    protected static void bright(Gabe g, Display.Brightness b) {
        if (g.body == null) return;
        for (Entity e : g.body.entities()) {
            if (!(e instanceof Display d) || !d.isValid()) continue;
            Display.Brightness cur = d.getBrightness();
            if (b == null ? cur == null : b.equals(cur)) continue;
            d.setBrightness(b);
        }
    }

    /** 시선은 그대로 둔 채 옮긴다(Travel, Roam 과 같은 방식). */
    protected static void slide(Player p, double x, double z) {
        ((CraftPlayer) p).getHandle().connection.teleport(new PositionMoveRotation(new Vec3(x, p.getLocation().getY(), z), Vec3.ZERO, 0f, 0f),
            EnumSet.of(Relative.Y_ROT, Relative.X_ROT));
    }

    /**
     * 네모 안에 가둠(서 있는 사람, 붙잡히거나 좌석에 탄 사람은 뺌): 조금 나가면 안쪽으로 밀고, 많이 나가면 안으로 옮김. 나가 있던 사람이면 true.
     */
    protected boolean confine(Player p, double x0, double x1, double z0, double z1) {
        PState s = game.players.get(p);
        if (s == null || s.life != Life.ALIVE || s.grabbedBy != null || game.players.riding(p) || game.players.launched(p)) return false;
        Location l = p.getLocation();
        double x = Math.max(x0, Math.min(x1, l.getX())), z = Math.max(z0, Math.min(z1, l.getZ()));
        double dx = x - l.getX(), dz = z - l.getZ(), d = Math.hypot(dx, dz);
        if (d < 1e-6) return false;
        if (d > 0.8) slide(p, x - Math.signum(dx) * 0.2, z - Math.signum(dz) * 0.2);
        else {
            Vector v = p.getVelocity();
            p.setVelocity(new Vector(dx * 0.5 + Math.signum(dx) * 0.12, Math.max(-0.08, v.getY()), dz * 0.5 + Math.signum(dz) * 0.12));
        }
        return true;
    }

    /** 반복자 도우미(지운 것 빼고). */
    protected static <T> void prune(List<T> l, java.util.function.Predicate<T> gone) {
        for (Iterator<T> it = l.iterator(); it.hasNext();) if (gone.test(it.next())) it.remove();
    }
}
