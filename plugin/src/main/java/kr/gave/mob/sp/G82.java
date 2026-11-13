package kr.gave.mob.sp;

import java.util.List;

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

/**
 * G82 발자국 게이브(떼, 함정). 몸은 안 보이고 점묘 다리와 땅에 찍히는 발자국(2초 남음, 갓 찍힌 것은 젖어 있음)만 보인다. 한 사람을 정해 그 사람
 * 등 뒤 3블록으로 돌아 들어가 발자국을 찍으며 따라오다가, 곧장 그 사람 등으로 걸어 들어간다. 마지막 2.5블록에서 그 사람이 진행선에서 1.6블록 넘게
 * 비켜 서 있으면 그대로 곧게 지나쳐 가며 드러나고(2초 동안 받는 피해 세 배 = 한 발), 안 비키면 등에 닿는 순간 드러나며 덮친다(15 피해, 앞으로
 * 넘어짐 0.8초 = 묶임 16틱). 어느 쪽이든 6블록 가서 다시 숨어 다른 사람을 고른다.
 *
 * <p>신호: 발자국마다 젖은 '찰박'(따라오는 발소리, 10블록). 숨은 동안도 맞는 구는 있다(작게): 맨 앞 발자국 위 허공을 쏴도 맞는다.
 *
 * <p>팩 계약(tools/gabe/sp/g82.py): 모델 gave:gabe/g82 상태 "ghost", ""(드러남), 발자국 gave:gabe/g82_print 상태 ""(젖음), "dry".
 * 소리 step(10), 원인 글귀 cause_g82.
 */
public final class G82 extends Gabe {

    static final double STALK_V = 0.3, FAR_MUL = 0.8;
    /** 등 뒤 자리 거리, 그 자리에 이만큼 닿으면 들어감, 진행선에서 비켜선 거리, 비킴을 보는 마지막 거리. */
    static final double BACK = 3.2, BACK_OK = 1.0, SIDE = 1.6, LAST = 2.5;
    static final double POUNCE_DMG = 15, PASS_DIST = 6, STRIDE = 0.9, PRINT_SIDE = 0.14;
    static final int TRIP = 16, COMMIT_MAX = 50, EXPOSE = 40, PRINTS = 4, PRINT_LIFE = 40, WET = 14;
    static final double GHOST_R = 0.3, SHOWN_R = 0.38, HOVER = 1.4, PRINT_SEEN = 36;
    static final int PACK = 2;

    enum Phase { STALK, COMMIT, PASS }

    private Phase phase = Phase.STALK;
    private int phaseAt, exposeUntil = -1;
    private Player prey, last;
    private final Vector line = new Vector(0, 0, 1), passFrom = new Vector(), stepFrom = new Vector();
    private boolean packed, leftFoot, stepped;
    private final ItemDisplay[] prints = new ItemDisplay[PRINTS];
    private final int[] printAge = new int[PRINTS];
    private int printN;

    public G82(Game game, Species sp) {
        super(game, sp);
        radius = GHOST_R;
        hover = HOVER;
        baseSpeed = STALK_V;
        state("ghost");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g82", 0.8f, 1.8f);
    }

    @Override
    public void tick() {
        if (!packed) {
            packed = true;
            G82Pack.fill(this, PACK - 1, 3.0);
            stepFrom.copy(pos);
        }
        dryPrints();
        switch (phase) {
            case STALK -> stalk();
            case COMMIT -> commit();
            case PASS -> pass();
        }
        if (exposeUntil >= 0 && age >= exposeUntil) {
            // 드러난 2초가 끝남: 다시 숨음(지나치는 길 위면 마저 감)
            exposeUntil = -1;
            state("ghost");
            radius = GHOST_R;
        }
        if (state.isEmpty()) {
            // 드러난 동안은 발자국을 안 찍음(다리가 보임)
            stepFrom.copy(pos);
            return;
        }
        double dx = pos.getX() - stepFrom.getX(), dz = pos.getZ() - stepFrom.getZ();
        if (dx * dx + dz * dz >= STRIDE * STRIDE) {
            Vector dir = new Vector(dx, 0, dz).normalize();
            stepFrom.copy(pos);
            step(dir);
        }
    }

    private void go(Phase p) {
        phase = p;
        phaseAt = age;
    }

