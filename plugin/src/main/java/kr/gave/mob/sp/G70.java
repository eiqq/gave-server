package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;

/**
 * G70 풍선 게이브(떼, 1막). 하늘 높이(바닥 위 22~30) 박힌 듯 떠서 아주 천천히(40초 안팎, 개체마다 어긋나게) 사람들 머리 위로 내려온다. 사람 머리 위
 * 6블록쯤(바닥 위 8)에 닿으면 바람 새는 소리와 바닥 그림자 점(1.2초) 뒤 바람이 빠지며 빠르게 떨어져, 머리 위에서 터지면 반경 2블록 9 피해와 분홍 즙
 * (2초 20% 느려짐). 비켜서 땅에 떨어진 것은 쭈그러진 채 꼭지 막대기로 기어 와 찌른다(5).
 *
 * <p>대처: 높이 있을 때(떨어지는 중 포함) 쏘면 한 발에 아무 일 없이 터지고, 2.5블록 안 옆 풍선도 30% 로 따라 터진다(쏜 사람 몫). 떨어지는 것은 그림자 점에서 비킨다.
 *
 * <p>팩(tools/gabe/sp/g70.py): 모델 {@code gave:gabe/g70} 상태 "" 둥실, "sag" 쭈그러짐(떨어짐), "crawl" 기기. 그림자 {@code gave:gabe/g70_shadow}.
 * 소리 rub(풍선 소리, 40), hiss(바람 새는 소리, 16), pop(풍선 터짐, 32). 원인 글귀 cause_g70(터짐), cause_g70_poke(찌름).
 */
