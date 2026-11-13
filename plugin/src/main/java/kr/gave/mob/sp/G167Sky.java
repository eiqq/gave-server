package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 묶음 19(하늘의 거인과 일식 떼: G167, G169, G184, G192, G193, G196, G200, sp_b19)가 같이 쓰는 작은 도구. 종 파일 안 도우미라 다른 묶음은 쓰지 않는다.
 *
 * <p>거대한 하늘 몸의 공통 꼴: 몸은 {@link Body#carrier}(빈 닻) + 부품. 빈 닻과 부품은 컬링 상자가 0 이라 하늘 수십 블록에 뻗은 몸이 화면 가장자리에서
 * 사라지지 않는다. 아래를 보는 큰 얼굴 면은 아이템 셰이더의 방향 빛을 거의 못 받아 올려다보면 시커멓다(boss_b2 가 찾음): 그 부품만 밝기
 * {@link #FACE_LIGHT}(13, 10)로 메운다.
 */
final class G167Sky {

    private G167Sky() {
    }

    /** 아래를 보는 얼굴 부품의 밝기(몸 기본 10, 10). */
    static final Display.Brightness FACE_LIGHT = new Display.Brightness(13, 10);
    /** 스스로 빛나는 것(삼킨 등불을 품은 배, 빛줄기). 발광 테두리가 아니라 밝기만. */
    static final Display.Brightness GLOW = new Display.Brightness(15, 15);
    static final int FY = Plaza.FLOOR_Y;

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

    /** 사람들이 지키는 가운데: 트럭이 서 있으면 트럭 가운데, 아니면 보급 상자 칸 가운데(y 는 바닥). */
    static Vector center() {
        if (Truck.live()) {
            Vector c = Truck.center();
            return new Vector(c.getX(), FY, c.getZ());
        }
        return new Vector(Plaza.CX + 0.5, FY, Plaza.CZ + 0.5);
    }

    /** 수평 거리. */
    static double flat(Vector a, Vector b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    static double flat(Vector a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** 마크 yaw(0 = +Z, 90 = -X): from 에서 to 쪽. */
    static float yawTo(Vector from, Vector to) {
        return (float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ()));
    }

    /** yaw 쪽 수평 단위 벡터. */
    static Vector fwd(double yawDeg) {
        double a = Math.toRadians(yawDeg);
        return new Vector(-Math.sin(a), 0, Math.cos(a));
    }

    /** 반경 r 안에 서 있는 사람이 가장 많은 사람(같으면 from 에 가까운 쪽). 아무도 없으면 null. */
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

    /** 서 있는 사람들의 무게 중심(바닥 높이). 없으면 null. */
    static Vector centroid(Game game) {
        List<Player> ps = game.mobs.standing();
        if (ps.isEmpty()) return null;
        double x = 0, z = 0;
        for (Player p : ps) {
            x += p.getLocation().getX();
            z += p.getLocation().getZ();
        }
        return new Vector(x / ps.size(), FY, z / ps.size());
    }

    /** 감독이 곱한 체력 배율(부위 체력에 곱함). */
    static double hpK(Gabe g) {
        return g.sp.hp <= 0 ? 1 : Math.max(0.2, g.maxHp / g.sp.hp);
    }

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

    /** 다른 게이브를 고를 때 빼는 것: 보스, 거대한 몸, 자기 자신, 죽은 것, 판 규칙 밖(연출, 우리), 맞지 않는 것. */
    static boolean other(Gabe me, Gabe g) {
        return g != me && !g.dead && !g.removed && !g.sp.is(Role.BOSS) && !g.big && !g.noRules && !g.unhittable();
    }

    /** 사람을 붙잡거나 가두거나 머리에 붙은 개체(옮기거나 지우면 안 되는 것). */
    static Set<Gabe> holders(Game game) {
        Set<Gabe> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PState s : game.players.all()) {
            if (s.grabbedBy != null) out.add(s.grabbedBy);
            if (s.eater != null) out.add(s.eater);
            if (s.headBy != null) out.add(s.headBy);
        }
        return out;
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

    /** 맞은 점에서 가장 가까운 구 자체(없으면 null). */
    static double[] sphereAt(List<double[]> sph, Vector at) {
        if (at == null || sph == null) return null;
        double best = Double.MAX_VALUE;
        double[] out = null;
        for (double[] s : sph) {
            double dx = s[0] - at.getX(), dy = s[1] - at.getY(), dz = s[2] - at.getZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz) - s[3];
            if (d < best) {
                best = d;
                out = s;
            }
        }
        return out;
    }

    /** 부품 밝기를 b 로(이미 같으면 그대로: 패킷 아낌). */
    static void light(Body.Part p, Display.Brightness b) {
        if (p == null || p.d == null || !p.d.isValid()) return;
        if (!b.equals(p.d.getBrightness())) p.d.setBrightness(b);
    }

    /**
     * 바닥이나 허공에 이 종 모델 디스플레이 하나(연출 예산, 우선 KEY): 예고 원, 떨어지는 것처럼 몸과 따로 움직이는 것. life 틱 뒤 저절로 지워진다.
     * 컬링 상자는 0(가장자리에서 사라지지 않게). 못 낳으면 null.
     */
    static ItemDisplay fx(Game game, Location at, String model, String state, Matrix4f m, int life) {
        ItemDisplay d = game.shotFx.spawn(at, "fx_quad", 0, 0, m, Display.Billboard.FIXED, false, life, ShotFx.KEY);
        if (d == null) return null;
        d.setItemStack(Body.item(model, state, new LookState(), game.worldTime()));
        d.setBrightness(new Display.Brightness(12, 12));
        d.setTeleportDuration(2);
        d.setViewRange(4f);
        d.setDisplayWidth(0);
        d.setDisplayHeight(0);
        return d;
    }

    static void kill(ItemDisplay d) {
        if (d != null && d.isValid()) d.remove();
    }

    /** 따로 선 디스플레이에 이 종 모델을 놓는 변환: yaw 로 돌리고 (sx, sy, sz) 배 x 줄여 구운 배율(Look.scale), 아이템 디스플레이 반 바퀴. */
    static Matrix4f lay(String model, float yawDeg, float sx, float sy, float sz) {
        float k = Look.scale(model);
        return new Matrix4f().rotateY((float) Math.toRadians(-yawDeg)).scale(sx * k, sy * k, sz * k).rotateY((float) Math.PI);
    }

    /** 다음 틱부터 보간으로 변환을 바꿈. */
    static void glideTo(Game game, ItemDisplay d, Matrix4f m, int ticks) {
        game.shotFx.grow(d, m, ticks);
    }

    /** 땅울림 고리(반경 r)와 흙먼지: 셰이더 충격파 고리(ShotFx RING) 하나와 연기 하나. */
    static void quake(Game game, Location at, float r) {
        Location gr = ShotFx.ground(at, 3);
        ItemDisplay ring = game.shotFx.spawn(gr.clone().add(0, 0.15, 0), "fx_flat", ShotFx.RING, 0, ShotFx.scale(2f), Display.Billboard.FIXED, false,
                12, ShotFx.NORMAL);
        if (ring != null) {
            ring.setDisplayWidth(0);
            ring.setDisplayHeight(0);
        }
        game.shotFx.grow(ring, ShotFx.scale(r / 0.4f), 8);
        Location c = gr.clone().add(0, 0.8, 0);
        ItemDisplay sm = game.shotFx.spawn(c, "fx_quad", ShotFx.SMOKE, 1, ShotFx.scale(2.5f), Display.Billboard.CENTER, false, 40, ShotFx.EXTRA);
        game.shotFx.grow(sm, ShotFx.scale(r * 0.9f), 36);
        game.shotFx.drift(sm, c.clone().add(0, 1.6, 0), 36);
    }

    /** 분홍 물보라나 피 안개(맞은 자리 표시). */
    static void splash(Game game, Vector at, boolean pink) {
        Location l = new Location(game.world, at.getX(), at.getY(), at.getZ());
        ItemDisplay d = game.shotFx.spawn(l, "fx_quad", ShotFx.BLOOD, pink ? ShotFx.BLOOD_PINK : ShotFx.BLOOD_RED, ShotFx.scale(1.2f),
                Display.Billboard.CENTER, false, 14, ShotFx.EXTRA);
        game.shotFx.grow(d, ShotFx.scale(2.6f), 12);
    }

    /** 연기 한 줌(탄이 지나간 허깨비). */
    static void smoke(Game game, Vector at) {
        Location l = new Location(game.world, at.getX(), at.getY(), at.getZ());
        ItemDisplay d = game.shotFx.spawn(l, "fx_quad", ShotFx.SMOKE, 0, ShotFx.scale(1.0f), Display.Billboard.CENTER, false, 24, ShotFx.EXTRA);
        game.shotFx.grow(d, ShotFx.scale(2.4f), 22);
    }

    /** 점 p 에서 선분 a-b 까지 거리. */
    static double segDist(Vector p, Vector a, Vector b) {
        double abx = b.getX() - a.getX(), aby = b.getY() - a.getY(), abz = b.getZ() - a.getZ();
        double apx = p.getX() - a.getX(), apy = p.getY() - a.getY(), apz = p.getZ() - a.getZ();
        double l2 = abx * abx + aby * aby + abz * abz;
        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, (apx * abx + apy * aby + apz * abz) / l2));
        double dx = apx - abx * t, dy = apy - aby * t, dz = apz - abz * t;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 광선 o + dir t 가 선분 a-b 둘레 반지름 r 캡슐에 처음 닿는 t(안 닿으면 +무한, max 넘으면 +무한). 구를 촘촘히 늘어놓아 어림. */
    static double rayCapsule(Vector o, Vector dir, double max, Vector a, Vector b, double r) {
        Vector ab = b.clone().subtract(a);
        double len = ab.length();
        int n = Math.max(1, (int) Math.ceil(len / Math.max(0.2, r)));
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= n; i++) {
            double k = i / (double) n;
            double cx = a.getX() + ab.getX() * k, cy = a.getY() + ab.getY() * k, cz = a.getZ() + ab.getZ() * k;
            double t = raySphere(o, dir, cx, cy, cz, r);
            if (t < best) best = t;
        }
        return best <= max ? best : Double.POSITIVE_INFINITY;
    }

    /** 광선과 구가 처음 닿는 t(안 닿으면 +무한). dir 은 단위. */
    static double raySphere(Vector o, Vector dir, double cx, double cy, double cz, double r) {
        double ox = o.getX() - cx, oy = o.getY() - cy, oz = o.getZ() - cz;
        double b = ox * dir.getX() + oy * dir.getY() + oz * dir.getZ();
        double c = ox * ox + oy * oy + oz * oz - r * r;
        double disc = b * b - c;
        if (disc < 0) return Double.POSITIVE_INFINITY;
        double s = Math.sqrt(disc);
        double t = -b - s;
        if (t < 0) t = -b + s;
        return t < 0 ? Double.POSITIVE_INFINITY : t;
    }

    /**
     * 떨어뜨린 사람의 착지 피해(바닐라 낙하 피해는 판이 막는다): 들어 올렸다 놓은 사람이 바닥에 닿는 첫 틱에 피해와 넘어짐. 종이 매 틱 {@link #tick} 을 부른다.
     */
    static final class Drops {
        private final List<Object[]> list = new ArrayList<>();

        /** p 를 놓았음: 바닥에 닿으면 dmg(원인 cause)와 넘어짐 rootTicks. 120틱 안에 안 닿으면 지움. */
        void add(Gabe src, Player p, double dmg, String cause, int rootTicks) {
            if (p == null) return;
            list.add(new Object[] {p, dmg, cause, rootTicks, src.now() + 4, src.now() + 120});
        }

        void tick(Gabe src) {
            if (list.isEmpty()) return;
            long now = src.now();
            for (int i = list.size() - 1; i >= 0; i--) {
                Object[] e = list.get(i);
                Player p = (Player) e[0];
                if (!up(src.game, p) || now > (long) e[5]) {
                    list.remove(i);
                    continue;
                }
                if (now < (long) e[4]) continue;
                double fl = src.game.mobs.flow.floor(p.getLocation().getX(), p.getLocation().getZ());
                if (p.isOnGround() || p.getLocation().getY() <= fl + 0.08) {
                    list.remove(i);
                    double dmg = (double) e[1];
                    if (dmg > 0) src.hit(p, dmg, (String) e[2]);
                    int rt = (int) e[3];
                    if (rt > 0 && up(src.game, p)) src.game.players.root(p, rt, "fall_" + src.sp.key());
                    quake(src.game, p.getLocation(), 1.6f);
                }
            }
        }

        void clear() {
            list.clear();
        }
    }
}
