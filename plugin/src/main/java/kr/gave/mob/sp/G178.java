package kr.gave.mob.sp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G178 머리 따로 게이브(돌격, 정예). 몸은 멀리 꿇어앉아 기다리고 머리만 날아와 사람들을 스쳐 문다. 머리는 거의 안 맞으니 몸을 찾아 잡아야 한다.
 *
 * <p>몸(감독이 낸 개체, 보상과 체력을 가짐): 가장자리에서 걸어 들어와(상태 walk) 중앙에서 KNEEL_R 블록 자리에 꿇어앉는다(상태 ""). 움직이지 않고,
 * 다가온 사람은 팔 막대기로 후려친다(상태 swing, 16). 가장 가까운 사람을 mark 해 웨이브가 끝나지 않게 한다.
 *
 * <p>머리(같은 종 child, 보상 0): 몸에서 36블록 떨어져 날아다니므로 몸의 부품이 아니라 따로 움직이는 개체다(부품이면 몸 자리 컬링에 걸려 안 보임).
 * 몸이 앉자마자 떠올라 사람 머리 높이로 0.4블록/틱 날아와 사람들을 꿰듯 일직선으로 스치며 문다(지나는 길 0.8블록 안 사람 모두 14, 한 번 지날 때 한 번).
 * 8블록 지나쳐 돌아 다시 온다(한 틱 12도까지 틂). 세 번째 지날 때마다 한 사람 얼굴에 붙어 1.5초 동안 0.5초마다 6(붙잡힘, 연타 4). 20초마다 2초 동안
 * 높이 떠올랐다가 몸으로 돌아가 3초 얹혀 쉰다. 머리가 받은 피해는 몸 체력에서 빠진다: 날아다니는 동안 10분의 1, 얹혀 있는 동안 그대로. 몸이 죽으면
 * 머리는 그 자리에서 떨어져 구른다. 2초마다 머리에서 몸 쪽으로 빛 실(ShotFx.streak)이 번쩍인다.
 *
 * <p>팩 계약(tools/gabe/sp/g178.py): 몸 gabe/g178 상태 "walk", "", "swing". 머리 gabe/g178_head 상태 "". 소리 giggle(24), bite(16), swing(20).
 * 글귀 cause_g178.
 */
