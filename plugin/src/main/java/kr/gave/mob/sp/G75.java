package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G75 배불뚝이 게이브(1막 함정, 튼튼이). 다리 둘 위에 비치는 배 자루를 이고 뒤뚱뒤뚱 사람 무리 한가운데로 곧장 들어온다. 무리 2블록 안에 닿으면 스스로 배를
 * 쥐어짜 터뜨린다: 반경 3 안 즙 6과 느려짐 30% 2초, 작은 얼굴 10마리(체력 3, 물기 3, 보상 0)가 사방으로 쏟아진다. 걸어오며 닿은 사람은 발로 찬다(5).
 *
 * <p>크고 쉬운 배를 쏘면 그 자리에서 터진다(보상 0). 꼭지 얼굴(체력 25, 감독 체력 배율 비례)을 맞혀야 깨끗이 죽는다(배가 쭈그러들고 새끼 없음, 보상 9).
 * 폭발은 배를 터뜨리고, 불은 배를 태워 터질 때 새끼가 다섯만 나온다.
 *
 * <p>팩 계약(tools/gabe/sp/g75.py): 모델 gabe/g75 상태 "", "squeeze", "shrivel". 새끼 gabe/g75_brat(G41Baby). 소리 slosh, burst, kick,
 * peck(새끼, G41Baby 가 부름). 원인 글귀 cause_g75.
 */
public final class G75 extends Gabe {

    static final double SPEED = 0.13, HOVER = 1.85, BELLY_R = 1.15, HEAD_Y = 1.2, HEAD_R = 0.3;
    /** 무리: 가장 가까운 사람 둘레 이 반경 안의 사람들 가운데로. 터뜨리기 시작 거리(배 표면에서). */
    static final double CROWD_R = 8, SQUEEZE_REACH = 2.0;
    static final int SQUEEZE = 16;
    /** 터짐: 반경, 즙 피해, 느려짐(%, 틱), 새끼 수(불탄 배), 새끼 피해, 체력, 수명. */
    static final double BURST_R = 3, BURST_DMG = 6, BURST_SLOW = 30;
    static final int BURST_SLOW_T = 40, BRATS = 10, BRATS_BURNED = 5, BRAT_LIFE = 220;
    static final double BRAT_DMG = 3, BRAT_HP = 3;
    /** 꼭지 얼굴 체력, 발차기. */
    static final double HEAD_HP = 25, KICK_DMG = 5;
    static final int KICK_CD = 20;

    enum Phase { WALK, SQUEEZE }

    private Phase phase = Phase.WALK;
    private int phaseAt, kickAt, nextSlosh;
    private double headHp = -1;
    private boolean burned, popped;

