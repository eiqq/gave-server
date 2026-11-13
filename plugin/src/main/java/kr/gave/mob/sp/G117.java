package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;

/**
 * G117 등 맞댄 게이브(정예, 돌격). 등을 맞댄 거울 짝 인형 둘, 하나를 죽이면 다른 하나가 되살린다. 둘을 함께 죽여야 한다.
 *
 * <p>도감: 게걸음으로 빙글빙글 돌며 온다(0.2, 두 얼굴이 번갈아 앞을 봄). 붙어 돌 때 닿으면 12 와 밀침. 사람 곁(4블록)에서 둘로 떨어져 그 사람 양옆
 * 1.5블록으로 벌어져 사이에 끼고 번갈아 친다(16, 1초에 두 번). 한쪽(체력 절반씩)이 쓰러지고 20틱 안에 다른 쪽도 쓰러지면 죽는다. 아니면 남은 쪽이
 * 꿴 막대기를 당겨(막대기가 팽팽해지며 끼익) 쓰러진 지 40틱 만에 일으킨다(체력 가득). 당기는 동안 남은 쪽은 쓰러지지 않고, 늘어난 막대기(체력 40)를
 * 쏴 끊으면 못 일으킨다(그 뒤 남은 쪽 하나만 잡으면 끝).
 *
 * <p>몸: Body.carrier + 부품 둘(gave:gabe/g117, 상태 "l", "r", l 의 부위 split) + 떨어졌을 때 잇는 막대기 부품 gave:gabe/g117_rod. 소리 clap, creak,
 * snap, strike. 원인 글귀 cause_g117.
 */
public final class G117 extends Gabe {

    static final double SPEED = 0.2, HOVER = 1.24, SPIN = 9, SPLIT_RANGE = 4, SIDE = 1.5, DOLL_V = 0.28;
    static final double TOUCH = 6, STRIKE = 9, STRIKE_R = 1.9, ROD_HP = 40;
    static final int TOUCH_CD = 30, STRIKE_EVERY = 20, SPLIT_MAX = 140, SPLIT_CD = 60, PAIR_T = 20, REVIVE_T = 40;
    static final double HEAD_Y = 0.8, JOIN_SIDE = 0.28;

    enum Phase { JOINED, SPLIT, REJOIN }

    /** 인형 하나. */
    private final class Doll {
        final String st;
        Body.Part p;
        final Vector at = new Vector();
        float yaw;
        double hp;
        int downAt = -1;
        boolean gone; // 막대기가 끊겨 다시 못 일어남
        float lean;

        Doll(String st) {
            this.st = st;
        }

        boolean down() {
            return downAt >= 0 || gone;
        }
    }

    private final Doll a = new Doll("l"), b = new Doll("r");
    private Phase phase = Phase.JOINED;
    private int phaseAt, touchAt = -100, splitReady;
    private Player prey;
    private Body.Part rod;
    private double rodHp;
    private Rod rodShoot;
    private boolean scaled;

