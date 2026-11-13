package kr.gave.mob.sp;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G155 묶인 거인의 일꾼(손톱만 한 얼굴 0.45 + 다리 넷). 같은 종 G155 로 센다(보상 0). 흙먼지 끝에서 나와 빠르게 말뚝으로 기어가
 * 매듭을 갉는다(갉는 동안 틱마다 거인의 말뚝 진행). 곁(닿는 거리)의 사람은 물기 3. 거인이 죽거나 다 풀리면 사람에게 달려든다.
 */
public final class G155Worker extends Gabe {

    static final double HOVER = 0.28, SPEED = 0.21, HP = 10, BITE = 3;
    static final int BITE_CD = 20;

    final G155 giant;
    int stake = -2;
    private int biteAt, gnawSnd;

    G155Worker(Game game, Species sp, G155 giant) {
        super(game, sp);
        this.giant = giant;
        radius = 0.3;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true;
        noBite = true;
        ragKick = 1.4;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g155_worker", 1.6f, 0.9f);
    }

    @Override
    public void tick() {
        Player p = nearest(400);
        boolean working = giant != null && !giant.dead && !giant.removed && giant.left() > 0;
        if (working && (stake == -2 || (stake >= 0 && giant.pulled(stake)))) stake = giant.pickStake();
        if (!working || stake < 0) {
            // 할 일이 없으면 사람에게
            if (p == null) {
                follow(speed());
                return;
            }
            if (p != target) mark(p);
            face(chest(p));
            pitch = 0;
            follow(speed());
            biteNear(p);
            return;
        }
        Vector at = giant.stakeAt(stake);
        double dx = at.getX() - pos.getX(), dz = at.getZ() - pos.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 0.9) {
            seek(new Vector(at.getX(), pos.getY(), at.getZ()), speed());
            face(at);
            pitch = 0;
        } else {
            hold();
            face(at);
            pitch = 25;
            giant.gnaw(stake);
            if (age >= gnawSnd) {
                gnawSnd = age + 16;
                sound("gnaw", Sound.ENTITY_GENERIC_EAT, 1.4f, 16);
                body.squash(1.15f, 0.85f, 1.15f, 3);
            } else if (age % 8 == 4) body.squash(1, 1, 1, 3);
        }
        if (p != null) biteNear(p);
    }

    private void biteNear(Player p) {
        if (age >= biteAt && touches(p, 0.15)) {
            hit(p, BITE, "cause_g155_worker");
            biteAt = age + cd(BITE_CD);
            body.squash(1.3f, 0.75f, 1.3f, 2);
        }
    }
}
