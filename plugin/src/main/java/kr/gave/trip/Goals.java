package kr.gave.trip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;
import net.kyori.adventure.key.Key;

/**
 * 라운드 목표 엔진(원정.md 5.0절 goal 축): 다 잡기, 버티기, 트럭 지키기, 자리 지키기(표시 원), 줍기(트럭에 싣기), 표적 사냥, 시간 안에, 건조(자리 지키기 꼴),
 * 시동(트럭 앞에서 막대 채우기), 특수가 직접 정하는 막대(custom). 라운드 끝 판정({@link #done(boolean)})을 감독 판정 대신 정하고, 목표 막대 값을 HUD 에 내놓는다.
 *
 * <p>특수 라운드(묶음 8, 9)가 쓰는 공개 바탕: {@link #custom(String, double, String)}, {@link #count(int, int)}, {@link #timer(int)},
 * {@link #circle(Vector, double, double)}, {@link #pickup(Vector, String)}, {@link #target(Gabe, boolean)}, {@link #succeed()}, {@link #fail()}, {@link #met()}.
 * 목표를 마치면(succeed) 남은 떼는 바깥으로 물러나고(RETREAT_TICKS) 라운드가 끝난다. 숫자는 {@link Rounds.Tune}.
 */
public final class Goals {

    public enum Kind {
        KILL, SURVIVE, TRUCK, HOLD, COLLECT, HUNT, TIMED, BUILD, START, CUSTOM;

        public static Kind of(String s) {
            if (s == null) return KILL;
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return KILL;
            }
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 표시 원 하나(자리 지키기, 건조 지점, 떨어진 짐). */
    public final class Circle {
        public final Vector at;
        public final double r;
        /** 채워야 할 틱, 채운 틱. */
        public double need, have;
        public boolean done;
        /** 이번 틱 원 안 서 있는 사람 수, 게이브 수. */
        public int people, mobs;
        ItemDisplay ring, prop;
        int shownQ = -1;

        Circle(Vector at, double r, double need) {
            this.at = at;
            this.r = r;
            this.need = need;
        }

        public double frac() {
            return need <= 0 ? 1 : Math.max(0, Math.min(1, have / need));
        }

        /** 사람이 게이브보다 많아 돌고 있나. */
        public boolean active() {
            return people > 0 && people > mobs;
        }
    }

    /** 줍는 것 하나(부품, 탄약통, 기름통). */
    public final class Pickup {
        public Vector at;
        public final String model;
        public UUID carrier;
        public boolean delivered;
        ItemDisplay d;

        Pickup(Vector at, String model) {
            this.at = at;
            this.model = model;
        }
    }

    final Game game;
    final Trip trip;
    final Rounds rounds;
    private final Random rnd = new Random();

    public Kind kind = Kind.KILL;
    /** 목표 값(버티기 초, 줍기 수, 사냥 수, 시간 초 ...). */
    public double value;
    private boolean active, met, failed;
    /** 막대와 끝을 장치(RoundFx)가 쥠: 감독 판정으로 끝나지 않고 succeed/fail 로만. */
    private boolean owned;
    private long startAt, retreatUntil = -1;
    private String failNote;

    // 막대(특수, 시동, 건조)
    private String customIcon;
    private double customFrac = -1;
    private String customText;
    private int countGot = -1, countNeed;
    private long timerEnd = -1;
    private double startProg;
    private long startKnockAt;
    private Vector hood;

    public final List<Circle> circles = new ArrayList<>();
    public final List<Pickup> pickups = new ArrayList<>();
    private int pickNeed, pickGot;
    /** 탄약 줍기: 잡은 게이브가 떨군다(놓인 것 없음), 지난번 떨군 뒤 잡은 수. */
    private boolean drops;
    private int sinceDrop;
    /** 떨어진 짐: 짐 체력(0..CARGO_HP, 음수면 짐 없음). */
    private double cargoHp = -1;
    /** 건조: 모두 돌 때 차는 틱, 지금 몫. */
    private double buildTicks, buildFrac;

    // 사냥
    private final Set<Gabe> targets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Gabe, Long> markedAt = new IdentityHashMap<>();
    private final Map<Gabe, kr.gave.mob.Body.Tag> tags = new IdentityHashMap<>();
    private int huntNeed, huntKilled, huntEscaped, huntMarked;
    /** 사냥 표적이 금빛인가, 달아나는가(초, 0 이면 안 달아남), 이름표를 다나, 정예만 고르나, 죽은 사람 얼굴 개체를 고르나. */
    private boolean huntGold, huntNames, huntElite, huntDeathBorn;
    private int fleeSeconds;
    private List<String> names = new ArrayList<>();

    Goals(Game game, Trip trip, Rounds rounds) {
        this.game = game;
        this.trip = trip;
        this.rounds = rounds;
    }

