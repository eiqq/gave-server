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

/**
 * G64 등 뒤에 앉는 게이브(떼, 방해, 1막). 작은 연 얼굴이 하늘 높이(바닥 위 12) 소리 없이 날아와, 노리는 사람의 시선 반대편(등 뒤 R 6~12)에 하나씩
 * 내려앉아 날개를 접는다(작은 분홍 돌). 한 사람 등 뒤에 8마리 넘게 모이거나, 누가 앉은 것을 똑바로 보면(시선 22도 안) 그 사람 등 뒤의 것들이 모두
 * 한꺼번에 날개를 펴고 얼굴 높이로 덮친다: 하나마다 물기 4, 얼굴 둘레를 맴돌며 0.5초마다 1(3초), 셋 넘게 붙으면 걷기 20% 느려짐. 맴돌기가 끝나면 다시
 * 날아올라 등 뒤에 앉는다. 30초 넘게 앉아 있으면 저절로 덮친다(웨이브 끝을 막지 않게).
 *
 * <p>팩(tools/gabe/sp/g64.py): 모델 {@code gave:gabe/g64} 상태 "fly"(기본, 퍼덕), "sit"(앉음), "dive"(덮침). 소리 land(사뿐 내려앉음, 12),
 * flap(퍼덕, 16). 원인 글귀 cause_g64.
 */
