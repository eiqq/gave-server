package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G155 말뚝에 묶인 거인 게이브(4막, 목표 압박, 보상). 웨이브 시작부터 트럭에서 R 30~36 에 엎드려 누운 11 블록 거인이 말뚝 일곱에 묶여 트럭을
 * 보고 웃는다. 10초마다 일꾼 얼굴 넷({@link G155Worker})이 흙먼지 끝에서 나와 말뚝으로 기어가 매듭을 갉는다(일꾼 하나가 12초 갉으면 하나 뽑힘).
 * 말뚝은 사람 총과 폭발에도 다친다(체력 100, Mobs.Shootable). 뽑힐 때마다 징 소리(반경 128), 포효, 온 떼가 멈춰 환호하고 알림 '남은 말뚝 n'.
 *
 * <ul>
 * <li>뽑힌 수 1~2: 한 팔로 흙덩이를 6초마다 던짐(반경 2 안 16).</li>
 * <li>3~4: 두 팔로 사람 쪽으로 기어 옴(처음 자리에서 4, 8 블록까지), 팔 긁기 앞 8 블록 부채꼴 22.</li>
 * <li>5~6: 윗몸을 일으켜 내려치기(빨간 고리 1초 뒤 반경 4 안 30).</li>
 * <li>7: 일어서서 트럭으로 걸어와 트럭 위에 주저앉음: 반경 6 안 40, 트럭 15%, 20초 동안 트럭 등불을 덮어 상점이 잠김(공용 LAMP).
 *   그 뒤 트럭 둘레를 기어 다니며 내려치기 30.</li>
 * <li>묶인 채(말뚝이 남은 채) 잡으면 남은 말뚝마다 팀 전원 15원 x 감독 보상 배율.</li>
 * <li>얼굴(땅에 안 닿은 위쪽 볼)이 약점 x2(묶여 있는 동안).</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g155.py): 모델 gabe/g155(shrink 0.18), 상태 "", throw, crawl, rise(누움, 바닥 위 1.0), stand(바닥 위 6.0),
 * sit(엉덩이 = 몸 가운데 - 1), 부위 s0..s6(뽑힌 말뚝). 일꾼 gabe/g155_worker, 흙덩이 gabe/g155_clod. 소리 stake(128), roar(96), throw(32),
 * slam(64), cheer(96), gnaw(16). 글귀 cause_g155, cause_g155_sit, cause_g155_worker, notice_g155_free, notice_g155_left_0..6.
 *
 * <p>도감과 다른 점: 아랫몸, 팔을 부품으로 나누지 않고 상태(손으로 짠 자세)로 구웠다(디스플레이 1). 말뚝은 몸 모델에 붙어 있어 기어 오면 함께
 * 끌려온다(밧줄이 몸을 따라감). 트럭 상점 잠금은 G14 갉기 대신 공용 등불 덮기(LAMP 8 = 상점 잠김). 일꾼 보상은 공통 규칙대로 0.
 */
public final class G155 extends Gabe {

    static final double HOVER = 1.0, STAND_HOVER = 6.0, SIT_UP = 1.0;
    /** 말뚝 자리(몸 틀: 옆 x, 앞 z), 팩 STAKES 와 같게. */
    static final double[][] STAKES = {{3.3, 3.2}, {-3.3, 3.0}, {3.5, 0.2}, {-3.5, -0.2}, {3.2, -2.8}, {-3.2, -3.0}, {0.0, -7.3}};
    static final double STAKE_HP = 100;
    static final int GNAW_T = 240;
    static final int WORKER_CD = 200, WORKERS = 4, WORKER_MAX = 8;
    static final int THROW_CD = 120, THROW_LEN = 20, THROW_AT = 12;
    static final double CLOD_R = 2, CLOD_DMG = 16;
    static final double SWIPE_R = 8, SWIPE_COS = 0.5, SWIPE_DMG = 22, CRAWL_SPEED = 0.05;
    static final int SWIPE_CD = 70;
    static final double SLAM_R = 4, SLAM_DMG = 30;
    static final int SLAM_WARN = 20, SLAM_CD = 80;
    static final double SIT_R = 6, SIT_DMG = 40, SIT_TRUCK = 0.15, WALK_SPEED = 0.09;
    static final int LOCK_T = 400;
    static final int BONUS_PER_STAKE = 15;

    enum Phase { BOUND, FREE, SIT, ROAM }

