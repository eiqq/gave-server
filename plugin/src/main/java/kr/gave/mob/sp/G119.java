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
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;

/**
 * G119 이름 연 게이브(3막 24라운드, 방해 + 정예, 6블록). 표적 한 사람의 이름이 이마에 적힌 방패연 얼굴(가운데 둥근 구멍이 입)이 하늘 14블록에서 좌우로
 * 크게 흔들리며 그 사람만 따라다니고, 늘어진 살 띠 연줄 끝 얼레가 땅 위 1블록에서 느리게(걷는 사람보다 느림) 그 사람을 쫓는다. 얼레가 닿으면 연줄이
 * 몸을 감고 연이 4초에 걸쳐 끌어 올려(초당 8, 못 쏨) 입 구멍으로 삼킨다(갇힘, 연이 죽으면 풀림). 얼레에 스친 다른 사람은 10 과 넘어짐.
 *
 * <p>대처: 이름 주인이 쏘면 두 배로 들어가고 되돌림이 없다. 다른 사람이 연을 쏘면 그 피해의 20%(초당 12 까지)가 이름 주인에게 간다. 연줄(체력 60 x 감독
 * 배율, Mobs.Shootable)을 쏴 끊으면 연이 날아가 버린다(보상 그대로, 되돌림 없음). 얼레는 느리니 걸어서 피한다.
 *
 * <p>몸: 닻 = 연 판(gave:gabe/g119 상태 "" / "pull", pitch 40 으로 아래를 봄), 부품 연줄(g119_line, 세계 축, 얼레까지 늘림), 얼레(g119_reel, 세계 축),
 * 이름표 text_display(G119Tag). 표적은 spawned() 에서 서 있는 사람 하나를 무작위로(쓰러지거나 갇히면 다시 뽑음).
 */
