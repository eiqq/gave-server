package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G170 똬리 게이브(2막, 함정, 방해). 얼굴 마디 긴 몸이 지름 15 블록 똬리(두 바퀴 나선)로 사람이 가장 많이 모인 곳을 감싸 눕고 천천히 돈다.
 *
 * <ul>
 * <li>끌림: 똬리 안 서 있는 사람은 감긴 골을 따라 한가운데로 끌린다(공용 PUSH, 골 방향 0.7 + 안쪽 0.3). 바깥 고리 0.03, 안쪽 0.09 블록/틱,
 * 조이기 중 세 배. 안에 든 사람 조준점 아래에 끌림 게이지(안쪽 몫).</li>
 * <li>마디 넘기: 사람이 스스로 마디 몸을 타 넘으면(골이 바뀌면) 마디 얼굴에 물려 6 과 0.3 초 묶임. 골을 따라 비스듬히 나가면 안 물린다.</li>
 * <li>한가운데 3 블록: 70% 느려짐, 0.5 초마다 12. 머리에 닿으면 물려 24 와 1.5 초 붙잡힘(쏴서 머리를 맞히면 1 초 입을 다물고 놓음).</li>
 * <li>조이기: 15 초마다, 1 초 전 마디 가시가 한꺼번에 곤두서고(예고) 3 초 동안 똬리가 조여 끄는 힘 세 배.</li>
 * <li>맞음: 머리 x1, 마디 x0.2(폭발, 불은 x0.6: 몸이 낮아 잘 듣는다).</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g170.py): 머리 gabe/g170(상태 "", "bite"), 나선 부품 gabe/g170_in, gabe/g170_out(상태 "", "up", 실눈 뜸 = open).
 * 나선 r = R0 + B x th. 소리 coil(40), bite(24), cross(16), squeeze(48). 글귀 cause_g170, cause_g170_cross, cause_g170_grind.
 *
 * <p>도감과 다른 점: 디스플레이 셋(머리, 나선 안쪽 반, 바깥쪽 반: 꼬리와 가시는 나선 부품에 구움). 사체는 래그돌 대신 제자리에서 납작하게 꺼짐
 * (15 블록 나선 조각이 굴러다니지 않게). 똬리 가운데는 트럭 발자국에서 2.5 블록 밖으로 잡는다(트럭 위에 눕지 않게).
 */
public final class G170 extends Gabe {

    static final double R0 = 2.1, R1 = 7.0, TH = 2 * 2 * Math.PI, B = (R1 - R0) / TH, HALF_T = 0.6, HOVER = 0.6;
    static final double PULL_OUT = 0.03, PULL_IN = 0.09, SQUEEZE_MUL = 3, SQUEEZE_SCALE = 0.93;
    static final double CENTER_R = 3.0, HEAD_R = 1.35;
    static final double CROSS_DMG = 1, GRIND_DMG = 1, BITE_DMG = 2, SLOW_PCT = 70;
    static final int CROSS_ROOT = 6, GRIND_EVERY = 10, BITE_HOLD = 30, BITE_CD = 40, SQUEEZE_CD = 300, SQUEEZE_TELL = 20, SQUEEZE_LEN = 60, SHUT = 20;
    static final double SEG_MUL = 0.2, SEG_BLAST = 0.6;
    static final double SPIN = 0.0055;

    /** 사람마다: 지난 자리, 마지막으로 마디에 물린 틱, 갈림 틱, 머리 물기 다음 틱, 붙잡힘 풀 틱. */
    private static final class PS {
        double x, z;
        boolean has;
        int crossAt = -100, grindAt = -100, biteReady, releaseAt = -1;
        int seen;
    }

    private final Map<UUID, PS> people = new HashMap<>();
    private Body.Part inner, outer;
    private double spin, scaleNow = 1;
    private int posedAt = -100, squeezeReady, squeezeAt = -1, shutUntil, biteState = -1, retargetAt, soundAt;
    private final Vector goal = new Vector();
    private boolean hasGoal;
    private int crosses, bites, squeezes;
    private final List<double[]> sph = new ArrayList<>(32);
    private int sphAge = -1;

