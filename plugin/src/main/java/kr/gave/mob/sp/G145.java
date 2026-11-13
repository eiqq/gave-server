package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Role;
import kr.gave.mob.Species;

/**
 * G145 못 박힌 게이브(정예, 돌격). 황소 꼴 얼굴: 앞발로 땅을 두 번 긁고 한 사람을 찍어 곧게 들이받는다(0.34블록/틱, 24 피해와 날림, 걸린 사람을
 * 치켜올려 12 더). 빗나가면 5블록 미끄러져 돌아선다.
 *
 * <p>목덜미 구멍(작은 구)에 8블록 안에서 한 발을 박으면 20초(400틱, 박자 배율 안 탐) 동안 순해진다: 실눈, 옅은 홍조, 사람 곁 8블록을 맴돌며
 * 가장 가까운 다른 게이브(보스 빼고)를 들이받아 제 최대 체력의 30% 피해. 박힌 못이 조금씩 밀려 나오는 길이가 남은 시간이다. 순한 동안 사람 탄에
 * 맞으면 못이 바로 빠진다(구멍에 다시 맞으면 대신 10초 더, 두 번까지). 못이 빠지면 울부짖고 15초 동안 1.5배 빠르기, 32 피해로 못을 뺀 사람에게
 * 간다. 숙인 이마(얼굴 구)는 피해 절반.
 *
 * <p>바꾼 것: 큰 게이브를 3초 멈칫하게 하는 것은 엔진에 남의 움직임을 멈추는 길이 없어 피해만 준다. 밀쳐 내기도 같은 까닭으로 뺐다.
 *
 * <p>팩 계약(tools/gabe/sp/g145.py): 모델 gave:gabe/g145 상태 "", "scrape", "tame", 부품 gave:gabe/g145_nail, 소리 nail(32), bellow(32),
 * scrape(16), ram(24), 글귀 cause_g145.
 */
public final class G145 extends Gabe {

    static final double WALK = 0.16, CHARGE = 0.34, RAGE_K = 1.5, SEE = 14, RAM = 24, RAM_RAGE = 32, TOSS = 12, CHARGE_MAX = 26;
    static final int SCRAPE = 30, TOSS_AT = 6, READY = 40, TAME = 400, TAME_MORE = 200, RAGE = 300, GORE_CD = 30;
    static final double NAIL_REACH = 8.5, GORE_K = 0.3, TAME_KEEP = 8, TAME_SEEK = 12;
    /** 몸 기준 자리(블록, 앞 +Z): 얼굴, 몸통, 엉덩이, 목덜미 구멍. */
    static final double[] FACE = {0, -0.1, 1.25, 0.72}, TRUNK = {0, 0.05, 0.0, 0.88}, REAR = {0, -0.05, -1.15, 0.55}, HOLE = {0, 0.97, 0.36, 0.26};

    enum Phase { APPROACH, SCRAPE, CHARGE, SLIDE, TAME }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, readyAt = 20, tameUntil, rageUntil = -1, extends_, goreAt, tossAt = -1;
    private final Vector dir = new Vector();
    private final Vector start = new Vector();
    private double slideV;
    private Player prey, tossP, rageAt;
    private Gabe foe;
    private Body.Part nail;

    public G145(Game game, Species sp) {
        super(game, sp);
        radius = 0.9;
        hover = 1.3;
        baseSpeed = WALK;
        noPush = true;
        ragKick = 0.5;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g145", 3.8f, 2.8f);
    }

    private boolean raging() {
        return age < rageUntil;
    }

    @Override
    public void tick() {
        if (tossAt >= 0 && age >= tossAt) toss();
        switch (phase) {
            case APPROACH -> approach();
            case SCRAPE -> scrape();
            case CHARGE -> charge();
            case SLIDE -> slide();
            case TAME -> tame();
        }
    }

    // ------------------------------------------------------------------ 사나운 동안

    private void approach() {
        Player p = raging() && standing(rageAt) ? rageAt : nearest(400);
        // 실눈으로 다가오다 긁기 시작에 한 사람을 찍는다(화났으면 못을 뺀 사람을 계속 봄)
        if (!raging() && target != null) mark(null);
        if (p == null) {
            follow(speed());
            return;
        }
        follow(speed() * (raging() ? RAGE_K : 1));
        if (age >= readyAt && dist(p) < SEE && sees(p)) {
            prey = p;
            mark(p);
            go(Phase.SCRAPE);
            state("scrape");
            hold();
        }
    }

    private void go(Phase ph) {
        phase = ph;
        phaseAt = age;
    }

