package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G41 가시 잠복(Players.latent 의 kind "g41"). 쏜 게이브가 죽어도 잠복은 이어지므로 종 개체가 아닌 플러그인 작업 하나가 매 틱 돈다
 * (잠복이 하나도 없으면 멈춤).
 *
 * <p>15초 동안 남들 눈에만 그 사람 등에서 엔드 막대기가 하나, 둘, 길게 자라 날개처럼 퍼덕인다(효과 디스플레이 하나가 그 사람을 따라다니고
 * 본인에게는 hideEntity: 본인 화면에는 안 보임). 본인에게는 두근 소리와 조준점 옆 작은 표시만, 둘레 사람에게는 작은 퍼덕 소리(위치 소리).
 * 다 자라면 등에서 새끼 가오리 3마리(보상 0)가 나와 그 사람을 쫓는다(4 피해씩). 동료가 등 뒤에서 돕기 3초로 짜내거나(엔진 공통), 정화,
 * 또는 동료가 등 쪽에서 쏘면(아군 탄 갈고리: 아군 피해 없이 막대기만 맞음) 풀린다.
 *
 * <p>사람에게 승객을 태우지 않는다(자리 옮기기가 얽히지 않게): 디스플레이를 매 틱 그 사람 자리, 보는 방향으로 옮긴다.
 */
final class G41Infest implements Guns.AllyShot {

    static final String KIND = "g41";
    static final int TICKS = 300, STAGE2 = 100, STAGE3 = 200, BABIES = 3, BABY_LIFE = 240;
    static final double BABY_DMG = 4, BABY_HP = 8;
    /** 등 막대기가 버티는 아군 탄 피해 합. */
    static final double ROD_HP = 28;

    private static G41Infest inst;

    final Game game;
    private final Map<UUID, Inf> list = new HashMap<>();
    private BukkitTask task;

    private static final class Inf {
        final UUID id;
        final long start;
        ItemDisplay d;
        int stage;
        double rodHp = ROD_HP;
        long nextFlutter, nextHeart, growAt = -1;

        Inf(UUID id, long start) {
            this.id = id;
            this.start = start;
        }
    }

    private G41Infest(Game game) {
        this.game = game;
    }

    static G41Infest of(Game g) {
        if (inst == null || inst.game != g) {
            inst = new G41Infest(g);
            g.guns.addAllyShot(inst);
        }
        return inst;
    }

    /** 가시에 맞음: 잠복을 건다(한 사람 하나, 판 전체 상한, 비옷이 막을 수 있음). 걸렸으면 true. */
    boolean infect(Player p, Gabe src) {
        UUID id = p.getUniqueId();
        if (list.containsKey(id)) return false;
        Inf inf = new Inf(id, game.now());
        boolean ok = game.players.latent(p, KIND, TICKS, q -> pop(q, inf), q -> cured(q, inf));
        if (!ok) return false;
        list.put(id, inf);
        rods(p, inf);
        if (task == null || task.isCancelled()) task = game.plugin.getServer().getScheduler().runTaskTimer(game.plugin, this::tick, 1, 1);
        return true;
    }

    /** 등 막대기 뿌리(사람 발 기준: 어깨뼈 높이, 등 뒤)를 중심으로 s 배. 팩 g41.py 의 g41_back 과 같은 자리. */
    private static Matrix4f grow(float s) {
        return new Matrix4f().translate(0f, 1.15f, -0.17f).scale(s).translate(0f, -1.15f, 0.17f).rotateY((float) Math.PI);
    }

    private void rods(Player p, Inf inf) {
        Location at = p.getLocation();
        ItemDisplay d = game.shotFx.spawn(at, "fx_quad", 0, 0, grow(0.35f), Display.Billboard.FIXED, false, TICKS + 60, true, ShotFx.KEY);
        if (d == null) return;
        inf.stage = 1;
        d.setItemStack(Body.item("gabe/g41_back", "s1", new LookState(), game.worldTime()));
        d.setBrightness(Body.LIGHT);
        d.setTeleportDuration(2);
        d.setViewRange(1.0f);
        // 본인 화면에는 안 보임(내 등에서 무엇이 퍼덕이는지 나만 모른다)
        p.hideEntity(game.plugin, d);
        inf.d = d;
        inf.growAt = game.now() + 2;
    }

    private void drop(Inf inf) {
        if (inf.d != null) inf.d.remove();
        inf.d = null;
    }

