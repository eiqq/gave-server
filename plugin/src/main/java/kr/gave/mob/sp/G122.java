package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;

/**
 * G122 호리병 게이브(함정, 3막). 소리 없이 낮게 떠서 무리를 돌아 한 사람의 등 뒤 7블록에 자리 잡고(늘 등 뒤로 돈다), 마개를 뽑아 아가리를 그 사람 쪽으로
 * 기울이고 부른다. 부른 뒤 1초 안에 그 사람이 돌아보면(시선이 호리병 쪽 40도 안) 빨려 들어가 갇힌다(이 개체를 잡으면 풀림). 안 돌아보면 3초 뒤 더
 * 가까이(4블록)서 한 번 더 부르고, 세 번째에는 직접 다가와 뒤통수를 박는다(20, 앞으로 넘어짐).
 *
 * <p>신호: 그 사람에게만 알림 띠에 '거기 너'(공통 규칙상 시스템이 지어낸 말은 분홍 글씨: HUD 담당 몫) + 등 뒤에서 병 속 목소리. 동료 눈에는 그 사람 뒤
 * 호리병이 그대로 보인다. 부르는 동안 마개가 뽑혀 아가리가 약점(두 배).
 *
 * <p>바꾼 것: 갇힌 사람은 엔진 갇힘(서 있지 않음)이라 '안에서 초당 6' 은 없다(호리병을 잡을 때까지 못 나옴). 빨려 드는 0.5초는 붙잡힘 + 아가리로
 * 끌려감. 앞으로 넘어짐은 앞으로 밀침 + 묶임 0.5초 + 화면 꺾임.
 *
 * <p>팩 계약(tools/gabe/sp/g122.py): gave:gabe/g122(상태 "", "open"). 소리 call(12), pop(12), suck(16), ram(16). 글귀 cause_g122, cause_g122_ram,
 * notice_g122_call.
 */
public final class G122 extends Gabe implements Detachable {

    static final double SPEED = 0.22, FLY_H = 1.9, BACK1 = 7, BACK2 = 4, PLACE_R = 1.4;
    static final double LOOK_DEG = 40, RAM = 20, MOUTH_UP = 0.95;
    /** 부른 뒤 돌아봄을 보는 틱, 다음 부름까지, 빨아들이는 틱, 쉬기. */
    static final int WATCH = 20, NEXT_CALL = 60, SUCK_T = 10, REST = 100, RAM_MAX = 60, PLACE_MAX = 200;
    static final float TILT = 55f;

    enum Phase { STALK, CALL, SUCK, RAM, REST }

    private Phase phase = Phase.STALK;
    private int phaseAt, calls, calledAt = -100;
    private Player prey, sucking;
    private final Vector spot = new Vector();

    public G122(Game game, Species sp) {
        super(game, sp);
        radius = 0.55;
        flying = true;
        hover = FLY_H;
        baseSpeed = SPEED;
        noPush = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g122", 1.6f, 2.4f);
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
        if (sucking != null && (!sucking.isOnline() || game.players.grabbedBy(sucking) != this)) let();
        switch (phase) {
            case STALK -> stalk();
            case CALL -> call();
            case SUCK -> suck();
            case RAM -> ram();
            case REST -> rest();
        }
    }

