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
import kr.gave.mob.Species;

/**
 * G76 머리 돋는 게이브(튼튼이, 방패). 빙 둘린 머리를 자르면 둘씩 돋아, 불로 지져야 하는 몸.
 *
 * <p>도감: 빠른 걸음으로 다가와 머리들이 제각각 곁의 사람을 물려고 목을 늘인다(1 에서 2.5블록). 머리마다 물기 7, 머리 수만큼 따로. 머리 하나(체력
 * 25)를 쏴 떨어뜨리면 2초 뒤 그 목에서 둘이 돋는다(최대 아홉: 그 목에 하나, 곁의 빈 목에 하나). 떨어뜨린 2초 안에 불이나 폭발로 그 목을 지지면 안
 * 돋는다. 몸통 앞 아래 반쯤 묻힌 얼굴은 맞으면 피해 두 배(낮게 옆에서 쏜다). 머리가 다 지져지면 몸통으로 들이받는다.
 *
 * <p>팩 계약(tools/gabe/sp/g76.py): 모델 gave:gabe/g76(상태 "", 부위 n1..n9 머리, long1..long9 늘인 목, cut1..cut9 그루터기, burnt1..burnt9 지진
 * 목), 부품 gave:gabe/g76_drop(떨어진 머리). 목 자리 식은 팩 slot() 과 같게. 소리 snarl, bite, pop, sear. 원인 글귀 cause_g76.
 */
public final class G76 extends Gabe {

    static final double SPEED = 0.2, HOVER = 0.95;
    static final int SLOTS = 9, START = 5, MAX_HEADS = 9;
    static final double HEAD_HP = 25, BITE = 7, RAM = 7, REACH = 1.6, BITE_R = 1.8, HEAD_R = 0.32, FACE_R = 0.28, FACE_MUL = 2.0;
    static final int REGROW = 40, LUNGE_T = 12, LUNGE_HIT = 5, BITE_CD = 18, SNARL_CD = 90, RAM_CD = 30, DROP_LIFE = 50;
    static final double RING_R = 0.5, RING_Y = 0.62, SHORT_LEN = 0.75, LONG_LEN = 2.3, HEAD = 0.55;
    static final double SHORT_TILT = Math.toRadians(40), LONG_TILT = Math.toRadians(80);
    /** 자리마다 [짧은 목/긴 목][머리 x, y, z](몸 기준, +X 왼쪽), 그루터기 자리. */
    static final double[][][] HEAD_AT = new double[SLOTS][2][3];
    static final double[][] STUMP_AT = new double[SLOTS][3];

    static {
        for (int i = 0; i < SLOTS; i++) {
            double a = Math.toRadians(360.0 * i / SLOTS), rx = Math.sin(a), rz = Math.cos(a);
            double ox = rx * RING_R, oy = RING_Y, oz = rz * RING_R;
            for (int l = 0; l < 2; l++) {
                double t = l == 1 ? LONG_TILT : SHORT_TILT, len = (l == 1 ? LONG_LEN : SHORT_LEN) + HEAD * 0.45;
                HEAD_AT[i][l][0] = ox + rx * Math.sin(t) * len;
                HEAD_AT[i][l][1] = oy + Math.cos(t) * len;
                HEAD_AT[i][l][2] = oz + rz * Math.sin(t) * len;
            }
            STUMP_AT[i][0] = ox + rx * Math.sin(SHORT_TILT) * 0.2;
            STUMP_AT[i][1] = oy + Math.cos(SHORT_TILT) * 0.2;
            STUMP_AT[i][2] = oz + rz * Math.sin(SHORT_TILT) * 0.2;
        }
    }

    enum Slot { EMPTY, HEAD, CUT, BURNT }

    private final Slot[] slot = new Slot[SLOTS];
    private final double[] headHp = new double[SLOTS];
    private final int[] cutAt = new int[SLOTS], lungeAt = new int[SLOTS], readyAt = new int[SLOTS];
    private final Player[] lungeAim = new Player[SLOTS];
    private int snarlAt, ramAt = -100;
    private double headHpMax = HEAD_HP;
    private boolean scaled;

