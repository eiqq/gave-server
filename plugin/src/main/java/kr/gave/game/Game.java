package kr.gave.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;

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
        switch (state) {
            case LOBBY -> lobbyTick();
            case PREP -> {
                runModules(false);
                if (stateTicks() >= prepSeconds * 20L) beginWave(1);
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
        run("hud", hud::tick);
        run("music", music::tick);
        run("horizon", horizon::tick);
        // 원정 부품(모든 상태: 대기실에서도 트럭이 서 있다)
        run("trip", trip::tick);
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

    private boolean nobodyStanding() {
        for (PState s : players.all()) {
            if (s.life == Life.ALIVE) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ 판 흐름

    /** 판 시작(대기실의 판에 들 사람 모두). */
    public void start() {
        if (state != State.LOBBY) return;
        round++;
        wave = 0;
        lobbyCountdownAt = -1;
        director.reset();
        records.reset();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inGame(p)) continue;
            players.join(p);
            guns.equip(p);
            p.teleport(players.shopSpot());
        }
        // 원정: 지도, 덱, 트럭 체력, 테마
        run("trip.start", trip::startGame);
        set(State.PREP);
        // 판 시작 오프닝(검은 화면 가운데 글, 원정 판이면 이어서 이야기 한 줄: kr.gave.trip.Travel.opening, 원정이 꺼져 있어도 나옴). 다 검은 동안은 준비 시간을 세지 않는다
        run("opening", () -> stateAt += kr.gave.trip.Travel.opening(this));
    }

    public void beginWave(int n) {
        wave = n;
        // 원정: 라운드 종류 고르기, 덱(감독이 계획을 짜기 전)
        run("trip.before", () -> trip.beforeRound(n));
        director.beginWave(n);
        set(State.WAVE);
        crates.beginWave(n);
        run("trip.begin", () -> trip.beginRound(n));
    }

    public void beginBreak() {
        set(State.BREAK);
        director.beginBreak(wave);
        // 쉬는 시간 시작: 서 있는 사람 체력 회복(Director.Tune.BREAK_HEAL)
        for (Player p : players.standing()) players.heal(p, players.get(p).maxHp * Director.Tune.BREAK_HEAL);
        // 라운드가 끝나면 모두 부활(2026-11-12 사용자): 죽은, 갇힌, 쓰러진 사람 모두 상점 앞에서 체력 가득, 공짜(Players.reviveFree).
        // 원정의 돈 내고 꺼내기는 라운드 중에만 쓰인다. 발사 차례(B5 엔딩 뒤)에는 예전 규칙: 쓰러진 사람, 기다리던 사람만 일어나고 죽은 사람은 꺼내기로
        boolean free = players.breakReviveNow(), paid = trip.paidRevive();
        for (Player p : players.inPlay()) {
            PState s = players.get(p);
            if (free) players.reviveFree(p);
            else if (s.life == Life.DOWNED || s.life == Life.WAITING || s.life == Life.DEAD && !paid) players.reviveAtShop(p, false);
        }
        // 원정: 보상, 갈림길, 이동 예약
        run("trip.break", () -> trip.beginBreak(wave));
    }

    public void gameOver() {
        set(State.OVER);
        guns.settle();
        recorded = false;
        director.gameOver();
        run("trip.over", trip::gameOver);
    }

    private void finishRecord() {
        recorded = true;
        records.finish(wave);
    }

    /** 판을 멈추고 모두 대기실로. */
    public void reset() {
        if (state == State.OVER && !recorded) finishRecord();
        mobs.clear();
        guns.clearAll();
        grenades.clear();
        shotFx.clear();
        director.reset();
        shop.clear();
        crates.clear();
        horizon.clear();
        // 원정: 땅과 테마, 놓은 것 되돌리기
        run("trip.reset", trip::reset);
        hud.clear();
        for (Player p : Bukkit.getOnlinePlayers()) toLobby(p);
        players.clear();
        wave = 0;
        set(State.LOBBY);
    }

    public void stop() {
        reset();
    }

    public void toLobby(Player p) {
        players.toLobby(p);
        p.teleport(Plaza.lobbySpot(world));
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
