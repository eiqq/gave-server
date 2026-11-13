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
 * G59 물동이 게이브(1막 돌격). 정수리에 살로 빚은 물동이를 인 얼굴이 새 다리 둘로 물 한 방울 안 흘리고 미끄러지듯 달려와 5블록 안에서 무릎을 굽혔다가
 * 머리로 들이받는다(12 피해, 뒤로 밀침, 젖어 2초 느려짐 30%). 물이 찬 동안은 받는 피해 절반.
 *
 * <p>대처: 표적이 이 개체를 보고 웅크리면(절) 맞절하다 물이 쏟아져 3초 숙인 채 굳고 그동안 피해 두 배. 물동이를 쏴 깨도(물동이 체력 12) 물이 쏟아진다.
 * 물이 없으면(dry) 피해 절반이 풀리고 다리가 후들거린다.
 *
 * <p>팩 계약(tools/gabe/sp/g59.py): 모델 gabe/g59, 상태 "" 달리기, "bow" 숙임(빈 물동이 속이 보임), "dry" 물 없음. 부위 "broken"(물동이 깨짐).
 * 소리 slosh(찰랑), surge(출렁: 들이받기 예고), spill(쏟아지는 물), bump(받힘). 원인 글귀 cause_g59.
 */
public final class G59 extends Gabe {

    static final double SPEED = 0.26, HOVER = 1.1, FACE_R = 0.42;
    /** 들이받기: 시작 거리, 준비 틱, 돌진 빠르기, 최대 틱, 표적 너머로 더 가는 거리. */
    static final double RAM_RANGE = 5, DASH_V = 0.55, OVERSHOOT = 1.2;
    static final int WINDUP = 10, DASH_MAX = 16, COOLDOWN = 30;
    /** 피해, 밀침, 젖음(느려짐 %, 틱). */
    static final double DAMAGE = 12, PUSH = 0.6, PUSH_UP = 0.3, WET_SLOW = 30;
    static final int WET_TICKS = 40;
    /** 절: 알아보는 거리, 사람이 이 개체를 보는 각(도), 숙인 채 굳는 틱(박자 배율 안 받음: 대처 창), 그동안 피해 배율. */
    static final double BOW_RANGE = 9, BOW_ANGLE = 40, BOW_MUL = 2.0, WATER_MUL = 0.5;
    static final int BOW_TICKS = 60;
    /** 물동이: 가운데 높이, 반지름, 체력(감독 체력 배율 비례). 깨지면 비틀거리는 틱. */
    static final double JAR_Y = 0.68, JAR_R = 0.5, JAR_HP = 12;
    static final int STAGGER = 20;

    enum Phase { RUN, WINDUP, DASH, RECOVER, BOW, STAGGER }

    private Phase phase = Phase.RUN;
    private int phaseAt, readyAt, nextSlosh;
    private Player prey;
    private final Vector dashDir = new Vector(0, 0, 1);
    private double dashGone, dashLen, jarHp = -1;
    private boolean water = true, broken;

    public G59(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g59", 1.5f, 2.1f);
    }

