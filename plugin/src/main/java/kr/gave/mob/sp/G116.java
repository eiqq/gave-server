package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G116 수레바퀴 게이브(3막, 돌격, 방해, 목표 압박). 지름 5블록 불바퀴가 세로로 서서 곧게 굴러온다(0.22블록/틱, 구를수록 빨라져 0.36까지). 겨눈 자리(사람
 * 무리, 둘에 한 번은 트럭)를 꿰뚫고 지나가 20블록 밖에서 크게 돌아 다시 온다. 깔리면 26 과 옆으로 튕김. 바퀴에 조준선을 0.5초 넘게 댄 사람이 있으면
 * 그 사람이 아니라 그 사람 4블록 안의 동료 모두의 발밑에 주황 불이 0.5초 먼저 일고 불똥이 튄다(10, 화상 2초). 트럭에 닿으면 들이받아 트럭을 깎고
 * 튕겨 나간다. 굴대 얼굴(부품, 늘 수평으로 사람 쪽 옆을 봄)이 약점(x2).
 *
 * <p>팩 계약(tools/gabe/sp/g116.py): 바퀴 gabe/g116(shrink 0.4, 바퀴는 YZ 평면, 앞 +Z 로 구름), 굴대 얼굴 gabe/g116_hub. 소리 roll, spark, ram.
 * 글귀 cause_g116, cause_g116_spark.
 *
 * <p>도감과 다른 점: 구름은 몸 pitch 를 돌려 보간(15도마다 보냄). 화상은 1초마다 2 두 번(바닐라 불이 아님). 조준 머묾은 2틱마다 잰다.
 */
public final class G116 extends Gabe {

    static final double HOVER = 2.65, WHEEL_R = 2.6, V0 = 0.22, VMAX = 0.36, ACCEL = 0.0015, PAST = 20, CRUSH = 26, SPARK = 10, BURN = 2,
        SPARK_R = 4, HUB_MUL = 2.0;
    static final int LOOK_T = 10, SPARK_TELL = 10, SPARK_CD = 30, HIT_CD = 20, TURN_MAX = 240;

    enum Phase { ROLL, TURN }

    private Phase phase = Phase.ROLL;
    private int phaseAt, rolls, crushes, sparks, rams, rollSoundAt;
    private double v = V0;
    private float spin;
    private final Vector dir = new Vector(0, 0, 1), aim = new Vector();
    private Body.Part hub;
    private int hubSide = 1, hubPosedAt = -100;
    private float hubYaw = Float.NaN;
    private final Map<UUID, Integer> looking = new HashMap<>(), sparkReady = new HashMap<>(), hitAt = new HashMap<>();
    /** 불똥 예약: 사람, 칠 틱, 단계(0 불똥, 1..2 화상). */
    private final List<Object[]> pending = new ArrayList<>();
    private final List<double[]> sph = new ArrayList<>(6);
    private int sphAge = -1;

    public G116(Game game, Species sp) {
        super(game, sp);
        radius = 1.6;
        hover = HOVER;
        baseSpeed = V0;
        big = true;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragKick = 0.6;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g116", 7f, 7f);
        body.viewRange(2.5f);
        hub = body.attach("gabe/g116_hub");
        if (hub != null) hub.follow = false;
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pickAim();
        yaw = G129Kit.yawOf(dir);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        Player p = nearest(400);
        mark(p != null && dist(p) < 40 ? p : null);
        switch (phase) {
            case ROLL -> roll();
            case TURN -> turn();
        }
        // 구름: 앞으로 굴러간 만큼 바퀴를 돌림(pitch, 위가 앞으로)
        double moved = Math.hypot(vel.getX(), vel.getZ());
        spin = (spin + (float) Math.toDegrees(moved / WHEEL_R)) % 360f;
        pitch = spin;
        hubTick(p);
        if (every(2)) watchers();
        sparksTick();
        if (age - rollSoundAt >= 30 && moved > 0.05) {
            sound("roll", Sound.BLOCK_WOOD_STEP, 0.5f, 40);
            rollSoundAt = age;
        }
    }

