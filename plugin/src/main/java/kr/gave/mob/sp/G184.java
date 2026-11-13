package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

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
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G184 펼친 책 게이브(3막 29라운드, 지원, 방해, 10블록). 하늘 16블록에 펼쳐져 아래를 향한 폭 10블록 책. 책장은 얼굴 종이 아홉 장.
 * <ul>
 * <li>6초 x 박자마다 책장을 한 장 넘기고(넘긴 장 수가 하늘에 보임) 한 사람(쓰러진 적이 있는 사람 먼저) 발밑에 창백한 원을 1초 그린 뒤 책등 얼굴에서
 * 빛줄기를 6초 내린다. 원(반경 2.5)은 사람을 0.08블록/틱으로 따라가고, 원 안 사람은 20% 느려지고 초당 4.</li>
 * <li>표적이 원 안에 모두 2초 넘게 있으면 그 사람을 마지막으로 쓰러뜨린 종(PState.lastDownBy, 없으면 이번 웨이브의 위협 3 이하 종)이 곁 3블록에
 * 탈색된 채 되살아나 그 사람을 찍고 쫓는다(체력 60%, 주는 피해 1.3배).</li>
 * <li>아홉째 장을 읽고 나면 책을 탁 덮는다: 서 있는 모든 사람이 책 반대쪽으로 날아가며 10, 넘어짐 0.5초. 다시 펼친다.</li>
 * </ul>
 * 대처: 2초 안에 원 밖으로 달린다. 책등 얼굴이 약점(x2). 책갈피 막대기(부위 체력 200 x 감독 체력 배율)를 꺾으면 20초 동안 아무도 못 부른다.
 * 도감과 다른 점: '쓰러진 적이 없으면 가장 크게 다치게 한 종'은 기록이 없어 이번 웨이브의 위협 3 이하 종으로. 되살린 종의 피해 1.3배는 그 사람에게만이
 * 아니라 그 개체가 주는 모든 피해(Gabe.dmgMul).
 *
 * <p>몸: {@link Body#carrier} + 부품(책등 g184, 오른쪽 g184_r, 왼쪽 g184_l(반 바퀴), 넘어가는 장 g184_page: 몸 방향, 빛줄기 g184_beam, 원 g184_ring:
 * 세계 축). Gabe.pos = 책등 가운데(바닥 + 16).
 */
public final class G184 extends Gabe {

    static final double ALT = 16, DRIFT = 0.1, LEASH = 30, RING_R = 2.5, RING_FOLLOW = 0.08, BEAM_R = 1.1;
    static final int CYCLE = 120, TURN_T = 16, WARN_T = 20, BEAM_T = 120, CALL_T = 40, SHUT_T = 14, REOPEN_T = 30, MARK_OFF = 400;
    static final double BEAM_DPS = 4, SLOW = 20, WIND_DMG = 10, WIND_KNOCK = 1.2, WIND_UP = 0.3, CALL_HP = 0.6, CALL_DMG = 1.3, FACE_MUL = 2, MARK_HP = 200;
    static final int PAGES = 9;

    enum Phase { DRIFT, TURN, WARN, BEAM, SHUT, REOPEN }

    private Phase phase = Phase.DRIFT;
    private int phaseAt, readyAt, page, markUntil = -1;
    private boolean init, called;
    private double markHp;
    private Body.Part spine, right, left, leaf, beam, ring;
    private Player victim;
    private final Vector ringAt = new Vector();
    private int inside;
    private List<double[]> sph;
    private int sphAge = -1;

    public G184(Game game, Species sp) {
        super(game, sp);
        radius = 3;
        baseSpeed = DRIFT;
        flying = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        noRules = true;
        big = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        body.viewRange(4f);
        spine = body.attach("gabe/g184");
        right = body.attach("gabe/g184_r");
        left = body.attach("gabe/g184_l");
        if (left != null) left.pose(new Matrix4f().rotateY((float) Math.PI), 0);
        leaf = body.attach("gabe/g184_page");
        hide(leaf);
        beam = body.attach("gabe/g184_beam");
        if (beam != null) beam.follow = false;
        hide(beam);
        ring = body.attach("gabe/g184_ring");
        if (ring != null) {
            ring.follow = false;
            ring.look.freeze = true;
        }
        hide(ring);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pos.setY(floor() + ALT);
        yaw = game.random.nextFloat() * 360f;
        if (body != null) body.warp();
    }

    @Override
    public void lure(Vector at, int ticks) {
    }

