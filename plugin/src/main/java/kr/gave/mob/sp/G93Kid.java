package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G93 피리 부는 게이브를 따르는 얼굴(0.5블록, 막대기 다리 둘). 피리 부는 것({@link G93})이 지나온 길을 한 줄로 발맞춰 따르며 박자에 고개를 까딱이고,
 * 피리 부는 것이 보내면 그 사람에게 달려들어 문다(5). 피리 부는 것이 죽으면 2초 멈칫한 뒤 낱개로 덤빈다.
 *
 * <p>같은 종(G93)으로 센다(이름표, 처치 기록). 감독이 내지 않고 G93 이 첫 틱에 낸다. 보상은 마리당 1원 x 감독 보상 배율.
 */
public final class G93Kid extends Gabe {

    static final double HOVER = 0.55, SPEED = 0.2, DASH = 0.36, BITE = 5;
    static final int DASH_MAX = 40, LOST = 40, BITE_CD = 20;

    enum Phase { MARCH, DASH, BACK, LOST, FREE }

    private final G93 piper;
    private final int slot;
    private Phase phase = Phase.MARCH;
    private int phaseAt, biteAt = -100;
    private Player prey;
    private boolean nod;

    G93Kid(Game game, Species sp, G93 piper, int slot) {
        super(game, sp);
        this.piper = piper;
        this.slot = slot;
        radius = 0.28;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true; // 갈라지면 피리 부는 것이 하나 더 생기므로 빼 둠
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g93_kid", 0.6f, 1f);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 줄에서 놀고 있어 보낼 수 있나. */
    boolean idle() {
        return phase == Phase.MARCH && !dead;
    }

    /** 그 사람에게 달려들게 함. */
    void attack(Player p) {
        if (!idle() || !standing(p)) return;
        prey = p;
        mark(p);
        state("bite");
        phase(Phase.DASH);
    }

    /** 피리 부는 것이 죽음: 박자를 잃고 멈칫. */
    void orphan() {
        if (dead) return;
        prey = null;
        mark(null);
        state("");
        body.look.freeze = true;
        phase(Phase.LOST);
    }

    @Override
    public void tick() {
        if (phase != Phase.LOST && phase != Phase.FREE && (piper.dead || piper.removed)) orphan();
        switch (phase) {
            case MARCH -> march();
            case DASH -> dash();
            case BACK -> back();
            case LOST -> lost();
            case FREE -> free();
        }
    }

    /** 피리 부는 것이 지나온 길의 제 자리로(발맞춰), 박자에 고개 까딱. */
    private void march() {
        Vector at = piper.kidPoint(slot);
        at.setY(pos.getY());
        Vector to = at.subtract(pos).setY(0);
        double l = to.length();
        if (l < 0.15) hold();
        else vel.copy(to.multiply(Math.min(Math.max(speed(), l * 0.35), 0.4) / l));
        if (l > 0.3) face(pos.clone().add(vel));
        else yaw = piper.yaw;
        if (piper.beat()) {
            nod = !nod;
            body.squash(1f, nod ? 0.86f : 1f, 1f, 3);
        }
    }

    private void dash() {
        Player p = prey;
        if (!standing(p) || inPhase() > DASH_MAX) {
            home();
            return;
        }
        Vector c = chest(p);
        face(c);
        Vector to = c.clone().subtract(pos).setY(0);
        double l = to.length();
        vel.copy(l < 1e-3 ? new Vector() : to.multiply(DASH * Math.max(0.7, speed() / baseSpeed) / l));
        if (touches(p, 0.15)) {
            hit(p, BITE, "cause_g93");
            sound("bite", Sound.ENTITY_SLIME_ATTACK, 1.4f, 16);
            body.squash(1.2f, 0.8f, 1.2f, 2);
            home();
        }
    }

    private void home() {
        prey = null;
        mark(null);
        state("");
        phase(Phase.BACK);
    }

    private void back() {
        Vector at = piper.kidPoint(slot);
        at.setY(pos.getY());
        Vector to = at.subtract(pos).setY(0);
        double l = to.length();
        if (l < 0.6 || inPhase() > 80) {
            phase(Phase.MARCH);
            return;
        }
        vel.copy(to.multiply(Math.min(DASH, l) / l));
        face(pos.clone().add(vel));
    }

    private void lost() {
        hold();
        if (inPhase() >= LOST) {
            body.look.freeze = false;
            body.squash(1f, 1f, 1f, 2);
            phase(Phase.FREE);
        }
    }

    /** 낱개로 덤빔. */
    private void free() {
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        if (target != p) mark(p);
        if (dist(p) > 8) follow(speed() * 1.2);
        else {
            seek(chest(p), DASH * 0.8);
            face(chest(p));
        }
        if (age - biteAt >= cd(BITE_CD) && touches(p, 0.15)) {
            biteAt = age;
            state("bite");
            hit(p, BITE, "cause_g93");
            sound("bite", Sound.ENTITY_SLIME_ATTACK, 1.4f, 16);
        } else if (age - biteAt > 8) state("");
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(1);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.28});
        return out;
    }

    @Override
    public String toString() {
        return super.toString() + " kid" + slot + " ph=" + phase;
    }
}
