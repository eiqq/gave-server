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
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G175 태워 주는 게이브(3막 함정, 방해). 허리 높이의 네 발 짐승(길이 2.4)이 총총 달려와(0.3블록/틱) 사람 2 블록 앞에서 앞무릎을 꿇고 등을 내민다
 * (히힝 웃음). 돕기(좌클릭)로 올라탄다(넷까지, 탈수록 등 안장 얼굴이 엿가락처럼 늘어남). 탄 사람은 SEAT 좌석(Players.ride)이라 그 등에서 쏘고
 * 재장전하며, 짐승은 트럭 둘레를 빠르게(걷는 것의 두 배) 돌아 준다. 처음 6초 안에는 웅크리면 내려 준다(조준점 아래 줄어드는 게이지).
 * 6초째(1초 전 등이 끈적하게 번들거림) 고개를 돌려 눈을 뜨고 손발이 들러붙어(붙잡힘, 동료가 못 뗌) 트럭 둘레 밖 R 30 의 게이브가 가장 많은 쪽으로
 * 내달려 내동댕이친다: 20 피해와 1.5초 넘어짐, 그 자리에서 물기 8 을 두 번 더.
 * 4초 동안 아무도 안 타면 벌떡 일어나 뒷발로 차고(16, 4 블록 날림) 가장 가까운 사람을 등으로 떠 올려 억지로 태운다(처음부터 들러붙음).
 * 다리마다 부위 체력 30(몸 체력 배율을 따름): 다리 넷 가운데 둘을 끊으면 고꾸라지며 탄 사람이 모두 풀려난다(그 뒤로는 태우지 않고 절뚝거림).
 *
 * <p>팩 계약(tools/gabe/sp/g175.py): 몸 gave:gabe/g175 상태 "", "kneel", "run". 안장 부품 gave:gabe/g175_saddle 상태 "", "grin", "sticky".
 * 소리 neigh(24), kick(20), stick(20), slam(24). 글귀 cause_g175, cause_g175_kick, cause_g175_slam. 게이지는 종 키 g175(아이콘).
 */
