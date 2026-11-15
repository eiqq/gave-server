package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Band;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.trip.Rounds;
import kr.gave.trip.Stream;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 1 추격전(원정.md 5.4절): 모두 트럭 뒤 판(트레일러 둘, 폭 5 길이 22) 위에 탄다. 사람도 트럭도 옮기지 않고 세상이 뒤로 흐른다(Stream).
 * 게이브는 뒤쪽 호와 양옆에서만 나와 판을 따라잡고, 판 옆면에 닿은 땅 몹은 매달려(트럭이 깎임) 2초 뒤 기어오른다. 쏘면 떨어져 뒤로 흘러간다.
 * 하늘 종에게 채여 판 밖으로 끌려간 사람은 갇힘: 끈 게이브를 6초 안에 잡으면 돌아오고 아니면 죽음. 15초마다 길 사건(굽은 길, 얼굴 벽, 끊긴 다리, 좁은 굴).
 * 끝: 거리 막대가 다 참(100초, 막마다 길어짐). 다 달리면 사건 chaseDone(보스 길목 '질주' 보상).
 *
 * <p>이어받기(가로막힌 길 Ram, 마지막 질주 LastRun 같은 것): {@link #duration()}(달리는 틱), {@link #pickEvent(List)}(다음 길 사건),
 * {@link #startEvent}, {@link #runEvent}, {@link #stopEvent}(사건 키를 더할 때는 super 를 먼저/나중에), {@link #spawnWall(int, double)}(얼굴 벽 한 겹),
 * {@link #wallHit(int, int)}(벽이 트럭에 닿을 때), {@link #ran(double)}(매 틱 달린 몫), {@link #beginMore()}. 판 좌표 px0, px1, pz0, pz1 과 흐름 stream 은 protected.
 */
public class Chase extends Special {

    protected static final String[] EVENTS = {"bend", "wall", "gap", "tunnel"};

    private final class Hang {
        long since;
        double hp;
        boolean done;
    }

    protected Stream stream;
    protected long dur;
    protected double px0, px1, pz0, pz1;
    private final Map<Gabe, Hang> hangs = new IdentityHashMap<>();
    /** 떨어뜨린(쏴서 떨군, 기어오른) 개체: 다시 매달리지 않는 틱. */
    private final Map<Gabe, Long> noHang = new IdentityHashMap<>();
    /** 떨어진 사람 → 끈 개체, 떨어진 틱. */
    private final Map<UUID, Object[]> fallen = new HashMap<>();
    protected long nextEvent, eventAt = -1, eventEnd = -1;
    protected String event, lastEvent;
    protected int bendSide;
    protected final List<Gabe> wall = new ArrayList<>();
    protected int wallN, wallHits, hangHits, falls, climbs, drops;
    protected double truckDealt;

    public Chase(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_dist";
    }

    @Override
    public void plan(Director.Plan p) {
        scaleUnits(p, Tune.CHASE_UNITS);
        p.lean = -1;
        p.leanCounts.clear();
        p.leanExtra = 0;
    }

    @Override
    protected boolean begin() {
        ownEnd = true;
        dur = duration();
        px0 = Truck.OX - Tune.PLAT_HALF;
        px1 = Truck.OX + Tune.PLAT_HALF;
        pz0 = Truck.Z1 + 0.3;
        pz1 = Truck.Z1 + Tune.PLAT_LEN;
        hangs.clear();
        noHang.clear();
        fallen.clear();
        wall.clear();
        event = lastEvent = null;
        eventAt = eventEnd = -1;
        nextEvent = openAt + 200;
        wallN = wallHits = hangHits = falls = climbs = drops = 0;
        truckDealt = 0;
        stream = new Stream(game);
        stream.span = 26;
        stream.bend = 0;
        stream.mark(trip.stagesLeft(), "launch");
        stream.start(Tune.CHASE_SPEED, 40);
        fog(Tune.CHASE_FOG);
        cue("chase", (int) dur + 200);
        trailers();
        board();
        rounds.goals().custom(icon(), 0, "0%");
        streaming = true;
        sound(truckLoc(1.5), "trip.engine_rev", Sound.ENTITY_MINECART_RIDING, 1.6f, 0.7f, 64);
        sound(truckLoc(1.5), "trip.horn", Sound.ITEM_GOAT_HORN_SOUND_2, 2f, 0.8f, 80);
        beginMore();
        return true;
    }

    /** 달리는 틱(이어받아 바꿈). */
    protected long duration() {
        return Tune.act(Tune.CHASE_SECONDS, act) * 20L;
    }

    /** 연 뒤 덧붙임(이어받아 씀). */
    protected void beginMore() {
    }

    /** 매 틱 달린 몫 f(0..1)(이어받아 씀: 지평선 그림자 같은 것). */
    protected void ran(double f) {
    }

    /** 다음 길 사건(c = 지난 것을 뺀 후보). */
    protected String pickEvent(List<String> c) {
        return c.get(rnd.nextInt(c.size()));
    }

    /** 트레일러 둘(팩에 trip/trailer 가 있으면 그것, 없으면 판 양옆 난간). 바닥이 땅에 붙게 낮춤. */
    private void trailers() {
        double len = pz1 - pz0;
        if (Scenery_has("trip/trailer")) {
            // MrCrayfish 트레일러(바닥 판 24 x 32 px, 윗면 y 2 px, 연결 고리 +Z): 판 크기로 축마다 늘리고, 고리가 트럭(북)을 보게 반 바퀴, 바닥 윗면을 땅에 붙임
            float sx = (float) ((px1 - px0) / 1.5), sz = (float) (len / 2 / 2.0), sy = (float) Tune.TRAILER_SY;
            for (int i = 0; i < 2; i++) {
                double z = pz0 + len * (i + 0.5) / 2;
                Location at = new Location(game.world, Truck.OX, Plaza.FLOOR_Y + 0.03 + (8 - 2) / 16.0 * sy, z);
                org.bukkit.inventory.ItemStack it = kr.gave.trip.Scenery.item("trip/trailer");
                org.joml.Matrix4f m = new org.joml.Matrix4f().rotateY((float) Math.toRadians(Tune.TRAILER_YAW)).scale(sx, sy, sz);
                keep(game.world.spawn(at, org.bukkit.entity.ItemDisplay.class, x -> {
                    x.setPersistent(false);
                    x.setItemStack(it);
                    x.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);
                    x.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                    x.setViewRange(1.6f);
                    x.setTransformationMatrix(m);
                }));
            }
            return;
        }
        if (!Scenery_has("trip/guardrail")) return;
        for (double z = pz0 + 1; z <= pz1 - 0.5; z += 2) {
            prop("trip/guardrail", new Location(game.world, px0 - 0.2, Plaza.FLOOR_Y + 0.5, z), 90f, 1f);
            prop("trip/guardrail", new Location(game.world, px1 + 0.2, Plaza.FLOOR_Y + 0.5, z), -90f, 1f);
        }
    }

    private boolean Scenery_has(String model) {
        return kr.gave.trip.Scenery.has(game, model);
    }

    /** 서 있는 사람을 판 위로(뒤를 보게). */
    private void board() {
        List<Player> ps = standing();
        // 한 줄 자리 수는 판 폭에서(폭 9 면 다섯): 판 오른쪽 절반도 처음부터 쓴다
        int i = 0, cols = Math.max(1, (int) ((px1 - px0 - 1.4) / 1.8) + 1);
        for (Player p : ps) {
            double x = px0 + 0.7 + (i % cols) * 1.8, z = pz0 + 1.2 + (i / cols) * 1.6;
            if (z > pz1 - 0.5) z = pz0 + 1.2 + rnd.nextDouble() * (pz1 - pz0 - 2);
            p.teleport(new Location(game.world, x, Plaza.FLOOR_Y + 0.05, z, 0f, 5f));
            i++;
        }
    }

    @Override
    protected void spawned(Gabe g) {
        if (g.sp.is(Role.BOSS) || g.sp.is(Role.BONUS) || g.sp == Species.G43) return;
        if (g.sp.spawn != Species.Spawn.RING && g.sp.spawn != Species.Spawn.SKY) return;
        double a, r;
        if (g.flying) {
            // 하늘 종: 옆에서 나란히
            a = (rnd.nextBoolean() ? 0 : Math.PI) + (rnd.nextDouble() - 0.5) * Math.toRadians(40);
            r = 22 + rnd.nextDouble() * 6;
        } else if (rnd.nextDouble() < 0.7) {
            a = Math.PI / 2 + (rnd.nextDouble() - 0.5) * 2 * Math.toRadians(Tune.CHASE_ARC);
            r = Tune.CHASE_R0 + rnd.nextDouble() * (Tune.CHASE_R1 - Tune.CHASE_R0);
        } else {
            a = (rnd.nextBoolean() ? 0 : Math.PI) + (rnd.nextDouble() - 0.5) * Math.toRadians(30);
            r = Tune.CHASE_R0 - 4 + rnd.nextDouble() * 4;
        }
        Vector to = ring(a, r);
        // 판 뒤쪽 끝을 기준으로(트럭 가운데가 아니라 판 가운데에서)
        to.setZ(to.getZ() + (pz0 + pz1) / 2 - Plaza.CZ - 0.5);
        move(g, to);
    }

    @Override
    protected void step(long now) {
        long t = now - openAt;
        double f = Math.min(1, t / (double) dur);
        rounds.goals().custom(icon(), f, Math.round(f * 100) + "%");
        // 달리는 동안 지평선 그림자 숨김(매 틱 1초씩 늘림: 끝나면 저절로 풀림)
        game.horizon.hide(20);
        stream.tick();
        // 판 위 가둠, 흔들림
        for (Player p : game.players.inPlay()) {
            confine(p, px0, px1, pz0, pz1);
            if (alive(p) && t % 6 == 0) Guns.kick(p, 0f, (t / 6) % 2 == 0 ? 0.15f : -0.15f);
        }
        mobs(now);
        fallTick(now);
        eventTick(now, t);
        ran(f);
        if (f >= 1) {
            trip.fire("chaseDone", null);
            finish(true);
        }
    }

    @Override
    protected void after(long now) {
        if (stream != null && stream.running()) stream.tick();
    }

    @Override
    protected void finished(boolean ok) {
        if (stream != null) stream.speed(0, Rounds.Tune.RETREAT_TICKS);
        sound(truckLoc(1.5), "trip.brake", Sound.ENTITY_MINECART_INSIDE, 1.2f, 0.6f, 64);
        for (Gabe g : hangs.keySet()) game.mobs.lure(g, g.pos.clone().add(new Vector(0, 0, 30)), 60);
        hangs.clear();
    }

    @Override
    protected void end() {
        if (stream != null) stream.stop();
        stream = null;
        hangs.clear();
        noHang.clear();
        fallen.clear();
        wall.clear();
    }

    // ------------------------------------------------------------------ 게이브: 빠르게, 매달림

    private boolean onPlatform(Vector v, double pad) {
        return v.getX() >= px0 - pad && v.getX() <= px1 + pad && v.getZ() >= pz0 - pad && v.getZ() <= pz1 + pad;
    }

    private void mobs(long now) {
        Truck tr = trip.truck();
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.sp.is(Role.BOSS)) continue;
            if (!g.flying) g.speedBuff *= Tune.CHASE_MOB_SPEED;
            if (g.flying || wall.contains(g) || hangs.containsKey(g)) continue;
            Long nh = noHang.get(g);
            if (nh != null && nh > now) continue;
            // 판 바깥 변 HANG_EDGE 안에 들어온 땅 몹 = 매달림
            if (onPlatform(g.pos, Tune.HANG_EDGE) && !onPlatform(g.pos, 0) && hangs.size() < Tune.HANG_SLOTS && g.sp.band != Band.SKY) {
                Hang h = new Hang();
                h.since = now;
                h.hp = g.hp;
                hangs.put(g, h);
                game.mobs.lure(g, g.pos.clone(), Tune.HANG_TICKS + 4);
                hangHits++;
            }
        }
        for (Iterator<Map.Entry<Gabe, Hang>> it = hangs.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Gabe, Hang> e = it.next();
            Gabe g = e.getKey();
            Hang h = e.getValue();
            if (g.dead || g.removed) {
                it.remove();
                continue;
            }
            if (g.hp < h.hp - 0.01) {
                // 맞아서 떨어짐: 뒤로 흘러감
                it.remove();
                noHang.put(g, now + 100);
                game.mobs.lure(g, new Vector(g.pos.getX() + (g.pos.getX() < Truck.OX ? -3 : 3), g.pos.getY(), g.pos.getZ() + 26), 50);
                drops++;
                continue;
            }
            // 매달린 동안 초당 HANG_TRUCK: 무는 피해(Truck.biteDamage)처럼 사람 수^TRUCK_POW 로 나눈다(혼자면 그대로, 떼가 큰 판에서 트럭이 녹지 않게)
            if ((now - h.since) % 20 == 0 && now > h.since && tr != null)
                truckDealt += tr.hitFrac(Tune.HANG_TRUCK / Math.pow(trip.people(), Trip.Tune.TRUCK_POW), g, "cause_sp_chase_hang");
            if (now - h.since >= Tune.HANG_TICKS) {
                // 기어오름: 판 가장자리 안쪽으로
                it.remove();
                noHang.put(g, now + 200);
                double x = Math.max(px0 + 0.3, Math.min(px1 - 0.3, g.pos.getX())), z = Math.max(pz0 + 0.3, Math.min(pz1 - 0.3, g.pos.getZ()));
                move(g, new Vector(x, 0, z));
                climbs++;
            }
        }
        noHang.keySet().removeIf(g -> g.dead || g.removed);
    }

    // ------------------------------------------------------------------ 떨어진 사람

    private void fallTick(long now) {
        for (Player p : game.players.inPlay()) {
            PState s = game.players.get(p);
            if (s.life != Life.ALIVE || s.grabbedBy == null || fallen.containsKey(p.getUniqueId())) continue;
            Location l = p.getLocation();
            if (onPlatform(l.toVector(), 1.5)) continue;
            // 마지막 서 있는 사람은 안 가둔다(가두면 다음 틱에 '서 있는 사람 0' 으로 판이 끝남: 숨죽이기, 끌려간 동료와 같은 규칙)
            if (standing().size() <= 1) continue;
            // 판 밖으로 끌려감: 그 게이브에 갇혀 뒤로 흘러간다
            Gabe by = s.grabbedBy;
            game.players.trap(p, by);
            fallen.put(p.getUniqueId(), new Object[] {by, now});
            game.mobs.lure(by, new Vector(by.pos.getX(), by.pos.getY(), by.pos.getZ() + 40), Tune.FALL_TICKS);
            say(p, "sa_chase_fell", 60);
            game.hud.point(null, by.pos.clone(), Tune.FALL_TICKS);
            falls++;
        }
        for (Iterator<Map.Entry<UUID, Object[]>> it = fallen.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Object[]> e = it.next();
            PState s = game.players.get(e.getKey());
            Player p = Bukkit.getPlayer(e.getKey());
            if (s == null || p == null || s.life != Life.TRAPPED) {
                it.remove();
                continue;
            }
            long at = (long) e.getValue()[1];
            if (now - at >= Tune.FALL_TICKS) {
                it.remove();
                game.players.trapEnd(p);
            }
        }
    }

    // ------------------------------------------------------------------ 길 사건

    private void eventTick(long now, long t) {
        if (event == null && now >= nextEvent - Tune.EVENT_WARN && eventAt < 0 && now < openAt + dur - 120) {
            List<String> c = new ArrayList<>(List.of(EVENTS));
            if (lastEvent != null && c.size() > 1) c.remove(lastEvent);
            event = pickEvent(c);
            eventAt = nextEvent;
            warn(event);
        }
        if (event != null && eventAt >= 0 && now >= eventAt && eventEnd < 0) startEvent(now);
        if (event != null && eventEnd >= 0) {
            runEvent(now);
            if (now >= eventEnd) stopEvent(now);
        }
    }

    protected void warn(String ev) {
        say("sa_chase_" + ev, Tune.EVENT_WARN);
        switch (ev) {
            case "wall" -> game.hud.waveFrom("sa_chase_wall", Tune.EVENT_WARN, 180);
            case "bend" -> {
                bendSide = rnd.nextBoolean() ? 1 : -1;
                game.hud.waveFrom("sa_chase_bend", Tune.EVENT_WARN, bendSide > 0 ? 270 : 90);
            }
            default -> {
            }
        }
        sound(truckLoc(2), "trip.horn", Sound.ITEM_GOAT_HORN_SOUND_2, 1.4f, 1.1f, 64);
    }

    protected void startEvent(long now) {
        switch (event) {
            case "bend" -> {
                stream.bend = Tune.BEND_DEG * bendSide;
                eventEnd = now + Tune.BEND_TICKS;
                sound(truckLoc(1), "trip.tires", Sound.ENTITY_MINECART_INSIDE, 1.4f, 0.5f, 48);
            }
            case "wall" -> {
                wall.clear();
                spawnWall(Tune.act(Tune.WALL_N, act), Tune.WALL_FROM);
                wallN = wall.size();
                eventEnd = now + Tune.WALL_TICKS;
            }
            case "gap" -> {
                for (Player p : standing()) game.players.launch(p, Tune.GAP_LIFT, new Vector(0, 0, 0), 0, "cause_sp_chase_gap");
                eventEnd = now + 22;
            }
            case "tunnel" -> {
                fog(Tune.TUNNEL_FOG);
                eventEnd = now + Tune.TUNNEL_TICKS;
                sound(truckLoc(2), "trip.engine", Sound.ENTITY_MINECART_RIDING, 1.4f, 0.5f, 48);
            }
            default -> eventEnd = now;
        }
    }

    /** 얼굴 벽 한 겹(앞 from 블록에서 n 마리, 트럭 앞까지 WALL_TICKS 동안 다가옴). wall 목록에 더함. */
    protected void spawnWall(int n, double from) {
        Species sp = rounds.pick(s -> s.spawn == Species.Spawn.RING && s.band != Band.SKY && (s.is(Role.TANK) || s.is(Role.CHARGE) || s.is(Role.SHIELD)) && !s.is(Role.BOSS));
        if (sp == null) sp = cheapRunner();
        for (int i = 0; i < n && sp != null; i++) {
            double x = px0 - 1.5 + (px1 - px0 + 3) * i / Math.max(1, n - 1);
            Gabe g = spawn(sp, new Vector(x, 0, Truck.Z0 - from), true);
            if (g == null) continue;
            g.noHold = true;
            wall.add(g);
            game.mobs.lure(g, new Vector(x, g.pos.getY(), Truck.Z0 - 0.8), Tune.WALL_TICKS + 20);
        }
    }

    /** 벽이 트럭에 닿음(남은 수 left, 처음 수 n): 트럭 피해(범퍼 ramMul 을 곱함). 이어받아 바꿈. */
    protected void wallHit(int left, int n) {
        Truck tr = trip.truck();
        if (left <= 0 || tr == null) return;
        double frac = Tune.WALL_FRAC * left / Math.max(1, n) * game.augments.ramMul();
        truckDealt += tr.hitFrac(frac, null, "cause_sp_chase_wall");
        wallHits++;
        game.horizon.shake(0.8, 16);
        sound(truckLoc(1), "trip.truck_hit", Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.7f, 64);
    }

    protected void runEvent(long now) {
        switch (event) {
            case "bend" -> {
                for (Player p : standing()) if (!game.players.riding(p)) game.players.push(p, new Vector(-bendSide * Tune.BEND_PUSH, 0, 0));
            }
            case "wall" -> {
                // 벽은 흐름 빠르기만큼 다가온다
                double need = Tune.WALL_FROM / (double) Tune.WALL_TICKS;
                for (Gabe g : wall) {
                    if (g.dead) continue;
                    double base = Math.max(0.02, g.speed() / Math.max(0.05, g.buff()));
                    g.speedBuff = need / base;
                }
            }
            default -> {
            }
        }
    }

    protected void stopEvent(long now) {
        Truck tr = trip.truck();
        switch (event) {
            case "bend" -> stream.bend = 0;
            case "wall" -> {
                int left = 0;
                for (Gabe g : wall) if (!g.dead && !g.removed) left++;
                wallHit(left, wallN);
                // 들이받힌 것은 날아간다
                Vector front = new Vector(Truck.OX, Plaza.FLOOR_Y + 1, Truck.Z0);
                for (Gabe g : wall) {
                    if (g.dead || g.removed) continue;
                    g.noReward = true;
                    Vector d = g.pos.clone().subtract(front).setY(0.6);
                    if (d.lengthSquared() < 1e-6) d = new Vector(0, 0.6, -1);
                    game.mobs.damage(g, new Damage(1e6, DamageType.EXPLOSION, null, g.pos.clone(), d.normalize(), "truck", 0));
                }
                wall.clear();
            }
            case "gap" -> {
                for (Player p : standing()) {
                    double x = p.getLocation().getX();
                    if (x - px0 < Tune.GAP_EDGE || px1 - x < Tune.GAP_EDGE) game.players.root(p, Tune.GAP_ROOT, "sp_chase_gap");
                }
                if (tr != null) truckDealt += tr.hitFrac(Tune.GAP_FRAC, null, "cause_sp_chase_gap");
                game.horizon.shake(0.8, 14);
                sound(truckLoc(1), "trip.thud", Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.8f, 0.5f, 64);
            }
            case "tunnel" -> fog(Tune.CHASE_FOG);
            default -> {
            }
        }
        lastEvent = event;
        event = null;
        eventAt = eventEnd = -1;
        nextEvent = now + Tune.EVENT_EVERY;
    }

    // ------------------------------------------------------------------ HUD, 상태, 시험

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.dist", Math.min(1, (game.now() - openAt) / (double) Math.max(1, dur)));
        if (event != null) {
            out.put("sp.event", event);
            out.put("sp.warn", (int) Math.max(0, eventAt - game.now()));
        }
        Object[] f = fallen.get(p.getUniqueId());
        if (f != null) out.put("sp.fallen", (int) Math.max(0, Tune.FALL_TICKS - (game.now() - (long) f[1])));
    }

    @Override
    protected String detail() {
        return String.format(Locale.ROOT, "dist=%.2f ev=%s hang=%d hung=%d climb=%d drop=%d fall=%d wall=%d truck=%.0f stream=%d", Math.min(1, (game.now() - openAt) / (double) Math.max(1, dur)),
            event == null ? "-" : event + "@" + Math.max(0, eventAt - game.now()), hangs.size(), hangHits, climbs, drops, falls, wallHits, truckDealt,
            stream == null ? 0 : stream.displays());
    }

    @Override
    public Object ask(String k) {
        if (k.equals("disp")) return displays() + (stream == null ? 0 : stream.displays());
        if (k.equals("dist")) return Math.min(1, (game.now() - openAt) / (double) Math.max(1, dur));
        return super.ask(k);
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        switch (what) {
            case "사건" -> {
                String ev = a.length > 4 ? a[4] : EVENTS[rnd.nextInt(EVENTS.length)];
                if (event != null && eventEnd >= 0) stopEvent(game.now());
                event = ev;
                if (ev.equals("bend")) bendSide = rnd.nextBoolean() ? 1 : -1;
                eventAt = game.now();
                startEvent(game.now());
                return "사건 " + ev;
            }
            case "거리" -> {
                double f = a.length > 4 ? Double.parseDouble(a[4]) : 0.9;
                openAt = game.now() - (long) (f * dur);
                return "거리 " + f;
            }
            case "떨어짐" -> {
                if (p == null) return "사람 없음";
                // 가장 가까운 하늘 종(없으면 아무 게이브)에게 붙잡힌 채 판 밖으로
                Gabe by = game.mobs.nearest(p.getLocation().toVector(), 40, g -> !g.dead && g.flying && !g.sp.is(Role.BOSS));
                if (by == null) by = game.mobs.nearest(p.getLocation().toVector(), 60, g -> !g.dead && !g.sp.is(Role.BOSS));
                if (by == null) return "끌 게이브 없음";
                p.teleport(new Location(game.world, px1 + 3, Plaza.FLOOR_Y + 0.05, (pz0 + pz1) / 2));
                game.players.grab(p, by, 99);
                return "붙잡힘 " + by.sp.name();
            }
            default -> {
                return null;
            }
        }
    }
}
