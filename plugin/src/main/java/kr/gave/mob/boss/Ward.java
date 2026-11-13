package kr.gave.mob.boss;

import org.bukkit.util.Vector;

/**
 * 지킬 것: 사람이 아닌 보스 표적(중앙의 트럭 같은 것. docs/모듈/보스.md 11.13). 그것을 만드는 모듈이 {@code game.director.bosses.guard(ward)} 로 올리고
 * 없어질 때 {@code unguard(ward)} 로 내린다. 보스는 {@link Boss#ward()} 로 가장 가까운 것을 찾고, 공격의 표적 고르기를 {@link Boss.Pick#WARD} 로 두면
 * 그것을 겨눈다(없으면 사람). 피해는 {@link Boss#hitWard}(보스 표 값 x 회차, 화남). 맵 연출({@link Terrain})은 지킬 것 둘레 땅을 바꾸지 않는다.
 */
public interface Ward {

    /** 글귀, 기록용 이름(예: truck). */
    String key();

    /** 가운데(바닥 높이). */
    Vector pos();

    /** 수평 반지름(블록): 예고 안에 들었는지, 땅을 바꾸지 않을 둘레. */
    double radius();

    /** 아직 지킬 것이 남아 있나(터졌으면 false). */
    boolean alive();

    /** 보스가 준 피해(사람 피해와 같은 단위: 체력 100 기준 표 값에 회차, 화남 배율을 곱한 값). 체력과 판 끝은 지킬 것 쪽이 정한다. */
    void hurt(double amount, Boss by, String cause);
}
