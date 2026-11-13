package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G04 뭉텅이 떼 게이브(떼). 얼굴 10개가 볼을 맞댄 공(디스플레이 하나)으로 굴러온다('꾸물꾸물'). 12블록 안에 사람이 보이거나 맞으면 '퍽' 터져
 * 낱개 10마리(각자 디스플레이 하나, 작은 가오리 꼴)가 사방으로 튀고, 낱개는 G01 처럼 짧게 겨눴다 박는다(1 피해, 약한 밀침).
 * 터진 지 3초에 다섯 이상 살아 있으면 서로를 찾아 모여 다시 공이 된다(두 번째 밀집 때). 공은 굴러 부딪히면 2 피해.
 *
 * <p>체력 약속(G02 와 같음): 감독이 넣은 maxHp 를 첫 틱에 '낱개 하나 체력'으로 읽고 공의 hp 는 낱개 합계. 공은 맞으면 체력이 깎이지 않고 터지며
 * 그 피해를 낱개에 넘긴다(폭발, 불은 반경 안 모두, 그 밖은 맞은 점에 가장 가까운 하나). 보상(무리 8원)은 무리의 마지막 개체가 사람 손에 죽을 때.
 * 다시 뭉친 공이 또 터지면 낱개는 모였을 때 남은 체력 합을 나눠 갖는다(뭉쳤다고 다 낫지 않음). 디스플레이 예산이 꽉 차 낱개를 하나도 낼 수 없으면
 * 터지지 않고 한 몸으로 맞는다(체력은 낱개 합, 굴러 부딪힘은 1초에 한 번).
 *
 * <p>도감과 다른 점: 낱개는 G02 닻(Swarm) 대신 각자 가짜 몹(같은 종, 보상 0)이다. 낱개가 서로 다른 사람에게 따로 박고, 다시 모일 때 실제로
 * 걸어 모여야 해서(닻 오프셋만으로는 떨어진 낱개 판정과 벽이 맞지 않음). 디스플레이 수는 공 1, 터지면 낱개 수(LOD 없이 10).
 *
 * <p>팩 계약(tools/gabe/sp/g04.py): 공 {@code gave:gabe/g04} 상태 {@code ""}(얼굴 10), {@code "n7"}, {@code "n5"}, 굴림 자세 8(주기 30,
 * look.rate 로 빠르기, 멈추면 freeze). 낱개 {@code gave:gabe/g04_m} 상태 {@code ""}, {@code "b"}. 소리 rub, pop, bump. 글귀 cause_g04.
 */
