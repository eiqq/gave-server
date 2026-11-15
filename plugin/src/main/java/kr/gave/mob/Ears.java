package kr.gave.mob;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import kr.gave.Props;
import kr.gave.game.Game;

/**
 * 보탠 크리처 소리(2026-11-14 개수, docs/소리출처.md '크리처 보강 소리'). 기존 소리 호출은 그대로 두고 없는 소리만 보탠다. 사건은 팩의 공용
 * gabe.cry_*, move_*, flesh_*, corpse_*(tools/gabe/flesh.py NEAR)와 그것을 가리키는 종 별칭 gabe.&lt;종&gt;.idle, gait, gait_&lt;재질&gt;, flesh
 * (data/gabe_voice.json, 키트 finish). 팩에 그 사건이 없으면 아무것도 안 보낸다(바닐라 대체 소리 없음).
 * <p>떼가 커져도 패킷이 늘지 않게 듣는 사람마다 예산을 건다: 울음 1~1.8초에 하나, 이동, 살덩이, 사체는 갈래마다 초당 {@link #PER_SEC} 안쪽
 * (연달아 둘까지). 수백 마리여도 사람마다 4틱에 한 번 개체를 훑는 것이 서버 일의 전부다(새 엔티티, 파티클 없음).
 * <ul>
 * <li>울음: 팩에 gabe.&lt;종&gt;.idle 이 있는 종(울음 사건이 없는 종)의 {@link #CRY_R} 블록 안 가까운 여섯 가운데 하나(가까울수록 자주).</li>
 * <li>이동: gabe.&lt;종&gt;.gait(퍼덕) 또는 gait_&lt;재질&gt;(발소리, 끄는 소리)이 있는 종의 {@link #GAIT_R} 블록 안에서 움직이는 가장 가까운 개체.
 * 재질은 원정 막(1 마른 땅, 2 젖은 벌, 3 재, 4 발사장), 원정을 끄면 마른 땅.</li>
 * <li>살덩이: 죽을 때 기존 죽음 소리와 같이 gabe.&lt;종&gt;.flesh 를 음량 {@link #FLESH_VOLUME} 으로({@link #FLESH_R} 블록 안).</li>
 * <li>사체(래그돌 물리 단계, {@link Ragdoll}): 처음 세게 닿을 때 떨어짐(굳은 사체 위면 쌓임), 흐물 막대기가 있으면 셋에 하나 0.15초 뒤 딸그락,
 * 땅으로 꺼질 때 꺼짐(가까운 것만). 값싼 단계는 떨어짐 소리를 내지 않는다.</li>
 * </ul>
 * 소리 없는 웨이브에는 내지 않는다. 설정 config.yml sfx.near(끄면 이 소리만 꺼지고 기존 소리는 그대로). 먼 저음 층은 {@code Horizon.farTick}.
 * 시험 훅 '/gave 시험 소리 x [near on|off | far on|off | 울음|이동|살덩이|사체 | 부름|대답|울림|숨|거인 | 다시]'.
 */
public final class Ears {

    /** 울음 후보 반경(블록), 그 가운데 가까운 몇에서 고르나, 울음 사이(틱). */
    public static final double CRY_R = 14;
    public static final int CRY_PICK = 6, CRY_MIN = 20, CRY_MAX = 36;
    /** 이동 소리 반경, 움직임으로 칠 빠르기 제곱(블록/틱). */
    public static final double GAIT_R = 10, MOVING = 4e-4;
    /** 살덩이 층 반경과 음량(기존 죽음 소리 위에 얹는 크기, 2026-11-14 '늘어지는 웃음'을 낮게 얹기로 정함). */
    public static final double FLESH_R = 16;
    public static final float FLESH_VOLUME = 0.3f;
    /** 사체 떨어짐, 딸그락, 꺼짐 반경. */
    public static final double CORPSE_R = 16, ROD_R = 12, SINK_R = 10;
    /** 이동, 살덩이, 사체 갈래마다 한 사람이 받는 초당 수. */
    public static final double PER_SEC = 3;
    static final String[] MATS = {"dry", "mud", "ash", "pad"};
    private static final int CRY = 0, GAIT = 1, FLESH = 2, CORPSE = 3;
    private static final double EACH = 20 / PER_SEC;

    private final Game game;
    private final Mobs mobs;
    private final Random rnd;
    /** config.yml sfx.near. */
    public boolean on;
    /** 사람 → 갈래마다 다음에 보낼 수 있는 틱(울음, 이동, 살덩이, 사체). */
    private final Map<UUID, double[]> ears = new HashMap<>();
    private final String[] idle, gait, flesh;
    private final String[][] gaitMat;
    /** 시험 기록: 보낸 수(사람마다 하나씩 셈). */
    public int nCry, nGait, nFlesh, nCorpse, nRod, nSink;

