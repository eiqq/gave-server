package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G81 눈싸움 게이브(방해, 정예, 시선 규칙). 작은 얼굴 수십이 뭉친 3.6블록 눈알 공이 땅에서 한 뼘 떠 밀려온다(0.12). 20블록 안에서 멈춰 한 사람(지금 가장
 * 많이 쏘는 사람, 없으면 가장 가까운 사람)을 정하고 눈동자를 그 사람에게 돌린다(속눈썹이 쫙 섬, '노려보는 소리', 조준점 옆 눈 표시, 3초 게이지).
 * 3초 동안 1초(20틱) 넘게 조준선이 공에서 벗어나면 34 피해와 5초 겁먹음(느려짐 30%, 손떨림으로 탄 퍼짐). 끝까지 마주 보면 공이 눈을 감고 8초 굳어
 * 받는 피해가 두 배. 어느 쪽이든 6초 뒤 다른 사람. 마주 보는 동안 눈동자 속 작은 얼굴들이 차례로 입을 벌린다. 몸에 닿으면 깔아 20.
 *
 * <p>조준은 엔진 looked(블록 가림, 앞 게이브가 막음) 대신 공이 보이는 각(공 반지름 / 거리 + 3도)으로 잰다: 떼가 사이를 지나가도 공을 보고 있으면 본 것.
 *
 * <p>팩 계약(tools/gabe/sp/g81.py): 모델 gave:gabe/g81(shrink 0.5) 상태 "", "glare", "glare2", "glare3", "blink". 소리 glare(32),
 * 원인 글귀 cause_g81, cause_g81_crush. HUD 게이지와 표시는 종 키 "g81"(아이콘).
 */
public final class G81 extends Gabe {

    static final double DRIFT_V = 0.12, PICK_R = 20, BALL_R = 1.8, HOVER = 2.0, R = 1.7;
    static final double LOSE_DMG = 34, CRUSH = 20, FEAR_SLOW = 0.3, MARGIN_DEG = 3;
    static final int STARE = 60, AWAY_MAX = 20, FEAR = 100, BLINK = 160, AGAIN = 120, CRUSH_CD = 30, SHOTS_EVERY = 20;
    static final int TREMOR_EVERY = 3, FEAR_SLOW_TICKS = 12;

    enum Phase { DRIFT, STARE, BLINK, REST }

    private Phase phase = Phase.DRIFT;
    private int phaseAt, away, crushAt;
    private Player prey, last, feared;
    private int fearUntil = -1;
    /** 사람별 쏜 수(20틱마다 적음)와 그 사이 는 수. */
    private final Map<UUID, Integer> shotsSeen = new HashMap<>(), shotsDelta = new HashMap<>();

    public G81(Game game, Species sp) {
        super(game, sp);
        radius = R;
        hover = HOVER;
        baseSpeed = DRIFT_V;
        ragKick = 0.4;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g81", 4.0f, 4.0f);
    }

    @Override
    public void tick() {
        if (every(SHOTS_EVERY)) noteShots();
        fear();
        crush();
        switch (phase) {
            case DRIFT -> drift();
            case STARE -> stare();
            case BLINK -> {
                hold();
                if (in() >= cd(BLINK)) {
                    state("");
                    go(Phase.REST);
                }
            }
            case REST -> {
                follow(speed());
                Player p = nearest(400);
                face(p != null ? chest(p) : pos.clone().add(vel));
                if (in() >= cd(AGAIN)) go(Phase.DRIFT);
            }
        }
    }

