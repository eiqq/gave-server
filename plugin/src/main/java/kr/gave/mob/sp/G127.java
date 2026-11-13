package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.trip.Truck;

/**
 * G127 머리 탑 게이브(3막, 지원, 목표 압박). 1블록 얼굴 다섯을 쌓은 5.5블록 탑이 라운드 중간에 트럭에서 28~36블록 떨어진 땅을 뚫고 층층이 솟는다.
 * 움직이지 않는다.
 *
 * <p>10초마다(안테나 둘이 다 꺾이면 20초) 신호음이 빨라지다 딸깍, 다섯 얼굴이 한꺼번에 한 사람(최근 10초 처치 1위, 없으면 가장 가까운 사람) 쪽으로
 * 돌아간다: 그 사람 둘레 48블록 게이브가 10초 동안 그 사람만 노리고(FORCE), 그 사람이 받는 피해 1.5배(TAKEN), 머리 위 분홍 표시(남들만, 머리 칸),
 * 그 사람에게 재난문자 꼴 알림. 탑이 선 동안 반경 40블록 게이브는 서로 물어뜯지 않는다. 안테나가 남아 있으면 5초마다 12블록 안 사람에게 번쩍임 16.
 *
 * <p>맞음: 층마다 따로(층 체력 = 최대 체력 / 5), 층이 부서지면 위가 내려앉는다(남은 층 수 상태). 안테나 둘은 따로(최대 체력의 8% 씩, 탑 체력에 안 듦).
 * 거대한 몸(BIG): 폭발과 불이 층 표면에 닿는다.
 *
 * <p>팩 계약(tools/gabe/sp/g127.py): 모델 gabe/g127, 상태 ""/"snap", "l4"/"l4s" .. "l1"/"l1s", 부위 ant_l, ant_r. 머리 칸 gabe/g127_pin.
 * 소리 beep, click, zap, rise, crumble. 글귀 cause_g127, notice_g127_marked.
 *
 * <p>도감과 다른 점: 안테나는 부품 대신 탑 모델 안(깜빡임은 손으로 짠 자세 2). 층 돌아감은 상태 둘(흩어짐, 모두 앞) + 몸 방향으로.
 */
public final class G127 extends Gabe {

    static final double HOVER = 2.75, LAYER = 1.0, GAP = 0.1;
    /** 트럭(없으면 가운데)에서 놓는 거리, 솟기 전 기다림(틱, 무작위 범위), 솟는 층 하나 틱. */
    static final double R_MIN = 28, R_MAX = 36;
    static final int WAIT_MIN = 60, WAIT_MAX = 260, RISE_STEP = 12;
    /** 찍기 간격(안테나 하나라도 있을 때, 다 꺾였을 때), 찍기 유지, 찍힌 동안 얼굴이 앞을 보는 틱. */
    static final int MARK_CD = 200, MARK_CD_SLOW = 400, MARK_TICKS = 200, SNAP_HOLD = 40;
    static final double FORCE_R = 48, TAKEN = 1.5, NOBITE_R = 40;
    /** 안테나 번쩍임 간격, 반경, 피해. */
    static final int ZAP_CD = 100;
    static final double ZAP_R = 12, ZAP_DMG = 16;
    /** 안테나 체력 몫(최대 체력의). */
    static final double ANT_FRAC = 0.08;

    enum Phase { BURIED, RISE, STAND }

    private Phase phase = Phase.BURIED;
    private int phaseAt, riseAt, nextMark, nextZap, snapUntil;
    private int layers = 5, dropAt = -1;
    private final double[] layerHp = new double[5];
    private final double[] antHp = new double[2];
    private boolean init;
    private Player marked;
    private ItemStack pin;
    /** 사람마다 1초 간격 처치 수 10칸(최근 10초 처치 셈). */
    private final Map<UUID, int[]> kills = new HashMap<>();
    /** 물어뜯기를 꺼 준 둘레 게이브(탑이 사라지면 되돌림). */
    private final Set<Gabe> calmed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<double[]> sph = new ArrayList<>(7);
    private int sphAge = -1;

