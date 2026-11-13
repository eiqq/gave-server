package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Role;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;

/**
 * 5 정전된 밤: 트럭 등과 중앙 원 빛이 꺼지고(Truck.lampOff) 하늘이 가장 어두워진다(테마 어둡게 막마다 BLACK_DIM). 빛(조명탄, 불, 남은 등) 밖의 게이브는 몸이
 * 어두워지고(디스플레이 밝기 DARK_BLOCK) 흰 실눈만 보인다(PAL_WHITE_EYES + 실눈 뜸). 총을 쏘면 총구 불꽃이 MUZZLE_TICKS 동안 둘레 MUZZLE_R 를 비춘다.
 * 조명탄 하나씩 공짜, 상점 조명탄 반값. 끝: 다 잡기.
 */
public final class Blackout extends Special {

    private static final Display.Brightness DARK = new Display.Brightness(Tune.DARK_BLOCK, Tune.DARK_SKY);

    private final Set<Gabe> darkened = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Gabe> eyed = Collections.newSetFromMap(new IdentityHashMap<>());
    private int flares, litNow, darkNow;

    public Blackout(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_kill";
    }

    @Override
    public void plan(Director.Plan p) {
        p.night = true;
    }

    @Override
    protected boolean begin() {
        darkened.clear();
        eyed.clear();
        flares = 0;
        lampOff(this, true);
        dimWant = Tune.act(Tune.BLACK_DIM, act);
        for (Player p : standing()) if (game.shop.supplies.give(p, "flare", Tune.FLARE_FREE)) flares++;
        sound(truckLoc(1.5), "trip.power_off", Sound.BLOCK_COPPER_BULB_TURN_OFF, 2f, 0.5f, 96);
        say("sa_flare_free", 60);
        return true;
    }

    @Override
    protected double mulFor(String k) {
        if (!opened || ended) return 1;
        return switch (k) {
            case "hp" -> Tune.BLACK_HP;
            case "price.flare" -> Tune.FLARE_PRICE;
            default -> 1;
        };
    }

    @Override
    protected void step(long now) {
        if (now % 4 != 0) return;
        List<Vector> muzzles = new ArrayList<>();
        for (Player p : standing()) if (game.mobs.shotsBy(p, Tune.MUZZLE_TICKS) > 0) muzzles.add(p.getEyeLocation().toVector());
        double r2 = Tune.MUZZLE_R * Tune.MUZZLE_R;
        litNow = darkNow = 0;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.body == null || g.sp.is(Role.BOSS)) continue;
            boolean lit = game.mobs.lit(g.pos);
            if (!lit) for (Vector m : muzzles) if (m.distanceSquared(g.pos) <= r2) {
                lit = true;
                break;
            }
            if (lit) {
                litNow++;
                if (darkened.remove(g)) bright(g, Body.LIGHT);
            } else {
                darkNow++;
                darkened.add(g);
                bright(g, DARK);
                if (eyed.add(g)) {
                    if ((g.body.look.palette & Look.PAL_WHITE_EYES) == 0) g.body.look.palette |= Look.PAL_WHITE_EYES;
                    else eyed.remove(g);
                }
                g.body.look.open = true;
            }
        }
        darkened.removeIf(g -> g.dead || g.removed);
        eyed.removeIf(g -> g.dead || g.removed);
    }

    @Override
    protected void end() {
        for (Gabe g : darkened) if (!g.dead && !g.removed) bright(g, Body.LIGHT);
        for (Gabe g : eyed) if (!g.dead && !g.removed && g.body != null) g.body.look.palette &= ~Look.PAL_WHITE_EYES;
        darkened.clear();
        eyed.clear();
        if (opened) sound(truckLoc(1.5), "trip.power_on", Sound.BLOCK_COPPER_BULB_TURN_ON, 1.6f, 1f, 64);
    }

    @Override
    protected String detail() {
        return String.format(Locale.ROOT, "dark=%d lit=%d flares=%d dim=%.2f", darkNow, litNow, flares, dimWant);
    }
}
