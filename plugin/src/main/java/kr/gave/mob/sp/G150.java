package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;

/**
 * G150 모래시계 게이브(4막, 정예, 목표 압박, DERIVED). 웨이브가 90초를 넘기면 가장자리(나오는 고리)에서 하나가 걸어 나오고 그 뒤 30초마다 하나씩 더
 * (다섯까지). 그 웨이브에서 게이브를 가장 적게 잡은 서 있는 사람에게 걷는 빠르기보다 느리게(0.11/틱) 곧장 온다. 다른 게이브와 밀치지 않고 지나치며
 * 뛰지도 멈추지도 않는다. 닿으면 틀 기둥으로 35와 넘어짐 1초, 10초 동안 걷기 25% 느려짐과 재장전 1.33배(엉킴 JAM, 헛방 없음). 곁에 있으면 2초마다.
 *
 * <p>죽일 수 없다: 체력이 0 이 되면 4초 동안 뒤집히며 멈추고(그동안 안 맞음) 다시 걷는다. 멈출 때마다 다음 체력이 20% 늘어난다. 진짜 끝은 웨이브 끝
 * 정리뿐(noHold: 남은 수에서 빠지고 웨이브를 붙들지 않음, 감독의 끝 정리가 die 를 부름) → 모래가 다 떨어지며 사라짐(사체 없음).
 *
 * <p>지킴이: 감독이 내지 않는 종이라 판 단위 갈고리(FIELDTICK, 키 "G150")가 웨이브 경과 틱(DIRECTOR waveTicks)을 보고 spawnLike 로 놓는다. 갈고리는
 * 판이 바뀌면 떼이니 4막 종들(이 묶음의 G133 등)이 놓일 때와 이 종이 놓일 때 {@link #arm(Game)} 로 다시 건다. 34웨이브부터, 또는 원정 막 덱이 이
 * 종을 한 마리 낸 웨이브부터(덱이 낸 개체는 지킴이를 걸고 바로 사라짐: 90초 규칙을 지키려고) 보스 웨이브만 빼고 늘.
 *
 * <p>팩 계약(tools/gabe/sp/g150.py): 상태 "", "stop". 소리 sand(24), strike(20), flip(24). 글귀 cause_g150, notice_g150_late.
 */
public final class G150 extends Gabe {

    static final double SPEED = 0.11, SPEED_CAP = 0.17, HOVER = 1.6, RADIUS = 0.8;
    static final double STRIKE = 35, SLOW = 0.25, RELOAD_MUL = 1.33;
    static final int STRIKE_CD = 40, DOWN = 20, SLOW_TICKS = 200, STOP = 80, FLIP = 20;
    static final double HP_GROW = 0.2;
    /** 지킴이: 처음 나오는 웨이브 경과 틱(90초), 간격(30초), 한 웨이브 상한. 박자 배율 안 탐. */
    static final int FIRST = 1800, EVERY = 600, MAX = 5;
    static final String KEY = "G150";

    /** 지킴이가 놓는 중(이때 생긴 개체는 덱이 낸 것이 아님). */
    private static boolean making;

    private final boolean byKeeper;
    private boolean checked;
    private Player prey;
    private int stopUntil = -1, stops, strikeAt, sandAt, pickAt, detour;
    private double baseHp = -1;

    public G150(Game game, Species sp) {
        super(game, sp);
        byKeeper = making;
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true;
        noBite = true;
        noRules = true;
        noPush = true;
        leaveCorpse = false;
        noReward = true;
        reward = 0;
        noHold = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g150", 2.2f, 3.6f);
    }

    @Override
    protected void spawned() {
        arm(game);
        sandAt = game.random.nextInt(30);
    }

    // ------------------------------------------------------------------ 지킴이(판 단위)

    /** 지킴이 갈고리를 건다(이미 있으면 그대로). 이 묶음 4막 종들이 spawned 에서 부른다. */
    public static void arm(Game game) {
        if (game == null || game.mobs.field(KEY) != null) return;
        game.mobs.field(KEY, new Keeper(game));
    }

