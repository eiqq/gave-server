package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;

/**
 * G196 하늘 아치 게이브(4막 34라운드, 지원, 원거리). 지평선에서 지평선까지 하늘에 걸린 얼굴 마디 아치(원호 반지름 57, 꼭대기가 트럭 위 22, 양 끝은
 * 가운데에서 45블록 땅속: 보이는 길이 100블록쯤). 토막 여섯(토막마다 얼굴 마디 여섯)이 한 끝에서부터 차례로 솟아 30초에 걸쳐 머리 위를 가로질러
 * 걸치고, 몸은 한 방향으로 천천히 흐른다(토막마다 2초에 한 마디씩 밀렸다 되돌아오는 컨베이어).
 * <ul>
 * <li>10초 x 박자마다 트럭 둘레 위를 지나는 마디 셋이 배의 장대를 땅까지 내린다: 1.5초 먼저 그림자 점 셋, 꽂히는 자리 반경 1.5 안 26 과 밀림.
 * 장대마다 8초 동안 작은 게이브 여섯이 미끄러져 내려온다(이번 웨이브의 디스플레이 1짜리 종, 없으면 G01: 보상 0). 장대를 내린 마디는 배를 드러내
 * 그동안 피해 x2.</li>
 * <li>장대는 따로 쏠 수 있다(Mobs.Shootable, 체력 90 x 감독 체력 배율): 꺾이면 타고 내려오던 것들이 떨어져 죽는다.</li>
 * <li>아치 그늘(폭 6 띠) 안 사람은 2초마다 반쯤 침에 맞는다(8).</li>
 * <li>맞는 것은 트럭 둘레 위를 지나는 가운데 토막 둘의 마디 열둘(마디마다 400 x 감독 체력 배율). 여섯이 깨지면(또는 체력이 다하면) 끊어져 걷혀 올라간다.</li>
 * </ul>
 * 몸: {@link Body#carrier} + 부품(토막 g196_seg 여섯, 장대 g196_pole 셋, 그늘 띠와 점 g196_band 넷: 모두 세계 축). Gabe.pos = 아치 꼭대기(트럭 위 22).
 */
public final class G196 extends Gabe {

    static final double ALT = 22, ARC_R = 57, HALF_ANG = Math.toRadians(52), GAP = 2.9, FACE_R = 1.45, BAND_W = 3, POLE_R = 1.5;
    static final double POLE_DMG = 26, SPIT_DMG = 8, FACE_HP = 400, POLE_HP = 90, EXPOSED_MUL = 2;
    static final int SEGS = 6, RISE_T = 600, FLOW_T = 40, POLE_CD = 200, POLE_WARN = 30, POLE_DOWN = 6, RIDE_T = 160, KIDS = 6, SPIT_CD = 40, BREAK_N = 6,
            DYING_T = 40;

    enum Phase { RISE, HOLD, WARN, POLES, DYING }

    private Phase phase = Phase.RISE;
    private int phaseAt, nextPole, broken, risen, spitAt;
    private boolean init;
    private float archYaw;
    private final Vector apex = new Vector();
    private final Body.Part[] seg = new Body.Part[SEGS];
    private final Matrix4f[] segBase = new Matrix4f[SEGS];
    private final Body.Part[] poleP = new Body.Part[3], dots = new Body.Part[3];
    private Body.Part band;
    private final double[] faceHp = new double[12];
    private final Vector[] faceAt = new Vector[12];
    private final int[] poleFace = {-1, -1, -1};
    private final Pole[] poles = new Pole[3];
    private Damage last;
    private List<double[]> sph;
    private int sphAge = -1;

    public G196(Game game, Species sp) {
        super(game, sp);
        radius = 3;
        baseSpeed = 0;
        flying = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        noRules = true;
        big = true;
        leaveCorpse = false;
        hpPow = Director.Tune.HP_POW_BIG;
    }

    /** 내린 장대 하나(따로 쏠 수 있음). */
    private final class Pole implements Mobs.Shootable {
        final int i;
        final Vector top = new Vector(), bot = new Vector();
        double hp;
        boolean down, snapped;
        final List<Gabe> riders = new ArrayList<>();
        int kids;

