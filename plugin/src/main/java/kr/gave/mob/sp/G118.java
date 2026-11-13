package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G118 빚 놓는 게이브(보상, 정예). 통통 튀며 트럭 둘레(R 18)를 돌아 금화를 흘리고, 주운 사람에게 닢 수만큼 박치기로 빚을 받아 낸다.
 *
 * <p>도감: 금화(바닥 모델, 12닢까지)를 사람이 밟으면 그 사람에게 5원, 장부에 한 닢. 25초 흘린 뒤 멈춰 고개를 끄덕여 세고, 비녀가 '탁' 빠지면
 * 가장 많이 주운 사람부터 찾아가 닢마다 7 피해 박치기를 연달아(다 받을 때까지 그 사람만). 아무도 안 주웠으면 가장 가까운 사람에게 14 한 번.
 * 다 받으면 다시 흘린다. 잡으면 주운 돈은 그대로 내 것(남은 금화는 사라짐). 라운드가 끝날 때까지 살아 있으면(웨이브 끝 정리, 멈춤 정리) 장부의
 * 두 배를 걷어 간다(가진 돈까지). 사람에게는 6블록 안으로 다가오지 않고 돈다.
 *
 * <p>바꾼 것: 동료 머리 위 닢 수 표시 대신 주운 사람 화면에 이 종 표시(Hud.mark)를 띄운다(머리 칸 모델은 빚마다 그릴 수 없어서).
 * 금화는 종 전용 모델(g118_coin)을 연출 디스플레이에 실음(효과 예산 안, 못 낳으면 그 닢은 건너뜀).
 *
 * <p>팩 계약(tools/gabe/sp/g118.py): 모델 gave:gabe/g118 상태 "", 부위 unpin, gave:gabe/g118_coin, 소리 coins(24), pin(16), ram(16), 글귀 cause_g118.
 */
public final class G118 extends Gabe {

    static final double SPEED = 0.25, RING = 18, KEEP = 6, PICK = 1.1;
    static final int COIN_WON = 5, COIN_CD = 40, MAX_COINS = 12, COIN_LIFE = 900;
    static final int SCATTER = 500, COUNT = 40, UNPIN = 12, RAM_CD = 8;
    static final double RAM = 7, RAM_NONE = 14;

    enum Phase { SCATTER, COUNT, COLLECT }

    private static final class Coin {
        final Vector at;
        final ItemDisplay d;
        final long until;

        Coin(Vector at, ItemDisplay d, long until) {
            this.at = at;
            this.d = d;
            this.until = until;
        }
    }

    private Phase phase = Phase.SCATTER;
    private final List<Coin> coins = new ArrayList<>();
    /** 장부: 사람마다 주운 닢 수(주운 차례). */
    private final Map<UUID, Integer> ledger = new LinkedHashMap<>();
    private int phaseAt, coinAt = 40, ramAt, picked, dir = 1;
    private Player debtor;
    private boolean anyPicked;

    public G118(Game game, Species sp) {
        super(game, sp);
        radius = 0.65;
        baseSpeed = SPEED;
        hover = 0.8;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g118", 1.6f, 1.8f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        dir = game.random.nextBoolean() ? 1 : -1;
    }

    private Vector center() {
        return Truck.live() ? Truck.center() : new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
    }

    @Override
    public void tick() {
        hover = 0.8 + Math.abs(Math.sin(age * 0.26)) * 0.32;
        pickups();
        switch (phase) {
            case SCATTER -> scatter();
            case COUNT -> count();
            case COLLECT -> collect();
        }
    }

    // ------------------------------------------------------------------ 흘리기

    private void scatter() {
        Vector c = center().setY(pos.getY());
        circle(c, RING, speed(), dir);
        Player near = game.mobs.nearestPlayer(pos, KEEP);
        if (near != null) {
            Vector away = pos.clone().subtract(near.getLocation().toVector()).setY(0);
            if (away.lengthSquared() > 1e-4) vel.add(away.normalize().multiply(speed() * 0.8));
        }
        if (Math.hypot(vel.getX(), vel.getZ()) > 1e-3) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        if (age >= coinAt) {
            coinAt = age + cd(COIN_CD);
            double r = Math.hypot(pos.getX() - c.getX(), pos.getZ() - c.getZ());
            if (r < RING + 10) drop();
        }
        if (age - phaseAt >= SCATTER) {
            phase = Phase.COUNT;
            phaseAt = age;
        }
    }

    /** 금화 한 닢을 발밑에. */
    private void drop() {
        prune();
        if (coins.size() >= MAX_COINS || !game.shotFx.room(ShotFx.NORMAL)) return;
        double x = pos.getX() + game.random.nextGaussian() * 0.3, z = pos.getZ() + game.random.nextGaussian() * 0.3;
        double y = game.mobs.flow.floor(x, z) + 0.24;
        Location at = new Location(game.world, x, y, z);
        float tilt = (float) Math.toRadians(70 + game.random.nextInt(20));
        Matrix4f m = new Matrix4f().rotateY(game.random.nextFloat() * 6.28f).rotateX(tilt).rotateY((float) Math.PI);
        ItemDisplay d = game.shotFx.spawn(at, "fx_quad", 0, 0, m, Display.Billboard.FIXED, false, COIN_LIFE, ShotFx.NORMAL);
        if (d == null) return;
        d.setItemStack(Body.item("gabe/g118_coin", "", new LookState(), game.worldTime()));
        d.setBrightness(new Display.Brightness(14, 14));
        coins.add(new Coin(new Vector(x, y, z), d, now() + COIN_LIFE - 5));
        sound("coins", Sound.BLOCK_CHAIN_PLACE, 1.6f, 24);
    }

