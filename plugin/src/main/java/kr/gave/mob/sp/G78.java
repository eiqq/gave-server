package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G78 돈 냄새 사냥개 게이브(1막 보상, 돌격). 검은 얼굴 사냥개에 작은 얼굴 기수가 올라탔고 셋이 한 무리다(감독이 낸 하나가 첫 틱에 둘을 더 데려옴).
 * 판에서 가진 돈이 가장 많은 서 있는 사람을 맡아(10초마다 다시) 셋이 늑대처럼 퍼져 둘러싸며 달려와 번갈아 문다: 물기 12, 물 때마다 그 사람 돈 4% 를
 * 훔쳐 문 개가 입에 문다. 쫓기는 사람 머리 위에 모두에게 보이는 동전이 뜨고, 그 사람 조준점 곁에 이 종 표시가 뜬다. 개가 죽으면 기수가 뛰어내려
 * 맨발로 계속 쫓으며 걷어찬다(5). 무리(개와 기수)를 다 잡으면 훔친 돈 x1.5 를 판에 든 사람들이 나눠 받는다.
 *
 * <p>팩 계약(tools/gabe/sp/g78.py): 개 gabe/g78 상태 "", 부위 coin. 기수 부품 gabe/g78_rider 상태 ""(탐), "run"(내려 뜀, G78Rider).
 * 동전 표시 부품 gabe/g78_coin. 소리 bark(32), bite, snatch, kick. 원인 글귀 cause_g78.
 */
public final class G78 extends Gabe {

    static final double SPEED = 0.24, HOVER = 0.8;
    /** 무리: 데려오는 수, 둘러싸는 반지름, 다시 고르는 틱, 쫓기는 사람 표시 틱. */
    static final int FOLLOWERS = 2, RETARGET = 200, MARK_T = 30;
    static final double RING = 3.2;
    /** 물기: 덤비는 거리, 덤비는 빠르기와 최대 틱, 피해, 훔치는 몫, 무리가 번갈아 무는 간격, 개마다 쉬는 틱. */
    static final double LUNGE_RANGE = 4.2, LUNGE_V = 0.5, BITE_DMG = 3, STEAL = 0.04;
    static final int LUNGE_MAX = 9, PACK_GAP = 14, REST = 24;
    static final double REFUND = 1.5;

    /** 한 무리(개 셋과 내린 기수들). */
    static final class Pack {
        final List<Gabe> members = new ArrayList<>(6);
        Player target;
        int stolen;
        long retargetAt, nextBite;
        G78 coinOwner;
        boolean paid;

        boolean alive() {
            for (Gabe g : members) if (!g.dead) return true;
            return false;
        }
    }

    enum Phase { CHASE, LUNGE, REST }

    Pack pack;
    int slot;
    private boolean follower;
    private Phase phase = Phase.CHASE;
    private int phaseAt, nextBark;
    private final Vector lungeDir = new Vector();
    private Body.Part rider, coin;
    private final Matrix4f mat = new Matrix4f();

    public G78(Game game, Species sp) {
        super(game, sp);
        radius = 0.45;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g78", 1.4f, 1.9f);
        rider = body.attach("gabe/g78_rider");
        if (rider != null) {
            mat.identity().translate(0f, 0.55f, -0.12f);
            rider.pose(mat, 0);
        }
    }

