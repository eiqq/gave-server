package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.world.Plaza;

/**
 * G105 병풍 게이브(함정, 방패, 2막). 접힌 병풍이 옆걸음으로 스르륵 미끄러져 와, 8블록 안에 동료가 없는 혼자 있는 사람만 골라 그 곁에서
 * 네 폭을 활짝 펴 둘러싸고 탁 접어 가둔다. 혼자인 사람이 없으면 무리 가장자리 사람을 판으로 밀어 떼어 놓는다.
 *
 * <p>도감: 둘러싸 접는 3초 동안 초당 10, 다 접히면 갇힘(이 개체를 잡으면 풀림). 그림 쪽 정면(100도)으로 온 탄은 모두 막고, 뒤쪽 살빛 면은
 * 그대로 맞는다. 밀어내기 6 과 4블록 밀침. 펴서 둘러싸는 동안 경첩 막대기 셋이 드러나고 하나를 끊으면(경첩 체력 60) 다시는 못 접는다.
 * 혼자인 사람에게는 조준점 옆에 이 종 표시가 뜨고 펴지기 전 촤악 소리.
 *
 * <p>바꾼 것: 둘러싼 동안에는 그림이 사방 바깥을 보므로 총탄은 경첩에만 들어간다(폭발과 불은 몸에). 경첩 체력은 몸 최대 체력의 1/7
 * (도감 60/420, 감독 체력 배율을 따름). 갇힌 사람이 있는 동안은 다른 사람을 가두지 않고 밀어내기만 한다.
 *
 * <p>팩 계약(tools/gabe/sp/g105.py): gave:gabe/g105 상태 ""(접힘 걸음), "open", "wrap", "shut", 부위 h1 h2 h3. 소리 fold, swish, shove, snap.
 * 글귀 cause_g105, cause_g105_shove.
 */
public final class G105 extends Gabe implements Detachable {

    static final double SPEED = 0.2, HOVER = 1.55;
    /** 혼자의 기준(이 블록 안에 서 있는 동료가 없음), 펴기 시작 거리, 밀칠 거리. */
    static final double ALONE_R = 8, OPEN_R = 2.6, SHOVE_R = 1.6;
    /** 예고(폄), 접는 틱, 밀친 뒤 쉬기, 경첩 끊긴 뒤 휘청. */
    static final int OPEN_T = 20, FOLD_T = 60, SHOVE_CD = 60, REST = 40, STAGGER = 30, MARK_EVERY = 20;
    static final double FOLD_DPS = 10, SHOVE = 6, SHOVE_V = 0.95, SHOVE_UP = 0.32;
    static final int ESCAPE = 12;
    /** 정면으로 막는 반각(도). */
    static final double FRONT_HALF = 50;
    /** 경첩(둘러쌈 상태, 몸 기준 왼쪽 x, 앞 z), 구 반지름, 체력 몫. g105.py zig((0, 90, 180, 270)) 와 같게. */
    static final double[][] HINGE = {{0.45, -0.45}, {0.45, 0.45}, {-0.45, 0.45}};
    static final double HINGE_R = 0.22, HINGE_FRAC = 60.0 / 420.0;

    enum Phase { SLIDE, OPEN, WRAP, SHOVE, REST }

    private Phase phase = Phase.SLIDE;
    private int phaseAt, shoveAt = -100, markAt = -100;
    private Player prey, held;
    private boolean broken, holding;
    private double hingeHp = -1;
    private final double[] hinge = new double[3];

    public G105(Game game, Species sp) {
        super(game, sp);
        radius = 0.6;
        hover = HOVER;
        baseSpeed = SPEED;
        ragKick = 0.5;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g105", 3.6f, 3.6f);
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
        if (hingeHp < 0) {
            hingeHp = Math.max(1, maxHp * HINGE_FRAC);
            for (int i = 0; i < 3; i++) hinge[i] = hingeHp;
        }
        if (held != null && (!held.isOnline() || game.players.grabbedBy(held) != this)) let();
        holding = !game.players.trappedIn(this).isEmpty();
        switch (phase) {
            case SLIDE -> slide();
            case OPEN -> open();
            case WRAP -> wrap();
            case SHOVE -> shove();
            case REST -> rest();
        }
    }

    /** 사람 둘레 ALONE_R 안에 서 있는 다른 사람이 없나. */
    private boolean alone(Player p) {
        Vector c = Mobs.chest(p);
        double r2 = ALONE_R * ALONE_R;
        for (Player q : game.mobs.standing()) {
            if (q == p) continue;
            if (Mobs.chest(q).distanceSquared(c) <= r2) return false;
        }
        return true;
    }

