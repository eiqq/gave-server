package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G129 가로지르는 장대 거인 게이브(3막, 튼튼이, 돌격, 목표 압박). 키 16블록: 엔드 막대기 다발 장대 다리 둘(부품) 위 얼굴 비늘 기둥과 3블록 얼굴.
 * 사람이 가장 많이 지나는 직선을 골라 경기장을 일직선으로 가로지르고(한 걸음 5블록, 방향을 안 바꿈), 건너가면 R 58 밖에서 크게 돌아 새 직선으로 다시 온다.
 * 발을 들 때 디딜 자리에 발바닥 그림자가 짙어지고(1초 넘게), 디디면 반경 2.5 안 30 피해와 넘어짐 1초, 반경 6 충격파 고리 6(뛰어 넘으면 피함).
 * 발밑의 값싼 게이브도 밟혀 튄다(보상 없음). 줄에 트럭이 걸리면 밟고 지나가 트럭을 크게 깎는다. 무릎 얼굴(무릎마다, x2.5)을 둘 다 꺾으면 무릎을 꿇고
 * 12초 동안 등의 큰 얼굴이 하늘을 본다(x3). 쓰러질 때 넘어지는 쪽 길이 12 띠에 25.
 *
 * <p>팩 계약(tools/gabe/sp/g129.py): 모델 gabe/g129(shrink 0.27, 상태 "", "kneel"), 다리 gabe/g129_leg(shrink 0.16, 엉덩이 (0,0,0) 발 (0,-9,0),
 * 부위 broke). 소리 stride, kneel, fall. 글귀 cause_g129, cause_g129_wave, cause_g129_fall.
 *
 * <p>도감과 다른 점: 한 걸음은 5블록(다리 길이를 지나치게 늘이지 않으려고 6 에서 줄임), 걸음 박자는 빠르기에서 나온다(웨이브가 깊으면 빨라짐).
 * 무릎을 꿇은 뒤 12초면 다시 일어난다(무릎 체력 절반으로, 외곽에서 멈추지 않게). 무릎 체력은 최대 체력의 8%.
 */
public final class G129 extends Gabe {

    static final double HOVER = 11.2, HOVER_KNEEL = 5.2, HIP_X = 1.3, HIP_Y = -2.45, LEG_L = 9.0, HEAD_Y = 3.35;
    static final double SPEED = 0.15, REACH = 5.0, LIFT = 2.4, BOB = 0.9;
    /** 디딤: 반경, 피해, 넘어짐 틱. 충격파 반경, 피해. 쓰러짐 띠 길이, 폭, 피해. */
    static final double STEP_R = 2.5, STEP_DMG = 30, WAVE_R = 6, WAVE_DMG = 6, FALL_LEN = 12, FALL_W = 3, FALL_DMG = 25;
    static final int DOWN_T = 20, KNEEL_T = 240;
    /** 무릎: 반지름, 배율, 체력 몫. 등 얼굴 배율. */
    static final double KNEE_R = 0.85, KNEE_MUL = 2.5, KNEE_HP = 0.08, BACK_MUL = 3.0;
    /** 이만큼 중앙에서 멀어지며 바깥으로 가면 돈다. */
    static final double R_TURN = 58;
    static final double KNEEL_DEG = 40;

    enum Phase { CROSS, TURN, KNEEL }

    private Phase phase = Phase.CROSS;
    private int phaseAt;
    private G129Kit.Legs legs;
    private final Vector lineDir = new Vector(0, 0, 1), lineFrom = new Vector();
    private final double[] knee = {-1, -1};
    private boolean kneeInit;
    private final ItemDisplay[] shadow = new ItemDisplay[2];
    private final int[] shadowAt = {-1, -1}, shadowLen = {0, 0};
    private int lines, truckAt;
    private final List<double[]> sph = new ArrayList<>(10);
    private int sphAge = -1;

