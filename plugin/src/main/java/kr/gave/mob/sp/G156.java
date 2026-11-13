package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G156 줄넘기 게이브(떼, 방해). 땅에 반쯤 박힌 얼굴 둘이 3.5 블록 떨어져 살 줄을 돌리며 나란히 미끄러져 온다. 줄은 20틱(1초)에 한 바퀴, 바퀴마다
 * 땅을 '탁' 친다. 사람 무리를 줄로 훑고 지나가 PASS 블록 뒤에서 돌아서 다시 훑는다.
 *
 * <p>줄 판정: 줄 자리는 팩과 같은 식(ANGLES, 주기 20틱 고정, 박자 배율을 안 탐: 읽을 수 있게)으로 판 시계(world.getGameTime)에서 센다. 줄이 땅에
 * 닿는 두 틱(위상 0, 1)에 두 손잡이를 잇는 선분 둘레 HIT_BAND 안에서 발이 바닥에서 FOOT_UP 넘게 떠 있지 않은 사람에게 TRIP 피해, 넘어짐(root)
 * TRIP_ROOT 틱, 줄 가는 쪽(앞)으로 끌림. '탁' 에 맞춰 뛰면 발밑을 지나간다.
 *
 * <p>맞는 구는 줄 한가운데 매듭 하나뿐(돌기 위상을 따라 움직임, 양 끝 얼굴은 땅에 박혀 탄이 지나감). 매듭이 꼭대기(자세 4)에서 멈춘 듯 느리고
 * 눈을 뜨고 반짝인다(팩). 매듭이 0 이 되면 줄이 끊겨 양 끝 얼굴이 따로 'whip'(child, 체력 WHIP_HP x 감독 체력 배율, 보상 0)으로 갈라져 끊긴 줄을
 * 채찍처럼 끌고 달려든다(하나마다 WHIP 피해와 넘어짐 WHIP_ROOT).
 *
 * <p>도감과 다른 점: 줄이 같은 자리를 다시 칠 때까지 무리 위를 지나가는 동안은 SWEEP_SPEED 로 느려진다(1 바퀴에 1.8 블록, 서 있는 사람은 지나가는
 * 동안 꼭 한 번 땅 치는 줄을 만남). 끊긴 끝의 보상은 공통 규칙(분열 개체 보상 0)을 따랐다.
 *
 * <p>팩 계약(tools/gabe/sp/g156.py): 상태 "", "whip", 소리 slap(24), snap(20), 글귀 cause_g156.
 */
