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

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G67 허수아비 게이브(1막 원거리, 방해). 기둥 하나로 깡충깡충(한 번 1.5블록) 다가와 18~24블록에서 땅에 박혀 선다. 3초마다 얼굴이 '삐' 하며 눈을 번쩍 뜨고
 * 0.5초 동안 둘레 30블록을 훑는다. 그 0.5초 동안 0.4블록 넘게 움직인 사람(가까운 둘까지)에게 가로 막대기 끝 얼굴 열매를 곧게 튕겨 쏜다(9 피해).
 * 쏘고 나면 옆으로 깡충 자리를 옮긴다. 3블록 안에 온 사람은 가로 막대기로 후려친다(6 피해와 밀침).
 *
 * <p>대처: '삐'가 울리면 0.5초 멈춘다(쏘기, 고개 돌리기는 움직임이 아님). 열매는 쏜 순간의 자리로 곧게 날아오니 옆걸음으로도 피한다. 체력이 낮다.
 *
 * <p>팩 계약(tools/gabe/sp/g67.py): 모델 gabe/g67 상태 "", "scan"(큰 눈), "hop"(기둥 접힘). 열매 탄 gabe/g67_fruit(부품, 세계 축).
 * 소리 scan(살피는 삐, 32), shoot, hop, swat. 원인 글귀 cause_g67.
 */
public final class G67 extends Gabe {

    static final double SPEED = 0.11, HOVER = 1.75, HOP_UP = 1.0;
    static final int HOP_T = 14, HOP_PAUSE = 4;
    /** 서는 거리(이 안이면 박혀 섬), 다시 다가오는 거리. */
    static final double PLANT_FAR = 24, APPROACH_AGAIN = 27;
    /** 훑기: 간격 틱, 창 틱(박자 배율 안 받음: 대처 창), 훑는 반경, 움직임 문턱, 한 번에 쏘는 수. */
    static final int SCAN_EVERY = 50, SCAN_T = 10;
    static final double SCAN_R = 30, MOVED = 0.4;
    static final int SHOTS = 2, SHOT_GAP = 4;
    /** 열매 탄: 빠르기, 맞는 거리, 피해, 최대 틱. */
    static final double FRUIT_V = 0.9, FRUIT_HIT = 0.6, FRUIT_DMG = 9;
    static final int FRUIT_MAX = 44;
    /** 후려치기: 닿는 거리(몸 반지름 밖), 피해, 쉬는 틱. */
    static final double SWAT_REACH = 2.2, SWAT_DMG = 6;
    static final int SWAT_CD = 30;
    static final int REPOSITION_HOPS = 2;

    enum Phase { APPROACH, STAND, SCAN, MOVE }

    /** 날아가는 열매 하나. */
    final class Fruit {
        Body.Part p;
        final Vector at = new Vector(), v = new Vector();
        int t;
    }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, swatAt, hopAt = -1, hopsLeft, shotsQueued, nextShot;
    private final Vector hopDir = new Vector(0, 0, 1);
    private boolean inAir;
    private final Map<UUID, double[]> moved = new HashMap<>();
    private final List<Player> shootAt = new ArrayList<>(SHOTS);
    private final List<Fruit> fruits = new ArrayList<>(SHOTS);
    private final Matrix4f mat = new Matrix4f();

