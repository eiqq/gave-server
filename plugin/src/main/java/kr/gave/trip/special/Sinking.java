package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Band;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 특수 15 가라앉는 트럭(3~4막, specials_draft sinking): 땅에서 솟은 큰 얼굴 넷이 트럭 네 바퀴를 물고 끌어내린다. 트럭 모델이 천천히 가라앉고(넷이 다 물면
 * {@link LateTune#SINK_DEPTH} 블록까지) 체력이 초마다 깎이며(무는 수만큼), 등불이 묻혀 상점이 잠긴다(Truck.lampCover). 넷은 트럭 밑이라 트럭 곁
 * ({@link LateTune#SINK_REACH} 블록 안)에서 쏜 것만 제대로 먹고, 그 자리로 떼가 몰린다(트럭 노림 x {@link LateTune#SINK_SHARE}). 하나 잡을 때마다
 * 깎임이 줄고, 넷을 다 잡으면 끝. 트럭 체력이 다 깎이면 판 끝(트럭 부품 그대로).
 *
 * <p>무는 얼굴은 그 막 땅밑 종(G24 땅밑 얼굴, 없으면 땅 종 가운데 큰 것)을 키운 것이다. 엔진의 끌기(Mobs.lure: 그동안 종 행동이 멈춤)로 바퀴 곁에 붙잡아 둔다.
 * 트럭 모델은 Truck 의 디스플레이(꼬리표 gave_trip_truck)를 아래로 옮기고 닫을 때 되돌린다(요청: Truck 에 sink(깊이) 고리가 생기면 그것으로).
 */
public final class Sinking extends Late {

    private final List<Gabe> biters = new ArrayList<>();
    private final List<Vector> spots = new ArrayList<>();
    private final Map<ItemDisplay, Transformation> truckForms = new IdentityHashMap<>();
    private int total, killed;
    private double depth, drained;

    public Sinking(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_truck";
    }

    private Species biterSpecies() {
        if (Species.G24.implemented()) return Species.G24;
        Species s = rounds.pick(sp -> sp.band == Band.GROUND && sp.spawn == Species.Spawn.RING && !sp.is(Role.BOSS) && !sp.is(Role.SWARM) && sp.threat >= 3);
        if (s == null) s = rounds.pick(sp -> sp.spawn == Species.Spawn.RING && !sp.is(Role.BOSS) && !sp.is(Role.SWARM) && sp.band != Band.SKY);
        return s;
    }

    @Override
    protected boolean begin() {
        Truck tr = truck();
        Species sp = biterSpecies();
        if (tr == null || !tr.alive() || sp == null) return false;
        biters.clear();
        spots.clear();
        total = killed = 0;
        depth = drained = 0;
        // 바퀴 곁(발자국 밖 한 칸): 앞뒤 바퀴 z, 좌우
        double side = (Truck.X1 - Truck.X0) / 2.0 + 0.9;
        for (double dz : new double[] {-2.16, 1.82}) {
            for (int s = -1; s <= 1; s += 2) spots.add(new Vector(Truck.OX + s * side, Plaza.FLOOR_Y, Truck.OZ + dz));
        }
        for (Vector at : spots) {
            Gabe g = rounds.spawnAt(sp, at);
            if (g == null) continue;
            g.maxHp = hpFor(sp, LateTune.SINK_HP);
            g.hp = g.maxHp;
            g.noReward = false;
            g.reward = Math.max(g.reward, sp.reward * 4);
            if (g.body != null) g.body.scale(LateTune.SINK_SCALE, 10);
            // 땅밑 얼굴은 솟은 꼴(얼굴 전체가 맞는 자리)로 문다: 끌기 동안 종 행동이 멈춰 그 꼴 그대로
            if (sp == Species.G24) g.state("rise");
            hold(g, at);
            biters.add(g);
            total++;
            game.hud.point(null, g.pos.clone(), 100);
            game.world.spawnParticle(Particle.BLOCK, at.getX(), Plaza.FLOOR_Y + 0.3, at.getZ(), 40, 0.6, 0.3, 0.6, 0.1,
                    game.world.getBlockAt((int) Math.floor(at.getX()), Plaza.FLOOR_Y - 1, (int) Math.floor(at.getZ())).getBlockData());
        }
        if (total == 0) return false;
        tr.lampCover(this, Truck.LAMP_COVER_FULL);
        for (Player p : game.players.inPlay()) if (game.shop.view(p) != null) game.shop.close(p);
        game.mobs.scale("trip_sinking", (g, d) -> {
            if (!running || !biters.contains(g)) return 1;
            Player by = d.by();
            if (by == null) return 1;
            return Truck.dist(by.getLocation().getX(), by.getLocation().getZ()) <= LateTune.SINK_REACH ? 1 : LateTune.SINK_FAR;
        });
        grabTruck();
        Props.soundAround(loc(new Vector(Truck.OX, 0, Truck.OZ), 1), "gabe.g24.rumble", Sound.ENTITY_WARDEN_DIG, 1.6f, 0.7f, 0.6f, 120, -1);
        game.horizon.shake(0.6, 30);
        darken(act() >= 4 ? 0.75 : 0.55);
        return true;
    }

    private void hold(Gabe g, Vector at) {
        game.mobs.lure(g, at.clone().setY(g.pos.getY()), 40);
    }

    /** 트럭 디스플레이(몸통, 바퀴)의 처음 변환을 적어 둠. */
    private void grabTruck() {
        truckForms.clear();
        for (Entity e : game.world.getNearbyEntities(new Location(game.world, Truck.OX, Plaza.FLOOR_Y + 1, Truck.OZ), 6, 4, 6)) {
            if (!(e instanceof ItemDisplay d) || !e.getScoreboardTags().contains("gave_trip_truck")) continue;
            var m = d.getItemStack().getData(DataComponentTypes.ITEM_MODEL);
            if (m == null || !m.value().startsWith("trip/truck") && !m.value().startsWith("trip/wheel")) continue;
            truckForms.put(d, d.getTransformation());
        }
    }

    private void sinkTruck(int ticks) {
        for (Map.Entry<ItemDisplay, Transformation> e : truckForms.entrySet()) {
            ItemDisplay d = e.getKey();
            if (!d.isValid()) continue;
            Transformation t = e.getValue();
            Vector3f tr = new Vector3f(t.getTranslation()).add(0, (float) -depth, 0);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(new Transformation(tr, t.getLeftRotation(), t.getScale(), t.getRightRotation()));
        }
    }

    @Override
    protected void end() {
        game.mobs.unscale("trip_sinking");
        Truck tr = truck();
        if (tr != null) tr.lampCover(this, 0);
        depth = 0;
        sinkTruck(20);
        truckForms.clear();
        biters.clear();
    }

    @Override
    public void plan(Director.Plan p) {
        scaleCounts(p, LateTune.SINK_UNITS);
    }

    @Override
    protected double mulFor(String k) {
        return k.equals("truckShare") ? LateTune.SINK_SHARE : 1;
    }

    @Override
    protected boolean killAll() {
        return false;
    }

    private int alive() {
        int n = 0;
        for (Gabe g : biters) if (!g.dead && !g.removed) n++;
        return n;
    }

    @Override
    protected double frac() {
        return total <= 0 ? -1 : (double) (total - alive()) / total;
    }

    @Override
    protected String text() {
        return (total - alive()) + "/" + total;
    }

    @Override
    protected void step(long t) {
        long now = game.now();
        int k = 0;
        for (int i = 0; i < biters.size(); i++) {
            Gabe g = biters.get(i);
            if (g.dead || g.removed) continue;
            k++;
            if (now % 20 == 0) {
                hold(g, spots.get(Math.min(i, spots.size() - 1)));
                if (g.sp == Species.G24) g.state("rise");
            }
        }
        if (k != total - killed) {
            int was = total - killed;
            killed = total - k;
            if (k < was) Props.soundAround(loc(new Vector(Truck.OX, 0, Truck.OZ), 1), "gabe.g10.creak", Sound.BLOCK_CHAIN_BREAK, 1.4f, 1.1f, 0.8f, 80, -1);
        }
        Truck tr = truck();
        if (tr == null) return;
        double share = k / 4.0;
        if (k > 0 && now % 20 == 0) {
            drained += tr.hitFrac(LateTune.SINK_DRAIN * share, null, "cause_sp_sinking");
            if (now % 60 == 0) Props.soundAround(loc(new Vector(Truck.OX, 0, Truck.OZ), 1), "gabe.g10.creak", Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.1f, 0.6f, 0.5f, 64, -1);
        }
        double want = LateTune.SINK_DEPTH * share;
        double was = depth;
        if (depth < want) depth = Math.min(want, depth + LateTune.SINK_RATE * k);
        else if (depth > want) depth = Math.max(want, depth - LateTune.SINK_RATE * 6);
        if (Math.abs(depth - was) > 1e-6 && now % 10 == 0) sinkTruck(10);
        if (k == 0) win();
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.sink", depth / LateTune.SINK_DEPTH);
    }

    @Override
    protected String statusMore() {
        return String.format(Locale.ROOT, "biters=%d/%d depth=%.2f drained=%.1f forms=%d", alive(), total, depth, drained, truckForms.size());
    }

    @Override
    protected String testMore(Player p, String sub, String[] a) {
        if (sub.equals("잡기")) {
            for (Gabe g : biters) {
                if (g.dead || g.removed) continue;
                g.hp = 0;
                g.die(new kr.gave.mob.Damage(1e6, kr.gave.mob.DamageType.OTHER, null, g.pos.clone(), null, "test", 0));
                return "얼굴 하나 잡음 " + statusMore();
            }
            return "없음";
        }
        if (sub.equals("멀리") && p != null) {
            // 멀리서 쏜 피해가 거의 안 먹는지: 그 사람이 트럭에서 20 블록 떨어져 무는 얼굴 하나에 50 피해
            for (Gabe g : biters) {
                if (g.dead || g.removed) continue;
                double before = g.hp;
                p.teleport(new Location(game.world, Truck.OX + 20, Plaza.FLOOR_Y + 0.1, Truck.OZ, 90, 0));
                game.mobs.damage(g, new kr.gave.mob.Damage(50, kr.gave.mob.DamageType.BULLET, p, g.pos.clone(), null, "pistol", 0));
                return String.format(Locale.ROOT, "먼 사격 %.1f → %.1f", before, g.hp);
            }
            return "없음";
        }
        return null;
    }
}
