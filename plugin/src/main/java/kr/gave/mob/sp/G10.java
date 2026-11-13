package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G10 쪼개지는 게이브(떼). 얼굴 한가운데 세로 바늘땀. 온전할 땐 G01 처럼 겨눴다 돌진(4 피해). 맞으면 '지이익' 이음선을 따라 반쪽 얼굴 둘로 갈라지고,
 * 반쪽들은 반대로 돌아 표적의 양옆에서 덮친다(2 피해). 반쪽이 다시 맞으면 눈 반쪽만 남은 살 조각 둘로(한 마리 최대 넷), 조각은 자벌레처럼 기어
 * 발목을 물고(1 피해, 느려짐) 3초 뒤 서로 찾아가 다시 꿰매 붙는다('끼익': 조각 둘 → 반쪽, 반쪽 둘 → 온전).
 * 한 틱에 받은 피해가 그 조각 최대 체력의 45% 를 넘으면(저격, 근거리 산탄) 쪼개지지 않고 죽는다. 불과 폭발은 쪼개지 않고 태운다.
 *
 * <p>보상: 처음 개체(6원)만 보상을 갖고 쪼개진 조각은 보상 0(엔진 child). 처음 개체가 다른 조각에 꿰매 붙어 사라지면 보상을 그 조각에 넘긴다.
 * 쪼개짐은 디스플레이 예산(child) 안에서만.
 *
 * <p>팩 계약(tools/gabe/sp/g10.py): 모델 {@code gave:gabe/g10} 상태 {@code ""}, {@code "half_l"}, {@code "half_r"}, {@code "chunk_a"},
 * {@code "chunk_b"}. 소리 zip, creak, bite. 글귀 cause_g10.
 */
public final class G10 extends Gabe {

    static final double KILL_FRAC = 0.45;
    /** 단계별 빠르기, 높이, 반지름, 피해. */
    static final double[] SPEED = {0.16, 0.17, 0.075}, HOVER = {0.85, 0.85, 0.15}, RAD = {0.42, 0.3, 0.2}, DMG = {5, 3, 2};
    /** 온전: 겨눔 거리와 틱, 돌진 빠르기와 최대 틱. 반쪽: 옆으로 도는 거리, 덮침 빠르기. */
    static final double AIM_RANGE = 7, DASH_V = 0.5, FLANK = 2.6, POUNCE_V = 0.45;
    static final int AIM = 14, DASH = 18, REST = 24, POUNCE = 14;
    /** 조각이 서로 찾기까지, 반쪽이 다시 붙기까지(쪼개진 뒤 틱), 찾는 반경, 붙는 거리. */
    static final int CHUNK_JOIN = 60, HALF_JOIN = 140;
    static final double JOIN_R = 10, JOIN_TOUCH = 0.6;

    enum Phase { MOVE, AIM, DASH, REST, FLANK, POUNCE, JOIN }

    private int stage, side = 1;
    /** 처음 온전한 개체의 최대 체력(한 번에 죽는 문턱의 바탕, 조각도 같은 값을 물려받음). */
    private double rootMax = -1;
    private Phase phase = Phase.MOVE;
    private int phaseAt, splitAt, lastBite = -100, unsquashAt = -1;
    private Player prey;
    private final Vector dashDir = new Vector();
    private long dmgTick = -1;
    private double dmgSum;
    private boolean splitPending;

    public G10(Game game, Species sp) {
        super(game, sp);
        apply();
    }

    private void apply() {
        radius = RAD[stage];
        hover = HOVER[stage];
        baseSpeed = SPEED[stage];
        noPush = stage == 2;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g10", 3.0f, 1.3f);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private void become(int st, int sd) {
        stage = st;
        side = sd;
        apply();
        state(st == 0 ? "" : st == 1 ? (sd > 0 ? "half_l" : "half_r") : (sd > 0 ? "chunk_b" : "chunk_a"));
        body.size(st == 0 ? 3.0f : st == 1 ? 1.6f : 0.8f, st == 2 ? 0.6f : 1.3f);
        splitAt = age;
        mark(null);
        prey = null;
        // 겨누던 펄럭임 빠르기, 물며 찌그러진 몸을 다음 단계로 끌고 가지 않음
        body.look.rate = 1.0;
        body.squash(1f, 1f, 1f, 2);
        unsquashAt = -1;
        phase(Phase.MOVE);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (splitPending) {
            splitPending = false;
            if (!dead) split();
            if (dead) return;
        }
        // 물며 찌그러진 몸 되돌리기(틱을 건너뛰어도 다음에 꼭)
        if (unsquashAt >= 0 && age >= unsquashAt) {
            body.squash(1f, 1f, 1f, 4);
            unsquashAt = -1;
        }
        if (prey != null && !standing(prey)) {
            prey = null;
            mark(null);
            if (phase != Phase.JOIN) phase(Phase.MOVE);
        }
        if (stage > 0 && joinCheck()) return;
        switch (stage) {
            case 0 -> whole();
            case 1 -> half();
            default -> chunk();
        }
    }

