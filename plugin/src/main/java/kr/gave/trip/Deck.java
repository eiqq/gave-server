package kr.gave.trip;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import org.bukkit.entity.Player;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Band;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.mob.SpeciesBan;

/**
 * 원정 막 덱(docs/모듈/원정.md 2.3절). 게이브는 두 갈래다(2026-11-12 사용자 결정 "모든 장에서 등장하는 게이브랑 특정 장에서만 등장하는 게이브가 따로"):
 * 공통(G01 ~ G{@link Tune#COMMON_MAX}, 어느 막에서나 나옴)과 막 전용(그 위 번호, Species.act = data/bestiary.json act 인 막에서만 나옴. 그 막 밖에서는
 * 새 종, 돌아온 종, 밑떼, 보스 떼 어디서도 안 나옴).
 *
 * <p>막(1~10, 11~20, 21~30, 31~35)의 덱 = 그 막 전용 전부 + 아직 안 나온 공통의 몫(남은 공통 / 남은 막 수 올림, 4막은 남은 것 모두). 라운드마다 정한 수만큼
 * 새 종을 내는데 전용부터 내고, 표의 수가 남은 전용보다 많을 때만 공통으로 채운다. 보스 라운드(10, 20, 30, 35)는 덱에 남은 것(전용 + 그 막 몫의 공통)을
 * 모두 낸다(보스 떼 = 아직 못 본 얼굴들). 그래서 투표로 어느 길을 가도 35 에서 판의 모든 종이 나왔다. 감독(Director.plan)이 {@link #draw(int)} 를 읽어
 * 계획을 짜고, 웨이브를 열 때 {@link #planned} 로 확정한다. 감독이나 다른 부품이 계획에 막 밖 종을 넣으면 roundPlan 사건과 planned 에서 뺀다.
 *
 * <p>덱 차례: 1막 G01(밑떼, 첫 라운드부터 모든 라운드에 낌), 그 막 밑떼 감(전용 값싼 떼, 막의 첫 라운드에 나와 다음 라운드부터 밑떼), 전용(tier 낮은 것
 * 먼저, 같은 값끼리 판 씨앗으로 섞음), 공통 몫((원래 막, tier) 차례). 라운드마다 새 종 수는 {@link Tune#NEW_ROAD}, {@link Tune#NEW_PAD}(합 50)를 그 막 덱의
 * 종 수에 비례해 줄인 누적 목표에서 이미 나온 수를 뺀 것이라 웨이브를 건너뛰거나 한 종만 라운드(mono)가 있어도 뒤 라운드가 따라잡는다. 라운드 종류의 종
 * 갈래(comp)에 맞는 종이 있으면 그것부터. 금지표(시선 규칙 종 하나, G16 과 G47, 잠복 둘까지)에 걸리면 다음 라운드로 미루고 보스 라운드에서는 푼다.
 *
 * <p>돌아온 종과 밑떼는 '나온 공통 + 이 막 전용 중 나온 것'에서만(같은 막 70%, 앞 막에서 나온 공통 30%). 35라운드는 4막 남은 종 + 그 안에서 나온 종 스물.
 * 새 종은 감독이 {@link #gap(int)} 간격으로 한 종씩 풂, 처음 보는 종 칸은 '처음 잡거나 {@link Tune#FS_HOLD} 틱'으로 넘김(Hud.firstKilled).
 * 클래스가 없는 종과 설정 bannedSpecies 로 막은 종(SpeciesBan, 시야를 돌리는 종)은 덱에 안 든다. 숫자는 {@link Tune} 한곳(켠 채 '덱값 x 이름 값').
 */
public final class Deck implements TripPart {

    /** 숫자 표 한곳. 켠 채 '/gave 시험 덱값 x [이름 [값]]'. */
    public static final class Tune {
        /** 공통 종의 마지막 번호: G01 ~ G{COMMON_MAX} 는 모든 막 공통, 그 위 번호는 Species.act 막 전용(2026-11-12 사용자 결정, 기존 55종이 공통). */
        public static int COMMON_MAX = 55;
        /** 1~3막 라운드마다 새 종 수(막의 첫 라운드부터, 열째 = 보스 라운드는 남은 것 모두). 합 50. 막 덱의 종 수(전용 + 공통 몫)에 비례해 줄이거나 늘림. */
        public static int[] NEW_ROAD = {3, 4, 5, 5, 6, 6, 6, 5, 5, 5};
        /** 4막(31~35) 라운드마다 새 종 수(다섯째 = 35 보스 라운드는 남은 것 모두). */
        public static int[] NEW_PAD = {10, 10, 10, 10, 10};
        /** 돌아온 종 수(막별 아래, 위 포함), 같은 막에서 고르는 몫(나머지는 앞 막에서 나온 공통), comp return 이면 더할 수. */
        public static int[] BACK_MIN = {2, 2, 2, 1}, BACK_MAX = {4, 4, 4, 2};
        public static double BACK_SAME = 0.7;
        public static int BACK_RETURN = 3;
        /** 보스 라운드(10, 20, 30)의 돌아온 종 수(떼는 남은 종이 주인공). */
        public static int BOSS_BACK = 1;
        /** 35라운드(B5 모두의 게이브) 돌아온 종 수: 나온 공통 + 4막 전용 중 나온 것에서. */
        public static int FINAL_BACK = 20;
        /** 엔딩 뒤 끝없는 구간(36~, 4막 규칙)의 돌아온 종 수. */
        public static int ENDLESS_MIN = 6, ENDLESS_MAX = 9;
        /** 밑떼 수(막별). */
        public static int[] BASE_N = {1, 1, 1, 2};
        /** 몫: 밑떼 합, 새 종 하나, 돌아온 종 하나, 갈래에 맞는 종 배율, comp new 의 새 종 배율(comp return 의 새 종은 몫 없이 한 마리씩). */
        public static double SHARE_BASE = 0.3, SHARE_NEW = 0.1, SHARE_BACK = 0.1, SHARE_COMP = 2.0, SHARE_NEW_X = 2.0;
        /** 새 종을 무리에 섞는 간격(틱, 막별): 감독이 혼자 나오는 종 다음부터 이 간격으로 한 종씩 푼다. 0 이면 한꺼번에. */
        public static int[] RELEASE_GAP = {300, 300, 300, 200};
        /** 처음 보는 종 칸(HUD FS)을 넘기는 틱(막별): 처음 잡거나 이만큼 지나면. 0 이면 처음 잡을 때까지(옛 규칙). */
        public static int[] FS_HOLD = {240, 240, 240, 160};

        private Tune() {
        }
    }

    /** 아는 종 갈래(rounds_draft axes.comp). */
    public static final List<String> COMPS = List.of("mix", "swarm", "charge", "ranged", "sky", "ground", "tank", "trap", "mimic", "elite", "support", "eater",
        "latent", "giant", "objective", "mono", "duo", "new", "return");

    /** 거대 종(몸이 4블록 넘음): data/bestiary.json sizeBlocks 가 4 넘는 종(2026-11-12) + 수십이 한 몸인 G53. 크기가 안 적힌 종은 체력 900 이상이면 거대로 침. */
    public static final Set<Species> GIANT;

    static {
        EnumSet<Species> g = EnumSet.noneOf(Species.class);
        for (String id : ("G53 G61 G66 G71 G77 G85 G91 G94 G97 G100 G102 G103 G106 G107 G112 G115 G116 G119 G121 G125 G126 G127 G128 G129 G130 G131 G136 G137"
            + " G141 G142 G143 G147 G148 G149 G152 G153 G154 G155 G161 G167 G169 G170 G180 G183 G184 G188 G193 G194 G195 G196 G197 G198 G199 G200").split(" ")) {
            Species sp = Species.of(id);
            if (sp != null) g.add(sp);
        }
        GIANT = Collections.unmodifiableSet(g);
    }

