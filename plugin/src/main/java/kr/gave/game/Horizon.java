package kr.gave.game;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.world.Fog;
import kr.gave.world.Plaza;

/**
 * 압도감 연출(docs/모듈/압도감.md, 2026-11-12 사용자 "거대한 절망감이라든지 압도감이 느껴지면 좋겠어"). 실제 개체가 아닌 것(맞지 않고 감독 대기열과
 * 무관)으로 '끝이 없다', '압도당한다'를 보이고 들리게 한다. 실제 수와 피해는 감독(bal12)이 정하고 여기는 그 위에 얹는다.
 *
 * <ol>
 * <li>지평선 그림자 떼: 흙먼지 안개가 거의 다 가리는 거리에 게이브 수십 마리를 담은 카드(디스플레이 하나, 팩 모델 gave:gabe/horizon, 세로 빌보드)를
 * 사방에 세운다. 안개도 디스플레이를 덮으므로 카드는 안개 끝 안쪽, 그 자리 색이 7~30% 남는 거리(서 있는 모든 사람에게서 GAP_MIN 밖)에 선다.
 * 웨이브가 깊을수록, 몰아칠수록 많이, 가깝게. 실제 게이브가 흙먼지 속에 많은 쪽과 쏠림 쪽이 먼저 짙어진다. 안개가 짙어 보이는 거리가 짧으면
 * (단계 3~5) 땅으로 꺼지고 소리만 남는다(싸움 시야를 가리지 않게).</li>
 * <li>큰 물결: 몰아치기가 시작될 때(잦지 않게) 사방 땅울림과 떼의 함성, 아주 약한 화면 흔들림, 그림자 떼가 한꺼번에 밀려옴.</li>
 * <li>하늘: 높은 웨이브에 거대한 그림자 게이브(G01 모델을 크게, 느린 날갯짓)가 흙먼지 속에서 나와 하늘을 가로지르며 내려다봄. 더 높은 웨이브의
 * 마지막 몰아치기에는 하늘을 메우는 얼굴들(머리 위, 고개를 들어야 보임).</li>
 * <li>소리 층: 흙먼지 너머 먼 포효(사람마다 떼가 짙은 쪽에서). 소리는 aud12 의 sfx.* 사건(팩에 없으면 바닐라 대체 소리).</li>
 * <li>판 끝: 그림자 떼가 중앙 원으로 조여 와 삼킴.</li>
 * </ol>
 * 보스가 실제로 나오는 웨이브(director.bossWave() 이고 boss 고리가 있음)에는 하늘과 큰 물결을 끄고 지평선 떼만 옅게 둔다(보스가 더 커 보이게).
 * 소리 없는 웨이브에는 소리를 모두 끊고 날갯짓을 얼린다. 설정 config.yml horizon.enabled(기본 true). 시험 훅 '/gave 시험 압도 x ...'.
 *
 * <p>Game 이 부르는 것: {@link #tick()}(매 틱, 모든 상태, hud 뒤), {@link #clear()}(Game.reset), {@link #test(Player, String[])}.
 */
public final class Horizon {

    /** 숫자 한곳(시험 훅 '압도 x 값 이름 값' 으로 켠 채 바꿈). */
    public static final class Tune {
        /** 지평선 카드 수(10도마다 하나, 가까운 줄과 먼 줄이 번갈아), 보이는 수(세기 0 → SHOW_MIN, 1 → SHOW_MAX). */
        public static int CARDS = 36, SHOW_MIN = 8, SHOW_MAX = 36;
        /** 카드 배율(가까운 줄, 먼 줄). 배율 1 이면 카드 한 장이 폭 6, 높이 5블록쯤. */
        public static double SCALE_NEAR = 1.45, SCALE_FAR = 2.1;
        /** 카드가 설 자리의 보임(1 - 안개 몫: 그 자리 색이 화면에 남는 몫). 세기 0 → C_FAR, 1 → C_NEAR. 먼 줄은 x FAR_DIM. */
        public static double C_FAR = 0.1, C_NEAR = 0.34, FAR_DIM = 0.5;
        /** 서 있는 사람에게서 적어도 이만큼(블록). 이보다 가까워야 보일 만큼 안개가 짙으면 카드는 땅으로 꺼진다. */
        public static double GAP_MIN = 40;
        /** 가운데에서 이보다 멀리 두지 않음(서버 디스플레이 추적 거리 128 안). */
        public static double R_MAX = 116;
        /** 일렁임: 카드마다 반지름이 DRIFT 블록만큼 DRIFT_TICKS 주기로 다가왔다 물러남. */
        public static double DRIFT = 2.5;
        public static int DRIFT_TICKS = 420;
        /** 카드 하나를 다시 옮기는 사이(틱, 카드마다 엇갈림), 옮김 보간(틱, 59 까지: 물결 때 떼가 2.5초에 걸쳐 밀려옴). */
        public static int MOVE_EVERY = 40, GLIDE = 50;
        /**
         * 카드 밝기(블록 빛, 하늘 빛 같은 값). 해 지는 쪽(서쪽 붉은 하늘, 좌우 GLOW_ARC 도)은 LIGHT_GLOW 로 어둡게 해 붉은 하늘에 검은 그림자 벽으로,
         * 그 밖(어두운 하늘)은 LIGHT 로 밝게 해 흙먼지 속에 흐린 얼굴 떼가 드러나게(2026-11-12 사진: 3 은 어두운 쪽에서 거의 안 보임, 8~9 는 얼굴 떼로
         * 읽힘). 밤 사건(세계 시각 17000 넘음)에는 모두 LIGHT.
         */
        public static int LIGHT = 11, LIGHT_GLOW = 3;
        public static double GLOW_ARC = 50, GLOW_FADE = 45;
        /**
         * 큰 물결: 웨이브 첫 몰아치기는 BIG_START_FROM 웨이브부터, 마지막 몰아치기는 BIG_FINAL_FROM 부터, 중간 몰아치기는 BIG_MID_FROM 부터 크게(그 전엔
         * 작게: 땅울림만 작게). 큰 물결 사이 BIG_GAP 틱, 작은 것까지 모든 물결 사이 ANY_GAP 틱.
         */
        public static int BIG_START_FROM = 8, BIG_FINAL_FROM = 3, BIG_MID_FROM = 15, BIG_GAP = 900, ANY_GAP = 300;
        /** 물결 때 카드가 다가오는 몫(보임에 더함)과 그 틱. */
        public static double PUSH_BIG = 0.18, PUSH_SMALL = 0.05;
        public static int PUSH_TICKS = 160;
        /** 큰 물결의 아주 약한 화면 흔들림(도)과 틱. 0 이면 없음. */
        public static double SHAKE = 0.3;
        public static int SHAKE_TICKS = 28;
        /** 큰 물결 동안 하늘을 어둡게(사람마다 시각을 이만큼 늦춤, 0 이면 끔). */
        public static int DUSK = 0;
        /** 거대한 그림자 게이브: 이 웨이브부터, 사이 틱, 몰아치기 시작마다 나올 확률, 배율, 가장 낮은 높이(바닥 위), 가로지르는 틱. */
        public static int GIANT_FROM = 12, GIANT_GAP = 1800, GIANT_TICKS = 600;
        public static double GIANT_CHANCE = 0.5, GIANT_SCALE = 14, GIANT_ALT = 32;
        /** 하늘을 메우는 얼굴: 이 웨이브부터 마지막 몰아치기에, 카드 수, 높이(바닥 위), 가운데에서 거리. */
        public static int SKY_FROM = 18, SKY_CARDS = 10;
        public static double SKY_ALT = 36, SKY_R = 30;
        /** 먼 포효(흙먼지 너머 끝없는 떼) 사이 틱(세기 1 → ROAR_MIN, 0 → ROAR_MAX). */
        public static int ROAR_MIN = 120, ROAR_MAX = 340;
        /** 판 끝: 그림자 떼가 가운데에서 이 반지름까지 조여 옴. */
        public static double END_R = 20;
    }

