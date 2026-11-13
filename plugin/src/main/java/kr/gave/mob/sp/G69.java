package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G69 털 뭉치 게이브(떼). 털 막대기 열여섯이 사방으로 뻗은 털공이 데굴데굴 굴러 오고, 얕은 물웅덩이를 지나면 그 자리에서 젖어 작은 털공 다섯으로
 * 갈라진다(원래 것도 작은 것이 됨 + 새끼 넷, 보상 0, 예산이 차면 그만큼 덜). 작은 것은 다시 안 갈라진다.
 *
 * <p>길: 놓일 때 돌아가는 길이 DETOUR 블록 안인 물웅덩이가 있으면 그쪽으로 굴러가 젖는다(신호: 물웅덩이 쪽으로 굴러가는 털공). 없으면 사람에게 곧장.
 * 불(FIRE)에 맞으면 털이 타서(탈색) 갈라지지 않는다.
 *
 * <p>굴림은 몸 pitch 를 빠르기만큼 계속 돌린다. 부딪혀 물기 BITE(작은 것 SMALL_BITE), 한 사람 발치에 CLING 이상 붙으면 느려짐 SLOW%.
 *
 * <p>팩 계약(tools/gabe/sp/g69.py): 상태 "", "wet", "small", 소리 fluff(16), split(24), bite(12), 글귀 cause_g69.
 */
public final class G69 extends Gabe {

    static final double HOVER = 0.62, SMALL_HOVER = 0.3, RADIUS = 0.55, SMALL_R = 0.28;
    /** 굴러오는 빠르기, 작은 것. 몸이 도는 반지름(굴림 각도). */
    static final double SPEED = 0.17, SMALL_SPEED = 0.21, ROLL_R = 0.6, SMALL_ROLL_R = 0.3;
    /** 피해, 쿨다운(박자 배율), 붙음 수, 느려짐. */
    static final double BITE = 3, SMALL_BITE = 2, SLOW = 20, CLING_R = 1.6;
    static final int BITE_CD = 20, CLING = 4, SLOW_T = 20;
    /** 갈라짐: 작은 것 체력, 새끼 수, 젖는 틱, 튀어 나가는 빠르기와 틱, 물웅덩이로 돌아가는 길 상한. */
    static final double SMALL_HP = 3, POP_V = 0.35, DETOUR = 15;
    static final int KIDS = 4, WET_T = 10, POP_T = 8;

    /** child() 로 작은 것을 만드는 동안 참(그 생성자가 읽음). */
    private static boolean nextSmall;
    private static List<Vector> puddles;

    private boolean small, burnt, wet;
    private int wetAt, readyAt, popUntil, nextFluff;
    private final Vector pop = new Vector();
    /** 굴러갈 물웅덩이(없으면 null). */
    private Vector goal;
    private double goalR;

    public G69(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
        if (nextSmall) {
            small = true;
            radius = SMALL_R;
            hover = SMALL_HOVER;
            baseSpeed = SMALL_SPEED;
            state("small");
        }
    }

    private static List<Vector> puddles() {
        if (puddles == null) puddles = Plaza.puddles();
        return puddles;
    }

    @Override
    protected void spawned() {
        nextFluff = age + game.random.nextInt(40);
        if (small) return;
        // 돌아가는 길이 짧은 물웅덩이 하나를 고름(가운데까지 가는 길 대비)
        Vector c = new Vector(Plaza.CX + 0.5, pos.getY(), Plaza.CZ + 0.5);
        double direct = flat(pos, c), best = DETOUR;
        List<Vector> ps = puddles();
        for (int i = 0; i < ps.size(); i++) {
            Vector p = ps.get(i);
            double extra = flat(pos, p) + flat(p, c) - direct;
            if (extra < best) {
                best = extra;
                goal = p.clone();
                goalR = Plaza.puddleRadius(i);
            }
        }
    }

    @Override
    public void tick() {
        if (wet) {
            soak();
            return;
        }
        Player p = nearest(400);
        if (age < popUntil) {
            vel.copy(pop);
            pop.multiply(0.8);
        } else if (goal != null && !small && !burnt) {
            seek(goal.clone().setY(pos.getY()), speed());
        } else follow(speed());
        roll();
        if (age >= nextFluff) {
            if (p != null && dist(p) <= 20) sound("fluff", Sound.BLOCK_WOOL_STEP, 0.8f, 16);
            nextFluff = age + 30 + game.random.nextInt(40);
        }
        if (!small && !burnt && every(5)) puddleCheck();
        if (p != null && age >= readyAt && touches(p, 0.05)) bite(p);
    }

