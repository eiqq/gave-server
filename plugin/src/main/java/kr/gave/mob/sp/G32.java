package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G32 웃음 옮는 게이브(방해). 가오리 꼴인데 점 입 자리에 벌집 구멍이 뚫려 있고, 날 때마다 구멍들에서 짧은 들숨 '히이'가 난다.
 *
 * <p>도감: G01 처럼 펄럭이며 오다가 7블록 안에서 표적을 찍고, 박지 않고 옆을 스치며 문다(2 피해). 지나친 뒤 곧장 12블록 안 다음 사람에게
 * (한 번에 넷까지, 방금 문 사람은 건너뜀). 물린 사람은 20초 동안 웃음이 옮는다: 쏘거나 걸을 때마다 그 사람 자리에서 작은 킥킥이 나고
 * (남들에게 위치 소리로 들림), 그 소리가 날 때 12블록 안에서 아직 아무도 찍지 않은 게이브들이 잠깐(0.75초) 그쪽으로 끌려온다. 옮은 사람
 * 화면에는 조준점 옆에 이 종 아이콘이 깜빡인다. 스치는 순간이 가까워 산탄 피해 1.5배.
 *
 * <p>도감과 다른 점: 흐름장 목표 가중치 대신 킥킥이 날 때마다 짧은 끌림(엔진 lure 15틱, 같은 게이브는 2.5초에 한 번, 4블록 안에 이미 온 것은
 * 빼서 공격은 그대로 함). 웃음은 문 개체가 죽으면 바로 풀린다(공통 규칙). 요청: Flow 에 사람별 목표 가중치.
 *
 * <p>팩 계약(tools/gabe/sp/g32.py): 모델 {@code gave:gabe/g32} 상태 {@code ""}, {@code "swoop"}. 소리 inhale(20), bite(16), giggle(24).
 * 글귀 {@code cause_g32}, {@code notice_g32_laugh}.
 */
