package kr.gave.mob.sp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * G30 얼굴 다리 지네 게이브(돌격, 함정, 잠복).
 *
 * <p>도감: 엔드 막대기 마디 몸통 아래 작은 얼굴 여섯 쌍이 땅을 짚으며 바짝 붙어 빠르게 긴다. 머리 얼굴은 입 아래 살이 늘어져 바닥에 끌리고
 * 꼬리 끝 막대기가 까딱인다. 빛(조명탄, 보급 상자 등불) 3블록 안에선 꼬리를 말고 뒷걸음질. 꼬리로 찌르면 1 피해와 잠복(찔린 순간 화면이 크게
 * 흔들리고 등 뒤에서 소리): 20초 동안 시점이 조금씩 가장 가까운 동료 쪽으로 돌아가고 마지막 3초엔 몸이 그 동료에게 걸어가 등에서 새끼 지네가
 * 나와 그 동료를 문다(본인과 물린 동료 체력 30%씩, {@link G30Infest}). 잠복은 동료가 등 뒤에서 돕기 3초로 짜내거나 정화, 보급 상자 등불 아래
 * 10초로 풀린다. 칠판 긁는 소리는 가까울수록 높다(사람마다 따로). 몸통 마디를 쏘면 토막 나 느려지고(마디 체력, 본체에는 20%), 머리를 부수면
 * 죽는다. 꼬리 토막이 떨어진 지네는 찌르지 못하고 머리로 물기만 한다(1 피해, 잠복 없음).
 *
 * <p>황야 맵(docs/모듈/맵변경_황야.md): 피하는 빛은 조명탄과 중앙 원 한가운데 보급 상자 등불(둘레 3블록)뿐이다. 탈 벽이 없어 벽 타기는 뺐다.
 *
 * <p>몸: 닻 {@code gave:gabe/g30}(머리) + 부품 {@code gave:gabe/g30_seg}(몸통 앞), {@code gave:gabe/g30_tail}
 * (몸통 뒤, 상태 {@code ""}, {@code "raise"}, {@code "curl"}). 부품은 세계 축(follow false)으로 머리가 지나간 길을 따라감(마디 행렬).
 * 소리 scrape(24), sting(16), crawl(12), baby(16), recoil(16). 원인 글귀 {@code cause_g30}(찔림), {@code cause_g30_bite}(꼬리 없는 지네의 물기),
 * {@code cause_g30_back}(등에서 새끼), {@code cause_g30_baby}(새끼에게 물림).
 */
