package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G73 왕관 쓴 떼 게이브(떼, 지원). 세 다리로 뒤뚱대는 작은 떼 가운데 하나만 왕관을 썼다. 왕관은 CROWN_MOVE 틱마다 무리 안 다른 머리로 폴짝
 * 옮겨 가고(쨍, 반짝), 왕이 살아 있으면 SPIT 틱마다 쓰러진 마리 하나를 왕의 입에서 다시 뱉어 낸다(무리 처음 수까지, 보상 0). 왕이 죽으면 남은
 * 마리가 모두 그 자리에서 쓰러지고, 왕을 잡은 사람에게 무리 합계 보상 x2.
 *
 * <p>무리 묶기는 G56 과 같은 꼴(spawned 에서 열린 무리에 붙음, 없으면 새 무리의 왕). 왕 체력은 왕관에 붙어 있다: 왕관 쓴 머리가 받은 피해는 무리의
 * 왕관 체력(CROWN_HP x 감독 체력 배율)에서 빠지고, 왕관이 옮겨 가도 그대로다.
 *
 * <p>움직임: 무리가 왕이 고른 한 사람에게 몰려와 ENGAGE 안에서 RING_R 둘레를 돌며 둘러싸고, LUNGE_EVERY 틱(박자 배율)마다 한 마리씩 차례로 뛰어들어
 * 문다(마리 BITE, 왕 KING_BITE).
 *
 * <p>팩 계약(tools/gabe/sp/g73.py): 상태 "a", "b", "king", 소리 crown(24), gulp(20), bite(12), 글귀 cause_g73.
 */
public final class G73 extends Gabe {

    static final double HOVER = 0.56, RADIUS = 0.32, SPEED = 0.16;
    static final double BITE = 3, KING_BITE = 6, CROWN_HP = 30;
    /** 왕관 옮김, 다시 뱉음(틱). */
    static final int CROWN_MOVE = 100, SPIT = 60;
    /** 둘러싸기: 표적 곁 거리, 도는 반지름, 뛰어들기 간격(박자 배율), 뛰어드는 틱과 빠르기. */
    static final double ENGAGE = 7, RING_R = 2.4, LUNGE_V = 0.38;
    static final int LUNGE_EVERY = 12, LUNGE_T = 10;
    /** 무리 묶기. */
    static final int JOIN = 100, MAX_COURT = 20;
    static final double FAR = 36, NEAR_JOIN = 8;

    /** 한 무리. */
    static final class Court {
        final Game game;
        final long t0;
        final List<G73> members = new ArrayList<>();
        G73 king;
        int initial;
        double crownHp, crownMax;
        Player target;
        long nextCrown, nextSpit, nextLunge, beatTick = Long.MIN_VALUE;
        boolean fallen;

        Court(Game game, long t0) {
            this.game = game;
            this.t0 = t0;
        }
    }

    private static final List<Court> OPEN = new ArrayList<>();
    /** 왕이 다시 뱉는 동안 참(그 개체의 spawned 가 무리를 찾지 않음). */
    private static boolean spitting;

    private Court court;
    private int lungeUntil = -1, orbitDir;
    private final Vector lungeDir = new Vector();

