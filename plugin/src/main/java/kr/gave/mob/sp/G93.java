package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;

/**
 * G93 피리 부는 게이브(방해, 지원, 2막). 키 3.5블록 막대기 사람이 피리를 물고 걸어오고, 뒤로 0.5블록 얼굴 여섯({@link G93Kid})이 발맞춰 한 줄로
 * 따른다. 12초마다 5초 동안 피리를 불어 14블록 안 사람을 피리 쪽으로 끌어당기고(초당 1.5블록쯤, 반대로 걸으면 절반), 끌려온 사람에게 따르는 얼굴들이
 * 차례차례 달려든다.
 *
 * <p>도감: 따르는 것 물기 5(마리마다), 피리 부는 것 막대기 휘두르기 10. 피리 부는 것(체력 70)을 먼저 잡으면 끌림이 멈추고, 따르는 것들은 박자를
 * 잃어 2초 멈칫한 뒤 낱개로 덤빈다. 보상은 피리 부는 것 12원, 따르는 것 마리당 1원.
 *
 * <p>바꾼 것: 따르는 것은 피리 부는 것의 부품이 아니라 따로 사는 게이브(같은 종 G93, 클래스 G93Kid, 체력 15 x 감독 체력 배율)다: 피리 부는 것이
 * 죽어도 남아 덤벼야 해서. 첫 틱에 몸 예산이 되는 만큼(여섯까지) 낸다. 끌기는 5틱마다 사람 속도에 피리 쪽 0.16(반대로 걸으면 0.08).
 *
 * <p>팩 계약(tools/gabe/sp/g93.py): gave:gabe/g93(상태 "walk", "play"), gave:gabe/g93_kid(상태 "", "bite"). 소리 pipe(32), swing(16), bite(16).
 * 글귀 cause_g93, cause_g93_swing.
 */
public final class G93 extends Gabe {

    static final double SPEED = 0.13, HOVER = 1.75;
    static final int KIDS = 6, TRAIL = 64, KID_LAG = 6;
    static final double KID_HP = 15;
    /** 피리: 간격, 길이, 끌기 간격, 소리 간격. */
    static final int PLAY_EVERY = 240, PLAY_T = 100, PULL_EVERY = 5, PIPE_EVERY = 70;
    static final double PULL_R = 14, PULL_V = 0.16, START_R = 18;
    /** 따르는 것 덮치기: 피리 부는 것에서 이 블록 안 사람에게 차례로, 간격. */
    static final double KID_R = 7;
    static final int KID_EVERY = 10, KID_EVERY_WALK = 24;
    static final double SWING = 10, SWING_R = 2.4;
    static final int SWING_CD = 30;

    private final double[] tx = new double[TRAIL], tz = new double[TRAIL];
    private int trailN, trailAt;
    private final List<G93Kid> kids = new ArrayList<>(KIDS);
    private final Map<UUID, double[]> lastAt = new HashMap<>();
    private boolean kidsMade, playing;
    private int playAt, playStart, swingAt = -100, kidAt, nextPipe;

    public G93(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        hover = HOVER;
        baseSpeed = SPEED;
        state("walk");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g93", 2f, 3.8f);
    }

    @Override
    protected void spawned() {
        playAt = age + 60 + game.random.nextInt(60);
        for (int i = 0; i < TRAIL; i++) {
            tx[i] = pos.getX();
            tz[i] = pos.getZ();
        }
        trailN = 1;
    }

    // ------------------------------------------------------------------ 따르는 것

