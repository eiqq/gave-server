package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;

/**
 * G142 담 치는 지네 게이브(4막, 방패, 튼튼이). 마디 열여덟, 길이 28 블록의 얼굴 지네가 고리에서 나와 트럭 곁(6~9 블록)을 스치며 트럭 둘레를
 * 가로지른다. 몸이 트럭 둘레를 둘로 가르는 살아 있는 담이 되고, 반대편(가운데에서 44 블록)에 닿으면 바깥을 70~150도 돈 뒤 다른 각도로 다시
 * 가로지른다(0.16 블록/틱).
 *
 * <ul>
 * <li>머리 물기: 머리 앞 2.6 블록 안 32 와 날림.</li>
 * <li>몸통: 마디에 깔리거나 넘으려 하면 다리에 찍혀 14(사람마다 0.5초에 한 번)와 몸 바깥으로 밀림.</li>
 * <li>15초마다 다리 사이에서 작은 지네 조각 둘({@link G142Bit}, 보상 0).</li>
 * <li>맞음: 마디마다 껍질. 같은 마디를 1.5초 안에 세 번 맞혀야 깨지고(처음 두 발은 튕김) 셋째부터 피해가 든다. 폭발은 바로 깸.
 *   깨진 마디에 피해가 쌓이면(최대 체력의 10%) 그 토막에서 끊겨 뒤쪽은 떨어져 나간다(남은 체력에서 그만큼 깎임). 머리는 껍질 없음.</li>
 * <li>가로지를 길에 흙먼지 줄이 3초 먼저 인다.</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g142.py): 머리 gabe/g142(shrink 0.45, 상태 "", "bite"), 토막 gabe/g142_seg(shrink 0.6, 부위 crack0..2) 여섯,
 * 꼬리 gabe/g142_tail, 조각 gabe/g142_bit. 소리 legs(64), bite(32), tink(24), crack(32), cut(48). 글귀 cause_g142, cause_g142_legs, cause_g142_bit.
 *
 * <p>도감과 다른 점: 끊김은 토막(마디 셋) 단위다(디스플레이 하나가 마디 셋). 마디 얼굴이 차례로 돌아보는 것은 마디 양옆 얼굴 판으로 대신했다.
 */
public final class G142 extends Gabe {

    static final double HOVER = 1.3, SPEED = 0.16, SEG_Y = 0.8;
    static final int SEGS = 18, PIECES = 6;
    /** 머리 가운데에서 첫 마디까지, 마디 사이. */
    static final double HEAD_GAP = 1.95, SEG = 1.55;
    static final double SEG_R = 0.95, HEAD_R = 1.35;
    /** 길: 트럭 곁 스치는 거리, 반대편 끝(가운데에서), 도는 고리 반지름. */
    static final double PASS_MIN = 6, PASS_MAX = 9, EXIT_R = 44, ORBIT_R = 44;
    static final float TURN = 3.2f;
    /** 물기, 다리 찍기. */
    static final double BITE_R = 2.6, BITE_DMG = 32, LEG_R = 1.35, LEG_DMG = 14;
    static final int BITE_CD = 24, LEG_CD = 10;
    /** 껍질: 세는 틱, 깨는 수, 끊는 피해(최대 체력 몫). */
    static final int CRACK_WINDOW = 30, CRACK_HITS = 3;
    static final double CUT_FRAC = 0.10;
    static final int BIT_CD = 300, BIT_N = 2;
    /** 자취 길이(점). */
    static final int TRAIL = 512;

    enum Phase { CROSS, ORBIT }

    private Phase phase = Phase.CROSS;
    private final Vector pass = new Vector(), exit = new Vector();
    private double orbitLeft;
    private int orbitDir = 1;
    private int crossAt;

    // 자취: 머리 자리(바닥 높이), 누적 거리
    private final double[] tx = new double[TRAIL], tz = new double[TRAIL], ty = new double[TRAIL], td = new double[TRAIL];
    private int tHead = -1, tCount;
    private double travelled;

