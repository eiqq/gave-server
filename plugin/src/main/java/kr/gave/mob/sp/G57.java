package kr.gave.mob.sp;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Crates;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Corpse;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G57 종종걸음 게이브(떼). 죽마 다리 둘로 종종걸음 쳐 와 정강이를 찍고, 셀 것을 보면 멈춰 고개를 끄덕이며 센다.
 *
 * <p>걷기: 흐름장(follow), 0.2블록/틱. 닿으면 PECK 피해(PECK_CD 틱마다, 박자 배율). 그 사람 곁에 같은 종이 셋 이상 붙어 있으면 찍힐 때마다
 * 조준이 휘청(Guns.kick).
 *
 * <p>세기: SCAN 틱마다 SEE 블록 안의 아직 안 센 '셀 것'(진짜 보급 상자, 사체)을 찾고, 있으면 그 앞으로 가서 멈춰 COUNT 틱 세다가 잊어버려 처음부터
 * 한 번 더 센다(모두 2 x COUNT). 같은 것은 다시 안 센다. 세는 동안은 곁을 지나가도 안 덤빈다. 외곽 사체밭에 묶이지 않게 서 있는 사람에게서
 * PEOPLE_R 안의 것만, 한 번 센 뒤 REST 틱은 다시 안 셈. 바닥 재화 자리 조회는 엔진에 없어 사체와 보급 상자만 센다.
 *
 * <p>팩 계약(tools/gabe/sp/g57.py): 상태 "", "count", 소리 step(16), count(12), peck(12), 글귀 cause_g57.
 */
public final class G57 extends Gabe {

    static final double HOVER = 0.62, RADIUS = 0.3, SPEED = 0.2;
    /** 정강이 찍기 피해, 쿨다운(박자 배율), 휘청에 필요한 곁 수와 반경. */
    static final double PECK = 5, CROWD_R = 1.8;
    static final int PECK_CD = 24, CROWD = 3;
    /** 세기: 찾는 반경, 다가가 멈추는 거리, 한 번 세는 틱(두 번), 찾는 간격, 센 뒤 쉬는 틱, 사람에게서 이 안의 것만. */
    static final double SEE = 6, AT = 1.3, PEOPLE_R = 30;
    static final int COUNT = 50, SCAN = 10, REST = 60, GO_MAX = 80;

    enum Phase { WALK, GO, COUNT }

    private Phase phase = Phase.WALK;
    private int phaseAt, readyAt, countReady, nextStep;
    /** 지금 세는 것(Corpse 또는 Crate)과 그 자리. */
    private Object counting;
    private final Vector countAt = new Vector();
    private final Set<Object> counted = Collections.newSetFromMap(new IdentityHashMap<>());

    public G57(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void spawned() {
        nextStep = age + game.random.nextInt(30);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    @Override
    public void tick() {
        switch (phase) {
            case WALK -> walk();
            case GO -> go();
            case COUNT -> count();
        }
    }

    private void walk() {
        state("");
        follow(speed());
        Vector ahead = pos.clone().add(vel);
        face(ahead);
        pitch = 0;
        if (age >= nextStep) {
            if (game.random.nextInt(4) == 0) sound("step", Sound.BLOCK_BAMBOO_STEP, 1.6f, 16);
            nextStep = age + 14 + game.random.nextInt(16);
        }
        Player p = nearest(400);
        if (p != null && age >= readyAt && touches(p, 0.15)) peck(p);
        if (age >= countReady && every(SCAN)) look(p);
    }

    private void peck(Player p) {
        hit(p, PECK, "cause_g57");
        sound("peck", Sound.BLOCK_BAMBOO_HIT, 1.5f, 12);
        readyAt = age + cd(PECK_CD);
        Vector c = chest(p);
        int n = game.mobs.near(c.clone().setY(pos.getY()), CROWD_R, g -> g.sp == sp).size();
        if (n >= CROWD) Guns.kick(p, (float) (game.random.nextGaussian() * 1.8), (float) (game.random.nextGaussian() * 1.2));
    }

    /** 셀 것 찾기: 진짜 보급 상자, 사체(사람 곁의 것만). */
    private void look(Player near) {
        if (near == null || dist(near) > PEOPLE_R + SEE) return;
        double best = SEE * SEE;
        Object pick = null;
        Vector at = null;
        for (Crates.Crate c : game.crates.live()) {
            if (c.gone() || counted.contains(c)) continue;
            double d = c.pos.distanceSquared(pos);
            if (d < best) {
                best = d;
                pick = c;
                at = c.pos;
            }
        }
        for (Corpse c : game.mobs.corpses()) {
            if (c.gone() || counted.contains(c)) continue;
            double dx = c.pos.getX() - pos.getX(), dz = c.pos.getZ() - pos.getZ(), d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                pick = c;
                at = c.pos;
            }
        }
        if (pick == null) return;
        counting = pick;
        countAt.copy(at);
        counted.add(pick);
        phase(Phase.GO);
    }

    private boolean countGone() {
        if (counting instanceof Corpse c) return c.gone();
        if (counting instanceof Crates.Crate c) return c.gone();
        return true;
    }

    private void go() {
        if (countGone() || age - phaseAt > GO_MAX) {
            endCount();
            return;
        }
        Vector to = countAt.clone();
        double dx = to.getX() - pos.getX(), dz = to.getZ() - pos.getZ();
        if (dx * dx + dz * dz <= AT * AT) {
            hold();
            state("count");
            phase(Phase.COUNT);
            return;
        }
        seek(to.setY(pos.getY()), speed());
        face(pos.clone().add(vel));
        pitch = 0;
    }

    /** 고개를 끄덕이며 센다(pitch 를 번갈아), COUNT 틱 뒤 잊고 한 번 더. */
    private void count() {
        hold();
        int t = age - phaseAt;
        face(countAt.clone().setY(pos.getY()));
        int beat = (t + id) / 6;
        pitch = (beat & 1) == 0 ? 20f : -6f;
        if (t % 10 == 0) sound("count", Sound.BLOCK_NOTE_BLOCK_HAT, 1.8f, 12);
        if (t == COUNT) {
            // 잊어버림: 고개를 갸웃하고 처음부터
            body.squash(1.05f, 0.92f, 1.05f, 3);
        }
        if (t >= COUNT * 2 || countGone() && t >= COUNT) endCount();
    }

    private void endCount() {
        counting = null;
        pitch = 0;
        state("");
        countReady = age + REST;
        phase(Phase.WALK);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " counted=" + counted.size();
    }
}
