package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 7 숨죽이기: 하늘의 거대한 것이 머리 위를 낮고 느리게 지나간다(HUSH_SECONDS). 총소리, 달리기, 폭발이 소음 게이지(팀 공용)를 채우고 웅크리면 빨리 준다.
 * 가득 차면 거대한 것이 내려와 가장 시끄러웠던 사람을 채 가고(갇힘: 라운드가 끝나면 죽음) 트럭을 한 번 친다. 그동안 약한 떼가 소리 없이 걸어온다.
 * 끝: 그림자가 지나감.
 */
public final class Hush extends Special {

    private static final String GIANT = "gabe/g01";

    private long dur;
    private double noise;
    private final Map<UUID, Double> loud = new HashMap<>();
    private final List<UUID> taken = new ArrayList<>();
    private ItemDisplay giant;
    private double ax, az, bx, bz;
    private long diveUntil = -1;
    private int fills;

    public Hush(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_survive";
    }

    @Override
    public void plan(Director.Plan p) {
        scaleUnits(p, Tune.HUSH_UNITS);
        p.silent = true;
    }

    @Override
    protected double mulFor(String k) {
        return k.equals("hp") && opened && !ended ? Tune.HUSH_HP : 1;
    }

    @Override
    protected boolean begin() {
        ownEnd = true;
        dur = Tune.act(Tune.HUSH_SECONDS, act) * 20L;
        noise = 0;
        loud.clear();
        taken.clear();
        fills = 0;
        diveUntil = -1;
        double a = rnd.nextDouble() * Math.PI * 2, side = (rnd.nextDouble() - 0.5) * 16;
        double ux = Math.cos(a), uz = Math.sin(a);
        ax = Plaza.CX + 0.5 - ux * 110 - uz * side;
        az = Plaza.CZ + 0.5 - uz * 110 + ux * side;
        bx = Plaza.CX + 0.5 + ux * 110 - uz * side;
        bz = Plaza.CZ + 0.5 + uz * 110 + ux * side;
        giant = null;
        if (Look.has(GIANT)) {
            LookState ls = new LookState();
            ls.rate = 0.2;
            ls.phase = rnd.nextInt(80);
            ls.open = true;
            ItemStack it = Body.item(GIANT, "", ls, game.worldTime());
            Location at = new Location(game.world, ax, alt(0), az);
            giant = keep(game.world.spawn(at, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setItemStack(it);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setBillboard(Display.Billboard.FIXED);
                e.setBrightness(new Display.Brightness(5, 6));
                e.setViewRange(4f);
                e.setDisplayWidth(0);
                e.setDisplayHeight(0);
                e.setTeleportDuration(20);
                e.setInterpolationDuration(20);
                e.setTransformationMatrix(matrix(at));
            }));
            Props.soundOn(giant, "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f, 0.5f);
        }
        rounds.goals().timer((int) (dur / 20));
        dimWant = 0.35;
        say("sa_hint_hush", 60);
        return true;
    }

    private double alt(double t) {
        double mid = 1 - 4 * (t - 0.5) * (t - 0.5);
        return Plaza.FLOOR_Y + Tune.GIANT_ALT + 16 * (1 - mid);
    }

