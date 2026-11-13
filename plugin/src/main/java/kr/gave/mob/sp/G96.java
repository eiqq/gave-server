package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G96 쇠 먹는 게이브(튼튼이, 방패). 탄을 받아먹어 비늘 갑옷으로 두르고 자라는 짐승 얼굴.
 *
 * <p>도감: 배를 끌며 느릿하게 기어온다(0.13). 총알과 산탄은 피해의 30%만 들고 나머지 70%는 먹어 자란다(다섯 단계, 길이 1.8 에서 3.8). 불, 폭발,
 * 저격, 체력의 15% 를 넘는 한 방은 그대로 들고 안 자란다. 많이 맞으면 공처럼 말아 굴러와(0.3) 들이받는다(14 + 단계마다 6, 밀침). 사람 곁에서
 * 풀리며 꼬리 집게를 휘두른다(12). 말았다 풀리는 동안은 비늘 없는 배가 드러나 총알도 그대로 든다. 다 자라면(5단계) 3초 비늘을 곤두세운 뒤 사방으로
 * 쏜다(반경 10, 16). 쏜 뒤에는 비늘이 빠져 4단계로 내려간다.
 *
 * <p>팩 계약(tools/gabe/sp/g96.py): 모델 gave:gabe/g96(상태 "", "ball", 부위 s1..s4), 부품 gave:gabe/g96_scale(쏘는 비늘). 소리 munch, bristle,
 * ram, burst, pinch. 원인 글귀 cause_g96, cause_g96_scale.
 */
public final class G96 extends Gabe {

    static final double SPEED = 0.13, BALL_SPEED = 0.3;
    /** 단계마다 크기(0..5): 길이 1.8 에서 3.8 블록쯤. */
    static final float[] SCALE = {0.9f, 1.1f, 1.3f, 1.5f, 1.7f, 1.9f};
    /** 먹은 양이 maxHp 의 이 몫마다 한 단계. */
    static final double STAGE_EAT = 0.3;
    /** 총알이 드는 몫, 먹지 못하는 한 방(maxHp 몫). */
    static final double GUN_TAKE = 0.3, BIG_SHOT = 0.15;
    /** 최근 먹은 양(틱마다 줄어듦)이 maxHp 의 이 몫을 넘으면 말아 굴러옴. */
    static final double CURL_AT = 0.14, RECENT_DECAY = 0.95;
    static final double RAM = 14, RAM_STEP = 6, PINCH = 12, BURST = 16, BURST_R = 10;
    static final int BALL_MAX = 110, BELLY = 14, SWING_T = 16, SWING_HIT = 11, BRISTLE_T = 60, BURST_CD = 200, SWING_CD = 50, CURL_CD = 80;

    enum Phase { CRAWL, BALL, SWING, BRISTLE }

    private Phase phase = Phase.CRAWL;
    private int phaseAt, stage, swingReady, curlReady, burstReady, bellyUntil = -1, munchAt = -100, rollUntil = -1;
    private double eaten, recent;
    private Player prey;
    private float rollSide, spin;

    /** 쏘아 날아가는 비늘 조각. */
    private final List<Body.Part> flying = new ArrayList<>();
    private final List<Vector> flyDir = new ArrayList<>();
    private int flyAt = -1;

    public G96(Game game, Species sp) {
        super(game, sp);
        baseSpeed = SPEED;
        ragKick = 0.5;
        size();
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g96", 3.0f * SCALE[0], 2.0f * SCALE[0]);
        body.scale(SCALE[0], 0);
    }

    private float s() {
        return SCALE[Math.min(stage, SCALE.length - 1)];
    }

    /** 단계에 맞춰 맞는 구, 높이, 컬링 상자. */
    private void size() {
        float s = s();
        radius = 0.72 * s;
        hover = (phase == Phase.BALL ? 0.8 : 0.86) * s;
        // 컬링 상자는 단계가 바뀔 때만(Body.size 는 자세를 바로 다시 보냄)
        if (body != null && Math.abs(body.width - 3.0f * s) > 1e-3) body.size(3.0f * s, 2.0f * s);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private Vector fwd() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        recent *= RECENT_DECAY;
        flyTick();
        if (rollUntil >= 0 && age >= rollUntil && phase != Phase.SWING) {
            body.roll = 0;
            rollUntil = -1;
        }
        switch (phase) {
            case CRAWL -> crawl();
            case BALL -> ball();
            case SWING -> swing();
            case BRISTLE -> bristle();
        }
    }

