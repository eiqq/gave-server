package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G86 소금쟁이 게이브(떼, 돌격). 물 위(물웅덩이와 물가 젖은 흙, 물 칸)에서는 갈지자로 빠르게 미끄러지고(0.4, 10틱마다 옆으로 튐), 마른 땅에서는
 * 벌렁 자빠져 허우적거리며 느리게 온다(0.12). 길은 사람 쪽에 있는 물웅덩이를 먼저 거친다. 물 위에서 그 물가 4블록 안에 선 사람을 보면 튀어 올라
 * 정강이에 매달린다(물기 6, 물에 발을 담근 사람은 9와 2초 느려짐 30%). 매달리면 0.5초마다 2(2초), 그 사람이 보고 좌클릭하면 떨어지고 동료가 쏘면
 * 한 발. 셋 넘게 매달리면 그 사람이 가장 가까운 물 쪽으로 끌린다(초당 1블록).
 *
 * <p>팩 계약(tools/gabe/sp/g86.py): 모델 gave:gabe/g86 상태 ""(물 위), "land", "cling". 소리 skate(20), 원인 글귀 cause_g86.
 */
public final class G86 extends Gabe {

    static final double WATER_V = 0.4, LAND_V = 0.12, LEAP_V = 0.5, ZIG = 0.6, SHORE = 4, WET_EDGE = 1.0;
    static final double BITE = 6, WET_BITE = 9, CLING_DMG = 2, WET_SLOW = 0.3, DRAG = 0.05;
    static final double HOVER = 0.25, CLING_HOVER = 0.45, CLING_SIDE = 0.42;
    static final int ZIG_EVERY = 10, LEAP = 12, CLING = 40, CLING_EVERY = 10, SLOW_TICKS = 40, REST = 30, SKATE_EVERY = 8, DRAG_N = 3;
    static final int PACK = 5;

    /** 물웅덩이(가운데 x, z, 반지름): Plaza 값은 판 동안 그대로라 한 번만 읽음. */
    private static double[][] pools;
    private static long skateAt = Long.MIN_VALUE / 2;

    private boolean packed, onWater;
    private int zigSide = 1, leapUntil = -1, clingAt = -1, restUntil = -1;
    private Player victim;
    private final Vector leapDir = new Vector(0, 0, 1), clingOff = new Vector(1, 0, 0);

    public G86(Game game, Species sp) {
        super(game, sp);
        radius = 0.35;
        hover = HOVER;
        baseSpeed = WATER_V;
        state("land");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g86", 1.8f, 0.9f);
    }

    static double[][] pools() {
        if (pools == null) {
            List<Vector> pts = Plaza.puddles();
            double[][] a = new double[pts.size()][];
            for (int i = 0; i < a.length; i++) a[i] = new double[] {pts.get(i).getX(), pts.get(i).getZ(), Plaza.puddleRadius(i) + WET_EDGE};
            pools = a;
        }
        return pools;
    }

    /** (x, z) 가 물 위인가: 물웅덩이(+ 물가 젖은 흙) 또는 발밑 물 칸. 웅덩이 번호, 물 칸이면 99, 아니면 -1. */
    private int water(double x, double z) {
        double[][] a = pools();
        for (int i = 0; i < a.length; i++) {
            double dx = x - a[i][0], dz = z - a[i][1];
            if (dx * dx + dz * dz <= a[i][2] * a[i][2]) return i;
        }
        int fy = (int) Math.floor(game.mobs.flow.floor(x, z) - 0.5);
        return game.world.getBlockAt((int) Math.floor(x), fy, (int) Math.floor(z)).isLiquid() ? 99 : -1;
    }

    @Override
    public void tick() {
        if (!packed) {
            packed = true;
            G82Pack.fill(this, PACK - 1, 2.5);
        }
        if (victim != null) {
            cling();
            return;
        }
        int pool = water(pos.getX(), pos.getZ());
        onWater = pool >= 0;
        if (age < leapUntil) {
            dash(leapDir, LEAP_V * speed() / WATER_V);
            face(pos.clone().add(leapDir));
            hover = HOVER + Math.sin(Math.PI * (leapUntil - age) / (double) LEAP) * 0.5;
            for (Player q : game.mobs.standing()) {
                if (touches(q, 0.15)) {
                    latch(q);
                    return;
                }
            }
            return;
        }
        hover = HOVER;
        if (age < restUntil) {
            hold();
            return;
        }
        Player p = nearest(400);
        if (onWater) {
            state("");
            // 물가 4블록 안의 사람에게 튀어 오름
            Player shore = pool < 99 ? shoreline(pool) : p != null && dist(p) < SHORE + 1 ? p : null;
            if (shore != null) {
                leapDir.copy(toward(shore));
                leapUntil = age + LEAP;
                mark(shore);
                return;
            }
            Vector to = route(p);
            if (to != null) seek(to, speed());
            else follow(speed());
            // 갈지자: 10틱마다 옆으로 튐
            if (every(ZIG_EVERY)) zigSide = -zigSide;
            double vl = Math.hypot(vel.getX(), vel.getZ());
            if (vl > 1e-6 && (age + id) % ZIG_EVERY < 3) vel.add(new Vector(-vel.getZ() / vl * ZIG * zigSide, 0, vel.getX() / vl * ZIG * zigSide));
            skate();
        } else {
            state("land");
            Vector to = route(p);
            double v = speed() * LAND_V / WATER_V;
            if (to != null) seek(to, v);
            else follow(v);
            if (p != null && touches(p, 0.1)) latch(p);
        }
        face(p != null && dist(p) < 10 ? chest(p) : pos.clone().add(vel));
    }