        Pole(int i) {
            this.i = i;
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (!down || snapped || dead) return Double.POSITIVE_INFINITY;
            return G167Sky.rayCapsule(o, dir, max, top, bot, 0.55);
        }

        @Override
        public boolean shot(Damage d) {
            if (!down || snapped) return false;
            hp -= d.amount();
            if (hp <= 0) snap(this);
            return false;
        }

        @Override
        public Vector center() {
            return top.clone().add(bot).multiply(0.5);
        }

        @Override
        public boolean gone() {
            return dead || removed || !down;
        }
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        body.viewRange(5f);
        for (int i = 0; i < SEGS; i++) {
            seg[i] = body.attach("gabe/g196_seg");
            if (seg[i] != null) {
                seg[i].follow = false;
                seg[i].pose(new Matrix4f().scale(0.001f), 0);
            }
        }
        for (int i = 0; i < 3; i++) {
            poleP[i] = body.attach("gabe/g196_pole");
            if (poleP[i] != null) {
                poleP[i].follow = false;
                poleP[i].pose(new Matrix4f().scale(0.001f), 0);
            }
            dots[i] = body.attach("gabe/g196_band");
            if (dots[i] != null) {
                dots[i].follow = false;
                dots[i].state("dot");
                dots[i].look.freeze = true;
                dots[i].pose(new Matrix4f().scale(0.001f), 0);
            }
            poles[i] = new Pole(i);
        }
        band = body.attach("gabe/g196_band");
        if (band != null) {
            band.follow = false;
            band.look.freeze = true;
            band.pose(new Matrix4f().scale(0.001f), 0);
        }
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Vector c = G167Sky.center();
        archYaw = game.random.nextFloat() * 180f;
        apex.setX(c.getX()).setY(c.getY() + ALT).setZ(c.getZ());
        if (!game.mobs.relocate(this, apex)) pos.copy(apex);
        if (body != null) body.warp();
        layout();
        sound("moan", Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 0.5f, 96);
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

    // ------------------------------------------------------------------ 원호

    /** 꼭대기에서 각 a(라디안, + 가 아치 방향) 의 점(꼭대기 기준 상대). */
    private Vector arc(double a) {
        Vector u = G167Sky.fwd(archYaw);
        double y = ARC_R * Math.cos(a) - ARC_R;
        return new Vector(u.getX() * ARC_R * Math.sin(a), y, u.getZ() * ARC_R * Math.sin(a));
    }

    /** 토막 i 의 자세(꼭대기 기준): 현의 가운데, X = 접선, Y = 바깥 법선. 맞는 마디 자리도 셈. */
    private void layout() {
        Vector u = G167Sky.fwd(archYaw);
        double step = 2 * HALF_ANG / SEGS;
        for (int i = 0; i < SEGS; i++) {
            double a0 = -HALF_ANG + i * step, a1 = a0 + step, am = (a0 + a1) / 2;
            Vector m = arc(a0).add(arc(a1)).multiply(0.5);
            Vector3f ex = new Vector3f((float) (Math.cos(am) * u.getX()), (float) -Math.sin(am), (float) (Math.cos(am) * u.getZ())).normalize();
            Vector3f ey = new Vector3f((float) (Math.sin(am) * u.getX()), (float) Math.cos(am), (float) (Math.sin(am) * u.getZ())).normalize();
            Vector3f ez = new Vector3f(ex).cross(ey).normalize();
            segBase[i] = new Matrix4f(ex.x, ex.y, ex.z, 0, ey.x, ey.y, ey.z, 0, ez.x, ez.y, ez.z, 0, (float) m.getX(), (float) m.getY(), (float) m.getZ(), 1);
            if (i == 2 || i == 3) {
                for (int j = 0; j < 6; j++) {
                    Vector3f p = segBase[i].transformPosition(new Vector3f((float) ((-2.5 + j) * GAP), -0.4f, 0), new Vector3f());
                    faceAt[(i - 2) * 6 + j] = new Vector(p.x, p.y, p.z);
                }
            }
        }
    }

