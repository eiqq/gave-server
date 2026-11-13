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
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G130 따라 하는 거인 게이브(3막, 방해, 정예). 키 12블록 헝겊 인형 거인이 흙먼지에서 걸어 나와 30블록 안에서 한 사람을 찍고(머리 위 작은 인형, 그 사람
 * 조준점 표시와 알림), 그 사람의 몸짓을 4배로 따라 한다: 걸으면 같은 쪽으로 4배 걷고(밟히면 30과 넘어짐 1초, 발밑 게이브도 밟혀 죽고 그 보상 절반은 찍힌
 * 사람에게), 뛰면 발을 굴러 반경 7 충격파 12(뛰어 넘으면 피함), 쏘면 그 사람이 보는 쪽으로 주먹(길이 9, 폭 3 띠 28과 밀림), 웅크리면 앞 반달 반경 8 을
 * 손바닥으로 쓸기(20). 몸짓마다 0.8초 전에 바닥 예고. 15초마다 표시가 다른 사람으로 옮겨 가고, 찍힌 사람이 2초 넘게 가만히 있으면 제 발로 세 걸음 다가온다.
 * 정수리 태엽 손잡이 둘(체력 각 200)을 다 부수면 따라 하기가 끊기고 10초 멍하니 선다(피해 x2). 그 뒤 손잡이가 절반으로 돋아 다시 따라 한다.
 *
 * <p>팩 계약(tools/gabe/sp/g130.py): gabe/g130(shrink 0.25, 상태 "", "daze", 부위 cut0, cut1), 팔 gabe/g130_arm, 다리 gabe/g130_leg,
 * 머리 칸 인형 gabe/g130_doll. 소리 wind, step, punch, sweep. 글귀 cause_g130, cause_g130_punch, cause_g130_sweep, cause_g130_stomp, notice_g130.
 *
 * <p>도감과 다른 점: 따라 걷는 빠르기는 한 틱 0.55블록까지(넘친 걸음은 쌓아 두었다가 따라잡음, 18블록까지). 반달 쓸기의 예고는 앞쪽 네모 띠(그 안 반경 8).
 * 멍한 뒤 다시 따라 한다(외곽에서 멈추지 않게).
 */
public final class G130 extends Gabe {

    static final double HOVER = 7.5, HIP_X = 1.0, HIP_Y = -2.5, SH_X = 2.05, SH_Y = 1.15, LEG_L = 5.0, ARM_L = 5.5, HEAD_Y = 3.15, KNOB_X = 0.62;
    static final double SPEED = 0.13, MIRROR = 4.0, MAXV = 0.55, DEBT_MAX = 18, START_R = 30;
    static final double STEP_R = 2.2, STEP_DMG = 30, STOMP_R = 7, STOMP_DMG = 12, PUNCH_LEN = 9, PUNCH_W = 3, PUNCH_DMG = 28, SWEEP_R = 8,
        SWEEP_DMG = 20, KNOB_HP = 200, DAZE_MUL = 2.0;
    static final int TELL = 16, DOWN_T = 20, MARK_T = 300, IDLE_T = 40, DAZE_T = 200, PUNCH_CD = 24, STOMP_CD = 40, SWEEP_CD = 40;

    enum Phase { APPROACH, MIRROR, DAZE }

    enum Act { NONE, PUNCH, STOMP, SWEEP }

    private Phase phase = Phase.APPROACH;
    private int phaseAt;
    private Act act = Act.NONE;
    private int actAt, punchReady, stompReady, sweepReady, markAt, idle, marks, punches, stomps, sweeps, crushed;
    private Player marked;
    private final Vector last = new Vector(), debt = new Vector(), actDir = new Vector(0, 0, 1);
    private boolean wasGround = true, wasSneak, stompPending;
    private final double[] knob = {-1, -1};
    private boolean knobInit;
    private G129Kit.Legs legs;
    private final Body.Part[] arms = new Body.Part[2];
    /** 팔 자세(도): 옆 돌림 a(y), 앞뒤 b(x), 벌림 c(z). */
    private final float[][] armRot = {{0, 0, 10}, {0, 0, -10}};
    private int armPosedAt = -100;
    private final List<double[]> sph = new ArrayList<>(12);
    private int sphAge = -1;

