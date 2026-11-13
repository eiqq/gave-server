package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G177 우는 장막 게이브(3막 방해, 돌격). 키 3.4 블록의 장막 두른 키다리가 사람들이 가장 덜 보는 쪽(감독 ringPoint)에서 미끄러지듯 온다(0.12블록/틱).
 * 울음(VOLUME)은 사람마다 따로: 멀수록 크게(40 블록 넘으면 1.0), 다가올수록 잦아들어(6 블록이면 0.1) 6 블록 안에서는 울음이 그치고 장막이 땅에 끌리는
 * 소리만 난다. 멀 때는 귀 옆에서 우는 것처럼 그 사람 곁(몸 쪽 4 블록)에서 울린다(Props.soundTo, 한 사람에게만).
 * 3 블록 안에 들면 장막이 0.7초 부풀어 오르고(눈 뜸) 크게 휘둘러 반경 3.5 안 모두 16 피해와 밀림, 가장 가까운 한 사람을 장막 속으로 끌어들여
 * 2초 동안 화면을 젖은 천으로 덮고(본인만) 0.5초마다 7, 뱉을 때 4 블록 날리고 0.6초 넘어짐. 휘두른 뒤 2.5초 쉰다.
 * 장막은 탄을 절반만 받고 발 둘과 꼭대기 머리는 x1.5. 끌려 들어간 동료가 있을 때 장막을 쏘면 빨리 뱉는다.
 *
 * <p>팩 계약(tools/gabe/sp/g177.py): 모델 gave:gabe/g177(shrink 0.6) 상태 "", "swell", "sweep". 덮개 g177_veil. 소리 sob(64), drag(12), sweep(24),
 * spit(20). 글귀 cause_g177, cause_g177_wrap.
 */