public final class G04 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int COUNT = 10, REFORM_MIN = 5;
    /** 공: 빠르기, 높이, 반지름, 터지는 거리(보이면), 다시 뭉친 뒤 터지지 않는 틱, 굴러 부딪힘 피해. */
    static final double BALL_SPEED = 0.14, BALL_HOVER = 0.75, BALL_R = 0.7, BURST_RANGE = 12, BALL_DMG = 3;
    static final int REFORM_CALM = 50, BUMP_EVERY = 20;
    /** 굴림 자세 한 바퀴가 맞는 빠르기(블록/틱): 2 pi x 0.6 / 30. */
    static final double ROLL_V = 0.126;
    /** 낱개: 빠르기, 높이, 반지름, 튀는 빠르기와 틱, 겨눔 거리, 겨눔 틱, 박치기 빠르기와 최대 틱, 피해, 밀침. */
    static final double M_SPEED = 0.19, M_HOVER = 0.8, M_R = 0.25, SCATTER_V = 0.42, AIM_RANGE = 5, DASH_V = 0.5, M_DMG = 1.5, M_PUSH = 0.28;
    static final int SCATTER = 7, AIM = 8, DASH = 14, BOUNCE = 10;
    /** 터진 뒤 이 틱에 다섯 이상이면 모임, 모이는 반경, 모이기 최대 틱. */
    static final int REFORM_AT = 60, GATHER_MAX = 80;
    static final double GATHER_R = 1.3;

    /** 터져 나온 낱개 한 무리(보상과 다시 모이기를 나눠 가짐). */
    static final class Family {
        final List<G04> members = new ArrayList<>();
        int reward;
        double memberHp;
        int burstAt;
        boolean gathering, decided;

        int alive() {
            int n = 0;
            for (G04 g : members) if (!g.dead) n++;
            return n;
        }

        Vector centroid() {
            Vector c = new Vector();
            int n = 0;
            for (G04 g : members) {
                if (g.dead) continue;
                c.add(g.pos);
                n++;
            }
            return n == 0 ? null : c.multiply(1.0 / n);
        }
    }

    enum Phase { ROLL, SCATTER, HUNT, AIM, DASH, BOUNCE, GATHER }

    private boolean member, init;
    private Family fam;
    private int count = COUNT;
    private double memberHp;
    private Phase phase = Phase.ROLL;
    private int phaseAt, calmUntil, nextRub, bumpAt, squashAt;
    private boolean squashed;
    private final Vector dir = new Vector(0, 0, 1);
    private Player prey;

    public G04(Game game, Species sp) {
        super(game, sp);
        radius = BALL_R;
        hover = BALL_HOVER;
        baseSpeed = BALL_SPEED;
    }

    /** 낱개(터질 때 공이 만든다). */
    private G04(Game game, Species sp, Family fam, Vector scatter) {
        super(game, sp);
        member = true;
        this.fam = fam;
        radius = M_R;
        hover = M_HOVER;
        baseSpeed = M_SPEED;
        noPush = false;
        dir.copy(scatter);
        phase = Phase.SCATTER;
        state(game.random.nextBoolean() ? "" : "b");
    }

    @Override
    protected void build() {
        if (member) body = Body.of(this, "gabe/g04_m", 1.4f, 0.8f);
        else body = Body.of(this, "gabe/g04", 2.2f, 2.0f);
    }

    @Override
    protected void spawned() {
        nextRub = age + game.random.nextInt(12);
        phaseAt = age;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private void init() {
        init = true;
        if (member) return;
        memberHp = Math.max(1, maxHp);
        maxHp = hp = memberHp * count;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        // 찌그러짐 되돌리기(박은 뒤, 다시 뭉친 뒤): 틱을 건너뛰어도(물어뜯기, 끌림) 다음에 꼭 되돌림
        if (squashed && age >= squashAt + 3) {
            body.squash(1f, 1f, 1f, 4);
            squashed = false;
        }
        if (prey != null && !standing(prey)) prey = null;
        if (member) memberTick();
        else roll();
    }

    /** 공: 흐름장을 따라 굴러옴(굴림 자세 빠르기 = 움직임 빠르기). 12블록 안에 사람이 보이면 터짐. */
    private void roll() {
        follow(speed());
        double v = Math.hypot(vel.getX(), vel.getZ());
        if (v > 1e-4) {
            float want = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
            yaw = want;
            pitch = 0;
        }
        boolean moving = v > 0.01;
        body.look.freeze = !moving;
        body.look.rate = moving ? Math.max(0.5, Math.min(3.0, Math.round(v / ROLL_V * 4) / 4.0)) : 1.0;
        if (moving && age >= nextRub) {
            sound("rub", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.8f, 20);
            nextRub = age + 12 + game.random.nextInt(6);
        }
        Player p = nearest(BURST_RANGE + 1);
        if (p != null) {
            if (touches(p, 0)) {
                // 굴러 부딪힘(예산이 차서 못 터지고 남아도 1초에 한 번만)
                if (age >= bumpAt) {
                    bumpAt = age + cd(BUMP_EVERY);
                    hit(p, BALL_DMG, "cause_g04");
                    knock(p, toward(p), 0.45, 0.15);
                }
                burst(null);
                return;
            }
            boolean ready = age >= calmUntil;
            double reach = ready ? BURST_RANGE : AIM_RANGE;
            if (dist(p) <= reach && sees(p)) burst(null);
        }
    }

    /**
     * 터짐: 낱개를 사방으로 튀기고 공은 사라진다. d 가 있으면(맞아서 터짐) 그 피해를 낱개에 넘긴다. 디스플레이 예산이 꽉 차 낱개를 하나도 낼 수
     * 없으면 터지지 않고 false(공이 한 몸으로 남는다).
     */
    private boolean burst(Damage d) {
        if (dead || !game.mobs.budgetFor(1)) return false;
        Family f = fam != null ? fam : new Family();
        f.members.removeIf(g -> g.dead || g == this);
        f.reward = Math.max(f.reward, noReward ? 0 : reward);
        f.memberHp = memberHp;
        f.burstAt = (int) game.now();
        f.gathering = false;
        f.decided = false;
        int n = Math.max(1, count);
        // 낱개 체력 = 공 체력을 나눈 것(처음 공은 낱개 체력 그대로, 다시 뭉친 공은 모였을 때 남은 체력 합을 나눔)
        double each = Math.max(0.5, Math.min(memberHp, hp / n));
        double base = game.random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            if (!game.mobs.budgetFor(1)) break;
            double a = base + i * Math.PI * 2 / n + game.random.nextGaussian() * 0.2;
            Vector sc = new Vector(-Math.sin(a), 0, Math.cos(a));
            Vector at = pos.clone().add(sc.clone().multiply(0.35)).setY(pos.getY() + game.random.nextGaussian() * 0.15);
            G04 m = new G04(game, sp, f, sc);
            m.noReward = true;
            m.reward = 0;
            m.maxHp = m.hp = each;
            m.memberHp = each;
            m.yaw = (float) Math.toDegrees(Math.atan2(-sc.getX(), sc.getZ()));
            if (game.mobs.place(m, at) != null) f.members.add(m);
        }
        sound("pop", Sound.ENTITY_SLIME_SQUISH, 0.7f, 24);
        Location c = loc();
        game.shotFx.bloodHit(c, null, false, ShotFx.BLOOD_PINK);
        // 공은 보상 없이 사라짐(보상은 무리의 마지막 낱개에)
        fam = f;
        remove();
        if (d != null) pass(f, d);
        return true;
    }

    /** 공에 맞은 피해를 갓 튀어나온 낱개에(폭발, 불은 반경 안 모두, 그 밖은 맞은 점에 가장 가까운 하나). */
    private static void pass(Family f, Damage d) {
        List<G04> alive = new ArrayList<>();
        for (G04 g : f.members) if (!g.dead) alive.add(g);
        if (alive.isEmpty()) return;
        if (d.type() == DamageType.EXPLOSION || d.type() == DamageType.FIRE) {
            for (G04 g : alive) g.damage(d);
        } else {
            G04 best = alive.get(0);
            if (d.at() != null) {
                double bd = Double.MAX_VALUE;
                for (G04 g : alive) {
                    double dd = g.pos.distanceSquared(d.at());
                    if (dd < bd) {
                        bd = dd;
                        best = g;
                    }
                }
            }
            best.damage(d);
        }
    }

    // ------------------------------------------------------------------ 낱개

    private void memberTick() {
        regroupCheck();
        switch (phase) {
            case SCATTER -> {
                dash(dir, SCATTER_V);
                face(pos.clone().add(dir));
                if (inPhase() >= SCATTER || wallAhead(0.4)) phase(Phase.HUNT);
            }
            case HUNT -> hunt();
            case AIM -> aim();
            case DASH -> charge();
            case BOUNCE -> {
                vel.multiply(0.8);
                if (inPhase() >= BOUNCE) {
                    mark(null);
                    phase(Phase.HUNT);
                }
            }
            case GATHER -> gather();
            default -> phase(Phase.HUNT);
        }
    }

    private void hunt() {
        follow(speed());
        double sway = Math.sin((age + id * 11) * 0.21) * 0.5 * speed();
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-6) vel.add(new Vector(-vel.getZ() / vl, 0, vel.getX() / vl).multiply(sway));
        Player p = nearest(32);
        face(p != null ? chest(p) : pos.clone().add(vel));
        if (p != null && dist(p) <= AIM_RANGE && sees(p)) {
            prey = p;
            mark(p);
            phase(Phase.AIM);
        }
    }

    private void aim() {
        hold();
        if (prey == null) {
            mark(null); // 찍은 사람이 쓰러지거나 나감: 실눈을 다시 감음
            phase(Phase.HUNT);
            return;
        }
        face(chest(prey));
        if (inPhase() >= cd(AIM)) {
            dir.copy(toward(prey));
            phase(Phase.DASH);
        }
    }

    private void charge() {
        dash(dir, DASH_V * speed() / baseSpeed);
        face(pos.clone().add(dir));
        for (Player p : game.mobs.standing()) {
            if (touches(p, 0.05)) {
                hit(p, M_DMG, "cause_g04");
                knock(p, dir, M_PUSH, 0.08);
                sound("bump", Sound.ENTITY_SLIME_ATTACK, 1.6f, 12);
                body.squash(1.3f, 0.75f, 0.8f, 2);
                squashed = true;
                squashAt = age;
                vel.copy(dir.clone().multiply(-0.2));
                phase(Phase.BOUNCE);
                return;
            }
        }
        if (inPhase() >= DASH || wallAhead(0.4)) {
            vel.multiply(0.3);
            phase(Phase.BOUNCE);
        }
    }

    /** 터진 지 3초: 다섯 이상 살아 있으면 모두 모이기로. */
    private void regroupCheck() {
        if (fam == null || fam.decided) return;
        if (game.now() - fam.burstAt < REFORM_AT) return;
        fam.decided = true;
        if (fam.alive() < REFORM_MIN) return;
        fam.gathering = true;
        for (G04 g : fam.members) {
            if (g.dead) continue;
            g.mark(null);
            g.prey = null;
            g.noPush = true; // 서로 볼을 맞대며 겹쳐 모임
            g.phase(Phase.GATHER);
        }
    }

    /** 무리 가운데로 기를 쓰고 모임. 모두 모이면 가장 앞 번호가 다시 공이 되고 나머지는 사라진다. */
    private void gather() {
        Vector c = fam == null ? null : fam.centroid();
        if (c == null || !fam.gathering) {
            noPush = false;
            phase(Phase.HUNT);
            return;
        }
        Vector d = c.clone().subtract(pos).setY(0);
        double l = d.length();
        if (l > 0.15) vel.copy(d.multiply(Math.min(speed() * 1.4, l) / l));
        else hold();
        face(c);
        if (leader() != this) return;
        boolean all = true;
        for (G04 g : fam.members) if (!g.dead && g.pos.clone().setY(0).distanceSquared(c.clone().setY(0)) > GATHER_R * GATHER_R) all = false;
        if (all || inPhase() >= GATHER_MAX) reform(c);
    }

    private G04 leader() {
        G04 best = null;
        for (G04 g : fam.members) if (!g.dead && (best == null || g.id < best.id)) best = g;
        return best;
    }

    /** 다시 공이 됨(이 개체가 공, 모인 낱개는 사라짐). 모이지 못한 낱개는 그대로 사냥. */
    private void reform(Vector c) {
        int n = 0;
        double sum = 0;
        List<G04> joined = new ArrayList<>();
        for (G04 g : fam.members) {
            if (g.dead) continue;
            g.noPush = false;
            if (g != this && g.pos.distanceSquared(c) > (GATHER_R + 1.2) * (GATHER_R + 1.2)) {
                g.phase(Phase.HUNT);
                continue;
            }
            joined.add(g);
            sum += Math.max(0.5, g.hp);
            n++;
        }
        fam.gathering = false;
        if (n < REFORM_MIN) {
            for (G04 g : joined) g.phase(Phase.HUNT);
            return;
        }
        for (G04 g : joined) if (g != this) g.remove();
        fam.members.removeIf(g -> g.dead);
        member = false;
        count = n;
        memberHp = fam.memberHp;
        radius = BALL_R;
        hover = BALL_HOVER;
        baseSpeed = BALL_SPEED;
        maxHp = memberHp * n;
        hp = sum;
        mark(null);
        prey = null;
        body.model("gabe/g04");
        body.size(2.2f, 2.0f);
        state(n >= 9 ? "" : n >= 7 ? "n7" : "n5");
        // 볼을 맞대며 오므렸다가 퍼짐(다음 틱에 tick() 이 되돌림)
        body.squash(0.7f, 1.3f, 0.7f, 0);
        squashed = true;
        squashAt = age - 2;
        calmUntil = age + cd(REFORM_CALM);
        sound("rub", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.8f, 20);
        phase(Phase.ROLL);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        // 공은 맞으면 터지고 그 피해를 낱개에 넘김. 예산이 차서 못 터지면 한 몸으로 맞음(체력은 낱개 합)
        if (!member && d.type() != DamageType.BITE && burst(d)) return true;
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 무리의 마지막 개체(낱개든 다시 뭉친 공이든)가 사람 손에 죽으면 무리 보상(웨이브 끝 정리, 시험 죽이기는 보상 없음)
        if (fam != null && fam.reward > 0 && d != null && d.by() != null) {
            int others = 0;
            for (G04 g : fam.members) if (g != this && !g.dead) others++;
            if (others == 0) {
                noReward = false;
                reward = fam.reward;
                fam.reward = 0;
            }
        }
        super.die(d);
    }

    /** 시험용. */
    public String phaseName() {
        return (member ? "member " : "ball ") + phase + " n=" + (member ? (fam == null ? 0 : fam.alive()) : count);
    }

    @Override
    public String toString() {
        return super.toString() + (member ? " member" : " ball n=" + count) + " ph=" + phase;
    }
}
