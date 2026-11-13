package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G56 깡충 줄 게이브(떼, 돌격). 팔(막대기 둘)을 앞으로 뻗은 얼굴 줄이 방울 박자에 맞춰 같은 틱에 깡충 뛰어 와 내려찍는다.
 *
 * <p>줄 묶기: 감독은 개체를 고리의 서로 다른 각도에 하나씩 놓으므로, 놓인 직후(spawned) '열린 줄'(이 종 클래스 static, 생긴 지 JOIN 틱 안,
 * 인도자가 사람에게서 멀거나 새 자리 곁)에 붙으며 자리를 줄 꼬리 뒤로 옮긴다(고리는 흙먼지 속이라 안 보임). 열린 줄이 없으면 새 줄의 인도자가 된다.
 * 인도자는 부위 bell(장대와 방울)을 켜고 보상이 다섯 배.
 *
 * <p>박자: 방울(bell)이 울리고 BELL_LEAD 틱 뒤 줄 전체가 같은 틱에 뛴다(HOP 틱, 높이 HOP_H, 포물선은 hover 를 틱마다 바꿈). 뛰는 사이 REST 틱은
 * 꼿꼿이 멈춰 모두 표적을 본다(뻗은 팔 끝이 한꺼번에 가리킴). 인도자는 찍은 한 사람에게 곧장, 따르는 것은 앞 개체가 내려앉을 자리 뒤 SPACING 으로
 * (줄이 같은 길을 밟음). 표적 곁(NEAR)에 들면 따르는 것도 그 사람 위로 뛴다. 내려앉을 때 닿으면 SLAM 피해와 잠깐 넘어짐(root), 멈춘 동안 팔 끝에
 * 걸리면 ARM 피해와 뒤로 밀림.
 *
 * <p>인도자가 죽으면 방울이 떨어져(drop) 줄이 흩어진다: 상태 wild(종이쪽 없음, 팔 휘저음), 눈 뜸, 저마다 가장 가까운 사람에게 WILD_MUL 배
 * 빠르기로 제멋대로 뛰고 내려찍기는 SLAM_WILD.
 *
 * <p>팩 계약(tools/gabe/sp/g56.py): 상태 "", "wild", 부위 bell, 소리 bell, drop, stomp, 글귀 cause_g56.
 */
