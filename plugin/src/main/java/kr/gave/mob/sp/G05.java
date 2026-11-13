package kr.gave.mob.sp;

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
import kr.gave.player.PState;

/**
 * G05 혹 터지는 게이브(돌격). 가장 빠른 돌격 종: 볼 밑 피부를 작은 얼굴들이 밀어 올린 채 떼 사이를 지그재그로 달려온다('끽끽').
 * 4블록 안에서 사람이 보이면 멈춰 찍고(실눈 뜸) 1.2초 동안 혹이 차오르며(혹 3단, 끽끽이 커지고 빨라짐) 터진다. 쏴 죽여도 그 자리에서 터진다.
 * 터지면 반경 3블록 분홍 즙: 사람은 4 피해와 '녹음' 게이지, 다른 종 게이브도 녹는다(대처: 다른 종 무리 한가운데서 쏘기). 작은 얼굴 조각이
 * 쏟아져 기다가 3초 안에 안 밟히면 작은 웅덩이가 된다({@link G05Shards}). 터진 뒤 얼굴 가죽만 남아 몇 초 더 웃는다(시체 상태 "skin").
 *
 * <p>도감과 다른 점: 멈추는 거리는 5가 아니라 4블록이고 부푸는 동안 조금씩 다가온다(가만히 선 사람이 즙 반경 3 안에 들고 물러나면 피하게).
 * 녹음 게이지는 공용이 없어 G05 안에서 따로 센다({@link G05Melt}). 즙 원판은 공용 분홍 원판이 없어 분홍 피 안개 셋과 붉은 바닥 자국으로 그린다.
 *
 * <p>팩 계약(tools/gabe/sp/g05.py): 모델 {@code gave:gabe/g05} 상태 {@code ""}, {@code "swell"}, {@code "full"}, {@code "skin"}, 덮개
 * {@code g05_juice}. 소리 squeak, burst, stomp. 글귀 cause_g05.
 */
public final class G05 extends Gabe {

    static final double SPEED = 0.22, SWAY = 0.75;
    /** 이 블록 안, 보이면 멈춰 부풂(부푸는 동안 조금씩 다가옴). 부푸는 틱(혹 단계가 바뀌는 틱). */
    static final double SWELL_RANGE = 3, CREEP = 0.1;
    static final int SWELL = 24, STAGE2 = 12;
    /** 즙 반경, 사람 피해, 녹음, 다른 게이브 피해(최소, 최대 체력 비율). */
    static final double JUICE_R = 3.5, JUICE_DMG = 6, JUICE_MELT = 0.45, OTHER_MIN = 15, OTHER_FRAC = 0.35;
    static final double FACE_R = 0.5; // 2026-11-12 혹 자루 아래 몸통까지 맞게(0.42 에서)

    enum Phase { RUSH, SWELL }

    private Phase phase = Phase.RUSH;
    private int phaseAt, nextSqueak;
    private boolean burst;
    private Player prey;

    public G05(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = 0.85;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g05", 2.0f, 1.2f);
    }

    @Override
    protected void spawned() {
        nextSqueak = age + 10 + game.random.nextInt(30);
    }

    @Override
    public void tick() {
        if (phase == Phase.RUSH) rush();
        else swell();
    }

