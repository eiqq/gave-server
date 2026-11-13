package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G28 짓누르는 게이브(돌격, 방해, 하늘).
 *
 * <p>도감: 2 블록 넓적한 얼굴이 아래를 보고 엎어져 하늘 15블록을 떠다니며 가장 빽빽하게 모인 사람 무리 위로 자리를 잡는다. 바닥 그림자가
 * 그 아래 사람 수만큼 짙어지고(4 단계), '우우웅'이 내려올수록 낮아진다. 3초 뒤 막대기를 접어 떨어진다: 떨어진 자리 반경 4블록에
 * '그 안의 사람 수 x 1.5' 피해(최대 9)와 넘어짐 1초. 바닥에 얼굴부터 깔려 2초 떨고(무방비) 다시 떠오른다.
 * 맞서기: 그림자가 짙어지면 흩어진다. 내려오는 동안 아래에서 쏘면 넓어 맞히기 쉽다.
 *
 * <p>넘어짐(묶임 1초)은 깔려 있는 동안 매 틱 2틱씩 이어 건다: 이 개체가 그사이 죽거나 치워지면 2틱 안에 풀린다. 묶인 사람은 제자리에
 * 붙들리므로 옆으로 밀치지 않고 살짝 띄우고 화면만 꺾는다. 무리 찾기(사람 수 제곱 셈)는 5틱에 한 번만 한다.
 *
 * <p>몸: 닻 {@code gave:gabe/g28}(줄여 구운 모델 shrink 0.5, 상태 {@code ""} 펄럭, {@code "fold"} 접힘, {@code "splat"} 깔림, 몸 pitch 90 으로
 * 얼굴이 아래) + 부품 {@code gave:gabe/g28_shadow}(세계 축, 바닥 높이, 반지름 4 로 키움, 상태 {@code s1..s4} 짙기).
 * 소리 drone(40, 음높이는 Java 가 내려올수록 낮춤), fold(32), splat(32). 원인 글귀 {@code cause_g28}.
 */
