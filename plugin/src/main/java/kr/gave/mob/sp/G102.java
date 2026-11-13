package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G102 던져 올리는 손 게이브(2막, 방해, 돌격). 폭 7 블록의 거대한 손이 손가락 끝으로 거미처럼 걸어와 사람 하나를 고르면 1초 동안 손을 치켜들고
 * 손가락을 오므리며(그 사람 발밑에 빨간 고리) 떠 올려 하늘로 곧장 던진다(22 블록). 떨어지면 18, 떨어질 자리(그림자 고리)에 동료가 서 있으면
 * 받아 줘서 6. 던진 뒤 손바닥 얼굴이 하늘을 보며 웃는다. 곁에 붙은 사람은 손바닥으로 내려친다(반경 3 안 14).
 * 손바닥 얼굴이 약점(앞에서 맞으면 x2), 손가락(마다 체력)을 끊으면 걸음이 느려진다.
 *
 * <p>팩 계약(tools/gabe/sp/g102.py): 모델 gabe/g102(shrink 0.3), 상태 ""(걷기 16틱 자세 4), "reach", "grab", "up", 부위 cut0..cut4.
 * 소리 slap, curl, laugh. 글귀 cause_g102, cause_g102_slap.
 *
 * <p>도감과 다른 점: 손가락 다섯을 부품 디스플레이로 나누지 않고 닻 하나의 걸음 자세와 몸짓 상태로 구웠다(디스플레이 1). 띄우기는 공용
 * 공용 LAUNCH(Players.launch) 위에 이 묶음 도우미 {@link G66Giant.Flings}(받아 주기: 곁에 동료가 있으면 6, 없으면 18).
 */
public final class G102 extends Gabe {

    static final double HOVER = 3.2, SPEED = 0.12;
    /** 떠 올리기: 고르는 거리(몸 가운데에서), 떠 담는 자리(앞), 고리 반지름, 띄우는 높이(블록), 피해, 받아 줌 피해. */
    static final double GRAB_R = 6.0, SCOOP_AT = 2.6, SCOOP_R = 1.9, UP_H = 22, FALL_DMG = 18, CAUGHT_DMG = 6;
    static final int TELL = 20, GRAB_LEN = 6, UP_LEN = 40, GRAB_CD = 110, MAX_LIFT = 2;
    /** 내려치기: 반경, 피해, 예고, 쿨다운. */
    static final double SLAP_R = 3.0, SLAP_DMG = 14, SLAP_NEAR = 3.4;
    static final int SLAP_TELL = 14, SLAP_CD = 50;
    /** 손가락 체력, 끊길 때마다 느려지는 몫. */
    static final double FINGER_HP = 90, CUT_SLOW = 0.15;
    /** 손가락 뿌리와 끝(팩 g102.py FINGERS 와 같게, 뿌리는 손바닥 기울기 전). */
    static final double[][] F_BASE = {{1.75, -0.2, 0.15}, {1.2, -1.75, 0.3}, {0.4, -1.85, 0.35}, {-0.4, -1.85, 0.35}, {-1.2, -1.7, 0.3}};
    static final double[][] F_TIP = {{3.5, 1.4}, {2.05, 3.0}, {0.7, 3.5}, {-0.7, 3.3}, {-2.0, 2.7}};
    static final double PALM_R = 1.95;

    enum Phase { WALK, REACH, GRAB, UP, SLAP }

    private Phase phase = Phase.WALK;
    private int phaseAt, grabAt, slapAt, nextStep, cuts;
    private Player prey;
    private final Vector scoop = new Vector();
    private final double[] fingerHp = {-1, -1, -1, -1, -1};
    private final boolean[] cut = new boolean[5];
    private final G66Giant.Flings flings = new G66Giant.Flings();
    private final List<double[]> sph = new ArrayList<>(8);
    private int sphAge = -1;

    public G102(Game game, Species sp) {
        super(game, sp);
        radius = PALM_R;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        noMutate = true;
        ragKick = 0.35;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g102", 8f, 8f);
        body.viewRange(2.6f);
    }

