package kr.gave.mob.sp;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * 새끼 가오리 하나(보조 개체, 보상 0). G41 가시 잠복이 터질 때 등에서 나오는 새끼와 G42 알주머니가 떨구는 새끼가 같이 쓴다
 * (종, 모델, 원인 글귀, 피해는 만드는 쪽이 정함). 주인(host)을 쫓아가 한 번 쪼고(피해) 퍼덕이며 달아나다 사라진다. 주인이 쓰러지면 가까운 사람.
 *
 * <p>판 규칙(오래 살면 변함, 물어뜯기)에서 빠지고, 수명이 다하면 그냥 사라진다(웨이브가 이것 때문에 끝나지 않는 일이 없게).
 * 소리 {@code gave:gabe.<종>.peck}.
 */
final class G41Baby extends Gabe {

    static final double SPEED = 0.2, R = 0.2, HOVER = 1.0, FALL_V = 0.38;
    static final int FLEE = 26;

    private final String model, cause;
    private final double dmg;
    private final int life;
    private Player host;
    private boolean falling;
    private int fleeAt = -1, scatterUntil;
    private final Vector fleeDir = new Vector(), scatterDir = new Vector();

    /**
     * @param drop true 면 놓인 높이에서 바닥까지 떨어진 뒤 쫓기 시작(알주머니에서 떨어짐)
     * @param life 수명(틱)
     */
    G41Baby(Game game, Species sp, String model, String cause, double dmg, Player host, int life, double hp, boolean drop) {
        super(game, sp);
        this.model = model;
        this.cause = cause;
        this.dmg = dmg;
        this.host = host;
        this.life = life;
        radius = R;
        hover = HOVER;
        baseSpeed = SPEED;
        noReward = true;
        reward = 0;
        noMutate = true;
        noBite = true;
        maxHp = this.hp = hp;
        falling = drop;
        flying = drop;
    }

    /** 처음 ticks 동안은 dir 쪽으로 흩어졌다가 쫓기 시작(등에서 터져 나오는 순간). */
    void scatter(Vector dir, int ticks) {
        scatterDir.copy(dir).setY(0);
        if (scatterDir.lengthSquared() > 1e-8) scatterDir.normalize();
        scatterUntil = ticks;
    }

    @Override
    protected void build() {
        body = Body.of(this, model, 1.6f, 0.8f);
    }

    @Override
    public void tick() {
        if (age >= life) {
            remove();
            return;
        }
        if (falling) {
            double fl = floor() + HOVER;
            if (pos.getY() - FALL_V <= fl) {
                falling = false;
                flying = false;
                vel.zero();
            } else {
                vel.setX(vel.getX() * 0.9).setZ(vel.getZ() * 0.9).setY(-FALL_V);
            }
            return;
        }
        if (fleeAt >= 0) {
            // 쪼고 난 뒤: 퍼덕이며 달아나다 사라짐
            vel.copy(fleeDir).multiply(speed() * 1.1);
            hover += 0.035;
            face(pos.clone().add(fleeDir));
            if (age - fleeAt >= FLEE) remove();
            return;
        }
        if (age < scatterUntil) {
            vel.copy(scatterDir).multiply(speed() * 0.9);
            face(pos.clone().add(scatterDir));
            return;
        }
        Player p = standing(host) ? host : nearest(32);
        if (p == null) {
            hold();
            return;
        }
        if (target != p) mark(p);
        Vector d = toward(p);
        double sway = Math.sin((age + id * 17) * 0.3) * 0.35;
        vel.copy(d.clone().multiply(speed()).add(new Vector(-d.getZ(), 0, d.getX()).multiply(sway * speed())));
        face(chest(p));
        if (touches(p, 0.05)) {
            hit(p, dmg, cause);
            sound("peck", Sound.ENTITY_SLIME_ATTACK, 1.8f, 12);
            body.squash(1.3f, 0.75f, 0.8f, 2);
            fleeDir.copy(d.multiply(-1).rotateAroundY((game.random.nextDouble() - 0.5) * 1.6));
            mark(null);
            fleeAt = age;
        }
    }
}
