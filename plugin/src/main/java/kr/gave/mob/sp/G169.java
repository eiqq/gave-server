package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G169 물그림자 게이브(2막 18라운드, 방해, 정예, 8블록). 하늘 14블록에 폭 8블록의 흐릿한 얼굴이 떠 있고(탄이 지나가는 허깨비: 맞으면 연기만), 그
 * 밑 웅덩이 수면에 거꾸로 비친 물그림자가 진짜 몸이다.
 * <ul>
 * <li>하늘 얼굴은 사람이 많이 모인 웅덩이 위로 천천히 흘러간다(0.14블록/틱). 하늘 얼굴을 오래 쏜 사람(20발 넘게) 곁의 웅덩이로 먼저 간다.
 * 하늘 얼굴이 웅덩이 위에 닿으면 물그림자가 그 웅덩이로 옮겨 비친다.</li>
 * <li>웅덩이 4블록 안 사람은 0.5초마다 물가 쪽으로 끌린다. 웅덩이 안이나 물가 1블록 안에 사람이 들면 1초 x 박자 소용돌이(예고) 뒤 물기: 그 안 사람
 * 모두 24 와 1초 묶임, 묶인 동안 0.5초마다 6. 물고 나면 4초 x 박자 쉬고 다음 웅덩이로.</li>
 * <li>조명탄이 그 웅덩이에 떨어지면 물그림자가 3초 흩어져 못 문다. 물그림자가 죽으면 하늘 얼굴도 흩어진다.</li>
 * </ul>
 * 웅덩이: 맵의 물웅덩이(Plaza.puddles)와 사람 곁 6블록 안의 물 칸(Plaza.wet). 물이 하나도 없으면 가장 많이 모인 사람 발밑에 제 웅덩이를 편다(도감과
 * 다름: 마른 땅만 있는 스테이지에서도 위협이 남게).
 *
 * <p>몸: {@link Body#carrier} + 부품(물그림자 g169, 하늘 얼굴 g169_sky: 둘 다 세계 축). Gabe.pos = 수면 가운데(바닥 + 0.3, 하늘 몹 바닥 한계), 맞는 구는
 * 수면의 납작한 구 다섯(BIG: 물 위라 폭발이 잘 듣는다). 하늘 얼굴은 {@link Mobs.Shootable}(피해 0, 연기).
 */
public final class G169 extends Gabe implements Mobs.Shootable {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SKY_H = 14, SKY_SPEED = 0.14, SKY_R = 3.8, POOL_Y = -0.24;
    /** 물기: 피해, 묶임 틱, 묶인 동안 0.5초마다 피해, 웅덩이 둘레 더하는 물가(블록), 끌어당기는 둘레, 끌어당김 세기(2틱마다). */
    static final double BITE_DMG = 10, DRAG_DMG = 2, SHORE = 1, PULL_R = 4, PULL = 0.12, POOL_R = 3;
    static final int ROOT_T = 20, SWIRL_T = 20, REST_T = 80, FLARE_T = 60, FADE = 8, PICK_EVERY = 60;
    /** 하늘 얼굴을 이만큼 넘게 쏜 사람 곁 웅덩이로 먼저(발 수). */
    static final int SHOT_LIMIT = 20;

    enum Phase { DRIFT, LURK, SWIRL, REST, GONE }

    private Phase phase = Phase.DRIFT;
    private int phaseAt;
    private Body.Part pool, sky;
    /** 하늘 얼굴 자리(세계, 높이 = 바닥 + SKY_H). */
    private final Vector skyAt = new Vector();
    /** 갈 웅덩이(가운데 x, z, 반지름 [2]). */
    private final double[] target = new double[3];
    private boolean hasTarget, formed, init;
    private double poolR = POOL_R;
    private int pickAt, flareUntil;
    private final Map<UUID, Integer> skyShots = new HashMap<>();
    private final Map<UUID, Integer> dragged = new HashMap<>();
    private List<double[]> sph;
    private int sphAge = -1;

    public G169(Game game, Species sp) {
        super(game, sp);
        radius = 2.4;
        baseSpeed = SKY_SPEED;
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
        pool = body.attach("gabe/g169");
        if (pool != null) {
            pool.follow = false;
            pool.pose(new Matrix4f().translate(0, (float) POOL_Y, 0).scale(0.001f), 0);
        }
        sky = body.attach("gabe/g169_sky");
        if (sky != null) sky.follow = false;
        skyAt.copy(pos).setY(pos.getY() + SKY_H);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        // 나온 자리 하늘에 얼굴만 있고 물그림자는 아직 없다: 몸 자리를 바닥으로 내림
        skyAt.setX(pos.getX()).setZ(pos.getZ()).setY(floor() + SKY_H);
        pos.setY(floor() + 0.3);
        if (body != null) body.warp();
        poseSky(0);
        game.mobs.addShootable(this);
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
            game.mobs.addShootable(this);
        }
        vel.zero();
        vel.setY(Math.max(-0.4, Math.min(0.4, floor() + 0.3 - pos.getY())));
        if (every(20)) G167Sky.light(sky, G167Sky.FACE_LIGHT);
        driftSky();
        if (!G167Sky.wave(game)) return;
        dragTick();
        if (formed && game.shop.inFlare(pos, poolR) && phase != Phase.GONE) {
            flareUntil = age + FLARE_T;
            poolState("gone");
            phase(Phase.GONE);
        }
        switch (phase) {
            case DRIFT -> drift();
            case LURK -> lurk();
            case SWIRL -> swirl();
            case REST -> rest();
            case GONE -> scattered();
        }
    }

    /** 하늘 얼굴: 갈 웅덩이 위로 흐름(웅덩이를 정할 때마다 바뀜). */
    private void driftSky() {
        // 비친 웅덩이 곁에 사람이 있으면(지키는 중) 웅덩이를 바꾸지 않는다: 물고 쉰 뒤에 다음 웅덩이로
        if (formed && phase != Phase.DRIFT && anyIn(poolR + 8)) pickAt = Math.max(pickAt, age + 20);
        if (age >= pickAt || !hasTarget) {
            pickAt = age + PICK_EVERY;
            hasTarget = pickPool(target);
        }
        if (!hasTarget) return;
        double dx = target[0] - skyAt.getX(), dz = target[1] - skyAt.getZ(), d = Math.hypot(dx, dz);
        double v = Math.min(speed(), d);
        if (d > 0.05) {
            skyAt.setX(skyAt.getX() + dx / d * v).setZ(skyAt.getZ() + dz / d * v);
        }
        skyAt.setY(game.mobs.flow.floor(skyAt.getX(), skyAt.getZ()) + SKY_H);
        if (every(4)) poseSky(4);
    }

    private void drift() {
        if (!hasTarget) return;
        double d = Math.hypot(target[0] - skyAt.getX(), target[1] - skyAt.getZ());
        double away = Math.hypot(target[0] - pos.getX(), target[1] - pos.getZ());
        if (formed && away > 1.5) {
            // 비친 웅덩이를 떠남: 물그림자가 흐려짐
            formed = false;
            if (pool != null) pool.pose(new Matrix4f().translate(0, (float) POOL_Y, 0).scale(0.001f), FADE);
            phase(Phase.DRIFT);
            return;
        }
        if (d <= 2.5 && inPhase() >= FADE) form();
    }

    /** 하늘 얼굴 밑 웅덩이에 물그림자가 비침. */
    private void form() {
        poolR = Math.max(1.5, Math.min(POOL_R, target[2]));
        Vector at = new Vector(target[0], game.mobs.flow.floor(target[0], target[1]) + 0.3, target[1]);
        if (pos.distanceSquared(at) > 0.25) {
            if (!game.mobs.relocate(this, at)) pos.copy(at);
            if (body != null) body.warp();
            poseSky(0);
        }
        formed = true;
        poolState("");
        float s = (float) (poolR / POOL_R);
        if (pool != null) pool.pose(new Matrix4f().translate(0, (float) POOL_Y, 0).scale(s, 1f, s), FADE);
        sound("ripple", Sound.ENTITY_GENERIC_SPLASH, 32);
        phase(Phase.LURK);
    }

    private void lurk() {
        if (!formed) {
            phase(Phase.DRIFT);
            return;
        }
        if (target[0] != pos.getX() || target[1] != pos.getZ()) {
            if (Math.hypot(target[0] - pos.getX(), target[1] - pos.getZ()) > 1.5) {
                phase(Phase.DRIFT);
                return;
            }
        }
        if (anyIn(poolR + SHORE)) {
            poolState("swirl");
            sound("ripple", Sound.ENTITY_GENERIC_SPLASH, 0.7f, 32);
            phase(Phase.SWIRL);
        }
    }

    private void swirl() {
        if (inPhase() < cd(SWIRL_T)) return;
        poolState("bite");
        sound("gulp", Sound.ENTITY_GENERIC_SPLASH, 0.5f, 32);
        boolean any = false;
        for (Player p : G167Sky.standing(game)) {
            if (G167Sky.flat(pos, p.getLocation()) > poolR + SHORE) continue;
            if (p.getLocation().getY() > floor() + 2.5) continue;
            hit(p, BITE_DMG, "cause_g169");
            if (G167Sky.up(game, p)) {
                game.players.root(p, ROOT_T, "water");
                dragged.put(p.getUniqueId(), age);
            }
            mark(p);
            any = true;
        }
        G167Sky.splash(game, pos.clone().add(new Vector(0, 0.6, 0)), true);
        if (any) sound("laugh", Sound.ENTITY_WITCH_CELEBRATE, 0.6f, 64);
        phase(Phase.REST);
    }

    private void rest() {
        if (inPhase() == 10) poolState("");
        if (inPhase() >= cd(REST_T)) {
            mark(null);
            pickAt = age;
            hasTarget = false;
            phase(formed ? Phase.LURK : Phase.DRIFT);
        }
    }

    private void scattered() {
        if (age >= flareUntil) {
            poolState("");
            phase(formed ? Phase.LURK : Phase.DRIFT);
        }
    }

    /** 묶인 사람 0.5초마다 6, 웅덩이 4블록 안 사람은 물가 쪽으로 끌림. */
    private void dragTick() {
        if (!dragged.isEmpty() && every(10)) {
            dragged.entrySet().removeIf(e -> age - e.getValue() > ROOT_T);
            for (Player p : G167Sky.standing(game)) {
                Integer at = dragged.get(p.getUniqueId());
                if (at == null || age == at) continue;
                hit(p, DRAG_DMG, "cause_g169");
            }
        }
        if (!formed || phase == Phase.GONE || phase == Phase.REST || age % 2 != 0) return;
        for (Player p : G167Sky.standing(game)) {
            Location l = p.getLocation();
            double d = G167Sky.flat(pos, l);
            if (d > poolR + PULL_R || d < poolR * 0.6 || l.getY() > floor() + 2.5) continue;
            Vector v = new Vector(pos.getX() - l.getX(), 0, pos.getZ() - l.getZ()).normalize().multiply(PULL);
            game.players.push(p, v);
        }
    }

    private boolean anyIn(double r) {
        for (Player p : game.mobs.standing()) {
            if (G167Sky.flat(pos, p.getLocation()) <= r && p.getLocation().getY() <= floor() + 2.5) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 웅덩이 고르기

    /** 갈 웅덩이(가운데 x, z, 반지름): 사람이 가장 많이 모인 물. 하늘 얼굴을 오래 쏜 사람 곁 물을 먼저. 물이 없으면 가장 많이 모인 사람 발밑. */
    private boolean pickPool(double[] out) {
        List<Player> ps = game.mobs.standing();
        if (ps.isEmpty()) return false;
        List<double[]> cand = new ArrayList<>();
        List<Vector> pud = Plaza.puddles();
        for (int i = 0; i < pud.size(); i++) cand.add(new double[] {pud.get(i).getX(), pud.get(i).getZ(), Plaza.puddleRadius(i)});
        int n = 0;
        for (Player p : ps) {
            if (n++ >= 10) break;
            double[] w = wetNear(p.getLocation());
            if (w != null) cand.add(w);
        }
        Player angry = null;
        int most = SHOT_LIMIT;
        for (Player p : ps) {
            int s = skyShots.getOrDefault(p.getUniqueId(), 0);
            if (s > most) {
                most = s;
                angry = p;
            }
        }
        double[] best = null;
        double bestScore = -1;
        for (double[] c : cand) {
            double score = G167Sky.countNear(game, c[0], c[1], 9);
            if (angry != null) {
                double d = Math.hypot(angry.getLocation().getX() - c[0], angry.getLocation().getZ() - c[1]);
                score += d < 10 ? 50 - d : 0;
            }
            score -= Math.hypot(c[0] - skyAt.getX(), c[1] - skyAt.getZ()) * 0.02;
            // 방금 문 웅덩이는 덜(물고 나면 다음 웅덩이로)
            if (formed && Math.hypot(c[0] - pos.getX(), c[1] - pos.getZ()) < 2) score -= 2.5;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        if (best == null || bestScore < 0.5) {
            Player p = angry != null ? angry : G167Sky.densest(game, 8, skyAt);
            if (p == null) return false;
            best = new double[] {p.getLocation().getX(), p.getLocation().getZ(), POOL_R};
        }
        out[0] = best[0];
        out[1] = best[1];
        out[2] = best[2];
        return true;
    }

    /** 사람 곁 6블록 안 가장 가까운 물 칸(가운데, 반지름 2.5). 없으면 null. */
    private double[] wetNear(Location l) {
        int bx = l.getBlockX(), bz = l.getBlockZ();
        double bd = Double.MAX_VALUE;
        int fx = 0, fz = 0;
        boolean found = false;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 36 || d2 >= bd) continue;
                if (!Plaza.wet(bx + dx, bz + dz)) continue;
                bd = d2;
                fx = bx + dx;
                fz = bz + dz;
                found = true;
            }
        }
        return found ? new double[] {fx + 0.5, fz + 0.5, 2.5} : null;
    }

    // ------------------------------------------------------------------ 몸

    private void poolState(String s) {
        if (pool != null) pool.state(s);
    }

    private void poseSky(int ticks) {
        if (sky == null) return;
        sky.pose(new Matrix4f().translate((float) (skyAt.getX() - pos.getX()), (float) (skyAt.getY() - pos.getY()), (float) (skyAt.getZ() - pos.getZ())),
                ticks);
    }

    // ------------------------------------------------------------------ 하늘 얼굴(쏠 수 있지만 피해 없음)

    @Override
    public double rayHit(Vector o, Vector dir, double max) {
        if (dead || removed) return Double.POSITIVE_INFINITY;
        double t = G167Sky.raySphere(o, dir, skyAt.getX(), skyAt.getY(), skyAt.getZ(), SKY_R);
        return t <= max ? t : Double.POSITIVE_INFINITY;
    }

    @Override
    public boolean shot(Damage d) {
        Vector at = d.at() != null ? d.at() : skyAt;
        if (game.random.nextInt(3) == 0) G167Sky.smoke(game, at);
        if (d.by() != null) skyShots.merge(d.by().getUniqueId(), 1, Integer::sum);
        return false;
    }

    @Override
    public Vector center() {
        return skyAt.clone();
    }

    @Override
    public boolean gone() {
        return dead || removed;
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    /** 맞는 구: 수면의 납작한 구 다섯(물그림자가 비쳐 있을 때만, 아니면 하늘 얼굴 밑 허공에 작은 구 하나). */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(5);
        double y = pos.getY();
        if (formed) {
            double k = poolR / POOL_R;
            out.add(new double[] {pos.getX(), y, pos.getZ(), 1.4 * k + 0.2, 0});
            for (int i = 0; i < 4; i++) {
                double a = Math.PI / 2 * i + Math.PI / 4;
                out.add(new double[] {pos.getX() + Math.cos(a) * 1.7 * k, y, pos.getZ() + Math.sin(a) * 1.7 * k, 1.1 * k + 0.15, 0});
            }
        } else {
            out.add(new double[] {pos.getX(), y, pos.getZ(), 0.6, 0});
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || unhittable()) return false;
        if (!formed) return false;
        hp -= d.amount();
        game.mobs.onHurt(this, d);
        if (d.at() != null && game.random.nextInt(2) == 0) G167Sky.splash(game, d.at(), true);
        if (hp <= 0) die(d);
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        game.mobs.removeShootable(this);
        for (int i = 0; i < 3; i++) G167Sky.smoke(game, skyAt.clone().add(new Vector(game.random.nextGaussian() * 2, 0, game.random.nextGaussian() * 2)));
        G167Sky.splash(game, pos.clone().add(new Vector(0, 0.5, 0)), true);
        super.die(d);
    }

    @Override
    public void remove() {
        game.mobs.removeShootable(this);
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " formed=" + formed + " poolR=" + poolR + " sky=" + Math.round(skyAt.getX()) + "," + Math.round(skyAt.getZ())
                + " tgt=" + Math.round(target[0]) + "," + Math.round(target[1]) + " skyShots=" + skyShots.values().stream().mapToInt(Integer::intValue).sum();
    }
}
