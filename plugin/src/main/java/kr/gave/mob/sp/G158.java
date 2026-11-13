package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G158 문턱 게이브(떼, 함정). 무릎 높이 얼굴 떼가 종종걸음으로 와서 중앙 원(트럭 둘레) 테두리 선 바로 밖(LINE)에 어깨를 맞대고 빙 둘러 서서
 * 허공을 '똑똑' 두드리며 기다린다. 선을 못 넘는다.
 *
 * <p>틈: 웨이브 중 사람이 테두리(Plaza.R_SAFE)를 가로지르면(나가든 들어오든) 그 각도에 GAP_T 틱 동안 틈이 생기고(분홍 피 안개가 테두리에 번짐),
 * 틈 GAP_R 블록 안 문턱 게이브가 모두 그 틈으로 쏟아져 들어온다(상태 in: 눈 뜸, 빠르기 IN_MUL 배, 등 뒤부터 물기 IN_BITE). 더 멀리(GAP_SEE)
 * 선 것들은 넘은 사람을 향해 한꺼번에 눈을 뜬다. 쉬는 시간에는 틈을 만들지 않는다.
 *
 * <p>테두리 밖 사람에게는 OUT_R 안이면 곧장 달려들어 정강이 물기(BITE). 선 가까이(안쪽 POKE_IN 블록) 선 사람은 선 너머에서 막대기로 찌름(POKE,
 * POKE_CD 마다).
 *
 * <p>도감과 다른 점: '트럭 둘레 테두리'는 지금 판의 중앙 원 테두리(Plaza.R_SAFE)로 읽었다(트럭 발자국에 따로 선을 긋지 않음). 벌어진 틈은 테두리
 * 위 세 자리에 분홍 피 안개(ShotFx.bloodHit BLOOD_PINK)로 보인다.
 *
 * <p>팩 계약(tools/gabe/sp/g158.py): 상태 "", "knock", "in", 소리 knock(24), gap(32), bite(12), 글귀 cause_g158.
 */
public final class G158 extends Gabe {

    static final double HOVER = 0.48, RADIUS = 0.3, SPEED = 0.17, IN_MUL = 1.5;
    /** 서는 테두리(중앙 원 바로 밖). */
    static final double LINE = Plaza.R_SAFE + 0.6;
    static final double BITE = 5, POKE = 4, IN_BITE = 8;
    static final int BITE_CD = 20, POKE_CD = 120, IN_BITE_CD = 16;
    static final double OUT_R = 6, POKE_IN = 1.5, POKE_REACH = 2.8;
    /** 틈: 지속, 들어오는 거리, 눈 뜨는 거리. */
    static final int GAP_T = 60;
    static final double GAP_R = 8, GAP_SEE = 18;
    /** 똑똑 소리: 개체마다 간격, 판 전체 한 틱 상한. */
    static final int KNOCK_EVERY = 50, KNOCK_CAP = 2;

    /** 틈 하나(테두리 위 자리). */
    static final class Gap {
        final double x, z;
        final long until;
        final Player by;

        Gap(double x, double z, long until, Player by) {
            this.x = x;
            this.z = z;
            this.until = until;
            this.by = by;
        }
    }

    // 판 전체(종 공용): 사람마다 지난 틱 반지름, 지금 열린 틈
    private static Game scanGame;
    private static long scanTick = Long.MIN_VALUE;
    private static final Map<UUID, Double> LAST_R = new HashMap<>();
    private static final List<Gap> GAPS = new ArrayList<>();
    private static int knocksNow;

    private boolean inside;
    private Gap entering;
    private int biteReady, pokeReady, knockAt;

