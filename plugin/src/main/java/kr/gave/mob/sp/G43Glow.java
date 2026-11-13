package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.mob.Body;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G43 탐조등 빛의 '변이' 게이지(사람마다 하나, 등불 여럿이 같이 채움). 살아 있는 등불이 매 틱 {@link #tick()} 을 돌리고(틱마다 한 번만 돎),
 * 마지막 등불이 죽거나 치워지면 {@link #leave} 가 걸린 것을 모두 푼다.
 *
 * <p>빛 안에 있으면 6초에 다 차고, 벗어나면 12초에 걸쳐 빠진다. 쌓일수록 본인 화면 가장자리부터 분홍 살이 번지고(덮개 g43_v1, g43_v2),
 * 남들 눈에는 그 사람 머리가 노란 빛 테두리와 함께 게이브 얼굴로 바뀌어 간다(머리 칸 모델 g43_mask s1..s3). 다 차면 3초 굳고(못 걷고 못 쏨),
 * 그 사이 동료가 돕기로 떼어 주지 않으면 쓰러지며 기본 게이브 2마리가 나온다. 동료가 떼면(Players.detach → Detachable) 게이지가 비고 5초 동안 안 쌓인다.
 */
final class G43Glow {

    /** 틱당 차는 양(6초), 빠지는 양(12초), 굳는 틱(3초), 떼어 준 뒤 안 쌓이는 틱. */
    static final double FILL = 1.0 / 120, DRAIN = 1.0 / 240;
    static final int FREEZE = 60, IMMUNE = 100;
    static final double S1 = 0.25, S2 = 0.5, S3 = 0.8;

    private static G43Glow inst;

    final Game game;
    private final Map<UUID, Dose> doses = new HashMap<>();
    private final List<G43> lamps = new ArrayList<>();
    private long tickAt = Long.MIN_VALUE;

    private static final class Dose {
        final UUID id;
        double v;
        long litAt = Long.MIN_VALUE, frozenUntil = -1, immuneUntil = -1, nextBirds;
        int stage;
        G43 by;
        String cover;
        boolean masked;

        Dose(UUID id) {
            this.id = id;
        }
    }

    private G43Glow(Game game) {
        this.game = game;
    }

    static G43Glow of(Game g) {
        if (inst == null || inst.game != g) inst = new G43Glow(g);
        return inst;
    }

    void enter(G43 g) {
        if (!lamps.contains(g)) lamps.add(g);
    }

    /** 등불이 죽거나 치워짐. 마지막 등불이면 걸린 것을 모두 푼다. */
    void leave(G43 g) {
        lamps.remove(g);
        lamps.removeIf(x -> x.dead);
        if (lamps.isEmpty()) clearAll();
        else {
            // 그 등불이 붙인 머리는 엔진이 떼므로 다른 등불 이름으로 다시 붙이게 표시를 지움
            for (Dose d : doses.values()) {
                if (d.by == g) {
                    d.by = lamps.get(0);
                    d.masked = false;
                }
            }
        }
    }

    /** 이번 틱에 p 가 등불 by 의 빛 안에 있음. */
    void lit(Player p, G43 by) {
        long now = game.now();
        Dose d = doses.computeIfAbsent(p.getUniqueId(), Dose::new);
        if (d.immuneUntil > now) return;
        d.litAt = now;
        if (d.by == null || d.by.dead) d.by = by;
        if (now >= d.nextBirds && !game.director.silent()) {
            // 빛이 지날 때 본인에게만 따뜻한 새소리(편안한 것이 위험 신호)
            d.nextBirds = now + 50;
            Props.sound(p, "gabe.g43.birds", Sound.ENTITY_PARROT_AMBIENT, 0.7f, 1f, 1.6f);
        }
    }

    /** 이 사람 게이지(0..1). */
    double dose(Player p) {
        Dose d = doses.get(p.getUniqueId());
        return d == null ? 0 : d.v;
    }

    /** 살아 있는 등불이 매 틱 부름(한 틱에 한 번만 돎). */
    void tick() {
        long now = game.now();
        if (tickAt == now) return;
        tickAt = now;
        for (Iterator<Dose> it = doses.values().iterator(); it.hasNext();) {
            Dose d = it.next();
            Player p = Bukkit.getPlayer(d.id);
            PState s = p == null ? null : game.players.get(p);
            if (p == null || !p.isOnline() || s == null || !s.standing()) {
                // 쓰러짐, 죽음, 나감: Players 가 머리, 덮기, 묶임을 풀었음
                it.remove();
                continue;
            }
            boolean lit = d.litAt == now;
            if (d.frozenUntil >= 0) {
                if (now >= d.frozenUntil) {
                    burst(p, d);
                    it.remove();
                }
                continue;
            }
            if (lit) d.v = Math.min(1.0, d.v + FILL);
            else d.v = Math.max(0.0, d.v - DRAIN);
            if (d.v <= 0 && !lit) {
                if (d.stage != 0 || d.cover != null || d.masked) {
                    clear(p, d);
                    d.stage = 0;
                }
                // 떼어 준 뒤 안 쌓이는 동안은 기록을 남겨 둠
                if (d.immuneUntil <= now) it.remove();
                continue;
            }
            int stage = d.v >= S3 ? 3 : d.v >= S2 ? 2 : d.v >= S1 ? 1 : 0;
            if (stage != d.stage) {
                d.stage = stage;
                d.masked = false;
            }
            if (now % 5 == 0) {
                game.hud.meter(p, "g43", d.v, 12);
                effects(p, s, d);
            }
            if (d.v >= 1.0) freeze(p, d);
        }
    }

    /** 단계에 맞는 덮개(본인 화면)와 머리(남들 눈). 다른 종이 쓰고 있으면 건드리지 않는다. */
    private void effects(Player p, PState s, Dose d) {
        long now = game.now();
        String want = d.stage >= 3 ? "g43_v2" : d.stage >= 1 ? "g43_v1" : null;
        boolean mine = d.cover != null && d.cover.equals(s.cover);
        if (d.cover != null && !mine) d.cover = null; // 누가 지웠거나 끝남
        if (want == null) {
            if (mine) game.players.uncover(p);
            d.cover = null;
        } else if (!want.equals(d.cover) || s.coverUntil - now < 40) {
            if (mine) game.players.uncover(p);
            d.cover = null;
            if (Hud.hasCover(want) && game.players.cover(p, want, 200)) {
                s.coverDetach = false; // 동료 돕기는 머리(게이브 얼굴)를 떼는 것으로 센다
                d.cover = want;
            }
        }
        boolean free = s.headItem == null || s.headBy instanceof G43;
        if (d.stage <= 0) {
            if (s.headBy instanceof G43) game.players.attachHead(p, null);
            d.masked = false;
        } else if (free && (!d.masked || s.headItem == null) && d.by != null && !d.by.dead) {
            game.players.attachHead(p, Body.item("gabe/g43_mask", "s" + d.stage, new LookState(), game.worldTime()), d.by);
            d.masked = true;
        }
    }

    /** 다 참: 3초 굳음. 모두에게 알리고 그 사람 쪽 화살촉. */
    private void freeze(Player p, Dose d) {
        long now = game.now();
        d.frozenUntil = now + FREEZE;
        d.stage = 3;
        game.players.root(p, FREEZE, "g43");
        PState s = game.players.get(p);
        d.masked = false;
        effects(p, s, d);
        if (!game.director.silent())
            Props.soundAround(p.getLocation(), "gabe.g43.bake", Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1f, 1.6f, 24, -1);
        game.hud.notice("notice_g43_full", Species.G43, 50);
        game.hud.point(null, p.getLocation().toVector().add(new Vector(0, 1, 0)), FREEZE);
    }

    /** 굳은 채 3초가 지남: 쓰러지며 기본 게이브 2마리가 나온다. */
    private void burst(Player p, Dose d) {
        Location l = p.getLocation();
        G43 by = d.by != null ? d.by : lamps.isEmpty() ? null : lamps.get(0);
        clear(p, d);
        game.players.hurt(p, 100000, by, "cause_g43");
        if (!Species.G01.implemented()) return;
        for (int i = 0; i < 2; i++) {
            double a = game.random.nextDouble() * Math.PI * 2;
            Vector at = new Vector(l.getX() + Math.cos(a) * 0.9, 0, l.getZ() + Math.sin(a) * 0.9);
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + Species.G01.band.hover);
            game.mobs.spawn(Species.G01, at, true);
        }
    }

    /** 동료가 돕기로 머리의 게이브 얼굴을 떼어 줌: 게이지가 비고 굳음이 풀린다. */
    void detached(Player victim, Player helper, String how) {
        Dose d = doses.get(victim.getUniqueId());
        if (d == null) return;
        d.masked = false;
        if (!"detach".equals(how) || helper == null) return;
        long now = game.now();
        d.v = 0;
        d.stage = 0;
        d.frozenUntil = -1;
        d.immuneUntil = now + IMMUNE;
        d.litAt = Long.MIN_VALUE;
        PState s = game.players.get(victim);
        if (d.cover != null && d.cover.equals(s.cover)) game.players.uncover(victim);
        d.cover = null;
        if ("g43".equals(s.rootGroup)) game.players.clearSlows(victim);
        game.hud.meter(victim, "g43", -1, 0);
    }

    private void clear(Player p, Dose d) {
        PState s = game.players.get(p);
        if (d.cover != null && d.cover.equals(s.cover)) game.players.uncover(p);
        d.cover = null;
        if (s.headBy instanceof G43) game.players.attachHead(p, null);
        d.masked = false;
        game.hud.meter(p, "g43", -1, 0);
    }

    private void clearAll() {
        for (Dose d : doses.values()) {
            Player p = Bukkit.getPlayer(d.id);
            if (p == null) continue;
            PState s = game.players.get(p);
            clear(p, d);
            if (d.frozenUntil >= 0 && "g43".equals(s.rootGroup)) game.players.clearSlows(p);
        }
        doses.clear();
    }
}
