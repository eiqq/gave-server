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

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G98 물러나는 초롱 게이브(보상, 함정, 2막, 미리 깔림). 트럭 둘레 테두리 바로 밖(R 14~18)에서 금빛 초롱이 까딱이며 짤랑인다. 사람이 4블록 안으로
 * 오면 같은 빠르기로 바깥으로 물러나 늘 3블록 앞에 있고, 쫓는 사람이 트럭에서 28블록 넘게 나왔거나 8초 넘게 쫓았으면 초롱이 뚝 멈추고 그 발밑에서
 * 땅속을 따라오던 큰 입이 솟는다. 아무도 안 쫓으면 6초마다 트럭 둘레로 들어와 가장 가까운 사람을 초롱으로 후려쳐 바깥으로 낚아챈다.
 *
 * <p>도감: 솟는 입 반경 2 안 28 과 1.5초 물림(붙잡힘, 초당 6, 연타나 동료가 솟은 입을 쏘면 풀림). 초롱 후려치기 9 와 바깥으로 2블록.
 * 낚싯대(체력 40, 가늘어 겨눠야 맞음)를 끊으면 초롱이 떨어져 팀 전원 25원. 땅속 두둑은 피해 절반, 솟은 입은 두 배. 입이 솟을 때 초롱 얼굴이
 * 처음으로 눈을 뜬다.
 *
 * <p>바꾼 것: '물러날 때마다 밝아짐'은 몸 밝기(10 고정 규칙) 대신 낚싯대 휘청과 짤랑이 빨라지는 것으로. 낚싯대 체력은 몸 최대 체력의 1/4(도감
 * 40/160, 감독 체력 배율을 따름). 낚싯대가 끊긴 뒤에는 초롱 없이 땅속 입만 남아 가장 가까운 사람 밑으로 와서 솟는다(그것을 잡으면 12원).
 * 솟은 입을 동료가 쏴 몸 최대 체력의 15% 를 깎으면 물린 사람이 풀린다.
 *
 * <p>팩 계약(tools/gabe/sp/g98.py): 닻 gave:gabe/g98(상태 "lure", "still"), 부품 gave:gabe/g98_mouth(상태 "mound", "bite", 세계 축, 원점 = 바닥).
 * 소리 jingle(24), rise(32), bite(24), slap(16), snap(24). 글귀 cause_g98, cause_g98_slap, notice_g98_cut.
 */
public final class G98 extends Gabe implements Detachable {

    static final double HOVER = 1.6, BOB_V = 0.08, LURE_V = 0.3;
    /** 쫓는다고 보는 거리, 앞서 있는 거리, 놓는 거리. */
    static final double CHASE_R = 4, LEAD = 3, LOSE_R = 8;
    /** 놓이는 고리(가운데에서), 입이 솟는 조건(쫓는 사람이 가운데에서 이 블록 넘게, 또는 쫓은 틱). */
    static final double HOME_IN = 14, HOME_OUT = 18, SPRING_R = 28;
    static final int SPRING_T = 160, RACE_T = 8, BITE_T = 30, LURE_EVERY = 120, LURE_MAX = 80, JINGLE = 34;
    static final double RISE_R = 2, RISE_DMG = 28, BITE_DPS = 6, SLAP = 9, SLAP_V = 0.55, SLAP_R = 1.6;
    static final int ESCAPE = 6;
    /** 입 두둑은 초롱 뒤 이 블록(낚싯대가 박힌 곳). */
    static final double MOUTH_BACK = 1.5;
    static final double ROD_FRAC = 40.0 / 160.0, ROD_R = 0.2, FREE_FRAC = 0.15, TEAM_WON = 25;

    enum Phase { HOME, FLEE, RACE, BITE, LURE, SUNK }

    private Phase phase = Phase.HOME;
    private int phaseAt, nextLure, nextJingle, retreats;
    private Player chaser, held, prey;
    private double chaseT, rodHp = -1, homeA, homeR, mouthTaken;
    private boolean cut, risen;
    private final Vector mouth = new Vector(), home = new Vector(), lastChaser = new Vector();
    private Body.Part maw;

