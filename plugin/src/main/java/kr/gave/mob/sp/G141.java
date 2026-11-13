package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G141 밀려오는 얼굴 벽 게이브(4막, 방패, 튼튼이). 폭 16, 높이 4 블록 얼굴 벽돌 벽이 흙먼지 끝 한쪽에서 가로로 나타나 초당 2.5 블록으로
 * 트럭 곁(사람이 많은 쪽으로 9~13 블록 비켜)을 지나 경기장을 일직선으로 밀고 온다. 반대편 끝(가운데에서 58 블록)에 닿으면 돌아서지 않고 그대로 거꾸로 밀고 온다
 * (앞뒤가 같은 모습이라 나아가는 쪽 면이 앞).
 *
 * <ul>
 * <li>밀기: 나아가는 쪽 면에 닿은 사람은 벽 앞에 붙어 함께 밀려가고(공용 PUSH) 초당 6.</li>
 * <li>망루 얼굴 셋이 4초마다 얼굴 덩이를 벽 너머(벽 뒤나 양 끝 바깥 사람 먼저)로 던짐: 떨어진 자리 반경 1.5 안 8.</li>
 * <li>맞음: 앞면(나아가는 쪽에서 날아온 탄)은 x0(팅), 뒷면 x1.5, 양 끝 큰 얼굴 x2. 폭발은 터진 자리로: 뒤 x1.5, 끝 x2, 앞 x0.2.</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g141.py): 모델 gabe/g141(shrink 0.15, 블록 좌표 그대로), 상태 "", 실눈 뜸이면 벽돌 얼굴이 모두 눈을 뜸.
 * 덩이 gabe/g141_lump. 소리 grind(64), toss(32), splat(24), tink(24). 글귀 cause_g141, cause_g141_lump.
 *
 * <p>도감과 다른 점: 벽 판 셋과 끝 얼굴 둘을 부품으로 나누지 않고 닻 하나 모델에 구웠다(디스플레이 1). 벽돌 얼굴 수백 장은 작은 얼굴을 이은
 * 텍스처 판(앞뒤 넷씩) + 도드라진 얼굴 판 스물이다. 트럭 발자국을 지나지 않게 트럭 옆 9~13 블록 줄로 지나간다(끝 얼굴이 트럭을 스치면 초당 1.5%).
 */
public final class G141 extends Gabe {

    static final double HOVER = 2.0, SPEED = 0.125;
    static final double HALF_W = 8.0, HALF_H = 2.0, HALF_D = 0.55;
    /** 반대편 끝(가운데에서 나아가는 쪽으로 잰 거리), 트럭 옆으로 비켜 가는 거리. */
    static final double TURN_R = 58, OFF_MIN = 9, OFF_MAX = 13;
    /** 밀기: 면 앞 닿는 두께, 미는 빠르기(블록/틱), 피해(PUSH_EVERY 틱마다). */
    static final double PUSH_DEPTH = 1.4, PUSH_V = 0.19, PUSH_DMG = 3;
    static final int PUSH_EVERY = 10;
    /** 망루: 던지는 쿨다운, 사거리, 비행 틱, 떨어진 자리 반경, 피해. */
    static final int TOSS_CD = 80, TOSS_LEN = 24;
    static final double TOSS_RANGE = 34, LUMP_R = 1.5, LUMP_DMG = 8;
    static final double[] TURRETS = {-5, 0, 5};
    static final double TURRET_Y = 3.0;
    /** 배율: 뒷면, 끝 얼굴, 앞면 폭발. 끝 얼굴로 치는 옆 거리. */
    static final double BACK_MUL = 1.5, END_MUL = 2.0, FRONT_BLAST = 0.2, END_U = 7.4;
    /** 트럭을 스칠 때 초당 깎는 몫. */
    static final double TRUCK_FRAC = 0.015;

