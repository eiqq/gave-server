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
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G65 빨랫줄 게이브(1막 방패, 돌격). 장대 둘 사이 줄에 얼굴 천 다섯 장을 널고, 두 장대가 다리처럼 번갈아 걸으며 줄을 팽팽히 편 채 온다.
 * 판에서 체력이 가장 가득 찬 사람에게 줄 한가운데를 맞춰 가서, 6블록 안에서 줄이 팽팽해지고(천이 한꺼번에 섬) 목 높이 줄로 훑고 지나간다.
 *
 * <p>줄에 목이 걸리면 16 피해와 1초 묶임(줄 폭 3.6 안 모두). 웅크리면 줄 밑으로 지나가고 천에만 스쳐 4. 천은 정면 탄을 막는다(천마다 체력 20, 다 깎이면
 * 떨어짐). 장대(체력 35)를 부수면 줄이 처져 훑지 못하고 끌려다니며(sag), 둘 다 부서지면 무너진다. 옆에서 쏘면 천이 못 막는다.
 *
 * <p>팩 계약(tools/gabe/sp/g65.py): 닻 gabe/g65(줄과 천, 원점 = 줄 높이) 상태 "", "taut", "sag", 부위 c1..c5(1 이 몸 오른쪽 끝 -X).
 * 부품 gabe/g65_pole 둘(몸 왼쪽 +X, 오른쪽 -X 에 1.8). 소리 snap, whip, crack. 원인 글귀 cause_g65.
 */
public final class G65 extends Gabe {

    static final double SPEED = 0.17, LINE_Y = 1.5, HALF = 1.8;
    /** 천 자리(몸 기준 x: 왼쪽 +), 천 맞는 구 높이와 반지름, 천 체력, 정면으로 치는 탄 방향 문턱(탄 방향 . 앞 &lt; 이 값). */
    static final double[] CLOTH_X = {-1.44, -0.72, 0.0, 0.72, 1.44};
    static final double CLOTH_Y = -0.42, CLOTH_R = 0.4, CLOTH_HP = 20, FRONT_DOT = -0.35;
    /** 장대: 체력, 맞는 구 높이들과 반지름. 줄 구(천 사이). */
    static final double POLE_HP = 35, POLE_R = 0.3, LINE_R = 0.18;
    static final double[] POLE_Y = {-1.0, 0.0, 1.0};
    /** 훑기: 시작 거리, 팽팽 준비 틱, 빠르기, 표적 너머 더 가는 거리, 최대 틱, 쉬는 틱. */
    static final double SWEEP_RANGE = 6, SWEEP_V = 0.5, OVERSHOOT = 3;
    static final int TAUT = 16, SWEEP_MAX = 26, REST = 30, RETARGET = 40;
    /** 피해: 줄(목), 천 스침, 묶임 틱. */
    static final double LINE_DMG = 6, CLOTH_DMG = 2;
    static final int ROOT = 20, BRUSH_CD = 20;

    enum Phase { WALK, TAUT, SWEEP, REST, SAG }

    private Phase phase = Phase.WALK;
    private int phaseAt, readyAt, retargetAt, brushAt, nextSnap;
    private Player prey;
    private Body.Part poleL, poleR;
    private final double[] clothHp = new double[5];
    private double poleHpL = -1, poleHpR;
    private boolean init;
    private final Vector sweepDir = new Vector(0, 0, 1);
    private double sweepGone, sweepLen;
    private final Set<UUID> swept = new HashSet<>();
    private final Matrix4f mat = new Matrix4f();

