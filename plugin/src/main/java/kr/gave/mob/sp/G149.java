package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;

/**
 * G149 안 보이는 거인 게이브(4막, 정예, 돌격). 평소에는 등과 어깨에 꽂힌 막대기 여섯만 허공에 떠서 걷고, 발 디딘 자리에 흙먼지 고리가 일며,
 * 지나는 길의 떼가 저절로 갈라진다. 번쩍이면(조명탄 곁, 불, 폭발, 4 블록 안에서 난 총구 불빛) 0.5초(불은 4초, 조명탄 빛 안은 내내) 동안
 * 키 10 블록 짐승 꼴 몸 전체가 흰 윤곽으로 드러난다. 6 블록 보폭으로 사람이 가장 많은 쪽으로 걸어온다(0.11 블록/틱).
 *
 * <ul>
 * <li>발 디딤: 반경 2.5 안 28 과 넘어짐.</li>
 * <li>앞발 내려찍기: 앞 5 블록 안 사람이 있으면 빨간 고리 0.8초 뒤 반경 3.5 안 34.</li>
 * <li>물어 올리기: 목을 숙여 한 사람을 물고 8 블록 위로 들어 2초 흔든 뒤 내던져 20, 떨어져 12(공용 LAUNCH). 물린 동안 머리를 쏘면 놓음.</li>
 * <li>안 보이는 동안 받는 피해 절반. 사람마다 가까울수록 크고 빠른 두근거림(VOLUME, 한 사람에게만).</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g149.py): 모델 gabe/g149(shrink 0.2), 상태 ""(안 보임: 막대기 여섯), "seen"(목 든 몸), "low"(목 숙인 몸).
 * 소리 pulse(48), step(64), bite(32), roar(64). 글귀 cause_g149, cause_g149_slam, cause_g149_bite.
 *
 * <p>도감과 다른 점: 흰 윤곽은 발광 테두리가 아니라 드러나는 동안 맞은 번쩍임(흰색)을 길게 거는 것이다(0.5초 뒤에는 얼굴 가죽 그대로).
 * 흙먼지 고리 부품 대신 ShotFx 흙 고리. 한 웨이브 하나는 감독 몫(요청).
 */
public final class G149 extends Gabe {

    static final double HOVER = 5.0, SPEED = 0.11;
    static final double STRIDE = 6.0;
    /** 발: 몸 틀(옆, 앞) 자리. */
    static final double[][] FEET = {{1.56, 2.3}, {-1.56, -2.5}, {-1.56, 2.3}, {1.56, -2.5}};
    static final double STEP_R = 2.5, STEP_DMG = 28, SLAM_R = 3.5, SLAM_DMG = 34, SLAM_REACH = 5;
    static final int SLAM_WARN = 16, SLAM_CD = 60;
    /** 물어 올리기: 닿는 거리, 들어 올림 틱, 흔듦 틱, 높이, 던짐 피해, 떨어짐 피해, 쿨다운. */
    static final double BITE_REACH = 4.5, LIFT_H = 8, THROW_DMG = 20, FALL_DMG = 12;
    static final int LIFT_T = 16, SHAKE_T = 40, BITE_CD = 200;
    /** 드러남 틱: 번쩍임, 불. */
    static final int SEEN_T = 10, FIRE_T = 80;
    static final double UNSEEN_MUL = 0.5;

    enum Phase { WALK, SLAM, BITE }

    private Phase phase = Phase.WALK;
    private int phaseAt, seenUntil = -1, stepAt, slamAt, biteAt, foot;
    private final Vector slamC = new Vector();
    private Player held;
    private final Map<Player, Integer> pulseAt = new IdentityHashMap<>();
    private final List<double[]> sph = new ArrayList<>(8);
    private int sphAge = -1;
    private boolean low;

    public G149(Game game, Species sp) {
        super(game, sp);
        radius = 1.8;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        noPush = true;
        noMutate = true;
        noBite = true;
        ragKick = 0.3;
        hpPow = Director.Tune.HP_POW_BIG;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g149", 17f, 11f);
        body.viewRange(3f);
    }

