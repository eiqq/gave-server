package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
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
import kr.gave.mob.Species;

/**
 * G136 총 엉키는 덩치 게이브(4막, 방해, 튼튼이, 거대). 깨진 얼굴 조각 열둘이 고리 둘을 따라 후광처럼 도는 4블록 덩치가 사람이 가장 많은 쪽으로 무겁게
 * 걸어와(0.1/틱) 3블록 안을 양 주먹으로 내려친다(22와 넘어짐 0.5초). 12블록 안 사람의 총이 엉킨다(JAM: 재장전 2배, 네 발에 한 발 헛방, 퍼짐 1.5배,
 * 10틱마다 20틱씩 다시 걸어 둠). 다가올수록 지지직 잡음이 사람마다 크게(VOLUME). 후광 조각은 맞은 탄 하나를 막고 3초 뒤 다시 찬다.
 *
 * <p>대처: 12블록 밖에서 저격과 수류탄(엉킴은 총만, 폭발과 불은 조각을 지나 몸에 닿음, BIG: 구 표면으로 잼). 조각을 깨 틈을 연다. 붙었으면 물러난다.
 *
 * <p>팩 계약(tools/gabe/sp/g136.py): 모델 gabe/g136 상태 "", "pound", 부품 gabe/g136_halo 둘(부위 s0..s5 = 깨진 조각). 소리 static(32),
 * slam(32), step(24), shard(24). 글귀 cause_g136.
 */
public final class G136 extends Gabe {

    static final double SPEED = 0.1, HOVER = 2.0, RADIUS = 1.7;
    static final double JAM_R = 12, STATIC_R = 26, POUND_AT = 3.6, POUND_R = 3.0, POUND = 22;
    static final int JAM_EVERY = 10, JAM_TICKS = 20, POUND_WIND = 16, POUND_CD = 40, DOWN = 10, SHARD_BACK = 60, STEP_EVERY = 24;
    static final double JAM_RELOAD = 2.0, JAM_MISFIRE = 0.25, JAM_SPREAD = 1.5;
    /** 후광: 고리 반지름, 높이(몸 기준), 고리 B 기울기(도), 조각 구 반지름, 고리 도는 빠르기(도/틱). */
    static final double HALO_R = 3.0, HALO_A_Y = 2.6, HALO_B_Y = 1.4, HALO_B_TILT = 32, SHARD_R = 0.5, SPIN = 4.5;
    static final int SPIN_STEP = 20;

    enum Phase { WALK, POUND }

    private Phase phase = Phase.WALK;
    private int phaseAt, jamAt, staticAt, poundReady, stepAt;
    private Body.Part haloA, haloB;
    /** 조각 12개가 다시 차는 나이(0..5 고리 A, 6..11 고리 B). */
    private final int[] shardBack = new int[12];
    private final Vector poundAt = new Vector();
    private List<double[]> cache;
    private int cacheAge = -1;

    public G136(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        ragKick = 0.4;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g136", 7.5f, 7.5f);
        haloA = body.attach("gabe/g136_halo");
        haloB = body.attach("gabe/g136_halo");
        spinPose(0);
    }

    @Override
    protected void spawned() {
        G150.arm(game);
        staticAt = game.random.nextInt(20);
    }

    /** 고리 각(도): 나이에 맞춰 돈다(조각 구 자리와 같은 셈). */
    private double ringAngle(int a) {
        return a * SPIN;
    }

    /** SPIN_STEP 틱 뒤 각으로 보간해 보냄. */
    private void spinPose(int ticks) {
        float ang = (float) Math.toRadians(ringAngle(age + ticks));
        if (haloA != null) haloA.pose(new Matrix4f().translate(0, (float) HALO_A_Y, 0).rotateY(ang), ticks);
        if (haloB != null) haloB.pose(new Matrix4f().translate(0, (float) HALO_B_Y, 0).rotateZ((float) Math.toRadians(HALO_B_TILT)).rotateY(-ang), ticks);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (age % SPIN_STEP == 0) spinPose(SPIN_STEP);
        for (int i = 0; i < 12; i++) {
            if (shardBack[i] > 0 && age >= shardBack[i]) {
                shardBack[i] = 0;
                (i < 6 ? haloA : haloB).look.on.remove("s" + (i % 6));
            }
        }
        if (age >= jamAt) jamField();
        switch (phase) {
            case WALK -> walk();
            case POUND -> pound();
        }
    }

