package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;

/**
 * G162 덮쳐 앉는 게이브(떼, 돌격). 메뚜기 다리로 폴짝폴짝 다가온다(HOP_LEN 블록 뜀, REST 틱 쉼). 서 있는 사람 POUNCE_R 안에 들면 납작 웅크려
 * CROUCH 틱 부르르 떨며(다리 넷이 얼굴 위로 솟음, 표적 쪽 눈 뜸, '끼이익 통') 그 사람 발 자리를 굳히고, 포물선으로 LEAP 틱 날아 그 자리에
 * 떨어진다(떨어질 자리에 둥근 그림자). 뛴 뒤에는 방향을 못 바꾼다.
 *
 * <p>덮치기: 떨어진 자리 LAND_R 안 사람에게 POUNCE 피해와 깔고 앉기(붙잡힘: 못 걷고 못 쏨, 돕기 연타 ESCAPE 번으로 탈출). 앉은 동안 THUMP_EVERY
 * 틱마다 THUMP(위에 하나라도 더 올라앉았으면 + THUMP_STACK) 찧음. 이미 다른 덮쳐 앉는 게이브에 깔린 사람에게 떨어지면 그 위에 올라앉아 탑이 된다(둘까지).
 * 깔고 앉은 것을 동료가 쏘면(한 발) 바로 풀리고, 빗나가면 엉덩방아를 찧고 SIT 틱 버둥거린다.
 *
 * <p>도감과 다른 점: 둘이 앉으면 0.5초마다 7 = 아래 것이 THUMP 4 + 위에 앉은 것이 있으면 3 으로 셈(셋째부터는 얹히기만)(붙잡기는 한 사람에 하나라 위에 앉은 것은 붙잡지
 * 않고 아래 것에 얹힘).
 *
 * <p>팩 계약(tools/gabe/sp/g162.py): 상태 "", "crouch", "leap", "sit", 부품 모델 gabe/g162_shadow, 소리 leap(20), thump(16), hop(12),
 * 글귀 cause_g162.
 */
public final class G162 extends Gabe implements Detachable {

    static final double HOVER = 0.5, RADIUS = 0.42;
    /** 깡충: 한 번 거리, 높이, 뛰는 틱, 쉬는 틱(박자 배율). */
    static final double HOP_LEN = 2.0, HOP_H = 0.8;
    static final int HOP = 10, REST = 10;
    /** 덮치기: 거리, 웅크림, 나는 틱(박자 배율, 아래 LEAP_MIN), 높이, 떨어진 자리 닿는 거리. */
    static final double POUNCE_R = 9, LEAP_H = 3.4, LAND_R = 1.15;
    static final int CROUCH = 12, LEAP = 30, LEAP_MIN = 18;
    /** 피해: 덮치기, 찧기, 위에 얹힌 하나마다 더함. 찧는 간격, 탈출 연타. */
    static final double POUNCE = 8, THUMP = 4, THUMP_STACK = 3;
    static final int THUMP_EVERY = 10, ESCAPE = 6, STACK_MAX = 2;
    /** 엉덩방아(빗나감), 떨어진 뒤 버둥. 앉는 높이(사람 발 위), 얹히는 높이. */
    static final int SIT = 16, RECOVER = 24;
    static final double SEAT_UP = 2.2, STACK_UP = 0.8;

    enum Phase { HOP, REST, CROUCH, LEAP, SIT, PIN, STACK }

    private Phase phase = Phase.REST;
    private int phaseAt, leapT;
    private final Vector from = new Vector(), dest = new Vector();
    private Player victim;
    /** STACK 이면 아래의 덮쳐 앉는 게이브, PIN 이면 위에 얹힌 것들. */
    private G162 base;
    private final List<G162> stack = new ArrayList<>();
    private Body.Part shadow;
    private int thumpAt;

    public G162(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = 0.2;
    }

    @Override
    protected void spawned() {
        phaseAt = -game.random.nextInt(REST);
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
        switch (phase) {
            case HOP -> hopTick();
            case REST -> restTick();
            case CROUCH -> crouchTick();
            case LEAP -> leapTick();
            case SIT -> sitTick();
            case PIN -> pinTick();
            case STACK -> stackTick();
        }
    }

