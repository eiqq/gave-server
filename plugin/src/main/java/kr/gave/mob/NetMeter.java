package kr.gave.mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;

/**
 * 시험용 패킷 재기(시험 훅 '패킷'): 한 사람에게 나가는 패킷 수와 바이트를 센다. 바이트는 압축과 길이 머리까지 붙은 뒤(소켓에 쓰는 그대로,
 * TCP/IP 머리는 빼고), 패킷 수는 그 프레임 수(묶음의 앞뒤 표시도 하나씩)다. 종류별 수는 부호기 앞에서 패킷 객체로 센다(묶음 안의 것도).
 * {@link #tick} 을 서버 틱마다 부르면 틱당 평균과 가장 많은 1초(20틱)를 낸다.
 */
final class NetMeter {

    private static final String WIRE = "gave_meter_wire", KINDS = "gave_meter_kinds";

    final Player player;
    private final Channel channel;
    private final AtomicLong frames = new AtomicLong(), bytes = new AtomicLong();
    private final Map<String, AtomicLong> kinds = new ConcurrentHashMap<>();
    private long lastFrames, lastBytes, ticks, sumFrames, sumBytes, peakFrames, peakBytes, peakSecFrames, peakSecBytes;
    private final long[] ringF = new long[20], ringB = new long[20];

    private NetMeter(Player player, Channel channel) {
        this.player = player;
        this.channel = channel;
    }

    /** 이 사람의 연결에 세는 손잡이 둘을 끼운다(이미 있으면 빼고 다시). */
    static NetMeter attach(Player p) {
        Channel ch = ((CraftPlayer) p).getHandle().connection.connection.channel;
        NetMeter m = new NetMeter(p, ch);
        m.detach();
        ch.pipeline().addFirst(WIRE, new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (msg instanceof ByteBuf b) {
                    m.frames.incrementAndGet();
                    m.bytes.addAndGet(b.readableBytes());
                }
                super.write(ctx, msg, promise);
            }
        });
        if (ch.pipeline().get(HandlerNames.PACKET_HANDLER) != null) {
            ch.pipeline().addBefore(HandlerNames.PACKET_HANDLER, KINDS, new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    m.kind(msg);
                    super.write(ctx, msg, promise);
                }
            });
        }
        return m;
    }

    private void kind(Object msg) {
        if (msg instanceof BundlePacket<?> b) {
            for (Packet<?> s : b.subPackets()) kind(s);
            return;
        }
        if (msg instanceof Packet<?>) kinds.computeIfAbsent(msg.getClass().getSimpleName(), k -> new AtomicLong()).incrementAndGet();
    }

    void detach() {
        try {
            if (channel.pipeline().get(WIRE) != null) channel.pipeline().remove(WIRE);
            if (channel.pipeline().get(KINDS) != null) channel.pipeline().remove(KINDS);
        } catch (RuntimeException e) {
            // 연결이 닫혔으면 그만
        }
    }

    /** 서버 틱마다: 이번 틱에 나간 양을 적는다. */
    void tick() {
        long f = frames.get(), b = bytes.get(), df = f - lastFrames, db = b - lastBytes;
        lastFrames = f;
        lastBytes = b;
        int i = (int) (ticks % 20);
        ringF[i] = df;
        ringB[i] = db;
        ticks++;
        sumFrames += df;
        sumBytes += db;
        if (db > peakBytes) {
            peakBytes = db;
            peakFrames = df;
        }
        if (ticks >= 20) {
            long sf = 0, sb = 0;
            for (int k = 0; k < 20; k++) {
                sf += ringF[k];
                sb += ringB[k];
            }
            if (sb > peakSecBytes) {
                peakSecBytes = sb;
                peakSecFrames = sf;
            }
        }
    }

    /** 세던 것을 0 으로. */
    void reset() {
        lastFrames = frames.get();
        lastBytes = bytes.get();
        ticks = sumFrames = sumBytes = peakFrames = peakBytes = peakSecFrames = peakSecBytes = 0;
        java.util.Arrays.fill(ringF, 0);
        java.util.Arrays.fill(ringB, 0);
        kinds.clear();
    }

    /** 틱 수, 합, 틱당 평균, 가장 많은 틱과 1초, 종류별 수(많은 것 8개). */
    String report() {
        List<Map.Entry<String, AtomicLong>> top = new ArrayList<>(kinds.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, top.size()); i++) sb.append(i == 0 ? "" : ",").append(top.get(i).getKey()).append('=').append(top.get(i).getValue().get());
        double t = Math.max(1, ticks);
        return String.format(Locale.ROOT, "net=%s ticks=%d packets=%d bytes=%d perTick=%.1fp/%.0fB peakTick=%dp/%dB peakSec=%dp/%dB kinds=%s", player.getName(), ticks,
                sumFrames, sumBytes, sumFrames / t, sumBytes / t, peakFrames, peakBytes, peakSecFrames, peakSecBytes, sb);
    }
}