    /** 온전: 다가와 겨눴다 일직선 돌진. */
    private void whole() {
        switch (phase) {
            case MOVE -> {
                follow(speed());
                Player p = nearest(40);
                face(p != null ? chest(p) : pos.clone().add(vel));
                if (p != null && age >= phaseAt && dist(p) <= AIM_RANGE && sees(p)) {
                    prey = p;
                    mark(p);
                    hold();
                    phase(Phase.AIM);
                }
            }
            case AIM -> {
                hold();
                face(chest(prey));
                // 겨누는 동안 펄럭임이 빨라짐(0.25 계단: 틱마다 아이템을 다시 싣지 않게)
                body.look.rate = 1.0 + 0.25 * (4 * inPhase() / cd(AIM));
                if (inPhase() >= cd(AIM)) {
                    dashDir.copy(toward(prey));
                    body.look.rate = 2.0;
                    phase(Phase.DASH);
                }
            }
            case DASH -> {
                dash(dashDir, DASH_V * speed() / baseSpeed);
                face(pos.clone().add(dashDir));
                if (bite(dashDir) || inPhase() >= DASH || wallAhead(0.6)) {
                    body.look.rate = 1.0;
                    hold();
                    phase(Phase.REST);
                }
            }
            case REST -> {
                vel.multiply(0.75);
                if (inPhase() >= cd(REST)) {
                    mark(null);
                    prey = null;
                    phase(Phase.MOVE);
                }
            }
            default -> phase(Phase.MOVE);
        }
    }

    /** 반쪽: 표적의 제 쪽 옆(게이브 왼쪽 반쪽은 마주 본 표적의 오른쪽)으로 돌아가 옆에서 덮침. */
    private void half() {
        Player p = prey != null ? prey : nearest(40);
        if (p == null) {
            follow(speed());
            return;
        }
        switch (phase) {
            case MOVE, FLANK -> {
                if (prey == null && dist(p) <= 14 && sees(p)) {
                    prey = p;
                    mark(p);
                }
                Vector c = chest(p);
                Vector look = p.getLocation().getDirection().setY(0);
                if (look.lengthSquared() < 1e-6) look = toward(p).multiply(-1);
                look.normalize();
                // (look.z, 0, -look.x) 는 사람의 왼쪽. side 1(게이브 왼쪽 반쪽)은 마주 본 사람의 오른쪽으로 돈다
                Vector sideV = new Vector(look.getZ(), 0, -look.getX()).multiply(-side * FLANK);
                Vector spot = c.clone().add(sideV);
                Vector d = spot.clone().subtract(pos).setY(0);
                double l = d.length();
                if (dist(p) > 12) follow(speed());
                else vel.copy(l < 1e-6 ? d : d.multiply(Math.min(speed() * 1.2, l) / l));
                face(c);
                if (prey != null && (l < 0.8 || inPhase() > 60) && dist(p) < 4.5) {
                    dashDir.copy(toward(p));
                    phase(Phase.POUNCE);
                }
            }
            case POUNCE -> {
                dash(dashDir, POUNCE_V * speed() / baseSpeed);
                face(chest(p));
                if (bite(dashDir) || inPhase() >= POUNCE || wallAhead(0.5)) {
                    hold();
                    phase(Phase.REST);
                }
            }
            case REST -> {
                vel.copy(toward(p).multiply(-speed() * 0.6));
                if (inPhase() >= cd(REST)) phase(Phase.FLANK);
            }
            default -> phase(Phase.MOVE);
        }
    }

    /** 조각: 자벌레처럼 기어 발목을 묾. */
    private void chunk() {
        Player p = nearest(24);
        if (p == null) {
            follow(speed());
            return;
        }
        vel.copy(toward(p).multiply(speed()));
        face(chest(p).setY(pos.getY()));
        if (age - lastBite >= 20 && touches(p, 0.1)) {
            lastBite = age;
            hit(p, DMG[2], "cause_g10");
            game.players.slow(p, 30, 30, "g10_ankle");
            sound("bite", Sound.ENTITY_SLIME_ATTACK, 1.5f, 14);
            body.squash(1.2f, 0.7f, 1.3f, 1);
            unsquashAt = age + 2;
        }
    }