    /** 나아가는 쪽(수평 단위). 앞면 = 이쪽을 보는 면. */
    private final Vector trav = new Vector(0, 0, 1);
    private double offset;
    private int tossAt, grindAt, pushAt, tinkAt;
    private final G141Kit.Lobs lobs = new G141Kit.Lobs("gabe/g141_lump", 3);
    private final List<double[]> sph = new ArrayList<>(24);
    private int sphAge = -1;
    private int reversals;

    public G141(Game game, Species sp) {
        super(game, sp);
        radius = 1.3;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        noPush = true;
        noMutate = true;
        noBite = true;
        ghost = true;
        ragKick = 0.15;
        hpPow = Director.Tune.HP_POW_BIG;
    }

    @Override
    protected void build() {
        // 높이는 작게(닻 엔티티가 몸 가운데 - 높이/2 에 놓임: 땅 밑 깊으면 클라가 안 그림)
        body = Body.of(this, "gabe/g141", 18f, 7f);
        body.viewRange(3.2f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Vector c = G141Kit.middle();
        Vector a = c.clone().subtract(pos).setY(0);
        if (a.lengthSquared() < 1e-6) a = new Vector(0, 0, 1);
        a.normalize();
        // 사람이 많은 쪽 옆으로 비켜 선 줄을 곧게 밀고 옴(흙먼지 끝에서 옆으로 옮겨 나타남)
        offset = (OFF_MIN + game.random.nextDouble() * (OFF_MAX - OFF_MIN)) * side(a);
        Vector lat = new Vector(a.getZ(), 0, -a.getX());
        Vector at = pos.clone().add(lat.multiply(offset));
        game.mobs.relocate(this, at);
        trav.copy(a);
        yaw = G141Kit.yawOf(trav);
        body.pose(0);
        tossAt = age + 60;
        grindAt = age;
    }

    /** 축 a 의 왼쪽(+1)과 오른쪽(-1) 가운데 줄 둘레 10 블록 안 사람이 많은 쪽(같으면 가장 가까운 사람 쪽, 없으면 아무 쪽). */
    private int side(Vector a) {
        Vector c = G141Kit.middle();
        Vector lat = new Vector(a.getZ(), 0, -a.getX());
        int plus = 0, minus = 0;
        double best = 1e9;
        int near = game.random.nextBoolean() ? 1 : -1;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double u = (l.getX() - c.getX()) * lat.getX() + (l.getZ() - c.getZ()) * lat.getZ();
            double mag = (OFF_MIN + OFF_MAX) / 2;
            if (Math.abs(u - mag) <= 10) plus++;
            if (Math.abs(u + mag) <= 10) minus++;
            double d = G141Kit.hdist(pos, l);
            if (d < best) {
                best = d;
                near = u >= 0 ? 1 : -1;
            }
        }
        return plus > minus ? 1 : minus > plus ? -1 : near;
    }

    /** 거꾸로 밀 때 나아갈 줄: 축 a(가운데 쪽)에서 옆으로 offset 비켜 간 줄 위 점을 향함. */
    private void aim(Vector a) {
        Vector c = G141Kit.middle();
        Vector lat = new Vector(a.getZ(), 0, -a.getX());
        Vector t = c.clone().add(lat.multiply(offset)).subtract(pos).setY(0);
        if (t.lengthSquared() < 1e-6) t = a.clone();
        trav.copy(t.normalize());
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        Vector c = G141Kit.middle();
        // 반대편 끝: 돌아서지 않고 거꾸로(앞뒤가 같은 모습: 몸은 그대로 두고 나아가는 쪽만 바꿈, 옆으로 비킨 쪽도 바꿈)
        double ahead = (pos.getX() - c.getX()) * trav.getX() + (pos.getZ() - c.getZ()) * trav.getZ();
        if (ahead > TURN_R) {
            Vector back = trav.clone().multiply(-1);
            offset = Math.abs(offset) * side(back);
            aim(back);
            reversals++;
            sound("grind", Sound.BLOCK_GRINDSTONE_USE, 0.5f, 64);
        }
        vel.setX(trav.getX() * speed());
        vel.setZ(trav.getZ() * speed());
        // 몸 방향: 앞면(+Z 또는 -Z, 모양이 같음)이 나아가는 쪽에 가깝게 천천히 맞춤
        float want = G141Kit.yawOf(trav);
        float d = G141Kit.wrap(want - yaw);
        if (Math.abs(d) > 90) want = G141Kit.wrap(want + 180);
        float dd = G141Kit.wrap(want - yaw);
        yaw = G141Kit.wrap(yaw + Math.max(-1.5f, Math.min(1.5f, dd)));
        pitch = 0;

        if (age >= grindAt) {
            grindAt = age + 36;
            sound("grind", Sound.BLOCK_GRINDSTONE_USE, 0.5f, 64);
        }
        push();
        tosses();
        lobs.tick(this, this::land);
        truck();
    }

