package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G182 요새 게이브(3막, 방패, 원거리, 보상). 한 변 4.6 블록 닫힌 얼굴 상자가 모서리 창을 다리 삼아 뒤뚱뒤뚱 기어와 트럭(없으면 중앙 원 가운데)에서
 * 20 블록 자리에 주저앉아 창을 땅에 박는다. 4 초마다 뿔나팔 뒤 총안을 열어 가장 가까운 보이는 사람에게 가시 셋(한 개 12)을 쏘고, 다가온 사람은
 * 모서리 창으로 찔러 18 과 밀림. 10 초마다 한 뼘씩 땅으로 파고들어 50 초 뒤에는 위만 남는다(다 파고들면 두 배로 자주 쏘고 빗장을 깰 사람 수가 하나 는다).
 * 닫힌 동안 받는 피해는 20 분의 1. 앞면 빗장을 서로 다른 다섯 사람(사람이 적으면 서 있는 사람의 절반)이 1 초 안에 맞히면 빗장이 부러져 6 초 열리고
 * 그동안 피해 두 배. 맞힌 사람 수만큼 등불이 1 초 켜진다. 파고들기 전에 깨면 팀 전원 20 원(보상 배율).
 *
 * <p>팩 계약(tools/gabe/sp/g182.py): 몸 gabe/g182(shrink 0.38, 상태 ""(2 자세 걸음), "sit", 부위 slit, broken, l1..l5), 가시 gabe/g182_spike.
 * 소리 horn(48), shoot(32), stab(24), crack(48), giggle(24), thud(48). 글귀 cause_g182, cause_g182_stab.
 *
 * <p>도감과 다른 점: 한 변 4 에서 4.6 블록. 디스플레이 하나(빗장과 등불, 창을 몸에 구움) + 날아가는 가시. 가시는 쏜 순간 그 사람 자리로 곧게 날아가
 * 닿는 자리 1.1 블록 안 사람이 맞는다(비키면 피함).
 */
public final class G182 extends Gabe {

    static final double H = 2.3, WALK_HOVER = 3.2, SIT_HOVER = 2.3, BAR_Y = 1.25, BAR_Z = 2.72, MUZZLE_Y = 0.22;
    static final double SIT_R = 20, SINK_STEP = 0.6, SINK_MAX = 3.0;
    static final double SPIKE_DMG = 12, SPIKE_V = 2.2, SPIKE_HIT_R = 1.1, RANGE = 30, STAB_DMG = 18, STAB_R = 3.6;
    static final double CLOSED_MUL = 0.05, OPEN_MUL = 2.0;
    static final int FIRE_CD = 80, FIRE_TELL = 16, VOLLEY = 3, VOLLEY_GAP = 12, SINK_CD = 200, OPEN_T = 120, WINDOW = 20, STAB_CD = 30;
    static final int TEAM_BONUS = 20;

    enum Phase { CRAWL, SIT }

    private Phase phase = Phase.CRAWL;
    private final Vector seat = new Vector();
    private boolean hasSeat;
    private double sunk;
    private int sinkAt, fireAt, fireStart = -1, shotsLeft, openUntil = -1, stabAt, lampsLit, breaks, volleys;
    private boolean paidBonus;
    private Player volleyAt;
    private final Map<UUID, Integer> barHits = new HashMap<>();
    private final List<Flight> flights = new ArrayList<>(6);
    private final List<double[]> sph = new ArrayList<>(12);
    private int sphAge = -1;

    /** 날아가는 가시 하나. */
    private static final class Flight {
        Body.Part part;
        Vector from, to;
        int start, len;
    }

