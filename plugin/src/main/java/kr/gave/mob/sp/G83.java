package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G83 장승 게이브(떼, 돌격). 서 있는 사람 누구의 화면(시선 55도 안, 48블록 안, 가리지 않음)에라도 들어 있는 동안은 박힌 듯 선다. 아무도 안 보는
 * 순간 소리 없이 미끄러져(0.5블록/틱) 가장 가까운 사람 등 뒤로 온다. 멈추는 순간에만 나무가 '딱' 울고, 멈출 때마다 조금씩 더 기운다.
 * 등에 닿으면 앞으로 넘어지듯 덮쳐 18 피해, 붙잡아 깔고(돕기 연타 5번으로 탈출) 초당 5. 깔린 동안 동료가 15 넘게 쏘면 굴러 떨어진다. 5초면 일어선다.
 *
 * <p>시야 셈은 사람 수 x 장승 수라 4틱에 한 번(개체마다 어긋나게). 지켜보는 동안에는 덮치기 판 규칙이 물지 않게 noRules 를 켜 둔다(엔진 NO_POUNCE
 * 에 넣어 달라고 요청함).
 *
 * <p>팩 계약(tools/gabe/sp/g83.py): 모델 gave:gabe/g83(shrink 0.5) 상태 "". 소리 knock(20), 원인 글귀 cause_g83.
 */
public final class G83 extends Gabe {

    static final double SLIDE_V = 0.5, HOVER = 1.6, R = 0.5, SEG = 1.05;
    static final double WATCH_R = 48, COS_FOV = Math.cos(Math.toRadians(55)), BEHIND = 0.9, NEAR = 6;
    static final double POUNCE_DMG = 18, PIN_DMG = 5, TOPPLE = 15;
    static final int CHECK = 4, ROOT = 20, CLICKS = 5, PIN_MAX = 100, PIN_EVERY = 20, REST = 40;
    static final float LEAN_STEP = 4f, LEAN_MAX = 16f, FALL = 82f;
    static final int PACK = 4;

    private boolean watched = true, moving, packed;
    private float lean;
    private int leanSide = 1;
    private Player victim;
    private int pinAt = -1, readyAt;
    private double pinDamage;

    public G83(Game game, Species sp) {
        super(game, sp);
        radius = R;
        hover = HOVER;
        baseSpeed = SLIDE_V;
        leanSide = game.random.nextBoolean() ? 1 : -1;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g83", 1.0f, 3.4f);
    }

    @Override
    public void tick() {
        if (!packed) {
            packed = true;
            G82Pack.fill(this, PACK - 1, 6.0);
        }
        if (victim != null) {
            pin();
            return;
        }
        if (every(CHECK)) watched = watchedNow();
        noRules = watched;
        if (watched || age < readyAt) {
            hold();
            if (moving) stopped();
            moving = false;
            return;
        }
        Player p = nearest(400);
        if (p == null) {
            hold();
            return;
        }
        moving = true;
        if (target != p && dist(p) < 24) mark(p);
        double d = dist(p);
        if (d > NEAR) {
            follow(speed());
        } else {
            Location l = p.getLocation();
            Vector look = l.getDirection().setY(0);
            if (look.lengthSquared() < 1e-6) look = new Vector(0, 0, 1);
            Vector spot = new Vector(l.getX(), pos.getY(), l.getZ()).subtract(look.normalize().multiply(BEHIND));
            seek(spot, speed());
        }
        face(chest(p));
        // 기둥은 앞뒤로 숙이지 않음(쓰러질 때만)
        pitch = 0;
        if (touches(p, 0.25)) pounce(p);
    }

    /** 멈추는 순간: '딱' 하고 조금 더 기운다. */
    private void stopped() {
        sound("knock", Sound.BLOCK_WOOD_BREAK, 0.6f, 20);
        if (lean < LEAN_MAX) lean = Math.min(LEAN_MAX, lean + LEAN_STEP);
        if (game.random.nextInt(4) == 0) leanSide = -leanSide;
        body.roll = leanSide * lean;
        body.pose(2);
    }