    private void restTick() {
        hold();
        hover = HOVER;
        if (!"".equals(state)) state("");
        Player p = nearest(400);
        if (p != null) {
            face(chest(p));
            pitch = 0;
        }
        if (inPhase() < cd(REST)) return;
        if (p != null && dist(p) <= POUNCE_R && sees(p)) {
            mark(p);
            victim = p;
            state("crouch");
            sound("leap", Sound.BLOCK_PISTON_EXTEND, 1.4f, 20);
            phase(Phase.CROUCH);
            return;
        }
        // 다음 깡충: 가까우면 사람 쪽, 멀면 흐름장 쪽
        Vector dir = p != null && dist(p) < 20 ? toward(p) : game.mobs.flow.dir(pos).setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        double reach = HOP_LEN * speed() / baseSpeed;
        from.copy(pos);
        dest.copy(pos).add(dir.multiply(reach));
        if (!game.mobs.flow.walkable(dest.getX(), dest.getZ())) dest.copy(pos);
        if (target != null && (p == null || dist(p) > POUNCE_R * 1.5)) mark(null);
        phase(Phase.HOP);
    }

    private void hopTick() {
        int k = inPhase();
        double t = Math.min(1, (k + 1) / (double) HOP);
        Vector step = dest.clone().subtract(from).setY(0).multiply(1.0 / HOP);
        vel.setX(step.getX()).setY(0).setZ(step.getZ());
        hover = HOVER + 4 * HOP_H * t * (1 - t);
        if (step.lengthSquared() > 1e-6) {
            face(pos.clone().add(step));
            pitch = 0;
        }
        if (k + 1 >= HOP) {
            hover = HOVER;
            if (every(2)) sound("hop", Sound.ENTITY_SLIME_JUMP_SMALL, 1.4f, 12);
            phase(Phase.REST);
        }
    }

    private void crouchTick() {
        hold();
        Player p = victim;
        if (p == null || !standing(p)) {
            state("");
            phase(Phase.REST);
            return;
        }
        face(chest(p));
        pitch = 0;
        if (inPhase() < cd(CROUCH)) return;
        // 그 사람 발 자리를 굳히고 뜀(뛴 뒤에는 방향을 못 바꿈)
        from.copy(pos);
        Location l = p.getLocation();
        dest.setX(l.getX()).setZ(l.getZ()).setY(game.mobs.flow.floor(l.getX(), l.getZ()) + HOVER);
        leapT = Math.max(LEAP_MIN, cd(LEAP));
        state("leap");
        flying = true;
        noPush = true;
        showShadow(true);
        phase(Phase.LEAP);
    }

    private void leapTick() {
        int k = inPhase();
        double t = Math.min(1, (k + 1) / (double) leapT);
        double x = from.getX() + (dest.getX() - from.getX()) * t, z = from.getZ() + (dest.getZ() - from.getZ()) * t;
        double y = from.getY() + (dest.getY() - from.getY()) * t + 4 * LEAP_H * t * (1 - t);
        vel.setX(x - pos.getX()).setY(y - pos.getY()).setZ(z - pos.getZ());
        if (k % 2 == 0) showShadow(true);
        if (k + 1 < leapT) return;
        land();
    }

