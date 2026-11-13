package kr.gave.mob.sp;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G109 몰리면 변하는 떼 게이브(떼, 돌격). 흩어져 있을 땐 종종 걸어와 살짝 무는 얼굴 벌레. 3블록 안에 동족이 여섯 넘게 모이면 1초 만에 떼 꼴
 * (검은 줄무늬, 짙은 홍조)로 바뀌어 1.8배 빠르게 튀어 오르며 달려들고 세게 문다. 흩어진 채 2초가 지나면 다시 순한 꼴.
 *
 * <p>팩 계약(tools/gabe/sp/g109.py): 모델 gave:gabe/g109, 상태 "calm"(기본), "swarm". 소리 swarm(떼 울음, 24), 원인 글귀 cause_g109.
 */
public final class G109 extends Gabe {

    static final double SPEED = 0.12, SWARM_MUL = 1.8, CROWD_R = 3.0;
    /** 이 수(자신 포함) 이상 모이면 떼 꼴, 바뀌는 데 걸리는 틱, 흩어진 뒤 순해지는 틱, 세는 간격. */
    static final int CROWD = 6, MORPH = 20, CALM_AFTER = 40, COUNT_EVERY = 10;
    static final double BITE = 2, SWARM_BITE = 5;
    /** 떼 꼴 달려들기: 시작 거리, 빠르기, 틱. */
    static final double LEAP_R = 2.8, LEAP_V = 0.4;
    static final int LEAP = 5, BITE_CD = 24, LEAP_CD = 34;
    static final double HOVER = 0.32;

    /** 떼 울음은 여러 마리가 같은 틱에 바뀌어도 한 번만. */
    private static long cryAt = Long.MIN_VALUE / 2;

    /** 감독이 낸 한 개체가 채우는 무리 수(자신 포함, G82Pack). */
    static final int PACK = 5;

    private boolean swarm, packed;
    private int crowdedAt = -1, looseAt, readyAt, leapAt, leapEnd = -1;
    private final Vector leapDir = new Vector(0, 0, 1);

    public G109(Game game, Species sp) {
        super(game, sp);
        radius = 0.3;
        hover = HOVER;
        baseSpeed = SPEED;
        state("calm");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g109", 1.0f, 0.7f);
    }

    @Override
    public void tick() {
        if (!packed) {
            packed = true;
            G82Pack.fill(this, PACK - 1, 2.5);
        }
        if (every(COUNT_EVERY)) count();
        Player p = nearest(400);
        if (age < leapEnd) {
            leap(p);
            return;
        }
        double v = speed() * (swarm ? SWARM_MUL : 1.0);
        follow(v);
        if (swarm) {
            // 튀어 오르며 줄지어 옴
            hover = HOVER + Math.abs(Math.sin((age + id * 7) * 0.55)) * 0.35;
        } else {
            hover = HOVER;
            // 느슨하게 흩어져 옴: 옆으로 흔들림
            double sway = Math.sin((age + id * 17) * 0.09) * 0.5 * v, vl = Math.hypot(vel.getX(), vel.getZ());
            if (vl > 1e-6) vel.add(new Vector(-vel.getZ() / vl * sway, 0, vel.getX() / vl * sway));
        }
        if (p == null) {
            face(pos.clone().add(vel));
            return;
        }
        if (dist(p) < 14) face(chest(p));
        else face(pos.clone().add(vel));
        if (age >= readyAt && touches(p, 0.05)) {
            bite(p);
        } else if (swarm && age >= leapAt && dist(p) <= LEAP_R) {
            leapDir.copy(toward(p));
            leapEnd = age + LEAP;
            leapAt = age + cd(LEAP_CD);
        }
    }

    /** 떼 꼴의 뛰어 달려들기(1.5블록쯤, 방향 못 틈). */
    private void leap(Player p) {
        dash(leapDir, LEAP_V);
        int left = leapEnd - age;
        hover = HOVER + Math.sin(Math.PI * (LEAP - left + 1) / (LEAP + 1.0)) * 0.6;
        face(pos.clone().add(leapDir));
        for (Player q : game.mobs.standing()) {
            if (age >= readyAt && touches(q, 0.1)) {
                bite(q);
                leapEnd = age;
                break;
            }
        }
    }

    private void bite(Player p) {
        hit(p, swarm ? SWARM_BITE : BITE, "cause_g109");
        readyAt = age + cd(BITE_CD);
        body.squash(1.2f, 0.8f, 1.2f, 2);
    }

    /** 3블록 안 동족 수로 꼴을 바꾼다. */
    private void count() {
        int n = game.mobs.near(pos, CROWD_R, g -> g.sp == sp && !g.dead).size();
        if (n >= CROWD) {
            looseAt = age;
            if (swarm) return;
            if (crowdedAt < 0) crowdedAt = age;
            if (age - crowdedAt >= MORPH) morph(true);
        } else {
            crowdedAt = -1;
            if (swarm && age - looseAt >= CALM_AFTER) morph(false);
        }
    }

    private void morph(boolean on) {
        swarm = on;
        crowdedAt = -1;
        state(on ? "swarm" : "calm");
        mark(on ? nearest(400) : null);
        body.squash(on ? 1.35f : 0.85f, on ? 1.35f : 0.85f, on ? 1.35f : 0.85f, 2);
        if (!on) {
            hover = HOVER;
            return;
        }
        leapAt = age + cd(10);
        long now = now();
        if (Math.abs(now - cryAt) >= 8) {
            cryAt = now;
            sound("swarm", Sound.ENTITY_SILVERFISH_AMBIENT, 0.7f, 24);
        }
    }

    @Override
    public String toString() {
        return super.toString() + (swarm ? " swarm" : " calm");
    }
}
