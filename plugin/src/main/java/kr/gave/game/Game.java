package kr.gave.game;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import kr.gave.Gave;
import kr.gave.gun.GunHost;
import kr.gave.gun.GunMods;
import kr.gave.gun.Grenades;
import kr.gave.gun.Guns;
import kr.gave.gun.HitTargets;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Mobs;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.player.Players;
import kr.gave.world.Plaza;
import kr.gave.world.Roam;

/**
 * 판 하나(상태 기계와 시계). 모든 모듈을 들고 있고 매 틱 순서대로 부른다.
 *
 * <p>LOBBY → PREP(10초) → WAVE n → BREAK(25초) → WAVE n+1 ... → OVER(서 있는 사람 0, 쓰러진 사람도 일으킬 사람이 없으면) → 기록 → LOBBY.
 * 진 판의 OVER 동안 채팅의 다시 하기를 누르면 OVER → PREP(20초) → 진 WAVE 로 되감는다({@link #retry}).
 * 웨이브 안의 내용(몇 마리, 어떤 종, 사건)은 Director, 쉬는 시간 상점은 Shop.
 */
public final class Game implements GunHost {

    public enum State { LOBBY, PREP, WAVE, BREAK, OVER }

    public final Gave plugin;
    public final World world;
    public final Random random = new Random();

    public final Players players;
    public final Mobs mobs;
    public final ShotFx shotFx;
    public final Guns guns;
    public final Grenades grenades;
    public final Director director;
    public final Augments augments;
    public final Shop shop;
    public final Hud hud;
    public final Records records;
    /** 배경 음악(docs/모듈/브금.md). */
    public final Music music;
    /** 진짜 팀 보급 상자(웨이브 중 드물게 떨어짐, 돕기로 열면 탄약 가득 + 덤). */
    public final Crates crates;
    /** 압도감 연출(지평선 그림자 떼, 큰 물결, 하늘, 먼 포효: docs/모듈/압도감.md). */
    public final Horizon horizon;
    /** 원정(트럭, 길, 막, 죽음과 부활: docs/모듈/원정.md). 부품이 없거나 설정 trip.enabled 가 꺼져 있으면 지금 판 그대로. */
    public final kr.gave.trip.Trip trip;

    public State state = State.LOBBY;
    public long stateAt;
    /** 지금 웨이브(PREP 에선 0, 첫 웨이브 1). */
    public int wave;
    /** 판 번호(서버 켠 뒤). */
    public int round;
    private long tick;

    public int prepSeconds = 10, breakSeconds = 25, overSeconds = 20;
    /** 판 끝 기록을 이미 냈나(판 끝 연출 뒤에 냄). */
    private boolean recorded;
    public boolean autoStart;
    public int minPlayers = 1;
    private long lobbyCountdownAt = -1;
    /**
     * 다시 하기(2026-11-16 사용자 "탈락 이후 모두에게 채팅보내서 ... 누르면 탈락한 라운드부터 다시 그때 가진 총기/증강/업그레이드 전부 그대로"):
     * 진 판(전멸, 트럭 폭발)의 판 끝(OVER) 동안 물을 라운드. 0 이면 묻지 않음. 판 끝은 아무것도 지우지 않으므로(지우는 것은 reset) 되감으면 총, 증강,
     * 강화, 돈, 트럭 부품, 길과 막이 그대로다. 플레이어에게 알리는 것은 HUD 로만 한다는 규칙의 예외: 누를 칸은 채팅에만 있다(사용자 지시).
     */
    private int retryWave;
    /** 판 끝마다 하나씩 오름(지난 판 끝의 채팅을 눌러도 안 먹게). */
    private int overSeq;
    /** 다시 하기 뒤 그 라운드가 열리기까지 준비 시간(초): 상점, 증강을 고를 틈. */
    public int retrySeconds = 20;

