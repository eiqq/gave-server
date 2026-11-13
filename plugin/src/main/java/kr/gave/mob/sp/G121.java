package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.player.Life;

/**
 * G121 채 가는 둥지 게이브(3막 25라운드, 방해 + 원거리, 9블록). 막대기를 엮은 하늘 둥지가 바닥 위 10블록으로 천천히 흘러와 서 있는 사람 머리 위에 선다.
 * 테의 새끼 얼굴 다섯이 아래를 내려다본다. 쓰러진 사람이 생기면 한 번 크게 울고 곧장 그 위로 가서 내려앉아 살 띠로 4초 감아 둥지에 가둔다(셋까지,
 * 둥지를 잡아야 풀림). 둥지 그늘 아래는 어둡다.
 *
 * <p>공격: 6초마다 테에서 가지 둘을 뽑아 내리꽂는다(빨간 예고 원 1초 뒤 반경 2.5 안 26 과 0.6초 굳음, 두 자리 동시에). 내리꽂은 직후 3초는 가지가 땅에
 * 박혀 둥지가 낮게(바닥 위 4) 끌려 내려와 있다.
 *
 * <p>대처: 쓰러진 동료 곁 2블록에 서 있는 사람이 있으면 둥지가 못 내려온다(곁을 지키며 일으킨다). 감는 살 띠(체력 50 x 감독 배율, Mobs.Shootable)를
 * 쏘면 끊긴다. 가지를 내리꽂은 직후 낮게 내려와 쏘기 쉽다. 테의 새끼 얼굴 다섯이 약점(x2).
 *
 * <p>몸: 닻 = 둥지(gave:gabe/g121 상태 "" / "open"), 부품 그림자(g121_shadow, 세계 축), 그때그때 붙였다 떼는 가지(g121_branch), 예고 원(g121_warn),
 * 살 띠(g121_band). pos 는 늘 바닥 + {@link #ALT}, 내려옴은 body.offset(G77Sky 주석).
 */
