package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Band;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.PadLights;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;

/**
 * 특수 20 투광등(4막 발사장, specials_draft litpad): 발사장 투광등 넷이 사분면을 하나씩 비춘다. 하늘 종이 등 머리로 날아가 앉으면 그 등이 부서져 꺼지고
 * (사분면은 칠흑: 그 안 게이브는 몸 빛이 꺼져 실눈만, 빨라짐) 발전기(등 밑동에 서 있기 5초)를 돌려야 다시 켜진다. 등을 노리는 하늘 종이 없으면 바깥에서
 * 하나 불러온다. 건조는 켜진 등 수만큼 빨라지고 넷이 다 꺼지면 뒤로 간다.
 *
 * <p>등: 발사장(Launch)이 세운 등이 엔진 빛(game.mobs.lights())에 올라와 있으면 그것을 빌려 쓰고({@link PadLights#adopt}), 없으면 {@link PadLights} 의
 * 제 등 넷을 세운다(같은 자리 R 30 대각선). 끝: Launch 가 있으면 건조 한 칸 판정은 Launch 가(이 특수는 mul("build") 와 사건 buildDrain 만 냄), 없으면
 * 이 특수가 건조 막대를 채운다.
 */
public final class LitPad extends Late {

    private PadLights pad;
    /** 등을 끄러 가는 하늘 개체 → 등. */
    private final Map<Gabe, Mobs.Light> raiders = new IdentityHashMap<>();
    private long nextRaid;
    private double build;
    private int smashes, called;

    public LitPad(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_build";
    }

    private boolean launch() {
        return trip.has("Launch");
    }

    @Override
    protected boolean begin() {
        pad = PadLights.of(game);
        if (!pad.active()) {
            List<Mobs.Light> up = new ArrayList<>(game.mobs.lights());
            if (up.size() >= 2) pad.adopt(up, this);
            else pad.raise(this);
        }
        for (Mobs.Light l : pad.lights()) pad.relight(l);
        pad.generators(true);
        raiders.clear();
        build = 0;
        smashes = called = 0;
        nextRaid = game.now() + 100;
        fog(1);
        darken(0.72);
        return true;
    }

    @Override
    protected void end() {
        raiders.clear();
        if (pad != null) {
            for (Mobs.Light l : pad.lights()) pad.relight(l);
            pad.release(this);
        }
    }

    @Override
    public void plan(Director.Plan p) {
        scaleCounts(p, LateTune.LIT_UNITS);
    }

    @Override
    protected double mulFor(String k) {
        return switch (k) {
            case "hp" -> LateTune.LIT_HP;
            case "build" -> pad == null ? 1 : pad.litCount() / 4.0;
            default -> 1;
        };
    }

    @Override
    protected boolean killAll() {
        return false;
    }

    @Override
    public boolean roundDone(boolean done) {
        // 발사장 부품이 있으면 건조 한 칸 판정은 Launch 가 한다(뒤 부품 차례에서 이 false 를 덮어씀)
        if (running && launch() && !ended()) return false;
        return super.roundDone(done);
    }

    @Override
    protected double frac() {
        return launch() ? -1 : build;
    }

    @Override
    protected String text() {
        return launch() ? null : Math.round(build * 100) + "%";
    }

    @Override
    protected void step(long t) {
        long now = game.now();
        if (now % 20 == 0) swapToForeign();
        int lit = pad.litCount(), all = Math.max(1, pad.lights().size());
        darken(0.72 + PadLights.DIM_PER_LAMP * (all - lit));
        // 건조
        if (launch()) {
            if (lit == 0 && now % 20 == 0) trip.fire("buildDrain", LateTune.LIT_BACK);
        } else {
            if (lit > 0) build += (double) lit / all / (LateTune.LIT_BUILD_SECONDS * 20);
            else build = Math.max(0, build - LateTune.LIT_BACK / 20);
            if (build >= 1) {
                build = 1;
                win();
                return;
            }
        }
        raidTick(now);
        stream(LateTune.STREAM_SHARE);
    }

    /** 제 등을 세운 뒤에 발사장(Launch)이 제 등을 올렸으면(같은 라운드 beginRound 차례가 Rounds 다음) 제 것을 내리고 그쪽 것을 빌림: 두 벌이 서지 않게. */
    private void swapToForeign() {
        if (!pad.raised() || pad.owner() != this) return;
        List<Mobs.Light> mine = pad.lights(), other = new ArrayList<>();
        for (Mobs.Light l : game.mobs.lights()) if (!mine.contains(l)) other.add(l);
        if (other.size() < 2) return;
        raiders.clear();
        pad.lower(this);
        pad.adopt(other, this);
        trip.log("특수 litpad: 발사장 등 " + other.size() + "개로 바꿈");
    }