    /** 떨어진 머리(부품, 세계 자리). */
    private final List<Body.Part> drops = new ArrayList<>();
    private final List<Vector> dropAt = new ArrayList<>();
    private final List<Integer> dropBorn = new ArrayList<>();

    public G76(Game game, Species sp) {
        super(game, sp);
        radius = 0.75;
        hover = HOVER;
        baseSpeed = SPEED;
        ragKick = 0.6;
        for (int i = 0; i < SLOTS; i++) {
            slot[i] = Slot.EMPTY;
            lungeAt[i] = -1;
        }
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g76", 3.0f, 2.6f);
        // 처음 머리 다섯(빙 둘러 고르게)
        for (int n = 0; n < START; n++) setHead(Math.round(n * SLOTS / (float) START) % SLOTS);
    }

    @Override
    protected void spawned() {
        snarlAt = age + game.random.nextInt(60);
    }

    private void setHead(int i) {
        slot[i] = Slot.HEAD;
        headHp[i] = headHpMax;
        lungeAt[i] = -1;
        readyAt[i] = age + 10 + game.random.nextInt(20);
        body.look.on.add("n" + (i + 1));
        body.look.on.remove("long" + (i + 1));
        body.look.on.remove("cut" + (i + 1));
    }

    private int heads() {
        int n = 0;
        for (Slot s : slot) if (s == Slot.HEAD) n++;
        return n;
    }

    private int growing() {
        int n = 0;
        for (Slot s : slot) if (s == Slot.CUT) n++;
        return n;
    }

