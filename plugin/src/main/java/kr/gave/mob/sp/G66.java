package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G66 바구니 진 덩치 게이브(1막, 원거리, 지원, 목표 압박). 키 5.5 블록의 굽은 덩치가 걸음마다 땅울림을 내며 걸어와 20~26블록에서 멈춰 서고,
 * 3초마다 등 바구니의 웃는 새끼 둘({@link G66Kid})을 사람 무리 머리 너머 한가운데로 던진다(셋에 하나는 트럭 지붕). 8블록 안에 사람이 오면
 * 몽둥이를 휘두르며 다가간다(앞 3블록 부채꼴 14, 4블록 밀침). 약점은 바구니(x2): 부수면 남은 새끼가 발밑에 쏟아지고 더는 못 던진다.
 *
 * <p>팩 계약(tools/gabe/sp/g66.py): 모델 gabe/g66(shrink 0.37, 블록 좌표 그대로), 상태 ""(걷기 24틱), "stand", "throw"(20틱, 자세 8),
 * "swing"(12틱, 자세 4), 부위 "empty". 새끼 gabe/g66_kid. 소리 toss, swing, crack, kid. 글귀 cause_g66, cause_g66_kid.
 *
 * <p>도감과 다른 점: 바구니, 몽둥이 팔, 살 팔을 부품 디스플레이로 나누지 않고 닻 하나에 상태(손으로 짠 자세)로 구웠다(디스플레이 1, 패킷 적음).
 * 걸음 화면 흔들림은 반경 6 안 사람에게만 시선 끄덕(Guns.kick: horizon.shake 는 판 전체라 안 씀).
 */
public final class G66 extends Gabe {

    static final double HOVER = 2.75, SPEED = 0.075;
    /** 이 거리 안에서 멈춰 서서 던진다, 이 거리 안이면 몽둥이로 다가간다(벗어나는 거리). */
    static final double THROW_R = 25, SMASH_R = 8, SMASH_OUT = 12;
    static final int THROW_CD = 60, THROW_LEN = 20, THROW_RELEASE = 15, KIDS = 10, PER_THROW = 2;
    /** 몽둥이: 몸 가운데에서 닿는 거리, 반각(도), 피해, 밀침. */
    static final double CLUB_R = 4.6, CLUB_HALF = 75, CLUB_DMG = 5, CLUB_PUSH = 0.9, CLUB_UP = 0.4;
    static final int SWING_LEN = 12, SWING_HIT = 6, SWING_CD = 36;
    /** 바구니 체력 몫(최대 체력의), 바구니 맞으면 배율. */
    static final double BASKET_FRAC = 0.3, BASKET_MUL = 2.0;
    /** 걸음 주기(""상태 24틱에 두 걸음), 걸음 소리 반경, 화면 끄덕 반경. */
    static final int STEP = 12;
    static final double STEP_R = 64, SHAKE_R = 6;
    /** 맞는 구(몸 틀 x, y, z, r). 마지막이 바구니. */
    static final double[][] SPH = {{0, 0.25, 0.25, 1.55}, {0, -1.0, 0.1, 1.15}, {0, 1.95, 1.55, 0.95}, {0.72, -2.05, 0, 0.55},
        {-0.72, -2.05, 0, 0.55}, {0, 2.25, -1.2, 1.4}};
    /** 이 높이(몸 가운데에서) 위로 맞은 점은 바구니(앞에서 보면 머리 위로 솟은 바구니 테와 새끼들). */
    static final double BASKET_Y = 2.6;
    static final int BASKET = 5;

    enum Phase { WALK, STAND, THROW, SMASH, SWING }

    private Phase phase = Phase.WALK;
    private int phaseAt, readyAt, swingAt, nextStep, tosses;
    private int kids = KIDS;
    private double basketHp = -1;
    private boolean empty;
    private Player prey;
    private final List<G66Kid> flying = new ArrayList<>();
    private final List<double[]> sph = new ArrayList<>(SPH.length);
    private int sphAge = -1;

    public G66(Game game, Species sp) {
        super(game, sp);
        radius = 1.55;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        noMutate = true;
        ragKick = 0.35;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g66", 7f, 6.2f);
        body.viewRange(2.6f);
    }

    @Override
    protected void spawned() {
        nextStep = age + STEP;
        readyAt = age + 20;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (basketHp < 0) basketHp = maxHp * BASKET_FRAC;
        Player p = nearest(400);
        if (p != prey) {
            prey = p;
            mark(p != null && dist(p) <= THROW_R + 6 ? p : null);
        }
        double d = p == null ? 1e9 : hdist(p);
        switch (phase) {
            case WALK -> walk(p, d);
            case STAND -> stand(p, d);
            case THROW -> throwing(p);
            case SMASH -> smash(p, d);
            case SWING -> swing(p);
        }
    }

