package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G160 엉뚱 달리기 게이브(떼, 방해). 긴 막대기 팔다리로 네 발로 엎드려 지그재그로 내달리는 작은 인형(SPEED, ZIG_MIN..ZIG_MAX 틱마다 방향이 확
 * 꺾임). 가장 가까운 사람은 본체만체 지나치고, 이 개체에서 가장 먼 무리(GROUP_R 안에 GROUP_MIN 명 넘게 모인 곳, 없으면 가장 먼 한 사람)로
 * 곧장 뛰어든다. 고른 무리 쪽으로 눈을 뜨고(그 무리 사람 조준점 옆에 이 종 표시), 무리에 닿으면 들이받기 RAM.
 *
 * <p>던지기: 무리 사람 THROW_REACH 안이면 WIND 틱 몸을 뒤로 젖혔다가(throw) 그 사람 다리를 붙들어 머리 위로 넘겨 던진다(뒤 위쪽으로 밀침, 약
 * 5블록). 던져진 사람은 땅에 떨어질 때 THROW_LAND 피해와 넘어짐, 떨어진 자리 SPLASH_R 안 다른 사람은 SPLASH 피해와 밀림. 젖힐 때 한 걸음
 * 물러서면 붙들지 못한다. 던진 뒤 다른 먼 무리를 고른다.
 *
 * <p>팩 계약(tools/gabe/sp/g160.py): 상태 "", "throw", 소리 scamper(20), laugh(24), throw(20), 글귀 cause_g160.
 */
public final class G160 extends Gabe {

    static final double HOVER = 0.42, RADIUS = 0.42, SPEED = 0.36;
    static final double RAM = 5, THROW_LAND = 9, SPLASH = 6;
    static final int RAM_CD = 60, THROW_CD = 80, WIND = 10, THROW_ROOT = 10, MISS_CD = 24;
    /** 무리: 반지름, 최소 수(나 포함), 다시 고르는 틱. */
    static final double GROUP_R = 4;
    static final int GROUP_MIN = 3, REPICK = 20;
    /** 지그재그: 꺾임 간격, 옆 몫. */
    static final int ZIG_MIN = 20, ZIG_MAX = 40;
    static final double ZIG = 0.8;
    /** 던지기: 붙드는 거리, 밀침 세기와 위, 떨어진 자리 곁. */
    static final double THROW_REACH = 1.5, THROW_V = 0.52, THROW_UP = 0.74, SPLASH_R = 1.5;

    /** 던져서 날아가는 사람. */
    private record Thrown(Player p, int at) {
    }

    private final Vector groupAt = new Vector();
    private final List<Player> group = new ArrayList<>();
    private final List<Thrown> thrown = new ArrayList<>();
    private Player avoid, victim;
    private int pickAt = -1000, zigAt, zigSide = 1, ramReady, throwReady, windEnd = -1, avoidUntil;
    private boolean hasGroup;