    public G98(Game game, Species sp) {
        super(game, sp);
        radius = 0.32;
        hover = HOVER;
        ghost = true; // 입이 땅속을 따라오고 초롱은 떠 있음
        noPush = true;
        baseSpeed = BOB_V;
        state("lure");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g98", 6f, 4f);
        maw = body.attach("gabe/g98_mouth");
        if (maw != null) {
            maw.follow = false;
            maw.state("mound");
        }
    }

    @Override
    protected void spawned() {
        homeA = game.random.nextDouble() * Math.PI * 2;
        homeR = HOME_IN + game.random.nextDouble() * (HOME_OUT - HOME_IN);
        nextLure = age + 60 + game.random.nextInt(60);
        if (!game.director.cageSpawning()) {
            homePoint();
            pos.setX(home.getX()).setZ(home.getZ());
            pos.setY(game.mobs.flow.floor(home.getX(), home.getZ()) + hover);
            Player p = nearest(400);
            if (p != null) watch(p);
        }
        mouth.copy(pos).setY(floor());
        poseMouth(0);
    }

    private Vector center() {
        if (Truck.live()) return Truck.center();
        return new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
    }

    private double fromCenter(Vector at) {
        Vector c = center();
        return Math.hypot(at.getX() - c.getX(), at.getZ() - c.getZ());
    }

    private void homePoint() {
        Vector c = center();
        home.setX(c.getX() + Math.cos(homeA) * homeR).setY(c.getY()).setZ(c.getZ() + Math.sin(homeA) * homeR);
    }

    /** 표적을 세우되(웨이브 끝 규칙) 실눈은 입이 솟을 때만 뜬다. */
    private void watch(Player p) {
        mark(p);
        body.look.open = risen;
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
        if (rodHp < 0) rodHp = Math.max(1, maxHp * ROD_FRAC);
        if (held != null && (!held.isOnline() || game.players.grabbedBy(held) != this)) held = null;
        if (every(40)) {
            Player p = nearest(400);
            if (p != null && target != p) watch(p);
        }
        switch (phase) {
            case HOME -> homeTick();
            case FLEE -> flee();
            case RACE -> race();
            case BITE -> biteTick();
            case LURE -> lureTick();
            case SUNK -> sunk();
        }
        if (phase != Phase.RACE && phase != Phase.BITE) followMouth();
        if ((age & 1) == 0 && phase != Phase.BITE) poseMouth(2);
        if (!cut && age >= nextJingle) {
            sound("jingle", Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f, 24);
            nextJingle = age + Math.max(8, JINGLE - retreats * 2) + game.random.nextInt(8);
        }
    }

    /** 고리 위 제자리에서 까딱이며 기다림. 다가오는 사람에게서는 물러나고, 6초마다 안으로 들어와 낚아챔. */
    private void homeTick() {
        state("lure");
        homeA += 0.002;
        homePoint();
        Vector want = home.clone();
        want.setY(pos.getY());
        Vector to = want.subtract(pos).setY(0);
        double l = to.length();
        vel.copy(l < 0.2 ? new Vector() : to.multiply(Math.min(BOB_V * 2, l) / l));
        Player p = nearest(400);
        if (p != null) face(chest(p));
        Player c = closeChaser(CHASE_R);
        if (c != null) {
            chaser = c;
            chaseT = 0;
            lastChaser.copy(c.getLocation().toVector());
            phase(Phase.FLEE);
            return;
        }
        if (age >= nextLure && p != null && fromCenter(p.getLocation().toVector()) < HOME_IN + 6) {
            prey = p;
            phase(Phase.LURE);
        }
    }

    private Player closeChaser(double r) {
        Player best = null;
        double bd = r;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double d = Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        return best;
    }

