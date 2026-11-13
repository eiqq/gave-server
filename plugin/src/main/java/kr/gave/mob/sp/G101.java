package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * G101 나만 보이는 게이브(방해, 돌격, 2막). 막대기 네모 틀 속 납작한 얼굴 액자가 한 사람(주인)에게만 보이고 들린다. 가장자리에서 걸어오지 않고
 * 주인 정면 12~16블록에 불쑥 나타나 또각또각 다가온다. 주인이 동료 곁(3블록 안)이면 느리고(0.15/틱), 혼자면 빠르다(0.35/틱). 닿으면 틀 모서리로
 * 찍고(12) 주인 머리에 액자처럼 끼운다: 3초 동안 화면 가장자리에 틀이 끼어 시야가 좁아지고 초당 6(연타로 벗음).
 *
 * <p>다른 사람 눈에는 안 보이고 총알도 지나간다(주인이 쏜 탄만 맞음). 조명탄 빛 안이나 정화(reveal) 뒤 5초는 모두에게 드러나 누구나 쏠 수 있다.
 * 동료 눈에는 끼인 사람 머리 둘레의 희미한 네모 테만 보이고, 그 사람을 좌클릭(돕기)해 떼어 줄 수 있다.
 *
 * <p>구현: 늘 untargetable(탄이 지나감) + 쏠 수 있는 것(Mobs.Shootable)으로 주인의 탄만 받는다(탄이 주인 눈에서 나왔는지로 가림).
 * 판 규칙(물어뜯기, 돌아보기, 덮치기)에서 뺌(noRules). 끼운 동안은 붙잡힘(걸을 수는 있음, 연타 8번) + 덮개 g101_frame + 남들에게 머리 테.
 * 끼우기가 끝나면 다시 주인 정면 12~16블록에 나타난다.
 *
 * <p>바꾼 것: '사람 셋에 하나꼴, 한 사람에 하나'는 감독이 정할 몫이라(요청) 여기서는 주인을 고를 때 아직 액자가 붙지 않은 사람을 먼저 고른다.
 *
 * <p>팩 계약(tools/gabe/sp/g101.py): gave:gabe/g101(상태 ""), gave:gabe/g101_ring(머리 칸), 덮개 g101_frame. 소리 clack, strike, fit(모두 주인에게만).
 * 글귀 cause_g101.
 */
public final class G101 extends Gabe implements Detachable {

    static final double HOVER = 1.15, SLOW = 0.15, FAST = 0.35, NEAR_ALLY = 3, APPEAR_MIN = 12, APPEAR_MAX = 16;
    static final double STRIKE = 12, FRAME_DPS = 6, BODY_R = 0.62;
    static final int FRAME_T = 60, ESCAPE = 8, REVEAL_T = 100, COOLDOWN = 40, CLACK_EVERY = 9, MARK_EVERY = 20;

    private Player owner;
    private boolean framing, ending;
    private int frameAt, revealUntil = -1, readyAt, nextClack, markAt = -100;
    private Mine mine;

    public G101(Game game, Species sp) {
        super(game, sp);
        radius = BODY_R;
        hover = HOVER;
        baseSpeed = SLOW;
        untargetable = true; // 남의 탄은 지나감(주인 탄은 Mine 이 받음)
        noRules = true;
        noPush = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g101", 1.6f, 2.4f);
        body.look.palette |= Look.PAL_PALE; // 빛바랜 색
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Player p = pickOwner();
        if (p != null) own(p);
        appear();
        mine = new Mine();
        game.mobs.addShootable(mine);
        nextClack = age + 5;
    }

    // ------------------------------------------------------------------ 주인

