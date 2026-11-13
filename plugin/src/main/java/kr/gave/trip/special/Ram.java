package kr.gave.trip.special;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;

/**
 * 특수 16 가로막힌 길(3~4막, specials_draft ram): 추격전(trip_special_a 의 {@link Chase})의 뒤집힘. 뒤에서 쫓기는데 앞에 얼굴 벽이
 * {@link LateTune#RAM_WALLS} 겹 차례로 막아선다(길 사건이 하나 걸러 벽). 벽마다 체력이 크고(합 {@link LateTune#RAM_WALL_HP} x 감독 체력 배율, 겹마다 더
 * 두껍게) 다 깎아야 들이받아 뚫는다. 못 깎은 몫 x {@link LateTune#RAM_FAIL} 이 트럭에서 깎인다(범퍼 부품 game.augments.ramMul() 을 곱함). 벽을 쏘는 동안
 * 뒤가 빈다(뒤쫓는 떼 x1.25). 세 겹을 다 지나면 끝(거리 막대가 다 차기 전이라도).
 */
public final class Ram extends Chase {

    private int walls, passed, evs;
    private double wallHp0, lastLeftFrac = -1, rammed;

    public Ram(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_dist";
    }

    @Override
    public void plan(Director.Plan p) {
        super.plan(p);
        scaleUnits(p, 1.25);
    }

    @Override
    protected void beginMore() {
        walls = passed = evs = 0;
        wallHp0 = 0;
        lastLeftFrac = -1;
        rammed = 0;
        dimWant = act >= 4 ? LateTune.DIM_ACT[4] : LateTune.DIM_ACT[3];
        // 첫 벽은 조금 일찍
        nextEvent = openAt + 160;
    }

    @Override
    protected String pickEvent(List<String> c) {
        evs++;
        if (walls < LateTune.RAM_WALLS && evs % 2 == 1) return "wall";
        c.remove("wall");
        return c.isEmpty() ? "bend" : c.get(rnd.nextInt(c.size()));
    }

    @Override
    protected void spawnWall(int n, double from) {
        int before = wall.size();
        super.spawnWall(n + 2 + walls * 2, from);
        int made = wall.size() - before;
        if (made <= 0) return;
        double sum = 0;
        for (int i = before; i < wall.size(); i++) {
            Gabe g = wall.get(i);
            double hp = LateTune.RAM_WALL_HP * (1 + 0.35 * walls) * game.director.hpMul(g.sp) / made;
            g.maxHp = Math.max(g.maxHp, hp);
            g.hp = g.maxHp;
            sum += g.hp;
        }
        wallHp0 = sum;
        walls++;
        sound(truckLoc(2), "sfx.dread", Sound.ENTITY_WARDEN_EMERGE, 1.6f, 0.7f, 96);
    }

    @Override
    protected void wallHit(int left, int n) {
        Truck tr = trip.truck();
        double rem = 0;
        for (Gabe g : wall) if (!g.dead && !g.removed) rem += Math.max(0, g.hp);
        double fr = wallHp0 <= 0 ? 0 : Math.min(1, rem / wallHp0);
        lastLeftFrac = fr;
        passed++;
        if (fr > 0 && tr != null) {
            double frac = LateTune.RAM_FAIL * fr * game.augments.ramMul();
            double d = tr.hitFrac(frac, null, "cause_sp_ram");
            truckDealt += d;
            rammed += d;
            wallHits++;
        }
        game.horizon.shake(fr > 0 ? 1.2 : 0.5, 18);
        sound(truckLoc(1), "trip.truck_hit", Sound.ENTITY_GENERIC_EXPLODE, 1.8f, fr > 0 ? 0.6f : 0.9f, 80);
        if (passed >= LateTune.RAM_WALLS) {
            trip.fire("chaseDone", null);
            finish(true);
        }
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        super.hudMore(p, out);
        out.put("sp.walls", passed + "/" + LateTune.RAM_WALLS);
        if (event != null && event.equals("wall") && wallHp0 > 0) {
            double rem = 0;
            for (Gabe g : wall) if (!g.dead && !g.removed) rem += Math.max(0, g.hp);
            out.put("sp.wall", rem / wallHp0);
        }
    }

    @Override
    protected String detail() {
        return super.detail() + String.format(Locale.ROOT, " walls=%d passed=%d/%d wallhp0=%.0f left=%.2f rammed=%.1f", walls, passed, LateTune.RAM_WALLS, wallHp0, lastLeftFrac, rammed);
    }
}
