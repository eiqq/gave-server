package kr.gave.mob;

/** 도감 역할. 감독이 조합 패(앞잡이, 받침, 가시)를 짤 때 쓴다. */
public enum Role {
    CHARGE, SWARM, RANGED, TRAP, DISRUPT, SHIELD, TANK, SUPPORT, ELITE, BONUS, OBJECTIVE,
    /** 10웨이브마다 나오는 보스(2026-11-12 사용자, docs/모듈/보스.md). 감독이 보통 웨이브에 뽑지 않고, 다른 종이 고르거나 먹지 않는다. */
    BOSS
}
