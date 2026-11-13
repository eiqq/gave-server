package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Corpse;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;

/**
 * G120 사체 버섯 게이브(떼, 원거리). 굴러온 얼굴 포자가 게이브 사체에 뿌리내려 얼굴 갓 버섯으로 솟고 포자 구름을 쏜다.
 *
 * <p>포자(체력 10): 20블록 안에서 사람 곁(36블록) 사체를 골라 굴러가 내려앉고, 2초 만에 버섯이 솟는다(사체는 먹혀 사라짐). 고를 사체가 없으면
 * 사람에게 바로 달려들어 문다(5). 버섯(체력 60 x 감독 배율): 그 자리에서 3초마다 갓을 오므렸다 펴며 10블록 안 가장 가까운 사람에게 포자 구름을
 * 보낸다(초당 6블록, 닿은 자리 반경 2 에 2초 머묾, 0.5초마다 4 와 기침: 조준 흔들림). 3블록 안이면 갓으로 덮쳐 14. 20초마다 곁의 사체로 새 포자
 * 둘(보상 0). 갓은 피해 절반, 대는 그대로. 14블록 안에 사람이 30초 없으면 스스로 시든다(보상 없음, 웨이브를 붙들지 않게).
 *
 * <p>바꾼 것: 사체가 불타는지는 엔진에 없어 '불로 태워 두기'는 사체 위에 불(화염병, 화염탄)이 있으면 그 사체를 고르지 않는 것으로 했다.
 * 포자 구름은 종 전용 모델(g120_cloud)을 연출 디스플레이에 실어 날린다(효과 예산 안).
 *
 * <p>팩 계약(tools/gabe/sp/g120.py): 모델 gave:gabe/g120 상태 "seed", "grow", "", gave:gabe/g120_cloud, 소리 puff(20), root(16), bite(12),
 * 글귀 cause_g120.
 */
public final class G120 extends Gabe {

    static final double SEED_HOVER = 0.3, SHROOM_HOVER = 1.1, SEED_SPEED = 0.22;
    static final double PICK_R = 20, MEAL_NEAR = 36, BITE = 5;
    static final int BITE_CD = 30, GROW = 40;
    static final double SHROOM_HP = 60;
    /** 구름: 겨누는 거리, 날아가는 빠르기(블록/틱), 머무는 틱, 반경, 피해, 간격. */
    static final double CLOUD_RANGE = 10, CLOUD_SPEED = 0.3, CLOUD_R = 2, CLOUD_DMG = 4;
    static final int CLOUD_CD = 60, CLOUD_STAY = 40, CLOUD_TICK = 10;
    static final double SMASH_R = 3, SMASH = 14;
    static final int SMASH_CD = 40, SPREAD_CD = 400, WITHER = 600;
    static final double WITHER_R = 14;

    enum Phase { SEED, GROW, SHROOM }

    /** 포자들이 고른 사체(둘이 한 사체로 가지 않게). */
    private static final Set<Corpse> CLAIMED = Collections.newSetFromMap(new IdentityHashMap<>());

    /** 날아가거나 머무는 포자 구름 하나. */
    private static final class Cloud {
        final Vector at;
        final long land, until;
        long nextTick;

        Cloud(Vector at, long land, long until) {
            this.at = at;
            this.land = land;
            this.until = until;
            this.nextTick = land;
        }
    }

    private Phase phase = Phase.SEED;
    private Corpse meal;
    private final List<Cloud> clouds = new ArrayList<>(2);
    private int phaseAt, readyAt, pickAt, cloudAt = 40, smashAt, spreadAt, aloneSince = -1;
    private double unit = 1;

    public G120(Game game, Species sp) {
        super(game, sp);
        radius = 0.3;
        hover = SEED_HOVER;
        baseSpeed = SEED_SPEED;
        ragRods = 0;
        state("seed");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g120", 2f, 2.6f);
    }

    @Override
    public void tick() {
        if (age == 0) unit = Math.max(1.0, maxHp / Math.max(1.0, sp.hp));
        switch (phase) {
            case SEED -> seed();
            case GROW -> grow();
            case SHROOM -> shroom();
        }
    }

    // ------------------------------------------------------------------ 포자

