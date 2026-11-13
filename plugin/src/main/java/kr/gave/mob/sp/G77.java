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
 * G77 하늘 발 게이브(1막 8라운드, 돌격, 9블록). 구름에서 내려온 맨발 하나: 발바닥이 통째로 늘인 얼굴이고 발목 위 살 기둥은 구름 속으로 올라간다.
 * 발바닥 꼴 그림자가 먼저 땅에 지고, 사람이 가장 많이 모인 자리 위 14블록에 멈춰 2.5초 발가락을 꼼지락거리다(0.8초 전 쫙 펴짐) 내려 밟는다.
 * 밟으면 발바닥 밑 35 + 1.2초 넘어짐, 반경 9 땅울림 6, 1.5초 눌러 비비는 동안 밑에 깔린 사람 초당 10. 들어 올려 다음 자리로(걷듯이 세 번까지).
 *
 * <p>대처: 그림자 밖으로 뛴다. 발바닥의 때 얼룩 여섯(얼룩마다 체력 = 최대 체력 6%)을 다 쏴 씻으면 밟지 않고 구름 속으로 올라간다(보상 그대로).
 * 비비는 동안 복사뼈 막대기가 약점(x2).
 *
 * <p>몸: 닻 = 발 모델(gave:gabe/g77, 상태 "" 꼼지락, "splay" 쫙 펴짐, 부위 c1..c6 씻긴 얼룩), 부품 발목 기둥(g77_leg, 발을 따름), 바닥
 * 그림자(g77_shadow, 세계 축, 상태 s1..s4 짙기). Gabe.pos 는 늘 바닥 + {@link #ALT}, 발의 실제 높이는 body.offset.y = {@link #dy}(G77Sky 주석).
 * 사체 없음(죽거나 씻기면 올라가 사라짐).
 */
public final class G77 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** Gabe.pos 높이(바닥 위), 발 가운데 높이 차(높이 날 때, 겨눌 때 = 발바닥 14, 밟을 때 = 발바닥 바닥), 사라질 때. */
    static final double ALT = 20, DY_CRUISE = 4, DY_HOVER = 15 - ALT, DY_STOMP = 1.06 - ALT, DY_GONE = 34;
    /** 발바닥 아랫면(모델 y), 발바닥 타원 반지름(옆, 앞뒤, 발가락까지), 땅울림 반경. */
    static final double SOLE_Y = -1.0, SOLE_RX = 2.8, SOLE_RZ = 4.9, QUAKE_R = 9;
    /** 피해: 밟기, 땅울림, 비비기(10틱마다). */
    static final double STOMP_DMG = 35, QUAKE_DMG = 6, RUB_DMG = 5;
    /** 틱: 겨눔(꼼지락), 쫙 펴짐 예고, 내려찍기, 누르기, 들어올리기, 세 번 뒤 쉼, 사라짐. */
    static final int AIM = 50, SPLAY = 16, STOMP = 15, PRESS = 30, LIFT = 20, REST = 80, GONE = 40, ROOT = 24;
    static final int STEPS = 3, KEY = 4;
    /** 빠르기(블록/틱): 높이 날 때, 다음 자리로 걸을 때. 감독 빠르기 배율을 곱한다. */
    static final double CRUISE_V = 0.3, STEP_V = 0.35;
    /** 때 얼룩 하나 체력(최대 체력 몫). */
    static final double SPOT_FRAC = 0.06;
    /** 때 얼룩 자리(모델 x, z, g77.py GRIME 과 같음), 발목 기둥 뿌리. */
    static final double[][] GRIME = {{1.2, 1.6}, {-1.1, 1.2}, {0.25, -0.4}, {1.35, -2.1}, {-1.2, -2.5}, {0.15, -3.6}};
    static final float[] LEG_AT = {0f, 1.6f, -1.6f};

    enum Phase { CRUISE, AIM, STOMP, PRESS, LIFT, STEP, RISE, REST, LEAVE }

    private Phase phase = Phase.CRUISE;
    private int phaseAt, steps, aimFor;
    private final G77Sky.Glide dyG = new G77Sky.Glide();
    private Body.Part leg, shadow;
    private float shadowYaw = Float.NaN, shadowDy = Float.NaN;
    private int dark;
    private final Vector spot = new Vector();
    private boolean hasSpot, entered;
    private Player spotBy;
    private final double[] spotHp = new double[6];
    private boolean spotInit;
    private Damage last;
    private Player lastBy;
    private float baseYaw, keyYaw = Float.NaN;
    private boolean keyNow;
    private List<double[]> sph;
    private int sphAge = -1;

    public G77(Game game, Species sp) {
        super(game, sp);
        radius = 2.4;
        baseSpeed = CRUISE_V;
        noPush = true;
        noBite = true;
        noMutate = true;
        big = true;
        leaveCorpse = false;
        flying = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g77", 16f, G77Sky.boxH(ALT));
        body.viewRange(3.2f);
        dyG.snap(DY_CRUISE);
        body.offset.set(0, (float) DY_CRUISE, 0);
        keyYaw = yaw;
        leg = body.attach("gabe/g77_leg");
        if (leg != null) leg.pose(new Matrix4f().translate(LEG_AT[0], LEG_AT[1], LEG_AT[2]), 0);
        shadow = body.attach("gabe/g77_shadow");
        if (shadow != null) {
            shadow.follow = false;
            shadow.state("s1");
            dark = 1;
        }
        G77Sky.bright(body);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pos.setY(floor() + ALT);
        Vector c = G77Sky.center();
        yaw = (float) Math.toDegrees(Math.atan2(-(c.getX() - pos.getX()), c.getZ() - pos.getZ()));
        pitch = 0;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 발의 실제 높이 차(서버가 아는 보간 값). */
    private double dy() {
        return dyG.at(now());
    }

    private void lift(double to, int ticks) {
        dyG.go(to, now(), ticks);
        keyNow = true;
    }

    /**
     * 몸 자세 열쇠(KEY 틱마다 한 번): 높이 차는 KEY 틱 뒤 값을, 방향은 지금 값을 KEY 틱 보간으로 보낸다. 긴 보간 하나를 걸면 다음 방향 바꿈이 그것을 덮어
     * 끊기므로(엔진 Body 는 자세 보간이 하나뿐) 짧게 나눠 보낸다.
     */
    private void keyframe() {
        if (age % KEY != 0 && !keyNow) return;
        boolean moving = keyNow || !dyG.done(now()) || Math.abs(yaw - keyYaw) > 0.3f;
        keyNow = false;
        if (!moving) return;
        keyYaw = yaw;
        body.offset.set(0, (float) dyG.at(now() + KEY), 0);
        body.pose(KEY);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!spotInit) {
            spotInit = true;
            for (int i = 0; i < 6; i++) spotHp[i] = maxHp * SPOT_FRAC;
        }
        // 높이 유지(pos 는 늘 바닥 + ALT)
        double wantY = floor() + ALT;
        vel.setY(Math.max(-0.4, Math.min(0.4, (wantY - pos.getY()) * 0.2)));
        switch (phase) {
            case CRUISE -> cruise();
            case AIM -> aim();
            case STOMP -> stomp();
            case PRESS -> press();
            case LIFT -> afterLift();
            case STEP -> step();
            case RISE -> rise();
            case REST -> rest();
            case LEAVE -> leave();
        }
        keyframe();
        poseShadow();
        if (!entered && every(10)) {
            Player p = nearest(64);
            if (p != null) {
                entered = true;
                Props.soundAround(loc(), "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f, 0.5f, 160, -1);
            }
        }
    }

    private double k() {
        return speed() / baseSpeed;
    }

    /** 수평으로 to 쪽 v 로(높이는 tick 이 정한 vel.y 그대로). 다 왔으면 true. */
    private boolean flyTo(Vector to, double v) {
        double dx = to.getX() - pos.getX(), dz = to.getZ() - pos.getZ(), l = Math.hypot(dx, dz);
        if (l < 0.6) {
            vel.setX(0).setZ(0);
            return true;
        }
        double s = Math.min(v, l) / l;
        vel.setX(dx * s).setZ(dz * s);
        turnTo(new Vector(to.getX(), pos.getY(), to.getZ()), 3f);
        pitch = 0;
        return false;
    }

    /** 사람이 가장 많이 모인 자리(6블록 안 수가 가장 많은 사람). 없으면 가운데. */
    private boolean pickSpot() {
        Player p = G77Sky.densest(game, 6, pos);
        if (p == null) {
            Vector c = G77Sky.center();
            spot.setX(c.getX()).setZ(c.getZ());
            hasSpot = false;
            return false;
        }
        spot.setX(p.getLocation().getX()).setZ(p.getLocation().getZ());
        hasSpot = true;
        spotBy = p;
        return true;
    }

    private void cruise() {
        if (inPhase() == 0 || every(20)) pickSpot();
        if (Math.abs(dyG.to - DY_CRUISE) > 0.01) lift(DY_CRUISE, 30);
        darkness(1);
        if (flyTo(spot, CRUISE_V * k()) && hasSpot) startAim();
    }

    private void startAim() {
        vel.setX(0).setZ(0);
        lift(DY_HOVER, 20);
        state("");
        body.look.rate = 2.5f;
        aimFor = Math.max(SPLAY + 10, cd(AIM));
        sound("horn", Sound.EVENT_RAID_HORN, 0.5f, 64);
        if (standing(spotBy)) mark(spotBy);
        darkness(2);
        phase(Phase.AIM);
    }

    private void aim() {
        vel.setX(0).setZ(0);
        int t = inPhase();
        if (t == aimFor - SPLAY) {
            state("splay");
            body.look.rate = 1f;
            darkness(3);
        }
        if (t >= aimFor) {
            lift(DY_STOMP, STOMP);
            darkness(4);
            phase(Phase.STOMP);
        }
    }

    private void stomp() {
        vel.setX(0).setZ(0);
        if (inPhase() >= STOMP) impact();
    }

    /** 발바닥 타원 안인가(사람 발 자리, 발 가운데 기준 몸 축). */
    private boolean under(Player p) {
        Location l = p.getLocation();
        double[] o = G77Sky.toLocal(l.getX() - pos.getX(), l.getZ() - pos.getZ(), yaw);
        double ex = o[0] / SOLE_RX, ez = o[1] / SOLE_RZ;
        return ex * ex + ez * ez <= 1;
    }

    private void impact() {
        double fl = floor();
        for (Player p : new ArrayList<>(game.mobs.standing())) {
            Location l = p.getLocation();
            if (l.getY() > fl + 3.5) continue;
            if (under(p)) {
                hit(p, STOMP_DMG, "cause_g77");
                game.players.root(p, ROOT, "g77");
                Vector out = l.toVector().subtract(pos).setY(0);
                knock(p, out.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : out, 0.25, 0.05);
            } else if (G77Sky.flat(pos, l) <= QUAKE_R) {
                hit(p, QUAKE_DMG, "cause_g77_quake");
            }
        }
        Location at = new Location(game.world, pos.getX(), fl, pos.getZ());
        sound("stomp", Sound.ENTITY_RAVAGER_STEP, 0.5f, 64);
        Props.soundAround(at, "sfx.quake", Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f, 0.6f, 96, -1);
        game.horizon.shake(1.0, 12);
        G77Sky.quake(game, at, (float) QUAKE_R);
        baseYaw = yaw;
        phase(Phase.PRESS);
    }

    private void press() {
        vel.setX(0).setZ(0);
        int t = inPhase();
        if (t % 6 == 0) {
            yaw = baseYaw + ((t / 6) % 2 == 0 ? 7f : -7f);
            keyNow = true;
            if (t % 12 == 0) sound("rub", Sound.BLOCK_GRAVEL_STEP, 0.6f, 40);
        }
        if (t > 0 && t % 10 == 0) {
            for (Player p : new ArrayList<>(game.mobs.standing())) {
                if (p.getLocation().getY() > floor() + 3.5 || !under(p)) continue;
                hit(p, RUB_DMG, "cause_g77");
                game.players.root(p, 12, "g77");
            }
        }
        if (t >= PRESS) {
            yaw = baseYaw;
            state("");
            lift(DY_HOVER, LIFT);
            darkness(2);
            steps++;
            mark(null);
            phase(Phase.LIFT);
        }
    }

    private void afterLift() {
        vel.setX(0).setZ(0);
        if (inPhase() < LIFT) return;
        if (steps < STEPS && pickSpot() && G77Sky.flat(pos, spot) < 40) {
            phase(Phase.STEP);
        } else {
            lift(DY_CRUISE, 30);
            darkness(1);
            phase(Phase.RISE);
        }
    }

    private void step() {
        if (flyTo(spot, STEP_V * k())) startAim();
        else if (inPhase() > 160) {
            lift(DY_CRUISE, 30);
            phase(Phase.RISE);
        }
    }

    private void rise() {
        vel.setX(0).setZ(0);
        if (inPhase() >= 30) phase(Phase.REST);
    }

    private void rest() {
        Vector c = G77Sky.centroid(game);
        if (c == null) c = G77Sky.center();
        flyTo(c, CRUISE_V * 0.4 * k());
        if (inPhase() >= cd(REST)) {
            steps = 0;
            phase(Phase.CRUISE);
        }
    }

    // ------------------------------------------------------------------ 올라가 사라짐(죽음, 씻김)

    private void goAway(Damage d) {
        if (phase == Phase.LEAVE) return;
        last = d;
        untargetable = true;
        mark(null);
        state("");
        body.look.rate = 1f;
        lift(DY_GONE, GONE);
        darkness(1);
        Props.soundAround(loc(), "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f, 0.6f, 160, -1);
        phase(Phase.LEAVE);
    }

    private void leave() {
        vel.setX(0).setZ(0);
        if (inPhase() >= GONE) {
            Damage d = last != null ? last : new Damage(0, DamageType.OTHER, lastBy, pos.clone(), null, "wash", 0);
            untargetable = false;
            die(d);
        }
    }

    // ------------------------------------------------------------------ 그림자

    private void darkness(int n) {
        if (shadow == null || n == dark) return;
        dark = n;
        shadow.state("s" + n);
    }

    private void poseShadow() {
        if (shadow == null) return;
        float fy = (float) (floor() + 0.06 - pos.getY());
        if (Math.abs(fy - shadowDy) < 0.05f && Math.abs(yaw - shadowYaw) < 3f) return;
        shadowDy = fy;
        shadowYaw = yaw;
        shadow.pose(new Matrix4f().translate(0, fy, 0).rotateY((float) Math.toRadians(-yaw)), 3);
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: 0..5 때 얼룩, 6..7 복사뼈 막대기, 그 뒤 발 몸. 틱마다 한 번만 만든다. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(20);
        double cy = pos.getY() + dy();
        for (int i = 0; i < 6; i++) {
            if (spotHp[i] <= 0 && spotInit) {
                out.add(null);
                continue;
            }
            add(out, GRIME[i][0], SOLE_Y - 0.05, GRIME[i][1], 0.8, cy);
        }
        add(out, 2.5, 1.7, -1.7, 0.6, cy);
        add(out, -2.5, 1.7, -1.7, 0.6, cy);
        for (double x : new double[] {-1.2, 1.2}) for (double z : new double[] {-3.2, -1.1, 1.0, 3.0}) add(out, x, 0.35, z, 1.3, cy);
        add(out, 0, -0.3, 4.0, 1.0, cy);
        add(out, 0, 2.4, -1.6, 1.4, cy);
        out.removeIf(s -> s == null);
        sph = out;
        sphAge = age;
        return out;
    }

    private void add(List<double[]> out, double x, double y, double z, double r, double cy) {
        Vector w = G77Sky.local(x, y, z, yaw);
        out.add(new double[] {pos.getX() + w.getX(), cy + w.getY(), pos.getZ() + w.getZ(), r, out.size()});
    }

    /** 맞은 점에서 가장 가까운 부위 번호(0..5 얼룩, 6..7 복사뼈, 8 몸). */
    private int partAt(Vector at) {
        if (at == null) return 8;
        double best = Double.MAX_VALUE;
        int part = 8;
        for (double[] s : spheres()) {
            double dx = s[0] - at.getX(), dy = s[1] - at.getY(), dz = s[2] - at.getZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz) - s[3];
            if (d < best) {
                best = d;
                part = (int) s[4] < 8 ? (int) s[4] : 8;
            }
        }
        return part;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || phase == Phase.LEAVE) return false;
        int part = partAt(d.at());
        Damage dd = part >= 6 && part <= 7 && phase == Phase.PRESS ? d.times(2) : d;
        hp -= dd.amount();
        game.mobs.onHurt(this, dd);
        if (dd.by() != null) lastBy = dd.by();
        if (part < 6 && spotHp[part] > 0) {
            spotHp[part] -= dd.amount();
            if (spotHp[part] <= 0) {
                body.look.on.add("c" + (part + 1));
                sph = null;
                sound("rub", Sound.ENTITY_GENERIC_SPLASH, 1.4f, 32);
                boolean all = true;
                for (double h : spotHp) all &= h <= 0;
                if (all && hp > 0) {
                    goAway(new Damage(0, DamageType.OTHER, lastBy, pos.clone(), null, "wash", 0));
                    return true;
                }
            }
        }
        if (hp <= 0) goAway(dd);
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (shadow != null) {
            shadow.remove();
            shadow = null;
        }
        leaveCorpse = false;
        super.die(d);
    }

    @Override
    public String toString() {
        int washed = 0;
        for (double h : spotHp) if (h <= 0) washed++;
        return super.toString() + " ph=" + phase + " dy=" + Math.round(dy() * 10) / 10.0 + " steps=" + steps + " washed=" + washed;
    }
}