    /** 팩 모델(키트, tools/gabe/sp/horizon.py). 없으면 G02 떼 덩어리(lod)를 크게 대신 쓴다. */
    public static final String MODEL = "gabe/horizon", FALLBACK = "gabe/g02", FALLBACK_STATE = "lod", GIANT_MODEL = "gabe/g01";
    /** 모델 가운데에서 맨 아래까지(블록, 배율 1. horizon.py FOOT 와 같게), 대신 쓰는 덩어리는 FOOT_FALLBACK. 카드 폭, 높이(컬링 상자). */
    public static final double FOOT = 2.2, FOOT_FALLBACK = 0.65, CARD_W = 6.4, CARD_H = 5.3;
    /** 안개 타임라인 자리 0..5 의 시작 거리(data/datapack/gave_env timeline fog.json 과 같게: 자리 0 맑음, 1 기본, 2..5 짙음). 끝은 Fog.END. */
    static final double[] FOG_START = {40, 4, 16, 10, 4, 0};
    /** 한 틱에 새로 만드는 카드 디스플레이 수(판 시작에 몰아 만들지 않게). */
    static final int SPAWN_PER_TICK = 4;
    /** 카드 view range(x 64블록 = 166블록: 맑은 안개의 가장 먼 자리 116 + 사람이 반대쪽 끝까지 나간 거리). */
    static final float VIEW = 2.6f;
    /** 실제 게이브를 각도별로 셀 때 칸 수, 흙먼지 속으로 칠 반지름(가운데에서). */
    static final int BINS = 36;
    static final double INCOMING_R = 40;

    private final Game game;
    private final Random rnd = new Random();
    public boolean enabled;

    private final List<Card> cards = new ArrayList<>();
    private final List<Card> sky = new ArrayList<>();
    private boolean active;
    private String model = MODEL;
    private double foot = FOOT;
    private boolean fallback, forceFallback;

    /** 지금 세기(0..1, 부드럽게)와 목표, 시험 훅 강제 값(음수면 자동). */
    private double level, target, forced = -1;
    /** 지금 안개 시작, 끝 거리(블록, 시계에서 읽음). */
    private double fogStart = 4, fogEnd = 64;
    /** 각도 칸별 흙먼지 속 실제 게이브(부드럽게, 0..1 로 나눈 것). */
    private final double[] sector = new double[BINS];
    private boolean surgePrev, finalPrev, pendingFinal;
    private long lastAny = -100000, lastBig = -100000, pushUntil, rushUntil;
    private double push;
    private boolean silentFrozen, nightPrev;
    private long nextRoarAt;
    // 화면 흔들림
    private long shakeAt = -1;
    private double shakeAmp;
    private int shakeLen = 28;
    private final Map<UUID, float[]> shakeSent = new HashMap<>();
    // 하늘 어둡게
    private long duskUntil = -1;
    private int duskNow, duskAdd;
    // 거대한 그림자
    private ItemDisplay giant;
    private long giantAt, lastGiant = -100000;
    private double gx0, gz0, gx1, gz1, gside;
    private int giantFlaps;
    /** 시험: 거대한 그림자를 한 자리에 멈춰 둠. */
    private boolean giantHold;
    // 판 끝
    private long endAt = -1;
    /** 하늘 얼굴이 올라가기 시작한 틱(-1 이면 내려와 있음). */
    private long skyUpAt = -1, skyForceUntil;
    // 시험 기록
    private int nBig, nSmall, nRoar, nGiant, nSky;
    private String lastWave = "-";
    /** 미뤄 울릴 소리(틱, 할 일). */
    private final List<Object[]> later = new ArrayList<>();
    /** 이 틱까지 지평선 그림자(카드, 하늘 얼굴, 거대한 그림자)를 숨김(이동, 추격전, 점화: {@link #hide}). */
    private long hideUntil = -1;

    public Horizon(Game game) {
        this.game = game;
        boolean on = true;
        try {
            on = game.plugin.getConfig().getBoolean("horizon.enabled", true);
        } catch (RuntimeException ignored) {
            // 설정을 못 읽으면 켬
        }
        enabled = on;
    }

    // ------------------------------------------------------------------ 카드

    /** 지평선(또는 하늘) 카드 하나: 디스플레이 하나에 게이브 수십 마리. */
    private final class Card {
        final double ang;
        final boolean far, skyCard;
        final String state;
        final float scale;
        final double tie, driftPh;
        final int moveOff;
        ItemDisplay d;
        boolean shown;
        double w, r = -1, y = -1000;
        long nextMove;
        /** 땅속에 숨은 뒤 그리지 않게 view range 를 0 으로 둔 틱(-1 이면 그림). 숨은 카드도 그리면 프레임을 먹으므로. */
        long dimAt = -1;
        boolean dimmed;

        Card(double ang, boolean far, boolean skyCard, String state, float scale, int moveOff) {
            this.ang = ang;
            this.far = far;
            this.skyCard = skyCard;
            this.state = state;
            this.scale = scale;
            this.tie = rnd.nextDouble() * 0.12;
            this.driftPh = rnd.nextDouble() * Math.PI * 2;
            this.moveOff = moveOff;
        }

        double x(double rr) {
            return Plaza.CX + 0.5 + Math.cos(ang) * rr;
        }

        double z(double rr) {
            return Plaza.CZ + 0.5 + Math.sin(ang) * rr;
        }

