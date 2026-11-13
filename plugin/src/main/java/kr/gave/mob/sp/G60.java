package kr.gave.mob.sp;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G60 튀는 공 게이브(떼, 돌격). 굴대 막대기가 꽂힌 얼굴 공이 표적을 향해 포물선으로 통통 튀고, 튈 때마다 20% 빨라지고 높아진다(다섯 번까지,
 * 높이 1.5 → 4블록). 맨 위에서 HANG 틱 걸린 듯 멈칫했다가 떨어질 자리로 내리꽂힌다. 빗나가면 빠르기를 이어 가며 다시 튄다.
 *
 * <p>높이는 서버가 틱마다 정한다(flying, vel = 다음 자리 - 지금 자리). 떨어질 자리는 표적의 0.5초 앞 예측 자리(최근 움직임으로), 한 번에 갈 수 있는
 * 거리까지. 표적에서 ENGAGE 블록 밖에서는 낮게 튀며 오기만 하고 튄 횟수를 세지 않는다. 표적 곁에 들면 떨어질 자리에 점묘 그림자 원(부품
 * g60_shadow, 세계 축)을 깐다.
 *
 * <p>닿으면 BASE + 튄 횟수 x PER 피해(다섯 번째면 14)와 위로 튕김. 땅에 닿을 때 반경 SPLASH_R 안 SPLASH. 맞히면 튄 횟수가 1로 돌아간다.
 *
 * <p>팩 계약(tools/gabe/sp/g60.py): 상태 ""(굴대 회전), "fast"(두 배 빠른 회전), 부품 모델 gabe/g60_shadow, 소리 boing0..boing4(20, 튄 횟수마다
 * 높아짐), slam(16), 글귀 cause_g60.
 */
public final class G60 extends Gabe {

    static final double RADIUS = 0.42, GROUND = 0.42;
    /** 표적 곁(이 안에서만 튄 횟수를 셈), 다섯 번까지. */
    static final double ENGAGE = 24;
    static final int MAX_LV = 4;
    /** 높이: 처음, 한 번마다. 멀리서 오는 낮은 뜀 높이. */
    static final double H0 = 1.5, H_PER = 0.625, H_TRAVEL = 1.2;
    /** 한 번 뛰는 거리(블록, 감독 빠르기 배율 곱함): 처음, 한 번마다 배율, 멀리서. */
    static final double RANGE0 = 3.0, RANGE_MUL = 1.2, RANGE_TRAVEL = 3.6;
    /** 오르는 틱(처음, 한 번마다 줄어듦), 맨 위 멈칫, 내리꽂는 틱, 땅에 닿아 있는 틱. */
    static final int RISE0 = 10, HANG = 6, FALL = 5, REST = 2;
    /** 피해. */
    static final double BASE = 4, PER = 2, SPLASH = 1, SPLASH_R = 1.0;
    static final double KNOCK = 0.3, KNOCK_UP = 0.55;

    enum Phase { REST, RISE, HANG, FALL }

    private Phase phase = Phase.REST;
    private int phaseAt, lv, rise, fall;
    private boolean engaged, struck, retreat;
    private double height;
    private final Vector start = new Vector(), land = new Vector(), apex = new Vector();
    private Player prey, struckP;
    private final Vector preyLast = new Vector(), preyVel = new Vector();
    private Body.Part shadow;

    public G60(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        flying = true;
        baseSpeed = 0.16;
    }