    public G129(Game game, Species sp) {
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
        ragKick = 0.25;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g129", 13f, 23f);
        body.viewRange(3f);
        legs = new G129Kit.Legs(this, "gabe/g129_leg", LEG_L, new double[] {HIP_X, -HIP_X}, HIP_Y, 0);
        legs.reach = REACH;
        legs.lead = REACH;
        legs.lift = LIFT;
        legs.stance = 0.5;
        legs.swing = 26;
        legs.land = this::landed;
        legs.lifted = this::lifted;
        G129Kit.bright(body);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Vector c = new Vector(Plaza.CX + 0.5, pos.getY(), Plaza.CZ + 0.5);
        yaw = G129Kit.yawOf(c.subtract(pos).setY(0));
        pickLine();
        yaw = G129Kit.yawOf(lineDir);
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
        if (!kneeInit) {
            knee[0] = knee[1] = Math.max(60, maxHp * KNEE_HP);
            kneeInit = true;
        }
        Player p = nearest(400);
        mark(p != null && dist(p) < 48 ? p : null);
        switch (phase) {
            case CROSS -> cross();
            case TURN -> turn();
            case KNEEL -> kneel();
        }
        // 걸음 박자: 빠를수록 발을 빨리 옮김(디딤 그림자는 늘 0.6초 넘게)
        double v = Math.max(0.05, speed());
        legs.swing = (int) Math.max(12, Math.min(26, REACH / v * 0.9));
        legs.tick();
        if (phase != Phase.KNEEL) hover = HOVER - BOB * Math.min(1, Math.pow(legs.spread() / REACH, 2));
        shadows();
        truckBody();
    }

