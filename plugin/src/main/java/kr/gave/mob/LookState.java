package kr.gave.mob;

import java.util.Objects;
import java.util.TreeSet;

/**
 * 몸 겉모습 상태(틴트로 싣는 것). Body 가 들고 있다가 바뀌면 Look.colors 로 다시 싣는다.
 *
 * <p>팩의 모델마다 틴트 칸 역할표가 있고(make_gabe 가 assets/gave/gabe_looks.json 에 씀), Look 이 그 표와 이 상태로 색을 만든다.
 */
public final class LookState {

    /** 팔레트 비트(Look.PAL_*). */
    public int palette;
    /** 실눈이 뜨임(표적을 찍음). 판 규칙상 표적을 정한 종만 켠다. */
    public boolean open;
    /** 오래 살면 변함 단계(0 없음, 1 입 찢어짐, 2 막대기 넷). */
    public int mut;
    /** 펄럭임 멈춤(소리 없는 웨이브 등). */
    public boolean freeze;
    /** 개체마다 다른 위상(틱). */
    public int phase;
    /** 펄럭임 빠르기 배율(1 = 모델 기본, 2 = 두 배 빠름). */
    public double rate = 1.0;
    /** 켠 부위 이름들(모델이 정한 것, 예: "rod_l" 끊기면 끔 대신 "cut_l" 켬). */
    public final TreeSet<String> on = new TreeSet<>();
    /** 맞은 번쩍임 끝나는 월드 틱(셰이더가 흰 번쩍임). 0 이면 없음. */
    public long hurtUntil;
    /** 멈췄을 때(freeze) 보이는 자세 번호(기본 0). 래그돌 사체 상태(.c)가 바닥에 닿은 면의 누운 막대기 자세를 고르는 데 쓴다. */
    public int pose;

    public LookState copy() {
        LookState s = new LookState();
        s.palette = palette;
        s.open = open;
        s.mut = mut;
        s.freeze = freeze;
        s.phase = phase;
        s.rate = rate;
        s.on.addAll(on);
        s.hurtUntil = hurtUntil;
        s.pose = pose;
        return s;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LookState s && s.palette == palette && s.open == open && s.mut == mut && s.freeze == freeze && s.phase == phase
                && s.rate == rate && s.on.equals(on) && s.hurtUntil == hurtUntil && s.pose == pose;
    }

    @Override
    public int hashCode() {
        return Objects.hash(palette, open, mut, freeze, phase, rate, on, hurtUntil, pose);
    }
}