public final class G30 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.21;
    /** 머리 가운데 높이(바닥에서). */
    static final double HEAD_H = 0.35;
    /** 이 거리 안이면 찌르기 준비(꼬리를 치켜듦), 끝에 이 안이면 찔림. */
    static final double STING_START = 2.4, STING_REACH = 2.1;
    static final int WINDUP = 12, BACKOFF = 24, STING_CD = 160, FLEE_MIN = 30;
    /** 빛: 켜진 조명탄 3블록 안, 보급 상자 등불(중앙 원 한가운데) 둘레 3블록 안. */
    static final double FLARE_R = 3, LANTERN_R = 3.5;
    static final double LANTERN_X = Plaza.CX + 0.5, LANTERN_Z = Plaza.CZ + 0.5;
    /** 머리에서 몸통 앞, 뒤 가운데까지 길 거리. 마디 체력(최대 체력 비율), 마디 피해 중 본체로 가는 비율. */
    static final double SEG1 = 0.72, SEG2 = 1.52, SEG_HP = 0.3, SEG_PASS = 0.2;
    static final double HEAD_R = 0.36, SEG_R = 0.36;

    enum Phase { HUNT, WINDUP, BACKOFF, FLEE }

    private Phase phase = Phase.HUNT;
    private int phaseAt, readyAt, nextScrape, nextCrawl;
    private Body.Part seg, tail;
    private Player prey, picked;
    /** 머리가 지나간 길(x, y, z), 앞이 가장 최근. */
    private final ArrayDeque<double[]> path = new ArrayDeque<>();
    private final double[] segHp = new double[2];
    private final boolean[] segGone = new boolean[2];
    private final Vector[] segPos = {new Vector(), new Vector()};
    private final float[] segYaw = new float[2];
    /** 지난 틱 머리 자리(뒷걸음 판정). */
    private final Vector prev = new Vector();
    private boolean trailed;

    public G30(Game game, Species sp) {
        super(game, sp);
        radius = HEAD_R;
        hover = HEAD_H;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g30", 5f, 1.6f);
        seg = body.attach("gabe/g30_seg");
        tail = body.attach("gabe/g30_tail");
        if (seg != null) seg.follow = false;
        if (tail != null) tail.follow = false;
        seedPath();
        poseSegments(0);
    }

    @Override
    protected void spawned() {
        nextScrape = age + game.random.nextInt(20);
        seedPath();
    }

    /** 길이 아직 없으면 뒤쪽으로 곧게 깔아 둠(몸통이 머리 뒤에 펴진 채 나옴). */
    private void seedPath() {
        path.clear();
        double y = Math.toRadians(yaw);
        Vector back = new Vector(Math.sin(y), 0, -Math.cos(y));
        for (int i = 0; i <= 24; i++) {
            Vector p = pos.clone().add(back.clone().multiply(i * 0.1));
            path.addLast(new double[] {p.getX(), p.getY(), p.getZ()});
        }
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private double segMax() {
        return maxHp * SEG_HP;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (segHp[0] == 0 && segHp[1] == 0 && !segGone[0]) segHp[0] = segHp[1] = segMax();
        trail();
        pitch = 0;
        switch (phase) {
            case HUNT -> hunt();
            case WINDUP -> windup();
            case BACKOFF -> backoff();
            case FLEE -> flee();
        }
        sounds();
    }

    @Override
    public double speed() {
        int lost = (segGone[0] ? 1 : 0) + (segGone[1] ? 1 : 0);
        return super.speed() * (1.0 - 0.28 * lost);
    }

    /** 땅에 바짝 붙어 빠르게 긴다. 빛이면 물러남. 가까우면 꼬리를 치켜듦. */
    private void hunt() {
        tailState("");
        if (every(5) && lit()) {
            startFlee();
            return;
        }
        if (every(4) || (picked != null && !standing(picked))) picked = pick();
        Player p = picked;
        // 흐름장은 가장 가까운 사람 쪽만 안다: 고른 사람(잠복 없는 사람)이 가장 가까운 사람이 아니면 그 사람에게 곧장 간다
        if (p != null && p != nearest(64)) seekFlat(p.getLocation().toVector(), speed());
        else follow(speed());
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-6) {
            // 지네 꿈틀: 몸이 좌우로 S 자
            double w = Math.sin((age + id * 5) * 0.45) * 0.35 * speed();
            vel.add(new Vector(-vel.getZ() / vl, 0, vel.getX() / vl).multiply(w));
            yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        }
        if (p != null && age >= readyAt && dist(p) <= STING_START && sees(p)) {
            prey = p;
            mark(p);
            hold();
            tailState("raise");
            phase(Phase.WINDUP);
        }
    }

    /** 꼬리를 등 위로 치켜들고 떪(예고). 끝에 닿으면 찌름. */
    private void windup() {
        if (!standing(prey)) {
            calm();
            return;
        }
        if (every(5) && lit()) {
            mark(null);
            prey = null;
            startFlee();
            return;
        }
        face(chest(prey));
        // 꼬리 토막이 없으면 꼬리 대신 머리를 쳐듦(물기 예고)
        pitch = segGone[1] ? -22 : 0;
        double d = dist(prey);
        if (d > 1.2) seekFlat(prey.getLocation().toVector(), speed() * 0.6);
        else hold();
        if (inPhase() < cd(WINDUP)) return;
        if (d <= STING_REACH) sting(prey);
        tailState("");
        readyAt = age + cd(STING_CD);
        phase(Phase.BACKOFF);
    }

    private void sting(Player p) {
        if (segGone[1]) {
            // 꼬리 토막이 떨어짐: 찌르지 못하고 머리로 물기만(잠복 없음)
            hit(p, 1, "cause_g30_bite");
            game.shotFx.bloodHit(p.getLocation().add(0, 0.6, 0), toward(p), false, ShotFx.BLOOD_PINK);
            return;
        }
        hit(p, 1, "cause_g30");
        sound("sting", Sound.ENTITY_BEE_STING, 0.8f, 16);
        // 화면이 크게 흔들리고 등 뒤에서 위치 소리
        Guns.kick(p, (float) (game.random.nextBoolean() ? 14 : -14), -9f);
        Location l = p.getLocation();
        Vector back = l.getDirection().setY(0);
        if (back.lengthSquared() > 1e-6) back.normalize().multiply(-1.6);
        if (!game.director.silent())
            Props.soundTo(p, l.clone().add(back).add(0, 1.2, 0), "gabe.g30.scrape", Sound.ENTITY_SILVERFISH_AMBIENT, 1f, 1.3f, 1.4f, -1);
        game.shotFx.bloodHit(p.getLocation().add(0, 1.0, 0), toward(p), false, ShotFx.BLOOD_PINK);
        G30Infest.of(game).infect(p, this);
    }

    /** 찌른 뒤 뒤로 물러남. */
    private void backoff() {
        if (standing(prey)) {
            Vector away = toward(prey).multiply(-speed() * 0.7);
            vel.copy(away);
            face(chest(prey));
            pitch = 0;
        } else hold();
        if (inPhase() >= cd(BACKOFF)) calm();
    }

    private void startFlee() {
        tailState("curl");
        sound("recoil", Sound.ENTITY_SILVERFISH_HURT, 0.7f, 16);
        // 머리는 빛 쪽을 본 채 뒤로: 가장 가까운 빛(등불, 켜진 조명탄)을 바라봄
        Vector light = nearLantern(pos.getX(), pos.getZ()) ? new Vector(LANTERN_X, pos.getY(), LANTERN_Z) : null;
        double bd = light == null ? Double.MAX_VALUE : 0;
        if (light == null) {
            for (Vector f : game.shop.supplies.flares()) {
                double d = f.distanceSquared(pos);
                if (d < bd) {
                    bd = d;
                    light = f;
                }
            }
        }
        if (light != null && light.clone().setY(pos.getY()).distanceSquared(pos) > 1e-4) {
            face(light.clone().setY(pos.getY()));
            pitch = 0;
        }
        phase(Phase.FLEE);
    }

    /** 빛: 꼬리를 말고 뒷걸음질(머리는 빛 쪽을 본 채 뒤로). */
    private void flee() {
        tailState("curl");
        double y = Math.toRadians(yaw);
        Vector back = new Vector(Math.sin(y), 0, -Math.cos(y)).multiply(speed() * 0.75);
        vel.copy(back);
        if (wallAhead(0.6)) vel.multiply(-0.3);
        // 5틱마다 빛을 다시 봄: 빛 밖으로 나왔을 때만 다시 사냥
        if (inPhase() >= FLEE_MIN && inPhase() % 5 == 0 && !lit()) {
            tailState("");
            phase(Phase.HUNT);
        }
    }

    private void calm() {
        mark(null);
        prey = null;
        tailState("");
        phase(Phase.HUNT);
    }

    /** 잠복이 없는 가장 가까운 서 있는 사람(없으면 가장 가까운 사람). */
    private Player pick() {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            PState s = game.players.get(p);
            double d = p.getLocation().toVector().distanceSquared(pos);
            if (s.latent != null) d += 400;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** 켜진 조명탄 3블록 안이거나 보급 상자 등불(중앙 원 한가운데) 둘레 3블록 안. */
    private boolean lit() {
        if (nearLantern(pos.getX(), pos.getZ())) return true;
        return game.shop.nearFlare(pos, FLARE_R);
    }

    /** 보급 상자 등불 둘레 3블록 안인가(G30Infest 의 '등불 아래 10초'도 같은 자리). */
    static boolean nearLantern(double x, double z) {
        double dx = x - LANTERN_X, dz = z - LANTERN_Z;
        return dx * dx + dz * dz <= LANTERN_R * LANTERN_R;
    }

    private void seekFlat(Vector to, double v) {
        Vector d = to.clone().subtract(pos).setY(0);
        double l = d.length();
        vel.copy(l < 1e-6 ? d : d.multiply(Math.min(v, l) / l));
    }

    private void tailState(String s) {
        if (tail != null && !segGone[1]) tail.state(s);
    }

    private void sounds() {
        if (age >= nextCrawl && vel.lengthSquared() > 0.002) {
            sound("crawl", Sound.ENTITY_SILVERFISH_STEP, 1.2f, 12);
            nextCrawl = age + 7 + game.random.nextInt(3);
        }
        if (age < nextScrape || game.director.silent()) return;
        nextScrape = age + 22 + game.random.nextInt(10);
        // 칠판 긁는 소리: 가까울수록 높게(사람마다 따로)
        Location at = loc();
        for (Player p : game.world.getPlayers()) {
            double d = p.getLocation().distance(at);
            if (d > 24) continue;
            float pitchV = (float) (0.75 + 0.6 * (1 - d / 24));
            Props.soundTo(p, at, "gabe.g30.scrape", Sound.ENTITY_SILVERFISH_AMBIENT, 0.8f, pitchV, 1.2f * pitchV, -1);
        }
    }

    // ------------------------------------------------------------------ 마디 행렬

    /**
     * 길 기록과 마디 자세(2틱마다). tick() 맨 앞에서(엔진이 지난 틱에 옮긴 머리 자리를 적음). 뒷걸음(보는 방향 반대로 움직임)일 때는
     * 길을 새로 적지 않고 길 전체를 같은 만큼 민다: 몸통이 머리 뒤에 붙은 채 함께 물러난다(적으면 머리가 제 몸통 위를 지나가 몸통이 앞에 옴).
     */
    private void trail() {
        if (!trailed) {
            trailed = true;
            prev.copy(pos);
        }
        double mx = pos.getX() - prev.getX(), my = pos.getY() - prev.getY(), mz = pos.getZ() - prev.getZ();
        prev.copy(pos);
        double y = Math.toRadians(yaw);
        boolean backward = mx * -Math.sin(y) + mz * Math.cos(y) < -1e-4;
        if (backward) {
            for (double[] q : path) {
                q[0] += mx;
                q[1] += my;
                q[2] += mz;
            }
        } else {
            double[] last = path.peekFirst();
            if (last == null || Math.hypot(pos.getX() - last[0], pos.getZ() - last[2]) >= 0.08 || Math.abs(pos.getY() - last[1]) > 0.05) {
                path.addFirst(new double[] {pos.getX(), pos.getY(), pos.getZ()});
                while (path.size() > 60) path.removeLast();
            }
        }
        if (every(2)) poseSegments(3);
    }

    /** 머리에서 길 거리 s 뒤의 점(길이 모자라면 마지막 점에서 머리 뒤쪽으로 곧게). */
    private Vector along(double s) {
        double left = s;
        double px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        Iterator<double[]> it = path.iterator();
        while (it.hasNext()) {
            double[] q = it.next();
            double dx = q[0] - px, dy = q[1] - py, dz = q[2] - pz, l = Math.sqrt(dx * dx + dz * dz);
            if (l >= left && l > 1e-6) {
                double k = left / l;
                return new Vector(px + dx * k, py + dy * k, pz + dz * k);
            }
            left -= l;
            px = q[0];
            py = q[1];
            pz = q[2];
        }
        double y = Math.toRadians(yaw);
        return new Vector(px + Math.sin(y) * left, py, pz - Math.cos(y) * left);
    }

    private void poseSegments(int ticks) {
        double[] at = {SEG1, SEG2};
        Body.Part[] parts = {seg, tail};
        for (int i = 0; i < 2; i++) {
            Vector c = along(at[i]);
            Vector f = along(at[i] - 0.3), b = along(at[i] + 0.3);
            Vector d = f.clone().subtract(b);
            float yw = d.lengthSquared() > 1e-6 ? (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())) : yaw;
            segPos[i].copy(c);
            segYaw[i] = yw;
            Body.Part p = parts[i];
            if (p == null || segGone[i]) continue;
            Vector rel = c.clone().subtract(pos);
            p.pose(new Matrix4f().translate((float) rel.getX(), (float) rel.getY(), (float) rel.getZ()).rotateY((float) Math.toRadians(-yw)), ticks);
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), HEAD_R});
        for (int i = 0; i < 2; i++) {
            if (segGone[i]) continue;
            Vector c = segPos[i];
            out.add(new double[] {c.getX(), c.getY(), c.getZ(), SEG_R});
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        int segHit = -1;
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            double best = d.at().distanceSquared(pos);
            for (int i = 0; i < 2; i++) {
                if (segGone[i]) continue;
                double dd = d.at().distanceSquared(segPos[i]);
                if (dd < best) {
                    best = dd;
                    segHit = i;
                }
            }
        }
        if (segHit < 0) return super.damage(d);
        if (segHp[segHit] <= 0 && !segGone[segHit]) segHp[segHit] = segMax();
        segHp[segHit] -= d.amount();
        double pass = d.amount() * SEG_PASS;
        double before = hp;
        hp -= pass;
        game.mobs.onHurt(this, d, Math.min(before, pass));
        if (segHp[segHit] <= 0) chop(segHit);
        if (hp <= 0) die(d);
        return true;
    }

    /** 토막: 앞 마디가 끊기면 뒤 마디도 함께 떨어짐. 느려짐. */
    private void chop(int i) {
        for (int k = i; k < 2; k++) {
            if (segGone[k]) continue;
            segGone[k] = true;
            Body.Part p = k == 0 ? seg : tail;
            if (p != null) {
                Vector c = segPos[k];
                game.shotFx.bloodHit(c.toLocation(game.world), new Vector(0, 1, 0), true, ShotFx.BLOOD_PINK);
                p.remove();
            }
            if (k == 0) seg = null;
            else tail = null;
        }
        sound("recoil", Sound.ENTITY_SILVERFISH_HURT, 0.6f, 16);
    }

    /** 시험용. */
    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (segGone[0] ? " seg0x" : "") + (segGone[1] ? " seg1x" : "");
    }
}
