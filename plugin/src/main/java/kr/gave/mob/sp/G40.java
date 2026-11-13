package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.mob.Swarm;

/**
 * G40 짐승 탄 게이브(떼, 돌격).
 *
 * <p>도감: 돼지, 양, 소 다섯 마리에 게이브가 하나씩 타고 원래보다 빠르게 떼 지어 달려온다(들이받기 3). 짐승에 탄 게이브는 한 방에 떨어진다.
 * 8블록 안에서 게이브들이 뛰어올라 1초 회오리치며 사람 키 인형으로 쌓이고(이때 몰려 있어 폭발 2배), 사람 걸음을 흉내 내 걸어온다(꿴 팔 3블록 휘두름 5).
 * 맞으면 와르르 흩어졌다가 2초 뒤 다시 쌓이는데, 쌓일 자리에 0.8초 전 바닥 맥동 고리가 뜨고 심장 얼굴이 먼저 내려앉는다(심장 3배).
 * 버려진 짐승은 3초 날뛰다 쓰러져 사라진다(무적, 탄이 지나감).
 *
 * <p>바꾼 것: 바닐라 짐승 바탕 몹 대신 떼 마리 디스플레이(짐승 몸은 바닐라 텍스처를 입힌 아이템 모델, 팩 g40.py). 인형은 얼굴 10개를 묶은 모델 하나,
 * 조립과 흩어짐만 낱개 얼굴 부품(타고 있던 수만큼). 체력: 감독이 넣은 maxHp 를 '게이브 하나 몫'으로 읽고 남은 게이브 수 x 몫이 무리 체력.
 * 사람 걸음 흉내는 표적이 실제로 움직인 빠르기를 따라 걷는 것으로 했다.
 *
 * <p>팩 계약(tools/gabe/sp/g40.py): 마리 {@code gave:gabe/g40} 상태 pig, sheep, cow, *_bare, 덩어리 lod. 인형 {@code gave:gabe/g40_doll}
 * 상태 walk, swing, heart. 낱개 얼굴 {@code gave:gabe/g40_rider}. 소리 bleat(32), hoof(32), ram(16), whirl(24), heart(16), scatter(24),
 * swing(16). 글귀 cause_g40, cause_g40_arm.
 */