    /** 직선을 따라 곧게. 중앙에서 R_TURN 넘게 멀어지며 바깥으로 가면 돈다. */
    private void cross() {
        double sp = speed();
        vel.setX(lineDir.getX() * sp).setZ(lineDir.getZ() * sp);
        turnYaw(lineDir, 2f);
        Vector c = new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5);
        double dx = pos.getX() - c.getX(), dz = pos.getZ() - c.getZ();
        boolean out = dx * lineDir.getX() + dz * lineDir.getZ() > 0;
        if ((out && Math.hypot(dx, dz) > R_TURN) || inPhase() > 1800) {
            pickLine();
            phase(Phase.TURN);
        }
    }

    /** 크게 돌기: 반 빠르기로 걸으며 새 직선 쪽으로 한 틱 1.5도씩. */
    private void turn() {
        Vector f = G129Kit.fwd(this);
        double sp = speed() * 0.55;
        vel.setX(f.getX() * sp).setZ(f.getZ() * sp);
        float want = G129Kit.yawOf(lineDir);
        float d = wrap(want - yaw);
        yaw += Math.max(-1.5f, Math.min(1.5f, d));
        if (Math.abs(d) < 6 || inPhase() > 400) {
            yaw = want;
            lineFrom.copy(pos);
            phase(Phase.CROSS);
        }
    }

    private void turnYaw(Vector dir, float max) {
        float d = wrap(G129Kit.yawOf(dir) - yaw);
        yaw += Math.max(-max, Math.min(max, d));
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** 사람이 가장 많이 걸리는 직선(지금 자리에서 앞으로 160블록, 폭 4). 트럭을 지나면 덤. */
    private void pickLine() {
        List<Player> ps = game.mobs.standing();
        List<Vector> cands = new ArrayList<>();
        for (Player q : ps) cands.add(q.getLocation().toVector());
        Truck t = truck();
        Vector tc = t != null ? Truck.center() : null;
        if (tc != null) cands.add(tc.clone());
        if (cands.isEmpty()) cands.add(new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5));
        Vector best = null;
        double bs = -1;
        for (Vector c : cands) {
            Vector d = c.clone().subtract(pos).setY(0);
            double l = d.length();
            if (l < 8) continue;
            d.multiply(1 / l);
            double s = 0;
            for (Player q : ps) {
                Location ql = q.getLocation();
                double rx = ql.getX() - pos.getX(), rz = ql.getZ() - pos.getZ();
                double along = rx * d.getX() + rz * d.getZ();
                double lat = Math.abs(rx * d.getZ() - rz * d.getX());
                if (along > 0 && along < 160 && lat < 4) s += 1;
            }
            if (tc != null) {
                double rx = tc.getX() - pos.getX(), rz = tc.getZ() - pos.getZ();
                if (rx * d.getX() + rz * d.getZ() > 0 && Math.abs(rx * d.getZ() - rz * d.getX()) < 3) s += 1.5;
            }
            s -= l * 0.001;
            if (s > bs) {
                bs = s;
                best = d;
            }
        }
        if (best == null) best = new Vector(Plaza.CX + 0.5 - pos.getX(), 0, Plaza.CZ + 0.5 - pos.getZ()).normalize();
        lineDir.copy(best);
        lineFrom.copy(pos);
        lines++;
    }

    private Truck truck() {
        Truck t = game.trip == null ? null : game.trip.truck();
        return t != null && t.alive() ? t : null;
    }

    // ------------------------------------------------------------------ 걸음

    /** 발을 듦: 디딜 자리에 발바닥 그림자가 자라며 짙어짐. */
    private void lifted(int i, Vector to, int ticks) {
        shadow[i] = G129Kit.plate(this, "gabe/b1_shadow", "", to, 2.0, ticks + 6, ticks);
        shadowAt[i] = age;
        shadowLen[i] = ticks;
    }

    private void shadows() {
        for (int i = 0; i < 2; i++) {
            if (shadowAt[i] < 0) continue;
            if (age - shadowAt[i] >= shadowLen[i] * 0.6) {
                G129Kit.plateState(this, shadow[i], "gabe/b1_shadow", "dark");
                shadowAt[i] = -1;
            }
        }
    }

    /** 디딤: 밟기, 충격파, 떼 밟기, 트럭, 웅덩이 물결, 땅울림. */
    private void landed(int i, Vector foot) {
        if (phase == Phase.KNEEL) {
            G129Kit.stomp(this, foot, 64, 10, 0.8f);
            return;
        }
        G129Kit.stomp(this, foot, 96, 16, 1f);
        G129Kit.soundAt(this, foot, "stride", Sound.ENTITY_RAVAGER_STEP, 1f, 96);
        G129Kit.wave(this, foot, 1.0, WAVE_R, 6);
        G129Kit.ripples(this, 70);
        boolean near = false;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double h = G129Kit.flat(l, foot);
            if (h < 50) near = true;
            if (Math.abs(l.getY() - foot.getY()) > 3) continue;
            if (h <= STEP_R) {
                hit(q, STEP_DMG, "cause_g129");
                game.players.root(q, DOWN_T, "g129");
                Vector out = new Vector(l.getX() - foot.getX(), 0, l.getZ() - foot.getZ());
                knock(q, out.lengthSquared() < 1e-4 ? G129Kit.fwd(this) : out, 0.5, 0.25);
            } else if (h <= WAVE_R && q.isOnGround()) {
                hit(q, WAVE_DMG, "cause_g129_wave");
            }
        }
        if (near && game.horizon != null) game.horizon.shake(0.3, 10);
        G129Kit.crush(this, foot, STEP_R, null, 0);
        Truck t = truck();
        if (t != null && Truck.dist(foot.getX(), foot.getZ()) < 2.2) {
            t.hit(t.biteDamage(sp) * 8, this, "cause_g129");
            if (game.horizon != null) game.horizon.shake(0.5, 14);
        }
    }

    /** 큰 몸이 트럭 발자국 위를 지나면 초당 1%(원정.md 4.4). */
    private void truckBody() {
        if (age < truckAt) return;
        Truck t = truck();
        if (t == null || Truck.dist(pos.getX(), pos.getZ()) > 2) return;
        t.hitFrac(0.01, this, "cause_g129");
        truckAt = age + 20;
    }

    // ------------------------------------------------------------------ 무릎 꿇음

    private void startKneel() {
        phase(Phase.KNEEL);
        state("kneel");
        hover = HOVER_KNEEL;
        sound("kneel", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.6f, 64);
        legs.pinned = true;
        Vector f = G129Kit.fwd(this), l = G129Kit.left(this);
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? 1 : -1;
            Vector to = pos.clone().add(l.clone().multiply(side * 2.6)).subtract(f.clone().multiply(6.5));
            legs.step(i, to, 14);
        }
        G129Kit.ring(this, G129Kit.ground(this, pos), 4.5, 14);
        if (game.horizon != null) game.horizon.shake(0.4, 12);
    }

    private void kneel() {
        hold();
        if (inPhase() < KNEEL_T) return;
        knee[0] = knee[1] = Math.max(30, maxHp * KNEE_HP * 0.5);
        for (Body.Part pt : legs.part) if (pt != null) pt.look.on.remove("broke");
        state("");
        hover = HOVER;
        legs.pinned = false;
        for (int i = 0; i < 2; i++) legs.step(i, legs.rest(i, null, 0), 24);
        pickLine();
        phase(Phase.TURN);
    }

    // ------------------------------------------------------------------ 맞음

    /** 몸 틀 점(숙임이면 엉덩이 축으로 돌림) → 세계. */
    private Vector part(double x, double y, double z) {
        if (phase == Phase.KNEEL) {
            double a = Math.toRadians(KNEEL_DEG), ry = y - HIP_Y;
            double y2 = ry * Math.cos(a) - z * Math.sin(a) + HIP_Y, z2 = ry * Math.sin(a) + z * Math.cos(a);
            return G129Kit.at(this, x, y2, z2);
        }
        return G129Kit.at(this, x, y, z);
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(part(0, HEAD_Y, 0.15), 1.65);                 // 0 머리
        add(part(0, 0.6, 0), 1.45);                       // 1 몸통 위
        add(part(0, -1.5, 0), 1.45);                      // 2 몸통 아래
        if (legs != null) {
            add(legs.knee(0), KNEE_R);                    // 3 무릎
            add(legs.knee(1), KNEE_R);                    // 4
            for (int i = 0; i < 2; i++) {
                add(legs.along(i, 0.2), 0.55);            // 5..8 장대
                add(legs.along(i, 0.8), 0.55);
            }
        }
        if (phase == Phase.KNEEL) add(part(0, HIP_Y + 2.0, -1.15), 1.05); // 9 등 얼굴
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.at() == null) return super.damage(d);
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
        if ((best == 3 || best == 4) && phase != Phase.KNEEL) {
            int k = best - 3;
            double amt = d.amount() * KNEE_MUL;
            knee[k] -= amt;
            boolean ok = super.damage(d.times(KNEE_MUL));
            if (knee[k] <= 0 && legs.part[k] != null && !legs.part[k].look.on.contains("broke")) {
                legs.part[k].look.on.add("broke");
                sound("kneel", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8f, 64);
            }
            if (!dead && knee[0] <= 0 && knee[1] <= 0) startKneel();
            return ok;
        }
        if (best == 9 && phase == Phase.KNEEL) return super.damage(d.times(BACK_MUL));
        return super.damage(d);
    }

    // ------------------------------------------------------------------ 죽음: 넘어지는 쪽 띠

    @Override
    public void die(Damage d) {
        Vector dir = d != null && d.dir() != null ? d.dir().clone().setY(0) : null;
        if (dir == null || dir.lengthSquared() < 1e-4) dir = G129Kit.fwd(this);
        dir.normalize();
        Vector from = G129Kit.ground(this, pos);
        Vector to = from.clone().add(dir.clone().multiply(FALL_LEN));
        G129Kit.strip(this, from, to, FALL_W, 16, true);
        final Vector fd = dir;
        final long start = now();
        final G129 self = this;
        game.mobs.field(new Object(), new Mobs.Field() {
            @Override
            public boolean tick(long now) {
                if (now - start < 12) return true;
                for (Player q : game.mobs.standing()) {
                    Location l = q.getLocation();
                    double rx = l.getX() - from.getX(), rz = l.getZ() - from.getZ();
                    double along = rx * fd.getX() + rz * fd.getZ(), lat = Math.abs(rx * fd.getZ() - rz * fd.getX());
                    if (along >= -1 && along <= FALL_LEN && lat <= FALL_W / 2 + 0.4 && Math.abs(l.getY() - from.getY()) < 4)
                        self.hit(q, FALL_DMG, "cause_g129_fall");
                }
                G129Kit.stomp(self, to, 96, 20, 1f);
                G129Kit.soundAt(self, to, "fall", Sound.ENTITY_GENERIC_EXPLODE, 1f, 96);
                G129Kit.wave(self, to, 1.0, 7, 7);
                if (game.horizon != null) game.horizon.shake(0.6, 16);
                return false;
            }
        });
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " knee=" + Math.round(knee[0]) + "/" + Math.round(knee[1]) + " steps=" + (legs == null ? 0 : legs.steps)
            + " lines=" + lines + " dir=" + String.format("%.2f,%.2f", lineDir.getX(), lineDir.getZ());
    }
}