    @Override
    protected void spawned() {
        grabAt = age + 40;
        nextStep = age + 8;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private double walkSpeed() {
        return speed() * Math.max(0.3, 1 - CUT_SLOW * cuts);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (fingerHp[0] < 0) {
            double k = Math.max(0.5, maxHp / Math.max(1, sp.hp));
            for (int i = 0; i < 5; i++) fingerHp[i] = FINGER_HP * k;
        }
        flings.tick(this, null);
        switch (phase) {
            case WALK -> walk();
            case REACH -> reach();
            case GRAB -> grab();
            case UP -> up();
            case SLAP -> slap();
        }
    }

    private double hdist(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    private void walk() {
        state("");
        body.look.freeze = false;
        double v = walkSpeed();
        body.look.rate = Math.max(0.5, Math.min(2, v / SPEED));
        Player p = nearest(400);
        if (p == null) {
            follow(v);
            return;
        }
        mark(dist(p) < 40 ? p : null);
        double d = hdist(p);
        G66Giant.faceFlat(this, chest(p));
        if (d > 2.2) follow(v);
        else hold();
        if (age >= nextStep) {
            nextStep = age + Math.max(4, (int) Math.round(8 / Math.max(0.5, body.look.rate)));
            Vector f = G66Giant.at(this, (age / 8) % 2 == 0 ? 1.4 : -1.4, -HOVER, 2.8);
            f.setY(floor());
            G66Giant.stomp(this, f, 40, 3.5, 0.55f);
        }
        if (d <= SLAP_NEAR && age >= slapAt && (age < grabAt || flings.has(p))) {
            startSlap();
            return;
        }
        if (d <= GRAB_R && age >= grabAt && !flings.has(p) && sees(p)) {
            prey = p;
            mark(p);
            hold();
            scoop.copy(p.getLocation().toVector());
            scoop.setY(floor());
            G66Giant.warn(this, scoop, SCOOP_R, cd(TELL) + 2);
            state("reach");
            sound("curl", Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.5f, 24);
            phase(Phase.REACH);
        }
    }

    /** 1초 예고: 손을 치켜들고 손가락을 오므림, 고리가 그 사람 발밑에 남음(움직여 피함). 손은 고리 쪽으로 다가붙음. */
    private void reach() {
        Vector to = scoop.clone().subtract(G66Giant.fwd(this).multiply(SCOOP_AT));
        double h = Math.hypot(to.getX() - pos.getX(), to.getZ() - pos.getZ());
        if (h > 0.3) seek(to.setY(pos.getY()), Math.min(0.25, h));
        else hold();
        G66Giant.faceFlat(this, scoop);
        body.offset.set(0, (float) Math.min(1.0, inPhase() * 0.08), 0);
        if (inPhase() >= cd(TELL)) {
            body.offset.set(0, 0, 0);
            state("grab");
            sound("slap", Sound.ENTITY_SLIME_ATTACK, 0.5f, 48);
            int n = 0;
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                if (Math.hypot(l.getX() - scoop.getX(), l.getZ() - scoop.getZ()) > SCOOP_R || Math.abs(l.getY() - scoop.getY()) > 2.5) continue;
                if (flings.launch(this, q, UP_H, null, FALL_DMG, CAUGHT_DMG, "cause_g102")) {
                    // 떨어질 자리(그림자): 곧게 위로 던지므로 지금 자리
                    G66Giant.warn(this, l.toVector(), 1.8, 52);
                    n++;
                }
                if (n >= MAX_LIFT) break;
            }
            G66Giant.ring(this, scoop, SCOOP_R + 0.6, 10);
            grabAt = age + cd(GRAB_CD);
            phase(Phase.GRAB);
        }
    }

    private void grab() {
        hold();
        if (inPhase() >= GRAB_LEN) {
            state("up");
            sound("laugh", Sound.ENTITY_WITCH_CELEBRATE, 0.6f, 40);
            phase(Phase.UP);
        }
    }

    /** 손바닥 얼굴이 하늘을 보며 웃음. */
    private void up() {
        hold();
        if (inPhase() >= UP_LEN) {
            state("");
            mark(null);
            phase(Phase.WALK);
        }
    }

    private void startSlap() {
        hold();
        state("reach");
        phase(Phase.SLAP);
    }

    /** 내려치기: 손을 치켜들었다(14틱) 손바닥으로 땅을 침. */
    private void slap() {
        hold();
        int t = inPhase();
        if (t < cd(SLAP_TELL)) {
            body.offset.set(0, (float) Math.min(1.6, t * 0.14), 0);
            return;
        }
        body.offset.set(0, 0, 0);
        body.pose(2);
        state("");
        sound("slap", Sound.ENTITY_SLIME_ATTACK, 0.5f, 48);
        Vector c = G66Giant.at(this, 0, 0, 1.0);
        c.setY(floor());
        G66Giant.ring(this, c, SLAP_R, 12);
        G66Giant.stomp(this, c, 48, 6, 1f);
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (Math.hypot(l.getX() - c.getX(), l.getZ() - c.getZ()) > SLAP_R + 0.5 || Math.abs(l.getY() - c.getY()) > 3) continue;
            hit(q, SLAP_DMG, "cause_g102_slap");
            knock(q, new Vector(l.getX() - c.getX(), 0, l.getZ() - c.getZ()), 0.5, 0.3);
        }
        slapAt = age + cd(SLAP_CD);
        phase(Phase.WALK);
    }

