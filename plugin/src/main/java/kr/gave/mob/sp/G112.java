package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G112 허물 벗는 게이브(3막, 돌격, 튼튼이). 고리에서 1 블록 얼굴이 막대기 넷으로 어기적어기적 기어 나온다(0.1). 가운데에서 45 블록, 25 블록 선을
 * 넘을 때마다 멈춰 등이 갈라지고 2초에 걸쳐 허물을 벗으며 커진다(벗는 동안 못 움직이고 피해 x2, 김이 오름). 둘째 꼴은 3 블록(내려치기 16),
 * 셋째 꼴은 6 블록 거인(0.2 로 성큼성큼, 팔 휩쓸기 30 과 3 블록 밀침, 발 구르기 18). 체력은 꼴마다 몫이 늘어난다(첫 꼴은 5분의 1).
 * 벗은 허물은 온 길에 남아 15초 뒤 작은 첫 꼴 하나로 일어난다(보상 없음, 다시 벗지 않음). 허물을 쏴 터뜨려 두면 일어나지 않는다.
 *
 * <p>팩 계약(tools/gabe/sp/g112.py): 모델 gabe/g112(첫 꼴, ""/molt), gabe/g112_two(""/slam/molt, shrink 0.5), gabe/g112_three(""/sweep,
 * shrink 0.25), 허물 gabe/g112_skin. 소리 molt, slam, sweep, pop. 글귀 cause_g112, cause_g112_bite, cause_g112_slam, cause_g112_stomp.
 *
 * <p>도감과 다른 점: 꼴마다 한 모델 안 상태 대신 모델 셋을 같은 디스플레이에서 갈아 끼움(Body.model, 꼴마다 줄임이 다름). 팔은 부품 대신 셋째 꼴
 * 모델의 휩쓸기 상태(손으로 짠 자세). 허물은 연출 디스플레이(ShotFx 예산)에 허물 모델 + 쏠 수 있는 것(Mobs.Shootable).
 */
public final class G112 extends Gabe {

    static final String[] MODEL = {"gabe/g112", "gabe/g112_two", "gabe/g112_three"};
    static final double[] HOVER = {0.45, 1.5, 3.0}, RADIUS = {0.5, 1.0, 1.8}, SPEED = {0.1, 0.14, 0.2};
    static final double[] HP_FRAC = {100 / 700.0, 260 / 700.0, 520 / 700.0};
    static final float[][] CULL = {{2.2f, 1.6f}, {3.5f, 3.6f}, {6.5f, 7f}};
    static final double[] MOLT_AT = {45, 25};
    static final double[] SKIN = {1.1, 2.6};
    static final int MOLT_LEN = 40, SKIN_LIFE = 300;
    static final double BITE = 1, SLAM_DMG = 3, SLAM_R = 2.2, SWEEP_DMG = 5, SWEEP_R = 4.5, SWEEP_PUSH = 0.9, STOMP_DMG = 4, STOMP_R = 1.6;
    static final int BITE_CD = 30, SLAM_TELL = 10, SLAM_CD = 40, SWEEP_LEN = 12, SWEEP_HIT = 6, SWEEP_CD = 50, STEP = 12;

    enum Phase { GO, MOLT, SLAM, SWEEP }

    private Phase phase = Phase.GO;
    private int phaseAt, stage, biteAt, slamAt, sweepAt, nextStep;
    private double base = -1;
    /** 허물에서 일어난 개체: 다시 벗지 않음. */
    boolean noMolt;
    private final List<Skin> skins = new ArrayList<>();
    private final Map<UUID, Long> stompAt = new HashMap<>();

    public G112(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS[0];
        hover = HOVER[0];
        baseSpeed = SPEED[0];
        big = true;
        ragKick = 0.8;
    }

    @Override
    protected void build() {
        body = Body.of(this, MODEL[0], CULL[0][0], CULL[0][1]);
        body.viewRange(2f);
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
        if (base < 0) {
            // 감독이 곱한 체력을 바탕으로 첫 꼴 몫만 남김(허물에서 일어난 것은 그대로)
            base = maxHp;
            if (!noMolt) {
                maxHp = base * HP_FRAC[0];
                hp = Math.min(hp, maxHp);
            }
        }
        skinsTick();
        Player p = nearest(400);
        mark(p != null && dist(p) < 30 ? p : null);
        switch (phase) {
            case GO -> go(p);
            case MOLT -> molt();
            case SLAM -> slam();
            case SWEEP -> sweep();
        }
    }

