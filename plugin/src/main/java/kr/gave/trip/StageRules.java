package kr.gave.trip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.game.Shop;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * 스테이지 종류별 규칙과 보상(원정.md 3.1절, 숫자는 stages.json effects: tools/trip/route.py EFFECTS 한곳).
 *
 * <p>때는 넷: 도착(arrive), 그 스테이지의 라운드를 열 때(round), 둘째 라운드가 끝날 때(end), 그리고 스테이지 동안의 배율(mul: Trip.mul 로 나감).
 * 종류의 것과 사건의 것을 함께 건다. 탄약, 돈, 증강 카드, 보급 상자, 게이브 놓기는 여기서 바로 하고, 트럭 수리와 트럭 부품은 Truck 을 부른다.
 * 공짜 무기 상자는 상점에 그 표가 생기기 전까지 상자 값만큼 돈으로 준다. 준 것은 사건 {@link #EV_REWARD} 로도 알린다.
 */
public final class StageRules {

    /** 부품 사이 사건: 스테이지 보상(arg = "truck:0.300" 트럭 수리 비율, "part:1" 트럭 부품 고를 차례 수, "box:1" 공짜 무기 상자). */
    public static final String EV_REWARD = "routeReward";

    private final Game game;
    private final Trip trip;
    private final StageData data;
    /** 지금 스테이지에 걸린 것(종류, 사건). */
    private final List<StageData.Effect> active = new ArrayList<>();
    private Stage stage;
    /** 이번 스테이지에 준 것(기록, 시험). */
    private final Map<String, Double> given = new LinkedHashMap<>();
    /** 라운드 안에 떨굴 보급 상자(판 틱, 없으면 -1). */
    private long crateAt = -1;
    /** 보스 길목 '질주'를 끝까지 달렸나(다른 부품이 사건 chaseDone 으로 알림). */
    private boolean ran;

    StageRules(Game game, Trip trip, StageData data) {
        this.game = game;
        this.trip = trip;
        this.data = data;
    }

    void reset() {
        active.clear();
        given.clear();
        stage = null;
        crateAt = -1;
        ran = false;
    }

    /** 지금 스테이지를 바꾼다(효과 표만 다시 건다. 도착 보상은 arrive 가 따로). */
    void enter(Stage st) {
        active.clear();
        given.clear();
        crateAt = -1;
        ran = false;
        stage = st;
        if (st == null) return;
        StageData.Effect e = data.effect(st.effectKey());
        if (e != null) active.add(e);
        if (st.event != null && Stage.EVENT.equals(st.kind())) {
            StageData.Event ev = data.event(st.event);
            if (ev != null) active.add(ev.effect);
        }
    }

    /** 도착(이동이 끝나 새 땅이 드러날 때): 탄약, 트럭 수리, 공짜 상자, 사건의 도착 연출. quiet 면 알림 없이(라운드를 건너뛴 시험). */
    void arrive(Stage st, boolean quiet) {
        if (st == null) return;
        Random r = trip.rng("arrive" + st.index);
        for (StageData.Effect e : active) {
            Map<String, Double> a = e.arrive;
            if (a.getOrDefault("ammo", 0.0) > 0) {
                int n = 0;
                for (Player p : game.players.inPlay()) {
                    if (game.players.get(p).life != Life.ALIVE) continue;
                    game.guns.refillAll(p);
                    n++;
                }
                note("ammo", n);
            }
            if (a.containsKey("truck")) reward("truck", a.get("truck"));
            if (a.containsKey("box")) {
                // 공짜 무기 상자 표(상점이 상자 값을 0 으로 받음)
                int nb = (int) Math.max(1, Math.round(a.get("box")));
                for (Player p : game.players.inPlay()) {
                    if (game.players.get(p).life != Life.ALIVE) continue;
                    game.shop.giveFreeBox(p, nb);
                }
                reward("box", a.get("box"));
            }
            if (a.containsKey("hitch")) hitch(e, a, r);
            else if (a.containsKey("money")) money(a.get("money"));
        }
        if (!quiet) trip.log("도착 " + st + " 준 것 " + given);
    }

    /** 손 든 사람(사건 ev_hitch): 몫만큼은 진짜 짐(모두 돈), 아니면 사람 탈 게이브가 트럭 곁에서 나온다. */
    private void hitch(StageData.Effect e, Map<String, Double> a, Random r) {
        if (r.nextDouble() < a.get("hitch")) {
            money(a.getOrDefault("money", 0.5));
            return;
        }
        Species sp = Species.of(e.text.getOrDefault("arrive.mob", "G50"));
        int n = (int) Math.round(a.getOrDefault("mobs", 2.0)), made = 0;
        if (sp != null && sp.implemented()) {
            for (int i = 0; i < n; i++) {
                double ang = r.nextDouble() * Math.PI * 2, rad = 9 + r.nextDouble() * 3;
                Vector at = new Vector(Plaza.CX + 0.5 + Math.cos(ang) * rad, 0, Plaza.CZ + 0.5 + Math.sin(ang) * rad);
                at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + sp.band.hover);
                Gabe g = game.mobs.spawn(sp, at, false);
                if (g != null) made++;
            }
        }
        note("hitchMobs", made);
    }

    /** 그 스테이지의 라운드가 열린 뒤: 보급 상자 예약. */
    void beginRound(int n) {
        crateAt = -1;
        for (StageData.Effect e : active) {
            Double at = e.round.get("crate");
            if (at != null) crateAt = game.now() + Math.max(1, at.longValue());
        }
    }

    void tick() {
        if (crateAt >= 0 && game.state == Game.State.WAVE && game.now() >= crateAt) {
            crateAt = -1;
            game.crates.drop(null);
            note("crate", 1);
        }
    }

    /** 스테이지의 둘째 라운드가 끝났다(쉬는 시간이 열린 뒤): 돈, 증강 카드, 트럭 부품. */
    void end(Stage st) {
        if (st == null) return;
        for (StageData.Effect e : active) {
            Map<String, Double> a = e.end;
            if (a.containsKey("money")) money(a.get("money"));
            if (a.containsKey("aug")) {
                int n = (int) Math.round(a.get("aug")), who = 0;
                for (Player p : game.players.inPlay()) {
                    PState s = game.players.get(p);
                    if (s.life == Life.DEAD || s.life == Life.TRAPPED || s.life == Life.WAITING) continue;
                    game.augments.offer(p, n);
                    who++;
                }
                note("aug", n * who);
            }
            if (a.containsKey("part") && (!"gate_run".equals(st.effectKey()) || ran)) reward("part", a.get("part"));
        }
        trip.log("스테이지 끝 " + st + " 준 것 " + given);
    }

    /** 모두에게 x U(서 있거나 쓰러진 사람). */
    private void money(double u) {
        // 2026-11-14: 설정 roundMoneyMul(2)과 moneyMul(1.7)은 표 값(route.json effects 의 money)에 녹였다
        int each = (int) Math.round(u * trip.unit() / 5.0) * 5, who = 0;
        if (each <= 0) return;
        for (Player p : game.players.inPlay()) {
            PState s = game.players.get(p);
            if (s.life != Life.ALIVE && s.life != Life.DOWNED) continue;
            game.players.addMoney(p, each);
            who++;
        }
        note("money", each * who);
    }

    /**
     * 다른 부품의 일인 보상. 트럭 수리(truck: 최대 체력 비율)와 트럭 부품(part: 고를 차례 수)은 Truck 이 있으면 여기서 바로 부르고,
     * 사건 {@link #EV_REWARD} 로도 알린다(알림일 뿐이다: truck, part 를 받아 다시 처리하지 말 것. box 는 상점이 공짜 상자 표를 만들 때 쓴다).
     */
    private void reward(String what, double v) {
        Truck truck = trip.part(Truck.class);
        if (truck != null && what.equals("truck")) note("truckHp", truck.repairFrac(v));
        if (truck != null && what.equals("part")) for (int i = 0; i < (int) Math.round(v); i++) truck.grantPart();
        note(what, v);
        trip.fire(EV_REWARD, what + ":" + String.format(Locale.ROOT, "%.3f", v));
    }

    private void note(String k, double v) {
        given.merge(k, v, Double::sum);
    }

    /** 다른 부품의 사건(보스 길목 '질주'를 끝까지 달림). */
    void on(String event, Object arg) {
        if ("chaseDone".equals(event)) ran = true;
    }

    /** 스테이지 동안의 배율(모르는 키는 1). 키 bossHp 는 질주를 끝까지 달렸을 때만. */
    double mul(String key) {
        double m = 1;
        for (StageData.Effect e : active) {
            Double v = e.mul.get(key);
            if (v != null) m *= v;
            if (ran && key.equals("bossHp")) {
                Double b = e.boss.get("hp");
                if (b != null) m *= b;
            }
        }
        return m;
    }

    /** 이번 스테이지에 준 것(시험, 상태). */
    Map<String, Double> given() {
        return given;
    }

    String status() {
        StringBuilder sb = new StringBuilder();
        for (String k : new String[] {"price", "revive", "truckShare", "reward", "money"}) {
            double m = mul(k);
            if (m != 1) sb.append(k).append('x').append(String.format(Locale.ROOT, "%.2f", m)).append(',');
        }
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb.length() == 0 ? "-" : sb.toString();
    }
}