    private final Body.Part[] pieces = new Body.Part[PIECES];
    private Body.Part tail;
    private int alivePieces = PIECES;
    private final boolean[] cracked = new boolean[SEGS];
    private final double[] segDmg = new double[SEGS];
    private final int[][] hits = new int[SEGS][CRACK_HITS];
    private final Map<Player, Integer> legHit = new IdentityHashMap<>();
    private int biteAt, bitAt, legsAt, tinkAt, biteShow = -1;
    private final List<double[]> sph = new ArrayList<>(SEGS + 1);
    private int sphAge = -1;
    private final Vector[] segPos = new Vector[SEGS];

    public G142(Game game, Species sp) {
        super(game, sp);
        radius = HEAD_R;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        noPush = true;
        noMutate = true;
        noBite = true;
        ghost = true;
        ragKick = 0.3;
        hpPow = Director.Tune.HP_POW_BIG;
        for (int i = 0; i < SEGS; i++) segPos[i] = new Vector();
        for (int[] h : hits) java.util.Arrays.fill(h, -1000);
    }

    @Override
    protected void build() {
        // 컬링 상자: 토막(승객)이 뒤로 30 블록까지 뻗으므로 넉넉히
        body = Body.of(this, "gabe/g142", 64f, 6f);
        body.viewRange(3.2f);
        for (int i = 0; i < PIECES; i++) {
            pieces[i] = body.attach("gabe/g142_seg");
            if (pieces[i] != null) pieces[i].follow = false;
        }
        tail = body.attach("gabe/g142_tail");
        if (tail != null) tail.follow = false;
    }

    @Override
    protected void spawned() {
        Vector c = G141Kit.middle();
        G141Kit.faceFlat(this, c);
        // 자취를 몸 뒤로 곧게 채움(처음부터 28 블록 몸이 뒤로 뻗어 있게)
        Vector b = G141Kit.fwd(this).multiply(-1);
        double total = HEAD_GAP + SEG * SEGS + 3;
        int n = (int) Math.ceil(total / 0.5);
        for (int i = n; i >= 0; i--) {
            Vector p = pos.clone().add(b.clone().multiply(i * 0.5));
            record(p.getX(), floorAt(p), p.getZ(), i == n);
        }
        segments();
        if (game.director.cageSpawning()) return;
        cross(c);
        bitAt = age + BIT_CD / 2;
        placePieces(0);
    }

    private double floorAt(Vector p) {
        return game.mobs.flow.floor(p.getX(), p.getZ());
    }

