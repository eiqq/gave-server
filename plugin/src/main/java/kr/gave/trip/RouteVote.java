package kr.gave.trip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 갈림길 투표(원정.md 3.3절): 트럭 앞(북쪽 {@link #NORTH} 블록)에 표지판 셋 1, 2, 3 을 세우고 숫자키 1, 2, 3 으로 고른다(2026-11-12 사용자
 * "그 맵 선택은 손 1 2 3 핫바로 해", "핫바에는 그림 띄우지 마, HUD 로만"). 투표 창 동안 그 키는 무기를 바꾸지 않고(칸 바꿈 사건 취소) 핫바에
 * 아이템을 더하거나 지우지 않는다(든 총의 칸만 잠시 옮김). 세 갈래(번호, 종류, 위험, 표 수, 내 표)는 hud 값 vote.* 로 내놓아 HUD 가 그린다. 표지판을 보고 좌클릭해도 표가 들어간다(남겨 둠).
 * 지금 든 칸의 번호 키는 클라가 아무것도 안 보내므로({@link #PARK}) 창 동안 든 총을 빈 칸(3..8)으로 옮겨 그 칸을 들고(손에는 같은 총, 쏠 수 있음)
 * 닫을 때 원래 칸으로 되돌린다(kr.gave.gun.Guns.stow, unstow).
 * 한 사람 한 표, 다시 누르면 바뀜. 표지판 위에 표 수, 고른 사람에게만 그 표지판 밑에 초록 화살촉. 죽은 사람도 표를 낸다.
 * 닫을 때 표가 가장 많은 갈래, 같으면 무작위(표지판이 번갈아 커졌다 멈춤), 표가 없으면 가운데.
 *
 * <p>재미.md 의 고르기(Vote)가 아직 없어 같은 방식을 여기에 작게 둔다(나중에 합칠 수 있게 길과 무관한 부분은 표 세기와 겨눔 판정뿐).
 * 좌클릭은 돕기 차례(Players.help)에 끼는 것이 맞지만 그 고리가 아직 없어 이 클래스가 휘두름 사건을 직접 받는다: 표지판을 겨눈 누름이면 표를 넣고,
 * 같은 누름으로 열린 상점은 같은 틱에 닫는다. 고리가 생기면 Players.help 가 {@link Route#help(Player)} 를 부르고(그 뒤로 이 리스너는 표를 안 넣음) 상점은 열리지 않는다.
 *
 * <p>표지판 하나 = 판 모델(gave:trip/sign, 상태 = 종류) + 글자 디스플레이 하나(표 수, 번호) + 위험 해골(바닐라 해골 아이템) + 보상 아이콘(상점 아이콘, 바닐라 아이템)
 * + 내 표 화살촉. 뒤쪽 위에는 다음 갈림길 셋이 작고 어둡게 선다. 설명 글은 없다: 판의 꼴과 색이 종류, 해골 수가 위험, 아이콘이 보상.
 */
public final class RouteVote implements Listener {

    /** 표지판 자리: 트럭(가운데)에서 북쪽, 좌우 간격, 판 배율(판 한 변 = 이 블록), 미리보기 판 배율. */
    public static final double NORTH = 9, SPREAD = 5;
    public static final float SIGN = 3.4f, PREVIEW = 1.5f, ICON = 0.78f, TEXT = 4.2f;
    /** 숫자키 투표(기본 켬. 설정 trip.voteKeys 와 둘 중 하나라도 켜져 있으면 숫자키로 받음). */
    public static boolean KEYS = true;
    /**
     * 창 동안 든 총을 빈 칸(인덱스 3..8)으로 옮겨 그 칸을 든다({@link kr.gave.gun.Guns#stow}): 지금 든 칸의 번호 키는 클라가 칸 바꿈을 안 보내서
     * 그 갈래를 못 고르기 때문. 손에는 같은 총이 그대로이고 쏠 수 있다(Guns 가 그 칸을 원래 칸으로 봄). 닫을 때 원래 칸으로 되돌린다.
     * false 면 옮기지 않고, 든 칸 번호의 갈래는 표지판 좌클릭으로만 고른다.
     */
    public static boolean PARK = true; // 2026-11-12 사용자 "투표할 때 1번이 안 눌러지더라" + "투표 창 동안에도 총은 손에": 총을 든 채로 옮김
    /** 내려오는 틱, 떠나는 틱. */
    public static final int DROP = 18, LEAVE = 20;
    /** 모델이 없는 팩일 때 종류를 대신 보일 바닐라 아이템. */
    private static final Map<String, Material> FALLBACK = Map.of("fight", Material.IRON_SWORD, "elite", Material.NETHERITE_SWORD, "cache", Material.CHEST,
        "rest", Material.ANVIL, "event", Material.BELL, "rush", Material.MINECART, "trap", Material.TNT, "unknown", Material.BARRIER, "gate",
        Material.DRAGON_HEAD);

    /** 표지판 하나. */
    private final class Sign {
        final int index;
        final Vector base;
        /** 겨눔 판정의 가운데(판 가운데). */
        final Vector center;
        final float yaw;
        ItemDisplay board;
        TextDisplay text, mine;
        final List<Display> all = new ArrayList<>();
        int shown = -1;
        boolean big;

        Sign(int index) {
            this.index = index;
            double x = Plaza.CX + 0.5 + (index - 1) * SPREAD, z = Plaza.CZ + 0.5 - NORTH;
            this.base = new Vector(x, Plaza.FLOOR_Y, z);
            this.center = new Vector(x, Plaza.FLOOR_Y + 1.5 * SIGN, z);
            // 판 앞면이 가운데(트럭)를 보게
            this.yaw = (float) Math.atan2(Plaza.CX + 0.5 - x, NORTH);
        }
    }

    private final Game game;
    private final Trip trip;
    private final StageData data;
    private final Route route;

    private Stage[] options;
    private final Sign[] signs = new Sign[3];
    private final List<Display> extra = new ArrayList<>();
    private final Map<UUID, Integer> votes = new HashMap<>();
    private boolean open, hinted;
    private long openAt = -1, resolveAt = -1, dropAt = -1, leaveAt = -1;
    private int result = -1;
    private final List<Integer> tied = new ArrayList<>();
    /** 이 틱에 표지판을 겨눠 누른 사람(같은 누름으로 열린 상점을 닫으려고). */
    private final Map<UUID, Long> clicked = new HashMap<>();
    /** Players.help 고리가 한 번이라도 불렸나(그러면 휘두름 리스너는 표를 넣지 않는다). */
    boolean hooked;
    /** 센 것(시험): 겨눠 누른 표, 숫자키 표, 시험 훅 표, 막은 칸 바꿈. */
    int byClick, byKey, byTest, blockedKeys;
    /** 창 동안 총을 옮겨 둔 사람(되돌릴 때 부름. 칸과 아이템은 Guns 가 기억). */
    private final java.util.Set<UUID> parked = new java.util.HashSet<>();

    RouteVote(Game game, Trip trip, StageData data, Route route) {
        this.game = game;
        this.trip = trip;
        this.data = data;
        this.route = route;
        Bukkit.getPluginManager().registerEvents(this, game.plugin);
    }

    // ------------------------------------------------------------------ 열기, 닫기

    /** 투표 창이 열려 있나(표를 받는 중). */
    public boolean open() {
        return open;
    }

    /** 표지판이 서 있나(창이 닫힌 뒤 이동이 가릴 때까지도 참). */
    public boolean standing() {
        return options != null;
    }

    /** 정해진 갈래(0..2), 아직이면 -1. */
    public int result() {
        return result;
    }

    public int displays() {
        int n = extra.size();
        for (Sign s : signs) if (s != null) n += s.all.size();
        return n;
    }

    /** 표지판 셋을 세우고 표를 받기 시작한다. preview 는 다음 갈림길 셋(없으면 null). */
    public void open(Stage[] opts, Stage[] preview) {
        clear();
        options = opts;
        open = true;
        hinted = false;
        result = -1;
        openAt = game.now();
        dropAt = openAt + 1;
        boolean model = Scenery.has(game, "trip/sign");
        for (int i = 0; i < 3 && i < opts.length; i++) signs[i] = build(i, opts[i], model);
        if (preview != null) {
            for (int i = 0; i < 3 && i < preview.length; i++) {
                double x = Plaza.CX + 0.5 + (i - 1) * 2.4, z = Plaza.CZ + 0.5 - NORTH - 6.5, y = Plaza.FLOOR_Y + 9.2;
                ItemDisplay d = board(new Location(game.world, x, y + RISE, z), preview[i], model, 0f, PREVIEW);
                // 다음 갈림길은 어둡게(멀리 있는 것)
                d.setBrightness(new Display.Brightness(4, 4));
                extra.add(d);
            }
        }
        Location at = new Location(game.world, Plaza.CX + 0.5, Plaza.FLOOR_Y + 2, Plaza.CZ + 0.5 - NORTH);
        Props.soundAround(at, "trip.vote_open", Sound.BLOCK_BELL_USE, 1.2f, 1f, 0.7f, 48, -1);
        game.hud.notice("rt_vote", 60);
        // 처음 쓰는 순간 한 줄(판에 한 번)
        for (Player p : game.players.inPlay()) game.hud.hint(p, "rt_hint_vote", 80);
        parkAll();
        trip.log("갈림길 열림 " + opts[0] + " | " + opts[1] + " | " + opts[2]);
    }

    /** 정해진 때(판 틱), 그 뒤 HUD 세 갈래 칸을 보이는 틱. */
    private long decidedAt = -1;
    public static int PICK_SHOW = 40;

    /** 처음 높이(이만큼 위에서 내려온다). */
    private static final double RISE = 7;

    private Sign build(int i, Stage st, boolean model) {
        Sign s = new Sign(i);
        float S = SIGN;
        double top = Plaza.FLOOR_Y + 2.0 * S;
        s.board = board(new Location(game.world, s.base.getX(), Plaza.FLOOR_Y + 1.5 * S + RISE, s.base.getZ()), st, model, s.yaw, S);
        s.all.add(s.board);
        // 판 위 한 줄: 왼쪽에 위험 해골, 오른쪽에 보상 아이콘
        int risk = st.risk();
        List<String> rewards = st.rewards();
        double row = top + 0.5;
        for (int k = 0; k < risk; k++) {
            double off = -0.25 - ICON * 0.5 - (risk - 1 - k) * ICON;
            s.all.add(icon(s, off, row, ItemStack.of(Material.SKELETON_SKULL), ICON * 1.25f));
        }
        for (int k = 0; k < rewards.size() && k < 2; k++) {
            ItemStack it = rewardItem(rewards.get(k));
            if (it != null) s.all.add(icon(s, 0.25 + ICON * 0.5 + k * ICON, row, it, ICON));
        }
        // 그 위: 표 수(금색)와 번호(흰색 굵게)
        Location ta = at(s, 0, row + 0.55 + RISE, 0.05);
        s.text = game.world.spawn(ta, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.FIXED);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setSeeThrough(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(1.5f);
            d.setTeleportDuration(DROP);
            d.setInterpolationDuration(4);
            d.setTransformationMatrix(new Matrix4f().rotateY(s.yaw).scale(TEXT));
            d.text(label(i, 0));
        });
        s.all.add(s.text);
        s.shown = 0;
        // 내 표(고른 사람에게만 보임): 판 밑 초록 화살촉
        Location ma = at(s, 0, Plaza.FLOOR_Y + 1.0 * S - 1.15 + RISE, 0.3);
        s.mine = game.world.spawn(ma, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.FIXED);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(1.5f);
            d.setTeleportDuration(DROP);
            d.setTransformationMatrix(new Matrix4f().rotateY(s.yaw).scale(TEXT));
            d.text(Component.text("▲", NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
        });
        s.all.add(s.mine);
        return s;
    }

    /** 표지판 기준 자리: 옆(판을 마주 보고 오른쪽 +), 높이(월드 y), 앞으로(가운데 쪽 +). */
    private Location at(Sign s, double side, double y, double front) {
        double c = Math.cos(s.yaw), sn = Math.sin(s.yaw);
        // 앞 = (sin yaw, cos yaw), 마주 보는 사람의 오른쪽 = 서쪽 기준(-cos yaw, sin yaw)의 반대
        return new Location(game.world, s.base.getX() + sn * front + c * side, y, s.base.getZ() + c * front - sn * side);
    }

    private ItemDisplay icon(Sign s, double side, double y, ItemStack it, float scale) {
        Location l = at(s, side, y + RISE, 0.12);
        return game.world.spawn(l, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(1.5f);
            d.setTeleportDuration(DROP);
            // 아이템 디스플레이는 모델을 y 로 반 바퀴 돌려 그린다: 그림 앞면이 사람 쪽을 보게 반 바퀴 더
            d.setTransformationMatrix(new Matrix4f().rotateY(s.yaw + (float) Math.PI).scale(scale, scale, 0.02f));
        });
    }

    private ItemStack rewardItem(String reward) {
        String id = data.rewardIcon.get(reward);
        if (id == null) return null;
        int c = id.indexOf(':');
        String ns = c < 0 ? Props.NS : id.substring(0, c), path = c < 0 ? id : id.substring(c + 1);
        if (ns.equals(Props.NS) && !Scenery.has(game, path)) return ItemStack.of(Material.GOLD_NUGGET);
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(ns, path));
        return it;
    }

    /** 표지판 판(모델 gave:trip/sign, 상태 = 종류 키). 팩에 모델이 없으면 종류를 바닐라 아이템으로 대신 보인다. */
    private ItemDisplay board(Location l, Stage st, boolean model, float yaw, float scale) {
        ItemStack it;
        if (model) {
            it = ItemStack.of(Material.PAPER);
            it.setData(DataComponentTypes.ITEM_MODEL, Key.key(Props.NS, "trip/sign"));
            it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(st.sign()).build());
        } else it = ItemStack.of(FALLBACK.getOrDefault(st.sign(), Material.OAK_SIGN));
        Matrix4f m = model ? new Matrix4f().rotateY(yaw).scale(scale) : new Matrix4f().rotateY(yaw + (float) Math.PI).scale(scale * 0.8f, scale * 0.8f, 0.05f);
        return game.world.spawn(l, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(model ? ItemDisplay.ItemDisplayTransform.FIXED : ItemDisplay.ItemDisplayTransform.GUI);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(1.6f);
            d.setTeleportDuration(DROP);
            d.setInterpolationDuration(4);
            d.setTransformationMatrix(m);
        });
    }

    private Component label(int i, int count) {
        // 표가 없으면 번호만: 표가 들어오면 그 위에 금색 수가 뜬다
        return Component.text(count > 0 ? Integer.toString(count) : " ", NamedTextColor.GOLD).append(Component.newline())
            .append(Component.text(Integer.toString(i + 1), NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
    }

    /** 표 받기를 끝내고 갈래를 정한다(동률이면 tie 틱 동안 번갈아 커지다 멈춘 뒤에 정해짐). */
    public void close() {
        if (!open) return;
        open = false;
        unparkAll();
        int[] c = counts();
        int best = Math.max(c[0], Math.max(c[1], c[2]));
        tied.clear();
        if (best == 0) tied.add(1);
        else for (int i = 0; i < 3; i++) if (c[i] == best) tied.add(i);
        Random r = trip.rng("vote" + route.stage());
        int pick = tied.get(r.nextInt(tied.size()));
        if (tied.size() > 1) {
            // 동률: 번갈아 커지는 구경거리 뒤에 멈춤
            resolveAt = game.now() + data.voteTie;
            pendingPick = pick;
        } else decide(pick);
        trip.log("갈림길 닫힘 표 " + c[0] + "/" + c[1] + "/" + c[2] + (tied.size() > 1 ? " 동률 " + tied : "") + " → " + (pick + 1));
    }

    private int pendingPick = -1;

    /** 동률 구경거리가 아직 안 끝났으면 지금 정한다. 정해진 갈래(없으면 -1). */
    public int settle() {
        if (result < 0 && pendingPick >= 0) decide(pendingPick);
        return result;
    }

    private void decide(int pick) {
        result = pick;
        decidedAt = game.now();
        resolveAt = -1;
        pendingPick = -1;
        leaveAt = game.now();
        for (Sign s : signs) {
            if (s == null) continue;
            grow(s, s.index == pick);
            if (s.index != pick) rise(s.all);
        }
        rise(extra);
        Location at = new Location(game.world, Plaza.CX + 0.5, Plaza.FLOOR_Y + 2, Plaza.CZ + 0.5 - NORTH);
        Props.soundAround(at, "trip.vote_done", Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f, 1f, 1.2f, 48, -1);
    }

    private void rise(List<Display> list) {
        for (Display d : list) {
            if (d == null || !d.isValid()) continue;
            d.setTeleportDuration(LEAVE);
            d.teleport(d.getLocation().add(0, RISE + 6, 0));
        }
    }

    private void grow(Sign s, boolean big) {
        if (s.board == null || !s.board.isValid() || s.big == big) return;
        s.big = big;
        boolean model = s.board.getItemDisplayTransform() == ItemDisplay.ItemDisplayTransform.FIXED;
        float k = big ? 1.12f : 1f;
        s.board.setInterpolationDelay(0);
        s.board.setInterpolationDuration(4);
        s.board.setTransformationMatrix(model ? new Matrix4f().rotateY(s.yaw).scale(SIGN * k)
            : new Matrix4f().rotateY(s.yaw + (float) Math.PI).scale(SIGN * 0.8f * k, SIGN * 0.8f * k, 0.05f));
    }

    /** 표지판을 모두 치운다(이동이 안개로 가린 뒤, 판 정리). */
    public void clear() {
        unparkAll();
        for (int i = 0; i < 3; i++) {
            Sign s = signs[i];
            if (s != null) for (Display d : s.all) if (d != null && d.isValid()) d.remove();
            signs[i] = null;
        }
        for (Display d : extra) if (d != null && d.isValid()) d.remove();
        extra.clear();
        votes.clear();
        clicked.clear();
        tied.clear();
        options = null;
        open = false;
        openAt = resolveAt = dropAt = leaveAt = -1;
        pendingPick = -1;
    }

    // ------------------------------------------------------------------ 표

    /** 갈래별 표 수(판에서 나간 사람의 표는 뺀다). */
    public int[] counts() {
        int[] c = new int[3];
        for (Map.Entry<UUID, Integer> e : votes.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) continue;
            c[e.getValue()]++;
        }
        return c;
    }

    /** 그 사람의 표(0..2, 없으면 -1). */
    public int of(Player p) {
        Integer v = votes.get(p.getUniqueId());
        return v == null ? -1 : v;
    }

    /** 표를 넣는다(다시 넣으면 바뀜). 창이 닫혀 있거나 판에 든 사람이 아니면 false. */
    public boolean cast(Player p, int i) {
        if (!open || i < 0 || i > 2 || signs[i] == null || !voter(p)) return false;
        Integer old = votes.put(p.getUniqueId(), i);
        if (old != null && old == i) return true;
        if (old != null && signs[old] != null && signs[old].mine != null) p.hideEntity(game.plugin, signs[old].mine);
        if (signs[i].mine != null) p.showEntity(game.plugin, signs[i].mine);
        Props.sound(p, null, Sound.UI_BUTTON_CLICK, 0.7f, 1f, 1.3f);
        return true;
    }

    /** 표를 낼 수 있는 사람: 판에 든 사람(쓰러진 사람, 죽은 사람 포함). */
    private boolean voter(Player p) {
        if (p == null || !game.inGame(p)) return false;
        PState s = game.players.get(p.getUniqueId());
        return s != null && s.life != Life.LOBBY;
    }

    /** 그 사람이 겨눈 표지판(0..2), 없으면 -1: 시선이 판 가운데에서 맞는 반지름 안을 지나는 것 가운데 가장 가까이 지나는 것. */
    public int looked(Player p) {
        if (options == null) return -1;
        Location eye = p.getEyeLocation();
        Vector o = eye.toVector(), d = eye.getDirection();
        int best = -1;
        double bestM = data.voteRadius * data.voteRadius;
        for (Sign s : signs) {
            if (s == null) continue;
            double ox = s.center.getX() - o.getX(), oy = s.center.getY() - o.getY(), oz = s.center.getZ() - o.getZ();
            double t = ox * d.getX() + oy * d.getY() + oz * d.getZ();
            if (t <= 0 || t > data.voteReach) continue;
            double m2 = ox * ox + oy * oy + oz * oz - t * t;
            if (m2 < bestM) {
                bestM = m2;
                best = s.index;
            }
        }
        return best;
    }

    /** 돕기(좌클릭) 차례에 끼는 곳: 창이 열려 있고 표지판을 겨눴으면 표를 넣고 true(그 누름은 상점을 열지 않는다). */
    public boolean help(Player p) {
        if (!open) return false;
        int i = looked(p);
        if (i < 0) return false;
        if (cast(p, i)) byClick++;
        return true;
    }

    // ------------------------------------------------------------------ 누름을 직접 받기(Players.help 고리가 생기기 전)

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwing(PlayerAnimationEvent e) {
        if (!open || hooked || !trip.enabled || e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (help(p)) clicked.put(p.getUniqueId(), game.now());
    }

    /** 표지판을 겨눈 누름으로 상점이 열렸으면(Players.help 의 마지막 차례) 같은 틱에 닫는다. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void afterSwing(PlayerAnimationEvent e) {
        if (hooked || e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        Long at = clicked.get(p.getUniqueId());
        if (at == null || at != game.now()) return;
        // 상점은 다음 틱에 열린다(Shop.show): 그 일 바로 뒤에 닫는 일을 건다
        Bukkit.getScheduler().runTask(game.plugin, () -> {
            if (p.isOnline() && game.shop.view(p) != null) p.closeInventory();
        });
    }

    /** 숫자키로 받나(이 클래스의 KEYS 나 설정 trip.voteKeys). */
    private boolean keys() {
        return KEYS || trip.voteKeys;
    }

    /**
     * 숫자키 1 2 3: 창이 열린 동안 칸 0..2 로 바꾸려 하면 그 번호에 표를 넣고 칸 바꿈을 취소한다(총이 안 바뀜, 핫바 아이템은 손대지 않음).
     * 총의 칸 바꿈 처리(Guns.onHeld, 보통 차례)보다 먼저 받는다. 시험 훅 '길투표 &lt;이름&gt; &lt;1|2|3&gt;' 도 이 사건을 내서 같은 길을 탄다.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(PlayerItemHeldEvent e) {
        if (!open || !trip.enabled || !keys()) return;
        int slot = e.getNewSlot();
        Player p = e.getPlayer();
        if (slot < 0 || slot > 2 || !voter(p)) return;
        e.setCancelled(true);
        blockedKeys++;
        if (cast(p, slot)) byKey++;
    }

    /**
     * 창 동안 든 총을 빈 칸으로 옮겨 든다(PARK, Guns.stow: 손에는 같은 총). 이미 옮긴 사람은 그대로이고, 다른 일(나갔다 들어옴 같은)로 옮김이 풀렸으면
     * 다시 옮긴다(5틱마다 부름).
     */
    private void parkAll() {
        if (!PARK || !keys()) return;
        for (Player p : game.players.inPlay()) {
            if (!voter(p)) continue;
            if (game.guns.stow(p) >= 0) parked.add(p.getUniqueId());
        }
    }

    /** 옮겨 둔 총을 원래 칸으로 되돌리고 원래 칸을 든다(Guns.unstow, 나간 사람은 Guns 가 나갈 때 이미 되돌림). */
    private void unparkAll() {
        for (UUID id : parked) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) game.guns.unstow(p);
        }
        parked.clear();
    }

    /** 그 사람의 핫바 그림(시험: 투표 앞뒤로 아이템이 그대로인지 견줌). 칸마다 종류[:모델][:수], 든 칸. */
    String hotbar(Player p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (i > 0) sb.append(' ');
            if (it == null || it.getType().isAir()) {
                sb.append('-');
                continue;
            }
            sb.append(it.getType().name().toLowerCase(java.util.Locale.ROOT));
            Key m = it.getData(DataComponentTypes.ITEM_MODEL);
            if (m != null) sb.append(':').append(m.value());
            if (it.getAmount() > 1) sb.append('x').append(it.getAmount());
        }
        return sb.append(" held=").append(p.getInventory().getHeldItemSlot() + 1).toString();
    }

    // ------------------------------------------------------------------ 매 틱

    void tick() {
        if (options == null) return;
        long now = game.now();
        if (dropAt >= 0 && now >= dropAt) {
            dropAt = -1;
            for (Sign s : signs) if (s != null) for (Display d : s.all) if (d.isValid()) d.teleport(d.getLocation().add(0, -RISE, 0));
            for (Display d : extra) if (d.isValid()) d.teleport(d.getLocation().add(0, -RISE, 0));
        }
        if (open) {
            if (now % 5 == 0) parkAll();
            if (now % 4 == 0) {
                int[] c = counts();
                for (Sign s : signs) {
                    if (s == null || s.text == null || !s.text.isValid() || s.shown == c[s.index]) continue;
                    s.shown = c[s.index];
                    s.text.text(label(s.index, s.shown));
                }
            }
            // 안 고른 사람에게 한 번 더 알림
            if (!hinted && now - openAt >= data.voteHint) {
                hinted = true;
                for (Player p : game.players.inPlay()) if (!votes.containsKey(p.getUniqueId())) game.hud.notice(p, "rt_vote", 40);
            }
        }
        if (resolveAt >= 0) {
            // 동률: 4틱마다 다음 표지판이 커진다
            if (now >= resolveAt) decide(pendingPick);
            else if (now % 4 == 0 && !tied.isEmpty()) {
                int k = tied.get((int) (now / 4 % tied.size()));
                for (Sign s : signs) if (s != null) grow(s, s.index == k);
                Location at = new Location(game.world, Plaza.CX + 0.5, Plaza.FLOOR_Y + 2, Plaza.CZ + 0.5 - NORTH);
                Props.soundAround(at, "trip.vote_tick", Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1f, 1.4f, 48, -1);
            }
        }
        // 떠난 표지판(진 갈래, 미리보기)은 올라간 뒤 지운다
        if (leaveAt >= 0 && now - leaveAt >= LEAVE + 2) {
            leaveAt = -1;
            for (Sign s : signs) {
                if (s == null || s.index == result) continue;
                for (Display d : s.all) if (d.isValid()) d.remove();
                s.all.clear();
            }
            for (Display d : extra) if (d.isValid()) d.remove();
            extra.clear();
        }
    }

    /** 그 사람 HUD 값(키 route.*): 갈래 종류 셋, 표 수 셋, 내 표, 겨눈 갈래, 남은 틱. */
    void hud(Player p, Map<String, Object> out, long closeAt) {
        if (options == null) return;
        int[] c = counts();
        out.put("route.fork", options[0].sign() + "," + options[1].sign() + "," + options[2].sign());
        out.put("route.votes", c[0] + "," + c[1] + "," + c[2]);
        // 갈래 이름 글귀(보스 길목은 숨 고르기, 질주, 무장): HUD 가 종류 글귀 대신 쓴다
        out.put("route.names", options[0].phrase() + "," + options[1].phrase() + "," + options[2].phrase());
        out.put("route.mine", of(p) + 1);
        out.put("route.open", open);
        if (open) {
            out.put("route.aim", looked(p) + 1);
            out.put("route.left", (int) Math.max(0, closeAt - game.now()));
        }
        if (result >= 0) out.put("route.pick", result + 1);
        // HUD 의 세 갈래 칸(trip_core 가 그림): 열림, 종류 아이콘 키 셋, 위험 셋, 이름 글귀 셋, 표 수 셋, 내 표(0 없음), 남은 틱, 정해진 갈래.
        // 창이 열린 동안과 동률 구경거리 동안, 정해진 뒤 PICK_SHOW 틱까지만(이동 동안은 안 보임)
        if (!open && result >= 0 && game.now() - decidedAt > PICK_SHOW) return;
        out.put("vote.open", open);
        out.put("vote.kinds", options[0].sign() + "," + options[1].sign() + "," + options[2].sign());
        out.put("vote.risk", options[0].risk() + "," + options[1].risk() + "," + options[2].risk());
        out.put("vote.names", options[0].phrase() + "," + options[1].phrase() + "," + options[2].phrase());
        out.put("vote.counts", c[0] + "," + c[1] + "," + c[2]);
        out.put("vote.mine", of(p) + 1);
        if (open) out.put("vote.left", (int) Math.max(0, closeAt - game.now()));
        if (result >= 0) out.put("vote.pick", result + 1);
        // 맨 위 집계 줄(HUD TALLY 칸): 번호 뒤에 표 수. 갈래가 정해지면 지움(이동 동안 남지 않게)
        if (result < 0) out.put("vote.tally", "1 " + c[0] + "   2 " + c[1] + "   3 " + c[2]);
    }

    String status() {
        if (options == null) return "vote=-";
        int[] c = counts();
        return "vote=" + (open ? "open" : result >= 0 ? "pick" + (result + 1) : "tie") + " votes=" + c[0] + "/" + c[1] + "/" + c[2] + " parked=" + parked.size();
    }
}