    private void prune() {
        long now = now();
        coins.removeIf(c -> now >= c.until || !c.d.isValid());
    }

    /** 사람이 금화를 밟으면 5원, 장부에 한 닢. */
    private void pickups() {
        if (coins.isEmpty() || age % 2 != 0) return;
        for (Iterator<Coin> it = coins.iterator(); it.hasNext();) {
            Coin c = it.next();
            if (now() >= c.until || !c.d.isValid()) {
                it.remove();
                continue;
            }
            for (Player p : game.mobs.standing()) {
                Location l = p.getLocation();
                if (Math.hypot(l.getX() - c.at.getX(), l.getZ() - c.at.getZ()) > PICK || Math.abs(l.getY() - c.at.getY()) > 2) continue;
                c.d.remove();
                it.remove();
                game.players.addMoney(p, COIN_WON);
                ledger.merge(p.getUniqueId(), 1, Integer::sum);
                anyPicked = true;
                picked++;
                body.scale((float) Math.min(1.6, 1.0 + picked * 0.06), 4);
                game.hud.mark(p, sp.key(), 200);
                Location s = l.clone().add(0, 1, 0);
                game.shotFx.spawn(s, "fx_quad", ShotFx.SPARK, 0, ShotFx.scale(0.5f), Display.Billboard.CENTER, true, 8, ShotFx.EXTRA);
                sound("coins", Sound.BLOCK_CHAIN_PLACE, 1.8f, 24);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ 세기, 받기

    private void count() {
        hold();
        int t = age - phaseAt;
        pitch = (t / 6) % 2 == 0 ? 18 : -4;
        if (t == COUNT - UNPIN) {
            if (anyPicked) {
                body.look.on.add("unpin");
                sound("pin", Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 1.5f, 16);
            }
        }
        if (t < COUNT) return;
        pitch = 0;
        phase = Phase.COLLECT;
        phaseAt = age;
        debtor = null;
    }

    private void collect() {
        if (debtor == null || !standing(debtor) || ledger.getOrDefault(debtor.getUniqueId(), 0) <= 0) {
            if (debtor != null && !standing(debtor)) ledger.remove(debtor.getUniqueId());
            debtor = nextDebtor();
            if (debtor == null) {
                if (!anyPicked && phaseAt >= 0) {
                    // 아무도 안 주웠음: 가장 가까운 사람에게 한 번
                    Player p = nearest(400);
                    if (p != null) {
                        mark(p);
                        follow(speed() * 1.4);
                        face(chest(p));
                        if (touches(p, 0.3)) {
                            hit(p, RAM_NONE, "cause_g118");
                            knock(p, toward(p), 0.5, 0.2);
                            sound("ram", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 16);
                            restart();
                        }
                        return;
                    }
                }
                restart();
                return;
            }
            mark(debtor);
        }
        Player p = debtor;
        follow(speed() * 1.5);
        face(chest(p));
        pitch = 0;
        if (age >= ramAt && touches(p, 0.35)) {
            ramAt = age + cd(RAM_CD);
            hit(p, RAM, "cause_g118");
            knock(p, toward(p), 0.25, 0.12);
            body.squash(1.2f, 0.8f, 1.2f, 2);
            sound("ram", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 16);
            UUID id = p.getUniqueId();
            int left = ledger.getOrDefault(id, 0) - 1;
            if (left <= 0) ledger.remove(id);
            else ledger.put(id, left);
            picked = Math.max(0, picked - 1);
            body.scale((float) Math.min(1.6, 1.0 + picked * 0.06), 4);
        }
    }

    /** 장부에서 가장 많이 주운 서 있는 사람. */
    private Player nextDebtor() {
        Player best = null;
        int most = 0;
        for (Map.Entry<UUID, Integer> e : ledger.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !standing(p) || e.getValue() <= most) continue;
            most = e.getValue();
            best = p;
        }
        return best;
    }

    private void restart() {
        phase = Phase.SCATTER;
        phaseAt = age;
        debtor = null;
        anyPicked = !ledger.isEmpty();
        mark(null);
        body.look.on.remove("unpin");
        dir = -dir;
    }

    // ------------------------------------------------------------------ 죽음

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 라운드가 끝날 때까지 살아남음: 장부의 두 배를 흙으로(가진 돈까지)
        if (d != null && d.by() == null && d.type() == DamageType.OTHER && ("wave_end".equals(d.how()) || "stall".equals(d.how()))) {
            for (Map.Entry<UUID, Integer> e : ledger.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null) continue;
                int take = (int) Math.min(game.players.get(p).money, Math.round(e.getValue() * COIN_WON * 2 * game.players.moneyMul)); // 주운 돈(돈 배율 든 값)의 두 배
                if (take > 0) game.players.addMoney(p, -take);
            }
        }
        ledger.clear();
        for (Coin c : coins) if (c.d.isValid()) c.d.remove();
        coins.clear();
        super.die(d);
    }

    @Override
    public void remove() {
        for (Coin c : coins) if (c.d.isValid()) c.d.remove();
        coins.clear();
        super.remove();
    }

    @Override
    public String toString() {
        int owed = 0;
        for (int v : ledger.values()) owed += v;
        return super.toString() + " " + phase + " coins=" + coins.size() + " owed=" + owed + (debtor != null ? " debtor=" + debtor.getName() : "");
    }
}
