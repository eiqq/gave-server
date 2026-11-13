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
 * G09 멈칫 게이브(돌격). 1.2배 얼굴로 평범하게 오다 12블록 안에서 사람이 보이면 뚝 멈춰 공중에 못 박힌 듯 정지('흡'), 막대기 둘이 위로 곧게 서고
 * 실눈이 뜨이며(찍었다) 크기만 1.0에서 1.4로 1.5초 동안 커진다. 그 뒤 휙 사라졌다가 표적 등 뒤 2블록에 숨소리와 함께 나타나(3틱 보간) 0.5초 숨을
 * 고른 뒤 뒤에서 박는다(5 피해, 표적이 보는 쪽 = 떼 쪽으로 밀침). 정지 중 맞히면 정지가 늘지만 한 번 정지에 최대 +1.5초.
 *
 * <p>대처(구조물 없는 황야): 박을 방향은 등 뒤에 나타나는 순간 굳으므로 숨소리가 들리면 옆으로(또는 앞으로 달려) 비키면 빗나간다. 등 뒤에 동료가
 * 붙어 있거나 보급 상자처럼 막힌 칸을 등지면 나타날 자리가 없어 앞 3블록에 나타나 막대기가 축 처진 채 2초 멍해진다(이때 쏘기 쉬움).
 * 코앞까지 다가가 붙어 있어도 그 자리에서 멈춰 같은 공격을 한다(가까이 붙으면 아무 일도 없던 구멍을 막음).
 *
 * <p>사라졌다 나타날 때: 줄어든 채로 자리를 옮기고 클라 이동 보간(2틱)이 끝난 뒤에 커진다(커지는 얼굴이 사람을 꿰뚫고 날아가는 꼴이 안 보이게).
 *
 * <p>팩 계약(tools/gabe/sp/g09.py): 모델 {@code gave:gabe/g09} 상태 {@code ""}, {@code "halt"}, {@code "dazed"}. 소리 hup(반경 28),
 * breath(등 뒤 숨소리), ram. 글귀 cause_g09.
 */
public final class G09 extends Gabe {

    static final double SPEED = 0.16, HOVER = 2.0, BACK_HOVER = 1.55;
    /** 이 거리 안에서 보이면 멈춤(가까운 쪽 끝은 없음: 코앞에 붙어도 멈춰 공격). */
    static final double HALT_MAX = 12;
    /** 정지 틱(커짐 30틱), 맞으면 늘어나는 틱과 그 최대, 커지는 크기. */
    static final int HALT = 30, HALT_HIT = 10, HALT_EXTRA = 30;
    static final float GROW = 1.4f;
    /**
     * 사라짐 틱, 옮긴 뒤 숨은 채 기다리는 틱(클라 이동 보간), 나타나 커지는 보간 틱, 나타나 숨 고르는 틱(커지는 틱 포함, 이 동안 비키면 빗나감),
     * 박치기 최대 틱, 회복 틱, 멍한 틱, 다음 멈춤까지 쉬는 틱.
     */
    static final int VANISH = 3, SLIDE = 3, GROW_IN = 3, APPEAR = 10, LUNGE = 8, RECOVER = 30, DAZED = 40, COOLDOWN = 70;
    /** 등 뒤 거리, 앞(막혔을 때) 거리, 박치기 빠르기, 피해, 밀침, 동료가 막는 반경. */
    static final double BEHIND = 2.0, FRONT = 3.0, LUNGE_V = 0.45, DAMAGE = 6, PUSH = 0.9, BUDDY_R = 1.4;
    static final double FACE_R = 0.48;

    enum Phase { APPROACH, HALT, VANISH, APPEAR, LUNGE, RECOVER, DAZED }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, haltLen, readyAt, unsquashAt = -1;
    private Player prey;
    private float size = 1f;
    /** 사라진 뒤 자리를 옮겼나, 그 자리가 등 뒤인가(아니면 앞: 멍해짐). */
    private boolean moved, back;
    private final Vector dashDir = new Vector();

