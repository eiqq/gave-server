package kr.gave.player;

import org.bukkit.entity.Player;

/**
 * 사람에게 붙는 종(붙잡기, 머리 부착)이 구현하면, 그 붙음이 풀리는 순간 Players 가 부른다. 구현하지 않으면 종이 매 틱
 * Players.grabbedBy(p) == this, PState.headBy == this 를 확인하면 된다.
 *
 * @param how "escape"(본인 연타), "detach"(동료 돕기, helper 가 그 동료), "release"(누가 release 를 부름, 쓰러짐, 부활, 판 정리)
 */
public interface Detachable {

    void detached(Player victim, Player helper, String how);
}
