package kr.gave.trip;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import kr.gave.world.Plaza;
import kr.gave.world.PlazaGen;

/**
 * 땅 겉면 다시 칠하기(docs/모듈/원정.md 3.5절). 가운데에서 반지름 {@link Plaza#R_PAINT} 안의 열마다 바닥 윗면 한 칸(물 칸은 그 밑 한 칸도)과 발 높이의
 * 풀을 막 act 의 것({@link PlazaGen#surface}, {@link PlazaGen#deco})으로 바꾼다. 높이는 그대로라 걷기 격자와 흐름장은 다시 읽지 않는다.
 *
 * <p>칠하는 차례는 북에서 남으로 한 줄씩(이동 연출이 흐르는 방향: 새 땅이 앞에서 밀려와 발밑을 지나간다). 한 틱에 몇 줄을 칠할지({@code rowsPerTick},
 * 0 이하면 틱 예산 {@link #BUDGET_MS} 만큼 빠르게)와 상관없이 한 틱에 쓰는 시간은 예산을 넘지 않는다(넘으면 다음 틱에 이어서). 이미 맞는 칸은 건드리지 않아
 * 같은 막을 다시 칠하면 '맞는지 확인하고 어긋난 칸만 고치기'가 된다(서버를 켠 뒤, 판이 끝난 뒤, 보스 지형 연출이 남긴 칸).
 *
 * <p>블록은 물리 없이 놓는다(이웃 갱신 없음: 풀이 뽑히거나 가루 블록이 떨어지지 않는다). 겉면끼리는 빛 성질이 같아 빛 계산이 없고, 물 칸(보이지 않는
 * 꽉 찬 블록)과 불씨(밝기 3)만 빛을 다시 잰다. 발 높이 칸은 비어 있거나 내가 놓는 풀({@link PlazaGen#isDeco})일 때만 바꾼다(트럭 발자국, 빛 블록, 남의 것은
 * 그대로). 가장자리 열 칸(반지름 90..100)은 바탕 막 땅과 점점 섞여 칠한 끝이 줄로 보이지 않는다.
 *
 * <p>보스 땅 도구(kr.gave.mob.boss.Terrain: 구덩이, 둔덕, 금, 그을음과 그 기록 파일 boss_terrain.journal)와의 차례: Terrain 이 먼저, 다시 칠하기가 나중.
 * <ul>
 * <li>바닥 윗면이 비어 있는 열(누가 판 구덩이, 도랑)은 건너뛴다: 남의 구덩이를 메우지 않는다.</li>
 * <li>칠하는 동안 한 번이라도 남이 땅을 쥐고 있었으면({@link #foreign(BooleanSupplier)}) 끝난 뒤 {@link #needsCheck()} 가 참이다. 남이 쥔 칸은 남이
 * 처음 건드릴 때의 블록(옛 막)으로 되돌아가므로, 부른 쪽(Theme)이 남이 다 되돌린 뒤 같은 막으로 한 번 더 맞춘다(어긋난 칸만 고침, 몇 틱).</li>
 * <li>서버를 켠 뒤와 판 정리: Terrain 이 먼저 되돌린다(보스 감독의 첫 틱 recover, Game.reset 의 director.reset 이 trip.reset 보다 앞). 그다음 칠한다.</li>
 * </ul>
 *
 * <p>재기: 칠한 틱마다 쓴 시간, 바뀐 블록, 바뀐 청크 구역 수(한 사람에게 가는 구역 갱신 패킷 수와 같다)를 모은다({@link #stats()}). {@link #tap(Player)}
 * 을 걸면 그 사람에게 실제로 나간 패킷을 종류별로 센다.
 */
public final class Repaint {

    /** 한 틱에 칠하기에 쓰는 시간 한도(ms). */
    public static double BUDGET_MS = 6.0;
    /** 놓는 깃발: 클라에 알림(2) + 이웃 모양 갱신 없음(16) + 놓임 처리 없음(1024). */
    private static final int FLAGS = 2 | 16 | 1024;
    private static final int R = Plaza.R_PAINT, FY = Plaza.FLOOR_Y;

    private final Plugin plugin;
    private final World world;
    private final ServerLevel level;
    private final Map<String, BlockState> states = new HashMap<>();
    private final Set<Block> decoBlocks = new HashSet<>();
    private final BlockState air = Blocks.AIR.defaultBlockState();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final int[] buf = new int[4];

    // 지금 일
    private boolean running;
    private int act = -1, base;
    private double wet, rate, cursor;
    private int nextZ;
    private BukkitTask task;
    private Runnable done;
    /** 남(보스 땅 도구)이 지금 땅을 바꿔 쥐고 있나. */
    private BooleanSupplier foreign = () -> false;
    private boolean passForeign, needsCheck;

    // 재기(마지막 일)
    private int sAct, sTicks, sCols, sChanged, sLight, sSections, sSectionsMax, sChangedMax, sHoles;
    private double sMsTotal, sMsMax;
    private long sStart, sEnd;
    private final Set<Long> touched = new HashSet<>();
    private Tap tap;
    private String tapName = "";

