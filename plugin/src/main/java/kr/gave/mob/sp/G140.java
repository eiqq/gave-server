package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Role;
import kr.gave.mob.Species;

/**
 * G140 항아리 게이브(4막, 지원, 정예). 지게 다리로 뒤뚱뒤뚱 걸어와(0.13/틱) 떼 한가운데에서 뚜껑을 열고 6블록 안 다른 종 게이브를 넷까지 빨아들인다(둘
 * 이상일 때). 안에서 12초(240틱) 동안 씹고 뜯는 소리가 나고 그동안 사람 쪽으로 계속 걷는다(열리기 2초 전 뚜껑이 들썩). 12초 뒤 뚜껑이 열리며 남은
 * 하나가 나온다: 먹은 넷 가운데 가장 센 종(체력 몫이 가장 큰 것)으로, 넷의 체력 합을 지니고, 사람에게 주는 피해 x1.5(MOBDMG: Gabe.dmgMul),
 * 홍조가 검붉고(PAL_DEEP_BLUSH) 보상 3배. 12초가 차기 전에 독을 깨면(이 개체가 죽으면) 안의 것들이 체력 40% 로 쏟아진다(보상 없음).
 * 지게 다리 하나(체력 90)를 꺾으면 넘어져 독이 깨진다. 몸통 박기 18. 사람이 2블록 안이면 뚜껑으로 덮어 안에 넣는다: 붙잡고(탈출 연타 8) 독 안 자리로
 * 옮겨 어둡게 덮고 1초마다 12, 4초 뒤 뱉음.
 *
 * <p>바꾼 것: 금 틈으로 보이는 덩어리는 부품 대신 같은 모델 부위(fed). 떼 몸(마리가 많은 Swarm)인 종은 정예로 내지 않는다(무리 체력 약속이 달라서,
 * 그 몫은 셈에서 뺌).
 *
 * <p>팩 계약(tools/gabe/sp/g140.py): 상태 "", "open", "rattle", 부위 crack1..3, fed. 소리 gnaw(32), suck(24), lid(16), out(32), crack(32).
 * 글귀 cause_g140, cause_g140_jar.
 */
public final class G140 extends Gabe {

    static final double SPEED = 0.13, HOVER = 1.9, RADIUS = 1.05, SUCK_R = 6, COVER_R = 2.0;
    static final int MAX_EAT = 4, OPEN = 20, BREW = 240, RATTLE = 40, RELEASE = 20, EAT_CD = 200, RAM_CD = 40;
    static final double RAM = 18, JAR_DPS = 12, ELITE_DMG = 1.5, SPILL = 0.4, LEG_HP = 90;
    static final int JAR_HOLD = 80, JAR_CLICKS = 8, ELITE_REWARD = 3;

    enum Phase { WALK, OPEN, BREW, RELEASE }

    /** 먹은 것 하나(종, 남은 체력, 떼 몸이었나). */
    record Eaten(Species sp, double hp, boolean swarm) {
    }

    private Phase phase = Phase.WALK;
    private int phaseAt, eatReady = 60, ramAt, gnawAt, heldAt = -1;
    private final List<Eaten> eaten = new ArrayList<>(MAX_EAT);
    private final double[] legHp = {LEG_HP, LEG_HP};
    private Player held;

    public G140(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
        noPush = true;
        ragKick = 0.5;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g140", 2.6f, 4.0f);
    }

    @Override
    protected void spawned() {
        G150.arm(game);
    }

    private void go(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        cracks();
        if (held != null) holdTick();
        switch (phase) {
            case WALK -> walk();
            case OPEN -> open();
            case BREW -> brew();
            case RELEASE -> release();
        }
    }

    private void cracks() {
        double f = hpFrac();
        if (f < 0.75) body.look.on.add("crack1");
        if (f < 0.5) body.look.on.add("crack2");
        if (f < 0.25) body.look.on.add("crack3");
    }

    private void approach() {
        Player p = nearest(400);
        follow(speed());
        if (p != null) face(chest(p));
        if (p != null && age >= ramAt && touches(p, 0.3)) {
            hit(p, RAM, "cause_g140");
            knock(p, toward(p), 0.6, 0.2);
            body.squash(1.08f, 0.94f, 1.08f, 2);
            ramAt = age + cd(RAM_CD);
        }
        if (held == null && p != null && age >= ramAt && dist(p) <= COVER_R + RADIUS && standing(p)) cover(p);
    }

    private void walk() {
        approach();
        if (age >= eatReady && every(10) && food().size() >= 2) {
            hold();
            state("open");
            sound("lid", Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.6f, 16);
            go(Phase.OPEN);
        }
    }

