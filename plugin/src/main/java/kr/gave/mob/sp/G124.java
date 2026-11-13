package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;

/**
 * G124 도깨비불 게이브(방해, 떼, 3막). 가장자리에서 날아와 사람마다 하나씩 짝을 정해(짝이 가장 적은 사람) 머리 뒤 위에 붙어 따라다닌다(사람이 돌아보면
 * 반대쪽으로 돈다: 한 틱에 12도까지만). 짝이 게이브를 직접 처치할 때마다(PState.kills) 불꽃이 줄어 푸르러지고, 15초(300틱, 박자 배율) 동안 아무것도
 * 못 잡으면 붉게 부풀어 머리에 내리꽂혀 터진다(22 피해, 3초 화상 초당 3). 터진 뒤 5초면 멀리서 새 불이 다시 날아온다(같은 개체: 작아져 사라졌다가
 * 28블록 밖에서 다시 옴). 마지막 3초는 짝의 조준점 옆에 이 종 표시가 깜빡인다.
 *
 * <p>대처: 탄 한 발이면 꺼진다(동료가 쏘거나, 짝이 위를 크게 올려다보면 2초 동안 눈앞에 내려와 스스로도 쏠 수 있다). 15초 안에 직접 하나를 잡는다.
 *
 * <p>팩(tools/gabe/sp/g124.py): 모델 {@code gave:gabe/g124} 상태 "" 배부름, "hungry" 굶주림, "dive" 내리꽂힘. 소리 crackle(불 타는 소리, 12),
 * burst(불 터짐, 24), hush(불이 잦아듦, 10). 글귀 cause_g124, cause_g124_burn. HUD 표시 종 키 g124.
 */