    public G158(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void spawned() {
        knockAt = game.random.nextInt(KNOCK_EVERY);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        long now = now();
        if (scanGame != game || scanTick != now) scan(game, now);
        if (inside) {
            inTick();
            return;
        }
        if (entering != null && entering.until > now) {
            enter();
            return;
        }
        entering = null;
        Gap g = gapNear(now);
        if (g != null) {
            entering = g;
            state("in");
            enter();
            return;
        }
        Player out = outsider();
        if (out != null) {
            chase(out, BITE, BITE_CD, 1.0);
            if ("knock".equals(state)) state("");
            return;
        }
        double r = Plaza.radius(pos.getX(), pos.getZ());
        if (r > LINE + 0.4) {
            if ("knock".equals(state)) state("");
            follow(speed());
            Vector next = pos.clone().add(vel);
            if (Plaza.radius(next.getX(), next.getZ()) < LINE) wait(r);
            else face(pos.clone().add(vel));
            pitch = 0;
            return;
        }
        wait(r);
    }

    /** 테두리 선 밖에 서서 두드림: 반지름을 LINE 에 맞추고, 곁에 붙은 동족이 있으면 테두리를 따라 옆걸음. */
    private void wait(double r) {
        state("knock");
        double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5;
        Vector out = new Vector(pos.getX() - cx, 0, pos.getZ() - cz);
        if (out.lengthSquared() < 1e-6) out = new Vector(1, 0, 0);
        out.normalize();
        Vector tan = new Vector(-out.getZ(), 0, out.getX());
        double side = 0;
        for (Gabe o : kin(0.75)) {
            if (!(o instanceof G158)) continue;
            Vector d = pos.clone().subtract(o.pos);
            side += Math.signum(d.getX() * tan.getX() + d.getZ() * tan.getZ() + (o.id < id ? 0.01 : -0.01));
        }
        vel.copy(out.clone().multiply((LINE - r) * 0.3)).add(tan.multiply(Math.signum(side) * speed() * 0.5));
        // 안쪽 가장 가까운 사람을 봄(없으면 원 가운데)
        Player p = nearest(LINE + 6);
        face(p != null ? chest(p) : new Vector(cx, pos.getY(), cz));
        pitch = 0;
        if (age % KNOCK_EVERY == knockAt && knocksNow < KNOCK_CAP) {
            knocksNow++;
            sound("knock", Sound.BLOCK_WOOD_HIT, 1.3f, 24);
        }
        // 선 가까이 선 사람을 선 너머에서 찌름
        if (age >= pokeReady && p != null && dist(p) <= POKE_REACH && Plaza.radius(p.getLocation().getX(), p.getLocation().getZ()) >= Plaza.R_SAFE - POKE_IN) {
            pokeReady = age + cd(POKE_CD);
            hit(p, POKE, "cause_g158");
            knock(p, toward(p), 0.18, 0.05);
            sound("bite", Sound.ENTITY_FOX_BITE, 1.4f, 12);
        }
    }

    /** 틈으로 들어감: 틈 자리를 지나 원 안쪽으로. */
    private void enter() {
        Gap g = entering;
        double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5;
        Vector in = new Vector(cx - g.x, 0, cz - g.z);
        double l = in.length();
        Vector to = l < 1e-6 ? new Vector(cx, 0, cz) : new Vector(g.x, 0, g.z).add(in.multiply(2.5 / l));
        to.setY(pos.getY());
        seek(to, speed() * IN_MUL);
        face(to);
        pitch = 0;
        if (Plaza.radius(pos.getX(), pos.getZ()) < Plaza.R_SAFE - 0.8) {
            inside = true;
            entering = null;
        }
    }

    /** 원 안에서 날뜀: 빠르게, 등 뒤부터 묾. */
    private void inTick() {
        if (!"in".equals(state)) state("in");
        Player p = standing(target) ? target : nearest(400);
        if (p != target) mark(p);
        if (p == null) {
            follow(speed() * IN_MUL);
            return;
        }
        chase(p, IN_BITE, IN_BITE_CD, IN_MUL);
    }

    /** 사람에게: 가까우면 등 뒤 자리로 돌아 들어가 묾. */
    private void chase(Player p, double dmg, int cdTicks, double mul) {
        if (target != p) mark(p);
        double d = dist(p);
        Location l = p.getLocation();
        if (d > 16) follow(speed() * mul);
        else {
            Vector back = l.getDirection().setY(0);
            Vector to = l.toVector();
            if (back.lengthSquared() > 1e-6 && d < 4) to.subtract(back.normalize().multiply(0.7));
            seek(to, speed() * mul);
        }
        face(chest(p));
        pitch = 0;
        if (age >= biteReady && touches(p, 0.12)) {
            biteReady = age + cd(cdTicks);
            hit(p, dmg, "cause_g158");
            sound("bite", Sound.ENTITY_FOX_BITE, 1.4f, 12);
            body.squash(1.15f, 0.85f, 1.15f, 2);
        }
    }

    /** 테두리 밖, OUT_R 안의 가장 가까운 서 있는 사람. */
    private Player outsider() {
        Player best = null;
        double bd = OUT_R;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Plaza.radius(l.getX(), l.getZ()) <= Plaza.R_SAFE + 0.3) continue;
            double d = dist(p);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private Gap gapNear(long now) {
        for (Gap g : GAPS) {
            if (g.until <= now) continue;
            double dx = pos.getX() - g.x, dz = pos.getZ() - g.z;
            if (dx * dx + dz * dz <= GAP_R * GAP_R) return g;
        }
        return null;
    }

    // ------------------------------------------------------------------ 판 전체: 테두리를 가로지른 사람

    private static void scan(Game game, long now) {
        if (scanGame != game) {
            scanGame = game;
            LAST_R.clear();
            GAPS.clear();
        }
        scanTick = now;
        knocksNow = 0;
        GAPS.removeIf(g -> g.until <= now);
        if (game.state != Game.State.WAVE) {
            LAST_R.clear();
            GAPS.clear();
            return;
        }
        Set<UUID> seen = new HashSet<>();
        double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double r = Plaza.radius(l.getX(), l.getZ());
            UUID u = p.getUniqueId();
            seen.add(u);
            Double prev = LAST_R.put(u, r);
            if (prev == null || (prev > Plaza.R_SAFE) == (r > Plaza.R_SAFE)) continue;
            // 가로지름: 그 각도의 테두리 위에 틈
            double a = Math.atan2(l.getZ() - cz, l.getX() - cx);
            double gx = cx + Math.cos(a) * LINE, gz = cz + Math.sin(a) * LINE;
            Gap g = new Gap(gx, gz, now + GAP_T, p);
            GAPS.add(g);
            opened(game, g, a);
        }
        LAST_R.keySet().retainAll(seen);
        // 벌어진 틈: 테두리 위 세 자리에 분홍 안개
        if (now % 15 == 0) {
            for (Gap g : GAPS) {
                double a = Math.atan2(g.z - cz, g.x - cx);
                for (int i = -1; i <= 1; i++) {
                    double b = a + i * 3.0 / LINE;
                    Location at = new Location(game.world, cx + Math.cos(b) * Plaza.R_SAFE, game.mobs.flow.floor(g.x, g.z) + 0.5,
                            cz + Math.sin(b) * Plaza.R_SAFE);
                    game.shotFx.bloodHit(at, new Vector(0, 1, 0), false, ShotFx.BLOOD_PINK);
                }
            }
        }
    }

    /** 틈이 열림: 곁의 문턱 게이브가 넘은 사람을 향해 한꺼번에 눈을 뜸(가까운 것은 다음 틱에 쏟아져 들어옴). */
    private static void opened(Game game, Gap g, double a) {
        boolean sounded = false;
        Vector at = new Vector(g.x, 0, g.z);
        for (Gabe o : game.mobs.all()) {
            if (o.dead || !(o instanceof G158 m) || m.inside) continue;
            at.setY(o.pos.getY());
            if (o.pos.distanceSquared(at) > GAP_SEE * GAP_SEE) continue;
            if (g.by != null && g.by.isOnline()) m.mark(g.by);
            if (!sounded) {
                m.sound("gap", Sound.BLOCK_WOODEN_DOOR_OPEN, 0.8f, 32);
                sounded = true;
            }
        }
    }

    /** 시험용. */
    public String info() {
        return (inside ? "in" : entering != null ? "entering" : "out") + " r=" + String.format(java.util.Locale.ROOT, "%.1f",
                Plaza.radius(pos.getX(), pos.getZ())) + " gaps=" + GAPS.size();
    }

    @Override
    public String toString() {
        return super.toString() + " " + info();
    }

}
