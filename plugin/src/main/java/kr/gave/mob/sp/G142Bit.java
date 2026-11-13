package kr.gave.mob.sp;

import org.bukkit.entity.Player;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G142 지네가 다리 사이에서 떨어뜨린 작은 지네 조각(0.7 블록 얼굴 + 다리 둘씩). 같은 종 G142 로 센다(보상 0). 가장 가까운 사람에게 달려들어
 * 물기 6(1초에 한 번). 체력 HP x 감독 체력 배율(4배까지).
 */
public final class G142Bit extends Gabe {

    static final double HOVER = 0.4, SPEED = 0.2, HP = 30, BITE = 6;
    static final int BITE_CD = 20;

    private int biteAt;

    G142Bit(Game game, Species sp) {
        super(game, sp);
        radius = 0.4;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true;
        noBite = true;
        ragKick = 1.2;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g142_bit", 2f, 1.2f);
    }

    @Override
    public void tick() {
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        if (p != target) mark(p);
        face(chest(p));
        pitch = 0;
        follow(speed());
        body.look.rate = 1.5;
        if (age >= biteAt && touches(p, 0.15)) {
            hit(p, BITE, "cause_g142_bit");
            biteAt = age + cd(BITE_CD);
            body.squash(1.3f, 0.75f, 1.3f, 2);
        }
        if (age % 10 == 0) body.squash(1, 1, 1, 3);
    }
}