    /** 값싼 것 먼저(위협, tier, 이름): 밑떼 고르기. */
    private static final Comparator<Species> CHEAP = Comparator.<Species>comparingInt(sp -> sp.threat).thenComparingInt(sp -> sp.tier).thenComparing(Enum::name);

    /** 라운드 하나의 뽑기(읽기만). */
    public static final class Draw {
        public final int round, act;
        public final String comp;
        /** 보스 라운드: 덱에 남은 종 모두가 새 종. */
        public final boolean boss;
        /** 새 종(이 판에 처음, 감독이 이 차례로 푼다), 돌아온 종, 밑떼(모든 라운드에 낌, 새 종일 수도 있음), 한 마리만 낼 종, 금지표로 미룬 종. */
        public final List<Species> fresh, back, base, single, deferred;
        /** 혼자 먼저 나와 보여 줄 종(시선 규칙, 흉내, 함정 먼저), 감독 조합 칸(앞잡이, 받침, 가시). 없으면 null. */
        public final Species solo, lead, baseRole, spike;
        /** 종 → 몫(감독이 총량을 나눔). 한 마리만 낼 종은 안 듦(감독이 1 로 넣음). */
        public final Map<Species, Double> share;
        /** 같은 뽑기인지 견주는 표지(0 이 아님). */
        public final int stamp;
        final int version;

        Draw(int round, int act, String comp, boolean boss, List<Species> fresh, List<Species> back, List<Species> base, List<Species> single,
             List<Species> deferred, Species solo, Species lead, Species baseRole, Species spike, Map<Species, Double> share, int version) {
            this.round = round;
            this.act = act;
            this.comp = comp;
            this.boss = boss;
            this.fresh = Collections.unmodifiableList(fresh);
            this.back = Collections.unmodifiableList(back);
            this.base = Collections.unmodifiableList(base);
            this.single = Collections.unmodifiableList(single);
            this.deferred = Collections.unmodifiableList(deferred);
            this.solo = solo;
            this.lead = lead;
            this.baseRole = baseRole;
            this.spike = spike;
            this.share = Collections.unmodifiableMap(share);
            this.version = version;
            this.stamp = Objects.hash(round, comp, version, fresh, back, base) | 1;
        }

        /** 새 종 + 돌아온 종 + 밑떼(겹침 없이, 차례대로). */
        public List<Species> all() {
            List<Species> out = new ArrayList<>(base);
            for (Species sp : fresh) if (!out.contains(sp)) out.add(sp);
            for (Species sp : back) if (!out.contains(sp)) out.add(sp);
            return out;
        }

        public String describe() {
            return "w" + round + " 막" + act + " " + comp + (boss ? " 보스" : "") + " 새 " + names(fresh) + (solo == null ? "" : "(혼자 " + solo.name() + ")") + " 돌아온 "
                + names(back) + " 밑떼 " + names(base) + (deferred.isEmpty() ? "" : " 미룸 " + names(deferred));
        }
    }

    private final Game game;
    private final Trip trip;
    /** 설정 trip.deck(기본 true): false 면 감독이 옛 묶음을 쓴다. */
    private boolean on;
    /** 모의 덱(판 없이 {@link #simulate} 가 만듦)의 씨앗. 진짜 덱은 trip.seed. */
    private long simSeed;
    /** 막별 덱(남은 종, 뽑힐 차례). 막의 라운드를 처음 짤 때 짓는다. 칸 0 은 안 씀. */
    @SuppressWarnings("unchecked")
    private final List<Species>[] decks = new List[Trip.ACTS + 1];
    /** 막 덱을 지을 때 그 막 덱에 든 공통(그 막 몫). */
    @SuppressWarnings("unchecked")
    private final List<Species>[] quotas = new List[Trip.ACTS + 1];
    /** 막 덱을 지을 때의 그 막 종 수(덱 + 이미 나온 그 막 전용): 그 막에서 나온 수 = 이것 - 남은 수. */
    private final int[] totals = new int[Trip.ACTS + 1];
    /** 나온 종 → 처음 계획된 라운드. */
    private final EnumMap<Species, Integer> seenAt = new EnumMap<>(Species.class);
    /** 실제로 필드에 나온 종, 처음 보는 종 칸 시계(흉내 중이 아닌 첫 개체를 본 틱), 칸을 넘긴 종. */
    private final EnumSet<Species> shown = EnumSet.noneOf(Species.class), fsDone = EnumSet.noneOf(Species.class);
    private final EnumMap<Species, Long> fsAt = new EnumMap<>(Species.class);
    /** 확정한 라운드의 뽑기, 정해 둔 갈래, 아직 안 확정한 마지막 뽑기 한 칸. */
    private final Map<Integer, Draw> committed = new HashMap<>();
    private final Map<Integer, String> comps = new HashMap<>();
    private Draw pending;
    /** 확정할 때마다 오름(뽑기가 덱 상태를 알게). */
    private int version;
    /** 지난 라운드 계획의 종(돌아온 종을 고를 때 바로 앞 라운드 것은 덜 고름). */
    private final EnumSet<Species> lastRound = EnumSet.noneOf(Species.class);

    public Deck(Game game, Trip trip) {
        this.game = game;
        this.trip = trip;
        this.on = game.plugin.getConfig().getBoolean("trip.deck", true);
    }

    /** 모의 전용(판 없이 덱만, 씨앗만 받음). */
    private Deck(long seed) {
        this.game = null;
        this.trip = null;
        this.on = true;
        this.simSeed = seed;
    }

    /** 원정이 켜져 있고 덱을 쓰는 판인가(감독이 이것을 보고 덱 길로 감). */
    public boolean active() {
        return on && trip != null && trip.enabled;
    }

    private long seed() {
        return trip != null ? trip.seed : simSeed;
    }

    /** trip.rng 와 같은 꼴(모의 덱은 제 씨앗으로). */
    private Random rng(String salt) {
        return new Random(seed() * 31 + salt.hashCode());
    }

    private void log(String s) {
        if (trip != null) trip.log(s);
    }

    // ------------------------------------------------------------------ 공통과 막 전용

