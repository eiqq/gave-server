package kr.gave.mob;

import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Vector;

/**
 * 시체 하나(시체 풀). 래그돌 사체({@link Ragdoll}: 구르고 굳었다가 땅으로 꺼짐)이거나, 예산이 넘쳤을 때의 납작 사체(최대
 * {@link Mobs#CORPSE_MAX})다. 시체 뜯는 종(G11, G12, G19)이 {@link Mobs#corpses()} 로 찾고 {@link Mobs#eat(Corpse)} 로 치운다.
 */
public final class Corpse {

    /** 누구의 시체인가. */
    public final Species sp;
    /** 바닥 자리(세계 좌표). 래그돌 사체는 구르는 동안 이 값이 따라 바뀐다(같은 Vector 를 고쳐 씀). */
    public final Vector pos;
    /** 죽은 판 틱. */
    public final long at;
    long until;
    final Body body;
    final ItemDisplay single;
    /** 래그돌 사체면 그 구(막대기 마디까지 함께 치움). */
    Ragdoll.Doll doll;
    boolean gone;

    Corpse(Species sp, Vector pos, long at, long until, Body body, ItemDisplay single) {
        this.sp = sp;
        this.pos = pos;
        this.at = at;
        this.until = until;
        this.body = body;
        this.single = single;
    }

    /** 이미 치웠나. */
    public boolean gone() {
        return gone;
    }

    /** 남은 틱. */
    public long left(long now) {
        return Math.max(0, until - now);
    }

    void remove() {
        if (gone) return;
        gone = true;
        if (doll != null) doll.dispose();
        if (body != null) body.remove();
        if (single != null && single.isValid()) single.remove();
    }
}
