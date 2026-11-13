package kr.gave.mob.sp;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G190 초침 게이브(4막 32라운드, 방해 + 정예). 하늘에서 나와 한 사람(임자, 다른 초침이 안 고른 사람 먼저)의 머리 위 9블록에 떠 따라다니며 내려다본다.
 * 둘레의 눈금 막대기 열둘이 게이지(240 = 24블록): 임자가 걸은 수평 거리만큼 줄고(밀림, 띄움, 좌석, 붙잡힘, 순간 이동은 안 셈), 10틱 동안 0.2블록 안에
 * 서 있으면 틱마다 1 씩 찬다. 임자 조준점 아래 게이지(Hud.meter, 열두 칸 몫), 눈금 넷 아래면 임자에게 표시 깜빡임. 걸을 때 눈금이 하나 접힐 때마다 임자에게만
 * 크게 들리는 째깍(VOLUME). 다 줄면 종소리 0.5초 뒤 수직으로 내리꽂힘: 임자 50 + 1초 묶임, 곁 2블록 12. 내리꽂은 뒤 다시 떠올라 처음부터 센다.
 *
 * <p>팩 계약(tools/gabe/sp/g190.py): gabe/g190(상태 "", "dive", 부위 t1..t12 = 그 눈금 접힘). 소리 tick(24), bell(24), dive(24).
 * 글귀 cause_g190, cause_g190_near.
 *
 * <p>도감과 다른 점: 서 있을 때 차는 빠르기는 틱마다 1(엔진 줄의 0.5 보다 '걷는 빠르기의 절반'에 가깝게). 화면 가장자리 붉은 뜀은 VEIL 그리기가 아직 없어
 * Hud.mark(임자, g190) 깜빡임으로 대신. 내리꽂는 자리는 내리꽂기 시작 때의 임자 자리(종소리 0.5초 동안은 따라감).
 */
public final class G190 extends Gabe {

    static final double ABOVE = 9.0, FOLLOW = 0.5, GAUGE = 240, WALK_K = 10, REFILL = 1.0, STILL_R = 0.2, DIVE_V = 1.4;
    static final double DIVE_DMG = 50, NEAR_DMG = 12, NEAR_R = 2.0, HIT_R = 1.7;
    static final int STILL_T = 10, BELL_T = 10, ROOT_T = 20, RISE_T = 40, LOW_TICKS = 4;

    enum Phase { SEEK, HOVER, BELL, DIVE, RISE }

    private Phase phase = Phase.SEEK;
    private int phaseAt;
    private Player owner;
    private double gauge = GAUGE;
    private final Vector lastFeet = new Vector(), stillAt = new Vector(), diveTo = new Vector();
    private boolean feetKnown;
    private int stillSince, folded, dives;
    private long meterAt;

