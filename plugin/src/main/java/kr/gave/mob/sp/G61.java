package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G61 설 짐승 게이브(1막, 돌격, 튼튼이). 2.6블록 탈 머리 뒤로 살 띠 천 몸통이 4블록 이어진 6블록 짐승. 천 밑 다리 넷(앞 쌍, 뒤 쌍)이 따로 논다.
 *
 * <p>머리를 좌우로 흔들고 턱을 덜컥이며 춤추듯 걷다(0.14) 12블록 안에서 머리를 낮추고 앞발로 땅을 두 번 긁은 뒤 곧게 돌진(0.4, 사람 너머 6블록
 * 미끄러짐): 길목의 사람 모두 28 과 4블록 날림. 곁의 사람은 턱으로 물어 흔들기 16. 큰 소리와 불빛(조명탄, 화염병 불, 폭발, 18블록 안 1초 14발
 * 넘는 일제 사격)을 12블록 안에서 만나면 갈기를 세우고 몸을 옆으로 돌려 3초 뒷걸음친다(8초에 한 번): 그동안 천 몸통 피해 두 배, 그 뒤 돌진은
 * 1.3배 빠르다. 천 밑 뒷다리를 잡으면 뒤가 주저앉아 돌진을 못 한다. 죽으면 천 속 작은 게이브 둘({@link G61Kid})이 튀어나와 덤빈다.
 *
 * <p>몸: 탈 머리(닻 gabe/g61: "", "charge", "scared") + 천 몸통 부품(gabe/g61_cloth) + 앞, 뒤 다리 쌍 부품(gabe/g61_legs: "", "scrape",
 * "broken"). 앞뒤 다리 박자가 가끔 어긋나면 천 뒤쪽이 옆으로 꼬인다(부품 자세). 소리 step, growl, clack, ram. 글귀 cause_g61, cause_g61_bite,
 * cause_g61_kid.
 *
 * <p>도감과 다른 점: 걸음 흔들림은 판 전체 horizon.shake 대신 8블록 안 사람에게만 화면 끄덕(Guns.kick). 겁먹기 쿨다운 8초와 뒷걸음 3초는 박자 배율
 * 없이 고정(사람 쪽 대처 창). 돌진이 트럭 발자국에 닿으면 트럭을 한 번 들이받는다(무는 값의 3배).
 */
public final class G61 extends Gabe {

    static final double HOVER = 1.9, SPEED = 0.14, CHARGE_V = 0.4, ENRAGE = 1.3;
    static final double CHARGE_R = 12, OVERSHOOT = 6, RAM = 16, RAM_PUSH = 0.95, RAM_UP = 0.5;
    static final double BITE = 9, BITE_R = 2.4;
    static final int WINDUP = 30, CHARGE_MAX = 70, TURN_MAX = 40, COOLDOWN = 50, BITE_CD = 40, STEP = 12;
    /** 겁: 반경, 뒷걸음 틱(고정), 다시 겁먹기까지(고정), 일제 사격 셈(반경, 틱, 발 수). */
    static final double SCARE_R = 12, VOLLEY_R = 18;
    static final int SCARE_T = 60, SCARE_CD = 160, VOLLEY_T = 20, VOLLEY_N = 14;
    /** 뒷다리 체력 몫, 겁먹은 동안 천 몸통 배율. */
    static final double REAR_FRAC = 0.12, CLOTH_MUL = 2.0;
    /** 맞는 구(몸 틀 x, y, z, r): 탈, 천 앞, 천 뒤, 뒷다리. 천 뒤와 뒷다리는 꼬임 각만큼 돈다(이음 z = JOINT). */
    static final double[][] SPH = {{0, 0.1, 0.1, 1.3}, {0, 0.05, -1.9, 1.15}, {0, 0.05, -3.6, 1.15}, {0, -1.25, -4.4, 0.55}};
    static final double JOINT = -0.65;
    static final int REAR = 3;