    public G127(Game game, Species sp) {
        super(game, sp);
        radius = 0.75;
        hover = HOVER;
        baseSpeed = 0;
        big = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        ragKick = 0.3;
        state("buried");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g127", 3.2f, 7f);
        body.viewRange(2.6f);
    }

    @Override
    protected void spawned() {
        body.offset.y = -7.8f;
        body.pose(0);
        untargetable = true;
        riseAt = age + WAIT_MIN + game.random.nextInt(WAIT_MAX - WAIT_MIN + 1);
        yaw = game.random.nextFloat() * 360f;
        if (game.director.cageSpawning()) return;
        // 트럭(없으면 가운데)에서 28~36블록 고리 위로(감독이 준 방향 그대로)
        Vector c = Truck.live() ? Truck.center() : new Vector(kr.gave.world.Plaza.CX + 0.5, 0, kr.gave.world.Plaza.CZ + 0.5);
        Vector d = pos.clone().subtract(c).setY(0);
        if (d.lengthSquared() < 1e-6) d = new Vector(1, 0, 0);
        double r = Math.max(R_MIN, Math.min(R_MAX, d.length()));
        if (Math.abs(r - d.length()) > 0.5) {
            Vector at = c.clone().add(d.normalize().multiply(r));
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
            game.mobs.relocate(this, at);
        }
    }

    private void initHp() {
        init = true;
        for (int i = 0; i < 5; i++) layerHp[i] = maxHp / 5;
        antHp[0] = antHp[1] = maxHp * ANT_FRAC;
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
        if (!init) initHp();
        hold();
        switch (phase) {
            case BURIED -> {
                if (age >= riseAt) {
                    phase(Phase.RISE);
                    sound("rise", Sound.ENTITY_WARDEN_EMERGE, 0.8f, 48);
                }
            }
            case RISE -> rise();
            case STAND -> stand();
        }
        if (age % 20 == 0) noteKills();
        if (dropAt >= 0 && age >= dropAt) {
            dropAt = -1;
            body.offset.y = 0;
            body.pose(6);
        }
    }

    /** 땅을 뚫고 한 층씩 솟음(솟는 동안은 아직 안 맞음, 다 솟으면 맞음). */
    private void rise() {
        int t = inPhase();
        if (t == 1) state(stateName());
        int step = t / RISE_STEP;
        if (t % RISE_STEP == 0 && step <= 6) {
            float y = (float) Math.min(0, -7.8 + step * 1.3);
            body.offset.y = y;
            body.pose(RISE_STEP);
            dirt(2.0 + step * 0.2);
            if (step >= 1) sound("crumble", Sound.BLOCK_ROOTED_DIRT_BREAK, 0.7f, 32);
        }
        if (t >= RISE_STEP * 7) {
            body.offset.y = 0;
            untargetable = false;
            nextMark = age + 40;
            nextZap = age + cd(ZAP_CD);
            phase(Phase.STAND);
        }
    }

    private void dirt(double r) {
        if (!game.shotFx.room(ShotFx.EXTRA)) return;
        game.shotFx.spawn(new Location(game.world, pos.getX(), floor() + 0.06, pos.getZ()), "fx_flat", ShotFx.RING, 0, ShotFx.scale((float) (r * 2)),
            Display.Billboard.FIXED, false, 12, ShotFx.EXTRA);
    }

    private void stand() {
        // 신호음: 찍기 전 60틱 동안 점점 빨라짐
        int left = nextMark - age;
        if (left == 60 || left == 44 || left == 32 || left == 23 || left == 16 || left == 11 || left == 7 || left == 4 || left == 2)
            sound("beep", Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 48);
        if (age >= nextMark) markOne();
        if (snapUntil > 0 && age >= snapUntil) {
            snapUntil = 0;
            mark(null);
            target = marked != null && standing(marked) ? marked : null;
        }
        if (snapUntil == 0) yaw += 0.6f;
        state(stateName());
        if (age >= nextZap) {
            nextZap = age + cd(ZAP_CD);
            zap();
        }
        if (age % 10 == 0) calmKin();
        // 찍힌 사람이 쓰러지면 풂
        if (marked != null && !standing(marked)) release();
    }

