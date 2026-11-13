package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G22 뒷걸음 게이브(돌격). 늘 뒤통수를 표적 쪽으로 하고 뒷걸음질로 오는데(걸음마다 '뚝'), 뒤통수 한가운데 세로로 찢어진 외눈이 떠서 그 사람을 본다.
 * 다리(엔드 막대기 둘)와 머리 사이를 분홍 살 목이 잇는다.
 *
 * <p>6블록 안에서 보이면 0.4초 움츠렸다가 목이 고무줄처럼 늘어나며('끼익') 머리가 0.6초 동안 튀어 나가고, 날아가는 도중 90도씩 두 번(3틱씩)
 * 비틀려 앞얼굴이 돌아오고 뒤통수 외눈이 감긴다. 늘어난 목 끝이 물면 5 피해, 되감기며 1블록쯤 끌어당긴다. 되감긴 뒤 2.5초 쉰다.
 *
 * <p>대처: 뒤통수가 표적 쪽일 때(걸어오는 동안) 머리에 맞는 정면 탄은 피해 2배. 목이 늘어난 동안 가는 목(구 판정)을 쏘면 끊겨('우두둑') 머리가 되돌아오고
 * 3초 기절(다리 멈춤, 머리 늘어짐).
 *
 * <p>디스플레이 셋: 닻 gave:gabe/g22(엉덩이, 다리), 부품 gave:gabe/g22_head(상태 "", "shut"), gave:gabe/g22_neck(1블록 목, 길이만큼 늘임).
 * 소리 step, stretch, bite, snap, 원인 글귀 cause_g22.
 */