    private void seed() {
        hover = SEED_HOVER + Math.abs(Math.sin(age * 0.35)) * 0.35;
        if (meal != null && (meal.gone() || burning(meal.pos))) dropMeal();
        if (meal == null && age >= pickAt) {
            pickAt = age + 10;
            meal = pick();
            if (meal != null) CLAIMED.add(meal);
        }
        if (meal != null) {
            Vector m = meal.pos;
            double d = Math.hypot(m.getX() - pos.getX(), m.getZ() - pos.getZ());
            if (d < 0.8) {
                root();
                return;
            }
            seek(new Vector(m.getX(), pos.getY(), m.getZ()), speed());
            if (age % 20 == 0) {
                Player p = nearest(400);
                if (p != target) mark(p);
            }
            return;
        }
        Player p = nearest(400);
        if (p != target) mark(p);
        follow(speed());
        if (p != null && age >= readyAt && touches(p, 0.2)) {
            readyAt = age + cd(BITE_CD);
            hit(p, BITE, "cause_g120");
            knock(p, toward(p), 0.25, 0.1);
            sound("bite", Sound.ENTITY_SLIME_ATTACK, 1.5f, 12);
        }
    }

    /** 20블록 안, 사람 곁의 사체 가운데 가장 가까운 것(다른 포자가 고른 것, 불 위의 것은 빼고). */
    private Corpse pick() {
        Corpse best = null;
        double bd = PICK_R * PICK_R;
        for (Corpse c : game.mobs.corpses()) {
            if (c.gone() || CLAIMED.contains(c) || c.sp == sp) continue;
            double dx = c.pos.getX() - pos.getX(), dz = c.pos.getZ() - pos.getZ(), d = dx * dx + dz * dz;
            if (d >= bd || game.mobs.nearestPlayer(c.pos, MEAL_NEAR) == null || burning(c.pos)) continue;
            bd = d;
            best = c;
        }
        return best;
    }

    private boolean burning(Vector at) {
        return game.shop.inFire(at);
    }

    private void dropMeal() {
        if (meal != null) CLAIMED.remove(meal);
        meal = null;
    }

    /** 사체에 내려앉아 뿌리내림. */
    private void root() {
        Vector m = meal.pos.clone();
        game.mobs.eat(meal);
        dropMeal();
        pos.setX(m.getX()).setZ(m.getZ());
        hold();
        phase = Phase.GROW;
        phaseAt = age;
        state("grow");
        hover = SHROOM_HOVER - 0.6;
        radius = 0.5;
        noPush = true;
        body.scale(0.45f, 1);
        sound("root", Sound.BLOCK_ROOTED_DIRT_PLACE, 0.8f, 16);
        game.shotFx.bloodHit(new Location(game.world, m.getX(), floor() + 0.3, m.getZ()), new Vector(0, 1, 0), false, ShotFx.BLOOD_PINK);
    }

    private void grow() {
        hold();
        int t = age - phaseAt;
        hover = SHROOM_HOVER - 0.6 + 0.6 * Math.min(1.0, (double) t / GROW);
        if (t == 2) body.scale(1f, GROW - 2);
        if (t >= GROW / 2 && !"".equals(state)) state("");
        if (t < GROW) return;
        phase = Phase.SHROOM;
        phaseAt = age;
        hover = SHROOM_HOVER;
        maxHp = hp = SHROOM_HP * unit;
        radius = 0.7;
        cloudAt = age + 20;
        spreadAt = age + SPREAD_CD;
        smashAt = age;
    }

    // ------------------------------------------------------------------ 버섯

    private void shroom() {
        hold();
        Player p = nearest(400);
        if (p != target) mark(p);
        if (p != null) {
            face(chest(p));
            pitch = 0;
        }
        tickClouds();
        // 시듦: 14블록 안에 사람이 30초 없으면
        if (game.mobs.nearestPlayer(pos, WITHER_R) != null) aloneSince = -1;
        else if (aloneSince < 0) aloneSince = age;
        else if (age - aloneSince >= WITHER) {
            noReward = true;
            reward = 0;
            die(new Damage(hp, DamageType.OTHER, null, pos.clone(), null, "wither", 0));
            return;
        }
        if (p == null) return;
        double flat = Math.hypot(p.getLocation().getX() - pos.getX(), p.getLocation().getZ() - pos.getZ());
        if (flat <= SMASH_R && age >= smashAt && standing(p)) {
            smashAt = age + cd(SMASH_CD);
            hit(p, SMASH, "cause_g120");
            body.squash(1.25f, 0.75f, 1.25f, 2);
            body.pose(3);
            sound("puff", Sound.ENTITY_SLIME_ATTACK, 0.6f, 20);
        } else if (age >= cloudAt && flat <= CLOUD_RANGE && standing(p)) {
            cloudAt = age + cd(CLOUD_CD);
            shoot(p);
        }
        if (age >= spreadAt) {
            spreadAt = age + SPREAD_CD;
            spread();
        }
    }

