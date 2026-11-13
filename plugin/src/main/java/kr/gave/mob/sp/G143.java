package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;

/**
 * G143 갈아 먹는 톱니 게이브(4막, 튼튼이, 보상). 맞물려 안쪽으로 도는 얼굴 톱니바퀴 둘이 0.13 블록/틱으로 다가오며, 길 위의 값싼 게이브(cost 2 이하,
 * 거대 종과 보스 빼고)를 틈으로 빨아들여 갈아 먹는다. 다섯 마리마다 지름이 1 블록씩(3 → 9) 커지고 단계마다 체력 +400(감독 배율)과 그만큼 회복,
 * 먹은 마리마다 보상 +2원. 지름 9 가 되면 8초 뒤 터져 반경 8 안 30, 먹은 것 절반을 되살려 쏟고(보상 0) 지름 3 으로 돌아간다(더한 보상도 사라짐).
 *
 * <ul>
 * <li>빨아들이기: 앞 5 블록(x 배율) 부채꼴 안 사람이 틈 쪽으로 끌림(초당 2 블록, 반대로 걸으면 버팀).</li>
 * <li>틈에 닿으면 갈림: 붙잡아(연타 5) 0.3초마다 9, 1.5초 뒤 앞으로 뱉음.</li>
 * <li>구르는 바퀴에 치이면 지름 x 4(사람마다 1초에 한 번).</li>
 * <li>약점 굴대(바퀴 뒤에 가로지름, 뒤나 옆에서 보임): 맞으면 x1.5, 굴대 체력(최대 체력의 30%)을 다 깎으면 굴대가 부러져 바퀴가 벌어지고
 *   3초 헛돎(못 먹고 못 끔). 그 뒤 굴대는 다시 붙는다.</li>
 * <li>화염병 불이 틈에 들면 먹은 것이 타서 한 단계 줄어든다(3초에 한 번).</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g143.py): 닻 gabe/g143(shrink 0.6, 지름 3 크기, 상태 "", "broke"), 바퀴 gabe/g143_wheel(shrink 0.62, 상태 "",
 * f10, f12, f14, f16, f18 = 테 얼굴 수) 둘. 크기 단계는 body.scale(지름 / 3). 소리 grind(48), gulp(24), suck(32), snap(32), swell(64), burst(64).
 * 글귀 cause_g143, cause_g143_roll, cause_g143_burst.
 *
 * <p>도감과 다른 점: 판 규칙 표 EATERS 에는 못 넣었다(감독 파일, 요청). 바퀴는 앞을 보는 원판 둘이 좌우로 맞물린 꼴(구르는 바퀴가 아니라 얼굴 분쇄기).
 */
public final class G143 extends Gabe {

    static final double SPEED = 0.13, RG = 1.5, HUB_X = 1.42, AXLE_Z = -0.75;
    /** 바퀴 테 밖으로 나온 턱 이빨 길이(지름 3 크기): 이빨 끝이 바닥에 닿게 띄움. */
    static final double TOOTH = 0.45;
    static final int MAX_STAGE = 6, PER_STAGE = 5;
    static final double STAGE_HP = 400, AXLE_FRAC = 0.3, AXLE_MUL = 1.5;
    /** 먹기: 쿨다운, 틈 반경(x 배율). */
    static final int EAT_CD = 6;
    static final double EAT_R = 1.5;
    /** 빨아들이기, 갈기, 치기. */
    static final double SUCK_R = 5, SUCK_COS = 0.5, SUCK_V = 0.1, GRIND_R = 0.9, GRIND_DMG = 9, ROLL_PER_D = 4;
    static final int GRIND_EVERY = 6, GRIND_LEN = 30, ROLL_CD = 20, GRIND_CD = 40;
    /** 터짐: 다 자란 뒤 틱, 반경, 피해. */
    static final int SWELL_T = 160;
    static final double BURST_R = 8, BURST_DMG = 30;
    static final int SPLAY_T = 60, FIRE_CD = 60;
    static final String[] WHEEL_STATES = {"", "", "f10", "f12", "f14", "f16", "f18"};

    private int stage, eaten, eatenSince, eatAt, rollAt, fireAt, swellAt = -1, splayUntil = -1, grindStart, grindAt, grindCd, grindSnd;
    private double baseMaxHp = -1, axleHp = -1, ratio = 1, k = 1, kShown = 1;
    private int baseReward;
    private final List<Species> stomach = new ArrayList<>();
    private Body.Part wl, wr;
    private float spin;
    private Player grinding;
    private final Map<Player, Integer> rolled = new IdentityHashMap<>();
    private final List<double[]> sph = new ArrayList<>(24);
    private int sphAge = -1;