    /** 아직 액자가 붙지 않은(가장 적게 붙은) 서 있는 사람. */
    private Player pickOwner() {
        Player best = null;
        int bc = Integer.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            int c = 0;
            for (Gabe g : game.mobs.all()) if (g != this && g instanceof G101 o && !o.dead && o.owner == p) c++;
            if (c < bc || c == bc && game.random.nextBoolean()) {
                bc = c;
                best = p;
            }
        }
        return best;
    }

    private void own(Player p) {
        Player old = owner;
        owner = p;
        mark(p);
        if (old != null && old != p && old.isOnline()) body.hide(old, true);
        if (p != null) body.hide(p, false);
        hideOthers();
    }

    /** 주인 아닌 모두에게 숨김(드러난 동안은 모두에게 보임). 새로 들어온 사람도 20틱마다. */
    private void hideOthers() {
        boolean shown = revealed();
        for (Player p : game.world.getPlayers()) {
            if (p == owner) continue;
            if (shown) {
                if (body.hidden(p)) body.hide(p, false);
            } else if (!body.hidden(p)) body.hide(p, true);
        }
    }

    private boolean revealed() {
        return age < revealUntil;
    }

    /** 주인 정면 12~16블록(사람이 갈 수 있는 R 48 안)에 불쑥 나타남. */
    private void appear() {
        if (owner == null) return;
        Location l = owner.getLocation();
        double r = Math.toRadians(l.getYaw());
        double fx = -Math.sin(r), fz = Math.cos(r);
        double d = APPEAR_MIN + game.random.nextDouble() * (APPEAR_MAX - APPEAR_MIN);
        double x = l.getX() + fx * d, z = l.getZ() + fz * d;
        double rr = Plaza.radius(x, z);
        if (rr > Plaza.R_ROAM - 2) {
            // 원 밖이면 가운데 쪽으로 당김
            double k = (Plaza.R_ROAM - 2) / rr;
            x = Plaza.CX + 0.5 + (x - Plaza.CX - 0.5) * k;
            z = Plaza.CZ + 0.5 + (z - Plaza.CZ - 0.5) * k;
        }
        pos.setX(x).setZ(z).setY(game.mobs.flow.floor(x, z) + hover);
        vel.zero();
        face(chest(owner));
        body.pose(0);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (owner == null || !owner.isOnline() || !standing(owner) && !framing) {
            Player p = pickOwner();
            if (p != null && p != owner) {
                own(p);
                appear();
            } else if (p == null) {
                hold();
                return;
            }
        }
        if (game.shop.inFlare(pos, 0)) revealUntil = Math.max(revealUntil, age + 20);
        untargetable = !revealed();
        if (every(20) || age == revealUntil) hideOthers();
        if (framing) frame();
        else walk();
    }

    private void walk() {
        Player o = owner;
        face(chest(o));
        if (age < readyAt) {
            hold();
            return;
        }
        boolean company = false;
        Vector c = Mobs.chest(o);
        for (Player q : game.mobs.standing()) {
            if (q != o && Mobs.chest(q).distanceSquared(c) <= NEAR_ALLY * NEAR_ALLY) {
                company = true;
                break;
            }
        }
        double v = (company ? SLOW : FAST) * Math.max(0.7, speed() / baseSpeed);
        if (dist(o) > 20) follow(v);
        else seek(chest(o), v);
        if (age >= nextClack) {
            mine("clack", Sound.BLOCK_WOODEN_DOOR_CLOSE, 1.6f);
            nextClack = age + (company ? CLACK_EVERY * 2 : CLACK_EVERY);
        }
        if (age - markAt >= MARK_EVERY) {
            markAt = age;
            game.hud.mark(o, "g101", MARK_EVERY + 10);
        }
        if (touches(o, 0.15)) strike(o);
    }

    /** 틀 모서리로 찍고 머리에 액자처럼 끼움. */
    private void strike(Player o) {
        hit(o, STRIKE, "cause_g101");
        mine("strike", Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f);
        if (!standing(o) || game.players.grabbedBy(o) != null || !game.players.cover(o, "g101_frame", FRAME_T)) {
            reset();
            return;
        }
        if (!grab(o, ESCAPE)) {
            game.players.uncover(o);
            reset();
            return;
        }
        PState s = game.players.get(o);
        s.grabPin = false; // 걸을 수는 있음
        ItemStack ring = Body.item("gabe/g101_ring", "", new LookState(), game.worldTime());
        game.players.attachHead(o, ring, this);
        framing = true;
        frameAt = age;
        body.model(Body.EMPTY);
        mine("fit", Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 1.2f);
    }

    /** 끼운 동안: 주인 머리를 따라다니며 초당 6. 3초 뒤 벗겨짐. */
    private void frame() {
        Player o = owner;
        if (o == null || !standing(o)) {
            endFrame();
            return;
        }
        pos.copy(o.getEyeLocation().toVector());
        vel.zero();
        int t = age - frameAt;
        if (t > 0 && t % 10 == 0) hit(o, FRAME_DPS / 2, "cause_g101");
        if (t >= FRAME_T) endFrame();
    }

    private void endFrame() {
        endFrame(true);
    }

    private void endFrame(boolean again) {
        if (!framing) return;
        framing = false;
        ending = true;
        try {
            Player o = owner;
            if (o != null && o.isOnline()) {
                PState s = game.players.get(o);
                if (s.headBy == this) game.players.attachHead(o, null);
                if (game.players.grabbedBy(o) == this) game.players.release(o);
                if ("g101_frame".equals(s.cover)) game.players.uncover(o);
            }
        } finally {
            ending = false;
        }
        body.model("gabe/g101");
        if (again) reset();
    }

    /** 다시 주인 정면에 나타나 잠깐 섰다가 다가옴. */
    private void reset() {
        appear();
        readyAt = age + cd(COOLDOWN);
    }

    /** 주인에게만 들리는 소리. */
    private void mine(String name, Sound fb, float fbPitch) {
        if (owner == null || game.director.silent()) return;
        Props.soundTo(owner, loc(), "gabe." + sp.key() + "." + name, fb, 1f, 0.94f + game.random.nextFloat() * 0.12f, fbPitch, -1);
    }

    @Override
    public void detached(Player victim, Player helper, String how) {
        if (ending || victim != owner || !framing) return;
        endFrame();
    }

    // ------------------------------------------------------------------ 드러남, 맞음

    @Override
    public void reveal() {
        revealUntil = age + REVEAL_T;
        untargetable = false;
        hideOthers();
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(1);
        if (!framing) out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), BODY_R});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || framing) return false;
        // 숨은 동안은 주인 것만(드러나면 누구나)
        if (!revealed() && d.by() != owner) return false;
        hp -= d.amount();
        game.mobs.onHurt(this, d);
        if (hp <= 0) die(d);
        return true;
    }

    /** 주인이 쏜 탄만 받는 맞는 자리(숨은 동안). */
    private final class Mine implements Mobs.Shootable {
        private boolean fromOwner(Vector o, Vector dir) {
            Player p = owner;
            if (p == null || !p.isOnline()) return false;
            Vector eye = p.getEyeLocation().toVector();
            Vector rel = o.clone().subtract(eye);
            double l2 = rel.lengthSquared();
            if (l2 < 0.36) return true;
            // 관통 탄: 주인 눈에서 나간 광선 위의 점에서 이어 감
            double along = rel.dot(dir);
            return along > 0 && l2 - along * along < 0.09;
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (dead || framing || revealed() || !fromOwner(o, dir)) return Double.POSITIVE_INFINITY;
            double ox = pos.getX() - o.getX(), oy = pos.getY() - o.getY(), oz = pos.getZ() - o.getZ();
            double t = ox * dir.getX() + oy * dir.getY() + oz * dir.getZ();
            double rr = BODY_R + 0.25;
            if (t < -rr) return Double.POSITIVE_INFINITY;
            double m2 = ox * ox + oy * oy + oz * oz - t * t;
            if (m2 > rr * rr) return Double.POSITIVE_INFINITY;
            double at = Math.max(0, t - Math.sqrt(rr * rr - m2));
            return at <= max ? at : Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean shot(Damage d) {
            if (dead || framing || d.by() != owner) return false;
            return game.mobs.damage(G101.this, d);
        }

        @Override
        public Vector center() {
            return pos.clone();
        }

        @Override
        public boolean gone() {
            return dead || removed;
        }
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (framing) endFrame(false);
        super.die(d);
    }

    @Override
    public void remove() {
        if (framing) endFrame(false);
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " owner=" + (owner == null ? "-" : owner.getName()) + (framing ? " framing" : "") + (revealed() ? " shown" : "");
    }
}
