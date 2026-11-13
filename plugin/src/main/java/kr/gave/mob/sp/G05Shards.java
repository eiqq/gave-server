package kr.gave.mob.sp;

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
 * G05 가 터진 뒤 쏟아진 작은 얼굴 조각들(보조 개체, 종은 G05, 보상 0). 3초 동안 바닥을 기어 가까운 사람 쪽으로 모이고, 그동안 밟히거나(사람이
 * 0.9 블록 안에 서면) 쏘면 치워진다. 3초 안에 안 치우면 작은 분홍 웅덩이(6초)가 되어 그 위에 선 사람의 녹음을 올리고 느리게 한다.
 *
 * <p>팩: {@code gave:gabe/g05_shard} 상태 {@code ""}(기는 조각), {@code "puddle"}. 소리 gave:gabe.g05.stomp.
 */
public final class G05Shards extends Gabe {

    static final int CRAWL = 60, PUDDLE = 120, FADE = 10;
    static final double CRAWL_V = 0.03, STOMP_R = 0.9, PUDDLE_R = 1.0, PUDDLE_MELT = 0.022;
    static final double CRAWL_HP = 6, PUDDLE_HP = 20;

    private boolean puddle;
    private int fadeAt = -1, spreadAt = -1;

    G05Shards(Game game, Species sp) {
        super(game, sp);
        radius = 0.7;
        hover = 0.05;
        baseSpeed = CRAWL_V;
        noPush = true;
        noRules = true;
        noMutate = true;
        noBite = true;
        leaveCorpse = false;
        noReward = true;
        reward = 0;
        maxHp = hp = CRAWL_HP;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g05_shard", 2.0f, 0.4f);
    }

    @Override
    public void tick() {
        if (fadeAt >= 0) {
            hold();
            if (age >= fadeAt + FADE) remove();
            return;
        }
        if (!puddle) crawl();
        else pool();
    }

    private void crawl() {
        Player p = nearest(12);
        if (p != null) {
            Vector d = toward(p);
            vel.copy(d.multiply(CRAWL_V));
            face(chest(p));
            pitch = 0;
        } else hold();
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= STOMP_R * STOMP_R && Math.abs(l.getY() - floor()) < 0.6) {
                // 밟힘: 치운 사람 손에 죽음(보상 없음)
                sound("stomp", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 12);
                die(new Damage(hp, DamageType.OTHER, q, pos.clone(), null, "stomp", 0));
                return;
            }
        }
        if (age >= CRAWL) {
            puddle = true;
            state("puddle");
            hold();
            maxHp = hp = PUDDLE_HP;
            radius = 0.85;
            // 조각이 가운데로 모였다가(오므림) 웅덩이로 퍼짐: 퍼지는 것은 다음 틱에(같은 틱에 둘을 부르면 뒤의 것만 남음)
            body.squash(0.6f, 1f, 0.6f, 0);
            spreadAt = age;
        }
    }

    private void pool() {
        hold();
        if (spreadAt >= 0 && age > spreadAt) {
            body.squash(1f, 1f, 1f, 6);
            spreadAt = -1;
        }
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz > PUDDLE_R * PUDDLE_R || Math.abs(l.getY() - floor()) > 0.6) continue;
            G05Melt.add(game, this, q, PUDDLE_MELT);
            game.players.slow(q, 30, 10, "g05_puddle");
        }
        if (age >= CRAWL + PUDDLE) {
            fadeAt = age;
            body.scale(0.05f, FADE);
        }
    }

    @Override
    public boolean damage(Damage d) {
        if (fadeAt >= 0) return false;
        return super.damage(d);
    }

    @Override
    public String toString() {
        return super.toString() + (puddle ? " puddle" : " shards");
    }
}
