package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G111 제 머리 든 게이브(정예, 돌격). 제 머리를 손에 들고 걷는 목 없는 몸, 머리를 떨어뜨린 사람을 찍어 주워 꽂고 끝까지 쫓는다.
 *
 * <p>도감: 든 머리를 앞으로 내밀어 둘러보며 성큼성큼 걷는다(0.2). 사람 6블록 안이면 머리를 겨드랑이에 끼고 달려와 곤봉으로 내려친다(16, 0.5초
 * 넘어짐). 든 머리(체력 60)를 쏴 떨어뜨리면 머리가 4~6블록 굴러가고(지나가며 물기 6), 몸은 더듬더듬 머리 쪽으로 가서 주워 목에 꽂는다(3초). 그때부터
 * 떨어뜨린 사람을 찍어 그 사람만 쫓는다(빠르기 x1.4, 그 사람에게 곤봉 24, 조준점 옆 이 종 표시). 꽂은 머리는 체력이 다시 찬다. 땅의 머리를 몸이
 * 줍기 전에 부수면(체력 60) 몸도 쓰러진다. 급히 꽂느라 거꾸로 꽂히기도 한다.
 *
 * <p>팩 계약(tools/gabe/sp/g111.py): 몸 gave:gabe/g111(상태 "" 들고 걸음, "tuck", "smash", "grope", "set"), 머리 부품 gave:gabe/g111_head. 손, 겨드랑이,
 * 목 자리는 팩의 팔 식에서 잰 값. 소리 neck, smash, roll, laugh. 원인 글귀 cause_g111(곤봉), cause_g111_head(구르는 머리).
 */
public final class G111 extends Gabe {

    static final double SPEED = 0.2, HOVER = 1.22, RUN = 1.3, HUNT = 1.4, GROPE = 0.6, TUCK_RANGE = 6, LEAVE_RANGE = 9;
    static final double CLUB = 16, CLUB_GRUDGE = 24, CLUB_R = 2.3, HEAD_BITE = 6, HEAD_HP = 60, HEAD_R = 0.45;
    static final int SMASH_T = 8, CLUB_CD = 30, TRIP_T = 10, SET_T = 60, MARK_EVERY = 40;
    static final double ROLL_V = 0.6, ROLL_DRAG = 0.88;
    /** 머리 자리(몸 기준, +X 왼쪽): 오른손에 들고, 오른 겨드랑이, 목. */
    static final float[] CARRY = {-0.85f, 1.03f, 1.0f}, TUCK = {-0.98f, 0.32f, 0.05f}, NECK = {0f, 1.45f, 0f};

    enum Phase { CARRY, TUCK, SMASH, GROPE, SET, HUNT }

    private Phase phase = Phase.CARRY, back = Phase.TUCK;
    private int phaseAt, clubAt = -100, markAt = -100;
    private Body.Part head;
    private double headHp = HEAD_HP, headMax = HEAD_HP;
    private boolean dropped, onNeck, flipped, scaled;
    private final Vector headAt = new Vector(), headV = new Vector();
    private float headSpin;
    private final Set<UUID> bitten = new HashSet<>();
    private Player prey, grudge;

    public G111(Game game, Species sp) {
        super(game, sp);
        radius = 0.6;
        hover = HOVER;
        baseSpeed = SPEED;
        ragKick = 0.7;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g111", 2.0f, 2.8f);
        head = body.attach("gabe/g111_head");
        holdHead(CARRY, 0, 0);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private void holdHead(float[] at, float turn, int ticks) {
        if (head == null) return;
        head.follow = true;
        Matrix4f m = new Matrix4f().translate(at[0], at[1], at[2]).rotateY((float) Math.toRadians(turn));
        if (at == NECK && flipped) m.rotateZ((float) Math.PI);
        head.pose(m, ticks);
    }

    /** 머리 가운데(세계). */
    private Vector headWorld() {
        if (dropped) return headAt.clone();
        float[] h = onNeck ? NECK : (phase == Phase.CARRY ? CARRY : TUCK);
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r), lx = fz, lz = -fx;
        return new Vector(pos.getX() + lx * h[0] + fx * h[2], pos.getY() + h[1], pos.getZ() + lz * h[0] + fz * h[2]);
    }

