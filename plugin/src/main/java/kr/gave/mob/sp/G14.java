package kr.gave.mob.sp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.game.Shop;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Flow;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G14 상점 갉는 게이브(목표 압박). 사람을 무시하고 한가운데 상점(보급 상자)으로 곧장 달려가 얼굴을 박고 갉는다.
 *
 * <p>맵(2026-11-11 황야): 상점은 중앙 원 한가운데 보급 상자 하나다. 구조물 좌표를 쓰지 않고 걷기 격자에서 가운데 둘레의 막힌 칸(상자)을 찾아
 * 거기 맞닿은 걷는 칸을 '붙는 자리'로 삼는다(상자 한 칸이면 여덟 자리. 막힌 칸이 없으면 가운데 칸을 상자로 봄). 다른 갉는 게이브가 덜 노리는
 * 가까운 자리를 고르고, 자리가 상자 건너편이면 상자를 끼고 돈다. 멀리서 길이 막혀 있을 때만 붙는 자리까지의 걷기 거리장을 따로 잰다(30초마다).
 *
 * <p>도감: 붙으면 15초 갉는 동안 상점 칸 하나가 잠기고(Shop.gnawStart), 다 갉으면 다음 쉬는 시간에 그 칸 값이 25% 오른다
 * (Shop.gnawEnd(key, true), 웨이브 알림 '상점 칸을 갉았다'). 사람 피해 없음. 갉은 자리에는 자국이 남는다(효과 자국, 1분).
 * 다 갉으면 다른 자리로 옮겨 또 갉는다. 갉는 동안 잡히면 그 칸은 그냥 풀린다. 상점 잠금은 하나뿐이라(Shop) 여럿이 같은 칸을 갉을 때는
 * 마지막 개체가 죽어야 풀리고, 하나가 다 갉으면 나머지는 새 칸을 잠근다.
 *
 * <p>팩 계약(tools/gabe/sp/g14.py): 모델 gave:gabe/g14, 상태 "" 달림, "gnaw" 갉음. 소리 gnaw(사각사각, 위치 소리), scurry.
 * 글귀 notice_g14_gnawed(cause_g14 는 피해가 없어 쓰이지 않음).
 */
