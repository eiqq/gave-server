package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G183 가짜 트럭 게이브(3막, 목표 압박, 함정). 트럭(없으면 중앙 원 가운데)에서 26~34 블록, 서 있는 사람이 가장 적은 쪽 연기 속에 트럭 꼴 껍데기가 나타나
 * 움직이지 않고 선다. 나타나는 순간 진짜 트럭 등불이 깜빡이다 꺼지고(LAMP: 살아 있는 동안 트럭 노림 몫 x2, 무는 피해 x1.5), 이것의 등불이 켜지며
 * 반 박자 늦은 경적 두 번과 짐칸 문 소리를 흉내 낸다. 지붕 위 체력 막대는 줄지 않는다. 사람이 짐칸 문 4 블록 안에 들면 1 초 문이 들썩인 뒤
 * 아가리로 열려 앞 부채꼴 30, 한가운데 사람은 짐칸에 삼켜져 3 초 붙잡힘(0.5 초마다 6, 동료가 짐칸 문에 40 을 넣으면 뱉음). 10 초마다 바퀴 얼굴 넷이
 * 굴러 나와 가까운 사람에게 달려든다(체력 20, 치기 12 와 넘어짐 0.4 초). 등불 얼굴이 약점(x3, 부위 체력 200): 깨지면 진짜 트럭 등불이 다시 켜지고
 * 껍데기가 벗겨져 x1.5, 팀 전원 10 원.
 *
 * <p>팩 계약(tools/gabe/sp/g183.py): 몸 gabe/g183(shrink 0.38, 상태 "", "ajar", "open", "s", "s_open", 부위 w1..w4), 등불 gabe/g183_lamp
 * (상태 "", "broken"), 바퀴 gabe/g183_wheel(같은 종의 다른 꼴 개체). 소리 horn(64), door(32), maw(32), roll(24), smash(48).
 * 글귀 cause_g183, cause_g183_maw, cause_g183_wheel.
 *
 * <p>도감과 다른 점: 삼킴은 가두기(trap) 대신 붙잡아 짐칸 안 좌석으로(동료가 문을 쏘거나 3 초가 지나면 문 앞으로 뱉음). 굴러 나온 바퀴는 다른 소환처럼
 * 보상 없음(공통 규칙). 상점 빛은 빼고 체력 막대는 바닐라 글 줄(NAMETAG)로.
 */
public final class G183 extends Gabe {

    static final double HOVER = 1.6, R_MIN = 26, R_MAX = 34;
    static final double MAW_R = 4, MAW_COS = Math.cos(Math.toRadians(55)), MAW_DMG = 30, GULP_DMG = 6, SPIT_AT = 40, LAMP_HP = 200, LAMP_MUL = 3,
        STRIP_MUL = 1.5, WHEEL_HP = 20, WHEEL_DMG = 12, WHEEL_V = 0.36;
    static final int MAW_CD = 60, MAW_TELL = 20, GULP_T = 60, GULP_EVERY = 10, WHEEL_CD = 200, WHEEL_LIFE = 300, WHEEL_REGROW = 70, HORN_CD = 120,
        FLICKER = 18, TEAM_BONUS = 10;
    static final double[][] WHEELS = {{1.62, -2.3}, {-1.62, -2.3}, {1.62, 2.25}, {-1.62, 2.25}};

    private static boolean nextWheel;

    enum Phase { STAND, TELL, OPEN }

    private final boolean wheel;
    private Phase phase = Phase.STAND;
    private int phaseAt, mawReady, wheelAt, wheelsBackAt = -1, hornAt, smokeAt, gulpAt, appearAt = -1, flickerEnd = -1;
    private double lamp = -1, doorDmg;
    private boolean stripped;
    private Player gulped;
    private Body.Part lampPart;
    private Body.Tag bar;
    private final String key;
    private int maws, gulps, rolled, hits;
    // 바퀴 꼴
    private final Vector rollDir = new Vector(0, 0, 1);
    private int hitAt = -100, turnAt;
    private final List<double[]> sph = new ArrayList<>(6);
    private int sphAge = -1;

