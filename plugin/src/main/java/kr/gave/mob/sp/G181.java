package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G181 잿빛 돌 게이브(3막 지원, 원거리). 감독이 미리 깔면(PLACED) 하늘에 숨어 있다가 10초 뒤 트럭에서 18~30 블록 자리로 떨어져 박힌다(2초 전 그 자리에
 * 그림자 원과 하늘의 잿빛 꼬리, 떨어질 때 반경 3 안 24). 움직이지 않는다.
 * <ul>
 * <li>둘레 10 블록 땅이 잿빛으로 바래고, 그 안에 든 게이브(보스 빼고)는 3초 만에 1.4배로 부풀어(체력 x1.5, 피해 x1.4: MOBDMG {@code Gabe.dmgMul}) 눈이
 * 잿빛으로 바래고(PAL_PALE), 부푼 지 12초가 지나면 바스러져 저절로 죽는다(보상 절반: 그때까지 맞힌 사람들에게). 부풂과 바스러짐은 Mobs.Field "G181" 이
 * 돌이 죽은 뒤에도 마저 센다.</li>
 * <li>돌은 14 블록 안 사람에게 2초마다 수정 가시를 쏜다(가시 탄은 종 모델을 효과 예산 디스플레이에 실어 날림): 10 피해와 2초 20% 느려짐.
 * 날아오는 동안 비키면 빗나간다.</li>
 * <li>잿빛 땅 안에 선 사람은 1초마다 최대 체력이 3 씩 깎인다(50 까지, 돌이 죽어야 돌아옴). PState.maxHp 는 증강 계산(Augments.apply)이 10틱마다
 * 다시 쓰므로 직접 못 깎는다: 돌이 사는 동안 그 사람 체력을 (최대 체력 - 깎인 몫) 위로 못 오르게 누른다(요청 참고). 낯선 울림은 잿빛 땅 안의 사람에게만.</li>
 * <li>윗면(금 간 자리)에 맞은 탄은 x2.</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g181.py): 모델 gave:gabe/g181(shrink 0.5) 상태 "", gave:gabe/g181_ground 상태 ""(잿빛 땅), "shadow"(떨어질 자리),
 * gave:gabe/g181_spike 상태 ""(가시 탄). 소리 drone(32), fall(48), spike(24), crumble(20). 글귀 cause_g181, cause_g181_fall.
 */
