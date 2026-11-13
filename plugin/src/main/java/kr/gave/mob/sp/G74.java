package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G74 칙칙 수레 게이브(떼, 돌격, 목표 압박, 1막). 바퀴가 땅을 물고 덜컹덜컹 곧게 굴러온다. 사람보다 트럭을 먼저 노려 들이받고(트럭이 없으면 사람에게),
 * 가는 길의 사람은 치고 지나간다(닿아 있는 동안 0.5초마다 8). 방향을 30도 넘게 틀 때는 멈춰 한쪽 바퀴만 돌려 제자리에서 끼익 돈다. 죽으면 3초 남는 반경
 * 2블록 분홍 가스(사람 초당 4, 겹쳐도 8까지, 기침으로 조준 흔들림. 다른 게이브는 절반: 초당 2, 잡은 사람 몫).
 *
 * <p>대처: 멀리서 잡으면 가스가 저쪽에서 퍼진다. 바퀴를 쏘면(맞은 점이 바퀴 쪽) 그 바퀴가 떨어져 나가 제자리를 맴돌며 느리게 다가온다.
 *
 * <p>목표 압박 종(Role.OBJECTIVE)이라 원정의 엔진 트럭 노림, 물기에서 빠진다: 이 종이 직접 {@link Truck#hit} 을 부른다(물기 피해 x1.5).
 *
 * <p>팩(tools/gabe/sp/g74.py): 모델 {@code gave:gabe/g74} 상태 "" 구름, "spin" 제자리 돌기, 부위 "wheel_l", "wheel_r". 가스 {@code gave:gabe/g74_gas}.
 * 소리 cart(수레 소리, 24), steam(칙칙, 16), screech(끼익, 16), burst(수레 터짐, 32). 글귀 cause_g74, cause_g74_gas.
 */
public final class G74 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.22;
    /** 이만큼 넘게 틀어야 하면 멈춰 제자리에서 돎(도), 구르며 틀 수 있는 각(도/틱), 제자리 도는 각(도/틱). */
    static final float TURN_LIMIT = 30f, ROLL_TURN = 3.5f, SPIN_TURN = 9f;
    /** 치기 피해, 간격(틱, 박자 배율), 밀침. */
    static final double RUN_DMG = 8, RUN_PUSH = 0.45;
    static final int RUN_EVERY = 10;
    /** 트럭 들이받기: 물기 피해 배율, 들이받은 뒤 물러남(틱, 빠르기), 다시 들이받기까지. */
    static final double RAM_MUL = 1.5, BACK_V = 0.12;
    static final int BACK = 12, RAM_REST = 30;
    /** 가스: 반경, 틱, 사람 초당, 사람 상한, 게이브 초당. */
    static final double GAS_R = 2.0, GAS_DPS = 4, GAS_CAP = 8, GAS_MOB = 2;
    static final int GAS_T = 60;
    /** 맞는 구: 몸, 바퀴(옆 거리, 반지름, 높이). */
    static final double BODY_R = 0.55, WHEEL_SIDE = 0.56, WHEEL_R = 0.33, WHEEL_DY = -0.38;
    /** 바퀴가 떨어진 뒤 맴돌기(반지름, 각속도 라디안/틱, 가운데가 다가오는 몫). */
    static final double SPIN_R = 0.9, SPIN_W = 0.16, LAME = 0.4;

    enum Phase { ROLL, TURN, RAM, BACK, LAME }

    private Phase phase = Phase.ROLL;
    private int phaseAt, readyAt, ramReady, nextClank, nextSteam;
    private boolean lostL, lostR;
    private final Vector spinAt = new Vector();
    private double spinA;
    private Player lastBy;

    public G74(Game game, Species sp) {
        super(game, sp);
        radius = BODY_R;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g74", 1.4f, 1.6f);
    }

    @Override
    protected void spawned() {
        nextClank = age + game.random.nextInt(20);
        nextSteam = age + game.random.nextInt(30);
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
            case ROLL -> roll();
            case TURN -> turn();
            case RAM -> ram();
            case BACK -> back();
            case LAME -> lame();
        }
        runOver();
        noises();
    }

    private static Truck truck() {
        Truck t = Truck.get();
        return t != null && t.alive() ? t : null;
    }

    /** 트럭 발자국에서 가장 가까운 점(트럭 쪽 겨냥). */
    private Vector truckPoint() {
        double x = Math.max(Truck.X0 + 0.2, Math.min(Truck.X1 - 0.2, pos.getX())), z = Math.max(Truck.Z0 + 0.2, Math.min(Truck.Z1 - 0.2, pos.getZ()));
        return new Vector(x, pos.getY(), z);
    }

    /** 가려는 쪽(수평 단위): 트럭이 있으면 트럭, 없으면 흐름장(사람). */
    private Vector want() {
        Truck t = truck();
        if (t != null) {
            Vector d = truckPoint().subtract(pos).setY(0);
            double l = d.length();
            if (l > 1e-6) return d.multiply(1 / l);
        }
        follow(1);
        Vector d = vel.clone().setY(0);
        double l = d.length();
        return l > 1e-6 ? d.multiply(1 / l) : forward();
    }

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a > 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    /** 곧게 굴러감: 조금씩만 틀고, 30도 넘게 틀어야 하면 멈춰 제자리 돌기. 트럭에 닿으면 들이받음. */
    private void roll() {
        Vector w = want();
        float wy = (float) Math.toDegrees(Math.atan2(-w.getX(), w.getZ()));
        float diff = wrap(wy - yaw);
        if (Math.abs(diff) > TURN_LIMIT) {
            hold();
            state("spin");
            sound("screech", Sound.BLOCK_IRON_DOOR_CLOSE, 1.6f, 16);
            phase(Phase.TURN);
            return;
        }
        yaw += Math.max(-ROLL_TURN, Math.min(ROLL_TURN, diff));
        state("");
        dash(forward(), speed());
        Truck t = truck();
        if (t != null && age >= ramReady && Truck.dist(pos.getX(), pos.getZ()) - radius <= 0.35) {
            hit(t);
        }
    }

    private void hit(Truck t) {
        t.hit(t.biteDamage(sp) * RAM_MUL, this, "cause_g74");
        sound("cart", Sound.ENTITY_IRON_GOLEM_HURT, 0.7f, 24);
        body.squash(1.2f, 0.8f, 0.8f, 2);
        hold();
        phase(Phase.RAM);
    }

    /** 제자리에서 한쪽 바퀴만 돌려 돎. */
    private void turn() {
        hold();
        Vector w = want();
        hold();
        boolean done = turnTo(pos.clone().add(w), SPIN_TURN);
        if (done || inPhase() >= 30) {
            state("");
            phase(Phase.ROLL);
        }
    }

    private void ram() {
        hold();
        if (inPhase() >= 3) {
            body.squash(1f, 1f, 1f, 3);
            phase(Phase.BACK);
        }
    }

    /** 들이받은 뒤 조금 물러났다가 다시. */
    private void back() {
        dash(forward().multiply(-1), BACK_V);
        if (inPhase() >= BACK) {
            ramReady = age + cd(RAM_REST) - BACK;
            phase(Phase.ROLL);
        }
    }

    /** 바퀴가 떨어짐: 기울어 제자리를 맴돌며 가운데가 느리게 다가옴. */
    private void lame() {
        state("spin");
        Vector w = want();
        spinAt.add(w.multiply(speed() * LAME));
        spinA += SPIN_W * (lostL ? 1 : -1);
        Vector at = spinAt.clone().add(new Vector(Math.cos(spinA) * SPIN_R, 0, Math.sin(spinA) * SPIN_R));
        Vector v = at.subtract(pos).setY(0);
        if (v.length() > 0.3) v.normalize().multiply(0.3);
        vel.copy(v);
        face(pos.clone().add(new Vector(-Math.sin(spinA), 0, Math.cos(spinA)).multiply(lostL ? 1 : -1)));
        body.roll = lostL ? -18f : 18f;
        Truck t = truck();
        if (t != null && age >= ramReady && Truck.dist(pos.getX(), pos.getZ()) - radius <= 0.35) {
            t.hit(t.biteDamage(sp) * RAM_MUL, this, "cause_g74");
            ramReady = age + cd(RAM_REST);
        }
    }

    /** 바퀴에 치임: 닿은 사람마다, 닿아 있는 동안 0.5초마다. */
    private void runOver() {
        if (age < readyAt) return;
        boolean any = false;
        Vector f = forward();
        for (Player p : game.mobs.standing()) {
            if (!touches(p, 0.1)) continue;
            hit(p, RUN_DMG, "cause_g74");
            knock(p, f, RUN_PUSH, 0.2);
            any = true;
        }
        if (any) {
            sound("cart", Sound.ENTITY_IRON_GOLEM_HURT, 0.7f, 24);
            body.squash(1.1f, 0.9f, 0.9f, 2);
            readyAt = age + cd(RUN_EVERY);
        } else if (age == readyAt + 3) body.squash(1f, 1f, 1f, 3);
    }

    /** 덜컹, 칙칙. 체력이 적으면 김이 진해짐(펄럭임 빠르기). */
    private void noises() {
        boolean low = hpFrac() < 0.35;
        body.look.rate = low ? 2.0 : 1.0;
        if (phase == Phase.ROLL && age >= nextClank) {
            sound("cart", Sound.ENTITY_IRON_GOLEM_STEP, 0.7f, 24);
            nextClank = age + 14 + game.random.nextInt(14);
        }
        if (age >= nextSteam) {
            sound("steam", Sound.BLOCK_FIRE_EXTINGUISH, 1.6f, 16);
            nextSteam = age + (low ? 12 : 30) + game.random.nextInt(20);
        }
    }

    // ------------------------------------------------------------------ 맞음

    private Vector left() {
        double r = Math.toRadians(yaw);
        return new Vector(Math.cos(r), 0, Math.sin(r));
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), pos.getY() + 0.05, pos.getZ(), BODY_R});
        Vector l = left().multiply(WHEEL_SIDE);
        if (!lostL) out.add(new double[] {pos.getX() + l.getX(), pos.getY() + WHEEL_DY, pos.getZ() + l.getZ(), WHEEL_R});
        if (!lostR) out.add(new double[] {pos.getX() - l.getX(), pos.getY() + WHEEL_DY, pos.getZ() - l.getZ(), WHEEL_R});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.by() != null) lastBy = d.by();
        DamageType t = d.type();
        if (d.at() != null && !lostL && !lostR && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            Vector l = left().multiply(WHEEL_SIDE);
            Vector c = pos.clone().add(new Vector(0, 0.05, 0));
            Vector wl = pos.clone().add(l).add(new Vector(0, WHEEL_DY, 0)), wr = pos.clone().subtract(l).add(new Vector(0, WHEEL_DY, 0));
            double dc = d.at().distanceSquared(c), dl = d.at().distanceSquared(wl), dr = d.at().distanceSquared(wr);
            if (dl < dc && dl <= dr) lose(true);
            else if (dr < dc && dr < dl) lose(false);
        }
        return super.damage(d);
    }

    private void lose(boolean leftSide) {
        if (leftSide) lostL = true;
        else lostR = true;
        body.look.on.add(leftSide ? "wheel_l" : "wheel_r");
        sound("screech", Sound.BLOCK_IRON_DOOR_CLOSE, 1.6f, 16);
        spinAt.copy(pos);
        spinA = game.random.nextDouble() * Math.PI * 2;
        phase(Phase.LAME);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        double fl = floor();
        sound("burst", Sound.ENTITY_GENERIC_EXPLODE, 1.3f, 32);
        G72Zone.gas(game, new org.bukkit.util.Vector(pos.getX(), fl, pos.getZ()), GAS_R, GAS_T, GAS_DPS, GAS_CAP, GAS_MOB, this,
                d != null && d.by() != null ? d.by() : lastBy, "cause_g74_gas");
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (lostL ? " lostL" : "") + (lostR ? " lostR" : "") + " zones=" + G72Zone.count();
    }
}
