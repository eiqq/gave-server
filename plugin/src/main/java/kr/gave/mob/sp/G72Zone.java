package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Role;

/**
 * 남는 구역(G72 운석이 떨어진 자리의 불, G74 수레가 터진 자리의 분홍 가스). 만든 개체가 죽어도 정해진 틱 동안 남아야 해서 종 tick 밖에서 돈다
 * (구역이 있을 때만 1틱 작업 하나, 다 꺼지면 멈춤). 웨이브가 아니면 모두 꺼진다(사람에게 남는 것 없음: 피해는 그때그때, 느려짐, 흔들림 없음).
 *
 * <p>피해는 10틱마다 반 초 몫. 한 사람이 여러 구역에 겹쳐 서도 1초에 받는 합은 구역 종류의 상한까지(가스 8).
 */
final class G72Zone {

    static final int FIRE = 0, GAS = 1;

    private static final List<G72Zone> ZONES = new ArrayList<>();
    /** 사람마다 이번 초(판 틱 / 20)와 그 초에 받은 합: 종류마다. */
    private static final Map<UUID, double[]> TAKEN = new HashMap<>();
    private static BukkitTask task;
    private static Game owner;

    final Game game;
    final int kind;
    final Vector at;
    final double r, dps, mobDps, capPerSec;
    final int life;
    final Gabe src;
    final Player by;
    final String cause;
    int age;
    ItemDisplay cloud;

    private G72Zone(Game game, int kind, Vector at, double r, int life, double dps, double mobDps, double capPerSec, Gabe src, Player by,
            String cause) {
        this.game = game;
        this.kind = kind;
        this.at = at;
        this.r = r;
        this.life = life;
        this.dps = dps;
        this.mobDps = mobDps;
        this.capPerSec = capPerSec;
        this.src = src;
        this.by = by;
        this.cause = cause;
    }

    /** 불 구역(운석 자리): 반경 r, life 틱, 사람 초당 dps. */
    static void fire(Game game, Vector at, double r, int life, double dps, Gabe src, String cause) {
        add(new G72Zone(game, FIRE, at.clone(), r, life, dps, 0, dps * 2, src, null, cause));
    }

    /** 분홍 가스(수레 자리): 사람 초당 dps(겹쳐도 cap 까지), 다른 게이브 초당 mobDps(보스 빼고, by 몫), 기침으로 조준 흔들림. */
    static void gas(Game game, Vector at, double r, int life, double dps, double cap, double mobDps, Gabe src, Player by, String cause) {
        G72Zone z = new G72Zone(game, GAS, at.clone(), r, life, dps, mobDps, cap, src, by, cause);
        add(z);
        // 가스 구름: 점묘 분홍 구름 판 하나(늘 사람을 봄), 자라며 오르다 사라짐
        Location l = new Location(game.world, at.getX(), at.getY() + 0.9, at.getZ());
        z.cloud = game.shotFx.spawn(l, "fx_quad", 0, 0, ShotFx.scale((float) (r * 1.4)), Display.Billboard.CENTER, false, life + 2, ShotFx.NORMAL);
        if (z.cloud != null) {
            z.cloud.setItemStack(Body.item("gabe/g74_gas", "", new LookState(), game.worldTime()));
            z.cloud.setBrightness(new Display.Brightness(12, 12));
            game.shotFx.grow(z.cloud, ShotFx.scale((float) (r * 2.3)), Math.min(59, life));
            game.shotFx.drift(z.cloud, l.clone().add(0, 0.5, 0), Math.min(59, life));
        }
    }

    private static void add(G72Zone z) {
        if (owner != z.game) {
            clear();
            owner = z.game;
        }
        ZONES.add(z);
        if (task == null || task.isCancelled()) task = z.game.plugin.getServer().getScheduler().runTaskTimer(z.game.plugin, G72Zone::tickAll, 1, 1);
    }

    /** 모두 끔(판 끝, 웨이브 밖). */
    static void clear() {
        for (G72Zone z : ZONES) z.drop();
        ZONES.clear();
        TAKEN.clear();
        if (task != null) task.cancel();
        task = null;
    }

    static int count() {
        return ZONES.size();
    }

    private static void tickAll() {
        Game game = owner;
        if (game == null || game.state != Game.State.WAVE) {
            clear();
            return;
        }
        for (Iterator<G72Zone> it = ZONES.iterator(); it.hasNext();) {
            G72Zone z = it.next();
            z.age++;
            if (z.age > z.life) {
                z.drop();
                it.remove();
                continue;
            }
            z.tick();
        }
        if (ZONES.isEmpty()) {
            TAKEN.clear();
            if (task != null) task.cancel();
            task = null;
        }
    }

    private void drop() {
        if (cloud != null && cloud.isValid()) cloud.remove();
        cloud = null;
    }

    private void tick() {
        // 겉: 불은 불꽃 덩이, 가스는 분홍 안개 방울(구름 판 위로)
        if (kind == FIRE && age % 7 == 1 && game.shotFx.room(ShotFx.NORMAL)) {
            double a = game.random.nextDouble() * Math.PI * 2, d = Math.sqrt(game.random.nextDouble()) * r * 0.7;
            game.shotFx.flame(new Location(game.world, at.getX() + Math.cos(a) * d, at.getY() + 0.25, at.getZ() + Math.sin(a) * d), 0.7f, 12,
                    ShotFx.NORMAL);
        }
        if (kind == GAS && age % 6 == 2 && game.shotFx.room(ShotFx.EXTRA)) {
            double a = game.random.nextDouble() * Math.PI * 2, d = Math.sqrt(game.random.nextDouble()) * r * 0.8;
            Location l = new Location(game.world, at.getX() + Math.cos(a) * d, at.getY() + 0.6 + game.random.nextDouble() * 0.8, at.getZ() + Math.sin(a) * d);
            ItemDisplay b = game.shotFx.spawn(l, "fx_quad", ShotFx.BLOOD, ShotFx.BLOOD_PINK, ShotFx.scale(0.9f), Display.Billboard.CENTER, false, 14,
                    ShotFx.EXTRA);
            game.shotFx.grow(b, ShotFx.scale(2.2f), 12);
        }
        if (age % 10 != 0) return;
        long sec = game.now() / 20;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - at.getX(), dz = l.getZ() - at.getZ();
            if (dx * dx + dz * dz > r * r || l.getY() > at.getY() + 2.5 || l.getY() < at.getY() - 1.5) continue;
            double[] t = TAKEN.computeIfAbsent(p.getUniqueId(), k -> new double[4]);
            int i = kind * 2;
            if ((long) t[i] != sec) {
                t[i] = sec;
                t[i + 1] = 0;
            }
            double dmg = Math.min(dps / 2, capPerSec - t[i + 1]);
            if (dmg <= 0) continue;
            t[i + 1] += dmg;
            if (src != null) src.hit(p, dmg, cause);
            else game.players.hurt(p, dmg, null, cause);
            if (kind == GAS) {
                // 기침: 조준이 흔들림
                Guns.kick(p, (float) (game.random.nextGaussian() * 2.5), (float) (-1.5 - game.random.nextDouble() * 2));
            }
        }
        if (mobDps > 0) {
            for (Gabe g : game.mobs.near(at, r + 0.5, g -> !g.dead && g != src && !g.sp.is(Role.BOSS) && !g.untargetable)) {
                g.damage(new Damage(mobDps / 2, DamageType.OTHER, by, g.pos.clone(), null, "gas", 0));
            }
        }
    }
}
