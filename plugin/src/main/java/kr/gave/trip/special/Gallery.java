package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;

/**
 * 11 사격장(1~3막): 금빛 게이브가 줄지어 중앙 원 둘레를 가로질러 달아난다(잡으면 돈 GOLD_REWARD 배). 넷에 하나는 붉은 실눈: 그것을 맞히면 그 줄 전체가 돌아서
 * 덤빈다(빠르기, 피해 배율). 실력대로 벌되 욕심내면 물린다. 끝: GALLERY_SECONDS.
 */
public final class Gallery extends Special {

    private final class Line {
        final List<Gabe> members = new ArrayList<>();
        final Set<Gabe> red = Collections.newSetFromMap(new IdentityHashMap<>());
        Vector to;
        boolean mad;
    }

    private final List<Line> lines = new ArrayList<>();
    private final Map<Gabe, Line> lineOf = new IdentityHashMap<>();
    private long dur, nextLine;
    private int made, mads, escaped;
    private Species sp;

    public Gallery(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_hunt";
    }

    @Override
    public void plan(Director.Plan p) {
        scaleUnits(p, Tune.GALLERY_UNITS);
    }

    @Override
    protected boolean begin() {
        ownEnd = true;
        lines.clear();
        lineOf.clear();
        made = mads = escaped = 0;
        dur = Tune.GALLERY_SECONDS * 20L;
        nextLine = openAt + 40;
        sp = cheapRunner();
        if (sp == null) return false;
        scale("sp_gallery", this::onHit);
        rounds.goals().timer(Tune.GALLERY_SECONDS);
        return true;
    }

    /** 맞음 갈고리: 붉은 실눈이 맞으면 그 줄이 덤빈다(배율은 그대로 1). */
    private double onHit(Gabe g, Damage d) {
        if (!opened || ended || d.by() == null) return 1;
        Line l = lineOf.get(g);
        if (l != null && !l.mad && l.red.contains(g)) madden(l);
        return 1;
    }

    private void madden(Line l) {
        l.mad = true;
        mads++;
        for (Gabe g : l.members) {
            if (g.dead || g.removed) continue;
            // 끌기를 지움(제자리로 옮겨 lure 를 끊음): 그다음부터 제 행동으로 사람을 노림
            move(g, g.pos.clone());
            g.dmgMul = Tune.MAD_DMG;
            if (g.body != null) {
                g.body.look.palette &= ~Look.PAL_GOLD;
                g.body.look.palette |= Look.PAL_DEEP_BLUSH;
                g.body.look.open = true;
            }
        }
        say("sa_gallery_mad", 40);
        sound(loc(l.members.isEmpty() ? center() : l.members.get(0).pos, 1), null, Sound.ENTITY_RAVAGER_ROAR, 1.4f, 1.2f, 64);
    }

    private void spawnLine() {
        Line l = new Line();
        double a = rnd.nextDouble() * Math.PI * 2, r = Tune.LINE_R0 + rnd.nextDouble() * (Tune.LINE_R1 - Tune.LINE_R0);
        Vector u = new Vector(Math.cos(a), 0, Math.sin(a)), v = new Vector(-u.getZ(), 0, u.getX());
        if (rnd.nextBoolean()) v.multiply(-1);
        Vector mid = center().add(u.clone().multiply(r));
        double half = Math.max(24, r * 1.1);
        l.to = mid.clone().add(v.clone().multiply(half + 8));
        for (int i = 0; i < Tune.LINE_N; i++) {
            Vector at = mid.clone().add(v.clone().multiply(-half - i * 1.8));
            Gabe g = spawn(sp, at, true);
            if (g == null) continue;
            g.noHold = true;
            g.reward = (int) Math.max(1, Math.round(Math.max(1, g.reward) * Tune.GOLD_REWARD));
            if (g.body != null) {
                if (i % Tune.LINE_RED == Tune.LINE_RED - 1) {
                    l.red.add(g);
                    g.body.look.palette |= Look.PAL_DEEP_BLUSH;
                    g.body.look.open = true;
                } else g.body.look.palette |= Look.PAL_GOLD;
            }
            l.members.add(g);
            lineOf.put(g, l);
            made++;
        }
        lines.add(l);
        sound(loc(mid, 1), "sfx.bell", Sound.BLOCK_BELL_USE, 1.2f, 1.4f, 64);
    }

    @Override
    protected void step(long now) {
        long t = now - openAt;
        if (now >= nextLine && t < dur - 100) {
            nextLine = now + Tune.LINE_EVERY;
            spawnLine();
        }
        for (Line l : lines) {
            for (Gabe g : l.members) {
                if (g.dead || g.removed) continue;
                if (l.mad) {
                    g.speedBuff *= Tune.MAD_SPEED;
                    continue;
                }
                Vector to = l.to.clone().setY(g.pos.getY());
                if (now % 10 == 0 || g.age < 3) game.mobs.lure(g, to, 14);
                double base = Math.max(0.02, g.speed() / Math.max(0.05, g.buff()));
                g.speedBuff = Tune.RUN_SPEED / base;
                if (g.pos.clone().setY(0).distanceSquared(l.to.clone().setY(0)) < 4) {
                    g.remove();
                    escaped++;
                }
            }
        }
        lines.removeIf(l -> {
            for (Gabe g : l.members) if (!g.dead && !g.removed) return false;
            return true;
        });
        lineOf.keySet().removeIf(g -> g.dead || g.removed);
        if (t >= dur) finish(true);
    }

    @Override
    protected void finished(boolean ok) {
        // 남은 금빛 줄은 달아나 사라짐(덤빈 줄은 물러남을 따름)
        for (Line l : lines) if (!l.mad) for (Gabe g : l.members) if (!g.dead && !g.removed) g.remove();
    }

    @Override
    protected void end() {
        lines.clear();
        lineOf.clear();
    }

    @Override
    protected String detail() {
        int alive = 0;
        for (Line l : lines) for (Gabe g : l.members) if (!g.dead && !g.removed) alive++;
        return String.format(Locale.ROOT, "lines=%d made=%d alive=%d mad=%d escaped=%d sp=%s", lines.size(), made, alive, mads, escaped, sp == null ? "-" : sp.name());
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.lines", lines.size());
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        switch (what) {
            case "줄" -> {
                spawnLine();
                return "줄 하나";
            }
            case "붉은" -> {
                for (Line l : lines) if (!l.mad && !l.red.isEmpty()) {
                    madden(l);
                    return "줄이 덤빔";
                }
                return "붉은 실눈 없음";
            }
            case "거의" -> {
                openAt = game.now() - dur + 40;
                return "곧 끝";
            }
            default -> {
                return null;
            }
        }
    }
}
