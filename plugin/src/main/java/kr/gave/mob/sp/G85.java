package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G85 땅 금 입 게이브(함정, 돌격, 목표 압박, 2막). 쐐기 얼굴이 땅에 반쯤 묻힌 채 걸음 빠르기로 사람을 향해 일직선으로 땅을 가르며 오고(1초에
 * 15도씩만 틈), 지나간 자리에 길이 8블록의 금이 따라온다. 금이 사람 발밑을 지나는 순간 양 벽의 얼굴들이 솟아 맞물린다.
 *
 * <p>도감: 금이 닫힐 때 그 위 사람 14, 묶임 1초, 넘어짐. 쐐기 얼굴 들이받기 7. 금이 트럭 밑을 지나며 닫히면 바퀴를 물어 트럭 체력을 깎는다.
 * 맞는 곳은 쐐기 얼굴뿐. 신호는 땅이 쩍쩍 갈라지는 소리와 흙을 가르는 쐐기 얼굴, 뒤따르는 금 줄.
 *
 * <p>바꾼 것: 금은 최근 40틱 지나온 점들 가운데 가장 오래된 점에서 쐐기까지 곧은 띠로 그리고 판정한다(1초에 15도만 트니 거의 곧다).
 * 넘어짐은 짧은 위로 밀침과 화면 흔들림으로. 트럭을 무는 피해는 트럭 무는 값(biteDamage) 두 배를 40틱에 한 번.
 *
 * <p>팩 계약(tools/gabe/sp/g85.py): 몸 gave:gabe/g85(상태 ""), 부품 gave:gabe/g85_crack(상태 "", "shut", 세계 축, 길이 8). 소리 crack(32),
 * crunch(24), ram(16). 글귀 cause_g85, cause_g85_ram.
 */
public final class G85 extends Gabe {

    static final double SPEED = 0.215, HOVER = 0.25, FACE_R = 0.42;
    /** 도는 빠르기(도/틱 = 1초에 15도), 사람에게서 멀 때(40블록 넘게) 도는 빠르기. */
    static final float TURN = 0.75f, TURN_FAR = 3f;
    static final double FAR = 40;
    /** 금: 기록 간격(틱), 기록 수(40틱), 최대 길이, 판정 반 폭(금 반 폭 0.75 + 사람 몸 0.3), 땅 위 높이 한계. */
    static final int TRAIL_EVERY = 2, TRAIL_N = 20;
    static final double CRACK_MAX = 8, CRACK_HALF = 1.05, ON_GROUND = 0.6, CRACK_FROM = 0.5;
    static final double CLOSE = 14, RAM = 7;
    static final int ROOT = 20, CLOSE_CD = 40, RAM_CD = 20, SHUT_TICKS = 12, TRUCK_CD = 40, CRACK_SOUND = 14;

    private final double[] tx = new double[TRAIL_N], tz = new double[TRAIL_N];
    private int trailN, trailAt;
    private float heading;
    private int ramAt = -100, truckAt = -100, shutUntil, nextCrack;
    private final Map<UUID, Integer> closedAt = new HashMap<>();
    private Body.Part crack;
    private final Vector tail = new Vector(), front = new Vector();
    private double crackLen;

    public G85(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = HOVER;
        ghost = true; // 땅밑(벽 무시)
        baseSpeed = SPEED;
        ragKick = 0.6;
    }

    @Override
    protected void build() {
        // 금 띠가 8블록 뒤까지 늘어지므로 컬링 상자를 넓게
        body = Body.of(this, "gabe/g85", 18f, 2f);
        crack = body.attach("gabe/g85_crack");
        if (crack != null) crack.follow = false;
    }

