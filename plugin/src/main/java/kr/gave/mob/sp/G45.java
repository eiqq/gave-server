package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;
import kr.gave.mob.Swarm;
import kr.gave.player.PState;
import kr.gave.world.Plaza;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * G45 줄지어 나는 게이브(떼, 원거리, 하늘).
 *
 * <p>도감: 얼굴 여섯이 쇠사슬과 살 띠로 이어진 행렬. 하늘(바닥 위 11블록)에서 S 자로 굽이치며 사람들 둘레를 돌다가, 낮게 내려와 무리를
 * 가로질러 훑고 다시 오른다. 내려올 길에는 길쭉한 바닥 그림자가 먼저 지나간다. 훑을 때 닿으면 4 피해와 옆으로 밀침, 처음 걸린 한 명은
 * 꼬리에 4초 매달린다(매달린 마디는 짙은 홍조). 그 사이 아무 마디나 끊으면 떨어지고, 못 구하면 갇히며 행렬이 한 마디 길어진다. 여섯
 * 마디를 넘으면 꼬리 셋이 떨어져 나가 새 행렬이 된다. 아무 마디나 쏴 죽이면 거기서 끊겨 둘이 되고 3마디 미만 조각은 떨어져 죽는다.
 * 보상: 마디당 2원(끊은 사람에게 바로), 행렬을 다 끊으면 합계만큼 한 번 더.
 *
 * <p>몸: 무리 닻(빈 몸, 자리 = 맨 앞 얼굴) + 마리 {@code gave:gabe/g45}(상태 {@code f0 .. f5} 표정 점층, 부위 {@code tail}) + 부품
 * {@code gave:gabe/g45_shadow}(바닥 그림자, 세계 축, 부위 {@code show}). 머리가 지나간 길을 기록하고 마디는 그 길 위 SPACING 간격 자리를
 * 따라간다. 소리 flap(48), hook(32), snap(32). 글귀 {@code cause_g45}, {@code notice_g45_hook}.
 *
 * <p>도감과 다른 점: 매달린 사람은 엔진 붙잡기(Players.grab + carry)라 그동안 쏘지 못한다(대신 좌클릭 연타로 스스로 떨어짐).
 * 구조물 없는 황야(2026-11-11 맵 변경)에 맞춰 지붕, 벽을 보지 않는다.
 */
