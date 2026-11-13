package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G110 긁는 게이브(떼, 돌격). 하늘 보고 누운 얼굴이 막대기 둘로 땅을 찍고 몸을 휙 당기기를 되풀이해 톡톡 끊기며 미끄러져 와 발목을 벤다.
 *
 * <p>당김: PULL 틱 동안 PULL_V 로 곧게 당기고 REST 틱 멈춤(평균 0.29블록/틱, 사람보다 빠름, 감독 빠르기 배율 곱함). 방향은 당김을 시작할 때만 정한다
 * (곧게만 옴: 옆으로 한 걸음이면 빗나감). 표적 발밑(EYE_R)에 닿으면 눈을 뜨고(mark), 발이 땅에 붙은 사람의 발목에 닿으면 DMG 피해와 느려짐
 * SLOW%(SLOW_T 틱, 다른 개체와 겹쳐 50% 까지). 사람이 바닥에서 JUMP 블록 넘게 떠 있으면 빗나간다. 벤 뒤 PASS 블록을 미끄러져 지나쳤다가
 * 돌아선다.
 *
 * <p>맞는 구는 낮다(중심이 바닥 위 0.12). 폭발, 불에 약하다(받는 피해 x1.3).
 *
 * <p>팩 계약(tools/gabe/sp/g110.py): 상태 "", 소리 scrape(28), slash(16), 글귀 cause_g110.
 */
public final class G110 extends Gabe {

    static final double HOVER = 0.12, RADIUS = 0.42;
    /** 당김 틱, 멈춤 틱, 당김 빠르기(블록/틱). */
    static final int PULL = 4, REST = 3;
    static final double PULL_V = 0.5, BASE = 0.29;
    /** 피해, 느려짐, 눈 뜨는 거리, 발이 떠 있으면 빗나가는 높이, 벤 뒤 지나치는 거리, 돌아서기 틱, 다시 벨 때까지(박자 배율). */
    static final double DMG = 6, SLOW = 25, EYE_R = 4, JUMP = 0.4, PASS = 3;
    static final int SLOW_T = 40, TURN = 12, COOLDOWN = 20;
    /** 폭발, 불 배율. */
    static final double WEAK = 1.3;

    enum Phase { CRAWL, PASS, TURN }

    private Phase phase = Phase.CRAWL;
    private int phaseAt, cycleAt, pulls, readyAt;
    private final Vector dir = new Vector(0, 0, 1);
    private double passGone;

    public G110(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = BASE;
    }

    @Override
    protected void spawned() {
        cycleAt = age - game.random.nextInt(PULL + REST);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    @Override
    public void tick() {
        switch (phase) {
            case CRAWL -> crawl();
            case PASS -> pass();
            case TURN -> turn();
        }
    }

    private void crawl() {
        int t = age - cycleAt;
        Player p = nearest(400);
        if (t >= PULL + REST) {
            cycleAt = age;
            t = 0;
            // 당김 시작: 이때만 방향을 정함
            Vector d;
            if (p != null && dist(p) <= 24) d = feet(p).subtract(pos).setY(0);
            else d = game.mobs.flow.dir(pos).setY(0);
            if (d.lengthSquared() > 1e-6) dir.copy(d.normalize());
            pulls++;
            boolean close = p != null && dist(p) <= 24;
            if (close || pulls % 4 == 0) sound("scrape", Sound.BLOCK_GRAVEL_BREAK, 0.6f, 28);
            if (close) dust();
        }
        if (t < PULL) dash(dir, PULL_V * speed() / baseSpeed);
        else hold();
        yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        pitch = 0;
        if (p == null) return;
        if (dist(p) <= EYE_R) {
            if (target != p) mark(p);
        } else if (target != null && (!standing(target) || dist(target) > EYE_R * 2)) mark(null);
        if (age >= readyAt && ankle(p)) strike(p);
    }

    /** 발목에 닿았나: 수평 거리와 발 높이(뛰어오르면 빗나감). */
    private boolean ankle(Player p) {
        Location l = p.getLocation();
        double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ(), r = RADIUS + 0.45;
        if (dx * dx + dz * dz > r * r) return false;
        return l.getY() - game.mobs.flow.floor(l.getX(), l.getZ()) <= JUMP;
    }

    private void strike(Player p) {
        hit(p, DMG, "cause_g110");
        game.players.slow(p, SLOW, SLOW_T, (id & 1) == 0 ? "g110a" : "g110b");
        sound("slash", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 16);
        readyAt = age + cd(COOLDOWN);
        passGone = 0;
        phase(Phase.PASS);
    }

    /** 벤 뒤 같은 쪽으로 미끄러져 지나침. */
    private void pass() {
        double v = PULL_V * speed() / baseSpeed * Math.max(0.25, 1 - passGone / PASS);
        dash(dir, v);
        passGone += v;
        if (passGone >= PASS || wallAhead(0.5)) {
            hold();
            phase(Phase.TURN);
        }
    }

    private void turn() {
        hold();
        Player p = nearest(400);
        Vector to = p != null ? feet(p) : pos.clone().add(game.mobs.flow.dir(pos));
        boolean done = turnTo(to, 18f);
        pitch = 0;
        if (done || age - phaseAt >= cd(TURN)) {
            Vector d = to.clone().subtract(pos).setY(0);
            if (d.lengthSquared() > 1e-6) dir.copy(d.normalize());
            cycleAt = age - (PULL + REST);
            phase(Phase.CRAWL);
        }
    }

    private Vector feet(Player p) {
        return p.getLocation().toVector();
    }

    /** 낮은 흙먼지 줄(당길 때 뒤로). */
    private void dust() {
        try {
            BlockData bd = game.world.getBlockAt((int) Math.floor(pos.getX()), (int) Math.floor(floor() - 0.5), (int) Math.floor(pos.getZ()))
                    .getBlockData();
            Location l = new Location(game.world, pos.getX() - dir.getX() * 0.5, floor() + 0.05, pos.getZ() - dir.getZ() * 0.5);
            game.world.spawnParticle(Particle.BLOCK, l, 3, 0.25, 0.02, 0.25, 0.0, bd);
        } catch (RuntimeException e) {
            // 바닥 블록을 못 읽으면 먼지 없이
        }
    }

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), RADIUS});
    }

    @Override
    public boolean damage(Damage d) {
        if (d.type() == DamageType.EXPLOSION || d.type() == DamageType.FIRE) d = d.times(WEAK);
        return super.damage(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase;
    }
}
