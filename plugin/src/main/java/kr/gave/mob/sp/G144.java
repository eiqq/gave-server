package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G144 소리 먹는 게이브(4막, 원거리, 방해). 삼발이 위 나팔 얼굴이 성큼성큼 걸어와(0.12/틱) 사람들에게서 18블록쯤에 선다. 주둥이를 가장 시끄러운 쪽
 * (30블록 안에서 최근 2초 동안 가장 많이 쏜 사람)으로 돌린다. 1초마다 30블록 안 사격 수 + 폭발 수 x5 를 빨아들여(FIRELOG: Mobs.shotsNear,
 * blastsNear) 목을 채운다(30 마다 한 단계, 넷째 단계 = 가득). 가득 차면 몸을 뒤로 젖히고 바닥에 붉은 부채꼴(길이 20, 끝 폭 9)을 그린 뒤 1초 뒤
 * 내지른다: 부채꼴 안 모두에게 단계마다 14(넷이면 56)와 6블록 밀림, 1.5초 굳음. 3초 동안 둘레 30블록이 조용하면 목이 한 단계 꺼지고, 다 꺼지거나
 * 내지른 뒤에는 4초 동안 축 늘어져 목구멍 얼굴이 주둥이로 밀려 나온다(그 얼굴에 피해 x3, 늘어진 동안 쏜 소리는 안 먹음). 평소 피해 x0.6, 불은
 * 소리가 없어 그대로. 다리(하나 체력 80)를 꺾으면 넘어져 주둥이가 하늘을 본다(내질러도 하늘로 감, 피해 그대로). 다리 곁 사람은 걷어차기 10.
 *
 * <p>바꾼 것: 나팔 목 단계는 range_dispatch 대신 부위 fill1..fill3, 삼발이는 부품 대신 같은 모델의 다리 자세(부위 leg0..leg2 = 꺾임).
 * 화염방사기 사격은 엔진 사격 기록에 덜 잡힌다(sp_engine 남은 문제).
 *
 * <p>팩 계약(tools/gabe/sp/g144.py): 모델 gabe/g144 상태 "", "limp", 부위 fill1..3, leg0..2, 부품 gabe/g144_fan. 소리 suck1..4(48),
 * blast(64), kick(16), limp(24). 글귀 cause_g144, cause_g144_kick.
 */
public final class G144 extends Gabe {

    static final double SPEED = 0.12, HOVER = 2.2, RADIUS = 1.2, KEEP = 18, HEAR = 30;
    /** 한 단계 채움, 단계 수(가득 = STAGES), 폭발 한 번 몫. */
    static final int STEP = 30, STAGES = 4, BLAST_SHOTS = 5;
    static final int POLL = 20, QUIET = 60, LEAN = 20, LIMP = 80, KICK_CD = 30;
    static final double FAN_LEN = 20, FAN_W0 = 1.3, FAN_W1 = 4.5, PER_STAGE = 14, PUSH = 1.1, KICK = 10;
    static final int ROOT = 30;
    static final double NORMAL_MUL = 0.6, THROAT_MUL = 3.0;
    static final double LEG_HP = 80, LEG_R = 0.35;
    /** 주둥이(몸 기준 앞), 목구멍 얼굴(늘어졌을 때). */
    static final double MOUTH_Z = 0.6, THROAT_Z = 0.45, THROAT_R = 0.55;

    enum Phase { WALK, LEAN, LIMP, DOWN }

    private Phase phase = Phase.WALK;
    private int phaseAt, pollAt = 20, quiet, fill, shownStage = -1, kickAt;
    private final double[] legHp = {LEG_HP, LEG_HP, LEG_HP};
    private final Vector blastDir = new Vector(0, 0, 1);
    private Body.Part fan;
    private int blastStage;

    public G144(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
        noPush = true;
        ragKick = 0.5;
    }

    @Override
    protected void build() {
        // 부채꼴 예고 부품이 몸 앞 20블록까지 뻗으므로 컬링 상자를 넓게(부품도 같은 상자)
        body = Body.of(this, "gabe/g144", 44f, 7f);
    }

    @Override
    protected void spawned() {
        G150.arm(game);
        pollAt = 20 + game.random.nextInt(20);
    }

    private void go(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private int stage() {
        return Math.min(STAGES, fill / STEP);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case WALK -> walk();
            case LEAN -> lean();
            case LIMP -> limp();
            case DOWN -> down();
        }
        if (phase != Phase.LIMP && age >= pollAt) poll();
        if (age >= kickAt && every(3)) kick();
        showStage();
    }

