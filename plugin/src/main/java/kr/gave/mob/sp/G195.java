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

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;

/**
 * G195 어둠 장막 게이브(4막, 방패, 지원). 작은 연 얼굴 여덟이 윗단을 물고 나르는 폭 14, 높이 6 블록 검은 장막이 가장 가까운 사람 쪽으로 밀려온다
 * (0.12 블록/틱, 지나치면 천천히 돌아서 다시 쓸고 감).
 *
 * <ul>
 * <li>가림(UNTARGET): 장막 뒤 3.5 블록 안 게이브(틈 뒤, 보스, 거대한 몸은 빼고)는 매 틱 가려져 탄, 폭발, 불이 지나간다. 장막 면은 Mobs.Shootable 로
 * 총 광선을 먼저 받아 버린다(피해 0). 뒤 게이브는 장막을 앞지르지 못하게 빠르기를 장막에 맞춘다(바짝 붙어 따라옴).</li>
 * <li>덮고 지나감: 장막 면이 사람 몸을 지나가면 18 과 2 초 눈앞이 캄캄함(본인만, 덮개 dark).</li>
 * <li>연(spheres, 하나 체력 = 몸 체력의 8 분의 1): 떨어질 때마다 그 기둥 윗단이 처져 틈(그 뒤 게이브가 드러나고 탄이 지나감). 넷째가 떨어지면 1 초
 * 남은 연이 치솟았다가(예고) 장막이 앞으로 무너져 깔린 사람 24 와 넘어짐 0.6 초, 3 초 뒤 남은 연이 다시 들어 올림. 여덟이 다 떨어지면 죽고 뒤에 있던
 * 게이브가 1 초 멍하니 선다.</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g195.py): 장막 gabe/g195(shrink 0.19, 상태 "", "fallen", 부위 s1..s8, 실눈 뜸이면 무늬 눈이 뜸), 연 gabe/g195_kites
 * (부위 k1..k8). 소리 dread(64), pass(24), kite(32), collapse(48). 글귀 cause_g195, cause_g195_fall.
 *
 * <p>도감과 다른 점: 디스플레이 둘(장막, 연 여덟 한 부품). 무너지는 것은 한 판에 한 번(넷째 연). 눈앞 캄캄함은 공용 덮개 dark. 뒤 게이브를 붙잡아
 * 데려오지는 않고 이미 뒤에 있는 것만 가리고 빠르기를 맞춘다.
 */
public final class G195 extends Gabe {

    static final double HOVER = 3.0, HALF_W = 7.0, COL_W = 1.75, KITE_Y = 3.45, TOP_HIT = 2.8, SAG_HIT = 1.0, BEHIND = 3.5;
    static final double PASS_DMG = 18, FALL_DMG = 24, FALL_DEPTH = 6;
    static final int COLS = 8, BLIND = 40, FALL_TELL = 20, FALLEN = 60, LIFT = 60, STUN = 20, DREAD_EVERY = 70;
    static final float TURN = 1.6f;

    enum Phase { DRIFT, RISE_UP, FALLEN, LIFT }

    private Phase phase = Phase.DRIFT;
    private int phaseAt, dreadAt, openUntil, collapses, passes, cloakedNow;
    private boolean collapsed;
    private final double[] kite = new double[COLS];
    private boolean kitesInit;
    private int down;
    private Body.Part kites;
    private float kitesDy, kitesDz;
    private final Map<UUID, Double> side = new HashMap<>();
    private final Veil veil = new Veil();
    private final List<Gabe> hidden = new ArrayList<>(32);
    private final List<double[]> sph = new ArrayList<>(COLS);
    private int sphAge = -1;