public final class G14 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.2;
    /** 갉는 틱(15초), 소리 간격, 자리에 붙었다고 보는 거리, 갉다 밀려나면 다시 가는 거리. */
    static final int GNAW = 300, GNAW_SOUND = 12;
    static final double ARRIVE = 0.3, PUSHED = 0.9;
    /** 상점(보급 상자) 칸을 찾는 범위(가운데 칸에서 체비쇼프 거리), 붙는 자리를 상자 쪽으로 당기는 거리, 상자를 끼고 돌 때 두는 틈. */
    static final int SHOP_R = 2;
    static final double LEAN = 0.12, RING_GAP = 0.7;
    /** 붙는 자리와 거리장을 다시 재는 간격(밀리초). */
    static final long FIELD_MS = 30_000;

    enum Phase { RUN, GNAW }

    private Phase phase = Phase.RUN;
    private int phaseAt, slot = -1, gnawLeft = GNAW, nextSound, done;
    private String key;

    public G14(Game game, Species sp) {
        super(game, sp);
        radius = 0.42;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g14", 4.4f, 1.6f); // 쥐 꼴(몸통과 꼬리가 뒤로 2.2): 컬링 상자
    }

    @Override
    protected void spawned() {
        nextSound = age + game.random.nextInt(16);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        double[][] ss = slots(game);
        if (slot < 0 || slot >= ss.length) slot = pickSlot(ss, -1);
        switch (phase) {
            case RUN -> run(ss[slot]);
            case GNAW -> gnaw(ss);
        }
    }

    /** 사람을 무시하고 붙는 자리로 곧장. 자리가 상자 건너편이면 상자를 끼고 돈다. */
    private void run(double[] t) {
        double dx = t[0] - pos.getX(), dz = t[1] - pos.getZ(), d = Math.hypot(dx, dz);
        if (d <= ARRIVE) {
            hold();
            startGnaw();
            return;
        }
        // 원정: 트럭(3 x 6 발자국)은 둥글게 끼고 돌 수 없다. 곧은 길이 막혔으면 붙는 자리들까지의 걷기 거리장을 따르고, 가다가 닿은 다른 빈 자리에 붙는다
        boolean truck = kr.gave.trip.Truck.live();
        Vector dir = truck ? null : around(t);
        if (dir == null) {
            if ((truck || d >= 4) && !game.mobs.clearLine(pos, new Vector(t[0], pos.getY(), t[1]))) {
                dir = path();
                if (truck) {
                    double[][] ss = slots(game);
                    for (int i = 0; i < ss.length; i++) {
                        if (i == slot || Math.hypot(ss[i][0] - pos.getX(), ss[i][1] - pos.getZ()) > 0.7) continue;
                        slot = i;
                        return;
                    }
                }
            }
            if (dir == null) dir = new Vector(dx / d, 0, dz / d);
        }
        vel.copy(dir.multiply(Math.min(speed(), d)));
        if (vel.lengthSquared() > 1e-8) face(pos.clone().add(vel));
        pitch = 0;
        if (age >= nextSound) {
            sound("scurry", Sound.ENTITY_SILVERFISH_STEP, 0.9f, 16);
            nextSound = age + 14 + game.random.nextInt(6);
        }
    }

    /** 상자 가까이에서 자리가 내 쪽이 아니면(가운데에서 본 방위가 50도 넘게 다르면) 상자 둘레를 도는 방향. 아니면 null. */
    private Vector around(double[] t) {
        Vector cc = Shop.counterCenter();
        double cx = cc.getX(), cz = cc.getZ();
        double mx = pos.getX() - cx, mz = pos.getZ() - cz, mr = Math.hypot(mx, mz);
        double sx = t[0] - cx, sz = t[1] - cz, sr = Math.hypot(sx, sz);
        if (mr < 1e-3 || mr > sr + 3) return null;
        double ang = Math.atan2(mx * sz - mz * sx, mx * sx + mz * sz);
        if (Math.abs(ang) <= Math.toRadians(50)) return null;
        double sign = Math.signum(ang), radial = (sr + RING_GAP - mr) * 0.5;
        Vector v = new Vector(-mz / mr * sign + mx / mr * radial, 0, mx / mr * sign + mz / mr * radial);
        double l = v.length();
        return l < 1e-6 ? null : v.multiply(1 / l);
    }

    private void startGnaw() {
        state("gnaw");
        if (key == null) key = game.shop.gnawStart();
        phase(Phase.GNAW);
    }

    /** 상자에 얼굴을 박고 갉음(사각사각). 밀려나면 다시 붙으러 감(갉은 양은 남음). */
    private void gnaw(double[][] ss) {
        double[] t = ss[slot];
        face(new Vector(pos.getX() + t[2], pos.getY(), pos.getZ() + t[3]));
        pitch = 0;
        double dx = t[0] - pos.getX(), dz = t[1] - pos.getZ();
        if (Math.hypot(dx, dz) > PUSHED) {
            state("");
            phase(Phase.RUN);
            return;
        }
        vel.setX(dx * 0.3).setY(0).setZ(dz * 0.3);
        if (age >= nextSound) {
            sound("gnaw", Sound.BLOCK_GRINDSTONE_USE, 1.6f, 32);
            nextSound = age + GNAW_SOUND + game.random.nextInt(4);
        }
        if (--gnawLeft > 0) return;
        // 다 갉음: 그 칸 값이 다음 쉬는 시간에 오름, 알림, 자국. 다른 자리로 옮겨 또 갉는다
        String k = key;
        key = null;
        game.shop.gnawEnd(k, true);
        // 원정: 다 갉을 때마다 트럭 최대 체력의 TRUCK_GNAW(2%)
        kr.gave.trip.Truck tr = game.trip.truck();
        if (tr != null) tr.hitFrac(kr.gave.trip.Trip.Tune.TRUCK_GNAW, this, "cause_g14");
        // 같은 칸을 갉던 다른 개체: 잠금이 풀렸으니 새 칸을 잠근다
        for (Gabe g : game.mobs.all()) if (g != this && !g.dead && g instanceof G14 o && o.key != null) o.key = game.shop.gnawStart();
        done++;
        game.hud.notice("notice_g14_gnawed", Species.G14, 80);
        scar(t);
        gnawLeft = GNAW;
        slot = pickSlot(ss, slot);
        state("");
        phase(Phase.RUN);
    }

    /** 갉은 자리 자국(맞닿은 막힌 칸의 내 쪽 옆면, 1분). */
    private void scar(double[] t) {
        double nx = -t[2], nz = -t[3];
        if (Math.abs(nx) >= Math.abs(nz)) {
            nx = Math.signum(nx);
            nz = 0;
        } else {
            nz = Math.signum(nz);
            nx = 0;
        }
        int bx = (int) Math.floor(t[0] + t[2] * 0.9), bz = (int) Math.floor(t[1] + t[3] * 0.9);
        double j = (game.random.nextDouble() - 0.5) * 0.4;
        Location at = new Location(game.world, bx + 0.5 + nx * 0.5 + (nx == 0 ? j : 0), Plaza.FLOOR_Y + 0.4 + game.random.nextDouble() * 0.35,
                bz + 0.5 + nz * 0.5 + (nz == 0 ? j : 0));
        try {
            game.shotFx.decal(at, new Vector(nx, 0, nz), ShotFx.DECAL_SCORCH, 0.32f, 1200);
        } catch (RuntimeException ignored) {
            // 자국 없이
        }
    }

    // ------------------------------------------------------------------ 붙는 자리, 거리장

    /** 붙는 자리들: {자리 x, 자리 z, 상자 쪽 단위 x, 단위 z}. 가운데 둘레 막힌 칸에 맞닿은 걷는 칸마다 하나(옆으로 맞닿은 것 먼저). */
    private static double[][] slots = new double[0][];
    private static long slotsAt;
    private static Game slotsGame;
    /** 붙는 자리를 트럭 발자국으로 쟀나(트럭이 서거나 치워지면 다시 잼). */
    private static boolean slotsTruck;

    private static double[][] slots(Game game) {
        long ms = System.currentTimeMillis();
        if (slotsGame == game && slots.length > 0 && ms - slotsAt < FIELD_MS && slotsTruck == kr.gave.trip.Truck.live()) return slots;
        if (kr.gave.trip.Truck.live()) {
            // 원정: 트럭 발자국에 옆으로 맞닿은 칸 18개가 붙는 자리(Truck.slots)
            List<double[]> ts = kr.gave.trip.Truck.slots();
            double[][] out = new double[ts.size()][];
            for (int i = 0; i < out.length; i++) {
                double[] s = ts.get(i);
                out[i] = new double[] {s[0] + s[2] * LEAN, s[1] + s[3] * LEAN, s[2], s[3]};
            }
            slots = out;
            slotsAt = ms;
            slotsTruck = true;
            slotsGame = game;
            field = null;
            return slots;
        }
        slotsTruck = false;
        Flow fl = game.mobs.flow;
        int[] cell = Shop.counterCell();
        int cx = cell[0], cz = cell[1];
        // 상점 칸: Shop 이 카운터라고 보는 블록(Shop.isCounter, 가운데 둘레 COUNTER_R). 옛 맵은 3x3 판매대, 새 맵은 보급 상자 한 칸.
        // 카운터가 아직 없으면 걷기 격자에서 가운데 둘레 막힌 칸, 그것도 없으면 가운데 칸을 상자로 본다.
        int sr = Math.max(0, Math.min(SHOP_R, Shop.COUNTER_R));
        boolean[] box = new boolean[(2 * SHOP_R + 1) * (2 * SHOP_R + 1)];
        boolean any = false;
        for (int dx = -sr; dx <= sr; dx++)
            for (int dz = -sr; dz <= sr; dz++)
                if (counter(game, cx + dx, cz + dz)) any = box[(dx + SHOP_R) * (2 * SHOP_R + 1) + dz + SHOP_R] = true;
        if (!any)
            for (int dx = -SHOP_R; dx <= SHOP_R; dx++)
                for (int dz = -SHOP_R; dz <= SHOP_R; dz++)
                    if (!fl.walkable(cx + dx + 0.5, cz + dz + 0.5)) any = box[(dx + SHOP_R) * (2 * SHOP_R + 1) + dz + SHOP_R] = true;
        if (!any) box[SHOP_R * (2 * SHOP_R + 1) + SHOP_R] = true;
        List<double[]> out = new ArrayList<>();
        int[][] orth = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}, diag = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int dx = -SHOP_R - 1; dx <= SHOP_R + 1; dx++) {
            for (int dz = -SHOP_R - 1; dz <= SHOP_R + 1; dz++) {
                int x = cx + dx, z = cz + dz;
                if (Math.abs(dx) <= SHOP_R && Math.abs(dz) <= SHOP_R && box[(dx + SHOP_R) * (2 * SHOP_R + 1) + dz + SHOP_R]) continue;
                if (!fl.walkable(x + 0.5, z + 0.5)) continue;
                int[] to = null;
                for (int[][] set : new int[][][] {orth, diag}) {
                    for (int[] n : set) {
                        int ax = dx + n[0], az = dz + n[1];
                        if (Math.abs(ax) > SHOP_R || Math.abs(az) > SHOP_R) continue;
                        if (box[(ax + SHOP_R) * (2 * SHOP_R + 1) + az + SHOP_R]) {
                            to = n;
                            break;
                        }
                    }
                    if (to != null) break;
                }
                if (to == null) continue;
                double l = Math.hypot(to[0], to[1]), ux = to[0] / l, uz = to[1] / l;
                out.add(new double[] {x + 0.5 + ux * LEAN, z + 0.5 + uz * LEAN, ux, uz});
            }
        }
        if (out.isEmpty()) out.add(new double[] {cx + 1.5 - LEAN, cz + 0.5, -1, 0});
        slots = out.toArray(new double[0][]);
        slotsAt = ms;
        if (slotsGame != game) {
            slotsGame = game;
            field = null;
        }
        return slots;
    }

    /** (x, z) 의 발 높이나 그 위 칸이 상점 카운터 블록인가. */
    private static boolean counter(Game game, int x, int z) {
        for (int y = Plaza.FLOOR_Y; y <= Plaza.FLOOR_Y + 1; y++) {
            Block b = game.world.getBlockAt(x, y, z);
            if (Shop.isCounter(b)) return true;
        }
        return false;
    }

    /** 다른 갉는 게이브가 덜 노리는 자리 가운데 가까운 것(skip 은 방금 갉은 자리). */
    private int pickSlot(double[][] ss, int skip) {
        int[] taken = new int[ss.length];
        for (Gabe g : game.mobs.all()) if (g != this && !g.dead && g instanceof G14 o && o.slot >= 0 && o.slot < ss.length) taken[o.slot]++;
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < ss.length; i++) {
            if (i == skip && ss.length > 1) continue;
            double d = Math.hypot(ss[i][0] - pos.getX(), ss[i][1] - pos.getZ()) + taken[i] * 30;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private static final int W = Plaza.MAX - Plaza.MIN + 1, INF = Integer.MAX_VALUE;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1}, DZ = {0, 0, 1, -1, 1, -1, 1, -1}, COST = {2, 2, 2, 2, 3, 3, 3, 3};
    private static int[] field;
    private static long fieldAt;

    /** 붙는 자리(아무 것) 쪽 다음 걸음 방향(단위, 수평). 못 가면 null. 길이 막혔을 때만 부른다. */
    private Vector path() {
        int[] f = field();
        int cx = (int) Math.floor(pos.getX()), cz = (int) Math.floor(pos.getZ());
        int c = cell(cx, cz);
        if (c < 0 || f[c] == INF) return null;
        Flow fl = game.mobs.flow;
        double here = fl.floor(cx + 0.5, cz + 0.5);
        int best = -1, bd = f[c];
        for (int k = 0; k < 8; k++) {
            int nx = cx + DX[k], nz = cz + DZ[k], n = cell(nx, nz);
            if (n < 0 || f[n] >= bd || !step(fl, cx, cz, nx, nz, here)) continue;
            bd = f[n];
            best = k;
        }
        if (best < 0) return null;
        Vector to = new Vector(cx + DX[best] + 0.5 - pos.getX(), 0, cz + DZ[best] + 0.5 - pos.getZ());
        double l = to.length();
        return l < 1e-6 ? null : to.multiply(1 / l);
    }

    /** (cx, cz) 에서 이웃 (nx, nz) 로 걸어갈 수 있나(열린 칸, 오르는 턱 STEP, 대각은 두 옆 칸도 열려야). */
    private static boolean step(Flow fl, int cx, int cz, int nx, int nz, double here) {
        if (!fl.walkable(nx + 0.5, nz + 0.5)) return false;
        if (fl.floor(nx + 0.5, nz + 0.5) - here > Flow.STEP + 0.05) return false;
        if (nx != cx && nz != cz && (!fl.walkable(nx + 0.5, cz + 0.5) || !fl.walkable(cx + 0.5, nz + 0.5))) return false;
        return true;
    }

    private static int cell(int x, int z) {
        int ix = x - Plaza.MIN, iz = z - Plaza.MIN;
        if (ix < 0 || iz < 0 || ix >= W || iz >= W) return -1;
        return iz * W + ix;
    }

    /** 붙는 자리들까지의 걷기 거리장(자리 칸 모두에서 뒤로 넓혀 감, 30초마다 다시). */
    private int[] field() {
        long ms = System.currentTimeMillis();
        if (field != null && ms - fieldAt < FIELD_MS) return field;
        Flow fl = game.mobs.flow;
        int[] d = new int[W * W];
        Arrays.fill(d, INF);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (double[] s : slots(game)) {
            int t = cell((int) Math.floor(s[0]), (int) Math.floor(s[1]));
            if (t >= 0 && d[t] != 0) {
                d[t] = 0;
                q.add(t);
            }
        }
        // 다익스트라 근사(비용 2, 3): 넣은 뒤 더 짧은 길이 나오면 다시 넣음
        while (!q.isEmpty()) {
            int c = q.poll();
            int cx = c % W + Plaza.MIN, cz = c / W + Plaza.MIN;
            for (int k = 0; k < 8; k++) {
                int nx = cx + DX[k], nz = cz + DZ[k], n = cell(nx, nz);
                if (n < 0) continue;
                int nd = d[c] + COST[k];
                if (nd >= d[n]) continue;
                // 몹은 n 에서 c 로 걷는다
                if (!fl.walkable(nx + 0.5, nz + 0.5)) continue;
                if (!step(fl, nx, nz, cx, cz, fl.floor(nx + 0.5, nz + 0.5))) continue;
                d[n] = nd;
                q.add(n);
            }
        }
        field = d;
        fieldAt = ms;
        return d;
    }

    // ------------------------------------------------------------------ 죽음, 치움

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

    /** 갉던 칸을 그냥 풂(잡힘, 치움). 같은 칸을 아직 갉는 다른 개체가 있으면 잠금은 그대로 둔다. */
    private void release() {
        if (key == null) return;
        String k = key;
        key = null;
        for (Gabe g : game.mobs.all()) if (g != this && !g.dead && g instanceof G14 o && k.equals(o.key)) return;
        game.shop.gnawEnd(k, false);
    }

    public String phaseName() {
        return phase.name() + " slot=" + slot + " left=" + gnawLeft;
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " slot=" + slot + " left=" + gnawLeft + " done=" + done + (key != null ? " key=" + key : "");
    }

    /** 맞는 구: 머리(몸 가운데 앞, radius) + 털가죽 몸통(뒤 0.6, 아래 0.09). 2026-11-12 외형 쥐: 엉덩이를 쏘아도 맞게. */
    @Override
    public List<double[]> spheres() {
        double yr = Math.toRadians(yaw);
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), radius},
                new double[] {pos.getX() + 0.6 * Math.sin(yr), pos.getY() - 0.09, pos.getZ() - 0.6 * Math.cos(yr), 0.32});
    }
}
