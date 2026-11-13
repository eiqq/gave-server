package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;

/**
 * G92 팔 뻗는 게이브(원거리, 2막). 떼 뒤에서 걸어오다 24블록 안에서 멈춰 서고, 왼쪽 어깨에 접자처럼 접혀 있던 막대기 팔을 일직선으로 뻗어(초당 24블록)
 * 손끝 얼굴로 한 사람을 문다. 물면 팔을 접으며 그 사람을 제 앞(떼 한복판)까지 끌어오고, 놓은 뒤 지팡이로 내려친다.
 *
 * <p>도감: 물기 12, 끌려가는 2초 동안 초당 8, 끌려온 자리에서 지팡이 22. 뻗는 팔 길목의 다른 사람은 스쳐 8. 뻗기 0.7초 전 손끝 얼굴이 눈을 뜨고
 * 마디 펴지는 소리가 빨라지며 길목에 흙먼지 줄. 예고 때 본 자리로 끝까지 가므로 옆으로 뛰면 빗나가고, 접는 1.5초 동안 무방비. 뻗은 팔은
 * 마디 어디든 쏘면(팔 체력) 끊겨 끌려가던 사람이 떨어지고 팔이 8블록 짧아진다(세 번 끊기면 팔 없음).
 *
 * <p>바꾼 것: 끌려가는 사람은 붙잡힘(엔진 grab)이라 직접 쏘지 못하는 대신 연타 4번으로 빠져나온다(동료가 팔을 쏘거나 떼어 내도 됨).
 * 팔 체력은 몸 최대 체력의 23%(도감 60/260, 감독 체력 배율을 따라감).
 *
 * <p>팩 계약(tools/gabe/sp/g92.py): 몸 gave:gabe/g92(상태 "", 부위 out), 부품 gave:gabe/g92_arm(상태 a3..a24, 세계 축), gave:gabe/g92_hand
 * (상태 "", "bite"). 소리 unfold(32), bite(24), slam(16), snap(24). 글귀 cause_g92, cause_g92_cane, cause_g92_drag.
 */
public final class G92 extends Gabe implements Detachable {

    static final double SPEED = 0.16, HOVER = 1.32;
    /** 팔 최대 길이, 끊길 때마다 줄어드는 길이, 뻗는 빠르기(블록/틱), 접힌 길이. */
    static final double REACH = 24, CUT_LEN = 8, EXTEND_V = 1.2, FOLD_LEN = 1.6, OVERSHOOT = 1.5;
    /** 서는 거리(팔 길이에서 뺌), 지팡이 거리. */
    static final double STAND_SLACK = 2, CANE_R = 2.6;
    /** 예고, 끌기, 접기(빗나감), 쉬기, 지팡이 준비 틱. */
    static final int AIM = 14, DRAG = 40, FOLD = 30, COOLDOWN = 100, SLAM_WIND = 8, CANE_CD = 40;
    static final double BITE = 4, DRAG_DPS = 3, CANE = 8, GRAZE = 3;
    static final int ESCAPE = 4;
    /** 물기 판정(이번 틱에 지나간 팔 끝 선분에서 가슴까지), 스침(팔 전체에서 가슴까지), 팔 맞는 굵기. */
    static final double BITE_R = 1.2, GRAZE_R = 0.6, ARM_R = 0.3;
    static final double ARM_HP_FRAC = 60.0 / 260.0;
    /** 어깨(몸 중심 기준: 왼쪽, 위, 앞). g92.py SHOULDER 와 같게. */
    static final double SH_L = 0.4, SH_UP = 0.3, SH_F = -0.05;
    static final int[] LENGTHS = {3, 6, 9, 12, 15, 18, 21, 24};

    enum Phase { WALK, AIM, REACH, DRAG, FOLD, SLAM }

    private Phase phase = Phase.WALK;
    private int phaseAt, readyAt, caneAt = -100, nextUnfold;
    private Player prey, held;
    private double maxLen = REACH, len, reachLen, armHp = -1;
    private final Vector aim = new Vector(), dir = new Vector(0, 0, 1), shoulder = new Vector(), tip = new Vector(), tipPrev = new Vector();
    private final List<Player> grazed = new ArrayList<>(4);
    private Body.Part arm, hand;
    private Arm shootable;

