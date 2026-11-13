package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * G100 가마 게이브(2막, 방패, 방해). 나타나면 땅에 길이 먼저 그려진다: 나온 자리에서 6블록 안 사람이 가장 많은 곳을 지나 반대편 고리까지 폭 4블록 흰 줄.
 * 4초 뒤 가마꾼 넷(부품)이 멘 가마가 그 길로만 박자 맞춰 종종걸음으로 지나간다(0.2블록/틱, 멈추지 않고 꺾지 않음). 길 위에 있으면 가마꾼에게 밟혀 14 와
 * 길 밖으로 튕김. 가마가 3블록 곁을 지날 때 길 위 한 사람을 발 너머로 끌어들여 태운다(타고 가는 동안 초당 7, 고리 끝까지 가면 갇힘). 끝에 닿으면
 * 그 자리에서 새 길을 그리고 다시 온다. 가마 벽은 탄을 80% 막는다. 가마꾼 넷이 약점(체력 40씩): 둘을 잡으면 기울어 느려지고, 넷을 다 잡으면 그
 * 자리에 내려앉아 태운 사람을 뱉고 벽이 열린다(그 뒤로는 사람 쪽으로 천천히 기어 옴). 길 위 화염병은 가마꾼을 태운다(큰 몸 불 판정).
 *
 * <p>팩 계약(tools/gabe/sp/g100.py): gabe/g100(shrink 0.42, 상태 "", "grab", "down"), 가마꾼 gabe/g100_bearer, 길 gabe/g100_lane(폭 1 길이 1, 뒤 끝에서
 * +Z). 소리 chant, grab, drop. 글귀 cause_g100, cause_g100_step.
 *
 * <p>도감과 다른 점: 내려앉은 뒤 가만히 있지 않고 사람 쪽으로 기어 온다(외곽에서 웨이브 끝을 막지 않게). 길은 한 장의 늘인 판(연출 예산 하나).
 */
public final class G100 extends Gabe {

    static final double HOVER = 2.1, DOWN_HOVER = 1.25, SPEED = 0.2, TILT_MUL = 0.65, CRAWL = 0.04, LANE_W = 4, R_END = 84;
    static final double STEP_DMG = 14, RIDE_DMG = 3.5, BEARER_HP = 40, WALL_MUL = 0.2, GRAB_LAT = 2.2, GRAB_ALONG = 1.6;
    static final int TELL = 80, GRAB_T = 16, ESCAPE = 6, STEP_CD = 20;
    static final double[][] BEARER = {{1.45, -0.95, 1.95}, {-1.45, -0.95, 1.95}, {1.45, -0.95, -1.95}, {-1.45, -0.95, -1.95}};

    enum Phase { PLAN, TRAVEL, DOWN }

    private Phase phase = Phase.PLAN;
    private int phaseAt, grabAt = -100, chantAt, lanes, grabs, traps;
    private final Vector from = new Vector(), dir = new Vector(0, 0, 1);
    private double len;
    private ItemDisplay lane;
    private Player rider;
    private final Body.Part[] bearer = new Body.Part[4];
    private final double[] bhp = {-1, -1, -1, -1};
    private boolean bhpInit;
    private int deadBearers;
    private boolean planned;
    private final Map<UUID, Integer> stepAt = new HashMap<>();
    private final List<double[]> sph = new ArrayList<>(10);
    private int sphAge = -1;