    /** 1초마다 소리 먹기와 꺼지기. */
    private void poll() {
        pollAt = age + POLL;
        int n = game.mobs.shotsNear(pos, HEAR, POLL) + BLAST_SHOTS * game.mobs.blastsNear(pos, HEAR, POLL);
        int was = stage();
        if (n > 0) {
            fill = Math.min(STEP * STAGES, fill + n);
            quiet = 0;
            int st = Math.max(1, stage());
            sound("suck" + Math.min(STAGES, st), Sound.ENTITY_PLAYER_BREATH, 0.5f + 0.15f * st, 48);
        } else if (fill > 0) {
            quiet += POLL;
            if (quiet >= QUIET) {
                quiet = 0;
                fill = Math.max(0, (was - 1) * STEP);
                if (fill == 0 && phase == Phase.WALK) goLimp();
            }
        }
        if (stage() != was) log("단계 " + was + " -> " + stage() + " 먹음 " + n);
        if (phase == Phase.WALK && stage() >= STAGES) startLean();
    }

    private void showStage() {
        int st = Math.min(3, stage());
        if (st == shownStage) return;
        shownStage = st;
        body.look.on.remove("fill1");
        body.look.on.remove("fill2");
        body.look.on.remove("fill3");
        for (int i = 1; i <= st; i++) body.look.on.add("fill" + i);
    }

    /** 30블록 안에서 최근 2초 동안 가장 많이 쏜 사람(없으면 가장 가까운 사람). */
    private Player loudest() {
        Player best = null;
        int bn = 0;
        for (Player q : game.mobs.standing()) {
            if (dist(q) > HEAR + 6) continue;
            int n = game.mobs.shotsBy(q, 40);
            if (n > bn) {
                bn = n;
                best = q;
            }
        }
        return best != null ? best : nearest(400);
    }

    private void walk() {
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        if (dist(p) > KEEP) follow(speed());
        else hold();
        Player loud = every(10) || target == null ? loudest() : target;
        if (loud != null) {
            if (loud != target) mark(loud);
            turnTo(chest(loud), 6f);
        }
        pitch = 0;
    }

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    private void startLean() {
        hold();
        Player loud = loudest();
        if (loud != null) {
            mark(loud);
            face(chest(loud));
        }
        pitch = -24;
        body.pose(6);
        blastDir.copy(forward());
        blastStage = Math.max(1, stage());
        showFan();
        sound("suck4", Sound.ENTITY_PLAYER_BREATH, 1.2f, 48);
        go(Phase.LEAN);
    }

    private void showFan() {
        if (fan == null) fan = body.attach("gabe/g144_fan");
        if (fan == null) return;
        fan.follow = false;
        float y = (float) (-(pos.getY() - floor()) + 0.03);
        float yr = (float) Math.toRadians(-yaw);
        fan.pose(new Matrix4f().translate(0, y, 0).rotateY(yr).translate(0, 0, (float) MOUTH_Z).scale((float) FAN_LEN), 0);
    }

    private void hideFan() {
        if (fan != null) {
            fan.remove();
            fan = null;
        }
    }

    private void lean() {
        hold();
        if (inPhase() < cd(LEAN)) return;
        blast();
    }

