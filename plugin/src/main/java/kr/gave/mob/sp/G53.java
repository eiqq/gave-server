package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;
import kr.gave.mob.Swarm;

/**
 * G53 점으로 짠 얼굴 떼(하늘 떼).
 *
 * <p>도감: 손바닥만 한 얼굴 24개가 하늘에서 큰 _Gave 얼굴의 픽셀(실눈 획, 점 입, 홍조, 머리 덩어리, 귀 줄)을 짠다. 큰 얼굴은 천천히 무리 쪽으로
 * 기운다. 눈 픽셀의 작은 얼굴들이 자리를 바꿔 '눈 뜬' 고리를 만들면(찍었다) 1초 뒤 모두 흩어져 비처럼 내리꽂는다(하나 1 피해).
 * 살아남은 것들은 다시 올라가 구멍 난 틀린 얼굴(외눈, 입 없음)로 또 쏟아진다. 쏟아지기 전에 눈 픽셀을 쏘면 눈을 못 떠서 늦어진다.
 * 큰 얼굴 자체엔 체력이 없고 한 발에 한 마리, 무리 보상은 마지막 한 마리에.
 *
 * <p>엔진에 맞춘 것: 무리 닻(Swarm) 하나에 마리 24. 떠 있는 동안 15블록 밖 사람에게는 픽셀 얼굴을 구워 넣은 덩어리 디스플레이 하나만
 * 보이고(팩 상태 "lod": 죽은 마리의 픽셀은 부위 dNN 으로 숨김, 눈 뜬 고리는 open 조건), 판정은 서버가 아는 마리 자리(대형 자리 = 구운 픽셀
 * 자리)로 한다. 눈을 뜨는 순간부터 다시 모일 때까지는 모두에게 낱개 마리를 보인다(lodDist 를 크게). '더 작은 얼굴로 다시 짠다'는
 * 마리가 제 픽셀 자리로 돌아가 구멍 난 얼굴이 되는 것으로 했다(덩어리가 구운 그림이라 자리를 새로 짤 수 없음).
 *
 * <p>체력 약속(G02 와 같음): 감독이 넣은 maxHp 를 첫 틱에 마리 하나 체력으로 읽고 그 뒤 maxHp, hp 는 무리 합계.
 *
 * <p>팩 계약(tools/gabe/sp/g53.py): 모델 gave:gabe/g53, 마리 상태 "a", "b", 덩어리 "lod", 부위 d00..d23. 픽셀 자리표 PIXELS 는 팩과 같음.
 * 소리 titter(48), rain(48), flutter(24). 원인 글귀 cause_g53.
 */
