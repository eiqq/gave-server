package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G95 두 번째 그림자 게이브(떼, 방해). 바닥에 납작 붙은 검은 얼굴 그림자가 빠르게 미끄러져 사람 발밑으로 들어가 그 사람 발에서 비스듬히 뻗은 두 번째
 * 그림자로 붙는다(붙은 그림자마다 다른 각으로 부채꼴). 붙은 동안 그림자마다 초당 3, 셋 넘게 붙으면 걷기 20% 느려짐.
 *
 * <p>대처: 동료가 그 사람을 쏘면 탄이 몸에 막히지 않고 발밑 그림자 하나에 맞는다(아군 피해 없음, 한 발: 아군 탄 갈고리). 본인은 고개를 숙여 제 발밑
 * 그림자를 쏘거나, 3초 안에 두 번 크게 뛰면 하나가 떨어져 나간다(1.5초 뒤 다시 붙으러 옴). 사체는 없다(그림자가 흩어짐).
 *
 * <p>팩 계약(tools/gabe/sp/g95.py): 모델 gave:gabe/g95 상태 "slide"(기본), "stick". 소리 slither(12), 원인 글귀 cause_g95.
 */
public final class G95 extends Gabe {

    static final double SLIDE_V = 0.3, ATTACH_R = 0.9, GNAW = 3, SLOW = 0.2, HOVER = 0.02, R = 0.5, REACH = 0.6;
    static final int GNAW_EVERY = 20, SLOW_N = 3, SLOW_TICKS = 30, JUMP_WINDOW = 60, OFF_TICKS = 30, SLITHER_EVERY = 30;
    static final double JUMP_VY = 0.3, SHAKE_V = 0.6;
    static final int PACK = 4;

    private boolean packed, rising;
    private Player victim;
    private int attachAt = -1, offUntil = -1, jumps, jumpAt = -1, slot;
    private float fan;

    public G95(Game game, Species sp) {
        super(game, sp);
        radius = R;
        hover = HOVER;
        baseSpeed = SLIDE_V;
        leaveCorpse = false;
        state("slide");
        Ally.of(game);
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g95", 1.4f, 0.3f);
    }

    @Override
    public void tick() {
        if (!packed) {
            packed = true;
            G82Pack.fill(this, PACK - 1, 2.0);
        }
        if (victim != null) {
            stuck();
            return;
        }
        Player p = nearest(400);
        if (age < offUntil) {
            // 떨려 나가 미끄러짐
            vel.multiply(0.85);
            return;
        }
        if (p == null) {
            follow(speed());
            face(pos.clone().add(vel));
            return;
        }
        Location l = p.getLocation();
        double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
        if (dist(p) > 6) follow(speed());
        else seek(new Vector(l.getX(), pos.getY(), l.getZ()), speed());
        face(pos.clone().add(vel));
        if (every(SLITHER_EVERY) && dist(p) < 14) sound("slither", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.6f, 12);
        if (dx * dx + dz * dz <= ATTACH_R * ATTACH_R) attach(p);
    }

    private void attach(Player p) {
        victim = p;
        attachAt = age;
        jumps = 0;
        jumpAt = -1;
        noPush = true;
        ghost = true;
        state("stick");
        mark(p);
        // 이미 붙은 그림자 수만큼 다른 각으로(부채꼴)
        slot = attached(p) - 1;
        fan = 35f + slot * 47f;
        sound("slither", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.6f, 12);
    }

    /** 이 사람 발밑에 붙은 그림자 수(자신 포함). */
    private int attached(Player p) {
        int n = 0;
        for (Gabe g : game.mobs.near(p.getLocation().toVector(), 3.0, g -> g instanceof G95 s && s.victim == p)) n++;
        return n;
    }

