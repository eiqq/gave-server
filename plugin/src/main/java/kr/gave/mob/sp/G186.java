package kr.gave.mob.sp;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G186 양탄자 게이브(4막 떼, 방해). 가로세로 3 블록의 납작한 얼굴 양탄자가 여러 장 땅을 덮으며 밀려와(감독이 낸 하나가 첫 틱에 일곱에서 열하나를 더
 * 데려옴, 0.17블록/틱) 사람들 발밑의 3 블록 칸에 깔리고, 빈 칸을 찾아 서로 이어 붙는다(종 공용 칸 표). 사람이 옮겨 가면 따라가 다시 깔린다.
 * 깔린 자리는 땅이 안 보인다: 바닥 높이 0.06 에 그려(그리기 높이 약속 DRAWORDER, API 16.7) 0.02 의 바닥 예고(그림자, 금, 예고 원)와 0.04 의 쓰러짐
 * 고리를 가린다.
 * <ul>
 * <li>위에 선 사람의 발목을 물어 0.5초마다 3 피해와 25% 느려짐. 네 장 넘게(셋 넘게) 이어진 자리에서는 5. 넘어진(묶인) 사람은 두 배.</li>
 * <li>조명탄 빛 안에는 깔리지 않는다(그 칸은 고르지 않고, 깔린 칸에 빛이 들면 떠남). 불에 맞은 장은 2초 동안 타며 이어진 이웃 장으로 번진다.</li>
 * <li>깔리기 직전 귀퉁이 못 넷이 땅을 긁는 불티 줄, 깔린 얼굴들은 실눈이다가 물 때 눈을 뜸.</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g186.py): 모델 gave:gabe/g186(shrink 0.5) 상태 "". 소리 murmur(20), bite(16), scrape(16). 글귀 cause_g186.
 */
