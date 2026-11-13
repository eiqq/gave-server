package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G94 솥 다리 게이브(2막, 원거리, 목표 압박). 7 블록 장대 다리 셋 위의 살 솥이 한 다리씩 옮겨 휘청이며 사람 무리 머리 위까지 와서
 * 다리를 벌리고 선다. 쏟을 자리에 빨간 고리 2초, 솥이 앞으로 기울어 끓는 즙을 쏟는다(줄기에 바로 맞으면 20, 반경 4 웅덩이 5초: 초당 12 와
 * 느려짐 30%). 쏟은 뒤 8초 동안 다음 자리로 옮긴다. 다리에 밟히면 15. 다리 하나(체력 150)를 꺾으면 솥이 엎어져 제 즙을 뒤집어쓰고(체력 절반)
 * 둘레 게이브도 삭는다. 엎어진 솥은 남은 다리로 기어 와 문다. 솥이 기운 동안 보이는 솥 안이 약점(x2). 트럭 위에 쏟은 웅덩이는 트럭도 삭힌다.
 *
 * <p>팩 계약(tools/gabe/sp/g94.py): 모델 gabe/g94(shrink 0.18), 상태 ""(걷기 36틱 자세 6), "stand", "pour"(12틱 자세 4), "fallen",
 * 부위 eye0..eye2(사람 쪽 얼굴만 눈 뜸). 웅덩이 gabe/g94_pool(반지름 1 판). 소리 boil, pour, snap, crash. 글귀 cause_g94, cause_g94_step.
 *
 * <p>도감과 다른 점: 다리 셋을 부품 디스플레이로 나누지 않고 닻 하나의 걸음 자세로 구웠다(걸음 주기에 맞춰 발이 땅에 붙음, 디스플레이 1).
 * 웅덩이는 연출 디스플레이(ShotFx 예산)에 웅덩이 모델을 실은 것. 엎어진 뒤의 기어 오기와 물기(10)는 도감에 없어 더함(엎어진 채 서 있으면 외곽에 남음).
 */
public final class G94 extends Gabe {

    static final double HOVER = 7.0, FALLEN_HOVER = 1.3, SPEED = 0.09, CRAWL = 0.035;
    /** 쏟을 자리(솥 가운데 앞 POUR_AT)에 서는 거리, 예고, 줄기 피해 반경, 쏟기 길이, 다음까지. */
    static final double POUR_AT = 1.9, ARRIVE = 2.4, STREAM_R = 1.4, STREAM_DMG = 20;
    static final int TELL = 40, POUR_LEN = 30, MOVE = 160;
    /** 웅덩이: 반지름, 틱, 초당 피해, 느려짐. */
    static final double POOL_R = 4, POOL_DPS = 12, POOL_SLOW = 0.3;
    static final int POOL_LIFE = 100;
    /** 밟기: 디딘 발 둘레 반경, 피해. */
    static final double STEP_R = 1.2, STEP_DMG = 15;
    /** 다리 체력, 엎어질 때 제 피해 몫, 둘레 게이브 피해, 엎어진 뒤 물기. */
    static final double LEG_HP = 150, FALL_SELF = 0.5, FALL_MOB = 40, FALL_R = 5, BITE = 10, BITE_R = 3.0;
    /** 걸음(팩 g94.py 와 같게): 주기, 자세, 한 주기 몸이 가는 거리, 다리 자리, 발 반지름, 엉덩이. */
    static final int PERIOD = 36, POSES = 6;
    static final double STRIDE = 3.2, FOOT_R = 3.5, HIP_R = 1.05, HIP_Y = -0.85, L1 = 4.1, L2 = 4.1;
    static final double[] ANG = {90, 210, 330};
    static final double POT_R = 1.75;

    enum Phase { WALK, TELL, POUR, MOVE, FALLEN }

    private Phase phase = Phase.WALK;
    private int phaseAt, nextBoil, biteAt, pours, lastFrame = -1;
    private final double[] legHp = {-1, -1, -1};
    private final Vector pourAt = new Vector();
    private boolean fallen, legsSet;
    private String eye = "";
    private final List<double[]> pools = new ArrayList<>(); // x, y, z, 끝 틱, 트럭 다음 물기 틱
    private final List<ItemDisplay> poolFx = new ArrayList<>();
    private final List<double[]> sph = new ArrayList<>(12);
    private int sphAge = -1;
    /** 다리 i 의 무릎(몸 틀, 걸음 가운데 자세). */
    private static final double[][] KNEE = new double[3][];

