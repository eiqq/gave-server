package kr.gave.mob.sp;

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
import kr.gave.world.Plaza;

/**
 * 하늘의 거인 묶음(G77, G71, G119, G97, G91, G121, G126, sp_b10)이 같이 쓰는 작은 도구. 종 파일 안 도우미라 다른 묶음은 쓰지 않는다.
 *
 * <p>거대한 하늘 몸의 공통 꼴: Gabe.pos 는 하늘 높이에 두고 아래위 움직임(밟기, 내려오기)은 body.offset 보간으로만 보인다(서버는 {@link Glide} 로
 * 같은 값을 안다). pos 를 땅까지 내리면 컬링 상자 높이 때문에 닻 엔티티가 땅 밑 깊이(y 48 아래) 들어가 몸째 안 보인다(G28 주석과 같은 까닭).
 */
final class G77Sky {

    private G77Sky() {
    }

    /** 직선 보간 하나(디스플레이 보간과 같은 값을 서버도 알게). */
    static final class Glide {
        double from, to;
        long t0;
        int n = 1;

        void set(double cur, double target, long now, int ticks) {
            from = cur;
            to = target;
            t0 = now;
            n = Math.max(1, ticks);
        }

        /** 지금 값에서 target 으로. */
        void go(double target, long now, int ticks) {
            set(at(now), target, now, ticks);
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

    /** 가운데(보급 상자, 트럭) 자리. */
    static Vector center() {
        return new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5);
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
            ring.setDisplayWidth(r * 2.4f);
            ring.setDisplayHeight(3f);
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
     * 바닥이나 허공에 이 종 모델 디스플레이 하나(연출 예산, 우선 KEY): 그림자 띠, 예고 원, 우박처럼 몸과 따로 움직이는 것. life 틱 뒤 저절로 지워진다.
     * box = 컬링 상자 한 변(그림 크기만큼 주지 않으면 큰 판이 보였다 안 보였다 함: Tele 의 게임 안 확인). 못 낳으면 null.
     */
    static ItemDisplay fx(Game game, Location at, String model, String state, Matrix4f m, int life, float box) {
        ItemDisplay d = game.shotFx.spawn(at, "fx_quad", 0, 0, m, Display.Billboard.FIXED, false, life, ShotFx.KEY);
        if (d == null) return null;
        d.setItemStack(Body.item(model, state, new LookState(), game.worldTime()));
        d.setBrightness(new Display.Brightness(12, 12));
        d.setTeleportDuration(2);
        d.setViewRange(3f);
        if (box > 0) {
            d.setDisplayWidth(box);
            d.setDisplayHeight(Math.max(3f, box * 0.4f));
        }
        return d;
    }

    /** 큰 몸 아래쪽 면 밝기(B2 와 같은 값: 블록 13, 하늘 10). 아래에서 올려다보는 면이 방향 그늘로 시커멓지 않게. */
    static final Display.Brightness UNDER = new Display.Brightness(13, 10);

    /** 몸의 닻과 지금 붙은 부품 모두 밝기를 올린다(build 끝에서, 나중에 붙인 부품은 {@link #bright(ItemDisplay)}). */
    static void bright(Body b) {
        if (b == null) return;
        bright(b.anchor);
        for (ItemDisplay d : b.parts) bright(d);
    }

    static void bright(ItemDisplay d) {
        if (d != null && d.isValid()) d.setBrightness(UNDER);
    }

    /**
     * 컬링 상자 높이: 닻 엔티티가 바닥 1블록 아래(땅 밑 깊이 들어가면 클라가 그리지 않음, 묶음 8 G107)에 서고 위로는 몸 높이까지. pos 가 바닥 + alt 일 때.
     */
    static float boxH(double alt) {
        return (float) (2 * alt + 2);
    }

    static void kill(ItemDisplay d) {
        if (d != null && d.isValid()) d.remove();
    }

    /**
     * 따로 선 디스플레이에 이 종 모델을 놓는 변환: yaw 로 돌리고 (sx, sy, sz) 배 x 줄여 구운 배율(Look.scale), 아이템 디스플레이 반 바퀴(Body 와 같게:
     * 모델 +Z 가 yaw 쪽).
     */
    static Matrix4f lay(String model, float yawDeg, float sx, float sy, float sz) {
        float k = Look.scale(model);
        return new Matrix4f().rotateY((float) Math.toRadians(-yawDeg)).scale(sx * k, sy * k, sz * k).rotateY((float) Math.PI);
    }
}
