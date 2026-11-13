package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;

/**
 * G79 접시 세는 게이브(1막 원거리). 머리 높이로 둥둥 떠와 18블록에서 멈추고, 젓가락으로 머리 위 접시 탑의 맨 위 접시를 집어 사람 쪽으로 휘어 던지기를
 * 1.2초마다 되풀이한다(10 피해, 깨진 조각이 1블록 안 다른 사람에게 3). 집을 때마다 고개를 끄덕여 센다. 아홉을 다 세면 접시가 없어 두리번거리다
 * 빨간 원판 예고(1.5초) 뒤 비명: 반경 14 안 모두 24 피해와 2초 묶임. 비명 뒤 접시 아홉이 돌아와 다시 센다.
 *
 * <p>대처: 날아오는 접시를 쏴 깨면(접시도 맞음) 그 수는 안 센 것이 되어 갸웃하고 그 접시가 탑으로 돌아온다. 쌓인 접시를 미리 깨 두면(접시마다 체력 8,
 * 감독 체력 배율 비례) 아홉을 못 채워 비명 없이 갸웃만 한다. 앞에서 쏘면 이마를 가린 아래 접시가 맞는다.
 *
 * <p>팩 계약(tools/gabe/sp/g79.py): 모델 gabe/g79 상태 "", "pick", "scream", "confused", 부위 p1..p9(그 접시 없음, 1 맨 아래).
 * 던진 접시 gabe/g79_plate(부품, 세계 축). 소리 plate, crack, scream. 원인 글귀 cause_g79, cause_g79_scream.
 */
public final class G79 extends Gabe {

    static final double SPEED = 0.15, HOVER = 1.7, FACE_R = 0.5, PLATE_R = 0.3;
    /** 멈추는 거리, 다시 다가오는 거리. */
    static final double HOLD_R = 18, APPROACH_AGAIN = 23;
    /** 던지기: 간격, 집는 틱, 접시 빠르기(블록/틱), 휘는 옆 거리, 맞는 거리, 피해, 조각 반경과 피해. */
    static final int THROW_EVERY = 24, PICK = 6;
    static final double PLATE_V = 0.7, CURVE = 3.0, PLATE_HIT = 0.6, PLATE_DMG = 10, SHARD_R = 1.0, SHARD_DMG = 3;
    /** 접시: 수, 체력(감독 체력 배율 비례), 탑 자리(몸 기준). */
    static final int N = 9;
    static final double PLATE_HP = 8, STACK_Y0 = 0.62, STACK_DY = 0.17, STACK_Z = 0.32;
    /** 비명: 두리번 틱, 예고 틱(박자 배율 안 받음: 대처 창), 반경, 피해, 묶임. 갸웃 틱, 다시 쌓는 틱. */
    static final int LOOK = 20, WARN = 30, ROOT = 40, CONFUSED = 40, RESTOCK = 40;
    static final double SCREAM_R = 14, SCREAM_DMG = 24;

    enum Phase { APPROACH, HOLD, PICK, LOOK, WARN, CONFUSED, RESTOCK }

    /** 날아가는 접시(쏠 수 있음). */
    final class Flying implements Mobs.Shootable {
        final int idx;
        Body.Part p;
        final Vector a = new Vector(), c = new Vector(), b = new Vector(), at = new Vector();
        int t, T;
        boolean done;

        Flying(int idx) {
            this.idx = idx;
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            Vector oc = at.clone().subtract(o);
            double tc = oc.dot(dir);
            if (tc < 0 || tc > max) return Double.POSITIVE_INFINITY;
            double d2 = oc.lengthSquared() - tc * tc;
            double r = PLATE_R + 0.1;
            return d2 > r * r ? Double.POSITIVE_INFINITY : tc - Math.sqrt(r * r - d2);
        }

        @Override
        public boolean shot(Damage d) {
            if (done) return false;
            // 날아가던 접시를 깸: 그 수는 안 센 것, 갸웃하고 그 접시가 탑으로 돌아옴
            land(this, false);
            present[idx] = true;
            body.look.on.remove("p" + (idx + 1));
            sound("crack", Sound.BLOCK_GLASS_BREAK, 1.3f, 20);
            if (phase == Phase.HOLD || phase == Phase.PICK) {
                state("confused");
                tiltUntil = age + 16;
            }
            return false;
        }