    // ------------------------------------------------------------------ 맞음

    /** 손가락 i 의 맞는 구 자리(몸 틀): 뿌리에서 끝으로 55% + 위로 0.8(솟은 마디). */
    private static double[] fingerPt(int i) {
        double t = Math.toRadians(-22);
        double[] b = F_BASE[i];
        double by = b[1] * Math.cos(t) - b[2] * Math.sin(t), bz = b[1] * Math.sin(t) + b[2] * Math.cos(t);
        double tx = F_TIP[i][0], ty = -HOVER, tz = F_TIP[i][1];
        return new double[] {b[0] + (tx - b[0]) * 0.55, by + (ty - by) * 0.55 + 0.8, bz + (tz - bz) * 0.55};
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(0, 0, 0.1, PALM_R);
        add(0, 2.0, -1.3, 1.2);
        for (int i = 0; i < 5; i++) {
            double[] f = fingerPt(i);
            add(f[0], f[1], f[2], cut[i] ? 0.0 : 0.75);
        }
        sphAge = age;
        return sph;
    }

    private void add(double x, double y, double z, double r) {
        Vector w = G66Giant.at(this, x, y, z);
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public Vector head() {
        return G66Giant.at(this, 0, 0, 0.5);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.at() == null) return super.damage(d);
        List<double[]> s = spheres();
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < s.size(); i++) {
            double[] c = s.get(i);
            if (c[3] <= 0) continue;
            double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        if (best == 0) {
            // 손바닥 얼굴: 앞에서 날아온 것만 x2
            Vector f = G66Giant.fwd(this);
            boolean front = d.dir() == null ? d.at().clone().subtract(pos).setY(0).dot(f) > 0 : d.dir().getX() * f.getX() + d.dir().getZ() * f.getZ() < 0;
            return super.damage(front ? d.times(2.0) : d);
        }
        if (best >= 2) {
            int i = best - 2;
            fingerHp[i] -= d.amount();
            boolean ok = super.damage(d);
            if (!dead && !cut[i] && fingerHp[i] <= 0) {
                cut[i] = true;
                cuts++;
                body.look.on.add("cut" + i);
                sound("curl", Sound.ENTITY_ITEM_BREAK, 0.7f, 24);
            }
            return ok;
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        flings.clear();
        super.die(d);
    }

    @Override
    public void remove() {
        flings.clear();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " cuts=" + cuts + " flung=" + flings.list.size();
    }
}
