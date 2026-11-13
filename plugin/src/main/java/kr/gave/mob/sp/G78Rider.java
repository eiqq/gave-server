package kr.gave.mob.sp;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G78 사냥개가 죽으면 뛰어내린 기수(보조 개체, 보상 0). 무리가 맡은 사람(없으면 가까운 사람)을 맨발로 쫓아 닿으면 걷어찬다(5). 무리의 마지막이 죽으면
 * 훔친 돈을 돌려준다(G78.packDied). 모델 gabe/g78_rider 상태 "run".
 */
final class G78Rider extends Gabe {

    static final double SPEED = 0.17, HOVER = 0.62, KICK_DMG = 1, HP_SHARE = 0.3;
    static final int KICK_CD = 20, JUMP = 10;

    private final G78.Pack pack;
    private int kickAt;

    G78Rider(Game game, Species sp, G78.Pack pack, double dogMaxHp) {
        super(game, sp);
        this.pack = pack;
        radius = 0.3;
        hover = HOVER;
        baseSpeed = SPEED;
        noReward = true;
        reward = 0;
        noMutate = true;
        maxHp = hp = Math.max(6, dogMaxHp * HP_SHARE);
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g78_rider", 0.9f, 1.1f);
        state("run");
    }

    @Override
    public void tick() {
        // 뛰어내림: 처음 JUMP 틱은 한 번 튀어 오름
        if (age < JUMP) hover = HOVER + 0.6 * Math.sin(Math.PI * age / JUMP);
        else hover = HOVER;
        Player p = pack != null && pack.target != null && standing(pack.target) ? pack.target : nearest(400);
        if (p == null) {
            follow(speed());
            face(pos.clone().add(vel));
            return;
        }
        if (target != p && dist(p) < 20) mark(p);
        if (dist(p) > 16) follow(speed());
        else seek(chest(p).setY(pos.getY()), speed());
        face(chest(p));
        if (age >= kickAt && touches(p, 0.2)) {
            hit(p, KICK_DMG, "cause_g78");
            knock(p, toward(p), 0.3, 0.15);
            sound("kick", Sound.ENTITY_PLAYER_ATTACK_WEAK, 1.2f, 16);
            body.squash(1.1f, 0.9f, 1.1f, 2);
            kickAt = age + cd(KICK_CD);
        }
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        super.die(d);
        // 기수는 늘 보상 0 이라 웨이브 끝 정리와 사람 손을 피해를 준 사람(by)으로 가름
        G78.packDied(game, pack, d == null || d.by() == null);
    }

    @Override
    public String toString() {
        return super.toString() + " rider";
    }
}
