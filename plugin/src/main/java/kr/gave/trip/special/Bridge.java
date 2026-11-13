package kr.gave.trip.special;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.world.Plaza;

/**
 * 4 좁은 다리: 경기장이 길 방향(남북) 폭 14 의 띠로 줄어든다. 띠 밖은 틈: 사람은 되밀리며 다치고 땅 게이브는 띠 안으로 되돌아온다.
 * 떼는 앞(북)과 뒤(남)에서만, 하늘 종은 옆(동서)에서 온다. 양옆에 난간(모드 가드레일). 끝: 다 잡기.
 */
public final class Bridge extends Special {

    private double x0, x1;
    private final Map<UUID, Long> fellAt = new HashMap<>();
    private int falls, pulled, rails;

    public Bridge(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_kill";
    }

    @Override
    public void plan(Director.Plan p) {
        scaleUnits(p, Tune.BRIDGE_UNITS);
        p.lean = -1;
        p.leanCounts.clear();
        p.leanExtra = 0;
    }

    @Override
    protected boolean begin() {
        x0 = Plaza.CX + 0.5 - Tune.BRIDGE_HALF;
        x1 = Plaza.CX + 0.5 + Tune.BRIDGE_HALF;
        fellAt.clear();
        falls = pulled = rails = 0;
        for (double z = -Plaza.R_ROAM + 3; z <= Plaza.R_ROAM - 3; z += Tune.RAIL_GAP) {
            if (prop("trip/guardrail", new Location(game.world, x0 - 0.4, Plaza.FLOOR_Y + 0.5, Plaza.CZ + 0.5 + z), 90f, 1f) != null) rails++;
            if (prop("trip/guardrail", new Location(game.world, x1 + 0.4, Plaza.FLOOR_Y + 0.5, Plaza.CZ + 0.5 + z), -90f, 1f) != null) rails++;
        }
        // 띠 밖에 선 사람은 띠 안으로
        for (Player p : standing()) {
            Location l = p.getLocation();
            if (l.getX() < x0 || l.getX() > x1) p.teleport(new Location(game.world, Math.max(x0 + 1, Math.min(x1 - 1, l.getX())), Plaza.FLOOR_Y + 0.05, l.getZ(), l.getYaw(), l.getPitch()));
        }
        game.hud.waveFrom("ev_dir", 80, 180, 0);
        sound(truckLoc(2), "sfx.wind", Sound.ITEM_ELYTRA_FLYING, 1.2f, 0.6f, 64);
        dimWant = 0.15;
        return true;
    }

    @Override
    protected void spawned(Gabe g) {
        if (g.sp.is(Role.BOSS) || g.sp.is(Role.BONUS) || g.sp == Species.G43) return;
        if (g.sp.spawn != Species.Spawn.RING && g.sp.spawn != Species.Spawn.SKY) return;
        Vector to;
        if (g.flying) {
            double side = rnd.nextBoolean() ? 1 : -1;
            to = new Vector(Plaza.CX + 0.5 + side * (24 + rnd.nextDouble() * 8), 0, Plaza.CZ + 0.5 + (rnd.nextDouble() - 0.5) * 60);
        } else {
            double sgn = rnd.nextBoolean() ? 1 : -1;
            to = new Vector(x0 + 1 + rnd.nextDouble() * (x1 - x0 - 2), 0, Plaza.CZ + 0.5 + sgn * (70 + rnd.nextDouble() * 10));
        }
        move(g, to);
    }

    @Override
    protected void step(long now) {
        for (Player p : game.players.inPlay()) {
            if (!alive(p)) continue;
            Location l = p.getLocation();
            boolean out = l.getX() < x0 - 0.6 || l.getX() > x1 + 0.6;
            if (confine(p, x0, x1, -Plaza.R_ROAM, Plaza.R_ROAM) && out) {
                Long at = fellAt.get(p.getUniqueId());
                if (at == null || now - at >= 20) {
                    fellAt.put(p.getUniqueId(), now);
                    game.players.hurt(p, Tune.BRIDGE_FALL_DMG, null, "cause_sp_bridge");
                    falls++;
                }
            }
        }
        if (now % 5 != 0) return;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.flying || g.sp.is(Role.BOSS) || g.ghost) continue;
            if (Math.abs(g.pos.getZ() - Plaza.CZ - 0.5) > Plaza.R_ROAM + 6) continue;
            double x = g.pos.getX();
            if (x >= x0 - 0.3 && x <= x1 + 0.3) continue;
            if (move(g, new Vector(Math.max(x0 + 0.5, Math.min(x1 - 0.5, x)), 0, g.pos.getZ()))) pulled++;
        }
    }

    @Override
    protected void end() {
        fellAt.clear();
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.band", Tune.BRIDGE_HALF);
    }

    @Override
    protected String detail() {
        return String.format(Locale.ROOT, "band=%.0f..%.0f falls=%d pulled=%d rails=%d", x0, x1, falls, pulled, rails);
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        if (what.equals("밖")) {
            if (p == null) return "사람 없음";
            p.teleport(new Location(game.world, x1 + 3, Plaza.FLOOR_Y + 0.05, p.getLocation().getZ(), 90f, 0f));
            return "띠 밖으로";
        }
        return null;
    }
}
