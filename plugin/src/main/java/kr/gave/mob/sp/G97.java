package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;

/**
 * G97 올려다보면 삼키는 구름 게이브(2막 16라운드, 방해 + 원거리, 12블록). 바랜 얼굴 수십이 뭉친 구름이 바닥 위 24블록에 거의 멈춰 떠 있고, 아무도 안 볼
 * 때만 사람 무리 위로 조금씩 흘러온다. 누가 올려다보면 모든 얼굴이 눈을 뜨고 아랫면 입이 그쪽으로 천천히 돌아간다.
 *
 * <p>공격: 구름을 시야 가운데 15도 안에 둔 시간이 사람마다 쌓여(1.2초 넘게 이어서 보면 1.5배로, 눈을 떼면 천천히 빠짐) 2초가 차면 접힌 입이 깔때기 살 관으로
 * 1초에 걸쳐 그 사람에게 내려와 삼켰다 뱉는다(25 와 3블록 들렸다 떨어짐, 그 1초 사이 자리를 벗어나면 빗나감). 아무도 안 볼 때는 3초마다 바랜 얼굴 우박
 * 넷을 사람에게 떨군다(하나 6, 떨어질 자리에 작은 그림자).
 *
 * <p>대처: 1초 안짝으로 끊어 쏘고 시선을 뗀다(쌓인 것이 빠짐, 조준점 아래 게이지). 여럿이 돌아가며 쏜다. 입이 내려오는 동안 깔때기 목(x3)이 드러남. 우박은
 * 그림자를 보고 비킨다.
 *
 * <p>몸: 닻 = 구름(gave:gabe/g97 상태 "" / "gape", 팔레트 탈색), 부품 깔때기(g97_funnel, 세계 축으로 늘여 기울임), 바닥 그림자(g97_shadow). 우박과 우박
 * 그림자는 따로 선 연출 디스플레이. 사체 없음(흩어져 사라짐). 판 규칙 GAZE(한 웨이브 하나)는 감독 표에 넣어야 함(요청).
 */
