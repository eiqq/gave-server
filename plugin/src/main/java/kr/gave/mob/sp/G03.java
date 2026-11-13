package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;

/**
 * G03 막대기 곡예 게이브(원거리). 머리 높이에 떠서 고개로 엔드 막대기 둘을 번갈아 쳐 올리는 곡예를 하며 다가온다(박자마다 '통', 얼굴이 납작 눌렸다 튐).
 * 12블록 안에서 사람이 보이면 그 사람을 찍고(실눈 뜸) 박자가 두 배가 된 뒤, 고개를 젖혀 막대기를 뒤통수에서 하나씩 뽑아(찡그린 얼굴 0.2초)
 * 그 사람이 있던 자리로 던진다. 막대기는 돌며 포물선으로 날아가 4 피해, 땅에 꽂혀 2초 꿈틀대다 주인 뒤통수로 돌아오는데 돌아오는 길에도 맞는다.
 * 둘 다 던진 1.5초는 무방비(가라앉아 멈춤, 뒤통수에 빈 구멍 둘). 공중 막대기를 맞히면 떨어지고 주인이 내려가 주워 꽂는다.
 *
 * <p>대처(구조물 없는 황야): 막대기는 던지는 순간 그 사람이 서 있던 자리로 가므로 박자가 두 배가 되면 옆으로 비킨다. 꽂힌 막대기와 주인 사이
 * (돌아가는 길목)에 서지 않는다. 벽에 맞아 떨어지는 길(FALL)은 시험 벽이나 보급 상자에만 남아 있다.
 *
 * <p>도감과 다른 점: 던진 막대기는 바닐라 눈덩이 대신 몸의 부품 디스플레이(세계 축, 2틱마다 자세 보간)로 그리고 서버가 포물선과 맞음을 계산한다
 * (바탕 엔티티를 쓰지 않는 계약). 부품은 컬링을 끈다(땅에 꽂힌 막대기만 보고 주인이 화면 밖이어도 보이게).
 *
 * <p>팩 계약(tools/gabe/sp/g03.py): 모델 {@code gave:gabe/g03} 상태 {@code ""}, {@code "ouch"}, 부위 {@code gone_a}(게이브 왼쪽 막대기 뽑힘),
 * {@code gone_b}. 부품 모델 {@code gave:gabe/g03_stick} 상태 {@code ""}(돎), {@code "stuck"}. 소리 tong, puk, whirl. 글귀 cause_g03.
 */