public final class G22 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.12;
    /** 덮치는 거리, 움츠림 틱, 늘어나는 틱(0.6초), 되감는 틱, 쉬는 틱. */
    static final double LUNGE_R = 6;
    static final int WIND = 8, EXTEND = 12, RETRACT = 10, COOLDOWN = 50;
    /** 물기 피해, 끌어당김(블록/틱), 무는 거리(머리 끝에서 사람 몸통). */
    static final double BITE_DMG = 6, PULL = 0.55, BITE_R = 1.15;
    /** 뒤통수 피해 배율, 기절 틱. */
    static final double BACK_MUL = 2;
    static final int STUN = 60;
    /** 몸 기준: 목 뿌리, 머리 쉬는 자리, 머리 구, 목 구 반지름. */
    static final Vector3f ROOT = new Vector3f(0, 0.12f, 0), REST = new Vector3f(0, 0.72f, -0.04f);
    static final double HEAD_R = 0.33, NECK_R = 0.2, HIP_R = 0.25;
    /** 걸음 '뚝' 간격(16틱 한 바퀴에 두 걸음). */
    static final int STEP = 8;

    enum Phase { WALK, WIND, EXTEND, RETRACT, STUN }

    private Phase phase = Phase.WALK;
    private int phaseAt, readyAt, nextStep;
    private Body.Part head, neck;
    private Player prey;
    /** 머리 자리(몸 기준), 비튼 각(도, 0 = 뒤통수가 앞), 늘어난 끝 자리(몸 기준). */
    private final Vector3f headAt = new Vector3f(REST);
    private final Vector3f reach = new Vector3f();
    private float twist;
    private boolean bit;

    public G22(Game game, Species sp) {
        super(game, sp);
        radius = 0.4;
        hover = 1.0; // 엉덩이(몸 중심)가 바닥에서 1 블록: 다리 막대기가 바닥까지
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/" + sp.key(), 1.4f, 2.4f);
        neck = body.attach("gabe/g22_neck");
        head = body.attach("gabe/g22_head");
        place(0);
    }

    @Override
    protected void spawned() {
        nextStep = age + game.random.nextInt(STEP);
        if (head != null) head.look.phase = body.look.phase;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 부품 자세

    /** 머리와 목을 headAt, twist 로(ticks 보간). */
    private void place(int ticks) {
        if (head != null) head.pose(new Matrix4f().translate(headAt).rotateY((float) Math.toRadians(twist)), ticks);
        if (neck != null) {
            Vector3f d = new Vector3f(headAt).sub(ROOT);
            d.y -= 0.22f; // 머리 아래쪽까지
            float len = Math.max(0.05f, d.length());
            Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), d.normalize());
            neck.pose(new Matrix4f().translate(ROOT).rotate(q).scale(1f, 1f, len), ticks);
        }
    }

    private void syncLook() {
        if (head == null) return;
        head.look.open = target != null;
        head.look.mut = body.look.mut;
        head.state(twist > 45 ? "shut" : "");
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case WALK -> walk();
            case WIND -> wind();
            case EXTEND -> extend();
            case RETRACT -> retract();
            case STUN -> stun();
        }
        syncLook();
    }

    /** 뒷걸음: 몸(뒤통수 외눈)은 표적을 보고 그쪽으로 걸어옴. */
    private void walk() {
        body.look.freeze = false;
        follow(speed());
        Player p = nearest(40);
        if (p != null) {
            face(chest(p));
            pitch = 0;
        }
        if (vel.lengthSquared() > 1e-5 && age >= nextStep) {
            sound("step", Sound.ENTITY_SKELETON_STEP, 1.4f, 16);
            nextStep = age + STEP;
        }
        if (p != null && dist(p) <= LUNGE_R && sees(p)) {
            if (target != p) mark(p);
            if (age >= readyAt) {
                prey = p;
                hold();
                phase(Phase.WIND);
            }
        } else if (target != null) mark(null);
    }

    /** 움츠림: 머리를 살짝 끌어내림. */
    private void wind() {
        hold();
        if (!standing(prey)) {
            back();
            return;
        }
        face(chest(prey));
        pitch = 0;
        // 단계에 든 다음 틱이 첫 틱(inPhase 1): 머리를 살짝 끌어내려 움츠림
        if (inPhase() == 1) {
            headAt.set(REST.x, REST.y - 0.12f, REST.z - 0.12f);
            place(cd(WIND) - 2);
        }
        if (inPhase() >= cd(WIND)) {
            // 목 끝이 갈 자리: 지금 표적 몸통(몸 기준), 6블록까지
            Vector c = chest(prey).subtract(pos);
            Vector3f local = toLocal(c);
            float l = local.length();
            if (l > LUNGE_R + 0.5) local.mul((float) ((LUNGE_R + 0.5) / l));
            reach.set(local);
            bit = false;
            sound("stretch", Sound.ENTITY_GOAT_HORN_BREAK, 1.6f, 20);
            phase(Phase.EXTEND);
        }
    }

    /** 늘어남: 0.6초에 걸쳐 머리가 날아가며 90도씩 두 번 비틀림. 끝에서 묾. */
    private void extend() {
        hold();
        int t = inPhase() + 1;
        float u = Math.min(1f, (float) t / EXTEND);
        headAt.set(REST).lerp(reach, u);
        if (t == EXTEND / 3) twist = 90f;
        if (t == EXTEND * 2 / 3) twist = 180f;
        place(t == EXTEND / 3 || t == EXTEND * 2 / 3 ? 3 : 1);
        if (!bit && prey != null && standing(prey) && headWorld().distance(chest(prey)) <= BITE_R) bite(prey);
        if (t >= EXTEND) {
            if (!bit) {
                for (Player q : game.mobs.standing()) {
                    if (headWorld().distance(chest(q)) <= BITE_R) {
                        bite(q);
                        break;
                    }
                }
            }
            phase(Phase.RETRACT);
        }
    }

    private void bite(Player p) {
        bit = true;
        hit(p, BITE_DMG, "cause_g22");
        sound("bite", Sound.ENTITY_SLIME_ATTACK, 0.8f, 16);
        // 되감기며 끌어당김
        Vector pull = pos.clone().subtract(p.getLocation().toVector()).setY(0);
        knock(p, pull, PULL, 0.12);
    }

    /** 되감음: 머리가 쉬는 자리로, 비틀림도 되돌림. */
    private void retract() {
        hold();
        int t = inPhase() + 1;
        float u = Math.min(1f, (float) t / RETRACT);
        Vector3f from = new Vector3f(reach);
        headAt.set(from).lerp(REST, u);
        if (t == RETRACT / 2) twist = 90f;
        if (t == RETRACT) twist = 0f;
        place(t == RETRACT / 2 || t == RETRACT ? 3 : 1);
        if (t >= RETRACT) back();
    }

    private void back() {
        headAt.set(REST);
        twist = 0;
        place(3);
        prey = null;
        readyAt = age + cd(COOLDOWN);
        phase(Phase.WALK);
    }

    /** 목이 끊김: 머리가 되돌아와 늘어진 채 3초 기절. */
    private void snap() {
        sound("snap", Sound.BLOCK_BAMBOO_BREAK, 0.7f, 20);
        headAt.set(REST.x + 0.18f, REST.y - 0.3f, REST.z + 0.1f);
        twist = 30f;
        place(2);
        body.look.freeze = true;
        mark(null);
        prey = null;
        phase(Phase.STUN);
    }

    private void stun() {
        hold();
        body.roll = (float) Math.sin(age * 0.9) * 4f;
        if (inPhase() >= STUN) {
            body.roll = 0;
            body.look.freeze = false;
            back();
        }
    }

    // ------------------------------------------------------------------ 좌표

    private Vector3f toLocal(Vector w) {
        double a = Math.toRadians(yaw);
        double lx = Math.cos(a) * w.getX() + Math.sin(a) * w.getZ();
        double lz = -Math.sin(a) * w.getX() + Math.cos(a) * w.getZ();
        return new Vector3f((float) lx, (float) w.getY(), (float) lz);
    }

    private Vector world(Vector3f l) {
        double a = Math.toRadians(-yaw);
        double c = Math.cos(a), s = Math.sin(a);
        return new Vector(pos.getX() + c * l.x + s * l.z, pos.getY() + l.y, pos.getZ() - s * l.x + c * l.z);
    }

    private Vector headWorld() {
        return world(headAt);
    }

    private boolean necking() {
        return phase == Phase.EXTEND || phase == Phase.RETRACT;
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(8);
        Vector h = headWorld();
        out.add(new double[] {h.getX(), h.getY(), h.getZ(), HEAD_R});
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), HIP_R});
        if (necking()) {
            Vector r = world(ROOT);
            Vector d = h.clone().subtract(r);
            double l = d.length();
            int n = (int) Math.floor(l / 0.55);
            for (int i = 1; i < n; i++) {
                Vector c = r.clone().add(d.clone().multiply(i * 0.55 / l));
                out.add(new double[] {c.getX(), c.getY(), c.getZ(), NECK_R});
            }
        }
        return out;
    }

    /** 맞은 점에서 가장 가까운 목 구의 겉까지(목 구가 없으면 아주 큰 값). 구 자리는 spheres() 와 같다. */
    private double neckGap(Vector at, Vector h) {
        Vector r = world(ROOT);
        double dx = h.getX() - r.getX(), dy = h.getY() - r.getY(), dz = h.getZ() - r.getZ();
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = (int) Math.floor(l / 0.55);
        double best = Double.MAX_VALUE;
        for (int i = 1; i < n; i++) {
            double k = i * 0.55 / l;
            double cx = r.getX() + dx * k - at.getX(), cy = r.getY() + dy * k - at.getY(), cz = r.getZ() + dz * k - at.getZ();
            best = Math.min(best, Math.sqrt(cx * cx + cy * cy + cz * cz) - NECK_R);
        }
        return best;
    }

    @Override
    public Vector head() {
        return headWorld();
    }

    /** 목이 늘어난 채 죽으면 머리를 되감아 시체가 몸 곁에 놓이게. */
    @Override
    public void die(Damage d) {
        if (!dead && necking()) {
            headAt.set(REST);
            twist = 0;
            place(0);
        }
        super.die(d);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            Vector h = headWorld();
            double dh = d.at().distance(h) - HEAD_R, dhip = d.at().distance(pos) - HIP_R;
            // 맞은 점은 맞은 구의 겉(반지름 + 탄 여유)에 있다: 목 구가 머리, 엉덩이 구보다 가까울 때만 목에 맞은 것(끊겨 기절)
            if (necking()) {
                double dn = neckGap(d.at(), h);
                if (dn < dh - 1e-3 && dn < dhip - 1e-3) {
                    snap();
                    return super.damage(d);
                }
            }
            Vector f = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            boolean fromFront = d.dir() != null && d.dir().clone().setY(0).dot(f) < -0.2;
            if (dh <= dhip && twist < 45 && fromFront) d = d.times(BACK_MUL);
        }
        return super.damage(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " twist=" + (int) twist;
    }
}