    private void scrape() {
        hold();
        Player p = prey;
        if (!standing(p)) {
            state("");
            go(Phase.APPROACH);
            return;
        }
        face(chest(p));
        pitch = 0;
        int t = age - phaseAt, len = cd(SCRAPE);
        if (t == 0 || t == len / 2) sound("scrape", Sound.BLOCK_GRAVEL_STEP, 0.6f, 16);
        if (t < len) return;
        state("");
        dir.copy(toward(p));
        start.copy(pos);
        go(Phase.CHARGE);
    }

    private void charge() {
        double v = CHARGE * (speed() / Math.max(1e-6, baseSpeed)) * (raging() ? RAGE_K : 1);
        dash(dir, v);
        yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        pitch = 8;
        Player p = prey;
        if (standing(p) && touches(p, 0.6)) {
            hit(p, raging() ? RAM_RAGE : RAM, "cause_g145");
            knock(p, dir, 1.0, 0.45);
            sound("ram", Sound.ENTITY_RAVAGER_ATTACK, 0.8f, 24);
            body.squash(0.85f, 1.1f, 1.15f, 2);
            tossP = p;
            tossAt = age + TOSS_AT;
            endCharge(v);
            return;
        }
        boolean past = p != null && p.isOnline() && p.getLocation().toVector().subtract(pos).setY(0).dot(dir) < -2.5;
        if (pos.distance(start) > CHARGE_MAX || wallAhead(1.6) || past || age - phaseAt > 90) endCharge(v);
    }

    private void endCharge(double v) {
        pitch = 0;
        slideV = v;
        go(Phase.SLIDE);
    }

    /** 걸린 사람을 치켜올려 내던짐. */
    private void toss() {
        Player p = tossP;
        tossAt = -1;
        tossP = null;
        if (!standing(p) || dist(p) > 4.5) return;
        hit(p, TOSS, "cause_g145");
        knock(p, dir, 0.5, 0.75);
    }

    private void slide() {
        slideV *= 0.86;
        if (slideV > 0.04) {
            dash(dir, slideV);
            return;
        }
        hold();
        Player p = raging() && standing(rageAt) ? rageAt : nearest(400);
        if (p == null || turnTo(chest(p), 9f) || age - phaseAt > 50) {
            readyAt = age + cd(READY);
            go(Phase.APPROACH);
        }
    }

    // ------------------------------------------------------------------ 순한 동안

    private void tameStart(int ticks) {
        go(Phase.TAME);
        tameUntil = age + ticks;
        extends_ = 0;
        state("tame");
        mark(null);
        body.look.palette |= Look.PAL_PALE;
        noBite = true;
        tossAt = -1;
        foe = null;
        if (nail == null) nail = body.attach("gabe/g145_nail");
        nailPose(3);
        sound("nail", Sound.BLOCK_ANVIL_LAND, 1.8f, 32);
        hold();
    }

    /** 못이 밀려 나온 길이 = 지난 몫(구멍 자리에서 위로). */
    private void nailPose(int ticks) {
        if (nail == null) return;
        int total = Math.max(1, tameUntil - phaseAt);
        double f = Math.max(0, Math.min(1, 1.0 - (double) (tameUntil - age) / total));
        nail.pose(new Matrix4f().translate((float) HOLE[0], (float) (HOLE[1] - 0.55 + 0.75 * f), (float) HOLE[2]).rotateX(-0.25f), ticks);
    }

    private void tame() {
        if (age >= tameUntil) {
            Player p = nearest(400);
            enrage(p);
            return;
        }
        if ((age - phaseAt) % 10 == 0) nailPose(10);
        Player near = nearest(400);
        if (foe != null && (foe.dead || foe.removed || foe.pos.distance(pos) > TAME_SEEK + 4)) foe = null;
        if (foe == null && age % 10 == 0) {
            Vector c = near != null ? near.getLocation().toVector() : pos;
            foe = game.mobs.nearest(c, TAME_SEEK, g -> g != this && !g.dead && !g.untargetable && !g.disguised && !g.sp.is(Role.BOSS)
                && !(g instanceof G145 o && o.phase == Phase.TAME));
        }
        if (foe != null && age >= goreAt) {
            Vector d = foe.pos.clone().subtract(pos).setY(0);
            double l = d.length();
            if (l > 1e-3) {
                dash(d, speed() * 2.0);
                yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
            }
            if (l <= radius + foe.radius + 0.5) {
                goreAt = age + GORE_CD;
                Damage hitIt = new Damage(maxHp * GORE_K, DamageType.OTHER, null, foe.pos.clone(), d.lengthSquared() > 1e-6 ? d.normalize() : null,
                    "g145_gore", 0);
                game.mobs.damage(foe, hitIt);
                body.squash(0.85f, 1.1f, 1.15f, 2);
                sound("ram", Sound.ENTITY_RAVAGER_ATTACK, 0.9f, 24);
                if (foe.dead) foe = null;
            }
            return;
        }
        if (near != null) {
            Location l = near.getLocation();
            double dd = Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
            if (dd > TAME_KEEP) follow(speed());
            else circle(new Vector(l.getX(), pos.getY(), l.getZ()), Math.min(TAME_KEEP - 2, Math.max(4, dd)), speed() * 0.8, 1);
            if (Math.hypot(vel.getX(), vel.getZ()) > 1e-3) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        } else hold();
    }