    public G67(Game game, Species sp) {
        super(game, sp);
        radius = 0.45;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g67", 2.6f, 3.2f);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    @Override
    public void tick() {
        for (int i = fruits.size() - 1; i >= 0; i--) fruitTick(fruits.get(i));
        if (inAir) {
            hopTick();
            return;
        }
        switch (phase) {
            case APPROACH -> approach();
            case STAND -> stand();
            case SCAN -> scan();
            case MOVE -> move();
        }
        if (phase != Phase.APPROACH && phase != Phase.MOVE) swat();
    }

    // ------------------------------------------------------------------ 깡충

    private void hop(Vector dir) {
        hopDir.copy(dir).setY(0);
        if (hopDir.lengthSquared() < 1e-6) hopDir.setX(0).setZ(1);
        hopDir.normalize();
        inAir = true;
        hopAt = age;
        state("hop");
    }

    private void hopTick() {
        int t = age - hopAt;
        double k = Math.min(1.0, (double) t / HOP_T);
        hover = HOVER + HOP_UP * Math.sin(Math.PI * k);
        vel.copy(hopDir).multiply(1.5 / HOP_T * speed() / SPEED);
        if (wallAhead(0.5)) vel.zero();
        if (t >= HOP_T) {
            inAir = false;
            hover = HOVER;
            hold();
            state(phase == Phase.SCAN ? "scan" : "");
            body.squash(1.12f, 0.88f, 1.12f, 2);
            sound("hop", Sound.BLOCK_WOOD_STEP, 0.8f, 16);
            hopAt = age;
        }
    }

    /** 다가오기: 깡충 사이에 잠깐 쉼. 24블록 안이면 박혀 섬. */
    private void approach() {
        hold();
        Player p = nearest(400);
        if (p != null) face(chest(p));
        if (p != null && dist(p) <= PLANT_FAR) {
            plant();
            return;
        }
        if (age - hopAt < HOP_PAUSE) return;
        Vector d = p != null ? toward(p) : game.mobs.flow.dir(pos);
        hop(d);
    }

    private void plant() {
        hold();
        body.squash(1.05f, 0.92f, 1.05f, 2);
        phase(Phase.STAND);
    }

    /** 자리를 옮김: 표적과 옆으로 깡충 둘(다가오는 쪽으로 조금). */
    private void move() {
        hold();
        if (hopsLeft <= 0) {
            plant();
            return;
        }
        if (age - hopAt < HOP_PAUSE) return;
        hopsLeft--;
        Player p = nearest(400);
        Vector to = p != null ? toward(p) : game.mobs.flow.dir(pos);
        double side = (id % 2 == 0 ? 1 : -1) * ((hopsLeft + age / 97) % 2 == 0 ? 1 : -1);
        Vector d = new Vector(-to.getZ() * side, 0, to.getX() * side).add(to.clone().multiply(p != null && dist(p) > PLANT_FAR ? 0.8 : 0.15));
        hop(d);
    }

    // ------------------------------------------------------------------ 훑기

    private void stand() {
        hold();
        Player p = nearest(400);
        if (p != null) face(chest(p));
        if (p == null || dist(p) > APPROACH_AGAIN) {
            phase(Phase.APPROACH);
            return;
        }
        if (inPhase() >= cd(SCAN_EVERY)) startScan();
    }

    private void startScan() {
        state("scan");
        sound("scan", Sound.BLOCK_NOTE_BLOCK_BIT, 1.6f, 32);
        moved.clear();
        for (Player q : game.mobs.standing()) {
            if (q.getLocation().toVector().distanceSquared(pos) > SCAN_R * SCAN_R) continue;
            Location l = q.getLocation();
            moved.put(q.getUniqueId(), new double[] {l.getX(), l.getY(), l.getZ(), 0});
        }
        shotsQueued = 0;
        phase(Phase.SCAN);
    }

    private void scan() {
        hold();
        int t = inPhase();
        if (t <= SCAN_T) {
            for (Player q : game.mobs.standing()) {
                double[] m = moved.get(q.getUniqueId());
                if (m == null) continue;
                Location l = q.getLocation();
                double dx = l.getX() - m[0], dy = l.getY() - m[1], dz = l.getZ() - m[2];
                m[3] += Math.sqrt(dx * dx + dy * dy + dz * dz);
                m[0] = l.getX();
                m[1] = l.getY();
                m[2] = l.getZ();
            }
            if (t == SCAN_T) pickShots();
            return;
        }
        if (shotsQueued > 0 && age >= nextShot) {
            Player q = shootAt.get(shootAt.size() - shotsQueued);
            shotsQueued--;
            nextShot = age + SHOT_GAP;
            if (standing(q)) shoot(q);
        }
        if (shotsQueued <= 0 && age >= nextShot) {
            state("");
            if (shootAt.isEmpty()) {
                plant();
            } else {
                hopsLeft = REPOSITION_HOPS;
                phase(Phase.MOVE);
            }
        }
    }

    /** 0.5초 동안 문턱보다 많이 움직인 사람 가운데 가까운 둘. */
    private void pickShots() {
        shootAt.clear();
        for (int n = 0; n < SHOTS; n++) {
            Player best = null;
            double bd = Double.MAX_VALUE;
            for (Player q : game.mobs.standing()) {
                double[] m = moved.get(q.getUniqueId());
                if (m == null || m[3] <= MOVED || shootAt.contains(q)) continue;
                double d = dist(q);
                if (d < bd) {
                    bd = d;
                    best = q;
                }
            }
            if (best == null) break;
            shootAt.add(best);
        }
        shotsQueued = shootAt.size();
        nextShot = age;
        if (!shootAt.isEmpty()) mark(shootAt.get(0));
        else if (target != null) mark(null);
    }

    // ------------------------------------------------------------------ 열매 탄

    private void shoot(Player q) {
        face(chest(q));
        Body.Part part = body.attach("gabe/g67_fruit");
        if (part == null) return;
        part.follow = false;
        if (part.d != null) {
            part.d.setDisplayWidth(0f);
            part.d.setDisplayHeight(0f);
        }
        Fruit f = new Fruit();
        f.p = part;
        // 가로 막대기 끝(몸 왼쪽 또는 오른쪽 1.15)에서
        double r = Math.toRadians(yaw), side = (fruits.size() + age) % 2 == 0 ? 1.15 : -1.15;
        f.at.copy(pos).add(new Vector(Math.cos(r) * side, -0.3, Math.sin(r) * side));
        Vector d = chest(q).subtract(f.at);
        if (d.lengthSquared() < 1e-6) d = new Vector(0, 0, 1);
        f.v.copy(d.normalize().multiply(FRUIT_V));
        fruits.add(f);
        sound("shoot", Sound.ENTITY_SNOWBALL_THROW, 1.2f, 20);
        body.squash(0.92f, 1.06f, 0.92f, 2);
        poseFruit(f, 1);
    }

    private void fruitTick(Fruit f) {
        f.t++;
        Vector prev = f.at.clone();
        f.at.add(f.v);
        for (Player p : game.mobs.standing()) {
            Vector c = chest(p);
            if (segDist2(prev, f.at, c) <= FRUIT_HIT * FRUIT_HIT) {
                hit(p, FRUIT_DMG, "cause_g67");
                endFruit(f);
                return;
            }
        }
        double fl = game.mobs.flow.floor(f.at.getX(), f.at.getZ());
        if (f.t >= FRUIT_MAX || f.at.getY() <= fl + 0.1 || !game.world.getBlockAt(f.at.getBlockX(), f.at.getBlockY(), f.at.getBlockZ()).isPassable()) {
            endFruit(f);
            return;
        }
        if (f.t % 2 == 0) poseFruit(f, 2);
    }

    private static double segDist2(Vector a, Vector b, Vector c) {
        double abx = b.getX() - a.getX(), aby = b.getY() - a.getY(), abz = b.getZ() - a.getZ();
        double acx = c.getX() - a.getX(), acy = c.getY() - a.getY(), acz = c.getZ() - a.getZ();
        double l2 = abx * abx + aby * aby + abz * abz;
        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, (acx * abx + acy * aby + acz * abz) / l2));
        double dx = acx - abx * t, dy = acy - aby * t, dz = acz - abz * t;
        return dx * dx + dy * dy + dz * dz;
    }

    private void endFruit(Fruit f) {
        if (f.p != null) f.p.remove();
        f.p = null;
        fruits.remove(f);
        game.shotFx.bloodHit(new Location(game.world, f.at.getX(), f.at.getY(), f.at.getZ()), f.v.clone(), false, kr.gave.gun.ShotFx.BLOOD_PINK);
    }

    private void poseFruit(Fruit f, int ticks) {
        if (f.p == null) return;
        Vector off = f.at.clone().subtract(pos);
        mat.identity().translate((float) off.getX(), (float) off.getY(), (float) off.getZ())
                .rotateY((float) Math.atan2(f.v.getX(), f.v.getZ())).rotateX((float) (f.t * 0.6));
        f.p.pose(mat, ticks);
    }

    // ------------------------------------------------------------------ 후려치기

    private void swat() {
        if (age < swatAt) return;
        for (Player p : game.mobs.standing()) {
            if (!touches(p, SWAT_REACH)) continue;
            face(chest(p));
            hit(p, SWAT_DMG, "cause_g67");
            knock(p, toward(p), 0.5, 0.25);
            sound("swat", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 16);
            body.roll = (float) (game.random.nextBoolean() ? 25 : -25);
            body.squash(1f, 1f, 1f, 3);
            swatAt = age + cd(SWAT_CD);
            return;
        }
        if (body.roll != 0 && age - swatAt + cd(SWAT_CD) > 6) body.roll = 0;
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), pos.getY() + 0.72, pos.getZ(), 0.48});
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.32});
        out.add(new double[] {pos.getX(), pos.getY() - 0.9, pos.getZ(), 0.22});
        return out;
    }

    @Override
    public void die(kr.gave.mob.Damage d) {
        for (Fruit f : new ArrayList<>(fruits)) {
            if (f.p != null) f.p.remove();
            f.p = null;
        }
        fruits.clear();
        super.die(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (inAir ? " air" : "") + " fruits=" + fruits.size();
    }
}
