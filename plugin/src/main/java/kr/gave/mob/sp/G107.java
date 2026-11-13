package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.game.Shop;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G107 솟구쳐 덮는 게이브(2막, 돌격, 정예). 길이 16 블록 물고기 얼굴이 땅속을 헤엄친다: 땅 위로는 등지느러미 막대기 다섯만 흙을 가르며 트럭 둘레를
 * 돈다(0.3 블록/틱). 사람 곁을 지날 때 꼬리만 솟아 후려친다(26, 날림, 걸린 한 사람은 땅속으로 끌려가 갇힘: 쳐든 꼬리의 작은 얼굴을 쏘면 풀림).
 * 20초마다 둘레 한쪽에서 땅이 둥글게 부풀고(2초, 뛰어들 자리에 그림자) 온몸이 솟구쳐 가슴지느러미를 펴고 하늘을 덮으며 둘레 위를 넘어가(3초)
 * 반대편 땅으로 뛰어든다: 솟는 자리 반경 5 에 30, 넘어가며 배에서 떨어지는 얼굴 비가 둘레 안 여섯 자리 반경 2 에 18, 뛰어드는 자리 반경 6 에 40.
 * 땅속 몸은 안 맞고 등지느러미 막대기(각 180)만 맞는다. 다섯을 다 꺾으면 땅 위로 떠올라 10초 퍼덕이며 온몸이 드러난다. 넘어가는 동안의 배는 x2.
 *
 * <p>팩 계약(tools/gabe/sp/g107.py): 모델 gabe/g107(shrink 0.135), 상태 ""(헤엄), "spread"(솟구침), "tail"(꼬리 쳐듦), 부위 fin0..fin4.
 * 소리 deep, breach, slap, snap. 글귀 cause_g107, cause_g107_tail, cause_g107_rain.
 *
 * <p>도감과 다른 점: 디스플레이 여섯 대신 닻 하나(상태 셋, 몸 기울기는 pitch, 솟구침은 그동안 하늘 몹으로 몸 자리를 포물선으로). 그림자 판 대신 빨간 예고 고리(ShotFx).
 * 맞는 구는 그림 offset 까지 더함. 하늘 덮기는 horizon.dusk 잠깐 + shake.
 */
public final class G107 extends Gabe {

    static final double HOVER_UNDER = -2.4, SWIM = 0.3, CIRCLE_R = 16, PEAK = 18;
    static final int BREACH_CD = 400, TELL = 40, FLIGHT = 60, SURFACE = 200;
    static final double RISE_R = 5, RISE_DMG = 30, LAND_R = 6, LAND_DMG = 40, RAIN_R = 2, RAIN_DMG = 18;
    static final int RAIN_TELL = 16;
    /** 꼬리: 사람이 이 거리 안이면, 맞는 반경, 피해, 갇힐 몫, 쿨다운, 쳐든 꼬리 체력. */
    static final double TAIL_NEAR = 5, TAIL_R = 4, TAIL_DMG = 26, TAIL_TRAP = 0.35, TAIL_HP = 60;
    static final int TAIL_LEN = 16, TAIL_HIT = 6, TAIL_CD = 80;
    static final double FIN_HP = 180, BELLY_MUL = 2.0;
    static final double[] FIN_Z = {3.0, 1.5, 0.0, -1.5, -3.0};

    enum Phase { APPROACH, CIRCLE, TELL, BREACH, TAIL, SURFACED }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, breachAt, tailAt, finsLeft = 5;
    private double theta;
    private int dir = 1;
    private final double[] finHp = {-1, -1, -1, -1, -1};
    private final boolean[] finGone = new boolean[5];
    private double tailHp;
    private final Vector from = new Vector(), to = new Vector();
    private final List<double[]> rain = new ArrayList<>(); // x, y, z, 맞는 틱
    private final List<double[]> sph = new ArrayList<>(8);
    private int sphAge = -1;
    private boolean exposed, surfaced;

    public G107(Game game, Species sp) {
        super(game, sp);
        radius = 2.5;
        hover = HOVER_UNDER;
        baseSpeed = SWIM;
        big = true;
        ghost = true;
        noPush = true;
        noMutate = true;
        noBite = true;
        ragKick = 0.2;
    }

