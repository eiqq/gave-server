package kr.gave.mob;

import org.joml.Vector3d;

/**
 * 래그돌 사체의 흐물 막대기 사슬 하나(순수 계산, 엔티티 없음: {@link Ragdoll} 이 마디마다 디스플레이에 싣는다).
 *
 * <p>이음점 n + 1 개. 0 번은 몸({@link RagBody})의 붙임점(몸 틀 좌표 root)에 매여 몸을 따라가고, 나머지는 베를레 적분(공기 저항, 중력)
 * 뒤에 거리 제약(마디 길이 seg)을 세 번 푼다. 이음점은 바닥 아래로 못 내려가고(굵기 반만큼 위), 바닥에 닿으면 미끄럼이 빨리 줄어
 * 눕는다. 몸 가운데 둘레 작은 구 안으로는 파고들지 않는다. 사슬이 몸을 끌지는 않는다(몸이 구르면 끌려다닐 뿐).
 */
final class RagChain {

    /** 공기 저항(틱마다 속도에 곱함), 중력(블록/틱^2), 바닥에 닿은 이음점의 미끄럼 감쇠. */
    static final double DRAG = 0.9, GRAVITY = 0.06, FLOOR = 0.5;
    /** 한 틱에 이음점이 갈 수 있는 거리 위끝(터지지 않게). */
    static final double V_MAX = 2.5;

    final int n;
    final double seg, thick, rx, ry, rz;
    /** 이음점 지금, 앞 틱((n + 1) x 3). */
    final double[] p, o;
    private final Vector3d tmp = new Vector3d();

    RagChain(int n, double seg, double thick, double rx, double ry, double rz) {
        this.n = Math.max(1, n);
        this.seg = Math.max(0.01, seg);
        this.thick = thick;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.p = new double[(this.n + 1) * 3];
        this.o = new double[(this.n + 1) * 3];
    }