    public G143(Game game, Species sp) {
        super(game, sp);
        radius = 1.6;
        hover = (RG + TOOTH) * 1;
        baseSpeed = SPEED;
        big = true;
        noPush = true;
        noMutate = true;
        noBite = true;
        ragKick = 0.4;
        hpPow = Director.Tune.HP_POW_BIG;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g143", 7f, 4.2f);
        body.viewRange(3f);
        wl = body.attach("gabe/g143_wheel");
        wr = body.attach("gabe/g143_wheel");
        poseWheels(0);
    }

    @Override
    protected void spawned() {
        baseReward = reward;
    }

    private double kTarget() {
        return (3 + stage) / 3.0;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (baseMaxHp < 0) {
            baseMaxHp = maxHp;
            ratio = Math.max(0.3, maxHp / Math.max(1, sp.hp));
            axleHp = maxHp * AXLE_FRAC;
        }
        // 크기: 배율을 천천히(20틱) 따라가고 땅 몹 높이도 같이
        double kt = kTarget();
        if (Math.abs(kShown - kt) > 1e-3) {
            kShown += Math.signum(kt - kShown) * Math.min(Math.abs(kt - kShown), 1 / 60.0);
            body.scale((float) kShown, 1);
            if (Math.abs(kShown - kt) <= 1e-3) body.size((float) (7 * kShown), (float) (4.2 * kShown));
        }
        k = kShown;
        hover = (RG + TOOTH) * k;
        radius = 1.6 * k;

        boolean splayed = age < splayUntil;
        if (splayUntil >= 0 && !splayed) {
            splayUntil = -1;
            state("");
            axleHp = maxHp * AXLE_FRAC;
        }
        Player p = nearest(400);
        if (p != target) mark(p);
        if (grinding != null) grind();
        else if (swellAt >= 0) {
            hold();
            if (p != null) G141Kit.turnFlat(this, chest(p), 2f);
        } else {
            if (p != null) G141Kit.turnFlat(this, chest(p), 2.5f);
            follow(speed());
        }
        spin += (float) ((splayed ? 0.35 : 0.12) / Math.max(1, k * 0.7));
        if (age % 4 == 0) poseWheels(4);
        if (age >= grindSnd) {
            grindSnd = age + 30;
            sound("grind", Sound.BLOCK_GRINDSTONE_USE, 0.6f, 48);
        }
        if (!splayed && swellAt < 0) {
            eat();
            suck();
        }
        roll();
        fire();
        if (stage >= MAX_STAGE && swellAt < 0) {
            swellAt = age + SWELL_T;
            sound("swell", Sound.ENTITY_RAVAGER_ROAR, 0.6f, 64);
            G141Kit.warn(this, floorAt(pos), BURST_R, SWELL_T);
        }
        if (swellAt >= 0) {
            if (age % 10 == 0) body.squash(1.06f, 1.06f, 1.06f, 5);
            else if (age % 10 == 5) body.squash(1f, 1f, 1f, 5);
            if (age >= swellAt) burst();
        }
    }

    private Vector floorAt(Vector v) {
        Vector f = v.clone();
        f.setY(game.mobs.flow.floor(v.getX(), v.getZ()));
        return f;
    }

    /** 바퀴 둘: 굴대 자리로 옮기고 z 축으로 서로 반대로 돌림(벌어지면 바깥으로 기울임). */
    private void poseWheels(int ticks) {
        boolean splayed = age < splayUntil;
        float out = splayed ? 0.5f : 0f, tilt = splayed ? 0.35f : 0f;
        if (wl != null) {
            wl.pose(new Matrix4f().translate((float) (HUB_X + out), 0, 0).rotateY(tilt).rotateZ(spin), ticks);
            wl.state(WHEEL_STATES[Math.min(stage, MAX_STAGE)]);
        }
        if (wr != null) {
            wr.pose(new Matrix4f().translate((float) (-HUB_X - out), 0, 0).rotateY(-tilt).rotateZ(-spin + 0.3f), ticks);
            wr.state(WHEEL_STATES[Math.min(stage, MAX_STAGE)]);
        }
    }

    /** 틈(입) 자리: 두 바퀴가 맞물리는 앞. */
    private Vector mouth() {
        return G141Kit.at(this, 0, -0.5 * k, 0.6 * k);
    }

    private boolean edible(Gabe o) {
        if (o == this || o.dead || o.removed || o.big || o.sp == sp || o.sp.is(Role.BOSS) || o.untargetable || o.disguised) return false;
        return o.sp.cost <= 2;
    }

    private void eat() {
        if (age < eatAt) return;
        eatAt = age + EAT_CD;
        Vector m = mouth();
        Gabe o = game.mobs.nearest(m, EAT_R * k + 0.8, this::edible);
        if (o == null) return;
        devour(o);
    }

