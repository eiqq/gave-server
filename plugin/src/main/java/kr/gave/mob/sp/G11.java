package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Corpse;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.mob.Swarm;

/**
 * G11 시체 뜯는 떼 게이브(떼). 바닥을 덮는 납작한 얼굴 무리(마리마다 디스플레이 하나, G02 닻 방식).
 *
 * <p>도감: 다다닥 튀어 오고 떼 전체가 물결처럼 갈라졌다 합친다. 다른 종 시체에 몰려 고개를 처박고 뜯는다(뒤통수만 보임). 누가 다가오면
 * 모든 얼굴이 같은 틱에 고개를 든다(입가에 분홍 즙). 5블록 안 첫 총소리에 흩어졌다 2초 뒤 모이고, 한 번 흩어지면 6초 동안 다시 안 흩어진다.
 * 발목 물기 1 피해, 셋 이상 붙으면 느려짐. 시체를 다 먹으면 한 마리가 둘로 갈라진다(무리당 최대 수).
 *
 * <p>체력 약속(G02 와 같음): 감독이 넣은 maxHp 를 첫 틱에 '마리 하나 체력'으로 읽고 그 뒤 maxHp, hp 는 무리 합계.
 *
 * <p>팩 계약(tools/gabe/sp/g11.py): 모델 gave:gabe/g11, 마리 상태 "a", "b"(깨끗), "fa", "fb"(먹은 뒤 분홍 즙), 덩어리 "lod".
 * 소리 munch(쩝쩝 쪽, 무리 중심), skitter(다다닥), bite(발목 물림). 원인 글귀 cause_g11.
 */