    /** 겨눌 자리: 둘에 한 번은 트럭(서 있으면), 아니면 가장 가까운 사람 무리. */
    private void pickAim() {
        Truck t = truck();
        Player p = nearest(400);
        if (t != null && (rolls % 2 == 1 || p == null)) aim.copy(Truck.center());
        else if (p != null) aim.copy(p.getLocation().toVector());
        else aim.copy(new Vector(Plaza.CX + 0.5, pos.getY(), Plaza.CZ + 0.5));
        Vector d = aim.clone().subtract(pos).setY(0);
        if (d.lengthSquared() < 1) d = G129Kit.fwd(this);
        dir.copy(d.normalize());
        rolls++;
    }

    private Truck truck() {
        Truck t = game.trip == null ? null : game.trip.truck();
        return t != null && t.alive() ? t : null;
    }

    private void roll() {
        v = Math.min(VMAX, v + ACCEL);
        double sp = v * game.director.speedMul();
        vel.setX(dir.getX() * sp).setZ(dir.getZ() * sp);
        yaw = G129Kit.yawOf(dir);
        crush();
        ram();
        // 겨눈 자리를 지나 20블록 밖이면 돈다(경기장 끝 가까이여도)
        double rx = pos.getX() - aim.getX(), rz = pos.getZ() - aim.getZ();
        double along = rx * dir.getX() + rz * dir.getZ();
        double r = Math.hypot(pos.getX() - Plaza.CX - 0.5, pos.getZ() - Plaza.CZ - 0.5);
        if ((along > PAST) || r > Plaza.R_EDGE - 8 && along > 0 || age - phaseAt > 1200) phase(Phase.TURN);
    }

    /** 크게 돌기: 한 틱 3도씩, 0.2 로 구르며 새 겨눔 쪽으로. */
    private void turn() {
        if (age - phaseAt == 0) pickAim();
        float want = G129Kit.yawOf(dir);
        float d = ((want - yaw) % 360f + 540f) % 360f - 180f;
        yaw += Math.max(-3f, Math.min(3f, d));
        Vector f = G129Kit.fwd(this);
        double sp = 0.2 * game.director.speedMul();
        vel.setX(f.getX() * sp).setZ(f.getZ() * sp);
        crush();
        if (Math.abs(d) < 4 || age - phaseAt > TURN_MAX) {
            yaw = want;
            v = V0;
            phase(Phase.ROLL);
        }
    }

    /** 깔림: 바퀴 아래(굴러가는 줄 위 폭 1.8, 앞 2 뒤 1.2) 사람 26 과 옆으로 튕김. */
    private void crush() {
        Vector f = G129Kit.fwd(this);
        Vector l = G129Kit.left(this);
        for (Player q : game.mobs.standing()) {
            Location ql = q.getLocation();
            double rx = ql.getX() - pos.getX(), rz = ql.getZ() - pos.getZ();
            double along = rx * f.getX() + rz * f.getZ(), lat = rx * l.getX() + rz * l.getZ();
            if (Math.abs(lat) > 0.95 || along < -1.2 || along > 2.0 || ql.getY() > pos.getY() + 1) continue;
            Integer last = hitAt.get(q.getUniqueId());
            if (last != null && age - last < HIT_CD) continue;
            hitAt.put(q.getUniqueId(), age);
            hit(q, CRUSH, "cause_g116");
            knock(q, l.clone().multiply(lat >= 0 ? 1 : -1).add(f.clone().multiply(0.3)), 1.0, 0.35);
            crushes++;
        }
    }

    /** 트럭에 닿으면 들이받고 튕겨 나감. */
    private void ram() {
        Truck t = truck();
        if (t == null || Truck.dist(pos.getX(), pos.getZ()) > 1.2) return;
        t.hit(t.biteDamage(sp) * 6, this, "cause_g116");
        sound("ram", Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.6f, 48);
        if (game.horizon != null) game.horizon.shake(0.3, 8);
        G129Kit.ring(this, G129Kit.ground(this, pos), 2.5, 10);
        rams++;
        dir.multiply(-1);
        aim.copy(pos);
        yaw = G129Kit.yawOf(dir);
        v = V0;
        Vector back = dir.clone().multiply(1.5);
        pos.add(back);
    }

