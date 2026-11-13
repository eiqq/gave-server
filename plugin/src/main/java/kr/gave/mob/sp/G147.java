package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * G147 저울 게이브(4막 33라운드, 방해, 12블록). 하늘 22블록에 걸린 대저울: 저울대 한쪽 끝에 3블록 얼굴 추, 다른 쪽에 쇠사슬 셋에 걸린 폭 5블록 살 접시.
 * 저울대가 트럭 둘레 위에서 천천히 맴돌아 접시가 사람들 머리 위를 훑는다(가볍게 떨어져 선 사람 쪽으로). 접시 그늘(반경 6) 아래 선 사람이 셋 이하면
 * 추보다 가볍다고 보고 그 사람들을 띄워 올린다(초당 2블록, 좌석: 못 걷고 쏠 수는 있음). 넷 이상이면 접시가 무거워 기울 뿐 아무도 못 든다. 떠오르는
 * 동안 그늘에 넷째가 들어오면 도로 떨어져 높이 1블록마다 3 피해. 접시에 실리면 초당 6, 처음 실리고 12초(박자) 뒤 접시가 뒤집혀 추 얼굴의 입에 털려
 * 갇힌다. 사람을 못 든 채 8초(박자)가 지나면 추 얼굴이 사람이 가장 많은 곳 위로 가 빨간 원판 1.5초 뒤 곧장 떨어져 찍는다(반경 3 안 26 + 날림).
 * 추 줄이 다시 감기는 3초 동안 추가 땅에 있다(약점 x2).
 *
 * <p>대처: 그늘이 오면 넷 넘게 뭉친다. 떠오르는 동료 밑으로 뛰어들어 넷을 채우면 떨어진다(낮을 때 채울수록 덜 다친다). 접시 사슬 셋(하나 체력 80 x 감독
 * 체력 배율)을 끊으면 접시가 떨어져 실린 사람이 천천히 내려와 풀린다(15초 뒤 접시가 다시 자람). 추 얼굴이 약점(x2). 떠오르는 사람도 사슬을 쏠 수 있다.
 *
 * <p>몸: {@link Body#carrier} + 부품(저울대 g147: 몸 yaw 와 roll(기울기), 매단 막대기 g147_rod, 추 g147_weight, 접시 g147_pan(c1..c3), 그늘 g147_shadow:
 * 모두 세계 축으로 저울대 끝 자리에). 떠오르는 사람 발밑 흰 고리, 추 원판은 g147_mark 연출. Gabe.pos = 굴대(바닥 + 22). 사체 없음.
 */
