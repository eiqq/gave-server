package kr.gave.dex;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import kr.gave.Gave;
import kr.gave.game.Game;
import kr.gave.hud.HudSpec;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.Players;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 게임 안 도감(26.3 다이얼로그). 한 번 만난 종만 열람한다.
 *
 * <p>여는 길: 누구나 쓰는 명령 {@code /도감 [갈래|종]}, 빠른 행동 단축키(기본 G)와 일시정지 화면 단추(데이터팩 gave_dex: 이 플러그인이 jar 안
 * {@code dexpack/} 을 월드 datapacks 에 깔고, 서버를 다음에 켤 때부터 먹음). 단축키 다이얼로그의 '열기' 는 맞춤 누름
 * {@code gave:dex/open} 을 보내고 여기서 도감을 연다.
 *
 * <p>첫 화면: 갈래 단추 여섯(모든 막 공통 55, 1~4막 전용, 보스, 단추마다 '만남 n/전체', 고른 갈래는 금빛) 아래 그 갈래 종 단추. 만난 종은 이름(마우스를
 * 올리면 한 줄), 안 만난 종은 '???'(누름 없음). 종 화면: HUD 아이콘, 막과 역할, 한 줄, 생김새, 공격, 신호, 대처(data/bestiary.json 을
 * tools/gen_dex.py 가 jar 안 dex/species.json 으로), 단추 [앞 만난 종] [뒤로] [다음 만난 종].
 *
 * <p>만남(사람마다, 판을 넘어 plugins/Gave/dex.yml 에 남음): 판에 든 사람의 둘레 {@link #NEAR} 블록(수평) 안에 그 종이 나타남(HUD 처음 보는 종
 * 안내처럼 흉내 중, 안 맞는 중, 이름 지우기 변이 중, 대기실 우리 개체는 빼고), 그 종에게 맞음(Players 피해 갈고리), 그 종을 맞힘(Mobs 피해 배율 훅,
 * 배율은 늘 1). 보스는 나오면 판에 든 모두가 만남. 저장은 바뀐 것이 있을 때 비동기로(웨이브 중 60초, 그 밖 5초에 한 번) 그리고 끌 때.
 *
 * <p>시험 훅(Gave 가 Game.test 다음에 넘김): {@code /gave 시험 도감 <이름> [all | clear | <종...> | 열기 [갈래] | 종 <종> | 누름 <id> | 저장 | 상태]}.
 */
public final class Dex implements Listener, CommandExecutor, TabCompleter {

    /** 둘레 반지름(블록, 수평): 이 안에 나타난 종을 만남으로 친다(사람이 갈 수 있는 끝 Plaza.R_ROAM 과 같은 크기). */
    public static final double NEAR = 48;
    /** 둘레 훑기 주기(틱) */
    private static final int SCAN = 10;
    private static final String NS = "gave";
    private static final String HOOK = "dex";
    private static final String PACK = "gave_dex";
    private static final String[] PACK_FILES = {"pack.mcmeta", "data/gave/dialog/dex.json", "data/minecraft/tags/dialog/quick_actions.json",
        "data/minecraft/tags/dialog/pause_screen_additions.json"};
    /** 갈래 차례: 모든 막 공통(G01~G55), 1~4막 전용, 보스 */
    static final String[] GROUPS = {"common", "1", "2", "3", "4", "boss"};
    /** 단추 폭과 열 수(1280x720 자동 GUI 배율 3 = 426 GUI 폭 안에 들게), 본문 폭 */
    private static final int W = 130, COLS = 3, BODY_W = 300, NAV_W = 100;

    private static final TextColor GOLD = TextColor.color(0xFFC53D), WHITE = TextColor.color(0xF0F0F0), GRAY = TextColor.color(0x9A9A9A),
        DARK = TextColor.color(0x5A5A5A), AQUA = TextColor.color(0x7FD8FF), PALE = TextColor.color(0xFFE6A6);

    /** 종 한 칸(jar 안 dex/species.json) */
    record Entry(Species sp, String group, int act, String name, String role, String oneliner, String look, String attack, String tell,
        String counter) {
    }