public final class G40 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 대형(몸 기준 x 옆, z 앞)과 짐승. 팩 g40.py HOMES, KINDS 와 같게(덩어리 모델이 이 자리로 구워짐). */
    static final double[][] HOMES = {{0.0, 0.9}, {-1.15, 0.2}, {1.15, 0.1}, {-0.55, -1.0}, {0.65, -1.1}};
    static final String[] KINDS = {"pig", "sheep", "cow", "pig", "sheep"};
    static final double RUN_SPEED = 0.25, BARE_SPEED = 0.2, ASSEMBLE_R = 8, BEAST_R = 0.55;
    static final float TURN_RATE = 9f;
    /** 짐승 들이받기 피해, 사람마다 다시 맞는 틱. 팔 휘두름 거리, 피해, 준비 틱, 한 번 틱. */
    static final double RAM_DMG = 4, ARM_R = 3, ARM_DMG = 6, SWING_START = 2.6;
    static final int RAM_CD = 16, WIND = 10, SWING_T = 22;
    /** 조립 회오리 틱, 흩어진 틱(2초), 심장이 먼저 내려앉는 틱(0.8초 전), 다시 흩어질 수 있을 때까지. 버려진 짐승 날뛰는 틱. */
    static final int WHIRL = 20, SCATTER = 40, HEART_AT = SCATTER - 16, SCATTER_CD = 30, BARE_LIFE = 60;
    /** 인형 걸음 빠르기 한계, 인형 피해 배율, 심장 배율, 조립 중 폭발 배율. */
    static final double DOLL_MIN = 0.09, DOLL_MAX = 0.24, DOLL_MUL = 0.5, HEART_MUL = 3, WHIRL_BLAST = 2;
    /** 몸(닻) 높이: 짐승 모델 가운데 = 바닥 위 0.5. 인형 모델 가운데 = 바닥 위 0.95. 심장 = 인형 가운데 아래 0.74. */
    static final double BASE_H = 0.5, DOLL_C = 0.95, HEART_DOWN = 0.74;

    enum Phase { RUN, WHIRL, DOLL, SWING, SCATTER }

    private Swarm swarm;
    private Phase phase = Phase.RUN;
    private int phaseAt, nextBleat, nextHoof, nextHeart, scatterReady;
    private boolean init;
    private double per;
    private float heading, dollScale = 1f;
    private final boolean[] ridden = new boolean[HOMES.length];
    private final int[] bareUntil = new int[HOMES.length];
    private final Vector[] bareAt = new Vector[HOMES.length];
    private final Vector[] bareDir = new Vector[HOMES.length];
    private final Map<UUID, Long> rammed = new HashMap<>();
    private Body.Part doll;
    private final Body.Part[] faces = new Body.Part[HOMES.length];
    private final Vector[] faceOff = new Vector[HOMES.length];
    private Player prey;
    private Vector preyLast;
    private double preyV = 0.15;
    private boolean swung;

    public G40(Game game, Species sp) {
        super(game, sp);
        radius = 1.2;
        noPush = true;
        noMutate = true;
        baseSpeed = RUN_SPEED;
        hover = BASE_H;
    }

    @Override
    protected void build() {
        swarm = game.mobs.swarm(this, "gabe/g40", HOMES.length);
        for (Swarm.Member m : swarm.members) {
            m.home.setX(HOMES[m.index][0]).setY(0).setZ(HOMES[m.index][1]);
            m.state(KINDS[m.index]);
            ridden[m.index] = true;
        }
        heading = yaw;
        swarm.formation(heading, 0);
        doll = body.attach("gabe/g40_doll");
        if (doll != null) doll.model(Body.EMPTY);
        for (int i = 0; i < faces.length; i++) {
            faces[i] = body.attach("gabe/g40_rider");
            faceOff[i] = new Vector();
            if (faces[i] != null) {
                faces[i].follow = false;
                faces[i].model(Body.EMPTY);
            }
        }
    }

    private void init() {
        init = true;
        per = Math.max(1, maxHp);
        maxHp = hp = per * HOMES.length;
        nextBleat = age + game.random.nextInt(30);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private int riders() {
        int n = 0;
        for (boolean r : ridden) if (r) n++; // 짐승에 탔거나(질주) 인형 몫으로 남은 게이브
        return n;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        if (prey != null && !standing(prey)) prey = null;
        hover = BASE_H;
        switch (phase) {
            case RUN -> run();
            case WHIRL -> whirl();
            case DOLL -> walk();
            case SWING -> swing();
            case SCATTER -> scatter();
        }
        bare();
        ram();
        if (age >= nextBleat) {
            nextBleat = age + 45 + game.random.nextInt(40);
            if (phase == Phase.RUN || anyBare()) sound("bleat", Sound.ENTITY_PIG_AMBIENT, 1.4f, 32);
        }
    }

    /** 떼 지어 질주: 흐름장 쪽으로 한 곡선으로 휘며. 8블록 안 보이는 사람이 있으면 조립. */
    private void run() {
        follow(speed());
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-6) {
            float want = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
            heading += Math.max(-TURN_RATE, Math.min(TURN_RATE, wrap(want - heading)));
        }
        double r = Math.toRadians(heading);
        vel.setX(-Math.sin(r) * speed()).setZ(Math.cos(r) * speed());
        yaw = heading;
        swarm.heading(heading);
        if (age >= nextHoof) {
            nextHoof = age + 10;
            sound("hoof", Sound.ENTITY_HORSE_GALLOP, 1.2f, 32);
        }
        Player p = nearest(ASSEMBLE_R + 2);
        if (p != null && flat(p) <= ASSEMBLE_R && sees(p) && riders() > 0) assemble(p);
    }

    /** 게이브들이 짐승에서 뛰어올라 1초 회오리치며 사람 꼴로 쌓임. */
    private void assemble(Player p) {
        prey = p;
        mark(p);
        hold();
        int n = riders();
        dollScale = (float) (0.78 + 0.055 * n);
        int k = 0;
        for (int i = 0; i < ridden.length; i++) {
            if (!ridden[i] || swarm.members.get(i).dead) continue;
            Vector from = swarm.members.get(i).off().add(new Vector(0, 0.25, 0));
            abandon(i);
            ridden[i] = true; // 게이브는 아직 살아 있음(인형 몫)
            showFace(i, from, 0);
            k++;
        }
        sound("whirl", Sound.ENTITY_BREEZE_WIND_BURST, 1f, 24);
        swarm.lodDist = 1e9; // 인형이 된 뒤엔 멀리서도 낱개(덩어리 모델은 짐승 떼 그림)
        phase(Phase.WHIRL);
    }

    private void whirl() {
        hold();
        if (prey != null) face(chest(prey));
        int t = inPhase();
        if (t % 3 == 0) {
            double f = Math.min(1.0, (t + 3) / (double) WHIRL);
            int i = 0;
            for (int j = 0; j < faces.length; j++) {
                if (!ridden[j]) continue;
                double a = j * (Math.PI * 2 / HOMES.length) + t * 0.45;
                double rr = 1.3 * (1 - f) + 0.25;
                showFace(j, new Vector(Math.cos(a) * rr, -BASE_H + 0.4 + f * 1.1 * dollScale + (j % 2) * 0.2, Math.sin(a) * rr), 3);
                i++;
            }
        }
        if (t >= WHIRL) {
            hideFaces();
            dollShow("walk");
            nextHeart = age + 10;
            phase(Phase.DOLL);
        }
    }

    /** 사람 꼴로 걸어옴: 표적이 실제로 움직인 빠르기를 따라 걷는다. 가까우면 휘두름. */
    private void walk() {
        // 쫓던 사람이 쓰러지거나 나간 뒤 남은 사람이 64블록 밖이면 인형이 그 자리에 영영 서 있었다(이 상태는 시간 제한이 없음).
        // 반경 제한 없이 다시 찾는다(2026-11-11).
        Player p = prey != null ? prey : nearest(400);
        if (p == null) {
            hold();
            return;
        }
        if (target != p) mark(p);
        prey = p;
        Vector now = p.getLocation().toVector();
        if (preyLast != null) {
            double mv = Math.hypot(now.getX() - preyLast.getX(), now.getZ() - preyLast.getZ());
            if (mv < 1.5) preyV = preyV * 0.85 + mv * 0.15;
        }
        preyLast = now;
        double v = Math.max(DOLL_MIN, Math.min(DOLL_MAX, preyV)) * game.director.speedMul();
        follow(v);
        face(chest(p));
        pitch = 0;
        heartbeat();
        if (flat(p) <= SWING_START) {
            hold();
            swung = false;
            dollShow("swing");
            phase(Phase.SWING);
        }
    }

    private void swing() {
        hold();
        if (prey != null) turnTo(chest(prey), 10f);
        pitch = 0;
        int t = inPhase();
        if (t == cd(WIND) && !swung) {
            swung = true;
            sound("swing", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 16);
            double ry = Math.toRadians(yaw);
            Vector fwd = new Vector(-Math.sin(ry), 0, Math.cos(ry));
            for (Player q : game.mobs.standing()) {
                Vector d = q.getLocation().toVector().subtract(pos).setY(0);
                double l = d.length();
                if (l > ARM_R || Math.abs(q.getLocation().getY() - floor()) > 2.5) continue;
                if (l > 0.6 && d.multiply(1 / l).dot(fwd) < 0.2) continue;
                hit(q, ARM_DMG, "cause_g40_arm");
                knock(q, fwd, 0.7, 0.3);
            }
        }
        if (t >= SWING_T) {
            dollShow("walk");
            phase(Phase.DOLL);
        }
    }

    /** 와르르 흩어짐 → 0.8초 전 바닥 맥동 고리와 심장 먼저 → 다시 쌓임. */
    private void scatter() {
        hold();
        int t = inPhase();
        if (t == HEART_AT) {
            dollShow("heart");
            sound("heart", Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 16);
            pulse();
        }
        if (t > HEART_AT && (t - HEART_AT) % 5 == 0) {
            double f = (t - HEART_AT) / (double) (SCATTER - HEART_AT);
            for (int j = 0; j < faces.length; j++) {
                if (!ridden[j] || faceOff[j] == null) continue;
                Vector o = faceOff[j];
                showFace(j, new Vector(o.getX() * (1 - f) * 0.6, -BASE_H + 0.3 + f * 1.2 * dollScale, o.getZ() * (1 - f) * 0.6), 5);
            }
        }
        if (t == HEART_AT + 8) pulse();
        if (t >= SCATTER) {
            hideFaces();
            dollShow("walk");
            scatterReady = age + SCATTER_CD;
            sound("whirl", Sound.ENTITY_BREEZE_WIND_BURST, 1.2f, 24);
            phase(Phase.DOLL);
        }
    }

    private void breakApart() {
        dollHide();
        sound("scatter", Sound.BLOCK_BONE_BLOCK_BREAK, 0.8f, 24);
        for (int j = 0; j < faces.length; j++) {
            if (!ridden[j]) continue;
            double a = game.random.nextDouble() * Math.PI * 2, r = 1.2 + game.random.nextDouble() * 1.6;
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            double fl = game.mobs.flow.floor(pos.getX() + x, pos.getZ() + z) - pos.getY() + 0.15;
            showFace(j, new Vector(0, 0.5, 0), 0);
            showFace(j, new Vector(x, fl, z), 7);
        }
        phase(Phase.SCATTER);
    }

    /** 쌓일 자리의 바닥 맥동 고리. */
    private void pulse() {
        Location at = new Location(game.world, pos.getX(), floor() + 0.05, pos.getZ());
        var d = game.shotFx.spawn(at, "fx_flat", ShotFx.RING, 1, ShotFx.scale(0.6f), Display.Billboard.FIXED, false, 14, ShotFx.KEY);
        if (d != null) game.shotFx.grow(d, ShotFx.scale(2.2f), 10);
    }

    private void heartbeat() {
        if (age < nextHeart) return;
        nextHeart = age + 28;
        sound("heart", Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 16);
    }

    // ------------------------------------------------------------------ 짐승

    /** 게이브가 떨어진 짐승: 3초 날뛰다 쓰러져 사라짐. */
    private void abandon(int i) {
        Swarm.Member m = swarm.members.get(i);
        if (m.dead) return;
        ridden[i] = false;
        m.state(KINDS[i] + "_bare");
        bareUntil[i] = age + BARE_LIFE;
        bareAt[i] = m.pos();
        double a = game.random.nextDouble() * Math.PI * 2;
        bareDir[i] = new Vector(Math.cos(a), 0, Math.sin(a));
    }

    private boolean anyBare() {
        for (int i = 0; i < bareUntil.length; i++) if (bareAt[i] != null && !swarm.members.get(i).dead) return true;
        return false;
    }

    private void bare() {
        for (int i = 0; i < bareUntil.length; i++) {
            Swarm.Member m = swarm.members.get(i);
            if (bareAt[i] == null || m.dead) continue;
            if (age >= bareUntil[i]) {
                swarm.kill(m);
                bareAt[i] = null;
                continue;
            }
            if ((age + i) % 5 != 0) continue;
            // 날뛰기: 가끔 방향을 확 꺾음
            if (game.random.nextInt(3) == 0) bareDir[i].rotateAroundY((game.random.nextDouble() - 0.5) * 2.4);
            Vector next = bareAt[i].clone().add(bareDir[i].clone().multiply(BARE_SPEED * 5));
            next.setY(game.mobs.flow.floor(next.getX(), next.getZ()) + BASE_H);
            bareAt[i] = next;
            m.moveTo(next.clone().subtract(pos), 5);
            m.face((float) Math.toDegrees(Math.atan2(-bareDir[i].getX(), bareDir[i].getZ())), 0, 0, 3);
        }
    }

    /** 짐승 몸(탄 것, 버려진 것)에 닿은 사람 들이받기 3. */
    private void ram() {
        if (phase != Phase.RUN && !anyBare()) return;
        long now = game.now();
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            Long last = rammed.get(p.getUniqueId());
            if (last != null && now - last < cd(RAM_CD)) continue;
            for (Swarm.Member m : swarm.members) {
                if (m.dead) continue;
                if (phase != Phase.RUN && bareAt[m.index] == null) continue;
                Vector mp = m.pos();
                if (Math.hypot(mp.getX() - l.getX(), mp.getZ() - l.getZ()) > 0.95 || Math.abs(mp.getY() - l.getY() - 0.5) > 1.3) continue;
                Vector dir = new Vector(l.getX() - mp.getX(), 0, l.getZ() - mp.getZ());
                if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
                hit(p, RAM_DMG, "cause_g40");
                knock(p, dir.normalize(), 0.8, 0.35);
                sound("ram", Sound.ENTITY_RAVAGER_ATTACK, 1.2f, 16);
                rammed.put(p.getUniqueId(), now);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ 부품

    private Matrix4f dollPose() {
        return new Matrix4f().translate(0f, (float) (DOLL_C * dollScale - BASE_H), 0f).scale(dollScale);
    }

    private void dollShow(String st) {
        if (doll == null) return;
        doll.model("gabe/g40_doll");
        doll.state(st);
        doll.pose(dollPose(), 2);
    }

    private void dollHide() {
        if (doll != null) doll.model(Body.EMPTY);
    }

    private void showFace(int i, Vector off, int ticks) {
        Body.Part f = faces[i];
        if (f == null) return;
        if (Body.EMPTY.equals(f.model)) f.model("gabe/g40_rider");
        faceOff[i] = off.clone();
        f.pose(new Matrix4f().translate((float) off.getX(), (float) off.getY(), (float) off.getZ())
                .rotateY((float) Math.toRadians(-yaw + i * 72)), ticks);
    }

    private void hideFaces() {
        for (Body.Part f : faces) if (f != null) f.model(Body.EMPTY);
    }

    // ------------------------------------------------------------------ 맞음

    private Vector heartPos() {
        return new Vector(pos.getX(), floor() + (DOLL_C - HEART_DOWN) * dollScale, pos.getZ());
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(5);
        switch (phase) {
            case RUN -> {
                for (Swarm.Member m : swarm.members) {
                    if (m.dead || !ridden[m.index]) continue;
                    Vector p = m.pos();
                    out.add(new double[] {p.getX(), p.getY(), p.getZ(), BEAST_R});
                }
            }
            case WHIRL -> out.add(new double[] {pos.getX(), floor() + 1.0, pos.getZ(), 1.1});
            case DOLL, SWING -> {
                double fl = floor();
                for (double h : new double[] {0.55, 1.05, 1.55})
                    out.add(new double[] {pos.getX(), fl + h * dollScale, pos.getZ(), 0.34 * dollScale});
            }
            case SCATTER -> {
                if (inPhase() >= HEART_AT) {
                    Vector h = heartPos();
                    out.add(new double[] {h.getX(), h.getY(), h.getZ(), 0.32});
                }
            }
        }
        return out;
    }

    @Override
    public Vector head() {
        if (phase == Phase.RUN) return pos;
        return new Vector(pos.getX(), floor() + 1.6 * dollScale, pos.getZ());
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        DamageType t = d.type();
        boolean blast = t == DamageType.EXPLOSION || t == DamageType.FIRE;
        Vector at = d.at() != null ? d.at() : pos;
        double before = hp;
        switch (phase) {
            case RUN -> {
                // 짐승에 탄 게이브는 한 방에 떨어진다(폭발은 3블록 안 모두). 버려진 짐승은 무적
                List<Swarm.Member> hitList = new ArrayList<>();
                if (blast) {
                    for (Swarm.Member m : swarm.within(at, 3)) if (ridden[m.index]) hitList.add(m);
                } else {
                    Swarm.Member best = null;
                    double bd = Double.MAX_VALUE;
                    for (Swarm.Member m : swarm.members) {
                        if (m.dead || !ridden[m.index]) continue;
                        double dd = m.pos().distanceSquared(at);
                        if (dd < bd) {
                            bd = dd;
                            best = m;
                        }
                    }
                    if (best != null) hitList.add(best);
                }
                if (hitList.isEmpty()) return false;
                for (Swarm.Member m : hitList) {
                    game.shotFx.pop(m.pos().toLocation(game.world).add(0, 0.4, 0));
                    abandon(m.index);
                    hp -= per;
                }
                swarm.flinch();
            }
            case WHIRL -> hp -= d.amount() * (blast ? WHIRL_BLAST : 1);
            case DOLL, SWING -> {
                hp -= d.amount() * DOLL_MUL;
                if (hp > 0 && age >= scatterReady) breakApart();
            }
            case SCATTER -> {
                if (inPhase() < HEART_AT) return false;
                hp -= d.amount() * HEART_MUL;
                body.flash(game.worldTime() + 3);
            }
        }
        game.mobs.onHurt(this, d, Math.max(0, before - Math.max(0, hp)));
        if (hp <= 0 || riders() == 0 && phase == Phase.RUN) {
            hp = 0;
            die(d);
        }
        return true;
    }

    // ------------------------------------------------------------------ 도우미

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " riders=" + riders() + " doll=" + String.format(java.util.Locale.ROOT, "%.2f", dollScale);
    }
}