    @Override
    protected void build() {
        // 컬링 상자 높이 14(닻 엔티티는 몸 가운데 7 아래): 엔티티가 땅속 깊이(지표 구역 아래) 있으면 클라가 그 구역을 안 그려 몸 전체가 안 보인다
        // (2026-11-12 시험: 높이 48 이면 엔티티가 땅 밑 24 블록에 놓여 안 보였음). 솟구침은 몸 자리(pos)를 실제로 올려(하늘 몹) 상자가 따라감.
        body = Body.of(this, "gabe/g107", 22f, 14f);
        body.viewRange(4f);
        body.turnTicks = 5;
    }

    @Override
    protected void spawned() {
        breachAt = age + 160;
        dir = game.random.nextBoolean() ? 1 : -1;
        if (!game.director.cageSpawning()) sound("deep", Sound.ENTITY_RAVAGER_ROAR, 0.5f, 96);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private static Vector center() {
        return Shop.counterCenter();
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (finHp[0] < 0) {
            double k = Math.max(0.5, maxHp / Math.max(1, sp.hp));
            for (int i = 0; i < 5; i++) finHp[i] = FIN_HP * k;
            tailHp = TAIL_HP * k;
        }
        rainTick();
        Player p = nearest(400);
        mark(p != null && dist(p) < 40 ? p : null);
        switch (phase) {
            case APPROACH -> approach(p);
            case CIRCLE -> circle(p);
            case TELL -> tell();
            case BREACH -> breach();
            case TAIL -> tail();
            case SURFACED -> surfaced();
        }
    }

    private void wake() {
        // 땅을 가르는 흙 물결(등지느러미 앞)
        if (!every(6) || !game.shotFx.room(ShotFx.EXTRA)) return;
        Vector f = G66Giant.at(this, 0, 0, 4.0);
        Location l = new Location(game.world, f.getX(), floor() + 0.3, f.getZ());
        ItemDisplay d = game.shotFx.spawn(l, "fx_quad", ShotFx.DUST, 1, ShotFx.scale(1.4f), Display.Billboard.CENTER, false, 16, ShotFx.EXTRA);
        game.shotFx.grow(d, ShotFx.scale(3.2f), 14);
    }

    private void approach(Player p) {
        Vector c = center();
        double h = Math.hypot(pos.getX() - c.getX(), pos.getZ() - c.getZ());
        if (h <= CIRCLE_R + 2) {
            theta = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
            phase(Phase.CIRCLE);
            return;
        }
        seek(c.clone().setY(pos.getY()), speed());
        face(c.clone().setY(pos.getY()));
        wake();
        if (age % 160 == 0) sound("deep", Sound.ENTITY_RAVAGER_ROAR, 0.5f, 96);
    }

    /** 둘레를 돈다. 사람 곁을 지나면 꼬리, 때가 되면 솟구칠 준비. */
    private void circle(Player p) {
        Vector c = center();
        double v = speed();
        theta += dir * v / CIRCLE_R;
        Vector want = c.clone().add(new Vector(Math.cos(theta) * CIRCLE_R, 0, Math.sin(theta) * CIRCLE_R));
        Vector d = want.clone().subtract(pos).setY(0);
        if (d.length() > 1e-6) vel.copy(d.normalize().multiply(Math.min(v * 1.4, d.length() + v)));
        Vector tangent = new Vector(-Math.sin(theta) * dir, 0, Math.cos(theta) * dir);
        face(pos.clone().add(tangent));
        wake();
        boolean holding = !game.players.trappedIn(this).isEmpty();
        state(holding ? "tail" : "");
        if (age % 200 == 0) sound("deep", Sound.ENTITY_RAVAGER_ROAR, 0.5f, 96);
        if (age >= breachAt) {
            from.copy(pos);
            Vector across = c.clone().subtract(pos).setY(0);
            if (across.lengthSquared() < 1e-4) across = new Vector(1, 0, 0);
            to.copy(c.clone().add(across.normalize().multiply(CIRCLE_R)));
            to.setY(game.mobs.flow.floor(to.getX(), to.getZ()));
            Vector s = from.clone().setY(floor());
            G66Giant.warn(this, s, RISE_R, cd(TELL) + 2);
            G66Giant.warn(this, to, LAND_R, cd(TELL) + FLIGHT + 2);
            sound("deep", Sound.ENTITY_RAVAGER_ROAR, 0.5f, 96);
            sound("breach", Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 64);
            hold();
            phase(Phase.TELL);
            return;
        }
        if (!holding && age >= tailAt) {
            Vector tp = tailPoint();
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                if (Math.hypot(l.getX() - tp.getX(), l.getZ() - tp.getZ()) <= TAIL_NEAR) {
                    state("tail");
                    sound("slap", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 32);
                    phase(Phase.TAIL);
                    return;
                }
            }
        }
    }