    private void devour(Gabe o) {
        Species what = o.sp;
        o.noReward = true;
        o.reward = 0;
        o.leaveCorpse = false;
        try {
            o.die(new Damage(Math.max(1, o.hp), DamageType.OTHER, null, o.pos.clone(), null, "eaten", 0));
        } catch (RuntimeException e) {
            o.remove();
        }
        eaten++;
        stomach.add(what);
        reward += 2;
        if (game.shotFx.room(ShotFx.NORMAL)) {
            Vector m = mouth();
            game.shotFx.bloodHit(new Location(game.world, m.getX(), m.getY(), m.getZ()), G141Kit.fwd(this).multiply(-1), true, ShotFx.BLOOD_PINK);
        }
        sound("gulp", Sound.ENTITY_GENERIC_EAT, 0.6f, 24);
        if (++eatenSince >= PER_STAGE && stage < MAX_STAGE) {
            eatenSince = 0;
            grow(1);
        }
    }

    private void grow(int n) {
        int was = stage;
        stage = Math.max(0, Math.min(MAX_STAGE, stage + n));
        int d = stage - was;
        if (d == 0) return;
        double add = STAGE_HP * ratio * d;
        maxHp = Math.max(baseMaxHp, maxHp + add);
        if (d > 0) hp = Math.min(maxHp, hp + add);
        else hp = Math.min(hp, maxHp);
        sphAge = -1;
        poseWheels(4);
    }

    private void suck() {
        Vector m = mouth();
        Vector f = G141Kit.fwd(this);
        double r = SUCK_R * Math.max(1, k * 0.8);
        boolean any = false;
        for (Player q : game.mobs.standing()) {
            if (game.players.grabbedBy(q) != null) continue;
            Location l = q.getLocation();
            Vector to = new Vector(m.getX() - l.getX(), 0, m.getZ() - l.getZ());
            double d = to.length();
            if (d > r + 0.8 * k || d < 1e-6) continue;
            Vector from = new Vector(l.getX() - pos.getX(), 0, l.getZ() - pos.getZ());
            if (from.lengthSquared() > 1e-6 && from.clone().normalize().dot(f) < SUCK_COS) continue;
            if (d <= GRIND_R * k + 0.6) {
                if (grinding == null && age >= grindCd && grab(q, 5)) {
                    grinding = q;
                    grindStart = age;
                    grindAt = age;
                    mark(q);
                    return;
                }
                continue;
            }
            game.players.push(q, to.multiply(SUCK_V / d));
            any = true;
        }
        if (any && age % 20 == 0) sound("suck", Sound.ITEM_ELYTRA_FLYING, 0.8f, 32);
    }

    private void grind() {
        Player q = grinding;
        hold();
        if (q == null || !q.isOnline() || game.players.grabbedBy(q) != this) {
            grinding = null;
            grindCd = age + cd(GRIND_CD);
            return;
        }
        Vector m = mouth();
        game.players.carry(q, m.clone().add(new Vector(0, 0.6, 0)));
        if (age >= grindAt) {
            grindAt = age + GRIND_EVERY;
            hit(q, GRIND_DMG, "cause_g143");
            body.squash(1.04f, 0.97f, 1.04f, 2);
        }
        if (age - grindStart >= GRIND_LEN) {
            game.players.release(q);
            knock(q, G141Kit.fwd(this), 1.1, 0.5);
            grinding = null;
            grindCd = age + cd(GRIND_CD);
        }
    }

    /** 구르는 바퀴에 치임: 바퀴 앞 띠(틈 제외) 안 사람. */
    private void roll() {
        if (age < rollAt) return;
        rollAt = age + 2;
        double dmg = (3 + stage) * ROLL_PER_D;
        Vector f = G141Kit.fwd(this), l = G141Kit.left(this);
        for (Player q : game.mobs.standing()) {
            if (q == grinding) continue;
            Location loc = q.getLocation();
            double dx = loc.getX() - pos.getX(), dz = loc.getZ() - pos.getZ();
            double fw = dx * f.getX() + dz * f.getZ(), u = dx * l.getX() + dz * l.getZ();
            if (Math.abs(u) < 0.55 * k || Math.abs(u) > (HUB_X + RG) * k + 0.3 || fw < -0.6 * k || fw > 0.55 * k + 0.6) continue;
            if (Math.abs(loc.getY() - (pos.getY() - hover)) > 2 * RG * k) continue;
            Integer last = rolled.get(q);
            if (last != null && age - last < ROLL_CD) continue;
            rolled.put(q, age);
            hit(q, dmg, "cause_g143_roll");
            knock(q, new Vector(dx, 0, dz).normalize().add(f).normalize(), 0.9, 0.45);
        }
    }

