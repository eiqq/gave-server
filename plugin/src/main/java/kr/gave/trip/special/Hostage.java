package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.Collections;
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
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.world.Plaza;

/**
 * 10 끌려간 동료: 라운드가 열리면 서 있는 사람 가운데 몇(사람 수 x HOST_SHARE, 1..HOST_MAX, 마지막 한 사람은 안 데려감)이 고치에 싸여 R 40 밖 세 방향에
 * 매달린다(갇힘, 값 없음). 고치마다 지키는 무리. 고치를 쏴서 찢으면 그 사람이 그 자리에서 풀려 싸운다. HOST_SECONDS 가 지나면 고치가 끌려가 그 사람은 죽음으로 친다
 * (꺼내는 값을 내야 돌아옴). 끝: 모두 풀리거나 끌려감.
 */
public final class Hostage extends Special {

    private static final String COCOON = "gabe/g26";

    private final class Cocoon implements Mobs.Shootable {
        final UUID who;
        final String name;
        final Vector at;
        double hp, max;
        ItemDisplay d;
        boolean done, saved;
        long dragAt = -1;

        Cocoon(Player p, Vector at, double hp) {
            this.who = p.getUniqueId();
            this.name = p.getName();
            this.at = at;
            this.hp = this.max = hp;
        }

        Vector mid() {
            return new Vector(at.getX(), Plaza.FLOOR_Y + 1.4, at.getZ());
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (done || dragAt >= 0) return Double.POSITIVE_INFINITY;
            Vector c = mid();
            double r = Tune.COCOON_R;
            double ox = c.getX() - o.getX(), oy = c.getY() - o.getY(), oz = c.getZ() - o.getZ();
            double t = ox * dir.getX() + oy * dir.getY() + oz * dir.getZ();
            double m2 = ox * ox + oy * oy + oz * oz - t * t;
            if (t < -r || m2 > r * r) return Double.POSITIVE_INFINITY;
            double hit = Math.max(0, t - Math.sqrt(r * r - m2));
            return hit <= max ? hit : Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean shot(Damage dmg) {
            if (done || dragAt >= 0) return false;
            hp -= Math.max(0, dmg.amount());
            if (dmg.by() != null) game.hud.hit(dmg.by(), false);
            if (game.now() % 3 == 0) sound(loc(at, 1.4), null, Sound.BLOCK_WOOL_BREAK, 1f, 0.7f, 24);
            if (hp <= 0) free(this, dmg.by());
            return false;
        }

        @Override
        public Vector center() {
            return mid();
        }

        @Override
        public boolean gone() {
            return done || !opened;
        }
    }

    private final List<Cocoon> cocoons = new ArrayList<>();
    private int saved, lost;
    private long dur;

    public Hostage(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_hunt";
    }

    @Override
    protected boolean begin() {
        ownEnd = true;
        cocoons.clear();
        saved = lost = 0;
        dur = Tune.HOST_SECONDS * 20L;
        List<Player> st = new ArrayList<>(standing());
        int n = (int) Math.round(st.size() * Tune.HOST_SHARE);
        n = Math.max(1, Math.min(Tune.HOST_MAX, n));
        n = Math.min(n, st.size() - 1);
        if (n <= 0) return false;
        Collections.shuffle(st, rnd);
        double base = rnd.nextDouble() * Math.PI * 2;
        double hp = Tune.act(Tune.COCOON_HP, act);
        int guards = Tune.GUARDS + act;
        Species gs = rounds.pick(s -> s.spawn == Species.Spawn.RING && s.band != kr.gave.mob.Band.SKY && s.threat >= 2 && s.threat <= 3 && !s.is(Role.BOSS) && !s.is(Role.BONUS));
        if (gs == null) gs = cheapRunner();
        for (int i = 0; i < n; i++) {
            Player p = st.get(i);
            double a = base + i * Math.PI * 2 / 3 + (rnd.nextDouble() - 0.5) * 0.3;
            double r = Tune.HOST_R0 + rnd.nextDouble() * (Tune.HOST_R1 - Tune.HOST_R0);
            Vector at = ring(a, r);
            Cocoon c = new Cocoon(p, at, hp);
            cocoons.add(c);
            p.teleport(new Location(game.world, at.getX(), Plaza.FLOOR_Y + 0.05, at.getZ(), p.getLocation().getYaw(), 30));
            game.players.trap(p, null);
            c.d = keep(spawnCocoon(at));
            shootable(c);
            for (int k = 0; k < guards && gs != null; k++) {
                double b = rnd.nextDouble() * Math.PI * 2, rr = 2.5 + rnd.nextDouble() * 3;
                spawn(gs, new Vector(at.getX() + Math.cos(b) * rr, 0, at.getZ() + Math.sin(b) * rr), false);
            }
            game.hud.point(null, c.mid(), 120);
            say(p, "sa_host_me", 80);
        }
        alert("sa_host_taken", 3, 80);
        sound(truckLoc(2), "trip.horn_broken", Sound.ENTITY_MINECART_INSIDE, 1.2f, 0.5f, 64);
        rounds.goals().timer(Tune.HOST_SECONDS);
        streaming = true;
        return true;
    }