public final class G11 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int MIN = 18, MAX = 24, CAP = 34;
    static final double SPEED = 0.16, HOVER = 0.12, HOP = 0.1;
    static final float TURN_RATE = 8f;
    /** 대형(납작한 원판 반축: 옆, 앞)과 마리 사이 최소 거리. */
    static final double SPREAD_X = 1.3, SPREAD_Z = 1.6, SPACING = 0.36;
    /** 시체를 찾는 거리, 사람이 이 안이면 시체보다 사람, 고개 드는 거리. */
    static final double CORPSE_R = 24, PEOPLE_FIRST = 6, LIFT_R = 5.5;
    /** 서 있는 사람에게서 이 거리 안에 놓인 시체만 먹는다. 멀리서 잡힌 게이브의 시체가 외곽에 쌓이면 무리가 그것들을 옮겨 다니며
     * 가운데로 안 들어오던 것을 막는다(2026-11-11). */
    static final double MEAL_NEAR = 36;
    /** 시체 하나 먹는 틱, 고개 든 채 멈칫하는 틱. */
    static final int FEED = 60, LIFT = 10;
    /** 시체로 곧장 가는 데 쓰는 최대 틱, 막혀서 버린 뒤 다시 찾기까지 틱. */
    static final int SEEK_MAX = 160, MEAL_AGAIN = 80;
    /** 덮침: 이 거리(수평)부터, 마리 닿음 거리, 사람마다 물림 간격, 느려짐 마리 수. */
    static final double ATTACK_R = 7, TOUCH = 0.7, BITE = 1.5;
    static final int BITE_EVERY = 10, SLOW_COUNT = 3, SLOW_TICKS = 8;
    /** 흩어짐: 총소리 거리, 흩어진 채 있는 틱, 다시 흩어지기까지(흩어진 순간부터). */
    static final double SHOT_R = 5;
    static final int SCATTER = 40, SCATTER_CD = 120;
    /** 물결: 갈라지는 간격, 갈라진 채 틱, 옆으로 벌어지는 폭. */
    static final int RIPPLE_EVERY = 90, RIPPLE = 22;
    static final double RIPPLE_W = 0.9;
    static final double SPLASH = 3.0;

    enum Phase { CRUISE, SEEK, FEED, LIFT, ATTACK, SCATTER }

    private Swarm swarm;
    private Phase phase = Phase.CRUISE;
    private int phaseAt, nextSound, nextRipple, scatterReady, rippleUntil = -1;
    private boolean init, rippled, regather;
    private double each = 1;
    /** 물기 판정용 산 마리 자리(x, y, z 차례, 틱마다 다시 채움: 사람마다 Vector 를 새로 만들지 않게). */
    private double[] mp = new double[0];
    private float heading;
    private Player prey;
    private Corpse meal;
    /** 시체로 가다 막히거나(한가운데 상자 등) 너무 오래 걸리면 그 시체를 버리고 이 틱까지는 시체를 찾지 않는다(2026-11-12). */
    private int noMealUntil;
    private final Map<UUID, Integer> shots = new HashMap<>();
    private final Map<UUID, Long> bitAt = new HashMap<>();

    public G11(Game game, Species sp) {
        super(game, sp);
        radius = 1.0;
        noPush = true;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        int n = MIN + game.random.nextInt(MAX - MIN + 1);
        swarm = game.mobs.swarm(this, "gabe/g11", n);
        swarm.memberRadius = 0.17;
        for (Swarm.Member m : swarm.members) {
            m.home.copy(slot(m.index));
            m.state(m.index % 2 == 0 ? "a" : "b");
        }
        heading = yaw;
        swarm.formation(heading, 0);
    }

    /** 대형 자리: 바닥에 깔린 납작한 타원 안에서 다른 마리와 SPACING 넘게 떨어진 점. */
    private Vector slot(int i) {
        Vector best = new Vector();
        for (int tries = 0; tries < 200; tries++) {
            double x = (game.random.nextDouble() * 2 - 1) * SPREAD_X, z = (game.random.nextDouble() * 2 - 1) * SPREAD_Z;
            if (x * x / (SPREAD_X * SPREAD_X) + z * z / (SPREAD_Z * SPREAD_Z) > 1) continue;
            best.setX(x).setY(0).setZ(z);
            boolean ok = true;
            for (int j = 0; j < i && ok; j++) if (swarm.members.get(j).home.distanceSquared(best) < SPACING * SPACING) ok = false;
            if (ok) break;
        }
        return best;
    }

    private void init() {
        init = true;
        each = Math.max(1, maxHp);
        for (Swarm.Member m : swarm.members) m.hp = each;
        maxHp = hp = each * swarm.members.size();
        nextSound = age + game.random.nextInt(30);
        nextRipple = age + RIPPLE_EVERY / 2 + game.random.nextInt(RIPPLE_EVERY);
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
        if (prey != null && !standing(prey)) prey(null);
        if (phase != Phase.SCATTER && gunshot()) {
            scatter();
            return;
        }
        switch (phase) {
            case CRUISE -> cruise();
            case SEEK -> seek();
            case FEED -> feed();
            case LIFT -> lift();
            case ATTACK -> attack();
            case SCATTER -> scattered();
        }
    }

    /** 표적을 정함(실눈 문법: 찍으면 모든 얼굴이 눈을 뜨고, 풀면 감는다). */
    private void prey(Player p) {
        if (p == prey && (p != null) == (target != null)) return;
        prey = p;
        mark(p);
        boolean on = p != null;
        for (Swarm.Member m : swarm.members) if (!m.dead) m.look.open = on;
    }

    /** 사람 쪽으로 다다닥(뛰듯 튀며), 한 곡선으로 휨, 가끔 물결처럼 갈라졌다 합침. 시체가 있고 사람이 멀면 시체로. */
    private void cruise() {
        hop();
        follow(speed());
        steer();
        ripple();
        sounds("skitter", 26);
        Player p = nearest(ATTACK_R + 2);
        if (p != null && flat(p) <= ATTACK_R) {
            attackOn(p);
            return;
        }
        if (every(10) && age >= noMealUntil && nearest(PEOPLE_FIRST) == null) {
            Corpse c = findMeal();
            if (c != null) {
                meal = c;
                phase(Phase.SEEK);
            }
        }
    }

    /** 시체 쪽으로 곧장. */
    private void seek() {
        hop();
        if (rippled) {
            rippled = false;
            swarm.formation(heading, 10);
        }
        if (meal == null || meal.gone()) {
            meal = null;
            phase(Phase.CRUISE);
            return;
        }
        Player p = nearest(PEOPLE_FIRST);
        if (p != null) {
            attackOn(p);
            return;
        }
        Vector to = meal.pos.clone();
        double d = Math.hypot(to.getX() - pos.getX(), to.getZ() - pos.getZ());
        if (d <= 0.9) {
            hold();
            startFeed();
            return;
        }
        Vector v = to.subtract(pos).setY(0);
        // 곧장 가는 길이 막혔거나(한가운데 보급 상자 등) 너무 오래 걸리면 그 시체를 버리고 다시 사람 쪽으로
        if (inPhase() >= SEEK_MAX || (d > 1.5 && wallAhead(Math.min(1.2, d)))) {
            meal = null;
            noMealUntil = age + MEAL_AGAIN;
            phase(Phase.CRUISE);
            return;
        }
        vel.copy(v.multiply(Math.min(speed(), d) / d));
        steer();
        sounds("skitter", 26);
    }

    private void startFeed() {
        hover = HOVER;
        Vector c = meal.pos;
        for (Swarm.Member m : swarm.members) {
            if (m.dead) continue;
            double a = game.random.nextDouble() * Math.PI * 2, r = 0.25 + game.random.nextDouble() * 0.75;
            Vector at = new Vector(c.getX() + Math.cos(a) * r, pos.getY(), c.getZ() + Math.sin(a) * r);
            m.moveTo(at.clone().subtract(pos), 6 + m.index % 5);
            // 시체 쪽으로 고개를 처박음(뒤통수만 보임)
            float y = (float) Math.toDegrees(Math.atan2(-(c.getX() - at.getX()), c.getZ() - at.getZ()));
            m.face(y, 58f + game.random.nextFloat() * 14f, (game.random.nextFloat() - 0.5f) * 20f, 6);
        }
        phase(Phase.FEED);
    }

    /** 뜯어 먹음: 쩝쩝 쪽. 다 먹으면 한 마리가 둘로 갈라짐. 사람이 다가오면 같은 틱에 고개를 듦. */
    private void feed() {
        hold();
        Player p = nearest(LIFT_R);
        if (p != null) {
            // 먹다 들킨 얼굴: 한참 뜯던 중이면 입가에 분홍 즙을 묻힌 채 고개를 든다
            if (inPhase() >= 10) fed();
            liftNow(p);
            return;
        }
        if (meal == null || meal.gone()) {
            regroup();
            return;
        }
        sounds("munch", 18);
        if (inPhase() >= FEED) {
            game.mobs.eat(meal);
            meal = null;
            fed();
            splitOne();
            regroup();
        }
    }

    /** 먹은 얼굴들: 분홍 즙. */
    private void fed() {
        for (Swarm.Member m : swarm.members) if (!m.dead && !m.stateName().startsWith("f")) m.state("f" + m.stateName());
    }

    /** 한 마리가 둘로(무리 최대 수, 디스플레이 예산 안에서만). */
    private void splitOne() {
        if (swarm.alive() >= CAP || !game.mobs.budgetFor(1)) return;
        Swarm.Member src = swarm.nearest(null);
        if (src == null) return;
        Vector home = slot(swarm.members.size());
        Swarm.Member n = swarm.add(home, src.stateName());
        n.hp = each;
        maxHp += each;
        hp += each;
        if (!game.director.silent())
            kr.gave.Props.soundAround(n.pos().toLocation(game.world), "gabe.split", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 1.1f, 1.4f, 16, -1);
    }

    /** 모든 얼굴이 같은 틱에 고개를 듦(1틱 보간), 잠깐 멈칫한 뒤 덮침. */
    private void liftNow(Player p) {
        prey(p);
        Vector c = chest(p);
        for (Swarm.Member m : swarm.members) if (!m.dead) m.lookAt(c, 1);
        phase(Phase.LIFT);
    }

    private void lift() {
        hold();
        if (prey == null) {
            regroup();
            return;
        }
        if (inPhase() >= LIFT) attackOn(prey);
    }

    private void attackOn(Player p) {
        prey(p);
        meal = null;
        phase(Phase.ATTACK);
    }

    /** 덮침: 무리가 발목으로 몰려 마리마다 문다(사람마다 0.5초에 한 번 1 피해), 셋 이상 붙으면 느려짐. */
    private void attack() {
        hop();
        if (prey == null) {
            regroup();
            return;
        }
        Location fl = prey.getLocation();
        Vector feet = new Vector(fl.getX(), pos.getY(), fl.getZ());
        double d = flat(prey);
        if (d > ATTACK_R + 4) {
            // 멀어진 먹잇감은 놓고 다시 흐름장으로(직선으로 끝없이 쫓지 않음, 매 틱 대형을 다시 보내지 않음)
            prey(null);
            regroup();
            return;
        }
        Vector v = feet.clone().subtract(pos).setY(0);
        double l = v.length();
        vel.copy(l < 0.4 ? new Vector() : v.multiply(Math.min(speed() * 1.2, l) / l));
        // 쫓는 동안은 무리 방향을 가는 쪽으로(발목에 붙은 뒤에는 마리마다 따로 본다)
        if (d >= 2.4) steer();
        sounds("skitter", 20);
        if (d < 2.4 && every(8)) {
            // 마리들이 발목 둘레로(조금씩 다른 틱)
            for (Swarm.Member m : swarm.members) {
                if (m.dead) continue;
                double a = game.random.nextDouble() * Math.PI * 2, r = 0.2 + game.random.nextDouble() * 0.9;
                Vector at = new Vector(feet.getX() + Math.cos(a) * r, pos.getY(), feet.getZ() + Math.sin(a) * r);
                m.moveTo(at.subtract(pos), 4 + m.index % 4);
                m.lookAt(chest(prey), 3);
            }
        } else if (d >= 2.4 && every(20)) {
            swarm.formation(heading, 8);
        }
        bite();
    }

    private void bite() {
        int n = fill();
        if (n == 0) return;
        long now = game.now();
        double ax = pos.getX(), az = pos.getZ();
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double px = l.getX(), py = l.getY(), pz = l.getZ();
            // 마리는 닻에서 많아야 4.5블록 안이다
            if (Math.abs(px - ax) > 6 || Math.abs(pz - az) > 6) continue;
            int c = 0;
            for (int i = 0; i < n; i++) {
                int k = i * 3;
                if (Math.abs(mp[k + 1] - py) > 0.8) continue;
                double dx = mp[k] - px, dz = mp[k + 2] - pz;
                if (dx * dx + dz * dz <= TOUCH * TOUCH) c++;
            }
            if (c == 0) continue;
            // 느려짐은 짧게 이어 걸어서 무리가 죽거나 떨어지면 바로 풀린다
            if (c >= SLOW_COUNT) game.players.slow(p, 0.3, SLOW_TICKS, "g11");
            Long last = bitAt.get(p.getUniqueId());
            if (last != null && now - last < cd(BITE_EVERY)) continue;
            bitAt.put(p.getUniqueId(), now);
            hit(p, BITE, "cause_g11");
            sound("bite", Sound.ENTITY_SILVERFISH_HURT, 1.4f, 12);
        }
    }

    /** 산 마리 자리를 mp 에 채우고 그 수를 돌려줌. */
    private int fill() {
        int need = swarm.members.size() * 3;
        if (mp.length < need) mp = new double[need + 12];
        int n = 0;
        double ox = pos.getX(), oy = pos.getY(), oz = pos.getZ();
        for (Swarm.Member m : swarm.members) {
            if (m.dead) continue;
            Vector o = m.off();
            mp[n * 3] = ox + o.getX();
            mp[n * 3 + 1] = oy + o.getY();
            mp[n * 3 + 2] = oz + o.getZ();
            n++;
        }
        return n;
    }

    /** 5블록 안 첫 총소리: 흩어졌다 2초 뒤 모임(흩어진 순간부터 6초는 다시 안 흩어짐). */
    private boolean gunshot() {
        boolean heard = false;
        Vector c = swarm.centroid();
        for (Player p : game.mobs.standing()) {
            var arms = game.guns.arms(p);
            if (arms == null) continue;
            int s = arms.shots;
            Integer prev = shots.put(p.getUniqueId(), s);
            if (prev == null || s <= prev) continue;
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - c.getX(), l.getZ() - c.getZ()) <= SHOT_R) heard = true;
        }
        return heard && age >= scatterReady;
    }

    private void scatter() {
        scatterReady = age + SCATTER_CD;
        hold();
        for (Swarm.Member m : swarm.members) {
            if (m.dead) continue;
            Vector o = m.off().setY(0);
            double a = o.lengthSquared() > 1e-4 ? Math.atan2(o.getZ(), o.getX()) : game.random.nextDouble() * Math.PI * 2;
            a += (game.random.nextDouble() - 0.5) * 1.2;
            double r = 2.5 + game.random.nextDouble() * 1.8;
            Vector to = new Vector(Math.cos(a) * r, 0, Math.sin(a) * r);
            // 벽 안으로는 안 흩어짐
            if (!game.mobs.flow.walkable(pos.getX() + to.getX(), pos.getZ() + to.getZ())) to.multiply(0.4);
            m.moveTo(to, 5 + m.index % 3);
            m.face((float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ())), 0, 0, 3);
        }
        sound("skitter", Sound.ENTITY_SILVERFISH_STEP, 1.6f, 16);
        regather = true;
        rippled = false;
        phase(Phase.SCATTER);
    }

    private void scattered() {
        hold();
        if (regather && inPhase() >= SCATTER) {
            regather = false;
            swarm.formation(heading, 12);
        }
        if (!regather && inPhase() >= SCATTER + 12) {
            Player p = prey != null ? prey : nearest(ATTACK_R);
            if (p != null) attackOn(p);
            else phase(Phase.CRUISE);
        }
    }

    private void regroup() {
        swarm.formation(heading, 10);
        hover = HOVER;
        rippled = false;
        if (prey == null) prey(null);
        phase(prey != null ? Phase.ATTACK : Phase.CRUISE);
    }

    // ------------------------------------------------------------------ 도우미

    /** 다다닥: 닻 높이를 빠르게 들썩(마리 모두 한 이동 패킷으로). */
    private void hop() {
        hover = HOVER + Math.abs(Math.sin((age + id) * 0.55)) * HOP;
    }

    /** 무리 방향을 속도 쪽으로 한 틱 TURN_RATE 까지 틀고, 15도 넘게 꺾이면 모든 얼굴이 같은 틱에 휙. */
    private void steer() {
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-6) {
            float want = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
            float d = wrap(want - heading);
            heading += Math.max(-TURN_RATE, Math.min(TURN_RATE, d));
        }
        yaw = heading;
        if (age >= rippleUntil) swarm.heading(heading);
    }

    /** 물결: 떼가 둘로 갈라졌다(옆으로 벌어짐) 다시 합침. */
    private void ripple() {
        if (rippled && age >= rippleUntil) {
            rippled = false;
            swarm.formation(heading, 10);
        }
        if (age < nextRipple) return;
        nextRipple = age + RIPPLE_EVERY + game.random.nextInt(40);
        rippleUntil = age + RIPPLE;
        rippled = true;
        for (Swarm.Member m : swarm.members) {
            if (m.dead) continue;
            double side = m.home.getX() >= 0 ? 1 : -1;
            Vector h = m.home.clone().add(new Vector(side * RIPPLE_W, 0, 0));
            m.moveTo(Swarm.rotate(h, heading), 8);
        }
    }

    private Corpse findMeal() {
        Corpse best = null;
        double bd = CORPSE_R * CORPSE_R;
        for (Corpse c : game.mobs.corpses()) {
            if (c.gone() || c.sp == sp) continue;
            double dx = c.pos.getX() - pos.getX(), dz = c.pos.getZ() - pos.getZ();
            double d = dx * dx + dz * dz;
            if (d < bd && game.mobs.flow.walkable(c.pos.getX(), c.pos.getZ()) && game.mobs.nearestPlayer(c.pos, MEAL_NEAR) != null) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    private void sounds(String name, int every) {
        if (age < nextSound) return;
        nextSound = age + every + game.random.nextInt(8);
        Sound fb = name.equals("munch") ? Sound.ENTITY_GENERIC_EAT : Sound.ENTITY_SILVERFISH_STEP;
        sound(name, fb, 1.3f, name.equals("munch") ? 20 : 16);
    }

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
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
    public Vector head() {
        return swarm.centroid();
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
        if (t != DamageType.BITE) swarm.flinch();
        if (swarm.alive() == 0) die(d);
        return true;
    }

    private void hurt(Swarm.Member m, double amount) {
        m.hp -= amount;
        m.look.hurtUntil = game.worldTime() + 3;
        if (m.hp <= 0) swarm.kill(m);
    }

    public String phaseName() {
        return phase.name() + " alive=" + swarm.alive();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " alive=" + (swarm == null ? 0 : swarm.alive()) + (meal != null ? " meal" : "");
    }
}
