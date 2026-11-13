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
import org.joml.Matrix4f;
import org.joml.Vector3f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G137 하늘 원반 게이브(4막 31라운드, 목표 압박, 원거리, 20블록). 흙먼지 위에서 소리 없이 미끄러져 와 사람이 가장 많이 모인 곳(대개 트럭 둘레) 22블록
 * 위에 멈춘다. 그림자(반경 10)가 트럭 둘레를 통째로 덮고, 낮은 웅웅 소리만 커지며 테두리 막대기 묶음 여덟이 하나씩 켜진다(박자 배율, 5초 아래로는 안
 * 줄어듦). 다 켜지면 입 구멍이 2초 벌어지고(약점 x3) 그림자 전체에 빛기둥을 내리꽂는다: 그림자 안에 선 사람 45 + 1초 넘어짐, 테두리 2블록 띠 22,
 * 그림자 안 게이브는 휩쓸려 죽고(보상 없음, 거대한 몸, 보스, 죽음 결과 종은 빼고), 그림자에 든 트럭은 최대 체력의 8%. 쏜 뒤 4초 식으며 구멍에서
 * 얼굴 덩이 셋을 그림자 안 사람에게 떨군다(반경 1.5 안 10). 그다음 사람이 많은 곳으로 다시 미끄러져 간다(초당 3블록).
 *
 * <p>대처: 그림자 테두리 밖으로 나간다. 켜진 막대기 묶음(체력 90 x 감독 체력 배율)을 쏴 끄면 그만큼 늦어지고, 한 번 모으는 동안 여덟 묶음을 끄면
 * 빛기둥이 불발로 꺼진다. 쏘기 직전 2초 동안 벌어진 입 구멍이 약점(x3).
 *
 * <p>몸: {@link Body#carrier} + 부품(가운데 g137, 바깥 부채꼴 g137_q 넷: 가운데와 바깥이 서로 반대로 돎, 그림자 g137_shadow 세계 축). 빛기둥(g137_beam)과
 * 얼굴 덩이(g137_lump)는 연출 디스플레이. Gabe.pos = 원반 가운데(바닥 + 22). 사체 없음(기울며 떨어져 부서짐).
 */
public final class G137 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double ALT = 22, R = 10, BAND = 2, ROD_AT = 10.8;
    /** 틱: 모으기(박자 배율, 아래 끝), 입 벌림, 식기(박자, 아래 끝), 덩이 떨어지는 틱, 빛기둥 보이는 틱. */
    static final int CHARGE = 160, CHARGE_MIN = 100, OPEN = 40, COOL = 80, COOL_MIN = 60, LUMP_FALL = 14, BEAM_LIFE = 16, ROOT = 20;
    /** 피해: 빛기둥 안, 테두리 띠, 덩이. 트럭 몫. */
    static final double BEAM_DMG = 45, BAND_DMG = 22, LUMP_DMG = 10, LUMP_R = 1.5, TRUCK_FRAC = 0.08;
    /** 막대기 묶음 하나 체력(감독 체력 배율을 곱함), 미끄러지는 빠르기(블록/틱), 돌기(도/틱: 가운데, 바깥). */
    static final double GROUP_HP = 90, GLIDE_V = 0.15, SPIN_IN = 0.5, SPIN_OUT = -0.35;

    enum Phase { GLIDE, CHARGE, OPEN, COOL, FALL }

    private Phase phase = Phase.GLIDE;
    private int phaseAt;
    private final Vector spot = new Vector();
    private Body.Part core, shadow;
    private final Body.Part[] quads = new Body.Part[4];
    private final boolean[] lit = new boolean[8];
    private final double[] groupHp = new double[8];
    private double groupMax;
    private int litCount, shotOut, chargeTicks = CHARGE, coolTicks = COOL, dark;
    private long nextLight, nextHum;
    private double innerRot, outerRot;
    private boolean init, entered;
    private final List<double[]> lumps = new ArrayList<>();
    private final List<ItemDisplay> lumpFx = new ArrayList<>();
    private Damage last;
    private List<double[]> sph;
    private int sphAge = -1;
    private float fallSpin;
    private double h = ALT;

    public G137(Game game, Species sp) {
        super(game, sp);
        radius = 4.0;
        baseSpeed = GLIDE_V;
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
        core = body.attach("gabe/g137");
        for (int i = 0; i < 4; i++) quads[i] = body.attach("gabe/g137_q");
        G148Sky.bright(core);
        G148Sky.bright(quads);
        shadow = body.attach("gabe/g137_shadow");
        if (shadow != null) {
            shadow.follow = false;
            shadow.state("s1");
            dark = 1;
        }
        poseRings(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pos.setY(floor() + ALT);
        yaw = 0;
        pitch = 0;
        pickSpot();
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

    /** 반경 10 안 서 있는 사람이 가장 많은 자리. 트럭 둘레가 그만큼이면 트럭 가운데. 아무도 없으면 트럭 가운데. */
    private void pickSpot() {
        Player p = G148Sky.densest(game, R, pos);
        Vector c = G148Sky.center();
        if (p == null) {
            spot.setX(c.getX()).setZ(c.getZ());
            return;
        }
        int nP = G148Sky.countNear(game, p.getLocation().getX(), p.getLocation().getZ(), R);
        int nT = G148Sky.countNear(game, c.getX(), c.getZ(), R - 1);
        if (Truck.live() && nT >= nP) spot.setX(c.getX()).setZ(c.getZ());
        else spot.setX(p.getLocation().getX()).setZ(p.getLocation().getZ());
        mark(p);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            groupMax = GROUP_HP * G148Sky.hpK(this);
            for (int i = 0; i < 8; i++) groupHp[i] = groupMax;
        }
        // 남의 끌기(lure)가 face 로 숙인 몸을 바로 세움(원반은 기울지 않음)
        if (phase != Phase.FALL) pitch = 0;
        innerRot += SPIN_IN;
        outerRot += SPIN_OUT;
        if (every(20)) poseRings(20);
        switch (phase) {
            case GLIDE -> glide();
            case CHARGE -> charge();
            case OPEN -> open();
            case COOL -> cool();
            case FALL -> fall();
        }
        tickLumps();
        poseShadow();
        if (!entered && every(10) && nearest(70) != null) {
            entered = true;
            Props.soundAround(loc(), "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.7f, 0.5f, 160, -1);
        }
    }

    private void holdHeight() {
        double want = floor() + h;
        vel.setY(Math.max(-1, Math.min(1, want - pos.getY())));
    }

    private void glide() {
        h = ALT;
        holdHeight();
        if (every(20)) pickSpot();
        double dx = spot.getX() - pos.getX(), dz = spot.getZ() - pos.getZ(), l = Math.hypot(dx, dz);
        double v = GLIDE_V * speed() / baseSpeed;
        if (l < 0.6) {
            vel.setX(0).setZ(0);
            startCharge();
            return;
        }
        double s = Math.min(v, l) / l;
        vel.setX(dx * s).setZ(dz * s);
        darkness(1);
    }

    private void startCharge() {
        chargeTicks = Math.max(CHARGE_MIN, cd(CHARGE));
        litCount = shotOut = 0;
        for (int i = 0; i < 8; i++) {
            lit[i] = false;
            groupHp[i] = groupMax;
        }
        syncLit();
        nextLight = now() + chargeTicks / 8;
        nextHum = now();
        state("wake");
        phase(Phase.CHARGE);
    }

    private void charge() {
        vel.zero();
        holdHeight();
        long now = now();
        if (now >= nextHum) {
            nextHum = now + 40;
            sound("hum", Sound.BLOCK_BEACON_AMBIENT, 0.6f, 96);
        }
        if (now >= nextLight) {
            nextLight = now + Math.max(4, chargeTicks / 8);
            for (int i = 0; i < 8; i++) {
                if (!lit[i]) {
                    lit[i] = true;
                    groupHp[i] = groupMax;
                    sound("lit", Sound.BLOCK_LEVER_CLICK, 0.6f, 48);
                    break;
                }
            }
            syncLit();
        }
        litCount = 0;
        for (boolean b : lit) if (b) litCount++;
        darkness(litCount >= 6 ? 4 : litCount >= 4 ? 3 : 2);
        if (shotOut >= 8) {
            fizzle();
            return;
        }
        if (litCount >= 8) {
            state("open");
            sound("hum", Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 96);
            phase(Phase.OPEN);
        }
    }

    private void fizzle() {
        for (int i = 0; i < 8; i++) lit[i] = false;
        syncLit();
        state("");
        sound("fizzle", Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 64);
        coolTicks = Math.max(COOL_MIN, cd(COOL));
        phase(Phase.COOL);
    }

    private void open() {
        vel.zero();
        holdHeight();
        if (inPhase() >= OPEN) {
            fire();
            for (int i = 0; i < 8; i++) lit[i] = false;
            syncLit();
            state("wake");
            coolTicks = Math.max(COOL_MIN, cd(COOL));
            phase(Phase.COOL);
        }
    }

    /** 빛기둥: 그림자 안 사람, 게이브, 트럭. */
    private void fire() {
        double fl = floor();
        for (Player p : G148Sky.standing(game)) {
            Location l = p.getLocation();
            if (l.getY() > fl + 8) continue;
            double d = G148Sky.flat(pos, l);
            if (d <= R) {
                hit(p, BEAM_DMG, "cause_g137");
                game.players.root(p, ROOT, "g137");
            } else if (d <= R + BAND) {
                hit(p, BAND_DMG, "cause_g137");
            }
        }
        Vector c = new Vector(pos.getX(), fl + 1, pos.getZ());
        for (Gabe g : game.mobs.near(c, R, g -> G148Sky.other(this, g) && !g.big && !g.persist && g.pos.getY() < fl + 9
                && g.sp != Species.G38 && g.sp != Species.G52 && g.sp != Species.G50)) {
            g.noReward = true;
            g.reward = 0;
            g.die(new Damage(99999, DamageType.EXPLOSION, null, g.pos.clone(), new Vector(0, -1, 0), "g137", 0));
        }
        Truck t = Truck.get();
        if (t != null && Truck.dist(pos.getX(), pos.getZ()) <= R) t.hitFrac(TRUCK_FRAC, this, "cause_g137");
        // 연출: 빛기둥(높이 = 바닥에서 원반까지), 땅 고리, 흔들림, 소리
        float hh = (float) ((pos.getY() - fl) / 2);
        Location mid = new Location(game.world, pos.getX(), fl + hh, pos.getZ());
        ItemDisplay beam = G148Sky.fx(game, mid, "gabe/g137_beam", "", new Matrix4f().scale((float) R * 0.98f, hh, (float) R * 0.98f).rotateY((float) Math.PI),
                BEAM_LIFE);
        if (beam != null) beam.setBrightness(new Display.Brightness(15, 15));
        Location gr = new Location(game.world, pos.getX(), fl, pos.getZ());
        G148Sky.quake(game, gr, (float) (R + BAND));
        game.horizon.shake(0.8, 16);
        sound("fire", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 128);
        Props.soundAround(gr, "sfx.quake", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f, 0.6f, 96, -1);
        // 식는 동안 떨굴 덩이 셋
        lumps.clear();
        for (int i = 0; i < 3; i++) lumps.add(new double[] {now() + 10 + i * 20, -1, 0, 0});
    }

    private void cool() {
        vel.zero();
        holdHeight();
        darkness(2);
        if (inPhase() >= coolTicks) {
            state("");
            pickSpot();
            phase(Phase.GLIDE);
        }
    }

    // ------------------------------------------------------------------ 얼굴 덩이

    /** 덩이: [떨굴 틱, 땅에 닿을 틱(-1 = 아직), x, z]. */
    private void tickLumps() {
        long now = now();
        for (Iterator<double[]> it = lumps.iterator(); it.hasNext();) {
            double[] lp = it.next();
            if (lp[1] < 0 && now >= lp[0]) {
                Player p = victimUnder();
                if (p == null || phase == Phase.FALL) {
                    it.remove();
                    continue;
                }
                lp[1] = now + LUMP_FALL;
                lp[2] = p.getLocation().getX();
                lp[3] = p.getLocation().getZ();
                Location from = new Location(game.world, pos.getX(), pos.getY() - 0.8, pos.getZ());
                ItemDisplay d = G148Sky.fx(game, from, "gabe/g137_lump", "", G148Sky.lay("gabe/g137_lump", game.random.nextFloat() * 360, 1.6f, 1.6f, 1.6f),
                        LUMP_FALL + 4);
                if (d != null) {
                    lumpFx.add(d);
                    game.shotFx.drift(d, new Location(game.world, lp[2], floor() + 0.7, lp[3]), LUMP_FALL);
                }
                sound("lit", Sound.ENTITY_SLIME_SQUISH, 0.6f, 48);
            } else if (lp[1] >= 0 && now >= lp[1]) {
                Location at = new Location(game.world, lp[2], floor(), lp[3]);
                for (Player p : G148Sky.standing(game)) {
                    Location l = p.getLocation();
                    if (Math.hypot(l.getX() - lp[2], l.getZ() - lp[3]) <= LUMP_R && l.getY() < at.getY() + 3) hit(p, LUMP_DMG, "cause_g137_lump");
                }
                game.shotFx.pop(at.clone().add(0, 0.5, 0));
                it.remove();
            }
        }
        lumpFx.removeIf(d -> !d.isValid());
    }

    /** 그림자 안에 선 사람 하나(무작위). */
    private Player victimUnder() {
        List<Player> in = new ArrayList<>();
        for (Player p : game.mobs.standing()) if (G148Sky.flat(pos, p.getLocation()) <= R) in.add(p);
        return in.isEmpty() ? null : in.get(game.random.nextInt(in.size()));
    }

    // ------------------------------------------------------------------ 몸

    private void poseRings(int ticks) {
        if (core != null) core.pose(new Matrix4f().rotateY((float) Math.toRadians(innerRot)), ticks);
        for (int i = 0; i < 4; i++) {
            if (quads[i] != null) quads[i].pose(new Matrix4f().rotateY((float) Math.toRadians(outerRot + 90 * i)), ticks);
        }
    }

    private void syncLit() {
        for (int q = 0; q < 4; q++) {
            if (quads[q] == null) continue;
            quads[q].look.on.clear();
            if (lit[2 * q]) quads[q].look.on.add("l1");
            if (lit[2 * q + 1]) quads[q].look.on.add("l2");
        }
    }

    @Override
    public void state(String s) {
        super.state(s);
        if (core != null) core.state(s);
        String qs = s.equals("") ? "" : "wake";
        for (Body.Part q : quads) if (q != null) q.state(qs);
    }

    private void darkness(int n) {
        if (shadow == null || n == dark) return;
        dark = n;
        shadow.state("s" + n);
    }

    private float shDy = Float.NaN;

    private void poseShadow() {
        if (shadow == null) return;
        float fy = (float) (floor() + 0.04 - pos.getY());
        if (Math.abs(fy - shDy) < 0.05f) return;
        shDy = fy;
        shadow.pose(new Matrix4f().translate(0, fy, 0).scale((float) R, 1, (float) R), 3);
    }

    private void fall() {
        untargetable = true;
        int t = inPhase();
        h = Math.max(0.5, h - (0.3 + t * 0.06));
        holdHeight();
        vel.setX(0).setZ(0);
        fallSpin += 1.8f;
        pitch = fallSpin;
        body.roll = fallSpin * 0.3f;
        body.pose(2);
        if (h <= 0.6 || t > 140) {
            Location at = new Location(game.world, pos.getX(), floor(), pos.getZ());
            G148Sky.quake(game, at, (float) R);
            game.horizon.shake(0.7, 14);
            sound("fire", Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 96);
            untargetable = false;
            die(last != null ? last : new Damage(0, DamageType.OTHER, null, pos.clone(), null, "fall", 0));
        }
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: 0..7 막대기 묶음, 8 입 구멍, 9 원반. 틱마다 한 번(기울기 반영). */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(48);
        Matrix4f m = new Matrix4f().rotateX((float) Math.toRadians(pitch)).rotateZ((float) Math.toRadians(body == null ? 0 : body.roll));
        for (int g = 0; g < 8; g++) {
            double a = Math.toRadians(outerRot + 90 * (g / 2) + (g % 2 == 0 ? 22.5 : 67.5));
            add(out, m, Math.sin(a) * ROD_AT, 0, Math.cos(a) * ROD_AT, 1.8, g);
        }
        add(out, m, 0, -0.7, 0, 1.4, 8);
        double[][] rings = {{3.2, 8}, {5.8, 12}, {8.4, 16}};
        for (double[] rg : rings) {
            for (int i = 0; i < rg[1]; i++) {
                double a = 2 * Math.PI * i / rg[1];
                add(out, m, Math.sin(a) * rg[0], 0, Math.cos(a) * rg[0], 1.6, 9);
            }
        }
        sph = out;
        sphAge = age;
        return out;
    }

    private void add(List<double[]> out, Matrix4f m, double x, double y, double z, double r, int part) {
        Vector3f v = m.transformPosition(new Vector3f((float) x, (float) y, (float) z));
        out.add(new double[] {pos.getX() + v.x, pos.getY() + v.y, pos.getZ() + v.z, r, part});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || phase == Phase.FALL) return false;
        int part = G148Sky.partAt(spheres(), d.at());
        Damage dd = part == 8 && phase == Phase.OPEN ? d.times(3) : d;
        double amt = dd.amount();
        hp -= amt;
        game.mobs.onHurt(this, dd);
        if (part >= 0 && part < 8 && lit[part] && phase == Phase.CHARGE) {
            groupHp[part] -= amt;
            if (groupHp[part] <= 0) {
                lit[part] = false;
                groupHp[part] = groupMax;
                shotOut++;
                syncLit();
                nextLight = Math.max(nextLight, now() + 10);
                sound("fizzle", Sound.BLOCK_GLASS_BREAK, 1.2f, 48);
            }
        }
        if (hp <= 0) {
            last = dd;
            mark(null);
            for (int i = 0; i < 8; i++) lit[i] = false;
            syncLit();
            state("");
            lumps.clear();
            phase(Phase.FALL);
        }
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        for (ItemDisplay x : lumpFx) G148Sky.kill(x);
        lumpFx.clear();
        lumps.clear();
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " lit=" + litCount + " out=" + shotOut + " charge=" + chargeTicks + " spot=" + Math.round(spot.getX()) + ","
                + Math.round(spot.getZ()) + " d=" + Math.round(G148Sky.flat(pos, spot));
    }
}
