package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
import kr.gave.game.Shop;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G191 포도송이 게이브(4막 32라운드, 튼튼이 + 목표 압박). A자 막대기 받침대 위 꼭지에 검보라 얼굴 알 서른이 송이로 매달려(부품, 걸음에 흔들림) 한가운데의
 * 우주선(트럭)으로 곧게 걷는다(0.11). 6블록 안 사람에게 걸으면서 알을 떼어 던진다(포물선, 12 + 2초 독 초당 3). 체력 10% 아래면 알이 모두 부풀어 1초 뒤
 * 스스로 터진다. 죽으면 그 자리에서 터져(반경 5 안 20) 즙 못이 된다: 막이 끝날 때까지 안 마름(막 내내 셋, 넷째에 가장 오래된 것이 마름. 우주선 12블록 안의
 * 못은 그 웨이브가 끝나면 마름, 불로 죽이면 절반 크기, 테두리 갉는 게이브가 갉은 땅에는 안 생김). 못 안 사람은 0.5초마다 5 와 30% 느려짐(판 단위 틱 G191).
 * 트럭 곁에 닿으면 주저앉아 5초 뒤 터진다: 그 웨이브 동안 트럭 둘레 반쪽이 못(반경 6)이 되고 터지는 피해의 세 배가 트럭에.
 * 다리(부위 체력 120씩)를 꺾으면 주저앉아 8초 멈춘다(죽일 자리를 고르는 법).
 *
 * <p>팩 계약(tools/gabe/sp/g191.py): gabe/g191(shrink 0.5, 상태 "", "sit", 부위 cut_l, cut_r), gabe/g191_bunch(shrink 0.4, "", "swell"),
 * gabe/g191_egg, gabe/g191_pool(바닥 판 3 x 3, "" 끓음, "drop" 방울 자국). 소리 drip(24), throw(24), splat(16), burst(48).
 * 글귀 cause_g191, cause_g191_burst, cause_g191_pool.
 *
 * <p>도감과 다른 점: 알 던지는 거리 6(도감 5, 송이가 커서 닿는 데까지). 독은 던진 개체가 살아 있는 동안만 셈(죽으면 그 독은 멎음).
 */
public final class G191 extends Gabe {

    static final String KEY = "G191";
    static final double HOVER = 1.5, SPEED = 0.11, APEX_Y = 1.9, SIT_DROP = 1.1, LEG_X = 1.3;
    static final double THROW_R = 6, EGG_DMG = 12, POISON_DMG = 3, BURST_R = 5, BURST_DMG = 20, POOL_R = 5, TRUCK_POOL_R = 6;
    static final double POOL_DMG = 5, POOL_SLOW = 0.3, SHIP_R = 12, LEG_HP = 120, SWELL_AT = 0.1;
    static final int THROW_CD = 40, FLIGHT = 12, POISON_T = 40, SIT_T = 160, TRUCK_T = 100, SWELL_T = 20, MAX_POOLS = 3;

    enum Phase { WALK, SIT, TRUCK, SWELL }

    private Phase phase = Phase.WALK;
    private int phaseAt, throwAt, flightAt = -1, throwsN;
    private Body.Part bunch, egg;
    private final Vector eggFrom = new Vector(), eggTo = new Vector();
    private final double[] legHp = {LEG_HP, LEG_HP};
    private final Map<UUID, Long> poison = new HashMap<>();
    private Player lastBy;
    private final List<double[]> sph = new ArrayList<>(5);
    private int sphAge = -1, posedAt;