        void spawn(double rr, double yy) {
            LookState ls = look(this);
            ItemStack it = Body.item(model, fallback ? FALLBACK_STATE : state, ls, game.worldTime());
            float k = Look.scale(model);
            float s = scale * (fallback ? 3f : 1f);
            Matrix4f m = new Matrix4f().translate(0, (float) (foot * s), 0).scale(s * k).rotateY((float) Math.PI);
            Location at = new Location(game.world, x(rr), yy, z(rr));
            d = game.world.spawn(at, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setItemStack(it);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setBillboard(skyCard ? Display.Billboard.CENTER : Display.Billboard.VERTICAL);
                int li = skyCard ? Tune.LIGHT : light(ang);
                e.setBrightness(new Display.Brightness(li, li));
                e.setViewRange(VIEW);
                e.setDisplayWidth((float) (CARD_W * s));
                e.setDisplayHeight((float) (CARD_H * s));
                e.setTeleportDuration(Tune.GLIDE);
                e.setInterpolationDuration(0);
                e.setTransformationMatrix(m);
            });
            r = rr;
            y = yy;
        }

        void moveTo(double rr, double yy) {
            if (d == null || !d.isValid()) return;
            boolean under = yy < Plaza.FLOOR_Y - 1;
            // 다시 솟을 때는 먼저 그리게 하고 옮김, 땅속으로 꺼질 때는 다 꺼진 뒤(보간 끝) 그리지 않게
            if (!under && dimmed) {
                d.setViewRange(VIEW);
                dimmed = false;
            }
            dimAt = under ? game.now() + Tune.GLIDE + 5 : -1;
            if (Math.abs(rr - r) < 0.4 && Math.abs(yy - y) < 0.2) return;
            d.teleport(new Location(game.world, x(rr), yy, z(rr)));
            r = rr;
            y = yy;
        }

        /** 땅속에 다 꺼졌으면 그리지 않게(view range 0: 클라가 그 디스플레이를 건너뜀). */
        void dimTick(long now) {
            if (dimmed || dimAt < 0 || now < dimAt || d == null || !d.isValid()) return;
            d.setViewRange(0f);
            dimmed = true;
        }