public final class G03 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.13;
    /** 떠 있는 높이(몸 중심, 얼굴 아래가 땅에서 1 블록 남짓). 무방비 때 가라앉는 높이, 주우러 내려간 높이. */
    static final double HOVER = 1.5, SAG = 1.05, LOW = 0.75;
    /** 이 블록 안에서 보이면 박자 두 배, 이보다 가까우면 물러남. */
    static final double RANGE = 12, KEEP_MIN = 6.5;
    /** 박자 두 배 틱, 젖힘 틱, 두 막대기 사이 틱, 찡그림 틱, 무방비 틱, 다시 던지기까지 쉬는 틱. */
    static final int FAST = 30, TILT = 6, GAP = 10, OUCH = 4, DEFENSELESS = 30, COOLDOWN = 50;
    static final double DAMAGE = 5;
    /** 던짐 수평 빠르기(블록/틱), 돌아옴 빠르기, 중력(블록/틱²), 꽂혀 꿈틀대는 틱, 최대 비행 틱. */
    static final double THROW_V = 0.6, RETURN_V = 0.5, GRAV = 0.035;
    static final int STUCK = 40, FLY_MAX = 70, DOWN_MAX = 240, RETRIEVE_MAX = 100;
    /** 맞는 구: 얼굴, 공중 막대기. 막대기가 사람에 닿는 거리. */
    static final double FACE_R = 0.42, STICK_R = 0.32, STICK_HIT = 0.7;
    /** 뒤통수 뿌리(몸 기준 블록: 왼쪽 +x, 위, 앞 +z), g03.py h.point(+-0.45, 0.72, -0.9) 와 같음. */
    static final double ROOT_X = 0.18, ROOT_Y = 0.288, ROOT_Z = -0.27;
    /** 곡예 주기(틱, 팩 period 20). */
    static final int PERIOD = 20;

    enum Phase { APPROACH, WINDUP, THROW, DEFENSELESS, WAIT, RETRIEVE }

    enum Mode { HOME, FLY, STUCK, RETURN, FALL, DOWN }

    /** 막대기 하나(a = 게이브 왼쪽, b = 오른쪽). */
    final class Stick {
        final int side;
        final String part;
        Mode mode = Mode.HOME;
        Body.Part p;
        final Vector at = new Vector(), v = new Vector(), sent = new Vector();
        int t;
        float spin;
        boolean sentOnce;
        final Set<UUID> hit = new HashSet<>();

        Stick(int side, String part) {
            this.side = side;
            this.part = part;
        }

        boolean airborne() {
            return mode == Mode.FLY || mode == Mode.RETURN;
        }
    }

    private final Stick a = new Stick(1, "gone_a"), b = new Stick(-1, "gone_b");
    private Phase phase = Phase.APPROACH;
    private int phaseAt, readyAt, lastHalf = -1, squashAt;
    private boolean squashed;
    private Player prey;
    private Stick fetching;
    private final Vector fetchFrom = new Vector();

    public G03(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        // 얼굴 0.8 + 머리 위 곡예 2 블록: 폭 1.6, 높이 4(가운데 기준, 위로 넉넉히)
        body = Body.of(this, "gabe/g03", 1.6f, 4.2f);
    }

    @Override
    protected void spawned() {
        readyAt = age + 20 + game.random.nextInt(30);
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
        if (prey != null && !standing(prey)) prey = null;
        switch (phase) {
            case APPROACH -> approach();
            case WINDUP -> windup();
            case THROW -> throwing();
            case DEFENSELESS -> defenseless();
            case WAIT -> waitSticks();
            case RETRIEVE -> retrieve();
        }
        stickTick(a);
        stickTick(b);
        beat();
    }

    /** 흐름장으로 다가오다 12블록 안에서는 거리를 지키며 옆걸음. */
    private void approach() {
        Player p = nearest(48);
        keepDistance(p);
        if (p != null && age >= readyAt && a.mode == Mode.HOME && b.mode == Mode.HOME && dist(p) <= RANGE && sees(p)) {
            prey = p;
            mark(p);
            body.look.rate = 2.0; // 박자 두 배(던지기 직전)
            phase(Phase.WINDUP);
        }
    }

    private void keepDistance(Player p) {
        if (p == null) {
            follow(speed());
            return;
        }
        double d = dist(p);
        if (d > RANGE || !sees(p)) follow(speed());
        else if (d < KEEP_MIN) vel.copy(toward(p).multiply(-speed() * 0.8));
        else {
            Vector t = toward(p);
            double s = Math.sin((age + id * 17) * 0.05) * speed() * 0.6;
            vel.copy(new Vector(-t.getZ(), 0, t.getX()).multiply(s));
            if (wallAhead(0.6)) vel.multiply(-1);
        }
        face(chest(p));
    }

    private void windup() {
        if (prey == null || !sees(prey)) {
            calm();
            return;
        }
        hold();
        face(chest(prey));
        if (inPhase() >= cd(FAST)) {
            phase(Phase.THROW);
        }
    }

    /** 고개를 젖혀 막대기를 하나씩 뽑아 던짐. */
    private void throwing() {
        hold();
        int t = inPhase();
        if (prey != null) face(chest(prey));
        pitch = -35f; // 고개를 뒤로 젖힘
        body.pose(2);
        if (t == TILT) pull(a);
        if (t == TILT + OUCH || t == TILT + GAP + OUCH) state("");
        if (t == TILT + GAP) pull(b);
        if (t >= TILT + GAP + OUCH) {
            // 물어뜯기로 틱을 건너뛰어도 찡그린 얼굴이 남지 않게 여기서 한 번 더 되돌림
            state("");
            pitch = 0;
            body.look.rate = 1.0;
            hover = SAG;
            phase(Phase.DEFENSELESS);
        }
    }

    private void pull(Stick s) {
        if (s.mode != Mode.HOME) return;
        Vector target = prey != null && standing(prey) ? chest(prey) : pos.clone().add(forward().multiply(8));
        Body.Part part = body.attach("gabe/g03_stick");
        if (part == null) return;
        part.follow = false;
        if (part.d != null) {
            // 컬링 끔(땅에 꽂힌 막대기만 화면에 있고 주인이 밖이어도 보이게)
            part.d.setDisplayWidth(0f);
            part.d.setDisplayHeight(0f);
        }
        s.p = part;
        s.at.copy(root(s.side));
        Vector d = target.clone().subtract(s.at);
        double flat = Math.hypot(d.getX(), d.getZ());
        double T = Math.max(8, flat / THROW_V);
        s.v.setX(d.getX() / T).setZ(d.getZ() / T).setY((d.getY() + 0.5 * GRAV * T * T) / T);
        s.spin = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        s.mode = Mode.FLY;
        s.t = 0;
        s.hit.clear();
        s.sentOnce = false;
        body.look.on.add(s.part);
        state("ouch");
        sound("puk", Sound.ENTITY_SLIME_ATTACK, 1.6f, 16);
        soundAt(s.at, "whirl", Sound.ENTITY_PHANTOM_FLAP, 1.8f, 20);
        poseStick(s, 1);
    }

    /** 둘 다 던진 1.5초: 가라앉아 멈춤(집중 사격 때). */
    private void defenseless() {
        hold();
        if (prey != null) face(chest(prey));
        if (inPhase() >= DEFENSELESS) {
            hover = HOVER;
            mark(null);
            phase(Phase.WAIT);
        }
    }

    /** 막대기가 돌아올 때까지 거리만 지킴. 떨어진 막대기가 있으면 주우러. */
    private void waitSticks() {
        Stick down = a.mode == Mode.DOWN ? a : b.mode == Mode.DOWN ? b : null;
        if (down != null) {
            fetching = down;
            hover = LOW;
            phase(Phase.RETRIEVE);
            return;
        }
        keepDistance(nearest(48));
        if (a.mode == Mode.HOME && b.mode == Mode.HOME) {
            readyAt = age + cd(COOLDOWN);
            phase(Phase.APPROACH);
        }
    }

    /** 떨어진 막대기 쪽으로 내려가 주워 꽂음. */
    private void retrieve() {
        Stick s = fetching;
        if (s == null || s.mode != Mode.DOWN) {
            hover = HOVER;
            fetching = null;
            phase(Phase.WAIT);
            return;
        }
        Vector d = s.at.clone().subtract(pos).setY(0);
        double l = d.length();
        if (l < 0.9) {
            home(s);
            return;
        }
        // 못 닿는 자리(벽 너머, 막힘)면 막대기가 스스로 날아 돌아옴
        if (inPhase() >= RETRIEVE_MAX || (inPhase() % 20 == 19 && pos.distanceSquared(fetchFrom) < 0.25)) {
            s.mode = Mode.RETURN;
            s.t = 0;
            s.hit.clear();
            state(s, "");
            fetching = null;
            hover = HOVER;
            phase(Phase.WAIT);
            return;
        }
        if (inPhase() % 20 == 0) fetchFrom.copy(pos);
        vel.copy(d.multiply(Math.min(speed() * 1.3, l) / l));
        face(s.at);
    }

    private void calm() {
        mark(null);
        prey = null;
        body.look.rate = 1.0;
        pitch = 0;
        state("");
        hover = HOVER;
        readyAt = age + cd(COOLDOWN) / 2;
        phase(a.mode == Mode.HOME && b.mode == Mode.HOME ? Phase.APPROACH : Phase.WAIT);
    }

    // ------------------------------------------------------------------ 곡예 박자('통', 받을 때 납작)

    /** 셰이더와 같은 자세 시계로 반 바퀴(막대기 하나가 오르고 내려옴)마다 '통' 과 얼굴 눌림. */
    private void beat() {
        if (body == null) return;
        if (squashed && age >= squashAt + 2) {
            body.squash(1f, 1f, 1f, 3);
            squashed = false;
        }
        int p = Look.PERIODS[Look.periodIndex(PERIOD, body.look.rate)];
        long wt = game.worldTime();
        int ph = Math.floorMod(body.look.phase, p) * 16 / p;
        double m = Math.floorMod(wt * 16 + (long) ph * p, (long) p * 16) / 16.0;
        int half = m < p / 2.0 ? 0 : 1;
        if (half == lastHalf) return;
        lastHalf = half;
        // 반 바퀴 0 은 막대기 a 가 오름, 1 은 b(막대기가 나가 있으면 그 쪽은 소리 없음)
        Stick s = half == 0 ? a : b;
        if (s.mode != Mode.HOME || phase == Phase.THROW || phase == Phase.DEFENSELESS) return;
        sound("tong", Sound.BLOCK_BAMBOO_WOOD_STEP, 1.4f, 20);
        body.squash(1.12f, 0.84f, 1.08f, 1);
        squashAt = age;
        squashed = true;
    }

    // ------------------------------------------------------------------ 막대기

    private void stickTick(Stick s) {
        if (s.mode == Mode.HOME) return;
        s.t++;
        switch (s.mode) {
            case FLY -> fly(s);
            case STUCK -> {
                if (s.t >= STUCK) {
                    s.mode = Mode.RETURN;
                    s.t = 0;
                    s.hit.clear();
                    state(s, "");
                    soundAt(s.at, "whirl", Sound.ENTITY_PHANTOM_FLAP, 1.8f, 20);
                }
            }
            case RETURN -> back(s);
            case FALL -> fall(s);
            case DOWN -> {
                if (s.t >= DOWN_MAX) {
                    s.mode = Mode.RETURN;
                    s.t = 0;
                    s.hit.clear();
                    state(s, "");
                }
            }
            default -> {
            }
        }
        if (s.mode != Mode.HOME && (s.t % 2 == 0 || !s.sentOnce)) poseStick(s, 2);
    }

    private void fly(Stick s) {
        Vector prev = s.at.clone();
        s.at.add(s.v);
        s.v.setY(s.v.getY() - GRAV);
        strike(s);
        double fl = game.mobs.flow.floor(s.at.getX(), s.at.getZ());
        if (game.mobs.flow.walkable(s.at.getX(), s.at.getZ()) && s.at.getY() <= fl + 0.05) {
            plant(s, fl, Mode.STUCK); // 땅에 꽂힘
            return;
        }
        if (blocked(s.at)) {
            double fp = game.mobs.flow.floor(prev.getX(), prev.getZ());
            s.at.copy(prev);
            if (prev.getY() - fp < 0.6) {
                plant(s, fp, Mode.STUCK); // 턱이나 바닥 모서리에 꽂힘
                return;
            }
            // 벽에 맞음: 떨어져 눕고 주인이 주우러 옴
            s.v.setX(0).setZ(0).setY(Math.min(0, s.v.getY()));
            s.mode = Mode.FALL;
            return;
        }
        if (s.t >= FLY_MAX) plant(s, fl, Mode.STUCK);
    }

    private void fall(Stick s) {
        s.v.setY(s.v.getY() - GRAV * 1.5);
        s.v.setX(s.v.getX() * 0.85).setZ(s.v.getZ() * 0.85);
        Vector prev = s.at.clone();
        s.at.add(s.v);
        if (blocked(s.at)) s.at.copy(prev).setY(prev.getY() + s.v.getY());
        double fl = game.mobs.flow.floor(s.at.getX(), s.at.getZ());
        if (s.at.getY() <= fl + 0.05) plant(s, fl, Mode.DOWN);
    }

    private void plant(Stick s, double fl, Mode m) {
        s.at.setY(fl);
        s.v.zero();
        s.mode = m;
        s.t = 0;
        state(s, "stuck");
        soundAt(s.at, "puk", Sound.ENTITY_SLIME_ATTACK, 1.2f, 16);
    }

    /** 주인 뒤통수로 곧장(살짝 솟아) 돌아옴. 길목의 사람도 맞음. */
    private void back(Stick s) {
        Vector to = root(s.side);
        Vector d = to.clone().subtract(s.at);
        double l = d.length();
        if (l < 0.6) {
            home(s);
            return;
        }
        double step = Math.min(RETURN_V * Math.max(1, speed() / baseSpeed), l);
        s.at.add(d.multiply(step / l));
        if (l > 3) s.at.setY(s.at.getY() + 0.04);
        s.spin = (float) Math.toDegrees(Math.atan2(-(to.getX() - s.at.getX()), to.getZ() - s.at.getZ()));
        strike(s);
        if (s.t > 200) home(s);
    }

    private void strike(Stick s) {
        for (Player p : game.mobs.standing()) {
            if (s.hit.contains(p.getUniqueId())) continue;
            Vector c = chest(p);
            double dy = s.at.getY() - c.getY();
            if (Math.abs(dy) > 1.05) continue;
            double dx = s.at.getX() - c.getX(), dz = s.at.getZ() - c.getZ();
            if (dx * dx + dz * dz > STICK_HIT * STICK_HIT) continue;
            s.hit.add(p.getUniqueId());
            hit(p, DAMAGE, "cause_g03");
            Vector dir = s.v.lengthSquared() > 1e-6 ? s.v.clone() : c.clone().subtract(s.at);
            knock(p, dir, 0.25, 0.1);
            if (s.mode == Mode.FLY) {
                // 맞힌 막대기는 힘을 잃고 그 사람 발치에 꽂힘
                s.v.setX(s.v.getX() * 0.15).setZ(s.v.getZ() * 0.15).setY(Math.min(s.v.getY(), 0));
            }
        }
    }

    private void home(Stick s) {
        if (s.p != null) s.p.remove();
        s.p = null;
        s.mode = Mode.HOME;
        s.t = 0;
        body.look.on.remove(s.part);
        sound("puk", Sound.ENTITY_SLIME_ATTACK, 1.4f, 16);
        body.squash(0.9f, 1.15f, 0.9f, 1); // 다시 꽂히며 움찔
        squashAt = age;
        squashed = true;
        if (fetching == s) {
            fetching = null;
            hover = HOVER;
            if (phase == Phase.RETRIEVE) phase(Phase.WAIT);
        }
    }

    private void state(Stick s, String st) {
        if (s.p != null) s.p.state(st);
    }

    private void poseStick(Stick s, int ticks) {
        if (s.p == null) return;
        Vector off = s.at.clone().subtract(pos);
        if (s.sentOnce && off.distanceSquared(s.sent) < 0.0004 && s.mode != Mode.FLY && s.mode != Mode.RETURN) return;
        s.sent.copy(off);
        s.sentOnce = true;
        Matrix4f m = new Matrix4f().translate((float) off.getX(), (float) off.getY(), (float) off.getZ())
                .rotateY((float) Math.toRadians(-s.spin));
        if (s.mode == Mode.DOWN) m.rotateX((float) Math.toRadians(80)); // 떨어진 막대기는 누움
        s.p.pose(m, ticks);
    }

    private boolean blocked(Vector at) {
        Block bl = game.world.getBlockAt((int) Math.floor(at.getX()), (int) Math.floor(at.getY()), (int) Math.floor(at.getZ()));
        return !bl.isPassable();
    }

    /** 막대기 뿌리 세계 자리(뒤통수). */
    private Vector root(int side) {
        double r = Math.toRadians(yaw);
        Vector f = new Vector(-Math.sin(r), 0, Math.cos(r)), l = new Vector(Math.cos(r), 0, Math.sin(r));
        return pos.clone().add(l.multiply(ROOT_X * side)).add(f.multiply(ROOT_Z)).add(new Vector(0, ROOT_Y, 0));
    }

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    private void soundAt(Vector at, String name, Sound fb, float fbPitch, double range) {
        if (game.director.silent()) return;
        Props.soundAround(new Location(game.world, at.getX(), at.getY(), at.getZ()), "gabe." + sp.key() + "." + name, fb, 1f,
                0.94f + game.random.nextFloat() * 0.12f, fbPitch, range, -1);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), FACE_R});
        for (Stick s : new Stick[] {a, b}) if (s.airborne()) out.add(new double[] {s.at.getX(), s.at.getY(), s.at.getZ(), STICK_R});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            double face = d.at().distanceSquared(pos);
            for (Stick s : new Stick[] {a, b}) {
                if (!s.airborne()) continue;
                double ds = d.at().distanceSquared(s.at);
                if (ds < face && ds < (STICK_R + 0.4) * (STICK_R + 0.4)) {
                    // 공중 막대기를 맞힘: 떨어지고 주인이 주우러 내려옴
                    s.mode = Mode.FALL;
                    s.t = 0;
                    s.v.setX(s.v.getX() * 0.3).setZ(s.v.getZ() * 0.3).setY(0.08);
                    soundAt(s.at, "puk", Sound.ENTITY_SLIME_ATTACK, 2f, 16);
                    return true;
                }
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        // 공중 막대기는 치우고 땅에 꽂힌 것은 시체와 함께 남김
        for (Stick s : new Stick[] {a, b}) {
            if (s.p != null && (s.airborne() || s.mode == Mode.FALL)) {
                s.p.remove();
                s.p = null;
            }
        }
        super.die(d);
    }

    /** 시험용: 지금 단계와 막대기. */
    public String phaseName() {
        return phase.name() + " a=" + a.mode + " b=" + b.mode;
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " a=" + a.mode + " b=" + b.mode;
    }
}
