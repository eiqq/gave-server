package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G165 무른 땅 게이브(함정, 떼, 미리 깔림). 밟으면 발이 빠지고 둘레 진흙에서 등딱지 얼굴 다섯이 솟는 무른 땅. 누가 그 위에 서 있는 동안 끝없이 솟는다.
 *
 * <p>무른 땅(감독이 placeFor 자리에 놓는 개체): 지름 7블록 진흙 원판(ghost, noPush), 맞는 구는 가운데 잠긴 얼굴 하나(체력 120). 놓이자마자 가장 가까운
 * 사람을 mark 해 target 을 세운다(웨이브 끝 규칙: 잡아야 웨이브가 끝남. 실눈은 다시 감음: 눈은 밟힐 때만 뜸). 사람이 자주 지나는 쪽(4블록 칸 발자국 누적,
 * 이 종 static)으로 아주 천천히 기어 옮긴다. 밟히면(서 있는 사람이 반경 3.5 안) 푹 소리, 눈 뜸(그동안 피해 x2), 0.5초 전 진흙이 볼록(상태 bulge, 그 사람
 * 둘레 다섯 군데 진흙 튐) → 등딱지 다섯이 솟으며 발목 물기(6 피해, 0.5초 묶임) → 누가 서 있는 동안 3초마다 또 다섯. 위에 선 사람은 40% 느림.
 * 화염(화염병)에 맞으면 땅속 것들이 한꺼번에 튀어나온다(1.5초에 한 번). 죽으면 상태 dry(굳은 땅)로 납작 사체.
 *
 * <p>등딱지(같은 종의 child, 보상 0): 진흙에서 솟아 가장 가까운 사람에게 게걸음으로 빠르게(0.26) 쫓아 온다(무른 땅 밖까지). 집게 물기 5, 한 사람에게
 * 셋 넘게 붙으면 넘어뜨림(0.6초 묶임). 무른 땅 하나에 살아 있는 등딱지는 MAX_CRABS 까지.
 *
 * <p>팩 계약(tools/gabe/sp/g165.py): 무른 땅 gabe/g165 상태 "", "bulge", "dry". 등딱지 gabe/g165_crab 상태 "". 소리 sink(20), rise(20), pinch(12),
 * 글귀 cause_g165.
 */
