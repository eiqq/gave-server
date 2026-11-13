package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.world.Plaza;

/**
 * G163 프로펠러 모자 게이브(떼, 방해). 정수리 프로펠러를 돌리며 사람 머리 높이로 붕붕 갈지자로 날아와(SPEED) 사람 어깨에 내려앉아(cling) 집게로
 * 문다(PINCH_EVERY 틱마다 PINCH, 붙은 동안 조준 까딱). 한 사람에 셋까지(왼 어깨, 오른 어깨, 정수리). 이미 하나가 붙은 사람을 먼저 노린다.
 *
 * <p>들어 올림: 한 사람에게 둘이 붙으면 프로펠러를 빨리 돌려('붕' 한 음 높게) 그 사람을 붙잡아(Players.grab) 좌석에 태우고 LIFT_T 틱 동안
 * LIFT_2(셋이면 LIFT_3) 블록까지 들어 올리며 떼 쪽으로 CARRY 블록 옮긴 뒤 놓는다: 떨어져 땅에 닿을 때 FALL_2 / FALL_3 피해와 넘어짐.
 * 들려 올라가는 중에 둘 가운데 하나라도 떨어지면(동료가 쏨) 천천히 내려와 피해 없이 놓는다. 든 것(맨 먼저 붙은 것)이 죽거나 떼이면 그 자리에
 * 내려선다. 놓은 뒤 붙었던 것들은 REST 틱 날아올라 쉰다.
 *
 * <p>사람 몸에 붙은 것을 동료가 쏠 때 그 사람 몸이 탄을 막지 않게 Guns 아군 탄 갈고리를 단다(탄 줄기가 붙은 것의 구를 지나면 그것이 맞음).
 *
 * <p>팩 계약(tools/gabe/sp/g163.py): 상태 "", "cling", 소리 buzz(20), lift(24), pinch(12), drop(20), 글귀 cause_g163, cause_g163_pinch.
 */
public final class G163 extends Gabe implements Detachable {

    static final double FLY_UP = 2.0, SPEED = 0.2, SWAY = 0.12, RADIUS = 0.3;
    static final double PINCH = 2, CLING_R = 1.3, PREFER_R = 16;
    static final int PINCH_EVERY = 10, WOBBLE_EVERY = 5, MAX_CLING = 3;
    /** 들어 올림: 틱, 높이(둘, 셋), 옮기는 거리, 떨어짐 피해(둘, 셋), 넘어짐, 천천히 내려옴. */
    static final int LIFT_T = 40, FALL_ROOT = 12, REST = 60;
    static final double LIFT_2 = 6, LIFT_3 = 9, CARRY = 3, FALL_2 = 14, FALL_3 = 24, SLOW_V = 0.12, GRAV = 0.04;
    /** 붙는 자리(사람 발 기준: 몸 왼쪽 몫, 높이): 왼 어깨, 오른 어깨, 정수리. */
    static final double[][] SLOTS = {{0.44, 1.55}, {-0.44, 1.55}, {0.0, 2.15}};

    enum Phase { FLY, CLING, REST }

    /** 한 사람에게 붙은 것들. 맨 앞이 드는 것. */
    static final class Ride {
        final Game game;
        final UUID who;
        final List<G163> on = new ArrayList<>();
        boolean lifting, falling, slow;
        int t;
        double height, topY;
        final Vector start = new Vector(), drift = new Vector(), seat = new Vector();

        Ride(Game game, UUID who) {
            this.game = game;
            this.who = who;
        }
    }

    private static final Map<UUID, Ride> RIDES = new HashMap<>();
    private static Game hooked;

    private Phase phase = Phase.FLY;
    private int phaseAt, slot = -1, pinchAt;
    private Player victim;
    private Ride ride;
    private long rideTick = Long.MIN_VALUE;

    public G163(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        flying = true;
        baseSpeed = SPEED;
    }