    /** 배를 끌며 기어 옴. 먹으면 굴러오고, 곁이면 꼬리 집게, 다 자랐으면 비늘 쏘기. */
    private void crawl() {
        follow(speed());
        Player p = nearest(400);
        if (p == null) {
            if (vel.lengthSquared() > 1e-6) face(pos.clone().add(vel));
            pitch = 0;
            return;
        }
        if (vel.lengthSquared() > 1e-6 && dist(p) > 4) face(pos.clone().add(vel));
        else face(chest(p));
        pitch = 0;
        double d = dist(p);
        if (stage >= 5 && age >= burstReady && d <= BURST_R + 2) {
            prey = p;
            mark(p);
            hold();
            sound("bristle", Sound.BLOCK_CHAIN_PLACE, 0.6f, 24);
            body.squash(1.08f, 1.12f, 1.0f, 6);
            phase(Phase.BRISTLE);
            return;
        }
        if (recent > CURL_AT * maxHp && age >= curlReady) {
            curl(p);
            return;
        }
        if (age >= swingReady && d <= 1.6 + 1.2 * s()) {
            prey = p;
            mark(p);
            phase(Phase.SWING);
        }
    }

    private void curl(Player p) {
        prey = p;
        mark(p);
        state("ball");
        phase = Phase.BALL;
        phaseAt = age;
        size();
        body.squash(1f, 1f, 1f, 3);
        sound("bristle", Sound.BLOCK_CHAIN_PLACE, 0.9f, 24);
    }

    /** 공처럼 말려 굴러와 들이받음. */
    private void ball() {
        if (!standing(prey)) {
            Player q = nearest(400);
            if (q == null) {
                uncurl(false);
                return;
            }
            prey = q;
            mark(q);
        }
        double v = speed() * BALL_SPEED / SPEED;
        seek(prey.getLocation().toVector(), v);
        face(chest(prey));
        // 구르는 모습: 앞으로 굴러 감는 pitch
        spin = (float) ((spin + Math.toDegrees(v / Math.max(0.4, 0.8 * s()))) % 360f);
        pitch = spin;
        for (Player q : game.mobs.standing()) {
            if (!touches(q, 0.15)) continue;
            hit(q, RAM + RAM_STEP * stage, "cause_g96");
            knock(q, toward(q), 0.9, 0.35);
            sound("ram", Sound.BLOCK_ANVIL_LAND, 0.8f, 20);
            body.squash(1.15f, 0.85f, 1.15f, 2);
            uncurl(true);
            return;
        }
        if (inPhase() >= BALL_MAX || wallAhead(0.9 * s())) uncurl(true);
    }

    /** 풀림: 비늘 없는 배가 잠깐 드러나고 곁의 사람에게 꼬리 집게. */
    private void uncurl(boolean swing) {
        state("");
        pitch = 0;
        hold();
        bellyUntil = age + BELLY;
        curlReady = age + cd(CURL_CD);
        recent = 0;
        body.roll = rollSide >= 0 ? 32f : -32f;
        rollUntil = age + 10;
        if (swing && prey != null && standing(prey) && dist(prey) <= 3 + 1.2 * s()) {
            phase(Phase.SWING);
        } else {
            calm();
        }
        size();
    }

    /** 꼬리를 사람 쪽으로 돌려 집게를 휘두름. */
    private void swing() {
        hold();
        int t = inPhase();
        if (prey == null || !standing(prey)) {
            calm();
            return;
        }
        if (t < SWING_HIT) {
            // 등(꼬리)이 사람 쪽을 보게 돌아섬
            Vector away = pos.clone().multiply(2).subtract(chest(prey));
            turnTo(away, 24f);
            pitch = 0;
        }
        if (t == 2) sound("pinch", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 16);
        if (t == SWING_HIT) {
            Vector f = fwd();
            double s = s();
            Vector tail = pos.clone().subtract(f.clone().multiply(1.55 * s));
            body.squash(1.05f, 0.95f, 1.1f, 2);
            for (Player q : game.mobs.standing()) {
                Vector c = chest(q);
                if (c.distanceSquared(tail) > (1.3 * s) * (1.3 * s) && dist(q) > 1.2 + 0.9 * s) continue;
                hit(q, PINCH, "cause_g96");
                Vector k = toward(q);
                knock(q, k, 0.6, 0.2);
            }
        }
        if (t >= SWING_T) {
            body.squash(1f, 1f, 1f, 3);
            calm();
        }
    }

    /** 다 자람: 비늘을 곤두세우고(3초) 사방으로 쏨. */
    private void bristle() {
        hold();
        int t = inPhase();
        body.roll = (float) (Math.sin(t * 1.3) * (2 + t * 0.08));
        if (t % 20 == 10) sound("bristle", Sound.BLOCK_CHAIN_PLACE, 0.6f + t * 0.004f, 24);
        if (prey != null && standing(prey)) face(chest(prey));
        pitch = 0;
        if (t < BRISTLE_T) return;
        body.roll = 0;
        burst();
        burstReady = age + cd(BURST_CD);
        calm();
    }

