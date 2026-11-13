package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G13 바람개비 게이브(방패, 돌격). 얼굴 앞에서 막대기 둘이 일자로 이어져 바람개비처럼 돈다.
 *
 * <p>도감: 멀리선 느리게 휘휘 돌리며 오다, 표적을 정하면 빨리 돌아 얼굴 앞에 분홍 원반이 생기고 그 원반을 앞세워 돌진. 원반에 닿으면
 * 0.1초 간격 1 피해 다섯 번. 원반이 선 동안 정면 탄은 튕긴다(아무도 안 맞히고 '팅'). 끝나면 1초 막대기가 흐물흐물 늘어지고
 * 가운데 얼굴 눈이 빙글빙글(그때는 정면도 뚫림), 끝 얼굴들이 헛구역질. 가까울수록 높아지는 '위이잉'(사람마다 거리로 높이를 정함).
 *
 * <p>팩 계약(tools/gabe/sp/g13.py): 모델 gave:gabe/g13, 상태 "" 느린 회전, "spin" 원반, "limp" 늘어짐. 소리 whirr, ting, grind, retch.
 * 원인 글귀 cause_g13.
 */
public final class G13 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.13, HOVER = 2.0, CHARGE_HOVER = 1.35;
    static final double RANGE = 12;
    static final int WINDUP = 22, CHARGE_MAX = 30, GRIND_HITS = 5, GRIND_EVERY = 2, LIMP = 20, COOLDOWN = 50;
    static final double CHARGE_V = 0.42, OVERSHOOT = 2.0, DMG = 1.5;
    /** 정면 판정(원반 법선과 탄이 온 쪽 사이 각, 도), 얼굴 구, 원반 구(앞으로 나온 거리, 반지름). */
    static final double FRONT_DEG = 62, FACE_R = 0.42, DISC_AHEAD = 0.42, DISC_R = 0.7;
    static final double WHIRR_R = 24;

    enum Phase { APPROACH, WINDUP, CHARGE, GRIND, LIMP }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, readyAt, nextWhirr, hits, lastTing = -100;
    private Player prey;
    private final Vector dashDir = new Vector(0, 0, 1);
    private double dashGone, dashLen;

    public G13(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g13", 2.0f, 2.0f);
    }

    @Override
    protected void spawned() {
        nextWhirr = age + game.random.nextInt(30);
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
            case GRIND -> grind();
            case LIMP -> limp();
        }
    }

    /** 느리게 휘휘 돌리며 옴. */
    private void approach() {
        hover = HOVER;
        follow(speed());
        Player p = nearest(48);
        face(p != null ? chest(p) : pos.clone().add(vel));
        whirr(40, 0.85f);
        if (age < readyAt) return;
        Player t = acquire(RANGE);
        if (t != null) {
            prey = t;
            mark(t);
            state("spin");
            hold();
            phase(Phase.WINDUP);
        }
    }

    /** 빨리 돌아 원반이 섬, 머리 높이로 내려오며 겨눔. */
    private void windup() {
        if (!standing(prey)) {
            calm();
            return;
        }
        hover = HOVER + (CHARGE_HOVER - HOVER) * Math.min(1.0, inPhase() / (double) cd(WINDUP));
        face(chest(prey));
        vel.copy(toward(prey).multiply(-0.015));
        whirr(10, 1.0f);
        if (inPhase() >= cd(WINDUP)) {
            dashDir.copy(toward(prey));
            dashLen = dist(prey) + OVERSHOOT;
            dashGone = 0;
            phase(Phase.CHARGE);
        }
    }

    /** 원반을 앞세워 일직선 돌진. */
    private void charge() {
        hover = CHARGE_HOVER;
        double v = CHARGE_V * speed() / baseSpeed;
        dash(dashDir, v);
        face(pos.clone().add(dashDir));
        pitch = 0;
        dashGone += v;
        whirr(10, 1.1f);
        for (Player p : game.mobs.standing()) {
            if (touches(p, 0.35)) {
                if (p != prey) {
                    prey = p;
                    mark(p);
                }
                hits = 0;
                hold();
                phase(Phase.GRIND);
                return;
            }
        }
        if (dashGone >= dashLen || inPhase() >= CHARGE_MAX || wallAhead(0.8)) toLimp();
    }

    /** 원반에 닿음: 0.1초 간격 1 피해 다섯 번(멀어지면 끝). */
    private void grind() {
        if (!standing(prey) || dist(prey) > 1.9) {
            toLimp();
            return;
        }
        face(chest(prey));
        vel.copy(toward(prey).multiply(Math.max(0, dist(prey) - 0.9) * 0.3));
        if (inPhase() % GRIND_EVERY == 0 && hits < GRIND_HITS) {
            hit(prey, DMG, "cause_g13");
            knock(prey, toward(prey), 0.08, 0.02);
            if (hits == 0) sound("grind", Sound.ITEM_AXE_STRIP, 1.4f, 16);
            hits++;
        }
        if (hits >= GRIND_HITS) toLimp();
    }

    private void toLimp() {
        state("limp");
        hold();
        sound("retch", Sound.ENTITY_PLAYER_BURP, 0.6f, 12);
        phase(Phase.LIMP);
    }

    /** 1초 흐물흐물(정면도 뚫림). */
    private void limp() {
        hold();
        hover = CHARGE_HOVER + (HOVER - CHARGE_HOVER) * Math.min(1.0, inPhase() / (double) cd(LIMP));
        if (inPhase() >= cd(LIMP)) calm();
    }

    private void calm() {
        mark(null);
        prey = null;
        state("");
        readyAt = age + cd(COOLDOWN);
        phase(Phase.APPROACH);
    }

    /** 위이잉: 사람마다 거리로 높이를 정해 보냄(가까울수록 높음). */
    private void whirr(int every, float base) {
        if (age < nextWhirr || game.director.silent()) return;
        nextWhirr = age + every;
        Location at = loc();
        for (Player p : game.world.getPlayers()) {
            double d = p.getLocation().distance(at);
            if (d > WHIRR_R) continue;
            float pitch = (float) (base * (0.8 + 0.55 * Math.max(0, 1 - d / 18.0)));
            Props.soundTo(p, at, "gabe.g13.whirr", Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 1f, pitch, pitch, -1);
        }
    }

    // ------------------------------------------------------------------ 맞음

    /** 원반이 서 있나(돌진 준비, 돌진, 갈기). */
    private boolean discUp() {
        return phase == Phase.WINDUP || phase == Phase.CHARGE || phase == Phase.GRIND;
    }

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), FACE_R});
        if (discUp()) {
            Vector f = forward().multiply(DISC_AHEAD);
            out.add(new double[] {pos.getX() + f.getX(), pos.getY(), pos.getZ() + f.getZ(), DISC_R});
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (gun && discUp()) {
            Vector from = d.dir() != null ? d.dir().clone().multiply(-1) : d.by() != null ? d.by().getEyeLocation().toVector().subtract(pos) : null;
            if (from != null) {
                from.setY(0);
                double l = from.length();
                if (l > 1e-6 && from.multiply(1 / l).dot(forward()) >= Math.cos(Math.toRadians(FRONT_DEG))) {
                    // 정면 원반이 튕김: 아무도 안 맞히고 팅
                    if (age - lastTing >= 3) {
                        lastTing = age;
                        sound("ting", Sound.BLOCK_ANVIL_LAND, 2.0f, 24);
                    }
                    return false;
                }
            }
        }
        return super.damage(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (discUp() ? " disc" : "");
    }
}