    @Override
    protected void spawned() {
        stepAt = age + 10;
        biteAt = age + 100;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 드러남

    private boolean seen() {
        return age < seenUntil;
    }

    /** ticks 동안 드러남(흰 번쩍임을 앞 SEEN_T 틱). */
    private void reveal(int ticks) {
        boolean was = seen();
        seenUntil = Math.max(seenUntil, age + ticks);
        if (!was) {
            body.flash(game.worldTime() + SEEN_T);
            if (target != null) body.look.open = true;
        }
        state(low ? "low" : "seen");
    }

    private void checkReveal() {
        Vector c = pos;
        Vector feet = pos.clone().setY(pos.getY() - HOVER);
        if (game.shop.inFlare(c, 4) || game.shop.nearFlare(c, 6)) reveal(SEEN_T);
        if (game.shop.inFire(feet)) reveal(FIRE_T);
        if (game.mobs.blastsNear(c, 6 + 3, 2) > 0) reveal(SEEN_T);
        if (game.mobs.shotsNear(feet.clone().setY(feet.getY() + 1.6), 4 + 2.5, 1) > 0) reveal(SEEN_T);
        if (!seen() && !state.isEmpty()) {
            state("");
            body.look.open = false;
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        checkReveal();
        Player p = crowdPrey();
        if (p != target && phase != Phase.BITE) mark(p);
        if (!seen()) body.look.open = false;
        switch (phase) {
            case WALK -> walk(p);
            case SLAM -> slam();
            case BITE -> bite();
        }
        pulses();
        if (age % 10 == 0) part();
    }

    /** 사람이 가장 많은 쪽: 서 있는 사람 가운데 둘레 10 블록 안 사람이 가장 많은 사람. */
    private Player crowdPrey() {
        List<Player> st = game.mobs.standing();
        Player best = null;
        int bn = -1;
        double bd = 1e9;
        for (Player a : st) {
            Location la = a.getLocation();
            int n = 0;
            for (Player b : st) if (b.getLocation().distanceSquared(la) <= 100) n++;
            double d = G141Kit.hdist(pos, la);
            if (n > bn || (n == bn && d < bd)) {
                bn = n;
                bd = d;
                best = a;
            }
        }
        return best;
    }

    private void walk(Player p) {
        low = false;
        if (seen()) state("seen");
        if (p == null) {
            follow(speed());
            steps();
            return;
        }
        G141Kit.turnFlat(this, p.getLocation().toVector(), 2.5f);
        follow(speed());
        steps();
        double d = G141Kit.hdist(pos, p.getLocation());
        // 물어 올리기(목을 숙여 앞 아래)
        Vector mouth = G141Kit.at(this, 0, -1.4, 6.2);
        if (age >= biteAt && G141Kit.hdist(mouth, p.getLocation()) <= BITE_REACH && standing(p) && game.players.grabbedBy(p) == null) {
            if (grab(p, 6)) {
                held = p;
                low = true;
                state(seen() ? "low" : "");
                sound("bite", Sound.ENTITY_RAVAGER_ATTACK, 0.6f, 32);
                phase(Phase.BITE);
                return;
            }
        }
        // 앞발 내려찍기
        Vector front = G141Kit.at(this, 0, -HOVER, 3.2);
        if (age >= slamAt && G141Kit.hdist(front, p.getLocation()) <= SLAM_REACH) {
            slamC.copy(G141Kit.at(this, 0, 0, 3.6));
            slamC.setY(game.mobs.flow.floor(slamC.getX(), slamC.getZ()));
            G141Kit.warn(this, slamC, SLAM_R, SLAM_WARN + 2);
            phase(Phase.SLAM);
        }
        if (d > 40 && age % 40 == 0) G141Kit.dust(this, pos.clone().setY(pos.getY() - HOVER), 2.5f, 20);
    }

    /** 걸음: 6 블록 보폭, 발마다 차례로 흙먼지 고리와 발 디딤 피해. */
    private void steps() {
        if (age < stepAt) return;
        stepAt = age + Math.max(6, (int) Math.round(STRIDE / 4 / Math.max(0.02, speed())));
        double[] f = FEET[foot++ % FEET.length];
        Vector at = G141Kit.at(this, f[0], 0, f[1] + STRIDE * 0.25);
        at.setY(game.mobs.flow.floor(at.getX(), at.getZ()));
        G141Kit.stomp(this, at, 64, 7, 0.9f);
        sound("step", Sound.ENTITY_RAVAGER_STEP, 0.5f, 64);
        for (Player q : G141Kit.within(this, at, STEP_R, 3)) {
            hit(q, STEP_DMG, "cause_g149");
            Location l = q.getLocation();
            knock(q, new Vector(l.getX() - at.getX(), 0, l.getZ() - at.getZ()), 0.8, 0.5);
        }
        if (foot % 4 == 0) game.horizon.shake(0.4, 10);
    }

    private void slam() {
        hold();
        if (inPhase() == SLAM_WARN) {
            G141Kit.stomp(this, slamC, 64, 10, 1f);
            G141Kit.ring(this, slamC, SLAM_R, 14);
            for (Player q : G141Kit.within(this, slamC, SLAM_R, 3.5)) {
                hit(q, SLAM_DMG, "cause_g149_slam");
                Location l = q.getLocation();
                knock(q, new Vector(l.getX() - slamC.getX(), 0, l.getZ() - slamC.getZ()), 1.0, 0.6);
            }
            game.horizon.shake(0.5, 10);
        }
        if (inPhase() >= SLAM_WARN + 10) {
            slamAt = age + cd(SLAM_CD);
            phase(Phase.WALK);
        }
    }

    /** 물어 올림: 들어 올리고 흔든 뒤 내던짐. 머리가 맞아 놓으면 떨어짐 피해만. */
    private void bite() {
        hold();
        Player q = held;
        if (q == null || !q.isOnline() || game.players.grabbedBy(q) != this) {
            drop(false);
            return;
        }
        int t = inPhase();
        double lift = Math.min(1, t / (double) LIFT_T);
        Vector low0 = G141Kit.at(this, 0, -1.4, 6.2);
        Vector top = G141Kit.at(this, 0, -HOVER + LIFT_H, 5.2);
        Vector seat = low0.clone().multiply(1 - lift).add(top.clone().multiply(lift));
        if (t > LIFT_T) {
            double sh = Math.sin(t * 1.3) * 0.8;
            seat.add(G141Kit.left(this).multiply(sh));
        }
        game.players.carry(q, seat);
        if (t == LIFT_T) {
            low = false;
            if (seen()) state("seen");
        }
        if (t >= LIFT_T + SHAKE_T) {
            hit(q, THROW_DMG, "cause_g149_bite");
            game.players.release(q);
            Vector side = G141Kit.left(this).multiply(game.random.nextBoolean() ? 0.9 : -0.9).add(G141Kit.fwd(this).multiply(0.6));
            game.players.launch(q, 1.5, side, FALL_DMG, this, "cause_g149_bite");
            held = null;
            biteAt = age + cd(BITE_CD);
            phase(Phase.WALK);
        }
    }

    /** 놓음(머리가 맞았거나 끊김): 떨어짐 피해만. */
    private void drop(boolean fall) {
        Player q = held;
        held = null;
        low = false;
        if (q != null && game.players.grabbedBy(q) == this) {
            game.players.release(q);
            if (fall) game.players.launch(q, 0.3, new Vector(), FALL_DMG, this, "cause_g149_bite");
        }
        biteAt = age + cd(BITE_CD);
        phase(Phase.WALK);
    }

    /** 사람마다 가까울수록 크고 빠른 두근거림(그 사람에게만). */
    private void pulses() {
        for (Player q : game.mobs.standing()) {
            double d = G141Kit.hdist(pos, q.getLocation());
            if (d > 70) continue;
            Integer next = pulseAt.get(q);
            if (next != null && age < next) continue;
            int gap = (int) Math.max(7, Math.min(40, d * 0.7));
            pulseAt.put(q, age + gap);
            float vol = (float) Math.max(0.15, Math.min(1.6, 1.8 - d / 40));
            soundFor(q, "pulse", Sound.ENTITY_WARDEN_HEARTBEAT, vol);
        }
    }

    /** 지나는 길의 떼가 저절로 갈라짐: 몸 앞뒤 줄 3.5 블록 안 게이브를 옆으로 끌어냄. */
    private void part() {
        Vector f = G141Kit.fwd(this), l = G141Kit.left(this);
        for (Gabe o : game.mobs.near(pos.clone().setY(pos.getY() - HOVER + 1), 7, g -> g != this && !g.big && !g.sp.is(Role.BOSS) && !g.dead)) {
            double dx = o.pos.getX() - pos.getX(), dz = o.pos.getZ() - pos.getZ();
            double u = dx * l.getX() + dz * l.getZ(), fw = dx * f.getX() + dz * f.getZ();
            if (Math.abs(u) > 3.5 || fw < -4 || fw > 7) continue;
            double side = u >= 0 ? 1 : -1;
            Vector to = o.pos.clone().add(l.clone().multiply(side * (4.5 - Math.abs(u))));
            game.mobs.lure(o, to, 12);
        }
    }

    // ------------------------------------------------------------------ 맞음

    private Vector headAt() {
        return low || phase == Phase.BITE && inPhase() < LIFT_T ? G141Kit.at(this, 0, -1.4, 6.2) : G141Kit.at(this, 0, 4.1, 4.9);
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        for (double z : new double[] {-2.2, -0.7, 0.8, 2.1}) {
            Vector w = G141Kit.at(this, 0, 0.7, z);
            sph.add(new double[] {w.getX(), w.getY(), w.getZ(), 1.75});
        }
        Vector h = headAt();
        Vector n = G141Kit.at(this, 0, 0, 2.3).add(h).multiply(0.5);
        n.setY((pos.getY() + 1.2 + h.getY()) / 2);
        sph.add(new double[] {n.getX(), n.getY(), n.getZ(), 0.95});
        sph.add(new double[] {h.getX(), h.getY(), h.getZ(), 1.45});
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return headAt();
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (t == DamageType.EXPLOSION) reveal(SEEN_T);
        else if (t == DamageType.FIRE) reveal(FIRE_T);
        // 물린 동안 머리를 맞으면 놓음
        if (held != null && d.at() != null && d.at().distance(headAt()) <= 2.2) drop(true);
        Damage dd = seen() ? d : d.times(UNSEEN_MUL);
        return super.damage(dd);
    }

    @Override
    public void die(Damage d) {
        if (held != null) drop(true);
        // 사체는 드러난 몸으로 쓰러짐
        state("seen");
        body.look.open = false;
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (seen() ? " seen" : " unseen") + (held != null ? " held=" + held.getName() : "");
    }
}
