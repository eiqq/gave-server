package kr.gave.mob.boss;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Crates;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.world.Plaza;

/**
 * 맵을 쓰는 보스 연출 도구(사용자 2026-11-12 "보스 게이브들은 맵 등을 활용하는 연출", docs/모듈/보스.md 11.12). 땅을 잠깐 바꾸고(구덩이, 둔덕, 솟는 기둥,
 * 벽, 갈라진 금, 그을음) 반드시 원래대로 되돌린다. 보스 바탕의 한 줄 도우미({@link Boss#pit}, {@link Boss#pillar} ...)나 {@code boss.land()} 로 부른다.
 *
 * <p>되돌림: 바꾸는 칸마다 원래 블록을 처음 건드릴 때 한 번 적어 둔다(메모리 + 파일 plugins/Gave/boss_terrain.journal). 보스 감독이 쉬는 시간이 시작될 때,
 * 새 웨이브가 시작될 때, 판이 바뀔 때(시작, 끝) {@link #restoreAll()} 을 부른다. 서버가 싸움 도중 꺼졌으면 다음에 켠 뒤 첫 틱에 파일을 읽어 되돌린다
 * ({@link #recover()}). 묶음 하나만 되돌리기는 {@link #restore(int)}, 천천히 되돌리기는 {@link #mend(int, int)}.
 *
 * <p>흐름장과 사람: 블록이 바뀌면 걷기 격자(Plaza.scan)와 흐름장(Flow.rescan)을 다시 읽는다(바뀜이 잦아들고 {@link #SCAN_GAP} 틱 뒤 한 번). 솟은
 * 칸은 땅 게이브에게 막힌 칸(돌아감), 꺼진 칸은 낮은 바닥이다(게이브는 내려가기만 하고 1블록 턱은 못 오른다: 구덩이에 든 게이브는 되돌릴 때까지 갇힌다).
 * 사람은 진짜 블록이라 그대로 걷고 뛰어오른다. 블록이 사람 몸에 겹치게 솟으면 그 사람을 그 위로 올린다(솟는 기둥에 올라탐, 메워지는 구덩이에서 올라옴).
 *
 * <p>손대지 않는 곳: 보급 상자 둘레 {@link #KEEP_SHOP} 블록, 살아 있는 보급 상자 둘레 2.5블록, 지킬 것({@link Ward}) 둘레, 경기장 밖(R_EDGE),
 * 바닥 아래 {@link #MAX_DEPTH} 보다 깊은 곳, 바닥 위 {@link #MAX_HEIGHT} 보다 높은 곳. 한 번에 기록하는 칸은 {@link #MAX_BLOCKS} 까지.
 *
 * <p>지형 판정(숨을 곳, 무기): {@link #hidden(Vector, Player)}(기둥이나 둔덕 뒤라 그 점에서 안 보임 = 그늘), {@link #inPit(Player)},
 * {@link #depth(double, double)}, {@link #height(double, double)}, {@link #roofed(Player, int)}, {@link #clear(Vector, Vector)}.
 */
public final class Terrain {

    public static final int MAX_DEPTH = 3, MAX_HEIGHT = 24, MAX_BLOCKS = 24000;
    public static final double KEEP_SHOP = 3.6, KEEP_CRATE = 2.5;
    /** 마지막 바뀜 뒤 걷기 격자를 다시 읽기까지 틱(연출이 이어지는 동안은 LONG_GAP 마다 한 번). */
    public static final int SCAN_GAP = 3, LONG_GAP = 20;
    static final String FILE = "boss_terrain.journal";
    private static final int FY = Plaza.FLOOR_Y;

    final Game game;
    final BossDirector bd;
    /** 바꾼 칸 → 원래 블록(처음 건드릴 때 한 번). */
    private final LinkedHashMap<Long, BlockData> orig = new LinkedHashMap<>();
    /** 묶음(연출 하나) → 그 묶음이 바꾼 칸. */
    private final Map<Integer, List<Long>> groups = new HashMap<>();
    /** 바뀐 열(x, z). */
    private final Set<Long> cols = new HashSet<>();
    private final List<Step> steps = new ArrayList<>();
    /** 나중에 고칠 묶음: {묶음, 시작 틱, 걸리는 틱}. */
    private final List<long[]> mends = new ArrayList<>();
    private final List<String> pending = new ArrayList<>();
    private int groupSeq;
    private boolean dirty, recovered, moved;
    private long lastChange = Long.MIN_VALUE / 2, lastScan = Long.MIN_VALUE / 2;
    /** 마지막 걷기 격자 다시 읽기에 걸린 ms, 지금까지 바꾼 블록 수(시험). */
    public double lastScanMs;
    public int changed, scans;
    private final BlockData air = Bukkit.createBlockData(Material.AIR);

