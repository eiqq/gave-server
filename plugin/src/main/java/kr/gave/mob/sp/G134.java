package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G134 북 게이브(4막, 방해, 지원). 북통을 옆으로 뉜 얼굴이 떼 뒤쪽에서 느리게(0.12/틱) 걸어와 제 볼을 북채로 친다. 칠 때마다 24블록 안 서 있는 사람의
 * 예비 탄약이 3%(12블록 안이면 6%) 빠져나가고(AMMO: Guns.takeAmmo), 예비 탄약이 없는 사람(권총만 든 사람 포함)은 대신 8 피해. 사람과 가까울수록
 * 박자가 빨라지고 10블록 안에 들면 멈춰 서서 연타: 칠 때마다 충격파(바닥 고리)가 10블록 안 사람에게 12 피해와 1블록 밀침.
 *
 * <p>대처: 북채 하나를 쏴 부러뜨리면 박자가 절반(둘 다 부러지면 북을 못 치고 몸으로 들이받으러 옴). 북면(앞뒤 얼굴)을 맞히면 3초 동안 소리가
 * 먹먹해져 탄약이 안 빠진다(충격파는 그대로).
 *
 * <p>팩 계약(tools/gabe/sp/g134.py): 상태 "", "roll", 부위 stick_l, stick_r(부러짐). 소리 drum(48), bells(32), muffle(24). 글귀 cause_g134,
 * cause_g134_dry.
 */
public final class G134 extends Gabe {

    static final double SPEED = 0.12, HOVER = 1.0, RADIUS = 0.62;
    static final double FAR = 24, NEAR = 12, STOP_AT = 10;
    static final double DRAIN = 0.03, DRAIN_NEAR = 0.06, DRY = 8, WAVE = 12, WAVE_PUSH = 0.42, BUMP = 10;
    /** 박자 틱: 24블록 밖, 10블록 앞, 연타. 박자 배율(cd) 곱함. 북채 하나 부러지면 x2. */
    static final int BEAT_FAR = 40, BEAT_NEAR = 18, BEAT_ROLL = 24, MUTE = 60, BUMP_CD = 30;
    /** 맞는 구(몸 기준): 북면 앞뒤 거리, 반지름, 북채 끝 자리. */
    static final double FACE_Z = 0.66, FACE_R = 0.5, BODY_R = 0.62, STICK_X = 0.55, STICK_Y = 1.15, STICK_Z = 0.35, STICK_R = 0.22;

    private boolean brokenL, brokenR, rolling;
    private int beatAt = 30, muteUntil = -1, bumpAt, unsquashAt = -1, beats;

    public G134(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g134", 2.0f, 2.8f);
    }