    /** 표적: 가두기가 되면 가장 가까운 혼자인 사람, 아니면 가운데에서 가장 먼(가장자리) 사람. */
    private Player pick(boolean wantAlone) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            if (game.players.grabbedBy(p) != null) continue;
            if (wantAlone) {
                if (!alone(p)) continue;
                double d = dist(p);
                if (d < bd) {
                    bd = d;
                    best = p;
                }
            } else {
                double r = -Plaza.radius(p.getLocation().getX(), p.getLocation().getZ()) + dist(p) * 0.25;
                if (r < bd) {
                    bd = r;
                    best = p;
                }
            }
        }
        return best;
    }

    /** 접힌 채 옆걸음으로 스르륵. 혼자인 사람 곁에서 편다, 아니면 가장자리 사람을 밀러 간다. */
    private void slide() {
        state(holding ? "shut" : "");
        boolean canTrap = !broken && !holding;
        Player lone = canTrap ? pick(true) : null;
        Player p = lone != null ? lone : pick(false);
        if (p == null) {
            follow(speed());
            return;
        }
        if (prey != p) {
            prey = p;
            mark(p);
        }
        face(chest(p));
        if (lone != null && age - markAt >= MARK_EVERY) {
            markAt = age;
            game.hud.mark(p, "g105", MARK_EVERY + 10);
        }
        double d = dist(p);
        if (lone != null && d <= OPEN_R) {
            hold();
            state("open");
            sound("swish", Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 24);
            phase(Phase.OPEN);
            return;
        }
        if (lone == null && d <= SHOVE_R + 0.6 && age - shoveAt >= cd(SHOVE_CD)) {
            hold();
            state("open");
            phase(Phase.SHOVE);
            return;
        }
        // 멀면 흐름장, 가까우면 곧장(옆걸음: 몸은 사람을 보고 옆으로 미끄러짐)
        if (d > 10) follow(speed());
        else seek(chest(p), speed());
        if (every(14)) sound("fold", Sound.BLOCK_WOOD_STEP, 0.7f, 24);
    }

    /** 펴서 둘러쌀 준비(예고 20틱). 그동안 혼자가 아니게 되거나 멀어지면 접고 돌아감. */
    private void open() {
        hold();
        Player p = prey;
        if (!standing(p) || !alone(p) || dist(p) > OPEN_R + 2.5) {
            calm();
            return;
        }
        face(chest(p));
        if (inPhase() < cd(OPEN_T)) return;
        if (game.players.grabbedBy(p) != null || !grab(p, ESCAPE)) {
            calm();
            return;
        }
        held = p;
        noPush = true;
        state("wrap");
        sound("fold", Sound.BLOCK_WOOD_PLACE, 0.6f, 24);
        phase(Phase.WRAP);
    }

    /** 둘러싸 접는 3초: 사람 자리로 겹쳐 서고 조여 듦(초당 10). 다 접히면 가둠. */
    private void wrap() {
        Player p = held;
        if (p == null) {
            calm();
            return;
        }
        Vector at = p.getLocation().toVector();
        at.setY(floor() + hover);
        vel.copy(at.subtract(pos).setY(0).multiply(0.5));
        int t = inPhase();
        float k = 1f - 0.22f * Math.min(1f, t / (float) cd(FOLD_T));
        body.squash(k, 1f, k, 2);
        if (t > 0 && t % 10 == 0) {
            hit(p, FOLD_DPS / 2, "cause_g105");
            sound("fold", Sound.BLOCK_WOOD_PLACE, 0.6f, 24);
        }
        if (held == null) return;
        if (t >= cd(FOLD_T)) {
            held = null;
            body.squash(1f, 1f, 1f, 4);
            noPush = false;
            game.players.trap(p, this);
            state("shut");
            sound("fold", Sound.BLOCK_WOOD_PLACE, 0.5f, 24);
            mark(null);
            prey = null;
            phase(Phase.REST);
        }
    }

    /** 판으로 밀어 무리에서 떼어 냄. */
    private void shove() {
        hold();
        Player p = prey;
        if (!standing(p)) {
            calm();
            return;
        }
        face(chest(p));
        if (inPhase() < cd(8)) return;
        if (dist(p) <= SHOVE_R + 1.2) {
            // 가운데에서 바깥으로(가운데에 있으면 병풍에서 먼 쪽으로)
            Vector out = p.getLocation().toVector().subtract(new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5)).setY(0);
            if (out.lengthSquared() < 4) out = toward(p);
            hit(p, SHOVE, "cause_g105_shove");
            knock(p, out, SHOVE_V, SHOVE_UP);
            sound("shove", Sound.BLOCK_WOOD_BREAK, 0.6f, 16);
            body.squash(1.06f, 1f, 0.9f, 2);
        }
        shoveAt = age;
        calm();
    }

    private void rest() {
        hold();
        if (inPhase() == 6) body.squash(1f, 1f, 1f, 4);
        if (inPhase() >= cd(REST)) phase(Phase.SLIDE);
    }

    /** 표적을 풀고 접힌 걸음으로. */
    private void calm() {
        if (held != null) {
            Player p = held;
            held = null;
            game.players.release(p);
        }
        noPush = false;
        body.squash(1f, 1f, 1f, 4);
        mark(null);
        prey = null;
        state(holding ? "shut" : "");
        phase(Phase.REST);
    }

    private void let() {
        held = null;
        if (phase == Phase.WRAP) calm();
    }

    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim != held) return;
        held = null;
        if (!dead && phase == Phase.WRAP) calm();
    }

    // ------------------------------------------------------------------ 맞음

    /** 몸 왼쪽(yaw 0 일 때 +X), 앞. */
    private double[] axes() {
        double r = Math.toRadians(yaw);
        return new double[] {Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r)};
    }

    private Vector hingeAt(int i, double up) {
        double[] a = axes();
        double x = HINGE[i][0], z = HINGE[i][1];
        return new Vector(pos.getX() + a[0] * x + a[2] * z, pos.getY() + up, pos.getZ() + a[1] * x + a[3] * z);
    }

    private boolean wrapping() {
        return phase == Phase.WRAP && !broken;
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(9);
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        out.add(new double[] {x, y - 0.6, z, 0.55});
        out.add(new double[] {x, y + 0.5, z, 0.55});
        out.add(new double[] {x, y + 1.35, z, 0.5});
        if ("open".equals(state)) {
            double[] a = axes();
            for (int s = -1; s <= 1; s += 2) {
                out.add(new double[] {x + a[0] * 1.05 * s, y, z + a[1] * 1.05 * s, 0.5});
                out.add(new double[] {x + a[0] * 1.05 * s, y + 1.1, z + a[1] * 1.05 * s, 0.5});
            }
        }
        if (wrapping()) {
            for (int i = 0; i < 3; i++) {
                if (hinge[i] <= 0) continue;
                for (double up : new double[] {-0.5, 0.6}) {
                    Vector h = hingeAt(i, up);
                    out.add(new double[] {h.getX(), h.getY(), h.getZ(), HINGE_R});
                }
            }
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (!gun) return super.damage(d);
        if (wrapping() && d.at() != null) {
            // 둘러싼 동안: 경첩만 맞음(그림이 사방 바깥)
            for (int i = 0; i < 3; i++) {
                if (hinge[i] <= 0) continue;
                Vector h = hingeAt(i, 0);
                if (Math.hypot(d.at().getX() - h.getX(), d.at().getZ() - h.getZ()) > HINGE_R + 0.28) continue;
                hinge[i] -= d.amount();
                game.mobs.onHurt(this, d, 0);
                if (d.by() != null) game.hud.hit(d.by(), false);
                if (hinge[i] <= 0) cutHinge(i);
                return true;
            }
            ting(d);
            return true;
        }
        if (frontal(d)) {
            ting(d);
            return true;
        }
        return super.damage(d);
    }

    /** 탄이 그림 쪽 정면(반각 50도)에서 왔나. */
    private boolean frontal(Damage d) {
        Vector from = null;
        if (d.dir() != null && d.dir().lengthSquared() > 1e-6) from = d.dir().clone().multiply(-1);
        else if (d.by() != null) from = d.by().getLocation().toVector().subtract(pos);
        if (from == null) return false;
        from.setY(0);
        if (from.lengthSquared() < 1e-6) return false;
        from.normalize();
        double[] a = axes();
        double dot = from.getX() * a[2] + from.getZ() * a[3];
        return dot >= Math.cos(Math.toRadians(FRONT_HALF));
    }

    /** 막음: 피해 없이 맞은 표시와 짧은 소리. */
    private void ting(Damage d) {
        game.mobs.onHurt(this, d, 0);
        if (age % 4 == 0) sound("fold", Sound.BLOCK_WOOD_HIT, 1.2f, 12);
    }

    private void cutHinge(int i) {
        broken = true;
        body.look.on.add("h" + (i + 1));
        sound("snap", Sound.BLOCK_WOOD_BREAK, 0.8f, 24);
        Player p = held;
        held = null;
        if (p != null) game.players.release(p);
        noPush = false;
        body.squash(1f, 1f, 1f, 3);
        mark(null);
        prey = null;
        state("open");
        phase(Phase.REST);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (broken ? " broken" : "") + (held != null ? " held=" + held.getName() : "")
                + (holding ? " holding" : "") + String.format(java.util.Locale.ROOT, " hinge=%.0f/%.0f/%.0f", hinge[0], hinge[1], hinge[2]);
    }
}