public final class G64 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 나는 높이(바닥 위), 나는 빠르기, 내려앉는 빠르기, 앉은 몸 높이. */
    static final double FLY_H = 12, FLY_V = 0.3, LAND_V = 0.18, SIT_HOVER = 0.25;
    /** 등 뒤 앉을 거리(범위), 등 뒤에서 벗어나는 각(도, 개체마다 ±). */
    static final double PERCH_MIN = 6, PERCH_MAX = 12, PERCH_SPREAD = 50;
    /** 한 사람 등 뒤에 이만큼 모이면 덮침, 모인 수를 세는 반경, 앉은 것을 '본' 시선 각의 코사인, 보는 거리. */
    static final int GATHER = 8;
    static final double GATHER_R = 14, LOOK_COS = 0.927, LOOK_R = 24;
    /** 노리는 사람이 이만큼 멀어지면 다시 날아올라 등 뒤로. */
    static final double REPERCH_R = 16;
    /** 덮칠 때: 고개 드는 틱, 빠르기, 물기 피해, 맴돌기 틱, 맴돌며 피해와 간격, 반지름, 각속도(라디안/틱). */
    static final int RISE = 8, DIVE_MAX = 40, ORBIT = 60, ORBIT_EVERY = 10, SIT_MAX = 600, LIFT = 20;
    static final double DIVE_V = 0.45, BITE_DMG = 4, ORBIT_DMG = 1, ORBIT_R = 0.85, ORBIT_W = 0.28;
    /** 맴도는 수가 이만큼이면 걷기 느려짐(비율). */
    static final int CROWD = 3;
    static final double CROWD_SLOW = 0.2;

    enum Phase { FLY, LAND, SIT, RISE, DIVE, ORBIT, LIFT }

    private Phase phase = Phase.FLY;
    private int phaseAt, riseFor;
    private Player victim, prey;
    private double perchR, perchA, orbitA;
    private int orbitDir = 1;
    private final Vector spot = new Vector();

    public G64(Game game, Species sp) {
        super(game, sp);
        radius = 0.35;
        baseSpeed = FLY_V;
        flying = true;
        noPush = true;
        state("fly");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g64", 1.7f, 0.6f);
    }

    @Override
    protected void spawned() {
        perchR = PERCH_MIN + game.random.nextDouble() * (PERCH_MAX - PERCH_MIN);
        perchA = Math.toRadians((game.random.nextDouble() * 2 - 1) * PERCH_SPREAD);
        orbitDir = game.random.nextBoolean() ? 1 : -1;
        if (!game.director.cageSpawning()) pos.setY(floor() + FLY_H);
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
        switch (phase) {
            case FLY -> fly();
            case LAND -> land();
            case SIT -> sit();
            case RISE -> rise();
            case DIVE -> dive();
            case ORBIT -> orbit();
            case LIFT -> lift();
        }
    }

    /** 등 뒤 자리: 노리는 사람이 보는 쪽 반대편 perchR, 개체마다 perchA 만큼 비낌. */
    private void updateSpot() {
        Location l = victim.getLocation();
        double yw = Math.toRadians(l.getYaw()) + Math.PI + perchA; // 보는 쪽(-sin, cos)의 반대 + 비낌
        double r = perchR;
        spot.setX(l.getX() - Math.sin(yw) * r).setY(0).setZ(l.getZ() + Math.cos(yw) * r);
        // 트럭 위나 바로 곁이면 같은 쪽으로 더 물러나 앉음(지붕 위에 앉지 않게)
        for (int i = 0; i < 4 && kr.gave.trip.Truck.live() && kr.gave.trip.Truck.dist(spot.getX(), spot.getZ()) < 1.5; i++) {
            r += 2.5;
            spot.setX(l.getX() - Math.sin(yw) * r).setZ(l.getZ() + Math.cos(yw) * r);
        }
    }

    /** 높이 소리 없이 등 뒤 자리 위로. */
    private void fly() {
        state("fly");
        if (!standing(victim) || every(40)) victim = nearest(400);
        if (victim == null) {
            follow(speed());
            return;
        }
        if (every(5) || spot.lengthSquared() == 0) updateSpot();
        double fl = floor();
        double dx = spot.getX() - pos.getX(), dz = spot.getZ() - pos.getZ(), hd = Math.hypot(dx, dz);
        double v = speed() * Math.min(1, hd / 3);
        vel.setX(hd > 1e-6 ? dx / hd * v : 0).setZ(hd > 1e-6 ? dz / hd * v : 0).setY((fl + FLY_H - pos.getY()) * 0.1);
        face(pos.clone().add(new Vector(vel.getX(), 0, vel.getZ())));
        if (hd < 3) phase(Phase.LAND);
    }

    /** 자리 위에서 사뿐 내려앉음(자리는 계속 사람 등 뒤로 따라감). */
    private void land() {
        if (!standing(victim)) {
            phase(Phase.FLY);
            return;
        }
        if (every(5)) updateSpot();
        double fl = floor();
        double dx = spot.getX() - pos.getX(), dz = spot.getZ() - pos.getZ(), hd = Math.hypot(dx, dz);
        double v = Math.min(speed(), hd * 0.3);
        vel.setX(hd > 1e-6 ? dx / hd * v : 0).setZ(hd > 1e-6 ? dz / hd * v : 0).setY(-LAND_V * Math.min(1, (pos.getY() - fl) / 2 + 0.3));
        if (hd > 5) phase(Phase.FLY);
        else if (pos.getY() - fl <= SIT_HOVER + 0.2) {
            flying = false;
            hover = SIT_HOVER;
            hold();
            state("sit");
            sound("land", Sound.BLOCK_WOOL_PLACE, 1.4f, 12);
            body.squash(1.2f, 0.8f, 1.2f, 2);
            phase(Phase.SIT);
        }
    }

    /** 날개를 접고 작은 분홍 돌로 앉음. 모이거나 누가 보면 그 사람 등 뒤의 것이 모두 덮침. */
    private void sit() {
        hold();
        if (inPhase() == 3) body.squash(1f, 1f, 1f, 3);
        if (!every(5)) return;
        if (!standing(victim) || dist(victim) > REPERCH_R) {
            takeOff();
            return;
        }
        // 누가 똑바로 봄
        Player looker = null;
        for (Player p : game.mobs.standing()) {
            Location eye = p.getEyeLocation();
            Vector to = pos.clone().subtract(eye.toVector());
            double l = to.length();
            if (l > LOOK_R || l < 1e-6) continue;
            if (to.multiply(1 / l).dot(eye.getDirection()) >= LOOK_COS && sees(p)) {
                looker = p;
                break;
            }
        }
        if (looker != null || inPhase() >= cd(SIT_MAX) || gathered() >= GATHER) wake(victim, looker);
    }

    /** 같은 사람 등 뒤에 앉은 수(나 포함). */
    private int gathered() {
        Vector at = victim.getLocation().toVector();
        Player v = victim;
        return game.mobs.near(at, GATHER_R, g -> g instanceof G64 o && !o.dead && o.phase == Phase.SIT && o.victim == v).size();
    }

    /** 그 사람 등 뒤에 앉은 것들이 모두 한꺼번에 고개를 들고 덮침(본 사람이 가까우면 그 사람에게). */
    private void wake(Player v, Player looker) {
        Vector at = v.getLocation().toVector();
        boolean first = true;
        for (Gabe g : game.mobs.near(at, GATHER_R + 2, g -> g instanceof G64 o && !o.dead && o.phase == Phase.SIT && o.victim == v)) {
            G64 o = (G64) g;
            Player t = looker != null && o.dist(looker) <= 12 ? looker : v;
            o.rouse(t, first ? 0 : game.random.nextInt(5));
            first = false;
        }
    }

    private void rouse(Player t, int delay) {
        prey = t;
        mark(t);
        riseFor = RISE + delay;
        state("fly");
        flying = true;
        noPush = true;
        if (game.random.nextInt(3) == 0) sound("flap", Sound.ENTITY_PHANTOM_FLAP, 1.6f, 16);
        phase(Phase.RISE);
    }

    private void takeOff() {
        flying = true;
        noPush = true;
        state("fly");
        mark(null);
        phase(Phase.LIFT);
    }

    /** 일제히 고개를 들고 날개를 폄(얼굴 높이로 떠오름). */
    private void rise() {
        if (!standing(prey)) {
            takeOff();
            return;
        }
        double fl = floor();
        vel.setX(0).setZ(0).setY((fl + 1.2 - pos.getY()) * 0.25);
        face(prey.getEyeLocation().toVector());
        if (inPhase() >= riseFor) {
            state("dive");
            phase(Phase.DIVE);
        }
    }

    /** 얼굴로 곧장. 닿으면 물고 맴돌기. */
    private void dive() {
        if (!standing(prey)) {
            takeOff();
            return;
        }
        Vector eye = prey.getEyeLocation().toVector();
        Vector d = eye.clone().subtract(pos);
        double l = d.length();
        face(eye);
        if (l < 0.8) {
            hit(prey, BITE_DMG, "cause_g64");
            orbitA = Math.atan2(pos.getZ() - eye.getZ(), pos.getX() - eye.getX());
            state("fly");
            phase(Phase.ORBIT);
            return;
        }
        vel.copy(d.multiply(Math.min(DIVE_V * speed() / baseSpeed, l) / l));
        if (inPhase() >= DIVE_MAX) takeOff();
    }

    /** 얼굴 둘레를 맴돌며 0.5초마다 1. 셋 넘게 붙으면 그 사람이 느려짐. */
    private void orbit() {
        if (!standing(prey) || inPhase() >= ORBIT) {
            takeOff();
            return;
        }
        Vector eye = prey.getEyeLocation().toVector();
        orbitA += ORBIT_W * orbitDir;
        Vector want = eye.clone().add(new Vector(Math.cos(orbitA) * ORBIT_R, Math.sin(age * 0.4) * 0.2, Math.sin(orbitA) * ORBIT_R));
        Vector d = want.subtract(pos);
        double l = d.length();
        if (l > 0.6) d.multiply(0.6 / l);
        vel.copy(d);
        face(eye);
        if (inPhase() % ORBIT_EVERY == ORBIT_EVERY - 1) {
            hit(prey, ORBIT_DMG, "cause_g64");
            Player p = prey;
            int n = game.mobs.near(eye, 2.0, g -> g instanceof G64 o && !o.dead && o.phase == Phase.ORBIT && o.prey == p).size();
            if (n >= CROWD) game.players.slow(p, CROWD_SLOW, ORBIT_EVERY + 4, "g64");
        }
    }

    /** 다시 날아올라 등 뒤로(맴돌기 끝, 노리던 사람이 쓰러짐, 멀어짐). */
    private void lift() {
        double fl = floor();
        Vector away = prey != null && prey.isOnline() ? toward(prey).multiply(-0.2) : new Vector();
        vel.setX(away.getX()).setZ(away.getZ()).setY(Math.min(0.3, (fl + FLY_H - pos.getY()) * 0.15));
        if (inPhase() >= LIFT) {
            prey = null;
            victim = null;
            spot.zero();
            phase(Phase.FLY);
        }
    }

    @Override
    public List<double[]> spheres() {
        double r = phase == Phase.SIT ? 0.3 : 0.4;
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), r});
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (victim != null ? " v=" + victim.getName() : "");
    }
}
