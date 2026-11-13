package kr.gave.world;

import java.util.EnumSet;

import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * 사람이 갈 수 있는 끝(Plaza.R_ROAM). 피해 없이 부드럽게 되민다: 끝을 넘으면 안쪽으로 살짝 밀고(넘은 만큼 세게), 그래도 HARD 블록 넘게 나가 있으면
 * 시선은 그대로 둔 채 끝 바로 안으로 옮긴다. 끝 가까이 가면 그 사람에게만 흙먼지가 일어 막힌 곳임을 알린다.
 *
 * <p>바닐라 월드 경계를 쓰지 않는 까닭: 푸른 벽이 한가운데에서도 비쳐 보여 '끝이 안 보이는 황야'가 깨지고, 네모라서 고리(72~84)와 어긋난다.
 * 게이브(가짜 몹)와 탄은 이 끝과 상관없다(몹의 끝은 Plaza.R_EDGE, 엔진이 지킴).
 *
 * <p>Game.tick 이 모든 상태에서 매 틱 부른다. 크리에이티브, 관전, 좌석에 앉은 사람(쓰러짐, 끌려감), 날고 있는 사람(죽어서 지켜보는 중)은 건드리지 않는다.
 */
public final class Roam {

    /** 끝을 이만큼 넘게 나가 있으면 밀지 않고 끝 안으로 옮긴다(렉, 넉백으로 멀리 나간 경우). */
    public static final double HARD = 3.0;
    /** 끝에서 이 블록 안이면 흙먼지가 인다. */
    public static final double DUST_NEAR = 4.0;
    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(0x9A8672), 2.2f);

    private Roam() {
    }

    /** ARENA(2026-11-12 sp_engine): 사람의 끝을 줄이는 칸(키 → 반지름). 가장 작은 것이 이기고 없으면 Plaza.R_ROAM. 판이 바뀌면 Mobs.clear 가 지움. */
    private static final java.util.Map<Object, Double> limits = new java.util.LinkedHashMap<>();

    /** ARENA: 사람의 끝을 반지름 r 로(키마다, r 이 0 이하거나 R_ROAM 이상이면 뗌). 되밀기와 흙먼지가 그 자리로 옮는다. 몰려오는 고리 자리는 그대로. */
    public static void limit(Object key, double r) {
        if (key == null) return;
        if (r <= 0 || r >= Plaza.R_ROAM) limits.remove(key);
        else limits.put(key, Math.max(Plaza.R_SAFE + 2, r));
    }

    public static void clearLimits() {
        limits.clear();
    }

    /** 지금 사람의 끝 반지름(ARENA). */
    public static double radius() {
        double r = Plaza.R_ROAM;
        for (double v : limits.values()) r = Math.min(r, v);
        return r;
    }

    public static void tick(World w, long now) {
        for (Player p : w.getPlayers()) contain(p, now);
    }

    /** 이 사람이 되밀기 대상인가(판 안의 걷는 사람). */
    public static boolean applies(Player p) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return false;
        return !p.isInsideVehicle() && !p.isFlying() && !p.isDead();
    }

    static void contain(Player p, long now) {
        if (!applies(p)) return;
        Location l = p.getLocation();
        if (Plaza.cageRoom && Plaza.inLobby(l.getX(), l.getZ())) return;
        double dx = l.getX() - (Plaza.CX + 0.5), dz = l.getZ() - (Plaza.CZ + 0.5), r = Math.hypot(dx, dz);
        double lim = radius();
        if (r < lim - DUST_NEAR) return;
        double ux = dx / r, uz = dz / r;
        if ((now + (p.getEntityId() & 3)) % 4 == 0) dust(p, l, ux, uz, lim);
        double over = r - lim;
        if (over <= 0) return;
        if (over > HARD) {
            double rr = lim - 0.5;
            ((CraftPlayer) p).getHandle().connection.teleport(
                    new PositionMoveRotation(new Vec3(Plaza.CX + 0.5 + ux * rr, l.getY(), Plaza.CZ + 0.5 + uz * rr), Vec3.ZERO, 0f, 0f),
                    EnumSet.of(Relative.Y_ROT, Relative.X_ROT));
            return;
        }
        if ((now & 1) != 0) return;
        double v = 0.18 + 0.22 * over;
        p.setVelocity(new Vector(-ux * v, Math.max(-0.08, p.getVelocity().getY()), -uz * v));
    }

    /** 끝 바로 밖에 이는 흙먼지(그 사람에게만 보임). */
    private static void dust(Player p, Location l, double ux, double uz, double lim) {
        double a0 = Math.atan2(uz, ux);
        for (int i = 0; i < 5; i++) {
            double a = a0 + (Math.random() - 0.5) * 0.22, rr = lim + 0.6 + Math.random() * 1.2;
            p.spawnParticle(Particle.DUST, Plaza.CX + 0.5 + Math.cos(a) * rr, l.getY() + 0.2 + Math.random() * 2.0, Plaza.CZ + 0.5 + Math.sin(a) * rr, 1, 0.15, 0.2,
                    0.15, 0, DUST);
        }
    }
}