    /** 흐름장을 따라 크게 지그재그. */
    private void rush() {
        follow(speed());
        double sway = Math.sin((age + id * 7) * 0.16) * SWAY * speed();
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-6) vel.add(new Vector(-vel.getZ() / vl, 0, vel.getX() / vl).multiply(sway));
        Player p = nearest(40);
        face(p != null ? chest(p) : pos.clone().add(vel));
        if (age >= nextSqueak) {
            sound("squeak", Sound.BLOCK_HONEY_BLOCK_SLIDE, 1.7f, 20);
            nextSqueak = age + 30 + game.random.nextInt(25);
        }
        if (p != null && dist(p) <= SWELL_RANGE && sees(p)) {
            prey = p;
            mark(p);
            hold();
            state("swell");
            phase = Phase.SWELL;
            phaseAt = age;
            nextSqueak = age;
        }
    }

    /** 멈춰 1.2초 동안 혹이 차오름(끽끽이 빨라지고 몸이 부풂). 끝에 터짐(사람이 물러나도). */
    private void swell() {
        hold();
        int t = age - phaseAt;
        if (prey != null && standing(prey)) {
            face(chest(prey));
            // 부풀며 조금씩 다가옴(가만히 선 사람은 즙 반경 안에 들고, 물러나면 피함)
            if (dist(prey) > 1.6) vel.copy(toward(prey).multiply(CREEP));
        }
        if (t >= cd(STAGE2)) state("full"); // 같은 상태면 아무 일 없음(물어뜯기로 그 틱을 건너뛰어도 마지막 단계는 보임)
        float s = 1f + 0.18f * t / cd(SWELL);
        body.scale(s, 2);
        if (age >= nextSqueak) {
            sound("squeak", Sound.BLOCK_HONEY_BLOCK_SLIDE, 1.9f, 20);
            nextSqueak = age + Math.max(2, 6 - t / 5);
        }
        if (t >= cd(SWELL)) die(new Damage(hp, DamageType.OTHER, null, pos.clone(), null, "g05_burst", 0));
    }

    // ------------------------------------------------------------------ 터짐

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 웨이브 끝 정리, 시험 죽이기(보상 없음, 쏜 사람 없음)는 터지지 않고 그냥 사라짐. 그 밖은(쏴 죽여도) 터짐
        boolean cleanup = noReward && (d == null || d.by() == null) && !"g05_burst".equals(d == null ? null : d.how());
        if (!cleanup && !burst) burst(d == null ? null : d.by());
        super.die(d);
    }

    private void burst(Player by) {
        burst = true;
        Location c = loc();
        sound("burst", Sound.ENTITY_SLIME_SQUISH, 0.6f, 24);
        // 분홍 즙: 피 안개(분홍) 셋 + 바닥 자국
        game.shotFx.bloodHit(c, null, false, ShotFx.BLOOD_PINK);
        for (int i = 0; i < 2; i++) {
            Location o = c.clone().add(game.random.nextGaussian() * 0.9, -0.2 + game.random.nextDouble() * 0.4, game.random.nextGaussian() * 0.9);
            game.shotFx.bloodHit(o, null, false, ShotFx.BLOOD_PINK);
        }
        Location gr = ShotFx.ground(c, 2.5);
        if (gr.distanceSquared(c) < 9) game.shotFx.decal(gr, new Vector(0, 1, 0), ShotFx.DECAL_BLOOD, 3.2f, 160);
        for (Player p : game.mobs.standing()) {
            if (Gabe_dist(p) > JUICE_R) continue;
            hit(p, JUICE_DMG, "cause_g05");
            G05Melt.add(game, this, p, JUICE_MELT);
            drip(p);
        }
        // 즙은 다른 종도 녹인다(같은 종과 조각은 빼고)
        List<Gabe> near = game.mobs.near(pos, JUICE_R + 0.5, g -> g != this && !g.dead && g.sp != sp && !g.untargetable && !g.sp.is(kr.gave.mob.Role.BOSS));
        for (Gabe g : near) {
            double amt = Math.max(OTHER_MIN, g.maxHp * OTHER_FRAC);
            Vector dir = g.pos.clone().subtract(pos);
            if (dir.lengthSquared() > 1e-6) dir.normalize();
            // 엔진을 거쳐 맞힘: 남의 종 코드가 던져도 그 개체만 치워지고 터짐은 끝까지 감
            game.mobs.damage(g, new Damage(amt, DamageType.OTHER, by, g.pos.clone(), dir, "g05_juice", 0));
        }
        // 작은 얼굴 조각(보상 0, 예산 안에서만)
        if (game.mobs.budgetFor(1)) {
            G05Shards s = new G05Shards(game, sp);
            Vector at = pos.clone();
            at.setY(floor() + 0.05);
            game.mobs.place(s, at);
        }
        // 시체는 위를 보고 누운 얼굴 가죽
        state("skin");
        body.scale(1f, 0);
    }

    private double Gabe_dist(Player p) {
        return chest(p).distance(pos);
    }

    /** 화면 가장자리에 분홍 즙(덮개가 이미 걸려 있으면 안 그림, 비옷을 쓰지 않는 그림만). */
    private void drip(Player p) {
        PState s = game.players.get(p);
        if (s.cover != null && s.coverUntil > game.now()) return;
        game.hud.cover(p, "g05_juice", 30);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase;
    }
}
