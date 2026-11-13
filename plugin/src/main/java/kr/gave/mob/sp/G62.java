package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G62 부채 게이브(떼, 1막). 활짝 편 접부채 꼴 얼굴이 하늘 가장자리에서 가랑잎처럼 좌우로 흔들리며 팔랑팔랑 떨어져 트럭 둘레 안쪽(R 4.5~10)에 내려앉는다
 * (바깥 떼를 건너뛴다). 땅에 닿으면 사북으로 서서 콩콩 뛰어 사람에게 다가가 부채질(앞 3블록 부채꼴, 5 피해와 2블록 밀침)을 하고, 코앞에서는 '착' 접혀
 * 막대기 묶음으로 찌른다(7). 떨어지며 머리에 닿으면 4.
 *
 * <p>대처: 떨어지는 동안은 느리고 한 발이면 찢어진다(받는 피해 x3). 선 채로는 옆에서 보면 종잇장이라 옆에서 쏜 탄은 스친다(x0.35). 접힌 것은 산탄에 꿰인다(x1.5).
 *
 * <p>팩(tools/gabe/sp/g62.py): 모델 {@code gave:gabe/g62} 상태 "" 편 채, "fan" 부채질, "shut" 접힘. 그림자 {@code gave:gabe/g62_shadow}(효과 디스플레이에 실음).
 * 소리 fan(부채 소리, 24), snap(부채 접힘, 16). 원인 글귀 cause_g62.
 */
