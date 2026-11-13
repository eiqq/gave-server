package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G151 꿰뚫는 작살 게이브(정예, 돌격). 트럭(없으면 중앙) 둘레 밖 30블록에 떠서 2초 가만히 겨누고(땅에 분홍 선이 그어짐), 그 선을 따라 트럭 둘레를
 * 한 틱에 꿰뚫어 반대편 30블록까지 간다. 선 위 0.8블록 안 사람은 38, 길목의 다른 게이브는 꿰여 죽는다(정예, 큰 몸은 60). 지나간 선 위에는 가슴
 * 높이 살 줄이 남아 걸리면 6 과 0.5초 휘청(웅크리면 밑으로 지나감, 쏘면 끊김: 줄 체력 20). 거기서 1.5초 멈춰 줄을 감으며 돌아서고(이때만 맞음),
 * 다른 각도로 다시 겨눈다. 선은 사람이 가장 많이 걸리게 잡힌다.
 *
 * <p>바꾼 것: 남은 줄은 다음 꿰뚫기 때 감겨 사라진다(멈춤 1.5초 + 겨눔 2초 = 3.5초 남음). 줄 셋이 동시에 남아 그물이 되는 것은 뺐다.
 * 겨누는 선과 줄은 종 전용 모델(g151_rope)을 연출 디스플레이에 실어 늘인다(효과 예산 안).
 *
 * <p>팩 계약(tools/gabe/sp/g151.py): 모델 gave:gabe/g151 상태 "", gave:gabe/g151_rope 상태 "", "line", 소리 ring(48), pierce(32), reel(16),
 * 글귀 cause_g151, cause_g151_rope.
 */
public final class G151 extends Gabe {

    static final double HOVER = 1.2, SPEED = 0.24, R_AIM = 30, LINE_HALF = 0.8, PIERCE = 38, PIERCE_BIG = 60;
    static final double ROPE_Y = 1.25, ROPE_R = 0.5, ROPE_DMG = 6, ROPE_HP = 20, MIN_LEN = 40, MAX_LEN = 72;
    static final int AIM = 40, REEL = 30, APPROACH_MAX = 700, ROPE_HIT_CD = 20;

    enum Phase { APPROACH, AIM, REEL }

    /** 남은 줄(쏘면 끊김). */
    private final class Rope implements Mobs.Shootable {
        final Vector a, b;
        final ItemDisplay d;
        double hp = ROPE_HP;
        boolean cut;

        Rope(Vector a, Vector b, ItemDisplay d) {
            this.a = a;
            this.b = b;
            this.d = d;
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (cut) return Double.POSITIVE_INFINITY;
            // 광선과 선분의 가장 가까운 거리
            Vector u = dir, v = b.clone().subtract(a), w = o.clone().subtract(a);
            double uu = u.dot(u), uv = u.dot(v), vv = v.dot(v), uw = u.dot(w), vw = v.dot(w);
            double den = uu * vv - uv * uv;
            if (den < 1e-9) return Double.POSITIVE_INFINITY;
            double s = (uv * vw - vv * uw) / den, t = (uu * vw - uv * uw) / den;
            if (s < 0 || s > max || t < 0 || t > 1) return Double.POSITIVE_INFINITY;
            Vector p = o.clone().add(u.clone().multiply(s)), q = a.clone().add(v.clone().multiply(t));
            return p.distanceSquared(q) <= 0.15 * 0.15 ? s : Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean shot(Damage dmg) {
            if (cut) return false;
            hp -= dmg.amount();
            if (hp <= 0) snap();
            return false;
        }

        void snap() {
            cut = true;
            if (d != null && d.isValid()) d.remove();
            game.mobs.removeShootable(this);
            sound("reel", Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.2f, 16);
        }

        @Override
        public Vector center() {
            return a.clone().add(b).multiply(0.5);
        }

        @Override
        public boolean gone() {
            return cut || dead;
        }
    }

    private Phase phase = Phase.APPROACH;
    private int phaseAt;
    private final Vector lineFrom = new Vector(), lineTo = new Vector();
    private ItemDisplay aimLine;
    private Rope rope;
    private final Map<UUID, Long> ropeHit = new HashMap<>();

    public G151(Game game, Species sp) {
        super(game, sp);
        radius = 0.3;
        hover = HOVER;
        baseSpeed = SPEED;
        noPush = true;
        noBite = true;
        untargetable = true;
        ragKick = 1.4;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g151", 3f, 1f);
    }

    private static Vector mid() {
        return Truck.live() ? Truck.center() : new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
    }

    private double fromMid() {
        Vector m = mid();
        return Math.hypot(pos.getX() - m.getX(), pos.getZ() - m.getZ());
    }

