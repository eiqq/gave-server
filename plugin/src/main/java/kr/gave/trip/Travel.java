package kr.gave.trip;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.gun.Guns;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Fog;
import kr.gave.world.Plaza;
import kr.gave.world.Roam;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * 이동 연출(원정.md 3.4절): 트럭에 모여 달리고(사람도 트럭도 옮기지 않는다: 세상이 뒤로 흐름, {@link Stream}), 화면이 검게 덮인 채 글 한 줄이 떴다가
 * 다음 스테이지에서 다시 밝아진다(2026-11-12 사용자: "맵이 전환될 때 화면 거멓게 되고 중간에 스토리 한 줄이나 팁들"). 보통 10초, 막 넘김과 발사장 진입은 16초.
 *
 * <pre>
 * 틱 0              경적 두 번, 원 밖 사람을 트럭 곁으로, 반지름 confine 안에 가둠, 상점 닫힘, 사건 EV_DEPART
 * 20               레터박스, 엔진, 안개를 5 로(20틱), 흐름 시작(길 점선, 먼지 줄, 길가 소품, 약한 화면 떨림)
 * blackIn          화면이 검어지기 시작(10틱), 글 한 줄(이야기나 팁, 판에서 되풀이 없음)
 * hidden           다 검음: 사건 {@link #EV_HIDDEN}(옛 소품과 표지판 치우기, 다음 스테이지로 넘김, 땅 다시 칠하기는 이때부터), 흐름과 안개를 걷음
 * reveal = 끝 - 20 밝아지기 시작(20틱), 브레이크, 사건 {@link #EV_REVEAL}(도착 보상과 스테이지 이름은 Route 가)
 * 끝               가둠 풀림
 * </pre>
 *
 * <p>검은 화면은 {@link #black}(Travel 밖에서도 부른다: 판 시작, 게임 오버, 엔딩 앞). 그리는 법은 둘이다.
 * HUD(trip_core 의 Hud: 덮개 'black' 과 가운데 STORY 칸)가 있으면 다 검은 동안은 hud 값 screen.black(true), screen.line(줄 키)을 내놓아 HUD 가 그리고
 * (그동안 다른 HUD 는 가려짐), 검어지고 밝아지는 흐림만 바닐라 제목 글의 검은 글리프로 한다. HUD 덮개가 없거나 그 줄이 HUD 글귀로 안 구워졌으면
 * 바닐라 제목 글로만: 큰 제목 자리에 팩의 검은 글리프(글꼴 gave:trip, 화면을 다 덮는 크기), 부제 자리에 글 한 줄(같은 글꼴의 미리 그린 Galmuri 그림 글리프.
 * tools/trip/route.py 가 굽고 팩의 assets/gave/trip_loading.json 이 줄 키 → 글리프를 알려 준다). 제목 글은 HUD(보스바) 위에 그려져 HUD 도 함께 가린다.
 * 팩에 그 글꼴도 없으면 글은 바닐라 글꼴로, 어둠은 실명 효과로 대신한다. {@link #useHud} 를 끄면 늘 제목 글로만.
 */
public final class Travel implements TripPart {

    /** 부품 사이 사건: 화면이 다 가려짐(arg = 이동 길이 틱 Integer), 다시 드러나기 시작(arg null). 가려진 동안 땅과 소품을 바꾼다. */
    public static final String EV_HIDDEN = "travelHidden", EV_REVEAL = "travelReveal";
    /** 검은 화면이 다 덮인 채 머무는 틱(보통, 막 넘김), 검어지는 틱, 밝아지는 틱. */
    public static int BLACK_STAY = 80, BLACK_STAY_ACT = 120, BLACK_IN = 10, BLACK_OUT = 20;
    /** 판 시작의 검은 화면(틱, 0 이면 없음)과 그때의 줄. */
    public static int START_BLACK = 50;
    public static String START_LINE = "ld_s0_human";
    /**
     * 판 시작 오프닝(사용자 2026-11-12): 검은 화면 가운데에 이 글귀(data/phrases/trip_opening.json)를 OPEN_STAY 틱(글은 그 안에서 약 6초),
     * 원정 판이면 이어서 START_LINE 줄. 검어지고 밝아지는 흐림은 엔딩과 같은 틱(Launch.Tune.FADE_TICKS). 글귀가 HUD 에 안 구워졌으면 건너뜀.
     */
    public static String OPEN_LINE = "opening_1";
    public static int OPEN_STAY = 130;
    /** HUD 덮개 'black' 이 팩에 있으면 다 검은 동안은 HUD 가 그린다(false 면 늘 제목 글로만). */
    public static boolean useHud = true;
    /** HUD 로 그릴 때 다 검어진 뒤 글이 뜨기까지, 밝아지기 전에 글이 지는 틱. */
    public static int LINE_DELAY = 6, LINE_EARLY = 4;
    /** 떨림(도)과 간격(틱). */
    public static float SHAKE = 0.15f;
    public static int SHAKE_EVERY = 6;

    private static final Key FONT = Key.key(Props.NS, "trip");

    /** 줄 하나(이야기나 팁). */
    private static final class Line {
        final String key, text, kind;
        final int actFrom, actTo;

        Line(String key, String text, String kind, int actFrom, int actTo) {
            this.key = key;
            this.text = text;
            this.kind = kind;
            this.actFrom = actFrom;
            this.actTo = actTo;
        }
    }

    private final Game game;
    private final Trip trip;
    private final StageData data;
    private final Stream stream;
    private final Random rnd = new Random();

    /** 이동 중인가, 시작 틱(판 시계), 길이, 때들(시작 뒤 틱). */
    private boolean active;
    private long startAt;
    private int length, blackIn, hidden, reveal;
    private boolean didHidden, didReveal;
    private Runnable onHidden, onReveal;
    private int fogBase;
    private int runs;

    /** 줄(jar 의 trip/loading.json), 이 판에서 쓴 줄. */
    private final List<Line> lines = new ArrayList<>();
    private final Set<String> used = new HashSet<>();
    /** 팩의 글리프(줄 키 → 글리프 글, 검은 글리프). 팩이 바뀌면 다시 읽는다. */
    private byte[] packSeen;
    private final Map<String, String> glyphs = new HashMap<>();
    private String blackGlyph;
    /** 지금 검은 화면: 판 틱(다 검음, 밝아지기 시작, 끝), 줄 키, 보는 사람, HUD 가 그리나, 밝아지는 흐림을 보냈나. */
    private long blackFull = -1, blackFade = -1, blackUntil = -1;
    private String blackLine;
    private final Set<UUID> blackWho = new HashSet<>();
    private boolean blackHud, fadeSent;
    private int blacks;
    /** 오프닝 다음 줄(START_LINE)을 띄울 판 틱(없으면 -1): 앞 검은 화면이 밝아지기 한 틱 전에 이어 붙인다. */
    private long chainAt = -1;

    public Travel(Game game, Trip trip) {
        this.game = game;
        this.trip = trip;
        Route r = trip.part(Route.class);
        this.data = r != null ? r.data() : StageData.load(Travel.class.getClassLoader(), game.plugin.getLogger());
        this.stream = new Stream(game);
        loadLines();
    }

    // ------------------------------------------------------------------ 읽기

    /** 이동 중인가. */
    public boolean active() {
        return active;
    }

    /** 이동 진행(0..1), 이동 중이 아니면 -1. */
    public double progress() {
        return active ? Math.min(1, (double) (game.now() - startAt) / Math.max(1, length)) : -1;
    }

    /** 화면이 다 가려져 있나(이동 중 hidden 과 reveal 사이): 이때 세상을 바꿔도 안 보인다. */
    public boolean hidden() {
        long t = game.now() - startAt;
        return active && t >= hidden && t < reveal;
    }

    /** 흐름 연출(추격전이 따로 하나 만들어 써도 되고, 이것을 빌려 써도 된다: 이동 중이 아닐 때만). */
    public Stream stream() {
        return stream;
    }

    // ------------------------------------------------------------------ 이동

    /**
     * 이동을 시작한다. ticks = 길이, act = 막 넘김(검은 화면이 길다), left = 거리 표지에 적을 남은 스테이지 수(음수면 없음),
     * hiddenDo = 화면이 다 가려졌을 때 할 일, revealDo = 드러나기 시작할 때 할 일(둘 다 null 가능).
     */
    public void start(int ticks, boolean act, int left, Runnable hiddenDo, Runnable revealDo) {
        if (active) finish();
        active = true;
        runs++;
        startAt = game.now();
        int stay = act ? BLACK_STAY_ACT : BLACK_STAY;
        length = Math.max(ticks, 40 + BLACK_IN + stay + BLACK_OUT);
        reveal = length - BLACK_OUT;
        hidden = reveal - stay;
        blackIn = hidden - BLACK_IN;
        didHidden = didReveal = false;
        onHidden = hiddenDo;
        onReveal = revealDo;
        fogBase = game.director.fog();
        // 이동 동안 지평선 그림자 숨김(움직이는 길 위에 멈춘 떼 그림이 남지 않게)
        game.horizon.hide(length);
        stream.mark(left, Stage.LAUNCH);
        Location c = center();
        Props.soundAround(c, "trip.horn", Sound.ITEM_GOAT_HORN_SOUND_2, 2.0f, 1f, 0.8f, 64, -1);
        // 원 밖 사람은 트럭 곁으로(화면 깜빡), 상점 닫기
        for (Player p : game.players.inPlay()) {
            if (confined(p) && radius(p) > data.travelConfine) {
                if (Hud.hasCover("dark")) game.hud.cover(p, "dark", 8);
                double ang = rnd.nextDouble() * Math.PI * 2, r = 4.0 + rnd.nextDouble() * 2.5;
                move(p, Plaza.CX + 0.5 + Math.cos(ang) * r, Plaza.CZ + 0.5 + Math.sin(ang) * r);
            }
            game.shop.close(p);
        }
        // 이동 곡(music.json cues 'travel': 팩에 없으면 Music 이 무시하고 쉬는 시간 곡 그대로). 이동이 끝나면 풀림
        game.music.cue("travel", length);
        trip.log("이동 시작 " + length + "틱" + (act ? " 막 넘김" : "") + " 검은 화면 " + blackIn + ".." + reveal);
    }

    /** 이동을 바로 끝낸다(남은 일은 건너뛰지 않고 차례대로 한다: 가려짐 → 드러남 → 정리). */
    public void finish() {
        if (!active) return;
        if (!didHidden) hiddenNow();
        if (!didReveal) revealNow();
        end();
    }

    private void hiddenNow() {
        didHidden = true;
        stream.stop();
        fog(Fog.pos(game.director.fog()));
        if (onHidden != null) onHidden.run();
        trip.fire(EV_HIDDEN, length);
    }

    private void revealNow() {
        didReveal = true;
        Props.soundAround(center(), "trip.brake", Sound.ENTITY_MINECART_INSIDE, 0.9f, 1f, 0.6f, 48, -1);
        if (onReveal != null) onReveal.run();
        trip.fire(EV_REVEAL, null);
    }

    private void end() {
        // 이동 곡을 건 것이 이 이동이면 풀기(그사이 다른 cue 가 들었으면 그대로)
        if (active && "travel".equals(game.music.cue())) game.music.uncue();
        active = false;
        stream.stop();
        fog(Fog.pos(game.director.fog()));
        onHidden = onReveal = null;
    }

    private void travelTick() {
        long t = game.now() - startAt;
        if (t == 8) Props.soundAround(center(), "trip.horn", Sound.ITEM_GOAT_HORN_SOUND_2, 2.0f, 1f, 0.8f, 64, -1);
        if (t == 20) {
            if (Hud.hasCover("letterbox")) {
                for (Player p : game.players.inPlay()) if (game.players.get(p).cover == null) game.hud.cover(p, "letterbox", Math.max(1, hidden - 20));
            }
            Props.soundAround(center(), "trip.engine", Sound.ENTITY_MINECART_RIDING, 1.2f, 1f, 0.7f, 48, -1);
            stream.start(data.travelSpeed, 30);
        }
        // 안개: 20..40 틱에 연출 단계로(흐르는 것들이 나타나고 사라지는 자리를 가림)
        if (t >= 20 && t <= 40 && !didHidden) {
            double a = Fog.pos(fogBase), b = Fog.pos(data.travelFog);
            fog(a + (b - a) * (t - 20) / 20.0);
        }
        if (t == blackIn) {
            // 검은 화면: 검어지기 BLACK_IN, 머묾 reveal - hidden, 밝아지기 BLACK_OUT
            black(BLACK_IN, reveal - hidden, BLACK_OUT, null);
            stream.speed(0, Math.max(1, hidden - blackIn + 10));
        }
        if (!didHidden && t >= hidden) hiddenNow();
        if (!didReveal && t >= reveal) revealNow();
        if (t >= 20 && t < hidden && SHAKE > 0 && t % SHAKE_EVERY == 0) {
            float s = (t / SHAKE_EVERY) % 2 == 0 ? SHAKE : -SHAKE;
            for (Player p : game.players.inPlay()) if (confined(p)) Guns.kick(p, 0f, s);
        }
        // 가둠: 반지름 안에서만(걷고 쏘는 것은 그대로)
        for (Player p : game.players.inPlay()) {
            if (!confined(p)) continue;
            double r = radius(p);
            if (r > data.travelConfine + 3) {
                double k = (data.travelConfine - 0.5) / r;
                Location l = p.getLocation();
                move(p, Plaza.CX + 0.5 + (l.getX() - Plaza.CX - 0.5) * k, Plaza.CZ + 0.5 + (l.getZ() - Plaza.CZ - 0.5) * k);
            } else if (r > data.travelConfine && (game.now() & 1) == 0) {
                Location l = p.getLocation();
                double ux = (l.getX() - Plaza.CX - 0.5) / r, uz = (l.getZ() - Plaza.CZ - 0.5) / r, v = 0.18 + 0.22 * (r - data.travelConfine);
                p.setVelocity(new org.bukkit.util.Vector(-ux * v, Math.max(-0.08, p.getVelocity().getY()), -uz * v));
            }
            // 상점은 이동 동안 닫힘(상점 쪽 고리가 생기기 전까지 열린 화면을 닫는다)
            if (t % 5 == 0 && game.shop.view(p) != null) game.shop.close(p);
        }
        stream.tick();
        if (t >= length) end();
    }

    private boolean confined(Player p) {
        PState s = game.players.get(p.getUniqueId());
        return s != null && s.life == Life.ALIVE && Roam.applies(p);
    }

    private static double radius(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - Plaza.CX - 0.5, l.getZ() - Plaza.CZ - 0.5);
    }

    /** 시선은 그대로 둔 채 옮긴다(Roam 과 같은 방식). */
    private static void move(Player p, double x, double z) {
        ((CraftPlayer) p).getHandle().connection.teleport(new PositionMoveRotation(new Vec3(x, p.getLocation().getY(), z), Vec3.ZERO, 0f, 0f),
            EnumSet.of(Relative.Y_ROT, Relative.X_ROT));
    }

    private Location center() {
        return new Location(game.world, Plaza.CX + 0.5, Plaza.FLOOR_Y + 1.5, Plaza.CZ + 0.5);
    }

    /** 안개 시계를 그 타임라인 자리로(감독의 안개가 멈춰 있는 쉬는 시간에만 쓴다. 시계가 없으면 보기 거리로). */
    private void fog(double pos) {
        if (Fog.ready(game.world)) Fog.setTicks(game.world, Fog.ticksAt(pos));
        else {
            int lv = Fog.levelAt(pos);
            for (Player p : game.world.getPlayers()) Fog.view(p, lv);
        }
    }

    // ------------------------------------------------------------------ 검은 화면 + 글 한 줄

    /**
     * 판에 든 모두의 화면을 검게 덮고 한가운데에 글 한 줄을 띄운다. in 틱 동안 검어지고 stay 틱 머문 뒤 out 틱 동안 밝아진다.
     * lineKey 가 null 이면 이 판에서 아직 안 쓴 줄 가운데 무작위(지금 막의 이야기 줄과 팁을 섞음), "" 이면 글 없이. 쓴 줄의 키를 돌려준다(없으면 null).
     */
    public String black(int in, int stay, int out, String lineKey) {
        return black(game.players.inPlay(), in, stay, out, lineKey);
    }

    /**
     * 그 사람들에게만. lineKey 는 이동 화면 줄 키(ld_*)나 팩에 함께 구운 다른 줄 키(ending_1 같은 것: tools/trip/route.py EXTRA_SRC).
     * 모르는 키인데 HUD 글귀로는 있으면 그 글을 바닐라 글꼴로 띄운다.
     */
    public String black(Collection<Player> who, int in, int stay, int out, String lineKey) {
        loadPack();
        Line line = lineKey == null ? pick() : find(lineKey);
        if (line == null && lineKey != null && !lineKey.isEmpty() && Hud.phrase(lineKey) != null) line = new Line(lineKey, Hud.phrase(lineKey), "extra", 1, 4);
        if (line != null) used.add(line.key);
        blacks++;
        long now = game.now();
        blackFull = now + in;
        blackFade = blackFull + stay;
        blackUntil = blackFade + out;
        blackLine = line == null ? null : line.key;
        blackWho.clear();
        for (Player p : who) blackWho.add(p.getUniqueId());
        fadeSent = false;
        blackHud = useHud && Hud.hasCover("black") && (blackLine == null || Hud.hasPhrase(blackLine));
        if (blackHud) {
            // 다 검은 동안은 HUD(hud 값 screen.black, screen.line). 검어지는 흐림만 제목 글의 검은 글리프로(밝아지는 흐림은 tick 이 blackFade 에 보냄)
            if (blackGlyph != null && in > 0) {
                Title t = Title.title(blackTitle(), Component.empty(), Title.Times.times(Duration.ofMillis(in * 50L), Duration.ofMillis(150), Duration.ZERO));
                for (Player p : who) p.showTitle(t);
            }
            trip.log("검은 화면(HUD) " + (in + stay + out) + "틱 줄 " + (line == null ? "-" : line.key + " " + line.text));
            return blackLine;
        }
        Component sub = Component.empty();
        if (line != null) {
            String g = glyphs.get(line.key);
            sub = g != null ? Component.text(g, NamedTextColor.WHITE).font(FONT) : Component.text(line.text, NamedTextColor.WHITE);
        }
        Component main = blackTitle();
        Title title = Title.title(main, sub, Title.Times.times(Duration.ofMillis(in * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(out * 50L)));
        for (Player p : who) {
            p.showTitle(title);
            if (blackGlyph == null) {
                // 팩에 검은 글리프가 없을 때: 실명으로 어둡게(가까운 것만 보임)
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, in + stay + out / 2, 0, true, false, false));
            }
        }
        trip.log("검은 화면 " + (in + stay + out) + "틱 줄 " + (line == null ? "-" : line.key + " " + line.text) + (blackGlyph == null ? " (팩에 글꼴 없음: 실명으로 대신)" : ""));
        return blackLine;
    }

    /** 제목 글의 큰 제목: 팩의 검은 글리프(없으면 빈칸). */
    private Component blackTitle() {
        return blackGlyph != null ? Component.text(blackGlyph, NamedTextColor.WHITE).font(FONT) : Component.text(" ");
    }

    /** 검은 화면이 아직 걸려 있나(검어지는 중부터 밝아지는 중까지). */
    public boolean blackActive() {
        return blackUntil > game.now();
    }

    /** HUD 로 그리는 검은 화면: 밝아지기 시작할 때 제목 글의 검은 글리프를 다 보인 채 흐려지게 보내(HUD 덮개가 걷히는 틱과 겹치게) 부드럽게 밝아진다. */
    private void blackTick() {
        if (!blackHud || fadeSent || blackUntil < 0) return;
        long now = game.now();
        if (now < blackFade - 1) return;
        fadeSent = true;
        int out = (int) (blackUntil - blackFade);
        if (blackGlyph == null || out <= 0 || now >= blackUntil) return;
        Title t = Title.title(blackTitle(), Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(out * 50L)));
        for (UUID id : blackWho) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) p.showTitle(t);
        }
    }

    /** 이 판에서 안 쓴 줄 가운데 하나: 이야기(지금 막 것)와 팁을 반반. 다 썼으면 처음부터 다시. */
    private Line pick() {
        int act = trip.act();
        List<Line> story = new ArrayList<>(), tip = new ArrayList<>();
        // HUD 가 그릴 때는 HUD 글귀로 구워진 줄만(make_hud 를 다시 돌리기 전에 새로 적은 줄은 건너뜀)
        boolean hud = useHud && Hud.hasCover("black");
        for (Line l : lines) {
            if (used.contains(l.key) || l.key.equals(START_LINE)) continue;
            if (hud && !Hud.hasPhrase(l.key)) continue;
            if (l.kind.equals("tip")) tip.add(l);
            else if (l.kind.equals("story") && act >= l.actFrom && act <= l.actTo) story.add(l);
        }
        if (story.isEmpty() && tip.isEmpty()) {
            if (lines.isEmpty() || used.isEmpty()) return null;
            used.clear();
            return pick();
        }
        List<Line> from = story.isEmpty() ? tip : tip.isEmpty() ? story : rnd.nextBoolean() ? story : tip;
        return from.get(rnd.nextInt(from.size()));
    }

    private Line find(String key) {
        for (Line l : lines) if (l.key.equals(key)) return l;
        return null;
    }

    /** jar 의 trip/loading.json({"lines": [{"key", "text", "kind": "story"|"tip", "acts": [처음, 끝]}]}). */
    private void loadLines() {
        lines.clear();
        try (InputStream in = Travel.class.getClassLoader().getResourceAsStream("trip/loading.json")) {
            if (in == null) throw new IllegalStateException("jar 에 trip/loading.json 없음");
            JsonObject o = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            for (JsonElement e : o.getAsJsonArray("lines")) {
                JsonObject l = e.getAsJsonObject();
                int a = 1, b = 4;
                if (l.has("acts")) {
                    a = l.getAsJsonArray("acts").get(0).getAsInt();
                    b = l.getAsJsonArray("acts").get(1).getAsInt();
                }
                lines.add(new Line(l.get("key").getAsString(), l.get("text").getAsString(), l.has("kind") ? l.get("kind").getAsString() : "tip", a, b));
            }
        } catch (Exception e) {
            game.plugin.getLogger().warning("[이동] 줄 읽기 실패: " + e);
        }
    }

    /** 내보내는 팩의 assets/gave/trip_loading.json({"black": 글리프, "lines": {키: 글리프 글}}). 팩이 바뀌었을 때만 다시 읽는다. */
    private void loadPack() {
        byte[] b = game.plugin.pack == null ? null : game.plugin.pack.bytes();
        if (b == packSeen) return;
        packSeen = b;
        glyphs.clear();
        blackGlyph = null;
        if (b == null) return;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(b))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (!e.getName().equals("assets/" + Props.NS + "/trip_loading.json")) continue;
                JsonObject o = new Gson().fromJson(new String(z.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                if (o.has("black")) blackGlyph = o.get("black").getAsString();
                if (o.has("lines")) for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("lines").entrySet()) glyphs.put(en.getKey(), en.getValue().getAsString());
                break;
            }
        } catch (Exception ex) {
            game.plugin.getLogger().warning("[이동] 팩의 trip_loading.json 읽기 실패: " + ex);
        }
    }

    // ------------------------------------------------------------------ TripPart

    @Override
    public void startGame() {
        used.clear();
        blacks = 0;
        chainAt = -1;
        // 판 시작 검은 화면(오프닝 + START_LINE)은 Game.start 가 부르는 opening(Game) 이 띄운다(원정이 꺼진 판에서도 오프닝이 나오게)
    }

    // ------------------------------------------------------------------ 판 시작 오프닝

    /**
     * 판 시작 오프닝을 띄운다(Game.start 가 set(PREP) 바로 뒤에 부름, 원정이 꺼져 있어도). 다 검은 화면이 밝아지기 시작하기까지의 틱을 돌려준다
     * (Game 은 그동안 준비 시간을 세지 않는다). 0 이면 오프닝 없음(예전처럼 START_LINE 검은 화면만, 준비 시간 그대로).
     */
    public static int opening(Game game) {
        Travel tr = game.trip.part(Travel.class);
        return tr == null ? 0 : tr.open(game.trip.enabled);
    }

    /** 엔딩과 같은 흐림 틱(검어지고 밝아짐: Launch.Tune.FADE_TICKS). */
    private static int fade() {
        return Math.max(2, Launch.Tune.FADE_TICKS);
    }

    private int open(boolean tripOn) {
        boolean has = OPEN_STAY > 0 && Hud.hasPhrase(OPEN_LINE);
        if (tripOn) {
            if (!has) {
                if (START_BLACK > 0) black(2, START_BLACK, BLACK_OUT, START_LINE);
                return 0;
            }
            // 오프닝 줄(검어지는 흐림 fade) → 밝아지기 한 틱 전에 START_LINE 줄로 이어 붙임(tick 의 chainAt) → 밝아지는 흐림 fade
            int in = fade();
            black(game.players.inPlay(), in, OPEN_STAY, START_BLACK > 0 ? 0 : in, OPEN_LINE);
            if (START_BLACK > 0) chainAt = blackFade - 1;
            trip.log("오프닝 " + OPEN_LINE + " " + OPEN_STAY + "틱" + (START_BLACK > 0 ? " 이어서 " + START_LINE : ""));
            return (int) ((START_BLACK > 0 ? blackFade - 1 + START_BLACK : blackFade) - game.now());
        }
        // 원정이 꺼진 판: Travel.tick 과 원정 hud 값이 안 돌므로 HUD 의 글 차례(story)로 그리고, 흐림은 제목 글의 검은 글리프를 스케줄러로
        if (!has || !Hud.hasCover("black")) return 0;
        loadPack();
        int in = fade(), out = fade(), round = game.round;
        List<Player> who = new ArrayList<>(game.players.inPlay());
        if (blackGlyph != null) {
            Title t = Title.title(blackTitle(), Component.empty(), Title.Times.times(Duration.ofMillis(in * 50L), Duration.ofMillis(150), Duration.ZERO));
            for (Player p : who) p.showTitle(t);
        }
        Bukkit.getScheduler().runTaskLater(game.plugin, () -> {
            if (game.round == round && game.state != Game.State.LOBBY) game.hud.story(List.of(OPEN_LINE), OPEN_STAY, 0);
        }, in - 1);
        if (blackGlyph != null) Bukkit.getScheduler().runTaskLater(game.plugin, () -> {
            if (game.round != round || game.state == Game.State.LOBBY) return;
            Title t = Title.title(blackTitle(), Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(out * 50L)));
            for (Player p : who) if (p.isOnline()) p.showTitle(t);
        }, in - 2 + OPEN_STAY);
        game.plugin.getLogger().info("[이동] 오프닝(원정 꺼짐) " + OPEN_LINE + " " + OPEN_STAY + "틱");
        return in - 1 + OPEN_STAY;
    }

    /** 오프닝 다음 줄: 앞 검은 화면이 밝아지기 한 틱 전(tick 이 HUD 그리기 뒤에 돌아 덮개가 끊기지 않음). 검어지는 흐림 없이 바로, 밝아지는 흐림은 엔딩과 같게. */
    private void chainTick() {
        if (chainAt < 0 || game.now() < chainAt) return;
        chainAt = -1;
        if (game.state == Game.State.LOBBY || game.state == Game.State.OVER) return;
        black(game.players.inPlay(), 0, START_BLACK, fade(), START_LINE);
    }

    @Override
    public void beforeRound(int n) {
        // 라운드가 열리는데 아직 달리는 중이면(시험으로 건너뜀) 바로 끝낸다
        if (active) finish();
    }

    @Override
    public void tick() {
        blackTick();
        chainTick();
        if (active) {
            if (game.state == Game.State.LOBBY || game.state == Game.State.OVER) finish();
            else travelTick();
        } else if (stream.running() && testStream) stream.tick();
    }

    @Override
    public void gameOver() {
        if (active) finish();
    }

    @Override
    public void reset() {
        if (active) finish();
        stream.stop();
        testStream = false;
        used.clear();
        chainAt = -1;
        blackFull = blackFade = blackUntil = -1;
        blackLine = null;
        blackWho.clear();
        blackHud = false;
    }

    @Override
    public void enabled(boolean on) {
        if (!on) reset();
    }

    @Override
    public Object ask(String key) {
        return switch (key) {
            case "disp" -> stream.displays();
            case "travel" -> active ? (Object) progress() : null;
            case "travelHidden" -> active && hidden() ? Boolean.TRUE : null;
            default -> null;
        };
    }

    @Override
    public void hud(Player p, Map<String, Object> out) {
        if (active) out.put("travel.frac", progress());
        long now = game.now();
        if (blackUntil <= now || !blackWho.contains(p.getUniqueId())) return;
        out.put("travel.black", (int) (blackUntil - now));
        // HUD 가 그리는 동안(다 검음 한 틱 앞부터 밝아지기 시작까지): 온 화면 검정, 그 사이에 가운데 글 한 줄
        if (blackHud && now >= blackFull - 1 && now < blackFade) {
            out.put("screen.black", Boolean.TRUE);
            if (blackLine != null && now >= blackFull + LINE_DELAY && now < blackFade - LINE_EARLY) out.put("screen.line", blackLine);
        }
    }

    @Override
    public String status() {
        long left = blackUntil - game.now();
        return "travel=" + (active ? String.format(Locale.ROOT, "%.2f", progress()) + (hidden() ? "(black)" : "") : "-") + " stream=" + stream.displays()
            + " black=" + (left > 0 ? left + (blackHud ? "(hud)" : "(title)") + (blackLine == null ? "" : ":" + blackLine) : "-");
    }

    /** 시험으로 켠 흐름(이동 중이 아닐 때 달리기 훅). */
    private boolean testStream;

    /**
     * 시험 훅. '달리기 x 시작 [빠르기] [끝자리]'(흐름만 켬), '달리기 x 끝', '달리기 x 상태', '검은화면 x [틱] [줄 키]', '검은화면 x 줄'(줄 목록 수),
     * '이동 x [틱] [막]'(지금 바로 이동 연출만: 스테이지는 안 바뀜).
     */
    @Override
    public String test(Player p, String[] a) {
        String act = a[1];
        String sub = a.length > 3 ? a[3] : "";
        switch (act) {
            case "달리기" -> {
                switch (sub) {
                    case "시작" -> {
                        if (active) return "[이동] 이동 중에는 못 켬";
                        double sp = a.length > 4 ? Double.parseDouble(a[4]) : data.travelSpeed;
                        if (a.length > 5) stream.span = Double.parseDouble(a[5]);
                        stream.mark(trip.stagesLeft(), Stage.LAUNCH);
                        stream.start(sp, 30);
                        testStream = true;
                        return "[이동] 흐름 시작 speed=" + sp + " span=" + stream.span;
                    }
                    case "끝" -> {
                        stream.stop();
                        testStream = false;
                        return "[이동] 흐름 끝";
                    }
                    default -> {
                        return "[이동] stream=" + stream.displays() + " running=" + stream.running() + String.format(Locale.ROOT, " speed=%.2f dist=%.1f", stream.speed(), stream.distance());
                    }
                }
            }
            case "검은화면" -> {
                if (sub.equals("방식")) {
                    if (a.length > 4) useHud = a[4].equals("hud");
                    return "[이동] 검은 화면 방식 " + (useHud ? "hud" : "title") + " HUD 덮개 " + Hud.hasCover("black");
                }
                if (sub.equals("줄")) {
                    loadPack();
                    int story = 0, tip = 0;
                    for (Line l : lines) {
                        if (l.kind.equals("tip")) tip++;
                        else if (l.kind.equals("story")) story++;
                    }
                    int baked = 0;
                    for (Line l : lines) if (Hud.hasPhrase(l.key)) baked++;
                    return "[이동] 줄 " + lines.size() + "(이야기 " + story + ", 팁 " + tip + ") 팩 글리프 " + glyphs.size() + " 검은 글리프 " + (blackGlyph != null)
                        + " HUD 글귀 " + baked + " HUD 덮개 " + Hud.hasCover("black") + " 쓴 줄 " + used.size();
                }
                int ticks = sub.isEmpty() ? BLACK_STAY : Integer.parseInt(sub);
                String key = a.length > 4 ? a[4] : null;
                Collection<Player> who = p != null ? List.of(p) : new ArrayList<>(Bukkit.getOnlinePlayers());
                String used0 = black(who, BLACK_IN, ticks, BLACK_OUT, key);
                return "[이동] 검은 화면 " + ticks + "틱 줄=" + used0;
            }
            case "이동" -> {
                int ticks = sub.isEmpty() ? data.travelNormal : Integer.parseInt(sub);
                boolean actChange = a.length > 4 && a[4].equals("막");
                start(ticks, actChange, trip.stagesLeft(), null, null);
                return "[이동] 시작 " + length + "틱 blackIn=" + blackIn + " hidden=" + hidden + " reveal=" + reveal;
            }
            default -> {
                return null;
            }
        }
    }

    /** 줄 표(시험, 다른 부품): 키 → 글. */
    public Map<String, String> lines() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Line l : lines) out.put(l.key, l.text);
        return out;
    }
}