    public G191(Game game, Species sp) {
        super(game, sp);
        radius = 1.0;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true;
        noBite = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g191", 3.4f, 4f);
        bunch = body.attach("gabe/g191_bunch");
        if (bunch != null) bunch.local.translation(0f, (float) APEX_Y, 0f);
        egg = body.attach("gabe/g191_egg");
        if (egg != null) {
            egg.follow = false;
            egg.local.scaling(0.001f);
        }
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Pools.of(game);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private Vector goal() {
        Truck t = Truck.get();
        return t != null && t.alive() ? Truck.center() : Shop.counterCenter();
    }

    private double goalDist() {
        Truck t = Truck.get();
        if (t != null && t.alive()) return Truck.dist(pos.getX(), pos.getZ());
        Vector c = Shop.counterCenter();
        return Math.max(0, Math.hypot(c.getX() - pos.getX(), c.getZ() - pos.getZ()) - 1.5);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        Pools.of(game);
        Player near = nearest(400);
        mark(near != null && dist(near) < 30 ? near : null);
        if (phase != Phase.SWELL && hp < maxHp * SWELL_AT && hp > 0) {
            phase(Phase.SWELL);
            if (bunch != null) bunch.state("swell");
            body.squash(1.12f, 1.05f, 1.12f, 6);
            sound("burst", Sound.ENTITY_SLIME_SQUISH, 0.5f, 48);
        }
        switch (phase) {
            case WALK -> walk(near);
            case SIT -> sit();
            case TRUCK -> truckSit();
            case SWELL -> swell();
        }
        flight();
        poisonTick();
        swing();
    }

    private void walk(Player near) {
        Vector g = goal();
        seek(g.clone().setY(pos.getY()), speed());
        face(g.clone().setY(pos.getY()));
        if (goalDist() < 1.6) {
            phase(Phase.TRUCK);
            hold();
            sound("burst", Sound.ENTITY_SLIME_SQUISH, 0.6f, 48);
            return;
        }
        if (age % 30 == 0) {
            sound("drip", Sound.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 0.6f, 24);
            drop();
        }
        if (age >= throwAt && flightAt < 0) {
            Player q = nearest(THROW_R + 2);
            if (q != null && dist(q) <= THROW_R + 1.2) throwEgg(q);
        }
    }

    private void sit() {
        hold();
        if (inPhase() < SIT_T) return;
        legHp[0] = legHp[1] = LEG_HP * 0.5;
        body.look.on.remove("cut_l");
        body.look.on.remove("cut_r");
        state("");
        phase(Phase.WALK);
    }

    private void truckSit() {
        hold();
        if (inPhase() % 10 == 0) body.squash(1.05f + inPhase() / (float) TRUCK_T * 0.12f, 0.95f, 1.05f, 4);
        if (inPhase() == TRUCK_T - SWELL_T && bunch != null) bunch.state("swell");
        if (inPhase() >= TRUCK_T) die(new Damage(Math.max(1, hp), DamageType.OTHER, null, pos.clone(), null, "g191", 0));
    }

    private void swell() {
        hold();
        if (inPhase() >= SWELL_T) die(new Damage(Math.max(1, hp), DamageType.OTHER, lastBy, pos.clone(), null, "g191", 0));
    }

    /** 송이 흔들림(꼭지를 축으로, 걸음 박자). */
    private void swing() {
        if (bunch == null || age - posedAt < 4) return;
        posedAt = age;
        double moving = Math.min(1, Math.hypot(vel.getX(), vel.getZ()) / 0.08);
        float a = (float) Math.toRadians(7 * moving * Math.sin(age * 0.32));
        float y = (float) (APEX_Y - (phase == Phase.SIT ? SIT_DROP : 0));
        bunch.pose(new Matrix4f().translation(0f, y, 0f).rotateX(a).rotateZ(a * 0.4f), 4);
    }

    private void drop() {
        double fy = game.mobs.flow.floor(pos.getX(), pos.getZ());
        Location l = new Location(game.world, pos.getX() + (game.random.nextDouble() - 0.5), fy + 0.06, pos.getZ() + (game.random.nextDouble() - 0.5));
        float s = (float) (0.3 + game.random.nextDouble() * 0.15);
        ItemDisplay d = game.shotFx.spawn(l, "fx_flat", 0, 0, new Matrix4f().scale(s, 1, s).rotateY((float) Math.PI), Display.Billboard.FIXED, false, 120,
            ShotFx.EXTRA);
        if (d == null) return;
        LookState ls = new LookState();
        ls.freeze = true;
        d.setItemStack(Body.item("gabe/g191_pool", "drop", ls, game.worldTime()));
        d.setBrightness(Body.LIGHT);
    }

    // ------------------------------------------------------------------ 알 던지기

    private void throwEgg(Player q) {
        throwAt = age + cd(THROW_CD);
        flightAt = age;
        throwsN++;
        eggFrom.copy(pos).add(new Vector(0, APEX_Y - 0.8, 0));
        Location l = q.getLocation();
        Vector v = q.getVelocity();
        eggTo.setX(l.getX() + v.getX() * FLIGHT * 0.6).setY(game.mobs.flow.floor(l.getX(), l.getZ()) + 0.3).setZ(l.getZ() + v.getZ() * FLIGHT * 0.6);
        sound("throw", Sound.ENTITY_SNOWBALL_THROW, 0.6f, 24);
        if (bunch != null) body.squash(0.96f, 1.06f, 0.96f, 3);
    }

    private void flight() {
        if (flightAt < 0 || egg == null) return;
        int t = age - flightAt;
        if (t > FLIGHT) {
            flightAt = -1;
            egg.pose(new Matrix4f().scaling(0.001f), 0);
            landEgg();
            return;
        }
        if (t % 2 != 0 && t != FLIGHT) return;
        double f = t / (double) FLIGHT;
        Vector p = eggFrom.clone().multiply(1 - f).add(eggTo.clone().multiply(f));
        p.setY(p.getY() + 3.2 * Math.sin(Math.PI * f));
        egg.pose(new Matrix4f().translation((float) (p.getX() - pos.getX()), (float) (p.getY() - pos.getY()), (float) (p.getZ() - pos.getZ()))
            .rotateY((float) (age * 0.4)), t == 0 ? 0 : 2);
    }

    private void landEgg() {
        G129Kit.soundAt(this, eggTo, "splat", Sound.ENTITY_SLIME_SQUISH, 1f, 16);
        G129Kit.ring(this, eggTo, 1.2, 10);
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (Math.hypot(l.getX() - eggTo.getX(), l.getZ() - eggTo.getZ()) > 1.4 || Math.abs(l.getY() - eggTo.getY()) > 2.5) continue;
            hit(q, EGG_DMG, "cause_g191");
            poison.put(q.getUniqueId(), now() + POISON_T);
        }
    }

