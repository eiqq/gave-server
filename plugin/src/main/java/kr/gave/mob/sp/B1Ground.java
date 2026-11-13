package kr.gave.mob.sp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.boss.Boss;
import kr.gave.mob.boss.Terrain;
import kr.gave.world.Plaza;

/**
 * B1 산더미 게이브가 맵을 쓰는 것(보스.md 12절 '맵 활용', 원정.md 10절). 모두 보스 바탕의 {@link Terrain} 으로 땅을 잠깐 바꾸고, 되돌림은 보스 감독이
 * 쉬는 시간, 새 웨이브, 판 바뀜에 한다(여기서는 수명이 다한 것만 스스로 고친다).
 *
 * <ul>
 * <li>등장: 올라오는 자리에서 사람들 쪽으로 금이 달리고 땅이 불룩 솟았다가(둔덕) 머리가 터져 나오면 구덩이가 된다. 금을 따라 솟은 길 판 여섯 장이
 * 차례로 사람들 쪽으로 솟는다.</li>
 * <li>솟은 길 판({@link Slab}): 길이 5, 사람 쪽은 3블록 벽이고 보스 쪽은 계단처럼 낮아지는 기운 판. 숨을 곳이고, 구르기를 한 번 막아 주고 부서진다.</li>
 * <li>고랑({@link Zone}): 구른 자리의 그을린 띠. 그 위의 땅 게이브가 빨라진다(보스가 떼의 길을 낸다).</li>
 * <li>진창({@link Zone}): 무더기가 지나간 발자국. 그 위의 사람이 20% 느려진다.</li>
 * </ul>
 */
final class B1Ground {

    static final int MUD_MAX = 5, FURROW_MAX = 3, SLAB_MAX = 12;
    static final Material[] MUDS = {Material.MUD, Material.PACKED_MUD, Material.ROOTED_DIRT, Material.MUD};

    /** 솟은 길 판 하나. c = 가장 높은 줄의 가운데, n = 사람 쪽 단위 벡터(그 반대쪽으로 낮아짐). */
    static final class Slab {
        final Vector c, n;
        final int[] groups = new int[3];
        long riseAt;
        boolean risen, broken;

        Slab(Vector c, Vector n) {
            this.c = c;
            this.n = n;
        }

        /** 구르기와 부딪히는 가운데(가운뎃줄). */
        Vector mid() {
            return c.clone().subtract(n);
        }
    }

    /** 띠(고랑) 또는 원(진창). b 가 null 이면 원. */
    static final class Zone {
        final int group;
        final Vector a, b;
        final double half;

        Zone(int group, Vector a, Vector b, double half) {
            this.group = group;
            this.a = a;
            this.b = b;
            this.half = half;
        }