    static {
        for (int i = 0; i < 3; i++) {
            double a = Math.toRadians(ANG[i]);
            double[] h = {HIP_R * Math.cos(a), HIP_Y, HIP_R * Math.sin(a)};
            double[] f = {FOOT_R * Math.cos(a), -HOVER, FOOT_R * Math.sin(a)};
            double[] d = {f[0] - h[0], f[1] - h[1], f[2] - h[2]};
            double n = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            double[] al = {d[0] / n, d[1] / n, d[2] / n};
            double aa = (L1 * L1 - L2 * L2 + n * n) / (2 * n), hh = Math.sqrt(Math.max(0, L1 * L1 - aa * aa));
            double[] p = {Math.cos(a), 1.2, Math.sin(a)};
            double dot = p[0] * al[0] + p[1] * al[1] + p[2] * al[2];
            p = new double[] {p[0] - al[0] * dot, p[1] - al[1] * dot, p[2] - al[2] * dot};
            double pn = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            KNEE[i] = new double[] {h[0] + al[0] * aa + p[0] / pn * hh, h[1] + al[1] * aa + p[1] / pn * hh, h[2] + al[2] * aa + p[2] / pn * hh};
        }
    }

    public G94(Game game, Species sp) {
        super(game, sp);
        radius = POT_R;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        noMutate = true;
        noPush = true;
        ragKick = 0.3;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g94", 11f, 17f);
        body.viewRange(3f);
    }