    /** 몸 기준 점 → 세계. */
    private Vector world(double x, double y, double z) {
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r), lx = fz, lz = -fx;
        return new Vector(pos.getX() + lx * x + fx * z, pos.getY() + y, pos.getZ() + lz * x + fz * z);
    }

    private Vector headAt(int i) {
        double[] h = HEAD_AT[i][lungeAt[i] >= 0 ? 1 : 0];
        return world(h[0], h[1], h[2]);
    }

    private Vector longAt(int i) {
        double[] h = HEAD_AT[i][1];
        return world(h[0], h[1], h[2]);
    }

    private Vector rootAt(int i) {
        double a = Math.toRadians(360.0 * i / SLOTS);
        return world(Math.sin(a) * RING_R, RING_Y, Math.cos(a) * RING_R);
    }

    /** 사람 몸통이 그 목이 늘어나는 길(뿌리에서 늘인 머리까지)에서 얼마나 떨어졌나. */
    private double reach(int i, Player q) {
        Vector c = chest(q).add(new Vector(0, 0.3, 0));
        return G41.segDist(c, rootAt(i), longAt(i));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!scaled) {
            scaled = true;
            double k = sp.hp > 0 ? maxHp / sp.hp : 1;
            headHpMax = HEAD_HP * k;
            for (int i = 0; i < SLOTS; i++) if (slot[i] == Slot.HEAD) headHp[i] = headHpMax;
        }
        dropTick();
        regrow();
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            if (vel.lengthSquared() > 1e-6) face(pos.clone().add(vel));
            pitch = 0;
            return;
        }
        double d = dist(p);
        // 다가옴: 곁에서는 머리들이 닿을 만큼만(몸통은 머리 그늘에)
        if (d > 2.2) follow(speed());
        else vel.multiply(0.5);
        if (d > 5 && vel.lengthSquared() > 1e-6) face(pos.clone().add(vel));
        else turnTo(chest(p), 12f);
        pitch = 0;
        // 걸음 흔들림
        if (every(6)) body.squash(1.03f, (age / 6) % 2 == 0 ? 0.95f : 1.0f, 1.03f, 3);
        if (d < 14 && age >= snarlAt) {
            snarlAt = age + cd(SNARL_CD) + game.random.nextInt(30);
            sound("snarl", Sound.ENTITY_RAVAGER_ROAR, 1.4f, 24);
        }
        mark(d <= 6 ? p : null);
        lunges();
        // 머리가 하나도 없고 돋을 것도 없으면 몸통으로 들이받음
        if (heads() == 0 && growing() == 0 && age - ramAt >= cd(RAM_CD) && touches(p, 0.3)) {
            ramAt = age;
            hit(p, RAM, "cause_g76");
            knock(p, toward(p), 0.6, 0.2);
        }
    }

    /** 머리마다 따로: 늘인 목 끝에 닿는 사람이 있으면 덮침. */
    private void lunges() {
        List<Player> standing = game.mobs.standing();
        for (int i = 0; i < SLOTS; i++) {
            if (slot[i] != Slot.HEAD) continue;
            if (lungeAt[i] >= 0) {
                int t = age - lungeAt[i];
                if (t == LUNGE_HIT) {
                    Player q = lungeAim[i];
                    if (standing(q) && reach(i, q) <= BITE_R) {
                        hit(q, BITE, "cause_g76");
                        sound("bite", Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.3f, 16);
                    }
                }
                if (t >= LUNGE_T) {
                    lungeAt[i] = -1;
                    lungeAim[i] = null;
                    body.look.on.remove("long" + (i + 1));
                    readyAt[i] = age + cd(BITE_CD) + game.random.nextInt(10);
                }
                continue;
            }
            if (age < readyAt[i]) continue;
            Player best = null;
            double bd = REACH;
            for (Player q : standing) {
                if (dist(q) > LONG_LEN + 2) continue;
                double d = reach(i, q);
                if (d < bd) {
                    bd = d;
                    best = q;
                }
            }
            if (best == null) continue;
            lungeAt[i] = age;
            lungeAim[i] = best;
            body.look.on.add("long" + (i + 1));
        }
    }

    /** 떨어뜨린 지 2초: 지지지 않았으면 그 목에 하나, 곁의 빈 목에 하나. */
    private void regrow() {
        for (int i = 0; i < SLOTS; i++) {
            if (slot[i] != Slot.CUT || age - cutAt[i] < REGROW) continue;
            setHead(i);
            int extra = -1;
            for (int off = 1; off <= SLOTS / 2 && extra < 0; off++) {
                for (int sg : new int[] {1, -1}) {
                    int j = Math.floorMod(i + sg * off, SLOTS);
                    if (slot[j] == Slot.EMPTY) {
                        extra = j;
                        break;
                    }
                }
            }
            if (extra >= 0 && heads() < MAX_HEADS) setHead(extra);
            sound("pop", Sound.ENTITY_CHICKEN_EGG, 0.8f, 20);
            body.squash(1.06f, 1.1f, 1.06f, 3);
        }
    }

    // ------------------------------------------------------------------ 떨어진 머리

    private void dropHead(int i) {
        Body.Part part = body.attach("gabe/g76_drop");
        if (part == null) return;
        part.follow = false;
        Vector at = headAt(i);
        drops.add(part);
        dropAt.add(at);
        dropBorn.add(age);
        poseDrop(part, at, 0, 0);
    }

    private void poseDrop(Body.Part part, Vector at, float spinDeg, int ticks) {
        part.pose(new Matrix4f().translate((float) (at.getX() - pos.getX()), (float) (at.getY() - pos.getY()), (float) (at.getZ() - pos.getZ()))
                .rotateY((float) Math.toRadians(-yaw + spinDeg)).rotateX((float) Math.toRadians(-70)), ticks);
    }

    private void dropTick() {
        for (int j = drops.size() - 1; j >= 0; j--) {
            Body.Part part = drops.get(j);
            Vector at = dropAt.get(j);
            int t = age - dropBorn.get(j);
            if (t >= DROP_LIFE) {
                part.remove();
                drops.remove(j);
                dropAt.remove(j);
                dropBorn.remove(j);
                continue;
            }
            double fl = game.mobs.flow.floor(at.getX(), at.getZ()) + 0.22;
            if (at.getY() > fl) at.setY(Math.max(fl, at.getY() - 0.12 - t * 0.04));
            // 땅에서 혼자 웃다(흔들) 꺼짐(가라앉음)
            if (t > DROP_LIFE - 12) at.setY(at.getY() - 0.04);
            poseDrop(part, at, (float) (Math.sin(t * 0.8) * 12), 1);
        }
    }

    private void clearDrops() {
        for (Body.Part p : drops) p.remove();
        drops.clear();
        dropAt.clear();
        dropBorn.clear();
    }

    // ------------------------------------------------------------------ 맞음

    private Vector faceAt() {
        return world(0, -0.2, 0.7);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(SLOTS + 2);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.72});
        Vector f = faceAt();
        out.add(new double[] {f.getX(), f.getY(), f.getZ(), FACE_R});
        for (int i = 0; i < SLOTS; i++) {
            if (slot[i] != Slot.HEAD) continue;
            Vector h = headAt(i);
            out.add(new double[] {h.getX(), h.getY(), h.getZ(), HEAD_R});
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean heat = t == DamageType.FIRE || t == DamageType.EXPLOSION;
        if (heat) {
            // 지지기: 떨어뜨린 지 2초 안의 그루터기(위치를 모르면 모두)
            boolean seared = false;
            for (int i = 0; i < SLOTS; i++) {
                if (slot[i] != Slot.CUT || age - cutAt[i] > REGROW) continue;
                double[] s = STUMP_AT[i];
                if (d.at() != null && world(s[0], s[1], s[2]).distanceSquared(d.at()) > 3.0 * 3.0) continue;
                burn(i);
                seared = true;
            }
            if (seared) sound("sear", Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 20);
            // 폭발은 곁의 머리들도
            if (t == DamageType.EXPLOSION && d.at() != null) {
                for (int i = 0; i < SLOTS; i++) {
                    if (slot[i] != Slot.HEAD || headAt(i).distanceSquared(d.at()) > 2.5 * 2.5) continue;
                    headHp[i] -= d.amount();
                    if (headHp[i] <= 0) {
                        cut(i);
                        burn(i);
                    }
                }
            }
            return super.damage(d);
        }
        if (d.at() != null) {
            double body2 = d.at().distanceSquared(pos) - 0.72 * 0.72;
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < SLOTS; i++) {
                if (slot[i] != Slot.HEAD) continue;
                double ds = headAt(i).distanceSquared(d.at());
                if (ds < bd) {
                    bd = ds;
                    best = i;
                }
            }
            if (best >= 0 && bd <= (HEAD_R + 0.3) * (HEAD_R + 0.3) && bd - HEAD_R * HEAD_R < body2) {
                headHp[best] -= d.amount();
                game.mobs.onHurt(this, d, 0);
                if (d.by() != null) game.hud.hit(d.by(), false);
                if (headHp[best] <= 0) cut(best);
                return true;
            }
            if (d.at().distanceSquared(faceAt()) <= (FACE_R + 0.15) * (FACE_R + 0.15)) return super.damage(d.times(FACE_MUL));
        }
        return super.damage(d);
    }

    /** 머리가 떨어짐: 2초 뒤 둘이 돋는다(지지지 않으면). */
    private void cut(int i) {
        if (slot[i] != Slot.HEAD) return;
        dropHead(i);
        slot[i] = Slot.CUT;
        cutAt[i] = age;
        lungeAt[i] = -1;
        lungeAim[i] = null;
        body.look.on.remove("n" + (i + 1));
        body.look.on.remove("long" + (i + 1));
        body.look.on.add("cut" + (i + 1));
        sound("bite", Sound.ENTITY_SLIME_SQUISH, 0.7f, 16);
    }

    private void burn(int i) {
        slot[i] = Slot.BURNT;
        body.look.on.remove("cut" + (i + 1));
        body.look.on.remove("n" + (i + 1));
        body.look.on.remove("long" + (i + 1));
        body.look.on.add("burnt" + (i + 1));
    }

    @Override
    public void die(Damage d) {
        clearDrops();
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " heads=" + heads() + " growing=" + growing();
    }
}