    public G170(Game game, Species sp) {
        super(game, sp);
        radius = 1.3;
        hover = HOVER;
        baseSpeed = 0.1;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragdoll = false;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g170", 16f, 4f);
        body.viewRange(3f);
        inner = body.attach("gabe/g170_in");
        outer = body.attach("gabe/g170_out");
        spin = game.random.nextDouble() * Math.PI * 2;
        poseCoil(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        squeezeReady = age + 200;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        yaw = 0;
        pitch = 0;
        spin += SPIN * (squeezeAt >= 0 ? 2 : 1);
        if (age - posedAt >= 10) poseCoil(10);
        move();
        squeeze();
        boolean squeezing = squeezeAt >= 0 && age - squeezeAt >= cd(SQUEEZE_TELL);
        peopleTick(squeezing);
        if (biteState >= 0 && age >= biteState) {
            state("");
            biteState = -1;
        }
    }

    /** 나선 부품 둘을 지금 각과 크기로. */
    private void poseCoil(int ticks) {
        posedAt = age;
        Matrix4f m = new Matrix4f().rotateY((float) -spin).scale((float) scaleNow, 1f, (float) scaleNow);
        if (inner != null) inner.pose(m, ticks);
        if (outer != null) outer.pose(new Matrix4f(m), ticks);
    }

    /** 사람이 가장 많이 모인 곳으로 미끄러져 감싸 누움(트럭 발자국 위는 피함). */
    private void move() {
        if (age >= retargetAt) {
            retargetAt = age + 40;
            hasGoal = densest(goal);
        }
        if (!hasGoal) {
            follow(speed());
            return;
        }
        Vector d = goal.clone().subtract(pos).setY(0);
        double l = d.length();
        double v = l > 4 ? speed() : l > 1.0 ? speed() * 0.3 : 0;
        if (v <= 0 || l < 1e-3) {
            hold();
            return;
        }
        vel.setX(d.getX() / l * v);
        vel.setZ(d.getZ() / l * v);
    }

    /** 서 있는 사람마다 7 블록 안 사람 수를 세어 가장 많은 무리의 가운데. */
    private boolean densest(Vector out) {
        List<Player> ps = game.mobs.standing();
        if (ps.isEmpty()) return false;
        int best = -1;
        double bestD = 1e9;
        Player pick = null;
        for (Player p : ps) {
            Location l = p.getLocation();
            int n = 0;
            for (Player q : ps) {
                Location m = q.getLocation();
                if (Math.hypot(m.getX() - l.getX(), m.getZ() - l.getZ()) <= 7) n++;
            }
            double dd = Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
            if (n > best || (n == best && dd < bestD)) {
                best = n;
                bestD = dd;
                pick = p;
            }
        }
        if (pick == null) return false;
        Location pl = pick.getLocation();
        double sx = 0, sz = 0;
        int c = 0;
        for (Player q : ps) {
            Location m = q.getLocation();
            if (Math.hypot(m.getX() - pl.getX(), m.getZ() - pl.getZ()) > 7) continue;
            sx += m.getX();
            sz += m.getZ();
            c++;
        }
        out.setX(sx / c).setZ(sz / c).setY(pos.getY());
        if (Truck.live()) {
            Vector tc = Truck.center();
            for (int i = 0; i < 16 && Truck.dist(out.getX(), out.getZ()) < 2.5; i++) {
                Vector a = out.clone().subtract(tc).setY(0);
                if (a.lengthSquared() < 1e-6) a = new Vector(1, 0, 0);
                out.add(a.normalize().multiply(0.6));
            }
        }
        return true;
    }

    /** 15 초마다: 1 초 예고(가시 곤두섬) 뒤 3 초 조이기. */
    private void squeeze() {
        if (squeezeAt < 0) {
            if (age < squeezeReady || people.isEmpty() || !anyInside()) return;
            squeezeAt = age;
            if (inner != null) inner.state("up");
            if (outer != null) outer.state("up");
            sound("squeeze", Sound.BLOCK_PISTON_CONTRACT, 0.5f, 48);
            return;
        }
        int t = age - squeezeAt, tell = cd(SQUEEZE_TELL);
        if (t == tell) {
            squeezes++;
            scaleNow = SQUEEZE_SCALE;
            poseCoil(8);
            sound("coil", Sound.ITEM_ELYTRA_FLYING, 0.7f, 40);
        }
        if (t >= tell + SQUEEZE_LEN) {
            squeezeAt = -1;
            squeezeReady = age + cd(SQUEEZE_CD);
            scaleNow = 1;
            if (inner != null) inner.state("");
            if (outer != null) outer.state("");
            poseCoil(10);
        }
    }

    private boolean anyInside() {
        for (PS s : people.values()) if (s.seen == age - 1 || s.seen == age) return true;
        return false;
    }

    /** 골 좌표: 사람 자리(가운데 기준 dx, dz)에서 나선 몇째 바퀴 사이인가(정수 = 마디 선). */
    private double lap(double dx, double dz) {
        double r = Math.hypot(dx, dz) / scaleNow;
        double psi = Math.atan2(dz, dx);
        double a = mod2pi(psi - spin);
        return ((r - R0) / B - a) / (2 * Math.PI);
    }

    private static double mod2pi(double a) {
        double t = a % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }

    private void peopleTick(boolean squeezing) {
        Player closest = null;
        double cd = 1e9;
        boolean innerSeen = false, outerSeen = false;
        double edge = (R1 + HALF_T + 0.3) * scaleNow;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            double r = Math.hypot(dx, dz);
            if (r > edge || Math.abs(l.getY() - (pos.getY() - HOVER)) > 2.5) continue;
            PS s = people.computeIfAbsent(p.getUniqueId(), k -> new PS());
            s.seen = age;
            if (r < cd) {
                cd = r;
                closest = p;
            }
            double rr = r / scaleNow;
            if (rr < (R0 + R1) / 2) innerSeen = true;
            else outerSeen = true;
            double q = Math.max(0, Math.min(1, (R1 - rr) / (R1 - R0)));
            // 마디 넘기: 같은 똬리 각으로 지난 자리와 지금 자리의 골이 다르면 마디를 타 넘음
            double u = lap(dx, dz);
            if (s.has && rr > R0 - HALF_T) {
                double up = lap(s.x - pos.getX(), s.z - pos.getZ());
                int g0 = (int) Math.floor(up), g1 = (int) Math.floor(u);
                if (g0 != g1 && age - s.crossAt >= 10) {
                    int k = Math.max(g0, g1);
                    double th = mod2pi(Math.atan2(dz, dx) - spin) + 2 * Math.PI * k;
                    if (k >= 0 && th <= TH + 0.2) {
                        s.crossAt = age;
                        crosses++;
                        hit(p, CROSS_DMG, "cause_g170_cross");
                        game.players.root(p, CROSS_ROOT, "g170");
                        sound("cross", Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 1.2f, 16);
                    }
                }
            }
            s.x = l.getX();
            s.z = l.getZ();
            s.has = true;
            // 끌림: 골을 따라 안쪽(나선 각이 줄어드는 쪽) 0.7 + 가운데 쪽 0.3
            double ux = r < 1e-3 ? 0 : -dx / r, uz = r < 1e-3 ? 0 : -dz / r;
            double pull = (PULL_OUT + (PULL_IN - PULL_OUT) * q) * (squeezing ? SQUEEZE_MUL : 1);
            Vector push;
            if (rr <= R0 - HALF_T || r < 1e-3) push = new Vector(ux, 0, uz).multiply(PULL_IN * (squeezing ? SQUEEZE_MUL : 1) * 0.6);
            else {
                double psi = Math.atan2(dz, dx);
                double tx = B * Math.cos(psi) - rr * Math.sin(psi), tz = B * Math.sin(psi) + rr * Math.cos(psi);
                double tl = Math.hypot(tx, tz);
                tx /= tl;
                tz /= tl;
                push = new Vector(-tx * 0.7 + ux * 0.3, 0, -tz * 0.7 + uz * 0.3).normalize().multiply(pull);
            }
            game.players.push(p, push);
            if ((age + p.getEntityId()) % 5 == 0) game.hud.meter(p, "g170", q, 12);
            // 한가운데
            if (r < CENTER_R * scaleNow + 0.2) {
                game.players.slow(p, SLOW_PCT, 12, "g170");
                if (age - s.grindAt >= cd(GRIND_EVERY)) {
                    s.grindAt = age;
                    hit(p, GRIND_DMG, "cause_g170_grind");
                }
            }
            if (r < HEAD_R + 0.3 && age >= shutUntil && age >= s.biteReady) {
                s.biteReady = age + cd(BITE_CD);
                bites++;
                hit(p, BITE_DMG, "cause_g170");
                if (game.players.grabbedBy(p) == null && grab(p, 5)) s.releaseAt = age + BITE_HOLD;
                state("bite");
                biteState = age + 16;
                sound("bite", Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, 24);
            }
        }
        // 붙잡힘 풀기, 안 보인 사람 지움
        for (Iterator<Map.Entry<UUID, PS>> it = people.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, PS> e = it.next();
            PS s = e.getValue();
            Player p = game.plugin.getServer().getPlayer(e.getKey());
            if (s.releaseAt >= 0 && (age >= s.releaseAt || age < shutUntil || p == null)) {
                if (p != null && game.players.grabbedBy(p) == this) game.players.release(p);
                s.releaseAt = -1;
            }
            if (age - s.seen > 40 && s.releaseAt < 0) {
                it.remove();
                if (p != null) game.hud.meter(p, "g170", -1, 0);
            } else if (s.seen != age) s.has = false;
        }
        if (inner != null && inner.look.open != innerSeen) inner.look.open = innerSeen;
        if (outer != null && outer.look.open != outerSeen) outer.look.open = outerSeen;
        if (closest != target) mark(closest);
        if (age >= soundAt) {
            soundAt = age + 30;
            if (!game.director.silent()) {
                Location at = loc();
                for (Player p : game.mobs.standing()) {
                    Location l = p.getLocation();
                    double r = Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
                    if (r > 36) continue;
                    double q = Math.max(0, Math.min(1, (R1 - r) / (R1 - R0)));
                    Props.soundTo(p, at, "gabe.g170.coil", Sound.ITEM_ELYTRA_FLYING, 0.9f, (float) (0.75 + 0.6 * q), 0.6f, -1);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        sph.add(new double[] {pos.getX(), pos.getY() + 0.15, pos.getZ(), 1.35});
        double th = 0.4;
        while (th < TH) {
            double r = (R0 + B * th) * scaleNow;
            double a = th + spin;
            sph.add(new double[] {pos.getX() + r * Math.cos(a), pos.getY(), pos.getZ() + r * Math.sin(a), 0.78});
            th += 2.0 / Math.max(1.5, r);
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return pos.clone().add(new Vector(0, 0.4, 0));
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        boolean headHit = d.at() == null;
        if (d.at() != null) {
            double hx = d.at().getX() - pos.getX(), hz = d.at().getZ() - pos.getZ();
            headHit = Math.hypot(hx, hz) <= 1.6 && Math.abs(d.at().getY() - pos.getY()) < 1.8;
        }
        if (headHit) {
            if (d.type() != DamageType.FIRE) {
                shutUntil = age + SHUT;
                state("");
                biteState = -1;
            }
            return super.damage(d);
        }
        double mul = (d.type() == DamageType.EXPLOSION || d.type() == DamageType.FIRE) ? SEG_BLAST : SEG_MUL;
        return super.damage(d.times(mul));
    }

    private void releaseAll() {
        for (Map.Entry<UUID, PS> e : people.entrySet()) {
            Player p = game.plugin.getServer().getPlayer(e.getKey());
            if (p == null) continue;
            if (game.players.grabbedBy(p) == this) game.players.release(p);
            game.hud.meter(p, "g170", -1, 0);
        }
        people.clear();
    }

    @Override
    public void die(Damage d) {
        releaseAll();
        super.die(d);
    }

    @Override
    public void remove() {
        releaseAll();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " spin=" + String.format("%.2f", spin) + " in=" + people.size() + " cross=" + crosses + " bite=" + bites + " sq=" + squeezes
            + (squeezeAt >= 0 ? " squeezing" : "") + " goal=" + (hasGoal ? String.format("%.1f,%.1f", goal.getX(), goal.getZ()) : "-");
    }
}
