package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Gabe;

/**
 * 바닥 재화(가짜 G07, 진짜 G07Coin)가 같이 쓰는 '쳐다봄'과 '겨눈 사격' 재기. 도감: 가짜는 판정 없이 서버 좌표만 갖고 펴질 때 판정이 생긴다.
 * 그래서 접힌 동안은 맞는 구가 없고(흘린 탄은 그냥 지나감: 피 튀김으로 공짜 판별이 안 됨), 조준선을 0.5초 댄 채 쏜 것만 여기서 서버 좌표로 잰다.
 *
 * <p>매 틱 서 있는 사람마다 눈에서 조준선까지 이 재화 중심의 수직 거리를 재고(블록에 가리면 뺌), 조준선이 닿은 틱을 사람마다 센다.
 * 그 사람의 발사 수(Guns.Arms.shots)가 늘었는데 머문 틱이 {@link #AIM} 이상이면 겨눈 사격이다(총은 몹 뒤에 틱이 돌고 반동으로
 * 조준선이 튀므로 {@link #GRACE} 틱은 벗어나도 봐줌). 할당은 조준선이 닿은 사람의 기록 하나와 가림 확인 광선뿐이다.
 */
final class G07Aim {

    /** 겨눈 사격으로 치는 머문 틱(0.5초), 조준선이 벗어나도 봐주는 틱. */
    static final int AIM = 10, GRACE = 3;
    /** 겨눈 사격을 재는 거리, 쳐다봄(회전을 멈추는 신호)을 재는 거리와 원뿔 반각(도), 조준선이 닿았다고 보는 수직 거리. */
    static final double RANGE = 48, WATCH_R = 24, WATCH_DEG = 4.5, ON = 0.5;
    private static final double WATCH_TAN = Math.tan(Math.toRadians(WATCH_DEG));

    /** 사람마다 {조준선이 머문 틱, 마지막으로 본 발사 수, 벗어난 틱}. */
    private final Map<UUID, int[]> rec = new HashMap<>(4);
    private final Location tmp = new Location(null, 0, 0, 0);
    /** 이번 틱에 이 재화를 쳐다보는 가장 가까운 사람({@link #WATCH_R} 안, 없으면 null). */
    Player watcher;

    /** 매 틱 한 번. 겨눈 사격을 한 사람(없으면 null). */
    Player tick(Gabe g) {
        Game game = g.game;
        Player shot = null, best = null;
        double bd = WATCH_R;
        double gx = g.pos.getX(), gy = g.pos.getY(), gz = g.pos.getZ();
        for (Player p : game.mobs.standing()) {
            p.getLocation(tmp);
            double ex = tmp.getX(), ey = tmp.getY() + p.getEyeHeight(), ez = tmp.getZ();
            double vx = gx - ex, vy = gy - ey, vz = gz - ez;
            double d2 = vx * vx + vy * vy + vz * vz;
            boolean on = false;
            if (d2 <= RANGE * RANGE && d2 > 1e-6) {
                double yaw = Math.toRadians(tmp.getYaw()), pitch = Math.toRadians(tmp.getPitch()), cp = Math.cos(pitch);
                double t = vx * -Math.sin(yaw) * cp + vy * -Math.sin(pitch) + vz * Math.cos(yaw) * cp;
                if (t > 0) {
                    double perp2 = d2 - t * t, d = Math.sqrt(d2), lim = Math.max(ON, d * WATCH_TAN);
                    if (perp2 <= lim * lim && game.mobs.clearLine(new Vector(ex, ey, ez), g.pos)) {
                        if (d < bd) {
                            bd = d;
                            best = p;
                        }
                        on = perp2 <= ON * ON;
                    }
                }
            }
            UUID id = p.getUniqueId();
            int[] r = rec.get(id);
            if (r == null) {
                if (!on) continue;
                r = new int[] {0, shots(game, p), 0};
                rec.put(id, r);
            }
            if (on) {
                r[0]++;
                r[2] = 0;
            } else if (++r[2] > GRACE) {
                rec.remove(id);
                continue;
            }
            int s = shots(game, p);
            if (s > r[1] && r[0] >= AIM) shot = p;
            r[1] = s;
        }
        // 나갔거나 쓰러진 사람의 기록은 가끔 치움(서 있는 사람만 위에서 갱신되므로)
        if (!rec.isEmpty() && g.age % 40 == 0) rec.keySet().removeIf(id -> {
            Player q = Bukkit.getPlayer(id);
            return q == null || !g.standing(q);
        });
        watcher = best;
        return shot;
    }

    /** 이 사람이 조준선을 대고 있는 틱(시험, 기록용). */
    int dwell(Player p) {
        int[] r = p == null ? null : rec.get(p.getUniqueId());
        return r == null ? 0 : r[0];
    }

    private static int shots(Game game, Player p) {
        var a = game.guns.arms(p);
        return a == null ? 0 : a.shots;
    }
}
