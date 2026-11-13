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
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G31 웅덩이 게이브(방해, 떼). 얼굴이 녹아 퍼진 납작한 분홍 반죽 장판(지름 1 .. 4 블록)이 바닥을 흐르듯 번져 온다. 황야에서는 땅 갈라진 틈에서
 * 스며 나온다(자리는 감독이 줌, 나올 때 작게 솟아 퍼짐).
 *
 * <p>도감: 위에 서면 녹음 게이지({@link G31Melt})가 선 시간과 걸친 넓이만큼 쌓여 느려지고, 가득 차면 쓰러진다. 절반을 넘은 사람은 분홍 발자국을
 * 남기고 동료가 밟으면 옮는다. 밟는 순간 눈 조각들이 발밑으로 몰리고 실눈을 뜬다(찍음). 2~3 블록 반죽 혀를 뻗었다 거둔다(혀 위도 반죽).
 * 쏘면 쪼개지고(체력이 절반이 될 때마다 둘로, 지름 0.64배) 지름 1 블록보다 작은 조각은 3초 만에 말라 죽는다. 불, 폭발은 두 배로 듣고 쪼개지지
 * 않는다. 중앙 원(마른 돌, Plaza.R_SAFE)은 못 넘어온다(혀도). 아무도 안 밟으면 가끔 이목구비가 모여 원래 얼굴로 웃는다.
 *
 * <p>도감과 다른 점: 깊이 시차 셰이더 대신 장판 그림의 검붉은 깊은 곳, HUD 숫자 흐려짐은 없음(게이지와 느려짐만), 상점 마른 모래는 없어
 * 불 계열만 듣는다. 혀는 뻗는 동안만 부품 하나가 더 붙는다(보통 디스플레이 둘).
 *
 * <p>팩 계약(tools/gabe/sp/g31.py): 닻 {@code gave:gabe/g31}(장판 지름 3 블록 + 지느러미 둘, Java 가 지름 / 3 배), 부품
 * {@code gave:gabe/g31_eyes}(상태 {@code ""}, {@code "face"}), {@code gave:gabe/g31_tongue}(+Z 1 블록), 발자국 {@code gave:gabe/g31_print}.
 * 소리 gurgle(24), squelch(16), split(16), dry(16). 원인 글귀 {@code cause_g31}.
 */