    private void stuck() {
        Player p = victim;
        if (!standing(p)) {
            unstick(false);
            return;
        }
        Location l = p.getLocation();
        // 발에 붙어 따라다님(늘 같은 각으로 비스듬히)
        seek(new Vector(l.getX(), pos.getY(), l.getZ()), 1.2);
        yaw = fan;
        int in = age - attachAt;
        if (in > 0 && in % cd(GNAW_EVERY) == 0) {
            hit(p, GNAW, "cause_g95");
            if (!standing(p)) {
                unstick(false);
                return;
            }
            if (attached(p) >= SLOW_N) game.players.slow(p, SLOW, SLOW_TICKS, "g95");
        }
        // 크게 두 번 뛰면 하나(가장 먼저 붙은 것)가 떨어짐
        double vy = p.getVelocity().getY();
        if (vy > JUMP_VY && !rising) {
            rising = true;
            if (jumpAt < 0 || age - jumpAt > JUMP_WINDOW) jumps = 0;
            jumpAt = age;
            jumps++;
            if (jumps >= 2 && firstOn(p)) {
                unstick(true);
                return;
            }
        } else if (vy <= 0.05) rising = false;
    }

    /** 이 사람에게 붙은 그림자 가운데 이것이 가장 먼저 붙었나(떨어질 차례). */
    private boolean firstOn(Player p) {
        for (Gabe g : game.mobs.near(p.getLocation().toVector(), 3.0, g -> g instanceof G95 s && s.victim == p))
            if (g != this && ((G95) g).attachAt < attachAt) return false;
        return true;
    }

    private void unstick(boolean shaken) {
        Player p = victim;
        victim = null;
        attachAt = -1;
        noPush = false;
        ghost = false;
        state("slide");
        mark(null);
        if (shaken && p != null) {
            Vector away = pos.clone().subtract(p.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            dash(away, SHAKE_V);
            offUntil = age + OFF_TICKS;
        }
    }

    @Override
    public void die(Damage d) {
        victim = null;
        super.die(d);
    }

    /** 맞는 구: 붙었으면 그림자 가운데(발에서 비스듬히 0.7), 아니면 몸 가운데. 땅 높이라 고개를 숙여 쏜다. */
    @Override
    public List<double[]> spheres() {
        if (victim == null) return List.of(new double[] {pos.getX(), pos.getY() + 0.15, pos.getZ(), radius});
        double r = Math.toRadians(yaw);
        return List.of(new double[] {pos.getX() - Math.sin(r) * 0.7, pos.getY() + 0.15, pos.getZ() + Math.cos(r) * 0.7, radius});
    }

    @Override
    public String toString() {
        return super.toString() + (victim != null ? " stuck slot=" + slot : "");
    }

    /** 아군 탄 갈고리(판마다 하나): 동료 몸에 닿은 탄이 그 사람 발밑 그림자에 맞는다(가장 가까운 것 하나, 한 발). */
    static final class Ally implements Guns.AllyShot {
        private static Ally inst;
        private final Game game;

        private Ally(Game game) {
            this.game = game;
        }

        static void of(Game g) {
            if (g.guns == null) return;
            if (inst == null || inst.game != g) {
                if (inst != null && inst.game.guns != null) inst.game.guns.removeAllyShot(inst);
                inst = new Ally(g);
                g.guns.addAllyShot(inst);
            }
        }

        @Override
        public boolean take(Player shooter, Player victim, Vector from, Vector dir, double dist, Guns.Spec spec) {
            if (victim == null) return false;
            Vector at = from.clone().add(dir.clone().multiply(dist));
            G95 best = null;
            double bd = Double.MAX_VALUE;
            for (Gabe g : game.mobs.near(victim.getLocation().toVector(), 3.0, x -> x instanceof G95 s && s.victim == victim)) {
                double d = g.pos.distanceSquared(at);
                if (d < bd) {
                    bd = d;
                    best = (G95) g;
                }
            }
            if (best == null) return false;
            double dmg = Math.max(spec.damage(), best.hp + 1);
            game.mobs.damage(best, new Damage(dmg, DamageType.BULLET, shooter, best.pos.clone(), dir.clone(), spec.key(), 0));
            return true;
        }
    }
}