    private static void hide(Body.Part p) {
        if (p != null) p.pose(new Matrix4f().scale(0.001f), 0);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            markHp = MARK_HP * G167Sky.hpK(this);
            readyAt = age + 60;
        }
        vel.zero();
        vel.setY(Math.max(-0.4, Math.min(0.4, floor() + ALT - pos.getY())));
        if (every(20)) for (Body.Part p : new Body.Part[] {spine, right, left, leaf}) G167Sky.light(p, G167Sky.FACE_LIGHT);
        if (markUntil >= 0 && age >= markUntil) {
            markUntil = -1;
            markHp = MARK_HP * G167Sky.hpK(this);
            if (spine != null) spine.look.on.remove("mark_cut");
        }
        driftOver();
        if (!G167Sky.wave(game)) return;
        switch (phase) {
            case DRIFT -> {
                if (age >= readyAt) startTurn();
            }
            case TURN -> turn();
            case WARN -> warn();
            case BEAM -> beamTick();
            case SHUT -> shut();
            case REOPEN -> reopen();
        }
    }

    /** 사람들 무게 중심 위로(가운데에서 LEASH 안). */
    private void driftOver() {
        Vector c = G167Sky.centroid(game);
        if (c == null) return;
        Vector mid = G167Sky.center();
        double dx = c.getX() - mid.getX(), dz = c.getZ() - mid.getZ(), d = Math.hypot(dx, dz);
        if (d > LEASH) {
            c.setX(mid.getX() + dx / d * LEASH).setZ(mid.getZ() + dz / d * LEASH);
        }
        double ex = c.getX() - pos.getX(), ez = c.getZ() - pos.getZ(), e = Math.hypot(ex, ez);
        if (e < 2) return;
        double v = Math.min(speed(), e);
        vel.setX(ex / e * v).setZ(ez / e * v);
    }

    private void startTurn() {
        if (page >= PAGES) {
            startShut();
            return;
        }
        sound("page", Sound.ITEM_BOOK_PAGE_TURN, 64);
        // 오른쪽 맨 아래 장이 경첩을 돌아 왼쪽으로(두 번에 90도씩: 쿼터니언 보간이 짧은 쪽으로 돌게)
        if (leaf != null) leaf.pose(new Matrix4f(), 0);
        phase(Phase.TURN);
    }

    private void turn() {
        if (inPhase() == 1 && leaf != null) leaf.pose(new Matrix4f().rotateZ((float) (Math.PI / 2)), TURN_T / 2);
        if (inPhase() == 1 + TURN_T / 2 && leaf != null) leaf.pose(new Matrix4f().rotateZ((float) Math.PI), TURN_T / 2);
        if (inPhase() == 1 + TURN_T / 2) {
            page++;
            setPage(page);
        }
        if (inPhase() < TURN_T + 2) return;
        hide(leaf);
        victim = pickVictim();
        if (victim == null) {
            readyAt = age + cd(CYCLE);
            phase(Phase.DRIFT);
            return;
        }
        Location l = victim.getLocation();
        ringAt.setX(l.getX()).setY(floor()).setZ(l.getZ());
        if (ring != null) ring.state("");
        poseRing(0);
        if (spine != null) spine.state("read");
        mark(victim);
        phase(Phase.WARN);
    }

    private void warn() {
        followRing();
        if (inPhase() < cd(WARN_T)) return;
        if (ring != null) ring.state("on");
        inside = 0;
        called = false;
        poseBeam(0);
        phase(Phase.BEAM);
    }

    private void beamTick() {
        followRing();
        if (every(4)) poseBeam(4);
        if (every(20)) G167Sky.light(beam, G167Sky.GLOW);
        int t = inPhase();
        boolean tick20 = t % 20 == 10;
        for (Player p : G167Sky.standing(game)) {
            if (G167Sky.flat(ringAt, p.getLocation()) > RING_R) continue;
            if (every(10)) game.players.slow(p, SLOW, 12, "g184_beam");
            if (tick20) hit(p, BEAM_DPS, "cause_g184");
            if (p == victim) inside++;
        }
        if (!called && inside >= CALL_T && G167Sky.up(game, victim) && markUntil < 0) {
            called = true;
            call(victim);
        }
        if (t >= BEAM_T) endBeam();
    }

    private void endBeam() {
        hide(beam);
        hide(ring);
        if (spine != null) spine.state("");
        mark(null);
        victim = null;
        readyAt = age + cd(CYCLE - BEAM_T / 2);
        phase(Phase.DRIFT);
    }

    private void startShut() {
        sound("page", Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 64);
        // 두 쪽이 책등을 축으로 위로 접혀 맞닿음
        if (right != null) right.pose(new Matrix4f().rotateZ((float) (Math.PI / 2)), SHUT_T);
        if (left != null) left.pose(new Matrix4f().rotateY((float) Math.PI).rotateZ((float) (Math.PI / 2)), SHUT_T);
        phase(Phase.SHUT);
    }

    private void shut() {
        if (inPhase() < SHUT_T) return;
        sound("slam", Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 96);
        game.horizon.shake(0.5, 10);
        Vector c = new Vector(pos.getX(), 0, pos.getZ());
        for (Player p : G167Sky.standing(game)) {
            Location l = p.getLocation();
            Vector away = new Vector(l.getX() - c.getX(), 0, l.getZ() - c.getZ());
            if (away.lengthSquared() < 1e-4) away = G167Sky.fwd(game.random.nextDouble() * 360);
            hit(p, WIND_DMG, "cause_g184_wind");
            if (G167Sky.up(game, p)) {
                knock(p, away, WIND_KNOCK, WIND_UP);
                game.players.root(p, 10, "g184_wind");
            }
        }
        page = 0;
        setPage(0);
        if (right != null) right.pose(new Matrix4f(), REOPEN_T);
        if (left != null) left.pose(new Matrix4f().rotateY((float) Math.PI), REOPEN_T);
        phase(Phase.REOPEN);
    }

    private void reopen() {
        if (inPhase() < REOPEN_T) return;
        readyAt = age + cd(CYCLE / 2);
        phase(Phase.DRIFT);
    }

    private void setPage(int n) {
        for (Body.Part p : new Body.Part[] {right, left}) {
            if (p == null) continue;
            for (int i = 1; i <= PAGES; i++) {
                if (i <= n) p.look.on.add("p" + i);
                else p.look.on.remove("p" + i);
            }
        }
    }

    /** 원이 표적을 느리게 따라감. */
    private void followRing() {
        if (G167Sky.up(game, victim)) {
            Location l = victim.getLocation();
            double dx = l.getX() - ringAt.getX(), dz = l.getZ() - ringAt.getZ(), d = Math.hypot(dx, dz);
            if (d > 0.01) {
                double v = Math.min(RING_FOLLOW, d);
                ringAt.setX(ringAt.getX() + dx / d * v).setZ(ringAt.getZ() + dz / d * v);
            }
        }
        ringAt.setY(game.mobs.flow.floor(ringAt.getX(), ringAt.getZ()));
        if (every(2)) poseRing(2);
    }

    /** 서 있는 사람 가운데 쓰러진 적이 있는 사람 먼저(가장 최근), 없으면 책 밑에서 가까운 사람. */
    private Player pickVictim() {
        Player best = null;
        long bestAt = -1;
        double bd = Double.MAX_VALUE;
        for (Player p : G167Sky.standing(game)) {
            PState s = game.players.get(p);
            long at = s.lastDownBy != null ? s.lastDownAt : -1;
            double d = G167Sky.flat(pos, p.getLocation());
            if (at > bestAt || at == bestAt && d < bd) {
                best = p;
                bestAt = at;
                bd = d;
            }
        }
        return best;
    }

    /** 그 사람을 마지막으로 쓰러뜨렸던 종을 곁에 되살림. */
    private void call(Player p) {
        PState s = game.players.get(p);
        List<Species> tries = new ArrayList<>();
        if (s.lastDownBy != null && callable(s.lastDownBy)) tries.add(s.lastDownBy);
        List<Species> wave = new ArrayList<>();
        for (Species w : game.director.waveSpecies()) if (callable(w) && w.threat <= 3) wave.add(w);
        java.util.Collections.shuffle(wave, game.random);
        tries.addAll(wave);
        if (tries.isEmpty()) tries.add(Species.G01);
        Location l = p.getLocation();
        for (Species sp2 : tries) {
            double a = game.random.nextDouble() * Math.PI * 2;
            Vector at = new Vector(l.getX() + Math.cos(a) * 3, floor() + sp2.band.hover, l.getZ() + Math.sin(a) * 3);
            Gabe g = child(sp2, at);
            if (g == null) continue;
            if (g.big) {
                g.remove();
                continue;
            }
            g.maxHp = Math.max(1, sp2.hp * game.director.hpMul(sp2) * CALL_HP);
            g.hp = g.maxHp;
            g.dmgMul *= CALL_DMG;
            if (g.body != null) g.body.look.palette |= Look.PAL_PALE;
            g.mark(p);
            sound("call", Sound.ENTITY_VEX_AMBIENT, 0.6f, 32);
            G167Sky.smoke(game, at.clone().add(new Vector(0, 0.8, 0)));
            return;
        }
    }

    private static boolean callable(Species s) {
        return s.implemented() && !s.is(Role.BOSS) && s.spawn != Species.Spawn.DERIVED && s.spawn != Species.Spawn.PLACED && s != Species.G184
                && !(s.band == kr.gave.mob.Band.SKY && s.cost >= 3);
    }

    // ------------------------------------------------------------------ 몸

    private void poseRing(int ticks) {
        if (ring == null) return;
        ring.pose(new Matrix4f().translate((float) (ringAt.getX() - pos.getX()), (float) (ringAt.getY() + 0.03 - pos.getY()), (float) (ringAt.getZ() - pos.getZ()))
                .scale((float) RING_R, 1f, (float) RING_R), ticks);
    }

    /** 빛줄기: 책등 얼굴 밑에서 원 가운데로. */
    private void poseBeam(int ticks) {
        if (beam == null) return;
        Vector top = new Vector(0, -0.8, 0);
        Vector to = new Vector(ringAt.getX() - pos.getX(), ringAt.getY() - pos.getY(), ringAt.getZ() - pos.getZ());
        Vector d = to.clone().subtract(top);
        double len = d.length();
        if (len < 0.5) return;
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, -1, 0), new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ()).normalize());
        float r = (float) (BEAM_R / 0.5);
        beam.pose(new Matrix4f().translate((float) top.getX(), (float) top.getY(), (float) top.getZ()).rotate(q).scale(r, (float) len, r), ticks);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    /** 맞는 구: 0 책장(양쪽 열둘), 1 책등 얼굴(약점), 2 책갈피. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(20);
        double y = pos.getY();
        boolean closed = phase == Phase.SHUT || phase == Phase.REOPEN && inPhase() < REOPEN_T / 2;
        if (closed) {
            for (int i = 0; i < 3; i++) out.add(local(0, 1.8, -2.4 + i * 2.4, 2.0, 0));
        } else {
            for (int side = -1; side <= 1; side += 2) {
                for (double x : new double[] {1.4, 3.7}) {
                    for (double z : new double[] {-2.3, 0, 2.3}) out.add(local(side * x, -0.1, z, 1.3, 0));
                }
            }
        }
        out.add(local(0, -0.45, 0, 1.0, 1));
        if (markUntil < 0) for (double k = 1.0; k <= 4.8; k += 0.9) out.add(local(0.05 + 0.06 * k, -0.1 - k, 2.2 - 0.05 * k, 0.35, 2));
        sph = out;
        sphAge = age;
        return out;
    }

    /** 몸 기준 (x 왼쪽, y, z 앞) → 세계 구. */
    private double[] local(double x, double y, double z, double r, int part) {
        double a = Math.toRadians(yaw), c = Math.cos(a), s = Math.sin(a);
        return new double[] {pos.getX() + x * c - z * s, pos.getY() + y, pos.getZ() + x * s + z * c, r, part};
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || unhittable()) return false;
        int part = G167Sky.partAt(spheres(), d.at());
        Damage dd = part == 1 ? d.times(FACE_MUL) : d;
        double amt = dd.amount();
        hp -= amt;
        game.mobs.onHurt(this, dd);
        if (part == 2 && markUntil < 0) {
            markHp -= amt;
            if (markHp <= 0) {
                markUntil = age + MARK_OFF;
                if (spine != null) spine.look.on.add("mark_cut");
                sound("snap", Sound.ENTITY_ITEM_BREAK, 0.8f, 48);
            }
        }
        if (hp <= 0) die(dd);
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        for (int i = 0; i < 4; i++) G167Sky.smoke(game, pos.clone().add(new Vector(game.random.nextGaussian() * 2.5, -0.5, game.random.nextGaussian() * 2.5)));
        sound("slam", Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 96);
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " page=" + page + " victim=" + (victim == null ? "-" : victim.getName()) + " inside=" + inside + " called="
                + called + (markUntil >= 0 ? " markCut" : "") + " ring=" + Math.round(ringAt.getX()) + "," + Math.round(ringAt.getZ());
    }
}