    /** 첫 틱(감독이 체력 배율을 곱한 뒤): 따르는 얼굴 여섯을 뒤에 한 줄로 낸다(몸 예산 안에서). */
    private void makeKids() {
        kidsMade = true;
        double ratio = Math.max(0.5, maxHp / Math.max(1, sp.hp));
        int reward = (int) Math.max(1, Math.round(game.director.rewardMul()));
        Vector back = forward().multiply(-1);
        for (int i = 0; i < KIDS; i++) {
            if (game.mobs.budgetUsed() + 1 > Mobs.BUDGET) break;
            Vector at = pos.clone().add(back.clone().multiply(1.2 + i * 0.9));
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + G93Kid.HOVER);
            G93Kid k = new G93Kid(game, sp, this, i);
            k.maxHp = k.hp = KID_HP * ratio;
            k.reward = noReward ? 0 : reward;
            k.noReward = noReward;
            k.yaw = yaw;
            if (game.mobs.place(k, at) != null) kids.add(k);
        }
    }

    /** 따르는 것 i 가 설 자리: 피리 부는 것이 지나온 길의 (i + 1) x 6틱 전 자리. */
    Vector kidPoint(int i) {
        int lag = Math.min(trailN - 1, (i + 1) * KID_LAG);
        int at = Math.floorMod(trailAt - 1 - lag, TRAIL);
        return new Vector(tx[at], 0, tz[at]);
    }

    /** 박자(0..1): 따르는 것들이 고개를 까딱이는 때. */
    boolean beat() {
        return playing ? age % 8 == 0 : age % 14 == 0;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!kidsMade) makeKids();
        tx[trailAt] = pos.getX();
        tz[trailAt] = pos.getZ();
        trailAt = (trailAt + 1) % TRAIL;
        if (trailN < TRAIL) trailN++;
        kids.removeIf(k -> k.dead || k.removed);
        Player p = nearest(400);
        if (playing) play(p);
        else walk(p);
        swing();
    }

    private void walk(Player p) {
        state("walk");
        if (p == null) {
            follow(speed());
            return;
        }
        double d = dist(p);
        if (d > 9) follow(speed());
        else {
            hold();
            face(chest(p));
        }
        if (age >= playAt && d <= START_R) {
            playing = true;
            playStart = age;
            nextPipe = age;
            lastAt.clear();
            hold();
            return;
        }
        if (age - kidAt >= cd(KID_EVERY_WALK) && d <= KID_R) sendKid(p);
    }

    /** 피리를 붊: 제자리에서 14블록 안 사람을 끌어당기고 끌려온 사람에게 따르는 것들을 차례로 보냄. */
    private void play(Player p) {
        state("play");
        hold();
        if (p != null) face(chest(p));
        if (age >= nextPipe) {
            sound("pipe", Sound.BLOCK_NOTE_BLOCK_FLUTE, 1.2f, 32);
            nextPipe = age + PIPE_EVERY;
        }
        int t = age - playStart;
        if (t % PULL_EVERY == 0) pull();
        if (age - kidAt >= cd(KID_EVERY)) {
            Player near = nearest(KID_R);
            if (near != null) sendKid(near);
        }
        if (t >= PLAY_T) {
            playing = false;
            playAt = age + cd(PLAY_EVERY - PLAY_T);
            lastAt.clear();
        }
    }

    private void pull() {
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double dx = pos.getX() - l.getX(), dz = pos.getZ() - l.getZ(), d = Math.hypot(dx, dz);
            if (d > PULL_R || d < 1.5 || game.players.grabbedBy(q) != null) continue;
            Vector to = new Vector(dx / d, 0, dz / d);
            double[] last = lastAt.get(q.getUniqueId());
            double v = PULL_V;
            if (last != null) {
                double mx = l.getX() - last[0], mz = l.getZ() - last[1];
                // 반대로 걷는 사람은 절반으로 버팀
                if (mx * to.getX() + mz * to.getZ() < -0.05) v *= 0.5;
            }
            lastAt.put(q.getUniqueId(), new double[] {l.getX(), l.getZ()});
            knock(q, to, v, 0);
        }
    }

    /** 놀고 있는 따르는 것 하나(앞에서부터)를 그 사람에게 보냄. */
    private void sendKid(Player p) {
        for (G93Kid k : kids) {
            if (k.dead || !k.idle()) continue;
            k.attack(p);
            kidAt = age;
            return;
        }
    }

    /** 가까이 온 사람은 막대기 팔로 휘두름. */
    private void swing() {
        if (age - swingAt < cd(SWING_CD)) return;
        for (Player q : game.mobs.standing()) {
            if (dist(q) > SWING_R + 0.9 || Math.abs(q.getLocation().getY() + 1 - (pos.getY() - 0.4)) > 2.5) continue;
            Location l = q.getLocation();
            if (Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) > SWING_R) continue;
            hit(q, SWING, "cause_g93_swing");
            knock(q, toward(q), 0.4, 0.15);
            sound("swing", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 16);
            body.squash(1.1f, 0.94f, 1.1f, 2);
            swingAt = age;
            return;
        }
    }

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), pos.getY() + 1.25, pos.getZ(), 0.5});
        out.add(new double[] {pos.getX(), pos.getY() + 0.2, pos.getZ(), 0.4});
        out.add(new double[] {pos.getX(), pos.getY() - 1.0, pos.getZ(), 0.35});
        return out;
    }

    @Override
    public Vector head() {
        return pos.clone().add(new Vector(0, 1.25, 0));
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 피리가 멈춤: 따르는 것들은 박자를 잃고 2초 멈칫한 뒤 낱개로 덤빈다
        for (G93Kid k : kids) if (!k.dead) k.orphan();
        kids.clear();
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + (playing ? " play" : " walk") + " kids=" + kids.size();
    }
}