public final class G31 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.07;
    /** 장판 모델 지름(블록, 팩과 같음). 처음 지름 범위. */
    static final double MODEL_D = 3.0, D_MIN = 2.6, D_MAX = 3.6;
    /** 쪼개질 때 지름 배율, 쪼개지는 마지막 세대(이 세대 조각은 지름 1 아래라 말라 죽음), 마르는 지름과 틱. */
    static final double SHRINK = 0.64, DRY_D = 1.0;
    static final int MAX_GEN = 3, DRY_TICKS = 60;
    /** 온몸이 반죽 위일 때 틱마다 오르는 녹음(6초면 가득), 혀 위 비율, 발 높이 한도(뛰어도 닿음). */
    static final double MELT_RATE = 1.0 / 120, TONGUE_RATE = 0.8, REACH_UP = 1.3;
    /** 불, 폭발 배율. */
    static final double FIRE_MUL = 2.0;
    /** 혀: 닿는 거리(장판 가장자리에서), 뻗는 틱, 머무는 틱, 거두는 틱, 쉬는 틱, 반폭. */
    static final double TONGUE_MAX = 3.0, TONGUE_HALF = 0.42;
    static final int T_OUT = 12, T_HOLD = 30, T_BACK = 10, T_COOL = 90;
    /** 솟아 퍼지는 틱, 중앙 원 여유. */
    static final int RISE = 24;
    static final double SAFE_PAD = 0.25;
    /** 이목구비: 떠도는 간격, 모여 웃는 틱, 다음 웃음까지. */
    static final int DRIFT = 44, FACE_HOLD = 50, FACE_EVERY = 260;

    private Body.Part eyes, tongue;
    private int gen;
    private double baseDiam, diam, pieceMax;
    private boolean inited, dry;
    private int dryAt, riseUntil = -1;
    private int nextGurgle, nextDrift, nextFace, faceStep, faceAt, tongueAt = -1, tongueReady;
    private double tongueLen, tongueDx, tongueDz;
    private float eyeX, eyeZ, eyeRot;
    private boolean eyesOnFoot;
    private Player stander;
    private final List<double[]> hit = new ArrayList<>(7);

    public G31(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        hover = 0.04; // 장판이 바닥 바로 위
        baseSpeed = SPEED;
        noPush = true; // 사람, 다른 몹이 위로 지나감
        noBite = true; // 부딪힐 머리가 없음
        baseDiam = diam = D_MIN + game.random.nextDouble() * (D_MAX - D_MIN);
        for (int i = 0; i < 7; i++) hit.add(new double[4]);
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g31", 7.0f, 1.6f);
        body.scale((float) (diam / MODEL_D), 0);
        eyes = body.attach("gabe/g31_eyes");
        if (eyes != null) {
            eyes.follow = false;
            poseEyes(0);
        }
    }

    @Override
    protected void spawned() {
        nextGurgle = age + 20 + game.random.nextInt(60);
        nextFace = age + 120 + game.random.nextInt(FACE_EVERY);
        tongueReady = age + 60 + game.random.nextInt(60);
        yaw = game.random.nextFloat() * 360f;
        if (game.director.cageSpawning()) return;
        // 틈에서 스며 나옴: 작게 솟아 퍼진다
        body.scale((float) (diam / MODEL_D * 0.15), 0);
        riseUntil = age + RISE;
        sound("gurgle", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.6f, 24);
    }

    private double r() {
        return diam * 0.5;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!inited) {
            inited = true;
            if (pieceMax <= 0) pieceMax = Math.max(1, maxHp);
        }
        G31Melt melt = G31Melt.of(game);
        melt.tick(this);
        pitch = 0;
        if (riseUntil >= 0) {
            hold();
            if (age == riseUntil - RISE + 1) body.scale((float) (diam / MODEL_D), RISE - 2);
            if (age >= riseUntil) riseUntil = -1;
            return;
        }
        if (dry) {
            hold();
            if (age - dryAt >= DRY_TICKS) die(new Damage(Math.max(1, hp), DamageType.OTHER, null, pos.clone(), null, "dry", 0));
            return;
        }
        flow();
        contact(melt);
        tongue(melt);
        eyes();
        if (age >= nextGurgle) {
            sound("gurgle", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.6f, 24);
            nextGurgle = age + 70 + game.random.nextInt(60);
        }
    }

    /** 흐름장을 따라 번져 감(밀려왔다 잦아드는 흐름), 중앙 원은 못 넘음. 혀를 뻗은 동안은 멈춤. */
    private void flow() {
        if (tongueAt >= 0) hold();
        else {
            double surge = 0.55 + 0.75 * Math.max(0, Math.sin((age + id * 11) * 0.11));
            follow(speed() * surge);
            if (stander != null) vel.multiply(0.25);
        }
        keepOut();
        Player p = nearest(40);
        if (p != null) turnTo(chest(p), 2.5f);
        pitch = 0;
        if (every(10)) {
            float a = 0.05f * (float) Math.sin((age + id * 7) * 0.13);
            body.squash(1f + a, 1f, 1f - a, 10);
        }
    }

    /** 중앙 원(마른 돌) 안으로는 장판 가장자리도 못 들어간다: 안쪽 성분을 지워 테두리를 따라 미끄러지고, 안에 놓였으면 밖으로 밀린다. */
    private void keepOut() {
        double lim = Plaza.R_SAFE + r() + SAFE_PAD;
        double nx = pos.getX() + vel.getX() - Plaza.CX, nz = pos.getZ() + vel.getZ() - Plaza.CZ;
        if (nx * nx + nz * nz >= lim * lim) return;
        double ox = pos.getX() - Plaza.CX, oz = pos.getZ() - Plaza.CZ, od = Math.hypot(ox, oz);
        if (od < 1e-6) {
            ox = 1;
            oz = 0;
            od = 1;
        }
        ox /= od;
        oz /= od;
        double inward = -(vel.getX() * ox + vel.getZ() * oz);
        if (inward > 0) vel.setX(vel.getX() + ox * inward).setZ(vel.getZ() + oz * inward);
        if (od < lim - 0.05) vel.setX(vel.getX() + ox * 0.08).setZ(vel.getZ() + oz * 0.08);
    }

    /** 장판 위에 선 사람: 걸친 넓이만큼 녹음. 가장 깊이 밟은 사람을 찍고(실눈 뜸) 눈 조각이 그 발밑으로 몰린다. */
    private void contact(G31Melt melt) {
        Player best = null;
        double bestF = 0;
        double R = r();
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            Location l = p.getLocation();
            double dy = l.getY() - pos.getY();
            if (dy < -0.6 || dy > REACH_UP) continue;
            double d = Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
            double f = Math.max(0, Math.min(1, (R + 0.3 - d) / 0.6));
            if (f <= 0) continue;
            melt.add(p, MELT_RATE * f, this);
            if (f > bestF) {
                bestF = f;
                best = p;
            }
        }
        if (best != stander) {
            if (best != null && stander == null) sound("squelch", Sound.BLOCK_HONEY_BLOCK_STEP, 0.7f, 16);
            stander = best;
            mark(best);
            if (eyes != null) eyes.look.open = best != null;
            if (best != null) endFace();
        }
    }

    // ------------------------------------------------------------------ 혀

    /** 가장자리에서 3 블록 안에 선 사람 쪽으로 반죽 혀를 뻗었다 거둔다. 혀 위에 선 사람도 녹는다. 혀 끝도 중앙 원은 못 넘는다. */
    private void tongue(G31Melt melt) {
        if (tongueAt < 0) {
            if (age < tongueReady || stander != null || !every(5)) return;
            Player p = nearest(r() + TONGUE_MAX + 0.5);
            if (p == null) return;
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ(), d = Math.hypot(dx, dz);
            double gap = d - r();
            if (gap < 0.4 || gap > TONGUE_MAX || Math.abs(l.getY() - pos.getY()) > REACH_UP) return;
            tongueDx = dx / d;
            tongueDz = dz / d;
            tongueLen = clampOutside(Math.min(TONGUE_MAX, gap + 0.7));
            if (tongueLen < 0.6) {
                tongueReady = age + 20;
                return;
            }
            tongue = body.attach("gabe/g31_tongue");
            if (tongue == null) return;
            tongue.follow = false;
            tongue.pose(tongueMatrix(0.05), 0);
            tongueAt = age;
            sound("squelch", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.8f, 16);
            return;
        }
        int t = age - tongueAt;
        if (t == 1 && tongue != null) tongue.pose(tongueMatrix(tongueLen), T_OUT);
        if (t == T_OUT + T_HOLD && tongue != null) tongue.pose(tongueMatrix(0.05), T_BACK);
        double len = t <= T_OUT ? tongueLen * t / T_OUT : t <= T_OUT + T_HOLD ? tongueLen
                : tongueLen * Math.max(0, 1 - (t - T_OUT - T_HOLD) / (double) T_BACK);
        if (len > 0.3) {
            double x0 = pos.getX() + tongueDx * (r() - 0.3), z0 = pos.getZ() + tongueDz * (r() - 0.3);
            List<Player> ps = game.mobs.standing();
            for (int i = 0; i < ps.size(); i++) {
                Player p = ps.get(i);
                Location l = p.getLocation();
                double dy = l.getY() - pos.getY();
                if (dy < -0.6 || dy > REACH_UP) continue;
                double px = l.getX() - x0, pz = l.getZ() - z0;
                double along = px * tongueDx + pz * tongueDz;
                if (along < 0 || along > len + 0.3) continue;
                double side = Math.abs(px * tongueDz - pz * tongueDx);
                if (side > TONGUE_HALF + 0.3) continue;
                melt.add(p, MELT_RATE * TONGUE_RATE, this);
            }
        }
        if (t >= T_OUT + T_HOLD + T_BACK) endTongue();
    }

    /** 혀 끝이 중앙 원 안으로 들어가지 않는 가장 긴 길이. */
    private double clampOutside(double len) {
        double x0 = pos.getX() + tongueDx * (r() - 0.3) - Plaza.CX, z0 = pos.getZ() + tongueDz * (r() - 0.3) - Plaza.CZ;
        double lim = Plaza.R_SAFE + SAFE_PAD;
        for (double l = len; l > 0.5; l -= 0.25) {
            double x = x0 + tongueDx * l, z = z0 + tongueDz * l;
            if (x * x + z * z >= lim * lim) return l;
        }
        return 0;
    }

    private Matrix4f tongueMatrix(double len) {
        double s = Math.max(0.5, Math.min(1.2, diam / 2.6));
        return new Matrix4f().translate((float) (tongueDx * (r() - 0.3)), 0.004f, (float) (tongueDz * (r() - 0.3)))
                .rotateY((float) Math.atan2(tongueDx, tongueDz)).scale((float) s, 1f, (float) len);
    }

    private void endTongue() {
        if (tongue != null) tongue.remove();
        tongue = null;
        tongueAt = -1;
        tongueReady = age + cd(T_COOL) + game.random.nextInt(40);
    }

    // ------------------------------------------------------------------ 이목구비

    /** 눈 조각: 밟히면 발밑으로 몰리고, 아니면 장판 위를 천천히 떠돌다 가끔 모여 원래 얼굴로 웃는다. */
    private void eyes() {
        if (eyes == null) return;
        if (stander != null) {
            Location l = stander.getLocation();
            double ex = l.getX() - pos.getX(), ez = l.getZ() - pos.getZ();
            double lim = Math.max(0, r() - 0.7), d = Math.hypot(ex, ez);
            if (d > lim && d > 1e-6) {
                ex *= lim / d;
                ez *= lim / d;
            }
            if (!eyesOnFoot || Math.abs(ex - eyeX) + Math.abs(ez - eyeZ) > 0.12) {
                eyeX = (float) ex;
                eyeZ = (float) ez;
                eyesOnFoot = true;
                poseEyes(5);
            }
            return;
        }
        eyesOnFoot = false;
        if (faceStep > 0) {
            face();
            return;
        }
        if (age >= nextFace) {
            // 가운데로 모이며 오므라듦 → 얼굴로 바뀌어 펴짐
            faceStep = 1;
            faceAt = age;
            eyeX = eyeZ = 0;
            poseEyes(6, 0.3f);
            return;
        }
        if (age >= nextDrift) {
            nextDrift = age + DRIFT;
            double a = game.random.nextDouble() * Math.PI * 2, rr = Math.max(0, r() - 1.05) * Math.sqrt(game.random.nextDouble());
            eyeX = (float) (Math.cos(a) * rr);
            eyeZ = (float) (Math.sin(a) * rr);
            eyeRot += (float) ((game.random.nextDouble() - 0.5) * 1.6);
            poseEyes(DRIFT);
        }
    }

    private void face() {
        int t = age - faceAt;
        if (faceStep == 1 && t >= 6) {
            eyes.state("face");
            Player p = nearest(24);
            if (p != null) eyeRot = (float) Math.atan2(p.getLocation().getX() - pos.getX(), p.getLocation().getZ() - pos.getZ());
            poseEyes(6);
            faceStep = 2;
            faceAt = age;
        } else if (faceStep == 2 && t >= FACE_HOLD) {
            poseEyes(6, 0.3f);
            faceStep = 3;
            faceAt = age;
        } else if (faceStep == 3 && t >= 6) {
            eyes.state("");
            poseEyes(6);
            faceStep = 0;
            nextFace = age + FACE_EVERY + game.random.nextInt(FACE_EVERY);
            nextDrift = age + 10;
        }
    }

    private void endFace() {
        if (faceStep == 0 || eyes == null) return;
        eyes.state("");
        faceStep = 0;
        nextFace = age + FACE_EVERY + game.random.nextInt(FACE_EVERY);
    }

    private void poseEyes(int ticks) {
        poseEyes(ticks, 1f);
    }

    private void poseEyes(int ticks, float k) {
        if (eyes == null) return;
        float s = (float) Math.max(0.4, Math.min(1.0, diam / 3.2)) * k;
        eyes.pose(new Matrix4f().translate(eyeX, 0.004f, eyeZ).rotateY(eyeRot).scale(s), ticks);
    }

    // ------------------------------------------------------------------ 크기, 쪼개짐, 마름

    /** 지름 = 세대 지름 x (0.8 + 0.2 x 남은 체력 비율). 바뀌면 장판 크기를 다시 보냄. */
    private void resize(int ticks) {
        double f = pieceMax <= 0 ? 1 : Math.max(0, Math.min(1, hp / pieceMax));
        double d = baseDiam * (0.8 + 0.2 * f);
        if (Math.abs(d - diam) < 0.04 && ticks > 0) return;
        diam = d;
        body.scale((float) (diam / MODEL_D), ticks);
    }

    /** 둘로 쪼개짐: 남은 체력을 나눠 갖고 지름 0.64배. 새 조각은 보상 0. 예산이 차면 쪼개지지 않고 그대로 맞는다. */
    private void split() {
        double a = game.random.nextDouble() * Math.PI * 2;
        Vector side = new Vector(Math.cos(a), 0, Math.sin(a)).multiply(Math.max(0.35, r() * 0.45));
        Gabe c = child(Species.G31, pos.clone().add(side));
        if (!(c instanceof G31 o)) return;
        gen++;
        baseDiam *= SHRINK;
        hp = Math.max(0.5, hp / 2);
        pieceMax = maxHp = hp;
        o.adopt(gen, baseDiam, hp, yaw + 140f);
        pos.subtract(side);
        endTongue();
        resize(0);
        body.squash(1.25f, 1f, 0.8f, 2);
        sound("split", Sound.ENTITY_SLIME_SQUISH, 0.9f, 16);
        if (baseDiam < DRY_D) dryOut();
    }

    /** 쪼개져 나온 조각으로 만듦(부모가 부름). */
    private void adopt(int g, double d, double h, float y) {
        gen = g;
        baseDiam = diam = d;
        hp = h;
        pieceMax = maxHp = h;
        inited = true;
        yaw = y;
        riseUntil = -1;
        body.scale((float) (diam / MODEL_D), 0);
        poseEyes(0);
        if (baseDiam < DRY_D) dryOut();
    }

    /** 지름 1 블록보다 작은 조각: 탈색되며 3초 만에 말라 죽는다(그동안 녹이지 못함). */
    private void dryOut() {
        if (dry) return;
        dry = true;
        dryAt = age;
        endTongue();
        if (stander != null) {
            stander = null;
            mark(null);
        }
        body.look.palette |= Look.PAL_PALE;
        body.look.freeze = true;
        if (eyes != null) {
            eyes.remove();
            eyes = null;
        }
        body.scale((float) (diam / MODEL_D * 0.7), DRY_TICKS);
        sound("dry", Sound.BLOCK_SAND_BREAK, 1.4f, 16);
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        // 납작한 원판을 구 일곱으로 덮음(가운데 하나, 둘레 여섯). 목록과 배열은 다시 씀
        double R = r(), y = pos.getY() - 0.12;
        double rc = Math.max(0.22, Math.min(0.45, R * 0.5)), ring = R * 0.6, rr = Math.max(0.2, Math.min(0.45, R * 0.42));
        double[] c = hit.get(0);
        c[0] = pos.getX();
        c[1] = y;
        c[2] = pos.getZ();
        c[3] = rc;
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 3 * i;
            double[] s = hit.get(i + 1);
            s[0] = pos.getX() + Math.cos(a) * ring;
            s[1] = y;
            s[2] = pos.getZ() + Math.sin(a) * ring;
            s[3] = rr;
        }
        return hit;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (pieceMax <= 0) pieceMax = Math.max(1, maxHp);
        DamageType t = d.type();
        boolean burn = t == DamageType.FIRE || t == DamageType.EXPLOSION;
        boolean r = super.damage(burn ? d.times(FIRE_MUL) : d);
        if (dead || dry) return r;
        if (!burn && t != DamageType.BITE && gen < MAX_GEN && riseUntil < 0 && hp <= pieceMax * 0.5) split();
        else resize(3);
        return r;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 죽은 반죽은 바랜 얼룩으로 남는다(눈 조각, 혀는 걷음): 살아 있는 장판과 헷갈리지 않게
        if (tongue != null) tongue.remove();
        tongue = null;
        if (eyes != null) eyes.remove();
        eyes = null;
        if (body != null) {
            body.look.palette |= Look.PAL_PALE;
            body.scale((float) (diam / MODEL_D * 0.75), 8);
        }
        super.die(d);
    }

    /** 시험용. */
    public double diameter() {
        return diam;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(java.util.Locale.ROOT, " d=%.2f gen=%d", diam, gen) + (dry ? " dry" : "")
                + (tongueAt >= 0 ? " tongue" : "") + (stander != null ? " on=" + stander.getName() : "") + (faceStep > 0 ? " face" : "")
                + " prints=" + G31Melt.of(game).prints();
    }
}