    /** 내지르기: 부채꼴 안 모두. 넘어졌으면 하늘로(피해 없음). */
    private void blast() {
        hideFan();
        pitch = 0;
        body.pose(3);
        sound("blast", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 64);
        Vector f = blastDir;
        double mx = pos.getX() + f.getX() * MOUTH_Z, mz = pos.getZ() + f.getZ() * MOUTH_Z;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double rx = l.getX() - mx, rz = l.getZ() - mz;
            double a = rx * f.getX() + rz * f.getZ();
            if (a < -0.5 || a > FAN_LEN) continue;
            double s = Math.abs(rx * f.getZ() - rz * f.getX());
            double half = FAN_W0 + (FAN_W1 - FAN_W0) * Math.max(0, a) / FAN_LEN;
            if (s > half || l.getY() - floor() > 4) continue;
            hit(q, PER_STAGE * blastStage, "cause_g144");
            log("내지름 맞음 " + q.getName() + " 단계 " + blastStage + " 체력 " + Math.round(game.players.get(q).hp));
            knock(q, f, PUSH, 0.3);
            game.players.root(q, ROOT, "g144");
        }
        // 소리 물결: 부채꼴 가운데 줄을 따라 고리 넷(ShotFx 충격파, 폭발 기록에는 안 듦)
        try {
            for (int i = 0; i < 4; i++) {
                double d = 3 + i * 5;
                Location c = new Location(game.world, mx + f.getX() * d, floor() + 0.15 + i * 0.05, mz + f.getZ() * d);
                ItemDisplay ring = game.shotFx.spawn(c, "fx_flat", ShotFx.RING, 0, ShotFx.scale(1.2f), Display.Billboard.FIXED, true, 9 + i * 2,
                    i == 0 ? ShotFx.KEY : ShotFx.NORMAL);
                game.shotFx.grow(ring, ShotFx.scale((float) ((FAN_W0 + (FAN_W1 - FAN_W0) * d / FAN_LEN) * 2.5 * 2)), 5 + i * 2);
            }
        } catch (RuntimeException ignored) {
            // 연출은 덤
        }
        fill = 0;
        goLimp();
    }

    private void goLimp() {
        log("늘어짐");
        hideFan();
        fill = 0;
        quiet = 0;
        state("limp");
        mark(null);
        hold();
        sound("limp", Sound.ENTITY_PLAYER_BREATH, 0.5f, 24);
        go(Phase.LIMP);
    }

    private void limp() {
        hold();
        if (inPhase() >= LIMP) {
            state("");
            pollAt = age + POLL;
            go(Phase.WALK);
        }
    }

    /** 넘어짐: 주둥이가 하늘(엎어진 채 제자리). 가득 차면 하늘로 내지름. */
    private void down() {
        hold();
        pitch = -70;
        if (stage() >= STAGES) {
            sound("blast", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 64);
            fill = 0;
        }
    }

    /** 다리 곁 사람 걷어차기. */
    private void kick() {
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz > 2.2 * 2.2 || l.getY() - floor() > 2) continue;
            hit(q, KICK, "cause_g144_kick");
            knock(q, toward(q), 0.6, 0.2);
            sound("kick", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 16);
            kickAt = age + cd(KICK_CD);
            return;
        }
    }

    // ------------------------------------------------------------------ 맞음

    private double[] at(double x, double y, double z, double r) {
        double rr = Math.toRadians(yaw), fx = -Math.sin(rr), fz = Math.cos(rr);
        return new double[] {pos.getX() + fx * z + fz * x, pos.getY() + y, pos.getZ() + fz * z - fx * x, r};
    }

    /** 다리 i 가운데(몸 기준: 뿌리와 발 사이). */
    private double[] leg(int i) {
        double a = Math.toRadians(90 + 120 * i);
        double x = Math.cos(a) * 0.8, z = -1.1 + Math.sin(a) * 0.8;
        return at(x, -1.35, z, LEG_R);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(6);
        out.add(at(0, 0, 0, RADIUS));
        if (phase == Phase.LIMP) out.add(at(0, -0.1, THROAT_Z + 0.3, THROAT_R));
        for (int i = 0; i < 3; i++) if (legHp[i] > 0) out.add(leg(i));
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        Vector p = d.at();
        if (p != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            // 맞은 점이 몸 구보다 다리 구에 가까우면 그 다리
            double[] b = at(0, 0, 0, RADIUS);
            double bd = Math.sqrt(sq(p.getX() - b[0]) + sq(p.getY() - b[1]) + sq(p.getZ() - b[2])) - RADIUS;
            int li = -1;
            for (int i = 0; i < 3; i++) {
                if (legHp[i] <= 0) continue;
                double[] l = leg(i);
                double dd = Math.sqrt(sq(p.getX() - l[0]) + sq(p.getY() - l[1]) + sq(p.getZ() - l[2])) - LEG_R;
                if (dd < bd) {
                    bd = dd;
                    li = i;
                }
            }
            if (li >= 0) {
                legHp[li] -= d.amount();
                game.mobs.onHurt(this, d, 0);
                if (legHp[li] <= 0) breakLeg(li);
                return true;
            }
            if (phase == Phase.LIMP) {
                double[] th = at(0, -0.1, THROAT_Z + 0.3, THROAT_R);
                double dt = Math.sqrt(sq(p.getX() - th[0]) + sq(p.getY() - th[1]) + sq(p.getZ() - th[2])) - THROAT_R;
                if (dt <= 0.45) return super.damage(d.times(THROAT_MUL));
                return super.damage(d);
            }
        }
        if (t == DamageType.FIRE || phase == Phase.DOWN) return super.damage(d);
        if (phase == Phase.LIMP) return super.damage(d);
        return super.damage(d.times(NORMAL_MUL));
    }

    private static double sq(double v) {
        return v * v;
    }

    private void breakLeg(int i) {
        legHp[i] = 0;
        body.look.on.add("leg" + i);
        log("다리 꺾임 " + i);
        sound("kick", Sound.BLOCK_WOOD_BREAK, 0.7f, 16);
        if (phase != Phase.DOWN) {
            hideFan();
            state("");
            mark(null);
            hover = 1.0;
            go(Phase.DOWN);
        }
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        hideFan();
        pitch = 0;
        super.die(d);
    }

    /** 시험과 판 뒤 분석용 기록(드문 사건만). */
    void log(String s) {
        game.plugin.getLogger().info("[G144] " + s);
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + " fill=" + fill + " st=" + stage() + " legs=" + (int) legHp[0] + "," + (int) legHp[1] + "," + (int) legHp[2];
    }
}
