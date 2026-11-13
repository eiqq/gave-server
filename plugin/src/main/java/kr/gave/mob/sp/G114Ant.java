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

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G114 개미 무덤이 쏟는 개미 줄 하나(얼굴 개미 넷이 한 줄인 디스플레이 하나). 종은 무덤과 같은 G114(보상 0, 판 규칙 빠짐).
 *
 * <p>가장 가까운 사람에게 곧장 가서 물고(4) 다리에 붙어 0.5초마다 2를 2초 동안 준 뒤 떨어져 죽는다(보상 0, 사체는 남음). 한 사람에게 두 줄 넘게
 * 붙으면 느려짐 30%. 2026-11-12 시험: 떨어진 줄이 다시 붙으면 여섯 줄이 혼자인 사람을 5초 만에 쓰러뜨려서 한 번만 붙게 했다.
 * 트럭 줄(셋에 하나)은 트럭이 서 있으면 트럭에 붙어 문다(트럭 무는 값의 40%, 1초마다). 30초 뒤 흙으로 꺼진다(웨이브 끝을 오래 막지 않게).
 * 폭발과 불에는 한꺼번에 죽는다(체력 8).
 */
final class G114Ant extends Gabe {

    static final double HOVER = 0.16, SPEED = 0.2, BITE = 4, GNAW = 2, HP = 8, SLOW = 0.3;
    static final int CLING = 40, GNAW_CD = 10, AGAIN = 30, LIFE = 600, TRUCK_CD = 20;
    static final double TRUCK_K = 0.4;

    /** 사람마다 이번 틱, 지난 틱에 붙은 줄 수: {틱, 이번, 지난}. */
    private static final Map<UUID, long[]> CLUNG = new HashMap<>();

    private final G114 mound;
    private final boolean truckLine;
    private Player clingTo;
    private int clingAt = -1, readyAt, gnawAt, truckAt;
    private double clingA;

    G114Ant(Game game, Species sp, G114 mound, boolean truckLine) {
        super(game, sp);
        this.mound = mound;
        this.truckLine = truckLine;
        double k = Math.max(1.0, mound.maxHp / Math.max(1.0, sp.hp));
        maxHp = hp = HP * k;
        reward = 0;
        noReward = true;
        noMutate = true;
        noBite = true;
        radius = 0.32;
        hover = HOVER;
        baseSpeed = SPEED;
        ragKick = 0.6;
        ragRods = 0;
        clingA = game.random.nextDouble() * Math.PI * 2;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g114_ant", 1.4f, 0.4f);
    }

    @Override
    public void tick() {
        if (age >= LIFE && clingTo == null) {
            remove();
            return;
        }
        if (truckLine && truck()) return;
        if (clingTo != null) {
            cling();
            return;
        }
        Player p = nearest(400);
        if (p != target && (age % 10 == 0 || target == null || !standing(target))) mark(p);
        p = target;
        if (p == null || !standing(p)) {
            follow(speed());
            return;
        }
        follow(speed());
        if (age >= readyAt && touches(p, 0.35)) {
            hit(p, BITE, "cause_g114");
            sound("bite", Sound.ENTITY_SILVERFISH_HURT, 1.4f, 12);
            clingTo = p;
            clingAt = age;
            gnawAt = age + cd(GNAW_CD);
            noPush = true;
        }
    }

    /** 다리에 붙어 갉음. */
    private void cling() {
        Player p = clingTo;
        if (!standing(p) || age - clingAt >= CLING) {
            letGo();
            return;
        }
        Location l = p.getLocation();
        clingA += 0.05;
        double tx = l.getX() + Math.cos(clingA) * 0.45, tz = l.getZ() + Math.sin(clingA) * 0.45;
        double dx = tx - pos.getX(), dz = tz - pos.getZ(), d = Math.hypot(dx, dz);
        double v = Math.min(0.9, d);
        if (d > 1e-4) vel.setX(dx / d * v).setY(0).setZ(dz / d * v);
        else hold();
        face(chest(p));
        long[] c = CLUNG.computeIfAbsent(p.getUniqueId(), k -> new long[3]);
        long now = now();
        if (c[0] != now) {
            c[2] = c[0] == now - 1 ? c[1] : 0;
            c[0] = now;
            c[1] = 0;
        }
        c[1]++;
        if (c[2] > 2) game.players.slow(p, SLOW, 12, "g114_ants");
        if (age >= gnawAt) {
            gnawAt = age + cd(GNAW_CD);
            hit(p, GNAW, "cause_g114");
        }
    }

    /** 2초 갉고 나면 줄이 떨어져 죽는다(한 줄이 줄 수 있는 피해는 물기 + 갉기 2초까지: 무덤 하나가 쏟는 열다섯 줄이 판의 피해 예산). */
    private void letGo() {
        Player p = clingTo;
        clingTo = null;
        noPush = false;
        readyAt = age + cd(AGAIN);
        if (p == null) return;
        noReward = true;
        reward = 0;
        die(new kr.gave.mob.Damage(Math.max(1, hp), kr.gave.mob.DamageType.OTHER, null, pos.clone(), null, "spent", 0));
    }

    /** 트럭 줄: 트럭이 서 있으면 붙어 문다(없으면 false: 사람에게 감). */
    private boolean truck() {
        Truck t = game.trip == null ? null : game.trip.truck();
        if (t == null || !t.alive()) return false;
        double d = Truck.dist(pos.getX(), pos.getZ());
        if (d > 0.9) {

            double x = Math.max(Truck.X0 + 0.5, Math.min(Truck.X1 - 0.5, pos.getX())), z = Math.max(Truck.Z0 + 0.5, Math.min(Truck.Z1 - 0.5, pos.getZ()));
            seek(new Vector(x, pos.getY(), z), speed());
            if (age % 20 == 0 && target == null) mark(nearest(400));
            return true;
        }
        hold();
        face(Truck.center().setY(pos.getY()));
        if (age >= truckAt) {
            truckAt = age + cd(TRUCK_CD);
            t.hit(t.biteDamage(sp) * TRUCK_K, this, "cause_g114");
            sound("bite", Sound.ENTITY_SILVERFISH_HURT, 1.4f, 12);
            body.squash(1.1f, 0.85f, 1.1f, 2);
        }
        return true;
    }

    @Override
    public List<double[]> spheres() {
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX() + fx * 0.35, pos.getY() + 0.05, pos.getZ() + fz * 0.35, 0.32});
        out.add(new double[] {pos.getX() - fx * 0.35, pos.getY() + 0.05, pos.getZ() - fz * 0.35, 0.32});
        return out;
    }

    @Override
    public void remove() {
        clingTo = null;
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ant" + (truckLine ? " truck" : "") + (clingTo != null ? " cling=" + clingTo.getName() : "");
    }
}
