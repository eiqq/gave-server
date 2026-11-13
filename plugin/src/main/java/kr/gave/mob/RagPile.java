package kr.gave.mob;

import java.util.Arrays;

/**
 * 굳은 사체 더미의 높이 지도(순수 계산, 엔티티 없음). 경기장을 {@link #CELL}(0.5블록) 칸으로 나눠 칸마다 그 위에 누운 사체의 윗면 높이
 * (세계 y)를 둔다. 움직이는 사체({@link RagBody}, {@link RagChain})는 바닥을 {@link #support}(땅과 이 지도 가운데 높은 쪽)로 보고 그 위에
 * 내려앉는다. 그래서 굳은 사체 위에 다음 사체가 올라앉아 더미가 된다. 사람, 게이브, 탄은 이 지도를 보지 않는다(그냥 지나감).
 *
 * <p>사체가 굳을 때(값싼 사체는 길을 정할 때 미리) {@link #stamp} 로 제 발자국(가운데, 반지름)을 윗면 높이로 올린다. 사체가 치워지면
 * (꺼짐, 먹힘) {@link Ragdoll} 이 {@link #restack} 으로 지도를 비우고 남은 사체를 낮은 것부터 다시 찍으면서, 받치던 것이 사라진 사체를
 * 내려앉힌다.
 *
 * <p>단(높이가 다른 땅) 가까이: 발자국은 받침(땅이나 아래 사체)이 밑면 가까이 있는 칸에만 찍히고, 옆 칸의 더미는 같은 높이의 땅 위에
 * 있을 때만 받침으로 친다. 그래서 단 위 사체가 단 아래로 내민 자리에 선 몸이 단 위 높이로 떠오르지 않는다.
 */
final class RagPile {

    /** 칸 크기(블록). 블록 모서리와 칸 모서리가 겹친다(칸 하나가 높이 다른 두 블록에 걸치지 않음). */
    static final double CELL = 0.5;
    /** 발자국이 작아도 가운데 칸 하나는 찍히게 하는 최소 반지름. */
    static final double MIN_R = 0.36;
    /** 받침을 잴 때 가운데에서 사방으로 보는 거리(몸이 옆 사체에 반쯤 파묻히지 않게). */
    static final double REACH = 0.3;
    /** 옆 칸의 더미를 받침으로 치는 땅 높이 차이. 발자국 칸의 받침이 밑면보다 이만큼 넘게 낮으면 찍지 않는다(허공에 내민 자리). */
    static final double LEVEL = 0.6;

    private final int x0, z0, n;
    private final float[] top;
    private final RagBody.Ground terrain;
    private boolean any;

    /** 블록 좌표 min 에서 blocks 블록(가로, 세로 같은 크기)을 덮는다. terrain = 땅(사체 없는 바닥). */
    RagPile(int min, int blocks, RagBody.Ground terrain) {
        this.x0 = min;
        this.z0 = min;
        this.n = Math.max(1, blocks) * 2;
        this.top = new float[n * n];
        this.terrain = terrain;
        Arrays.fill(top, Float.NEGATIVE_INFINITY);
    }

    /** 모두 비움. */
    void clear() {
        if (any) Arrays.fill(top, Float.NEGATIVE_INFINITY);
        any = false;
    }

    /** 찍힌 사체가 하나라도 있나. */
    boolean any() {
        return any;
    }

    /** 이 자리 칸의 사체 윗면 높이(없거나 지도 밖이면 음의 무한대). */
    double top(double x, double z) {
        int ix = (int) Math.floor((x - x0) / CELL), iz = (int) Math.floor((z - z0) / CELL);
        if (ix < 0 || iz < 0 || ix >= n || iz >= n) return Double.NEGATIVE_INFINITY;
        return top[iz * n + ix];
    }

    /**
     * 이 자리의 받침 높이: 땅, 이 칸의 사체 윗면, 사방 {@link #REACH} 옆 칸의 사체 윗면(그 칸의 땅이 이 자리 땅과 같은 높이일 때만)
     * 가운데 가장 높은 것. 움직이는 사체의 바닥이다.
     */
    double support(double x, double z) {
        double f = terrain.floor(x, z);
        if (!any) return f;
        double best = Math.max(f, top(x, z));
        for (int i = 0; i < 4; i++) {
            double sx = x + (i == 0 ? REACH : i == 1 ? -REACH : 0), sz = z + (i == 2 ? REACH : i == 3 ? -REACH : 0);
            double t = top(sx, sz);
            if (t > best && Math.abs(terrain.floor(sx, sz) - f) < LEVEL) best = t;
        }
        return best;
    }

    /**
     * 가운데 (x, z), 반지름 r 안의 칸(칸 가운데 기준)을 윗면 y 로 올린다(이미 더 높으면 그대로). base = 사체 밑면 높이: 받침(땅이나 아래
     * 사체)이 그보다 {@link #LEVEL} 넘게 낮은 칸은 건너뛴다(가운데 칸은 늘 찍음).
     */
    void stamp(double x, double z, double r, double base, double y) {
        double rr = Math.max(r, MIN_R);
        int ix0 = (int) Math.floor((x - rr - x0) / CELL), ix1 = (int) Math.floor((x + rr - x0) / CELL);
        int iz0 = (int) Math.floor((z - rr - z0) / CELL), iz1 = (int) Math.floor((z + rr - z0) / CELL);
        int icx = (int) Math.floor((x - x0) / CELL), icz = (int) Math.floor((z - z0) / CELL);
        float fy = (float) y;
        for (int iz = Math.max(0, iz0); iz <= Math.min(n - 1, iz1); iz++) {
            double wz = z0 + (iz + 0.5) * CELL, cz = wz - z;
            for (int ix = Math.max(0, ix0); ix <= Math.min(n - 1, ix1); ix++) {
                double wx = x0 + (ix + 0.5) * CELL, cx = wx - x;
                if (cx * cx + cz * cz > rr * rr) continue;
                int i = iz * n + ix;
                if ((ix != icx || iz != icz) && Math.max(top[i], terrain.floor(wx, wz)) < base - LEVEL) continue;
                if (top[i] < fy) top[i] = fy;
                any = true;
            }
        }
    }

    /**
     * 다시 쌓기(사체가 치워진 뒤): 사체 n 구(가운데 x, z, 발자국 반지름 r, 밑면 높이 base, 키 h)를 밑면이 낮은 것부터 다시 찍으면서,
     * 받침({@link #support})이 밑면보다 낮아진 사체의 base 를 그 받침으로 내린다(올리지는 않는다: 나란히 누운 사체가 서로 올라타지
     * 않게). 하나라도 내려갔으면 true. order = 차례를 적어 둘 곳(길이 n 이상).
     */
    boolean restack(int n, double[] x, double[] z, double[] r, double[] base, double[] h, int[] order) {
        for (int i = 0; i < n; i++) {
            // 밑면 높이로 끼워 넣기 정렬(거의 정렬돼 있음, 같은 높이는 들어온 차례)
            int j = i;
            while (j > 0 && base[order[j - 1]] > base[i]) {
                order[j] = order[j - 1];
                j--;
            }
            order[j] = i;
        }
        clear();
        boolean moved = false;
        for (int k = 0; k < n; k++) {
            int i = order[k];
            double s = support(x[i], z[i]);
            if (base[i] - s > 0.02) {
                base[i] = s;
                moved = true;
            }
            stamp(x[i], z[i], r[i], base[i], base[i] + h[i]);
        }
        return moved;
    }
}