    public G92(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        // 팔이 24블록까지 뻗으므로 컬링 상자를 넓게
        body = Body.of(this, "gabe/g92", 52f, 6f);
        arm = body.attach(Body.EMPTY);
        hand = body.attach(Body.EMPTY);
        if (arm != null) arm.follow = false;
        if (hand != null) hand.follow = false;
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
        if (armHp < 0) armHp = Math.max(1, maxHp * ARM_HP_FRAC); // 감독이 체력 배율을 곱한 뒤
        if (held != null && (!held.isOnline() || game.players.grabbedBy(held) != this)) dropHeld();
        if (prey != null && !standing(prey)) prey = null;
        geometry();
        switch (phase) {
            case WALK -> walk();
            case AIM -> aimTick();
            case REACH -> reach();
            case DRAG -> drag();
            case FOLD -> fold();
            case SLAM -> slam();
        }
        if (phase == Phase.REACH || phase == Phase.DRAG || phase == Phase.FOLD) poseArm(phase == Phase.REACH ? 1 : 2);
    }

    /** 걸어오다 팔이 닿는 거리에서 선다. 가까이 온 사람은 지팡이로. */
    private void walk() {
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        double d = dist(p);
        double stand = Math.max(0, maxLen - STAND_SLACK);
        if (d <= CANE_R && age - caneAt >= cd(CANE_CD)) {
            prey = p;
            hold();
            face(chest(p));
            phase(Phase.SLAM);
            return;
        }
        if (maxLen >= 3 && d <= stand && age >= readyAt && sees(p)) {
            prey = p;
            mark(p);
            aim.copy(chest(p));
            hold();
            face(aim);
            nextUnfold = age;
            phase(Phase.AIM);
            return;
        }
        if (d > Math.min(stand, 18) || maxLen < 3) follow(speed());
        else {
            hold();
            face(chest(p));
        }
    }

    /** 예고: 손끝 얼굴이 눈을 뜨고(실눈 뜸) 마디 펴지는 소리가 빨라지며 길목에 흙먼지 줄. 예고 때 본 자리(aim)로 끝까지 간다. */
    private void aimTick() {
        hold();
        face(aim);
        int t = inPhase();
        if (age >= nextUnfold) {
            sound("unfold", Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 1.6f, 32);
            nextUnfold = age + Math.max(2, 7 - t / 3);
        }
        if (t % 4 == 0) dust();
        if (t >= cd(AIM)) {
            Vector to = aim.clone().subtract(shoulder);
            double l = to.length();
            if (l < 1e-3) {
                phase(Phase.WALK);
                return;
            }
            dir.copy(to.multiply(1 / l));
            reachLen = Math.min(maxLen, l + OVERSHOOT);
            len = 0.5;
            tip.copy(shoulder).add(dir.clone().multiply(len));
            tipPrev.copy(tip);
            grazed.clear();
            body.look.on.add("out");
            if (arm != null) arm.model("gabe/g92_arm");
            if (hand != null) {
                hand.model("gabe/g92_hand");
                hand.state("");
                hand.look.open = true;
            }
            if (shootable == null || shootable.gone()) {
                shootable = new Arm();
                game.mobs.addShootable(shootable);
            }
            phase(Phase.REACH);
        }
    }

    /** 길목 바닥에 흙먼지 줄(가는 입자 몇 개). */
    private void dust() {
        Vector to = aim.clone().subtract(pos).setY(0);
        double l = Math.min(to.length(), maxLen);
        if (l < 1) return;
        to.normalize();
        for (double s = 1.5; s < l; s += 2.2) {
            double x = pos.getX() + to.getX() * s, z = pos.getZ() + to.getZ() * s;
            double y = game.mobs.flow.floor(x, z) + 0.1;
            game.world.spawnParticle(Particle.DUST_PLUME, x, y, z, 1, 0.08, 0.02, 0.08, 0.005);
        }
    }