    @Override
    protected void spawned() {
        Vector goal = goal();
        heading = goal == null ? yaw : yawTo(goal);
        yaw = heading;
        nextCrack = age + game.random.nextInt(CRACK_SOUND);
        record();
        tail.copy(pos);
        front.copy(pos);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        Vector goal = goal();
        if (goal != null) {
            float want = yawTo(goal);
            double d = Math.hypot(goal.getX() - pos.getX(), goal.getZ() - pos.getZ());
            float rate = d > FAR ? TURN_FAR : TURN * (float) Math.max(0.6, speed() / baseSpeed);
            float diff = wrap(want - heading);
            heading = wrap(heading + Math.max(-rate, Math.min(rate, diff)));
        }
        double r = Math.toRadians(heading);
        vel.setX(-Math.sin(r) * speed()).setY(0).setZ(Math.cos(r) * speed());
        yaw = heading;
        if (age % TRAIL_EVERY == 0) record();
        geometry();
        if ((age & 1) == 0) poseCrack(2);
        if (crack != null) crack.state(age < shutUntil ? "shut" : "");
        if (age >= nextCrack) {
            sound("crack", Sound.BLOCK_ROOTED_DIRT_BREAK, 0.6f, 32);
            nextCrack = age + CRACK_SOUND + game.random.nextInt(6);
        }
        ram();
        closeOn();
        truck();
    }

    /** 노릴 곳: 트럭 노림이면 트럭 가운데, 아니면 가장 가까운 사람(없으면 흐름장 쪽). */
    private Vector goal() {
        Truck t = game.trip == null ? null : game.trip.truck();
        if (t != null && t.alive() && t.isSeeker(this)) return Truck.center();
        Player p = nearest(400);
        if (p != null) return p.getLocation().toVector();
        Vector d = game.mobs.flow.dir(pos);
        return d == null ? null : pos.clone().add(d.clone().multiply(8));
    }

    private float yawTo(Vector at) {
        return (float) Math.toDegrees(Math.atan2(-(at.getX() - pos.getX()), at.getZ() - pos.getZ()));
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a > 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private void record() {
        tx[trailAt] = pos.getX();
        tz[trailAt] = pos.getZ();
        trailAt = (trailAt + 1) % TRAIL_N;
        if (trailN < TRAIL_N) trailN++;
    }

    /** 금의 앞(쐐기 바로 뒤)과 꼬리(40틱 전 자리, 8블록까지). */
    private void geometry() {
        int oldest = trailN < TRAIL_N ? 0 : trailAt;
        double ox = tx[oldest], oz = tz[oldest];
        double dx = ox - pos.getX(), dz = oz - pos.getZ(), l = Math.hypot(dx, dz);
        if (l < 1e-3) {
            double hr = Math.toRadians(heading);
            dx = Math.sin(hr);
            dz = -Math.cos(hr);
            l = 0;
        } else {
            dx /= l;
            dz /= l;
        }
        double len = Math.min(CRACK_MAX, l);
        front.setX(pos.getX() + dx * CRACK_FROM).setZ(pos.getZ() + dz * CRACK_FROM);
        tail.setX(pos.getX() + dx * Math.max(CRACK_FROM, len)).setZ(pos.getZ() + dz * Math.max(CRACK_FROM, len));
        front.setY(game.mobs.flow.floor(front.getX(), front.getZ()));
        tail.setY(game.mobs.flow.floor(tail.getX(), tail.getZ()));
        crackLen = Math.max(0, len - CRACK_FROM);
    }

    private void poseCrack(int ticks) {
        if (crack == null) return;
        if (crackLen < 0.3) {
            crack.pose(new Matrix4f().translate(0f, -2f, 0f).scale(0.01f), ticks);
            return;
        }
        double mx = (front.getX() + tail.getX()) / 2, mz = (front.getZ() + tail.getZ()) / 2;
        double my = Math.max(front.getY(), tail.getY()) + 0.03;
        float th = (float) Math.atan2(front.getX() - tail.getX(), front.getZ() - tail.getZ());
        crack.pose(new Matrix4f().translate((float) (mx - pos.getX()), (float) (my - pos.getY()), (float) (mz - pos.getZ())).rotateY(th)
                .scale(1f, 1f, (float) (crackLen / CRACK_MAX)), ticks);
    }

    /** 쐐기 얼굴 들이받기. */
    private void ram() {
        if (age - ramAt < cd(RAM_CD)) return;
        for (Player p : game.mobs.standing()) {
            if (!touches(p, 0.2)) continue;
            double r = Math.toRadians(heading);
            hit(p, RAM, "cause_g85_ram");
            knock(p, new Vector(-Math.sin(r), 0, Math.cos(r)), 0.5, 0.2);
            sound("ram", Sound.BLOCK_ROOTED_DIRT_BREAK, 0.6f, 16);
            ramAt = age;
            return;
        }
    }

    /** 금 위(발이 땅에 닿은)에 선 사람: 양 벽 얼굴이 솟아 맞물림. */
    private void closeOn() {
        if (crackLen < 0.5) return;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double fy = game.mobs.flow.floor(l.getX(), l.getZ());
            if (l.getY() - fy > ON_GROUND) continue;
            if (flatSeg(l.getX(), l.getZ()) > CRACK_HALF) continue;
            Integer at = closedAt.get(p.getUniqueId());
            if (at != null && age - at < cd(CLOSE_CD)) continue;
            closedAt.put(p.getUniqueId(), age);
            if (closedAt.size() > 40) closedAt.entrySet().removeIf(e -> age - e.getValue() > CLOSE_CD * 3);
            shutUntil = age + SHUT_TICKS;
            hit(p, CLOSE, "cause_g85");
            if (standing(p)) {
                game.players.root(p, ROOT, "g85");
                // 넘어짐: 금 바깥쪽으로 비틀, 화면이 꺾임
                Vector side = side(l.getX(), l.getZ());
                knock(p, side, 0.12, 0.32);
                Guns.kick(p, (game.random.nextFloat() - 0.5f) * 10f, 9f);
            }
            soundAt(l, "crunch", Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.8f, 24);
        }
    }