    /** 종 번호(G12 → 12). G 번호가 아니면(보스) -1. */
    static int number(Species sp) {
        String s = sp.name();
        if (s.length() < 2 || s.charAt(0) != 'G') return -1;
        try {
            return Integer.parseInt(s.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 모든 막 공통 종인가(G01 ~ G{COMMON_MAX}, 보스 뺌). */
    public static boolean common(Species sp) {
        if (sp == null || sp.is(Role.BOSS)) return false;
        int n = number(sp);
        return n >= 1 && n <= Tune.COMMON_MAX;
    }

    /** 그 막 전용 종인가(공통이 아니고 Species.act 가 그 막, 보스 뺌). */
    public static boolean own(Species sp, int act) {
        return sp != null && !sp.is(Role.BOSS) && !common(sp) && sp.act == act;
    }

    /** 그 막에 나와도 되나: 공통이거나 그 막 전용(보스는 보스 고리가 따로 내니 늘 true). 36~ 끝없는 구간은 4막. */
    public static boolean allowed(Species sp, int act) {
        if (sp == null) return false;
        return sp.is(Role.BOSS) || common(sp) || sp.act == act;
    }

    /** 그 막에 나올 수 있는 구현된 종 전부(그 막 전용 + 공통, 보스 뺌, Species 차례). */
    public static List<Species> species(int act) {
        List<Species> out = new ArrayList<>();
        for (Species sp : Species.values()) if (!sp.is(Role.BOSS) && usable(sp) && (common(sp) || sp.act == act)) out.add(sp);
        return out;
    }

    /** 그 막 전용의 구현된 종(Species 차례). */
    public static List<Species> dedicated(int act) {
        List<Species> out = new ArrayList<>();
        for (Species sp : Species.values()) if (own(sp, act) && usable(sp)) out.add(sp);
        return out;
    }

    /** 덱에 들 수 있나: 클래스가 있고 설정 bannedSpecies 로 막지 않음(SpeciesBan). 막은 종은 막 전용 수, 공통 수, '판의 모든 종' 셈에서도 빠진다. */
    static boolean usable(Species sp) {
        return sp != null && sp.implemented() && !SpeciesBan.listed(sp);
    }

    /** 구현된 공통 종(Species 차례). */
    public static List<Species> commons() {
        List<Species> out = new ArrayList<>();
        for (Species sp : Species.values()) if (common(sp) && usable(sp)) out.add(sp);
        return out;
    }

    // ------------------------------------------------------------------ 갈래

    /** 그 종이 갈래에 맞나. mix, mono, duo, new, return, 모르는 갈래는 늘 true. */
    public static boolean matches(Species sp, String comp) {
        if (sp == null) return false;
        if (comp == null) return true;
        return switch (comp) {
            case "swarm" -> sp.is(Role.SWARM);
            case "charge" -> sp.is(Role.CHARGE);
            case "ranged" -> sp.is(Role.RANGED);
            case "sky" -> sp.band == Band.SKY || sp.spawn == Species.Spawn.SKY;
            case "ground" -> sp.band == Band.GROUND;
            case "tank" -> sp.is(Role.TANK) || sp.is(Role.SHIELD);
            case "trap" -> sp.is(Role.TRAP) || sp.spawn == Species.Spawn.PLACED;
            case "mimic" -> Director.MIMIC.contains(sp) || Director.GAZE.contains(sp) || sp.act == 3 && (sp.is(Role.TRAP) || sp.is(Role.DISRUPT));
            case "elite" -> sp.is(Role.ELITE);
            case "support" -> sp.is(Role.SUPPORT);
            case "eater" -> Director.EATERS.contains(sp) || sp.act == 2 && (sp.is(Role.DISRUPT) || sp.is(Role.TRAP));
            case "latent" -> Director.LATENT.contains(sp) || sp.band == Band.GROUND && sp.is(Role.TRAP);
            case "giant" -> GIANT.contains(sp) || sp.hp >= 900 && !sp.is(Role.SWARM);
            case "objective" -> sp.is(Role.OBJECTIVE);
            default -> true;
        };
    }

    /** 갈래가 종을 가르는 것인가(mix, mono, duo, new, return 은 아님). */
    private static boolean sorting(String comp) {
        return comp != null && COMPS.contains(comp) && !Set.of("mix", "mono", "duo", "new", "return").contains(comp);
    }

    /** 라운드 n 의 갈래를 정함(Rounds 가 라운드 종류를 고를 때). 그 라운드를 감독이 아직 안 썼으면 다음 draw(n) 이 다시 뽑는다. */
    public void comp(int n, String comp) {
        if (comp == null) comps.remove(n);
        else comps.put(n, comp);
    }

    /** 라운드 n 의 갈래: 정해 둔 것, 아니면 trip.ask("roundComp")(지금 라운드에 한해), 아니면 mix. */
    public String comp(int n) {
        String c = comps.get(n);
        if (c != null) return c;
        Object o = trip == null ? null : trip.ask("roundComp");
        return o == null ? "mix" : o.toString();
    }

    // ------------------------------------------------------------------ 막과 덱

    /** 막 넷의 라운드 n 이 몇째인가(0 부터). */
    static int index(int n) {
        int a = Trip.actOf(n);
        return a < 4 ? n - (a - 1) * 10 - 1 : n - 31;
    }

    /** 그 막 덱에 남은 종(뽑힐 차례대로). 아직 안 지은 막이면 지금 상태로 지어 보인 것(기억하지 않음). */
    public List<Species> left(int act) {
        if (act < 1 || act > Trip.ACTS) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(peek(act)));
    }

    /** 지금 막 덱에 남은 종. */
    public List<Species> left() {
        return left(Trip.actOf(Math.max(1, game.wave)));
    }

    /** 이 판에 나온 종(감독 계획에 든 종, 모든 막). */
    public Set<Species> seen() {
        return Collections.unmodifiableSet(seenAt.isEmpty() ? EnumSet.noneOf(Species.class) : EnumSet.copyOf(seenAt.keySet()));
    }

    /** 그 막에 나올 수 있는 종 가운데 나온 것(나온 공통 + 그 막 전용 중 나온 것). */
    public Set<Species> seen(int act) {
        EnumSet<Species> s = EnumSet.noneOf(Species.class);
        for (Species sp : seenAt.keySet()) if (!sp.is(Role.BOSS) && (common(sp) || sp.act == act)) s.add(sp);
        return s;
    }

    public boolean seen(Species sp) {
        return seenAt.containsKey(sp);
    }

    /** 처음 나온 라운드(안 나왔으면 -1). */
    public int seenAt(Species sp) {
        Integer r = seenAt.get(sp);
        return r == null ? -1 : r;
    }

    /** 그 막 전용 가운데 나온 수. */
    private int seenOwn(int act) {
        int n = 0;
        for (Species sp : seenAt.keySet()) if (own(sp, act)) n++;
        return n;
    }

    /** 나온 공통 수. */
    private int seenCommon() {
        int n = 0;
        for (Species sp : seenAt.keySet()) if (common(sp)) n++;
        return n;
    }

    /** 실제로 필드에 나온 종(엔진 개체로 확인, 흉내 중인 것 포함). */
    public Set<Species> shown() {
        return Collections.unmodifiableSet(EnumSet.copyOf(shown));
    }

    /** 새 종을 무리에 섞는 간격(틱, 막별). */
    public static long gap(int act) {
        int[] g = Tune.RELEASE_GAP;
        return g.length == 0 ? 0 : Math.max(0, g[Math.max(0, Math.min(g.length - 1, act - 1))]);
    }

    /** 나온 종 가운데 지금 막에 나와도 되고 갈래에 맞는 k 종(보스 빼고, 판 씨앗과 r 로 섞음). */
    public List<Species> pickSeen(String comp, int k, Random r) {
        int act = game == null ? Trip.ACTS : Trip.actOf(Math.max(1, game.wave));
        List<Species> pool = new ArrayList<>();
        for (Species sp : seenAt.keySet()) if (!sp.is(Role.BOSS) && allowed(sp, act) && matches(sp, comp)) pool.add(sp);
        Collections.shuffle(pool, r == null ? rng("deck.pick") : r);
        return pool.subList(0, Math.min(Math.max(0, k), pool.size()));
    }

    /** 막 덱(그 막의 라운드를 처음 짤 때 짓고 기억). 마지막 막은 아직 안 나온 종이 덱 밖에 남지 않게 채운다(35 에서 남은 종 0). */
    private List<Species> deck(int act) {
        List<Species> d = decks[act];
        if (d == null) {
            List<Species> q = new ArrayList<>();
            d = build(act, q);
            decks[act] = d;
            quotas[act] = q;
            totals[act] = d.size() + seenOwn(act);
        } else if (act >= Trip.ACTS) {
            for (Species sp : species(act)) {
                if (seenAt.containsKey(sp) || d.contains(sp)) continue;
                d.add(sp);
                totals[act]++;
                if (common(sp)) quotas[act].add(sp);
            }
        }
        return d;
    }

    /** 지은 막 덱, 아직 안 지었으면 지금 상태로 지어 본 것(기억하지 않음: 시험 훅과 목록이 앞 막에서 뒤 막의 공통 몫을 정해 버리지 않게). */
    private List<Species> peek(int act) {
        return decks[act] != null ? decks[act] : build(act, new ArrayList<>());
    }

    /**
     * 막 덱 짓기: 아직 안 나온 밑떼(1막 G01) + 그 막 밑떼 감(전용 값싼 떼) + 그 막 전용(tier 차례, 같은 값끼리 섞음) + 공통 몫. quota 에 공통 몫을 담는다.
     */
    private List<Species> build(int act, List<Species> quota) {
        Random r = rng("deck.act" + act);
        List<Species> own = new ArrayList<>();
        for (Species sp : dedicated(act)) if (!seenAt.containsKey(sp)) own.add(sp);
        Collections.shuffle(own, r);
        own.sort(Comparator.comparingInt(sp -> sp.tier));
        List<Species> fod = fodder(act);
        for (int i = fod.size() - 1; i >= 0; i--) if (own.remove(fod.get(i))) own.add(0, fod.get(i));
        List<Species> com = commonShare(act, r);
        quota.addAll(com);
        List<Species> all = new ArrayList<>();
        for (Species b : basesFor(act)) if (com.remove(b) || own.remove(b)) all.add(b);
        all.addAll(own);
        all.addAll(com);
        return all;
    }

    /**
     * 그 막 덱에 넣을 공통 몫: 아직 안 나온 공통을 (원래 막, tier) 차례(같은 값끼리 섞음)로 세워 앞에서부터 '남은 공통 / 남은 막 수'(올림)만큼.
     * 마지막 막은 남은 것 모두. 그래서 공통은 판 전체에 고르게 나뉘어 처음 나오고, 앞 막에서 못 낸 공통은 뒤 막 몫에 다시 든다.
     */
    private List<Species> commonShare(int act, Random r) {
        List<Species> pool = new ArrayList<>();
        for (Species sp : commons()) if (!seenAt.containsKey(sp)) pool.add(sp);
        Collections.shuffle(pool, r);
        pool.sort(Comparator.<Species>comparingInt(sp -> sp.act).thenComparingInt(sp -> sp.tier));
        int acts = Math.max(1, Trip.ACTS - act + 1);
        int q = act >= Trip.ACTS ? pool.size() : (int) Math.ceil(pool.size() / (double) acts);
        return new ArrayList<>(pool.subList(0, Math.min(q, pool.size())));
    }

    /**
     * 막의 밑떼(값싼 떼, 모든 라운드에 낌): 나온 종에서만(그 막 전용 중 나온 것 먼저, 그다음 나온 공통). 1막은 G01(첫 라운드부터, 새 종으로).
     * 나온 것이 하나도 없으면(웨이브를 건너뛰어 연 판) 그 막 밑떼 감이나 G01(그 라운드에 새 종으로 나옴).
     */
    private Species[] basesFor(int act) {
        int want = Math.max(0, Tune.BASE_N[Math.max(0, Math.min(Tune.BASE_N.length - 1, act - 1))]);
        List<Species> out = new ArrayList<>();
        if (act == 1 && want > 0 && common(Species.G01) && usable(Species.G01)) out.add(Species.G01);
        if (out.size() < want) {
            List<Species> ownSeen = new ArrayList<>(), comSeen = new ArrayList<>();
            for (Species sp : seenAt.keySet()) {
                if (out.contains(sp) || !baseLike(sp) || !usable(sp)) continue;
                if (own(sp, act)) ownSeen.add(sp);
                else if (common(sp)) comSeen.add(sp);
            }
            ownSeen.sort(CHEAP);
            comSeen.sort(CHEAP);
            for (Species sp : ownSeen) if (out.size() < want) out.add(sp);
            for (Species sp : comSeen) if (out.size() < want) out.add(sp);
        }
        if (out.isEmpty() && want > 0) {
            for (Species sp : fodder(act)) if (out.size() < want) out.add(sp);
            if (out.isEmpty() && common(Species.G01) && usable(Species.G01)) out.add(Species.G01);
        }
        return out.toArray(new Species[0]);
    }

    /** 그 막 밑떼 감: 그 막 전용 가운데 값 1 짜리 떼나 돌격(위협 낮은 것 먼저) BASE_N 개. 막의 첫 라운드에 새 종으로 나와 다음 라운드부터 밑떼. 1막은 없음(G01). */
    private static List<Species> fodder(int act) {
        if (act <= 1) return List.of();
        int want = Math.max(0, Tune.BASE_N[Math.max(0, Math.min(Tune.BASE_N.length - 1, act - 1))]);
        List<Species> c = new ArrayList<>();
        for (Species sp : dedicated(act)) if (baseLike(sp)) c.add(sp);
        c.sort(CHEAP);
        return c.subList(0, Math.min(want, c.size()));
    }

    private static boolean baseLike(Species sp) {
        if (sp.is(Role.BOSS) || sp.is(Role.BONUS) || sp.is(Role.TRAP) || sp.spawn == Species.Spawn.PLACED || sp.spawn == Species.Spawn.DERIVED) return false;
        if (Director.PASSIVE.contains(sp) || Director.GAZE.contains(sp) || Director.LATENT.contains(sp)) return false;
        return (sp.is(Role.SWARM) || sp.is(Role.CHARGE)) && sp.cost <= 1 && sp.threat <= 3;
    }

    /** 돌아온 종으로 다시 낼 수 있나(보스, 보상 종, 죽음 결과 같은 다른 일의 결과로만 생기는 종은 안 됨). */
    private static boolean backLike(Species sp) {
        return !sp.is(Role.BOSS) && !sp.is(Role.BONUS) && sp.spawn != Species.Spawn.DERIVED && usable(sp);
    }

    /** 보스 웨이브의 돌아온 종으로 맞나(보스 고리가 미리 깔리는 종, 탐조등을 뺀다: 남은 새 종만 되살림). */
    private static boolean bossLike(Species sp) {
        return sp.spawn != Species.Spawn.PLACED && sp != Species.G43;
    }

    /** 한 마리만 낼 종(보상 종, 죽음 결과 같은 파생 종). */
    private static boolean singleLike(Species sp) {
        return sp.is(Role.BONUS) || sp.spawn == Species.Spawn.DERIVED;
    }

    /** 금지표: chosen 에 sp 를 더하면 안 되나(시선 규칙 종 하나, G16 과 G47, 잠복 둘까지). */
    private static boolean clash(Species sp, Collection<Species> chosen) {
        int latent = 0;
        for (Species c : chosen) {
            if (Director.bannedTogether(sp, c)) return true;
            if (Director.LATENT.contains(c)) latent++;
        }
        return Director.LATENT.contains(sp) && latent >= 2;
    }

    // ------------------------------------------------------------------ 뽑기

    /** 라운드 n 의 뽑기(갈래는 {@link #comp(int)}). 확정한 라운드면 그때 것, 아니면 지금 덱 상태로 굴림(같은 상태, 같은 갈래면 늘 같음). */
    public Draw draw(int n) {
        Draw c = committed.get(n);
        if (c != null) return c;
        String comp = comp(n);
        if (pending != null && pending.round == n && pending.comp.equals(comp) && pending.version == version) return pending;
        pending = compute(n, comp, true);
        return pending;
    }

    /** 갈래를 정해서 뽑기(= comp(n, comp) 다음 draw(n)). */
    public Draw draw(int n, String comp) {
        comp(n, comp);
        return draw(n);
    }

    /** 라운드 n 의 누적 목표(그 막에서 이 라운드까지 나와야 할 종 수). */
    private int target(int n, int total) {
        int[] t = Trip.actOf(n) < 4 ? Tune.NEW_ROAD : Tune.NEW_PAD;
        int idx = Math.max(0, Math.min(t.length - 1, index(n)));
        int sum = 0, cum = 0;
        for (int i = 0; i < t.length; i++) {
            sum += Math.max(0, t[i]);
            if (i <= idx) cum += Math.max(0, t[i]);
        }
        if (sum <= 0) return total;
        return (int) Math.min(total, Math.round(total * (double) cum / sum));
    }

    /** 덱 order 에서 새 종을 k 까지(ownPass 면 그 막 전용만, 아니면 공통만). 갈래에 맞는 것 먼저, 금지표에 걸리면 미룸. */
    private static void pickFresh(List<Species> order, boolean ownPass, int k, String comp, List<Species> fresh, List<Species> chosen, List<Species> deferred) {
        for (int pass = sorting(comp) ? 0 : 1; pass < 2 && fresh.size() < k; pass++) {
            for (Species sp : order) {
                if (fresh.size() >= k) break;
                if (common(sp) == ownPass || fresh.contains(sp) || pass == 0 && !matches(sp, comp)) continue;
                if (clash(sp, chosen)) {
                    if (!deferred.contains(sp)) deferred.add(sp);
                    continue;
                }
                fresh.add(sp);
                chosen.add(sp);
            }
        }
    }

    /** store 가 false 면 아직 안 지은 막 덱을 기억하지 않는다(시험 훅 '뽑기'). */
    private Draw compute(int n, String comp, boolean store) {
        int act = Trip.actOf(Math.max(1, n));
        boolean endless = n > Trip.FINAL_ROUND;
        // 33 B3 는 4막 가운데 보스라 덱을 비우지 않는다(남은 종은 34, 35 몫): 막 끝 보스(10, 20, 30, 35)만 남은 덱을 다 낸다
        boolean boss = !endless && Trip.bossRound(n) && (n % 10 == 0 || n == Trip.FINAL_ROUND);
        Random r = new Random(seed() * 1_000_003L + n * 7919L + comp.hashCode() * 31L + version);
        List<Species> order = store ? deck(act) : peek(act);
        int total = decks[act] != null ? totals[act] : order.size() + seenOwn(act);
        int done = Math.max(0, total - order.size());
        boolean mono = comp.equals("mono"), duo = comp.equals("duo");
        List<Species> fresh = new ArrayList<>(), back = new ArrayList<>(), base = new ArrayList<>(), deferred = new ArrayList<>();
        // 밑떼(mono, duo 는 없음)
        if (!mono && !duo) for (Species b : basesFor(act)) if (usable(b) && !base.contains(b)) base.add(b);
        // 새 종 수: 누적 목표 - 이미 나온 수(보스 라운드는 남은 것 모두)
        int k = endless ? 0 : boss ? order.size() : Math.max(0, target(n, total) - done);
        if (mono) k = Math.min(k, 1);
        if (duo) k = Math.min(k, 2);
        List<Species> chosen = new ArrayList<>(base);
        // 아직 안 나온 밑떼(1막 G01)는 새 종으로 먼저
        for (Species b : base) {
            if (order.contains(b) && fresh.size() < Math.max(k, 1)) fresh.add(b);
        }
        if (boss) {
            for (Species sp : order) if (!fresh.contains(sp)) fresh.add(sp);
        } else {
            // 그 막 밑떼 감(전용 값싼 떼)을 먼저: 막의 첫 라운드에 나와 다음 라운드부터 밑떼
            for (Species f : fodder(act)) {
                if (fresh.size() >= k) break;
                if (!order.contains(f) || fresh.contains(f) || clash(f, chosen)) continue;
                fresh.add(f);
                chosen.add(f);
            }
            // 그 막 전용부터
            pickFresh(order, true, k, comp, fresh, chosen, deferred);
            // 표의 수가 남은 전용보다 많을 때만 아직 안 나온 공통(이 막 몫)으로 채움
            int ownLeft = 0;
            for (Species sp : order) if (!common(sp) && !fresh.contains(sp)) ownLeft++;
            int room = k - fresh.size() - ownLeft;
            if (room > 0) pickFresh(order, false, fresh.size() + room, comp, fresh, chosen, deferred);
            deferred.removeAll(fresh);
        }
        for (Species sp : fresh) if (!chosen.contains(sp)) chosen.add(sp);
        // 돌아온 종: 나온 공통 + 이 막 전용 중 나온 것에서만
        int want;
        List<Species> pool;
        if (mono || duo) {
            // 한 마리만 낼 종(보상, 파생)은 그 종의 몫이 없으니 세지 않음
            int real = 0;
            for (Species sp : fresh) if (!singleLike(sp)) real++;
            want = Math.max(0, (mono ? 1 : 2) - real);
            pool = seenPool(act, chosen);
        } else if (n == Trip.FINAL_ROUND) {
            want = Tune.FINAL_BACK;
            pool = seenPool(act, chosen);
        } else if (endless) {
            want = Tune.ENDLESS_MIN + r.nextInt(Math.max(1, Tune.ENDLESS_MAX - Tune.ENDLESS_MIN + 1));
            pool = seenPool(act, chosen);
        } else if (boss) {
            want = Tune.BOSS_BACK;
            pool = null;
        } else {
            int ai = Math.max(0, Math.min(Tune.BACK_MIN.length - 1, act - 1));
            int lo = Tune.BACK_MIN[ai], hi = Math.max(lo, Tune.BACK_MAX[Math.min(ai, Tune.BACK_MAX.length - 1)]);
            want = lo + r.nextInt(hi - lo + 1) + (comp.equals("return") ? Tune.BACK_RETURN : 0);
            pool = null;
        }
        if (pool != null) {
            // 이 막에 나와도 되는 나온 종 전체에서 고름(35, 끝없는 구간, mono, duo): 갈래에 맞는 것 먼저
            Collections.shuffle(pool, r);
            if (sorting(comp)) pool.sort(Comparator.comparingInt(sp -> matches(sp, comp) ? 0 : 1));
            for (Species sp : pool) {
                if (back.size() >= want) break;
                if (boss && !bossLike(sp)) continue;
                if (!boss && !endless && n != Trip.FINAL_ROUND && clash(sp, chosen)) continue;
                back.add(sp);
                chosen.add(sp);
            }
        } else {
            // 같은 막(이 막 전용 + 이 막에서 처음 나온 공통) BACK_SAME, 나머지는 앞 막에서 나온 공통
            List<Species> same = new ArrayList<>(), prev = new ArrayList<>();
            for (Map.Entry<Species, Integer> e : seenAt.entrySet()) {
                Species sp = e.getKey();
                if (chosen.contains(sp) || !backLike(sp) || !allowed(sp, act)) continue;
                if (common(sp) && Trip.actOf(Math.max(1, e.getValue())) < act) prev.add(sp);
                else same.add(sp);
            }
            for (int i = 0; i < want && (!same.isEmpty() || !prev.isEmpty()); i++) {
                List<Species> from = same.isEmpty() ? prev : prev.isEmpty() ? same : r.nextDouble() < Tune.BACK_SAME ? same : prev;
                Species sp = weighted(from, comp, r);
                from.remove(sp);
                if (sp == null || boss && !bossLike(sp) || !boss && clash(sp, chosen)) {
                    i--;
                    continue;
                }
                back.add(sp);
                chosen.add(sp);
            }
        }
        // 한 마리만 낼 종
        List<Species> single = new ArrayList<>();
        for (Species sp : fresh) if (singleLike(sp) || comp.equals("return") && !base.contains(sp)) single.add(sp);
        // 혼자 먼저(시선 규칙, 흉내, 함정 먼저). 밑떼, 한 마리만 낼 종은 아님. 보스 라운드는 없음
        Species solo = null;
        if (!boss) {
            int best = Integer.MAX_VALUE;
            for (Species sp : fresh) {
                if (base.contains(sp) || single.contains(sp)) continue;
                int rank = Director.GAZE.contains(sp) ? 0 : matches(sp, "mimic") ? 1 : sp.is(Role.TRAP) ? 2 : 3;
                if (rank < best) {
                    best = rank;
                    solo = sp;
                }
            }
        }
        // 몫
        Map<Species, Double> share = new LinkedHashMap<>();
        boolean sorts = sorting(comp);
        List<Species> all = new ArrayList<>(base);
        for (Species sp : fresh) if (!all.contains(sp)) all.add(sp);
        for (Species sp : back) if (!all.contains(sp)) all.add(sp);
        all.removeAll(single);
        if (mono) {
            if (!all.isEmpty()) share.put(all.get(0), 1.0);
        } else if (duo) {
            for (int i = 0; i < all.size() && i < 2; i++) share.put(all.get(i), i == 0 ? 0.6 : 0.4);
        } else {
            for (Species sp : all) {
                double x = base.contains(sp) ? Tune.SHARE_BASE / Math.max(1, base.size())
                    : fresh.contains(sp) ? (comp.equals("new") ? Tune.SHARE_NEW * Tune.SHARE_NEW_X : Tune.SHARE_NEW)
                    : Tune.SHARE_BACK;
                if (sorts && matches(sp, comp)) x *= Tune.SHARE_COMP;
                share.put(sp, x);
            }
        }
        // 감독 조합 칸(보스 보충, 쏠림 부름이 앞잡이와 받침을 씀)
        Species lead = null, baseRole = null, spike = null;
        for (Species sp : share.keySet()) {
            if (sp.spawn == Species.Spawn.PLACED) continue;
            if (lead == null && (sp.is(Role.CHARGE) || sp.is(Role.SWARM)) && !Director.PASSIVE.contains(sp)) lead = sp;
            else if (baseRole == null && (sp.is(Role.SHIELD) || sp.is(Role.TANK) || sp.is(Role.SUPPORT))) baseRole = sp;
            else if (spike == null && (sp.is(Role.TRAP) || sp.is(Role.DISRUPT) || sp.is(Role.RANGED))) spike = sp;
        }
        return new Draw(n, act, comp, boss, fresh, back, base, single, deferred, solo, lead, baseRole, spike, share, version);
    }

    /** 이 막에 나와도 되는 나온 종(다시 낼 수 있는 것, chosen 뺌): 나온 공통 + 이 막 전용 중 나온 것. */
    private List<Species> seenPool(int act, Collection<Species> chosen) {
        List<Species> out = new ArrayList<>();
        for (Species sp : seenAt.keySet()) if (!chosen.contains(sp) && backLike(sp) && allowed(sp, act)) out.add(sp);
        return out;
    }

    /** 무게를 둔 뽑기: 갈래에 맞으면 x3, 바로 앞 라운드에 나온 것은 x0.5. */
    private Species weighted(List<Species> from, String comp, Random r) {
        if (from.isEmpty()) return null;
        boolean sorts = sorting(comp);
        double sum = 0;
        double[] w = new double[from.size()];
        for (int i = 0; i < w.length; i++) {
            Species sp = from.get(i);
            double x = 1;
            if (sorts && matches(sp, comp)) x *= 3;
            if (lastRound.contains(sp)) x *= 0.5;
            w[i] = x;
            sum += x;
        }
        double t = r.nextDouble() * sum;
        for (int i = 0; i < w.length; i++) {
            t -= w[i];
            if (t <= 0) return from.get(i);
        }
        return from.get(from.size() - 1);
    }

    /**
     * 막 밖 종(다른 막 전용)을 감독 계획에서 뺌(몫, 쏠림 덤, 조합 칸, 새 종 칸). 감독의 보상 종 고리(이 판에 나온 보상 종: 앞 막 전용일 수 있음)나
     * 다른 부품이 넣은 것을 막는다. keepFresh 면 계획의 새 종 칸은 남김(시험 훅 '새종'이 그 칸에 막 밖 종을 넣을 수 있음). 뺀 종.
     */
    List<Species> strip(Director.Plan p, boolean keepFresh) {
        if (p == null) return List.of();
        int act = Trip.actOf(Math.max(1, p.wave));
        List<Species> out = new ArrayList<>();
        for (Species sp : p.counts.keySet()) if (!allowed(sp, act) && !(keepFresh && sp == p.fresh)) out.add(sp);
        if (out.isEmpty()) return out;
        for (Species sp : out) {
            p.counts.remove(sp);
            Integer x = p.leanCounts.remove(sp);
            if (x != null) p.leanExtra = Math.max(0, p.leanExtra - x);
        }
        if (out.contains(p.fresh)) p.fresh = null;
        if (out.contains(p.lead)) p.lead = null;
        if (out.contains(p.base)) p.base = null;
        if (out.contains(p.spike)) p.spike = null;
        boolean bonus = false;
        int el = 0;
        for (Map.Entry<Species, Integer> e : p.counts.entrySet()) {
            if (e.getKey().is(Role.BONUS)) bonus = true;
            if (e.getKey().is(Role.ELITE)) el += e.getValue();
        }
        p.bonus = p.bonus && bonus;
        p.elites = el;
        log("덱 막 밖 종 뺌 w" + p.wave + " 막" + act + " " + names(out));
        return out;
    }

    /** 감독이 라운드 n 계획을 확정함: 그 종들을 '나온 종'으로, 덱에서 뺌. 그 전에 막 밖 종을 계획에서 뺀다(species 가 계획의 종 목록이면 거기서도 빠짐). */
    public void planned(int n, Collection<Species> species) {
        if (game != null && game.director != null) {
            try {
                Director.Plan p = game.director.plan();
                if (p != null && p.wave == n) strip(p, true);
            } catch (RuntimeException e) {
                log("덱 막 밖 종 빼기 오류 w" + n + ": " + e);
            }
        }
        Draw d = draw(n);
        int fresh = 0;
        for (Species sp : species) {
            if (sp == null || sp.is(Role.BOSS) || seenAt.containsKey(sp)) continue;
            seenAt.put(sp, n);
            fresh++;
            for (int a = 1; a <= Trip.ACTS; a++) if (decks[a] != null) decks[a].remove(sp);
        }
        committed.put(n, d);
        lastRound.clear();
        for (Species sp : species) if (sp != null) lastRound.add(sp);
        version++;
        pending = null;
        int act = d.act;
        log("덱 " + d.describe() + " | 확정 새 " + fresh + " 막" + act + " 전용 " + seenOwn(act) + "/" + dedicated(act).size() + " 공통 " + seenCommon() + "/"
            + commons().size() + " 남은 " + deck(act).size());
    }

    // ------------------------------------------------------------------ 부품 고리

    @Override
    public void startGame() {
        clear();
    }

    @Override
    public void reset() {
        clear();
    }

    @Override
    public void enabled(boolean on) {
        if (!on) clear();
    }

    /** 감독이 웨이브 계획을 정한 직후(Rounds 가 고치기 전): 막 밖 종을 뺀다. */
    @Override
    public void on(String event, Object arg) {
        if ("roundPlan".equals(event) && arg instanceof Director.Plan p && active()) strip(p, false);
    }

    private void clear() {
        Arrays.fill(decks, null);
        Arrays.fill(quotas, null);
        Arrays.fill(totals, 0);
        seenAt.clear();
        shown.clear();
        fsDone.clear();
        fsAt.clear();
        committed.clear();
        comps.clear();
        lastRound.clear();
        pending = null;
        version = 0;
    }

    @Override
    public void tick() {
        if (!active() || game.state != Game.State.WAVE) return;
        long now = game.now();
        if (now % 10 != 0) return;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.removed || g.sp.is(Role.BOSS)) continue;
            shown.add(g.sp);
            if (!g.disguised && !g.untargetable) fsAt.putIfAbsent(g.sp, now);
        }
        // 처음 보는 종 칸 넘기기는 감독이 덱으로 이 라운드를 짰을 때만(옛 묶음 판은 옛 규칙)
        if (!committed.containsKey(game.wave)) return;
        int[] h = Tune.FS_HOLD;
        int hold = h.length == 0 ? 0 : h[Math.max(0, Math.min(h.length - 1, Trip.actOf(Math.max(1, game.wave)) - 1))];
        if (hold <= 0) return;
        for (Map.Entry<Species, Long> e : fsAt.entrySet()) {
            if (now - e.getValue() < hold || !fsDone.add(e.getKey())) continue;
            game.hud.firstKilled(e.getKey());
        }
    }