    private Phase phase = Phase.BOUND;
    private int phaseAt;
    private final boolean[] pulled = new boolean[7];
    private final double[] stakeHp = new double[7];
    private final int[] gnaw = new int[7];
    private final Stake[] stakes = new Stake[7];
    private int pulledN;
    private final Vector origin = new Vector();
    private int workerAt, throwAt, throwStart = -1, swipeAt, slamAt, slamStart = -1, lockUntil = -1;
    private final Vector slamC = new Vector();
    private Player throwAtP;
    private final List<G155Worker> workers = new ArrayList<>();
    private final G141Kit.Lobs lobs = new G141Kit.Lobs("gabe/g155_clod", 3);
    private final List<double[]> sph = new ArrayList<>(10);
    private int sphAge = -1;
    private double hoverTo = HOVER;
    private boolean lampOn;

    public G155(Game game, Species sp) {
        super(game, sp);
        radius = 1.8;
        hover = HOVER;
        baseSpeed = CRAWL_SPEED;
        big = true;
        noPush = true;
        noMutate = true;
        noBite = true;
        ghost = true;
        ragKick = 0.2;
        hpPow = Director.Tune.HP_POW_BIG;
        for (int i = 0; i < 7; i++) stakeHp[i] = STAKE_HP;
    }

    @Override
    protected void build() {
        // 높이는 자세마다(닻 엔티티 = 몸 가운데 - 높이/2: 누운 몸에 큰 높이를 주면 닻이 땅 밑 깊이 놓여 안 그려짐)
        body = Body.of(this, "gabe/g155", 17f, 4f);
        body.viewRange(3f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Vector c = G141Kit.middle();
        // 트럭에서 R 30~36(감독 자리가 밖이면 그 방향으로 당김)
        Vector d = pos.clone().subtract(c).setY(0);
        if (d.lengthSquared() < 1e-6) d = new Vector(1, 0, 0);
        double r = d.length();
        if (r < 30 || r > 36) {
            Vector at = c.clone().add(d.normalize().multiply(30 + game.random.nextDouble() * 6));
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
            game.mobs.relocate(this, at);
        }
        G141Kit.faceFlat(this, c);
        body.pose(0);
        origin.copy(pos);
        for (int i = 0; i < 7; i++) {
            stakes[i] = new Stake(i);
            game.mobs.addShootable(stakes[i]);
        }
        workerAt = age + 60;
        throwAt = age + 40;
        mark(nearest(400));
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 말뚝

    /** 말뚝 i 의 세계 자리(가운데, 바닥 위 1). */
    Vector stakeAt(int i) {
        Vector v = G141Kit.at(this, STAKES[i][0], 0, STAKES[i][1]);
        v.setY(game.mobs.flow.floor(v.getX(), v.getZ()) + 1.0);
        return v;
    }

    boolean pulled(int i) {
        return pulled[i];
    }

    int left() {
        return 7 - pulledN;
    }

    /** 남은 말뚝 가운데 하나(없으면 -1): 일꾼이 덜 몰린 것. */
    int pickStake() {
        int best = -1, bn = Integer.MAX_VALUE;
        for (int i = 0; i < 7; i++) {
            if (pulled[i]) continue;
            int n = 0;
            for (G155Worker w : workers) if (!w.dead && w.stake == i) n++;
            n = n * 10 + game.random.nextInt(10);
            if (n < bn) {
                bn = n;
                best = i;
            }
        }
        return best;
    }

    /** 일꾼이 한 틱 갉음. */
    void gnaw(int i) {
        if (i < 0 || i >= 7 || pulled[i] || dead) return;
        if (++gnaw[i] >= GNAW_T) pull(i);
    }

    private void pull(int i) {
        if (pulled[i] || dead) return;
        pulled[i] = true;
        pulledN++;
        body.look.on.add("s" + i);
        game.mobs.removeShootable(stakes[i]);
        Vector at = stakeAt(i);
        sound("stake", Sound.BLOCK_BELL_USE, 0.5f, 128);
        sound("roar", Sound.ENTITY_RAVAGER_ROAR, 0.55f, 96);
        if (game.shotFx.room(ShotFx.NORMAL)) G141Kit.dust(this, at, 2.2f, 24);
        G141Kit.ring(this, at.clone().setY(at.getY() - 1), 2.5, 14);
        game.hud.alert("notice_g155_left_" + left(), Math.min(5, pulledN), 100);
        // 온 떼가 멈춰 환호
        sound("cheer", Sound.ENTITY_EVOKER_CELEBRATE, 0.8f, 96);
        for (Gabe g : game.mobs.near(pos, 48, g -> g != this && !g.dead && !g.sp.is(Role.BOSS) && !(g instanceof G155Worker))) game.mobs.lure(g, g.pos.clone(), 24);
        game.horizon.shake(0.3, 10);
        if (pulledN >= 7) free();
    }

    /** 말뚝 하나: 사람 총과 폭발에 다침(체력 100). 0 이 되면 뽑힘. */
    private final class Stake implements Mobs.Shootable {
        final int i;

        Stake(int i) {
            this.i = i;
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (pulled[i] || dead) return Double.POSITIVE_INFINITY;
            Vector c = stakeAt(i);
            double best = Double.POSITIVE_INFINITY;
            for (double dy : new double[] {-0.4, 0.4, 1.15}) {
                double cx = c.getX() - o.getX(), cy = c.getY() + dy - o.getY(), cz = c.getZ() - o.getZ();
                double t = cx * dir.getX() + cy * dir.getY() + cz * dir.getZ();
                if (t < 0 || t > max) continue;
                double px = cx - dir.getX() * t, py = cy - dir.getY() * t, pz = cz - dir.getZ() * t;
                double r = dy > 1 ? 0.38 : 0.3;
                double m2 = px * px + py * py + pz * pz;
                if (m2 > r * r) continue;
                best = Math.min(best, t - Math.sqrt(r * r - m2));
            }
            return best;
        }

        @Override
        public boolean shot(Damage d) {
            if (pulled[i] || dead) return false;
            stakeHp[i] -= d.amount();
            if (stakeHp[i] <= 0) pull(i);
            return false;
        }

        @Override
        public Vector center() {
            return stakeAt(i);
        }

        @Override
        public boolean gone() {
            return pulled[i] || dead || removed;
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (target == null || !standing(target)) mark(nearest(400));
        // 땅 몹 높이를 천천히
        if (Math.abs(hover - hoverTo) > 1e-3) hover += Math.signum(hoverTo - hover) * Math.min(Math.abs(hoverTo - hover), 0.12);
        lobs.tick(this, this::clodLand);
        switch (phase) {
            case BOUND -> bound();
            case FREE -> walkFree();
            case SIT -> sit();
            case ROAM -> roam();
        }
        workers.removeIf(w -> w.dead || w.removed);
        if (phase == Phase.BOUND && age >= workerAt) {
            workerAt = age + cd(WORKER_CD);
            spawnWorkers();
        }
        blasts();
        if (lockUntil >= 0 && age >= lockUntil) unlock();
    }

    /** 폭발이 말뚝 곁(2.5)에서 나면 말뚝도 다침. */
    private void blasts() {
        if (phase != Phase.BOUND) return;
        for (int i = 0; i < 7; i++) {
            if (pulled[i]) continue;
            Vector at = stakeAt(i);
            if (game.mobs.blastsNear(at, 2.5, 1) > 0) {
                stakeHp[i] -= 45;
                if (stakeHp[i] <= 0) pull(i);
            }
        }
    }

    private void spawnWorkers() {
        if (workers.size() >= WORKER_MAX) return;
        Vector c = G141Kit.middle();
        double base = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
        for (int i = 0; i < WORKERS && workers.size() < WORKER_MAX; i++) {
            if (game.mobs.budgetUsed() + 1 > Mobs.BUDGET) return;
            double a = base + (game.random.nextDouble() - 0.5) * 1.4, r = 58 + game.random.nextDouble() * 6;
            Vector at = c.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r));
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + G155Worker.HOVER);
            G155Worker w = new G155Worker(game, sp, this);
            double ratio = Math.max(0.5, maxHp / Math.max(1, sp.hp));
            w.maxHp = w.hp = G155Worker.HP * Math.min(3, ratio);
            w.noReward = true;
            w.reward = 0;
            if (game.mobs.place(w, at) != null) workers.add(w);
        }
    }

