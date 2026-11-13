package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.mob.Role;
import kr.gave.world.Plaza;

/**
 * G132 신호탄 게이브(떼, 지원). 떼 뒤쪽에 섞여 오다가 사람이 16블록 안에 보이면 멈춰 눈을 뜨고 통을 하늘로 세운다. 2초 뒤 12블록 위에서
 * 신호탄이 터지면 그 쪽 가장자리(중앙에서 R 60)에서 이번 라운드의 값싼 종 다섯이 달려오고(보상 0, 한 라운드에 스무 번까지), 터진 자리 12블록 안
 * 게이브가 5초 동안 20% 빨라진다. 쏜 뒤에는 통을 내리고 20초 동안 그 사람에게 달려들어 통으로 후려친다(9, 1블록 밀침). 세운 통을 든 채 죽으면
 * 통이 터져 2블록 안 사람에게 7.
 *
 * <p>신호탄과 불꽃은 셰이더 불꽃(ShotFx FIRE, FLASH, SPARK)이라 분홍이 아니라 주황이다(도감과 다른 점).
 *
 * <p>팩 계약(tools/gabe/sp/g132.py): 모델 gave:gabe/g132 상태 "", "aim", "spent", 소리 fuse(32), flare(64), swing(16), 글귀 cause_g132, cause_g132_pop.
 */
public final class G132 extends Gabe {

    static final double SPEED = 0.2, SEE = 16, STAY_FAR = 14, STAY_NEAR = 10, FLARE_UP = 12, BUFF_R = 12, BUFF = 1.2;
    static final int AIM = 40, CALL = 5, ROUND_CAP = 20, BUFF_T = 100, SPENT = 400, SWING_CD = 30, RISE = 12;
    static final double SWING = 9, POP = 7, POP_R = 2, EDGE_R = 60;

    enum Phase { WALK, AIM, SPENT }

    /** 판마다 이번 라운드에 터진 신호탄 수: {웨이브, 수}. */
    private static final Map<Game, int[]> FIRED = new WeakHashMap<>();

    private Phase phase = Phase.WALK;
    private int phaseAt, swingAt, buffUntil = -1;
    private final Vector buffAt = new Vector();
    private Player aimAt;

    public G132(Game game, Species sp) {
        super(game, sp);
        radius = 0.4;
        hover = 0.8;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g132", 1f, 2f);
    }

    @Override
    public void tick() {
        if (age < buffUntil) buffAround();
        switch (phase) {
            case WALK -> walk();
            case AIM -> aim();
            case SPENT -> spent();
        }
    }

    private void walk() {
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        double d = dist(p);
        if (d > STAY_FAR || !canFire()) follow(speed());
        else if (d < STAY_NEAR) {
            Vector away = pos.clone().subtract(chest(p)).setY(0);
            dash(away, speed() * 0.7);
            face(chest(p));
        } else {
            hold();
            face(chest(p));
        }
        if (!canFire()) {
            phase = Phase.SPENT;
            phaseAt = age - SPENT / 2;
            state("spent");
            return;
        }
        Player q = acquire(SEE);
        if (q != null) {
            phase = Phase.AIM;
            phaseAt = age;
            aimAt = q;
            mark(q);
            state("aim");
            sound("fuse", Sound.ENTITY_CREEPER_PRIMED, 0.7f, 32);
        }
    }

    private boolean canFire() {
        int[] f = FIRED.get(game);
        return f == null || f[0] != game.wave || f[1] < ROUND_CAP;
    }

    private void aim() {
        hold();
        if (aimAt != null && aimAt.isOnline()) face(chest(aimAt));
        pitch = 0;
        if (age - phaseAt < cd(AIM)) return;
        fire();
        phase = Phase.SPENT;
        phaseAt = age;
        state("spent");
    }

    /** 신호탄: 통에서 불씨가 솟아 12블록 위에서 터지고, 그 쪽 가장자리에서 다섯이 온다. */
    private void fire() {
        int[] f = FIRED.computeIfAbsent(game, g -> new int[2]);
        if (f[0] != game.wave) {
            f[0] = game.wave;
            f[1] = 0;
        }
        f[1]++;
        Location from = new Location(game.world, pos.getX(), pos.getY() + 1.4, pos.getZ());
        Location top = from.clone().add(0, FLARE_UP, 0);
        ItemDisplay ember = game.shotFx.spawn(from, "fx_quad", ShotFx.FIRE, 0, ShotFx.scale(0.6f), Display.Billboard.CENTER, true, RISE + 2, ShotFx.KEY);
        game.shotFx.drift(ember, top, RISE);
        Vector at = top.toVector();
        Bukkit.getScheduler().runTaskLater(game.plugin, () -> burst(at), RISE);
        buffAt.copy(at).setY(pos.getY());
        buffUntil = age + RISE + BUFF_T;
    }