        @Override
        public Vector center() {
            return at.clone();
        }

        @Override
        public boolean gone() {
            return done || dead;
        }
    }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, count, tiltUntil;
    private final boolean[] present = new boolean[N];
    private final double[] plateHp = new double[N];
    private boolean init;
    private Player prey;
    private final List<Flying> flying = new ArrayList<>(3);
    private final Matrix4f mat = new Matrix4f();

    public G79(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g79", 1.6f, 3.2f);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private void restock() {
        double k = Math.max(1, maxHp / Math.max(1, sp.hp));
        for (int i = 0; i < N; i++) {
            present[i] = true;
            plateHp[i] = PLATE_HP * k;
            body.look.on.remove("p" + (i + 1));
        }
        count = 0;
    }

    @Override
    public void tick() {
        if (!init) {
            init = true;
            restock();
        }
        for (int i = flying.size() - 1; i >= 0; i--) fly(flying.get(i));
        if (age < tiltUntil) body.roll = 18;
        else if (body.roll != 0 && phase != Phase.LOOK) body.roll = 0;
        if (age == tiltUntil && phase == Phase.HOLD) state("");
        switch (phase) {
            case APPROACH -> approach();
            case HOLD -> holdAndThrow();
            case PICK -> pick();
            case LOOK -> lookAround();
            case WARN -> warn();
            case CONFUSED -> confused();
            case RESTOCK -> restocking();
        }
    }

    private void approach() {
        follow(speed());
        Player p = nearest(400);
        face(p != null ? chest(p) : pos.clone().add(vel));
        if (p != null && dist(p) <= HOLD_R) {
            hold();
            phase(Phase.HOLD);
        }
    }

    private int top() {
        for (int i = N - 1; i >= 0; i--) if (present[i]) return i;
        return -1;
    }

    private void holdAndThrow() {
        hold();
        Player p = nearest(400);
        if (p == null || dist(p) > APPROACH_AGAIN) {
            phase(Phase.APPROACH);
            return;
        }
        face(chest(p));
        if (inPhase() > 4) pitch = 0;
        if (top() < 0) {
            if (flying.isEmpty()) outOfPlates();
            return;
        }
        if (inPhase() >= cd(THROW_EVERY) && sees(p)) {
            prey = p;
            mark(p);
            state("pick");
            phase(Phase.PICK);
        }
    }

    /** 젓가락으로 맨 위 접시를 집어 고개를 끄덕이며 던짐. */
    private void pick() {
        hold();
        if (standing(prey)) face(chest(prey));
        if (inPhase() < PICK) return;
        int i = top();
        state("");
        if (i >= 0 && standing(prey)) throwPlate(i, prey);
        body.squash(1f, 0.9f, 1f, 2);
        pitch = 14;
        phase(Phase.HOLD);
    }

    private Vector plateAt(int i) {
        double r = Math.toRadians(yaw), cs = Math.cos(r), sn = Math.sin(r);
        double lx = 0.04 * ((i % 3) - 1), ly = STACK_Y0 + STACK_DY * i, lz = STACK_Z - 0.02 * i;
        return new Vector(pos.getX() + cs * lx - sn * lz, pos.getY() + ly, pos.getZ() + sn * lx + cs * lz);
    }

    private void throwPlate(int i, Player p) {
        Body.Part part = body.attach("gabe/g79_plate");
        if (part == null) return;
        part.follow = false;
        if (part.d != null) {
            part.d.setDisplayWidth(0f);
            part.d.setDisplayHeight(0f);
        }
        present[i] = false;
        body.look.on.add("p" + (i + 1));
        Flying f = new Flying(i);
        f.p = part;
        f.a.copy(plateAt(i));
        f.b.copy(chest(p));
        Vector ab = f.b.clone().subtract(f.a);
        double len = ab.length();
        Vector side = new Vector(-ab.getZ(), 0, ab.getX());
        if (side.lengthSquared() > 1e-6) side.normalize();
        double s = (count % 2 == 0 ? 1 : -1) * CURVE;
        f.c.copy(f.a.clone().add(f.b).multiply(0.5).add(side.multiply(s)).add(new Vector(0, 1.5, 0)));
        f.T = Math.max(12, (int) Math.round(len * 1.25 / PLATE_V));
        f.at.copy(f.a);
        flying.add(f);
        game.mobs.addShootable(f);
        sound("plate", Sound.BLOCK_GLASS_HIT, 1.4f, 24);
        poseFlying(f, 1);
    }