    private double speedNow() {
        return switch (phase) {
            case TUCK -> speed() * RUN;
            case HUNT -> speed() * HUNT;
            case GROPE -> speed() * GROPE;
            default -> speed();
        };
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!scaled) {
            scaled = true;
            double k = sp.hp > 0 ? maxHp / sp.hp : 1;
            headMax = HEAD_HP * k;
            headHp = headMax;
        }
        if (dropped) headTick();
        if (grudge != null && !standing(grudge)) grudge = null;
        if (grudge != null && age - markAt >= MARK_EVERY) {
            markAt = age;
            game.hud.mark(grudge, "g111", MARK_EVERY + 20);
        }
        switch (phase) {
            case CARRY -> carry();
            case TUCK, HUNT -> run();
            case SMASH -> smash();
            case GROPE -> grope();
            case SET -> set();
        }
    }

    /** 머리를 앞으로 내밀어 이리저리 비추듯 둘러보며 걸음. */
    private void carry() {
        follow(speedNow());
        Player p = nearest(400);
        if (vel.lengthSquared() > 1e-6) face(pos.clone().add(vel));
        pitch = 0;
        if (every(8)) holdHead(CARRY, (float) (Math.sin(age * 0.05) * 35), 8);
        if (p == null) {
            mark(null);
            return;
        }
        if (dist(p) <= TUCK_RANGE && sees(p)) {
            prey = p;
            mark(p);
            state("tuck");
            holdHead(TUCK, 0, 4);
            phase(Phase.TUCK);
        }
    }

    /** 겨드랑이에 끼고(또는 목에 꽂고) 달려와 곤봉. HUNT 는 원수만 쫓는다. */
    private void run() {
        if (phase == Phase.HUNT) {
            Player want = grudge != null ? grudge : nearest(400);
            if (want != prey) {
                prey = want;
                mark(prey);
            }
        } else if (!standing(prey) || dist(prey) > LEAVE_RANGE) {
            prey = null;
            mark(null);
            state("");
            holdHead(CARRY, 0, 4);
            phase(Phase.CARRY);
            return;
        }
        if (prey == null) {
            follow(speedNow());
            if (vel.lengthSquared() > 1e-6) face(pos.clone().add(vel));
            pitch = 0;
            return;
        }
        if (dist(prey) > 2.5) follow(speedNow());
        else seek(prey.getLocation().toVector(), speedNow() * 0.6);
        face(chest(prey));
        pitch = 0;
        if (age - clubAt >= cd(CLUB_CD) && dist(prey) <= CLUB_R - 0.3) {
            back = phase;
            state("smash");
            sound("smash", Sound.ENTITY_IRON_GOLEM_ATTACK, 0.7f, 20);
            phase(Phase.SMASH);
        }
    }

    /** 곤봉을 치켜들었다 내려침. */
    private void smash() {
        vel.multiply(0.3);
        if (standing(prey)) face(chest(prey));
        pitch = 0;
        if (inPhase() < cd(SMASH_T)) return;
        clubAt = age;
        state("tuck");
        body.squash(1.05f, 0.92f, 1.05f, 2);
        boolean any = false;
        for (Player q : game.mobs.standing()) {
            if (dist(q) > CLUB_R) continue;
            Vector to = q.getLocation().toVector().subtract(pos).setY(0);
            double r = Math.toRadians(yaw);
            if (to.lengthSquared() > 0.5 && (to.getX() * -Math.sin(r) + to.getZ() * Math.cos(r)) < 0) continue; // 등 뒤는 안 맞음
            hit(q, q == grudge ? CLUB_GRUDGE : CLUB, "cause_g111");
            game.players.root(q, TRIP_T, "g111");
            knock(q, toward(q), 0.4, 0.15);
            any = true;
        }
        if (any) sound("smash", Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 20);
        phase(back);
    }

    /** 머리 없이 더듬더듬 머리 쪽으로. */
    private void grope() {
        Vector to = headAt.clone().setY(pos.getY());
        double d = to.distance(pos);
        if (d > 1.1) seek(to, speedNow());
        else hold();
        face(headAt);
        pitch = 0;
        body.roll = (float) (Math.sin(age * 0.25) * 5);
        if (d <= 1.3 && headV.lengthSquared() < 0.01) {
            body.roll = 0;
            hold();
            state("set");
            sound("neck", Sound.BLOCK_BONE_BLOCK_BREAK, 0.8f, 24);
            phase(Phase.SET);
        }
    }

    /** 주워 목에 꽂음(3초): 집어 들고 천천히 올려 꽂는다. */
    private void set() {
        hold();
        int t = inPhase();
        if (t == 15) {
            // 주움: 손에 들림
            dropped = false;
            flipped = game.random.nextDouble() < 0.3;
            onNeck = false;
            holdHead(TUCK, 0, 0);
            if (head != null) head.pose(new Matrix4f().translate(0f, 0.6f, 0.75f), 6);
        }
        if (t == 30) {
            onNeck = true;
            holdHead(NECK, 0, 20);
        }
        if (t < cd(SET_T)) return;
        sound("neck", Sound.BLOCK_BONE_BLOCK_BREAK, 1.0f, 24);
        onNeck = true;
        headHp = headMax;
        holdHead(NECK, 0, 2);
        state("tuck");
        prey = grudge;
        mark(prey);
        phase(Phase.HUNT);
    }

    // ------------------------------------------------------------------ 떨어진 머리

    private void drop(Damage d) {
        // 떨어지기 전 자리(손, 겨드랑이, 목)에서 굴러 나감: dropped 를 켜기 전에 잰다
        headAt.copy(headWorld());
        dropped = true;
        onNeck = false;
        bitten.clear();
        Vector dir = d.dir() != null ? d.dir().clone().setY(0) : null;
        if (dir == null || dir.lengthSquared() < 1e-4) dir = d.by() != null ? headAt.clone().subtract(d.by().getLocation().toVector()).setY(0) : null;
        if (dir == null || dir.lengthSquared() < 1e-4) dir = new Vector(game.random.nextDouble() - 0.5, 0, game.random.nextDouble() - 0.5);
        double v = ROLL_V * (0.8 + game.random.nextDouble() * 0.4);
        headV.copy(dir.normalize().multiply(v)).setY(0.25);
        headHp = headMax;
        if (head != null) head.follow = false;
        if (d.by() != null && standing(d.by())) {
            grudge = d.by();
            markAt = -100;
        }
        sound("neck", Sound.BLOCK_BONE_BLOCK_BREAK, 1.2f, 24);
        state("grope");
        mark(null);
        prey = null;
        phase(Phase.GROPE);
        poseDropped(0);
    }

    private void headTick() {
        double fl = game.mobs.flow.floor(headAt.getX(), headAt.getZ()) + HEAD_R;
        Vector prev = headAt.clone();
        headAt.add(headV);
        if (headAt.getY() <= fl) {
            headAt.setY(fl);
            headV.setY(headV.getY() < -0.08 ? -headV.getY() * 0.35 : 0);
            headV.setX(headV.getX() * ROLL_DRAG);
            headV.setZ(headV.getZ() * ROLL_DRAG);
        } else headV.setY(headV.getY() - 0.06);
        if (!game.mobs.flow.walkable(headAt.getX(), headAt.getZ())) {
            headAt.copy(prev);
            headV.setX(-headV.getX() * 0.4).setZ(-headV.getZ() * 0.4);
        }
        double hs = Math.hypot(headV.getX(), headV.getZ());
        if (hs > 0.03) {
            headSpin += (float) Math.toDegrees(hs / HEAD_R);
            if (every(3)) sound("roll", Sound.BLOCK_WOOD_HIT, 1.3f, 16);
            // 지나가며 물기
            for (Player q : game.mobs.standing()) {
                if (bitten.contains(q.getUniqueId())) continue;
                if (chest(q).add(new Vector(0, -0.6, 0)).distanceSquared(headAt) > 0.95 * 0.95) continue;
                bitten.add(q.getUniqueId());
                hit(q, HEAD_BITE, "cause_g111_head");
            }
        } else if (every(50)) sound("laugh", Sound.ENTITY_WITCH_CELEBRATE, 1.3f, 20);
        poseDropped(1);
    }

    private void poseDropped(int ticks) {
        if (head == null) return;
        // 구르는 동안은 굴러가는 쪽으로 돌고, 멈추면 쏜 사람 쪽을 보고 웃음
        float look;
        if (grudge != null && Math.hypot(headV.getX(), headV.getZ()) < 0.03) {
            Vector g = grudge.getLocation().toVector();
            look = (float) Math.toDegrees(Math.atan2(-(g.getX() - headAt.getX()), g.getZ() - headAt.getZ()));
            headSpin *= 0.7f;
        } else look = (float) Math.toDegrees(Math.atan2(-headV.getX(), headV.getZ()));
        head.pose(new Matrix4f().translate((float) (headAt.getX() - pos.getX()), (float) (headAt.getY() - pos.getY()), (float) (headAt.getZ() - pos.getZ()))
                .rotateY((float) Math.toRadians(-look)).rotateX((float) Math.toRadians(headSpin % 360f)), ticks);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), pos.getY() + 0.3, pos.getZ(), 0.62});
        out.add(new double[] {pos.getX(), pos.getY() - 0.7, pos.getZ(), 0.36});
        if (head != null && phase != Phase.SET) {
            Vector h = headWorld();
            out.add(new double[] {h.getX(), h.getY(), h.getZ(), HEAD_R});
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (head != null && phase != Phase.SET && d.at() != null) {
            Vector h = headWorld();
            double hd = d.at().distanceSquared(h);
            double bd = Math.min(d.at().distanceSquared(pos.clone().add(new Vector(0, 0.3, 0))), d.at().distanceSquared(pos.clone().add(new Vector(0, -0.7, 0))));
            boolean blast = d.type() == DamageType.EXPLOSION;
            if ((hd < bd && hd <= (HEAD_R + 0.35) * (HEAD_R + 0.35)) || (blast && hd <= 3.0 * 3.0)) {
                headHp -= d.amount();
                if (dropped && headHp <= 0) {
                    // 땅의 머리를 부숨: 몸도 쓰러짐
                    hp = 0;
                    game.mobs.onHurt(this, d, d.amount());
                    die(d);
                    return true;
                }
                game.mobs.onHurt(this, d, 0);
                if (d.by() != null) game.hud.hit(d.by(), false);
                if (!dropped && headHp <= 0) drop(d);
                if (!blast) return true;
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (grudge != null) game.hud.mark(grudge, "g111", 0);
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " head=" + Math.round(headHp) + (dropped ? " dropped" : onNeck ? " neck" : " hand")
                + (grudge != null ? " grudge=" + grudge.getName() : "");
    }
}