    private final Gave plugin;
    private final Game game;
    private final DexStore store;
    private final Map<Species, Entry> bySp = new EnumMap<>(Species.class);
    private final Map<String, List<Entry>> groups = new LinkedHashMap<>();
    private final Map<UUID, String> lastGroup = new HashMap<>();
    private final Map<UUID, String> lastShown = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Players.HurtHook hurtHook = (p, dealt, src, cause) -> {
        if (src != null) meet(p, src.sp);
    };
    private BukkitTask task;
    private long ticks;
    private String packNote = "";
    /** 이번 서버에서 새로 적은 만남 수 */
    private int met;
    private boolean scanErr;

    public Dex(Gave plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
        this.store = new DexStore(new File(plugin.getDataFolder(), "dex.yml"), plugin.getLogger());
        for (String g : GROUPS) groups.put(g, new ArrayList<>());
        loadEntries();
        store.load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        PluginCommand c = plugin.getCommand("도감");
        if (c != null) {
            c.setExecutor(this);
            c.setTabCompleter(this);
        } else {
            plugin.getLogger().warning("[도감] plugin.yml 에 /도감 명령이 없다");
        }
        // 맞힘: 피해 배율 훅(배율은 늘 1, 판이 바뀌어도 남음). 맞음: 피해 갈고리(판이 바뀌면 지워지니 훑을 때마다 다시 건다)
        game.mobs.scale(HOOK, (Mobs.DamageScale) (g, d) -> {
            try {
                if (g != null && d != null && d.by() != null) meet(d.by(), g.sp);
            } catch (RuntimeException ignored) {
                // 기록 실패가 피해를 막지 않게
            }
            return 1;
        });
        game.players.addHurtHook(HOOK, hurtHook);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, SCAN, SCAN);
        installPack();
        int n = 0;
        for (List<Entry> l : groups.values()) n += l.size();
        plugin.getLogger().info("[도감] 종 " + n + "개, 단축키 " + packNote);
    }

    /** 끌 때: 훅을 떼고 기록을 바로 쓴다. */
    public void stop() {
        if (task != null) task.cancel();
        game.mobs.unscale(HOOK);
        game.players.removeHurtHook(HOOK);
        store.saveNow();
    }

    // ------------------------------------------------------------------ 자료

    private void loadEntries() {
        try (InputStream in = plugin.getResource("dex/species.json")) {
            if (in == null) {
                plugin.getLogger().warning("[도감] jar 안 dex/species.json 이 없다(tools/gen_dex.py)");
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("species")) {
                JsonObject o = el.getAsJsonObject();
                Species sp = Species.of(str(o, "id"));
                String g = str(o, "group");
                if (sp == null || !groups.containsKey(g)) continue;
                Entry e = new Entry(sp, g, o.has("act") ? o.get("act").getAsInt() : 0, str(o, "name"), str(o, "role"), str(o, "oneliner"), str(o, "look"),
                    str(o, "attack"), str(o, "tell"), str(o, "counter"));
                bySp.put(sp, e);
                groups.get(g).add(e);
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "[도감] dex/species.json 읽기 실패", e);
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        // 가운뎃점 금지: 자료에 섞여 들어와도 쉼표로
        return e == null || e.isJsonNull() ? "" : e.getAsString().replace("\u00b7", ", ");
    }

    // ------------------------------------------------------------------ 만남

    /** 그 사람이 그 종을 만남(처음이면 true). */
    public boolean meet(Player p, Species sp) {
        if (p == null || sp == null || !bySp.containsKey(sp)) return false;
        if (!store.add(p.getUniqueId(), p.getName(), sp)) return false;
        met++;
        return true;
    }

    public boolean seen(Player p, Species sp) {
        return p != null && store.has(p.getUniqueId(), sp);
    }

    private void tick() {
        ticks += SCAN;
        try {
            scan();
        } catch (RuntimeException e) {
            if (!scanErr) plugin.getLogger().log(Level.WARNING, "[도감] 훑기 오류(한 번만 적음)", e);
            scanErr = true;
        }
        // 저장: 바뀐 것이 있을 때만, 웨이브 중이면 60초, 그 밖(쉬는 시간, 대기실)이면 5초에 한 번 몰아서
        long gap = game.state == Game.State.WAVE ? 1200 : 100;
        if (store.dirty() && ticks - store.savedAt >= gap) store.saveAsync(r -> Bukkit.getScheduler().runTaskAsynchronously(plugin, r), ticks);
    }

    /** 판에 든 사람 둘레에 나타난 종(보스는 나오면 모두). */
    private void scan() {
        game.players.addHurtHook(HOOK, hurtHook);
        List<Player> ps = game.players.inPlay();
        if (ps.isEmpty()) return;
        int n = ps.size();
        double[] xs = new double[n], zs = new double[n];
        UUID[] ids = new UUID[n];
        boolean[] here = new boolean[n];
        for (int i = 0; i < n; i++) {
            Player p = ps.get(i);
            Location l = p.getLocation();
            here[i] = l.getWorld() == game.world && game.inGame(p);
            xs[i] = l.getX();
            zs[i] = l.getZ();
            ids[i] = p.getUniqueId();
        }
        boolean hide = game.director.hideNames();
        double r2 = NEAR * NEAR;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.removed || g.sp == null) continue;
            if (g.sp.is(Role.BOSS)) {
                for (int i = 0; i < n; i++) if (here[i] && !store.has(ids[i], g.sp)) meet(ps.get(i), g.sp);
                continue;
            }
            if (hide || g.disguised || g.untargetable || game.director.inCage(g)) continue;
            for (int i = 0; i < n; i++) {
                if (!here[i] || store.has(ids[i], g.sp)) continue;
                double dx = g.pos.getX() - xs[i], dz = g.pos.getZ() - zs[i];
                if (dx * dx + dz * dz <= r2) meet(ps.get(i), g.sp);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastGroup.remove(id);
        lastShown.remove(id);
        lastClick.remove(id);
    }

    // ------------------------------------------------------------------ 화면

    private static Component t(String s, TextColor c) {
        return Component.text(s, c);
    }

    private static Key click(String path) {
        return Key.key(NS, "dex/" + path);
    }

    private static String groupTab(String g) {
        return switch (g) {
            case "common" -> "공통";
            case "boss" -> "보스";
            default -> g + "막";
        };
    }

    private static String groupLong(Entry e) {
        return switch (e.group) {
            case "common" -> "모든 막 공통";
            case "boss" -> e.act > 0 ? e.act + "막 보스" : "보스";
            default -> e.group + "막 전용";
        };
    }

    /** 사람이 친 갈래 이름(공통, 1막, 1, 보스 ...)을 갈래 키로. 모르면 null. */
    static String group(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "공통", "common", "모든막", "모든 막" -> "common";
            case "1막", "1" -> "1";
            case "2막", "2" -> "2";
            case "3막", "3" -> "3";
            case "4막", "4" -> "4";
            case "보스", "boss" -> "boss";
            default -> null;
        };
    }

    private int count(List<Entry> es, Set<Species> seen) {
        int n = 0;
        for (Entry e : es) if (seen.contains(e.sp)) n++;
        return n;
    }

    private static DialogBase base(Component title, List<DialogBody> body) {
        return DialogBase.builder(title).externalTitle(t("게이브 도감", WHITE)).canCloseWithEscape(true).pause(false)
            .afterAction(DialogBase.DialogAfterAction.NONE).body(body).build();
    }

    /** 첫 화면: 갈래 단추 여섯 + 그 갈래 종 단추. group 이 null 이거나 모르면 그 사람이 마지막에 본 갈래(없으면 공통). */
    public void openList(Player p, String group) {
        UUID id = p.getUniqueId();
        if (group == null || !groups.containsKey(group)) group = lastGroup.getOrDefault(id, "common");
        lastGroup.put(id, group);
        Set<Species> seen = store.of(id);
        List<ActionButton> btns = new ArrayList<>();
        int all = 0, allSeen = 0;
        for (String g : GROUPS) {
            List<Entry> es = groups.get(g);
            int n = count(es, seen);
            all += es.size();
            allSeen += n;
            boolean cur = g.equals(group);
            Component label = Component.text().append(t(groupTab(g), cur ? GOLD : WHITE)).append(t("  만남 " + n + "/" + es.size(), cur ? PALE : GRAY))
                .decoration(TextDecoration.BOLD, cur).build();
            btns.add(ActionButton.builder(label).width(W).action(cur ? null : DialogAction.customClick(click("g/" + g), null)).build());
        }
        for (Entry e : groups.get(group)) {
            if (seen.contains(e.sp)) {
                ActionButton.Builder b = ActionButton.builder(t(e.name, WHITE)).width(W).action(DialogAction.customClick(click("s/" + key(e.sp)), null));
                if (!e.oneliner.isEmpty()) b.tooltip(t(e.oneliner, PALE));
                btns.add(b.build());
            } else {
                btns.add(ActionButton.builder(t("???", DARK)).width(W).build());
            }
        }
        ActionButton close = ActionButton.builder(t("닫기", WHITE)).width(NAV_W * 2).action(DialogAction.customClick(click("close"), null)).build();
        List<DialogBody> body = List.of(DialogBody.plainMessage(t("모두 만남 " + allSeen + "/" + all, GRAY), BODY_W));
        Dialog d = Dialog.create(f -> f.empty().base(base(t("게이브 도감", GOLD), body)).type(DialogType.multiAction(btns).columns(COLS).exitAction(close).build()));
        p.showDialog(d);
        lastShown.put(id, "목록 " + group);
    }

    /** 종 화면. 안 만난 종이면 열지 않고 false. */
    public boolean openSpecies(Player p, Species sp) {
        Entry e = sp == null ? null : bySp.get(sp);
        if (e == null || !store.has(p.getUniqueId(), sp)) return false;
        UUID id = p.getUniqueId();
        lastGroup.put(id, e.group);
        List<DialogBody> body = new ArrayList<>();
        Component icon = icon(key(sp));
        // 아이콘 글리프는 높이 20(기준선 위 8, 아래 12)이라 빈 줄 하나로 자리를 잡는다(남는 1 화소는 본문 사이 띄움 10 안)
        if (icon != null) body.add(DialogBody.plainMessage(Component.text().append(icon).append(Component.text("\n")).build(), 40));
        TextComponent.Builder head = Component.text().append(t(groupLong(e), AQUA));
        if (!e.group.equals("boss") && !e.role.isEmpty()) head.append(t("   " + e.role, GRAY));
        body.add(DialogBody.plainMessage(head.build(), BODY_W));
        if (!e.oneliner.isEmpty()) body.add(DialogBody.plainMessage(t(e.oneliner, PALE), BODY_W));
        section(body, "생김새", e.look);
        section(body, "공격", e.attack);
        section(body, "신호", e.tell);
        section(body, "대처", e.counter);
        // 같은 갈래에서 앞, 뒤 만난 종
        List<Entry> es = groups.get(e.group);
        Set<Species> seen = store.of(id);
        int at = es.indexOf(e);
        Entry prev = null, next = null;
        for (int i = at - 1; i >= 0 && prev == null; i--) if (seen.contains(es.get(i).sp)) prev = es.get(i);
        for (int i = at + 1; i < es.size() && next == null; i++) if (seen.contains(es.get(i).sp)) next = es.get(i);
        List<ActionButton> btns = new ArrayList<>();
        btns.add(nav("◀", prev));
        btns.add(ActionButton.builder(t("뒤로", WHITE)).width(NAV_W).action(DialogAction.customClick(click("g/" + e.group), null)).build());
        btns.add(nav("▶", next));
        Dialog d = Dialog.create(f -> f.empty().base(base(t(e.name, GOLD), body)).type(DialogType.multiAction(btns).columns(3).build()));
        p.showDialog(d);
        lastShown.put(id, "종 " + sp.name());
        return true;
    }

    private ActionButton nav(String arrow, Entry to) {
        if (to == null) return ActionButton.builder(t(arrow, DARK)).width(NAV_W).build();
        return ActionButton.builder(t(arrow, WHITE)).tooltip(t(to.name, PALE)).width(NAV_W).action(DialogAction.customClick(click("s/" + key(to.sp)), null))
            .build();
    }

    private static void section(List<DialogBody> body, String label, String text) {
        if (text == null || text.isEmpty()) return;
        body.add(DialogBody.plainMessage(Component.text().append(t(label, GOLD).decoration(TextDecoration.BOLD, true)).append(t("  " + text, WHITE)).build(),
            BODY_W));
    }

    private static String key(Species sp) {
        return sp.name().toLowerCase(Locale.ROOT);
    }

    /** HUD 그림 이름 → 글자(HudSpec.IMAGES 와 IMAGE_CHARS 차례가 같음) */
    private static Map<String, Character> images;

    /** 종 HUD 아이콘(큰 것 ic_l_&lt;종&gt;, 없으면 기본 얼굴) 글리프. 흰색이라 HUD 셰이더는 건드리지 않는다(표시 색 B 가 신호 값이 아님). */
    static Component icon(String key) {
        if (images == null) {
            Map<String, Character> m = new HashMap<>();
            String cs = HudSpec.IMAGE_CHARS;
            for (int i = 0; i < HudSpec.IMAGES.length && i < cs.length(); i++) m.put(HudSpec.IMAGES[i], cs.charAt(i));
            images = m;
        }
        Character c = images.get("ic_l_" + key);
        if (c == null) c = images.get("ic_l_default");
        if (c == null) return null;
        return Component.text(String.valueOf(c)).font(Key.key(HudSpec.FONT_HUD)).color(TextColor.color(0xFFFFFF)).shadowColor(ShadowColor.none());
    }

    // ------------------------------------------------------------------ 누름(맞춤 누름 gave:dex/...)

    @EventHandler
    public void onClick(PlayerCustomClickEvent e) {
        Key k = e.getIdentifier();
        if (!k.namespace().equals(NS) || !k.value().startsWith("dex/")) return;
        if (!(e.getCommonConnection() instanceof PlayerGameConnection gc)) return;
        Player p = gc.getPlayer();
        String v = k.value().substring(4);
        if (Bukkit.isPrimaryThread()) handle(p, v, "누름");
        else Bukkit.getScheduler().runTask(plugin, () -> handle(p, v, "누름"));
    }

    private void handle(Player p, String v, String how) {
        if (p == null || !p.isOnline()) return;
        // 진짜 누름만 너무 잦으면 버림(손님이 맞춤 누름을 쏟아 화면을 계속 짓게 하지 않게). 시험 훅은 늘 받음
        if (how.equals("누름")) {
            long now = System.nanoTime();
            Long last = lastClick.get(p.getUniqueId());
            if (last != null && now - last < 80_000_000L) return;
            lastClick.put(p.getUniqueId(), now);
        }
        if (v.equals("open")) openList(p, null);
        else if (v.equals("close")) {
            p.closeDialog();
            lastShown.put(p.getUniqueId(), "닫음");
        } else if (v.startsWith("g/")) openList(p, v.substring(2));
        else if (v.startsWith("s/")) {
            Species sp = Species.of(v.substring(2));
            if (!openSpecies(p, sp)) openList(p, null);
        } else return;
        lastShown.computeIfPresent(p.getUniqueId(), (id, s) -> s + " " + how);
    }

    // ------------------------------------------------------------------ 명령 /도감

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("게임 안에서 연다");
            return true;
        }
        if (a.length == 0) {
            openList(p, null);
            return true;
        }
        String arg = String.join(" ", a).trim();
        String g = group(arg);
        if (g != null) {
            openList(p, g);
            return true;
        }
        Species sp = Species.of(arg);
        if (sp != null && openSpecies(p, sp)) return true;
        // 안 만난 종은 열지 않고 그 갈래 목록(그 자리는 ???)
        Entry e = sp == null ? null : bySp.get(sp);
        openList(p, e == null ? null : e.group);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length != 1) return out;
        for (String g : GROUPS) {
            String t = groupTab(g);
            if (t.startsWith(a[0])) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ 단축키 데이터팩

    /** jar 안 dexpack/ 을 월드 datapacks/gave_dex 에 깐다(바뀐 파일만). 데이터팩은 서버를 켤 때 읽히므로 처음 깐 뒤에는 다음 켤 때부터 먹는다. */
    private void installPack() {
        if (!plugin.getConfig().getBoolean("dex.quickKey", true)) {
            packNote = "끔(설정 dex.quickKey, 깔린 datapacks/" + PACK + " 은 그대로)";
            return;
        }
        try {
            Path dst = worldRoot(game.world).resolve("datapacks").resolve(PACK);
            boolean changed = false;
            for (String f : PACK_FILES) {
                byte[] b;
                try (InputStream in = plugin.getResource("dexpack/" + f)) {
                    if (in == null) {
                        packNote = "jar 안 원본 없음 " + f;
                        return;
                    }
                    b = in.readAllBytes();
                }
                Path to = dst.resolve(f);
                if (Files.isRegularFile(to) && Arrays.equals(Files.readAllBytes(to), b)) continue;
                Files.createDirectories(to.getParent());
                Files.write(to, b);
                changed = true;
            }
            packNote = (changed ? "깔았음" : "같음") + (live() ? ", 먹음" : ", 다음 켤 때부터");
        } catch (IOException | RuntimeException e) {
            packNote = "실패 " + e;
            plugin.getLogger().log(Level.WARNING, "[도감] 단축키 데이터팩 깔기 실패", e);
        }
    }

    /** 단축키 다이얼로그 gave:dex 가 지금 서버에 있나. */
    private static boolean live() {
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.DIALOG).get(Key.key(NS, "dex")) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** level.dat 이 있는 월드 폴더(26.3 의 getWorldFolder 는 차원 폴더라 올라감, Fog.install 과 같음). */
    private static Path worldRoot(World w) {
        Path root = w.getWorldFolder().toPath().toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("level.dat"))) root = root.getParent();
        if (root == null) root = Bukkit.getWorldContainer().toPath().toAbsolutePath().resolve(w.getName());
        return root;
    }

    // ------------------------------------------------------------------ 시험 훅

    /** /gave 시험 도감 &lt;이름&gt; [all | clear | &lt;종...&gt; | 열기 [갈래] | 종 &lt;종&gt; | 누름 &lt;id&gt; | 저장 | 상태]. 내 행동이 아니면 null. */
    public String test(Player p, String[] a) {
        if (a.length < 2 || !a[1].equals("도감")) return null;
        if (p == null) return "그 이름의 사람 없음: " + (a.length > 2 ? a[2] : "");
        String op = a.length > 3 ? a[3] : "상태";
        UUID id = p.getUniqueId();
        switch (op) {
            case "all", "전부" -> {
                for (Species sp : bySp.keySet()) meet(p, sp);
            }
            case "clear", "지움" -> {
                store.clear(id);
                lastGroup.remove(id);
            }
            case "열기" -> openList(p, a.length > 4 ? group(a[4]) : null);
            case "종" -> {
                Species sp = a.length > 4 ? Species.of(a[4]) : null;
                if (sp == null) return "모르는 종";
                if (!openSpecies(p, sp)) return "안 만난 종 " + sp.name() + " | " + status(p);
            }
            case "누름" -> {
                if (a.length < 5) return "누름 <open|close|g/갈래|s/종>";
                handle(p, a[4], "훅");
            }
            case "저장" -> store.saveNow();
            case "상태" -> {
            }
            default -> {
                List<String> bad = new ArrayList<>();
                int added = 0;
                for (int i = 3; i < a.length; i++) {
                    Species sp = Species.of(a[i]);
                    if (sp == null || !bySp.containsKey(sp)) bad.add(a[i]);
                    else if (meet(p, sp)) added++;
                }
                return "만남 +" + added + (bad.isEmpty() ? "" : " 모르는 " + bad) + " | " + status(p);
            }
        }
        return op + " | " + status(p);
    }

    private String status(Player p) {
        Set<Species> seen = store.of(p.getUniqueId());
        StringBuilder sb = new StringBuilder("도감 " + p.getName() + " 만남 " + seen.size() + "/" + bySp.size());
        for (String g : GROUPS) sb.append(' ').append(groupTab(g)).append(' ').append(count(groups.get(g), seen)).append('/').append(groups.get(g).size());
        sb.append(" 기록 ").append(store.people()).append("명 새만남 ").append(met).append(store.dirty() ? " 저장대기" : " 저장됨");
        sb.append(" 화면=").append(lastShown.getOrDefault(p.getUniqueId(), "-")).append(" 단축키=").append(packNote);
        return sb.toString();
    }
}