    public G130(Game game, Species sp) {
        super(game, sp);
        radius = 1.8;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragKick = 0.35;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g130", 12f, 16f);
        body.viewRange(3f);
        legs = new G129Kit.Legs(this, "gabe/g130_leg", LEG_L, new double[] {HIP_X, -HIP_X}, HIP_Y, 0);
        legs.reach = 2.4;
        legs.lead = 2.4;
        legs.lift = 1.3;
        legs.stance = 0.3;
        legs.swing = 14;
        legs.land = this::landed;
        for (int i = 0; i < 2; i++) arms[i] = body.attach("gabe/g130_arm");
        poseArms(0);
        G129Kit.bright(body);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        markAt = age;
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
        if (!knobInit) {
            knob[0] = knob[1] = KNOB_HP * Math.max(0.5, maxHp / Math.max(1, sp.hp));
            knobInit = true;
        }
        if (marked != null && (!marked.isOnline() || !standing(marked))) unmark();
        switch (phase) {
            case APPROACH -> approach();
            case MIRROR -> mirror();
            case DAZE -> daze();
        }
        actTick();
        legs.swing = (int) Math.max(6, Math.min(16, 2.2 / Math.max(0.05, vel.clone().setY(0).length())));
        legs.tick();
        armsTick();
    }

    /** 흙먼지에서 걸어 나옴: 가장 가까운 사람 30블록 안에 들면 한 사람을 찍음. */
    private void approach() {
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        if (dist(p) > START_R) {
            follow(speed());
            G129Kit.faceFlat(this, chest(p));
            return;
        }
        hold();
        pickMark(null, p);
        if (marked != null) phase(Phase.MIRROR);
    }

    private void pickMark(Player not) {
        pickMark(not, null);
    }

    /** 찍을 사람: first 가 있으면 그 사람(다가온 사람), 없으면 70블록 안에서 not 아닌 사람 무작위. */
    private void pickMark(Player not, Player first) {
        List<Player> ps = game.mobs.standing();
        List<Player> c = new ArrayList<>();
        if (first != null && standing(first)) c.add(first);
        else {
            for (Player q : ps) if (q != not && dist(q) < 70) c.add(q);
        }
        if (c.isEmpty()) for (Player q : ps) if (q != not) c.add(q);
        if (c.isEmpty() && not != null && standing(not)) c.add(not);
        unmark();
        if (c.isEmpty()) return;
        Player p = c.get(game.random.nextInt(c.size()));
        marked = p;
        mark(p);
        markAt = age;
        marks++;
        last.copy(p.getLocation().toVector());
        debt.zero();
        idle = 0;
        wasGround = p.isOnGround();
        wasSneak = p.isSneaking();
        PState s = game.players.get(p);
        if (s.headItem == null) game.players.attachHead(p, Body.item("gabe/g130_doll", "", new LookState(), game.worldTime()), this);
        game.hud.mark(p, "g130", MARK_T);
        game.hud.notice(p, "notice_g130", 60);
        sound("wind", Sound.BLOCK_DISPENSER_FAIL, 0.6f, 64);
    }

    private void unmark() {
        if (marked != null) {
            PState s = game.players.get(marked);
            if (s.headBy == this) game.players.attachHead(marked, null);
            game.hud.mark(marked, "g130", 0);
        }
        marked = null;
        mark(null);
    }

