package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.mob.Role;
import kr.gave.player.PState;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 묶음 11(하늘의 거인 2와 솟는 것: G148, G147, G137, G152, G153, G128, G154, sp_b11)이 같이 쓰는 작은 도구. 종 파일 안 도우미라 다른 묶음은 쓰지 않는다.
 *
 * <p>거대한 몸의 공통 꼴: 몸은 {@link Body#carrier}(빈 닻) + 부품. 빈 닻과 부품은 컬링 상자가 0 이라(바닐라 디스플레이: 폭이나 높이가 0 이면 컬링 안 함)
 * 하늘 40블록이나 땅 위 12블록까지 뻗은 몸이 화면 가장자리에서 사라지지 않는다. 몸 자리(Gabe.pos)는 그 종이 정한 기준점(종 아가리, 저울 굴대, 추 매단 점,
 * 원반 가운데, 목 뿌리, 트럭 가운데)이고 부품은 그 점에서 Part.local 로 놓는다.
 */
final class G148Sky {

    private G148Sky() {
    }

    /** 직선 보간 하나(디스플레이 보간과 같은 값을 서버도 알게). */
    static final class Glide {
        double from, to;
        long t0;
        int n = 1;

        void go(double target, long now, int ticks) {
            double cur = at(now);
            from = cur;
            to = target;
            t0 = now;
            n = Math.max(1, ticks);
        }

        void snap(double v) {
            from = to = v;
            t0 = Long.MIN_VALUE / 4;
        }

        double at(long now) {
            double f = (now - t0) / (double) n;
            return f >= 1 ? to : f <= 0 ? from : from + (to - from) * f;
        }

        boolean done(long now) {
            return now - t0 >= n;
        }
    }

    /** 몸 기준 (x 왼쪽, y 위, z 앞) 을 세계 축으로(yaw 만, 마크 약속 yaw 0 = +Z). */
    static Vector local(double x, double y, double z, float yawDeg) {
        double a = Math.toRadians(yawDeg), c = Math.cos(a), s = Math.sin(a);
        return new Vector(x * c - z * s, y, x * s + z * c);
    }

    /** 세계 축 차이(dx, dz) 를 몸 기준 (x 왼쪽, z 앞) 으로. */
    static double[] toLocal(double dx, double dz, float yawDeg) {
        double a = Math.toRadians(yawDeg), c = Math.cos(a), s = Math.sin(a);
        return new double[] {dx * c + dz * s, -dx * s + dz * c};
    }

    /** 서 있는 사람들의 무게 중심(바닥 높이 무시). 없으면 null. */
    static Vector centroid(Game game) {
        List<Player> ps = game.mobs.standing();
        if (ps.isEmpty()) return null;
        double x = 0, z = 0;
        for (Player p : ps) {
            x += p.getLocation().getX();
            z += p.getLocation().getZ();
        }
        return new Vector(x / ps.size(), 0, z / ps.size());
    }

    /** 반경 r 안에 서 있는 사람이 가장 많은 사람(같으면 from 에 가까운 쪽). 아무도 없으면 null. 사람 수 30 이면 900 번 거리 계산. */
    static Player densest(Game game, double r, Vector from) {
        List<Player> ps = game.mobs.standing();
        Player best = null;
        int bestN = -1;
        double bestD = Double.MAX_VALUE, r2 = r * r;
        for (Player p : ps) {
            Location l = p.getLocation();
            int n = 0;
            for (Player q : ps) {
                Location m = q.getLocation();
                double dx = m.getX() - l.getX(), dz = m.getZ() - l.getZ();
                if (dx * dx + dz * dz <= r2) n++;
            }
            double d = from == null ? 0 : Math.hypot(l.getX() - from.getX(), l.getZ() - from.getZ());
            if (n > bestN || n == bestN && d < bestD) {
                best = p;
                bestN = n;
                bestD = d;
            }
        }
        return best;
    }

    /** 자리 (x, z) 의 반경 r 안 서 있는 사람 수. */
    static int countNear(Game game, double x, double z, double r) {
        int n = 0;
        double r2 = r * r;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - x, dz = l.getZ() - z;
            if (dx * dx + dz * dz <= r2) n++;
        }
        return n;
    }

    /** 사람들이 지키는 가운데: 트럭이 서 있으면 트럭 가운데, 아니면 보급 상자 칸 가운데(y 0). */
    static Vector center() {
        if (Truck.live()) {
            Vector c = Truck.center();
            return new Vector(c.getX(), 0, c.getZ());
        }
        return new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5);
    }

    /** 서 있는 트럭(원정이 꺼져 있으면 null). */
    static Truck truck() {
        return Truck.get();
    }

    /** 수평 거리. */
    static double flat(Vector a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    static double flat(Vector a, Vector b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** 감독이 곱한 체력 배율(부위 체력에 곱함). */
    static double hpK(Gabe g) {
        return g.sp.hp <= 0 ? 1 : Math.max(0.2, g.maxHp / g.sp.hp);
    }

    /** 땅울림 고리(반경 r)와 흙먼지: 셰이더 충격파 고리(ShotFx RING) 하나와 연기 둘. */
    static void quake(Game game, Location at, float r) {
        Location gr = ShotFx.ground(at, 3);
        ItemDisplay ring = game.shotFx.spawn(gr.clone().add(0, 0.15, 0), "fx_flat", ShotFx.RING, 0, ShotFx.scale(2f), Display.Billboard.FIXED, false,
                12, ShotFx.KEY);
        if (ring != null) {
            ring.setDisplayWidth(0);
            ring.setDisplayHeight(0);
        }
        game.shotFx.grow(ring, ShotFx.scale(r / 0.4f), 8);
        for (int i = 0; i < 2; i++) {
            Location c = gr.clone().add((i == 0 ? 1 : -1) * r * 0.3, 0.8, (i == 0 ? -1 : 1) * r * 0.2);
            ItemDisplay sm = game.shotFx.spawn(c, "fx_quad", ShotFx.SMOKE, 1, ShotFx.scale(3f), Display.Billboard.CENTER, false, 50, ShotFx.EXTRA);
            game.shotFx.grow(sm, ShotFx.scale(r * 0.9f), 44);
            game.shotFx.drift(sm, c.clone().add(0, 2, 0), 44);
        }
    }

    /**
     * 바닥이나 허공에 이 종 모델 디스플레이 하나(연출 예산, 우선 KEY): 예고 원, 떨어지는 덩이처럼 몸과 따로 움직이는 것. life 틱 뒤 저절로 지워진다.
     * 컬링 상자는 0(가장자리에서 사라지지 않게). 못 낳으면 null.
     */
    static ItemDisplay fx(Game game, Location at, String model, String state, Matrix4f m, int life) {
        ItemDisplay d = game.shotFx.spawn(at, "fx_quad", 0, 0, m, Display.Billboard.FIXED, false, life, ShotFx.KEY);
        if (d == null) return null;
        d.setItemStack(Body.item(model, state, new LookState(), game.worldTime()));
        d.setBrightness(new Display.Brightness(12, 12));
        d.setTeleportDuration(2);
        d.setViewRange(3f);
        d.setDisplayWidth(0);
        d.setDisplayHeight(0);
        return d;
    }

    static void kill(ItemDisplay d) {
        if (d != null && d.isValid()) d.remove();
    }

    /**
     * 따로 선 디스플레이에 이 종 모델을 놓는 변환: yaw 로 돌리고 (sx, sy, sz) 배 x 줄여 구운 배율(Look.scale), 아이템 디스플레이 반 바퀴(Body 와 같게).
     */
    static Matrix4f lay(String model, float yawDeg, float sx, float sy, float sz) {
        float k = Look.scale(model);
        return new Matrix4f().rotateY((float) Math.toRadians(-yawDeg)).scale(sx * k, sy * k, sz * k).rotateY((float) Math.PI);
    }

    /** 다른 게이브를 고를 때 빼는 것: 보스, 자기 자신, 죽은 것. */
    static boolean other(Gabe me, Gabe g) {
        return g != me && !g.dead && !g.sp.is(Role.BOSS);
    }

    /** 맞은 점에서 가장 가까운 구의 번호(구 배열의 [4] 칸, 없으면 -1). */
    static int partAt(List<double[]> sph, Vector at) {
        if (at == null || sph == null) return -1;
        double best = Double.MAX_VALUE;
        int part = -1;
        for (double[] s : sph) {
            double dx = s[0] - at.getX(), dy = s[1] - at.getY(), dz = s[2] - at.getZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz) - s[3];
            if (d < best) {
                best = d;
                part = (int) s[4];
            }
        }
        return part;
    }

    /**
     * 큰 몸 부품의 밝기를 (블록 13, 하늘 10)으로(보스 B2 와 같게): 아이템 모델의 아랫면은 그늘이 져서 4막 밤에 아래를 보는 큰 얼굴이 검게 묻힌다.
     * 그림자, 예고 판에는 쓰지 않는다.
     */
    static void bright(Body.Part... ps) {
        for (Body.Part p : ps) if (p != null && p.d != null) p.d.setBrightness(BRIGHT);
    }

    static final Display.Brightness BRIGHT = new Display.Brightness(13, 10);

    /** 사람이 지금 판에서 서 있나(null, 나감, 쓰러짐 빼고). */
    static boolean up(Game game, Player p) {
        if (p == null || !p.isOnline()) return false;
        PState s = game.players.get(p);
        return s != null && s.standing();
    }

    /** 서 있는 사람 목록 사본(돌면서 hit 해도 안전하게). */
    static List<Player> standing(Game game) {
        return new ArrayList<>(game.mobs.standing());
    }

    /** 판이 웨이브 중인가(쉬는 시간, 판 끝 연출에는 사람을 건드리지 않음). */
    static boolean wave(Game game) {
        return game.state == Game.State.WAVE;
    }
}
