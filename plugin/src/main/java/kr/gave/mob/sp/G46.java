package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G46 골라 먹는 게이브(정예, 돌격).
 *
 * <p>도감: 소리 없이 떠서 무리에서 떨어진 사람이나 체력이 낮은 사람을 고른다(표적 점수 = 가장 가까운 동료까지 거리 + 잃은 체력, 장비는 안 봄).
 * 고르면 실눈이 뜨이고(mark), 다가가 4.5 블록 둘레를 한 바퀴 돈 뒤 짧은 숨소리와 함께 덮쳐 크게 한 번 문다(6 피해). 문 채로 3 블록을
 * 무리 반대쪽으로 끌고 가 떼어 놓는다. 고른 사람 곁 5 블록 안에 다른 사람이 붙어 있으면 다가오지 못하고 바깥(6.5 블록)을 맴돈다.
 * 웨이브가 지날수록 한 사람을 여럿이 노린다(24 웨이브 하나, 6 웨이브마다 하나 더, 넷까지).
 * 맞서기: 곁에 붙어 맴돌게 한 뒤 쏜다. 눈동자를 맞히면 2.5배 피해.
 *
 * <p>팩 계약(tools/gabe/sp/g46.py): 모델 gave:gabe/g46 상태 ""(막대기 꼿꼿), "lunge"(입 벌림, 막대기 벌려 침). 고개 갸웃은 body.roll 15.
 * 소리 breath(12, 덮치기 직전에만), bite(16). 원인 글귀 cause_g46. 다른 소리는 내지 않는다(혼자만의 정적).
 */