    /** 묶인 동안: 뽑힌 수에 따라 가만히, 던지기, 기어 오기와 긁기, 일으켜 내려치기. */
    private void bound() {
        Player p = target;
        int n = pulledN;
        if (throwStart >= 0) {
            hold();
            throwing();
            return;
        }
        if (slamStart >= 0) {
            hold();
            slamming("rise");
            return;
        }
        if (n == 0) {
            hold();
            state("");
            G141Kit.turnFlat(this, G141Kit.middle(), 0.5f);
            return;
        }
        if (n <= 2) {
            hold();
            state("");
            if (age >= throwAt && p != null && G141Kit.hdist(pos, p.getLocation()) < 44) {
                G141Kit.turnFlat(this, p.getLocation().toVector(), 30f);
                throwAtP = p;
                throwStart = age;
                state("throw");
                body.look.phase = 0;
                sound("throw", Sound.ENTITY_RAVAGER_ATTACK, 0.6f, 32);
            }
            return;
        }
        // 3 이상: 기어 옴(처음 자리에서 줄 길이만큼)
        double leash = n <= 4 ? 4 * (n - 2) : 6 + 4 * (n - 4);
        state(n <= 4 ? "crawl" : "crawl");
        if (p != null) {
            G141Kit.turnFlat(this, p.getLocation().toVector(), 1.5f);
            Vector to = p.getLocation().toVector();
            Vector next = pos.clone().add(G141Kit.fwd(this).multiply(CRAWL_SPEED));
            if (G141Kit.hdist(next, origin) <= leash && G141Kit.hdist(pos, to) > 5) {
                Vector f = G141Kit.fwd(this);
                vel.setX(f.getX() * speed());
                vel.setZ(f.getZ() * speed());
            } else hold();
            if (n <= 4) swipe(p);
            else startSlam(p);
        } else hold();
    }

