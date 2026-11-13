package kr.gave.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;

/**
 * 안개 짙기(26.3). 데이터팩 gave_env(원본 data/datapack/gave_env)가 시계 gave:fog 와 그 시계에 묶인 시각 타임라인 gave:fog 를 넣는다.
 * 타임라인은 환경 속성 visual/fog_start_distance, fog_end_distance, sky_fog_end_distance 를 시계 틱 구간마다 다르게 둔다
 * (자리 k 는 틱 k*STEP+15 .. k*STEP+85 에서 고정, 사이는 선형). 감독은 시계를 멈춘 채 틱을 바꿔 짙기를 고른다(서버가 클라에 시계를 보냄).
 *
 * <p>단계(감독의 안개 짙기): 0 기본(사건 없음, 4블록부터 옅게 끼는 따뜻한 흙빛 먼지로 64블록에서 안 보이고 하늘 끝도 같은 거리라 지평선이 먼지에 묻힘), 1 맑음(멀리까지), 2..5 짙음(5 가 가장 짙음). 타임라인에는
 * 옅은 차례로 놓는다: 자리 0 = 맑음(단계 1), 자리 1 = 기본(단계 0), 자리 2..5 = 단계 2..5({@link #pos}). 그래서 기본에서 짙음으로 옮겨 갈 때
 * 맑음을 거치지 않는다(감독은 자리 값으로 부드럽게 옮김).
 *
 * <p>데이터팩은 월드를 읽을 때 정해지므로(동적 레지스트리) 처음 깐 판에서는 다음 켤 때부터 먹는다. 시계가 없으면(데이터팩이 아직 안 먹음)
 * 사람마다 보기 거리(청크)를 줄여 바닐라 지형 안개를 당기는 대안으로 간다.
 *
 * <p>원정의 테마(docs/모듈/원정.md 2.1절, tools/make_theme_env.py 가 구움)는 같은 데이터팩의 시계 셋을 더 쓴다. 안개 거리는 여전히 gave:fog 만 정한다.
 * <ul>
 * <li>{@link #THEME} gave:theme: 안개 색, 하늘색, 하늘빛 세기와 색, 주변광, 구름, 별, 달, 날리는 재. 틱 = 막 x 1000 + 800 x 막 안 진행.
 * 0..900 은 옛 모습(아무것도 안 덮음), 5000..5800 은 발사 뒤 아침. alpha_blend 라 사이 틱은 부드럽게 넘어간다.</li>
 * <li>{@link #DIM} gave:dim 0..100: 한 단계 더 어둡게(안개와 하늘 x 0.25 까지, 땅 빛 x 0.7 까지).</li>
 * <li>{@link #RAIN} gave:rain 0..100 = 비 세기: 26.3 클라는 비가 오면 안개 시작을 160 x 비 세기만큼 당긴다(끝 64 인 우리 안개에서는 코앞부터
 * 뿌옇게 됨). 이 시계의 타임라인이 안개 시작에 같은 만큼을 더해 되돌린다. 이 시계가 없으면 비를 켜지 않는다.</li>
 * </ul>
 * 태그 차례(= 층 차례)는 fog, theme, dim, rain.
 */
public final class Fog {

    public static final String PACK = "gave_env";
    /** 시계 이름(이름공간 gave). */
    public static final String FOG = "fog", THEME = "theme", DIM = "dim", RAIN = "rain";
    /** 타임라인 한 단계 틱. */
    public static final int STEP = 100;
    /** 단계 0..5 의 안개 끝 거리(블록, 데이터팩 표와 같은 값. 0 기본 흙먼지, 1 맑음). 대안 보기 거리(청크, 0 이면 서버 기본). */
    public static final double[] END = {64, 160, 52, 38, 26, 14};
    public static final int[] VIEW = {5, 0, 4, 3, 2, 2};
    /** 테마 시계: 막 한 칸, 막 안 진행 0..1 의 폭, 옛 모습 끝, 아침 자리. */
    public static final int THEME_STEP = 1000, THEME_SPAN = 800, THEME_OLD_END = 900, THEME_MORNING = 5000;