public final class G175 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.3, RUN_V = 0.45, DRAG_V = 0.4, KNEEL_R = 2.6, KICK = 16, SLAM = 20, BITE = 8, LEG_HP = 30;
    static final double SEAT_GAP = 0.7, SEAT_UP = 0.8, LOOP_R = 9, DROP_R = 30, BOARD_R = 4;
    static final int OFFER = 80, BOARD_WAIT = 20, FREE_T = 120, WARN_T = 20, DRAG_MAX = 300, SEATS = 4, BITES = 2, BITE_GAP = 15,
        SLAM_ROOT = 30, REST = 60, COLLAPSE_T = 60, RIDE_CD = 200;
    static final double HOVER = 0.8, HOVER_KNEEL = 0.62;
    /** 맞는 구: 몸통, 머리, 다리(몸 기준 x, y, z). */
    static final double[][] LEGS = {{0.3, -0.45, 0.72}, {-0.3, -0.45, 0.72}, {0.3, -0.45, -0.78}, {-0.3, -0.45, -0.78}};

    enum Phase { APPROACH, KNEEL, KICK, RIDE, DRAG, REST, DOWN }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, readyAt, boardedAt = -1, rideAt;
    private Body.Part saddle;
    private final Matrix4f mat = new Matrix4f();
    /** 탄 사람(차례대로 좌석)과 들러붙은 사람. */
    private final List<Player> riders = new ArrayList<>(SEATS);
    private final List<Player> stuck = new ArrayList<>(SEATS);
    /** 내동댕이친 사람들(물기 두 번 더). */
    private final List<Player> thrown = new ArrayList<>(SEATS);
    private int bitesLeft, nextBite;
    private final double[] legHp = new double[4];
    private int legsGone;
    private boolean lame, init;
    private double loopA;
    private final Vector drop = new Vector();
    private final List<double[]> spheres = new ArrayList<>(6);
    private long spheresAt = Long.MIN_VALUE;

    public G175(Game game, Species sp) {
        super(game, sp);
        radius = 0.6;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g175", 2.6f, 1.6f);
        saddle = body.attach("gabe/g175_saddle");
        poseSaddle(0, 0);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 안장 얼굴: 등 위, 탄 사람 수만큼 앞뒤로 늘어남. */
    private void poseSaddle(int n, int ticks) {
        if (saddle == null) return;
        float s = 1f + 0.22f * Math.max(0, n - 1);
        saddle.pose(mat.identity().translate(0f, 0.12f, -0.05f).scale(1f, 1f, s), ticks);
    }

    private Vector fwd() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            for (int i = 0; i < 4; i++) legHp[i] = LEG_HP * maxHp / Math.max(1, sp.hp);
        }
        riders.removeIf(p -> !p.isOnline() || !game.players.get(p).standing());
        stuck.removeIf(p -> !riders.contains(p));
        switch (phase) {
            case APPROACH -> approach();
            case KNEEL -> kneel();
            case KICK -> kick();
            case RIDE -> ride();
            case DRAG -> drag();
            case REST -> rest();
            case DOWN -> down();
        }
        biteThrown();
    }

    private void approach() {
        state("");
        hover = HOVER;
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            face(pos.clone().add(vel));
            return;
        }
        double d = dist(p);
        if (d > 6) follow(speed() * (lame ? 0.5 : 1));
        else seek(p.getLocation().toVector().setY(pos.getY()), speed() * (lame ? 0.5 : 1));
        face(chest(p));
        if (!lame && age >= readyAt && d <= KNEEL_R + 0.3 && sees(p)) {
            hold();
            state("kneel");
            hover = HOVER_KNEEL;
            boardedAt = -1;
            sound("neigh", Sound.ENTITY_HORSE_AMBIENT, 1.3f, 24);
            phase(Phase.KNEEL);
        }
    }

    /** 앞무릎을 꿇고 등을 내밂(4초). 돕기(좌클릭)로 탐. 첫 사람이 타면 1초 더 기다렸다 달림. */
    private void kneel() {
        hold();
        Player p = nearest(12);
        if (p != null) turnTo(chest(p), 6f);
        if (boardedAt >= 0 && age - boardedAt >= BOARD_WAIT || riders.size() >= SEATS) {
            startRide();
            return;
        }
        seatAll();
        if (boardedAt < 0 && inPhase() >= OFFER) phase(Phase.KICK);
    }

    /** 사람이 이 개체를 보고 좌클릭: 무릎 꿇은 동안이면 등에 태움. */
    @Override
    public boolean helped(Player p) {
        if (phase != Phase.KNEEL || riders.contains(p) || riders.size() >= SEATS || !standing(p) || dist(p) > BOARD_R) return false;
        return board(p, false);
    }

    private boolean board(Player p, boolean forced) {
        if (riders.size() >= SEATS) return false;
        riders.add(p);
        if (forced) {
            if (!stickTo(p)) {
                riders.remove(p);
                return false;
            }
        } else if (!game.players.ride(p, seat(riders.size() - 1, riders.size()), 3)) {
            riders.remove(p);
            return false;
        }
        if (boardedAt < 0) boardedAt = age;
        poseSaddle(riders.size(), 4);
        game.hud.meter(p, sp.key(), 1, 10);
        return true;
    }

    /** 들러붙음: 좌석을 붙잡힘으로 바꿈(못 쏨, 동료가 못 뗌, 연타로도 못 벗어남). */
    private boolean stickTo(Player p) {
        game.players.unride(p);
        if (!game.players.grab(p, this, 999)) return false;
        PState s = game.players.get(p);
        s.grabDetach = false;
        s.grabPin = true;
        if (!stuck.contains(p)) stuck.add(p);
        game.players.carry(p, seat(riders.indexOf(p), riders.size()));
        return true;
    }

    /** i 번째 좌석 자리(몸 앞뒤로 0.7 씩). */
    private Vector seat(int i, int n) {
        double off = (i - (n - 1) / 2.0) * SEAT_GAP;
        return pos.clone().add(fwd().multiply(off)).add(new Vector(0, SEAT_UP, 0));
    }

    /** 탄 사람을 좌석에(좌석은 쏘기 됨, 들러붙은 사람은 붙잡힘 좌석). */
    private void seatAll() {
        int n = riders.size();
        for (int i = 0; i < n; i++) {
            Player p = riders.get(i);
            Vector at = seat(i, n);
            if (stuck.contains(p)) {
                if (game.players.grabbedBy(p) != this) {
                    riders.remove(i--);
                    n--;
                    stuck.remove(p);
                    continue;
                }
                game.players.carry(p, at);
            } else if (!game.players.ride(p, at, 3)) {
                riders.remove(i--);
                n--;
            }
        }
    }

    /** 아무도 안 탐: 벌떡 일어나 뒷발로 차고, 가장 가까운 사람을 등으로 떠 올려 억지로 태움. */
    private void kick() {
        state("");
        hover = HOVER;
        hold();
        if (inPhase() == 0) {
            Vector rear = pos.clone().subtract(fwd().multiply(1.4));
            for (Player p : game.mobs.standing()) {
                Vector l = p.getLocation().toVector();
                if (Math.hypot(l.getX() - rear.getX(), l.getZ() - rear.getZ()) > 2.2 || Math.abs(l.getY() - pos.getY()) > 2) continue;
                hit(p, KICK, "cause_g175_kick");
                Vector away = l.clone().subtract(pos).setY(0);
                knock(p, away.lengthSquared() > 1e-6 ? away : fwd().multiply(-1), 0.9, 0.38);
            }
            sound("kick", Sound.ENTITY_HORSE_ANGRY, 0.8f, 20);
            body.squash(1f, 1.1f, 0.9f, 3);
            return;
        }
        if (inPhase() < 6) return;
        body.squash(1f, 1f, 1f, 3);
        Player p = nearest(BOARD_R + 1);
        if (p != null && board(p, true)) {
            sound("stick", Sound.ENTITY_SLIME_SQUISH, 0.6f, 20);
            startRide();
            return;
        }
        readyAt = age + cd(RIDE_CD / 2);
        phase(Phase.REST);
    }

    private Vector hubCenter() {
        return Truck.live() ? Truck.center() : new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
    }

    private void startRide() {
        rideAt = age;
        state("run");
        hover = HOVER;
        Vector c = hubCenter();
        loopA = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
        if (saddle != null) saddle.state("");
        phase(Phase.RIDE);
    }

    /** 트럭 둘레를 빠르게 돌아 줌(6초). 들러붙기 전에는 웅크리면 내려 줌. 6초째 들러붙어 떼 쪽으로. */
    private void ride() {
        if (riders.isEmpty()) {
            endRide();
            return;
        }
        int t = age - rideAt;
        Vector c = hubCenter();
        double v = RUN_V * speed() / baseSpeed * (lame ? 0.5 : 1);
        loopA += v / LOOP_R;
        Vector spot = new Vector(c.getX() + Math.cos(loopA) * LOOP_R, pos.getY(), c.getZ() + Math.sin(loopA) * LOOP_R);
        seek(spot, v);
        face(pos.clone().add(vel.clone().setY(0).multiply(4)));
        // 웅크리면 내려 줌(들러붙기 전, 좌석 사람만)
        for (int i = riders.size() - 1; i >= 0; i--) {
            Player p = riders.get(i);
            if (stuck.contains(p)) continue;
            if (p.getCurrentInput().isSneak()) {
                riders.remove(i);
                game.players.unride(p);
                game.hud.meter(p, sp.key(), -1, 0);
            }
        }
        seatAll();
        poseSaddle(riders.size(), 2);
        if (every(2)) for (Player p : riders) game.hud.meter(p, sp.key(), Math.max(0, 1 - t / (double) FREE_T), 6);
        if (t == FREE_T - WARN_T && saddle != null) {
            saddle.state("sticky");
            sound("stick", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.7f, 20);
        }
        if (t >= FREE_T) betray();
    }

    /** 6초: 고개를 돌려 눈을 뜨고 손발이 들러붙음 → 떼 한복판으로. */
    private void betray() {
        for (Player p : new ArrayList<>(riders)) {
            game.hud.meter(p, sp.key(), -1, 0);
            if (!stuck.contains(p) && !stickTo(p)) riders.remove(p);
        }
        if (riders.isEmpty()) {
            endRide();
            return;
        }
        if (saddle != null) {
            saddle.state("grin");
            saddle.look.open = true;
        }
        mark(riders.get(0));
        sound("neigh", Sound.ENTITY_HORSE_ANGRY, 0.7f, 24);
        pickDrop();
        state("run");
        phase(Phase.DRAG);
    }

    /** 트럭 둘레 밖 R 30, 여덟 방향 가운데 둘레 15 블록 안 게이브가 가장 많은 쪽. */
    private void pickDrop() {
        Vector c = hubCenter();
        int best = -1;
        double bestA = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            Vector at = new Vector(c.getX() + Math.cos(a) * DROP_R, pos.getY(), c.getZ() + Math.sin(a) * DROP_R);
            int n = 0;
            for (Gabe g : game.mobs.near(at, 15, g -> g != this && !g.sp.is(Role.BOSS))) n++;
            if (n > best) {
                best = n;
                bestA = a;
            }
        }
        drop.setX(c.getX() + Math.cos(bestA) * DROP_R).setY(pos.getY()).setZ(c.getZ() + Math.sin(bestA) * DROP_R);
    }

    private void drag() {
        if (riders.isEmpty()) {
            endRide();
            return;
        }
        seek(drop.clone().setY(pos.getY()), DRAG_V * speed() / baseSpeed * (lame ? 0.5 : 1));
        face(pos.clone().add(vel.clone().setY(0).multiply(4)));
        seatAll();
        if (Math.hypot(drop.getX() - pos.getX(), drop.getZ() - pos.getZ()) < 1.5 || inPhase() >= DRAG_MAX || wallAhead(0.8)) slam();
    }

    /** 내동댕이침: 20 피해와 1.5초 넘어짐, 그 뒤 물기 둘. */
    private void slam() {
        thrown.clear();
        for (Player p : new ArrayList<>(riders)) {
            if (stuck.contains(p) && game.players.grabbedBy(p) == this) game.players.release(p);
            else game.players.unride(p);
            hit(p, SLAM, "cause_g175_slam");
            game.players.root(p, SLAM_ROOT, "g175_slam");
            Vector out = p.getLocation().toVector().subtract(pos).setY(0);
            knock(p, out.lengthSquared() > 1e-6 ? out : fwd(), 0.35, 0.3);
            thrown.add(p);
        }
        riders.clear();
        stuck.clear();
        bitesLeft = thrown.isEmpty() ? 0 : BITES;
        nextBite = age + cd(BITE_GAP);
        sound("slam", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.6f, 24);
        body.squash(1.1f, 0.85f, 1.1f, 3);
        endRide();
    }

    /** 내동댕이친 자리에서 물기 8 두 번(3.5 블록 안에 있으면). */
    private void biteThrown() {
        if (bitesLeft <= 0 || age < nextBite) return;
        nextBite = age + cd(BITE_GAP);
        bitesLeft--;
        for (Player p : thrown) {
            if (!standing(p) || dist(p) > 3.5) continue;
            hit(p, BITE, "cause_g175");
            sound("kick", Sound.ENTITY_HORSE_EAT, 0.8f, 20);
        }
        if (bitesLeft <= 0) thrown.clear();
    }

    /** 태움 끝(모두 내림): 안장 되돌리고 잠깐 쉼. */
    private void endRide() {
        for (Player p : riders) {
            if (stuck.contains(p) && game.players.grabbedBy(p) == this) game.players.release(p);
            else game.players.unride(p);
            game.hud.meter(p, sp.key(), -1, 0);
        }
        riders.clear();
        stuck.clear();
        poseSaddle(0, 4);
        if (saddle != null) {
            saddle.state("");
            saddle.look.open = false;
        }
        mark(null);
        state("");
        readyAt = age + cd(RIDE_CD);
        phase(Phase.REST);
    }

    private void rest() {
        state("");
        hover = HOVER;
        Player p = nearest(400);
        if (p != null && dist(p) > 5) follow(speed() * 0.6);
        else hold();
        if (p != null) face(chest(p));
        if (inPhase() >= cd(REST)) phase(Phase.APPROACH);
    }

    /** 다리 둘이 끊김: 고꾸라짐(탄 사람 모두 풀림), 잠깐 뒤 절뚝거리며 다시 옴(더는 안 태움). */
    private void down() {
        hold();
        if (inPhase() >= COLLAPSE_T) {
            body.roll = 0;
            phase(Phase.APPROACH);
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        long now = game.now();
        if (spheresAt == now && !spheres.isEmpty()) return spheres;
        spheresAt = now;
        spheres.clear();
        Vector f = fwd();
        double lx = f.getZ(), lz = -f.getX();
        spheres.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.55});
        Vector h = pos.clone().add(f.clone().multiply(1.08));
        spheres.add(new double[] {h.getX(), h.getY() + (phase == Phase.KNEEL ? -0.12 : 0.22), h.getZ(), 0.42});
        for (double[] l : LEGS) {
            spheres.add(new double[] {pos.getX() + lx * l[0] + f.getX() * l[2], pos.getY() + l[1], pos.getZ() + lz * l[0] + f.getZ() * l[2], 0.2});
        }
        return spheres;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || unhittable()) return false;
        if (d.at() != null && legsGone < 4) {
            List<double[]> ss = spheres();
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < ss.size(); i++) {
                double[] s = ss.get(i);
                double dd = Math.pow(s[0] - d.at().getX(), 2) + Math.pow(s[1] - d.at().getY(), 2) + Math.pow(s[2] - d.at().getZ(), 2);
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best >= 2) {
                int leg = best - 2;
                if (legHp[leg] > 0) {
                    legHp[leg] -= d.amount();
                    if (legHp[leg] <= 0) legBroken();
                }
            }
        }
        return super.damage(d);
    }

    private void legBroken() {
        legsGone++;
        sound("kick", Sound.ENTITY_ITEM_BREAK, 0.6f, 20);
        if (legsGone == 2) {
            // 고꾸라지며 탄 사람이 모두 풀려남
            lame = true;
            for (Player p : riders) {
                if (stuck.contains(p) && game.players.grabbedBy(p) == this) game.players.release(p);
                else game.players.unride(p);
                game.hud.meter(p, sp.key(), -1, 0);
            }
            riders.clear();
            stuck.clear();
            poseSaddle(0, 4);
            if (saddle != null) {
                saddle.state("");
                saddle.look.open = false;
            }
            mark(null);
            state("kneel");
            hover = HOVER_KNEEL;
            body.roll = 12f;
            phase(Phase.DOWN);
        }
    }

    private void letGo() {
        for (Player p : riders) {
            if (!p.isOnline()) continue;
            if (stuck.contains(p) && game.players.grabbedBy(p) == this) game.players.release(p);
            else game.players.unride(p);
            game.hud.meter(p, sp.key(), -1, 0);
        }
        riders.clear();
        stuck.clear();
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        letGo();
        super.die(d);
    }

    @Override
    public void remove() {
        letGo();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + " riders=" + riders.size() + " stuck=" + stuck.size() + " legs=" + (4 - legsGone)
            + (lame ? " lame" : "");
    }
}
