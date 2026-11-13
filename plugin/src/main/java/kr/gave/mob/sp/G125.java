package kr.gave.mob.sp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * G125 갈비 우리 거인 게이브(3막, 정예, 튼튼이). 키 9블록 뼈대 거인: 3블록 얼굴 아래 가슴이 엔드 막대기 열두 대의 갈비 우리, 등뼈 한가운데 붉은 심장 얼굴,
 * 막대기 팔다리(부품 넷). 팔을 늘어뜨리고 뒤뚱뒤뚱(걸음마다 땅울림, 발에 밟히면 24) 6블록 안 사람이 가장 많은 사람에게 가서, 허리를 굽히며 갈비 창살을
 * 벌리고(1초 예고) 막대기 손가락으로 한 사람을 집어 4초에 걸쳐 가슴으로 가져가고(초당 9), 다 넣으면 갇힘(셋까지. 넷째를 넣으면 맨 먼저 가둔 사람이
 * 밀려 나와 풀리고 발밑 반경 3 에 15). 곁 사람은 손으로 휩쓴다(30과 날림). 앞에서 준 피해는 3초 뒤 절반이 도로 붙는다. 등뼈의 심장 얼굴만 세 배이고
 * 안 붙는다(앞에서는 우리에 가려 안 맞음). 집는 손목을 쏘면 놓친다. 갈비 창살 넷을 3초 안에 부러뜨리면(하나 60) 갇힌 사람이 모두 빠져나온다.
 *
 * <p>팩 계약(tools/gabe/sp/g125.py): gabe/g125(shrink 0.3, 상태 "", "bend", 부위 r0..r11, in1..in3), 팔 gabe/g125_arm(상태 "", "grip"),
 * 다리 gabe/g125_leg. 소리 step, breath, creak, snap. 글귀 cause_g125, cause_g125_step, cause_g125_sweep, cause_g125_drop.
 *
 * <p>도감과 다른 점: 갇힌 사람은 엔진 갇힘(숨김, 머리 위 좌석)이라 창살 사이로는 웅크린 작은 모습(부위 in1..in3)이 대신 보인다. 부러진 창살은 3초 뒤
 * 다시 붙는다. 화면 흔들림은 걸음마다 서른 블록 안에 사람이 있을 때만.
 */
public final class G125 extends Gabe {

    static final double HOVER = 5.0, HIP_X = 0.75, HIP_Y = -1.5, SH_X = 1.55, SH_Y = 1.15, SH_Z = -0.25, LEG_L = 3.6, ARM_L = 4.0, BEND = 35;
    static final double SPEED = 0.13, REACH = 4.6, STEP_R = 1.8, STEP_DMG = 15, CARRY_DPS = 6, DROP_R = 3, DROP_DMG = 9, SWEEP_R = 4.4,
        SWEEP_DMG = 19, RIB_HP = 60, REGROW = 0.5, HEART_MUL = 3.0;
    static final int TELL = 20, CARRY = 80, PICK_CD = 100, SWEEP_TELL = 14, SWEEP_CD = 60, REGROW_T = 60, RIB_WINDOW = 60, MAX_IN = 3,
        ESCAPE = 8;
    static final int RIBS = 12;

    enum Phase { WALK, TELL, CARRY, SWEEP }

    private Phase phase = Phase.WALK;
    private int phaseAt, pickReady, sweepReady, breathAt, rollAt, picks, frees;
    private Player prey, held;
    private final Vector pickAt = new Vector(), handFrom = new Vector();
    private final Deque<Player> caged = new ArrayDeque<>();
    private final List<double[]> regrow = new ArrayList<>();
    private final double[] rib = new double[RIBS];
    private final int[] ribAt = new int[RIBS];
    private boolean ribInit;
    private G129Kit.Legs legs;
    private final Body.Part[] arms = new Body.Part[2];
    private int armPosedAt = -100;
    private final List<double[]> sph = new ArrayList<>(24);
    private int sphAge = -1;

    public G125(Game game, Species sp) {
        super(game, sp);
        radius = 1.6;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragKick = 0.4;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g125", 9f, 11f);
        body.viewRange(3f);
        legs = new G129Kit.Legs(this, "gabe/g125_leg", LEG_L, new double[] {HIP_X, -HIP_X}, HIP_Y, 0);
        legs.reach = 1.3;
        legs.lead = 1.2;
        legs.lift = 0.9;
        legs.stance = 0.35;
        legs.swing = 12;
        legs.land = this::landed;
        for (int i = 0; i < 2; i++) arms[i] = body.attach("gabe/g125_arm");
        restArms(0);
        G129Kit.bright(body);
    }