    /** 휘는 길(이차 베지에)을 따라 날아감. 사람에 닿거나 끝까지 가면 깨짐(센 것). */
    private void fly(Flying f) {
        if (f.done) {
            flying.remove(f);
            return;
        }
        f.t++;
        Vector prev = f.at.clone();
        double u = Math.min(1.0, (double) f.t / f.T), w = 1 - u;
        f.at.setX(w * w * f.a.getX() + 2 * w * u * f.c.getX() + u * u * f.b.getX());
        f.at.setY(w * w * f.a.getY() + 2 * w * u * f.c.getY() + u * u * f.b.getY());
        f.at.setZ(w * w * f.a.getZ() + 2 * w * u * f.c.getZ() + u * u * f.b.getZ());
        for (Player p : game.mobs.standing()) {
            if (segDist2(prev, f.at, chest(p)) <= PLATE_HIT * PLATE_HIT) {
                hit(p, PLATE_DMG, "cause_g79");
                shards(f, p);
                land(f, true);
                return;
            }
        }
        if (u >= 1.0 || !game.world.getBlockAt(f.at.getBlockX(), f.at.getBlockY(), f.at.getBlockZ()).isPassable()) {
            shards(f, null);
            land(f, true);
            return;
        }
        if (f.t % 2 == 0) poseFlying(f, 2);
    }

    /** 깨진 조각: 접시 자리 1블록 안 다른 사람. */
    private void shards(Flying f, Player hitOne) {
        for (Player p : game.mobs.standing()) {
            if (p == hitOne) continue;
            if (chest(p).distanceSquared(f.at) <= (SHARD_R + 0.4) * (SHARD_R + 0.4)) hit(p, SHARD_DMG, "cause_g79");
        }
    }

    /** 접시가 끝남. counted 면 센 것(쏴서 깬 것은 안 셈). */
    private void land(Flying f, boolean counted) {
        if (f.done) return;
        f.done = true;
        if (f.p != null) f.p.remove();
        f.p = null;
        game.mobs.removeShootable(f);
        if (counted) {
            count++;
            if (!game.director.silent())
                kr.gave.Props.soundAround(new Location(game.world, f.at.getX(), f.at.getY(), f.at.getZ()), "gabe.g79.crack", Sound.BLOCK_GLASS_BREAK,
                        1f, 0.94f + game.random.nextFloat() * 0.12f, 1.2f, 20, -1);
        }
        game.shotFx.pop(new Location(game.world, f.at.getX(), f.at.getY(), f.at.getZ()));
    }

