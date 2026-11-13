package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 12 흩어짐: 트럭이 급히 서며 모두 튕겨 나간다. 사람마다 R 30~44 의 다른 자리에서 안개 5 로 시작한다. 트럭은 HORN_EVERY 틱마다 경적을 울리고(그 사람에게 트럭 쪽
 * 화살촉) 등이 켜져 있다. 혼자인 사람(LONER_R 안에 동료 없음)부터 둘레 게이브가 노린다. 모두(서 있는 사람) 중앙 원에 들어오면 안개가 걷히고 보통 라운드로
 * 이어진다. 끝: 모인 뒤 다 잡기.
 */
public final class Scatter extends Special {

    private boolean gathered;
    private long nextHorn, gatheredAt = -1;
    private final Set<UUID> loners = new HashSet<>();
    private int horns, inside, total;

    public Scatter(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_hold";
    }

    @Override
    protected boolean begin() {
        gathered = false;
        gatheredAt = -1;
        horns = 0;
        loners.clear();
        nextHorn = openAt + 40;
        List<Player> st = new ArrayList<>(standing());
        if (st.isEmpty()) return false;
        double base = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < st.size(); i++) {
            Player p = st.get(i);
            double a = base + i * Math.PI * 2 / st.size() + (rnd.nextDouble() - 0.5) * 0.5;
            double r = Tune.SCATTER_R0 + rnd.nextDouble() * (Tune.SCATTER_R1 - Tune.SCATTER_R0);
            Vector at = ring(a, r);
            // 등을 트럭으로(어디서 왔는지 모르게)
            Location l = new Location(game.world, at.getX(), Plaza.FLOOR_Y + 0.05, at.getZ());
            l.setDirection(at.clone().subtract(center()).setY(0));
            p.teleport(l);
            game.players.slow(p, 40, 30, "sp_scatter");
        }
        fog(Tune.SCATTER_FOG);
        sound(truckLoc(1), "trip.brake", Sound.ENTITY_MINECART_INSIDE, 2f, 0.6f, 120);
        sound(truckLoc(1), "trip.thud", Sound.ENTITY_IRON_GOLEM_DAMAGE, 2f, 0.5f, 120);
        game.horizon.shake(1.0, 20);
        rounds.goals().custom(icon(), 0, null);
        streaming = true;
        dimWant = 0.2;
        return true;
    }

    @Override
    protected void step(long now) {
        if (gathered) return;
        List<Player> st = standing();
        total = st.size();
        inside = 0;
        for (Player p : st) if (Plaza.radius(p.getLocation().getX(), p.getLocation().getZ()) <= Tune.GATHER_R) inside++;
        rounds.goals().custom(icon(), total == 0 ? 0 : (double) inside / total, inside + "/" + total);
        if (now >= nextHorn) {
            nextHorn = now + Tune.HORN_EVERY;
            horns++;
            sound(truckLoc(1.5), "trip.horn", org.bukkit.Sound.ITEM_GOAT_HORN_SOUND_2, 3f, 0.9f, 160);
            Vector t = Truck.center().clone().setY(Plaza.FLOOR_Y + 2);
            for (Player p : st) if (Plaza.radius(p.getLocation().getX(), p.getLocation().getZ()) > Tune.GATHER_R) game.hud.point(p, t, 40);
        }
        // 혼자인 사람: 둘레 게이브가 그 사람만 노림
        if (now % 20 == 0) {
            for (Player p : st) {
                boolean alone = true;
                for (Player q : st) if (q != p && q.getLocation().distanceSquared(p.getLocation()) <= Tune.LONER_R * Tune.LONER_R) {
                    alone = false;
                    break;
                }
                String k = "sp_scatter_" + p.getUniqueId();
                if (alone && Plaza.radius(p.getLocation().getX(), p.getLocation().getZ()) > Tune.GATHER_R) {
                    force(k, p, Tune.LONER_FORCE_R, 30);
                    loners.add(p.getUniqueId());
                } else if (loners.remove(p.getUniqueId())) unforce(k);
            }
        }
        if (total > 0 && inside >= total) gather(now);
    }

    private void gather(long now) {
        gathered = true;
        gatheredAt = now;
        for (UUID id : loners) unforce("sp_scatter_" + id);
        loners.clear();
        fogBack();
        streaming = false;
        dimWant = 0;
        rounds.goals().custom(icon(), 1, null);
        say("sa_scatter_gather", 60);
        sound(truckLoc(1.5), "trip.horn", org.bukkit.Sound.ITEM_GOAT_HORN_SOUND_2, 2f, 1.1f, 96);
    }

    @Override
    protected boolean check(boolean done) {
        return gathered && done;
    }

    @Override
    protected void end() {
        loners.clear();
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.gathered", gathered);
        if (!gathered) out.put("sp.gather", total == 0 ? 0.0 : (double) inside / total);
    }

    @Override
    protected String detail() {
        return String.format(Locale.ROOT, "gathered=%s in=%d/%d loners=%d horns=%d", gathered, inside, total, loners.size(), horns);
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        switch (what) {
            case "모임" -> {
                for (Player q : standing()) q.teleport(new Location(game.world, Truck.OX, Plaza.FLOOR_Y + 0.05, Truck.Z1 + 2 + rnd.nextDouble() * 3, 180f, 0f));
                return "모두 트럭 뒤로";
            }
            case "경적" -> {
                nextHorn = game.now();
                return "경적";
            }
            default -> {
                return null;
            }
        }
    }
}
