package kr.gave.player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * 사람 쪽 규칙. 공개 메서드 이름과 뜻은 계약(더하는 것은 됨).
 *
 * <p>체력은 플러그인 값(PState.hp)이고 바닐라 체력, 배고픔은 늘 가득(바닐라 피해, 배고픔, 블록, 버리기, 인벤토리, 엔티티 상호작용은 막음,
 * 크리에이티브는 모두 빼 줌). 피해는 모두 hurt 로 온다. 체력 0 이면 쓰러짐(바닥 좌석, 바닥 원, 이름과 남은 초, 출혈 12초),
 * 출혈 끝이면 죽음(숨김, 날기, 광장 위 관전, 결과 굴림), 먹히면 갇힘(숨김, 그 게이브 위 좌석). 부활은 상점 앞에서만
 * (동료가 일으키는 쓰러짐만 그 자리).
 */
public final class Players implements Listener {

    /** 출혈(틱), 일으키기와 짜내기 채널(틱), 같은 계열 면역(틱), 아군 탄 사격 끊김(틱). */
    public static final int BLEED = 240, REVIVE = 60, SQUEEZE = 60, IMMUNE = 30, FRIENDLY_LOCK = 6;
    /** 돕기 닿는 거리(수평 블록). */
    public static final double HELP_REACH = 2.5;
    /**
     * 동료가 일으키면 이 비율의 체력으로 일어남. 죽은 사람이 잃는 재화 비율: 0(2026-11-12 사용자 "라운드 끝나면 모두 부활": 원정도 예전 판도 죽어서 돈을
     * 잃지 않는다. 예전 판의 20% 손실은 없앰). 모의(tools/balance_sim.py)가 DEATH_LOSS 를 이 자리에서 읽는다.
     */
    public static final double REVIVE_HP = 0.3, DEATH_LOSS = 0;
    /** 듣기(웅크리기) 반경과 간격. */
    public static final double LISTEN_R = 6;
    public static final int LISTEN_EVERY = 10;
    /**
     * 바닥 원 연출 종류: ShotFx 셰이더 종류 14 쓰러짐 고리(늘 보이는 빨간 고리, 남은 출혈만큼 호가 줄어듦, 일으키는 중이면 초록).
     * RING_EVERY 틱마다 남은 몫으로 다시 찍는다({@code game.shotFx.downRing(몫, 일으키는중)}). 엎드린 척하는 종도 같은 것을 쓴다
     * ({@code ShotFx.fx("fx_flat", RING_KIND, 0)} 로 찍으면 가득 찬 빨간 고리).
     */
    public static final int RING_KIND = ShotFx.DOWN_RING, RING_EVERY = 6;
    public static final float RING_SCALE = 2.4f;
    static final float BASE_WALK = 0.2f;
    /** 갇힌 사람 좌석 높이(먹은 게이브 몸 중심 위). */
    static final double TRAP_UP = 3.2;
    /** 쓰러짐 표시(이름과 남은 초) 글자 높이(바닥 위)와 크기. 쓰러진 사람의 바닐라 이름표는 팀 DOWN_TEAM 으로 숨긴다. */
    public static final double TAG_UP = 1.25;
    public static final float TAG_SCALE = 0.8f;
    /** 쓰러진 동안 바닐라 이름표를 숨기는 팀(주 점수판). */
    static final String DOWN_TEAM = "gave_downed";
    static final NamespacedKey ROOT_JUMP = new NamespacedKey("gave", "root_jump");
    static final String GRAB_GROUP = "grab";

    public final Game game;
    private final Map<UUID, PState> states = new LinkedHashMap<>();
    private final Map<UUID, Rig> rigs = new HashMap<>();
    /** 내리면 안 되는 좌석(쓰러짐, 끌림, 갇힘). 내 코드가 내릴 때만 먼저 뺀다. */
    private final Set<UUID> lockedSeats = new HashSet<>();
    private final Random rnd = new Random();
    /** 시험 훅 결과: 다음 죽음 결과를 이 종으로(묶음, 웨이브 단계 무시, 구현된 종만). 한 번 쓰면 지움. */
    private final Map<UUID, Species> forcedResult = new HashMap<>();
    private Game.State seenState;
    private long seenStateAt = -1;
    /** 시험 훅 판끝막기: 서 있는 척하는 빈 상태(사람 없음). */
    private PState phantom;
    private int spread;

    // 새 종 갈고리(2026-11-12 sp_engine): 띄운 사람, 이번 틱 밀기, 키 갈고리
    private static final class Launch {
        Gabe by;
        double dmg;
        String cause;
        long at;
        boolean left;
        double y0, top;
    }

    private final Map<UUID, Launch> launches = new HashMap<>();
    private final Map<UUID, double[]> pushes = new HashMap<>();
    private final Map<Object, HurtHook> hurtHooks = new LinkedHashMap<>();
    private final Map<Object, HelpHook> helpHooks = new LinkedHashMap<>();
    private final Map<Object, CureHook> cureHooks = new LinkedHashMap<>();
    private int hurtDepth;
    private final Set<String> hookErr = new HashSet<>();

    /** 화면 쪽 물건(사람마다). */
    private static final class Rig {
        ItemDisplay seat;
        boolean carried;
        ItemDisplay ring;
        long ringAt;
        TextDisplay tag;
        String tagKey;
        Location rootAt;
        float walk = -1f;
        boolean jumpOff;
        /** 탭 목록에 마지막으로 건 상태(색). */
        Life tabLife;
    }

    /**
     * 돈 배율(설정 moneyMul, 기본 1: 운영자 행사용. 2026-11-14 까지의 1.7 은 Director.Tune.REWARD_MUL 같은 바탕 값에 녹였다). 사람이 버는 모든 돈({@link #addMoney} 의 양수:
     * 처치, 보스, 라운드, 증강, 팀, 스테이지 보상)에 곱한다. 옮기기(돌려받기, 합류 몫, 시험)는 {@link #addMoneyRaw} 로 곱하지 않는다.
     */
    public double moneyMul = 1.0;
    /** 모든 벌이에 곱하는 바탕 배율(2026-11-16 사용자 "돈 수급량 좀 더 보정해주고(증가)": +25%). 운영자 행사용 moneyMul 과 따로 곱한다 */
    public static final double INCOME = 1.25;

    /** 처치 보상 나누기와 정산(팀 몫, 때린 몫, 팀 일 수당, 따라잡기 몫). */
    public final Wallet wallet;

    public Players(Game game) {
        this.game = game;
        this.wallet = new Wallet(game);
        try {
            // 옛 돈 설정(assistMul 이나 roundMoneyMul 이 남은 파일): 한 번만 옮긴다(migrateMoney. 두 키가 없어진 뒤 운영자가 바꾼 moneyMul 은 그대로)
            var cfg = game.plugin.getConfig();
            if (cfg.contains("assistMul", true) || cfg.contains("roundMoneyMul", true)) migrateMoney(cfg);
            double m = cfg.getDouble("moneyMul", 1.0);
            if (m > 0 && !Double.isNaN(m) && !Double.isInfinite(m)) moneyMul = Math.min(100, m);
        } catch (RuntimeException e) {
            moneyMul = 1.0;
        }
    }

    /** 2026-11-14 까지의 moneyMul 기본(e4c25c4 의 config.yml 과 이 클래스): 옛 설정을 옮길 때 옛 값을 이것으로 나눈다. */
    static final double OLD_MONEY_MUL = 1.7;
    private static final Pattern MONEY_LINE = Pattern.compile("(moneyMul[ \\t]*:[ \\t]*)([^#\\r\\n]*?)([ \\t]*(?:#[^\\r\\n]*)?)(\\r?\\n)?");
    private static final Pattern OLD_KEY_LINE = Pattern.compile("(?:assistMul|roundMoneyMul)[ \\t]*:.*", Pattern.DOTALL);

