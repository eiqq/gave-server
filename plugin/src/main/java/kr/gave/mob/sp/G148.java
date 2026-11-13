package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Theme;
import kr.gave.trip.Truck;

/**
 * G148 내려앉는 종 게이브(4막 33라운드, 돌격, 튼튼이, 14블록). 아가리 지름 14, 높이 12블록의 큰 종이 트럭 위 하늘 40블록에 나타나 그림자로 트럭 둘레를
 * 덮고, 22초(박자 배율, 15초 아래로는 안 줄어듦)에 걸쳐 흔들리며 내려앉는다. 땅에 닿으면 밑에 든 사람에게 남은 체력 몫만큼 최대 90 피해와 2초 깔림,
 * 1초 뒤 종이 한 번 울려 15 더(둘레 14블록 5). 트럭이 밑에 있으면 트럭 최대 체력의 18% x 남은 체력 몫. 그 뒤 부서진다(보상 없음).
 * 내려오는 동안 테에서 흘러내린 얼굴 가루가 작은 얼굴 떼(G04 다섯, 보상 0)로 떨어진다.
 *
 * <p>대처: 한쪽 테 조각만 몰아 쏜다. 테 조각마다 깎인 몫 x 3블록만큼 종이 반대쪽으로 밀려 내려앉을 자리(그림자)가 옮겨 간다(조각 여덟, 조각 체력 200 x
 * 감독 체력 배율). 다 깎인 조각은 떨어져 나가고 그쪽이 들린다. 아가리 속 얼굴은 1.5배로 맞아 남은 체력(= 낙하 피해)을 빨리 깎는다. 통나무 막대기를 맞히면
 * 종이 크게 흔들려 2초 멈춘다(5초에 한 번). 그림자 가장자리로 뛴다.
 *
 * <p>몸: {@link Body#carrier} + 부품(종 몸 gave:gabe/g148, 부위 e1..e8; 통나무 g148_log; 바닥 그림자 g148_shadow 세계 축, s1..s4).
 * Gabe.pos = 아가리 아랫면 가운데(바닥 + {@link #h}). 사체 없음(부서짐).
 */