    public Repaint(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.level = ((CraftWorld) world).getHandle();
        for (String id : new String[] {"dead_bush", "short_dry_grass", "tall_dry_grass", "short_grass", "fern"}) decoBlocks.add(state(id).getBlock());
    }

    private BlockState state(String id) {
        return states.computeIfAbsent(id, k -> ((CraftBlockData) PlazaGen.bd(k)).getState());
    }

    /** 칠하는 중인가. */
    public boolean running() {
        return running;
    }

    /** 칠하는(마지막으로 칠한) 막. 아직 한 번도 안 칠했으면 -1. */
    public int act() {
        return act;
    }

    /** 젖은 벌 물 높이(마지막으로 칠한 것). */
    public double wet() {
        return wet;
    }

    /** 남이 땅을 쥐고 있나 묻는 함수(보스 땅 도구가 바꾼 칸이 있으면 참). */
    public void foreign(BooleanSupplier f) {
        foreign = f == null ? () -> false : f;
    }

    /** 마지막 칠하기 동안 남이 땅을 쥐고 있었나(남이 다 되돌린 뒤 같은 막으로 한 번 더 맞춰야 함). */
    public boolean needsCheck() {
        return needsCheck;
    }

    /** 칠한 몫 0..1(안 칠하는 중이면 1). */
    public double progress() {
        return running ? Math.max(0, Math.min(1, (nextZ + R) / (2.0 * R + 1))) : 1;
    }

