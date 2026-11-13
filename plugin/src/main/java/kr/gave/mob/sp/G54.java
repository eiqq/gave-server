package kr.gave.mob.sp;

import java.util.Map;
import java.util.WeakHashMap;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;

/**
 * G54 올라타는 게이브(지원, 기생).
 *
 * <p>도감: 반 크기 무표정 얼굴. 혼자일 땐 똑딱똑딱 바닥을 빠르게 기다가 가까운 게이브 정수리로 뛰어올라 움켜쥔다. 올라탄 숙주는 눈이 흰자로
 * 뒤집히고 체력과 빠르기가 크게 오른다. 올라탄 작은 얼굴을 먼저 쏘면 숙주가 3초 기절하고, 숙주를 먼저 죽이면 옆 숙주로 옮겨 탄다.
 * 혼자 길 땐 한 방.
 *
 * <p>엔진에 맞춘 것: 올라탄 동안 보이는 얼굴은 숙주 몸의 부품({@code host.body.attach("gabe/g54")})으로 실어 숙주와 한 몸처럼 움직이게 하고
 * (도감 '숙주 디스플레이의 passenger'), 이 개체 자신의 몸은 빈 모델로 숨긴 채 숙주 정수리 자리를 따라다니며 맞는 구만 맡는다.
 * 숙주 강화는 종을 가리지 않는 것만 한다: 체력 +50%, 빠르기 x1.4, 흰자 팔레트. 종마다 기술이 한 단계 강해지는 것(방패 두 겹, 사슬 둘 등)은
 * 그 종 파일의 일이라 하지 않았다(요청). 숙주는 높이 띠를 가리지 않는다(도감 예시가 여러 띠). 미리 깔린 종, 죽음 결과 종, 흉내 중인 종,
 * 같은 종은 타지 않는다.
 *
 * <p>팩 계약(tools/gabe/sp/g54.py): 모델 gave:gabe/g54, 상태 "" 기어감, "hop" 뛰어오름, "ride" 움켜쥠. 소리 tick(24), clamp(24), hop(16).
 * 글귀 notice_g54_ride.
 */
