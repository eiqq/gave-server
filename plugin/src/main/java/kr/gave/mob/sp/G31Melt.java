package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.world.Plaza;

/**
 * 웅덩이 게이브(G31)의 '녹음' 게이지와 분홍 발자국. 판마다 하나, 살아 있는 웅덩이가 매 틱 {@link #tick} 을 부르고 그 틱의 첫 부름만 일한다
 * (웅덩이가 하나도 없으면 돌지 않는다: 게이지는 다음에 돌 때 밀린 틱만큼 한꺼번에 빠지고, 발자국은 연출 수명으로 저절로 사라진다).
 *
 * <p>게이지 0..1(조준점 아래 게이지, 아이콘 g31): 반죽 위에 선 시간과 걸친 넓이만큼 오르고, 오를수록 느려지며(계열 g31, 10% .. 50%),
 * 가득 차면 쓰러진다. 젖은 땅에서는 2초 뒤부터 천천히, 중앙 원(마른 돌, Plaza.R_SAFE) 안에서는 바로 빠르게 빠진다. 절반을 넘은 사람은
 * 걸은 자리에 분홍 발자국을 남기고(중앙 원 밖에서만), 다른 사람이 밟으면 그 사람 게이지가 오른다(밟힌 발자국은 사라짐).
 *
 * <p>공용 게이지가 없어 종 파일 안에 둔다(G05 즙의 G05Melt 와 따로 돈다. 요청: Players 에 공용 녹음 게이지).
 */
final class G31Melt {

    /** 마지막으로 오른 뒤 빠지기 시작하는 틱, 틱마다 빠지는 양(젖은 땅 / 중앙 원 마른 돌). */
    static final int HOLD = 40;
    static final double DECAY = 0.004, DRY_DECAY = 0.02;
    /** 느려짐이 걸리는 문턱, 발자국을 남기는 문턱, 발자국 간격(블록). */
    static final double SLOW_AT = 0.12, PRINT_AT = 0.5, PRINT_STEP = 1.15;
    /** 발자국 수명(틱), 한 번에 남는 수, 밟히는 반경, 밟은 사람에게 옮는 양. */
    static final int PRINT_LIFE = 160, PRINT_MAX = 14;
    static final double PRINT_R = 0.55, PRINT_ADD = 0.14;
    /** 가득 찼을 때 주는 피해(쓰러뜨림). */
    static final double FULL_DMG = 1000;

    private static final Map<Game, G31Melt> ALL = new WeakHashMap<>();

    static G31Melt of(Game game) {
        return ALL.computeIfAbsent(game, G31Melt::new);
    }

    /** 사람 하나: 값, 마지막 오른 틱, 마지막 발자국 자리. */
    private static final class Gauge {
        double v;
        long roseAt = Long.MIN_VALUE / 2;
        double px = Double.NaN, pz;
    }

    private static final class Print {
        ItemDisplay d;
        double x, y, z;
        UUID owner;
        long until;
    }

    private final Game game;
    private final Map<UUID, Gauge> gauges = new HashMap<>();
    private final List<Print> prints = new ArrayList<>();
    private long tickAt = Long.MIN_VALUE;
    private int round = Integer.MIN_VALUE;

    private G31Melt(Game game) {
        this.game = game;
    }

    /** 지금 녹음(0..1). */
    double get(Player p) {
        Gauge g = gauges.get(p.getUniqueId());
        return g == null ? 0 : g.v;
    }

    int prints() {
        return prints.size();
    }

    /** 녹음을 올림. 가득 차면 src 가 쓰러뜨린다(원인 cause_g31). */
    void add(Player p, double amount, Gabe src) {
        if (amount <= 0 || !game.players.get(p).standing()) return;
        Gauge g = gauges.computeIfAbsent(p.getUniqueId(), k -> new Gauge());
        g.v = Math.min(1.0, g.v + amount);
        g.roseAt = game.now();
        if (g.v >= 1.0) {
            g.v = 0;
            if (src != null) src.hit(p, FULL_DMG, "cause_g31");
            else game.players.hurt(p, FULL_DMG, null, "cause_g31");
        }
    }

