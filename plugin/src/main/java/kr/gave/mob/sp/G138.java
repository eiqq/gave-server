package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G138 다친 냄새 물구나무 게이브(4막, 떼, 돌격). 물구나무 선 뒤집힌 얼굴 무리가 서 있는 사람 가운데 체력 비율이 가장 낮은 한 사람만 냄새로 찾아 몰려간다
 * (같으면 동료를 일으키는 사람). 표적 체력이 절반 아래면 x1.5 빠르기, 모두가 80% 위면 표적을 못 정해 x0.7 로 가장 가까운 사람 쪽으로. 다른 사람
 * 곁은 지나치고 길을 막으면 한 번 물고(3) 넘어간다. 표적 2.4블록 안에서 막대기 팔로 뛰어올라 뒤집힌 입으로 7(표적 체력 30% 아래면 10). 표적이
 * 쓰러지면 다음 약한 사람으로 일제히 돌아선다. 표적만 보느라 옆이 비어 표적 아닌 사람의 사격에 피해 x1.5.
 *
 * <p>표적은 이 종 모두가 함께 본다(판마다 static 한 칸, 20틱마다 다시 셈). 표적이 된 사람 조준점 옆에 이 종 표시(Hud.mark), 사방에서 킁킁.
 * 떼 묶기: 놓인 직후 '열린 무리'(생긴 지 JOIN 틱 안)의 첫 개체 곁으로 옮긴다(감독은 고리 여러 각도에 하나씩 놓음).
 *
 * <p>팩 계약(tools/gabe/sp/g138.py): 상태 "", "leap". 소리 sniff(20), bite(16). 글귀 cause_g138.
 */
public final class G138 extends Gabe {

    static final double SPEED = 0.2, HOVER = 0.75, RADIUS = 0.42;
    static final double FAST = 1.5, SLOW = 0.7, WEAK = 0.5, HEALTHY = 0.8, LOW = 0.3;
    static final double BITE = 7, BITE_LOW = 10, BLOCK_BITE = 3, OTHER_MUL = 1.5;
    static final double LEAP_AT = 2.4, LEAP_V = 0.32, LEAP_H = 1.1;
    static final int LEAP = 10, LEAP_CD = 30, BLOCK_CD = 40, HUNT_EVERY = 20, JOIN = 60, MAX_PACK = 10;

    /** 판마다 하나: 이 종 모두가 쫓는 사람. */
    static final class Hunt {
        final Game game;
        Player target;
        long at = Long.MIN_VALUE;
        boolean calm;

        Hunt(Game game) {
            this.game = game;
        }
    }

    private static Hunt hunt;
    private static final List<G138> OPEN = new ArrayList<>();
    private static long openAt;

    private int leapAt = -1, readyAt, sniffAt;
    private final Vector leapFrom = new Vector(), leapTo = new Vector();
    private final Map<UUID, Integer> blocked = new HashMap<>(4);

    public G138(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g138", 1.6f, 2.4f);
    }

    @Override
    protected void spawned() {
        sniffAt = game.random.nextInt(60);
        G150.arm(game);
        if (game.director.cageSpawning()) return;
        long now = game.now();
        for (Iterator<G138> it = OPEN.iterator(); it.hasNext();) {
            G138 o = it.next();
            if (o.game != game || o.dead || now - openAt > JOIN) it.remove();
        }
        if (OPEN.isEmpty() || OPEN.size() >= MAX_PACK) {
            OPEN.clear();
            OPEN.add(this);
            openAt = now;
            return;
        }
        G138 lead = OPEN.get(0);
        double a = game.random.nextDouble() * Math.PI * 2, r = 1.0 + game.random.nextDouble() * 2.6;
        double x = lead.pos.getX() + Math.cos(a) * r, z = lead.pos.getZ() + Math.sin(a) * r;
        if (game.mobs.flow.walkable(x, z)) {
            pos.setX(x);
            pos.setZ(z);
            pos.setY(game.mobs.flow.floor(x, z) + HOVER);
        }
        OPEN.add(this);
    }