    /** 12블록 안 사람의 총을 엉키게, 26블록 안 사람마다 가까울수록 큰 잡음. */
    private void jamField() {
        jamAt = age + JAM_EVERY;
        boolean noise = age >= staticAt;
        if (noise) staticAt = age + 20;
        for (Player q : game.mobs.standing()) {
            double d = flat(q);
            if (d <= JAM_R) {
                if (!game.guns.jammed(q)) log("엉킴 " + q.getName() + String.format(" d=%.1f", d));
                game.guns.jam(q, JAM_TICKS, JAM_RELOAD, JAM_MISFIRE, JAM_SPREAD);
            }
            if (noise && d <= STATIC_R) soundFor(q, "static", Sound.BLOCK_FIRE_EXTINGUISH, (float) Math.max(0.15, 1.0 - d / STATIC_R));
        }
    }

    private double flat(Player q) {
        Location l = q.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    /** 사람이 가장 많은 쪽: 8블록 안 동료 수가 가장 많은 서 있는 사람(같으면 가까운 사람). */
    private Player crowd() {
        Player best = null;
        int bn = -1;
        double bd = Double.MAX_VALUE;
        List<Player> st = game.mobs.standing();
        for (Player q : st) {
            Location l = q.getLocation();
            int n = 0;
            for (Player o : st) if (o != q && o.getLocation().distanceSquared(l) <= 64) n++;
            double d = flat(q);
            if (n > bn || n == bn && d < bd) {
                bn = n;
                bd = d;
                best = q;
            }
        }
        return best;
    }

    private void walk() {
        Player p = every(20) || target == null || !standing(target) ? crowd() : target;
        if (p == null) {
            follow(speed());
            return;
        }
        if (p != target) mark(p);
        if (flat(p) > 6) {
            seek(chest(p), speed());
            if (wallAhead(1.8)) follow(speed());
        } else follow(speed());
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-4) turnTo(pos.clone().add(vel), 6f);
        if (age >= stepAt && vl > 1e-3) {
            sound("step", Sound.ENTITY_RAVAGER_STEP, 0.5f, 24);
            stepAt = age + STEP_EVERY;
        }
        if (age < poundReady) return;
        for (Player q : game.mobs.standing()) {
            if (flat(q) <= POUND_AT + RADIUS * 0.4) {
                startPound(q);
                return;
            }
        }
    }

    private void startPound(Player q) {
        phase = Phase.POUND;
        phaseAt = age;
        hold();
        face(chest(q));
        state("pound");
        Vector f = toward(q);
        poundAt.copy(pos.clone().add(f.multiply(1.6)));
    }