    @Override
    public void gameOver() {
        StringBuilder sb = new StringBuilder("덱 판 끝 w" + game.wave);
        int all = commons().size();
        for (int a = 1; a <= Trip.ACTS; a++) {
            sb.append(" 막").append(a).append(" 전용 ").append(seenOwn(a)).append('/').append(dedicated(a).size());
            all += dedicated(a).size();
        }
        sb.append(" 공통 ").append(seenCommon()).append('/').append(commons().size()).append(" 모두 ").append(seenAt.size()).append('/').append(all)
            .append(" 실제 ").append(shown.size());
        log(sb.toString());
    }

    @Override
    public Object ask(String key) {
        if (!active()) return null;
        return switch (key) {
            case "deckLeft" -> left().size();
            case "deckSeen" -> seenAt.size();
            // 이 라운드에 나와도 되는 종(나온 종 가운데 이 막에 나와도 되는 것 + 이번 라운드 뽑기): Rounds 가 종 갈래를 채울 때 이 안에서만 고른다
            case "deckSpecies" -> deckSpecies(Math.max(1, game.wave));
            default -> null;
        };
    }

    /** 라운드 n 에 나와도 되는 종: 이 판에 나온 종 가운데 그 막에 나와도 되는 것(나온 공통 + 그 막 전용 중 나온 것) + 그 라운드 뽑기. */
    public Set<Species> deckSpecies(int n) {
        int act = Trip.actOf(Math.max(1, n));
        EnumSet<Species> out = EnumSet.noneOf(Species.class);
        for (Species sp : seenAt.keySet()) if (allowed(sp, act) && !sp.is(Role.BOSS)) out.add(sp);
        Draw d = draw(n);
        out.addAll(d.all());
        out.addAll(d.single);
        return out;
    }

