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
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G108 죽은 척 게이브(함정, 떼, 미리 깔림). 웨이브 시작에 황야 맨땅(R 14~40) 여기저기 사체처럼 하늘을 보고 누워 있다(흉내: 첫 등장 안내, 이름,
 * 물어뜯기, 덮치기에서 빠짐). 10블록 안에서 총을 쏘면 한 발마다 25% 로 하나씩 깨고, 폭발은 12블록 안 모두를 깨운다. 맞고도 살면 깬다. 25초 동안
 * 아무도 안 깨우면 스스로 깬다. 깨면 벌떡 일어나 쏜 사람(없으면 가장 가까운 사람)에게 네 발로 달려들어 물기 6, 정강이에 매달려 0.5초마다 2(2초).
 * 매달린 것은 그 사람이 보고 좌클릭하면 떨어진다. 웅크려 들으면 자는 것의 코 고는 소리가 들린다.
 *
 * <p>총소리는 Records.shots(사람별 쏜 수)가 는 것을 틱마다 한 번(모든 G108 이 나눠 씀) 보고 알고, 폭발은 피해 배율 갈고리(Mobs.scale)로 폭발
 * 피해가 난 자리를 적어 둔 것과 Mobs.blastAt() 으로 안다.
 *
 * <p>팩 계약(tools/gabe/sp/g108.py): 모델 gave:gabe/g108 상태 "sleep"(기본), "up". 소리 snore(12), wake(16), 원인 글귀 cause_g108.
 */
public final class G108 extends Gabe {

    static final double HEAR_R = 10, BLAST_R = 12, WAKE_CHANCE = 0.25, RUN_V = 0.3;
    static final double BITE = 6, CLING_DMG = 2, CLING_SIDE = 0.45, SLEEP_HOVER = 0.3, UP_HOVER = 0.72;
    static final int SELF_WAKE = 500, RISE = 12, CLING = 40, CLING_EVERY = 10, REST = 30;
    static final int PACK = 5;

    // ------------------------------------------------------------------ 판 전체 소식(총소리, 폭발): 틱마다 한 번 모음

    private static Game seenGame;
    private static long seenTick = Long.MIN_VALUE;
    private static final Map<UUID, Integer> lastShots = new HashMap<>();
    /** 이번 틱에 새로 쏜 사람과 그 수. */
    private static final List<Player> shooters = new ArrayList<>();
    private static final List<Integer> shotCounts = new ArrayList<>();
    private static long blastTick = Long.MIN_VALUE;
    private static final Vector blast = new Vector();

    private static void gather(Game game) {
        long now = game.now();
        if (seenGame != game) {
            seenGame = game;
            lastShots.clear();
            game.mobs.scale("g108_blast", (g, d) -> {
                if (d.type() == DamageType.EXPLOSION && d.at() != null) {
                    blastTick = game.now();
                    blast.copy(d.at());
                }
                return 1.0;
            });
        }
        if (seenTick == now) return;
        seenTick = now;
        shooters.clear();
        shotCounts.clear();
        for (Player p : game.mobs.standing()) {
            int n = game.records.shots(p.getUniqueId());
            Integer was = lastShots.put(p.getUniqueId(), n);
            if (was != null && n > was) {
                shooters.add(p);
                shotCounts.add(n - was);
            }
        }
        Vector b = game.mobs.blastAt();
        if (b != null) {
            blastTick = now;
            blast.copy(b);
        }
    }

    // ------------------------------------------------------------------ 개체

    private boolean packed, awake;
    private int wakeAt = -1, clingAt = -1, restUntil = -1, readyAt;
    private Player victim;
    private final Vector clingOff = new Vector(1, 0, 0);

    public G108(Game game, Species sp) {
        super(game, sp);
        radius = 0.42;
        hover = SLEEP_HOVER;
        baseSpeed = RUN_V;
        disguised = true;
        noPush = true;
        state("sleep");
        yaw = game.random.nextFloat() * 360f;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g108", 1.6f, 0.8f);
    }

