package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;

/**
 * G166 까치발 게이브(방패, 지원). 땅에 까치발로 선 막대기 발 넷만 보이는 투명한 벽(폭 3.6, 높이 3). 위를 쏘면 튕기고 발을 쏴야 무너진다.
 *
 * <p>몸: 닻이 발 넷(gabe/g166, 발마다 부위 f0..f3 이 켜지면 꺾여 사라짐), 벽은 부품 gabe/g166_wall(상태 "" 성긴 점묘, "hit" 눌린 얼굴 자국, "touch" 눈 뜬
 * 자국, "fall" 불투명 얼굴 판). 흐름장을 따라 가장 가까운 사람 쪽으로 종종걸음(0.15), 벽 면이 늘 그 사람을 본다(가로로 서서 다가옴).
 *
 * <p>맞음: 벽 면을 납작한 구 줄로 spheres() 에 올려 뒤 게이브보다 먼저 탄을 받는다(G19 방식). 맞은 점이 바닥 + 0.5 위면 튕김(피해 없음, 얼굴 자국,
 * 맞은 점 2블록 안 사람에게 3 피해), 아래면 가장 가까운 산 발에(발마다 체력 22 x 감독 체력 배율). 폭발과 불은 땅에서 터지니 모든 발에. 발을 다 잃으면
 * 벽이 넓적한 얼굴 판으로 드러나 앞으로 쓰러진다(앞 3블록 14 피해와 0.6초 넘어짐) 그리고 죽는다.
 *
 * <p>밀기: 벽 앞면에 닿은 사람은 밀려나며 0.5초마다 5 피해(눌림), 벽과 다른 게이브 사이에 끼이면 10. 닿는 순간 눈 뜬 자국이 찍힌다.
 *
 * <p>팩 계약(tools/gabe/sp/g166.py): 모델 gabe/g166(부위 f0..f3), gabe/g166_wall(상태 "", "hit", "touch", "fall"). 소리 tiptoe(20), ricochet(24),
 * snap(20), fall(32). 글귀 cause_g166.
 */
