package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G106 숨 쉬는 땅 게이브(2막, 함정, 방해). 지름 14 블록, 두께 0.3 의 흙빛 등딱지가 땅 거죽 바로 아래를 미끄러지듯 사람이 가장 많이 선 자리 밑으로
 * 기어들어 4초마다 숨을 쉰다(위에 선 사람 3 피해, 발이 빠져 25% 느려짐, 발밑이 살짝 들썩). 위에 선 사람 수, 그 위에서 쏜 총, 그 위의 불만큼
 * 뜨거움이 차서 다 차면(대개 6~8초) 등을 뒤집어 턴다: 위에 선 모두가 6 블록 위로 튕겨 떨어지면 26 과 넘어짐 1초, 드러난 아랫면의 입들이
 * 떨어진 사람을 물어 10. 2초 아랫면을 드러냈다가 다시 엎어져 다음 자리로 긴다. 가장자리를 밟고 지나가면 발목 물기 5.
 * 등딱지는 피해 30%, 뒤집힌 동안 x2.5. 숨구멍 대롱(각 120) 둘을 부수면 숨이 막혀 스스로 뒤집혀 8초 아랫면을 드러낸다. 아무도 안 올라선 등에
 * 불이 붙으면 바로 뒤집힌다.
 *
 * <p>팩 계약(tools/gabe/sp/g106.py): 모델 gabe/g106(shrink 0.18, 윗면 등 + 아랫면 입), 부위 tube_l, tube_r. 뒤집기는 몸 앞축 pitch 180 도.
 * 소리 breathe, flip, crack. 글귀 cause_g106, cause_g106_bite, cause_g106_sink.
 *
 * <p>도감과 다른 점: 등딱지 반달 둘과 흙먼지 고리 부품 대신 닻 하나(윗면과 아랫면을 한 모델에, 뒤집기는 몸 회전). 흙먼지 고리는 숨 쉴 때 ShotFx
 * 흙 고리. 띄우기는 공용 LAUNCH(Players.launch, 이 묶음 도우미 {@link G66Giant.Flings} 를 거쳐). 위에서 난 사격은 공용 FIRELOG(game.mobs.shotsNear)로 셈.
 */
public final class G106 extends Gabe {

    static final double R = 7.0, T = 0.15, SPEED = 0.12, SETTLE = 2.5, HOVER_SET = 0.2, HOVER_CRAWL = 0.04;
    /** 숨: 간격, 위에 선 사람 피해, 느려짐, 발밑 들썩임 위 속도. */
    static final int BREATH = 80;
    static final double BREATH_DMG = 3, BREATH_SLOW = 0.25, BREATH_HOP = 0.2;
    /** 뜨거움: 가득, 위에 선 사람 한 틱, 위에서 쏜 한 발, 불. */
    static final double HEAT_MAX = 320, HEAT_MAN = 1.0, HEAT_SHOT = 6, HEAT_FIRE = 6;
    /** 뒤집기: 예고, 띄우는 높이(블록), 떨어짐 피해, 넘어짐, 아랫면 입 물기, 드러냄, 숨 막혀 드러냄. */
    static final int FLIP_TELL = 20, BELLY = 40, CHOKE = 160, FLIP_STEP = 5;
    static final double UP_H = 6, FALL_DMG = 26, BITE_DMG = 10, EDGE_DMG = 5;
    static final int FALL_ROOT = 20;
    /** 피해 배율: 등, 아랫면. 숨구멍 체력. */
    static final double BACK_MUL = 0.3, BELLY_MUL = 2.5, TUBE_HP = 120;
    static final double[][] TUBES = {{5.6, -2.6}, {-5.2, 3.4}};

    enum Phase { CRAWL, SETTLED, TELL, FLIP, BELLY, UNFLIP }

    private Phase phase = Phase.CRAWL;
    private int phaseAt, breathAt, bellyLen = BELLY;
    private double heat;
    private final double[] tubeHp = {-1, -1};
    private final boolean[] tubeGone = new boolean[2];
    private boolean belly;
    private final Vector goal = new Vector();
    private final G66Giant.Flings flings = new G66Giant.Flings();
    private final List<Player> landed = new ArrayList<>(4);
    private final Map<UUID, Long> edgeAt = new HashMap<>();
    private final List<double[]> sph = new ArrayList<>(24);
    private int sphAge = -1;
    private static final double[][] GRID;

