package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 9 떼몰이: 한쪽 지평선에서 반대쪽으로 수십에서 백 가까운 떼가 사람을 안 보고 곧게 달려 지나간다. 예고 HERD_WARN 틱(땅울림, 화살촉, 지나갈 띠가 바닥에
 * 빨간 고리 줄로). 띠 안에 선 사람은 받혀 날아가고 다친다. 떼 머리(앞줄 금빛 HERD_HEADS)를 다 잡으면 떼가 갈라져 트럭을 비켜 가고, 못 가르면 트럭을 정통으로
 * 들이받는다(HERD_TRUCK). 세 번, 갈수록 넓고 많게. 끝: 세 번 지나감.
 */
public final class Stampede extends Special {

    private final class Herd {
        double a, w;
        Vector u, v;
        final List<Gabe> members = new ArrayList<>();
        final Set<Gabe> heads = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Gabe, Double> lane = new IdentityHashMap<>();
        boolean split, hit, spawnedAll;
        long warnAt, runAt;
        int rows, made, want;
        final List<ItemDisplay> marks = new ArrayList<>();
    }

    private Herd herd;
    private int pass, splits, truckHits, knocked;
    private long nextAt;
    private Species sp;
    private final Map<UUID, Long> knockAt = new HashMap<>();

    public Stampede(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_truck";
    }

    @Override
    public void plan(Director.Plan p) {
        scaleUnits(p, 0.6);
    }

    @Override
    protected boolean begin() {
        ownEnd = true;
        herd = null;
        pass = splits = truckHits = knocked = 0;
        nextAt = openAt + 100;
        knockAt.clear();
        sp = cheapRunner();
        if (sp == null) return false;
        rounds.goals().count(0, Tune.HERD_PASSES);
        streaming = true;
        return true;
    }

    @Override
    protected void step(long now) {
        if (herd == null) {
            if (pass >= Tune.HERD_PASSES) {
                finish(true);
                return;
            }
            if (now >= nextAt) warn(now);
            return;
        }
        Herd h = herd;
        if (now < h.runAt) {
            if ((h.runAt - now) % 40 == 0) sound(loc(ring(h.a, 40), 1), "sfx.quake", Sound.ENTITY_WARDEN_EMERGE, 1.4f, 0.5f, 120);
            return;
        }
        if (!h.spawnedAll) spawnRows(h);
        run(h, now);
    }

    private void warn(long now) {
        Herd h = new Herd();
        h.a = rnd.nextDouble() * Math.PI * 2;
        h.w = Tune.HERD_W[Math.min(pass, Tune.HERD_W.length - 1)];
        h.u = new Vector(Math.cos(h.a), 0, Math.sin(h.a));
        h.v = new Vector(-h.u.getZ(), 0, h.u.getX());
        h.warnAt = now;
        h.runAt = now + Tune.HERD_WARN;
        h.want = Tune.HERD_N[Math.min(pass, Tune.HERD_N.length - 1)] + Tune.HERD_ACT * (act - 1);
        // 지나갈 띠: 가운데를 지나는 줄에 빨간 고리를 띠 폭으로
        for (double t = -60; t <= 60; t += h.w) {
            Vector at = center().add(h.u.clone().multiply(t));
            ItemDisplay d = redRing(at, h.w / 2);
            if (d != null) h.marks.add(d);
        }
        herd = h;
        game.hud.waveFrom("sa_herd_come", Tune.HERD_WARN, yawOf(h.a));
        alert("sa_herd_come", 3, 80);
        sound(loc(ring(h.a, 40), 1), "sfx.horde_cry", Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 1.6f, 0.5f, 140);
        game.horizon.shake(0.4, 40);
    }

    /** 4틱에 걸쳐 줄마다 놓음(한 틱에 수십을 놓지 않게). 앞줄 HERD_HEADS 는 금빛 머리. */
    private void spawnRows(Herd h) {
        int perRow = Math.max(4, (int) Math.round(h.w / 1.2));
        int rowsAll = (int) Math.ceil(h.want / (double) perRow);
        int step = Math.max(1, (int) Math.ceil(rowsAll / 4.0));
        for (int r = 0; r < step && h.rows < rowsAll; r++, h.rows++) {
            for (int i = 0; i < perRow && h.made < h.want; i++) {
                double side = (i + 0.5) / perRow - 0.5;
                double lat = side * h.w;
                Vector at = center().add(h.u.clone().multiply(Tune.HERD_FROM + h.rows * 1.7)).add(h.v.clone().multiply(lat));
                Gabe g = spawn(sp, at, true);
                h.made++;
                if (g == null) continue;
                g.noHold = true;
                g.noReward = false;
                h.members.add(g);
                h.lane.put(g, lat);
                if (h.heads.size() < Tune.HERD_HEADS && h.rows == 0 || h.heads.size() < Tune.HERD_HEADS && h.rows == 1) {
                    h.heads.add(g);
                    if (g.body != null) {
                        g.body.look.palette |= Look.PAL_GOLD;
                        g.body.look.open = true;
                    }
                }
            }
        }
        if (h.rows >= rowsAll || h.made >= h.want) h.spawnedAll = true;
    }

