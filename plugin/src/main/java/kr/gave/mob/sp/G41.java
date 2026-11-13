package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G41 가시 쏘는 게이브(원거리, 함정, 잠복).
 *
 * <p>도감: 반 블록 떠서 느리게 오고, 멈출 때마다 꼬리 막대기 끝을 제 입으로 '뚝' 부러뜨려 고개를 휘둘러 '픽' 튕겨 쏜다. 가시는 느려 옆걸음으로
 * 피하거나 쏴서 떨굴 수 있다. 맞으면 1 피해와 잠복: 본인 눈에는 안 보이고 남들 눈에만 그 사람 등에서 엔드 막대기 끝이 하나씩 솟아 날개처럼
 * 퍼덕이기 시작한다(15초). 다 자라면 등에서 새끼 가오리 3마리(보상 0)가 떨어져 나와 그 사람을 쫓는다(4 피해). 동료가 등 뒤에서 돕기 3초로
 * 짜내거나(잠복 공통) 등 막대기를 겨눠 쏘면(아군 피해 없이 막대기만 맞음) 풀린다. 잠복 쪽은 {@link G41Infest}.
 *
 * <p>몸: 닻 {@code gave:gabe/g41}(상태 {@code ""}, {@code "snap"}, 부위 {@code spent_l}, {@code spent_r}) + 날아가는 가시마다 부품
 * {@code gave:gabe/g41_thorn}(세계 축). 가시는 이 개체의 맞는 구에 들어 있어 쏘면 떨어진다. 소리 snap(24), flick(24), stick(16).
 * 원인 글귀 {@code cause_g41}(가시), {@code cause_g41_back}(새끼).
 */