    /** 사람 자리를 벽 틀로: [옆 u(왼쪽 +), 나아가는 쪽 f, 높이 차]. */
    private double[] local(Location l) {
        double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
        double f = dx * trav.getX() + dz * trav.getZ();
        double u = dx * trav.getZ() - dz * trav.getX();
        return new double[] {u, f, l.getY() - (pos.getY() - HOVER)};
    }

    /** 나아가는 쪽 면에 닿은 사람을 함께 밀고, 초당 6. 눈을 뜨는 것도 여기서(앞 20 블록 안 사람). */
    private void push() {
        Player close = null;
        double best = 1e9;
        boolean dmg = age >= pushAt;
        boolean any = false;
        for (Player p : game.mobs.standing()) {
            double[] q = local(p.getLocation());
            if (Math.abs(q[0]) > HALF_W + 0.4 || q[2] < -1 || q[2] > HALF_H * 2 + 1.5) continue;
            if (q[1] > -HALF_D - 0.35 && q[1] < 20 && q[1] < best) {
                best = q[1];
                close = p;
            }
            if (q[1] < -HALF_D - 0.35 || q[1] > HALF_D + PUSH_DEPTH) continue;
            game.players.push(p, trav.clone().multiply(PUSH_V));
            any = true;
            if (dmg) hit(p, PUSH_DMG, "cause_g141");
        }
        if (dmg && any) pushAt = age + cd(PUSH_EVERY);
        if (close != target) mark(close);
    }