    /** 빨아들일 수 있는 둘레 게이브(다른 종, 보스, 흉내, 가림, 사람을 가둔 것 빼고), 가까운 것부터. */
    private List<Gabe> food() {
        List<Gabe> out = game.mobs.near(pos, SUCK_R, g -> g != this && !g.dead && !(g instanceof G140) && !g.sp.is(Role.BOSS) && !g.disguised
            && !g.unhittable() && !g.noRules && game.players.trappedIn(g).isEmpty());
        out.sort((a, b) -> Double.compare(a.pos.distanceSquared(pos), b.pos.distanceSquared(pos)));
        return out;
    }

    private void open() {
        hold();
        if (inPhase() < cd(OPEN)) return;
        List<Gabe> f = food();
        int n = 0;
        for (Gabe g : f) {
            if (n >= MAX_EAT) break;
            boolean swarm = g.body != null && g.body.count() > 3;
            eaten.add(new Eaten(g.sp, Math.max(1, g.hp), swarm));
            g.remove();
            n++;
        }
        sound("suck", Sound.ENTITY_PLAYER_BREATH, 0.5f, 24);
        sound("lid", Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 16);
        if (!eaten.isEmpty()) log("빨아들임 " + eaten);
        if (eaten.isEmpty()) {
            state("");
            eatReady = age + cd(60);
            go(Phase.WALK);
            return;
        }
        body.look.on.add("fed");
        state("");
        go(Phase.BREW);
    }

    private void brew() {
        approach();
        if (age >= gnawAt) {
            sound("gnaw", Sound.ENTITY_GENERIC_EAT, 0.6f, 32);
            body.squash(1.04f, 0.97f, 1.04f, 2);
            gnawAt = age + 24 + game.random.nextInt(10);
        } else if (age == gnawAt - 20) body.squash(1f, 1f, 1f, 4);
        int t = inPhase();
        if (t >= BREW - RATTLE && !"rattle".equals(state)) state("rattle");
        if (t >= BREW) {
            hold();
            state("open");
            sound("lid", Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.5f, 16);
            go(Phase.RELEASE);
        }
    }

    private void release() {
        hold();
        if (inPhase() < RELEASE) return;
        Eaten best = null;
        double sum = 0, bestHp = -1;
        for (Eaten e : eaten) {
            if (e.swarm()) continue;
            sum += e.hp();
            if (e.sp().hp > bestHp) {
                bestHp = e.sp().hp;
                best = e;
            }
        }
        if (best != null) {
            Gabe out = child(best.sp(), front());
            if (out != null) {
                out.maxHp = sum;
                out.hp = sum;
                out.dmgMul = ELITE_DMG;
                out.noReward = false;
                out.reward = Math.max(1, best.sp().reward * ELITE_REWARD);
                if (out.body != null) out.body.look.palette |= Look.PAL_DEEP_BLUSH;
                log("정예 나옴 " + out.sp.name() + "#" + out.id + " 체력 " + Math.round(sum) + " 피해배율 " + out.dmgMul + " 보상 " + out.reward);
                steam(front());
            }
        }
        sound("out", Sound.ENTITY_RAVAGER_ROAR, 0.8f, 32);
        eaten.clear();
        body.look.on.remove("fed");
        state("");
        eatReady = age + cd(EAT_CD);
        go(Phase.WALK);
    }

    /** 독 앞 땅 자리(나오는 것을 놓을 곳). */
    private Vector front() {
        double r = Math.toRadians(yaw);
        Vector v = pos.clone().add(new Vector(-Math.sin(r), 0, Math.cos(r)).multiply(1.8));
        v.setY(game.mobs.flow.floor(v.getX(), v.getZ()) + 1.0);
        return v;
    }

    private void steam(Vector at) {
        try {
            Location l = new Location(game.world, at.getX(), at.getY() + 0.6, at.getZ());
            Display.Billboard bb = Display.Billboard.CENTER;
            var d = game.shotFx.spawn(l, "fx_quad", ShotFx.SMOKE, 1, ShotFx.scale(1.4f), bb, false, 40, ShotFx.EXTRA);
            game.shotFx.grow(d, ShotFx.scale(3.4f), 36);
            game.shotFx.drift(d, l.clone().add(0, 1.6, 0), 36);
        } catch (RuntimeException ignored) {
            // 연출은 덤
        }
    }

    // ------------------------------------------------------------------ 사람 덮기

