package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G152 하늘 시계추 게이브(4막 34라운드, 돌격, 방해, 26블록). 트럭 위 하늘 32블록의 한 점에서 굵은 막대기 둘을 묶은 추대가 내려오고 그 끝에 5블록 얼굴
 * 추가 달렸다. 트럭 둘레 위를 지나는 큰 호(폭 30)로 흔들리고(한 번 지나는 데 3초, 박자는 틀리지 않음), 처음에는 가장 낮은 점이 바닥 위 6블록이라
 * 그림자만 지나가다 한 번 흔들릴 때마다 0.8블록씩 내려와 일곱 번째부터 바닥을 스친다. 바닥을 스치기 시작하면 흔들리는 길이 10초마다 30도씩
 * 사람이 더 많은 쪽으로 돈다(2초 전에 바닥 띠가 그쪽에 붉게 미리 그려짐). 양 끝에서 0.3초 멈추며 얼굴 덩이 둘을 떨군다(반경 1.5 안 10).
 * 추 얼굴과 턱 밑 칼날 판이 지나간 길(틱 사이 선분)에 든 사람 36 + 지나가는 쪽으로 날아감, 스칠 때 흙먼지 띠 6, 트럭 위를 스치면 트럭 2%.
 *
 * <p>대처: 박자를 세고 띠 밖에 선다. 추대 막대기(추 위 8~20블록 구간, 하나 체력 600 x 감독 체력 배율)를 하나 끊으면 호가 절반, 둘 다 끊으면 추 얼굴이
 * 떨어져 3초 굴러다니다(닿으면 20) 멈춘다(그동안 피해 x2). 멈춘 얼굴은 4초마다 가까운 사람 쪽으로 다시 구른다.
 *
 * <p>몸: {@link Body#carrier} + 부품(추 얼굴 g152, 추대 g152_arm(cut_l, cut_r), 바닥 띠 g152_band 세계 축, 추 그림자 g152_shadow 세계 축).
 * 매달린 동안 Gabe.pos = 매단 점(바닥 + 32), 몸 yaw = 흔드는 방향, pitch = -흔들림 각. 떨어진 뒤 pos = 얼굴 가운데. 사체 없음.
 */
public final class G152 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 매단 점 높이(바닥 위), 얼굴 반(정수리까지), 칼날 끝(얼굴 가운데 아래), 처음 추 길이(가장 낮은 점 바닥 위 6), 흔들 때마다 늘어남. */
    static final double PIVOT_H = 32, HALF = 2.5, BLADE_LOW = 5.5, L0 = PIVOT_H - BLADE_LOW - 6, L_MAX = PIVOT_H - BLADE_LOW - 0.35, L_STEP = 0.8;
    /** 호의 수평 반폭, 맞는 구(얼굴, 칼날), 흙먼지 띠 반경. */
    static final double HALF_SWEEP = 15, BOB_R = 2.6, BLADE_R = 2.1, DUST_R = 4.5;
    /** 틱: 한 번 지나감, 끝에서 멈춤, 내려오기, 방향 바꾸기 간격과 미리 보임, 덩이 떨어짐, 떨어진 얼굴 구르기와 멈춤. */
    static final int SWING_T = 60, PAUSE_T = 6, ENTER_T = 80, TURN_EVERY = 200, TURN_WARN = 40, LUMP_FALL = 12, ROLL_T = 60, REST_T = 80;
    /** 피해: 쓸림, 흙먼지, 덩이, 굴러 닿음. 트럭 몫. */
    static final double SWEEP_DMG = 36, DUST_DMG = 6, LUMP_DMG = 10, ROLL_DMG = 20, TRUCK_FRAC = 0.02;
    /** 추대 막대기 하나 체력(감독 체력 배율을 곱함). */
    static final double ARM_HP = 600;

    enum Phase { ENTER, SWING, PAUSE, ROLL, REST, FALL }

    private Phase phase = Phase.ENTER;
    private int phaseAt;
    /** 매단 점(세계). */
    private final Vector piv = new Vector();
    private double len = 4, ampK = 1, start = L0;
    /** 흔드는 방향(도, 마크 yaw), 이번 지나감의 쪽(+1 앞으로, -1 뒤로), 지금 세계 각(+ 가 앞). */
    private float dirYaw;
    private int side = 1;
    private double th, swingT0;
    private Float pendingYaw;
    private long turnAt, nextPlan;
    private int passes;
    private boolean skimming, init;
    private final double[] armHp = new double[2];
    private final Vector bob = new Vector(), bobPrev = new Vector(), blade = new Vector(), bladePrev = new Vector();
    private final Set<UUID> hitPass = new HashSet<>();
    private boolean truckPass;
    private Body.Part face, arm, band, shadow;
    private float faceSpin, rollAng;
    private final Vector rollDir = new Vector(1, 0, 0);
    private final List<double[]> lumps = new ArrayList<>();
    private Damage last;
    private List<double[]> sph;
    private int sphAge = -1;

    public G152(Game game, Species sp) {
        super(game, sp);
        radius = 2.6;
        baseSpeed = 0.2;
        flying = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        noRules = true;
        big = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        body.viewRange(4f);
        face = body.attach("gabe/g152");
        arm = body.attach("gabe/g152_arm");
        band = body.attach("gabe/g152_band");
        if (band != null) {
            band.follow = false;
            band.state("now");
        }
        G148Sky.bright(face, arm);
        shadow = body.attach("gabe/g152_shadow");
        if (shadow != null) shadow.follow = false;
        poseParts(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Vector c = G148Sky.center();
        pos.setX(c.getX()).setZ(c.getZ()).setY(floor() + PIVOT_H);
        piv.copy(pos);
        if (body != null) body.warp();
        Player p = G148Sky.densest(game, 10, c);
        if (p != null) {
            dirYaw = yawTo(c, p.getLocation().toVector());
            mark(p);
        } else dirYaw = game.random.nextFloat() * 360f;
        yaw = dirYaw;
        pitch = 0;
        th = 0;
        bobAt();
        bobPrev.copy(bob);
        bladePrev.copy(blade);
    }

    @Override
    public void lure(Vector at, int ticks) {
    }

    private static float yawTo(Vector from, Vector to) {
        return (float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ()));
    }

    private Vector fwd() {
        double a = Math.toRadians(dirYaw);
        return new Vector(-Math.sin(a), 0, Math.cos(a));
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            armHp[0] = armHp[1] = ARM_HP * G148Sky.hpK(this);
            turnAt = now() + TURN_EVERY;
            // 시험 소환(감독이 내지 않은 것)은 두 번 흔들면 바닥을 스치게(볼 것을 빨리)
            if (!game.director.fromDirector(this)) start = L_MAX - 2 * L_STEP;
        }
        vel.zero();
        bobPrev.copy(bob);
        bladePrev.copy(blade);
        switch (phase) {
            case ENTER -> enter();
            case SWING -> swing();
            case PAUSE -> pause();
            case ROLL -> roll();
            case REST -> rest();
            case FALL -> fall();
        }
        tickLumps();
        if (every(2)) poseShadow();
    }

    /** 매단 동안 pos 를 매단 점에 붙듦. */
    private void holdPivot() {
        vel.setX(piv.getX() - pos.getX()).setY(piv.getY() - pos.getY()).setZ(piv.getZ() - pos.getZ());
    }

    /** 지금 각, 길이로 추 얼굴과 칼날 가운데(세계). */
    private void bobAt() {
        Vector f = fwd();
        double s = Math.sin(Math.toRadians(th)), c = Math.cos(Math.toRadians(th));
        bob.setX(piv.getX() + len * s * f.getX()).setY(piv.getY() - len * c).setZ(piv.getZ() + len * s * f.getZ());
        double lb = len + 3.6;
        blade.setX(piv.getX() + lb * s * f.getX()).setY(piv.getY() - lb * c).setZ(piv.getZ() + lb * s * f.getZ());
    }

    private double thMax() {
        return Math.toDegrees(Math.asin(Math.min(0.95, HALF_SWEEP / Math.max(1, len)))) * ampK;
    }

    private void enter() {
        holdPivot();
        int t = inPhase();
        len = 4 + (start - 4) * Math.min(1, t / (double) ENTER_T);
        th = 0;
        bobAt();
        poseParts(3);
        if (t == 0) Props.soundAround(loc(), "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.7f, 0.5f, 160, -1);
        if (t >= ENTER_T) {
            side = 1;
            swingT0 = age - SWING_T / 2.0;
            newPass();
            phase(Phase.SWING);
        }
    }

    private void newPass() {
        hitPass.clear();
        truckPass = false;
        faceSpin = side > 0 ? 0 : 180;
        poseParts(3);
    }

    private void swing() {
        holdPivot();
        double t = age - swingT0;
        double ph = -Math.PI / 2 + Math.PI * Math.min(1, t / SWING_T);
        double old = th;
        th = side * thMax() * Math.sin(ph);
        // 바닥(가운데)을 지날 때 방향 바꿈(자리가 끊기지 않음)
        if (pendingYaw != null && now() >= turnAt - TURN_EVERY + TURN_WARN && Math.signum(old) != Math.signum(th)) {
            dirYaw = pendingYaw;
            pendingYaw = null;
            if (band != null) band.state("now");
        }
        yaw = dirYaw;
        pitch = (float) -th;
        bobAt();
        sweep();
        if (every(2)) body.pose(2);
        if (Math.abs(th) < thMax() * 0.25 && skimming && every(4)) dustFx();
        if (Math.abs(th) < 4 && every(30)) sound("swoosh", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 48);
        if (t >= SWING_T) {
            sound("tick", Sound.BLOCK_NOTE_BLOCK_HAT, side > 0 ? 0.6f : 0.5f, 96);
            dropLumps();
            phase(Phase.PAUSE);
        }
        planTurn();
    }

    private void pause() {
        holdPivot();
        bobAt();
        if (inPhase() == 2) {
            faceSpin = side > 0 ? 90 : 270;
            poseParts(3);
        }
        if (inPhase() >= PAUSE_T) {
            side = -side;
            passes++;
            if (len < L_MAX) len = Math.min(L_MAX, len + L_STEP);
            skimming = len >= L_MAX - 0.01;
            // 실눈 = 아직, 눈 뜸 = 바닥을 스치며 사람을 쓸 때
            if (face != null) face.look.open = skimming;
            swingT0 = age;
            newPass();
            phase(Phase.SWING);
        }
    }

    /** 틱 사이 지나간 길에 든 사람: 36 + 날아감, 스치면 흙먼지 6, 트럭 위를 스치면 트럭. */
    private void sweep() {
        Vector f = fwd().multiply(side);
        double fl = floor();
        boolean low = blade.getY() - 2.0 < fl + 1.5;
        for (Player p : G148Sky.standing(game)) {
            if (hitPass.contains(p.getUniqueId())) continue;
            Location l = p.getLocation();
            Vector chest = new Vector(l.getX(), l.getY() + 0.9, l.getZ()), feet = new Vector(l.getX(), l.getY() + 0.2, l.getZ());
            double dBob = Math.min(segDist(chest, bobPrev, bob), segDist(feet, bobPrev, bob));
            double dBlade = Math.min(segDist(chest, bladePrev, blade), segDist(feet, bladePrev, blade));
            if (dBob <= BOB_R + 0.35 || dBlade <= BLADE_R + 0.35) {
                hitPass.add(p.getUniqueId());
                hit(p, SWEEP_DMG, "cause_g152");
                knock(p, f, 1.4, 0.5);
            } else if (low && Math.hypot(l.getX() - blade.getX(), l.getZ() - blade.getZ()) <= DUST_R && l.getY() < fl + 2.5) {
                hitPass.add(p.getUniqueId());
                hit(p, DUST_DMG, "cause_g152_dust");
            }
        }
        if (low && !truckPass) {
            Truck t = Truck.get();
            if (t != null && Truck.dist(blade.getX(), blade.getZ()) <= 1.5) {
                truckPass = true;
                t.hitFrac(TRUCK_FRAC, this, "cause_g152");
            }
        }
    }

    /** 점 p 에서 선분 a-b 까지 거리. */
    private static double segDist(Vector p, Vector a, Vector b) {
        double abx = b.getX() - a.getX(), aby = b.getY() - a.getY(), abz = b.getZ() - a.getZ();
        double l2 = abx * abx + aby * aby + abz * abz;
        double t = l2 < 1e-9 ? 0 : ((p.getX() - a.getX()) * abx + (p.getY() - a.getY()) * aby + (p.getZ() - a.getZ()) * abz) / l2;
        t = Math.max(0, Math.min(1, t));
        double x = a.getX() + abx * t - p.getX(), y = a.getY() + aby * t - p.getY(), z = a.getZ() + abz * t - p.getZ();
        return Math.sqrt(x * x + y * y + z * z);
    }

    private void dustFx() {
        Location at = new Location(game.world, blade.getX(), floor() + 0.6, blade.getZ());
        ItemDisplay sm = game.shotFx.spawn(at, "fx_quad", ShotFx.SMOKE, 1, ShotFx.scale(2.5f), Display.Billboard.CENTER, false, 30, ShotFx.EXTRA);
        game.shotFx.grow(sm, ShotFx.scale(6f), 26);
        game.shotFx.drift(sm, at.clone().add(0, 1.5, 0), 26);
    }

    /** 바닥을 스친 뒤 10초마다: 지금 방향 ±30 도 가운데 길 둘레(3.5블록) 사람이 더 많은 쪽을 2초 전에 띠로 보이고 바닥을 지날 때 바꿈. */
    private void planTurn() {
        if (!skimming || pendingYaw != null || now() < turnAt) return;
        turnAt = now() + TURN_EVERY;
        int a = pathCount(dirYaw + 30), b = pathCount(dirYaw - 30);
        float to = a == b ? dirYaw + (game.random.nextBoolean() ? 30 : -30) : a > b ? dirYaw + 30 : dirYaw - 30;
        pendingYaw = to;
        if (band != null) {
            band.state("next");
            poseBand(to, 2);
        }
        sound("tick", Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 96);
    }

    private int pathCount(float yawDeg) {
        double a = Math.toRadians(yawDeg), fx = -Math.sin(a), fz = Math.cos(a);
        int n = 0;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - piv.getX(), dz = l.getZ() - piv.getZ();
            double along = dx * fx + dz * fz, across = Math.abs(-dx * fz + dz * fx);
            if (Math.abs(along) <= HALF_SWEEP + 2 && across <= 3.5) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ 덩이

    private void dropLumps() {
        Vector g = new Vector(bob.getX(), floor(), bob.getZ());
        List<Player> near = new ArrayList<>();
        for (Player p : game.mobs.standing()) if (G148Sky.flat(g, p.getLocation()) <= 18) near.add(p);
        near.sort((x, y) -> Double.compare(G148Sky.flat(g, x.getLocation()), G148Sky.flat(g, y.getLocation())));
        for (int i = 0; i < Math.min(2, near.size()); i++) {
            Location l = near.get(i).getLocation();
            lumps.add(new double[] {now() + LUMP_FALL, l.getX(), l.getZ()});
            Location from = new Location(game.world, bob.getX(), bob.getY() - 2.4, bob.getZ());
            ItemDisplay d = G148Sky.fx(game, from, "gabe/g152_lump", "", G148Sky.lay("gabe/g152_lump", game.random.nextFloat() * 360, 1.5f, 1.5f, 1.5f),
                    LUMP_FALL + 4);
            game.shotFx.drift(d, new Location(game.world, l.getX(), floor() + 0.7, l.getZ()), LUMP_FALL);
        }
    }

    private void tickLumps() {
        long now = now();
        for (Iterator<double[]> it = lumps.iterator(); it.hasNext();) {
            double[] lp = it.next();
            if (now < lp[0]) continue;
            it.remove();
            double fl = floor();
            for (Player p : G148Sky.standing(game)) {
                Location l = p.getLocation();
                if (Math.hypot(l.getX() - lp[1], l.getZ() - lp[2]) <= 1.5 && l.getY() < fl + 3) hit(p, LUMP_DMG, "cause_g152_lump");
            }
            game.shotFx.pop(new Location(game.world, lp[1], fl + 0.5, lp[2]));
        }
    }

    // ------------------------------------------------------------------ 떨어진 얼굴

    /** 추대가 다 끊김: 얼굴이 떨어져 지나가던 쪽으로 구른다. */
    private void detach() {
        Vector f = fwd().multiply(side);
        rollDir.copy(f);
        pos.setX(bob.getX()).setY(Math.max(floor() + HALF + 0.3, bob.getY())).setZ(bob.getZ());
        if (body != null) body.warp();
        if (arm != null) {
            arm.remove();
            arm = null;
        }
        if (band != null) {
            band.remove();
            band = null;
        }
        if (pendingYaw != null) pendingYaw = null;
        yaw = yawTo(new Vector(0, 0, 0), f);
        pitch = 0;
        rollAng = 0;
        if (face != null) face.pose(new Matrix4f(), 2);
        body.pose(2);
        sound("crash", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 96);
        G148Sky.quake(game, new Location(game.world, pos.getX(), floor(), pos.getZ()), 6f);
        game.horizon.shake(0.6, 12);
        hitPass.clear();
        phase(Phase.ROLL);
    }

    private void roll() {
        double want = floor() + HALF + 0.3;
        int t = inPhase();
        double v = 0.32 * (1 - t / (double) ROLL_T) * speed() / baseSpeed;
        vel.setX(rollDir.getX() * v).setZ(rollDir.getZ() * v).setY(Math.max(-1, Math.min(1, want - pos.getY())));
        rollAng += (float) Math.toDegrees(v / HALF);
        if (face != null && every(2)) face.pose(new Matrix4f().rotateX((float) Math.toRadians(rollAng)), 2);
        for (Player p : G148Sky.standing(game)) {
            if (hitPass.contains(p.getUniqueId())) continue;
            if (pos.distance(p.getLocation().toVector().add(new Vector(0, 0.9, 0))) <= HALF + 0.9) {
                hitPass.add(p.getUniqueId());
                hit(p, ROLL_DMG, "cause_g152");
                knock(p, rollDir, 0.9, 0.35);
            }
        }
        if (t >= ROLL_T) {
            if (face != null) face.pose(new Matrix4f().rotateX((float) Math.toRadians(Math.round(rollAng / 90f) * 90f)), 4);
            phase(Phase.REST);
        }
    }

    private void rest() {
        double want = floor() + HALF + 0.3;
        vel.setX(0).setZ(0).setY(Math.max(-1, Math.min(1, want - pos.getY())));
        if (inPhase() >= cd(REST_T)) {
            Player p = nearest(400);
            if (p != null) {
                rollDir.copy(p.getLocation().toVector().subtract(pos).setY(0));
                if (rollDir.lengthSquared() < 1e-4) rollDir.setX(1);
                rollDir.normalize();
                mark(p);
            }
            hitPass.clear();
            phase(Phase.ROLL);
        }
    }

    /** 잡힘(매달린 채): 추대가 끊겨 얼굴이 떨어져 부서짐. 굴러다니다 잡히면 바로. */
    private void fall() {
        untargetable = true;
        int t = inPhase();
        double fl = floor();
        double y = Math.max(fl + HALF, pos.getY() - (0.4 + t * 0.1));
        vel.setX(0).setZ(0).setY(y - pos.getY());
        if (y <= fl + HALF + 0.05 || t > 80) {
            Location at = new Location(game.world, pos.getX(), fl, pos.getZ());
            G148Sky.quake(game, at, 6f);
            sound("crash", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 96);
            game.horizon.shake(0.5, 10);
            untargetable = false;
            die(last != null ? last : new Damage(0, DamageType.OTHER, null, pos.clone(), null, "fall", 0));
        }
    }

    // ------------------------------------------------------------------ 몸

    private void poseParts(int ticks) {
        if (phase == Phase.ROLL || phase == Phase.REST || phase == Phase.FALL) return;
        if (face != null) face.pose(new Matrix4f().translate(0, (float) -len, 0).rotateY((float) Math.toRadians(faceSpin)), ticks);
        if (arm != null) arm.pose(new Matrix4f().translate(0, (float) (-len + HALF + 17), 0), ticks);
        if (band != null && pendingYaw == null) poseBand(dirYaw, ticks);
    }

    private void poseBand(float yawDeg, int ticks) {
        if (band == null) return;
        float fy = (float) (floor() + 0.05 - pos.getY());
        band.pose(new Matrix4f().translate(0, fy, 0).rotateY((float) Math.toRadians(-yawDeg)).scale(2.5f, 1f, (float) HALF_SWEEP + 1f), ticks);
    }

    private void poseShadow() {
        if (shadow == null) return;
        Vector at = phase == Phase.ROLL || phase == Phase.REST || phase == Phase.FALL ? pos : bob;
        float fy = (float) (floor() + 0.07 - pos.getY());
        shadow.pose(new Matrix4f().translate((float) (at.getX() - pos.getX()), fy, (float) (at.getZ() - pos.getZ())).scale(2.7f, 1f, 2.7f), 2);
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: 0 얼굴, 1 칼날, 2 추대(8~20블록 구간). */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(10);
        if (phase == Phase.ROLL || phase == Phase.REST || phase == Phase.FALL) {
            out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), HALF + 0.3, 0});
        } else {
            out.add(new double[] {bob.getX(), bob.getY(), bob.getZ(), BOB_R, 0});
            out.add(new double[] {blade.getX(), blade.getY(), blade.getZ(), BLADE_R, 1});
            if (arm != null) {
                Vector up = piv.clone().subtract(bob).normalize();
                for (double k = HALF + 8; k <= HALF + 20; k += 2.4) {
                    out.add(new double[] {bob.getX() + up.getX() * k, bob.getY() + up.getY() * k, bob.getZ() + up.getZ() * k, 1.2, 2});
                }
            }
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || phase == Phase.FALL) return false;
        int part = G148Sky.partAt(spheres(), d.at());
        boolean down = phase == Phase.REST || phase == Phase.ROLL;
        Damage dd = down ? d.times(2) : d;
        double amt = dd.amount();
        hp -= amt;
        game.mobs.onHurt(this, dd);
        if (part == 2 && arm != null && d.at() != null) {
            double a = Math.toRadians(yaw);
            double lx = Math.cos(a), lz = Math.sin(a);
            Vector rel = d.at().clone().subtract(bob);
            int rod = rel.getX() * lx + rel.getZ() * lz >= 0 ? 0 : 1;
            if (armHp[rod] <= 0) rod = 1 - rod;
            if (armHp[rod] > 0) {
                armHp[rod] -= amt;
                if (armHp[rod] <= 0) {
                    arm.look.on.add(rod == 0 ? "cut_l" : "cut_r");
                    sound("snap", Sound.ENTITY_ITEM_BREAK, 0.6f, 64);
                    if (armHp[0] <= 0 && armHp[1] <= 0 && hp > 0) detach();
                    else ampK = 0.5;
                }
            }
        }
        if (hp <= 0) {
            last = dd;
            mark(null);
            if (phase != Phase.ROLL && phase != Phase.REST) {
                pos.setX(bob.getX()).setY(bob.getY()).setZ(bob.getZ());
                if (body != null) body.warp();
                if (arm != null) {
                    arm.remove();
                    arm = null;
                }
                if (band != null) {
                    band.remove();
                    band = null;
                }
                if (face != null) face.pose(new Matrix4f(), 2);
                pitch = 0;
                body.pose(2);
                sound("snap", Sound.ENTITY_ITEM_BREAK, 0.6f, 64);
                phase(Phase.FALL);
            } else {
                Location at = new Location(game.world, pos.getX(), floor(), pos.getZ());
                G148Sky.quake(game, at, 5f);
                die(dd);
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " len=" + Math.round(len * 10) / 10.0 + " th=" + Math.round(th) + " dir=" + Math.round(dirYaw) + " passes="
                + passes + " skim=" + skimming + " arms=" + (armHp[0] > 0 ? 1 : 0) + (armHp[1] > 0 ? 1 : 0) + " bobY="
                + Math.round((bob.getY() - floor()) * 10) / 10.0;
    }
}