    @Override
    protected void spawned() {
        nextBark = age + 20 + game.random.nextInt(40);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    @Override
    public void tick() {
        if (pack == null) formPack();
        if (rider != null) rider.look.open = body.look.open;
        packTarget();
        switch (phase) {
            case CHASE -> chase();
            case LUNGE -> lunge();
            case REST -> rest();
        }
        if (pack.coinOwner == this) poseCoin();
        if (age >= nextBark && pack.target != null) {
            nextBark = age + 50 + game.random.nextInt(50);
            sound("bark", Sound.ENTITY_WOLF_GROWL, 1.2f, 32);
        }
    }

    /** 감독이 낸 개(첫 틱): 둘을 더 데려와 무리를 꾸림(체력, 보상은 이 개와 같게). 데려온 개는 이미 무리가 있음. */
    private void formPack() {
        pack = new Pack();
        pack.members.add(this);
        pack.coinOwner = this;
        slot = 0;
        if (follower) return;
        for (int i = 0; i < FOLLOWERS; i++) {
            if (!game.mobs.budgetFor(2)) break;
            G78 f = new G78(game, sp);
            f.pack = pack;
            f.follower = true;
            f.slot = i + 1;
            f.reward = reward;
            f.noReward = noReward;
            double a = Math.toRadians(yaw) + (i == 0 ? 1.2 : -1.2);
            Vector at = pos.clone().add(new Vector(-Math.sin(a) * 1.8, 0, Math.cos(a) * 1.8));
            if (game.mobs.place(f, at) == null) continue;
            f.maxHp = f.hp = maxHp;
            f.yaw = yaw;
            pack.members.add(f);
        }
    }

    /** 돈이 가장 많은 서 있는 사람(10초마다, 맡은 사람이 쓰러지면 바로 다시). */
    private void packTarget() {
        long now = game.now();
        if (pack.target != null && standing(pack.target) && now < pack.retargetAt) return;
        pack.retargetAt = now + RETARGET;
        Player best = null;
        int bm = -1;
        for (Player p : game.mobs.standing()) {
            int m = game.players.get(p).money;
            if (m > bm || (m == bm && best != null && dist(p) < dist(best))) {
                bm = m;
                best = p;
            }
        }
        pack.target = best;
    }

    /** 맡은 사람 둘레 제 자리(무리 차례마다 120도)로 달려와 둘러쌈. 가까우면 번갈아 덤빔. */
    private void chase() {
        Player p = pack.target != null && standing(pack.target) ? pack.target : nearest(400);
        if (p == null) {
            follow(speed());
            face(pos.clone().add(vel));
            return;
        }
        if (target != p && dist(p) < 24) mark(p);
        if (p == pack.target && every(20)) game.hud.mark(p, sp.key(), MARK_T);
        double d = dist(p);
        if (d > 18) {
            follow(speed());
        } else {
            // 사람 둘레 제 자리: 사람에서 이 개 쪽 각도를 무리 차례만큼 돌린 곳
            Vector from = pos.clone().subtract(p.getLocation().toVector()).setY(0);
            double base = Math.atan2(from.getZ(), from.getX());
            double want = base + Math.toRadians(slot == 0 ? 0 : (slot == 1 ? 40 : -40)) * Math.min(1, d / 8);
            double r = Math.max(RING, d - 1.5);
            Vector spot = p.getLocation().toVector().add(new Vector(Math.cos(want) * r, 0, Math.sin(want) * r));
            seek(spot.setY(pos.getY()), speed());
        }
        face(chest(p));
        long now = game.now();
        if (d <= LUNGE_RANGE && now >= pack.nextBite && sees(p)) {
            pack.nextBite = now + cd(PACK_GAP);
            lungeDir.copy(toward(p));
            body.squash(0.9f, 0.9f, 1.15f, 2);
            phase(Phase.LUNGE);
        }
    }

    private void lunge() {
        dash(lungeDir, LUNGE_V * speed() / baseSpeed);
        Player p = pack.target != null && standing(pack.target) ? pack.target : target;
        if (p != null && standing(p) && touches(p, 0.25)) {
            bite(p);
            return;
        }
        if (age - phaseAt >= LUNGE_MAX || wallAhead(0.5)) {
            body.squash(1f, 1f, 1f, 3);
            phase(Phase.REST);
        }
    }

    private void bite(Player p) {
        hit(p, BITE_DMG, "cause_g78");
        knock(p, lungeDir, 0.25, 0.1);
        sound("bite", Sound.ENTITY_WOLF_HURT, 0.8f, 16);
        PState s = game.players.get(p);
        int take = Math.min(s.money, Math.max(1, (int) Math.round(s.money * STEAL)));
        if (take > 0 && game.players.spend(p, take)) {
            pack.stolen += take;
            body.look.on.add("coin");
            sound("snatch", Sound.ENTITY_ITEM_PICKUP, 0.8f, 16);
        }
        body.squash(1.15f, 0.9f, 0.85f, 2);
        hold();
        phase(Phase.REST);
    }

    /** 문 뒤 잠깐 물러섬. */
    private void rest() {
        vel.copy(lungeDir.clone().multiply(-0.12));
        if (age - phaseAt >= 3) body.squash(1f, 1f, 1f, 4);
        if (age - phaseAt >= cd(REST)) phase(Phase.CHASE);
    }

    /** 쫓기는 사람 머리 위 동전(무리에서 한 개가 들고 다님, 세계 자리를 몸 기준으로). */
    private void poseCoin() {
        Player p = pack.target;
        if (p == null || !standing(p)) {
            if (coin != null) {
                coin.remove();
                coin = null;
            }
            return;
        }
        if (coin == null) {
            coin = body.attach("gabe/g78_coin");
            if (coin == null) return;
            coin.follow = false;
            if (coin.d != null) {
                coin.d.setDisplayWidth(0f);
                coin.d.setDisplayHeight(0f);
            }
        }
        if (!every(2)) return;
        Location l = p.getLocation();
        float bob = (float) Math.sin(age * 0.15) * 0.08f;
        mat.identity().translate((float) (l.getX() - pos.getX()), (float) (l.getY() + 2.35 - pos.getY()) + bob, (float) (l.getZ() - pos.getZ()))
                .rotateY(age * 0.12f);
        coin.pose(mat, 2);
    }

    // ------------------------------------------------------------------ 죽음

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (coin != null) {
            coin.remove();
            coin = null;
        }
        // 기수가 뛰어내림(웨이브 끝 정리 죽음이면 같이 사라짐)
        if (rider != null) {
            rider.remove();
            rider = null;
            if (!noReward && game.mobs.budgetFor(1)) {
                G78Rider r = new G78Rider(game, sp, pack, maxHp);
                r.yaw = yaw;
                if (game.mobs.place(r, pos.clone().add(new Vector(0, 0.3, 0))) != null) pack.members.add(r);
            }
        }
        super.die(d);
        if (pack.coinOwner == this) {
            pack.coinOwner = null;
            for (Gabe g : pack.members) {
                if (g instanceof G78 o && !o.dead) {
                    pack.coinOwner = o;
                    break;
                }
            }
        }
        packDied(game, pack, noReward || d == null || d.by() == null);
    }