    @Override
    protected void spawned() {
        G150.arm(game);
        beatAt = 20 + game.random.nextInt(30);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (age == unsquashAt) body.squash(1f, 1f, 1f, 3);
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            roll(false);
            return;
        }
        double d = dist(p);
        boolean silentDrum = brokenL && brokenR;
        if (silentDrum) {
            // 북채가 없으면 몸으로 들이받으러 옴
            roll(false);
            follow(speed() * 1.3);
            face(chest(p));
            if (age >= bumpAt && touches(p, 0.2)) {
                hit(p, BUMP, "cause_g134");
                knock(p, toward(p), 0.4, 0.15);
                bumpAt = age + cd(BUMP_CD);
            }
            return;
        }
        if (d > STOP_AT) {
            follow(speed());
            roll(false);
        } else {
            hold();
            roll(true);
        }
        face(chest(p));
        if (age >= beatAt) beat(d);
    }

    private void roll(boolean on) {
        if (on == rolling) return;
        rolling = on;
        state(on ? "roll" : "");
    }

    /** 한 박자: 북소리, 탄약 빼기(먹먹하지 않을 때), 연타면 충격파. */
    private void beat(double nearestDist) {
        int base;
        if (rolling) base = BEAT_ROLL;
        else {
            double f = Math.max(0, Math.min(1, (nearestDist - STOP_AT) / (FAR - STOP_AT)));
            base = (int) Math.round(BEAT_NEAR + (BEAT_FAR - BEAT_NEAR) * f);
        }
        if (brokenL || brokenR) base *= 2;
        beatAt = age + cd(base);
        boolean muted = age < muteUntil;
        if (muted) sound("muffle", Sound.BLOCK_WOOL_PLACE, 0.6f, 24);
        else {
            sound("drum", Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 48);
            if (game.random.nextInt(3) == 0) sound("bells", Sound.BLOCK_NOTE_BLOCK_BELL, 1.8f, 32);
        }
        body.squash(1.07f, 0.93f, 1.07f, 1);
        unsquashAt = age + 2;
        beats++;
        for (Player q : game.mobs.standing()) {
            double dq = dist(q);
            if (dq > FAR) continue;
            if (!muted) {
                int took = game.guns.takeAmmo(q, dq <= NEAR ? DRAIN_NEAR : DRAIN, false);
                if (took > 0) Guns.kick(q, (game.random.nextFloat() - 0.5f) * 0.6f, -0.35f);
                else hit(q, DRY, "cause_g134_dry");
                if (beats <= 4 || beats % 10 == 0) log("박자 " + beats + (rolling ? " 연타" : "") + " " + q.getName() + String.format(" d=%.1f", dq) + " 뺀탄 " + took);
            } else if (beats <= 8) log("박자 " + beats + " 먹먹함 " + q.getName());
            if (rolling && dq <= STOP_AT + 0.5) {
                hit(q, WAVE, "cause_g134");
                knock(q, toward(q), WAVE_PUSH, 0.12);
            }
        }
        if (rolling) shockwave();
    }

    /** 충격파 고리(바닥 fx_flat 고리가 10블록까지 퍼짐). */
    private void shockwave() {
        try {
            Location g = new Location(game.world, pos.getX(), floor() + 0.12, pos.getZ());
            ItemDisplay ring = game.shotFx.spawn(g, "fx_flat", ShotFx.RING, 0, ShotFx.scale(1.5f), Display.Billboard.FIXED, true, 10, ShotFx.NORMAL);
            game.shotFx.grow(ring, ShotFx.scale((float) (STOP_AT * 2.5)), 7);
        } catch (RuntimeException ignored) {
            // 연출은 덤
        }
    }

    // ------------------------------------------------------------------ 맞음

    private double[] at(double x, double y, double z, double r) {
        double rr = Math.toRadians(yaw), fx = -Math.sin(rr), fz = Math.cos(rr);
        // 몸 왼쪽 = (fz, 0, -fx)
        return new double[] {pos.getX() + fx * z + fz * x, pos.getY() + y, pos.getZ() + fz * z - fx * x, r};
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(5);
        out.add(at(0, 0, 0, BODY_R));
        out.add(at(0, 0, FACE_Z, FACE_R));
        out.add(at(0, 0, -FACE_Z, FACE_R));
        if (!brokenL) out.add(at(STICK_X, STICK_Y, STICK_Z, STICK_R));
        if (!brokenR) out.add(at(-STICK_X, STICK_Y, STICK_Z, STICK_R));
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        Vector p = d.at();
        if (p != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            double[][] s = {at(0, 0, 0, BODY_R), at(0, 0, FACE_Z, FACE_R), at(0, 0, -FACE_Z, FACE_R), at(STICK_X, STICK_Y, STICK_Z, STICK_R),
                at(-STICK_X, STICK_Y, STICK_Z, STICK_R)};
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < s.length; i++) {
                if (i == 3 && brokenL || i == 4 && brokenR) continue;
                double dd = Math.sqrt(sq(p.getX() - s[i][0]) + sq(p.getY() - s[i][1]) + sq(p.getZ() - s[i][2])) - s[i][3];
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best == 3 || best == 4) breakStick(best == 3);
            else if (best == 1 || best == 2) muteUntil = age + MUTE;
        }
        return super.damage(d);
    }

    private static double sq(double v) {
        return v * v;
    }

    private void breakStick(boolean left) {
        if (left) brokenL = true;
        else brokenR = true;
        body.look.on.add(left ? "stick_l" : "stick_r");
        log("북채 부러짐 " + (left ? "왼" : "오른"));
        sound("muffle", Sound.BLOCK_WOOD_BREAK, 1.2f, 24);
    }

    /** 시험과 판 뒤 분석용 기록(드문 사건만). */
    void log(String s) {
        game.plugin.getLogger().info("[G134] " + s);
    }

    @Override
    public String toString() {
        return super.toString() + (rolling ? " ROLL" : "") + (brokenL ? " stickL" : "") + (brokenR ? " stickR" : "") + (age < muteUntil ? " muted" : "");
    }
}