public final class G54 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 기어가는 빠르기(빠르게), 바닥에서 몸 중심 높이. */
    static final double SPEED = 0.3, HOVER = 0.25;
    /** 숙주를 찾는 반경, 뛰어오르는 거리(수평), 뛰는 틱(높이 차마다 더), 다시 찾기까지. */
    static final double SEEK_R = 80, HOP_RANGE = 3.2;
    static final int HOP_T = 9, HOP_T_MAX = 18, RESEEK = 10;
    /** 숙주 강화: 체력 덤 비율, 빠르기 배율. 올라탄 것이 먼저 죽으면 숙주 기절 틱. */
    static final double HOST_HP = 0.5, HOST_SPEED = 1.4;
    static final int STUN = 60;
    /** 사람과 두는 거리(숙주가 없을 때 맴도는 곳). */
    static final double LOITER = 12;
    static final int TICK_EVERY = 6;
    /** 올라탄 동안 맞는 구 반지름(숙주 반지름보다 작으면 숙주 반지름). */
    static final double RIDE_R = 0.42;

    enum Phase { CRAWL, HOP, RIDE }

    /** 지금 올라탄 것들(숙주 → 올라탄 개체). 한 숙주에 하나만. */
    private static final Map<Gabe, G54> RIDERS = new WeakHashMap<>();
    private static long noticeAt = Long.MIN_VALUE;

    private Phase phase = Phase.CRAWL;
    private int phaseAt, nextSeek, nextTick, hopTicks;
    private Gabe host, want;
    private Body.Part seat;
    private double hostBonus;
    private float seatYaw = Float.NaN;
    private final Vector seatOff = new Vector();
    private final Vector hopFrom = new Vector();

    public G54(Game game, Species sp) {
        super(game, sp);
        radius = 0.22;
        hover = HOVER;
        baseSpeed = SPEED;
        noBite = true; // 숙주와 머리가 맞닿아도 물어뜯지 않게
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g54", 1.6f, 0.9f);
    }

    @Override
    protected void spawned() {
        nextTick = age + game.random.nextInt(TICK_EVERY);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case CRAWL -> crawl();
            case HOP -> hop();
            case RIDE -> ride();
        }
    }

    /** 바닥을 빠르게 김: 가장 가까운 숙주 쪽으로. 없으면 사람 둘레 12블록에서 맴돈다. */
    private void crawl() {
        state("");
        if (want != null && !rideable(want)) want = null;
        if (age >= nextSeek) {
            nextSeek = age + RESEEK;
            Gabe g = game.mobs.nearest(pos, SEEK_R, this::rideable);
            if (g != null) want = g;
        }
        if (age >= nextTick) {
            nextTick = age + TICK_EVERY;
            sound("tick", Sound.BLOCK_WOOD_STEP, 1.8f, 24);
        }
        if (want == null) {
            loiter();
            return;
        }
        Vector to = want.pos.clone().subtract(pos).setY(0);
        double d = to.length();
        if (d <= HOP_RANGE + want.radius) {
            startHop();
            return;
        }
        vel.copy(to.multiply(speed() / d));
        face(pos.clone().add(vel));
        pitch = 0;
    }

    private void loiter() {
        Player p = nearest(200);
        if (p == null) {
            hold();
            return;
        }
        double d = Math.hypot(p.getLocation().getX() - pos.getX(), p.getLocation().getZ() - pos.getZ());
        if (d > LOITER + 3) follow(speed() * 0.7);
        else if (d < LOITER - 3) vel.copy(toward(p).multiply(-speed() * 0.7));
        else {
            Vector t = toward(p);
            vel.setX(-t.getZ() * speed() * 0.5).setY(0).setZ(t.getX() * speed() * 0.5);
        }
        if (vel.lengthSquared() > 1e-6) face(pos.clone().add(vel));
        pitch = 0;
    }

    private void startHop() {
        hopFrom.copy(pos);
        double dy = Math.max(0, top(want).getY() - pos.getY());
        hopTicks = (int) Math.min(HOP_T_MAX, HOP_T + dy * 0.9);
        flying = true;
        noPush = true;
        state("hop");
        sound("hop", Sound.ENTITY_SLIME_JUMP_SMALL, 1.6f, 16);
        phase(Phase.HOP);
    }

    /** 포물선으로 숙주 정수리까지(숙주가 움직여도 따라감). */
    private void hop() {
        if (want == null || !rideable(want)) {
            fall();
            return;
        }
        int t = age - phaseAt;
        double s = Math.min(1.0, t / (double) hopTicks);
        Vector end = top(want);
        Vector at = hopFrom.clone().add(end.clone().subtract(hopFrom).multiply(s));
        at.setY(at.getY() + 1.1 * 4 * s * (1 - s));
        vel.copy(at.subtract(pos));
        face(end);
        if (s >= 1.0) mount(want);
    }

    /** 뛰다가 숙주를 잃음: 바닥으로. */
    private void fall() {
        want = null;
        flying = false;
        noPush = false;
        vel.zero();
        nextSeek = age;
        phase(Phase.CRAWL);
    }

    private void mount(Gabe g) {
        Body.Part p = g.body == null ? null : g.body.attach("gabe/g54");
        if (p == null) {
            fall();
            nextSeek = age + 40;
            return;
        }
        host = g;
        want = null;
        seat = p;
        seat.follow = false;
        seat.state("ride");
        seat.look.phase = body.look.phase;
        seatYaw = Float.NaN;
        RIDERS.put(g, this);
        // 숙주 강화: 체력, 흰자(빠르기는 매 틱)
        hostBonus = g.maxHp * HOST_HP;
        g.maxHp += hostBonus;
        g.hp += hostBonus;
        g.body.look.palette |= Look.PAL_WHITE_EYES;
        body.model(Body.EMPTY);
        state("ride");
        sound("clamp", Sound.ENTITY_SPIDER_STEP, 0.6f, 24);
        long now = game.now();
        if (now - noticeAt > 400 || now < noticeAt) {
            noticeAt = now;
            game.hud.notice("notice_g54_ride", Species.G54, 60);
        }
        phase(Phase.RIDE);
        poseSeat(0);
    }

    /** 숙주 정수리 위에서: 숙주를 빠르게 하고, 가장 가까운 사람을 무표정으로 본다. */
    private void ride() {
        Gabe g = host;
        if (g == null || g.dead || g.removed || g.body == null || seat == null || seat.d == null || !seat.d.isValid()) {
            // 숙주가 먼저 죽음: 옆 숙주로 옮겨 탄다
            dismount(false);
            return;
        }
        Vector at = top(g).add(g.vel);
        vel.copy(at.subtract(pos));
        g.speedBuff = Math.max(g.speedBuff, HOST_SPEED);
        if ((g.body.look.palette & Look.PAL_WHITE_EYES) == 0) g.body.look.palette |= Look.PAL_WHITE_EYES;
        Player p = nearest(40);
        if (p != null) {
            if (target != p) mark(p);
            Vector c = chest(p);
            yaw = (float) Math.toDegrees(Math.atan2(-(c.getX() - pos.getX()), c.getZ() - pos.getZ()));
        } else if (target != null) mark(null);
        pitch = 0;
        // 숙주 몸에 실은 부품이 이 개체의 겉모습을 따라감(눈 뜸, 변함, 맞은 번쩍임)
        seat.look.open = body.look.open;
        seat.look.mut = body.look.mut;
        seat.look.hurtUntil = body.look.hurtUntil;
        Vector off = top(g).subtract(g.pos);
        if (Float.isNaN(seatYaw) || Math.abs(wrap(yaw - seatYaw)) > 10f || off.distanceSquared(seatOff) > 0.12 * 0.12) poseSeat(3);
    }

    /** 숙주 몸 중심 기준 좌석 자리(숙주 머리 위)와 보는 방향을 부품에 보냄(세계 축, 숙주가 구르거나 찌그러져도 그대로). */
    private void poseSeat(int ticks) {
        if (seat == null || host == null) return;
        seatYaw = yaw;
        seatOff.copy(top(host).subtract(host.pos));
        seat.pose(new Matrix4f().translate((float) seatOff.getX(), (float) seatOff.getY(), (float) seatOff.getZ())
                .rotateY((float) Math.toRadians(-yaw)), ticks);
    }

    /** 숙주에서 내림. killed 면 이 개체가 먼저 죽은 것(숙주 3초 기절). */
    private void dismount(boolean killed) {
        Gabe g = host;
        host = null;
        if (seat != null) {
            try {
                seat.remove();
            } catch (RuntimeException ignored) {
                // 숙주 몸이 이미 치워짐
            }
            seat = null;
        }
        if (g != null) {
            if (RIDERS.get(g) == this) RIDERS.remove(g);
            if (!g.dead && !g.removed) {
                g.maxHp = Math.max(1, g.maxHp - hostBonus);
                g.hp = Math.min(g.hp, g.maxHp);
                if (g.body != null) g.body.look.palette &= ~Look.PAL_WHITE_EYES;
                if (killed) game.mobs.lure(g, g.pos.clone(), STUN);
            }
        }
        hostBonus = 0;
        if (target != null) mark(null);
        if (!killed) {
            body.model("gabe/g54");
            flying = false;
            noPush = false;
            vel.zero();
            nextSeek = age;
            state("");
            phase(Phase.CRAWL);
        }
    }

    // ------------------------------------------------------------------ 숙주 고르기

    private boolean rideable(Gabe g) {
        if (g == this || g.dead || g.removed || g.body == null || g.body.anchor == null) return false;
        if (g instanceof G54 || g.disguised || g.untargetable || g.noRules) return false;
        if (g.sp.spawn == Species.Spawn.PLACED || g.sp.spawn == Species.Spawn.DERIVED) return false;
        if (game.director.inCage(g)) return false;
        G54 r = RIDERS.get(g);
        return r == null || r == this || r.dead || r.host != g;
    }

    /** 숙주 정수리 위(이 개체 몸 중심 자리). */
    private static Vector top(Gabe g) {
        Vector h = g.head();
        return new Vector(h.getX(), h.getY() + g.radius * 0.85 + 0.14, h.getZ());
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    /**
     * 올라탄 동안은 맞는 구를 숙주 정수리 둘레만큼 키운다: 숙주 구와 거의 같은 자리라 그대로면 탄이 숙주 구에 먼저 닿아 정수리 얼굴을 못 맞힌다.
     * 이 크기면 숙주 가운데보다 위로 겨눈 탄은 이 개체가, 가운데 아래는 숙주가 받는다.
     */
    @Override
    public java.util.List<double[]> spheres() {
        if (phase != Phase.RIDE || host == null) return super.spheres();
        return java.util.List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), Math.max(RIDE_R, host.radius)});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        // 혼자 길 땐 한 방
        if (phase != Phase.RIDE) return super.damage(new Damage(Math.max(d.amount(), hp + 1), d.type(), d.by(), d.at(), d.dir(), d.how(), d.aimTicks()));
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (host != null) {
            // 올라탄 것이 먼저 죽음: 숙주는 3초 기절(웨이브 끝 정리는 빼고)
            boolean cleanup = noReward && (d == null || d.by() == null);
            dismount(!cleanup);
            body.model("gabe/g54");
        }
        super.die(d);
    }

    @Override
    public void remove() {
        if (host != null || seat != null) dismount(true);
        super.remove();
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (host != null ? " host=" + host.sp.name() + "#" + host.id : "") + (want != null ? " want=" + want.sp.name() : "");
    }
}
