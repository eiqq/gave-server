package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Mobs;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * G201 안아줘요 게이브에게 끝까지 안긴 사람의 경련(판 단위 갈고리: 안은 개체가 죽어도 이어진다).
 *
 * <p>경련은 보통 쓰러짐(Players 의 DOWNED: 바닥 좌석, 고리, 이름과 남은 초, 출혈) 위에 얹는 한 끗이다.
 * <ul>
 * <li>FIT 틱(5초) 동안 두 팔을 번갈아 허우적댄다(남들에게 보이는 팔 휘두르기 애니메이션: 시점은 건드리지 않는다)와 파닥거리는 소리.</li>
 * <li>그동안 동료가 일으키기를 WAKE 틱(1초)만 이어도 깨어난다(보통은 3초). 깨어나면 쓰러질 때 남아 있던 체력을 돌려받는다(30% 보다 많았을 때).</li>
 * <li>FIT 틱이 지나면 축 늘어져 보통 쓰러짐이 된다(일으키기 3초, 체력 30%). 출혈 시계는 처음부터 보통 쓰러짐과 같다.</li>
 * </ul>
 * 사람마다 한 틱에 하는 일: 상태 한 번 읽기. 보내는 것: 팔 휘두르기(평균 3틱에 한 번, 그 사람을 보는 사람에게 하나씩), 소리(반 초에 한 번, 12 블록 안).
 */
final class G201Fits implements Mobs.Field {

    static final String KEY = "G201";
    /** 경련 틱, 그동안 깨우는 데 드는 틱, 처음 보는 사람에게 한 줄 알림이 닿는 거리. */
    static final int FIT = 100, WAKE = 20;
    static final double HINT_R = 16;

    private static final class Fit {
        final UUID id;
        final long until;
        final double hp;
        int wake;
        long swingAt, soundAt;
        boolean left;

        Fit(UUID id, long until, double hp) {
            this.id = id;
            this.until = until;
            this.hp = hp;
        }
    }

    private final Game game;
    private final List<Fit> fits = new ArrayList<>();

    private G201Fits(Game game) {
        this.game = game;
    }

    /** 이 판의 경련 목록(없으면 걸어서). */
    static G201Fits of(Game game) {
        if (game.mobs.field(KEY) instanceof G201Fits f) return f;
        G201Fits f = new G201Fits(game);
        game.mobs.field(KEY, f);
        return f;
    }

    /** 쓰러진 사람 p 의 경련을 시작한다. hp: 쓰러질 때 남아 있던 체력(깨어나면 돌려받음, 품에서 체력이 다해 쓰러졌으면 0). */
    void add(Player p, double hp) {
        PState s = game.players.get(p);
        if (s.life != Life.DOWNED) return;
        fits.removeIf(f -> f.id.equals(p.getUniqueId()));
        fits.add(new Fit(p.getUniqueId(), game.now() + FIT, hp));
        Location at = p.getLocation();
        for (Player o : game.mobs.standing()) {
            if (o != p && o.getLocation().distanceSquared(at) <= HINT_R * HINT_R) game.hud.hint(o, "hint_g201", 60);
        }
    }

    @Override
    public boolean tick(long now) {
        for (Iterator<Fit> it = fits.iterator(); it.hasNext();) {
            Fit f = it.next();
            Player p = Bukkit.getPlayer(f.id);
            PState s = game.players.get(f.id);
            if (p == null || s == null || s.offline || s.life != Life.DOWNED || now >= f.until) {
                it.remove();
                continue;
            }
            if (now >= f.swingAt) {
                if (f.left) p.swingOffHand();
                else p.swingMainHand();
                f.left = !f.left;
                f.swingAt = now + 2 + game.random.nextInt(3);
            }
            if (now >= f.soundAt) {
                f.soundAt = now + 8 + game.random.nextInt(6);
                if (!game.director.silent())
                    Props.soundAround(p.getLocation(), "gabe.g201.fit", Sound.ENTITY_COD_FLOP, 0.8f, 0.94f + game.random.nextFloat() * 0.12f, 0.6f, 12, -1);
            }
            f.wake = s.reviver != null ? f.wake + 1 : 0;
            if (f.wake < WAKE) continue;
            it.remove();
            game.players.reviveHere(p, Bukkit.getPlayer(s.reviver));
            if (f.hp > s.hp) game.players.heal(p, f.hp - s.hp);
        }
        return !fits.isEmpty();
    }

    @Override
    public void reset() {
        fits.clear();
    }
}
