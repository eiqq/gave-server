package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G161 순찰 병정 게이브(1막, 원거리, 튼튼이). 키 8.4 블록 구릿빛 장난감 병정이 흙먼지에서 걸어 들어와 트럭(없으면 중앙 원 가운데)에서 12 블록 둘레를
 * 걸음마다 땅을 울리며 돈다(0.1 블록/틱). 둘레 길 위의 사람은 발에 밟혀 30 과 밀림(발밑의 값싼 게이브도 밟혀 죽음). 앞 14 블록 안(정면 90 도)에 사람이
 * 들면 멈춰 서서 발밑의 얼굴 덩이를 뽑아 머리 위로 치켜들었다가(1.5 초) 그 사람에게 던진다: 떨어질 자리에 그림자 원이 자라고 1.5 초 뒤 반경 2.5 안
 * 22 와 밀림. 등 뒤와 옆은 보지 않는다. 한 바퀴 돌 때마다 모자를 한 번 까딱이고(1 초 전 신호) 돌아서서 반대로 돈다.
 * 왼발 뒤꿈치 못이 약점(피해 x3, 못 부위 체력 150): 뽑히면 즙이 다 빠져 무릎부터 주저앉아 멈춘다(그 사격이 처치).
 *
 * <p>팩 계약(tools/gabe/sp/g161.py): 몸 gabe/g161(shrink 0.29, 상태 ""), 다리 gabe/g161_leg(상태 "", "nail", "pulled"), 팔 gabe/g161_arm,
 * 덩이 gabe/g161_lump. 소리 march(48), lift(32), toss(40), splat(32), pop(48). 글귀 cause_g161, cause_g161_lump.
 *
 * <p>도감과 다른 점: 키를 6 에서 8.4 블록으로(모자 포함, 거대한 몸이 이 묶음의 핵심). 디스플레이 다섯(몸, 다리 둘, 팔 둘). 걸음마다 땅울림
 * (Horizon.shake)은 24 블록 안에 사람이 있을 때만. 덩이는 던지는 순간의 그 사람 자리로 떨어진다(그림자가 그 자리에서 자람).
 */
public final class G161 extends Gabe {

    static final double HOVER = 4.0, HIP_X = 0.62, HIP_Y = -1.2, LEG_L = 2.8, SH_X = 1.55, SH_Y = 0.95, ARM_L = 2.3, HEAD_Y = 2.3;
    /** 둘레 반지름, 걷기 배율(다가올 때), 도는 쪽을 바꾸는 각(한 바퀴). */
    static final double PATROL_R = 12, APPROACH_MUL = 1.45, LAP = Math.PI * 2;
    /** 보는 거리, 정면 반각의 cos(45 도). */
    static final double SEE_R = 14, SEE_COS = Math.cos(Math.toRadians(45));
    static final double LUMP_R = 2.5, LUMP_DMG = 22, STEP_R = 1.5, STEP_DMG = 30;
    static final int THROW_CD = 70, WINDUP = 30, FLIGHT = 30, NOD = 20, COLLAPSE = 34;
    static final double NAIL_HP = 150, NAIL_MUL = 3;
    static final float TURN = 3f;

    enum Phase { APPROACH, PATROL, WINDUP, NOD, COLLAPSE }

    private Phase phase = Phase.APPROACH, resume = Phase.APPROACH;
    private int phaseAt, throwReady, dir = 1, tossed, laps, stomps;
    private double lapAcc, lastTheta = Double.NaN, nail = -1;
    private Player aim;
    private Damage killer;
    private G129Kit.Legs legs;
    private final Body.Part[] arms = new Body.Part[2];
    private Body.Part held;
    private final float[] armX = {0, 0};
    private int armPosedAt = -100;
    private final G141Kit.Lobs lobs = new G141Kit.Lobs("gabe/g161_lump", 3);
    private final List<double[]> sph = new ArrayList<>(12);
    private int sphAge = -1;