    private String stateName() {
        boolean snap = snapUntil > 0;
        if (layers >= 5) return snap ? "snap" : "";
        return "l" + layers + (snap ? "s" : "");
    }

    private boolean antennas() {
        return antHp[0] > 0 || antHp[1] > 0;
    }

    /** 10초 처치 셈: 1초마다 사람마다 처치 수를 적음. */
    private void noteKills() {
        int slot = (age / 20) % 10;
        for (Player p : game.mobs.standing()) {
            Guns.Arms a = game.guns.arms(p);
            if (a == null) continue;
            int[] h = kills.computeIfAbsent(p.getUniqueId(), k -> {
                int[] x = new int[10];
                java.util.Arrays.fill(x, a.kills);
                return x;
            });
            h[slot] = a.kills;
        }
    }

    private Player topKiller() {
        int slot = (age / 20) % 10, old = (slot + 1) % 10;
        Player best = null;
        int bn = 0;
        for (Player p : game.mobs.standing()) {
            int[] h = kills.get(p.getUniqueId());
            Guns.Arms a = game.guns.arms(p);
            if (h == null || a == null) continue;
            int n = a.kills - h[old];
            if (n > bn) {
                bn = n;
                best = p;
            }
        }
        return best != null ? best : nearest(400);
    }

    /** 딸깍: 다섯 얼굴이 한 사람 쪽으로, 판의 게이브가 그 사람을 노림. */
    private void markOne() {
        nextMark = age + cd(antennas() ? MARK_CD : MARK_CD_SLOW);
        Player p = topKiller();
        if (p == null) return;
        release();
        marked = p;
        Vector c = chest(p);
        double dx = c.getX() - pos.getX(), dz = c.getZ() - pos.getZ();
        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = 0;
        body.pose(1);
        snapUntil = age + SNAP_HOLD;
        mark(p);
        sound("click", Sound.BLOCK_LEVER_CLICK, 0.6f, 48);
        game.mobs.force(this, p, FORCE_R, MARK_TICKS);
        game.players.taken(p, "g127", TAKEN, MARK_TICKS);
        game.hud.alert(p, "notice_g127_marked", 4, 60);
        PState s = game.players.get(p);
        if (s.headItem == null) {
            if (pin == null) pin = Body.item("gabe/g127_pin", "", new LookState(), game.worldTime());
            game.players.attachHead(p, pin, this);
        }
    }

    /** 찍기 풂(받는 피해 배율, 강제 표적, 머리 표시). */
    private void release() {
        if (marked == null) return;
        game.players.taken(marked, "g127", 1, 0);
        game.mobs.unforce(this);
        PState s = game.players.get(marked);
        if (s.headBy == this) game.players.attachHead(marked, null);
        marked = null;
    }

