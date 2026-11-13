package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;

/**
 * G115 진드기 덮인 거인 게이브(3막, 튼튼이, 지원). 키 7.4블록 분홍 살 사람꼴 거인(다리 둘은 부품), 온몸에 얼굴 진드기 스물넷이 붙어 꿈틀댄다.
 * 느린 걸음으로 사람에게 곧장 와서 3블록 안 사람을 양팔로 내려친다(반경 3, 22 와 넘어짐 1초, 0.5초 예고). 탄에 맞을 때마다 진드기 둘이 떨어져
 * ({@link G115Tick}, 체력 3, 보상 0) 사람에게 기어 가 문다(3, 붙으면 2초 15% 느려짐, 셋까지 겹침). 불 피해는 진드기를 떨어뜨리지 않고 태운다.
 * 진드기가 다 떨어진 거인은 매끈한 살만 남는다(부위 t1..t4).
 *
 * <p>팩 계약(tools/gabe/sp/g115.py): gabe/g115(shrink 0.32, 상태 "", "slam"(20틱 자세 4, 자세 2 에 닿음), 부위 t1..t4), 다리 gabe/g115_leg,
 * 진드기 gabe/g115_tick. 소리 shed, step, slam, bite. 글귀 cause_g115, cause_g115_tick.
 *
 * <p>도감과 다른 점: 팔은 부품이 아니라 몸 모델의 자세(걷기 흔듦, 내려치기)라 디스플레이가 셋(몸, 다리 둘)이다. 탄 한 발(산탄 알 하나)마다 둘씩.
 */
public final class G115 extends Gabe {

    static final double HOVER = 4.0, HIP_X = 0.72, HIP_Y = -1.25, LEG_L = 2.8, HEAD_Y = 2.25;
    static final double SPEED = 0.08, SLAM_AT = 2.0, SLAM_R = 3.0, SLAM_DMG = 22, START = 4.4;
    static final int SLAM_CD = 50, SLAM_HIT = 10, SLAM_LEN = 20, DOWN_T = 20, TICKS = 24, PER_HIT = 2;

    private boolean slamming;
    private int slamAt, slamReady, onBody = TICKS, dropped, burned, slams, shedAt = -100;
    private G129Kit.Legs legs;
    private final Vector slamC = new Vector();
    private final List<double[]> sph = new ArrayList<>(6);
    private int sphAge = -1;

    public G115(Game game, Species sp) {
        super(game, sp);
        radius = 1.5;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ragKick = 0.5;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g115", 6f, 9f);
        body.viewRange(2.5f);
        legs = new G129Kit.Legs(this, "gabe/g115_leg", LEG_L, new double[] {HIP_X, -HIP_X}, HIP_Y, 0);
        legs.reach = 0.9;
        legs.lead = 0.85;
        legs.lift = 0.55;
        legs.stance = 0.12;
        legs.swing = 12;
        legs.land = this::landed;
        G129Kit.bright(body);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        Player p = nearest(400);
        mark(p != null && dist(p) < 32 ? p : null);
        if (slamming) slam();
        else {
            if (p == null) follow(speed());
            else {
                double h = G129Kit.flat(p.getLocation(), pos);
                if (h > START - 1.2) follow(speed());
                else hold();
                G129Kit.faceFlat(this, p.getLocation().toVector());
                if (h <= START && age >= slamReady) startSlam();
            }
        }
        legs.tick();
    }

    private void startSlam() {
        hold();
        slamming = true;
        slamAt = age;
        G129Kit.gesture(this, "slam", SLAM_LEN, 4);
        slamC.copy(G129Kit.ground(this, pos.clone().add(G129Kit.fwd(this).multiply(SLAM_AT))));
        G129Kit.warn(this, slamC, SLAM_R, SLAM_HIT + 2);
        slams++;
    }

    private void slam() {
        hold();
        int t = age - slamAt;
        if (t == SLAM_HIT) {
            sound("slam", Sound.ENTITY_IRON_GOLEM_ATTACK, 0.5f, 48);
            G129Kit.stomp(this, slamC, 48, 8, 1f);
            G129Kit.ring(this, slamC, SLAM_R, 10);
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                if (G129Kit.flat(l, slamC) > SLAM_R || Math.abs(l.getY() - slamC.getY()) > 3) continue;
                hit(q, SLAM_DMG, "cause_g115");
                game.players.root(q, DOWN_T, "g115");
            }
        }
        if (t >= SLAM_LEN) {
            slamming = false;
            state("");
            slamReady = age + cd(SLAM_CD);
        }
    }

    private void landed(int i, Vector foot) {
        G129Kit.stomp(this, foot, 48, 0, 0.6f);
        if ((i & 1) == 0) G129Kit.soundAt(this, foot, "step", Sound.ENTITY_RAVAGER_STEP, 0.7f, 48);
    }

    // ------------------------------------------------------------------ 진드기

    /** 몸의 진드기 무리 표시(여섯 마리마다 한 무리씩 사라짐). */
    private void showTicks() {
        int gone = Math.min(4, (TICKS - onBody + 5) / 6);
        for (int g = 1; g <= 4; g++) {
            if (g <= gone) body.look.on.add("t" + g);
            else body.look.on.remove("t" + g);
        }
    }

    /** 맞은 자리 둘레에서 진드기 n 마리를 떨굼(몸 예산 안에서). */
    private void shed(Vector at, int n) {
        int done = 0;
        for (int i = 0; i < n && onBody > 0; i++) {
            if (game.mobs.budgetUsed() + 1 > Mobs.BUDGET) break;
            Vector from = at == null ? G129Kit.at(this, (game.random.nextDouble() - 0.5) * 2, 0, 0.8) : at.clone();
            from.add(new Vector((game.random.nextDouble() - 0.5) * 0.8, 0, (game.random.nextDouble() - 0.5) * 0.8));
            G115Tick t = new G115Tick(game, sp);
            t.maxHp = t.hp = G115Tick.HP;
            t.noReward = true;
            t.reward = 0;
            t.yaw = game.random.nextFloat() * 360f;
            if (game.mobs.place(t, from) != null) {
                onBody--;
                dropped++;
                done++;
            }
        }
        if (done > 0) {
            showTicks();
            if (age - shedAt >= 6) {
                sound("shed", Sound.ENTITY_SILVERFISH_AMBIENT, 1.2f, 24);
                shedAt = age;
            }
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(G129Kit.at(this, 0, HEAD_Y, 0.05), 1.35);
        add(G129Kit.at(this, 0, 0.1, 0), 1.4);
        add(G129Kit.at(this, 0, -1.1, 0), 1.2);
        if (legs != null) {
            add(legs.along(0, 0.55), 0.55);
            add(legs.along(1, 0.55), 0.55);
        }
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.type() == DamageType.FIRE) {
            // 불은 붙은 진드기를 떨어지기 전에 태움
            if (onBody > 0 && game.random.nextInt(2) == 0) {
                onBody--;
                burned++;
                showTicks();
            }
        } else if (d.type() != DamageType.BITE && d.type() != DamageType.OTHER) {
            shed(d.at(), PER_HIT);
        }
        return super.damage(d);
    }

    @Override
    public String toString() {
        return super.toString() + " onBody=" + onBody + " dropped=" + dropped + " burned=" + burned + " slams=" + slams + " steps="
            + (legs == null ? 0 : legs.steps);
    }
}
