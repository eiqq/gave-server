package kr.gave.mob;

/**
 * 값싼 사체의 길(순수 계산, 엔티티 없음). 강체 {@link RagBody} 를 그 자리에서 끝까지 굴려 보고(매 틱 물리는 여기서 한 번에 끝남)
 * 날아가는 동안은 {@link #FLY_STEP} 틱마다, 땅에 닿은 뒤로는 {@link #GROUND_STEP} 틱마다의 자세만 키프레임으로 남긴다.
 * {@link Ragdoll} 은 키프레임마다 한 번씩만 보간으로 보내므로(디스플레이 하나, 죽음 하나에 패킷 네댓 번) 수백 마리가 한꺼번에 죽어도
 * 거의 모두 포물선으로 날아가 눕는다.
 *
 * <p>끝은 굳는 자세({@link #rest}): 얼굴이 깔리지 않는 면 {@link #face} 로 똑바로 눕는다. 멈춘 면이 그 면이 아니면(엎어짐) 아래 모서리를
 * 축으로 45도씩 굴러 넘어가는 키프레임이 붙는다. 매 틱 물리로 굴리던 사체를 굳힐 때도 같은 키프레임을 쓴다.
 */
final class RagPlan {

    /** 키프레임 간격(틱): 날아가는 동안(포물선이 꺾여 보이지 않을 만큼), 땅에 닿은 뒤. */
    static final int FLY_STEP = 5, GROUND_STEP = 8;
    /** 한 길의 키프레임 수 위끝(넘으면 간격을 넓힘). */
    static final int MAX_KEYS = 12;

    /** 키프레임: 시작에서 at 틱 뒤에 상자 가운데가 (x, y, z), 방향 q 에 닿는다. */
    static final class Key {
        int at;
        double x, y, z, qx, qy, qz, qw;

        Key() {
        }

        Key(int at, RagBody b) {
            this.at = at;
            x = b.x;
            y = b.y;
            z = b.z;
            qx = b.q.x;
            qy = b.q.y;
            qz = b.q.z;
            qw = b.q.w;
        }
    }

    final Key[] keys;
    /** 굳을 때 바닥에 대는 면, 처음 땅에 닿는 틱, 멈추는 틱, 굳는 자리의 첫 키프레임 번호(그 앞은 날아가고 구르는 길). */
    final int face, land, rest, restKey;

    private RagPlan(Key[] keys, int face, int land, int rest, int restKey) {
        this.keys = keys;
        this.face = face;
        this.land = land;
        this.rest = rest;
        this.restKey = restKey;
    }

    /**
     * b 를 지금 상태에서 멈출 때까지(길어야 maxTicks) 굴려 길을 만든다. b 는 굳은 자세로 끝난다. allowed = 굳을 때 댈 수 있는 면
     * (null 이면 여섯 면), avoid = 피할 면 묶음(얼굴이 깔리는 면, {@link RagBody#restFace}), rollStep = 굴러 넘어갈 때 45도마다 틱,
     * buf = 틱마다 자세를 적어 둘 곳(길이 (maxTicks + 1) x 7 이상).
     */
    static RagPlan make(RagBody b, RagBody.Ground g, double gravity, double bounce, double mu, int maxTicks, int[] allowed, int avoid, int rollStep,
            double[] buf) {
        int land = -1, rest = -1, t = 0;
        put(buf, 0, b);
        while (t < maxTicks) {
            b.step(g, gravity, bounce, mu);
            t++;
            put(buf, t, b);
            if (land < 0 && b.ground) land = t;
            if (b.rest >= 3) {
                rest = t;
                break;
            }
        }
        if (rest < 0) rest = t;
        if (land < 0 || land > rest) land = rest;
        int cur = b.downFace(), face = b.restFace(allowed, avoid);
        int tail = face == cur ? 1 : RagBody.adjacent(cur, face) ? 2 : 4, cap = MAX_KEYS - tail;
        // 키프레임 틱 고르기: 공중은 fly 마다, 처음 닿는 틱, 그 뒤는 ground 마다(끝은 굳는 자세가 따로 붙음)
        int fly = FLY_STEP, ground = GROUND_STEP, n;
        int[] ticks = new int[MAX_KEYS];
        while (true) {
            n = 0;
            boolean fits = true;
            for (int k = fly; k < land - 1; k += fly) {
                if (n >= cap) {
                    fits = false;
                    break;
                }
                ticks[n++] = k;
            }
            if (fits && land > 2 && land < rest - 1) {
                if (n >= cap) fits = false;
                else ticks[n++] = land;
            }
            for (int k = land + ground; fits && k < rest - 2; k += ground) {
                if (n >= cap) {
                    fits = false;
                    break;
                }
                ticks[n++] = k;
            }
            if (fits) break;
            fly += 2;
            ground += 4;
        }
        Key[] keys = new Key[n + tail];
        for (int i = 0; i < n; i++) keys[i] = get(buf, ticks[i]);
        int m = rest(b, g, face, Math.max(1, rest), 0, rollStep, keys, n);
        if (m != keys.length) keys = java.util.Arrays.copyOf(keys, m);
        return new RagPlan(keys, face, land, rest, n);
    }