public final class G46 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.17, FACE_R = 0.45;
    static final float TILT = 15f;
    /** 떠 있는 높이(머리 위 띠), 덮칠 때 높이(몸통). */
    static final double HOVER = 1.9, LUNGE_HOVER = 1.25;
    /** 도는 반지름, 맴도는 반지름, 곁에 붙었다고 보는 거리, 놓치는 거리. */
    static final double CIRCLE_R = 4.5, HOLD_R = 6.5, GUARD_R = 5, LOSE_R = 11;
    /** 한 바퀴 틱, 숨 고르는 틱, 덮치는 최대 틱, 끌고 가는 틱, 물러나는 틱, 다시 덮치기까지. */
    static final int LAP = 70, WINDUP = 8, LUNGE_MAX = 18, DRAG_T = 12, RETREAT_T = 40, COOLDOWN = 110;
    static final double LUNGE_V = 0.55, DAMAGE = 8, DRAG = 3.0;
    /** 붙잡힌 사람이 스스로 떨어지는 좌클릭 수(끌려가는 0.6초 동안). */
    static final int ESCAPE = 8;
    /** 눈동자(몸 기준: 옆, 위, 앞)와 판정 반지름, 피해 배율. */
    static final double EYE_X = 0.225, EYE_Y = 0.03, EYE_Z = 0.31, EYE_R = 0.13, EYE_MUL = 2.5;

    enum Phase { STALK, CIRCLE, WINDUP, LUNGE, DRAG, RETREAT }

    private Phase phase = Phase.STALK;
    private int phaseAt, readyAt, nextPick, followUntil, stuckAt;
    private double stuckX, stuckZ;
    /** 고른 사람(다른 개체가 읽어 포위 수를 센다). */
    Player prey;
    private Player held;
    private boolean orbiting;
    private double orbitA, lap;
    private int orbitDir = 1;
    private final Vector dashDir = new Vector(0, 0, 1), dragFrom = new Vector(), dragDir = new Vector(0, 0, 1), seat = new Vector();
    private double dragLen;

    public G46(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g46", 3.4f, 1.8f);
        body.roll = TILT;
    }

    @Override
    protected void spawned() {
        orbitDir = game.random.nextBoolean() ? 1 : -1;
        nextPick = age + game.random.nextInt(10);
        if (game.director.cageSpawning()) return;
        Player p = nearest(64);
        if (p != null) {
            face(chest(p));
            pitch = 0;
        }
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
        switch (phase) {
            case STALK -> stalk();
            case CIRCLE -> circle();
            case WINDUP -> windup();
            case LUNGE -> lunge();
            case DRAG -> drag();
            case RETREAT -> retreat();
        }
    }

    /** 고른 사람에게 다가가고, 곁에 누가 붙어 있으면 바깥을 맴돈다. 혼자 떨어져 있으면 돌기 시작. */
    private void stalk() {
        state("");
        hover = HOVER;
        if (prey == null || !standing(prey) || age >= nextPick) {
            nextPick = age + 20;
            Player np = pick();
            if (np != prey) {
                prey = np;
                mark(np);
                orbiting = false;
            }
        }
        if (prey == null) {
            follow(speed() * 0.5);
            return;
        }
        double d = flat(prey);
        if (d > HOLD_R + 0.6) {
            orbiting = false;
            approach(prey);
            face(chest(prey));
            return;
        }
        if (guarded(prey) || age < readyAt) {
            orbit(prey, HOLD_R, speed() * 0.8 / HOLD_R);
            face(chest(prey));
            return;
        }
        // 혼자 떨어진 사람: 둘레를 한 바퀴
        lap = 0;
        game.hud.mark(prey, "g46", cd(LAP) + cd(WINDUP) + LUNGE_MAX);
        phase(Phase.CIRCLE);
    }

    /** 한 바퀴 돈다. 그 사이 누가 곁에 붙으면 다시 맴돈다. */
    private void circle() {
        if (!standing(prey)) {
            lose();
            return;
        }
        if (flat(prey) > LOSE_R || guarded(prey)) {
            phase(Phase.STALK);
            return;
        }
        double w = Math.PI * 2 / cd(LAP);
        lap += w;
        orbit(prey, CIRCLE_R, w);
        face(chest(prey));
        if (lap >= Math.PI * 2) {
            hold();
            sound("breath", Sound.ENTITY_PLAYER_BREATH, 1.35f, 12);
            state("lunge");
            body.squash(1.08f, 0.94f, 1.1f, cd(WINDUP));
            phase(Phase.WINDUP);
        }
    }

    /** 짧은 숨(0.4초) 뒤 그 순간의 방향으로 덮친다. */
    private void windup() {
        if (!standing(prey)) {
            lose();
            return;
        }
        hold();
        face(chest(prey));
        if (inPhase() >= cd(WINDUP)) {
            dashDir.copy(toward(prey));
            if (dashDir.lengthSquared() < 1e-6) dashDir.setX(0).setY(0).setZ(1);
            hover = LUNGE_HOVER;
            body.squash(1f, 1f, 1f, 2);
            phase(Phase.LUNGE);
        }
    }

    /** 일직선으로 덮침. 닿은 사람을 문다. */
    private void lunge() {
        dash(dashDir, LUNGE_V * speed() / baseSpeed);
        face(pos.clone().add(dashDir));
        pitch = 0;
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            if (touches(p, 0.15)) {
                bite(p);
                return;
            }
        }
        if (inPhase() >= LUNGE_MAX || wallAhead(0.7)) backOff();
    }

    private void bite(Player p) {
        hit(p, DAMAGE, "cause_g46");
        sound("bite", Sound.ENTITY_FOX_BITE, 0.6f, 16);
        body.squash(1.2f, 0.8f, 0.9f, 2);
        if (!standing(p) || !grab(p, ESCAPE)) {
            backOff();
            return;
        }
        held = p;
        Location l = p.getLocation();
        dragFrom.setX(l.getX()).setY(l.getY()).setZ(l.getZ());
        away(p, dragDir);
        dragLen = DRAG;
        while (dragLen > 0.5 && !game.mobs.flow.walkable(dragFrom.getX() + dragDir.getX() * dragLen, dragFrom.getZ() + dragDir.getZ() * dragLen)) dragLen -= 0.5;
        noPush = true;
        phase(Phase.DRAG);
    }

    /** 문 채로 무리 반대쪽으로 3 블록 끌고 간다(0.6초), 끝나면 놓고 물러난다. */
    private void drag() {
        Player p = held;
        if (p == null || !p.isOnline() || !standing(p) || game.players.grabbedBy(p) != this) {
            endDrag(false);
            return;
        }
        double u = Math.min(1.0, (inPhase() + 1.0) / DRAG_T);
        double x = dragFrom.getX() + dragDir.getX() * dragLen * u, z = dragFrom.getZ() + dragDir.getZ() * dragLen * u;
        seat.setX(x).setY(game.mobs.flow.floor(x, z) + 0.62).setZ(z);
        game.players.carry(p, seat);
        // 끄는 쪽 앞에서 물고 있음
        vel.setX(x + dragDir.getX() * 0.6 - pos.getX()).setY(0).setZ(z + dragDir.getZ() * 0.6 - pos.getZ());
        face(new Vector(x, pos.getY(), z));
        pitch = 0;
        if (u >= 1.0) endDrag(true);
    }

    private void endDrag(boolean release) {
        Player p = held;
        held = null;
        noPush = false;
        if (release && p != null && p.isOnline() && game.players.grabbedBy(p) == this) game.players.release(p);
        backOff();
    }

    /** 덮친 뒤(물었든 빗나갔든) 물러나 숨을 고른다. */
    private void backOff() {
        readyAt = age + cd(COOLDOWN);
        state("");
        hover = HOVER;
        body.squash(1f, 1f, 1f, 4);
        orbiting = false;
        phase(Phase.RETREAT);
    }

    private void retreat() {
        if (prey != null && standing(prey)) {
            Vector t = toward(prey);
            vel.setX(-t.getX() * speed() * 0.9).setY(0).setZ(-t.getZ() * speed() * 0.9);
            face(chest(prey));
        } else hold();
        if (inPhase() >= cd(RETREAT_T)) {
            nextPick = age;
            phase(Phase.STALK);
        }
    }

    private void lose() {
        prey = null;
        mark(null);
        orbiting = false;
        state("");
        hover = HOVER;
        phase(Phase.STALK);
    }

    // ------------------------------------------------------------------ 움직임

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    /** 고른 사람 쪽으로 곧장(막혀서 못 가면 잠깐 흐름장을 따른다). */
    private void approach(Player p) {
        if (age - stuckAt >= 20) {
            if (Math.hypot(pos.getX() - stuckX, pos.getZ() - stuckZ) < 0.4) followUntil = age + 60;
            stuckAt = age;
            stuckX = pos.getX();
            stuckZ = pos.getZ();
        }
        if (age < followUntil) {
            follow(speed());
            return;
        }
        Vector t = toward(p);
        vel.setX(t.getX() * speed()).setY(0).setZ(t.getZ() * speed());
    }

    /** p 둘레 반지름 r 을 각속도 w(라디안/틱)로 돈다. */
    private void orbit(Player p, double r, double w) {
        Location l = p.getLocation();
        if (!orbiting) {
            orbiting = true;
            orbitA = Math.atan2(pos.getZ() - l.getZ(), pos.getX() - l.getX());
        }
        orbitA += w * orbitDir;
        double tx = l.getX() + Math.cos(orbitA) * r, tz = l.getZ() + Math.sin(orbitA) * r;
        double dx = tx - pos.getX(), dz = tz - pos.getZ(), d = Math.hypot(dx, dz);
        double max = Math.max(0.12, w * r * 1.8);
        if (d > max) {
            dx *= max / d;
            dz *= max / d;
        }
        vel.setX(dx).setY(0).setZ(dz);
    }

    // ------------------------------------------------------------------ 고르기

    /** 표적 점수 = 가장 가까운 동료까지 거리(12 까지, 혼자면 12) + 잃은 체력 x 8, 이미 포위 수가 찬 사람은 건너뜀. */
    private Player pick() {
        List<Player> ps = game.mobs.standing();
        if (ps.isEmpty()) return null;
        Map<UUID, Integer> hunters = null;
        for (Gabe g : game.mobs.all()) {
            if (g == this || g.dead || !(g instanceof G46 o) || o.prey == null) continue;
            if (hunters == null) hunters = new HashMap<>();
            hunters.merge(o.prey.getUniqueId(), 1, Integer::sum);
        }
        int cap = Math.max(1, Math.min(4, 1 + (game.wave - sp.tier) / 6));
        Player best = null;
        double bs = -Double.MAX_VALUE;
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            if (p != prey && hunters != null && hunters.getOrDefault(p.getUniqueId(), 0) >= cap) continue;
            Location l = p.getLocation();
            double iso = 12;
            for (int j = 0; j < ps.size(); j++) {
                if (j == i) continue;
                Location q = ps.get(j).getLocation();
                iso = Math.min(iso, Math.hypot(q.getX() - l.getX(), q.getZ() - l.getZ()));
            }
            double score = iso + (1 - game.players.get(p).hpFrac()) * 8 - Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) * 0.03;
            if (p == prey) score += 1.0;
            if (score > bs) {
                bs = score;
                best = p;
            }
        }
        return best;
    }

    /** 고른 사람 곁 GUARD_R 안에 다른 서 있는 사람이 있나. */
    private boolean guarded(Player p) {
        List<Player> ps = game.mobs.standing();
        Location l = p.getLocation();
        for (int i = 0; i < ps.size(); i++) {
            Player q = ps.get(i);
            if (q == p) continue;
            Location ql = q.getLocation();
            double dx = ql.getX() - l.getX(), dz = ql.getZ() - l.getZ();
            if (dx * dx + dz * dz <= GUARD_R * GUARD_R) return true;
        }
        return false;
    }

    /** 끌고 갈 방향: 12 블록 안 다른 사람들의 가운데 반대쪽, 아무도 없으면 덮친 방향 그대로. */
    private void away(Player p, Vector out) {
        List<Player> ps = game.mobs.standing();
        Location l = p.getLocation();
        double x = 0, z = 0;
        int n = 0;
        for (int i = 0; i < ps.size(); i++) {
            Player q = ps.get(i);
            if (q == p) continue;
            Location ql = q.getLocation();
            if (Math.hypot(ql.getX() - l.getX(), ql.getZ() - l.getZ()) > 12) continue;
            x += ql.getX();
            z += ql.getZ();
            n++;
        }
        double dx, dz;
        if (n == 0) {
            dx = dashDir.getX();
            dz = dashDir.getZ();
        } else {
            dx = l.getX() - x / n;
            dz = l.getZ() - z / n;
        }
        double d = Math.hypot(dx, dz);
        if (d < 1e-6) {
            dx = dashDir.getX();
            dz = dashDir.getZ();
            d = Math.max(1e-6, Math.hypot(dx, dz));
        }
        out.setX(dx / d).setY(0).setZ(dz / d);
    }

    // ------------------------------------------------------------------ 맞음

    /** 총알이 눈동자를 지나갔나(앞에서 쏜 것만): 맞은 점에서 탄 방향 직선과 눈 가운데의 거리. */
    private boolean eyeHit(Damage d) {
        Vector at = d.at(), dir = d.dir();
        if (at == null || dir == null || dir.lengthSquared() < 1e-9) return false;
        double yr = Math.toRadians(yaw), rr = Math.toRadians(body == null ? TILT : body.roll);
        double fx = -Math.sin(yr), fz = Math.cos(yr);
        double lx = Math.cos(yr), lz = Math.sin(yr);
        double dl = dir.length();
        double ux = dir.getX() / dl, uy = dir.getY() / dl, uz = dir.getZ() / dl;
        if (ux * fx + uz * fz > -0.2) return false;
        for (int side = -1; side <= 1; side += 2) {
            double ex = EYE_X * side, ey = EYE_Y;
            double rx = ex * Math.cos(rr) - ey * Math.sin(rr), ry = ex * Math.sin(rr) + ey * Math.cos(rr);
            double wx = pos.getX() + lx * rx + fx * EYE_Z - at.getX();
            double wy = pos.getY() + ry - at.getY();
            double wz = pos.getZ() + lz * rx + fz * EYE_Z - at.getZ();
            double t = wx * ux + wy * uy + wz * uz;
            double perp2 = wx * wx + wy * wy + wz * wz - t * t;
            if (perp2 <= EYE_R * EYE_R) return true;
        }
        return false;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if ((t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER) && eyeHit(d)) d = d.times(EYE_MUL);
        return super.damage(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (prey != null ? " prey=" + prey.getName() : "") + (held != null ? " held" : "")
                + String.format(java.util.Locale.ROOT, " lap=%.1f", lap);
    }
}