    /** 못이 빠짐: 울부짖고 15초 동안 빠르고 세게 그 사람에게. */
    private void enrage(Player by) {
        if (nail != null) {
            nail.remove();
            nail = null;
        }
        body.look.palette &= ~Look.PAL_PALE;
        noBite = false;
        state("");
        rageUntil = age + RAGE;
        rageAt = by;
        if (by != null) mark(by);
        sound("bellow", Sound.ENTITY_RAVAGER_ROAR, 0.7f, 32);
        body.squash(1.1f, 0.9f, 1.05f, 4);
        readyAt = age + 20;
        go(Phase.APPROACH);
    }

    // ------------------------------------------------------------------ 맞음

    private double[] at(double[] o) {
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
        // 몸 왼쪽 = (fz, 0, -fx)
        return new double[] {pos.getX() + fx * o[2] + fz * o[0], pos.getY() + o[1], pos.getZ() + fz * o[2] - fx * o[0], o[3]};
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(4);
        out.add(at(FACE));
        out.add(at(TRUNK));
        out.add(at(REAR));
        out.add(at(HOLE));
        return out;
    }

    @Override
    public Vector head() {
        double[] f = at(FACE);
        return new Vector(f[0], f[1], f[2]);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        int part = part(d.at(), d.dir());
        Player by = d.by();
        boolean shot = by != null && (d.type() == DamageType.BULLET || d.type() == DamageType.PELLET || d.type() == DamageType.SNIPER);
        boolean holeShot = shot && part == 3 && by.getLocation().toVector().distance(pos) <= NAIL_REACH;
        if (phase == Phase.TAME) {
            if (holeShot && extends_ < 2) {
                extends_++;
                tameUntil += TAME_MORE;
                sound("nail", Sound.BLOCK_ANVIL_LAND, 1.9f, 32);
                return false;
            }
            if (by != null) {
                enrage(by);
                return super.damage(d);
            }
            return super.damage(d);
        }
        if (holeShot) {
            tameStart(TAME);
            game.mobs.onHurt(this, d, 0);
            return false;
        }
        if (part == 0) return super.damage(d.times(0.5));
        return super.damage(d);
    }

    /**
     * 맞은 부위(0 얼굴, 1 몸통, 2 엉덩이, 3 구멍). 엔진의 맞은 점은 탄 판정만큼 부푼 구(+0.25)의 겉이라 작은 구멍 구가 몸통 구에 가려진다:
     * 탄 방향이 있으면 맞은 점에서 그 방향으로 몸을 지나는 동안 탄 줄기가 구멍 구(여유 0.1)를 지나는지로 구멍을 가린다.
     */
    private int part(Vector p, Vector dir) {
        if (p == null) return 1;
        double[][] s = {at(FACE), at(TRUNK), at(REAR), at(HOLE)};
        double[] h = s[3];
        if (dir != null && dir.lengthSquared() > 1e-9) {
            Vector u = dir.clone().normalize();
            double hx = h[0] - p.getX(), hy = h[1] - p.getY(), hz = h[2] - p.getZ();
            double t = hx * u.getX() + hy * u.getY() + hz * u.getZ();
            if (t > -0.6 && t < 2.6) {
                double m = Math.sqrt(Math.max(0, hx * hx + hy * hy + hz * hz - t * t));
                if (m <= h[3] + 0.1) return 3;
            }
        } else {
            double dh = Math.sqrt(sq(p.getX() - h[0]) + sq(p.getY() - h[1]) + sq(p.getZ() - h[2]));
            if (dh <= h[3] + 0.3) return 3;
        }
        int best = 1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            double d = Math.abs(Math.sqrt(sq(p.getX() - s[i][0]) + sq(p.getY() - s[i][1]) + sq(p.getZ() - s[i][2])) - s[i][3] - 0.25);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private static double sq(double v) {
        return v * v;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        tossP = null;
        if (nail != null) {
            nail.remove();
            nail = null;
        }
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + (phase == Phase.TAME ? " tameLeft=" + (tameUntil - age) + " foe=" + (foe == null ? "-" : foe.sp.name()) : "")
            + (raging() ? " rage" : "");
    }
}
