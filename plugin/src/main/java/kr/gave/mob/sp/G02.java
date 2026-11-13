package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.mob.Swarm;

/**
 * G02 새끼 가오리 떼. 본보기 종 2: 무리 닻 하나에 마리 16~24 를 태운 떼 종(다른 떼 종 담당이 이 꼴을 따라 쓴다).
 *
 * <p>도감: 물고기 떼처럼 한 곡선으로 휘고, 꺾을 때 모든 얼굴이 같은 틱에 휙 돌아본다. 8블록 앞에서 일제히 멈춰 같은 쪽으로 갸웃하고 1초 뒤
 * 흩어져 덮친다. 맞으면 무리 전체가 같이 움찔한다(0.5초에 한 번). 한 마리 1 피해, 한 마리에 닿은 뒤 0.5초 동안 같은 무리 피해는 무시.
 * 한 발에 한 마리씩 죽고, 무리 합계 보상은 마지막 마리가 죽을 때(엔진이 피해 기여로 나눔). 15블록 밖은 덩어리 모델(LOD).
 *
 * <p>체력 약속: 감독이 놓은 뒤 넣는 maxHp(= sp.hp x 웨이브 배율)를 첫 틱에 '마리 하나 체력'으로 읽고, 그 뒤 maxHp, hp 는 무리 합계다.
 *
 * <p>팩 계약(tools/gabe/sp/g02.py): 모델 {@code gave:gabe/g02}, 마리 상태 {@code "a"}, {@code "b"}, {@code "c"}(실눈 각도 셋),
 * 덩어리 상태 {@code "lod"}(무리 하나 크기, 닻 가운데 기준). 소리 {@code gave:gabe.g02.giggle}(킥킥 파도, 무리 중심),
 * {@code gave:gabe.g02.flutter}(덮칠 때 파닥). 원인 글귀 {@code cause_g02}.
 */