    public G182(Game game, Species sp) {
        super(game, sp);
        radius = 2.0;
        hover = WALK_HOVER;
        baseSpeed = 0.1;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragKick = 0.25;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g182", 7f, 7f);
        body.viewRange(3f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Vector c = G141Kit.middle();
        Vector out = pos.clone().subtract(c).setY(0);
        if (out.lengthSquared() < 1e-4) out = new Vector(0, 0, 1);
        out.normalize();
        seat.copy(c.clone().add(out.multiply(SIT_R)));
        hasSeat = true;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (phase == Phase.CRAWL) crawl();
        else sit();
        flightsTick();
        lampsTick();
        if (openUntil >= 0 && age >= openUntil) close();
    }

    /** 모서리 창 다리로 뒤뚱뒤뚱(몸이 좌우로 기우뚱). */
    private void crawl() {
        if (!hasSeat) {
            follow(speed());
            return;
        }
        Vector d = seat.clone().subtract(pos).setY(0);
        double l = d.length();
        if (l < 0.8) {
            hold();
            phase = Phase.SIT;
            state("sit");
            hover = SIT_HOVER;
            body.roll = 0;
            body.pose(6);
            sinkAt = age + cd(SINK_CD);
            fireAt = age + 30;
            sound("thud", Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.5f, 48);
            G129Kit.stomp(this, pos.clone().setY(floor()), 48, 8, 1f);
            G129Kit.ring(this, pos, 3.4, 14);
            if (game.horizon != null) game.horizon.shake(0.3, 8);
            return;
        }
        double v = speed();
        vel.setX(d.getX() / l * v);
        vel.setZ(d.getZ() / l * v);
        G141Kit.turnFlat(this, seat, 2f);
        if (age % 8 == 0) {
            body.roll = (age / 8) % 2 == 0 ? 4f : -4f;
            body.pose(8);
        }
        stab();
    }

    private void sit() {
        hold();
        if (sunk < SINK_MAX && age >= sinkAt) {
            sunk = Math.min(SINK_MAX, sunk + SINK_STEP);
            body.offset.set(0, (float) -sunk, 0);
            body.pose(20);
            sinkAt = age + cd(SINK_CD);
            G129Kit.ring(this, pos, 3.2, 16);
            G141Kit.dust(this, pos, 4f, 16);
            sound("thud", Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.4f, 48);
        }
        fire();
        stab();
    }

    private boolean deep() {
        return sunk >= SINK_MAX - 1e-6;
    }

    /** 빗장을 깰 사람 수: 다섯과 서 있는 사람 절반(올림) 가운데 작은 쪽, 다 파고들면 하나 더. */
    private int need() {
        int n = Math.max(1, Math.min(5, (int) Math.ceil(game.mobs.standing().size() / 2.0)));
        return Math.min(5, n + (deep() ? 1 : 0));
    }

    /** 뿔나팔(예고) → 총안 열고 가시 셋. */
    private void fire() {
        if (fireStart < 0) {
            if (age < fireAt) return;
            Player p = aimTarget();
            if (p == null) {
                fireAt = age + 20;
                return;
            }
            volleyAt = p;
            mark(p);
            fireStart = age;
            shotsLeft = VOLLEY;
            sound("horn", Sound.EVENT_RAID_HORN, 0.6f, 48);
            return;
        }
        int t = age - fireStart;
        if (t == FIRE_TELL) body.look.on.add("slit");
        if (t >= FIRE_TELL && shotsLeft > 0 && (t - FIRE_TELL) % VOLLEY_GAP == 0) {
            Player p = volleyAt;
            if (p == null || !p.isOnline() || !standing(p)) p = aimTarget();
            if (p != null) shoot(p);
            shotsLeft--;
        }
        if (shotsLeft <= 0 && t >= FIRE_TELL + VOLLEY_GAP * VOLLEY) {
            fireStart = -1;
            volleys++;
            if (openUntil < 0) body.look.on.remove("slit");
            mark(null);
            fireAt = age + cd(deep() ? FIRE_CD / 2 : FIRE_CD);
        }
    }

    /** 30 블록 안 보이는 가장 가까운 서 있는 사람. */
    private Player aimTarget() {
        Player p = acquire(RANGE);
        if (p == null) {
            Player q = nearest(RANGE);
            if (q != null && dist(q) <= RANGE) p = q;
        }
        return p;
    }

    private Vector center() {
        return new Vector(pos.getX(), pos.getY() - sunk, pos.getZ());
    }

    private void shoot(Player p) {
        Location l = p.getLocation();
        Vector to = new Vector(l.getX(), l.getY() + 1.0, l.getZ());
        Vector c = center();
        Vector dir = to.clone().subtract(c).setY(0);
        if (dir.lengthSquared() < 1e-4) dir = G141Kit.fwd(this);
        dir.normalize();
        Vector from = c.clone().add(dir.clone().multiply(H + 0.4)).add(new Vector(0, MUZZLE_Y, 0));
        Flight f = new Flight();
        f.part = body.attach("gabe/g182_spike");
        if (f.part == null) return;
        f.part.follow = false;
        f.from = from;
        f.to = to;
        f.start = age;
        f.len = Math.max(3, (int) Math.ceil(from.distance(to) / SPIKE_V));
        poseFlight(f, 0, 0);
        flights.add(f);
        sound("shoot", Sound.ENTITY_ARROW_SHOOT, 0.7f, 32);
    }

    private void poseFlight(Flight f, double t, int ticks) {
        Vector p = f.from.clone().multiply(1 - t).add(f.to.clone().multiply(t));
        Vector d = f.to.clone().subtract(f.from);
        Vector3f u = new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ()).normalize();
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), u);
        f.part.pose(new Matrix4f().translate((float) (p.getX() - pos.getX()), (float) (p.getY() - pos.getY()), (float) (p.getZ() - pos.getZ())).rotate(q),
            ticks);
    }

    private void flightsTick() {
        for (Iterator<Flight> it = flights.iterator(); it.hasNext();) {
            Flight f = it.next();
            double t = (age - f.start) / (double) f.len;
            if (t >= 1) {
                f.part.remove();
                it.remove();
                for (Player q : G141Kit.within(this, f.to, SPIKE_HIT_R, 2.2)) hit(q, SPIKE_DMG, "cause_g182");
                continue;
            }
            poseFlight(f, Math.min(1, t + 1.0 / f.len), 1);
        }
    }

    /** 곁에 온 사람을 모서리 창으로. */
    private void stab() {
        if (age < stabAt) return;
        boolean any = false;
        Vector c = center();
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double h = Math.hypot(l.getX() - c.getX(), l.getZ() - c.getZ());
            if (h > STAB_R || Math.abs(l.getY() - (pos.getY() - hover)) > 3) continue;
            hit(p, STAB_DMG, "cause_g182_stab");
            knock(p, new Vector(l.getX() - c.getX(), 0, l.getZ() - c.getZ()), 0.9, 0.3);
            any = true;
        }
        if (any) {
            stabAt = age + cd(STAB_CD);
            sound("stab", Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 24);
        }
    }

    // ------------------------------------------------------------------ 빗장

    private void lampsTick() {
        if (openUntil >= 0) {
            setLamps(5);
            return;
        }
        if (!barHits.isEmpty()) barHits.values().removeIf(t -> age - t > WINDOW);
        int n = Math.min(5, barHits.size());
        if (n == 0 && lampsLit >= 3) sound("giggle", Sound.ENTITY_WITCH_CELEBRATE, 1.2f, 24);
        setLamps(n);
    }

    private void setLamps(int n) {
        if (n == lampsLit) return;
        lampsLit = n;
        for (int i = 1; i <= 5; i++) {
            if (i <= n) body.look.on.add("l" + i);
            else body.look.on.remove("l" + i);
        }
    }

    private void open() {
        breaks++;
        openUntil = age + OPEN_T;
        barHits.clear();
        body.look.on.add("broken");
        body.look.on.add("slit");
        body.look.open = true;
        setLamps(5);
        sound("crack", Sound.BLOCK_ANVIL_BREAK, 0.6f, 48);
        G141Kit.dust(this, G141Kit.at(this, 0, BAR_Y - sunk, BAR_Z), 2.5f, 12);
        if (!paidBonus && !deep()) {
            paidBonus = true;
            game.players.teamMoney((int) Math.max(1, Math.round(TEAM_BONUS * game.director.rewardMul())));
        }
    }

    private void close() {
        openUntil = -1;
        body.look.on.remove("broken");
        if (fireStart < 0) body.look.on.remove("slit");
        body.look.open = target != null;
        setLamps(0);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        Vector c = center();
        sph.add(new double[] {c.getX(), c.getY(), c.getZ(), H + 0.05});
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sy = -1; sy <= 1; sy += 2)
                for (int sz = -1; sz <= 1; sz += 2) {
                    Vector w = G141Kit.at(this, sx * 1.6, sy * 1.6 - sunk, sz * 1.6);
                    sph.add(new double[] {w.getX(), w.getY(), w.getZ(), 0.85});
                }
        Vector b = G141Kit.at(this, 0, BAR_Y - sunk, BAR_Z);
        sph.add(new double[] {b.getX(), b.getY(), b.getZ(), 0.6});                 // 마지막: 빗장
        sphAge = age;
        return sph;
    }

    private boolean onBar(Damage d) {
        if (d.at() == null) return false;
        List<double[]> s = spheres();
        double[] bar = s.get(s.size() - 1);
        double best = Double.MAX_VALUE, bd = 0;
        for (double[] c : s) {
            double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
            if (dd < best) best = dd;
            if (c == bar) bd = dd;
        }
        return bd <= best + 1e-6;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (openUntil >= 0) return super.damage(d.times(OPEN_MUL));
        if (d.by() != null && onBar(d)) {
            barHits.put(d.by().getUniqueId(), age);
            barHits.values().removeIf(t -> age - t > WINDOW);
            if (barHits.size() >= need()) {
                open();
                return super.damage(d.times(OPEN_MUL));
            }
        }
        return super.damage(d.times(CLOSED_MUL));
    }

    @Override
    public void die(Damage d) {
        for (Flight f : flights) f.part.remove();
        flights.clear();
        super.die(d);
    }

    @Override
    public void remove() {
        for (Flight f : flights) f.part.remove();
        flights.clear();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " sunk=" + String.format("%.1f", sunk) + " need=" + need() + " hits=" + barHits.size() + " open="
            + (openUntil >= 0) + " breaks=" + breaks + " volleys=" + volleys + " bonus=" + paidBonus;
    }
}