    public G183(Game game, Species sp) {
        super(game, sp);
        wheel = nextWheel;
        nextWheel = false;
        key = "G183#" + id;
        noMutate = true;
        noBite = true;
        if (wheel) {
            radius = 0.55;
            hover = 0.55;
            baseSpeed = WHEEL_V;
            noReward = true;
            reward = 0;
            noHold = true;
            noRules = true;
            leaveCorpse = true;
            ragKick = 1.4;
        } else {
            radius = 1.8;
            hover = HOVER;
            baseSpeed = 0;
            big = true;
            noPush = true;
            ghost = true;
            hpPow = Director.Tune.HP_POW_BIG;
            ragKick = 0.2;
        }
    }

    @Override
    protected void build() {
        if (wheel) {
            body = Body.of(this, "gabe/g183_wheel", 1.4f, 1.4f);
            return;
        }
        body = Body.of(this, "gabe/g183", 8f, 6f);
        body.viewRange(3f);
        lampPart = body.attach("gabe/g183_lamp");
        if (lampPart != null) lampPart.pose(new Matrix4f().translate(0f, 0.75f, -2.2f), 0);
        bar = body.tag(3.9f, 1.5f);
        bar.color(0x7CD67C).text("████████████████████");
    }

    @Override
    protected void spawned() {
        if (wheel || game.director.cageSpawning()) return;
        // 사람이 가장 적은 쪽 R 26~34 연기 속으로
        Vector c = G141Kit.middle();
        double best = 1e9, bestA = game.random.nextDouble() * Math.PI * 2;
        double r = R_MIN + game.random.nextDouble() * (R_MAX - R_MIN);
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6 + game.random.nextDouble() * 0.2;
            double x = c.getX() + Math.cos(a) * r, z = c.getZ() + Math.sin(a) * r;
            double n = 0;
            for (Player p : ps) {
                Location l = p.getLocation();
                double d = Math.hypot(l.getX() - x, l.getZ() - z);
                if (d < 24) n += 1 + (24 - d) / 24;
            }
            n += game.random.nextDouble() * 0.3;
            if (n < best) {
                best = n;
                bestA = a;
            }
        }
        Vector at = new Vector(c.getX() + Math.cos(bestA) * r, 0, c.getZ() + Math.sin(bestA) * r);
        at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
        game.mobs.relocate(this, at);
        G141Kit.faceFlat(this, c);
        body.pose(0);
        appearAt = age;
        flickerEnd = age + FLICKER;
        mawReady = age + 40;
        wheelAt = age + 120;
        hornAt = age + 10;
        sound("door", Sound.BLOCK_IRON_DOOR_OPEN, 0.8f, 32);
        for (int i = 0; i < 6; i++) smoke(4.5f, 40);
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
        if (wheel) {
            wheelTick();
            return;
        }
        hold();
        if (lamp < 0) lamp = LAMP_HP * Math.max(0.6, Math.sqrt(maxHp / Math.max(1, sp.hp)));
        truckLamp();
        if (age >= hornAt) honk();
        if (age >= smokeAt) {
            smokeAt = age + 12;
            smoke(3.2f, 50);
        }
        switch (phase) {
            case STAND -> stand();
            case TELL -> tell();
            case OPEN -> open();
        }
        gulpTick();
        wheels();
    }

    /** 진짜 트럭 등불: 나타날 때 깜빡이다 꺼짐, 등불이 깨지기 전까지 꺼진 채(LAMP 갈고리, 판이 바뀌면 떼어지니 40 틱마다 다시 검). */
    private void truckLamp() {
        Truck t = Truck.get();
        if (t == null) return;
        if (stripped) return;
        if (flickerEnd >= 0 && age < flickerEnd) {
            int k = (age - appearAt) / 3;
            t.lampOff(key, k % 2 == 0);
            return;
        }
        if (flickerEnd >= 0 || age % 40 == 0) {
            flickerEnd = -1;
            t.lampOff(key, true);
        }
    }

    private void lampOn() {
        Truck t = Truck.get();
        if (t != null) t.lampOff(key, false);
    }

    /** 반 박자 늦은 경적 두 번(가짜는 늦게 울린다). */
    private void honk() {
        int t = age - hornAt;
        if (t == 0 || t == 7) sound("horn", Sound.ENTITY_GOAT_HORN_BREAK, 0.6f, 64);
        if (t >= 7) hornAt = age + cd(HORN_CD) + game.random.nextInt(40);
    }

    private void smoke(float size, int life) {
        if (!game.shotFx.room(ShotFx.EXTRA)) return;
        double a = game.random.nextDouble() * Math.PI * 2, r = 1.5 + game.random.nextDouble() * 3.5;
        Location l = new Location(game.world, pos.getX() + Math.cos(a) * r, pos.getY() - HOVER + 0.6 + game.random.nextDouble() * 1.6,
            pos.getZ() + Math.sin(a) * r);
        game.shotFx.spawn(l, "fx_quad", ShotFx.SMOKE, 0, ShotFx.scale(size), Display.Billboard.CENTER, false, life, ShotFx.EXTRA);
    }

    // ------------------------------------------------------------------ 짐칸 아가리

    private Vector doorAt() {
        return G141Kit.at(this, 0, -0.4, 3.5);
    }

    private void stand() {
        if (age < mawReady || gulped != null) return;
        Vector dr = doorAt();
        Player near = null;
        double bd = 1e9;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double d = Math.hypot(l.getX() - dr.getX(), l.getZ() - dr.getZ());
            if (d <= MAW_R && d < bd && Math.abs(l.getY() - (pos.getY() - HOVER)) < 3) {
                bd = d;
                near = p;
            }
        }
        if (near == null) {
            if (target != null) mark(null);
            return;
        }
        mark(near);
        state(stripped ? "s" : "ajar");
        sound("door", Sound.BLOCK_IRON_DOOR_OPEN, 1.2f, 32);
        phase(Phase.TELL);
    }

    /** 문이 들썩이는 1 초(예고) 뒤 아가리. */
    private void tell() {
        if (inPhase() % 6 == 0) body.squash(1.03f, 0.97f, 1.04f, 3);
        if (inPhase() < cd(MAW_TELL)) return;
        maws++;
        state(stripped ? "s_open" : "open");
        sound("maw", Sound.ENTITY_RAVAGER_ATTACK, 0.6f, 32);
        Vector dr = doorAt();
        Vector f = G141Kit.fwd(this);
        Player center = null;
        double bestLat = 1e9;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - dr.getX(), dz = l.getZ() - dr.getZ(), h = Math.hypot(dx, dz);
            if (h > MAW_R + 0.4 || Math.abs(l.getY() - (pos.getY() - HOVER)) > 3) continue;
            if (h > 0.6 && (dx * f.getX() + dz * f.getZ()) / h < MAW_COS) continue;
            hit(p, MAW_DMG, "cause_g183_maw");
            double lat = Math.abs(dx * f.getZ() - dz * f.getX());
            if (lat < bestLat) {
                bestLat = lat;
                center = p;
            }
        }
        if (center != null && gulped == null && standing(center) && grab(center, 6)) {
            game.players.get(center).grabDetach = false;
            gulped = center;
            gulpAt = age;
            doorDmg = 0;
            gulps++;
        }
        phase(Phase.OPEN);
    }

    private void open() {
        if (inPhase() < 24) return;
        state(stripped ? "s" : "");
        mawReady = age + cd(MAW_CD);
        mark(null);
        phase(Phase.STAND);
    }

    /** 삼킨 사람: 짐칸 안 좌석, 0.5 초마다 6, 3 초나 문에 40 이면 뱉음. */
    private void gulpTick() {
        if (gulped == null) return;
        Player p = gulped;
        if (!p.isOnline() || game.players.grabbedBy(p) != this) {
            gulped = null;
            return;
        }
        game.players.carry(p, G141Kit.at(this, 0, -0.85 + 0.6, 1.2));
        if ((age - gulpAt) % GULP_EVERY == GULP_EVERY - 1) hit(p, GULP_DMG, "cause_g183");
        if (age - gulpAt >= GULP_T || doorDmg >= SPIT_AT) spit();
    }

    private void spit() {
        Player p = gulped;
        gulped = null;
        if (p == null) return;
        if (game.players.grabbedBy(p) == this) game.players.release(p);
        if (p.isOnline() && standing(p)) {
            Vector out = G141Kit.at(this, 0, -HOVER + 0.2, 4.6);
            p.teleport(new Location(game.world, out.getX(), out.getY(), out.getZ(), p.getLocation().getYaw(), p.getLocation().getPitch()));
            knock(p, G141Kit.fwd(this), 0.8, 0.35);
        }
        state(stripped ? "s_open" : "open");
        phase(Phase.OPEN);
        sound("door", Sound.BLOCK_IRON_DOOR_CLOSE, 0.8f, 32);
    }

    // ------------------------------------------------------------------ 바퀴

    private void wheels() {
        if (wheelsBackAt >= 0 && age >= wheelsBackAt) {
            wheelsBackAt = -1;
            for (int i = 1; i <= 4; i++) body.look.on.remove("w" + i);
        }
        if (age < wheelAt || game.mobs.standing().isEmpty()) return;
        wheelAt = age + cd(WHEEL_CD);
        int made = 0;
        for (int i = 0; i < 4; i++) {
            Vector at = G141Kit.at(this, WHEELS[i][0] * 1.15, -HOVER + 0.55, WHEELS[i][1]);
            nextWheel = true;
            Gabe c;
            try {
                c = child(sp, at);
            } finally {
                nextWheel = false;
            }
            if (c instanceof G183 w && w.wheel) {
                double k = Math.max(1, Math.sqrt(maxHp / Math.max(1, sp.hp)));
                w.maxHp = w.hp = WHEEL_HP * k;
                Vector side = G141Kit.left(this).multiply(WHEELS[i][0] > 0 ? 1 : -1);
                w.rollDir.copy(side);
                w.yaw = G141Kit.yawOf(side);
                body.look.on.add("w" + (i + 1));
                made++;
            }
        }
        if (made > 0) {
            rolled += made;
            wheelsBackAt = age + WHEEL_REGROW;
            sound("roll", Sound.ENTITY_MINECART_RIDING, 0.8f, 24);
        }
    }

    /** 바퀴 꼴: 옆으로 튀어나온 뒤 가장 가까운 사람에게 굴러가 친다. 300 틱 뒤 쓰러짐. */
    private void wheelTick() {
        if (age >= WHEEL_LIFE) {
            noReward = true;
            die(Damage.of(hp + 1, DamageType.OTHER));
            return;
        }
        if (age > 10 && age >= turnAt) {
            Player p = nearest(400);
            if (p != null) {
                Vector d = toward(p);
                if (d.lengthSquared() > 1e-6) {
                    // 굴러가는 쪽은 천천히 꺾임(한 틱 6 도)
                    double a0 = Math.atan2(rollDir.getZ(), rollDir.getX()), a1 = Math.atan2(d.getZ(), d.getX());
                    double da = a1 - a0;
                    while (da > Math.PI) da -= 2 * Math.PI;
                    while (da < -Math.PI) da += 2 * Math.PI;
                    double a = a0 + Math.max(-0.1, Math.min(0.1, da));
                    rollDir.setX(Math.cos(a)).setZ(Math.sin(a));
                }
                if (age - hitAt > 16 && touches(p, 0.35)) {
                    hitAt = age;
                    hits++;
                    hit(p, WHEEL_DMG, "cause_g183_wheel");
                    game.players.root(p, 8, "g183");
                    knock(p, rollDir, 0.5, 0.25);
                    turnAt = age + 18;
                }
            }
        }
        double v = speed() * (age < 10 ? 0.6 : 1);
        vel.setX(rollDir.getX() * v);
        vel.setZ(rollDir.getZ() * v);
        yaw = G141Kit.yawOf(rollDir);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (wheel) return super.spheres();
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(G141Kit.at(this, 0, 0.2, 1.8), 1.75);         // 0 짐칸 뒤쪽
        add(G141Kit.at(this, 0, 0.2, -0.2), 1.7);         // 1 짐칸 앞쪽
        add(G141Kit.at(this, 0, -0.2, -2.3), 1.3);        // 2 운전석
        add(G141Kit.at(this, 0, -0.1, 3.45), 0.95);       // 3 짐칸 문
        add(G141Kit.at(this, 0, 2.1, -2.2), lamp > 0 ? 0.48 : 0.01);  // 4 등불
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    private int nearestSphere(Vector at) {
        List<double[]> s = spheres();
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < s.size(); i++) {
            double[] c = s.get(i);
            double dd = Math.sqrt(Math.pow(at.getX() - c[0], 2) + Math.pow(at.getY() - c[1], 2) + Math.pow(at.getZ() - c[2], 2)) - c[3];
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        return best;
    }

    @Override
    public Vector head() {
        return wheel ? pos : G141Kit.at(this, 0, 2.1, -2.2);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (wheel) return super.damage(d);
        int s = d.at() == null ? 0 : nearestSphere(d.at());
        if (s == 4 && lamp > 0) {
            Damage x = d.times(LAMP_MUL);
            lamp -= x.amount();
            boolean ok = super.damage(x);
            if (!dead && lamp <= 0) strip();
            return ok;
        }
        if (s == 3 && gulped != null) doorDmg += d.amount();
        return super.damage(stripped ? d.times(STRIP_MUL) : d);
    }

    /** 등불이 깨짐: 진짜 트럭 등불이 다시 켜지고 껍데기가 벗겨진다(x1.5), 팀 전원 10 원. */
    private void strip() {
        stripped = true;
        lamp = 0;
        lampOn();
        if (lampPart != null) lampPart.state("broken");
        state(phase == Phase.OPEN ? "s_open" : "s");
        if (bar != null) bar.text("");
        sound("smash", Sound.BLOCK_GLASS_BREAK, 0.7f, 48);
        game.players.teamMoney((int) Math.max(1, Math.round(TEAM_BONUS * game.director.rewardMul())));
        if (game.shotFx.room(ShotFx.NORMAL)) {
            Vector l = G141Kit.at(this, 0, 2.1, -2.2);
            game.shotFx.bloodHit(new Location(game.world, l.getX(), l.getY(), l.getZ()), new Vector(0, 1, 0), true, ShotFx.BLOOD_PINK);
        }
    }

    private void cleanup() {
        if (wheel) return;
        lampOn();
        if (gulped != null && game.players.grabbedBy(gulped) == this) game.players.release(gulped);
        gulped = null;
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
        if (wheel) return super.toString() + " wheel hits=" + hits;
        return super.toString() + " ph=" + phase + " lamp=" + Math.round(lamp) + " stripped=" + stripped + " maws=" + maws + " gulps=" + gulps + " gulped="
            + (gulped == null ? "-" : gulped.getName()) + " rolled=" + rolled + " r=" + String.format("%.1f", G141Kit.hdist(pos, G141Kit.middle()))
            + " truckLamp=" + (Truck.get() == null ? "-" : Truck.get().lampDown() ? "off" : "on");
    }
}
