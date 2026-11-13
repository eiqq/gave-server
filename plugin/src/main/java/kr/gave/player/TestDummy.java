package kr.gave.player;

import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/** 시험 훅(가두기, 붙잡기)용 가만히 떠 있는 게이브. 보상 없음. 판에서는 쓰지 않는다. */
final class TestDummy extends Gabe {

    TestDummy(Game game, Species sp) {
        super(game, sp);
        noReward = true;
        reward = 0;
    }

    @Override
    public void tick() {
        hold();
    }
}
