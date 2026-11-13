package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G25 셋 목말 게이브(돌격).
 *
 * <p>도감: 0.7 블록 얼굴 셋이 막대기로 목말을 태운 사람 키 세로 탑. 비틀비틀 걸어오며 '퉁, 퉁, 휙' 박자, 3박자째 대상이 닿는 거리면 탑이
 * 앞으로 쓰러지며 꼭대기(이빨 고리 입)가 머리를 문다(2초 지속, 합 4 피해, 덮기 아님). 쓰러진 뒤 아래 둘이 버둥거리며 2초에 걸쳐 다시 쌓는다.
 * 맞서기: '휙'에 비킨다. 꼭대기가 약점(아래 둘은 피해 35%, 쓰러진 동안 꼭대기 1.5배). 꼭대기를 잡으면(체력 0) 남은 둘이 흰 실뭉치(G26,
 * 보상 0)를 토해 내고 멈춘다.
 *
 * <p>쓰러짐: 꼭대기는 밑동을 축으로 반지름 1.81 의 호를 그린다. '휙' 순간 대상 머리가 그 호에 닿는 각(코앞이면 26도, 2.4 블록이면 56도)
 * 까지 쓰러져 머리에 걸친 채 문다. 그사이 비켰으면 끝까지(74도) 엎어져 잠깐 누워 있는다.
 *
 * <p>몸: 닻 {@code gave:gabe/g25}(아래 얼굴 둘, 상태 {@code ""} 걷기, {@code "flail"} 다시 쌓기) + 부품 {@code gave:gabe/g25_top}
 * (꼭대기, 상태 {@code ""}, {@code "bite"}). 쓰러짐은 몸 pitch 를 밑동 축으로(몸 offset 으로 축을 옮김). 소리 beat(퉁), whoosh(휙),
 * bite, spit. 원인 글귀 {@code cause_g25}.
 */