    private Vector faceWorld(int f) {
        return pos.clone().add(faceAt[f]);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            double k = G167Sky.hpK(this);
            for (int f = 0; f < 12; f++) faceHp[f] = FACE_HP * k;
            for (Pole p : poles) p.hp = POLE_HP * k;
            if (segBase[0] == null) layout();
        }
        vel.zero();
        if (every(20)) for (Body.Part p : seg) G167Sky.light(p, G167Sky.FACE_LIGHT);
        switch (phase) {
            case RISE -> rise();
            case HOLD -> idle();
            case WARN -> warn();
            case POLES -> polesTick();
            case DYING -> dying();
        }
        if (phase != Phase.RISE && phase != Phase.DYING) flow();
        if (phase != Phase.DYING && G167Sky.wave(game)) spit();
    }

    private void rise() {
        int t = inPhase();
        int each = Math.max(1, RISE_T / SEGS);
        while (risen < SEGS && t >= risen * each) {
            Body.Part p = seg[risen];
            if (p != null) {
                p.pose(new Matrix4f(segBase[risen]), 40);
            }
            if (risen % 2 == 0) sound("moan", Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 0.5f, 96);
            risen++;
        }
        if (t >= RISE_T) {
            showBand();
            nextPole = age + cd(POLE_CD) / 2;
            phase(Phase.HOLD);
        }
    }

    /** 몸이 한 방향으로 흐름: 토막마다 2초에 한 마디만큼 밀렸다가 되돌아옴(마디가 같은 자리로 오므로 이어진 흐름으로 보임). */
    private void flow() {
        int t = age % FLOW_T;
        if (t != 0 && t != 1) return;
        for (int i = 0; i < SEGS; i++) {
            if (seg[i] == null) continue;
            if (t == 0) seg[i].pose(new Matrix4f(segBase[i]).translate((float) -GAP, 0, 0), 0);
            else seg[i].pose(new Matrix4f(segBase[i]), FLOW_T - 2);
        }
    }

    private void showBand() {
        if (band == null) return;
        Vector c = G167Sky.center();
        band.pose(new Matrix4f().translate((float) (c.getX() - pos.getX()), (float) (c.getY() + 0.05 - pos.getY()), (float) (c.getZ() - pos.getZ()))
                .rotateY((float) Math.toRadians(-archYaw)).scale((float) BAND_W, 1f, 45f), 10);
    }

    private void idle() {
        if (!G167Sky.wave(game) || age < nextPole) return;
        // 깨지지 않은 가운데 마디 셋
        List<Integer> ok = new ArrayList<>();
        for (int f = 0; f < 12; f++) if (faceHp[f] > 0) ok.add(f);
        java.util.Collections.shuffle(ok, game.random);
        for (int i = 0; i < 3; i++) {
            poleFace[i] = i < ok.size() ? ok.get(i) : -1;
            if (poleFace[i] < 0 || dots[i] == null) continue;
            Vector fw = faceWorld(poleFace[i]);
            double fy = game.mobs.flow.floor(fw.getX(), fw.getZ());
            dots[i].pose(new Matrix4f().translate((float) (fw.getX() - pos.getX()), (float) (fy + 0.06 - pos.getY()), (float) (fw.getZ() - pos.getZ()))
                    .scale((float) POLE_R, 1f, (float) POLE_R), 4);
        }
        segLook(true);
        phase(Phase.WARN);
    }

    private void warn() {
        if (inPhase() < cd(POLE_WARN)) return;
        sound("slam", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 64);
        for (int i = 0; i < 3; i++) {
            int f = poleFace[i];
            if (dots[i] != null) dots[i].pose(new Matrix4f().scale(0.001f), 0);
            Pole p = poles[i];
            p.riders.clear();
            p.kids = 0;
            p.snapped = false;
            p.down = false;
            if (f < 0) continue;
            Vector fw = faceWorld(f);
            double fy = game.mobs.flow.floor(fw.getX(), fw.getZ());
            p.top.copy(fw).add(new Vector(0, -0.6, 0));
            p.bot.setX(fw.getX()).setY(fy).setZ(fw.getZ());
            p.hp = POLE_HP * G167Sky.hpK(this);
            p.down = true;
            game.mobs.addShootable(p);
            double len = p.top.getY() - fy;
            if (poleP[i] != null) {
                Matrix4f at = new Matrix4f().translate((float) (p.top.getX() - pos.getX()), (float) (p.top.getY() - pos.getY()), (float) (p.top.getZ() - pos.getZ()));
                poleP[i].pose(new Matrix4f(at).scale(1f, 0.01f, 1f), 0);
                poleP[i].pose(at.scale(1f, (float) (len / 3.0), 1f), POLE_DOWN);
            }
            Location gl = new Location(game.world, fw.getX(), fy, fw.getZ());
            G167Sky.quake(game, gl, 2f);
            for (Player pl : G167Sky.standing(game)) {
                Location l = pl.getLocation();
                if (Math.hypot(l.getX() - fw.getX(), l.getZ() - fw.getZ()) > POLE_R || l.getY() > fy + 3) continue;
                hit(pl, POLE_DMG, "cause_g196");
                Vector away = new Vector(l.getX() - fw.getX(), 0, l.getZ() - fw.getZ());
                knock(pl, away.lengthSquared() > 1e-4 ? away : new Vector(1, 0, 0), 0.6, 0.3);
            }
        }
        phase(Phase.POLES);
    }