    public Game(Gave plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.players = new Players(this);
        this.mobs = new Mobs(this);
        this.shotFx = new ShotFx(this);
        this.guns = new Guns(this);
        this.grenades = new Grenades(this, guns, shotFx);
        this.director = new Director(this);
        this.augments = new Augments(this);
        this.shop = new Shop(this);
        shop.ammoBought.add(augments.rules::ammoBought);
        this.hud = new Hud(this);
        this.records = new Records(this);
        this.music = new Music(this);
        this.crates = new Crates(this);
        this.horizon = new Horizon(this);
        // 원정 허브는 맨 끝에(부품 생성자가 위 모듈을 다 쓸 수 있게)
        this.trip = new kr.gave.trip.Trip(this);
    }

    /** 이 클래스와 모듈들 중 이벤트를 받는 것(Gave 가 등록). */
    public List<Object> listeners() {
        return List.of(players, guns, grenades, shop);
    }

    // ------------------------------------------------------------------ 시계

    @Override
    public long now() {
        return tick;
    }

    @Override
    public long worldTime() {
        return world.getGameTime();
    }

    public long stateTicks() {
        return tick - stateAt;
    }

    private void set(State s) {
        state = s;
        stateAt = tick;
        plugin.getLogger().info("[판] 상태 " + s + " 웨이브 " + wave);
    }

    public boolean inGame(Player p) {
        return p.getGameMode() != GameMode.CREATIVE;
    }

    // ------------------------------------------------------------------ 매 틱

    public void tick() {
        tick++;
        run("director.side", director::sideTick);
        // 사람이 갈 수 있는 끝(Plaza.R_ROAM): 모든 상태에서 피해 없이 부드럽게 되밂
        run("roam", () -> Roam.tick(world, tick));
        // 판 흐름 판정(감독, 원정 부품에 묻는 것)이 던져도 그 틱의 HUD, 음악, 원정 틱은 돈다
        run("flow", this::flow);
        run("hud", hud::tick);
        run("music", music::tick);
        run("horizon", horizon::tick);
        // 원정 부품(모든 상태: 대기실에서도 트럭이 서 있다)
        run("trip", trip::tick);
    }

    private void flow() {
        switch (state) {
            case LOBBY -> lobbyTick();
            case PREP -> {
                runModules(false);
                // 판 시작이면 1, 다시 하기면 진 라운드(retry 가 wave 를 그 앞으로 둠)
                if (stateTicks() >= prepSeconds * 20L) beginWave(wave + 1);
            }
            case WAVE -> {
                runModules(true);
                // 판 끝 조건은 둘: 서 있는 사람 0, 또는 원정 부품이 댄 까닭(트럭 폭발). 라운드 끝은 감독 판정에 원정 부품(목표)이 낌
                if (nobodyStanding() || trip.truckDead()) gameOver();
                else if (trip.roundDone(director.waveDone())) beginBreak();
            }
            case BREAK -> {
                runModules(false);
                // 쉬는 시간 길이: 감독 값에 원정 부품(길 투표, 이동)이 낌
                if (stateTicks() >= trip.breakTicks(director.breakTicks())) beginWave(wave + 1);
            }
            case OVER -> {
                runModules(false);
                // 판 끝 연출(남은 게이브가 중앙 원을 메움) 뒤에 기록
                if (!recorded && stateTicks() >= director.endTicks()) finishRecord();
                if (stateTicks() >= overSeconds * 20L) reset();
            }
        }
    }

    private void runModules(boolean wavePhase) {
        if (wavePhase) run("director", director::tick);
        run("mobs", mobs::tick);
        run("players", players::tick);
        run("guns", guns::tick);
        run("grenades", grenades::tick);
        run("shotFx", shotFx::tick);
        run("shop", shop::tick);
        run("crates", crates::tick);
    }

    /** 모듈마다 마지막으로 기록한 오류 틱 */
    private final Map<String, Long> failedAt = new HashMap<>();