    public G75(Game game, Species sp) {
        super(game, sp);
        radius = BELLY_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g75", 2.6f, 3.6f);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    @Override
    public void tick() {
        if (headHp < 0) headHp = HEAD_HP * Math.max(1, maxHp / Math.max(1, sp.hp));
        if (phase == Phase.SQUEEZE) {
            squeeze();
            return;
        }
        Player near = nearest(400);
        if (near == null) {
            follow(speed());
            face(pos.clone().add(vel));
        } else {
            Vector c = crowd(near);
            if (dist(near) > 20) follow(speed());
            else seek(c, speed());
            face(c.clone().setY(pos.getY()));
            if (target != near && dist(near) < 14) mark(near);
        }
        if (age >= nextSlosh) {
            nextSlosh = age + 30 + game.random.nextInt(20);
            sound("slosh", Sound.ENTITY_SLIME_SQUISH, 0.5f, 20);
        }
        if (near == null) return;
        // 발차기
        if (age >= kickAt) {
            for (Player p : game.mobs.standing()) {
                if (!touches(p, 0.15)) continue;
                hit(p, KICK_DMG, "cause_g75");
                knock(p, toward(p), 0.35, 0.15);
                sound("kick", Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.9f, 16);
                kickAt = age + cd(KICK_CD);
                break;
            }
        }
        // 무리 2블록 안: 쥐어짬
        for (Player p : game.mobs.standing()) {
            if (touches(p, SQUEEZE_REACH)) {
                hold();
                state("squeeze");
                phase(Phase.SQUEEZE);
                return;
            }
        }
    }

    /** 가장 가까운 사람 둘레 사람들의 가운데(바닥 높이는 몸 높이로). */
    private Vector crowd(Player near) {
        Vector n = near.getLocation().toVector();
        double x = 0, z = 0;
        int k = 0;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.abs(l.getX() - n.getX()) > CROWD_R || Math.abs(l.getZ() - n.getZ()) > CROWD_R) continue;
            x += l.getX();
            z += l.getZ();
            k++;
        }
        return k == 0 ? n : new Vector(x / k, pos.getY(), z / k);
    }

    /** 스스로 배를 쥐어짬(출렁출렁 빨라짐), 끝에 터짐. */
    private void squeeze() {
        hold();
        int t = age - phaseAt;
        float k = (float) Math.sin(t * 0.9) * 0.06f;
        body.squash(1.06f + k, 0.9f - k, 1.06f + k, 1);
        if (t % 4 == 0) sound("slosh", Sound.ENTITY_SLIME_SQUISH, 0.6f, 20);
        if (t >= cd(SQUEEZE)) burst(null);
    }

    /** 배가 터짐: 즙, 새끼, 이 개체는 보상 0 으로 죽음. */
    private void burst(Damage by) {
        if (popped || dead) return;
        popped = true;
        Location at = loc();
        if (!game.director.silent()) sound("burst", Sound.ENTITY_SLIME_DEATH, 0.6f, 24);
        game.shotFx.bloodHit(at, new Vector(0, 1, 0), true, ShotFx.BLOOD_PINK);
        game.shotFx.spawn(new Location(game.world, pos.getX(), floor() + 0.05, pos.getZ()), "fx_flat", ShotFx.RING, 0,
                ShotFx.scale((float) (BURST_R * 2)), org.bukkit.entity.Display.Billboard.FIXED, false, 10);
        for (Player p : game.mobs.standing()) {
            if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(pos) > (BURST_R + BELLY_R) * (BURST_R + BELLY_R)) continue;
            hit(p, BURST_DMG, "cause_g75");
            game.players.slow(p, BURST_SLOW, BURST_SLOW_T, "g75_juice");
        }
        int n = burned ? BRATS_BURNED : BRATS;
        Player host = nearest(400);
        Vector base = new Vector(pos.getX(), floor() + 1.0, pos.getZ());
        for (int i = 0; i < n; i++) {
            if (!game.mobs.budgetFor(1)) break;
            double a = i * (Math.PI * 2 / n) + game.random.nextDouble() * 0.4;
            Vector out = new Vector(Math.cos(a), 0, Math.sin(a));
            G41Baby b = new G41Baby(game, sp, "gabe/g75_brat", "cause_g75", BRAT_DMG, host, BRAT_LIFE, BRAT_HP, false);
            b.scatter(out, 10 + game.random.nextInt(8));
            b.yaw = (float) Math.toDegrees(Math.atan2(-out.getX(), out.getZ()));
            game.mobs.place(b, base.clone().add(out.clone().multiply(0.8)));
        }
        reward = 0;
        leaveCorpse = false;
        hp = 0;
        Damage d = by != null ? by : Damage.of(0, DamageType.OTHER);
        super.die(d);
    }

    // ------------------------------------------------------------------ 맞음

    private Vector headAt() {
        return pos.clone().add(new Vector(0, HEAD_Y, 0));
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY() + HEAD_Y, pos.getZ(), HEAD_R});
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), BELLY_R});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || popped) return false;
        DamageType t = d.type();
        if (t == DamageType.EXPLOSION) {
            game.mobs.onHurt(this, d, Math.min(d.amount(), hp));
            burst(d);
            return true;
        }
        if (t == DamageType.FIRE) burned = true;
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            double dh = d.at().distance(headAt()) - HEAD_R, db = d.at().distance(pos) - BELLY_R;
            if (dh <= db) {
                headHp -= d.amount();
                if (headHp <= 0) {
                    // 꼭지를 맞힘: 깨끗이 죽음(배가 쭈그러듦, 새끼 없음)
                    game.mobs.onHurt(this, d, Math.max(0, hp));
                    hp = 0;
                    die(d);
                    return true;
                }
                return super.damage(d);
            }
            // 배를 맞힘: 그 자리에서 터짐
            game.mobs.onHurt(this, d, Math.min(d.amount(), hp));
            burst(d);
            return true;
        }
        return super.damage(d);
    }

    /** 터지지 않고 죽으면(꼭지, 불, 웨이브 끝 정리) 배가 쭈그러든 꼴로 쓰러짐. */
    @Override
    public void die(Damage d) {
        if (!dead && !popped) state("shrivel");
        super.die(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (burned ? " burned" : "") + " head=" + Math.round(headHp);
    }
}