    /** 안테나 번쩍임: 12블록 안 서 있는 사람마다 16. */
    private void zap() {
        if (!antennas()) return;
        Vector tip = antennaTip(antHp[0] > 0 ? 0 : 1);
        boolean any = false;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) > ZAP_R) continue;
            hit(p, ZAP_DMG, "cause_g127");
            game.shotFx.bolt(new Location(game.world, tip.getX(), tip.getY(), tip.getZ()), new Location(game.world, l.getX(), l.getY() + 1.0, l.getZ()), null);
            any = true;
        }
        if (any) sound("zap", Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.4f, 24);
    }

    /** 탑이 선 동안 반경 40블록 게이브는 서로 물어뜯지 않음(꺼 준 것만 기억했다가 되돌림). */
    private void calmKin() {
        double r2 = NOBITE_R * NOBITE_R;
        calmed.removeIf(g -> {
            if (g.dead) return true;
            if (g.pos.distanceSquared(pos) > r2) {
                g.noBite = false;
                return true;
            }
            return false;
        });
        for (Gabe g : game.mobs.near(pos, NOBITE_R, g -> g != this && !g.dead && !g.noBite && !g.sp.is(Role.BOSS))) {
            g.noBite = true;
            calmed.add(g);
        }
    }

    private void uncalm() {
        for (Gabe g : calmed) if (!g.dead) g.noBite = false;
        calmed.clear();
    }

    // ------------------------------------------------------------------ 몸 틀

    /** 몸 틀 점(+X 몸 왼쪽, +Y 위, +Z 앞) → 세계. */
    private Vector at(double x, double y, double z) {
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
        return new Vector(pos.getX() + fz * x + fx * z, pos.getY() + y, pos.getZ() - fx * x + fz * z);
    }

    private double layerY(int i) {
        return -HOVER + LAYER / 2 + i * (LAYER + GAP);
    }

    private double topY() {
        return -HOVER + layers * (LAYER + GAP) - GAP;
    }

    private Vector antennaTip(int i) {
        double side = i == 0 ? 1 : -1;
        double dx = side * 0.45, l = Math.hypot(dx, 1.0);
        return at(side * 0.22 + dx / l * 1.5, topY() + 1.0 / l * 1.5, 0);
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        for (int i = 0; i < layers; i++) sph.add(new double[] {pos.getX(), pos.getY() + layerY(i), pos.getZ(), 0.72});
        for (int i = 0; i < 2; i++) {
            if (antHp[i] <= 0) continue;
            Vector t = antennaTip(i);
            sph.add(new double[] {t.getX(), t.getY(), t.getZ(), 0.38});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return pos.clone().add(new Vector(0, topY() - LAYER / 2, 0));
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) initHp();
        Vector at = d.at() != null ? d.at() : pos;
        // 맞은 점에 가장 가까운 구(층 또는 안테나)
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < layers; i++) {
            double dd = Math.hypot(Math.hypot(at.getX() - pos.getX(), at.getZ() - pos.getZ()), at.getY() - (pos.getY() + layerY(i))) - 0.72;
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        for (int i = 0; i < 2; i++) {
            if (antHp[i] <= 0) continue;
            double dd = at.distance(antennaTip(i)) - 0.38;
            if (dd < bd - 0.05) {
                bd = dd;
                best = 10 + i;
            }
        }
        if (best >= 10) {
            int i = best - 10;
            double eff = Math.min(antHp[i], d.amount());
            antHp[i] -= d.amount();
            game.mobs.onHurt(this, d, eff);
            if (antHp[i] <= 0) {
                body.look.on.add(i == 0 ? "ant_l" : "ant_r");
                sound("crumble", Sound.BLOCK_CHAIN_BREAK, 1.3f, 32);
            }
            return true;
        }
        if (best < 0) return false;
        double before = hp, eff = Math.min(layerHp[best], d.amount());
        layerHp[best] -= d.amount();
        if (layerHp[best] <= 0) breakLayer(best);
        hp = 0;
        for (int i = 0; i < layers; i++) hp += Math.max(0, layerHp[i]);
        game.mobs.onHurt(this, d, Math.min(before - hp, eff));
        if (layers <= 0 || hp <= 0) die(d);
        return true;
    }

    /** 층 하나가 부서짐: 위층들이 한 칸씩 내려앉음. */
    private void breakLayer(int i) {
        for (int k = i; k < layers - 1; k++) layerHp[k] = layerHp[k + 1];
        layers--;
        if (layers >= 0 && layers < 5) layerHp[layers] = 0;
        sound("crumble", Sound.BLOCK_ROOTED_DIRT_BREAK, 0.6f, 32);
        dirt(1.6);
        if (layers > 0) {
            // 남은 층이 한 칸 위에서 내려앉는 꼴: 이번 틱은 위로 올려 바로 보내고 다음 틱에 6틱 동안 내림
            state(stateName());
            body.offset.y = (float) (LAYER + GAP);
            body.pose(0);
            dropAt = age + 1;
        }
    }

    @Override
    public void die(Damage d) {
        if (!dead) {
            release();
            uncalm();
        }
        super.die(d);
    }

    @Override
    public void remove() {
        release();
        uncalm();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " layers=" + layers + " ant=" + (antHp[0] > 0 ? 1 : 0) + (antHp[1] > 0 ? 1 : 0)
            + (marked != null ? " marked=" + marked.getName() : "") + " calm=" + calmed.size();
    }
}