    /** 금이 트럭 발자국 밑을 지나며 닫힘: 바퀴를 문다. */
    private void truck() {
        if (age - truckAt < cd(TRUCK_CD) || crackLen < 0.5) return;
        Truck t = game.trip == null ? null : game.trip.truck();
        if (t == null || !t.alive()) return;
        double l = front.distance(tail);
        for (double s = 0; s <= l; s += 0.8) {
            double k = l < 1e-6 ? 0 : s / l;
            double x = front.getX() + (tail.getX() - front.getX()) * k, z = front.getZ() + (tail.getZ() - front.getZ()) * k;
            if (Truck.dist(x, z) > 0.4) continue;
            truckAt = age;
            shutUntil = age + SHUT_TICKS;
            t.hit(t.biteDamage(sp) * 2, this, "cause_g85");
            soundAt(new Location(game.world, x, front.getY(), z), "crunch", Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.8f, 24);
            return;
        }
    }

    /** (x, z) 에서 금(앞-꼬리 선분)까지 수평 거리. */
    private double flatSeg(double x, double z) {
        double ax = front.getX(), az = front.getZ(), bx = tail.getX() - ax, bz = tail.getZ() - az;
        double l2 = bx * bx + bz * bz;
        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - ax) * bx + (z - az) * bz) / l2));
        return Math.hypot(ax + bx * t - x, az + bz * t - z);
    }

    /** 금에서 (x, z) 쪽 수평 단위 방향(금 바깥). */
    private Vector side(double x, double z) {
        double bx = tail.getX() - front.getX(), bz = tail.getZ() - front.getZ();
        double l = Math.hypot(bx, bz);
        if (l < 1e-6) return new Vector(1, 0, 0);
        Vector n = new Vector(-bz / l, 0, bx / l);
        if ((x - front.getX()) * n.getX() + (z - front.getZ()) * n.getZ() < 0) n.multiply(-1);
        return n;
    }

    private void soundAt(Location at, String name, Sound fb, float fbPitch, double range) {
        if (game.director.silent()) return;
        kr.gave.Props.soundAround(at, "gabe." + sp.key() + "." + name, fb, 1f, 0.94f + game.random.nextFloat() * 0.12f, fbPitch, range, -1);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(1);
        out.add(new double[] {pos.getX(), pos.getY() + 0.1, pos.getZ(), FACE_R});
        return out;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 금 띠가 사체 조각으로 구르지 않게 먼저 뗀다
        if (crack != null) crack.remove();
        crack = null;
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + String.format(java.util.Locale.ROOT, " head=%.0f crack=%.1f", heading, crackLen) + (age < shutUntil ? " shut" : "");
    }
}
