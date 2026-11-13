package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.trip.Truck;

/**
 * G167 뽑기 집게 게이브(2막 16라운드, 방해, 지원, 목표 압박, 7블록). 하늘 15블록에 뜬 2.5블록 얼굴 상자에서 굵은 막대기 기둥이 내려오고 그 끝에
 * 집게발 셋(다 벌리면 폭 7블록)이 달렸다. 하늘을 천천히 떠다니며(0.12블록/틱) 두 가지를 번갈아 한다.
 * <ul>
 * <li>떼 집기: 트럭 둘레 밖 떼 위에 멈춰 집게를 내려 게이브 여섯까지 한 움큼 집어 올리고, 트럭 둘레 안(가운데에서 6~9블록)에 떨어뜨린다. 떨어진
 * 게이브는 트럭 노림으로 찍힌다(집힌 동안은 몸을 치우고 집게 모델이 한 움큼을 그림, 떨어뜨릴 때 같은 종을 같은 체력 몫으로 되살림).</li>
 * <li>사람 집기: 한 사람 위로 가서 멈추면(그림자가 멈춤) 1.2초 x 박자 뒤 집게를 내린다. 집게 아래 1.4블록 안 사람을 집어 9블록 위로 들어 트럭 둘레
 * 밖 34블록까지 4초 동안 옮겨 떼 한복판에 떨어뜨린다(낙하 18, 넘어짐 1초). 내려찍을 때 둘레 1.5블록 안 다른 사람은 12 와 밀림. 가끔 놓친다.</li>
 * </ul>
 * 대처: 그림자가 멈추면 2블록 비킨다. 내려온 집게발 끝 얼굴 셋이 약점(x2). 기둥(부위 체력 80 x 감독 체력 배율)을 끊으면 집힌 사람이 그 자리에서
 * 떨어지고(낮을수록 덜 아픔) 집힌 게이브들은 떨어져 죽는다. 끊긴 기둥은 15초 x 박자 뒤 다시 자란다.
 *
 * <p>몸: {@link Body#carrier} + 부품(얼굴 상자 g167 몸 방향, 기둥 g167_pole, 집게 g167_claw, 그림자 g167_shadow: 셋은 세계 축). Gabe.pos = 얼굴 상자
 * 가운데(바닥 + 15). 기둥 길이와 집게 높이는 서버가 {@link G167Sky.Glide} 로 디스플레이 보간과 같은 값을 안다.
 */