    private void poisonTick() {
        if (poison.isEmpty() || age % 20 != 0) return;
        long now = now();
        for (Iterator<Map.Entry<UUID, Long>> it = poison.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Long> e = it.next();
            Player q = org.bukkit.Bukkit.getPlayer(e.getKey());
            if (q == null || e.getValue() < now || !standing(q)) {
                it.remove();
                continue;
            }
            hit(q, POISON_DMG, "cause_g191");
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    private Vector legMid(int i) {
        double side = i == 0 ? 1 : -1;
        return G129Kit.at(this, side * LEG_X * 0.55, (APEX_Y - HOVER) * 0.5 - 0.2, 0);
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        double ay = phase == Phase.SIT ? SIT_DROP : 0;
        sph.add(new double[] {pos.getX(), pos.getY() + APEX_Y - ay - 1.4, pos.getZ(), 1.15});   // 0 송이
        sph.add(new double[] {pos.getX(), pos.getY() + APEX_Y - ay, pos.getZ(), 0.45});         // 1 꼭지
        if (phase != Phase.SIT) {
            for (int i = 0; i < 2; i++) {
                Vector m = legMid(i);
                sph.add(new double[] {m.getX(), m.getY(), m.getZ(), 0.4});                       // 2, 3 다리
            }
        }
        sphAge = age;
        return sph;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.by() != null) lastBy = d.by();
        if (d.at() != null && phase == Phase.WALK) {
            List<double[]> s = spheres();
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < s.size(); i++) {
                double[] c = s.get(i);
                double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best >= 2) {
                int k = best - 2;
                legHp[k] -= d.amount();
                if (legHp[k] <= 0) {
                    body.look.on.add(k == 0 ? "cut_l" : "cut_r");
                    state("sit");
                    phase(Phase.SIT);
                    hold();
                    sound("splat", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.7f, 24);
                    body.squash(1.1f, 0.8f, 1.1f, 4);
                }
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        burst(d != null && d.type() == DamageType.FIRE);
        super.die(d);
    }

    private void burst(boolean fire) {
        double fy = game.mobs.flow.floor(pos.getX(), pos.getZ());
        Vector at = new Vector(pos.getX(), fy, pos.getZ());
        sound("burst", Sound.ENTITY_SLIME_DEATH, 0.5f, 48);
        G129Kit.wave(this, at, 1.0, BURST_R, 8);
        game.shotFx.bloodHit(new Location(game.world, pos.getX(), fy + 1.0, pos.getZ()), new Vector(0, 1, 0), true);
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (Math.hypot(l.getX() - at.getX(), l.getZ() - at.getZ()) > BURST_R || Math.abs(l.getY() - fy) > 3) continue;
            hit(q, BURST_DMG, "cause_g191_burst");
        }
        Truck t = Truck.get();
        boolean atTruck = t != null && Truck.dist(pos.getX(), pos.getZ()) <= 3;
        if (atTruck) {
            double k = game.director.dmgWave() * Trip.Tune.TRUCK_DMG / Math.pow(Math.max(1, game.trip.people()), Trip.Tune.TRUCK_POW);
            t.hit(BURST_DMG * Trip.Tune.TRUCK_BLAST * k, this, "cause_g191_burst");
            // 트럭 둘레 반쪽이 못: 트럭 가운데에서 이쪽으로 3블록 밀어 반경 6
            Vector c = Truck.center();
            Vector dir = at.clone().subtract(c).setY(0);
            if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
            dir.normalize();
            Vector pc = c.clone().add(dir.multiply(3.0));
            pc.setY(game.mobs.flow.floor(pc.getX(), pc.getZ()));
            Pools.of(game).add(this, pc, TRUCK_POOL_R, true);
        } else {
            Pools.of(game).add(this, at, fire ? POOL_R * 0.5 : POOL_R, false);
        }
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " legs=" + Math.round(legHp[0]) + "/" + Math.round(legHp[1]) + " throws=" + throwsN + " goal="
            + String.format("%.1f", goalDist()) + " pools=" + Pools.of(game).pools.size();
    }

    // ------------------------------------------------------------------ 즙 못(판 단위 틱 FIELDTICK)

    /** 막 내내 남는 즙 못. 못 디스플레이는 이 갈고리가 세우고 치운다(막이 바뀌거나 판이 바뀌면 모두). */
    static final class Pools implements Mobs.Field {
        final Game game;
        final List<Pool> pools = new ArrayList<>();
        int act = -1;

        static final class Pool {
            Vector at;
            double r;
            boolean wave;
            ItemDisplay d;
            Gabe src;
        }

        private Pools(Game game) {
            this.game = game;
        }

        static Pools of(Game game) {
            Mobs.Field f = game.mobs.field(KEY);
            if (f instanceof Pools p) return p;
            Pools p = new Pools(game);
            game.mobs.field(KEY, p);
            return p;
        }

        /** 이 자리에 못 하나(wave = 그 웨이브 끝에 마름). 갉힌 땅이면 안 생김. */
        void add(Gabe src, Vector at, double r, boolean wave) {
            if (G187.eaten(game, at.getX(), at.getZ())) return;
            Vector c = Truck.live() ? Truck.center() : Shop.counterCenter();
            boolean near = Math.hypot(at.getX() - c.getX(), at.getZ() - c.getZ()) < SHIP_R;
            Pool p = new Pool();
            p.at = at.clone();
            p.r = r;
            p.wave = wave || near;
            p.src = src;
            p.d = spawn(p);
            pools.add(p);
            act = Trip.actOf(Math.max(1, game.wave));
            int lasting = 0;
            for (Pool q : pools) if (!q.wave) lasting++;
            while (lasting > MAX_POOLS) {
                for (Iterator<Pool> it = pools.iterator(); it.hasNext();) {
                    Pool q = it.next();
                    if (q.wave) continue;
                    gone(q);
                    it.remove();
                    lasting--;
                    break;
                }
            }
        }

        private ItemDisplay spawn(Pool p) {
            double y = game.mobs.flow.floor(p.at.getX(), p.at.getZ()) + 0.065;
            float s = (float) (p.r / 1.5);
            LookState ls = new LookState();
            return game.world.spawn(new Location(game.world, p.at.getX(), y, p.at.getZ()), ItemDisplay.class, x -> {
                x.setPersistent(false);
                x.setItemStack(Body.item("gabe/g191_pool", "", ls, game.worldTime()));
                x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                x.setBillboard(Display.Billboard.FIXED);
                x.setBrightness(Body.LIGHT);
                x.setViewRange(3f);
                x.setDisplayWidth((float) (p.r * 2.2));
                x.setDisplayHeight(1f);
                x.setTransformationMatrix(new Matrix4f().scale(s, 1, s).rotateY((float) Math.PI));
            });
        }

        private void gone(Pool p) {
            if (p.d != null && p.d.isValid()) p.d.remove();
        }

        void clear() {
            for (Pool p : pools) gone(p);
            pools.clear();
        }

        @Override
        public boolean tick(long now) {
            if (pools.isEmpty()) return true;
            if (game.state == Game.State.LOBBY || game.state == Game.State.OVER || Trip.actOf(Math.max(1, game.wave)) != act) {
                clear();
                return true;
            }
            if (game.state != Game.State.WAVE || now % 10 != 0) return true;
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                for (Pool p : pools) {
                    if (Math.hypot(l.getX() - p.at.getX(), l.getZ() - p.at.getZ()) > p.r || Math.abs(l.getY() - p.at.getY()) > 2) continue;
                    p.src.hit(q, POOL_DMG, "cause_g191_pool");
                    game.players.slow(q, POOL_SLOW, 14, "g191pool");
                    break;
                }
            }
            return true;
        }

        @Override
        public void waveEnd(int wave) {
            for (Iterator<Pool> it = pools.iterator(); it.hasNext();) {
                Pool p = it.next();
                if (!p.wave) continue;
                gone(p);
                it.remove();
            }
        }

        @Override
        public void reset() {
            clear();
        }
    }

    /** 바깥에서 묻기: 이 자리가 즙 못 안인가. */
    static boolean inPool(Game game, double x, double z) {
        Mobs.Field f = game.mobs.field(KEY);
        if (!(f instanceof Pools p)) return false;
        for (Pools.Pool q : p.pools) if (Math.hypot(x - q.at.getX(), z - q.at.getZ()) <= q.r) return true;
        return false;
    }

    static double cx() {
        return Plaza.CX + 0.5;
    }
}