        boolean has(double x, double z) {
            if (b == null) return (x - a.getX()) * (x - a.getX()) + (z - a.getZ()) * (z - a.getZ()) <= half * half;
            double lx = b.getX() - a.getX(), lz = b.getZ() - a.getZ(), l2 = lx * lx + lz * lz;
            double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - a.getX()) * lx + (z - a.getZ()) * lz) / l2));
            double dx = x - (a.getX() + lx * t), dz = z - (a.getZ() + lz * t);
            return dx * dx + dz * dz <= half * half;
        }
    }

    private final B1 boss;
    private final Game game;
    final List<Slab> slabs = new ArrayList<>();
    final ArrayDeque<Zone> muds = new ArrayDeque<>(), furrows = new ArrayDeque<>();
    private final List<Vector[]> plan = new ArrayList<>();
    private int planAt, mound;
    private long craterAt = -1;
    private final Vector craterC = new Vector();
    int slabsBroken;

    B1Ground(B1 boss) {
        this.boss = boss;
        this.game = boss.game;
    }

    private static Vector turned(Vector d, double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        return new Vector(d.getX() * c - d.getZ() * s, 0, d.getX() * s + d.getZ() * c);
    }

    private void push(Vector from, double r, double strength, double up) {
        for (Player p : game.mobs.standing()) {
            Vector f = p.getLocation().toVector();
            double d = Boss.horiz(f, from);
            if (d > r) continue;
            Vector out = f.subtract(from).setY(0);
            if (out.lengthSquared() < 1e-6) out = new Vector(1, 0, 0);
            boss.knock(p, out, strength, up);
        }
    }

    // ------------------------------------------------------------------ 등장

    /** 등장 시작: 금이 사람들 쪽으로 달리고 올라오는 자리가 불룩 솟는다. dir = 사람들(가운데) 쪽 단위 벡터. */
    void emergeStart(Vector at, Vector dir) {
        boss.crack(at, at.clone().add(dir.clone().multiply(28)), 3);
        for (int s = -1; s <= 1; s += 2) boss.crack(at, at.clone().add(turned(dir, s * 42).multiply(13)), 2);
        push(at, 8, 0.8, 0.4);
        mound = boss.mound(at, 6, 3, 44);
        Vector perp = new Vector(-dir.getZ(), 0, dir.getX());
        plan.clear();
        planAt = 0;
        for (int k = 0; k < 6; k++) {
            // 금 양옆으로 엇갈려: 셋째, 여섯째 판이 금 한가운데 길에 걸친다(금을 따라 곧장 구르면 거기서 막힘)
            double d = 9 + 5 * k, lat = (k % 2 == 0 ? 1 : -1) * (6.5 - k % 3);
            plan.add(new Vector[] {at.clone().add(dir.clone().multiply(d)).add(perp.clone().multiply(lat)), dir.clone()});
        }
    }

    /** 머리가 터져 나옴: 둔덕이 무너지고 그 자리가 구덩이가 된다(20초 뒤 메워짐). */
    void emergeBurst(Vector at) {
        boss.mend(mound, 8);
        craterC.copy(at);
        craterAt = game.now() + 10;
    }

    /** 금을 따라 다음 길 판이 솟는다(등장 때 여섯 번). */
    void slabNext() {
        if (planAt >= plan.size()) return;
        Vector[] p = plan.get(planAt++);
        raise(p[0], p[1]);
    }

    // ------------------------------------------------------------------ 솟은 길 판

    /** 길 판 하나를 예약(선 사람을 먼저 밀어내고 6틱 뒤 솟음). c 는 가장 높은 줄 가운데, n 은 사람 쪽. */
    Slab raise(Vector c, Vector n) {
        if (alive() >= SLAB_MAX) return null;
        Slab s = new Slab(c.clone().setY(Plaza.FLOOR_Y), n.clone());
        push(s.mid(), 3.6, 0.7, 0.35);
        s.riseAt = game.now() + 6;
        slabs.add(s);
        return s;
    }

    private void build(Slab s) {
        s.risen = true;
        Terrain t = boss.land();
        if (t == null) return;
        Vector u = new Vector(-s.n.getZ(), 0, s.n.getX());
        Material m = soil(s.c);
        for (int k = 0; k < 3; k++) {
            Vector o = s.c.clone().subtract(s.n.clone().multiply(k));
            s.groups[k] = t.wall(o.clone().subtract(u.clone().multiply(2)), o.clone().add(u.clone().multiply(2)), 1.0, 3 - k, m, 6);
        }
        if (s.groups[0] == 0 && s.groups[1] == 0 && s.groups[2] == 0) s.broken = true;
    }

    private Material soil(Vector c) {
        Material m = game.world.getBlockAt(c.getBlockX(), Plaza.FLOOR_Y - 1, c.getBlockZ()).getType();
        if (!m.isSolid() || m == Material.BARRIER || m == Material.BLACK_CONCRETE) return Material.COARSE_DIRT;
        return m;
    }

    int alive() {
        int n = 0;
        for (Slab s : slabs) if (!s.broken) n++;
        return n;
    }

    /** at 에서 reach 안에 서 있는 길 판(구르기가 부딪힘). 없으면 null. */
    Slab hit(Vector at, double reach) {
        for (Slab s : slabs) {
            if (s.broken || !s.risen) continue;
            if (Boss.horiz(s.mid(), at) <= reach) return s;
        }
        return null;
    }

    /** 구르는 몸 앞(dir 쪽, reach 안)에서 굴림 줄과 옆으로 side 안에 선 길 판. 옆이나 뒤의 판은 막지 않는다. 없으면 null. */
    Slab hitAhead(Vector at, Vector dir, double reach, double side) {
        for (Slab s : slabs) {
            if (s.broken || !s.risen) continue;
            Vector m = s.mid();
            double dx = m.getX() - at.getX(), dz = m.getZ() - at.getZ();
            double ahead = dx * dir.getX() + dz * dir.getZ(), lat = Math.abs(-dx * dir.getZ() + dz * dir.getX());
            if (ahead > 0 && ahead <= reach && lat <= side) return s;
        }
        return null;
    }

    /** from 에서 dir 쪽 len 블록 줄과 옆으로 side 안에 서 있는 길 판이 없나(시험 자리 잡기). */
    boolean clearLine(Vector from, Vector dir, double len, double side) {
        for (Slab s : slabs) {
            if (s.broken) continue;
            Vector m = s.mid();
            double dx = m.getX() - from.getX(), dz = m.getZ() - from.getZ();
            double along = dx * dir.getX() + dz * dir.getZ(), lat = Math.abs(-dx * dir.getZ() + dz * dir.getX());
            if (along > -3 && along < len + 3 && lat <= side) return false;
        }
        return true;
    }

    /** 길 판이 부서진다(4틱에 걸쳐 내려앉음). */
    void shatter(Slab s) {
        if (s.broken) return;
        s.broken = true;
        slabsBroken++;
        for (int g : s.groups) boss.mend(g, 4);
        boss.dust(s.mid(), 2.5, 4);
        boss.sfx("sfx.wipe", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.6f, 96);
    }

    /** 단계가 바뀔 때: 발밑에서 금이 사방으로 달리고 사람들 쪽에 길 판 둘이 더 솟는다. */
    void phaseBreak(Vector at, Vector toCrowd) {
        Vector c = at.clone().setY(Plaza.FLOOR_Y);
        for (int i = 0; i < 5; i++) boss.crack(c, c.clone().add(turned(toCrowd, 36 + i * 72).multiply(13)), 2);
        Vector perp = new Vector(-toCrowd.getZ(), 0, toCrowd.getX());
        for (int s = -1; s <= 1; s += 2) raise(c.clone().add(toCrowd.clone().multiply(10)).add(perp.clone().multiply(s * 5.5)), toCrowd);
    }

    // ------------------------------------------------------------------ 고랑, 진창

    /** 구른 자리가 그을린 고랑으로 남는다(셋까지, 넘으면 가장 오래된 것을 메움). */
    void furrow(Vector from, Vector to, double width) {
        Terrain t = boss.land();
        if (t == null || Boss.horiz(from, to) < 2) return;
        while (furrows.size() >= FURROW_MAX) t.restore(furrows.poll().group);
        int g = boss.crack(from, to, width);
        if (g > 0) furrows.add(new Zone(g, from.clone(), to.clone(), width / 2 + 0.5));
    }

    /** 무더기가 지나간 자리가 진창이 된다(다섯까지, 넘으면 가장 오래된 것을 되돌림: 같은 틱이라 걷기 격자는 한 번만 다시 읽힘). */
    void mud(Vector c, double r) {
        Terrain t = boss.land();
        if (t == null) return;
        while (muds.size() >= MUD_MAX) t.restore(muds.poll().group);
        int g = 0, x0 = (int) Math.floor(c.getX() - r), x1 = (int) Math.floor(c.getX() + r), z0 = (int) Math.floor(c.getZ() - r),
            z1 = (int) Math.floor(c.getZ() + r);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double d = Math.hypot(x + 0.5 - c.getX(), z + 0.5 - c.getZ());
                if (d > r) continue;
                int h = (x * 73856093) ^ (z * 19349663);
                h = (h ^ (h >>> 13)) * 1274126177;
                double u = ((h >>> 8) & 0xFFFF) / 65536.0;
                if (u > 1 - 0.7 * (d / r) * (d / r)) continue;
                if (t.height(x + 0.5, z + 0.5) > 0) continue;
                int got = t.set(x, Plaza.FLOOR_Y - 1 - t.depth(x + 0.5, z + 0.5), z, MUDS[(h >>> 4) & 3], g);
                if (g == 0) g = got;
            }
        }
        if (g > 0) muds.add(new Zone(g, c.clone(), null, r));
    }

    /** 그 사람이 진창 위인가. */
    boolean inMud(Player p) {
        double x = p.getLocation().getX(), z = p.getLocation().getZ();
        for (Zone m : muds) if (m.has(x, z)) return true;
        return false;
    }

    // ------------------------------------------------------------------ 틱

    void tick(long clock) {
        long now = game.now();
        for (Slab s : slabs) if (!s.risen && !s.broken && now >= s.riseAt) build(s);
        if (craterAt >= 0 && now >= craterAt) {
            craterAt = -1;
            boss.mend(boss.pit(craterC, 6, 2, 12), 40, 400);
        }
        // 고랑 위의 땅 게이브는 빨라진다(빠르기 덤은 다음 틱까지 남으니 2틱에 한 번)
        if (!furrows.isEmpty() && (clock & 1) == 0) {
            for (Gabe g : game.mobs.all()) {
                if (g.dead || g.flying || g == boss || g.sp.is(Role.BOSS)) continue;
                for (Zone f : furrows) {
                    if (!f.has(g.pos.getX(), g.pos.getZ())) continue;
                    g.speedBuff = Math.max(g.speedBuff, B1.Tune.FURROW_BUFF);
                    break;
                }
            }
        }
        // 진창 위의 사람은 느려진다
        if (!muds.isEmpty() && clock % 5 == 0) {
            for (Player p : game.mobs.standing()) {
                if (p.getLocation().getY() > Plaza.FLOOR_Y + 1.2 || !inMud(p)) continue;
                game.players.slow(p, B1.Tune.MUD_SLOW, 12, "b1_mud");
            }
        }
    }

    String status() {
        return "slabs=" + alive() + "/" + slabs.size() + " broke=" + slabsBroken + " furrows=" + furrows.size() + " muds=" + muds.size();
    }
}
