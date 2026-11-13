package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G99 붓 게이브(2막, 함정). 2블록 붓대 아래 끝에 얼굴이 꿰인 붓이 머리 높이로 날아와 무리에서 가장 바깥 사람 둘레를 1.5초에 한 바퀴 돌며 바닥에
 * 먹 원(반지름 2.5)을 그린다. 원이 닫히면 한가운데 위로 올라가 3초마다 0.5초 멈췄다 내려찍는다(안의 사람 모두 14).
 *
 * <p>닫힌 먹 원: 안의 사람은 먹 줄에서 되밀려 못 나가고(PUSH), 그 안에서는 빗나감이 없다(NOMISS 깃발), 둘레 16블록 게이브가 갇힌 사람에게 몰린다
 * (FORCE). 밖의 동료가 먹 줄을 밟고 지나가면 그 조각이 지워져 틈이 된다. 15초 뒤 먹이 마르며 한 번 조여 안에 남은 사람에게 20.
 * 그리는 중에 붓을 맞히면(최대 체력 6%) 붓이 튕겨 줄이 끊긴다.
 *
 * <p>몸: 붓(닻, gabe/g99, 상태 "" / "aim") + 먹 부품(gabe/g99_ink). 먹 원은 따로 놓는 바닥 개체 {@link G99Ring}(gabe/g99_ring, 호 조각 on:s0..s11,
 * 상태 "dry"). 소리 stroke, close, stamp. 글귀 cause_g99, cause_g99_dry, notice_g99_ring.
 *
 * <p>도감과 다른 점: 먹 원을 효과 예산(ShotFx) 대신 바닥 디스플레이 하나(조각은 부위 켜기)로 그린다(효과 예산 50 을 붓 하나가 12 씩 쓰지 않게).
 * 주변 게이브는 끌기(lure: 그동안 안 때림) 대신 강제 표적으로 몰린다. 마르는 시간 15초는 박자 배율 없이 고정.
 */
public final class G99 extends Gabe {

    static final double R = 2.5;
    /** 붓 모델 가운데에서 붓끝까지(블록), 그릴 때와 머물 때 바닥 위 붓 가운데 높이, 내려찍을 때 높이. */
    static final double TIP = 2.05, DRAW_Y = 2.12, HOLD_Y = 4.7, STAMP_Y = 2.6;
    static final double SPEED = 0.22;
    /** 그리기 틱(한 바퀴), 내려찍기 간격, 멈칫, 마르기(고정), 조이기 틱, 튕긴 뒤 쉬기. */
    static final int DRAW = 30, STAMP_CD = 60, PAUSE = 10, DRY = 300, SQUEEZE = 12, RECOVER = 50;
    static final double STAMP_DMG = 14, SQUEEZE_DMG = 20, BOUNCE_FRAC = 0.06, FORCE_R = 16;
    /** 맞는 구(몸 가운데 기준 높이, 반지름). */
    static final double[][] SPH = {{-0.68, 0.5}, {0.4, 0.28}, {1.2, 0.28}, {-1.5, 0.42}};

    enum Phase { APPROACH, DRAW, RISE, HOLD, PAUSE, STAMP, SQUEEZE, RECOVER }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, closedAt, nextStamp, nextStroke;
    private Player prey;
    private final Vector center = new Vector();
    private double startA, drawDmg;
    G99Ring ring;
    private Body.Part ink;
    private final Set<UUID> inside = new HashSet<>();
    private final List<double[]> sph = new ArrayList<>(4);
    private int sphAge = -1;

    public G99(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        flying = true;
        noPush = true;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g99", 1.6f, 4.4f);
        ink = body.attach("gabe/g99_ink");
        inkPose(0);
    }

    @Override
    protected void spawned() {
        body.look.phase = game.random.nextInt(12);
    }

