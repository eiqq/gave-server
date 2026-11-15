package kr.gave.trip.special;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Particle;
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
import kr.gave.gun.ShotFx;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.trip.Launch;
import kr.gave.trip.Rounds;
import kr.gave.trip.Scenery;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 특수 21 연료 화재(4막 발사장, specials_draft fuelfire): 새어 나온 연료에 불이 붙어 불 고리가 R {@link LateTune#FIRE_R0} 에서
 * {@link LateTune#FIRE_R1} 까지 조여 온다(그 뒤로도 꺼지지 않은 호는 트럭 쪽으로 천천히). 고리 밖(띠 안쪽 끝 0.9 x 반지름부터)은 타는 땅: 사람은 초당
 * {@link LateTune#FIRE_DMG} 와 안쪽으로 밀림, 게이브도 타지만 불붙은 채 와서(피해 x) 닿은 사람에게 불을 옮긴다. 소화 밸브 셋(R {@link LateTune#VALVE_R},
 * 120도 간격, 그 자리에 서 있기 {@link LateTune#VALVE_TICKS} 틱)을 잠그면 그 쪽 120도 호가 꺼진다. 꺼지지 않은 호가 트럭에 닿으면 트럭이 초마다 깎인다.
 * 건조는 잠근 밸브가 많을수록 빠르다(Launch 가 있으면 끝 판정은 Launch).
 *
 * <p>그림: 불 띠는 쓰러짐 고리 셰이더(fx_flat 종류 14, 빨강)를 반지름에 맞춰 키운 판 하나(켜진 몫 = 꺼지지 않은 호), 그 위에 불꽃(ShotFx.flame).
 * 밸브는 교통 통 소품 + 초록 고리(잠근 몫). 발광 상자는 쓰지 않는다.
 */
public final class FuelFire extends Late {

    /**
     * 고리 판 방향 맞춤. 셰이더(zrfx.glsl 종류 14)는 판의 +v 쪽에서 +u 쪽으로 켜진 호를 그린다. 판 윗면 u = +x, v = +z 에 아이템 디스플레이가 y 로 반 바퀴
     * 돌린다고 보면 호는 북(-z)에서 서(-x) 쪽, 곧 세계 각(atan2(z, x))이 줄어드는 쪽으로 간다(ARC_DIR 1). 디스플레이를 θ 만큼 돌리면 각이 -θ 옮긴다.
     * 그림이 어긋나면 시험 훅 '특수b x 고리 r 도 방향'으로 ARC_ROT(도), ARC_DIR(-1 이면 각이 느는 쪽)을 맞춘다.
     */
    public static double ARC_ROT = 0, ARC_DIR = 1;

    private double r;
    private double valveA;
    private final boolean[] closed = new boolean[3];
    private final double[] charge = new double[3];
    private final ItemDisplay[] valveRing = new ItemDisplay[3];
    private final int[] valveQ = {-1, -1, -1};
    private ItemDisplay ring;
    private int ringQ = -1;
    private double build, drained;
    private final Map<UUID, Long> burning = new HashMap<>();
    private final Map<Gabe, Long> burningMobs = new IdentityHashMap<>();
    private int spreads;

    public FuelFire(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_build";
    }

    /** 발사장(Launch)이 이 라운드의 건조와 끝을 쥐고 있나: 31~34 밖에서 열리면(시험) 제 건조 막대와 끝 판정으로 돈다. */
    private boolean launch() {
        Launch l = trip.has("Launch") ? trip.part(Launch.class) : null;
        return l != null && l.building();
    }

    @Override
    protected boolean begin() {
        r = LateTune.FIRE_R0;
        valveA = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 3; i++) {
            closed[i] = false;
            charge[i] = 0;
            valveQ[i] = -1;
            Vector v = valve(i);
            if (Scenery.has(game, "trip/barrel")) keep(Scenery.spawn(game, "trip/barrel", new Location(game.world, v.getX(), Plaza.FLOOR_Y + 0.5 * 1.4, v.getZ()), 0, 1.4f, 1.2f));
        }
        ringQ = -1;
        build = drained = 0;
        burning.clear();
        burningMobs.clear();
        spreads = 0;
        fog(1);
        darken(0.72);
        Props.soundAround(loc(new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5), 1), "gun.flamethrower.ignite", Sound.ITEM_FIRECHARGE_USE, 1.6f, 0.6f, 0.5f, 200, -1);
        return true;
    }

    @Override
    protected void end() {
        ring = null;
        for (int i = 0; i < 3; i++) valveRing[i] = null;
        burning.clear();
        burningMobs.clear();
    }

    @Override
    public void plan(Director.Plan p) {
        scaleCounts(p, LateTune.FIRE_UNITS);
    }

    @Override
    protected double mulFor(String k) {
        return k.equals("build") ? buildRate() : 1;
    }

    private double buildRate() {
        return 0.4 + 0.2 * closedCount();
    }

    private int closedCount() {
        int n = 0;
        for (boolean c : closed) if (c) n++;
        return n;
    }

    @Override
    protected boolean killAll() {
        return false;
    }

    @Override
    public boolean roundDone(boolean done) {
        if (running && launch() && !ended()) return false;
        return super.roundDone(done);
    }

    @Override
    protected double frac() {
        return launch() ? -1 : build;
    }

    @Override
    protected String text() {
        return launch() ? null : Math.round(build * 100) + "%";
    }

    /** 밸브 i 자리(바닥). */
    private Vector valve(int i) {
        return polar(LateTune.VALVE_R, valveA + i * Math.PI * 2 / 3);
    }

    /** 각 a 쪽 호 번호(가장 가까운 밸브). */
    private int arcOf(double a) {
        int best = 0;
        double bd = 9;
        for (int i = 0; i < 3; i++) {
            double d = angDiff(a, valveA + i * Math.PI * 2 / 3);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** 그 자리가 타는 땅인가. */
    private boolean burns(double x, double z) {
        double d = fromCenter(x, z);
        return d >= r * 0.9 && !closed[arcOf(angleOf(x, z))];
    }

    @Override
    protected void step(long t) {
        long now = game.now();
        // 반지름
        double shrinkT = LateTune.FIRE_SHRINK_S * 20.0;
        if (t <= shrinkT) r = LateTune.FIRE_R0 + (LateTune.FIRE_R1 - LateTune.FIRE_R0) * (t / shrinkT);
        else r = Math.max(LateTune.FIRE_TRUCK_R, r - LateTune.FIRE_LATE / 20);
        // 밸브
        for (int i = 0; i < 3; i++) valveTick(i);
        // 사람
        boolean sec = now % 20 == 0;
        for (Player p : game.players.standing()) {
            Location l = p.getLocation();
            if (burns(l.getX(), l.getZ())) {
                if (sec) game.players.hurt(p, LateTune.FIRE_DMG * game.director.dmgCmd(), null, "cause_sp_fuelfire");
                Vector in = new Vector(Plaza.CX + 0.5 - l.getX(), 0, Plaza.CZ + 0.5 - l.getZ());
                if (in.lengthSquared() > 1e-6) game.players.push(p, in.normalize().multiply(0.12));
                if (now % 6 == 0) game.shotFx.flame(l.clone().add(0, 0.4, 0), 0.8f, 16, ShotFx.EXTRA);
            }
            Long until = burning.get(p.getUniqueId());
            if (until != null) {
                if (until < now) burning.remove(p.getUniqueId());
                else {
                    if (sec) game.players.hurt(p, LateTune.FIRE_SPREAD_DMG * game.director.dmgCmd(), null, "cause_sp_fuelfire");
                    if (now % 5 == 0) game.shotFx.flame(l.clone().add(0, 1.0, 0), 0.6f, 14, ShotFx.EXTRA);
                }
            }
        }
        // 게이브: 타는 땅에서 타고, 불붙은 채 오며 닿은 사람에게 옮김
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.removed || g.sp.is(Role.BOSS)) continue;
            if (burns(g.pos.getX(), g.pos.getZ())) {
                burningMobs.put(g, now + LateTune.FIRE_MOB_BURN);
                // 개체마다 다른 틱에(같은 틱에 수백 마리의 맞은 번쩍임이 한 덩어리로 나가지 않게)
                if ((now + g.id) % 20 == 0) game.mobs.damage(g, new Damage(g.maxHp * LateTune.FIRE_MOB, DamageType.FIRE, null, g.pos.clone(), null, "fuelfire", 0));
            }
        }
        burningMobs.entrySet().removeIf(e -> e.getValue() < now || e.getKey().dead || e.getKey().removed);
        for (Gabe g : burningMobs.keySet()) {
            g.dmgBuff *= LateTune.FIRE_BURN_MUL;
            if (now % 8 == (g.age & 7)) game.shotFx.flame(new Location(game.world, g.pos.getX(), g.pos.getY() + 0.3, g.pos.getZ()), 0.7f, 14, ShotFx.EXTRA);
            if (now % 5 != 0) continue;
            for (Player p : game.players.standing()) {
                Location l = p.getLocation();
                double dx = l.getX() - g.pos.getX(), dz = l.getZ() - g.pos.getZ();
                if (dx * dx + dz * dz > 1.7 * 1.7) continue;
                if (burning.put(p.getUniqueId(), now + LateTune.FIRE_BURN_TICKS) == null) spreads++;
            }
        }
        // 트럭에 닿음
        Truck tr = truck();
        if (tr != null && r <= LateTune.FIRE_TRUCK_R + 0.01 && closedCount() < 3 && sec) drained += tr.hitFrac(LateTune.FIRE_TRUCK, null, "cause_sp_fuelfire");
        drawFire(now);
        // 건조
        if (!launch()) {
            build += buildRate() / (LateTune.FIRE_BUILD_SECONDS * 20);
            if (build >= 1) {
                build = 1;
                win();
                return;
            }
        }
        stream(LateTune.STREAM_SHARE);
    }

    private void valveTick(int i) {
        if (closed[i]) return;
        Vector v = valve(i);
        boolean near = false;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - v.getX(), dz = l.getZ() - v.getZ();
            if (dx * dx + dz * dz > LateTune.VALVE_REACH * LateTune.VALVE_REACH) continue;
            near = true;
            game.hud.meter(p, "sp_fuelfire", charge[i] / LateTune.VALVE_TICKS, 6);
        }
        if (near) {
            charge[i]++;
            if (charge[i] % 20 == 1) Props.soundAround(loc(v, 1), "gabe.g10.creak", Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.9f, 0.7f + (float) (charge[i] / LateTune.VALVE_TICKS) * 0.6f,
                    0.6f, 32, -1);
        } else charge[i] = Math.max(0, charge[i] - 0.5);
        if (charge[i] >= LateTune.VALVE_TICKS) {
            closed[i] = true;
            Location at = loc(v, 1);
            Props.soundAround(at, "director.flood", Sound.BLOCK_FIRE_EXTINGUISH, 1.5f, 0.8f, 0.7f, 120, -1);
            game.world.spawnParticle(Particle.CLOUD, at.getX(), at.getY() + 1, at.getZ(), 60, 1.5, 1.5, 1.5, 0.05);
            notice("sp_fuelfire_valve", 40);
        }
        // 초록 고리(잠근 몫)
        int q = closed[i] ? 64 : (int) Math.round(charge[i] / LateTune.VALVE_TICKS * 63);
        if (q != valveQ[i] || valveRing[i] == null || !valveRing[i].isValid()) {
            valveQ[i] = q;
            ItemStack it = game.shotFx.downRing(closed[i] ? 1 : Math.max(0.02, charge[i] / LateTune.VALVE_TICKS), true);
            if (valveRing[i] == null || !valveRing[i].isValid()) valveRing[i] = keep(flat(v, LateTune.VALVE_REACH, it));
            else valveRing[i].setItemStack(it);
        }
    }

    /** 바닥에 눕는 고리 판(쓰러짐 고리 셰이더: 배율 1 의 고리 반지름 Rounds.Tune.RING_BASE_R). */
    private ItemDisplay flat(Vector at, double radius, ItemStack it) {
        float s = (float) (radius / Rounds.Tune.RING_BASE_R);
        return game.world.spawn(new Location(game.world, at.getX(), Plaza.FLOOR_Y + 0.05, at.getZ()), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformationMatrix(new Matrix4f().scale(s));
            d.setDisplayWidth((float) (radius * 2.4));
            d.setDisplayHeight(2f);
            d.setViewRange(4f);
            d.setInterpolationDuration(20);
        });
    }

    private void drawFire(long now) {
        int open = 3 - closedCount();
        // 고리 판: 꺼지지 않은 호의 몫만큼 켜짐(셋 가운데 둘은 늘 이웃이라 켜진 호는 한 덩어리)
        if (open == 0) {
            if (ring != null && ring.isValid()) ring.remove();
            ring = null;
        } else {
            int first = 0;
            if (open < 3) {
                // 켜진 덩어리의 시작 호(앞 호가 꺼진 첫 켜진 호)
                for (int i = 0; i < 3; i++) if (!closed[i] && closed[(i + 2) % 3]) first = i;
            }
            double aStart = valveA + first * Math.PI * 2 / 3 - Math.PI / 3, aEnd = aStart + open * Math.PI * 2 / 3;
            // 셰이더 호의 시작(돌리기 전 세계 각 -PI/2)을 켜진 덩어리의 끝(줄어드는 쪽으로 감) 또는 시작(느는 쪽)에
            double from = ARC_DIR >= 0 ? aEnd : aStart;
            float yaw = (float) (-Math.PI / 2 - from + Math.toRadians(ARC_ROT));
            ItemStack it = game.shotFx.downRing(open / 3.0, false);
            float s = (float) (r / Rounds.Tune.RING_BASE_R);
            Matrix4f m = new Matrix4f().rotateY(yaw).scale(s);
            if (ring == null || !ring.isValid()) {
                ring = keep(flat(new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5), r, it));
                ring.setTransformationMatrix(m);
                ringQ = open;
            } else if (now % 20 == 0 || ringQ != open) {
                if (ringQ != open) ring.setItemStack(it);
                ringQ = open;
                ring.setInterpolationDelay(0);
                ring.setInterpolationDuration(20);
                ring.setTransformationMatrix(m);
                ring.setDisplayWidth((float) (r * 2.4));
            }
        }
        // 불꽃: 꺼지지 않은 호 위에(효과 예산 안에서: 4틱마다 셋이면 열여섯 남짓이라 '덤' 몫을 혼자 다 쓰지 않는다)
        if (open > 0 && now % 4 == 0) {
            for (int k = 0; k < 3; k++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                if (closed[arcOf(a)]) continue;
                double rr = r * (0.92 + rnd.nextDouble() * 0.2);
                Vector at = polar(rr, a);
                game.shotFx.flame(new Location(game.world, at.getX(), Plaza.FLOOR_Y + 0.4, at.getZ()), 1.3f + rnd.nextFloat(), 22, ShotFx.EXTRA);
            }
        }
        if (open > 0 && now % 30 == 0) Props.soundAround(loc(polar(r, rnd.nextDouble() * Math.PI * 2), 1), null, Sound.BLOCK_FIRE_AMBIENT, 2f, 0.8f, 0.8f, 60, -1);
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.fire", r);
        out.put("sp.valves", closedCount());
        if (burning.containsKey(p.getUniqueId())) out.put("sp.burning", true);
    }

    @Override
    protected String statusMore() {
        return String.format(Locale.ROOT, "ring=%.1f valves=%d%d%d charge=%.0f/%.0f/%.0f build=%.2f burning=%d mobsburning=%d spreads=%d drained=%.1f", r, closed[0] ? 1 : 0,
                closed[1] ? 1 : 0, closed[2] ? 1 : 0, charge[0], charge[1], charge[2], build, burning.size(), burningMobs.size(), spreads, drained);
    }

    @Override
    protected String testMore(Player p, String sub, String[] a) {
        switch (sub) {
            case "밸브" -> {
                if (p == null) return null;
                int i = a.length > 4 ? Integer.parseInt(a[4]) : 0;
                Vector v = valve(i);
                p.teleport(new Location(game.world, v.getX() + 0.6, Plaza.FLOOR_Y + 0.1, v.getZ(), p.getLocation().getYaw(), 30));
                return "밸브 " + i + " 곁으로 " + statusMore();
            }
            case "잠금" -> {
                int i = a.length > 4 ? Integer.parseInt(a[4]) : 0;
                charge[i] = LateTune.VALVE_TICKS;
                return "밸브 " + i + " 잠금 " + statusMore();
            }
            case "고리" -> {
                if (a.length > 4) r = Double.parseDouble(a[4]);
                if (a.length > 5) ARC_ROT = Double.parseDouble(a[5]);
                if (a.length > 6) ARC_DIR = Double.parseDouble(a[6]);
                ringQ = -1;
                return String.format(Locale.ROOT, "고리 r=%.1f rot=%.0f dir=%.0f", r, ARC_ROT, ARC_DIR);
            }
            case "밖" -> {
                if (p == null) return null;
                double ang = valveA + Math.PI / 3 * 0;
                Vector v = polar(r + 3, ang);
                p.teleport(new Location(game.world, v.getX(), Plaza.FLOOR_Y + 0.1, v.getZ(), p.getLocation().getYaw(), 20));
                return "고리 밖으로 " + statusMore();
            }
            default -> {
                return null;
            }
        }
    }
}