    @Override
    protected void spawned() {
        breathAt = age + 60;
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
        if (!ribInit) {
            double k = Math.max(0.5, maxHp / Math.max(1, sp.hp));
            for (int i = 0; i < RIBS; i++) rib[i] = RIB_HP * k;
            ribInit = true;
        }
        regrowTick();
        ribsTick();
        cageTick();
        switch (phase) {
            case WALK -> walk();
            case TELL -> tell();
            case CARRY -> carry();
            case SWEEP -> sweep();
        }
        legs.swing = (int) Math.max(8, Math.min(14, 1.2 / Math.max(0.03, vel.clone().setY(0).length())));
        legs.tick();
        waddle();
        if (age >= breathAt) {
            sound("breath", Sound.ENTITY_POLAR_BEAR_WARNING, 0.5f, 32);
            breathAt = age + 80 + game.random.nextInt(60);
        }
    }

    /** 6블록 안 서 있는 사람이 가장 많은 사람(그 수가 같으면 가까운 쪽). */
    private Player crowded() {
        List<Player> ps = game.mobs.standing();
        Player best = null;
        double bs = -1;
        for (Player p : ps) {
            Location l = p.getLocation();
            int n = 0;
            for (Player q : ps) if (q.getLocation().distanceSquared(l) <= 36) n++;
            double s = n * 100 - dist(p);
            if (s > bs) {
                bs = s;
                best = p;
            }
        }
        return best;
    }

