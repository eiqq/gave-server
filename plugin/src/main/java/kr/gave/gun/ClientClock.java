package kr.gave.gun;

import org.bukkit.World;

import net.minecraft.server.MinecraftServer;

/**
 * 클라가 지금 읽고 있을 세계 시각(셰이더 도장용).
 * <p>
 * 총구 불꽃, 탄 궤적, 1인칭 총 몸짓, 적중 표시 같은 연출은 서버가 '세계 시각 도장'을 색 칸에 싣고 팩 셰이더가 '클라 시계 - 도장'으로 나이를 잰다.
 * 그런데 클라 시계는 클라 틱마다 스스로 1씩 가고(ClientLevel.tickTime), 서버는 서버 틱 20번에 한 번(MinecraftServer.tickChildren 의
 * tickCount % 20 == 0, 모든 사람에게 같은 틱에)만 제 시각으로 맞춰 준다. 서버가 20 TPS 아래로 떨어지면 그사이 클라 시계가 앞서가서
 * (15 TPS 면 맞추기 직전 6.7틱) 서버 시각으로 찍은 도장은 닿았을 때 이미 지난 것이 되고, 수명 2.3틱짜리 총구 불꽃은 아예 안 보인다.
 * <p>
 * 그래서 도장에는 서버 시각 대신 {@link #time}(= 서버 시각 + {@link #ahead})을 쓴다: 마지막으로 맞춘 틱부터 흐른 실제 시간으로 클라 시계가
 * 얼마나 앞서 있을지를 어림한다. 서버가 제 빠르기면 0 이라 서버 시각과 같다. 맞춘 직후에는 클라 시계가 뒤로 물러나므로 이 값도 줄어든다
 * (끝났는지 같은 논리 비교에는 쓰지 말고 도장을 만들 때만 더할 것). 사람마다 핑이 달라도 맞춤 패킷과 도장 패킷이 같은 만큼 늦게 닿으므로 값은 모두에게 같다.
 * {@link #tick} 을 매 서버 틱 맨 앞(스케줄러 일)에서 불러야 한다(Guns.watch 가 부름).
 */
public final class ClientClock {
    private ClientClock() {
    }

    /** 앞섬 어림의 위끝(틱): 틱이 아주 길게 멈춘 뒤 엉뚱한 도장을 찍지 않게 */
    private static final int MAX_AHEAD = 10;
    private static long syncNanos, syncTime;
    private static int syncTick = Integer.MIN_VALUE;
    /** 시험: 끄면 예전처럼 서버 시각 그대로 */
    static boolean enabled = true;

    /** 매 서버 틱 맨 앞: 이번 틱에 서버가 클라 시계를 맞추면(또는 오래 못 봤으면) 그 때와 그때의 세계 시각을 적어 둔다 */
    static void tick(World w) {
        int tc = MinecraftServer.getServer().getTickCount();
        if (tc % 20 == 0 || tc - syncTick > 20 || tc < syncTick) {
            syncNanos = System.nanoTime();
            syncTime = w.getGameTime();
            syncTick = tc;
        }
    }

    /** 클라 시계가 서버 시각보다 앞서 있을 틱 수(0..10). 서버가 제 빠르기면 0 */
    public static int ahead(World w) {
        if (!enabled || syncTick == Integer.MIN_VALUE) return 0;
        long clientRan = (System.nanoTime() - syncNanos) / 50_000_000L, serverRan = w.getGameTime() - syncTime;
        return (int) Math.max(0, Math.min(MAX_AHEAD, clientRan - serverRan));
    }

    /** 셰이더 도장에 쓸 시각: 이 도장이 닿을 때 클라 시계가 가리킬 세계 시각 */
    public static long time(World w) {
        return w.getGameTime() + ahead(w);
    }
}
