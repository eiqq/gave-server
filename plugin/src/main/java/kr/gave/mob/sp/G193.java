package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.trip.Truck;

/**
 * G193 빛 쫓는 게이브(4막 33라운드, 돌격, 목표 압박, 8블록 짐승, 둘이 한 쌍). 싸움터 둘레 하늘 20블록을 달리다 15초 x 박자마다 번갈아 내려온다.
 * <ul>
 * <li>등 물기: 켜진 빛(LIGHTS: 발사장 투광등 Mobs.Light, 없으면 트럭 등불 LAMP) 하나 쪽으로 고개를 돌려 눈을 뜨고 2초 x 박자 뒤 덥석 물어 삼킨다.
 * 그 2초 동안 150 x 감독 체력 배율 넘게 맞으면 못 물고 올라간다. 삼킨 놈은 배가 빛나고 느려져 싸움터 위 15블록을 느릿느릿 돌며 훑기만 한다.
 * 삼킨 놈이 죽을 때까지 그 빛은 꺼져 있고 그 빛 반경 안 게이브가 20% 빨라진다. 한 번에 꺼진 빛은 둘까지, 다음 웨이브로 넘어가는 것은 하나(persist).</li>
 * <li>훑기: 사람이 가장 많은 자리를 지나는 직선에 2초 x 박자 먼저 바닥 그림자 줄, 그다음 높이 3으로 그 줄을 달려 지나간다: 폭 4 길 위 사람 모두 30 과
 * 옆으로 날림, 처음 친 한 사람은 발톱에 걸려 10블록 끌려가 떨어짐(12 더). 트럭 위를 지나면 트럭 4%.</li>
 * </ul>
 * 대처: 내려오는 2초 동안 모아 쏜다. 그림자 줄에서 비켜 지나가는 배를 쏜다(배 x2). 삼켰으면 배가 빛나는 놈을 잡아 빛을 꺼낸다.
 * 도감과 다른 점: 등이 깜빡이는 예고는 없음(빛 쪽 눈 뜸과 울음만). 한 쌍은 처음 놓인 놈이 감독이 낸 것처럼 짝을 하나 더 놓는다(spawnLike).
 */
public final class G193 extends Gabe {

    static final double ALT = 20, ORBIT_R = 30, ORBIT_V = 0.25, FULL_ALT = 15, FULL_R = 18, FULL_V = 0.12, SWEEP_H = 3, SWEEP_V = 0.9, PATH_W = 2,
            SWEEP_DMG = 30, DROP_DMG = 12, CARRY_LEN = 10, CANCEL = 150, DARK_BUFF = 1.2, BELLY_MUL = 2, TRUCK_FRAC = 0.04, HALF_LEN = 32;
    static final int CYCLE = 300, AIM_T = 40, DIVE_MAX = 80, CLIMB_T = 60;

    enum Phase { RUN, LAMP_AIM, LAMP_DIVE, SWEEP_AIM, SWEEP, CLIMB }

    private Phase phase = Phase.RUN;
    private int phaseAt, nextAt;
    private boolean init, nextLamp = true, partner, truckHit;
    private int orbitDir = 1;
    private Body.Part beast, belly, line;
    /** 삼킨 빛(Mobs.Light) 또는 트럭 등불(Truck), 없으면 null. 노리는 빛. */
    private Object swallowed, aimLight;
    private final Vector aimAt = new Vector(), lineA = new Vector(), lineB = new Vector(), lastPos = new Vector();
    private double aimDmg;
    private Player clawed;
    private double clawedAt;
    private final Set<UUID> swept = new HashSet<>();
    private final G167Sky.Drops drops = new G167Sky.Drops();
    private List<double[]> sph;
    private int sphAge = -1;