    public G09(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g09", 2.6f, 5.0f); // 꼭두각시(줄부터 발까지 3.2, 1.4 배로 커짐): 컬링 상자
    }

    @Override
    protected void spawned() {
        readyAt = age + 20;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private void size(float s, int ticks) {
        size = s;
        body.scale(s, ticks);
        radius = FACE_R * Math.max(0.3f, s);
    }

    @Override
    public void tick() {
        if (prey != null && !standing(prey) && phase != Phase.APPROACH && phase != Phase.RECOVER) {
            calm();
            return;
        }
        // 박은 뒤 찌그러진 몸 되돌리기(틱을 건너뛰어도 다음에 꼭)
        if (unsquashAt >= 0 && age >= unsquashAt) {
            body.squash(1f, 1f, 1f, 4);
            unsquashAt = -1;
        }
        switch (phase) {
            case APPROACH -> approach();
            case HALT -> halt();
            case VANISH -> vanish();
            case APPEAR -> appear();
            case LUNGE -> lunge();
            case RECOVER -> recover();
            case DAZED -> dazed();
        }
    }

    private void approach() {
        follow(speed());
        Player p = nearest(48);
        face(p != null ? chest(p) : pos.clone().add(vel));
        if (p != null && age >= readyAt) {
            double d = dist(p);
            if (d <= HALT_MAX && sees(p)) {
                prey = p;
                mark(p);
                hold();
                state("halt");
                haltLen = cd(HALT);
                size(GROW, cd(HALT));
                sound("hup", Sound.ENTITY_BREEZE_INHALE, 1.8f, 28);
                phase(Phase.HALT);
            }
        }
    }

    /** 공중 정지: 크기만 커짐(다가오는 것처럼 보임). */
    private void halt() {
        hold();
        face(chest(prey));
        if (inPhase() >= haltLen) {
            size(0.02f, VANISH);
            untargetable = true;
            phase(Phase.VANISH);
        }
    }

    /** 휙 사라짐: 줄어든 뒤 등 뒤(막혔으면 앞) 자리로 옮기고, 이동 보간이 끝난 뒤에 나타남. */
    private void vanish() {
        hold();
        int t = inPhase();
        if (t < VANISH) return;
        if (!moved) {
            Vector spot = behind(prey);
            back = spot != null;
            if (!back) spot = front(prey);
            pos.copy(spot);
            hover = back ? BACK_HOVER : 1.2;
            face(chest(prey));
            pitch = 0;
            // 박을 방향은 나타나는 순간 굳음(그 뒤에 비키면 빗나감)
            if (back) dashDir.copy(chest(prey).subtract(spot).setY(0));
            body.pose(0);
            moved = true;
            return;
        }
        if (t < VANISH + SLIDE) return;
        moved = false;
        untargetable = false;
        size(1f, GROW_IN);
        if (back) {
            soundAt(pos, "breath", Sound.ENTITY_HORSE_BREATHE, 1.4f, 12);
            phase(Phase.APPEAR);
        } else {
            state("dazed");
            mark(null);
            phase(Phase.DAZED);
        }
    }

    /** 등 뒤에서 숨을 고름(박을 방향을 그대로 봄: 표적이 비켜도 따라 돌지 않음). */
    private void appear() {
        hold();
        if (inPhase() >= APPEAR) phase(Phase.LUNGE);
    }

    /** 뒤에서 박음(굳힌 방향으로 일직선, 클라 보간이 끝난 뒤에 피해). */
    private void lunge() {
        dash(dashDir, LUNGE_V);
        if (touches(prey, 0.15)) {
            hit(prey, DAMAGE, "cause_g09");
            Vector fwd = prey.getLocation().getDirection().setY(0);
            if (fwd.lengthSquared() < 1e-6) fwd = dashDir.clone();
            knock(prey, fwd, PUSH, 0.25);
            sound("ram", Sound.ENTITY_SLIME_ATTACK, 0.6f, 16);
            body.squash(1.3f, 0.7f, 0.75f, 2);
            unsquashAt = age + 3;
            hold();
            phase(Phase.RECOVER);
            return;
        }
        if (inPhase() >= LUNGE || wallAhead(0.5)) {
            hold();
            phase(Phase.RECOVER);
        }
    }

    private void recover() {
        Player p = prey != null && standing(prey) ? prey : nearest(32);
        if (p != null) {
            vel.copy(toward(p).multiply(-speed() * 0.5));
            face(chest(p));
        } else hold();
        if (inPhase() >= cd(RECOVER)) calm();
    }

    /** 등 뒤가 막혀 앞에 나타남: 막대기 처진 채 멍함. */
    private void dazed() {
        hold();
        if (inPhase() >= cd(DAZED)) calm();
    }

    private void calm() {
        mark(null);
        prey = null;
        state("");
        hover = HOVER;
        if (size != 1f) size(1f, 6);
        untargetable = false;
        moved = false;
        readyAt = age + cd(COOLDOWN);
        phase(Phase.APPROACH);
    }

    /** 표적이 보는 반대쪽 2블록(바닥, 벽, 시선, 등 뒤 동료 확인). 자리가 없으면 null. */
    private Vector behind(Player p) {
        Location l = p.getLocation();
        Vector f = l.getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) f = toward(p).multiply(-1);
        f.normalize();
        Vector feet = l.toVector();
        Vector spot = feet.clone().subtract(f.multiply(BEHIND));
        if (!game.mobs.flow.walkable(spot.getX(), spot.getZ())) return null;
        double fl = game.mobs.flow.floor(spot.getX(), spot.getZ());
        if (Math.abs(fl - feet.getY()) > 1.1) return null;
        spot.setY(fl + BACK_HOVER);
        if (!game.mobs.clearLine(chest(p), spot)) return null;
        List<Player> all = game.mobs.standing();
        for (Player q : all) {
            if (q == p) continue;
            Vector c = q.getLocation().toVector();
            // 등 뒤에 붙은 동료(등과 그 자리 사이)
            if (c.clone().setY(0).distanceSquared(spot.clone().setY(0)) <= BUDDY_R * BUDDY_R
                    || c.clone().setY(0).distanceSquared(feet.clone().subtract(l.getDirection().setY(0).normalize()).setY(0)) <= 1.0) return null;
        }
        return spot;
    }