    private void tick() {
        if (list.isEmpty() || game.state == Game.State.LOBBY) {
            for (Inf inf : list.values()) drop(inf);
            list.clear();
            if (task != null) task.cancel();
            task = null;
            return;
        }
        long now = game.now();
        boolean silent = game.director.silent();
        for (Iterator<Inf> it = list.values().iterator(); it.hasNext();) {
            Inf inf = it.next();
            Player p = Bukkit.getPlayer(inf.id);
            if (p == null || !p.isOnline()) {
                drop(inf);
                it.remove();
                continue;
            }
            PState s = game.players.get(p);
            if (!KIND.equals(s.latent)) {
                // 죽거나 갇혀 잠복이 사라짐(터짐, 풀림은 콜백이 먼저 지움)
                drop(inf);
                it.remove();
                continue;
            }
            long el = now - inf.start;
            int stage = el >= STAGE3 ? 3 : el >= STAGE2 ? 2 : 1;
            ItemDisplay d = inf.d;
            if (d != null && d.isValid()) {
                Location l = p.getLocation();
                d.teleport(new Location(l.getWorld(), l.getX(), l.getY(), l.getZ(), l.getYaw(), 0f));
                if (stage != inf.stage) {
                    inf.stage = stage;
                    d.setItemStack(Body.item("gabe/g41_back", "s" + stage, new LookState(), game.worldTime()));
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(0);
                    d.setTransformationMatrix(grow(0.6f));
                    inf.growAt = now + 2;
                } else if (inf.growAt >= 0 && now >= inf.growAt) {
                    // 살을 뚫고 자라남: 그 단계 남은 시간 동안 크기 보간
                    inf.growAt = -1;
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(59);
                    d.setTransformationMatrix(grow(1f));
                }
            }
            if (!silent && now >= inf.nextFlutter) {
                inf.nextFlutter = now + (stage >= 3 ? 12 : stage == 2 ? 22 : 34);
                Props.soundAround(p.getLocation().add(0, 1.2, 0), "gabe.g41.flutter", Sound.ENTITY_PHANTOM_FLAP, 0.5f, 0.94f + game.random.nextFloat() * 0.12f,
                        2.0f, 10, -1);
            }
            if (now >= inf.nextHeart) {
                inf.nextHeart = now + (stage >= 3 ? 16 : 28);
                Props.sound(p, "gabe.g41.heart", Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1f, 1.2f);
                game.hud.mark(p, "g41", 36);
            }
        }
    }

    /** 다 자람: 등에서 새끼 가오리가 터져 나와 그 사람을 쫓는다. */
    private void pop(Player p, Inf inf) {
        drop(inf);
        list.remove(inf.id);
        Location l = p.getLocation();
        Vector back = l.getDirection().setY(0);
        if (back.lengthSquared() < 1e-6) back = new Vector(0, 0, 1);
        back.normalize().multiply(-1);
        Vector at = new Vector(l.getX() + back.getX() * 0.45, l.getY() + 1.15, l.getZ() + back.getZ() * 0.45);
        if (!game.director.silent())
            Props.soundAround(at.toLocation(game.world), "gabe.g41.burst", Sound.ENTITY_SLIME_SQUISH, 1f, 0.94f + game.random.nextFloat() * 0.12f, 0.6f, 24, -1);
        game.shotFx.bloodHit(at.toLocation(game.world), back.clone(), true, ShotFx.BLOOD_PINK);
        for (int i = 0; i < BABIES; i++) {
            if (!game.mobs.budgetFor(1)) break;
            double a = (i - (BABIES - 1) / 2.0) * 0.9;
            Vector out = back.clone().rotateAroundY(a);
            G41Baby b = new G41Baby(game, Species.G41, "gabe/g41_baby", "cause_g41_back", BABY_DMG, p, BABY_LIFE, BABY_HP, false);
            b.scatter(out, 16);
            b.yaw = (float) Math.toDegrees(Math.atan2(-out.getX(), out.getZ()));
            game.mobs.place(b, at.clone().add(out.clone().multiply(0.3)));
        }
    }

    private void cured(Player p, Inf inf) {
        drop(inf);
        list.remove(inf.id);
        if (!game.director.silent())
            Props.soundAround(p.getLocation().add(0, 1.2, 0), "gabe.g41.stick", Sound.ENTITY_ITEM_BREAK, 0.8f, 1f, 1.2f, 16, -1);
    }

    /** 아군 탄이 감염자 몸에 닿음: 등 쪽에서 온 탄이면 막대기가 대신 맞는다(아군 사격 벌 없음). 다 깎이면 잠복이 풀린다. */
    @Override
    public boolean take(Player shooter, Player victim, Vector from, Vector dir, double dist, Guns.Spec spec) {
        Inf inf = list.get(victim.getUniqueId());
        if (inf == null) return false;
        Vector f = victim.getLocation().getDirection().setY(0);
        Vector d = dir.clone().setY(0);
        if (f.lengthSquared() < 1e-6 || d.lengthSquared() < 1e-6) return false;
        if (f.normalize().dot(d.normalize()) < 0.3) return false;
        inf.rodHp -= spec.damage();
        game.hud.hit(shooter, false);
        if (inf.rodHp <= 0) game.players.cure(victim);
        return true;
    }

    /** 시험, 기록용: 지금 잠복 수. */
    int count() {
        return list.size();
    }
}