    private double hdist(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    private void go(Player p) {
        state("");
        follow(speed());
        body.look.freeze = false;
        if (p != null) G66Giant.faceFlat(this, chest(p));
        // 선을 넘으면 허물 벗기
        if (!noMolt && stage < 2 && Plaza.radius(pos.getX(), pos.getZ()) <= MOLT_AT[stage]) {
            hold();
            state("molt");
            sound("molt", Sound.ENTITY_SLIME_SQUISH, 0.5f, 48);
            phase(Phase.MOLT);
            return;
        }
        if (p == null) return;
        double d = hdist(p);
        switch (stage) {
            case 0 -> {
                if (age >= biteAt && touches(p, 0.15)) {
                    hit(p, BITE, "cause_g112_bite");
                    biteAt = age + cd(BITE_CD);
                    body.squash(1.3f, 0.7f, 1.3f, 2);
                }
            }
            case 1 -> {
                if (d <= SLAM_R + 1.0 && age >= slamAt) {
                    hold();
                    state("slam");
                    phase(Phase.SLAM);
                }
            }
            default -> {
                steps();
                if (d <= SWEEP_R && age >= sweepAt && G66Giant.fwd(this).dot(toward(p)) > 0) {
                    hold();
                    G66Giant.gesture(this, "sweep", SWEEP_LEN, 4);
                    sound("sweep", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 24);
                    phase(Phase.SWEEP);
                }
            }
        }
    }

    /** 셋째 꼴 걸음: 발마다 땅울림, 그 발에 밟힌 사람 18(사람마다 1초에 한 번). */
    private void steps() {
        if (age < nextStep) return;
        nextStep = age + STEP;
        int side = (age / STEP) % 2 == 0 ? 1 : -1;
        Vector f = G66Giant.at(this, side * 0.75, -HOVER[2], 0.5);
        f.setY(floor());
        G66Giant.stomp(this, f, 48, 5, 0.75f);
        long now = now();
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (Math.hypot(l.getX() - f.getX(), l.getZ() - f.getZ()) > STOMP_R || Math.abs(l.getY() - f.getY()) > 2) continue;
            Long at = stompAt.get(q.getUniqueId());
            if (at != null && now - at < 20) continue;
            stompAt.put(q.getUniqueId(), now);
            hit(q, STOMP_DMG, "cause_g112_stomp");
        }
    }

    /** 허물 벗기: 2초 멈춤(피해 x2, 김), 끝에 허물을 남기고 다음 꼴로. */
    private void molt() {
        hold();
        int t = inPhase();
        if (t % 8 == 0) {
            body.squash(t % 16 == 0 ? 1.15f : 0.92f, t % 16 == 0 ? 0.9f : 1.12f, 1.05f, 6);
            if (game.shotFx.room(ShotFx.EXTRA)) {
                Location l = new Location(game.world, pos.getX(), pos.getY() + HOVER[stage] * 0.8 + 0.5, pos.getZ());
                ItemDisplay d = game.shotFx.spawn(l, "fx_quad", ShotFx.SMOKE, 1, ShotFx.scale(1.2f + stage), Display.Billboard.CENTER, false, 30,
                    ShotFx.EXTRA);
                game.shotFx.grow(d, ShotFx.scale(3f + stage * 1.5f), 26);
                game.shotFx.drift(d, l.clone().add(0, 2, 0), 28);
            }
        }
        if (t >= MOLT_LEN) {
            leaveSkin();
            stage++;
            double oldMax = maxHp;
            maxHp = base * HP_FRAC[stage];
            hp = Math.min(maxHp, hp + (maxHp - oldMax));
            hover = HOVER[stage];
            radius = RADIUS[stage];
            baseSpeed = SPEED[stage];
            body.squash(1f, 1f, 1f, 0);
            body.model(MODEL[stage]);
            body.size(CULL[stage][0], CULL[stage][1]);
            body.viewRange(stage == 2 ? 2.6f : 2.2f);
            state("");
            // 새 꼴이 젖은 채 부풀어 오름
            body.scale(0.55f, 0);
            sound("molt", Sound.ENTITY_SLIME_SQUISH, 0.4f, 48);
            G66Giant.ring(this, pos.clone().setY(floor()), 1.5 + stage * 1.5, 12);
            phase(Phase.GO);
            pendingGrow = age + 2;
        }
    }

    private int pendingGrow = -1;

    @Override
    protected void spawned() {
        nextStep = age + STEP;
    }