public final class G156 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감, 팩과 같게)

    /** 몸 가운데에서 바닥까지(팩 GROUND -0.55), 손잡이 끝 높이와 가로 자리, 줄 처짐 반지름, 끝 얼굴 가로 자리. */
    static final double HOVER = 0.55, AXIS_Y = 0.6, AXIS_X = 1.6, ROPE_R = 1.12, END_X = 1.75;
    /** 줄 자세 k 의 각(도, 0 = 땅). 팩 g156.py ANGLES 와 같게. */
    static final int[] ANGLES = {0, 60, 115, 155, 180, 205, 245, 300};
    static final int PERIOD = 20;
    static final double SPEED = 0.2, SWEEP_SPEED = 0.09, KNOT_R = 0.3;
    /** 줄에 걸림: 피해, 넘어짐 틱, 선분 둘레 폭, 발이 떠도 되는 높이, 끌림 세기. */
    static final double TRIP = 6, HIT_BAND = 0.9, FOOT_UP = 0.3, DRAG = 0.42;
    static final int TRIP_ROOT = 16;
    /** 훑기: 이 안에서 훑기 시작, 지나간 뒤 이만큼 뒤에서 돌아섬, 훑기 최대 틱. */
    static final double ENGAGE = 12, PASS = 6;
    static final int SWEEP_MAX = 260;
    /** 끊긴 끝(whip). */
    static final double WHIP_HP = 6, WHIP = 6, WHIP_SPEED = 0.22, WHIP_REACH = 2.2, WHIP_HOVER = 0.18;
    static final int WHIP_ROOT = 6, WHIP_CD = 30;

    enum Phase { APPROACH, SWEEP, TURN }

    /** child 로 끊긴 끝을 놓는 동안 참(그 개체의 spawned 가 whip 으로 시작). */
    private static boolean makingWhip;

    private boolean whip;
    private Phase phase = Phase.APPROACH;
    private int phaseAt, whipReady;
    private final Vector heading = new Vector(0, 0, 1);
    private final Map<UUID, Long> tripped = new HashMap<>();

    public G156(Game game, Species sp) {
        super(game, sp);
        radius = KNOT_R;
        hover = HOVER;
        baseSpeed = SPEED;
        noPush = true;
        noBite = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g156", 4.4f, 3.0f);
    }

    @Override
    protected void spawned() {
        if (makingWhip) {
            whip = true;
            state("whip");
            hover = WHIP_HOVER;
            radius = 0.34;
            baseSpeed = WHIP_SPEED;
            noPush = false;
            noBite = false;
            return;
        }
        // 줄 위상: 주기 20 의 1/4 배수만(셰이더 위상 칸 16분의 1 에 꼭 맞아 Java 가 센 줄 자리와 화면이 같은 틱)
        body.look.phase = 5 * game.random.nextInt(4);
        Vector d = game.mobs.flow.dir(pos);
        if (d.lengthSquared() > 1e-6) face(pos.clone().add(d));
        pitch = 0;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (whip) {
            whipTick();
            return;
        }
        int t = ropeTick();
        if (t == 0) sound("slap", Sound.ENTITY_SLIME_ATTACK, 0.6f, 24);
        if (t <= 1) ropeHit();
        Player p = standing(target) ? target : nearest(400);
        switch (phase) {
            case APPROACH -> approach(p);
            case SWEEP -> sweep(p);
            case TURN -> turn(p);
        }
        pitch = 0;
    }

    private void phase(Phase ph) {
        phase = ph;
        phaseAt = age;
    }

    private void approach(Player p) {
        if (p == null || dist(p) > ENGAGE) {
            if (target != null && p == null) mark(null);
            follow(speed());
            if (vel.lengthSquared() > 1e-6) turnTo(pos.clone().add(vel), 6f);
            return;
        }
        mark(p);
        heading.copy(toward(p));
        phase(Phase.SWEEP);
    }

    private void sweep(Player p) {
        if (p == null) {
            phase(Phase.APPROACH);
            return;
        }
        if (p != target) mark(p);
        Vector f = fwd();
        // 무리 위를 지나는 동안은 느리게(줄이 서 있는 사람을 꼭 한 번 땅에서 만나게)
        boolean over = false;
        for (Player o : game.mobs.standing()) {
            Location l = o.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            double along = dx * f.getX() + dz * f.getZ(), lat = Math.abs(dx * f.getZ() - dz * f.getX());
            if (lat <= END_X + 0.6 && along > -1.5 && along < 3.0) {
                over = true;
                break;
            }
        }
        dash(heading, over ? SWEEP_SPEED * speed() / baseSpeed : speed());
        turnTo(pos.clone().add(heading), 8f);
        Location l = p.getLocation();
        double along = (l.getX() - pos.getX()) * heading.getX() + (l.getZ() - pos.getZ()) * heading.getZ();
        if (along < -PASS || inPhase() > SWEEP_MAX || wallAhead(1.2)) phase(Phase.TURN);
        else if (dist(p) > ENGAGE * 1.8) phase(Phase.APPROACH);
    }

    private void turn(Player p) {
        hold();
        if (p == null) {
            phase(Phase.APPROACH);
            return;
        }
        if (turnTo(chest(p), 9f)) {
            heading.copy(toward(p));
            phase(Phase.SWEEP);
        }
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 줄이 땅에 닿는 틱: 손잡이 둘을 잇는 선분 둘레에 발을 디딘 사람. */
    private void ropeHit() {
        Vector f = fwd();
        long now = now();
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            double along = dx * f.getX() + dz * f.getZ(), lat = dx * f.getZ() - dz * f.getX();
            if (Math.abs(lat) > AXIS_X + 0.25 || Math.abs(along) > HIT_BAND) continue;
            double up = l.getY() - game.mobs.flow.floor(l.getX(), l.getZ());
            if (up > FOOT_UP) continue;
            Long until = tripped.get(p.getUniqueId());
            if (until != null && until > now) continue;
            tripped.put(p.getUniqueId(), now + 12);
            hit(p, TRIP, "cause_g156");
            game.players.root(p, TRIP_ROOT, "trip");
            knock(p, f, DRAG, 0.06);
            mark(p);
        }
        if (tripped.size() > 40) tripped.values().removeIf(u -> u <= now);
    }

    // ------------------------------------------------------------------ 끊긴 끝

    private void whipTick() {
        Player p = standing(target) ? target : nearest(400);
        if (p != target) mark(p);
        if (p == null) {
            follow(speed());
            return;
        }
        double d = dist(p);
        if (d > 20) follow(speed());
        else seek(p.getLocation().toVector(), speed());
        face(chest(p));
        pitch = 0;
        if (age >= whipReady && d <= WHIP_REACH) {
            whipReady = age + cd(WHIP_CD);
            hit(p, WHIP, "cause_g156");
            game.players.root(p, WHIP_ROOT, "trip");
            knock(p, toward(p), 0.25, 0.08);
            sound("slap", Sound.ENTITY_SLIME_ATTACK, 0.8f, 24);
            body.squash(1.2f, 0.85f, 1.2f, 2);
        }
    }

    private void initWhip(double hpMul, float yaw) {
        maxHp = hp = WHIP_HP * hpMul;
        this.yaw = yaw;
        whipReady = age + 10;
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        if (whip) return super.spheres();
        Vector k = knot();
        return List.of(new double[] {k.getX(), k.getY(), k.getZ(), KNOT_R});
    }

    @Override
    public Vector head() {
        return whip ? pos : knot();
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (!whip) {
            // 줄이 끊김: 몸(얼굴 둘과 줄)은 그 자리에서 사라지고 양 끝이 따로 달려듦
            leaveCorpse = false;
            sound("snap", Sound.ENTITY_SLIME_SQUISH, 1f, 20);
            double mul = sp.hp > 0 ? maxHp / sp.hp : 1;
            Vector L = left();
            for (int side = -1; side <= 1; side += 2) {
                Vector at = pos.clone().add(L.clone().multiply(side * END_X));
                at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + WHIP_HOVER);
                makingWhip = true;
                try {
                    Gabe c = child(sp, at);
                    if (c instanceof G156 w) w.initWhip(mul, yaw + side * 60f);
                } finally {
                    makingWhip = false;
                }
            }
        }
        super.die(d);
    }

    // ------------------------------------------------------------------ 도우미

    /** 지금 보이는 줄 자세의 매듭 자리(세계 좌표): 팩과 같은 식(판 시계, 주기 20, 자세 8). */
    private Vector knot() {
        int k = ropeTick() * ANGLES.length / PERIOD;
        double a = Math.toRadians(ANGLES[Math.min(k, ANGLES.length - 1)]);
        Vector f = fwd();
        double y = AXIS_Y - ROPE_R * Math.cos(a), z = ROPE_R * Math.sin(a);
        return new Vector(pos.getX() + f.getX() * z, pos.getY() + y, pos.getZ() + f.getZ() * z);
    }

    /** 줄 위상(0..19): 셰이더와 같은 판 시계 + 개체 위상. */
    private int ropeTick() {
        return (int) Math.floorMod(game.worldTime() + (body == null ? 0 : body.look.phase), (long) PERIOD);
    }

    private Vector fwd() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** 몸 왼쪽(모델 +X). */
    private Vector left() {
        double r = Math.toRadians(yaw);
        return new Vector(Math.cos(r), 0, Math.sin(r));
    }

    @Override
    public String toString() {
        return super.toString() + (whip ? " whip" : " phase=" + phase + " rope=" + ropeTick());
    }
}