    /** 모듈 하나의 틱. 던져도 그 틱의 뒤 모듈은 계속 돈다(모듈마다 10초에 한 번만 기록). */
    private void run(String name, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Long last = failedAt.get(name);
            if (last == null || tick - last >= 200) {
                failedAt.put(name, tick);
                plugin.getLogger().log(Level.WARNING, "[판] " + name + " 틱 오류", e);
            }
        }
    }

    private void lobbyTick() {
        // 대기실 우리(감독이 우리 개체가 있을 때 mobs.tick 을 한 번 돌림, Game 은 LOBBY 에서 따로 부르지 않음)
        run("director.lobby", director::lobbyTick);
        if (!autoStart) return;
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (inGame(p)) n++;
        if (n < minPlayers) {
            lobbyCountdownAt = -1;
            return;
        }
        if (lobbyCountdownAt < 0) lobbyCountdownAt = tick;
        if (tick - lobbyCountdownAt >= 30 * 20L) start();
    }

    /** 판 끝 판정: 서 있는 사람이 없나(나간 사람, 크리에이티브로 바꾼 사람은 서 있어도 안 셈: Players.anyStanding). */
    private boolean nobodyStanding() {
        return !players.anyStanding();
    }

    // ------------------------------------------------------------------ 판 흐름

    /** 판 시작(대기실의 판에 들 사람 모두). */
    public void start() {
        if (state != State.LOBBY) return;
        round++;
        wave = 0;
        lobbyCountdownAt = -1;
        // 단계마다 run 으로 감싼다: 한 단계(한 사람)가 던져도 판은 준비 상태까지 간다(반쯤 시작한 채 대기실에 남지 않게)
        run("director.reset", director::reset);
        run("records.reset", records::reset);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inGame(p)) continue;
            run("start.join", () -> {
                players.join(p);
                guns.equip(p);
                p.teleport(players.shopSpot());
            });
        }
        // 원정: 지도, 덱, 트럭 체력, 테마
        run("trip.start", trip::startGame);
        set(State.PREP);
        // 판 시작 오프닝(검은 화면 가운데 글, 원정 판이면 이어서 이야기 한 줄: kr.gave.trip.Travel.opening, 원정이 꺼져 있어도 나옴). 다 검은 동안은 준비 시간을 세지 않는다
        run("opening", () -> stateAt += kr.gave.trip.Travel.opening(this));
    }

    // 판 흐름 함수는 단계마다 run 으로 감싼다: 한 단계가 던져도 상태는 꼭 넘어간다. 감싸지 않으면 감독이 던질 때 상태와 stateAt 이 그대로라
    // 다음 틱에 같은 함수가 또 불려, 매 틱 웨이브 번호가 오르거나(beginWave) 판 끝에 갇힌다(reset).

    public void beginWave(int n) {
        wave = n;
        // 원정: 라운드 종류 고르기, 덱(감독이 계획을 짜기 전)
        run("trip.before", () -> trip.beforeRound(n));
        run("director.beginWave", () -> director.beginWave(n));
        set(State.WAVE);
        run("crates.beginWave", () -> crates.beginWave(n));
        run("trip.begin", () -> trip.beginRound(n));
    }

    public void beginBreak() {
        set(State.BREAK);
        run("director.beginBreak", () -> director.beginBreak(wave));
        // 쉬는 시간 시작: 서 있는 사람 체력 회복(Director.Tune.BREAK_HEAL)
        run("break.heal", () -> {
            for (Player p : players.standing()) players.heal(p, players.get(p).maxHp * Director.Tune.BREAK_HEAL);
        });
        // 라운드가 끝나면 모두 부활(2026-11-12 사용자): 죽은, 갇힌, 쓰러진 사람 모두 상점 앞에서 체력 가득, 공짜(Players.reviveFree).
        // 원정의 돈 내고 꺼내기는 라운드 중에만 쓰인다. 발사 차례(B5 엔딩 뒤)에는 예전 규칙: 쓰러진 사람, 기다리던 사람만 일어나고 죽은 사람은 꺼내기로
        boolean free = players.breakReviveNow(), paid = trip.paidRevive();
        for (Player p : players.inPlay()) {
            // 한 사람이 던져도 나머지는 부활
            run("break.revive", () -> {
                PState s = players.get(p);
                if (free) players.reviveFree(p);
                else if (s.life == Life.DOWNED || s.life == Life.WAITING || s.life == Life.DEAD && !paid) players.reviveAtShop(p, false);
            });
        }
        // 원정: 보상, 갈림길, 이동 예약
        run("trip.break", () -> trip.beginBreak(wave));
    }

    public void gameOver() {
        State was = state;
        set(State.OVER);
        recorded = false;
        run("guns.settle", guns::settle);
        run("director.gameOver", director::gameOver);
        run("trip.over", trip::gameOver);
        // 진 판이면 다시 하기를 묻는다(발사로 끝난 판은 아님). 웨이브 중에 졌으면 그 웨이브, 쉬는 시간이나 준비 중에 트럭이 터졌으면 다음 웨이브부터
        overSeq++;
        retryWave = kr.gave.trip.Trip.END_LAUNCH.equals(trip.endCause()) ? 0 : was == State.WAVE ? Math.max(1, wave) : wave + 1;
        if (retryWave > 0) run("retry.offer", this::offerRetry);
    }

    /** 진 판 끝: 접속한 모두에게 채팅으로 다시 하기를 묻는다(누르면 {@link #retry}. 판 끝이 지나 대기실로 가면 늦음). */
    private void offerRetry() {
        int seq = overSeq, n = retryWave;
        Component button = Component.text("[여기를 누르기]", NamedTextColor.GOLD, TextDecoration.BOLD)
            .hoverEvent(HoverEvent.showText(Component.text(n + "라운드부터, 지금 가진 총, 증강, 강화, 돈 그대로", NamedTextColor.GRAY)))
            .clickEvent(ClickEvent.callback(a -> {
                if (!(a instanceof Player p)) return;
                String no = retry(p.getName(), seq);
                if (no != null) p.sendMessage(Component.text(no, NamedTextColor.GRAY));
            }, o -> o.uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(5))));
        Component line = Component.text("다시 하시겠습니까? ", NamedTextColor.WHITE).append(button);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(line);
        plugin.getLogger().info("[판] 다시 하기 물음 " + n + "라운드");
    }

    /**
     * 다시 하기 누름(채팅 콜백, 메인 스레드, 누구든 먼저 누른 한 사람). 그 판 끝이 아직 도는 중이면 판 끝 연출을 거두고(감독, 보스, 트럭, HUD) 판에 든 모두를
     * 상점 앞에 체력 가득으로 세운 뒤, 진 라운드 앞 준비 시간({@link #retrySeconds})으로 되감는다. 처음 시도의 기록은 남기고(아직 안 냈으면 지금)
     * 다시 한 판은 최고 기록에 넣지 않는다(웨이브 건너뛴 판과 같게: Records.skipped). 돌려주는 값: 되감았으면 null, 아니면 누른 사람에게 보일 까닭.
     */
    private String retry(String who, int seq) {
        if (state != State.OVER || seq != overSeq || retryWave <= 0) {
            boolean going = seq == overSeq && state != State.OVER && state != State.LOBBY;
            return going ? "벌써 다시 하는 중이다" : "늦었다: 판이 이미 끝났다";
        }
        int n = retryWave;
        retryWave = 0;
        if (!recorded) finishRecord();
        records.skipped = true;
        run("director.retry", director::retry);
        run("trip.retry", trip::retry);
        run("mobs.clear", mobs::clear);
        run("grenades.clear", grenades::clear);
        run("shotFx.clear", shotFx::clear);
        run("crates.clear", crates::clear);
        run("horizon.clear", horizon::clear);
        run("hud.retry", hud::retry);
        wave = n - 1;
        set(State.PREP);
        stateAt += (retrySeconds - prepSeconds) * 20L;
        for (Player p : players.inPlay()) {
            // 죽은, 갇힌, 쓰러진 사람은 쉬는 시간 부활처럼. 서 있던 사람(트럭이 터져 진 판)은 체력만 채워 상점 앞으로
            run("retry.stand", () -> {
                if (players.reviveFree(p)) return;
                players.heal(p, players.get(p).maxHp);
                p.teleport(players.shopSpot());
            });
        }
        Component line = Component.text(who + " 님이 눌렀다: " + n + "라운드부터 다시", NamedTextColor.GRAY);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(line);
        plugin.getLogger().info("[판] 다시 하기 " + who + " → " + n + "라운드(준비 " + retrySeconds + "초)");
        return null;
    }

    /** 운영자 명령 '/gave 다시하기': 지금 판 끝에서 채팅을 누른 것과 같다(누르기가 안 되는 클라, 시험). */
    public String retry(String who) {
        String no = retry(who, overSeq);
        return no != null ? no : "다시 하기: " + (wave + 1) + "라운드 앞 준비 " + retrySeconds + "초";
    }

    private void finishRecord() {
        recorded = true;
        run("records.finish", () -> records.finish(wave));
    }

    /** 판을 멈추고 모두 대기실로. */
    public void reset() {
        if (state == State.OVER && !recorded) finishRecord();
        run("mobs.clear", mobs::clear);
        run("guns.clear", guns::clearAll);
        run("grenades.clear", grenades::clear);
        run("shotFx.clear", shotFx::clear);
        run("director.reset", director::reset);
        run("shop.clear", shop::clear);
        run("crates.clear", crates::clear);
        run("horizon.clear", horizon::clear);
        // 원정: 땅과 테마, 놓은 것 되돌리기
        run("trip.reset", trip::reset);
        run("hud.clear", hud::clear);
        for (Player p : Bukkit.getOnlinePlayers()) run("toLobby", () -> toLobby(p));
        run("players.clear", players::clear);
        wave = 0;
        set(State.LOBBY);
    }

    public void stop() {
        reset();
    }

    /** 대기실로(상태, 판에서 얻은 것 정리). 크리에이티브(맵 짓는 사람)는 상태만 정리하고 자리는 그대로 둔다. */
    public void toLobby(Player p) {
        players.toLobby(p);
        if (inGame(p)) p.teleport(Plaza.lobbySpot(world));
    }

    public String status() {
        int cage = 0;
        for (var g : mobs.all()) if (!g.dead && director.inCage(g)) cage++;
        return "[단계상태] state=" + state + " wave=" + wave + " mobs=" + (mobs.alive() - cage) + " cage=" + cage + " alive=" + players.standing().size()
                + " inplay=" + players.inPlay().size() + " left=" + director.remaining() + " tick=" + tick + " " + director.status()
                + " trip=" + trip.status();
    }

    // ------------------------------------------------------------------ GunHost

    @Override
    public JavaPlugin plugin() {
        return plugin;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public Collection<Player> shooters() {
        return players.standing();
    }

    @Override
    public boolean running() {
        return state == State.WAVE || state == State.BREAK || state == State.PREP;
    }

    @Override
    public boolean blocked(Player p) {
        PState s = players.get(p);
        return !s.standing() || s.grabbedBy != null || s.rootUntil > tick || s.gunLocked(tick);
    }

    @Override
    public HitTargets targets() {
        return mobs;
    }

    @Override
    public GunMods mods(Player p) {
        return augments.mods(p);
    }

    @Override
    public boolean tryUse(Player p) {
        return shop.tryOpen(p);
    }

    @Override
    public void onShot(Player p, String gunKey) {
        records.shot(p);
    }

    @Override
    public void onHelp(Player p) {
        players.help(p);
    }

    @Override
    public void crit(Player p) {
        hud.crit(p);
    }

    // ------------------------------------------------------------------ 시험 훅

    /** /gave 시험 &lt;행동&gt; &lt;이름&gt; [수...]. 모듈마다 자기 행동이면 글을, 아니면 null. */
    public String test(Player p, String[] a) {
        List<java.util.function.Supplier<String>> chain = new ArrayList<>();
        chain.add(() -> mobs.test(p, a));
        chain.add(() -> players.test(p, a));
        chain.add(() -> guns.test(p, a));
        chain.add(() -> director.test(p, a));
        chain.add(() -> shop.test(p, a));
        chain.add(() -> augments.test(p, a));
        chain.add(() -> hud.test(p, a));
        chain.add(() -> music.test(p, a));
        chain.add(() -> crates.test(p, a));
        chain.add(() -> horizon.test(p, a));
        chain.add(() -> trip.test(p, a));
        for (var c : chain) {
            String r = c.get();
            if (r != null) return r;
        }
        return null;
    }
}