    private Vector front(Player p) {
        Location l = p.getLocation();
        Vector f = l.getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) f = toward(p).multiply(-1);
        f.normalize();
        Vector spot = l.toVector().add(f.multiply(FRONT));
        if (!game.mobs.flow.walkable(spot.getX(), spot.getZ())) spot = l.toVector().add(toward(p).multiply(-FRONT));
        spot.setY(game.mobs.flow.floor(spot.getX(), spot.getZ()) + 1.2);
        return spot;
    }

    private void soundAt(Vector at, String name, Sound fb, float fbPitch, double range) {
        if (game.director.silent()) return;
        Props.soundAround(new Location(game.world, at.getX(), at.getY(), at.getZ()), "gabe." + sp.key() + "." + name, fb, 1f,
                0.94f + game.random.nextFloat() * 0.12f, fbPitch, range, -1);
    }

    @Override
    public boolean damage(Damage d) {
        if (phase == Phase.HALT && haltLen < cd(HALT) + HALT_EXTRA) haltLen = Math.min(cd(HALT) + HALT_EXTRA, haltLen + HALT_HIT);
        return super.damage(d);
    }

    /** 맞는 구: 머리(얼굴, radius 가 크기를 따라감) + 헝겊 몸통(머리 아래 0.88, 크기를 따라감). 2026-11-12 외형 꼭두각시. */
    @Override
    public List<double[]> spheres() {
        double s = Math.max(0.3f, size), pr = Math.toRadians(pitch), yr = Math.toRadians(yaw);
        double y2 = -0.88 * s * Math.cos(pr), z2 = -0.88 * s * Math.sin(pr);
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), radius},
                new double[] {pos.getX() - z2 * Math.sin(yr), pos.getY() + y2, pos.getZ() + z2 * Math.cos(yr), 0.3 * s});
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + String.format(java.util.Locale.ROOT, " size=%.2f", size);
    }
}