public final class G124 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 날아오는 빠르기, 붙는 자리(눈 위, 뒤 거리), 돌아보면 따라 도는 각(도/틱). */
    static final double FLY_V = 0.32, PERCH_UP = 0.95, PERCH_BACK = 0.75;
    static final double TURN_DEG = 12;
    /** 굶는 한도(틱, 박자 배율), 표시를 깜빡이는 마지막 틱. */
    static final int STARVE = 300, WARN = 60;
    /** 내리꽂힘: 빠르기, 피해, 화상(틱, 간격, 피해). */
    static final double DIVE_V = 0.6, BURST_DMG = 22, BURN_DMG = 3;
    static final int BURN_T = 60, BURN_EVERY = 20, DIVE_MAX = 30;
    /** 터진 뒤 다시 오기까지(사라진 틱), 다시 나오는 거리. */
    static final int GONE = 40;
    static final double RETURN_R = 28;
    /** 위를 올려다본 피치(아래로 +, 그래서 음수), 눈앞에 머무는 틱, 다시 내려오기까지. */
    static final float LOOK_UP = -55f;
    static final int REVEAL = 40, REVEAL_REST = 60;
    /** 굶주림 단계별 크기. */
    static final float[] SCALE = {0.8f, 1.05f, 1.3f};

    enum Phase { FLY, PERCH, REVEAL, DIVE, GONE }

    private Phase phase = Phase.FLY;
    private int phaseAt, stage = -1, nextCrackle, revealReady;
    private Player mate;
    private int lastKills;
    private long fedAt;
    private double orbitA;
    private int burnLeft, burnNext;
    private Player burnOn;

    public G124(Game game, Species sp) {
        super(game, sp);
        radius = 0.3;
        baseSpeed = FLY_V;
        flying = true;
        noPush = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g124", 0.9f, 1.2f);
    }

    @Override
    protected void spawned() {
        nextCrackle = age + game.random.nextInt(30);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 짝

    /** 짝이 가장 적은 서 있는 사람(같으면 가까운 사람). */
    private Player pickMate() {
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            int n = 0;
            for (Gabe g : game.mobs.all()) if (g != this && !g.dead && g instanceof G124 o && o.mate == p) n++;
            double s = n * 1000 + p.getLocation().toVector().distance(pos);
            if (s < bestScore) {
                bestScore = s;
                best = p;
            }
        }
        return best;
    }

    private void bind(Player p) {
        mate = p;
        mark(p);
        lastKills = game.players.get(p).kills;
        fedAt = game.now();
        Location l = p.getLocation();
        orbitA = Math.atan2(pos.getZ() - l.getZ(), pos.getX() - l.getX());
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        burnTick();
        if (phase != Phase.GONE && phase != Phase.DIVE && !standing(mate)) {
            Player p = pickMate();
            if (p == null) {
                follow(speed());
                return;
            }
            bind(p);
            if (phase != Phase.FLY) phase(Phase.FLY);
        }
        switch (phase) {
            case FLY -> fly();
            case PERCH -> perch();
            case REVEAL -> inFront();
            case DIVE -> dive();
            case GONE -> gone();
        }
    }

    /** 머리 뒤 위 자리(짝이 보는 쪽 반대, 한 틱에 TURN_DEG 까지만 돌아감). */
    private Vector perchPoint(boolean turn) {
        Location eye = mate.getEyeLocation();
        double back = Math.toRadians(eye.getYaw()) + Math.PI; // 보는 쪽(-sin, cos)의 반대
        double want = Math.atan2(Math.cos(back), -Math.sin(back));
        if (turn) {
            double diff = Math.atan2(Math.sin(want - orbitA), Math.cos(want - orbitA));
            double step = Math.toRadians(TURN_DEG);
            orbitA += Math.max(-step, Math.min(step, diff));
        }
        return new Vector(eye.getX() + Math.cos(orbitA) * PERCH_BACK, eye.getY() + PERCH_UP, eye.getZ() + Math.sin(orbitA) * PERCH_BACK);
    }

    /** 가장자리에서 짝의 머리 뒤로 날아옴. */
    private void fly() {
        Vector at = perchPoint(false);
        seek(at, speed());
        face(at);
        hunger(false);
        if (at.distanceSquared(pos) < 4) phase(Phase.PERCH);
    }

    /** 머리 뒤 위에 붙어 따라다님. 굶주림 시계. 위를 크게 올려다보면 눈앞으로. */
    private void perch() {
        Vector at = perchPoint(true);
        Vector d = at.subtract(pos);
        double l = d.length();
        if (l > 0.9) d.multiply(0.9 / l);
        vel.copy(d.multiply(0.6));
        face(mate.getEyeLocation().toVector());
        if (hunger(true)) return;
        if (mate.getLocation().getPitch() <= LOOK_UP && age >= revealReady) phase(Phase.REVEAL);
    }

    /** 2초 동안 짝의 눈앞으로 내려옴(스스로 쏠 수 있게). */
    private void inFront() {
        Location eye = mate.getEyeLocation();
        Vector at = eye.toVector().add(eye.getDirection().multiply(2.2));
        Vector d = at.subtract(pos);
        double l = d.length();
        if (l > 0.9) d.multiply(0.9 / l);
        vel.copy(d.multiply(0.5));
        face(eye.toVector());
        if (hunger(true)) return;
        if (inPhase() >= REVEAL) {
            revealReady = age + REVEAL_REST;
            phase(Phase.PERCH);
        }
    }

    /**
     * 굶주림: 짝이 직접 처치하면 시계가 처음으로(줄어 푸르러짐). 단계(0 배부름, 1, 2 굶주림)마다 크기와 불, 타닥 소리 간격. 한도면 내리꽂힘(true).
     */
    private boolean hunger(boolean live) {
        int kills = game.players.get(mate).kills;
        long now = game.now();
        if (kills > lastKills) {
            lastKills = kills;
            fedAt = now;
            if (stage > 0) sound("hush", Sound.BLOCK_FIRE_EXTINGUISH, 1.8f, 10);
        }
        if (!live) fedAt = now; // 날아오는 동안은 굶지 않음(붙은 때부터 잼)
        int limit = cd(STARVE);
        long starve = now - fedAt;
        int st = starve < limit / 3 ? 0 : starve < limit * 2 / 3 ? 1 : 2;
        if (st != stage) {
            stage = st;
            state(st == 2 ? "hungry" : "");
            body.scale(SCALE[st], 8);
            if (st == 2) body.look.palette |= Look.PAL_DEEP_BLUSH;
            else body.look.palette &= ~Look.PAL_DEEP_BLUSH;
        }
        if (age >= nextCrackle) {
            sound("crackle", Sound.BLOCK_FIRE_AMBIENT, 1.2f, 12);
            nextCrackle = age + (st == 0 ? 40 : st == 1 ? 24 : 10) + game.random.nextInt(10);
        }
        if (live && starve >= limit - WARN && every(20)) game.hud.mark(mate, "g124", 12);
        if (live && starve >= limit) {
            state("dive");
            sound("crackle", Sound.BLOCK_FIRE_AMBIENT, 1.4f, 12);
            phase(Phase.DIVE);
            return true;
        }
        return false;
    }

    /** 짝의 머리로 내리꽂힘. */
    private void dive() {
        if (!standing(mate)) {
            vanish();
            return;
        }
        Vector head = mate.getEyeLocation().toVector().add(new Vector(0, 0.2, 0));
        Vector d = head.clone().subtract(pos);
        double l = d.length();
        face(head);
        if (l < 0.6 || inPhase() >= DIVE_MAX) {
            burst();
            return;
        }
        vel.copy(d.multiply(Math.min(DIVE_V * speed() / baseSpeed, l) / l));
    }

    /** 머리 위에서 터짐: 22 피해, 3초 화상. 개체는 사라졌다가 멀리서 다시 온다. */
    private void burst() {
        hit(mate, BURST_DMG, "cause_g124");
        burnOn = mate;
        burnLeft = BURN_T / BURN_EVERY;
        burnNext = age + BURN_EVERY;
        Location l = mate.getEyeLocation().add(0, 0.3, 0);
        game.shotFx.flame(l, 1.1f, 14, ShotFx.KEY);
        game.shotFx.pop(l);
        sound("burst", Sound.ENTITY_BLAZE_SHOOT, 1.2f, 24);
        vanish();
    }

    /** 작아져 사라짐(맞지 않음), GONE 틱 뒤 멀리서 다시. */
    private void vanish() {
        untargetable = true;
        body.scale(0.01f, 3);
        mark(null);
        hold();
        phase(Phase.GONE);
    }

    private void gone() {
        hold();
        if (inPhase() == 6) {
            // 짝(없으면 새 짝)에게서 RETURN_R 밖, 높이 8
            Player p = standing(mate) ? mate : pickMate();
            if (p != null) {
                double a = game.random.nextDouble() * Math.PI * 2;
                Location l = p.getLocation();
                pos.setX(l.getX() + Math.cos(a) * RETURN_R).setY(floor() + 8).setZ(l.getZ() + Math.sin(a) * RETURN_R);
                pos.setY(game.mobs.flow.floor(pos.getX(), pos.getZ()) + 8);
            }
        }
        if (inPhase() >= GONE) {
            untargetable = false;
            Player p = standing(mate) ? mate : pickMate();
            stage = -1;
            body.scale(SCALE[0], 6);
            state("");
            if (p != null) bind(p);
            phase(Phase.FLY);
        }
    }

    /** 화상: 터진 뒤 1초마다 3, 세 번(개체가 죽으면 멎음). */
    private void burnTick() {
        if (burnLeft <= 0 || age < burnNext) return;
        burnLeft--;
        burnNext = age + BURN_EVERY;
        if (burnOn == null || !standing(burnOn)) {
            burnLeft = 0;
            return;
        }
        hit(burnOn, BURN_DMG, "cause_g124_burn");
        if (game.shotFx.room(ShotFx.NORMAL)) game.shotFx.flame(burnOn.getLocation().add(0, 1.0, 0), 0.7f, 10, ShotFx.NORMAL);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY() + 0.1, pos.getZ(), 0.32});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        // 탄 한 발이면 꺼짐
        if ((t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER) && d.amount() > 0 && d.amount() < hp + 1)
            d = d.times((hp + 1) / d.amount());
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        leaveCorpse = false; // 불이 꺼짐(사체 없음)
        game.shotFx.flame(loc(), 0.6f, 8, ShotFx.NORMAL);
        sound("hush", Sound.BLOCK_FIRE_EXTINGUISH, 1.8f, 10);
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " stage=" + stage + (mate != null ? " mate=" + mate.getName() : "")
                + (mate != null ? " starve=" + (game.now() - fedAt) : "");
    }
}