public final class G167 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 높이(바닥 위): 얼굴 상자 가운데, 쉬는 집게 바퀴통, 땅에 내린 집게(발끝이 바닥), 사람을 든 집게. 기둥은 얼굴 가운데 POLE_TOP 아래에서 시작. */
    static final double HEAD_H = 15, HUB_REST = 10.5, HUB_GROUND = 4.4, HUB_CARRY = 13.0, POLE_TOP = 1.45, SEAT_BELOW = 3.4;
    /** 틱: 멈춘 뒤 내려오기까지(박자), 내려오기, 들어 올리기, 옮기기 바탕, 벌리기, 기둥 다시 자람(박자), 떼 다시 찾기. */
    static final int WINDUP = 24, DROP_T = 8, LIFT_T = 24, CARRY_T = 80, OPEN_T = 10, REGROW = 300, RESCAN = 40;
    /** 피해: 내려찍기(둘레), 떨어뜨림. 반경: 내려찍기, 집기. 약점 배율. 기둥 체력. */
    static final double SLAM_DMG = 12, FALL_DMG = 18, SLAM_R = 1.5, GRAB_R = 1.4, TIP_MUL = 2, POLE_HP = 80;
    /** 떼: 한 번에 집는 수, 집는 반경, 떨어뜨리는 반경(가운데에서), 사람을 버리는 반경, 놓칠 몫. */
    static final int FLOCK_N = 6;
    static final double FLOCK_R = 3.0, IN_R0 = 6, IN_R1 = 9, OUT_R = 34, SLIP = 0.12;
    /** 집게 폭(벌림, 다묾)과 발끝 자리(바퀴통 기준 수평 반지름, 아래). */
    static final double OPEN_W = 6.6, SHUT_W = 2.2, TIP_OPEN_R = 3.2, TIP_OPEN_Y = 3.9, TIP_SHUT_R = 0.85, TIP_SHUT_Y = 4.2;

    enum Phase { MOVE, WINDUP, DROP, LIFT, CARRY, OPEN, CUT }

    /** 집어 간 게이브 하나(되살릴 때 쓸 값). */
    private record Load(Species sp, double frac, boolean fromDir) {
    }

    private Phase phase = Phase.MOVE;
    private int phaseAt;
    private boolean flockMode = true, init, shut, cut;
    private double poleHp;
    private final G167Sky.Glide hub = new G167Sky.Glide();
    private Body.Part head, pole, claw, shadow;
    private final Matrix4f mx = new Matrix4f();
    private Player prey, held;
    private final Vector spot = new Vector(), dest = new Vector(), heldFrom = new Vector();
    private boolean hasSpot;
    private final List<Load> load = new ArrayList<>();
    private int slipAt = -1, scanAt, tuneAt;
    private float wob;
    private final G167Sky.Drops drops = new G167Sky.Drops();
    private List<double[]> sph;
    private int sphAge = -1;
    private String clawState = "";

    public G167(Game game, Species sp) {
        super(game, sp);
        radius = 1.6;
        baseSpeed = 0.12;
        flying = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        noRules = true;
        big = true;
        ragKick = 0.4;
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        body.viewRange(4f);
        head = body.attach("gabe/g167");
        pole = body.attach("gabe/g167_pole");
        if (pole != null) pole.follow = false;
        claw = body.attach("gabe/g167_claw");
        if (claw != null) claw.follow = false;
        shadow = body.attach("gabe/g167_shadow");
        if (shadow != null) {
            shadow.follow = false;
            shadow.look.freeze = true;
        }
        hub.snap(HUB_REST);
        poseRig(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pos.setY(floor() + HEAD_H);
        if (body != null) body.warp();
        poseRig(0);
    }

    @Override
    public void lure(Vector at, int ticks) {
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
        if (!init) {
            init = true;
            poleHp = POLE_HP * G167Sky.hpK(this);
            flockMode = game.random.nextBoolean();
        }
        vel.zero();
        double want = floor() + HEAD_H;
        vel.setY(Math.max(-0.4, Math.min(0.4, want - pos.getY())));
        drops.tick(this);
        if (every(20)) {
            G167Sky.light(head, G167Sky.FACE_LIGHT);
            G167Sky.light(claw, G167Sky.FACE_LIGHT);
        }
        switch (phase) {
            case MOVE -> move();
            case WINDUP -> windup();
            case DROP -> drop();
            case LIFT -> lift();
            case CARRY -> carry();
            case OPEN -> open();
            case CUT -> cutTick();
        }
        if (held != null) carrySeat();
        if (every(2)) poseShadow();
    }

    private double hubY() {
        return floor() + hub.at(now());
    }

    /** 수평으로 to 쪽 speed. 가까우면 true. */
    private boolean goTo(Vector to, double speed, double near) {
        double dx = to.getX() - pos.getX(), dz = to.getZ() - pos.getZ(), d = Math.hypot(dx, dz);
        if (d <= near) return true;
        double v = Math.min(speed, d);
        vel.setX(dx / d * v).setZ(dz / d * v);
        if (d > 1.5) yaw = G167Sky.yawTo(pos, to);
        return false;
    }

    private void move() {
        if (!G167Sky.wave(game)) return;
        if (flockMode) {
            if (!hasSpot || age >= scanAt) {
                scanAt = age + RESCAN;
                hasSpot = flockSpot(spot);
                if (!hasSpot) {
                    flockMode = false;
                    return;
                }
            }
            if (goTo(spot, speed(), 0.6)) {
                startWindup();
            }
        } else {
            if (!G167Sky.up(game, prey) || held != null) {
                prey = pickPrey();
                if (prey == null) {
                    Vector c = G167Sky.center();
                    goTo(c, speed(), 6);
                    return;
                }
            }
            Location l = prey.getLocation();
            Vector to = new Vector(l.getX(), 0, l.getZ());
            if (age >= tuneAt) {
                tuneAt = age + 60;
                sound("tune", Sound.BLOCK_NOTE_BLOCK_BIT, 48);
            }
            if (goTo(to, speed(), 0.9)) {
                mark(prey);
                startWindup();
            }
        }
    }

    /** 돌아가는 길의 사람: 서 있는(붙잡히거나 앉지 않은) 사람 가운데 집게에서 가까운 셋 중 하나. */
    private Player pickPrey() {
        List<Player> ps = G167Sky.standing(game);
        ps.removeIf(p -> game.players.grabbedBy(p) != null || game.players.riding(p));
        if (ps.isEmpty()) return null;
        ps.sort((a, b) -> Double.compare(G167Sky.flat(pos, a.getLocation()), G167Sky.flat(pos, b.getLocation())));
        return ps.get(game.random.nextInt(Math.min(3, ps.size())));
    }

    private void startWindup() {
        if (shadow != null) shadow.state("dark");
        clawState("");
        sound("tune", Sound.BLOCK_NOTE_BLOCK_BIT, 48);
        lowerTo(HUB_REST - 1.2, 10);
        phase(Phase.WINDUP);
    }

    private void windup() {
        if (inPhase() >= cd(WINDUP)) {
            lowerTo(HUB_GROUND, DROP_T);
            phase(Phase.DROP);
        }
    }

    private void drop() {
        if (inPhase() < DROP_T) return;
        Location at = new Location(game.world, pos.getX(), floor(), pos.getZ());
        G167Sky.quake(game, at, 2.4f);
        sound("clank", Sound.BLOCK_ANVIL_LAND, 0.7f, 40);
        if (flockMode) grabFlock();
        else grabMan();
        if (shadow != null) shadow.state("");
        lowerTo(held != null ? HUB_CARRY : HUB_REST, LIFT_T);
        phase(Phase.LIFT);
    }

    private void lift() {
        if (inPhase() < LIFT_T) return;
        if (held != null || !load.isEmpty()) {
            pickDest();
            slipAt = game.random.nextDouble() < SLIP ? age + 20 + game.random.nextInt(40) : -1;
            phase(Phase.CARRY);
        } else {
            flockMode = !flockMode;
            hasSpot = false;
            phase(Phase.MOVE);
        }
    }

    private void carry() {
        double d = G167Sky.flat(pos, dest);
        double sp = Math.max(speed(), Math.min(0.5, d / Math.max(10, CARRY_T - inPhase())));
        if (held != null && !G167Sky.up(game, held) && game.players.grabbedBy(held) != this) held = null;
        if (age == slipAt) slip();
        if (every(6) && claw != null) {
            wob = -wob + (game.random.nextFloat() - 0.5f) * 0.12f;
            if (Math.abs(wob) < 0.04f) wob = 0.08f;
            poseClaw(6);
            sound("clank", Sound.BLOCK_CHAIN_STEP, 1.4f, 24);
        }
        if (goTo(dest, sp, 0.8) || inPhase() > CARRY_T * 3) {
            phase(Phase.OPEN);
            clawState("");
            wob = 0;
            poseClaw(4);
        }
    }

    private void open() {
        if (inPhase() == 2) release();
        if (inPhase() >= OPEN_T) {
            lowerTo(HUB_REST, 20);
            flockMode = !flockMode;
            hasSpot = false;
            prey = null;
            phase(Phase.MOVE);
        }
    }

    private void cutTick() {
        Vector c = G167Sky.centroid(game);
        if (c != null) goTo(c, speed() * 0.6, 8);
        if (inPhase() >= cd(REGROW)) {
            cut = false;
            poleHp = POLE_HP * G167Sky.hpK(this);
            hub.snap(HUB_REST - 6);
            clawState("");
            poseRig(0);
            lowerTo(HUB_REST, 30);
            flockMode = !flockMode;
            hasSpot = false;
            phase(Phase.MOVE);
        }
    }

    // ------------------------------------------------------------------ 집기, 놓기

    /** 트럭 둘레 밖(가운데에서 16~70블록) 떼가 가장 빽빽한 자리. 없으면 false. */
    private boolean flockSpot(Vector out) {
        Vector c = G167Sky.center();
        List<Gabe> cand = new ArrayList<>();
        for (Gabe g : game.mobs.all()) {
            if (!grabbable(g)) continue;
            double r = G167Sky.flat(g.pos, c);
            if (r < 16 || r > 70) continue;
            cand.add(g);
        }
        if (cand.isEmpty()) return false;
        Gabe best = null;
        int bestN = -1;
        for (int i = 0; i < Math.min(14, cand.size()); i++) {
            Gabe g = cand.get(game.random.nextInt(cand.size()));
            int n = 0;
            for (Gabe h : cand) if (G167Sky.flat(g.pos, h.pos) <= FLOCK_R) n++;
            if (n > bestN) {
                bestN = n;
                best = g;
            }
        }
        if (best == null) return false;
        out.setX(best.pos.getX()).setY(0).setZ(best.pos.getZ());
        return true;
    }

    /** 집어 옮겨도 되는 게이브: 보스, 거대, 판 규칙 밖, 미리 깔리는 종, 파생 종, 함정, 보상, 목표 압박, 뽑기 집게 빼고. */
    private boolean grabbable(Gabe g) {
        if (!G167Sky.other(this, g) || g.sp == Species.G167 || g.disguised) return false;
        Species s = g.sp;
        if (s.spawn == Species.Spawn.PLACED || s.spawn == Species.Spawn.DERIVED) return false;
        return !s.is(Role.TRAP) && !s.is(Role.BONUS) && !s.is(Role.OBJECTIVE);
    }

    private void grabFlock() {
        Set<Gabe> busy = G167Sky.holders(game);
        double fl = floor();
        for (Gabe g : game.mobs.all()) {
            if (load.size() >= FLOCK_N) break;
            if (!grabbable(g) || busy.contains(g)) continue;
            if (G167Sky.flat(g.pos, pos) > FLOCK_R || g.pos.getY() > fl + 5.5) continue;
            load.add(new Load(g.sp, g.hpFrac(), game.director.fromDirector(g)));
            G167Sky.splash(game, g.pos.clone().add(new Vector(0, 0.4, 0)), true);
            g.remove();
        }
        clawState(load.isEmpty() ? "shut" : "full");
    }

    private void grabMan() {
        Player best = null;
        double bd = GRAB_R;
        Vector c = new Vector(pos.getX(), 0, pos.getZ());
        for (Player p : G167Sky.standing(game)) {
            double d = G167Sky.flat(c, p.getLocation());
            if (d <= bd && p.getLocation().getY() < floor() + 3) {
                bd = d;
                best = p;
            }
        }
        if (best != null && grab(best, 99)) {
            PState s = game.players.get(best);
            s.grabDetach = false;
            held = best;
            heldFrom.copy(best.getLocation().toVector());
            mark(best);
            clawState("man");
        } else clawState("shut");
        for (Player p : G167Sky.standing(game)) {
            if (p == held) continue;
            Location l = p.getLocation();
            double d = G167Sky.flat(c, l);
            if (d <= SLAM_R + 0.6 && l.getY() < floor() + 3) {
                hit(p, SLAM_DMG, "cause_g167_slam");
                Vector away = new Vector(l.getX() - pos.getX(), 0, l.getZ() - pos.getZ());
                knock(p, away.lengthSquared() > 1e-4 ? away : new Vector(1, 0, 0), 0.55, 0.3);
            }
        }
    }

    /** 놓을 곳: 떼는 트럭 둘레 안(가운데에서 6~9블록 무작위), 사람은 집힌 자리에서 바깥쪽 34블록. */
    private void pickDest() {
        Vector c = G167Sky.center();
        if (held != null) {
            Vector out = heldFrom.clone().subtract(c).setY(0);
            double r = out.length();
            if (r < 1) out = G167Sky.fwd(game.random.nextDouble() * 360);
            else out.multiply(1 / r);
            double R = Math.max(OUT_R, r + 14);
            dest.setX(c.getX() + out.getX() * R).setY(0).setZ(c.getZ() + out.getZ() * R);
        } else {
            double a = game.random.nextDouble() * Math.PI * 2, R = IN_R0 + game.random.nextDouble() * (IN_R1 - IN_R0);
            dest.setX(c.getX() + Math.cos(a) * R).setY(0).setZ(c.getZ() + Math.sin(a) * R);
        }
    }

    /** 집게가 덜컹거리다 놓침: 사람이면 그 자리에서 떨어짐, 떼면 하나가 떨어짐. */
    private void slip() {
        sound("clank", Sound.BLOCK_ANVIL_LAND, 1.2f, 40);
        if (held != null) {
            Player p = held;
            letGo(p, 1.0);
            clawState("shut");
        } else if (!load.isEmpty()) {
            Load l = load.remove(load.size() - 1);
            Vector at = new Vector(pos.getX(), hubY() - 3, pos.getZ());
            Gabe g = born(l, at);
            if (g == null) return;
            if (load.isEmpty()) clawState("shut");
        }
    }

    /** 사람을 놓음(높이 몫 k 로 낙하 피해). */
    private void letGo(Player p, double k) {
        held = null;
        if (p == null) return;
        if (game.players.grabbedBy(p) == this) game.players.release(p);
        double h = Math.max(0, p.getLocation().getY() - floor());
        double dmg = FALL_DMG * k * Math.max(0.2, Math.min(1, h / 9.0));
        drops.add(this, p, dmg, "cause_g167", 20);
        sound("drop", Sound.ENTITY_ITEM_PICKUP, 0.5f, 40);
    }

    private void release() {
        sound("drop", Sound.ENTITY_ITEM_PICKUP, 0.5f, 40);
        if (held != null) letGo(held, 1.0);
        Truck t = Truck.get();
        for (Load l : load) {
            double a = game.random.nextDouble() * Math.PI * 2;
            Vector at = new Vector(pos.getX() + Math.cos(a) * 1.2, hubY() - 3, pos.getZ() + Math.sin(a) * 1.2);
            Gabe g = born(l, at);
            if (g != null && t != null) t.mark(g);
        }
        load.clear();
        clawState("");
    }

    /** 집어 간 게이브를 되살림(감독이 낸 것이면 감독이 낸 것처럼, 같은 체력 몫). 예산이 차면 null. */
    private Gabe born(Load l, Vector at) {
        Gabe g = l.fromDir() ? game.director.spawnLike(l.sp(), at) : game.mobs.spawn(l.sp(), at, true);
        if (g == null) return null;
        g.hp = Math.max(1, g.maxHp * l.frac());
        return g;
    }

    /** 기둥이 끊김: 집힌 사람은 그 자리에서 떨어지고(낮을수록 덜 아픔), 집힌 게이브는 떨어져 죽는다. 집게는 떨어져 부서진다. */
    private void cutPole() {
        if (cut) return;
        cut = true;
        sound("snap", Sound.ENTITY_ITEM_BREAK, 0.6f, 48);
        double hy = hubY();
        if (held != null) letGo(held, 1.0);
        Vector ground = new Vector(pos.getX(), floor() + 0.6, pos.getZ());
        for (Load l : load) {
            Gabe g = born(l, ground.clone().add(new Vector(game.random.nextGaussian() * 0.8, 0, game.random.nextGaussian() * 0.8)));
            if (g == null) continue;
            g.noReward = true;
            g.reward = 0;
            g.die(new Damage(g.hp + 1, DamageType.OTHER, null, g.pos.clone(), new Vector(0, -1, 0), "fall", 0));
        }
        load.clear();
        // 떨어지는 집게(연출 디스플레이: 몸 부품은 숨김)
        ItemDisplay fall = G167Sky.fx(game, new Location(game.world, pos.getX(), hy, pos.getZ()), "gabe/g167_claw", "cut", G167Sky.lay("gabe/g167_claw", 0,
                1, 1, 1), 40);
        if (fall != null) {
            Matrix4f to = new Matrix4f().translate(0, (float) (floor() + 4.2 - hy), 0).rotateZ(0.5f).mul(G167Sky.lay("gabe/g167_claw", 0, 1, 1, 1));
            G167Sky.glideTo(game, fall, to, 12);
        }
        game.shotFx.pop(new Location(game.world, pos.getX(), floor() + 0.5, pos.getZ()));
        hub.snap(HUB_REST);
        poseRig(0);
        if (shadow != null) shadow.state("");
        phase(Phase.CUT);
    }

    // ------------------------------------------------------------------ 몸

    private void clawState(String s) {
        clawState = s;
        shut = !s.isEmpty() && !s.equals("cut");
        if (claw != null) claw.state(s);
    }

    private void lowerTo(double h, int ticks) {
        hub.go(h, now(), ticks);
        poseRig(ticks);
    }

    private void poseRig(int ticks) {
        if (pole != null) {
            if (cut) pole.pose(mx.identity().scale(0.001f), 0);
            else {
                double top = -POLE_TOP, bot = hub.to + floorOff();
                double len = Math.max(0.2, top - bot), mid = (top + bot) / 2;
                pole.pose(new Matrix4f().translate(0, (float) mid, 0).scale(1f, (float) (len / 3.0), 1f), ticks);
            }
        }
        poseClaw(ticks);
    }

    /** 바닥 - 얼굴 높이(부품 자리 셈: 몸 기준 y 는 hub - HEAD_H 쯤). */
    private double floorOff() {
        return floor() - pos.getY();
    }

    private void poseClaw(int ticks) {
        if (claw == null) return;
        if (cut) {
            claw.pose(new Matrix4f().scale(0.001f), 0);
            return;
        }
        claw.pose(new Matrix4f().translate(0, (float) (hub.to + floorOff()), 0).rotateX(wob).rotateZ(wob * 0.7f), ticks);
    }

    private void poseShadow() {
        if (shadow == null) return;
        double w = cut ? 2.6 : shut ? SHUT_W + 1 : OPEN_W;
        shadow.pose(new Matrix4f().translate(0, (float) (floorOff() + 0.06), 0).scale((float) (w / 2), 1f, (float) (w / 2)), 2);
    }

    /** 든 사람을 집게 아래 좌석에. */
    private void carrySeat() {
        if (held == null) return;
        if (game.players.grabbedBy(held) != this) {
            held = null;
            return;
        }
        game.players.carry(held, new Vector(pos.getX(), hubY() - SEAT_BELOW, pos.getZ()));
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    /** 맞는 구: 0 얼굴 상자, 1 기둥, 2 집게발 끝 얼굴(약점), 3 바퀴통. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(18);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 1.6, 0});
        if (!cut) {
            double top = pos.getY() - POLE_TOP, hy = hubY();
            for (double y = top - 0.4; y > hy + 0.3; y -= 1.0) out.add(new double[] {pos.getX(), y, pos.getZ(), 0.5, 1});
            out.add(new double[] {pos.getX(), hy - 0.2, pos.getZ(), 0.7, 3});
            double r = shut ? TIP_SHUT_R : TIP_OPEN_R, dy = shut ? TIP_SHUT_Y : TIP_OPEN_Y;
            for (int i = 0; i < 3; i++) {
                double a = Math.toRadians(90 + i * 120);
                out.add(new double[] {pos.getX() + Math.cos(a) * r, hy - dy, pos.getZ() + Math.sin(a) * r, 0.5, 2});
            }
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || unhittable()) return false;
        int part = G167Sky.partAt(spheres(), d.at());
        Damage dd = part == 2 ? d.times(TIP_MUL) : d;
        double amt = dd.amount();
        hp -= amt;
        game.mobs.onHurt(this, dd);
        if (part == 1 && !cut) {
            poleHp -= amt;
            if (poleHp <= 0 && hp > 0) cutPole();
        }
        if (hp <= 0) die(dd);
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (held != null) letGo(held, 1.0);
        drops.clear();
        // 집혀 있던 게이브는 떨어져 죽음
        Vector ground = new Vector(pos.getX(), floor() + 0.6, pos.getZ());
        for (Load l : load) {
            Gabe g = born(l, ground.clone().add(new Vector(game.random.nextGaussian(), 0, game.random.nextGaussian())));
            if (g == null) continue;
            g.noReward = true;
            g.reward = 0;
            g.die(new Damage(g.hp + 1, DamageType.OTHER, null, g.pos.clone(), new Vector(0, -1, 0), "fall", 0));
        }
        load.clear();
        if (shadow != null) {
            shadow.remove();
            shadow = null;
        }
        if (pole != null) {
            pole.remove();
            pole = null;
        }
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " mode=" + (flockMode ? "flock" : "man") + " hub=" + Math.round(hub.at(now()) * 10) / 10.0 + " pole="
                + Math.round(poleHp) + (cut ? " cut" : "") + " load=" + load.size() + " held=" + (held == null ? "-" : held.getName()) + " claw="
                + (clawState.isEmpty() ? "open" : clawState);
    }
}