    /** 표적: 아직 다른 호리병이 노리지 않은 가장 가까운 사람. */
    private Player pick() {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            if (game.players.grabbedBy(p) != null) continue;
            double d = dist(p);
            for (Gabe g : game.mobs.near(pos, 64, x -> x != this && x instanceof G122 && !x.dead)) if (g.target == p) d += 20;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** 등 뒤 back 블록, 바닥 + FLY_H. */
    private Vector behind(Player p, double back) {
        Location l = p.getLocation();
        double r = Math.toRadians(l.getYaw());
        double x = l.getX() + Math.sin(r) * back, z = l.getZ() - Math.cos(r) * back;
        return new Vector(x, game.mobs.flow.floor(x, z) + FLY_H, z);
    }

    /** 바닥 위 높이를 맞추며 점으로 날아감(수평 빠르기 v). */
    private void fly(Vector to, double v) {
        Vector d = to.clone().subtract(pos);
        double h = Math.hypot(d.getX(), d.getZ());
        Vector out = new Vector(d.getX(), 0, d.getZ());
        if (h > 1e-3) out.multiply(Math.min(v, h) / h);
        double want = Math.max(floor() + 0.9, to.getY());
        out.setY(Math.max(-0.12, Math.min(0.12, (want - pos.getY()) * 0.2)));
        vel.copy(out);
    }

    /** 소리 없이 떠서 표적의 등 뒤 7블록(두 번째는 4블록)으로 돈다. 자리에 닿고 표적이 등지고 있으면 부른다. */
    private void stalk() {
        state("");
        pitch = 0;
        Player p = standing(prey) ? prey : pick();
        if (p == null) {
            follow(speed());
            return;
        }
        if (p != prey) {
            prey = p;
            calls = 0;
            mark(p);
        }
        double back = calls == 0 ? BACK1 : BACK2;
        Vector want = behind(p, back);
        double far = dist(p);
        // 멀면 흐름장으로 오고, 가까우면 그 사람 둘레를 돌아(앞을 가로지르지 않게) 등 뒤 자리로
        Location pl = p.getLocation();
        if (far > back + 10) follow(speed() * 1.6);
        else {
            double ra = Math.atan2(pos.getZ() - pl.getZ(), pos.getX() - pl.getX());
            double wa = Math.atan2(want.getZ() - pl.getZ(), want.getX() - pl.getX());
            double diff = Math.atan2(Math.sin(wa - ra), Math.cos(wa - ra));
            if (Math.abs(diff) > Math.toRadians(30)) {
                double a = ra + Math.signum(diff) * Math.min(Math.abs(diff), Math.toRadians(40));
                double rr = Math.max(back, Math.min(far, back + 3));
                double x = pl.getX() + Math.cos(a) * rr, z = pl.getZ() + Math.sin(a) * rr;
                fly(new Vector(x, game.mobs.flow.floor(x, z) + FLY_H, z), speed() * 1.6);
            } else fly(want, speed() * 1.4);
        }
        face(chest(p));
        Vector flat = want.clone().subtract(pos).setY(0);
        if (flat.length() <= PLACE_R && !looking(p) || inPhase() > PLACE_MAX && far < back + 3) {
            spot.copy(want);
            callOut(p);
        }
    }

    /** 사람이 지금 호리병 쪽을 40도 안으로 보나. */
    private boolean looking(Player p) {
        Location e = p.getEyeLocation();
        Vector to = pos.clone().subtract(e.toVector());
        double l = to.length();
        if (l < 1e-3) return true;
        return e.getDirection().dot(to.multiply(1 / l)) >= Math.cos(Math.toRadians(LOOK_DEG));
    }

    /** 마개를 뽑고 아가리를 기울여 부름(그 사람에게만 '거기 너', 목소리는 등 뒤에서). */
    private void callOut(Player p) {
        calls++;
        calledAt = age;
        state("open");
        pitch = TILT;
        sound("pop", Sound.ENTITY_ITEM_PICKUP, 0.5f, 12);
        sound("call", Sound.ENTITY_VILLAGER_AMBIENT, 0.5f, 12);
        game.hud.notice(p, "notice_g122_call", 40);
        phase(Phase.CALL);
    }

    /** 부른 뒤 1초: 돌아보면 빨아들임. 안 돌아보면 3초 뒤 더 가까이서, 세 번째에는 박치기. */
    private void call() {
        Player p = prey;
        if (!standing(p)) {
            calm();
            return;
        }
        fly(spot, speed());
        face(chest(p));
        pitch = TILT;
        int t = inPhase();
        if (t <= WATCH && looking(p)) {
            if (game.players.grabbedBy(p) == null && grab(p, 30)) {
                sucking = p;
                game.players.get(p).grabPin = false; // 좌석이 끌고 감
                sound("suck", Sound.ENTITY_PLAYER_BREATH, 0.5f, 16);
                body.squash(0.8f, 1.5f, 0.8f, 6);
                phase(Phase.SUCK);
                return;
            }
        }
        if (t > WATCH) {
            state("");
            pitch = 0;
        }
        if (t >= cd(NEXT_CALL)) {
            if (calls >= 2) {
                state("");
                pitch = 0;
                phase(Phase.RAM);
            } else phase(Phase.STALK);
        }
    }

    /** 빨려 듦: 0.5초 동안 아가리로 끌려가 갇힘. */
    private void suck() {
        hold();
        Player p = sucking;
        if (p == null) {
            calm();
            return;
        }
        face(chest(p));
        pitch = TILT;
        double k = Math.min(1, (inPhase() + 1) / (double) SUCK_T);
        Vector mouth = mouth();
        Location l = p.getLocation();
        Vector from = new Vector(l.getX(), l.getY() + 0.6, l.getZ());
        Vector seat = from.clone().add(mouth.clone().subtract(from).multiply(k));
        game.players.carry(p, seat);
        if (inPhase() >= SUCK_T) {
            sucking = null;
            game.players.trap(p, this);
            sound("suck", Sound.ENTITY_GENERIC_DRINK, 0.6f, 16);
            body.squash(1f, 1f, 1f, 6);
            state("");
            pitch = 0;
            mark(null);
            prey = null;
            calls = 0;
            phase(Phase.REST);
        }
    }

    /** 세 번째: 직접 다가와 뒤통수를 박는다. */
    private void ram() {
        Player p = prey;
        if (!standing(p) || inPhase() > RAM_MAX) {
            calm();
            return;
        }
        Vector head = p.getEyeLocation().toVector();
        Vector d = head.clone().subtract(pos);
        double l = d.length();
        face(head);
        vel.copy(l < 1e-3 ? new Vector() : d.multiply(Math.min(l, speed() * 2.4) / l));
        if (l <= radius + 0.5) {
            Location e = p.getEyeLocation();
            Vector fwd = e.getDirection().setY(0);
            if (fwd.lengthSquared() < 1e-4) fwd = toward(p);
            hit(p, RAM, "cause_g122_ram");
            if (standing(p)) {
                knock(p, fwd, 0.7, 0.15);
                game.players.root(p, 10, "g122");
                Guns.kick(p, (game.random.nextFloat() - 0.5f) * 6f, 14f);
            }
            sound("ram", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 16);
            body.squash(1.2f, 0.8f, 1.2f, 2);
            calm();
        }
    }

    private void rest() {
        Player p = nearest(400);
        if (p != null && dist(p) < 10) fly(pos.clone().subtract(toward(p).multiply(2)), speed());
        else hold();
        if (inPhase() == 6) body.squash(1f, 1f, 1f, 4);
        if (inPhase() >= cd(REST)) phase(Phase.STALK);
    }

    private void calm() {
        if (sucking != null) {
            Player p = sucking;
            sucking = null;
            game.players.release(p);
        }
        body.squash(1f, 1f, 1f, 4);
        state("");
        pitch = 0;
        mark(null);
        prey = null;
        calls = 0;
        phase(Phase.REST);
    }

    private void let() {
        sucking = null;
        if (phase == Phase.SUCK) calm();
    }

    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim != sucking) return;
        sucking = null;
        if (!dead && phase == Phase.SUCK) calm();
    }

    // ------------------------------------------------------------------ 맞음

    /** 아가리 자리(기운 몸의 정수리). */
    private Vector mouth() {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double up = MOUTH_UP * Math.cos(pr), fw = MOUTH_UP * Math.sin(pr);
        return new Vector(pos.getX() - Math.sin(yr) * fw, pos.getY() + up, pos.getZ() + Math.cos(yr) * fw);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double fx = -Math.sin(yr), fz = Math.cos(yr);
        for (double[] c : new double[][] {{-0.4, 0.55}, {0.52, 0.36}}) {
            double up = c[0] * Math.cos(pr), fw = c[0] * Math.sin(pr);
            out.add(new double[] {pos.getX() + fx * fw, pos.getY() + up, pos.getZ() + fz * fw, c[1]});
        }
        Vector m = mouth();
        out.add(new double[] {m.getX(), m.getY(), m.getZ(), 0.2});
        return out;
    }

    @Override
    public Vector head() {
        return pos.clone().add(new Vector(0, 0.52, 0));
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        // 부르는 동안 마개가 뽑힌 아가리가 약점(두 배)
        if ("open".equals(state) && d.at() != null && d.at().distance(mouth()) <= 0.45) return super.damage(d.times(2));
        return super.damage(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " calls=" + calls + (prey != null ? " prey=" + prey.getName() : "")
                + (sucking != null ? " sucking" : "");
    }
}