    @Override
    protected void spawned() {
        nextSlosh = age + game.random.nextInt(20);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    @Override
    public void tick() {
        if (jarHp < 0) jarHp = JAR_HP * Math.max(1, maxHp / Math.max(1, sp.hp));
        switch (phase) {
            case RUN -> run();
            case WINDUP -> windup();
            case DASH -> dash();
            case RECOVER -> recover();
            case BOW -> bowing();
            case STAGGER -> stagger();
        }
    }

    /** 위는 그대로 둔 채 다리만 바삐 놀려 미끄러져 옴(얼굴은 늘 수평). */
    private void run() {
        follow(speed());
        Player p = nearest(400);
        face(p != null ? chest(p) : pos.clone().add(vel));
        pitch = 0;
        slosh(water ? 14 : 30);
        if (p == null) return;
        if (bowedBy(p)) return;
        if (age >= readyAt && dist(p) <= RAM_RANGE && sees(p)) {
            prey = p;
            mark(p);
            hold();
            body.squash(1.05f, 0.88f, 1.05f, 3); // 무릎을 굽힘
            if (water) sound("surge", Sound.ENTITY_GENERIC_SPLASH, 0.9f, 20);
            phase(Phase.WINDUP);
        }
    }

    private void windup() {
        if (!standing(prey)) {
            calm();
            return;
        }
        if (bowedBy(prey)) return;
        face(chest(prey));
        hold();
        if (inPhase() >= cd(WINDUP)) {
            dashDir.copy(toward(prey));
            dashLen = dist(prey) + OVERSHOOT;
            dashGone = 0;
            body.squash(0.92f, 1.08f, 1.12f, 2);
            phase(Phase.DASH);
        }
    }

    private void dash() {
        double v = DASH_V * speed() / baseSpeed;
        dash(dashDir, v);
        dashGone += v;
        for (Player p : game.mobs.standing()) {
            if (touches(p, 0.15)) {
                ram(p);
                return;
            }
        }
        if (dashGone >= dashLen || inPhase() >= DASH_MAX || wallAhead(0.6)) {
            body.squash(1f, 1f, 1f, 3);
            readyAt = age + cd(COOLDOWN);
            phase(Phase.RECOVER);
        }
    }

    private void ram(Player p) {
        hit(p, DAMAGE, "cause_g59");
        knock(p, dashDir, PUSH, PUSH_UP);
        game.players.slow(p, WET_SLOW, WET_TICKS, "g59_wet");
        sound("bump", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 16);
        body.squash(1.2f, 0.82f, 0.8f, 2);
        hold();
        readyAt = age + cd(COOLDOWN);
        phase(Phase.RECOVER);
    }

    /** 들이받은 뒤 잠깐 물러서며 숨 고름. */
    private void recover() {
        vel.copy(dashDir.clone().multiply(-0.06));
        if (inPhase() >= 3) body.squash(1f, 1f, 1f, 4);
        if (inPhase() >= cd(14)) calm();
    }

    /** 절: 숙인 채 굳음(피해 두 배). 끝나면 물 없이 다시 달려옴. */
    private void bowing() {
        hold();
        if (prey != null && standing(prey)) face(chest(prey));
        pitch = 0;
        if (inPhase() >= BOW_TICKS) {
            state("dry");
            body.squash(1f, 1f, 1f, 4);
            readyAt = age + cd(10);
            calm();
        }
    }

    private void stagger() {
        vel.multiply(0.6);
        if (inPhase() >= STAGGER) {
            body.roll = 0;
            calm();
        } else body.roll = (float) (Math.sin(inPhase() * 0.9) * 12);
    }

    private void calm() {
        if (target != null) mark(null);
        prey = null;
        phase(Phase.RUN);
    }

    /** 표적이 이 개체를 보며 웅크렸으면 맞절(물이 있을 때만). */
    private boolean bowedBy(Player p) {
        if (!water || p == null || !p.isSneaking()) return false;
        Vector eye = p.getEyeLocation().toVector();
        Vector to = pos.clone().subtract(eye);
        double d = to.length();
        if (d > BOW_RANGE || d < 1e-6) return false;
        double cos = to.multiply(1 / d).dot(p.getEyeLocation().getDirection());
        if (cos < Math.cos(Math.toRadians(BOW_ANGLE))) return false;
        prey = p;
        mark(p);
        hold();
        spill();
        state("bow");
        body.squash(1f, 1f, 1f, 2);
        phase(Phase.BOW);
        return true;
    }

    private void spill() {
        water = false;
        sound("spill", Sound.ENTITY_GENERIC_SPLASH, 0.6f, 20);
        game.shotFx.bloodHit(loc().add(0, JAR_Y, 0), new Vector(0, 1, 0), false, kr.gave.gun.ShotFx.BLOOD_PINK);
    }

    private void slosh(int every) {
        if (age < nextSlosh) return;
        nextSlosh = age + every + game.random.nextInt(6);
        if (water) sound("slosh", Sound.ENTITY_GENERIC_SPLASH, 1.4f, 20);
    }

    // ------------------------------------------------------------------ 맞음

    private Vector jarCenter() {
        return pos.clone().add(new Vector(0, JAR_Y, 0));
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), FACE_R});
        if (!broken && phase != Phase.BOW) out.add(new double[] {pos.getX(), pos.getY() + JAR_Y, pos.getZ(), JAR_R});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (!broken && phase != Phase.BOW && d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)
                && d.at().distanceSquared(jarCenter()) < d.at().distanceSquared(pos)) {
            // 물동이에 맞음: 몸은 안 다치고 물동이 체력만
            jarHp -= d.amount();
            game.mobs.onHurt(this, d, Math.min(d.amount(), 1));
            if (jarHp <= 0) breakJar();
            return true;
        }
        if (phase == Phase.BOW) d = d.times(BOW_MUL);
        else if (water) d = d.times(WATER_MUL);
        return super.damage(d);
    }

    private void breakJar() {
        broken = true;
        body.look.on.add("broken");
        if (water) spill();
        if (phase != Phase.BOW) {
            state("dry");
            if (target != null) mark(null);
            prey = null;
            body.squash(1f, 1f, 1f, 2);
            phase(Phase.STAGGER);
        }
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (water ? " water" : " dry") + (broken ? " broken" : "");
    }
}