    enum Phase { APPROACH, WINDUP, CHARGE, SLIDE, TURN, SCARED }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, readyAt, biteAt, nextStep, nextClack, scareReady, twistUntil;
    private Player prey;
    private final Vector dashDir = new Vector(0, 0, 1), scareFrom = new Vector();
    private double dashGone, dashLen, slideV, rearHp = -1;
    private boolean enraged, rearBroken, truckHit;
    private float twist, twistAim;
    private final Set<UUID> rammed = new HashSet<>();
    private Body.Part cloth, legsF, legsR;
    private final List<double[]> sph = new ArrayList<>(4);
    private int sphAge = -1;

    public G61(Game game, Species sp) {
        super(game, sp);
        radius = 1.5;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        noMutate = true;
        ragKick = 0.4;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g61", 9f, 3.6f);
        body.viewRange(2.4f);
        cloth = body.attach("gabe/g61_cloth");
        legsF = body.attach("gabe/g61_legs");
        legsR = body.attach("gabe/g61_legs");
        legsF.pose(new Matrix4f().translate(0, -1.0f, -1.45f), 0);
        parts(0);
    }

    @Override
    protected void spawned() {
        int ph = game.random.nextInt(16);
        legsF.look.phase = ph;
        legsR.look.phase = ph + 8;
        nextStep = age + STEP;
    }

    /** 부품 자세: 천과 뒷다리는 이음(JOINT)에서 꼬임 각만큼 돌고, 뒷다리가 꺾였으면 천 뒤가 내려앉는다. */
    private void parts(int ticks) {
        float tw = (float) Math.toRadians(twist);
        Matrix4f c = new Matrix4f().translate(0, 0, (float) JOINT).rotateY(tw);
        if (rearBroken) c.rotateX((float) Math.toRadians(-9));
        cloth.pose(new Matrix4f(c).translate(0, 0.05f, -2.0f), ticks);
        legsR.pose(new Matrix4f(c).translate(0, rearBroken ? -1.25f : -1.0f, -3.75f), ticks);
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
        if (rearHp < 0) rearHp = maxHp * REAR_FRAC;
        if (prey != null && !standing(prey)) prey = null;
        if (phase != Phase.SCARED && age >= scareReady && every(5)) checkScare();
        switch (phase) {
            case APPROACH -> approach();
            case WINDUP -> windup();
            case CHARGE -> charge();
            case SLIDE -> slide();
            case TURN -> turn();
            case SCARED -> scared();
        }
        gait();
    }

    /** 다리 빠르기와 박자 어긋남, 천 꼬임. */
    private void gait() {
        double v = Math.hypot(vel.getX(), vel.getZ());
        boolean moving = v > 0.015;
        double rate = Math.max(0.5, Math.min(2.6, v / SPEED));
        for (Body.Part p : new Body.Part[] {legsF, legsR}) {
            p.look.freeze = !moving && phase != Phase.WINDUP;
            p.look.rate = rate;
        }
        // 가끔 앞뒤 박자가 어긋나 몸통이 꼬임
        if (moving && phase == Phase.APPROACH && age >= twistUntil && game.random.nextInt(120) == 0) {
            twistUntil = age + 40;
            twistAim = (game.random.nextBoolean() ? 1 : -1) * 14f;
            legsR.look.phase = legsF.look.phase + 3 + game.random.nextInt(3);
        }
        if (age >= twistUntil && twistAim != 0) {
            twistAim = 0;
            legsR.look.phase = legsF.look.phase + 8;
        }
        float want = twistAim + (float) Math.sin(age * 0.11) * (moving ? 4f : 0f);
        // 꼬임 자세는 4틱마다(부품 둘 자세 패킷을 아낌)
        if (age % 4 == 0 && Math.abs(want - twist) > 0.6f) {
            twist += (want - twist) * 0.6f;
            parts(4);
        }
        if (moving && age >= nextStep) {
            nextStep = age + Math.max(5, (int) Math.round(STEP / rate));
            step();
        }
    }

