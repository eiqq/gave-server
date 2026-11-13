package kr.gave.trip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;

import kr.gave.game.Game;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * 길(원정.md 3절): 판마다 무작위 지도(스테이지 15개와 갈림길 14개, 30 뒤 발사장), 짝수 라운드 뒤 쉬는 시간의 갈림길 투표와 이동, 스테이지 종류의 규칙과 보상.
 *
 * <p>지도: 판을 시작할 때 판 씨앗으로 갈림길 14개(갈래 셋씩)를 모두 굴린다. 갈림길 k(1..14)는 스테이지 k 뒤(라운드 2k 뒤)에 열리고 스테이지 k + 1 을 고른다.
 * 스테이지 1 은 늘 '국도'(전투), 스테이지 5, 10, 15 는 보스 길목(갈래 셋은 준비 셋: 숨 고르기, 무장, 질주), 16 은 발사장(투표 없이 들어감).
 * 규칙: 세 갈래는 종류가 서로 다르고, 하나는 위험 1(전투, 보급, 정비), 하나는 위험 2 이상. 막마다 종류 무게가 다르고 막마다 험로가 적어도 한 번 갈래에 나온다.
 * 열 때 고치는 것: 고른 길로 같은 종류(전투 빼고)가 세 번 이어지지 않게, 트럭 체력이 낮거나 죽은 사람이 많으면 정비가 꼭 하나.
 * 아무도 험로를 안 골라도 막마다 특수 라운드가 한 번 끼게 그 막의 한 스테이지 첫 라운드를 바꾼다(Rounds 가 'roundSpec' 으로 읽음).
 * 설정 trip.noSpecials(기본 [chase])로 막은 특수는 보장, 보스 길목 질주(그 자리는 카드 없는 험로), 카드 특수에서 빠진다.
 *
 * <p>쉬는 시간(짝수 라운드 n &lt;= 28 뒤): 틱 open 에 표지판 셋({@link RouteVote}), 이동 시작 close 틱 전에 닫고 갈래를 정함, 틱 shop 에 이동({@link Travel}).
 * 30 라운드 뒤는 투표 없이 발사장으로. 이동의 화면이 다 가려졌을 때 스테이지를 넘기고(막, 스테이지 답이 바뀜) 소품을 바꾸며, 드러날 때 도착 보상과 이름을 낸다.
 *
 * <p>다른 부품이 읽는 것({@link Trip#ask}): act, stage, stageKind, stageCard(카드 키), roundSpec(이번 라운드의 풀 글: pool:plain, special:chase, event, boss ...),
 * bossHp(보스 체력 배율, 질주를 끝까지 달렸을 때 0.92), disp(디스플레이 수). {@link Trip#mul}: price, revive, truckShare, reward, money(스테이지 규칙).
 * 사건: {@link Trip#EV_DEPART}, {@link Trip#EV_ARRIVE}(arg = 스테이지 종류 키), {@link StageRules#EV_REWARD}. 받는 사건: chaseDone(보스 길목 질주를 끝까지 달림).
 */
public final class Route implements TripPart {

    private final Game game;
    private final Trip trip;
    private final StageData data;
    private final RouteVote vote;
    private final Scenery scenery;
    private final StageRules rules;
    /** 설정 trip.noSpecials 로 막은 특수 키(Rounds.blockedSpecials): 보장, 보스 길목 질주, 험로와 함정 카드에서 뺀다. */
    private final Set<String> blocked;

    /** 갈림길 1..14 의 갈래 셋. */
    private final Stage[][] forks = new Stage[15][];
    private Stage first, current;
    /** 지금 스테이지(1..16), 판 밖이면 0. */
    private int stage;
    /** 고른 갈래(갈림길 차례대로 0..2), 지나온 스테이지의 종류. */
    private final List<Integer> picks = new ArrayList<>();
    private final List<String> path = new ArrayList<>();
    /** 막 → 특수 라운드를 꼭 끼울 스테이지, 그 막에서 특수 라운드가 이미 있었나. */
    private final Map<Integer, Integer> forcedStage = new HashMap<>();
    private final Set<Integer> specialActs = new HashSet<>();
    /** 이번 라운드의 풀 글(beforeRound 에서 정함). */
    private String roundSpec = "pool:plain";

    /** 쉬는 시간 계획: 끝난 라운드(없으면 -1), 투표가 있나, 틱들(쉬는 시간 시작 뒤), 이동 길이, 막 넘김인가. */
    private int planRound = -1;
    private boolean planVote, planAct, opened, departed;
    private int openAt, closeAt, travelAt, travelLen;
    /** 이동이 끝난 뒤 갈 스테이지. */
    private Stage next;
    /** 도착한 스테이지 이름(글귀 키)과 그때(판 틱): HUD ST_NAME 칸에 NAME_TICKS 동안. */
    private String arriveName;
    private long arriveAt = -1;
    public static int NAME_TICKS = 80;
    /** 센 것(시험, 기록): 열린 갈림길, 이동, 고쳐 넣은 갈래. */
    private int opens, travels, patches;

    public Route(Game game, Trip trip) {
        this.game = game;
        this.trip = trip;
        this.data = StageData.load(Route.class.getClassLoader(), game.plugin.getLogger());
        this.vote = new RouteVote(game, trip, data, this);
        this.scenery = new Scenery(game, data);
        this.rules = new StageRules(game, trip, data);
        this.blocked = Rounds.blockedSpecials(game);
        game.plugin.getLogger().info("[길] " + data.note);
    }

    // ------------------------------------------------------------------ 읽기(다른 부품)

    public StageData data() {
        return data;
    }

    /** 지금 스테이지(1..16, 판 밖이면 0). */
    public int stage() {
        return stage;
    }

    /** 지금 스테이지(판 밖이면 null). */
    public Stage current() {
        return current;
    }

    public RouteVote vote() {
        return vote;
    }

    public Scenery scenery() {
        return scenery;
    }

    /** 갈림길 k(1..14)의 갈래 셋(없으면 null). */
    public Stage[] fork(int k) {
        return k >= 1 && k <= 14 ? forks[k] : null;
    }

    /** 고른 갈래(갈림길 차례대로 1..3)와 지나온 종류: 기록용 글. */
    public String pathText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append('>');
            sb.append(path.get(i));
            if (i > 0 && i - 1 < picks.size()) sb.append('(').append(picks.get(i - 1) + 1).append(')');
        }
        return sb.toString();
    }

    /**
     * 돕기(좌클릭) 차례에 끼는 곳(Players.help 가 상점 앞, 죽은 사람도 받게 맨 앞에서 부른다): 투표 창이 열려 있고 표지판을 겨눴으면 표를 넣고 true.
     * 이것이 한 번이라도 불리면 휘두름을 직접 받는 길은 꺼진다.
     */
    public boolean help(Player p) {
        vote.hooked = true;
        return vote.help(p);
    }

    // ------------------------------------------------------------------ 지도 굴리기

    private void generate() {
        first = new Stage(data, 1, Stage.FIGHT);
        first.card = data.card("a1_road");
        forcedStage.clear();
        roll(trip.rng("route"), forks, forcedStage);
    }

    /** 씨앗 하나로 갈림길 14개를 굴려 into[1..14] 에 넣는다(시험 훅 '길 x 굴리기' 도 이것으로 분포를 센다). */
    private void roll(Random r, Stage[][] forks, Map<Integer, Integer> forcedStage) {
        Set<String> usedCards = new HashSet<>();
        usedCards.add("a1_road");
        for (int k = 1; k <= 14; k++) {
            int s = k + 1, act = Stage.actOf(s);
            Stage[] o = new Stage[3];
            if (Stage.gateStage(s) && data.gates.size() >= 3) {
                List<StageData.Gate> g = new ArrayList<>(data.gates);
                Collections.shuffle(g, r);
                for (int i = 0; i < 3; i++) {
                    // 질주: 추격전(3막은 마지막 질주)
                    String sp = g.get(i).first.startsWith("special") ? act == 3 ? "lastrun" : "chase" : null;
                    if (sp != null && blocked.contains(sp)) {
                        // 그 특수를 막았으면(trip.noSpecials) 질주 갈래는 표지판에 안 낸다: 그 자리는 카드 없는 험로(첫 라운드는 그 막의 다른 특수, 둘째는 보스)
                        o[i] = new Stage(data, s, data.types.containsKey(Stage.RUSH) ? Stage.RUSH : Stage.FIGHT);
                        continue;
                    }
                    o[i] = new Stage(data, s, Stage.GATE);
                    o[i].gate = g.get(i);
                    o[i].special = sp;
                }
            } else {
                List<String> types = pickTypes(act, r);
                for (int i = 0; i < 3; i++) o[i] = make(s, types.get(i), r, usedCards);
            }
            forks[k] = o;
        }
        // 막마다 험로가 적어도 한 번 갈래에 나온다
        int[][] acts = {{1, 3}, {5, 8}, {10, 13}};
        for (int[] range : acts) {
            boolean has = false;
            for (int k = range[0]; k <= range[1]; k++) for (Stage st : forks[k]) if (Stage.RUSH.equals(st.type)) has = true;
            if (has || !data.types.containsKey(Stage.RUSH)) continue;
            int k = range[0] + r.nextInt(range[1] - range[0] + 1);
            // 위험 2 이상인 갈래 하나를 험로로(위험 1 갈래는 지킨다)
            List<Integer> can = new ArrayList<>();
            for (int i = 0; i < 3; i++) if (!data.safe(forks[k][i].type) && !Stage.UNKNOWN.equals(forks[k][i].type)) can.add(i);
            if (can.isEmpty()) for (int i = 0; i < 3; i++) if (!data.safe(forks[k][i].type)) can.add(i);
            if (can.isEmpty()) continue;
            int i = can.get(r.nextInt(can.size()));
            forks[k][i] = make(k + 1, Stage.RUSH, r, usedCards);
        }
        for (Map.Entry<Integer, int[]> e : data.forced.entrySet()) {
            int[] ft = e.getValue();
            forcedStage.put(e.getKey(), ft[0] + r.nextInt(Math.max(1, ft[1] - ft[0] + 1)));
        }
    }

    /** 세 갈래의 종류: 서로 다르고, 하나는 위험 1, 하나는 위험 2 이상. */
    private List<String> pickTypes(int act, Random r) {
        Map<String, Integer> w = data.weights(act);
        List<String> out = new ArrayList<>();
        for (int tries = 0; tries < 80; tries++) {
            out.clear();
            for (int i = 0; i < 3; i++) {
                String t = weighted(w, r, out);
                if (t != null) out.add(t);
            }
            if (out.size() < 3) break;
            boolean safe = false, risky = false;
            for (String t : out) {
                if (data.safe(t)) safe = true;
                else if (data.type(t).risk >= 2) risky = true;
            }
            if (safe && risky) {
                Collections.shuffle(out, r);
                return out;
            }
        }
        // 종류가 모자란 표(떨어진 데이터): 있는 것으로 채움
        while (out.size() < 3) out.add(Stage.FIGHT);
        return out;
    }

    private static String weighted(Map<String, Integer> w, Random r, List<String> exclude) {
        int total = 0;
        for (Map.Entry<String, Integer> e : w.entrySet()) if (e.getValue() > 0 && !exclude.contains(e.getKey())) total += e.getValue();
        if (total <= 0) return null;
        int x = r.nextInt(total);
        for (Map.Entry<String, Integer> e : w.entrySet()) {
            if (e.getValue() <= 0 || exclude.contains(e.getKey())) continue;
            x -= e.getValue();
            if (x < 0) return e.getKey();
        }
        return null;
    }

    /** 그 스테이지 번호, 그 종류의 갈래 하나(카드, 미지의 속, 사건, 카드의 특수 라운드까지 굴림). */
    private Stage make(int s, String type, Random r, Set<String> usedCards) {
        Stage st = new Stage(data, s, data.types.containsKey(type) ? type : Stage.FIGHT);
        List<StageData.Card> cards = data.cards(st.act, st.type);
        List<StageData.Card> fresh = new ArrayList<>();
        for (StageData.Card c : cards) if (usedCards == null || !usedCards.contains(c.key)) fresh.add(c);
        if (fresh.isEmpty()) fresh = cards;
        if (!fresh.isEmpty()) {
            st.card = fresh.get(r.nextInt(fresh.size()));
            if (usedCards != null) usedCards.add(st.card.key);
        }
        if (Stage.UNKNOWN.equals(st.type)) st.hidden = weighted(data.unknown(st.act), r, List.of());
        if (Stage.EVENT.equals(st.type) || Stage.EVENT.equals(st.hidden)) {
            List<StageData.Event> evs = data.events(st.act);
            if (!evs.isEmpty()) st.event = evs.get(r.nextInt(evs.size())).key;
        }
        // 카드의 특수(막은 것이면 빼서 그 종류의 풀 글대로: 그 막 특수)
        if (st.card != null && st.card.special != null && !blocked.contains(st.card.special) && (Stage.RUSH.equals(st.type) || Stage.TRAP.equals(st.type)))
            st.special = st.card.special;
        return st;
    }

    // ------------------------------------------------------------------ 스테이지 넘기기

    /** 그 스테이지에 선다(효과 표와 소품). */
    private void enter(Stage st) {
        current = st;
        stage = st.index;
        if (Stage.UNKNOWN.equals(st.type)) st.revealed = true;
        path.add(st.kind());
        if (st.special != null && (Stage.RUSH.equals(st.kind()) || st.gate != null)) specialActs.add(st.act);
        rules.enter(st);
        scenery.set(st, trip.rng("scenery" + st.index));
    }

    /** 라운드를 건너뛰어(시험 /gave 웨이브 n) 스테이지가 어긋났을 때: 사이 갈림길은 가운데 갈래로 친다. 이동 연출과 도착 보상은 없다. */
    private void jump(int target) {
        if (first == null) generate();
        if (target < stage || current == null) {
            stage = 0;
            picks.clear();
            path.clear();
            specialActs.clear();
            enter(first);
        }
        while (stage < target) {
            Stage st;
            if (stage >= 15) st = new Stage(data, 16, Stage.LAUNCH);
            else {
                st = forks[stage][1];
                picks.add(1);
            }
            enter(st);
        }
        trip.log("스테이지 맞춤(건너뜀) " + current + " 길 " + pathText());
    }

    private void cancelPlan() {
        planRound = -1;
        opened = departed = false;
        next = null;
    }

    // ------------------------------------------------------------------ 갈림길 열기, 떠나기

    /** 갈림길을 연다(열 때 고치는 것 둘을 한 뒤 표지판을 세움). */
    private void openFork() {
        int k = stage;
        if (k < 1 || k > 14) return;
        Stage[] o = forks[k];
        if (!Stage.gateStage(k + 1)) {
            Random r = trip.rng("patch" + k);
            // 고른 길로 같은 종류(전투 빼고)가 세 번 이어지지 않게
            int n = path.size();
            if (n >= 2 && path.get(n - 1).equals(path.get(n - 2)) && !Stage.FIGHT.equals(path.get(n - 1))) {
                String rep = path.get(n - 1);
                for (int i = 0; i < 3; i++) {
                    if (!o[i].type.equals(rep)) continue;
                    List<String> ex = new ArrayList<>(List.of(rep));
                    for (Stage st : o) ex.add(st.type);
                    String t = weighted(data.weights(o[i].act), r, ex);
                    if (t == null) continue;
                    o[i] = make(k + 1, t, r, null);
                    o[i].patched = true;
                    patches++;
                }
            }
            // 트럭 체력이 낮거나 죽은 사람이 많으면 정비가 꼭 하나
            if (needRest() && data.types.containsKey(Stage.REST)) {
                boolean has = false;
                for (Stage st : o) if (Stage.REST.equals(st.type)) has = true;
                if (!has) {
                    int at = -1;
                    for (int i = 0; i < 3 && at < 0; i++) if (data.safe(o[i].type)) at = i;
                    for (int i = 0; i < 3 && at < 0; i++) if (!Stage.RUSH.equals(o[i].type)) at = i;
                    if (at < 0) at = 1;
                    o[at] = make(k + 1, Stage.REST, r, null);
                    o[at].patched = true;
                    patches++;
                }
            }
        }
        vote.open(o, k + 1 <= 14 ? forks[k + 1] : null);
        opens++;
    }

    /** 정비 보장 문턱: 트럭 체력 비율(Truck 이 'truckFrac' 으로 답하면)이 낮거나 죽은 사람 비율이 높다. */
    private boolean needRest() {
        Object f = trip.ask("truckFrac");
        if (f instanceof Number n && n.doubleValue() < data.restTruck) return true;
        int dead = 0, all = 0;
        for (Player p : game.players.inPlay()) {
            PState s = game.players.get(p);
            if (s.life == Life.LOBBY) continue;
            all++;
            if (s.life == Life.DEAD) dead++;
        }
        return all > 0 && (double) dead / all >= data.restDead && dead > 0;
    }

    /** 떠난다: 정해진 갈래(없으면 가운데)로 이동 연출을 건다. 발사장 진입(30 뒤)은 투표 없이. */
    private void depart(int ticks, boolean act) {
        if (stage < 1 || stage > 15 || current == null) return;
        int pick = -1;
        if (stage <= 14) {
            if (vote.open()) vote.close();
            pick = vote.result();
            if (pick < 0) pick = vote.settle();
            if (pick < 0 || !vote.standing()) pick = 1;
            next = forks[stage][pick];
            picks.add(pick);
        } else next = new Stage(data, 16, Stage.LAUNCH);
        travels++;
        trip.fire(Trip.EV_DEPART, current.kind());
        game.hud.notice(next.phrase(), 60);
        trip.log("떠남 " + current + " → " + next + (pick >= 0 ? " 갈래 " + (pick + 1) : "") + " 표 " + java.util.Arrays.toString(vote.counts()));
        Travel t = trip.part(Travel.class);
        if (t != null) t.start(ticks, act, Trip.STAGES - next.index, this::hidden, this::reveal);
        else {
            hidden();
            reveal();
        }
    }

    /** 이동의 화면이 다 가려짐: 표지판과 옛 소품을 치우고 다음 스테이지에 선다(막, 스테이지 답이 여기서 바뀜). */
    private void hidden() {
        vote.clear();
        if (next != null) enter(next);
    }

    /** 다시 드러남: 도착 보상, 스테이지 이름, 사건 EV_ARRIVE. */
    private void reveal() {
        if (next == null || current == null) return;
        next = null;
        rules.arrive(current, false);
        // 도착한 스테이지 이름(미지는 드러난 종류): HUD ST_NAME 칸(hud 값 st.name)에 NAME_TICKS 동안
        arriveName = Stage.UNKNOWN.equals(current.type) ? "rt_type_" + current.kind() : current.phrase();
        arriveAt = game.now();
        trip.fire(Trip.EV_ARRIVE, current.kind());
    }

    // ------------------------------------------------------------------ TripPart

    @Override
    public void startGame() {
        cancelPlan();
        vote.clear();
        picks.clear();
        path.clear();
        specialActs.clear();
        rules.reset();
        opens = travels = patches = 0;
        generate();
        stage = 0;
        enter(first);
        StringBuilder sb = new StringBuilder("지도 씨앗 " + trip.seed + ":");
        for (int k = 1; k <= 14; k++) sb.append(" [").append(k).append("] ").append(forks[k][0]).append(" | ").append(forks[k][1]).append(" | ").append(forks[k][2]);
        sb.append(" 특수 보장 ").append(forcedStage);
        trip.log(sb.toString());
    }

    @Override
    public void beforeRound(int n) {
        // 라운드가 열린다: 쉬는 시간 계획과 표지판은 끝(시험으로 건너뛴 경우 포함)
        if (vote.standing()) vote.clear();
        cancelPlan();
        int target = Trip.stageOf(n);
        if (current == null || target != stage) jump(target);
        if (force(current, n, forcedStage, specialActs)) trip.log("특수 라운드 보장 " + current + " 라운드 " + n);
        roundSpec = current.roundSpec(n);
    }

    /**
     * 특수 라운드 보장(beforeRound 와 모의가 같이 씀): 이 막에서 아직 없었고 st 가 그 막의 정한 스테이지이고 n 이 그 첫 라운드면 st.special 을 정하고 true.
     * 정한 특수(route.json rules.forced, 1막 chase)를 막았으면(trip.noSpecials) 그 막 특수(actN: Rounds 가 막은 것을 빼고 고름).
     */
    private boolean force(Stage st, int n, Map<Integer, Integer> fsm, Set<Integer> acts) {
        int act = st.act;
        Integer fs = fsm.get(act);
        if (fs == null || fs != st.index || n != st.firstRound() || acts.contains(act) || st.gate != null || st.special != null) return false;
        String sp = data.forcedSpecial.getOrDefault(act, "");
        st.special = sp.isEmpty() || blocked.contains(sp) ? "act" + act : sp;
        st.forced = true;
        acts.add(act);
        return true;
    }

    /**
     * 모의 한 판(시험 훅 '길 x 특수모의'): r 로 지도를 f 에 굴리고 갈래를 무작위로 골라 지나가며 라운드 1..30 의 줄 {막, 라운드, 풀 글}을 낸다
     * (미지는 드러난 것으로, 보장은 beforeRound 와 같은 셈. 갈림길을 열 때 고치는 갈래는 빠짐: 그것도 make 를 지나므로 카드 특수는 같이 걸러짐).
     */
    private List<Object[]> simulate(Random r, Stage[][] f) {
        Map<Integer, Integer> fsm = new HashMap<>();
        roll(r, f, fsm);
        Set<Integer> acts = new HashSet<>();
        List<Object[]> out = new ArrayList<>();
        Stage st = new Stage(data, 1, Stage.FIGHT);
        st.card = data.card("a1_road");
        for (int s = 1; s <= 15; s++) {
            if (s > 1) st = f[s - 1][r.nextInt(3)];
            if (Stage.UNKNOWN.equals(st.type)) st.revealed = true;
            if (st.special != null && (Stage.RUSH.equals(st.kind()) || st.gate != null)) acts.add(st.act);
            for (int n = st.firstRound(); n <= st.secondRound(); n++) {
                force(st, n, fsm, acts);
                out.add(new Object[] {st.act, n, st.roundSpec(n)});
            }
        }
        return out;
    }

    @Override
    public void beginRound(int n) {
        rules.beginRound(n);
    }

    @Override
    public void beginBreak(int n) {
        cancelPlan();
        if (current == null) return;
        if (n == current.secondRound()) rules.end(current);
        boolean fork = n % 2 == 0 && n <= 28 && stage == n / 2;
        boolean launch = n == Trip.LAST_ROAD_ROUND && stage == 15;
        if (!fork && !launch) return;
        planRound = n;
        planVote = fork;
        planAct = n == 10 || n == 20 || launch;
        openAt = data.voteOpen;
        travelAt = data.voteShop;
        closeAt = Math.max(openAt + 20, travelAt - data.voteClose);
        travelLen = planAct ? data.travelAct : data.travelNormal;
    }

    @Override
    public int breakTicks(int ticks) {
        if (planRound < 0 || game.wave != planRound) return ticks;
        return Math.max(ticks, travelAt + travelLen);
    }

    @Override
    public void tick() {
        vote.tick();
        rules.tick();
        if (planRound < 0) return;
        if (game.state != Game.State.BREAK || game.wave != planRound) {
            // 쉬는 시간이 아닌데 계획이 남았다(판 끝, 정리): 표지판을 치움
            if (game.state != Game.State.WAVE) vote.clear();
            cancelPlan();
            return;
        }
        long t = game.stateTicks();
        if (planVote && !opened && t >= openAt) {
            opened = true;
            openFork();
        }
        if (planVote && opened && vote.open() && t >= closeAt) vote.close();
        if (!departed && t >= travelAt) {
            departed = true;
            depart(travelLen, planAct);
        }
    }

    @Override
    public void gameOver() {
        vote.clear();
        cancelPlan();
    }

    @Override
    public void reset() {
        vote.clear();
        scenery.clear();
        rules.reset();
        cancelPlan();
        picks.clear();
        path.clear();
        specialActs.clear();
        current = first = null;
        stage = 0;
        arriveName = null;
        arriveAt = -1;
    }

    @Override
    public void enabled(boolean on) {
        if (!on) reset();
    }

    @Override
    public double mul(String key) {
        return current == null ? 1 : rules.mul(key);
    }

    @Override
    public Object ask(String key) {
        if (key.equals("disp")) return vote.displays() + scenery.displays();
        if (current == null) return null;
        return switch (key) {
            case "act" -> current.act;
            case "stage" -> stage;
            case "stageKind" -> current.kind();
            case "stageCard" -> current.card == null ? null : current.card.key;
            case "roundSpec" -> roundSpec;
            case "bossHp" -> {
                double m = rules.mul("bossHp");
                yield m == 1 ? null : (Object) m;
            }
            default -> null;
        };
    }

    @Override
    public void on(String event, Object arg) {
        rules.on(event, arg);
    }

    @Override
    public void hud(Player p, Map<String, Object> out) {
        if (current == null) return;
        out.put("route.card", current.phrase());
        out.put("route.risk", current.risk());
        // 스테이지 표시(트럭 그림 + 남은 스테이지 수: trip.left 는 Trip 이 넣음), 도착한 스테이지 이름
        out.put("st.show", Boolean.TRUE);
        if (arriveName != null && game.now() - arriveAt < NAME_TICKS) out.put("st.name", arriveName);
        vote.hud(p, out, game.stateAt + closeAt);
    }

    @Override
    public String status() {
        if (current == null) return "route=-";
        return "route=" + stage + "/15 card=" + (current.card == null ? (current.gate == null ? "-" : current.gate.key) : current.card.key) + " spec=" + roundSpec
            + " " + vote.status() + " rule=" + rules.status() + " scen=" + scenery.displays() + " rdisp=" + (vote.displays() + scenery.displays());
    }

    // ------------------------------------------------------------------ 시험 훅

    /**
     * '길 x 보기'(지도), '길 x 상태', '길 x 굴리기 [판 수]'(지도를 여러 번 굴려 규칙과 종류 몫을 셈), '길 x 특수모의 [판 수]'(지도를 굴려 지나가며 열릴 특수를 셈: 막은 특수 0 확인),
     * '길 x 다음 &lt;종류&gt; [갈래 1..3]'(다음 갈림길의 갈래를 그 종류로), '길 x 열기'(지금 갈림길을 연다),
     * '길 x 닫기'(표 받기를 끝냄), '길 x 이동 [틱] [막]'(정해진 갈래로 지금 떠남), '길 x 치움'(표지판), '길 x 소품 &lt;묶음|다시&gt;',
     * '길투표 &lt;이름&gt; &lt;1|2|3&gt;'(표), '길투표 &lt;이름&gt; 보기'(지금 시선으로: 겨눈 표지판에 표), '길투표 &lt;이름&gt; 클릭'(휘두름 사건을 낸다: 좌클릭과 같은 길).
     */
    @Override
    public String test(Player p, String[] a) {
        if (a[1].equals("길투표")) {
            if (p == null || a.length < 4) return "길투표 <이름> <1|2|3|직접 n|핫바|보기|클릭>";
            switch (a[3]) {
                case "보기" -> {
                    int i = vote.looked(p);
                    boolean ok = vote.help(p);
                    return "[길] 겨눔 " + (i + 1) + " 표 " + ok + " " + vote.status();
                }
                case "클릭" -> {
                    int before = vote.of(p);
                    Bukkit.getPluginManager().callEvent(new PlayerAnimationEvent(p, PlayerAnimationType.ARM_SWING));
                    return "[길] 클릭 겨눔 " + (vote.looked(p) + 1) + " 표 " + (before + 1) + "→" + (vote.of(p) + 1) + " " + vote.status();
                }
                case "핫바" -> {
                    return "[길] 핫바 " + p.getName() + " " + vote.hotbar(p);
                }
                case "직접" -> {
                    int i = a.length > 4 ? Integer.parseInt(a[4]) - 1 : 1;
                    boolean ok = vote.cast(p, i);
                    if (ok) vote.byTest++;
                    return "[길] 직접 표 " + (i + 1) + " " + ok + " " + vote.status();
                }
                default -> {
                    // 숫자키를 누른 것과 같은 길: 칸 바꿈 사건을 내고(RouteVote.onHeld 가 표를 넣고 취소, 총 쪽 처리도 같이 받음) 결과를 본다
                    int i = Integer.parseInt(a[3]) - 1;
                    int before = vote.of(p), held = p.getInventory().getHeldItemSlot();
                    org.bukkit.event.player.PlayerItemHeldEvent ev = new org.bukkit.event.player.PlayerItemHeldEvent(p, held, i);
                    Bukkit.getPluginManager().callEvent(ev);
                    // 실제 패킷 처리처럼: 취소되지 않았으면 칸이 바뀐다
                    if (!ev.isCancelled()) p.getInventory().setHeldItemSlot(i);
                    return "[길] 키 " + (i + 1) + " 취소=" + ev.isCancelled() + " 표 " + (before + 1) + "→" + (vote.of(p) + 1) + " " + vote.hotbar(p) + " " + vote.status();
                }
            }
        }
        if (!a[1].equals("길")) return null;
        String sub = a.length > 3 ? a[3] : "상태";
        switch (sub) {
            case "보기" -> {
                if (first == null) return "[길] 지도 없음(판 밖)";
                StringBuilder sb = new StringBuilder("[길] 씨앗 " + trip.seed + " 지금 " + current + " 길 " + pathText());
                for (int k = Math.max(1, stage); k <= 14 && k < stage + 4; k++)
                    sb.append(" [").append(k).append("] ").append(forks[k][0]).append(" | ").append(forks[k][1]).append(" | ").append(forks[k][2]);
                return sb.toString();
            }
            case "굴리기" -> {
                // 지도를 n 번 굴려 규칙을 센다: 종류 겹침, 위험 1 없음, 위험 2 이상 없음, 막에 험로 없음, 막별 종류 몫
                int n = a.length > 4 ? Integer.parseInt(a[4]) : 200, dup = 0, noSafe = 0, noRisk = 0, noRush = 0;
                Map<String, Integer>[] count = new Map[4];
                for (int act = 1; act <= 3; act++) count[act] = new java.util.TreeMap<>();
                Random seed = new Random(20261112);
                for (int i = 0; i < n; i++) {
                    Stage[][] f = new Stage[15][];
                    roll(new Random(seed.nextLong()), f, new HashMap<>());
                    boolean[] rush = new boolean[4];
                    for (int k = 1; k <= 14; k++) {
                        if (Stage.gateStage(k + 1)) continue;
                        int act = Stage.actOf(k + 1);
                        Set<String> types = new HashSet<>();
                        boolean safe = false, risk = false;
                        for (Stage st : f[k]) {
                            types.add(st.type);
                            count[act].merge(st.type, 1, Integer::sum);
                            if (data.safe(st.type)) safe = true;
                            else if (st.risk() >= 2) risk = true;
                            if (Stage.RUSH.equals(st.type)) rush[act] = true;
                        }
                        if (types.size() < 3) dup++;
                        if (!safe) noSafe++;
                        if (!risk) noRisk++;
                    }
                    for (int act = 1; act <= 3; act++) if (!rush[act]) noRush++;
                }
                return "[길] 굴리기 " + n + "판: 종류 겹친 갈림길 " + dup + ", 위험 1 없는 " + noSafe + ", 위험 2 이상 없는 " + noRisk + ", 험로 없는 막 " + noRush
                    + " | 1막 " + count[1] + " 2막 " + count[2] + " 3막 " + count[3];
            }
            case "특수모의" -> {
                // 지도 n 판을 굴리고 갈래를 무작위로 지나가며 라운드마다 열릴 특수를 센다(Rounds.specialFor 와 풀의 특수 후보). 막은 특수는 0 이어야 함
                int n = a.length > 4 ? Integer.parseInt(a[4]) : 300;
                Rounds rd = trip.part(Rounds.class);
                if (rd == null) return "[길] 라운드 부품 없음";
                Map<String, Integer> open = new java.util.TreeMap<>();
                int specRounds = 0, rawBlocked = 0, poolBlocked = 0, forcedHit = 0;
                int[] run = new int[4], runBlocked = new int[4];
                Random seed = new Random(20261112L);
                for (int i = 0; i < n; i++) {
                    Random r = new Random(seed.nextLong());
                    Stage[][] f = new Stage[15][];
                    for (Object[] row : simulate(r, f)) {
                        int act = (Integer) row[0];
                        String spec = (String) row[2];
                        if (spec.startsWith("pool:")) {
                            for (String k : rd.poolSpecials(spec.substring(5), act)) if (blocked.contains(k)) poolBlocked++;
                            continue;
                        }
                        if (!spec.startsWith("special")) continue;
                        specRounds++;
                        String raw = spec.contains(":") ? spec.substring(spec.indexOf(':') + 1) : "";
                        if (raw.contains("(")) raw = raw.substring(0, raw.indexOf('(')).trim();
                        if (blocked.contains(raw)) rawBlocked++;
                        if (spec.startsWith("special:act")) forcedHit++;
                        String k = rd.specialFor(spec, act, r);
                        open.merge(k == null ? "(hard)" : k, 1, Integer::sum);
                    }
                    for (int k = 1; k <= 14; k++) {
                        for (Stage st : f[k]) {
                            if (st.gate == null || !st.gate.first.startsWith("special")) continue;
                            run[st.act]++;
                            if (blocked.contains(st.special)) runBlocked[st.act]++;
                        }
                    }
                }
                int openBlocked = 0;
                for (Map.Entry<String, Integer> e : open.entrySet()) if (blocked.contains(e.getKey())) openBlocked += e.getValue();
                return "[길] 특수모의 " + n + "판 막음 " + blocked + ": 열린 막은 특수 " + openBlocked + ", 풀 글에 막은 키 " + rawBlocked + ", 풀 후보에 막은 키 " + poolBlocked
                    + ", 질주 갈래 1막 " + run[1] + "(막은 것 " + runBlocked[1] + ") 2막 " + run[2] + "(" + runBlocked[2] + ") 3막 " + run[3] + "(" + runBlocked[3] + ")"
                    + ", 특수 라운드 " + specRounds + "(막 특수 글 " + forcedHit + ") | 열린 것 " + open;
            }
            case "다음" -> {
                if (stage < 1 || stage > 14 || a.length < 5) return "길 x 다음 <종류> [갈래 1..3] (스테이지 1..14 에서)";
                int i = a.length > 5 ? Integer.parseInt(a[5]) - 1 : 1;
                if (i < 0 || i > 2) return "갈래는 1..3";
                if (Stage.gateStage(stage + 1)) return "[길] 다음은 보스 길목(준비 셋)";
                forks[stage][i] = make(stage + 1, a[4], new Random(), null);
                return "[길] 갈림길 " + stage + " 갈래 " + (i + 1) + " = " + forks[stage][i];
            }
            case "열기" -> {
                if (stage < 1 || stage > 14) return "[길] 열 갈림길 없음 stage=" + stage;
                openFork();
                return "[길] 열림 " + forks[stage][0] + " | " + forks[stage][1] + " | " + forks[stage][2] + " disp=" + vote.displays();
            }
            case "닫기" -> {
                vote.close();
                return "[길] 닫힘 " + vote.status();
            }
            case "이동" -> {
                if (stage < 1 || stage > 15) return "[길] 떠날 수 없음 stage=" + stage;
                int ticks = a.length > 4 ? Integer.parseInt(a[4]) : data.travelNormal;
                departed = true;
                depart(ticks, a.length > 5 && a[5].equals("막"));
                return "[길] 떠남 → " + next;
            }
            case "치움" -> {
                vote.clear();
                return "[길] 표지판 치움";
            }
            case "소품" -> {
                if (current == null) return "[길] 판 밖";
                if (a.length > 4 && !a[4].equals("다시")) {
                    Stage st = new Stage(data, stage, current.type);
                    StageData.Card c = null;
                    for (StageData.Card x : data.cards) if (x.props.equals(a[4])) c = x;
                    if (c == null) return "[길] 그 묶음을 쓰는 카드 없음: " + a[4];
                    st.card = c;
                    return "[길] 소품 " + a[4] + " " + scenery.set(st, new Random(7)) + "개";
                }
                return "[길] 소품 " + current.props() + " " + scenery.set(current, trip.rng("scenery" + stage)) + "개";
            }
            default -> {
                return "[길] " + status() + " 길=" + pathText() + " 준것=" + rules.given() + " opens=" + opens + " travels=" + travels + " patches=" + patches
                    + " click=" + vote.byClick + " key=" + vote.byKey + " test=" + vote.byTest + " hooked=" + vote.hooked + " plan=" + planRound
                    + (planRound >= 0 ? "(open " + openAt + " close " + closeAt + " go " + travelAt + " len " + travelLen + ")" : "");
            }
        }
    }
}
