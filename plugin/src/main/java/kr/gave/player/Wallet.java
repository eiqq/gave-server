package kr.gave.player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import kr.gave.game.Game;
import kr.gave.gun.Guns;

/**
 * 돈 나누기(2026-11-14 사용자 "돈은 b안으로 하자 ... 배분을 관대하게", 근거와 숫자 build/audit/돈.md, build/notes/money_작업기록.md).
 * <p>
 * 게이브를 잡으면 그 보상의 {@link #TEAM}(70%)은 판에 든 모두가 똑같이 받고, 나머지(30%)는 때린 사람들이 때린 만큼 받는다.
 * 둘 이상이 함께 잡았으면 때린 사람들에게 {@link #JOINT}(10%)를 더 얹고, 줄 때 소수는 올린다. 그래서 여럿이 하면 나눠 준 합이
 * 보상의 100% 를 조금 넘는다(많아야 110% + 사람마다 정산 한 번에 1원 미만. 혼자면 정확히 100%).
 * <p>
 * 처치({@link #kill})는 몫을 쌓기만 하고 돈은 {@link #EVERY} 틱마다 한 번({@link #tick}) 준다: 처치마다 도는 것은 그 개체를 때린 사람 수뿐이고
 * 돈 HUD 줄은 처치 돈으로는 초당 한 번만 바뀐다. 팀 몫은 '활동 중'인 사람만 받는다: 최근 {@link #ACTIVE} 틱 안에 쐈거나 때렸거나 도왔음.
 * 쓰러진, 죽은, 갇힌 사람은 그렇게 되기 직전에 활동했으면 그동안에도 받는다. 기다리는 사람(판 중간 합류)은 못 받는다.
 * <p>
 * 팀 일: 일으키기와 짜내기는 수당({@link #helped}), 트럭 수리와 꺼내기에 낸 돈은 팀 몫에서 먼저 갚는다(수리는 PState.repairPaid 가 는 만큼,
 * 꺼내기는 {@link #owe}). 늦게 온 사람은 {@link #joinGrant}. 증강(현상금, 얇은 피, 빚, 팁)은 {@link #rate} 로 배율만 넣는다.
 */
public final class Wallet {

    /** 처치 보상에서 판에 든 모두가 똑같이 나누는 몫. 나머지(1 - TEAM)는 때린 사람들이 때린 만큼. */
    public static double TEAM = 0.7;
    /** 둘 이상이 함께 잡았을 때 때린 사람들에게 더 얹는 몫(보상에 대한 비율). */
    public static double JOINT = 0.1;
    /** 정산 간격(틱), 활동으로 치는 틱. */
    public static int EVERY = 20, ACTIVE = 1200;
    /** 한 번 정산에서 팀 일에 낸 돈을 갚는 데 쓰는 팀 몫의 상한(나머지는 바로 나눔: 갚는 동안에도 모두의 돈이 오르게). */
    public static double BACK = 0.5;
    /** 일으키기, 짜내기 수당(x U)과 한 라운드에 받는 횟수. */
    public static double HELP_U = 0.25;
    public static int HELP_MAX = 6;
    /** 늦게 온 사람이 받는 몫(판에 든 다른 사람들의 판 누적 벌이 평균에 곱함). */
    public static double JOIN = 0.7;

    private static final long NEVER = Long.MIN_VALUE / 2;

    /** 사람 하나의 셈(판이 바뀌면 지움). */
    private static final class Acct {
        /** 아직 안 준 몫(원, 소수). */
        double owed;
        /** 마지막으로 쏘거나 때리거나 돕거나 낸 틱. */
        long activeAt = NEVER;
        /** 지난 정산 때 총 발수(-1 = 아직 못 봄), 지난 정산 때 PState.repairPaid. */
        int shots = -1, repairSeen;
        /** 이번 라운드에 받은 수당 횟수, 팀이 갚을 돈(원). */
        int helps, due;
        /** 증강: 처치 돈 배율, 팁 몫. */
        double rate = 1, tip;
        /** 이번 정산에서 팀 몫을 받나. */
        boolean on;
    }