public final class G181 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double HOVER = 1.1, FALL_H = 34, FALL_DMG = 24, FALL_R = 3, BLIGHT_R = 10, SPIKE = 10, SPIKE_R = 14, SPIKE_SLOW = 0.2;
    static final double SWELL = 1.4, HP_UP = 1.5, DMG_UP = 1.4, MAXHP_STEP = 3, MAXHP_CAP = 50, LAND_MIN = 18, LAND_MAX = 30, CRACK = 2;
    static final int HIDE_T = 200, FALL_T = 40, SPIKE_EVERY = 40, SPIKE_FLY = 8, SPIKE_SLOW_T = 40, SWELL_GROW = 60, SWELL_LIFE = 240,
        BLIGHT_EVERY = 20, SCAN_EVERY = 10, DRONE_EVERY = 60;
    static final String FIELD = "G181";

    /** 부푼 게이브와 부풀기 시작한 틱(판 하나: 돌이 죽어도 Field 가 마저 셈). */
    static final Map<Gabe, Long> SWOLLEN = new LinkedHashMap<>();

    enum Phase { HIDDEN, FALLING, LANDED }

    private Phase phase = Phase.HIDDEN;
    private int phaseAt;
    private final Vector land = new Vector();
    private double floorY;
    private Body.Part ground;
    private ItemDisplay shadow;
    /** 사람마다 깎인 최대 체력. */
    private final Map<UUID, Double> blight = new HashMap<>();
    private int nextSpike, nextDrone;
    /** 날아가는 가시: 표적, 닿는 나이, 떨어질 자리. */
    private final List<Object[]> spikes = new ArrayList<>(4);

    public G181(Game game, Species sp) {
        super(game, sp);
        radius = 1.1;
        hover = HOVER;
        baseSpeed = 0;
        noPush = true;
        ghost = true;
        flying = true;
        ragKick = 0.3;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g181", 4.2f, 3.0f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        // 떨어질 자리: 트럭에서 18~30 블록, 놓인 자리(감독 placeFor, 시험 소환)와 같은 방향에서 가장 가까운 곳(걸을 수 있는 곳)
        Vector c = Truck.live() ? Truck.center() : new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
        double a0 = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
        double r0 = Math.max(LAND_MIN, Math.min(LAND_MAX, Math.hypot(pos.getX() - c.getX(), pos.getZ() - c.getZ())));
        double x = c.getX() + Math.cos(a0) * r0, z = c.getZ() + Math.sin(a0) * r0;
        for (int t = 0; t < 30 && !game.mobs.flow.walkable(x, z); t++) {
            double a = a0 + (game.random.nextDouble() - 0.5) * 0.6 * (1 + t * 0.2), r = LAND_MIN + game.random.nextDouble() * (LAND_MAX - LAND_MIN);
            x = c.getX() + Math.cos(a) * r;
            z = c.getZ() + Math.sin(a) * r;
        }
        floorY = game.mobs.flow.floor(x, z);
        land.setX(x).setY(floorY + HOVER).setZ(z);
        pos.copy(land.clone().add(new Vector(0, FALL_H, 0)));
        untargetable = true;
        body.scale(0.001f, 0);
        if (game.mobs.field(FIELD) == null) game.mobs.field(FIELD, new Swell(game));
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case HIDDEN -> hidden();
            case FALLING -> falling();
            case LANDED -> landed();
        }
    }

    /** 숨어 있음(보이지 않고 맞지 않음). 2초 전부터 떨어짐. */
    private void hidden() {
        hold();
        if (age < HIDE_T - FALL_T) return;
        body.scale(1f, 2);
        shadow = flat("shadow", (float) FALL_R, FALL_T + 4);
        phase(Phase.FALLING);
    }

    /** 땅 위 납작 판(효과 예산 디스플레이에 종 모델을 실음). */
    private ItemDisplay flat(String state, float scale, int life) {
        Location at = new Location(game.world, land.getX(), floorY + 0.02, land.getZ());
        ItemDisplay d = game.shotFx.spawn(at, "fx_quad", 0, 0, ShotFx.scale(scale), Display.Billboard.FIXED, false, life, ShotFx.KEY);
        if (d == null) return null;
        d.setItemStack(Body.item("gabe/g181_ground", state, new LookState(), game.worldTime()));
        d.setBrightness(new Display.Brightness(12, 12));
        d.setDisplayWidth(0);
        d.setDisplayHeight(0);
        d.setViewRange(3f);
        return d;
    }

    /** 하늘에서 떨어짐(가속, 잿빛 꼬리), 닿으면 반경 3 안 24. */
    private void falling() {
        int t = age - phaseAt;
        double f = Math.min(1, (t + 1) / (double) FALL_T);
        Vector want = land.clone().add(new Vector(0, FALL_H * (1 - f * f), 0));
        vel.copy(want.subtract(pos));
        if (t % 3 == 0) {
            Location l = loc().add(0, 1.2, 0);
            game.shotFx.spawn(l, "fx_quad", ShotFx.SMOKE, 0, ShotFx.scale(1.4f), Display.Billboard.CENTER, false, 24, ShotFx.EXTRA);
        }
        if (t < FALL_T) return;
        hold();
        pos.copy(land);
        if (shadow != null && shadow.isValid()) shadow.remove();
        shadow = null;
        game.shotFx.explosion(new Location(game.world, land.getX(), floorY + 0.5, land.getZ()));
        sound("fall", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 48);
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - land.getX(), l.getZ() - land.getZ()) > FALL_R || Math.abs(l.getY() - floorY) > 3) continue;
            hit(p, FALL_DMG, "cause_g181_fall");
            Vector out = l.toVector().subtract(land).setY(0);
            knock(p, out.lengthSquared() > 1e-6 ? out : new Vector(1, 0, 0), 0.5, 0.3);
        }
        untargetable = false;
        ground = body.attach("gabe/g181_ground");
        if (ground != null) {
            ground.follow = false;
            ground.pose(new Matrix4f().translate(0f, (float) (floorY + 0.02 - pos.getY()), 0f).scale((float) BLIGHT_R), 0);
            if (ground.d != null) {
                ground.d.setDisplayWidth(0f);
                ground.d.setDisplayHeight(0f);
            }
        }
        Player p = nearest(400);
        if (p != null) mark(p);
        nextSpike = age + 20;
        phase(Phase.LANDED);
    }

    private void landed() {
        hold();
        long now = game.now();
        if (target == null || !standing(target)) {
            // 웨이브가 이 개체를 붙들게(감독: PLACED 이면서 target 이 없으면 20초 뒤 웨이브 끝)
            Player p = nearest(400);
            if (p != null) mark(p);
        }
        body.look.open = !spikes.isEmpty();
        if (every(SCAN_EVERY)) swellAround(now);
        if (every(BLIGHT_EVERY)) blightTick();
        pressHp();
        spikeTick();
    }

    /** 둘레 10 블록 안에 든 게이브를 부풀림(처음 든 것만). */
    private void swellAround(long now) {
        for (Gabe g : game.mobs.near(land, BLIGHT_R, g -> g != this && !g.unhittable() && !g.noRules && !g.sp.is(Role.BOSS) && !(g instanceof G181))) {
            if (SWOLLEN.containsKey(g)) continue;
            SWOLLEN.put(g, now);
            g.maxHp *= HP_UP;
            g.hp *= HP_UP;
            g.dmgMul *= DMG_UP;
            if (g.body != null) {
                g.body.scale(g.body.scale * (float) SWELL, SWELL_GROW);
                g.body.look.palette |= Look.PAL_PALE;
            }
        }
    }

    /** 잿빛 땅 안의 사람: 1초마다 최대 체력 3 씩(50 까지), 낯선 울림은 그 사람에게만. */
    private void blightTick() {
        boolean drone = age >= nextDrone;
        if (drone) nextDrone = age + DRONE_EVERY;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - land.getX(), l.getZ() - land.getZ()) > BLIGHT_R || Math.abs(l.getY() - floorY) > 4) continue;
            blight.merge(p.getUniqueId(), MAXHP_STEP, (a, b) -> Math.min(MAXHP_CAP, a + b));
            if (drone) soundFor(p, "drone", Sound.BLOCK_BEACON_AMBIENT, 0.9f);
        }
    }

    /** 깎인 최대 체력: 체력이 (최대 체력 - 깎인 몫) 위로 못 오름(돌이 사는 동안). */
    private void pressHp() {
        if (blight.isEmpty()) return;
        for (Map.Entry<UUID, Double> e : blight.entrySet()) {
            PState s = game.players.get(e.getKey());
            if (s == null || !s.standing()) continue;
            s.cutMaxHp("g181_" + id, e.getValue(), game.now() + 40);
        }
    }

    /** 2초마다 14 블록 안 사람에게 수정 가시. 가시는 8틱 날아가고, 그때 그 자리 1.6 블록 안에 있으면 맞음. */
    private void spikeTick() {
        for (Iterator<Object[]> it = spikes.iterator(); it.hasNext();) {
            Object[] s = it.next();
            if (age < (int) s[1]) continue;
            it.remove();
            Player p = (Player) s[0];
            Vector at = (Vector) s[2];
            if (!standing(p) || Mobs.chest(p).distance(at) > 1.6) continue;
            hit(p, SPIKE, "cause_g181");
            game.players.slow(p, SPIKE_SLOW, SPIKE_SLOW_T, "g181_spike");
        }
        if (age < nextSpike) return;
        Player p = acquire(SPIKE_R);
        if (p == null) {
            nextSpike = age + 10;
            return;
        }
        nextSpike = age + cd(SPIKE_EVERY);
        Vector to = Mobs.chest(p);
        Vector dir = to.clone().subtract(pos);
        double len = dir.length();
        if (len < 1e-3) return;
        dir.multiply(1 / len);
        Location from = pos.clone().add(dir.clone().multiply(1.3)).toLocation(game.world);
        ItemDisplay d = game.shotFx.spawn(from, "fx_quad", 0, 0, ShotFx.along(dir, 1f, 1f), Display.Billboard.FIXED, false, SPIKE_FLY + 3, ShotFx.NORMAL);
        if (d == null) return;
        d.setItemStack(Body.item("gabe/g181_spike", "", new LookState(), game.worldTime()));
        d.setBrightness(new Display.Brightness(14, 14));
        game.shotFx.drift(d, to.toLocation(game.world), SPIKE_FLY);
        spikes.add(new Object[] {p, age + SPIKE_FLY, to});
        sound("spike", Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.2f, 24);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public boolean damage(Damage d) {
        if (dead || unhittable()) return false;
        // 윗면 금 간 자리 x2
        if (d.at() != null && d.at().getY() >= pos.getY() + 0.5 && d.type() != DamageType.FIRE) return super.damage(d.times(CRACK));
        return super.damage(d);
    }

    private void cleanup() {
        if (shadow != null && shadow.isValid()) shadow.remove();
        shadow = null;
        blight.clear();
        spikes.clear();
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        cleanup();
        if (ground != null) {
            ground.remove();
            ground = null;
        }
        super.die(d);
    }

    @Override
    public void remove() {
        cleanup();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + " swollen=" + SWOLLEN.size() + " blight=" + blight.size();
    }

    // ------------------------------------------------------------------ 부푼 게이브 마저 세기(FIELDTICK)

    /** 부푼 지 3초(커짐) + 12초가 지나면 바스러뜨림(보상 절반). 판이 바뀌면 지움. */
    static final class Swell implements Mobs.Field {
        final Game game;

        Swell(Game game) {
            this.game = game;
        }

        @Override
        public boolean tick(long now) {
            if (SWOLLEN.isEmpty() || (now % 5) != 0) return true;
            for (Iterator<Map.Entry<Gabe, Long>> it = SWOLLEN.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Gabe, Long> e = it.next();
                Gabe g = e.getKey();
                if (g.dead || g.removed) {
                    it.remove();
                    continue;
                }
                if (now - e.getValue() < SWELL_GROW + SWELL_LIFE) continue;
                it.remove();
                g.reward = Math.max(0, g.reward / 2);
                kr.gave.Props.soundAround(g.loc(), "gabe.g181.crumble", Sound.BLOCK_GRAVEL_BREAK, 1f, 0.9f, 0.8f, 20, -1);
                game.mobs.damage(g, new Damage(g.hp + 1, DamageType.OTHER, null, g.pos.clone(), new Vector(0, 1, 0), "g181", 0));
            }
            return true;
        }

        @Override
        public void reset() {
            SWOLLEN.clear();
        }
    }
}
