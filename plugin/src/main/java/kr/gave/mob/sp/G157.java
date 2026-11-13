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
 * G157 새끼 딸린 게이브(떼, 보상). 둥근 얼굴에 짧은 다리 둘, 날개 막대기 둘로 뒤뚱뒤뚱 걸어오는 무리. 무리마다 셋째로 붙은 하나가 절반 크기
 * 금빛 새끼(calf, 체력 x CALF_HP_MUL)가 되어 무리 앞 CALF_AHEAD 블록, 사람 쪽 사선으로 먼저 걸어간다(가는 울음 cry).
 *
 * <p>성남: 새끼가 맞으면(죽지 않아도, 폭발도) 무리 전체가 STARE 틱 멈춰 쏜 사람을 돌아보며 한꺼번에 눈을 뜨고(mark), 짙은 홍조로 배를 깔고
 * 엎드려(rage) 곧게 내달린다(RAGE_SPEED). 쏜 사람 쪽으로 방향을 굳혀 지나간 뒤 RUN_PAST 블록 또는 RUN_MAX 틱이면 멈춰 다시 겨눔(곧게만 달리니
 * 옆으로 비켜 지나보냄). 배 미끄럼 들이받기 SLIDE 피해와 넘어짐. 새끼가 죽으면 판에 있는 이 종 모두가 성난다(그 무리는 더 오래).
 * 새끼를 안 다치고 어미를 다 잡으면 새끼는 울며 달아나 사라지고(보상 없음) 팀 전원에게 TEAM_BONUS x 감독 보상 배율.
 *
 * <p>무리 묶기는 G56 과 같은 꼴: 감독이 개체를 고리의 서로 다른 각도에 하나씩 놓으므로 spawned 에서 '열린 무리'(이 종 static, JOIN 틱 안, 사람에게서
 * 멀거나 첫 마리 곁)에 붙으며 자리를 그 무리 곁으로 옮긴다(고리는 흙먼지 속이라 안 보임).
 *
 * <p>팩 계약(tools/gabe/sp/g157.py): 상태 "", "rage", "calf", 소리 cry(24), rage(32), bump(16), 글귀 cause_g157, notice_g157_calm.
 */