    public G117(Game game, Species sp) {
        super(game, sp);
        radius = 0.75;
        hover = HOVER;
        baseSpeed = SPEED;
        ragKick = 0.7;
        a.hp = b.hp = sp.hp / 2.0;
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        a.p = body.attach("gabe/g117");
        b.p = body.attach("gabe/g117");
        if (a.p != null) {
            a.p.follow = false;
            a.p.state("l");
        }
        if (b.p != null) {
            b.p.follow = false;
            b.p.state("r");
        }
        placeJoined();
        poseDolls(0);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private Vector fwd(float y) {
        double r = Math.toRadians(y);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    private Doll other(Doll d) {
        return d == a ? b : a;
    }

    /** 붙어 있음: 등을 맞대고 몸 가운데에서 앞뒤 0.2, 옆으로 0.28 씩 어긋나게(멀리서도 둘이 보이게). */
    private void placeJoined() {
        Vector f = fwd(yaw), l = new Vector(f.getZ(), 0, -f.getX());
        a.at.copy(pos).add(f.clone().multiply(0.2)).add(l.clone().multiply(JOIN_SIDE));
        a.yaw = yaw;
        b.at.copy(pos).subtract(f.clone().multiply(0.2)).subtract(l.clone().multiply(JOIN_SIDE));
        b.yaw = yaw + 180f;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!scaled) {
            scaled = true;
            a.hp = b.hp = maxHp / 2.0;
        }
        hp = Math.max(0, a.hp) + Math.max(0, b.hp);
        revive(a);
        revive(b);
        if (dead) return;
        switch (phase) {
            case JOINED -> joined();
            case SPLIT -> split();
            case REJOIN -> rejoin();
        }
        boolean apart = phase != Phase.JOINED || a.down() || b.down();
        if (a.p != null) {
            if (apart) a.p.look.on.add("split");
            else a.p.look.on.remove("split");
            a.p.look.open = body.look.open;
        }
        if (b.p != null) b.p.look.open = body.look.open;
        poseDolls(2);
        poseRod();
    }

    private void joined() {
        noPush = false;
        if (pulling()) hold();
        else follow(speed());
        Player p = nearest(400);
        // 게걸음으로 빙글빙글: 두 얼굴이 번갈아 앞을 봄
        yaw = (float) ((yaw + SPIN) % 360f);
        pitch = 0;
        if (a.down() || b.down()) {
            // 하나가 쓰러져 있으면 붙지 못하고 남은 쪽만 제자리에서 섬
            Doll up = a.down() ? b : a;
            up.at.copy(pos);
            if (p != null) up.yaw = yawTo(up.at, chest(p));
        } else placeJoined();
        if (p == null) {
            mark(null);
            return;
        }
        mark(dist(p) <= 8 ? p : null);
        if (age - touchAt >= cd(TOUCH_CD)) {
            for (Player q : game.mobs.standing()) {
                if (!touches(q, 0.25)) continue;
                touchAt = age;
                hit(q, TOUCH, "cause_g117");
                knock(q, toward(q), 0.7, 0.25);
                sound("strike", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 16);
                break;
            }
        }
        if (age >= splitReady && !a.down() && !b.down() && dist(p) <= SPLIT_RANGE && sees(p)) {
            prey = p;
            mark(p);
            sound("clap", Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.4f, 20);
            phase(Phase.SPLIT);
        }
    }

    /** 둘로 떨어져 사람 양옆에서 번갈아 침. */
    private void split() {
        noPush = true;
        int t = inPhase();
        if (!standing(prey) || dist(prey) > SPLIT_RANGE + 3 || t >= SPLIT_MAX) {
            phase(Phase.REJOIN);
            return;
        }
        Vector c = prey.getLocation().toVector();
        // 몸 가운데(닻)는 사람 자리로, 인형은 양옆
        Vector to = c.clone().subtract(pos).setY(0);
        if (to.length() > 0.3) vel.copy(to.normalize().multiply(Math.min(speed() * 1.4, to.length())));
        else hold();
        Vector side = new Vector(-(c.getZ() - pos.getZ()), 0, c.getX() - pos.getX());
        if (side.lengthSquared() < 1e-4) side = new Vector(1, 0, 0);
        side.normalize();
        for (int i = 0; i < 2; i++) {
            Doll d = i == 0 ? a : b;
            if (d.down()) continue;
            Vector want = c.clone().add(side.clone().multiply(i == 0 ? SIDE : -SIDE));
            want.setY(pos.getY());
            Vector step = want.subtract(d.at);
            double len = step.length();
            if (len > DOLL_V) step.multiply(DOLL_V / len);
            d.at.add(step);
            d.at.setY(pos.getY());
            d.yaw = yawTo(d.at, chest(prey));
            // 번갈아 침(한쪽씩 10틱 엇갈림)
            int beat = cd(STRIKE_EVERY);
            if (t > 6 && (t + i * beat / 2) % beat == 0) {
                d.lean = 28f;
                Vector dc = d.at.clone().add(new Vector(0, 0.2, 0));
                if (chest(prey).distanceSquared(dc) <= STRIKE_R * STRIKE_R) {
                    hit(prey, STRIKE, "cause_g117");
                    sound("clap", Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.3f, 20);
                } else sound("strike", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 16);
            } else d.lean *= 0.6f;
        }
        yaw = a.yaw;
        pitch = 0;
    }

    /** 다시 등을 맞댐. */
    private void rejoin() {
        noPush = false;
        hold();
        boolean close = true;
        Vector f = fwd(yaw), l = new Vector(f.getZ(), 0, -f.getX());
        for (int i = 0; i < 2; i++) {
            Doll d = i == 0 ? a : b;
            if (d.down()) continue;
            Vector want = pos.clone().add(f.clone().multiply(i == 0 ? 0.2 : -0.2)).add(l.clone().multiply(i == 0 ? JOIN_SIDE : -JOIN_SIDE));
            Vector step = want.subtract(d.at);
            double len = step.length();
            if (len > DOLL_V) {
                step.multiply(DOLL_V / len);
                close = false;
            }
            d.at.add(step);
            d.lean *= 0.6f;
        }
        if (close || inPhase() > 30) {
            a.lean = b.lean = 0;
            prey = null;
            splitReady = age + cd(SPLIT_CD);
            phase(Phase.JOINED);
        }
    }

    // ------------------------------------------------------------------ 쓰러짐과 되살림

    private void downed(Doll d, Damage dmg) {
        Doll o = other(d);
        d.hp = 0;
        if (o.down() && (o.gone || age - o.downAt <= PAIR_T)) {
            // 둘이 함께(20틱 안) 쓰러짐, 또는 짝을 못 일으키는 쪽이 쓰러짐: 죽음
            die(dmg);
            return;
        }
        d.downAt = age;
        d.lean = 0;
        // 쓰러진 쪽은 짝에게서 1.4블록 떨어져 눕는다(막대기가 늘어남)
        Vector away = d.at.clone().subtract(o.at).setY(0);
        if (away.lengthSquared() < 1e-4) away = fwd(d.yaw);
        d.at.copy(o.at).add(away.normalize().multiply(1.4));
        if (phase == Phase.SPLIT) phase(Phase.REJOIN);
        sound("snap", Sound.ENTITY_PLAYER_HURT, 0.6f, 20);
    }

    private void revive(Doll d) {
        if (d.downAt < 0 || d.gone || dead) return;
        Doll o = other(d);
        int t = age - d.downAt;
        if (t == PAIR_T) {
            // 당기기 시작: 막대기가 팽팽해짐(쏠 수 있음)
            sound("creak", Sound.BLOCK_CHAIN_PLACE, 0.6f, 24);
            rodHp = ROD_HP * (sp.hp > 0 ? maxHp / sp.hp : 1);
            if (rodShoot == null) rodShoot = new Rod();
            game.mobs.addShootable(rodShoot);
        }
        if (t > PAIR_T) {
            // 끌어당김
            Vector step = o.at.clone().subtract(d.at).setY(0);
            double len = step.length();
            if (len > 0.75) d.at.add(step.multiply(Math.min(0.05, len - 0.75) / len));
        }
        if (t >= REVIVE_T) {
            d.downAt = -1;
            d.hp = maxHp / 2.0;
            stopRod();
            sound("clap", Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.1f, 20);
            body.flash(game.worldTime() + 3);
        }
    }

    private boolean pulling() {
        return (a.downAt >= 0 && !a.gone && age - a.downAt >= PAIR_T) || (b.downAt >= 0 && !b.gone && age - b.downAt >= PAIR_T);
    }

    private void stopRod() {
        if (rodShoot != null) game.mobs.removeShootable(rodShoot);
    }

    /** 되살리는 동안 늘어난 막대기. 끊기면 쓰러진 쪽은 다시 못 일어남. */
    private final class Rod implements Mobs.Shootable {
        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (!pulling() || dead) return Double.POSITIVE_INFINITY;
            Vector p0 = a.at.clone().add(new Vector(0, 0.12, 0)), p1 = b.at.clone().add(new Vector(0, 0.12, 0));
            if (a.down()) p0.setY(a.at.getY() - 0.8);
            if (b.down()) p1.setY(b.at.getY() - 0.8);
            // 광선과 선분의 가장 가까운 거리(표본)
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i <= 8; i++) {
                Vector q = p0.clone().add(p1.clone().subtract(p0).multiply(i / 8.0));
                Vector w = q.clone().subtract(o);
                double s = w.dot(dir);
                if (s < 0 || s > max) continue;
                double d2 = w.lengthSquared() - s * s;
                if (d2 <= 0.25 * 0.25 && s < best) best = s;
            }
            return best;
        }