    /** 찾아본 시계(없으면 값이 null 인 채로 적어 둔다: 데이터팩은 켜진 동안 바뀌지 않는다). */
    private static final Map<String, Holder<WorldClock>> clocks = new HashMap<>();
    private static String installNote = "";

    private Fog() {
    }

    /** 단계의 타임라인 자리(옅은 차례: 1 맑음 → 0, 0 기본 → 1, 2..5 그대로). 거꾸로도 같은 표(levelAt). */
    public static int pos(int level) {
        level = Math.max(0, Math.min(5, level));
        return level == 0 ? 1 : level == 1 ? 0 : level;
    }

    /** 타임라인 자리의 단계(pos 의 거꾸로, 반올림). */
    public static int levelAt(double pos) {
        return pos((int) Math.round(Math.max(0, Math.min(5, pos))));
    }

    /** 단계의 시계 틱(고정 구간 가운데). */
    public static long ticksFor(int level) {
        return ticksAt(pos(level));
    }

    /** 타임라인 자리의 시계 틱(0..5 실수, 사이 값은 두 자리 사이를 선형으로). */
    public static long ticksAt(double pos) {
        return Math.round(Math.max(0, Math.min(5, pos)) * STEP + STEP / 2.0);
    }

    /** 테마 시계 틱: 막 1..4 와 막 안 진행 0..1. */
    public static long themeTicks(int act, double progress) {
        return (long) Math.max(1, Math.min(4, act)) * THEME_STEP + Math.round(THEME_SPAN * Math.max(0, Math.min(1, progress)));
    }

    /** 안개 시계가 있나(데이터팩이 먹음). */
    public static boolean ready(World w) {
        return holder(w, FOG) != null;
    }

    /** 시계 gave:name 이 있나. */
    public static boolean ready(World w, String name) {
        return holder(w, name) != null;
    }

    private static Holder<WorldClock> holder(World w, String name) {
        synchronized (clocks) {
            if (clocks.containsKey(name)) return clocks.get(name);
            Holder<WorldClock> found = null;
            try {
                ServerLevel lvl = ((CraftWorld) w).getHandle();
                Registry<WorldClock> reg = lvl.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
                Optional<Holder.Reference<WorldClock>> h = reg.get(Identifier.fromNamespaceAndPath("gave", name));
                found = h.isPresent() ? h.get() : null;
            } catch (Throwable t) {
                found = null;
            }
            clocks.put(name, found);
            return found;
        }
    }

    /** 시계 gave:fog 를 ticks 로 맞추고 멈춤. 시계가 없으면 false. */
    public static boolean setTicks(World w, long ticks) {
        return setTicks(w, FOG, ticks);
    }

