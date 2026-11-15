package kr.gave.trip.special;

import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.player.PState;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 2 고장: 시동이 꺼졌다. 트럭 등이 꺼지고(Truck.lampOff: 트럭 노림 x2, 무는 피해 x1.5) 상점이 잠긴다. 보닛(트럭 앞) 곁에서 웅크린 사람이 수리 막대를 채운다
 * (한 사람 초당 FIX_RATE, FIX_MAX 명까지 겹침, 웅크린 동안 못 쏨). 트럭이 맞을 때마다 막대가 깎인다. 그동안 떼는 끝없이 온다.
 * 50% 를 넘으면 등이 깜빡이며 돌아온다. 끝: 수리 100%.
 */
public final class Breakdown extends Special {

    private double fix;
    private long lastKnock, lastSputter;
    private int knocks, peak;
    private Vector hood;

    public Breakdown(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_start";
    }

    @Override
    protected boolean begin() {
        ownEnd = true;
        fix = 0;
        knocks = peak = 0;
        lastKnock = lastSputter = 0;
        hood = new Vector(Truck.OX, Plaza.FLOOR_Y, Truck.Z0 - 1.4);
        lampOff(this, true);
        streaming = true;
        rounds.goals().custom(icon(), 0, "0%");
        sound(truckLoc(1.2), "trip.power_off", Sound.BLOCK_COPPER_BULB_TURN_OFF, 1.6f, 0.6f, 64);
        sound(truckLoc(1.2), "trip.horn_broken", Sound.ENTITY_MINECART_INSIDE, 1.4f, 0.5f, 64);
        for (Player p : game.players.inPlay()) if (game.shop.view(p) != null) game.shop.close(p);
        game.hud.point(null, hood.clone().setY(Plaza.FLOOR_Y + 1.2), 100);
        cue("last_stand", Tune.MAX_SECONDS * 20);
        return true;
    }

    @Override
    protected double mulFor(String k) {
        return k.equals("truckShare") && opened && !ended ? Tune.BREAK_SHARE : 1;
    }

    @Override
    public Object ask(String k) {
        if (k.equals("shopLocked") && opened && !ended) return Boolean.TRUE;
        return super.ask(k);
    }

    @Override
    protected void step(long now) {
        int n = 0;
        for (Player p : standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - hood.getX(), dz = l.getZ() - hood.getZ(), d2 = dx * dx + dz * dz;
            if (d2 > Tune.HOOD_R * Tune.HOOD_R * 2.2) continue;
            if (d2 <= Tune.HOOD_R * Tune.HOOD_R && p.isSneaking() && !game.players.riding(p)) {
                n++;
                PState s = game.players.get(p);
                s.gunLockUntil = Math.max(s.gunLockUntil, now + 4);
                if (now % 5 == 0) meter(p, "sa_gl_fix", fix, 10);
            } else if (now % 20 == 0) {
                hint(p, "sa_hint_fix", 60);
                meter(p, "sa_gl_fix", fix, 22);
            }
        }
        peak = Math.max(peak, n);
        fix = Math.min(1, fix + Tune.FIX_RATE / 20.0 * Math.min(n, Tune.FIX_MAX));
        rounds.goals().custom(icon(), fix, Math.round(fix * 100) + "%");
        if (n > 0 && now - lastSputter >= 70) {
            lastSputter = now;
            sound(truckLoc(1.0), "trip.truck_start", Sound.ENTITY_MINECART_INSIDE, 0.8f, 0.6f, 40);
        }
        // 50% 를 넘으면 등이 깜빡이며 돌아온다(갈수록 오래 켜짐)
        if (fix >= Tune.FIX_LIGHT) {
            int on = (int) (4 + 12 * (fix - Tune.FIX_LIGHT) / (1 - Tune.FIX_LIGHT));
            lampOff(this, now % 20 >= on);
        }
        if (now % 5 == 0) for (Player p : game.players.inPlay()) if (game.shop.view(p) != null) game.shop.close(p);
        if (fix >= 1) {
            lampOff(this, false);
            sound(truckLoc(1.2), "trip.truck_start", Sound.ENTITY_MINECART_RIDING, 2f, 0.8f, 80);
            sound(truckLoc(1.2), "trip.power_on", Sound.BLOCK_COPPER_BULB_TURN_ON, 1.6f, 1f, 64);
            finish(true);
        }
    }

    @Override
    public void on(String event, Object arg) {
        if (!opened || ended || !Trip.EV_TRUCK_HIT.equals(event)) return;
        long now = game.now();
        // 깎임은 1초에 한 번, 무는 피해(Truck.biteDamage)처럼 사람 수^TRUCK_POW 로 나눈다: 떼가 큰 판에서 맞는 횟수만큼 뒤로 가지 않고, 혼자여도 수리(초당 FIX_RATE)가 앞선다
        if (now - lastKnock < 20) return;
        lastKnock = now;
        knocks++;
        fix = Math.max(0, fix - Tune.FIX_KNOCK / Math.pow(trip.people(), Trip.Tune.TRUCK_POW));
    }

    @Override
    protected void finished(boolean ok) {
        lampOff(this, false);
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.fix", fix);
    }

    @Override
    protected String detail() {
        return String.format(Locale.ROOT, "fix=%.2f knocks=%d peak=%d", fix, knocks, peak);
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        switch (what) {
            case "채움" -> {
                fix = a.length > 4 ? Double.parseDouble(a[4]) : 0.95;
                return "수리 " + fix;
            }
            case "보닛" -> {
                if (p == null) return "사람 없음";
                p.teleport(new Location(game.world, hood.getX() + 0.5, Plaza.FLOOR_Y + 0.05, hood.getZ() - 0.6, 0f, 20f));
                return "보닛 앞";
            }
            default -> {
                return null;
            }
        }
    }
}