    /** 사람 고르기: 지난번 사람이 아닌 가장 가까운 서 있는 사람(혼자면 그 사람). */
    private Player pick() {
        Player best = null, any = null;
        double bd = Double.MAX_VALUE, ad = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            double d = dist(p);
            if (d < ad) {
                ad = d;
                any = p;
            }
            if (p != last && d < bd) {
                bd = d;
                best = p;
            }
        }
        return best != null ? best : any;
    }

    private void stalk() {
        if (!standing(prey) || every(40)) {
            Player n = pick();
            if (n != prey) mark(null);
            prey = n;
        }
        if (prey == null) {
            follow(speed() * FAR_MUL);
            face(pos.clone().add(vel));
            return;
        }
        double d = dist(prey);
        if (d > 16) {
            follow(speed() * FAR_MUL);
            face(pos.clone().add(vel));
            return;
        }
        if (target != prey) mark(prey);
        // 등 뒤 자리: 그 사람이 보는 쪽의 반대로 BACK 블록
        Location l = prey.getLocation();
        Vector look = l.getDirection().setY(0);
        if (look.lengthSquared() < 1e-6) look = new Vector(0, 0, 1);
        look.normalize();
        Vector spot = new Vector(l.getX(), pos.getY(), l.getZ()).subtract(look.clone().multiply(BACK));
        double sx = spot.getX() - pos.getX(), sz = spot.getZ() - pos.getZ();
        if (sx * sx + sz * sz <= BACK_OK * BACK_OK) {
            line.copy(toward(prey));
            go(Phase.COMMIT);
            return;
        }
        // 그 사람 앞을 가로지르지 않게: 사람 곁 2블록 안이면 옆으로 돌아감
        if (d < 2.6) {
            Vector side = new Vector(look.getZ(), 0, -look.getX());
            if (side.dot(pos.clone().subtract(l.toVector())) < 0) side.multiply(-1);
            spot.add(side.multiply(1.8));
        }
        seek(spot, speed());
        face(pos.clone().add(vel));
    }

    private void commit() {
        if (!standing(prey)) {
            go(Phase.STALK);
            return;
        }
        Vector rel = chest(prey).subtract(pos).setY(0);
        double along = rel.dot(line), lateral = Math.abs(rel.getX() * line.getZ() - rel.getZ() * line.getX());
        // 마지막 2.5블록 전까지는 그 사람 쪽으로 고쳐 걷는다
        if (along > LAST) line.copy(toward(prey));
        dash(line, speed() * 1.05);
        face(pos.clone().add(line));
        if (touches(prey, 0.15)) {
            pounce(prey);
            return;
        }
        if (along <= LAST && lateral > SIDE || along < -0.4) {
            passBy();
            return;
        }
        if (age - phaseAt > cd(COMMIT_MAX)) go(Phase.STALK);
    }

    /** 덮침: 등에 닿는 순간 드러나며 앞으로 넘어뜨림. */
    private void pounce(Player p) {
        hit(p, POUNCE_DMG, "cause_g82");
        game.players.root(p, TRIP, "g82_trip");
        knock(p, line, 0.35, 0.08);
        sound("step", Sound.ENTITY_PLAYER_SPLASH, 12);
        passBy();
        body.squash(1.3f, 0.75f, 1.3f, 3);
    }

    /** 조명탄, 정화가 비추면 2초 드러남(하던 일은 그대로). */
    @Override
    public void reveal() {
        super.reveal();
        state("");
        radius = SHOWN_R;
        exposeUntil = age + EXPOSE;
    }

    /** 드러나며 그대로 곧게 6블록 지나감. */
    private void passBy() {
        state("");
        radius = SHOWN_R;
        exposeUntil = age + EXPOSE;
        passFrom.copy(pos);
        last = prey;
        mark(null);
        go(Phase.PASS);
    }

    private void pass() {
        dash(line, speed() * 0.85);
        face(pos.clone().add(line));
        double dx = pos.getX() - passFrom.getX(), dz = pos.getZ() - passFrom.getZ();
        if (dx * dx + dz * dz >= PASS_DIST * PASS_DIST || wallAhead(0.8) || age - phaseAt > 60) {
            prey = null;
            go(Phase.STALK);
        }
    }

    /** 발자국 하나(효과 디스플레이, 2초). 아무도 가까이 없으면 안 찍는다. */
    private void step(Vector dir) {
        boolean seen = false;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= PRINT_SEEN * PRINT_SEEN) {
                seen = true;
                break;
            }
        }
        if (!seen) return;
        leftFoot = !leftFoot;
        double s = leftFoot ? PRINT_SIDE : -PRINT_SIDE;
        double fx = pos.getX() + dir.getZ() * s, fz = pos.getZ() - dir.getX() * s;
        double fl = game.mobs.flow.floor(fx, fz);
        float yw = (float) Math.atan2(-dir.getX(), dir.getZ());
        Matrix4f m = new Matrix4f().rotateY(-yw).rotateY((float) Math.PI);
        ItemDisplay d = game.shotFx.spawn(new Location(game.world, fx, fl + 0.03, fz), "fx_quad", 0, 0, m, Display.Billboard.FIXED, false,
                PRINT_LIFE, ShotFx.NORMAL);
        if (d != null) {
            d.setItemStack(Body.item("gabe/g82_print", "", new LookState(), game.worldTime()));
            d.setBrightness(Body.LIGHT);
            int i = printN++ % PRINTS;
            if (prints[i] != null && prints[i].isValid()) prints[i].remove();
            prints[i] = d;
            printAge[i] = age;
        }
        sound("step", Sound.ENTITY_PLAYER_SPLASH, 0.6f, 10);
    }

    /** 갓 찍힌 발자국은 WET 틱 뒤 마른다(맨 앞 것만 젖어 보임). */
    private void dryPrints() {
        for (int i = 0; i < PRINTS; i++) {
            ItemDisplay d = prints[i];
            if (d == null) continue;
            if (!d.isValid()) {
                prints[i] = null;
                continue;
            }
            if (age - printAge[i] == WET) d.setItemStack(Body.item("gabe/g82_print", "dry", new LookState(), game.worldTime()));
        }
    }

    @Override
    public boolean damage(Damage d) {
        // 지나치며 드러난 2초는 한 발
        return super.damage(exposed() ? d.times(3) : d);
    }

    private boolean exposed() {
        return exposeUntil >= 0 && age < exposeUntil;
    }

    @Override
    public void die(Damage d) {
        state("");
        super.die(d);
    }

    @Override
    public void remove() {
        super.remove();
        for (int i = 0; i < PRINTS; i++) {
            if (prints[i] != null && prints[i].isValid()) prints[i].remove();
            prints[i] = null;
        }
    }

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), radius});
    }

    @Override
    public String listenSound() {
        return "gave:gabe.g82.step";
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + (exposed() ? " shown" : "");
    }
}