    private double hdist(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    private void walk(Player p, double d) {
        state("");
        follow(speed());
        body.look.freeze = false;
        body.look.rate = Math.max(0.5, Math.min(2.0, speed() / SPEED));
        if (p != null) G66Giant.faceFlat(this, chest(p));
        steps();
        if (p == null) return;
        if (d <= SMASH_R) phase(Phase.SMASH);
        else if (d <= THROW_R && canThrow()) {
            hold();
            phase(Phase.STAND);
        }
    }

    private boolean canThrow() {
        return kids > 0 && !empty;
    }

    private void steps() {
        if (age < nextStep) return;
        nextStep = age + Math.max(4, (int) Math.round(STEP / Math.max(0.5, body.look.rate)));
        int side = (age / STEP) % 2 == 0 ? 1 : -1;
        Vector foot = G66Giant.at(this, side * 0.75, -HOVER, 0.4);
        foot.setY(floor());
        G66Giant.stomp(this, foot, STEP_R, SHAKE_R, 0.9f);
    }

    /** 멈춰 서서 무리를 봄, 쿨다운이 지나면 던짐. */
    private void stand(Player p, double d) {
        hold();
        state("stand");
        if (p == null || d > THROW_R + 4 || !canThrow()) {
            phase(Phase.WALK);
            return;
        }
        if (d <= SMASH_R) {
            phase(Phase.SMASH);
            return;
        }
        turnTo(crowd(p), 4f);
        if (age >= readyAt) {
            G66Giant.gesture(this, "throw", THROW_LEN, 8);
            sound("toss", Sound.ENTITY_RAVAGER_ATTACK, 0.6f, 40);
            phase(Phase.THROW);
        }
    }

    /** 던지기 몸짓: 팔이 바구니에 들어갔다 머리 위로 내던짐, THROW_RELEASE 틱째에 새끼 둘. */
    private void throwing(Player p) {
        hold();
        if (p != null) turnTo(crowd(p), 3f);
        int t = inPhase();
        if (t == THROW_RELEASE && canThrow()) release(p);
        if (t >= THROW_LEN) {
            readyAt = age + cd(THROW_CD);
            state("stand");
            phase(Phase.STAND);
        }
    }

    /** 사람 무리 한가운데(이 사람 곁 12블록 안 서 있는 사람들의 가운데). */
    private Vector crowd(Player p) {
        Vector c = new Vector();
        int n = 0;
        Location pl = p.getLocation();
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (l.distanceSquared(pl) > 144) continue;
            c.add(l.toVector());
            n++;
        }
        if (n == 0) return pl.toVector();
        return c.multiply(1.0 / n);
    }

