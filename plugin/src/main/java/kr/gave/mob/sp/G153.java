package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;

/**
 * G153 하늘 찢는 게이브(4막 34라운드, 지원, 원거리, 16블록). 트럭에서 R 30 쯤 하늘(12~28블록)에 가는 금이 먼저 생기고(3초, 하늘 찢어지는 소리),
 * 문지기 얼굴 둘이 금에서 기어 나와 양쪽으로 당겨 틈을 벌린다(3초). 벌어진 만큼 큰 것이 넘어온다: 처음 10초는 작은 얼굴 비(8초마다 여섯: 떨어진 자리
 * 반경 1 안 6, 그 자리에 작은 얼굴 떼 G04), 그다음은 그 웨이브의 보통 종(디스플레이 점수 2 이하, 고리에서 오는 종)이 틈에서 떨어지고(6초마다 둘),
 * 30초를 넘기면 5초마다 셋. 넘어온 것은 보상 0(child). 문지기는 4초(박자)마다 가장 가까운 세 사람에게 덩이를 뱉게 한다(반경 1.5 안 14).
 * 틈 바로 아래(반경 4)에 서 있으면 0.5초마다 5.
 *
 * <p>대처: 버팀목 막대기 둘(체력 각 300 x 감독 체력 배율)을 모두 부수거나 문지기 하나를 잡으면 틈이 반쯤 닫혀 넘어오는 것이 한 단계 준다(둘 다면 두 단계).
 * 문지기 둘(체력 = 최대 체력의 절반씩)을 다 잡으면 틈이 닫히며 걸쳐 있던 것이 잘려 떨어진다(죽음). 틈 몸을 쏘면 절반만 들어간다. 일찍 닫을수록 넘어온 것이 적다.
 *
 * <p>몸: {@link Body#carrier} + 부품(틈 g153: 폭을 벌어진 몫으로, 문지기 g153_keeper 둘, 버팀목 g153_brace 둘, 바닥 그림자 줄 g153_line 세계 축).
 * 얼굴 비(g153_drop)와 덩이(g153_spit)는 연출 디스플레이. Gabe.pos = 틈 가운데(바닥 + 20), 몸 yaw = 트럭 쪽. 사체 없음.
 */