    /**
     * 옛 돈 설정 옮기기(2026-11-14 돈 구조): 옛 moneyMul 의 기본 1.7 과 assistMul, roundMoneyMul 은 바탕 값에 녹였다. 메모리 값을 바꾸고, 설정 파일은
     * 줄 단위로 고친다({@link #migrateMoneyText}: 다른 줄의 주석, 한 줄 목록, 줄 끝 CRLF 는 그대로). moneyMul 은 '옛 값 / 1.7'(운영자가 올려 둔
     * 행사 배율의 비율은 지킴, 옛 기본이면 1). 파일을 못 고치면 기록 한 줄을 남기고 메모리 값만 바꾼다(다음에 켤 때 다시 해 봄).
     */
    private void migrateMoney(org.bukkit.configuration.file.FileConfiguration cfg) {
        double old = cfg.contains("moneyMul", true) ? cfg.getDouble("moneyMul", OLD_MONEY_MUL) : OLD_MONEY_MUL;
        if (!(old > 0) || !Double.isFinite(old)) old = OLD_MONEY_MUL;
        double now = Math.round(old / OLD_MONEY_MUL * 1000) / 1000.0;
        cfg.set("moneyMul", now);
        cfg.set("assistMul", null);
        cfg.set("roundMoneyMul", null);
        String how;
        try {
            Path f = game.plugin.getDataFolder().toPath().resolve("config.yml");
            String out = migrateMoneyText(Files.readString(f, StandardCharsets.UTF_8), now);
            Path tmp = f.resolveSibling("config.yml.money-tmp");
            Files.writeString(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            how = "설정 파일은 그 줄만 고침";
        } catch (IOException | RuntimeException e) {
            how = "설정 파일은 못 고쳐 메모리 값만 바꿈(" + e + ")";
        }
        game.plugin.getLogger().info("[돈] 옛 돈 설정을 옮김: moneyMul " + old + " 에서 " + now + " 으로(옛 기본 1.7 을 바탕 값에 녹임), assistMul, roundMoneyMul 지움. " + how);
    }

    /**
     * {@link #migrateMoney} 의 파일 고치기(줄 단위, 줄 끝 그대로): 맨 바깥 assistMul, roundMoneyMul 줄을 지우고(바로 위 줄이 그 키의 옛 기본 주석이면 같이),
     * moneyMul 줄은 값만 now 로 바꾼다(바로 위 줄이 옛 기본 주석 '돈 70% 더: 1.7'이면 지금 기본 주석으로). 그 밖의 줄은 한 글자도 안 바꾼다.
     */
    static String migrateMoneyText(String text, double now) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("(?<=\\n)", -1)) {
            if (OLD_KEY_LINE.matcher(line).matches()) {
                int last = out.size() - 1;
                if (last >= 0 && (out.get(last).startsWith("# 함께 때린 사람(어시스트)") || out.get(last).startsWith("# 스테이지(2라운드)가 끝날 때"))) out.remove(last);
                continue;
            }
            Matcher m = MONEY_LINE.matcher(line);
            if (m.matches()) {
                String eol = m.group(4) == null ? "" : m.group(4);
                int last = out.size() - 1;
                if (last >= 0 && out.get(last).startsWith("#") && out.get(last).contains("\"돈 70% 더\": 1.7")) {
                    String e = eol.isEmpty() ? "\n" : eol;
                    out.set(last, "# 사람이 버는 모든 돈(처치, 보스, 라운드, 증강, 팀, 스테이지 보상)의 배율. 기본 1(운영자 행사용: 2 면 돈 두 배). 돌려받기, 사람끼리 옮기기에는 안 곱함." + e
                        + "# 2026-11-14 돈 구조를 바꾸며 옛 값(moneyMul 1.7, assistMul 2.0, roundMoneyMul 2.0)은 바탕 값에 녹였다: 1.7 로 되돌리면 두 번 곱해진다" + e);
                }
                line = m.group(1) + now + m.group(3) + eol;
            }
            out.add(line);
        }
        return String.join("", out);
    }

    public PState get(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), id -> new PState(id, p.getName()));
    }

    public PState get(UUID id) {
        return states.get(id);
    }

    public Collection<PState> all() {
        return states.values();
    }

    /**
     * 서 있는 사람(쏠 수 있음). 나간 사람과 판 중에 크리에이티브로 바꾼 사람은 상태가 서 있어도 뺀다(피해도 틱도 안 받는 사람이
     * 몹 표적, 감독 인원 셈, 판 끝 판정에 남지 않게).
     */
    public List<Player> standing() {
        List<Player> out = new ArrayList<>();
        for (PState s : states.values()) {
            if (!s.standing() || s.offline) continue;
            Player p = Bukkit.getPlayer(s.id);
            if (p != null && p.isOnline() && game.inGame(p)) out.add(p);
        }
        return out;
    }

    /** 판 끝 판정(Game): 서 있는 사람이 하나라도 있나. 시험 훅 판끝막기의 빈 상태도 센다. */
    public boolean anyStanding() {
        return phantom != null && phantom.life == Life.ALIVE || !standing().isEmpty();
    }

    /** 판에 든 사람(서 있음, 쓰러짐, 죽음, 갇힘, 기다림). 나간 사람은 빠짐. */
    public List<Player> inPlay() {
        List<Player> out = new ArrayList<>();
        for (PState s : states.values()) {
            if (s.life == Life.LOBBY || s.offline) continue;
            Player p = Bukkit.getPlayer(s.id);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    /** 이 상태인 사람(접속 중). */
    public List<Player> withLife(Life l) {
        List<Player> out = new ArrayList<>();
        for (PState s : states.values()) {
            if (s.life != l || s.offline) continue;
            Player p = Bukkit.getPlayer(s.id);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    /** 쓰러진 사람(HUD 방향 글리프, 엎드린 척 게이브). */
    public List<Player> downed() {
        return withLife(Life.DOWNED);
    }

    /** 붙잡힌 사람(HUD 방향 글리프). */
    public List<Player> grabbed() {
        List<Player> out = new ArrayList<>();
        for (Player p : standing()) if (get(p).grabbedBy != null) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ 매 틱

    public void tick() {
        long now = game.now();
        // 돈 정산은 맨 앞에서 따로(뒤의 사람 틱이 던져도 돈은 돌고, 지갑이 던져도 사람 틱은 돈다)
        try {
            wallet.tick();
        } catch (RuntimeException e) {
            if (now - walletErrAt >= 200) {
                walletErrAt = now;
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[돈] 정산 오류", e);
            }
        }
        phaseEdges();
        boolean over = game.state == Game.State.OVER;
        for (PState s : new ArrayList<>(states.values())) {
            if (s == phantom || s.offline) continue;
            Player p = Bukkit.getPlayer(s.id);
            if (p == null || !p.isOnline() || !game.inGame(p)) continue;
            switch (s.life) {
                case ALIVE -> aliveTick(p, s, now);
                case DOWNED -> {
                    if (!over) downedTick(p, s, now);
                }
                case DEAD -> {
                    if (!deadFollow(p, s, now)) spectateTick(p, s);
                }
                case WAITING -> spectateTick(p, s);
                case TRAPPED -> trappedTick(p, s, now);
                default -> {
                }
            }
            tabTick(p, s);
            if (s.life == Life.LOBBY) continue;
            statusTick(p, s, now);
            helpTick(p, s, now);
            if ((now + (s.id.hashCode() & 7)) % LISTEN_EVERY == 0) listen(p, s);
        }
        if (!launches.isEmpty()) launchTick(now);
        if (now % 40 == 0) visibilityPass();
    }

    /** 지갑 정산 오류를 마지막으로 기록한 틱(10초에 한 번). */
    private long walletErrAt = Long.MIN_VALUE / 2;

    /** 판 상태가 바뀐 첫 틱: 웨이브 시작이면 기다리던 사람을 넣고, 쉬는 시간 시작이면 갇힌 사람을 상점 앞에 세운다. */
    private void phaseEdges() {
        if (game.state == seenState && game.stateAt == seenStateAt) return;
        seenState = game.state;
        seenStateAt = game.stateAt;
        if (phantom != null && game.state == Game.State.PREP) phantom.life = Life.ALIVE;
        if (game.state == Game.State.WAVE) {
            for (Player p : withLife(Life.WAITING)) if (game.inGame(p)) activate(p, get(p));
        } else if (game.state == Game.State.BREAK) {
            launches.clear();
            // 원정: 갇힌 사람을 라운드가 끝날 때까지 못 구하면 죽음으로 친다(꺼내는 값 절반). 예전 판은 그냥 상점 앞 부활
            for (Player p : withLife(Life.TRAPPED)) {
                // 갇힘 이어 가기(FREE, holdTrapped): 가둔 종이 다음 웨이브로 잇는다
                if (get(p).trapHold) continue;
                if (game.trip.paidRevive()) trappedToDead(p);
                else reviveAtShop(p, false);
            }
        }
    }

    /** 원정: 갇힌 채 라운드가 끝남 → 죽음(죽음 결과 개체 없음, 꺼내는 값 절반). */
    private void trappedToDead(Player p) {
        PState s = get(p);
        if (s.life != Life.TRAPPED) return;
        Location at = spot(p);
        unseat(p, false);
        removeMarks(p);
        s.life = Life.DEAD;
        s.lifeAt = game.now();
        s.eater = null;
        s.deathMob = null;
        s.deathResult = null;
        s.holdSkips = 0;
        deathBegan(p, s);
        s.trappedDeath = true;
        spectate(p, at.clone().add(0, 8, 0));
        lookPitch(p, 50);
        log("갇힌 채 라운드 끝: 죽음으로 침 " + p.getName());
    }

    /** 원정: 죽음 하나의 시작(죽은 횟수, 죽은 라운드, 모금과 깎임 지움, 부품에 알림). */
    private void deathBegan(Player p, PState s) {
        if (!game.trip.paidRevive()) return;
        s.deaths++;
        s.deadRound = game.wave;
        s.revivePool = 0;
        s.faceKilled = false;
        s.trappedDeath = false;
        game.trip.fire(kr.gave.trip.Trip.EV_DEAD, p);
    }

    /** 죽은 사람 좌석 높이(자기 얼굴을 한 게이브 위: 갇힘 관전과 같은 높이. 몸 속에 박히면 몸 조각이 화면을 가린다, 2026-11-12 사진). */
    static final double DEAD_UP = TRAP_UP;

    /**
     * 원정: 죽은 사람의 카메라는 자기 얼굴을 한 게이브(죽음 결과 개체)에 붙어 따라다닌다(조종은 못 하고 둘러볼 수만 있음: 갇힘 관전과 같은 좌석).
     * 그 개체가 없거나 죽었으면 false(광장 위 자유 관전으로).
     */
    private boolean deadFollow(Player p, PState s, long now) {
        Gabe m = s.deathMob;
        Rig r = rig(p);
        if (m == null || m.dead || m.removed || !game.trip.paidRevive() || game.state == Game.State.OVER) {
            if (r.seat != null) {
                Location at = r.seat.getLocation();
                unseat(p, false);
                spectate(p, at.add(0, 6, 0));
            }
            return false;
        }
        Location to = new Location(game.world, m.pos.getX(), m.pos.getY() + DEAD_UP, m.pos.getZ());
        if (r.seat == null || !r.seat.isValid()) seatAt(p, to, false);
        else if (!r.seat.getPassengers().contains(p)) mount(r.seat, p);
        if (now % 2 == 0 && r.seat.getLocation().distanceSquared(to) > 0.0025) r.seat.teleport(to);
        return true;
    }

    private void aliveTick(Player p, PState s, long now) {
        Rig r = rig(p);
        if (s.grabbedBy != null && (s.grabbedBy.dead || s.grabbedBy.removed)) releaseQuiet(p, s, "release");
        // 태워 주는 좌석(SEAT, ride)이 아직이면 붙잡힘 없이도 좌석에 둠
        if (r.carried && s.grabbedBy == null && s.rideUntil <= now) {
            s.rideUntil = 0;
            unseat(p, true);
        }
        if (r.carried && r.seat != null && !r.seat.getPassengers().contains(p)) mount(r.seat, p);
        boolean stuck = stuck(s, now);
        if (stuck && !r.carried) {
            Location l = p.getLocation();
            if (r.rootAt == null) r.rootAt = l.clone();
            else if (Math.hypot(l.getX() - r.rootAt.getX(), l.getZ() - r.rootAt.getZ()) > 0.2) teleportKeepLook(p, r.rootAt);
        } else {
            r.rootAt = null;
        }
    }

    private void downedTick(Player p, PState s, long now) {
        Rig r = rig(p);
        // 일으키는 동안은 출혈이 멈춤
        if (s.reviver != null) s.bleedUntil++;
        if (now >= s.bleedUntil) {
            bleedOut(p, s);
            return;
        }
        if (r.seat == null || !r.seat.isValid()) {
            seatAt(p, ground(p.getLocation()), false);
        } else if (!r.seat.getPassengers().contains(p)) {
            mount(r.seat, p);
        }
        // 원정 트럭 부품 '견인줄'(증강 winch: {거리, 틱당 블록}): 트럭 곁에 쓰러진 사람이 트럭 쪽으로 끌려온다(일으키는 동안은 멈춤)
        if (s.reviver == null && now % 2 == 0 && kr.gave.trip.Truck.live() && r.seat != null && r.seat.isValid()) {
            double[] w = game.augments.winch();
            if (w != null) {
                Location l = r.seat.getLocation();
                double d = kr.gave.trip.Truck.dist(l.getX(), l.getZ());
                Vector c = kr.gave.trip.Truck.center();
                Vector dir = new Vector(c.getX() - l.getX(), 0, c.getZ() - l.getZ());
                if (d > 1.5 && d <= w[0] && dir.lengthSquared() > 1e-6) r.seat.teleport(l.add(dir.normalize().multiply(Math.min(d - 1.5, w[1] * 2))));
            }
        }
        marksTick(p, s, now);
    }

    private void spectateTick(Player p, PState s) {
        if (!p.getAllowFlight()) p.setAllowFlight(true);
        if (!p.isFlying()) p.setFlying(true);
        Location l = p.getLocation();
        double dx = l.getX() - (Plaza.CX + 0.5), dz = l.getZ() - (Plaza.CZ + 0.5), r = Math.hypot(dx, dz);
        double lim = Plaza.R_OUTER - 0.5, x = l.getX(), y = l.getY(), z = l.getZ();
        boolean out = false;
        if (r > lim) {
            x = Plaza.CX + 0.5 + dx / r * (lim - 0.5);
            z = Plaza.CZ + 0.5 + dz / r * (lim - 0.5);
            out = true;
        }
        if (y < Plaza.FLOOR_Y + 2) {
            y = Plaza.FLOOR_Y + 2.5;
            out = true;
        } else if (y > Plaza.FLOOR_Y + 40) {
            y = Plaza.FLOOR_Y + 39.5;
            out = true;
        }
        if (out) teleportKeepLook(p, new Location(l.getWorld(), x, y, z));
    }

    private void trappedTick(Player p, PState s, long now) {
        Gabe e = s.eater;
        if (e != null && (e.dead || e.removed)) {
            if (!s.trapHold) {
                reviveAtShop(p, false);
                return;
            }
            // 갇힘 이어 가기(FREE): 먹은 개체가 사라져도 갇힌 채 그 자리 좌석에(가둔 종이 retrap, free, trapEnd 로 마무리)
            s.eater = null;
            if (s.trapLostAt < 0) s.trapLostAt = now;
            e = null;
        }
        // 이어 가는 갇힘을 아무도 이어 받지 않으면(가둔 종이 잊음) TRAP_HOLD_MAX 틱 뒤 갇힘 끝 규칙
        if (e == null && s.trapHold && s.trapLostAt >= 0 && now - s.trapLostAt > TRAP_HOLD_MAX) {
            trapEnd(p);
            return;
        }
        Rig r = rig(p);
        if (r.seat == null || !r.seat.isValid()) {
            Location at = e != null ? e.loc().add(0, TRAP_UP, 0) : p.getLocation();
            seatAt(p, at, false);
        }
        if (!r.seat.getPassengers().contains(p)) mount(r.seat, p);
        if (e != null && now % 2 == 0) {
            Location to = new Location(game.world, e.pos.getX(), e.pos.getY() + TRAP_UP, e.pos.getZ());
            if (r.seat.getLocation().distanceSquared(to) > 0.0025) r.seat.teleport(to);
        }
    }

    /** 상태 이상 끝, 덮기 끝, 잠복 터짐, 걷기 속도. */
    private void statusTick(Player p, PState s, long now) {
        for (Iterator<Map.Entry<String, Long>> it = s.slowUntil.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() > now) continue;
            it.remove();
            s.slowPct.remove(e.getKey());
            s.immuneUntil.put(e.getKey(), now + IMMUNE);
        }
        if (s.rootUntil > 0 && s.rootUntil <= now) {
            s.rootUntil = 0;
            if (s.rootGroup != null) s.immuneUntil.put(s.rootGroup, now + IMMUNE);
            s.rootGroup = null;
        }
        if (s.cover != null && s.coverUntil <= now) {
            s.cover = null;
            game.hud.uncover(p);
        }
        if (s.veil != null && s.veilUntil <= now) s.veil = null;
        if (s.latent != null && now >= s.latentAt) {
            Consumer<Player> pop = s.latentPop;
            String kind = s.latent;
            s.latent = null;
            s.latentPop = s.latentCure = null;
            if (pop != null && (s.life == Life.ALIVE || s.life == Life.DOWNED)) {
                try {
                    pop.accept(p);
                } catch (RuntimeException ex) {
                    game.plugin.getLogger().warning("[사람] 잠복 " + kind + " 터짐 오류: " + ex);
                }
            }
        }
        // 붙인 개체가 죽거나 사라지면 머리 부착도 떨어짐(죽은 게이브는 tick 이 돌지 않아 스스로 못 치움)
        if (s.headItem != null && s.headBy != null && (s.headBy.dead || s.headBy.removed)) attachHead(p, null);
        if (s.headItem != null && (now + (s.id.hashCode() & 31)) % 40 == 0) sendHead(p, s.headItem);
        applyMove(p, s, now);
    }

    private boolean stuck(PState s, long now) {
        return s.life == Life.ALIVE && (s.rootUntil > now || s.grabbedBy != null && s.grabPin);
    }

    /** 걷기 속도 = 0.2 x 증강 배율 x (1 - 느려짐 합), 묶이거나 붙잡히면 0 과 점프 0. setWalkSpeed 는 MOVEMENT_SPEED 바탕값과 능력치를 같이 바꿔 시야가 좁아지지 않는다. */
    private void applyMove(Player p, PState s, long now) {
        Rig r = rig(p);
        boolean stuck = stuck(s, now);
        float w = BASE_WALK;
        if (s.life == Life.ALIVE) w = stuck ? 0f : (float) Math.max(0, Math.min(1, BASE_WALK * s.moveMul * (1 - s.slowSum(now))));
        if (Math.abs(w - r.walk) > 1e-5) {
            p.setWalkSpeed(w);
            r.walk = w;
        }
        if (stuck != r.jumpOff) {
            AttributeInstance j = p.getAttribute(Attribute.JUMP_STRENGTH);
            if (j != null) {
                j.removeModifier(ROOT_JUMP);
                if (stuck) j.addTransientModifier(new AttributeModifier(ROOT_JUMP, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
            r.jumpOff = stuck;
        }
    }

    // ------------------------------------------------------------------ 피해와 회복

    /**
     * 게이브(src, 없으면 null)가 준 피해. causeKey 는 HUD 글귀 키(죽은 이유 카드). 종은 기본 피해를 넘긴다: 여기서
     * 사람 armor 와 (src 가 있으면) Director.dmgMul() 을 곱한다. 서 있지 않은 사람에게는 아무 일도 없다.
     */
    public void hurt(Player p, double amount, Gabe src, String causeKey) {
        PState s = get(p);
        if (!s.standing() || amount <= 0 || !game.inGame(p)) return;
        // 원정: 트럭에서 막 꺼내진 사람은 잠깐 무적
        if (game.now() < s.safeUntil) return;
        // 받는 피해 배율(TAKEN)과 이 개체 배율(MOBDMG, Gabe.dmgMul x dmgBuff): 2026-11-12 sp_engine
        double dmg = amount * s.armor * s.takenMul(game.now()) * (src != null ? game.director.dmgMul() * src.hurtMul() : 1.0);
        s.hp -= dmg;
        if (src != null) s.lastBy = src.sp;
        s.lastCause = causeKey;
        s.lastHurtAt = game.now();
        float yaw = 0;
        if (src != null) {
            Location l = p.getLocation();
            yaw = (float) (Math.toDegrees(Math.atan2(src.pos.getZ() - l.getZ(), src.pos.getX() - l.getX())) - 90 - l.getYaw());
        }
        p.sendHurtAnimation(yaw);
        Props.sound(p.getLocation(), null, Sound.ENTITY_PLAYER_HURT, 0.7f, 0.95f + rnd.nextFloat() * 0.1f, 1f);
        // 피해 통지(HURTHOOK): 갈고리 안에서 다시 hurt 를 불러도(피해 나누기) 그 피해는 통지하지 않는다(되먹임 막기)
        if (!hurtHooks.isEmpty() && hurtDepth == 0) {
            hurtDepth++;
            try {
                for (Map.Entry<Object, HurtHook> e : new ArrayList<>(hurtHooks.entrySet())) {
                    try {
                        e.getValue().hurt(p, dmg, src, causeKey);
                    } catch (RuntimeException ex) {
                        if (hookErr.add("hurt:" + e.getKey())) game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[사람] 피해 갈고리 " + e.getKey() + " 오류", ex);
                    }
                }
            } finally {
                hurtDepth--;
            }
            if (!s.standing()) return;
        }
        if (s.hp <= 0) {
            // 증강 '두 번째 숨': 쓰러질 피해를 한 번 버팀(체력 1)
            if (game.augments.cheatDeath(p)) {
                s.hp = 1;
                return;
            }
            s.hp = 0;
            down(p, src, causeKey);
        }
    }

    public void heal(Player p, double amount) {
        PState s = get(p);
        if (s.life != Life.ALIVE) return;
        s.hp = Math.min(s.maxHp, s.hp + amount);
    }

    // ------------------------------------------------------------------ 상태 이상(계열 group 마다 면역 1.5초)

    /**
     * 느려짐: pct 는 비율(0.3 = 30% 느림, 1 보다 크면 퍼센트로 읽음), ticks 동안. 같은 계열이 걸려 있으면 큰 값과 늦은 끝으로 늘림.
     * 걸린 것을 다 합해도 50% 까지. 끝나면 그 계열 1.5초 면역(면역 중이면 무시).
     */
    public void slow(Player p, double pct, int ticks, String group) {
        PState s = get(p);
        if (!s.standing() || ticks <= 0 || immune(p, group)) return;
        double f = pct > 1 ? pct / 100.0 : pct;
        f = Math.max(0, Math.min(0.5, f));
        long until = game.now() + ticks;
        s.slowUntil.merge(group, until, Math::max);
        s.slowPct.merge(group, f, Math::max);
        applyMove(p, s, game.now());
    }

    /** 묶임: ticks 동안 못 움직임(걷기 0, 점프 0, 제자리), 쏘기도 못 함(Game.blocked). 끝나면 그 계열 1.5초 면역. */
    public void root(Player p, int ticks, String group) {
        PState s = get(p);
        if (!s.standing() || ticks <= 0 || immune(p, group)) return;
        long until = game.now() + ticks;
        if (until > s.rootUntil) {
            s.rootUntil = until;
            s.rootGroup = group;
        }
        applyMove(p, s, game.now());
    }

    public boolean immune(Player p, String group) {
        Long u = get(p).immuneUntil.get(group);
        return u != null && u > game.now();
    }

    /** 묶임만 풂(그 계열 group 으로 걸린 것일 때만, null 이면 계열 무관). 느려짐은 그대로. 면역 없이. */
    public void unroot(Player p, String group) {
        PState s = get(p);
        if (s.rootUntil <= 0 || group != null && !group.equals(s.rootGroup)) return;
        s.rootUntil = 0;
        s.rootGroup = null;
        applyMove(p, s, game.now());
    }

    /** 쓰러진 사람의 출혈 끝을 ticks 만큼 옮긴다(음수면 앞당김 = 빨리 죽음, 양수면 늦춤). 쓰러져 있지 않으면 아무것도 안 함. */
    public void bleedShift(Player p, int ticks) {
        if (p == null) return;
        PState s = get(p);
        if (s.life != Life.DOWNED || ticks == 0) return;
        s.bleedUntil += ticks;
    }

    /** 느려짐, 묶임을 모두 풂(면역 없이). */
    public void clearSlows(Player p) {
        PState s = get(p);
        s.slowUntil.clear();
        s.slowPct.clear();
        s.rootUntil = 0;
        s.rootGroup = null;
    }

    /**
     * 붙잡기(못 쏨, 재장전 못 함, 기본은 제자리. 좌클릭 escapeClicks 번으로 탈출, 동료가 그 게이브를 죽이거나 detach 로 떼면 풀림).
     * 이미 붙잡혔거나, 서 있지 않거나, 'grab' 면역 중이거나, 비옷이 막으면 false. 건 바로 뒤에 get(p).grabPin = false(움직임 허용),
     * grabDetach = false(쏴야만 풀림)로 바꿀 수 있다. 끌고 가려면 매 틱 carry(p, 자리).
     */
    public boolean grab(Player p, Gabe by, int escapeClicks) {
        PState s = get(p);
        if (by == null || !s.standing() || s.grabbedBy != null || immune(p, GRAB_GROUP)) return false;
        if (coat(p, s)) return false;
        endHelp(s);
        s.grabbedBy = by;
        s.escapeNeed = Math.max(1, escapeClicks);
        s.escapeHits = 0;
        s.grabAt = game.now();
        s.grabPin = true;
        s.grabDetach = true;
        applyMove(p, s, game.now());
        return true;
    }

    /** 붙잡힘 풂(끌던 좌석에서도 내림, 'grab' 1.5초 면역). 그 게이브가 Detachable 이면 detached(p, null, "release"). */
    public void release(Player p) {
        PState s = get(p);
        if (s.grabbedBy != null) releaseQuiet(p, s, "release");
        else if (rig(p).carried) unseat(p, true);
    }

    private void releaseQuiet(Player p, PState s, String how) {
        releaseBy(p, s, null, how);
    }

    private void releaseBy(Player p, PState s, Player helper, String how) {
        Gabe g = s.grabbedBy;
        s.grabbedBy = null;
        s.escapeHits = s.escapeNeed = 0;
        s.immuneUntil.put(GRAB_GROUP, game.now() + IMMUNE);
        Rig r = rig(p);
        if (r.carried && s.life == Life.ALIVE) unseat(p, true);
        if (g instanceof Detachable d) {
            try {
                d.detached(p, helper, how);
            } catch (RuntimeException ex) {
                game.plugin.getLogger().warning("[사람] detached 오류: " + ex);
            }
        }
    }

    public Gabe grabbedBy(Player p) {
        return get(p).grabbedBy;
    }

    /** 화면 덮기(한 번에 하나, 걸린 동안 새 것은 거절, 비옷이 막으면 false). key 는 HUD 덮개 그림 키. Hud.cover 로 그린다. */
    public boolean cover(Player p, String key, int ticks) {
        PState s = get(p);
        if (s.cover != null && s.coverUntil > game.now()) return false;
        if (s.life == Life.LOBBY || coat(p, s)) return false;
        s.cover = key;
        s.coverUntil = game.now() + ticks;
        s.coverDetach = true;
        game.hud.cover(p, key, ticks);
        return true;
    }

    /** 덮기 지움. */
    public void uncover(Player p) {
        PState s = get(p);
        if (s.cover == null) return;
        s.cover = null;
        s.coverUntil = 0;
        game.hud.uncover(p);
    }

    /** 머리에 붙는 것: 남들에게 머리 칸 모델을 보냄(본인은 HUD 덮개를 종이 cover 로). null 이면 뗌. */
    public void attachHead(Player p, ItemStack model) {
        attachHead(p, model, null);
    }

    /** 머리 부착과 붙인 개체(by, 동료가 떼면 Detachable.detached). PlayerTrackEntityEvent 와 2초마다 다시 보낸다. */
    public void attachHead(Player p, ItemStack model, Gabe by) {
        PState s = get(p);
        Gabe old = s.headBy;
        s.headItem = model == null ? null : model.clone();
        s.headBy = model == null ? null : by;
        sendHead(p, s.headItem);
        if (model == null && old instanceof Detachable d && old != by) {
            try {
                d.detached(p, null, "release");
            } catch (RuntimeException ex) {
                game.plugin.getLogger().warning("[사람] detached 오류: " + ex);
            }
        }
    }

    private void sendHead(Player p, ItemStack item) {
        ItemStack it = item;
        if (it == null) {
            ItemStack real = p.getInventory().getHelmet();
            it = real == null ? ItemStack.empty() : real;
        }
        for (Player o : p.getWorld().getPlayers()) {
            if (o != p && o.canSee(p)) o.sendEquipmentChange(p, EquipmentSlot.HEAD, it);
        }
    }

    /** 잠복(한 사람에 하나, 판 전체 동시 수는 config latentCap, 기본 4). ticks 뒤 onPop(쓰러진 사람에게도 터짐, 죽거나 갇히면 사라짐). */
    public boolean latent(Player p, String kind, int ticks, Consumer<Player> onPop) {
        return latent(p, kind, ticks, onPop, null);
    }

    /** onCure 는 정화(cure), 동료 짜내기(돕기 3초, 등 뒤)로 풀릴 때. */
    public boolean latent(Player p, String kind, int ticks, Consumer<Player> onPop, Consumer<Player> onCure) {
        PState s = get(p);
        if (s.life != Life.ALIVE && s.life != Life.DOWNED) return false;
        if (s.latent != null || latentCount() >= latentCap()) return false;
        if (coat(p, s)) return false;
        long now = game.now();
        s.latentFree = false;
        s.latent = kind;
        s.latentStart = now;
        s.latentAt = now + Math.max(1, ticks);
        s.latentPop = onPop;
        s.latentCure = onCure;
        return true;
    }

    /** 잠복 풂(정화 소모품). onCure 가 있으면 부름. */
    public void cure(Player p) {
        PState s = get(p);
        if (s.latent == null) return;
        Consumer<Player> c = s.latentCure;
        s.latent = null;
        s.latentPop = s.latentCure = null;
        if (c != null) {
            try {
                c.accept(p);
            } catch (RuntimeException ex) {
                game.plugin.getLogger().warning("[사람] 잠복 정화 오류: " + ex);
            }
        }
    }

    /** 정화 소모품 전체: 잠복, 덮기, 느려짐(묶임 포함)을 지움. */
    public void purify(Player p) {
        cure(p);
        uncover(p);
        clearSlows(p);
        cured(p, "cleanse");
    }

    /** 지금 판 전체 잠복 수. */
    public int latentCount() {
        int n = 0;
        for (PState s : states.values()) if (s.latent != null && !s.latentFree) n++;
        return n;
    }

    public int latentCap() {
        // 원정 라운드 종류(붙은 것 같은)가 상한을 바꿀 수 있다(trip_rounds: ask latentCap)
        return game.trip.askInt("latentCap", game.plugin.getConfig().getInt("latentCap", 4));
    }

    /** 비옷: 이번 공격을 막았으면 true(하나 씀, 같은 틱의 다음 grab, latent, cover 도 막음). */
    public boolean coat(Player p, PState s) {
        long now = game.now();
        if (s.coatTornAt == now) return true;
        if (s.raincoat <= 0) return false;
        s.raincoat--;
        s.coatTornAt = now;
        Props.sound(p.getLocation(), null, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.9f, 0.7f, 1f);
        return true;
    }

    // ------------------------------------------------------------------ 쓰러짐, 죽음, 갇힘, 부활

    /** 체력 0(서 있는 사람만): 바닥 좌석, 바닥 원, 이름과 남은 초, 출혈 12초(+bleedBonus). 붙잡힘, 덮기, 머리 부착, 느려짐은 풀림. */
    public void down(Player p, Gabe by, String causeKey) {
        PState s = get(p);
        if (s.life != Life.ALIVE) return;
        long now = game.now();
        Location at = rig(p).seat != null ? rig(p).seat.getLocation() : p.getLocation();
        endHelp(s);
        if (s.grabbedBy != null) releaseQuiet(p, s, "release");
        if (s.headItem != null) attachHead(p, null);
        uncover(p);
        clearSlows(p);
        s.hp = 0;
        s.life = Life.DOWNED;
        s.lifeAt = now;
        s.downs++;
        s.bleedUntil = now + BLEED + Math.max(0, s.bleedBonus);
        s.reviver = null;
        if (by != null) s.lastBy = by.sp;
        else if (s.lastHurtAt != now) s.lastBy = null;
        if (causeKey != null) s.lastCause = causeKey;
        else if (s.lastHurtAt != now) s.lastCause = null;
        // 마지막으로 쓰러뜨린 종(LASTKILLER), 태운 좌석과 띄움은 끝
        s.lastDownBy = s.lastBy;
        s.lastDownAt = now;
        s.rideUntil = 0;
        launches.remove(p.getUniqueId());
        seatAt(p, ground(at), false);
        marks(p, s);
        game.hud.cause(p, s.lastBy, s.lastCause);
        Props.sound(p.getLocation(), null, Sound.ENTITY_PLAYER_BIG_FALL, 1f, 0.8f, 1f);
        log("쓰러짐 " + p.getName() + " 원인 " + (s.lastBy == null ? "-" : s.lastBy.name()) + " " + (s.lastCause == null ? "-" : s.lastCause));
    }

    /** 출혈 끝(또는 시험 죽이기): 죽음. 숨김, 날기, 광장 위 관전, 죽음 결과 굴림. */
    private void bleedOut(Player p, PState s) {
        Location at = spot(p);
        endHelpOn(s);
        unseat(p, false);
        removeMarks(p);
        s.life = Life.DEAD;
        s.lifeAt = game.now();
        s.bleedUntil = 0;
        s.latent = null;
        s.latentPop = s.latentCure = null;
        s.holdSkips = 0;
        rollDeathResult(p, s, at);
        deathBegan(p, s);
        spectate(p, at.clone().add(0, 8, 0));
        lookPitch(p, 50);
        log("죽음 " + p.getName() + " 결과 " + (s.deathResult == null ? "-" : s.deathResult.name()));
    }

    /** 한 번의 죽음 = 결과 하나: 업은(G38) 40, 총 주운(G52) 30, 사람 탈(G50) 30 가운데 묶음에 들고 구현되고 그 웨이브에 나올 수 있는 것만. */
    private void rollDeathResult(Player p, PState s, Location at) {
        s.deathResult = null;
        s.deathMob = null;
        Species pick = forcedResult.remove(p.getUniqueId());
        if (pick == null) {
            Species[] sp = {Species.G38, Species.G52, Species.G50};
            int[] w = {40, 30, 30};
            int total = 0;
            boolean[] ok = new boolean[sp.length];
            // 원정: 죽으면 늘 하나 선다(묶음, 웨이브 단계와 무관: 죽은 사람이 된 게이브)
            boolean always = game.trip.paidRevive();
            for (int i = 0; i < sp.length; i++) {
                ok[i] = sp[i].implemented() && (always || game.director.inBundle(sp[i]) && game.wave >= sp[i].tier);
                if (ok[i]) total += w[i];
            }
            if (total <= 0) return;
            int roll = rnd.nextInt(total);
            for (int i = 0; i < sp.length; i++) {
                if (!ok[i]) continue;
                if (roll < w[i]) {
                    pick = sp[i];
                    break;
                }
                roll -= w[i];
            }
        } else if (pick == Species.G01) {
            return; // 시험 훅 '결과 없음'
        }
        if (pick == null) return;
        Vector v = at.toVector();
        try {
            v.setY(game.mobs.flow.floor(v.getX(), v.getZ()) + pick.band.hover);
        } catch (RuntimeException ex) {
            v.setY(at.getY() + pick.band.hover);
        }
        Gabe g = game.mobs.spawn(pick, v, false);
        if (g == null) return;
        s.deathResult = pick;
        s.deathMob = g;
        if (g instanceof DeathBorn db) {
            try {
                db.bornFrom(p, s, at.clone());
            } catch (RuntimeException ex) {
                game.plugin.getLogger().warning("[사람] " + pick.name() + " bornFrom 오류: " + ex);
            }
        }
    }

    /** 먹힘(서 있거나 쓰러진 사람): 숨기고 그 게이브 위 좌석에서 따라다니며 내려다봄. 그 개체가 죽으면 freeFrom 으로 상점 앞 부활(손실 없음). */
    public void trap(Player p, Gabe eater) {
        PState s = get(p);
        if (s.life != Life.ALIVE && s.life != Life.DOWNED) return;
        long now = game.now();
        Location at = spot(p);
        endHelp(s);
        endHelpOn(s);
        if (s.grabbedBy != null) releaseQuiet(p, s, "release");
        if (s.headItem != null) attachHead(p, null);
        uncover(p);
        clearSlows(p);
        unseat(p, false);
        removeMarks(p);
        s.life = Life.TRAPPED;
        s.eater = eater;
        s.lifeAt = now;
        s.bleedUntil = 0;
        s.latent = null;
        s.latentPop = s.latentCure = null;
        s.deathResult = eater != null ? eater.sp : null;
        s.deathMob = eater;
        s.holdSkips = 0;
        hide(p);
        p.setInvulnerable(true);
        Location seat = eater != null ? eater.loc().add(0, TRAP_UP, 0) : at.clone().add(0, TRAP_UP, 0);
        seat.setYaw(0);
        seat.setPitch(0);
        seatAt(p, seat, false);
        lookPitch(p, 75);
        log("갇힘 " + p.getName() + " " + (eater == null ? "-" : eater.toString()));
    }

    /**
     * 개체가 죽음(Mobs.onDeath 가 모든 죽음에 부름, 종이 직접 불러도 됨). 거기 갇힌 사람은 상점 앞 부활(재화 손실 없음). 그 개체가 죽음 결과로
     * 생긴 업은(G38), 총 주운(G52) 개체면 그 죽은 사람도 상점 앞 부활(손실 없음). 단 웨이브 끝 정리, 막힘 정리로 치워진 것(noReward,
     * 또는 쉬는 시간 첫 틱의 죽음)은 잡힌 것이 아니라 살아남은 것으로 본다: G38 이면 그 사람은 다음 쉬는 시간 부활을 한 번 건너뛰고,
     * G52 면 보통 죽음처럼 쉬는 시간에 손실 부활. 그래서 G38, G52 종은 noReward 를 켜지 말 것.
     */
    public void freeFrom(Gabe eater) {
        if (eater == null) return;
        // 보스는 늘 noReward 라 그것만 보면 보스가 죽거나 뱉을 때도 '정리로 치워짐'이 된다(boss_b2 보고): 보스는 Boss.cleaned 로 본다
        boolean swept = eater instanceof kr.gave.mob.boss.Boss boss ? boss.cleaned : eater.noReward;
        boolean cleaned = swept || game.state == Game.State.BREAK && game.stateAt == game.now();
        for (PState s : new ArrayList<>(states.values())) {
            if (s == phantom) continue;
            // 갇힌 채 나간 사람(상태는 DEAD + offline, 먹은 개체는 deathMob 에 남음)도 그 개체에 갇힌 사람으로 본다(잡히면 돌아올 때 손실 없이 선다)
            boolean away = s.offline && s.quitLife == Life.TRAPPED && s.deathMob == eater;
            boolean trapped = away || s.life == Life.TRAPPED && s.eater == eater;
            // 갇힘 이어 가기(FREE): 정리로 치워진 개체(웨이브 끝)에 갇힌 사람은 갇힌 채 둔다(가둔 종이 잇는다)
            if (trapped && s.trapHold && cleaned) {
                s.eater = null;
                if (s.trapLostAt < 0) s.trapLostAt = game.now();
                continue;
            }
            boolean paid = game.trip.paidRevive();
            boolean carried = !trapped && s.life == Life.DEAD && s.deathMob == eater && (paid || carrier(s.deathResult));
            if (!trapped && !carried) continue;
            if (paid && carried) {
                // 원정: 내 얼굴을 한 게이브가 잡혀도 돌아오지 않는다. 사람이 잡았으면 꺼내는 값이 싸지고(얼굴 깎임), 정리로 치워졌으면 다음 라운드에 다시 선다
                s.deathMob = null;
                if (!cleaned) s.faceKilled = true;
                log("죽은 사람의 게이브 " + (cleaned ? "정리됨 " : "잡힘 ") + s.name);
                continue;
            }
            if (paid && trapped && cleaned) {
                // 원정: 먹은 개체가 웨이브 끝 정리로 치워짐 = 라운드가 끝날 때까지 못 구함 → 죽음으로 침
                Player tp = Bukkit.getPlayer(s.id);
                if (tp != null && !s.offline) {
                    trappedToDead(tp);
                    continue;
                }
                if (away) {
                    // 나간 사람도 같게: 돌아오면 죽은 채(꺼내는 값 절반)
                    s.deathMob = null;
                    s.trappedDeath = true;
                    continue;
                }
            }
            if (carried && cleaned) {
                s.deathMob = null;
                if (s.deathResult == Species.G38 && s.holdSkips == 0) s.holdSkips = 1;
                log("살아남은 " + s.deathResult.name() + " 정리됨 " + s.name + " hold=" + s.holdSkips);
                continue;
            }
            Player p = Bukkit.getPlayer(s.id);
            if (p == null || s.offline) {
                // 나간 사람: 돌아오면 바로(웨이브 중이라도) 손실 없이 부활
                s.eater = null;
                s.deathMob = null;
                s.freeRevive = true;
                s.freedAway = true;
                continue;
            }
            reviveAtShop(p, false);
        }
    }

    /** 잡으면 그 죽은 사람이 풀려나는 죽음 결과(업은, 총 주운). */
    private static boolean carrier(Species sp) {
        return sp == Species.G38 || sp == Species.G52;
    }

    /** 먹은 개체가 다른 개체로 바뀜(겹얼굴 갈라짐 등): 갇힌 사람을 to 로 옮김. */
    public void transferTrapped(Gabe from, Gabe to) {
        for (PState s : states.values()) {
            if (s.eater == from) s.eater = to;
            if (s.deathMob == from) s.deathMob = to;
        }
    }

    /** 이 개체에 갇힌 사람(실뭉치, 겹얼굴 등이 이름을 띄울 때). */
    public List<Player> trappedIn(Gabe g) {
        List<Player> out = new ArrayList<>();
        for (PState s : states.values()) {
            if (s.life != Life.TRAPPED || s.eater != g || s.offline) continue;
            Player p = Bukkit.getPlayer(s.id);
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * 쉬는 시간 공짜 부활이 지금 도나(2026-11-12 사용자 "라운드 끝나면 모두 부활"): 쉬는 시간이나 준비 중이고, 발사 차례(B5 엔딩 뒤:
     * 원정 Launch 가 launchPhase 로 답하거나 엔딩 라운드 35 의 쉬는 시간)가 아님. 판 끝(OVER)에는 안 돈다.
     */
    public boolean breakReviveNow() {
        if (game.state != Game.State.BREAK && game.state != Game.State.PREP) return false;
        if (game.trip.ask("launchPhase") != null) return false;
        return !(game.trip.has("Launch") && game.wave == kr.gave.trip.Trip.FINAL_ROUND);
    }

    /**
     * 쉬는 시간 공짜 부활(Game.beginBreak 가 쉬는 시간 첫 틱에 판에 든 모두에게, 쉬는 시간에 돌아온 사람에게 부름): 죽은, 갇힌, 쓰러진 사람이
     * 상점 앞에서 체력 가득으로 선다. 원정 꺼내기 값, 업은 개체의 쉬어 가기, 갇힘 이어 가기를 따지지 않고, 재화 손실도 없고 꺼내진 횟수(꺼내는 값 오름)도
     * 세지 않는다. 죽음 결과로 선 '내 얼굴 게이브'는 치우지 않는다(그냥 남은 게이브). 원정 모금에 낸 돈은 꺼내기 부품이 돌려준다(Revive.freed).
     * 기다리던 사람은 판에 든다. 돌려주는 값: 세웠나.
     */
    public boolean reviveFree(Player p) {
        PState s = get(p);
        if (s.offline) return false;
        if (s.life == Life.WAITING) {
            activate(p, s);
            return true;
        }
        if (s.life != Life.DEAD && s.life != Life.TRAPPED && s.life != Life.DOWNED) return false;
        Life was = s.life;
        cleanup(p, s);
        s.life = Life.ALIVE;
        s.lifeAt = game.now();
        s.hp = s.maxHp;
        s.eater = null;
        s.deathMob = null;
        s.holdSkips = 0;
        s.freeRevive = s.freedAway = s.resumeHp = false;
        s.revivePool = 0;
        s.faceKilled = s.trappedDeath = false;
        if (game.guns.arms(p) == null) game.guns.equip(p);
        p.teleport(shopSpot());
        if (was == Life.DEAD) {
            kr.gave.trip.Revive rv = game.trip.revive();
            if (rv != null) rv.freed(p);
        }
        log("쉬는 시간 부활 " + p.getName() + " " + was);
        return true;
    }

    /**
     * 상점 앞 부활(보스 보상, 갇힌 개체가 잡힘, 쉬는 시간에 들어옴 등. 쉬는 시간 첫 틱의 모두 부활은 {@link #reviveFree}). 재화 손실은 없다
     * (2026-11-12 사용자 "라운드 끝나면 모두 부활": 옛 판의 '재화 20% 잃음'을 없앰. penalty 는 부르는 쪽 호환으로만 남음).
     * 기다리던 사람은 판에 들어감(기본 총, 처음 합류면 팀 평균 재화의 절반). 업은 게이브(G38)가 웨이브 끝까지 살아남은 죽은 사람
     * (그 개체가 아직 살아 있거나 웨이브 끝 정리로 치워짐)은 쉬는 시간 시작 부활을 한 번 건너뜀(다음 웨이브를 쉼: 쉬는 시간 공짜 부활이 도는 동안은 안 씀).
     */
    public void reviveAtShop(Player p, boolean penalty) {
        PState s = get(p);
        long now = game.now();
        // 나간 사람은 세우지 않는다(돌아오면 resume 이 잇는다): 오프라인 상태가 서 있는 사람이 되면 전멸해도 판이 안 끝난다
        if (s.offline) return;
        if (s.life == Life.WAITING) {
            activate(p, s);
            return;
        }
        // 원정: 죽은 사람은 저절로(쉬는 시간, 보스 처치 보상, 업은 개체가 잡힘) 돌아오지 않는다. 다른 사람이 값을 내면 reviveAtTruck
        if (s.life == Life.DEAD && game.trip.paidRevive()) return;
        if (s.life == Life.DEAD && game.state == Game.State.BREAK && game.stateAt == now) {
            boolean carrierAlive = s.deathResult == Species.G38 && s.deathMob != null && !s.deathMob.dead;
            if (s.holdSkips == 1 || carrierAlive && s.holdSkips == 0) {
                s.holdSkips = 2;
                log("업힌 채 쉼 " + p.getName());
                return;
            }
        }
        if (s.freeRevive) penalty = false;
        cleanup(p, s);
        s.life = Life.ALIVE;
        s.lifeAt = now;
        s.hp = s.maxHp;
        s.eater = null;
        s.deathMob = null;
        s.holdSkips = 0;
        s.freeRevive = s.freedAway = s.resumeHp = false;
        if (game.guns.arms(p) == null) game.guns.equip(p);
        p.teleport(shopSpot());
        log("상점 앞 부활 " + p.getName());
    }

    /**
     * 원정 꺼내기(kr.gave.trip.Revive 가 모금이 차면 부름): 죽은 사람이 트럭 짐칸 뒤에 내린다. 체력은 Trip.Tune.REVIVE_HP, REVIVE_SAFE 틱 무적,
     * 총과 증강은 그대로, 재화 손실 없음. 그 사람 얼굴을 한 게이브가 살아 있으면 치운다(보상 없음). payer 는 마지막으로 낸 사람(없으면 null).
     */
    public boolean reviveAtTruck(Player p, Player payer) {
        PState s = get(p);
        if (s.life != Life.DEAD || s.offline) return false;
        long now = game.now();
        Gabe mob = s.deathMob;
        cleanup(p, s);
        s.life = Life.ALIVE;
        s.lifeAt = now;
        s.hp = Math.max(1, s.maxHp * kr.gave.trip.Trip.Tune.REVIVE_HP);
        s.eater = null;
        s.deathMob = null;
        s.holdSkips = 0;
        s.freeRevive = s.freedAway = s.resumeHp = false;
        s.revived++;
        s.revivePool = 0;
        s.faceKilled = s.trappedDeath = false;
        s.safeUntil = now + (long) kr.gave.trip.Trip.Tune.REVIVE_SAFE;
        if (game.guns.arms(p) == null) game.guns.equip(p);
        p.teleport(shopSpot());
        if (mob != null && !mob.dead && !mob.removed) {
            mob.noReward = true;
            mob.remove();
        }
        if (payer != null) get(payer).revives++;
        Props.sound(p.getLocation(), null, Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.8f, 1f);
        log("트럭에서 꺼냄 " + p.getName() + " 낸 사람 " + (payer == null ? "-" : payer.getName()) + " 꺼내진 횟수 " + s.revived);
        return true;
    }

    /**
     * 원정: 죽은 사람의 얼굴을 한 게이브를 at 에 다시 세움(라운드가 바뀌어도 따라온다). 죽은 사람이 아니거나 이미 살아 있는 것이 있으면 null.
     */
    public Gabe respawnDeathMob(Player p, Location at) {
        PState s = get(p);
        if (s.life != Life.DEAD || s.deathMob != null && !s.deathMob.dead && !s.deathMob.removed) return null;
        rollDeathResult(p, s, at);
        return s.deathMob;
    }

    /** 쓰러진 사람을 그 자리에서 일으킴(동료 돕기 3초 끝, 시험). 체력 30%. */
    public void reviveHere(Player p, Player helper) {
        PState s = get(p);
        if (s.life != Life.DOWNED) return;
        endHelpOn(s);
        Location seat = rig(p).seat != null ? rig(p).seat.getLocation() : p.getLocation();
        unseat(p, false);
        removeMarks(p);
        s.life = Life.ALIVE;
        s.lifeAt = game.now();
        s.hp = Math.max(1, s.maxHp * REVIVE_HP);
        s.bleedUntil = 0;
        Location up = ground(seat);
        teleportKeepLook(p, up);
        if (helper != null) get(helper).revives++;
        wallet.helped(helper, p);
        Props.sound(up, null, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f, 1f);
        log("일어남 " + p.getName() + " 도운 사람 " + (helper == null ? "-" : helper.getName()));
    }

    /** 기다림 → 판에 듦(웨이브 시작, 쉬는 시간, 준비 중 합류). */
    private void activate(Player p, PState s) {
        cleanup(p, s);
        s.life = Life.ALIVE;
        s.lifeAt = game.now();
        // 서 있다가 나갔던 사람은 그때 체력 그대로(나갔다 들어와 체력을 채우지 못하게)
        s.hp = s.resumeHp ? Math.max(1, Math.min(s.maxHp, s.hp)) : s.maxHp;
        s.resumeHp = false;
        if (s.joinBonus) {
            s.joinBonus = false;
            // 따라잡기 몫: 판에 든 다른 사람들의 판 누적 벌이 평균 x Wallet.JOIN(이미 번 돈에서 셈: 배율을 또 곱하지 않음)
            int b = wallet.joinGrant(s);
            if (b > 0) addMoneyRaw(p, b);
        }
        if (game.guns.arms(p) == null) game.guns.equip(p);
        p.teleport(shopSpot());
        log("판에 듦 " + p.getName());
    }

    private void toWaiting(Player p, PState s) {
        cleanup(p, s);
        s.life = Life.WAITING;
        s.lifeAt = game.now();
        Location c = Plaza.center(game.world).add(0, 14, 0);
        spectate(p, c);
        lookPitch(p, 55);
    }

    /** 죽은 채로 돌아옴: 관전(죽음 결과, 업은 개체는 그대로), 다음 쉬는 시간에 보통 죽음처럼 부활. */
    private void toDead(Player p, PState s) {
        cleanup(p, s);
        s.life = Life.DEAD;
        s.lifeAt = game.now();
        spectate(p, Plaza.center(game.world).add(0, 14, 0));
        lookPitch(p, 55);
    }

    // ------------------------------------------------------------------ 돕기, 아군 사격, 끌고 가기

    /** 좌클릭(돕기): 탈출 연타, 일으키기, 떼기, 짜내기(잠복, 등 뒤), 종 상호작용(Gabe.helped), 상점 순. */
    public void help(Player p) {
        if (!game.inGame(p)) return;
        PState s = states.get(p.getUniqueId());
        if (s == null) return;
        long now = game.now();
        if (s.life == Life.ALIVE && s.grabbedBy != null) {
            escapeClick(p, s);
            return;
        }
        // 원정: 부품이 좌클릭을 가로챔(표지판 투표: 죽은 사람도 표를 낸다)
        if (game.trip.help(p)) return;
        // 원정: 죽은 사람의 좌클릭은 '살려 달라' 신호(30초에 한 번)
        if (s.life == Life.DEAD && game.trip.paidRevive()) {
            kr.gave.trip.Revive rv = game.trip.revive();
            if (rv != null) rv.call(p);
            return;
        }
        if (s.life != Life.ALIVE || s.helping != null) return;
        if (now - s.lastHelpAt < 3) return;
        s.lastHelpAt = now;
        Player t = lookedMate(p, HELP_REACH, o -> o.life == Life.DOWNED && o.reviver == null);
        if (t != null) {
            startHelp(p, s, t, PState.Help.REVIVE);
            return;
        }
        Player v = lookedMate(p, HELP_REACH, o -> o.life == Life.ALIVE);
        if (v != null) {
            if (detach(p, v)) return;
            PState vs = get(v);
            if (vs.latent != null && vs.reviver == null && behind(p, v)) {
                startHelp(p, s, v, PState.Help.SQUEEZE);
                return;
            }
        }
        // 돕기 갈고리(HELPHOOK: 동료를 보고 돕기, HELPOBJ: 게이브 아닌 돕기 대상). v = 바라본 서 있는 동료(없으면 null)
        if (!helpHooks.isEmpty()) {
            for (Map.Entry<Object, HelpHook> e : new ArrayList<>(helpHooks.entrySet())) {
                try {
                    if (e.getValue().help(p, v)) return;
                } catch (RuntimeException ex) {
                    if (hookErr.add("help:" + e.getKey())) game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[사람] 돕기 갈고리 " + e.getKey() + " 오류", ex);
                }
            }
        }
        Gabe g = game.mobs.looked(p, 3.0);
        if (g != null && game.mobs.helped(g, p)) return;
        if (game.crates.tryOpen(p)) return;
        game.shop.helpOpen(p);
    }

    private void escapeClick(Player p, PState s) {
        s.escapeHits++;
        Guns.kick(p, (rnd.nextFloat() - 0.5f) * 2f, (rnd.nextFloat() - 0.5f) * 1.5f);
        if (s.escapeHits >= s.escapeNeed) {
            releaseBy(p, s, p, "escape");
            Props.sound(p.getLocation(), null, Sound.ENTITY_SLIME_SQUISH, 0.8f, 1.3f, 1f);
            log("탈출 " + p.getName());
        }
    }

    private void startHelp(Player p, PState s, Player t, PState.Help kind) {
        PState ts = get(t);
        long now = game.now();
        double mul = Math.max(0.2, s.reviveMul);
        s.helping = t.getUniqueId();
        s.helpKind = kind;
        s.helpStart = now;
        s.helpUntil = now + Math.max(5, Math.round((kind == PState.Help.REVIVE ? REVIVE : SQUEEZE) / mul));
        ts.reviver = p.getUniqueId();
        ts.reviveUntil = s.helpUntil;
        Props.sound(t.getLocation(), null, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.1f, 1f);
    }

    /** 돕는 사람 쪽 채널 확인(거리, 시선, 시야), 다 차면 일으키기 또는 짜내기. */
    private void helpTick(Player p, PState s, long now) {
        if (s.helping == null) return;
        PState ts = states.get(s.helping);
        Player t = Bukkit.getPlayer(s.helping);
        boolean ok = s.life == Life.ALIVE && s.grabbedBy == null && ts != null && t != null && !ts.offline && t.getWorld() == p.getWorld()
            && p.getUniqueId().equals(ts.reviver);
        if (ok) ok = s.helpKind == PState.Help.REVIVE ? ts.life == Life.DOWNED : ts.life == Life.ALIVE && ts.latent != null;
        if (ok) {
            Vector body = bodyPoint(t, ts);
            Location l = p.getLocation();
            ok = Math.hypot(body.getX() - l.getX(), body.getZ() - l.getZ()) <= HELP_REACH + 0.6 && facing(p, body) >= Math.cos(Math.toRadians(55));
            if (ok && (now - s.helpStart) % 5 == 0) ok = p.hasLineOfSight(t);
            if (ok && s.helpKind == PState.Help.SQUEEZE) ok = behind(p, t);
        }
        if (!ok) {
            endHelp(s);
            return;
        }
        if (now < s.helpUntil) return;
        PState.Help kind = s.helpKind;
        endHelp(s);
        if (kind == PState.Help.REVIVE) reviveHere(t, p);
        else {
            cure(t);
            cured(t, "squeeze");
            wallet.helped(p, t);
            Props.sound(t.getLocation(), null, Sound.ENTITY_SLIME_SQUISH, 1f, 0.7f, 1f);
            log("짜냄 " + t.getName() + " 도운 사람 " + p.getName());
        }
    }

    /** 이 사람이 돕던 채널을 끊음. */
    private void endHelp(PState s) {
        if (s.helping == null) return;
        PState ts = states.get(s.helping);
        if (ts != null && s.id.equals(ts.reviver)) {
            ts.reviver = null;
            ts.reviveUntil = 0;
        }
        s.helping = null;
        s.helpKind = null;
    }

    /** 이 사람을 돕던 채널을 끊음. */
    private void endHelpOn(PState s) {
        if (s.reviver == null) return;
        PState hs = states.get(s.reviver);
        if (hs != null && s.id.equals(hs.helping)) {
            hs.helping = null;
            hs.helpKind = null;
        }
        s.reviver = null;
        s.reviveUntil = 0;
    }

    /** 동료에게 붙은 게이브 떼기(뗄 수 있는 붙잡힘, 머리 부착, 덮기). 뗀 것이 있으면 true. */
    public boolean detach(Player helper, Player victim) {
        PState v = get(victim);
        boolean did = false;
        if (v.grabbedBy != null && v.grabDetach) {
            releaseBy(victim, v, helper, "detach");
            did = true;
        }
        if (v.headItem != null) {
            Gabe by = v.headBy;
            v.headItem = null;
            v.headBy = null;
            sendHead(victim, null);
            if (by instanceof Detachable d) {
                try {
                    d.detached(victim, helper, "detach");
                } catch (RuntimeException ex) {
                    game.plugin.getLogger().warning("[사람] detached 오류: " + ex);
                }
            }
            did = true;
        }
        if (v.cover != null && v.coverUntil > game.now() && v.coverDetach) {
            uncover(victim);
            did = true;
        }
        if (did) {
            Props.sound(victim.getLocation(), null, Sound.ENTITY_SLIME_SQUISH, 0.9f, 1f, 1f);
            log("떼어 냄 " + victim.getName() + " 도운 사람 " + (helper == null ? "-" : helper.getName()));
            wallet.helped(helper, victim);
        }
        return did;
    }

    /**
     * 끌고 가는 종: 붙잡힌(또는 쓰러진) 사람을 보이지 않는 좌석에 태워 at(좌석 자리, 사람 발은 그보다 0.6 아래)으로 옮김. 매 틱 불러도 됨
     * (2틱 보간). 붙잡힘이 풀리면 좌석에서 내려 그 자리 바닥에 선다.
     */
    public void carry(Player p, Vector at) {
        PState s = get(p);
        if (s.life != Life.ALIVE && s.life != Life.DOWNED) return;
        Rig r = rig(p);
        Location to = new Location(game.world, at.getX(), at.getY(), at.getZ());
        if (r.seat == null || !r.seat.isValid()) {
            seatAt(p, to, s.life == Life.ALIVE);
            return;
        }
        if (s.life == Life.ALIVE) r.carried = true;
        if (r.seat.getLocation().distanceSquared(to) > 1e-4) r.seat.teleport(to);
        if (!r.seat.getPassengers().contains(p)) mount(r.seat, p);
    }

    /** 아군 탄이 동료에게 닿음: 피해 없음, 맞은 사람 사격 0.3초 끊김, 화면 살짝 흔들림. 쓰러진 동료면 출혈 3초 줄어듦(0.5초에 한 번). */
    public void friendlyHit(Player shooter, Player victim) {
        if (victim == null || shooter == victim) return;
        PState v = get(victim);
        long now = game.now();
        if (v.life == Life.DOWNED) {
            if (now - v.lastFriendlyAt >= 10) {
                v.lastFriendlyAt = now;
                v.bleedUntil -= 60;
            }
            return;
        }
        if (!v.standing()) return;
        v.gunLockUntil = Math.max(v.gunLockUntil, now + FRIENDLY_LOCK);
        if (now - v.lastFriendlyAt >= 3) {
            v.lastFriendlyAt = now;
            Guns.kick(victim, (rnd.nextFloat() - 0.5f) * 3f, -0.8f - rnd.nextFloat());
        }
    }

    // ------------------------------------------------------------------ 재화

    /**
     * 재화 벌이(음수면 빼기). 양수면 돈 배율 {@link #moneyMul} 을 곱해(반올림, 줄지 않음) earned 에 쌓고 Hud.money 로 +n 을 띄운다
     * (부르는 쪽이 Hud.money 를 따로 부르지 말 것). 사람이 버는 돈은 모두 여기로 온다. 증강 '빚'이 남은 동안 절반(Wallet.earnMul, 남는 쪽 올림:
     * 원본 규칙). 증강이 주는 돈은 {@link #addBonus}, 옮기기는 {@link #addMoneyRaw} 라 빚에 안 걸린다.
     */
    public void addMoney(Player p, int amount) {
        if (amount > 0) {
            amount = (int) Math.max(amount, Math.min(Integer.MAX_VALUE / 4, Math.round(amount * moneyMul * INCOME)));
            double k = wallet.earnMul(p.getUniqueId());
            if (k < 1) amount = (int) Math.ceil(amount * k - 1e-9);
        }
        credit(p, get(p), amount);
    }

    /** 증강이 주는 돈(이자, 단골, 내기, 장물, 빚의 큰돈: AugRules.grant, 지갑의 물주 덤): 돈 배율은 곱하고 빚의 반값에는 안 걸린다(원본 규칙). */
    public void addBonus(Player p, int amount) {
        if (amount <= 0) return;
        credit(p, get(p), (int) Math.max(amount, Math.min(Integer.MAX_VALUE / 4, Math.round(amount * moneyMul * INCOME))));
    }

    /**
     * 옮기기(돌려받기, 합류 몫, 시험): 돈 배율 없이 더함(음수면 빼기), 빚의 반값에도 안 걸림. 양수면 earned 에 쌓고 +n 을 띄운다. 벌이가 아니라서
     * 증강에 알린다(Augments.moved: 같은 틱에 쓴 돈이 '상점에서 씀' 셈에서 덜 잡히지 않게).
     */
    public void addMoneyRaw(Player p, int amount) {
        PState s = get(p);
        credit(p, s, amount);
        game.augments.moved(s.id, amount);
    }

    /** 나간 사람에게도 닿는 옮기기(모금 돌려주기): 판 상태에 바로 더한다(+n 표시는 접속 중일 때만). 판 상태가 없으면 false. */
    public boolean addMoneyRaw(UUID id, int amount) {
        PState s = states.get(id);
        if (s == null) return false;
        credit(s.offline ? null : Bukkit.getPlayer(id), s, amount);
        game.augments.moved(id, amount);
        return true;
    }

    private void credit(Player p, PState s, int amount) {
        s.money += amount;
        if (amount > 0) {
            s.earned += amount;
            if (p != null) game.hud.money(p, amount);
        }
    }

    /** 판에 든 모두에게. */
    public void teamMoney(int amount) {
        for (Player p : inPlay()) addMoney(p, amount);
    }

    /** 값을 낼 수 있으면 빼고 true. */
    public boolean spend(Player p, int cost) {
        PState s = get(p);
        if (cost < 0 || s.money < cost) return false;
        s.money -= cost;
        return true;
    }

    // ------------------------------------------------------------------ 판 시작, 끝

    /** 판에 넣기(체력, 재화, 판 기록, 증강, 상태 이상 초기화. 총 지급은 Game). */
    public void join(Player p) {
        PState s = get(p);
        cleanup(p, s);
        s.life = Life.ALIVE;
        s.lifeAt = game.now();
        s.hp = s.maxHp = 100;
        s.armor = 1.0;
        s.moveMul = 1.0;
        s.bleedBonus = 0;
        s.reviveMul = 1.0;
        s.raincoat = 0;
        s.money = 0;
        s.earned = 0;
        s.kills = s.downs = s.revives = 0;
        s.dealt = 0;
        s.augs.clear();
        s.immuneUntil.clear();
        s.deathResult = null;
        s.deathMob = null;
        s.eater = null;
        s.lastBy = null;
        s.lastCause = null;
        s.lastDownBy = null;
        s.lastDownAt = -1;
        s.offline = false;
        s.quitLife = null;
        s.joinBonus = false;
        s.holdSkips = 0;
        s.freeRevive = s.freedAway = s.resumeHp = false;
        // 원정 칸도 판마다 새로(꺼내진 횟수가 남으면 다음 판 꺼내는 값이 처음부터 비싸다)
        s.deaths = s.revived = 0;
        s.deadRound = 0;
        s.revivePool = 0;
        s.faceKilled = s.trappedDeath = false;
        s.callAt = -100000;
        s.safeUntil = 0;
        s.revivePaid = s.repairPaid = 0;
        s.name = p.getName();
        fresh(p);
    }

    public void toLobby(Player p) {
        PState s = get(p);
        cleanup(p, s);
        s.life = Life.LOBBY;
        s.lifeAt = game.now();
        s.hp = s.maxHp;
        applyMove(p, s, game.now());
        tabTick(p, s);
        fresh(p);
        if (game.inGame(p)) {
            // 판에서 얻은 것(총, 소모품 그림)과 걸린 것(어둠, 실명)을 대기실까지 끌고 오지 않는다. 크리에이티브(맵 짓는 사람)는 그대로
            p.getInventory().clear();
            p.clearActivePotionEffects();
        }
    }

    /**
     * 나감(Gave.onQuit). 대기실이면 상태를 지우고, 판 중이면(판 끝 연출 중에도: 그 뒤에 쓰는 판 기록에 남게) 남겨 둔다(재화, 증강, 기록, 죽음 결과): 판 계산에서 빼려고 DEAD 로 두고
     * offline 표시, 나갈 때 상태는 quitLife. 돌아오면 그 상태에서 이어 간다(arrive): 서 있던 사람은 그 체력 그대로(웨이브 중이면 다음
     * 쉬는 시간이나 웨이브까지 기다림), 쓰러졌거나 죽었던 사람은 죽은 채(쉬는 시간이면 바로 손실 부활), 갇혔던 사람은 손실 없이.
     * 붙잡힘, 머리 부착은 Detachable 'release' 로 알리고 푼다.
     */
    public void forget(UUID id) {
        PState s = states.get(id);
        if (s == null) return;
        Player p = Bukkit.getPlayer(id);
        endHelp(s);
        endHelpOn(s);
        if (p != null) {
            if (s.grabbedBy != null) releaseQuiet(p, s, "release");
            if (s.headItem != null) attachHead(p, null);
            unseat(p, false);
            removeMarks(p);
            p.setInvulnerable(false);
            p.setWalkSpeed(BASE_WALK);
            AttributeInstance j = p.getAttribute(Attribute.JUMP_STRENGTH);
            if (j != null) j.removeModifier(ROOT_JUMP);
            p.playerListName(null);
        }
        Rig r = rigs.remove(id);
        if (r != null) removeRigEntities(r);
        if (game.state == Game.State.LOBBY || s.life == Life.LOBBY || s == phantom) {
            states.remove(id);
            return;
        }
        if (s.offline) return;
        // 원정: 죽은 채 나가면 그 사람 모금에 낸 돈을 낸 사람들에게 바로 돌려준다(돌아올 때까지 묶이지 않게)
        kr.gave.trip.Revive rv = game.trip.revive();
        if (rv != null) rv.refund(id);
        s.offline = true;
        s.quitLife = s.life;
        s.resumeHp = s.life == Life.ALIVE;
        // 갇힌 사람은 쉬는 시간에 손실 없이 부활하니 나가도 같게
        if (s.life == Life.TRAPPED) s.freeRevive = true;
        s.life = Life.DEAD;
        s.grabbedBy = null;
        s.eater = null;
        s.latent = null;
        s.latentPop = s.latentCure = null;
        s.cover = null;
        s.headItem = null;
        s.headBy = null;
        s.slowUntil.clear();
        s.slowPct.clear();
        s.rootUntil = 0;
        log("나감(상태 남김) " + s.name + " " + s.quitLife);
    }

    public void clear() {
        for (PState s : new ArrayList<>(states.values())) {
            Player p = Bukkit.getPlayer(s.id);
            if (p != null && p.isOnline() && !s.offline) cleanup(p, s);
            s.life = Life.LOBBY;
            if (s.offline || s == phantom || p == null) states.remove(s.id);
        }
        for (Rig r : rigs.values()) removeRigEntities(r);
        rigs.clear();
        lockedSeats.clear();
        launches.clear();
        pushes.clear();
        hurtHooks.clear();
        helpHooks.clear();
        cureHooks.clear();
        if (phantom != null) states.put(phantom.id, phantom);
    }

    // ------------------------------------------------------------------ HUD 가 읽는 것

    /** 남은 출혈(틱), 쓰러지지 않았으면 -1. */
    public long bleedLeft(Player p) {
        PState s = get(p);
        return s.life == Life.DOWNED ? Math.max(0, s.bleedUntil - game.now()) : -1;
    }

    /** 출혈 고리(1 막 쓰러짐 .. 0 끝), 쓰러지지 않았으면 -1. */
    public double bleedFrac(Player p) {
        PState s = get(p);
        if (s.life != Life.DOWNED) return -1;
        double len = BLEED + Math.max(0, s.bleedBonus);
        return Math.max(0, Math.min(1, (s.bleedUntil - game.now()) / len));
    }

    /** 돕기 채널 진행(0..1): 돕는 사람이든 받는 사람이든. 채널이 없으면 -1. */
    public double helpFrac(Player p) {
        PState s = get(p);
        PState h = s.helping != null ? s : s.reviver != null ? states.get(s.reviver) : null;
        if (h == null || h.helping == null) return -1;
        double len = Math.max(1, h.helpUntil - h.helpStart);
        return Math.max(0, Math.min(1, (game.now() - h.helpStart) / len));
    }

    /** 탈출 연타 진행(0..1), 붙잡히지 않았으면 -1. */
    public double escapeFrac(Player p) {
        PState s = get(p);
        return s.grabbedBy == null ? -1 : Math.min(1, s.escapeHits / (double) Math.max(1, s.escapeNeed));
    }

    // ------------------------------------------------------------------ 새 종 갈고리(2026-11-12 sp_engine, docs/모듈/API.md '새 종 갈고리')

    /**
     * LAUNCH: 사람을 height 블록 높이까지 띄워 올리고(side 는 더할 수평 속도, 블록/틱, null 이면 없음) 땅에 닿는 첫 틱에 landDamage 를 준다(hurt 와 같은 셈:
     * src 가 있으면 감독 피해 배율과 그 개체 배율, 사람 armor 가 곱해짐, 원인 글귀 causeKey). 서 있는 사람만(쓰러짐, 붙잡힘, 좌석에 탄 사람은 false).
     * 이미 떠 있으면 새로 띄우고 앞 착지 피해는 버린다. 바닐라 낙하 피해는 판이 막으니 착지 피해는 이것뿐. 공중에서 쓰러지거나 갇히면 착지 피해 없음.
     * 웨이브가 끝나거나 판이 바뀌면 지운다.
     */
    public boolean launch(Player p, double height, Vector side, double landDamage, String causeKey) {
        return launch(p, height, side, landDamage, null, causeKey);
    }

    /** LAUNCH(원인 개체 src 가 있는 판: 감독 피해 배율, 개체 배율, 죽은 이유 카드의 종). */
    public boolean launch(Player p, double height, Vector side, double landDamage, Gabe src, String causeKey) {
        PState s = get(p);
        if (!s.standing() || !game.inGame(p) || s.grabbedBy != null || p.isInsideVehicle()) return false;
        Rig r = rig(p);
        if (r.seat != null) return false;
        Vector v = side == null ? new Vector() : side.clone().setY(0);
        double h = Math.hypot(v.getX(), v.getZ());
        if (h > 3) v.multiply(3 / h);
        // 서버의 큰 속도 경고(한 축 4 블록/틱 넘음)를 피함
        v.setY(Math.min(3.9, launchSpeed(Math.max(0, height))));
        p.setVelocity(v);
        p.setFallDistance(0);
        Launch l = new Launch();
        l.by = src;
        l.dmg = landDamage;
        l.cause = causeKey;
        l.at = game.now();
        l.y0 = l.top = p.getLocation().getY();
        launches.put(p.getUniqueId(), l);
        return true;
    }

    /** 띄운 뒤 아직 안 내려온 사람인가(LAUNCH). */
    public boolean launched(Player p) {
        return p != null && launches.containsKey(p.getUniqueId());
    }

    /** 바닐라 중력(틱마다 0.08 빼고 0.98 곱함)으로 꼭대기가 height 블록이 되는 처음 위 속도(블록/틱). */
    static double launchSpeed(double height) {
        double lo = 0, hi = 4;
        for (int it = 0; it < 30; it++) {
            double mid = (lo + hi) / 2, y = 0, v = mid;
            for (int t = 0; t < 400 && v > 0; t++) {
                y += v;
                v = (v - 0.08) * 0.98;
            }
            if (y < height) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /**
     * 띄운 사람 착지 보기: 실제로 0.6 블록 넘게 올라간 뒤(클라 onGround 깜빡임에 속지 않게) 다시 땅에 닿는 첫 틱에 피해. 20틱 안에 못 오르면(막힘)
     * 피해 없이 지움. 서 있지 않게 되면 피해 없이 지움(20초 넘으면 지움).
     */
    private void launchTick(long now) {
        for (Iterator<Map.Entry<UUID, Launch>> it = launches.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Launch> e = it.next();
            Launch l = e.getValue();
            PState s = states.get(e.getKey());
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || s == null || s.offline || !s.standing() || now - l.at > 400) {
                it.remove();
                continue;
            }
            double y = p.getLocation().getY();
            l.top = Math.max(l.top, y);
            if (now - l.at < 2) continue;
            if (!l.left) {
                if (y > l.y0 + 0.6) l.left = true;
                else if (now - l.at > 20) it.remove();
                continue;
            }
            if (!p.isOnGround() || y > l.top - 0.4) continue;
            it.remove();
            p.setFallDistance(0);
            if (l.dmg > 0) hurt(p, l.dmg, l.by, l.cause);
        }
    }

    /**
     * SEAT: 사람을 좌석에 태워 at(좌석 자리, 사람 발은 0.6 아래)으로 옮긴다. carry 와 달리 붙잡힘이 아니라 그동안 쏘고 재장전할 수 있다(걷지 못하고 웅크려
     * 내리기는 막힘, 끝은 종이 정함). ticks 동안 이어지고(매 틱 다시 불러 옮기면서 늘림), 끝나면 그 자리 바닥에 선다. 서 있는 사람만, 붙잡힌 사람은 false.
     * 탄 사람의 웅크림은 {@code p.getCurrentInput().isSneak()} 으로 읽는다(좌석이 막아 isSneaking 이 안 바뀔 수 있음).
     */
    public boolean ride(Player p, Vector at, int ticks) {
        PState s = get(p);
        if (!s.standing() || s.grabbedBy != null || at == null || ticks <= 0) return false;
        long now = game.now();
        s.rideUntil = Math.max(s.rideUntil, now + ticks);
        Rig r = rig(p);
        Location to = new Location(game.world, at.getX(), at.getY(), at.getZ());
        if (r.seat == null || !r.seat.isValid()) seatAt(p, to, true);
        else {
            r.carried = true;
            if (r.seat.getLocation().distanceSquared(to) > 1e-4) r.seat.teleport(to);
            if (!r.seat.getPassengers().contains(p)) mount(r.seat, p);
        }
        launches.remove(p.getUniqueId());
        return true;
    }

    /** 태운 좌석에서 내림(그 자리 바닥에 섬). SEAT */
    public void unride(Player p) {
        PState s = get(p);
        if (s.rideUntil <= 0) return;
        s.rideUntil = 0;
        Rig r = rig(p);
        if (r.carried && s.grabbedBy == null && s.life == Life.ALIVE) unseat(p, true);
    }

    /** 지금 태운 좌석에 앉아 있나. SEAT */
    public boolean riding(Player p) {
        PState s = get(p);
        return s.rideUntil > game.now() && s.grabbedBy == null && rig(p).carried;
    }

    /**
     * TAKEN: 이 사람이 받는 피해에 mul 을 ticks 동안 곱한다(키마다 하나, 여럿이면 곱함, 끝나면 저절로 빠짐). mul 1 이나 ticks 0 이하면 그 키를 뗌.
     * hurt 의 모든 피해(게이브, 원인 없는 피해)에 든다. 증강과 상점이 쓰는 armor 는 건드리지 않는다.
     */
    public void taken(Player p, String key, double mul, int ticks) {
        PState s = get(p);
        if (key == null) return;
        if (ticks <= 0 || Math.abs(mul - 1) < 1e-9) s.taken.remove(key);
        else s.taken.put(key, new double[] {Math.max(0, mul), game.now() + ticks});
    }

    /** 지금 받는 피해 배율(TAKEN 곱). */
    public double takenMul(Player p) {
        return get(p).takenMul(game.now());
    }

    /**
     * STICK: 잠복 칸을 쓰는 '붙음'(latent 와 같음: ticks 뒤 onPop, 정화와 동료 짜내기로 풀리면 onCure, 한 사람 하나)이지만 판 전체 잠복 상한(latentCap)에
     * 안 들고 상한이 차 있어도 붙는다. 이미 잠복이나 붙음이 있거나, 서 있거나 쓰러진 사람이 아니거나, 비옷이 막으면 false.
     */
    public boolean stick(Player p, String kind, int ticks, Consumer<Player> onPop, Consumer<Player> onCure) {
        PState s = get(p);
        if (s.life != Life.ALIVE && s.life != Life.DOWNED) return false;
        if (s.latent != null) return false;
        if (coat(p, s)) return false;
        long now = game.now();
        s.latent = kind;
        s.latentFree = true;
        s.latentStart = now;
        s.latentAt = now + Math.max(1, ticks);
        s.latentPop = onPop;
        s.latentCure = onCure;
        return true;
    }

    /** CURE: 낫게 하는 일이 일어날 때 불리는 갈고리. how = cleanse(정화 소모품), bandage(붕대가 다 감김), squeeze(동료 짜내기). */
    public interface CureHook {
        void cured(Player p, String how);
    }

    /** CURE 갈고리를 키로 건다(같은 키면 바꿈, h null 이면 뗌). 판이 바뀌면 모두 떼니 놓일 때마다 건다. */
    public void addCureHook(Object key, CureHook h) {
        if (key == null) return;
        if (h == null) cureHooks.remove(key);
        else cureHooks.put(key, h);
    }

    public void removeCureHook(Object key) {
        cureHooks.remove(key);
    }

    /** 낫게 함을 알림(정화는 purify 가, 짜내기는 돕기가 부름. 붕대는 Supplies 가 다 감았을 때 부른다). CURE */
    public void cured(Player p, String how) {
        if (p == null || cureHooks.isEmpty()) return;
        for (Map.Entry<Object, CureHook> e : new ArrayList<>(cureHooks.entrySet())) {
            try {
                e.getValue().cured(p, how);
            } catch (RuntimeException ex) {
                if (hookErr.add("cure:" + e.getKey())) game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[사람] 낫기 갈고리 " + e.getKey() + " 오류", ex);
            }
        }
    }

    /** NOMISS: 이 사람은 ticks 동안 빗나감 판정(뛰어 피하기, 웅크려 피하기)을 건너뛴다. 종은 noMiss(p) 를 읽어 빗나감을 무시한다. */
    public void noMiss(Player p, int ticks) {
        PState s = get(p);
        s.noMissUntil = Math.max(s.noMissUntil, game.now() + Math.max(0, ticks));
    }

    /** 지금 빗나감을 건너뛰나(NOMISS). 빗나감을 쓰는 종은 '빗나감이면 return' 앞에 이것을 본다. */
    public boolean noMiss(Player p) {
        return p != null && get(p).noMissUntil > game.now();
    }

    /** HELPHOOK, HELPOBJ: 돕기(좌클릭) 차례에 끼우는 갈고리. mate = 돕기 거리 안에서 바라본 서 있는 동료(없으면 null). 썼으면 true(뒤 차례는 안 감). */
    public interface HelpHook {
        boolean help(Player helper, Player mate);
    }

    /**
     * 돕기 갈고리를 키로 건다(같은 키면 바꿈, h null 이면 뗌). 차례: 탈출 연타 → 일으키기 → 떼기 → 짜내기 → <b>이 갈고리들(건 차례)</b> → 게이브 helped →
     * 진짜 보급 상자 → 상점. 동료를 보고 하는 일(시계 떠넘기기)은 mate 를, 게이브 아닌 것(땅에 떨어진 눈 알 줍기)은 helper 의 시선과 거리를 종이 잰다.
     * 판이 바뀌면 모두 떼니 놓일 때마다 건다.
     */
    public void addHelpHook(Object key, HelpHook h) {
        if (key == null) return;
        if (h == null) helpHooks.remove(key);
        else helpHooks.put(key, h);
    }

    public void removeHelpHook(Object key) {
        helpHooks.remove(key);
    }

    /** HURTHOOK: 사람이 피해를 받을 때(hurt 에서 체력이 깎인 직후, 쓰러짐 판정 전). dealt = 실제로 깎인 양(배율 뒤), src 와 원인 키는 null 일 수 있음. */
    public interface HurtHook {
        void hurt(Player p, double dealt, Gabe src, String cause);
    }

    /** 피해 통지 갈고리를 키로 건다(같은 키면 바꿈, h null 이면 뗌). 갈고리 안에서 부른 hurt 는 다시 통지하지 않는다. 판이 바뀌면 모두 뗌. */
    public void addHurtHook(Object key, HurtHook h) {
        if (key == null) return;
        if (h == null) hurtHooks.remove(key);
        else hurtHooks.put(key, h);
    }

    public void removeHurtHook(Object key) {
        hurtHooks.remove(key);
    }

    /** 한 틱에 여럿이 밀어도 합친 수평 속도 상한(블록/틱). PUSH */
    public static final double PUSH_MAX = 0.8;

    /**
     * PUSH: 넘어뜨리지 않고 민다. 수평 v(블록/틱, y 는 버림)를 이번 틱 속도에 더하고 위 속도는 그대로 둔다(knock 은 위로 튀게 함). 같은 틱에 여럿이 밀면
     * 이 틱 처음 속도에 합을 한 번만 더한다(합 PUSH_MAX 까지). 서 있고 좌석에 안 탄 사람만. 끌어당김, 바람, 똬리처럼 매 틱 조금씩 미는 데 쓴다.
     */
    public void push(Player p, Vector v) {
        PState s = get(p);
        if (v == null || !s.standing() || p.isInsideVehicle() || !game.inGame(p)) return;
        long now = game.now();
        double[] e = pushes.get(p.getUniqueId());
        if (e == null || (long) e[0] != now) {
            Vector b = p.getVelocity();
            e = new double[] {now, b.getX(), b.getY(), b.getZ(), 0, 0};
            pushes.put(p.getUniqueId(), e);
        }
        e[4] += v.getX();
        e[5] += v.getZ();
        double h = Math.hypot(e[4], e[5]);
        double k = h > PUSH_MAX ? PUSH_MAX / h : 1;
        p.setVelocity(new Vector(e[1] + e[4] * k, e[2], e[3] + e[5] * k));
    }

    /**
     * FREE: 갇힌 이 사람만 풀어 상점 앞에 세운다(손실 없음: 엔진 freeFrom 이 사람을 잡은 개체에 한 사람에게 하는 것과 같음). 한 개체가 여러 자리에 사람을 가둔
     * 종(등불잡이마다)이 한 자리를 풀 때. 갇혀 있지 않으면 false.
     */
    public boolean free(Player p) {
        PState s = get(p);
        if (s.life != Life.TRAPPED || s.offline) return false;
        s.trapHold = false;
        reviveAtShop(p, false);
        return true;
    }

    /**
     * FREE: 갇힘 이어 가기. on 이면 쉬는 시간 첫 틱의 갇힘 끝 규칙(원정: 죽음, 옛 판: 상점 앞 부활), 먹은 개체가 사라짐(remove), 웨이브 끝 정리로 치워짐에도
     * 갇힌 채 둔다(먹은 개체가 사람에게 잡혀 죽으면 엔진 freeFrom 이 여느 때처럼 풂). 이어 받는 개체로는 retrap, 끝내기는 free 나 trapEnd.
     * 갇힌 사람에게만, 풀리거나 판에서 나오면 저절로 꺼짐.
     */
    public void holdTrapped(Player p, boolean on) {
        PState s = get(p);
        s.trapHold = on && s.life == Life.TRAPPED;
        s.trapLostAt = s.trapHold && s.eater == null ? game.now() : -1;
    }

    /** 이어 가는 갇힘을 아무 개체도 이어 받지 않고 이만큼 지나면 갇힘 끝 규칙(trapEnd). FREE */
    public static final int TRAP_HOLD_MAX = 2400;

    /** FREE: 갇힌 사람을 다른 개체(다시 나온 개체)에 옮겨 가둠. 좌석이 그 개체를 따라감. 갇혀 있지 않거나 개체가 죽었으면 false. */
    public boolean retrap(Player p, Gabe eater) {
        PState s = get(p);
        if (s.life != Life.TRAPPED || eater == null || eater.dead) return false;
        s.eater = eater;
        s.deathMob = eater;
        s.trapLostAt = -1;
        return true;
    }

    /** FREE: 갇힘 끝 규칙을 지금 이 사람에게(원정이면 죽음으로 침, 꺼내는 값 절반. 옛 판이면 상점 앞 부활). 갇혀 있지 않으면 false. */
    public boolean trapEnd(Player p) {
        PState s = get(p);
        if (s.life != Life.TRAPPED || s.offline) return false;
        s.trapHold = false;
        if (game.trip.paidRevive()) trappedToDead(p);
        else reviveAtShop(p, false);
        return true;
    }

    /**
     * VEIL: 옅은 화면 테 하나(키 = HUD 덮개 그림 키, ticks 동안). 덮개(cover)와 따로라 덮개가 있어도 걸리고, 비옷이 막지 않고, 동료 떼기로 지워지지 않는다.
     * 같은 칸에 새로 걸면 바꿈. 그리기는 Hud 가 PState.veil 을 읽어 한다(2026-11-12 요청: 아직 그리지 않음).
     */
    public void veil(Player p, String key, int ticks) {
        PState s = get(p);
        if (s.life == Life.LOBBY || key == null || ticks <= 0) return;
        s.veil = key;
        s.veilUntil = game.now() + ticks;
    }

    public void unveil(Player p) {
        PState s = get(p);
        s.veil = null;
        s.veilUntil = 0;
    }

    /** 새 종 갈고리 시험 훅(이 모듈 차례에서 먼저 봄): 띄우기, 태우기, 받는배율, 붙음, 빗나감끔, 밀기, 갇힘잇기, 한사람풀기, 옅은테, 갈고리. */
    private String testHooks(Player p, String act, String[] a) {
        switch (act) {
            case "띄우기", "태우기", "받는배율", "붙음", "빗나감끔", "밀기", "갇힘잇기", "한사람풀기", "옅은테", "새갈고리" -> {
            }
            default -> {
                return null;
            }
        }
        if (p == null) return "[사람] 없는 사람: " + a[2];
        PState s = get(p);
        double n1 = num(a, 3), n2 = num(a, 4), n3 = num(a, 5);
        switch (act) {
            case "띄우기" -> {
                // 띄우기 <이름> [높이 8] [착지 피해 10] [옆 빠르기 0]
                double yr = Math.toRadians(p.getLocation().getYaw());
                double side = Double.isNaN(n3) ? 0 : n3;
                Vector sv = new Vector(-Math.sin(yr) * side, 0, Math.cos(yr) * side);
                boolean ok = launch(p, Double.isNaN(n1) ? 8 : n1, sv, Double.isNaN(n2) ? 10 : n2, "cause_none");
                return "[사람] 띄우기 " + ok + " vy=" + String.format(Locale.ROOT, "%.3f", launchSpeed(Double.isNaN(n1) ? 8 : n1)) + " hp=" + Math.round(s.hp);
            }
            case "태우기" -> {
                // 태우기 <이름> [틱 100] [위 3]: 그 자리 위 좌석(쏠 수 있음)
                Location l = p.getLocation();
                boolean ok = ride(p, new Vector(l.getX(), l.getY() + 0.6 + (Double.isNaN(n2) ? 3 : n2), l.getZ()), Double.isNaN(n1) ? 100 : (int) n1);
                return "[사람] 태우기 " + ok + " riding=" + riding(p) + " blocked=" + game.blocked(p);
            }
            case "받는배율" -> {
                taken(p, "test", Double.isNaN(n1) ? 2 : n1, Double.isNaN(n2) ? 200 : (int) n2);
                return "[사람] 받는배율 " + String.format(Locale.ROOT, "%.2f", takenMul(p));
            }
            case "붙음" -> {
                boolean ok = stick(p, "test_stick", Double.isNaN(n1) ? 200 : (int) n1, q -> log("붙음 터짐 " + q.getName()), q -> log("붙음 풀림 " + q.getName()));
                return "[사람] 붙음 " + ok + " latent=" + s.latent + " free=" + s.latentFree + " count=" + latentCount() + "/" + latentCap();
            }
            case "빗나감끔" -> {
                noMiss(p, Double.isNaN(n1) ? 200 : (int) n1);
                return "[사람] 빗나감끔 " + noMiss(p);
            }
            case "밀기" -> {
                // 밀기 <이름> <x> <z> [틱 1]: 틱 동안 매 틱 그만큼 민다
                Vector v = new Vector(Double.isNaN(n1) ? 0.3 : n1, 0, Double.isNaN(n2) ? 0 : n2);
                int t = Double.isNaN(n3) ? 1 : (int) n3;
                Location from = p.getLocation();
                for (int i = 0; i < t; i++) {
                    long due = i;
                    Bukkit.getScheduler().runTaskLater(game.plugin, () -> push(p, v), due);
                }
                return "[사람] 밀기 " + v + " x" + t + " from=" + String.format(Locale.ROOT, "%.2f,%.2f", from.getX(), from.getZ());
            }
            case "갇힘잇기" -> {
                holdTrapped(p, a.length <= 3 || !a[3].equals("off"));
                return "[사람] 갇힘잇기 " + s.trapHold + " life=" + s.life;
            }
            case "한사람풀기" -> {
                return "[사람] 한사람풀기 " + free(p) + " life=" + s.life;
            }
            case "옅은테" -> {
                veil(p, a.length > 3 ? a[3] : "dark", Double.isNaN(n2) ? 100 : (int) n2);
                return "[사람] 옅은테 " + s.veil;
            }
            default -> {
                // 새갈고리 <이름>: 사람 칸 보기
                return "[사람] 새갈고리 lastDownBy=" + (s.lastDownBy == null ? "-" : s.lastDownBy.name()) + " noMiss=" + noMiss(p) + " taken="
                    + String.format(Locale.ROOT, "%.2f", takenMul(p)) + " riding=" + riding(p) + " launched=" + launched(p) + " trapHold=" + s.trapHold + " veil="
                    + s.veil + " hooks=" + hurtHooks.size() + "/" + helpHooks.size() + "/" + cureHooks.size();
            }
        }
    }

    // ------------------------------------------------------------------ 좌석, 바닥 원, 이름표, 숨김

    private Rig rig(Player p) {
        return rigs.computeIfAbsent(p.getUniqueId(), k -> new Rig());
    }

    /** 보이지 않는 좌석에 태움(좌석 자리 at, 사람 발은 그보다 0.6 아래). 이미 좌석이 있으면 옮김. */
    private void seatAt(Player p, Location at, boolean carried) {
        Rig r = rig(p);
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        if (r.seat != null && r.seat.isValid()) {
            // 26.3 Paper 의 엔티티 teleport 는 늘 타고 있는 것과 함께 옮긴다(RETAIN_PASSENGERS 는 무시되고 제거 예정)
            r.seat.teleport(l);
        } else {
            r.seat = l.getWorld().spawn(l, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setTeleportDuration(2);
                d.setViewRange(1.5f);
            });
            lockedSeats.add(r.seat.getUniqueId());
        }
        r.carried = carried;
        if (!r.seat.getPassengers().contains(p)) mount(r.seat, p);
    }

    /** 좌석에 태우고 바닐라 '내리려면 Shift' 안내(액션바)를 빈 글로 덮음. */
    private void mount(ItemDisplay seat, Player p) {
        if (!seat.addPassenger(p)) return;
        for (long d : new long[] {1, 4, 10}) {
            Bukkit.getScheduler().runTaskLater(game.plugin, () -> {
                if (p.isOnline()) p.sendActionBar(Component.empty());
            }, d);
        }
    }

    /** 좌석에서 내림. stand 면 좌석 자리 바닥에 세움. */
    private void unseat(Player p, boolean stand) {
        Rig r = rigs.get(p.getUniqueId());
        if (r == null || r.seat == null) return;
        ItemDisplay seat = r.seat;
        r.seat = null;
        r.carried = false;
        lockedSeats.remove(seat.getUniqueId());
        Location at = seat.getLocation();
        seat.eject();
        seat.remove();
        if (stand && p.isOnline()) teleportKeepLook(p, ground(at));
    }

    private void marks(Player p, PState s) {
        Rig r = rig(p);
        removeMarks(p);
        Location g = r.seat != null ? r.seat.getLocation() : ground(p.getLocation());
        Location ringAt = new Location(g.getWorld(), g.getX(), g.getY() + 0.04, g.getZ());
        r.ring = ringAt.getWorld().spawn(ringAt, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(game.shotFx.downRing(bleedFracOf(s), s.reviver != null));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformationMatrix(new Matrix4f().scale(RING_SCALE));
            d.setViewRange(1.5f);
            d.setTeleportDuration(2);
        });
        r.ringAt = game.now();
        Location tagAt = new Location(g.getWorld(), g.getX(), g.getY() + TAG_UP, g.getZ());
        r.tag = tagAt.getWorld().spawn(tagAt, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setSeeThrough(false);
            d.setViewRange(0.6f);
            d.setTeleportDuration(2);
            d.setTransformationMatrix(new Matrix4f().scale(TAG_SCALE));
            d.text(tagText(p, s));
            p.hideEntity(game.plugin, d);
        });
        r.tagKey = tagKey(s);
        // 바닐라 이름표는 숨기고 이 표시가 이름을 대신 보여 줌(엎드린 척하는 종의 표시와 같은 부품)
        nameTag(p, true);
    }

    private void marksTick(Player p, PState s, long now) {
        Rig r = rig(p);
        if (r.ring == null || !r.ring.isValid() || r.tag == null || !r.tag.isValid()) {
            marks(p, s);
            return;
        }
        if (now - r.ringAt >= RING_EVERY) {
            r.ringAt = now;
            r.ring.setItemStack(game.shotFx.downRing(bleedFracOf(s), s.reviver != null));
        }
        String key = tagKey(s);
        if (!key.equals(r.tagKey)) {
            r.tagKey = key;
            r.tag.text(tagText(p, s));
        }
        if (r.seat != null && now % 2 == 0) {
            Location g = r.seat.getLocation();
            Location ringAt = new Location(g.getWorld(), g.getX(), g.getY() + 0.04, g.getZ());
            if (r.ring.getLocation().distanceSquared(ringAt) > 0.01) {
                r.ring.teleport(ringAt);
                r.tag.teleport(ringAt.clone().add(0, TAG_UP - 0.04, 0));
            }
        }
    }

    private double bleedFracOf(PState s) {
        if (s.life != Life.DOWNED) return 1;
        double len = BLEED + Math.max(0, s.bleedBonus);
        return Math.max(0, Math.min(1, (s.bleedUntil - game.now()) / len));
    }

    private String tagKey(PState s) {
        long left = Math.max(0, s.bleedUntil - game.now());
        return (left + 19) / 20 + (s.reviver != null ? "r" : "");
    }

    private Component tagText(Player p, PState s) {
        long left = Math.max(0, s.bleedUntil - game.now());
        return downedTag(p.getName(), (left + 19) / 20, s.reviver != null);
    }

    /**
     * 쓰러짐 표시 글자: 이름, 그 아래 남은 초(빨강 굵게, 일으키는 중이면 초록). 엎드린 척하는 종은 이 글자를 TAG_UP, TAG_SCALE 의
     * text_display(배경 없음, 그림자, 가운데 보기)로, 바닥 원은 ShotFx.downRing(남은 몫, 일으키는중) 을 RING_SCALE 로 RING_EVERY 틱마다
     * 다시 찍어 진짜와 같은 부품으로 그린다.
     */
    public static Component downedTag(String name, long sec, boolean reviving) {
        return Component.text(name == null ? "" : name, NamedTextColor.WHITE).append(Component.newline())
            .append(Component.text(Long.toString(Math.max(0, sec)), reviving ? NamedTextColor.GREEN : NamedTextColor.RED).decorate(TextDecoration.BOLD));
    }

    private void removeMarks(Player p) {
        if (p.isOnline()) nameTag(p, false);
        Rig r = rigs.get(p.getUniqueId());
        if (r == null) return;
        if (r.ring != null) r.ring.remove();
        if (r.tag != null) r.tag.remove();
        r.ring = null;
        r.tag = null;
        r.tagKey = null;
    }

    private void removeRigEntities(Rig r) {
        if (r.seat != null) {
            lockedSeats.remove(r.seat.getUniqueId());
            r.seat.eject();
            r.seat.remove();
        }
        if (r.ring != null) r.ring.remove();
        if (r.tag != null) r.tag.remove();
        r.seat = null;
        r.ring = null;
        r.tag = null;
    }

    /** 다른 사람 모두에게서 숨김(죽음, 갇힘, 기다림). 탭 목록 항목은 남긴다. */
    private void hide(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) if (o != p && o.canSee(p)) hideFrom(o, p);
    }

    /**
     * viewer 에게서 target 몸을 숨기되 탭 목록 항목은 다시 넣는다. Paper hideEntity 는 사람이면 탭 항목까지 지우는데(PlayerInfoRemove),
     * 도감 신호(탭에서 그 사람 상태 보기)가 탭 항목에 기대므로 바로 뒤에 같은 연결로 그 사람 정보(ADD_PLAYER, 목록에 올림)를 다시 보낸다.
     * 엔티티 추적은 숨김을 따르므로 몸은 생기지 않는다.
     */
    private void hideFrom(Player viewer, Player target) {
        viewer.hideEntity(game.plugin, target);
        tabTo(viewer, target);
    }

    private boolean tabWarned;

    /** viewer 의 탭 목록에 target 항목(이름 색 포함)을 보냄. 이미 있으면 갱신만 된다. */
    private void tabTo(Player viewer, Player target) {
        try {
            ((CraftPlayer) viewer).getHandle().connection
                .send(ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(((CraftPlayer) target).getHandle(), true));
        } catch (RuntimeException | LinkageError ex) {
            if (!tabWarned) game.plugin.getLogger().warning("[사람] 탭 항목 보내기 실패: " + ex);
            tabWarned = true;
        }
    }

    /** 탭 목록 이름 색 = 상태(서 있음과 대기실은 기본 흰색, 쓰러짐 빨강, 죽음 짙은 회색, 갇힘 짙은 보라, 기다림 회색). */
    private void tabTick(Player p, PState s) {
        Rig r = rig(p);
        if (r.tabLife == s.life) return;
        r.tabLife = s.life;
        NamedTextColor c = switch (s.life) {
            case DOWNED -> NamedTextColor.RED;
            case DEAD -> NamedTextColor.DARK_GRAY;
            case TRAPPED -> NamedTextColor.DARK_PURPLE;
            case WAITING -> NamedTextColor.GRAY;
            default -> null;
        };
        p.playerListName(c == null ? null : Component.text(p.getName(), c));
        // Paper 는 그 사람을 보는 사람에게만 이름을 보내니 숨겨져 못 보는 사람에게는 따로
        for (Player o : Bukkit.getOnlinePlayers()) if (o != p && !o.canSee(p)) tabTo(o, p);
    }

    private boolean teamWarned;

    /** 바닐라 이름표가 숨겨졌나(시험 상태 줄). */
    private boolean nameTagHidden(Player p) {
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(DOWN_TEAM);
        return t != null && t.hasEntry(p.getName());
    }

    /** 쓰러진 동안 바닐라 이름표 숨기기(주 점수판 팀 DOWN_TEAM, 이름표 안 보임). */
    private void nameTag(Player p, boolean hide) {
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            Team t = sb.getTeam(DOWN_TEAM);
            if (t == null) {
                if (!hide) return;
                t = sb.registerNewTeam(DOWN_TEAM);
            }
            if (t.getOption(Team.Option.NAME_TAG_VISIBILITY) != Team.OptionStatus.NEVER) t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            String e = p.getName();
            if (hide && !t.hasEntry(e)) t.addEntry(e);
            else if (!hide && t.hasEntry(e)) t.removeEntry(e);
        } catch (RuntimeException ex) {
            if (!teamWarned) game.plugin.getLogger().warning("[사람] 이름표 팀 오류: " + ex);
            teamWarned = true;
        }
    }

    private void show(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) if (o != p && !o.canSee(p)) o.showEntity(game.plugin, p);
    }

    private boolean hiddenLife(PState s) {
        return !s.offline && (s.life == Life.DEAD || s.life == Life.TRAPPED || s.life == Life.WAITING);
    }

    /** 숨김을 상태에 맞춤(새로 들어온 사람, 놓친 것). 2초마다와 들어올 때. */
    private void visibilityPass() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player t : online) {
            PState ts = states.get(t.getUniqueId());
            boolean hide = ts != null && hiddenLife(ts) && game.inGame(t);
            for (Player v : online) {
                if (v == t) continue;
                boolean sees = v.canSee(t);
                if (hide && sees) hideFrom(v, t);
                else if (!hide && !sees) v.showEntity(game.plugin, t);
            }
        }
    }

    /** 숨기고 날게 해서 at 에서 지켜보게 함(경기장 밖으로 못 감, spectateTick). */
    private void spectate(Player p, Location at) {
        hide(p);
        p.setInvulnerable(true);
        p.setAllowFlight(true);
        p.setFlying(true);
        Location l = at.clone();
        l.setY(Math.max(Plaza.FLOOR_Y + 4, Math.min(Plaza.FLOOR_Y + 30, l.getY())));
        teleportKeepLook(p, l);
    }

    /** 판 상태 물건을 모두 걷음(좌석, 표시, 숨김, 날기, 채널, 상태 이상, 덮기, 머리, 잠복). */
    private void cleanup(Player p, PState s) {
        endHelp(s);
        endHelpOn(s);
        // 새 종 갈고리(sp_engine) 사람 칸
        s.trapHold = false;
        s.trapLostAt = -1;
        s.rideUntil = 0;
        s.veil = null;
        s.noMissUntil = 0;
        s.taken.clear();
        s.latentFree = false;
        launches.remove(s.id);
        pushes.remove(s.id);
        if (s.grabbedBy != null) releaseQuiet(p, s, "release");
        unseat(p, false);
        removeMarks(p);
        show(p);
        p.setInvulnerable(false);
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        clearSlows(p);
        if (s.cover != null) uncover(p);
        if (s.headItem != null) attachHead(p, null);
        s.latent = null;
        s.latentPop = s.latentCure = null;
        s.bleedUntil = 0;
        s.gunLockUntil = 0;
        applyMove(p, s, game.now());
    }

    /** 바닐라 체력, 배고픔 가득, 모험 모드(크리에이티브는 그대로). */
    private void fresh(Player p) {
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.ADVENTURE);
        AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
        if (mh != null) p.setHealth(mh.getValue());
        p.setFoodLevel(20);
        p.setSaturation(5);
        p.setFireTicks(0);
        p.setFallDistance(0);
    }

    // ------------------------------------------------------------------ 듣기(웅크리기)

    private void listen(Player p, PState s) {
        if (!p.isSneaking() || s.life != Life.ALIVE && s.life != Life.DOWNED) return;
        Vector eye = p.getEyeLocation().toVector();
        List<Gabe> near = new ArrayList<>(game.mobs.near(eye, LISTEN_R, g -> !g.dead));
        near.sort((a, b) -> Double.compare(a.pos.distanceSquared(eye), b.pos.distanceSquared(eye)));
        int n = 0;
        for (Gabe g : near) {
            if (n >= 3) break;
            String ev = game.mobs.listenSound(g);
            if (ev == null || ev.isEmpty()) continue;
            n++;
            Location at = g.loc();
            if (ev.startsWith("minecraft:")) p.playSound(at, ev, org.bukkit.SoundCategory.PLAYERS, 0.35f, 1f);
            else Props.soundTo(p, at, ev.startsWith(Props.NS + ":") ? ev.substring(Props.NS.length() + 1) : ev, null, 0.35f, 1f, 1f, -1);
        }
    }

    // ------------------------------------------------------------------ 도우미

    /**
     * 이 위치 아래 단단한 면 위. 걷는 바닥 높이(Flow.floor, 격자 밖은 Plaza.floor)보다 조금 아래까지 찾으므로 높이 끌려가다 쓰러져도
     * 바닥에 앉는다(적어도 8블록, 많아야 64블록). 못 찾으면 걷는 바닥 높이.
     */
    private Location ground(Location l) {
        World w = l.getWorld();
        double fl = floorAt(l.getX(), l.getZ());
        int x = l.getBlockX(), z = l.getBlockZ();
        int y0 = (int) Math.floor(l.getY() + 0.7);
        int yMin = Math.max(y0 - 64, Math.min(y0 - 8, (int) Math.floor(fl) - 1));
        for (int y = y0; y > yMin && y > w.getMinHeight(); y--) {
            Block b = w.getBlockAt(x, y - 1, z);
            if (!b.isPassable()) return new Location(w, l.getX(), b.getBoundingBox().getMaxY(), l.getZ(), l.getYaw(), l.getPitch());
        }
        return new Location(w, l.getX(), fl, l.getZ(), l.getYaw(), l.getPitch());
    }

    /** 걷는 바닥 높이(엔진 격자, 없으면 광장 표). */
    private double floorAt(double x, double z) {
        try {
            return game.mobs.flow.floor(x, z);
        } catch (RuntimeException ex) {
            return Plaza.floor(x, z);
        }
    }

    private boolean walkableAt(double x, double z) {
        try {
            return game.mobs.flow.walkable(x, z);
        } catch (RuntimeException ex) {
            return Plaza.walkable((int) Math.floor(x), (int) Math.floor(z));
        }
    }

    /** 이 사람이 지금 있는 자리(좌석이면 좌석 자리). */
    private Location spot(Player p) {
        Rig r = rigs.get(p.getUniqueId());
        return r != null && r.seat != null && r.seat.isValid() ? r.seat.getLocation() : p.getLocation();
    }

    /** 돕기 판정 점: 쓰러진 사람은 좌석 바로 위(몸통), 나머지는 허리. */
    private Vector bodyPoint(Player t, PState ts) {
        Rig r = rigs.get(t.getUniqueId());
        if (ts.life == Life.DOWNED && r != null && r.seat != null) return r.seat.getLocation().toVector().add(new Vector(0, 0.35, 0));
        return t.getLocation().toVector().add(new Vector(0, 1.0, 0));
    }

    /** 보는 방향과 그 점 쪽의 코사인. */
    private static double facing(Player p, Vector point) {
        Location eye = p.getEyeLocation();
        Vector to = point.clone().subtract(eye.toVector());
        double len = to.length();
        return len < 1e-3 ? 1 : to.dot(eye.getDirection()) / len;
    }

    /** p 가 보는 쪽(40도 안) reach 블록(수평) 안의 동료 중 가장 정면에 가까운 사람. */
    private Player lookedMate(Player p, double reach, Predicate<PState> f) {
        Player best = null;
        double bestCos = Math.cos(Math.toRadians(40));
        Location l = p.getLocation();
        for (PState t : states.values()) {
            if (t == phantom || t.offline || t.id.equals(p.getUniqueId()) || !f.test(t)) continue;
            Player tp = Bukkit.getPlayer(t.id);
            if (tp == null || tp.getWorld() != p.getWorld()) continue;
            Vector body = bodyPoint(tp, t);
            if (Math.hypot(body.getX() - l.getX(), body.getZ() - l.getZ()) > reach + 0.3) continue;
            double cos = facing(p, body);
            if (cos > bestCos && p.hasLineOfSight(tp)) {
                bestCos = cos;
                best = tp;
            }
        }
        return best;
    }

    /** helper 가 victim 등 뒤에 있나(victim 이 보는 방향 반대쪽 반원). */
    private static boolean behind(Player helper, Player victim) {
        Vector f = victim.getLocation().getDirection().setY(0);
        Vector h = helper.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0);
        if (f.lengthSquared() < 1e-6 || h.lengthSquared() < 1e-6) return false;
        return f.normalize().dot(h.normalize()) < -0.2;
    }

    /**
     * 시선은 그대로 두고 자리만 옮김. Paper 26.3 의 TeleportFlag.Relative.YAW, PITCH 는 VELOCITY_ROTATION 의 별칭이라 회전을 상대로
     * 두지 못한다(넘긴 회전이 절대로 들어감). 그래서 NMS 위치 패킷을 회전만 상대(Y_ROT, X_ROT, 0 만큼)로 보낸다. 속도는 0 이 된다.
     * Paper 가 PlayerTeleportEvent 를 부르고, 다른 세계면 보통 teleport(지금 회전).
     */
    private static void teleportKeepLook(Player p, Location to) {
        if (to.getWorld() != null && to.getWorld() != p.getWorld()) {
            Location now = p.getLocation();
            p.teleport(new Location(to.getWorld(), to.getX(), to.getY(), to.getZ(), now.getYaw(), now.getPitch()));
            return;
        }
        ((CraftPlayer) p).getHandle().connection.teleport(new PositionMoveRotation(new Vec3(to.getX(), to.getY(), to.getZ()), Vec3.ZERO, 0f, 0f),
            EnumSet.of(Relative.Y_ROT, Relative.X_ROT));
    }

    /** 고개만 그 높이각으로(상대 회전 패킷, 좌석에 앉아 있어도 됨). */
    private static void lookPitch(Player p, float pitch) {
        Guns.kick(p, 0f, pitch - p.getLocation().getPitch());
    }

    /**
     * 상점 앞 부활 자리. 상점 쪽을 등진 반원(남쪽) 위에 황금각으로 한 사람씩 퍼뜨린다(i 번째는 반지름 0.95 x 루트 i, 사이 약 1.2블록,
     * 48자리면 반지름 6.6). 같은 틱에 여럿이 부활해도 겹치지 않고, 걸을 수 없는 칸은 건너뛴다. 모두 상점을 본다.
     */
    public Location shopSpot() {
        // 원정: 트럭이 서 있으면 짐칸 뒤(남쪽, 발자국 뒤 변에서 2블록 = 가운데에서 5블록)
        Location base = kr.gave.trip.Truck.live() ? kr.gave.trip.Truck.rear(game.world, 2.0) : Plaza.shopFront(game.world);
        for (int tries = 0; tries < 16; tries++) {
            int i = spread++ % SHOP_SPOTS;
            double r = 0.95 * Math.sqrt(i);
            double a = Math.toRadians((i * 137.508) % 180.0);
            double x = base.getX() + r * Math.cos(a), z = base.getZ() + r * Math.sin(a);
            if (i > 0 && !walkableAt(x, z)) continue;
            return new Location(game.world, x, i == 0 ? base.getY() : floorAt(x, z), z, base.getYaw(), base.getPitch());
        }
        return base;
    }

    static final int SHOP_SPOTS = 48;

    private void log(String msg) {
        game.plugin.getLogger().info("[사람] " + msg);
    }

    // ------------------------------------------------------------------ 이벤트(바닐라 막기, 들어옴, 좌석)

    /** 바닐라 피해는 모두 막음(크리에이티브 빼고). 공허에 떨어지면 판 중엔 상점 앞, 아니면 대기실로. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !game.inGame(p)) return;
        e.setCancelled(true);
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
            PState s = states.get(p.getUniqueId());
            boolean playing = s != null && s.life != Life.LOBBY && game.state != Game.State.LOBBY;
            Bukkit.getScheduler().runTask(game.plugin, () -> {
                if (!p.isOnline()) return;
                if (playing && s.life == Life.ALIVE) {
                    unseat(p, false);
                    p.teleport(shopSpot());
                } else if (!playing) p.teleport(Plaza.lobby(game.world));
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMelee(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && game.inGame(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFood(FoodLevelChangeEvent e) {
        e.setCancelled(true);
        if (e.getEntity() instanceof Player p) p.setFoodLevel(20);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (game.inGame(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (game.inGame(e.getPlayer())) e.setCancelled(true);
    }

    /** Q 는 총이 재장전으로 씀(Guns.onDrop 이 취소와 재장전, 여기선 총이 없는 사람까지 막음). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (game.inGame(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(PlayerAttemptPickupItemEvent e) {
        if (game.inGame(e.getPlayer())) e.setCancelled(true);
    }

    /** 내 인벤토리 조작은 막고, 상점 화면(위 칸)의 클릭은 상점이 다룸(더 높은 우선순위에서 취소를 바꿔도 됨). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !game.inGame(p)) return;
        InventoryType top = e.getView().getTopInventory().getType();
        boolean own = top == InventoryType.CRAFTING || e.getClickedInventory() == null || e.getClickedInventory() == e.getView().getBottomInventory();
        InventoryAction a = e.getAction();
        if (own || a == InventoryAction.HOTBAR_SWAP || a == InventoryAction.MOVE_TO_OTHER_INVENTORY || a == InventoryAction.COLLECT_TO_CURSOR)
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && game.inGame(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (game.inGame(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (game.inGame(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (game.inGame(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHanging(HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player p && game.inGame(p)) e.setCancelled(true);
    }

    /** 블록 쓰기(문, 상자, 밟기)만 막는다. 아이템 쓰기(총의 누름)는 건드리지 않는다. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (!game.inGame(e.getPlayer())) return;
        if (e.getAction() == Action.PHYSICAL) e.setCancelled(true);
        else e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (!game.inGame(p)) return;
        e.setCancelled(true);
        fresh(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PState s = states.get(e.getPlayer().getUniqueId());
        if (s != null && s.life != Life.LOBBY && game.state != Game.State.LOBBY) e.setRespawnLocation(shopSpot());
        else e.setRespawnLocation(Plaza.lobby(game.world));
    }

    /** 내 좌석(쓰러짐, 끌림, 갇힘)에서 웅크려 내리기를 막는다. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDismount(EntityDismountEvent e) {
        if (lockedSeats.contains(e.getDismounted().getUniqueId()) && e.isCancellable()) e.setCancelled(true);
    }

    /** 관전(죽음, 기다림) 중에는 날기를 끌 수 없다. */
    @EventHandler
    public void onFlight(PlayerToggleFlightEvent e) {
        PState s = states.get(e.getPlayer().getUniqueId());
        if (s != null && !e.isFlying() && (s.life == Life.DEAD || s.life == Life.WAITING) && game.inGame(e.getPlayer())) e.setCancelled(true);
    }

    /** 새로 보기 시작한 사람에게 머리 부착 모델을 다시 보냄(바닐라 장비 묶음이 덮어씀). */
    @EventHandler
    public void onTrack(PlayerTrackEntityEvent e) {
        if (!(e.getEntity() instanceof Player t)) return;
        PState s = states.get(t.getUniqueId());
        if (s == null || s.headItem == null) return;
        Player v = e.getPlayer();
        Bukkit.getScheduler().runTask(game.plugin, () -> {
            PState s2 = states.get(t.getUniqueId());
            if (v.isOnline() && t.isOnline() && s2 != null && s2.headItem != null) v.sendEquipmentChange(t, EquipmentSlot.HEAD, s2.headItem);
        });
    }

    /**
     * 판이 도는 중에 크리에이티브에서 나온 사람(판 시작 때 빠져 있던 운영자)은 새로 들어온 사람처럼 판에 든다. 그대로 두면 대기실 상태로
     * 경기장에 남는다(못 쏘고 몹도 안 노림). 반대로 판 중에 크리에이티브로 바꾼 사람은 standing() 이 뺀다.
     * 관전자 모드로 가는 사람(구경하는 운영자, 시험 카메라)은 판에 넣지 않는다.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        GameMode to = e.getNewGameMode();
        if (p.getGameMode() != GameMode.CREATIVE || to == GameMode.CREATIVE || to == GameMode.SPECTATOR) return;
        Bukkit.getScheduler().runTask(game.plugin, () -> {
            PState s = states.get(p.getUniqueId());
            boolean running = game.state == Game.State.PREP || game.state == Game.State.WAVE || game.state == Game.State.BREAK;
            if (running && p.isOnline() && (s == null || s.life == Life.LOBBY)) arrive(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(game.plugin, () -> {
            if (p.isOnline()) arrive(p);
        });
    }

    /** 들어옴: 대기실이면 대기실로, 판 중이면 준비, 쉬는 시간엔 바로 상점 앞, 웨이브 중엔 기다림(관전). 나갔다 온 사람은 이어서. */
    private void arrive(Player p) {
        fresh(p);
        nameTag(p, false);
        visibilityPass();
        if (!game.inGame(p)) return;
        PState s = states.get(p.getUniqueId());
        boolean running = game.state == Game.State.PREP || game.state == Game.State.WAVE || game.state == Game.State.BREAK;
        if (!running) {
            if (s != null && s.offline) states.remove(p.getUniqueId());
            game.toLobby(p);
            return;
        }
        if (s != null && s.offline) {
            resume(p, s);
            log("돌아옴 " + p.getName() + " " + s.life + " 재화 " + s.money);
            return;
        }
        if (s == null || s.life == Life.LOBBY) {
            join(p);
            s = get(p);
            s.joinBonus = true;
        } else {
            s.name = p.getName();
            s.joinBonus = false;
        }
        if (game.state == Game.State.WAVE) toWaiting(p, s);
        else activate(p, s);
        log("들어옴 " + p.getName() + " " + s.life);
    }

    /** 판 중에 나갔던 사람이 돌아옴: 나갈 때 상태(quitLife)에서 이어 간다. */
    private void resume(Player p, PState s) {
        Life q = s.quitLife == null ? Life.WAITING : s.quitLife;
        s.offline = false;
        s.quitLife = null;
        s.name = p.getName();
        // joinBonus 는 그대로 둔다: 판에 들기 전에(웨이브 중 기다리다) 나갔다 온 사람은 아직 합류 몫을 안 받았다(activate 가 한 번 주고 끔)
        boolean wave = game.state == Game.State.WAVE;
        switch (q) {
            case ALIVE, WAITING, LOBBY -> {
                if (wave) toWaiting(p, s);
                else activate(p, s);
            }
            default -> {
                // 쓰러짐, 죽음, 갇힘: 죽은 채로. 나간 사이 업은(총 주운) 개체나 먹은 개체가 잡혔으면 바로 손실 없이
                // (먹은 개체가 freeFrom 을 거치지 않고 사라진 것도 같게: 접속 중이었다면 trappedTick 이 세웠다).
                boolean eaterGone = q == Life.TRAPPED && s.deathMob != null && (s.deathMob.dead || s.deathMob.removed);
                if (s.freedAway || eaterGone) {
                    // 원정에서도 서게 reviveFree 로(reviveAtShop 은 원정의 죽은 사람을 세우지 않는다)
                    reviveFree(p);
                    return;
                }
                // 갇힌 채 나갔다 웨이브 중에 돌아옴: 먹은 개체가 살아 있으면 다시 갇힌다(그 개체를 잡으면 여느 때처럼 풀림)
                if (wave && q == Life.TRAPPED && s.deathMob != null && !s.deathMob.dead && !s.deathMob.removed) {
                    Gabe eater = s.deathMob;
                    cleanup(p, s);
                    s.life = Life.ALIVE;
                    trap(p, eater);
                    return;
                }
                // 쉬는 시간, 준비 중: 놓친 쉬는 시간 공짜 부활을 지금(꺼내기 값, 업은 개체 쉬어 가기 없이). 발사 차례에는 예전 규칙
                if (!wave && breakReviveNow()) {
                    s.life = Life.DEAD;
                    reviveFree(p);
                    return;
                }
                // 업은 개체가 살아남았으면 이번 쉬는 시간은 쉰다.
                if (!wave && s.holdSkips == 0) {
                    s.life = Life.DEAD;
                    reviveAtShop(p, true);
                    // 섰으면 끝. 원정의 발사 차례(공짜 부활이 없는 쉬는 시간)에는 죽은 사람이 서지 않는다: 아래에서 죽은 채 관전으로
                    if (s.life != Life.DEAD) return;
                } else if (!wave && s.holdSkips == 1) s.holdSkips = 2;
                // 쓰러지거나 갇힌 채 나갔다가 죽은 채로 돌아옴: 이제 죽음 하나로 친다(원정: 죽은 라운드를 지금으로 적는다. 안 적으면 옛 값이 남아
                // 꺼내는 값이 바닥까지 깎인다). 갇혔던 사람은 '갇힌 채 라운드를 넘김'과 같은 값 절반
                if (q != Life.DEAD) {
                    deathBegan(p, s);
                    if (q == Life.TRAPPED) s.trappedDeath = true;
                }
                toDead(p, s);
            }
        }
    }

    // ------------------------------------------------------------------ 시험 훅

    /** /gave 시험 &lt;행동&gt; &lt;이름&gt; [수...]. 내 행동이 아니면 null. */
    public String test(Player p, String[] a) {
        if (a.length < 3) return null;
        String act = a[1];
        if (act.startsWith("지갑")) return wallet.test(p, a);
        double n1 = num(a, 3), n2 = num(a, 4);
        switch (act) {
            case "판규칙" -> {
                // 판규칙 x [moneyMul|mobHp 값]: 돈 배율, 게이브 체력 배율(보스 빼고, 새로 놓이는 개체부터)을 켠 채 바꾸거나 읽음.
                // 보스 체력 배율(설정 bossHp x 난이도 몫), 처치 10원 벌이가 실제로 몇 원인지, 지금 쉬는 시간 공짜 부활이 도는지도 보임
                if (a.length > 4) {
                    double m = num(a, 4);
                    if (!(m > 0) || Double.isInfinite(m)) return "[사람] 판규칙: 0 보다 큰 수";
                    switch (a[3]) {
                        case "moneyMul" -> moneyMul = Math.min(100, m);
                        case "mobHp" -> game.mobs.mobHp = Math.min(100, m);
                        default -> {
                            return "[사람] 판규칙 x <moneyMul|mobHp> <값>";
                        }
                    }
                }
                return String.format(Locale.ROOT, "[사람] 판규칙 moneyMul=%.2f 벌이10=%d mobHp=%.2f bossHpScale=%.3f 쉬는부활=%s", moneyMul,
                    (int) Math.max(10, Math.round(10 * moneyMul)), game.mobs.mobHp, game.director.bosses.hpScale, breakReviveNow());
            }
            case "판끝막기" -> {
                boolean on = a.length <= 3 || !a[3].equalsIgnoreCase("off");
                if (on) {
                    if (phantom == null) phantom = new PState(UUID.nameUUIDFromBytes("gave:phantom".getBytes()), "Hicks");
                    phantom.life = Life.ALIVE;
                    states.put(phantom.id, phantom);
                } else if (phantom != null) {
                    states.remove(phantom.id);
                    phantom = null;
                }
                return "[사람] 판끝막기 " + (phantom != null);
            }
            case "쉬기" -> {
                // 쉬기 x: 몹을 조용히 치우고 쉬는 시간. 쉬기 x 정리: 치우지 않고 감독의 웨이브 끝 정리(남은 개체 처리)에 맡김
                if (game.state != Game.State.WAVE) return "[사람] 쉬기: 웨이브 중이 아님 state=" + game.state;
                if (a.length <= 3 || !a[3].equals("정리")) game.mobs.clear();
                game.beginBreak();
                return "[사람] 쉬기 state=" + game.state;
            }
            case "더미죽이기" -> {
                // 사람이 잡은 것처럼(보상 0, noReward 끔) 죽임: 엔진 Mobs.onDeath 가 freeFrom 을 부른다
                int k = 0;
                for (Gabe g : new ArrayList<>(game.mobs.all())) {
                    if (g instanceof TestDummy && !g.dead) {
                        g.noReward = false;
                        g.reward = 0;
                        g.die(Damage.of(9999, DamageType.OTHER));
                        k++;
                    }
                }
                return "[사람] 더미죽이기 " + k;
            }
            default -> {
            }
        }
        String mine = testHooks(p, act, a);
        if (mine != null) return mine;
        if (!isMine(act)) return null;
        if (p == null) return "[사람] 없는 사람: " + a[2];
        PState s = get(p);
        long now = game.now();
        switch (act) {
            case "체력" -> {
                s.hp = Math.max(0, Math.min(s.maxHp, Double.isNaN(n1) ? s.maxHp : n1));
                if (s.hp <= 0 && s.standing()) down(p, null, null);
            }
            case "쓰러뜨리기" -> {
                if (!s.standing()) return "[사람] 서 있지 않음 " + s.life;
                s.lastHurtAt = now;
                s.lastBy = null;
                s.lastCause = null;
                s.hp = 0;
                down(p, null, null);
            }
            case "일으키기" -> {
                if (s.life == Life.DOWNED) reviveHere(p, null);
                else if (s.life != Life.ALIVE && s.life != Life.LOBBY) reviveAtShop(p, false);
            }
            case "죽이기" -> {
                if (s.standing()) {
                    s.lastHurtAt = now;
                    s.hp = 0;
                    down(p, null, null);
                }
                if (s.life == Life.DOWNED) bleedOut(p, s);
            }
            case "가두기" -> trap(p, dummyNear(p));
            case "풀기" -> freeFrom(s.eater);
            case "느리게" -> slow(p, Double.isNaN(n1) ? 50 : n1, Double.isNaN(n2) ? 100 : (int) n2, a.length > 5 ? a[5] : "test");
            case "묶기" -> root(p, Double.isNaN(n1) ? 60 : (int) n1, a.length > 4 ? a[4] : "test");
            case "붙잡기" -> {
                boolean ok = grab(p, dummyNear(p), Double.isNaN(n1) ? 6 : (int) n1);
                if (!ok) return "[사람] 붙잡기 실패 " + status(p, s);
            }
            case "놓기" -> release(p);
            case "끌기" -> {
                Location l = p.getLocation();
                carry(p, l.toVector().add(new Vector(Double.isNaN(n1) ? 0 : n1, 0.6, Double.isNaN(n2) ? 0 : n2)));
            }
            case "덮기" -> {
                boolean ok = cover(p, a.length > 3 ? a[3] : "test", Double.isNaN(n2) ? 60 : (int) n2);
                if (!ok) return "[사람] 덮기 거절 " + status(p, s);
            }
            case "머리" -> {
                // 머리 <이름> [off|더미]: 더미면 가까운 게이브(없으면 시험 더미)가 붙인 것으로
                String m = a.length > 3 ? a[3] : "";
                if (m.equals("off")) attachHead(p, null);
                else attachHead(p, ItemStack.of(Material.CARVED_PUMPKIN), m.equals("더미") ? newDummy(p) : null);
            }
            case "업힘" -> {
                // 업힘 <이름> [G38|G52]: 죽은 사람의 죽음 결과를 그 종으로 두고 시험 더미를 그 개체로(더미죽이기, 쉬기 x 정리 로 시험)
                if (s.life != Life.DEAD) return "[사람] 업힘: 죽은 사람만 " + s.life;
                Location l = p.getLocation();
                Vector at = new Vector(l.getX() + 2, floorAt(l.getX() + 2, l.getZ()) + 1.2, l.getZ());
                Gabe g = game.mobs.place(new TestDummy(game, Species.G01), at);
                if (g == null) return "[사람] 업힘: 더미를 못 놓음";
                s.deathResult = a.length > 3 && a[3].equalsIgnoreCase("G52") ? Species.G52 : Species.G38;
                s.deathMob = g;
                s.holdSkips = 0;
            }
            case "잠복" -> {
                boolean ok = latent(p, "test", Double.isNaN(n1) ? 100 : (int) n1, x -> log("잠복 터짐 " + x.getName()), x -> log("잠복 풀림 " + x.getName()));
                if (!ok) return "[사람] 잠복 거절 " + status(p, s);
            }
            case "정화" -> purify(p);
            case "아군" -> {
                Player shooter = a.length > 3 ? Bukkit.getPlayerExact(a[3]) : null;
                friendlyHit(shooter, p);
            }
            case "돕기" -> help(p);
            case "돈" -> addMoneyRaw(p, Double.isNaN(n1) ? 0 : (int) n1);
            case "비옷" -> s.raincoat = Double.isNaN(n1) ? 1 : (int) n1;
            case "시선" -> {
                float yaw = Double.isNaN(n1) ? p.getLocation().getYaw() : (float) n1, pitch = Double.isNaN(n2) ? 0 : (float) n2;
                Location l = p.getLocation();
                float dy = yaw - l.getYaw();
                dy = (float) (dy - 360 * Math.floor((dy + 180) / 360));
                Guns.kick(p, dy, pitch - l.getPitch());
            }
            case "옆으로", "보기사람" -> {
                Player t = a.length > 3 ? Bukkit.getPlayerExact(a[3]) : null;
                if (t == null) return "[사람] 없는 대상";
                PState ts = get(t);
                Vector body = bodyPoint(t, ts);
                Location at = p.getLocation();
                if (act.equals("옆으로")) {
                    double d = Double.isNaN(n2) ? 2 : n2;
                    Vector f = t.getLocation().getDirection().setY(0);
                    if (f.lengthSquared() < 1e-6) f = new Vector(0, 0, 1);
                    Location tl = ts.life == Life.DOWNED ? spot(t) : t.getLocation();
                    at = ground(tl.clone().add(f.normalize().multiply(d)));
                }
                Vector eye = at.toVector().add(new Vector(0, p.getEyeHeight(), 0));
                Vector dv = body.clone().subtract(eye);
                at.setYaw((float) Math.toDegrees(Math.atan2(-dv.getX(), dv.getZ())));
                at.setPitch((float) Math.toDegrees(-Math.atan2(dv.getY(), Math.hypot(dv.getX(), dv.getZ()))));
                p.teleport(at);
            }
            case "닮음끔" -> {
                // 닮음끔 <이름> [on|off]: '내 얼굴, 내 이름 쓰지 않기'(PState.noLikeness). 인자 없으면 켬
                s.noLikeness = a.length <= 3 || !a[3].equalsIgnoreCase("off");
                return "[사람] 닮음끔 " + p.getName() + " noLikeness=" + s.noLikeness;
            }
            case "결과" -> {
                // 결과 <이름> <G38|G52|G50|없음|자동>: 다음 죽음 결과를 고름(묶음, 단계 무시, 한 번). 자동 = 고른 것 지움(굴림)
                String k = a.length > 3 ? a[3].toUpperCase(Locale.ROOT) : "";
                Species f = switch (k) {
                    case "G38" -> Species.G38;
                    case "G52" -> Species.G52;
                    case "G50" -> Species.G50;
                    case "없음" -> Species.G01;
                    default -> null;
                };
                if (f == null) {
                    forcedResult.remove(p.getUniqueId());
                    return "[사람] 결과 " + p.getName() + " 자동(굴림)";
                }
                if (f != Species.G01 && !f.implemented()) return "[사람] 결과: " + f.name() + " 구현 안 됨";
                forcedResult.put(p.getUniqueId(), f);
                return "[사람] 결과 " + p.getName() + " 다음 죽음 " + (f == Species.G01 ? "없음" : f.name());
            }
            case "자리" -> {
                Location l = p.getLocation();
                return String.format(Locale.ROOT, "[사람] 자리 %s x=%.3f y=%.3f z=%.3f yaw=%.1f pitch=%.1f tick=%d walk=%.3f", p.getName(), l.getX(), l.getY(),
                    l.getZ(), l.getYaw(), l.getPitch(), now, p.getWalkSpeed());
            }
            default -> {
            }
        }
        return status(p, s);
    }

    private static boolean isMine(String act) {
        return switch (act) {
            case "체력", "쓰러뜨리기", "일으키기", "죽이기", "가두기", "풀기", "느리게", "묶기", "붙잡기", "놓기", "끌기", "덮기", "머리", "잠복", "정화", "아군", "돕기",
                "돈", "비옷", "시선", "자리", "사람상태", "옆으로", "보기사람", "업힘", "닮음끔", "결과" -> true;
            default -> false;
        };
    }

    private static double num(String[] a, int i) {
        if (a.length <= i) return Double.NaN;
        try {
            return Double.parseDouble(a[i]);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** 3블록 앞 가장 가까운 게이브, 없으면 시험용 더미를 놓음. */
    private Gabe dummyNear(Player p) {
        Vector at = p.getLocation().toVector();
        Gabe g = game.mobs.nearest(at, 12, x -> !x.dead);
        if (g != null) return g;
        Vector v = at.clone().add(p.getLocation().getDirection().setY(0).normalize().multiply(3)).add(new Vector(0, 1.2, 0));
        return game.mobs.place(new TestDummy(game, Species.G01), v);
    }

    /** 늘 새 시험 더미(3블록 앞). */
    private Gabe newDummy(Player p) {
        Vector at = p.getLocation().toVector();
        Vector v = at.clone().add(p.getLocation().getDirection().setY(0).normalize().multiply(3)).add(new Vector(0, 1.2, 0));
        return game.mobs.place(new TestDummy(game, Species.G01), v);
    }

    /** 사람상태 한 줄(시험 대본이 읽음). */
    public String status(Player p, PState s) {
        long now = game.now();
        Location l = p.getLocation();
        Rig r = rigs.get(p.getUniqueId());
        int hiddenFrom = 0;
        for (Player o : Bukkit.getOnlinePlayers()) if (o != p && !o.canSee(p)) hiddenFrom++;
        return String.format(Locale.ROOT,
            "[사람] %s life=%s hp=%.0f/%.0f bleed=%.1f reviver=%s money=%d earned=%d seat=%s vehicle=%s flying=%s hidden=%d walk=%.3f slow=%.2f root=%d"
                + " grab=%s esc=%d/%d cover=%s latent=%s head=%s result=%s mob=%s hold=%d free=%s downs=%d revives=%d gunlock=%s pos=%.2f,%.2f,%.2f"
                + " yaw=%.1f pitch=%.1f tag=%s",
            p.getName(), s.life, s.hp, s.maxHp, s.life == Life.DOWNED ? (s.bleedUntil - now) / 20.0 : -1, s.reviver == null ? "-" : s.reviver.toString().substring(0, 8),
            s.money, s.earned, r != null && r.seat != null, p.getVehicle() == null ? "-" : p.getVehicle().getType().name(), p.isFlying(), hiddenFrom,
            p.getWalkSpeed(), s.slowSum(now), Math.max(0, s.rootUntil - now), s.grabbedBy == null ? "-" : s.grabbedBy.sp.name(), s.escapeHits, s.escapeNeed,
            s.cover == null ? "-" : s.cover, s.latent == null ? "-" : s.latent + "@" + (s.latentAt - now), s.headItem == null ? "-" : s.headItem.getType().name(),
            s.deathResult == null ? "-" : s.deathResult.name(), s.deathMob == null ? "-" : s.deathMob.dead ? "dead" : "alive", s.holdSkips, s.freeRevive,
            s.downs, s.revives, s.gunLocked(now), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), nameTagHidden(p))
            + " deaths=" + s.deaths + " revived=" + s.revived + " pool=" + s.revivePool + " face=" + s.faceKilled + " trappedDeath=" + s.trappedDeath;
    }
}