        @Override
        public boolean shot(Damage d) {
            if (!pulling()) return false;
            rodHp -= d.amount();
            if (d.by() != null) game.hud.hit(d.by(), false);
            if (rodHp <= 0) {
                Doll down = a.downAt >= 0 ? a : b;
                down.gone = true;
                down.downAt = -1;
                stopRod();
                sound("snap", Sound.ENTITY_ITEM_BREAK, 0.8f, 20);
            }
            return false;
        }

        @Override
        public Vector center() {
            return a.at.clone().add(b.at).multiply(0.5);
        }

        @Override
        public boolean gone() {
            return dead || !pulling();
        }
    }

    // ------------------------------------------------------------------ 그리기

    private static float yawTo(Vector from, Vector at) {
        return (float) Math.toDegrees(Math.atan2(-(at.getX() - from.getX()), at.getZ() - from.getZ()));
    }

    private void poseDolls(int ticks) {
        if (ticks > 0 && !every(2)) return;
        for (Doll d : new Doll[] {a, b}) {
            if (d.p == null) continue;
            Matrix4f m = new Matrix4f().translate((float) (d.at.getX() - pos.getX()), (float) (d.at.getY() - pos.getY()), (float) (d.at.getZ() - pos.getZ()));
            if (d.down()) {
                // 등을 대고 누움(얼굴이 하늘)
                m.translate(0, (float) (-HOVER + 0.3), 0).rotateY((float) Math.toRadians(-d.yaw)).rotateX((float) Math.toRadians(-88));
                if (d.gone) m.scale(1f, 1f, 0.6f);
            } else m.rotateY((float) Math.toRadians(-d.yaw)).rotateX((float) Math.toRadians(d.lean));
            d.p.pose(m, ticks);
        }
    }

    /** 떨어졌거나 쓰러졌을 때 두 인형을 잇는 늘어난 막대기. */
    private void poseRod() {
        boolean show = (phase != Phase.JOINED || a.downAt >= 0 || b.downAt >= 0) && !a.gone && !b.gone;
        if (!show) {
            if (rod != null) {
                rod.remove();
                rod = null;
            }
            return;
        }
        if (rod == null) {
            rod = body.attach("gabe/g117_rod");
            if (rod == null) return;
            rod.follow = false;
        }
        if (!every(2)) return;
        Vector p0 = a.at.clone().add(new Vector(0, 0.12, 0)), p1 = b.at.clone().add(new Vector(0, 0.12, 0));
        if (a.down()) p0.setY(a.at.getY() - HOVER + 0.3);
        if (b.down()) p1.setY(b.at.getY() - HOVER + 0.3);
        Vector d = p1.clone().subtract(p0);
        double len = Math.max(0.05, d.length());
        float ty = (float) Math.atan2(-d.getX(), d.getZ());
        float tp = (float) -Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ()));
        float thick = pulling() ? 1.5f : 1f;
        rod.pose(new Matrix4f().translate((float) (p0.getX() - pos.getX()), (float) (p0.getY() - pos.getY()), (float) (p0.getZ() - pos.getZ()))
                .rotateY(-ty).rotateX(tp).scale(thick, thick, (float) len), 2);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(4);
        for (Doll d : new Doll[] {a, b}) {
            if (d.down()) continue;
            Vector f = fwd(d.yaw);
            out.add(new double[] {d.at.getX() + f.getX() * 0.25, d.at.getY() + HEAD_Y, d.at.getZ() + f.getZ() * 0.25, 0.46});
            out.add(new double[] {d.at.getX() + f.getX() * 0.22, d.at.getY(), d.at.getZ() + f.getZ() * 0.22, 0.42});
        }
        if (out.isEmpty()) out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.5});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        List<Doll> hitDolls = new ArrayList<>(2);
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            // 폭발, 불: 곁의 둘 모두(한 방이 가장 쉬움)
            for (Doll x : new Doll[] {a, b}) {
                if (x.down()) continue;
                if (d.at() == null || x.at.distanceSquared(d.at()) <= 4.0 * 4.0) hitDolls.add(x);
            }
        } else {
            Doll best = null;
            double bd = Double.MAX_VALUE;
            for (Doll x : new Doll[] {a, b}) {
                if (x.down()) continue;
                double ds = d.at() == null ? 0 : Math.min(d.at().distanceSquared(x.at), d.at().distanceSquared(x.at.clone().add(new Vector(0, HEAD_Y, 0))));
                if (ds < bd) {
                    bd = ds;
                    best = x;
                }
            }
            if (best != null) hitDolls.add(best);
        }
        if (hitDolls.isEmpty()) return false;
        double dealt = 0;
        boolean pull = pulling();
        for (Doll x : hitDolls) {
            double before = x.hp;
            x.hp -= d.amount();
            // 짝을 당기는 동안 남은 쪽은 쓰러지지 않음
            if (pull && x.hp < 1) x.hp = 1;
            dealt += before - Math.max(0, x.hp);
        }
        hp = Math.max(0, a.hp) + Math.max(0, b.hp);
        game.mobs.onHurt(this, d, dealt);
        for (Doll x : hitDolls) {
            if (dead) break;
            if (x.hp <= 0 && !x.down()) downed(x, d);
        }
        // 남은 쪽만 남았고 그것도 쓰러짐
        if (!dead && a.down() && b.down() && (a.gone || b.gone)) die(d);
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        stopRod();
        if (rod != null) {
            rod.remove();
            rod = null;
        }
        hp = 0;
        super.die(d);
    }

    @Override
    public void remove() {
        stopRod();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " a=" + Math.round(a.hp) + (a.down() ? (a.gone ? "x" : "v") : "") + " b=" + Math.round(b.hp)
                + (b.down() ? (b.gone ? "x" : "v") : "") + (pulling() ? " pull rod=" + Math.round(rodHp) : "");
    }
}
