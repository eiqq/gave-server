package kr.gave.trip.special;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Band;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.PadLights;
import kr.gave.trip.Rounds;
import kr.gave.trip.Scenery;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 특수 19 마지막 질주(3막 29라운드, 보스 길목 '질주', specials_draft lastrun): 가장 긴 추격전({@link Chase}를 이어받음, {@link LateTune#LAST_SECONDS} 초).
 * 빠르기가 세 단계로 오르고(흐름과 게이브 모두), 정예가 판 뒤 끝으로 뛰어올라 사람 사이에서 싸우며, 길 사건은 정해진 차례(얼굴 벽 둘, 끊긴 다리 둘이 끼임).
 * 앞(북쪽) 지평선 끝에 발사장 불빛(투광등 모델)이 보이고 달릴수록 가까워지며 커진다. 팩에 우주선 모델(gave:trip/rocket)이 있으면 그 검은 그림자도.
 * 끝나면 그대로 30라운드(지평선 게이브가 그 불빛을 가린다).
 */
public final class LastRun extends Chase {

    private static final String[] ORDER = {"bend", "wall", "gap", "tunnel", "wall", "bend", "gap", "tunnel", "bend"};
    private static final String ROCKET = "trip/rocket";

    private int stage, evs, elites;
    private long nextElite;
    private ItemDisplay rocket;
    private final ItemDisplay[] glow = new ItemDisplay[2];

    public LastRun(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected long duration() {
        return LateTune.LAST_SECONDS * 20L;
    }

    @Override
    public void plan(Director.Plan p) {
        super.plan(p);
        scaleUnits(p, LateTune.LAST_UNITS);
    }

    @Override
    protected void beginMore() {
        stage = 0;
        evs = 0;
        elites = 0;
        nextElite = openAt + LateTune.LAST_ELITE_FIRST;
        dimWant = LateTune.DIM_ACT[3];
        // 앞 지평선의 불빛이 보이게 맑음(추격전 기본 안개 4 대신): 3막 밤이라 어둡기는 그대로
        fog(1);
        horizon(0);
    }

    @Override
    protected double mulFor(String k) {
        if (k.equals("speed")) return 1 + LateTune.LAST_STEP_SPEED * stage;
        return 1;
    }

    @Override
    protected String pickEvent(List<String> c) {
        String ev = ORDER[evs % ORDER.length];
        evs++;
        return ev;
    }

    @Override
    protected void ran(double f) {
        int want = f >= 2 / 3.0 ? 2 : f >= 1 / 3.0 ? 1 : 0;
        if (want != stage) {
            stage = want;
            stream.speed(Special.Tune.CHASE_SPEED * (1 + LateTune.LAST_STEP_SPEED * stage), 60);
            sound(truckLoc(1.5), "trip.engine_rev", Sound.ENTITY_MINECART_RIDING, 1.8f, 0.8f + stage * 0.2f, 80);
            sound(truckLoc(2), "trip.horn", Sound.ITEM_GOAT_HORN_SOUND_2, 2f, 1f, 96);
            game.horizon.shake(0.6, 20);
        }
        long now = game.now();
        if (now % 10 == 0) horizon(f);
        if (now >= nextElite && f < 0.95) {
            nextElite = now + LateTune.LAST_ELITE_EVERY;
            elite();
        }
        if (now % 120 == 0) sound(new Location(game.world, Truck.OX, Plaza.FLOOR_Y + 6, Truck.Z0 - 60), "director.alert", Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 0.6f, 120);
    }

    /** 정예 하나가 판 뒤 끝으로 뛰어오름. */
    private void elite() {
        Species sp = rounds.pick(s -> s.is(Role.ELITE) && s.spawn == Species.Spawn.RING && s.band != Band.SKY && !s.is(Role.BOSS));
        if (sp == null) sp = rounds.pick(s -> (s.is(Role.TANK) || s.is(Role.CHARGE)) && s.spawn == Species.Spawn.RING && s.band != Band.SKY && !s.is(Role.BOSS));
        if (sp == null) return;
        double x = px0 + 0.8 + rnd.nextDouble() * (px1 - px0 - 1.6);
        Gabe g = spawn(sp, new Vector(x, 0, pz1 + 6), true);
        if (g == null) return;
        Vector to = new Vector(x, 0, pz1 - 1.6);
        game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
            if (g.dead || g.removed) return;
            move(g, to);
            Location l = loc(to, 0.2);
            game.world.spawnParticle(Particle.BLOCK, l.getX(), l.getY(), l.getZ(), 30, 0.6, 0.2, 0.6, 0.1, game.world.getBlockAt(l.getBlockX(), Plaza.FLOOR_Y - 1, l.getBlockZ()).getBlockData());
            sound(l, "trip.thud", Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.6f, 0.6f, 48);
        }, 12);
        elites++;
    }

    /** 앞 지평선의 발사장 불빛(과 우주선 그림자): 달린 몫 f 만큼 가까이, 크게. */
    private void horizon(double f) {
        double dist = LateTune.LAST_FAR - (LateTune.LAST_FAR - LateTune.LAST_NEAR) * f;
        float sc = (float) (1.0 + 1.6 * f);
        boolean lampPack = Scenery.has(game, PadLights.MODEL);
        for (int i = 0; i < 2; i++) {
            double x = Truck.OX + (i == 0 ? -1 : 1) * (14 + 10 * f);
            Location at = new Location(game.world, x, Plaza.FLOOR_Y + 0.5 * sc, Truck.Z0 - dist);
            if (glow[i] == null || !glow[i].isValid()) {
                if (!lampPack) continue;
                glow[i] = keep(prop(PadLights.MODEL, at, 0f, sc));
                if (glow[i] != null) {
                    glow[i].setViewRange(10f);
                    glow[i].setBrightness(new Display.Brightness(15, 15));
                    glow[i].setTeleportDuration(10);
                }
            } else {
                glow[i].teleport(at);
                glow[i].setInterpolationDelay(0);
                glow[i].setInterpolationDuration(10);
                glow[i].setTransformationMatrix(new Matrix4f().scale(sc));
            }
        }
        if (!Scenery.has(game, ROCKET)) return;
        float rs = (float) (2.0 + 3.0 * f);
        Location at = new Location(game.world, Truck.OX, Plaza.FLOOR_Y + 0.5 * rs, Truck.Z0 - dist - 8);
        if (rocket == null || !rocket.isValid()) {
            rocket = keep(prop(ROCKET, at, 0f, rs));
            if (rocket != null) {
                rocket.setViewRange(10f);
                rocket.setBrightness(new Display.Brightness(0, 0));
                rocket.setTeleportDuration(10);
            }
        } else {
            rocket.teleport(at);
            rocket.setInterpolationDelay(0);
            rocket.setInterpolationDuration(10);
            rocket.setTransformationMatrix(new Matrix4f().scale(rs));
        }
    }

    @Override
    protected void stopEvent(long now) {
        boolean tunnel = "tunnel".equals(event);
        super.stopEvent(now);
        // 굴을 지나면 추격전 기본 안개(4) 대신 다시 맑음: 앞 불빛이 보이게
        if (tunnel) fog(1);
    }

    @Override
    protected void end() {
        super.end();
        rocket = null;
        glow[0] = glow[1] = null;
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        super.hudMore(p, out);
        out.put("sp.stage", stage + 1);
    }

    @Override
    protected String detail() {
        return super.detail() + String.format(Locale.ROOT, " stage=%d elites=%d evs=%d glow=%s rocket=%s", stage + 1, elites, evs,
                glow[0] != null && glow[0].isValid(), rocket != null && rocket.isValid());
    }
}