    private record Cell(int x, int y, int z, BlockData data) {
    }

    private static final class Step {
        long at;
        int group;
        List<Cell> cells;
        /** true 면 그 칸을 원래 블록으로(mend). */
        boolean back;
    }

    Terrain(Game game, BossDirector bd) {
        this.game = game;
        this.bd = bd;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 1024) << 40) | ((long) (z + 1024) << 20) | (y + 512);
    }

    private static long col(int x, int z) {
        return ((long) (x + 1024) << 20) | (z + 1024);
    }

    private static int kx(long k) {
        return (int) (k >> 40) - 1024;
    }

    private static int kz(long k) {
        return (int) ((k >> 20) & 0xFFFFF) - 1024;
    }

    private static int ky(long k) {
        return (int) (k & 0xFFFFF) - 512;
    }

    /** 열마다 정해진 0..1 값(금, 그을음의 얼룩이 다시 그려도 같게). */
    private static double rnd(int x, int z, int salt) {
        int h = x * 374761393 + z * 668265263 + salt * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    private Path file() {
        return game.plugin.getDataFolder().toPath().resolve(FILE);
    }

    // ------------------------------------------------------------------ 손대도 되는 칸

    /** 이 열을 바꿔도 되나(보급 상자 둘레, 살아 있는 보급 상자, 지킬 것, 경기장 밖은 안 됨). */
    public boolean allowed(int x, int z) {
        if (x < Plaza.MIN || x > Plaza.MAX || z < Plaza.MIN || z > Plaza.MAX) return false;
        double r = Plaza.radius(x + 0.5, z + 0.5);
        if (r < KEEP_SHOP || r > Plaza.R_EDGE) return false;
        try {
            for (Crates.Crate c : game.crates.live()) {
                if (c.gone()) continue;
                double dx = c.pos.getX() - x - 0.5, dz = c.pos.getZ() - z - 0.5;
                if (dx * dx + dz * dz < KEEP_CRATE * KEEP_CRATE) return false;
            }
        } catch (RuntimeException e) {
            // 보급 상자 모듈이 아직 없으면 넘어감
        }
        if (bd != null) {
            for (Ward w : bd.wards()) {
                Vector p = w.pos();
                if (p == null) continue;
                double dx = p.getX() - x - 0.5, dz = p.getZ() - z - 0.5, rr = w.radius() + 1.5;
                if (dx * dx + dz * dz < rr * rr) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 블록 하나 바꾸기(기록)

    private boolean put(int x, int y, int z, BlockData data, int group) {
        if (y < FY - MAX_DEPTH - 1 || y > FY + MAX_HEIGHT) return false;
        Block b = game.world.getBlockAt(x, y, z);
        BlockData cur = b.getBlockData();
        if (cur.equals(data)) return false;
        long k = key(x, y, z);
        if (!orig.containsKey(k)) {
            if (orig.size() >= MAX_BLOCKS) return false;
            orig.put(k, cur);
            pending.add(x + " " + y + " " + z + " " + cur.getAsString());
        }
        if (group > 0) groups.computeIfAbsent(group, g -> new ArrayList<>()).add(k);
        cols.add(col(x, z));
        if (data.getMaterial().isSolid() && !cur.getMaterial().isSolid()) moved = true;
        b.setBlockData(data, false);
        changed++;
        dirty = true;
        lastChange = game.now();
        return true;
    }

    /** 그 칸을 원래 블록으로(기록에서 뺌). */
    private boolean back(long k) {
        BlockData o = orig.remove(k);
        if (o == null) return false;
        Block b = game.world.getBlockAt(kx(k), ky(k), kz(k));
        if (o.getMaterial().isSolid() && !b.getType().isSolid()) moved = true;
        b.setBlockData(o, false);
        dirty = true;
        lastChange = game.now();
        return true;
    }

    private void flush() {
        if (pending.isEmpty()) return;
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.write(f, pending, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            game.plugin.getLogger().warning("[맵] 보스 땅 기록을 못 씀(서버가 꺼지면 되돌리지 못할 수 있음): " + e);
        }
        pending.clear();
    }

    private void dropFile() {
        pending.clear();
        try {
            Files.deleteIfExists(file());
        } catch (IOException | RuntimeException e) {
            game.plugin.getLogger().warning("[맵] 보스 땅 기록 파일을 못 지움: " + e);
        }
    }

    /** 지난번에 못 되돌린 땅(서버가 싸움 도중 꺼짐)을 파일에서 읽어 되돌린다. 보스 감독이 켠 뒤 첫 틱에 부른다. 되돌린 칸 수. */
    public int recover() {
        recovered = true;
        Path f = file();
        if (!Files.isRegularFile(f)) return 0;
        int n = 0;
        try {
            Set<Long> seen = new HashSet<>();
            for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] a = ln.split(" ", 4);
                if (a.length < 4) continue;
                try {
                    int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
                    // 같은 칸이 여러 번 적혔으면 첫 줄이 진짜 원래 블록
                    if (!seen.add(key(x, y, z)) || orig.containsKey(key(x, y, z))) continue;
                    game.world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(a[3]), false);
                    n++;
                } catch (RuntimeException e) {
                    // 깨진 줄은 건너뜀
                }
            }
            if (orig.isEmpty()) Files.deleteIfExists(f);
        } catch (IOException | RuntimeException e) {
            game.plugin.getLogger().warning("[맵] 보스 땅 기록 읽기 실패: " + e);
        }
        if (n > 0) {
            game.plugin.getLogger().info("[맵] 지난번에 못 되돌린 보스 땅 " + n + "칸을 되돌림");
            rescan();
        }
        return n;
    }

    // ------------------------------------------------------------------ 틱(보스 감독 곁 틱: 모든 상태)

    void tick() {
        if (!recovered) recover();
        long now = game.now();
        if (!steps.isEmpty()) {
            for (Iterator<Step> it = steps.iterator(); it.hasNext();) {
                Step s = it.next();
                if (s.at > now) continue;
                it.remove();
                run(s);
            }
        }
        if (!mends.isEmpty()) {
            for (Iterator<long[]> it = mends.iterator(); it.hasNext();) {
                long[] m = it.next();
                if (m[1] > now) continue;
                it.remove();
                mend((int) m[0], (int) m[2]);
            }
        }
        if (moved) {
            moved = false;
            liftPlayers();
        }
        flush();
        if (orig.isEmpty() && cols.isEmpty() == false && steps.isEmpty()) {
            cols.clear();
            dropFile();
        }
        if (dirty && ((steps.isEmpty() && now - lastChange >= SCAN_GAP) || now - lastScan >= LONG_GAP)) rescan();
    }

    private void run(Step s) {
        for (Cell c : s.cells) {
            if (s.back) back(key(c.x, c.y, c.z));
            else if (allowed(c.x, c.z)) put(c.x, c.y, c.z, c.data, s.group);
        }
    }

    /** 걷기 격자와 흐름장을 지금 다시 읽음. */
    public void rescan() {
        long t0 = System.nanoTime();
        try {
            Plaza.scan(game.world);
            game.mobs.flow.rescan();
            game.mobs.flow.invalidate();
        } catch (RuntimeException e) {
            game.plugin.getLogger().warning("[맵] 걷기 격자 다시 읽기 실패: " + e);
        }
        lastScanMs = (System.nanoTime() - t0) / 1e6;
        scans++;
        dirty = false;
        lastScan = game.now();
    }

    /** 솟은 블록에 몸이 겹친 사람을 그 위로 올림. */
    private void liftPlayers() {
        for (Player p : game.world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = p.getLocation();
            int x = l.getBlockX(), z = l.getBlockZ();
            if (!cols.contains(col(x, z))) continue;
            int by = (int) Math.floor(l.getY() + 0.001);
            if (!solid(x, by, z) && !solid(x, by + 1, z)) continue;
            int ny = by;
            while (ny < FY + MAX_HEIGHT + 2 && (solid(x, ny, z) || solid(x, ny + 1, z))) ny++;
            l.setY(ny);
            p.teleport(l);
            p.setFallDistance(0);
        }
    }

    private boolean solid(int x, int y, int z) {
        return game.world.getBlockAt(x, y, z).getType().isSolid();
    }

    // ------------------------------------------------------------------ 모양(보스가 부름). 모두 묶음 번호를 돌려준다(바꿀 칸이 없으면 0)

    private int schedule(List<List<Cell>> layers, int ticks) {
        int n = 0;
        for (List<Cell> l : layers) n += l.size();
        if (n == 0) return 0;
        int g = ++groupSeq;
        long now = game.now();
        int count = layers.size();
        for (int i = 0; i < count; i++) {
            if (layers.get(i).isEmpty()) continue;
            Step s = new Step();
            s.group = g;
            s.cells = layers.get(i);
            s.at = now + (count <= 1 || ticks <= 0 ? 0 : Math.round(i * (double) ticks / count));
            if (s.at <= now) run(s);
            else steps.add(s);
        }
        return g;
    }

    private static List<List<Cell>> layers(int n) {
        List<List<Cell>> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(new ArrayList<>());
        return l;
    }

    /** 그 열의 원래 바닥 윗면 블록(바닥 높이 바로 아래 칸). 물웅덩이, 큰 금이면 진흙. */
    private BlockData floorData(int x, int z) {
        BlockData o = orig.get(key(x, FY - 1, z));
        if (o == null) o = game.world.getBlockAt(x, FY - 1, z).getBlockData();
        Material m = o.getMaterial();
        if (m == Material.BARRIER || m == Material.BLACK_CONCRETE || !m.isSolid()) return Bukkit.createBlockData(Material.MUD);
        return o;
    }

    /**
     * 구덩이(그릇 꼴): 가운데 depth 블록(1..3), 가장자리로 갈수록 얕게. ticks 에 걸쳐 한 층씩 꺼진다(0 이면 바로). 꺼진 바닥은 그 자리 흙 그대로.
     * 사람은 빠지고(턱 1블록: 뛰어오름), 땅 게이브는 내려가기만 한다.
     */
    public int pit(Vector c, double r, int depth, int ticks) {
        depth = Math.max(1, Math.min(MAX_DEPTH, depth));
        List<List<Cell>> ls = layers(depth);
        int x0 = (int) Math.floor(c.getX() - r), x1 = (int) Math.floor(c.getX() + r), z0 = (int) Math.floor(c.getZ() - r), z1 = (int) Math.floor(c.getZ() + r);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double d = Math.hypot(x + 0.5 - c.getX(), z + 0.5 - c.getZ());
                if (d > r || !allowed(x, z)) continue;
                int dd = Math.max(1, (int) Math.ceil(depth * (1 - (d / r) * (d / r)) - 1e-6));
                BlockData top = floorData(x, z);
                for (int k = 1; k <= dd; k++) {
                    List<Cell> l = ls.get(k - 1);
                    // 바닥 위 한 칸(마른 덤불 같은 장식)만 치운다. 그 위 칸(중앙 원의 보이지 않는 빛 블록)은 그대로
                    if (k == 1) l.add(new Cell(x, FY, z, air));
                    l.add(new Cell(x, FY - k, z, air));
                    l.add(new Cell(x, FY - k - 1, z, top));
                }
            }
        }
        int g = schedule(ls, ticks);
        if (g > 0) fx(c, r);
        return g;
    }

    /** 둔덕(뒤집은 그릇): 가운데 height 블록, 가장자리 1블록. 재질 m 이 null 이면 그 자리 흙. 땅 게이브에게는 막힌 칸, 사람은 뛰어오른다. */
    public int mound(Vector c, double r, int height, Material m, int ticks) {
        height = Math.max(1, Math.min(MAX_HEIGHT, height));
        List<List<Cell>> ls = layers(height);
        BlockData md = m == null ? null : Bukkit.createBlockData(m);
        int x0 = (int) Math.floor(c.getX() - r), x1 = (int) Math.floor(c.getX() + r), z0 = (int) Math.floor(c.getZ() - r), z1 = (int) Math.floor(c.getZ() + r);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double d = Math.hypot(x + 0.5 - c.getX(), z + 0.5 - c.getZ());
                if (d > r || !allowed(x, z)) continue;
                int hh = Math.max(1, (int) Math.ceil(height * (1 - (d / r) * (d / r)) - 1e-6));
                BlockData top = md == null ? floorData(x, z) : md;
                for (int k = 0; k < hh; k++) ls.get(k).add(new Cell(x, FY + k, z, top));
            }
        }
        int g = schedule(ls, ticks);
        if (g > 0) fx(c, r);
        return g;
    }

    /** 솟는 기둥(반지름 r 원기둥, 바닥에서 height 블록, 재질 m): ticks 에 걸쳐 한 층씩 솟는다. 위에 선 사람은 함께 올라간다. 가리개, 그늘, 막힌 칸. */
    public int pillar(Vector c, double r, int height, Material m, int ticks) {
        height = Math.max(1, Math.min(MAX_HEIGHT, height));
        List<List<Cell>> ls = layers(height);
        BlockData md = Bukkit.createBlockData(m == null ? Material.STONE : m);
        int x0 = (int) Math.floor(c.getX() - r), x1 = (int) Math.floor(c.getX() + r), z0 = (int) Math.floor(c.getZ() - r), z1 = (int) Math.floor(c.getZ() + r);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double d = Math.hypot(x + 0.5 - c.getX(), z + 0.5 - c.getZ());
                if (d > Math.max(0.5, r) || !allowed(x, z)) continue;
                for (int k = 0; k < height; k++) ls.get(k).add(new Cell(x, FY + k, z, md));
            }
        }
        int g = schedule(ls, ticks);
        if (g > 0) fx(c, Math.max(2, r * 2));
        return g;
    }

    /** 벽(from 에서 to 까지, 두께 thick, 높이 height, 재질 m): ticks 에 걸쳐 솟는다. */
    public int wall(Vector from, Vector to, double thick, int height, Material m, int ticks) {
        height = Math.max(1, Math.min(MAX_HEIGHT, height));
        List<List<Cell>> ls = layers(height);
        BlockData md = Bukkit.createBlockData(m == null ? Material.STONE : m);
        for (int[] xz : along(from, to, Math.max(0.5, thick / 2))) for (int k = 0; k < height; k++) ls.get(k).add(new Cell(xz[0], FY + k, xz[1], md));
        int g = schedule(ls, ticks);
        if (g > 0) fx(from.clone().add(to).multiply(0.5), 3);
        return g;
    }

    /** 갈라진 금(from 에서 to 까지, 폭 width): 바닥 윗면만 검은 속(가운데 줄)과 어두운 흙으로. deep 이면 가운데 줄이 1블록 꺼진다(도랑). */
    public int crack(Vector from, Vector to, double width, boolean deep) {
        List<List<Cell>> ls = layers(1);
        BlockData core = Bukkit.createBlockData(Material.BLACK_CONCRETE), rim = Bukkit.createBlockData(Material.SOUL_SOIL);
        double ax = from.getX(), az = from.getZ(), bx = to.getX(), bz = to.getZ(), len = Math.hypot(bx - ax, bz - az);
        for (int[] xz : along(from, to, Math.max(0.5, width / 2))) {
            int x = xz[0], z = xz[1];
            double t = len < 1e-6 ? 0 : ((x + 0.5 - ax) * (bx - ax) + (z + 0.5 - az) * (bz - az)) / (len * len);
            t = Math.max(0, Math.min(1, t));
            double d = Math.hypot(x + 0.5 - (ax + (bx - ax) * t), z + 0.5 - (az + (bz - az) * t));
            // 금은 삐뚤빼뚤: 열마다 조금 흔들림
            double jitter = (rnd(x, z, 91) - 0.5) * 0.8;
            boolean mid = d + jitter < Math.max(0.5, width / 4);
            // 지금 땅 윗면(구덩이 안이면 그 바닥)에 그린다. 솟은 칸(기둥, 둔덕)은 건너뜀
            int ty = surface(x, z);
            if (ty == Integer.MIN_VALUE) continue;
            if (mid && deep && ty == FY - 1) {
                ls.get(0).add(new Cell(x, FY, z, air));
                ls.get(0).add(new Cell(x, FY - 1, z, air));
                ls.get(0).add(new Cell(x, FY - 2, z, core));
            } else if (mid) ls.get(0).add(new Cell(x, ty, z, core));
            else if (rnd(x, z, 92) < 0.75) ls.get(0).add(new Cell(x, ty, z, rim));
        }
        return schedule(ls, 0);
    }

    /** 그을음(반지름 r): 바닥 윗면만 검게 탄 흙으로(가운데 짙게, 가장자리 드문드문). 걷기는 그대로. */
    public int scorch(Vector c, double r) {
        List<List<Cell>> ls = layers(1);
        BlockData[] burnt = {Bukkit.createBlockData(Material.BLACKSTONE), Bukkit.createBlockData(Material.COAL_BLOCK), Bukkit.createBlockData(Material.SOUL_SOIL),
            Bukkit.createBlockData(Material.BLACK_TERRACOTTA)};
        int x0 = (int) Math.floor(c.getX() - r), x1 = (int) Math.floor(c.getX() + r), z0 = (int) Math.floor(c.getZ() - r), z1 = (int) Math.floor(c.getZ() + r);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double d = Math.hypot(x + 0.5 - c.getX(), z + 0.5 - c.getZ());
                if (d > r || !allowed(x, z)) continue;
                double keep = 1 - 0.75 * (d / r) * (d / r);
                if (rnd(x, z, 93) > keep) continue;
                // 지금 땅 윗면(구덩이 안이면 그 바닥)을 태운다. 솟은 칸은 건너뜀
                int ty = surface(x, z);
                if (ty == Integer.MIN_VALUE) continue;
                ls.get(0).add(new Cell(x, ty, z, burnt[(int) (rnd(x, z, 94) * burnt.length) % burnt.length]));
                if (ty == FY - 1) ls.get(0).add(new Cell(x, FY, z, air));
            }
        }
        return schedule(ls, 0);
    }

    /** 그 열의 지금 땅 윗면 블록 y(평지는 바닥 높이 - 1, 구덩이 안은 더 낮음). 솟은 칸이거나 못 찾으면 Integer.MIN_VALUE. */
    private int surface(int x, int z) {
        if (solid(x, FY, z)) return Integer.MIN_VALUE;
        for (int y = FY - 1; y >= FY - MAX_DEPTH - 1; y--) if (solid(x, y, z)) return y;
        return Integer.MIN_VALUE;
    }

    /** 블록 하나를 바꿈(기록됨, 묶음 group: 0 이면 새 묶음). 보스가 스스로 모양을 짤 때. 묶음 번호(못 바꾸면 0). */
    public int set(int x, int y, int z, Material m, int group) {
        if (!allowed(x, z)) return 0;
        int g = group > 0 ? group : ++groupSeq;
        return put(x, y, z, Bukkit.createBlockData(m), g) ? g : 0;
    }

    /** 선분 둘레 half 블록 안 열들(바꿔도 되는 것만). */
    private List<int[]> along(Vector from, Vector to, double half) {
        List<int[]> out = new ArrayList<>();
        double ax = from.getX(), az = from.getZ(), bx = to.getX(), bz = to.getZ(), len2 = (bx - ax) * (bx - ax) + (bz - az) * (bz - az);
        int x0 = (int) Math.floor(Math.min(ax, bx) - half), x1 = (int) Math.floor(Math.max(ax, bx) + half);
        int z0 = (int) Math.floor(Math.min(az, bz) - half), z1 = (int) Math.floor(Math.max(az, bz) + half);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double t = len2 < 1e-9 ? 0 : ((x + 0.5 - ax) * (bx - ax) + (z + 0.5 - az) * (bz - az)) / len2;
                t = Math.max(0, Math.min(1, t));
                double d = Math.hypot(x + 0.5 - (ax + (bx - ax) * t), z + 0.5 - (az + (bz - az) * t));
                if (d <= half && allowed(x, z)) out.add(new int[] {x, z});
            }
        }
        return out;
    }

    /** 땅이 바뀌는 자리의 흙먼지 고리와 낮은 쿵(보스가 따로 소리를 내면 겹쳐도 됨). */
    private void fx(Vector c, double r) {
        Location at = new Location(game.world, c.getX(), FY + 0.1, c.getZ());
        try {
            ItemDisplay d = game.shotFx.spawn(at, "fx_flat", ShotFx.RING, 0, ShotFx.scale((float) Math.max(1, r * 0.6) * Tele.RING_K), Display.Billboard.FIXED,
                    true, 10, ShotFx.NORMAL);
            Tele.box(d, (float) (r * 1.4) * Tele.RING_K);
            if (d != null) game.shotFx.grow(d, ShotFx.scale((float) (r * 1.4) * Tele.RING_K), 8);
        } catch (RuntimeException e) {
            // 효과 예산이 없으면 소리만
        }
        Props.soundAround(at, "sfx.stomp", Sound.ENTITY_RAVAGER_STEP, 1f, 0.9f, 0.5f, 64, -1);
    }

    // ------------------------------------------------------------------ 되돌리기

    /** 묶음 하나를 바로 원래대로. 되돌린 칸 수. */
    public int restore(int group) {
        List<Long> ks = groups.remove(group);
        steps.removeIf(s -> s.group == group && !s.back);
        if (ks == null) return 0;
        int n = 0;
        for (Long k : ks) if (back(k)) n++;
        return n;
    }

    /** 묶음 하나를 ticks 에 걸쳐 천천히 원래대로(솟은 것은 위에서부터 내려가고, 꺼진 것은 아래에서부터 메워짐). */
    public void mend(int group, int ticks) {
        List<Long> ks = groups.remove(group);
        steps.removeIf(s -> s.group == group && !s.back);
        if (ks == null || ks.isEmpty()) return;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Long k : ks) {
            lo = Math.min(lo, ky(k));
            hi = Math.max(hi, ky(k));
        }
        int n = hi - lo + 1;
        List<List<Cell>> ls = layers(n);
        for (Long k : ks) {
            int y = ky(k);
            // 바닥 위 블록은 위층부터, 바닥 아래는 아래층부터
            int i = y >= FY ? hi - y : (hi >= FY ? hi - FY + 1 : 0) + (y - lo);
            ls.get(Math.max(0, Math.min(n - 1, i))).add(new Cell(kx(k), y, kz(k), air));
        }
        long now = game.now();
        for (int i = 0; i < n; i++) {
            if (ls.get(i).isEmpty()) continue;
            Step s = new Step();
            s.group = group;
            s.back = true;
            s.cells = ls.get(i);
            s.at = now + (ticks <= 0 ? 0 : Math.round(i * (double) ticks / n));
            if (s.at <= now) run(s);
            else steps.add(s);
        }
    }

    /** 묶음 하나를 after 틱 뒤부터 ticks 에 걸쳐 원래대로(잠깐 있다 사라지는 구덩이, 기둥). */
    public void mend(int group, int ticks, int after) {
        if (group <= 0) return;
        if (after <= 0) mend(group, ticks);
        else mends.add(new long[] {group, game.now() + after, ticks});
    }

    /** 모두 원래대로(보스 감독이 쉬는 시간, 새 웨이브, 판 바뀜에 부름). 되돌린 칸 수. */
    public int restoreAll() {
        steps.clear();
        mends.clear();
        int n = 0;
        for (Long k : new ArrayList<>(orig.keySet())) if (back(k)) n++;
        orig.clear();
        groups.clear();
        dropFile();
        if (n > 0 || dirty) {
            if (moved) {
                moved = false;
                liftPlayers();
            }
            cols.clear();
            rescan();
            game.plugin.getLogger().info("[맵] 보스 땅 " + n + "칸 되돌림");
        }
        return n;
    }

    /** 지금 바뀌어 있는 칸 수. */
    public int size() {
        return orig.size();
    }

    // ------------------------------------------------------------------ 지형 판정(숨을 곳, 무기)

    /** from 에서 to 까지 블록에 안 막히나(솟은 기둥, 둔덕, 벽, 보급 상자에 막힘). */
    public boolean clear(Vector from, Vector to) {
        Vector d = to.clone().subtract(from);
        double len = d.length();
        if (len < 1e-6) return true;
        RayTraceResult r = game.world.rayTraceBlocks(new Location(game.world, from.getX(), from.getY(), from.getZ()), d.multiply(1 / len), len,
                FluidCollisionMode.NEVER, true);
        return r == null || r.getHitBlock() == null;
    }

    /** 그 사람이 from(빛, 눈, 폭발점)에서 가려져 있나: 가슴과 머리가 둘 다 블록에 막혀야 가림(그늘, 기둥 뒤). */
    public boolean hidden(Vector from, Player p) {
        Location l = p.getLocation();
        Vector chest = new Vector(l.getX(), l.getY() + 0.9, l.getZ()), head = new Vector(l.getX(), l.getY() + (p.isSneaking() ? 1.3 : 1.6), l.getZ());
        return !clear(from, chest) && !clear(from, head);
    }

    /** 그 사람이 꺼진 땅(구덩이, 도랑) 안에 서 있나. */
    public boolean inPit(Player p) {
        Location l = p.getLocation();
        return l.getY() < FY - 0.4 && cols.contains(col(l.getBlockX(), l.getBlockZ()));
    }

    /** 그 열이 원래 바닥보다 꺼진 깊이(블록, 안 꺼졌으면 0). */
    public int depth(double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!cols.contains(col(bx, bz))) return 0;
        int d = 0;
        while (d < MAX_DEPTH && !solid(bx, FY - 1 - d, bz)) d++;
        return d;
    }

    /** 그 열이 바닥 위로 솟은 높이(블록, 안 솟았으면 0). */
    public int height(double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!cols.contains(col(bx, bz))) return 0;
        int h = 0;
        while (h < MAX_HEIGHT && solid(bx, FY + h, bz)) h++;
        return h;
    }

    /** 그 사람 머리 위 up 블록 안에 블록이 있나(덮인 곳). */
    public boolean roofed(Player p, int up) {
        Location l = p.getLocation();
        int x = l.getBlockX(), z = l.getBlockZ(), y = (int) Math.floor(l.getY()) + 2;
        for (int i = 0; i < up; i++) if (solid(x, y + i, z)) return true;
        return false;
    }

    // ------------------------------------------------------------------ 시험

    /** 시험 훅 '보스 <이름> 땅 <구덩이|둔덕|기둥|벽|금|도랑|그을음|고침 [묶음] [틱]|되돌림|잊기|가림|상태> [반지름] [깊이나 높이] [틱]': 그 사람 앞 8블록에. */
    String test(Player p, String[] a) {
        String k = a.length > 4 ? a[4] : "상태";
        double r = a.length > 5 ? Double.parseDouble(a[5]) : 4;
        int n = a.length > 6 ? Integer.parseInt(a[6]) : 2, ticks = a.length > 7 ? Integer.parseInt(a[7]) : 12;
        String state = String.format(Locale.ROOT, "칸 %d 묶음 %d 차례 %d 바꾼 %d 격자 %d번 %.1fms", orig.size(), groups.size(), steps.size(), changed, scans, lastScanMs);
        if (k.equals("상태")) return "[보스] 땅 " + state;
        if (k.equals("되돌림")) return "[보스] 땅 되돌림 " + restoreAll() + "칸 | " + state;
        if (k.equals("잊기")) {
            // 서버가 싸움 도중 꺼진 것처럼: 메모리 기록만 버리고(블록은 바뀐 채) 다음 틱에 파일에서 되돌리게 함(recover 확인용)
            flush();
            int had = orig.size();
            orig.clear();
            groups.clear();
            steps.clear();
            mends.clear();
            recovered = false;
            return "[보스] 땅 잊기 " + had + "칸(다음 틱에 기록 파일에서 되돌림) 파일=" + Files.isRegularFile(file());
        }
        if (k.equals("고침")) {
            int g = a.length > 5 ? Integer.parseInt(a[5]) : groupSeq;
            mend(g, a.length > 6 ? Integer.parseInt(a[6]) : 20);
            return "[보스] 땅 고침 묶음 " + g;
        }
        if (p == null) return "[보스] 땅: 사람 이름을 줄 것";
        Vector f = p.getLocation().getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) f = new Vector(0, 0, 1);
        f.normalize();
        Vector me = p.getLocation().toVector(), at = me.clone().add(f.clone().multiply(8));
        int g;
        switch (k) {
            case "구덩이" -> g = pit(at, r, n, ticks);
            case "둔덕" -> g = mound(at, r, n, null, ticks);
            case "기둥" -> g = pillar(at, r, n, Material.BONE_BLOCK, ticks);
            case "벽" -> g = wall(at.clone().add(new Vector(-f.getZ(), 0, f.getX()).multiply(r)), at.clone().add(new Vector(f.getZ(), 0, -f.getX()).multiply(r)), 1, n,
                    Material.BONE_BLOCK, ticks);
            case "금" -> g = crack(me.clone().add(f.clone().multiply(3)), at.clone().add(f.clone().multiply(10)), r, false);
            case "도랑" -> g = crack(me.clone().add(f.clone().multiply(3)), at.clone().add(f.clone().multiply(10)), r, true);
            case "그을음" -> g = scorch(at, r);
            case "가림" -> {
                // 앞 20블록 높이 6 의 점(빛)에서 내가 가려졌나, 구덩이 안인가
                Vector light = me.clone().add(f.clone().multiply(20)).add(new Vector(0, 6, 0));
                return "[보스] 땅 가림=" + hidden(light, p) + " 구덩이=" + inPit(p) + " 깊이=" + depth(me.getX(), me.getZ()) + " 높이=" + height(at.getX(), at.getZ())
                        + " 덮임=" + roofed(p, 8);
            }
            default -> {
                return "[보스] 땅 <구덩이|둔덕|기둥|벽|금|도랑|그을음|고침|되돌림|가림|상태> [반지름] [깊이나 높이] [틱]";
            }
        }
        return "[보스] 땅 " + k + " 묶음 " + g + " r=" + r + " n=" + n + " | " + state;
    }
}