public final class G166 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double HOVER = 0.2, RADIUS = 0.5, SPEED = 0.15;
    /** 벽: 폭, 높이, 바닥에서 아랫변, 이 높이(바닥 기준) 아래로 맞으면 발. 발 자리(옆, 블록). */
    static final double WALL_W = 3.6, WALL_H = 3.0, WALL_BOTTOM = 0.38, FOOT_LINE = 0.5;
    static final double[] FEET_X = {-1.35, -0.45, 0.45, 1.35};
    static final double FOOT_HP = 22;
    /** 밀기, 끼임, 튕김 피해와 쿨다운, 튕김 반경, 쓰러짐(피해, 앞 거리, 넘어짐 틱, 쓰러지는 틱). */
    static final double PRESS = 5, PINCH = 10, RICOCHET = 3, RICO_R = 2, FALL_DMG = 14, FALL_REACH = 3.5, PUSH = 0.32;
    static final int PRESS_CD = 10, RICO_CD = 10, FALL_ROOT = 12, FALL_T = 8, FLASH = 6, STEP_EVERY = 8;
    /** 벽 구 줄: 옆으로, 위로(바닥 기준 높이), 반지름. */
    static final double[] ROW_X = {-1.4, -0.7, 0, 0.7, 1.4};
    static final double[] ROW_Y = {0.8, 1.55, 2.3, 3.05};
    static final double WALL_SR = 0.42, FOOT_SR = 0.24;

    private Body.Part wall;
    private final double[] footHp = new double[4];
    private boolean init, falling;
    private int fallAt;
    private Damage fallBy;
    private long flashUntil;
    private String flashState = "";
    private int nextStep;
    private final Map<UUID, Long> pressUntil = new HashMap<>();
    private long ricoAt = Long.MIN_VALUE / 2;
    /** 이번 틱 구(맞음 판정은 같은 틱에 여러 번 묻는다). */
    private final List<double[]> sph = new ArrayList<>(24);
    private long sphTick = Long.MIN_VALUE;
    private int sphN;

    public G166(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
        ragKick = 0.3;
        for (int i = 0; i < 24; i++) sph.add(new double[4]);
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g166", 4.2f, 4.0f);
        wall = body.attach("gabe/g166_wall");
        if (wall != null) wall.pose(new Matrix4f().translate(0, (float) (WALL_BOTTOM + WALL_H / 2 - HOVER), 0), 0);
    }

    @Override
    protected void spawned() {
        nextStep = age + game.random.nextInt(STEP_EVERY);
    }

    /** 감독이 체력 배율을 곱한 뒤(첫 틱) 발 체력을 나눔. */
    private void init() {
        init = true;
        double k = maxHp / Math.max(1e-6, sp.hp);
        for (int i = 0; i < 4; i++) footHp[i] = FOOT_HP * k;
        hp = maxHp = FOOT_HP * k * 4;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        long now = now();
        if (falling) {
            hold();
            if (age - fallAt >= FALL_T) {
                untargetable = false;
                super.die(fallBy != null ? fallBy : Damage.of(1, DamageType.OTHER));
            }
            return;
        }
        if (wall != null && flashUntil != 0 && now >= flashUntil) {
            flashUntil = 0;
            wall.state("");
            wall.look.open = false;
        }
        Player p = nearest(400);
        follow(speed());
        if (p != null) {
            face(chest(p));
            pitch = 0;
            if (dist(p) < 12 && target != p) mark(p);
        }
        if (age >= nextStep && Math.hypot(vel.getX(), vel.getZ()) > 0.02) {
            sound("tiptoe", Sound.BLOCK_WOOD_STEP, 1.6f, 20);
            nextStep = age + STEP_EVERY;
        }
        press(now);
    }

    /** 벽 앞면에 닿은 사람: 밀어냄, 0.5초마다 눌림 5(다른 게이브와 사이에 끼이면 10). */
    private void press(long now) {
        Vector f = fwd(), l = left();
        double fl = floor();
        for (Player p : game.mobs.standing()) {
            Vector rel = p.getLocation().toVector().subtract(pos);
            double fd = rel.getX() * f.getX() + rel.getZ() * f.getZ();
            double ld = rel.getX() * l.getX() + rel.getZ() * l.getZ();
            double py = p.getLocation().getY() - fl;
            if (fd < -0.35 || fd > 0.75 || Math.abs(ld) > WALL_W / 2 + 0.2 || py > WALL_BOTTOM + WALL_H || py < -1) continue;
            knock(p, f, PUSH, 0.02);
            Long until = pressUntil.get(p.getUniqueId());
            if (until != null && until > now) continue;
            pressUntil.put(p.getUniqueId(), now + cd(PRESS_CD));
            hit(p, pinched(p, f) ? PINCH : PRESS, "cause_g166");
            flash("touch", true);
        }
    }

    /** 사람 뒤(벽 반대쪽) 1.5블록 안에 다른 게이브가 있으면 끼임. */
    private boolean pinched(Player p, Vector f) {
        Vector c = chest(p);
        for (Gabe g : game.mobs.near(c, 1.6, g -> g != this && !g.sp.is(Role.BOSS))) {
            Vector r = g.pos.clone().subtract(c);
            if (r.getX() * f.getX() + r.getZ() * f.getZ() > 0.2) return true;
        }
        return false;
    }

    private void flash(String st, boolean open) {
        if (wall == null || falling) return;
        wall.state(st);
        wall.look.open = open;
        flashUntil = now() + FLASH;
    }

    // ------------------------------------------------------------------ 맞음

    private Vector fwd() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** 몹 자신의 왼쪽(yaw 0 일 때 +X). */
    private Vector left() {
        double r = Math.toRadians(yaw);
        return new Vector(Math.cos(r), 0, Math.sin(r));
    }

    @Override
    public List<double[]> spheres() {
        long now = now();
        if (sphTick == now) return sph.subList(0, sphN);
        sphTick = now;
        double r = Math.toRadians(yaw), lx = Math.cos(r), lz = Math.sin(r);
        double fl = floor();
        int k = 0;
        if (!falling) {
            for (double y : ROW_Y) {
                for (double x : ROW_X) {
                    double[] s = sph.get(k++);
                    s[0] = pos.getX() + lx * x;
                    s[1] = fl + y;
                    s[2] = pos.getZ() + lz * x;
                    s[3] = WALL_SR;
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            double[] s = sph.get(k++);
            s[0] = pos.getX() + lx * FEET_X[i];
            s[1] = fl + 0.2;
            s[2] = pos.getZ() + lz * FEET_X[i];
            s[3] = footHp[i] > 0 || !init ? FOOT_SR : 0.01;
        }
        sphN = k;
        return sph.subList(0, k);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || falling) return false;
        if (!init) init();
        DamageType t = d.type();
        double fl = floor();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (gun && d.at() != null && d.at().getY() - fl > FOOT_LINE) {
            ricochet(d);
            return false;
        }
        double dealt = 0;
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            // 땅에서 터짐: 산 발 모두에
            double each = t == DamageType.FIRE ? d.amount() * 0.5 : d.amount();
            for (int i = 0; i < 4; i++) dealt += hurtFoot(i, each);
        } else {
            int i = nearestFoot(d.at());
            if (i >= 0) dealt = hurtFoot(i, d.amount());
        }
        if (dealt <= 0) return false;
        hp = 0;
        for (double v : footHp) hp += Math.max(0, v);
        game.mobs.onHurt(this, d, dealt);
        boolean any = false;
        for (double v : footHp) any |= v > 0;
        if (!any) fall(d);
        return true;
    }

    private double hurtFoot(int i, double amount) {
        if (footHp[i] <= 0) return 0;
        double dealt = Math.min(footHp[i], amount);
        footHp[i] -= amount;
        if (footHp[i] <= 0) {
            body.look.on.add("f" + i);
            sound("snap", Sound.BLOCK_WOOD_BREAK, 1.2f, 20);
        }
        return dealt;
    }

    private int nearestFoot(Vector at) {
        double r = Math.toRadians(yaw), lx = Math.cos(r), lz = Math.sin(r);
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            if (footHp[i] <= 0) continue;
            if (at == null) return i;
            double dx = at.getX() - (pos.getX() + lx * FEET_X[i]), dz = at.getZ() - (pos.getZ() + lz * FEET_X[i]);
            double d2 = dx * dx + dz * dz;
            if (d2 < bd) {
                bd = d2;
                best = i;
            }
        }
        return best;
    }

    /** 위쪽에 맞은 탄: 튕김(피해 없음), 눌린 얼굴 자국, 맞은 점 2블록 안 가장 가까운 사람에게 3. */
    private void ricochet(Damage d) {
        flash("hit", false);
        long now = now();
        if (now - ricoAt < RICO_CD) return;
        ricoAt = now;
        sound("ricochet", Sound.BLOCK_GLASS_HIT, 1.4f, 24);
        Vector at = d.at();
        Player best = null;
        double bd = RICO_R * RICO_R;
        for (Player p : game.mobs.standing()) {
            double d2 = chest(p).distanceSquared(at);
            if (d2 < bd) {
                bd = d2;
                best = p;
            }
        }
        if (best != null) hit(best, RICOCHET, "cause_g166");
    }

    /** 발을 다 잃음: 넓적한 얼굴 판으로 드러나 앞으로 쓰러짐(앞 3블록 14 피해와 넘어짐), FALL_T 뒤 죽음. */
    private void fall(Damage d) {
        falling = true;
        fallAt = age;
        fallBy = d;
        untargetable = true;
        hold();
        if (wall != null) {
            wall.state("fall");
            wall.look.open = true;
            float by = (float) (WALL_BOTTOM - HOVER);
            wall.pose(new Matrix4f().translate(0, by, 0).rotateX((float) Math.toRadians(88)).translate(0, (float) (WALL_H / 2), 0), FALL_T);
        }
        sound("fall", Sound.ENTITY_GENERIC_BIG_FALL, 0.6f, 32);
        Vector f = fwd(), l = left();
        for (Player p : game.mobs.standing()) {
            Vector rel = p.getLocation().toVector().subtract(pos);
            double fd = rel.getX() * f.getX() + rel.getZ() * f.getZ();
            double ld = rel.getX() * l.getX() + rel.getZ() * l.getZ();
            if (fd < -0.3 || fd > FALL_REACH || Math.abs(ld) > WALL_W / 2 + 0.3) continue;
            hit(p, FALL_DMG, "cause_g166");
            game.players.root(p, FALL_ROOT, "g166");
        }
    }

    /** 시험용. */
    public String info() {
        StringBuilder b = new StringBuilder("feet=");
        for (double v : footHp) b.append(Math.round(v)).append(',');
        return b.append(falling ? " falling" : "").toString();
    }

    @Override
    public String toString() {
        return super.toString() + " " + info();
    }
}