    // ------------------------------------------------------------------ 열기, 닫기

    /** 라운드 종류의 목표로 연다(beginRound). */
    void begin(RoundKind k, int n) {
        stop();
        kind = Kind.of(k == null ? "kill" : k.goal);
        value = k == null ? 0 : k.goalValue;
        active = true;
        met = failed = false;
        failNote = null;
        startAt = game.now();
        retreatUntil = -1;
        rnd.setSeed(trip.seed * 131 + n);
        if (k == null) return;
        if (k.has("eggs")) {
            // 알 무더기: 표적이 게이브가 아니라 땅의 알(RoundFx 가 셈)
            kind = Kind.CUSTOM;
            owned = true;
            return;
        }
        switch (kind) {
            case HOLD -> openHold(k);
            case COLLECT -> openCollect(k);
            case HUNT -> openHunt(k);
            case BUILD -> openBuild(k);
            case START -> {
                Vector c = Truck.center();
                hood = new Vector(c.getX(), Plaza.FLOOR_Y, c.getZ() - 3.6);
                startProg = 0;
            }
            default -> {
            }
        }
    }

    /** 특수 라운드가 쓸 빈 목표(막대는 특수가 custom/count/timer 로 채움). */
    public void beginCustom(String icon) {
        stop();
        kind = Kind.CUSTOM;
        active = true;
        met = failed = false;
        startAt = game.now();
        retreatUntil = -1;
        customIcon = icon;
    }

    /** 다 치움(라운드 끝, 판 끝, 정리). */
    public void stop() {
        for (Circle c : circles) {
            remove(c.ring);
            remove(c.prop);
        }
        circles.clear();
        for (Pickup p : pickups) remove(p.d);
        pickups.clear();
        for (Gabe g : targets) unmarkLook(g);
        targets.clear();
        markedAt.clear();
        for (kr.gave.mob.Body.Tag t : tags.values()) t.text("");
        tags.clear();
        huntNeed = huntKilled = huntEscaped = huntMarked = 0;
        huntGold = huntNames = huntElite = huntDeathBorn = false;
        fleeSeconds = 0;
        pickNeed = pickGot = 0;
        drops = false;
        sinceDrop = 0;
        cargoHp = -1;
        buildFrac = 0;
        customIcon = null;
        customFrac = -1;
        customText = null;
        countGot = -1;
        timerEnd = -1;
        startProg = 0;
        hood = null;
        active = false;
        owned = false;
        retreatUntil = -1;
    }

    private static void remove(Display d) {
        if (d != null && d.isValid()) d.remove();
    }

    // ------------------------------------------------------------------ 특수가 쓰는 바탕

    /** 막대를 직접(0..1, 음수면 지움). text 는 막대 옆 짧은 글(숫자). */
    public void custom(String icon, double frac, String text) {
        if (icon != null) customIcon = icon;
        customFrac = frac;
        customText = text;
    }

    /** 막대를 '얻은 수 / 필요 수'로. */
    public void count(int got, int need) {
        countGot = got;
        countNeed = Math.max(1, need);
    }

    /** 막대를 줄어드는 시간으로(초). */
    public void timer(int seconds) {
        timerEnd = game.now() + seconds * 20L;
    }

    /** 표시 원 하나(반지름 r, 채울 초). */
    public Circle circle(Vector at, double r, double seconds) {
        Circle c = new Circle(at.clone(), r, seconds * 20);
        circles.add(c);
        drawRing(c);
        return c;
    }

    /** 줍는 것 하나(model = 팩 아이템 모델 이름, 예: "trip/wheel", "ammo_box"). */
    public Pickup pickup(Vector at, String model) {
        Pickup p = new Pickup(at.clone(), model);
        pickups.add(p);
        p.d = spawnItem(groundAt(p.at, 0.35), model, 0.9f);
        return p;
    }

    /** 사냥 표적으로 찍음(gold 면 금빛 얼굴). */
    public void target(Gabe g, boolean gold) {
        if (g == null || g.dead || targets.contains(g)) return;
        targets.add(g);
        markedAt.put(g, game.now());
        huntMarked++;
        if (gold && g.body != null) g.body.look.palette |= Look.PAL_GOLD;
        if (huntGold && g.reward > 0) g.reward = (int) Math.round(g.reward * Rounds.Tune.HUNT_GOLD_REWARD);
        game.hud.point(null, g.pos.clone(), 60);
        if (huntNames && !names.isEmpty()) tag(g, names.get((huntMarked - 1) % names.size()));
    }

    /** 목표를 마침(남은 떼가 물러난 뒤 라운드 끝). */
    public void succeed() {
        if (met || failed) return;
        met = true;
        retreat();
        game.hud.notice("rd_done", 60);
    }