    public G161(Game game, Species sp) {
        super(game, sp);
        radius = 1.3;
        hover = HOVER;
        baseSpeed = 0.1;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragKick = 0.3;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g161", 4.2f, 10f);
        body.viewRange(3f);
        legs = new G129Kit.Legs(this, "gabe/g161_leg", LEG_L, new double[] {HIP_X, -HIP_X}, HIP_Y, 0);
        legs.reach = 1.25;
        legs.lead = 1.2;
        legs.lift = 1.1;
        legs.stance = 0.12;
        legs.swing = 12;
        legs.land = this::landed;
        if (legs.part[0] != null) legs.part[0].state("nail");
        for (int i = 0; i < 2; i++) arms[i] = body.attach("gabe/g161_arm");
        poseArms(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        throwReady = age + 60;
        dir = game.random.nextBoolean() ? 1 : -1;
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
        if (nail < 0) nail = NAIL_HP * Math.max(0.6, Math.sqrt(maxHp / Math.max(1, sp.hp)));
        switch (phase) {
            case APPROACH -> approach();
            case PATROL -> patrol();
            case WINDUP -> windup();
            case NOD -> nod();
            case COLLAPSE -> collapse();
        }
        lobs.tick(this, this::land);
        if (phase != Phase.COLLAPSE) {
            double v = vel.clone().setY(0).length();
            legs.swing = (int) Math.max(8, Math.min(16, 1.4 / Math.max(0.05, v)));
        }
        legs.tick();
        armsTick();
    }

    /** 지킬 한가운데(트럭이 서 있으면 트럭 가운데). */
    private Vector mid() {
        return G141Kit.middle();
    }

    private void approach() {
        Vector c = mid();
        Vector out = pos.clone().subtract(c).setY(0);
        double r = out.length();
        if (r < 1e-3) out = new Vector(1, 0, 0);
        else out.multiply(1 / r);
        if (r <= PATROL_R + 1.0) {
            lastTheta = Double.NaN;
            lapAcc = 0;
            phase(Phase.PATROL);
            return;
        }
        Vector to = c.clone().add(out.clone().multiply(PATROL_R));
        Vector d = to.subtract(pos).setY(0);
        double l = d.length();
        double v = speed() * APPROACH_MUL;
        if (l > 1e-3) {
            vel.setX(d.getX() / l * v);
            vel.setZ(d.getZ() / l * v);
            turnToward(d, TURN);
        }
        lookForThrow();
    }

    /** 둘레를 돈다: 접선 + 반지름 바로잡기. 한 바퀴마다 까딱(NOD) 뒤 반대로. */
    private void patrol() {
        Vector c = mid();
        double dx = pos.getX() - c.getX(), dz = pos.getZ() - c.getZ();
        double r = Math.hypot(dx, dz);
        if (r > PATROL_R + 6) {
            phase(Phase.APPROACH);
            return;
        }
        double th = Math.atan2(dz, dx);
        if (!Double.isNaN(lastTheta)) {
            double d = th - lastTheta;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            lapAcc += d * dir;
        }
        lastTheta = th;
        double ux = r < 1e-3 ? 1 : dx / r, uz = r < 1e-3 ? 0 : dz / r;
        // 반시계(dir 1) 접선 = (-uz, ux)
        double tx = -uz * dir, tz = ux * dir;
        double corr = Math.max(-0.4, Math.min(0.4, (PATROL_R - r) * 0.15));
        double v = speed();
        Vector want = new Vector(tx + ux * corr, 0, tz + uz * corr).normalize().multiply(v);
        vel.setX(want.getX());
        vel.setZ(want.getZ());
        turnToward(want, TURN);
        if (lapAcc >= LAP) {
            lapAcc = 0;
            laps++;
            hold();
            phase(Phase.NOD);
            return;
        }
        lookForThrow();
    }

    /** 앞 14 블록, 정면 90 도 안 가장 가까운 서 있는 사람이 있으면 던지기 준비. */
    private void lookForThrow() {
        if (age < throwReady || !every(4)) return;
        Vector f = G141Kit.fwd(this);
        Player best = null;
        double bd = 1e9;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            double h = Math.hypot(dx, dz);
            if (h > SEE_R || h < 1.5) continue;
            if ((dx * f.getX() + dz * f.getZ()) / h < SEE_COS) continue;
            if (Math.abs(l.getY() - (pos.getY() - HOVER)) > 6) continue;
            if (h < bd) {
                bd = h;
                best = p;
            }
        }
        if (best == null) return;
        aim = best;
        mark(best);
        hold();
        armX[1] = 165;
        poseArms(10);
        held = body.attach("gabe/g161_lump");
        if (held != null) poseHeld(0);
        resume = phase == Phase.PATROL ? Phase.PATROL : Phase.APPROACH;
        sound("lift", Sound.BLOCK_GRAVEL_BREAK, 0.6f, 32);
        G129Kit.ring(this, G129Kit.ground(this, G141Kit.at(this, -SH_X - 0.4, 0, 0.6)), 1.2, 10);
        phase(Phase.WINDUP);
    }