    /** 시계 gave:name 을 ticks 로 맞추고 멈춤(모든 사람에게 간다). 시계가 없으면 false. */
    public static boolean setTicks(World w, String name, long ticks) {
        Holder<WorldClock> h = holder(w, name);
        if (h == null) return false;
        try {
            ServerLevel lvl = ((CraftWorld) w).getHandle();
            lvl.clockManager().setPaused(h, true);
            lvl.clockManager().setTotalTicks(h, ticks);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 지금 안개 시계 틱(없으면 -1). */
    public static long ticks(World w) {
        return ticks(w, FOG);
    }

    /** 지금 시계 gave:name 틱(없으면 -1). */
    public static long ticks(World w, String name) {
        Holder<WorldClock> h = holder(w, name);
        if (h == null) return -1;
        try {
            return ((CraftWorld) w).getHandle().clockManager().getInstance(h).totalTicks();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 한 사람에게만 시계 gave:name 을 다르게 보여 줌(환각, 혼자만 다른 하늘). 서버 시계는 그대로다: 그 시계가 다음에 바뀌면 모두와 같아지므로
     * 이어 가려면 그때 다시 보낸다. 되돌리려면 {@link #ticks(World, String)} 값으로 다시 보낸다. 시계가 없으면 false.
     */
    public static boolean sendTicks(Player p, String name, long ticks) {
        Holder<WorldClock> h = holder(p.getWorld(), name);
        if (h == null) return false;
        try {
            ServerLevel lvl = ((CraftWorld) p.getWorld()).getHandle();
            ((CraftPlayer) p).getHandle().connection.send(new ClientboundSetTimePacket(lvl.getGameTime(), Map.of(h, new ClockNetworkState(ticks, 0f, 0f))));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 대안: 이 사람 보기 거리를 단계에 맞춤(1 맑음은 서버 기본으로 되돌림). */
    public static void view(Player p, int level) {
        int v = level >= 0 && level <= 5 && VIEW[level] > 0 ? VIEW[level] : Bukkit.getViewDistance();
        try {
            if (p.getViewDistance() != v) p.setViewDistance(v);
            if (p.getSendViewDistance() != v) p.setSendViewDistance(v);
        } catch (RuntimeException ignored) {
            // 서버가 막는 값이면 그대로
        }
    }

    /**
     * 데이터팩을 월드 datapacks/gave_env 로 깐다. 원본은 시스템 속성 gave.datapackDir, 없으면 플러그인 폴더 세 단계 위의
     * data/datapack/gave_env(gave/ 저장소). 내용이 같으면 그대로 둔다. 바꿨으면 다음 켤 때 먹는다(로그에 적음).
     */
    public static String install(JavaPlugin plugin, World w) {
        Logger log = plugin.getLogger();
        try {
            String prop = System.getProperty("gave.datapackDir");
            Path data = plugin.getDataFolder().toPath().toAbsolutePath();
            Path src = prop != null ? Path.of(prop) : data.getParent().getParent().getParent().resolve("data/datapack/" + PACK);
            if (!Files.isDirectory(src)) {
                installNote = "원본 없음 " + src;
                log.warning("[맵] 안개 데이터팩 원본이 없음: " + src + " (안개는 보기 거리 대안으로)");
                return installNote;
            }
            // 26.3 의 getWorldFolder 는 차원 폴더(gave_plaza/dimensions/minecraft/overworld)라 level.dat 가 있는 곳까지 올라감
            Path root = w.getWorldFolder().toPath().toAbsolutePath();
            while (root != null && !Files.isRegularFile(root.resolve("level.dat"))) root = root.getParent();
            if (root == null) root = Bukkit.getWorldContainer().toPath().toAbsolutePath().resolve(w.getName());
            Path dst = root.resolve("datapacks").resolve(PACK);
            List<Path> files;
            try (Stream<Path> s = Files.walk(src)) {
                files = new ArrayList<>(s.filter(Files::isRegularFile).toList());
            }
            boolean changed = false;
            for (Path f : files) {
                Path to = dst.resolve(src.relativize(f).toString());
                byte[] b = Files.readAllBytes(f);
                if (Files.isRegularFile(to) && Arrays.equals(Files.readAllBytes(to), b)) continue;
                Files.createDirectories(to.getParent());
                Files.write(to, b);
                changed = true;
            }
            boolean live = ready(w);
            installNote = (changed ? "깔았음" : "같음") + " 시계=" + (live ? "있음" : "없음") + " 테마=" + (ready(w, THEME) ? "있음" : "없음");
            if (changed || !live) log.info("[맵] 안개 데이터팩 " + dst + " " + installNote + (live ? "" : " (다음 켤 때부터 시계 안개, 지금은 보기 거리 대안)"));
            return installNote;
        } catch (IOException | RuntimeException e) {
            installNote = "실패 " + e;
            log.warning("[맵] 안개 데이터팩 깔기 실패: " + e);
            return installNote;
        }
    }

    public static String note() {
        return installNote;
    }
}