    /** 닿으면 물기(피해, 밀침). 닿았으면 true. */
    private boolean bite(Vector dir) {
        for (Player q : game.mobs.standing()) {
            if (!touches(q, 0.05)) continue;
            hit(q, DMG[stage], "cause_g10");
            knock(q, dir, stage == 0 ? 0.5 : 0.3, 0.15);
            sound("bite", Sound.ENTITY_SLIME_ATTACK, 1.2f, 14);
            body.squash(1.3f, 0.75f, 0.8f, 2);
            unsquashAt = age + 3;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 쪼개짐, 다시 붙음

    /** 맞은 다음 틱: 반쪽 둘이나 조각 둘로(예산이 차면 그대로). */
    private void split() {
        if (stage >= 2) return;
        int next = stage + 1;
        double each = Math.max(1, hp / 2);
        Vector l = left().multiply(next == 1 ? 0.28 : 0.18);
        Gabe c = child(sp, pos.clone().subtract(l));
        if (!(c instanceof G10 o)) {
            if (c != null) c.remove();
            return;
        }
        sound("zip", Sound.ENTITY_SLIME_SQUISH, 1.6f, 20);
        o.rootMax = rootMax();
        o.maxHp = maxHp / 2;
        o.hp = each;
        o.yaw = yaw;
        o.become(next, -1);
        maxHp = maxHp / 2;
        hp = each;
        pos.add(l);
        become(next, 1);
        // 반쪽들은 서로 반대로 돌아섬
        yaw += 70;
        o.yaw -= 70;
        body.pose(2);
        o.body.pose(2);
    }

    /** 쪼개진 뒤 얼마 지나면 같은 단계 조각을 찾아가 꿰매 붙음. 붙었으면 true(이 틱 끝). */
    private boolean joinCheck() {
        int wait = stage == 2 ? CHUNK_JOIN : HALF_JOIN;
        if (age - splitAt < wait) return false;
        G10 mate = null;
        double best = JOIN_R * JOIN_R;
        for (Gabe g : game.mobs.near(pos, JOIN_R, x -> x != this && x instanceof G10 o && !o.dead && o.stage == stage)) {
            G10 o = (G10) g;
            if (stage == 1 && o.side == side) continue;
            double d = o.pos.distanceSquared(pos);
            if (d < best) {
                best = d;
                mate = o;
            }
        }
        if (mate == null) return false;
        if (phase != Phase.JOIN) {
            phase(Phase.JOIN);
            mark(null);
            prey = null;
        }
        Vector d = mate.pos.clone().subtract(pos).setY(0);
        double l = d.length();
        face(mate.pos);
        if (l > JOIN_TOUCH) {
            vel.copy(d.multiply(Math.min(speed() * 1.3, l) / l));
            return true;
        }
        if (id < mate.id) join(mate);
        else hold();
        return true;
    }

    /** mate 를 꿰매 붙여 한 단계 큰 몸이 됨. */
    private void join(G10 mate) {
        double sum = hp + mate.hp;
        double mx = maxHp + mate.maxHp;
        if (!mate.noReward && mate.reward > reward) {
            noReward = false;
            reward = mate.reward;
        }
        pos.add(mate.pos).multiply(0.5);
        mate.remove();
        sound("creak", Sound.BLOCK_WOODEN_DOOR_OPEN, 1.6f, 16);
        int next = stage - 1;
        int sd = 1;
        if (next == 1) {
            Gabe other = game.mobs.nearest(pos, JOIN_R, x -> x != this && x instanceof G10 o && !o.dead && o.stage == 1);
            if (other instanceof G10 o) sd = -o.side;
        }
        become(next, sd);
        maxHp = mx;
        hp = Math.min(sum, mx);
        // 꿰매 붙으며 오므렸다 펴짐(같은 틱에 둘을 부르면 뒤의 것만 남으니 되돌리기는 tick() 에)
        body.squash(0.8f, 1.2f, 0.8f, 1);
        unsquashAt = age + 2;
    }

    private double rootMax() {
        if (rootMax <= 0) rootMax = maxHp;
        return rootMax;
    }

    private Vector left() {
        double r = Math.toRadians(yaw);
        return new Vector(Math.cos(r), 0, Math.sin(r));
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        long now = game.now();
        if (now != dmgTick) {
            dmgTick = now;
            dmgSum = 0;
        }
        dmgSum += d.amount();
        DamageType t = d.type();
        boolean burn = t == DamageType.EXPLOSION || t == DamageType.FIRE;
        if (!burn && t != DamageType.BITE && dmgSum >= KILL_FRAC * rootMax()) {
            // 한 번에 크게 맞음: 쪼개지지 않고 죽음
            splitPending = false;
            double before = hp;
            hp = 0;
            game.mobs.onHurt(this, d, before);
            die(d);
            return true;
        }
        boolean ok = super.damage(d);
        if (ok && !dead && !burn && t != DamageType.BITE && stage < 2) splitPending = true;
        return ok;
    }

    public String phaseName() {
        return "stage=" + stage + " side=" + side + " " + phase;
    }

    @Override
    public String toString() {
        return super.toString() + " stage=" + stage + " side=" + side + " ph=" + phase;
    }

    /** 시험용: 이 개체와 같은 무리(근처 G10). */
    List<G10> family() {
        List<G10> out = new ArrayList<>();
        for (Gabe g : game.mobs.near(pos, 30, x -> x instanceof G10)) out.add((G10) g);
        return out;
    }
}