    /** 갓을 오므렸다 펴며 포자 구름 하나를 사람 자리로. */
    private void shoot(Player p) {
        if (clouds.size() >= 2) return;
        Vector from = new Vector(pos.getX(), pos.getY() + 0.9, pos.getZ());
        Vector to = p.getLocation().toVector().add(new Vector(0, 1.0, 0));
        double len = to.distance(from);
        int fly = (int) Math.max(4, Math.min(59, Math.round(len / CLOUD_SPEED)));
        long now = now();
        clouds.add(new Cloud(to, now + fly, now + fly + CLOUD_STAY));
        sound("puff", Sound.ENTITY_PANDA_SNEEZE, 0.7f, 20);
        body.squash(0.9f, 1.15f, 0.9f, 3);
        ItemDisplay d = game.shotFx.spawn(from.toLocation(game.world), "fx_quad", 0, 0, ShotFx.scale(0.5f), Display.Billboard.FIXED, false,
            fly + CLOUD_STAY + 2, ShotFx.NORMAL);
        if (d == null) return;
        d.setItemStack(Body.item("gabe/g120_cloud", "", new LookState(), game.worldTime()));
        d.setBrightness(new Display.Brightness(12, 12));
        game.shotFx.grow(d, ShotFx.scale(1.25f), fly);
        game.shotFx.drift(d, to.toLocation(game.world), fly);
    }

    /** 내려앉은 구름: 0.5초마다 반경 2 안 사람에게 4 와 기침. */
    private void tickClouds() {
        long now = now();
        for (Iterator<Cloud> it = clouds.iterator(); it.hasNext();) {
            Cloud c = it.next();
            if (now >= c.until) {
                it.remove();
                continue;
            }
            if (now < c.nextTick) continue;
            c.nextTick = now + CLOUD_TICK;
            for (Player p : game.mobs.standing()) {
                Location l = p.getLocation();
                if (Math.hypot(l.getX() - c.at.getX(), l.getZ() - c.at.getZ()) > CLOUD_R || Math.abs(l.getY() + 1 - c.at.getY()) > 2.5) continue;
                hit(p, CLOUD_DMG, "cause_g120");
                Guns.kick(p, (game.random.nextFloat() - 0.5f) * 6f, -1.5f - game.random.nextFloat() * 2f);
            }
        }
    }

    /** 곁의 사체로 새 포자 둘(보상 0, 예산 안). */
    private void spread() {
        int n = 0;
        for (Corpse c : game.mobs.corpses()) {
            if (n >= 2) break;
            if (c.gone() || CLAIMED.contains(c) || c.sp == sp || c.pos.distanceSquared(pos) > PICK_R * PICK_R) continue;
            Gabe s = child(sp, new Vector(pos.getX(), floor() + SEED_HOVER + 0.6, pos.getZ()));
            if (s == null) return;
            n++;
        }
        if (n > 0) sound("puff", Sound.ENTITY_PANDA_SNEEZE, 0.9f, 20);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        if (phase == Phase.SEED) return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.32});
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY() - 0.35, pos.getZ(), 0.38});
        out.add(new double[] {pos.getX(), pos.getY() + 0.72, pos.getZ(), phase == Phase.GROW ? 0.5 : 0.85});
        return out;
    }

    @Override
    public Vector head() {
        return phase == Phase.SEED ? pos : new Vector(pos.getX(), pos.getY() + 0.72, pos.getZ());
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (phase == Phase.SHROOM && d.at() != null && d.type() != DamageType.EXPLOSION && d.type() != DamageType.FIRE) {
            double cap = d.at().getY() - (pos.getY() + 0.72), stalk = d.at().getY() - (pos.getY() - 0.35);
            if (Math.abs(cap) < Math.abs(stalk)) return super.damage(d.times(0.5));
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        dropMeal();
        clouds.clear();
        super.die(d);
    }

    @Override
    public void remove() {
        dropMeal();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + (meal != null ? " meal" : "") + " clouds=" + clouds.size();
    }
}