    private Vector tailPoint() {
        Vector t = G66Giant.at(this, 0, 0, -8.5);
        t.setY(floor());
        return t;
    }

    /** 꼬리만 솟아 후려침: 둘레 사람 26 과 날림, 가장 가까운 한 사람은 몫에 따라 땅속에 갇힘. */
    private void tail() {
        Vector c = center();
        theta += dir * speed() * 0.4 / CIRCLE_R;
        hold();
        if (inPhase() == TAIL_HIT) {
            Vector tp = tailPoint();
            Player near = null;
            double nd = 99;
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                double h = Math.hypot(l.getX() - tp.getX(), l.getZ() - tp.getZ());
                if (h > TAIL_R || Math.abs(l.getY() - tp.getY()) > 4) continue;
                hit(q, TAIL_DMG, "cause_g107_tail");
                knock(q, new Vector(l.getX() - tp.getX(), 0, l.getZ() - tp.getZ()), 0.9, 0.5);
                if (h < nd) {
                    nd = h;
                    near = q;
                }
            }
            G66Giant.ring(this, tp, TAIL_R, 10);
            if (near != null && game.random.nextDouble() < TAIL_TRAP && game.players.trappedIn(this).isEmpty()) {
                game.players.trap(near, this);
                double k = Math.max(0.5, maxHp / Math.max(1, sp.hp));
                tailHp = TAIL_HP * k;
            }
        }
        if (inPhase() >= TAIL_LEN) {
            tailAt = age + cd(TAIL_CD);
            state(game.players.trappedIn(this).isEmpty() ? "" : "tail");
            theta = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
            phase(Phase.CIRCLE);
        }
    }

    /** 솟구치기 전: 땅이 둥글게 부풂(흙 고리가 거듭 일어남). */
    private void tell() {
        hold();
        if (inPhase() % 8 == 0) G66Giant.ring(this, from.clone().setY(floor()), RISE_R * (0.5 + inPhase() / (double) cd(TELL)), 10);
        if (inPhase() % 10 == 0) {
            body.offset.set(0, 0.6f, 0);
            body.pose(5);
        } else if (inPhase() % 10 == 5) {
            body.offset.set(0, 0, 0);
            body.pose(5);
        }
        if (inPhase() >= cd(TELL)) startBreach();
    }

    private void startBreach() {
        state("spread");
        exposed = true;
        flying = true;
        face(to.clone().setY(pos.getY()));
        sound("breach", Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 64);
        Vector s = from.clone().setY(floor());
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (Math.hypot(l.getX() - s.getX(), l.getZ() - s.getZ()) > RISE_R || Math.abs(l.getY() - s.getY()) > 4) continue;
            hit(q, RISE_DMG, "cause_g107");
            knock(q, new Vector(l.getX() - s.getX(), 0, l.getZ() - s.getZ()), 0.5, 0.9);
        }
        G66Giant.stomp(this, s, 64, 10, 1f);
        G66Giant.ring(this, s, RISE_R + 1, 14);
        if (game.horizon != null) {
            game.horizon.dusk(6000, FLIGHT + 10);
            game.horizon.shake(0.8, 15);
        }
        // 넘어가며 떨어뜨릴 얼굴 비 여섯
        Vector c = center();
        List<Player> in = new ArrayList<>();
        for (Player q : game.mobs.standing()) if (Math.hypot(q.getLocation().getX() - c.getX(), q.getLocation().getZ() - c.getZ()) <= 16) in.add(q);
        for (int i = 0; i < 6; i++) {
            Vector at;
            if (!in.isEmpty() && i < 4) {
                Location l = in.get(game.random.nextInt(in.size())).getLocation();
                at = new Vector(l.getX() + game.random.nextGaussian() * 1.2, 0, l.getZ() + game.random.nextGaussian() * 1.2);
            } else {
                double a = game.random.nextDouble() * Math.PI * 2, r = Math.sqrt(game.random.nextDouble()) * 11;
                at = c.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r));
            }
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()));
            rain.add(new double[] {at.getX(), at.getY(), at.getZ(), now() + 18 + i * 5 + RAIN_TELL, 0});
        }
        phase(Phase.BREACH);
    }

    /** 솟구쳐 넘어감: 수평으로 from → to, 그림은 포물선으로 들어 올림, 코가 들렸다 숙여짐. */
    private void breach() {
        int t = inPhase();
        double u = Math.min(1.0, t / (double) FLIGHT);
        Vector step = to.clone().subtract(from).setY(0).multiply(1.0 / FLIGHT);
        vel.setX(step.getX()).setZ(step.getZ());
        // 몸 자리를 포물선으로 올림(땅 위 0.35 아래로는 못 내려가므로 땅속 몫은 그림 offset 으로)
        double ground = floor();
        double want = ground + HOVER_UNDER + 4 * (PEAK - HOVER_UNDER) * u * (1 - u);
        double base = Math.max(want, ground + 0.35);
        vel.setY(base - pos.getY());
        float off = (float) (want - base);
        if (t % 2 == 0 || Math.abs(off - body.offset.y) > 0.3f) {
            body.offset.set(0, off, 0);
            pitch = (float) ((u - 0.5) * 100);
            body.pose(2);
        }
        if (t >= FLIGHT) land();
    }

    private void land() {
        hold();
        flying = false;
        exposed = false;
        pitch = 0;
        body.offset.set(0, 0, 0);
        body.pose(3);
        state(game.players.trappedIn(this).isEmpty() ? "" : "tail");
        Vector e = pos.clone().setY(floor());
        sound("breach", Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 64);
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (Math.hypot(l.getX() - e.getX(), l.getZ() - e.getZ()) > LAND_R || Math.abs(l.getY() - e.getY()) > 4) continue;
            hit(q, LAND_DMG, "cause_g107");
            knock(q, new Vector(l.getX() - e.getX(), 0, l.getZ() - e.getZ()), 0.7, 0.5);
        }
        G66Giant.stomp(this, e, 64, 12, 1f);
        G66Giant.ring(this, e, LAND_R + 1, 14);
        Vector c = center();
        theta = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
        dir = -dir;
        breachAt = age + cd(BREACH_CD);
        phase(Phase.CIRCLE);
    }

    private void rainTick() {
        if (rain.isEmpty()) return;
        long now = now();
        for (Iterator<double[]> it = rain.iterator(); it.hasNext();) {
            double[] r = it.next();
            Vector at = new Vector(r[0], r[1], r[2]);
            if (r[4] == 0 && now >= r[3] - RAIN_TELL) {
                r[4] = 1;
                G66Giant.warn(this, at, RAIN_R, RAIN_TELL + 2);
                if (game.shotFx.room(ShotFx.NORMAL)) {
                    Vector b = pos.clone().add(new Vector(0, body.offset.y - 1.5, 0));
                    Location l = new Location(game.world, b.getX(), b.getY(), b.getZ());
                    ItemDisplay d = game.shotFx.spawn(l, "fx_quad", ShotFx.BLOOD, ShotFx.BLOOD_PINK, ShotFx.scale(1.8f), Display.Billboard.CENTER, false,
                        RAIN_TELL + 4, ShotFx.NORMAL);
                    game.shotFx.drift(d, new Location(game.world, r[0], r[1] + 0.6, r[2]), RAIN_TELL);
                }
            }
            if (now >= r[3]) {
                for (Player q : game.mobs.standing()) {
                    Location l = q.getLocation();
                    if (Math.hypot(l.getX() - r[0], l.getZ() - r[2]) <= RAIN_R && Math.abs(l.getY() - r[1]) < 3) hit(q, RAIN_DMG, "cause_g107_rain");
                }
                G66Giant.ring(this, at, RAIN_R, 8);
                it.remove();
            }
        }
    }

    /** 등지느러미 다섯이 다 꺾임: 땅 위로 떠올라 10초 퍼덕임(온몸이 드러남). */
    private void surfaced() {
        hold();
        int t = inPhase();
        if (t % 10 == 0) {
            body.roll = (t / 10) % 2 == 0 ? 18 : -18;
            body.squash(1.05f, 0.92f, 1f, 4);
        }
        if (t >= SURFACE) {
            surfaced = false;
            exposed = false;
            body.roll = 0;
            body.offset.set(0, 0, 0);
            body.pose(10);
            Vector c = center();
            theta = Math.atan2(pos.getZ() - c.getZ(), pos.getX() - c.getX());
            phase(Phase.CIRCLE);
        }
    }

    // ------------------------------------------------------------------ 맞음

    /** 몸 틀 점(pitch 까지 돌림) → 세계, 그림 들어 올림(offset) 포함. */
    private Vector pt(double x, double y, double z) {
        double a = Math.toRadians(pitch);
        double y2 = y * Math.cos(a) - z * Math.sin(a), z2 = y * Math.sin(a) + z * Math.cos(a);
        Vector w = G66Giant.at(this, x, y2, z2);
        w.setY(w.getY() + body.offset.y);
        return w;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        if (exposed || surfaced) {
            add(pt(0, 0, 5.0), 2.7);
            add(pt(0, 0, 0.5), 2.3);
            add(pt(0, 0, -3.5), 1.8);
            add(pt(0, 0, -6.4), 1.3);
        } else {
            for (int i = 0; i < 5; i++) add(pt(0, 2.0 + 1.5, FIN_Z[i] - (0.35 + 0.1 * i) * 1.5), finGone[i] ? 0 : 0.6);
            if (!game.players.trappedIn(this).isEmpty() || phase == Phase.TAIL) add(pt(0, 2.2, -10.0), 1.3);
        }
        sphAge = age;
        return sph;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (exposed) return super.damage(phase == Phase.BREACH ? d.times(BELLY_MUL) : d);
        if (surfaced) return super.damage(d);
        if (d.at() == null) return false;
        List<double[]> s = spheres();
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < s.size(); i++) {
            double[] c = s.get(i);
            if (c[3] <= 0) continue;
            double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        if (best < 0 || bd > 1.2) return false;
        if (best == 5) {
            // 쳐든 꼬리의 작은 얼굴: 갇힌 사람이 풀림
            tailHp -= d.amount();
            boolean ok = super.damage(d);
            if (!dead && tailHp <= 0) {
                game.players.freeFrom(this);
                state("");
            }
            return ok;
        }
        finHp[best] -= d.amount();
        boolean ok = super.damage(d);
        if (!dead && !finGone[best] && finHp[best] <= 0) {
            finGone[best] = true;
            finsLeft--;
            body.look.on.add("fin" + best);
            sound("snap", Sound.ENTITY_ITEM_BREAK, 0.6f, 24);
            if (finsLeft <= 0 && phase != Phase.BREACH) {
                surfaced = true;
                rain.clear();
                state("");
                body.offset.set(0, (float) (-HOVER_UNDER + 2.1), 0);
                body.pose(12);
                sound("breach", Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 64);
                phase(Phase.SURFACED);
            }
        }
        return ok;
    }

    @Override
    public void die(Damage d) {
        rain.clear();
        // 땅속에서 죽으면 사체가 땅 위에 보이게 그림을 땅 위로(래그돌은 pos + offset 에서 시작)
        if (body != null && body.offset.y < -HOVER_UNDER + 2.0) body.offset.set(0, (float) (-HOVER_UNDER + 2.1), 0);
        pitch = 0;
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " fins=" + finsLeft + (exposed ? " exposed" : "") + (surfaced ? " surfaced" : "") + " breachIn="
            + (breachAt - age);
    }
}