public final class G178 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double BODY_HOVER = 1.3, BODY_R = 0.5, WALK = 0.12, KNEEL_R = 36, SWING_R = 2.8, SWING = 16;
    static final int SWING_T = 12, SWING_HIT = 6, SWING_CD = 30, THREAD_EVERY = 40;
    /** 목 받침 위 머리 자리(몸 중심에서). */
    static final double NECK = 1.5;
    /** 머리: 반지름, 빠르기, 사람 위 높이(발 기준), 지나쳐 가는 거리, 스침 거리, 피해, 붙기, 쉬기. */
    static final double HEAD_R = 0.45, FLY = 0.4, EYE = 1.55, PAST = 8, GRAZE_R = 0.8, GRAZE = 14, LATCH = 6, FLY_MUL = 0.1;
    static final double TURN = Math.toRadians(12), RETURN_V = 0.6, RISE_H = 5;
    static final int LATCH_T = 30, LATCH_CLICKS = 4, HUNT_T = 400, RISE_T = 40, REST_T = 60, PASS_MAX = 120, GIGGLE_CD = 30;

    enum BPhase { WALK, KNEEL, SWING }

    enum HPhase { RIDE, HUNT, LATCH, RISE, RETURN, REST }

    /** child 로 머리를 만들 때만 true(생성자가 읽음). */
    private static boolean spawnHead;

    final boolean isHead;
    private G178 head, owner;
    // 몸
    private BPhase bp = BPhase.WALK;
    private int bAt, readyAt;
    private final Vector kneelAt = new Vector();
    // 머리
    private HPhase hph = HPhase.RIDE;
    private int hAt, huntAt, passes, giggleAt = -1000;
    private Player prey;
    private final Vector dir = new Vector(0, 0, 1), passEnd = new Vector(), last = new Vector();
    private final Set<UUID> grazed = new HashSet<>();

    public G178(Game game, Species sp) {
        super(game, sp);
        isHead = spawnHead;
        if (isHead) {
            radius = HEAD_R;
            flying = true;
            ghost = true;
            noPush = true;
            noBite = true;
            noMutate = true;
            baseSpeed = FLY;
        } else {
            radius = BODY_R;
            hover = BODY_HOVER;
            baseSpeed = WALK;
            noBite = true;
            ragKick = 0.5;
            state("walk");
        }
    }

    @Override
    protected void build() {
        if (isHead) body = Body.of(this, "gabe/g178_head", 1.0f, 1.4f);
        else body = Body.of(this, "gabe/g178", 1.8f, 3.0f);
    }

    @Override
    protected void spawned() {
        if (isHead) {
            last.copy(pos);
            return;
        }
        // 꿇어앉을 자리: 중앙에서 나온 쪽으로 KNEEL_R
        double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5;
        Vector out = new Vector(pos.getX() - cx, 0, pos.getZ() - cz);
        if (out.lengthSquared() < 1e-6) out = new Vector(1, 0, 0);
        out.normalize().multiply(KNEEL_R);
        kneelAt.setX(cx + out.getX()).setZ(cz + out.getZ());
        kneelAt.setY(game.mobs.flow.floor(kneelAt.getX(), kneelAt.getZ()) + BODY_HOVER);
        if (game.director.cageSpawning()) return;
        spawnHead = true;
        Gabe g;
        try {
            g = child(sp, neck());
        } finally {
            spawnHead = false;
        }
        if (g instanceof G178 h) {
            head = h;
            h.owner = this;
            h.maxHp = h.hp = maxHp;
        }
    }

    private Vector neck() {
        return pos.clone().add(new Vector(0, NECK, 0));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (isHead) headTick();
        else bodyTick();
    }

    private void bodyTick() {
        switch (bp) {
            case WALK -> {
                Vector to = kneelAt.clone().subtract(pos).setY(0);
                double d = to.length();
                Player p = nearest(400);
                // 걸어 들어오는 길에 사람이 코앞이면 거기서 앉음
                if (d < 0.8 || p != null && dist(p) < 6) {
                    hold();
                    state("");
                    bp = BPhase.KNEEL;
                    bAt = age;
                    if (p != null) {
                        mark(p);
                        face(chest(p));
                        pitch = 0;
                    }
                    return;
                }
                if (d > 30) follow(speed());
                else seek(kneelAt, Math.min(speed(), d));
                face(pos.clone().add(to));
                pitch = 0;
            }
            case KNEEL -> {
                hold();
                Player p = nearest(400);
                if (every(40) && !standing(target)) mark(p);
                if (p != null && dist(p) < 12) {
                    turnTo(chest(p), 4f);
                    pitch = 0;
                }
                if (p != null && age >= readyAt && dist(p) <= SWING_R) {
                    state("swing");
                    bp = BPhase.SWING;
                    bAt = age;
                    sound("swing", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 20);
                }
                if (head != null && !head.dead && every(THREAD_EVERY) && head.hph != HPhase.RIDE && head.hph != HPhase.REST) {
                    Location a = head.loc(), b = loc().add(0, NECK - 0.3, 0);
                    game.shotFx.streak(a, b);
                }
            }
            case SWING -> {
                hold();
                if (age - bAt == SWING_HIT) {
                    for (Player q : game.mobs.standing()) if (dist(q) <= SWING_R + 0.3) hit(q, SWING, "cause_g178");
                }
                if (age - bAt >= SWING_T) {
                    state("");
                    readyAt = age + cd(SWING_CD);
                    bp = BPhase.KNEEL;
                }
            }
        }
    }

    /** 몸이 꿇어앉았나(머리가 떠날 수 있나). */
    boolean kneeling() {
        return bp != BPhase.WALK;
    }

    // ------------------------------------------------------------------ 머리

    private void hphase(HPhase p) {
        hph = p;
        hAt = age;
    }

    private void headTick() {
        G178 b = owner;
        if (b == null || b.dead) {
            // 몸이 없으면 그 자리에서 떨어짐(몸이 죽으면 몸의 die 가 이미 죽였음)
            noReward = true;
            die(Damage.of(1, DamageType.OTHER));
            return;
        }
        last.copy(pos);
        switch (hph) {
            case RIDE, REST -> {
                Vector n = b.neck();
                vel.copy(n.subtract(pos));
                yaw = b.yaw;
                pitch = 0;
                if (target != null) mark(null);
                boolean go = hph == HPhase.RIDE ? b.kneeling() : age - hAt >= REST_T;
                if (go) {
                    huntAt = age;
                    passes = 0;
                    aim(true);
                    hphase(HPhase.HUNT);
                }
            }
            case HUNT -> hunt();
            case LATCH -> latch();
            case RISE -> {
                vel.setX(0).setY(RISE_H / RISE_T).setZ(0);
                if (age - hAt >= RISE_T) hphase(HPhase.RETURN);
            }
            case RETURN -> {
                Vector to = b.neck().subtract(pos);
                double d = to.length();
                if (d < 0.5) {
                    pos.copy(b.neck());
                    vel.zero();
                    hphase(HPhase.REST);
                    return;
                }
                vel.copy(to.multiply(Math.min(RETURN_V * game.director.speedMul(), d) / d));
                face(b.neck());
            }
        }
    }

    /** 다음 표적을 고르고 그 사람 머리 높이를 꿰어 PAST 블록 뒤까지 가는 길을 정함. */
    private void aim(boolean snap) {
        Player p = nearest(400);
        prey = p;
        grazed.clear();
        if (p == null) {
            passEnd.copy(owner.neck());
            return;
        }
        mark(p);
        Vector eye = p.getLocation().toVector().add(new Vector(0, EYE, 0));
        Vector d = eye.clone().subtract(pos);
        if (d.lengthSquared() < 1e-6) d = new Vector(1, 0, 0);
        d.normalize();
        Vector flat = d.clone().setY(0);
        if (flat.lengthSquared() < 1e-6) flat = new Vector(1, 0, 0);
        passEnd.copy(eye).add(flat.normalize().multiply(PAST));
        passEnd.setY(Math.max(passEnd.getY(), p.getLocation().getY() + EYE));
        if (snap) dir.copy(d);
    }

    private void hunt() {
        // 20초마다 높이 떠올랐다가 몸으로 돌아감
        if (age - huntAt >= HUNT_T) {
            hphase(HPhase.RISE);
            return;
        }
        Vector want = passEnd.clone().subtract(pos);
        double d = want.length();
        if (d < 0.8 || age - hAt >= PASS_MAX) {
            passes++;
            hAt = age;
            aim(false);
            return;
        }
        // 표적 3블록 앞까지는 그 사람 머리 쪽으로 조금씩 틀고, 그 뒤는 곧게(꿰어 지나감)
        if (prey != null && standing(prey)) {
            Vector eye = prey.getLocation().toVector().add(new Vector(0, EYE, 0));
            Vector toEye = eye.clone().subtract(pos);
            if (toEye.dot(dir) > 3) {
                Vector flat = toEye.clone().setY(0);
                if (flat.lengthSquared() > 1e-6) passEnd.copy(eye).add(flat.normalize().multiply(PAST));
                want = passEnd.clone().subtract(pos);
            }
        }
        steer(want.normalize());
        double v = FLY * game.director.speedMul();
        vel.copy(dir.clone().multiply(v));
        face(pos.clone().add(dir));
        graze();
    }

    /** 방향을 want 쪽으로 한 틱 TURN 까지 돌림(돌아 다시 오는 활꼴). */
    private void steer(Vector want) {
        double cos = Math.max(-1, Math.min(1, dir.dot(want)));
        double ang = Math.acos(cos);
        if (ang <= TURN) {
            dir.copy(want);
            return;
        }
        Vector axis = dir.getCrossProduct(want);
        if (axis.lengthSquared() < 1e-9) axis = new Vector(0, 1, 0);
        dir.rotateAroundAxis(axis.normalize(), TURN).normalize();
    }

    /** 지난 틱에서 지금까지 머리가 지난 선분 0.8블록 안 사람: 14(한 번 지날 때 사람마다 한 번). 세 번째 지날 때는 표적 얼굴에 붙음. */
    private void graze() {
        Vector a = last, ab = pos.clone().subtract(last);
        double l2 = Math.max(1e-6, ab.lengthSquared());
        for (Player p : game.mobs.standing()) {
            Vector e = p.getLocation().toVector().add(new Vector(0, 1.3, 0));
            double t = Math.max(0, Math.min(1, e.clone().subtract(a).dot(ab) / l2));
            Vector c = a.clone().add(ab.clone().multiply(t));
            double dx = e.getX() - c.getX(), dz = e.getZ() - c.getZ(), dy = e.getY() - c.getY();
            if (dx * dx + dz * dz > (GRAZE_R + HEAD_R) * (GRAZE_R + HEAD_R) || Math.abs(dy) > 1.0 + HEAD_R) {
                if (age - giggleAt >= GIGGLE_CD && dx * dx + dz * dz < 9) {
                    giggleAt = age;
                    sound("giggle", Sound.ENTITY_WITCH_CELEBRATE, 1.4f, 24);
                }
                continue;
            }
            if (!grazed.add(p.getUniqueId())) continue;
            if (passes % 3 == 2 && p == prey && grab(p, LATCH_CLICKS)) {
                hit(p, GRAZE, "cause_g178");
                sound("bite", Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.1f, 16);
                hphase(HPhase.LATCH);
                return;
            }
            hit(p, GRAZE, "cause_g178");
            sound("bite", Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.1f, 16);
            if (age - giggleAt >= GIGGLE_CD) {
                giggleAt = age;
                sound("giggle", Sound.ENTITY_WITCH_CELEBRATE, 1.4f, 24);
            }
        }
    }

    /** 한 사람 얼굴에 붙음: 1.5초 동안 0.5초마다 6. */
    private void latch() {
        Player p = prey;
        if (p == null || !p.isOnline() || game.players.grabbedBy(p) != this || age - hAt >= LATCH_T) {
            unlatch();
            passes++;
            aim(true);
            hAt = age;
            hph = HPhase.HUNT;
            return;
        }
        Location l = p.getEyeLocation();
        Vector f = l.getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) f = new Vector(0, 0, 1);
        Vector want = l.toVector().add(f.normalize().multiply(0.45));
        vel.copy(want.subtract(pos));
        face(p.getEyeLocation().toVector());
        if ((age - hAt) > 0 && (age - hAt) % 10 == 0) hit(p, LATCH, "cause_g178");
    }

    private void unlatch() {
        Player p = prey;
        if (p != null && p.isOnline() && game.players.grabbedBy(p) == this) game.players.release(p);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        if (isHead) return super.spheres();
        return List.of(new double[] {pos.getX(), pos.getY() + 0.2, pos.getZ(), BODY_R},
                new double[] {pos.getX(), pos.getY() - 0.8, pos.getZ(), BODY_R + 0.1});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!isHead) return super.damage(d);
        // 머리가 받은 피해는 몸 체력에서: 날아다니는 동안 10분의 1, 얹혀 있으면 그대로
        G178 b = owner;
        if (b == null || b.dead) return super.damage(d);
        boolean resting = hph == HPhase.RIDE || hph == HPhase.REST;
        flash();
        return b.damage(resting ? d : d.times(FLY_MUL));
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (isHead) {
            unlatch();
            super.die(d);
            return;
        }
        super.die(d);
        G178 h = head;
        if (h != null && !h.dead) {
            // 몸이 죽으면 머리는 그 자리에서 떨어져 구름
            h.noReward = true;
            h.unlatch();
            h.flying = false;
            h.die(Damage.of(1, DamageType.OTHER));
        }
    }

    @Override
    public void remove() {
        if (isHead) unlatch();
        super.remove();
        if (!isHead && head != null && !head.dead) head.remove();
    }

    /** 시험용. */
    public String info() {
        return isHead ? "head " + hph + " passes=" + passes : "body " + bp + (head == null ? " nohead" : head.dead ? " headDead" : " head=" + head.hph);
    }

    @Override
    public String toString() {
        return super.toString() + " " + info();
    }
}