    private void record(double x, double y, double z, boolean first) {
        if (tHead >= 0 && !first) {
            double dx = x - tx[tHead], dz = z - tz[tHead];
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 0.05) return;
            travelled += d;
        }
        tHead = (tHead + 1) % TRAIL;
        tx[tHead] = x;
        ty[tHead] = y;
        tz[tHead] = z;
        td[tHead] = travelled;
        tCount = Math.min(TRAIL, tCount + 1);
    }

    /** 머리에서 자취를 따라 back 블록 뒤 점(바닥 높이). out 에 씀. 방향은 dir 에(수평 단위, 앞 쪽). */
    private void trail(double back, Vector out, Vector dir) {
        double want = td[tHead] - back;
        int i = tHead;
        for (int k = 0; k < tCount - 1; k++) {
            int j = (i - 1 + TRAIL) % TRAIL;
            if (td[j] <= want) {
                double span = td[i] - td[j];
                double t = span < 1e-9 ? 0 : (want - td[j]) / span;
                out.setX(tx[j] + (tx[i] - tx[j]) * t);
                out.setY(ty[j] + (ty[i] - ty[j]) * t);
                out.setZ(tz[j] + (tz[i] - tz[j]) * t);
                if (dir != null) {
                    dir.setX(tx[i] - tx[j]);
                    dir.setY(0);
                    dir.setZ(tz[i] - tz[j]);
                }
                return;
            }
            i = j;
        }
        out.setX(tx[i]);
        out.setY(ty[i]);
        out.setZ(tz[i]);
        if (dir != null) dir.copy(G141Kit.fwd(this));
    }

    // ------------------------------------------------------------------ 길

    /** 새로 가로지름: 트럭 곁 스치는 점을 고르고 그 너머 끝점까지. */
    private void cross(Vector c) {
        double a = game.random.nextDouble() * Math.PI * 2, r = PASS_MIN + game.random.nextDouble() * (PASS_MAX - PASS_MIN);
        pass.copy(c).add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r));
        Vector d = pass.clone().subtract(pos).setY(0);
        if (d.lengthSquared() < 1e-6) d = G141Kit.fwd(this);
        d.normalize();
        // 끝점: 스치는 점 너머, 가운데에서 EXIT_R
        exit.copy(pass).add(d.clone().multiply(EXIT_R + 4));
        phase = Phase.CROSS;
        crossAt = age;
    }

    private void steer(Vector to) {
        G141Kit.turnFlat(this, to, TURN);
        Vector f = G141Kit.fwd(this);
        vel.setX(f.getX() * speed());
        vel.setZ(f.getZ() * speed());
    }

    @Override
    public void tick() {
        Vector c = G141Kit.middle();
        if (phase == Phase.CROSS) {
            boolean passed = G141Kit.hdist(pos, pass) < 5 || (pos.clone().subtract(pass).setY(0).dot(exit.clone().subtract(pass).setY(0)) > 0);
            steer(passed ? exit : pass);
            if (inPhase(crossAt) < 60 && inPhase(crossAt) % 20 == 0) dustLine();
            if (passed && (G141Kit.hdist(pos, exit) < 6 || G141Kit.hdist(pos, c) > EXIT_R + 6)) {
                phase = Phase.ORBIT;
                orbitLeft = Math.toRadians(70 + game.random.nextDouble() * 80);
                double ang = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
                Vector f = G141Kit.fwd(this);
                // 지금 도는 쪽(머리가 꺾이기 쉬운 쪽)
                Vector tang = new Vector(-Math.sin(ang), 0, Math.cos(ang));
                orbitDir = f.dot(tang) >= 0 ? 1 : -1;
            }
        } else {
            double ang = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
            double step = orbitDir * 0.3;
            Vector to = c.clone().add(new Vector(Math.cos(ang + step) * ORBIT_R, 0, Math.sin(ang + step) * ORBIT_R));
            steer(to);
            orbitLeft -= speed() / ORBIT_R;
            if (orbitLeft <= 0) cross(c);
        }
        record(pos.getX(), floorAt(pos), pos.getZ(), false);
        placePieces(2);
        segments();
        attacks();
        if (age >= legsAt) {
            legsAt = age + 12;
            sound("legs", Sound.ENTITY_SPIDER_STEP, 0.6f, 64);
            if (age % 48 == 0) game.horizon.shake(0.15, 8);
        }
        if (biteShow >= 0 && age >= biteShow) {
            state("");
            biteShow = -1;
        }
        if (age >= bitAt) {
            bitAt = age + cd(BIT_CD);
            dropBits(BIT_N, -1);
        }
    }

    private int inPhase(int at) {
        return age - at;
    }

    /** 가로지를 줄 위에 흙먼지(앞 6..36 블록). */
    private void dustLine() {
        Vector d = exit.clone().subtract(pos).setY(0);
        if (d.lengthSquared() < 1e-6) return;
        d.normalize();
        for (int i = 6; i <= 36; i += 5) G141Kit.dust(this, pos.clone().add(d.clone().multiply(i)), 1.6f, 30);
    }

    /** 토막과 꼬리를 자취 위에 놓음. */
    private void placePieces(int ticks) {
        Vector p = new Vector(), dir = new Vector();
        for (int i = 0; i < PIECES; i++) {
            Body.Part part = pieces[i];
            if (part == null || i >= alivePieces) continue;
            trail(HEAD_GAP + (3 * i + 1) * SEG, p, dir);
            float yw = dir.lengthSquared() < 1e-9 ? yaw : G141Kit.yawOf(dir);
            part.pose(new Matrix4f().translate((float) (p.getX() - pos.getX()), (float) (p.getY() + SEG_Y - pos.getY()), (float) (p.getZ() - pos.getZ()))
                .rotateY((float) Math.toRadians(-yw)), ticks);
        }
        if (tail != null) {
            trail(HEAD_GAP + (3 * alivePieces) * SEG + 0.2, p, dir);
            float yw = dir.lengthSquared() < 1e-9 ? yaw : G141Kit.yawOf(dir);
            tail.pose(new Matrix4f().translate((float) (p.getX() - pos.getX()), (float) (p.getY() + SEG_Y - pos.getY()), (float) (p.getZ() - pos.getZ()))
                .rotateY((float) Math.toRadians(-yw)), ticks);
        }
    }

    /** 마디 가운데(세계): segPos. */
    private void segments() {
        for (int j = 0; j < SEGS; j++) {
            if (j >= alivePieces * 3) break;
            trail(HEAD_GAP + j * SEG, segPos[j], null);
            segPos[j].setY(segPos[j].getY() + SEG_Y);
        }
    }

    // ------------------------------------------------------------------ 공격

    private void attacks() {
        Vector mouth = G141Kit.at(this, 0, -0.4, 1.6);
        Vector f = G141Kit.fwd(this);
        int segN = alivePieces * 3;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            // 머리 물기
            if (age >= biteAt && G141Kit.hdist(mouth, l) <= BITE_R && Math.abs(l.getY() - (pos.getY() - HOVER)) < 2.5) {
                hit(p, BITE_DMG, "cause_g142");
                knock(p, f.clone().add(new Vector(0, 0, 0)), 1.4, 0.55);
                biteAt = age + cd(BITE_CD);
                state("bite");
                body.look.phase = 0;
                biteShow = age + 10;
                sound("bite", Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.7f, 32);
                mark(p);
                continue;
            }
            // 몸통 다리 찍기
            Integer last = legHit.get(p);
            if (last != null && age - last < LEG_CD) continue;
            for (int j = 0; j < segN; j++) {
                Vector s = segPos[j];
                double dx = l.getX() - s.getX(), dz = l.getZ() - s.getZ();
                if (dx * dx + dz * dz > LEG_R * LEG_R || Math.abs(l.getY() - (s.getY() - SEG_Y)) > 2.2) continue;
                legHit.put(p, age);
                hit(p, LEG_DMG, "cause_g142_legs");
                // 몸 바깥으로(마디 줄에 수직, 사람이 있는 쪽)
                Vector side = new Vector(dx, 0, dz);
                if (side.lengthSquared() < 1e-6) side = G141Kit.left(this);
                knock(p, side.normalize(), 0.9, 0.35);
                break;
            }
        }
        if (target == null || !standing(target)) mark(nearest(400));
    }

    /** 작은 지네 조각 n(마디 j 자리, -1 이면 무작위 마디). */
    private void dropBits(int n, int j) {
        int segN = alivePieces * 3;
        if (segN <= 0) return;
        for (int i = 0; i < n; i++) {
            if (game.mobs.budgetUsed() + 1 > Mobs.BUDGET) return;
            int k = j >= 0 ? Math.min(segN - 1, j) : 2 + game.random.nextInt(Math.max(1, segN - 2));
            Vector at = segPos[Math.min(k, segN - 1)].clone();
            Vector side = G141Kit.left(this).multiply(game.random.nextBoolean() ? 2.0 : -2.0);
            at.add(side);
            at.setY(floorAt(at) + G142Bit.HOVER);
            G142Bit b = new G142Bit(game, sp);
            double ratio = Math.max(0.5, maxHp / Math.max(1, sp.hp));
            b.maxHp = b.hp = G142Bit.HP * Math.min(4, ratio);
            b.noReward = true;
            b.reward = 0;
            b.yaw = yaw;
            game.mobs.place(b, at);
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        Vector h = G141Kit.at(this, 0, 0.1, 0.3);
        sph.add(new double[] {h.getX(), h.getY(), h.getZ(), HEAD_R});
        if (tHead >= 0) {
            int segN = alivePieces * 3;
            for (int j = 0; j < segN; j++) sph.add(new double[] {segPos[j].getX(), segPos[j].getY(), segPos[j].getZ(), SEG_R});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return G141Kit.at(this, 0, 0.1, 0.6);
    }

    /** 맞은 점에 가장 가까운 마디(-1 = 머리). */
    private int which(Vector at) {
        if (at == null) return -1;
        Vector h = G141Kit.at(this, 0, 0.1, 0.3);
        double best = at.distance(h) - HEAD_R;
        int bi = -1;
        int segN = alivePieces * 3;
        for (int j = 0; j < segN; j++) {
            double d = at.distance(segPos[j]) - SEG_R;
            if (d < best) {
                best = d;
                bi = j;
            }
        }
        return bi;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        int j = which(d.at());
        if (j < 0) return super.damage(d);
        if (d.type() == DamageType.EXPLOSION) {
            // 폭발: 반경 안 마디 껍질을 모두 깸
            int segN = alivePieces * 3;
            for (int k = 0; k < segN; k++) if (d.at() != null && d.at().distance(segPos[k]) <= SEG_R + 2.5) crack(k);
            crack(j);
        } else if (!cracked[j]) {
            int[] h = hits[j];
            // 가장 오래된 칸을 지금으로
            int oldest = 0;
            for (int i = 1; i < h.length; i++) if (h[i] < h[oldest]) oldest = i;
            h[oldest] = age;
            int n = 0;
            for (int t : h) if (age - t <= CRACK_WINDOW) n++;
            if (n < CRACK_HITS) {
                if (age - tinkAt >= 3) {
                    tinkAt = age;
                    sound("tink", Sound.BLOCK_ANVIL_LAND, 1.8f, 24);
                }
                return false;
            }
            crack(j);
        }
        segDmg[j] += d.amount();
        boolean r = super.damage(d);
        if (!dead && segDmg[j] >= maxHp * CUT_FRAC) cut(j / 3);
        return r;
    }

    private void crack(int j) {
        if (j < 0 || j >= SEGS || cracked[j]) return;
        cracked[j] = true;
        Body.Part p = pieces[j / 3];
        if (p != null) p.look.on.add("crack" + (j % 3));
        sound("crack", Sound.ENTITY_TURTLE_EGG_CRACK, 0.7f, 32);
    }

    /** 토막 i 에서 끊음: i 와 그 뒤 토막, 꼬리가 떨어져 나가고 남은 체력에서 그만큼 깎임. 남은 토막이 하나 아래면 죽음. */
    private void cut(int i) {
        if (i >= alivePieces) return;
        int removed = alivePieces - i;
        for (int k = i; k < alivePieces; k++) {
            for (int s = 0; s < 3; s++) {
                Vector at = segPos[k * 3 + s];
                if (game.shotFx.room(ShotFx.NORMAL))
                    game.shotFx.bloodHit(new Location(game.world, at.getX(), at.getY(), at.getZ()), new Vector(0, 1, 0), true, ShotFx.BLOOD_PINK);
            }
        }
        dropBits(2, i * 3);
        for (int k = i; k < PIECES; k++) {
            if (pieces[k] != null) {
                pieces[k].remove();
                pieces[k] = null;
            }
        }
        alivePieces = i;
        sound("cut", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.6f, 48);
        if (alivePieces < 1) {
            if (tail != null) {
                tail.remove();
                tail = null;
            }
            die(Damage.of(Math.max(1, hp), DamageType.OTHER));
            return;
        }
        hp = Math.max(1, hp - maxHp * 0.5 * removed / (double) PIECES);
        sphAge = -1;
    }

    @Override
    public String toString() {
        int c = 0;
        for (boolean b : cracked) if (b) c++;
        return super.toString() + " ph=" + phase + " pieces=" + alivePieces + " cracked=" + c;
    }
}