public final class G121 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 높이(바닥 위), 가지 뒤 끌려 내려온 높이 차(바닥 위 4), 쓰러진 사람 위로 내려앉은 높이 차(둥지 바닥이 머리 위 1.5). */
    static final double ALT = 10, DY_LOW = 4 - ALT, DY_SNATCH = 4.6 - ALT;
    /** 빠르기(블록/틱): 떠돎(0.1), 멀리서 들어올 때, 쓰러진 사람에게 갈 때. */
    static final double DRIFT_V = 0.1, FAR_V = 0.22, RUSH_V = 0.24, FAR = 30;
    /** 가지: 간격, 예고, 박혀 있는 틱, 반경, 피해, 굳음, 두 번째 자리 거리. */
    static final int STAB_EVERY = 120, STAB_TELL = 20, STUCK = 60, ROOT = 12;
    static final double STAB_R = 2.5, STAB_DMG = 26, SECOND_R = 12;
    /** 채 가기: 곁을 지키는 거리, 내려앉는 틱, 감는 틱, 가둘 수 상한, 살 띠 체력, 끊긴 뒤 쉼. 새끼 얼굴 배율. */
    static final double GUARD = 2.0, BAND_HP = 50, BAND_R = 0.5, CHICK_MUL = 2;
    static final int DESCEND = 20, WRAP = 80, MAX_TRAP = 3, SNATCH_REST = 80;
    /** 테(모델 g121.py R, RIM_Y), 새끼 얼굴 수. */
    static final double RIM = 4.4, RIM_Y = 1.2, BOT_Y = -2.0;
    static final int CHICKS = 5;

    enum Phase { ROAM, TELL, STUCK, RISE, SEEK, DESCEND, WRAP, LIFT }

    private Phase phase = Phase.ROAM;
    private int phaseAt, stabAt = 80, snatchReady;
    private final G77Sky.Glide dyG = new G77Sky.Glide();
    private Body.Part shadow;
    private final Body.Part[] branch = new Body.Part[2], warn = new Body.Part[2];
    private Body.Part band;
    private final Vector[] stabAt2 = {new Vector(), new Vector()};
    private final Vector[] branchFrom = {new Vector(), new Vector()};
    private float shadowDy = Float.NaN;
    private int dark;
    private Player prey;
    private double bandHp;
    private final Band bandShot = new Band();
    private boolean bandOn;
    private List<double[]> sph;
    private int sphAge = -1;
    private int downedSeen;

    public G121(Game game, Species sp) {
        super(game, sp);
        radius = 3.0;
        baseSpeed = DRIFT_V;
        noPush = true;
        noBite = true;
        noMutate = true;
        big = true;
        flying = true;
        ragKick = 0.3;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g121", 34f, G77Sky.boxH(ALT));
        body.viewRange(3f);
        dyG.snap(0);
        shadow = body.attach("gabe/g121_shadow");
        if (shadow != null) {
            shadow.follow = false;
            shadow.state("s1");
            dark = 1;
        }
        G77Sky.bright(body);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pos.setY(floor() + ALT);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private void lower(double dy, int ticks) {
        dyG.go(dy, now(), ticks);
        body.offset.set(0, (float) dy, 0);
        body.pose(ticks);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (bandHp == 0) bandHp = BAND_HP * G77Sky.hpK(this);
        vel.setY(Math.max(-0.3, Math.min(0.3, (floor() + ALT - pos.getY()) * 0.15)));
        switch (phase) {
            case ROAM -> roam();
            case TELL -> tell();
            case STUCK -> stuck();
            case RISE -> rise();
            case SEEK -> seek();
            case DESCEND -> descend();
            case WRAP -> wrap();
            default -> phase(Phase.ROAM);
        }
        poseShadow();
        if (every(140) && game.random.nextInt(2) == 0) sound("caw", Sound.ENTITY_PARROT_AMBIENT, 0.5f, 64);
    }

    /** 곁 GUARD 안에 서 있는 사람이 없는 쓰러진 사람(가장 가까운 것, 50블록 안). */
    private Player lonelyDowned() {
        if (game.players.trappedIn(this).size() >= MAX_TRAP) return null;
        Player best = null;
        double bd = 50;
        for (Player p : game.players.downed()) {
            if (game.players.get(p).life != Life.DOWNED || guarded(p)) continue;
            double d = G77Sky.flat(pos, p.getLocation());
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private boolean guarded(Player p) {
        Location l = p.getLocation();
        for (Player q : game.mobs.standing()) if (q != p && q.getLocation().distanceSquared(l) <= GUARD * GUARD) return true;
        return false;
    }

    private void moveOver(Vector at, double v) {
        double d = G77Sky.flat(pos, at);
        if (d < 0.4) {
            vel.setX(0).setZ(0);
            return;
        }
        double k = Math.min(v, d) / d;
        vel.setX((at.getX() - pos.getX()) * k).setZ((at.getZ() - pos.getZ()) * k);
    }

    private void roam() {
        double k = speed() / baseSpeed;
        if (age >= snatchReady) {
            Player d = lonelyDowned();
            int n = game.players.downed().size();
            if (n > downedSeen) sound("caw", Sound.ENTITY_PARROT_AMBIENT, 0.4f, 96);
            downedSeen = n;
            if (d != null) {
                prey = d;
                state("open");
                phase(Phase.SEEK);
                return;
            }
        }
        Player p = nearest(400);
        Vector goal = p != null ? p.getLocation().toVector() : G77Sky.center();
        double dist = G77Sky.flat(pos, goal);
        moveOver(goal, (dist > FAR ? FAR_V : DRIFT_V) * k);
        if (p != null && every(8)) mark(p);
        if (age >= stabAt && p != null && dist < 8) startStab(p);
    }

    // ------------------------------------------------------------------ 가지 내리꽂기

    private void startStab(Player p) {
        vel.setX(0).setZ(0);
        Location l = p.getLocation();
        stabAt2[0].setX(l.getX()).setZ(l.getZ()).setY(game.mobs.flow.floor(l.getX(), l.getZ()));
        // 두 번째 자리: 곁의 다른 사람, 없으면 첫 자리 둘레 4블록
        Player o = null;
        double bd = SECOND_R;
        for (Player q : game.mobs.standing()) {
            if (q == p) continue;
            double d = q.getLocation().distance(l);
            if (d < bd && d > STAB_R * 1.5) {
                bd = d;
                o = q;
            }
        }
        if (o != null) stabAt2[1].setX(o.getLocation().getX()).setZ(o.getLocation().getZ());
        else {
            double a = game.random.nextDouble() * Math.PI * 2;
            stabAt2[1].setX(l.getX() + Math.cos(a) * 4).setZ(l.getZ() + Math.sin(a) * 4);
        }
        stabAt2[1].setY(game.mobs.flow.floor(stabAt2[1].getX(), stabAt2[1].getZ()));
        for (int i = 0; i < 2; i++) {
            Vector s = stabAt2[i];
            warn[i] = body.attach("gabe/g121_warn");
            if (warn[i] != null) {
                warn[i].follow = false;
                warn[i].look.freeze = true;
                warn[i].pose(new Matrix4f().translate((float) (s.getX() - pos.getX()), (float) (s.getY() + 0.07 - pos.getY()), (float) (s.getZ() - pos.getZ()))
                        .scale((float) STAB_R, 1f, (float) STAB_R), 0);
            }
            // 가지: 테에서 뽑혀 위로 솟음
            double a = Math.atan2(s.getZ() - pos.getZ(), s.getX() - pos.getX());
            branchFrom[i].setX(Math.cos(a) * RIM).setY(RIM_Y + 2.5).setZ(Math.sin(a) * RIM);
            branch[i] = body.attach("gabe/g121_branch");
            if (branch[i] != null) {
                branch[i].follow = false;
                branch[i].pose(new Matrix4f().translate((float) branchFrom[i].getX(), (float) (RIM_Y + 0.3), (float) branchFrom[i].getZ()), 0);
            }
        }
        sound("caw", Sound.ENTITY_PARROT_AMBIENT, 0.45f, 64);
        phase(Phase.TELL);
    }

    private void tell() {
        vel.setX(0).setZ(0);
        int t = inPhase();
        if (t == 1) for (int i = 0; i < 2; i++) {
            if (branch[i] != null) branch[i].pose(new Matrix4f().translate((float) branchFrom[i].getX(), (float) branchFrom[i].getY(),
                    (float) branchFrom[i].getZ()), STAB_TELL - 6);
        }
        if (t == STAB_TELL / 2) for (Body.Part w : warn) if (w != null) w.state("fill");
        if (t == STAB_TELL - 4) {
            // 가지가 내리꽂힘(4틱): 땅에 끝이 박히게(가지 길이 3.9)
            for (int i = 0; i < 2; i++) {
                if (branch[i] == null) continue;
                Vector s = stabAt2[i];
                Vector top = new Vector(s.getX() - pos.getX(), s.getY() + 3.5 - pos.getY(), s.getZ() - pos.getZ());
                branch[i].pose(new Matrix4f().translate((float) top.getX(), (float) top.getY(), (float) top.getZ()), 4);
            }
        }
        if (t >= STAB_TELL) {
            for (int i = 0; i < 2; i++) {
                Vector s = stabAt2[i];
                for (Player p : new ArrayList<>(game.mobs.standing())) {
                    Location l = p.getLocation();
                    if (Math.hypot(l.getX() - s.getX(), l.getZ() - s.getZ()) <= STAB_R && Math.abs(l.getY() - s.getY()) < 3) {
                        hit(p, STAB_DMG, "cause_g121");
                        game.players.root(p, ROOT, "g121");
                    }
                }
                G77Sky.quake(game, new Location(game.world, s.getX(), s.getY(), s.getZ()), 2.5f);
                if (warn[i] != null) warn[i].remove();
                warn[i] = null;
            }
            sound("stab", Sound.BLOCK_WOOD_BREAK, 0.6f, 48);
            game.horizon.shake(0.3, 6);
            lower(DY_LOW, 10);
            darkness(2);
            stabAt = age + cd(STAB_EVERY);
            phase(Phase.STUCK);
        }
    }

    private void stuck() {
        vel.setX(0).setZ(0);
        if (inPhase() >= STUCK) {
            for (int i = 0; i < 2; i++) {
                if (branch[i] != null) branch[i].remove();
                branch[i] = null;
            }
            lower(0, 20);
            darkness(1);
            phase(Phase.RISE);
        }
    }

    private void rise() {
        vel.setX(0).setZ(0);
        if (inPhase() >= 20) phase(Phase.ROAM);
    }

    // ------------------------------------------------------------------ 쓰러진 사람 채 가기

    private boolean preyOk() {
        return prey != null && prey.isOnline() && game.players.get(prey).life == Life.DOWNED && !guarded(prey)
                && game.players.trappedIn(this).size() < MAX_TRAP;
    }

    private void giveUp() {
        prey = null;
        state("");
        removeBand();
        if (Math.abs(dyG.to) > 0.01) lower(0, 20);
        snatchReady = age + cd(SNATCH_REST);
        mark(null);
        phase(Phase.RISE);
    }

    private void seek() {
        if (!preyOk()) {
            giveUp();
            return;
        }
        Vector at = prey.getLocation().toVector();
        moveOver(at, RUSH_V * speed() / baseSpeed);
        mark(null);
        if (G77Sky.flat(pos, at) < 1.0) {
            vel.setX(0).setZ(0);
            lower(prey.getLocation().getY() + 4.6 - pos.getY(), DESCEND);
            darkness(2);
            sound("caw", Sound.ENTITY_PARROT_AMBIENT, 0.4f, 64);
            phase(Phase.DESCEND);
        }
    }

    private void descend() {
        vel.setX(0).setZ(0);
        if (!preyOk()) {
            giveUp();
            return;
        }
        if (inPhase() >= DESCEND) {
            band = body.attach("gabe/g121_band");
            if (band != null) band.follow = false;
            bandHp = BAND_HP * G77Sky.hpK(this);
            bandOn = true;
            game.mobs.addShootable(bandShot);
            sound("wrap", Sound.BLOCK_SLIME_BLOCK_PLACE, 0.7f, 32);
            phase(Phase.WRAP);
        }
    }

    private Vector bandTop() {
        return new Vector(pos.getX(), pos.getY() + dyG.at(now()) + BOT_Y - 0.4, pos.getZ());
    }

    private void wrap() {
        vel.setX(0).setZ(0);
        if (!preyOk() || !bandOn) {
            giveUp();
            return;
        }
        int t = inPhase();
        if (band != null && t % 2 == 0) {
            Vector top = bandTop();
            Vector to = prey.getLocation().toVector().add(new Vector(0, 0.4, 0));
            Vector d = to.clone().subtract(top);
            float len = (float) Math.max(0.3, d.length()) * (float) Math.min(1, (t + 2) / 16.0);
            Vector3f u = new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ()).normalize();
            band.pose(new Matrix4f().translate((float) (top.getX() - pos.getX()), (float) (top.getY() - pos.getY()), (float) (top.getZ() - pos.getZ()))
                    .rotate(new Quaternionf().rotationTo(new Vector3f(0, -1, 0), u)).rotateY(t * 0.3f).scale(1f + 0.4f * (t % 4 == 0 ? 1 : 0), len, 1f), 2);
        }
        if (t % 20 == 10) sound("wrap", Sound.BLOCK_SLIME_BLOCK_PLACE, 0.7f, 32);
        if (t >= WRAP) {
            Player p = prey;
            removeBand();
            game.players.trap(p, this);
            prey = null;
            state("");
            sound("caw", Sound.ENTITY_PARROT_AMBIENT, 0.35f, 96);
            lower(0, 30);
            darkness(1);
            snatchReady = age + cd(40);
            phase(Phase.RISE);
        }
    }

    private void removeBand() {
        if (bandOn) game.mobs.removeShootable(bandShot);
        bandOn = false;
        if (band != null) band.remove();
        band = null;
    }

    // ------------------------------------------------------------------ 그림자

    private void darkness(int n) {
        if (shadow == null || n == dark) return;
        dark = n;
        shadow.state("s" + n);
    }

    private void poseShadow() {
        if (shadow == null) return;
        float fy = (float) (floor() + 0.06 - pos.getY());
        if (Math.abs(fy - shadowDy) < 0.05f) return;
        shadowDy = fy;
        shadow.pose(new Matrix4f().translate(0, fy, 0), 3);
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: [x, y, z, r, 부위] 부위 0 둥지, 1 새끼 얼굴. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(12);
        double cy = pos.getY() + dyG.at(now());
        out.add(new double[] {pos.getX(), cy - 0.4, pos.getZ(), 3.0, 0});
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2;
            out.add(new double[] {pos.getX() + Math.cos(a) * 3.0, cy + 0.6, pos.getZ() + Math.sin(a) * 3.0, 1.8, 0});
        }
        for (int i = 0; i < CHICKS; i++) {
            double a = i / (double) CHICKS * Math.PI * 2 + 0.35;
            Vector w = G77Sky.local(Math.cos(a) * (RIM + 0.45), RIM_Y, Math.sin(a) * (RIM + 0.45), yaw);
            out.add(new double[] {pos.getX() + w.getX(), cy + w.getY(), pos.getZ() + w.getZ(), 0.6, 1});
        }
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.at() != null) {
            double best = Double.MAX_VALUE;
            int part = 0;
            for (double[] s : spheres()) {
                double dx = s[0] - d.at().getX(), dy = s[1] - d.at().getY(), dz = s[2] - d.at().getZ();
                double dd = Math.sqrt(dx * dx + dy * dy + dz * dz) - s[3];
                if (dd < best) {
                    best = dd;
                    part = (int) s[4];
                }
            }
            if (part == 1) return super.damage(d.times(CHICK_MUL));
        }
        return super.damage(d);
    }

    /** 감는 살 띠: 둥지 바닥에서 쓰러진 사람까지 반지름 BAND_R 캡슐(쏘면 끊김). */
    private final class Band implements Mobs.Shootable {
        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (!bandOn || prey == null || dead) return Double.POSITIVE_INFINITY;
            Vector a = bandTop(), b = prey.getLocation().toVector().add(new Vector(0, 0.4, 0));
            Vector v = b.clone().subtract(a), w = o.clone().subtract(a);
            double uu = dir.dot(dir), uv = dir.dot(v), vv = v.dot(v), uw = dir.dot(w), vw = v.dot(w);
            double den = uu * vv - uv * uv;
            double s = den < 1e-9 ? 0 : Math.max(0, Math.min(1, (uu * vw - uv * uw) / den));
            Vector q = a.clone().add(v.multiply(s));
            double t = Math.max(0, Math.min(max, q.clone().subtract(o).dot(dir) / uu));
            return o.clone().add(dir.clone().multiply(t)).distanceSquared(q) <= BAND_R * BAND_R ? t : Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean shot(Damage d) {
            if (!bandOn || dead) return false;
            bandHp -= d.amount();
            game.mobs.onHurt(G121.this, d, d.amount());
            if (bandHp <= 0) {
                sound("stab", Sound.ENTITY_ITEM_BREAK, 1.2f, 40);
                removeBand();
            }
            return false;
        }

        @Override
        public Vector center() {
            return prey == null ? bandTop() : bandTop().add(prey.getLocation().toVector()).multiply(0.5);
        }

        @Override
        public boolean gone() {
            return !bandOn || dead || removed;
        }
    }

    private void clearParts() {
        removeBand();
        for (int i = 0; i < 2; i++) {
            if (branch[i] != null) branch[i].remove();
            if (warn[i] != null) warn[i].remove();
            branch[i] = warn[i] = null;
        }
        if (shadow != null) shadow.remove();
        shadow = null;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        clearParts();
        super.die(d);
    }

    @Override
    public void remove() {
        removeBand();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " dy=" + Math.round(dyG.at(now()) * 10) / 10.0 + " trapped=" + game.players.trappedIn(this).size()
                + " prey=" + (prey == null ? "-" : prey.getName());
    }
}