    private ItemDisplay spawnCocoon(Vector at) {
        ItemStack it;
        if (Look.has(COCOON)) {
            LookState ls = new LookState();
            ls.palette = Look.PAL_PALE;
            ls.rate = 0.3;
            it = Body.item(COCOON, "", ls, game.worldTime());
        } else it = ItemStack.of(Material.COBWEB);
        float s = (float) (2.2 * Look.scale(COCOON));
        Location l = loc(at, 1.4);
        return game.world.spawn(l, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(9, 9));
            d.setTransformationMatrix(new Matrix4f().scale(s).rotateY((float) Math.PI));
            d.setViewRange(2.4f);
            d.setTeleportDuration(2);
        });
    }

    /** 고치가 찢어짐: 그 사람이 그 자리에서 풀려 싸운다. */
    private void free(Cocoon c, Player by) {
        c.done = c.saved = true;
        saved++;
        drop(c.d);
        c.d = null;
        unshootable(c);
        Player p = Bukkit.getPlayer(c.who);
        if (p != null && game.players.get(p).life == Life.TRAPPED) {
            game.players.free(p);
            Location l = new Location(game.world, c.at.getX(), Plaza.FLOOR_Y + 0.05, c.at.getZ());
            l.setDirection(center().subtract(c.at).setY(0));
            p.teleport(l);
            say(p, "sa_host_free_me", 50);
        }
        game.shotFx.pop(loc(c.at, 1.4));
        say("sa_host_saved", 50);
        trip.log("끌려간 동료 풀림 " + c.name + (by == null ? "" : " by " + by.getName()));
    }

    @Override
    protected void step(long now) {
        long t = now - openAt;
        int open = 0;
        for (Cocoon c : cocoons) {
            if (c.done) continue;
            Player p = Bukkit.getPlayer(c.who);
            PState s = p == null ? null : game.players.get(p);
            if (p == null || s == null || s.life != Life.TRAPPED) {
                // 나갔거나 다른 길로 풀림
                c.done = true;
                drop(c.d);
                unshootable(c);
                continue;
            }
            open++;
            if (c.dragAt < 0) {
                if (t % 40 == 0) game.hud.point(null, c.mid(), 40);
                if (c.d != null && c.d.isValid() && t % 4 == 0) {
                    Location l = loc(c.at, 1.4 + 0.1 * Math.sin(now * 0.08));
                    l.setYaw((float) (Math.sin(now * 0.05) * 25));
                    c.d.teleport(l);
                }
                if (t >= dur) {
                    c.dragAt = now;
                    sound(loc(c.at, 1), null, Sound.ENTITY_WARDEN_DIG, 1.4f, 0.6f, 64);
                }
            } else {
                // 끌려감: 바깥으로 미끄러지다 사라짐
                long k = now - c.dragAt;
                Vector out = c.at.clone().subtract(center()).setY(0);
                if (out.lengthSquared() > 1e-6) out.normalize();
                if (c.d != null && c.d.isValid()) c.d.teleport(loc(c.at.clone().add(out.multiply(k * 0.5)), 1.4 - k * 0.03));
                if (k >= 40) {
                    c.done = true;
                    lost++;
                    drop(c.d);
                    c.d = null;
                    unshootable(c);
                    game.players.trapEnd(p);
                    say("sa_host_lost", 60);
                    trip.log("끌려간 동료 놓침 " + c.name);
                }
            }
        }
        if (open == 0) finish(lost == 0 && saved > 0);
    }

    @Override
    protected void end() {
        cocoons.clear();
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        int left = 0;
        for (Cocoon c : cocoons) if (!c.done) left++;
        out.put("sp.left", left);
        for (Cocoon c : cocoons) if (c.who.equals(p.getUniqueId()) && !c.done) out.put("sp.cocoon", Math.max(0, c.hp / c.max));
    }

    @Override
    protected String detail() {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "taken=%d saved=%d lost=%d", cocoons.size(), saved, lost));
        for (Cocoon c : cocoons) sb.append(String.format(Locale.ROOT, " %s:%.0f%s", c.name, c.hp, c.done ? c.saved ? "(ok)" : "(x)" : c.dragAt >= 0 ? "(drag)" : ""));
        return sb.toString();
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        switch (what) {
            case "찢기" -> {
                for (Cocoon c : cocoons) if (!c.done && c.dragAt < 0) {
                    free(c, p);
                    return "찢음 " + c.name;
                }
                return "고치 없음";
            }
            case "가기" -> {
                if (p == null) return "사람 없음";
                for (Cocoon c : cocoons) if (!c.done) {
                    Vector in = center().subtract(c.at).setY(0).normalize().multiply(6);
                    Location l = new Location(game.world, c.at.getX() + in.getX(), Plaza.FLOOR_Y + 0.05, c.at.getZ() + in.getZ());
                    l.setDirection(c.mid().subtract(l.toVector().add(new Vector(0, 1.6, 0))));
                    p.teleport(l);
                    return "고치 앞";
                }
                return "고치 없음";
            }
            case "시간" -> {
                openAt = game.now() - dur + 20;
                return "곧 끌려감";
            }
            default -> {
                return null;
            }
        }
    }
}