public final class G62 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 땅에서 콩콩 뛰는 빠르기, 하늘에서 미끄러져 오는 빠르기(블록/틱, 감독 배율은 speed() 비로 곱함). */
    static final double HOP_V = 0.16, GLIDE_V = 0.3;
    /** 이 수평 거리 안에서 내려가기 시작(밖에서는 처음 높이로 미끄러져 옴). */
    static final double DESCEND_R = 16;
    /** 내려앉을 자리: 트럭(없으면 중앙 원 가운데)에서 이 반지름 띠. */
    static final double LAND_R0 = 4.5, LAND_R1 = 10;
    /** 서 있을 때 몸 가운데 높이(사북이 바닥 위 0.35), 콩콩 뛰는 높이. */
    static final double HOVER = 0.85, HOP_H = 0.45;
    /** 떨어지며 머리, 부채질, 접혀 찌르기 피해. */
    static final double HEAD_DMG = 4, FAN_DMG = 5, STAB_DMG = 7;
    /** 부채질 거리, 앞 부채꼴(내적), 밀침(블록/틱: 약 2블록), 찌르기 거리. */
    static final double FAN_RANGE = 3.2, FAN_DOT = 0.4, FAN_PUSH = 0.75, STAB_RANGE = 1.7;
    /** 부채질 길이와 바람 틱, 접기 준비, 찌르기 최대 틱, 쉬는 틱(박자 배율 cd). */
    static final int FAN_TICKS = 16, FAN_GUST = 8, SHUT_WIND = 7, STAB_MAX = 7, REST = 30, RECOVER = 10;
    static final double STAB_V = 0.45;
    /** 받는 피해 배율: 떨어지는 중, 옆에서 맞음, 접힌 채 산탄. */
    static final double FALL_MUL = 3.0, EDGE_MUL = 0.35, EDGE_DOT = 0.35, SHUT_PELLET = 1.5;
    /** 떨어지는 것을 포기하는 틱(무엇에 막혀도 웨이브 끝을 막지 않게). */
    static final int FALL_MAX = 1200;

    enum Phase { FALL, HOP, FAN, SHUT, STAB, RECOVER }

    private Phase phase = Phase.FALL;
    private int phaseAt, readyAt, nextFan;
    private final Vector landAt = new Vector();
    private double cruise = 10, hopA;
    private boolean headHit;
    private Player prey;
    private final Vector stabDir = new Vector(0, 0, 1);
    private ItemDisplay shadow;
    private int shadowAt;

    public G62(Game game, Species sp) {
        super(game, sp);
        radius = 0.55;
        baseSpeed = HOP_V;
        noPush = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g62", 1.6f, 1.4f);
    }

    @Override
    protected void spawned() {
        hopA = game.random.nextDouble() * Math.PI;
        nextFan = age + 20 + game.random.nextInt(40);
        cruise = Math.max(9, pos.getY() - floor());
        pickLanding();
    }

    /** 트럭 둘레 안쪽 무작위 자리(트럭이 없으면 중앙 원 가운데 둘레). */
    private void pickLanding() {
        Vector c = Truck.live() ? Truck.center() : new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
        double a = game.random.nextDouble() * Math.PI * 2, r = LAND_R0 + game.random.nextDouble() * (LAND_R1 - LAND_R0);
        landAt.setX(c.getX() + Math.cos(a) * r).setY(0).setZ(c.getZ() + Math.sin(a) * r);
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
            case FALL -> fall();
            case HOP -> hop();
            case FAN -> fan();
            case SHUT -> shut();
            case STAB -> stab();
            case RECOVER -> recover();
        }
    }

    /** 가랑잎처럼: 좌우로 크게 흔들리며 내려앉을 자리로, 가까워지면 비탈을 따라 내려감. 얼굴은 아래를 비스듬히 본다. */
    private void fall() {
        double dx = landAt.getX() - pos.getX(), dz = landAt.getZ() - pos.getZ(), hd = Math.hypot(dx, dz);
        double fl = floor();
        double k = speed() / baseSpeed;
        double v = GLIDE_V * k * Math.min(1, 0.25 + hd / 4);
        double sx = 0, sz = 0;
        if (hd > 1e-6) {
            sx = dx / hd;
            sz = dz / hd;
        }
        double sway = Math.sin(age * 0.13 + hopA * 3) * 0.2 * k;
        double vx = sx * v - sz * sway, vz = sz * v + sx * sway;
        double goalY = hd > DESCEND_R ? fl + cruise : fl + HOVER + (cruise - HOVER) * Math.max(0, hd - 1) / (DESCEND_R - 1);
        double vy = Math.max(-0.2, Math.min(0.08, (goalY - pos.getY()) * 0.12)) + Math.sin(age * 0.26 + hopA) * 0.035;
        if (hd < 2.5) vy = Math.min(vy, -0.06); // 자리 위에서는 꼭 내려앉음
        vel.setX(vx).setY(vy).setZ(vz);
        // 몸: 진행 쪽을 보며 얼굴을 아래로 비스듬히, 좌우로 흔들림(가랑잎). 자세 패킷을 아끼려고 4틱마다만 바꾼다(보간 4틱)
        if (every(4)) {
            yaw = (float) Math.toDegrees(Math.atan2(-vx, vz));
            pitch = (float) (48 + Math.sin(age * 0.2 + hopA) * 14);
            body.roll = (float) (Math.sin(age * 0.13 + hopA * 3) * 32);
            body.pose(4);
        }
        if (hd < 30 && age >= nextFan) {
            sound("fan", Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 24);
            nextFan = age + 35 + game.random.nextInt(30);
        }
        if (hd < DESCEND_R + 2) shadowFollow(fl);
        // 떨어지며 머리에 닿음(한 번)
        if (!headHit) {
            for (Player p : game.mobs.standing()) {
                if (pos.getY() > p.getLocation().getY() + 1.2 && touches(p, 0.25)) {
                    hit(p, HEAD_DMG, "cause_g62");
                    headHit = true;
                    break;
                }
            }
        }
        if (pos.getY() - fl <= HOVER + 0.25 || inPhase() >= FALL_MAX) land();
    }

    private void land() {
        flying = false;
        noPush = false;
        hover = HOVER;
        pitch = 0;
        body.roll = 0;
        removeShadow();
        sound("fan", Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 24);
        body.squash(1.15f, 0.8f, 1.15f, 2);
        readyAt = age + 6;
        phase(Phase.HOP);
    }

    /** 사북으로 서서 콩콩 뛰어 사람에게. 부채질 거리면 부채질, 코앞이면 접어 찌름. */
    private void hop() {
        if (inPhase() == 3) body.squash(1f, 1f, 1f, 3);
        follow(speed());
        hopA += 0.34;
        hover = HOVER + HOP_H * Math.abs(Math.sin(hopA));
        Player p = nearest(400);
        if (p == null) return;
        face(chest(p));
        pitch = 0;
        if (age < readyAt) return;
        double d = dist(p);
        if (d <= STAB_RANGE) {
            begin(p, Phase.SHUT);
        } else if (d <= FAN_RANGE) {
            begin(p, Phase.FAN);
        }
    }

    private void begin(Player p, Phase ph) {
        prey = p;
        mark(p);
        hold();
        hover = HOVER;
        if (ph == Phase.FAN) state("fan");
        else {
            state("shut");
            sound("snap", Sound.ITEM_BOOK_PUT, 1.4f, 16);
        }
        phase(ph);
    }

    /** 부채질: 제자리에서 앞뒤로 두 번 젖히고, 한가운데에 바람(앞 부채꼴 사람을 밀어내고 피해). */
    private void fan() {
        hold();
        if (standing(prey)) face(chest(prey));
        int t = inPhase();
        if (t == FAN_GUST) {
            sound("fan", Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 24);
            Vector f = forward();
            for (Player p : game.mobs.standing()) {
                Vector to = chest(p).subtract(pos).setY(0);
                double l = to.length();
                if (l > FAN_RANGE + 0.4 || l < 1e-6) continue;
                if (to.clone().multiply(1 / l).dot(f) < FAN_DOT) continue;
                hit(p, FAN_DMG, "cause_g62");
                knock(p, f, FAN_PUSH, 0.18);
            }
        }
        if (t >= FAN_TICKS) {
            state("");
            readyAt = age + cd(REST);
            mark(null);
            phase(Phase.HOP);
        }
    }

    /** '착' 접힘: 짧게 멈췄다가 접힌 막대기 묶음으로 곧게 찌름. */
    private void shut() {
        hold();
        if (!standing(prey)) {
            recoverNow();
            return;
        }
        face(chest(prey));
        pitch = Math.min(70, inPhase() * 12);
        if (inPhase() >= cd(SHUT_WIND)) {
            stabDir.copy(toward(prey));
            phase(Phase.STAB);
        }
    }

    private void stab() {
        dash(stabDir, STAB_V * speed() / baseSpeed);
        pitch = 75;
        for (Player p : game.mobs.standing()) {
            if (touches(p, 0.2)) {
                hit(p, STAB_DMG, "cause_g62");
                knock(p, stabDir, 0.3, 0.1);
                body.squash(0.8f, 1.1f, 1.2f, 2);
                recoverNow();
                return;
            }
        }
        if (inPhase() >= STAB_MAX || wallAhead(0.5)) recoverNow();
    }

    private void recoverNow() {
        hold();
        phase(Phase.RECOVER);
    }

    /** 접은 채 잠깐 서 있다가(산탄에 꿰이는 때) 다시 펼침. */
    private void recover() {
        vel.multiply(0.5);
        pitch = Math.max(0, pitch - 15);
        if (inPhase() >= RECOVER) {
            state("");
            pitch = 0;
            body.squash(1f, 1f, 1f, 3);
            sound("fan", Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 24);
            readyAt = age + cd(REST);
            mark(null);
            prey = null;
            phase(Phase.HOP);
        }
    }

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    // ------------------------------------------------------------------ 그림자(점묘 반달, 효과 디스플레이)

    private void shadowFollow(double fl) {
        double h = Math.max(0, pos.getY() - fl);
        float s = (float) Math.max(0.7, Math.min(1.8, 1.8 - h * 0.09));
        Location at = new Location(game.world, pos.getX(), fl + 0.04, pos.getZ());
        if (shadow == null || !shadow.isValid()) {
            if (age < shadowAt) return;
            shadowAt = age + 20;
            shadow = game.shotFx.spawn(at, "fx_quad", 0, 0, ShotFx.scale(s), Display.Billboard.FIXED, false, FALL_MAX, ShotFx.NORMAL);
            if (shadow == null) return;
            shadow.setItemStack(Body.item("gabe/g62_shadow", "", new LookState(), game.worldTime()));
            shadow.setBrightness(new Display.Brightness(12, 12));
            shadow.setTeleportDuration(2);
            return;
        }
        if (every(2)) {
            shadow.teleport(at);
            game.shotFx.grow(shadow, ShotFx.scale(s), 2);
        }
    }

    private void removeShadow() {
        if (shadow != null && shadow.isValid()) shadow.remove();
        shadow = null;
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        double r = "shut".equals(state) ? 0.35 : 0.6;
        return List.of(new double[] {pos.getX(), pos.getY() + 0.05, pos.getZ(), r});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (phase == Phase.FALL) return super.damage(d.times(FALL_MUL));
        if (gun && "shut".equals(state)) return super.damage(t == DamageType.PELLET ? d.times(SHUT_PELLET) : d);
        if (gun && d.dir() != null && !"shut".equals(state)) {
            Vector dir = d.dir().clone().setY(0);
            double l = dir.length();
            if (l > 1e-6 && Math.abs(dir.multiply(1 / l).dot(forward())) < EDGE_DOT) return super.damage(d.times(EDGE_MUL));
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        removeShadow();
        super.die(d);
    }

    @Override
    public void remove() {
        removeShadow();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase;
    }
}