public final class G41 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.1, HOVER = 0.95, FACE_R = 0.42;
    /** 이 거리 안에 보이면 멈춰서 쏨, 이보다 가까우면 물러남. */
    static final double SHOOT_RANGE = 16, BACK_OFF = 6;
    /** 꼬리 끝을 부러뜨리는 틱, 휘두르는 틱, 다음까지 쉬는 틱, 꼬리 끝이 다시 자라는 틱. */
    static final int SNAP_T = 14, WHIP_T = 7, COOLDOWN = 64, REGROW = 70;
    /** 가시: 빠르기(초당 9블록), 사거리, 맞는 구, 사람에게 닿는 거리, 피해, 박힌 채 남는 틱. */
    static final double THORN_V = 0.45, THORN_RANGE = 22, THORN_R = 0.16, THORN_TOUCH = 0.5, THORN_DMG = 4;
    static final int STUCK_T = 40;
    /** 입 자리(몸 기준 위, 앞). */
    static final double MOUTH_Y = -0.2, MOUTH_Z = 0.45;

    enum Phase { ROAM, SNAP, WHIP }

    /** 가시 하나(입에 문 것 → 날아감 → 박힘). */
    private final class Thorn {
        Body.Part p;
        final Vector at = new Vector(), v = new Vector();
        double gone;
        int stuckAt = -1;
        boolean flying;
    }

    private Phase phase = Phase.ROAM;
    private int phaseAt, readyAt, regrowL = -1, regrowR = -1;
    private Player prey;
    private boolean leftNext = true;
    private float aimYaw;
    private Thorn held;
    private final List<Thorn> thorns = new ArrayList<>(2);

    public G41(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        // 얼굴 0.8 + 뒤로 1.9 블록 꼬리(어느 쪽을 봐도 들게), 머리 위로 만 꼬리
        body = Body.of(this, "gabe/g41", 4.4f, 2.4f);
    }

    @Override
    protected void spawned() {
        readyAt = age + 30 + game.random.nextInt(40);
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
        if (prey != null && !standing(prey)) prey = null;
        switch (phase) {
            case ROAM -> roam();
            case SNAP -> snap();
            case WHIP -> whip();
        }
        for (int i = thorns.size() - 1; i >= 0; i--) thornTick(thorns.get(i));
        regrow();
    }

    /** 느리게 다가옴. 사거리 안에 보이면 멈춰서 꼬리 끝을 부러뜨린다. 너무 가까우면 물러남. */
    private void roam() {
        Player p = nearest(64);
        pitch = 0;
        if (p == null) {
            follow(speed());
            return;
        }
        double d = dist(p);
        face(chest(p));
        pitch = 0;
        if (d < BACK_OFF) vel.copy(toward(p).multiply(-speed() * 0.8));
        else if (d > SHOOT_RANGE - 3 || !sees(p)) follow(speed());
        else hold();
        if (wallAhead(0.5)) hold();
        if (age >= readyAt && d <= SHOOT_RANGE && sees(p) && thorns.size() < 2) {
            prey = p;
            mark(p);
            hold();
            state("snap");
            phase(Phase.SNAP);
        }
    }

    /** 꼬리를 머리 위로 말아 끝을 입에 가져와 '뚝'. */
    private void snap() {
        hold();
        if (prey != null) face(chest(prey));
        pitch = 0;
        int t = inPhase();
        if (t == 9) {
            sound("snap", Sound.ENTITY_ITEM_BREAK, 1.6f, 24);
            body.look.on.add(leftNext ? "spent_l" : "spent_r");
            if (leftNext) regrowL = age + cd(REGROW);
            else regrowR = age + cd(REGROW);
            leftNext = !leftNext;
            // 부러뜨린 끝을 입에 가로로 묾
            Thorn th = new Thorn();
            th.p = body.attach("gabe/g41_thorn");
            if (th.p != null) {
                th.p.pose(new Matrix4f().translate(0f, (float) MOUTH_Y, (float) MOUTH_Z).rotateY((float) (Math.PI / 2)).scale(1.4f), 0);
                held = th;
            }
            body.squash(1.08f, 0.9f, 1.05f, 2);
        }
        if (t >= SNAP_T) {
            body.squash(1f, 1f, 1f, 3);
            state("");
            if (prey == null || held == null) {
                dropHeld();
                rest();
                return;
            }
            aimYaw = yawTo(chest(prey));
            phase(Phase.WHIP);
        }
    }

    /** 고개를 옆으로 젖혔다가 휘둘러 '픽' 튕겨 쏨. */
    private void whip() {
        hold();
        int t = inPhase();
        pitch = 0;
        if (t == 0) {
            yaw = aimYaw - 55f;
            body.pose(3);
        } else if (t == 3) {
            yaw = aimYaw + 12f;
            body.pose(2);
            launch();
        } else if (t == 5) {
            yaw = aimYaw;
            body.pose(2);
        }
        if (t >= WHIP_T) rest();
    }

    private void launch() {
        Thorn th = held;
        held = null;
        if (th == null || th.p == null) return;
        double r = Math.toRadians(aimYaw);
        Vector from = new Vector(pos.getX() - Math.sin(r) * MOUTH_Z, pos.getY() + MOUTH_Y, pos.getZ() + Math.cos(r) * MOUTH_Z);
        Vector to = prey != null ? chest(prey) : from.clone().add(new Vector(-Math.sin(r), 0, Math.cos(r)).multiply(10));
        Vector d = to.subtract(from);
        if (d.lengthSquared() < 1e-6) d = new Vector(-Math.sin(r), 0, Math.cos(r));
        th.at.copy(from);
        th.v.copy(d.normalize().multiply(THORN_V));
        th.flying = true;
        th.p.follow = false;
        thorns.add(th);
        poseThorn(th, 0);
        sound("flick", Sound.ENTITY_ARROW_SHOOT, 1.5f, 24);
    }

    private void rest() {
        mark(null);
        prey = null;
        readyAt = age + cd(COOLDOWN) + game.random.nextInt(20);
        phase(Phase.ROAM);
    }

    private void dropHeld() {
        if (held != null && held.p != null) held.p.remove();
        held = null;
    }

    /** 부러뜨린 꼬리 끝이 다시 자람. */
    private void regrow() {
        if (regrowL >= 0 && age >= regrowL) {
            body.look.on.remove("spent_l");
            regrowL = -1;
        }
        if (regrowR >= 0 && age >= regrowR) {
            body.look.on.remove("spent_r");
            regrowR = -1;
        }
    }

    // ------------------------------------------------------------------ 가시

    private void thornTick(Thorn th) {
        if (th.p == null) {
            thorns.remove(th);
            return;
        }
        if (!th.flying) {
            // 땅에 박힌 채 잠깐 남음(몸이 움직이면 자리를 다시 맞춤)
            if (age - th.stuckAt >= STUCK_T) {
                th.p.remove();
                thorns.remove(th);
            } else if (vel.lengthSquared() > 1e-8) poseThorn(th, 2);
            return;
        }
        Vector prev = th.at.clone();
        th.at.add(th.v);
        th.gone += THORN_V;
        // 사람: 지난 자리와 지금 자리 사이 선분이 몸통(발 + 0.3 .. 1.7) 가까이 지나면 맞음
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double best = Double.MAX_VALUE;
            for (double h = 0.4; h <= 1.61; h += 0.4) best = Math.min(best, segDist(new Vector(l.getX(), l.getY() + h, l.getZ()), prev, th.at));
            if (best > THORN_TOUCH) continue;
            hit(p, THORN_DMG, "cause_g41");
            soundAt(th.at, "stick", Sound.ENTITY_ARROW_HIT, 1.4f, 16);
            if (standing(p)) G41Infest.of(game).infect(p, this);
            th.p.remove();
            thorns.remove(th);
            return;
        }
        double fl = game.mobs.flow.floor(th.at.getX(), th.at.getZ());
        boolean ground = th.at.getY() <= fl + 0.08;
        if (ground || th.gone >= THORN_RANGE || !game.mobs.clearLine(prev, th.at)) {
            if (ground) th.at.setY(fl + 0.1);
            th.flying = false;
            th.stuckAt = age;
            soundAt(th.at, "stick", Sound.ENTITY_ARROW_HIT, 1.4f, 16);
            poseThorn(th, 1);
            return;
        }
        poseThorn(th, 1);
    }

    private void poseThorn(Thorn th, int ticks) {
        if (th.p == null) return;
        float ty = (float) Math.atan2(-th.v.getX(), th.v.getZ());
        float tp = (float) -Math.atan2(th.v.getY(), Math.hypot(th.v.getX(), th.v.getZ()));
        th.p.pose(new Matrix4f().translate((float) (th.at.getX() - pos.getX()), (float) (th.at.getY() - pos.getY()), (float) (th.at.getZ() - pos.getZ()))
                .rotateY(-ty).rotateX(tp).scale(2.2f), ticks);
    }

    /** 가시를 쏴서 떨굼. */
    private void knock(Thorn th) {
        th.flying = false;
        th.stuckAt = age - STUCK_T + 8;
        th.v.setY(-0.3);
        double fl = game.mobs.flow.floor(th.at.getX(), th.at.getZ());
        th.at.setY(fl + 0.1);
        poseThorn(th, 6);
        soundAt(th.at, "stick", Sound.ENTITY_ARROW_HIT, 1.8f, 16);
    }

    // ------------------------------------------------------------------ 기하, 맞음, 죽음

    private float yawTo(Vector at) {
        return (float) Math.toDegrees(Math.atan2(-(at.getX() - pos.getX()), at.getZ() - pos.getZ()));
    }

    private void soundAt(Vector at, String name, Sound fb, float fbPitch, double range) {
        if (game.director.silent()) return;
        Props.soundAround(new Location(game.world, at.getX(), at.getY(), at.getZ()), "gabe." + sp.key() + "." + name, fb, 1f,
                0.94f + game.random.nextFloat() * 0.12f, fbPitch, range, -1);
    }

    static double segDist(Vector p, Vector a, Vector b) {
        double abx = b.getX() - a.getX(), aby = b.getY() - a.getY(), abz = b.getZ() - a.getZ();
        double l2 = abx * abx + aby * aby + abz * abz;
        double t = l2 < 1e-9 ? 0 : ((p.getX() - a.getX()) * abx + (p.getY() - a.getY()) * aby + (p.getZ() - a.getZ()) * abz) / l2;
        t = Math.max(0, Math.min(1, t));
        double dx = a.getX() + abx * t - p.getX(), dy = a.getY() + aby * t - p.getY(), dz = a.getZ() + abz * t - p.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), FACE_R});
        for (Thorn th : thorns) if (th.flying) out.add(new double[] {th.at.getX(), th.at.getY(), th.at.getZ(), THORN_R});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            double face = d.at().distanceSquared(pos);
            for (Thorn th : thorns) {
                if (!th.flying) continue;
                double ds = d.at().distanceSquared(th.at);
                if (ds < face && ds < (THORN_R + 0.45) * (THORN_R + 0.45)) {
                    knock(th);
                    if (d.by() != null) game.hud.hit(d.by(), false);
                    return true;
                }
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        clearThorns();
        super.die(d);
    }

    @Override
    public void remove() {
        clearThorns();
        super.remove();
    }

    private void clearThorns() {
        dropHeld();
        for (Thorn th : thorns) if (th.p != null) th.p.remove();
        thorns.clear();
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " thorns=" + thorns.size();
    }
}