    @Override
    public void tick() {
        if (!packed) {
            packed = true;
            G82Pack.fill(this, PACK - 1, 0);
        }
        gather(game);
        if (!awake) {
            sleep();
            return;
        }
        if (victim != null) {
            cling();
            return;
        }
        if (age - wakeAt < RISE) {
            hold();
            return;
        }
        hover = UP_HOVER;
        if (age < restUntil) {
            hold();
            return;
        }
        Player p = target != null && standing(target) ? target : nearest(400);
        if (p != target) mark(p);
        if (p == null) {
            follow(speed());
            face(pos.clone().add(vel));
            return;
        }
        if (dist(p) > 5) follow(speed());
        else seek(chest(p), speed() * 1.15);
        face(chest(p));
        if (age >= readyAt && touches(p, 0.1)) latch(p);
    }

    private void sleep() {
        hold();
        Player by = null;
        boolean wake = false;
        for (int i = 0; i < shooters.size() && !wake; i++) {
            Player s = shooters.get(i);
            Location l = s.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz > HEAR_R * HEAR_R) continue;
            for (int k = 0; k < shotCounts.get(i); k++) {
                if (game.random.nextDouble() < WAKE_CHANCE) {
                    wake = true;
                    by = s;
                    break;
                }
            }
        }
        if (!wake && blastTick >= now() - 1 && blast.distanceSquared(pos) <= BLAST_R * BLAST_R) wake = true;
        if (!wake && age >= cd(SELF_WAKE)) wake = true;
        if (wake) wake(by);
    }

    /** 깸: 벌떡 일어나 쏜 사람(없으면 가장 가까운 사람)을 찍음. */
    private void wake(Player by) {
        if (awake) return;
        awake = true;
        wakeAt = age;
        disguised = false;
        noPush = false;
        state("up");
        hover = UP_HOVER;
        body.squash(1.1f, 1.35f, 1.1f, 4);
        sound("wake", Sound.ENTITY_ZOMBIE_AMBIENT, 1.3f, 16);
        Player p = by != null && standing(by) ? by : nearest(400);
        mark(p);
        if (p != null) face(chest(p));
    }

    private void latch(Player p) {
        hit(p, BITE, "cause_g108");
        if (!standing(p)) {
            restUntil = age + cd(REST);
            return;
        }
        victim = p;
        clingAt = age;
        noPush = true;
        Vector off = pos.clone().subtract(p.getLocation().toVector()).setY(0);
        if (off.lengthSquared() < 1e-6) off = new Vector(1, 0, 0);
        clingOff.copy(off.normalize().multiply(CLING_SIDE));
        body.squash(1.2f, 0.85f, 1.2f, 2);
    }

    /** 정강이에 매달려 0.5초마다 2, 2초 뒤 떨어짐. */
    private void cling() {
        Player p = victim;
        if (!standing(p) || age - clingAt >= CLING) {
            drop();
            return;
        }
        Location l = p.getLocation();
        seek(new Vector(l.getX() + clingOff.getX(), pos.getY(), l.getZ() + clingOff.getZ()), 0.8);
        face(new Vector(l.getX(), pos.getY(), l.getZ()));
        int in = age - clingAt;
        if (in > 0 && in % CLING_EVERY == 0) hit(p, CLING_DMG, "cause_g108");
    }

    private void drop() {
        victim = null;
        clingAt = -1;
        noPush = false;
        restUntil = age + cd(REST);
        readyAt = restUntil + cd(10);
    }

    @Override
    public boolean helped(Player p) {
        if (victim == null || p != victim) return false;
        drop();
        return true;
    }

    @Override
    public boolean damage(Damage d) {
        boolean r = super.damage(d);
        // 맞고도 살면 깬다(쏜 사람을 찍음)
        if (!dead && !awake) wake(d.by());
        return r;
    }

    @Override
    public String listenSound() {
        return awake ? null : "gave:gabe.g108.snore";
    }

    @Override
    public String toString() {
        return super.toString() + (awake ? victim != null ? " cling" : " up" : " sleep");
    }
}
