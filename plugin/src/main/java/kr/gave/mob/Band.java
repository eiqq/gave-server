package kr.gave.mob;

/** 높이 띠. 멀리서 역할을 가르는 첫 신호이고, 같은 띠 = 같은 계열(지원 종이 돕는 범위). */
public enum Band {
    /** 바닥에 붙음(0 ~ 0.3블록). */
    GROUND(0.1),
    /** 허리 높이(0.6 ~ 1.2). */
    WAIST(0.8),
    /** 머리 위(1.5 ~ 3). */
    HEAD(2.0),
    /** 하늘(8블록 넘게). */
    SKY(10.0);

    /** 바닥에서 기본 떠 있는 높이. */
    public final double hover;

    Band(double hover) {
        this.hover = hover;
    }
}
