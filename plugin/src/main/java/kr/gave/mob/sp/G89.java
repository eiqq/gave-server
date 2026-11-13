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
import kr.gave.world.Plaza;

/**
 * G89 조개 게이브(방패, 돌격). 꼭 닫힌 두 짝 껍데기로 미끄러져 와, 3블록 안에서 0.8초 활짝 벌리고(안쪽 얼굴이 눈을 뜸) 튀어 올라 다리를 문다.
 *
 * <p>도감: 닫힌 동안 총알 10%, 폭발과 불 40%. 벌어진 동안(벌리는 0.8초, 빗나가 휘청이는 1초, 물고 매달린 2초)은 총알 세 배. 물기 20 과 물림(2초,
 * 초당 6, 못 걸음. 연타나 벌어진 안쪽을 쏘면 풀림). 닫힌 껍데기 들이받기 8 과 밀침. 뒤쪽 경첩 막대기 둘(하나 체력 30)을 끊으면 다시 못 닫는다.
 *
 * <p>팩 계약(tools/gabe/sp/g89.py): 모델 gave:gabe/g89, 상태 "" 닫힘, "open", "bite", "broken". 부위 h1(+X 경첩 끊김), h2. 소리 clack, creak, snap, ting.
 * 원인 글귀 cause_g89.
 */
public final class G89 extends Gabe {

    static final double SPEED = 0.17, WET_SPEED = 0.24, HOVER = 0.56, OPEN_RANGE = 3.0;
    static final int OPEN_T = 16, STAGGER = 20, HANG = 40, HANG_EVERY = 20, COOLDOWN = 24, LEAP_MAX = 9, RAM_CD = 30;
    static final double LEAP_V = 0.5, BITE = 20, HANG_DMG = 6, RAM = 8;
    static final int ESCAPE_CLICKS = 6;
    /** 받는 피해 배율: 닫힘(총, 폭발과 불), 벌림(총). */
    static final double SHUT_GUN = 0.1, SHUT_BLAST = 0.4, OPEN_GUN = 3.0;
    /** 경첩: 뒤로, 옆으로 떨어진 거리, 구 반지름, 체력. */
    static final double HINGE_BACK = 0.85, HINGE_SIDE = 0.45, HINGE_R = 0.24, HINGE_HP = 30;

    enum Phase { SLIDE, OPEN, LEAP, HANG, STAGGER }

    private Phase phase = Phase.SLIDE;
    private int phaseAt, readyAt, nextClack, ramAt = -100, lastTing = -100;
    private Player prey;
    private final Vector dashDir = new Vector(0, 0, 1);
    private final double[] hinge = {HINGE_HP, HINGE_HP};
    private boolean scaled, wet;

    public G89(Game game, Species sp) {
        super(game, sp);
        radius = 0.8;
        hover = HOVER;
        baseSpeed = SPEED;
        ragKick = 0.6;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g89", 2.4f, 2.4f);
    }

    @Override
    protected void spawned() {
        nextClack = age + game.random.nextInt(30);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private boolean broken() {
        return hinge[0] <= 0 && hinge[1] <= 0;
    }

    /** 닫을 수 있으면 닫힌 상태, 경첩이 둘 다 끊겼으면 벌어진 채. */
    private void shut() {
        state(broken() ? "broken" : "");
    }

    private boolean open() {
        return phase == Phase.OPEN || phase == Phase.LEAP || phase == Phase.HANG || phase == Phase.STAGGER || broken();
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!scaled) {
            // 감독이 넣은 체력 배율을 경첩에도
            scaled = true;
            double k = sp.hp > 0 ? maxHp / sp.hp : 1;
            hinge[0] *= k;
            hinge[1] *= k;
        }
        switch (phase) {
            case SLIDE -> slide();
            case OPEN -> opening();
            case LEAP -> leap();
            case HANG -> hang();
            case STAGGER -> stagger();
        }
    }