    private Matrix4f matrix(Location at) {
        double dx = Plaza.CX + 0.5 - at.getX(), dz = Plaza.CZ + 0.5 - at.getZ(), dy = Plaza.FLOOR_Y + 1.6 - at.getY();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.hypot(dx, dz)));
        float s = (float) Tune.GIANT_SCALE * Look.scale(GIANT);
        return new Matrix4f().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch)).scale(s).rotateY((float) Math.PI);
    }

    @Override
    protected void step(long now) {
        long t = now - openAt;
        double f = Math.min(1, t / (double) dur);
        // 거대한 것: 20틱마다 다음 자리로(보간), 내려오는 동안 낮게
        if (giant != null && giant.isValid() && t % 20 == 0) {
            double g = Math.min(1, (t + 20) / (double) dur);
            double y = alt(g) - (now < diveUntil ? Tune.GIANT_ALT * 0.55 : 0);
            Location at = new Location(game.world, ax + (bx - ax) * g, y, az + (bz - az) * g);
            giant.teleport(at);
            giant.setInterpolationDelay(0);
            giant.setInterpolationDuration(20);
            giant.setTransformationMatrix(matrix(at));
            if (t % 80 == 0) Props.soundAround(at, null, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 0.15f, 0.4f, 160, -1);
            if (t == (dur / 2) / 20 * 20) Props.soundOn(giant, "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.9f, 0.45f);
        }
        // 소음: 서 있는 사람 수로 나눈 팀 평균(혼자면 예전 그대로: 30명이 저마다 쏴도 한 사람이 쏠 때와 같은 빠르기로 찬다). 웅크림도 웅크린 몫으로.
        // 거대한 것이 내려와 있는 동안(diveUntil)은 안 찬다(가득 찬 바로 뒤에 다시 가득 차지 않게)
        List<Player> st = standing();
        double per = 1.0 / Math.max(1, st.size());
        boolean diving = now < diveUntil;
        int crouch = 0;
        for (Player p : st) {
            int shots = game.mobs.shotsBy(p, 1);
            double add = shots * Tune.NOISE_SHOT + (p.isSprinting() ? Tune.NOISE_SPRINT : 0);
            if (p.isSneaking()) crouch++;
            if (add > 0) {
                if (!diving) noise += add * per;
                loud.merge(p.getUniqueId(), add, Double::sum);
            }
        }
        if (!diving) noise += game.mobs.blastsNear(null, 0, 1) * Tune.NOISE_BLAST * per;
        noise = Math.max(0, noise - (Tune.NOISE_DECAY + crouch * per * Tune.NOISE_CROUCH) / 20.0);
        if (now % 20 == 0) loud.replaceAll((k, v) -> v * 0.9);
        if (now % 5 == 0) for (Player p : game.players.inPlay()) meter(p, "sa_gl_noise", noise / Tune.NOISE_MAX, 10);
        if (noise >= Tune.NOISE_MAX) snatch(now);
        if (f >= 1) {
            say("sa_hush_pass", 60);
            finish(true);
        }
    }

    /** 가득 참: 가장 시끄러웠던 사람을 채 가고 트럭을 친다(마지막 서 있는 사람은 안 채 감: 트럭만). */
    private void snatch(long now) {
        fills++;
        noise = Tune.NOISE_MAX * 0.3;
        diveUntil = now + 60;
        Player who = null;
        double best = -1;
        List<Player> st = standing();
        for (Player p : st) {
            double v = loud.getOrDefault(p.getUniqueId(), 0.0);
            if (v > best) {
                best = v;
                who = p;
            }
        }
        if (who != null && st.size() > 1) {
            Location l = who.getLocation();
            game.shotFx.explosion(l.clone().add(0, 1, 0));
            game.players.trap(who, null);
            taken.add(who.getUniqueId());
            loud.remove(who.getUniqueId());
            say(who, "sa_hush_taken_me", 60);
        }
        alert("sa_hush_taken", 4, 60);
        Truck tr = trip.truck();
        if (tr != null) tr.hitFrac(Tune.NOISE_TRUCK, null, "cause_sp_hush");
        if (giant != null) Props.soundOn(giant, "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1.4f, 0.8f, 0.4f);
        trip.log("숨죽이기 가득 참 " + fills + (who == null ? "" : " 채 감 " + who.getName()));
    }

    @Override
    protected void end() {
        // 웨이브 중에 닫히면(시험 건너뛰기, '특수 x 끝') 채 간 사람을 풀어 준다: 먹은 개체가 없는 갇힘이라 쉬는 시간 부활 말고는 풀 길이 없다
        if (game.state == Game.State.WAVE) for (UUID id : taken) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null) game.players.free(p);
        }
        loud.clear();
        taken.clear();
        giant = null;
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.noise", Math.min(1, noise / Tune.NOISE_MAX));
    }

    @Override
    protected String detail() {
        return String.format(Locale.ROOT, "noise=%.0f fills=%d taken=%d giant=%s", noise, fills, taken.size(), giant != null && giant.isValid());
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        switch (what) {
            case "소음" -> {
                noise = a.length > 4 ? Double.parseDouble(a[4]) : Tune.NOISE_MAX;
                if (p != null) loud.merge(p.getUniqueId(), 50.0, Double::sum);
                return "소음 " + noise;
            }
            case "거의" -> {
                openAt = game.now() - (long) (dur * 0.95);
                return "거의 지나감";
            }
            default -> {
                return null;
            }
        }
    }
}
