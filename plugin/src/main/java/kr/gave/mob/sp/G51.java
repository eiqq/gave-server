package kr.gave.mob.sp;

import java.util.HashMap;
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
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G51 보고 배우는 게이브(지원, 공격 없음).
 *
 * <p>도감: 떼 뒤 25블록 밖에서 다가오지 않고, 그 순간 가장 많이 쏘는 사람을 따라 고개를 돌린다(찍으면 실눈이 크게 뜨임). 맞으면 더 멀리
 * 물러난다(2초에 한 번). 웨이브 끝까지 살아 있으면 그 웨이브에 가장 많이 들어온 피해 종류(산탄, 저격, 폭발, 불)에 모든 게이브가 30% 저항을
 * 얻는다(늘 하나만, {@link G51Lesson}). 배운 횟수만큼 이마에 눈이 열린다(최대 4). 사람에게 잡히면 배운 저항이 지워진다.
 *
 * <p>황야(구조물 없음): 가장 가까운 사람과 25~32블록을 지킨다(안개 70블록 안이라 늘 보임). 이 종은 고리에서 나오는 종이라 살아 있으면
 * 감독이 웨이브를 끝내지 않는다. 그래서 '웨이브 끝까지 살아남음'은 종이 직접 본다: 감독 대기열이 비고 다른 게이브가 다 잡힌 채 3초가 지나면
 * 찰칵 하고 배운 뒤 위로 떠올라 사라진다(보상 없음). 감독의 웨이브 끝 정리, 막힘 정리로 치워질 때도 배운다.
 *
 * <p>팩 계약(tools/gabe/sp/g51.py): 모델 gave:gabe/g51, 상태 "" 지켜봄, "snap" 찰칵. 부위 eye1..eye4(이마 눈). 소리 shutter(64), flinch(32).
 * 글귀 notice_g51_pellet, sniper, explosion, fire, forgot.
 */