    /** 목표를 놓침(보상 없이 라운드 끝). */
    public void fail() {
        if (met || failed) return;
        failed = true;
        retreat();
        game.hud.notice("rd_fail", 60);
    }

    public boolean met() {
        return met;
    }

    public boolean failed() {
        return failed;
    }

    /** 지금 목표가 다 잡기 꼴(감독 판정 그대로)인가. */
    public boolean killLike() {
        return kind == Kind.KILL || kind == Kind.TRUCK || kind == Kind.TIMED;
    }

    /** 감독 대기열이 비어도 떼를 계속 채워야 하나(목표가 남은 라운드). */
    public boolean streaming() {
        return active && !met && !failed && (owned || !killLike() && kind != Kind.CUSTOM);
    }

    public long elapsed() {
        return game.now() - startAt;
    }

    /** 처치 보상 배율(시간 안 x2). */
    double rewardMul() {
        if (!active || kind != Kind.TIMED || value <= 0) return 1;
        return elapsed() < value * 20 ? Rounds.Tune.TIMED_MUL : 1;
    }

    /** 시간 안에 끝냈나(시간 목표). */
    boolean inTime() {
        return kind == Kind.TIMED && elapsed() < value * 20;
    }

    // ------------------------------------------------------------------ 끝 판정

    /** 라운드 끝(감독 판정 dd 에 낌). */
    boolean done(boolean dd) {
        if (!active) return dd;
        long now = game.now();
        if (retreatUntil >= 0) return now >= retreatUntil;
        switch (kind) {
            case KILL, TRUCK, TIMED -> {
                return dd;
            }
            case CUSTOM -> {
                if (!owned) return dd;
            }
            default -> {
            }
        }
        if (elapsed() > Rounds.Tune.GOAL_MAX_SECONDS * 20L) {
            failNote = "time";
            fail();
        }
        return false;
    }