public final class G147 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double ALT = 22, BEAM = 6, PAN_DROP = 5.0, PAN_TOP = 4.65, W_DROP = 4.5, SHADE_R = 6, SLAM_R = 3;
    /** 틱: 실린 뒤 털기(박자, 아래 끝), 못 들고 추 찍기까지(박자, 아래 끝), 원판 예고, 추가 땅에 있는 틱, 접시 다시 자람, 털린 뒤 쉼. */
    static final int LOAD_T = 240, LOAD_MIN = 160, IDLE_T = 160, IDLE_MIN = 110, WARN_T = 30, GROUND_T = 60, REGROW_T = 300, FLIP_T = 20;
    /** 피해: 떨어짐(높이 1블록마다), 접시 위(10틱마다 = 초당 6), 추 찍기. */
    static final double FALL_PER = 6, PAN_DMG = 6, SLAM_DMG = 53;
    /** 떠오르는 빠르기(블록/틱), 굴대가 옮겨 가는 빠르기, 돌기(도/틱), 사슬 하나 체력. */
    static final double LIFT_V = 0.1, MOVE_V = 0.12, TURN_V = 0.35, CHAIN_HP = 80;

    enum Phase { ROAM, WARN, SLAM, GROUND, FLIP, FALL }

    /** 떠오르거나 실린 사람 하나. */
    private static final class Rider {
        final Player p;
        double x, z, h, fallFrom = -1;
        boolean loaded;
        int slot;
        ItemDisplay ring;

        Rider(Player p) {
            this.p = p;
        }
    }

    private Phase phase = Phase.ROAM;
    private int phaseAt;
    private final List<Rider> riders = new ArrayList<>();
    private final List<Rider> falling = new ArrayList<>();
    private long loadedAt = -1, idleSince, regrowAt = -1;
    private final double[] chainHp = new double[3];
    private double chainMax;
    private boolean panOn = true, init, entered;
    private Body.Part beamP, rodP, weightP, panP, shadeP;
    private float tilt;
    private final Vector target = new Vector(), slamAt = new Vector();
    private ItemDisplay warn;
    private Damage last;
    private List<double[]> sph;
    private int sphAge = -1;
    private double fallY;

    public G147(Game game, Species sp) {
        super(game, sp);
        radius = 2.0;
        baseSpeed = MOVE_V;
        flying = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        noRules = true;
        big = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        body.viewRange(4f);
        beamP = body.attach("gabe/g147");
        rodP = body.attach("gabe/g147_rod");
        weightP = body.attach("gabe/g147_weight");
        panP = body.attach("gabe/g147_pan");
        shadeP = body.attach("gabe/g147_shadow");
        for (Body.Part p : new Body.Part[] {rodP, weightP, panP, shadeP}) if (p != null) p.follow = false;
        G148Sky.bright(beamP, rodP, weightP, panP);
        poseAll(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pos.setY(floor() + ALT);
        Vector c = G148Sky.center();
        yaw = (float) Math.toDegrees(Math.atan2(-(c.getX() - pos.getX()), c.getZ() - pos.getZ()));
        target.copy(c);
        idleSince = now();
    }

    @Override
    public void lure(Vector at, int ticks) {
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 자리

    /** 몸 기준 (x, y, z) 를 저울대 회전(yaw, roll)으로 세계 차이로. */
    private Vector rel(double x, double y, double z) {
        Vector3f v = new Matrix4f().rotateY((float) Math.toRadians(-yaw)).rotateZ((float) Math.toRadians(tilt))
                .transformPosition(new Vector3f((float) x, (float) y, (float) z));
        return new Vector(v.x, v.y, v.z);
    }

    /** 접시 고리, 추 고리(세계). */
    private Vector panHook() {
        return pos.clone().add(rel(BEAM, -0.6, 0));
    }

    private Vector weightHook() {
        return pos.clone().add(rel(-BEAM, -0.6, 0));
    }

    /** 접시 가운데(세계, 윗면 높이). */
    private Vector panTop() {
        return panHook().add(new Vector(0, -PAN_TOP, 0));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            chainMax = CHAIN_HP * G148Sky.hpK(this);
            for (int i = 0; i < 3; i++) chainHp[i] = chainMax;
        }
        pitch = 0;
        if (!G148Sky.wave(game)) {
            dropAll(false);
            tickFalling();
            vel.zero();
            return;
        }
        double want = floor() + ALT;
        vel.setY(Math.max(-1, Math.min(1, want - pos.getY())));
        if (!panOn && regrowAt > 0 && now() >= regrowAt) regrow();
        switch (phase) {
            case ROAM -> roam();
            case WARN -> warn();
            case SLAM -> slam();
            case GROUND -> ground();
            case FLIP -> flip();
            case FALL -> fall();
        }
        tickFalling();
        if (every(2)) poseAll(2);
        if (!entered && every(10) && nearest(70) != null) {
            entered = true;
            Props.soundAround(loc(), "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.75f, 0.5f, 160, -1);
        }
    }

    private void roam() {
        Vector pt = panTop();
        // 그늘 아래 서 있는 사람(좌석에 탄 사람은 riders 로 셈)
        List<Player> under = new ArrayList<>();
        for (Player p : game.mobs.standing()) {
            if (isRider(p)) continue;
            if (G148Sky.flat(pt, p.getLocation()) <= SHADE_R && p.getLocation().getY() < floor() + 3) under.add(p);
        }
        int n = under.size() + riders.size();
        if (panOn && n >= 4 && !riders.isEmpty()) {
            // 넷째가 들어옴: 도로 떨어짐
            sound("tilt", Sound.BLOCK_CHAIN_BREAK, 0.6f, 64);
            dropAll(true);
        }
        if (panOn && n >= 1 && n <= 3) {
            for (Player p : under) {
                if (riders.size() >= 3) break;
                PState s = game.players.get(p);
                if (s == null || !s.standing() || s.grabbedBy != null) continue;
                Rider r = new Rider(p);
                r.x = p.getLocation().getX();
                r.z = p.getLocation().getZ();
                r.h = 0;
                r.slot = riders.size();
                riders.add(r);
                mark(p);
                game.hud.point(null, p.getLocation().toVector(), 60);
                sound("tilt", Sound.BLOCK_CHAIN_PLACE, 0.6f, 64);
            }
        }
        // 기울기: 사람이 실리면 접시 쪽이 살짝 들리고(가볍다), 넷 이상 모이면 접시 쪽이 크게 내려감(무겁다)
        float want = !riders.isEmpty() ? 6f : n >= 4 ? -14f : 0f;
        tilt += Math.max(-1.5f, Math.min(1.5f, want - tilt));
        liftRiders();
        if (riders.isEmpty()) {
            moveToward(pickTarget(), true);
            if (now() - idleSince >= Math.max(IDLE_MIN, cd(IDLE_T))) startWarn();
        } else {
            vel.setX(0).setZ(0);
            idleSince = now();
            if (loadedAt > 0 && now() - loadedAt >= Math.max(LOAD_MIN, cd(LOAD_T))) {
                weightState("gape");
                sound("gulp", Sound.ENTITY_GENERIC_EAT, 0.5f, 48);
                phase(Phase.FLIP);
            }
        }
    }

    private boolean isRider(Player p) {
        for (Rider r : riders) if (r.p == p) return true;
        for (Rider r : falling) if (r.p == p) return true;
        return false;
    }

    /** 접시가 갈 곳: 반경 6 안 서 있는 사람이 셋 이하인 사람 가운데 접시에서 가까운 사람(가벼운 사람). 없으면 사람이 가장 많은 곳. */
    private Vector pickTarget() {
        Vector pt = panTop();
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (G148Sky.countNear(game, l.getX(), l.getZ(), SHADE_R) > 3) continue;
            double d = G148Sky.flat(pt, l);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        if (best == null) best = G148Sky.densest(game, SHADE_R, pt);
        if (best == null) return G148Sky.center();
        if (target.getX() != best.getLocation().getX()) mark(best);
        return best.getLocation().toVector();
    }

    /** 접시(pan=true)나 추(false)가 to 위에 오게 굴대를 옮기고 천천히 돈다. 다 왔으면 true. */
    private boolean moveToward(Vector to, boolean pan) {
        yaw += (float) TURN_V;
        Vector off = rel(pan ? BEAM : -BEAM, 0, 0);
        double tx = to.getX() - off.getX(), tz = to.getZ() - off.getZ();
        double dx = tx - pos.getX(), dz = tz - pos.getZ(), l = Math.hypot(dx, dz);
        double v = MOVE_V * speed() / baseSpeed;
        if (l < 0.3) {
            vel.setX(0).setZ(0);
            return true;
        }
        double s = Math.min(v, l) / l;
        vel.setX(dx * s).setZ(dz * s);
        return l < 1.0;
    }

    /** 떠오르는 사람 좌석을 올리고, 접시에 닿으면 실림. */
    private void liftRiders() {
        Vector pt = panTop();
        double fl = floor();
        for (Iterator<Rider> it = riders.iterator(); it.hasNext();) {
            Rider r = it.next();
            if (!G148Sky.up(game, r.p)) {
                if (r.p != null && r.p.isOnline()) game.players.unride(r.p);
                G148Sky.kill(r.ring);
                it.remove();
                continue;
            }
            Vector seat;
            if (!r.loaded) {
                r.h += LIFT_V;
                double topH = pt.getY() - fl;
                if (r.h >= topH) {
                    r.loaded = true;
                    if (loadedAt < 0) loadedAt = now();
                    G148Sky.kill(r.ring);
                    r.ring = null;
                }
                seat = new Vector(r.x, fl + Math.min(r.h, topH) + 0.6, r.z);
                if (r.ring == null && !r.loaded) {
                    r.ring = G148Sky.fx(game, new Location(game.world, r.x, fl + 0.03, r.z), "gabe/g147_mark", "lift", new Matrix4f().scale(0.9f), 600);
                }
            } else {
                double a = Math.toRadians(120 * r.slot);
                seat = pt.clone().add(new Vector(Math.cos(a) * 1.2, 0.65, Math.sin(a) * 1.2));
                if (every(10)) hit(r.p, PAN_DMG, "cause_g147_pan");
            }
            if (!game.players.ride(r.p, seat, 6)) {
                G148Sky.kill(r.ring);
                it.remove();
            }
        }
        if (riders.isEmpty()) loadedAt = -1;
    }

    /** 실렸거나 떠오르던 사람을 모두 떨어뜨림(hurt = 높이만큼 피해). 좌석을 빠르게 내린 뒤 내린다. */
    private void dropAll(boolean hurt) {
        double fl = floor();
        for (Rider r : riders) {
            G148Sky.kill(r.ring);
            r.ring = null;
            if (!G148Sky.up(game, r.p)) {
                if (r.p != null && r.p.isOnline()) game.players.unride(r.p);
                continue;
            }
            Location l = r.p.getLocation();
            r.x = l.getX();
            r.z = l.getZ();
            r.h = Math.max(0, l.getY() - fl);
            r.fallFrom = hurt ? r.h : -1;
            falling.add(r);
        }
        riders.clear();
        loadedAt = -1;
    }

    private void tickFalling() {
        double fl = floor();
        for (Iterator<Rider> it = falling.iterator(); it.hasNext();) {
            Rider r = it.next();
            if (!G148Sky.up(game, r.p)) {
                if (r.p != null && r.p.isOnline()) game.players.unride(r.p);
                it.remove();
                continue;
            }
            r.h -= r.fallFrom >= 0 ? 1.1 : 0.45;
            if (r.h <= 0.05) {
                game.players.unride(r.p);
                if (r.fallFrom > 0.5) hit(r.p, FALL_PER * r.fallFrom, "cause_g147_fall");
                it.remove();
                continue;
            }
            game.players.ride(r.p, new Vector(r.x, fl + r.h + 0.6, r.z), 4);
        }
    }

    // ------------------------------------------------------------------ 추 찍기

    private void startWarn() {
        Player p = G148Sky.densest(game, SLAM_R + 1, weightHook());
        Vector to = p != null ? p.getLocation().toVector() : G148Sky.center();
        if (p != null) mark(p);
        slamAt.copy(to);
        phase(Phase.WARN);
    }

    private void warn() {
        boolean there = moveToward(slamAt, false);
        if (warn == null && (there || inPhase() > 60)) {
            vel.setX(0).setZ(0);
            Vector wh = weightHook();
            slamAt.setX(wh.getX()).setZ(wh.getZ());
            warn = G148Sky.fx(game, new Location(game.world, wh.getX(), floor() + 0.03, wh.getZ()), "gabe/g147_mark", "warn",
                    new Matrix4f().scale((float) SLAM_R), WARN_T + 4);
            sound("tilt", Sound.BLOCK_CHAIN_BREAK, 0.5f, 64);
            phaseAt = age;
        }
        if (warn != null) {
            vel.setX(0).setZ(0);
            if (inPhase() >= WARN_T) {
                G148Sky.kill(warn);
                warn = null;
                weightState("gape");
                phase(Phase.SLAM);
            }
        }
    }

    private void slam() {
        vel.setX(0).setZ(0);
        if (inPhase() == 6) {
            double fl = floor();
            for (Player p : G148Sky.standing(game)) {
                Location l = p.getLocation();
                if (isRider(p) || l.getY() > fl + 4) continue;
                double d = Math.hypot(l.getX() - slamAt.getX(), l.getZ() - slamAt.getZ());
                if (d <= SLAM_R) {
                    hit(p, SLAM_DMG, "cause_g147");
                    Vector out = l.toVector().subtract(slamAt).setY(0);
                    knock(p, out.lengthSquared() < 1e-4 ? new Vector(1, 0, 0) : out, 1.1, 0.6);
                }
            }
            Location at = new Location(game.world, slamAt.getX(), fl, slamAt.getZ());
            sound("slam", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 96);
            G148Sky.quake(game, at, (float) SLAM_R + 2);
            game.horizon.shake(0.7, 12);
            phase(Phase.GROUND);
        }
    }

    private void ground() {
        vel.setX(0).setZ(0);
        if (inPhase() >= GROUND_T) {
            weightState("");
            idleSince = now();
            phase(Phase.ROAM);
        }
    }

    // ------------------------------------------------------------------ 털기

    private void flip() {
        vel.setX(0).setZ(0);
        liftRiders();
        if (inPhase() == FLIP_T / 2) {
            for (Rider r : new ArrayList<>(riders)) {
                if (!G148Sky.up(game, r.p) || !r.loaded) continue;
                game.players.unride(r.p);
                game.players.trap(r.p, this);
                riders.remove(r);
            }
            sound("gulp", Sound.ENTITY_GENERIC_EAT, 0.4f, 48);
        }
        if (inPhase() >= FLIP_T) {
            weightState("");
            dropAll(true);
            idleSince = now();
            phase(Phase.ROAM);
        }
    }

    private void cutPan() {
        panOn = false;
        regrowAt = now() + cd(REGROW_T);
        if (panP != null) panP.look.on.add("c1");
        dropAll(false);
        sound("chain", Sound.BLOCK_CHAIN_BREAK, 0.8f, 48);
        Vector pt = panTop();
        G148Sky.quake(game, new Location(game.world, pt.getX(), floor(), pt.getZ()), 4f);
    }

    private void regrow() {
        panOn = true;
        regrowAt = -1;
        for (int i = 0; i < 3; i++) chainHp[i] = chainMax;
        if (panP != null) panP.look.on.clear();
        sph = null;
    }

    // ------------------------------------------------------------------ 몸

    private void weightState(String s) {
        if (weightP != null) weightP.state(s);
    }

    private void poseAll(int ticks) {
        body.roll = tilt;
        body.pose(ticks);
        Vector ph = panHook().subtract(pos), wh = weightHook().subtract(pos);
        if (rodP != null) rodP.pose(new Matrix4f(), ticks);
        if (panP != null) {
            float y = panOn ? (float) ph.getY() : (float) (floor() + 0.3 + PAN_DROP - pos.getY());
            panP.pose(new Matrix4f().translate((float) ph.getX(), y, (float) ph.getZ()).rotateY((float) Math.toRadians(-yaw)), ticks);
        }
        if (weightP != null) {
            float wy = (float) wh.getY();
            if (phase == Phase.SLAM || phase == Phase.GROUND) wy = (float) (floor() + W_DROP + 1.6 - pos.getY());
            weightP.pose(new Matrix4f().translate((float) wh.getX(), wy, (float) wh.getZ()).rotateY((float) Math.toRadians(-yaw + 180)),
                    phase == Phase.SLAM && inPhase() < 6 ? 5 : ticks);
        }
        if (shadeP != null) {
            float fy = (float) (floor() + 0.04 - pos.getY());
            shadeP.pose(new Matrix4f().translate((float) ph.getX(), fy, (float) ph.getZ()).scale((float) SHADE_R, 1f, (float) SHADE_R), ticks);
        }
    }

    private void fall() {
        untargetable = true;
        dropAll(false);
        int t = inPhase();
        fallY = Math.max(1, (pos.getY() - floor()) - (0.4 + t * 0.08));
        vel.setX(0).setZ(0).setY(floor() + fallY - pos.getY());
        tilt += 2.5f;
        if (fallY <= 1.05 || t > 120) {
            Location at = new Location(game.world, pos.getX(), floor(), pos.getZ());
            G148Sky.quake(game, at, 6f);
            sound("slam", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 96);
            game.horizon.shake(0.6, 12);
            untargetable = false;
            die(last != null ? last : new Damage(0, DamageType.OTHER, null, pos.clone(), null, "fall", 0));
        }
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: 0 추 얼굴, 1 접시, 2..4 사슬, 5 저울대와 굴대. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(12);
        Vector wh = weightHook();
        double wy = phase == Phase.SLAM || phase == Phase.GROUND ? floor() + W_DROP + 1.6 - W_DROP : wh.getY() - W_DROP;
        out.add(new double[] {wh.getX(), wy, wh.getZ(), 1.9, 0});
        if (panOn) {
            Vector pt = panTop();
            out.add(new double[] {pt.getX(), pt.getY() - 0.3, pt.getZ(), 2.4, 1});
            Vector ph = panHook();
            for (int i = 0; i < 3; i++) {
                if (chainHp[i] <= 0) continue;
                double a = Math.toRadians(120 * i + 90 - yaw);
                double r = 1.2;
                out.add(new double[] {ph.getX() + Math.cos(a) * r, ph.getY() - 2.4, ph.getZ() + Math.sin(a) * r, 0.9, 2 + i});
            }
        }
        for (int i = -2; i <= 2; i++) {
            Vector b = pos.clone().add(rel(i * 2.6, 0, 0));
            out.add(new double[] {b.getX(), b.getY(), b.getZ(), 1.1, 5});
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || phase == Phase.FALL) return false;
        int part = G148Sky.partAt(spheres(), d.at());
        Damage dd = part == 0 ? d.times(2) : d;
        double amt = dd.amount();
        hp -= amt;
        game.mobs.onHurt(this, dd);
        if (part >= 2 && part <= 4 && panOn && chainHp[part - 2] > 0) {
            chainHp[part - 2] -= amt;
            if (chainHp[part - 2] <= 0) {
                sph = null;
                sound("chain", Sound.BLOCK_CHAIN_BREAK, 1.0f, 48);
                int cut = 0;
                for (double c : chainHp) if (c <= 0) cut++;
                if (cut >= 3) cutPan();
                else if (panP != null) panP.look.on.add("c" + (part - 1));
            }
        }
        if (hp <= 0) {
            last = dd;
            mark(null);
            G148Sky.kill(warn);
            warn = null;
            phase(Phase.FALL);
        }
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        G148Sky.kill(warn);
        for (Rider r : riders) {
            G148Sky.kill(r.ring);
            if (r.p != null && r.p.isOnline() && game.players.get(r.p).life == Life.ALIVE) game.players.unride(r.p);
        }
        for (Rider r : falling) if (r.p != null && r.p.isOnline()) game.players.unride(r.p);
        riders.clear();
        falling.clear();
        super.die(d);
    }

    @Override
    public void remove() {
        for (Rider r : riders) {
            G148Sky.kill(r.ring);
            if (r.p != null && r.p.isOnline()) game.players.unride(r.p);
        }
        for (Rider r : falling) if (r.p != null && r.p.isOnline()) game.players.unride(r.p);
        riders.clear();
        falling.clear();
        G148Sky.kill(warn);
        super.remove();
    }

    @Override
    public String toString() {
        int cut = 0;
        for (double c : chainHp) if (c <= 0) cut++;
        int loaded = 0;
        for (Rider r : riders) if (r.loaded) loaded++;
        return super.toString() + " ph=" + phase + " riders=" + riders.size() + " loaded=" + loaded + " falling=" + falling.size() + " tilt="
                + Math.round(tilt) + " cut=" + cut + " pan=" + panOn;
    }
}