public final class G02 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int MIN = 16, MAX = 24;
    /** 떼 빠르기(블록/틱), 한 틱에 꺾는 각(도, 한 곡선으로 휨). */
    static final double SPEED = 0.14;
    static final float TURN_RATE = 7f;
    /** 이 블록 안에서 멈춰 갸웃. */
    static final double HALT_RANGE = 8;
    /** 갸웃 틱, 덮침 틱, 다시 모이는 틱, 다음 덮침까지 쉬는 틱. */
    static final int HALT = 20, POUNCE = 16, REGROUP = 12, COOLDOWN = 50;
    /** 갸웃 기울기(도). */
    static final float TILT = 26f;
    /** 마리 하나 피해, 같은 무리 피해를 무시하는 틱, 닿음 거리(사람 몸통에서). */
    static final double MEMBER_DMG = 1.5, TOUCH = 0.75;
    static final int PLAYER_INVULN = 6;
    /** 이 웨이브부터 나온 무리는 강화판: 같은 무리 피해 무적 시간 없이 겹쳐 맞음(도감). */
    static final int ENHANCED_WAVE = 13;
    /** 폭발, 불에 함께 죽는 반경. */
    static final double SPLASH = 3.0;
    /** 대형(몸 기준 반축: 옆, 위, 앞)과 마리 사이 최소 거리. */
    static final double SCHOOL_X = 1.1, SCHOOL_Y = 0.4, SCHOOL_Z = 1.6, SPACING = 0.42;

    enum Phase { CRUISE, HALT, POUNCE, REGROUP }

    private Swarm swarm;
    private Phase phase = Phase.CRUISE;
    private int phaseAt, readyAt, nextGiggle;
    private boolean init;
    /** 강화판(13웨이브부터 놓인 무리): 사람마다 무적 시간 없음. */
    boolean enhanced;
    private Player prey;
    private float heading, tilt;
    private final Map<UUID, Long> bitAt = new HashMap<>();
    /** 강화판: 이번 덮침에 이미 문 마리. */
    private boolean[] memberBit = new boolean[0];

    public G02(Game game, Species sp) {
        super(game, sp);
        radius = 1.0; // 무리 몸통(밀어내기, 물어뜯기). 탄은 마리 구로만 맞음
        noPush = true; // 다른 몹, 사람 몸과 겹쳐도 됨
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        int n = MIN + game.random.nextInt(MAX - MIN + 1);
        swarm = game.mobs.swarm(this, "gabe/g02", n);
        String[] faces = {"a", "b", "c"};
        for (Swarm.Member m : swarm.members) {
            m.home.copy(slot(m.index));
            m.state(faces[m.index % 3]);
        }
        heading = yaw;
        swarm.formation(heading, 0);
    }

    /** 대형 자리 하나: 납작한 타원체 안에서 다른 마리와 SPACING 넘게 떨어진 점. */
    private Vector slot(int i) {
        Vector best = new Vector();
        for (int tries = 0; tries < 200; tries++) {
            double x = (game.random.nextDouble() * 2 - 1) * SCHOOL_X, y = (game.random.nextDouble() * 2 - 1) * SCHOOL_Y,
                    z = (game.random.nextDouble() * 2 - 1) * SCHOOL_Z;
            if (x * x / (SCHOOL_X * SCHOOL_X) + y * y / (SCHOOL_Y * SCHOOL_Y) + z * z / (SCHOOL_Z * SCHOOL_Z) > 1) continue;
            best.setX(x).setY(y).setZ(z);
            boolean ok = true;
            for (int j = 0; j < i && ok; j++) if (swarm.members.get(j).home.distanceSquared(best) < SPACING * SPACING) ok = false;
            if (ok) break;
        }
        return best;
    }

    private void init() {
        init = true;
        double each = Math.max(1, maxHp);
        for (Swarm.Member m : swarm.members) m.hp = each;
        maxHp = hp = each * swarm.members.size();
        nextGiggle = age + game.random.nextInt(40);
        enhanced = game.wave >= ENHANCED_WAVE;
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
        if (!init) init();
        if (prey != null && !standing(prey)) prey = null;
        switch (phase) {
            case CRUISE -> cruise();
            case HALT -> halt();
            case POUNCE -> pounce();
            case REGROUP -> regroup();
        }
        if (age >= nextGiggle) {
            sound("giggle", Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1.6f, 24);
            nextGiggle = age + 50 + game.random.nextInt(30);
        }
    }

    /** 흐름장 쪽으로 방향을 조금씩 틀며(한 곡선) 흐름. 15도 넘게 꺾이면 모든 얼굴이 같은 틱에 휙. */
    private void cruise() {
        follow(speed());
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-6) {
            float want = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
            float d = wrap(want - heading);
            heading += Math.max(-TURN_RATE, Math.min(TURN_RATE, d));
        }
        double r = Math.toRadians(heading);
        vel.setX(-Math.sin(r) * speed()).setZ(Math.cos(r) * speed());
        yaw = heading;
        swarm.heading(heading);
        Player p = nearest(HALT_RANGE + 2);
        if (p != null && age >= readyAt && flat(p) <= HALT_RANGE && sees(p)) {
            prey = p;
            mark(p);
            hold();
            // 일제히 멈춰 같은 쪽으로 갸웃(같은 틱)
            tilt = game.random.nextBoolean() ? TILT : -TILT;
            Vector c = chest(p);
            for (Swarm.Member m : swarm.members) {
                if (m.dead) continue;
                m.lookAt(c, 2);
                m.face(m.yaw, m.pitch, tilt, 2);
            }
            phase(Phase.HALT);
        }
    }

    /** 1초 멈춤(밀집, 쓸어 담을 순간). */
    private void halt() {
        hold();
        if (prey == null) {
            regroupNow();
            return;
        }
        face(chest(prey));
        if (inPhase() >= cd(HALT)) {
            sound("flutter", Sound.ENTITY_PHANTOM_FLAP, 1.8f, 16);
            scatter(prey, true);
            phase(Phase.POUNCE);
        }
    }

    /** 흩어져 덮침: 마리마다 사람 몸통 둘레 한 점으로(조금씩 다른 틱). 중간에 한 번 다시 겨눔. */
    private void pounce() {
        hold();
        if (prey == null) {
            regroupNow();
            return;
        }
        if (inPhase() == 7) scatter(prey, false);
        long now = game.now();
        for (Player p : game.mobs.standing()) {
            Long last = bitAt.get(p.getUniqueId());
            if (!enhanced && last != null && now - last < PLAYER_INVULN) continue;
            Vector c = chest(p);
            for (Swarm.Member m : swarm.members) {
                if (m.dead || m.pos().distanceSquared(c) > TOUCH * TOUCH) continue;
                // 강화판: 무적 시간 없이 닿은 마리마다 겹쳐 맞음(한 번 덮칠 때 마리마다 한 번)
                if (enhanced) {
                    if (m.index >= memberBit.length || memberBit[m.index]) continue;
                    memberBit[m.index] = true;
                    hit(p, MEMBER_DMG, "cause_g02");
                    continue;
                }
                hit(p, MEMBER_DMG, "cause_g02");
                bitAt.put(p.getUniqueId(), now);
                break;
            }
        }
        if (inPhase() >= POUNCE) regroupNow();
    }

    private void scatter(Player p, boolean first) {
        if (first) memberBit = new boolean[swarm.members.size()];
        Vector c = chest(p);
        for (Swarm.Member m : swarm.members) {
            if (m.dead) continue;
            Vector jitter = new Vector(game.random.nextGaussian() * 0.45, game.random.nextGaussian() * 0.35, game.random.nextGaussian() * 0.45);
            m.moveTo(c.clone().add(jitter).subtract(pos), first ? 5 + m.index % 4 : 4);
            m.lookAt(c, 1);
        }
    }

    private void regroupNow() {
        swarm.formation(heading, 8);
        phase(Phase.REGROUP);
    }

    /** 다시 대형으로 모이며 조금 물러남. */
    private void regroup() {
        if (prey != null) vel.copy(toward(prey).multiply(-speed() * 0.6));
        else hold();
        if (inPhase() >= cd(REGROUP)) {
            mark(null);
            prey = null;
            readyAt = age + cd(COOLDOWN);
            phase(Phase.CRUISE);
        }
    }

    private double flat(Player p) {
        Vector c = chest(p);
        return Math.hypot(c.getX() - pos.getX(), c.getZ() - pos.getZ());
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        return swarm.spheres();
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        double before = hp;
        DamageType t = d.type();
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            for (Swarm.Member m : swarm.within(d.at() != null ? d.at() : pos, SPLASH)) hurt(m, d.amount());
        } else {
            Swarm.Member m = swarm.nearest(d.at());
            if (m != null) hurt(m, d.amount());
        }
        hp = 0;
        for (Swarm.Member m : swarm.members) if (!m.dead) hp += Math.max(0, m.hp);
        game.mobs.onHurt(this, d, before - hp);
        if (t != DamageType.BITE) swarm.flinch(); // 무리 전체가 같이 움찔
        if (swarm.alive() == 0) die(d);
        return true;
    }

    private void hurt(Swarm.Member m, double amount) {
        m.hp -= amount;
        m.look.hurtUntil = game.worldTime() + 3; // 맞은 마리만 번쩍
        if (m.hp <= 0) swarm.kill(m);
    }

    /** 시험용: 지금 단계와 산 마리. */
    public String phaseName() {
        return phase.name() + " alive=" + swarm.alive();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " alive=" + (swarm == null ? 0 : swarm.alive()) + (enhanced ? " enh" : "");
    }
}
