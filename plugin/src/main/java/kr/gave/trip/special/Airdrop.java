package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Crates;
import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.world.Plaza;

/**
 * 8 보급 낙하: 보급 상자 여섯이 DROP_EVERY 틱 간격으로 떨어진다(진짜 상자 Crates.drop). 둘은 꾸러미 게이브(G44: 진짜와 똑같이 떨어져 연 사람을 문다).
 * 진짜 상자는 지금처럼 좌클릭으로 열고(모두 탄약 가득, 연 사람 소모품), 총으로 맞히면 부서진다. 떼는 내려앉은 상자 쪽으로 쏠린다.
 * 진짜 넷을 다 열면 모두 무기 상자 한 번 공짜. 끝: 진짜 넷이 열리거나 다 사라짐(그 뒤 남은 떼가 물러남).
 */
public final class Airdrop extends Special {

    private final List<Crates.Crate> mine = new ArrayList<>();
    private int dropped, fakes, openedBase, real;
    private long nextDrop;

    public Airdrop(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_collect";
    }

    @Override
    protected boolean begin() {
        ownEnd = true;
        mine.clear();
        dropped = fakes = 0;
        real = Tune.DROP_N - Tune.DROP_FAKE.length;
        openedBase = game.crates.opened;
        nextDrop = openAt + 60;
        rounds.goals().count(0, real);
        streaming = true;
        return true;
    }

    private boolean fakeAt(int i) {
        for (int f : Tune.DROP_FAKE) if (f == i) return true;
        return false;
    }

    private int openedNow() {
        return Math.max(0, game.crates.opened - openedBase);
    }

    @Override
    protected void step(long now) {
        if (dropped < Tune.DROP_N && now >= nextDrop) {
            nextDrop = now + Tune.DROP_EVERY;
            double a = rnd.nextDouble() * Math.PI * 2, r = Tune.DROP_R0 + rnd.nextDouble() * (Tune.DROP_R1 - Tune.DROP_R0);
            Vector at = ring(a, r);
            if (fakeAt(dropped) && Species.G44.implemented()) {
                Gabe g = spawn(Species.G44, at, false);
                if (g != null) fakes++;
                else mine.add(game.crates.drop(at));
            } else {
                Crates.Crate c = game.crates.drop(at);
                if (c != null) mine.add(c);
            }
            dropped++;
        }
        // 떼는 내려앉은 상자 쪽으로 쏠린다(잠깐씩 끌었다 놓음: 그 사이 제 행동)
        if (now % 60 == 0) for (Crates.Crate c : mine) {
            if (c.gone()) continue;
            game.mobs.lure(c.pos.clone().setY(Plaza.FLOOR_Y), Tune.DROP_LURE_R, 24);
        }
        int op = openedNow();
        rounds.goals().count(Math.min(op, real), real);
        boolean anyLive = false;
        for (Crates.Crate c : mine) if (!c.gone() && game.crates.live().contains(c)) anyLive = true;
        if (op >= real) finish(true);
        else if (dropped >= Tune.DROP_N && !anyLive && now >= nextDrop - Tune.DROP_EVERY + 60) finish(false);
    }

    @Override
    protected void reward(int n) {
        for (Player p : game.players.inPlay()) {
            Life l = game.players.get(p).life;
            if (l == Life.ALIVE || l == Life.DOWNED) game.shop.giveFreeBox(p, 1);
        }
        say("sa_drop_all", 60);
    }

    @Override
    protected void end() {
        mine.clear();
    }

    @Override
    protected void hudMore(Player p, Map<String, Object> out) {
        out.put("sp.crates", openedNow());
    }

    @Override
    protected String detail() {
        int live = 0;
        for (Crates.Crate c : mine) if (!c.gone() && game.crates.live().contains(c)) live++;
        return String.format(Locale.ROOT, "drops=%d/%d fakes=%d live=%d opened=%d/%d", dropped, Tune.DROP_N, fakes, live, openedNow(), real);
    }

    @Override
    protected String testStep(Player p, String what, String[] a) {
        switch (what) {
            case "떨굼" -> {
                nextDrop = game.now();
                return "다음 상자 지금";
            }
            case "열기" -> {
                // 시험: 남은 진짜 상자 하나를 그 사람이 연 것처럼(앞에 세우고 tryOpen)
                if (p == null) return "사람 없음";
                for (Crates.Crate c : mine) {
                    if (c.gone() || !game.crates.live().contains(c)) continue;
                    Vector at = c.center();
                    org.bukkit.Location l = new org.bukkit.Location(game.world, at.getX(), Plaza.FLOOR_Y + 0.05, at.getZ() + 1.2);
                    l.setDirection(at.clone().subtract(l.toVector().add(new Vector(0, 1.6, 0))));
                    p.teleport(l);
                    return "열기=" + game.crates.tryOpen(p);
                }
                return "열 상자 없음";
            }
            default -> {
                return null;
            }
        }
    }
}