    /**
     * 막 act(0 옛 땅, 1..4)로 칠하기 시작(하던 일이 있으면 버리고 처음부터). wet 은 젖은 벌의 물 높이 0..1. rowsPerTick 은 한 틱에 칠할 줄 수(이동 연출은
     * 1.2 = 북에서 남으로 167틱), 0 이하면 틱 예산만큼 빠르게(예닐곱 틱). done 은 끝났을 때 한 번(없으면 null).
     */
    public void start(int act, double wet, double rowsPerTick, Runnable done) {
        this.act = Math.max(0, Math.min(PlazaGen.ACTS, act));
        this.base = PlazaGen.baseAct();
        this.wet = this.act == 2 ? Math.max(0, Math.min(1, wet)) : 0;
        this.rate = rowsPerTick;
        this.cursor = -R;
        this.nextZ = -R;
        this.done = done;
        running = true;
        passForeign = false;
        Plaza.groundAct = this.act;
        sAct = this.act;
        sTicks = sCols = sChanged = sLight = sSections = sSectionsMax = sChangedMax = sHoles = 0;
        sMsTotal = sMsMax = 0;
        sStart = System.nanoTime();
        sEnd = 0;
        if (tap != null) tap.reset();
        // 서버를 끄는 중(플러그인이 꺼짐)에는 틱 일을 못 건다: 부른 쪽이 finishNow 로 끝낸다
        if (task == null && plugin.isEnabled()) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** 지금 끝까지 한 번에(서버를 끌 때: 틱이 더 돌지 않는다). */
    public void finishNow() {
        if (!running) return;
        rate = 0;
        step(Double.MAX_VALUE);
    }

    private void tick() {
        if (!running) {
            if (task != null) {
                task.cancel();
                task = null;
            }
            return;
        }
        step(BUDGET_MS);
    }

    private void step(double budgetMs) {
        long t0 = System.nanoTime();
        touched.clear();
        int before = sChanged;
        if (!passForeign) {
            try {
                passForeign = foreign.getAsBoolean();
            } catch (RuntimeException | LinkageError e) {
                passForeign = false;
            }
        }
        cursor = rate > 0 ? cursor + rate : R + 1;
        int until = (int) Math.min(R, Math.floor(cursor));
        while (nextZ <= until) {
            row(nextZ++);
            if ((System.nanoTime() - t0) / 1e6 > budgetMs) break;
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        sTicks++;
        sMsTotal += ms;
        sMsMax = Math.max(sMsMax, ms);
        sSections += touched.size();
        sSectionsMax = Math.max(sSectionsMax, touched.size());
        sChangedMax = Math.max(sChangedMax, sChanged - before);
        if (nextZ > R) {
            running = false;
            needsCheck = passForeign;
            sEnd = System.nanoTime();
            Runnable d = done;
            done = null;
            if (d != null) d.run();
        }
    }

    private void row(int z) {
        int w = (int) Math.floor(Math.sqrt((double) R * R - (double) z * z));
        for (int x = -w; x <= w; x++) column(Plaza.CX + x, Plaza.CZ + z);
    }

    private void column(int x, int z) {
        sCols++;
        // 바닥 윗면이 빈 열은 누가 판 구덩이(보스 땅 도구): 메우지 않고 건너뜀
        pos.set(x, FY - 1, z);
        if (level.getBlockState(pos).isAir()) {
            sHoles++;
            return;
        }
        int a = PlazaGen.blendAct(x, z, act, base);
        double wt = a == 2 ? wet : 0;
        String top = PlazaGen.surface(x, z, a, wt, buf);
        boolean water = PlazaGen.WATER.equals(top);
        put(x, FY - 1, z, state(top));
        if (water) put(x, FY - 2, z, state(PlazaGen.UNDER_WATER));
        Plaza.wet(x, z, water);
        if (Math.hypot(x - Plaza.CX, z - Plaza.CZ) < Plaza.R_SAFE + 3) return;
        String d = PlazaGen.deco(x, z, a, wt, top, buf);
        BlockState want = d == null ? air : state(d);
        pos.set(x, FY, z);
        BlockState cur = level.getBlockState(pos);
        if (cur == want || !(cur.isAir() || decoBlocks.contains(cur.getBlock()))) return;
        set(want);
    }

    private void put(int x, int y, int z, BlockState want) {
        pos.set(x, y, z);
        if (level.getBlockState(pos) == want) return;
        set(want);
    }

    /** pos 에 놓는다(물리 없음). 빛 성질이 달라지는 칸은 따로 센다. */
    private void set(BlockState want) {
        BlockState cur = level.getBlockState(pos);
        if (cur.getLightEmission() != want.getLightEmission() || cur.canOcclude() != want.canOcclude()) sLight++;
        level.setBlock(pos, want, FLAGS);
        sChanged++;
        touched.add(((long) (pos.getX() >> 4) << 40) ^ ((long) ((pos.getZ() >> 4) & 0xfffff) << 12) ^ ((pos.getY() >> 4) & 0xfff));
    }

    // ------------------------------------------------------------------ 재기

    /** 마지막(또는 하는 중인) 칠하기의 숫자 한 줄. */
    public String stats() {
        double sec = ((sEnd == 0 ? System.nanoTime() : sEnd) - sStart) / 1e9;
        String s = String.format(Locale.ROOT, "paint=%s act=%d wet=%.2f ticks=%d sec=%.1f cols=%d changed=%d(max %d/틱) light=%d ms=%.1f(avg %.2f max %.2f) sections=%d(max %d/틱) holes=%d foreign=%s",
            running ? String.format(Locale.ROOT, "run%.0f%%", progress() * 100) : sTicks == 0 ? "none" : "done", sAct, wet, sTicks, sec, sCols, sChanged, sChangedMax,
            sLight, sMsTotal, sTicks == 0 ? 0 : sMsTotal / sTicks, sMsMax, sSections, sSectionsMax, sHoles, running ? passForeign : needsCheck);
        if (tap != null) s += " tap(" + tapName + ")=" + tap;
        return s;
    }

    public int changed() {
        return sChanged;
    }

    /** 그 사람에게 나가는 블록, 빛, 청크 패킷을 세기 시작(다시 부르면 0 부터). p 가 null 이면 그만 센다. */
    public String tap(Player p) {
        if (tap != null) {
            Channel ch = tap.channel;
            try {
                if (ch.pipeline().get("gave_tap") != null) ch.pipeline().remove("gave_tap");
            } catch (RuntimeException ignored) {
                // 이미 닫힌 연결
            }
            tap = null;
        }
        if (p == null) return "재기 끔";
        try {
            Channel ch = ((CraftPlayer) p).getHandle().connection.connection.channel;
            Tap t = new Tap(ch);
            ch.pipeline().addBefore("packet_handler", "gave_tap", t);
            tap = t;
            tapName = p.getName();
            return "재기 켬 " + p.getName();
        } catch (RuntimeException | LinkageError e) {
            return "재기 못 걺: " + e;
        }
    }

    /** 나가는 패킷을 종류별로 센다(네티 스레드에서 불림). */
    private static final class Tap extends ChannelOutboundHandlerAdapter {
        final Channel channel;
        final AtomicInteger section = new AtomicInteger(), block = new AtomicInteger(), light = new AtomicInteger(), chunk = new AtomicInteger(),
            all = new AtomicInteger();

        Tap(Channel channel) {
            this.channel = channel;
        }

        void reset() {
            section.set(0);
            block.set(0);
            light.set(0);
            chunk.set(0);
            all.set(0);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            count(msg);
            super.write(ctx, msg, promise);
        }

        private void count(Object msg) {
            if (msg instanceof ClientboundBundlePacket b) {
                for (Packet<?> p : b.subPackets()) count(p);
                return;
            }
            all.incrementAndGet();
            if (msg instanceof ClientboundSectionBlocksUpdatePacket) section.incrementAndGet();
            else if (msg instanceof ClientboundBlockUpdatePacket) block.incrementAndGet();
            else if (msg instanceof ClientboundLightUpdatePacket) light.incrementAndGet();
            else if (msg instanceof ClientboundLevelChunkWithLightPacket) chunk.incrementAndGet();
        }

        @Override
        public String toString() {
            return "구역 " + section + " 낱칸 " + block + " 빛 " + light + " 청크 " + chunk + " 모든패킷 " + all;
        }
    }
}