    /** 머리 위로 치켜든 채 고개만 뻣뻣하게 표적으로(몸이 천천히 돎), 끝에 던짐. */
    private void windup() {
        hold();
        Player p = aim;
        if (p != null && (!p.isOnline() || !standing(p))) p = null;
        if (p != null) G141Kit.turnFlat(this, chest(p), 4f);
        if (held != null && age - armPosedAt <= 10) poseHeld(2);
        if (inPhase() < cd(WINDUP)) return;
        if (p != null) throwAt(p);
        dropHeld();
        aim = null;
        mark(null);
        throwReady = age + cd(THROW_CD);
        lastTheta = Double.NaN;
        // 둘레를 돌던 중이면 바퀴 셈을 이어감(던질 때마다 셈이 지워지면 돌아서지 않음)
        phase(resume);
    }

    private void throwAt(Player p) {
        Location l = p.getLocation();
        Vector to = new Vector(l.getX(), game.mobs.flow.floor(l.getX(), l.getZ()) + 0.35, l.getZ());
        Vector from = handWorld(1);
        double arc = 3.5 + G141Kit.hdist(from, to) * 0.15;
        if (lobs.toss(this, from, to, FLIGHT, arc)) {
            tossed++;
            G129Kit.plate(this, "gabe/b1_shadow", "", to, LUMP_R, FLIGHT + 4, FLIGHT);
            sound("toss", Sound.ENTITY_SNOWBALL_THROW, 0.5f, 40);
        }
        armX[1] = -70;
        poseArms(4);
    }

    private void land(Vector at) {
        Location l = new Location(game.world, at.getX(), at.getY(), at.getZ());
        if (!game.director.silent()) kr.gave.Props.soundAround(l, "gabe.g161.splat", Sound.ENTITY_SLIME_SQUISH, 1f, 0.9f, 0.7f, 32, -1);
        if (game.shotFx.room(ShotFx.NORMAL)) game.shotFx.bloodHit(l.clone().add(0, 0.3, 0), new Vector(0, 1, 0), true, ShotFx.BLOOD_PINK);
        G129Kit.ring(this, at, LUMP_R, 12);
        for (Player q : G141Kit.within(this, at, LUMP_R + 0.3, 2.5)) {
            hit(q, LUMP_DMG, "cause_g161_lump");
            Location ql = q.getLocation();
            knock(q, new Vector(ql.getX() - at.getX(), 0, ql.getZ() - at.getZ()), 0.7, 0.3);
        }
    }

    /** 모자 까딱(몸을 앞으로 숙였다 폄) 뒤 돌아서서 반대로. */
    private void nod() {
        hold();
        int t = inPhase();
        if (t == 0) {
            pitch = 14;
            body.pose(4);
            sound("march", Sound.BLOCK_ANVIL_LAND, 0.5f, 48);
        } else if (t == 7) {
            pitch = 0;
            body.pose(5);
        }
        if (t < cd(NOD)) return;
        dir = -dir;
        lastTheta = Double.NaN;
        lapAcc = 0;
        phase(Phase.PATROL);
    }

    private void turnToward(Vector d, float maxDeg) {
        if (d.lengthSquared() < 1e-8) return;
        float want = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        float dd = G141Kit.wrap(want - yaw);
        yaw = G141Kit.wrap(yaw + Math.max(-maxDeg, Math.min(maxDeg, dd)));
        pitch = 0;
    }

    // ------------------------------------------------------------------ 걸음

