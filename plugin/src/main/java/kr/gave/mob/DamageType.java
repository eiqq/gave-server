package kr.gave.mob;

/** 피해 종류. 보고 배우는 게이브(G51)가 배우는 단위이기도 하다. */
public enum DamageType {
    /** 권총, 기관단총, 소총 한 발. */
    BULLET,
    /** 산탄 한 알. */
    PELLET,
    /** 저격총 한 발. */
    SNIPER,
    /** 수류탄, 폭발탄, 혹 터짐. */
    EXPLOSION,
    /** 화염병, 화염탄. */
    FIRE,
    /** 게이브끼리 물어뜯기, 환경. */
    BITE,
    /** 시험 명령, 판 정리. */
    OTHER
}