    public G193(Game game, Species sp) {
        super(game, sp);
        radius = 1.6;
        baseSpeed = ORBIT_V;
        flying = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        noRules = true;
        big = true;
        ragKick = 0.3;
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        body.viewRange(4f);
        beast = body.attach("gabe/g193");
        belly = body.attach("gabe/g193_belly");
        if (belly != null) belly.pose(new Matrix4f().translate(0, -1.25f, -0.5f), 0);
        line = body.attach("gabe/g193_line");
        if (line != null) {
            line.follow = false;
            line.look.freeze = true;
            line.pose(new Matrix4f().scale(0.001f), 0);
        }
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        pos.setY(floor() + ALT);
        if (body != null) body.warp();
        sound("howl", Sound.ENTITY_RAVAGER_ROAR, 0.6f, 96);
        // 한 쌍: 혼자 놓였으면 짝을 하나 더(감독이 낸 것처럼)
        int others = 0;
        for (Gabe g : game.mobs.all()) if (g != this && g instanceof G193 && !g.dead) others++;
        if (others == 0 && !partner && game.director.fromDirector(this)) {
            Vector at = pos.clone().add(G167Sky.fwd(game.random.nextDouble() * 360).multiply(12));
            Gabe g = game.director.spawnLike(Species.G193, at);
            if (g instanceof G193 m) {
                m.partner = true;
                m.orbitDir = orbitDir;
                m.nextLamp = !nextLamp;
            }
        }
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

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            nextAt = age + (partner ? cd(CYCLE) / 2 : cd(CYCLE) / 4);
        }
        lastPos.copy(pos);
        drops.tick(this);
        if (swallowed != null) darkBuff();
        if (every(20) && swallowed != null) G167Sky.light(belly, G167Sky.GLOW);
        switch (phase) {
            case RUN -> run();
            case LAMP_AIM -> lampAim();
            case LAMP_DIVE -> lampDive();
            case SWEEP_AIM -> sweepAim();
            case SWEEP -> sweep();
            case CLIMB -> climb();
        }
        if (clawed != null) carryClawed();
    }

    private void run() {
        Vector c = G167Sky.center();
        boolean full = swallowed != null;
        c.setY(floor() + (full ? FULL_ALT : ALT));
        circle(c, full ? FULL_R : ORBIT_R, full ? FULL_V * speed() / baseSpeed : speed(), orbitDir);
        faceFlat(pos.clone().add(vel));
        if (!G167Sky.wave(game) || age < nextAt) return;
        nextAt = age + cd(CYCLE);
        boolean lamp = nextLamp && !full;
        nextLamp = !nextLamp;
        if (lamp && pickLight()) {
            if (beast != null) beast.state("open");
            sound("howl", Sound.ENTITY_RAVAGER_ROAR, 0.6f, 96);
            aimDmg = 0;
            phase(Phase.LAMP_AIM);
        } else startSweep();
    }

    // ------------------------------------------------------------------ 등 물기

    /** 켜진 빛 하나(발사장 투광등 먼저, 없으면 트럭 등불). 꺼진 빛이 이미 둘이면 false. */
    private boolean pickLight() {
        int out = 0;
        for (Gabe g : game.mobs.all()) if (g instanceof G193 m && !m.dead && m.swallowed != null) out++;
        if (out >= 2) return false;
        Mobs.Light best = null;
        double bd = Double.MAX_VALUE;
        for (Mobs.Light l : game.mobs.lights()) {
            if (!l.lit()) continue;
            double d = G167Sky.flat(pos, l.pos());
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        if (best != null) {
            aimLight = best;
            aimAt.copy(best.pos());
            return true;
        }
        Truck t = Truck.get();
        if (t != null && !t.lampDown()) {
            aimLight = t;
            Vector c = Truck.center();
            aimAt.setX(c.getX()).setY(c.getY() + Truck.HEIGHT).setZ(c.getZ());
            return true;
        }
        return false;
    }

    private void lampAim() {
        // 빛 쪽 하늘에 멈춰 고개를 돌림
        Vector over = aimAt.clone().setY(floor() + ALT - 4);
        seek(over, speed());
        faceFlat(aimAt);
        if (aimDmg > CANCEL * G167Sky.hpK(this)) {
            if (beast != null) beast.state("");
            phase(Phase.CLIMB);
            return;
        }
        if (inPhase() >= cd(AIM_T)) {
            sound("rush", Sound.ENTITY_PHANTOM_SWOOP, 0.6f, 48);
            phase(Phase.LAMP_DIVE);
        }
    }

    private void lampDive() {
        Vector to = aimAt.clone().add(new Vector(0, 1.5, 0));
        seek(to, Math.max(0.8, speed() * 3));
        faceFlat(aimAt);
        if (pos.distanceSquared(to) < 2.5 || inPhase() > DIVE_MAX) {
            if (pos.distanceSquared(to) < 9) swallow();
            if (beast != null) beast.state("");
            phase(Phase.CLIMB);
        }
    }

    private void swallow() {
        if (aimLight instanceof Mobs.Light l) {
            if (!l.lit()) return;
            l.off(this, true);
        } else if (aimLight instanceof Truck t) {
            t.lampOff(this, true);
        } else return;
        swallowed = aimLight;
        aimLight = null;
        sound("gulp", Sound.ENTITY_GENERIC_EAT, 0.5f, 64);
        if (belly != null) belly.state("full");
        G167Sky.light(belly, G167Sky.GLOW);
        baseSpeed = FULL_V;
        // 다음 웨이브로 넘어가는 꺼진 빛은 하나까지
        boolean held = false;
        for (Gabe g : game.mobs.all()) if (g != this && g instanceof G193 m && !m.dead && m.persist) held = true;
        if (!held) persist = true;
    }

    private void unswallow() {
        Object s = swallowed;
        swallowed = null;
        if (s instanceof Mobs.Light l) l.off(this, false);
        else if (s instanceof Truck t) t.lampOff(this, false);
    }

    /** 삼킨 빛 반경 안 게이브 20% 빨라짐(이 틱만, 매 틱 다시). */
    private void darkBuff() {
        Vector c;
        double r;
        if (swallowed instanceof Mobs.Light l) {
            c = l.pos();
            r = l.radius();
        } else {
            c = Truck.center();
            r = Truck.LAMP_R + 4;
        }
        for (Gabe g : game.mobs.near(new Vector(c.getX(), floor() + 1, c.getZ()), r + 2, g -> !g.big && !g.sp.is(Role.BOSS) && g != this)) {
            g.speedBuff = Math.max(g.speedBuff, DARK_BUFF);
        }
    }

    // ------------------------------------------------------------------ 훑기

    private void startSweep() {
        Player p = G167Sky.densest(game, 8, pos);
        if (p == null) return;
        Location l = p.getLocation();
        Vector mid = new Vector(l.getX(), floor(), l.getZ());
        Vector dir = mid.clone().subtract(pos).setY(0);
        if (dir.lengthSquared() < 1) dir = G167Sky.fwd(game.random.nextDouble() * 360);
        dir.normalize();
        lineA.copy(mid.clone().subtract(dir.clone().multiply(HALF_LEN)));
        lineB.copy(mid.clone().add(dir.clone().multiply(HALF_LEN)));
        if (line != null) {
            float yawDeg = G167Sky.yawTo(lineA, lineB);
            line.pose(new Matrix4f().translate((float) (mid.getX() - pos.getX()), (float) (game.mobs.flow.floor(mid.getX(), mid.getZ()) + 0.05 - pos.getY()),
                    (float) (mid.getZ() - pos.getZ())).rotateY((float) Math.toRadians(-yawDeg)).scale((float) PATH_W, 1f, (float) HALF_LEN), 0);
        }
        sound("howl", Sound.ENTITY_RAVAGER_ROAR, 0.7f, 96);
        swept.clear();
        truckHit = false;
        phase(Phase.SWEEP_AIM);
    }

    private void sweepAim() {
        Vector start = lineA.clone().setY(floor() + SWEEP_H + 1);
        seek(start, Math.max(1.2, speed() * 4));
        faceFlat(lineB);
        if (inPhase() >= cd(AIM_T) && pos.distanceSquared(start) < 9 || inPhase() > cd(AIM_T) + 60) {
            sound("rush", Sound.ENTITY_PHANTOM_SWOOP, 0.5f, 48);
            phase(Phase.SWEEP);
        }
    }

    private void sweep() {
        Vector end = lineB.clone().setY(floor() + SWEEP_H);
        Vector d = end.clone().subtract(pos);
        double l = d.length();
        double v = Math.max(SWEEP_V, speed() * 3.5);
        if (l <= v || inPhase() > 120) {
            hideLine();
            phase(Phase.CLIMB);
            return;
        }
        vel.copy(d.multiply(v / l));
        faceFlat(end);
        Vector next = pos.clone().add(vel);
        for (Player p : G167Sky.standing(game)) {
            if (swept.contains(p.getUniqueId())) continue;
            Location pl = p.getLocation();
            Vector ch = new Vector(pl.getX(), pl.getY() + 0.9, pl.getZ());
            if (G167Sky.segDist(ch, pos, next) > PATH_W + 0.6) continue;
            swept.add(p.getUniqueId());
            hit(p, SWEEP_DMG, "cause_g193");
            if (!G167Sky.up(game, p)) continue;
            if (clawed == null && grab(p, 99)) {
                PState s = game.players.get(p);
                s.grabDetach = false;
                clawed = p;
                clawedAt = 0;
                mark(p);
            } else {
                Vector side = new Vector(-vel.getZ(), 0, vel.getX());
                Vector rel = new Vector(pl.getX() - pos.getX(), 0, pl.getZ() - pos.getZ());
                if (side.dot(rel) < 0) side.multiply(-1);
                knock(p, side, 1.0, 0.45);
            }
        }
        Truck t = Truck.get();
        if (t != null && !truckHit && Truck.dist(pos.getX(), pos.getZ()) < 2.5) {
            truckHit = true;
            t.hitFrac(TRUCK_FRAC, this, "cause_g193");
        }
    }

    private void carryClawed() {
        if (game.players.grabbedBy(clawed) != this) {
            clawed = null;
            return;
        }
        clawedAt += pos.distance(lastPos);
        if (clawedAt >= CARRY_LEN || phase != Phase.SWEEP) {
            Player p = clawed;
            clawed = null;
            game.players.release(p);
            drops.add(this, p, DROP_DMG, "cause_g193_drop", 20);
            return;
        }
        game.players.carry(clawed, new Vector(pos.getX(), pos.getY() - 2.2, pos.getZ()));
    }

    private void hideLine() {
        if (line != null) line.pose(new Matrix4f().scale(0.001f), 0);
    }

    private void climb() {
        Vector c = G167Sky.center();
        double h = floor() + (swallowed != null ? FULL_ALT : ALT);
        Vector up = pos.clone().setY(h).add(c.clone().subtract(pos).setY(0).normalize().multiply(swallowed != null ? 0 : -0.5));
        seek(up, Math.max(0.45, speed()));
        faceFlat(pos.clone().add(vel));
        if (Math.abs(pos.getY() - h) < 1.5 || inPhase() > CLIMB_T) phase(Phase.RUN);
    }

    /** yaw 만 돌림(몸 전체가 숙여지지 않게). */
    private void faceFlat(Vector at) {
        double dx = at.getX() - pos.getX(), dz = at.getZ() - pos.getZ();
        if (dx * dx + dz * dz < 1e-4) return;
        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = 0;
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    /** 맞는 구: 0 머리와 몸통, 1 배(내려올 때 약점 x2). */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(6);
        out.add(local(0, 0.3, 2.9, 1.5, 0));
        for (double z : new double[] {1.0, -1.0, -3.0}) out.add(local(0, 0.1, z, 1.3, 0));
        out.add(local(0, -1.25, -0.5, 1.1, 1));
        sph = out;
        sphAge = age;
        return out;
    }

    private double[] local(double x, double y, double z, double r, int part) {
        double a = Math.toRadians(yaw), c = Math.cos(a), s = Math.sin(a);
        return new double[] {pos.getX() + x * c - z * s, pos.getY() + y, pos.getZ() + x * s + z * c, r, part};
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || unhittable()) return false;
        int part = G167Sky.partAt(spheres(), d.at());
        boolean low = phase == Phase.SWEEP || phase == Phase.LAMP_DIVE || swallowed != null;
        Damage dd = part == 1 && low ? d.times(BELLY_MUL) : d;
        hp -= dd.amount();
        if (phase == Phase.LAMP_AIM) aimDmg += dd.amount();
        game.mobs.onHurt(this, dd);
        if (hp <= 0) die(dd);
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        unswallow();
        if (clawed != null && game.players.grabbedBy(clawed) == this) game.players.release(clawed);
        clawed = null;
        if (line != null) {
            line.remove();
            line = null;
        }
        super.die(d);
    }

    @Override
    public void remove() {
        unswallow();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " swallowed=" + (swallowed == null ? "-" : swallowed instanceof Truck ? "truck" : "pad") + " persist="
                + persist + " partner=" + partner + " clawed=" + (clawed == null ? "-" : clawed.getName());
    }
}