    public G160(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void spawned() {
        zigSide = game.random.nextBoolean() ? 1 : -1;
        zigAt = game.random.nextInt(ZIG_MIN);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        landings();
        if (windEnd >= 0) {
            windup();
            return;
        }
        if (age - pickAt >= REPICK || !hasGroup) pick();
        if (!hasGroup) {
            follow(speed());
            face(pos.clone().add(vel));
            pitch = 0;
            return;
        }
        // 고른 무리로 지그재그
        Vector dir = groupAt.clone().subtract(pos).setY(0);
        double d = dir.length();
        if (d > 1e-6) dir.multiply(1 / d);
        if (age >= zigAt) {
            zigSide = -zigSide;
            zigAt = age + ZIG_MIN + game.random.nextInt(ZIG_MAX - ZIG_MIN + 1);
        }
        Vector perp = new Vector(-dir.getZ(), 0, dir.getX());
        double zig = d > 3 ? ZIG : 0.2;
        Vector run = dir.clone().add(perp.multiply(zigSide * zig));
        dash(run, speed());
        face(pos.clone().add(vel));
        pitch = 0;
        if (every(14)) sound("scamper", Sound.ENTITY_SPIDER_STEP, 1.6f, 20);
        if (d < 14 && every(100)) sound("laugh", Sound.ENTITY_WITCH_CELEBRATE, 1.4f, 24);
        // 무리 사람: 닿으면 들이받기, 붙들 거리면 던지기 준비
        for (Player p : group) {
            if (!standing(p)) continue;
            double pd = dist(p);
            if (age >= throwReady && pd <= THROW_REACH + radius) {
                victim = p;
                windEnd = age + WIND;
                state("throw");
                mark(p);
                hold();
                return;
            }
            if (age >= ramReady && touches(p, 0.1)) {
                ramReady = age + cd(RAM_CD);
                hit(p, RAM, "cause_g160");
                knock(p, toward(p), 0.3, 0.1);
            }
        }
    }

    /** 몸을 뒤로 젖힘(예고) 뒤 붙들어 머리 위로 넘김. */
    private void windup() {
        hold();
        Player p = victim;
        if (p != null && p.isOnline()) face(chest(p));
        pitch = 0;
        if (age < windEnd) return;
        windEnd = -1;
        state("");
        if (p == null || !standing(p) || dist(p) > THROW_REACH + radius + 0.45) {
            // 한 걸음 물러서서 못 붙듦
            throwReady = age + cd(MISS_CD);
            victim = null;
            return;
        }
        throwReady = age + cd(THROW_CD);
        Vector over = toward(p).multiply(-1);
        if (over.lengthSquared() < 1e-6) over = new Vector(1, 0, 0);
        knock(p, over, THROW_V, THROW_UP);
        thrown.add(new Thrown(p, age));
        sound("throw", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 20);
        body.squash(0.8f, 1.3f, 0.8f, 3);
        victim = null;
        // 다른 먼 무리로(지금 무리는 한동안 빼고)
        avoid = p;
        avoidUntil = age + 120;
        hasGroup = false;
    }

    /** 던진 사람이 땅에 떨어짐. */
    private void landings() {
        if (thrown.isEmpty()) return;
        for (Iterator<Thrown> it = thrown.iterator(); it.hasNext();) {
            Thrown t = it.next();
            Player p = t.p();
            if (!p.isOnline() || !game.players.get(p).standing()) {
                it.remove();
                continue;
            }
            int air = age - t.at();
            if (air < 5 || !(p.isOnGround() || air > 40)) continue;
            it.remove();
            hit(p, THROW_LAND, "cause_g160");
            game.players.root(p, THROW_ROOT, "g160");
            sound("throw", Sound.ENTITY_PLAYER_BIG_FALL, 0.9f, 20);
            Location l = p.getLocation();
            for (Player o : game.mobs.standing()) {
                if (o == p) continue;
                Location ol = o.getLocation();
                double dx = ol.getX() - l.getX(), dz = ol.getZ() - l.getZ();
                if (dx * dx + dz * dz > SPLASH_R * SPLASH_R) continue;
                hit(o, SPLASH, "cause_g160");
                knock(o, new Vector(dx, 0, dz), 0.35, 0.15);
            }
        }
    }

    /** 이 개체에서 가장 먼 무리(셋 넘게 모인 곳), 없으면 가장 먼 한 사람. */
    private void pick() {
        pickAt = age;
        if (avoid != null && age >= avoidUntil) avoid = null;
        List<Player> st = game.mobs.standing();
        Player bestSeed = null;
        double best = -1;
        boolean bestIsGroup = false;
        for (Player a : st) {
            Location la = a.getLocation();
            int n = 0;
            for (Player b : st) {
                Location lb = b.getLocation();
                double dx = la.getX() - lb.getX(), dz = la.getZ() - lb.getZ();
                if (dx * dx + dz * dz <= GROUP_R * GROUP_R) n++;
            }
            boolean isGroup = n >= GROUP_MIN;
            double d = dist(a);
            // 던진 사람 곁 무리는 덜 고름(다른 먼 무리로)
            if (avoid != null && avoid.isOnline() && la.distance(avoid.getLocation()) <= GROUP_R) d *= 0.35;
            if (isGroup && !bestIsGroup || isGroup == bestIsGroup && d > best) {
                best = d;
                bestSeed = a;
                bestIsGroup = isGroup;
            }
        }
        group.clear();
        if (bestSeed == null) {
            hasGroup = false;
            if (target != null) mark(null);
            return;
        }
        Location ls = bestSeed.getLocation();
        groupAt.zero();
        for (Player b : st) {
            Location lb = b.getLocation();
            double dx = ls.getX() - lb.getX(), dz = ls.getZ() - lb.getZ();
            if (dx * dx + dz * dz <= GROUP_R * GROUP_R) {
                group.add(b);
                groupAt.add(lb.toVector());
            }
        }
        groupAt.multiply(1.0 / group.size());
        hasGroup = true;
        // 무리에서 가장 가까운 사람을 찍고(눈 뜸), 무리 사람 조준점 옆에 이 종 표시
        Player near = null;
        double nd = Double.MAX_VALUE;
        for (Player b : group) {
            double d = dist(b);
            if (d < nd) {
                nd = d;
                near = b;
            }
        }
        if (near != target) mark(near);
        if (nd < 40) for (Player b : group) game.hud.mark(b, "g160", REPICK + 10);
    }

    @Override
    public void die(Damage d) {
        thrown.clear();
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " group=" + group.size() + (windEnd >= 0 ? " wind" : "") + " thrown=" + thrown.size();
    }
}