    /** 이 판의 표적(20틱마다 다시 셈). */
    private Hunt hunt() {
        Hunt h = hunt;
        if (h == null || h.game != game) h = hunt = new Hunt(game);
        long now = game.now();
        if (now - h.at < HUNT_EVERY && (h.target == null || standing(h.target))) return h;
        h.at = now;
        Player best = null;
        double bf = 2;
        boolean bestHelping = false;
        for (Player p : game.mobs.standing()) {
            PState s = game.players.get(p);
            double f = s.hpFrac();
            boolean helping = s.helping != null;
            if (f < bf - 1e-6 || Math.abs(f - bf) <= 1e-6 && helping && !bestHelping) {
                bf = f;
                best = p;
                bestHelping = helping;
            }
        }
        Player was = h.target;
        h.calm = best == null || bf >= HEALTHY;
        h.target = h.calm ? null : best;
        if (h.target != was) game.plugin.getLogger().info("[G138] 표적 " + (h.target == null ? "없음(모두 80% 위)" : h.target.getName() + String.format(" 체력몫 %.2f", bf)));
        if (h.target != null && h.target != was) {
            // 새 표적: 무리가 일제히 돌아봄(킁킁이 그 사람 쪽으로)
            sound("sniff", Sound.ENTITY_FOX_SNIFF, 0.8f, 20);
        }
        if (h.target != null) game.hud.mark(h.target, "g138", HUNT_EVERY + 10);
        return h;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (leapAt >= 0) {
            leap();
            return;
        }
        Hunt h = hunt();
        Player t = h.target;
        if (age >= sniffAt) {
            sound("sniff", Sound.ENTITY_FOX_SNIFF, 0.9f, 20);
            sniffAt = age + 60 + game.random.nextInt(60);
        }
        if (t == null) {
            if (target != null) mark(null);
            follow(speed() * SLOW);
            faceMove();
            blockBite(null);
            return;
        }
        if (target != t) mark(t);
        double k = game.players.get(t).hpFrac() < WEAK ? FAST : 1.0;
        double d = dist(t);
        if (d > 6) {
            seek(chest(t), speed() * k);
            if (wallAhead(0.7)) follow(speed() * k);
        } else seek(chest(t), speed() * k);
        faceMove();
        blockBite(t);
        if (age >= readyAt && d <= LEAP_AT) startLeap(t);
    }

    private void faceMove() {
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-4) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
    }

    /** 길을 막은 사람(표적 아님)을 한 번 물고 넘어감. */
    private void blockBite(Player t) {
        if (!every(4)) return;
        for (Player q : game.mobs.standing()) {
            if (q == t || !touches(q, 0.15)) continue;
            Integer at = blocked.get(q.getUniqueId());
            if (at != null && age - at < BLOCK_CD) continue;
            blocked.put(q.getUniqueId(), age);
            hit(q, BLOCK_BITE, "cause_g138");
            sound("bite", Sound.ENTITY_FOX_BITE, 1.2f, 16);
            return;
        }
    }

    private void startLeap(Player t) {
        leapAt = age;
        leapFrom.copy(pos);
        Vector to = chest(t);
        leapTo.setX(to.getX()).setZ(to.getZ()).setY(pos.getY());
        state("leap");
        face(to);
    }

    private void leap() {
        int t = age - leapAt;
        double f = Math.min(1, (t + 1) / (double) LEAP);
        Vector want = leapFrom.clone().multiply(1 - f).add(leapTo.clone().multiply(f));
        vel.setX(want.getX() - pos.getX()).setZ(want.getZ() - pos.getZ());
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > LEAP_V) vel.multiply(LEAP_V / vl);
        hover = HOVER + LEAP_H * Math.sin(Math.PI * f);
        Hunt h = hunt;
        Player p = h != null ? h.target : null;
        if (t >= LEAP - 3 && p != null && standing(p) && touches(p, 0.45)) {
            double dmg = game.players.get(p).hpFrac() < LOW ? BITE_LOW : BITE;
            hit(p, dmg, "cause_g138");
            knock(p, toward(p), 0.25, 0.1);
            if (every(3)) log("뛰어 묾 " + p.getName() + " 피해 " + dmg + " 체력 " + Math.round(game.players.get(p).hp));
            sound("bite", Sound.ENTITY_FOX_BITE, 1.0f, 16);
            endLeap();
            return;
        }
        if (t >= LEAP) endLeap();
    }

    private void endLeap() {
        leapAt = -1;
        hover = HOVER;
        state("");
        readyAt = age + cd(LEAP_CD);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        Hunt h = hunt;
        Player t = h != null && h.game == game ? h.target : null;
        if (d.by() != null && t != null && d.by() != t) return super.damage(d.times(OTHER_MUL));
        return super.damage(d);
    }

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), RADIUS}, new double[] {pos.getX(), pos.getY() + 0.75, pos.getZ(), 0.38});
    }

    /** 시험과 판 뒤 분석용 기록(드문 사건만). */
    void log(String s) {
        game.plugin.getLogger().info("[G138] " + s);
    }

    @Override
    public String toString() {
        Hunt h = hunt;
        return super.toString() + (leapAt >= 0 ? " LEAP" : "") + " hunt=" + (h == null || h.target == null ? "-" : h.target.getName());
    }
}