    /** 찍힌 사람의 몸짓을 4배로. */
    private void mirror() {
        Player p = marked;
        if (p == null) {
            phase(Phase.APPROACH);
            return;
        }
        if (age - markAt >= MARK_T) pickMark(p);
        p = marked;
        if (p == null) {
            phase(Phase.APPROACH);
            return;
        }
        Location l = p.getLocation();
        Vector cur = l.toVector();
        Vector d = cur.clone().subtract(last).setY(0);
        last.copy(cur);
        double dl = d.length();
        if (dl < 2.5) debt.add(d.multiply(MIRROR));
        if (dl < 0.03) idle++;
        else idle = 0;
        // 2초 넘게 가만히: 그 사람 쪽으로 제 발로 세 걸음
        if (idle >= cd(IDLE_T) && act == Act.NONE) {
            Vector to = cur.clone().subtract(pos).setY(0);
            double tl = to.length();
            if (tl > 5) debt.add(to.multiply(Math.min(12, tl - 4) / tl));
            idle = 0;
        }
        double dbl = debt.length();
        if (dbl > DEBT_MAX) debt.multiply(DEBT_MAX / dbl);
        // 몸짓 신호(하나씩)
        boolean ground = p.isOnGround();
        boolean sneak = p.isSneaking();
        if (act == Act.NONE) {
            if (wasGround && !ground && p.getVelocity().getY() > 0.2 && age >= stompReady) startAct(Act.STOMP, p);
            else if (game.mobs.shotsBy(p, 3) > 0 && age >= punchReady) startAct(Act.PUNCH, p);
            else if (sneak && !wasSneak && age >= sweepReady) startAct(Act.SWEEP, p);
        }
        wasGround = ground;
        wasSneak = sneak;
        // 걸음(몸짓 중에는 멈춤): 쌓인 몫을 한 틱 MAXV 까지
        if (act == Act.NONE) {
            double m = Math.min(debt.length(), MAXV * Math.max(1, game.director.speedMul()));
            if (m > 0.01) {
                Vector v = debt.clone().normalize().multiply(m);
                vel.setX(v.getX()).setZ(v.getZ());
                debt.subtract(v);
            } else hold();
            turnYaw(l.getYaw(), 10f);
        } else hold();
    }

    private void turnYaw(float want, float max) {
        float dd = wrap(want - yaw);
        yaw += Math.max(-max, Math.min(max, dd));
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    // ------------------------------------------------------------------ 몸짓

    private void startAct(Act a, Player p) {
        act = a;
        actAt = age;
        Vector look = p.getLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1e-4) look = G129Kit.fwd(this);
        actDir.copy(look.normalize());
        yaw = G129Kit.yawOf(actDir);
        Vector g0 = G129Kit.ground(this, pos);
        int t = cd(TELL);
        switch (a) {
            case PUNCH -> {
                G129Kit.strip(this, g0.clone().add(actDir.clone().multiply(1.5)), g0.clone().add(actDir.clone().multiply(1.5 + PUNCH_LEN)), PUNCH_W,
                    t + 4, true);
                armRot[1][0] = 0;
                armRot[1][1] = 55;
                poseArms(t);
                punchReady = age + cd(PUNCH_CD) + t;
                punches++;
            }
            case STOMP -> {
                G129Kit.plate(this, "gabe/b1_warn_disc", "", g0, STOMP_R, t + 4, 0);
                G129Kit.plate(this, "gabe/b1_warn_disc", "fill", g0, STOMP_R, t + 4, t);
                legs.lift = 3.2;
                legs.step(0, legs.foot[0].clone().add(actDir.clone().multiply(0.4)), t);
                stompPending = true;
                for (float[] r : armRot) r[1] = -40;
                poseArms(t);
                stompReady = age + cd(STOMP_CD) + t;
                stomps++;
            }
            case SWEEP -> {
                G129Kit.strip(this, g0, g0.clone().add(actDir.clone().multiply(SWEEP_R)), SWEEP_R * 2, t + 4, true);
                armRot[0][0] = 60;
                armRot[0][1] = -40;
                armRot[1][0] = 60;
                armRot[1][1] = -40;
                poseArms(t);
                sweepReady = age + cd(SWEEP_CD) + t;
                sweeps++;
            }
            default -> {
            }
        }
    }

    private void actTick() {
        if (act == Act.NONE) return;
        int t = cd(TELL), in = age - actAt;
        if (in == t) strike();
        if (in >= t + 8) {
            if (stompPending) doStomp(legs.foot[0]);
            act = Act.NONE;
            restArms();
        }
    }