    public G190(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        flying = true;
        baseSpeed = FOLLOW;
        noPush = true;
        noBite = true;
        ragKick = 0.8;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g190", 1.2f, 1.2f);
        // 아래를 보는 얼굴은 아이템 셰이더의 기본 빛만 받아 시커멓다: 밝기를 올려 메움(boss_b2)
        if (body.anchor != null) body.anchor.setBrightness(new Display.Brightness(13, 10));
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pickOwner();
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 다른 초침이 안 고른 서 있는 사람 가운데 가장 가까운 사람(모두 골랐으면 가장 가까운 사람). */
    private void pickOwner() {
        Player best = null, any = null;
        double bd = Double.MAX_VALUE, ad = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            double d = p.getLocation().toVector().distanceSquared(pos);
            if (d < ad) {
                ad = d;
                any = p;
            }
            if (taken(p.getUniqueId())) continue;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        Player o = best != null ? best : any;
        if (o != owner) {
            owner = o;
            feetKnown = false;
            gauge = GAUGE;
            unfoldAll();
        }
        mark(owner);
    }

    private boolean taken(UUID id) {
        for (Gabe g : game.mobs.all()) {
            if (g == this || g.dead || !(g instanceof G190 o)) continue;
            if (o.owner != null && o.owner.getUniqueId().equals(id)) return true;
        }
        return false;
    }

    private Vector above(Player p) {
        Location e = p.getEyeLocation();
        return new Vector(e.getX(), e.getY() + ABOVE, e.getZ());
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (owner == null || !standing(owner)) {
            if (phase == Phase.DIVE) phase(Phase.RISE);
            pickOwner();
            if (owner == null) {
                follow(speed());
                return;
            }
        }
        mark(owner);
        switch (phase) {
            case SEEK -> seekOwner();
            case HOVER -> hover();
            case BELL -> bell();
            case DIVE -> dive();
            case RISE -> rise();
        }
    }

    private void seekOwner() {
        Vector to = above(owner);
        pitch = 20;
        face(to);
        seek(to, Math.max(speed(), 0.35));
        if (to.distance(pos) < 2.0) {
            phase(Phase.HOVER);
            feetKnown = false;
        }
    }

    private void keepAbove(double sp) {
        Vector to = above(owner);
        Vector d = to.clone().subtract(pos);
        double l = d.length();
        if (l < 1e-3) vel.zero();
        else vel.copy(d.multiply(Math.min(sp, l) / l));
        pitch = 90;
    }

    private void hover() {
        keepAbove(0.7);
        if (above(owner).distance(pos) > 12) {
            phase(Phase.SEEK);
            return;
        }
        count();
        if (gauge <= 0) {
            phase(Phase.BELL);
            state("dive");
            soundFor(owner, "bell", Sound.BLOCK_BELL_USE, 1f);
            sound("bell", Sound.BLOCK_BELL_USE, 24);
            body.squash(1.2f, 1.2f, 0.8f, 3);
        }
    }

    /** 임자 걸음 읽기: 걸은 만큼 깎고 서 있으면 참. 눈금, 게이지, 째깍. */
    private void count() {
        Location l = owner.getLocation();
        Vector f = l.toVector();
        if (!feetKnown) {
            lastFeet.copy(f);
            stillAt.copy(f);
            stillSince = age;
            feetKnown = true;
        }
        double step = Math.hypot(f.getX() - lastFeet.getX(), f.getZ() - lastFeet.getZ());
        lastFeet.copy(f);
        boolean carried = game.players.riding(owner) || game.players.launched(owner) || game.players.grabbedBy(owner) != null || owner.isInsideVehicle();
        if (step > 1.0 || carried) step = 0;   // 순간 이동, 좌석, 띄움, 붙잡힘은 안 셈
        if (Math.hypot(f.getX() - stillAt.getX(), f.getZ() - stillAt.getZ()) > STILL_R) {
            stillAt.copy(f);
            stillSince = age;
        }
        if (step > 0.02) gauge -= step * WALK_K;
        else if (age - stillSince >= STILL_T) gauge = Math.min(GAUGE, gauge + REFILL);
        gauge = Math.max(0, gauge);
        int want = (int) Math.min(12, Math.max(0, 12 - Math.ceil(gauge / (GAUGE / 12))));
        if (want > folded) {
            soundFor(owner, "tick", Sound.UI_BUTTON_CLICK, 1f);
            body.squash(1.06f, 1.06f, 0.94f, 2);
        }
        while (folded < want) body.look.on.add("t" + (++folded));
        while (folded > want) body.look.on.remove("t" + (folded--));
        if (game.now() - meterAt >= 5) {
            meterAt = game.now();
            game.hud.meter(owner, "g190", gauge / GAUGE, 12);
            if (12 - folded <= LOW_TICKS) game.hud.mark(owner, "g190", 12);
        }
    }

    private void unfoldAll() {
        for (int i = 1; i <= 12; i++) body.look.on.remove("t" + i);
        folded = 0;
    }

    private void bell() {
        keepAbove(0.9);
        if (inPhase() >= BELL_T) {
            Location l = owner.getLocation();
            diveTo.setX(l.getX()).setY(l.getY() + 0.9).setZ(l.getZ());
            phase(Phase.DIVE);
        }
    }

    private void dive() {
        pitch = 90;
        Vector d = diveTo.clone().subtract(pos);
        double l = d.length();
        if (l > DIVE_V) {
            vel.copy(d.multiply(DIVE_V / l));
            return;
        }
        vel.zero();
        dives++;
        sound("dive", Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 24);
        body.squash(1.4f, 1.4f, 0.6f, 3);
        G129Kit.ring(this, diveTo, 1.6, 10);
        for (Player q : game.mobs.standing()) {
            Location ql = q.getLocation();
            double h = Math.hypot(ql.getX() - diveTo.getX(), ql.getZ() - diveTo.getZ());
            if (Math.abs(ql.getY() + 0.9 - diveTo.getY()) > 3) continue;
            if (q == owner && h <= HIT_R) {
                hit(q, DIVE_DMG, "cause_g190");
                game.players.root(q, ROOT_T, "g190");
            } else if (h <= NEAR_R) {
                hit(q, NEAR_DMG, "cause_g190_near");
            }
        }
        phase(Phase.RISE);
    }

    private void rise() {
        if (inPhase() < 6) {
            hold();
            return;
        }
        state("");
        gauge = GAUGE;
        unfoldAll();
        if (owner != null) game.hud.meter(owner, "g190", 1, 12);
        keepAbove(0.45);
        if (owner == null || above(owner).distance(pos) < 1.5 || inPhase() > RISE_T + 60) {
            feetKnown = false;
            phase(Phase.HOVER);
        }
    }

    @Override
    public void remove() {
        clearMeter();
        super.remove();
    }

    @Override
    public void die(kr.gave.mob.Damage d) {
        clearMeter();
        super.die(d);
    }

    private void clearMeter() {
        if (owner != null && owner.isOnline()) game.hud.meter(owner, "g190", -1, 1);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " owner=" + (owner == null ? "-" : owner.getName()) + " gauge=" + Math.round(gauge) + " folded=" + folded
            + " dives=" + dives;
    }
}
