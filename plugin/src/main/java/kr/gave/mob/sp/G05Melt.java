package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import kr.gave.game.Game;
import kr.gave.mob.Gabe;

/**
 * '녹음' 게이지(G05 즙, G05 조각 웅덩이). 사람마다 0..1, 마지막으로 오른 뒤 2초가 지나면 천천히 빠진다(따로 틱을 돌지 않고 읽을 때 계산).
 * 가득 차면 큰 피해 한 번 뒤 조금 남긴다. 조준점 아래 게이지(종 아이콘 g05)로 보인다.
 *
 * <p>도감은 웅덩이 게이브(G31)와 같은 게이지라고 했지만 공용 게이지가 없어 G05 안에 둔다(요청: Players 에 공용 녹음 게이지).
 */
final class G05Melt {

    /** 마지막으로 오른 뒤 빠지기 시작하는 틱, 틱마다 빠지는 양, 가득 찼을 때 피해, 그 뒤 남는 양. */
    static final int HOLD = 40;
    static final double DECAY = 0.006, FULL_DMG = 8, AFTER = 0.25;

    private static final Map<UUID, double[]> MELT = new HashMap<>();

    private G05Melt() {
    }

    /** 지금 녹음(0..1). */
    static double get(Game game, Player p) {
        double[] m = MELT.get(p.getUniqueId());
        if (m == null) return 0;
        long now = game.now();
        double v = m[0];
        long idle = now - (long) m[1] - HOLD;
        if (idle > 0) v = Math.max(0, v - idle * DECAY);
        if (v <= 0) MELT.remove(p.getUniqueId()); // 다 빠진 칸은 지움
        return v;
    }

    /** 녹음을 올림. 가득 차면 src 가 피해(원인 cause_g05)를 주고 조금 남긴다. */
    static void add(Game game, Gabe src, Player p, double amount) {
        if (!game.players.get(p).standing()) return;
        double v = Math.min(1.0, get(game, p) + amount);
        if (v >= 1.0) {
            if (src != null) src.hit(p, FULL_DMG, "cause_g05");
            else game.players.hurt(p, FULL_DMG, null, "cause_g05");
            if (!game.players.get(p).standing()) {
                MELT.remove(p.getUniqueId()); // 그 피해로 쓰러짐: 일어난 뒤에는 0 부터
                return;
            }
            v = AFTER;
        }
        MELT.put(p.getUniqueId(), new double[] {v, game.now()});
        // 나간 사람 칸이 쌓이지 않게: 다 빠지고도 남은 오래된 칸을 가끔 쓸어 냄
        if (MELT.size() > 64) {
            long old = game.now() - HOLD - (long) Math.ceil(1.0 / DECAY);
            MELT.values().removeIf(m -> (long) m[1] < old);
        }
        game.hud.meter(p, "g05", v, HOLD + (int) Math.ceil(v / DECAY));
    }
}