    /** 얕은 물(웅덩이) 위인가. */
    private boolean onWater() {
        List<Vector> ps = Plaza.puddles();
        for (int i = 0; i < ps.size(); i++) {
            Vector c = ps.get(i);
            double r = Plaza.puddleRadius(i), dx = c.getX() - pos.getX(), dz = c.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    /** 껍데기를 닫은 채 미끄러져 옴. 가까울수록 '딱, 딱'이 잦다. */
    private void slide() {
        hover = HOVER;
        if (every(10)) wet = onWater();
        follow(speed() * (wet ? WET_SPEED / SPEED : 1));
        Player p = nearest(400);
        if (p == null) {
            face(pos.clone().add(vel));
            return;
        }
        face(chest(p));
        pitch = 0;
        double d = dist(p);
        if (age >= nextClack) {
            sound("clack", Sound.BLOCK_STONE_HIT, 0.7f, 20);
            body.squash(1.0f, 0.94f, 1.0f, 2);
            nextClack = age + (int) Math.max(5, Math.min(40, d * 2.2));
        } else if (age == nextClack - 3) body.squash(1f, 1f, 1f, 2);
        // 닫힌 껍데기로 들이받기
        if (age - ramAt >= cd(RAM_CD) && d > OPEN_RANGE * 0.5) {
            for (Player q : game.mobs.standing()) {
                if (!touches(q, 0.05)) continue;
                hit(q, RAM, "cause_g89");
                knock(q, toward(q), 0.5, 0.2);
                sound("snap", Sound.BLOCK_STONE_PLACE, 0.8f, 16);
                ramAt = age;
                break;
            }
        }
        if (age >= readyAt && d <= OPEN_RANGE && sees(p)) {
            prey = p;
            mark(p);
            hold();
            state(broken() ? "broken" : "open");
            sound("creak", Sound.BLOCK_WOODEN_DOOR_OPEN, 0.6f, 20);
            phase(Phase.OPEN);
        }
    }

    /** 0.8초 활짝 벌림(안쪽 얼굴이 눈을 뜸): 이때 크게 맞는다. */
    private void opening() {
        hold();
        if (!standing(prey)) {
            calm();
            return;
        }
        face(chest(prey));
        pitch = 0;
        if (inPhase() >= cd(OPEN_T)) {
            dashDir.copy(toward(prey));
            phase(Phase.LEAP);
        }
    }

    /** 벌린 채 튀어 올라 덮침. 방향은 뛰는 순간 굳는다(옆으로 한 걸음이면 빗나감). */
    private void leap() {
        int t = inPhase();
        hover = HOVER + 0.7 * Math.sin(Math.PI * Math.min(1.0, (t + 1) / (double) LEAP_MAX));
        dash(dashDir, LEAP_V);
        face(pos.clone().add(dashDir));
        pitch = 0;
        for (Player p : game.mobs.standing()) {
            if (!touches(p, 0.15)) continue;
            bite(p);
            return;
        }
        if (t >= LEAP_MAX || wallAhead(0.8)) {
            hold();
            hover = HOVER;
            phase(Phase.STAGGER);
        }
    }

    private void bite(Player p) {
        hit(p, BITE, "cause_g89");
        sound("snap", Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.2f, 16);
        hover = HOVER;
        hold();
        if (standing(p) && grab(p, ESCAPE_CLICKS)) {
            prey = p;
            mark(p);
            noPush = true;
            state(broken() ? "broken" : "bite");
            phase(Phase.HANG);
        } else phase(Phase.STAGGER);
    }

    /** 다리를 문 채 2초 매달림(초당 6, 못 걸음). 연타, 죽음, 벌어진 안쪽을 쏘면 풀림. */
    private void hang() {
        if (!standing(prey) || game.players.grabbedBy(prey) != this || inPhase() >= HANG) {
            letGo();
            phase(Phase.STAGGER);
            return;
        }
        Vector feet = prey.getLocation().toVector();
        Vector want = feet.add(toward(prey).multiply(-0.55));
        vel.copy(want.subtract(pos).setY(0));
        if (vel.length() > 0.6) vel.normalize().multiply(0.6);
        face(chest(prey));
        pitch = 0;
        int t = inPhase();
        if (t > 0 && t % HANG_EVERY == 0) {
            hit(prey, HANG_DMG, "cause_g89");
            body.squash(1.06f, 0.9f, 1.06f, 2);
        } else if (t % HANG_EVERY == 3) body.squash(1f, 1f, 1f, 3);
    }

    private void letGo() {
        if (prey != null && prey.isOnline() && game.players.grabbedBy(prey) == this) game.players.release(prey);
        noPush = false;
        body.squash(1f, 1f, 1f, 2);
    }

    /** 빗나감, 놓침: 벌린 채 1초 휘청(약점). */
    private void stagger() {
        vel.multiply(0.6);
        if (!broken()) state("open");
        body.roll = (float) (Math.sin(inPhase() * 0.9) * 7);
        if (inPhase() >= STAGGER) {
            body.roll = 0;
            calm();
        }
    }

    private void calm() {
        mark(null);
        prey = null;
        noPush = false;
        hover = HOVER;
        shut();
        readyAt = age + cd(COOLDOWN);
        phase(Phase.SLIDE);
    }

    // ------------------------------------------------------------------ 맞음

    private Vector hingeAt(int i) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        double side = i == 0 ? HINGE_SIDE : -HINGE_SIDE;
        return new Vector(pos.getX() - fx * HINGE_BACK + fz * side, pos.getY(), pos.getZ() - fz * HINGE_BACK - fx * side);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), radius});
        for (int i = 0; i < 2; i++) {
            if (hinge[i] <= 0) continue;
            Vector h = hingeAt(i);
            out.add(new double[] {h.getX(), h.getY(), h.getZ(), HINGE_R});
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (gun && d.at() != null) {
            // 맞은 점이 몸 가운데보다 경첩 구에 가까우면 그 경첩이 깎임
            double body2 = d.at().distanceSquared(pos);
            for (int i = 0; i < 2; i++) {
                if (hinge[i] <= 0) continue;
                double h2 = d.at().distanceSquared(hingeAt(i));
                if (h2 < body2 && h2 <= (HINGE_R + 0.45) * (HINGE_R + 0.45)) {
                    hinge[i] -= d.amount();
                    game.mobs.onHurt(this, d, 0);
                    if (d.by() != null) game.hud.hit(d.by(), false);
                    if (hinge[i] <= 0) cut(i);
                    return true;
                }
            }
        }
        double k;
        if (open()) k = gun ? OPEN_GUN : 1.0;
        else k = gun ? SHUT_GUN : (t == DamageType.EXPLOSION || t == DamageType.FIRE) ? SHUT_BLAST : 1.0;
        if (gun && !open() && age - lastTing >= 4) {
            lastTing = age;
            sound("ting", Sound.BLOCK_STONE_HIT, 1.6f, 20);
        }
        // 물고 매달린 동안 벌어진 안쪽을 쏘면 놓는다
        if (gun && phase == Phase.HANG) {
            letGo();
            phase(Phase.STAGGER);
        }
        return super.damage(k == 1.0 ? d : d.times(k));
    }

    private void cut(int i) {
        body.look.on.add(i == 0 ? "h1" : "h2");
        sound("creak", Sound.ENTITY_ITEM_BREAK, 0.7f, 20);
        if (broken() && (phase == Phase.SLIDE)) state("broken");
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (broken() ? " broken" : "") + " hinge=" + Math.round(Math.max(0, hinge[0])) + "/"
                + Math.round(Math.max(0, hinge[1]));
    }
}