    public G100(Game game, Species sp) {
        super(game, sp);
        radius = 1.6;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragKick = 0.5;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g100", 6f, 5f);
        body.viewRange(2.5f);
        for (int i = 0; i < 4; i++) {
            bearer[i] = body.attach("gabe/g100_bearer");
            if (bearer[i] != null) bearer[i].pose(new Matrix4f().translate((float) BEARER[i][0], (float) BEARER[i][1], (float) BEARER[i][2]), 0);
        }
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        plan();
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 길

    /** 지금 자리에서 6블록 안 사람이 가장 많은 곳을 지나 반대편 고리까지. 흰 줄을 깔고 4초 기다림. */
    private void plan() {
        planned = true;
        List<Player> ps = game.mobs.standing();
        Vector best = null;
        int bn = -1;
        for (Player p : ps) {
            Location l = p.getLocation();
            int n = 0;
            for (Player q : ps) if (q.getLocation().distanceSquared(l) <= 36) n++;
            if (n > bn || n == bn && best != null && G129Kit.flat(l, pos) < G129Kit.flat(best, pos)) {
                bn = n;
                best = l.toVector();
            }
        }
        if (best == null) best = new Vector(Plaza.CX + 0.5, pos.getY(), Plaza.CZ + 0.5);
        Vector d = best.clone().subtract(pos).setY(0);
        if (d.lengthSquared() < 4) d = new Vector(Plaza.CX + 0.5 - pos.getX(), 0, Plaza.CZ + 0.5 - pos.getZ());
        if (d.lengthSquared() < 1e-3) d = G129Kit.fwd(this);
        dir.copy(d.normalize());
        from.copy(G129Kit.ground(this, pos));
        // 반대편 고리(R_END)와 만나는 먼 쪽
        double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5;
        double ox = pos.getX() - cx, oz = pos.getZ() - cz;
        double b = ox * dir.getX() + oz * dir.getZ(), c = ox * ox + oz * oz - R_END * R_END;
        double disc = b * b - c;
        len = disc > 0 ? -b + Math.sqrt(disc) : best.distance(pos) + 40;
        len = Math.max(20, Math.min(180, len));
        yaw = G129Kit.yawOf(dir);
        drawLane();
        lanes++;
        phase(Phase.PLAN);
    }

    private void drawLane() {
        clearLane();
        int life = TELL + (int) Math.ceil(len / (SPEED * 0.6)) + 40;
        float yawR = (float) Math.atan2(dir.getX(), dir.getZ());
        Matrix4f m = new Matrix4f().rotateY(yawR).scale((float) LANE_W, 1, (float) len).rotateY((float) Math.PI);
        ItemDisplay d = game.shotFx.spawn(new Location(game.world, from.getX(), from.getY() + 0.03, from.getZ()), "fx_flat", 0, 0, m,
            Display.Billboard.FIXED, false, life, ShotFx.KEY);
        if (d == null) return;
        LookState ls = new LookState();
        ls.freeze = true;
        d.setItemStack(Body.item("gabe/g100_lane", "", ls, game.worldTime()));
        d.setBrightness(Body.LIGHT);
        d.setViewRange(3f);
        d.setDisplayWidth((float) (len * 2));
        d.setDisplayHeight(4f);
        lane = d;
    }

    private void clearLane() {
        if (lane != null && lane.isValid()) lane.remove();
        lane = null;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!planned && phase != Phase.DOWN) plan();
        if (!bhpInit) {
            for (int i = 0; i < 4; i++) bhp[i] = BEARER_HP * Math.max(0.5, maxHp / Math.max(1, sp.hp));
            bhpInit = true;
        }
        Player near = nearest(400);
        mark(near != null && dist(near) < 40 ? near : null);
        switch (phase) {
            case PLAN -> {
                hold();
                freezeBearers(true);
                if (inPhase() >= cd(TELL)) {
                    freezeBearers(false);
                    phase(Phase.TRAVEL);
                }
            }
            case TRAVEL -> travel();
            case DOWN -> crawl(near);
        }
        ride();
    }

    private void freezeBearers(boolean f) {
        for (Body.Part b : bearer) if (b != null) b.look.freeze = f;
    }

    private void travel() {
        double v = SPEED * game.director.speedMul() * (deadBearers >= 2 ? TILT_MUL : 1);
        vel.setX(dir.getX() * v).setZ(dir.getZ() * v);
        yaw = G129Kit.yawOf(dir);
        if (age - chantAt >= 30) {
            sound("chant", Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 40);
            chantAt = age;
        }
        trample();
        if (rider == null && age - grabAt > GRAB_T + 20) tryGrab();
        if (age - grabAt == GRAB_T) state("");
        double along = (pos.getX() - from.getX()) * dir.getX() + (pos.getZ() - from.getZ()) * dir.getZ();
        if (along >= len || inPhase() > 2400) arrive();
    }

    /** 길 끝: 태운 사람은 갇히고, 그 자리에서 새 길. */
    private void arrive() {
        Player p = rider;
        if (p != null && game.players.grabbedBy(p) == this) {
            game.players.trap(p, this);
            traps++;
        }
        rider = null;
        state("");
        plan();
    }

    /** 가마꾼 발밑(길 위): 14 와 길 밖으로 튕김. */
    private void trample() {
        Vector l = G129Kit.left(this);
        for (int i = 0; i < 4; i++) {
            if (bhp[i] <= 0) continue;
            Vector b = G129Kit.at(this, BEARER[i][0], BEARER[i][1], BEARER[i][2]);
            for (Player q : game.mobs.standing()) {
                if (q == rider) continue;
                Location ql = q.getLocation();
                if (G129Kit.flat(ql, b) > 1.1 || Math.abs(ql.getY() - (pos.getY() - HOVER)) > 2.5) continue;
                Integer last = stepAt.get(q.getUniqueId());
                if (last != null && age - last < STEP_CD) continue;
                stepAt.put(q.getUniqueId(), age);
                hit(q, STEP_DMG, "cause_g100_step");
                double lat = (ql.getX() - pos.getX()) * l.getX() + (ql.getZ() - pos.getZ()) * l.getZ();
                knock(q, l.clone().multiply(lat >= 0 ? 1 : -1), 1.1, 0.3);
            }
        }
    }