    @Override
    public void tick() {
        ropeTick();
        switch (phase) {
            case APPROACH -> approach();
            case AIM -> aim();
            case REEL -> reel();
        }
    }

    private void go(Phase ph) {
        phase = ph;
        phaseAt = age;
    }

    private void approach() {
        untargetable = true;
        Player p = nearest(400);
        if (p != target) mark(p);
        follow(speed());
        if (Math.hypot(vel.getX(), vel.getZ()) > 1e-3) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        if (fromMid() <= R_AIM + 1 || age >= APPROACH_MAX) startAim();
    }

    /** 사람이 가장 많이 걸리는 선을 고르고 땅에 그음. */
    private void startAim() {
        go(Phase.AIM);
        untargetable = true;
        hold();
        Vector from = new Vector(pos.getX(), 0, pos.getZ());
        Vector m = mid();
        Vector best = null;
        int bestN = -1;
        double bestOff = Double.MAX_VALUE;
        List<Vector> cands = new ArrayList<>();
        cands.add(new Vector(m.getX() - from.getX(), 0, m.getZ() - from.getZ()));
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - from.getX(), l.getZ() - from.getZ()) < 90) cands.add(new Vector(l.getX() - from.getX(), 0, l.getZ() - from.getZ()));
        }
        double len = Math.max(MIN_LEN, Math.min(MAX_LEN, Math.hypot(m.getX() - from.getX(), m.getZ() - from.getZ()) + R_AIM));
        for (Vector c : cands) {
            if (c.lengthSquared() < 1e-4) continue;
            Vector d = c.clone().normalize();
            Vector to = from.clone().add(d.clone().multiply(len));
            int n = 0;
            for (Player p : game.mobs.standing()) if (segDist(p.getLocation().getX(), p.getLocation().getZ(), from, to) <= LINE_HALF + 0.5) n++;
            double off = segDist(m.getX(), m.getZ(), from, to);
            if (n > bestN || n == bestN && off < bestOff) {
                bestN = n;
                bestOff = off;
                best = d;
            }
        }
        if (best == null) best = new Vector(1, 0, 0);
        Vector to = from.clone().add(best.clone().multiply(len));
        // 몹의 끝(R_EDGE) 안으로
        double er = Math.hypot(to.getX() - (Plaza.CX + 0.5), to.getZ() - (Plaza.CZ + 0.5));
        if (er > Plaza.R_EDGE - 4) {
            double k = (Plaza.R_EDGE - 4) / er;
            to.setX(Plaza.CX + 0.5 + (to.getX() - (Plaza.CX + 0.5)) * k).setZ(Plaza.CZ + 0.5 + (to.getZ() - (Plaza.CZ + 0.5)) * k);
        }
        lineFrom.copy(from);
        lineTo.copy(to);
        yaw = (float) Math.toDegrees(Math.atan2(-best.getX(), best.getZ()));
        pitch = 0;
        Vector way = to.clone().subtract(from);
        double l = way.length();
        aimLine = strip("line", new Location(game.world, from.getX(), game.mobs.flow.floor(from.getX(), from.getZ()) + 0.05, from.getZ()), way.multiply(1 / l),
            1.4f, (float) l, AIM + 2);
        sound("ring", Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.8f, 48);
    }

    private ItemDisplay strip(String state, Location at, Vector dir, float w, float len, int life) {
        ItemDisplay d = game.shotFx.spawn(at, "fx_quad", 0, 0, ShotFx.along(dir, w, len), Display.Billboard.FIXED, false, life, ShotFx.KEY);
        if (d == null) return null;
        d.setItemStack(Body.item("gabe/g151_rope", state, new LookState(), game.worldTime()));
        d.setBrightness(new Display.Brightness(15, 15));
        d.setDisplayWidth(0);
        d.setDisplayHeight(0);
        d.setViewRange(4f);
        return d;
    }

    private void aim() {
        hold();
        untargetable = true;
        int t = age - phaseAt;
        // 겨누는 동안 '찡'이 올라감
        if (t == AIM / 2) sound("ring", Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 2f, 48);
        if (t < cd(AIM)) return;
        pierce();
    }

    /** 선을 따라 한 틱에 꿰뚫음: 사람 38, 게이브는 꿰여 죽음. 지나간 선 위에 줄이 남는다. */
    private void pierce() {
        if (aimLine != null && aimLine.isValid()) aimLine.remove();
        aimLine = null;
        if (rope != null) rope.snap();
        rope = null;
        double y = game.mobs.flow.floor(lineTo.getX(), lineTo.getZ()) + HOVER;
        Vector from = new Vector(pos.getX(), pos.getY(), pos.getZ());
        Vector to = new Vector(lineTo.getX(), y, lineTo.getZ());
        // 사람
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (segDist(l.getX(), l.getZ(), lineFrom, lineTo) > LINE_HALF + 0.3) continue;
            hit(p, PIERCE, "cause_g151");
            game.shotFx.bloodHit(l.clone().add(0, 1.1, 0), to.clone().subtract(from).normalize(), true);
        }
        // 길목의 게이브
        List<Gabe> skewer = new ArrayList<>();
        for (Gabe g : game.mobs.all()) {
            if (g == this || g.dead || g.untargetable || g.sp.is(Role.BOSS)) continue;
            if (Math.abs(g.pos.getY() - pos.getY()) > 3) continue;
            if (segDist(g.pos.getX(), g.pos.getZ(), lineFrom, lineTo) <= LINE_HALF + g.radius) skewer.add(g);
        }
        Vector dir = to.clone().subtract(from).normalize();
        for (Gabe g : skewer) {
            boolean tough = g.big || g.sp.is(Role.ELITE);
            double amt = tough ? PIERCE_BIG : g.hp + 1;
            game.mobs.damage(g, new Damage(amt, DamageType.OTHER, null, g.pos.clone(), dir.clone(), "g151", 0));
        }
        game.shotFx.streak(from.toLocation(game.world), to.toLocation(game.world));
        pos.copy(to);
        hold();
        yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        body.pose(1);
        sound("pierce", Sound.ITEM_TRIDENT_THROW, 0.7f, 32);
        // 지나간 선 위의 줄(가슴 높이)
        double fy = game.mobs.flow.floor(lineFrom.getX(), lineFrom.getZ()) + ROPE_Y;
        Vector a = new Vector(lineFrom.getX(), fy, lineFrom.getZ()), b = new Vector(lineTo.getX(), game.mobs.flow.floor(lineTo.getX(), lineTo.getZ()) + ROPE_Y,
            lineTo.getZ());
        Vector way = b.clone().subtract(a);
        double l = way.length();
        if (l > 1) {
            ItemDisplay d = strip("", a.toLocation(game.world), way.clone().multiply(1 / l), 1f, (float) l, REEL + AIM + 6);
            rope = new Rope(a, b, d);
            game.mobs.addShootable(rope);
        }
        go(Phase.REEL);
        untargetable = false;
    }

    private void reel() {
        hold();
        untargetable = false;
        int t = age - phaseAt;
        if (t == 4) sound("reel", Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 16);
        turnTo(mid().setY(pos.getY()), 7f);
        if (t >= REEL) startAim();
    }

    /** 남은 줄에 걸림: 웅크린 사람은 밑으로 지나감. */
    private void ropeTick() {
        if (rope == null || rope.cut) return;
        long now = now();
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (p.isSneaking() || Math.abs(l.getY() + 1.0 - rope.a.getY()) > 1.0) continue;
            if (segDist(l.getX(), l.getZ(), rope.a, rope.b) > ROPE_R) continue;
            Long at = ropeHit.get(p.getUniqueId());
            if (at != null && now - at < ROPE_HIT_CD) continue;
            ropeHit.put(p.getUniqueId(), now);
            hit(p, ROPE_DMG, "cause_g151_rope");
            game.players.slow(p, 0.5, 10, "g151_rope");
            Guns.kick(p, (game.random.nextFloat() - 0.5f) * 8f, -2f);
        }
    }

    /** 점 (x, z)에서 선분 a-b(수평)까지 거리. */
    static double segDist(double x, double z, Vector a, Vector b) {
        double vx = b.getX() - a.getX(), vz = b.getZ() - a.getZ(), wx = x - a.getX(), wz = z - a.getZ();
        double vv = vx * vx + vz * vz;
        double t = vv < 1e-9 ? 0 : Math.max(0, Math.min(1, (wx * vx + wz * vz) / vv));
        double dx = wx - vx * t, dz = wz - vz * t;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
        List<double[]> out = new ArrayList<>(3);
        for (double k : new double[] {-0.75, 0, 0.8}) out.add(new double[] {pos.getX() + fx * k, pos.getY(), pos.getZ() + fz * k, k < 0 ? 0.3 : 0.22});
        return out;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        cleanup();
        super.die(d);
    }

    @Override
    public void remove() {
        cleanup();
        super.remove();
    }

    private void cleanup() {
        if (aimLine != null && aimLine.isValid()) aimLine.remove();
        aimLine = null;
        if (rope != null && !rope.cut) {
            rope.cut = true;
            if (rope.d != null && rope.d.isValid()) rope.d.remove();
            game.mobs.removeShootable(rope);
        }
        rope = null;
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + " mid=" + Math.round(fromMid()) + (rope != null && !rope.cut ? " rope" : "");
    }
}