    private void retreat() {
        retreatUntil = game.now() + Rounds.Tune.RETREAT_TICKS;
        game.director.dropQueue();
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.sp.is(Role.BOSS)) continue;
            double a = Math.atan2(g.pos.getZ() - Plaza.CZ - 0.5, g.pos.getX() - Plaza.CX - 0.5);
            Vector to = new Vector(Plaza.CX + 0.5 + Math.cos(a) * 95, g.pos.getY(), Plaza.CZ + 0.5 + Math.sin(a) * 95);
            game.mobs.lure(g, to, Rounds.Tune.RETREAT_TICKS + 40);
        }
        for (Gabe g : new ArrayList<>(targets)) unmarkLook(g);
    }

    // ------------------------------------------------------------------ 틱

    void tick() {
        if (!active || game.state != Game.State.WAVE) return;
        long now = game.now();
        if (retreatUntil >= 0) return;
        switch (kind) {
            case SURVIVE -> {
                if (elapsed() >= value * 20) succeed();
            }
            case HOLD -> {
                holdTick(now);
                if (cargoHp > 0 && now % 20 == 0) cargoTick();
                boolean all = !circles.isEmpty();
                for (Circle c : circles) all &= c.done;
                if (all) succeed();
            }
            case BUILD -> buildTick(now);
            case COLLECT -> {
                collectTick(now);
                if (pickNeed > 0 && pickGot >= pickNeed) succeed();
            }
            case HUNT -> {
                huntTick(now);
                if (huntNeed > 0 && huntKilled + huntEscaped >= huntNeed) {
                    if (huntKilled > 0) succeed();
                    else fail();
                }
            }
            case START -> startTick(now);
            default -> {
            }
        }
        for (Circle c : circles) if (now % 4 == 0) drawRing(c);
    }

    // ------------------------------------------------------------------ 자리 지키기, 건조

    private void openHold(RoundKind k) {
        int n = Math.max(1, (int) Math.round(value <= 0 ? 1 : value));
        String at = k.str("holdAt", "");
        double base = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            Vector v;
            if (at.equals("road")) {
                // 길(남북) 위: 북쪽이나 남쪽 R 22~28
                double r = 22 + rnd.nextDouble() * 6;
                v = new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + (rnd.nextBoolean() ^ (i % 2 == 1) ? r : -r));
            } else {
                double a = base + i * Math.PI * 2 / n, r = Rounds.Tune.HOLD_RMIN + rnd.nextDouble() * (Rounds.Tune.HOLD_RMAX - Rounds.Tune.HOLD_RMIN);
                v = new Vector(Plaza.CX + 0.5 + Math.cos(a) * r, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * r);
            }
            Circle c = circle(v, Rounds.Tune.HOLD_R, Rounds.Tune.HOLD_SECONDS);
            if (k.has("cargo")) {
                c.prop = spawnItem(groundAt(v, 0.35), "ammo_box", 1.4f);
                cargoHp = Rounds.Tune.CARGO_HP;
            }
            game.hud.point(null, v.clone().setY(Plaza.FLOOR_Y + 1), 100);
        }
    }

    /** 원 안 사람과 게이브를 세고, 사람이 많으면 채움(게이브가 많으면 천천히 빠짐). */
    private void holdTick(long now) {
        for (Circle c : circles) {
            if (c.done) continue;
            count(c);
            if (c.active()) c.have += 1;
            else if (c.mobs > c.people) c.have = Math.max(0, c.have - Rounds.Tune.HOLD_DECAY);
            if (c.have >= c.need) {
                c.done = true;
                Props.soundAround(c.at.toLocation(game.world).add(0, 1, 0), "trip.goal", Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.1f, 1.4f, 48, -1);
            }
            if (now % 10 == 0) for (Player p : game.players.standing()) if (inside(c, p.getLocation())) game.hud.meter(p, "gl_hold", c.frac(), 14);
            if (now % 200 == 0 && c.people == 0) game.hud.point(null, c.at.clone().setY(Plaza.FLOOR_Y + 1), 60);
        }
    }

    /** 떨어진 짐: 짐 둘레 2.5블록 게이브마다 위협 등급만큼 깎임, 0 이면 놓침. */
    private void cargoTick() {
        Circle c = circles.get(0);
        double hit = 0;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.flying) continue;
            double dx = g.pos.getX() - c.at.getX(), dz = g.pos.getZ() - c.at.getZ();
            if (dx * dx + dz * dz <= 2.5 * 2.5) hit += g.sp.threat;
        }
        if (hit <= 0) return;
        cargoHp -= hit;
        if (Math.floorMod(game.now(), 100) < 20) game.hud.notice("rd_cargo_hit", 30);
        if (cargoHp <= 0) {
            cargoHp = 0;
            remove(c.prop);
            game.hud.notice("rd_lost_cargo", 60);
            failNote = "cargo";
            fail();
        }
    }

    private void count(Circle c) {
        c.people = 0;
        c.mobs = 0;
        for (Player p : game.players.standing()) if (inside(c, p.getLocation())) c.people++;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.flying) continue;
            double dx = g.pos.getX() - c.at.getX(), dz = g.pos.getZ() - c.at.getZ();
            if (dx * dx + dz * dz <= c.r * c.r) c.mobs++;
        }
    }

    private static boolean inside(Circle c, Location l) {
        double dx = l.getX() - c.at.getX(), dz = l.getZ() - c.at.getZ();
        return dx * dx + dz * dz <= c.r * c.r;
    }

    /** 건조(발사장 부품이 없을 때의 꼴): 지점 몇 개, 모두 돌면 BUILD_SECONDS 에 막대가 참. Launch 가 있으면 Rounds 가 비켜 주므로 여기 안 옴. */
    private void openBuild(RoundKind k) {
        int n = switch (k.key) {
            case "f_fuel", "f_power", "f_board", "f_pad" -> 1;
            case "f_frame" -> 2;
            default -> 3;
        };
        buildTicks = Rounds.Tune.BUILD_SECONDS * 20;
        buildFrac = 0;
        double base = -Math.PI / 2;
        for (int i = 0; i < n; i++) {
            Vector v = k.key.equals("f_pad") ? new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + 9)
                : new Vector(Plaza.CX + 0.5 + Math.cos(base + i * Math.PI * 2 / n) * 18, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(base + i * Math.PI * 2 / n) * 18);
            Circle c = circle(v, Rounds.Tune.HOLD_R, 1e9);
            c.need = 1e9;
        }
    }

    private void buildTick(long now) {
        int on = 0;
        for (Circle c : circles) {
            count(c);
            if (c.active()) on++;
            c.have = buildFrac * c.need;
        }
        buildFrac += circles.isEmpty() ? 0 : (double) on / circles.size() / Math.max(1, buildTicks);
        for (Circle c : circles) c.have = Math.min(c.need, buildFrac * c.need);
        if (now % 10 == 0) for (Player p : game.players.standing()) for (Circle c : circles) if (inside(c, p.getLocation())) game.hud.meter(p, "gl_build", buildFrac, 14);
        if (buildFrac >= 1) succeed();
    }

    // ------------------------------------------------------------------ 줍기

    private void openCollect(RoundKind k) {
        pickNeed = Math.max(1, (int) Math.round(value));
        if (k.has("drops")) {
            // 탄약 줍기: 처음에는 아무것도 없고 잡은 게이브가 떨군다(onKill)
            drops = true;
            return;
        }
        String model = k.key.equals("r_ammo") ? "ammo_box" : "trip/wheel";
        double base = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < pickNeed; i++) {
            double a = base + i * Math.PI * 2 / pickNeed + (rnd.nextDouble() - 0.5) * 0.5;
            double r = Rounds.Tune.COLLECT_RMIN + rnd.nextDouble() * (Rounds.Tune.COLLECT_RMAX - Rounds.Tune.COLLECT_RMIN);
            Vector v = new Vector(Plaza.CX + 0.5 + Math.cos(a) * r, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * r);
            pickup(v, model);
            game.hud.point(null, v.clone().setY(Plaza.FLOOR_Y + 1), 80);
        }
    }

    private void collectTick(long now) {
        for (Pickup it : pickups) {
            if (it.delivered) continue;
            if (it.carrier != null) {
                Player p = Bukkit.getPlayer(it.carrier);
                PState s = p == null ? null : game.players.get(p);
                if (p == null || s == null || s.life != Life.ALIVE) {
                    // 든 사람이 쓰러지거나 나가면 그 자리에 떨어짐
                    it.carrier = null;
                    if (p != null) it.at = p.getLocation().toVector().setY(Plaza.FLOOR_Y);
                    it.d = respawn(it.d, groundAt(it.at, 0.35), it.model, 0.9f);
                    continue;
                }
                Location l = p.getLocation();
                it.at = l.toVector().setY(Plaza.FLOOR_Y);
                if (it.d != null && it.d.isValid()) it.d.teleport(l.clone().add(0, 2.3, 0));
                game.players.slow(p, Rounds.Tune.CARRY_SLOW, 6, "trip_carry");
                s.gunLockUntil = Math.max(s.gunLockUntil, now + 4);
                if (Truck.dist(l.getX(), l.getZ()) <= Rounds.Tune.DELIVER_R) {
                    it.delivered = true;
                    it.carrier = null;
                    remove(it.d);
                    pickGot++;
                    Props.soundAround(l, "trip.goal", Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.1f, 1.4f, 48, -1);
                }
                if (now % 10 == 0) game.hud.meter(p, "gl_carry", (double) pickGot / pickNeed, 14);
            } else if (it.d != null && it.d.isValid() && now % 2 == 0) {
                Location l = groundAt(it.at, 0.35 + 0.08 * Math.sin(now * 0.12));
                l.setYaw((now * 4) % 360);
                it.d.teleport(l);
            }
        }
    }

    /** 좌클릭(돕기): 가까운 것을 집음. 집었으면 true. */
    boolean help(Player p) {
        if (!active || kind != Kind.COLLECT && pickups.isEmpty()) return false;
        PState s = game.players.get(p);
        if (s.life != Life.ALIVE) return false;
        for (Pickup it : pickups) if (p.getUniqueId().equals(it.carrier)) return false;
        Location l = p.getLocation();
        Pickup best = null;
        double bd = Rounds.Tune.PICK_R * Rounds.Tune.PICK_R;
        for (Pickup it : pickups) {
            if (it.delivered || it.carrier != null) continue;
            double dx = it.at.getX() - l.getX(), dz = it.at.getZ() - l.getZ(), d = dx * dx + dz * dz;
            if (d <= bd) {
                bd = d;
                best = it;
            }
        }
        if (best == null) return false;
        best.carrier = p.getUniqueId();
        best.d = respawn(best.d, l.clone().add(0, 2.3, 0), best.model, 0.7f);
        Props.sound(p, "trip.pick", Sound.ENTITY_ITEM_PICKUP, 1f, 0.1f, 0.8f);
        return true;
    }

    // ------------------------------------------------------------------ 사냥

    private void openHunt(RoundKind k) {
        huntNeed = (int) Math.round(value);
        huntGold = k.has("flee") || k.has("carrier") || k.has("gold") || k.key.equals("r_hunt") || k.key.equals("r_box");
        huntElite = k.comp.equals("elite");
        huntNames = k.has("names");
        huntDeathBorn = k.has("revenge") || k.flag("huntAllDeathBorn");
        fleeSeconds = (int) k.num("flee", 0);
        if (huntNames) {
            names = new ArrayList<>();
            for (Player p : game.players.inPlay()) if (!game.players.get(p).noLikeness) names.add(p.getName());
            Collections.shuffle(names, rnd);
        }
        if (huntDeathBorn) {
            int dead = 0;
            for (PState s : game.players.all()) if (s.life == Life.DEAD) dead++;
            huntNeed = Math.max(1, dead);
        }
        if (huntNeed <= 0) huntNeed = 1;
    }

    /** 추적하던 개체가 사람에게 잡힘(Rounds 가 부름): 탄약 줍기면 떨굼. */
    void onKill(Gabe g) {
        if (!active || !drops || met || failed) return;
        int out = 0;
        for (Pickup it : pickups) if (!it.delivered) out++;
        if (pickGot + out >= pickNeed + 1 || out >= Rounds.Tune.DROP_OUT) return;
        sinceDrop++;
        if (rnd.nextDouble() >= Rounds.Tune.DROP_CHANCE && sinceDrop < Rounds.Tune.DROP_PITY) return;
        sinceDrop = 0;
        double x = g.pos.getX(), z = g.pos.getZ(), r = Plaza.radius(x, z);
        if (r > Plaza.R_ROAM - 2) {
            double k = (Plaza.R_ROAM - 2) / r;
            x = Plaza.CX + 0.5 + (x - Plaza.CX - 0.5) * k;
            z = Plaza.CZ + 0.5 + (z - Plaza.CZ - 0.5) * k;
        }
        Vector at = new Vector(x, Plaza.FLOOR_Y, z);
        pickup(at, "ammo_box");
        game.hud.point(null, at.clone().setY(Plaza.FLOOR_Y + 1), 60);
    }

    /** 감독이 막 낸 개체(Rounds 가 부름): 표적이 모자라면 찍는다. 떨어진 짐이면 일부가 짐으로 간다. */
    void onSpawn(Gabe g) {
        if (active && cargoHp > 0 && !circles.isEmpty() && !g.flying && rnd.nextDouble() < Rounds.Tune.CARGO_SHARE)
            game.mobs.lure(g, circles.get(0).at.clone().setY(g.pos.getY()), 400);
        if (!active || kind != Kind.HUNT || met || failed || huntDeathBorn) return;
        if (huntMarked >= huntNeed || g.sp.spawn == Species.Spawn.PLACED || g.sp.is(Role.BOSS) || g.sp.is(Role.BONUS) || g.untargetable || g.disguised) return;
        if (huntElite && !g.sp.is(Role.ELITE) && elapsed() < 200) return;
        if (g.sp.is(Role.SWARM) && elapsed() < 300) return;
        target(g, huntGold);
    }

    private void huntTick(long now) {
        if (huntDeathBorn && now % 20 == 0) {
            for (PState s : game.players.all()) {
                Gabe m = s.deathMob;
                if (s.life == Life.DEAD && m != null && !m.dead && !m.removed && !targets.contains(m) && huntMarked < huntNeed + 4) target(m, true);
            }
        }
        for (Iterator<Gabe> it = targets.iterator(); it.hasNext();) {
            Gabe g = it.next();
            if (g.dead || g.removed) {
                it.remove();
                kr.gave.mob.Body.Tag t = tags.remove(g);
                if (t != null) t.text("");
                boolean killed = g.dead && !g.noReward;
                if (killed) huntKilled++;
                else huntEscaped++;
                if (!killed) game.hud.notice("rd_escaped", 40);
                continue;
            }
            Long at = markedAt.get(g);
            if (fleeSeconds > 0 && at != null) {
                if (now - at >= fleeSeconds * 20L) {
                    // 달아남: 바깥으로 끌려가 사라짐
                    double a = Math.atan2(g.pos.getZ() - Plaza.CZ - 0.5, g.pos.getX() - Plaza.CX - 0.5);
                    game.mobs.lure(g, new Vector(Plaza.CX + 0.5 + Math.cos(a) * 98, g.pos.getY(), Plaza.CZ + 0.5 + Math.sin(a) * 98), 200);
                    if (Plaza.radius(g.pos.getX(), g.pos.getZ()) > Plaza.R_SPAWN_IN) {
                        g.noReward = true;
                        g.remove();
                    }
                } else if (now % 20 == (g.id & 15)) {
                    Player near = game.mobs.nearestPlayer(g.pos, 16);
                    if (near != null) {
                        Vector away = g.pos.clone().subtract(near.getLocation().toVector()).setY(0);
                        if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
                        away.normalize().multiply(10);
                        Vector to = g.pos.clone().add(away);
                        double r = Plaza.radius(to.getX(), to.getZ());
                        if (r > Plaza.R_ROAM + 6) to = g.pos.clone().add(new Vector(-away.getZ(), 0, away.getX()));
                        game.mobs.lure(g, to, 30);
                    }
                }
            }
            if (now % 40 == (g.id & 31)) game.hud.point(null, g.pos.clone(), 40);
        }
    }

    /** 머리 위 이름표(몸에 붙은 글 줄: 몸과 같이 움직이고 죽으면 엔진이 지움). */
    private void tag(Gabe g, String name) {
        if (g.body == null) return;
        tags.put(g, g.body.tag((float) (1.6 + g.radius), 1.4f).color(0xFFD24A).text(name));
    }

    private void unmarkLook(Gabe g) {
        if (g != null && g.body != null) g.body.look.palette &= ~Look.PAL_GOLD;
    }

    /** 사냥 표적인가. */
    public boolean isTarget(Gabe g) {
        return targets.contains(g);
    }

    public Set<Gabe> targets() {
        return Collections.unmodifiableSet(targets);
    }

    // ------------------------------------------------------------------ 시동

    private void startTick(long now) {
        int near = 0;
        for (Player p : game.players.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - hood.getX(), dz = l.getZ() - hood.getZ();
            if (dx * dx + dz * dz <= Rounds.Tune.HOOD_R * Rounds.Tune.HOOD_R) near++;
        }
        if (near > 0) startProg += Math.min(2, 1 + 0.5 * (near - 1)) / Math.max(20, value * 20);
        if (now % 10 == 0) for (Player p : game.players.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - hood.getX(), dz = l.getZ() - hood.getZ();
            if (dx * dx + dz * dz <= Rounds.Tune.HOOD_R * Rounds.Tune.HOOD_R * 2) game.hud.meter(p, "gl_start", startProg, 14);
        }
        if (startProg >= 1) succeed();
    }

    /** 사건(트럭이 맞음: 시동 막대가 깎임). */
    void on(String event, Object arg) {
        if (!active || kind != Kind.START || !Trip.EV_TRUCK_HIT.equals(event)) return;
        long now = game.now();
        if (now - startKnockAt < 10) return;
        startKnockAt = now;
        startProg = Math.max(0, startProg - Rounds.Tune.START_KNOCK);
    }

    // ------------------------------------------------------------------ 막대 값

    /** 목표 막대 몫(0..1, 없으면 -1). */
    public double frac() {
        if (!active) return -1;
        if (customFrac >= 0) return customFrac;
        if (countGot >= 0) return Math.min(1, (double) countGot / countNeed);
        if (timerEnd >= 0) return Math.max(0, (timerEnd - game.now()) / 20.0) / Math.max(1, (timerEnd - startAt) / 20.0);
        return switch (kind) {
            case SURVIVE -> value <= 0 ? -1 : Math.max(0, 1 - elapsed() / (value * 20));
            case TIMED -> value <= 0 ? -1 : Math.max(0, 1 - elapsed() / (value * 20));
            case HOLD -> {
                double s = 0;
                for (Circle c : circles) s += c.frac();
                yield circles.isEmpty() ? 0 : s / circles.size();
            }
            case BUILD -> buildFrac;
            case COLLECT -> pickNeed <= 0 ? 0 : (double) pickGot / pickNeed;
            case HUNT -> huntNeed <= 0 ? 0 : (double) (huntKilled + huntEscaped) / huntNeed;
            case START -> startProg;
            default -> -1;
        };
    }

    /** 막대 옆 짧은 글(남은 초, 3/6). */
    public String text() {
        if (!active) return null;
        if (customText != null) return customText;
        if (countGot >= 0) return countGot + "/" + countNeed;
        if (timerEnd >= 0) return Integer.toString((int) Math.ceil(Math.max(0, timerEnd - game.now()) / 20.0));
        return switch (kind) {
            case SURVIVE, TIMED -> Integer.toString((int) Math.ceil(Math.max(0, value * 20 - elapsed()) / 20.0));
            case COLLECT -> pickGot + "/" + pickNeed;
            case HUNT -> huntKilled + "/" + huntNeed;
            case HOLD -> {
                int d = 0;
                for (Circle c : circles) if (c.done) d++;
                yield d + "/" + circles.size();
            }
            case BUILD, START -> Math.round(frac() * 100) + "%";
            default -> null;
        };
    }

    /** 차오르는 금빛 막대인가(자리, 건조, 줍기, 사냥, 시동, 특수). 아니면 줄어드는 시간 막대. */
    public boolean filling() {
        return kind != Kind.SURVIVE && kind != Kind.TIMED && timerEnd < 0;
    }

    void hud(Player p, Map<String, Object> out) {
        if (!active) return;
        out.put("goal.kind", kind.key());
        double f = frac();
        if (f >= 0) out.put("goal.frac", f);
        String t = text();
        if (t != null) out.put("goal.text", t);
        out.put("goal.fill", filling());
        out.put("goal.icon", customIcon != null ? customIcon : "ti_goal_" + kind.key());
        if (cargoHp >= 0) out.put("goal.cargo", cargoHp / Rounds.Tune.CARGO_HP);
        if (met) out.put("goal.met", true);
        if (failed) out.put("goal.failed", true);
    }

    String status() {
        if (!active) return "goal=-";
        double f = frac();
        StringBuilder sb = new StringBuilder("goal=").append(kind.key());
        if (f >= 0) sb.append(String.format(Locale.ROOT, ":%.2f", f));
        String t = text();
        if (t != null) sb.append(':').append(t);
        if (met) sb.append(":met");
        if (failed) sb.append(":fail").append(failNote == null ? "" : "(" + failNote + ")");
        if (retreatUntil >= 0) sb.append(":retreat");
        if (!circles.isEmpty()) {
            sb.append(" circles=");
            for (Circle c : circles) sb.append(c.people).append('v').append(c.mobs).append('@').append(Math.round(c.frac() * 100)).append(',');
            sb.setLength(sb.length() - 1);
        }
        if (!pickups.isEmpty()) {
            int carried = 0;
            for (Pickup it : pickups) if (it.carrier != null) carried++;
            sb.append(" picks=").append(pickGot).append('/').append(pickNeed).append(" carried=").append(carried);
        }
        if (cargoHp >= 0) sb.append(String.format(Locale.ROOT, " cargo=%.0f", cargoHp));
        if (drops) sb.append(" drops=").append(pickups.size());
        if (kind == Kind.HUNT) sb.append(" hunt=").append(huntKilled).append('+').append(huntEscaped).append('/').append(huntNeed).append(" marked=").append(targets.size());
        return sb.toString();
    }

    /** 시험: 원 하나를 채움, 줍기 하나를 실음, 표적 하나를 죽임 따위(목표가 돌고 끝나는지 빨리 보려고). */
    String testStep(String what) {
        switch (what) {
            case "채움" -> {
                for (Circle c : circles) {
                    c.have = c.need;
                    c.done = kind == Kind.HOLD;
                }
                if (kind == Kind.BUILD) buildFrac = 1;
                if (kind == Kind.START) startProg = 1;
                if (kind == Kind.SURVIVE) startAt = game.now() - (long) (value * 20);
                return "채움 " + status();
            }
            case "싣기" -> {
                for (Pickup it : pickups) {
                    if (it.delivered) continue;
                    it.delivered = true;
                    remove(it.d);
                    pickGot++;
                    return "싣기 " + status();
                }
                return "실을 것 없음";
            }
            case "표적" -> {
                // 사람이 잡은 것처럼(보상 있음, noReward 끔): 막는 종도 있어 die 로 바로
                for (Gabe g : targets) {
                    if (g.dead) continue;
                    g.noReward = false;
                    g.hp = 0;
                    g.die(new kr.gave.mob.Damage(1e6, kr.gave.mob.DamageType.OTHER, null, g.pos.clone(), null, "test", 0));
                    return "표적 하나 " + g.sp.name() + " dead=" + g.dead + " " + status();
                }
                return "표적 없음";
            }
            case "성공" -> {
                succeed();
                return status();
            }
            case "실패" -> {
                fail();
                return status();
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ 그리기

    /** 표시 원: 쓰러짐 고리 셰이더의 초록 판(채운 몫만큼 호). 다 차면 가득. */
    private void drawRing(Circle c) {
        int q = (int) Math.round(c.frac() * 63);
        if (c.ring != null && c.ring.isValid() && q == c.shownQ) return;
        c.shownQ = q;
        ItemStack it = game.shotFx.downRing(c.done ? 1 : c.frac(), true);
        if (c.ring != null && c.ring.isValid()) {
            c.ring.setItemStack(it);
            return;
        }
        Location at = new Location(game.world, c.at.getX(), Plaza.FLOOR_Y + 0.05, c.at.getZ());
        float s = (float) (c.r / Rounds.Tune.RING_BASE_R);
        c.ring = game.world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformationMatrix(new Matrix4f().scale(s));
            d.setDisplayWidth((float) (c.r * 2.2));
            d.setDisplayHeight(1f);
            d.setViewRange(2.0f);
        });
    }

    Location groundAt(Vector v, double up) {
        return new Location(game.world, v.getX(), Plaza.FLOOR_Y + up, v.getZ());
    }

    /** 팩 아이템 모델을 얹은 디스플레이(모델이 없으면 바닐라 상자). */
    ItemDisplay spawnItem(Location at, String model, float scale) {
        ItemStack it = ItemStack.of(Material.PAPER);
        boolean ok = model.startsWith("trip/") ? Truck.live() : Props.has(model);
        it.setData(DataComponentTypes.ITEM_MODEL, ok ? Key.key(Props.NS, model) : Key.key("minecraft", "chest"));
        return game.world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(12, 12));
            d.setTransformationMatrix(new Matrix4f().scale(scale));
            d.setViewRange(1.6f);
            d.setTeleportDuration(2);
        });
    }

    private ItemDisplay respawn(ItemDisplay old, Location at, String model, float scale) {
        remove(old);
        return spawnItem(at, model, scale);
    }

    /** 놓인 디스플레이 수(원정 disp 셈). */
    int displays() {
        int n = 0;
        for (Circle c : circles) n += (c.ring != null ? 1 : 0) + (c.prop != null ? 1 : 0);
        for (Pickup p : pickups) if (p.d != null && p.d.isValid()) n++;
        return n + tags.size();
    }
}
