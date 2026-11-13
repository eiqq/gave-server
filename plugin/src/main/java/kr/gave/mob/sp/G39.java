package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.PState;

/**
 * G39 사슬 늘어뜨린 게이브(원거리, 방해, 먹는 종).
 *
 * <p>도감: 뒤집힌 얼굴이 12~15블록 높이에 떠서 무리 위를 천천히 맴돌고, 입에서 늘어진 쇠사슬 끝의 주먹만 한 얼굴이 진자처럼 크게 흔들린다.
 * 표적 위에서는 6블록까지 내려온다. 끝 얼굴이 사람 머리에 닿으면 물고 초당 2블록 감아올린다(매달린 동안 사격 불가, 신음이 위치 소리로 울림).
 * 끝까지 올라가면 입속으로 사라져 갇힌다(이 개체를 잡으면 상점 앞 부활). 8블록 안은 화면이 일렁인다. 사슬 중간 마디를 쏘면 끊겨 떨어지고
 * (높을수록 떨어지는 피해가 큼), 매달린 사람은 연타로 빠져나오거나 동료가 아래에서 돕기로 떼어 낼 수 있다(둘 다 그 높이에서 떨어짐).
 *
 * <p>바꾼 것: 화면 일렁임은 렌즈가 없어 시점을 조금씩 흔드는 것으로(Guns.kick), 매달린 사람 발밑 돕기는 '감기 멈춤' 대신 엔진 공통 떼기
 * (떼어져 떨어짐)로 했다. 맵이 평지라 '지붕 밑으로 피하기'는 없다. 갇힌 사람이 있는 채 웨이브 끝 정리로 치워지면 다음 웨이브 종 하나를 아이콘으로 알린다.
 *
 * <p>몸: 닻 {@code gave:gabe/g39}(상태 {@code ""}, {@code "reel"}) + 부품 {@code gave:gabe/g39_chain}(세계 축, 상태 c3..c13 = 길이) +
 * 부품 {@code gave:gabe/g39_hook}(세계 축, 상태 {@code ""}, {@code "bite"}). 소리 gurgle(32), reel(24), bite(24), moan(32), snap(24),
 * gulp(24), murmur(32). 글귀 {@code cause_g39}, {@code cause_g39_fall}, {@code notice_g39_ate}, {@code notice_g39_murmur}.
 */