    private static double segDist2(Vector a, Vector b, Vector c) {
        double abx = b.getX() - a.getX(), aby = b.getY() - a.getY(), abz = b.getZ() - a.getZ();
        double acx = c.getX() - a.getX(), acy = c.getY() - a.getY(), acz = c.getZ() - a.getZ();
        double l2 = abx * abx + aby * aby + abz * abz;
        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, (acx * abx + acy * aby + acz * abz) / l2));
        double dx = acx - abx * t, dy = acy - aby * t, dz = acz - abz * t;
        return dx * dx + dy * dy + dz * dz;
    }

    private void poseFlying(Flying f, int ticks) {
        if (f.p == null) return;
        Vector off = f.at.clone().subtract(pos);
        mat.identity().translate((float) off.getX(), (float) off.getY(), (float) off.getZ()).rotateY(f.t * 0.7f).rotateX((float) Math.toRadians(70));
        f.p.pose(mat, ticks);
    }

    /** 탑이 비었음: 아홉을 다 셌으면 두리번 → 예고 → 비명, 아니면 갸웃. */
    private void outOfPlates() {
        pitch = 0;
        if (count >= N) {
            state("confused");
            phase(Phase.LOOK);
        } else {
            state("confused");
            phase(Phase.CONFUSED);
        }
    }

    private void lookAround() {
        hold();
        yaw += (inPhase() / 5 % 2 == 0 ? 9 : -9);
        body.roll = (float) (Math.sin(inPhase() * 0.5) * 10);
        if (inPhase() >= cd(LOOK)) {
            body.roll = 0;
            state("scream");
            mark(nearest(400));
            game.shotFx.spawn(new Location(game.world, pos.getX(), floor() + 0.06, pos.getZ()), "fx_flat", ShotFx.RING, 0,
                    ShotFx.scale((float) (SCREAM_R * 2)), Display.Billboard.FIXED, false, WARN + 4, ShotFx.KEY);
            sound("scream", Sound.ENTITY_GHAST_WARN, 0.8f, 32);
            phase(Phase.WARN);
        }
    }

    /** 빨간 원판 예고 뒤 비명(반경 안 모두). */
    private void warn() {
        hold();
        body.squash(1f + 0.08f * (inPhase() % 4 < 2 ? 1 : 0), 1f, 1f, 1);
        if (inPhase() < WARN) return;
        sound("scream", Sound.ENTITY_GHAST_SCREAM, 0.7f, 32);
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz > SCREAM_R * SCREAM_R || Math.abs(l.getY() - pos.getY()) > 8) continue;
            hit(p, SCREAM_DMG, "cause_g79_scream");
            game.players.root(p, ROOT, "g79_scream");
        }
        body.squash(1f, 1f, 1f, 4);
        phase(Phase.RESTOCK);
    }

    private void confused() {
        hold();
        body.roll = 18;
        if (inPhase() >= CONFUSED) {
            body.roll = 0;
            phase(Phase.RESTOCK);
        }
    }

    /** 접시 아홉이 돌아와 쌓임(다시 셈). */
    private void restocking() {
        hold();
        if (inPhase() < cd(RESTOCK)) return;
        restock();
        state("");
        if (target != null) mark(null);
        sound("plate", Sound.BLOCK_GLASS_HIT, 1.4f, 24);
        phase(Phase.HOLD);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(N + 1);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), FACE_R});
        for (int i = 0; i < N; i++) {
            if (!present[i] && init) continue;
            Vector v = plateAt(i);
            out.add(new double[] {v.getX(), v.getY(), v.getZ(), PLATE_R});
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            int best = -1;
            double bd = d.at().distance(pos) - FACE_R;
            for (int i = 0; i < N; i++) {
                if (!present[i]) continue;
                double dd = d.at().distance(plateAt(i)) - PLATE_R;
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best >= 0) {
                plateHp[best] -= d.amount();
                game.mobs.onHurt(this, d, Math.min(1, d.amount()));
                if (plateHp[best] <= 0) breakPlate(best);
                return true;
            }
        } else if (d.at() != null && (t == DamageType.EXPLOSION || t == DamageType.FIRE)) {
            for (int i = 0; i < N; i++) {
                if (present[i] && d.at().distanceSquared(plateAt(i)) <= 4) {
                    plateHp[i] -= d.amount();
                    if (plateHp[i] <= 0) breakPlate(i);
                }
            }
        }
        return super.damage(d);
    }

    /** 쌓인 접시가 깨짐(이번 차례에는 그 수를 못 채움). */
    private void breakPlate(int i) {
        present[i] = false;
        body.look.on.add("p" + (i + 1));
        sound("crack", Sound.BLOCK_GLASS_BREAK, 1.2f, 20);
        Vector v = plateAt(i);
        game.shotFx.pop(new Location(game.world, v.getX(), v.getY(), v.getZ()));
    }

    @Override
    public void die(Damage d) {
        for (Flying f : new ArrayList<>(flying)) {
            f.done = true;
            if (f.p != null) f.p.remove();
            f.p = null;
            game.mobs.removeShootable(f);
        }
        flying.clear();
        super.die(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        int left = 0;
        for (boolean b : present) if (b) left++;
        return super.toString() + " ph=" + phase + " count=" + count + " stack=" + left + " flying=" + flying.size();
    }
}
