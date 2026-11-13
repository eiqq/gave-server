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
 * G63 집게다리 게이브(1막 돌격, 튼튼이). 땅에 바짝 엎드린 얼굴 몸통 둘레로 엔드 막대기 다리 여섯이 게처럼 뻗는다. 옆걸음으로 빠르게 다가와 3블록 안에서
 * 앞 집게 둘을 쳐들었다 번갈아 내리찍는다(7 피해 두 번, 맞을 때마다 0.5초 느려짐).
 *
 * <p>얼굴(껍질)을 쏘면 탄이 튕겨 피해 25%. 다리마다 체력 15(감독 체력 배율 비례): 다리를 맞히면 그 다리가 깎이고 몸에도 절반이 간다. 다리가 끊길 때마다
 * 느려지고, 셋을 잃으면 주저앉아(down) 기어 오며 물기 5, 얼굴이 피해 100% 를 받는다. 폭발과 불은 몸에 그대로, 둘레 다리에도 닿는다.
 *
 * <p>팩 계약(tools/gabe/sp/g63.py): 모델 gabe/g63, 상태 "" 걸음, "strike" 집게를 쳐듦, "down" 주저앉음. 부위 cut1..cut6
 * (1 앞 왼쪽, 2 가운데 왼쪽, 3 뒤 왼쪽, 4 앞 오른쪽, 5 가운데 오른쪽, 6 뒤 오른쪽, 왼쪽 = 게이브 자신의 +X). 소리 skitter, clack, snap, snip.
 */
public final class G63 extends Gabe {

    static final double SPEED = 0.2, HOVER = 0.6, DOWN_HOVER = 0.3, FACE_R = 0.5, LEG_R = 0.32;
    /** 찍기: 닿는 거리(몸 반지름 밖으로, 쳐들기는 이보다 0.45 안에서 시작), 준비, 두 찍기 사이, 쉬는 틱. */
    static final double REACH = 1.7;
    static final int WINDUP = 10, GAP = 10, REST = 20;
    static final double STRIKE_DMG = 7, STRIKE_SLOW = 35, BITE_DMG = 5;
    static final int STRIKE_SLOW_T = 10, BITE_CD = 20;
    /** 다리 체력(감독 체력 배율 비례), 얼굴 피해 몫, 다리를 맞힐 때 몸 몫, 주저앉는 끊긴 다리 수, 다리 하나마다 느려짐. */
    static final double LEG_HP = 15, SHELL_MUL = 0.25, LEG_BODY_MUL = 0.5, PER_LEG_SLOW = 0.12;
    static final int DOWN_AT = 3;
    /** 다리 맞는 구 자리(몸 기준: x 왼쪽 +, y 위, z 앞), 팩 g63.py LEGS 의 정강이 가운데. */
    static final double[][] LEG = {
            {0.87, 0.23, 0.9}, {1.02, 0.02, -0.15}, {1.02, 0.02, -0.89},
            {-0.87, 0.23, 0.9}, {-1.02, 0.02, -0.15}, {-1.02, 0.02, -0.89}};

    enum Phase { WALK, WINDUP, STRIKE, REST, DOWN }

    private Phase phase = Phase.WALK;
    private int phaseAt, readyAt, nextStep, cutCount;
    private Player prey;
    private final boolean[] cut = new boolean[6];
    private final double[] legHp = new double[6];
    private boolean legInit;
    private final List<double[]> sph = new ArrayList<>(7);
    private int sphAge = -1;

    public G63(Game game, Species sp) {
        super(game, sp);
        radius = 0.55;
        hover = HOVER;
        baseSpeed = SPEED;
        for (int i = 0; i < 7; i++) sph.add(new double[4]);
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g63", 2.6f, 1.5f);
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
        if (!legInit) {
            legInit = true;
            double k = Math.max(1, maxHp / Math.max(1, sp.hp));
            for (int i = 0; i < 6; i++) legHp[i] = LEG_HP * k;
        }
        switch (phase) {
            case WALK -> walk();
            case WINDUP -> windup();
            case STRIKE -> strike();
            case REST -> rest();
            case DOWN -> down();
        }
    }

    private double legSpeed() {
        return speed() * Math.max(0.4, 1 - PER_LEG_SLOW * cutCount);
    }