    private void throwing() {
        int t = age - throwStart;
        if (t == THROW_AT && throwAtP != null && throwAtP.isOnline()) {
            Location l = throwAtP.getLocation();
            Vector from = G141Kit.at(this, -2.4, 3.5, 0.5);
            Vector to = new Vector(l.getX(), game.mobs.flow.floor(l.getX(), l.getZ()) + 0.3, l.getZ());
            double d = G141Kit.hdist(from, to);
            if (lobs.toss(this, from, to, 18 + (int) (d * 0.5), 5 + d * 0.15)) G141Kit.warn(this, to, CLOD_R, 18 + (int) (d * 0.5));
        }
        if (t >= THROW_LEN) {
            throwStart = -1;
            throwAt = age + cd(THROW_CD);
            state("");
        }
    }

    private void clodLand(Vector at) {
        Location l = new Location(game.world, at.getX(), at.getY(), at.getZ());
        if (!game.director.silent()) kr.gave.Props.soundAround(l, "sfx.stomp", Sound.ENTITY_RAVAGER_STEP, 0.8f, 1.1f, 0.6f, 32, -1);
        G141Kit.ring(this, at, CLOD_R, 12);
        G141Kit.dust(this, at, 1.8f, 16);
        for (Player q : G141Kit.within(this, at, CLOD_R + 0.3, 2.5)) hit(q, CLOD_DMG, "cause_g155");
    }

