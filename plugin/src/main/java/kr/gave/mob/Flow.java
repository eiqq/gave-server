package kr.gave.mob;

import java.util.Arrays;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.world.Plaza;

/**
 * 흐름장: 경기장 2D 격자(Plaza.MIN..MAX, 칸 = 블록)에 '가장 가까운 서 있는 사람까지 걸음 비용'을 {@link #PERIOD} 틱마다 다중 출발
 * 최단 거리(곧은 칸 2, 대각 3 의 Dial 버킷 탐색, 배열 재사용, 할당 없음)로 계산한다. 막힘과 바닥 높이는 Plaza.walkable, Plaza.floor 를
 * 200틱마다 다시 읽어 둔다.
 *
 * <p>규칙: 8방향, 대각은 두 옆칸이 모두 열려야 지나감, 오르는 턱은 {@link #STEP} 까지(내려가는 것은 얼마든). dir() 은 지금 칸에서 비용이
 * 내려가는 이웃 사슬을 몇 칸 앞까지 따라가 곧게 보이는 가장 먼 칸을 겨눈다(지그재그 없이 모서리를 돌아감). 마지막 {@link #NEAR_BLOCKS}
 * 블록은 그 칸을 부른 사람에게 곧장. 사람이 없으면 가운데(상점) 쪽.
 */
public final class Flow {

    public final Game game;

    /** 다시 계산하는 간격(틱). */
    public static final int PERIOD = 10;
    /** 땅 몹이 오를 수 있는 턱. */
    public static final double STEP = 0.6;
    /** 이 블록 안이면 사람에게 곧장. */
    public static final double NEAR_BLOCKS = 3;
    /** 비용 단위: 곧은 칸 2, 대각 3(블록 = 비용 / 2). */
    public static final int ORTH = 2, DIAG = 3;