    private void burst(Vector at) {
        if (game.state != Game.State.WAVE) return;
        Location l = at.toLocation(game.world);
        ItemDisplay fl = game.shotFx.spawn(l, "fx_quad", ShotFx.FLASH, 0, ShotFx.scale(4f), Display.Billboard.CENTER, true, 4, ShotFx.KEY);
        game.shotFx.grow(fl, ShotFx.scale(9f), 2);
        game.shotFx.flame(l, 2.6f, 40, ShotFx.KEY);
        game.shotFx.spawn(l, "fx_quad", ShotFx.SPARK, 0, ShotFx.scale(5f), Display.Billboard.CENTER, true, 8, ShotFx.NORMAL);
        Props.soundAround(l, "gabe.g132.flare", Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 4f, 0.05f, 0.8f, 96, -1);
        call(at);
    }

    /** 터진 쪽 가장자리에서 이번 라운드의 값싼 종 다섯(보상 0, 예산 안). */
    private void call(Vector at) {
        List<Species> cheap = new ArrayList<>();
        for (Species s : game.director.waveSpecies()) {
            if (s == sp || !s.implemented() || s.cost != 1 || s.spawn != Species.Spawn.RING) continue;
            if (s.is(Role.BOSS) || s.is(Role.ELITE) || s.is(Role.BONUS) || s.is(Role.OBJECTIVE)) continue;
            cheap.add(s);
        }
        if (cheap.isEmpty() && Species.G01.implemented()) cheap.add(Species.G01);
        if (cheap.isEmpty()) return;
        double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5;
        double a = Math.atan2(at.getZ() - cz, at.getX() - cx);
        for (int i = 0; i < CALL; i++) {
            Species s = cheap.get(game.random.nextInt(cheap.size()));
            double b = a + (game.random.nextDouble() - 0.5) * 0.3;
            double x = cx + Math.cos(b) * EDGE_R, z = cz + Math.sin(b) * EDGE_R;
            Vector v = new Vector(x, game.mobs.flow.floor(x, z) + s.band.hover, z);
            if (game.mobs.spawn(s, v, true) == null) break;
        }
    }

    /** 터진 자리 12블록 안 게이브가 빨라짐(매 틱 걸어야 다음 틱까지). */
    private void buffAround() {
        for (Gabe g : game.mobs.near(buffAt, BUFF_R, g -> !g.sp.is(Role.BOSS))) g.speedBuff = Math.max(g.speedBuff, BUFF);
    }

    private void spent() {
        Player p = nearest(400);
        if (p != target) mark(p);
        if (p != null) {
            follow(speed() * 1.2);
            if (age >= swingAt && touches(p, 0.4)) {
                swingAt = age + cd(SWING_CD);
                hit(p, SWING, "cause_g132");
                knock(p, toward(p), 0.45, 0.15);
                body.pose(2);
                sound("swing", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 16);
            }
        } else follow(speed());
        if (age - phaseAt >= SPENT && canFire()) {
            phase = Phase.WALK;
            phaseAt = age;
            state("");
            mark(null);
        }
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (phase == Phase.AIM) {
            // 세운 통이 터짐
            Location l = new Location(game.world, pos.getX(), pos.getY() + 0.8, pos.getZ());
            game.shotFx.pop(l);
            for (Player p : game.mobs.standing()) if (p.getLocation().add(0, 1, 0).distance(l) <= POP_R + 0.6) hit(p, POP, "cause_g132_pop");
        }
        super.die(d);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.4});
        out.add(new double[] {pos.getX(), pos.getY() + (phase == Phase.AIM ? 0.9 : 0.55), pos.getZ(), 0.25});
        return out;
    }

    @Override
    public String toString() {
        int[] f = FIRED.get(game);
        return super.toString() + " " + phase + " fired=" + (f == null ? 0 : f[1]) + (age < buffUntil ? " buff" : "");
    }
}
