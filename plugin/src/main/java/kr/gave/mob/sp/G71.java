package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G71 해 가리는 날개 게이브(1막 6라운드, 방해 + 원거리, 날개 폭 16블록). 뼈대만 남은 날개 한 쌍(날개마다 굵은 막대기 뼈 둘, 그 사이에 얼굴 수십 장을
 * 기운 막)과 가운데 3블록 얼굴, 얼굴 깃 다섯 달린 꽁지. 몸통은 없다. 높은 하늘(바닥 위 22)을 넓은 그림자를 끌며 천천히 가로지르고 경기장 끝에서 크게
 * 돌아 다시 들어온다. 머리 위를 지나는 동안 하늘이 어두워진다.
 *
 * <p>공격: 8초마다 크게 날갯짓해 돌풍(날개가 1초 뒤로 젖혀지며 바닥에 흰 바람 띠가 일어남 → 띠가 사람 무리에서 바깥쪽으로 폭 3, 길이 24 로 기어 나감:
 * 닿으면 8 과 바람 쪽으로 5블록 밀림, 웅크리면 밀림 절반). 지나는 길에 한 번 낮게(바닥 위 7) 내려와 꽁지로 땅을 긁는다(그림자 띠가 좁아진 뒤 그 띠 안
 * 12 와 넘어짐 0.5초).
 *
 * <p>대처: 뼈 넷(뼈마다 체력 90 x 감독 배율)을 맞혀 둘을 끊으면 날개가 찢어져 떨어지고 땅에서 버둥댄다(받는 피해 x2). 가운데 얼굴 x1.5.
 *
 * <p>몸: 닻 = 가운데 얼굴과 꽁지(gave:gabe/g71), 부품 날개 둘(g71_wing 상태 l, r: Part.pose 로 어깨에서 날갯짓, 부위 cut_a, cut_b 끊긴 뼈),
 * 바닥 그림자(g71_shadow, 세계 축). 돌풍 띠(g71_gust)와 긁기 예고 띠(g71_strip)는 따로 선 연출 디스플레이. pos 는 늘 바닥 + {@link #ALT}, 내려옴과
 * 떨어짐은 body.offset(G77Sky 주석).
 */
public final class G71 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** pos 높이(바닥 위), 낮게 긁을 때 높이 차(얼굴 바닥 위 7), 땅에 떨어졌을 때 얼굴 높이(바닥 위). */
    static final double ALT = 22, DY_DIP = 7 - ALT, GROUND_H = 1.9;
    /** 나는 빠르기(블록/틱, 감독 배율 곱함), 한 틱에 꺾는 각도, 돌아서 다시 들어오는 거리(사람 무리 가운데에서). */
    static final double GLIDE_V = 0.28, TURN = 1.3, FAR = 46;
    /** 돌풍: 간격, 예고, 띠 길이(옆), 폭(앞뒤), 기어 나가는 거리, 틱, 피해, 밀침(블록/틱), 들림. */
    static final int GUST_EVERY = 160, GUST_TELL = 20, GUST_RUN = 22;
    static final double GUST_LEN = 24, GUST_W = 3, GUST_GO = 22, GUST_DMG = 17, GUST_PUSH = 1.2, GUST_UP = 0.3, GUST_REACH = 34;
    /** 낮게 긁기: 예고(그림자 띠가 좁아짐), 띠 길이, 맞는 반폭, 피해, 넘어짐, 올라가는 틱. */
    static final int DIP_TELL = 24, DIP_RISE = 30, DIP_ROOT = 10;
    static final double DIP_LEN = 16, DIP_HALF = 1.2, DIP_DMG = 25, DIP_R = 14;
    /** 뼈 체력, 끊겨 떨어지는 데 끊길 뼈 수, 떨어지는 틱, 땅에서 피해 배율, 버둥 치기 피해와 간격, 얼굴 배율. */
    static final double BONE_HP = 90, GROUND_MUL = 2, SLAP_DMG = 17, SLAP_R = 6, FACE_MUL = 1.5, CRASH_DMG = 21;
    static final int CUT_TO_FALL = 2, FALL = 30, SLAP_EVERY = 40, KEY = 4;
    /** 날개 어깨(몸 기준), 뼈(날개 모델 기준 뿌리, 끝: g71.py BONE_A, BONE_B 와 같음). */
    static final float SHOULDER_X = 1.5f, SHOULDER_Y = 0.2f;
    static final double[][] BONES = {{0.25, 0.0, 0.55, 8.2, 1.0, -0.3}, {0.25, -0.05, -0.35, 6.7, 0.35, -3.9}};

    enum Act { FLY, GUST, DIP, FALL, GROUND }

    private Act act = Act.FLY;
    private int actAt, nextGust, flapAt;
    private final G77Sky.Glide dyG = new G77Sky.Glide(), wingA = new G77Sky.Glide(), wingS = new G77Sky.Glide();
    private Body.Part wingL, wingR, shadow;
    private float shadowYaw = Float.NaN;
    private int dark;
    private final Vector goal = new Vector();
    private boolean hasGoal, dipped, duskOn;
    /** 뼈 체력: 0 왼 A, 1 왼 B, 2 오른 A, 3 오른 B. */
    private final double[] boneHp = new double[4];
    private boolean init;
    private int cut;
    // 돌풍 띠
    private ItemDisplay gust;
    private final Vector gustFrom = new Vector(), gustDir = new Vector();
    private final Set<UUID> gustHit = new HashSet<>();
    // 긁기 띠
    private ItemDisplay strip;
    private final Vector stripMid = new Vector();
    private float stripYaw;
    private int slapAt;
    private List<double[]> sph;
    private int sphAge = -1;

    public G71(Game game, Species sp) {
        super(game, sp);
        radius = 2.0;
        baseSpeed = GLIDE_V;
        noPush = true;
        noBite = true;
        noMutate = true;
        big = true;
        flying = true;
        ragKick = 0.3;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g71", 22f, G77Sky.boxH(ALT));
        body.viewRange(3.2f);
        dyG.snap(0);
        wingA.snap(0);
        wingS.snap(0);
        wingL = body.attach("gabe/g71_wing");
        wingR = body.attach("gabe/g71_wing");
        if (wingL != null) wingL.state("l");
        if (wingR != null) wingR.state("r");
        poseWings(0, 0);
        shadow = body.attach("gabe/g71_shadow");
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
        Vector c = G77Sky.centroid(game);
        if (c == null) c = G77Sky.center();
        yaw = (float) Math.toDegrees(Math.atan2(-(c.getX() - pos.getX()), c.getZ() - pos.getZ()));
        pitch = 0;
        passThrough(c);
        nextGust = 100 + game.random.nextInt(60);
        flapAt = game.random.nextInt(60);
        Props.soundAround(loc(), "sfx.colossus", Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f, 0.5f, 160, -1);
    }

    private void act(Act a) {
        act = a;
        actAt = age;
    }

    private int inAct() {
        return age - actAt;
    }

    private Vector fwd() {
        double a = Math.toRadians(yaw);
        return new Vector(-Math.sin(a), 0, Math.cos(a));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            for (int i = 0; i < 4; i++) boneHp[i] = BONE_HP * G77Sky.hpK(this);
        }
        double wantY = floor() + ALT;
        vel.setY(Math.max(-0.4, Math.min(0.4, (wantY - pos.getY()) * 0.2)));
        switch (act) {
            case FLY -> {
                glide();
                flapIdle();
                if (age >= nextGust && someoneNear(GUST_REACH)) startGust();
                else if (!dipped && dipNow()) startDip();
            }
            case GUST -> {
                glide();
                gust();
            }
            case DIP -> {
                glide();
                dip();
            }
            case FALL -> fall();
            case GROUND -> ground();
        }
        keyframe();
        poseShadow();
        duskTick();
    }

    private boolean someoneNear(double r) {
        for (Player p : game.mobs.standing()) if (G77Sky.flat(pos, p.getLocation()) <= r) return true;
        return false;
    }

    // ------------------------------------------------------------------ 가로지르기

    /** 사람 무리 가운데 c 를 지나 반대편 FAR 블록까지 가는 목표(조금 비스듬히). */
    private void passThrough(Vector c) {
        Vector d = c.clone().subtract(pos).setY(0);
        if (d.lengthSquared() < 1) d = fwd();
        d.normalize();
        double a = Math.toRadians((game.random.nextDouble() * 2 - 1) * 22);
        double x = d.getX() * Math.cos(a) - d.getZ() * Math.sin(a), z = d.getX() * Math.sin(a) + d.getZ() * Math.cos(a);
        goal.setX(c.getX() + x * FAR).setZ(c.getZ() + z * FAR);
        hasGoal = true;
        dipped = false;
    }

    private void glide() {
        Vector c = G77Sky.centroid(game);
        if (c == null) c = G77Sky.center();
        if (!hasGoal) passThrough(c);
        // 사람 무리를 지나 멀어졌으면 크게 돌아 다시 들어옴
        Vector off = pos.clone().subtract(c).setY(0);
        if (off.length() > FAR - 4 && off.dot(fwd()) > 0 && G77Sky.flat(pos, goal) < 14) passThrough(c);
        turnTo(new Vector(goal.getX(), pos.getY(), goal.getZ()), (float) TURN);
        pitch = 0;
        double v = GLIDE_V * speed() / baseSpeed;
        Vector f = fwd().multiply(v);
        vel.setX(f.getX()).setZ(f.getZ());
    }

    /** 머리 위를 지나는 동안 하늘을 어둡게(판 전체 상대 시각, Horizon.dusk 가 부드럽게 오르내림). 밤 사건, 보스 웨이브엔 안 함. */
    private void duskTick() {
        if (!every(20) || act == Act.GROUND || act == Act.FALL) return;
        Vector c = G77Sky.centroid(game);
        if (c == null || game.director.night() || game.director.bossWave()) return;
        if (G77Sky.flat(pos, c) < 16) {
            game.horizon.dusk(1800, 50);
            duskOn = true;
        }
    }

    // ------------------------------------------------------------------ 날갯짓

    /** 날개 둘을 어깨에서: 올림 각(+ 위), 뒤로 젖힘 각. */
    private void wings(double up, double sweep, int ticks) {
        long now = now();
        wingA.go(up, now, ticks);
        wingS.go(sweep, now, ticks);
    }

    /**
     * 몸 자세 열쇠(KEY 틱마다): 날개 각, 높이 차는 KEY 틱 뒤 값, 방향은 지금 값을 KEY 틱 보간으로 보낸다. 엔진 Body 는 부품마다 자세 보간이 하나뿐이라
     * 날갯짓(긴 보간)과 돌아섬(몸 자세 다시 보냄)이 서로 끊지 않게 짧게 나눠 보낸다.
     */
    private void keyframe() {
        if (age % KEY != 0) return;
        long t = now() + KEY;
        poseWings(t, KEY);
        body.offset.set(0, (float) dyG.at(t), 0);
        body.pose(KEY);
    }

    private void poseWings(long t, int ticks) {
        float up = (float) Math.toRadians(wingA.at(t)), sw = (float) Math.toRadians(wingS.at(t));
        if (wingL != null) wingL.pose(new Matrix4f().translate(SHOULDER_X, SHOULDER_Y, 0).rotateZ(up).rotateY(sw), ticks);
        if (wingR != null) wingR.pose(new Matrix4f().translate(-SHOULDER_X, SHOULDER_Y, 0).rotateZ(-up).rotateY(-sw), ticks);
    }

    private void flapIdle() {
        int t = (age + flapAt) % 70;
        if (t == 0) wings(18, 4, 16);
        else if (t == 16) {
            wings(-12, -2, 20);
            if ((age / 70) % 2 == 0) sound("flap", Sound.ENTITY_ENDER_DRAGON_FLAP, 0.5f, 96);
        } else if (t == 36) wings(0, 0, 18);
    }

    // ------------------------------------------------------------------ 돌풍

    private void startGust() {
        Vector c = G77Sky.centroid(game);
        Vector under = new Vector(pos.getX(), 0, pos.getZ());
        Vector d = c == null ? fwd() : under.clone().subtract(c).setY(0);
        if (d.lengthSquared() < 9) d = fwd();
        d.normalize();
        gustDir.copy(d);
        Vector start = c != null && G77Sky.flat(under, c) < 18 ? c.clone().subtract(d.clone().multiply(4)) : under.clone().subtract(d.clone().multiply(6));
        gustFrom.setX(start.getX()).setZ(start.getZ()).setY(game.mobs.flow.floor(start.getX(), start.getZ()) + 0.08);
        gustHit.clear();
        wings(32, 28, GUST_TELL - 2);
        sound("gust", Sound.ITEM_ELYTRA_FLYING, 0.5f, 128);
        float gy = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        Location at = new Location(game.world, gustFrom.getX(), gustFrom.getY(), gustFrom.getZ());
        G77Sky.kill(gust);
        gust = G77Sky.fx(game, at, "gabe/g71_gust", "", G77Sky.lay("gabe/g71_gust", gy, (float) GUST_LEN, 1f, (float) GUST_W), GUST_TELL + GUST_RUN + 4,
                (float) GUST_LEN + 2);
        nextGust = age + cd(GUST_EVERY);
        act(Act.GUST);
    }

    private void gust() {
        int t = inAct();
        if (t == GUST_TELL) {
            wings(-30, 0, 5);
            sound("flap", Sound.ENTITY_ENDER_DRAGON_FLAP, 0.4f, 128);
            game.horizon.shake(0.4, 10);
            if (gust != null && gust.isValid()) {
                gust.setItemStack(Body.item("gabe/g71_gust", "go", body.look.copy(), game.worldTime()));
                Vector to = gustFrom.clone().add(gustDir.clone().multiply(GUST_GO));
                game.shotFx.drift(gust, new Location(game.world, to.getX(), game.mobs.flow.floor(to.getX(), to.getZ()) + 0.08, to.getZ()), GUST_RUN);
            }
        }
        if (t >= GUST_TELL && t <= GUST_TELL + GUST_RUN) {
            double along = (t - GUST_TELL) * GUST_GO / GUST_RUN;
            Vector mid = gustFrom.clone().add(gustDir.clone().multiply(along));
            for (Player p : new ArrayList<>(game.mobs.standing())) {
                if (gustHit.contains(p.getUniqueId())) continue;
                Location l = p.getLocation();
                if (l.getY() > mid.getY() + 2.5) continue;
                double dx = l.getX() - mid.getX(), dz = l.getZ() - mid.getZ();
                double a = dx * gustDir.getX() + dz * gustDir.getZ(), s = Math.abs(-dx * gustDir.getZ() + dz * gustDir.getX());
                if (Math.abs(a) > GUST_W / 2 + 0.4 || s > GUST_LEN / 2) continue;
                gustHit.add(p.getUniqueId());
                boolean low = p.isSneaking();
                hit(p, GUST_DMG, "cause_g71");
                knock(p, gustDir, low ? GUST_PUSH / 2 : GUST_PUSH, low ? GUST_UP / 2 : GUST_UP);
            }
        }
        if (t == GUST_TELL + 6) wings(0, 0, 14);
        if (t >= GUST_TELL + GUST_RUN + 2) {
            G77Sky.kill(gust);
            gust = null;
            act(Act.FLY);
        }
    }

    // ------------------------------------------------------------------ 낮게 긁기

    private boolean dipNow() {
        Vector c = G77Sky.centroid(game);
        if (c == null) return false;
        Vector to = c.clone().subtract(pos).setY(0);
        return to.length() < DIP_R && to.dot(fwd()) > 0;
    }

    private void startDip() {
        dipped = true;
        double v = GLIDE_V * speed() / baseSpeed;
        Vector f = fwd();
        // 꽁지가 긁을 자리: 예고가 끝날 때 몸이 닿을 곳을 가운데로 앞뒤 DIP_LEN
        Vector mid = pos.clone().add(f.clone().multiply(v * DIP_TELL - 2));
        stripMid.setX(mid.getX()).setZ(mid.getZ()).setY(game.mobs.flow.floor(mid.getX(), mid.getZ()) + 0.07);
        stripYaw = yaw;
        Vector back = stripMid.clone().subtract(f.clone().multiply(DIP_LEN / 2));
        Location at = new Location(game.world, back.getX(), stripMid.getY(), back.getZ());
        G77Sky.kill(strip);
        strip = G77Sky.fx(game, at, "gabe/g71_strip", "", G77Sky.lay("gabe/g71_strip", yaw, 4f, 1f, (float) DIP_LEN), DIP_TELL + 8, (float) DIP_LEN + 2);
        game.shotFx.grow(strip, G77Sky.lay("gabe/g71_strip", yaw, (float) (DIP_HALF * 2), 1f, (float) DIP_LEN), DIP_TELL - 2);
        dyG.go(DY_DIP, now(), DIP_TELL);
        wings(10, 10, DIP_TELL);
        darkness(2);
        act(Act.DIP);
    }

    private void dip() {
        int t = inAct();
        if (t == DIP_TELL / 2 && strip != null && strip.isValid())
            strip.setItemStack(Body.item("gabe/g71_strip", "dark", body.look.copy(), game.worldTime()));
        if (t == DIP_TELL) {
            sound("scrape", Sound.BLOCK_GRAVEL_BREAK, 0.6f, 48);
            Vector f = new Vector(-Math.sin(Math.toRadians(stripYaw)), 0, Math.cos(Math.toRadians(stripYaw)));
            for (Player p : new ArrayList<>(game.mobs.standing())) {
                Location l = p.getLocation();
                if (l.getY() > stripMid.getY() + 3) continue;
                double dx = l.getX() - stripMid.getX(), dz = l.getZ() - stripMid.getZ();
                double a = dx * f.getX() + dz * f.getZ(), s = Math.abs(-dx * f.getZ() + dz * f.getX());
                if (Math.abs(a) <= DIP_LEN / 2 && s <= DIP_HALF + 0.3) {
                    hit(p, DIP_DMG, "cause_g71_tail");
                    game.players.root(p, DIP_ROOT, "g71");
                }
            }
            Location at = new Location(game.world, stripMid.getX(), stripMid.getY(), stripMid.getZ());
            G77Sky.quake(game, at, 4f);
        }
        if (t == DIP_TELL + 4) {
            G77Sky.kill(strip);
            strip = null;
            dyG.go(0, now(), DIP_RISE);
            wings(-20, 0, 12);
            sound("flap", Sound.ENTITY_ENDER_DRAGON_FLAP, 0.5f, 96);
            darkness(1);
        }
        if (t >= DIP_TELL + 4 + DIP_RISE) act(Act.FLY);
    }

    // ------------------------------------------------------------------ 찢어져 떨어짐, 땅에서 버둥

    private void startFall() {
        G77Sky.kill(gust);
        G77Sky.kill(strip);
        gust = strip = null;
        double to = floor() + GROUND_H - pos.getY();
        dyG.go(to, now(), FALL);
        wings(-38, 30, FALL);
        sound("gust", Sound.ITEM_ELYTRA_FLYING, 0.8f, 96);
        darkness(2);
        act(Act.FALL);
    }

    private void fall() {
        vel.setX(vel.getX() * 0.9).setZ(vel.getZ() * 0.9);
        if (inAct() == FALL) {
            Location at = new Location(game.world, pos.getX(), floor(), pos.getZ());
            for (Player p : new ArrayList<>(game.mobs.standing())) {
                if (G77Sky.flat(pos, p.getLocation()) <= 5 && p.getLocation().getY() < floor() + 3) hit(p, CRASH_DMG, "cause_g71");
            }
            G77Sky.quake(game, at, 7f);
            Props.soundAround(at, "sfx.quake", Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f, 0.6f, 96, -1);
            game.horizon.shake(0.6, 10);
            darkness(0);
            act(Act.GROUND);
        }
    }

    private void ground() {
        int t = inAct();
        Player p = nearest(400);
        if (p != null) {
            Vector to = p.getLocation().toVector();
            double l = G77Sky.flat(pos, to);
            double v = 0.05 * speed() / baseSpeed;
            if (l > 2.5) {
                vel.setX((to.getX() - pos.getX()) / l * v).setZ((to.getZ() - pos.getZ()) / l * v);
                turnTo(new Vector(to.getX(), pos.getY(), to.getZ()), 3f);
            } else vel.setX(0).setZ(0);
            if (l <= SLAP_R && age >= slapAt) {
                slapAt = age + cd(SLAP_EVERY);
                wings(25, -10, 4);
                hit(p, SLAP_DMG, "cause_g71");
                knock(p, toward(p), 0.7, 0.25);
                sound("flap", Sound.ENTITY_ENDER_DRAGON_FLAP, 0.9f, 48);
            }
        } else vel.setX(0).setZ(0);
        // 버둥: 날개를 아무렇게나 퍼덕
        if (t % 7 == 0) wings(-30 + game.random.nextInt(40), game.random.nextInt(40) - 10, 6);
        pitch = 0;
    }

    // ------------------------------------------------------------------ 그림자

    private void darkness(int n) {
        if (shadow == null || n == dark) return;
        dark = n;
        if (n == 0) {
            shadow.remove();
            shadow = null;
            return;
        }
        shadow.state("s" + n);
    }

    private void poseShadow() {
        if (shadow == null) return;
        if (Math.abs(yaw - shadowYaw) < 2f && !every(20)) return;
        shadowYaw = yaw;
        float fy = (float) (floor() + 0.06 - pos.getY());
        shadow.pose(new Matrix4f().translate(0, fy, 0).rotateY((float) Math.toRadians(-yaw)), 3);
    }

    // ------------------------------------------------------------------ 맞음

    /** 날개 모델 점(x, y, z)의 세계 자리(side 1 왼쪽, -1 오른쪽): 어깨 + 올림, 젖힘 + 몸 yaw + 높이 차. */
    private Vector wingPoint(int side, double x, double y, double z, double cy) {
        Vector3f v = new Vector3f((float) (x * side), (float) y, (float) z);
        float up = (float) Math.toRadians(wingA.at(now())), sw = (float) Math.toRadians(wingS.at(now()));
        new Matrix4f().translate(side * SHOULDER_X, SHOULDER_Y, 0).rotateZ(side * up).rotateY(side * sw).transformPosition(v);
        Vector w = G77Sky.local(v.x, v.y, v.z, yaw);
        return new Vector(pos.getX() + w.getX(), cy + w.getY(), pos.getZ() + w.getZ());
    }

    /** 맞는 구: [x, y, z, r, 부위] 부위 0 얼굴, 1..4 뼈(왼 A, 왼 B, 오른 A, 오른 B), 5 날개 막과 꽁지. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(20);
        double cy = pos.getY() + dyG.at(now());
        out.add(new double[] {pos.getX(), cy, pos.getZ(), 1.7, 0});
        for (int side : new int[] {1, -1}) {
            for (int b = 0; b < 2; b++) {
                int id = 1 + b + (side == 1 ? 0 : 2);
                double[] B = BONES[b];
                for (double u : new double[] {0.3, 0.62, 0.9}) {
                    if (boneHp[id - 1] <= 0 && init) break;
                    Vector w = wingPoint(side, B[0] + (B[3] - B[0]) * u, B[1] + (B[4] - B[1]) * u, B[2] + (B[5] - B[2]) * u, cy);
                    out.add(new double[] {w.getX(), w.getY(), w.getZ(), 0.55, id});
                }
            }
            for (double u : new double[] {0.35, 0.7}) {
                double[] A = BONES[0], B = BONES[1];
                double x = (A[0] + (A[3] - A[0]) * u + B[0] + (B[3] - B[0]) * u) / 2, y = (A[1] + (A[4] - A[1]) * u + B[1] + (B[4] - B[1]) * u) / 2,
                    z = (A[2] + (A[5] - A[2]) * u + B[2] + (B[5] - B[2]) * u) / 2;
                Vector w = wingPoint(side, x, y, z, cy);
                out.add(new double[] {w.getX(), w.getY(), w.getZ(), u < 0.5 ? 1.7 : 1.4, 5});
            }
        }
        for (double z : new double[] {-3.2, -6.0}) {
            Vector w = G77Sky.local(0, -0.3, z, yaw);
            out.add(new double[] {pos.getX() + w.getX(), cy + w.getY(), pos.getZ() + w.getZ(), 0.8, 5});
        }
        sph = out;
        sphAge = age;
        return out;
    }

    private int partAt(Vector at) {
        if (at == null) return 5;
        double best = Double.MAX_VALUE;
        int part = 5;
        for (double[] s : spheres()) {
            double dx = s[0] - at.getX(), dy = s[1] - at.getY(), dz = s[2] - at.getZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz) - s[3];
            if (d < best) {
                best = d;
                part = (int) s[4];
            }
        }
        return part;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        int part = partAt(d.at());
        double k = (part == 0 ? FACE_MUL : 1) * (act == Act.GROUND || act == Act.FALL ? GROUND_MUL : 1);
        Damage dd = k == 1 ? d : d.times(k);
        if (part >= 1 && part <= 4 && boneHp[part - 1] > 0) {
            boneHp[part - 1] -= dd.amount();
            if (boneHp[part - 1] <= 0) cutBone(part - 1);
        }
        return super.damage(dd);
    }

    private void cutBone(int i) {
        Body.Part w = i < 2 ? wingL : wingR;
        if (w != null) w.look.on.add(i % 2 == 0 ? "cut_a" : "cut_b");
        sph = null;
        cut++;
        sound("scrape", Sound.ENTITY_ITEM_BREAK, 0.5f, 64);
        if (cut >= CUT_TO_FALL && act != Act.FALL && act != Act.GROUND) startFall();
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        G77Sky.kill(gust);
        G77Sky.kill(strip);
        gust = strip = null;
        if (shadow != null) {
            shadow.remove();
            shadow = null;
        }
        super.die(d);
    }

    @Override
    public void remove() {
        G77Sky.kill(gust);
        G77Sky.kill(strip);
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " act=" + act + " cut=" + cut + " dy=" + Math.round(dyG.at(now()) * 10) / 10.0 + (duskOn ? " dusk" : "");
    }
}