    /** 화염병 불이 틈에 들면 한 단계 줄어듦. */
    private void fire() {
        if (age < fireAt || stage == 0) return;
        if (!game.shop.inFire(mouth()) && !game.shop.inFire(floorAt(mouth()))) return;
        fireAt = age + FIRE_CD;
        grow(-1);
        eatenSince = 0;
        sound("gulp", Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 24);
    }

    /** 다 자라 터짐: 반경 8 안 30, 먹은 것 절반을 되살려 쏟고 지름 3 으로. */
    private void burst() {
        swellAt = -1;
        Vector c = floorAt(pos);
        sound("burst", Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 64);
        game.shotFx.explosion(new Location(game.world, pos.getX(), pos.getY(), pos.getZ()));
        game.horizon.shake(0.5, 12);
        for (Player q : G141Kit.within(this, c, BURST_R, 6)) {
            hit(q, BURST_DMG, "cause_g143_burst");
            Location l = q.getLocation();
            knock(q, new Vector(l.getX() - c.getX(), 0, l.getZ() - c.getZ()), 1.2, 0.6);
        }
        if (grinding != null) {
            game.players.release(grinding);
            grinding = null;
        }
        int n = stomach.size() / 2;
        for (int i = 0; i < n; i++) {
            Species s = stomach.get(game.random.nextInt(stomach.size()));
            double a = game.random.nextDouble() * Math.PI * 2, r = 2 + game.random.nextDouble() * 4;
            Vector at = c.clone().add(new Vector(Math.cos(a) * r, s.band.hover, Math.sin(a) * r));
            if (child(s, at) == null) break;
        }
        stomach.clear();
        eaten = 0;
        eatenSince = 0;
        reward = baseReward;
        grow(-MAX_STAGE);
        maxHp = baseMaxHp;
        hp = Math.min(hp, maxHp);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        // 바퀴 테 구 여덟씩 + 굴통, 굴대(바퀴 뒤) 셋
        for (int side = -1; side <= 1; side += 2) {
            double hx = side * HUB_X * k;
            Vector h = G141Kit.at(this, hx, 0, 0);
            sph.add(new double[] {h.getX(), h.getY(), h.getZ(), 0.45 * k});
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8;
                Vector w = G141Kit.at(this, hx + Math.cos(a) * 1.05 * k, Math.sin(a) * 1.05 * k, 0);
                sph.add(new double[] {w.getX(), w.getY(), w.getZ(), 0.55 * k});
            }
        }
        for (int i = -1; i <= 1; i++) {
            Vector w = G141Kit.at(this, i * 0.75 * k, 0, AXLE_Z * k - 0.1);
            sph.add(new double[] {w.getX(), w.getY(), w.getZ(), 0.42 * k});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return mouth();
    }

    /** 맞은 점이 굴대 구 표면에 가장 가까운가. */
    private boolean axleHit(Vector at) {
        if (at == null) return false;
        List<double[]> s = spheres();
        double best = 1e9;
        int bi = -1;
        for (int i = 0; i < s.size(); i++) {
            double[] q = s.get(i);
            double d = Math.sqrt((at.getX() - q[0]) * (at.getX() - q[0]) + (at.getY() - q[1]) * (at.getY() - q[1]) + (at.getZ() - q[2]) * (at.getZ() - q[2]))
                - q[3];
            if (d < best) {
                best = d;
                bi = i;
            }
        }
        return bi >= s.size() - 3;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (age >= splayUntil && axleHit(d.at())) {
            Damage dd = d.times(AXLE_MUL);
            if (axleHp < 0) axleHp = maxHp * AXLE_FRAC;
            axleHp -= dd.amount();
            if (axleHp <= 0) snap();
            return super.damage(dd);
        }
        return super.damage(d);
    }

    /** 굴대 부러짐: 바퀴가 벌어져 3초 헛돎(못 먹고 못 끔, 붙든 사람을 놓음). */
    private void snap() {
        splayUntil = age + SPLAY_T;
        state("broke");
        sound("snap", Sound.ENTITY_ITEM_BREAK, 0.6f, 32);
        if (grinding != null) {
            game.players.release(grinding);
            grinding = null;
        }
        poseWheels(3);
    }

    @Override
    public void die(Damage d) {
        if (grinding != null) {
            game.players.release(grinding);
            grinding = null;
        }
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " stage=" + stage + " eaten=" + eaten + " k=" + String.format("%.2f", k) + " axle=" + Math.round(axleHp)
            + (swellAt >= 0 ? " swell" : "") + (age < splayUntil ? " splay" : "") + (grinding != null ? " grind" : "") + " reward=" + reward;
    }
}
