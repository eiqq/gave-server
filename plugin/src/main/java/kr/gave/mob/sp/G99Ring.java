package kr.gave.mob.sp;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G99 붓 게이브가 바닥에 그리는 먹 원(반지름 2.5): 판 규칙에서 빠지고 탄이 지나가는 바닥 디스플레이 하나(gabe/g99_ring). 조각은 부위 s0..s11
 * (세계 +X 에서 +Z 쪽으로 30도씩, 몸 방향 0 고정). 붓이 켜고 끄며, 붓이 죽거나 다른 원을 그리면 사라진다. 웨이브를 붙들지 않는다.
 */
final class G99Ring extends Gabe {

    final G99 owner;

    G99Ring(Game game, Species sp, G99 owner) {
        super(game, sp);
        this.owner = owner;
        radius = 0.1;
        flying = true;
        noPush = true;
        noRules = true;
        noMutate = true;
        noBite = true;
        untargetable = true;
        noHold = true;
        noReward = true;
        reward = 0;
        leaveCorpse = false;
        yaw = 0;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g99_ring", 5.6f, 0.4f);
        body.viewRange(1.8f);
    }

    @Override
    public void tick() {
        hold();
        yaw = 0;
        pitch = 0;
        if (owner.dead || owner.ring != this) remove();
    }

    void on(int i) {
        body.look.on.add("s" + i);
    }

    void off(int i) {
        body.look.on.remove("s" + i);
    }

    boolean isOn(int i) {
        return body.look.on.contains("s" + i);
    }

    void allOn() {
        for (int i = 0; i < 12; i++) on(i);
    }

    int lit() {
        int n = 0;
        for (int i = 0; i < 12; i++) if (isOn(i)) n++;
        return n;
    }
}