    private void inkPose(int ticks) {
        if (ink == null) return;
        double b = Math.sin((age + id) * 0.12) * 0.12;
        ink.pose(new Matrix4f().translate(0.78f, (float) (-0.55 + b), 0.18f).rotateZ((float) Math.toRadians(-25)), ticks);
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
        if (age % 10 == 0) inkPose(10);
        switch (phase) {
            case APPROACH -> approach();
            case DRAW -> draw();
            case RISE -> rise();
            case HOLD -> holdTick();
            case PAUSE -> pause();
            case STAMP -> stamp();
            case SQUEEZE -> squeeze();
            case RECOVER -> recover();
        }
    }

    /** 무리에서 가장 바깥 사람(서 있는 사람들 가운데에서 가장 먼 사람). 강제 표적이면 그 사람. */
    private Player outermost() {
        Player f = game.mobs.forcedFor(this);
        if (f != null) return f;
        List<Player> st = game.mobs.standing();
        if (st.isEmpty()) return null;
        double cx = 0, cz = 0;
        for (Player p : st) {
            Location l = p.getLocation();
            cx += l.getX();
            cz += l.getZ();
        }
        cx /= st.size();
        cz /= st.size();
        Player best = null;
        double bd = -1;
        for (Player p : st) {
            Location l = p.getLocation();
            double d = Math.hypot(l.getX() - cx, l.getZ() - cz) - dist(p) * 0.02;
            if (d > bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private double flat(Vector a, double x, double z) {
        return Math.hypot(a.getX() - x, a.getZ() - z);
    }

    private void upright(Vector at) {
        double dx = at.getX() - pos.getX(), dz = at.getZ() - pos.getZ();
        if (dx * dx + dz * dz > 1e-6) yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = 0;
    }

    /** 머리 높이로 날아와 그 사람 둘레 원 위 한 점으로. */
    private void approach() {
        state("");
        if (prey == null || !standing(prey) || age % 40 == 0) prey = outermost();
        if (prey == null) {
            Vector c = new Vector(kr.gave.world.Plaza.CX + 0.5, 0, kr.gave.world.Plaza.CZ + 0.5);
            c.setY(game.mobs.flow.floor(c.getX(), c.getZ()) + HOLD_Y);
            seek(c, speed());
            return;
        }
        Location l = prey.getLocation();
        double h = flat(pos, l.getX(), l.getZ());
        Vector want;
        if (h > R + 6) {
            want = l.toVector();
            want.setY(game.mobs.flow.floor(pos.getX(), pos.getZ()) + (h > 24 ? HOLD_Y + 2 : DRAW_Y + 0.4));
        } else {
            Vector out = pos.clone().subtract(l.toVector()).setY(0);
            if (out.lengthSquared() < 1e-6) out = new Vector(1, 0, 0);
            out.normalize().multiply(R);
            want = l.toVector().add(out);
            want.setY(game.mobs.flow.floor(want.getX(), want.getZ()) + DRAW_Y);
        }
        seek(want, speed());
        upright(l.toVector());
        if (h <= 12) mark(prey);
        if (h <= R + 1.2 && Math.abs(pos.getY() - (floor() + DRAW_Y)) < 1.2) startDraw(l);
    }

    private void startDraw(Location l) {
        center.setX(l.getX()).setZ(l.getZ());
        center.setY(game.mobs.flow.floor(l.getX(), l.getZ()));
        startA = Math.atan2(pos.getZ() - center.getZ(), pos.getX() - center.getX());
        drawDmg = 0;
        inside.clear();
        if (ring != null) ring.remove();
        ring = new G99Ring(game, sp, this);
        if (game.mobs.place(ring, center.clone().add(new Vector(0, 0.07, 0))) == null) ring = null;
        nextStroke = age;
        phase(Phase.DRAW);
    }

    /** 그 사람 둘레를 한 바퀴 돌며 먹 줄을 차례로 켬. */
    private void draw() {
        state("");
        int len = cd(DRAW);
        int t = inPhase();
        if (ring == null || ring.dead || prey == null || !standing(prey)) {
            breakRing();
            return;
        }
        double a = startA + Math.PI * 2 * Math.min(1.0, (t + 1) / (double) len);
        Vector want = new Vector(center.getX() + Math.cos(a) * R, center.getY() + DRAW_Y, center.getZ() + Math.sin(a) * R);
        vel.copy(want.subtract(pos));
        upright(center);
        // 지난 조각을 켬
        double from = startA, to = a;
        for (int i = 0; i < 12; i++) {
            double mid = (i + 0.5) * Math.PI * 2 / 12;
            double rel = ((mid - from) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
            if (rel <= to - from + 0.05) ring.on(i);
        }
        if (age >= nextStroke) {
            sound("stroke", Sound.BLOCK_WOOL_PLACE, 0.7f, 20);
            nextStroke = age + 8;
        }
        if (t + 1 >= len) close();
    }

    private void close() {
        ring.allOn();
        sound("close", Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 0.8f, 24);
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (flat(center, l.getX(), l.getZ()) < R) {
                inside.add(p.getUniqueId());
                game.hud.alert(p, "notice_g99_ring", 3, 60);
            }
        }
        closedAt = age;
        nextStamp = age + cd(STAMP_CD);
        phase(Phase.RISE);
    }

    /** 그리는 중 맞아 튕김: 줄이 끊기고 붓이 물러남. */
    private void breakRing() {
        if (ring != null) {
            ring.remove();
            ring = null;
        }
        inside.clear();
        game.mobs.unforce(this);
        mark(null);
        prey = null;
        state("");
        phase(Phase.RECOVER);
    }

    private Vector top() {
        return new Vector(center.getX() + Math.sin(age * 0.07) * 0.15, center.getY() + HOLD_Y, center.getZ() + Math.cos(age * 0.07) * 0.15);
    }

    private void rise() {
        state("");
        Vector want = top();
        vel.copy(want.subtract(pos).multiply(0.35));
        contain();
        if (inPhase() >= 10) phase(Phase.HOLD);
    }

    private void holdTick() {
        state("");
        vel.copy(top().subtract(pos).multiply(0.4));
        contain();
        if (age - closedAt >= DRY) {
            startSqueeze();
            return;
        }
        if (age >= nextStamp) {
            state("aim");
            hold();
            Location c = new Location(game.world, center.getX(), center.getY() + 0.02, center.getZ());
            game.shotFx.spawn(c, "fx_flat", ShotFx.DOWN_RING, 0, ShotFx.scale((float) (R / 0.4)), Display.Billboard.FIXED, false, cd(PAUSE) + 4, ShotFx.KEY);
            phase(Phase.PAUSE);
        }
    }

    /** 내려찍기 직전 0.5초 멈춤(갇힌 사람이 머리 위 붓을 쏠 틈). */
    private void pause() {
        state("aim");
        hold();
        contain();
        if (inPhase() >= cd(PAUSE)) phase(Phase.STAMP);
    }

    /** 내려찍기: 4틱 내려가 점을 찍고(안의 사람 모두 14) 6틱 올라감. */
    private void stamp() {
        int t = inPhase();
        contain();
        if (t < 4) {
            Vector want = new Vector(center.getX(), center.getY() + STAMP_Y, center.getZ());
            vel.copy(want.subtract(pos).multiply(0.55));
        } else vel.copy(top().subtract(pos).multiply(0.3));
        if (t == 3) {
            sound("stamp", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 24);
            for (Player p : game.mobs.standing()) {
                Location l = p.getLocation();
                if (flat(center, l.getX(), l.getZ()) < R + 0.2) hit(p, STAMP_DMG, "cause_g99");
            }
            if (game.shotFx.room(ShotFx.EXTRA))
                game.shotFx.spawn(new Location(game.world, center.getX(), center.getY() + 0.06, center.getZ()), "fx_flat", ShotFx.RING, 0,
                    ShotFx.scale(2.4f), Display.Billboard.FIXED, false, 10, ShotFx.EXTRA);
        }
        if (t >= 10) {
            state("");
            nextStamp = age + cd(STAMP_CD);
            phase(age - closedAt >= DRY ? Phase.HOLD : Phase.HOLD);
        }
    }

    private void startSqueeze() {
        state("aim");
        if (ring != null) {
            ring.state("dry");
            ring.body.scale(0.25f, SQUEEZE);
        }
        phase(Phase.SQUEEZE);
    }

    /** 15초 뒤 먹이 마르며 한 번 조임: 안에 남은 사람 20. */
    private void squeeze() {
        vel.copy(top().subtract(pos).multiply(0.4));
        if (inPhase() < SQUEEZE) {
            contain();
            return;
        }
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (flat(center, l.getX(), l.getZ()) < R) hit(p, SQUEEZE_DMG, "cause_g99_dry");
        }
        sound("close", Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 0.6f, 24);
        breakRing();
    }

    private void recover() {
        state("");
        Vector away = pos.clone().add(new Vector(Math.cos(id) * 4, 0, Math.sin(id) * 4));
        away.setY(floor() + HOLD_Y + 1);
        seek(away, speed());
        if (inPhase() >= cd(RECOVER)) phase(Phase.APPROACH);
    }

    /**
     * 닫힌 원: 안의 사람은 줄에서 되밀림(그 자리 조각이 켜져 있으면), 빗나감 없음, 둘레 게이브는 갇힌 사람에게. 밖의 사람이 줄을 밟으면 그 조각이
     * 지워진다.
     */
    private void contain() {
        if (ring == null || ring.dead) return;
        Player forced = null;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ(), d = Math.hypot(dx, dz);
            if (Math.abs(l.getY() - center.getY()) > 3) continue;
            int seg = (int) Math.floor(((Math.atan2(dz, dx) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2)) / (Math.PI * 2 / 12)) % 12;
            UUID id = p.getUniqueId();
            if (inside.contains(id)) {
                if (d > R + 0.9) {
                    inside.remove(id);
                    continue;
                }
                game.players.noMiss(p, 5);
                if (forced == null) forced = p;
                if (d > R - 0.55 && ring.isOn(seg) && d > 1e-6) {
                    double k = Math.min(0.6, 0.15 + (d - (R - 0.55)) * 0.7);
                    game.players.push(p, new Vector(-dx / d * k, 0, -dz / d * k));
                }
            } else if (Math.abs(d - R) < 0.45) {
                if (ring.isOn(seg)) ring.off(seg);
            } else if (d < R - 0.6) {
                inside.add(id);
            }
        }
        if (forced != null && age % 20 == 0) game.mobs.force(this, forced, FORCE_R, 30);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        for (double[] s : SPH) sph.add(new double[] {pos.getX(), pos.getY() + s[0], pos.getZ(), s[1]});
        sphAge = age;
        return sph;
    }

    @Override
    public boolean damage(Damage d) {
        boolean r = super.damage(d);
        if (r && !dead && phase == Phase.DRAW) {
            drawDmg += d.amount();
            if (drawDmg >= maxHp * BOUNCE_FRAC) {
                // 붓이 튕김: 맞은 쪽 반대로 밀려 줄이 끊김
                Vector away = d.dir() != null ? d.dir().clone().setY(0) : new Vector();
                breakRing();
                if (away.lengthSquared() > 1e-6) vel.copy(away.normalize().multiply(0.5).setY(0.2));
            }
        }
        return r;
    }

    @Override
    public void die(Damage d) {
        if (!dead) {
            if (ring != null) {
                ring.remove();
                ring = null;
            }
            game.mobs.unforce(this);
        }
        super.die(d);
    }

    @Override
    public void remove() {
        if (ring != null) {
            ring.remove();
            ring = null;
        }
        game.mobs.unforce(this);
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " in=" + inside.size() + (ring != null ? " ring=" + ring.lit() : "");
    }
}
