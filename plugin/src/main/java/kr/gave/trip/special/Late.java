package kr.gave.trip.special;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.Rounds;
import kr.gave.trip.SpecialRound;
import kr.gave.trip.Theme;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * 뒤 막 특수 라운드(trip_special_b)의 바탕. Rounds 가 이름으로 만들어 그 라운드 동안 TripPart 부름을 넘겨준다({@link SpecialRound}).
 *
 * <p>특수 라운드 동안에는 Rounds 의 목표 엔진(Goals)이 틱과 HUD 를 돌리지 않으므로, 끝 판정(물러남까지)과 목표 막대 값(goal.*)을 여기서 낸다.
 * 하위 클래스는 {@link #begin}(놓기), {@link #step}(매 틱, 웨이브 중), {@link #end}(치우기), 끝 조건({@link #killAll} 또는 스스로 {@link #win()},
 * {@link #lose()}), 막대({@link #frac}, {@link #text})를 채운다. 숫자는 {@link LateTune}.
 *
 * <p>어둠: 사용자 요구 '막이 오를수록 어둡게'를 따라 열 때 테마를 막의 몫({@link LateTune#DIM_ACT})과 특수의 몫 가운데 어두운 쪽으로 어둡게 하고, 닫을 때 되돌린다.
 * 알림은 HUD 로만(알림 띠, 게이지): 글귀 키 sp_&lt;키&gt;, sp_&lt;키&gt;_l(data/phrases/trip_special_b.json).
 */
public abstract class Late implements SpecialRound {

    public final Game game;
    public final Trip trip;
    public final Rounds rounds;
    protected final Random rnd = new Random();

    protected String key = "-";
    protected int round;
    protected long openAt;
    protected boolean running;
    /** 물러나는 끝(틱, -1 아직), 결과. */
    private long endAt = -1;
    private boolean ok;
    private String note;
    /** 놓은 디스플레이(닫을 때 지움). */
    protected final List<Entity> placed = new ArrayList<>();
    private boolean dimSet;
    private double dimNow;
    private boolean fogSet;
    private long nextStream;

    protected Late(Game game, Trip trip, Rounds rounds) {
        this.game = game;
        this.trip = trip;
        this.rounds = rounds;
    }

    // ------------------------------------------------------------------ 하위 클래스가 채움

    /** 목표 막대 아이콘(ti_goal_*). */
    protected String icon() {
        return "ti_goal_kill";
    }

    /** 놓기(라운드가 열린 뒤). false 면 못 엶(보통 라운드로). */
    protected abstract boolean begin();

    /** 매 틱(웨이브 중, 끝나기 전). t = 연 뒤 틱. */
    protected abstract void step(long t);

    /** 치우기(여러 번 불려도 됨). */
    protected abstract void end();

    /** 감독 판정(다 잡음)으로 끝나는 특수인가. 아니면 스스로 win/lose 를 부른다. */
    protected boolean killAll() {
        return true;
    }

    /** 다 잡기 특수에서 감독 판정 말고 더 필요한 것(예: 가짜를 다 잡음). */
    protected boolean extraDone() {
        return true;
    }

    /** 목표 막대 몫(0..1, 없으면 -1). */
    protected double frac() {
        return -1;
    }

    /** 막대 옆 짧은 글. */
    protected String text() {
        return null;
    }

    /** 차오르는 막대인가(아니면 줄어드는 시간 막대). */
    protected boolean filling() {
        return true;
    }

    /** 이 특수만의 배율(모르면 1). 막 배율(체력, 피해, 빠르기)에 곱해진다. */
    protected double mulFor(String k) {
        return 1;
    }

    /** 특수마다 더 내는 HUD 값. */
    protected void hudMore(Player p, Map<String, Object> out) {
    }

    /** 상태 글 덧붙임. */
    protected String statusMore() {
        return "";
    }

    /** 시험 훅 덧붙임('특수b x &lt;sub&gt; ...'). 모르면 null. */
    protected String testMore(Player p, String sub, String[] a) {
        return null;
    }

    // ------------------------------------------------------------------ SpecialRound

    @Override
    public final boolean open(int n, String key) {
        close();
        this.key = key;
        round = n;
        openAt = game.now();
        endAt = -1;
        ok = false;
        note = null;
        nextStream = openAt + 200;
        rnd.setSeed(trip.seed * 6151 + n * 31L + key.hashCode());
        rounds.goals().beginCustom(icon());
        running = true;
        boolean opened;
        try {
            opened = begin();
        } catch (RuntimeException e) {
            trip.log("특수 " + key + " 놓기 오류: " + e);
            opened = false;
        }
        if (!opened) {
            close();
            return false;
        }
        banner();
        trip.log("특수 " + key + " 열림 라운드 " + n + " " + statusMore());
        return true;
    }

    @Override
    public final void close() {
        if (!running) return;
        running = false;
        try {
            end();
        } catch (RuntimeException e) {
            trip.log("특수 " + key + " 치우기 오류: " + e);
        }
        for (Entity e : placed) if (e != null && e.isValid()) e.remove();
        placed.clear();
        if (dimSet) {
            Theme th = theme();
            if (th != null) th.dim(0, 1);
            dimSet = false;
        }
        if (fogSet) {
            Theme th = theme();
            game.director.bossFog(th != null ? th.fogBase() : 0);
            fogSet = false;
        }
        rounds.goals().stop();
    }

    @Override
    public void tick() {
        if (!running || game.state != Game.State.WAVE) return;
        if (endAt >= 0) return;
        long t = game.now() - openAt;
        try {
            step(t);
        } catch (RuntimeException e) {
            // 한 틱의 오류가 Rounds 의 틱 전체를 매번 막지 않게(10초에 한 번 기록)
            if (game.now() - errAt > 200) {
                errAt = game.now();
                trip.log("특수 " + key + " 틱 오류: " + e);
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[원정] 특수 " + key + " 틱 오류", e);
            }
        }
    }

    private long errAt = Long.MIN_VALUE / 2;

    @Override
    public boolean roundDone(boolean done) {
        if (!running) return done;
        long now = game.now();
        if (endAt >= 0) return now >= endAt;
        if (killAll() && done && extraDone()) {
            win();
            return false;
        }
        if (now - openAt > LateTune.MAX_SECONDS * 20L) {
            note = "time";
            lose();
        }
        return false;
    }

    @Override
    public void beginBreak(int n) {
        if (running) trip.log("특수 " + key + " 끝 라운드 " + n + (endAt < 0 ? " (끝나기 전 쉬는 시간)" : ok ? " 성공" : " 놓침" + (note == null ? "" : "(" + note + ")")));
    }

    @Override
    public void gameOver() {
        close();
    }

    @Override
    public void reset() {
        close();
    }

    /** 배율: 막마다 더 센 게이브(LateTune.HP_ACT, DMG_ACT, SPEED_ACT) x 특수의 몫. 열린 동안만. */
    @Override
    public double mul(String k) {
        if (!running) return 1;
        double m = mulFor(k);
        int a = Math.max(1, Math.min(4, act())) - 1;
        switch (k) {
            case "hp" -> m *= LateTune.HP_ACT[a];
            case "dmg" -> m *= LateTune.DMG_ACT[a];
            case "speed" -> m *= LateTune.SPEED_ACT[a];
            default -> {
            }
        }
        return m;
    }

    @Override
    public Object ask(String k) {
        if (!running) return null;
        return switch (k) {
            case "roundName" -> "sp_" + key;
            case "disp" -> placedCount();
            default -> null;
        };
    }

    @Override
    public void hud(Player p, Map<String, Object> out) {
        if (!running) return;
        try {
            hudAll(p, out);
        } catch (RuntimeException e) {
            // HUD 값 하나가 HUD 전체를 막지 않게
        }
    }

    private void hudAll(Player p, Map<String, Object> out) {
        out.put("round.key", "sp_" + key);
        out.put("round.name", "sp_" + key);
        out.put("round.line", "sp_" + key + "_l");
        long left = openAt + LateTune.BANNER_TICKS + LateTune.LINE_TICKS - game.now();
        if (left > 0) out.put("round.show", (int) left);
        out.put("goal.kind", "custom");
        double f = frac();
        if (f >= 0) out.put("goal.frac", Math.max(0, Math.min(1, f)));
        String t = text();
        if (t != null) out.put("goal.text", t);
        out.put("goal.fill", filling());
        out.put("goal.icon", icon());
        if (endAt >= 0) out.put(ok ? "goal.met" : "goal.failed", true);
        hudMore(p, out);
    }

    @Override
    public String status() {
        if (!running) return null;
        StringBuilder sb = new StringBuilder("sp=").append(key);
        double f = frac();
        if (f >= 0) sb.append(String.format(Locale.ROOT, " spgoal=%.2f", f));
        String t = text();
        if (t != null) sb.append(':').append(t);
        if (endAt >= 0) sb.append(ok ? " spend=ok" : " spend=fail").append(note == null ? "" : "(" + note + ")");
        String m = statusMore();
        if (m != null && !m.isEmpty()) sb.append(' ').append(m);
        return sb.toString();
    }

    @Override
    public String test(Player p, String[] a) {
        if (!running) return null;
        if (a[1].equals("특수값")) return tune(a);
        if (!a[1].equals("특수b")) return null;
        String sub = a.length > 3 ? a[3] : "상태";
        switch (sub) {
            case "상태" -> {
                return "[특수b] " + status() + " t=" + (game.now() - openAt) + " disp=" + placedCount();
            }
            case "성공" -> {
                win();
                return "[특수b] 성공 " + status();
            }
            case "실패" -> {
                lose();
                return "[특수b] 실패 " + status();
            }
            case "건너뛰기" -> {
                // 시간을 앞당김(초)
                int s = a.length > 4 ? Integer.parseInt(a[4]) : 10;
                openAt -= s * 20L;
                return "[특수b] " + s + "초 앞당김 " + status();
            }
            default -> {
                String r = testMore(p, sub, a);
                return r != null ? "[특수b] " + r : "특수b x <상태|성공|실패|건너뛰기 초|" + key + " 전용>";
            }
        }
    }

    // ------------------------------------------------------------------ 끝

    /** 목표를 마침: 남은 떼가 물러난 뒤 쉬는 시간. */
    protected final void win() {
        if (endAt >= 0) return;
        ok = true;
        rounds.finish(true);
        endAt = game.now() + Rounds.Tune.RETREAT_TICKS;
    }

    /** 놓침(보상 없음). */
    protected final void lose() {
        if (endAt >= 0) return;
        ok = false;
        rounds.finish(false);
        endAt = game.now() + Rounds.Tune.RETREAT_TICKS;
    }

    protected final boolean ended() {
        return endAt >= 0;
    }

    protected final void note(String s) {
        note = s;
    }

    // ------------------------------------------------------------------ 바탕 도구

    protected Theme theme() {
        return trip.part(Theme.class);
    }

    protected Truck truck() {
        return trip.truck();
    }

    protected int act() {
        return trip.act();
    }

    /** 테마를 어둡게(막 몫과 이 값 가운데 큰 것). 닫을 때 되돌림. */
    protected void darken(double want) {
        Theme th = theme();
        double d = Math.max(want, LateTune.DIM_ACT[Math.max(0, Math.min(LateTune.DIM_ACT.length - 1, act()))]);
        d = Math.max(0, Math.min(1, d));
        if (dimSet && Math.abs(d - dimNow) < 0.01) return;
        dimNow = d;
        if (th != null) th.dim(d, 0);
        dimSet = true;
    }

    protected double dimNow() {
        return dimNow;
    }

    /** 안개 단계(감독 bossFog). 닫을 때 막의 기본으로. */
    protected void fog(int level) {
        game.director.bossFog(level);
        fogSet = true;
    }

    /** 이름과 한 줄 알림 띠(글귀가 구워졌을 때만). */
    private void banner() {
        String n = "sp_" + key, l = n + "_l";
        if (Hud.hasPhrase(n)) game.hud.notice(n, LateTune.BANNER_TICKS);
        if (Hud.hasPhrase(l)) game.hud.notice(l, LateTune.LINE_TICKS);
    }

    /** 글귀가 있으면 모두에게 알림 띠. */
    protected void notice(String phrase, int ticks) {
        if (Hud.hasPhrase(phrase)) game.hud.notice(phrase, ticks);
    }

    protected void notice(Player p, String phrase, int ticks) {
        if (Hud.hasPhrase(phrase)) game.hud.notice(p, phrase, ticks);
    }

    /** 쉬는 시간에 짠 계획의 몰려오는 종 수를 k 배(미리 깔리는 종 빼고). */
    protected static void scaleCounts(Director.Plan p, double k) {
        if (p == null || k == 1) return;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            if (e.getKey().spawn == Species.Spawn.PLACED) continue;
            e.setValue(Math.max(1, (int) Math.round(e.getValue() * k)));
        }
    }

    /** 감독 대기열이 비었는데 살아 있는 수가 모자라면 채움(시간으로 끝나는 특수). */
    protected void stream(double share) {
        long now = game.now();
        if (now < nextStream) return;
        nextStream = now + LateTune.STREAM_EVERY;
        if (game.director.queued() > 0) return;
        if (game.director.aliveNow() >= game.director.aliveGoal() * share) return;
        Species sp = streamSpecies();
        // 한 번에 채우는 수: 작은 판은 STREAM_STEP 그대로, 큰 판은 채울 몫의 10분의 1(동시 수 목표가 사람 수를 따라 크니 채우는 빠르기도 따라간다)
        if (sp != null) game.director.addQueue(sp, Math.max(LateTune.STREAM_STEP, (int) Math.ceil(game.director.aliveGoal() * share / 10)));
    }

    /** 채울 종: 이 웨이브 계획의 몰려오는 종 가운데 하나, 없으면 값싼 떼. */
    protected Species streamSpecies() {
        Director.Plan p = game.director.plan();
        List<Species> c = new ArrayList<>();
        if (p != null) for (Species sp : p.counts.keySet()) {
            if (sp.spawn == Species.Spawn.RING && sp.implemented() && !sp.is(Role.BOSS)) c.add(sp);
        }
        if (!c.isEmpty() && rnd.nextDouble() < 0.7) return c.get(rnd.nextInt(c.size()));
        return rounds.cheapSwarm();
    }

    /** 가운데에서 각 ang(라디안, 0 = +x 동, PI/2 = +z 남), 반지름 r 의 바닥 자리. */
    protected static Vector polar(double r, double ang) {
        return new Vector(Plaza.CX + 0.5 + Math.cos(ang) * r, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(ang) * r);
    }

    /** 가운데에서의 수평 거리. */
    protected static double fromCenter(double x, double z) {
        double dx = x - Plaza.CX - 0.5, dz = z - Plaza.CZ - 0.5;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 가운데에서 본 각(라디안 -PI..PI). */
    protected static double angleOf(double x, double z) {
        return Math.atan2(z - Plaza.CZ - 0.5, x - Plaza.CX - 0.5);
    }

    /** 두 각의 차(0..PI). */
    protected static double angDiff(double a, double b) {
        double d = Math.abs(a - b) % (Math.PI * 2);
        return d > Math.PI ? Math.PI * 2 - d : d;
    }

    protected Location loc(Vector v, double up) {
        return new Location(game.world, v.getX(), Plaza.FLOOR_Y + up, v.getZ());
    }

    /** 살아 있는 게이브(보스 빼고). */
    protected List<Gabe> mobs() {
        List<Gabe> out = new ArrayList<>();
        for (Gabe g : game.mobs.all()) if (!g.dead && !g.removed && !g.sp.is(Role.BOSS)) out.add(g);
        return out;
    }

    protected int placedCount() {
        int n = 0;
        for (Entity e : placed) if (e != null && e.isValid()) n++;
        return n;
    }

    protected <T extends Display> T keep(T d) {
        if (d != null) placed.add(d);
        return d;
    }

    /** 감독 체력 배율(그 종: 웨이브, 사람 수 몫 포함)을 곱한 체력. */
    protected double hpFor(Species sp, double base) {
        return base * game.director.hpMul(sp);
    }

    // ------------------------------------------------------------------ 시험: 숫자 표 바꾸기

    /** '특수값 x [이름 [값]]': LateTune 의 정적 숫자 보기와 바꾸기(켠 채). */
    static String tune(String[] a) {
        try {
            if (a.length <= 3) {
                StringBuilder sb = new StringBuilder("[특수값]");
                for (Field f : LateTune.class.getFields()) if (Modifier.isStatic(f.getModifiers()) && !f.getType().isArray()) sb.append(' ').append(f.getName()).append('=').append(f.get(null));
                return sb.toString();
            }
            Field f = LateTune.class.getField(a[3]);
            if (a.length <= 4) return "[특수값] " + f.getName() + "=" + f.get(null);
            Class<?> t = f.getType();
            if (t == int.class) f.setInt(null, Integer.parseInt(a[4]));
            else if (t == double.class) f.setDouble(null, Double.parseDouble(a[4]));
            else if (t == float.class) f.setFloat(null, Float.parseFloat(a[4]));
            else return "[특수값] 바꿀 수 없는 꼴 " + t;
            return "[특수값] " + f.getName() + "=" + f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "[특수값] " + e;
        }
    }
}