    /** 팔이 일직선으로 뻗어 감. 이번 틱에 지나간 팔 끝 선분에 가슴이 닿은 사람을 문다. 길목 사람은 스침. */
    private void reach() {
        hold();
        tipPrev.copy(tip);
        len = Math.min(reachLen, len + EXTEND_V * Math.max(0.6, speed() / baseSpeed));
        tip.copy(shoulder).add(dir.clone().multiply(len));
        for (Player p : game.mobs.standing()) {
            Vector c = Mobs.chest(p);
            if (segDist(c, tipPrev, tip) <= BITE_R && game.players.grabbedBy(p) == null) {
                bite(p);
                return;
            }
            if (!grazed.contains(p) && segDist(c, shoulder, tipPrev) <= GRAZE_R) {
                grazed.add(p);
                hit(p, GRAZE, "cause_g92");
            }
        }
        if (len >= reachLen - 1e-6) {
            mark(null);
            if (hand != null) hand.look.open = false;
            phase(Phase.FOLD);
        }
    }

    private void bite(Player p) {
        hit(p, BITE, "cause_g92");
        soundAt(tip, "bite", Sound.ENTITY_SLIME_ATTACK, 0.8f, 24);
        if (hand != null) hand.state("bite");
        if (!standing(p) || !grab(p, ESCAPE)) {
            mark(null);
            phase(Phase.FOLD);
            return;
        }
        game.players.get(p).grabPin = false; // 좌석이 끌고 감
        held = p;
        prey = p;
        mark(p);
        phase(Phase.DRAG);
    }

    /** 팔을 접으며 문 사람을 제 앞까지 끌어온다(초당 8). 다 오면 놓고 지팡이. */
    private void drag() {
        hold();
        Player p = held;
        if (p == null) {
            phase(Phase.FOLD);
            return;
        }
        int t = inPhase();
        double step = Math.max(0.05, (reachLen - FOLD_LEN) / cd(DRAG));
        len = Math.max(FOLD_LEN, len - step);
        tip.copy(shoulder).add(dir.clone().multiply(len));
        face(tip);
        double seatY = Math.max(tip.getY() - 0.3, game.mobs.flow.floor(tip.getX(), tip.getZ()) + 0.6);
        game.players.carry(p, new Vector(tip.getX(), seatY, tip.getZ()));
        if (t > 0 && t % 10 == 0) hit(p, DRAG_DPS / 2, "cause_g92_drag");
        if (t % 6 == 0) sound("unfold", Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 1.4f, 32);
        if (len <= FOLD_LEN + 1e-6) {
            game.players.release(p);
            held = null;
            foldAway();
            prey = p;
            phase(Phase.SLAM);
        }
    }

    /** 빗나가거나 놓침: 1.5초 동안 팔을 접는다(본체 무방비). */
    private void fold() {
        hold();
        double step = Math.max(0.1, (reachLen - FOLD_LEN) / cd(FOLD));
        len = Math.max(FOLD_LEN, len - step);
        tip.copy(shoulder).add(dir.clone().multiply(len));
        if (inPhase() % 6 == 0) sound("unfold", Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 1.4f, 32);
        if (len <= FOLD_LEN + 1e-6 || inPhase() >= cd(FOLD) + 10) {
            foldAway();
            readyAt = age + cd(COOLDOWN);
            phase(Phase.WALK);
        }
    }

    /** 지팡이: 잠깐 치켜든 뒤 내려침(가까운 사람). */
    private void slam() {
        hold();
        Player p = standing(prey) ? prey : nearest(CANE_R + 1);
        if (p != null) face(chest(p));
        if (inPhase() == 1) body.squash(0.95f, 1.08f, 0.95f, 4);
        if (inPhase() < cd(SLAM_WIND)) return;
        body.squash(1.05f, 0.9f, 1.05f, 2);
        if (p != null && dist(p) <= CANE_R + 0.6) {
            hit(p, CANE, "cause_g92_cane");
            knock(p, toward(p), 0.5, 0.25);
            sound("slam", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 16);
        }
        caneAt = age;
        if (readyAt < age + cd(COOLDOWN) / 2) readyAt = age + cd(COOLDOWN) / 2;
        prey = null;
        mark(null);
        phase(Phase.WALK);
    }