    /** 쫓는 사람에게서 같은 빠르기로 바깥으로 물러남(늘 3블록 앞). 너무 멀리 나왔거나 오래 쫓았으면 입이 솟으러 감. */
    private void flee() {
        state("lure");
        Player c = chaser;
        if (!standing(c)) {
            chaser = null;
            phase(Phase.HOME);
            return;
        }
        Location l = c.getLocation();
        Vector cv = l.toVector();
        double moved = Math.hypot(cv.getX() - lastChaser.getX(), cv.getZ() - lastChaser.getZ());
        lastChaser.copy(cv);
        double d = Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
        if (d > LOSE_R) {
            chaser = null;
            phase(Phase.HOME);
            return;
        }
        chaseT++;
        face(chest(c));
        Vector away = pos.clone().subtract(cv).setY(0);
        if (away.lengthSquared() < 1e-4) away = pos.clone().subtract(center()).setY(0);
        away.normalize();
        Vector out = pos.clone().subtract(center()).setY(0);
        if (out.lengthSquared() > 1e-4) away.add(out.normalize().multiply(0.6)).normalize();
        double v = Math.max(0.08, Math.min(0.42, moved + (LEAD - d) * 0.15));
        vel.copy(away.multiply(v));
        if (moved > 0.05 && d < LEAD + 0.5 && inPhase() % 20 == 0) {
            retreats++;
            body.look.rate = 1.0 + Math.min(1.5, retreats * 0.12);
        }
        if (fromCenter(cv) > SPRING_R || chaseT > cd(SPRING_T)) {
            prey = c;
            hold();
            state("still");
            phase(Phase.RACE);
        }
    }

    /** 초롱이 뚝 멈추고 땅속 입이 쫓던 사람 발밑으로 달려옴(두둑이 땅을 가름). */
    private void race() {
        hold();
        Player p = prey;
        if (!standing(p)) {
            sink();
            return;
        }
        if (!cut) face(chest(p));
        Vector to = p.getLocation().toVector().setY(0).subtract(mouth.clone().setY(0));
        double l = to.length(), step = Math.max(0.5, l / Math.max(1, cd(RACE_T) - inPhase()));
        if (l > step) mouth.add(to.multiply(step / l));
        else mouth.add(to);
        mouth.setY(game.mobs.flow.floor(mouth.getX(), mouth.getZ()));
        poseMouth(1);
        if (inPhase() % 3 == 0) soundAt(mouth, "rise", Sound.BLOCK_ROOTED_DIRT_BREAK, 0.7f, 32);
        if (inPhase() >= cd(RACE_T)) rise();
    }