public final class G70 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 처음 높이(바닥 위, 범위), 떨어지기 시작하는 높이(바닥 위: 사람 머리 위 6블록쯤). */
    static final double HIGH_MIN = 22, HIGH_MAX = 30, PRIME_H = 8;
    /** 내려오는 데 걸리는 틱(가운데 값, 개체마다 x0.75..1.25). */
    static final int DESCENT = 800;
    /** 하늘에서 옆으로 흐르는 빠르기, 낮게 내려온 뒤 사람을 따라잡는 빠르기(블록/틱, 감독 배율 곱함). */
    static final double DRIFT_V = 0.11, CATCH_V = 0.16;
    /** 떨어지기 전 예고 틱(소리, 그림자 점), 떨어지는 처음 빠르기, 틱마다 더함, 끝 빠르기, 옆으로 따라가는 빠르기. */
    static final int PRIME = 24;
    static final double DROP_V0 = 0.15, DROP_ACC = 0.045, DROP_MAX = 0.9, DROP_HOME = 0.08;
    /** 터짐 반경, 피해, 즙 느려짐(비율, 틱), 머리 높이에서 이 수평 거리 안에 사람이 있으면 터짐. */
    static final double BURST_R = 2.0, BURST_DMG = 9, SLOW = 0.2, BURST_TRIGGER = 1.6;
    static final int SLOW_TICKS = 40;
    /** 땅에서 기는 빠르기, 찌르기 피해, 쉬는 틱. */
    static final double CRAWL_V = 0.13, POKE_DMG = 5;
    static final int POKE_REST = 25;
    /** 높이 있을 때 맞으면 따라 터지는 반경과 확률. */
    static final double CHAIN_R = 2.5, CHAIN_P = 0.3;
    /** 하늘에 머무는 최대 틱(웨이브 끝을 막지 않게, 넘으면 바로 떨어짐). */
    static final int DRIFT_MAX = 1600;

    enum Phase { DRIFT, PRIME, DROP, CRAWL }

    private Phase phase = Phase.DRIFT;
    private int phaseAt, readyAt;
    private double sink, offA, offR, bobA;
    private Player prey;
    private final Vector spot = new Vector();
    private ItemDisplay shadow;
    /** 따라 터짐 예약(나이, 쏜 사람). */
    private int popAt = -1;
    private Player popBy;

    public G70(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        baseSpeed = CRAWL_V;
        noPush = true;
    }

    @Override
    protected void spawned() {
        bobA = game.random.nextDouble() * Math.PI * 2;
        offA = game.random.nextDouble() * Math.PI * 2;
        offR = game.random.nextDouble() * 6;
        double fl = floor();
        double high = HIGH_MIN + game.random.nextDouble() * (HIGH_MAX - HIGH_MIN);
        if (!game.director.cageSpawning()) pos.setY(fl + high);
        sink = (high - PRIME_H) / (DESCENT * (0.75 + game.random.nextDouble() * 0.5));
        pitch = 50;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (popAt >= 0 && age >= popAt) {
            popAt = -1;
            damage(new Damage(hp + 1, DamageType.OTHER, popBy, pos.clone(), null, "chain", 0));
            return;
        }
        switch (phase) {
            case DRIFT -> drift();
            case PRIME -> prime();
            case DROP -> drop();
            case CRAWL -> crawl();
        }
    }

    /** 하늘에 박힌 듯 아주 천천히: 사람들 머리 위 자리로 흐르며 개체마다 다른 빠르기로 내려옴. 얼굴은 아래를 본다. */
    private void drift() {
        double fl = floor();
        double h = pos.getY() - fl;
        Player p = nearest(400);
        boolean low = h <= PRIME_H + 0.3;
        if (p != null && (every(20) || spot.lengthSquared() == 0)) {
            Location l = p.getLocation();
            double r = low ? 0 : offR;
            spot.setX(l.getX() + Math.cos(offA) * r).setZ(l.getZ() + Math.sin(offA) * r);
        }
        double k = speed() / baseSpeed;
        double dx = spot.getX() - pos.getX(), dz = spot.getZ() - pos.getZ(), hd = Math.hypot(dx, dz);
        double v = (low ? CATCH_V : DRIFT_V) * k * Math.min(1, hd / 2);
        double vx = hd > 1e-6 ? dx / hd * v : 0, vz = hd > 1e-6 ? dz / hd * v : 0;
        double vy = low ? (fl + PRIME_H - pos.getY()) * 0.1 : -sink;
        vel.setX(vx).setY(vy + Math.sin(age * 0.05 + bobA) * 0.01).setZ(vz);
        if (p != null) {
            face(chest(p));
            pitch = Math.max(pitch, 35);
        }
        if (game.random.nextInt(1200) == 0) sound("rub", Sound.ENTITY_PLAYER_SMALL_FALL, 1.6f, 40);
        if (low || inPhase() >= DRIFT_MAX) {
            // 머리 위 2.5블록 안에 선 사람이 있으면 그 사람에게(오래 떠 있었으면 가장 가까운 사람에게라도)
            Player under = null;
            double best = 2.5;
            for (Player q : game.mobs.standing()) {
                double d = Math.hypot(q.getLocation().getX() - pos.getX(), q.getLocation().getZ() - pos.getZ());
                if (d < best) {
                    best = d;
                    under = q;
                }
            }
            if (under == null && inPhase() >= DRIFT_MAX) under = p;
            if (under != null) {
                prey = under;
                mark(under);
                sound("hiss", Sound.BLOCK_FIRE_EXTINGUISH, 1.6f, 16);
                phase(Phase.PRIME);
            }
        }
    }

    /** 예고: 머리 위에서 부르르 떨며 바람 새는 소리, 바닥에 그림자 점. */
    private void prime() {
        double fl = floor();
        if (standing(prey)) {
            Location l = prey.getLocation();
            seek(new Vector(l.getX(), fl + PRIME_H, l.getZ()), 0.05);
            face(chest(prey));
        } else hold();
        if (inPhase() % 2 == 0) {
            float w = (float) Math.sin(inPhase() * 1.3) * 0.08f;
            body.squash(1f + w, 1f - w, 1f + w, 2);
        }
        shadowAt(fl);
        if (inPhase() >= Math.max(14, cd(PRIME))) {
            state("sag");
            body.squash(1f, 1f, 1f, 2);
            vel.zero();
            phase(Phase.DROP);
        }
    }

    /** 바람이 빠지며 빠르게 떨어짐. 머리 높이에서 사람이 곁에 있으면 터지고, 아니면 땅에 떨어져 기어 옴. */
    private void drop() {
        double fl = floor();
        double v = Math.min(DROP_MAX, DROP_V0 + DROP_ACC * inPhase());
        double hx = 0, hz = 0;
        if (standing(prey)) {
            Location l = prey.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ(), d = Math.hypot(dx, dz);
            if (d > 1e-6) {
                hx = dx / d * Math.min(DROP_HOME, d);
                hz = dz / d * Math.min(DROP_HOME, d);
            }
        }
        vel.setX(hx).setY(-v).setZ(hz);
        pitch = 70;
        shadowAt(fl);
        // 머리 높이(사람 눈 + 0.9)까지 내려왔으면 곁의 사람을 보고 터짐
        for (Player p : game.mobs.standing()) {
            double top = p.getEyeLocation().getY() + 0.9;
            if (pos.getY() > top || pos.getY() < p.getLocation().getY()) continue;
            if (Math.hypot(p.getLocation().getX() - pos.getX(), p.getLocation().getZ() - pos.getZ()) <= BURST_TRIGGER) {
                burst();
                return;
            }
        }
        if (pos.getY() - fl <= 1.0) land();
    }

    /** 머리 위에서 터짐: 반경 안 서 있는 사람에게 피해와 즙. 몸은 사라진다(사체 없음, 보상 없음). */
    private void burst() {
        for (Player p : game.mobs.standing()) {
            if (bodyDist(p) <= BURST_R + 0.5) {
                hit(p, BURST_DMG, "cause_g70");
                game.players.slow(p, SLOW, SLOW_TICKS, "g70");
            }
        }
        popFx(true);
        removeShadow();
        remove();
    }

    private double bodyDist(Player p) {
        Location l = p.getLocation();
        double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
        double dy = Math.max(0, Math.max(l.getY() - pos.getY(), pos.getY() - (l.getY() + 1.9)));
        return Math.sqrt(dx * dx + dz * dz + dy * dy);
    }

    /** 땅에 떨어짐: 쭈그러진 채 기기로. */
    private void land() {
        removeShadow();
        flying = false;
        noPush = false;
        hover = 0.82;
        pitch = 0;
        state("crawl");
        body.squash(1.3f, 0.6f, 1.3f, 2);
        sound("hiss", Sound.BLOCK_FIRE_EXTINGUISH, 1.6f, 16);
        mark(null);
        readyAt = age + 10;
        phase(Phase.CRAWL);
    }

    /** 쭈그러진 채 꼭지 막대기로 기어 덤빔. */
    private void crawl() {
        if (inPhase() == 3) body.squash(1f, 1f, 1f, 4);
        follow(speed());
        Player p = nearest(400);
        if (p == null) return;
        face(chest(p));
        if (age >= readyAt && touches(p, 0.2)) {
            hit(p, POKE_DMG, "cause_g70_poke");
            body.squash(0.85f, 1.15f, 1.2f, 2);
            readyAt = age + cd(POKE_REST);
        } else if (age == readyAt + 3) body.squash(1f, 1f, 1f, 3);
    }

    // ------------------------------------------------------------------ 그림자 점(효과 디스플레이)

    private void shadowAt(double fl) {
        double h = Math.max(0, pos.getY() - fl);
        float s = (float) Math.max(0.5, Math.min(1.5, 1.6 - h * 0.12));
        Location at = new Location(game.world, pos.getX(), fl + 0.04, pos.getZ());
        if (shadow == null || !shadow.isValid()) {
            shadow = game.shotFx.spawn(at, "fx_quad", 0, 0, ShotFx.scale(s), Display.Billboard.FIXED, false, PRIME + 80, ShotFx.KEY);
            if (shadow == null) return;
            shadow.setItemStack(Body.item("gabe/g70_shadow", "", new LookState(), game.worldTime()));
            shadow.setBrightness(new Display.Brightness(12, 12));
            shadow.setTeleportDuration(2);
            return;
        }
        if (every(2)) {
            shadow.teleport(at);
            game.shotFx.grow(shadow, ShotFx.scale(s), 2);
        }
    }

    private void removeShadow() {
        if (shadow != null && shadow.isValid()) shadow.remove();
        shadow = null;
    }

    /** 터지는 연출: 분홍 즙 안개와 터짐 소리. */
    private void popFx(boolean big) {
        Location l = new Location(game.world, pos.getX(), pos.getY(), pos.getZ());
        game.shotFx.bloodHit(l, new Vector(0, big ? -1 : 1, 0), big, ShotFx.BLOOD_PINK);
        sound("pop", Sound.ENTITY_GENERIC_EXPLODE, 2f, 32);
    }

    // ------------------------------------------------------------------ 맞음

    private boolean aloft() {
        return phase != Phase.CRAWL;
    }

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.5});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (aloft() && d.amount() > 0 && d.amount() < hp + 1) d = d.times((hp + 1) / d.amount()); // 높이 있으면 한 발에 터짐
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        removeShadow();
        if (aloft()) {
            // 공중에서 터짐: 아무도 안 다치고 살갗이 흩어짐(사체 없음). 옆 풍선이 따라 터짐(쏜 사람 몫)
            leaveCorpse = false;
            popFx(false);
            Player by = d != null ? d.by() : null;
            for (Gabe g : game.mobs.near(pos, CHAIN_R, x -> x instanceof G70 && x != this && !x.dead)) {
                G70 o = (G70) g;
                if (o.aloft() && o.popAt < 0 && game.random.nextDouble() < CHAIN_P) {
                    o.popAt = o.age + 3 + game.random.nextInt(4);
                    o.popBy = by;
                }
            }
        }
        super.die(d);
    }

    @Override
    public void remove() {
        removeShadow();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " h=" + Math.round((pos.getY() - floor()) * 10) / 10.0;
    }
}