public final class G28 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SKY_H = 15, LOCK_END_H = 9.5;
    static final double DRIFT_V = 0.09, LOCK_V = 0.035, DROP_V = 1.0, RISE_V = 0.32;
    /** 자리 잡기(3초), 깔려 떠는 틱(2초), 다시 떠올라 쉬는 틱. */
    static final int LOCK_T = 60, SPLAT_T = 40, COOLDOWN = 80;
    /** 짓누르는 반경, 사람 하나당 피해, 최대 피해, 넘어짐 틱. */
    static final double CRUSH_R = 4, PER = 5, MAX_DMG = 22, MIN_DMG = 11;
    static final int KNOCKDOWN = 20;
    /** 이 수평 거리 안이면 무리 위에 왔다고 봄. 무리를 다시 세는 틱. */
    static final double OVER = 1.4;
    static final int SCAN = 5;
    static final double FACE_R = 2.1; // 2026-11-12 거대화: 얼굴 4 블록

    enum Phase { DRIFT, LOCK, DROP, SPLAT, RISE }

    private Phase phase = Phase.DRIFT;
    private int phaseAt, readyAt, nextDrone;
    private Body.Part shadow;
    private int dark = 1;
    private double lockFrom;
    private float shadowDy = Float.NaN;

    /** 무리 찾기 결과(SCAN 틱마다): 가장 빽빽한 무리 가운데, 그 무리 사람 수, 가장 가까운 무리 사람, 바로 아래 사람 수. */
    private final Vector crowdAt = new Vector();
    private int crowdN, under;
    private Player crowdNear;
    private double[] sx = new double[8], sy = new double[8], sz = new double[8];
    /** 깔려 넘어뜨린 사람(깔려 있는 동안 묶임을 이어 검). */
    private final List<Player> floored = new ArrayList<>(4);

    public G28(Game game, Species sp) {
        super(game, sp);
        flying = true;
        radius = FACE_R;
        hover = SKY_H;
        baseSpeed = DRIFT_V;
        noPush = true;
    }

    @Override
    protected void build() {
        // 컬링 상자: 몸 가운데에서 위아래 15 블록(하늘 15블록에 떠 있을 때 바닥 그림자 부품까지 들게). 더 높이면 바닥에 깔렸을 때 닻 엔티티가
        // 땅 밑 깊은 구역(y 48 아래, 클라가 그리지 않는 가려진 구역)에 들어가 몸째 안 보인다
        pitch = 90;
        body = Body.of(this, "gabe/g28", 9f, 30f);
        body.viewRange(3.0f);
        shadow = body.attach("gabe/g28_shadow");
        if (shadow != null) {
            shadow.follow = false;
            shadow.state("s1");
            if (shadow.d != null) shadow.d.setBrightness(new Display.Brightness(6, 6));
            poseShadow(0);
        }
        pitch = 90;
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        // 하늘 높이로(소환 명령은 바닥 + 띠 높이에 놓음)
        pos.setY(game.mobs.flow.floor(pos.getX(), pos.getZ()) + SKY_H);
        nextDrone = age + game.random.nextInt(20);
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
        pitch = 90; // 얼굴은 늘 아래(돌아보기 규칙이 잠깐 바꿀 수 있음)
        if ((phase == Phase.DRIFT || phase == Phase.LOCK) && (every(SCAN) || inPhase() == 0)) scan();
        switch (phase) {
            case DRIFT -> drift();
            case LOCK -> lock();
            case DROP -> drop();
            case SPLAT -> splat();
            case RISE -> rise();
        }
        if (every(2)) poseShadow(2);
    }

    /** 가장 빽빽한 무리 위로 천천히. */
    private void drift() {
        state("");
        body.look.rate = 1.0;
        yaw += 0.6f;
        double fl = floor();
        if (crowdN > 0) flyTo(crowdAt, fl + SKY_H, DRIFT_V * game.director.speedMul());
        else hold();
        darkness(under);
        if (crowdN > 0 && age >= readyAt && flatDist(crowdAt.getX(), crowdAt.getZ()) <= OVER) {
            mark(crowdNear);
            lockFrom = pos.getY() - fl;
            nextDrone = age;
            phase(Phase.LOCK);
        }
    }

    /** 3초 자리 잡기: 무리를 아주 느리게 따라가며 내려옴, 그림자 짙어짐, 우우웅이 낮아짐. */
    private void lock() {
        state("");
        int t = inPhase();
        double k = Math.min(1.0, t / (double) LOCK_T);
        body.look.rate = 1.0 - 0.45 * k;
        yaw += 0.3f;
        double h = lockFrom + (LOCK_END_H - lockFrom) * k;
        // 무리가 흩어졌으면(아무도 없음) 제자리에서 그대로 내려옴
        if (crowdN > 0) flyTo(crowdAt, floor() + h, LOCK_V);
        else flyTo(pos, floor() + h, LOCK_V);
        darkness(under);
        if (age >= nextDrone && !game.director.silent()) {
            float pitchV = (float) (1.05 - 0.45 * k);
            Props.soundAround(loc(), "gabe.g28.drone", Sound.ENTITY_WARDEN_HEARTBEAT, 1f, pitchV, 0.6f * pitchV, 40, -1);
            nextDrone = age + 15;
        }
        if (t >= LOCK_T) {
            state("fold");
            sound("fold", Sound.ENTITY_PHANTOM_FLAP, 0.5f, 32);
            body.look.rate = 1.0;
            phase(Phase.DROP);
        }
    }

    /** 막대기를 접고 떨어짐. */
    private void drop() {
        state("fold");
        vel.setX(0).setZ(0).setY(-DROP_V);
        double fl = floor();
        if (pos.getY() + vel.getY() <= fl + 0.4) {
            vel.setY(fl + 0.35 - pos.getY());
            impact();
            phase(Phase.SPLAT);
        }
    }

    /** 철퍼덕: 반경 4 안 사람 수 x 1.5 피해(최대 9), 넘어짐 1초. */
    private void impact() {
        state("splat");
        sound("splat", Sound.ENTITY_SLIME_SQUISH, 0.5f, 32);
        body.squash(1.25f, 1.25f, 0.55f, 2);
        Location c = new Location(game.world, pos.getX(), floor(), pos.getZ());
        floored.clear();
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - c.getX(), l.getZ() - c.getZ()) > CRUSH_R) continue;
            if (Math.abs(l.getY() - c.getY()) > 2.5) continue;
            floored.add(p);
        }
        double dmg = Math.min(MAX_DMG, Math.max(MIN_DMG, floored.size() * PER));
        for (Player p : floored) {
            hit(p, dmg, "cause_g28");
            game.players.root(p, 2, "g28");
            // 넘어짐: 제자리에서 살짝 뜨고 화면이 아래로 꺾임(묶인 사람은 옆으로 밀면 제자리로 되돌려지므로 밀지 않음)
            knock(p, new Vector(), 0, 0.25);
            Guns.kick(p, (float) (game.random.nextGaussian() * 6), 22f);
        }
        BlockData bd = floorBlock();
        if (bd != null) game.world.spawnParticle(Particle.BLOCK, c.clone().add(0, 0.1, 0), 60, CRUSH_R * 0.45, 0.1, CRUSH_R * 0.45, 0.15, bd);
        if (game.shotFx.room(ShotFx.NORMAL))
            game.shotFx.spawn(c.clone().add(0, 0.05, 0), "fx_flat", ShotFx.RING, 0, ShotFx.scale((float) (CRUSH_R * 2)), Display.Billboard.FIXED, false, 12);
        darkness(1);
    }

    /** 바닥에 얼굴부터 깔려 2초 떪(무방비). 처음 1초는 깔린 사람이 넘어져 있음. */
    private void splat() {
        state("splat");
        hold();
        int t = inPhase();
        if (t < KNOCKDOWN - 1) {
            for (int i = 0; i < floored.size(); i++) {
                Player p = floored.get(i);
                if (standing(p)) game.players.root(p, 2, "g28");
            }
        } else if (!floored.isEmpty()) floored.clear();
        if (t % 2 == 0) {
            float j = (float) (game.random.nextGaussian() * 0.04);
            body.offset.set(j, 0, (float) (game.random.nextGaussian() * 0.04));
        }
        if (t == 2) body.squash(1.12f, 1.12f, 0.75f, 3);
        if (t >= cd(SPLAT_T)) {
            body.offset.set(0, 0, 0);
            body.squash(1f, 1f, 1f, 6);
            state("");
            mark(null);
            phase(Phase.RISE);
        }
    }

    private void rise() {
        state("");
        double want = floor() + SKY_H;
        vel.setX(0).setZ(0).setY(Math.min(RISE_V, want - pos.getY()));
        darkness(1);
        if (pos.getY() >= want - 0.3) {
            readyAt = age + cd(COOLDOWN);
            phase(Phase.DRIFT);
        }
    }

    // ------------------------------------------------------------------ 도우미

    /**
     * 무리 찾기(SCAN 틱마다 한 번): 사람마다 반경 4 안 사람을 세어 가장 많은 쪽(같으면 가까운 쪽)의 가운데. 자리는 한 번만 읽어 배열에 담는다
     * (사람 30명이면 900번 비교, 할당 없음). 바로 아래(그림자) 사람 수도 같이 센다.
     */
    private void scan() {
        List<Player> all = game.mobs.standing();
        int n = all.size();
        if (sx.length < n) {
            sx = new double[n * 2];
            sy = new double[n * 2];
            sz = new double[n * 2];
        }
        under = 0;
        for (int i = 0; i < n; i++) {
            Location l = all.get(i).getLocation();
            sx[i] = l.getX();
            sy[i] = l.getY();
            sz[i] = l.getZ();
            if (flatDist(sx[i], sz[i]) <= CRUSH_R) under++;
        }
        int best = -1, bestN = 0;
        double bestD = Double.MAX_VALUE;
        for (int a = 0; a < n; a++) {
            int c = 0;
            for (int b = 0; b < n; b++) {
                double dx = sx[a] - sx[b], dz = sz[a] - sz[b];
                if (dx * dx + dz * dz <= CRUSH_R * CRUSH_R && Math.abs(sy[a] - sy[b]) < 3) c++;
            }
            double d = flatDist(sx[a], sz[a]);
            if (c > bestN || (c == bestN && d < bestD)) {
                best = a;
                bestN = c;
                bestD = d;
            }
        }
        crowdN = bestN;
        crowdNear = null;
        if (best < 0) return;
        double cx = 0, cy = 0, cz = 0, nd = Double.MAX_VALUE;
        for (int b = 0; b < n; b++) {
            double dx = sx[best] - sx[b], dz = sz[best] - sz[b];
            if (dx * dx + dz * dz > CRUSH_R * CRUSH_R || Math.abs(sy[best] - sy[b]) >= 3) continue;
            cx += sx[b];
            cy += sy[b];
            cz += sz[b];
            double d = flatDist(sx[b], sz[b]);
            if (d < nd) {
                nd = d;
                crowdNear = all.get(b);
            }
        }
        crowdAt.setX(cx / bestN).setY(cy / bestN).setZ(cz / bestN);
    }

    private double flatDist(double x, double z) {
        return Math.hypot(x - pos.getX(), z - pos.getZ());
    }

    /** 수평으로 to 쪽(최대 v), 높이는 y 쪽으로. */
    private void flyTo(Vector to, double y, double v) {
        double dx = to.getX() - pos.getX(), dz = to.getZ() - pos.getZ(), l = Math.hypot(dx, dz);
        double k = l < 1e-6 ? 0 : Math.min(v, l) / l;
        vel.setX(dx * k).setZ(dz * k).setY(Math.max(-0.25, Math.min(0.25, (y - pos.getY()) * 0.15)));
    }

    /** 그림자 짙기 1..4. */
    private void darkness(int n) {
        int s = Math.max(1, Math.min(4, n));
        if (s == dark || shadow == null) return;
        dark = s;
        shadow.state("s" + s);
    }

    /** 그림자를 바로 아래 바닥에(세계 축), 반지름 CRUSH_R. 높이가 바뀔 때만 다시 보낸다. */
    private void poseShadow(int ticks) {
        if (shadow == null) return;
        float dy = (float) (floor() + 0.04 - pos.getY());
        if (Math.abs(dy - shadowDy) < 0.01f) return;
        shadowDy = dy;
        shadow.pose(new Matrix4f().translate(0, dy, 0).scale((float) CRUSH_R, 1f, (float) CRUSH_R), ticks);
    }

    private BlockData floorBlock() {
        try {
            BlockData bd = game.world.getBlockAt((int) Math.floor(pos.getX()), (int) Math.floor(floor() - 0.5), (int) Math.floor(pos.getZ()))
                    .getBlockData();
            return bd.getMaterial().isAir() ? null : bd;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        // 넓적한 얼굴(아래를 봄): 가운데 하나 + 가장자리 넷(넓어 아래에서 맞히기 쉬움)
        List<double[]> out = new ArrayList<>(5);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 1.5});
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(yaw) + Math.PI / 4 + i * Math.PI / 2;
            out.add(new double[] {pos.getX() + Math.cos(a) * 1.5, pos.getY(), pos.getZ() + Math.sin(a) * 1.5, 1.0});
        }
        return out;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 바닥 그림자는 시체와 함께 남지 않게 먼저 지움(넘어짐 묶임은 이어 걸지 않으면 2틱 안에 풀림)
        if (shadow != null) {
            shadow.remove();
            shadow = null;
        }
        floored.clear();
        super.die(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " h=" + Math.round((pos.getY() - floor()) * 10) / 10.0 + " dark=" + dark + " crowd=" + crowdN;
    }
}
