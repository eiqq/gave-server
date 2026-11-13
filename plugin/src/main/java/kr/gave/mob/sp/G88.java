package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G88 백눈 호저 게이브(원거리, 방패). 등 가시 끝 얼굴 넷이 따로따로 사람을 보며 바늘을 쏘는 호저.
 *
 * <p>도감: 종종걸음으로 와서 18블록쯤에 다리를 굽히고 등 가시를 세운다. 늘 가시 얼굴 넷이 깨어 있고(눈 뜸), 깨어 있는 얼굴마다 다른 사람을 따로
 * 노려 1.5초에 한 번 곧은 바늘을 쏜다(6). 6초마다 모든 눈이 1.5초 동시에 감긴다(그동안 안 쏨). 6블록 안에 사람이 오면 몸을 말아 굴러 부딪힌다(9).
 * 가시 얼굴은 하나에 체력 10, 꺾으면 그 가시는 끝(쏘는 수가 줆). 폭발은 곁의 가시 여럿을 한 번에 꺾는다.
 *
 * <p>팩 계약(tools/gabe/sp/g88.py): 모델 gave:gabe/g88(상태 "", "ball", 부위 eye1..eye12, dead1..dead12), 부품 gave:gabe/g88_needle. 가시 자리는
 * 팩의 SPINES 표와 같은 식. 소리 needle, blink, bump, snap. 원인 글귀 cause_g88(바늘), cause_g88_roll(굴러 받힘).
 */
public final class G88 extends Gabe {

    static final double SPEED = 0.16, HOVER = 0.95, SET_RANGE = 18, LOSE_RANGE = 24, ROLL_RANGE = 6;
    static final int AWAKE = 4, SHOT_CD = 30, BLINK_EVERY = 120, BLINK_T = 30, ROLL_MAX = 50, ROLL_CD = 70;
    static final double NEEDLE = 6, ROLL = 9, NEEDLE_V = 0.8, NEEDLE_RANGE = 30, NEEDLE_TOUCH = 0.5, SPINE_HP = 10, TIP_R = 0.22;
    static final double ROLL_V = 0.34;
    /** 가시: 팩 g88.py SPINES 와 같은 식(몸 기준 +X 왼쪽). */
    static final int N = 12;
    static final double SPINE_LEN = 1.1, TIP_UP = 0.09;
    static final double[][] TIP = new double[N][3];

    static {
        for (int i = 0; i < N; i++) {
            double th = Math.toRadians(-78 + 156.0 * i / 11);
            double rx = Math.sin(th) * 0.42, ry = 0.52 + Math.cos(th) * 0.06, rz = -0.35;
            double dx = Math.sin(th), dy = Math.cos(th), dz = -0.3, n = Math.sqrt(dx * dx + dy * dy + dz * dz);
            TIP[i][0] = rx + dx / n * SPINE_LEN;
            TIP[i][1] = ry + dy / n * SPINE_LEN + TIP_UP;
            TIP[i][2] = rz + dz / n * SPINE_LEN + 0.02;
        }
    }

    enum Phase { APPROACH, SET, BALL }

    private final class Needle {
        Body.Part p;
        final Vector at = new Vector(), v = new Vector();
        double gone;
    }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, blinkAt, blinkEnd = -1, rollReady, lostAt = -1;
    private final double[] spineHp = new double[N];
    private final boolean[] awake = new boolean[N];
    private final int[] nextShot = new int[N];
    private final Player[] aim = new Player[N];
    private final List<Needle> needles = new ArrayList<>();
    private Player prey;
    private float spin;
    private boolean scaled;

    public G88(Game game, Species sp) {
        super(game, sp);
        radius = 0.75;
        hover = HOVER;
        baseSpeed = SPEED;
        ragKick = 0.7;
        for (int i = 0; i < N; i++) spineHp[i] = SPINE_HP;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g88", 2.8f, 2.8f);
    }

