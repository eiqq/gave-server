package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;

/**
 * G72 불꼬리 운석 게이브(원거리, 떼, 목표 압박, 1막). 안개 너머 고리(감독이 놓은 자리)에서 먼 발사음과 함께 높은 포물선으로 날아와(1.5~2.5초) 사람의
 * 앞자리에 떨어진다. 떨어질 자리에 1.5초 전부터 줄어드는 붉은 원. 반경 2 안 10 피해와 넘어짐 0.5초, 그 자리 3초 불(초당 3). 땅에 박히면 공이 쩍
 * 갈라져(1초, 이때 받는 피해 x1.5) 막대기 셋을 다리 삼아 가장 가까운 사람에게 기어 와 문다(4). 18% 는 트럭을 겨눠 떨어지고(폭발은 트럭에 세 배),
 * 갈라진 공이 트럭 7블록 안이면 트럭을 문다(목표 압박: 원정의 엔진 물기에서 빠지는 종이라 종이 직접 Truck.hit).
 *
 * <p>대처: 붉은 원 밖으로 한 걸음. 날아오는 동안 쏘면(공중 받는 피해 x4) 공중에서 터져 아무도 안 다친다.
 *
 * <p>팩(tools/gabe/sp/g72.py): 모델 {@code gave:gabe/g72} 상태 "fly"(기본), "crawl". 원 {@code gave:gabe/g72_ring}. 소리 launch(먼 곳의 발사, 96),
 * whistle(떨어지는 소리, 48), impact(쿵, 40), crack(쩍, 16). 글귀 cause_g72(떨어짐), cause_g72_fire(불), cause_g72_bite(물기).
 */