    // ------------------------------------------------------------------ 굴대 얼굴

    /** 굴대 얼굴: 바퀴가 돌아도 수평으로, 사람이 있는 옆쪽을 봄. */
    private void hubTick(Player p) {
        if (hub == null) return;
        if (p != null) {
            Location l = p.getLocation();
            Vector lf = G129Kit.left(this);
            hubSide = (l.getX() - pos.getX()) * lf.getX() + (l.getZ() - pos.getZ()) * lf.getZ() >= 0 ? 1 : -1;
        }
        float want = yaw + (hubSide > 0 ? -90f : 90f);
        if (!Float.isNaN(hubYaw) && Math.abs(((want - hubYaw) % 360f + 540f) % 360f - 180f) < 4f && age - hubPosedAt < 20) return;
        hubYaw = want;
        hubPosedAt = age;
        Vector side = G129Kit.left(this).multiply(hubSide * 0.62);
        hub.pose(new Matrix4f().translate((float) side.getX(), 0, (float) side.getZ()).rotateY((float) Math.toRadians(-want)), 3);
    }

    // ------------------------------------------------------------------ 불똥(본 사람 곁 동료가 탐)

    private void watchers() {
        for (Player p : game.mobs.standing()) {
            UUID id = p.getUniqueId();
            if (game.mobs.looked(p, 60) != this) {
                looking.remove(id);
                continue;
            }
            int n = looking.merge(id, 2, Integer::sum);
            Integer ready = sparkReady.get(id);
            if (n < LOOK_T || ready != null && age < ready) continue;
            sparkReady.put(id, age + cd(SPARK_CD));
            looking.remove(id);
            Location pl = p.getLocation();
            for (Player q : game.mobs.standing()) {
                if (q == p || q.getLocation().distanceSquared(pl) > SPARK_R * SPARK_R) continue;
                Location ql = q.getLocation();
                if (game.shotFx.room(ShotFx.NORMAL)) game.shotFx.flame(ql.clone().add(0, 0.1, 0), 1.2f, SPARK_TELL + 4, ShotFx.KEY);
                G129Kit.warn(this, ql.toVector(), 0.9, SPARK_TELL + 2);
                pending.add(new Object[] {q, age + SPARK_TELL, 0});
            }
        }
    }

    private final List<Object[]> later = new ArrayList<>();

    private void sparksTick() {
        if (pending.isEmpty()) return;
        later.clear();
        for (Iterator<Object[]> it = pending.iterator(); it.hasNext();) {
            Object[] e = it.next();
            Player q = (Player) e[0];
            int at = (Integer) e[1], stage = (Integer) e[2];
            if (age < at) continue;
            it.remove();
            if (!q.isOnline() || !standing(q)) continue;
            if (stage == 0) {
                hit(q, SPARK, "cause_g116_spark");
                sound("spark", Sound.ENTITY_BLAZE_SHOOT, 1.2f, 24);
                sparks++;
            } else hit(q, BURN, "cause_g116_spark");
            if (game.shotFx.room(ShotFx.EXTRA)) game.shotFx.flame(q.getLocation().add(0, 0.9, 0), 0.9f, 14, ShotFx.EXTRA);
            if (stage < 2) later.add(new Object[] {q, age + 20, stage + 1});
        }
        pending.addAll(later);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(G129Kit.at(this, 0, 0, 0), 1.5);
        add(G129Kit.at(this, 0, 1.55, 0), 1.0);
        add(G129Kit.at(this, 0, -1.55, 0), 1.0);
        add(G129Kit.at(this, 0, 0, 1.55), 1.0);
        add(G129Kit.at(this, 0, 0, -1.55), 1.0);
        add(G129Kit.at(this, hubSide * 0.75, 0, 0), 0.85); // 5 굴대 얼굴
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.at() != null) {
            List<double[]> s = spheres();
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < s.size(); i++) {
                double[] c = s.get(i);
                double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best == 5) return super.damage(d.times(HUB_MUL));
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        pending.clear();
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " v=" + String.format("%.2f", v) + " rolls=" + rolls + " crush=" + crushes + " sparks=" + sparks
            + " rams=" + rams;
    }
}
