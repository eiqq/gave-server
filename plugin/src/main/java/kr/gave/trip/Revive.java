package kr.gave.trip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.game.Shop;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 원정의 죽음과 꺼내기(docs/모듈/원정.md 7절). 라운드 중에 죽은 사람은 서 있는 사람이 트럭 상점의 꺼내기 줄(죽은 동료 머리, 먼저 죽은 차례)에서
 * 값을 내면 트럭 짐칸 뒤에 내린다. 한 번 누름 = 값의 1 / REVIVE_PARTS(모금: 여럿이 나눠 내도, 한 사람이 여러 번 눌러도 된다).
 * 라운드가 끝나 쉬는 시간이 되면 죽은 사람은 모두 공짜로 돌아온다(2026-11-12 사용자, Players.reviveFree): 그때 모금에 낸 돈은 {@link #freed} 가 돌려준다.
 * 엔딩 뒤 발사 차례 쉬는 시간에는 공짜 부활이 없어 꺼내기 줄이 그대로 쓰인다.
 *
 * <p>값(원, 5 단위) = REVIVE_U x U x (1 + REVIVE_STEP x 그 사람이 이미 꺼내진 횟수)(REVIVE_MAX x U 까지) x 막 배율 x 깎임. U = 그 라운드 무기 상자 바탕값.
 * 깎임(곱함, 바닥 REVIVE_FLOOR): 부품과 스테이지의 trip.mul("revive")(인양기, 정비), 그 사람 얼굴을 한 게이브를 잡음 REVIVE_FACE, 보스를 잡은 쉬는 시간 REVIVE_BOSS,
 * 갇힌 채 라운드가 끝나 죽은 것으로 친 사람 REVIVE_TRAPPED, 죽은 뒤 라운드가 하나 지날 때마다 REVIVE_DECAY(REVIVE_DECAY_MIN 까지).
 * 숫자는 {@link Trip.Tune}.
 *
 * <p>죽은 동안(사람 쪽 규칙은 Players): 카메라는 자기 얼굴을 한 게이브(죽음 결과 개체)에 붙어 따라다닌다. 그 개체가 웨이브 끝 정리로 치워지면 다음 라운드에
 * 고리에서 다시 선다(따라온다). 동료가 잡으면 다시 서지 않고 값이 싸진다. 좌클릭은 '살려 달라' 신호(REVIVE_CALL_EVERY 틱에 한 번).
 * 설정 trip.bossFreeRevive 가 켜져 있으면 보스를 잡은 쉬는 시간에 죽은 사람이 모두 값 없이 돌아온다(기본은 반값).
 */
public final class Revive implements TripPart {

    private static final TextColor DIM = TextColor.color(0x9A9A9A), OK = TextColor.color(0x7CF07C), NO = TextColor.color(0xFF7A7A), GOLD = TextColor.color(0xFFC53D);

    public final Game game;
    public final Trip trip;
    private final Map<UUID, Pool> pools = new HashMap<>();
    private boolean ruleShown;
    private int revivedCount, deathCount;
    private String lastLog = "없음";

    public Revive(Game game, Trip trip) {
        this.game = game;
        this.trip = trip;
    }

    // ------------------------------------------------------------------ 값

    /** 죽은 채인 사람(접속 중, 먼저 죽은 차례). */
    public List<Player> dead() {
        List<Player> out = new ArrayList<>(game.players.withLife(Life.DEAD));
        out.sort(Comparator.comparingLong(p -> game.players.get(p).lifeAt));
        return out;
    }

    /** 이 죽은 사람을 꺼내는 지금 값(원, 5 단위). */
    public int price(PState s) {
        double u = trip.unit();
        double base = Math.min(Trip.Tune.REVIVE_MAX, Trip.Tune.REVIVE_U * (1 + Trip.Tune.REVIVE_STEP * Math.max(0, s.revived))) * u;
        double[] act = Trip.Tune.REVIVE_ACT;
        base *= act[Math.max(0, Math.min(act.length - 1, trip.act() - 1))];
        double cut = discount(s);
        // 증강(죽은 사람의 인식표, 편도): 모금 목표 자체가 바뀐다
        base *= game.augments.revivePriceMul(Bukkit.getPlayer(s.id), null);
        return Math.max(5, (int) Math.round(base * cut / 5.0) * 5);
    }

    /** 낸 사람 쪽 증강(몸값)으로 한 번 누름에 실제로 내는 돈(모금에는 step 이 다 들어간다). */
    public int cost(Player payer, int step) {
        if (step <= 0) return step;
        double k = game.augments.revivePriceMul(null, payer) / Math.max(1e-6, game.augments.revivePriceMul(null, null));
        if (Math.abs(k - 1) < 1e-6) return step;
        return Math.max(5, (int) Math.round(step * k / 5.0) * 5);
    }

    /** 깎임 배율(REVIVE_FLOOR..1). */
    public double discount(PState s) {
        double cut = trip.mul("revive");
        if (s.faceKilled) cut *= Trip.Tune.REVIVE_FACE;
        if (s.trappedDeath) cut *= Trip.Tune.REVIVE_TRAPPED;
        if (bossBreak()) cut *= Trip.Tune.REVIVE_BOSS;
        int rounds = Math.max(0, game.wave - s.deadRound);
        cut *= Math.max(Trip.Tune.REVIVE_DECAY_MIN, Math.pow(Trip.Tune.REVIVE_DECAY, rounds));
        return Math.max(Trip.Tune.REVIVE_FLOOR, Math.min(1.0, cut));
    }

    /** 보스를 잡은 쉬는 시간인가(그동안 꺼내는 값 반값). */
    public boolean bossBreak() {
        return game.state == Game.State.BREAK && Trip.bossRound(game.wave);
    }

    /** 이 죽은 사람에게 모인 돈과 모금(없으면 만들어서). */
    public Pool pool(UUID dead) {
        return pools.computeIfAbsent(dead, k -> new Pool());
    }

    /** 한 번 누를 때 낼 돈(원). */
    public int step(PState s) {
        return pool(s.id).step(price(s), (int) Trip.Tune.REVIVE_PARTS);
    }

    // ------------------------------------------------------------------ 누름, 꺼냄

    /** 낼 수 있나: 서 있는 사람만(죽은 본인, 쓰러진 사람은 못 냄), 트럭이 살아 있어야. */
    public boolean canPay(Player payer) {
        if (payer == null) return false;
        PState ps = game.players.get(payer);
        if (ps.life != Life.ALIVE || !game.augments.canHelp(payer)) return false;
        Truck t = trip.truck();
        return t == null || t.alive();
    }

    /**
     * 모금 한 번 누름: payer 가 dead 를 꺼내는 값의 1 / REVIVE_PARTS 를 냄. 모금이 차면 dead 가 트럭에서 내린다. 돌려주는 값: 낸 돈(못 냈으면 0).
     */
    public int press(Player payer, Player dead) {
        if (!canPay(payer) || dead == null || dead == payer) return fail("낼 수 없음");
        PState s = game.players.get(dead);
        if (s.life != Life.DEAD) return fail("죽은 사람이 아님");
        PState ps = game.players.get(payer);
        Pool pool = pool(s.id);
        int price = price(s), step = pool.step(price, (int) Trip.Tune.REVIVE_PARTS);
        int cost = cost(payer, step);
        if (step > 0) {
            if (ps.money < cost) {
                Props.sound(payer, null, Sound.BLOCK_DISPENSER_FAIL, 0.6f, 1f, 1.2f);
                return fail("돈 모자람 " + ps.money + "/" + cost);
            }
            if (!game.players.spend(payer, cost)) game.players.addMoney(payer, -cost);
            pool.add(payer.getUniqueId(), step);
            ps.revivePaid += cost;
            game.augments.paid(payer, "revive", cost);
            s.revivePool = pool.total();
            Props.sound(payer, null, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1f, 0.9f + 0.5f * (float) pool.frac(price));
        }
        lastLog = payer.getName() + " → " + dead.getName() + " " + cost + "원(모금 " + step + ") " + pool.total() + "/" + price;
        if (pool.full(price)) bringBack(dead, payer);
        return cost;
    }

    private int fail(String why) {
        lastLog = "못 냄: " + why;
        return 0;
    }

    /** 꺼냄: 트럭 짐칸 뒤에 내림(값 없이 부를 수도 있다: 설정 bossFreeRevive, 시험). */
    public boolean bringBack(Player dead, Player payer) {
        if (!game.players.reviveAtTruck(dead, payer)) return false;
        pools.remove(dead.getUniqueId());
        revivedCount++;
        game.augments.revived(dead, payer);
        game.hud.notice("trip_revived", 60);
        Truck t = trip.truck();
        Location at = t != null ? Truck.rear(game.world, 0.5) : dead.getLocation();
        Props.soundAround(at, "trip.revived", Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f, 0.8f, 32, -1);
        trip.fire(Trip.EV_REVIVED, dead);
        trip.log("꺼냄 " + dead.getName() + " 낸 사람 " + (payer == null ? "-" : payer.getName()));
        for (Player o : game.players.inPlay()) game.shop.refresh(o);
        return true;
    }

    /**
     * 쉬는 시간 공짜 부활로 돌아옴(Players.reviveFree 가 부름): 그 사람 모금에 이미 낸 돈을 낸 사람마다 돌려준다(옮기기라 돈 배율 없음), 모금을 지운다.
     * 꺼낸 횟수, 낸 사람의 꺼냄 기록, 증강 '꺼내짐' 효과는 세지 않는다(돈 내고 꺼낸 것이 아님).
     */
    public void freed(Player p) {
        Pool pool = pools.remove(p.getUniqueId());
        if (pool == null || pool.total() <= 0) return;
        int back = 0;
        for (Map.Entry<UUID, Integer> e : pool.payers().entrySet()) {
            Player payer = Bukkit.getPlayer(e.getKey());
            if (payer == null || e.getValue() <= 0) continue;
            game.players.addMoneyRaw(payer, e.getValue());
            PState ps = game.players.get(payer);
            ps.revivePaid = Math.max(0, ps.revivePaid - e.getValue());
            back += e.getValue();
        }
        trip.log("쉬는 시간 부활 " + p.getName() + " 모금 돌려줌 " + back + "원");
    }

    /** 죽은 사람의 '살려 달라' 신호(좌클릭): 트럭에서 작은 소리, HUD 값 revive.calling 에 3초. REVIVE_CALL_EVERY 틱에 한 번. */
    public boolean call(Player p) {
        PState s = game.players.get(p);
        long now = game.now();
        if (s.life != Life.DEAD || now - s.callAt < (long) Trip.Tune.REVIVE_CALL_EVERY) return false;
        s.callAt = now;
        Location at = Truck.live() ? Truck.center().toLocation(game.world).add(0, 1.5, 0) : Plaza.center(game.world);
        Props.soundAround(at, "trip.call", Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1f, 0.7f, 24, -1);
        Props.sound(p, null, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1f, 0.7f);
        return true;
    }

    // ------------------------------------------------------------------ 상점 줄

    private static Component text(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** 상점 화면의 꺼내기 줄(칸 row..row + 8): 죽은 동료 머리, 이름, 모인 돈과 값, 한 번 누를 돈. 누르면 {@link #press}. Shop.render 가 부른다. */
    public void render(Player viewer, Shop.View v, int row) {
        if (!trip.enabled) return;
        List<Player> dead = dead();
        int money = game.players.get(viewer).money;
        for (int i = 0; i < Math.min(9, dead.size()); i++) {
            Player d = dead.get(i);
            PState s = game.players.get(d);
            int price = price(s), have = pool(s.id).total(), step = step(s), parts = (int) Trip.Tune.REVIVE_PARTS;
            int filled = Math.min(parts, (int) Math.floor(have * parts / (double) Math.max(1, price) + 1e-9));
            ItemStack head = ItemStack.of(Material.PLAYER_HEAD, Math.max(1, Math.min(64, parts - filled)));
            try {
                head.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(d.getPlayerProfile()));
            } catch (RuntimeException e) {
                // 프로필을 못 실으면 기본 머리
            }
            head.setData(DataComponentTypes.ITEM_NAME, text(d.getName(), GOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(text("■".repeat(filled), OK).append(text("□".repeat(parts - filled), DIM)).append(text(" " + have + "/" + price + "원", DIM)));
            int cost = cost(viewer, step);
            lore.add(text(cost + "원", money >= cost ? OK : NO));
            head.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
            v.inv.setItem(row + i, head);
            v.actions.put(row + i, () -> {
                press(viewer, d);
                for (Player o : game.players.inPlay()) game.shop.refresh(o);
            });
        }
    }

    // ------------------------------------------------------------------ TripPart

    @Override
    public void startGame() {
        pools.clear();
        ruleShown = false;
        revivedCount = deathCount = 0;
    }

    @Override
    public void beginRound(int n) {
        // 죽은 사람의 얼굴을 한 게이브가 웨이브 끝 정리로 치워졌으면 고리에서 다시 선다(따라온다). 동료가 잡은 것(얼굴 깎임)은 다시 서지 않는다
        for (Player p : dead()) {
            PState s = game.players.get(p);
            if (s.faceKilled || s.trappedDeath || s.deathMob != null && !s.deathMob.dead && !s.deathMob.removed) continue;
            Vector at;
            try {
                at = game.director.ringPoint(Species.G38);
            } catch (RuntimeException e) {
                at = null;
            }
            if (at == null) {
                double a = game.random.nextDouble() * Math.PI * 2;
                at = new Vector(Plaza.CX + 0.5 + Math.cos(a) * 60, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * 60);
            }
            game.players.respawnDeathMob(p, new Location(game.world, at.getX(), Plaza.FLOOR_Y, at.getZ()));
        }
    }

    @Override
    public void beginBreak(int n) {
        // 설정 bossFreeRevive: 보스를 잡으면 죽은 사람도 모두 값 없이 돌아옴(기본 꺼짐: 그 쉬는 시간 반값)
        if (trip.bossFreeRevive && Trip.bossRound(n)) for (Player p : dead()) bringBack(p, null);
    }

    @Override
    public void tick() {
        if (game.state != Game.State.WAVE && game.state != Game.State.BREAK) return;
        // 값이 내려가(라운드 깎임, 보스 반값) 이미 모인 돈이 값에 닿았으면 꺼냄
        if (game.now() % 20 != 7 || pools.isEmpty()) return;
        Truck t = trip.truck();
        if (t != null && !t.alive()) return;
        for (Player d : dead()) {
            PState s = game.players.get(d);
            Pool pool = pools.get(s.id);
            if (pool == null || pool.total() <= 0 || !pool.full(price(s))) continue;
            UUID last = pool.last();
            bringBack(d, last == null ? null : Bukkit.getPlayer(last));
        }
    }

    @Override
    public void reset() {
        pools.clear();
        ruleShown = false;
    }

    @Override
    public void on(String event, Object arg) {
        if (Trip.EV_DEAD.equals(event) && arg instanceof Player p) {
            pools.remove(p.getUniqueId());
            deathCount++;
            // 판에서 처음 누가 죽을 때 모두에게 한 번, 죽은 사람에게는 늘
            if (!ruleShown) {
                ruleShown = true;
                game.hud.notice("trip_dead_rule", 100);
            }
            game.hud.notice(p, "trip_trapped", 100);
        }
    }

    @Override
    public String status() {
        return "dead=" + game.players.withLife(Life.DEAD).size() + " revived=" + revivedCount;
    }

    @Override
    public void hud(Player p, Map<String, Object> out) {
        List<Player> dead = dead();
        out.put("revive.dead", dead.size());
        if (dead.isEmpty()) return;
        long now = game.now();
        Set<UUID> ids = new HashSet<>(), calling = new HashSet<>();
        for (Player d : dead) {
            ids.add(d.getUniqueId());
            if (now - game.players.get(d).callAt < 60) calling.add(d.getUniqueId());
        }
        out.put("revive.ids", ids);
        out.put("revive.calling", calling);
        PState s = game.players.get(p);
        if (s.life == Life.DEAD) {
            int price = price(s);
            out.put("revive.me.price", price);
            out.put("revive.me.pool", pool(s.id).total());
            out.put("revive.me.frac", pool(s.id).frac(price));
        }
    }

    /**
     * 꺼내기 &lt;낸 사람&gt; &lt;죽은 사람&gt; [번](모금 누름), 꺼내기값 &lt;이름|x&gt;(죽은 사람마다 값, 깎임, 모인 돈), 살려달라 &lt;죽은 사람&gt;, 그냥꺼내기 &lt;죽은 사람&gt;(값 없이).
     */
    @Override
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        switch (a[1]) {
            case "꺼내기" -> {
                if (p == null || a.length < 4) return "꺼내기 <낸 사람> <죽은 사람> [번]";
                Player d = Bukkit.getPlayerExact(a[3]);
                if (d == null) return "없는 사람: " + a[3];
                int n = a.length > 4 ? Integer.parseInt(a[4]) : 1, paid = 0;
                for (int i = 0; i < n; i++) paid += press(p, d);
                PState s = game.players.get(d);
                return "[꺼내기] paid=" + paid + " life=" + s.life + " pool=" + s.revivePool + " money=" + game.players.get(p).money + " | " + lastLog;
            }
            case "꺼내기값" -> {
                StringBuilder sb = new StringBuilder("[꺼내기] u=" + trip.unit() + " act=" + trip.act() + " bossBreak=" + bossBreak());
                for (Player d : dead()) {
                    PState s = game.players.get(d);
                    sb.append(String.format(Locale.ROOT, " %s price=%d cut=%.2f pool=%d step=%d deaths=%d revived=%d face=%s mob=%s", d.getName(), price(s), discount(s),
                        pool(s.id).total(), step(s), s.deaths, s.revived, s.faceKilled, s.deathMob == null ? "-" : s.deathMob.dead ? "dead" : s.deathMob.sp.name()));
                }
                return sb.toString();
            }
            case "살려달라" -> {
                return p == null ? "없음" : "[꺼내기] call=" + call(p);
            }
            case "그냥꺼내기" -> {
                return p == null ? "없음" : "[꺼내기] back=" + bringBack(p, null);
            }
            default -> {
                return null;
            }
        }
    }
}