public final class G25 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.1;
    /** 한 박자 틱. 셋째 박자에 쓰러짐. */
    static final int BEAT = 12;
    /** 셋째 박자에 이 수평 거리 안이면 쓰러져 문다(탑 높이 1.81 x sin 쓰러짐각 + 꼭대기 반). */
    static final double STRIKE_RANGE = 2.4;
    /** 다 엎어진 각, 머리에 걸치는 가장 작은 각(도), 쓰러지는 틱, 물기 틱(2초, 10틱마다 1 = 합 4), 놓친 뒤 누워 있는 틱, 다시 쌓는 틱(2초). */
    static final float FALL_DEG = 74f, LEAN_MIN = 20f;
    static final int FALL_T = 6, BITE_T = 40, BITE_EVERY = 10, DOWN_T = 20, RESTACK_T = 40;
    static final double BITE_DMG = 2.0, BITE_REACH = 1.25, BITE_KEEP = 1.9;
    /** 탑 가운데에서 밑동까지(= hover), 꼭대기 얼굴 높이(가운데 기준), 아래 얼굴들 높이, 사람 눈 높이. */
    static final double HALF = 1.05, TOP_UP = 0.76, LOW_UP = -0.7, EYE = 1.62;
    static final double FACE_R = 0.38;
    /** 아래 둘이 받는 피해 비율, 쓰러진 동안 꼭대기 배율. */
    static final double LOWER_MUL = 0.35, DOWN_TOP_MUL = 1.5;

    enum Phase { WALK, FALL, BITE, DOWN, RESTACK }

    private Phase phase = Phase.WALK;
    private int phaseAt, beatN, nextBeat;
    private Body.Part top;
    private Player prey;
    private float tilt, fallYaw, leanTo, restackFrom;

    public G25(Game game, Species sp) {
        super(game, sp);
        radius = 0.42;
        hover = HALF;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g25", 1.8f, 2.6f);
        top = body.attach("gabe/g25_top");
        if (top != null) top.pose(new Matrix4f().translate(0, (float) TOP_UP, 0), 0);
    }

    @Override
    protected void spawned() {
        nextBeat = age + BEAT + game.random.nextInt(BEAT);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case WALK -> walk();
            case FALL -> fall();
            case BITE -> bite();
            case DOWN -> down();
            case RESTACK -> restack();
        }
    }

    /** 비틀비틀 걷기: 박자 앞쪽 반에만 쏠려 나아감. 셋째 박자에 닿으면 쓰러짐. */
    private void walk() {
        state("");
        Player p = nearest(40);
        int t = (age - nextBeat + BEAT * 100) % BEAT;
        double lurch = t < BEAT / 2 ? 1.5 : 0.35;
        follow(speed() * lurch);
        if (p != null) face(chest(p));
        pitch = 0;
        // 위가 무거운 탑의 흔들림: 꼭대기가 좌우로 늦게 따라옴
        if (top != null && every(4)) {
            float sway = (float) Math.sin((age + id * 11) * 0.26) * 0.09f;
            top.pose(new Matrix4f().translate(sway, (float) TOP_UP, 0).rotateZ(-sway * 1.6f), 4);
        }
        if (p != null && dist(p) < 10) {
            if (prey != p) eye(p);
            prey = p;
        } else if (prey != null) {
            eye(null);
            prey = null;
        }
        if (age < nextBeat) return;
        nextBeat = age + BEAT;
        beatN = beatN % 3 + 1;
        double flat = prey == null ? 99 : flatTo(prey);
        if (beatN == 3 && prey != null && flat <= STRIKE_RANGE && sees(prey)) {
            sound("whoosh", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 20);
            fallYaw = yaw;
            hold();
            // 꼭대기가 그리는 호(밑동 축, 반지름 HALF + TOP_UP)에서 대상 눈에 가장 가까운 각까지 쓰러진다
            double head = prey.getLocation().getY() + EYE - (pos.getY() - HALF);
            leanTo = (float) Math.max(LEAN_MIN, Math.min(FALL_DEG, Math.toDegrees(Math.atan2(flat, Math.max(0.2, head)))));
            setTilt(leanTo * 0.5f, 3);
            phase(Phase.FALL);
            return;
        }
        sound("beat", Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 20);
        body.squash(1.05f, 0.94f, 1.05f, 2);
    }

    /** 앞으로 쓰러짐(밑동 축): 두 번에 나눠 보냄(보간이 직선이라 호를 흉내). 끝에서 꼭대기가 머리 둘레에 닿은 사람을 문다. */
    private void fall() {
        hold();
        yaw = fallYaw;
        int t = inPhase();
        if (t == 3) setTilt(leanTo, 3);
        if (t < FALL_T) return;
        body.squash(1f, 1f, 1f, 2);
        Player victim = null;
        double best = BITE_REACH;
        Vector tp = topPos();
        for (Player q : game.mobs.standing()) {
            Location e = q.getEyeLocation();
            double dx = e.getX() - tp.getX(), dy = e.getY() - tp.getY(), dz = e.getZ() - tp.getZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < best) {
                best = d;
                victim = q;
            }
        }
        if (victim != null) {
            prey = victim;
            eye(victim);
            if (top != null) top.state("bite");
            sound("bite", Sound.ENTITY_RAVAGER_ATTACK, 1.4f, 16);
            hit(victim, BITE_DMG, "cause_g25");
            phase(Phase.BITE);
        } else {
            // 비켰다: 끝까지 엎어져 잠깐 누워 있음(집중 사격 때)
            if (tilt < FALL_DEG) setTilt(FALL_DEG, 3);
            sound("beat", Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 20);
            phase(Phase.DOWN);
        }
    }

    /** 2초 물고 늘어짐(10틱마다 1 피해). 멀어지면 놓침. */
    private void bite() {
        hold();
        yaw = fallYaw;
        int t = inPhase();
        if (!standing(prey) || prey.getEyeLocation().toVector().distance(topPos()) > BITE_KEEP) {
            endBite();
            return;
        }
        if (t > 0 && t % cd(BITE_EVERY) == 0) {
            hit(prey, BITE_DMG, "cause_g25");
            sound("bite", Sound.ENTITY_RAVAGER_ATTACK, 1.4f, 16);
            if (top != null) top.pose(new Matrix4f().translate(0, (float) TOP_UP + 0.05f, 0.06f), 2);
        } else if (t % cd(BITE_EVERY) == 3 && top != null) top.pose(new Matrix4f().translate(0, (float) TOP_UP, 0), 3);
        if (t >= BITE_T - cd(BITE_EVERY) / 2) endBite();
    }

    private void endBite() {
        if (top != null) {
            top.state("");
            top.pose(new Matrix4f().translate(0, (float) TOP_UP, 0), 3);
        }
        startRestack();
    }

    /** 놓침: 잠깐 누워 있음(집중 사격 때). */
    private void down() {
        hold();
        yaw = fallYaw;
        if (inPhase() >= DOWN_T) startRestack();
    }

    private void startRestack() {
        restackFrom = tilt;
        phase(Phase.RESTACK);
    }

    /** 아래 둘이 버둥거리며 다시 쌓음(2초, 흔들리며 일어남). */
    private void restack() {
        hold();
        yaw = fallYaw;
        state("flail");
        int t = inPhase();
        if (t % 5 == 0) {
            float k = 1f - Math.min(1f, (t + 5) / (float) cd(RESTACK_T));
            float jit = (float) (game.random.nextGaussian() * 6);
            setTilt(Math.max(0f, restackFrom * k + jit * k), 5);
        }
        if (t >= cd(RESTACK_T)) {
            setTilt(0f, 4);
            state("");
            beatN = 0;
            nextBeat = age + BEAT;
            phase(Phase.WALK);
        }
    }

    /** 찍은 사람: 실눈을 뜨는 얼굴은 꼭대기(부품)뿐이라 부품 겉모습도 같이 바꾼다. */
    private void eye(Player p) {
        mark(p);
        if (top != null) top.look.open = p != null;
    }

    /** 밑동을 축으로 앞으로 deg 기울임(몸 pitch + offset), ticks 보간. */
    private void setTilt(float deg, int ticks) {
        tilt = deg;
        pitch = deg;
        double r = Math.toRadians(deg), y = Math.toRadians(yaw);
        // 몸 가운데를 축으로 돈 만큼 되돌려 밑동이 제자리: 앞쪽으로 HALF sin, 아래로 HALF (1 - cos)
        double fwd = HALF * Math.sin(r), down = HALF * (1 - Math.cos(r));
        body.offset.set((float) (-Math.sin(y) * fwd), (float) -down, (float) (Math.cos(y) * fwd));
        body.pose(ticks);
    }

    /** 몸 기준 점(x 옆, y 위, z 앞)의 세계 자리(지금 기울기, 방향, offset 포함). */
    private Vector world(double x, double y, double z) {
        Vector v = new Vector(x, y, z).rotateAroundX(Math.toRadians(tilt)).rotateAroundY(Math.toRadians(-yaw));
        return pos.clone().add(new Vector(body.offset.x, body.offset.y, body.offset.z)).add(v);
    }

    private Vector topPos() {
        return world(0, TOP_UP, 0);
    }

    private double flatTo(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        Vector t = topPos(), m = world(0, 0, 0), b = world(0, LOW_UP, 0);
        out.add(new double[] {t.getX(), t.getY(), t.getZ(), FACE_R});
        out.add(new double[] {m.getX(), m.getY(), m.getZ(), FACE_R});
        out.add(new double[] {b.getX(), b.getY(), b.getZ(), FACE_R});
        return out;
    }

    @Override
    public Vector head() {
        return topPos();
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        double k = 1.0;
        DamageType ty = d.type();
        if (d.at() != null && ty != DamageType.EXPLOSION && ty != DamageType.FIRE && ty != DamageType.BITE) {
            Vector at = d.at();
            double dt = at.distanceSquared(topPos());
            double dm = Math.min(at.distanceSquared(world(0, 0, 0)), at.distanceSquared(world(0, LOW_UP, 0)));
            boolean down = phase == Phase.BITE || phase == Phase.DOWN || phase == Phase.FALL;
            k = dt <= dm ? (down ? DOWN_TOP_MUL : 1.0) : LOWER_MUL;
        }
        double amt = d.amount() * k;
        double before = hp;
        hp -= amt;
        game.mobs.onHurt(this, d, Math.min(before, amt));
        if (hp <= 0) die(d);
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 꼭대기를 잡힘: 남은 둘이 흰 실뭉치를 토해 내고 멈춤(실뭉치는 보상 0)
        if (!cleanup(d)) {
            sound("spit", Sound.BLOCK_HONEY_BLOCK_BREAK, 0.7f, 16);
            G26.skein(game, world(0, LOW_UP, 0.5));
        }
        super.die(d);
    }

    /** 감독의 웨이브 끝 정리, 시험 몹죽이기(OTHER, 쏜 사람 없음): 죽음 효과 없이 치움. */
    static boolean cleanup(Damage d) {
        return d == null || d.type() == DamageType.OTHER && d.by() == null;
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " beat=" + beatN + " tilt=" + Math.round(tilt);
    }
}