    /** 발 디딤: 땅울림, 걸음 소리, 발밑 사람 30 과 밀림, 값싼 게이브 밟힘. 왼발(못)은 즙 한 방울. */
    private void landed(int i, Vector foot) {
        if (phase == Phase.COLLAPSE) return;
        stomps++;
        G129Kit.stomp(this, foot, 64, 10, 0.9f);
        G129Kit.soundAt(this, foot, "march", Sound.BLOCK_ANVIL_LAND, 0.9f, 48);
        if (game.horizon != null && game.mobs.nearestPlayer(foot, 24) != null) game.horizon.shake(0.3, 6);
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (G129Kit.flat(l, foot) > STEP_R || Math.abs(l.getY() - foot.getY()) > 2) continue;
            hit(q, STEP_DMG, "cause_g161");
            knock(q, new Vector(l.getX() - foot.getX(), 0, l.getZ() - foot.getZ()), 0.9, 0.45);
        }
        G129Kit.crush(this, foot, 1.3, null, 0);
        if (i == 0 && game.shotFx.room(ShotFx.EXTRA))
            game.shotFx.bloodHit(new Location(game.world, foot.getX(), foot.getY() + 0.25, foot.getZ()), new Vector(0, -1, 0), false, ShotFx.BLOOD_PINK);
    }

    // ------------------------------------------------------------------ 팔

    /** 걷는 동안 팔을 앞뒤로 뻣뻣하게(던지기, 주저앉기 중에는 그대로). */
    private void armsTick() {
        if (phase == Phase.WINDUP || phase == Phase.COLLAPSE || age - armPosedAt < 6) return;
        double v = vel.clone().setY(0).length();
        float sw = (float) (Math.sin(age * 0.22) * Math.min(28, v * 220));
        armX[0] = sw;
        armX[1] = -sw;
        poseArms(6);
    }

    private Matrix4f armM(int i) {
        float x = (float) (i == 0 ? SH_X : -SH_X);
        return new Matrix4f().translate(x, (float) SH_Y, 0).rotateX((float) Math.toRadians(armX[i])).rotateZ((float) Math.toRadians(i == 0 ? 6 : -6));
    }

    private void poseArms(int ticks) {
        armPosedAt = age;
        for (int i = 0; i < 2; i++) if (arms[i] != null) arms[i].pose(armM(i), ticks);
    }

    /** 손 끝(몸 틀). */
    private Vector3f handLocal(int i) {
        return armM(i).transformPosition(new Vector3f(0, (float) -(ARM_L + 0.3), 0));
    }

    private Vector handWorld(int i) {
        Vector3f v = handLocal(i);
        return G141Kit.at(this, v.x, v.y, v.z);
    }

    private void poseHeld(int ticks) {
        if (held == null) return;
        Vector3f v = handLocal(1);
        held.pose(new Matrix4f().translate(v.x, v.y, v.z), ticks);
    }

    private void dropHeld() {
        if (held != null) held.remove();
        held = null;
    }

    // ------------------------------------------------------------------ 못, 주저앉기

    private void startCollapse(Damage d) {
        killer = d;
        dropHeld();
        aim = null;
        if (legs.part[0] != null) legs.part[0].state("pulled");
        legs.pinned = true;
        hold();
        sound("pop", Sound.BLOCK_ANVIL_BREAK, 0.7f, 48);
        phase(Phase.COLLAPSE);
    }

    /** 즙이 빠지며 무릎부터 내려앉고 앞으로 고꾸라짐, 끝에 죽음. */
    private void collapse() {
        hold();
        int t = inPhase();
        double k = Math.min(1, t / (double) COLLAPSE);
        hover = HOVER - 1.6 * k * k;
        pitch = (float) (22 * k);
        if (t % 4 == 0) body.pose(4);
        if (t % 5 == 0 && game.shotFx.room(ShotFx.NORMAL) && legs.foot[0] != null) {
            Vector h = legs.foot[0].clone().add(G141Kit.fwd(this).multiply(-0.5)).add(new Vector(0, 0.3, 0));
            game.shotFx.bloodHit(new Location(game.world, h.getX(), h.getY(), h.getZ()), G141Kit.fwd(this).multiply(-1), true, ShotFx.BLOOD_PINK);
        }
        if (t >= COLLAPSE && !dead) die(killer != null ? killer : Damage.of(1, kr.gave.mob.DamageType.OTHER));
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(G141Kit.at(this, 0, 0, 0), 1.35);                                    // 0 몸통
        add(G141Kit.at(this, 0, HEAD_Y, 0), 1.0);                                // 1 머리
        add(G141Kit.at(this, 0, HEAD_Y + 1.6, 0), 0.72);                         // 2 모자
        if (legs != null) {
            for (int i = 0; i < 2; i++) {
                add(legs.along(i, 0.3), 0.55);                                   // 3, 5 다리
                add(legs.along(i, 0.75), 0.5);                                   // 4, 6
            }
            Vector f = G141Kit.fwd(this);
            Vector heel = legs.foot[0].clone().add(f.multiply(-0.75)).add(new Vector(0, 0.45, 0));
            add(heel, nail > 0 ? 0.48 : 0.01);                                   // 7 뒤꿈치 못
        }
        for (int i = 0; i < 2; i++) add(handWorld(i), 0.45);                     // 8, 9 손
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public Vector head() {
        return G141Kit.at(this, 0, HEAD_Y, 0.5);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (phase != Phase.COLLAPSE && nail > 0 && d.at() != null) {
            List<double[]> s = spheres();
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < s.size(); i++) {
                double[] c = s.get(i);
                double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best == 7) {
                Damage x = d.times(NAIL_MUL);
                nail -= x.amount();
                boolean ok = super.damage(x);
                if (!dead && nail <= 0) startCollapse(x);
                return ok;
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        dropHeld();
        lobs.clear();
        mark(null);
        super.die(d);
    }

    @Override
    public void remove() {
        dropHeld();
        lobs.clear();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " dir=" + dir + " lap=" + String.format("%.2f", lapAcc) + " laps=" + laps + " throws=" + tossed
            + " stomps=" + stomps + " nail=" + Math.round(nail) + " r=" + String.format("%.1f", G141Kit.hdist(pos, mid()));
    }
}