    @Override
    protected void spawned() {
        if (!game.director.cageSpawning()) {
            double fl = floor();
            if (pos.getY() < fl + 1.0) pos.setY(fl + FLY_UP);
        }
        hook(game);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case FLY -> flyTick();
            case CLING -> clingTick();
            case REST -> restTick();
        }
    }

    private void flyTick() {
        if (!"".equals(state)) state("");
        Player p = pickTarget();
        if (p == null) {
            vel.zero();
            return;
        }
        if (target != p && dist(p) < 20) mark(p);
        Vector to = chest(p).add(new Vector(0, 0.8, 0)).subtract(pos);
        Vector h = to.clone().setY(0);
        double hl = h.length();
        double spd = speed();
        if (hl > 1e-6) h.multiply(spd / hl);
        // 갈지자(옆으로 흔들림)
        double sway = Math.sin((age + id * 11) * 0.22) * SWAY;
        if (hl > 1e-6) h.add(new Vector(-to.getZ() / hl * sway, 0, to.getX() / hl * sway));
        double wantY = hl > 6 ? floor() + FLY_UP : chest(p).getY() + 0.8;
        vel.copy(h).setY(Math.max(-0.08, Math.min(0.08, (wantY - pos.getY()) * 0.15)));
        face(chest(p));
        if (every(30)) sound("buzz", Sound.ENTITY_BEE_LOOP, 1.6f, 20);
        if (hl <= CLING_R + 0.5 && Math.abs(to.getY()) < 1.8) tryCling(p);
    }

    /** 이미 하나가 붙은(아직 안 든) 사람을 먼저, 아니면 가장 가까운 사람. */
    private Player pickTarget() {
        Player near = nearest(400);
        if (near == null || dist(near) > PREFER_R) return near;
        for (Player o : game.mobs.standing()) {
            Ride r = RIDES.get(o.getUniqueId());
            if (r != null && r.game == game && r.on.size() == 1 && !r.lifting && dist(o) <= PREFER_R) return o;
        }
        return near;
    }

    private void tryCling(Player p) {
        Ride r = RIDES.get(p.getUniqueId());
        if (r != null && r.game != game) {
            RIDES.remove(p.getUniqueId());
            r = null;
        }
        if (r == null) {
            r = new Ride(game, p.getUniqueId());
            RIDES.put(p.getUniqueId(), r);
        }
        if (r.lifting || r.on.size() >= MAX_CLING) return;
        boolean[] used = new boolean[SLOTS.length];
        for (G163 o : r.on) if (o.slot >= 0) used[o.slot] = true;
        int s = 0;
        while (s < SLOTS.length && used[s]) s++;
        if (s >= SLOTS.length) return;
        slot = s;
        ride = r;
        victim = p;
        r.on.add(this);
        noPush = true;
        state("cling");
        mark(p);
        pinchAt = age + cd(PINCH_EVERY);
        phase(Phase.CLING);
    }

    private void clingTick() {
        Player p = victim;
        Ride r = ride;
        if (p == null || r == null || !p.isOnline() || !standing(p) && !r.lifting) {
            letGo();
            return;
        }
        // 드는 것(맨 앞)이 한 틱에 한 번 사람 쪽 일을 맡음
        if (!r.on.isEmpty() && r.on.get(0) == this && rideTick != now()) {
            rideTick = now();
            if (!rideTick(r, p)) return;
        }
        if (dead || phase != Phase.CLING) return;
        Location l = p.getLocation();
        double yr = Math.toRadians(l.getYaw());
        double lx = Math.cos(yr), lz = Math.sin(yr);
        double[] sl = SLOTS[Math.max(0, slot)];
        Vector at = new Vector(l.getX() + lx * sl[0], l.getY() + sl[1], l.getZ() + lz * sl[0]);
        vel.copy(at.subtract(pos));
        // 귀 옆에서 머리를 봄(들려 올라가는 동안은 서로 마주 봄)
        face(p.getEyeLocation().toVector());
        if (!r.lifting) {
            if (age >= pinchAt) {
                pinchAt = age + cd(PINCH_EVERY);
                hit(p, PINCH, "cause_g163_pinch");
                sound("pinch", Sound.ENTITY_FOX_BITE, 1.6f, 12);
            }
            if (every(WOBBLE_EVERY)) Guns.kick(p, (age / WOBBLE_EVERY % 2 == 0 ? 1f : -1f) * 1.2f, -0.4f);
        }
    }

    /** 드는 것의 틱: 둘이 붙으면 들어 올림, 놓음, 떨어짐 피해. false 면 이 개체가 이번 틱에 놓음. */
    private boolean rideTick(Ride r, Player p) {
        r.on.removeIf(o -> o.dead || o.phase != Phase.CLING || o.ride != r);
        if (!r.lifting) {
            if (r.on.size() < 2) return true;
            // 둘이 붙음: 붙잡아 좌석에 태우고 들어 올림
            if (!grab(p, 999)) return true;
            game.players.get(p).grabDetach = true;
            r.lifting = true;
            r.falling = false;
            r.slow = false;
            r.t = 0;
            r.height = r.on.size() >= 3 ? LIFT_3 : LIFT_2;
            Location l = p.getLocation();
            r.start.setX(l.getX()).setY(l.getY()).setZ(l.getZ());
            r.seat.copy(r.start).add(new Vector(0, 0.6, 0));
            r.drift.copy(dropDir(l));
            for (G163 o : r.on) o.mark(p);
            sound("lift", Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 2f, 24);
            return true;
        }
        if (game.players.grabbedBy(p) != this) {
            // 놓쳤음(떼임, 탈출): 그 자리에 내려섬
            endRide(r, false);
            return false;
        }
        r.t++;
        if (!r.falling && !r.slow && r.on.size() < 2) r.slow = true;
        if (r.slow) {
            // 하나가 떨어짐: 천천히 내려와 피해 없이 놓음
            r.seat.setY(r.seat.getY() - SLOW_V);
            if (r.seat.getY() - 0.6 <= ground(r.seat)) {
                endRide(r, false);
                return false;
            }
        } else if (!r.falling) {
            double s = Math.min(1, r.t / (double) LIFT_T);
            double e = s * s * (3 - 2 * s);
            r.seat.copy(r.start).add(r.drift.clone().multiply(CARRY * e)).add(new Vector(0, 0.6 + r.height * e, 0));
            if (r.t >= LIFT_T) {
                r.falling = true;
                r.topY = r.seat.getY();
                r.t = 0;
            }
        } else {
            r.seat.setY(r.topY - GRAV * r.t * r.t);
            if (r.seat.getY() - 0.6 <= ground(r.seat)) {
                endRide(r, true);
                return false;
            }
        }
        game.players.carry(p, r.seat);
        return true;
    }

    /** 놓음. dropped 면 떨어진 피해와 넘어짐. 붙었던 것들은 날아올라 쉼. */
    private void endRide(Ride r, boolean dropped) {
        Player p = game.plugin.getServer().getPlayer(r.who);
        double h = r.height;
        r.lifting = r.falling = r.slow = false;
        List<G163> all = new ArrayList<>(r.on);
        r.on.clear();
        RIDES.remove(r.who);
        if (p != null && game.players.grabbedBy(p) == this) game.players.release(p);
        if (dropped && p != null && standing(p)) {
            hit(p, h >= LIFT_3 ? FALL_3 : FALL_2, "cause_g163");
            game.players.root(p, FALL_ROOT, "g163");
            sound("drop", Sound.ENTITY_PLAYER_BIG_FALL, 0.9f, 20);
        }
        for (G163 o : all) o.rest();
    }

    /** 떼 쪽: 사람 곁 20블록 안 다른 게이브 무리 쪽, 없으면 원 바깥 쪽. */
    private Vector dropDir(Location l) {
        Vector c = new Vector();
        int n = 0;
        for (Gabe g : game.mobs.near(l.toVector(), 20, g -> !(g instanceof G163) && !g.sp.is(kr.gave.mob.Role.BOSS))) {
            c.add(g.pos);
            n++;
            if (n >= 12) break;
        }
        Vector d = n > 0 ? c.multiply(1.0 / n).subtract(l.toVector()).setY(0)
                : new Vector(l.getX() - (Plaza.CX + 0.5), 0, l.getZ() - (Plaza.CZ + 0.5));
        if (d.lengthSquared() < 1e-6) d = new Vector(1, 0, 0);
        return d.normalize();
    }

    private double ground(Vector at) {
        return game.mobs.flow.floor(at.getX(), at.getZ());
    }

    private void letGo() {
        Ride r = ride;
        if (r != null) {
            if (!r.on.isEmpty() && r.on.get(0) == this && r.lifting) {
                endRide(r, false);
                return;
            }
            r.on.remove(this);
            if (r.on.isEmpty()) RIDES.remove(r.who);
        }
        rest();
    }

    private void rest() {
        ride = null;
        victim = null;
        slot = -1;
        noPush = false;
        if (dead) return;
        state("");
        mark(null);
        phase(Phase.REST);
    }

    private void restTick() {
        Player p = nearest(400);
        Vector away = p == null ? new Vector(0, 0, 0) : pos.clone().subtract(p.getLocation().toVector()).setY(0);
        if (away.lengthSquared() > 1e-6) away.normalize().multiply(speed());
        double wantY = floor() + FLY_UP + 1.5;
        vel.copy(away).setY(Math.max(-0.08, Math.min(0.1, (wantY - pos.getY()) * 0.15)));
        if (p != null) face(chest(p));
        if (age - phaseAt >= cd(REST)) phase(Phase.FLY);
    }

    // ------------------------------------------------------------------ 떼기, 죽음

    @Override
    public void detached(Player p, Player helper, String how) {
        Ride r = ride;
        if (r != null && r.lifting && !r.on.isEmpty() && r.on.get(0) == this) {
            r.lifting = false;
            endRide(r, false);
        }
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        Ride r = ride;
        boolean lead = r != null && !r.on.isEmpty() && r.on.get(0) == this;
        if (r != null) {
            r.on.remove(this);
            if (lead && r.lifting) {
                // 든 것이 죽음: 그 자리에 내려섬(엔진이 붙잡힘을 풂), 남은 것은 날아오름
                List<G163> all = new ArrayList<>(r.on);
                r.on.clear();
                r.lifting = false;
                RIDES.remove(r.who);
                for (G163 o : all) o.rest();
            } else if (r.on.isEmpty()) RIDES.remove(r.who);
        }
        ride = null;
        super.die(d);
    }

    // ------------------------------------------------------------------ 아군 탄 갈고리: 사람 몸에 붙은 것을 동료가 쏨

    private static void hook(Game game) {
        if (hooked == game) return;
        hooked = game;
        RIDES.clear();
        game.guns.addAllyShot((shooter, victim, from, dir, dist, spec) -> {
            Ride r = RIDES.get(victim.getUniqueId());
            if (r == null || r.game != game || r.on.isEmpty()) return false;
            G163 best = null;
            double bestAt = dist + 1.2;
            for (G163 c : r.on) {
                if (c.dead) continue;
                Vector oc = c.pos.clone().subtract(from);
                double along = oc.dot(dir);
                if (along < 0 || along > bestAt) continue;
                double off2 = oc.lengthSquared() - along * along;
                double rr = c.radius + 0.25;
                if (off2 > rr * rr) continue;
                bestAt = along;
                best = c;
            }
            if (best == null) return false;
            Vector at = from.clone().add(dir.clone().multiply(bestAt));
            game.mobs.damage(best, new Damage(spec.damage(), DamageType.BULLET, shooter, at, dir.clone(), spec.key(), 0));
            return true;
        });
    }

    /** 시험용. */
    public String info() {
        Ride r = ride;
        return phase + (r == null ? "" : " on=" + r.on.size() + (r.lifting ? " lift" : "") + (r.falling ? " fall" : "") + (r.slow ? " slow" : ""))
                + " rides=" + RIDES.size();
    }

    @Override
    public String toString() {
        return super.toString() + " " + info();
    }

}
