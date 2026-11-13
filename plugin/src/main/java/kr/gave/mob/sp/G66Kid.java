package kr.gave.mob.sp;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G66 바구니 진 덩치가 던진 새끼(0.55 블록 얼굴, 버둥대는 팔다리 막대기 넷). 같은 종 G66 으로 센다(보상 0). 포물선으로 날아가는 동안 떨어질 자리에
 * 빨간 고리, 떨어지면 반경 1.5 에 4, 그 뒤 물기 3 으로 달려든다(체력 4 x 감독 체력 배율). 트럭 지붕에 떨어진 것은 지붕에 앉아 트럭을 문다.
 */
public final class G66Kid extends Gabe {

    static final double HOVER = 0.42, SPEED = 0.17, HP = 4, LAND_R = 1.5, LAND_DMG = 1, BITE = 1;
    static final int BITE_CD = 20;

    private final Vector from, to;
    private boolean roof;
    private final int flight;
    private boolean landed;
    private int biteAt;

    G66Kid(Game game, Species sp, Gabe parent, Vector from, Vector to, boolean roof, boolean fly) {
        super(game, sp);
        this.from = from.clone();
        this.to = to.clone();
        this.roof = roof;
        double d = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        this.flight = fly ? (int) Math.max(14, Math.min(46, 16 + d * 1.1)) : 0;
        radius = 0.32;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true;
        noBite = true;
        flying = true;
        noPush = true;
        ragKick = 1.4;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g66_kid", 1.4f, 1.2f);
    }

    @Override
    protected void spawned() {
        if (flight > 0) G66Giant.warn(this, to, LAND_R, flight + 4);
        else land();
    }

    @Override
    public void tick() {
        if (!landed) {
            fly();
            return;
        }
        if (roof) {
            sitRoof();
            return;
        }
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        mark(p);
        face(chest(p));
        follow(speed());
        if (age >= biteAt && touches(p, 0.1)) {
            hit(p, BITE, "cause_g66_kid");
            biteAt = age + cd(BITE_CD);
            body.squash(1.3f, 0.75f, 1.3f, 2);
        }
    }

    /** 포물선: 높이 4 + 거리 x 0.18 의 활. 다음 틱 자리로 vel 을 줌. */
    private void fly() {
        int t = age + 1;
        double u = Math.min(1.0, t / (double) flight);
        double d = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        double h = 4 + d * 0.18;
        Vector want = from.clone().multiply(1 - u).add(to.clone().multiply(u)).add(new Vector(0, 4 * h * u * (1 - u), 0));
        vel.copy(want.subtract(pos));
        yaw += 23;
        pitch = (float) ((age * 31) % 360) - 180;
        if (u >= 1.0) land();
    }

    private void land() {
        landed = true;
        pitch = 0;
        vel.zero();
        pos.copy(to);
        if (!roof) flying = false;
        noPush = false;
        G66Giant.ring(this, to, LAND_R, 8);
        G66.kidSound(this);
        double r2 = LAND_R * LAND_R;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - to.getX(), dz = l.getZ() - to.getZ();
            if (dx * dx + dz * dz <= r2 && Math.abs(l.getY() - to.getY()) < 2.5) hit(p, LAND_DMG, "cause_g66_kid");
        }
        biteAt = age + 10;
    }

    /** 트럭 지붕에 앉아 문다(트럭이 없어지면 내려와 사람에게). */
    private void sitRoof() {
        Truck t = game.trip == null ? null : game.trip.truck();
        if (t == null || !t.alive()) {
            flying = false;
            hold();
            landedDown();
            return;
        }
        hold();
        pos.setY(Truck.center().getY() + Truck.HEIGHT + HOVER);
        if (age >= biteAt) {
            t.hit(t.biteDamage(sp), this, "cause_g66_kid");
            biteAt = age + cd(30);
            body.squash(1.25f, 0.8f, 1.25f, 2);
        }
        Player p = nearest(6);
        if (p != null) face(chest(p));
    }

    private boolean down;

    private void landedDown() {
        if (down) return;
        down = true;
        roof = false;
        // 지붕에서 뛰어내려 사람 쪽으로(보통 새끼처럼)
        Vector c = Truck.center();
        Vector out = pos.clone().subtract(c).setY(0);
        if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
        out.normalize().multiply(3.5);
        Vector at = c.clone().add(out);
        at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
        pos.copy(at);
    }

    @Override
    public void reveal() {
    }

    boolean onRoof() {
        return roof && landed;
    }
}