    static Keeper keeper(Game game) {
        return game.mobs.field(KEY) instanceof Keeper k ? k : null;
    }

    static final class Keeper implements Mobs.Field {
        final Game game;
        /** 원정 막 덱이 이 종을 낸 웨이브(그 웨이브부터 지킴). */
        int introduced = Integer.MAX_VALUE;
        int made;
        final Map<UUID, Integer> kills0 = new HashMap<>();

        Keeper(Game game) {
            this.game = game;
        }

        boolean active() {
            int w = game.wave;
            return (w >= Species.G150.tier || w >= introduced) && !game.director.bossActive() && !game.director.ending();
        }

        @Override
        public boolean tick(long now) {
            if (game.state != Game.State.WAVE || !active()) return true;
            long wt = game.director.waveTicks();
            if (wt < FIRST || made >= MAX || wt < FIRST + (long) made * EVERY) return true;
            Vector at = game.director.ringPoint(Species.G150);
            Gabe g;
            making = true;
            try {
                g = game.director.spawnLike(Species.G150, at);
            } finally {
                making = false;
            }
            made++;
            game.plugin.getLogger().info("[G150] 지킴이가 놓음 " + made + "/" + MAX + " 웨이브 " + game.wave + " 경과 " + wt + (g == null ? " (실패)" : ""));
            if (g != null && made == 1) game.hud.notice("notice_g150_late", Species.G150, 80);
            return true;
        }

        @Override
        public void waveStart(int wave) {
            made = 0;
            kills0.clear();
            for (Player p : game.players.standing()) kills0.put(p.getUniqueId(), game.players.get(p).kills);
        }

        @Override
        public void waveEnd(int wave) {
            made = 0;
        }

        /** 이번 웨이브에 잡은 수(웨이브 시작 때 적어 둔 것에서). */
        int killsThisWave(Player p) {
            int k = game.players.get(p).kills;
            Integer k0 = kills0.putIfAbsent(p.getUniqueId(), k);
            return k0 == null ? 0 : k - k0;
        }