    /** 망루 셋이 던짐(벽 너머 사람 먼저). */
    private void tosses() {
        if (age < tossAt) return;
        tossAt = age + cd(TOSS_CD);
        List<Player> back = new ArrayList<>(), front = new ArrayList<>();
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (G141Kit.hdist(pos, l) > TOSS_RANGE) continue;
            double[] q = local(l);
            if (q[1] < -HALF_D || Math.abs(q[0]) > HALF_W) back.add(p);
            else front.add(p);
        }
        List<Player> pool = back.isEmpty() ? front : back;
        if (pool.isEmpty()) return;
        boolean thrown = false;
        for (int i = 0; i < TURRETS.length; i++) {
            Player p = pool.get(game.random.nextInt(pool.size()));
            Location l = p.getLocation();
            Vector from = G141Kit.at(this, TURRETS[i], TURRET_Y + 0.6, 0);
            Vector to = new Vector(l.getX() + (game.random.nextDouble() - 0.5) * 1.2, game.mobs.flow.floor(l.getX(), l.getZ()) + 0.3,
                l.getZ() + (game.random.nextDouble() - 0.5) * 1.2);
            int len = TOSS_LEN + (int) Math.round(G141Kit.hdist(from, to) * 0.25);
            if (lobs.toss(this, from, to, len, 4.5 + G141Kit.hdist(from, to) * 0.12)) thrown = true;
        }
        if (thrown) sound("toss", Sound.ENTITY_SNOWBALL_THROW, 0.6f, 32);
    }

    private void land(Vector at) {
        Location l = new Location(game.world, at.getX(), at.getY(), at.getZ());
        if (!game.director.silent()) kr.gave.Props.soundAround(l, "gabe.g141.splat", Sound.ENTITY_SLIME_SQUISH, 1f, 0.9f, 0.75f, 24, -1);
        if (game.shotFx.room(ShotFx.NORMAL)) game.shotFx.bloodHit(l.clone().add(0, 0.3, 0), new Vector(0, 1, 0), true, ShotFx.BLOOD_PINK);
        G141Kit.ring(this, at, LUMP_R, 10);
        for (Player p : G141Kit.within(this, at, LUMP_R + 0.3, 2.5)) hit(p, LUMP_DMG, "cause_g141_lump");
    }

    /** 끝 얼굴이 트럭 발자국을 스치면 초당 TRUCK_FRAC. */
    private void truck() {
        if (age % 20 != 0) return;
        Truck t = game.trip == null ? null : game.trip.truck();
        if (t == null) return;
        // 벽 선분(옆 -W..W)에서 발자국까지 가장 가까운 거리
        double best = 1e9;
        for (int i = -4; i <= 4; i++) {
            Vector p = G141Kit.at(this, i * HALF_W / 4, 0, 0);
            best = Math.min(best, Truck.dist(p.getX(), p.getZ()));
        }
        if (best <= HALF_D + 0.3) t.hitFrac(TRUCK_FRAC, this, "cause_g141");
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        for (int i = 0; i < 9; i++) {
            double u = -7 + i * 1.75;
            for (double y : new double[] {-0.95, 0.95}) {
                Vector w = G141Kit.at(this, u, y, 0);
                sph.add(new double[] {w.getX(), w.getY(), w.getZ(), 1.2});
            }
        }
        for (double u : TURRETS) {
            Vector w = G141Kit.at(this, u, TURRET_Y, 0);
            sph.add(new double[] {w.getX(), w.getY(), w.getZ(), 1.1});
        }
        for (int s = -1; s <= 1; s += 2) {
            Vector w = G141Kit.at(this, s * (HALF_W + 0.35), -0.2, 0);
            sph.add(new double[] {w.getX(), w.getY(), w.getZ(), 1.3});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return G141Kit.at(this, 0, TURRET_Y, 0);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        double u = 0, f = 0;
        if (d.at() != null) {
            double dx = d.at().getX() - pos.getX(), dz = d.at().getZ() - pos.getZ();
            f = dx * trav.getX() + dz * trav.getZ();
            u = dx * trav.getZ() - dz * trav.getX();
        }
        boolean end = Math.abs(u) >= END_U;
        double mul;
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            mul = end ? END_MUL : f < -0.2 ? BACK_MUL : FRONT_BLAST;
        } else {
            // 탄: 날아온 쪽으로 앞뒤를 가름(나아가는 쪽에서 오는 탄 = 앞면)
            boolean fromFront;
            if (d.dir() != null) fromFront = d.dir().getX() * trav.getX() + d.dir().getZ() * trav.getZ() < 0;
            else if (d.by() != null) {
                Location e = d.by().getEyeLocation();
                fromFront = (e.getX() - pos.getX()) * trav.getX() + (e.getZ() - pos.getZ()) * trav.getZ() > 0;
            } else fromFront = f >= 0;
            if (end) mul = END_MUL;
            else if (fromFront) {
                if (age - tinkAt >= 4) {
                    tinkAt = age;
                    sound("tink", Sound.BLOCK_STONE_HIT, 1.4f, 24);
                }
                return false;
            } else mul = BACK_MUL;
        }
        return super.damage(mul == 1 ? d : d.times(mul));
    }

    @Override
    public void die(Damage d) {
        lobs.clear();
        super.die(d);
    }

    @Override
    public void remove() {
        lobs.clear();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " trav=" + String.format("%.2f,%.2f", trav.getX(), trav.getZ()) + " off=" + Math.round(offset) + " rev=" + reversals
            + " lobs=" + lobs.list.size();
    }
}