    @Override
    protected void spawned() {
        nextBoil = age + 40 + game.random.nextInt(40);
        body.look.phase = game.random.nextInt(PERIOD);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!legsSet) {
            legsSet = true;
            double k = Math.max(0.5, maxHp / Math.max(1, sp.hp));
            for (int i = 0; i < 3; i++) legHp[i] = LEG_HP * k;
        }
        poolsTick();
        Player p = nearest(400);
        if (p != null && dist(p) <= 34) mark(p);
        else mark(null);
        eyes(p);
        switch (phase) {
            case WALK -> walk(p, true);
            case MOVE -> {
                walk(p, false);
                if (inPhase() >= cd(MOVE)) phase(Phase.WALK);
            }
            case TELL -> tell(p);
            case POUR -> pour();
            case FALLEN -> crawl(p);
        }
        if (!fallen && age >= nextBoil && p != null && dist(p) < 40) {
            sound("boil", Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.6f, 40);
            nextBoil = age + 60 + game.random.nextInt(40);
        }
    }

    /** 사람 쪽 얼굴만 눈을 뜸. */
    private void eyes(Player p) {
        String want = "";
        if (p != null && !fallen) {
            Location l = p.getLocation();
            Vector f = G66Giant.fwd(this), lf = G66Giant.left(this);
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            double a = Math.toDegrees(Math.atan2(dx * f.getX() + dz * f.getZ(), dx * lf.getX() + dz * lf.getZ()));
            int best = 0;
            double bd = 999;
            for (int i = 0; i < 3; i++) {
                double dd = Math.abs(((a - ANG[i]) % 360 + 540) % 360 - 180);
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            want = "eye" + best;
        }
        if (want.equals(eye)) return;
        if (!eye.isEmpty()) body.look.on.remove(eye);
        if (!want.isEmpty()) body.look.on.add(want);
        eye = want;
    }

    /** 걷기: 무리(또는 트럭) 쪽으로. pourReady 면 닿았을 때 쏟을 준비. */
    private void walk(Player p, boolean pourReady) {
        state("");
        body.look.freeze = false;
        body.look.rate = 1.0;
        Vector goal = target(p);
        if (goal == null) {
            follow(speed());
        } else {
            double h = Math.hypot(goal.getX() - pos.getX(), goal.getZ() - pos.getZ());
            if (h > ARRIVE) {
                seek(goal.clone().setY(pos.getY()), speed());
                face(goal.clone().setY(pos.getY()));
            } else hold();
            if (pourReady && h <= ARRIVE) {
                hold();
                face(goal.clone().setY(pos.getY()));
                Vector f = G66Giant.fwd(this);
                pourAt.copy(pos.clone().add(f.multiply(POUR_AT)));
                pourAt.setY(game.mobs.flow.floor(pourAt.getX(), pourAt.getZ()));
                G66Giant.warn(this, pourAt, POOL_R, cd(TELL) + 4);
                state("stand");
                body.look.freeze = true;
                phase(Phase.TELL);
                return;
            }
        }
        footsteps();
    }

    /** 쏟을 곳: 셋에 하나는 트럭(서 있으면), 나머지는 가장 가까운 사람 곁 무리 가운데. */
    private Vector target(Player p) {
        Truck t = game.trip == null ? null : game.trip.truck();
        if (t != null && t.alive() && pours % 3 == 2) {
            Vector c = Truck.center();
            if (c.distance(pos.clone().setY(c.getY())) < 50) return c;
        }
        if (p == null) return null;
        Vector c = new Vector();
        int n = 0;
        Location pl = p.getLocation();
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (l.distanceSquared(pl) > 100) continue;
            c.add(l.toVector());
            n++;
        }
        return n == 0 ? pl.toVector() : c.multiply(1.0 / n);
    }

    /** 걸음 자세가 바뀌는 틱에 막 디딘 다리: 소리, 흙 고리, 그 발에 밟힌 사람. */
    private void footsteps() {
        int frame = frame();
        if (frame == lastFrame) return;
        lastFrame = frame;
        for (int i = 0; i < 3; i++) {
            if (frame != (2 * i + 2) % POSES) continue;
            double a = Math.toRadians(ANG[i]);
            Vector f = G66Giant.at(this, FOOT_R * Math.cos(a), -HOVER, FOOT_R * Math.sin(a) + STRIDE / 2);
            f.setY(game.mobs.flow.floor(f.getX(), f.getZ()));
            G66Giant.stomp(this, f, 48, 4.5, 0.7f);
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                if (Math.hypot(l.getX() - f.getX(), l.getZ() - f.getZ()) <= STEP_R && Math.abs(l.getY() - f.getY()) < 2) {
                    hit(q, STEP_DMG, "cause_g94_step");
                    knock(q, new Vector(l.getX() - f.getX(), 0, l.getZ() - f.getZ()), 0.4, 0.2);
                }
            }
        }
    }

    /** 지금 걸음 자세 번호(셰이더와 같은 셈: 위상 16분의 1 단위, 빠르기 배율 1). */
    private int frame() {
        long t = Math.floorMod(game.worldTime(), 24000L);
        int q = Math.floorMod(body.look.phase, PERIOD) * 16 / PERIOD;
        double m = ((t + q * PERIOD / 16.0) % PERIOD + PERIOD) % PERIOD;
        return Math.min(POSES - 1, (int) Math.floor(m * POSES / PERIOD));
    }

    /** 다리를 벌리고 서서 쏟을 자리에 빨간 고리(2초). */
    private void tell(Player p) {
        hold();
        if (inPhase() >= cd(TELL)) {
            G66Giant.gesture(this, "pour", 12, 4);
            sound("pour", Sound.ENTITY_GENERIC_SPLASH, 0.6f, 40);
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                if (Math.hypot(l.getX() - pourAt.getX(), l.getZ() - pourAt.getZ()) <= STREAM_R && Math.abs(l.getY() - pourAt.getY()) < 3)
                    hit(q, STREAM_DMG, "cause_g94");
            }
            addPool(pourAt, POOL_R);
            pours++;
            phase(Phase.POUR);
        }
    }

    private void pour() {
        hold();
        if (inPhase() % 6 == 0 && game.shotFx.room(ShotFx.EXTRA)) {
            Location l = new Location(game.world, pourAt.getX(), pourAt.getY() + 0.4, pourAt.getZ());
            ItemDisplay b = game.shotFx.spawn(l, "fx_quad", ShotFx.BLOOD, ShotFx.BLOOD_PINK, ShotFx.scale(1.4f), Display.Billboard.CENTER, false, 12,
                ShotFx.EXTRA);
            game.shotFx.grow(b, ShotFx.scale(3.2f), 10);
        }
        if (inPhase() >= POUR_LEN) {
            state("");
            phase(Phase.MOVE);
        }
    }

    // ------------------------------------------------------------------ 웅덩이

    private void addPool(Vector at, double r) {
        pools.add(new double[] {at.getX(), at.getY(), at.getZ(), now() + POOL_LIFE, now() + 10, r});
        ItemDisplay d = game.shotFx.spawn(new Location(game.world, at.getX(), at.getY() + 0.04, at.getZ()), "fx_quad", 0, 0,
            ShotFx.scale((float) r), Display.Billboard.FIXED, false, POOL_LIFE, ShotFx.KEY);
        if (d != null) {
            d.setItemStack(Body.item("gabe/g94_pool", "", new LookState(), game.worldTime()));
            d.setBrightness(Body.LIGHT);
            d.setDisplayWidth((float) (r * 2.5));
            d.setDisplayHeight(2f);
            poolFx.add(d);
        }
    }

    private void poolsTick() {
        if (pools.isEmpty()) return;
        long now = now();
        Truck t = game.trip == null ? null : game.trip.truck();
        for (Iterator<double[]> it = pools.iterator(); it.hasNext();) {
            double[] w = it.next();
            if (now >= w[3]) {
                it.remove();
                continue;
            }
            double r = w[5];
            if ((now + id) % 10 == 0) {
                for (Player q : game.mobs.standing()) {
                    Location l = q.getLocation();
                    if (Math.hypot(l.getX() - w[0], l.getZ() - w[2]) > r || Math.abs(l.getY() - w[1]) > 2) continue;
                    hit(q, POOL_DPS / 2, "cause_g94");
                    game.players.slow(q, POOL_SLOW, 20, "g94");
                }
            }
            if (t != null && t.alive() && Truck.dist(w[0], w[2]) <= r && now >= w[4]) {
                t.hit(2 * t.biteDamage(sp), this, "cause_g94");
                w[4] = now + cd(30);
            }
        }
        poolFx.removeIf(d -> !d.isValid());
    }

    // ------------------------------------------------------------------ 엎어짐

    private void fall(int leg) {
        if (fallen) return;
        fallen = true;
        sound("snap", Sound.ENTITY_ITEM_BREAK, 0.6f, 32);
        sound("crash", Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 48);
        state("fallen");
        body.look.freeze = true;
        hover = FALLEN_HOVER;
        if (!eye.isEmpty()) body.look.on.remove(eye);
        eye = "";
        // 엎어지는 쪽(꺾인 다리 쪽)으로 솥이 기울어 쏟아짐: 그 자리 둘레 웅덩이
        double a = Math.toRadians(ANG[leg]);
        Vector at = G66Giant.at(this, Math.cos(a) * 2.5, 0, Math.sin(a) * 2.5);
        at.setY(game.mobs.flow.floor(at.getX(), at.getZ()));
        addPool(at, FALL_R);
        G66Giant.ring(this, at, FALL_R, 14);
        // 제 즙을 뒤집어씀(체력 절반), 둘레 게이브도 삭음(보스 빼고)
        hp = Math.max(1, hp * (1 - FALL_SELF));
        for (Gabe g : game.mobs.near(at, FALL_R + 1, g -> g != this && !g.dead && !g.sp.is(Role.BOSS) && !g.untargetable))
            g.damage(new Damage(FALL_MOB, DamageType.OTHER, null, g.pos.clone(), null, "g94", 0));
        if (game.horizon != null) game.horizon.shake(0.25, 10);
        phase(Phase.FALLEN);
    }

    /** 엎어진 솥: 남은 다리로 기어 와 문다(멀리서 멈추지 않게 반경 없이 찾음). */
    private void crawl(Player p) {
        if (p == null) {
            follow(CRAWL * game.director.speedMul());
            return;
        }
        G66Giant.faceFlat(this, chest(p));
        seek(p.getLocation().toVector().setY(pos.getY()), CRAWL * game.director.speedMul());
        if (age >= biteAt && dist(p) <= BITE_R) {
            hit(p, BITE, "cause_g94");
            biteAt = age + cd(30);
            body.squash(1.1f, 0.9f, 1.1f, 3);
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(0, 0, 0, POT_R);
        if (!fallen) {
            for (int i = 0; i < 3; i++) {
                double a = Math.toRadians(ANG[i]);
                double[] k = KNEE[i];
                double hx = HIP_R * Math.cos(a), hz = HIP_R * Math.sin(a), fx = FOOT_R * Math.cos(a), fz = FOOT_R * Math.sin(a);
                add(k[0], k[1], k[2], 0.6);
                add((hx + k[0]) / 2, (HIP_Y + k[1]) / 2, (hz + k[2]) / 2, 0.45);
                add((fx + k[0]) / 2, (-HOVER + k[1]) / 2, (fz + k[2]) / 2, 0.45);
            }
            if (phase == Phase.POUR || phase == Phase.TELL) add(0, 0.95, 0.95, 0.85);
        }
        sphAge = age;
        return sph;
    }

    private void add(double x, double y, double z, double r) {
        Vector w = G66Giant.at(this, x, y, z);
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (fallen || d.at() == null) return super.damage(d);
        List<double[]> s = spheres();
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < s.size(); i++) {
            double[] c = s.get(i);
            double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        if (best == 10) return super.damage(d.times(2.0)); // 기운 솥 안
        if (best >= 1 && best <= 9) {
            int leg = (best - 1) / 3;
            legHp[leg] -= d.amount();
            boolean ok = super.damage(d);
            if (!dead && legHp[leg] <= 0) fall(leg);
            return ok;
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        pools.clear();
        super.die(d);
    }

    @Override
    public void remove() {
        pools.clear();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " legs=" + Math.round(legHp[0]) + "/" + Math.round(legHp[1]) + "/" + Math.round(legHp[2])
            + " pours=" + pours + " pools=" + pools.size();
    }
}