    @Override
    protected void spawned() {
        phaseAt = age - game.random.nextInt(REST + 1);
        pos.setY(game.mobs.flow.floor(pos.getX(), pos.getZ()) + GROUND);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    @Override
    public void tick() {
        track();
        int t = age - phaseAt;
        switch (phase) {
            case REST -> {
                vel.zero();
                if (t >= REST) takeoff();
            }
            case RISE -> {
                double u = Math.min(1, (t + 1) / (double) rise);
                moveTo(start.clone().add(apex.clone().subtract(start).multiply(u)).setY(start.getY() + height * (1 - (1 - u) * (1 - u))));
                if (t == 2) body.squash(1f, 1f, 1f, 3);
                if (t + 1 >= rise) phase(Phase.HANG);
            }
            case HANG -> {
                // 걸린 듯 멈칫(살짝 떨림)
                moveTo(apex.clone().setY(start.getY() + height + Math.sin(age * 1.7) * 0.03));
                if (t + 1 >= HANG) phase(Phase.FALL);
            }
            case FALL -> {
                double u = Math.min(1, (t + 1) / (double) fall);
                double top = start.getY() + height;
                moveTo(apex.clone().add(land.clone().subtract(apex).multiply(u)).setY(top + (land.getY() - top) * u * u));
                if (u >= 0.5 && !struck) strikeCheck();
                if (u >= 1) touchdown();
            }
        }
        Player p = prey != null && standing(prey) ? prey : null;
        if (p != null) face(chest(p));
        else if (vel.lengthSquared() > 1e-6) face(pos.clone().add(vel.clone().setY(0)));
        pitch = 0;
        shadowTick();
    }

    /** 표적의 최근 움직임(0.5초 앞 예측용). */
    private void track() {
        Player p = nearest(400);
        if (p != prey) {
            prey = p;
            if (p != null) preyLast.copy(p.getLocation().toVector());
            preyVel.zero();
            return;
        }
        if (p == null) return;
        Vector now = p.getLocation().toVector();
        preyVel.multiply(0.6).add(now.clone().subtract(preyLast).multiply(0.4));
        preyLast.copy(now);
    }

    private void takeoff() {
        Player p = prey != null && standing(prey) ? prey : null;
        engaged = p != null && dist(p) <= ENGAGE;
        if (!engaged) lv = 0;
        double sf = speed() / baseSpeed;
        double range = (engaged ? RANGE0 * Math.pow(RANGE_MUL, lv) : RANGE_TRAVEL) * sf;
        height = engaged ? H0 + H_PER * lv : H_TRAVEL;
        rise = engaged ? Math.max(5, RISE0 - lv) : 8;
        fall = engaged ? Math.max(3, FALL - lv / 2) : 6;
        Vector want;
        if (engaged && retreat) {
            // 맞힌 뒤에는 튕겨 나감(사람에게서 비스듬히 멀어지는 쪽으로 한 번)
            Vector away = pos.clone().subtract(p.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-6) away = new Vector(1, 0, 0);
            away.normalize().rotateAroundY((game.random.nextDouble() - 0.5) * 1.6);
            want = pos.clone().add(away.multiply(range));
        } else if (engaged) want = p.getLocation().toVector().add(preyVel.clone().setY(0).multiply(10));
        else if (p != null && dist(p) <= 40) want = p.getLocation().toVector();
        else want = pos.clone().add(game.mobs.flow.dir(pos).multiply(range));
        Vector step = want.subtract(pos).setY(0);
        if (step.length() > range) step.normalize().multiply(range);
        start.copy(pos);
        land.copy(pos).add(step);
        land.setY(game.mobs.flow.floor(land.getX(), land.getZ()) + GROUND);
        apex.copy(pos).add(step.clone().multiply(0.6));
        struck = false;
        struckP = null;
        retreat = false;
        state(lv >= 2 ? "fast" : "");
        sound("boing" + Math.min(lv, MAX_LV), Sound.ENTITY_SLIME_JUMP_SMALL, (float) (1.0 + 0.15 * lv), 20);
        body.squash(0.85f, 1.2f, 0.85f, 2);
        phase(Phase.RISE);
    }

    private void moveTo(Vector want) {
        vel.copy(want.subtract(pos));
    }

    /** 내리꽂히는 동안 닿은 사람(한 번 뛸 때 한 번). */
    private void strikeCheck() {
        for (Player p : game.mobs.standing()) {
            if (!touches(p, 0.1)) continue;
            struck = true;
            struckP = p;
            hit(p, BASE + PER * (engaged ? lv : 0), "cause_g60");
            Vector away = toward(p);
            knock(p, away, KNOCK, KNOCK_UP);
            sound("slam", Sound.ENTITY_SLIME_SQUISH, 0.8f, 16);
            return;
        }
    }

    private void touchdown() {
        vel.zero();
        pos.setY(land.getY());
        body.squash(1.3f, 0.65f, 1.3f, 2);
        if (!struck) {
            // 내려앉은 자리에 선 사람(마지막 틱의 자리는 엔진이 tick 뒤에 옮기므로 내려앉을 점으로 잰다)
            for (Player p : game.mobs.standing()) {
                double dx = p.getLocation().getX() - land.getX(), dz = p.getLocation().getZ() - land.getZ(), r = RADIUS + 0.45;
                if (dx * dx + dz * dz > r * r || p.getLocation().getY() - (land.getY() - GROUND) > 1.2) continue;
                struck = true;
                struckP = p;
                hit(p, BASE + PER * (engaged ? lv : 0), "cause_g60");
                knock(p, p.getLocation().toVector().subtract(land).setY(0), KNOCK, KNOCK_UP);
                sound("slam", Sound.ENTITY_SLIME_SQUISH, 0.8f, 16);
                break;
            }
        }
        for (Player p : game.mobs.standing()) {
            if (p == struckP) continue;
            double dx = p.getLocation().getX() - land.getX(), dz = p.getLocation().getZ() - land.getZ();
            if (dx * dx + dz * dz <= SPLASH_R * SPLASH_R && p.getLocation().getY() - land.getY() < 1.0) hit(p, SPLASH, "cause_g60");
        }
        // 맞히면 처음 빠르기 가까이로, 빗나가면 빠르기를 이어 감
        if (struck) {
            lv = 1;
            retreat = true;
        }
        else if (engaged) lv = Math.min(MAX_LV, lv + 1);
        phase(Phase.REST);
    }

    /** 표적 곁이면 떨어질 자리에 그림자 원(몸 부품, 세계 축). 땅에 있거나 멀면 숨김. */
    private void shadowTick() {
        boolean show = engaged && phase != Phase.REST;
        if (show && shadow == null && body != null) {
            shadow = body.attach("gabe/g60_shadow");
            if (shadow != null) shadow.follow = false;
        }
        if (shadow == null || !every(2)) return;
        Matrix4f m = new Matrix4f();
        if (show) m.translate((float) (land.getX() - pos.getX()), (float) (land.getY() - GROUND + 0.03 - pos.getY()), (float) (land.getZ() - pos.getZ()));
        else m.scale(0.001f);
        shadow.pose(m, 1);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (shadow != null) {
            shadow.remove();
            shadow = null;
        }
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " lv=" + lv + (engaged ? " engaged" : "");
    }
}
