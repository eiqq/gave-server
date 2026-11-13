package kr.gave.mob;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 래그돌 사체의 강체 상자 하나(순수 계산, 엔티티 없음: {@link Ragdoll} 이 디스플레이에 싣는다).
 *
 * <p>상자는 몸 틀(모델 좌표, 세계 배율) 안의 가운데 (cx, cy, cz)와 반 크기 (hx, hy, hz)다. 상태는 상자 가운데의 세계 위치 (x, y, z)와
 * 속도, 방향 q(몸 틀 → 세계), 각속도 w(세계 축, 라디안/틱). 질량 1, 관성은 꽉 찬 상자.
 *
 * <p>한 틱({@link #step}): 중력 → 옮김(막힌 칸은 벽처럼 튕김) → 돌림 → 바닥 닿음. 닿음은 가장 낮은 꼭짓점들(가장 낮은 것에서 3.5cm 안)의
 * 가운데 한 점에서 충격량으로 푼다: 수직은 반발 계수로 튕기고(느리면 0), 수평은 쿨롱 마찰(충격량의 mu 배까지)로 미끄럼을 줄이면서 그
 * 점의 팔 길이만큼 회전을 만든다. 그래서 모서리로 떨어지면 구르고, 면으로 떨어지면 미끄러지다 멈추고, 기울어 선 상자는 넓은 면으로
 * 넘어진다. 사람 몸과는 부딪히지 않는다. 굳은 사체 더미는 바닥 높이({@link Ground#floor})에 얹혀 들어온다({@link RagPile}).
 *
 * <p>비탈: 발밑이 둘레({@link #SLOPE_REACH} 블록 밖 네 방향)보다 {@link #SLOPE_DROP} 넘게 높으면 가장 낮은 쪽으로 미끄러져 내려가고
 * 그동안은 멈춘 것으로 치지 않는다. 그래서 사체 더미가 한 줄 탑으로 쌓이지 않고 둔덕이 된다(계단에서도 굴러 내려감). 더미 속에서 죽은
 * 몸(바닥보다 한참 아래에서 시작)은 튀어 오르지 않고 구르던 대로 구르면서 나눈 틱마다 {@link #LIFT} 씩 떠오르고 낮은 옆으로
 * {@link #ESCAPE} 씩 밀려 나가 더미 위나 옆으로 나온다.
 */
final class RagBody {

    /** 바닥과 벽을 묻는 곳(엔진은 흐름장, 시험은 평지). */
    interface Ground {
        /** 이 자리 바닥 윗면 높이. */
        double floor(double x, double z);

        /** 이 자리로 들어갈 수 있나(벽, 경기장 밖이면 false). */
        boolean open(double x, double z);
    }

    /** 한 틱을 나누는 수(충돌이 깊이 박히지 않게). */
    static final int SUB = 2;
    /** 공기 저항(틱마다 속도, 각속도에 곱함), 바닥에 닿은 동안의 구름 저항과 제자리 돌기 저항(나눈 틱마다). */
    static final double AIR = 0.99, AIR_SPIN = 0.985, ROLL = 0.9, TWIST = 0.85;
    /** 이보다 느리게 닿으면 안 튕김(블록/틱). */
    static final double SOFT = 0.12;
    /** 빠르기, 각속도 위끝(블록/틱, 라디안/틱: 2틱 보간이 반 바퀴를 넘지 않게). */
    static final double V_MAX = 2.0, W_MAX = 0.8;
    /** 벽에 부딪혀 되튀는 비율, 넘을 수 있는 턱 높이. */
    static final double WALL = 0.35, STEP = 0.35;
    /** 멈춘 것으로 치는 빠르기, 각속도. 면으로 누운 채 이보다 느리면 빨리 잦아든다. */
    static final double REST_V = 0.03, REST_W = 0.06, CALM_V = 0.1, CALM_W = 0.25;
    /** 비탈: 둘레를 재는 거리(블록), 이만큼 넘게 높으면 미끄러짐, 미끄러지는 빠르기(나눈 틱마다 블록), 구르는 각속도 더하기. */
    static final double SLOPE_REACH = 1.0, SLOPE_DROP = 0.65, SLOPE_SLIDE = 0.04, SLOPE_ROLL = 0.03;
    /** 바닥 속에 든 몸(사체 더미 속에서 죽음)이 나눈 틱마다 떠오르는 높이와 낮은 옆으로 밀려 나가는 거리. 떨어져 박힌 만큼은 바로 올린다. */
    static final double LIFT = 0.15, ESCAPE = 0.1;

    final double cx, cy, cz, hx, hy, hz;
    double x, y, z, vx, vy, vz, wx, wy, wz;
    final Quaterniond q = new Quaterniond();
    /** 이번 틱에 바닥에 닿았나, 비탈에서 미끄러지는 중인가, 이어서 멈춰 있은 틱, 세게 닿은 횟수. */
    boolean ground, sliding;
    int rest, bounces;

    private final double ix, iy, iz;
    private final Matrix3d r = new Matrix3d();
    private final double[] corner = new double[24], t = new double[3];
    private final Quaterniond dq = new Quaterniond();
    private static final double AIR_SUB = Math.pow(AIR, 1.0 / SUB), SPIN_SUB = Math.pow(AIR_SPIN, 1.0 / SUB);

    RagBody(double cx, double cy, double cz, double hx, double hy, double hz) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.hx = Math.max(0.02, hx);
        this.hy = Math.max(0.02, hy);
        this.hz = Math.max(0.02, hz);
        // 얇은 판도 제멋대로 돌지 않게 관성은 반 크기 8cm 아래로 내려가지 않는다
        double ax = Math.max(this.hx, 0.08), ay = Math.max(this.hy, 0.08), az = Math.max(this.hz, 0.08);
        ix = 3.0 / (ay * ay + az * az);
        iy = 3.0 / (ax * ax + az * az);
        iz = 3.0 / (ax * ax + ay * ay);
    }

    /** 모델 원점(디스플레이 자리)의 세계 위치 = 상자 가운데 - q x c. */
    Vector3d origin(Vector3d out) {
        q.transform(out.set(cx, cy, cz));
        return out.set(x - out.x, y - out.y, z - out.z);
    }

    /** 모델 원점이 at 에 오게 상자 가운데를 놓는다(q 를 먼저 정할 것). */
    void place(double ox, double oy, double oz) {
        Vector3d c = q.transform(new Vector3d(cx, cy, cz));
        x = ox + c.x;
        y = oy + c.y;
        z = oz + c.z;
    }

    /** 몸 틀의 점(세계 배율) → 세계 위치. */
    Vector3d world(double lx, double ly, double lz, Vector3d out) {
        q.transform(out.set(lx - cx, ly - cy, lz - cz));
        return out.add(x, y, z);
    }

    /** 세계 축 충격량 j 를 상자 가운데에서 (rx, ry, rz) 떨어진 점에 준다(속도와 각속도가 함께 바뀜). */
    void impulse(double jx, double jy, double jz, double rx, double ry, double rz) {
        vx += jx;
        vy += jy;
        vz += jz;
        q.get(r);
        iinv(ry * jz - rz * jy, rz * jx - rx * jz, rx * jy - ry * jx);
        wx += t[0];
        wy += t[1];
        wz += t[2];
        limit();
    }

    /** 가장 낮은 꼭짓점의 높이(상자 가운데 기준, 음수). */
    double lowest() {
        q.get(r);
        return -(Math.abs(r.m01) * hx + Math.abs(r.m11) * hy + Math.abs(r.m21) * hz);
    }

    /** 지금 바닥 쪽을 가장 많이 보는 면: 0 아래(-Y), 1 위(+Y), 2 뒤(-Z), 3 앞(+Z), 4 -X, 5 +X(tools/gabe/kit.py RAG_DOWN). */
    int downFace() {
        q.get(r);
        double ax = r.m01, ay = r.m11, az = r.m21;
        int best = 0;
        double bs = ay;
        if (-ay > bs) {
            bs = -ay;
            best = 1;
        }
        if (az > bs) {
            bs = az;
            best = 2;
        }
        if (-az > bs) {
            bs = -az;
            best = 3;
        }
        if (ax > bs) {
            bs = ax;
            best = 4;
        }
        if (-ax > bs) best = 5;
        return best;
    }

    /** 면 f 가 바닥을 얼마나 보나(1 = 똑바로 아래). */
    double facing(int f) {
        q.get(r);
        return switch (f) {
            case 0 -> r.m11;
            case 1 -> -r.m11;
            case 2 -> r.m21;
            case 3 -> -r.m21;
            case 4 -> r.m01;
            default -> -r.m01;
        };
    }

    /** 면 f 가 바닥에 닿았을 때 상자 가운데 높이(바닥 위). */
    double halfOf(int f) {
        return f < 2 ? hy : f < 4 ? hz : hx;
    }

    /** 면 f 를 바닥에 똑바로 대고 눕힌다(가장 짧은 회전, 수직축 방향은 그대로). 속도는 0, 높이는 바닥 위. */
    void layFlat(int f, double floor) {
        Vector3d dn = new Vector3d(f == 4 ? -1 : f == 5 ? 1 : 0, f == 0 ? -1 : f == 1 ? 1 : 0, f == 2 ? -1 : f == 3 ? 1 : 0);
        q.transform(dn);
        dq.rotationTo(dn.x, dn.y, dn.z, 0, -1, 0);
        dq.mul(q, q).normalize();
        vx = vy = vz = wx = wy = wz = 0;
        y = floor + halfOf(f);
    }

    /** 한 틱. */
    void step(Ground g, double gravity, double bounce, double mu) {
        if (!(Double.isFinite(x + y + z + vx + vy + vz + wx + wy + wz) && Double.isFinite(q.x + q.y + q.z + q.w))) {
            // 계산이 터지면 그 자리에서 멈춤
            vx = vy = vz = wx = wy = wz = 0;
            if (!Double.isFinite(x + y + z)) x = y = z = 0;
            if (!Double.isFinite(q.x + q.y + q.z + q.w)) q.identity();
        }
        ground = false;
        sliding = false;
        double dt = 1.0 / SUB;
        for (int s = 0; s < SUB; s++) {
            vy -= gravity * dt;
            vx *= AIR_SUB;
            vy *= AIR_SUB;
            vz *= AIR_SUB;
            wx *= SPIN_SUB;
            wy *= SPIN_SUB;
            wz *= SPIN_SUB;
            limit();
            double bottom = y + lowest();
            double nx = x + vx * dt, nz = z + vz * dt;
            if (vx != 0 && blocked(g, nx, z, bottom)) {
                vx = -vx * WALL;
                nx = x;
            }
            if (vz != 0 && blocked(g, nx, nz, bottom)) {
                vz = -vz * WALL;
                nz = z;
            }
            x = nx;
            z = nz;
            y += vy * dt;
            double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (wl > 1e-9) {
                dq.fromAxisAngleRad(wx / wl, wy / wl, wz / wl, wl * dt);
                dq.mul(q, q).normalize();
            }
            contact(g, bounce, mu);
        }
        if (ground && !sliding && vx * vx + vy * vy + vz * vz < REST_V * REST_V && wx * wx + wy * wy + wz * wz < REST_W * REST_W) rest++;
        else rest = 0;
    }

    /** 상태를 o 와 같게(위치, 속도, 방향, 각속도. 상자 크기는 같아야 함). */
    void set(RagBody o) {
        x = o.x;
        y = o.y;
        z = o.z;
        vx = o.vx;
        vy = o.vy;
        vz = o.vz;
        wx = o.wx;
        wy = o.wy;
        wz = o.wz;
        q.set(o.q);
        ground = o.ground;
        sliding = o.sliding;
        rest = o.rest;
        bounces = o.bounces;
    }

    /**
     * 굳을 때 바닥에 댈 면: allowed(null 이면 여섯 면 모두) 가운데 지금 바닥을 가장 많이 보는 면(비슷하면 낮게 눕는 면). avoid 는 피할 면
     * 묶음(비트 f 가 켜진 면으로 누우면 얼굴이 바닥에 깔림): 고른 면이 거기 들면, 피하지 않아도 되는 면 가운데 가장 납작하게 눕는 면
     * (가운데가 가장 낮은 면, 같으면 지금 바닥을 더 보는 면)으로 바꾼다. 엎어진 몸은 그래서 뒤로 눕는다(얼굴이 하늘을 봄).
     */
    int restFace(int[] allowed, int avoid) {
        int count = allowed == null ? 6 : allowed.length, best = -1, flat = -1;
        double bs = -1e9, fh = 1e9, ff = -2;
        boolean hidden = false;
        for (int i = 0; i < count; i++) {
            int f = allowed == null ? i : allowed[i];
            if (f < 0 || f > 5) continue;
            boolean bad = (avoid >> f & 1) != 0;
            double fc = facing(f), h = halfOf(f), sc = fc - 0.12 * h;
            if (sc > bs) {
                bs = sc;
                best = f;
                hidden = bad;
            }
            if (!bad && (h < fh - 1e-9 || (h < fh + 1e-9 && fc > ff))) {
                fh = h;
                ff = fc;
                flat = f;
            }
        }
        if (best < 0) return downFace();
        return hidden && flat >= 0 ? flat : best;
    }

    /** 자세만 (x, y, z, q)로 놓고 멈춘다(키프레임 자세를 실을 때). */
    void pose(double px, double py, double pz, double qx, double qy, double qz, double qw) {
        x = px;
        y = py;
        z = pz;
        q.set(qx, qy, qz, qw);
        vx = vy = vz = wx = wy = wz = 0;
    }

    /** 두 면이 맞닿아 있나(마주 보는 면이 아님). */
    static boolean adjacent(int a, int b) {
        return a != b && (a >> 1) != (b >> 1);
    }

    /**
     * 면 from 으로 똑바로 누운 자세(지금 자세가 그래야 함: {@link #layFlat})에서, 맞닿은 면 to 쪽 아래 모서리를 축으로 angle 라디안만큼
     * 굴러 넘어간 자세로 바꾼다(PI / 2 면 면 to 로 똑바로 누움). 속도는 0.
     */
    void rollOver(int from, int to, double angle) {
        // 면 to 의 바깥 법선(세계 축, 누운 상태에서는 수평)
        Vector3d ng = new Vector3d(to == 4 ? -1 : to == 5 ? 1 : 0, to == 0 ? -1 : to == 1 ? 1 : 0, to == 2 ? -1 : to == 3 ? 1 : 0);
        q.transform(ng);
        ng.y = 0;
        double l = ng.length();
        if (l < 1e-6) return;
        ng.div(l);
        double hd = halfOf(from), hg = halfOf(to);
        // 축: ng 를 아래로 보내는 회전(ng x 아래), 받침 모서리 P = 가운데 + 아래 x hd + ng x hg
        double ax = ng.z, az = -ng.x;
        double px = x + ng.x * hg, py = y - hd, pz = z + ng.z * hg;
        dq.fromAxisAngleRad(ax, 0, az, angle);
        Vector3d c = dq.transform(new Vector3d(x - px, y - py, z - pz));
        x = px + c.x;
        y = py + c.y;
        z = pz + c.z;
        dq.mul(q, q).normalize();
        vx = vy = vz = wx = wy = wz = 0;
    }

    private boolean blocked(Ground g, double px, double pz, double bottom) {
        return !g.open(px, pz) || g.floor(px, pz) > bottom + STEP;
    }

    private void limit() {
        double v2 = vx * vx + vy * vy + vz * vz;
        if (v2 > V_MAX * V_MAX) {
            double k = V_MAX / Math.sqrt(v2);
            vx *= k;
            vy *= k;
            vz *= k;
        }
        double w2 = wx * wx + wy * wy + wz * wz;
        if (w2 > W_MAX * W_MAX) {
            double k = W_MAX / Math.sqrt(w2);
            wx *= k;
            wy *= k;
            wz *= k;
        }
    }

    /** 세계 축 벡터에 관성 역수를 곱해 t 에(R diag(i) R^T v). r 은 지금 방향 행렬이어야 한다. */
    private void iinv(double ax, double ay, double az) {
        double lx = (r.m00 * ax + r.m01 * ay + r.m02 * az) * ix;
        double ly = (r.m10 * ax + r.m11 * ay + r.m12 * az) * iy;
        double lz = (r.m20 * ax + r.m21 * ay + r.m22 * az) * iz;
        t[0] = r.m00 * lx + r.m10 * ly + r.m20 * lz;
        t[1] = r.m01 * lx + r.m11 * ly + r.m21 * lz;
        t[2] = r.m02 * lx + r.m12 * ly + r.m22 * lz;
    }

    private void contact(Ground g, double e, double mu) {
        q.get(r);
        double fl = g.floor(x, z), low = Double.MAX_VALUE;
        for (int i = 0, k = 0; i < 8; i++) {
            double sx = (i & 1) == 0 ? -hx : hx, sy = (i & 2) == 0 ? -hy : hy, sz = (i & 4) == 0 ? -hz : hz;
            double py = r.m01 * sx + r.m11 * sy + r.m21 * sz;
            corner[k++] = r.m00 * sx + r.m10 * sy + r.m20 * sz;
            corner[k++] = py;
            corner[k++] = r.m02 * sx + r.m12 * sy + r.m22 * sz;
            if (py < low) low = py;
        }
        double pen = fl - (y + low);
        if (pen < -0.002) return;
        // 이번에 내려온 만큼은 바로 올린다. 그보다 깊이 든 몸(사체 더미 속에서 죽었거나 더미가 둘레에 생김)은 닿은 것으로 치지 않고
        // (구르던 대로 구른다) 천천히 떠오르면서, 둘레에 제 밑면보다 낮은 바닥이 있으면 그쪽으로 밀려 나간다
        if (pen > Math.max(LIFT, 0.02 - vy / SUB)) {
            sliding = true;
            y += LIFT;
            if (vy < 0) vy = 0;
            if (wx * wx + wz * wz < 0.02) {
                // 거의 안 돌고 있으면 구르게(선 채로 더미 위에 올라앉지 않게). 방향은 자리에서 정해지는 값
                double a = (x * 12.9898 + z * 78.233) % (Math.PI * 2);
                wx += Math.cos(a) * 0.22;
                wz += Math.sin(a) * 0.22;
            }
            double best = y + low, ex = 0, ez = 0;
            for (int i = 0; i < 4; i++) {
                double dx = i == 0 ? SLOPE_REACH : i == 1 ? -SLOPE_REACH : 0, dz = i == 2 ? SLOPE_REACH : i == 3 ? -SLOPE_REACH : 0;
                if (!g.open(x + dx, z + dz)) continue;
                double h = g.floor(x + dx, z + dz);
                if (h < best) {
                    best = h;
                    ex = dx / SLOPE_REACH;
                    ez = dz / SLOPE_REACH;
                }
            }
            x += ex * ESCAPE;
            z += ez * ESCAPE;
            return;
        }
        if (pen > 0) y += pen;
        ground = true;
        double rx = 0, ry = 0, rz = 0;
        int n = 0;
        for (int i = 0; i < 24; i += 3) {
            if (corner[i + 1] <= low + 0.035) {
                rx += corner[i];
                ry += corner[i + 1];
                rz += corner[i + 2];
                n++;
            }
        }
        rx /= n;
        ry /= n;
        rz /= n;
        double uy = vy + wz * rx - wx * rz;
        if (uy < 0) {
            // 수직 충격량: 닿은 점의 수직 속도를 (느리면 0 으로, 빠르면 반발 계수만큼 뒤집어) 맞춘다
            iinv(-rz, 0, rx);
            double kn = 1 + (t[2] * rx - t[0] * rz);
            boolean hard = -uy > SOFT;
            double jn = -(1 + (hard ? e : 0)) * uy / kn;
            vy += jn;
            wx += t[0] * jn;
            wy += t[1] * jn;
            wz += t[2] * jn;
            if (hard) bounces++;
            // 마찰: 닿은 점의 미끄럼을 충격량의 mu 배까지 줄인다(팔 길이만큼 회전이 생김)
            double ux = vx + wy * rz - wz * ry, uz = vz + wx * ry - wy * rx;
            double ul = Math.sqrt(ux * ux + uz * uz);
            if (ul > 1e-7) {
                double tx = ux / ul, tz = uz / ul;
                iinv(ry * tz, rz * tx - rx * tz, -ry * tx);
                double kt = 1 + (t[1] * rz - t[2] * ry) * tx + (t[0] * ry - t[1] * rx) * tz;
                double jt = Math.min(ul / kt, mu * jn);
                vx -= tx * jt;
                vz -= tz * jt;
                wx -= t[0] * jt;
                wy -= t[1] * jt;
                wz -= t[2] * jt;
            }
        }
        wx *= ROLL;
        wy *= TWIST;
        wz *= ROLL;
        // 비탈: 발밑이 둘레보다 한참 높으면(더미 꼭대기, 계단 끝) 가장 낮은 쪽으로 미끄러져 내려간다
        double lowAt = fl - SLOPE_DROP, sx = 0, sz = 0;
        for (int i = 0; i < 4; i++) {
            double dx = i == 0 ? SLOPE_REACH : i == 1 ? -SLOPE_REACH : 0, dz = i == 2 ? SLOPE_REACH : i == 3 ? -SLOPE_REACH : 0;
            if (!g.open(x + dx, z + dz)) continue;
            double h = g.floor(x + dx, z + dz);
            if (h < lowAt) {
                lowAt = h;
                sx = dx / SLOPE_REACH;
                sz = dz / SLOPE_REACH;
            }
        }
        if (sx != 0 || sz != 0) {
            sliding = true;
            x += sx * SLOPE_SLIDE;
            z += sz * SLOPE_SLIDE;
            // 내려가는 쪽으로 구르듯(축 = 위 x 방향)
            wx += sz * SLOPE_ROLL;
            wz -= sx * SLOPE_ROLL;
        }
        // 면으로 누운 채(꼭짓점 셋 넘게 닿음) 느려졌으면 빨리 잦아든다(모서리로 선 상자는 그대로 두어 스스로 넘어지게)
        if (n >= 3 && vx * vx + vz * vz < CALM_V * CALM_V && wx * wx + wy * wy + wz * wz < CALM_W * CALM_W && Math.abs(vy) < 0.1) {
            vx *= 0.8;
            vz *= 0.8;
            wx *= 0.7;
            wy *= 0.7;
            wz *= 0.7;
        }
    }
}