    public G73(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    private String variant() {
        return (id & 1) == 0 ? "a" : "b";
    }

    // ------------------------------------------------------------------ 놓임, 무리 묶기

    @Override
    protected void spawned() {
        orbitDir = game.random.nextBoolean() ? 1 : -1;
        state(variant());
        if (spitting) return;
        long now = game.now();
        Court join = null;
        if (!game.director.cageSpawning()) {
            for (Iterator<Court> it = OPEN.iterator(); it.hasNext();) {
                Court c = it.next();
                if (c.game != game || now < c.t0 || now - c.t0 > JOIN || c.fallen || c.king == null || c.king.dead
                        || c.members.size() >= MAX_COURT) {
                    it.remove();
                    continue;
                }
                if (join != null) continue;
                boolean far = game.mobs.nearestPlayer(c.king.pos, FAR) == null && game.mobs.nearestPlayer(pos, FAR) == null;
                if (far || c.king.pos.distance(pos) <= NEAR_JOIN) join = c;
            }
        }
        if (join == null) {
            court = new Court(game, now);
            court.members.add(this);
            court.king = this;
            court.initial = 1;
            court.nextCrown = now + CROWN_MOVE;
            court.nextSpit = now + SPIT;
            state("king");
            if (!game.director.cageSpawning()) OPEN.add(court);
            return;
        }
        court = join;
        join.members.add(this);
        join.initial = Math.max(join.initial, join.members.size());
        // 왕 곁 무리 속으로(고리는 흙먼지 속)
        double a = game.random.nextDouble() * Math.PI * 2, r = 0.8 + game.random.nextDouble() * 1.6;
        Vector at = join.king.pos.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r));
        if (game.mobs.flow.walkable(at.getX(), at.getZ())) {
            pos.setX(at.getX());
            pos.setZ(at.getZ());
            pos.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        long now = now();
        if (court.beatTick != now) beat(now);
        Player t = court.target;
        if (lungeUntil >= age) {
            dash(lungeDir, LUNGE_V * speed() / baseSpeed);
            face(pos.clone().add(lungeDir));
            pitch = 0;
            if (standing(t) && touches(t, 0.1)) {
                hit(t, court.king == this ? KING_BITE : BITE, "cause_g73");
                sound("bite", Sound.ENTITY_FOX_BITE, 1.4f, 12);
                lungeUntil = -1;
            }
            if (lungeUntil < age || lungeUntil == age) endLunge();
            return;
        }
        if (standing(t) && dist(t) <= ENGAGE) {
            // 둘러싸고 돎
            Vector c = t.getLocation().toVector().setY(pos.getY());
            Vector off = pos.clone().subtract(c).setY(0);
            if (off.lengthSquared() < 1e-6) off = new Vector(1, 0, 0);
            off.normalize();
            double ang = 0.35 * orbitDir;
            Vector rot = new Vector(off.getX() * Math.cos(ang) - off.getZ() * Math.sin(ang), 0, off.getX() * Math.sin(ang) + off.getZ() * Math.cos(ang));
            seek(c.clone().add(rot.multiply(RING_R)), speed() * 1.2);
            face(chest(t));
            pitch = 0;
        } else {
            follow(speed());
            if (t != null && t.isOnline()) face(chest(t));
            else face(pos.clone().add(vel));
            pitch = 0;
        }
    }

    private void startLunge(Player t) {
        Vector d = toward(t);
        if (d.lengthSquared() < 1e-6) return;
        lungeDir.copy(d);
        lungeUntil = age + LUNGE_T;
        if (court.king != this) mark(t);
    }

    private void endLunge() {
        lungeUntil = -1;
        if (court.king != this) mark(null);
    }

    /** 무리 박자(한 틱에 한 번): 표적, 왕관 옮김, 다시 뱉음, 차례로 뛰어들기. */
    private void beat(long now) {
        Court c = court;
        c.beatTick = now;
        c.members.removeIf(m -> m.dead);
        if (c.fallen || c.king == null || c.king.dead) return;
        G73 king = c.king;
        if (c.crownMax <= 0) {
            // 감독 체력 배율은 spawned 뒤에 곱해지므로 첫 박자에서 왕관 체력을 정함
            c.crownMax = CROWN_HP * (sp.hp > 0 ? king.maxHp / sp.hp : 1);
            c.crownHp = c.crownMax;
        }
        Player p = standing(c.target) ? c.target : king.nearest(400);
        c.target = p;
        if (p != null && king.dist(p) <= ENGAGE * 2) {
            if (king.target != p) king.mark(p);
        } else if (king.target != null) king.mark(null);
        if (now >= c.nextCrown) {
            c.nextCrown = now + CROWN_MOVE;
            moveCrown(c);
        }
        if (now >= c.nextSpit) {
            c.nextSpit = now + SPIT;
            if (c.members.size() < c.initial) spit(c);
        }
        if (p != null && now >= c.nextLunge) {
            G73 pick = null;
            double best = Double.MAX_VALUE;
            for (G73 m : c.members) {
                if (m.lungeUntil >= m.age || m.dist(p) > ENGAGE) continue;
                // 왕은 가끔만 뛰어듦
                double score = m.dist(p) + (m == king ? 3 : 0) + game.random.nextDouble() * 2;
                if (score < best) {
                    best = score;
                    pick = m;
                }
            }
            if (pick != null) {
                pick.startLunge(p);
                c.nextLunge = now + king.cd(LUNGE_EVERY);
            }
        }
    }