    /** 살아 있는 웅덩이가 매 틱 부름: 그 틱의 첫 부름만 게이지 빠짐, 느려짐, 발자국을 돌린다. */
    void tick(Gabe caller) {
        long now = game.now();
        if (now == tickAt) return;
        if (game.round != round || now < tickAt) {
            // 새 판(또는 판 시계가 되돌아감): 모두 지움
            round = game.round;
            gauges.clear();
            clearPrints();
            tickAt = now - 1;
        }
        long skipped = Math.max(1, Math.min(2000, now - tickAt));
        tickAt = now;
        List<Player> standing = game.mobs.standing();
        for (int i = 0; i < standing.size(); i++) {
            Player p = standing.get(i);
            Gauge g = gauges.get(p.getUniqueId());
            if (g == null || g.v <= 0) continue;
            Location l = p.getLocation();
            boolean dry = Math.hypot(l.getX() - Plaza.CX, l.getZ() - Plaza.CZ) <= Plaza.R_SAFE;
            if (dry) g.v -= DRY_DECAY * skipped;
            else if (now - g.roseAt > HOLD) g.v -= DECAY * skipped;
            if (g.v <= 0) {
                g.v = 0;
                g.px = Double.NaN;
                continue;
            }
            game.hud.meter(p, "g31", g.v, 10);
            if (g.v >= SLOW_AT && now % 5 == 0) game.players.slow(p, 0.1 + 0.4 * g.v, 12, "g31");
            if (g.v >= PRINT_AT && !dry) footprint(p, g, l, now);
            else if (g.v < PRINT_AT) g.px = Double.NaN;
        }
        stepped(standing, caller, now);
    }

    /** 절반 넘게 녹은 사람이 걸은 자리에 발자국(땅에 발이 닿았을 때만). */
    private void footprint(Player p, Gauge g, Location l, long now) {
        double fl = game.mobs.flow.floor(l.getX(), l.getZ());
        if (l.getY() - fl > 0.2) return;
        if (Double.isNaN(g.px)) {
            g.px = l.getX();
            g.pz = l.getZ();
            return;
        }
        double dx = l.getX() - g.px, dz = l.getZ() - g.pz;
        if (dx * dx + dz * dz < PRINT_STEP * PRINT_STEP) return;
        g.px = l.getX();
        g.pz = l.getZ();
        while (prints.size() >= PRINT_MAX) drop(prints.get(0));
        Location at = new Location(game.world, l.getX(), fl + 0.03, l.getZ());
        Matrix4f m = new Matrix4f().rotateY((float) Math.atan2(dx, dz));
        ItemDisplay d = game.shotFx.spawn(at, "fx_quad", 0, 0, m, Display.Billboard.FIXED, false, PRINT_LIFE, ShotFx.EXTRA);
        if (d == null) return;
        d.setItemStack(Body.item("gabe/g31_print", "", new LookState(), game.worldTime()));
        d.setBrightness(Body.LIGHT);
        d.setViewRange(1.0f);
        Print pr = new Print();
        pr.d = d;
        pr.x = l.getX();
        pr.y = fl;
        pr.z = l.getZ();
        pr.owner = p.getUniqueId();
        pr.until = now + PRINT_LIFE;
        prints.add(pr);
    }

    /** 남의 발자국을 밟은 사람에게 녹음이 옮는다(밟힌 발자국은 사라짐). */
    private void stepped(List<Player> standing, Gabe caller, long now) {
        for (int i = prints.size() - 1; i >= 0; i--) {
            Print pr = prints.get(i);
            if (now >= pr.until || pr.d == null || !pr.d.isValid()) {
                prints.remove(i);
                continue;
            }
            for (int j = 0; j < standing.size(); j++) {
                Player q = standing.get(j);
                if (q.getUniqueId().equals(pr.owner)) continue;
                Location l = q.getLocation();
                double dx = l.getX() - pr.x, dz = l.getZ() - pr.z;
                if (dx * dx + dz * dz > PRINT_R * PRINT_R || Math.abs(l.getY() - pr.y) > 0.6) continue;
                add(q, PRINT_ADD, caller);
                drop(pr);
                break;
            }
        }
    }

    private void drop(Print pr) {
        if (pr.d != null && pr.d.isValid()) pr.d.remove();
        prints.remove(pr);
    }

    private void clearPrints() {
        for (Print pr : prints) if (pr.d != null && pr.d.isValid()) pr.d.remove();
        prints.clear();
    }
}