    @Override
    public String status() {
        if (!active()) return null;
        int a = Trip.actOf(Math.max(1, game.wave));
        return "deck=" + a + ":" + seenOwn(a) + "/" + dedicated(a).size() + "+" + seenCommon() + "/" + commons().size();
    }

    @Override
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        if (a[1].equals("덱값")) {
            if (a.length < 4) return "[덱] " + tunes();
            String v = tune(a[3], a.length > 4 ? a[4] : null);
            return v == null ? "모르는 이름이거나 못 읽는 값" : "[덱] " + a[3] + "=" + v;
        }
        if (!a[1].equals("덱")) return null;
        String sub = a.length > 3 ? a[3] : "상태";
        switch (sub) {
            case "상태" -> {
                return "[덱] " + summary();
            }
            case "1", "2", "3", "4" -> {
                int act = Integer.parseInt(sub);
                List<Species> d = peek(act);
                int com = 0;
                for (Species sp : d) if (common(sp)) com++;
                StringBuilder sb = new StringBuilder("[덱] 막" + act + " 전용 " + seenOwn(act) + "/" + dedicated(act).size() + " 공통 몫 "
                    + (quotas[act] == null ? "아직(" + com + ")" : seenCount(quotas[act]) + "/" + quotas[act].size()) + " 남은 " + d.size() + "(공통 " + com + ")"
                    + " 밑떼 " + names(Arrays.asList(basesFor(act))) + " | 남은 " + names(d) + " | 나온");
                for (Species sp : species(act)) if (seenAt.containsKey(sp)) sb.append(' ').append(sp.name()).append('@').append(seenAt.get(sp));
                return sb.toString();
            }
            case "뽑기" -> {
                int n = a.length > 4 ? Integer.parseInt(a[4]) : game.wave + 1;
                String c = a.length > 5 ? a[5] : comp(n);
                Draw d = compute(n, c, false);
                return "[덱] " + d.describe() + " 몫 " + shares(d);
            }
            case "계획" -> {
                return "[덱] " + planCheck();
            }
            case "모의" -> {
                int runs = a.length > 4 ? Math.max(1, Math.min(50, Integer.parseInt(a[4]))) : 5;
                return "[덱] " + simulate(runs, trip.seed);
            }
            default -> {
                return "덱 x [상태|1..4|뽑기 n [갈래]|계획|모의 [판 수]]";
            }
        }
    }

    private int seenCount(Collection<Species> c) {
        int n = 0;
        for (Species sp : c) if (seenAt.containsKey(sp)) n++;
        return n;
    }

    /** 막마다 전용 나온 수/전체(실제), 공통 몫, 남은 수, 공통 나온 수, 지금 라운드의 뽑기. */
    public String summary() {
        StringBuilder sb = new StringBuilder("active=" + active() + " w" + game.wave);
        for (int a = 1; a <= Trip.ACTS; a++) {
            int sh = 0;
            List<Species> own = dedicated(a);
            for (Species sp : own) if (shown.contains(sp)) sh++;
            sb.append(" 막").append(a).append('=').append(seenOwn(a)).append('/').append(own.size()).append("(실제 ").append(sh);
            if (quotas[a] != null) sb.append(" 공통 몫 ").append(seenCount(quotas[a])).append('/').append(quotas[a].size()).append(" 남은 ").append(decks[a].size());
            sb.append(')');
        }
        int csh = 0;
        for (Species sp : commons()) if (shown.contains(sp)) csh++;
        sb.append(" 공통 ").append(seenCommon()).append('/').append(commons().size()).append("(실제 ").append(csh).append(") 나온 ").append(seenAt.size())
            .append(" 실제 ").append(shown.size());
        Draw d = committed.get(game.wave);
        if (d != null) sb.append(" | ").append(d.describe());
        return sb.toString();
    }

    /** 지금 감독 계획이 덱 뽑기를 다 담았나(보스 라운드면 남은 종이 다 들어갔고 대기열 맨 앞에 있나), 막 밖 종이 없나. */
    private String planCheck() {
        Director.Plan pl = game.director.plan();
        Draw d = committed.get(game.wave);
        if (pl == null || d == null) return "계획 없음 w" + game.wave + (pl == null ? "" : " (덱 확정 없음: 옛 묶음)");
        Set<Species> want = EnumSet.noneOf(Species.class);
        want.addAll(d.all());
        want.addAll(d.single);
        List<Species> miss = new ArrayList<>(), outside = new ArrayList<>();
        for (Species sp : want) if (!pl.counts.containsKey(sp)) miss.add(sp);
        for (Species sp : pl.counts.keySet()) if (!allowed(sp, d.act)) outside.add(sp);
        List<Species> head = head(Math.max(1, d.fresh.size()));
        int inHead = 0;
        for (Species sp : d.fresh) if (head.contains(sp)) inHead++;
        int movers = 0;
        for (Species sp : d.fresh) if (sp.spawn != Species.Spawn.PLACED) movers++;
        return "w" + game.wave + (d.boss ? " 보스" : "") + " 새 " + d.fresh.size() + " 계획에 " + (want.size() - miss.size()) + "/" + want.size()
            + " 빠짐 " + names(miss) + " 막 밖 " + names(outside) + " 계획 종 " + pl.counts.size() + (d.boss ? " 대기열 맨 앞 새 종 " + inHead + "/" + movers : "")
            + " 풀기 기다림 " + gated() + " 머리 " + names(head.subList(0, Math.min(12, head.size())));
    }

    /** 감독 대기열 머리(Director.queueHead, 없는 감독이면 빈 목록: 덱이 감독 고리보다 먼저 올라가도 컴파일되게 반사로). */
    @SuppressWarnings("unchecked")
    private List<Species> head(int k) {
        try {
            return (List<Species>) Director.class.getMethod("queueHead", int.class).invoke(game.director, k);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    /** 감독이 풀기를 기다리는 덱 새 종 수(Director.gated, 없으면 -1). */
    private int gated() {
        try {
            return (Integer) Director.class.getMethod("gated").invoke(game.director);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * 덱만 따로 판을 끝까지 돌려 봄(감독 없이 뽑기를 그대로 확정, 갈래는 무작위, 판 상태를 안 건드림). 막마다 전용 나온/전체, 공통(그 막에서 처음 나온 것)/
     * 그 막 몫, 35 끝에 판의 모든 종, 막 밖 종이 뽑기나 '나와도 되는 종'(deckSpecies)에 낀 수(36~40 끝없는 구간 포함), 첫 판 보스 라운드의 남은 수.
     */
    public static String simulate(int runs, long seed) {
        Deck sim = new Deck(seed);
        int acts = Trip.ACTS;
        int[] ownMin = new int[acts + 1], ownMax = new int[acts + 1], comMin = new int[acts + 1], comMax = new int[acts + 1], qMin = new int[acts + 1],
            qMax = new int[acts + 1];
        Arrays.fill(ownMin, Integer.MAX_VALUE);
        Arrays.fill(comMin, Integer.MAX_VALUE);
        Arrays.fill(qMin, Integer.MAX_VALUE);
        int allMin = Integer.MAX_VALUE, allMax = 0, comAllMin = Integer.MAX_VALUE, bad = 0, barred = 0;
        String barredEx = null;
        int all = commons().size();
        for (int a = 1; a <= acts; a++) all += dedicated(a).size();
        String badEx = null;
        StringBuilder boss = new StringBuilder();
        String[] cs = {"mix", "swarm", "sky", "mono", "return", "trap", "giant", "duo", "new", "objective"};
        for (int run = 0; run < runs; run++) {
            sim.simSeed = seed ^ (0x9E3779B97F4A7C15L * (run + 1));
            sim.clear();
            Random cr = new Random(run * 17L + 3);
            int[] comFirst = new int[acts + 1];
            for (int n = 1; n <= Trip.FINAL_ROUND + 5; n++) {
                int act = Trip.actOf(n);
                String c = cs[cr.nextInt(cs.length)];
                List<Species> before = sim.deck(act);
                int leftBefore = before.size(), comBefore = 0;
                for (Species sp : before) if (common(sp)) comBefore++;
                Draw d = sim.draw(n, c);
                List<Species> plan = new ArrayList<>(d.all());
                for (Species sp : d.single) if (!plan.contains(sp)) plan.add(sp);
                Set<Species> may = sim.deckSpecies(n);
                may.addAll(plan);
                for (Species sp : may) {
                    if (SpeciesBan.listed(sp)) {
                        barred++;
                        if (barredEx == null) barredEx = sp.name() + "@w" + n;
                    }
                    if (allowed(sp, act)) continue;
                    bad++;
                    if (badEx == null) badEx = sp.name() + "@w" + n;
                }
                if (run == 0 && d.boss) boss.append(" w").append(n).append(" 남은 ").append(leftBefore).append("(공통 ").append(comBefore).append(") 새 ").append(d.fresh.size());
                for (Species sp : plan) if (common(sp) && !sim.seenAt.containsKey(sp)) comFirst[act]++;
                sim.seenQuiet(n, plan);
                if (n != Trip.FINAL_ROUND) continue;
                for (int a = 1; a <= acts; a++) {
                    int s = sim.seenOwn(a), q = sim.quotas[a] == null ? 0 : sim.quotas[a].size();
                    ownMin[a] = Math.min(ownMin[a], s);
                    ownMax[a] = Math.max(ownMax[a], s);
                    comMin[a] = Math.min(comMin[a], comFirst[a]);
                    comMax[a] = Math.max(comMax[a], comFirst[a]);
                    qMin[a] = Math.min(qMin[a], q);
                    qMax[a] = Math.max(qMax[a], q);
                }
                allMin = Math.min(allMin, sim.seenAt.size());
                allMax = Math.max(allMax, sim.seenAt.size());
                comAllMin = Math.min(comAllMin, sim.seenCommon());
            }
        }
        StringBuilder sb = new StringBuilder("모의 " + runs + "판:");
        for (int a = 1; a <= acts; a++) {
            sb.append(" 막").append(a).append(" 전용 ").append(range(ownMin[a], ownMax[a])).append('/').append(dedicated(a).size()).append(" 공통 ")
                .append(range(comMin[a], comMax[a])).append('/').append(range(qMin[a], qMax[a])).append(" |");
        }
        sb.append(" 판 끝 모든 종 ").append(range(allMin, allMax)).append('/').append(all).append("(공통 ").append(comAllMin).append('/').append(commons().size())
            .append(") 막 밖 ").append(bad).append(badEx == null ? "" : "(" + badEx + ")").append(" 막은 종 ").append(barred).append("번")
            .append(barredEx == null ? "" : "(" + barredEx + ")").append("(설정 ").append(SpeciesBan.all().size()).append("종)").append(" | 첫 판 보스").append(boss);
        return sb.toString();
    }

    private static String range(int lo, int hi) {
        return lo == hi ? String.valueOf(lo) : lo + "~" + hi;
    }

    /** 모의용 확정(기록 없이). */
    private void seenQuiet(int n, Collection<Species> species) {
        Draw d = draw(n);
        for (Species sp : species) {
            if (sp == null || sp.is(Role.BOSS) || seenAt.containsKey(sp)) continue;
            seenAt.put(sp, n);
            for (int a = 1; a <= Trip.ACTS; a++) if (decks[a] != null) decks[a].remove(sp);
        }
        committed.put(n, d);
        lastRound.clear();
        lastRound.addAll(species);
        version++;
        pending = null;
    }

    private static String shares(Draw d) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Species, Double> e : d.share.entrySet()) sb.append(e.getKey().name()).append(String.format(Locale.ROOT, ":%.2f ", e.getValue()));
        if (!d.single.isEmpty()) sb.append("한마리 ").append(names(d.single));
        return sb.toString().trim();
    }

    static String names(Collection<Species> c) {
        if (c == null || c.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (Species sp : c) {
            if (sb.length() > 0) sb.append(',');
            sb.append(sp.name());
        }
        return sb.toString();
    }

    /** 숫자 표 값 하나를 읽거나(value == null) 바꿈(int, double, int[]). */
    static String tune(String name, String value) {
        try {
            Field f = Tune.class.getField(name);
            if (!Modifier.isStatic(f.getModifiers())) return null;
            Class<?> t = f.getType();
            if (t == int.class) {
                if (value != null) f.setInt(null, Integer.parseInt(value));
                return String.valueOf(f.getInt(null));
            }
            if (t == double.class) {
                if (value != null) f.setDouble(null, Double.parseDouble(value));
                return String.valueOf(f.getDouble(null));
            }
            if (t == int[].class) {
                if (value != null) {
                    String[] parts = value.split(",");
                    int[] v = new int[parts.length];
                    for (int i = 0; i < v.length; i++) v[i] = Integer.parseInt(parts[i].trim());
                    f.set(null, v);
                }
                return Arrays.toString((int[]) f.get(null));
            }
            return null;
        } catch (ReflectiveOperationException | NumberFormatException e) {
            return null;
        }
    }

    private static String tunes() {
        StringBuilder sb = new StringBuilder();
        for (Field f : Tune.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(f.getName()).append('=').append(tune(f.getName(), null));
        }
        return sb.toString();
    }
}