    private void polesTick() {
        int t = inPhase();
        int every = Math.max(4, RIDE_T / KIDS);
        if (t > POLE_DOWN && t % every == 0 && G167Sky.wave(game)) {
            Species kid = kidSpecies();
            for (Pole p : poles) {
                if (!p.down || p.snapped || p.kids >= KIDS) continue;
                p.kids++;
                Vector at = p.top.clone().add(new Vector(0, -1.5, 0));
                Gabe g = child(kid, at);
                if (g != null) p.riders.add(g);
            }
        }
        for (Pole p : poles) p.riders.removeIf(g -> g.dead || g.pos.getY() < game.mobs.flow.floor(g.pos.getX(), g.pos.getZ()) + 2.2);
        if (t >= RIDE_T + POLE_DOWN) {
            retract();
            nextPole = age + cd(POLE_CD);
            phase(Phase.HOLD);
        }
    }

    /** 이번 웨이브의 디스플레이 1짜리 종(보스, 깔리는 종, 파생, 거대 빼고). 없으면 G01. */
    private Species kidSpecies() {
        List<Species> c = new ArrayList<>();
        for (Species s : game.director.waveSpecies()) {
            if (!s.implemented() || s.is(Role.BOSS) || s.spawn == Species.Spawn.PLACED || s.spawn == Species.Spawn.DERIVED || s.cost != 1) continue;
            if (s.band == kr.gave.mob.Band.SKY || s == Species.G196 || s == Species.G192) continue;
            c.add(s);
        }
        return c.isEmpty() ? Species.G01 : c.get(game.random.nextInt(c.size()));
    }

    private void snap(Pole p) {
        if (p.snapped) return;
        p.snapped = true;
        sound("snap", Sound.ENTITY_ITEM_BREAK, 0.6f, 64);
        for (Gabe g : p.riders) {
            if (g.dead) continue;
            g.noReward = true;
            g.reward = 0;
            g.die(new Damage(g.hp + 1, DamageType.OTHER, null, g.pos.clone(), new Vector(0, -1, 0), "fall", 0));
        }
        p.riders.clear();
        if (poleP[p.i] != null) {
            Matrix4f at = new Matrix4f().translate((float) (p.top.getX() - pos.getX()), (float) (p.top.getY() - pos.getY()), (float) (p.top.getZ() - pos.getZ()));
            poleP[p.i].pose(at.scale(1f, 0.01f, 1f), 6);
        }
        G167Sky.splash(game, p.top.clone().add(p.bot).multiply(0.5), true);
        game.mobs.removeShootable(p);
        p.down = false;
    }

    private void retract() {
        for (Pole p : poles) {
            if (p.down && poleP[p.i] != null) {
                Matrix4f at = new Matrix4f().translate((float) (p.top.getX() - pos.getX()), (float) (p.top.getY() - pos.getY()), (float) (p.top.getZ() - pos.getZ()));
                poleP[p.i].pose(at.scale(1f, 0.01f, 1f), 8);
            }
            p.down = false;
            game.mobs.removeShootable(p);
        }
        for (int i = 0; i < 3; i++) poleFace[i] = -1;
        segLook(false);
    }

    /** 장대를 내리는 토막(가운데 둘)의 마디가 눈을 뜸. */
    private void segLook(boolean open) {
        for (int i = 2; i <= 3; i++) if (seg[i] != null) seg[i].look.open = open;
    }

