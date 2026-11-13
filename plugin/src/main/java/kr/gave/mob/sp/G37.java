package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Band;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * G37 보자기 게이브(방해). 얼굴을 막대기 둘 사이에 가로 3, 세로 2 블록으로 늘린 넓적한 막이 앞으로 기운 채 3블록 높이를 미끄러져 온다
 * ('푸웅', 바닥에 큰 그림자). 옆에서 보면 얇은 선.
 *
 * <p>도감: 표적(다른 보자기가 안 노리는 가장 가까운 사람) 머리 위에 오면 막을 눕혀 덮쳐 내려와 사람을 싸서 매듭을 짓고(붙잡힘, 1 피해, 화면 덮개
 * g37_cloth) 들어 올린다. 싸인 사람을 들고 3초 동안 15블록쯤 떨어진 게이브 떼 한복판(없으면 동료들에게서 먼 쪽 15블록)으로 날아가 떨어뜨린다.
 * 황야 기준: 떨어뜨릴 곳은 중앙 원 밖, 사람이 갈 수 있는 끝(48블록) 안. 싸인 본인은 돕기 연타로 최대 1초 늦춘다(풀지는 못함). 들려 가는
 * 동안 동료가 매듭(자루 위 막대기 둘이 엇갈린 곳)을 쏘면 그 자리에 떨어진다. 이 종을 죽여도 그 자리에 떨어진다. 넓어서 정면, 아래에서 맞히기
 * 쉽고 체력이 낮다.
 *
 * <p>도감과 다른 점: 디스플레이 하나(막은 세로 띠 여섯의 자세 접기 물결, 보따리는 상태 wrap). 바닥 그림자는 디스플레이의 바닐라 그림자.
 * 떨어뜨리는 곳의 '진형 밖'은 서 있는 사람들 가운데에서 먼 쪽으로 어림한다.
 *
 * <p>팩 계약(tools/gabe/sp/g37.py): 모델 {@code gave:gabe/g37}(반으로 줄여 구움) 상태 {@code ""}(펼침), {@code "wrap"}(보따리), 덮개
 * {@code g37_cloth}. 소리 puff(32), wrap(16), drop(16). 글귀 {@code cause_g37}, {@code notice_g37_carry}.
 */