    private void burst() {
        sound("burst", Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 32);
        body.squash(1.25f, 0.8f, 1.25f, 2);
        Vector c = pos.clone();
        for (Player q : game.mobs.standing()) {
            Vector qc = chest(q);
            if (qc.distanceSquared(c) > BURST_R * BURST_R) continue;
            if (!game.mobs.clearLine(c, qc)) continue;
            hit(q, BURST, "cause_g96_scale");
            knock(q, toward(q), 0.45, 0.2);
        }
        // 날아가는 비늘(부품, 세계 축)
        clearFlying();
        for (int i = 0; i < 8; i++) {
            Body.Part part = body.attach("gabe/g96_scale");
            if (part == null) break;
            part.follow = false;
            double a = Math.PI * 2 * i / 8 + game.random.nextDouble() * 0.3;
            Vector dir = new Vector(Math.cos(a), 0.15 + game.random.nextDouble() * 0.2, Math.sin(a)).normalize();
            flying.add(part);
            flyDir.add(dir);
            poseScale(part, dir, 0.4, 0);
        }
        flyAt = age;
        // 비늘이 빠져 한 단계 줄어듦
        stage = 4;
        eaten = STAGE_EAT * maxHp * 4;
        body.look.on.remove("s4");
        body.look.on.add("s3");
        size();
        body.scale(s(), 8);
    }

    private void poseScale(Body.Part part, Vector dir, double r, int ticks) {
        float ty = (float) Math.atan2(-dir.getX(), dir.getZ());
        float tp = (float) -Math.atan2(dir.getY(), Math.hypot(dir.getX(), dir.getZ()));
        part.pose(new Matrix4f().translate((float) (dir.getX() * r), (float) (dir.getY() * r), (float) (dir.getZ() * r)).rotateY(-ty).rotateX(tp)
                .scale(1.6f), ticks);
    }

    private void flyTick() {
        if (flyAt < 0) return;
        int t = age - flyAt;
        if (t > 9) {
            clearFlying();
            return;
        }
        for (int i = 0; i < flying.size(); i++) poseScale(flying.get(i), flyDir.get(i), 0.4 + t * 1.1, 1);
    }

    private void clearFlying() {
        for (Body.Part p : flying) p.remove();
        flying.clear();
        flyDir.clear();
        flyAt = -1;
    }

    private void calm() {
        mark(null);
        prey = null;
        swingReady = age + cd(SWING_CD);
        if (phase == Phase.BALL) state("");
        pitch = 0;
        phase(Phase.CRAWL);
        size();
    }

    // ------------------------------------------------------------------ 먹고 자람

    private void eat(double amount, Damage d) {
        eaten += amount;
        recent += amount;
        if (age - munchAt >= 8) {
            munchAt = age;
            sound("munch", Sound.ENTITY_GENERIC_EAT, 0.8f, 24);
        }
        // 맞은 쪽으로 비늘 등을 기울여 받음
        if (d.at() != null && phase == Phase.CRAWL) {
            Vector f = fwd();
            double lx = f.getZ(), lz = -f.getX();
            rollSide = (float) Math.signum((d.at().getX() - pos.getX()) * lx + (d.at().getZ() - pos.getZ()) * lz);
            body.roll = rollSide * 22f;
            rollUntil = age + 8;
        }
        int want = Math.min(5, (int) (eaten / (STAGE_EAT * maxHp)));
        while (stage < want) grow();
    }

    private void grow() {
        stage++;
        if (stage <= 4) body.look.on.add("s" + stage);
        sound("bristle", Sound.BLOCK_CHAIN_PLACE, 0.75f, 24);
        size();
        body.squash(1.1f, 1.15f, 1.0f, 4);
        body.scale(s(), 10);
        if (stage >= 5) burstReady = Math.max(burstReady, age + 20);
    }

    public int stage() {
        return stage;
    }

    @Override
    public List<double[]> spheres() {
        double s = s();
        if (phase == Phase.BALL) {
            List<double[]> out = new ArrayList<>(1);
            out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.82 * s});
            return out;
        }
        Vector f = fwd();
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX() + f.getX() * 0.75 * s, pos.getY() - 0.05 * s, pos.getZ() + f.getZ() * 0.75 * s, 0.62 * s});
        out.add(new double[] {pos.getX() - f.getX() * 0.35 * s, pos.getY() + 0.1 * s, pos.getZ() - f.getZ() * 0.35 * s, 0.8 * s});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET;
        if (!gun || d.amount() >= BIG_SHOT * maxHp || age < bellyUntil) return super.damage(d);
        // 비늘이 받아 먹음: 30% 만 들고 나머지는 자람
        double take = d.amount() * GUN_TAKE;
        eat(d.amount() - take, d);
        hp -= take;
        game.mobs.onHurt(this, d, take + Math.min(0, hp));
        if (hp <= 0) die(d);
        return true;
    }

    @Override
    public void die(Damage d) {
        clearFlying();
        if (prey != null) mark(null);
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " st=" + stage + " eaten=" + Math.round(eaten) + " recent=" + Math.round(recent);
    }
}