    private final Game game;
    private final Map<UUID, Acct> accts = new HashMap<>();
    /** 아직 안 나눈 팀 몫(원, 소수). */
    private double team;
    /** 지난 tick 의 판 시계(처치가 game 을 안 건드리게). */
    private long clock;
    private int round = -1, wave = -1;
    // 시험과 기록: 판 동안 쌓인 처치 보상, 나눠 준 처치 돈, 갚은 돈, 마지막 정산의 받은 사람 수와 한 사람 팀 몫
    private long rewardSum, paidSum, backSum;
    private int lastHeads, lastShare;

    public Wallet(Game game) {
        this.game = game;
    }

    private Acct acct(UUID id) {
        return accts.computeIfAbsent(id, k -> new Acct());
    }

    /** 줄 돈: 소수는 올린다(부동소수 찌꺼기는 버림). */
    static int up(double owed) {
        return owed <= 1e-9 ? 0 : (int) Math.ceil(owed - 1e-9);
    }

    // ------------------------------------------------------------------ 처치

    /**
     * 처치 하나(Mobs.payout 이 부름): 보상 total 원을 팀 몫과 때린 몫으로 쌓는다. contrib = 사람별로 그 개체에 준 피해.
     * 사람이 준 피해가 없으면(트럭 장치, 환경) 전부 팀 몫.
     */
    public void kill(Map<UUID, Double> contrib, int total) {
        if (total <= 0) return;
        rewardSum += total;
        double sum = 0;
        int n = 0;
        if (contrib != null) {
            for (double v : contrib.values()) {
                if (v > 0) {
                    sum += v;
                    n++;
                }
            }
        }
        if (n == 0) {
            team += total;
            return;
        }
        team += total * TEAM;
        double mine = total * (1 - TEAM + (n >= 2 ? JOINT : 0)) / sum;
        for (Map.Entry<UUID, Double> e : contrib.entrySet()) {
            double v = e.getValue();
            if (v <= 0) continue;
            Acct a = acct(e.getKey());
            a.owed += mine * v * a.rate;
            a.activeAt = clock;
        }
    }

    // ------------------------------------------------------------------ 정산

    /** 매 틱(Players.tick). EVERY 틱마다 한 번: 활동 보기 → 팀 일에 낸 돈 갚기 → 팀 몫 나누기 → 쌓인 몫 주기. */
    public void tick() {
        clock = game.now();
        if (game.round != round) {
            round = game.round;
            reset();
        }
        if (clock % EVERY != 0) return;
        if (game.wave != wave) {
            wave = game.wave;
            for (Acct a : accts.values()) a.helps = 0;
        }
        List<Player> in = game.players.inPlay();
        Acct[] as = new Acct[in.size()];
        int heads = 0, due = 0;
        for (int i = 0; i < as.length; i++) {
            Player p = in.get(i);
            PState s = game.players.get(p);
            Acct a = as[i] = acct(s.id);
            // 트럭 수리에 낸 돈(Shop 이 PState.repairPaid 에 적음)
            if (s.repairPaid > a.repairSeen) {
                a.due += s.repairPaid - a.repairSeen;
                a.activeAt = clock;
            }
            a.repairSeen = s.repairPaid;
            if (s.life == Life.ALIVE) {
                Guns.Arms arms = game.guns.arms(p);
                int shots = arms == null ? -1 : arms.shots;
                if (a.shots >= 0 && shots != a.shots) a.activeAt = clock;
                a.shots = shots;
            }
            a.on = s.life != Life.WAITING && (s.life == Life.ALIVE ? clock : s.lifeAt) - a.activeAt <= ACTIVE;
            if (a.on) heads++;
            due += a.due;
        }
        if (due > 0 && team >= 1) {
            int room = (int) Math.floor(team * BACK);
            for (int i = 0; i < as.length && room > 0; i++) {
                Acct a = as[i];
                if (a.due <= 0) continue;
                int back = Math.min(a.due, room);
                a.due -= back;
                room -= back;
                team -= back;
                backSum += back;
                game.players.addMoneyRaw(in.get(i), back);
                // 돌려받은 돈은 번 돈이 아니다(Revive.refund 와 같은 규칙: 판 기록과 번 돈을 따라 주는 효과가 부풀지 않게)
                game.players.get(in.get(i)).earned -= back;
            }
        }
        lastHeads = heads;
        lastShare = 0;
        if (heads > 0 && team > 0) {
            double each = team / heads;
            team = 0;
            lastShare = up(each);
            for (Acct a : as) if (a.on) a.owed += each * a.rate;
        }
        for (int i = 0; i < as.length; i++) {
            Acct a = as[i];
            int pay = up(a.owed);
            if (pay <= 0) continue;
            a.owed = 0;
            Player p = in.get(i);
            game.players.addMoney(p, pay);
            paidSum += pay;
            if (a.tip > 0) tip(in, p, up(pay * a.tip));
        }
    }