    private void go(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int in() {
        return age - phaseAt;
    }

    private void noteShots() {
        shotsDelta.clear();
        for (Player p : game.mobs.standing()) {
            int n = game.records.shots(p.getUniqueId());
            Integer was = shotsSeen.put(p.getUniqueId(), n);
            if (was != null && n > was) shotsDelta.put(p.getUniqueId(), n - was);
        }
    }

    private void drift() {
        Player p = nearest(400);
        if (p != null && dist(p) > PICK_R) {
            follow(speed());
            face(chest(p));
            return;
        }
        Player pick = pick();
        if (pick == null) {
            follow(speed());
            if (p != null) face(chest(p));
            return;
        }
        prey = pick;
        away = 0;
        mark(pick);
        state("glare");
        sound("glare", Sound.BLOCK_BEACON_AMBIENT, 0.5f, 32);
        body.squash(1.08f, 1.08f, 1.08f, 3);
        go(Phase.STARE);
    }

    /** 20블록 안, 가리지 않은 서 있는 사람 가운데 최근 가장 많이 쏜 사람(지난번 사람은 뒤로, 같으면 가까운 쪽). */
    private Player pick() {
        Player best = null;
        double bs = -Double.MAX_VALUE;
        for (Player q : game.mobs.standing()) {
            double d = dist(q);
            if (d > PICK_R || !sees(q)) continue;
            double s = shotsDelta.getOrDefault(q.getUniqueId(), 0) * 10 - d - (q == last ? 50 : 0);
            if (s > bs) {
                bs = s;
                best = q;
            }
        }
        return best;
    }

    private void stare() {
        hold();
        Player p = prey;
        if (!standing(p)) {
            endStare(false);
            return;
        }
        face(p.getEyeLocation().toVector());
        int t = in();
        state(t < STARE / 3 ? "glare" : t < 2 * STARE / 3 ? "glare2" : "glare3");
        if (!looking(p)) away++;
        game.hud.meter(p, "g81", Math.min(1.0, t / (double) STARE), 10);
        game.hud.mark(p, "g81", 10);
        if (away > AWAY_MAX) {
            // 눈을 피했다: 진다
            hit(p, LOSE_DMG, "cause_g81");
            if (standing(p)) {
                feared = p;
                fearUntil = age + FEAR;
            }
            endStare(false);
        } else if (t >= STARE) {
            endStare(true);
        }
    }

    /** 조준선이 공에 닿아 있나(공이 보이는 각 + 3도). */
    private boolean looking(Player p) {
        Location eye = p.getEyeLocation();
        Vector to = pos.clone().subtract(eye.toVector());
        double d = to.length();
        if (d < BALL_R) return true;
        double cos = eye.getDirection().dot(to) / d;
        double lim = Math.asin(Math.min(1.0, BALL_R / d)) + Math.toRadians(MARGIN_DEG);
        return cos >= Math.cos(lim);
    }

    private void endStare(boolean won) {
        if (prey != null) {
            game.hud.meter(prey, "g81", -1, 0);
            last = prey;
        }
        prey = null;
        mark(null);
        if (won) {
            // 마주 보고 버텼다: 눈을 감고 8초 굳음(받는 피해 두 배)
            state("blink");
            body.squash(1.12f, 0.9f, 1.12f, 4);
            go(Phase.BLINK);
        } else {
            state("");
            go(Phase.REST);
        }
    }

    /** 겁먹음: 짧은 느려짐을 이어 걸고 손을 떨게 함. 이 개체가 죽거나 끝나면 0.6초 안에 풀림. */
    private void fear() {
        if (feared == null) return;
        if (age >= fearUntil || !standing(feared)) {
            feared = null;
            return;
        }
        if ((age - fearUntil) % 10 == 0) game.players.slow(feared, FEAR_SLOW, FEAR_SLOW_TICKS, "g81_fear");
        if (age % TREMOR_EVERY == 0) {
            Location l = feared.getLocation();
            Guns.kick(feared, l.getYaw() + (float) (game.random.nextGaussian() * 1.6), l.getPitch() + (float) (game.random.nextGaussian() * 1.2));
        }
    }

    /** 몸에 닿으면 깔아뭉갬. */
    private void crush() {
        if (age - crushAt < cd(CRUSH_CD)) return;
        for (Player q : game.mobs.standing()) {
            if (touches(q, 0.05)) {
                hit(q, CRUSH, "cause_g81_crush");
                knock(q, toward(q), 0.5, 0.15);
                crushAt = age;
                return;
            }
        }
    }

    @Override
    public boolean damage(Damage d) {
        return super.damage(phase == Phase.BLINK ? d.times(2) : d);
    }

    @Override
    public void die(Damage d) {
        if (prey != null) game.hud.meter(prey, "g81", -1, 0);
        prey = null;
        feared = null;
        super.die(d);
    }

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), R});
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + (phase == Phase.STARE ? " away=" + away : "");
    }
}
