package kr.gave.mob.sp;

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
 * G80 춤추는 굴렁쇠 게이브(방해, 떼, 1막). 얼굴 여섯 굴렁쇠가 빠르게 굴러와 사람 다리를 치고(8) 튕겨 나갔다가 다시 굴러온다. 치인 사람은 '춤' 6초
 * (조준이 작은 원을 그리며 흔들리고 걸음이 옆으로 흐름, {@link G80Dance}), 춤추는 사람 1.5블록 안에 1초 넘게 머문 동료에게 옮는다.
 *
 * <p>대처: 가운데 얼굴(탄 길이 가운데 0.3블록 안을 지나면) 받는 피해 x2. 춤추는 동료와 떨어져 선다.
 *
 * <p>팩(tools/gabe/sp/g80.py): 모델 {@code gave:gabe/g80} 상태 "roll"(기본, 4자세로 돎: 빠르기는 body.look.rate), "bounce"(튕김).
 * 소리 roll(굴렁쇠 굴러옴, 20), bonk(쿵, 16), dance(깔깔, 16). 글귀 cause_g80. HUD 표시 종 키 g80(춤 걸린 사람에게).
 */
public final class G80 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.25;
    /** 이 블록 안, 보이면 일직선으로 굴러 듦. 굴러 드는 빠르기, 최대 틱, 틀 수 있는 각(도/틱). */
    static final double CHARGE_RANGE = 8, CHARGE_V = 0.42;
    static final int CHARGE_MAX = 30;
    static final float CHARGE_TURN = 4f;
    /** 치기 피해, 밀침, 튕겨 나감(틱, 빠르기, 높이), 다시 오기까지 쉬는 틱. */
    static final double HIT_DMG = 8, PUSH = 0.5, BOUNCE_V = 0.32, BOUNCE_H = 0.9;
    static final int BOUNCE = 14, REST = 30;
    /** 가운데 얼굴(약점) 반경과 배율, 굴렁쇠 맞는 구 반지름. */
    static final double HUB_R = 0.32, HUB_MUL = 2.0, HOOP_R = 0.8;
    /** 팩의 구름 4자세 8틱 한 바퀴가 겉으로 구르는 빠르기(블록/틱): 굴렁쇠 바깥 0.8 x 7.5도/틱. */
    static final double ROLL_SURF = 0.105;

    enum Phase { APPROACH, CHARGE, BOUNCE, REST }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, readyAt, nextRoll;
    private Player prey;
    private final Vector dir = new Vector(0, 0, 1);

    public G80(Game game, Species sp) {
        super(game, sp);
        radius = 0.7;
        baseSpeed = SPEED;
        state("roll");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g80", 1.7f, 1.7f);
    }

    @Override
    protected void spawned() {
        nextRoll = age + game.random.nextInt(12);
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
        G80Dance.tick(game);
        switch (phase) {
            case APPROACH -> approach();
            case CHARGE -> charge();
            case BOUNCE -> bounce();
            case REST -> rest();
        }
        rolling();
    }

    /** 겉으로 구르는 빠르기에 맞춰 바퀴 도는 빠르기(주기 칸이 몇 개뿐이라 세 단계로만), 달그락 소리. */
    private void rolling() {
        double v = Math.hypot(vel.getX(), vel.getZ());
        double rate = v < 0.03 ? 0.5 : v < 0.15 ? 1.0 : 2.0;
        if (body.look.rate != rate) body.look.rate = rate;
        if (v > 0.05 && age >= nextRoll) {
            sound("roll", Sound.BLOCK_BAMBOO_WOOD_STEP, 1.4f, 20);
            nextRoll = age + 8 + game.random.nextInt(8);
        }
    }

    /** 흐름장을 따라 굴러옴(굴렁쇠 면이 진행 쪽). */
    private void approach() {
        follow(speed());
        faceAlong();
        Player p = nearest(400);
        if (p != null && age >= readyAt && dist(p) <= CHARGE_RANGE && sees(p)) {
            prey = p;
            mark(p);
            dir.copy(toward(p));
            phase(Phase.CHARGE);
        }
    }

    private void faceAlong() {
        double l = Math.hypot(vel.getX(), vel.getZ());
        if (l > 1e-4) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
    }

    /** 일직선으로 굴러 들며 조금씩만 틂. 닿으면 치고 튕겨 나감. */
    private void charge() {
        if (!standing(prey)) {
            calm();
            return;
        }
        Vector want = toward(prey);
        double cur = Math.atan2(dir.getZ(), dir.getX()), tgt = Math.atan2(want.getZ(), want.getX());
        double diff = Math.atan2(Math.sin(tgt - cur), Math.cos(tgt - cur));
        double step = Math.toRadians(CHARGE_TURN);
        cur += Math.max(-step, Math.min(step, diff));
        dir.setX(Math.cos(cur)).setY(0).setZ(Math.sin(cur));
        dash(dir, CHARGE_V * speed() / baseSpeed);
        faceAlong();
        for (Player p : game.mobs.standing()) {
            if (touches(p, 0.1)) {
                strike(p);
                return;
            }
        }
        if (inPhase() >= CHARGE_MAX || wallAhead(0.8)) calm();
    }

    private void strike(Player p) {
        hit(p, HIT_DMG, "cause_g80");
        knock(p, dir, PUSH, 0.25);
        G80Dance.start(game, p, G80Dance.TICKS);
        sound("bonk", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 16);
        state("bounce");
        body.squash(1.15f, 0.85f, 1.15f, 2);
        dir.multiply(-1);
        phase(Phase.BOUNCE);
    }

    /** 맞은 쪽 반대로 튕겨 나감(통통 뛰며). */
    private void bounce() {
        int t = inPhase();
        dash(dir, BOUNCE_V * (1 - t / (double) BOUNCE));
        hover = 0.8 + BOUNCE_H * Math.sin(Math.PI * Math.min(1, t / (double) BOUNCE));
        if (t == 3) body.squash(1f, 1f, 1f, 3);
        if (t >= BOUNCE) {
            hover = 0.8;
            state("roll");
            readyAt = age + cd(REST);
            phase(Phase.REST);
        }
    }

    /** 잠깐 둘레를 맴돌다가 다시. */
    private void rest() {
        Player p = standing(prey) ? prey : nearest(400);
        if (p == null) {
            follow(speed());
            faceAlong();
        } else {
            // 사람 둘레를 비스듬히 돌며 거리를 둠
            Vector to = toward(p);
            Vector side = new Vector(-to.getZ(), 0, to.getX());
            double d = dist(p);
            Vector v = side.multiply(speed() * 0.9).add(to.multiply(d < 5 ? -speed() * 0.5 : speed() * 0.4));
            vel.setX(v.getX()).setZ(v.getZ());
            faceAlong();
        }
        if (age >= readyAt) calm();
    }

    private void calm() {
        mark(null);
        prey = null;
        hover = 0.8;
        state("roll");
        phase(Phase.APPROACH);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), HOOP_R});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (d.at() != null && d.dir() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            // 탄 길이 가운데 얼굴 가까이를 지났나(가운데까지 선 거리)
            Vector u = d.dir().clone();
            double l = u.length();
            if (l > 1e-6) {
                u.multiply(1 / l);
                Vector c = pos.clone().subtract(d.at());
                double miss = c.clone().crossProduct(u).length();
                if (miss <= HUB_R) return super.damage(d.times(HUB_MUL));
            }
        }
        return super.damage(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " dancing=" + G80Dance.count();
    }
}
