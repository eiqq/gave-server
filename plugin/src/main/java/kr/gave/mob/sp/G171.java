package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * G171 빈틈 노리는 게이브(3막 떼, 돌격). 어깨 높이 1 블록의 마른 개 꼴 넷에서 여섯이 한 패로 와서(감독이 낸 하나가 첫 틱에 셋에서 다섯을 더 데려옴)
 * 한 사람을 고르고, 7 블록 거리를 두고 서로 반대쪽에 서서 둘레를 천천히 돈다. 그 사람이 재장전을 시작하거나, 넘어지거나 묶이거나 느려지거나,
 * 체력이 40 아래로 내려가는 순간 패 전체가 몸을 낮췄다가(으르렁이 뚝 끊김) 한꺼번에 덮친다: 한 마리마다 11 피해, 재장전 끊김(RELOAD, 처음부터 다시),
 * 0.4초 넘어짐(묶임 8틱). 5초 동안 빈틈이 없으면 하나가 먼저 달려들어 물고(5) 빠진다. 고른 사람이 쓰러지면 그 곁을 돌며 일으키러 온 사람을
 * 기다렸다가(돕는 동안은 못 쏘니 빈틈) 그 사람을 덮친다.
 *
 * <p>팩 계약(tools/gabe/sp/g171.py): 모델 gave:gabe/g171 상태 ""(걷기), "low"(몸 낮춤), "leap"(덮침). 소리 growl(20), lunge(20), bite(16).
 * 글귀 cause_g171. 찍힌 사람 조준점 옆에 이 종 표시(Hud.mark).
 */
