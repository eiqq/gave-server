package kr.gave.trip.special;

import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.trip.Rounds;
import kr.gave.trip.Theme;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 특수 13 밀물(2막 젖은 벌, specials_draft tide): 벌판에 물이 차올라 {@link LateTune#TIDE_STEP} 틱마다 한 칸씩(Theme.flood: 낮은 땅부터 잠김) 마지막에는
 * 길 둔덕과 원 언저리만 남는다. 물 안에서는 걷기 {@link LateTune#TIDE_SLOW}% 느려지고 물밑 손이 발을 잡는다(초마다 몫, 잠깐 묶이고 피해). 물 안
 * 게이브는 빨라진다. 물이 바퀴 높이({@link LateTune#TIDE_WHEEL})를 넘으면 트럭이 초마다 깎이고, 트럭 동쪽 배수 손잡이(그 자리에 서 있기 채널)를 다
 * 돌리면 한동안 막는다. {@link LateTune#TIDE_SECONDS} 초를 버티면 물이 빠지며 끝(떼는 끝없이 채움).
 */
public final class Tide extends Late {

    private int level = -1;
    private double pump;
    private long pumpedUntil = -1, nextGrab;
    private int grabs, pumps;
    private double drained;

    public Tide(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_survive";
    }

    @Override
    protected boolean begin() {
        Theme th = theme();
        // 물 칸은 젖은 벌 땅에만 있다(Theme.flood 가 다른 땅에서는 아무 일 안 함)
        if (th == null || trip.act() != 2 && Plaza.groundAct() != 2) return false;
        level = -1;
        pump = 0;
        pumpedUntil = -1;
        nextGrab = game.now() + 40;
        grabs = pumps = 0;
        drained = 0;
        th.rain(Boolean.TRUE);
        th.storm(true);
        darken(0.3);
        return true;
    }

    @Override
    protected void end() {
        Theme th = theme();
        if (th != null) {
            th.flood(0, 3);
            th.rain(null);
            th.storm(false);
        }
        for (Player p : game.players.inPlay()) game.hud.meter(p, "sp_tide", -1, 1);
    }

    @Override
    public void plan(Director.Plan p) {
        scaleCounts(p, LateTune.TIDE_UNITS);
    }

    @Override
    protected double mulFor(String k) {
        return switch (k) {
            case "hp" -> LateTune.TIDE_HP;
            case "dmg" -> LateTune.TIDE_DMG;
            default -> 1;
        };
    }

    @Override
    protected boolean killAll() {
        return false;
    }

    private long dur() {
        return LateTune.TIDE_SECONDS * 20L;
    }

    @Override
    protected double frac() {
        return Math.max(0, 1 - (game.now() - openAt) / (double) dur());
    }

    @Override
    protected String text() {
        return Integer.toString((int) Math.ceil(Math.max(0, dur() - (game.now() - openAt)) / 20.0));
    }

    @Override
    protected boolean filling() {
        return false;
    }

    private double wet() {
        return level < 0 ? 0 : LateTune.TIDE_LEVELS[Math.min(level, LateTune.TIDE_LEVELS.length - 1)];
    }

    /** 배수 손잡이 자리(트럭 동쪽 옆구리). */
    private static Vector handle() {
        return new Vector(Truck.X1 + 1.2, Plaza.FLOOR_Y, Truck.OZ);
    }

    @Override
    protected void step(long t) {
        long now = game.now();
        Theme th = theme();
        // 물이 한 칸 오름
        int want = (int) Math.min(LateTune.TIDE_LEVELS.length - 1, t / LateTune.TIDE_STEP);
        if (want != level && th != null) {
            level = want;
            th.flood(wet(), 3);
            Props.soundAround(loc(new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5), 1), "director.flood", Sound.AMBIENT_UNDERWATER_ENTER, 1.4f, 0.8f, 0.6f, 160, -1);
            if (level > 0) notice("sp_tide_rise", 50);
        }
        // 물 안 게이브는 빨라짐(물밑에서 온 것들)
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.flying) continue;
            if (Plaza.wet((int) Math.floor(g.pos.getX()), (int) Math.floor(g.pos.getZ()))) g.speedBuff *= LateTune.TIDE_MOB_SPEED;
        }
        // 물 안 사람: 느려짐, 물밑 손
        boolean grabTick = now >= nextGrab;
        if (grabTick) nextGrab = now + 20;
        if (now % 10 == 0 || grabTick) {
            for (Player p : game.players.standing()) {
                Location l = p.getLocation();
                if (!Plaza.wet(l.getBlockX(), l.getBlockZ())) continue;
                game.players.slow(p, LateTune.TIDE_SLOW, 14, "trip_tide");
                if (grabTick && rnd.nextDouble() < LateTune.TIDE_GRAB * (0.5 + wet())) {
                    grabs++;
                    game.players.root(p, LateTune.TIDE_GRAB_TICKS, "trip_tide_grab");
                    game.players.hurt(p, LateTune.TIDE_GRAB_DMG, null, "cause_sp_tide");
                    game.world.spawnParticle(Particle.SPLASH, l.getX(), Plaza.FLOOR_Y + 0.2, l.getZ(), 24, 0.4, 0.1, 0.4, 0.1);
                    Props.soundAround(l, "gabe.g24.rumble", Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1f, 1f, 0.8f, 24, -1);
                    notice(p, "sp_tide_grab", 30);
                }
            }
        }
        // 배수 손잡이
        Vector h = handle();
        boolean near = false;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - h.getX(), dz = l.getZ() - h.getZ();
            if (dx * dx + dz * dz > LateTune.PUMP_R * LateTune.PUMP_R) continue;
            near = true;
            game.hud.meter(p, "sp_tide", pump / LateTune.PUMP_TICKS, 6);
        }
        if (near) {
            pump++;
            if (pump % 20 == 1) Props.soundAround(loc(h, 1), "gabe.g10.creak", Sound.BLOCK_GRINDSTONE_USE, 0.9f, 0.9f, 0.9f, 24, -1);
            if (pump >= LateTune.PUMP_TICKS) {
                pump = 0;
                pumps++;
                pumpedUntil = now + LateTune.PUMP_HOLD;
                game.world.spawnParticle(Particle.SPLASH, h.getX(), Plaza.FLOOR_Y + 1, h.getZ(), 60, 0.6, 0.6, 0.6, 0.3);
                Props.soundAround(loc(h, 1), "director.flood", Sound.ITEM_BUCKET_EMPTY, 1.2f, 1.2f, 1f, 40, -1);
            }
        } else pump = Math.max(0, pump - 0.5);
        // 바퀴가 잠기면 트럭이 깎임(퍼낸 동안은 안 깎임)
        Truck tr = truck();
        if (tr != null && wet() >= LateTune.TIDE_WHEEL && now >= pumpedUntil && now % 20 == 0) {
            drained += tr.hitFrac(LateTune.TIDE_TRUCK, null, "cause_sp_tide");
            if (now % 100 == 0) game.world.spawnParticle(Particle.BUBBLE_POP, Truck.OX, Plaza.FLOOR_Y + 0.6, Truck.OZ, 30, 1.6, 0.2, 3, 0.05);
        }
        stream(LateTune.STREAM_SHARE);
        if (t >= dur()) {
            if (th != null) th.flood(0, 2);
            win();
        }
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.tide", wet());
        out.put("sp.pump", pumpedUntil > game.now() ? 1.0 : pump / LateTune.PUMP_TICKS);
    }

    @Override
    protected String statusMore() {
        return String.format(java.util.Locale.ROOT, "tide=%d:%.2f wet=%d grabs=%d pump=%.0f pumps=%d pumped=%s drained=%.1f", level, wet(), Plaza.wetCount(), grabs, pump,
                pumps, pumpedUntil > game.now() ? "on" : "-", drained);
    }

    @Override
    protected String testMore(Player p, String sub, String[] a) {
        if (sub.equals("손잡이") && p != null) {
            Vector h = handle();
            p.teleport(new Location(game.world, h.getX(), Plaza.FLOOR_Y + 0.1, h.getZ(), 90, 10));
            return "손잡이로 감 " + statusMore();
        }
        if (sub.equals("물") && p != null) {
            // 가장 가까운 물 칸으로
            Location l = p.getLocation();
            for (int r = 1; r < 60; r++) for (int i = 0; i < 16; i++) {
                double a2 = i * Math.PI / 8;
                int x = (int) Math.floor(l.getX() + Math.cos(a2) * r), z = (int) Math.floor(l.getZ() + Math.sin(a2) * r);
                if (Plaza.wet(x, z)) {
                    p.teleport(new Location(game.world, x + 0.5, Plaza.FLOOR_Y + 0.1, z + 0.5, l.getYaw(), 10));
                    return "물로 감 " + x + " " + z;
                }
            }
            return "물 칸 없음";
        }
        return null;
    }
}
