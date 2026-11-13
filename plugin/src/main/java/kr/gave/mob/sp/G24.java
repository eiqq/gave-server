package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * G24 땅밑 얼굴 게이브(함정, 방해).
 *
 * <p>도감: 땅 위로는 이마 위쪽(적갈색 덩어리 둘)과 귀 줄 둘만 내놓고 땅속을 상어처럼 지그재그로 미끄러져 온다. 발밑을 한 바퀴 맴돌며
 * 웃는 눈으로 올려다보고(대상에게만 위치 없는 낮은 진동음), 1초 땅이 들썩인 뒤 솟구쳐 발목을 잡아 1.5초 묶고 아래에서 박치기로 띄운다(3 피해).
 * 맞서기: 귀가 맴돌면 점프하거나 비킨다(들썩일 때 그 자리를 벗어나거나 떠 있으면 빗나감). 땅속에서도 귀(이마)를 쏘면 맞는다.
 *
 * <p>황야 맵(docs/모듈/맵변경_황야.md): 중앙 원 안쪽 마른 돌바닥 밑으로는 못 들어온다(도감의 '상점 앞 돌'). 원 밖에 선 사람이 있으면
 * 그 사람에게 곧장 가고, 모두 원 안에 있으면 원 둘레를 상어처럼 맴돈다(귀가 보이니 원 안에서 쏘면 됨). 철판 바닥(하수구)은 없어졌다.
 *
 * <p>묶임은 쥐고 있는 동안 매 틱 2틱씩 이어 건다: 이 개체가 죽거나 치워지면 2틱 안에 풀린다.
 *
 * <p>팩 계약(tools/gabe/sp/g24.py): 모델 {@code gave:gabe/g24}(원점 = 땅 높이, 그래서 hover 0), 상태 {@code ""}(땅속 기기), {@code "peek"}
 * (하늘을 보는 얼굴, 맴돌기와 들썩), {@code "rise"}(솟구침, 잡기, 박치기). 소리 dig(16), rumble(8, 대상 한 사람에게만), burst(20), grab(16),
 * butt(16). 원인 글귀 {@code cause_g24}.
 */
