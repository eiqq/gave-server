package kr.gave.trip;

import kr.gave.game.Director;

/**
 * 특수 라운드(원정.md 5.3절, 묶음 8, 9 trip_special) 한 가지의 약속. 클래스는 {@code kr.gave.trip.special.<이름>}(이름은 trip/rounds_final.json 의
 * specials[].cls: Chase, Breakdown, Refuel ...), 공개 생성자 {@code (Game, Trip, Rounds)} 또는 {@code (Game, Trip)}. {@link Rounds} 가 이름으로 찾아 만들고
 * (없으면 그 라운드는 보통 라운드로 떨어진다) 그 라운드 동안만 TripPart 부름을 넘겨준다.
 *
 * <p>차례: {@link #plan}(beforeRound: 쉬는 시간에 짠 감독 계획을 고칠 기회, 계획이 없으면 null) → {@link #open}(라운드 시작 beginRound 뒤) →
 * 웨이브 중 tick, roundDone, lost, hud, mul, ask, on, help, test, status(TripPart 기본 구현 그대로 덮어쓰면 됨) → {@link #close}(쉬는 시간이 열릴 때,
 * 판 끝, 판 정리, 시험 '특수 x 끝'). roundDone(done) 의 done 은 감독 판정(Rounds 의 목표가 아님): 특수가 끝 조건을 정한다.
 *
 * <p>바탕(Rounds 공개 메서드): {@link Rounds#goals()}(목표 막대: custom(아이콘, 몫), count(얻은 수, 필요 수), timer(초), 표시 원, 줍기, 표적),
 * {@link Rounds#finish(boolean)}(라운드를 끝냄: 성공이면 보상), {@link Rounds#spawnRing}, {@link Rounds#seekTruck}, {@link Rounds#kind()}(지금 라운드 종류, 특수는 null).
 * 추격전 메모(증강 담당 요청): 트럭이 무언가를 들이받는 피해에는 game.augments.ramMul() 을 곱한다(범퍼 부품).
 */
public interface SpecialRound extends TripPart {

    /** 라운드 n 을 특수 key 로 연다(감독이 웨이브를 연 뒤). false 면 못 엶: Rounds 가 보통 라운드로 대신한다. */
    default boolean open(int n, String key) {
        return true;
    }

    /** 끝(쉬는 시간이 열릴 때, 판 끝, 판 정리, 시험): 놓은 것을 치운다. 여러 번 불려도 된다. */
    default void close() {
    }

    /** 쉬는 시간에 짠 다음 라운드 감독 계획을 고칠 기회(beforeRound, 계획이 그 라운드 것일 때만. 없으면 부르지 않음). */
    default void plan(Director.Plan p) {
    }
}