    /** 떨어짐: 자리 곁 사람을 깔고 앉거나, 이미 깔린 사람이면 위에 얹힘, 아니면 엉덩방아. */
    private void land() {
        showShadow(false);
        flying = false;
        noPush = false;
        hover = HOVER;
        sound("thump", Sound.ENTITY_SLIME_SQUISH, 0.8f, 16);
        body.squash(1.3f, 0.7f, 1.3f, 3);
        Player hitP = null;
        double bd = LAND_R * LAND_R;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - dest.getX(), dz = l.getZ() - dest.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 <= bd) {
                bd = d2;
                hitP = p;
            }
        }
        if (hitP != null) {
            hit(hitP, POUNCE, "cause_g162");
            Gabe holder = game.players.grabbedBy(hitP);
            if (holder instanceof G162 b && b.phase == Phase.PIN && b.victim == hitP && b.stack.size() < STACK_MAX) {
                base = b;
                b.stack.add(this);
                victim = hitP;
                startSeat(Phase.STACK);
                return;
            }
            if (holder == null && grab(hitP, ESCAPE)) {
                victim = hitP;
                game.players.get(hitP).grabPin = true;
                mark(hitP);
                thumpAt = age + cd(THUMP_EVERY);
                startSeat(Phase.PIN);
                return;
            }
        }
        victim = null;
        state("sit");
        phase(Phase.SIT);
    }

    private void startSeat(Phase p) {
        flying = true;
        noPush = true;
        state("sit");
        phase(p);
    }

    private void sitTick() {
        hold();
        hover = HOVER;
        if (inPhase() >= SIT) {
            state("");
            phase(Phase.REST);
        }
    }

    /** 깔고 앉음: 사람 머리 위에 붙어 고개를 젖혀 내려다보며 찧음. */
    private void pinTick() {
        Player p = victim;
        if (p == null || !standing(p) || game.players.grabbedBy(p) != this) {
            leave(RECOVER);
            return;
        }
        Location l = p.getLocation();
        Vector seat = new Vector(l.getX(), l.getY() + SEAT_UP, l.getZ());
        vel.copy(seat.subtract(pos));
        yaw = l.getYaw() + 180f;
        pitch = 55f;
        stack.removeIf(s -> s.dead || s.phase != Phase.STACK || s.base != this);
        if (age >= thumpAt) {
            thumpAt = age + cd(THUMP_EVERY);
            hit(p, THUMP + THUMP_STACK * Math.min(1, stack.size()), "cause_g162");
            sound("thump", Sound.ENTITY_SLIME_SQUISH, 0.9f, 16);
            body.squash(1.2f, 0.8f, 1.2f, 2);
        }
    }

    /** 다른 것 위에 얹힘. */
    private void stackTick() {
        G162 b = base;
        if (b == null || b.dead || b.phase != Phase.PIN) {
            leave(SIT);
            return;
        }
        int idx = Math.max(0, b.stack.indexOf(this));
        Vector seat = b.pos.clone().add(new Vector(0, STACK_UP * (idx + 1), 0));
        vel.copy(seat.subtract(pos));
        yaw = b.yaw + (idx % 2 == 0 ? 35f : -35f);
        pitch = 40f;
    }

    /** 사람 위에서 떨어져 내려와 버둥. */
    private void leave(int sitTicks) {
        Player v = victim;
        boolean pinned = phase == Phase.PIN;
        victim = null;
        phase(Phase.SIT);
        if (pinned && v != null && game.players.grabbedBy(v) == this) game.players.release(v);
        if (base != null) base.stack.remove(this);
        base = null;
        flying = false;
        noPush = false;
        hover = HOVER;
        pitch = 0;
        mark(null);
        state("sit");
        phase(Phase.SIT);
        phaseAt = age - (SIT - sitTicks);
    }

    // ------------------------------------------------------------------ 떼기, 맞음, 죽음

    @Override
    public void detached(Player p, Player helper, String how) {
        if (phase == Phase.PIN && p == victim) {
            victim = null;
            leave(RECOVER);
        }
    }

    @Override
    public boolean damage(Damage d) {
        boolean seated = phase == Phase.PIN || phase == Phase.STACK;
        Player v = victim;
        boolean ok = super.damage(d);
        // 동료가 쏘면 한 발에 풀림
        if (ok && !dead && seated && d.by() != null && d.by() != v) leave(RECOVER);
        return ok;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        showShadow(false);
        if (base != null) base.stack.remove(this);
        super.die(d);
    }

    private void showShadow(boolean show) {
        if (show && shadow == null && body != null) {
            shadow = body.attach("gabe/g162_shadow");
            if (shadow != null) shadow.follow = false;
        }
        if (shadow == null) return;
        if (!show) {
            shadow.remove();
            shadow = null;
            return;
        }
        Matrix4f m = new Matrix4f().translate((float) (dest.getX() - pos.getX()), (float) (dest.getY() - HOVER + 0.03 - pos.getY()),
                (float) (dest.getZ() - pos.getZ()));
        shadow.pose(m, 1);
    }

    @Override
    public String toString() {
        return super.toString() + " phase=" + phase + (victim != null ? " on=" + victim.getName() : "") + " stack=" + stack.size();
    }
}
