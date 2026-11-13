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
 * G01 가오리 게이브. 본보기 종 1: 디스플레이 하나짜리 돌격 종(다른 종 담당이 이 꼴을 따라 쓴다).
 *
 * <p>도감: 흐름장을 따라 비스듬히 미끄러져 오고(얼굴은 늘 표적), 8블록 안에서 퍼덕 간격이 좁아지다가 일직선 돌진. 빗나가면 4블록 미끄러진 뒤
 * 느리게 돌아선다. 박치기 3 피해와 밀침(사람 속도에 바깥쪽 0.6), 맞히면 0.5초 찌그러진 채 달라붙었다 떨어진다(시야 가림 없음).
 * 막대기 하나를 끊으면(맞은 점이 막대기 쪽 구) 기울어 제자리를 맴돈다.
 *
 * <p>팩 계약(tools/gabe/sp/g01.py 와 같은 이름): 모델 {@code gave:gabe/g01}, 상태(strings[0]) {@code ""} 기본 펄럭임,
 * {@code "charge"} 빠르고 넓은 펄럭임. 부위(LookState.on) {@code "cut_l"}, {@code "cut_r"}: 몹 자신의 왼쪽, 오른쪽 막대기가 끊김
 * (yaw 0 으로 +Z 를 볼 때 세계 +X 쪽이 왼쪽). 소리 {@code gave:gabe.g01.flap}(자막 '퍼덕'), {@code bump}(박치기 '철썩'), 원인 글귀 {@code cause_g01}.
 * 엔진이 따로 울리는 소리 hurt, die 는 팩에 없으면 바닐라 대체음(슬라임)이다.
 *
 * <p>종 코드 꼴: 생성자에서 수치(반지름, 높이, 빠르기)를 정하고, build() 에서 몸을 만들고, tick() 은 단계(Phase) 하나씩
 * 짧은 메서드로 나눈다. 움직임은 vel 만 정하고(적분, 벽, 밀어내기는 엔진), 방향은 yaw, 겉모습은 state() 와 body.look.
 */