public final class G171 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.2, CIRCLE_V = 0.16, HOVER = 0.8, HOVER_LOW = 0.6;
    /** 패: 데려오는 수(3..5), 도는 반지름, 패가 사람을 고르는 거리, 돌기 시작하는 거리. */
    static final int FOLLOW_MIN = 3, FOLLOW_MAX = 5;
    static final double RING = 7, PICK_R = 22, CIRCLE_R = 14;
    /** 빈틈: 체력 문턱, 느려짐 문턱. 덮치기: 몸 낮춤 틱, 돌진 빠르기와 최대 틱, 피해, 넘어짐 틱. 빈틈 없이 5초면 하나가 물고 빠짐(5). */
    static final double LOW_HP = 40, SLOW_MIN = 0.08, LEAP_V = 0.62, POUNCE_DMG = 11, SNAP_DMG = 5;
    static final int LOW_T = 8, LEAP_MAX = 16, TRIP = 8, IDLE_SNAP = 100, BACK_T = 30, POUNCE_GAP = 40;

    enum Phase { APPROACH, CIRCLE, LOW, LEAP, BACK }

    /** 한 패(같은 사람을 돌고, 덮치기를 함께). */
    static final class Pack {
        final List<G171> members = new ArrayList<>(6);
        Player prey;
        /** 쓰러진 사람(일으키러 오는 사람을 기다림)과 그 자리. */
        Player downed;
        final Vector hub = new Vector();
        long lastPounce, nextSnap, pounceAt = -1;
        Player pounceOn;
        double spin;
        int snapTurn;
        long growlAt;
    }

    private Pack pack;
    private boolean follower, init;
    private int slot;
    private Phase phase = Phase.APPROACH;
    private int phaseAt;
    private final Vector leapDir = new Vector(0, 0, 1);
    private Player leapAt;
    private boolean snapping, struck;

    public G171(Game game, Species sp) {
        super(game, sp);
        radius = 0.42;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g171", 1.4f, 1.1f);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    // ------------------------------------------------------------------ 패 꾸리기

    /** 감독이 낸 하나(첫 틱): 셋에서 다섯을 더 데려옴(체력, 보상은 이 개체와 같게). 데려온 것은 이미 패가 있음. */
    private void formPack() {
        init = true;
        if (pack != null) return;
        pack = new Pack();
        pack.members.add(this);
        pack.spin = game.random.nextDouble() * Math.PI * 2;
        pack.nextSnap = game.now() + cd(IDLE_SNAP);
        slot = 0;
        int n = FOLLOW_MIN + game.random.nextInt(FOLLOW_MAX - FOLLOW_MIN + 1);
        for (int i = 0; i < n; i++) {
            if (!game.mobs.budgetFor(1)) break;
            G171 f = new G171(game, sp);
            f.pack = pack;
            f.follower = true;
            f.init = true;
            f.slot = i + 1;
            f.reward = reward;
            f.noReward = noReward;
            double a = Math.toRadians(yaw) + (i + 1) * 0.9;
            Vector at = pos.clone().add(new Vector(-Math.sin(a) * 1.6, 0, Math.cos(a) * 1.6));
            if (game.mobs.place(f, at) == null) continue;
            f.maxHp = f.hp = maxHp;
            f.yaw = yaw;
            pack.members.add(f);
        }
    }

    private List<G171> alive() {
        pack.members.removeIf(g -> g.dead);
        return pack.members;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) formPack();
        if (pack == null) formPack();
        if (pack.members.get(0) == this || pack.members.get(0).dead) packTick();
        switch (phase) {
            case APPROACH -> approach();
            case CIRCLE -> circleTick();
            case LOW -> low();
            case LEAP -> leap();
            case BACK -> back();
        }
    }

    /** 패를 이끄는 것(살아 있는 첫째)이 한 틱에 한 번: 고른 사람, 쓰러진 사람 곁 기다림, 빈틈 읽기, 먼저 무는 차례. */
    private void packTick() {
        List<G171> ms = alive();
        if (ms.isEmpty() || ms.get(0) != this) return;
        long now = game.now();
        Pack k = pack;
        // 고른 사람이 쓰러짐: 그 자리를 돌며 일으키러 오는 사람을 기다림
        if (k.prey != null && !standing(k.prey)) {
            PState s = k.prey.isOnline() ? game.players.get(k.prey) : null;
            if (s != null && s.life == Life.DOWNED) {
                k.downed = k.prey;
                k.hub.copy(k.prey.getLocation().toVector());
            }
            k.prey = null;
        }
        if (k.downed != null) {
            PState s = k.downed.isOnline() ? game.players.get(k.downed) : null;
            if (s == null || s.life != Life.DOWNED) k.downed = null;
        }
        if (k.prey == null && k.downed == null) {
            Player p = nearest(PICK_R);
            if (p != null) {
                k.prey = p;
                k.nextSnap = now + cd(IDLE_SNAP);
                for (G171 g : ms) g.mark(p);
            }
        }
        k.spin += 0.012;
        if (k.prey != null && every(10)) game.hud.mark(k.prey, sp.key(), 14);
        // 으르렁(둘레를 따라 돎): 덮치기 직전에는 뚝 끊김
        if (k.pounceAt < 0 && now >= k.growlAt && (k.prey != null || k.downed != null)) {
            k.growlAt = now + 36 + game.random.nextInt(20);
            G171 g = ms.get(game.random.nextInt(ms.size()));
            g.sound("growl", Sound.ENTITY_WOLF_GROWL, 0.7f, 20);
        }
        if (k.pounceAt >= 0) {
            if (now - k.pounceAt > LOW_T + LEAP_MAX + BACK_T) k.pounceAt = -1;
            return;
        }
        if (now - k.lastPounce < cd(POUNCE_GAP)) return;
        // 빈틈 읽기
        Player gap = null;
        if (k.downed != null) gap = reviver(k.downed);
        else if (k.prey != null && opening(k.prey)) gap = k.prey;
        int circling = 0;
        for (G171 g : ms) if (g.phase == Phase.CIRCLE) circling++;
        if (gap != null && circling > 0) {
            k.pounceAt = now;
            k.pounceOn = gap;
            k.lastPounce = now;
            k.nextSnap = now + cd(IDLE_SNAP);
            for (G171 g : ms) if (g.phase == Phase.CIRCLE || g.phase == Phase.APPROACH && g.dist(gap) < CIRCLE_R) g.crouch(gap, false);
            return;
        }
        // 5초 동안 빈틈이 없으면 하나가 먼저 달려들어 물고 빠짐
        if (k.prey != null && now >= k.nextSnap && circling > 0) {
            k.nextSnap = now + cd(IDLE_SNAP);
            for (int i = 0; i < ms.size(); i++) {
                G171 g = ms.get((k.snapTurn + i) % ms.size());
                if (g.phase != Phase.CIRCLE) continue;
                k.snapTurn = (k.snapTurn + i + 1) % Math.max(1, ms.size());
                g.crouch(k.prey, true);
                break;
            }
        }
    }

    /** 빈틈: 재장전 중(RELOAD), 묶임, 느려짐, 붙잡힘, 체력 40 아래, 공중에 뜸. */
    private boolean opening(Player p) {
        if (!standing(p)) return false;
        PState s = game.players.get(p);
        long now = game.now();
        return game.guns.reloading(p) || s.rooted(now) || s.slowSum(now) >= SLOW_MIN || s.grabbedBy != null || s.hp < LOW_HP
            || game.players.launched(p);
    }

    /** 쓰러진 사람을 일으키는(돕는) 중인 사람. */
    private Player reviver(Player downed) {
        UUID id = downed.getUniqueId();
        for (Player p : game.mobs.standing()) {
            PState s = game.players.get(p);
            if (id.equals(s.helping)) return p;
        }
        return null;
    }

    /** 패가 도는 가운데(고른 사람 또는 쓰러진 사람 자리)와 그 사람. */
    private Vector hub() {
        if (pack.prey != null && standing(pack.prey)) return pack.prey.getLocation().toVector();
        if (pack.downed != null) return pack.hub;
        return null;
    }

    private void approach() {
        hover = HOVER;
        state("");
        Vector h = hub();
        Player p = pack.prey != null && standing(pack.prey) ? pack.prey : nearest(400);
        if (h == null) {
            follow(speed());
            face(p != null ? chest(p) : pos.clone().add(vel));
            return;
        }
        double d = Math.hypot(h.getX() - pos.getX(), h.getZ() - pos.getZ());
        if (pack.prey != null) follow(speed());
        else seek(h.clone().setY(pos.getY()), speed());
        face(pack.prey != null ? chest(pack.prey) : h.clone().setY(pos.getY()));
        if (d <= CIRCLE_R) phase(Phase.CIRCLE);
    }

    /** 7 블록 거리에서 둘레를 천천히 돎(패 차례마다 고르게 벌린 각도, 고른 사람만 봄). */
    private void circleTick() {
        hover = HOVER;
        state("");
        Vector h = hub();
        if (h == null) {
            phase(Phase.APPROACH);
            return;
        }
        int n = Math.max(1, pack.members.size());
        int i = Math.max(0, pack.members.indexOf(this));
        double a = pack.spin + i * Math.PI * 2 / n;
        Vector spot = new Vector(h.getX() + Math.cos(a) * RING, pos.getY(), h.getZ() + Math.sin(a) * RING);
        double far = Math.hypot(spot.getX() - pos.getX(), spot.getZ() - pos.getZ());
        double v = (far > 3 ? SPEED * 1.3 : CIRCLE_V) * speed() / baseSpeed;
        seek(spot, Math.min(v, far * 0.5 + 0.02));
        face(pack.prey != null ? chest(pack.prey) : h.clone().setY(pos.getY()));
        if (Math.hypot(h.getX() - pos.getX(), h.getZ() - pos.getZ()) > CIRCLE_R + 6) phase(Phase.APPROACH);
    }

    /** 덮치기 준비: 몸을 낮추고(으르렁은 패 쪽에서 끊김) 고른 사람을 봄. snap 이면 혼자 먼저 무는 것. */
    void crouch(Player p, boolean snap) {
        leapAt = p;
        snapping = snap;
        struck = false;
        if (target != p) mark(p);
        state("low");
        hover = HOVER_LOW;
        hold();
        body.squash(1.05f, 0.85f, 1.1f, 3);
        phase(Phase.LOW);
    }

    private void low() {
        hold();
        if (leapAt == null || !standing(leapAt)) {
            body.squash(1f, 1f, 1f, 3);
            phase(Phase.BACK);
            return;
        }
        face(chest(leapAt));
        if (age - phaseAt >= cd(LOW_T)) {
            leapDir.copy(toward(leapAt));
            state("leap");
            hover = HOVER;
            body.squash(1f, 1f, 1f, 2);
            sound("lunge", Sound.ENTITY_WOLF_GROWL, 1.4f, 20);
            phase(Phase.LEAP);
        }
    }

    private void leap() {
        Player p = leapAt;
        if (p == null || !standing(p)) {
            phase(Phase.BACK);
            return;
        }
        // 곧게 오되 조금씩 고쳐 겨눔(사람이 옆으로 비키면 빗나갈 수 있게 한 틱 4도)
        Vector want = toward(p);
        double cross = leapDir.getX() * want.getZ() - leapDir.getZ() * want.getX();
        double turn = Math.max(-0.07, Math.min(0.07, cross));
        double c = Math.cos(turn), s = Math.sin(turn);
        leapDir.copy(new Vector(leapDir.getX() * c - leapDir.getZ() * s, 0, leapDir.getX() * s + leapDir.getZ() * c).normalize());
        dash(leapDir, LEAP_V * speed() / baseSpeed);
        face(pos.clone().add(leapDir));
        if (!struck && touches(p, 0.25)) {
            struck = true;
            strike(p);
            phase(Phase.BACK);
            return;
        }
        if (age - phaseAt >= LEAP_MAX || wallAhead(0.5)) phase(Phase.BACK);
    }

    private void strike(Player p) {
        if (snapping) {
            hit(p, SNAP_DMG, "cause_g171");
            knock(p, leapDir, 0.2, 0.05);
        } else {
            hit(p, POUNCE_DMG, "cause_g171");
            // 재장전이 끊기고(처음부터 다시) 0.4초 넘어짐
            game.guns.cancelReload(p);
            game.players.root(p, TRIP, "g171_trip");
            knock(p, leapDir, 0.3, 0.12);
        }
        sound("bite", Sound.ENTITY_WOLF_HURT, 0.8f, 16);
        body.squash(1.15f, 0.9f, 0.85f, 2);
    }

    /** 물고 빠짐: 뒤로 물러나 다시 돌기. */
    private void back() {
        state("");
        hover = HOVER;
        Vector h = hub();
        Vector away = h != null ? pos.clone().subtract(h).setY(0) : leapDir.clone().multiply(-1);
        if (away.lengthSquared() < 1e-6) away = leapDir.clone().multiply(-1);
        dash(away, CIRCLE_V * 1.5 * speed() / baseSpeed);
        if (h != null) face(h.clone().setY(pos.getY()));
        if (age - phaseAt >= cd(BACK_T) || h != null && pos.clone().subtract(h).setY(0).length() >= RING - 0.5) {
            leapAt = null;
            phase(h != null ? Phase.CIRCLE : Phase.APPROACH);
        }
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + " pack=" + (pack == null ? 0 : pack.members.size()) + " slot=" + slot
            + (pack != null && pack.prey != null ? " prey=" + pack.prey.getName() : "") + (pack != null && pack.downed != null ? " downed" : "");
    }
}