    private void cover(Player p) {
        if (!grab(p, JAR_CLICKS)) return;
        held = p;
        heldAt = age;
        log("덮음 " + p.getName());
        state("open");
        sound("lid", Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.6f, 16);
        game.players.cover(p, "dark", JAR_HOLD + 10);
        ramAt = age + cd(RAM_CD);
    }

    private void holdTick() {
        Player p = held;
        if (p == null || !p.isOnline() || game.players.grabbedBy(p) != this) {
            letGo(false);
            return;
        }
        int t = age - heldAt;
        if (t == 6 && phase == Phase.WALK) state("");
        game.players.carry(p, pos.clone().add(new Vector(0, -0.35, 0)));
        if (t > 0 && t % 20 == 0) hit(p, JAR_DPS, "cause_g140_jar");
        if (t >= JAR_HOLD) letGo(true);
    }

    private void letGo(boolean spit) {
        Player p = held;
        held = null;
        if (p == null) return;
        if (game.players.grabbedBy(p) == this) game.players.release(p);
        game.players.uncover(p);
        if (spit && p.isOnline()) {
            Vector out = toward(p);
            if (out.lengthSquared() < 1e-6) out = new Vector(1, 0, 0);
            knock(p, out, 0.7, 0.4);
            sound("lid", Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.7f, 16);
        }
    }

    // ------------------------------------------------------------------ 맞음

    private double[] at(double x, double y, double z, double r) {
        double rr = Math.toRadians(yaw), fx = -Math.sin(rr), fz = Math.cos(rr);
        return new double[] {pos.getX() + fx * z + fz * x, pos.getY() + y, pos.getZ() + fz * z - fx * x, r};
    }

    private double[] leg(int i) {
        return at(i == 0 ? 0.65 : -0.65, -1.4, -1.05, 0.3);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(4);
        out.add(at(0, 0, 0, RADIUS));
        out.add(at(0, 0.95, -0.05, 0.6));
        for (int i = 0; i < 2; i++) if (legHp[i] > 0) out.add(leg(i));
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        Vector p = d.at();
        DamageType t = d.type();
        if (p != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            double[] b = at(0, 0, 0, RADIUS);
            double bd = Math.sqrt(sq(p.getX() - b[0]) + sq(p.getY() - b[1]) + sq(p.getZ() - b[2])) - RADIUS;
            for (int i = 0; i < 2; i++) {
                if (legHp[i] <= 0) continue;
                double[] l = leg(i);
                double dl = Math.sqrt(sq(p.getX() - l[0]) + sq(p.getY() - l[1]) + sq(p.getZ() - l[2])) - l[3];
                if (dl < bd) {
                    legHp[i] -= d.amount();
                    game.mobs.onHurt(this, d, 0);
                    if (legHp[i] <= 0) {
                        // 넘어져 독이 깨짐
                        sound("crack", Sound.BLOCK_DECORATED_POT_SHATTER, 0.7f, 32);
                        hp = 0;
                        die(d);
                    }
                    return true;
                }
            }
        }
        return super.damage(d);
    }

    private static double sq(double v) {
        return v * v;
    }

    /** 깨짐: 덮인 사람을 놓고, 안의 것들이 반쯤 뜯긴 채로 쏟아짐. */
    @Override
    public void die(Damage d) {
        if (dead) return;
        letGo(false);
        if (!eaten.isEmpty() && !noReward) {
            int i = 0;
            for (Eaten e : eaten) {
                double a = i++ * Math.PI / 2;
                Vector at = pos.clone().add(new Vector(Math.cos(a) * 1.2, 0, Math.sin(a) * 1.2));
                at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + 1.0);
                Gabe g = child(e.sp(), at);
                if (g != null && !e.swarm()) {
                    g.hp = Math.max(1, e.hp() * SPILL);
                    g.maxHp = Math.max(g.hp, g.maxHp);
                }
            }
            sound("crack", Sound.BLOCK_DECORATED_POT_SHATTER, 0.7f, 32);
            log("깨져 쏟음 " + eaten.size());
        }
        eaten.clear();
        super.die(d);
    }

    @Override
    public void remove() {
        letGo(false);
        super.remove();
    }

    /** 시험과 판 뒤 분석용 기록(드문 사건만). */
    void log(String s) {
        game.plugin.getLogger().info("[G140] " + s);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString()).append(' ').append(phase).append(" eaten=");
        for (Eaten e : eaten) sb.append(e.sp().name()).append(':').append(Math.round(e.hp())).append(e.swarm() ? "s" : "").append(',');
        sb.append(" legs=").append((int) legHp[0]).append(',').append((int) legHp[1]).append(held != null ? " held=" + held.getName() : "");
        return sb.toString();
    }
}