    private void walk() {
        if (prey == null || !standing(prey) || every(20)) prey = crowded();
        Player p = prey;
        mark(p);
        if (p == null) {
            follow(speed());
            return;
        }
        Vector to = p.getLocation().toVector().setY(pos.getY());
        double h = G129Kit.flat(pos, to);
        G129Kit.faceFlat(this, to);
        if (h > REACH - 0.8) seek(to, speed());
        else hold();
        if (h <= REACH && age >= pickReady && caged.size() < MAX_IN + 1) {
            startTell(p);
            return;
        }
        // 곁 사람 휩쓸기(집을 수 없을 때)
        if (age >= sweepReady && age < pickReady) {
            for (Player q : game.mobs.standing()) {
                if (G129Kit.flat(q.getLocation(), pos) <= SWEEP_R - 0.5) {
                    startSweep(q);
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ 집기

    private void startTell(Player p) {
        hold();
        prey = p;
        G129Kit.faceFlat(this, p.getLocation().toVector());
        state("bend");
        sound("creak", Sound.BLOCK_WOODEN_DOOR_OPEN, 0.5f, 32);
        pickAt.copy(p.getLocation().toVector());
        G129Kit.warn(this, pickAt, 1.6, cd(TELL) + 2);
        reachArm(1, pickAt.clone().add(new Vector(0, 0.9, 0)), cd(TELL));
        phase(Phase.TELL);
    }

    private void tell() {
        hold();
        if (prey != null && standing(prey)) {
            pickAt.copy(prey.getLocation().toVector());
            if (every(4)) reachArm(1, pickAt.clone().add(new Vector(0, 0.9, 0)), 4);
        }
        if (inPhase() < cd(TELL)) return;
        Player p = prey;
        if (p != null && standing(p) && G129Kit.flat(p.getLocation(), pos) <= REACH + 1.2 && game.players.grab(p, this, ESCAPE)) {
            held = p;
            PState s = game.players.get(p);
            s.grabPin = true;
            s.grabDetach = true;
            handFrom.copy(handWorld(1));
            if (arms[1] != null) arms[1].state("grip");
            sound("creak", Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.7f, 32);
            picks++;
            phase(Phase.CARRY);
            return;
        }
        // 못 집음: 손바닥으로 그 자리를 내리침(휩쓸기 피해)
        sweepHit(pickAt, 2.2);
        endPick();
    }

    /** 4초에 걸쳐 가슴으로 가져감(초당 9). 다 넣으면 갇힘. */
    private void carry() {
        hold();
        Player p = held;
        if (p == null || !p.isOnline() || game.players.grabbedBy(p) != this) {
            if (p != null && game.players.grabbedBy(p) == this) game.players.release(p);
            endPick();
            return;
        }
        int t = inPhase(), n = cd(CARRY);
        double f = Math.min(1, t / (double) n);
        Vector chest = bodyPt(0, 0.0, 0.2);
        Vector at = handFrom.clone().multiply(1 - f).add(chest.clone().multiply(f));
        game.players.carry(p, at.clone().add(new Vector(0, -0.2, 0)));
        if (t % 2 == 0) reachArm(1, at, 2);
        if (t > 0 && t % 20 == 0) hit(p, CARRY_DPS, "cause_g125");
        if (t >= n && standing(p)) {
            game.players.release(p);
            game.players.trap(p, this);
            caged.addLast(p);
            if (caged.size() > MAX_IN) {
                Player first = caged.pollFirst();
                if (first != null && game.players.free(first)) {
                    frees++;
                    Vector g0 = G129Kit.ground(this, pos);
                    G129Kit.ring(this, g0, DROP_R, 10);
                    for (Player q : game.mobs.standing()) if (q != first && G129Kit.flat(q.getLocation(), g0) <= DROP_R) hit(q, DROP_DMG, "cause_g125_drop");
                }
            }
            sound("creak", Sound.BLOCK_IRON_DOOR_CLOSE, 0.6f, 32);
            endPick();
        } else if (t >= n) endPick();
    }

    private void endPick() {
        held = null;
        state("");
        if (arms[1] != null) arms[1].state("");
        restArms(8);
        pickReady = age + cd(PICK_CD);
        phase(Phase.WALK);
    }

    /** 갇힌 사람 수만큼 우리 안 모습. 빠져나간 사람(나감, 풀림, 죽음)은 셈에서 뺌. */
    private void cageTick() {
        for (Iterator<Player> it = caged.iterator(); it.hasNext();) {
            Player p = it.next();
            PState s = game.players.get(p);
            if (!p.isOnline() || s.life != Life.TRAPPED || s.eater != this) it.remove();
        }
        int n = caged.size();
        for (int i = 1; i <= 3; i++) {
            if (n >= i) body.look.on.add("in" + i);
            else body.look.on.remove("in" + i);
        }
    }

    // ------------------------------------------------------------------ 휩쓸기

    private void startSweep(Player q) {
        hold();
        G129Kit.faceFlat(this, q.getLocation().toVector());
        Vector side = G129Kit.left(this).multiply(-1);
        reachArm(1, bodyPt(-3.0, -1.0, 1.0), cd(SWEEP_TELL));
        G129Kit.warn(this, G129Kit.ground(this, pos).add(G129Kit.fwd(this).multiply(2.2)).add(side.multiply(0.5)), SWEEP_R * 0.6, cd(SWEEP_TELL) + 2);
        phase(Phase.SWEEP);
    }

    private void sweep() {
        hold();
        int t = cd(SWEEP_TELL);
        if (inPhase() == t) {
            reachArm(1, bodyPt(3.0, -3.5, 2.5), 4);
            sweepHit(G129Kit.ground(this, pos).add(G129Kit.fwd(this).multiply(2.0)), SWEEP_R * 0.75);
        }
        if (inPhase() >= t + 10) {
            restArms(8);
            sweepReady = age + cd(SWEEP_CD);
            phase(Phase.WALK);
        }
    }

    private void sweepHit(Vector c, double r) {
        Vector f = G129Kit.fwd(this);
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (G129Kit.flat(l, c) > r || Math.abs(l.getY() - c.getY()) > 4) continue;
            hit(q, SWEEP_DMG, "cause_g125_sweep");
            knock(q, f.clone().add(new Vector(l.getX() - pos.getX(), 0, l.getZ() - pos.getZ()).normalize()), 1.1, 0.55);
        }
        G129Kit.ring(this, c, r, 8);
    }

    // ------------------------------------------------------------------ 걸음

    private void landed(int i, Vector foot) {
        G129Kit.soundAt(this, foot, "step", Sound.ENTITY_RAVAGER_STEP, 0.9f, 64);
        boolean near = false;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double h = G129Kit.flat(l, foot);
            if (h < 30) near = true;
            if (h <= STEP_R && Math.abs(l.getY() - foot.getY()) < 3) hit(q, STEP_DMG, "cause_g125_step");
        }
        G129Kit.stomp(this, foot, 64, near ? 10 : 0, 0.8f);
        if (near && game.horizon != null) game.horizon.shake(0.3, 8);
        G129Kit.crush(this, foot, 1.4, null, 0);
    }

    /** 뒤뚱(4틱마다 좌우 기울기). */
    private void waddle() {
        if (age - rollAt < 4) return;
        rollAt = age;
        double v = vel.clone().setY(0).length();
        float r = phase == Phase.WALK && v > 0.02 ? (float) (Math.sin(age * 0.16) * 7) : 0f;
        if (Math.abs(r - body.roll) > 0.5f) body.roll = r;
        if (phase == Phase.WALK && age - armPosedAt >= 8) {
            float sw = (float) (Math.sin(age * 0.16) * Math.min(18, v * 120));
            poseArm(0, new Matrix4f().translate((float) SH_X, (float) SH_Y, (float) SH_Z).rotateX((float) Math.toRadians(sw)).rotateZ(0.2f), 8);
            poseArm(1, new Matrix4f().translate((float) -SH_X, (float) SH_Y, (float) SH_Z).rotateX((float) Math.toRadians(-sw)).rotateZ(-0.2f), 8);
        }
    }

    // ------------------------------------------------------------------ 팔

    /** 몸 틀 점(숙이면 엉덩이 축으로 돌림) → 세계. */
    private Vector bodyPt(double x, double y, double z) {
        if ("bend".equals(state)) {
            double a = Math.toRadians(BEND), ry = y - HIP_Y;
            return G129Kit.at(this, x, ry * Math.cos(a) - z * Math.sin(a) + HIP_Y, ry * Math.sin(a) + z * Math.cos(a));
        }
        return G129Kit.at(this, x, y, z);
    }

    private Vector shoulderLocal(int i) {
        double x = i == 0 ? SH_X : -SH_X, y = SH_Y, z = SH_Z;
        if ("bend".equals(state)) {
            double a = Math.toRadians(BEND), ry = y - HIP_Y;
            return new Vector(x, ry * Math.cos(a) - z * Math.sin(a) + HIP_Y, ry * Math.sin(a) + z * Math.cos(a));
        }
        return new Vector(x, y, z);
    }

    /** 팔 i 를 세계 점 w 쪽으로 뻗음(길이가 모자라면 1.5배까지 늘임). */
    private void reachArm(int i, Vector w, int ticks) {
        Vector sh = shoulderLocal(i);
        Vector wl = G129Kit.toLocal(this, w);
        Vector d = wl.clone().subtract(sh);
        double l = Math.max(0.5, d.length());
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, -1, 0), new Vector3f((float) (d.getX() / l), (float) (d.getY() / l), (float) (d.getZ() / l)));
        float s = (float) Math.max(0.6, Math.min(1.5, l / ARM_L));
        poseArm(i, new Matrix4f().translate((float) sh.getX(), (float) sh.getY(), (float) sh.getZ()).rotate(q).scale(1f, s, 1f), ticks);
    }