public final class G97 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 높이(바닥 위), 흘러오는 빠르기(멀 때, 가까이 와서 아무도 안 볼 때), 멀다고 치는 거리. */
    static final double ALT = 24, APPROACH_V = 0.2, DRIFT_V = 0.05, NEAR = 30;
    /** 올려다봄: 원뿔 반각(도), 재는 간격, 삼키는 데 드는 틱, 이어 보면 빨라지는 틱과 배율, 뗐을 때 빠지는 몫(재는 간격마다), 세는 거리. */
    static final double CONE = 15, FAST = 1.5, DECAY = 0.5, GAZE_R = 32;
    static final int GAZE_EVERY = 5, NEED = 40, FAST_AFTER = 24;
    /** 삼키기: 깔때기 내려오는 틱, 뱉고 올라가는 틱, 맞는 반경, 피해, 들림, 끝난 뒤 쉼, 깔때기 모델 길이(g97.py), 목 피해 배율. */
    static final int DROP = 20, RETRACT = 26, SWALLOW_REST = 50;
    static final double SWALLOW_R = 2.4, SWALLOW_DMG = 25, LAUNCH = 0.75, FUNNEL_LEN = 21.1, NECK_MUL = 3;
    /** 우박: 간격, 수, 떨어지는 틱, 맞는 반경, 피해, 아무도 안 본 지 이만큼 지나야. */
    static final int HAIL_EVERY = 60, HAIL_N = 4, HAIL_FALL = 24, UNSEEN = 40;
    static final double HAIL_R = 1.3, HAIL_DMG = 6;

    enum Phase { FLOAT, DROP, RETRACT }

    private Phase phase = Phase.FLOAT;
    private int phaseAt, watchedAt = -1000, hailAt = 60, restUntil;
    private final Map<UUID, double[]> stare = new HashMap<>();
    private final Set<UUID> metered = new HashSet<>();
    private Player victim;
    private final Vector aim = new Vector(), mouthAt = new Vector();
    private final G77Sky.Glide funnelG = new G77Sky.Glide();
    private Body.Part funnel, shadow;
    private float shadowDy = Float.NaN;
    private int dark;
    private final List<Hail> hail = new ArrayList<>();
    /** 마지막으로 몸 자세를 보낸 방향(4틱마다 짧은 보간으로 돌아서게: 15도 문턱에서 툭 꺾이지 않게). */
    private float keyYaw = Float.NaN;
    private List<double[]> sph;
    private int sphAge = -1;
    private boolean entered;

    private static final class Hail {
        ItemDisplay ball, spot;
        Vector land;
        int at;
    }

    public G97(Game game, Species sp) {
        super(game, sp);
        radius = 3.5;
        baseSpeed = APPROACH_V;
        noPush = true;
        noBite = true;
        noMutate = true;
        big = true;
        flying = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g97", 64f, G77Sky.boxH(ALT));
        body.viewRange(3.5f);
        body.look.palette |= Look.PAL_PALE;
        funnelG.snap(0.02);
        funnel = body.attach("gabe/g97_funnel");
        if (funnel != null) {
            funnel.follow = false;
            funnel.pose(new Matrix4f().translate(0, -2.2f, 0).scale(0.02f), 0);
        }
        shadow = body.attach("gabe/g97_shadow");
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
        vel.setY(Math.max(-0.3, Math.min(0.3, (floor() + ALT - pos.getY()) * 0.15)));
        if (age % GAZE_EVERY == 0) stares();
        switch (phase) {
            case FLOAT -> drift();
            case DROP -> drop();
            case RETRACT -> retract();
        }
        hailTick();
        poseShadow();
        if (age % 4 == 0 && Math.abs(yaw - keyYaw) > 0.3f) {
            keyYaw = yaw;
            body.pose(4);
        }
        if (!entered && every(10) && nearest(48) != null) {
            entered = true;
            sound("rumble", Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 96);
        }
        if (every(160) && game.random.nextInt(3) == 0) sound("rumble", Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 96);
    }

    /** 사람마다 올려다본 시간을 쌓고 빼고, 게이지를 그리고, 가장 많이 본 사람을 찍는다. 2초가 차면 삼키기. */
    private void stares() {
        Player most = null;
        double mostV = 0;
        boolean any = false;
        double cosCone = Math.cos(Math.toRadians(CONE));
        Set<UUID> seen = new HashSet<>();
        for (Player p : game.mobs.standing()) {
            UUID id = p.getUniqueId();
            seen.add(id);
            Location eye = p.getEyeLocation();
            double[] st = stare.get(id);
            boolean look = false;
            if (G77Sky.flat(pos, eye) <= GAZE_R) {
                Vector to = pos.clone().subtract(eye.toVector());
                double l = to.length();
                look = l > 1e-6 && eye.getDirection().dot(to) / l >= cosCone;
            }
            if (look) {
                any = true;
                if (st == null) stare.put(id, st = new double[2]);
                st[1] += GAZE_EVERY;
                st[0] += GAZE_EVERY * (st[1] > FAST_AFTER ? FAST : 1);
            } else if (st != null) {
                st[1] = 0;
                st[0] = Math.max(0, st[0] - GAZE_EVERY * DECAY);
            }
            if (st != null) {
                if (st[0] > 0) {
                    game.hud.meter(p, sp.key(), Math.min(1, st[0] / NEED), GAZE_EVERY * 3);
                    metered.add(id);
                } else if (metered.remove(id)) game.hud.meter(p, sp.key(), -1, 0);
                if (st[0] > mostV) {
                    mostV = st[0];
                    most = p;
                }
            }
        }
        stare.keySet().retainAll(seen);
        metered.retainAll(seen);
        if (any) watchedAt = age;
        if (phase == Phase.FLOAT) {
            mark(mostV > 10 ? most : null);
            if (most != null && mostV >= NEED && age >= restUntil) startDrop(most);
        }
    }

    private void drift() {
        Vector c = G77Sky.centroid(game);
        if (c == null) c = G77Sky.center();
        double d = G77Sky.flat(pos, c);
        double v = d > NEAR ? APPROACH_V : age - watchedAt > GAZE_EVERY ? DRIFT_V : 0;
        v *= speed() / baseSpeed;
        if (d > 1.5 && v > 0) vel.setX((c.getX() - pos.getX()) / d * v).setZ((c.getZ() - pos.getZ()) / d * v);
        else vel.setX(0).setZ(0);
        if (target != null && standing(target)) turnTo(target.getLocation().toVector().setY(pos.getY()), 1.5f);
        pitch = 0;
    }

    // ------------------------------------------------------------------ 삼키기

    private void startDrop(Player p) {
        victim = p;
        mark(p);
        Location l = p.getLocation();
        aim.setX(l.getX()).setY(l.getY() + 1.0).setZ(l.getZ());
        mouthAt.copy(pos).add(new Vector(0, -2.3, 0));
        state("gape");
        darkness(2);
        sound("rumble", Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 96);
        double len = aim.clone().subtract(mouthAt).length() + 0.6;
        funnelG.set(0.02, len / FUNNEL_LEN, now(), DROP);
        poseFunnel(len / FUNNEL_LEN, DROP);
        vel.setX(0).setZ(0);
        phase(Phase.DROP);
    }

    /** 깔때기: 구름 아랫면 입에서 aim 쪽으로, 길이 몫 f(모델 길이 기준). 구름이 멈춰 있는 동안만 부른다. */
    private void poseFunnel(double f, int ticks) {
        if (funnel == null) return;
        Vector d = aim.clone().subtract(mouthAt);
        Vector3f u = d.lengthSquared() < 1e-6 ? new Vector3f(0, -1, 0) : new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ()).normalize();
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, -1, 0), u);
        float w = f < 0.05 ? (float) f : 1f;
        funnel.pose(new Matrix4f().translate((float) (mouthAt.getX() - pos.getX()), (float) (mouthAt.getY() - pos.getY()), (float) (mouthAt.getZ() - pos.getZ()))
                .rotate(q).scale(w, (float) f, w), ticks);
    }

    private void drop() {
        vel.setX(0).setZ(0);
        if (inPhase() < DROP) return;
        if (standing(victim)) {
            Location l = victim.getLocation();
            if (Math.hypot(l.getX() - aim.getX(), l.getZ() - aim.getZ()) <= SWALLOW_R && Math.abs(l.getY() + 1 - aim.getY()) < 3) {
                hit(victim, SWALLOW_DMG, "cause_g97");
                knock(victim, new Vector(0, 0, 0), 0, LAUNCH);
                sound("gulp", Sound.ENTITY_GENERIC_EAT, 0.5f, 48);
                game.shotFx.bloodHit(l.clone().add(0, 1.2, 0), new Vector(0, 1, 0), true, ShotFx.BLOOD_PINK);
            }
            double[] st = stare.get(victim.getUniqueId());
            if (st != null) st[0] = st[1] = 0;
        }
        funnelG.go(0.02, now(), RETRACT);
        poseFunnel(0.02, RETRACT);
        phase(Phase.RETRACT);
    }

    private void retract() {
        vel.setX(0).setZ(0);
        if (inPhase() >= RETRACT) {
            state("");
            darkness(1);
            victim = null;
            mark(null);
            restUntil = age + cd(SWALLOW_REST);
            phase(Phase.FLOAT);
        }
    }

    // ------------------------------------------------------------------ 우박

    private void hailTick() {
        for (Iterator<Hail> it = hail.iterator(); it.hasNext();) {
            Hail h = it.next();
            if (age < h.at) continue;
            for (Player p : new ArrayList<>(game.mobs.standing())) {
                Location l = p.getLocation();
                if (Math.hypot(l.getX() - h.land.getX(), l.getZ() - h.land.getZ()) <= HAIL_R && Math.abs(l.getY() - h.land.getY()) < 2.5)
                    hit(p, HAIL_DMG, "cause_g97_hail");
            }
            G77Sky.kill(h.ball);
            G77Sky.kill(h.spot);
            Location at = new Location(game.world, h.land.getX(), h.land.getY() + 0.3, h.land.getZ());
            Props.soundAround(at, "gabe." + sp.key() + ".hail", Sound.BLOCK_SLIME_BLOCK_FALL, 1f, 1f, 1f, 24, -1);
            it.remove();
        }
        if (phase != Phase.FLOAT || age < hailAt || age - watchedAt < UNSEEN) return;
        List<Player> near = new ArrayList<>();
        for (Player p : game.mobs.standing()) if (G77Sky.flat(pos, p.getLocation()) <= 34) near.add(p);
        if (near.isEmpty()) return;
        hailAt = age + cd(HAIL_EVERY);
        for (int i = 0; i < HAIL_N; i++) {
            Player p = near.get(game.random.nextInt(near.size()));
            Location l = p.getLocation();
            double a = game.random.nextDouble() * Math.PI * 2, r = game.random.nextDouble() * 1.4;
            Hail h = new Hail();
            h.land = new Vector(l.getX() + Math.cos(a) * r, game.mobs.flow.floor(l.getX(), l.getZ()), l.getZ() + Math.sin(a) * r);
            h.at = age + HAIL_FALL;
            Location from = new Location(game.world, pos.getX() + (game.random.nextDouble() * 2 - 1) * 4, pos.getY() - 2.5,
                    pos.getZ() + (game.random.nextDouble() * 2 - 1) * 4);
            h.ball = G77Sky.fx(game, from, "gabe/g97_hail", "", G77Sky.lay("gabe/g97_hail", game.random.nextFloat() * 360f, 1.6f, 1.6f, 1.6f),
                    HAIL_FALL + 4, 2f);
            if (h.ball != null) {
                h.ball.setTeleportDuration(HAIL_FALL);
                ItemDisplay b = h.ball;
                Location to = new Location(game.world, h.land.getX(), h.land.getY() + 0.4, h.land.getZ());
                game.shotFx.drift(b, to, HAIL_FALL);
            }
            h.spot = G77Sky.fx(game, new Location(game.world, h.land.getX(), h.land.getY() + 0.06, h.land.getZ()), "gabe/g97_spot", "",
                    G77Sky.lay("gabe/g97_spot", 0f, 1.6f, 1f, 1.6f), HAIL_FALL + 2, 3f);
            if (h.spot != null) h.spot.setBrightness(new Display.Brightness(8, 8));
            hail.add(h);
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
        if (Math.abs(fy - shadowDy) < 0.05f) return;
        shadowDy = fy;
        shadow.pose(new Matrix4f().translate(0, fy, 0), 3);
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: [x, y, z, r, 부위] 부위 0 구름, 1 깔때기 목(내려와 있는 동안). */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(10);
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        out.add(new double[] {x, y, z, 3.6, 0});
        Vector a = G77Sky.local(3.6, -0.2, 0, yaw), b = G77Sky.local(0, -0.2, 3.1, yaw);
        out.add(new double[] {x + a.getX(), y + a.getY(), z + a.getZ(), 2.8, 0});
        out.add(new double[] {x - a.getX(), y + a.getY(), z - a.getZ(), 2.8, 0});
        out.add(new double[] {x + b.getX(), y + b.getY(), z + b.getZ(), 2.6, 0});
        out.add(new double[] {x - b.getX(), y + b.getY(), z - b.getZ(), 2.6, 0});
        out.add(new double[] {x, y - 2.0, z, 2.0, 0});
        double f = funnelG.at(now());
        if (phase != Phase.FLOAT && f > 0.1) {
            Vector d = aim.clone().subtract(mouthAt);
            double len = f * FUNNEL_LEN, dl = d.length();
            if (dl > 1e-6) {
                d.multiply(1 / dl);
                for (double k : new double[] {0.45, 0.7, 0.92}) {
                    Vector p = mouthAt.clone().add(d.clone().multiply(len * k));
                    out.add(new double[] {p.getX(), p.getY(), p.getZ(), 1.25, 1});
                }
            }
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.at() != null && phase != Phase.FLOAT) {
            double best = Double.MAX_VALUE;
            int part = 0;
            for (double[] s : spheres()) {
                double dx = s[0] - d.at().getX(), dy = s[1] - d.at().getY(), dz = s[2] - d.at().getZ();
                double dd = Math.sqrt(dx * dx + dy * dy + dz * dz) - s[3];
                if (dd < best) {
                    best = dd;
                    part = (int) s[4];
                }
            }
            if (part == 1) return super.damage(d.times(NECK_MUL));
        }
        return super.damage(d);
    }

    private void clearFx() {
        for (Hail h : hail) {
            G77Sky.kill(h.ball);
            G77Sky.kill(h.spot);
        }
        hail.clear();
        for (UUID id : metered) {
            Player p = game.plugin.getServer().getPlayer(id);
            if (p != null) game.hud.meter(p, sp.key(), -1, 0);
        }
        metered.clear();
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        clearFx();
        leaveCorpse = false;
        // 흩어지는 구름: 연기 덩이 넷
        for (int i = 0; i < 4; i++) {
            Location c = new Location(game.world, pos.getX() + (i % 2 == 0 ? 3 : -3), pos.getY() - 0.5, pos.getZ() + (i < 2 ? 2.5 : -2.5));
            ItemDisplay sm = game.shotFx.spawn(c, "fx_quad", ShotFx.SMOKE, 1, ShotFx.scale(5f), Display.Billboard.CENTER, false, 60, ShotFx.EXTRA);
            game.shotFx.grow(sm, ShotFx.scale(11f), 56);
            game.shotFx.drift(sm, c.clone().add(0, 3, 0), 56);
        }
        sound("rumble", Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 96);
        super.die(d);
    }

    @Override
    public void remove() {
        clearFx();
        super.remove();
    }

    @Override
    public String toString() {
        double top = 0;
        for (double[] s : stare.values()) top = Math.max(top, s[0]);
        return super.toString() + " ph=" + phase + " stare=" + Math.round(top) + "/" + NEED + " hail=" + hail.size() + " watched=" + (age - watchedAt);
    }
}
