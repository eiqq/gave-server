package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;

/**
 * '춤'(G80 굴렁쇠). 걸린 사람은 정해진 틱 동안 조준이 작은 원을 그리며 흔들리고(초당 6도 안팎), 걸음이 원을 그리며 옆으로 흐른다. 춤추는 사람 1.5블록 안에
 * 1초 넘게 머문 서 있는 동료에게 옮는다(옮은 사람도 6초). 사람에게 남기는 것은 없다(그때그때 시선 패킷과 속도만): 살아 있는 굴렁쇠가 매 틱 한 번 돌리고,
 * 굴렁쇠가 다 죽거나 웨이브가 끝나면 춤도 그 자리에서 멎는다.
 */
final class G80Dance {

    /** 춤 틱, 옮는 거리와 머무는 틱. */
    static final int TICKS = 120, CATCH_TICKS = 20;
    static final double CATCH_R = 1.5;
    /** 조준 원(도, 라디안/틱), 걸음 흐름(블록/틱). */
    static final double AIM_A = 1.4, AIM_W = 0.22, DRIFT = 0.075;

    /** 사람마다 {끝 틱, 시작 틱}. */
    private static final Map<UUID, long[]> DANCE = new HashMap<>();
    /** 춤추는 사람 곁에 머문 틱. */
    private static final Map<UUID, Integer> NEAR = new HashMap<>();
    private static long lastTick = Long.MIN_VALUE;
    private static Game owner;

    private G80Dance() {
    }

    static boolean dancing(Game game, Player p) {
        long[] d = DANCE.get(p.getUniqueId());
        return d != null && d[0] > game.now();
    }

    static int count() {
        return DANCE.size();
    }

    /** 춤을 건다(이미 추면 늦은 끝으로). */
    static void start(Game game, Player p, int ticks) {
        own(game);
        if (!game.players.get(p).standing()) return;
        long now = game.now();
        long[] d = DANCE.get(p.getUniqueId());
        if (d == null || d[0] <= now) DANCE.put(p.getUniqueId(), new long[] {now + ticks, now});
        else d[0] = Math.max(d[0], now + ticks);
        NEAR.remove(p.getUniqueId());
        game.players.addCureHook(G80Dance.class, (q, how) -> {
            if ("cleanse".equals(how) && DANCE.remove(q.getUniqueId()) != null) game.hud.mark(q, "g80", 0);
        });
        game.hud.mark(p, "g80", ticks);
        if (!game.director.silent()) Props.soundAround(p.getLocation(), "gabe.g80.dance", org.bukkit.Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1f,
                0.95f + game.random.nextFloat() * 0.1f, 1.4f, 16, -1);
    }

    private static void own(Game game) {
        if (owner != game) {
            DANCE.clear();
            NEAR.clear();
            owner = game;
        }
    }

    /** 살아 있는 굴렁쇠가 매 틱 부른다(한 틱에 한 번만 돈다). */
    static void tick(Game game) {
        own(game);
        long now = game.now();
        if (now == lastTick) return;
        lastTick = now;
        if (DANCE.isEmpty()) return;
        if (game.state != Game.State.WAVE) {
            DANCE.clear();
            NEAR.clear();
            return;
        }
        for (Iterator<Map.Entry<UUID, long[]>> it = DANCE.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, long[]> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline() || e.getValue()[0] <= now || !game.players.get(p).standing()) {
                it.remove();
                continue;
            }
            long t = now - e.getValue()[1];
            // 조준: 작은 원(2틱마다 그만큼 돌림)
            if (t % 2 == 0) {
                double a = t * AIM_W;
                float dy = (float) (-Math.sin(a) * AIM_A * AIM_W * 2), dp = (float) (Math.cos(a) * AIM_A * AIM_W * 2);
                Guns.kick(p, dy, dp);
            }
            // 걸음: 원을 그리며 옆으로 흐름
            if (t % 3 == 0) {
                double b = t * 0.09 + (e.getKey().hashCode() & 7);
                Vector v = p.getVelocity();
                p.setVelocity(v.add(new Vector(Math.cos(b) * DRIFT, 0, Math.sin(b) * DRIFT)));
            }
        }
        if (now % 5 != 0 || DANCE.isEmpty()) return;
        // 옮음: 춤추는 사람 1.5블록 안에 1초 넘게
        for (Player q : game.mobs.standing()) {
            if (dancing(game, q)) continue;
            Location lq = q.getLocation();
            boolean near = false;
            for (UUID id : DANCE.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || p == q || p.getWorld() != q.getWorld()) continue;
                if (p.getLocation().distanceSquared(lq) <= CATCH_R * CATCH_R) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                NEAR.remove(q.getUniqueId());
                continue;
            }
            int n = NEAR.merge(q.getUniqueId(), 5, Integer::sum);
            if (n >= CATCH_TICKS) start(game, q, TICKS);
        }
    }
}