    public G65(Game game, Species sp) {
        super(game, sp);
        radius = 0.6;
        hover = LINE_Y;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g65", 4.2f, 3.4f);
        poleL = body.attach("gabe/g65_pole");
        poleR = body.attach("gabe/g65_pole");
        posePoles(0);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    @Override
    public void tick() {
        if (!init) {
            init = true;
            double k = Math.max(1, maxHp / Math.max(1, sp.hp));
            for (int i = 0; i < 5; i++) clothHp[i] = CLOTH_HP * k;
            poleHpL = poleHpR = POLE_HP * k;
        }
        switch (phase) {
            case WALK -> walk();
            case TAUT -> taut();
            case SWEEP -> sweep();
            case REST -> rest();
            case SAG -> sag();
        }
        if (every(2)) posePoles(2);
        // 장대 꼭대기 얼굴도 몸과 같이 실눈을 뜸(찍었을 때)
        if (poleL != null) poleL.look.open = body.look.open;
        if (poleR != null) poleR.look.open = body.look.open;
    }

    /** 장대 걸음: 두 장대가 번갈아 앞뒤로 한 걸음씩(들릴 때 살짝 뜸). 서 있으면 제자리. */
    private void posePoles(int ticks) {
        double mv = Math.hypot(vel.getX(), vel.getZ());
        double ph = age * 0.32;
        double amp = Math.min(0.45, mv * 3.2);
        for (int s = 0; s < 2; s++) {
            Body.Part p = s == 0 ? poleL : poleR;
            if (p == null) continue;
            double c = Math.sin(ph + s * Math.PI);
            mat.identity().translate((float) (s == 0 ? HALF : -HALF), (float) Math.max(0, c) * (float) amp * 0.5f, (float) (c * amp));
            p.pose(mat, ticks);
        }
    }

    /** 체력 몫이 가장 높은 서 있는 사람(같으면 가까운 쪽). */
    private Player healthiest() {
        Player best = null;
        double bf = -1, bd = 0;
        for (Player p : game.mobs.standing()) {
            PState s = game.players.get(p);
            double f = s.maxHp <= 0 ? 0 : s.hp / s.maxHp;
            double d = dist(p);
            if (best == null || f > bf + 0.02 || (f > bf - 0.02 && d < bd)) {
                best = p;
                bf = f;
                bd = d;
            }
        }
        return best;
    }

    private void approach(double sp) {
        if (age >= retargetAt || !standing(prey)) {
            retargetAt = age + RETARGET;
            Player p = healthiest();
            if (p != prey) {
                prey = p;
                mark(p);
            }
        }
        if (prey == null) {
            follow(sp);
            face(pos.clone().add(vel));
            return;
        }
        if (dist(prey) > 40) follow(sp);
        else seek(chest(prey), sp);
        turnTo(chest(prey), 6f);
    }

    private void walk() {
        approach(speed());
        brush();
        snapNoise(40);
        if (prey != null && age >= readyAt && dist(prey) <= SWEEP_RANGE && sees(prey) && facing(prey) > 0.9) {
            hold();
            state("taut");
            sound("snap", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 24);
            nextSnap = age + 6;
            phase(Phase.TAUT);
        }
    }

    /** 줄이 팽팽해지고 천이 한꺼번에 섬(예고). 팡, 팡. */
    private void taut() {
        if (!standing(prey)) {
            calm();
            return;
        }
        hold();
        turnTo(chest(prey), 10f);
        if (age >= nextSnap && inPhase() < cd(TAUT) - 2) {
            sound("snap", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 24);
            nextSnap = age + 8;
        }
        if (inPhase() >= cd(TAUT)) {
            sweepDir.copy(forward());
            sweepLen = dist(prey) + OVERSHOOT;
            sweepGone = 0;
            swept.clear();
            sound("whip", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 20);
            phase(Phase.SWEEP);
        }
    }

    /** 두 장대가 동시에 달려 목 높이 줄로 훑음. 줄 폭 안에서 줄이 몸을 지나간 사람을 친다. */
    private void sweep() {
        double v = SWEEP_V * speed() / baseSpeed;
        dash(sweepDir, v);
        sweepGone += v;
        Vector f = sweepDir, l = new Vector(f.getZ(), 0, -f.getX());
        for (Player p : game.mobs.standing()) {
            if (swept.contains(p.getUniqueId())) continue;
            Location at = p.getLocation();
            double rx = at.getX() - pos.getX(), rz = at.getZ() - pos.getZ();
            double lx = rx * l.getX() + rz * l.getZ(), lz = rx * f.getX() + rz * f.getZ();
            if (Math.abs(lx) > HALF + 0.3 || lz > 0.5 || lz < -0.5 - v) continue;
            if (Math.abs(at.getY() + 0.9 - pos.getY()) > 2.2) continue;
            swept.add(p.getUniqueId());
            if (p.isSneaking()) {
                if (clothNear(lx)) hit(p, CLOTH_DMG, "cause_g65");
            } else {
                hit(p, LINE_DMG, "cause_g65");
                game.players.root(p, ROOT, "g65_line");
                knock(p, f, 0.35, 0.15);
            }
        }
        if (sweepGone >= sweepLen || inPhase() >= SWEEP_MAX || wallAhead(0.8)) {
            state("");
            readyAt = age + cd(REST);
            phase(Phase.REST);
        }
    }

    private void rest() {
        vel.multiply(0.6);
        if (prey != null && standing(prey)) turnTo(chest(prey), 8f);
        if (inPhase() >= cd(REST)) calm();
    }

    private void calm() {
        state(isSag() ? "sag" : "");
        phase(isSag() ? Phase.SAG : Phase.WALK);
    }

    /** 장대 하나가 부서짐: 줄이 처져 훑지 못하고 끌려다님. 닿은 사람은 천에 스침. */
    private void sag() {
        approach(speed() * 0.6);
        brush();
    }

    /** 걷는 중 줄과 천이 몸을 지나가면 천 스침(4). */
    private void brush() {
        if (age < brushAt) return;
        Vector f = forward(), l = new Vector(f.getZ(), 0, -f.getX());
        for (Player p : game.mobs.standing()) {
            Location at = p.getLocation();
            double rx = at.getX() - pos.getX(), rz = at.getZ() - pos.getZ();
            double lx = rx * l.getX() + rz * l.getZ(), lz = rx * f.getX() + rz * f.getZ();
            if (Math.abs(lx) > HALF || Math.abs(lz) > 0.5 || Math.abs(at.getY() + 0.9 - pos.getY()) > 2.0) continue;
            if (!clothNear(lx) && phase != Phase.SAG) continue;
            hit(p, CLOTH_DMG, "cause_g65");
            brushAt = age + cd(BRUSH_CD);
            return;
        }
    }

    /** 몸 기준 x(왼쪽 +) 자리에 아직 남은 천이 있나. */
    private boolean clothNear(double x) {
        for (int i = 0; i < 5; i++) if (clothHp[i] > 0 && Math.abs(CLOTH_X[i] - x) < 0.45) return true;
        return false;
    }

    private void snapNoise(int every) {
        if (age < nextSnap) return;
        nextSnap = age + every + game.random.nextInt(30);
        sound("snap", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 24);
    }

    private boolean isSag() {
        return poleHpL <= 0 || poleHpR <= 0;
    }

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** 앞 방향과 표적 쪽의 코사인. */
    private double facing(Player p) {
        return forward().dot(toward(p));
    }

    // ------------------------------------------------------------------ 맞음

    private double[] at(double lx, double ly) {
        double r = Math.toRadians(yaw), c = Math.cos(r), s = Math.sin(r);
        return new double[] {pos.getX() + c * lx, pos.getY() + ly, pos.getZ() + s * lx, 0};
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(14);
        for (int i = 0; i < 5; i++) {
            if (clothHp[i] <= 0 && init) continue;
            double[] o = at(CLOTH_X[i], phase == Phase.TAUT ? -0.1 : CLOTH_Y);
            o[3] = CLOTH_R;
            out.add(o);
        }
        for (double x : new double[] {-1.08, -0.36, 0.36, 1.08}) {
            double[] o = at(x, 0);
            o[3] = LINE_R;
            out.add(o);
        }
        for (int s = 0; s < 2; s++) {
            if (s == 0 ? poleL == null : poleR == null) continue;
            for (double y : POLE_Y) {
                double[] o = at(s == 0 ? HALF : -HALF, y);
                o[3] = POLE_R;
                out.add(o);
            }
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (d.at() != null) {
            double r = Math.toRadians(yaw), c = Math.cos(r), s = Math.sin(r);
            double rx = d.at().getX() - pos.getX(), rz = d.at().getZ() - pos.getZ(), ly = d.at().getY() - pos.getY();
            double lx = rx * c + rz * s;
            // 장대
            for (int side = 0; side < 2; side++) {
                Body.Part p = side == 0 ? poleL : poleR;
                if (p == null) continue;
                double px = side == 0 ? HALF : -HALF;
                if (Math.abs(lx - px) < 0.5 && ly > -1.7 && ly < 1.6) {
                    if (side == 0) poleHpL -= d.amount();
                    else poleHpR -= d.amount();
                    if ((side == 0 ? poleHpL : poleHpR) <= 0 && breakPole(side)) {
                        // 장대 둘이 다 부서짐: 무너짐(이 탄이 처치)
                        game.mobs.onHurt(this, d, Math.max(0, hp));
                        hp = 0;
                        die(d);
                        return true;
                    }
                    return super.damage(d);
                }
            }
            if ((t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER) && d.dir() != null) {
                // 정면 탄은 천이 막음
                int ci = -1;
                for (int i = 0; i < 5; i++) if (clothHp[i] > 0 && Math.abs(lx - CLOTH_X[i]) < 0.36) ci = i;
                double ty = phase == Phase.TAUT ? -0.1 : CLOTH_Y;
                Vector dir = d.dir().clone().setY(0);
                boolean front = dir.lengthSquared() > 1e-6 && dir.normalize().dot(forward()) < FRONT_DOT;
                if (ci >= 0 && front && Math.abs(ly - ty) < 0.5) {
                    clothHp[ci] -= d.amount();
                    game.mobs.onHurt(this, d, Math.min(1, d.amount()));
                    if (clothHp[ci] <= 0) dropCloth(ci);
                    return false;
                }
            } else if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
                for (int i = 0; i < 5; i++) {
                    if (clothHp[i] <= 0 || Math.abs(lx - CLOTH_X[i]) > 2.5) continue;
                    clothHp[i] -= d.amount();
                    if (clothHp[i] <= 0) dropCloth(i);
                }
            }
        }
        return super.damage(d);
    }

    private void dropCloth(int i) {
        body.look.on.add("c" + (i + 1));
        sound("whip", Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, 0.7f, 16);
        game.shotFx.bloodHit(new Location(game.world, at(CLOTH_X[i], CLOTH_Y)[0], pos.getY() + CLOTH_Y, at(CLOTH_X[i], CLOTH_Y)[2]),
                new Vector(0, -1, 0), false, kr.gave.gun.ShotFx.BLOOD_PINK);
    }

    /** 장대 하나 부러짐. 둘 다 부서졌으면 true(무너짐). */
    private boolean breakPole(int side) {
        Body.Part p = side == 0 ? poleL : poleR;
        if (p == null) return false;
        double[] o = at(side == 0 ? HALF : -HALF, 0);
        p.remove();
        if (side == 0) poleL = null;
        else poleR = null;
        sound("crack", Sound.ENTITY_ITEM_BREAK, 0.6f, 20);
        game.shotFx.pop(new Location(game.world, o[0], o[1], o[2]));
        if (poleL == null && poleR == null) return true;
        hover = LINE_Y - 0.6;
        state("sag");
        if (phase != Phase.REST) phase(Phase.SAG);
        return false;
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        int c = 0;
        for (double h : clothHp) if (h > 0) c++;
        return super.toString() + " ph=" + phase + " cloths=" + c + " poles=" + ((poleL != null ? 1 : 0) + (poleR != null ? 1 : 0));
    }
}