    Ears(Game game, Mobs mobs) {
        this.game = game;
        this.mobs = mobs;
        this.rnd = game.random;
        boolean v = true;
        try {
            v = game.plugin.getConfig().getBoolean("sfx.near", true);
        } catch (RuntimeException ignored) {
            // 설정을 못 읽으면 켬
        }
        on = v;
        Species[] all = Species.values();
        idle = new String[all.length];
        gait = new String[all.length];
        flesh = new String[all.length];
        gaitMat = new String[all.length][MATS.length];
        for (Species s : all) {
            String k = "gabe." + s.key() + ".";
            idle[s.ordinal()] = k + "idle";
            gait[s.ordinal()] = k + "gait";
            flesh[s.ordinal()] = k + "flesh";
            for (int i = 0; i < MATS.length; i++) gaitMat[s.ordinal()][i] = k + "gait_" + MATS[i];
        }
    }

    private float jitter() {
        return 0.92f + rnd.nextFloat() * 0.16f;
    }

    private double[] ear(Player p) {
        return ears.computeIfAbsent(p.getUniqueId(), k -> new double[4]);
    }

    /** 그 갈래 예산이 남았으면 쓰고 true(초당 PER_SEC, 쉬었다 오면 연달아 둘까지). */
    private static boolean spend(double[] t, int kind, long now) {
        if (now < t[kind]) return false;
        t[kind] = Math.max(t[kind], now - EACH) + EACH;
        return true;
    }

    /** 지금 땅 재질 번호(원정 막, 원정을 끄면 마른 땅). */
    private int mat() {
        return game.trip.enabled ? Math.max(1, Math.min(MATS.length, game.trip.act())) - 1 : 0;
    }

    /** 그 종의 이동 사건(팩에 있는 것, 없으면 null). */
    private String gaitOf(Species s, int m) {
        String a = gait[s.ordinal()];
        if (Props.hasSound(a)) return a;
        String b = gaitMat[s.ordinal()][m];
        return Props.hasSound(b) ? b : null;
    }

    // ------------------------------------------------------------------ 울음과 이동(Mobs 틱마다, 4틱에 한 번 훑음)

    void tick() {
        long now = game.now();
        if (!on || (now & 3) != 0 || game.director.silent()) return;
        List<Gabe> live = mobs.all();
        if (live.isEmpty()) return;
        int m = -1;
        Gabe[] near = new Gabe[CRY_PICK];
        double[] nd = new double[CRY_PICK];
        for (Player p : game.world.getPlayers()) {
            double[] t = ear(p);
            boolean cry = now >= t[CRY], step = now >= t[GAIT];
            if (!cry && !step) continue;
            Location l = p.getLocation();
            double x = l.getX(), y = l.getY(), z = l.getZ();
            int n = 0;
            Gabe mover = null;
            String moverEv = null;
            double best = GAIT_R * GAIT_R;
            for (Gabe g : live) {
                if (g.dead) continue;
                double dx = g.pos.getX() - x, dy = g.pos.getY() - y, dz = g.pos.getZ() - z, d = dx * dx + dy * dy + dz * dz;
                if (d > CRY_R * CRY_R) continue;
                if (cry && (n < CRY_PICK || d < nd[n - 1]) && Props.hasSound(idle[g.sp.ordinal()])) {
                    // 가까운 차례로 꽂기(작은 배열)
                    int i = Math.min(n, CRY_PICK - 1);
                    while (i > 0 && nd[i - 1] > d) {
                        near[i] = near[i - 1];
                        nd[i] = nd[i - 1];
                        i--;
                    }
                    near[i] = g;
                    nd[i] = d;
                    if (n < CRY_PICK) n++;
                }
                if (step && d < best && g.vel.lengthSquared() > MOVING) {
                    if (m < 0) m = mat();
                    String ev = gaitOf(g.sp, m);
                    if (ev != null) {
                        mover = g;
                        moverEv = ev;
                        best = d;
                    }
                }
            }
            if (cry) {
                if (n == 0) t[CRY] = now + 10; // 0.5초 뒤 다시 봄
                else {
                    // 가까울수록 자주: 차례 i 의 몫 n - i
                    int r = rnd.nextInt(n * (n + 1) / 2), i = 0;
                    while ((r -= n - i) >= 0) i++;
                    Gabe g = near[i];
                    Props.soundTo(p, g.loc(), idle[g.sp.ordinal()], null, 1f, jitter(), 1f, -1);
                    t[CRY] = now + CRY_MIN + rnd.nextInt(CRY_MAX - CRY_MIN + 1);
                    nCry++;
                }
            }
            if (mover != null && spend(t, GAIT, now)) {
                Props.soundTo(p, mover.loc(), moverEv, null, 1f, jitter(), 1f, -1);
                nGait++;
            }
        }
    }

    // ------------------------------------------------------------------ 죽음과 사체