    static {
        List<double[]> g = new ArrayList<>();
        g.add(new double[] {0, 0});
        for (int i = 0; i < 6; i++) g.add(new double[] {3.3 * Math.cos(i * Math.PI / 3), 3.3 * Math.sin(i * Math.PI / 3)});
        for (int i = 0; i < 12; i++) g.add(new double[] {5.9 * Math.cos(i * Math.PI / 6 + 0.26), 5.9 * Math.sin(i * Math.PI / 6 + 0.26)});
        GRID = g.toArray(new double[0][]);
    }

    public G106(Game game, Species sp) {
        super(game, sp);
        radius = 1.5;
        hover = HOVER_CRAWL;
        baseSpeed = SPEED;
        big = true;
        ghost = true;
        noPush = true;
        noMutate = true;
        noBite = true;
        ragKick = 0;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g106", 16f, 10f); // 뒤집힐 때 세로로 선 판(14)이 상자 밖으로 덜 나가게, 엔티티는 지표 구역 안
        body.viewRange(3f);
        body.turnTicks = 6;
    }

    @Override
    protected void spawned() {
        breathAt = age + 40;
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
        if (tubeHp[0] < 0) {
            double k = Math.max(0.5, maxHp / Math.max(1, sp.hp));
            tubeHp[0] = tubeHp[1] = TUBE_HP * k;
        }
        landed.clear();
        flings.tick(this, landed);
        for (Player p : landed) {
            game.players.root(p, FALL_ROOT, "g106_fall");
            if (belly && onTop(p, 1.0)) hit(p, BITE_DMG, "cause_g106_bite");
        }
        Player p = nearest(400);
        mark(p != null && dist(p) < 30 ? p : null);
        switch (phase) {
            case CRAWL -> crawl(p);
            case SETTLED -> settled(p);
            case TELL -> tell();
            case FLIP -> flip();
            case BELLY -> bellyUp();
            case UNFLIP -> unflip();
        }
        if (!belly && (age + id) % 10 == 0) edgeBites();
    }