public final class G45 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int N = 6, MAX = 6;
    /** 마디 사이 거리(팩 g45.py GAP 과 같게). */
    static final double SPACING = 1.15;
    static final double SPEED = 0.30;
    /** 도는 높이(바닥 위), 도는 반지름, 훑는 높이(사람 몸통), 훑는 길 반 길이. */
    static final double ALT = 11, ORBIT_R = 22, LOW = 1.25, SWEEP_HALF = 10;
    static final double DIVE_MUL = 1.25, SWEEP_MUL = 1.5;
    /** 처음 내려오기까지, 다시 내려오기까지(틱). */
    static final int FIRST = 60, COOLDOWN = 160;
    static final double DAMAGE = 15, PUSH = 0.7, PUSH_UP = 0.3;
    /** 매달린 틱(4초), 스스로 떨어지는 좌클릭 수, 꼬리 아래 매달리는 높이(좌석). */
    static final int HOOK_T = 80, ESCAPE = 14;
    static final double HANG = 1.2;
    static final int SEG_REWARD = 2;
    static final double SEG_R = 0.36, TOUCH = 0.85;
    /** 그림자: 폭, 길이, 머리보다 앞서는 거리. */
    static final float SHADOW_W = 1.8f, SHADOW_L = 7f;
    static final double SHADOW_LEAD = 8;
    static final TextColor TAG_GRAY = TextColor.color(0x8c8c8c);

    enum Phase { CRUISE, DIVE, SWEEP, CLIMB }

    /** 마디 하나. */
    private static final class Seg {
        final Swarm.Member m;
        final int face;
        double hp;
        boolean gone;

        Seg(Swarm.Member m, int face, double hp) {
            this.m = m;
            this.face = face;
            this.hp = hp;
        }
    }

    /** 끊겨 생긴 조각들이 함께 쓰는 것(행렬을 다 끊었는지, 갇힌 사람 넘기기). */
    private static final class Family {
        final List<G45> alive = new ArrayList<>();
        int total;
    }

    /** 조각으로 태어날 때 받는 것(머리부터 꼬리까지 자리, 표정, 체력). */
    private static final class Seed {
        double[] x, y, z, hp;
        int[] face;
        double each;
        Family family;
    }

    private Swarm swarm;
    private final List<Seg> chain = new ArrayList<>();
    private final Seed seed;
    private Family family;
    private Body.Part shadow;
    private TextDisplay tag;
    private String tagText = "";
    private boolean init;
    private double each = 1;

    private Phase phase = Phase.CRUISE;
    private int phaseAt, readyAt, nextFlap;
    private int orbit = 1;
    /** 머리 방향(단위 벡터)과 이번 틱 빠르기. */
    private final Vector hd = new Vector(0, 0, 1);
    private double v;
    /** 훑는 길: 낮은 길 시작점(x, z)과 방향. */
    private double ax, az, sdx, sdz = 1;
    private final Set<UUID> passHit = new HashSet<>();
    private Player hooked;
    private int hookAt;

    // 머리가 지나간 길(고리 버퍼, 최신이 top)
    private static final int CAP = 192;
    private final double[] hx = new double[CAP], hy = new double[CAP], hz = new double[CAP];
    private int top = -1, hn;
    // 마디 세계 자리(계산용)
    private final double[] wx = new double[MAX + 2], wy = new double[MAX + 2], wz = new double[MAX + 2];
    private final Vector tmp = new Vector(), seat = new Vector();

    public G45(Game game, Species sp) {
        this(game, sp, null);
    }

    private G45(Game game, Species sp, Seed seed) {
        super(game, sp);
        this.seed = seed;
        radius = 0.5;
        noPush = true;
        baseSpeed = SPEED;
        flying = true;
        hover = ALT;
    }

    // ------------------------------------------------------------------ 만들기

    @Override
    protected void build() {
        int n = seed == null ? N : seed.x.length;
        swarm = game.mobs.swarm(this, "gabe/g45", n);
        swarm.memberRadius = SEG_R;
        // 하늘 종은 늘 멀리서 보므로 덩어리(LOD)를 쓰지 않는다
        swarm.lodDist = 1000;
        if (swarm.lod != null) {
            swarm.lod.remove();
            swarm.lod = null;
        }
        orbit = game.random.nextBoolean() ? 1 : -1;
        if (seed == null) {
            family = new Family();
            family.total = N;
            // 광장 가운데를 보고 나옴
            double dx = Plaza.CX + 0.5 - pos.getX(), dz = Plaza.CZ + 0.5 - pos.getZ(), d = Math.hypot(dx, dz);
            if (d < 1e-3) {
                dx = 0;
                dz = 1;
                d = 1;
            }
            hd.setX(dx / d).setY(0).setZ(dz / d);
            for (int i = 0; i < n; i++) {
                wx[i] = pos.getX() - hd.getX() * SPACING * i;
                wy[i] = pos.getY();
                wz[i] = pos.getZ() - hd.getZ() * SPACING * i;
            }
        } else {
            family = seed.family;
            each = seed.each;
            init = true;
            for (int i = 0; i < n; i++) {
                wx[i] = seed.x[i];
                wy[i] = seed.y[i];
                wz[i] = seed.z[i];
            }
            if (n >= 2) {
                tmp.setX(wx[0] - wx[1]).setY(wy[0] - wy[1]).setZ(wz[0] - wz[1]);
                if (tmp.lengthSquared() > 1e-6) hd.copy(tmp.normalize());
            }
        }
        family.alive.add(this);
        for (int i = 0; i < n; i++) {
            Swarm.Member m = swarm.members.get(i);
            int face = seed == null ? i : seed.face[i];
            Seg s = new Seg(m, face, seed == null ? 1 : seed.hp[i]);
            m.state("f" + face);
            m.look.phase = body.look.phase + i * 4;
            chain.add(s);
        }
        seedPath(n);
        retail();
        place(0);
        shadow = body.attach("gabe/g45_shadow");
        if (shadow != null) {
            shadow.follow = false;
            // 그림자는 밤 바닥보다도 어둡게(빛 0)
            if (shadow.d != null) shadow.d.setBrightness(new Display.Brightness(0, 0));
        }
        if (seed != null) sumHp();
    }

    @Override
    protected void spawned() {
        nextFlap = age + game.random.nextInt(30);
        readyAt = age + (seed == null ? cd(FIRST) : cd(COOLDOWN));
    }

    /** 감독이 넣은 maxHp 를 마디 하나 체력으로 읽고 행렬 합계로 바꾼다(떼 종 약속). */
    private void init() {
        init = true;
        each = Math.max(1, maxHp);
        for (Seg s : chain) s.hp = each;
        sumHp();
    }

    private void sumHp() {
        double h = 0;
        int n = 0;
        for (Seg s : chain) {
            if (s.gone) continue;
            h += Math.max(0, s.hp);
            n++;
        }
        maxHp = each * Math.max(1, n);
        hp = h;
        reward = noReward ? 0 : SEG_REWARD * n;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private Seg tail() {
        return chain.isEmpty() ? null : chain.get(chain.size() - 1);
    }

    /** 맨 끝 마디만 부위 tail(뒤로 가는 이음줄 없음). */
    private void retail() {
        for (int i = 0; i < chain.size(); i++) {
            Seg s = chain.get(i);
            if (i == chain.size() - 1) s.m.look.on.add("tail");
            else s.m.look.on.remove("tail");
        }
    }

    // ------------------------------------------------------------------ 길

    private void push(double x, double y, double z) {
        top = (top + 1) % CAP;
        hx[top] = x;
        hy[top] = y;
        hz[top] = z;
        if (hn < CAP) hn++;
    }

    /** wx/wy/wz[0..n-1](머리부터 꼬리) 자리로 길을 깐다(꼬리 쪽이 오래된 길). */
    private void seedPath(int n) {
        top = -1;
        hn = 0;
        if (n >= 2) {
            // 꼬리 뒤로 한 마디 더
            double ex = wx[n - 1] * 2 - wx[n - 2], ey = wy[n - 1] * 2 - wy[n - 2], ez = wz[n - 1] * 2 - wz[n - 2];
            line(ex, ey, ez, wx[n - 1], wy[n - 1], wz[n - 1]);
        }
        for (int i = n - 1; i >= 1; i--) line(wx[i], wy[i], wz[i], wx[i - 1], wy[i - 1], wz[i - 1]);
        push(wx[0], wy[0], wz[0]);
    }

    private void line(double x0, double y0, double z0, double x1, double y1, double z1) {
        int steps = 8;
        for (int k = 0; k < steps; k++) {
            double u = (double) k / steps;
            push(x0 + (x1 - x0) * u, y0 + (y1 - y0) * u, z0 + (z1 - z0) * u);
        }
    }

    private void record() {
        if (hn > 0) {
            double dx = pos.getX() - hx[top], dy = pos.getY() - hy[top], dz = pos.getZ() - hz[top];
            if (dx * dx + dy * dy + dz * dz < 0.0009) return;
        }
        push(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 길 위 마디 자리(머리에서 i x SPACING 뒤)를 wx/wy/wz 에. */
    private void sample() {
        double px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        double acc = 0;
        int k = 0;
        double bxDir = -hd.getX(), byDir = -hd.getY(), bzDir = -hd.getZ();
        int n = chain.size();
        for (int i = 0; i < n; i++) {
            double want = i * SPACING;
            boolean done = want <= acc;
            while (!done && k < hn) {
                int idx = ((top - k) % CAP + CAP) % CAP;
                double qx = hx[idx], qy = hy[idx], qz = hz[idx];
                double dx = qx - px, dy = qy - py, dz = qz - pz;
                double seg = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (seg > 1e-6) {
                    bxDir = dx / seg;
                    byDir = dy / seg;
                    bzDir = dz / seg;
                }
                if (acc + seg >= want) {
                    double u = seg < 1e-9 ? 0 : (want - acc) / seg;
                    px += dx * u;
                    py += dy * u;
                    pz += dz * u;
                    acc = want;
                    done = true;
                } else {
                    acc += seg;
                    px = qx;
                    py = qy;
                    pz = qz;
                    k++;
                }
            }
            if (done) {
                wx[i] = px;
                wy[i] = py;
                wz[i] = pz;
            } else {
                // 길이 모자라면 마지막 방향으로 곧게 잇는다
                double rest = want - acc;
                wx[i] = px + bxDir * rest;
                wy[i] = py + byDir * rest;
                wz[i] = pz + bzDir * rest;
            }
        }
    }

    /** 마디를 길 위 자리로(ticks 보간), 얼굴은 앞 마디를 본다. */
    private void place(int ticks) {
        sample();
        int n = chain.size();
        for (int i = 0; i < n; i++) {
            Swarm.Member m = chain.get(i).m;
            if (m.dead) continue;
            tmp.setX(wx[i] - pos.getX()).setY(wy[i] - pos.getY()).setZ(wz[i] - pos.getZ());
            m.moveTo(tmp, ticks);
            double fx, fy, fz;
            if (i == 0) {
                fx = hd.getX();
                fy = hd.getY();
                fz = hd.getZ();
            } else {
                fx = wx[i - 1] - wx[i];
                fy = wy[i - 1] - wy[i];
                fz = wz[i - 1] - wz[i];
            }
            double fl = Math.hypot(fx, fz);
            if (fl < 1e-6 && Math.abs(fy) < 1e-6) continue;
            float yw = (float) Math.toDegrees(Math.atan2(-fx, fz));
            float pt = (float) Math.toDegrees(-Math.atan2(fy, fl));
            m.face(yw, pt, 0, ticks);
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        if (chain.isEmpty()) {
            remove();
            return;
        }
        record();
        v = speed();
        switch (phase) {
            case CRUISE -> cruise();
            case DIVE -> dive();
            case SWEEP -> sweep();
            case CLIMB -> climb();
        }
        vel.setX(hd.getX() * v).setY(hd.getY() * v).setZ(hd.getZ() * v);
        yaw = (float) Math.toDegrees(Math.atan2(-hd.getX(), hd.getZ()));
        pitch = 0;
        if (every(2)) {
            place(2);
            shadowPose(2);
            tagPose(2);
        }
        hookTick();
        if (phase != Phase.CRUISE) sweepHits();
        if (age >= nextFlap) {
            sound("flap", Sound.ENTITY_PHANTOM_FLAP, 0.8f, 48);
            nextFlap = age + (phase == Phase.CRUISE ? 26 + game.random.nextInt(14) : 9);
        }
    }

    /** 머리 방향을 (x, y, z) 쪽으로 k 만큼 꺾는다. */
    private void turn(double x, double y, double z, double k) {
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-9) return;
        hd.setX(hd.getX() + (x / l - hd.getX()) * k).setY(hd.getY() + (y / l - hd.getY()) * k).setZ(hd.getZ() + (z / l - hd.getZ()) * k);
        double hl = hd.length();
        if (hl < 1e-6) hd.setX(x / l).setY(y / l).setZ(z / l);
        else hd.multiply(1 / hl);
    }

    /** 서 있는 사람들의 가운데(없으면 광장 가운데). */
    private void crowd(Vector out) {
        List<Player> ps = game.mobs.standing();
        double x = 0, z = 0;
        int n = 0;
        for (int i = 0; i < ps.size(); i++) {
            Location l = ps.get(i).getLocation();
            x += l.getX();
            z += l.getZ();
            n++;
        }
        if (n == 0) out.setX(Plaza.CX + 0.5).setY(0).setZ(Plaza.CZ + 0.5);
        else out.setX(x / n).setY(0).setZ(z / n);
    }

    /** 하늘에서 S 자로 굽이치며 사람들 둘레를 돈다. 준비되면 내려갈 길을 잡는다. */
    private void cruise() {
        crowd(tmp);
        double dx = pos.getX() - tmp.getX(), dz = pos.getZ() - tmp.getZ(), d = Math.hypot(dx, dz);
        if (d < 1e-3) {
            dx = 1;
            dz = 0;
            d = 1;
        }
        double nx = dx / d, nz = dz / d;
        double radial = Math.max(-1, Math.min(1, (ORBIT_R - d) * 0.12));
        double wxv = -nz * orbit + nx * radial, wzv = nx * orbit + nz * radial;
        double a = 0.55 * Math.sin((age + id * 17) * 0.06), ca = Math.cos(a), sa = Math.sin(a);
        double rx = wxv * ca - wzv * sa, rz = wxv * sa + wzv * ca;
        double ty = floor() + ALT + 1.2 * Math.sin((age + id * 7) * 0.045);
        double wyv = Math.max(-0.6, Math.min(0.6, (ty - pos.getY()) * 0.1));
        turn(rx, wyv, rz, 0.12);
        if (age >= readyAt && hooked == null && every(5)) tryDive();
    }

    /** 가장 가까운 사람 둘레 소집단을 가로지르는 낮은 길을 잡는다(머리에서 12~40 블록일 때만). */
    private void tryDive() {
        Player p = game.mobs.nearestPlayer(pos, 80);
        if (p == null) return;
        Location lp = p.getLocation();
        double cx = 0, cz = 0;
        int n = 0;
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Location l = ps.get(i).getLocation();
            if (Math.hypot(l.getX() - lp.getX(), l.getZ() - lp.getZ()) <= 5) {
                cx += l.getX();
                cz += l.getZ();
                n++;
            }
        }
        if (n == 0) {
            cx = lp.getX();
            cz = lp.getZ();
            n = 1;
        }
        cx /= n;
        cz /= n;
        double dx = cx - pos.getX(), dz = cz - pos.getZ(), d = Math.hypot(dx, dz);
        if (d < 12 || d > 40) return;
        sdx = dx / d;
        sdz = dz / d;
        ax = cx - sdx * SWEEP_HALF;
        az = cz - sdz * SWEEP_HALF;
        passHit.clear();
        mark(p);
        eyes(true);
        if (shadow != null) shadow.look.on.add("show");
        phase(Phase.DIVE);
        shadowPose(0);
        nextFlap = age;
    }

    private double along() {
        return (pos.getX() - ax) * sdx + (pos.getZ() - az) * sdz;
    }

    /** 낮은 길 시작점으로 내리꽂는다(그림자가 먼저 지나감). */
    private void dive() {
        double ty = game.mobs.flow.floor(ax, az) + LOW;
        double dx = ax - pos.getX(), dy = ty - pos.getY(), dz = az - pos.getZ();
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1.5 || along() > 0 || inPhase() > 120) {
            phase(Phase.SWEEP);
            return;
        }
        turn(dx, dy, dz, 0.2);
        v = speed() * DIVE_MUL;
    }

    /** 사람 몸통 높이로 무리를 가로질러 훑는다. */
    private void sweep() {
        double wyv = Math.max(-0.5, Math.min(0.5, (floor() + LOW - pos.getY()) * 0.35));
        turn(sdx, wyv, sdz, 0.4);
        v = speed() * SWEEP_MUL;
        if (along() >= SWEEP_HALF * 2 || inPhase() > 80) {
            if (shadow != null) shadow.look.on.remove("show");
            phase(Phase.CLIMB);
        }
    }

    /** 다시 오른다(꼬리가 아직 낮으면 꼬리에도 맞는다). */
    private void climb() {
        turn(sdx, 0.75, sdz, 0.15);
        v = speed() * 1.1;
        if (pos.getY() >= floor() + ALT - 1.5 || inPhase() > 100) {
            readyAt = age + cd(COOLDOWN);
            if (hooked == null) {
                mark(null);
                eyes(false);
            }
            phase(Phase.CRUISE);
        }
    }

    /** 맨 앞 얼굴의 실눈(찍으면 뜸). */
    private void eyes(boolean open) {
        if (!chain.isEmpty()) chain.get(0).m.look.open = open;
    }

    /** 그림자: 낮은 길 위, 머리보다 SHADOW_LEAD 앞(길 양 끝에서 멈춤). */
    private void shadowPose(int ticks) {
        if (shadow == null || (phase != Phase.DIVE && phase != Phase.SWEEP)) return;
        double s = Math.max(0, Math.min(SWEEP_HALF * 2, along() + SHADOW_LEAD));
        double x = ax + sdx * s, z = az + sdz * s;
        float dy = (float) (game.mobs.flow.floor(x, z) + 0.05 - pos.getY());
        shadow.pose(new Matrix4f().translate((float) (x - pos.getX()), dy, (float) (z - pos.getZ())).rotateY((float) Math.atan2(sdx, sdz))
                .scale(SHADOW_W, 1f, SHADOW_L), ticks);
    }

    // ------------------------------------------------------------------ 훑기, 낚기

    /** 낮게 지나가는 마디에 닿은 사람: 4 피해와 옆으로 밀침(한 번 지나갈 때 사람마다 한 번), 처음 걸린 한 명은 꼬리에 매단다. */
    private void sweepHits() {
        List<Player> ps = game.mobs.standing();
        if (ps.isEmpty()) return;
        int n = chain.size();
        for (int i = 0; i < n; i++) {
            Vector o = chain.get(i).m.off();
            wx[i] = pos.getX() + o.getX();
            wy[i] = pos.getY() + o.getY();
            wz[i] = pos.getZ() + o.getZ();
        }
        for (int j = 0; j < ps.size(); j++) {
            Player p = ps.get(j);
            UUID uid = p.getUniqueId();
            if (p == hooked || passHit.contains(uid)) continue;
            Location l = p.getLocation();
            double cx = l.getX(), cy = l.getY() + 0.9, cz = l.getZ();
            for (int i = 0; i < n; i++) {
                double dx = wx[i] - cx, dy = wy[i] - cy, dz = wz[i] - cz;
                if (Math.abs(dy) > 1.3 || dx * dx + dz * dz > TOUCH * TOUCH) continue;
                passHit.add(uid);
                strike(p, cx - wx[i], cz - wz[i]);
                break;
            }
        }
    }

    private void strike(Player p, double offX, double offZ) {
        hit(p, DAMAGE, "cause_g45");
        Vector side = new Vector(-sdz, 0, sdx);
        if (offX * side.getX() + offZ * side.getZ() < 0) side.multiply(-1);
        knock(p, side, PUSH, PUSH_UP);
        if (hooked == null && standing(p) && tail() != null && grab(p, ESCAPE)) {
            hooked = p;
            hookAt = age;
            tail().m.look.palette |= Look.PAL_DEEP_BLUSH;
            mark(p);
            eyes(true);
            sound("hook", Sound.ENTITY_PHANTOM_BITE, 0.7f, 32);
            game.hud.notice("notice_g45_hook", Species.G45, 60);
            game.hud.hint(p, "hint_escape", 60);
        }
    }

    /** 꼬리에 매달린 사람을 꼬리 아래로 옮긴다. 4초가 지나면 갇히고 행렬이 한 마디 길어진다. */
    private void hookTick() {
        Player p = hooked;
        if (p == null) return;
        if (!p.isOnline() || !standing(p) || game.players.grabbedBy(p) != this) {
            unhook();
            return;
        }
        Seg t = tail();
        if (t == null) {
            dropHook();
            return;
        }
        Vector o = t.m.off();
        seat.setX(pos.getX() + o.getX()).setY(pos.getY() + o.getY() - HANG).setZ(pos.getZ() + o.getZ());
        double fl = game.mobs.flow.floor(seat.getX(), seat.getZ()) + 0.62;
        if (seat.getY() < fl) seat.setY(fl);
        game.players.carry(p, seat);
        if (age - hookAt >= HOOK_T) swallow(p);
    }

    private void unhook() {
        hooked = null;
        for (Seg s : chain) s.m.look.palette &= ~Look.PAL_DEEP_BLUSH;
        if (phase == Phase.CRUISE) {
            mark(null);
            eyes(false);
        }
    }

    /** 매달린 사람을 떨군다(마디가 끊김, 이 개체가 사라짐). */
    private void dropHook() {
        Player p = hooked;
        if (p == null) return;
        unhook();
        if (p.isOnline() && game.players.grabbedBy(p) == this) game.players.release(p);
    }

    /** 못 구함: 갇히고 꼬리에 한 마디(비명 얼굴)가 는다. 여섯을 넘으면 꼬리 셋이 새 행렬로 떨어져 나간다. */
    private void swallow(Player p) {
        unhook();
        game.players.trap(p, this);
        PState s = game.players.get(p);
        if (s.eater != this) return;
        sound("hook", Sound.ENTITY_PHANTOM_BITE, 0.7f, 32);
        Swarm.Member m = swarm.add(new Vector(), "f5");
        m.look.phase = body.look.phase + chain.size() * 4;
        Seg seg = new Seg(m, 5, each);
        chain.add(seg);
        family.total++;
        retail();
        sumHp();
        // 새 마디는 바로 꼬리 자리에(머리 자리에서 날아오지 않게)
        sample();
        int i = chain.size() - 1;
        tmp.setX(wx[i] - pos.getX()).setY(wy[i] - pos.getY()).setZ(wz[i] - pos.getZ());
        m.moveTo(tmp, 0);
        if (chain.size() > MAX) {
            List<Seg> back = new ArrayList<>(chain.subList(chain.size() - 3, chain.size()));
            spawnPiece(back);
            chain.removeAll(back);
            retail();
            sumHp();
        }
        tagUpdate();
    }

    // ------------------------------------------------------------------ 갇힌 사람 이름표(꼬리 위)

    private void tagUpdate() {
        List<String> names = new ArrayList<>();
        for (Player q : game.players.trappedIn(this)) {
            PState s = game.players.get(q);
            if (s != null && s.noLikeness) continue;
            names.add(q.getName());
        }
        String text = String.join(" ", names);
        if (text.equals(tagText) && (tag != null || text.isEmpty())) return;
        tagText = text;
        if (text.isEmpty()) {
            if (tag != null) tag.remove();
            tag = null;
            return;
        }
        if ((tag == null || !tag.isValid()) && body != null && body.anchor != null && body.anchor.isValid()) {
            tag = body.anchor.getWorld().spawn(body.anchor.getLocation(), TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setSeeThrough(false);
                t.setShadowed(false);
                t.setDefaultBackground(false);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                t.setViewRange(1.0f);
                t.setBrightness(new Display.Brightness(10, 10));
            });
            body.anchor.addPassenger(tag);
            tagPose(0);
        }
        if (tag != null) tag.text(Component.text(text).color(TAG_GRAY));
    }

    private void tagPose(int ticks) {
        if (tag == null || !tag.isValid()) return;
        Seg t = tail();
        if (t == null) return;
        Vector o = t.m.off();
        tag.setInterpolationDelay(0);
        tag.setInterpolationDuration(ticks);
        tag.setTransformation(new Transformation(new Vector3f((float) o.getX(), (float) o.getY() + 0.75f, (float) o.getZ()), new AxisAngle4f(),
                new Vector3f(1.4f, 1.4f, 1.4f), new AxisAngle4f()));
    }

    // ------------------------------------------------------------------ 맞음, 끊김

    @Override
    public List<double[]> spheres() {
        return swarm.spheres();
    }

    private Seg nearestSeg(Vector at) {
        Seg best = null;
        double bd = Double.MAX_VALUE;
        for (Seg s : chain) {
            if (s.gone || s.m.dead) continue;
            double d = s.m.pos().distanceSquared(at);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        double before = hp;
        DamageType t = d.type();
        Vector at = d.at() != null ? d.at() : pos;
        long wt = game.worldTime();
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            for (Seg s : chain) {
                if (s.gone || s.m.dead || s.m.pos().distanceSquared(at) > 9) continue;
                s.hp -= d.amount();
                s.m.look.hurtUntil = wt + 3;
            }
        } else {
            Seg s = nearestSeg(at);
            if (s != null) {
                s.hp -= d.amount();
                s.m.look.hurtUntil = wt + 3;
            }
        }
        double h = 0;
        for (Seg s : chain) if (!s.gone) h += Math.max(0, s.hp);
        hp = h;
        game.mobs.onHurt(this, d, before - hp);
        swarm.flinch();
        cut(d);
        return true;
    }

    /** 마디 하나 죽음: 바닥으로 떨어지는 시체, 끊은 사람에게 마디 값. */
    private void kill(Seg s, Damage d) {
        if (s.gone) return;
        s.gone = true;
        swarm.kill(s.m);
        if (noReward) return;
        Player by = d == null ? null : d.by();
        if (by != null && by.isOnline()) game.players.addMoney(by, SEG_REWARD);
    }

    /** 체력이 다한 마디에서 끊는다: 3마디 미만 조각은 떨어져 죽고, 첫 조각은 이 개체가, 나머지는 새 행렬이 된다. */
    private void cut(Damage d) {
        boolean any = false;
        for (Seg s : chain) {
            if (!s.gone && s.hp <= 0) {
                kill(s, d);
                any = true;
            }
        }
        if (!any) return;
        dropHook();
        sound("snap", Sound.BLOCK_CHAIN_BREAK, 0.7f, 32);
        List<List<Seg>> runs = new ArrayList<>();
        List<Seg> cur = null;
        for (Seg s : chain) {
            if (s.gone) {
                cur = null;
                continue;
            }
            if (cur == null) {
                cur = new ArrayList<>();
                runs.add(cur);
            }
            cur.add(s);
        }
        List<Seg> keep = null;
        for (List<Seg> r : runs) {
            if (r.size() < 3) {
                for (Seg s : r) kill(s, d);
                continue;
            }
            if (keep == null) keep = r;
            else spawnPiece(r);
        }
        if (keep == null) {
            chain.clear();
            hp = 0;
            reward = 0;
            die(d);
            return;
        }
        adopt(keep);
    }

    /** 이 개체가 조각 keep 으로 이어 간다(맨 앞 마디가 바뀌면 닻을 그 자리로 옮기고 길을 다시 깐다). */
    private void adopt(List<Seg> keep) {
        boolean sameHead = !chain.isEmpty() && chain.get(0) == keep.get(0);
        int n = keep.size();
        if (!sameHead) {
            for (int i = 0; i < n; i++) {
                Vector o = keep.get(i).m.off();
                wx[i] = pos.getX() + o.getX();
                wy[i] = pos.getY() + o.getY();
                wz[i] = pos.getZ() + o.getZ();
            }
        }
        chain.clear();
        chain.addAll(keep);
        if (!sameHead) {
            pos.setX(wx[0]).setY(wy[0]).setZ(wz[0]);
            if (n >= 2) {
                tmp.setX(wx[0] - wx[1]).setY(wy[0] - wy[1]).setZ(wz[0] - wz[1]);
                if (tmp.lengthSquared() > 1e-6) hd.copy(tmp.normalize());
            }
            seedPath(n);
            place(2);
            // 훑던 중이면 그만두고 오른다
            if (shadow != null) shadow.look.on.remove("show");
            flatDir();
            phase(Phase.CLIMB);
        }
        eyes(target != null);
        retail();
        sumHp();
    }

    /** 훑는 방향을 지금 머리 방향의 수평 성분으로. */
    private void flatDir() {
        sdx = hd.getX();
        sdz = hd.getZ();
        double fl = Math.hypot(sdx, sdz);
        if (fl > 1e-6) {
            sdx /= fl;
            sdz /= fl;
        } else {
            sdx = 0;
            sdz = 1;
        }
    }

    /** 조각 run 을 새 행렬로 떼어 낸다(디스플레이는 새로 만들고 이쪽 것은 지움). */
    private void spawnPiece(List<Seg> run) {
        int n = run.size();
        Seed sd = new Seed();
        sd.x = new double[n];
        sd.y = new double[n];
        sd.z = new double[n];
        sd.hp = new double[n];
        sd.face = new int[n];
        sd.each = each;
        sd.family = family;
        for (int i = 0; i < n; i++) {
            Seg s = run.get(i);
            Vector o = s.m.off();
            sd.x[i] = pos.getX() + o.getX();
            sd.y[i] = pos.getY() + o.getY();
            sd.z[i] = pos.getZ() + o.getZ();
            sd.hp[i] = Math.max(1, s.hp);
            sd.face[i] = s.face;
        }
        for (Seg s : run) {
            s.gone = true;
            s.m.dead = true;
            if (s.m.d != null) {
                s.m.d.remove();
                s.m.d = null;
            }
        }
        G45 piece = new G45(game, sp, sd);
        piece.noReward = noReward;
        G45 placed = game.mobs.place(piece, new Vector(sd.x[0], sd.y[0], sd.z[0]));
        if (placed == null) {
            family.alive.remove(piece);
            return;
        }
        placed.flatDir();
        placed.phase(Phase.CLIMB);
    }

    // ------------------------------------------------------------------ 죽음, 치우기

    private void cleanup() {
        if (tag != null) tag.remove();
        tag = null;
        if (shadow != null) {
            shadow.remove();
            shadow = null;
        }
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        dropHook();
        family.alive.remove(this);
        if (!family.alive.isEmpty()) {
            // 다른 조각이 남았으면 갇힌 사람은 그쪽으로(행렬을 다 끊어야 풀려남)
            G45 next = family.alive.get(0);
            game.players.transferTrapped(this, next);
            next.tagUpdate();
        } else if (!noReward && d != null && d.by() != null && d.by().isOnline()) {
            // 행렬을 다 끊음: 마디 값 합계를 한 번 더
            game.players.addMoney(d.by(), SEG_REWARD * family.total);
        }
        cleanup();
        super.die(d);
    }

    @Override
    public void remove() {
        dropHook();
        if (family != null) family.alive.remove(this);
        cleanup();
        super.remove();
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " segs=" + chain.size() + (hooked != null ? " hook=" + hooked.getName() : "")
                + (family != null ? " fam=" + family.alive.size() + "/" + family.total : "");
    }
}