        void remove() {
            if (d != null && d.isValid()) d.remove();
            d = null;
        }
    }

    /** 그 각도(가운데에서 본, 0 = +X 동) 카드의 밝기: 해 지는 서쪽(붉은 하늘)은 어두운 그림자, 그 밖은 흐린 얼굴. */
    private int light(double ang) {
        if (game.world.getTime() % 24000 >= 17000) return Tune.LIGHT;
        double d = Math.toDegrees(Math.abs(Math.atan2(Math.sin(ang - Math.PI), Math.cos(ang - Math.PI))));
        double t = Math.max(0, Math.min(1, (d - Tune.GLOW_ARC) / Math.max(1, Tune.GLOW_FADE)));
        return (int) Math.round(Tune.LIGHT_GLOW + (Tune.LIGHT - Tune.LIGHT_GLOW) * t);
    }

    private LookState look(Card c) {
        LookState ls = new LookState();
        ls.phase = rnd.nextInt(240);
        ls.freeze = game.director.silent();
        return ls;
    }

    /** 카드 높이: 보이면 바닥(발이 땅), 숨기면 땅속(흙먼지 속으로 꺼짐). */
    private double groundY(Card c, boolean shown) {
        return shown ? Plaza.FLOOR_Y + 0.02 : Plaza.FLOOR_Y - (CARD_H + 1) * c.scale - 3;
    }

    /** 판이 시작될 때 카드를 땅속에 만들어 둔다(보이는 수는 세기로). */
    private void start() {
        clear();
        if (Look.has(MODEL) && !forceFallback) {
            model = MODEL;
            foot = FOOT;
            fallback = false;
        } else if (Look.has(FALLBACK)) {
            model = FALLBACK;
            foot = FOOT_FALLBACK;
            fallback = true;
        } else {
            active = true;
            return;
        }
        String[] near = {"", "b"};
        for (int i = 0; i < Tune.CARDS; i++) {
            double a = Math.PI * 2 * i / Tune.CARDS + (rnd.nextDouble() - 0.5) * Math.toRadians(5);
            boolean far = i % 2 == 1;
            String st = far ? (i % 4 == 1 ? "c" : near[(i / 2) % 2]) : near[(i / 2) % 2];
            float sc = (float) ((far ? Tune.SCALE_FAR : Tune.SCALE_NEAR) * (0.9 + rnd.nextDouble() * 0.2));
            // 디스플레이는 moveCards 가 한 틱에 SPAWN_PER_TICK 장씩 만든다(36장을 한 틱에 만들면 처음 판에서 124ms 멈춤이 한 번 있었음)
            cards.add(new Card(a, far, false, st, sc, i * 7 % Tune.MOVE_EVERY));
        }
        active = true;
        game.plugin.getLogger().info("[압도] 지평선 카드 " + cards.size() + "장(" + model + (fallback ? " 대신" : "") + ")");
    }

    /**
     * ticks 틱 동안 지평선 그림자를 숨긴다(이미 더 길게 숨겼으면 그대로). 카드는 흙먼지 속으로 물러나 가라앉고, 하늘 얼굴은 올라가며, 거대한 그림자는
     * 사라진다. 그동안 물결과 하늘 얼굴은 새로 안 냄. 부르는 쪽: 이동(Travel.start), 추격전(Chase, 매 틱), 점화(Launch.ignite).
     */
    public void hide(int ticks) {
        if (ticks <= 0) return;
        long now = game.now();
        boolean was = hidden();
        hideUntil = Math.max(hideUntil, now + ticks);
        if (was || !active) return;
        pickShown();
        rushUntil = now + 2;
        if (giant != null && giant.isValid() && !giantHold) giant.remove();
        giant = null;
    }

    /** 지금 숨김 중인가({@link #hide}). */
    public boolean hidden() {
        return game.now() < hideUntil;
    }

    /** 모두 치움(Game.reset, 대기실). */
    public void clear() {
        for (Card c : cards) c.remove();
        cards.clear();
        clearSky();
        if (giant != null && giant.isValid()) giant.remove();
        giant = null;
        resetDusk();
        shakeAt = -1;
        shakeSent.clear();
        later.clear();
        active = false;
        level = 0;
        push = 0;
        endAt = -1;
        surgePrev = finalPrev = false;
        silentFrozen = false;
        hideUntil = -1;
    }

    private void clearSky() {
        for (Card c : sky) c.remove();
        sky.clear();
        skyUpAt = -1;
    }

    // ------------------------------------------------------------------ 매 틱

    public void tick() {
        if (!enabled) {
            if (active) clear();
            return;
        }
        if (game.state == Game.State.LOBBY) {
            if (active) clear();
            return;
        }
        long t0 = System.nanoTime();
        try {
            work();
        } finally {
            long ns = System.nanoTime() - t0;
            tickSum += ns;
            tickMax = Math.max(tickMax, ns);
            if (++tickN >= 200) {
                tickAvgShown = tickSum / 1e6 / tickN;
                tickMaxShown = tickMax / 1e6;
                tickSum = tickMax = 0;
                tickN = 0;
            }
        }
    }

    /** 이 연출의 서버 틱 시간(최근 200틱 평균, 최대 ms). 시험 훅 상태에 '틱=' 으로. */
    private long tickSum, tickMax;
    private int tickN;
    private double tickAvgShown, tickMaxShown;

    private void work() {
        if (!active) start();
        long now = game.now();
        if (now % 10 == 0) evaluate(now);
        moveCards(now);
        skyTick(now);
        giantTick(now);
        shakeTick(now);
        duskTick(now);
        laterTick(now);
        if (game.state == Game.State.WAVE) roarTick(now);
    }

    /** 10틱마다: 안개, 세기, 각도별 떼, 보일 카드, 몰아치기 가장자리. */
    private void evaluate(long now) {
        readFog();
        target = forced >= 0 ? forced : targetLevel();
        level += (target - level) * (target > level ? 0.3 : 0.06);
        countSectors();
        pickShown();
        edges(now);
        // 밤 사건(세계 시각이 밤으로 바뀜)이 켜지거나 꺼지면 카드 밝기를 다시(해 지는 쪽 그림자가 밤에는 흐린 얼굴로)
        boolean night = game.world.getTime() % 24000 >= 17000;
        if (night != nightPrev) {
            nightPrev = night;
            for (Card c : cards) {
                if (c.d == null || !c.d.isValid()) continue;
                int li = light(c.ang);
                c.d.setBrightness(new Display.Brightness(li, li));
            }
        }
        boolean frozen = game.director.silent();
        if (frozen != silentFrozen) {
            silentFrozen = frozen;
            for (Card c : cards) refreshLook(c);
            for (Card c : sky) refreshLook(c);
        }
    }

    private void refreshLook(Card c) {
        if (c.d == null || !c.d.isValid()) return;
        c.d.setItemStack(Body.item(model, fallback ? FALLBACK_STATE : c.state, look(c), game.worldTime()));
    }

    /** 세기 목표(0..1): 웨이브가 깊을수록, 몰아칠수록, 대기열이 많을수록. 보스가 실제로 나오는 웨이브는 낮게. */
    private double targetLevel() {
        Director d = game.director;
        switch (game.state) {
            case PREP:
            case BREAK:
                return 0;
            case OVER:
                return 1;
            default:
                break;
        }
        int w = Math.max(1, game.wave);
        double base = 0.12 + 0.6 * (1 - Math.exp(-(w - 1) / 14.0));
        double s = d.finalSurge() ? 0.3 : d.surging() ? 0.14 : -0.06;
        double more = Math.min(0.1, d.queued() / Math.max(1.0, d.aliveGoal() * 4) * 0.1);
        double v = base + s + more;
        if (bossOn()) v = Math.min(v, 0.25);
        return Math.max(0, Math.min(1, v));
    }

    private boolean bossOn() {
        return game.director.bossWave() && game.director.boss != null;
    }

    /** 안개 시계(Fog.ticks)에서 지금 시작, 끝 거리를 읽는다(타임라인: 자리 k 는 k*100+15..k*100+85 고정, 사이는 30틱 선형). */
    private void readFog() {
        long t = Fog.ticks(game.world);
        if (t < 0) {
            int pos = Fog.pos(game.director.fog());
            fogStart = FOG_START[pos];
            fogEnd = Fog.END[Fog.pos(pos)];
            return;
        }
        double u = t - 15.0;
        int k = (int) Math.floor(u / Fog.STEP);
        double f = 0;
        if (k < 0) k = 0;
        else if (k >= 5) k = 5;
        else {
            double r = u - k * Fog.STEP;
            if (r > 70) f = Math.min(1, (r - 70) / 30.0);
        }
        int k1 = Math.min(5, k + 1);
        fogStart = FOG_START[k] + (FOG_START[k1] - FOG_START[k]) * f;
        fogEnd = Fog.END[Fog.pos(k)] + (Fog.END[Fog.pos(k1)] - Fog.END[Fog.pos(k)]) * f;
    }

    /** 보임 c(0..1)가 남는 거리(그 자리 색이 화면에 c 만큼 남음). */
    private double distFor(double c) {
        return fogStart + (1 - c) * (fogEnd - fogStart);
    }

    /** 각도 칸마다 흙먼지 속(가운데에서 INCOMING_R 밖) 살아 있는 게이브 수를 세어 부드럽게 0..1 로. */
    private void countSectors() {
        double[] raw = new double[BINS];
        for (Gabe g : game.mobs.all()) {
            if (g.dead) continue;
            double x = g.pos.getX() - Plaza.CX - 0.5, z = g.pos.getZ() - Plaza.CZ - 0.5;
            if (x * x + z * z < INCOMING_R * INCOMING_R) continue;
            raw[bin(Math.atan2(z, x))] += Director.weight(g.sp);
        }
        double max = 1;
        double[] blur = new double[BINS];
        for (int i = 0; i < BINS; i++) {
            blur[i] = raw[i] + 0.6 * (raw[(i + 1) % BINS] + raw[(i + BINS - 1) % BINS]) + 0.25 * (raw[(i + 2) % BINS] + raw[(i + BINS - 2) % BINS]);
            max = Math.max(max, blur[i]);
        }
        for (int i = 0; i < BINS; i++) sector[i] += (blur[i] / max - sector[i]) * 0.2;
    }

    private static int bin(double ang) {
        double a = ang % (Math.PI * 2);
        if (a < 0) a += Math.PI * 2;
        return Math.min(BINS - 1, (int) (a / (Math.PI * 2) * BINS));
    }

    /** 그 각도 쪽 무게(0..1): 흙먼지 속 실제 떼 + 쏠림 쪽 덤. */
    private double weightAt(double ang) {
        double w = 0.25 + 0.75 * sector[bin(ang)];
        int lean = game.director.lean();
        if (lean >= 0 && game.state == Game.State.WAVE) {
            var v = Plaza.dirVec(lean);
            double la = Math.atan2(v.getZ(), v.getX());
            double dd = Math.abs(Math.atan2(Math.sin(ang - la), Math.cos(ang - la)));
            if (dd < Math.toRadians(50)) w += 0.35 * (1 - dd / Math.toRadians(50));
        }
        return Math.min(1, w);
    }

    /** 보일 카드: 무게가 큰 쪽부터 SHOW 수만큼(지금 보이는 것에 덤을 줘 깜빡이지 않게). 안개가 짙어 가까이 와야 보이면 모두 숨김. */
    private void pickShown() {
        boolean fogOk = distFor(Tune.C_FAR) >= Tune.GAP_MIN || game.state == Game.State.OVER;
        // 물결 동안은 땅속의 카드도 솟아올라 한꺼번에 밀려온다(보이는 수에도 물결 몫을 더함)
        double eff = Math.min(1, level + 3 * pushNow(game.now()));
        int n = fogOk ? (int) Math.round(Tune.SHOW_MIN + (Tune.SHOW_MAX - Tune.SHOW_MIN) * eff) : 0;
        // 보스가 실제로 나오는 웨이브는 지평선을 비운다(보스 담당 요청: 겹치면 보스가 덜 커 보임)
        if (bossOn() || hidden()) n = 0;
        if (game.state == Game.State.PREP && game.stateTicks() < 40) n = 0;
        for (Card c : cards) c.w = weightAt(c.ang);
        List<Card> order = new ArrayList<>(cards);
        order.sort((a, b) -> Double.compare(score(b), score(a)));
        for (int i = 0; i < order.size(); i++) order.get(i).shown = i < n;
    }

    private double score(Card c) {
        return c.w + c.tie + (c.shown ? 0.15 : 0) + (c.far ? -0.05 : 0);
    }

    /** 카드를 엇갈려 옮긴다(한 틱에 몇 장만). 큰 물결 동안은 빠른 보간으로 한꺼번에. */
    private void moveCards(long now) {
        boolean rush = now < rushUntil;
        double pushNow = pushNow(now);
        List<Player> ref = refPlayers();
        int made = 0;
        for (Card c : cards) {
            if (c.d == null) {
                if (made >= SPAWN_PER_TICK) continue;
                c.spawn(Tune.R_MAX, groundY(c, false));
                c.dimAt = now + 2;
                made++;
                continue;
            }
            c.dimTick(now);
            if (!rush && Math.floorMod(now - c.moveOff, Tune.MOVE_EVERY) != 0) continue;
            double rr;
            boolean show = c.shown;
            if (game.state == Game.State.OVER && endAt >= 0) {
                // 판 끝: 가운데로 조여 옴(사람 간격 무시)
                double t = Math.min(1, (now - endAt) / (double) Math.max(1, game.director.endTicks()));
                double from = Math.max(Tune.END_R + 8, distFor(Tune.C_FAR));
                rr = from + (Tune.END_R + (c.far ? 8 : 0) - from) * t * t;
                show = !hidden();
            } else {
                // 땅속에 숨은 채로 남을 카드는 옮기지 않음(패킷 아낌). 다시 보일 때 그 자리에서 솟아오름
                if (!show && c.y < Plaza.FLOOR_Y - 1) continue;
                double cc = (Tune.C_FAR + (Tune.C_NEAR - Tune.C_FAR) * level * (0.55 + 0.45 * c.w) + pushNow) * (c.far ? Tune.FAR_DIM : 1);
                double gap = Math.max(Tune.GAP_MIN, distFor(Math.min(0.95, cc)));
                if (c.far) gap = Math.max(gap, Tune.GAP_MIN + 6);
                rr = radiusFor(c.ang, gap, ref) + Tune.DRIFT * Math.sin(now * Math.PI * 2 / Tune.DRIFT_TICKS + c.driftPh);
                if (rr > Tune.R_MAX) {
                    // 맑은 안개(끝이 R_MAX 보다 멂)면 가장 먼 자리에 그대로 보이게(안개가 걷혀 지평선 끝까지 떼가 보임), 아니면 숨김
                    rr = Tune.R_MAX;
                    show = show && fogEnd > Tune.R_MAX + 10;
                }
                // 숨을 때는 흙먼지 속으로 물러나며 꺼진다(제자리에서 가라앉지 않게)
                if (!show) rr = Math.min(Tune.R_MAX, Math.max(rr, fogEnd + 8));
            }
            c.moveTo(rr, groundY(c, show));
        }
    }

    /** 물결 몫(물결 직후 push, PUSH_TICKS 동안 줄어 0). */
    private double pushNow(long now) {
        return now < pushUntil ? push * (double) (pushUntil - now) / Tune.PUSH_TICKS : 0;
    }

    /** 카드가 서 있는 모든 사람에게서 gap 넘게 떨어지는, 그 각도 위 가장 안쪽 반지름(가운데에서). */
    private static double radiusFor(double ang, double gap, List<Player> ref) {
        double ux = Math.cos(ang), uz = Math.sin(ang), r = gap;
        for (Player p : ref) {
            double px = p.getLocation().getX() - Plaza.CX - 0.5, pz = p.getLocation().getZ() - Plaza.CZ - 0.5;
            double up = ux * px + uz * pz, disc = up * up - (px * px + pz * pz) + gap * gap;
            if (disc > 0) r = Math.max(r, up + Math.sqrt(disc));
        }
        return r;
    }

    private List<Player> refPlayers() {
        List<Player> ps = game.players.standing();
        return ps.isEmpty() ? game.players.inPlay() : ps;
    }

    // ------------------------------------------------------------------ 큰 물결

    /** 몰아치기 시작(웨이브 첫 몰아치기, 중간, 마지막)을 보고 큰 물결이나 작은 물결을 낸다. 판 끝이면 조임을 시작한다. */
    private void edges(long now) {
        if (game.state == Game.State.OVER) {
            if (endAt < 0 && game.director.ending()) {
                endAt = now;
                for (Card c : cards) c.shown = true;
                rushUntil = now + 2;
                if (!game.director.silent()) {
                    for (Player p : game.players.inPlay()) {
                        around(p, "sfx.horde_cry", Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 1f, 0.5f, 3, 20);
                        around(p, "sfx.quake", Sound.ENTITY_WARDEN_EMERGE, 0.9f, 0.6f, 2, 16);
                    }
                }
                lastWave = "끝";
            }
            surgePrev = finalPrev = pendingFinal = false;
            return;
        }
        if (game.state != Game.State.WAVE) {
            surgePrev = finalPrev = pendingFinal = false;
            return;
        }
        Director d = game.director;
        boolean surge = d.surging(), fin = d.finalSurge();
        boolean startEdge = surge && !surgePrev && game.stateTicks() < 120;
        boolean midEdge = surge && !surgePrev && !startEdge;
        // 웨이브 시작 직후 몇 틱은 감독의 마지막 몰아치기 표시가 앞 웨이브 값으로 남아 있을 수 있어(첫 내보내기 전) 보지 않는다
        boolean finEdge = fin && !finalPrev && game.stateTicks() > 80;
        surgePrev = surge;
        finalPrev = fin;
        if (bossOn() || hidden()) return;
        // 하늘을 메우는 얼굴은 마지막 몰아치기가 시작되는 순간 바로(물결 간격과 무관)
        if (finEdge && game.wave >= Tune.SKY_FROM) startSky(now);
        // 마지막 몰아치기가 앞 물결과 너무 가까우면(ANY_GAP) 버리지 않고 미뤘다가 간격이 차면 낸다(마지막 몰아치기가 이어지는 동안만)
        if (finEdge) pendingFinal = true;
        if (!fin) pendingFinal = false;
        boolean finFire = pendingFinal && now - lastAny >= Tune.ANY_GAP;
        if (!(startEdge || midEdge || finFire)) return;
        if (finFire) pendingFinal = false;
        int w = game.wave;
        boolean big;
        if (finFire) big = w >= Tune.BIG_FINAL_FROM && now - lastBig >= Tune.ANY_GAP;
        else if (startEdge) big = w >= Tune.BIG_START_FROM && now - lastBig >= Tune.BIG_GAP;
        else big = w >= Tune.BIG_MID_FROM && now - lastBig >= Tune.BIG_GAP;
        if (!big && now - lastAny < Tune.ANY_GAP) return;
        wave(now, big, finFire ? "마지막" : startEdge ? "시작" : "중간");
        // 하늘: 높은 웨이브에서 몰아치기 시작마다 확률로 거대한 그림자
        if (w >= Tune.GIANT_FROM && giant == null && now - lastGiant >= Tune.GIANT_GAP && rnd.nextDouble() < Tune.GIANT_CHANCE)
            startGiant(now, rnd.nextDouble() * Math.PI * 2, (rnd.nextDouble() - 0.5) * 24);
    }

    /** 물결 하나: 사방 땅울림(큰 것은 함성, 화면 흔들림, 하늘 어둡게 더), 그림자 떼가 한꺼번에 밀려옴. */
    private void wave(long now, boolean big, String why) {
        lastAny = now;
        if (big) lastBig = now;
        push = big ? Tune.PUSH_BIG : Tune.PUSH_SMALL;
        pushUntil = now + Tune.PUSH_TICKS;
        rushUntil = now + 2;
        if (big) nBig++;
        else nSmall++;
        lastWave = (big ? "큰 " : "작은 ") + why + " t=" + now;
        if (!game.director.silent()) {
            for (Player p : game.players.inPlay()) {
                around(p, "sfx.quake", Sound.ENTITY_WARDEN_EMERGE, big ? 1f : 0.55f, 0.6f, big ? 3 : 1, 18);
                if (big) {
                    final Player pp = p;
                    later.add(new Object[] {now + 10, (Runnable) () -> around(pp, "sfx.horde_cry", Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 0.9f, 0.55f, 2, 24)});
                }
            }
        }
        if (big && Tune.SHAKE > 0) shake(Tune.SHAKE * (0.6 + 0.4 * level), Tune.SHAKE_TICKS);
        if (big && Tune.DUSK > 0) dusk(Tune.DUSK, Tune.PUSH_TICKS + 60);
    }

    /** p 둘레 n 방향(고르게, 처음 각도 무작위)에서 dist 블록 떨어진 자리의 소리. */
    private void around(Player p, String event, Sound fallback, float vol, float fbPitch, int n, double dist) {
        Location l = p.getLocation();
        double a0 = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            double a = a0 + Math.PI * 2 * i / n + (rnd.nextDouble() - 0.5) * 0.5;
            Location at = l.clone().add(Math.cos(a) * dist, 1.5, Math.sin(a) * dist);
            Props.soundTo(p, at, event, fallback, vol, 0.9f + rnd.nextFloat() * 0.2f, fbPitch, -1);
        }
    }

    private void laterTick(long now) {
        for (Iterator<Object[]> it = later.iterator(); it.hasNext();) {
            Object[] o = it.next();
            if ((long) o[0] > now) continue;
            it.remove();
            try {
                ((Runnable) o[1]).run();
            } catch (RuntimeException ignored) {
                // 나간 사람 등
            }
        }
    }

    /** 아주 약한 화면 흔들림: 사인 두 개를 겹친 작은 시점 떨림(처음과 끝은 0, 보낸 몫을 되돌려 시선이 제자리로). 서 있는 사람만. */
    private void shakeTick(long now) {
        if (shakeAt < 0) return;
        long t = now - shakeAt;
        boolean last = t >= shakeLen;
        for (Player p : game.players.standing()) {
            float[] sent = shakeSent.computeIfAbsent(p.getUniqueId(), k -> new float[2]);
            float yaw = 0, pitch = 0;
            if (!last) {
                double env = Math.sin(Math.PI * t / shakeLen);
                yaw = (float) (shakeAmp * env * Math.sin(t * 1.9));
                pitch = (float) (shakeAmp * 0.8 * env * Math.sin(t * 2.6 + 1.1));
            }
            float dy = yaw - sent[0], dp = pitch - sent[1];
            if (Math.abs(dy) > 1e-4 || Math.abs(dp) > 1e-4) Guns.kick(p, dy, dp);
            sent[0] = yaw;
            sent[1] = pitch;
        }
        if (last) {
            shakeAt = -1;
            shakeSent.clear();
        }
    }

    /** 큰 물결 동안 하늘을 어둡게(사람마다 시각을 조금 늦춤, 상대 시각이라 감독의 밤 사건과 같이 움직임). 끝나면 되돌림. */
    private void duskTick(long now) {
        int want = now < duskUntil ? duskAdd : 0;
        if (want == duskNow || now % 2 != 0) return;
        int step = Math.max(10, Math.abs(duskAdd) / 12);
        duskNow = want > duskNow ? Math.min(want, duskNow + step) : Math.max(want, duskNow - step);
        for (Player p : game.players.inPlay()) {
            if (duskNow == 0) p.resetPlayerTime();
            else p.setPlayerTime(duskNow, true);
        }
    }

    private void resetDusk() {
        if (duskNow != 0) for (Player p : game.world.getPlayers()) p.resetPlayerTime();
        duskNow = 0;
        duskUntil = -1;
    }

    // ------------------------------------------------------------------ 다른 모듈이 쓰는 도구(보스 등)

    /** 서 있는 사람 모두에게 아주 약한 화면 흔들림(도, 틱). 이미 흔들리는 중이면 더 센 것으로 다시 시작. 보스 땅울림 같은 데 쓴다. */
    public void shake(double degrees, int ticks) {
        if (degrees <= 0 || ticks <= 0) return;
        if (shakeAt >= 0 && shakeAmp >= degrees) return;
        // 보내던 몫을 되돌릴 수 있게 새 흔들림도 같은 표(shakeSent)를 이어 씀
        shakeAt = game.now();
        shakeAmp = degrees;
        shakeLen = ticks;
    }

    /** 판에 든 사람의 하늘을 ticks 동안 시각 add 만큼 늦춰 어둡게(상대 시각, 끝나면 되돌림). 보스 등장 같은 데 쓴다. */
    public void dusk(int add, int ticks) {
        duskAdd = add;
        duskUntil = game.now() + ticks;
    }

    // ------------------------------------------------------------------ 먼 포효

    /** 흙먼지 너머 끝없는 떼: 세기에 따라 가끔, 사람마다 떼가 짙은 쪽 먼 자리에서 낮은 포효(소리 없는 웨이브, 보스 웨이브엔 없음). */
    private void roarTick(long now) {
        if (game.director.silent() || bossOn()) return;
        if (nextRoarAt == 0) nextRoarAt = now + Tune.ROAR_MAX;
        if (now < nextRoarAt) return;
        nextRoarAt = now + Math.round(Tune.ROAR_MAX - (Tune.ROAR_MAX - Tune.ROAR_MIN) * level) + rnd.nextInt(60);
        int best = 0;
        for (int i = 1; i < BINS; i++) if (sector[i] + rnd.nextDouble() * 0.3 > sector[best]) best = i;
        double a = (best + 0.5) / BINS * Math.PI * 2;
        for (Player p : game.players.inPlay()) {
            Location at = p.getLocation().clone().add(Math.cos(a) * 30, 2, Math.sin(a) * 30);
            Props.soundTo(p, at, "sfx.roar_far", Sound.ENTITY_RAVAGER_ROAR, (float) (0.45 + 0.4 * level), 0.85f + rnd.nextFloat() * 0.3f, 0.55f, -1);
        }
        nRoar++;
    }

    // ------------------------------------------------------------------ 하늘: 거대한 그림자 게이브

    /**
     * 거대한 그림자 게이브: G01 모델을 GIANT_SCALE 배로, 흙먼지 속(가운데에서 98블록, 높이 + 14)에서 나와 하늘을 가로질러(가장 낮은 곳 GIANT_ALT) 반대쪽
     * 흙먼지로 사라진다. 날갯짓은 4배 느리고 얼굴은 늘 가운데를 내려다본다. 연출이라 맞지 않는다(엔진 개체가 아님).
     */
    private void startGiant(long now, double a, double side) {
        if (!Look.has(GIANT_MODEL) || fogEnd < 45) return;
        double ux = Math.cos(a), uz = Math.sin(a);
        gx0 = Plaza.CX + 0.5 - ux * 98 - uz * side;
        gz0 = Plaza.CZ + 0.5 - uz * 98 + ux * side;
        gx1 = Plaza.CX + 0.5 + ux * 98 - uz * side;
        gz1 = Plaza.CZ + 0.5 + uz * 98 + ux * side;
        gside = side;
        giantHold = false;
        startGiantAt(now, new Location(game.world, gx0, giantY(0), gz0));
    }

    private void startGiantAt(long now, Location at) {
        giantAt = now;
        lastGiant = now;
        giantFlaps = 0;
        LookState ls = new LookState();
        ls.rate = 0.25;
        ls.phase = rnd.nextInt(80);
        ls.freeze = game.director.silent();
        ItemStack it = Body.item(GIANT_MODEL, "", ls, game.worldTime());
        giant = game.world.spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.FIXED);
            e.setBrightness(new Display.Brightness(6, 8));
            e.setViewRange(4f);
            e.setDisplayWidth(0);
            e.setDisplayHeight(0);
            e.setTeleportDuration(20);
            e.setInterpolationDuration(20);
            e.setTransformationMatrix(giantMatrix(at));
        });
        nGiant++;
        game.plugin.getLogger().info(String.format(Locale.ROOT, "[압도] 거대한 그림자 %.0f,%.0f,%.0f -> %.0f,%.0f 배율 %.0f", at.getX(), at.getY(), at.getZ(), gx1, gz1,
                Tune.GIANT_SCALE));
        if (!game.director.silent()) Props.soundOn(giant, "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f, 0.5f);
    }

    /** 가로지르는 몫 t(0..1)의 높이: 가장자리 + 14, 가운데 GIANT_ALT(완만한 호). */
    private static double giantY(double t) {
        double mid = 1 - 4 * (t - 0.5) * (t - 0.5);
        return Plaza.FLOOR_Y + Tune.GIANT_ALT + 14 * (1 - mid);
    }

    /** 얼굴이 가운데(사람들)를 내려다보게: 몸 틀 Ry(-yaw) Rx(pitch) x 배율 x Ry(PI)(엔진 Body 와 같은 약속). */
    private Matrix4f giantMatrix(Location at) {
        double dx = Plaza.CX + 0.5 - at.getX(), dz = Plaza.CZ + 0.5 - at.getZ(), dy = Plaza.FLOOR_Y + 1.6 - at.getY();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.hypot(dx, dz)));
        float s = (float) Tune.GIANT_SCALE * Look.scale(GIANT_MODEL);
        return new Matrix4f().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch)).scale(s).rotateY((float) Math.PI);
    }

    private void giantTick(long now) {
        if (giant == null || giantHold) return;
        if (!giant.isValid() || game.state == Game.State.LOBBY) {
            giant = null;
            return;
        }
        long t = now - giantAt;
        if (t >= Tune.GIANT_TICKS) {
            giant.remove();
            giant = null;
            return;
        }
        if (t % 20 != 0) return;
        double f = Math.min(1, (t + 20) / (double) Tune.GIANT_TICKS);
        Location at = new Location(game.world, gx0 + (gx1 - gx0) * f, giantY(f), gz0 + (gz1 - gz0) * f);
        giant.teleport(at);
        giant.setInterpolationDelay(0);
        giant.setInterpolationDuration(20);
        giant.setTransformationMatrix(giantMatrix(at));
        // 머리 위를 지날 무렵 한 번 더 거대한 것이 지나가는 울림(몸에 붙어 따라감)
        if (t == (Tune.GIANT_TICKS / 2 - 60) / 20 * 20 && !game.director.silent())
            Props.soundOn(giant, "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.9f, 0.45f);
        // 느린 날갯짓마다(80틱) 거대한 날개 소리
        if (t % 80 == 0 && !game.director.silent()) {
            giantFlaps++;
            Props.soundAround(at, null, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 0.9f + rnd.nextFloat() * 0.15f, 0.45f, 140, -1);
        }
    }

    // ------------------------------------------------------------------ 하늘: 하늘을 메우는 얼굴

    /** 마지막 몰아치기(높은 웨이브): 머리 위(고개를 들어야 보이는 높이)로 얼굴 떼 카드가 흙먼지 위에서 내려와 내려다봄. 몰아치기가 끝나면 올라가 사라짐. */
    private void startSky(long now) {
        if (!sky.isEmpty() || bossOn() || hidden() || fogEnd < 45 || !(Look.has(MODEL) || Look.has(FALLBACK))) return;
        for (int i = 0; i < Tune.SKY_CARDS; i++) {
            double a = Math.PI * 2 * i / Tune.SKY_CARDS + (rnd.nextDouble() - 0.5) * 0.4;
            float sc = (float) (Tune.SCALE_NEAR * (1.1 + rnd.nextDouble() * 0.5));
            Card c = new Card(a, false, true, i % 2 == 0 ? "" : "b", sc, i);
            double rr = Tune.SKY_R * (0.7 + rnd.nextDouble() * 0.6);
            c.spawn(rr, Plaza.FLOOR_Y + Tune.SKY_ALT + 40);
            c.shown = true;
            sky.add(c);
        }
        nSky++;
    }

    private void skyTick(long now) {
        if (sky.isEmpty()) return;
        boolean keep = !hidden() && (game.state == Game.State.WAVE && game.director.finalSurge() || game.state == Game.State.OVER || now < skyForceUntil);
        if (now % 20 != 0) return;
        for (Card c : sky) {
            double yy = keep ? Plaza.FLOOR_Y + Tune.SKY_ALT + 3 * Math.sin(now * 0.01 + c.driftPh) : Plaza.FLOOR_Y + Tune.SKY_ALT + 60;
            c.moveTo(c.r, yy);
        }
        if (keep) skyUpAt = -1;
        else if (skyUpAt < 0) skyUpAt = now;
        else if (now - skyUpAt > Tune.GLIDE + 20) clearSky();
    }

    // ------------------------------------------------------------------ 시험

    /** 시험 훅 '/gave 시험 압도 x [세기 0..1|자동 | 물결 [큰|작은] | 거인 | 하늘 | 끔 | 켬 | 값 이름 [값] | 해질녘 틱 | 카드]'. */
    public String test(Player p, String[] a) {
        if (a.length < 2 || !a[1].equals("압도")) return null;
        long now = game.now();
        String sub = a.length >= 4 ? a[3] : "";
        switch (sub) {
            case "세기":
                if (a.length >= 5) forced = a[4].equals("자동") ? -1 : Double.parseDouble(a[4]);
                return "[압도] 세기 강제 " + (forced < 0 ? "자동" : forced);
            case "물결":
                wave(now, a.length < 5 || !a[4].equals("작은"), "시험");
                return "[압도] 물결 " + lastWave;
            case "거인":
                if (giant != null) giant.remove();
                giant = null;
                lastGiant = -100000;
                startGiant(now, a.length >= 5 ? Math.toRadians(Double.parseDouble(a[4])) : rnd.nextDouble() * Math.PI * 2,
                        a.length >= 6 ? Double.parseDouble(a[5]) : 0);
                return "[압도] 거인 " + (giant != null ? String.format(Locale.ROOT, "%.0f,%.0f -> %.0f,%.0f", gx0, gz0, gx1, gz1) : "없음(모델 또는 안개)");
            case "거인고정": {
                // 시험: 보낸 사람 앞 거리, 바닥 위 높이에 멈춘 거대한 그림자(보이는지, 크기, 얼굴 방향 보기)
                if (giant != null) giant.remove();
                giant = null;
                Player who = p != null ? p : game.players.inPlay().stream().findFirst().orElse(null);
                if (who == null) return "[압도] 거인고정: 사람 없음";
                double dist = a.length >= 5 ? Double.parseDouble(a[4]) : 40, alt = a.length >= 6 ? Double.parseDouble(a[5]) : 30;
                Location l = who.getLocation();
                double yaw = Math.toRadians(l.getYaw());
                double x = l.getX() - Math.sin(yaw) * dist, z = l.getZ() + Math.cos(yaw) * dist;
                gx0 = gx1 = x;
                gz0 = gz1 = z;
                startGiantAt(now, new Location(game.world, x, Plaza.FLOOR_Y + alt, z));
                giantHold = true;
                return "[압도] 거인고정 " + (giant != null ? String.format(Locale.ROOT, "%.1f,%.1f,%.1f", x, Plaza.FLOOR_Y + alt, z) : "없음");
            }
            case "하늘":
                clearSky();
                startSky(now);
                skyForceUntil = now + (a.length >= 5 ? Integer.parseInt(a[4]) : 400);
                return "[압도] 하늘 카드 " + sky.size();
            case "대신":
                forceFallback = a.length < 5 || !a[4].equals("off");
                if (active) clear();
                return "[압도] 대신 모델 " + (forceFallback ? FALLBACK + " " + FALLBACK_STATE : "끔(팩에 " + MODEL + " 가 있으면 그것)");
            case "끔":
                enabled = false;
                clear();
                return "[압도] 끔";
            case "켬":
                enabled = true;
                return "[압도] 켬";
            case "해질녘":
                dusk(a.length >= 5 ? Integer.parseInt(a[4]) : 1200, a.length >= 6 ? Integer.parseInt(a[5]) : 200);
                return "[압도] 해질녘 " + duskAdd;
            case "흔들":
                shake(a.length >= 5 ? Double.parseDouble(a[4]) : Tune.SHAKE, a.length >= 6 ? Integer.parseInt(a[5]) : Tune.SHAKE_TICKS);
                return "[압도] 흔들림 " + shakeAmp + "도 " + shakeLen + "틱";
            case "값":
                return tune(a);
            case "카드":
                StringBuilder sb = new StringBuilder("[압도] 카드");
                for (Card c : cards) {
                    if (!c.shown) continue;
                    sb.append(String.format(Locale.ROOT, " %d:%.0f/%.0f%s", Math.round(Math.toDegrees(c.ang)), c.r, c.w * 100, c.far ? "f" : ""));
                }
                return sb.toString();
            default:
                return status();
        }
    }

    private String tune(String[] a) {
        try {
            if (a.length < 5) {
                StringBuilder sb = new StringBuilder("[압도] 값");
                for (Field f : Tune.class.getFields()) if (Modifier.isStatic(f.getModifiers())) sb.append(' ').append(f.getName()).append('=').append(f.get(null));
                return sb.toString();
            }
            Field f = Tune.class.getField(a[4]);
            if (a.length >= 6) {
                if (f.getType() == int.class) f.setInt(null, Integer.parseInt(a[5]));
                else f.setDouble(null, Double.parseDouble(a[5]));
            }
            return "[압도] 값 " + f.getName() + "=" + f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "[압도] 값 오류 " + e;
        }
    }

    /** 한 줄 상태(시험 훅, 서버 기록). */
    public String status() {
        int shown = 0, valid = 0;
        double rs = 0;
        for (Card c : cards) {
            if (c.d != null && c.d.isValid()) valid++;
            if (c.shown) {
                shown++;
                rs += c.r;
            }
        }
        String g = giant != null && giant.isValid()
                ? String.format(Locale.ROOT, "(%.0f,%.0f,%.0f)", giant.getLocation().getX(), giant.getLocation().getY(), giant.getLocation().getZ()) : "";
        return String.format(Locale.ROOT, "[압도] on=%s 상태=%s 세기=%.2f/%.2f 안개=%.0f..%.0f 카드=%d/%d(살아 있음 %d) 평균반지름=%.1f 모델=%s 물결=큰%d 작은%d 마지막=%s 포효=%d 거인=%d%s 하늘=%d(%d장) 흔들림=%s 디스플레이=%d 틱=%.3f/%.3fms",
                enabled, game.state, level, target, fogStart, fogEnd, shown, cards.size(), valid, shown == 0 ? 0 : rs / shown, model, nBig, nSmall,
                lastWave, nRoar, nGiant, g, nSky, sky.size(), shakeAt >= 0 ? "중" : "-", displays(), tickAvgShown, tickMaxShown);
    }

    /** 이 연출이 쓰는 디스플레이 수(시험, 프레임 측정). */
    public int displays() {
        int n = 0;
        for (Card c : cards) if (c.d != null && c.d.isValid()) n++;
        for (Card c : sky) if (c.d != null && c.d.isValid()) n++;
        if (giant != null && giant.isValid()) n++;
        return n;
    }
}