public final class G56 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 몸 중심 높이(통나무 몸통 바닥 -0.76 이 땅에 닿게). */
    static final double HOVER = 0.77;
    static final double RADIUS = 0.4;
    /** 뛰는 사이 꼿꼿이 멈춤(감독 박자 배율 곱함), 방울이 먼저 울리는 틱, 뛰는 틱, 높이, 한 번 뛰는 거리(감독 빠르기 배율 곱함). */
    static final int REST = 12, BELL_LEAD = 8, HOP = 10;
    static final double HOP_H = 1.5, HOP_LEN = 2.6;
    /** 따르는 것의 간격, 뒤처졌을 때 한 번에 더 뛸 수 있는 배율. */
    static final double SPACING = 1.25, CATCH_UP = 1.7;
    /** 표적 곁: 이 안이면 따르는 것도 사람 위로 뜀. 인도자가 표적을 찍는 거리. */
    static final double NEAR = 3.6, MARK_R = 24;
    /** 피해: 내려찍기, 팔에 걸림, 날뛰는 내려찍기. 넘어짐 틱, 팔 밀침. */
    static final double SLAM = 7, ARM = 3, SLAM_WILD = 10;
    static final int FALL = 6, ARM_CD = 20;
    static final double ARM_PUSH = 0.32, ARM_TIP = 0.95, ARM_REACH = 0.55;
    /** 날뛰는 상태 배율. */
    static final double WILD_MUL = 1.6;
    /** 줄 묶기: 줄이 열려 있는 틱, 줄 길이 상한, '사람에게서 멂' 거리, 자리 옮김을 받아 주는 거리. */
    static final int JOIN = 100, MAX_LINE = 12;
    static final double FAR = 36, NEAR_JOIN = 8;

    // ------------------------------------------------------------------ 줄(같은 종 static)

    /** 한 줄. 첫 개체가 인도자. 박자는 줄에 속한 개체 가운데 그 틱에 먼저 도는 것이 한 번만 돌린다. */
    static final class Line {
        final Game game;
        final long t0;
        final List<G56> members = new ArrayList<>();
        G56 leader;
        Player target;
        /** 사람마다 다음에 맞을 수 있는 틱(줄 전체가 한 박자에 한 번만 내려찍고, 팔도 줄 전체로 ARM_CD 에 한 번). */
        final Map<UUID, Long> slamUntil = new HashMap<>(), armUntil = new HashMap<>();
        boolean wild;
        long nextHop, bellAt = Long.MIN_VALUE, beatTick = Long.MIN_VALUE;

        Line(Game game, long t0) {
            this.game = game;
            this.t0 = t0;
        }
    }

    private static final List<Line> OPEN = new ArrayList<>();

    private Line line;
    /** 이번 뜀: 시작 틱, 출발점, 내려앉을 점, 날뛰는 상태의 다음 뜀. */
    /** 뛰지 않음 = NO_HOP(now - hopAt 이 넘치지 않게 Long.MIN_VALUE 를 쓰지 않는다). */
    private static final long NO_HOP = -1_000_000L;
    private long hopAt = NO_HOP;
    private final Vector from = new Vector(), dest = new Vector();
    private long wildNext;
    private int armReady;

    public G56(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = 0.12;
    }

    // ------------------------------------------------------------------ 놓임, 줄 묶기

    @Override
    protected void spawned() {
        long now = game.now();
        if (game.director.cageSpawning()) {
            line = new Line(game, now);
            line.members.add(this);
            line.leader = this;
            return;
        }
        Line join = null;
        for (Iterator<Line> it = OPEN.iterator(); it.hasNext();) {
            Line l = it.next();
            if (l.game != game || now < l.t0 || now - l.t0 > JOIN || l.wild || l.leader == null || l.leader.dead
                    || l.members.size() >= MAX_LINE) {
                it.remove();
                continue;
            }
            if (join != null) continue;
            G56 tail = l.members.get(l.members.size() - 1);
            boolean far = game.mobs.nearestPlayer(l.leader.pos, FAR) == null && game.mobs.nearestPlayer(pos, FAR) == null;
            if (far || tail.pos.distance(pos) <= NEAR_JOIN || l.leader.pos.distance(pos) <= NEAR_JOIN) join = l;
        }
        if (join == null) {
            line = new Line(game, now);
            line.members.add(this);
            line.leader = this;
            line.nextHop = now + 10 + game.random.nextInt(20);
            body.look.on.add("bell");
            reward *= 5;
            OPEN.add(line);
            return;
        }
        // 줄 꼬리 뒤로 옮김(앞 개체에서 꼬리 쪽, 줄이 하나면 인도자 뒤 = 사람 반대쪽)
        G56 tail = join.members.get(join.members.size() - 1);
        Vector back;
        if (join.members.size() >= 2) back = tail.pos.clone().subtract(join.members.get(join.members.size() - 2).pos).setY(0);
        else back = game.mobs.flow.dir(tail.pos).multiply(-1).setY(0);
        if (back.lengthSquared() < 1e-6) back = new Vector(1, 0, 0);
        Vector at = tail.pos.clone().add(back.normalize().multiply(SPACING));
        if (game.mobs.flow.walkable(at.getX(), at.getZ())) {
            pos.setX(at.getX());
            pos.setZ(at.getZ());
            pos.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
        }
        line = join;
        join.members.add(this);
        yaw = tail.yaw;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        long now = now();
        if (line.wild) {
            wildTick(now);
            return;
        }
        if (line.beatTick != now) beat(now);
        if (now - hopAt < HOP) {
            hopMove(now, false);
            return;
        }
        // 꼿꼿이 멈춤: 모두 표적을 봄, 팔 끝에 걸린 사람
        hold();
        hover = HOVER;
        Player t = line.target;
        if (t != null && t.isOnline()) faceFlat(chest(t));
        if (age >= armReady && every(3)) armCheck(ARM);
    }

    /** 줄의 박자(한 틱에 한 번): 죽은 개체 빼기, 표적 고르기, 방울, 뛰기 시작. */
    private void beat(long now) {
        Line l = line;
        l.beatTick = now;
        l.members.removeIf(m -> m.dead);
        if (l.leader == null || l.leader.dead) {
            scatter(l);
            return;
        }
        G56 lead = l.leader;
        // 인도자가 찍은 사람은 쓰러질 때까지 그대로, 아직 안 찍었으면 가장 가까운 사람 쪽으로 오다가 MARK_R 안에서 찍음
        if (!standing(lead.target)) {
            if (lead.target != null) lead.mark(null);
            Player p = lead.nearest(400);
            l.target = p;
            if (p != null && lead.dist(p) <= MARK_R) lead.mark(p);
        } else l.target = lead.target;
        if (now >= l.nextHop - BELL_LEAD && l.bellAt < l.nextHop - BELL_LEAD) {
            l.bellAt = now;
            lead.sound("bell", Sound.BLOCK_NOTE_BLOCK_BELL, 1.6f, 32);
        }
        if (now < l.nextHop) return;
        // 같은 틱에 모두 뜀: 인도자부터 차례로 내려앉을 자리를 정함
        Vector aheadDest = null;
        for (G56 m : l.members) {
            m.plan(l, aheadDest, now);
            aheadDest = m.dest;
        }
        l.nextHop = now + HOP + lead.cd(REST);
    }

    /** 이번 뜀의 내려앉을 자리(인도자는 표적 쪽, 따르는 것은 앞 개체 뒤, 표적 곁이면 그 사람 위). */
    private void plan(Line l, Vector aheadDest, long now) {
        double reach = HOP_LEN * speed() / baseSpeed;
        from.copy(pos);
        Player t = l.target;
        Vector want;
        if (t != null && standing(t) && (aheadDest == null || dist(t) <= NEAR)) {
            Vector feet = t.getLocation().toVector();
            Vector to = feet.clone().subtract(pos).setY(0);
            double d = to.length();
            if (d <= reach * 1.15) want = feet;
            else {
                Vector dir = d > 20 ? game.mobs.flow.dir(pos) : to.multiply(1 / Math.max(d, 1e-6));
                want = pos.clone().add(dir.multiply(reach));
            }
        } else if (aheadDest != null) {
            Vector off = pos.clone().subtract(aheadDest).setY(0);
            if (off.lengthSquared() < 1e-6) off = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))).multiply(-1);
            want = aheadDest.clone().add(off.normalize().multiply(SPACING));
            reach *= CATCH_UP;
        } else {
            want = pos.clone().add(game.mobs.flow.dir(pos).multiply(reach));
        }
        Vector step = want.clone().subtract(pos).setY(0);
        if (step.length() > reach) step.normalize().multiply(reach);
        dest.copy(pos).add(step);
        hopAt = now;
    }

    /** 뜀 한 틱: 수평은 고르게, 높이는 hover 포물선. 내려앉는 틱에 내려찍기. */
    private void hopMove(long now, boolean wild) {
        int k = (int) (now - hopAt);
        double t = (k + 1) / (double) HOP;
        Vector step = dest.clone().subtract(from).setY(0).multiply(1.0 / HOP);
        vel.setX(step.getX()).setY(0).setZ(step.getZ());
        double h = (wild ? HOP_H * 1.2 : HOP_H);
        hover = HOVER + 4 * h * t * (1 - t);
        if (step.lengthSquared() > 1e-6) faceFlat(pos.clone().add(step));
        if (k == HOP - 1) land(wild);
    }

    private void land(boolean wild) {
        hover = HOVER;
        body.squash(1.12f, 0.82f, 1.12f, 2);
        if (line.leader == this || wild && game.random.nextInt(3) == 0) sound("stomp", Sound.BLOCK_WOOD_FALL, 0.8f, 16);
        long now = now();
        for (Player p : game.mobs.standing()) {
            if (!touches(p, 0.15)) continue;
            Long until = line.slamUntil.get(p.getUniqueId());
            if (until != null && until > now) continue;
            // 줄 전체가 한 박자에 한 번(날뛰면 저마다 박자라 짧게)
            line.slamUntil.put(p.getUniqueId(), now + (wild ? 6 : HOP));
            hit(p, wild ? SLAM_WILD : SLAM, "cause_g56");
            game.players.root(p, FALL, "g56");
            knock(p, toward(p), 0.15, 0.1);
            if (!wild) sound("stomp", Sound.BLOCK_WOOD_FALL, 0.8f, 16);
        }
    }

    /** 멈춘 동안 뻗은 팔 끝(몸 앞 ARM_TIP)에 걸린 사람: 피해와 뒤로 밀림. */
    private void armCheck(double dmg) {
        double r = Math.toRadians(yaw);
        Vector f = new Vector(-Math.sin(r), 0, Math.cos(r));
        double tx = pos.getX() + f.getX() * ARM_TIP, tz = pos.getZ() + f.getZ() * ARM_TIP;
        for (Player p : game.mobs.standing()) {
            Vector c = chest(p);
            double dx = c.getX() - tx, dz = c.getZ() - tz;
            if (dx * dx + dz * dz > (ARM_REACH + 0.3) * (ARM_REACH + 0.3) || Math.abs(c.getY() - pos.getY()) > 1.4) continue;
            Long until = line.armUntil.get(p.getUniqueId());
            if (until != null && until > now()) continue;
            line.armUntil.put(p.getUniqueId(), now() + cd(ARM_CD));
            hit(p, dmg, "cause_g56");
            knock(p, f, ARM_PUSH, 0.15);
            armReady = age + cd(ARM_CD);
            return;
        }
    }

    // ------------------------------------------------------------------ 날뜀(인도자 잃음)

    private static void scatter(Line l) {
        if (l.wild) return;
        l.wild = true;
        for (G56 m : l.members) {
            if (m.dead) continue;
            m.state("wild");
            m.body.look.on.remove("bell");
            m.wildNext = m.now() + 4 + m.game.random.nextInt(12);
            m.hopAt = NO_HOP;
            m.hover = HOVER;
        }
    }

    private void wildTick(long now) {
        if (!"wild".equals(state)) {
            state("wild");
            body.look.on.remove("bell");
        }
        if (now - hopAt < HOP) {
            hopMove(now, true);
            return;
        }
        hold();
        hover = HOVER;
        Player p = nearest(400);
        if (p != target) mark(p);
        if (p != null) faceFlat(chest(p));
        if (age >= armReady && every(3)) armCheck(ARM);
        if (now < wildNext) return;
        // 저마다 제멋대로: 더 멀리, 더 자주
        double reach = HOP_LEN * WILD_MUL * speed() / baseSpeed;
        from.copy(pos);
        Vector want;
        if (p != null) {
            Vector feet = p.getLocation().toVector();
            Vector to = feet.clone().subtract(pos).setY(0);
            double d = to.length();
            want = d <= reach * 1.15 ? feet : pos.clone().add((d > 20 ? game.mobs.flow.dir(pos) : to.multiply(1 / Math.max(d, 1e-6))).multiply(reach));
            // 옆으로 어긋나게(제멋대로)
            want.add(new Vector(game.random.nextGaussian() * 0.6, 0, game.random.nextGaussian() * 0.6));
        } else want = pos.clone().add(game.mobs.flow.dir(pos).multiply(reach));
        Vector step = want.subtract(pos).setY(0);
        if (step.length() > reach) step.normalize().multiply(reach);
        dest.copy(pos).add(step);
        hopAt = now;
        wildNext = now + HOP + Math.max(2, (int) Math.round(cd(REST) / WILD_MUL)) + game.random.nextInt(4);
    }

    // ------------------------------------------------------------------ 죽음

    @Override
    public void die(Damage d) {
        if (dead) return;
        boolean wasLeader = line != null && line.leader == this && !line.wild;
        super.die(d);
        if (wasLeader) {
            sound("drop", Sound.BLOCK_BELL_RESONATE, 1.4f, 32);
            line.members.removeIf(m -> m.dead);
            scatter(line);
        }
    }

    // ------------------------------------------------------------------ 도우미

    /** 수평으로만 바라봄(통나무 몸이 기울지 않게). */
    private void faceFlat(Vector at) {
        face(at);
        pitch = 0;
    }

    /** 시험용: 줄 길이, 인도자인가, 날뛰는가. */
    public String lineInfo() {
        return (line == null ? "-" : line.members.size() + (line.leader == this ? " leader" : "") + (line.wild ? " wild" : ""));
    }

    @Override
    public String toString() {
        return super.toString() + " line=" + lineInfo();
    }
}