public final class G01 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 다가오는 빠르기(블록/틱, 감독 배율은 speed() 가 곱함). */
    static final double SPEED = 0.15;
    /** 이 블록 안, 보이면 돌진 준비. */
    static final double WINDUP_RANGE = 8;
    /** 돌진 준비 틱(퍼덕 간격이 10틱에서 3틱으로 좁아짐). */
    static final int WINDUP = 24;
    /** 돌진 빠르기(블록/틱 = 12 블록/초, 감독 배율 곱함), 최대 틱, 표적 너머로 더 가는 거리. */
    static final double CHARGE_SPEED = 0.6;
    static final int CHARGE_MAX = 30;
    static final double OVERSHOOT = 1.5;
    /** 빗나간 뒤 미끄러짐: 첫 빠르기와 틱마다 줄어드는 비율(합 약 4블록). */
    static final double SLIDE_V = 0.55, SLIDE_DECAY = 0.86;
    /** 느리게 돌아서기(도/틱), 최대 틱, 다시 돌진하기까지 쉬는 틱. */
    static final float TURN_RATE = 8f;
    static final int TURN_MAX = 30, COOLDOWN = 30;
    /** 박치기 피해, 밀침, 달라붙는 틱. */
    static final double DAMAGE = 4, PUSH = 0.45, PUSH_UP = 0.25;
    static final int STICK = 10;
    /** 맞는 구: 얼굴 반지름, 막대기 구(옆 거리, 반지름). */
    static final double FACE_R = 0.42, ROD_SIDE = 0.85, ROD_R = 0.3;
    /** 막대기 끊긴 뒤 맴돌기(반지름, 각속도 라디안/틱, 기울기 도). */
    static final double SPIN_R = 1.0, SPIN_W = 0.13;
    static final float SPIN_TILT = 28f;

    enum Phase { APPROACH, WINDUP, CHARGE, STICK, SLIDE, TURN, SPIN }

    private Phase phase = Phase.APPROACH;
    private int phaseAt;
    /** 돌진 표적과 방향, 간 거리, 갈 거리. */
    private Player prey;
    private final Vector dashDir = new Vector(0, 0, 1);
    private double dashGone, dashLen, slideV;
    /** 달라붙은 사람과 그 몸통에서의 자리. */
    private Player stuckTo;
    private final Vector stickOff = new Vector();
    private boolean cutL, cutR;
    private final Vector spinAt = new Vector();
    private double spinA;
    private int readyAt, nextFlap, lastSpinHit;

    public G01(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = 1.0; // 얼굴(0.8블록) 아래가 땅에서 0.6 블록
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        // 얼굴 0.8 + 양옆 막대기 1.2 씩: 폭 3.2, 높이 1.4(컬링 상자)
        body = Body.of(this, "gabe/g01", 3.2f, 1.4f);
    }

    @Override
    protected void spawned() {
        nextFlap = age + game.random.nextInt(20);
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
            case APPROACH -> approach();
            case WINDUP -> windup();
            case CHARGE -> charge();
            case STICK -> stick();
            case SLIDE -> slide();
            case TURN -> turn();
            case SPIN -> spin();
        }
    }

    /** 흐름장을 따라 비스듬히 미끄러져 옴. 얼굴은 가장 가까운 사람을 본다. */
    private void approach() {
        follow(speed());
        // 옆으로 흔들리는 미끄러짐(몸이 표적 쪽으로 곧게 오지 않음)
        double sway = Math.sin((age + id * 13) * 0.07) * 0.45 * speed();
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-6) vel.add(new Vector(-vel.getZ() / vl, 0, vel.getX() / vl).multiply(sway));
        Player p = nearest(48);
        face(p != null ? chest(p) : pos.clone().add(vel));
        flap(24);
        if (p != null && age >= readyAt && dist(p) <= WINDUP_RANGE && sees(p)) {
            prey = p;
            mark(p);
            hold();
            phase(Phase.WINDUP);
        }
    }

    /** 제자리에서 표적을 보며 퍼덕 간격이 좁아짐, 끝에 그 순간 표적 쪽으로 돌진. */
    private void windup() {
        if (!standing(prey) || !sees(prey)) {
            calm();
            return;
        }
        face(chest(prey));
        vel.copy(toward(prey).multiply(-0.02));
        int t = inPhase();
        body.look.rate = 1.0 + Math.floor(4.0 * t / cd(WINDUP)) * 0.25;
        flap(Math.max(3, 10 - t / 3));
        if (t >= cd(WINDUP)) {
            dashDir.copy(toward(prey));
            dashLen = dist(prey) + OVERSHOOT;
            dashGone = 0;
            body.look.rate = 1.0;
            state("charge");
            phase(Phase.CHARGE);
        }
    }

    /** 일직선 돌진(방향을 못 틈). 닿은 사람을 박고, 지나치거나 벽이면 빗나감. */
    private void charge() {
        double v = CHARGE_SPEED * speed() / baseSpeed;
        dash(dashDir, v);
        face(pos.clone().add(dashDir));
        dashGone += v;
        flap(4);
        for (Player p : game.mobs.standing()) {
            if (touches(p, 0.1)) {
                strike(p);
                return;
            }
        }
        if (dashGone >= dashLen || inPhase() >= CHARGE_MAX || wallAhead(0.7)) {
            slideV = Math.min(SLIDE_V, v);
            state("");
            phase(Phase.SLIDE);
        }
    }

    private void strike(Player p) {
        hit(p, DAMAGE, "cause_g01");
        knock(p, dashDir, PUSH, PUSH_UP);
        sound("bump", Sound.ENTITY_SLIME_ATTACK, 1.3f, 16);
        stuckTo = p;
        stickOff.copy(pos.clone().subtract(chest(p)));
        if (stickOff.length() > 0.55) stickOff.normalize().multiply(0.55);
        noPush = true;
        body.squash(1.35f, 0.7f, 0.75f, 2); // 젤리처럼 찌그러짐(웃음은 그대로)
        hold();
        phase(Phase.STICK);
    }

    /** 0.5초 사람 몸에 붙어 따라갔다가 떨어짐. */
    private void stick() {
        if (!standing(stuckTo) || inPhase() >= STICK) {
            noPush = false;
            body.squash(1f, 1f, 1f, 4);
            state("");
            Vector away = stickOff.clone().setY(0);
            vel.copy(away.lengthSquared() > 1e-6 ? away.normalize().multiply(0.25) : new Vector());
            readyAt = age + cd(COOLDOWN);
            phase(Phase.TURN);
            return;
        }
        Vector want = chest(stuckTo).add(stickOff);
        vel.copy(want.subtract(pos));
        face(chest(stuckTo));
    }

    /** 빗나감: 4블록쯤 미끄러지며 느려짐. 이때가 약점. */
    private void slide() {
        dash(dashDir, slideV);
        slideV *= SLIDE_DECAY;
        flap(16);
        if (slideV < 0.03 || wallAhead(0.6)) {
            hold();
            readyAt = age + cd(COOLDOWN);
            phase(Phase.TURN);
        }
    }

    /** 느리게 돌아섬. */
    private void turn() {
        vel.multiply(0.7);
        Player p = standing(prey) ? prey : nearest(48);
        boolean done = p == null || turnTo(chest(p), TURN_RATE);
        flap(20);
        if (done || inPhase() >= cd(TURN_MAX)) calm();
    }

    /** 표적을 풀고 다시 다가오기로. */
    private void calm() {
        mark(null);
        prey = null;
        body.look.rate = 1.0;
        state("");
        if (cutL || cutR) {
            spinAt.copy(pos);
            phase(Phase.SPIN);
        } else phase(Phase.APPROACH);
    }

    /** 막대기가 끊김: 기울어 제자리를 맴돈다(맴도는 가운데가 가장 가까운 사람 쪽으로 평소의 65% 빠르기로 옴). 둘 다 끊기면 낮게 기어 옴. */
    private void spin() {
        // 게이브는 사람보다 훨씬 먼 곳(72~84블록)에서 나오므로 가까운 사람을 반경 제한 없이 찾고, 없으면 흐름장 방향(가운데 쪽)으로 온다.
        // 전에는 48블록 안의 사람만 찾아서, 멀리서 막대기가 끊긴 개체가 외곽에서 제자리만 돌았다(2026-11-11 실서버).
        Player p = nearest(400);
        Vector to = p != null ? toward(p) : game.mobs.flow.dir(pos);
        if (cutL && cutR) {
            hover = 0.4;
            body.roll = 0;
            vel.copy(to.clone().multiply(speed() * 0.35));
            face(p != null ? chest(p) : pos.clone().add(to));
        } else {
            // 맴도는 가운데가 사람 쪽으로 평소 빠르기의 65% 로 온다(나선으로 비틀거리며 다가옴)
            spinAt.add(to.clone().multiply(speed() * 0.65));
            spinA += SPIN_W * (cutL ? 1 : -1);
            Vector want = spinAt.clone().add(new Vector(Math.cos(spinA) * SPIN_R, 0, Math.sin(spinA) * SPIN_R));
            vel.copy(want.subtract(pos).setY(0));
            if (vel.length() > 0.3) vel.normalize().multiply(0.3);
            face(pos.clone().add(new Vector(-Math.sin(spinA), 0, Math.cos(spinA)).multiply(cutL ? 1 : -1)));
            body.roll = cutL ? -SPIN_TILT : SPIN_TILT;
        }
        if (age >= nextFlap) {
            sound("flap", Sound.ENTITY_PHANTOM_FLAP, 1.4f, 20);
            nextFlap = age + 9 + game.random.nextInt(7);
        }
        // 맴돌다 닿은 사람은 그냥 박음(달라붙지 않음)
        if (age - lastSpinHit >= 20) {
            for (Player q : game.mobs.standing()) {
                if (touches(q, 0)) {
                    hit(q, DAMAGE, "cause_g01");
                    lastSpinHit = age;
                    break;
                }
            }
        }
    }

    /** 퍼덕 소리를 every 틱 간격으로. */
    private void flap(int every) {
        if (age < nextFlap) return;
        sound("flap", Sound.ENTITY_PHANTOM_FLAP, 1.4f, 20);
        nextFlap = age + every;
    }

    // ------------------------------------------------------------------ 맞음

    /** 몹 자신의 왼쪽(yaw 0 일 때 +X). */
    private Vector left() {
        double r = Math.toRadians(yaw);
        return new Vector(Math.cos(r), 0, Math.sin(r));
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), FACE_R});
        Vector l = left().multiply(ROD_SIDE);
        if (!cutL) out.add(new double[] {pos.getX() + l.getX(), pos.getY(), pos.getZ() + l.getZ(), ROD_R});
        if (!cutR) out.add(new double[] {pos.getX() - l.getX(), pos.getY(), pos.getZ() - l.getZ(), ROD_R});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            // 맞은 점이 얼굴보다 막대기 구에 가까우면 그 막대기가 끊김
            Vector l = left().multiply(ROD_SIDE);
            double face = d.at().distanceSquared(pos);
            double dl = d.at().distanceSquared(pos.clone().add(l)), dr = d.at().distanceSquared(pos.clone().subtract(l));
            if (!cutL && dl < face && dl <= dr) cut(true);
            else if (!cutR && dr < face && dr < dl) cut(false);
        }
        return super.damage(d);
    }

    private void cut(boolean leftSide) {
        if (leftSide) cutL = true;
        else cutR = true;
        body.look.on.add(leftSide ? "cut_l" : "cut_r");
        sound("cut", Sound.ENTITY_SLIME_SQUISH, 0.7f, 16);
        if (phase == Phase.STICK) {
            noPush = false;
            body.squash(1f, 1f, 1f, 3);
        }
        mark(null);
        prey = null;
        state("");
        spinAt.copy(pos);
        spinA = game.random.nextDouble() * Math.PI * 2;
        phase(Phase.SPIN);
    }

    /** 시험용: 지금 단계 이름. */
    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (cutL ? " cutL" : "") + (cutR ? " cutR" : "");
    }
}