public final class G153 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double ALT = 20, RING_R = 30, HALF_W = 2.5, UNDER_R = 4;
    /** 틱: 금, 벌어짐, 단계 경계(작은 얼굴 비 → 보통 종 → 셋씩), 간격(비, 보통, 셋), 뱉기(박자), 덩이 날아가는 틱, 얼굴 비 떨어지는 틱, 닫힘. */
    static final int CRACK_T = 60, OPEN_T = 60, STAGE2 = 200, STAGE3 = 600, RAIN_EVERY = 160, NORMAL_EVERY = 120, LATE_EVERY = 100, SPIT_EVERY = 80,
            SPIT_FLY = 20, RAIN_FALL = 18, CLOSE_T = 24;
    /** 피해: 덩이, 얼굴 비, 틈 아래(10틱마다). 버팀목 체력. 얼굴 비 수. */
    static final double SPIT_DMG = 28, RAIN_DMG = 12, UNDER_DMG = 10, BRACE_HP = 300;
    static final int RAIN_N = 6;

    enum Phase { CRACK, OPEN, ACTIVE, CLOSE }

    private Phase phase = Phase.CRACK;
    private int phaseAt, activeAt;
    private double open = 0.04;
    private final double[] keeperHp = new double[2];
    private final double[] braceHp = new double[2];
    private boolean init, closedHalf;
    private Body.Part rift, line;
    private final Body.Part[] keepers = new Body.Part[2], braces = new Body.Part[2];
    private long nextSpawn, nextSpit;
    /** 날아가는 것: [닿는 틱, x, z, 종류 0 덩이 / 1 얼굴 비]. */
    private final List<double[]> shots = new ArrayList<>();
    private int crossed;
    private Damage last;
    private List<double[]> sph;
    private int sphAge = -1;

    public G153(Game game, Species sp) {
        super(game, sp);
        radius = 2.0;
        baseSpeed = 0.1;
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
        rift = body.attach("gabe/g153");
        for (int i = 0; i < 2; i++) keepers[i] = body.attach("gabe/g153_keeper");
        for (int i = 0; i < 2; i++) braces[i] = body.attach("gabe/g153_brace");
        G148Sky.bright(rift);
        G148Sky.bright(keepers);
        G148Sky.bright(braces);
        line = body.attach("gabe/g153_line");
        if (line != null) line.follow = false;
        poseAll(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Vector c = G148Sky.center();
        double a = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
        if (!Double.isFinite(a)) a = game.random.nextDouble() * Math.PI * 2;
        pos.setX(c.getX() + Math.cos(a) * RING_R).setZ(c.getZ() + Math.sin(a) * RING_R);
        pos.setY(floor() + ALT);
        if (body != null) body.warp();
        yaw = (float) Math.toDegrees(Math.atan2(-(c.getX() - pos.getX()), c.getZ() - pos.getZ()));
        pitch = 0;
        poseAll(0);
    }

    @Override
    public void lure(Vector at, int ticks) {
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
        if (!init) {
            init = true;
            keeperHp[0] = keeperHp[1] = maxHp * 0.5;
            braceHp[0] = braceHp[1] = BRACE_HP * G148Sky.hpK(this);
            sound("tear", Sound.ENTITY_WARDEN_SONIC_BOOM, 0.5f, 128);
            game.horizon.shake(0.4, 16);
        }
        vel.zero();
        pitch = 0;
        double want = floor() + ALT;
        vel.setY(Math.max(-1, Math.min(1, want - pos.getY())));
        switch (phase) {
            case CRACK -> {
                open = 0.04 + 0.04 * Math.sin(age * 0.6);
                if (every(4)) poseAll(4);
                if (inPhase() >= CRACK_T) {
                    sound("tear", Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 128);
                    phase(Phase.OPEN);
                }
            }
            case OPEN -> {
                open = 0.08 + 0.92 * Math.min(1, inPhase() / (double) OPEN_T);
                if (every(3)) poseAll(3);
                if (inPhase() >= OPEN_T) {
                    activeAt = age;
                    nextSpawn = now() + 20;
                    nextSpit = now() + 40;
                    Player p = nearest(400);
                    if (p != null) mark(p);
                    for (Body.Part k : keepers) if (k != null) k.look.open = true;
                    phase(Phase.ACTIVE);
                }
            }
            case ACTIVE -> active();
            case CLOSE -> close();
        }
        tickFlying();
    }

    /** 반쯤 닫힌 단계 수(0..2): 버팀목 둘 다 부서짐 1, 문지기 하나 잡힘 1. */
    private int shut() {
        int n = 0;
        if (braceHp[0] <= 0 && braceHp[1] <= 0) n++;
        if (keeperHp[0] <= 0 || keeperHp[1] <= 0) n++;
        return n;
    }

    private void active() {
        long now = now();
        int t = age - activeAt;
        int stage = t < STAGE2 ? 0 : t < STAGE3 ? 1 : 2;
        int eff = stage - shut();
        double wantOpen = 1.0 - 0.3 * shut();
        if (Math.abs(open - wantOpen) > 0.01) {
            open += Math.signum(wantOpen - open) * 0.02;
            if (every(3)) poseAll(3);
        }
        if (now >= nextSpawn) {
            if (eff <= 0) {
                nextSpawn = now + Math.round(RAIN_EVERY * (eff < 0 ? 1.5 : 1));
                rain(eff < 0 ? RAIN_N / 2 : RAIN_N);
            } else if (eff == 1) {
                nextSpawn = now + NORMAL_EVERY;
                crossOver(2);
            } else {
                nextSpawn = now + LATE_EVERY;
                crossOver(3);
            }
        }
        if (now >= nextSpit) {
            nextSpit = now + Math.max(40, cd(SPIT_EVERY));
            spit();
        }
        if (every(10)) {
            double fl = floor();
            for (Player p : G148Sky.standing(game)) {
                Location l = p.getLocation();
                if (l.getY() < fl + 4 && Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) <= UNDER_R) hit(p, UNDER_DMG, "cause_g153_under");
            }
        }
    }

    /** 틈 아래 떨어질 자리(틈 아래 끝에서 조금 트럭 쪽). */
    private Vector mouth() {
        Vector f = G148Sky.local(0, 0, 1, yaw);
        return new Vector(pos.getX() + f.getX() * 1.5, pos.getY() - 7.5, pos.getZ() + f.getZ() * 1.5);
    }

    /** 작은 얼굴 비: 틈 아래 사람 쪽으로 n 개가 떨어져 반경 1 안 6, 그 자리에 작은 얼굴 떼. */
    private void rain(int n) {
        Vector m = mouth();
        Player p = nearest(400);
        double fl = floor();
        for (int i = 0; i < n; i++) {
            double tx, tz;
            if (p != null && i < n / 2) {
                tx = p.getLocation().getX() + game.random.nextGaussian() * 2.5;
                tz = p.getLocation().getZ() + game.random.nextGaussian() * 2.5;
            } else {
                Vector f = G148Sky.local(game.random.nextGaussian() * 4, 0, 4 + game.random.nextDouble() * 10, yaw);
                tx = pos.getX() + f.getX();
                tz = pos.getZ() + f.getZ();
            }
            int fall = RAIN_FALL + game.random.nextInt(8);
            shots.add(new double[] {now() + fall, tx, tz, 1});
            ItemDisplay d = G148Sky.fx(game, new Location(game.world, m.getX(), m.getY(), m.getZ()), "gabe/g153_drop", "",
                    G148Sky.lay("gabe/g153_drop", game.random.nextFloat() * 360, 1.2f, 1.2f, 1.2f), fall + 3);
            game.shotFx.drift(d, new Location(game.world, tx, fl + 0.6, tz), fall);
        }
        sound("drop", Sound.ENTITY_SLIME_SQUISH, 0.6f, 32);
    }

    /** 그 웨이브의 보통 종(점수 2 이하, 고리에서 오는 종)이 틈에서 떨어짐(보상 0). 없으면 작은 얼굴 떼. */
    private void crossOver(int n) {
        List<Species> pool = new ArrayList<>();
        try {
            for (Species s : game.director.waveSpecies()) {
                if (s == sp || s.is(Role.BOSS) || s.cost > 2 || s.spawn != Species.Spawn.RING || !s.implemented()) continue;
                pool.add(s);
            }
        } catch (RuntimeException e) {
            pool.clear();
        }
        Vector m = mouth();
        for (int i = 0; i < n; i++) {
            Species s = pool.isEmpty() ? Species.G04 : pool.get(game.random.nextInt(pool.size()));
            Vector at = m.clone().add(new Vector(game.random.nextGaussian() * 1.2, 0, game.random.nextGaussian() * 1.2));
            Gabe c = child(s, at);
            if (c == null) break;
            crossed++;
        }
        sound("tear", Sound.ENTITY_WARDEN_SONIC_BOOM, 0.9f, 128);
    }

    /** 가장 가까운 세 사람에게 덩이 하나씩. */
    private void spit() {
        List<Player> ps = new ArrayList<>(game.mobs.standing());
        Location me = loc();
        ps.removeIf(p -> p.getLocation().distanceSquared(me) > 64 * 64);
        ps.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(me), b.getLocation().distanceSquared(me)));
        Vector m = mouth().add(new Vector(0, 4, 0));
        double fl = floor();
        for (int i = 0; i < Math.min(3, ps.size()); i++) {
            Location l = ps.get(i).getLocation();
            shots.add(new double[] {now() + SPIT_FLY, l.getX(), l.getZ(), 0});
            ItemDisplay d = G148Sky.fx(game, new Location(game.world, m.getX(), m.getY(), m.getZ()), "gabe/g153_spit", "",
                    G148Sky.lay("gabe/g153_spit", game.random.nextFloat() * 360, 1.3f, 1.3f, 1.3f), SPIT_FLY + 3);
            game.shotFx.drift(d, new Location(game.world, l.getX(), fl + 0.8, l.getZ()), SPIT_FLY);
        }
        if (!ps.isEmpty()) sound("spit", Sound.ENTITY_LLAMA_SPIT, 0.6f, 48);
    }

    private void tickFlying() {
        long now = now();
        double fl = floor();
        for (Iterator<double[]> it = shots.iterator(); it.hasNext();) {
            double[] f = it.next();
            if (now < f[0]) continue;
            it.remove();
            boolean lump = f[3] == 0;
            double r = lump ? 1.5 : 1.0;
            for (Player p : G148Sky.standing(game)) {
                Location l = p.getLocation();
                if (l.getY() < fl + 3 && Math.hypot(l.getX() - f[1], l.getZ() - f[2]) <= r)
                    hit(p, lump ? SPIT_DMG : RAIN_DMG, lump ? "cause_g153" : "cause_g153_drop");
            }
            Location at = new Location(game.world, f[1], fl + 0.4, f[2]);
            game.shotFx.pop(at);
            if (!lump && phase == Phase.ACTIVE) {
                Gabe c = child(Species.G04, new Vector(f[1], fl + 0.5, f[2]));
                if (c != null) crossed++;
            }
        }
    }

    private void close() {
        open = Math.max(0, 1 - inPhase() / (double) CLOSE_T);
        if (every(2)) poseAll(2);
        if (inPhase() >= CLOSE_T) {
            Location at = new Location(game.world, pos.getX(), floor(), pos.getZ());
            G148Sky.quake(game, at, 5f);
            untargetable = false;
            die(last != null ? last : new Damage(0, DamageType.OTHER, null, pos.clone(), null, "close", 0));
        }
    }

    private void startClose(Damage d) {
        last = d;
        untargetable = true;
        mark(null);
        shots.removeIf(f -> f[3] == 1);
        sound("close", Sound.BLOCK_CHEST_CLOSE, 0.5f, 96);
        game.horizon.shake(0.5, 12);
        phase(Phase.CLOSE);
    }

    // ------------------------------------------------------------------ 몸

    private Vector keeperAt(int i) {
        double sx = i == 0 ? 1 : -1;
        return G148Sky.local(sx * (HALF_W * open + 0.9), 1.5, 0.4, yaw);
    }

    private void poseAll(int ticks) {
        float o = (float) Math.max(0.02, open);
        if (rift != null) rift.pose(new Matrix4f().scale(o, 1f, 1f), ticks);
        for (int i = 0; i < 2; i++) {
            float sx = i == 0 ? 1 : -1;
            if (keepers[i] != null) {
                keepers[i].pose(new Matrix4f().translate(sx * (float) (HALF_W * open + 0.9), 1.5f, 0.4f).rotateY((float) Math.toRadians(-sx * 25)), ticks);
            }
            if (braces[i] != null) {
                braces[i].pose(new Matrix4f().translate(0, i == 0 ? 4f : -3.5f, 0.2f).scale(Math.max(0.05f, o), 1f, 1f), ticks);
            }
        }
        if (line != null) {
            float fy = (float) (floor() + 0.05 - pos.getY());
            line.pose(new Matrix4f().translate(0, fy, 0).rotateY((float) Math.toRadians(-yaw)).translate(0, 0, 9f)
                    .scale(0.6f + 1.2f * o, 1f, 9f), ticks);
        }
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: 0, 1 문지기, 2, 3 버팀목, 4 틈. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(12);
        for (int i = 0; i < 2; i++) {
            if (keeperHp[i] <= 0 && init) continue;
            Vector k = keeperAt(i);
            out.add(new double[] {pos.getX() + k.getX(), pos.getY() + k.getY(), pos.getZ() + k.getZ(), 1.7, i});
        }
        for (int i = 0; i < 2; i++) {
            if (braceHp[i] <= 0 && init) continue;
            double y = i == 0 ? 4 : -3.5;
            for (double x : new double[] {-0.55, 0.55}) {
                Vector b = G148Sky.local(x * HALF_W * 2 * open, y, 0.2, yaw);
                out.add(new double[] {pos.getX() + b.getX(), pos.getY() + b.getY(), pos.getZ() + b.getZ(), 0.9, 2 + i});
            }
        }
        double w = Math.max(0.6, HALF_W * open);
        for (double y : new double[] {-6, -2.5, 1, 4.5}) {
            double rr = Math.min(w, 1.2 + w * (1 - Math.abs(y) / 8));
            out.add(new double[] {pos.getX(), pos.getY() + y, pos.getZ(), rr, 4});
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || phase == Phase.CLOSE) return false;
        int part = G148Sky.partAt(spheres(), d.at());
        double amt = d.amount();
        Damage dd = d;
        if (part == 4) {
            dd = d.times(0.5);
            amt = dd.amount();
        }
        hp -= amt;
        game.mobs.onHurt(this, dd);
        if (part == 0 || part == 1) {
            if (keeperHp[part] > 0) {
                keeperHp[part] -= amt;
                if (keeperHp[part] <= 0) {
                    sph = null;
                    if (keepers[part] != null) {
                        Vector k = keeperAt(part);
                        Location at = new Location(game.world, pos.getX() + k.getX(), pos.getY() + k.getY(), pos.getZ() + k.getZ());
                        ItemDisplay fx = G148Sky.fx(game, at, "gabe/g153_keeper", "", G148Sky.lay("gabe/g153_keeper", yaw, 1f, 1f, 1f), 30);
                        game.shotFx.drift(fx, at.clone().add(0, -(at.getY() - floor()) + 1.2, 0), 26);
                        keepers[part].remove();
                        keepers[part] = null;
                    }
                    sound("close", Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 96);
                    if (keeperHp[0] <= 0 && keeperHp[1] <= 0) {
                        startClose(dd);
                        return true;
                    }
                }
            }
        } else if ((part == 2 || part == 3) && braceHp[part - 2] > 0) {
            braceHp[part - 2] -= amt;
            if (braceHp[part - 2] <= 0) {
                sph = null;
                if (braces[part - 2] != null) {
                    braces[part - 2].remove();
                    braces[part - 2] = null;
                }
                sound("close", Sound.ENTITY_ITEM_BREAK, 0.7f, 64);
            }
        }
        if (hp <= 0) startClose(dd);
        return true;
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " open=" + Math.round(open * 100) / 100.0 + " keepers=" + Math.round(keeperHp[0]) + "/" + Math.round(keeperHp[1])
                + " braces=" + (braceHp[0] > 0 ? 1 : 0) + (braceHp[1] > 0 ? 1 : 0) + " shut=" + shut() + " crossed=" + crossed;
    }
}