public final class G148 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 아가리 아랫면 높이(바닥 위): 나타날 때. */
    static final double ALT = 40;
    /** 아가리 반지름(그림자, 깔림), 테 조각 자리 반지름, 종 높이 가운데(모델 가운데 = 아가리 + 6). */
    static final double RIM_R = 7.35, RIM_AT = 6.7, MID = 6.0;
    /** 몸 자리(pos)가 아가리보다 이만큼 위(하늘 몸도 바닥 + 0.3 아래로는 엔진이 막음: 아가리가 땅에 닿게). */
    static final double LIFT = 0.3;
    /** 통나무 매단 곳(아가리 기준, g148.py LOG_AT + 6). */
    static final float[] LOG_AT = {0f, 6.6f, 6.9f};
    /** 내려오는 틱(박자 배율, 아래 끝), 깔림, 울림까지, 통나무 멈춤과 재사용, 가루 떼 간격. */
    static final int DESCEND = 440, DESCEND_MIN = 300, PIN = 40, TOLL_AFTER = 20, STALL = 40, STALL_CD = 100, DUST_EVERY = 100;
    /** 피해: 깔림 최대, 울림(밑), 울림(둘레 TOLL_R 안). 트럭 몫. */
    static final double CRUSH = 90, CRUSH_MIN = 12, TOLL_DMG = 15, TOLL_NEAR = 5, TOLL_R = 14, TRUCK_FRAC = 0.18;
    /** 테 조각 하나 체력(감독 체력 배율을 곱함), 조각 하나가 다 깎이면 밀리는 블록, 밀리는 빠르기(블록/틱). */
    static final double RIM_HP = 200, PUSH = 3.0, DRIFT_V = 0.12, ARRIVE_V = 0.55;
    /** 가루 떼 한 번에 떨어지는 수. */
    static final int DUST_N = 5;

    enum Phase { ARRIVE, DESCEND, LANDED, FALL }

    private Phase phase = Phase.ARRIVE;
    private int phaseAt;
    /** 지금 아가리 높이(바닥 위). */
    private double h = ALT;
    /** 내려온 진행(틱, 멈춘 동안은 안 늘어남), 내려오는 데 걸릴 틱. */
    private double prog;
    private int descendTicks = DESCEND;
    private final double[] rimHp = new double[8];
    private double rimMax;
    private boolean init;
    /** 내려앉을 바탕 자리(트럭 가운데 등)와 지금 자리(밀림 더함). */
    private final Vector base = new Vector(), spot = new Vector();
    private long stallUntil = Long.MIN_VALUE / 2, stallReady = Long.MIN_VALUE / 2, nextToll, nextDust, logBack = -1;
    private Body.Part bell, log, shadow;
    private int dark;
    private float tiltP, tiltR;
    private Damage last;
    private List<double[]> sph;
    private int sphAge = -1;
    private boolean announced;
    private float fallSpin;

    public G148(Game game, Species sp) {
        super(game, sp);
        radius = 3.0;
        baseSpeed = ARRIVE_V;
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
        bell = body.attach("gabe/g148");
        if (bell != null) bell.pose(new Matrix4f().translate(0, (float) (MID - LIFT), 0), 0);
        log = body.attach("gabe/g148_log");
        poseLog(-18f, 0);
        G148Sky.bright(bell, log);
        shadow = body.attach("gabe/g148_shadow");
        if (shadow != null) {
            shadow.follow = false;
            shadow.state("s1");
            dark = 1;
        }
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pos.setY(floor() + ALT);
        pickBase();
        spot.copy(base);
        yaw = 0;
        pitch = 0;
    }

    @Override
    public void lure(Vector at, int ticks) {
        // 거대한 종은 딸랑이에 끌리지 않는다
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 내려앉을 바탕: 트럭이 있으면 트럭 가운데, 없으면 사람이 가장 많이 모인 자리. */
    private void pickBase() {
        if (Truck.live()) {
            base.copy(G148Sky.center());
            return;
        }
        Player p = G148Sky.densest(game, 8, pos);
        if (p != null) base.setX(p.getLocation().getX()).setZ(p.getLocation().getZ());
        else base.copy(G148Sky.center());
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            rimMax = RIM_HP * G148Sky.hpK(this);
            for (int i = 0; i < 8; i++) rimHp[i] = rimMax;
        }
        switch (phase) {
            case ARRIVE -> arrive();
            case DESCEND -> descend();
            case LANDED -> landed();
            case FALL -> fall();
        }
        poseShadow();
    }

    /** 높이 h 에 맞춰 pos.y 를 옮김(vel.y). */
    private void holdHeight() {
        double want = floor() + h + LIFT;
        vel.setY(Math.max(-1.5, Math.min(1.5, want - pos.getY())));
    }

    /** 수평으로 to 쪽 v 로. 다 왔으면 true. */
    private boolean flyTo(Vector to, double v) {
        double dx = to.getX() - pos.getX(), dz = to.getZ() - pos.getZ(), l = Math.hypot(dx, dz);
        if (l < 0.15) {
            vel.setX(0).setZ(0);
            return true;
        }
        double s = Math.min(v, l) / l;
        vel.setX(dx * s).setZ(dz * s);
        return l < 0.8;
    }

    private void arrive() {
        h = ALT;
        holdHeight();
        if (!Truck.live() && every(20)) pickBase();
        spot.copy(base);
        if (!announced && G148Sky.flat(pos, base) < 70) {
            announced = true;
            Props.soundAround(loc(), "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.8f, 0.5f, 160, -1);
        }
        if (flyTo(base, ARRIVE_V * speed() / baseSpeed) || inPhase() > 600) {
            descendTicks = Math.max(DESCEND_MIN, cd(DESCEND));
            prog = 0;
            nextToll = now();
            nextDust = now() + 40;
            if (bell != null) bell.look.open = true;
            Player p = G148Sky.densest(game, 10, pos);
            if (p != null) mark(p);
            phase(Phase.DESCEND);
        }
        sway(2.5f, 60);
    }

    private void descend() {
        long now = now();
        boolean stalled = now < stallUntil;
        if (!stalled) prog += 1;
        double f = Math.min(1, prog / descendTicks);
        h = ALT * (1 - f);
        holdHeight();
        // 내려앉을 자리 = 바탕 + 테가 깎인 쪽의 반대로 밀림
        spot.copy(base).add(push());
        flyTo(spot, DRIFT_V);
        sway(stalled ? 9f : 2.5f, stalled ? 10 : 60);
        // 종소리: 낮을수록 잦게
        if (now >= nextToll) {
            nextToll = now + Math.round(14 + 46 * (h / ALT));
            logBack = now + 6;
            sound("toll", Sound.BLOCK_BELL_USE, 0.5f, 96);
            poseLog(4f, 4);
        } else if (now == logBack) {
            poseLog(-18f, 10);
        }
        // 어둡게, 높이 게이지
        if (every(10)) {
            double frac = h / ALT;
            for (Player p : game.players.inPlay()) game.hud.meter(p, "g148", frac, 16);
            Theme th = game.trip == null ? null : game.trip.part(Theme.class);
            if (th != null) th.dim(0.3 + 0.55 * (1 - frac), 24);
            darkness(frac > 0.75 ? 1 : frac > 0.5 ? 2 : frac > 0.25 ? 3 : 4);
        }
        // 흘러내린 가루가 작은 얼굴 떼로
        if (now >= nextDust && h > 6) {
            nextDust = now + cd(DUST_EVERY);
            dust();
        }
        if (h <= 0.02) impact();
    }

    /** 테가 깎인 몫만큼 반대쪽으로 밀린 거리(수평). */
    private Vector push() {
        double x = 0, z = 0;
        for (int i = 0; i < 8; i++) {
            double lost = 1 - Math.max(0, rimHp[i]) / rimMax;
            double a = Math.toRadians(45.0 * i + 22.5);
            x -= Math.sin(a) * lost * PUSH;
            z -= Math.cos(a) * lost * PUSH;
        }
        return new Vector(x, 0, z);
    }

    /** 흔들림(진폭 amp 도, 주기 period 틱) + 테가 떨어진 쪽이 들림. 8틱마다 자세를 보냄. */
    private void sway(float amp, int period) {
        if (!every(8) && phase != Phase.FALL) return;
        double t = age * 2 * Math.PI / period;
        float sp = (float) (amp * Math.sin(t)), sr = (float) (amp * 0.7 * Math.cos(t * 0.77));
        // 떨어진 테 쪽이 들림
        double bx = 0, bz = 0;
        int n = 0;
        for (int i = 0; i < 8; i++) {
            if (rimHp[i] > 0) continue;
            double a = Math.toRadians(45.0 * i + 22.5);
            bx += Math.sin(a);
            bz += Math.cos(a);
            n++;
        }
        float lift = Math.min(14f, n * 3.5f);
        double bl = Math.hypot(bx, bz);
        tiltP = bl < 1e-3 ? 0 : (float) (-lift * bz / bl);
        tiltR = bl < 1e-3 ? 0 : (float) (lift * bx / bl);
        pitch = tiltP + sp;
        body.roll = tiltR + sr;
        body.pose(8);
    }

    private void poseLog(float swingDeg, int ticks) {
        if (log == null) return;
        log.pose(new Matrix4f().translate(LOG_AT[0], (float) (LOG_AT[1] - LIFT), LOG_AT[2]).rotateX((float) Math.toRadians(swingDeg)), ticks);
    }

    private void dust() {
        double fl = floor();
        for (int i = 0; i < DUST_N; i++) {
            double a = game.random.nextDouble() * Math.PI * 2, r = RIM_R * (0.6 + 0.4 * game.random.nextDouble());
            Vector at = new Vector(pos.getX() + Math.sin(a) * r, Math.min(pos.getY(), fl + 26), pos.getZ() + Math.cos(a) * r);
            Gabe c = child(Species.G04, at);
            if (c == null) break;
        }
        sound("crack", Sound.BLOCK_GRAVEL_BREAK, 0.6f, 48);
    }

    /** 땅에 닿음: 밑에 든 사람 깔림, 트럭, 땅울림. */
    private void impact() {
        h = 0;
        vel.zero();
        double frac = Math.max(0, hpFrac());
        double fl = floor();
        for (Player p : G148Sky.standing(game)) {
            Location l = p.getLocation();
            if (l.getY() > fl + 4) continue;
            if (G148Sky.flat(pos, l) <= RIM_R) {
                hit(p, Math.max(CRUSH_MIN, CRUSH * frac), "cause_g148");
                game.players.root(p, PIN, "g148");
            }
        }
        Truck t = Truck.get();
        if (t != null && Truck.dist(pos.getX(), pos.getZ()) < RIM_R) t.hitFrac(TRUCK_FRAC * Math.max(0.15, frac), this, "cause_g148");
        Location at = new Location(game.world, pos.getX(), fl, pos.getZ());
        sound("crash", Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 96);
        Props.soundAround(at, "sfx.quake", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f, 0.6f, 128, -1);
        game.horizon.shake(1.2, 20);
        G148Sky.quake(game, at, (float) (RIM_R + 3));
        pitch = 0;
        body.roll = 0;
        body.pose(4);
        darkness(4);
        phase(Phase.LANDED);
    }

    private void landed() {
        vel.zero();
        h = 0;
        holdHeight();
        if (inPhase() == TOLL_AFTER) {
            sound("toll", Sound.BLOCK_BELL_USE, 0.4f, 96);
            sound("ring", Sound.BLOCK_BELL_RESONATE, 0.5f, 64);
            poseLog(6f, 3);
            double fl = floor();
            for (Player p : G148Sky.standing(game)) {
                Location l = p.getLocation();
                if (l.getY() > fl + 4) continue;
                double d = G148Sky.flat(pos, l);
                if (d <= RIM_R) hit(p, TOLL_DMG, "cause_g148");
                else if (d <= TOLL_R) hit(p, TOLL_NEAR, "cause_g148");
            }
            game.horizon.shake(0.6, 16);
        }
        if (inPhase() >= TOLL_AFTER + 16) {
            // 부서진다(사람이 잡은 것이 아니므로 보상 없음)
            noReward = true;
            reward = 0;
            sound("crack", Sound.BLOCK_ANVIL_BREAK, 0.6f, 64);
            Location at = new Location(game.world, pos.getX(), floor(), pos.getZ());
            G148Sky.quake(game, at, (float) RIM_R);
            die(new Damage(0, DamageType.OTHER, null, pos.clone(), null, "landed", 0));
        }
    }

    /** 잡힘: 그 자리에서 기울며 떨어져 부서짐(피해 없음). */
    private void fall() {
        untargetable = true;
        int t = inPhase();
        h = Math.max(0, h - (0.4 + t * 0.08));
        holdHeight();
        vel.setX(0).setZ(0);
        fallSpin += 2.2f;
        pitch = tiltP + fallSpin;
        body.roll = tiltR + fallSpin * 0.4f;
        body.pose(2);
        if (h <= 0.05 || t > 120) {
            Location at = new Location(game.world, pos.getX(), floor(), pos.getZ());
            sound("crash", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 96);
            G148Sky.quake(game, at, (float) RIM_R);
            game.horizon.shake(0.8, 14);
            untargetable = false;
            Damage d = last != null ? last : new Damage(0, DamageType.OTHER, null, pos.clone(), null, "fall", 0);
            die(d);
        }
    }

    // ------------------------------------------------------------------ 그림자

    private void darkness(int n) {
        if (shadow == null || n == dark) return;
        dark = n;
        shadow.state("s" + n);
    }

    private float shDy = Float.NaN;

    private void poseShadow() {
        if (shadow == null) return;
        float fy = (float) (floor() + 0.03 - pos.getY());
        if (Math.abs(fy - shDy) < 0.05f) return;
        shDy = fy;
        float s = (float) RIM_R;
        shadow.pose(new Matrix4f().translate(0, fy, 0).scale(s, 1, s), 3);
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: 0..7 테 조각, 8 아가리 속 얼굴, 9 통나무, 10.. 종 몸. 틱마다 한 번만 만든다(기울기 반영). */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(30);
        Matrix4f m = new Matrix4f().rotateX((float) Math.toRadians(pitch)).rotateZ((float) Math.toRadians(body == null ? 0 : body.roll));
        for (int i = 0; i < 8; i++) {
            if (rimHp[i] <= 0 && init) continue;
            double a = Math.toRadians(45.0 * i + 22.5);
            add(out, m, Math.sin(a) * RIM_AT, 0.65, Math.cos(a) * RIM_AT, 1.7, i);
        }
        add(out, m, 0, 9.4, 0, 2.7, 8);
        add(out, m, LOG_AT[0], LOG_AT[1] - 4, LOG_AT[2], 1.3, 9);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(45.0 * i);
            add(out, m, Math.sin(a) * 5.5, 3.6, Math.cos(a) * 5.5, 2.0, 10);
            add(out, m, Math.sin(a) * 4.8, 7.4, Math.cos(a) * 4.8, 2.0, 10);
        }
        add(out, m, 0, 11.4, 0, 2.6, 10);
        sph = out;
        sphAge = age;
        return out;
    }

    private void add(List<double[]> out, Matrix4f m, double x, double y, double z, double r, int part) {
        Vector3f v = m.transformPosition(new Vector3f((float) x, (float) (y - LIFT), (float) z));
        out.add(new double[] {pos.getX() + v.x, pos.getY() + v.y, pos.getZ() + v.z, r, part});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || phase == Phase.FALL || phase == Phase.LANDED) return false;
        int part = G148Sky.partAt(spheres(), d.at());
        Damage dd = part == 8 ? d.times(1.5) : d;
        double amt = dd.amount();
        hp -= amt;
        game.mobs.onHurt(this, dd);
        if (part >= 0 && part < 8 && rimHp[part] > 0) {
            rimHp[part] -= amt;
            if (rimHp[part] <= 0) {
                if (bell != null) bell.look.on.add("e" + (part + 1));
                sph = null;
                sound("crack", Sound.BLOCK_ANVIL_BREAK, 0.8f, 48);
                double a = Math.toRadians(45.0 * part + 22.5);
                Location at = new Location(game.world, pos.getX() + Math.sin(a) * RIM_AT, pos.getY() + 0.6, pos.getZ() + Math.cos(a) * RIM_AT);
                game.shotFx.pop(at);
            }
        } else if (part == 9 && now() >= stallReady) {
            stallUntil = now() + STALL;
            stallReady = now() + cd(STALL_CD) + STALL;
            sound("ring", Sound.BLOCK_BELL_RESONATE, 0.7f, 64);
            poseLog(-38f, 4);
        }
        if (hp <= 0) {
            last = dd;
            if (bell != null) bell.look.open = false;
            mark(null);
            phase(Phase.FALL);
        }
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 감독 정리(웨이브 끝)나 시험 죽이기: 바로 치움
        for (Player p : game.players.inPlay()) game.hud.meter(p, "g148", -1, 0);
        super.die(d);
    }

    @Override
    public String toString() {
        int broken = 0;
        for (double r : rimHp) if (r <= 0) broken++;
        Vector pu = push();
        return super.toString() + " ph=" + phase + " h=" + Math.round(h * 10) / 10.0 + " prog=" + (int) prog + "/" + descendTicks + " broken=" + broken
                + " push=" + Math.round(pu.length() * 10) / 10.0 + " spot=" + Math.round(spot.getX()) + "," + Math.round(spot.getZ());
    }
}
