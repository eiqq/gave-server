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

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.PState;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G199 눈 찾는 거인 게이브(4막 34라운드, 정예 + 목표 압박 + 보상). 키 약 15블록 뼈대 거인(다리 둘 G129Kit.Legs, 팔 둘 부품)이 왼쪽 빈 눈구멍에서 검은
 * 진물을 쏟으며 트럭 둘레를 더듬더듬 헤맨다(0.08). 발밑 진물 못(부품, 세계 축)은 반경 6 에서 200틱마다 0.5 씩 14 까지: 안의 사람 0.5초마다 6 + 30% 느려짐,
 * 안의 값싼 게이브는 녹음(보상 없음), 트럭이 못 안이면 초당 1%. 손 쓸기(5초마다, 1초 전 손을 뒤로): 앞 8블록 부채꼴 30 + 날림. 집어 던지기(8초마다):
 * 6블록 안 한 사람을 집어 2초 눈구멍에 대 보고 15블록쯤 던짐(28).
 * 잃어버린 눈 알(따로 놓인 디스플레이, R 30~40)은 모두에게 금색 화살촉으로 보인다. 눈 알을 보고 돕기(좌클릭, HELPOBJ)로 들면 35% 느려지고 주무기가 잠기며
 * (WEAPONLOCK, 권총만) 눈 알이 품에서 눈을 뜬다. 든 사람이 쓰러지면 그 자리에 떨어진다. 든 채 거인 발치 5블록에 오면 거인이 무릎을 꿇고 눈을 끼운다:
 * 진물이 멎고 팀 전원 40 x 보상 배율, 거인은 돌아서 지평선으로 걸어 나가 사라진다(보상 0). 힘으로 잡으면 120. 빈 눈구멍 가장자리 x2.
 *
 * <p>팩 계약(tools/gabe/sp/g199.py): gabe/g199(shrink 0.2, 상태 "", "kneel"), g199_leg, g199_arm, g199_pool, g199_eye("", "held").
 * 소리 gurgle(96), step(96), sweep(48), throw(32), eye(96). 글귀 cause_g199, cause_g199_sweep, cause_g199_throw, notice_g199_eye, hint_g199.
 *
 * <p>도감과 다른 점: 눈 알 들기는 돕기 한 번(1.5초 채널 없음). 팀 보상은 서 있는 사람마다 addMoney(teamMoney 와 같은 몫).
 */
public final class G199 extends Gabe {

    static final double HOVER = 9.0, HIP_X = 1.2, HIP_Y = -2.4, LEG_L = 6.6, SHOULDER_X = 2.0, SHOULDER_Y = 2.4, HEAD_Y = 5.2, SOCKET_X = 0.9;
    static final double SPEED = 0.08, OOZE_R0 = 6, OOZE_R1 = 14, OOZE_STEP = 0.5, OOZE_DMG = 6, OOZE_SLOW = 0.3;
    static final double SWEEP_R = 8, SWEEP_DMG = 30, GRAB_R = 6, THROW_DMG = 28, GIVE_R = 5, CARRY_SLOW = 0.35, SOCKET_MUL = 2.0, EYE_PICK = 3.0;
    static final int SWEEP_CD = 100, SWEEP_TELL = 20, GRAB_CD = 160, HOLD_T = 40, OOZE_T = 200, KNEEL_T = 60, TEAM_PAY = 40;

    enum Phase { WANDER, SWEEP, HOLD, KNEEL, LEAVE }

    private Phase phase = Phase.WANDER;
    private int phaseAt, sweepAt, grabAt, wanderAt, oozeAt, truckAt;
    private G129Kit.Legs legs;
    private Body.Part armL, armR, pool;
    private double oozeR = OOZE_R0;
    private final Vector wander = new Vector(), eyeAt = new Vector(), leaveTo = new Vector();
    private ItemDisplay eye;
    private Player carrier, held;
    private boolean returned;
    private final Object hookKey = new Object();
    private final List<double[]> sph = new ArrayList<>(10);
    private int sphAge = -1;