    /** 아치 그늘 띠(폭 6) 안 사람: 2초마다 반쯤 침. */
    private void spit() {
        if (phase == Phase.RISE || age < spitAt) return;
        spitAt = age + cd(SPIT_CD);
        Vector c = G167Sky.center();
        Vector u = G167Sky.fwd(archYaw);
        boolean drip = false;
        for (Player p : G167Sky.standing(game)) {
            Location l = p.getLocation();
            double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
            double along = dx * u.getX() + dz * u.getZ(), across = Math.abs(dx * u.getZ() - dz * u.getX());
            if (across > BAND_W || Math.abs(along) > 45 || game.random.nextBoolean()) continue;
            Location at = l.clone().add(game.random.nextGaussian() * 0.6, 1.2, game.random.nextGaussian() * 0.6);
            ItemDisplay d = game.shotFx.spawn(at, "fx_quad", ShotFx.BILE, 0, ShotFx.scale(0.9f), Display.Billboard.CENTER, false, 12, ShotFx.EXTRA);
            game.shotFx.grow(d, ShotFx.scale(1.8f), 10);
            hit(p, SPIT_DMG, "cause_g196_spit");
            drip = true;
        }
        if (drip) sound("drip", Sound.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 0.6f, 24);
    }

    private void dying() {
        if (inPhase() == 1) {
            sound("moan", Sound.ENTITY_ELDER_GUARDIAN_DEATH, 0.5f, 96);
            for (int i = 0; i < SEGS; i++) {
                if (seg[i] == null) continue;
                // 끊어진 가운데에서 양쪽으로 걷혀 올라감
                Matrix4f m = new Matrix4f().translate(0, 14, 0).mul(segBase[i]).scale(0.001f);
                seg[i].pose(m, DYING_T);
            }
            if (band != null) band.pose(new Matrix4f().scale(0.001f), 10);
        }
        if (inPhase() >= DYING_T) {
            untargetable = false;
            super.die(last != null ? last : Damage.of(1, DamageType.OTHER));
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    /** 맞는 구: 가운데 토막 둘의 마디 열둘(번호 = 마디). 솟는 동안은 다 솟은 토막만. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(12);
        if (phase != Phase.DYING) {
            for (int f = 0; f < 12; f++) {
                if (faceHp[f] <= 0 || faceAt[f] == null) continue;
                if (phase == Phase.RISE && risen <= 2 + f / 6) continue;
                Vector w = faceWorld(f);
                out.add(new double[] {w.getX(), w.getY(), w.getZ(), FACE_R, f});
            }
        }
        if (out.isEmpty()) out.add(new double[] {pos.getX(), pos.getY() + 200, pos.getZ(), 0.1, -1});
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || unhittable() || phase == Phase.DYING) return false;
        int f = G167Sky.partAt(spheres(), d.at());
        if (f < 0) return false;
        boolean exposed = false;
        for (int pf : poleFace) if (pf == f && phase == Phase.POLES) exposed = true;
        Damage dd = exposed ? d.times(EXPOSED_MUL) : d;
        double amt = dd.amount();
        hp -= amt;
        faceHp[f] -= amt;
        game.mobs.onHurt(this, dd);
        if (faceHp[f] <= 0) {
            broken++;
            Body.Part p = seg[2 + f / 6];
            if (p != null) p.look.on.add("b" + (f % 6));
            G167Sky.splash(game, faceWorld(f), true);
            sound("snap", Sound.ENTITY_ITEM_BREAK, 0.5f, 64);
        }
        if (broken >= BREAK_N || hp <= 0) {
            last = dd;
            retract();
            mark(null);
            untargetable = true;
            phase(Phase.DYING);
        }
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        for (Pole p : poles) game.mobs.removeShootable(p);
        super.die(d);
    }

    @Override
    public void remove() {
        for (Pole p : poles) if (p != null) game.mobs.removeShootable(p);
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " risen=" + risen + " broken=" + broken + " yaw=" + Math.round(archYaw) + " poles="
                + (poles[0].down ? 1 : 0) + (poles[1].down ? 1 : 0) + (poles[2].down ? 1 : 0);
    }
}