public final class G53 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int N = 24, EYES = 10;
    /** 얼굴 평면 픽셀 자리(가로 u, 세로 v 블록): 0~4 왼눈, 5~9 오른눈, 10~11 입, 12~15 홍조, 16~19 머리 덩어리, 20~23 귀 줄. g53.py PIXELS 와 같음. */
    static final double[][] PIXELS = {{-2.2, 0.05}, {-1.85, 0.4}, {-1.5, 0.55}, {-1.15, 0.4}, {-0.8, 0.05},
        {0.8, 0.05}, {1.15, 0.4}, {1.5, 0.55}, {1.85, 0.4}, {2.2, 0.05},
        {-0.2, -1.7}, {0.2, -1.7},
        {-0.75, -1.0}, {-0.25, -1.0}, {0.25, -1.0}, {0.75, -1.0},
        {-2.4, 1.75}, {-1.9, 1.95}, {1.9, 1.95}, {2.4, 1.75},
        {-2.95, -0.2}, {-2.95, -0.7}, {2.95, -0.2}, {2.95, -0.7}};
    static final double[][] EYE_C = {{-1.5, 0.25}, {1.5, 0.25}};
    static final double EYE_R = 0.5;
    /** 떠 있는 높이(바닥에서 큰 얼굴 가운데), 빠르기, 무리 앞 이만큼에서 멈춤. */
    static final double HOVER = 12, SPEED = 0.12, STAND_OFF = 9;
    /** 눈 뜨기(자리 바꿈 틱, 그 뒤 1초), 죽은 눈 픽셀 하나마다 늦어지는 틱과 그 상한. */
    static final int SWITCH_T = 6, RESHUFFLE = 10, STARE_HOLD = 20, EYE_DELAY = 25, EYE_DELAY_MAX = 150;
    /** 내리꽂는 틱(마리마다 조금씩 다르게), 바닥에 머무는 틱, 다시 올라가는 틱, 다음까지 쉬는 틱. */
    static final int DIVE_T = 12, DIVE_VAR = 7, LINGER = 10, RISE_T = 40, COOLDOWN = 70;
    /** 한 마리 피해, 닿음 거리, 내리꽂을 사람을 고르는 반경(닻 아래 바닥에서). */
    static final double MEMBER_DMG = 1.5, TOUCH = 0.7, RAIN_R = 18;
    /** 폭발, 불에 함께 죽는 반경. */
    static final double SPLASH = 3.0;
    /** 기울기(도): 멀면 조금, 머리 위에 가까울수록 많이. 이만큼 달라지면 대형을 다시 보냄. */
    static final float TILT_MIN = 12, TILT_MAX = 68, TILT_STEP = 8, TURN_STEP = 12;

    enum Phase { DRIFT, STARE, RAIN, RISE }

    private Swarm swarm;
    private Phase phase = Phase.DRIFT;
    private int phaseAt, readyAt, stareUntil, rainEnd, eyeDelay;
    private boolean init;
    private float heading, tilt = TILT_MIN, sentHeading = Float.NaN, sentTilt = Float.NaN;
    private int reformAt = -100;
    private Player prey;
    private final boolean[] struck = new boolean[N];

    public G53(Game game, Species sp) {
        super(game, sp);
        radius = 1.0;
        hover = HOVER;
        baseSpeed = SPEED;
        noPush = true;
        noBite = true;
        flying = true;
    }

    @Override
    protected void build() {
        swarm = game.mobs.swarm(this, "gabe/g53", N);
        swarm.memberRadius = 0.2;
        heading = yaw;
        for (Swarm.Member m : swarm.members) {
            m.home.copy(home(m.index, false));
            m.state(m.index % 2 == 0 ? "a" : "b");
        }
        reform(0);
    }

    private void init() {
        init = true;
        double each = Math.max(1, maxHp);
        for (Swarm.Member m : swarm.members) m.hp = each;
        maxHp = hp = each * swarm.members.size();
        readyAt = age + 60;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 대형(큰 얼굴)

    /** 픽셀 i 의 대형 자리(몸 기준: 옆, 위, 앞). open 이면 눈 픽셀은 눈 뜬 고리 자리. 얼굴 평면을 tilt 도 아래로 기울인 것. */
    private Vector home(int i, boolean open) {
        double u = PIXELS[i][0], v = PIXELS[i][1];
        if (open && i < EYES) {
            double a = Math.toRadians(90 + 72 * (i % 5));
            u = EYE_C[i / 5][0] + Math.cos(a) * EYE_R;
            v = EYE_C[i / 5][1] + Math.sin(a) * EYE_R;
        }
        double t = Math.toRadians(tilt);
        return new Vector(u, v * Math.cos(t), v * Math.sin(t));
    }

    /** 대형과 덩어리를 지금 방향, 기울기로 ticks 동안. */
    private void reform(int ticks) {
        boolean open = body != null && body.look.open && phase == Phase.STARE;
        for (Swarm.Member m : swarm.members) if (!m.dead) m.home.copy(home(m.index, open));
        swarm.formation(heading, ticks);
        for (Swarm.Member m : swarm.members) if (!m.dead) m.face(heading, tilt, 0, ticks);
        tiltLod(ticks);
        sentHeading = heading;
        sentTilt = tilt;
        reformAt = age;
    }

    /** 덩어리(구운 큰 얼굴)도 같은 기울기로(Swarm 은 방향만 주므로 변환을 다시 실음). */
    private void tiltLod(int ticks) {
        if (swarm.lod == null || !swarm.lod.isValid()) return;
        swarm.lod.setInterpolationDelay(0);
        swarm.lod.setInterpolationDuration(ticks);
        swarm.lod.setTransformationMatrix(new Matrix4f().rotateY((float) Math.toRadians(-heading)).rotateX((float) Math.toRadians(tilt))
                .scale(swarm.lodScale * Look.scale(swarm.model)).rotateY((float) Math.PI));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        if (prey != null && !standing(prey)) prey = null;
        switch (phase) {
            case DRIFT -> drift();
            case STARE -> stare();
            case RAIN -> rain();
            case RISE -> rise();
        }
    }

    /** 사람들 가운데(없으면 광장 가운데). */
    private Vector crowd() {
        List<Player> ps = game.mobs.standing();
        if (ps.isEmpty()) return new Vector(kr.gave.world.Plaza.CX + 0.5, floor(), kr.gave.world.Plaza.CZ + 0.5);
        double x = 0, y = 0, z = 0;
        for (Player p : ps) {
            Location l = p.getLocation();
            x += l.getX();
            y += l.getY();
            z += l.getZ();
        }
        int n = ps.size();
        return new Vector(x / n, y / n, z / n);
    }

    /** 하늘에서 천천히 다가와 무리 앞 위에 떠서 내려다봄(가까울수록 더 기욺). 쉬는 시간이 지나면 눈을 뜬다. */
    private void drift() {
        Vector c = crowd();
        Vector flat = new Vector(c.getX() - pos.getX(), 0, c.getZ() - pos.getZ());
        double d = flat.length();
        if (d > 1e-3) heading = (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
        yaw = heading;
        double want = game.mobs.flow.floor(pos.getX(), pos.getZ()) + HOVER;
        double vy = Math.max(-speed(), Math.min(speed(), (want - pos.getY()) * 0.2));
        if (d > STAND_OFF + 0.5) vel.copy(flat.multiply(speed() / d)).setY(vy);
        else if (d < STAND_OFF - 2) vel.copy(flat.multiply(-speed() * 0.5 / Math.max(d, 1e-3))).setY(vy);
        else vel.setX(0).setY(vy).setZ(0);
        tilt = (float) Math.max(TILT_MIN, Math.min(TILT_MAX, Math.toDegrees(Math.atan2(Math.max(1, pos.getY() - c.getY()), Math.max(0.5, d)))));
        maybeReform(20);
        if (age >= readyAt && d <= STAND_OFF + 6 && !game.mobs.standing().isEmpty()) startStare(c);
    }

    private void maybeReform(int ticks) {
        if (age - reformAt < 10) return;
        float dh = Float.isNaN(sentHeading) ? 999f : Math.abs(wrap(heading - sentHeading));
        float dt = Float.isNaN(sentTilt) ? 999f : Math.abs(tilt - sentTilt);
        if (dh > TURN_STEP || dt > TILT_STEP) reform(ticks);
    }

    /** 찍었다: 모두에게 낱개 마리를 보이고, 눈 픽셀이 눈 뜬 고리로 자리를 바꾼다. 죽은 눈 픽셀만큼 늦어진다. */
    private void startStare(Vector c) {
        Player p = game.mobs.nearestPlayer(c, 64);
        if (p == null) return;
        prey = p;
        swarm.lodDist = 1e6;
        int deadEyes = 0;
        for (Swarm.Member m : swarm.members) if (m.index < EYES && m.dead) deadEyes++;
        eyeDelay = Math.min(EYE_DELAY_MAX, deadEyes * EYE_DELAY);
        stareUntil = age + SWITCH_T + RESHUFFLE + STARE_HOLD + eyeDelay;
        phase(Phase.STARE);
    }

    private void stare() {
        hold();
        int t = inPhase();
        if (t == SWITCH_T) {
            mark(prey);
            sound("titter", Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1.7f, 48);
            for (Swarm.Member m : swarm.members) {
                if (m.dead || m.index >= EYES) continue;
                m.home.copy(home(m.index, true));
                m.moveTo(Swarm.rotate(m.home, heading), RESHUFFLE);
            }
        }
        if (prey == null) prey = game.mobs.nearestPlayer(pos.clone().setY(floor()), 64);
        if (age >= stareUntil) {
            if (prey == null) {
                regroup();
                return;
            }
            startRain();
        }
    }

    /** 비처럼 쏟아짐: 마리마다 닻 아래 반경 안 사람 하나의 몸통 둘레로(조금씩 다른 틱). */
    private void startRain() {
        List<Player> victims = new ArrayList<>();
        Vector ground = pos.clone().setY(floor());
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - ground.getX(), l.getZ() - ground.getZ()) <= RAIN_R) victims.add(p);
        }
        if (victims.isEmpty() && prey != null) victims.add(prey);
        if (victims.isEmpty()) {
            regroup();
            return;
        }
        sound("rain", Sound.ENTITY_PHANTOM_FLAP, 1.6f, 48);
        int k = 0, last = 0;
        for (Swarm.Member m : swarm.members) {
            struck[m.index] = false;
            if (m.dead) continue;
            Player v = victims.get(k++ % victims.size());
            Vector c = chest(v);
            Vector jitter = new Vector(game.random.nextGaussian() * 0.5, game.random.nextGaussian() * 0.35, game.random.nextGaussian() * 0.5);
            int dur = DIVE_T + m.index % DIVE_VAR;
            last = Math.max(last, dur);
            m.moveTo(c.clone().add(jitter).subtract(pos), dur);
            m.lookAt(c, 2);
        }
        rainEnd = age + last + LINGER;
        phase(Phase.RAIN);
    }

    private void rain() {
        hold();
        for (Player p : game.mobs.standing()) {
            Vector c = chest(p);
            for (Swarm.Member m : swarm.members) {
                if (m.dead || struck[m.index] || m.pos().distanceSquared(c) > TOUCH * TOUCH) continue;
                struck[m.index] = true;
                hit(p, MEMBER_DMG, "cause_g53");
            }
        }
        if (age >= rainEnd) regroup();
    }

    /** 살아남은 것들이 제 픽셀 자리로 다시 올라감(구멍 난 얼굴). */
    private void regroup() {
        mark(null);
        phase(Phase.RISE);
        reform(cd(RISE_T));
        sound("flutter", Sound.ENTITY_PHANTOM_FLAP, 1.8f, 24);
    }

    private void rise() {
        hold();
        if (inPhase() == cd(RISE_T) + 10) swarm.lodDist = 15;
        if (inPhase() >= cd(RISE_T) + 20) {
            prey = null;
            readyAt = age + cd(COOLDOWN);
            phase(Phase.DRIFT);
        }
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        return swarm.spheres();
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        double before = hp;
        DamageType t = d.type();
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            for (Swarm.Member m : swarm.within(d.at() != null ? d.at() : pos, SPLASH)) hurt(m, d.amount());
        } else {
            Swarm.Member m = swarm.nearest(d.at());
            if (m != null) hurt(m, d.amount());
        }
        hp = 0;
        for (Swarm.Member m : swarm.members) if (!m.dead) hp += Math.max(0, m.hp);
        game.mobs.onHurt(this, d, before - hp);
        if (t != DamageType.BITE) swarm.flinch();
        if (swarm.alive() == 0) die(d);
        return true;
    }

    private void hurt(Swarm.Member m, double amount) {
        m.hp -= amount;
        m.look.hurtUntil = game.worldTime() + 3;
        if (m.hp > 0) return;
        swarm.kill(m);
        // 덩어리에서 그 픽셀을 숨김
        body.look.on.add(String.format("d%02d", m.index));
        // 눈 픽셀을 잃으면 눈을 못 떠서 늦어짐(떠 있거나 눈 뜨는 중일 때)
        if (m.index < EYES && phase == Phase.STARE && eyeDelay < EYE_DELAY_MAX) {
            eyeDelay += EYE_DELAY;
            stareUntil += EYE_DELAY;
        }
    }

    public String phaseName() {
        return phase.name() + " alive=" + swarm.alive();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " alive=" + (swarm == null ? 0 : swarm.alive()) + String.format(" tilt=%.0f", tilt);
    }
}