    @Override
    protected void spawned() {
        blinkAt = age + cd(BLINK_EVERY) + game.random.nextInt(40);
        wake();
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 가시 얼굴

    /** 살아 있는 가시 가운데 넷을 깨움(나머지는 실눈). */
    private void wake() {
        for (int i = 0; i < N; i++) {
            awake[i] = false;
            body.look.on.remove("eye" + (i + 1));
        }
        int alive = 0;
        for (int i = 0; i < N; i++) if (spineHp[i] > 0) alive++;
        int want = Math.min(AWAKE, alive);
        int tries = 0;
        while (want > 0 && tries++ < 60) {
            int i = game.random.nextInt(N);
            if (spineHp[i] <= 0 || awake[i]) continue;
            awake[i] = true;
            body.look.on.add("eye" + (i + 1));
            nextShot[i] = age + cd(SHOT_CD) / 2 + game.random.nextInt(cd(SHOT_CD));
            want--;
        }
    }

    private void wakeOne() {
        for (int t = 0; t < 30; t++) {
            int i = game.random.nextInt(N);
            if (spineHp[i] <= 0 || awake[i]) continue;
            awake[i] = true;
            body.look.on.add("eye" + (i + 1));
            nextShot[i] = age + cd(SHOT_CD);
            return;
        }
    }

    private boolean blinking() {
        return blinkEnd >= 0 && age < blinkEnd;
    }

    /** 가시 얼굴 i 의 세계 자리. */
    private Vector tip(int i) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r), lx = fz, lz = -fx;
        double sq = body.stretch.y;
        double x = TIP[i][0], y = TIP[i][1] * sq, z = TIP[i][2];
        return new Vector(pos.getX() + lx * x + fx * z, pos.getY() + y, pos.getZ() + lz * x + fz * z);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!scaled) {
            scaled = true;
            double k = sp.hp > 0 ? maxHp / sp.hp : 1;
            for (int i = 0; i < N; i++) spineHp[i] *= k;
        }
        needleTick();
        if (blinkEnd >= 0 && age >= blinkEnd) {
            blinkEnd = -1;
            blinkAt = age + cd(BLINK_EVERY);
            wake();
        } else if (blinkEnd < 0 && age >= blinkAt && phase != Phase.BALL) {
            // 모든 눈이 한꺼번에 감김
            blinkEnd = age + BLINK_T;
            for (int i = 0; i < N; i++) body.look.on.remove("eye" + (i + 1));
            sound("blink", Sound.ENTITY_PHANTOM_FLAP, 1.6f, 20);
        }
        switch (phase) {
            case APPROACH -> approach();
            case SET -> set();
            case BALL -> ball();
        }
    }

    private void approach() {
        follow(speed());
        Player p = nearest(400);
        if (p == null) {
            if (vel.lengthSquared() > 1e-6) face(pos.clone().add(vel));
            pitch = 0;
            return;
        }
        if (vel.lengthSquared() > 1e-6 && dist(p) > 6) face(pos.clone().add(vel));
        else face(chest(p));
        pitch = 0;
        double d = dist(p);
        if (d <= ROLL_RANGE && age >= rollReady) {
            roll(p);
            return;
        }
        if (d <= SET_RANGE && sees(p)) {
            prey = p;
            mark(p);
            hold();
            body.squash(1.05f, 0.86f, 1.0f, 6);
            body.look.freeze = true;
            lostAt = -1;
            phase(Phase.SET);
        }
    }

    /** 다리를 굽히고 가시를 세워 깨어 있는 얼굴마다 따로 쏨. */
    private void set() {
        hold();
        Player p = nearest(400);
        if (p == null || dist(p) > LOSE_RANGE) {
            unset();
            return;
        }
        if (p != prey) {
            prey = p;
            mark(p);
        }
        turnTo(chest(p), 8f);
        pitch = 0;
        if (dist(p) <= ROLL_RANGE && age >= rollReady) {
            roll(p);
            return;
        }
        if (!sees(p)) {
            if (lostAt < 0) lostAt = age;
            else if (age - lostAt > 40) {
                unset();
                return;
            }
        } else lostAt = -1;
        if (blinking()) return;
        if (every(10)) assign();
        for (int i = 0; i < N; i++) {
            if (!awake[i] || spineHp[i] <= 0 || age < nextShot[i]) continue;
            Player q = aim[i];
            if (!standing(q)) continue;
            Vector from = tip(i);
            Vector to = chest(q);
            if (from.distanceSquared(to) > NEEDLE_RANGE * NEEDLE_RANGE || !game.mobs.clearLine(from, to)) {
                nextShot[i] = age + 5;
                continue;
            }
            shoot(from, to);
            nextShot[i] = age + cd(SHOT_CD);
        }
    }

    /** 깨어 있는 얼굴마다 서로 다른 사람(가까운 차례로 나눔, 사람이 적으면 겹침). */
    private void assign() {
        List<Player> ps = new ArrayList<>(8);
        for (Player q : game.mobs.standing()) {
            if (q.getWorld() != game.world) continue;
            double d2 = q.getLocation().toVector().distanceSquared(pos);
            if (d2 > NEEDLE_RANGE * NEEDLE_RANGE) continue;
            ps.add(q);
        }
        if (ps.isEmpty()) {
            for (int i = 0; i < N; i++) aim[i] = null;
            return;
        }
        ps.sort((a, b) -> Double.compare(a.getLocation().toVector().distanceSquared(pos), b.getLocation().toVector().distanceSquared(pos)));
        int k = 0;
        for (int i = 0; i < N; i++) {
            if (!awake[i]) {
                aim[i] = null;
                continue;
            }
            aim[i] = ps.get(k % ps.size());
            k++;
        }
    }

    private void unset() {
        body.squash(1f, 1f, 1f, 6);
        body.look.freeze = false;
        mark(null);
        prey = null;
        phase(Phase.APPROACH);
    }

    private void roll(Player p) {
        prey = p;
        mark(p);
        body.squash(1f, 1f, 1f, 2);
        body.look.freeze = false;
        state("ball");
        hover = 0.75;
        sound("bump", Sound.ENTITY_ARMADILLO_ROLL, 1.0f, 20);
        phase(Phase.BALL);
    }

    /** 몸을 말아 굴러 부딪힘. */
    private void ball() {
        if (!standing(prey)) {
            Player q = nearest(400);
            if (q == null) {
                unroll();
                return;
            }
            prey = q;
            mark(q);
        }
        double v = speed() * ROLL_V / SPEED;
        seek(prey.getLocation().toVector(), v);
        face(chest(prey));
        spin = (float) ((spin + Math.toDegrees(v / 0.75)) % 360f);
        pitch = spin;
        for (Player q : game.mobs.standing()) {
            if (!touches(q, 0.15)) continue;
            hit(q, ROLL, "cause_g88_roll");
            knock(q, toward(q), 0.7, 0.3);
            sound("bump", Sound.BLOCK_WOOD_HIT, 0.8f, 20);
            unroll();
            return;
        }
        if (inPhase() >= ROLL_MAX || wallAhead(0.9)) unroll();
    }

    private void unroll() {
        state("");
        pitch = 0;
        hover = HOVER;
        hold();
        rollReady = age + cd(ROLL_CD);
        mark(null);
        prey = null;
        phase(Phase.APPROACH);
    }

    // ------------------------------------------------------------------ 바늘

    private void shoot(Vector from, Vector to) {
        Needle n = new Needle();
        n.p = body.attach("gabe/g88_needle");
        if (n.p == null) return;
        n.p.follow = false;
        n.at.copy(from);
        Vector d = to.clone().subtract(from);
        if (d.lengthSquared() < 1e-6) d = new Vector(0, 0, 1);
        n.v.copy(d.normalize().multiply(NEEDLE_V));
        needles.add(n);
        poseNeedle(n, 0);
        soundAt(from, "needle", Sound.ENTITY_ARROW_SHOOT, 1.6f, 24);
    }

    private void needleTick() {
        for (int i = needles.size() - 1; i >= 0; i--) {
            Needle n = needles.get(i);
            Vector prev = n.at.clone();
            n.at.add(n.v);
            n.gone += NEEDLE_V;
            boolean done = false;
            for (Player p : game.mobs.standing()) {
                Location l = p.getLocation();
                double best = Double.MAX_VALUE;
                for (double h = 0.3; h <= 1.7; h += 0.35) best = Math.min(best, G41.segDist(new Vector(l.getX(), l.getY() + h, l.getZ()), prev, n.at));
                if (best > NEEDLE_TOUCH) continue;
                hit(p, NEEDLE, "cause_g88");
                done = true;
                break;
            }
            if (!done) {
                double fl = game.mobs.flow.floor(n.at.getX(), n.at.getZ());
                done = n.at.getY() <= fl + 0.05 || n.gone >= NEEDLE_RANGE || !game.mobs.clearLine(prev, n.at);
            }
            if (done) {
                n.p.remove();
                needles.remove(i);
            } else poseNeedle(n, 1);
        }
    }

    private void poseNeedle(Needle n, int ticks) {
        float ty = (float) Math.atan2(-n.v.getX(), n.v.getZ());
        float tp = (float) -Math.atan2(n.v.getY(), Math.hypot(n.v.getX(), n.v.getZ()));
        n.p.pose(new Matrix4f().translate((float) (n.at.getX() - pos.getX()), (float) (n.at.getY() - pos.getY()), (float) (n.at.getZ() - pos.getZ()))
                .rotateY(-ty).rotateX(tp), ticks);
    }

    private void clearNeedles() {
        for (Needle n : needles) if (n.p != null) n.p.remove();
        needles.clear();
    }

    private void soundAt(Vector at, String name, Sound fb, float fbPitch, double range) {
        if (game.director.silent()) return;
        Props.soundAround(new Location(game.world, at.getX(), at.getY(), at.getZ()), "gabe." + sp.key() + "." + name, fb, 1f,
                0.94f + game.random.nextFloat() * 0.12f, fbPitch, range, -1);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(N + 2);
        if (phase == Phase.BALL) {
            out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.85});
            return out;
        }
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
        out.add(new double[] {pos.getX() + fx * 0.55, pos.getY(), pos.getZ() + fz * 0.55, 0.62});
        out.add(new double[] {pos.getX() - fx * 0.4, pos.getY() + 0.05, pos.getZ() - fz * 0.4, 0.62});
        for (int i = 0; i < N; i++) {
            if (spineHp[i] <= 0) continue;
            Vector t = tip(i);
            out.add(new double[] {t.getX(), t.getY(), t.getZ(), TIP_R});
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (phase != Phase.BALL && d.at() != null) {
            if (t == DamageType.EXPLOSION) {
                // 폭발은 곁의 가시 여럿을 한 번에
                for (int i = 0; i < N; i++) {
                    if (spineHp[i] <= 0) continue;
                    if (tip(i).distanceSquared(d.at()) <= 2.6 * 2.6) hurtSpine(i, d.amount());
                }
            } else if (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER) {
                double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
                double bx = pos.getX() + fx * 0.1, bz = pos.getZ() + fz * 0.1;
                double body2 = Math.min(d.at().distanceSquared(new Vector(bx, pos.getY(), bz)), 1e9);
                int best = -1;
                double bd = Double.MAX_VALUE;
                for (int i = 0; i < N; i++) {
                    if (spineHp[i] <= 0) continue;
                    double ds = tip(i).distanceSquared(d.at());
                    if (ds < bd) {
                        bd = ds;
                        best = i;
                    }
                }
                if (best >= 0 && bd < body2 && bd <= (TIP_R + 0.4) * (TIP_R + 0.4)) {
                    hurtSpine(best, d.amount());
                    game.mobs.onHurt(this, d, 0);
                    if (d.by() != null) game.hud.hit(d.by(), false);
                    return true;
                }
            }
        }
        return super.damage(d);
    }

    private void hurtSpine(int i, double amount) {
        spineHp[i] -= amount;
        if (spineHp[i] > 0) return;
        body.look.on.add("dead" + (i + 1));
        body.look.on.remove("eye" + (i + 1));
        sound("snap", Sound.ENTITY_ITEM_BREAK, 1.2f, 20);
        if (awake[i]) {
            awake[i] = false;
            aim[i] = null;
            if (!blinking()) wakeOne();
        }
    }

    public int spinesLeft() {
        int n = 0;
        for (int i = 0; i < N; i++) if (spineHp[i] > 0) n++;
        return n;
    }

    @Override
    public void die(Damage d) {
        clearNeedles();
        super.die(d);
    }

    @Override
    public String toString() {
        int aw = 0;
        for (int i = 0; i < N; i++) if (awake[i]) aw++;
        return super.toString() + " ph=" + phase + " spines=" + spinesLeft() + " awake=" + aw + (blinking() ? " blink" : "") + " needles=" + needles.size();
    }
}