    private void slam() {
        hold();
        int t = inPhase();
        if (t == cd(SLAM_TELL)) {
            state("");
            body.squash(1.2f, 0.8f, 1.2f, 2);
            sound("slam", Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, 24);
            Vector c = pos.clone().add(G66Giant.fwd(this).multiply(1.2));
            c.setY(floor());
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                if (Math.hypot(l.getX() - c.getX(), l.getZ() - c.getZ()) <= SLAM_R && Math.abs(l.getY() - c.getY()) < 2.5) hit(q, SLAM_DMG, "cause_g112_slam");
            }
            G66Giant.ring(this, c, SLAM_R, 8);
        }
        if (t >= cd(SLAM_TELL) + 6) {
            body.squash(1f, 1f, 1f, 3);
            slamAt = age + cd(SLAM_CD);
            phase(Phase.GO);
        }
    }

    /** 팔 휩쓸기: 앞 반원 4.5 블록 30 과 밀침. */
    private void sweep() {
        hold();
        int t = inPhase();
        if (t == SWEEP_HIT) {
            Vector f = G66Giant.fwd(this);
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                Vector to = new Vector(l.getX() - pos.getX(), 0, l.getZ() - pos.getZ());
                double h = to.length();
                if (h > SWEEP_R || h < 1e-6 || to.clone().multiply(1 / h).dot(f) < -0.05 || Math.abs(l.getY() - floor()) > 3) continue;
                hit(q, SWEEP_DMG, "cause_g112");
                knock(q, to, SWEEP_PUSH, 0.35);
            }
            Vector c = pos.clone().add(f.multiply(2.5));
            c.setY(floor());
            G66Giant.ring(this, c, 2.5, 10);
        }
        if (t >= SWEEP_LEN) {
            state("");
            sweepAt = age + cd(SWEEP_CD);
            phase(Phase.GO);
        }
    }

    // ------------------------------------------------------------------ 허물

    /** 벗은 허물 하나: 바닥에 눕는 연출 디스플레이 + 쏠 수 있는 것. 15초 뒤 첫 꼴 하나로 일어남. */
    private final class Skin implements Mobs.Shootable {
        final Vector at;
        final double r;
        final long wake;
        ItemDisplay d;
        boolean gone;

        Skin(Vector at, double size) {
            this.at = at;
            this.r = size * 0.55;
            this.wake = now() + SKIN_LIFE;
            d = game.shotFx.spawn(new Location(game.world, at.getX(), at.getY() + 0.03, at.getZ()), "fx_quad", 0, 0,
                new Matrix4f().rotateY((float) Math.toRadians(-yaw)).scale((float) size, 1f, (float) size), Display.Billboard.FIXED, false, SKIN_LIFE + 20,
                ShotFx.NORMAL);
            if (d != null) {
                d.setItemStack(Body.item("gabe/g112_skin", "", new LookState(), game.worldTime()));
                d.setBrightness(Body.LIGHT);
            }
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            Vector c = center();
            Vector oc = o.clone().subtract(c);
            double b = oc.dot(dir), cc = oc.lengthSquared() - (r + 0.2) * (r + 0.2);
            double disc = b * b - cc;
            if (disc < 0) return Double.POSITIVE_INFINITY;
            double t = -b - Math.sqrt(disc);
            return t >= 0 && t <= max ? t : Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean shot(Damage dmg) {
            if (gone) return false;
            pop(true);
            return false;
        }

        void pop(boolean sound) {
            gone = true;
            if (d != null && d.isValid()) d.remove();
            d = null;
            game.mobs.removeShootable(this);
            if (sound && !game.director.silent())
                kr.gave.Props.soundAround(new Location(game.world, at.getX(), at.getY(), at.getZ()), "gabe.g112.pop", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f,
                    1f, 1.2f, 16, -1);
        }

        @Override
        public Vector center() {
            return at.clone().add(new Vector(0, 0.2, 0));
        }

        @Override
        public boolean gone() {
            return gone;
        }
    }

    private void leaveSkin() {
        Vector at = pos.clone().setY(floor());
        Skin s = new Skin(at, SKIN[Math.min(stage, SKIN.length - 1)]);
        skins.add(s);
        game.mobs.addShootable(s);
    }

    private void skinsTick() {
        if (pendingGrow >= 0 && age >= pendingGrow) {
            pendingGrow = -1;
            body.scale(1f, 14);
        }
        if (skins.isEmpty()) return;
        boolean wave = game.state == Game.State.WAVE;
        long now = now();
        for (Iterator<Skin> it = skins.iterator(); it.hasNext();) {
            Skin s = it.next();
            if (s.gone) {
                it.remove();
                continue;
            }
            if (!wave) {
                s.pop(false);
                it.remove();
                continue;
            }
            if (now >= s.wake) {
                Vector at = s.at.clone().setY(s.at.getY() + HOVER[0]);
                s.pop(false);
                it.remove();
                Gabe c = child(sp, at);
                if (c instanceof G112 g) g.noMolt = true;
            }
        }
    }

    private void dropSkins() {
        for (Skin s : skins) s.pop(false);
        skins.clear();
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (stage == 0) return super.spheres();
        if (stage == 1) {
            Vector h = G66Giant.at(this, 0, 1.15, 0.7);
            return List.of(new double[] {pos.getX(), pos.getY() + 0.1, pos.getZ(), 1.0}, new double[] {h.getX(), h.getY(), h.getZ(), 0.7});
        }
        Vector h = G66Giant.at(this, 0, 2.6, 0.8);
        Vector l = G66Giant.at(this, 0, -2.2, 0);
        return List.of(new double[] {pos.getX(), pos.getY() + 0.3, pos.getZ(), 1.75}, new double[] {h.getX(), h.getY(), h.getZ(), 1.2},
            new double[] {l.getX(), l.getY(), l.getZ(), 0.9});
    }

    @Override
    public Vector head() {
        return stage == 0 ? pos : G66Giant.at(this, 0, stage == 1 ? 1.15 : 2.6, stage == 1 ? 0.7 : 0.8);
    }

    @Override
    public boolean damage(Damage d) {
        if (phase == Phase.MOLT) return super.damage(d.times(2.0));
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        dropSkins();
        super.die(d);
    }

    @Override
    public void remove() {
        dropSkins();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " stage=" + stage + (noMolt ? " skinborn" : "") + " skins=" + skins.size() + " r="
            + Math.round(Plaza.radius(pos.getX(), pos.getZ()));
    }
}
