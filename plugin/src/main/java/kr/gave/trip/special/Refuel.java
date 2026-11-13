package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.Mobs;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.trip.Rounds;
import kr.gave.trip.Scenery;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 3 주유: 기름통 넷이 사람의 끝 근처(R 34~44) 네 방향에 놓인다. 좌클릭(돕기)으로 들면 머리 위에 지고(못 쏨, 느려짐, 둘레 게이브가 그 사람만 노림)
 * 트럭 곁에서 3초 머물면 붓는다. 든 사람이 쓰러지면 그 자리에 떨어진다. 통을 쏘면 터진다(게이브만 다침, 그 통은 20초 뒤 제자리에 다시 놓임).
 * 끝: 넷을 다 부음.
 */
public final class Refuel extends Special {

    private final class Drum implements Mobs.Shootable {
        final int index;
        final Vector home;
        Vector at;
        UUID carrier;
        boolean poured, gone;
        long respawnAt = -1, pourAt = -1;
        ItemDisplay d;

        Drum(int index, Vector home) {
            this.index = index;
            this.home = home;
            this.at = home.clone();
        }

        Vector centerNow() {
            Player p = carrier == null ? null : Bukkit.getPlayer(carrier);
            if (p != null) return p.getLocation().toVector().add(new Vector(0, 2.4, 0));
            return new Vector(at.getX(), Plaza.FLOOR_Y + 0.55, at.getZ());
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (gone || poured) return Double.POSITIVE_INFINITY;
            Vector c = centerNow();
            double r = Tune.FUEL_HIT_R;
            double ox = c.getX() - o.getX(), oy = c.getY() - o.getY(), oz = c.getZ() - o.getZ();
            double t = ox * dir.getX() + oy * dir.getY() + oz * dir.getZ();
            double m2 = ox * ox + oy * oy + oz * oz - t * t;
            if (t < -r || m2 > r * r) return Double.POSITIVE_INFINITY;
            double hit = Math.max(0, t - Math.sqrt(r * r - m2));
            return hit <= max ? hit : Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean shot(Damage dmg) {
            if (gone || poured) return false;
            boom(this, dmg.by());
            return false;
        }

        @Override
        public Vector center() {
            return centerNow();
        }

        @Override
        public boolean gone() {
            return !opened || poured;
        }
    }

    private final List<Drum> drums = new ArrayList<>();
    private int poured, booms, pickups;

    public Refuel(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_collect";
    }

    @Override
    protected boolean begin() {
        ownEnd = true;
        drums.clear();
        poured = booms = pickups = 0;
        double base = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < Tune.FUEL_N; i++) {
            double a = base + i * Math.PI * 2 / Tune.FUEL_N + (rnd.nextDouble() - 0.5) * 0.4;
            double r = Tune.FUEL_R0 + rnd.nextDouble() * (Tune.FUEL_R1 - Tune.FUEL_R0);
            Drum d = new Drum(i, ring(a, r));
            drums.add(d);
            place(d);
            shootable(d);
            game.hud.point(null, d.home.clone().setY(Plaza.FLOOR_Y + 1.2), 100);
        }
        rounds.goals().count(0, Tune.FUEL_N);
        streaming = true;
        return true;
    }

    private ItemStack drumItem() {
        if (Scenery.has(game, "trip/barrel")) return Scenery.item("trip/barrel");
        return ItemStack.of(Material.BARREL);
    }

