package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * G154 여덟 머리 게이브(4막 34라운드, 정예, 튼튼이, 미리 깔림). 땅 밑 한 몸의 머리 여덟이 트럭 둘레 여덟 방향 18~22블록 자리(지름 40 원)에서 번갈아
 * 둘셋씩 솟는다(체력이 줄수록 한꺼번에 솟는 수가 늘어 여섯까지). 솟기 2초 전 그 자리 흙 둔덕이 부풀고 땅울림, 솟을 때 반경 3 안 20 + 띄움.
 * 솟은 머리는 목을 뒤로 젖혔다(뿔이 섬, 1초) 트럭 쪽 사람에게 내리꽂아 문다: 목 길이 10블록 안 한 사람 34 + 땅속으로 끌려 들어가 갇힌다(그 머리가
 * 다시 솟았을 때 맞히면 뱉음). 솟고 4초 뒤 땅속으로 들어간다.
 *
 * <p>한 몸이라 체력은 하나: 머리 하나에 준 피해는 다른 머리들이 6초에 걸쳐 메운다. 솟은 머리에 계속 피해를 주는 동안(1초 안에 맞음)은 그 머리가 못
 * 들어간다(묶임, 15초까지). 솟아 있는 머리가 모두 묶이면 메우기가 멈추고 피해가 두 배로, 그동안 준 피해는 메워지지 않는다. 한쪽에 몰려 한 머리만 쏘면 끝이 없다.
 *
 * <p>몸: {@link Body#carrier} + 머리 부품 여덟(gave:gabe/g154, 상태 "" rear bite mound, 머리마다 팔레트로 표정 다름). Gabe.pos = 트럭 가운데 땅.
 * 사체 없음(머리들이 땅속으로 무너짐).
 */
public final class G154 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int HEADS = 8;
    static final double R0 = 18, R_RND = 4, RISE_R = 3, BITE_REACH = 10, NECK = 6, FACE_UP = 7.2;
    /** 틱: 부풀기, 솟기, 젖히기, 내리꽂기, 솟아 있는 틱(박자), 들어가기, 다시 솟기까지 쉼, 묶임 판정, 묶임 상한, 메우는 시간. */
    static final int SWELL = 40, RISE = 8, REAR = 20, STRIKE = 6, UP_T = 80, SINK = 10, REST = 40, BOUND = 20, BOUND_MAX = 300, REGEN = 60;
    /** 피해: 솟음, 물기. */
    static final double RISE_DMG = 20, BITE_DMG = 34;
    static final int[] PALETTES = {0, Look.PAL_DEEP_BLUSH, Look.PAL_PALE, 0, Look.PAL_GOLD, Look.PAL_DEEP_BLUSH | Look.PAL_WHITE_EYES, Look.PAL_PALE, Look.PAL_WHITE_EYES};

    enum HP { BURIED, SWELL, RISE, LOOK, REAR, STRIKE, HOLD, SINK }

    /** 머리 하나. */
    private final class Head {
        final int i;
        final Body.Part part;
        double x, z;
        float yaw;
        HP st = HP.BURIED;
        long at, lastHit = Long.MIN_VALUE / 2, restUntil, upAt;
        double lean, stretch = 1, rise;
        Player prey;
        final List<Player> held = new ArrayList<>();

        Head(int i, Body.Part part) {
            this.i = i;
            this.part = part;
        }

        void set(HP s) {
            st = s;
            at = now();
        }

        long in() {
            return now() - at;
        }

        boolean up() {
            return st == HP.LOOK || st == HP.REAR || st == HP.STRIKE || st == HP.HOLD || st == HP.RISE;
        }

        boolean bound() {
            return now() - lastHit <= BOUND;
        }

        Vector base() {
            return new Vector(x, floor(), z);
        }

        /** 얼굴 가운데(세계): 젖힘과 늘임을 반영. */
        Vector face() {
            double a = Math.toRadians(lean), h = FACE_UP * stretch * rise;
            Vector f = G148Sky.local(0, 0, 1, yaw);
            double fwd = Math.sin(a) * h, up = Math.cos(a) * h;
            return new Vector(x + f.getX() * fwd, floor() + up, z + f.getZ() * fwd);
        }
    }

    private final Head[] heads = new Head[HEADS];
    private double pool;
    private boolean init, dying;
    private int dieAt;
    private Damage last;
    private List<double[]> sph;
    private int sphAge = -1;

    public G154(Game game, Species sp) {
        super(game, sp);
        radius = 1.0;
        baseSpeed = 0.1;
        flying = true;
        ghost = true;
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
        body.viewRange(3.5f);
        for (int i = 0; i < HEADS; i++) {
            Body.Part p = body.attach("gabe/g154");
            if (p != null) {
                p.state("mound");
                p.look.palette = PALETTES[i];
            }
            heads[i] = new Head(i, p);
            G148Sky.bright(p);
        }
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Vector c = G148Sky.center();
        pos.setX(c.getX()).setZ(c.getZ()).setY(floor() + 0.3);
        if (body != null) body.warp();
        double off = game.random.nextDouble() * 45;
        for (int i = 0; i < HEADS; i++) {
            Head h = heads[i];
            double a = Math.toRadians(off + 45.0 * i), r = R0 + game.random.nextDouble() * R_RND;
            h.x = c.getX() + Math.sin(a) * r;
            h.z = c.getZ() + Math.cos(a) * r;
            h.yaw = (float) Math.toDegrees(Math.atan2(-(c.getX() - h.x), c.getZ() - h.z));
            h.restUntil = now() + 20 + i * 7L;
            pose(h, 0);
        }
        Player p = nearest(400);
        if (p != null) mark(p);
    }

    @Override
    public void lure(Vector at, int ticks) {
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        vel.zero();
        pitch = 0;
        vel.setY(Math.max(-1, Math.min(1, floor() + 0.3 - pos.getY())));
        if (!init) init = true;
        if (dying) {
            dyingTick();
            return;
        }
        if (target == null || !G148Sky.up(game, target)) {
            Player p = nearest(400);
            if (p != null) mark(p);
        }
        // 동시에 솟는 수: 2 + 깎인 몫 x 4(여섯까지)
        int want = Math.min(6, 2 + (int) Math.floor((1 - Math.max(0, hpFrac())) * 4.5));
        int active = 0;
        for (Head h : heads) if (h.st != HP.BURIED) active++;
        if (active < want && every(10)) {
            List<Head> ready = new ArrayList<>();
            for (Head h : heads) if (h.st == HP.BURIED && now() >= h.restUntil) ready.add(h);
            if (!ready.isEmpty()) {
                Head h = ready.get(game.random.nextInt(ready.size()));
                h.set(HP.SWELL);
                sound("quake", org.bukkit.Sound.BLOCK_GRAVEL_BREAK, 0.6f, 64);
            }
        }
        boolean allBound = true;
        int up = 0;
        for (Head h : heads) {
            headTick(h);
            if (h.up()) {
                up++;
                allBound &= h.bound();
            }
        }
        allBound &= up > 0;
        // 메우기: 묶이지 않았으면 쌓인 피해를 6초에 걸쳐 되채움
        if (!allBound && pool > 0) {
            double heal = Math.min(pool, Math.max(0.5, pool / REGEN));
            hp = Math.min(maxHp, hp + heal);
            pool -= heal;
        }
        if (every(2)) for (Head h : heads) if (h.up() || h.st == HP.SWELL || h.st == HP.SINK) pose(h, 2);
    }

    private void headTick(Head h) {
        long t = h.in();
        switch (h.st) {
            case BURIED -> {
                h.rise = 0;
            }
            case SWELL -> {
                h.stretch = 1 + 0.2 * Math.sin(t * 0.6);
                if (t % 10 == 0) game.horizon.shake(0.12, 6);
                if (t >= SWELL) {
                    h.stretch = 1;
                    h.set(HP.RISE);
                    h.upAt = now();
                    risen(h);
                }
            }
            case RISE -> {
                h.rise = Math.min(1, t / (double) RISE);
                if (t >= RISE) {
                    h.rise = 1;
                    h.set(HP.LOOK);
                }
            }
            case LOOK -> {
                Player p = preyFor(h);
                h.prey = p;
                h.part.look.open = p != null;
                if (p != null) h.yaw = yawTo(h, p.getLocation());
                if (t >= 14 && p != null) {
                    h.part.state("rear");
                    h.set(HP.REAR);
                } else if (t >= cd(UP_T) && (!h.bound() || now() - h.upAt > BOUND_MAX)) sinkStart(h);
            }
            case REAR -> {
                h.lean = -22 * Math.min(1, t / 8.0);
                if (h.prey != null && G148Sky.up(game, h.prey)) h.yaw = yawTo(h, h.prey.getLocation());
                if (t >= Math.max(10, cd(REAR))) {
                    h.part.state("bite");
                    h.set(HP.STRIKE);
                }
            }
            case STRIKE -> {
                double reachAng = strikeAngle(h);
                h.lean = reachAng * Math.min(1, t / (double) STRIKE);
                h.stretch = 1 + 0.45 * Math.min(1, t / (double) STRIKE);
                if (t == STRIKE) bite(h);
                if (t >= STRIKE + 8) {
                    h.part.state("");
                    h.set(HP.HOLD);
                }
            }
            case HOLD -> {
                h.lean *= 0.8;
                h.stretch = 1 + (h.stretch - 1) * 0.8;
                boolean bound = h.bound() && now() - h.upAt < BOUND_MAX;
                if (t >= 40 && !bound) sinkStart(h);
            }
            case SINK -> {
                h.rise = Math.max(0, 1 - t / (double) SINK);
                if (t >= SINK) {
                    h.rise = 0;
                    h.lean = 0;
                    h.stretch = 1;
                    h.part.state("mound");
                    h.part.look.open = false;
                    h.restUntil = now() + REST + game.random.nextInt(40);
                    h.set(HP.BURIED);
                    pose(h, 2);
                }
            }
        }
    }

    private void risen(Head h) {
        h.part.state("");
        h.lean = 0;
        double fl = floor();
        for (Player p : G148Sky.standing(game)) {
            Location l = p.getLocation();
            if (l.getY() > fl + 3 || Math.hypot(l.getX() - h.x, l.getZ() - h.z) > RISE_R) continue;
            Vector out = new Vector(l.getX() - h.x, 0, l.getZ() - h.z);
            if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
            hit(p, RISE_DMG, "cause_g154_rise");
            if (!game.players.launch(p, 3.5, out.normalize().multiply(0.5), 0, this, "cause_g154_rise")) knock(p, out, 0.9, 0.7);
        }
        Location at = new Location(game.world, h.x, fl, h.z);
        G148Sky.quake(game, at, (float) RISE_R + 1);
        sound("roar", Sound.ENTITY_RAVAGER_ROAR, 0.6f, 64);
        game.horizon.shake(0.3, 10);
    }

    private void sinkStart(Head h) {
        h.part.state("");
        h.set(HP.SINK);
        sound("quake", Sound.BLOCK_GRAVEL_BREAK, 0.6f, 64);
    }

    /** 이 머리가 물 사람: 목 길이 안 서 있는 사람 가운데 트럭 쪽에 가까운 사람. */
    private Player preyFor(Head h) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        Vector c = G148Sky.center();
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double d = Math.hypot(l.getX() - h.x, l.getZ() - h.z);
            if (d > BITE_REACH) continue;
            double score = d + 0.3 * Math.hypot(l.getX() - c.getX(), l.getZ() - c.getZ());
            if (score < bd) {
                bd = score;
                best = p;
            }
        }
        return best;
    }

    private float yawTo(Head h, Location l) {
        return (float) Math.toDegrees(Math.atan2(-(l.getX() - h.x), l.getZ() - h.z));
    }

    /** 내리꽂을 각: 표적까지 거리에 맞춰(얼굴이 그 자리에 닿게, 15..85 도). */
    private double strikeAngle(Head h) {
        double d = h.prey != null ? Math.hypot(h.prey.getLocation().getX() - h.x, h.prey.getLocation().getZ() - h.z) : 6;
        double reach = FACE_UP * 1.45;
        return Math.toDegrees(Math.asin(Math.max(0.25, Math.min(0.99, d / reach))));
    }

    private void bite(Head h) {
        Vector f = h.face();
        sound("bite", Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.6f, 32);
        Player victim = null;
        double bd = 3.2;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double d = new Vector(l.getX(), l.getY() + 0.9, l.getZ()).distance(f);
            if (d < bd) {
                bd = d;
                victim = p;
            }
        }
        if (victim == null) return;
        hit(victim, BITE_DMG, "cause_g154");
        PState s = game.players.get(victim);
        if (s != null && s.life == Life.ALIVE) {
            game.players.trap(victim, this);
            h.held.add(victim);
        }
    }

    // ------------------------------------------------------------------ 몸

    private void pose(Head h, int ticks) {
        if (h.part == null) return;
        Matrix4f m = new Matrix4f().translate((float) (h.x - pos.getX()), (float) (floor() - pos.getY()), (float) (h.z - pos.getZ()))
                .rotateY((float) Math.toRadians(-h.yaw));
        if (h.st == HP.BURIED) {
            h.part.pose(m, ticks);
            return;
        }
        if (h.st == HP.SWELL) {
            h.part.pose(m.scale(1f + (float) (h.stretch - 1) * 1.5f, (float) h.stretch, 1f + (float) (h.stretch - 1) * 1.5f), ticks);
            return;
        }
        float sink = (float) ((h.rise - 1) * (NECK + 3.5));
        h.part.pose(m.translate(0, sink, 0).rotateX((float) Math.toRadians(h.lean)).scale(1f, (float) h.stretch, 1f), ticks);
    }

    private void dyingTick() {
        int t = age - dieAt;
        for (Head h : heads) {
            if (h.st != HP.BURIED) {
                h.rise = Math.max(0, h.rise - 0.05);
                h.lean += 3;
            }
        }
        if (every(2)) for (Head h : heads) pose(h, 2);
        if (t >= 30) {
            for (Head h : heads) G148Sky.quake(game, new Location(game.world, h.x, floor(), h.z), 3f);
            game.horizon.shake(0.6, 14);
            untargetable = false;
            die(last != null ? last : new Damage(0, DamageType.OTHER, null, pos.clone(), null, "sink", 0));
        }
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: 솟은 머리마다 얼굴(r 1.9)과 목(r 1.1). [4] = 머리 번호. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(16);
        for (Head h : heads) {
            if (!(h.up() || h.st == HP.SINK) || h.rise < 0.2) continue;
            Vector f = h.face();
            out.add(new double[] {f.getX(), f.getY(), f.getZ(), 1.9, h.i});
            out.add(new double[] {(f.getX() + h.x) / 2, (f.getY() + floor()) / 2, (f.getZ() + h.z) / 2, 1.1, h.i});
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || dying) return false;
        List<double[]> s = spheres();
        if (s.isEmpty()) return false;
        int part = G148Sky.partAt(s, d.at());
        if (part < 0 || part >= HEADS) return false;
        Head h = heads[part];
        h.lastHit = now();
        // 솟은 머리가 모두 묶였나(이번 맞음 포함)
        boolean allBound = true;
        int up = 0;
        for (Head x : heads) {
            if (!x.up()) continue;
            up++;
            allBound &= x.bound();
        }
        allBound &= up > 0;
        Damage dd = allBound ? d.times(2) : d;
        double amt = dd.amount();
        hp -= amt;
        if (!allBound) pool += amt;
        game.mobs.onHurt(this, dd);
        // 이 머리가 삼킨 사람은 맞히면 뱉음
        if (!h.held.isEmpty()) {
            for (Player p : new ArrayList<>(h.held)) {
                PState st = game.players.get(p);
                if (st != null && st.life == Life.TRAPPED && st.eater == this) game.players.free(p);
            }
            h.held.clear();
        }
        if (hp <= 0) {
            last = dd;
            dying = true;
            dieAt = age;
            untargetable = true;
            mark(null);
        }
        return true;
    }

    @Override
    public String toString() {
        int up = 0, bound = 0, held = 0;
        for (Head h : heads) {
            if (h.up()) {
                up++;
                if (h.bound()) bound++;
            }
            held += h.held.size();
        }
        return super.toString() + " up=" + up + " bound=" + bound + " pool=" + Math.round(pool) + " held=" + held + (dying ? " dying" : "");
    }
}