public final class G39 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.075;
    /** 떠 있는 높이(바닥 위): 맴돌 때 12~15, 표적 위 6. */
    static final double CRUISE_LO = 12, CRUISE_HI = 15, ATTACK_H = 6;
    static final double HEAD_R = 1.2, HOOK_R = 0.26, LINK_R = 0.16; // 2026-11-12 거대화(머리 2.4 배)
    /** 쉴 때 끝 얼굴 높이(바닥 위 = 선 사람 머리), 무는 거리, 물 때 피해. */
    static final double HOOK_H = 1.72, BITE_R = 0.62, BITE_DMG = 5;
    /** 표적으로 삼는 수평 거리, 놓치는 거리. */
    static final double NOTICE_R = 12, LOSE_R = 20;
    /** 감는 빠르기(초당 2블록), 사슬 길이를 맞추는 빠르기, 삼키는 길이. */
    static final double REEL_V = 0.1, LEN_V = 0.22, SWALLOW_LEN = 1.2;
    static final int ESCAPE = 12, GULP_TICKS = 40, CUT_TICKS = 200;
    /** 사슬 체력(최대 체력의 몫). */
    static final double CHAIN_HP = 0.35;
    /** 떨어지는 피해: 이 높이부터, 블록당, 위끝. */
    static final double FALL_FROM = 2.5, FALL_PER = 1.5, FALL_MAX = 12;
    /** 얼굴 숙임(도), 입(사슬이 나오는 자리, 얼굴 기준 위, 앞). */
    static final float PITCH = 62f;
    static final double MOUTH_Y = 0.6, MOUTH_Z = 1.26;
    static final int[] LENGTHS = {3, 5, 7, 9, 11, 13};
    /** 화면 일렁임 거리(끝 얼굴에서). */
    static final double WOBBLE_R = 8;

    enum Phase { CRUISE, HUNT, REEL, GULP, CUT }

    private Phase phase = Phase.CRUISE;
    private int phaseAt, nextGurgle, nextMoan, nextMurmur;
    private double cruiseH, len = 9, amp, swingT, phi, th, chainHp;
    private float spin;
    private Player prey, held;
    private boolean cut, swallowing;
    private int cutUntil, ate;
    private Body.Part chain, hook;
    private final Vector mouth = new Vector(), hookPos = new Vector(), dir = new Vector(0, -1, 0);

    public G39(Game game, Species sp) {
        super(game, sp);
        radius = HEAD_R;
        flying = true;
        noPush = true;
        baseSpeed = SPEED;
        cruiseH = CRUISE_LO + game.random.nextDouble() * (CRUISE_HI - CRUISE_LO);
        hover = cruiseH;
        pitch = PITCH;
    }

    @Override
    protected void build() {
        // 사슬이 13블록 아래까지 늘어지고 옆으로 흔들리므로 컬링 상자를 크게
        body = Body.of(this, "gabe/g39", 12f, 32f);
        chain = body.attach("gabe/g39_chain");
        hook = body.attach("gabe/g39_hook");
        if (chain != null) chain.follow = false;
        if (hook != null) hook.follow = false;
    }

    @Override
    protected void spawned() {
        phi = game.random.nextDouble() * Math.PI * 2;
        swingT = game.random.nextDouble() * Math.PI * 2;
        nextGurgle = age + 20 + game.random.nextInt(40);
        chainHp = Math.max(1, maxHp * CHAIN_HP);
        geometry();
        len = Math.max(2, mouth.getY() - (floor() + HOOK_H));
        geometry();
        poseParts(0);
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
        if (age == 1) chainHp = Math.max(1, maxHp * CHAIN_HP); // 감독이 체력 배율을 곱한 뒤
        if (prey != null && !standing(prey)) prey = null;
        if (held != null && (!held.isOnline() || game.players.grabbedBy(held) != this)) dropped();
        switch (phase) {
            case CRUISE -> cruise();
            case HUNT -> hunt();
            case REEL -> reel();
            case GULP -> gulp();
            case CUT -> cutTick();
        }
        pitch = PITCH;
        swing();
        geometry();
        if (!cut) {
            if (held == null && phase != Phase.GULP) biteCheck();
            if ((age & 1) == 0) poseParts(2);
        }
        ambient();
    }

    /** 수평으로 goal 쪽, 높이는 바닥 + h 로. */
    private void fly(Vector goal, double h, double v) {
        Vector d = goal.clone().subtract(pos).setY(0);
        double l = d.length();
        if (l < 0.05) d.zero();
        else d.multiply(Math.min(v, l) / l);
        double dy = floor() + h - pos.getY();
        d.setY(Math.max(-0.09, Math.min(0.09, dy * 0.12)));
        vel.copy(d);
        if (l > 0.3) yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
    }

    /** 무리 위를 천천히 맴돎: 가장 가까운 사람 쪽으로. 가까워지면 그 사람을 찍고 내려감. */
    private void cruise() {
        Player p = nearest(400); // 게이브는 72~84블록에서 나오므로 반경을 넉넉히(전에는 멀면 사람을 못 찾아 외곽에서 멈춤, 2026-11-11)
        if (p == null) {
            vel.zero();
            vel.setY(Math.max(-0.09, Math.min(0.09, (floor() + cruiseH - pos.getY()) * 0.12)));
            return;
        }
        fly(p.getLocation().toVector(), cruiseH, speed());
        phi += 0.012;
        if (!cut && flat(p) <= NOTICE_R) {
            prey = p;
            mark(p);
            phase(Phase.HUNT);
        }
    }

    /** 표적 위로 가서 6블록까지 내려옴. 진자 면을 표적 쪽으로 돌린다. */
    private void hunt() {
        if (prey == null || flat(prey) > LOSE_R || cut) {
            mark(null);
            prey = null;
            phase(Phase.CRUISE);
            return;
        }
        Location l = prey.getLocation();
        fly(l.toVector(), ATTACK_H, speed() * 1.2);
        double dx = l.getX() - mouth.getX(), dz = l.getZ() - mouth.getZ();
        if (dx * dx + dz * dz > 0.6 * 0.6) {
            double want = Math.atan2(dz, dx), d = want - phi;
            d = Math.atan2(Math.sin(d), Math.cos(d));
            // 진자 면은 지름이라 반 바퀴 차이는 같은 면
            if (d > Math.PI / 2) d -= Math.PI;
            if (d < -Math.PI / 2) d += Math.PI;
            phi += Math.max(-0.06, Math.min(0.06, d));
        }
    }

    /** 문 사람을 초당 2블록 감아올림. 끝까지 오면 삼킨다. */
    private void reel() {
        Player p = held;
        if (p == null) {
            phase(Phase.CRUISE);
            return;
        }
        vel.zero();
        vel.setY(Math.max(-0.05, Math.min(0.05, (floor() + ATTACK_H + 2 - pos.getY()) * 0.1)));
        len = Math.max(SWALLOW_LEN, len - REEL_V);
        // 끝 얼굴이 머리를 물고 있음: 좌석(사람 발 + 0.6) = 끝 얼굴 - 1.12
        game.players.carry(p, new Vector(hookPos.getX(), hookPos.getY() - 1.12, hookPos.getZ()));
        if (inPhase() % 8 == 0) sound("reel", Sound.BLOCK_CHAIN_STEP, 1.4f, 24);
        if (age >= nextMoan) {
            nextMoan = age + 26 + game.random.nextInt(10);
            soundAt(hookPos, "moan", Sound.ENTITY_VILLAGER_HURT, 0.6f, 32);
        }
        if (len <= SWALLOW_LEN + 1e-6) swallow(p);
    }

    private void swallow(Player p) {
        held = null;
        swallowing = true;
        try {
            game.players.trap(p, this);
        } finally {
            swallowing = false;
        }
        ate++;
        sound("gulp", Sound.ENTITY_GENERIC_DRINK, 0.6f, 24);
        game.hud.notice("notice_g39_ate", sp, 60);
        body.squash(1.2f, 0.85f, 1.2f, 3);
        if (hook != null) hook.state("");
        state("");
        mark(null);
        prey = null;
        nextMurmur = age + 120;
        phase(Phase.GULP);
    }

    private void gulp() {
        vel.zero();
        if (inPhase() == 6) body.squash(1f, 1f, 1f, 6);
        if (inPhase() >= GULP_TICKS) phase(Phase.CRUISE);
    }

    /** 사슬이 끊긴 동안: 높이 올라가 기다렸다가 사슬을 다시 늘어뜨림. */
    private void cutTick() {
        Player p = nearest(96);
        if (p != null) fly(p.getLocation().toVector().add(toward(p).multiply(-8)), cruiseH, speed());
        else vel.zero();
        if (age >= cutUntil) {
            cut = false;
            len = 1.0;
            amp = 0;
            chainHp = Math.max(1, maxHp * CHAIN_HP);
            if (chain != null) chain.model("gabe/g39_chain");
            if (hook != null) {
                hook.model("gabe/g39_hook");
                hook.state("");
            }
            phase(Phase.CRUISE);
        }
    }

    /** 진자: 사슬 길이를 맞추고 각도를 굴린다. */
    private void swing() {
        if (cut) return;
        if (phase != Phase.REEL && phase != Phase.GULP) {
            double rest = Math.max(1.0, mouth.getY() - (floor() + HOOK_H));
            len += Math.max(-LEN_V, Math.min(LEN_V, rest - len));
        } else if (phase == Phase.GULP) len = SWALLOW_LEN;
        double period = 96 * Math.sqrt(Math.max(2.0, len) / 10.0);
        swingT += Math.PI * 2 / period;
        double reach = phase == Phase.HUNT ? 2.6 : 3.4;
        double want = held != null || phase == Phase.GULP ? 0 : Math.min(Math.toRadians(38), Math.asin(Math.min(0.95, reach / Math.max(2.8, len))));
        amp += (want - amp) * (held != null ? 0.12 : 0.06);
        th = amp * Math.sin(swingT);
        spin += phase == Phase.REEL ? 4f : 13f;
        if (spin > 360f) spin -= 360f;
    }

    /** 입 자리, 사슬 방향, 끝 얼굴 자리(세계 좌표). */
    private void geometry() {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double up = MOUTH_Y * Math.cos(pr) - MOUTH_Z * Math.sin(pr), fw = MOUTH_Y * Math.sin(pr) + MOUTH_Z * Math.cos(pr);
        mouth.setX(pos.getX() - Math.sin(yr) * fw).setY(pos.getY() + up).setZ(pos.getZ() + Math.cos(yr) * fw);
        double s = Math.sin(th), c = Math.cos(th);
        dir.setX(s * Math.cos(phi)).setY(-c).setZ(s * Math.sin(phi));
        hookPos.setX(mouth.getX() + dir.getX() * len).setY(mouth.getY() + dir.getY() * len).setZ(mouth.getZ() + dir.getZ() * len);
    }

    /** 사슬과 끝 얼굴 부품 자세(몸 중심 기준 세계 축). */
    private void poseParts(int ticks) {
        float mx = (float) (mouth.getX() - pos.getX()), my = (float) (mouth.getY() - pos.getY()), mz = (float) (mouth.getZ() - pos.getZ());
        if (chain != null) {
            int L = LENGTHS[LENGTHS.length - 1];
            for (int v : LENGTHS) {
                if (v >= len - 1e-6) {
                    L = v;
                    break;
                }
            }
            chain.state("c" + L);
            chain.pose(new Matrix4f().translate(mx, my, mz).rotate((float) th, (float) -Math.sin(phi), 0f, (float) Math.cos(phi))
                    .translate(0f, (float) (-len / 2), 0f).scale(1f, (float) (len / L), 1f), ticks);
        }
        if (hook != null) {
            Matrix4f m = new Matrix4f().translate((float) (hookPos.getX() - pos.getX()), (float) (hookPos.getY() - pos.getY()),
                    (float) (hookPos.getZ() - pos.getZ())).rotateY((float) Math.toRadians(spin));
            // 사슬 끝에 매달림: 정수리 고리(얼굴 위 0.4)가 사슬 끝에 오게 내림
            hook.pose(m.translate(0f, -0.3f, 0f), ticks);
        }
    }

    /** 끝 얼굴이 머리에 닿은 사람을 문다. */
    private void biteCheck() {
        for (Player p : game.mobs.standing()) {
            Location e = p.getEyeLocation();
            double dx = e.getX() - hookPos.getX(), dy = e.getY() - hookPos.getY(), dz = e.getZ() - hookPos.getZ();
            if (dx * dx + dy * dy + dz * dz > BITE_R * BITE_R) continue;
            if (!grab(p, ESCAPE)) continue;
            PState s = game.players.get(p);
            s.grabPin = false; // 좌석이 끌어올림
            held = p;
            hit(p, BITE_DMG, "cause_g39");
            if (held != p || game.players.grabbedBy(p) != this) {
                held = null;
                return;
            }
            prey = p;
            mark(p);
            if (hook != null) hook.state("bite");
            state("reel");
            soundAt(hookPos, "bite", Sound.ENTITY_SLIME_ATTACK, 0.8f, 24);
            nextMoan = age + 10;
            phase(Phase.REEL);
            return;
        }
    }

    /** 꼴꼴, 화면 일렁임, 갇힌 사람이 있으면 중얼거림. */
    private void ambient() {
        if (age >= nextGurgle) {
            nextGurgle = age + 50 + game.random.nextInt(30);
            sound("gurgle", Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.7f, 32);
        }
        if (ate > 0 && age >= nextMurmur) {
            nextMurmur = age + 200 + game.random.nextInt(60);
            if (!game.players.trappedIn(this).isEmpty()) sound("murmur", Sound.ENTITY_DROWNED_AMBIENT_WATER, 0.6f, 32);
        }
        if (!cut && every(4)) {
            for (Player p : game.mobs.standing()) {
                if (p == held) continue;
                double d2 = p.getEyeLocation().toVector().distanceSquared(hookPos);
                if (d2 > WOBBLE_R * WOBBLE_R) continue;
                double k = 1.0 - Math.sqrt(d2) / WOBBLE_R;
                Guns.kick(p, (float) (Math.sin(age * 0.21 + id) * 0.5 * k), (float) (Math.cos(age * 0.17 + id) * 0.35 * k));
            }
        }
    }

    // ------------------------------------------------------------------ 놓침, 끊김

    /** 매달린 사람이 풀려남(탈출, 동료 떼기, 쓰러짐, 이 개체 죽음): 그 높이에서 떨어진다. */
    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim != held || swallowing) return;
        double h = hookPos.getY() - HOOK_H - game.mobs.flow.floor(hookPos.getX(), hookPos.getZ());
        dropped();
        double dmg = Math.min(FALL_MAX, (h - FALL_FROM) * FALL_PER);
        if (dmg <= 0) return;
        // 쓰러지는 길(Players.down) 안에서도 불리므로 피해는 다음 틱에: 그때 서 있을 때만
        Bukkit.getScheduler().runTask(game.plugin, () -> {
            if (victim.isOnline() && game.players.get(victim).standing()) game.players.hurt(victim, dmg, this, "cause_g39_fall");
        });
    }

    private void dropped() {
        held = null;
        if (hook != null) hook.state("");
        if (!dead) {
            state("");
            mark(null);
            prey = null;
            if (phase == Phase.REEL) phase(Phase.CRUISE);
        }
    }

    /** 사슬이 끊김: 매달린 사람을 놓치고 끝 얼굴이 떨어진다. 한동안 낚지 못한다. */
    private void cutChain() {
        if (cut) return;
        cut = true;
        cutUntil = age + CUT_TICKS;
        soundAt(hookPos, "snap", Sound.BLOCK_CHAIN_BREAK, 0.8f, 24);
        if (held != null) game.players.release(held);
        // 끝 얼굴이 바닥으로 떨어지는 연출(효과 디스플레이, 텍스처 모델)
        Location at = new Location(game.world, hookPos.getX(), hookPos.getY(), hookPos.getZ());
        ItemDisplay d = game.shotFx.spawn(at, "fx_quad", 0, 0, new Matrix4f().rotateY((float) Math.toRadians(spin)), Display.Billboard.FIXED, false,
                34, ShotFx.NORMAL);
        if (d != null) {
            d.setItemStack(Body.item("gabe/g39_hook", "", new LookState(), game.worldTime()));
            d.setBrightness(Body.LIGHT);
            Location ground = at.clone();
            ground.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + 0.2);
            game.shotFx.drift(d, ground, (int) Math.max(4, Math.min(20, (at.getY() - ground.getY()) * 1.6)));
        }
        if (chain != null) chain.model(Body.EMPTY);
        if (hook != null) hook.model(Body.EMPTY);
        mark(null);
        prey = null;
        state("");
        phase(Phase.CUT);
    }

    // ------------------------------------------------------------------ 기하, 맞음, 죽음

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    private void soundAt(Vector at, String name, Sound fb, float fbPitch, double range) {
        if (game.director.silent()) return;
        Props.soundAround(new Location(game.world, at.getX(), at.getY(), at.getZ()), "gabe." + sp.key() + "." + name, fb, 1f,
                0.94f + game.random.nextFloat() * 0.12f, fbPitch, range, -1);
    }

    /** 점 p 에서 선분 a-b 까지 거리. */
    private static double segDist(Vector p, Vector a, Vector b) {
        double abx = b.getX() - a.getX(), aby = b.getY() - a.getY(), abz = b.getZ() - a.getZ();
        double l2 = abx * abx + aby * aby + abz * abz;
        double t = l2 < 1e-9 ? 0 : ((p.getX() - a.getX()) * abx + (p.getY() - a.getY()) * aby + (p.getZ() - a.getZ()) * abz) / l2;
        t = Math.max(0, Math.min(1, t));
        double dx = a.getX() + abx * t - p.getX(), dy = a.getY() + aby * t - p.getY(), dz = a.getZ() + abz * t - p.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(12);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), HEAD_R});
        if (cut) return out;
        out.add(new double[] {hookPos.getX(), hookPos.getY(), hookPos.getZ(), HOOK_R});
        // 사슬 마디(1.25 블록마다, 머리 바로 밑과 끝 얼굴 바로 위는 뺌)
        for (double t = 1.0; t < len - 0.6 && out.size() < 13; t += 1.25) {
            out.add(new double[] {mouth.getX() + dir.getX() * t, mouth.getY() + dir.getY() * t, mouth.getZ() + dir.getZ() * t, LINK_R});
        }
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (gun && !cut && d.at() != null) {
            // 맞은 점이 머리, 끝 얼굴이 아니라 사슬이면 사슬 체력이 깎인다(본체는 안 다침)
            double dh = d.at().distance(pos), dk = d.at().distance(hookPos);
            if (dh > HEAD_R + 0.4 && dk > HOOK_R + 0.4 && segDist(d.at(), mouth, hookPos) < 0.8) {
                chainHp -= d.amount();
                game.mobs.onHurt(this, d, 0);
                if (chainHp <= 0) cutChain();
                return true;
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 갇힌 사람이 있는 채 웨이브 끝 정리로 치워짐: 쉬는 시간에 중얼거림과 다음 웨이브 종 하나(아이콘)
        if (ate > 0 && (d == null || d.type() == DamageType.OTHER && d.by() == null) && !game.players.trappedIn(this).isEmpty()) murmurLater();
        super.die(d);
    }

    private void murmurLater() {
        Location at = loc();
        Bukkit.getScheduler().runTaskLater(game.plugin, () -> {
            if (game.state != Game.State.BREAK) return;
            Director.Plan next = game.director.nextPlan();
            Species hint = null;
            if (next != null && next.counts != null && !next.counts.isEmpty()) {
                List<Species> list = new ArrayList<>(next.counts.keySet());
                hint = list.get(game.random.nextInt(list.size()));
            }
            Props.soundAround(at, "gabe.g39.murmur", Sound.ENTITY_DROWNED_AMBIENT_WATER, 1f, 1f, 0.6f, 32, -1);
            if (hint != null) game.hud.notice("notice_g39_murmur", hint, 80);
            else game.hud.notice("notice_g39_murmur", 80);
        }, 60);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + String.format(java.util.Locale.ROOT, " len=%.1f h=%.1f", len, pos.getY() - floor())
                + (cut ? " cut" : "") + (held != null ? " held=" + held.getName() : "") + " ate=" + ate;
    }
}