    private void place(Drum d) {
        drop(d.d);
        Location l = loc(d.at, 0.5);
        l.setYaw(rnd.nextFloat() * 360);
        ItemStack it = drumItem();
        d.d = keep(game.world.spawn(l, ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.setItemStack(it);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            x.setBillboard(Display.Billboard.FIXED);
            x.setBrightness(new Display.Brightness(12, 12));
            x.setTransformationMatrix(new Matrix4f().scale(1.0f));
            x.setViewRange(1.6f);
            x.setTeleportDuration(2);
        }));
    }

    /** 좌클릭(돕기): 가까운 통을 듦. */
    @Override
    public boolean help(Player p) {
        if (!opened || ended || game.state != Game.State.WAVE || !alive(p)) return false;
        for (Drum d : drums) if (p.getUniqueId().equals(d.carrier)) return false;
        Location l = p.getLocation();
        Drum best = null;
        double bd = Tune.FUEL_PICK_R * Tune.FUEL_PICK_R;
        for (Drum d : drums) {
            if (d.poured || d.gone || d.carrier != null) continue;
            double dx = d.at.getX() - l.getX(), dz = d.at.getZ() - l.getZ(), q = dx * dx + dz * dz;
            if (q <= bd) {
                bd = q;
                best = d;
            }
        }
        if (best == null) return false;
        pick(best, p);
        return true;
    }

    private void pick(Drum d, Player p) {
        d.carrier = p.getUniqueId();
        d.pourAt = -1;
        pickups++;
        kr.gave.Props.sound(p, null, Sound.ENTITY_ITEM_PICKUP, 1f, 0.1f, 0.7f);
        hint(p, "sa_hint_pour", 60);
    }

    @Override
    protected void step(long now) {
        for (Drum d : drums) {
            if (d.poured) continue;
            if (d.gone) {
                if (d.respawnAt >= 0 && now >= d.respawnAt) {
                    d.gone = false;
                    d.respawnAt = -1;
                    d.at = d.home.clone();
                    place(d);
                    game.hud.point(null, d.home.clone().setY(Plaza.FLOOR_Y + 1.2), 60);
                }
                continue;
            }
            if (d.carrier != null) carry(d, now);
            else if (d.d != null && d.d.isValid() && now % 2 == 0) {
                Location l = loc(d.at, 0.5 + 0.06 * Math.sin(now * 0.12 + d.index));
                l.setYaw((now * 3 + d.index * 90) % 360);
                d.d.teleport(l);
            }
            if (now % 60 == d.index * 7 % 60 && d.carrier == null) game.hud.point(null, d.at.clone().setY(Plaza.FLOOR_Y + 1.2), 40);
        }
        rounds.goals().count(poured, Tune.FUEL_N);
        if (poured >= Tune.FUEL_N) finish(true);
    }

    private void carry(Drum d, long now) {
        Player p = Bukkit.getPlayer(d.carrier);
        PState s = p == null ? null : game.players.get(p);
        if (p == null || s == null || s.life != Life.ALIVE) {
            // 든 사람이 쓰러지거나 나가면 그 자리에 떨어짐
            if (p != null) d.at = p.getLocation().toVector().setY(Plaza.FLOOR_Y);
            d.carrier = null;
            d.pourAt = -1;
            unforce(d);
            place(d);
            return;
        }
        Location l = p.getLocation();
        d.at = l.toVector().setY(Plaza.FLOOR_Y);
        if (d.d != null && d.d.isValid()) d.d.teleport(l.clone().add(0, 2.4, 0));
        game.players.slow(p, Tune.FUEL_SLOW, 6, "sp_fuel");
        s.gunLockUntil = Math.max(s.gunLockUntil, now + 4);
        if (now % 20 == 0) force(d, p, Tune.FUEL_FORCE_R, 30);
        if (Truck.dist(l.getX(), l.getZ()) <= Tune.FUEL_DELIVER_R) {
            if (d.pourAt < 0) {
                d.pourAt = now;
                sound(l, "trip.pour", Sound.ITEM_BUCKET_EMPTY, 1.2f, 0.8f, 32);
            }
            double f = (now - d.pourAt) / (double) Tune.FUEL_POUR_TICKS;
            if (now % 4 == 0) meter(p, "sa_gl_pour", f, 8);
            if (f >= 1) {
                d.poured = true;
                d.carrier = null;
                unforce(d);
                drop(d.d);
                d.d = null;
                poured++;
                sound(l, "trip.truck_repair", Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f, 48);
                say("sa_fuel_" + Math.min(4, poured), 40);
            }
        } else {
            d.pourAt = -1;
            if (now % 10 == 0) meter(p, "sa_gl_fuel", (double) poured / Tune.FUEL_N, 14);
        }
    }

    private void boom(Drum d, Player by) {
        Location at = d.centerNow().toLocation(game.world);
        int n = blast(at, Tune.FUEL_BOOM_R, Tune.FUEL_BOOM_DMG, by);
        booms++;
        if (d.carrier != null) unforce(d);
        d.carrier = null;
        d.pourAt = -1;
        d.gone = true;
        d.respawnAt = game.now() + Tune.FUEL_RESPAWN;
        drop(d.d);
        d.d = null;
        say("sa_fuel_boom", 40);
        trip.log("주유 통 터짐 맞은 " + n + (by == null ? "" : " by " + by.getName()));
    }

    @Override
    protected void finished(boolean ok) {
        for (Drum d : drums) if (d.carrier != null) unforce(d);
    }

    @Override
    protected void end() {
        drums.clear();
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.fuel", poured);
        for (Drum d : drums) if (p.getUniqueId().equals(d.carrier)) out.put("sp.carry", true);
    }

    @Override
    protected String detail() {
        int carried = 0, gone = 0;
        for (Drum d : drums) {
            if (d.carrier != null) carried++;
            if (d.gone) gone++;
        }
        return String.format(Locale.ROOT, "fuel=%d/%d carried=%d gone=%d booms=%d picks=%d", poured, Tune.FUEL_N, carried, gone, booms, pickups);
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        switch (what) {
            case "가기" -> {
                if (p == null) return "사람 없음";
                for (Drum d : drums) if (!d.poured && !d.gone && d.carrier == null) {
                    p.teleport(new Location(game.world, d.at.getX() + 0.6, Plaza.FLOOR_Y + 0.05, d.at.getZ(), p.getLocation().getYaw(), 20));
                    return "통 곁으로";
                }
                return "갈 통 없음";
            }
            case "줍기" -> {
                if (p == null) return "사람 없음";
                for (Drum d : drums) if (!d.poured && !d.gone && d.carrier == null) {
                    p.teleport(new Location(game.world, d.at.getX() + 0.6, Plaza.FLOOR_Y + 0.05, d.at.getZ(), p.getLocation().getYaw(), 20));
                    return "집음=" + help(p);
                }
                return "집을 통 없음";
            }
            case "트럭" -> {
                if (p == null) return "사람 없음";
                Vector c = Truck.center();
                p.teleport(new Location(game.world, c.getX(), Plaza.FLOOR_Y + 0.05, Truck.Z1 + 1.2, 180f, 10f));
                return "트럭 뒤";
            }
            case "붓기" -> {
                for (Drum d : drums) if (!d.poured) {
                    d.poured = true;
                    if (d.carrier != null) unforce(d);
                    d.carrier = null;
                    drop(d.d);
                    d.d = null;
                    poured++;
                    return "부음 " + poured;
                }
                return "부을 통 없음";
            }
            case "터뜨리기" -> {
                for (Drum d : drums) if (!d.poured && !d.gone) {
                    boom(d, p);
                    return "터뜨림 " + d.index;
                }
                return "터뜨릴 통 없음";
            }
            default -> {
                return null;
            }
        }
    }
}
