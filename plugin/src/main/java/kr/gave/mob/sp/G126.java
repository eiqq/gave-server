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
import kr.gave.mob.Corpse;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Role;
import kr.gave.mob.Species;

/**
 * G126 줄 인형 부리는 게이브(3막 27라운드, 지원 + 방해, 10블록). 바닥 위 14블록에 뜬 열십자 조종대(가운데에 아래를 보고 엎드린 3블록 얼굴)가 싸움터
 * 위를 천천히 떠다니며 사체가 많은 곳으로 간다. 줄을 내려 게이브 사체를 꿰어 인형으로 일으킨다(1.5초에 하나, 여섯까지). 줄 인형은 고개가 꺾인 채 줄에
 * 들려 춤추듯 덜렁거리며 사람에게 달려들어 정수리로 들이받는다(9 와 밀침). 인형을 쓰러뜨려도 3초 뒤 줄이 당겨 다시 일으킨다. 6초마다 조종대를 크게
 * 기울여 인형들을 한 사람에게 한꺼번에 내리찍는다(빨간 원 1.2초 뒤 반경 2.5 안 30 과 넘어짐 1초). 줄에 걸린 사람은 4블록 들렸다 떨어진다(12).
 *
 * <p>대처: 인형이 아니라 줄을 쏜다(줄마다 체력 40 x 감독 배율, 인형 머리 위 2블록까지 맞는 구가 굵다). 줄이 끊긴 인형은 쓰러져 다시 못 일어난다. 줄이 다
 * 끊기면 조종대가 균형을 잃고 바닥 위 6블록까지 내려와 5초 흔들린다(가운데 얼굴 x2).
 *
 * <p>몸: 닻 = 조종대(gave:gabe/g126 상태 "" / "tilt"), 부품 열십자 그림자(g126_shadow), 인형마다 줄(g126_string)과 인형(사체 종 모델을 얼린 채 든
 * 부품), 내리찍기 예고 원(g126_disc "warn"). 인형은 따로 가짜 몹이 아니라 이 개체의 부품과 맞는 구다(보상 0). 한 웨이브 하나(감독 표 요청).
 */
