package kr.gave.game;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * 증강이 원정 부품(트럭, 꺼내기, 막)에 묻는 것. 기본으로 {@link AugTrip}(허브 Trip 과 트럭 부품을 이름으로 찾아 물음)이 달려 있고,
 * 다른 것으로 바꾸려면 {@code game.augments.links} 에 넣는다. 아래 기본값: 트럭 자리 = 지금 상점 카운터, 트럭 부품과 꺼내기는 없음.
 */
public interface AugLinks {

    /** 트럭 가운데(발 높이). 기본은 상점 카운터 가운데 */
    default Vector truckPos() {
        return Shop.counterCenter();
    }

    /** 그 원정 부품이 있나: "truck"(트럭 부품이 올라와 있음), "revive"(유료 꺼내기). 참이면 그것에 기대는 증강과 부품이 뽑기에 나옴 */
    default boolean ready(String need) {
        return false;
    }

    /** 트럭이 가진 그 부품 수(키 p_plate ...). 트럭 부품이 없으면 0 */
    default int part(String key) {
        return 0;
    }

    /** 이 사람이 트럭 위(추격전의 판 위)라 늘 트럭 곁인가 */
    default boolean onTruck(Player p) {
        return false;
    }

    /** 지금 막(1..4). 0 이면 모름 */
    default int act() {
        return 0;
    }

    /** 값 단위 U(원). 0 이면 지금 웨이브 무기 상자 값 */
    default int unit() {
        return 0;
    }
}