    /** 서 있는 사람 가운데 누구라도 이것을 화면에 두고 있나(시선 55도, 48블록, 가리지 않음). */
    private boolean watchedNow() {
        for (Player p : game.mobs.standing()) {
            Location eye = p.getEyeLocation();
            double dx = pos.getX() - eye.getX(), dy = pos.getY() - eye.getY(), dz = pos.getZ() - eye.getZ();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > WATCH_R * WATCH_R) continue;
            double d = Math.sqrt(d2);
            if (d < 1.0) return true;
            Vector look = eye.getDirection();
            if ((look.getX() * dx + look.getY() * dy + look.getZ() * dz) / d < COS_FOV) continue;
            if (game.mobs.clearLine(eye.toVector(), pos)) return true;
        }
        return false;
    }

    /** 등에 닿음: 앞으로 넘어지며 덮쳐 깔아뭉갬. */
    private void pounce(Player p) {
        hit(p, POUNCE_DMG, "cause_g83");
        game.players.root(p, ROOT, "g83_pin");
        sound("knock", Sound.BLOCK_WOOD_BREAK, 0.6f, 20);
        noRules = false;
        if (!standing(p) || !grab(p, CLICKS)) {
            readyAt = age + cd(REST);
            return;
        }
        victim = p;
        pinAt = age;
        pinDamage = 0;
        noPush = true;
        pitch = FALL;
        body.roll = 0;
        hover = 0.6;
        body.pose(4);
        mark(p);
    }

    /** 깔고 누름: 매 PIN_EVERY 틱 피해. 풀리거나(탈출, 쓰러짐), 동료가 넘기거나, 5초면 일어선다. */
    private void pin() {
        Player p = victim;
        if (!standing(p) || game.players.grabbedBy(p) != this || age - pinAt > PIN_MAX || pinDamage >= TOPPLE) {
            getUp();
            return;
        }
        // 얼굴(기둥 위쪽)이 사람 위에 오게: 몸 중심은 사람 발에서 뒤로 조금
        Location l = p.getLocation();
        double r = Math.toRadians(yaw);
        Vector fwd = new Vector(-Math.sin(r), 0, Math.cos(r));
        seek(new Vector(l.getX(), pos.getY(), l.getZ()).subtract(fwd.multiply(0.5)), 0.3);
        if ((age - pinAt) % cd(PIN_EVERY) == 0 && age > pinAt) hit(p, PIN_DMG, "cause_g83");
    }

    private void getUp() {
        if (victim != null && victim.isOnline() && game.players.grabbedBy(victim) == this) game.players.release(victim);
        victim = null;
        pinAt = -1;
        noPush = false;
        pitch = 0;
        hover = HOVER;
        lean = 0;
        body.pose(6);
        mark(null);
        readyAt = age + cd(REST);
    }

    @Override
    public boolean damage(Damage d) {
        if (victim != null) pinDamage += d.amount();
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (victim != null && victim.isOnline() && game.players.grabbedBy(victim) == this) game.players.release(victim);
        victim = null;
        super.die(d);
    }

    /** 기둥 축을 따라 구 셋(쓰러지면 앞으로 누운 축). */
    @Override
    public List<double[]> spheres() {
        double a = Math.toRadians(pitch), r = Math.toRadians(yaw);
        double ax = -Math.sin(r) * Math.sin(a), ay = Math.cos(a), az = Math.cos(r) * Math.sin(a);
        List<double[]> out = new ArrayList<>(3);
        for (int i = -1; i <= 1; i++)
            out.add(new double[] {pos.getX() + ax * SEG * i, pos.getY() + ay * SEG * i, pos.getZ() + az * SEG * i, R});
        return out;
    }

    @Override
    public Vector head() {
        double a = Math.toRadians(pitch), r = Math.toRadians(yaw);
        return new Vector(pos.getX() - Math.sin(r) * Math.sin(a) * 0.9, pos.getY() + Math.cos(a) * 0.9, pos.getZ() + Math.cos(r) * Math.sin(a) * 0.9);
    }

    @Override
    public String toString() {
        return super.toString() + (victim != null ? " pin" : watched ? " watched" : " slide") + " lean=" + Math.round(lean);
    }
}