    private void release(Player p) {
        if (p == null) return;
        Vector c = crowd(p);
        tosses++;
        Truck truck = game.trip == null ? null : game.trip.truck();
        boolean roof = truck != null && Truck.live() && tosses % 3 == 0;
        Vector from = G66Giant.at(this, 1.3, 3.0, 0.4);
        int n = Math.min(PER_THROW, kids);
        for (int i = 0; i < n; i++) {
            Vector to;
            if (roof) {
                Vector tc = Truck.center();
                to = new Vector(tc.getX() + (game.random.nextDouble() - 0.5) * 1.6, tc.getY() + Truck.HEIGHT + 0.45,
                    tc.getZ() + (game.random.nextDouble() - 0.5) * 3.5);
            } else {
                double a = game.random.nextDouble() * Math.PI * 2, r = 0.8 + game.random.nextDouble() * 2.2;
                to = c.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r));
                to.setY(game.mobs.flow.floor(to.getX(), to.getZ()) + G66Kid.HOVER);
            }
            if (spawnKid(from, to, roof, true)) kids--;
        }
        if (kids <= 0) body.look.on.add("empty");
    }

    /** 새끼 하나: from 에서 to 로 날아감(fly), 아니면 to 에 바로 섬. 예산이 차면 false. */
    private boolean spawnKid(Vector from, Vector to, boolean roof, boolean fly) {
        if (game.mobs.budgetUsed() + 1 > kr.gave.mob.Mobs.BUDGET) return false;
        G66Kid k = new G66Kid(game, sp, this, from, to, roof, fly);
        double ratio = Math.max(0.5, maxHp / Math.max(1, sp.hp));
        k.maxHp = k.hp = G66Kid.HP * ratio;
        k.noReward = true;
        k.reward = 0;
        k.yaw = yaw;
        if (game.mobs.place(k, fly ? from : to) == null) return false;
        flying.removeIf(x -> x.dead);
        flying.add(k);
        return true;
    }

    /** 가까이 온 사람: 몽둥이를 들고 다가가 휘두름. */
    private void smash(Player p, double d) {
        if (p == null || d > SMASH_OUT) {
            phase(canThrow() && p != null && d <= THROW_R ? Phase.STAND : Phase.WALK);
            return;
        }
        state("");
        body.look.freeze = false;
        body.look.rate = 1.2;
        G66Giant.faceFlat(this, chest(p));
        if (d > CLUB_R - 1.2) {
            seek(p.getLocation().toVector(), speed() * 1.3);
            steps();
        } else hold();
        if (d <= CLUB_R && age >= swingAt) {
            hold();
            G66Giant.gesture(this, "swing", SWING_LEN, 4);
            sound("swing", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 24);
            phase(Phase.SWING);
        }
    }

    /** 몽둥이 휘두르기: 오른쪽 뒤에서 앞을 가로질러 왼쪽으로, SWING_HIT 틱째에 앞 부채꼴. */
    private void swing(Player p) {
        hold();
        int t = inPhase();
        if (t == SWING_HIT) {
            Vector f = G66Giant.fwd(this);
            double cos = Math.cos(Math.toRadians(CLUB_HALF));
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                Vector to = new Vector(l.getX() - pos.getX(), 0, l.getZ() - pos.getZ());
                double h = to.length();
                if (h > CLUB_R || h < 1e-6 || Math.abs(l.getY() - (pos.getY() - HOVER)) > 3.5) continue;
                if (to.clone().multiply(1 / h).dot(f) < cos) continue;
                hit(q, CLUB_DMG, "cause_g66");
                knock(q, to, CLUB_PUSH, CLUB_UP);
            }
            Vector c = pos.clone().add(f.multiply(2.6));
            c.setY(floor());
            G66Giant.ring(this, c, 2.2, 10);
        }
        if (t >= SWING_LEN) {
            swingAt = age + cd(SWING_CD);
            state("");
            phase(Phase.SMASH);
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        for (int i = 0; i < SPH.length; i++) {
            if (i == BASKET && empty) continue;
            double[] s = SPH[i];
            Vector w = G66Giant.at(this, s[0], s[1], s[2]);
            sph.add(new double[] {w.getX(), w.getY(), w.getZ(), s[3]});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return G66Giant.at(this, 0, 1.95, 1.55);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!empty && d.at() != null) {
            // 맞은 점이 바구니 구 표면에 가장 가까우면 바구니(x2)
            Vector b = G66Giant.at(this, SPH[BASKET][0], SPH[BASKET][1], SPH[BASKET][2]);
            double best = d.at().distance(b) - SPH[BASKET][3];
            boolean basket = true;
            if (d.at().getY() - pos.getY() < BASKET_Y) for (int i = 0; i < BASKET; i++) {
                Vector w = G66Giant.at(this, SPH[i][0], SPH[i][1], SPH[i][2]);
                if (d.at().distance(w) - SPH[i][3] < best - 0.05) {
                    basket = false;
                    break;
                }
            }
            if (basket) {
                Damage dd = d.times(BASKET_MUL);
                if (basketHp < 0) basketHp = maxHp * BASKET_FRAC;
                basketHp -= dd.amount();
                if (basketHp <= 0) breakBasket();
                return super.damage(dd);
            }
        }
        return super.damage(d);
    }

    /** 바구니 부서짐: 남은 새끼가 발밑에 쏟아지고 더는 못 던짐. */
    private void breakBasket() {
        if (empty) return;
        empty = true;
        body.look.on.add("empty");
        sound("crack", Sound.BLOCK_WOOD_BREAK, 0.6f, 32);
        int n = Math.min(kids, 5);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / Math.max(1, n) + game.random.nextDouble();
            Vector to = G66Giant.at(this, Math.cos(a) * 2.2, 0, -1.2 + Math.sin(a) * 2.2);
            to.setY(game.mobs.flow.floor(to.getX(), to.getZ()) + G66Kid.HOVER);
            spawnKid(G66Giant.at(this, 0, 2.2, -1.2), to, false, true);
        }
        kids = 0;
        if (phase == Phase.THROW || phase == Phase.STAND) {
            state("");
            phase(Phase.WALK);
        }
    }

    @Override
    public void die(Damage d) {
        // 날고 있는 새끼는 그대로 떨어져 산다(사람 쪽에 건 것이 없음)
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " kids=" + kids + " basket=" + Math.round(basketHp) + (empty ? " empty" : "");
    }

    /** 시험, 자식이 읽음. */
    boolean truckOn() {
        return game.trip != null && game.trip.truck() != null;
    }

    static void kidSound(Gabe g) {
        if (!g.game.director.silent())
            Props.soundAround(g.loc(), "gabe.g66.kid", Sound.ENTITY_WITCH_CELEBRATE, 0.8f, 0.9f + g.game.random.nextFloat() * 0.25f, 1.6f, 16, -1);
    }
}