public final class G186 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.17, HOVER = 0.06, CELL = 3, HALF = 1.5, BITE = 3, BITE_BIG = 5, SLOW = 0.25, BURN = 6;
    static final int FOLLOW_MIN = 7, FOLLOW_MAX = 11, BITE_EVERY = 10, SLOW_T = 12, OPEN_T = 10, IDLE_MOVE = 40, BURN_T = 40, BURN_EVERY = 10,
        BIG_CHAIN = 4, MURMUR_EVERY = 60;

    /** 칸 표: 칸 열쇠 → 그 칸을 맡은 장(판 하나). */
    static final Map<Long, G186> CELLS = new HashMap<>();

    private boolean follower, init, settled;
    private long cell = Long.MIN_VALUE;
    private final Vector spot = new Vector();
    private int idle, openUntil, burnUntil = -1, nextBurn, nextMurmur, nextSpark;

    public G186(Game game, Species sp) {
        super(game, sp);
        radius = 0.7;
        hover = HOVER;
        baseSpeed = SPEED;
        noPush = true;
        noBite = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g186", 3.2f, 0.4f);
    }

    /** 감독이 낸 하나(첫 틱): 일곱에서 열하나를 더 데려옴. */
    private void formRug() {
        init = true;
        nextMurmur = age + game.random.nextInt(MURMUR_EVERY);
        if (follower) return;
        // 판이 바뀌어 남은 칸 표(죽거나 치워진 장)를 거름
        CELLS.values().removeIf(g -> g.dead || g.removed);
        int n = FOLLOW_MIN + game.random.nextInt(FOLLOW_MAX - FOLLOW_MIN + 1);
        for (int i = 0; i < n; i++) {
            if (!game.mobs.budgetFor(1)) break;
            G186 f = new G186(game, sp);
            f.follower = true;
            f.reward = reward;
            f.noReward = noReward;
            double a = game.random.nextDouble() * Math.PI * 2, r = 2 + game.random.nextDouble() * 5;
            Vector at = pos.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r));
            if (game.mobs.place(f, at) == null) continue;
            f.maxHp = f.hp = maxHp;
            f.yaw = 0;
        }
    }

    // ------------------------------------------------------------------ 칸

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    private static int ci(double v) {
        return (int) Math.floor(v / CELL);
    }

    private static double cc(int i) {
        return i * CELL + HALF;
    }

    private boolean free(long k) {
        G186 g = CELLS.get(k);
        return g == null || g == this || g.dead || g.removed;
    }

    private void claim(long k, int cx, int cz) {
        if (cell != Long.MIN_VALUE && CELLS.get(cell) == this) CELLS.remove(cell);
        cell = k;
        CELLS.put(k, this);
        spot.setX(cc(cx)).setZ(cc(cz));
        settled = false;
    }

    private void release() {
        if (cell != Long.MIN_VALUE && CELLS.get(cell) == this) CELLS.remove(cell);
        cell = Long.MIN_VALUE;
        settled = false;
    }

    /** 발밑 칸 고르기: 사람 발밑 칸 가운데 빈 곳(가까운 것), 없으면 그 둘레 여덟 칸, 그것도 없으면 가장 가까운 사람 발밑(겹쳐 깔림). 빛 안은 뺌. */
    private boolean pickCell() {
        List<Player> st = game.mobs.standing();
        if (st.isEmpty()) return false;
        long best = Long.MIN_VALUE;
        int bx = 0, bz = 0;
        double bd = Double.MAX_VALUE;
        for (int ring = 0; ring <= 1 && best == Long.MIN_VALUE; ring++) {
            for (Player p : st) {
                Location l = p.getLocation();
                int px = ci(l.getX()), pz = ci(l.getZ());
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (ring == 1 && dx == 0 && dz == 0) continue;
                        int cx = px + dx, cz = pz + dz;
                        long k = key(cx, cz);
                        if (!free(k)) continue;
                        Vector c = new Vector(cc(cx), l.getY(), cc(cz));
                        if (game.shop.inFlare(c, 1.5) || !game.mobs.flow.walkable(c.getX(), c.getZ())) continue;
                        double d = Math.hypot(c.getX() - pos.getX(), c.getZ() - pos.getZ());
                        if (d < bd) {
                            bd = d;
                            best = k;
                            bx = cx;
                            bz = cz;
                        }
                    }
                }
            }
        }
        if (best == Long.MIN_VALUE) {
            Player p = nearest(400);
            if (p == null) return false;
            Location l = p.getLocation();
            bx = ci(l.getX());
            bz = ci(l.getZ());
            best = key(bx, bz);
            if (!free(best)) {
                // 겹쳐 깔림(칸 표에는 남이 그대로)
                if (cell != Long.MIN_VALUE && CELLS.get(cell) == this) CELLS.remove(cell);
                cell = Long.MIN_VALUE;
                spot.setX(cc(bx)).setZ(cc(bz));
                settled = false;
                return true;
            }
        }
        claim(best, bx, bz);
        return true;
    }

    /** 이어진 장 수(칸 표에서 맞닿은 장을 따라, 다섯까지만 셈). */
    private int chain() {
        if (cell == Long.MIN_VALUE) return 1;
        int cx = (int) (cell >> 32), cz = (int) cell;
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[] {cx, cz});
        seen.add(cell);
        while (!q.isEmpty() && seen.size() <= BIG_CHAIN) {
            int[] c = q.poll();
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long k = key(c[0] + d[0], c[1] + d[1]);
                if (seen.contains(k)) continue;
                G186 g = CELLS.get(k);
                if (g == null || g.dead || g.removed || !g.settled) continue;
                seen.add(k);
                q.add(new int[] {c[0] + d[0], c[1] + d[1]});
            }
        }
        return seen.size();
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) formRug();
        burnTick();
        boolean lit = game.shop.inFlare(pos, 0.5);
        if (spot.lengthSquared() == 0 || lit || !settled && cell != Long.MIN_VALUE && !free(cell)) {
            if (lit) release();
            if (!pickCell()) {
                follow(speed());
                return;
            }
        }
        double dx = spot.getX() - pos.getX(), dz = spot.getZ() - pos.getZ();
        double d = Math.hypot(dx, dz);
        if (d > 0.25) {
            settled = false;
            seek(new Vector(spot.getX(), pos.getY(), spot.getZ()), Math.min(speed(), d * 0.5 + 0.02));
            yaw = 0;
            sparks(d);
            if (every(30)) sound("scrape", Sound.BLOCK_GRAVEL_STEP, 0.7f, 16);
            return;
        }
        hold();
        yaw = 0;
        settled = true;
        bite();
        // 위에도 곁에도 사람이 없으면 따라가 다시 깔림
        if (!anyoneNear(CELL * 1.6)) {
            if (++idle >= IDLE_MOVE) {
                idle = 0;
                release();
                pickCell();
            }
        } else idle = 0;
        if (age >= nextMurmur) {
            nextMurmur = age + MURMUR_EVERY + game.random.nextInt(30);
            if (anyoneNear(8)) sound("murmur", Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 20);
        }
        body.look.open = age < openUntil;
    }

    private boolean anyoneNear(double r) {
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.abs(l.getX() - pos.getX()) <= r && Math.abs(l.getZ() - pos.getZ()) <= r) return true;
        }
        return false;
    }

    /** 깔리기 직전(4 블록 안) 귀퉁이 못이 땅을 긁는 불티. */
    private void sparks(double d) {
        if (d > 4 || age < nextSpark) return;
        nextSpark = age + 4;
        for (int sx : new int[] {-1, 1}) {
            Location l = new Location(game.world, pos.getX() + sx * 1.4, pos.getY() + 0.1, pos.getZ() - 1.4);
            game.shotFx.spawn(l, "fx_quad", ShotFx.SPARK, 0, ShotFx.scale(0.5f), Display.Billboard.CENTER, true, 4, ShotFx.EXTRA);
        }
    }

    /** 위에 선 사람의 발목을 묾(0.5초마다). */
    private void bite() {
        if (!every(BITE_EVERY)) return;
        int n = -1;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.abs(l.getX() - pos.getX()) > HALF + 0.2 || Math.abs(l.getZ() - pos.getZ()) > HALF + 0.2) continue;
            if (Math.abs(l.getY() - pos.getY()) > 0.6) continue;
            if (n < 0) n = chain();
            PState s = game.players.get(p);
            double dmg = (n >= BIG_CHAIN ? BITE_BIG : BITE) * (s.rooted(game.now()) ? 2 : 1);
            hit(p, dmg, "cause_g186");
            game.players.slow(p, SLOW, SLOW_T, "g186_rug");
            openUntil = age + OPEN_T;
            if (every(BITE_EVERY * 3)) sound("bite", Sound.ENTITY_FOX_BITE, 1.3f, 16);
        }
    }

    /** 타는 중: 0.5초마다 불 피해, 맞닿은 장으로 번짐. */
    private void burnTick() {
        if (burnUntil < 0) return;
        if (age >= burnUntil) {
            burnUntil = -1;
            return;
        }
        if (age < nextBurn) return;
        nextBurn = age + BURN_EVERY;
        game.shotFx.flame(loc().add(0, 0.3, 0), 1.6f, 12, ShotFx.EXTRA);
        for (Gabe g : game.mobs.near(pos, CELL * 1.2, g -> g instanceof G186 && g != this)) {
            G186 r = (G186) g;
            if (r.burnUntil < 0) r.ignite();
        }
        game.mobs.damage(this, new Damage(BURN, DamageType.OTHER, null, pos.clone(), new Vector(0, 1, 0), "g186_burn", 0));
    }

    private void ignite() {
        burnUntil = age + BURN_T;
        nextBurn = age + 6;
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        double y = pos.getY() + 0.15;
        return List.of(new double[] {pos.getX() + 0.75, y, pos.getZ() + 0.75, 0.68}, new double[] {pos.getX() - 0.75, y, pos.getZ() + 0.75, 0.68},
            new double[] {pos.getX() + 0.75, y, pos.getZ() - 0.75, 0.68}, new double[] {pos.getX() - 0.75, y, pos.getZ() - 0.75, 0.68});
    }

    @Override
    public boolean damage(Damage d) {
        if (d.type() == DamageType.FIRE && burnUntil < 0 && !dead) ignite();
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        release();
        super.die(d);
    }

    @Override
    public void remove() {
        release();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + (settled ? " settled chain=" + chain() : " moving") + " cells=" + CELLS.size() + (burnUntil >= 0 ? " burning" : "");
    }
}