    /**
     * 굳는 키프레임을 out[n] 부터 채우고 다음 번호를 돌려준다(많아야 4개). b(지금 자세, 시작에서 at 틱 뒤)를 면 face 로 똑바로 눕힌다:
     * 지금 바닥을 보는 면이 face 면 flat 틱 뒤에 눕고, 아니면 아래 모서리를 축으로 step 틱마다 45도씩 굴러 넘어간다(맞은편 면이면 옆면을
     * 거쳐 두 번). 눕는 높이는 굴러간 자리의 바닥({@link RagBody.Ground#floor}) 위. b 는 굳은 자세로 끝난다.
     */
    static int rest(RagBody b, RagBody.Ground g, int face, int at, int flat, int step, Key[] out, int n) {
        int cur = b.downFace();
        double[] facing = new double[6];
        for (int f = 0; f < 6; f++) facing[f] = b.facing(f);
        b.layFlat(cur, g.floor(b.x, b.z));
        if (face == cur) {
            out[n++] = new Key(at + flat, b);
            return n;
        }
        int mid = -1;
        if (!RagBody.adjacent(cur, face)) {
            // 맞은편: 가장 낮게 넘어가는 옆면을 거친다(같으면 이미 그쪽으로 기운 면)
            double mh = 0, mf = 0;
            for (int f = 0; f < 6; f++) {
                if (!RagBody.adjacent(cur, f)) continue;
                double h = b.halfOf(f), fc = facing[f];
                if (mid < 0 || h < mh - 1e-9 || (h < mh + 1e-9 && fc > mf)) {
                    mid = f;
                    mh = h;
                    mf = fc;
                }
            }
        }
        int t = at, from = cur;
        for (int leg = mid < 0 ? 1 : 0; leg < 2; leg++) {
            int to = leg == 0 ? mid : face;
            double sx = b.x, sy = b.y, sz = b.z, qx = b.q.x, qy = b.q.y, qz = b.q.z, qw = b.q.w;
            b.rollOver(from, to, Math.PI / 4);
            out[n++] = new Key(t + step, b);
            b.pose(sx, sy, sz, qx, qy, qz, qw);
            b.rollOver(from, to, Math.PI / 2);
            b.layFlat(to, g.floor(b.x, b.z));
            out[n++] = new Key(t + 2 * step, b);
            t += 2 * step;
            from = to;
        }
        return n;
    }

    private static void put(double[] buf, int t, RagBody b) {
        int i = t * 7;
        buf[i] = b.x;
        buf[i + 1] = b.y;
        buf[i + 2] = b.z;
        buf[i + 3] = b.q.x;
        buf[i + 4] = b.q.y;
        buf[i + 5] = b.q.z;
        buf[i + 6] = b.q.w;
    }

    private static Key get(double[] buf, int t) {
        int i = t * 7;
        Key k = new Key();
        k.at = t;
        k.x = buf[i];
        k.y = buf[i + 1];
        k.z = buf[i + 2];
        k.qx = buf[i + 3];
        k.qy = buf[i + 4];
        k.qz = buf[i + 5];
        k.qw = buf[i + 6];
        return k;
    }
}