    private void restArms(int ticks) {
        poseArm(0, new Matrix4f().translate((float) SH_X, (float) SH_Y, (float) SH_Z).rotateZ(0.2f), ticks);
        poseArm(1, new Matrix4f().translate((float) -SH_X, (float) SH_Y, (float) SH_Z).rotateZ(-0.2f), ticks);
    }

    private final Matrix4f[] armM = {new Matrix4f(), new Matrix4f()};

    private void poseArm(int i, Matrix4f m, int ticks) {
        armM[i].set(m);
        armPosedAt = age;
        if (arms[i] != null) arms[i].pose(m, ticks);
    }

    private Vector handWorld(int i) {
        Vector3f v = armM[i].transformPosition(new Vector3f(0, (float) -ARM_L * 0.9f, 0));
        return G129Kit.at(this, v.x, v.y, v.z);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(bodyPt(0, 3.05, 0.1), 1.65);                               // 0 머리
        add(bodyPt(0, 0.1, 0), 1.3);                                   // 1 우리(가슴)
        add(bodyPt(0, 0.15, -1.15), 0.5);                              // 2 심장 얼굴(등)
        add(handWorld(1), 0.65);                                       // 3 손(집는 손목)
        add(handWorld(0), 0.55);                                       // 4
        if (legs != null) {
            add(legs.along(0, 0.5), 0.45);                             // 5, 6 다리
            add(legs.along(1, 0.5), 0.45);
        }
        for (int i = 0; i < RIBS; i++) {                               // 7.. 앞 창살(앞 여섯)
            double a = Math.toRadians(i * 360.0 / RIBS + 15);
            if (Math.sin(a) <= 0.2) continue;
            add(bodyPt(1.32 * Math.cos(a), 0.1, 1.32 * Math.sin(a) - 0.05), 0.42);
        }
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.at() == null) return front(d);
        List<double[]> s = spheres();
        int best = 1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < s.size(); i++) {
            double[] c = s.get(i);
            double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        if (best == 2) {
            // 심장: 등 뒤에서 온 탄(앞으로 날아가는 탄)이나 폭발, 불만
            Vector f = G129Kit.fwd(this);
            boolean behind = d.dir() == null || d.type() == DamageType.EXPLOSION || d.type() == DamageType.FIRE
                || d.dir().getX() * f.getX() + d.dir().getZ() * f.getZ() > 0.15;
            if (behind) return super.damage(d.times(HEART_MUL));
            return front(d);
        }
        if (best == 3 && phase == Phase.CARRY && held != null) {
            // 집는 손목을 쏘면 놓침
            Player p = held;
            game.players.release(p);
            endPick();
            return front(d);
        }
        if (best >= 7) ribHit(best, d);
        return front(d);
    }

    /** 앞(심장 아닌 곳)에서 준 피해: 깎되 절반은 3초 뒤 도로 붙음. */
    private boolean front(Damage d) {
        double before = hp;
        boolean ok = super.damage(d);
        double took = before - hp;
        if (!dead && took > 0) regrow.add(new double[] {age + REGROW_T, took * REGROW});
        return ok;
    }

    private void regrowTick() {
        if (regrow.isEmpty()) return;
        for (Iterator<double[]> it = regrow.iterator(); it.hasNext();) {
            double[] r = it.next();
            if (age < r[0]) continue;
            hp = Math.min(maxHp, hp + r[1]);
            it.remove();
        }
    }

    /** 앞 창살 구 번호 → 창살 번호. 넷이 3초 안에 부러지면 갇힌 사람 모두 풀림. 부러진 창살은 3초 뒤 다시 붙음. */
    private void ribHit(int sphere, Damage d) {
        int seen = 7;
        int idx = -1;
        for (int i = 0; i < RIBS; i++) {
            double a = Math.toRadians(i * 360.0 / RIBS + 15);
            if (Math.sin(a) <= 0.2) continue;
            if (seen++ == sphere) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || rib[idx] <= 0) return;
        rib[idx] -= d.amount();
        if (rib[idx] > 0) return;
        ribAt[idx] = age;
        body.look.on.add("r" + idx);
        sound("snap", Sound.ENTITY_SKELETON_HURT, 0.6f, 32);
        int recent = 0;
        for (int i = 0; i < RIBS; i++) if (rib[i] <= 0 && age - ribAt[i] <= RIB_WINDOW) recent++;
        if (recent >= 4 && !caged.isEmpty()) {
            for (Player p : new ArrayList<>(caged)) if (game.players.free(p)) frees++;
            caged.clear();
            sound("snap", Sound.ENTITY_SKELETON_HURT, 0.4f, 48);
        }
    }

    private void ribsTick() {
        if (!ribInit) return;
        double k = Math.max(0.5, maxHp / Math.max(1, sp.hp));
        for (int i = 0; i < RIBS; i++) {
            if (rib[i] > 0 || age - ribAt[i] < RIB_WINDOW) continue;
            rib[i] = RIB_HP * k;
            body.look.on.remove("r" + i);
        }
    }

    @Override
    public void die(Damage d) {
        if (held != null && game.players.grabbedBy(held) == this) game.players.release(held);
        super.die(d);
    }

    @Override
    public String toString() {
        int broken = 0;
        for (double r : rib) if (r <= 0) broken++;
        return super.toString() + " ph=" + phase + " prey=" + (prey == null ? "-" : prey.getName()) + " held=" + (held == null ? "-" : held.getName())
            + " caged=" + caged.size() + " picks=" + picks + " frees=" + frees + " regrow=" + regrow.size() + " ribsBroken=" + broken + " steps="
            + (legs == null ? 0 : legs.steps);
    }
}