    /** 팁 증강: 서 있는 다른 사람 가운데 돈이 가장 적은 사람에게 새 돈을 준다. */
    private void tip(List<Player> in, Player from, int amount) {
        if (amount <= 0) return;
        Player poor = null;
        int least = Integer.MAX_VALUE;
        for (Player o : in) {
            if (o == from) continue;
            PState s = game.players.get(o);
            if (s.life == Life.ALIVE && s.money < least) {
                least = s.money;
                poor = o;
            }
        }
        if (poor != null) game.players.addMoney(poor, amount);
    }

    private void reset() {
        accts.clear();
        team = 0;
        rewardSum = paidSum = backSum = 0;
        lastHeads = lastShare = 0;
        wave = -1;
    }

    // ------------------------------------------------------------------ 팀 일, 합류, 증강

    /** 팀 일 수당: 쓰러진 동료를 일으켰거나 잠복을 짜냈다(Players 가 부름). 한 라운드에 HELP_MAX 번까지. */
    public void helped(Player helper) {
        if (helper == null) return;
        Acct a = acct(helper.getUniqueId());
        a.activeAt = clock;
        if (a.helps >= HELP_MAX) return;
        a.helps++;
        game.players.addMoney(helper, Math.max(1, (int) Math.round(HELP_U * game.trip.unit())));
    }

    /** 팀 일에 낸 돈(꺼내기가 끝났을 때 Revive 가 낸 사람마다 부름): 다음 정산부터 팀 몫에서 먼저 갚는다. */
    public void owe(UUID payer, int amount) {
        if (payer == null || amount <= 0) return;
        Acct a = acct(payer);
        a.due += amount;
        a.activeAt = clock;
    }

    /** 증강이 정하는 처치 돈 배율(현상금, 얇은 피, 빚)과 팁 몫(AugRules 가 1초에 한 번 넣음). */
    public void rate(UUID id, double earnMul, double tipFrac) {
        if (id == null) return;
        Acct a = acct(id);
        a.rate = earnMul > 0 && Double.isFinite(earnMul) ? earnMul : 1;
        a.tip = tipFrac > 0 && Double.isFinite(tipFrac) ? tipFrac : 0;
    }

    /** 늦게 온 사람의 따라잡기 몫(원): 판에 든 다른 사람들의 판 누적 벌이 평균 x JOIN. 옮기기(addMoneyRaw)로 줄 것. */
    public int joinGrant(PState me) {
        long sum = 0;
        int n = 0;
        for (Player p : game.players.inPlay()) {
            PState o = game.players.get(p);
            if (o == me || o.life == Life.WAITING) continue;
            sum += o.earned;
            n++;
        }
        return n == 0 ? 0 : (int) Math.round(JOIN * sum / n);
    }

    // ------------------------------------------------------------------ 시험

    public String status() {
        return String.format(Locale.ROOT, "[지갑] team=%.1f heads=%d share=%d reward=%d paid=%d back=%d ratio=%.3f accts=%d TEAM=%.2f JOINT=%.2f",
            team, lastHeads, lastShare, rewardSum, paidSum, backSum, rewardSum == 0 ? 0 : (double) paidSum / rewardSum, accts.size(), TEAM, JOINT);
    }