    private void strike() {
        Vector g0 = G129Kit.ground(this, pos);
        switch (act) {
            case PUNCH -> {
                armRot[1][0] = 0;
                armRot[1][1] = -90;
                poseArms(3);
                sound("punch", Sound.ENTITY_IRON_GOLEM_ATTACK, 0.6f, 48);
                Vector a = g0.clone().add(actDir.clone().multiply(1.5));
                for (Player q : game.mobs.standing()) {
                    Location l = q.getLocation();
                    double rx = l.getX() - a.getX(), rz = l.getZ() - a.getZ();
                    double along = rx * actDir.getX() + rz * actDir.getZ(), lat = Math.abs(rx * actDir.getZ() - rz * actDir.getX());
                    if (along < -0.5 || along > PUNCH_LEN + 0.5 || lat > PUNCH_W / 2 + 0.4 || Math.abs(l.getY() - g0.getY()) > 5) continue;
                    hit(q, PUNCH_DMG, "cause_g130_punch");
                    knock(q, actDir, 1.4, 0.35);
                }
                for (int k = 1; k <= 3; k++)
                    crushed += G129Kit.crush(this, a.clone().add(actDir.clone().multiply(k * 2.6)), 1.8, marked, 0.5);
                G129Kit.ring(this, a.clone().add(actDir.clone().multiply(PUNCH_LEN)), 2, 10);
            }
            case STOMP -> {
                // 발이 땅에 닿을 때(landed) 친다. 다리가 이미 닿았으면 여기서
                if (stompPending && !legs.swinging(0)) doStomp(legs.foot[0]);
            }
            case SWEEP -> {
                armRot[0][0] = -60;
                armRot[1][0] = -60;
                poseArms(5);
                sound("sweep", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 48);
                for (Player q : game.mobs.standing()) {
                    Location l = q.getLocation();
                    double rx = l.getX() - g0.getX(), rz = l.getZ() - g0.getZ();
                    double along = rx * actDir.getX() + rz * actDir.getZ();
                    if (along < -0.5 || Math.hypot(rx, rz) > SWEEP_R || Math.abs(l.getY() - g0.getY()) > 4) continue;
                    hit(q, SWEEP_DMG, "cause_g130_sweep");
                    knock(q, new Vector(rx, 0, rz), 0.7, 0.2);
                }
                crushed += G129Kit.crush(this, g0.clone().add(actDir.clone().multiply(4)), 4.5, marked, 0.5);
            }
            default -> {
            }
        }
    }

