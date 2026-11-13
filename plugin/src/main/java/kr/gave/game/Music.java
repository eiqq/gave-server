package kr.gave.game;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import kr.gave.Props;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.mob.boss.Boss;
import kr.gave.mob.boss.BossDirector;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * 배경 음악(docs/모듈/브금.md). 곡은 내려받은 CC BY, CC0 곡을 tools/make_bgm.py 가 잘라 팩에 넣은 것(사건 gave:music.&lt;이름&gt;), 길이와
 * 단계 목록은 내보내는 팩(PackServer.bytes())의 assets/gave/music.json 에서 읽는다(/gave 팩 으로 팩을 다시 읽으면 다음 검사 때 다시 읽음).
 *
 * <p>사람마다 따로 튼다: 그 사람 몸에 붙인 소리(자리 없는 스테레오, 스트리밍)로 보내고 되풀이와 잇기는 서버가 틱으로 센다.
 * <ul>
 * <li>LOBBY, PREP: 대기 곡 되풀이(PREP 으로 넘어가도 끊지 않음).</li>
 * <li>WAVE: 웨이브가 시작될 때 단계를 고르고 같은 단계 곡을 돌려 가며 튼다. 도중에 곡을 끊고 바꾸지 않는다: 곡이 끝날 때만 긴장도가
 * 몰렸으면 한 단계 올리고 풀렸으면 웨이브 시작 단계로 내린다.
 * <ul>
 * <li>막을 알면(2026-11-12 원정, {@link #act(int)} 또는 원정이 켜져 있으면 그 라운드의 막): 그 막의 곡 묶음(music.json 'acts', 막마다
 * 낮음, 높음, 보스). 막 앞쪽은 낮음, 막 뒤쪽(10 가운데 7 부터, 4막은 5 가운데 4 부터)과 직전 웨이브 긴장도 0.55 넘으면 높음.
 * 1 마른 길 빠른 락, 2 젖은 벌 무거운 락, 3 재의 땅 메탈, 4 발사장 가장 거친 메탈.</li>
 * <li>막을 모르면(옛 판, 원정 꺼짐): 깊이(1~10 낮음, 11~25 중간, 26~ 높음)와 직전 웨이브 긴장도로 세 단계(music.json 'tiers').</li>
 * </ul></li>
 * <li>BREAK: 뮤직박스 동요 10초 조각을 이어 틀고, 쉬는 시간 끝 5초 전에 치솟는 끝 조각. 다음이 보스 웨이브면 동요 대신 조여 오는
 * 드론(boss_pre) 한 번(쉬는 시간 끝 신호는 그대로).</li>
 * <li>OVER: 전멸 곡 한 번. 소리 없는 웨이브(director.silent())에서는 뚝 끊고, 끝나면 돌아온다.</li>
 * <li>보스전: {@link #boss(boolean)} 로 켜면 웨이브 곡을 멈추고 뿔나팔 신호(boss_sting) 뒤 보스 곡(막을 알면 그 막의 보스 곡)을 돌려 가며
 * 튼다. 보스가 마지막 단계에 들면 boss_final 로 1.5초 겹쳐 넘긴다. 쓰러지면({@code boss(false)} 바로 뒤 {@code hush(true)}, 또는
 * {@link #bossDown()}) 곡을 끊고 쓰러짐 신호(boss_down) 뒤 조용히. 그 판 첫 B5 가 쓰러지면 100틱 뒤 엔딩 곡(cue 'ending') 한 번.</li>
 * <li>cue(2026-11-12 재미 장치, 원정): {@link #cue(String, int)} 로 웨이브 곡 대신 그 곡(music.json 'cues': travel 이동, chase 추격전,
 * laststand 최후의 저항, credits 엔딩 크레딧, ending 엔딩 새벽). 차례: OVER &gt; 소리 없음 &gt; (조용히 &gt; 보스 곡) &gt; cue &gt; 보통.
 * credits, ending 은 조용히와 보스 곡보다 앞선다. 저절로 풀리는 때는 cue 마다(웨이브 시작, 웨이브 끝, 대기실), 틱을 주면 그 틱 뒤,
 * {@link #uncue()} 로 바로.</li>
 * <li>상태가 바뀔 때는 우리 곡만 멈추고(gave:music.*) 짧은 신호(북 한 방)로 넘어간다. 접속한 사람은 지금 상태의 곡부터(신호 없이).</li>
 * </ul>
 * 웨이브 곡과 보스 곡은 2026-11-12 사용자 요청("락 느낌의 신나고 템포 넘치는 브금으로 교체해 봐, 싸울 때")으로 락, 메탈(docs/브금출처.md).
 * 바닐라 배경 음악: 황야 바이옴(창백한 정원)의 환경 속성(audio/background_music 비어 있음, audio/music_volume 0)이 26.3 클라 음악을
 * 끈다. 그래도 남는 것(메뉴에서 이어 온 곡 등)은 접속 때와 30초마다 minecraft:music.* 를 멈춘다.
 *
 * <p>설정(config.yml): {@code music}(false 면 아무것도 안 함), {@code musicVolume}(0..1), {@code musicCategory}(기본 VOICE = 클라 설정의
 * '음성/내레이터' 막대로 줄임. 2026-11-12 사용자 요청, MUSIC 이면 '음악' 막대), {@code musicLog}(곡을 틀 때마다 서버 기록),
 * {@code musicActs}(기본 true: 원정이 켜져 있으면 라운드의 막으로 곡 묶음을 고름). 시험 훅 '음악'.
 */
public final class Music {

    /** 곡 하나: 틱 길이, 다음 곡이 겹쳐 들어올 틱(꼬리). */
    public record Track(String key, int ticks, int xfade) {}

    /** cue 하나(music.json 'cues'): 곡 목록(돌려 가며), 되풀이, 저절로 풀리는 때(wave_start, wave_end, lobby), 조용히와 보스 곡보다 앞서는가. */
    public record Cue(List<String> tracks, boolean loop, String until, boolean overHush) {}

    /** 사람 하나의 재생 상태. */
    private static final class Ear {
        String mode = "";
        String track = "";
        String last = "";
        long startAt, nextAt;
        int tier = -1;
        int breakIdx;
        /** 이번 웨이브에 튼 곡 수(같은 웨이브의 사람들이 같은 곡을 듣게 차례를 웨이브 번호에서 셈). */
        int waveTracks;
        boolean breakEnd;
        /** 이 모드에 들어온 때의 판 상태(신호를 칠지: 판 흐름이 바뀌었나). */
        Game.State state;
        /** cue 한 번 틀기를 마쳤나, 보스 앞 드론을 틀었나, 보스 마지막 단계 곡으로 넘어갔나. */
        boolean cuePlayed, prePlayed, finalPlayed;
        /** 겹쳐 넘긴 앞 곡을 멈출 틱(곡 키, 틱). */
        String fadeOut = "";
        long fadeAt;
    }

    private static final String[] VANILLA = {"minecraft:music.game", "minecraft:music.creative", "minecraft:music.menu", "minecraft:music.under_water"};
    private static final String[] TIER_KO = {"낮음", "중간", "높음"};
    private static final String[] ACT_TIER_KO = {"낮음", "높음"};

    private final Game game;
    private final Map<UUID, Ear> ears = new HashMap<>();
    private final Map<String, Track> tracks = new LinkedHashMap<>();
    private final List<List<String>> tiers = new ArrayList<>();
    private final List<String> breaks = new ArrayList<>();
    /** 보스전 곡(music.json 'boss', 없으면 높음 단계 곡)과 시작 신호(music.json 'boss_sting'). */
    private final List<String> bosses = new ArrayList<>();
    private String lobby = "lobby", sting = "sting", over = "over", breakEnd = "break_end", bossSting = "boss_sting";
    /** 원정 네 막(music.json 'acts'): 막 번호 1..4 → [낮음, 높음, 보스] 곡 목록. */
    private final Map<Integer, List<List<String>>> acts = new HashMap<>();
    private final Map<String, Cue> cues = new LinkedHashMap<>();
    private final Map<String, String> cueAlias = new HashMap<>();
    /** 보스 앞 쉬는 시간 드론, 쓰러짐 신호(없으면 ""), 마지막 단계 곡(없으면 보스 곡 그대로). */
    private String bossPre = "", bossDownKey = "";
    private final List<String> bossFinals = new ArrayList<>();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    /** 보스 신호 뒤 보스 곡이 들어올 틱(뿔나팔이 먼저 울리게). */
    private static final int BOSS_DELAY = 36;
    /** 마지막 단계 곡으로 넘길 때 앞 곡과 겹치는 틱. */
    private static final int FINAL_OVERLAP = 30;
    /** 그 판 첫 B5 가 쓰러진 뒤 엔딩 곡이 들어올 틱(docs/모듈/보스.md 10절: 0 멈춤, 40 딸깍, 100 엔딩). */
    private static final int ENDING_DELAY = 100;

    /** 보스전 음악을 켰는가(boss(true)). 웨이브를 벗어나면(BREAK, OVER, LOBBY) 저절로 꺼진다. */
    private boolean bossOn;
    /** 웨이브 중 조용히(보스가 쓰러진 뒤 무너짐 물결, 엔딩: 신호 한 방 뒤 곡 없음). 웨이브를 벗어나면 저절로 풀린다(2026-11-12 boss_infra). */
    private boolean hushed;
    private long bossAt;
    private Game.State lastState;
    /** 보스 마지막 단계 곡을 켰나(보스마다 한 번), 보스 곡을 끈 틱, 다음 조용히의 신호를 쓰러짐 신호로 칠지, 엔딩 곡을 넣을 틱. */
    private boolean finalOn;
    private long bossOffAt = -100;
    private boolean downSignal;
    private long endingAt = -1;

    /** 막: act(int) 로 정한 값(대기실로 가면 0), 시험 훅이 강제한 값(-1 = 자동). 0 이면 원정이 켜져 있을 때 라운드로 셈. */
    private int actSet;
    private int forceAct = -1;
    /** 지금 웨이브가 시작될 때 고른 막(0 = 막을 모름: 옛 세 단계). */
    private int waveAct;

    /** 지금 cue(""= 없음)와 그 시작 틱, 틱 제한(-1 없음), 걸 때의 웨이브, 그 뒤 웨이브를 지났나. */
    private String cueKey = "";
    private long cueAt, cueUntil = -1;
    private int cueWave;
    private boolean cueSawWave;

    public boolean enabled;
    public float volume;
    public SoundCategory category;
    public boolean log;
    /** 설정 musicActs: 원정이 켜져 있으면 라운드의 막으로 곡 묶음을 고른다. */
    public boolean useActs;

    /** 마지막으로 music.json 을 읽은 팩 바이트(PackServer.bytes(), 같은 배열이면 다시 읽지 않음). */
    private byte[] packSeen;
    private boolean packRead;
    private String loadNote = "아직 안 읽음";
    private final Set<String> warned = new HashSet<>();

    // 판 전체 긴장도(사람마다 같음)
    private int waveTier;
    private int liveTier;
    private int waveSeen = -1;
    private double tension, tensionSum, lastWaveTension;
    private int tensionN;
    private String parts = "";
    /** 시험 훅: 단계 강제(-1 = 자동), 긴장도 강제(음수 = 자동). */
    private int forceTier = -1;
    private double forceTension = -1;

    public Music(Game game) {
        this.game = game;
        var c = game.plugin.getConfig();
        enabled = c.getBoolean("music", true);
        volume = (float) Math.max(0, Math.min(1, c.getDouble("musicVolume", 0.7)));
        String cat = c.getString("musicCategory", "VOICE");
        SoundCategory sc;
        try {
            sc = SoundCategory.valueOf(cat.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sc = SoundCategory.VOICE;
        }
        category = sc;
        log = c.getBoolean("musicLog", false);
        useActs = c.getBoolean("musicActs", true);
    }

    // ------------------------------------------------------------------ 팩의 music.json

    /** 내보내는 팩(PackServer.bytes())이 바뀌었으면 music.json 을 다시 읽는다(클라가 받는 바로 그 바이트). */
    private void reload() {
        byte[] b = game.plugin.pack == null ? null : game.plugin.pack.bytes();
        if (packRead && b == packSeen) return;
        packRead = true;
        packSeen = b;
        clearLists();
        if (b == null) {
            loadNote = "팩 없음 " + (game.plugin.pack == null ? "-" : game.plugin.pack.file());
            return;
        }
        try (InputStream in = new java.io.ByteArrayInputStream(b); ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (!e.getName().equals("assets/gave/music.json")) continue;
                JsonObject o = new Gson().fromJson(new String(z.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                for (Map.Entry<String, JsonElement> t : o.getAsJsonObject("tracks").entrySet()) {
                    JsonObject v = t.getValue().getAsJsonObject();
                    tracks.put(t.getKey(), new Track(t.getKey(), v.get("ticks").getAsInt(), v.has("xfade") ? v.get("xfade").getAsInt() : 0));
                }
                JsonObject ti = o.getAsJsonObject("tiers");
                for (String k : new String[] {"low", "mid", "high"}) tiers.add(strings(ti.getAsJsonArray(k)));
                breaks.addAll(strings(o.getAsJsonArray("break")));
                lobby = o.get("lobby").getAsString();
                sting = o.get("sting").getAsString();
                over = o.get("over").getAsString();
                breakEnd = o.get("break_end").getAsString();
                // 보스전(2026-11-12 팩부터, 옛 팩에는 없음: 그때는 높음 단계 곡을 보스 곡으로)
                if (o.has("boss")) bosses.addAll(strings(o.getAsJsonArray("boss")));
                bossSting = o.has("boss_sting") ? o.get("boss_sting").getAsString() : sting;
                // 원정 네 막, cue, 보스 추가(2026-11-12 aud_trip 팩부터. 없으면 옛 방식 그대로)
                if (o.has("acts")) {
                    for (Map.Entry<String, JsonElement> a : o.getAsJsonObject("acts").entrySet()) {
                        JsonObject v = a.getValue().getAsJsonObject();
                        List<List<String>> l = new ArrayList<>();
                        for (String k : new String[] {"low", "high", "boss"}) l.add(v.has(k) ? strings(v.getAsJsonArray(k)) : new ArrayList<>());
                        try {
                            acts.put(Integer.parseInt(a.getKey()), l);
                        } catch (NumberFormatException ignored) {
                            // 막 번호가 아니면 건너뜀
                        }
                    }
                }
                if (o.has("cues")) {
                    for (Map.Entry<String, JsonElement> c : o.getAsJsonObject("cues").entrySet()) {
                        JsonObject v = c.getValue().getAsJsonObject();
                        cues.put(c.getKey(), new Cue(strings(v.getAsJsonArray("tracks")), !v.has("loop") || v.get("loop").getAsBoolean(),
                                v.has("until") ? v.get("until").getAsString() : "lobby", v.has("over_hush") && v.get("over_hush").getAsBoolean()));
                    }
                }
                if (o.has("cue_alias")) for (Map.Entry<String, JsonElement> c : o.getAsJsonObject("cue_alias").entrySet()) cueAlias.put(c.getKey(), c.getValue().getAsString());
                if (o.has("boss_pre")) bossPre = o.get("boss_pre").getAsString();
                if (o.has("boss_down")) bossDownKey = o.get("boss_down").getAsString();
                if (o.has("boss_final")) bossFinals.addAll(strings(o.getAsJsonArray("boss_final")));
                break;
            }
        } catch (Exception ex) {
            clearLists();
            loadNote = "music.json 읽기 실패: " + ex;
            game.plugin.getLogger().warning("[음악] " + loadNote);
            return;
        }
        loadNote = tracks.isEmpty() ? "팩에 music.json 없음(tools/make_bgm.py)"
                : "곡 " + tracks.size() + " 보스 " + bosses.size() + " 막 " + acts.size() + " cue " + cues.size();
        game.plugin.getLogger().info("[음악] " + loadNote + " 소리 종류 " + category + " (팩 sha1 " + game.plugin.pack.sha1() + ")");
    }

    private void clearLists() {
        tracks.clear();
        tiers.clear();
        breaks.clear();
        bosses.clear();
        acts.clear();
        cues.clear();
        cueAlias.clear();
        bossFinals.clear();
        bossPre = "";
        bossDownKey = "";
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    // ------------------------------------------------------------------ 매 틱

    public void tick() {
        long now = game.now();
        if (now % 100 == 0) {
            reload();
            ears.keySet().removeIf(id -> game.plugin.getServer().getPlayer(id) == null);
        }
        // 보스전은 웨이브 안에서만: 웨이브를 벗어나면(쉬는 시간, 전멸, 대기실) 저절로 끔. 웨이브 전에 켠 것은 그 웨이브가 끝날 때까지 둠
        if (bossOn && lastState == Game.State.WAVE && game.state != Game.State.WAVE) {
            bossOn = false;
            game.plugin.getLogger().info("[음악] 보스전 끝(웨이브를 벗어남) w=" + game.wave);
        }
        if (game.state == Game.State.LOBBY) {
            bossOn = false;
            actSet = 0;
            endingAt = -1;
        }
        if (hushed && game.state != Game.State.WAVE) {
            hushed = false;
            downSignal = false;
        }
        if (!bossOn) finalOn = false;
        else if (!finalOn) finalCheck();
        if (endingAt >= 0 && now >= endingAt) {
            endingAt = -1;
            cue("ending");
        }
        cueTick(now);
        lastState = game.state;
        if (!enabled || tracks.isEmpty()) return;
        senseTension(now);
        for (Player p : game.world.getPlayers()) {
            Ear e = ears.get(p.getUniqueId());
            if (e == null) {
                e = new Ear();
                ears.put(p.getUniqueId(), e);
                stopVanilla(p);
            }
            if ((now + (p.getEntityId() & 31)) % 600 == 0) stopVanilla(p);
            tickEar(p, e, now);
        }
    }

    private String wanted() {
        boolean cueOn = !cueKey.isEmpty();
        Cue c = cueOn ? cueOf(cueKey) : null;
        boolean first = c != null && c.overHush();
        return switch (game.state) {
            case LOBBY -> "lobby";
            case PREP -> cueOn ? "cue:" + cueKey : "lobby";
            case WAVE -> game.director.silent() ? "silent"
                    : cueOn && first ? "cue:" + cueKey
                    : hushed ? "silent" : bossOn ? "boss" : cueOn ? "cue:" + cueKey : "wave";
            case BREAK -> cueOn ? "cue:" + cueKey : "break";
            case OVER -> "over";
        };
    }

    private void tickEar(Player p, Ear e, long now) {
        if (!e.fadeOut.isEmpty() && now >= e.fadeAt) {
            if (!e.fadeOut.equals(e.track)) p.stopSound(Props.NS + ":music." + e.fadeOut, category);
            e.fadeOut = "";
        }
        String want = wanted();
        if (!want.equals(e.mode)) {
            boolean joined = e.mode.isEmpty();
            String from = e.mode;
            boolean flow = e.state != null && e.state != game.state; // 판 흐름이 바뀜(쉬는 시간 → 웨이브 등)
            Game.State fromState = e.state;
            stopOurs(p);
            e.mode = want;
            e.state = game.state;
            e.track = "";
            e.fadeOut = "";
            e.nextAt = now;
            e.breakIdx = 0;
            e.breakEnd = false;
            e.cuePlayed = false;
            e.prePlayed = false;
            e.finalPlayed = false;
            if (want.equals("wave")) {
                e.tier = -1;
                e.waveTracks = 0;
            }
            if (want.startsWith("cue:")) e.waveTracks = 0;
            // 판 흐름이 바뀔 때만 신호(접속, 대기실 되풀이, 소리 없는 웨이브에는 없음). 보스전은 뿔나팔 뒤 BOSS_DELAY 틱에 보스 곡
            if (want.equals("boss")) {
                if (!joined) play(p, e, bossSting, now, false);
                e.nextAt = now + (joined ? 0 : BOSS_DELAY);
                e.waveTracks = 0;
            } else if (want.equals("silent") && hushed && !joined && !from.equals("silent")) {
                // 보스가 쓰러짐: 곡을 끊고 쓰러짐 신호(없으면 북 한 방), 그 뒤 물결 동안 조용히
                play(p, e, downSignal && tracks.containsKey(bossDownKey) ? bossDownKey : sting, now, false);
            } else if (!joined && !from.equals("silent")) {
                boolean fromCue = from.startsWith("cue:");
                if (want.equals("wave") && (!fromCue || flow)) play(p, e, sting, now, false);
                else if (want.startsWith("cue:") && flow && game.state == Game.State.WAVE) play(p, e, sting, now, false);
                else if (want.equals("break") && fromState == Game.State.WAVE) play(p, e, sting, now, false);
            }
        }
        switch (e.mode) {
            case "lobby" -> {
                if (now >= e.nextAt) play(p, e, lobby, now, true);
            }
            case "wave" -> {
                if (now >= e.nextAt) {
                    int tier = forceTier >= 0 ? forceTier : (e.tier < 0 ? waveTier : liveTier);
                    e.tier = tier;
                    play(p, e, pickWave(tier, act(), e.last, game.wave + e.waveTracks++), now, true);
                }
            }
            case "boss" -> {
                boolean fin = finalOn && !bossFinals.isEmpty();
                if (fin && !e.finalPlayed && !e.track.isEmpty() && !bossFinals.contains(e.track)) {
                    // 마지막 단계: 지금 곡 위에 마지막 단계 곡을 얹고 FINAL_OVERLAP 틱 뒤 앞 곡을 멈춤
                    e.finalPlayed = true;
                    String old = e.track;
                    play(p, e, pickFrom(bossFinals, "", (int) bossAt), now, true);
                    if (!old.equals(e.track)) {
                        e.fadeOut = old;
                        e.fadeAt = now + FINAL_OVERLAP;
                    }
                } else if (now >= e.nextAt) {
                    if (fin) e.finalPlayed = true;
                    play(p, e, pickFrom(fin ? bossFinals : bossList(act()), e.last, (int) (bossAt + e.waveTracks++)), now, true);
                }
            }
            case "break" -> breakTick(p, e, now);
            case "over" -> {
                if (e.track.isEmpty()) play(p, e, over, now, true);
            }
            default -> {
                if (e.mode.startsWith("cue:") && now >= e.nextAt) cueEarTick(p, e, now);
            }
        }
    }

    /** cue 곡: 목록을 돌려 가며(되풀이), 한 번만이면 끝난 뒤 조용히(cue 가 풀리면 보통으로). */
    private void cueEarTick(Player p, Ear e, long now) {
        Cue c = cueOf(e.mode.substring(4));
        if (c == null || c.tracks().isEmpty()) {
            e.nextAt = now + 200;
            return;
        }
        if (!c.loop() && e.cuePlayed) {
            e.nextAt = Long.MAX_VALUE;
            return;
        }
        e.cuePlayed = true;
        play(p, e, pickFrom(c.tracks(), e.last, (int) (cueAt / 20) + e.waveTracks++), now, true);
    }

    /** 쉬는 시간: 동요 조각을 잇다가 끝 조각(쉬는 시간 끝에 맞춰 끝남). 다음이 보스면 동요 대신 보스 앞 드론 한 번. */
    private void breakTick(Player p, Ear e, long now) {
        if (e.breakEnd) return;
        long endAt = game.stateAt + breakLength() - ticks(breakEnd);
        if (now >= endAt) {
            e.breakEnd = true;
            if (e.track.equals(bossPre) && !bossPre.isEmpty()) p.stopSound(Props.NS + ":music." + bossPre, category);
            play(p, e, breakEnd, now, true);
            return;
        }
        if (now < e.nextAt) return;
        if (!e.prePlayed && preBoss()) {
            e.prePlayed = true;
            play(p, e, bossPre, now, true);
            e.nextAt = Long.MAX_VALUE; // 드론 뒤 끝 조각까지 비움(드론이 길면 끝 조각 때 멈춤)
            return;
        }
        if (breaks.isEmpty()) return;
        String k = breaks.get(e.breakIdx % breaks.size());
        if (now + ticks(k) > endAt + 10) {
            e.nextAt = Long.MAX_VALUE; // 남은 틈은 끝 조각까지 비움
            return;
        }
        e.breakIdx++;
        play(p, e, k, now, true);
    }

    /** 쉬는 시간 길이(원정이 늘이거나 줄인 것까지: Game 이 쓰는 그 값). */
    private int breakLength() {
        int d = game.director.breakTicks();
        try {
            return game.trip == null ? d : game.trip.breakTicks(d);
        } catch (RuntimeException ex) {
            return d;
        }
    }

    /** 다음 웨이브가 보스 웨이브인 쉬는 시간인가(보스가 켜져 있고 드론 곡이 팩에 있을 때). */
    private boolean preBoss() {
        if (bossPre.isEmpty() || !tracks.containsKey(bossPre) || game.state != Game.State.BREAK) return false;
        BossDirector bd = game.director.bosses;
        return bd != null && bd.enabled && BossDirector.isBossWave(game.wave + 1);
    }

    private int ticks(String k) {
        Track t = tracks.get(k);
        return t == null ? 0 : t.ticks;
    }

    /** 웨이브 곡: 막을 알면 그 막의 낮음, 높음(단계 0, 1 이상), 아니면 옛 세 단계. 차례 = 웨이브 번호 + 이번 웨이브에 튼 곡 수(바로 앞 곡은 피함). */
    private String pickWave(int tier, int a, String last, int turn) {
        if (actMode(a)) {
            List<List<String>> l = acts.get(a);
            List<String> lst = l.get(Math.max(0, Math.min(1, tier)));
            if (lst.isEmpty()) lst = l.get(lst == l.get(0) ? 1 : 0);
            if (!lst.isEmpty()) return pickFrom(lst, last, turn);
        }
        return pickFrom(tier >= 0 && tier < tiers.size() ? tiers.get(tier) : List.of(), last, turn);
    }

    /** 보스 곡 목록: 막을 알면 그 막의 보스 곡, 아니면 music.json 'boss', 그것도 없으면 높음 단계. */
    private List<String> bossList(int a) {
        if (actMode(a) && !acts.get(a).get(2).isEmpty()) return acts.get(a).get(2);
        return bosses.isEmpty() && tiers.size() > 2 ? tiers.get(2) : bosses;
    }

    private static String pickFrom(List<String> l, String last, int turn) {
        if (l.isEmpty()) return "";
        for (int i = 0; i < l.size(); i++) {
            String k = l.get(Math.floorMod(turn + i, l.size()));
            if (!k.equals(last) || l.size() == 1) return k;
        }
        return l.get(0);
    }

    /** 곡 하나를 p 에게(몸에 붙여, 자리 없이). main 이면 그 사람의 지금 곡으로 두고 다음 곡 틱을 정한다. */
    private void play(Player p, Ear e, String key, long now, boolean main) {
        Track t = tracks.get(key);
        String ev = "music." + key;
        if (t == null || !Props.hasSound(ev)) {
            if (warned.add(key)) game.plugin.getLogger().warning("[음악] 팩에 " + Props.NS + ":" + ev + " 가 없어 건너뜀");
            if (main) e.nextAt = now + 200;
            return;
        }
        float vol = volume;
        PState s = game.players.get(p);
        if (s != null && (s.life == Life.DEAD || s.life == Life.TRAPPED)) vol *= 0.8f; // 지켜보는 동안은 조금 작게
        p.playSound(p, Props.NS + ":" + ev, category, vol, 1f);
        if (main) {
            e.track = key;
            e.last = key;
            e.startAt = now;
            e.nextAt = now + Math.max(20, t.ticks - t.xfade);
        }
        String tierTxt = e.mode.equals("wave") && e.tier >= 0 ? tierName(e.tier, act()) : e.mode.equals("boss") ? (finalOn ? "보스마지막" : "보스")
                : e.mode.startsWith("cue:") ? e.mode : "-";
        String line = String.format(Locale.ROOT, "t=%d %s %s %s w=%d act=%d tier=%s ten=%.2f", now, p.getName(), e.mode, key, game.wave, act(), tierTxt, tension);
        history.addLast(line);
        while (history.size() > 24) history.removeFirst();
        if (log) game.plugin.getLogger().info("[음악] " + line);
    }

    private void stopOurs(Player p) {
        for (String k : tracks.keySet()) p.stopSound(Props.NS + ":music." + k, category);
    }

    private static void stopVanilla(Player p) {
        for (String v : VANILLA) p.stopSound(v, SoundCategory.MUSIC);
    }

    private String tierName(int tier, int a) {
        return actMode(a) ? ACT_TIER_KO[Math.max(0, Math.min(1, tier))] : TIER_KO[Math.max(0, Math.min(2, tier))];
    }

    // ------------------------------------------------------------------ 긴장도

    /**
     * 1초마다 판 긴장도 0..1: 살아 있는 몰려오는 수(감독 동시 수 목표 aliveGoal 이면 가득) 0.35, 중앙 원 테두리 16블록 안에 온 수(8 이면 가득) 0.35,
     * 쓰러지거나 죽은 사람 비율 0.3. 웨이브가 시작될 때 단계를 고른다: 막을 알면 막 앞쪽 낮음, 막 뒤쪽 높음, 막을 모르면 깊이 단계(1~10 낮음,
     * 11~25 중간, 26~ 높음). 직전 웨이브 평균 긴장도가 0.55 를 넘으면 한 단계 더한다. 웨이브 안에서는 곡이 끝날 때만 바뀐다: 긴장도(8초 평균)가
     * 0.7 을 넘으면 한 단계 위, 0.3 아래면 시작 단계로.
     */
    private void senseTension(long now) {
        if (game.state == Game.State.WAVE && game.wave != waveSeen) {
            lastWaveTension = tensionN > 0 ? tensionSum / tensionN : 0;
            waveSeen = game.wave;
            tensionSum = 0;
            tensionN = 0;
            waveAct = act();
            int base;
            String baseTxt;
            if (actMode(waveAct)) {
                int len = waveAct >= 4 ? 5 : 10;
                int r = game.wave - (waveAct - 1) * 10;
                base = r > len * 0.6 ? 1 : 0;
                waveTier = Math.min(1, base + (lastWaveTension > 0.55 ? 1 : 0));
                baseTxt = "막 " + waveAct + " 안 " + r + "/" + len + " " + ACT_TIER_KO[base];
            } else {
                base = game.wave <= 10 ? 0 : game.wave <= 25 ? 1 : 2;
                waveTier = Math.min(2, base + (lastWaveTension > 0.55 ? 1 : 0));
                baseTxt = "깊이 " + TIER_KO[base];
            }
            liveTier = waveTier;
            game.plugin.getLogger().info(String.format(Locale.ROOT, "[음악] 웨이브 %d 막 %d 단계 %s (%s, 직전 긴장도 %.2f)", game.wave, waveAct,
                    tierName(waveTier, waveAct), baseTxt, lastWaveTension));
        }
        if (game.state == Game.State.LOBBY && waveSeen != -1) {
            // 새 판은 처음부터(직전 판의 긴장도를 끌고 가지 않음)
            waveSeen = -1;
            tension = tensionSum = lastWaveTension = 0;
            tensionN = 0;
            waveTier = liveTier = 0;
        }
        if (game.state != Game.State.WAVE || now % 20 != 0) return;
        double t = forceTension >= 0 ? forceTension : rawTension();
        tension = tension * 0.88 + t * 0.12; // 8초쯤 평균
        tensionSum += t;
        tensionN++;
        int up = Math.min(actMode(waveAct) ? 1 : 2, waveTier + 1);
        if (tension > 0.7) liveTier = up;
        else if (tension < 0.3) liveTier = waveTier;
    }

    private double rawTension() {
        Director d = game.director;
        double goal = Math.max(1, d.aliveGoal());
        // 2026-11-12(bal12 요청, boss_infra): 떼 몫은 동시 수 목표 대비. 옛 '60 이면 가득' 은 30명 판(목표 300)에서 늘 가득이라 긴장도가 웨이브 내내 높았다
        double crowd = Math.min(1, d.aliveNow() / goal);
        int near = 0;
        double r = Plaza.R_SAFE + 16;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.sp.spawn == Species.Spawn.PLACED || d.inCage(g)) continue;
            if (Plaza.radius(g.pos.getX(), g.pos.getZ()) <= r) near++;
        }
        int in = 0, down = 0;
        for (PState s : game.players.all()) {
            if (s.life == Life.LOBBY || s.life == Life.WAITING) continue;
            in++;
            if (s.life != Life.ALIVE) down++;
        }
        double downs = in == 0 ? 0 : (double) down / in;
        parts = String.format(Locale.ROOT, "crowd=%.2f(%d/%.0f) near=%d downs=%d/%d", crowd, d.aliveNow(), goal, near, down, in);
        return 0.35 * crowd + 0.35 * Math.min(1, near / 8.0) + 0.3 * downs;
    }

    /** 지금 긴장도(0..1, 8초 평균). */
    public double tension() {
        return tension;
    }

    /** 지금 웨이브 단계(막을 알면 0 낮음, 1 높음. 모르면 0 낮음, 1 중간, 2 높음): 곡이 끝날 때 쓸 단계. */
    public int tier() {
        return forceTier >= 0 ? forceTier : liveTier;
    }

    /** p 가 지금 듣는 곡 이름(make_bgm 의 키, 없으면 ""). */
    public String current(Player p) {
        Ear e = ears.get(p.getUniqueId());
        return e == null ? "" : e.track;
    }

    // ------------------------------------------------------------------ 막(원정)

    /**
     * 원정의 막을 알림(1 마른 길, 2 젖은 벌, 3 재의 땅, 4 발사장, 0 = 모름). 다음 곡부터(웨이브가 시작될 때나 곡이 끝날 때) 그 막의 곡 묶음을
     * 쓴다. 부르지 않아도 원정이 켜져 있으면(game.trip.enabled, 설정 musicActs) 원정이 답하는 막(Trip.act(), 라운드로 셈)을 쓴다. 대기실로
     * 가면 0 으로 돌아간다. 막을 모르거나 팩에 막 곡이 없으면 옛 세 단계(깊이) 그대로.
     */
    public void act(int a) {
        int v = Math.max(0, Math.min(4, a));
        if (v == actSet) return;
        actSet = v;
        game.plugin.getLogger().info("[음악] 막 " + v + " w=" + game.wave + " state=" + game.state);
    }

    /** 곡 묶음을 고를 때 쓰는 막(0 = 모름). 시험 훅 강제 > act(int) > 원정 라운드. */
    public int act() {
        if (forceAct >= 0) return forceAct;
        if (actSet > 0) return actSet;
        if (!useActs || game.trip == null || !game.trip.enabled) return 0;
        try {
            return game.trip.act();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private boolean actMode(int a) {
        return a > 0 && acts.containsKey(a);
    }

    // ------------------------------------------------------------------ cue(재미 장치, 원정)

    /**
     * 웨이브 곡 대신 cue 곡(gave:music.&lt;곡&gt;, music.json 'cues' 의 키: travel, chase, laststand(= last_stand), credits, ending. 'cues' 에
     * 없는 키는 같은 이름의 곡 하나를 되풀이). 차례: OVER &gt; 소리 없음 &gt; (조용히 &gt; 보스 곡) &gt; cue &gt; 보통(credits, ending 은 조용히와
     * 보스 곡보다 앞섬). ticks &gt; 0 이면 그 틱 뒤 저절로 풀림, 아니면 cue 마다 정한 때(travel 다음 웨이브 시작, chase 와 laststand 웨이브 끝,
     * credits 와 ending 대기실, ending 은 한 번 틀고 끝)에 풀림. 같은 키로 다시 부르면 틱 제한만 고침. 다른 키를 부르면 바꿔 듦.
     */
    public void cue(String key, int ticks) {
        if (key == null || key.isEmpty()) {
            uncue();
            return;
        }
        String k = cueAlias.getOrDefault(key, key);
        long now = game.now();
        if (k.equals(cueKey)) {
            cueUntil = ticks > 0 ? now + ticks : -1;
            return;
        }
        if (!tracks.isEmpty() && cueOf(k) == null) {
            if (warned.add("cue:" + k)) game.plugin.getLogger().warning("[음악] cue " + key + " 곡이 팩에 없어 무시");
            return;
        }
        cueKey = k;
        cueAt = now;
        cueUntil = ticks > 0 ? now + ticks : -1;
        cueWave = game.wave;
        cueSawWave = game.state == Game.State.WAVE;
        game.plugin.getLogger().info("[음악] cue " + k + (ticks > 0 ? " " + ticks + "틱" : "") + " w=" + game.wave + " state=" + game.state);
    }

    /** cue(key, 틱 제한 없음): 원정의 cue("travel"), cue("chase"), 재미의 cue("last_stand"). */
    public void cue(String key) {
        cue(key, -1);
    }

    /** cue 를 풀고 보통 곡으로(웨이브 중이면 신호 없이 그 단계 곡, 쉬는 시간이면 쉬는 시간 곡). */
    public void uncue() {
        if (cueKey.isEmpty()) return;
        game.plugin.getLogger().info("[음악] cue 끝 " + cueKey + "(uncue) w=" + game.wave + " state=" + game.state);
        cueKey = "";
    }

    /** 지금 cue 키(없으면 ""). */
    public String cue() {
        return cueKey;
    }

    /** 엔딩 새벽 곡 한 번(= cue("ending")). 그 판 첫 B5 가 쓰러지면 저절로 부른다(쓰러진 뒤 ENDING_DELAY 틱). */
    public void ending() {
        endingAt = -1;
        cue("ending");
    }

    private Cue cueOf(String k) {
        Cue c = cues.get(k);
        if (c != null) return c;
        if (tracks.containsKey(k)) return new Cue(List.of(k), true, "lobby", false);
        return null;
    }

    /** cue 가 저절로 풀리는 때. */
    private void cueTick(long now) {
        if (cueKey.isEmpty()) return;
        Cue c = cueOf(cueKey);
        if (game.state == Game.State.WAVE) cueSawWave = true;
        String why = null;
        if (game.state == Game.State.LOBBY) why = "대기실";
        else if (game.state == Game.State.OVER) why = "전멸";
        else if (cueUntil >= 0 && now >= cueUntil) why = "시간";
        else if (c != null) {
            if (c.until().equals("wave_start") && game.state == Game.State.WAVE && game.wave != cueWave) why = "웨이브 시작";
            else if (c.until().equals("wave_end") && cueSawWave && game.state != Game.State.WAVE) why = "웨이브 끝";
            else if (!c.loop() && !c.tracks().isEmpty() && now >= cueAt + Math.max(20, ticks(c.tracks().get(0)))) why = "한 번 끝";
        }
        if (why == null) return;
        game.plugin.getLogger().info("[음악] cue 끝 " + cueKey + "(" + why + ") w=" + game.wave + " state=" + game.state);
        cueKey = "";
    }

    // ------------------------------------------------------------------ 보스

    /**
     * 보스전 음악 켜고 끄기(보스 담당이 보스가 나올 때 true, 보스가 죽거나 사라질 때 false). 켜면 모두의 웨이브 곡을 멈추고 뿔나팔 신호
     * (music.boss_sting) 뒤 BOSS_DELAY 틱에 보스 곡(막을 알면 그 막의 보스 곡, 아니면 music.json 'boss')을 돌려 가며 튼다. 끄면 북 한 방
     * (music.sting) 뒤 웨이브 곡으로 돌아온다(바로 뒤 hush(true) 면 쓰러짐 신호 뒤 조용히). 웨이브 밖(쉬는 시간, 대기실)에 켜 두면 다음 웨이브가
     * 시작될 때 보스 곡으로 들어가고, 웨이브를 벗어나면 저절로 꺼진다. 같은 값으로 다시 불러도 아무 일 없음. 소리 없는 웨이브에서는 끊긴다.
     */
    public void boss(boolean on) {
        if (on == bossOn) return;
        bossOn = on;
        finalOn = false;
        if (on) {
            bossAt = game.now();
            downSignal = false;
        } else {
            bossOffAt = game.now();
            endingCheck();
        }
        game.plugin.getLogger().info("[음악] 보스전 " + (on ? "시작" : "끝") + " w=" + game.wave + " state=" + game.state
                + (endingAt >= 0 ? " 엔딩 곡 " + (endingAt - game.now()) + "틱 뒤" : ""));
    }

    /**
     * 보스 곡을 끌 때 그 보스가 쓰러진 그 판 첫 B5(회차 1, 엔딩을 아직 안 봄)면 ENDING_DELAY 틱 뒤 엔딩 곡. 쓰러짐은 체력 0 으로 가른다(B5 는
     * 등장 때도 조용히를 켜므로 조용히로는 가르지 않음, 2026-11-12 게임 안 시험에서 고침).
     */
    private void endingCheck() {
        if (game.state != Game.State.WAVE || cueOf("ending") == null) return;
        BossDirector bd = game.director.bosses;
        Boss b = bd == null ? null : bd.boss();
        if (b != null && b.sp == Species.B5 && b.hp <= 0 && !bd.endingSeen() && BossDirector.cycleOf(game.wave) == 1) endingAt = game.now() + ENDING_DELAY;
    }

    /** 보스전 음악이 켜져 있는가. */
    public boolean boss() {
        return bossOn;
    }

    /**
     * 보스 마지막 단계(on): 보스 곡 위에 boss_final 을 얹고 1.5초 뒤 앞 곡을 멈춘다(웨이브 도중 곡을 바꾸지 않는다는 규칙의 예외). 보스전이
     * 켜져 있고 지금 보스가 단계 둘 이상에 마지막 단계면 저절로 부른다. 보스전이 꺼지면 함께 꺼진다.
     */
    public void bossFinal(boolean on) {
        if (on == finalOn || (on && !bossOn)) return;
        finalOn = on;
        game.plugin.getLogger().info("[음악] 보스 마지막 단계 " + (on ? "켬" : "끔") + " w=" + game.wave);
    }

    private void finalCheck() {
        BossDirector bd = game.director.bosses;
        Boss b = bd == null ? null : bd.boss();
        if (b != null && !b.dead && b.phases() > 1 && b.lastPhase()) bossFinal(true);
    }

    /** 보스가 쓰러짐: 보스 곡을 끊고 쓰러짐 신호(music.boss_down) 뒤 조용히(= boss(false) 바로 뒤 hush(true)). */
    public void bossDown() {
        boss(false);
        hush(true);
        downSignal = true;
    }

    /**
     * 웨이브 중 조용히(on): 지금 곡을 끊고 신호 한 방(보스 곡을 막 끈 틱이면 쓰러짐 신호 music.boss_down, 아니면 북) 뒤 곡 없음. 보스가 쓰러진
     * 뒤 무너짐 물결과 엔딩 동안(보스 담당이 부름). 웨이브를 벗어나면(쉬는 시간) 저절로 풀려 쉬는 시간 곡이 나온다. 엔딩 곡(그 판 첫 B5 가
     * 쓰러지면 boss(false) 가 넣음)은 조용히보다 앞선다. 같은 값으로 다시 불러도 아무 일 없음.
     */
    public void hush(boolean on) {
        if (on == hushed) return;
        if (on && game.state != Game.State.WAVE) return;
        hushed = on;
        long now = game.now();
        downSignal = on && now - bossOffAt <= 2;
        game.plugin.getLogger().info("[음악] 조용히 " + (on ? "켬" : "끔") + " w=" + game.wave + " state=" + game.state
                + (on && downSignal ? " 쓰러짐 신호" : "") + (endingAt >= 0 ? " 엔딩 곡 " + (endingAt - now) + "틱 뒤" : ""));
    }

    public boolean hushed() {
        return hushed;
    }

    // ------------------------------------------------------------------ 시험 훅

    /**
     * /gave 시험 음악 &lt;이름|x&gt; [보기 | 곡 &lt;키&gt; | 단계 &lt;낮음|중간|높음|자동&gt; | 긴장 &lt;0..1|자동&gt; | 보스 [on|off] | 마지막 |
     * 쓰러짐 | 조용히 [on|off] | 막 &lt;0..4|자동&gt; | cue &lt;키&gt; [틱] | uncue | 엔딩 | 기록 [on|off] | 끔 | 켬 | 다시].
     * 보기: 설정, 팩, 막, 단계, 긴장도, cue, 그 사람의 곡과 다음 곡까지 틱, 최근 튼 곡.
     */
    public String test(Player p, String[] a) {
        if (a.length < 2 || !a[1].equals("음악")) return null;
        String sub = a.length > 3 ? a[3] : "보기";
        Player who = a.length > 2 ? game.plugin.getServer().getPlayerExact(a[2]) : null;
        if (who == null) who = p;
        long now = game.now();
        switch (sub) {
            case "곡" -> {
                if (who == null || a.length < 5) return "[음악] 곡 <키>: " + String.join(",", tracks.keySet());
                Ear e = ears.computeIfAbsent(who.getUniqueId(), k -> new Ear());
                stopOurs(who);
                play(who, e, a[4], now, true);
                return "[음악] 강제 " + a[4] + " → " + who.getName() + " ticks=" + ticks(a[4]);
            }
            case "단계" -> {
                String v = a.length > 4 ? a[4] : "자동";
                forceTier = switch (v) {
                    case "낮음", "0" -> 0;
                    case "중간", "1" -> 1;
                    case "높음", "2" -> 2;
                    default -> -1;
                };
                if (forceTier == 2 && actMode(act())) forceTier = 1; // 막 곡은 낮음, 높음 둘
                return "[음악] 단계 " + (forceTier < 0 ? "자동" : tierName(forceTier, act()));
            }
            case "긴장" -> {
                forceTension = a.length > 4 && !a[4].equals("자동") ? Double.parseDouble(a[4]) : -1;
                return "[음악] 긴장 " + (forceTension < 0 ? "자동" : forceTension);
            }
            case "기록" -> {
                log = a.length > 4 ? a[4].equals("on") : !log;
                return "[음악] 기록 " + log;
            }
            case "보스" -> {
                boss(a.length > 4 ? a[4].equals("on") || a[4].equals("켬") : !bossOn);
                return "[음악] 보스전 " + (bossOn ? "켬" : "끔") + " 막 " + act() + " 곡 " + String.join(",", bossList(act())) + " 신호 " + bossSting;
            }
            case "마지막" -> {
                bossFinal(true);
                return "[음악] 보스 마지막 단계 " + finalOn + " 곡 " + String.join(",", bossFinals);
            }
            case "쓰러짐" -> {
                bossDown();
                return "[음악] 보스 쓰러짐 boss=" + bossOn + " hush=" + hushed + " 신호 " + (downSignal ? bossDownKey : sting);
            }
            case "조용히" -> {
                hush(a.length > 4 ? a[4].equals("on") || a[4].equals("켬") : !hushed);
                return "[음악] 조용히 " + hushed;
            }
            case "막" -> {
                String v = a.length > 4 ? a[4] : "자동";
                forceAct = v.equals("자동") ? -1 : Math.max(0, Math.min(4, Integer.parseInt(v)));
                return "[음악] 막 " + (forceAct < 0 ? "자동(" + act() + ")" : forceAct) + " 묶음 " + (actMode(act()) ? acts.get(act()) : "옛 단계 " + tiers);
            }
            case "cue", "큐" -> {
                if (a.length < 5) return "[음악] cue <키> [틱]: " + String.join(",", cues.keySet()) + " 별칭 " + cueAlias;
                cue(a[4], a.length > 5 ? Integer.parseInt(a[5]) : -1);
                return "[음악] cue " + cueKey + (cueUntil >= 0 ? " " + (cueUntil - now) + "틱" : "");
            }
            case "uncue", "큐끝" -> {
                uncue();
                return "[음악] cue 끝";
            }
            case "엔딩" -> {
                ending();
                return "[음악] 엔딩 cue " + cueKey;
            }
            case "끔", "켬" -> {
                enabled = sub.equals("켬");
                if (!enabled) for (Player q : game.world.getPlayers()) stopOurs(q);
                ears.clear();
                return "[음악] " + (enabled ? "켬" : "끔");
            }
            case "다시" -> {
                packRead = false;
                reload();
                ears.clear();
                return "[음악] 다시 읽음: " + loadNote;
            }
            default -> {
                Ear e = who == null ? null : ears.get(who.getUniqueId());
                StringBuilder sb = new StringBuilder("[음악] on=" + enabled + " vol=" + volume + " cat=" + category + " pack=" + loadNote
                        + " state=" + game.state + " want=" + wanted() + " wave=" + game.wave
                        + String.format(Locale.ROOT, " tension=%.2f raw=%.2f last=%.2f", tension, game.state == Game.State.WAVE ? rawTension() : 0, lastWaveTension) + " [" + parts + "]"
                        + " act=" + act() + (forceAct >= 0 ? "(강제)" : actSet > 0 ? "(정함)" : "") + " waveAct=" + waveAct
                        + " tier=" + tierName(tier(), act()) + " waveTier=" + tierName(waveTier, waveAct) + (forceTier >= 0 ? "(강제)" : "")
                        + " boss=" + bossOn + " final=" + finalOn + " hush=" + hushed + " pre=" + preBoss()
                        + " cue=" + (cueKey.isEmpty() ? "-" : cueKey + (cueUntil >= 0 ? "(" + (cueUntil - now) + ")" : ""))
                        + (endingAt >= 0 ? " ending_in=" + (endingAt - now) : ""));
                if (e != null) sb.append(" | ").append(who.getName()).append(" mode=").append(e.mode).append(" track=").append(e.track)
                        .append(" playing=").append(now - e.startAt).append(" next=").append(e.nextAt == Long.MAX_VALUE ? -1 : e.nextAt - now);
                sb.append(" | 최근 ").append(String.join(" ; ", history.stream().skip(Math.max(0, history.size() - 6)).toList()));
                return sb.toString();
            }
        }
    }
}