public final class G37 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.17;
    /** 미끄러지는 높이, 들고 가는 높이(바닥 위, 몸 중심), 앞으로 기운 각(도). */
    static final double FLY_H = 3.0, CARRY_H = 4.6;
    static final float LEAN = 38f;
    /** 이 수평 거리 안이면 찍음, 이 안이면 머리 위(덮칠 자리). */
    static final double MARK_R = 9, OVER_R = 1.1;
    /** 머리 위에서 막을 눕히는 틱, 덮쳐 내려오는 최대 틱, 싸서 매듭 짓는 틱. */
    static final int OVER_T = 8, SWOOP_T = 14, WRAP_T = 12;
    /** 들고 가는 거리와 틱(3초), 본인 연타로 늦추는 최대 틱과 연타 하나당 틱, 내려놓는 틱. */
    static final double CARRY_DIST = 15;
    static final int CARRY_T = 60, DELAY_MAX = 20, DELAY_PER_HIT = 2, DROP_T = 8;
    /** 놓친 뒤, 떨어뜨린 뒤 물러나 쉬는 틱. 싸는 피해. */
    static final int COOLDOWN = 100;
    static final double WRAP_DMG = 4;
    /** 사람이 갈 수 있는 끝(황야 R_ROAM, Plaza 에 상수가 생기면 그것으로), 떨어뜨릴 곳의 안팎 여유. */
    static final double ROAM = kr.gave.world.Plaza.R_ROAM, ROAM_PAD = 3, SAFE_PAD = 3;
    /** 맞는 구: 펼친 막(가운데, 양옆), 보따리, 매듭. 매듭 높이(몸 중심에서). */
    static final double SHEET_R = 0.6, SHEET_SIDE = 0.95, SACK_R = 0.75, KNOT_R = 0.32, KNOT_UP = 1.3;

    enum Phase { GLIDE, OVER, SWOOP, WRAP, CARRY, DROP, AWAY }

    private Phase phase = Phase.GLIDE;
    private int phaseAt, nextPuff, carryEnd;
    private Player prey, held;
    private final Vector dest = new Vector();
    private final Vector awayDir = new Vector(1, 0, 0);
    private final List<double[]> hit = new ArrayList<>(3);

    public G37(Game game, Species sp) {
        super(game, sp);
        radius = 0.7;
        flying = true; // 사람을 들고 넘어가야 해서 높이는 직접 잡는다
        hover = FLY_H;
        baseSpeed = SPEED;
        for (int i = 0; i < 3; i++) hit.add(new double[4]);
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g37", 7.0f, 5.0f);
        // 바닥의 큰 그림자(디스플레이 그림자: 닻이 몸 중심 2.5 아래라 3블록 높이에서 바닥에 닿음)
        body.anchor.setShadowRadius(2.2f);
        body.anchor.setShadowStrength(1.0f);
    }

    @Override
    protected void spawned() {
        nextPuff = age + game.random.nextInt(40);
        pitch = LEAN;
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
            case GLIDE -> glide();
            case OVER -> over();
            case SWOOP -> swoop();
            case WRAP -> wrap();
            case CARRY -> carry();
            case DROP -> dropping();
            case AWAY -> away();
        }
    }

    /** 높이 want(바닥 위)로 부드럽게. */
    private void height(double want) {
        double dy = floor() + want - pos.getY();
        vel.setY(Math.max(-0.3, Math.min(0.3, dy * 0.25)));
    }

    /** 수평으로 (x, z) 쪽 v 블록/틱(가까우면 남은 만큼만). */
    private double toward(double x, double z, double v) {
        double dx = x - pos.getX(), dz = z - pos.getZ(), d = Math.hypot(dx, dz);
        if (d < 1e-6) {
            vel.setX(0).setZ(0);
            return 0;
        }
        double k = Math.min(v, d) / d;
        vel.setX(dx * k).setZ(dz * k);
        return d;
    }

    private void puff(int every) {
        if (age < nextPuff) return;
        sound("puff", Sound.ITEM_ELYTRA_FLYING, 0.6f, 32);
        nextPuff = age + every + game.random.nextInt(20);
    }

    /** 표적 머리 위로 미끄러져 감(막은 앞으로 기움). 9블록 안이면 찍는다. */
    private void glide() {
        state("");
        if (prey == null || !valid(prey) || every(20)) prey = choose();
        puff(50);
        if (prey == null) {
            if (target != null) mark(null);
            follow(speed());
            height(FLY_H);
            pitch = LEAN;
            return;
        }
        Location l = prey.getLocation();
        double d = toward(l.getX(), l.getZ(), speed());
        height(FLY_H + 0.25 * Math.sin((age + id * 7) * 0.09));
        if (d > 0.3) yaw = (float) Math.toDegrees(Math.atan2(-(l.getX() - pos.getX()), l.getZ() - pos.getZ()));
        pitch = LEAN;
        if (d <= MARK_R && target != prey) mark(prey);
        if (d <= OVER_R) phase(Phase.OVER);
    }

    /** 다른 보자기가 노리지 않는, 붙잡히지 않은 가장 가까운 서 있는 사람. */
    private Player choose() {
        Player best = null;
        double bd = Double.MAX_VALUE;
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            if (!valid(p) || taken(p)) continue;
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ(), d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private boolean valid(Player p) {
        return p != null && p.isOnline() && standing(p) && game.players.grabbedBy(p) == null;
    }

    private boolean taken(Player p) {
        for (Gabe g : game.mobs.all()) {
            if (g != this && !g.dead && g instanceof G37 o && (o.prey == p || o.held == p)) return true;
        }
        return false;
    }

    /** 머리 위에서 막을 눕힘(덮칠 예고). */
    private void over() {
        if (!valid(prey)) {
            miss();
            return;
        }
        Location l = prey.getLocation();
        toward(l.getX(), l.getZ(), 0.3);
        height(FLY_H);
        pitch = LEAN + (90f - LEAN) * Math.min(1f, (inPhase() + 1) / (float) cd(OVER_T));
        body.pose(2);
        if (inPhase() >= cd(OVER_T)) {
            sound("puff", Sound.ITEM_ELYTRA_FLYING, 0.8f, 32);
            phase(Phase.SWOOP);
        }
    }

    /** 덮쳐 내려옴: 닿으면 싼다. */
    private void swoop() {
        if (!valid(prey)) {
            miss();
            return;
        }
        Location l = prey.getLocation();
        toward(l.getX(), l.getZ(), 0.35);
        double want = l.getY() + 1.0;
        vel.setY(Math.max(-0.45, Math.min(0.2, want - pos.getY())));
        pitch = 90f;
        double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
        if (dx * dx + dz * dz <= 0.8 * 0.8 && pos.getY() - want <= 0.5) {
            bundle(prey);
            return;
        }
        if (inPhase() >= SWOOP_T) miss();
    }

    private void bundle(Player p) {
        if (!grab(p, 999)) {
            miss();
            return;
        }
        PState s = game.players.get(p);
        s.grabDetach = false; // 동료 돕기로는 못 품: 매듭을 쏘거나 이 개체를 잡아야 함
        held = p;
        mark(p);
        noPush = true;
        hit(p, WRAP_DMG, "cause_g37");
        if (held == null) return; // 그 피해로 쓰러짐
        game.players.cover(p, "g37_cloth", CARRY_T + WRAP_T + DELAY_MAX + DROP_T + 20);
        body.hide(p, true);
        pitch = 0;
        state("wrap");
        body.squash(1.3f, 0.8f, 1.3f, 0);
        body.pose(0);
        sound("wrap", Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 16);
        game.hud.notice("notice_g37_carry", Species.G37, 60);
        phase(Phase.WRAP);
        seat(p);
    }

    /** 싸인 사람 자리: 몸통 가운데가 보따리 가운데(좌석 = 몸 중심 - 0.3, 발은 좌석 0.6 아래). */
    private void seat(Player p) {
        game.players.carry(p, new Vector(pos.getX(), pos.getY() - 0.3, pos.getZ()));
    }

    private boolean holding() {
        Player p = held;
        return p != null && p.isOnline() && game.players.grabbedBy(p) == this;
    }

    /** 꽁꽁 싸서 매듭을 지음(제자리). 끝나면 떨어뜨릴 곳을 정해 들어 올린다. */
    private void wrap() {
        if (!holding()) {
            lost();
            return;
        }
        vel.zero();
        pitch = 0;
        if (inPhase() == 2) body.squash(1f, 1f, 1f, WRAP_T - 3);
        seat(held);
        if (inPhase() >= WRAP_T) {
            dest.copy(dropPoint(held));
            carryEnd = age + CARRY_T;
            sound("puff", Sound.ITEM_ELYTRA_FLYING, 0.7f, 32);
            phase(Phase.CARRY);
        }
    }

    /** 들고 날아감: 3초(연타로 최대 1초 늦춤)에 맞춰 떨어뜨릴 곳 위로. */
    private void carry() {
        if (!holding()) {
            lost();
            return;
        }
        int delay = Math.min(DELAY_MAX, game.players.get(held).escapeHits * DELAY_PER_HIT);
        int left = Math.max(1, carryEnd + delay - age);
        double dx = dest.getX() - pos.getX(), dz = dest.getZ() - pos.getZ(), d = Math.hypot(dx, dz);
        double v = Math.min(0.6, d / left);
        toward(dest.getX(), dest.getZ(), v);
        height(CARRY_H);
        if (d > 0.3) yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = 0;
        // 버둥대는 보따리
        if (every(6)) body.roll = (float) Math.sin(age * 0.9) * 5f;
        puff(40);
        seat(held);
        if (d <= 0.5 || age >= carryEnd + delay) phase(Phase.DROP);
    }

    /** 내려놓음: 바닥 가까이 내려와 풀어 떨어뜨린다. */
    private void dropping() {
        if (!holding()) {
            lost();
            return;
        }
        vel.setX(0).setZ(0);
        double want = floor() + 1.3;
        vel.setY(Math.max(-0.5, Math.min(0.1, want - pos.getY())));
        seat(held);
        if (pos.getY() - want <= 0.3 || inPhase() >= DROP_T + 6) {
            Player p = held;
            held = null;
            free(p);
            if (game.players.grabbedBy(p) == this) game.players.release(p);
            sound("drop", Sound.ENTITY_PLAYER_BIG_FALL, 0.8f, 16);
            flee(p.getLocation());
        }
    }

    /** 덮개와 숨김을 걷음(싸임이 끝날 때 한 번). */
    private void free(Player p) {
        if (p == null) return;
        PState s = game.players.get(p);
        if ("g37_cloth".equals(s.cover)) game.players.uncover(p);
        body.hide(p, false);
    }

    /** 싸던 사람을 놓침(매듭이 풀림, 누가 놓아 줌): 그 자리에 떨어지고 물러남. */
    private void lost() {
        Player p = held;
        held = null;
        free(p);
        flee(p != null ? p.getLocation() : loc());
    }

    /** 표적을 놓침(사라짐, 붙잡기 실패). */
    private void miss() {
        flee(loc());
    }

    private void flee(Location from) {
        noPush = false;
        mark(null);
        prey = null;
        body.roll = 0;
        state("");
        pitch = LEAN;
        body.pose(4);
        Vector d = pos.clone().subtract(from.toVector()).setY(0);
        if (d.lengthSquared() < 0.04) {
            double a = game.random.nextDouble() * Math.PI * 2;
            d = new Vector(Math.cos(a), 0, Math.sin(a));
        }
        awayDir.copy(d.normalize());
        phase(Phase.AWAY);
    }

    /** 물러나 쉼: 사람에게서 멀어지며 높이를 되찾는다. */
    private void away() {
        int t = inPhase();
        double v = t < 40 ? speed() * 0.8 : 0;
        vel.setX(awayDir.getX() * v).setZ(awayDir.getZ() * v);
        height(FLY_H + 0.6);
        pitch = LEAN;
        if (v > 0) yaw = (float) Math.toDegrees(Math.atan2(-awayDir.getX(), awayDir.getZ()));
        puff(60);
        if (t >= cd(COOLDOWN)) phase(Phase.GLIDE);
    }

    // ------------------------------------------------------------------ 떨어뜨릴 곳

    /**
     * 15블록쯤 떨어진 게이브 떼 한복판: 그 사람에게서 10~22블록인 땅 게이브 가운데 3.5블록 안 이웃이 가장 많은 것의 자리. 없으면 서 있는 사람들
     * 가운데에서 먼 쪽으로 15블록. 중앙 원 밖, 사람이 갈 수 있는 끝 안으로 당긴다.
     */
    private Vector dropPoint(Player p) {
        Location l = p.getLocation();
        double px = l.getX(), pz = l.getZ();
        List<Gabe> all = game.mobs.all();
        Gabe best = null;
        int bestN = 0;
        double bestOff = Double.MAX_VALUE;
        for (int i = 0; i < all.size(); i++) {
            Gabe g = all.get(i);
            if (!crowd(g)) continue;
            double d = Math.hypot(g.pos.getX() - px, g.pos.getZ() - pz);
            if (d < 10 || d > 22 || !inside(g.pos.getX(), g.pos.getZ())) continue;
            int n = 0;
            for (int j = 0; j < all.size(); j++) {
                Gabe o = all.get(j);
                if (o == g || !crowd(o)) continue;
                double ox = o.pos.getX() - g.pos.getX(), oz = o.pos.getZ() - g.pos.getZ();
                if (ox * ox + oz * oz <= 3.5 * 3.5) n++;
            }
            double off = Math.abs(d - CARRY_DIST);
            if (n > bestN || n == bestN && off < bestOff) {
                best = g;
                bestN = n;
                bestOff = off;
            }
        }
        if (best != null && bestN >= 1) return new Vector(best.pos.getX(), 0, best.pos.getZ());
        // 동료들 가운데에서 먼 쪽(혼자면 광장 가운데에서 먼 쪽)
        double cx = 0, cz = 0;
        int n = 0;
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Player q = ps.get(i);
            if (q == p) continue;
            cx += q.getLocation().getX();
            cz += q.getLocation().getZ();
            n++;
        }
        if (n == 0) {
            cx = Plaza.CX;
            cz = Plaza.CZ;
        } else {
            cx /= n;
            cz /= n;
        }
        double dx = px - cx, dz = pz - cz, d = Math.hypot(dx, dz);
        if (d < 0.5) {
            double a = game.random.nextDouble() * Math.PI * 2;
            dx = Math.cos(a);
            dz = Math.sin(a);
            d = 1;
        }
        double x = px + dx / d * CARRY_DIST, z = pz + dz / d * CARRY_DIST;
        // 끝과 중앙 원 사이로 당김
        double r = Math.hypot(x - Plaza.CX, z - Plaza.CZ);
        double hi = ROAM - ROAM_PAD, lo = Plaza.R_SAFE + SAFE_PAD;
        if (r > hi) {
            x = Plaza.CX + (x - Plaza.CX) / r * hi;
            z = Plaza.CZ + (z - Plaza.CZ) / r * hi;
        } else if (r < lo && r > 1e-6) {
            x = Plaza.CX + (x - Plaza.CX) / r * lo;
            z = Plaza.CZ + (z - Plaza.CZ) / r * lo;
        }
        return new Vector(x, 0, z);
    }

    private boolean crowd(Gabe g) {
        return g != this && !g.dead && !g.disguised && g.sp.band != Band.SKY && !(g instanceof G37) && !game.director.inCage(g);
    }

    private static boolean inside(double x, double z) {
        double r = Math.hypot(x - Plaza.CX, z - Plaza.CZ);
        return r <= ROAM - ROAM_PAD && r >= Plaza.R_SAFE + SAFE_PAD;
    }

    // ------------------------------------------------------------------ 떼어짐, 맞음, 죽음

    @Override
    public void detached(Player p, Player helper, String how) {
        if (p == null || p != held) return;
        held = null;
        free(p);
        if (!dead) flee(p.getLocation());
    }

    private boolean bundled() {
        return phase == Phase.WRAP || phase == Phase.CARRY || phase == Phase.DROP;
    }

    @Override
    public List<double[]> spheres() {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (bundled()) {
            // 보따리와 그 위 매듭
            set(0, x, y, z, SACK_R);
            set(1, x, y + KNOT_UP, z, KNOT_R);
            return hit.subList(0, 2);
        }
        // 펼친 막: 가운데와 양옆
        double r = Math.toRadians(yaw);
        double lx = Math.cos(r) * SHEET_SIDE, lz = Math.sin(r) * SHEET_SIDE;
        set(0, x, y, z, SHEET_R);
        set(1, x + lx, y, z + lz, SHEET_R);
        set(2, x - lx, y, z - lz, SHEET_R);
        return hit;
    }

    private void set(int i, double x, double y, double z, double r) {
        double[] s = hit.get(i);
        s[0] = x;
        s[1] = y;
        s[2] = z;
        s[3] = r;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (bundled() && held != null && d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            // 매듭(자루 위 막대기 둘이 엇갈린 곳)에 맞음: 풀려서 그 자리에 떨어뜨림
            double knot = d.at().distance(new Vector(pos.getX(), pos.getY() + KNOT_UP, pos.getZ())) - KNOT_R;
            double sack = d.at().distance(pos) - SACK_R;
            if (knot < sack) {
                Player p = held;
                held = null;
                free(p);
                if (game.players.grabbedBy(p) == this) game.players.release(p);
                sound("drop", Sound.ENTITY_PLAYER_BIG_FALL, 0.8f, 16);
                flee(p.getLocation());
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        Player p = held;
        held = null;
        super.die(d);
        if (p != null) {
            free(p);
            if (game.players.grabbedBy(p) == this) game.players.release(p);
        }
    }

    @Override
    public void remove() {
        Player p = held;
        held = null;
        if (p != null) {
            free(p);
            if (game.players.grabbedBy(p) == this) game.players.release(p);
        }
        super.remove();
    }

    /** 시험용. */
    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (prey != null ? " prey=" + prey.getName() : "") + (held != null ? " held=" + held.getName() : "")
                + (bundled() ? String.format(java.util.Locale.ROOT, " dest=%.1f,%.1f", dest.getX(), dest.getZ()) : "");
    }
}