    public G199(Game game, Species sp) {
        super(game, sp);
        radius = 1.8;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragKick = 0.2;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g199", 7f, 14f);
        body.viewRange(4f);
        legs = new G129Kit.Legs(this, "gabe/g199_leg", LEG_L, new double[] {HIP_X, -HIP_X}, HIP_Y, 0);
        legs.reach = 2.6;
        legs.lead = 2.6;
        legs.lift = 1.4;
        legs.stance = 0.4;
        legs.swing = 26;
        legs.land = this::landed;
        armL = body.attach("gabe/g199_arm");
        armR = body.attach("gabe/g199_arm");
        pool = body.attach("gabe/g199_pool");
        if (pool != null) pool.follow = false;
        arms(0, 0, 0);
        poolPose();
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        for (int t = 0; t < 30; t++) {
            double a = game.random.nextDouble() * Math.PI * 2, r = 30 + game.random.nextDouble() * 10;
            double x = Plaza.CX + 0.5 + Math.cos(a) * r, z = Plaza.CZ + 0.5 + Math.sin(a) * r;
            if (!game.mobs.flow.walkable(x, z)) continue;
            eyeAt.setX(x).setY(game.mobs.flow.floor(x, z) + 0.75).setZ(z);
            break;
        }
        LookState ls = new LookState();
        eye = game.world.spawn(new Location(game.world, eyeAt.getX(), eyeAt.getY(), eyeAt.getZ()), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(Body.item("gabe/g199_eye", "", ls, game.worldTime()));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(12, 12));
            d.setViewRange(3f);
            d.setTeleportDuration(2);
            d.setTransformationMatrix(new Matrix4f().rotateY((float) Math.PI));
        });
        game.players.addHelpHook(hookKey, (helper, mate) -> pick(helper));
        pickWander();
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private boolean fighting() {
        return game.state == Game.State.WAVE;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        Player near = nearest(400);
        mark(phase == Phase.LEAVE ? null : near);
        switch (phase) {
            case WANDER -> wander(near);
            case SWEEP -> sweep();
            case HOLD -> hold(near);
            case KNEEL -> kneel();
            case LEAVE -> leave();
        }
        legs.tick();
        eyeTick();
        if (!returned) ooze();
        if (age % 50 == 0 && !returned) sound("gurgle", Sound.BLOCK_LAVA_POP, 0.5f, 96);
    }

    private void pickWander() {
        Vector c = Truck.live() ? Truck.center() : new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5);
        double a = game.random.nextDouble() * Math.PI * 2, r = 6 + game.random.nextDouble() * 16;
        wander.setX(c.getX() + Math.cos(a) * r).setZ(c.getZ() + Math.sin(a) * r).setY(pos.getY());
        wanderAt = age + 300 + game.random.nextInt(200);
    }

    private void wander(Player near) {
        arms(0, 0, 0);
        Vector to = wander;
        if (carrier != null) to = carrier.getLocation().toVector().setY(pos.getY());
        if (age >= wanderAt || Math.hypot(wander.getX() - pos.getX(), wander.getZ() - pos.getZ()) < 2) pickWander();
        seek(to, speed());
        turnTo(to.clone().add(vel.clone().multiply(5)), 2f);
        pitch = 0;
        if (!fighting() || near == null) return;
        if (carrier != null && G129Kit.flat(carrier.getLocation(), pos) <= GIVE_R) {
            startKneel();
            return;
        }
        if (age >= grabAt && dist(near) <= GRAB_R + HOVER && G129Kit.flat(near.getLocation(), pos) <= GRAB_R && near != carrier && grab(near, 40)) {
            game.players.get(near).grabDetach = true;
            held = near;
            grabAt = age + cd(GRAB_CD);
            phase(Phase.HOLD);
            return;
        }
        if (age >= sweepAt && G129Kit.flat(near.getLocation(), pos) <= SWEEP_R) {
            sweepAt = age + cd(SWEEP_CD);
            G66Giant.faceFlat(this, near.getLocation().toVector());
            phase(Phase.SWEEP);
        }
    }

    /** 손 쓸기: 1초 뒤로 뺐다가 앞 부채꼴. */
    private void sweep() {
        hold();
        if (inPhase() < SWEEP_TELL) {
            arms(-50 * inPhase() / (float) SWEEP_TELL, 0, 0);
            return;
        }
        if (inPhase() == SWEEP_TELL) {
            arms(70, 0, 4);
            sound("sweep", Sound.ENTITY_RAVAGER_ATTACK, 0.6f, 48);
            Vector f = G129Kit.fwd(this);
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                Vector d = new Vector(l.getX() - pos.getX(), 0, l.getZ() - pos.getZ());
                double h = d.length();
                if (h > SWEEP_R || h < 1e-3 || d.multiply(1 / h).dot(f) < 0.5) continue;
                hit(q, SWEEP_DMG, "cause_g199_sweep");
                knock(q, d, 1.2, 0.5);
            }
            G129Kit.wave(this, G129Kit.ground(this, G129Kit.at(this, 0, 0, 4)), 1.0, 4, 6);
        }
        if (inPhase() > SWEEP_TELL + 15) phase(Phase.WANDER);
    }

    /** 집어 눈구멍에 대 봄 → 내던짐. */
    private void hold(Player near) {
        hold();
        if (held == null || game.players.grabbedBy(held) != this) {
            held = null;
            phase(Phase.WANDER);
            return;
        }
        arms(0, -110, 0);
        game.players.carry(held, G129Kit.at(this, SOCKET_X, HEAD_Y - 1.2, 2.6));
        if (inPhase() >= HOLD_T) {
            Player p = held;
            held = null;
            game.players.release(p);
            Vector out = G129Kit.left(this).multiply(game.random.nextBoolean() ? 1 : -1).add(G129Kit.fwd(this));
            knock(p, out, 1.6, 0.7);
            hit(p, THROW_DMG, "cause_g199_throw");
            sound("throw", Sound.ENTITY_IRON_GOLEM_ATTACK, 0.6f, 32);
            phase(Phase.WANDER);
        }
    }

    private void landed(int i, Vector foot) {
        G129Kit.stomp(this, foot, 96, 14, 0.8f);
        if (age % 3 == 0) G129Kit.soundAt(this, foot, "step", Sound.ENTITY_RAVAGER_STEP, 1f, 96);
        if (game.horizon != null) game.horizon.shake(0.6, 8);
        G129Kit.crush(this, foot, 2.0, null, 0);
    }

    /** 팔 자세: 앞으로 든 각(도, + 앞), 안으로 굽힌 각, 보간 틱. */
    private void arms(float lift, float in, int ticks) {
        for (int s = 0; s < 2; s++) {
            Body.Part a = s == 0 ? armL : armR;
            if (a == null) continue;
            float side = s == 0 ? 1 : -1;
            float l = lift, k = in;
            if (in != 0 && s == 1) k = 0;
            a.pose(new Matrix4f().translation(side * (float) SHOULDER_X, (float) SHOULDER_Y, 0f).rotateX((float) Math.toRadians(-(l + (k != 0 ? -k : 0))))
                .rotateZ((float) Math.toRadians(side * 8)), ticks);
        }
    }

    // ------------------------------------------------------------------ 진물

    private void poolPose() {
        if (pool == null) return;
        float s = (float) (oozeR / 1.5);
        pool.pose(new Matrix4f().translation(0f, (float) (-HOVER + 0.07), 0f).scale(s, 1, s), 20);
    }

    private void ooze() {
        if (fighting() && age - oozeAt >= OOZE_T && oozeR < OOZE_R1) {
            oozeAt = age;
            oozeR = Math.min(OOZE_R1, oozeR + OOZE_STEP);
            poolPose();
        }
        if (!fighting() || age % 10 != 0) return;
        double fy = pos.getY() - HOVER;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (G129Kit.flat(l, pos) > oozeR || Math.abs(l.getY() - fy) > 2) continue;
            hit(q, OOZE_DMG, "cause_g199");
            game.players.slow(q, OOZE_SLOW, 14, "g199ooze");
        }
        for (Gabe g : game.mobs.near(pos, oozeR + HOVER, o -> o != this && !o.big && !o.sp.is(Role.BOSS) && o.sp.cost <= 1 && !o.dead)) {
            if (G129Kit.flat(g.pos, pos) > oozeR) continue;
            g.noReward = true;
            g.damage(new Damage(g.hp + 10, DamageType.OTHER, null, g.pos.clone(), null, "g199", 0));
        }
        Truck t = Truck.get();
        if (t != null && t.alive() && Truck.dist(pos.getX(), pos.getZ()) < oozeR && age >= truckAt) {
            truckAt = age + 20;
            t.hitFrac(0.01, this, "cause_g199");
        }
    }

    // ------------------------------------------------------------------ 눈 알(HELPOBJ, WEAPONLOCK)

    private boolean pick(Player helper) {
        if (dead || returned || eye == null || carrier != null || !fighting()) return false;
        Location e = helper.getEyeLocation();
        Vector to = eyeAt.clone().subtract(e.toVector());
        double d = to.length();
        if (d > EYE_PICK + 0.8 || d < 1e-3 || to.multiply(1 / d).dot(e.getDirection()) < (d < 1.6 ? 0.3 : 0.85)) return false;
        carrier = helper;
        eye.setItemStack(Body.item("gabe/g199_eye", "held", new LookState(), game.worldTime()));
        game.hud.hint(helper, "hint_g199", 80);
        sound("gurgle", Sound.BLOCK_LAVA_POP, 0.5f, 96);
        return true;
    }

    private void eyeTick() {
        if (eye == null || !eye.isValid()) return;
        if (carrier != null) {
            PState s = game.players.get(carrier);
            if (!carrier.isOnline() || !s.standing()) {
                Location l = carrier.getLocation();
                eyeAt.setX(l.getX()).setY(game.mobs.flow.floor(l.getX(), l.getZ()) + 0.75).setZ(l.getZ());
                carrier = null;
                eye.setItemStack(Body.item("gabe/g199_eye", "", new LookState(), game.worldTime()));
            } else {
                Location l = carrier.getLocation();
                Vector f = l.getDirection().setY(0);
                if (f.lengthSquared() > 1e-4) f.normalize().multiply(0.7);
                eyeAt.setX(l.getX() + f.getX()).setY(l.getY() + 1.1).setZ(l.getZ() + f.getZ());
                if (age % 5 == 0) {
                    game.players.slow(carrier, CARRY_SLOW, 12, "g199eye");
                    game.guns.lockPrimary(carrier, 12);
                }
            }
        }
        if (age % 2 == 0) eye.teleport(new Location(game.world, eyeAt.getX(), eyeAt.getY() - 0.7, eyeAt.getZ()));
        if (age % 40 == 0 && !returned) game.hud.point(null, eyeAt.clone(), 45);
    }

    private void startKneel() {
        Player c = carrier;
        carrier = null;
        returned = true;
        phase(Phase.KNEEL);
        state("kneel");
        body.look.open = true;
        hold();
        if (pool != null) pool.pose(new Matrix4f().translation(0f, (float) (-HOVER + 0.07), 0f).scale(0.001f), 30);
        if (eye != null) eye.remove();
        eye = null;
        game.players.removeHelpHook(hookKey);
        sound("eye", Sound.ENTITY_WARDEN_ROAR, 0.6f, 96);
        game.hud.notice("notice_g199_eye", sp, 100);
        int pay = (int) Math.max(1, Math.round(TEAM_PAY * game.director.rewardMul()));
        for (Player q : game.mobs.standing()) game.players.addMoney(q, pay);
        if (c != null) game.guns.lockPrimary(c, 0);
    }

    private void kneel() {
        hold();
        if (inPhase() < KNEEL_T) return;
        state("");
        Vector c = new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5);
        Vector out = pos.clone().subtract(c).setY(0);
        if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
        out.normalize().multiply(90);
        leaveTo.copy(c.add(out)).setY(pos.getY());
        noHold = true;
        persist = true;
        phase(Phase.LEAVE);
    }

    private void leave() {
        seek(leaveTo, Math.max(speed(), 0.12));
        G66Giant.faceFlat(this, leaveTo);
        if (Math.hypot(leaveTo.getX() - pos.getX(), leaveTo.getZ() - pos.getZ()) < 3 || inPhase() > 1400) {
            noReward = true;
            remove();
        }
    }

    // ------------------------------------------------------------------ 맞음, 치움

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(G129Kit.at(this, 0, HEAD_Y, 0.3), 2.1);                 // 0 머리
        add(G129Kit.at(this, SOCKET_X, HEAD_Y + 0.55, 1.9), 0.7);   // 1 빈 눈구멍
        add(G129Kit.at(this, 0, 1.0, 0), 1.6);                      // 2 몸통
        add(G129Kit.at(this, 0, -1.6, 0), 1.4);                     // 3 골반
        if (legs != null) {
            for (int i = 0; i < 2; i++) {
                add(legs.knee(i), 0.8);
                add(legs.along(i, 0.82), 0.6);
            }
        }
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.at() != null && !returned) {
            List<double[]> s = spheres();
            double[] c = s.get(1);
            double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
            if (dd < 0.35) return super.damage(d.times(SOCKET_MUL));
        }
        return super.damage(d);
    }

    private void cleanup() {
        game.players.removeHelpHook(hookKey);
        if (eye != null && eye.isValid()) eye.remove();
        eye = null;
        if (carrier != null) game.guns.lockPrimary(carrier, 0);
        carrier = null;
    }

    @Override
    public void die(Damage d) {
        cleanup();
        super.die(d);
    }

    @Override
    public void remove() {
        cleanup();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " ooze=" + String.format("%.1f", oozeR) + " carrier=" + (carrier == null ? "-" : carrier.getName())
            + " returned=" + returned + " eye=" + String.format("%.0f,%.0f", eyeAt.getX(), eyeAt.getZ());
    }
}