        @Override
        public String toString() {
            return "G150 keeper made=" + made + " intro=" + (introduced == Integer.MAX_VALUE ? "-" : introduced) + " active=" + active();
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!checked) {
            checked = true;
            arm(game);
            if (baseHp < 0) baseHp = maxHp;
            // 원정 막 덱이 한 마리로 낸 개체: 지킴이를 걸고(그 웨이브부터) 사라진다. 모래시계는 90초 규칙으로만 나온다
            if (!byKeeper && game.director.fromDirector(this)) {
                Keeper k = keeper(game);
                if (k != null) k.introduced = Math.min(k.introduced, Math.max(1, game.wave));
                log("덱이 낸 개체: 지킴이 걸고 사라짐(웨이브 " + game.wave + ")");
                remove();
                return;
            }
        }
        if (age < stopUntil) {
            stopped();
            return;
        }
        if (age >= sandAt) {
            sound("sand", Sound.BLOCK_SAND_FALL, 0.6f, 24);
            sandAt = age + 40 + game.random.nextInt(20);
        }
        if (prey == null || !standing(prey) || age >= pickAt) {
            Player p = pick();
            if (p != prey) mark(p);
            prey = p;
            pickAt = age + 20;
        }
        Player p = prey;
        if (p == null) {
            follow(Math.min(speed(), SPEED_CAP));
            return;
        }
        game.hud.mark(p, "g150", 25);
        game.hud.meter(p, "g150", Math.max(0.02, 1.0 - dist(p) / 48.0), 25);
        double v = Math.min(speed(), SPEED_CAP);
        if (detour > 0) {
            detour--;
            follow(v);
        } else {
            seek(chest(p), v);
            if (wallAhead(0.8)) detour = 30;
        }
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-4) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        if (age >= strikeAt) {
            for (Player q : game.mobs.standing()) {
                if (!touches(q, 0.35)) continue;
                strike(q);
                break;
            }
        }
    }

    /** 이번 웨이브에 가장 적게 잡은 서 있는 사람(같으면 가까운 사람). */
    private Player pick() {
        Keeper k = keeper(game);
        Player best = null;
        int bk = Integer.MAX_VALUE;
        double bd = Double.MAX_VALUE;
        for (Player q : game.mobs.standing()) {
            int kills = k != null ? k.killsThisWave(q) : game.players.get(q).kills;
            double d = dist(q);
            if (kills < bk || kills == bk && d < bd) {
                bk = kills;
                bd = d;
                best = q;
            }
        }
        return best;
    }

    private void strike(Player q) {
        hit(q, STRIKE, "cause_g150");
        log("찍음 " + q.getName() + " 체력 " + Math.round(game.players.get(q).hp));
        game.players.root(q, DOWN, "g150");
        game.players.slow(q, SLOW, SLOW_TICKS, "g150");
        game.guns.jam(q, SLOW_TICKS, RELOAD_MUL, 0, 1.0);
        knock(q, toward(q), 0.3, 0.15);
        sound("strike", Sound.BLOCK_WOOD_BREAK, 0.6f, 20);
        body.squash(1.08f, 0.9f, 1.08f, 2);
        strikeAt = age + cd(STRIKE_CD);
    }

    /** 멈춤: 처음 FLIP 틱에 뒤집히고, 마지막 FLIP 틱에 마저 한 바퀴 돌아 바로 섬. */
    private void stopped() {
        hold();
        int left = stopUntil - age, t = STOP - left;
        if (t < FLIP) body.roll = 180f * (t + 1) / FLIP;
        else if (left <= FLIP) body.roll = 180f + 180f * (FLIP - left + 1) / FLIP;
        body.pose(1);
        if (left <= 1) {
            body.roll = 0;
            body.pose(0);
            state("");
            body.squash(1f, 1f, 1f, 3);
        }
    }

    private void stop() {
        stops++;
        if (baseHp < 0) baseHp = maxHp;
        maxHp = baseHp * (1 + HP_GROW * stops);
        hp = maxHp;
        stopUntil = age + STOP;
        state("stop");
        log("멈춤 " + stops + " 다음 체력 " + Math.round(maxHp));
        sound("flip", Sound.BLOCK_GLASS_BREAK, 0.6f, 24);
        hold();
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (age < stopUntil) return false;
        hp -= d.amount();
        game.mobs.onHurt(this, d);
        if (hp <= 0) stop();
        return true;
    }

    @Override
    public java.util.List<double[]> spheres() {
        return java.util.List.of(new double[] {pos.getX(), pos.getY() + 0.1, pos.getZ(), RADIUS}, new double[] {pos.getX(), pos.getY() + 1.05, pos.getZ(), 0.6});
    }

    /** 진짜 끝(웨이브 끝 정리, 시험 몹죽이기): 모래가 다 떨어지며 사라짐. damage 는 die 를 부르지 않는다. */
    @Override
    public void die(Damage d) {
        if (dead) return;
        leaveCorpse = false;
        if (prey != null) game.hud.meter(prey, "g150", -1, 0);
        try {
            Location l = loc();
            kr.gave.Props.soundAround(l, "gabe.g150.sand", Sound.BLOCK_SAND_FALL, 1f, 0.8f, 0.5f, 24, -1);
        } catch (RuntimeException ignored) {
            // 소리는 덤
        }
        super.die(d);
    }

    /** 시험과 판 뒤 분석용 기록(드문 사건만). */
    void log(String s) {
        game.plugin.getLogger().info("[G150] " + s);
    }

    @Override
    public String toString() {
        return super.toString() + (age < stopUntil ? " STOP" : "") + " stops=" + stops + " prey=" + (prey == null ? "-" : prey.getName()) + (byKeeper ? " keeper" : "");
    }
}