public final class G177 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.12, HOVER = 1.75, REACH = 2.3, SWEEP_R = 3.5, SWEEP = 16, WRAP = 7;
    static final double LOUD_FAR = 40, QUIET_NEAR = 6, NEAR_VOL = 0.1, EAR = 4;
    static final int SWELL_T = 14, WRAP_T = 40, WRAP_EVERY = 10, REST_T = 50, SOB_EVERY = 46, DRAG_EVERY = 26, SPIT_ROOT = 12, SHOT_SPIT = 8;
    /** 맞는 구(몸 기준 높이): 꼭대기 머리, 장막 가운데, 발 둘. */
    static final double HEAD_Y = 1.08, VEIL_Y = -0.2, FOOT_Y = -1.45;

    enum Phase { APPROACH, SWELL, WRAP, REST }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, nextSob, nextDrag;
    private Player wrapped;
    private int wrapLeft, nextWrapHit;

    public G177(Game game, Species sp) {
        super(game, sp);
        radius = 0.6;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g177", 1.6f, 3.6f);
    }

    @Override
    protected void spawned() {
        nextSob = age + game.random.nextInt(SOB_EVERY);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        voices();
        switch (phase) {
            case APPROACH -> approach();
            case SWELL -> swell();
            case WRAP -> wrap();
            case REST -> rest();
        }
    }

    /** 사람마다 따로 울음: 멀수록 크게(귀 곁에서), 6 블록 안은 울음 대신 장막 끌리는 소리. */
    private void voices() {
        if (game.director.silent()) return;
        boolean sob = age >= nextSob, drag = age >= nextDrag;
        if (!sob && !drag) return;
        if (sob) nextSob = age + SOB_EVERY + game.random.nextInt(14);
        if (drag) nextDrag = age + DRAG_EVERY;
        boolean anyNear = false;
        for (Player p : game.mobs.standing()) {
            double d = dist(p);
            if (d <= QUIET_NEAR) {
                anyNear = true;
                continue;
            }
            if (!sob) continue;
            float vol = (float) (NEAR_VOL + (1 - NEAR_VOL) * Math.min(1, (d - QUIET_NEAR) / (LOUD_FAR - QUIET_NEAR)));
            // 귀 곁(몸 쪽으로 4 블록)에서: 멀수록 크게, 방향은 몸 쪽
            Location eye = p.getEyeLocation();
            Vector to = pos.clone().subtract(eye.toVector());
            double l = to.length();
            Location at = l > EAR ? eye.clone().add(to.multiply(EAR / l)) : loc();
            Props.soundTo(p, at, "gabe." + sp.key() + ".sob", Sound.ENTITY_GHAST_AMBIENT, vol, 0.94f + game.random.nextFloat() * 0.12f, 0.6f, -1);
        }
        if (drag && anyNear) sound("drag", Sound.BLOCK_WOOL_STEP, 0.6f, 12);
    }

    private void approach() {
        state("");
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            face(pos.clone().add(vel));
            return;
        }
        follow(speed());
        face(chest(p));
        if (dist(p) - 1.0 <= REACH && Math.abs(p.getLocation().getY() - (pos.getY() - HOVER)) < 2.5) {
            hold();
            mark(p);
            state("swell");
            body.scale(1.08f, cd(SWELL_T));
            phase(Phase.SWELL);
        }
    }

    /** 0.7초 부풀어 오름(예고), 그 뒤 크게 휘둘러 둘레를 쓸고 하나를 끌어들임. */
    private void swell() {
        hold();
        if (target != null) face(chest(target));
        if (age - phaseAt < cd(SWELL_T)) return;
        state("sweep");
        body.scale(1f, 4);
        sound("sweep", Sound.ENTITY_PHANTOM_FLAP, 0.4f, 24);
        Player pick = null;
        double pd = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double d = Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
            if (d > SWEEP_R || Math.abs(l.getY() - (pos.getY() - HOVER)) > 2.5) continue;
            hit(p, SWEEP, "cause_g177");
            Vector out = l.toVector().subtract(pos).setY(0);
            knock(p, out.lengthSquared() > 1e-6 ? out : new Vector(1, 0, 0), 0.6, 0.25);
            if (d < pd && standing(p)) {
                pd = d;
                pick = p;
            }
        }
        if (pick != null && grab(pick, 5)) {
            wrapped = pick;
            wrapLeft = WRAP_T;
            nextWrapHit = age + WRAP_EVERY;
            game.players.cover(pick, "g177_veil", WRAP_T + 4);
            phase(Phase.WRAP);
            return;
        }
        mark(null);
        phase(Phase.REST);
    }

    /** 장막 속: 2초 동안 화면 덮개와 0.5초마다 7, 그 뒤 뱉음(4 블록, 0.6초 넘어짐). */
    private void wrap() {
        hold();
        Player p = wrapped;
        if (p == null || !p.isOnline() || game.players.grabbedBy(p) != this) {
            endWrap(false);
            return;
        }
        game.players.carry(p, pos.clone().add(new Vector(0, -HOVER + 0.6, 0)));
        if (age >= nextWrapHit) {
            nextWrapHit = age + WRAP_EVERY;
            hit(p, WRAP, "cause_g177_wrap");
        }
        if (--wrapLeft <= 0) endWrap(true);
    }

    private void endWrap(boolean spit) {
        Player p = wrapped;
        wrapped = null;
        if (p != null && p.isOnline()) {
            if (game.players.grabbedBy(p) == this) game.players.release(p);
            game.players.uncover(p);
            if (spit && standing(p)) {
                Vector out = p.getLocation().toVector().subtract(pos).setY(0);
                if (out.lengthSquared() < 1e-4) {
                    double r = Math.toRadians(yaw);
                    out = new Vector(-Math.sin(r), 0, Math.cos(r));
                }
                knock(p, out, 0.9, 0.42);
                game.players.root(p, SPIT_ROOT, "g177_spit");
                sound("spit", Sound.ENTITY_LLAMA_SPIT, 0.6f, 20);
            }
        }
        mark(null);
        state("");
        phase(Phase.REST);
    }

    private void rest() {
        state("");
        hold();
        Player p = nearest(400);
        if (p != null) face(chest(p));
        if (age - phaseAt >= cd(REST_T)) phase(Phase.APPROACH);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY() + HEAD_Y, pos.getZ(), 0.45},
            new double[] {pos.getX(), pos.getY() + VEIL_Y, pos.getZ(), 0.8},
            new double[] {pos.getX() + 0.16, pos.getY() + FOOT_Y, pos.getZ(), 0.22},
            new double[] {pos.getX() - 0.16, pos.getY() + FOOT_Y, pos.getZ(), 0.22});
    }

    @Override
    public Vector head() {
        return pos.clone().add(new Vector(0, HEAD_Y, 0));
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || unhittable()) return false;
        double mul = 1;
        if (d.at() != null) {
            double y = d.at().getY() - pos.getY();
            // 꼭대기 머리와 장막 아래 발은 x1.5, 장막은 절반
            if (y >= HEAD_Y - 0.5 || y <= FOOT_Y + 0.3) mul = 1.5;
            else {
                mul = 0.5;
                if (wrapped != null) wrapLeft = Math.max(1, wrapLeft - SHOT_SPIT);
            }
        }
        return super.damage(mul == 1 ? d : d.times(mul));
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (wrapped != null && wrapped.isOnline()) game.players.uncover(wrapped);
        wrapped = null;
        super.die(d);
    }

    @Override
    public void remove() {
        if (wrapped != null && wrapped.isOnline()) {
            if (game.players.grabbedBy(wrapped) == this) game.players.release(wrapped);
            game.players.uncover(wrapped);
        }
        wrapped = null;
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + (wrapped != null ? " wrap=" + wrapped.getName() : "");
    }
}