    private void swipe(Player p) {
        if (age < swipeAt) return;
        Vector f = G141Kit.fwd(this);
        Vector shoulder = G141Kit.at(this, 0, 0, 2.5);
        boolean any = false;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            Vector to = new Vector(l.getX() - shoulder.getX(), 0, l.getZ() - shoulder.getZ());
            double d = to.length();
            if (d > SWIPE_R || d < 1e-6 || to.clone().multiply(1 / d).dot(f) < SWIPE_COS) continue;
            any = true;
        }
        if (!any) return;
        swipeAt = age + cd(SWIPE_CD);
        sound("slam", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 64);
        body.squash(1.08f, 0.94f, 1.1f, 3);
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            Vector to = new Vector(l.getX() - shoulder.getX(), 0, l.getZ() - shoulder.getZ());
            double d = to.length();
            if (d > SWIPE_R || d < 1e-6 || to.clone().multiply(1 / d).dot(f) < SWIPE_COS) continue;
            hit(q, SWIPE_DMG, "cause_g155");
            knock(q, to, 1.0, 0.4);
        }
        G141Kit.ring(this, G141Kit.at(this, 0, -HOVER, 6).setY(game.mobs.flow.floor(pos.getX(), pos.getZ())), 3, 10);
    }

    private void startSlam(Player p) {
        if (age < slamAt || slamStart >= 0 || p == null) return;
        Vector front = G141Kit.at(this, 0, 0, 6.5);
        if (G141Kit.hdist(front, p.getLocation()) > SLAM_R + 4) return;
        slamC.copy(front);
        slamC.setY(game.mobs.flow.floor(front.getX(), front.getZ()));
        G141Kit.warn(this, slamC, SLAM_R, SLAM_WARN + 2);
        slamStart = age;
        state("rise");
        sound("roar", Sound.ENTITY_RAVAGER_ROAR, 0.6f, 96);
    }

    /** 내려치기: 빨간 고리 SLAM_WARN 틱 뒤 반경 SLAM_R 안 SLAM_DMG. after = 끝난 뒤 상태. */
    private void slamming(String raise) {
        int t = age - slamStart;
        if (t < SLAM_WARN) state(raise);
        if (t == SLAM_WARN) {
            state("crawl");
            body.squash(1.1f, 0.85f, 1.05f, 2);
            G141Kit.stomp(this, slamC, 64, 10, 1f);
            G141Kit.ring(this, slamC, SLAM_R, 14);
            sound("slam", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 64);
            game.horizon.shake(0.45, 10);
            for (Player q : G141Kit.within(this, slamC, SLAM_R, 3.5)) {
                hit(q, SLAM_DMG, "cause_g155");
                Location l = q.getLocation();
                knock(q, new Vector(l.getX() - slamC.getX(), 0, l.getZ() - slamC.getZ()), 1.0, 0.6);
            }
            Truck tr = game.trip == null ? null : game.trip.truck();
            if (tr != null && Truck.dist(slamC.getX(), slamC.getZ()) <= SLAM_R) tr.hitFrac(0.03, this, "cause_g155");
        }
        if (t >= SLAM_WARN + 8) {
            body.squash(1, 1, 1, 4);
            slamStart = -1;
            slamAt = age + cd(SLAM_CD);
        }
    }

    // ------------------------------------------------------------------ 풀려남

    private void free() {
        phase(Phase.FREE);
        game.hud.alert("notice_g155_free", 5, 100);
        for (G155Worker w : workers) w.stake = -1;
        sound("roar", Sound.ENTITY_RAVAGER_ROAR, 0.5f, 96);
        game.horizon.shake(0.6, 20);
    }

    /** 일어서서 트럭(한가운데)으로 걸어 옴. */
    private void walkFree() {
        if (inPhase() < 30) {
            hold();
            state("rise");
            return;
        }
        state("stand");
        if (hoverTo != STAND_HOVER) body.size(17f, 13f);
        hoverTo = STAND_HOVER;
        body.look.rate = 1.2;
        Vector c = G141Kit.middle();
        G141Kit.turnFlat(this, c, 3f);
        Vector f = G141Kit.fwd(this);
        vel.setX(f.getX() * WALK_SPEED * speedMulOnly());
        vel.setZ(f.getZ() * WALK_SPEED * speedMulOnly());
        if (age % 14 == 0) {
            Vector foot = G141Kit.at(this, (age / 14) % 2 == 0 ? 1 : -1, 0, 0.5);
            foot.setY(game.mobs.flow.floor(foot.getX(), foot.getZ()));
            G141Kit.stomp(this, foot, 64, 6, 0.9f);
        }
        double d = Truck.live() ? Truck.dist(pos.getX(), pos.getZ()) : G141Kit.hdist(pos, c);
        if (d <= 1.0) sitDown(c);
    }

    private double speedMulOnly() {
        return speed() / Math.max(1e-6, baseSpeed);
    }

    /** 트럭 위에 주저앉음: 반경 6 안 40, 트럭 15%, 20초 동안 등불 덮음(상점 잠김). */
    private void sitDown(Vector c) {
        hold();
        Vector at = c.clone();
        double top = Truck.live() ? Truck.HEIGHT : 0;
        at.setY(game.mobs.flow.floor(c.getX(), c.getZ()));
        hoverTo = top + SIT_UP;
        hover = hoverTo;
        game.mobs.relocate(this, at.clone().setY(at.getY() + hover));
        state("sit");
        body.size(17f, 10f);
        G141Kit.stomp(this, at, 96, 14, 1f);
        G141Kit.ring(this, at, SIT_R, 16);
        sound("slam", Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 64);
        game.horizon.shake(0.8, 20);
        for (Player q : G141Kit.within(this, at, SIT_R, 5)) {
            hit(q, SIT_DMG, "cause_g155_sit");
            Location l = q.getLocation();
            knock(q, new Vector(l.getX() - at.getX(), 0, l.getZ() - at.getZ()), 1.4, 0.7);
        }
        Truck tr = game.trip == null ? null : game.trip.truck();
        if (tr != null) {
            tr.hitFrac(SIT_TRUCK, this, "cause_g155_sit");
            tr.lampCover(this, Truck.LAMP_COVER_FULL);
            lampOn = true;
        }
        lockUntil = age + LOCK_T;
        phase(Phase.SIT);
    }

    private void unlock() {
        lockUntil = -1;
        if (!lampOn) return;
        lampOn = false;
        Truck tr = Truck.get();
        if (tr != null) tr.lampCover(this, 0);
    }

    private void sit() {
        hold();
        if (inPhase() >= 100) {
            hoverTo = HOVER;
            state("crawl");
            body.size(17f, 4f);
            Vector c = G141Kit.middle();
            Vector out = pos.clone().subtract(c).setY(0);
            if (out.lengthSquared() < 1e-6) out = G141Kit.fwd(this);
            Vector at = c.clone().add(out.normalize().multiply(7));
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
            game.mobs.relocate(this, at);
            phase(Phase.ROAM);
        }
    }

    /** 트럭 둘레를 기어 다니며 내려치기. */
    private void roam() {
        Player p = target;
        if (slamStart >= 0) {
            hold();
            slamming("rise");
            return;
        }
        state("crawl");
        Vector c = G141Kit.middle();
        double ang = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX()) + 0.15;
        Vector to = p != null && G141Kit.hdist(c, p.getLocation()) < 20 ? p.getLocation().toVector()
            : c.clone().add(new Vector(Math.cos(ang) * 7, 0, Math.sin(ang) * 7));
        G141Kit.turnFlat(this, to, 2f);
        if (G141Kit.hdist(pos, to) > 5) {
            Vector f = G141Kit.fwd(this);
            vel.setX(f.getX() * 0.07 * speedMulOnly());
            vel.setZ(f.getZ() * 0.07 * speedMulOnly());
        } else hold();
        startSlam(p);
    }

    // ------------------------------------------------------------------ 맞음

    private boolean upright() {
        return state.equals("stand") || state.equals("sit");
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        double[][] s = upright() ? (state.equals("sit") ? new double[][] {{0, 1.2, 0, 2.1}, {0, 5.0, 0.4, 1.6}, {0, -1.5, 3.2, 1.2}}
            : new double[][] {{0, 1.0, 0, 2.1}, {0, 4.8, 0.3, 1.6}, {0, -3.8, 0.2, 1.4}})
            : new double[][] {{0.2, 0.6, 4.0, 1.6}, {0, -0.2, 1.4, 1.75}, {0, -0.2, -1.2, 1.75}, {0, -0.35, -3.2, 1.4}, {0.95, -0.5, -5.5, 0.7},
                {-0.95, -0.5, -5.5, 0.7}};
        for (double[] q : s) {
            Vector w = G141Kit.at(this, q[0], q[1], q[2]);
            sph.add(new double[] {w.getX(), w.getY(), w.getZ(), q[3]});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return upright() ? G141Kit.at(this, 0, 4.8, 0.3) : G141Kit.at(this, 0.2, 0.6, 4.0);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        // 묶인 동안 얼굴 위쪽 볼이 약점 x2
        if (phase == Phase.BOUND && d.at() != null) {
            Vector h = G141Kit.at(this, 0.2, 0.6, 4.0);
            if (d.at().distance(h) <= 1.9 && d.at().getY() >= h.getY() - 0.2) return super.damage(d.times(2));
        }
        return super.damage(d);
    }

    private void cleanup() {
        lobs.clear();
        for (Stake s : stakes) if (s != null) game.mobs.removeShootable(s);
        unlock();
    }

    @Override
    public void die(Damage d) {
        // 묶인 채(남은 말뚝이 있는 채) 사람이 잡으면 남은 말뚝마다 팀 보상
        if (!noReward && left() > 0 && phase == Phase.BOUND) {
            int each = (int) Math.round(left() * BONUS_PER_STAKE * game.director.rewardMul());
            if (each > 0) game.players.teamMoney(each);
        }
        cleanup();
        if (!upright()) state("");
        super.die(d);
    }

    @Override
    public void remove() {
        cleanup();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " pulled=" + pulledN + " workers=" + workers.size() + (lampOn ? " lamp" : "");
    }
}