    /**
     * 시험 훅(Players.test 가 '지갑'으로 시작하는 행동을 넘김): 지갑 &lt;이름|x&gt;(상태), 지갑처치 &lt;이름&gt; &lt;보상&gt; [내 몫 0..1] [같이 때린 이름]
     * (게이브 없이 처치 하나를 흉내), 지갑값 x &lt;TEAM|JOINT&gt; &lt;값&gt;, 지갑검사 x(식 검사).
     */
    public String test(Player p, String[] a) {
        switch (a[1]) {
            case "지갑" -> {
                if (p == null) return status();
                Acct c = acct(p.getUniqueId());
                return status() + String.format(Locale.ROOT, " | %s owed=%.2f due=%d helps=%d rate=%.2f tip=%.2f idle=%d on=%s", p.getName(), c.owed, c.due,
                    c.helps, c.rate, c.tip, c.activeAt == NEVER ? -1 : clock - c.activeAt, c.on);
            }
            case "지갑처치" -> {
                if (p == null || a.length < 4) return "지갑처치 <이름> <보상> [내 몫 0..1] [같이 때린 이름]";
                try {
                    int total = Integer.parseInt(a[3]);
                    double part = a.length > 4 ? Math.max(0, Math.min(1, Double.parseDouble(a[4]))) : 1;
                    Player o = a.length > 5 ? org.bukkit.Bukkit.getPlayerExact(a[5]) : null;
                    Map<UUID, Double> c = new HashMap<>();
                    c.put(p.getUniqueId(), part);
                    if (o != null && o != p) c.put(o.getUniqueId(), 1 - part);
                    kill(c, total);
                    return status();
                } catch (NumberFormatException e) {
                    return "숫자가 아님";
                }
            }
            case "지갑값" -> {
                if (a.length < 5) return String.format(Locale.ROOT, "[지갑] TEAM=%.2f JOINT=%.2f", TEAM, JOINT);
                try {
                    double v = Double.parseDouble(a[4]);
                    if (!(v >= 0 && v <= 1)) return "0..1";
                    if (a[3].equals("TEAM")) TEAM = v;
                    else if (a[3].equals("JOINT")) JOINT = v;
                    else return "지갑값 x <TEAM|JOINT> <값>";
                    return String.format(Locale.ROOT, "[지갑] TEAM=%.2f JOINT=%.2f", TEAM, JOINT);
                } catch (NumberFormatException e) {
                    return "숫자가 아님";
                }
            }
            case "지갑검사" -> {
                String r = selfCheck();
                return r == null ? "[지갑] 검사 통과" : "[지갑] 검사 실패: " + r;
            }
            default -> {
                return null;
            }
        }
    }

    /** 식 검사(게임 없이 돎): 문제가 없으면 null. */
    static String selfCheck() {
        Wallet w = new Wallet(null);
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        double mine = 1 - TEAM;
        // 둘이 6 대 4 로 함께 잡음: 팀 몫 + 때린 몫에 덤
        w.kill(Map.of(a, 60.0, b, 40.0), 100);
        // 혼자 잡음: 덤 없음
        w.kill(Map.of(a, 5.0), 50);
        // 사람이 준 피해 없음: 전부 팀 몫
        w.kill(null, 10);
        // 배율(빚 0.5)은 그 뒤 처치부터
        w.rate(b, 0.5, 0);
        w.kill(Map.of(b, 1.0), 20);
        double team = 100 * TEAM + 50 * TEAM + 10 + 20 * TEAM;
        double oa = 100 * (mine + JOINT) * 0.6 + 50 * mine, ob = 100 * (mine + JOINT) * 0.4 + 20 * mine * 0.5;
        if (Math.abs(w.team - team) > 1e-9) return "team " + w.team + " != " + team;
        if (Math.abs(w.acct(a).owed - oa) > 1e-9) return "a " + w.acct(a).owed + " != " + oa;
        if (Math.abs(w.acct(b).owed - ob) > 1e-9) return "b " + w.acct(b).owed + " != " + ob;
        // 혼자 판은 넘치지 않는다: 팀 몫 + 때린 몫 = 보상
        Wallet solo = new Wallet(null);
        solo.kill(Map.of(a, 3.0), 27);
        if (Math.abs(solo.team + solo.acct(a).owed - 27) > 1e-9) return "solo " + (solo.team + solo.acct(a).owed);
        if (up(0) != 0 || up(0.2) != 1 || up(3.0) != 3 || up(3.0000000001) != 3 || up(3.01) != 4) return "up";
        return null;
    }

    /** java -cp Gave.jar(와 서버 라이브러리) kr.gave.player.Wallet */
    public static void main(String[] args) {
        String r = selfCheck();
        System.out.println(r == null ? "지갑 검사 통과" : "지갑 검사 실패: " + r);
        if (r != null) System.exit(1);
    }
}