public final class G72 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 발사 전 기다림(무리가 조금씩 어긋나게), 비행 틱(거리에 따라 이 범위), 꼭대기 높이(기본 + 거리 몫). */
    static final int WAIT_MAX = 24, FLIGHT_MIN = 30, FLIGHT_MAX = 50;
    static final double FLIGHT_BPT = 2.2, APEX = 16, APEX_PER = 0.12;
    /** 떨어질 자리 흩뿌림(블록), 사람 걸음 앞질러 겨누는 몫(비행 틱의 비율). */
    static final double SCATTER = 1.2, LEAD = 0.4;
    /** 붉은 원을 띄우는 틱(떨어지기 전), 원 처음과 끝 지름. */
    static final int RING_T = 30;
    static final float RING_FROM = 7f, RING_TO = 4.2f;
    /** 떨어짐: 반경, 피해, 넘어짐 틱, 밀침. 불: 반경, 틱, 초당 피해. */
    static final double IMPACT_R = 2.0, IMPACT_DMG = 10, FIRE_R = 1.6, FIRE_DPS = 3;
    static final int TRIP = 10, FIRE_T = 60;
    /** 트럭을 겨누는 몫, 갈라진 공이 트럭을 무는 거리. */
    static final double TRUCK_P = 0.18, TRUCK_NEAR = 7;
    /** 갈라짐 틱, 기는 빠르기, 무는 피해와 쉬는 틱. */
    static final int CRACK = 20, BITE_REST = 22, TRUCK_REST = 30;
    static final double CRAWL_V = 0.15, BITE_DMG = 4, CRAWL_HOVER = 0.8;
    /** 받는 피해 배율: 공중, 갈라지는 중. */
    static final double AIR_MUL = 4, CRACK_MUL = 1.5;

    enum Phase { WAIT, FLY, CRACK, CRAWL }

    private Phase phase = Phase.WAIT;
    private int phaseAt, waitFor, flight, readyAt;
    private final Vector from = new Vector(), to = new Vector();
    private double apex;
    private boolean atTruck, whistled;
    private ItemDisplay ring;

    public G72(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        baseSpeed = CRAWL_V;
        flying = true;
        ghost = true;
        noPush = true;
        state("fly");
    }

    @Override
    protected void spawned() {
        waitFor = game.random.nextInt(WAIT_MAX + 1);
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
        switch (phase) {
            case WAIT -> waitLaunch();
            case FLY -> fly();
            case CRACK -> crack();
            case CRAWL -> crawl();
        }
    }

    /** 고리(안개 속)에서 잠깐 기다렸다가 발사. */
    private void waitLaunch() {
        hold();
        if (inPhase() < waitFor) return;
        Player p = nearest(400);
        Truck t = Truck.get();
        atTruck = t != null && t.alive() && (p == null || game.random.nextDouble() < TRUCK_P);
        if (atTruck) {
            // 트럭 바로 곁 땅(발자국에 맞닿은 칸에서 바깥으로 0~1.5): 지붕에 박히지 않게
            java.util.List<double[]> slots = Truck.slots();
            double[] sl = slots.get(game.random.nextInt(slots.size()));
            double out = game.random.nextDouble() * 1.5;
            to.setX(sl[0] - sl[2] * out).setZ(sl[1] - sl[3] * out);
        } else if (p != null) {
            Location l = p.getLocation();
            Vector v = p.getVelocity();
            to.setX(l.getX() + v.getX() * 40 * LEAD + game.random.nextGaussian() * SCATTER).setZ(l.getZ() + v.getZ() * 40 * LEAD
                    + game.random.nextGaussian() * SCATTER);
        } else {
            // 사람이 없으면 가운데 쪽으로 걸어오게
            land();
            return;
        }
        to.setY(game.mobs.flow.floor(to.getX(), to.getZ()) + 0.55);
        from.copy(pos);
        double d = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        flight = (int) Math.max(FLIGHT_MIN, Math.min(FLIGHT_MAX, d / FLIGHT_BPT));
        apex = APEX + d * APEX_PER;
        sound("launch", Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 96);
        if (atTruck) mark(null);
        else mark(p);
        phase(Phase.FLY);
    }

    /** 포물선(서버 계산): 다음 자리까지를 vel 로. 진행 방향을 보며 불꼬리를 끈다. */
    private Vector at(double s) {
        Vector v = from.clone().multiply(1 - s).add(to.clone().multiply(s));
        v.setY(v.getY() + 4 * apex * s * (1 - s));
        return v;
    }

    private void fly() {
        int t = inPhase();
        if (t >= flight) {
            impact();
            return;
        }
        Vector next = at((t + 1.0) / flight);
        vel.copy(next.clone().subtract(pos));
        face(pos.clone().add(vel.clone().multiply(3)));
        if (t == Math.max(0, flight - RING_T)) showRing();
        if (!whistled && t >= flight - 22) {
            whistled = true;
            sound("whistle", Sound.ENTITY_ARROW_SHOOT, 0.5f, 48);
        }
        if (ring != null && ring.isValid() && t % 3 == 0) {
            float k = (float) Math.max(0, Math.min(1, (t - (flight - RING_T)) / (double) RING_T));
            game.shotFx.grow(ring, ShotFx.scale(RING_FROM + (RING_TO - RING_FROM) * k), 3);
        }
        if (t % 3 == 0 && game.shotFx.room(ShotFx.EXTRA)) game.shotFx.flame(loc(), 0.45f, 6, ShotFx.EXTRA);
    }

    private void showRing() {
        removeRing();
        Location l = new Location(game.world, to.getX(), to.getY() - 0.5, to.getZ());
        ring = game.shotFx.spawn(l, "fx_quad", 0, 0, ShotFx.scale(RING_FROM), Display.Billboard.FIXED, false, RING_T + 10, ShotFx.KEY);
        if (ring == null) return;
        ring.setItemStack(Body.item("gabe/g72_ring", "", new LookState(), game.worldTime()));
        ring.setBrightness(new Display.Brightness(13, 13));
    }

    private void removeRing() {
        if (ring != null && ring.isValid()) ring.remove();
        ring = null;
    }

    /** 땅에 박힘: 반경 안 사람 피해와 넘어짐, 트럭 곁이면 트럭에 세 배, 그 자리 불. */
    private void impact() {
        removeRing();
        pos.copy(to);
        hold();
        double fl = floor();
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz > IMPACT_R * IMPACT_R || Math.abs(l.getY() - fl) > 2.5) continue;
            hit(p, IMPACT_DMG, "cause_g72");
            game.players.root(p, TRIP, "g72");
            knock(p, new Vector(dx, 0, dz), 0.35, 0.3);
        }
        Truck t = Truck.get();
        if (t != null && Truck.dist(pos.getX(), pos.getZ()) <= 3) {
            // 폭발 세 배(원정 4.4절): 사람 피해 x TRUCK_BLAST 를 트럭 물기와 같은 배율(웨이브 피해, 사람 수)로
            double k = game.director.dmgWave() * Trip.Tune.TRUCK_DMG / Math.pow(Math.max(1, game.trip.people()), Trip.Tune.TRUCK_POW);
            t.hit(IMPACT_DMG * Trip.Tune.TRUCK_BLAST * k, this, "cause_g72");
        }
        Location l = new Location(game.world, pos.getX(), fl + 0.2, pos.getZ());
        game.shotFx.blast(l, (float) IMPACT_R);
        sound("impact", Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 40);
        G72Zone.fire(game, new Vector(pos.getX(), fl, pos.getZ()), FIRE_R, FIRE_T, FIRE_DPS, this, "cause_g72_fire");
        // 갈라짐
        flying = false;
        ghost = false;
        hover = CRAWL_HOVER;
        pitch = 0;
        state("crawl");
        body.look.freeze = true;
        body.squash(1.35f, 0.6f, 1.35f, 2);
        sound("crack", Sound.BLOCK_STONE_BREAK, 0.8f, 16);
        phase(Phase.CRACK);
    }

    private void crack() {
        hold();
        if (inPhase() == 4) body.squash(1f, 1f, 1f, 10);
        if (inPhase() >= CRACK) {
            body.look.freeze = false;
            readyAt = age;
            phase(Phase.CRAWL);
        }
    }

    /** 사람이 없을 때(발사할 곳이 없음): 바로 땅에 내려 기기. */
    private void land() {
        flying = false;
        ghost = false;
        hover = CRAWL_HOVER;
        state("crawl");
        phase(Phase.CRAWL);
    }

    /** 막대기 셋으로 기어 옴: 트럭 7블록 안이면 트럭을 물고, 아니면 가장 가까운 사람을 문다. */
    private void crawl() {
        Truck t = Truck.get();
        Player p = nearest(400);
        double td = Truck.dist(pos.getX(), pos.getZ());
        // 트럭이 가까운 사람보다 가깝고 7블록 안이면 트럭을 문다
        if (t != null && t.alive() && td <= TRUCK_NEAR && (p == null || td + 0.5 < dist(p))) {
            double reach = Truck.dist(pos.getX(), pos.getZ()) - radius;
            Vector c = Truck.center();
            double x = Math.max(Truck.X0, Math.min(Truck.X1, pos.getX())), z = Math.max(Truck.Z0, Math.min(Truck.Z1, pos.getZ()));
            Vector edge = new Vector(x, pos.getY(), z);
            face(edge.clone().setY(c.getY() + 1));
            if (reach > 0.5) {
                seek(edge.setY(pos.getY()), speed());
                vel.setY(0);
            } else {
                hold();
                if (age >= readyAt) {
                    t.hit(t.biteDamage(sp), this, "cause_g72_bite");
                    body.squash(1.2f, 0.85f, 1.2f, 2);
                    readyAt = age + cd(TRUCK_REST);
                } else if (age == readyAt - cd(TRUCK_REST) + 3) body.squash(1f, 1f, 1f, 3);
            }
            return;
        }
        follow(speed());
        if (p == null) return;
        face(chest(p));
        if (age >= readyAt && touches(p, 0.15)) {
            hit(p, BITE_DMG, "cause_g72_bite");
            body.squash(0.85f, 1.15f, 1.2f, 2);
            readyAt = age + cd(BITE_REST);
        } else if (age == readyAt - cd(BITE_REST) + 3) body.squash(1f, 1f, 1f, 3);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.55});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (phase == Phase.FLY) return super.damage(d.times(AIR_MUL));
        if (phase == Phase.CRACK) return super.damage(d.times(CRACK_MUL));
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        removeRing();
        if (phase == Phase.FLY || phase == Phase.WAIT) {
            // 공중에서 터짐: 아무도 안 다침, 사체 없음
            leaveCorpse = false;
            game.shotFx.pop(loc());
        }
        super.die(d);
    }

    @Override
    public void remove() {
        removeRing();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (atTruck ? " truck" : "");
    }
}