    /** 3블록 곁, 길 위 한 사람을 끌어들임. */
    private void tryGrab() {
        Vector l = G129Kit.left(this), f = G129Kit.fwd(this);
        for (Player q : game.mobs.standing()) {
            Location ql = q.getLocation();
            double rx = ql.getX() - pos.getX(), rz = ql.getZ() - pos.getZ();
            double lat = Math.abs(rx * l.getX() + rz * l.getZ()), along = rx * f.getX() + rz * f.getZ();
            if (lat > GRAB_LAT || Math.abs(along) > GRAB_ALONG) continue;
            if (!game.players.grab(q, this, ESCAPE)) continue;
            PState s = game.players.get(q);
            s.grabPin = true;
            s.grabDetach = true;
            rider = q;
            grabAt = age;
            grabs++;
            state("grab");
            sound("grab", Sound.ENTITY_SLIME_SQUISH, 0.6f, 24);
            return;
        }
    }

    /** 태운 사람: 가마 안 좌석, 초당 7. 풀려났으면(탈출, 떼기) 비움. */
    private void ride() {
        Player p = rider;
        if (p == null) return;
        if (!p.isOnline() || game.players.grabbedBy(p) != this) {
            rider = null;
            return;
        }
        game.players.carry(p, pos.clone().add(new Vector(0, -0.45, 0)));
        if ((age - grabAt) % 10 == 9) hit(p, RIDE_DMG, "cause_g100");
    }

    // ------------------------------------------------------------------ 내려앉음

    private void sitDown() {
        phase(Phase.DOWN);
        clearLane();
        state("down");
        hover = DOWN_HOVER;
        body.roll = 0;
        sound("drop", Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.6f, 32);
        G129Kit.ring(this, G129Kit.ground(this, pos), 2.5, 12);
        Player p = rider;
        rider = null;
        if (p != null && game.players.grabbedBy(p) == this) {
            game.players.release(p);
            knock(p, G129Kit.fwd(this), 0.6, 0.4);
        }
    }

    private void crawl(Player p) {
        if (p == null) {
            follow(CRAWL);
            return;
        }
        follow(CRAWL * game.director.speedMul());
        G129Kit.faceFlat(this, p.getLocation().toVector());
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(G129Kit.at(this, 0, 0.1, 0), 1.55);                                  // 0 가마
        for (int i = 0; i < 4; i++) {                                           // 1..4 가마꾼 얼굴, 5..8 다리
            boolean alive = !bhpInit || bhp[i] > 0;
            add(G129Kit.at(this, BEARER[i][0], BEARER[i][1], BEARER[i][2]), alive ? 0.5 : 0.01);
        }
        for (int i = 0; i < 4; i++) {
            boolean alive = !bhpInit || bhp[i] > 0;
            add(G129Kit.at(this, BEARER[i][0], BEARER[i][1] - 0.75, BEARER[i][2]), alive ? 0.35 : 0.01);
        }
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        int best = 0;
        if (d.at() != null) {
            List<double[]> s = spheres();
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < s.size(); i++) {
                double[] c = s.get(i);
                if (c[3] < 0.05) continue;
                double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
        }
        if (best >= 1) {
            int i = (best - 1) % 4;
            if (bhp[i] > 0) {
                bhp[i] -= d.amount();
                boolean ok = super.damage(d);
                if (bhp[i] <= 0 && !dead) killBearer(i);
                return ok;
            }
        }
        return super.damage(phase == Phase.DOWN ? d : d.times(WALL_MUL));
    }

    private void killBearer(int i) {
        Vector b = G129Kit.at(this, BEARER[i][0], BEARER[i][1], BEARER[i][2]);
        if (bearer[i] != null) bearer[i].remove();
        bearer[i] = null;
        deadBearers++;
        if (game.shotFx.room(ShotFx.EXTRA)) game.shotFx.bloodHit(new Location(game.world, b.getX(), b.getY(), b.getZ()), new Vector(0, 1, 0), true, ShotFx.BLOOD_PINK);
        sound("drop", Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.9f, 32);
        if (deadBearers >= 4) {
            sitDown();
            return;
        }
        // 기울기: 왼쪽(+X: 0, 2)과 오른쪽(1, 3)에서 죽은 수 차이, 앞(0, 1)과 뒤(2, 3) 차이
        int left = (bhp[0] <= 0 ? 1 : 0) + (bhp[2] <= 0 ? 1 : 0), right = (bhp[1] <= 0 ? 1 : 0) + (bhp[3] <= 0 ? 1 : 0);
        if (deadBearers >= 2) body.roll = (right - left) * 9f + (left == right ? 6f : 0f);
    }

    @Override
    public void die(Damage d) {
        clearLane();
        rider = null;
        super.die(d);
    }

    @Override
    public void remove() {
        clearLane();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " lanes=" + lanes + " len=" + Math.round(len) + " rider=" + (rider == null ? "-" : rider.getName())
            + " grabs=" + grabs + " traps=" + traps + " bearers=" + (4 - deadBearers) + " bhp=" + Math.round(bhp[0]) + "/" + Math.round(bhp[1]) + "/"
            + Math.round(bhp[2]) + "/" + Math.round(bhp[3]);
    }
}
