package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.player.PState;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;

/**
 * 특수 17 등 돌리면(3~4막, specials_draft eyeson): 이 라운드의 게이브는 누가 보고 있으면 멈춘다(시선 원뿔 반각 30도, {@link LateTune#EYES_RANGE} 블록).
 * 아무도 안 보는 것만 움직이고, 그것들은 빠르고 아프다. 재장전하는 사람과 동료를 일으키는(돕는) 사람의 눈은 세지 않는다: 그 순간 한꺼번에 온다.
 * 멈춘 것은 총알이 반만 먹는다(엔진 피해 배율 훅). 등 뒤 가까이 안 보이는 것이 있으면 그 사람에게만 심장 소리. 끝: 다 잡기.
 *
 * <p>멈춤은 엔진의 이번 틱 배율(speedBuff, dmgBuff)과 겉모습 멈춤(LookState.freeze)으로 건다. 보스는 빼고(판 규칙).
 */
public final class EyesOn extends Late {

    private final Set<Gabe> frozen = Collections.newSetFromMap(new IdentityHashMap<>());
    private int seenNow, rushNow;
    private long froze, rushed;

    public EyesOn(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected boolean begin() {
        frozen.clear();
        seenNow = rushNow = 0;
        froze = rushed = 0;
        fog(LateTune.EYES_FOG);
        darken(act() >= 4 ? 0.75 : 0.55);
        game.mobs.scale("trip_eyeson", (g, d) -> running && frozen.contains(g) ? LateTune.EYES_FROZEN_DMG : 1);
        return true;
    }

    @Override
    protected void end() {
        game.mobs.unscale("trip_eyeson");
        for (Gabe g : frozen) look(g, false);
        frozen.clear();
    }

    @Override
    public void plan(Director.Plan p) {
        scaleCounts(p, LateTune.EYES_UNITS);
    }

    /** 지금 보는 눈: 서 있고, 재장전 중이 아니고, 남을 돕는 중이 아닌 사람. */
    private List<Location> eyes() {
        List<Location> out = new ArrayList<>();
        for (Player p : game.players.standing()) {
            PState s = game.players.get(p);
            if (s.helping != null || game.guns.reloading(p)) continue;
            out.add(p.getEyeLocation());
        }
        return out;
    }

    @Override
    protected void step(long t) {
        List<Location> eyes = eyes();
        double r2 = LateTune.EYES_RANGE * LateTune.EYES_RANGE;
        int seen = 0, rush = 0;
        List<Gabe> unseen = new ArrayList<>();
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.removed || g.sp.is(Role.BOSS)) continue;
            boolean watched = false;
            for (Location eye : eyes) {
                double dx = g.pos.getX() - eye.getX(), dy = g.pos.getY() - eye.getY(), dz = g.pos.getZ() - eye.getZ();
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > r2 || d2 < 0.01) continue;
                Vector dir = eye.getDirection();
                double dot = (dir.getX() * dx + dir.getY() * dy + dir.getZ() * dz) / Math.sqrt(d2);
                if (dot >= LateTune.EYES_COS) {
                    watched = true;
                    break;
                }
            }
            if (watched) {
                seen++;
                g.speedBuff *= LateTune.EYES_FROZEN;
                g.dmgBuff *= 0;
                if (frozen.add(g)) {
                    froze++;
                    look(g, true);
                }
            } else {
                rush++;
                g.speedBuff *= LateTune.EYES_RUSH;
                g.dmgBuff *= LateTune.EYES_RUSH_DMG;
                if (frozen.remove(g)) {
                    rushed++;
                    look(g, false);
                }
                unseen.add(g);
            }
        }
        frozen.removeIf(g -> g.dead || g.removed);
        seenNow = seen;
        rushNow = rush;
        // 등 뒤 가까이 안 보이는 것: 심장 소리(그 사람만)
        if (game.now() % 20 == 0) {
            double h2 = LateTune.EYES_HEART_R * LateTune.EYES_HEART_R;
            for (Player p : game.players.standing()) {
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection();
                for (Gabe g : unseen) {
                    double dx = g.pos.getX() - eye.getX(), dz = g.pos.getZ() - eye.getZ();
                    if (dx * dx + dz * dz > h2) continue;
                    if (dir.getX() * dx + dir.getZ() * dz > 0) continue;
                    Props.soundTo(p, new Location(game.world, g.pos.getX(), g.pos.getY(), g.pos.getZ()), "director.heart", Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1f, 1f, -1);
                    break;
                }
            }
        }
    }

    private static void look(Gabe g, boolean freeze) {
        if (g.body == null) return;
        try {
            g.body.look.freeze = freeze;
            g.body.dirty();
        } catch (RuntimeException e) {
            // 겉모습이 없는 몸
        }
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.watched", seenNow);
    }

    @Override
    protected String statusMore() {
        return String.format(Locale.ROOT, "seen=%d rush=%d frozen=%d froze=%d rushed=%d eyes=%d", seenNow, rushNow, frozen.size(), froze, rushed, eyes().size());
    }
}