public final class G32 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.16, HOVER = 1.15;
    /** 이 블록 안, 보이면 찍고 스치기 준비. 준비 틱(처음 / 이어서). */
    static final double SWOOP_RANGE = 7;
    static final int WINDUP = 14, WINDUP_NEXT = 8;
    /** 스치는 빠르기(블록/틱), 최대 틱, 표적 옆으로 비껴 가는 거리, 표적 너머로 더 가는 거리. */
    static final double SWOOP_V = 0.55, PASS_SIDE = 0.7, OVERSHOOT = 3.0;
    static final int SWOOP_MAX = 30;
    /** 무는 거리(몸 반지름 + 사람 몸통에 더), 피해, 옮은 웃음 틱(20초). */
    static final double BITE_REACH = 0.45, DAMAGE = 3;
    static final int INFECT = 400;
    /** 다음 사람을 찾는 거리, 한 번에 잇는 수, 다 돌고 쉬는 틱. */
    static final double NEXT_RANGE = 12;
    static final int CHAIN = 4, COOLDOWN = 40;
    /** 산탄 배율. */
    static final double PELLET_MUL = 1.5;
    /** 킥킥: 발소리로 치는 걸음 거리, 소리 사이 최소 틱(총 / 발), 끌리는 반경, 끌리는 틱, 같은 게이브 다시 끌리기까지, 이미 가까운 것은 뺌. */
    static final double STEP = 2.4, LURE_R = 12, LURE_NEAR = 4;
    static final int GAP_SHOT = 8, GAP_STEP = 14, LURE_T = 15, LURE_AGAIN = 50;

    enum Phase { APPROACH, WINDUP, SWOOP, TURN }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, readyAt, nextInhale, windup = cd(WINDUP), chain;
    private Player prey;
    private final Vector dashDir = new Vector(0, 0, 1);
    private double dashGone, dashLen;
    private final List<UUID> bitten = new ArrayList<>(CHAIN);

    public G32(Game game, Species sp) {
        super(game, sp);
        radius = 0.42;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g32", 3.2f, 1.4f);
    }

    @Override
    protected void spawned() {
        nextInhale = age + game.random.nextInt(30);
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
        Laugh.of(game).tick(game);
        switch (phase) {
            case APPROACH -> approach();
            case WINDUP -> windup();
            case SWOOP -> swoop();
            case TURN -> turn();
        }
    }

    /** 흐름장을 따라 옆으로 흔들리며 옴. 얼굴은 가장 가까운 사람. */
    private void approach() {
        follow(speed());
        double sway = Math.sin((age + id * 13) * 0.08) * 0.4 * speed();
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-6) vel.add(new Vector(-vel.getZ() / vl, 0, vel.getX() / vl).multiply(sway));
        Player p = nearest(48);
        face(p != null ? chest(p) : pos.clone().add(vel));
        inhale(26);
        if (p != null && age >= readyAt && dist(p) <= SWOOP_RANGE && sees(p)) pick(p, cd(WINDUP));
    }

    private void pick(Player p, int ticks) {
        prey = p;
        mark(p);
        hold();
        windup = ticks;
        phase(Phase.WINDUP);
    }

    /** 표적을 보며 들숨이 잦아짐, 끝에 그 사람 옆을 스치는 길로 나섬. */
    private void windup() {
        if (!standing(prey)) {
            calm();
            return;
        }
        vel.multiply(0.6);
        boolean faced = turnTo(chest(prey), 30f);
        body.look.rate = 1.5;
        inhale(7);
        if (inPhase() >= windup && faced) {
            Vector to = toward(prey);
            // 표적 몸통 옆 0.7 블록을 지나는 길(지금 자리에서 가까운 쪽 옆)
            Vector side = new Vector(-to.getZ(), 0, to.getX()).multiply(game.random.nextBoolean() ? PASS_SIDE : -PASS_SIDE);
            Vector aim = chest(prey).add(side).subtract(pos).setY(0);
            double l = aim.length();
            dashDir.copy(l < 1e-6 ? to : aim.multiply(1 / l));
            dashLen = l + OVERSHOOT;
            dashGone = 0;
            body.look.rate = 1.0;
            noPush = true; // 박지 않고 스쳐 지나감
            state("swoop");
            phase(Phase.SWOOP);
        }
    }

    /** 스치며 지나감: 닿는 사람을 한 번씩 문다(멈추지 않음). 지나친 뒤 곧장 다음 사람에게. */
    private void swoop() {
        double v = SWOOP_V * speed() / baseSpeed;
        dash(dashDir, v);
        face(pos.clone().add(dashDir));
        dashGone += v;
        inhale(6);
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            if (bitten.contains(p.getUniqueId()) || !touches(p, BITE_REACH)) continue;
            bite(p);
        }
        if (dashGone >= dashLen || inPhase() >= SWOOP_MAX || wallAhead(0.7)) {
            noPush = false;
            state("");
            chain++;
            Player next = chain < CHAIN ? next() : null;
            if (next != null) pick(next, cd(WINDUP_NEXT));
            else {
                mark(null);
                prey = null;
                readyAt = age + cd(COOLDOWN);
                phase(Phase.TURN);
            }
        }
    }

    private void bite(Player p) {
        bitten.add(p.getUniqueId());
        hit(p, DAMAGE, "cause_g32");
        sound("bite", Sound.ENTITY_FOX_BITE, 1.3f, 16);
        body.squash(1.15f, 0.85f, 1.1f, 2);
        if (standing(p)) Laugh.of(game).infect(game, p, this);
    }

    /** 이번에 아직 안 문 사람 중 가장 가까운 사람(12블록 안, 아직 웃음이 안 옮은 사람 먼저). */
    private Player next() {
        Laugh lg = Laugh.of(game);
        Player best = null;
        double bd = Double.MAX_VALUE;
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            if (bitten.contains(p.getUniqueId())) continue;
            double d = dist(p);
            if (d > NEXT_RANGE) continue;
            if (lg.has(p)) d += NEXT_RANGE;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** 느리게 돌아서며 쉼. */
    private void turn() {
        vel.multiply(0.75);
        if (inPhase() == 1) body.squash(1f, 1f, 1f, 4);
        Player p = nearest(48);
        if (p != null) turnTo(chest(p), 6f);
        inhale(30);
        if (inPhase() >= cd(COOLDOWN)) calm();
    }

    private void calm() {
        mark(null);
        prey = null;
        noPush = false;
        chain = 0;
        bitten.clear();
        body.look.rate = 1.0;
        state("");
        phase(Phase.APPROACH);
    }

    /** 들숨 '히이'를 every 틱 간격으로. */
    private void inhale(int every) {
        if (age < nextInhale) return;
        sound("inhale", Sound.ENTITY_PLAYER_BREATH, 1.7f, 20);
        nextInhale = age + every;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        return super.damage(d.type() == DamageType.PELLET ? d.times(PELLET_MUL) : d);
    }

    /** 시험용. */
    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " chain=" + chain + " laugh=" + Laugh.of(game).count();
    }

    // ------------------------------------------------------------------ 옮은 웃음(판마다 하나)

    /**
     * 웃음이 옮은 사람들. 살아 있는 G32 가 매 틱 부르고 그 틱의 첫 부름만 일한다. 옮은 사람이 쏘거나(쏜 발 수가 늚) 2.4블록 걸을 때마다
     * 그 자리에서 킥킥이 나고 12블록 안 게이브가 잠깐 끌려온다. 문 개체가 죽거나(웨이브 끝 정리 포함), 20초가 지나거나, 그 사람이 쓰러지면 풀린다.
     */
    static final class Laugh {

        private static final Map<Game, Laugh> ALL = new WeakHashMap<>();

        static Laugh of(Game game) {
            return ALL.computeIfAbsent(game, k -> new Laugh());
        }

        private static final class One {
            long until, giggleAt;
            int shots;
            double x, z;
            Gabe by;
        }

        private final Map<UUID, One> on = new HashMap<>();
        private final Map<Integer, Long> lured = new HashMap<>();
        private long tickAt = Long.MIN_VALUE;
        private int round = Integer.MIN_VALUE;

        boolean has(Player p) {
            return on.containsKey(p.getUniqueId());
        }

        int count() {
            return on.size();
        }

        void infect(Game game, Player p, Gabe by) {
            One o = on.get(p.getUniqueId());
            boolean fresh = o == null;
            if (fresh) {
                o = new One();
                on.put(p.getUniqueId(), o);
                Guns.Arms a = game.guns.arms(p);
                o.shots = a == null ? 0 : a.shots;
                Location l = p.getLocation();
                o.x = l.getX();
                o.z = l.getZ();
            }
            o.until = game.now() + INFECT;
            o.by = by;
            if (fresh) game.hud.notice(p, "notice_g32_laugh", Species.G32, 50);
            game.hud.mark(p, "g32", 30);
        }

        void tick(Game game) {
            long now = game.now();
            if (now == tickAt) return;
            boolean reset = now < tickAt || game.round != round;
            tickAt = now;
            round = game.round;
            if (on.isEmpty()) return;
            if (reset) {
                on.clear();
                lured.clear();
                return;
            }
            for (Iterator<Map.Entry<UUID, One>> it = on.entrySet().iterator(); it.hasNext();) {
                Map.Entry<UUID, One> e = it.next();
                One o = e.getValue();
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null || now >= o.until || o.by == null || o.by.dead || !game.players.get(p).standing()) {
                    it.remove();
                    continue;
                }
                if (now % 10 == 0) game.hud.mark(p, "g32", 20);
                Guns.Arms a = game.guns.arms(p);
                int shots = a == null ? o.shots : a.shots;
                Location l = p.getLocation();
                double dx = l.getX() - o.x, dz = l.getZ() - o.z;
                boolean shot = shots > o.shots, step = dx * dx + dz * dz >= STEP * STEP;
                o.shots = shots;
                if (step) {
                    o.x = l.getX();
                    o.z = l.getZ();
                }
                if (shot && now - o.giggleAt >= GAP_SHOT || step && now - o.giggleAt >= GAP_STEP) {
                    o.giggleAt = now;
                    giggle(game, p, l, now);
                }
            }
            if (now % 100 == 0) lured.values().removeIf(t -> t <= now);
        }

        /** 그 사람 자리에서 킥킥(남들에게 위치 소리), 12블록 안 아직 아무도 안 찍은 게이브가 잠깐 그쪽으로. */
        private void giggle(Game game, Player p, Location l, long now) {
            if (!game.director.silent())
                Props.soundAround(l, "gabe.g32.giggle", Sound.ENTITY_WITCH_CELEBRATE, 0.7f, 0.94f + game.random.nextFloat() * 0.12f, 1.7f, 24, -1);
            Vector at = l.toVector().add(new Vector(0, 0.9, 0));
            List<Gabe> near = game.mobs.near(at, LURE_R, g -> g.sp != Species.G32 && g.target == null && !g.disguised && !g.untargetable
                    && g.sp.spawn == Species.Spawn.RING && !g.lured() && !g.biting());
            for (int i = 0; i < near.size(); i++) {
                Gabe g = near.get(i);
                if (g.pos.distanceSquared(at) < LURE_NEAR * LURE_NEAR) continue;
                Long again = lured.get(g.id);
                if (again != null && again > now) continue;
                lured.put(g.id, now + LURE_AGAIN);
                try {
                    g.lure(at, LURE_T);
                } catch (RuntimeException ex) {
                    // 그 종의 lure 가 던져도 웃음은 계속
                }
            }
        }
    }
}
