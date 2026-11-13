package kr.gave.mob.sp;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G84 오르골 게이브(돌격, 떼). 셋이 짝지어 조용히 다가오다 사람 26블록 안에 들면 오르골 곡을 틀고 걸음보다 느리게(0.09) 미끄러져 온다. 곡(8초 x 박자
 * 배율)이 끝나 갈수록 작은 얼굴의 회전이 늘어지고, 끝나면 '철컥' 하며 뚜껑 얼굴이 젖혀져 눈을 뜨고 다리 넷이 펴져 가장 가까운 사람에게 일직선으로
 * 질주(0.5블록/틱, 방향을 안 틂)한다. 닿으면 물기 12와 넘어짐 0.5초(묶임 10틱). 맞히든 빗나가든 2초 뒤 다시 상자로 접혀 짧은 곡(4초)을 감는다.
 *
 * <p>팩 계약(tools/gabe/sp/g84.py): 모델 gave:gabe/g84 상태 "box"(기본), "open". 소리 tune(24), latch(24), 원인 글귀 cause_g84.
 */
public final class G84 extends Gabe {

    static final double APPROACH_V = 0.16, TUNE_V = 0.09, RUSH_V = 0.5, START_R = 26;
    static final double BITE = 12, BOX_HOVER = 0.46, OPEN_HOVER = 0.98;
    static final int TUNE = 160, TUNE_SHORT = 80, SLOW_END = 40, WINDUP = 6, RUSH = 34, REWIND = 40, ROOT = 10, TUNE_EVERY = 70;
    static final int PACK = 3;

    enum Phase { APPROACH, TUNE, WINDUP, RUSH, REST }

    /** 곡이 겹쳐 시끄럽지 않게: 마지막으로 곡을 튼 틱과 자리(가까운 다른 상자가 막 틀었으면 건너뜀). */
    private static long tuneAt = Long.MIN_VALUE / 2;
    private static final Vector tunePos = new Vector();

    private Phase phase = Phase.APPROACH;
    private int phaseAt, tuneLen = TUNE, nextTune;
    private boolean packed, bit;
    private final Vector rush = new Vector(0, 0, 1);

    public G84(Game game, Species sp) {
        super(game, sp);
        radius = 0.48;
        hover = BOX_HOVER;
        baseSpeed = APPROACH_V;
        state("box");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g84", 1.2f, 1.4f);
    }

    @Override
    public void tick() {
        if (!packed) {
            packed = true;
            G82Pack.fill(this, PACK - 1, 2.5);
        }
        switch (phase) {
            case APPROACH -> approach();
            case TUNE -> tune();
            case WINDUP -> windup();
            case RUSH -> rush();
            case REST -> rest();
        }
    }

    private void go(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int in() {
        return age - phaseAt;
    }

    private void approach() {
        follow(speed());
        face(pos.clone().add(vel));
        Player p = nearest(400);
        if (p != null && dist(p) <= START_R) startTune(TUNE);
    }

    private void startTune(int len) {
        tuneLen = cd(len);
        nextTune = 0;
        body.look.rate = 1.0;
        go(Phase.TUNE);
    }

    private void tune() {
        double k = TUNE_V / APPROACH_V;
        follow(speed() * k);
        Player p = nearest(400);
        face(p != null ? chest(p) : pos.clone().add(vel));
        pitch = 0;
        int left = tuneLen - in();
        if (in() >= nextTune) {
            nextTune = in() + TUNE_EVERY;
            long now = now();
            if (now - tuneAt > 50 || tunePos.distanceSquared(pos) > 144) {
                tuneAt = now;
                tunePos.copy(pos);
                sound("tune", Sound.BLOCK_NOTE_BLOCK_CHIME, 1.4f, 24);
            }
        }
        // 끝나 갈수록 작은 얼굴의 회전이 늘어짐
        // 네 단계로만 바꿈(겉모습을 매 틱 다시 싣지 않게)
        body.look.rate = left < SLOW_END ? Math.max(0.25, Math.ceil(left * 4.0 / SLOW_END) / 4.0) : 1.0;
        if (left <= 0) {
            sound("latch", Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.8f, 24);
            body.look.rate = 1.0;
            mark(p);
            state("open");
            hover = OPEN_HOVER;
            body.squash(1.15f, 1.25f, 1.15f, 3);
            go(Phase.WINDUP);
        }
    }

    private void windup() {
        hold();
        Player p = target != null && standing(target) ? target : nearest(400);
        if (p != null) {
            face(chest(p));
            pitch = 0;
            rush.copy(toward(p));
        }
        if (in() >= cd(WINDUP)) {
            bit = false;
            go(Phase.RUSH);
        }
    }

    /** 일직선 질주(방향을 안 틂). */
    private void rush() {
        double v = RUSH_V * speed() / APPROACH_V;
        dash(rush, v);
        face(pos.clone().add(rush));
        for (Player q : game.mobs.standing()) {
            if (touches(q, 0.1)) {
                hit(q, BITE, "cause_g84");
                game.players.root(q, ROOT, "g84_trip");
                knock(q, rush, 0.3, 0.06);
                body.squash(1.3f, 0.8f, 1.3f, 2);
                bit = true;
                go(Phase.REST);
                return;
            }
        }
        if (in() >= RUSH || wallAhead(0.6)) go(Phase.REST);
    }

    /** 2초 쉬고 다시 상자로 접혀 짧은 곡을 감는다. */
    private void rest() {
        hold();
        if (target != null) mark(null);
        if (in() >= cd(REWIND)) {
            state("box");
            hover = BOX_HOVER;
            Player p = nearest(400);
            if (p != null && dist(p) <= START_R) startTune(TUNE_SHORT);
            else go(Phase.APPROACH);
        }
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + (bit ? " bit" : "");
    }
}