    private void run(Herd h, long now) {
        Vector c = center();
        double front = Double.MAX_VALUE;
        int alive = 0, headsLeft = 0;
        for (Gabe g : h.members) {
            if (g.dead || g.removed) continue;
            alive++;
            if (h.heads.contains(g)) headsLeft++;
            double lat = h.lane.getOrDefault(g, 0.0);
            if (h.split) lat += Math.signum(lat == 0 ? 1 : lat) * Tune.HERD_SPLIT;
            Vector to = c.clone().add(h.u.clone().multiply(-95)).add(h.v.clone().multiply(lat));
            to.setY(g.pos.getY());
            if (now % 10 == 0 || g.age < 3) game.mobs.lure(g, to, 16);
            double base = Math.max(0.02, g.speed() / Math.max(0.05, g.buff()));
            g.speedBuff = Tune.HERD_SPEED / base;
            double s = g.pos.clone().subtract(c).dot(h.u);
            front = Math.min(front, s);
            if (s < -88) g.remove();
        }
        // 머리를 다 잡으면 갈라진다(트럭 앞 12블록 전까지)
        if (!h.split && h.spawnedAll && headsLeft == 0 && !h.heads.isEmpty() && front > 12) {
            h.split = true;
            splits++;
            say("sa_herd_split", 50);
        }
        // 받힘: 사람
        for (Player p : standing()) {
            Location l = p.getLocation();
            Long at = knockAt.get(p.getUniqueId());
            if (at != null && now - at < 20) continue;
            Gabe by = game.mobs.nearest(l.toVector(), 1.4, g -> h.lane.containsKey(g) && !g.dead);
            if (by == null) continue;
            knockAt.put(p.getUniqueId(), now);
            Vector side = h.u.clone().multiply(-0.9);
            game.players.launch(p, 1.4, side, Tune.HERD_DMG, by, "cause_sp_herd");
            knocked++;
        }
        // 받힘: 트럭(못 갈랐으면 한 번 정통으로)
        if (!h.hit && !h.split) {
            for (Gabe g : h.members) {
                if (g.dead || g.removed) continue;
                if (Truck.dist(g.pos.getX(), g.pos.getZ()) > 1.2) continue;
                h.hit = true;
                truckHits++;
                Truck tr = trip.truck();
                if (tr != null) tr.hitFrac(Tune.HERD_TRUCK, g, "cause_sp_herd");
                alert("sa_herd_hit", 4, 60);
                game.horizon.shake(1.0, 20);
                sound(truckLoc(1), "trip.truck_hit", org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f, 80);
                break;
            }
        }
        // 트럭에 막힌 것은 깔려 죽음(길을 막지 않게)
        if (h.hit && now % 5 == 0) for (Gabe g : h.members) {
            if (g.dead || g.removed || Truck.dist(g.pos.getX(), g.pos.getZ()) > 1.0) continue;
            game.mobs.damage(g, new Damage(1e6, DamageType.OTHER, null, g.pos.clone(), h.u.clone().multiply(-1), "truck", 0));
        }
        if (h.spawnedAll && (alive == 0 || front < -85)) {
            for (Gabe g : h.members) if (!g.dead && !g.removed) g.remove();
            for (ItemDisplay d : h.marks) drop(d);
            herd = null;
            pass++;
            nextAt = now + Tune.HERD_GAP;
            rounds.goals().count(pass, Tune.HERD_PASSES);
        }
    }

    @Override
    protected void end() {
        if (herd != null) for (ItemDisplay d : herd.marks) drop(d);
        herd = null;
        knockAt.clear();
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.herd", pass);
        if (herd != null) {
            out.put("sp.warn", (int) Math.max(0, herd.runAt - game.now()));
            out.put("sp.from", yawOf(herd.a));
        }
    }

    @Override
    protected String detail() {
        int alive = 0, heads = 0;
        if (herd != null) for (Gabe g : herd.members) if (!g.dead && !g.removed) {
            alive++;
            if (herd.heads.contains(g)) heads++;
        }
        return String.format(Locale.ROOT, "pass=%d/%d herd=%s alive=%d heads=%d splits=%d truck=%d knocked=%d sp=%s", pass, Tune.HERD_PASSES,
            herd == null ? "-" : herd.split ? "split" : game.now() < herd.runAt ? "warn" : "run", alive, heads, splits, truckHits, knocked, sp == null ? "-" : sp.name());
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        switch (what) {
            case "지금" -> {
                if (herd == null) nextAt = game.now();
                else herd.runAt = Math.min(herd.runAt, game.now());
                return "떼 지금";
            }
            case "머리" -> {
                if (herd == null) return "떼 없음";
                int n = 0;
                for (Gabe g : herd.heads) if (!g.dead) {
                    g.die(new Damage(1e6, DamageType.BULLET, p, g.pos.clone(), null, "test", 0));
                    n++;
                }
                return "머리 " + n;
            }
            default -> {
                return null;
            }
        }
    }
}
