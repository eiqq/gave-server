package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 묶음 12(땅의 거인 3과 트럭: G141 G142 G143 G149 G155 G146)가 같이 쓰는 도우미. 공용 엔진이 아니라 이 묶음 종 파일 안의 것이다.
 *
 * <ul>
 * <li>{@link #at}: 몸 틀 점(블록, +X 몸 왼쪽, +Y 위, +Z 앞) → 세계 점. {@link #fwd}, {@link #left}, {@link #faceFlat}, {@link #yawOf}.</li>
 * <li>{@link #middle}: 지킬 한가운데(트럭이 서 있으면 트럭 가운데, 아니면 중앙 원 가운데).</li>
 * <li>{@link #ring}, {@link #warn}, {@link #stomp}: 바닥 흙 고리, 빨간 예고 고리(ShotFx 셰이더 모양, 발광 상자 아님), 거인 걸음.</li>
 * <li>{@link Lobs}: 몸에서 던지는 덩이(부품 디스플레이를 포물선으로 옮김, 떨어진 자리에서 반경 피해).</li>
 * </ul>
 */
final class G141Kit {

    private G141Kit() {
    }

    /** 몸 틀 점 → 세계 점(yaw 만). */
    static Vector at(Gabe g, double x, double y, double z) {
        double r = Math.toRadians(g.yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        return new Vector(g.pos.getX() + fz * x + fx * z, g.pos.getY() + y, g.pos.getZ() - fx * x + fz * z);
    }

    /** 앞(수평 단위). */
    static Vector fwd(Gabe g) {
        double r = Math.toRadians(g.yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** 몸 왼쪽(수평 단위). */
    static Vector left(Gabe g) {
        double r = Math.toRadians(g.yaw);
        return new Vector(Math.cos(r), 0, Math.sin(r));
    }

    /** 수평 방향 d 를 보는 yaw(도). */
    static float yawOf(Vector d) {
        return (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
    }

    /** 보는 방향만 수평으로(거인은 몸 전체가 기울지 않게 pitch 0). */
    static void faceFlat(Gabe g, Vector at) {
        double dx = at.getX() - g.pos.getX(), dz = at.getZ() - g.pos.getZ();
        if (dx * dx + dz * dz < 1e-8) return;
        g.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        g.pitch = 0;
    }

    /** 수평으로 maxDeg 까지만 돌림. 다 돌았으면 true. */
    static boolean turnFlat(Gabe g, Vector at, float maxDeg) {
        double dx = at.getX() - g.pos.getX(), dz = at.getZ() - g.pos.getZ();
        if (dx * dx + dz * dz < 1e-8) return true;
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float d = wrap(want - g.yaw);
        g.pitch = 0;
        if (Math.abs(d) <= maxDeg) {
            g.yaw = want;
            return true;
        }
        g.yaw = wrap(g.yaw + Math.signum(d) * maxDeg);
        return false;
    }

    static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** 지킬 한가운데(바닥 높이): 트럭이 서 있으면 트럭 가운데, 아니면 중앙 원 가운데. */
    static Vector middle() {
        if (Truck.live()) return Truck.center();
        return new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
    }

    /** 수평 거리. */
    static double hdist(Vector a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    static double hdist(Vector a, Vector b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 바닥 흙 고리(충격파, ShotFx 종류 RING): 반지름 r, life 틱. 예산이 차면 안 그림. */
    static void ring(Gabe g, Vector c, double r, int life) {
        if (!g.game.shotFx.room(ShotFx.EXTRA)) return;
        double y = g.game.mobs.flow.floor(c.getX(), c.getZ()) + 0.06;
        g.game.shotFx.spawn(new Location(g.game.world, c.getX(), y, c.getZ()), "fx_flat", ShotFx.RING, 0, ShotFx.scale((float) (r * 2)),
            Display.Billboard.FIXED, false, life, ShotFx.EXTRA);
    }

    /** 빨간 예고 고리(가득 찬 쓰러짐 고리 모양): 반지름 r, life 틱. */
    static void warn(Gabe g, Vector c, double r, int life) {
        if (!g.game.shotFx.room(ShotFx.NORMAL)) return;
        double y = g.game.mobs.flow.floor(c.getX(), c.getZ()) + 0.02;
        g.game.shotFx.spawn(new Location(g.game.world, c.getX(), y, c.getZ()), "fx_flat", ShotFx.DOWN_RING, 0, ShotFx.scale((float) (r / 0.4)),
            Display.Billboard.FIXED, false, life, ShotFx.KEY);
    }

    /** 흙먼지 한 줌(ShotFx DUST, 빌보드): 크기 s, life 틱. */
    static void dust(Gabe g, Vector c, float s, int life) {
        if (!g.game.shotFx.room(ShotFx.EXTRA)) return;
        double y = g.game.mobs.flow.floor(c.getX(), c.getZ()) + 0.4;
        g.game.shotFx.spawn(new Location(g.game.world, c.getX(), y, c.getZ()), "fx_quad", ShotFx.DUST, 1, ShotFx.scale(s), Display.Billboard.CENTER,
            false, life, ShotFx.EXTRA);
    }

    /** 거인 걸음 한 번: 발 자리 foot(세계, y 는 바닥), 소리 반경 range, 화면 끄덕 반경 shakeR. */
    static void stomp(Gabe g, Vector foot, double range, double shakeR, float volume) {
        if (!g.game.director.silent())
            Props.soundAround(new Location(g.game.world, foot.getX(), foot.getY(), foot.getZ()), "sfx.stomp", Sound.ENTITY_RAVAGER_STEP, volume,
                0.8f + g.game.random.nextFloat() * 0.2f, 0.6f, range, -1);
        ring(g, foot, 1.8, 12);
        double r2 = shakeR * shakeR;
        for (Player p : g.game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - foot.getX(), dz = l.getZ() - foot.getZ();
            if (dx * dx + dz * dz > r2) continue;
            // 2026-11-12 사용자 '시야 각도를 바꾸는 게이브는 안 나오게': 거인 걸음 화면 끄덕 뺌(거인은 그대로 나옴)
        }
    }

    // ------------------------------------------------------------------ 던지는 덩이

    /** 날아가는 덩이 하나. */
    static final class Lob {
        final Body.Part part;
        final Vector from, to;
        final long start;
        final int len;
        final double arc;

        Lob(Body.Part part, Vector from, Vector to, long start, int len, double arc) {
            this.part = part;
            this.from = from;
            this.to = to;
            this.start = start;
            this.len = len;
            this.arc = arc;
        }

        Vector at(double t) {
            double k = Math.max(0, Math.min(1, t));
            Vector p = from.clone().multiply(1 - k).add(to.clone().multiply(k));
            p.setY(p.getY() + arc * 4 * k * (1 - k));
            return p;
        }
    }

    /** 떨어진 덩이(자리를 받아 피해를 줌). */
    interface Land {
        void land(Vector at);
    }

    /** 개체마다 하나: 부품 디스플레이(모델)를 포물선으로 옮기고 떨어지면 Land. 개체가 죽으면 clear(부품을 지움: 래그돌 조각이 되지 않게). */
    static final class Lobs {
        final List<Lob> list = new ArrayList<>(4);
        final String model;
        final int max;

        Lobs(String model, int max) {
            this.model = model;
            this.max = max;
        }

        /** from 에서 to 로 len 틱, 꼭대기 arc 블록. 부품을 못 만들면 false. */
        boolean toss(Gabe g, Vector from, Vector to, int len, double arc) {
            if (g.body == null || list.size() >= max) return false;
            Body.Part p = g.body.attach(model);
            if (p == null) return false;
            p.follow = false;
            p.pose(new Matrix4f().translate((float) (from.getX() - g.pos.getX()), (float) (from.getY() - g.pos.getY()), (float) (from.getZ() - g.pos.getZ())), 0);
            list.add(new Lob(p, from.clone(), to.clone(), g.now(), Math.max(4, len), arc));
            return true;
        }

        void tick(Gabe g, Land land) {
            long now = g.now();
            for (Iterator<Lob> it = list.iterator(); it.hasNext();) {
                Lob l = it.next();
                double t = (now - l.start) / (double) l.len;
                if (t >= 1) {
                    l.part.remove();
                    it.remove();
                    try {
                        land.land(l.to);
                    } catch (RuntimeException e) {
                        // 떨어짐 처리 실패는 그 덩이만 버림
                    }
                    continue;
                }
                Vector p = l.at(t + 1.0 / l.len);
                float spin = (float) ((now - l.start) * 0.5);
                l.part.pose(new Matrix4f().translate((float) (p.getX() - g.pos.getX()), (float) (p.getY() - g.pos.getY()), (float) (p.getZ() - g.pos.getZ()))
                    .rotateY(spin).rotateX(spin * 0.7f), 1);
            }
        }

        void clear() {
            for (Lob l : list) l.part.remove();
            list.clear();
        }
    }

    /** 자리 c 반경 r 안 서 있는 사람(수평, 높이 차 h 안). */
    static List<Player> within(Gabe g, Vector c, double r, double h) {
        List<Player> out = new ArrayList<>(4);
        for (Player p : g.game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
            if (dx * dx + dz * dz > r * r) continue;
            if (Math.abs(l.getY() - c.getY()) > h) continue;
            out.add(p);
        }
        return out;
    }
}