public final class G126 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 높이(바닥 위), 흔들릴 때 높이 차(바닥 위 6), 빠르기(초당 2블록, 멀리서 들어올 때), 멀다고 치는 거리. */
    static final double ALT = 14, DY_STAGGER = 6 - ALT, DRIFT_V = 0.1, FAR_V = 0.22, FAR = 35;
    /** 인형: 수, 꿰는 데 드는 틱, 꿰는 거리(조종대에서), 줄에 매인 거리 한도(수평), 빠르기, 박치기 피해와 간격, 쓰러졌다 일어나는 틱. */
    static final int MAX_PUPPETS = 6, RAISE = 30, HIT_EVERY = 30, GET_UP = 60;
    static final double RAISE_R = 16, TETHER = 13, PUPPET_V = 0.2, PUPPET_DMG = 9, PUPPET_HP = 60, LINE_HP = 40, MAX_SIZE = 1.9;
    /** 내리찍기: 간격, 예고, 반경, 피해, 넘어짐. 줄 걸림: 거리, 피해, 들림, 다시 걸리는 틱. 흔들림 틱, 얼굴 배율. */
    static final int SLAM_EVERY = 120, SLAM_TELL = 24, SLAM_ROOT = 20, CATCH_EVERY = 60, STAGGER = 100;
    static final double SLAM_R = 2.5, SLAM_DMG = 30, CATCH_R = 0.75, CATCH_DMG = 12, CATCH_UP = 0.95, FACE_MUL = 2;
    /** 줄이 매인 자리(조종대 기준): 네 끝, 가운데 둘. */
    static final double[][] SLOTS = {{5, -0.6, 0}, {-5, -0.6, 0}, {0, 0, 5}, {0, 0, -5}, {1.3, -1.6, 1.3}, {-1.3, -1.6, -1.3}};

    private static final class Puppet {
        int slot;
        String model;
        Species sp;
        float lift, size;
        Body.Part doll, line;
        final Vector at = new Vector();
        double hp, maxHp, lineHp;
        int downUntil = -1, readyAt;
        boolean gone;
        float face;
        Player prey;
    }

    private static final class Raise {
        int slot, at;
        Corpse corpse;
        Body.Part line;
    }

    enum Phase { ROAM, SLAM, STAGGER, RISE }

    private Phase phase = Phase.ROAM;
    private int phaseAt, raiseAt = 20, slamAt = 140, cutSince;
    private final G77Sky.Glide dyG = new G77Sky.Glide();
    private final Puppet[] puppets = new Puppet[MAX_PUPPETS];
    private Raise raising;
    private Body.Part shadow, warn;
    private final Vector goal = new Vector(), slamAtPos = new Vector();
    private final Map<UUID, Integer> caught = new HashMap<>();
    private List<double[]> sph;
    private int sphAge = -1;

    public G126(Game game, Species sp) {
        super(game, sp);
        radius = 2.0;
        baseSpeed = DRIFT_V;
        noPush = true;
        noBite = true;
        noMutate = true;
        big = true;
        flying = true;
        ragKick = 0.3;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g126", 30f, G77Sky.boxH(ALT));
        body.viewRange(3f);
        dyG.snap(0);
        shadow = body.attach("gabe/g126_shadow");
        G77Sky.bright(body);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pos.setY(floor() + ALT);
        yaw = game.random.nextInt(4) * 90f + 45f;
        pitch = 0;
        poseShadow();
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private Vector slotAt(int i) {
        double[] s = SLOTS[i];
        Vector w = G77Sky.local(s[0], s[1], s[2], yaw);
        return new Vector(pos.getX() + w.getX(), pos.getY() + dyG.at(now()) + w.getY(), pos.getZ() + w.getZ());
    }

    private int active() {
        int n = 0;
        for (Puppet p : puppets) if (p != null && !p.gone) n++;
        return n;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        vel.setY(Math.max(-0.3, Math.min(0.3, (floor() + ALT - pos.getY()) * 0.15)));
        switch (phase) {
            case ROAM -> roam();
            case SLAM -> slam();
            case STAGGER -> stagger();
            case RISE -> {
                vel.setX(0).setZ(0);
                if (inPhase() >= 20) phase(Phase.ROAM);
            }
        }
        raiseTick();
        puppetsTick();
        if (age % 5 == 0) catchTick();
        if (every(100) && game.random.nextInt(2) == 0) sound("creak", Sound.BLOCK_WOODEN_DOOR_OPEN, 0.5f, 48);
    }

    private void roam() {
        double k = speed() / baseSpeed;
        if (every(40) || goal.lengthSquared() == 0) pickGoal();
        double d = G77Sky.flat(pos, goal);
        double v = (d > FAR ? FAR_V : DRIFT_V) * k;
        if (d > 0.8) vel.setX((goal.getX() - pos.getX()) / d * Math.min(v, d)).setZ((goal.getZ() - pos.getZ()) / d * Math.min(v, d));
        else vel.setX(0).setZ(0);
        if (active() >= 2 && age >= slamAt) startSlam();
        else if (active() == 0 && raising == null && cutSince > 0) startStagger();
    }

    /** 갈 곳: 인형이 있으면 사람 무리 쪽, 없으면 사람 곁 사체가 가장 많이 모인 곳(없으면 사람 무리). */
    private void pickGoal() {
        Vector c = G77Sky.centroid(game);
        if (c == null) c = G77Sky.center();
        if (active() >= 3) {
            goal.copy(c);
            return;
        }
        Corpse best = null;
        int bestN = 0;
        List<Corpse> cs = game.mobs.corpses();
        for (Corpse a : cs) {
            if (a.gone() || !usable(a) || G77Sky.flat(a.pos, c) > 36) continue;
            int n = 0;
            for (Corpse b : cs) if (!b.gone() && G77Sky.flat(a.pos, b.pos) < 8) n++;
            if (n > bestN) {
                bestN = n;
                best = a;
            }
        }
        goal.copy(best != null ? best.pos : c).setY(0);
    }

    // ------------------------------------------------------------------ 꿰어 일으키기

    private boolean usable(Corpse c) {
        if (c.sp == null || c.sp.is(Role.BOSS)) return false;
        String m = "gabe/" + c.sp.key();
        if (!Look.has(m)) return false;
        Look.Rag r = Look.rag(m, "");
        if (r == null || r.box() == null) return true;
        float[] b = r.box();
        return Math.max(b[3], Math.max(b[4], b[5])) <= MAX_SIZE;
    }

    private int freeSlot() {
        for (int i = 0; i < MAX_PUPPETS; i++) if ((puppets[i] == null || puppets[i].gone && puppets[i].doll == null) && (raising == null || raising.slot != i))
            return i;
        return -1;
    }

    private void raiseTick() {
        if (raising != null) {
            Raise r = raising;
            if (r.corpse.gone() || dead) {
                if (r.line != null) r.line.remove();
                raising = null;
                return;
            }
            Vector b = slotAt(r.slot);
            Vector to = r.corpse.pos.clone().add(new Vector(0, 0.5, 0));
            double f = Math.min(1, (age - r.at) / (double) RAISE);
            if (r.line != null && (age - r.at) % 2 == 0) poseLine(r.line, b, b.clone().add(to.clone().subtract(b).multiply(Math.max(0.05, f))), 2);
            if (f >= 1) {
                raising = null;
                if (r.line != null) r.line.remove();
                Corpse c = r.corpse;
                Vector at = c.pos.clone();
                if (game.mobs.eat(c)) makePuppet(r.slot, c.sp, at);
                raiseAt = age + 4;
            }
            return;
        }
        if (age < raiseAt || phase == Phase.STAGGER || phase == Phase.SLAM) return;
        int slot = freeSlot();
        if (slot < 0) return;
        Corpse best = null;
        double bd = RAISE_R;
        for (Corpse c : game.mobs.corpses()) {
            if (c.gone() || !usable(c)) continue;
            double d = G77Sky.flat(pos, c.pos);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        if (best == null) {
            raiseAt = age + 10;
            return;
        }
        Raise r = new Raise();
        r.slot = slot;
        r.at = age;
        r.corpse = best;
        r.line = body.attach("gabe/g126_string");
        if (r.line != null) {
            r.line.follow = false;
            Vector b = slotAt(slot);
            poseLine(r.line, b, b.clone().add(new Vector(0, -0.3, 0)), 0);
        }
        raising = r;
        sound("creak", Sound.BLOCK_WOODEN_DOOR_OPEN, 0.6f, 48);
    }

    private void makePuppet(int slot, Species s, Vector at) {
        Puppet p = new Puppet();
        p.slot = slot;
        p.sp = s;
        p.model = "gabe/" + s.key();
        Look.Rag r = Look.rag(p.model, "");
        float[] b = r != null ? r.box() : null;
        p.size = b != null ? Math.max(0.35f, Math.max(b[3], b[5])) : 0.5f;
        p.lift = b != null ? b[4] - b[1] + 0.25f : 0.8f;
        p.maxHp = p.hp = PUPPET_HP * G77Sky.hpK(this);
        p.lineHp = LINE_HP * G77Sky.hpK(this);
        p.at.copy(at).setY(game.mobs.flow.floor(at.getX(), at.getZ()));
        p.doll = body.attach(p.model);
        p.line = body.attach("gabe/g126_string");
        if (p.doll != null) {
            G77Sky.bright(p.doll.d);
            p.doll.follow = false;
            p.doll.look.freeze = true;
            p.doll.look.open = false;
        }
        if (p.line != null) p.line.follow = false;
        p.face = game.random.nextFloat() * 360f;
        p.readyAt = age + 20;
        // 처음엔 누운 채 줄에 끌려 일어남
        p.downUntil = age + 12;
        puppets[slot] = p;
        posePuppet(p, 0);
    }

    // ------------------------------------------------------------------ 인형 움직임

    private void puppetsTick() {
        boolean send = age % 2 == 0;
        for (Puppet p : puppets) {
            if (p == null || p.gone) continue;
            if (age < p.downUntil) {
                if (send) posePuppet(p, 2);
                continue;
            }
            if (p.downUntil >= 0 && age >= p.downUntil) {
                p.downUntil = -1;
                p.hp = p.maxHp;
            }
            if (phase != Phase.SLAM) chase(p);
            if (send) posePuppet(p, 2);
        }
    }

    private void chase(Puppet p) {
        if (p.prey == null || !standing(p.prey) || every(20)) {
            Player best = null;
            double bd = 26;
            for (Player q : game.mobs.standing()) {
                double d = G77Sky.flat(p.at, q.getLocation());
                if (d < bd) {
                    bd = d;
                    best = q;
                }
            }
            p.prey = best;
        }
        Vector to = p.prey != null ? p.prey.getLocation().toVector() : new Vector(pos.getX(), 0, pos.getZ());
        double d = G77Sky.flat(p.at, to);
        double v = PUPPET_V * game.director.speedMul();
        if (d > 0.6) {
            double s = Math.min(v, d) / d;
            p.at.add(new Vector((to.getX() - p.at.getX()) * s, 0, (to.getZ() - p.at.getZ()) * s));
            p.face = (float) Math.toDegrees(Math.atan2(-(to.getX() - p.at.getX()), to.getZ() - p.at.getZ()));
        }
        // 줄에 매인 거리
        Vector b = slotAt(p.slot);
        double off = G77Sky.flat(p.at, b);
        if (off > TETHER) {
            double s = TETHER / off;
            p.at.setX(b.getX() + (p.at.getX() - b.getX()) * s).setZ(b.getZ() + (p.at.getZ() - b.getZ()) * s);
        }
        p.at.setY(game.mobs.flow.floor(p.at.getX(), p.at.getZ()));
        if (p.prey != null && age >= p.readyAt) {
            Location l = p.prey.getLocation();
            if (G77Sky.flat(p.at, l) <= 1.0 + p.size && Math.abs(l.getY() - p.at.getY()) < 2.2) {
                p.readyAt = age + cd(HIT_EVERY);
                hit(p.prey, PUPPET_DMG, "cause_g126");
                Vector dir = l.toVector().subtract(p.at).setY(0);
                knock(p.prey, dir.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : dir, 0.6, 0.2);
            }
        }
    }

    /** 인형 머리(줄이 매인 곳) 자리. */
    private Vector head(Puppet p) {
        double hop = p.downUntil >= 0 ? 0 : Math.abs(Math.sin((age + p.slot * 7) * 0.35)) * 0.7;
        return new Vector(p.at.getX(), p.at.getY() + p.lift + hop + p.size * 0.7, p.at.getZ());
    }

    private void posePuppet(Puppet p, int ticks) {
        boolean down = p.downUntil >= 0 && age < p.downUntil;
        double hop = down ? 0 : Math.abs(Math.sin((age + p.slot * 7) * 0.35)) * 0.7;
        if (p.doll != null) {
            double y = down ? p.at.getY() + p.size * 0.8 : p.at.getY() + p.lift + hop;
            float wob = down ? 0 : (float) Math.sin((age + p.slot * 11) * 0.3) * 0.35f;
            Matrix4f m = new Matrix4f().translate((float) (p.at.getX() - pos.getX()), (float) (y - pos.getY()), (float) (p.at.getZ() - pos.getZ()))
                    .rotateY((float) Math.toRadians(-p.face)).rotateX((float) Math.toRadians(down ? -88 : 38)).rotateZ(wob);
            p.doll.pose(m, ticks);
        }
        if (p.line != null) poseLine(p.line, slotAt(p.slot), head(p), ticks);
    }

    private void poseLine(Body.Part line, Vector from, Vector to, int ticks) {
        Vector d = to.clone().subtract(from);
        float len = (float) Math.max(0.2, d.length());
        Vector3f u = new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ());
        if (u.lengthSquared() < 1e-6) u.set(0, -1, 0);
        u.normalize();
        line.pose(new Matrix4f().translate((float) (from.getX() - pos.getX()), (float) (from.getY() - pos.getY()), (float) (from.getZ() - pos.getZ()))
                .rotate(new Quaternionf().rotationTo(new Vector3f(0, -1, 0), u)).scale(1f, len, 1f), ticks);
    }

    /** 조종대 아래 줄에 걸린 사람: 4블록 들렸다 떨어짐. */
    private void catchTick() {
        for (Player q : new ArrayList<>(game.mobs.standing())) {
            Integer at = caught.get(q.getUniqueId());
            if (at != null && age - at < CATCH_EVERY) continue;
            Vector c = q.getLocation().toVector().add(new Vector(0, 1.0, 0));
            for (Puppet p : puppets) {
                if (p == null || p.gone || p.line == null) continue;
                Vector a = slotAt(p.slot), b = head(p);
                if (segDist(c, a, b) <= CATCH_R && c.getY() > b.getY() - 0.2) {
                    caught.put(q.getUniqueId(), age);
                    hit(q, CATCH_DMG, "cause_g126_line");
                    knock(q, new Vector(0, 0, 0), 0, CATCH_UP);
                    sound("creak", Sound.BLOCK_WOODEN_DOOR_OPEN, 0.8f, 32);
                    break;
                }
            }
        }
    }

    private static double segDist(Vector c, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double l2 = ab.lengthSquared();
        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, c.clone().subtract(a).dot(ab) / l2));
        return a.clone().add(ab.multiply(t)).distance(c);
    }

    // ------------------------------------------------------------------ 한꺼번에 내리찍기

    private void startSlam() {
        Player t = null;
        double bd = 30;
        Vector mid = new Vector();
        int n = 0;
        for (Puppet p : puppets) if (p != null && !p.gone) {
            mid.add(p.at);
            n++;
        }
        mid.multiply(1.0 / Math.max(1, n));
        for (Player q : game.mobs.standing()) {
            double d = G77Sky.flat(mid, q.getLocation());
            if (d < bd) {
                bd = d;
                t = q;
            }
        }
        if (t == null) {
            slamAt = age + 40;
            return;
        }
        Location l = t.getLocation();
        slamAtPos.setX(l.getX()).setZ(l.getZ()).setY(game.mobs.flow.floor(l.getX(), l.getZ()));
        mark(t);
        state("tilt");
        body.roll = 18f;
        body.pose(10);
        if (warn != null) warn.remove();
        warn = body.attach("gabe/g126_disc");
        if (warn != null) {
            warn.follow = false;
            warn.state("warn");
            warn.look.freeze = true;
            warn.pose(new Matrix4f().translate((float) (slamAtPos.getX() - pos.getX()), (float) (slamAtPos.getY() + 0.07 - pos.getY()),
                    (float) (slamAtPos.getZ() - pos.getZ())).scale((float) SLAM_R, 1f, (float) SLAM_R), 0);
        }
        sound("creak", Sound.BLOCK_WOODEN_DOOR_OPEN, 0.4f, 64);
        phase(Phase.SLAM);
    }

    private void slam() {
        vel.setX(0).setZ(0);
        int t = inPhase();
        // 인형들이 줄에 들려 그 자리 위로 모임
        int i = 0;
        for (Puppet p : puppets) {
            if (p == null || p.gone || age < p.downUntil) continue;
            double a = i++ * Math.PI / 3;
            Vector to = slamAtPos.clone().add(new Vector(Math.cos(a) * 1.0, 0, Math.sin(a) * 1.0));
            double d = G77Sky.flat(p.at, to);
            double v = Math.max(0.25, d / Math.max(1, SLAM_TELL - t));
            if (d > 0.1) p.at.add(to.clone().subtract(p.at).setY(0).multiply(Math.min(1, v / d)));
            p.at.setY(game.mobs.flow.floor(p.at.getX(), p.at.getZ()) + (t < SLAM_TELL - 3 ? Math.min(3.5, t * 0.3) : 0));
        }
        if (t >= SLAM_TELL) {
            for (Player q : new ArrayList<>(game.mobs.standing())) {
                Location l = q.getLocation();
                if (Math.hypot(l.getX() - slamAtPos.getX(), l.getZ() - slamAtPos.getZ()) <= SLAM_R && Math.abs(l.getY() - slamAtPos.getY()) < 3) {
                    hit(q, SLAM_DMG, "cause_g126_slam");
                    game.players.root(q, SLAM_ROOT, "g126");
                }
            }
            for (Puppet p : puppets) if (p != null && !p.gone) {
                p.at.setY(game.mobs.flow.floor(p.at.getX(), p.at.getZ()));
                p.downUntil = age + 20;
            }
            G77Sky.quake(game, new Location(game.world, slamAtPos.getX(), slamAtPos.getY(), slamAtPos.getZ()), (float) SLAM_R + 1);
            sound("slam", Sound.ENTITY_IRON_GOLEM_ATTACK, 0.5f, 48);
            game.horizon.shake(0.3, 6);
            if (warn != null) warn.remove();
            warn = null;
            state("");
            body.roll = 0f;
            body.pose(12);
            mark(null);
            slamAt = age + cd(SLAM_EVERY);
            phase(Phase.ROAM);
        }
    }

    // ------------------------------------------------------------------ 줄이 다 끊겨 흔들림

    private void startStagger() {
        cutSince = 0;
        dyG.go(DY_STAGGER, now(), 16);
        body.offset.set(0, (float) DY_STAGGER, 0);
        body.pose(16);
        sound("creak", Sound.BLOCK_WOODEN_DOOR_OPEN, 0.3f, 64);
        phase(Phase.STAGGER);
    }

    private void stagger() {
        vel.setX(0).setZ(0);
        int t = inPhase();
        if (t % 10 == 0) {
            body.roll = (t / 10) % 2 == 0 ? 14f : -14f;
            body.pose(9);
        }
        if (t >= STAGGER) {
            body.roll = 0;
            dyG.go(0, now(), 20);
            body.offset.set(0, 0, 0);
            body.pose(20);
            raiseAt = age + 40;
            phase(Phase.RISE);
        }
    }

    // ------------------------------------------------------------------ 그림자

    private void poseShadow() {
        if (shadow == null) return;
        shadow.pose(new Matrix4f().translate(0, (float) (floor() + 0.06 - pos.getY()), 0), 0);
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: [x, y, z, r, 부위] 부위 0 가운데 얼굴, 1 조종대 막대기, 100 + 칸 줄, 200 + 칸 인형. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(40);
        double cy = pos.getY() + dyG.at(now());
        out.add(new double[] {pos.getX(), cy - 0.5, pos.getZ(), 1.7, 0});
        for (double[] s : new double[][] {{2.8, 0.3, 0}, {-2.8, 0.3, 0}, {0, 0.9, 2.8}, {0, 0.9, -2.8}}) {
            Vector w = G77Sky.local(s[0], s[1], s[2], yaw);
            out.add(new double[] {pos.getX() + w.getX(), cy + w.getY(), pos.getZ() + w.getZ(), 0.9, 1});
        }
        for (Puppet p : puppets) {
            if (p == null || p.gone) continue;
            Vector h = head(p);
            out.add(new double[] {p.at.getX(), p.at.getY() + p.lift, p.at.getZ(), p.size + 0.1, 200 + p.slot});
            Vector b = slotAt(p.slot);
            Vector d = b.clone().subtract(h);
            double len = d.length();
            if (len < 1e-6) continue;
            d.multiply(1 / len);
            out.add(new double[] {h.getX() + d.getX() * 0.7, h.getY() + d.getY() * 0.7, h.getZ() + d.getZ() * 0.7, 0.6, 100 + p.slot});
            out.add(new double[] {h.getX() + d.getX() * 1.7, h.getY() + d.getY() * 1.7, h.getZ() + d.getZ() * 1.7, 0.6, 100 + p.slot});
            for (double s = 3.0; s < len - 0.5; s += 2.6)
                out.add(new double[] {h.getX() + d.getX() * s, h.getY() + d.getY() * s, h.getZ() + d.getZ() * s, 0.32, 100 + p.slot});
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        int part = 1;
        if (d.at() != null) {
            double best = Double.MAX_VALUE;
            for (double[] s : spheres()) {
                double dx = s[0] - d.at().getX(), dy = s[1] - d.at().getY(), dz = s[2] - d.at().getZ();
                double dd = Math.sqrt(dx * dx + dy * dy + dz * dz) - s[3];
                if (dd < best) {
                    best = dd;
                    part = (int) s[4];
                }
            }
        }
        if (part >= 100) {
            Puppet p = puppets[part % 100];
            if (p == null || p.gone) return super.damage(d);
            game.mobs.onHurt(this, d, d.amount());
            if (part >= 200) {
                if (p.downUntil < 0 || age >= p.downUntil) {
                    p.hp -= d.amount();
                    if (p.hp <= 0) {
                        p.downUntil = age + GET_UP;
                        p.at.setY(game.mobs.flow.floor(p.at.getX(), p.at.getZ()));
                        if (p.doll != null) p.doll.look.hurtUntil = game.worldTime() + 3;
                    }
                }
            } else {
                p.lineHp -= d.amount();
                if (p.lineHp <= 0) cutLine(p);
            }
            return true;
        }
        if (part == 0 && phase == Phase.STAGGER) return super.damage(d.times(FACE_MUL));
        return super.damage(d);
    }

    private void cutLine(Puppet p) {
        p.gone = true;
        cutSince++;
        sph = null;
        sound("snap", Sound.ENTITY_ITEM_BREAK, 1.2f, 40);
        if (p.line != null) p.line.remove();
        p.line = null;
        // 줄 끊긴 인형은 그 자리에 쓰러져 다시 못 일어남: 잠깐 누웠다 땅으로
        if (p.doll != null) {
            Body.Part doll = p.doll;
            doll.pose(new Matrix4f().translate((float) (p.at.getX() - pos.getX()), (float) (p.at.getY() + p.size * 0.6 - pos.getY()),
                    (float) (p.at.getZ() - pos.getZ())).rotateY((float) Math.toRadians(-p.face)).rotateX((float) Math.toRadians(-88)), 6);
            game.plugin.getServer().getScheduler().runTaskLater(game.plugin, doll::remove, 40);
        }
        p.doll = null;
    }

    private void clearParts() {
        if (raising != null && raising.line != null) raising.line.remove();
        raising = null;
        if (warn != null) warn.remove();
        warn = null;
        if (shadow != null) shadow.remove();
        shadow = null;
        for (Puppet p : puppets) {
            if (p == null) continue;
            if (p.line != null) p.line.remove();
            p.line = null;
        }
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        clearParts();
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " puppets=" + active() + (raising != null ? "+1" : "") + " cut=" + cutSince
                + " corpses=" + game.mobs.corpses().size();
    }
}