    /** 무리가 다 잡혔으면 훔친 돈 x1.5 를 판에 든 사람들이 나눠 받음(한 번만, 마지막이 사람 손에 죽지 않았으면(웨이브 끝 정리) 없음). */
    static void packDied(Game game, Pack pack, boolean cleanup) {
        if (pack == null || pack.paid || pack.alive()) return;
        pack.paid = true;
        if (cleanup || pack.stolen <= 0) return;
        List<Player> in = new ArrayList<>(game.players.inPlay());
        if (in.isEmpty()) return;
        int total = (int) Math.round(pack.stolen * REFUND);
        int each = Math.max(1, total / in.size());
        game.players.teamMoney(each);
    }

    @Override
    public List<double[]> spheres() {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX() + fx * 0.76, pos.getY() + 0.14, pos.getZ() + fz * 0.76, 0.34});
        out.add(new double[] {pos.getX() - fx * 0.1, pos.getY(), pos.getZ() - fz * 0.1, 0.4});
        if (rider != null) out.add(new double[] {pos.getX() - fx * 0.12, pos.getY() + 0.65, pos.getZ() - fz * 0.12, 0.27});
        return out;
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " slot=" + slot + " stolen=" + (pack != null ? pack.stolen : 0)
                + (pack != null && pack.target != null ? " hunt=" + pack.target.getName() : "");
    }
}