    public G195(Game game, Species sp) {
        super(game, sp);
        radius = 0.9;
        hover = HOVER;
        baseSpeed = 0.12;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragKick = 0.2;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g195", 15f, 8f);
        body.viewRange(3f);
        kites = body.attach("gabe/g195_kites");
        poseKites(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        game.mobs.addShootable(veil);
        Player p = nearest(400);
        if (p != null) G141Kit.faceFlat(this, chest(p));
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
        if (!kitesInit) {
            kitesInit = true;
            for (int i = 0; i < COLS; i++) kite[i] = Math.max(1, maxHp / COLS);
        }
        if (age >= dreadAt) {
            dreadAt = age + DREAD_EVERY;
            sound("dread", Sound.AMBIENT_CAVE, 0.5f, 64);
        }
        switch (phase) {
            case DRIFT -> drift();
            case RISE_UP -> riseUp();
            case FALLEN -> fallen();
            case LIFT -> lift();
        }
        if (openUntil > 0 && age >= openUntil) {
            openUntil = 0;
            body.look.open = false;
        }
    }

    /** 가장 가까운 사람 쪽으로 밀려옴(천천히 돎), 덮고 지나감, 뒤 게이브를 가림. */
    private void drift() {
        Player p = nearest(400);
        if (p != null) {
            G141Kit.turnFlat(this, chest(p), TURN);
            if (target != p) target = p;
        }
        Vector f = G141Kit.fwd(this);
        double v = speed();
        vel.setX(f.getX() * v);
        vel.setZ(f.getZ() * v);
        passTick();
        shroud(v);
    }

    /** 장막 틀: 사람 자리 → [옆 u(몸 왼쪽 +), 앞 f, 바닥에서 높이]. */
    private double[] local(Location l) {
        Vector f = G141Kit.fwd(this);
        double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
        double fw = dx * f.getX() + dz * f.getZ();
        double u = dx * f.getZ() - dz * f.getX();
        return new double[] {u, fw, l.getY() - (pos.getY() - HOVER)};
    }

    /** 기둥 번호(u 가 -7..7 밖이면 -1). 모델 +X = 몸 왼쪽 = u +. */
    private static int column(double u) {
        if (u < -HALF_W || u > HALF_W) return -1;
        return Math.max(0, Math.min(COLS - 1, (int) Math.floor((u + HALF_W) / COL_W)));
    }

    private boolean gap(int col) {
        return col < 0 || kite[col] <= 0;
    }

    /** 장막 면이 사람을 지나가면(앞뒤가 바뀌면) 18 과 캄캄함. */
    private void passTick() {
        for (Player p : game.mobs.standing()) {
            double[] q = local(p.getLocation());
            UUID id = p.getUniqueId();
            Double prev = side.get(id);
            boolean inside = Math.abs(q[0]) <= HALF_W + 0.3 && q[2] > -1 && q[2] < 6.2 && Math.abs(q[1]) < 4;
            if (!inside) {
                side.remove(id);
                continue;
            }
            side.put(id, q[1]);
            if (prev == null || Math.signum(prev) == Math.signum(q[1]) || q[1] == 0) continue;
            int col = column(q[0]);
            double top = gap(col) ? SAG_HIT + 3 : 6;
            if (q[2] > top) continue;
            passes++;
            hit(p, PASS_DMG, "cause_g195");
            game.players.cover(p, "dark", BLIND);
            body.look.open = true;
            openUntil = age + 20;
            if (!game.director.silent()) kr.gave.Props.soundTo(p, p.getLocation(), "gabe.g195.pass", Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 0.8f, 0.6f, -1);
        }
        if (side.size() > 64) side.clear();
    }

    /** 뒤 3.5 블록 안(틈 뒤는 빼고) 게이브를 가리고, 장막을 앞지르지 못하게 빠르기를 맞춤. */
    private void shroud(double myV) {
        hidden.clear();
        Vector f = G141Kit.fwd(this);
        List<Gabe> near = game.mobs.near(pos, HALF_W + BEHIND + 1, g -> g != this && !g.dead && !g.big && !g.sp.is(Role.BOSS));
        for (Gabe g : near) {
            double dx = g.pos.getX() - pos.getX(), dz = g.pos.getZ() - pos.getZ();
            double fw = dx * f.getX() + dz * f.getZ();
            double u = dx * f.getZ() - dz * f.getX();
            if (fw > 0.3 || fw < -BEHIND || Math.abs(u) > HALF_W) continue;
            if (gap(column(u))) continue;
            g.cloak(2);
            hidden.add(g);
            // 버프를 뺀 그 개체 빠르기(지난 틱에 건 맞춤이 buffPrev 로 남아 있으므로 나눠 뺌)
            double b = g.buff();
            double gv = b > 1e-6 ? g.speed() / b : g.speed();
            if (fw > -1.6 && gv > myV && gv > 1e-6) g.speedBuff = Math.min(g.speedBuff, Math.max(0.2, myV / gv));
        }
        cloakedNow = hidden.size();
    }

    // ------------------------------------------------------------------ 연

    private void poseKites(int ticks) {
        if (kites != null) kites.pose(new Matrix4f().translate(0, kitesDy, kitesDz), ticks);
    }

    private void kiteDown(int i) {
        kite[i] = 0;
        down++;
        body.look.on.add("s" + (i + 1));
        if (kites != null) kites.look.on.add("k" + (i + 1));
        Vector at = G141Kit.at(this, -HALF_W + COL_W * (i + 0.5), KITE_Y + kitesDy, kitesDz);
        Location l = new Location(game.world, at.getX(), at.getY(), at.getZ());
        if (!game.director.silent()) kr.gave.Props.soundAround(l, "gabe.g195.kite", Sound.ENTITY_PHANTOM_DEATH, 1f, 0.9f, 0.8f, 32, -1);
        if (game.shotFx.room(ShotFx.NORMAL)) game.shotFx.bloodHit(l, new Vector(0, -1, 0), true, ShotFx.BLOOD_PINK);
        if (down == 4 && !collapsed && phase == Phase.DRIFT) {
            collapsed = true;
            // 남은 연이 한꺼번에 치솟음(예고)
            kitesDy = 1.6f;
            poseKites(8);
            phase(Phase.RISE_UP);
        }
    }

    private void riseUp() {
        hold();
        shroud(0);
        if (inPhase() < FALL_TELL) return;
        collapses++;
        state("fallen");
        kitesDy = (float) (-KITE_Y - HOVER + 0.6);
        kitesDz = (float) FALL_DEPTH;
        poseKites(6);
        sound("collapse", Sound.BLOCK_WOOL_BREAK, 0.5f, 48);
        if (game.horizon != null) game.horizon.shake(0.4, 8);
        for (Player p : game.mobs.standing()) {
            double[] q = local(p.getLocation());
            if (Math.abs(q[0]) > HALF_W || q[1] < -0.3 || q[1] > FALL_DEPTH + 0.3 || q[2] > 3) continue;
            hit(p, FALL_DMG, "cause_g195_fall");
            game.players.root(p, 12, "g195");
        }
        phase(Phase.FALLEN);
    }

    private void fallen() {
        hold();
        if (inPhase() < FALLEN) return;
        state("");
        kitesDy = 0;
        kitesDz = 0;
        poseKites(LIFT);
        phase(Phase.LIFT);
    }

    private void lift() {
        hold();
        shroud(0);
        if (inPhase() >= LIFT) phase(Phase.DRIFT);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        for (int i = 0; i < COLS; i++) {
            Vector w = G141Kit.at(this, -HALF_W + COL_W * (i + 0.5), KITE_Y + kitesDy, kitesDz);
            sph.add(new double[] {w.getX(), w.getY(), w.getZ(), kite[i] > 0 || !kitesInit ? 0.58 : 0.01});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return G141Kit.at(this, 0, KITE_Y, 0);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        int best = -1;
        if (d.at() != null) {
            List<double[]> s = spheres();
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < s.size(); i++) {
                if (kite[i] <= 0) continue;
                double[] c = s.get(i);
                double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
        }
        if (best < 0) for (int i = 0; i < COLS; i++) if (kite[i] > 0 && (best < 0 || kite[i] < kite[best])) best = i;
        if (best < 0) return false;
        double amt = Math.min(d.amount(), kite[best]);
        Damage x = amt < d.amount() ? d.times(amt / Math.max(1e-9, d.amount())) : d;
        kite[best] -= x.amount();
        boolean ok = super.damage(x);
        if (!dead && kite[best] <= 1e-6) kiteDown(best);
        boolean all = true;
        for (double k : kite) if (k > 1e-6) all = false;
        if (all && !dead) die(d);
        return ok;
    }

    /** 다 떨어지면: 뒤에 있던 게이브가 1 초 멍하니 섬(판 단위 갈고리로 20 틱 동안 빠르기 0). */
    private void stunHidden() {
        if (hidden.isEmpty()) return;
        List<Gabe> list = new ArrayList<>(hidden);
        long until = game.now() + STUN;
        String fk = "G195#" + id;
        game.mobs.field(fk, new Mobs.Field() {
            @Override
            public boolean tick(long now) {
                if (now > until) return false;
                for (Gabe g : list) if (!g.dead) g.speedBuff = 0;
                return true;
            }
        });
    }

    @Override
    public void die(Damage d) {
        stunHidden();
        game.mobs.removeShootable(veil);
        super.die(d);
    }

    @Override
    public void remove() {
        game.mobs.removeShootable(veil);
        super.remove();
    }

    // ------------------------------------------------------------------ 장막 면(총 광선을 먼저 받아 버림)

    private final class Veil implements Mobs.Shootable {
        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (dead || removed || phase == Phase.FALLEN || phase == Phase.RISE_UP && inPhase() > FALL_TELL - 2) return Double.POSITIVE_INFINITY;
            Vector f = G141Kit.fwd(G195.this);
            double den = dir.getX() * f.getX() + dir.getZ() * f.getZ();
            if (Math.abs(den) < 1e-6) return Double.POSITIVE_INFINITY;
            double t = ((pos.getX() - o.getX()) * f.getX() + (pos.getZ() - o.getZ()) * f.getZ()) / den;
            if (t < 0 || t >= max) return Double.POSITIVE_INFINITY;
            double hx = o.getX() + dir.getX() * t, hy = o.getY() + dir.getY() * t, hz = o.getZ() + dir.getZ() * t;
            double u = (hx - pos.getX()) * f.getZ() - (hz - pos.getZ()) * f.getX();
            int col = column(u);
            if (col < 0) return Double.POSITIVE_INFINITY;
            double v = hy - pos.getY();
            double top = gap(col) ? SAG_HIT : TOP_HIT;
            if (v < -HOVER || v > top) return Double.POSITIVE_INFINITY;
            return t;
        }

        @Override
        public boolean shot(Damage d) {
            return false;
        }

        @Override
        public Vector center() {
            return pos.clone();
        }

        @Override
        public boolean gone() {
            return dead || removed;
        }
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " down=" + down + " collapses=" + collapses + " passes=" + passes + " hidden=" + cloakedNow + " kites="
            + String.format("%.0f,%.0f,%.0f,%.0f,%.0f,%.0f,%.0f,%.0f", kite[0], kite[1], kite[2], kite[3], kite[4], kite[5], kite[6], kite[7]);
    }
}