    private void foldAway() {
        body.look.on.remove("out");
        if (arm != null) arm.model(Body.EMPTY);
        if (hand != null) {
            hand.model(Body.EMPTY);
            hand.state("");
            hand.look.open = false;
        }
        len = 0;
    }

    // ------------------------------------------------------------------ 기하

    /** 어깨 자리(세계). 몸 왼쪽 = (cos yaw, 0, sin yaw), 앞 = (-sin yaw, 0, cos yaw). */
    private void geometry() {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r), lx = Math.cos(r), lz = Math.sin(r);
        shoulder.setX(pos.getX() + lx * SH_L + fx * SH_F).setY(pos.getY() + SH_UP).setZ(pos.getZ() + lz * SH_L + fz * SH_F);
    }

    private void poseArm(int ticks) {
        if (len <= 0) return;
        Quaternionf q = new Quaternionf().rotationTo(0f, 0f, 1f, (float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        if (arm != null) {
            int L = LENGTHS[LENGTHS.length - 1];
            for (int v : LENGTHS) {
                if (v >= len - 1e-6) {
                    L = v;
                    break;
                }
            }
            arm.state("a" + L);
            double mx = (shoulder.getX() + tip.getX()) / 2 - pos.getX(), my = (shoulder.getY() + tip.getY()) / 2 - pos.getY(),
                    mz = (shoulder.getZ() + tip.getZ()) / 2 - pos.getZ();
            arm.pose(new Matrix4f().translate((float) mx, (float) my, (float) mz).rotate(q).scale(1f, 1f, (float) (len / L)), ticks);
        }
        if (hand != null) {
            hand.pose(new Matrix4f().translate((float) (tip.getX() - pos.getX()), (float) (tip.getY() - pos.getY()), (float) (tip.getZ() - pos.getZ()))
                    .rotate(q).translate(0f, 0f, 0.1f), ticks);
        }
    }

    /** 점 p 에서 선분 a-b 까지 거리. */
    static double segDist(Vector p, Vector a, Vector b) {
        double abx = b.getX() - a.getX(), aby = b.getY() - a.getY(), abz = b.getZ() - a.getZ();
        double l2 = abx * abx + aby * aby + abz * abz;
        double t = l2 < 1e-9 ? 0 : ((p.getX() - a.getX()) * abx + (p.getY() - a.getY()) * aby + (p.getZ() - a.getZ()) * abz) / l2;
        t = Math.max(0, Math.min(1, t));
        double dx = a.getX() + abx * t - p.getX(), dy = a.getY() + aby * t - p.getY(), dz = a.getZ() + abz * t - p.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 광선(o 에서 단위 방향 d)이 선분 a-b 둘레 반지름 r 캡슐에 처음 들어가는 거리(max 넘으면 +무한). 캡슐을 r 의 0.8 간격 구들로 채워 잰다:
     * 팔을 따라 거의 나란히 쏜 탄(팔 끝 쪽에서 본 탄)도 어깨가 아니라 가까운 마디에서 맞게.
     */
    public static double capsuleEntry(Vector o, Vector d, Vector a, Vector b, double r, double max) {
        double ax = a.getX(), ay = a.getY(), az = a.getZ(), bx = b.getX() - ax, by = b.getY() - ay, bz = b.getZ() - az;
        double len = Math.sqrt(bx * bx + by * by + bz * bz);
        int n = Math.max(1, (int) Math.ceil(len / (r * 0.8)));
        double best = Double.POSITIVE_INFINITY, rr = r * r;
        for (int i = 0; i <= n; i++) {
            double k = i / (double) n;
            double ox = ax + bx * k - o.getX(), oy = ay + by * k - o.getY(), oz = az + bz * k - o.getZ();
            double t = ox * d.getX() + oy * d.getY() + oz * d.getZ();
            if (t < -r) continue;
            double m2 = ox * ox + oy * oy + oz * oz - t * t;
            if (m2 > rr) continue;
            double at = Math.max(0, t - Math.sqrt(rr - m2));
            if (at < best) best = at;
        }
        return best <= max ? best : Double.POSITIVE_INFINITY;
    }

    private boolean armOut() {
        return !dead && (phase == Phase.REACH || phase == Phase.DRAG || phase == Phase.FOLD) && len > 0.3;
    }

    private void soundAt(Vector at, String name, Sound fb, float fbPitch, double range) {
        if (game.director.silent()) return;
        kr.gave.Props.soundAround(new Location(game.world, at.getX(), at.getY(), at.getZ()), "gabe." + sp.key() + "." + name, fb, 1f,
                0.94f + game.random.nextFloat() * 0.12f, fbPitch, range, -1);
    }

    // ------------------------------------------------------------------ 팔 맞음, 끊김

    /** 뻗은 팔(어깨에서 손끝까지 캡슐): 몸과 따로 맞는다. */
    private final class Arm implements Mobs.Shootable {
        @Override
        public double rayHit(Vector o, Vector d, double max) {
            if (!armOut()) return Double.POSITIVE_INFINITY;
            return capsuleEntry(o, d, shoulder, tip, ARM_R + 0.15, max);
        }

        @Override
        public boolean shot(Damage d) {
            if (!armOut()) return false;
            armHp -= d.amount();
            game.mobs.onHurt(G92.this, d, 0);
            if (d.by() != null) game.hud.hit(d.by(), false);
            if (armHp <= 0) cutArm();
            return false;
        }

        @Override
        public Vector center() {
            return shoulder.clone().add(tip).multiply(0.5);
        }

        @Override
        public boolean gone() {
            return dead || removed;
        }
    }

    /** 팔이 끊김: 끌던 사람이 떨어지고 손끝 얼굴이 바닥으로 떨어진다. 팔은 8블록 짧아진 채 다시 접힌다. */
    private void cutArm() {
        soundAt(tip, "snap", Sound.BLOCK_WOOD_BREAK, 0.8f, 24);
        Player p = held;
        held = null;
        if (p != null) game.players.release(p);
        Location at = new Location(game.world, tip.getX(), tip.getY(), tip.getZ());
        ItemDisplay fx = game.shotFx.spawn(at, "fx_quad", 0, 0, new Matrix4f(), Display.Billboard.FIXED, false, 30, ShotFx.NORMAL);
        if (fx != null) {
            fx.setItemStack(Body.item("gabe/g92_hand", "", new LookState(), game.worldTime()));
            fx.setBrightness(Body.LIGHT);
            Location ground = at.clone();
            ground.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + 0.25);
            game.shotFx.drift(fx, ground, (int) Math.max(4, Math.min(16, (at.getY() - ground.getY()) * 2)));
        }
        maxLen = Math.max(0, maxLen - CUT_LEN);
        armHp = Math.max(1, maxHp * ARM_HP_FRAC);
        foldAway();
        mark(null);
        prey = null;
        readyAt = age + cd(COOLDOWN);
        phase(Phase.WALK);
    }

    /** 끌던 사람이 풀려남(연타 탈출, 동료 떼기, 쓰러짐): 팔을 접는다. */
    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim != held) return;
        held = null;
        if (!dead && phase == Phase.DRAG) {
            mark(null);
            if (hand != null) hand.state("");
            phase(Phase.FOLD);
        }
    }

    private void dropHeld() {
        held = null;
        if (phase == Phase.DRAG) {
            mark(null);
            if (hand != null) hand.state("");
            phase(Phase.FOLD);
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY() + 0.8, pos.getZ(), 0.5});
        out.add(new double[] {pos.getX(), pos.getY() - 0.25, pos.getZ(), 0.38});
        return out;
    }

    @Override
    public Vector head() {
        return pos.clone().add(new Vector(0, 0.8, 0));
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 사체에 24블록 팔이 붙어 구르지 않게 부품을 먼저 뗀다
        if (arm != null) arm.remove();
        if (hand != null) hand.remove();
        arm = hand = null;
        body.look.on.remove("out");
        super.die(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + String.format(java.util.Locale.ROOT, " len=%.1f max=%.0f armHp=%.0f", len, maxLen, armHp)
                + (held != null ? " held=" + held.getName() : "");
    }
}
