package kr.gave.player;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 사람 죽음의 결과로 생기는 종(업은 G38, 총 주운 G52, 사람 탈 G50)이 구현한다. Players 가 출혈이 끝난 사람의 결과를 굴려
 * Mobs.spawn 으로 그 종을 죽은 자리(바닥 + 띠 높이)에 놓은 바로 뒤에 한 번 부른다.
 *
 * <p>dead 는 숨겨진 채 하늘에서 지켜보는 중(Life.DEAD)이고 state.deathMob == 이 개체. 이 개체가 사람에게 잡히면(Mobs.onDeath 가 모든
 * 죽음에 Players.freeFrom 을 부름) G38, G52 는 dead 를 바로 상점 앞에서 부활시킨다(재화 손실 없음). G50 은 부활 없음.
 * 웨이브 끝 정리, 막힘 정리로 치워지면(noReward 가 켜진 죽음, 쉬는 시간 첫 틱의 죽음) 잡힌 것이 아니라 살아남은 것: G38 이면
 * 그 사람은 다음 쉬는 시간 부활을 한 번 건너뛰고, G52 는 보통 죽음처럼 쉬는 시간에 손실 부활. 그러니 G38, G52 는 noReward 를 켜지 말 것.
 * 다른 개체로 바뀌면(머리를 업은 게이브가 따로 생기는 식) Players.transferTrapped(from, to) 로 deathMob 을 옮길 것.
 * state.noLikeness 가 true 면 그 사람 얼굴, 이름을 쓰지 말 것.
 */
public interface DeathBorn {

    void bornFrom(Player dead, PState state, Location at);
}