    /** 왕관이 다른 머리로 폴짝(쨍, 반짝). 왕관 체력은 그대로. */
    private static void moveCrown(Court c) {
        if (c.members.size() < 2) return;
        G73 old = c.king;
        G73 next = old;
        for (int tries = 0; tries < 6 && next == old; tries++) next = c.members.get(old.game.random.nextInt(c.members.size()));
        if (next == old || next.dead) return;
        old.state(old.variant());
        if (old.target != null) old.mark(null);
        c.king = next;
        next.state("king");
        next.flash();
        next.sound("crown", Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.6f, 24);
        if (c.target != null && next.standing(c.target)) next.mark(c.target);
    }

    /** 쓰러진 마리 하나를 왕의 입에서 다시 뱉음(보상 0, 체력은 무리와 같은 배율). */
    private static void spit(Court c) {
        G73 king = c.king;
        double r = Math.toRadians(king.yaw);
        Vector at = king.pos.clone().add(new Vector(-Math.sin(r), 0, Math.cos(r)).multiply(0.7));
        at.setY(king.game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
        Gabe g;
        spitting = true;
        try {
            g = king.child(king.sp, at);
        } finally {
            spitting = false;
        }
        if (!(g instanceof G73 m)) return;
        double hp = king.sp.hp * (c.crownMax > 0 ? c.crownMax / CROWN_HP : 1);
        m.maxHp = hp;
        m.hp = hp;
        m.noReward = true;
        m.reward = 0;
        m.court = c;
        c.members.add(m);
        king.sound("gulp", Sound.ENTITY_GENERIC_EAT, 0.8f, 20);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        Court c = court;
        if (c != null && c.king == this && !c.fallen && c.crownMax > 0) {
            // 왕관 쓴 머리: 왕관 체력에서 빠짐
            double eff = Math.min(d.amount(), Math.max(0, c.crownHp));
            c.crownHp -= d.amount();
            game.mobs.onHurt(this, d, eff);
            if (c.crownHp <= 0) {
                hp = 0;
                die(d);
            }
            return true;
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        Court c = court;
        boolean king = c != null && c.king == this && !c.fallen;
        if (king) {
            c.fallen = true;
            c.members.removeIf(m -> m.dead);
            // 무리 합계 x2 를 왕에게(쓰러지는 마리는 보상 0)
            int sum = 0;
            for (G73 m : c.members) sum += Math.max(0, m.reward);
            reward = noReward ? 0 : sum * 2;
        }
        super.die(d);
        if (king) {
            for (G73 m : new ArrayList<>(c.members)) {
                if (m == this || m.dead) continue;
                m.reward = 0;
                if (noReward) m.noReward = true;
                m.die(Damage.of(Math.max(1, m.hp), DamageType.OTHER));
            }
        }
    }

    /** 시험용. */
    public String courtInfo() {
        if (court == null) return "-";
        return court.members.size() + "/" + court.initial + (court.king == this ? " king" : "") + " crown=" + Math.round(court.crownHp) + "/"
                + Math.round(court.crownMax) + (court.fallen ? " fallen" : "");
    }

    @Override
    public String toString() {
        return super.toString() + " court=" + courtInfo();
    }
}