public final class G119 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 연 가운데 높이(바닥 위), 기울기(아래를 봄), 연줄이 매인 자리(판 기준 아래), 좌우 흔들림 폭. */
    static final double ALT = 14, PITCH = 40, TIE_Y = -3.2, SWAY = 5;
    /** 빠르기(블록/틱, 감독 배율 곱함): 멀 때, 표적 곁, 얼레(걷기 0.21 보다 느림), 연줄 최대 길이. */
    static final double FAR_V = 0.36, NEAR_V = 0.24, REEL_V = 0.16, LINE_MAX = 18;
    /** 끌어 올리기 틱, 그동안 20틱마다 피해, 붙잡기 탈출 클릭, 얼레 스침 피해, 넘어짐, 같은 사람 다시 스치는 틱. */
    static final int LIFT = 80, GRAB_CLICKS = 10, REEL_ROOT = 10, REEL_EVERY = 40, LINE_TICKS = 2;
    static final double LIFT_DMG = 8, REEL_DMG = 10, OWNER_MUL = 2, LINK = 0.2, LINK_CAP = 12, LINE_HP = 60, LINE_R = 0.45;

    enum Phase { HUNT, LIFT, AWAY }

    private Phase phase = Phase.HUNT;
    private int phaseAt, reelReady, linkAt;
    private double linkSum, lineHp;
    private boolean init, cut;
    private Player held;
    private final Vector reel = new Vector(), liftFrom = new Vector();
    private Body.Part linePart, reelPart;
    private G119Tag tag;
    private final Map<UUID, Integer> brushed = new HashMap<>();
    private Damage cutBy;
    private final Line line = new Line();
    private List<double[]> sph;
    private int sphAge = -1;
    private double swayA;
    private float keyYaw = Float.NaN;
    private boolean dragged;

    public G119(Game game, Species sp) {
        super(game, sp);
        radius = 2.0;
        baseSpeed = NEAR_V;
        noPush = true;
        noBite = true;
        noMutate = true;
        big = true;
        flying = true;
        leaveCorpse = false;
        pitch = (float) PITCH;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g119", 34f, G77Sky.boxH(ALT));
        body.viewRange(3f);
        linePart = body.attach("gabe/g119_line");
        reelPart = body.attach("gabe/g119_reel");
        if (linePart != null) linePart.follow = false;
        if (reelPart != null) reelPart.follow = false;
        tag = new G119Tag(this);
        G77Sky.bright(body);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pos.setY(floor() + ALT);
        reel.copy(pos).setY(floor() + 1);
        swayA = game.random.nextDouble() * Math.PI * 2;
        pick();
        game.mobs.addShootable(line);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 새 표적: 서 있는 사람 가운데 무작위(지금 표적 빼고 고를 수 있으면). */
    private void pick() {
        List<Player> ps = new ArrayList<>(game.mobs.standing());
        if (ps.size() > 1 && target != null) ps.remove(target);
        Player p = ps.isEmpty() ? null : ps.get(game.random.nextInt(ps.size()));
        mark(p);
        tag.set(p);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            lineHp = LINE_HP * G77Sky.hpK(this);
        }
        switch (phase) {
            case HUNT -> hunt();
            case LIFT -> lift();
            case AWAY -> away();
        }
        pitch = (float) PITCH;
        if (every(LINE_TICKS)) poseLine();
        keyframe();
        if (every(30)) {
            if (standing(target)) game.hud.mark(target, sp.key(), 50);
            tag.set(target);
        }
        if (every(90) && game.random.nextInt(2) == 0) sound("hum", Sound.ENTITY_PHANTOM_AMBIENT, 0.5f, 48);
    }

    private void hunt() {
        if (!standing(target) || game.players.get(target).grabbedBy != null && game.players.get(target).grabbedBy != this) {
            if (every(10)) pick();
        }
        double fl = floor();
        Vector goal;
        if (standing(target)) {
            Location l = target.getLocation();
            double a = swayA + age * 0.012;
            double s = Math.sin(age * 0.05) * SWAY;
            goal = new Vector(l.getX() + Math.cos(a) * s, 0, l.getZ() + Math.sin(a) * s);
        } else goal = G77Sky.center();
        double dist = G77Sky.flat(pos, goal);
        double v = (dist > 20 ? FAR_V : NEAR_V) * speed() / baseSpeed;
        double dx = goal.getX() - pos.getX(), dz = goal.getZ() - pos.getZ();
        double k = dist < 1e-6 ? 0 : Math.min(v, dist) / dist;
        vel.setX(dx * k).setZ(dz * k).setY(Math.max(-0.3, Math.min(0.3, (fl + ALT - pos.getY()) * 0.15)));
        if (standing(target) && G77Sky.flat(pos, target.getLocation()) > 2.5) turnTo(target.getLocation().toVector().setY(pos.getY()), 3f);
        // 얼레: 표적 발로 느리게(연줄 길이 안), 바닥 위 1
        Vector want = standing(target) ? target.getLocation().toVector() : new Vector(pos.getX(), 0, pos.getZ());
        moveReel(want, REEL_V * speed() / baseSpeed);
        // 스침, 붙잡기
        for (Player p : new ArrayList<>(game.mobs.standing())) {
            Location l = p.getLocation();
            if (Math.abs(l.getY() - (reel.getY() - 1)) > 1.6 || G77Sky.flat(reel, l) > 1.05) continue;
            if (p == target) {
                if (age >= reelReady && !dragged && grab(p, GRAB_CLICKS)) {
                    held = p;
                    game.players.get(p).grabDetach = false;
                    liftFrom.copy(reel);
                    state("pull");
                    sound("hum", Sound.ENTITY_PHANTOM_SWOOP, 0.6f, 48);
                    phase(Phase.LIFT);
                    return;
                }
                reelReady = Math.max(reelReady, age + 20);
            } else {
                Integer at = brushed.get(p.getUniqueId());
                if (at != null && age - at < cd(REEL_EVERY)) continue;
                brushed.put(p.getUniqueId(), age);
                hit(p, REEL_DMG, "cause_g119_reel");
                game.players.root(p, REEL_ROOT, "g119");
            }
        }
    }

    private void moveReel(Vector want, double v) {
        double fl = game.mobs.flow.floor(want.getX(), want.getZ());
        Vector to = new Vector(want.getX(), fl + 1, want.getZ());
        Vector d = to.subtract(reel);
        double l = d.length();
        if (l > 1e-6) reel.add(d.multiply(Math.min(v, l) / l));
        // 연줄 길이를 넘으면 연 쪽으로 끌려옴
        Vector tie = tie();
        Vector off = reel.clone().subtract(tie);
        // 연줄이 팽팽해 연에 끌려가는 동안은 얼레가 연 빠르기로 움직이니 잡지 못한다(얼레가 느리다는 대처가 살게)
        dragged = off.length() > LINE_MAX + 0.05;
        if (dragged) reel.copy(tie.add(off.normalize().multiply(LINE_MAX)));
        double rf = game.mobs.flow.floor(reel.getX(), reel.getZ()) + 1;
        if (reel.getY() < rf) reel.setY(rf);
    }

    /** 연줄이 매인 자리(판 아래 가운데, 세계). */
    private Vector tie() {
        Vector3f v = new Vector3f(0, (float) TIE_Y, 0);
        new Matrix4f().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch)).transformPosition(v);
        return new Vector(pos.getX() + v.x, pos.getY() + v.y, pos.getZ() + v.z);
    }

    private void lift() {
        if (held == null || !held.isOnline() || game.players.grabbedBy(held) != this) {
            held = null;
            state("");
            reelReady = age + cd(60);
            phase(Phase.HUNT);
            return;
        }
        int t = inPhase();
        double f = Math.min(1, t / (double) LIFT);
        f = f * f * (3 - 2 * f);
        Vector mouth = tie().add(new Vector(0, 0.6, 0));
        Vector at = liftFrom.clone().multiply(1 - f).add(mouth.clone().multiply(f));
        reel.copy(at);
        game.players.carry(held, at.clone().add(new Vector(0, 0.2, 0)));
        // 연이 솟구침
        vel.setX(vel.getX() * 0.8).setZ(vel.getZ() * 0.8).setY(Math.max(-0.2, Math.min(0.12, (floor() + ALT + 3 - pos.getY()) * 0.1)));
        if (t > 0 && t % 20 == 0) hit(held, LIFT_DMG, "cause_g119");
        if (t >= LIFT && held != null) {
            Player p = held;
            held = null;
            game.players.trap(p, this);
            sound("gulp", Sound.ENTITY_GENERIC_EAT, 0.5f, 32);
            state("");
            reelReady = age + cd(60);
            reel.copy(pos).setY(floor() + 1);
            mark(null);
            pick();
            phase(Phase.HUNT);
        }
    }

    /** 몸 자세 열쇠(4틱마다, 방향이 바뀌었을 때): 몸과 이름표를 같은 4틱 보간으로. */
    private void keyframe() {
        if (age % 4 != 0 || Math.abs(yaw - keyYaw) < 0.3f) return;
        keyYaw = yaw;
        body.pose(4);
        tag.follow(4);
    }

    // ------------------------------------------------------------------ 연줄 끊김: 날아감

    private void cutLine(Damage d) {
        if (cut) return;
        cut = true;
        cutBy = d;
        if (held != null && held.isOnline() && game.players.grabbedBy(held) == this) game.players.release(held);
        held = null;
        game.mobs.removeShootable(line);
        if (linePart != null) linePart.remove();
        if (reelPart != null) reelPart.remove();
        linePart = reelPart = null;
        sound("snap", Sound.ENTITY_ITEM_BREAK, 0.8f, 48);
        untargetable = true;
        mark(null);
        tag.set(null);
        phase(Phase.AWAY);
    }

    private void away() {
        Vector c = G77Sky.center();
        Vector out = pos.clone().subtract(c).setY(0);
        if (out.lengthSquared() < 1) out = new Vector(1, 0, 0);
        out.normalize().multiply(0.45);
        vel.setX(out.getX()).setZ(out.getZ()).setY(0.22);
        yaw += 9f;
        body.pose(2);
        if (inPhase() >= 50) {
            untargetable = false;
            die(cutBy != null ? cutBy : new Damage(0, DamageType.OTHER, null, pos.clone(), null, "line", 0));
        }
    }

    // ------------------------------------------------------------------ 연줄, 얼레 모습

    private void poseLine() {
        Vector tie = tie();
        if (reelPart != null)
            reelPart.pose(new Matrix4f().translate((float) (reel.getX() - pos.getX()), (float) (reel.getY() - pos.getY()), (float) (reel.getZ() - pos.getZ()))
                    .rotateY((float) Math.toRadians(-yaw)), LINE_TICKS);
        if (linePart != null) {
            Vector d = reel.clone().subtract(tie);
            float len = (float) Math.max(0.3, d.length());
            Vector3f u = new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ()).normalize();
            Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, -1, 0), u);
            linePart.pose(new Matrix4f().translate((float) (tie.getX() - pos.getX()), (float) (tie.getY() - pos.getY()), (float) (tie.getZ() - pos.getZ()))
                    .rotate(q).scale(1f, len, 1f), LINE_TICKS);
        }
    }

    // ------------------------------------------------------------------ 맞음

    /** 연 판 맞는 구: 가운데, 위, 아래, 양옆(판 기울기와 yaw 를 따름). */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(5);
        Matrix4f m = new Matrix4f().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch));
        double[][] pts = {{0, 0, 0, 1.9}, {0, 2.0, 0, 1.4}, {0, -2.0, 0, 1.4}, {1.75, 0.3, 0, 1.1}, {-1.75, 0.3, 0, 1.1}};
        for (double[] q : pts) {
            Vector3f v = new Vector3f((float) q[0], (float) q[1], (float) q[2]);
            m.transformPosition(v);
            out.add(new double[] {pos.getX() + v.x, pos.getY() + v.y, pos.getZ() + v.z, q[3]});
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        Player by = d.by();
        if (by != null && by == target) return super.damage(d.times(OWNER_MUL));
        if (by != null && standing(target)) {
            // 남이 쏘면 이름 주인이 아프다(초당 LINK_CAP 까지, 웨이브 배율 없이 쏜 피해에 비례)
            if (age - linkAt >= 20) {
                linkAt = age;
                linkSum = 0;
            }
            double x = Math.min(d.amount() * LINK, LINK_CAP - linkSum);
            if (x > 0.05) {
                linkSum += x;
                game.players.hurt(target, x, null, "cause_g119_link");
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        game.mobs.removeShootable(line);
        if (tag != null) tag.remove();
        if (held != null && held.isOnline() && game.players.grabbedBy(held) == this) game.players.release(held);
        held = null;
        super.die(d);
    }

    @Override
    public void remove() {
        game.mobs.removeShootable(line);
        if (tag != null) tag.remove();
        super.remove();
    }

    /** 연줄: 쏠 수 있는 가는 살 띠(연이 매인 자리에서 얼레까지 반지름 LINE_R 캡슐). */
    private final class Line implements Mobs.Shootable {
        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (cut || dead || linePart == null) return Double.POSITIVE_INFINITY;
            Vector a = tie(), b = reel;
            // 광선(o + t dir, 0..max)과 선분(a + s (b - a), 0..1) 사이 가장 가까운 두 점
            Vector u = dir, v = b.clone().subtract(a), w = o.clone().subtract(a);
            double uu = u.dot(u), uv = u.dot(v), vv = v.dot(v), uw = u.dot(w), vw = v.dot(w);
            double den = uu * vv - uv * uv;
            double t, s;
            if (den < 1e-9) {
                s = 0;
                t = -uw / uu;
            } else {
                t = (uv * vw - vv * uw) / den;
                s = (uu * vw - uv * uw) / den;
            }
            s = Math.max(0, Math.min(1, s));
            Vector q = a.clone().add(v.clone().multiply(s));
            t = Math.max(0, Math.min(max, q.clone().subtract(o).dot(u) / uu));
            Vector pt = o.clone().add(u.clone().multiply(t));
            if (pt.distanceSquared(q) > LINE_R * LINE_R) return Double.POSITIVE_INFINITY;
            return t;
        }

        @Override
        public boolean shot(Damage d) {
            if (cut || dead) return false;
            lineHp -= d.amount();
            game.mobs.onHurt(G119.this, d, d.amount());
            if (lineHp <= 0) cutLine(d);
            return false;
        }

        @Override
        public Vector center() {
            return tie().add(reel).multiply(0.5);
        }

        @Override
        public boolean gone() {
            return cut || dead || removed;
        }
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " target=" + (target == null ? "-" : target.getName()) + " line=" + Math.round(lineHp)
                + " reel=" + Math.round(G77Sky.flat(reel, target == null ? reel : target.getLocation().toVector()) * 10) / 10.0;
    }
}
