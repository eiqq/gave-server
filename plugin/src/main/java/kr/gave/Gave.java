package kr.gave;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.mob.SpeciesBan;
import kr.gave.world.Plaza;
import kr.gave.world.PlazaGen;

/** 게이브 서버 플러그인. 판(Game)을 만들고 매 틱 돌리고, /gave 명령을 나눠 준다. */
public final class Gave extends JavaPlugin implements Listener {

    private final PlazaGen gen = new PlazaGen();
    public PackServer pack;
    public Game game;
    /** 게임 안 도감(/도감, kr.gave.dex.Dex). 판이 생긴 뒤에 만든다. */
    public kr.gave.dex.Dex dex;

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return gen;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // 옛 대기실 방과 게이브 우리(기본 꺼짐). 생성기가 월드를 짓기 전에 정해 둔다(STARTUP 이라 월드는 이 뒤에 읽힘)
        Plaza.cageRoom = getConfig().getBoolean("cage", false);
        Props.load(getResource("props.json"), getLogger());
        pack = new PackServer(this);
        pack.start();
        getServer().getPluginManager().registerEvents(this, this);
        // STARTUP 으로 켜져 이 시점엔 월드가 없다. 한 틱 뒤에 판을 만든다.
        getServer().getScheduler().runTask(this, this::setup);
    }

    private void setup() {
        World w = getServer().getWorld(Plaza.WORLD);
        if (w == null) {
            getLogger().severe("월드 " + Plaza.WORLD + " 이 없다(server.properties level-name=" + Plaza.WORLD + " 인지 확인)");
            return;
        }
        w.setDifficulty(Difficulty.NORMAL);
        w.setGameRule(GameRules.ADVANCE_TIME, false);
        w.setGameRule(GameRules.ADVANCE_WEATHER, false);
        w.setGameRule(GameRules.SPAWN_MOBS, false);
        w.setGameRule(GameRules.FALL_DAMAGE, false);
        w.setGameRule(GameRules.KEEP_INVENTORY, true);
        w.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        w.setGameRule(GameRules.LOCATOR_BAR, false);
        w.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        w.setGameRule(GameRules.PVP, false);
        w.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        w.setTime(13000);
        w.setStorm(false);
        // 월드 스폰 = 중앙 원 안 대기실 자리. 사람의 끝은 Roam(R_ROAM 에서 부드럽게 되밂)이 지키고, 바닐라 경계는 안개 너머 아주 멀리 둔다
        // (푸른 벽이 비쳐 '끝이 안 보이는 황야'를 깨지 않게. 가운데는 보급 상자 칸 가운데)
        w.setSpawnLocation(Plaza.lobby(w));
        w.getWorldBorder().setCenter(Plaza.CX + 0.5, Plaza.CZ + 0.5);
        w.getWorldBorder().setSize(1200);
        Plaza.scan(w);

        game = new Game(this, w);
        game.autoStart = getConfig().getBoolean("autoStart", false);
        game.minPlayers = getConfig().getInt("minPlayers", 1);
        game.prepSeconds = getConfig().getInt("prepSeconds", 10);
        game.breakSeconds = getConfig().getInt("breakSeconds", 25);
        SpeciesBan.load(getConfig());
        getLogger().info("[종] " + SpeciesBan.describe());
        for (Object l : game.listeners()) {
            if (l instanceof Listener li) getServer().getPluginManager().registerEvents(li, this);
        }
        getServer().getScheduler().runTaskTimer(this, game::tick, 1, 1);
        // 게임 안 도감(만난 종만, 기록 plugins/Gave/dex.yml). 실패해도 판은 돈다
        try {
            dex = new kr.gave.dex.Dex(this, game);
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(java.util.logging.Level.WARNING, "[도감] 켜기 실패", e);
        }
        for (Player p : Bukkit.getOnlinePlayers()) game.toLobby(p);
        getLogger().info("게이브 준비 끝: 종 " + Arrays.stream(Species.values()).filter(Species::implemented).count() + "/55 구현");
    }

    @Override
    public void onDisable() {
        if (dex != null) dex.stop();
        if (game != null) game.stop();
        if (pack != null) pack.stop();
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent e) {
        pack.sendBeforeJoin(e.getConnection());
    }

    @EventHandler
    public void onRecipe(PlayerRecipeDiscoverEvent e) {
        e.setCancelled(true);
    }

    /**
     * 들어오는 사람은 늘 대기실(중앙 원 안, 사람마다 조금 흩어 보급 상자를 봄)에서 시작한다. 이것이 없으면 바닐라 자리 찾기가 생성기 바닥(y 60..63) 아래 빈 곳의 기반암 위(y -63)에 세운다
     * (2026-11-10 시험 서버에서 확인).
     */
    @EventHandler
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent e) {
        Game g = game;
        if (g != null) e.setSpawnLocation(Plaza.lobbySpot(g.world));
    }

    /** 나간 사람의 모듈별 상태를 지운다(총 Arms, 시야 걷기 속도, HUD 화면, 사람 상태). */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (game == null) return;
        UUID id = e.getPlayer().getUniqueId();
        game.guns.forget(id);
        game.hud.forget(id);
        game.players.forget(id);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        // /gave 명령 안(소환, 시험 훅이 바로 놓는 것)에서는 설정 bannedSpecies 로 막은 종도 놓인다(SpeciesBan)
        SpeciesBan.open++;
        try {
            return command(s, a);
        } finally {
            SpeciesBan.open--;
        }
    }

    private boolean command(CommandSender s, String[] a) {
        if (game == null) {
            s.sendMessage("판이 아직 없다");
            return true;
        }
        if (a.length == 0) {
            s.sendMessage("/gave 시작 | 중지 | 상태 | 팩 | 자동 on|off | 웨이브 <n> | 데미지 [배율] | 공격력 [배율] | 소환 <종> [수] [거리] [이름] | 시험 <행동> <이름> ...");
            return true;
        }
        switch (a[0]) {
            case "시작", "start" -> {
                game.start();
                s.sendMessage(game.status());
            }
            case "중지", "stop" -> {
                game.stop();
                s.sendMessage("멈춤");
            }
            case "상태", "status" -> s.sendMessage(game.status() + (Math.abs(game.director.dmgCmd() - 1) > 1e-9 ? " dmg=" + mul(game.director.dmgCmd()) : "")
                    + (Math.abs(game.mobs.pdmg() - 1) > 1e-9 ? " pdmg=" + mul(game.mobs.pdmg()) : ""));
            case "팩", "pack" -> {
                pack.reload();
                for (Player p : Bukkit.getOnlinePlayers()) pack.send(p);
                s.sendMessage("팩 다시 보냄");
            }
            case "자동", "auto" -> {
                game.autoStart = a.length > 1 && a[1].equalsIgnoreCase("on");
                s.sendMessage("자동 시작 " + game.autoStart);
            }
            case "웨이브", "wave" -> {
                int n = a.length > 1 ? Integer.parseInt(a[1]) : game.wave + 1;
                // 2026-11-12 버그 훑기: 판 끝(OVER) 연출 중에 건너뛰면 끝난 판에서 보스와 엔딩이 돌았다
                if (game.state == Game.State.OVER) {
                    s.sendMessage("판이 끝나는 중이다: 대기실로 돌아간 뒤(/gave 중지) 다시");
                    return true;
                }
                if (game.state == Game.State.LOBBY) game.start();
                game.records.skipped = true;
                game.mobs.clear();
                game.beginWave(n);
                s.sendMessage(game.status());
            }
            case "데미지", "damage" -> s.sendMessage(damageCmd(a));
            case "공격력", "power" -> s.sendMessage(powerCmd(a));
            case "소환", "spawn" -> s.sendMessage(spawnCmd(s, a));
            case "시험", "test" -> {
                if (a.length < 3) {
                    s.sendMessage("/gave 시험 <행동> <이름> [수...]");
                    return true;
                }
                Player p = Bukkit.getPlayerExact(a[2]);
                String r = game.test(p, a);
                if (r == null && dex != null) r = dex.test(p, a);
                s.sendMessage(r == null ? "모르는 시험 행동: " + a[1] : r);
            }
            default -> s.sendMessage("모르는 명령: " + a[0]);
        }
        return true;
    }

    /**
     * /gave 데미지 [배율]: 게이브가 사람에게 주는 모든 피해(보스 포함, 트럭 피해 빼고)에 곱하는 배율. 인자 없으면 지금 값. 바로 먹고 설정 damageMul 에
     * 저장해 다시 켜도 남는다.
     */
    private String damageCmd(String[] a) {
        double now = game.director.dmgCmd();
        String range = mul(kr.gave.game.Director.DMG_CMD_MIN) + "~" + mul(kr.gave.game.Director.DMG_CMD_MAX);
        if (a.length < 2) return "게이브 피해 배율 x" + mul(now) + " (기본 1, " + range + ")";
        String t = a[1].trim();
        if (t.startsWith("x") || t.startsWith("X")) t = t.substring(1);
        if (t.endsWith("배")) t = t.substring(0, t.length() - 1);
        double v;
        try {
            v = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return "숫자로 적는다: /gave 데미지 <" + range + ">";
        }
        if (!game.director.dmgCmd(v)) return "배율은 " + range + " 사이만 된다: " + a[1];
        reloadConfig();
        getConfig().set("damageMul", v);
        saveConfig();
        return "게이브 피해 배율 x" + mul(now) + " 에서 x" + mul(v) + " 로";
    }

    /**
     * /gave 공격력 [배율]: 사람이 게이브(보스 포함)에게 주는 모든 피해(총, 폭발, 불, 냉동, 번개, 증강, 트럭 장치)에 곱하는 배율. 인자 없으면 지금 값.
     * 바로 먹고 설정 playerDamageMul 에 저장해 다시 켜도 남는다. 곱하는 자리는 Mobs.damage.
     */
    private String powerCmd(String[] a) {
        double now = game.mobs.pdmg();
        String range = mul(kr.gave.mob.Mobs.PDMG_MIN) + "~" + mul(kr.gave.mob.Mobs.PDMG_MAX);
        if (a.length < 2) return "사람 공격력 배율 x" + mul(now) + " (기본 1, " + range + ")";
        String t = a[1].trim();
        if (t.startsWith("x") || t.startsWith("X")) t = t.substring(1);
        if (t.endsWith("배")) t = t.substring(0, t.length() - 1);
        double v;
        try {
            v = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return "숫자로 적는다: /gave 공격력 <" + range + ">";
        }
        if (!game.mobs.pdmg(v)) return "배율은 " + range + " 사이만 된다: " + a[1];
        reloadConfig();
        getConfig().set("playerDamageMul", v);
        saveConfig();
        return "사람 공격력 배율 x" + mul(now) + " 에서 x" + mul(v) + " 로";
    }

    /** 배율 글: 소수 둘째 자리까지, 끝 0 은 뗌(2, 1.5, 0.1). */
    private static String mul(double v) {
        String t = String.format(Locale.ROOT, "%.2f", v);
        while (t.endsWith("0")) t = t.substring(0, t.length() - 1);
        if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** /gave 소환 <종> [수] [거리] [이름]: 이름(없으면 보낸 사람, 없으면 광장 가운데) 앞 거리 블록에 놓는다. */
    private String spawnCmd(CommandSender s, String[] a) {
        if (a.length < 2) return "/gave 소환 <종> [수] [거리] [이름]";
        Species sp = Species.of(a[1]);
        if (sp == null) return "모르는 종: " + a[1];
        if (!sp.implemented()) return sp.name() + " 은 아직 구현 안 됨";
        int n = a.length > 2 ? Integer.parseInt(a[2]) : 1;
        double dist = a.length > 3 ? Double.parseDouble(a[3]) : 12;
        Player p = a.length > 4 ? Bukkit.getPlayerExact(a[4]) : (s instanceof Player sp0 ? sp0 : null);
        Vector base, dir;
        if (p != null) {
            base = p.getLocation().toVector();
            dir = p.getLocation().getDirection().setY(0).normalize();
        } else {
            base = Plaza.center(game.world).toVector();
            dir = new Vector(0, 0, 1);
        }
        int made = 0;
        for (int i = 0; i < n; i++) {
            double ang = (i - (n - 1) / 2.0) * 0.25;
            Vector d = dir.clone().rotateAroundY(ang).multiply(dist);
            Vector at = base.clone().add(d);
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + sp.band.hover);
            Gabe g = game.mobs.spawn(sp, at, false);
            if (g != null) made++;
        }
        return sp.name() + " " + made + "마리 소환";
    }
}