    /** 사람 쪽에 있는 물웅덩이(사람보다 가깝고, 사람 쪽 50도 안, 25블록 안)를 먼저 거침. 없으면 null(흐름장). */
    private Vector route(Player p) {
        if (p == null) return null;
        Vector tp = p.getLocation().toVector();
        double toP = Math.hypot(tp.getX() - pos.getX(), tp.getZ() - pos.getZ());
        if (toP < 6) return null;
        double ux = (tp.getX() - pos.getX()) / toP, uz = (tp.getZ() - pos.getZ()) / toP;
        double[][] a = pools();
        Vector best = null;
        double bd = 25;
        for (double[] w : a) {
            double dx = w[0] - pos.getX(), dz = w[1] - pos.getZ(), d = Math.hypot(dx, dz);
            if (d < w[2] * 0.5 || d >= toP - 2 || d > bd) continue;
            if ((dx * ux + dz * uz) / Math.max(d, 1e-6) < Math.cos(Math.toRadians(50))) continue;
            bd = d;
            best = new Vector(w[0], pos.getY(), w[1]);
        }
        // 이미 웅덩이 안이면 그 웅덩이 사람 쪽 가장자리를 지나 사람에게
        return best;
    }

    /** 이 웅덩이 물가 4블록 안에 선 사람(가장 가까운 사람). */
    private Player shoreline(int pool) {
        double[] w = pools()[pool];
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double d = Math.hypot(l.getX() - w[0], l.getZ() - w[1]);
            if (d > w[2] + SHORE) continue;
            double dm = dist(q);
            if (dm < bd) {
                bd = dm;
                best = q;
            }
        }
        return best;
    }

    private void skate() {
        if (!every(SKATE_EVERY)) return;
        long now = now();
        if (now - skateAt < 3) return;
        skateAt = now;
        sound("skate", Sound.ENTITY_PLAYER_SPLASH, 1.8f, 20);
    }

    /** 튀어 올라 정강이에 매달림. */
    private void latch(Player p) {
        boolean wetFoot = water(p.getLocation().getX(), p.getLocation().getZ()) >= 0;
        hit(p, wetFoot ? WET_BITE : BITE, "cause_g86");
        if (wetFoot) game.players.slow(p, WET_SLOW, SLOW_TICKS, "g86_wet");
        if (!standing(p)) {
            restUntil = age + cd(REST);
            return;
        }
        victim = p;
        clingAt = age;
        noPush = true;
        hover = CLING_HOVER;
        state("cling");
        mark(p);
        Vector off = pos.clone().subtract(p.getLocation().toVector()).setY(0);
        if (off.lengthSquared() < 1e-6) off = new Vector(1, 0, 0);
        clingOff.copy(off.normalize().multiply(CLING_SIDE));
        body.squash(1.2f, 0.8f, 1.2f, 2);
    }

    private void cling() {
        Player p = victim;
        if (!standing(p) || age - clingAt >= CLING) {
            drop();
            return;
        }
        Location l = p.getLocation();
        seek(new Vector(l.getX() + clingOff.getX(), pos.getY(), l.getZ() + clingOff.getZ()), 0.8);
        face(new Vector(l.getX(), pos.getY(), l.getZ()));
        int in = age - clingAt;
        if (in > 0 && in % CLING_EVERY == 0) hit(p, CLING_DMG, "cause_g86");
        // 셋 넘게 매달리면 가장 가까운 물 쪽으로 끌림(맨 앞 번호 하나만 끎)
        if (every(4)) {
            int n = 0, low = Integer.MAX_VALUE;
            for (Gabe g : game.mobs.near(pos, 2.0, g -> g.sp == sp && g instanceof G86 s && s.victim == p)) {
                n++;
                low = Math.min(low, g.id);
            }
            if (n >= DRAG_N && low == id) {
                Vector w = nearestPool(l.getX(), l.getZ());
                if (w != null) knock(p, w.subtract(l.toVector()), DRAG * 4, 0);
            }
        }
    }

    private Vector nearestPool(double x, double z) {
        Vector best = null;
        double bd = 40;
        for (double[] w : pools()) {
            double d = Math.hypot(w[0] - x, w[1] - z);
            if (d < bd) {
                bd = d;
                best = new Vector(w[0], 0, w[1]);
            }
        }
        return best;
    }

    /** 떨어짐: 자빠져 잠깐 허우적. */
    private void drop() {
        victim = null;
        clingAt = -1;
        noPush = false;
        hover = HOVER;
        state("land");
        mark(null);
        restUntil = age + cd(REST);
    }

    /** 매달린 사람이 이것을 보고 좌클릭하면 떨어진다. */
    @Override
    public boolean helped(Player p) {
        if (victim == null || p != victim) return false;
        drop();
        return true;
    }

    @Override
    public String toString() {
        return super.toString() + (victim != null ? " cling" : onWater ? " water" : " land");
    }
}
