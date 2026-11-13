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

/**
 * 묶음 8(땅의 거인 1: G66 G94 G102 G106 G103 G107 G112)이 같이 쓰는 도우미. 공용 엔진이 아니라 이 묶음 종 파일 안의 것이다.
 *
 * <ul>
 * <li>{@link #at}: 몸 틀 점(블록, +X 몸 왼쪽, +Y 위, +Z 앞) → 세계 점.</li>
 * <li>{@link #gesture}: 상태를 켜면서 그 상태의 손으로 짠 자세가 0 부터 돌게 look.phase 를 맞춤(셰이더 위상은 주기의 16분의 1 단위).</li>
 * <li>{@link #stomp}: 거인 걸음(공용 sfx.stomp, 발밑 흙 고리, 둘레 사람 화면 끄덕).</li>
 * <li>{@link Flings}: 사람 띄우기(공용 LAUNCH = Players.launch 위에 받아 주기 규칙).</li>
 * <li>{@link #ring}, {@link #warn}: 바닥 흙 고리, 빨간 예고 고리(ShotFx 셰이더 모양, 발광 상자 아님).</li>
 * </ul>
 */
final class G66Giant {

    private G66Giant() {
    }

    /** 몸 틀 점 → 세계 점(yaw 만, pitch 와 roll 은 안 봄). */
    static Vector at(Gabe g, double x, double y, double z) {
        double r = Math.toRadians(g.yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        return new Vector(g.pos.getX() + fz * x + fx * z, g.pos.getY() + y, g.pos.getZ() - fx * x + fz * z);
    }

    /** 보는 방향만 수평으로(거인은 몸 전체가 기울지 않게 pitch 0: Gabe.face 는 몸 중심에서 겨눈 pitch 를 넣어 큰 몸이 통째로 숙여짐). */
    static void faceFlat(Gabe g, Vector at) {
        double dx = at.getX() - g.pos.getX(), dz = at.getZ() - g.pos.getZ();
        if (dx * dx + dz * dz < 1e-8) return;
        g.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        g.pitch = 0;
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

    /**
     * 상태 st(주기 period 틱, 자세 n)를 켜고 지금 틱이 자세 0 의 첫머리가 되게 위상을 맞춘다. 셰이더: 자세 = floor(((T + ph16 x P / 16) mod P) x n / P),
     * ph16 = floorMod(phase, P) x 16 / P(정수). 맞는 phase 를 0..P-1 에서 고른다(한 번, 가벼움).
     */
    static void gesture(Gabe g, String st, int period, int n) {
        g.state(st);
        if (g.body == null) return;
        g.body.look.rate = 1.0;
        g.body.look.freeze = false;
        long t = Math.floorMod(g.game.worldTime(), 24000L);
        int best = 0;
        double bestRes = Double.MAX_VALUE;
        for (int ph = 0; ph < period; ph++) {
            int q = Math.floorMod(ph, period) * 16 / period;
            double m = ((t + q * period / 16.0) % period + period) % period;
            // 자세 0 안의 자리: 0 에 가까울수록 좋음(다음 틱 패킷이 닿는 동안 한 틱쯤 지남)
            double res = m < period / (double) n ? m : 1e6 + m;
            if (res < bestRes) {
                bestRes = res;
                best = ph;
            }
        }
        g.body.look.phase = best;
    }

    /** 거인 걸음 한 번: 발 자리 foot(세계, y 는 바닥), 소리 반경 range, 화면 끄덕 반경 shakeR. */
    static void stomp(Gabe g, Vector foot, double range, double shakeR, float volume) {
        if (!g.game.director.silent())
            Props.soundAround(new Location(g.game.world, foot.getX(), foot.getY(), foot.getZ()), "sfx.stomp", Sound.ENTITY_RAVAGER_STEP, volume,
                0.85f + g.game.random.nextFloat() * 0.2f, 0.6f, range, -1);
        ring(g, foot, 1.6, 10);
        double r2 = shakeR * shakeR;
        for (Player p : g.game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - foot.getX(), dz = l.getZ() - foot.getZ();
            if (dx * dx + dz * dz > r2) continue;
            // 2026-11-12 사용자 '시야 각도를 바꾸는 게이브는 안 나오게': 거인 걸음 화면 끄덕 뺌(거인은 그대로 나옴)
        }
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
        double y = g.game.mobs.flow.floor(c.getX(), c.getZ()) + 0.07;
        g.game.shotFx.spawn(new Location(g.game.world, c.getX(), y, c.getZ()), "fx_flat", ShotFx.DOWN_RING, 0, ShotFx.scale((float) (r / 0.4)),
            Display.Billboard.FIXED, false, life, ShotFx.KEY);
    }

    /** 부품 하나를 몸 가운데 기준 세계 축 자리(dx, dy, dz)에 크기 s 로(follow = false). */
    static void place(Body.Part part, double dx, double dy, double dz, float sx, float sy, float sz, int ticks) {
        if (part == null) return;
        part.follow = false;
        part.pose(new Matrix4f().translate((float) dx, (float) dy, (float) dz).scale(sx, sy, sz), ticks);
    }

    // ------------------------------------------------------------------ 사람 띄우기

    /** 띄운 사람 하나: 공용 LAUNCH(Players.launch)가 기본 착지 피해를 주고, 받아 주는 동료가 없으면 이 묶음이 나머지(extra)를 더 준다. */
    static final class Fling {
        final Player p;
        final long start;
        final double extra;
        final String cause;

        Fling(Player p, long start, double extra, String cause) {
            this.p = p;
            this.start = start;
            this.extra = extra;
            this.cause = cause;
        }
    }

    /**
     * 띄운 사람들. 종 개체마다 하나를 갖고 매 틱 {@link #tick}. 띄우기와 착지 피해는 공용 LAUNCH({@code game.players.launch}, 2026-11-12 sp_engine):
     * 높이 블록까지 오르고 땅에 닿는 첫 틱에 피해(웨이브가 끝나면 지움). 받아 주기(곁에 동료)가 있는 종은 기본 피해를 받아 준 값으로 걸고,
     * 착지했을 때 곁에 동료가 없으면 나머지를 이 종이 더한다. 개체가 죽으면 {@link #clear}(더할 몫만 버림).
     */
    static final class Flings {
        final List<Fling> list = new ArrayList<>(4);

        /** height 블록 높이로 띄움(수평 push 블록/틱을 더함). dmg 착지 피해, caught 곁에 동료가 있으면 받는 피해(음수 = 받아 주기 없음). */
        boolean launch(Gabe g, Player p, double height, Vector push, double dmg, double caught, String cause) {
            if (!g.standing(p)) return false;
            for (Fling f : list) if (f.p == p) return false;
            double base = caught >= 0 ? Math.min(caught, dmg) : dmg;
            if (!g.game.players.launch(p, height, push, base, g, cause)) return false;
            list.add(new Fling(p, g.now(), dmg - base, cause));
            return true;
        }

        boolean has(Player p) {
            for (Fling f : list) if (f.p == p) return true;
            return false;
        }

        /** 착지 판정(공용 LAUNCH 가 내려온 사람을 지운 뒤). 곁 1.8 블록 안에 다른 서 있는 사람이 없으면 extra 를 더 줌. 착지한 사람을 landed 에. */
        void tick(Gabe g, List<Player> landed) {
            for (Iterator<Fling> it = list.iterator(); it.hasNext();) {
                Fling f = it.next();
                long t = g.now() - f.start;
                if (!f.p.isOnline() || !g.standing(f.p) || t > 400) {
                    it.remove();
                    continue;
                }
                if (t < 3 || g.game.players.launched(f.p)) continue;
                if (f.extra > 0) {
                    boolean caughtBy = false;
                    Location l = f.p.getLocation();
                    for (Player q : g.game.mobs.standing()) {
                        if (q != f.p && q.getLocation().distanceSquared(l) <= 1.8 * 1.8) {
                            caughtBy = true;
                            break;
                        }
                    }
                    if (!caughtBy) g.hit(f.p, f.extra, f.cause);
                }
                if (landed != null) landed.add(f.p);
                it.remove();
            }
        }

        void clear() {
            list.clear();
        }
    }
}