    /** 죽을 때 기존 죽음 소리와 같이(Mobs.onDeath, 소리 없는 웨이브는 부르는 쪽이 거름). */
    void flesh(Gabe g) {
        String ev = flesh[g.sp.ordinal()];
        if (!on || !Props.hasSound(ev)) return;
        Location at = g.loc();
        long now = game.now();
        for (Player p : game.world.getPlayers()) {
            if (p.getLocation().distanceSquared(at) > FLESH_R * FLESH_R || !spend(ear(p), FLESH, now)) continue;
            Props.soundTo(p, at, ev, null, FLESH_VOLUME, jitter(), 1f, -1);
            nFlesh++;
        }
    }

    /** 물리 단계 사체가 처음 세게 닿음(Ragdoll). onPile: 굳은 사체 위, rods: 흐물 막대기가 있음. */
    void corpse(double x, double y, double z, boolean onPile, boolean rods) {
        if (!on || game.director.silent()) return;
        String m = MATS[mat()];
        String ev = onPile ? "gabe.corpse_pile" : "gabe.corpse_land_" + m;
        String rod = rods && rnd.nextInt(3) == 0 ? "gabe.corpse_rod_" + m : null;
        if (!Props.hasSound(ev)) return;
        Location at = new Location(game.world, x, y, z);
        long now = game.now();
        for (Player p : game.world.getPlayers()) {
            double d = p.getLocation().distanceSquared(at);
            if (d > CORPSE_R * CORPSE_R || !spend(ear(p), CORPSE, now)) continue;
            Props.soundTo(p, at, ev, null, 1f, jitter(), 1f, -1);
            nCorpse++;
            if (rod != null && d <= ROD_R * ROD_R && Props.hasSound(rod)) {
                float j = jitter();
                game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
                    if (p.isOnline()) Props.soundTo(p, at, rod, null, 1f, j, 1f, -1);
                }, 3L);
                nRod++;
            }
        }
    }

    /** 굳은 사체가 땅으로 꺼지기 시작(Ragdoll.sink). */
    void sink(double x, double y, double z) {
        if (!on || game.director.silent() || !Props.hasSound("gabe.corpse_sink")) return;
        Location at = new Location(game.world, x, y, z);
        long now = game.now();
        for (Player p : game.world.getPlayers()) {
            if (p.getLocation().distanceSquared(at) > SINK_R * SINK_R || !spend(ear(p), CORPSE, now)) continue;
            Props.soundTo(p, at, "gabe.corpse_sink", null, 1f, jitter(), 1f, -1);
            nSink++;
        }
    }

    // ------------------------------------------------------------------ 시험

    String status() {
        return String.format(Locale.ROOT, "[소리] near=%s 울음=%d 이동=%d 살덩이=%d 사체=%d 딸그락=%d 꺼짐=%d 재질=%s 사람=%d | %s", on, nCry, nGait, nFlesh, nCorpse,
                nRod, nSink, MATS[mat()], ears.size(), game.horizon.farStatus());
    }

    /** '/gave 시험 소리 x ...'(Mobs.test 가 넘김). 갈래 이름이면 p 에게 가장 가까운 개체 소리를 예산 없이 한 번(무엇을 보냈는지 돌려줌). */
    String test(Player p, String[] a) {
        String sub = a.length > 3 ? a[3] : "";
        switch (sub) {
            case "near" -> {
                on = a.length <= 4 || !a[4].equals("off");
                return status();
            }
            case "다시" -> {
                nCry = nGait = nFlesh = nCorpse = nRod = nSink = 0;
                ears.clear();
                return status();
            }
            case "울음", "이동", "살덩이", "사체" -> {
                if (p == null) return "[소리] 사람 없음";
                Gabe g = null;
                double best = Double.MAX_VALUE;
                for (Gabe c : mobs.all()) {
                    double d = c.dead ? Double.MAX_VALUE : c.pos.distanceSquared(p.getLocation().toVector());
                    if (d < best) {
                        best = d;
                        g = c;
                    }
                }
                Location at = g != null ? g.loc() : p.getLocation();
                String ev = switch (sub) {
                    case "울음" -> g == null ? null : idle[g.sp.ordinal()];
                    case "이동" -> g == null ? null : gaitOf(g.sp, mat());
                    case "살덩이" -> g == null ? null : flesh[g.sp.ordinal()];
                    default -> "gabe.corpse_land_" + MATS[mat()];
                };
                if (ev == null || !Props.hasSound(ev)) return "[소리] " + sub + ": 팩에 사건 없음(" + ev + ", 가장 가까운 " + (g == null ? "-" : g.sp.key()) + ")";
                Props.soundTo(p, at, ev, null, sub.equals("살덩이") ? FLESH_VOLUME : 1f, 1f, 1f, -1);
                return String.format(Locale.ROOT, "[소리] 보냄 %s(%.1f블록)", ev, Math.sqrt(p.getLocation().distanceSquared(at)));
            }
            case "far", "부름", "대답", "울림", "숨", "거인" -> {
                return game.horizon.farTest(sub, a.length > 4 ? a[4] : "");
            }
            default -> {
                return status();
            }
        }
    }
}