    private void pound() {
        hold();
        int t = age - phaseAt, len = Math.max(8, cd(POUND_WIND));
        if (t < len) return;
        sound("slam", Sound.ENTITY_IRON_GOLEM_ATTACK, 0.5f, 32);
        body.squash(1.06f, 0.92f, 1.06f, 2);
        double gy = floor();
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double dx = l.getX() - poundAt.getX(), dz = l.getZ() - poundAt.getZ();
            if (dx * dx + dz * dz > POUND_R * POUND_R || l.getY() - gy > 2.5) continue;
            hit(q, POUND, "cause_g136");
            game.players.root(q, DOWN, "g136");
            log("내려침 " + q.getName() + " 체력 " + Math.round(game.players.get(q).hp));
            knock(q, new Vector(dx, 0, dz), 0.45, 0.25);
        }
        try {
            Location g = new Location(game.world, poundAt.getX(), gy + 0.12, poundAt.getZ());
            ItemDisplay ring = game.shotFx.spawn(g, "fx_flat", ShotFx.RING, 0, ShotFx.scale(1.2f), Display.Billboard.FIXED, true, 10, ShotFx.NORMAL);
            game.shotFx.grow(ring, ShotFx.scale((float) (POUND_R * 2.5)), 6);
        } catch (RuntimeException ignored) {
            // 연출은 덤
        }
        state("");
        poundReady = age + cd(POUND_CD);
        phase = Phase.WALK;
        phaseAt = age;
    }

    // ------------------------------------------------------------------ 맞음

    /** 조각 i 의 세계 자리(고리 A 는 수평, 고리 B 는 앞축으로 기움). */
    private double[] shard(int i) {
        boolean a = i < 6;
        double ang = Math.toRadians((a ? -1 : 1) * ringAngle(age) + (i % 6) * 60);
        // 부품 pose 가 고리 A 는 rotateY(+각), B 는 rotateY(-각): 모델 조각 각 a 가 a - 각, a + 각 으로 감
        // 몸 기준(앞 +Z): 고리 위 점
        double x = HALO_R * Math.cos(ang), y = 0, z = HALO_R * Math.sin(ang);
        double hy = a ? HALO_A_Y : HALO_B_Y;
        if (!a) {
            double t = Math.toRadians(HALO_B_TILT), cx = x * Math.cos(t) - y * Math.sin(t), cy = x * Math.sin(t) + y * Math.cos(t);
            x = cx;
            y = cy;
        }
        // 고리의 돌림은 몸 기준 그대로(몸 yaw 를 따름)
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
        // 모델은 Ry(PI) 로 그려지므로 몸 기준 +X 는 몸 왼쪽(fz, -fx)
        return new double[] {pos.getX() + fx * z + fz * x, pos.getY() + hy + y, pos.getZ() + fz * z - fx * x, SHARD_R};
    }

    @Override
    public List<double[]> spheres() {
        if (cacheAge == age && cache != null) return cache;
        List<double[]> out = new ArrayList<>(15);
        out.add(new double[] {pos.getX(), pos.getY() - 0.3, pos.getZ(), RADIUS});
        out.add(new double[] {pos.getX(), pos.getY() + 1.4, pos.getZ(), 1.25});
        for (int i = 0; i < 12; i++) if (shardBack[i] == 0) out.add(shard(i));
        cache = out;
        cacheAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        Vector p = d.at();
        if (p != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            double body0 = Math.min(dd(p, pos.getX(), pos.getY() - 0.3, pos.getZ()) - RADIUS, dd(p, pos.getX(), pos.getY() + 1.4, pos.getZ()) - 1.25);
            int best = -1;
            double bd = body0;
            for (int i = 0; i < 12; i++) {
                if (shardBack[i] != 0) continue;
                double[] s = shard(i);
                double v = dd(p, s[0], s[1], s[2]) - SHARD_R;
                if (v < bd) {
                    bd = v;
                    best = i;
                }
            }
            if (best >= 0) {
                shardBack[best] = age + SHARD_BACK;
                (best < 6 ? haloA : haloB).look.on.add("s" + (best % 6));
                sound("shard", Sound.BLOCK_GLASS_BREAK, 1.3f, 24);
                log("조각 막음 " + best);
                cacheAge = -1;
                game.mobs.onHurt(this, d, 0);
                return false;
            }
        }
        return super.damage(d);
    }

    private static double dd(Vector p, double x, double y, double z) {
        double a = p.getX() - x, b = p.getY() - y, c = p.getZ() - z;
        return Math.sqrt(a * a + b * b + c * c);
    }

    /** 시험과 판 뒤 분석용 기록(드문 사건만). */
    void log(String s) {
        game.plugin.getLogger().info("[G136] " + s);
    }

    @Override
    public String toString() {
        int down = 0;
        for (int v : shardBack) if (v != 0) down++;
        return super.toString() + " " + phase + " shardsDown=" + down;
    }
}