public final class G24 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.17;
    /** 이 수평 거리 안 사람을 골라 발밑 맴돌기. */
    static final double STALK_RANGE = 4.5;
    /** 맴돌기 반지름, 한 바퀴 틱. */
    static final double CIRCLE_R = 1.5;
    static final int CIRCLE_T = 46;
    /** 땅 들썩(예고) 틱, 발목 묶는 틱, 솟구친 채 드러나 있는 틱, 다음 사냥까지 쉬는 틱. */
    static final int HEAVE = 20, GRAB = 30, EXPOSED = 22, COOLDOWN = 70;
    /** 솟을 때 잡히는 수평 거리, 발이 바닥에서 이만큼 넘게 떠 있으면(점프) 빗나감, 쥔 발이 이만큼 넘게 벗어나면 놓침. */
    static final double GRAB_R = 0.95, JUMP_CLEAR = 0.35, LOSE_R = 1.5;
    static final double DAMAGE = 5, LAUNCH = 0.85;
    static final double EAR_R = 0.42, FACE_R = 0.5;
    /** 중앙 원(마른 돌바닥) 가운데와 못 들어가는 반지름(돌바닥 끝 + 여유), 원 둘레를 맴도는 거리. */
    static final double CX = Plaza.CX + 0.5, CZ = Plaza.CZ + 0.5;
    static final double KEEP_OUT = Plaza.R_SAFE + 1.1, RIM = KEEP_OUT + 1.6;

    enum Phase { PROWL, STALK, HEAVE, RISE, HOLD, DIVE }

    private Phase phase = Phase.PROWL;
    private int phaseAt, readyAt, nextDig, nextRumble;
    /** 지금 쫓는 사람(원 밖에 선 가장 가까운 사람), 찍은 사람. */
    private Player goal, prey;
    private double circleA;
    private int circleDir = 1;
    private final Vector spot = new Vector();
    private boolean grabbed;

    public G24(Game game, Species sp) {
        super(game, sp);
        radius = EAR_R;
        hover = 0; // 원점 = 땅 높이(얼굴 대부분이 땅속)
        baseSpeed = SPEED;
        noPush = true; // 땅속이라 사람, 몹과 겹쳐도 됨(발밑을 지나감)
        ghost = true; // 땅속: 땅 위 물건에 막히지 않음(못 가는 곳은 중앙 원뿐, keepOut)
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g24", 2.8f, 1.8f);
        // 도감: 밝기 block 10 sky 15 고정(반쯤 묻혀도 시커메지지 않게)
        if (body.anchor != null) body.anchor.setBrightness(new Display.Brightness(10, 15));
    }

    @Override
    protected void spawned() {
        nextDig = age + game.random.nextInt(10);
        circleDir = game.random.nextBoolean() ? 1 : -1;
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
        pitch = 0;
        switch (phase) {
            case PROWL -> prowl();
            case STALK -> stalk();
            case HEAVE -> heave();
            case RISE -> rise();
            case HOLD -> holdAnkle();
            case DIVE -> dive();
        }
    }

    /** 땅속을 지그재그로 미끄러져 옴. 귀 줄이 흙을 가르고 흙이 튄다. 원 밖에 선 사람이 없으면 원 둘레를 맴돈다. */
    private void prowl() {
        state("");
        if (every(5) || (goal != null && !standing(goal))) goal = reachable();
        double v = speed();
        if (goal != null) {
            Vector f = feet(goal);
            double gx = pos.getX() - f.getX(), gz = pos.getZ() - f.getZ(), gd = Math.hypot(gx, gz);
            if (age < readyAt && gd < 3.6 && gd > 1e-3) {
                // 쉬는 동안: 그 사람 둘레 3블록을 맴돈다(발밑에 가만히 있지 않음)
                double nx = gx / gd, nz = gz / gd, w = v * 0.8;
                double radial = Math.max(-w, Math.min(w, (3.0 - gd) * 0.25));
                vel.setX(-nz * circleDir * w + nx * radial).setY(0).setZ(nx * circleDir * w + nz * radial);
            } else moveToward(new Vector(f.getX(), pos.getY(), f.getZ()), v);
        } else {
            double dx = pos.getX() - CX, dz = pos.getZ() - CZ, r = Math.hypot(dx, dz);
            if (r > RIM + 3) follow(v);
            else {
                // 상어처럼 원 둘레를 돈다(바깥으로 조금 띄운 채)
                if (r < 1e-3) {
                    dx = 1;
                    r = 1;
                }
                double nx = dx / r, nz = dz / r, w = v * 0.8;
                double radial = Math.max(-w, Math.min(w, (RIM - r) * 0.25));
                vel.setX(-nz * circleDir * w + nx * radial).setY(0).setZ(nx * circleDir * w + nz * radial);
            }
        }
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-6) {
            double zig = Math.sin((age + id * 17) * 0.21) * 0.75 * v;
            vel.add(new Vector(-vel.getZ() / vl, 0, vel.getX() / vl).multiply(zig));
        }
        keepOut();
        if (vel.lengthSquared() > 1e-6) face(new Vector(pos.getX() + vel.getX(), pos.getY(), pos.getZ() + vel.getZ()));
        dirt(2, 0.25);
        if (age >= nextDig) {
            sound("dig", Sound.BLOCK_ROOTED_DIRT_STEP, 0.6f, 16);
            nextDig = age + 9 + game.random.nextInt(4);
        }
        Player p = goal;
        if (p != null && age >= readyAt && standing(p) && flat(p) <= STALK_RANGE && !blocked(p)) {
            prey = p;
            mark(p);
            Vector rel = pos.clone().subtract(feet(p));
            circleA = Math.atan2(rel.getX(), rel.getZ());
            nextRumble = age;
            phase(Phase.STALK);
        }
    }

    /** 발밑 한 바퀴: 하늘을 보는 얼굴이 바닥 높이에서 웃으며 올려다봄. 대상에게만 진동음. */
    private void stalk() {
        if (!standing(prey) || blocked(prey)) {
            calm();
            return;
        }
        state("peek");
        circleA += circleDir * Math.PI * 2 / cd(CIRCLE_T);
        Vector f = feet(prey);
        Vector want = new Vector(f.getX() + Math.sin(circleA) * CIRCLE_R, pos.getY(), f.getZ() + Math.cos(circleA) * CIRCLE_R);
        moveToward(want, 0.42);
        keepOut();
        yaw = (float) Math.toDegrees(circleA) + (circleDir > 0 ? 180f : 0f);
        dirt(3, 0.2);
        rumble(14);
        if (inPhase() >= cd(CIRCLE_T)) {
            spot.copy(feet(prey));
            phase(Phase.HEAVE);
        }
    }

    /** 1초 땅이 들썩: 그 자리(들썩이기 시작할 때 대상의 발)에 붙어 있다가 솟는다. 이때 비키거나 뛰면 빗나감. */
    private void heave() {
        state("peek");
        moveToward(new Vector(spot.getX(), pos.getY(), spot.getZ()), 0.45);
        keepOut();
        int t = inPhase();
        float bob = 1f + 0.12f * (float) Math.sin(t * 1.4);
        body.squash(1.08f, bob, 1.08f, 1);
        dirt(1, 0.45);
        if (t % 5 == 0) groundShake();
        rumble(8);
        if (t < HEAVE) return;
        body.squash(1f, 1f, 1f, 2);
        sound("burst", Sound.BLOCK_ROOTED_DIRT_BREAK, 0.55f, 20);
        burst();
        state("rise");
        // 솟구침: 대상이 아직 그 자리에 발을 딛고 있으면 발목을 잡는다(비켰거나 떠 있거나 돌바닥으로 피했으면 빗나가 드러난 채 잠깐 멈춤)
        if (standing(prey) && !blocked(prey)) {
            Vector f = feet(prey);
            double off = Math.hypot(f.getX() - spot.getX(), f.getZ() - spot.getZ());
            double up = prey.getLocation().getY() - game.mobs.flow.floor(f.getX(), f.getZ());
            if (off <= GRAB_R && up <= JUMP_CLEAR) {
                game.players.root(prey, 2, "g24");
                PState s = game.players.get(prey);
                // 묶임이 실제로 걸렸을 때만 쥔 것(같은 계열 면역 중이면 빗나간 것으로)
                if (s.rootUntil > now()) {
                    spot.copy(f);
                    sound("grab", Sound.ENTITY_SLIME_ATTACK, 0.6f, 16);
                    grabbed = true;
                    phase(Phase.HOLD);
                    return;
                }
            }
        }
        phase(Phase.RISE);
    }

    /** 빗나간 솟구침: 얼굴 전체가 드러난 채 잠깐(약점). */
    private void rise() {
        hold();
        if (standing(prey)) face(chest(prey));
        pitch = 0;
        if (inPhase() >= EXPOSED) phase(Phase.DIVE);
    }

    /** 발목을 1.5초 쥐고 있다가 아래에서 박치기로 띄움. 묶임은 쥔 동안만 매 틱 이어 건다. */
    private void holdAnkle() {
        hold();
        state("rise");
        if (!standing(prey)) {
            phase(Phase.DIVE);
            return;
        }
        Vector f = feet(prey);
        if (Math.hypot(f.getX() - spot.getX(), f.getZ() - spot.getZ()) > LOSE_R) {
            // 다른 것에 끌려가거나 밀려나 손에서 빠짐
            phase(Phase.DIVE);
            return;
        }
        int t = inPhase();
        // 사람 발 바로 앞, 얼굴로 올려다봄
        moveToward(new Vector(f.getX(), pos.getY(), f.getZ()).add(toward(prey).multiply(-0.35)), 0.3);
        face(chest(prey));
        pitch = 0;
        if (t < GRAB) {
            game.players.root(prey, 2, "g24");
            return;
        }
        hit(prey, DAMAGE, "cause_g24");
        knock(prey, toward(prey), 0.08, LAUNCH);
        sound("butt", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 16);
        body.squash(0.85f, 1.3f, 0.85f, 2);
        phase(Phase.DIVE);
    }

    /** 잠깐 드러난 채 있다가(약점) 다시 땅속으로. */
    private void dive() {
        hold();
        if (inPhase() == 3) body.squash(1f, 1f, 1f, 4);
        if (inPhase() < 12) {
            state("rise");
            return;
        }
        burst();
        calm();
    }

    private void calm() {
        mark(null);
        prey = null;
        grabbed = false;
        readyAt = age + cd(COOLDOWN);
        state("");
        phase(Phase.PROWL);
    }

    // ------------------------------------------------------------------ 도우미

    /** 사람 발 자리(블록). */
    private static Vector feet(Player p) {
        return p.getLocation().toVector();
    }

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    /** to 쪽으로 수평 최대 v 블록/틱. */
    private void moveToward(Vector to, double v) {
        Vector d = to.clone().subtract(pos).setY(0);
        double l = d.length();
        vel.copy(l < 1e-6 ? d : d.multiply(Math.min(v, l) / l));
    }

    /** 중앙 원 안쪽 마른 돌바닥 밑: 못 들어감. */
    static boolean blocked(double x, double z) {
        double dx = x - CX, dz = z - CZ;
        return dx * dx + dz * dz < KEEP_OUT * KEEP_OUT;
    }

    private static boolean blocked(Player p) {
        Location l = p.getLocation();
        return blocked(l.getX(), l.getZ());
    }

    /** 원 밖(돌바닥이 아닌 땅)에 선 가장 가까운 사람. 없으면 null. */
    private Player reachable() {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (blocked(l.getX(), l.getZ())) continue;
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ(), d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** 다음 자리가 중앙 원 안이면 원 쪽으로 가는 성분을 지움(둘레를 따라 미끄러짐). 이미 안이면 바깥으로. */
    private void keepOut() {
        double nx = pos.getX() + vel.getX(), nz = pos.getZ() + vel.getZ();
        if (!blocked(nx, nz)) return;
        double ox = pos.getX() - CX, oz = pos.getZ() - CZ, r = Math.hypot(ox, oz);
        if (r < 1e-3) {
            vel.setX(0.1).setZ(0);
            return;
        }
        ox /= r;
        oz /= r;
        double inward = -(vel.getX() * ox + vel.getZ() * oz);
        if (inward > 0) {
            vel.setX(vel.getX() + ox * inward);
            vel.setZ(vel.getZ() + oz * inward);
        }
        if (r < KEEP_OUT) {
            vel.setX(vel.getX() + ox * 0.08);
            vel.setZ(vel.getZ() + oz * 0.08);
        }
    }

    /** 바닥 블록 조각(흙 튐). every 틱마다. */
    private void dirt(int every, double spread) {
        if (!every(every)) return;
        BlockData bd = floorBlock();
        if (bd == null) return;
        Location l = new Location(game.world, pos.getX(), floor() + 0.05, pos.getZ());
        game.world.spawnParticle(Particle.BLOCK, l, 4, spread, 0.04, spread, 0.0, bd);
    }

    private void burst() {
        BlockData bd = floorBlock();
        if (bd == null) return;
        Location l = new Location(game.world, pos.getX(), floor() + 0.1, pos.getZ());
        game.world.spawnParticle(Particle.BLOCK, l, 28, 0.45, 0.15, 0.45, 0.2, bd);
    }

    /** 들썩: 대상 발밑 둘레로 조각이 튐. */
    private void groundShake() {
        BlockData bd = floorBlock();
        if (bd == null) return;
        Location l = new Location(game.world, spot.getX(), floor() + 0.05, spot.getZ());
        game.world.spawnParticle(Particle.BLOCK, l, 10, 0.6, 0.05, 0.6, 0.05, bd);
    }

    private BlockData floorBlock() {
        try {
            BlockData bd = game.world.getBlockAt((int) Math.floor(pos.getX()), (int) Math.floor(floor() - 0.5), (int) Math.floor(pos.getZ()))
                    .getBlockData();
            return bd.getMaterial().isAir() ? null : bd;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 대상 한 사람에게만, 그 사람 자리에서(위치 없음) 낮은 진동. */
    private void rumble(int every) {
        if (prey == null || !prey.isOnline() || age < nextRumble || game.director.silent()) return;
        Props.sound(prey, "gabe.g24.rumble", Sound.BLOCK_SCULK_SENSOR_CLICKING, 1f, 0.95f + game.random.nextFloat() * 0.1f, 0.5f);
        nextRumble = age + every;
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        // 땅속: 땅 위로 나온 이마와 귀만 맞음. 솟았을 때는 얼굴 전체
        if ("rise".equals(state)) return List.of(new double[] {pos.getX(), pos.getY() + 0.55, pos.getZ(), FACE_R});
        if ("peek".equals(state)) return List.of(new double[] {pos.getX(), pos.getY() + 0.06, pos.getZ(), FACE_R});
        return List.of(new double[] {pos.getX(), pos.getY() + 0.14, pos.getZ(), EAR_R});
    }

    @Override
    public Vector head() {
        return "rise".equals(state) ? pos.clone().add(new Vector(0, 0.55, 0)) : pos;
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (grabbed ? " grabbed" : "") + (goal != null ? " goal=" + goal.getName() : "");
    }
}