public final class G51 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.13, HOVER = 2.0;
    /** 가장 가까운 사람과 지키는 거리(이보다 가까우면 물러남, 멀면 다가옴). */
    static final double KEEP_MIN = 25, KEEP_MAX = 32;
    /** 맞으면 물러나는 틱과 빠르기 배율, 다시 물러나기까지(2초에 한 번). */
    static final int FLEE_T = 30, FLEE_CD = 40;
    static final double FLEE_MUL = 2.2;
    /** 고개 돌리는 빠르기(도/틱), 쏜 수를 다시 세는 간격, 점수 줄어드는 비율. */
    static final float TURN_RATE = 6f;
    static final int COUNT_EVERY = 5;
    static final double SCORE_DECAY = 0.8, SCORE_MIN = 0.6;
    /** 지켜보며 적는 찰칵 간격, 찰칵 자세 틱. */
    static final int NOTE_EVERY = 160, SNAP_T = 12;
    /** 다른 게이브가 다 잡힌 뒤 배우기까지, 배운 뒤 떠오르는 틱. */
    static final int ALONE_T = 60, LEAVE_T = 24;

    private G51Lesson lesson;
    private final Map<UUID, double[]> shots = new HashMap<>();
    private Player watch;
    private int fleeUntil = -1, fleeReady, snapUntil = -1, nextNote, aloneSince = -1, leaveAt = -1, side = 1, eyes = -1;

    public G51(Game game, Species sp) {
        super(game, sp);
        radius = 0.42;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        // 얼굴 0.8 + 위로 솟은 안테나 1.05
        body = Body.of(this, "gabe/g51", 1.6f, 2.6f);
    }

    @Override
    protected void spawned() {
        lesson = G51Lesson.of(game);
        side = game.random.nextBoolean() ? 1 : -1;
        nextNote = age + 60 + game.random.nextInt(NOTE_EVERY);
        showEyes();
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (lesson == null) lesson = G51Lesson.of(game);
        if (leaveAt >= 0) {
            leave();
            return;
        }
        lesson.observe();
        showEyes();
        if (every(COUNT_EVERY)) pickWatch();
        keepAway();
        if (watch != null && standing(watch)) turnTo(chest(watch), TURN_RATE);
        note();
        state(age < snapUntil ? "snap" : "");
        if (every(10)) aloneCheck();
    }

    /** 이마 눈 = 이어서 배운 횟수. */
    private void showEyes() {
        int n = lesson == null ? 0 : lesson.lessons;
        if (n == eyes || body == null) return;
        eyes = n;
        for (int i = 1; i <= G51Lesson.MAX_EYES; i++) {
            if (i <= n) body.look.on.add("eye" + i);
            else body.look.on.remove("eye" + i);
        }
    }

    /** 그 순간 가장 많이 쏘는 사람(쏜 수가 느는 빠르기, 5틱마다 줄어드는 점수). 아무도 안 쏘면 찍지 않고 가장 가까운 사람 쪽만 본다. */
    private void pickWatch() {
        Player best = null;
        double top = SCORE_MIN;
        for (Player p : game.mobs.standing()) {
            Guns.Arms a = game.guns.arms(p);
            if (a == null) continue;
            double[] s = shots.computeIfAbsent(p.getUniqueId(), k -> new double[] {a.shots, 0});
            double d = Math.max(0, a.shots - s[0]);
            s[0] = a.shots;
            s[1] = s[1] * SCORE_DECAY + d;
            if (s[1] > top) {
                top = s[1];
                best = p;
            }
        }
        if (best != null) {
            watch = best;
            mark(best);
        } else {
            if (target != null) mark(null);
            watch = nearest(200);
        }
        if (shots.size() > 64) shots.keySet().removeIf(id -> org.bukkit.Bukkit.getPlayer(id) == null);
    }

    /** 가장 가까운 사람과 25~32블록: 가까우면 물러나고(맞은 뒤엔 빠르게), 멀면 흐름장을 따라 다가오고, 사이면 옆으로 천천히 돈다. */
    private void keepAway() {
        Player n = nearest(400);
        if (n == null) {
            hold();
            return;
        }
        Location l = n.getLocation();
        Vector away = new Vector(pos.getX() - l.getX(), 0, pos.getZ() - l.getZ());
        double d = away.length();
        if (d < 1e-4) away = new Vector(1, 0, 0);
        else away.multiply(1 / d);
        boolean fleeing = age < fleeUntil;
        if (fleeing || d < KEEP_MIN) {
            vel.copy(away.multiply(speed() * (fleeing ? FLEE_MUL : 1.0)));
        } else if (d > KEEP_MAX) {
            follow(speed());
        } else {
            // 거리를 지키며 옆으로 천천히
            vel.setX(-away.getZ() * side * speed() * 0.3).setY(0).setZ(away.getX() * side * speed() * 0.3);
        }
        if (watch == null || !standing(watch)) turnTo(chest(n), TURN_RATE);
    }

    /** 찍은 사람이 쏘는 동안 가끔 찰칵(적는다). */
    private void note() {
        if (age < nextNote) return;
        nextNote = age + NOTE_EVERY + game.random.nextInt(60);
        if (target == null) return;
        snap();
    }

    private void snap() {
        snapUntil = age + SNAP_T;
        sound("shutter", Sound.BLOCK_DISPENSER_FAIL, 1.8f, 64);
    }

    /** 감독 대기열이 비고 다른 게이브(안 움직이는 미리 깔린 종, 같은 종 빼고)가 다 잡힌 채 3초: 웨이브 끝까지 살아남았다. */
    private void aloneCheck() {
        if (game.state != Game.State.WAVE || game.director.cageSpawning() || game.director.inCage(this)) {
            aloneSince = -1;
            return;
        }
        int alive = 0, others = 0;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || game.director.inCage(g)) continue;
            alive++;
            if (g instanceof G51) continue;
            if (g.sp.spawn == Species.Spawn.PLACED && g.target == null) continue;
            others++;
        }
        boolean queueEmpty = game.director.remaining() <= alive;
        if (others > 0 || !queueEmpty) {
            aloneSince = -1;
            return;
        }
        if (aloneSince < 0) aloneSince = age;
        if (age - aloneSince >= ALONE_T) graduate();
    }

    /** 배우고(웨이브마다 한 번) 떠난다. */
    private void graduate() {
        snap();
        sound("shutter", Sound.BLOCK_DISPENSER_FAIL, 1.4f, 64);
        lesson.learn();
        showEyes();
        mark(null);
        state("snap");
        untargetable = true;
        noRules = true;
        flying = true;
        leaveAt = age;
    }

    /** 위로 떠오르며 작아져 사라짐(보상 없음, 죽음이 아님). */
    private void leave() {
        int t = age - leaveAt;
        vel.setX(0).setY(0.35).setZ(0);
        if (t == 1) body.scale(0.2f, LEAVE_T - 2);
        if (t >= LEAVE_T) remove();
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        boolean ok = super.damage(d);
        if (!dead && d.type() != DamageType.BITE && age >= fleeReady) {
            // 맞으면 더 멀리(2초에 한 번)
            fleeUntil = age + FLEE_T;
            fleeReady = age + FLEE_CD;
            snapUntil = age + 6;
            sound("flinch", Sound.ENTITY_ITEM_BREAK, 1.6f, 32);
        }
        return ok;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        G51Lesson ls = lesson != null ? lesson : G51Lesson.of(game);
        if (d != null && d.by() != null) ls.forget(); // 사람에게 잡힘: 배운 것이 지워짐
        else if (noReward && game.state != Game.State.LOBBY && !game.director.inCage(this)) ls.learn(); // 웨이브 끝 정리, 막힘 정리: 살아남음
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " watch=" + (watch == null ? "-" : watch.getName()) + (age < fleeUntil ? " flee" : "") + (leaveAt >= 0 ? " leaving" : "")
                + " " + (lesson == null ? "" : lesson.status());
    }
}