    /** 사람이 이 등 위에 섰나(수평 반경 R - pad 안, 바닥 높이 둘레). */
    private boolean onTop(Player p, double pad) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) <= R - pad && Math.abs(l.getY() - floor()) < 1.6;
    }

    /** 무리 한가운데(가장 가까운 사람 곁 10 블록 안 서 있는 사람들). */
    private Vector crowd(Player p) {
        Vector c = new Vector();
        int n = 0;
        Location pl = p.getLocation();
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (l.distanceSquared(pl) > 100) continue;
            c.add(l.toVector());
            n++;
        }
        return n == 0 ? pl.toVector() : c.multiply(1.0 / n);
    }

    private void crawl(Player p) {
        hover = HOVER_CRAWL;
        if (p == null) {
            follow(speed());
            return;
        }
        if (every(10) || goal.lengthSquared() == 0) goal.copy(crowd(p));
        double h = Math.hypot(goal.getX() - pos.getX(), goal.getZ() - pos.getZ());
        if (h > SETTLE) {
            seek(goal.clone().setY(pos.getY()), speed());
            turnTo(goal.clone().setY(pos.getY()), 3f);
            if (every(14)) {
                Vector e = pos.clone().add(G66Giant.fwd(this).multiply(R));
                e.setY(floor());
                G66Giant.ring(this, e, 1.4, 8);
            }
        } else {
            hold();
            hover = HOVER_SET;
            breathAt = age + 30;
            phase(Phase.SETTLED);
        }
    }

    private void settled(Player p) {
        hold();
        hover = HOVER_SET;
        int onTop = 0;
        for (Player q : game.mobs.standing()) if (onTop(q, 0.3)) onTop++;
        heat += onTop * HEAT_MAN;
        if ((age + id) % 20 == 0) {
            heat += game.mobs.shotsNear(pos, R, 20) * HEAT_SHOT;
            if (game.shop.inFire(pos.clone().setY(floor() + 0.3))) heat += HEAT_FIRE * 20;
        }
        if (age >= breathAt) breathe();
        else if (age == breathAt - cd(BREATH) + 20) {
            body.offset.set(0, 0, 0);
            body.pose(20);
        }
        if (heat >= HEAT_MAX) {
            startTell();
            return;
        }
        // 사람이 다 떠나 멀어지면 다시 김
        if (onTop == 0 && p != null && (age + id) % 40 == 0) {
            Vector c = crowd(p);
            if (Math.hypot(c.getX() - pos.getX(), c.getZ() - pos.getZ()) > R + 3) {
                heat *= 0.5;
                goal.copy(c);
                phase(Phase.CRAWL);
            }
        }
    }

    /** 숨 한 번: 들썩(0.3 올라갔다 내려옴), 위에 선 사람 피해와 느려짐, 숨구멍 김. */
    private void breathe() {
        breathAt = age + cd(BREATH);
        body.offset.set(0, 0.3f, 0);
        body.pose(20);
        sound("breathe", Sound.ENTITY_RAVAGER_AMBIENT, 0.45f, 32);
        for (Player q : game.mobs.standing()) {
            if (!onTop(q, 0.3)) continue;
            hit(q, BREATH_DMG, "cause_g106_sink");
            game.players.slow(q, BREATH_SLOW, 50, "g106");
            Vector v = q.getVelocity();
            q.setVelocity(v.setY(Math.max(v.getY(), BREATH_HOP)));
        }
        G66Giant.ring(this, pos.clone().setY(floor()), R + 0.6, 16);
        for (int i = 0; i < 2; i++) {
            if (tubeGone[i] || !game.shotFx.room(ShotFx.EXTRA)) continue;
            Vector t = G66Giant.at(this, TUBES[i][0], T + 0.8, TUBES[i][1]);
            Location l = new Location(game.world, t.getX(), t.getY(), t.getZ());
            ItemDisplay d = game.shotFx.spawn(l, "fx_quad", ShotFx.SMOKE, 1, ShotFx.scale(0.8f), Display.Billboard.CENTER, false, 30, ShotFx.EXTRA);
            game.shotFx.grow(d, ShotFx.scale(2.4f), 26);
            game.shotFx.drift(d, l.clone().add(0, 1.6, 0), 28);
        }
    }

    private void startTell() {
        hold();
        sound("breathe", Sound.ENTITY_RAVAGER_ROAR, 0.4f, 32);
        phase(Phase.TELL);
    }

    /** 뒤집기 예고: 등딱지가 떨며 들썩. */
    private void tell() {
        hold();
        int t = inPhase();
        if (t % 4 == 0) body.squash(1.02f, t % 8 == 0 ? 2.2f : 1f, 1.02f, 2);
        if (t % 6 == 0) G66Giant.ring(this, pos.clone().setY(floor()), R + 0.3, 8);
        if (t >= cd(FLIP_TELL)) {
            body.squash(1f, 1f, 1f, 2);
            flipOver(BELLY);
        }
    }

    /** 위에 선 모두를 튕겨 올리고 앞축으로 뒤집음. */
    private void flipOver(int show) {
        bellyLen = show;
        sound("flip", Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 64);
        for (Player q : game.mobs.standing()) {
            if (!onTop(q, -0.5)) continue;
            Location l = q.getLocation();
            Vector out = new Vector(l.getX() - pos.getX(), 0, l.getZ() - pos.getZ());
            if (out.lengthSquared() > 1e-4) out.normalize().multiply(0.25);
            flings.launch(this, q, UP_H, out, FALL_DMG, -1, "cause_g106");
        }
        G66Giant.stomp(this, pos.clone().setY(floor()), 64, R + 4, 1f);
        if (game.horizon != null && game.mobs.standing().stream().anyMatch(q -> dist(q) < 20)) game.horizon.shake(0.2, 10);
        pitch = 90;
        body.offset.set(0, 4f, 0);
        body.pose(FLIP_STEP);
        heat = 0;
        phase(Phase.FLIP);
    }

    private void flip() {
        hold();
        if (inPhase() == FLIP_STEP) {
            pitch = 180;
            body.offset.set(0, 0.25f, 0);
            body.pose(FLIP_STEP + 1);
        }
        if (inPhase() >= FLIP_STEP * 2 + 1) {
            belly = true;
            G66Giant.ring(this, pos.clone().setY(floor()), R + 1, 12);
            phase(Phase.BELLY);
        }
    }

    private void bellyUp() {
        hold();
        if (inPhase() >= bellyLen) {
            belly = false;
            pitch = 270;
            body.offset.set(0, 3f, 0);
            body.pose(FLIP_STEP);
            sound("flip", Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 64);
            phase(Phase.UNFLIP);
        }
    }

    private void unflip() {
        hold();
        if (inPhase() == FLIP_STEP) {
            pitch = 360;
            body.offset.set(0, 0, 0);
            body.pose(FLIP_STEP + 1);
        }
        if (inPhase() >= FLIP_STEP * 2 + 2) {
            pitch = 0;
            body.pose(0);
            G66Giant.ring(this, pos.clone().setY(floor()), R + 1, 10);
            phase(Phase.CRAWL);
        }
    }

    /** 가장자리를 밟고 지나가는 사람 발목 물기(사람마다 1.5초에 한 번). */
    private void edgeBites() {
        long now = now();
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double h = Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
            if (h < R - 1.0 || h > R + 0.4 || Math.abs(l.getY() - floor()) > 1.6) continue;
            Long at = edgeAt.get(q.getUniqueId());
            if (at != null && now - at < 30) continue;
            edgeAt.put(q.getUniqueId(), now);
            hit(q, EDGE_DMG, "cause_g106_bite");
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        double y = pos.getY() + body.offset.y - 0.65;
        for (double[] g : GRID) {
            Vector w = G66Giant.at(this, g[0], 0, g[1]);
            sph.add(new double[] {w.getX(), y, w.getZ(), 1.9});
        }
        if (!belly) for (int i = 0; i < 2; i++) {
            Vector w = G66Giant.at(this, TUBES[i][0], T + 0.45, TUBES[i][1]);
            sph.add(new double[] {w.getX(), w.getY() + body.offset.y, w.getZ(), tubeGone[i] ? 0 : 0.5});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return pos;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (belly) return super.damage(d.times(BELLY_MUL));
        // 아무도 안 올라선 등에 불: 바로 뒤집힘
        if (d.type() == DamageType.FIRE && (phase == Phase.SETTLED || phase == Phase.CRAWL)) {
            boolean anyone = false;
            for (Player q : game.mobs.standing()) if (onTop(q, 0)) anyone = true;
            if (!anyone) heat = HEAT_MAX;
        }
        if (d.at() != null) {
            List<double[]> s = spheres();
            for (int i = 0; i < 2; i++) {
                int k = GRID.length + i;
                if (k >= s.size() || tubeGone[i]) continue;
                double[] c = s.get(k);
                if (d.at().distance(new Vector(c[0], c[1], c[2])) <= c[3] + 0.3) {
                    tubeHp[i] -= d.amount();
                    boolean ok = super.damage(d);
                    if (!dead && tubeHp[i] <= 0) breakTube(i);
                    return ok;
                }
            }
        }
        return super.damage(d.times(BACK_MUL));
    }

    private void breakTube(int i) {
        tubeGone[i] = true;
        body.look.on.add(i == 0 ? "tube_l" : "tube_r");
        sound("crack", Sound.ENTITY_ITEM_BREAK, 0.6f, 24);
        if (tubeGone[0] && tubeGone[1] && !belly && phase != Phase.FLIP && phase != Phase.UNFLIP) {
            // 숨이 막혀 스스로 뒤집혀 8초 드러냄
            flipOver(cd(CHOKE));
        }
    }

    @Override
    public void die(Damage d) {
        flings.clear();
        super.die(d);
    }

    @Override
    public void remove() {
        flings.clear();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " heat=" + Math.round(heat) + (belly ? " belly" : "") + " tubes=" + Math.round(tubeHp[0]) + "/"
            + Math.round(tubeHp[1]);
    }
}