    static final int W = Plaza.MAX - Plaza.MIN + 1, N = W * W;
    static final int INF = Integer.MAX_VALUE;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1}, DZ = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final int LOOK = 6, MAX_SEEDS = 64;

    private final int[] cost = new int[N];
    private final byte[] src = new byte[N];
    private final int[] next = new int[N];
    private final int[] nextStamp = new int[N];
    private final boolean[] open = new boolean[N];
    /** 시험용 벽(흐름벽 훅): 맵 칸 위에 덧씌워 막음. */
    private final boolean[] testWall = new boolean[N];
    private final float[] floorY = new float[N];
    private final int[][] bucket = new int[4][N];
    private final int[] bsize = new int[4];

    private final double[] seedX = new double[MAX_SEEDS], seedZ = new double[MAX_SEEDS];
    /** 씨앗 사람의 이번 틱 자리(계산 사이 9틱 동안 달린 만큼). */
    private final double[] liveX = new double[MAX_SEEDS], liveZ = new double[MAX_SEEDS];
    /** 계산 때 자리에서 이만큼 넘게 가면(순간이동, 부활) 다음 계산까지 계산 때 자리를 씀. */
    private static final double LIVE_JUMP = 4;
    private final Player[] seedP = new Player[MAX_SEEDS];
    private int seeds;
    private boolean toPlayers;
    private int stamp = 1, runs;
    private long lastRun = Long.MIN_VALUE / 2;
    private boolean scanned;
    /** 마지막 계산에 걸린 시간(ms), 닿은 칸 수. */
    public double lastMs;
    public int reached;

    public Flow(Game game) {
        this.game = game;
    }

    // ------------------------------------------------------------------ 계산

    /** 매 틱 엔진이 부름(스스로 PERIOD 틱마다 다시 계산, 사람 자리는 매 틱 새로 읽음). */
    public void tick() {
        long now = game.now();
        if (now - lastRun >= PERIOD) {
            lastRun = now;
            compute(game.mobs.standing());
        } else {
            refreshLive();
        }
    }

    /** 씨앗 사람의 지금 자리(마지막 3블록 곧장 가기와 길 없을 때 대체 경로가 씀). 계산 때 자리에서 LIVE_JUMP 넘게 갔으면 계산 때 자리 그대로. */
    private void refreshLive() {
        for (int i = 0; i < seeds; i++) {
            Player p = seedP[i];
            if (p == null || !p.isOnline()) {
                liveX[i] = seedX[i];
                liveZ[i] = seedZ[i];
                continue;
            }
            Location l = p.getLocation();
            double dx = l.getX() - seedX[i], dz = l.getZ() - seedZ[i];
            if (dx * dx + dz * dz > LIVE_JUMP * LIVE_JUMP) {
                liveX[i] = seedX[i];
                liveZ[i] = seedZ[i];
            } else {
                liveX[i] = l.getX();
                liveZ[i] = l.getZ();
            }
        }
    }

    /** 다음 tick() 에 바로 다시 계산(사람이 순간이동했을 때 등). */
    public void invalidate() {
        lastRun = Long.MIN_VALUE / 2;
    }

    /** 걷기 격자를 Plaza 에서 다시 읽음(맵이 바뀌었을 때). */
    public void rescan() {
        for (int z = 0; z < W; z++) {
            for (int x = 0; x < W; x++) {
                int c = z * W + x;
                int wx = x + Plaza.MIN, wz = z + Plaza.MIN;
                open[c] = Plaza.walkable(wx, wz) && !testWall[c];
                floorY[c] = (float) Plaza.floor(wx + 0.5, wz + 0.5);
            }
        }
        scanned = true;
    }

    private void compute(List<Player> standing) {
        long t0 = System.nanoTime();
        if (!scanned || runs % 20 == 0) rescan();
        runs++;
        stamp++;
        Arrays.fill(cost, INF);
        bsize[0] = bsize[1] = bsize[2] = bsize[3] = 0;
        seeds = 0;
        for (Player p : standing) {
            if (seeds >= MAX_SEEDS) break;
            Location l = p.getLocation();
            int c = cell(l.getX(), l.getZ());
            if (c < 0) continue;
            seedX[seeds] = l.getX();
            seedZ[seeds] = l.getZ();
            seedP[seeds] = p;
            if (cost[c] != 0) {
                cost[c] = 0;
                src[c] = (byte) seeds;
                push(0, c);
            }
            seeds++;
        }
        toPlayers = seeds > 0;
        if (!toPlayers) {
            int c = cell(Plaza.CX + 0.5, Plaza.CZ + 0.5);
            seedX[0] = Plaza.CX + 0.5;
            seedZ[0] = Plaza.CZ + 0.5;
            seedP[0] = null;
            seeds = 1;
            cost[c] = 0;
            src[c] = 0;
            push(0, c);
        }
        for (int i = 0; i < seeds; i++) {
            liveX[i] = seedX[i];
            liveZ[i] = seedZ[i];
        }
        int pending = 0;
        for (int b = 0; b < 4; b++) pending += bsize[b];
        int n = 0;
        for (int d = 0; pending > 0; d++) {
            int b = d & 3;
            int[] q = bucket[b];
            int size = bsize[b];
            for (int k = 0; k < size; k++) {
                int c = q[k];
                pending--;
                if (cost[c] != d) continue;
                n++;
                int cx = c % W, cz = c / W;
                float fy = floorY[c];
                for (int i = 0; i < 8; i++) {
                    int nx = cx + DX[i], nz = cz + DZ[i];
                    if (nx < 0 || nz < 0 || nx >= W || nz >= W) continue;
                    int nc = nz * W + nx;
                    if (!open[nc]) continue;
                    boolean diag = i >= 4;
                    if (diag && (!open[cz * W + nx] || !open[nz * W + cx])) continue;
                    // 몹은 nc 에서 c 로 걷는다: 오르는 턱 STEP 까지
                    if (fy - floorY[nc] > STEP) continue;
                    int nd = d + (diag ? DIAG : ORTH);
                    if (nd < cost[nc]) {
                        cost[nc] = nd;
                        src[nc] = src[c];
                        push(nd, nc);
                        pending++;
                    }
                }
            }
            bsize[b] = 0;
        }
        reached = n;
        lastMs = (System.nanoTime() - t0) / 1e6;
    }

    private void push(int d, int c) {
        int b = d & 3;
        bucket[b][bsize[b]++] = c;
    }

    // ------------------------------------------------------------------ 읽기

    static int cell(double x, double z) {
        int ix = (int) Math.floor(x) - Plaza.MIN, iz = (int) Math.floor(z) - Plaza.MIN;
        if (ix < 0 || iz < 0 || ix >= W || iz >= W) return -1;
        return iz * W + ix;
    }

    private static double cx(int c) {
        return c % W + Plaza.MIN + 0.5;
    }

    private static double cz(int c) {
        return c / W + Plaza.MIN + 0.5;
    }

    /** c 에서 걸어서 갈 수 있는 이웃 가운데 비용이 가장 작은 칸(동점은 부른 사람 쪽 직선에 가까운 것). 내려갈 데가 없으면 -1. */
    private int nextOf(int c) {
        if (nextStamp[c] == stamp) return next[c];
        nextStamp[c] = stamp;
        int here = cost[c], best = -1, bestCost = here;
        double bestDot = -2;
        int x = c % W, z = c / W;
        int s = src[c] & 0xff;
        double tx = seedX[s] - cx(c), tz = seedZ[s] - cz(c);
        double tl = Math.sqrt(tx * tx + tz * tz);
        if (tl > 1e-6) {
            tx /= tl;
            tz /= tl;
        }
        float fy = floorY[c];
        for (int i = 0; i < 8; i++) {
            int nx = x + DX[i], nz = z + DZ[i];
            if (nx < 0 || nz < 0 || nx >= W || nz >= W) continue;
            int nc = nz * W + nx;
            if (!open[nc] || cost[nc] == INF) continue;
            if (i >= 4 && (!open[z * W + nx] || !open[nz * W + x])) continue;
            if (floorY[nc] - fy > STEP) continue;
            int cn = cost[nc];
            double inv = i >= 4 ? 0.7071 : 1.0;
            double dot = (DX[i] * tx + DZ[i] * tz) * inv;
            if (cn < bestCost || (cn == bestCost && best >= 0 && dot > bestDot)) {
                best = nc;
                bestCost = cn;
                bestDot = dot;
            }
        }
        next[c] = best;
        return best;
    }

    /** at 에서 칸 c 가운데까지 곧게 걸을 수 있나(가운데 선과 양옆 0.3 블록 선, 턱 STEP). */
    private boolean clear(double ax, double az, int c) {
        double bx = cx(c), bz = cz(c);
        double dx = bx - ax, dz = bz - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) return true;
        double px = -dz / len * 0.3, pz = dx / len * 0.3;
        return line(ax, az, bx, bz, len) && line(ax + px, az + pz, bx + px, bz + pz, len) && line(ax - px, az - pz, bx - px, bz - pz, len);
    }

    private boolean line(double ax, double az, double bx, double bz, double len) {
        int steps = (int) Math.ceil(len / 0.35);
        int prev = cell(ax, az);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int c = cell(ax + (bx - ax) * t, az + (bz - az) * t);
            if (c < 0 || !open[c]) return false;
            if (prev >= 0 && c != prev && open[prev] && floorY[c] - floorY[prev] > STEP) return false;
            prev = c;
        }
        return true;
    }

    /** 이 점에서 가장 가까운 사람 쪽(단위, y 0). 길이 없으면 그 사람(또는 경기장 가운데)에게 곧장. */
    public Vector dir(Vector at) {
        double ax = at.getX(), az = at.getZ();
        int c = cell(ax, az);
        if (c >= 0 && cost[c] == INF) c = nearbyReached(c);
        if (c < 0) {
            int s = nearestSeed(ax, az);
            return unit(liveX[s] - ax, liveZ[s] - az);
        }
        int s = src[c] & 0xff;
        // 마지막 몇 블록과 길 없을 때는 그 사람의 지금 자리로(계산 때 자리는 최대 PERIOD-1 틱 전)
        if (cost[c] <= NEAR_BLOCKS * ORTH) return unit(liveX[s] - ax, liveZ[s] - az);
        int t = c, best = -1;
        for (int k = 0; k < LOOK; k++) {
            int n = nextOf(t);
            if (n < 0) break;
            if (k == 0 || clear(ax, az, n)) best = n;
            else break;
            t = n;
        }
        if (best < 0) return unit(liveX[s] - ax, liveZ[s] - az);
        return unit(cx(best) - ax, cz(best) - az);
    }

    /** 닿지 않은 칸(벽 속, 막힌 섬)에 있으면 둘레 2칸 안에서 닿은 칸. 없으면 -1. */
    private int nearbyReached(int c) {
        int x = c % W, z = c / W, best = -1, bc = INF;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int nx = x + dx, nz = z + dz;
                if (nx < 0 || nz < 0 || nx >= W || nz >= W) continue;
                int nc = nz * W + nx;
                if (cost[nc] < bc) {
                    bc = cost[nc];
                    best = nc;
                }
            }
        }
        return best;
    }

    private int nearestSeed(double x, double z) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < seeds; i++) {
            double d = (liveX[i] - x) * (liveX[i] - x) + (liveZ[i] - z) * (liveZ[i] - z);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private static Vector unit(double x, double z) {
        double l = Math.sqrt(x * x + z * z);
        return l < 1e-6 ? new Vector() : new Vector(x / l, 0, z / l);
    }

    /** 이 칸에서 가장 가까운 사람까지 걸음 수(블록, 모르면 Integer.MAX_VALUE). */
    public int dist(double x, double z) {
        int c = cell(x, z);
        if (c < 0 || cost[c] == INF) return Integer.MAX_VALUE;
        return (cost[c] + 1) / 2;
    }

    /** 이 칸의 흐름이 향하는 사람(사람이 없거나 닿지 않은 칸이면 null). */
    public Player target(double x, double z) {
        int c = cell(x, z);
        if (!toPlayers || c < 0 || cost[c] == INF) return null;
        Player p = seedP[src[c] & 0xff];
        return p != null && p.isOnline() ? p : null;
    }

    /** 시험: 이 칸 흐름의 씨앗(계산 때 자리 snap, 이번 틱 자리 live). */
    public String seedInfo(double x, double z) {
        int c = cell(x, z);
        if (c < 0 || cost[c] == INF) return "seed=-";
        int s = src[c] & 0xff;
        return String.format(java.util.Locale.ROOT, "seed=%d snap=%.1f,%.1f live=%.1f,%.1f", s, seedX[s], seedZ[s], liveX[s], liveZ[s]);
    }

    /** 흐름이 사람 쪽인가(false 면 가운데 쪽). */
    public boolean toPlayers() {
        return toPlayers;
    }

    /** 걷는 바닥 높이(블록 윗면 y). 격자 밖은 Plaza.floor. */
    public double floor(double x, double z) {
        int c = cell(x, z);
        if (c < 0 || !scanned) return Plaza.floor(x, z);
        return floorY[c];
    }

    /** 땅 몹이 설 수 있는 칸인가. 격자 밖은 Plaza.walkable. */
    public boolean walkable(double x, double z) {
        int c = cell(x, z);
        if (c < 0 || !scanned) return Plaza.walkable((int) Math.floor(x), (int) Math.floor(z));
        return open[c];
    }

    /** 시험: (x1,z1)-(x2,z2) 칸들을 막거나(on) 풂. 다시 읽고 바로 다시 계산. */
    public void testWall(int x1, int z1, int x2, int z2, boolean on) {
        for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                int c = cell(x + 0.5, z + 0.5);
                if (c >= 0) testWall[c] = on;
            }
        }
        rescan();
        invalidate();
    }

    /** 시험 벽을 모두 풂. */
    public void clearTestWalls() {
        Arrays.fill(testWall, false);
        rescan();
        invalidate();
    }

    /** 격자 안인가. */
    public boolean inGrid(double x, double z) {
        return cell(x, z) >= 0;
    }
}