    /** 입이 솟음: 반경 2 안 사람 28, 가장 가까운 한 사람을 1.5초 문다. 초롱이 처음으로 눈을 뜬다. */
    private void rise() {
        risen = true;
        mouthTaken = 0;
        if (maw != null) {
            maw.state("bite");
            maw.pose(mouthMatrix(-1.6), 0);
        }
        body.look.open = true;
        soundAt(mouth, "rise", Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.6f, 32);
        Player bite = null;
        double bd = Double.MAX_VALUE;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            double d = Math.hypot(l.getX() - mouth.getX(), l.getZ() - mouth.getZ());
            if (d > RISE_R) continue;
            hit(q, RISE_DMG, "cause_g98");
            if (d < bd && standing(q) && game.players.grabbedBy(q) == null) {
                bd = d;
                bite = q;
            }
        }
        if (bite != null && grab(bite, ESCAPE)) {
            held = bite;
            soundAt(mouth, "bite", Sound.ENTITY_SLIME_ATTACK, 0.6f, 24);
        }
        phase(Phase.BITE);
    }

    private void biteTick() {
        hold();
        int t = inPhase();
        if (t == 1 && maw != null) maw.pose(mouthMatrix(0), 4);
        Player p = held;
        if (p != null) {
            face(chest(p));
            game.players.carry(p, new Vector(mouth.getX(), mouth.getY() + 1.0, mouth.getZ()));
            if (t > 0 && t % 10 == 0) hit(p, BITE_DPS / 2, "cause_g98");
        }
        if (t >= cd(BITE_T) || (p == null && t >= 12)) sink();
    }

    /** 입이 가라앉고 초롱은 고리로 돌아감. */
    private void sink() {
        if (held != null) {
            Player p = held;
            held = null;
            game.players.release(p);
        }
        risen = false;
        body.look.open = false;
        if (maw != null) {
            maw.state("mound");
            maw.pose(mouthMatrix(-0.4), 0);
        }
        chaser = null;
        prey = null;
        chaseT = 0;
        nextLure = age + cd(LURE_EVERY);
        state(cut ? "still" : "lure");
        phase(cut ? Phase.SUNK : Phase.HOME);
    }

    /** 안으로 들어와 가장 가까운 사람을 초롱으로 후려쳐 바깥으로 낚아챔. */
    private void lureTick() {
        Player p = prey;
        if (!standing(p) || inPhase() > LURE_MAX) {
            prey = null;
            nextLure = age + cd(LURE_EVERY);
            phase(Phase.HOME);
            return;
        }
        Vector at = chest(p);
        face(at);
        Vector to = at.clone().subtract(pos).setY(0);
        double l = to.length();
        if (l > SLAP_R) {
            vel.copy(to.multiply(LURE_V * Math.max(0.6, speed() / baseSpeed) / l));
            return;
        }
        hold();
        Vector out = p.getLocation().toVector().subtract(center()).setY(0);
        if (out.lengthSquared() < 1e-3) out = toward(p);
        hit(p, SLAP, "cause_g98_slap");
        knock(p, out, SLAP_V, 0.2);
        sound("slap", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 16);
        body.squash(1.2f, 0.85f, 1.2f, 2);
        prey = null;
        nextLure = age + cd(LURE_EVERY);
        phase(Phase.HOME);
    }

    /** 낚싯대가 끊긴 뒤: 땅속 입만 남아 가장 가까운 사람 밑으로 와서 솟는다. */
    private void sunk() {
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        if (target != p) watch(p);
        Location l = p.getLocation();
        double d = Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
        if (d > 2.5) {
            if (d > 12) follow(Math.max(speed(), 0.16));
            else seek(chest(p), Math.max(speed() * 2, 0.16));
            return;
        }
        hold();
        if (inPhase() >= cd(40)) {
            prey = p;
            phase(Phase.RACE);
        }
    }

    // ------------------------------------------------------------------ 입 자리

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** 입 두둑이 초롱 뒤(낚싯대가 박힌 곳)를 땅속으로 따라감. 끊긴 뒤에는 몸 바로 밑. */
    private void followMouth() {
        Vector want = cut ? pos.clone() : pos.clone().subtract(forward().multiply(MOUTH_BACK));
        Vector d = want.subtract(mouth).setY(0);
        double l = d.length();
        if (l > 0.35) mouth.add(d.multiply(Math.min(l, Math.max(0.35, l * 0.35)) / l));
        else mouth.add(d);
        mouth.setY(game.mobs.flow.floor(mouth.getX(), mouth.getZ()));
    }

    private Matrix4f mouthMatrix(double lift) {
        return new Matrix4f().translate((float) (mouth.getX() - pos.getX()), (float) (mouth.getY() + lift - pos.getY()),
                (float) (mouth.getZ() - pos.getZ())).rotateY((float) Math.toRadians(-yaw));
    }

    private void poseMouth(int ticks) {
        if (maw != null) maw.pose(mouthMatrix(0), ticks);
    }

    private void soundAt(Vector at, String name, Sound fb, float fbPitch, double range) {
        if (game.director.silent()) return;
        kr.gave.Props.soundAround(new Location(game.world, at.getX(), at.getY(), at.getZ()), "gabe." + sp.key() + "." + name, fb, 1f,
                0.94f + game.random.nextFloat() * 0.12f, fbPitch, range, -1);
    }

    // ------------------------------------------------------------------ 맞음

    /** 낚싯대 위 점(t 0 = 초롱 밑, 1 = 두둑). */
    private Vector rodAt(double t) {
        Vector a = pos.clone().add(new Vector(0, -0.4, 0));
        Vector b = mouth.clone().add(new Vector(0, 0.45, 0));
        Vector c = pos.clone().subtract(forward().multiply(0.9)).add(new Vector(0, 0.2, 0));
        double u = 1 - t;
        return a.multiply(u * u).add(c.multiply(2 * u * t)).add(b.multiply(t * t));
    }

    private double[] mouthSphere() {
        return risen ? new double[] {mouth.getX(), mouth.getY() + 0.8, mouth.getZ(), 1.6} : new double[] {mouth.getX(), mouth.getY() + 0.15, mouth.getZ(), 0.55};
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(6);
        if (!cut) {
            out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.3});
            for (double t : new double[] {0.3, 0.55, 0.8}) {
                Vector r = rodAt(t);
                out.add(new double[] {r.getX(), r.getY(), r.getZ(), ROD_R});
            }
        }
        out.add(mouthSphere());
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        Vector at = d.at();
        if (at == null) return super.damage(d);
        double[] m = mouthSphere();
        double dm = at.distance(new Vector(m[0], m[1], m[2])) - m[3];
        double dl = cut ? Double.MAX_VALUE : at.distance(pos) - 0.3;
        double dr = Double.MAX_VALUE;
        if (!cut) for (double t : new double[] {0.15, 0.3, 0.45, 0.6, 0.75, 0.9}) dr = Math.min(dr, at.distance(rodAt(t)) - ROD_R);
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (gun && !cut && dr < dl && dr < dm) {
            rodHp -= d.amount();
            game.mobs.onHurt(this, d, 0);
            if (d.by() != null) game.hud.hit(d.by(), false);
            if (rodHp <= 0) cutRod();
            return true;
        }
        if (dm < dl) {
            // 땅속 두둑은 절반, 솟은 입은 두 배
            Damage dd = d.times(risen ? 2.0 : 0.5);
            if (risen && held != null) {
                mouthTaken += dd.amount();
                if (mouthTaken >= maxHp * FREE_FRAC) {
                    Player p = held;
                    held = null;
                    game.players.release(p);
                }
            }
            return super.damage(dd);
        }
        return super.damage(d);
    }

    /** 낚싯대가 끊김: 초롱이 떨어져 팀 보상, 땅속 입만 남는다. */
    private void cutRod() {
        if (cut) return;
        cut = true;
        int won = (int) Math.max(1, Math.round(TEAM_WON * game.director.rewardMul()));
        game.players.teamMoney(won);
        game.hud.notice("notice_g98_cut", sp, 60);
        sound("snap", Sound.BLOCK_WOOD_BREAK, 1.2f, 24);
        Location at = loc();
        ItemDisplay fx = game.shotFx.spawn(at, "fx_quad", 0, 0, new Matrix4f().rotateY((float) Math.toRadians(-yaw)), Display.Billboard.FIXED, false, 40,
                ShotFx.NORMAL);
        if (fx != null) {
            fx.setItemStack(Body.item("gabe/g98", "still", new LookState(), game.worldTime()));
            fx.setBrightness(Body.LIGHT);
            Location ground = at.clone();
            ground.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + 0.35);
            game.shotFx.drift(fx, ground, 10);
        }
        body.model(Body.EMPTY);
        hover = 0.3;
        chaser = null;
        if (phase != Phase.BITE && phase != Phase.RACE) phase(Phase.SUNK);
    }

    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim == held) held = null;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (maw != null) maw.remove();
        maw = null;
        if (cut) leaveCorpse = false; // 땅속 입은 그대로 꺼짐
        super.die(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (cut ? " cut" : String.format(java.util.Locale.ROOT, " rod=%.0f", rodHp)) + (risen ? " risen" : "")
                + (held != null ? " held=" + held.getName() : "") + String.format(java.util.Locale.ROOT, " r=%.1f", fromCenter(pos));
    }
}