public final class G157 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 몸 가운데에서 바닥까지(팩 어미 -0.52, 새끼 -0.29, 성남 -0.3). */
    static final double HOVER = 0.52, CALF_HOVER = 0.29, RAGE_HOVER = 0.31;
    static final double RADIUS = 0.45, CALF_R = 0.28;
    static final double SPEED = 0.14, RAGE_SPEED = 0.36;
    /** 피해: 부딪치기, 배 미끄럼 들이받기, 새끼 쪼기. */
    static final double BUMP = 4, SLIDE = 9, PECK = 1;
    static final int BUMP_CD = 24, SLIDE_ROOT = 8, PECK_CD = 20;
    /** 성남: 지속, 새끼가 죽었을 때 그 무리의 지속, 돌아보는 틱. */
    static final int RAGE_T = 240, CALF_DEAD_RAGE = 400, STARE = 10;
    /** 내달리기: 지나간 뒤 이만큼, 한 번 최대 틱, 다시 겨누는 멈춤. */
    static final double RUN_PAST = 4;
    static final int RUN_MAX = 50, RUN_TURN = 8;
    static final double CALF_HP_MUL = 3, CALF_AHEAD = 3.0, CALF_RUSH = 1.9;
    static final int TEAM_BONUS = 12, FLEE_T = 80;
    /** 무리 묶기: 열린 틱, 최대 수, 몇째가 새끼, '사람에게서 멂', 곁. 무리 뭉침(가운데에서 이만큼 넘으면 당김). */
    static final int JOIN = 100, MAX_HERD = 14, CALF_AT = 3;
    static final double FAR = 36, NEAR_JOIN = 8, COHERE = 3.0;

    /** 한 무리. */
    static final class Herd {
        final Game game;
        final long t0;
        final List<G157> members = new ArrayList<>();
        G157 calf;
        Player target, shooter;
        long rageUntil = Long.MIN_VALUE / 2, stareUntil = Long.MIN_VALUE / 2, beatTick = Long.MIN_VALUE;
        boolean calfHurt, done, everMother;
        final Vector center = new Vector();

        Herd(Game game, long t0) {
            this.game = game;
            this.t0 = t0;
        }

        boolean raging(long now) {
            return now < rageUntil;
        }
    }

    private static final List<Herd> OPEN = new ArrayList<>();

    private Herd herd;
    private boolean calf, calfInit, fleeing;
    private int bumpReady, runEnd = -1, turnUntil = -1, fleeEnd, runNo;
    private final Vector runDir = new Vector();
    private final Map<UUID, Integer> slid = new HashMap<>();

    public G157(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    // ------------------------------------------------------------------ 놓임, 무리 묶기

    @Override
    protected void spawned() {
        long now = game.now();
        Herd join = null;
        if (!game.director.cageSpawning()) {
            for (Iterator<Herd> it = OPEN.iterator(); it.hasNext();) {
                Herd h = it.next();
                h.members.removeIf(m -> m.dead);
                if (h.game != game || now < h.t0 || now - h.t0 > JOIN || h.members.isEmpty() || h.members.size() >= MAX_HERD
                        || h.raging(now)) {
                    it.remove();
                    continue;
                }
                if (join != null) continue;
                G157 first = h.members.get(0);
                boolean far = game.mobs.nearestPlayer(first.pos, FAR) == null && game.mobs.nearestPlayer(pos, FAR) == null;
                if (far || first.pos.distance(pos) <= NEAR_JOIN) join = h;
            }
        }
        if (join == null) {
            herd = new Herd(game, now);
            herd.members.add(this);
            herd.everMother = true;
            if (!game.director.cageSpawning()) OPEN.add(herd);
            return;
        }
        herd = join;
        G157 first = join.members.get(0);
        double a = game.random.nextDouble() * Math.PI * 2, r = 0.9 + game.random.nextDouble() * 1.8;
        Vector at = first.pos.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r));
        boolean becomeCalf = join.calf == null && join.members.size() == CALF_AT - 1;
        if (game.mobs.flow.walkable(at.getX(), at.getZ())) {
            pos.setX(at.getX());
            pos.setZ(at.getZ());
            pos.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + (becomeCalf ? CALF_HOVER : HOVER));
        }
        join.members.add(this);
        yaw = first.yaw;
        if (becomeCalf) {
            calf = true;
            join.calf = this;
            state("calf");
            hover = CALF_HOVER;
            radius = CALF_R;
        } else join.everMother = true;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        long now = now();
        if (calf && !calfInit) {
            // 감독 체력 배율은 spawned 뒤에 곱해지므로 첫 틱에(한두 발에 죽지 않게)
            calfInit = true;
            maxHp *= CALF_HP_MUL;
            hp = maxHp;
        }
        if (herd.beatTick != now) beat(now);
        if (fleeing) {
            flee();
            return;
        }
        if (calf) {
            calfTick(now);
            return;
        }
        if (now < herd.stareUntil) {
            // 멈춰 서서 쏜 사람을 돌아봄
            hold();
            if (herd.shooter != null && herd.shooter.isOnline()) face(chest(herd.shooter));
            pitch = 0;
            return;
        }
        if (herd.raging(now)) rageTick();
        else walkTick();
        pitch = 0;
    }

    /** 무리 박자(한 틱에 한 번): 죽은 것 빼기, 가운데, 표적, 성남 끝, 어미가 다 죽었는가. */
    private void beat(long now) {
        Herd h = herd;
        h.beatTick = now;
        h.members.removeIf(m -> m.dead);
        if (h.calf != null && h.calf.dead) h.calf = null;
        h.center.zero();
        int n = 0;
        for (G157 m : h.members) {
            if (m.calf) continue;
            h.center.add(m.pos);
            n++;
        }
        if (n > 0) h.center.multiply(1.0 / n);
        else if (!h.members.isEmpty()) h.center.copy(h.members.get(0).pos);
        G157 lead = h.members.isEmpty() ? this : h.members.get(0);
        if (!standing(h.target)) h.target = lead.nearest(400);
        if (h.raging(now) && !standing(h.shooter)) h.shooter = h.target;
        if (!h.raging(now) && now >= h.stareUntil) {
            // 성남이 끝나면 다시 실눈으로 뒤뚱
            for (G157 m : h.members) {
                if (!m.calf && "rage".equals(m.state)) m.calm();
            }
        }
        // 어미가 다 쓰러지고 새끼만 남음: 새끼가 울며 달아나고, 안 다쳤으면 팀 보상
        if (!h.done && h.everMother && n == 0 && h.calf != null && !h.calf.fleeing) {
            h.done = true;
            G157 c = h.calf;
            c.fleeing = true;
            c.noReward = true;
            c.fleeEnd = c.age + FLEE_T;
            c.mark(null);
            c.sound("cry", Sound.ENTITY_CHICKEN_HURT, 1.6f, 24);
            if (!h.calfHurt) {
                game.players.teamMoney((int) Math.max(1, Math.round(TEAM_BONUS * game.director.rewardMul())));
                game.hud.notice("notice_g157_calm", Species.G157, 60);
            }
        }
    }

    private void walkTick() {
        Player t = herd.target;
        if (t == null) {
            follow(speed());
        } else {
            follow(speed());
            // 무리 가운데에서 너무 벗어나면 당김(느긋한 뒤뚱 무리)
            Vector off = herd.center.clone().subtract(pos).setY(0);
            double d = off.length();
            if (d > COHERE && dist(t) > 4) {
                vel.add(off.multiply(0.5 * speed() / d));
                double l = Math.hypot(vel.getX(), vel.getZ());
                if (l > speed()) vel.multiply(speed() / l);
            }
            face(chest(t));
            if (age >= bumpReady && touches(t, 0.1)) {
                bumpReady = age + cd(BUMP_CD);
                hit(t, BUMP, "cause_g157");
                knock(t, toward(t), 0.25, 0.08);
                sound("bump", Sound.ENTITY_SLIME_SQUISH, 1f, 16);
                body.squash(1.15f, 0.85f, 1.15f, 2);
            }
        }
        if (t == null) face(pos.clone().add(vel));
    }

    private void calfTick(long now) {
        Player t = herd.target;
        if (every(90)) sound("cry", Sound.ENTITY_CHICKEN_AMBIENT, 1.6f, 24);
        if (t == null) {
            follow(speed());
            face(pos.clone().add(vel));
            pitch = 0;
            return;
        }
        // 무리 앞, 사람 쪽 사선으로 먼저(무리와 사람 사이)
        Vector to = chest(t).subtract(herd.center).setY(0);
        double d = to.length();
        Vector want = d < 1e-6 ? t.getLocation().toVector() : herd.center.clone().add(to.multiply(Math.min(CALF_AHEAD, d - 0.6) / d));
        // 뒤처지면 빨리 앞으로 나섬(무리보다 먼저 사선에 들어감)
        double lag = Math.hypot(want.getX() - pos.getX(), want.getZ() - pos.getZ());
        want.setY(pos.getY());
        if (dist(t) > 20 || herd.members.size() <= 1) follow(speed() * 1.1);
        else seek(want, speed() * (lag > 1.5 ? CALF_RUSH : 1.15));
        face(chest(t));
        pitch = 0;
        if (age >= bumpReady && touches(t, 0.05)) {
            bumpReady = age + cd(PECK_CD);
            hit(t, PECK, "cause_g157");
        }
    }

    private void rageTick() {
        Player t = standing(herd.shooter) ? herd.shooter : herd.target;
        if (!"rage".equals(state)) {
            state("rage");
            hover = RAGE_HOVER;
            body.look.palette |= kr.gave.mob.Look.PAL_DEEP_BLUSH;
            runEnd = -1;
        }
        if (t == null) {
            follow(speed());
            return;
        }
        if (target != t) mark(t);
        if (age < turnUntil) {
            // 지나간 뒤 멈춰 다시 겨눔
            hold();
            turnTo(chest(t), 30f);
            return;
        }
        if (runEnd < 0) {
            runNo++;
            runDir.copy(toward(t));
            if (runDir.lengthSquared() < 1e-6) runDir.setX(1);
            runEnd = age + RUN_MAX;
        }
        dash(runDir, RAGE_SPEED * speed() / baseSpeed);
        face(pos.clone().add(runDir));
        for (Player p : game.mobs.standing()) {
            if (!touches(p, 0.15)) continue;
            // 한 번 내달릴 때 한 사람에게 한 번(지나간 뒤 돌아와 또 침)
            Integer run = slid.get(p.getUniqueId());
            if (run != null && run == runNo) continue;
            slid.put(p.getUniqueId(), runNo);
            hit(p, SLIDE, "cause_g157");
            game.players.root(p, SLIDE_ROOT, "g157");
            knock(p, runDir, 0.45, 0.22);
            sound("bump", Sound.ENTITY_SLIME_SQUISH, 0.8f, 16);
        }
        if (slid.size() > 30) slid.values().removeIf(u -> u < runNo);
        Vector rel = t.getLocation().toVector().subtract(pos);
        double along = rel.getX() * runDir.getX() + rel.getZ() * runDir.getZ();
        if (along < -RUN_PAST || age >= runEnd || wallAhead(1.0)) {
            runEnd = -1;
            turnUntil = age + RUN_TURN;
        }
    }

    private void calm() {
        state("");
        hover = HOVER;
        body.look.palette &= ~kr.gave.mob.Look.PAL_DEEP_BLUSH;
        runEnd = -1;
        mark(null);
    }

    /** 새끼만 남음: 사람에게서 멀어지며 울다 사라짐(보상 없음, 사체 없음). */
    private void flee() {
        Player p = nearest(400);
        if (p != null) {
            Vector away = pos.clone().subtract(p.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-6) away = new Vector(1, 0, 0);
            dash(away, 0.24);
            face(pos.clone().add(away));
        } else hold();
        pitch = 0;
        if (every(20)) sound("cry", Sound.ENTITY_CHICKEN_HURT, 1.6f, 24);
        if (age >= fleeEnd) {
            leaveCorpse = false;
            die(Damage.of(0, kr.gave.mob.DamageType.OTHER));
        }
    }

    // ------------------------------------------------------------------ 성남

    /** 이 무리를 ticks 동안 성나게(쏜 사람을 먼저 돌아봄). */
    private static void enrage(Herd h, Player shooter, int ticks) {
        long now = h.game.now();
        boolean fresh = !h.raging(now);
        h.rageUntil = Math.max(h.rageUntil, now + STARE + ticks);
        if (shooter != null) h.shooter = shooter;
        if (!fresh) return;
        h.stareUntil = now + STARE;
        Player look = h.shooter != null ? h.shooter : h.target;
        boolean sounded = false;
        for (G157 m : h.members) {
            if (m.dead || m.calf || m.fleeing) continue;
            if (look != null) m.mark(look);
            m.runEnd = -1;
            if (!sounded) {
                m.sound("rage", Sound.ENTITY_RAVAGER_ROAR, 1.6f, 32);
                sounded = true;
            }
        }
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (calf && !fleeing && herd != null) {
            herd.calfHurt = true;
            enrage(herd, d.by(), RAGE_T);
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        boolean wasCalf = calf && !fleeing;
        super.die(d);
        if (wasCalf) {
            // 새끼가 죽음: 판에 있는 이 종 모두 성남(그 무리는 더 오래)
            Player by = d == null ? null : d.by();
            List<Herd> seen = new ArrayList<>();
            for (Gabe g : game.mobs.all()) {
                if (g.dead || !(g instanceof G157 m) || m.herd == null || seen.contains(m.herd)) continue;
                seen.add(m.herd);
                if (m.herd.target == null) m.herd.target = by;
                enrage(m.herd, by, m.herd == herd ? CALF_DEAD_RAGE : RAGE_T);
            }
        }
    }

    /** 시험용. */
    public String herdInfo() {
        if (herd == null) return "-";
        return herd.members.size() + (calf ? " calf" : "") + (herd.raging(now()) ? " rage" : "") + (fleeing ? " flee" : "")
                + (herd.calfHurt ? " hurt" : "");
    }

    @Override
    public String toString() {
        return super.toString() + " herd=" + herdInfo();
    }
}