    /** 붙임점에서 세계 방향 (dx, dy, dz) 쪽으로 곧게 펴고, 몸과 같은 속도로 출발. */
    void init(RagBody b, double dx, double dy, double dz) {
        b.world(rx, ry, rz, tmp);
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-9) {
            dx = 0;
            dy = -1;
            dz = 0;
            l = 1;
        }
        for (int i = 0; i <= n; i++) {
            int a = i * 3;
            p[a] = tmp.x + dx / l * seg * i;
            p[a + 1] = tmp.y + dy / l * seg * i;
            p[a + 2] = tmp.z + dz / l * seg * i;
            o[a] = p[a] - b.vx;
            o[a + 1] = p[a + 1] - b.vy;
            o[a + 2] = p[a + 2] - b.vz;
        }
    }

    /** 한 틱(몸을 먼저 한 틱 굴린 뒤에 부른다). */
    void step(RagBody b, RagBody.Ground g) {
        b.world(rx, ry, rz, tmp);
        o[0] = p[0];
        o[1] = p[1];
        o[2] = p[2];
        p[0] = tmp.x;
        p[1] = tmp.y;
        p[2] = tmp.z;
        for (int i = 3; i < p.length; i += 3) {
            double vx = (p[i] - o[i]) * DRAG, vy = (p[i + 1] - o[i + 1]) * DRAG - GRAVITY, vz = (p[i + 2] - o[i + 2]) * DRAG;
            double v2 = vx * vx + vy * vy + vz * vz;
            if (!(v2 < V_MAX * V_MAX)) {
                // 너무 빠르거나 계산이 터졌으면 줄임(NaN 이면 멈춤)
                double k = v2 > 0 && Double.isFinite(v2) ? V_MAX / Math.sqrt(v2) : 0;
                vx = k == 0 ? 0 : vx * k;
                vy = k == 0 ? 0 : vy * k;
                vz = k == 0 ? 0 : vz * k;
                if (!Double.isFinite(p[i] + p[i + 1] + p[i + 2])) {
                    p[i] = p[i - 3];
                    p[i + 1] = p[i - 2] - seg;
                    p[i + 2] = p[i - 1];
                }
            }
            o[i] = p[i];
            o[i + 1] = p[i + 1];
            o[i + 2] = p[i + 2];
            p[i] += vx;
            p[i + 1] += vy;
            p[i + 2] += vz;
        }
        double rs = Math.min(b.hx, Math.min(b.hy, b.hz)) * 0.85, half = thick / 2;
        for (int it = 0; it < 2; it++) {
            for (int i = 3; i < p.length; i += 3) {
                double dx = p[i] - p[i - 3], dy = p[i + 1] - p[i - 2], dz = p[i + 2] - p[i - 1];
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-9) {
                    dx = dz = 0;
                    dy = -1;
                    len = 1;
                }
                double k = (len - seg) / len;
                if (i == 3) {
                    // 붙임점은 안 움직인다
                    p[i] -= dx * k;
                    p[i + 1] -= dy * k;
                    p[i + 2] -= dz * k;
                } else {
                    k *= 0.5;
                    p[i - 3] += dx * k;
                    p[i - 2] += dy * k;
                    p[i - 1] += dz * k;
                    p[i] -= dx * k;
                    p[i + 1] -= dy * k;
                    p[i + 2] -= dz * k;
                }
            }
            for (int i = 3; i < p.length; i += 3) {
                // 몸 속으로 파고들지 않게(몸 가운데 둘레 작은 구 밖으로), 바닥 위로
                double dx = p[i] - b.x, dy = p[i + 1] - b.y, dz = p[i + 2] - b.z, d2 = dx * dx + dy * dy + dz * dz;
                if (d2 < rs * rs && d2 > 1e-9) {
                    double k = rs / Math.sqrt(d2);
                    p[i] = b.x + dx * k;
                    p[i + 1] = b.y + dy * k;
                    p[i + 2] = b.z + dz * k;
                }
                double fl = g.floor(p[i], p[i + 2]) + half;
                if (p[i + 1] < fl) p[i + 1] = fl;
            }
        }
        // 마지막 돌림: 뿌리에서 끝으로 마디 길이를 정확히 맞춘다(마디가 늘거나 줄어 보이지 않게). 바닥에 눌린 이음점은 높이를 둔 채
        // 수평으로 밀어 길이를 맞춘다(곧게 내려뜨려진 막대기가 바닥에 박히지 않고 옆으로 눕는다)
        for (int i = 3; i < p.length; i += 3) {
            double dx = p[i] - p[i - 3], dy = p[i + 1] - p[i - 2], dz = p[i + 2] - p[i - 1];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-9) {
                dx = dz = 0;
                dy = -1;
                len = 1;
            }
            double k = seg / len;
            p[i] = p[i - 3] + dx * k;
            p[i + 1] = p[i - 2] + dy * k;
            p[i + 2] = p[i - 1] + dz * k;
            double fl = g.floor(p[i], p[i + 2]) + half;
            if (p[i + 1] >= fl) continue;
            p[i + 1] = fl;
            dy = fl - p[i - 2];
            double h2 = seg * seg - dy * dy;
            if (h2 <= 0) continue;
            dx = p[i] - p[i - 3];
            dz = p[i + 2] - p[i - 1];
            double hl = Math.sqrt(dx * dx + dz * dz);
            if (hl < 1e-6) {
                // 수평 방향이 없으면 몸 가운데에서 바깥으로
                dx = p[i - 3] - b.x;
                dz = p[i - 1] - b.z;
                hl = Math.sqrt(dx * dx + dz * dz);
                if (hl < 1e-6) {
                    dx = 1;
                    dz = 0;
                    hl = 1;
                }
            }
            double h = Math.sqrt(h2);
            p[i] = p[i - 3] + dx / hl * h;
            p[i + 2] = p[i - 1] + dz / hl * h;
        }
        for (int i = 3; i < p.length; i += 3) {
            // 바닥에 닿은 이음점은 미끄럼이 빨리 준다(자리는 두고 다음 틱 속도만 줄임)
            if (p[i + 1] <= g.floor(p[i], p[i + 2]) + half + 1e-4) {
                o[i] = p[i] - (p[i] - o[i]) * FLOOR;
                o[i + 2] = p[i + 2] - (p[i + 2] - o[i + 2]) * FLOOR;
            }
        }
    }

    /** 굳은 사체의 누운 자리로 옮긴다: nodes = 몸 틀의 이음점(모델 좌표 x size). 몸은 이미 누운 자세여야 한다. */
    void lie(RagBody b, float[][] nodes, float size) {
        for (int i = 0; i <= n && i < nodes.length; i++) {
            b.world(nodes[i][0] * size, nodes[i][1] * size, nodes[i][2] * size, tmp);
            p[i * 3] = o[i * 3] = tmp.x;
            p[i * 3 + 1] = o[i * 3 + 1] = tmp.y;
            p[i * 3 + 2] = o[i * 3 + 2] = tmp.z;
        }
    }

    /** 붙임점으로 오므린다(누운 자세가 없는 모델: 굳을 때 막대기가 사라짐). */
    void retract(RagBody b) {
        b.world(rx, ry, rz, tmp);
        for (int i = 0; i <= n; i++) {
            p[i * 3] = o[i * 3] = tmp.x;
            p[i * 3 + 1] = o[i * 3 + 1] = tmp.y - i * 0.004;
            p[i * 3 + 2] = o[i * 3 + 2] = tmp.z;
        }
    }

    /** 가장 빨리 움직인 이음점의 이번 틱 이동 거리(멈췄는지 볼 때). */
    double motion() {
        double m = 0;
        for (int i = 3; i < p.length; i += 3) {
            double dx = p[i] - o[i], dy = p[i + 1] - o[i + 1], dz = p[i + 2] - o[i + 2];
            m = Math.max(m, dx * dx + dy * dy + dz * dz);
        }
        return Math.sqrt(m);
    }
}