    /** 굴림: 움직이는 쪽을 보고 pitch 를 빠르기만큼 돌린다. */
    private void roll() {
        double v = Math.hypot(vel.getX(), vel.getZ());
        if (v > 1e-4) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        pitch = (float) ((pitch + Math.toDegrees(v / (small ? SMALL_ROLL_R : ROLL_R))) % 360.0);
    }

    private void puddleCheck() {
        List<Vector> ps = puddles();
        for (int i = 0; i < ps.size(); i++) {
            Vector p = ps.get(i);
            double r = Plaza.puddleRadius(i) + 0.2;
            double dx = p.getX() - pos.getX(), dz = p.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= r * r) {
                wet = true;
                wetAt = age;
                goal = null;
                hold();
                state("wet");
                pitch = 0;
                sound("split", Sound.ENTITY_PLAYER_SPLASH, 1.2f, 24);
                game.world.spawnParticle(Particle.SPLASH, new Location(game.world, pos.getX(), pos.getY(), pos.getZ()), 30, 0.5, 0.3, 0.5, 0.1);
                return;
            }
        }
        // 물웅덩이로 가는 길이 사람에게서 너무 멀면(사람이 다가옴) 그냥 사람에게
        if (goal != null) {
            Player p = nearest(400);
            if (p != null && dist(p) < flat(pos, goal)) goal = null;
        }
    }

    /** 젖어 털이 달라붙은 채 잠깐 떨다가 다섯으로 갈라짐. */
    private void soak() {
        hold();
        int t = age - wetAt;
        body.squash(1f + 0.06f * (t % 2), 1f - 0.06f * (t % 2), 1f + 0.06f * (t % 2), 1);
        if (t < WET_T) return;
        wet = false;
        body.squash(1f, 1f, 1f, 2);
        double ratio = sp.hp > 0 ? maxHp / sp.hp : 1;
        double kidHp = SMALL_HP * ratio;
        double a0 = game.random.nextDouble() * Math.PI * 2;
        for (int k = 0; k < KIDS; k++) {
            double a = a0 + Math.PI * 2 * (k + 1) / (KIDS + 1);
            Vector d = new Vector(Math.cos(a), 0, Math.sin(a));
            Vector at = pos.clone().add(d.clone().multiply(0.4));
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + SMALL_HOVER);
            Gabe c;
            nextSmall = true;
            try {
                c = child(sp, at);
            } finally {
                nextSmall = false;
            }
            if (c instanceof G69 g) {
                g.maxHp = kidHp;
                g.hp = kidHp;
                g.noReward = true;
                g.reward = 0;
                g.pop.copy(d.multiply(POP_V));
                g.popUntil = g.age + POP_T;
            }
        }
        // 원래 것도 작은 것이 된다(보상은 그대로)
        small = true;
        radius = SMALL_R;
        hover = SMALL_HOVER;
        baseSpeed = SMALL_SPEED;
        maxHp = kidHp;
        hp = Math.min(hp, kidHp);
        state("small");
        double a = a0;
        pop.copy(new Vector(Math.cos(a), 0, Math.sin(a)).multiply(POP_V));
        popUntil = age + POP_T;
    }

    private void bite(Player p) {
        hit(p, small ? SMALL_BITE : BITE, "cause_g69");
        sound("bite", Sound.ENTITY_FOX_BITE, 1.3f, 12);
        readyAt = age + cd(BITE_CD);
        Vector feet = p.getLocation().toVector();
        int n = game.mobs.near(feet.setY(pos.getY()), CLING_R, g -> g.sp == sp).size();
        if (n >= CLING) game.players.slow(p, SLOW, SLOW_T, "g69");
    }

    @Override
    public boolean damage(Damage d) {
        if (!dead && d.type() == DamageType.FIRE && !burnt) {
            // 털이 탐: 갈라지지 않음(탈색으로 보임)
            burnt = true;
            goal = null;
            body.look.palette |= Look.PAL_PALE;
        }
        return super.damage(d);
    }

    private static double flat(Vector a, Vector b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String toString() {
        return super.toString() + (small ? " small" : "") + (burnt ? " burnt" : "") + (goal != null ? " puddle" : "");
    }
}
