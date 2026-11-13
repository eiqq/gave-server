package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
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
 * B4 지평선 게이브의 눈빛 '변이' 게이지(사람마다 하나, docs/모듈/보스.md 15절 바라봄). G43 탐조등({@link G43Glow})과 같은 방식이다.
 *
 * <p>눈빛 안에 있으면 3초에 다 차고({@link B4.Tune#FILL}), 벗어나면 6초에 걸쳐 빠진다. 쌓일수록 본인 화면 가장자리부터 분홍 살이 번지고(G43 의 덮개가
 * 팩에 있으면 그것), 남들 눈에는 그 사람 머리가 노란 빛 테두리와 함께 게이브 얼굴로 바뀌어 간다(머리 칸 모델 b4_mask s1..s3). 다 차면 3초 굳고(못 걷고
 * 못 쏨), 그 사이 동료가 돕기로 떼어 주지 않으면 쓰러지며 그 자리에 기본 게이브 둘이 선다. 동료가 떼면 게이지가 비고 5초 동안 안 쌓인다.
 * 죽은 사람을 조종하게 하지는 않는다(쓰러진 뒤는 판의 보통 죽음 규칙).
 */
final class B4Gaze {

    static final String MASK = "gabe/b4_mask", METER = "b4", ROOT = "b4";
    static final double S1 = 0.25, S2 = 0.5, S3 = 0.8;

    final B4 boss;
    final Game game;
    private final Map<UUID, Dose> doses = new HashMap<>();
    /** 지금까지 다 차서 굳은 수, 굳은 채 터진 수, 동료가 떼어 준 수(시험, 기록). */
    int frozen, burst, saved;

    private static final class Dose {
        final UUID id;
        double v;
        long litAt = Long.MIN_VALUE, frozenUntil = -1, immuneUntil = -1;
        int stage;
        String cover;
        boolean masked;

        Dose(UUID id) {
            this.id = id;
        }
    }

    B4Gaze(B4 boss) {
        this.boss = boss;
        this.game = boss.game;
    }

    /** 이번 틱에 p 가 눈빛 안에 있음(mul = 차는 빠르기 배율). */
    void lit(Player p, double mul) {
        long now = game.now();
        Dose d = doses.computeIfAbsent(p.getUniqueId(), Dose::new);
        if (d.immuneUntil > now || d.frozenUntil >= 0) return;
        if (d.litAt == now) return;
        d.litAt = now;
        // 빠지는 몫(DRAIN)을 미리 더하므로 위 끝도 1 + DRAIN: tick 이 뺀 뒤 꼭 1 에 닿아야 굳는다(전에는 0.992 에서 멈춰 굳지 않았음)
        d.v = Math.min(1.0 + B4.Tune.DRAIN, d.v + B4.Tune.FILL * mul + B4.Tune.DRAIN);
    }

    /** 이 사람 게이지(0..1). */
    double dose(Player p) {
        Dose d = doses.get(p.getUniqueId());
        return d == null ? 0 : Math.min(1.0, d.v);
    }

    /** 보스가 싸움 중 매 틱 부름. */
    void tick() {
        long now = game.now();
        for (Iterator<Dose> it = doses.values().iterator(); it.hasNext();) {
            Dose d = it.next();
            Player p = Bukkit.getPlayer(d.id);
            PState s = p == null ? null : game.players.get(p);
            if (p == null || !p.isOnline() || s == null || !s.standing()) {
                // 쓰러짐, 죽음, 나감: Players 가 머리, 덮기, 묶임을 풀었음
                it.remove();
                continue;
            }
            if (d.frozenUntil >= 0) {
                if (now >= d.frozenUntil) {
                    burst(p, d);
                    it.remove();
                }
                continue;
            }
            // lit 이 이번 틱 빠지는 몫까지 미리 더해 둠: 늘 DRAIN 만큼 뺌
            d.v = Math.max(0.0, d.v - B4.Tune.DRAIN);
            boolean lit = d.litAt == now;
            if (d.v <= 0 && !lit) {
                if (d.stage != 0 || d.cover != null || d.masked) {
                    clear(p, d);
                    d.stage = 0;
                }
                if (d.immuneUntil <= now) it.remove();
                continue;
            }
            int stage = d.v >= S3 ? 3 : d.v >= S2 ? 2 : d.v >= S1 ? 1 : 0;
            if (stage != d.stage) {
                d.stage = stage;
                d.masked = false;
            }
            if (now % 4 == 0) {
                game.hud.meter(p, METER, Math.min(1.0, d.v), 10);
                effects(p, s, d);
            }
            if (d.v >= 1.0 - 1e-9) freeze(p, d);
        }
    }

    /** 단계에 맞는 덮개(본인 화면)와 머리(남들 눈). 다른 종이 쓰고 있으면 건드리지 않는다. */
    private void effects(Player p, PState s, Dose d) {
        long now = game.now();
        String want = d.stage >= 3 ? "g43_v2" : d.stage >= 1 ? "g43_v1" : null;
        boolean mine = d.cover != null && d.cover.equals(s.cover);
        if (d.cover != null && !mine) d.cover = null;
        if (want == null) {
            if (mine) game.players.uncover(p);
            d.cover = null;
        } else if (!want.equals(d.cover) || s.coverUntil - now < 40) {
            if (mine) game.players.uncover(p);
            d.cover = null;
            if (Hud.hasCover(want) && game.players.cover(p, want, 200)) {
                s.coverDetach = false;
                d.cover = want;
            }
        }
        boolean free = s.headItem == null || s.headBy == boss;
        if (d.stage <= 0) {
            if (s.headBy == boss) game.players.attachHead(p, null);
            d.masked = false;
        } else if (free && (!d.masked || s.headItem == null)) {
            game.players.attachHead(p, Body.item(MASK, "s" + d.stage, new LookState(), game.worldTime()), boss);
            d.masked = true;
        }
    }

    /** 다 참: 굳음. 모두에게 알리고 그 사람 쪽 화살촉. */
    private void freeze(Player p, Dose d) {
        long now = game.now();
        d.frozenUntil = now + B4.Tune.FREEZE;
        d.stage = 3;
        frozen++;
        game.players.root(p, B4.Tune.FREEZE, ROOT);
        PState s = game.players.get(p);
        d.masked = false;
        effects(p, s, d);
        Props.soundAround(p.getLocation(), "gabe.b4.lid", Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f, 1.6f, 24, -1);
        if (Hud.hasPhrase("notice_b4_full")) game.hud.notice("notice_b4_full", Species.B4, 50);
        game.hud.point(null, p.getLocation().toVector().add(new Vector(0, 1, 0)), B4.Tune.FREEZE);
    }

    /** 굳은 채 시간이 지남: 쓰러지며 그 자리에 기본 게이브가 선다. */
    private void burst(Player p, Dose d) {
        Location l = p.getLocation();
        clear(p, d);
        burst++;
        game.players.hurt(p, 100000, boss, "cause_b4_gaze");
        int n = boss.minions(B4.Tune.CHILDREN);
        for (int i = 0; i < n; i++) {
            double a = game.random.nextDouble() * Math.PI * 2;
            Vector at = new Vector(l.getX() + Math.cos(a) * 0.9, 0, l.getZ() + Math.sin(a) * 0.9);
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + Species.G01.band.hover);
            boss.summon(Species.G01, at);
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
        d.immuneUntil = now + B4.Tune.IMMUNE;
        d.litAt = Long.MIN_VALUE;
        saved++;
        PState s = game.players.get(victim);
        if (d.cover != null && d.cover.equals(s.cover)) game.players.uncover(victim);
        d.cover = null;
        if (ROOT.equals(s.rootGroup)) game.players.clearSlows(victim);
        game.hud.meter(victim, METER, -1, 0);
    }

    private void clear(Player p, Dose d) {
        PState s = game.players.get(p);
        if (d.cover != null && d.cover.equals(s.cover)) game.players.uncover(p);
        d.cover = null;
        if (s.headBy == boss) game.players.attachHead(p, null);
        d.masked = false;
        game.hud.meter(p, METER, -1, 0);
    }

    /** 걸린 것을 모두 풂(보스가 쓰러지거나 치워질 때). 굳은 사람도 풀린다. */
    void clearAll() {
        for (Dose d : doses.values()) {
            Player p = Bukkit.getPlayer(d.id);
            if (p == null) continue;
            PState s = game.players.get(p);
            clear(p, d);
            if (d.frozenUntil >= 0 && s != null && ROOT.equals(s.rootGroup)) game.players.clearSlows(p);
        }
        doses.clear();
    }

    /** 시험 훅 '상태' 에 붙는 글: 사람마다 게이지. */
    String status() {
        StringBuilder b = new StringBuilder();
        for (Dose d : doses.values()) {
            Player p = Bukkit.getPlayer(d.id);
            b.append(b.length() == 0 ? "" : ",").append(p == null ? "?" : p.getName()).append(':')
                    .append(String.format(Locale.ROOT, "%.2f", d.v)).append(d.frozenUntil >= 0 ? "!" : "");
        }
        return (b.length() == 0 ? "-" : b.toString()) + " frozen=" + frozen + " burst=" + burst + " saved=" + saved;
    }
}
