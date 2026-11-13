package kr.gave.mob.sp;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G61 설 짐승이 죽을 때 천 속에서 튀어나오는 작은 게이브(gabe/g61_kid, 0.6 얼굴 + 다리 막대기 둘). 가장 가까운 사람에게 달려가 박치기 6
 * (박자 배율 쿨다운). 보상 없음(G61 이 놓을 때 noReward).
 */
final class G61Kid extends Gabe {

    static final double HOVER = 0.75, SPEED = 0.2, HP = 40, RAM = 3;
    static final int CD = 30;

    private int readyAt;

    G61Kid(Game game, Species sp) {
        super(game, sp);
        radius = 0.38;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g61_kid", 0.8f, 1.4f);
    }

    @Override
    protected void spawned() {
        readyAt = age + 10;
        body.look.phase = game.random.nextInt(8);
    }

    @Override
    public void tick() {
        Player p = game.mobs.forcedFor(this);
        if (p == null) p = nearest(400);
        if (age < 8) return; // 튀어나오는 첫 속도 그대로
        if (p == null) {
            follow(speed());
            return;
        }
        follow(speed());
        face(chest(p));
        if (dist(p) < 12) mark(p);
        if (age >= readyAt && touches(p, 0.15)) {
            readyAt = age + cd(CD);
            hit(p, RAM, "cause_g61_kid");
            knock(p, toward(p), 0.35, 0.2);
            sound("clack", Sound.ENTITY_SLIME_ATTACK, 1.5f, 24);
        }
    }
}