    /** 옆걸음: 흐름장 쪽으로 오되 옆으로 번갈아 쏠리며(게 걸음), 얼굴은 표적. */
    private void walk() {
        follow(legSpeed());
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-6) {
            double side = Math.sin((age + id * 7) * 0.16) * 0.9 * legSpeed();
            vel.add(new Vector(-vel.getZ() / vl, 0, vel.getX() / vl).multiply(side));
        }
        Player p = nearest(400);
        face(p != null ? chest(p) : pos.clone().add(vel));
        step(9);
        // 집게가 실제로 닿는 거리(수평)까지 들어와서 쳐듦(멀리서 쳐들면 내리찍어도 안 닿음, 2026-11-12 게임 안 시험)
        if (p != null && age >= readyAt && touches(p, REACH - 0.45)) {
            prey = p;
            mark(p);
            hold();
            state("strike");
            phase(Phase.WINDUP);
        }
    }

    private void windup() {
        if (!standing(prey)) {
            calm();
            return;
        }
        face(chest(prey));
        vel.copy(toward(prey).multiply(0.03));
        if (inPhase() >= cd(WINDUP)) phase(Phase.STRIKE);
    }

    /** 앞 집게 둘을 번갈아 내리찍음(0 틱에 왼쪽, GAP 틱에 오른쪽). 끊긴 집게는 못 찍음. */
    private void strike() {
        int t = inPhase();
        if (standing(prey)) face(chest(prey));
        hold();
        if (t == 0) snip(0);
        else if (t == cd(GAP)) snip(3);
        if (t == 2) state("strike");
        if (t >= cd(GAP) + 4) {
            state("");
            readyAt = age + cd(REST);
            phase(Phase.REST);
        }
    }

    private void snip(int leg) {
        state("");
        body.squash(1.08f, 0.85f, 1.12f, 2);
        if (cut[leg] || !standing(prey)) return;
        sound("snip", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 16);
        if (touches(prey, REACH)) {
            hit(prey, STRIKE_DMG, "cause_g63");
            game.players.slow(prey, STRIKE_SLOW, STRIKE_SLOW_T, "g63_snip");
        }
    }

    private void rest() {
        body.squash(1f, 1f, 1f, 3);
        vel.copy(prey != null && standing(prey) ? toward(prey).multiply(-0.04) : new Vector());
        if (inPhase() >= cd(REST)) calm();
    }

    private void calm() {
        if (target != null) mark(null);
        prey = null;
        state("");
        phase(Phase.WALK);
    }

    /** 주저앉음: 남은 다리로 기어 오며 닿으면 문다. */
    private void down() {
        follow(legSpeed() * 0.5);
        Player p = nearest(400);
        face(p != null ? chest(p) : pos.clone().add(vel));
        step(16);
        if (p == null) return;
        if (target != p && dist(p) < 6) mark(p);
        if (age >= readyAt && touches(p, 0.3)) {
            hit(p, BITE_DMG, "cause_g63");
            body.squash(1.1f, 0.85f, 1.15f, 2);
            readyAt = age + cd(BITE_CD);
        }
    }

    private void step(int every) {
        if (age < nextStep) return;
        nextStep = age + every + game.random.nextInt(5);
        sound("skitter", Sound.ENTITY_SPIDER_STEP, 1.4f, 20);
    }

    // ------------------------------------------------------------------ 맞음

    /** 몸 기준 자리 → 세계(왼쪽 L = (cos yaw, 0, sin yaw), 앞 F = (-sin yaw, 0, cos yaw)). */
    private void local(double lx, double ly, double lz, double[] out) {
        double r = Math.toRadians(yaw), c = Math.cos(r), s = Math.sin(r);
        out[0] = pos.getX() + c * lx - s * lz;
        out[1] = pos.getY() + ly;
        out[2] = pos.getZ() + s * lx + c * lz;
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge != age) {
            sphAge = age;
            double[] f = sph.get(0);
            f[0] = pos.getX();
            f[1] = pos.getY();
            f[2] = pos.getZ();
            f[3] = FACE_R;
            double drop = phase == Phase.DOWN ? -0.25 : 0;
            for (int i = 0; i < 6; i++) {
                double[] o = sph.get(i + 1);
                local(LEG[i][0] * (phase == Phase.DOWN ? 1.15 : 1), LEG[i][1] + drop, LEG[i][2], o);
                o[3] = cut[i] ? 0 : LEG_R;
            }
        }
        List<double[]> out = new ArrayList<>(7);
        for (double[] s : sph) if (s[3] > 0) out.add(s);
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            spheres();
            int best = -1;
            double bd = d.at().distanceSquared(pos) - FACE_R * FACE_R;
            double[] w = new double[3];
            for (int i = 0; i < 6; i++) {
                if (cut[i]) continue;
                double[] s = sph.get(i + 1);
                w[0] = d.at().getX() - s[0];
                w[1] = d.at().getY() - s[1];
                w[2] = d.at().getZ() - s[2];
                double dd = w[0] * w[0] + w[1] * w[1] + w[2] * w[2] - LEG_R * LEG_R;
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best >= 0) {
                legHp[best] -= d.amount();
                if (legHp[best] <= 0) cutLeg(best);
                return super.damage(d.times(LEG_BODY_MUL));
            }
            if (cutCount < DOWN_AT) {
                if (age % 3 == 0) sound("clack", Sound.BLOCK_ANVIL_LAND, 1.8f, 16);
                return super.damage(d.times(SHELL_MUL));
            }
            return super.damage(d);
        }
        if (d.at() != null && (t == DamageType.EXPLOSION || t == DamageType.FIRE)) {
            spheres();
            for (int i = 0; i < 6; i++) {
                if (cut[i]) continue;
                double[] s = sph.get(i + 1);
                double dx = d.at().getX() - s[0], dy = d.at().getY() - s[1], dz = d.at().getZ() - s[2];
                if (dx * dx + dy * dy + dz * dz <= 2.5 * 2.5) {
                    legHp[i] -= d.amount();
                    if (legHp[i] <= 0) cutLeg(i);
                }
            }
        }
        return super.damage(d);
    }

    private void cutLeg(int i) {
        if (cut[i]) return;
        cut[i] = true;
        cutCount++;
        sphAge = -1;
        body.look.on.add("cut" + (i + 1));
        sound("snap", Sound.ENTITY_ITEM_BREAK, 0.7f, 16);
        body.roll = (float) ((i < 3 ? 1 : -1) * Math.min(18, 6 * cutCount));
        if (cutCount >= DOWN_AT && phase != Phase.DOWN) {
            if (target != null) mark(null);
            prey = null;
            hover = DOWN_HOVER;
            body.roll = 0;
            state("down");
            body.squash(1f, 1f, 1f, 3);
            readyAt = age + cd(10);
            phase(Phase.DOWN);
        }
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " cut=" + cutCount;
    }
}