    /** 하늘 종을 켜진 등으로 보내고, 등 머리에 닿으면 부숨. */
    private void raidTick(long now) {
        raiders.entrySet().removeIf(e -> e.getKey().dead || e.getKey().removed || !e.getValue().lit());
        for (Map.Entry<Gabe, Mobs.Light> e : raiders.entrySet()) {
            Gabe g = e.getKey();
            Vector head = PadLights.headOf(e.getValue());
            if (now % 20 == 0) game.mobs.lure(g, head.clone(), 40);
            double dx = g.pos.getX() - head.getX(), dz = g.pos.getZ() - head.getZ();
            if (dx * dx + dz * dz <= LateTune.LIT_RAID_R * LateTune.LIT_RAID_R && Math.abs(g.pos.getY() - head.getY()) <= 6) {
                pad.smash(e.getValue(), g);
                smashes++;
                notice("sp_litpad_out", 40);
            }
        }
        if (now < nextRaid) return;
        nextRaid = now + LateTune.LIT_RAID_EVERY;
        List<Mobs.Light> litLamps = new ArrayList<>();
        for (Mobs.Light l : pad.lights()) if (l.lit() && !raiders.containsValue(l)) litLamps.add(l);
        if (litLamps.isEmpty()) return;
        Mobs.Light target = litLamps.get(rnd.nextInt(litLamps.size()));
        Vector head = PadLights.headOf(target);
        // 가장 가까운 하늘 개체
        Gabe best = null;
        double bd = Double.MAX_VALUE;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.removed || !g.flying || g.sp.is(Role.BOSS) || raiders.containsKey(g)) continue;
            double d = g.pos.distanceSquared(head);
            if (d < bd) {
                bd = d;
                best = g;
            }
        }
        if (best == null) {
            Species sp = rounds.pick(s -> s.band == Band.SKY && s.spawn == Species.Spawn.RING && !s.is(Role.BOSS));
            if (sp != null) {
                Vector at = polar(70, angleOf(head.getX(), head.getZ()) + (rnd.nextDouble() - 0.5) * 0.6);
                best = rounds.spawnAt(sp, at);
                if (best != null) called++;
            }
        }
        if (best == null) return;
        raiders.put(best, target);
        game.mobs.lure(best, head.clone(), 40);
        Props.soundAround(new Location(game.world, head.getX(), head.getY(), head.getZ()), "sfx.dread", Sound.ENTITY_PHANTOM_SWOOP, 1f, 1f, 0.8f, 80, -1);
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        if (pad != null) out.put("sp.lamps", pad.litCount());
    }

    @Override
    protected String statusMore() {
        return String.format(Locale.ROOT, "%s build=%.2f raiders=%d smashes=%d called=%d launch=%s", pad == null ? "pad=-" : pad.status(), build, raiders.size(), smashes, called,
                launch());
    }

    @Override
    protected String testMore(Player p, String sub, String[] a) {
        List<Mobs.Light> ls = pad.lights();
        if (ls.isEmpty()) return "등 없음";
        switch (sub) {
            case "끄기" -> {
                int i = a.length > 4 ? Integer.parseInt(a[4]) : 0;
                pad.smash(ls.get(i % ls.size()), "test");
                return "등 " + i + " 부숨 " + statusMore();
            }
            case "발전기" -> {
                if (p == null) return null;
                int i = a.length > 4 ? Integer.parseInt(a[4]) : 0;
                Vector b = ls.get(i % ls.size()).pos();
                double ang = angleOf(b.getX(), b.getZ());
                p.teleport(new Location(game.world, b.getX() - Math.cos(ang) * 1.2, b.getY() + 0.1, b.getZ() - Math.sin(ang) * 1.2, p.getLocation().getYaw(), 0));
                return "발전기 곁으로 " + statusMore();
            }
            case "등보기" -> {
                if (p == null) return null;
                int i = a.length > 4 ? Integer.parseInt(a[4]) : 0;
                Vector b = ls.get(i % ls.size()).pos();
                double ang = angleOf(b.getX(), b.getZ());
                // 등 안쪽 12 블록에서 등을 올려다봄
                Location at = new Location(game.world, b.getX() - Math.cos(ang) * 12, b.getY() + 0.1, b.getZ() - Math.sin(ang) * 12);
                at.setDirection(new Vector(Math.cos(ang), 0.45, Math.sin(ang)));
                p.teleport(at);
                return "등 " + i + " 보기 " + statusMore();
            }
            case "건조" -> {
                build = a.length > 4 ? Double.parseDouble(a[4]) : 0.95;
                return statusMore();
            }
            default -> {
                return null;
            }
        }
    }
}