public final class G165 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double MUD_R = 3.5, FACE_R = 0.75, FACE_UP = 0.15, MUD_HP = 120, CRAWL = 0.03;
    /** 솟기: 볼록 예고 틱, 다시 솟는 틱(박자 배율 곱함), 한 번에 솟는 수, 무른 땅 하나의 등딱지 상한. */
    static final int BULGE = 10, RISE_EVERY = 60, RISE_N = 5, MAX_CRABS = 30, FIRE_CD = 30;
    /** 솟으며 발목 물기(피해, 묶임 틱), 위에 선 사람 느려짐(%). */
    static final double RISE_BITE = 6, SLOW = 40;
    static final int RISE_ROOT = 10;
    /** 등딱지: 높이, 반지름, 빠르기, 집게 물기 피해와 쿨다운, 넘어뜨림(붙은 수, 틱). */
    static final double CRAB_HOVER = 0.22, CRAB_R = 0.34, CRAB_SPEED = 0.26, PINCH = 5;
    static final int PINCH_CD = 30, GANG = 3, GANG_ROOT = 12, RISE_T = 6;
    /** 발자국 칸(블록), 칸 수, 옮겨 갈 곳을 찾는 반경(칸), 중앙 둘레 금지 반경. */
    static final int CELL = 4, GRID = 52, LOOK = 3, GOAL_EVERY = 100;
    static final double KEEP_OUT = 9;

    // ------------------------------------------------------------------ 발자국(같은 종 static)

    private static final float[] PRINTS = new float[GRID * GRID];
    private static long printTick = Long.MIN_VALUE;
    private static Game printGame;
    private static int printRound = -1;
    /** child 로 등딱지를 만들 때만 true(생성자가 읽음). */
    private static boolean spawnCrab;

    final boolean crab;
    /** 무른 땅: 살아 있는 등딱지, 밟은 사람, 다음 솟기, 볼록 자리. */
    private final List<G165> crabs = new ArrayList<>();
    private final Set<UUID> on = new HashSet<>();
    private final List<Vector> bumps = new ArrayList<>();
    private long riseAt = Long.MAX_VALUE, fireAt = Long.MIN_VALUE / 2;
    private Player bulgeFor;
    private final Vector goal = new Vector();
    private boolean hasGoal;
    private int nextGoal;
    /** 등딱지: 나온 무른 땅. */
    private G165 mud;
    private int readyAt;

    public G165(Game game, Species sp) {
        super(game, sp);
        crab = spawnCrab;
        if (crab) {
            radius = CRAB_R;
            hover = CRAB_HOVER;
            baseSpeed = CRAB_SPEED;
            noMutate = true;
        } else {
            radius = FACE_R;
            hover = 0.02;
            baseSpeed = CRAWL;
            ghost = true;
            noPush = true;
            noBite = true;
            ragdoll = false;
            maxHp = hp = MUD_HP;
        }
    }

    @Override
    protected void build() {
        if (crab) body = Body.of(this, "gabe/g165_crab", 1.1f, 0.5f);
        else body = Body.of(this, "gabe/g165", 7.2f, 1.2f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        if (crab) {
            hover = -0.35;
            Player p = nearest(400);
            if (p != null) mark(p);
            return;
        }
        // 웨이브 끝 규칙: 잡아야 웨이브가 끝나게 target 을 세움(눈은 밟힐 때만 뜸)
        Player p = nearest(400);
        if (p != null) mark(p);
        body.look.open = false;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (crab) crabTick();
        else mudTick();
    }

    private void mudTick() {
        long now = now();
        prints(now);
        // 밟은 사람
        on.clear();
        Player first = null;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz > MUD_R * MUD_R || Math.abs(l.getY() - floor()) > 1.5) continue;
            on.add(p.getUniqueId());
            if (first == null) first = p;
            if (every(5)) game.players.slow(p, SLOW, 10, "mud");
        }
        crabs.removeIf(c -> c.dead);
        boolean stepped = first != null;
        if (stepped && !body.look.open) {
            // 막 밟힘: 푹, 눈 뜸, 곧 볼록
            body.look.open = true;
            sound("sink", Sound.BLOCK_MUD_STEP, 0.6f, 20);
            riseAt = now + BULGE;
            bulge(first);
        } else if (!stepped && body.look.open) {
            body.look.open = false;
            state("");
            riseAt = Long.MAX_VALUE;
        }
        if (stepped) {
            hold();
            if (now == riseAt - BULGE && bulgeFor == null) bulge(first);
            if (now >= riseAt) {
                rise(bulgeFor != null && on.contains(bulgeFor.getUniqueId()) ? bulgeFor : first, true);
                riseAt = now + Math.max(BULGE + 1, cd(RISE_EVERY));
            }
        } else crawl(now);
        // 표적(웨이브 끝 규칙)이 쓰러지거나 나가면 다시 세움
        if (every(40) && !standing(target)) {
            Player p = nearest(400);
            mark(p);
            body.look.open = stepped;
        }
    }

    /** 솟기 0.5초 전: 진흙이 볼록(상태 bulge) + 그 사람 둘레 다섯 군데에서 진흙이 튐. */
    private void bulge(Player p) {
        bulgeFor = p;
        state("bulge");
        bumps.clear();
        Vector c = p.getLocation().toVector();
        double a0 = game.random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < RISE_N; i++) {
            double a = a0 + i * Math.PI * 2 / RISE_N;
            Vector b = new Vector(c.getX() + Math.cos(a) * 1.3, 0, c.getZ() + Math.sin(a) * 1.3);
            // 무른 땅 안으로 당김
            Vector off = b.clone().subtract(pos).setY(0);
            if (off.length() > MUD_R - 0.4) off.normalize().multiply(MUD_R - 0.4);
            b = pos.clone().add(off);
            b.setY(game.mobs.flow.floor(b.getX(), b.getZ()));
            bumps.add(b);
            game.world.spawnParticle(Particle.BLOCK, b.getX(), b.getY() + 0.15, b.getZ(), 8, 0.2, 0.05, 0.2, 0.0,
                    Material.MUD.createBlockData());
        }
    }

    /** 등딱지 다섯이 솟음(bite 면 곁의 사람 발목을 물고 묶음). */
    private void rise(Player p, boolean bite) {
        state("");
        if (bumps.isEmpty() && p != null) bulge(p);
        bulgeFor = null;
        sound("rise", Sound.BLOCK_MUD_BREAK, 0.7f, 20);
        boolean bit = false;
        for (Vector b : bumps) {
            if (crabs.size() >= MAX_CRABS) break;
            Vector at = b.clone().setY(b.getY() + CRAB_HOVER);
            spawnCrab = true;
            Gabe g;
            try {
                g = child(sp, at);
            } finally {
                spawnCrab = false;
            }
            if (!(g instanceof G165 c)) break;
            c.mud = this;
            c.maxHp = c.hp = sp.hp * game.director.hpMul(sp);
            c.readyAt = c.age + 8;
            crabs.add(c);
            game.world.spawnParticle(Particle.BLOCK, b.getX(), b.getY() + 0.2, b.getZ(), 10, 0.2, 0.1, 0.2, 0.0,
                    Material.MUD.createBlockData());
        }
        bumps.clear();
        if (bite && p != null && standing(p)) {
            hit(p, RISE_BITE, "cause_g165");
            game.players.root(p, RISE_ROOT, "mudbite");
            bit = true;
        }
        if (bit) sound("pinch", Sound.ENTITY_PLAYER_HURT, 1.2f, 12);
    }

    /** 사람이 자주 지나는 쪽으로 아주 천천히 기어 옮김. */
    private void crawl(long now) {
        if (age >= nextGoal) {
            pickGoal();
            nextGoal = age + GOAL_EVERY;
        }
        Vector to = goal.clone().subtract(pos).setY(0);
        double d = to.length();
        if (!hasGoal || d < 0.5) {
            hold();
            return;
        }
        vel.copy(to.multiply(Math.min(d, CRAWL * game.director.speedMul()) / d));
    }

    private void pickGoal() {
        int cx = cell(pos.getX()), cz = cell(pos.getZ());
        float best = -1;
        int bx = cx, bz = cz;
        for (int dz = -LOOK; dz <= LOOK; dz++) {
            for (int dx = -LOOK; dx <= LOOK; dx++) {
                int x = cx + dx, z = cz + dz;
                if (x < 0 || z < 0 || x >= GRID || z >= GRID) continue;
                double wx = center(x), wz = center(z);
                if (Plaza.radius(wx, wz) < KEEP_OUT + MUD_R) continue;
                if (!game.mobs.flow.walkable(wx, wz) || crowded(wx, wz)) continue;
                float v = PRINTS[z * GRID + x];
                if (v > best) {
                    best = v;
                    bx = x;
                    bz = z;
                }
            }
        }
        hasGoal = best > 0.5f;
        goal.setX(center(bx)).setY(pos.getY()).setZ(center(bz));
    }

    /** 다른 무른 땅과 겹칠 자리면 true. */
    private boolean crowded(double x, double z) {
        for (Gabe g : game.mobs.all()) {
            if (g == this || g.dead || !(g instanceof G165 o) || o.crab) continue;
            double dx = g.pos.getX() - x, dz = g.pos.getZ() - z;
            if (dx * dx + dz * dz < (MUD_R * 2) * (MUD_R * 2)) return true;
        }
        return false;
    }

    private static int cell(double v) {
        return (int) Math.floor((v + GRID * CELL / 2.0) / CELL);
    }

    private static double center(int c) {
        return c * CELL - GRID * CELL / 2.0 + CELL / 2.0;
    }

    /** 발자국 누적(판마다 지움, 틱마다 한 번만, 10틱 간격). */
    private void prints(long now) {
        if (printGame != game || printRound != game.round) {
            java.util.Arrays.fill(PRINTS, 0);
            printGame = game;
            printRound = game.round;
        }
        if (now == printTick || now % 10 != 0) return;
        printTick = now;
        for (int i = 0; i < PRINTS.length; i++) PRINTS[i] *= 0.995f;
        for (Player p : game.mobs.standing()) {
            int x = cell(p.getLocation().getX()), z = cell(p.getLocation().getZ());
            if (x >= 0 && z >= 0 && x < GRID && z < GRID) PRINTS[z * GRID + x] += 1;
        }
    }

    // ------------------------------------------------------------------ 등딱지

    private void crabTick() {
        if (age <= RISE_T) {
            // 진흙에서 솟아오름
            hover = -0.35 + (CRAB_HOVER + 0.35) * age / RISE_T;
            hold();
            return;
        }
        hover = CRAB_HOVER;
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        if (p != target) mark(p);
        follow(speed());
        // 게걸음: 옆구리가 가는 쪽을 봄
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-3) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ())) + 90f;
        if (age >= readyAt && touches(p, 0.15)) {
            hit(p, PINCH, "cause_g165");
            readyAt = age + cd(PINCH_CD);
            if (every(2)) sound("pinch", Sound.ENTITY_PLAYER_HURT, 1.2f, 12);
            // 셋 넘게 붙으면 넘어뜨림
            int n = 0;
            Vector c = chest(p);
            for (Gabe g : game.mobs.near(c, 1.5, g -> g instanceof G165 o && o.crab)) n++;
            if (n >= GANG) game.players.root(p, GANG_ROOT, "crab");
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        if (crab) return super.spheres();
        return List.of(new double[] {pos.getX(), pos.getY() + FACE_UP, pos.getZ(), FACE_R});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (crab) return super.damage(d);
        // 화염: 땅속 것들이 한꺼번에 튀어나옴
        if (d.type() == DamageType.FIRE && now() - fireAt >= FIRE_CD) {
            fireAt = now();
            Player p = nearest(400);
            if (p != null) {
                bumps.clear();
                for (int i = 0; i < RISE_N; i++) {
                    double a = game.random.nextDouble() * Math.PI * 2, r = 0.8 + game.random.nextDouble() * (MUD_R - 1.2);
                    Vector b = pos.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r));
                    b.setY(game.mobs.flow.floor(b.getX(), b.getZ()));
                    bumps.add(b);
                }
                rise(p, false);
            }
        }
        // 누가 밟아 눈을 뜬 동안 피해 두 배
        return super.damage(body.look.open ? d.times(2) : d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (!crab) {
            body.look.open = false;
            state("dry");
        }
        super.die(d);
    }

    /** 시험용. */
    public String info() {
        if (crab) return "crab";
        return "mud on=" + on.size() + " crabs=" + crabs.size() + " open=" + body.look.open + (hasGoal ? " goal" : "");
    }

    @Override
    public String toString() {
        return super.toString() + " " + info();
    }
}