    private void doStomp(Vector foot) {
        stompPending = false;
        legs.lift = 1.3;
        Vector g0 = G129Kit.ground(this, pos);
        G129Kit.stomp(this, foot, 64, 14, 1f);
        G129Kit.wave(this, g0, 1.0, STOMP_R, 7);
        if (game.horizon != null) game.horizon.shake(0.4, 10);
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (G129Kit.flat(l, g0) > STOMP_R || !q.isOnGround() || Math.abs(l.getY() - g0.getY()) > 3) continue;
            hit(q, STOMP_DMG, "cause_g130_stomp");
        }
        crushed += G129Kit.crush(this, g0, 3.5, marked, 0.5);
    }

    /** 발 디딤: 밟기(사람 30과 넘어짐, 게이브는 죽고 보상 절반이 찍힌 사람에게). */
    private void landed(int i, Vector foot) {
        if (stompPending && i == 0) {
            doStomp(foot);
            return;
        }
        G129Kit.stomp(this, foot, 64, 9, 0.7f);
        if (game.random.nextInt(2) == 0) G129Kit.soundAt(this, foot, "step", org.bukkit.Sound.ENTITY_RAVAGER_STEP, 0.8f, 64);
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (G129Kit.flat(l, foot) > STEP_R || Math.abs(l.getY() - foot.getY()) > 3) continue;
            hit(q, STEP_DMG, "cause_g130");
            game.players.root(q, DOWN_T, "g130");
        }
        crushed += G129Kit.crush(this, foot, STEP_R, marked, 0.5);
    }

    // ------------------------------------------------------------------ 멍함

    private void startDaze() {
        unmark();
        act = Act.NONE;
        stompPending = false;
        legs.lift = 1.3;
        debt.zero();
        state("daze");
        sound("wind", Sound.BLOCK_DISPENSER_FAIL, 0.4f, 64);
        for (float[] r : armRot) {
            r[0] = 0;
            r[1] = 8;
        }
        armRot[0][2] = 28;
        armRot[1][2] = -28;
        poseArms(8);
        phase(Phase.DAZE);
    }

    private void daze() {
        hold();
        if (inPhase() < DAZE_T) return;
        knob[0] = knob[1] = KNOB_HP * 0.5 * Math.max(0.5, maxHp / Math.max(1, sp.hp));
        body.look.on.remove("cut0");
        body.look.on.remove("cut1");
        state("");
        restArms();
        sound("wind", Sound.BLOCK_DISPENSER_FAIL, 0.6f, 64);
        phase(Phase.APPROACH);
    }

    // ------------------------------------------------------------------ 팔

    private void restArms() {
        armRot[0][0] = 0;
        armRot[0][1] = 0;
        armRot[0][2] = 10;
        armRot[1][0] = 0;
        armRot[1][1] = 0;
        armRot[1][2] = -10;
        poseArms(6);
    }

    /** 걷는 동안 팔이 앞뒤로 흔들림(쉴 때만). */
    private void armsTick() {
        if (act != Act.NONE || phase == Phase.DAZE || age - armPosedAt < 6) return;
        double v = vel.clone().setY(0).length();
        float sw = (float) (Math.sin(age * 0.25) * Math.min(25, v * 60));
        armRot[0][1] = sw;
        armRot[1][1] = -sw;
        poseArms(6);
    }

    private Matrix4f armM(int i) {
        float x = (float) (i == 0 ? SH_X : -SH_X);
        float[] r = armRot[i];
        return new Matrix4f().translate(x, (float) SH_Y, 0).rotateY((float) Math.toRadians(r[0])).rotateX((float) Math.toRadians(r[1]))
            .rotateZ((float) Math.toRadians(r[2]));
    }

    private void poseArms(int ticks) {
        armPosedAt = age;
        for (int i = 0; i < 2; i++) if (arms[i] != null) arms[i].pose(armM(i), ticks);
    }

    /** 팔 i 위 몫 f(어깨 0 .. 손 1) 세계 점. */
    private Vector armPoint(int i, double f) {
        Vector3f v = armM(i).transformPosition(new Vector3f(0, (float) (-ARM_L * f), 0));
        return G129Kit.at(this, v.x, v.y, v.z);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(G129Kit.at(this, 0, HEAD_Y, 0.1), 1.65);                             // 0 머리
        add(G129Kit.at(this, 0, 0.3, 0), 1.85);                                  // 1 몸통 위
        add(G129Kit.at(this, 0, -1.9, 0), 1.6);                                  // 2 몸통 아래
        double ky = HEAD_Y + 1.5 + 1.05;
        add(G129Kit.at(this, KNOB_X, ky, 0), knob[0] > 0 ? 0.55 : 0.01);       // 3 태엽 손잡이
        add(G129Kit.at(this, -KNOB_X, ky, 0), knob[1] > 0 ? 0.55 : 0.01);      // 4
        if (legs != null) {
            for (int i = 0; i < 2; i++) {
                add(legs.along(i, 0.35), 0.7);                                   // 5, 7 다리
                add(legs.along(i, 0.8), 0.6);                                    // 6, 8
            }
        }
        for (int i = 0; i < 2; i++) add(armPoint(i, 0.85), 0.8);                 // 9, 10 손
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        double k = phase == Phase.DAZE ? DAZE_MUL : 1.0;
        if (d.at() != null && phase != Phase.DAZE) {
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
            if ((best == 3 || best == 4) && knob[best - 3] > 0) {
                int j = best - 3;
                knob[j] -= d.amount();
                if (knob[j] <= 0) {
                    body.look.on.add("cut" + j);
                    sound("wind", Sound.BLOCK_DISPENSER_FAIL, 0.4f, 64);
                    if (knob[0] <= 0 && knob[1] <= 0) {
                        boolean ok = super.damage(d);
                        if (!dead) startDaze();
                        return ok;
                    }
                }
            }
        }
        return super.damage(k == 1.0 ? d : d.times(k));
    }

    @Override
    public void die(Damage d) {
        unmark();
        super.die(d);
    }

    @Override
    public void remove() {
        unmark();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " act=" + act + " marked=" + (marked == null ? "-" : marked.getName()) + " knob="
            + Math.round(knob[0]) + "/" + Math.round(knob[1]) + " debt=" + String.format("%.1f", debt.length()) + " marks=" + marks + " punch=" + punches
            + " stomp=" + stomps + " sweep=" + sweeps + " crushed=" + crushed + " steps=" + (legs == null ? 0 : legs.steps);
    }
}
