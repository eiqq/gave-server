package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * G30 지네 잠복(Players.latent 의 kind "g30"). 잠복은 찌른 지네의 행동(물러남, 다음 사냥)과 따로 굴러가므로 플러그인 작업 하나가 매 틱 돈다
 * (잠복이 하나도 없으면 스스로 멈춤, 대기실로 돌아가도 멈춤).
 *
 * <p>20초 동안 시점이 조금씩(틱마다 0.45도, 2틱에 한 번 회전 패킷) 가장 가까운 동료 쪽으로 돌아가고, 마지막 3초는 빠르게 돌며 몸이 그 동료
 * 쪽으로 걸어간다(속도에 조금씩 더함). 터지면 등에서 새끼 지네(머리 칸 모델 2초)가 나온다: 본인은 최대 체력의 30%(도감 '본인 체력 30% 피해'),
 * 4.5블록 안 가장 가까운 동료는 물려 그 사람 최대 체력의 30%. 걸린 동안 본인 화면 조준점 곁에 지네 표시가 깜빡인다.
 *
 * <p>풀리는 길: 동료가 등 뒤에서 돕기 3초(짜내기), 정화(엔진 공통), 보급 상자 등불 아래(중앙 원 한가운데 둘레 3블록) 10초(게이지),
 * 찌른 지네가 죽거나 치워짐({@link #CURE_ON_SOURCE_DEATH}), 웨이브가 끝남(쉬는 시간으로 넘어가면 모두 풀림).
 */
final class G30Infest {

    static final String KIND = "g30";
    static final int TICKS = 400, WALK_FROM = 340, BABY_SHOW = 40;
    /** 시점 끌기(도/틱): 처음 17초, 마지막 3초. */
    static final float TURN_SLOW = 0.45f, TURN_FAST = 1.8f;
    static final double ALLY_R = 40, BITE_R = 4.5, HOST_FRAC = 0.3, BITE_FRAC = 0.3, WALK = 0.09;
    /** 보급 상자 등불 아래에 이만큼 이어 머물면 풀림. */
    static final int LANTERN_T = 200;
    /**
     * 찌른 지네가 죽거나 치워지면 잠복도 풀린다(종 공통 규칙: 사람에게 건 것은 그 개체가 죽으면 풀림). false 로 두면 도감 글자 그대로
     * 지네가 죽어도 20초를 채운다(그때도 웨이브가 끝나면 풀림).
     */
    static final boolean CURE_ON_SOURCE_DEATH = true;

    private static G30Infest inst;

    final Game game;
    private final Map<UUID, Inf> list = new HashMap<>();
    private BukkitTask task;

    private static final class Inf {
        final UUID id;
        final long start;
        final Gabe src;
        int babyLeft = -1;
        /** 등불 아래 이어 머문 틱. */
        int lit;
        /** 끌려가는 동료(10틱마다 다시 고름). */
        Player ally;

        Inf(UUID id, long start, Gabe src) {
            this.id = id;
            this.start = start;
            this.src = src;
        }
    }

    private G30Infest(Game game) {
        this.game = game;
    }

    static G30Infest of(Game g) {
        if (inst == null || inst.game != g) inst = new G30Infest(g);
        return inst;
    }

    /** 찔림: 잠복을 건다(한 사람 하나, 판 전체 상한, 비옷이 막을 수 있음). 웨이브 중에만. */
    void infect(Player p, Gabe src) {
        if (game.state != Game.State.WAVE || src == null) return;
        UUID id = p.getUniqueId();
        if (list.containsKey(id)) return;
        Inf inf = new Inf(id, game.now(), src);
        boolean ok = game.players.latent(p, KIND, TICKS, q -> pop(q, inf), q -> cured(q));
        if (!ok) return;
        list.put(id, inf);
        game.hud.mark(p, KIND, 14);
        if (task == null || task.isCancelled()) task = game.plugin.getServer().getScheduler().runTaskTimer(game.plugin, this::tick, 1, 1);
    }

    /** 풀림(짜내기, 정화, 등불, 지네 죽음, 웨이브 끝): 목록에서 빼고 등불 게이지를 지움. */
    private void cured(Player q) {
        Inf inf = list.remove(q.getUniqueId());
        if (inf != null && inf.lit > 0) game.hud.meter(q, KIND, -1, 0);
    }

    private void tick() {
        if (list.isEmpty() || game.state == Game.State.LOBBY) {
            for (Inf inf : list.values()) dropBaby(inf);
            list.clear();
            if (task != null) task.cancel();
            task = null;
            return;
        }
        long now = game.now();
        boolean wave = game.state == Game.State.WAVE;
        List<Player> cure = null;
        for (Iterator<Inf> it = list.values().iterator(); it.hasNext();) {
            Inf inf = it.next();
            Player p = Bukkit.getPlayer(inf.id);
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            PState s = game.players.get(p);
            if (inf.babyLeft >= 0) {
                if (--inf.babyLeft <= 0) {
                    dropBaby(inf);
                    it.remove();
                }
                continue;
            }
            if (!KIND.equals(s.latent)) {
                it.remove();
                continue;
            }
            // 웨이브가 끝났거나 찌른 지네가 죽음: 풀림(cure 가 목록을 고치므로 돌고 난 뒤에)
            if (!wave || (CURE_ON_SOURCE_DEATH && (inf.src.dead || inf.src.removed))) {
                if (cure == null) cure = new ArrayList<>(2);
                cure.add(p);
                continue;
            }
            if (s.life != Life.ALIVE) continue;
            if (now % 10 == 0) game.hud.mark(p, KIND, 14);
            Location l = p.getLocation();
            // 보급 상자 등불 아래 10초(이어서)
            if (G30.nearLantern(l.getX(), l.getZ())) {
                inf.lit++;
                game.hud.meter(p, KIND, inf.lit / (double) LANTERN_T, 6);
                if (inf.lit >= LANTERN_T) {
                    if (cure == null) cure = new ArrayList<>(2);
                    cure.add(p);
                    Props.sound(l, null, Sound.ENTITY_SLIME_SQUISH, 1f, 0.7f, 1f);
                    continue;
                }
            } else if (inf.lit > 0) {
                inf.lit = 0;
                game.hud.meter(p, KIND, -1, 0);
            }
            long el = now - inf.start;
            if (inf.ally == null || now % 10 == 0 || !inf.ally.isOnline()) inf.ally = nearestAlly(p, ALLY_R);
            Player ally = inf.ally;
            if (ally == null || !ally.isOnline() || ally.getWorld() != p.getWorld()) continue;
            Location al = ally.getLocation();
            double dx = al.getX() - l.getX(), dz = al.getZ() - l.getZ();
            float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float diff = wrap(want - l.getYaw());
            boolean last = el >= WALK_FROM;
            float step = (last ? TURN_FAST : TURN_SLOW) * 2;
            if (now % 2 == 0 && Math.abs(diff) > 0.5f) Guns.kick(p, Math.signum(diff) * Math.min(Math.abs(diff), step), 0f);
            if (last) {
                double h = Math.hypot(dx, dz);
                if (h > 1e-3) {
                    Vector v = p.getVelocity();
                    p.setVelocity(new Vector(v.getX() * 0.6 + dx / h * WALK, v.getY(), v.getZ() * 0.6 + dz / h * WALK));
                }
            }
        }
        if (cure != null) for (Player p : cure) game.players.cure(p);
    }

    /** 터짐: 등에서 새끼 지네. 본인은 최대 체력의 30%, 가까운 동료는 물려 그 사람 최대 체력의 30%. 피해는 찌른 지네의 hit() 로. */
    private void pop(Player p, Inf inf) {
        Gabe src = inf.src;
        double mul = Math.max(0.01, game.director.dmgMul());
        Player ally = nearestAlly(p, BITE_R);
        Location from = p.getLocation().add(0, 1.1, 0);
        if (!game.director.silent()) Props.soundAround(from, "gabe.g30.baby", Sound.ENTITY_SILVERFISH_AMBIENT, 1f, 1f, 0.8f, 16, -1);
        PState s = game.players.get(p);
        if (inf.lit > 0) game.hud.meter(p, KIND, -1, 0);
        // 등이 찢김(쓰러진 사람에게는 피해 없음: Players.hurt 가 서 있는 사람만 다치게 함)
        Vector back = p.getLocation().getDirection().setY(0);
        if (back.lengthSquared() > 1e-6) back.normalize().multiply(-1);
        else back = new Vector(0, 1, 0);
        game.shotFx.bloodHit(from, back, true, ShotFx.BLOOD_PINK);
        src.hit(p, s.maxHp * HOST_FRAC / mul, "cause_g30_back");
        if (ally != null) {
            PState as = game.players.get(ally);
            Location tl = ally.getLocation().add(0, 1.1, 0);
            Vector dir = tl.toVector().subtract(from.toVector());
            game.shotFx.bloodHit(tl, dir.lengthSquared() > 1e-6 ? dir.normalize() : new Vector(0, 1, 0), false, ShotFx.BLOOD_PINK);
            src.hit(ally, as.maxHp * BITE_FRAC / mul, "cause_g30_baby");
        }
        // 새끼 지네가 등(머리 칸 모델)에 2초 보임: 본인이 아직 서 있고 다른 것이 머리에 붙어 있지 않을 때만
        if (s.life == Life.ALIVE && s.headItem == null) {
            ItemStack baby = Body.item("gabe/g30_tail", "raise", new LookState(), game.worldTime());
            game.players.attachHead(p, baby, null);
            inf.babyLeft = BABY_SHOW;
            list.put(p.getUniqueId(), inf);
        } else list.remove(p.getUniqueId());
    }

    private void dropBaby(Inf inf) {
        if (inf.babyLeft < 0) return;
        Player p = Bukkit.getPlayer(inf.id);
        if (p == null) return;
        PState s = game.players.get(p);
        if (s.headBy == null && s.headItem != null && s.headItem.getType() == org.bukkit.Material.PAPER) game.players.attachHead(p, null);
        inf.babyLeft = -1;
    }

    /** 반경 안 가장 가까운 서 있는 동료(본인 빼고). 엔진이 틱마다 모아 둔 목록을 읽는다. */
    private Player nearestAlly(Player p, double r) {
        Player best = null;
        double bd = r * r;
        Location l = p.getLocation();
        for (Player q : game.mobs.standing()) {
            if (q == p || q.getWorld() != p.getWorld()) continue;
            double d = q.getLocation().distanceSquared(l);
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        return best;
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