    private void step() {
        sound("step", Sound.ENTITY_RAVAGER_STEP, 0.6f, 48);
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz > 64) continue;
            Guns.kick(p, (game.random.nextFloat() - 0.5f) * 0.6f, -0.5f);
        }
    }

    /** 춤추듯 걸어옴: 머리를 좌우로 흔들고 턱 덜컥. 12블록 안이면 돌진 준비, 곁이면 물기. */
    private void approach() {
        state("");
        Player p = game.mobs.forcedFor(this);
        if (p == null) p = nearest(400);
        prey = p;
        follow(speed() * (rearBroken ? 0.7 : 1.0));
        double vl = Math.hypot(vel.getX(), vel.getZ());
        float travel = vl > 1e-6 ? (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ())) : yaw;
        float sway = (float) Math.sin((age + id) * 0.13) * 13f;
        if (p != null && dist(p) < 8) {
            Vector c = chest(p);
            travel = (float) Math.toDegrees(Math.atan2(-(c.getX() - pos.getX()), c.getZ() - pos.getZ()));
        }
        yaw = turn(yaw, travel + sway, 6f);
        pitch = 0;
        if (age >= nextClack) {
            sound("clack", Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.6f, 24);
            nextClack = age + 20 + game.random.nextInt(25);
        }
        if (p == null) return;
        double d = dist(p);
        mark(d < 24 ? p : null);
        if (bite(p)) return;
        if (!rearBroken && age >= readyAt && d <= CHARGE_R && d > 3 && sees(p)) {
            hold();
            state("charge");
            legsF.state("scrape");
            sound("growl", Sound.ENTITY_RAVAGER_ROAR, 0.8f, 32);
            phase(Phase.WINDUP);
        }
    }

    private static float turn(float from, float to, float max) {
        float d = ((to - from) % 360f + 540f) % 360f - 180f;
        return from + Math.max(-max, Math.min(max, d));
    }

    /** 턱으로 물어 흔들기(곁의 사람, 앞쪽). */
    private boolean bite(Player p) {
        if (age < biteAt) return false;
        Vector f = fwd();
        Vector mouth = pos.clone().add(f.clone().multiply(1.0)).add(new Vector(0, -0.9, 0));
        if (chest(p).distance(mouth) > BITE_R) return false;
        biteAt = age + cd(BITE_CD);
        hit(p, BITE, "cause_g61_bite");
        Vector side = new Vector(f.getZ(), 0, -f.getX()).multiply(game.random.nextBoolean() ? 1 : -1);
        knock(p, side, 0.35, 0.15);
        sound("clack", Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.6f, 24);
        return true;
    }

    private Vector fwd() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** 머리를 낮추고 앞발로 땅을 두 번 긁음. */
    private void windup() {
        hold();
        if (prey == null) {
            calm();
            return;
        }
        Vector c = chest(prey);
        yaw = turn(yaw, (float) Math.toDegrees(Math.atan2(-(c.getX() - pos.getX()), c.getZ() - pos.getZ())), 8f);
        pitch = 0;
        int t = inPhase(), len = cd(WINDUP);
        if (t == len / 4 || t == len * 3 / 4) {
            Vector foot = pos.clone().add(fwd().multiply(-1.2));
            dust(foot, 1.4);
            sound("step", Sound.ENTITY_RAVAGER_STEP, 1.2f, 48);
        }
        if (t >= len) {
            dashDir.copy(toward(prey));
            dashLen = dist(prey) + OVERSHOOT;
            dashGone = 0;
            rammed.clear();
            truckHit = false;
            legsF.state("");
            phase(Phase.CHARGE);
        }
    }

    private void dust(Vector at, double r) {
        if (!game.shotFx.room(ShotFx.EXTRA)) return;
        game.shotFx.spawn(new Location(game.world, at.getX(), game.mobs.flow.floor(at.getX(), at.getZ()) + 0.06, at.getZ()), "fx_flat", ShotFx.RING, 0,
            ShotFx.scale((float) (r * 2)), Display.Billboard.FIXED, false, 10, ShotFx.EXTRA);
    }

    /** 곧게 돌진: 길목의 사람 모두 들이받음, 사람 너머 6블록까지. */
    private void charge() {
        state("charge");
        double v = CHARGE_V * (enraged ? ENRAGE : 1.0) * speed() / SPEED;
        dash(dashDir, v);
        yaw = (float) Math.toDegrees(Math.atan2(-dashDir.getX(), dashDir.getZ()));
        pitch = 0;
        dashGone += v;
        Vector head = at(SPH[0]), front = at(SPH[1]);
        for (Player p : game.mobs.standing()) {
            if (rammed.contains(p.getUniqueId())) continue;
            Vector c = chest(p);
            if (c.distance(head) > SPH[0][3] + 0.55 && c.distance(front) > SPH[1][3] + 0.45) continue;
            rammed.add(p.getUniqueId());
            hit(p, RAM, "cause_g61");
            Vector away = c.clone().subtract(pos).setY(0);
            Vector push = dashDir.clone().multiply(0.8).add(away.lengthSquared() > 1e-6 ? away.normalize().multiply(0.4) : new Vector());
            knock(p, push, RAM_PUSH, RAM_UP);
            sound("ram", Sound.ENTITY_RAVAGER_ATTACK, 0.7f, 32);
        }
        // 트럭 발자국에 닿으면 한 번 들이받고 멈춤
        Truck t = Truck.get();
        if (t != null && !truckHit && Truck.dist(head.getX(), head.getZ()) < SPH[0][3] + 0.7) {
            truckHit = true;
            t.hit(t.biteDamage(sp) * 3, this, "cause_g61");
            sound("ram", Sound.ENTITY_RAVAGER_ATTACK, 0.7f, 32);
            endCharge(0.1);
            return;
        }
        if (dashGone >= dashLen || inPhase() >= CHARGE_MAX || wallAhead(1.6)) endCharge(Math.min(0.35, v));
    }

    private void endCharge(double v) {
        enraged = false;
        slideV = v;
        phase(Phase.SLIDE);
    }

    private void slide() {
        state("");
        dash(dashDir, slideV);
        slideV *= 0.84;
        if (slideV < 0.03 || wallAhead(1.4)) {
            hold();
            readyAt = age + cd(COOLDOWN);
            phase(Phase.TURN);
        }
    }

    /** 느리게 돌아섬. */
    private void turn() {
        state("");
        vel.multiply(0.6);
        Player p = prey != null ? prey : nearest(400);
        boolean done = true;
        if (p != null) {
            Vector c = chest(p);
            float want = (float) Math.toDegrees(Math.atan2(-(c.getX() - pos.getX()), c.getZ() - pos.getZ()));
            float y0 = yaw;
            yaw = turn(yaw, want, 5f);
            done = Math.abs(yaw - y0) < 0.5f;
            if (bite(p)) done = true;
        }
        if (done || inPhase() >= cd(TURN_MAX)) calm();
    }

    private void calm() {
        state("");
        legsF.state("");
        phase(Phase.APPROACH);
    }

    /** 큰 소리와 불빛: 조명탄, 화염병 불, 폭발(0.5초 안), 일제 사격(1초 14발) 가운데 가장 가까운 것. */
    private void checkScare() {
        Vector src = null;
        double bd = SCARE_R * SCARE_R;
        for (List<Vector> l : List.of(game.shop.supplies.flares(), game.shop.supplies.fires())) {
            for (Vector v : l) {
                double d = v.distanceSquared(pos);
                if (d < bd) {
                    bd = d;
                    src = v;
                }
            }
        }
        Vector blast = game.mobs.lastBlastNear(pos, SCARE_R, 10);
        if (blast != null && (src == null || blast.distanceSquared(pos) < bd)) src = blast;
        if (src == null && game.mobs.shotsNear(pos, VOLLEY_R, VOLLEY_T) >= VOLLEY_N) {
            Player p = nearest(VOLLEY_R);
            if (p != null) src = p.getLocation().toVector();
        }
        if (src == null) return;
        scareFrom.copy(src);
        scareReady = age + SCARE_CD;
        enraged = true;
        state("scared");
        legsF.state("");
        mark(null);
        sound("growl", Sound.ENTITY_RAVAGER_STUNNED, 1.2f, 32);
        // 몸을 옆으로 돌림(천 몸통이 겁준 쪽에 드러남)
        Vector to = scareFrom.clone().subtract(pos).setY(0);
        if (to.lengthSquared() > 1e-6) {
            float toward = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
            yaw = toward + (game.random.nextBoolean() ? 80f : -80f);
            body.pose(6);
        }
        phase(Phase.SCARED);
    }

    /** 갈기를 세우고 3초 뒷걸음(겁준 것에서 멀어짐). */
    private void scared() {
        state("scared");
        Vector away = pos.clone().subtract(scareFrom).setY(0);
        if (away.lengthSquared() < 1e-6) away = fwd().multiply(-1);
        away.normalize();
        vel.copy(away.multiply(0.09));
        if (inPhase() >= SCARE_T) {
            readyAt = Math.min(readyAt, age + 10);
            calm();
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    /** 몸 틀 구 → 세계(천 뒤와 뒷다리는 꼬임 각만큼 이음에서 돎). */
    private Vector at(double[] s) {
        double x = s[0], z = s[2];
        if (z < JOINT - 1.5) {
            double r = Math.toRadians(twist), dz = z - JOINT;
            double nx = x * Math.cos(r) + dz * Math.sin(r), nz = -x * Math.sin(r) + dz * Math.cos(r);
            x = nx;
            z = JOINT + nz;
        }
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
        return new Vector(pos.getX() + fz * x + fx * z, pos.getY() + s[1], pos.getZ() - fx * x + fz * z);
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        for (int i = 0; i < SPH.length; i++) {
            if (i == REAR && rearBroken) continue;
            Vector w = at(SPH[i]);
            sph.add(new double[] {w.getX(), w.getY(), w.getZ(), SPH[i][3]});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return at(SPH[0]);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (rearHp < 0) rearHp = maxHp * REAR_FRAC;
        int idx = 0;
        if (d.at() != null) {
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < SPH.length; i++) {
                if (i == REAR && rearBroken) continue;
                double dd = d.at().distance(at(SPH[i])) - SPH[i][3];
                if (dd < bd - (i == REAR ? -0.05 : 0)) {
                    bd = dd;
                    idx = i;
                }
            }
        }
        Damage dd = d;
        if ((idx == 1 || idx == 2) && phase == Phase.SCARED) dd = d.times(CLOTH_MUL);
        if (idx == REAR && !rearBroken) {
            rearHp -= dd.amount();
            if (rearHp <= 0) collapse();
        }
        return super.damage(dd);
    }

    /** 뒷다리가 꺾임: 뒤가 주저앉아 돌진을 못 함. */
    private void collapse() {
        rearBroken = true;
        legsR.state("broken");
        legsR.look.freeze = true;
        parts(6);
        sound("growl", Sound.ENTITY_RAVAGER_HURT, 0.8f, 32);
        if (phase == Phase.WINDUP || phase == Phase.CHARGE) {
            hold();
            calm();
        }
    }

    @Override
    public void die(Damage d) {
        if (!dead) kids();
        super.die(d);
    }

    /** 천 속 작은 게이브 둘이 튀어나옴(보상 없음). */
    private void kids() {
        for (int i = 0; i < 2; i++) {
            if (game.mobs.budgetUsed() + 1 > Mobs.BUDGET) return;
            Vector at = at(new double[] {i == 0 ? 0.7 : -0.7, -1.0, i == 0 ? -1.6 : -3.6, 0});
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + G61Kid.HOVER);
            G61Kid k = new G61Kid(game, sp);
            double ratio = Math.max(0.5, maxHp / Math.max(1, sp.hp));
            k.maxHp = k.hp = G61Kid.HP * ratio;
            k.noReward = true;
            k.reward = 0;
            k.yaw = yaw + (i == 0 ? 70 : -70);
            if (game.mobs.place(k, at) != null) {
                Vector out = at.clone().subtract(pos).setY(0);
                if (out.lengthSquared() > 1e-6) k.vel.copy(out.normalize().multiply(0.3));
            }
        }
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (rearBroken ? " rear=broken" : " rear=" + Math.round(rearHp)) + (enraged ? " enraged" : "")
            + " twist=" + Math.round(twist);
    }
}
