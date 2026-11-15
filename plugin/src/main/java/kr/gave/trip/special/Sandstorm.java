package kr.gave.trip.special;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.player.PState;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 6 모래폭풍(1막): 안개 4, 바람 쪽이 WIND_EVERY 틱마다 바뀐다(화살촉). 바람을 마주 보면 화면이 모래로 덮이고(덮개) 멀리 쏜 탄이 약해지며 걸음이 느려진다.
 * 바람 쪽에서 오는 게이브는 날려 와 빠르다. 바람 그늘(트럭 바람 아래쪽 SHADE_LEN 블록)은 멀쩡하고, 그늘 밖에 선 사람은 모래에 조금씩 깎인다. 끝: 다 잡기.
 */
public final class Sandstorm extends Special {

    private static final BlockData SAND = Material.SAND.createBlockData();

    private double windA;
    private long nextTurn;
    private int turns;
    private final Map<UUID, Boolean> facing = new HashMap<>();
    private int sheltered, exposed;

    public Sandstorm(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_kill";
    }

    @Override
    protected boolean begin() {
        windA = rnd.nextDouble() * Math.PI * 2;
        turns = 0;
        nextTurn = openAt + Tune.WIND_EVERY;
        facing.clear();
        fog(Tune.SAND_FOG);
        game.hud.waveFrom("sa_wind", 100, yawOf(windA));
        scale("sp_sand", this::windScale);
        sound(truckLoc(2), "sfx.wind", Sound.ITEM_ELYTRA_FLYING, 1.6f, 0.5f, 96);
        dimWant = 0.2;
        return true;
    }

    /** 바람이 불어오는 쪽 단위 벡터(가운데에서 바람 쪽으로). */
    private Vector windDir() {
        return new Vector(Math.cos(windA), 0, Math.sin(windA));
    }

    private boolean faces(Player p) {
        Vector d = p.getLocation().getDirection().setY(0);
        if (d.lengthSquared() < 1e-6) return false;
        return d.normalize().dot(windDir()) > Math.cos(Math.toRadians(Tune.WIND_ARC));
    }

    /** 트럭 바람 그늘(트럭에서 바람 반대쪽으로 SHADE_LEN, 폭 SHADE_HALF x 2). */
    private boolean shade(Location l) {
        Vector c = Truck.center(), w = windDir();
        double vx = l.getX() - c.getX(), vz = l.getZ() - c.getZ();
        double along = -(vx * w.getX() + vz * w.getZ()), side = Math.abs(vx * w.getZ() - vz * w.getX());
        return along >= 0 && along <= Tune.SHADE_LEN + 3 && side <= Tune.SHADE_HALF + 1.5;
    }

    private boolean upwind(Vector v) {
        double a = Math.atan2(v.getZ() - Plaza.CZ - 0.5, v.getX() - Plaza.CX - 0.5);
        return Math.abs(wrap(a - windA)) < Math.toRadians(60);
    }

    private double windScale(Gabe g, Damage d) {
        if (!opened || ended || d.by() == null) return 1;
        Player p = d.by();
        if (!Boolean.TRUE.equals(facing.get(p.getUniqueId()))) return 1;
        Location l = p.getLocation();
        double dx = g.pos.getX() - l.getX(), dz = g.pos.getZ() - l.getZ();
        return dx * dx + dz * dz > Tune.SAND_NEAR * Tune.SAND_NEAR ? Tune.SAND_DMG : 1;
    }

    @Override
    protected void step(long now) {
        if (now >= nextTurn) {
            nextTurn = now + Tune.WIND_EVERY;
            windA += (rnd.nextBoolean() ? 1 : -1) * (Math.PI / 2 + rnd.nextDouble() * Math.PI / 2);
            turns++;
            game.hud.waveFrom("sa_wind", 80, yawOf(windA));
            say("sa_wind_turn", 50);
            sound(truckLoc(2), "sfx.wind", Sound.ITEM_ELYTRA_FLYING, 1.6f, 0.6f, 96);
        }
        for (Gabe g : game.mobs.all()) if (!g.dead && !g.sp.is(Role.BOSS) && upwind(g.pos)) g.speedBuff *= Tune.WIND_SPEED;
        sheltered = exposed = 0;
        Vector w = windDir().multiply(-0.9);
        for (Player p : standing()) {
            Location l = p.getLocation();
            boolean in = shade(l), f = faces(p) && !in;
            facing.put(p.getUniqueId(), f);
            if (in) sheltered++;
            else exposed++;
            if (f) {
                game.players.slow(p, Tune.SAND_SLOW, 25, "sp_sand");
                PState s = game.players.get(p);
                if (s.cover == null && now % 10 == 0) {
                    if (Hud.hasCover("sa_sand")) game.hud.cover(p, "sa_sand", 14);
                    else if (Hud.hasCover("dark")) game.hud.cover(p, "dark", 14);
                }
            }
            if (now % 4 == 0) {
                Location at = l.clone().add(Math.cos(windA) * 9, 1.6, Math.sin(windA) * 9);
                p.spawnParticle(Particle.FALLING_DUST, at, in ? 6 : 28, 7, 2.5, 7, 0, SAND);
                p.spawnParticle(Particle.CLOUD, at, 0, w.getX(), 0, w.getZ(), 1);
            }
        }
    }

    @Override
    protected void end() {
        facing.clear();
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.wind", yawOf(windA));
        out.put("sp.sand", Boolean.TRUE.equals(facing.get(p.getUniqueId())));
        out.put("sp.shade", shade(p.getLocation()));
    }

    @Override
    protected String detail() {
        return String.format(Locale.ROOT, "wind=%.0f turns=%d shade=%d exposed=%d", Math.toDegrees(wrap(windA)), turns, sheltered, exposed);
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        switch (what) {
            case "바람" -> {
                nextTurn = game.now();
                return "바람 돌림";
            }
            case "그늘" -> {
                if (p == null) return "사람 없음";
                Vector c = Truck.center(), w = windDir();
                Location to = new Location(game.world, c.getX() - w.getX() * 5, Plaza.FLOOR_Y + 0.05, c.getZ() - w.getZ() * 5);
                to.setDirection(w);
                p.teleport(to);
                return "그늘로";
            }
            case "마주" -> {
                if (p == null) return "사람 없음";
                Location l = p.getLocation();
                l.setDirection(windDir());
                p.teleport(l);
                return "바람 쪽을 봄";
            }
            default -> {
                return null;
            }
        }
    }
}
