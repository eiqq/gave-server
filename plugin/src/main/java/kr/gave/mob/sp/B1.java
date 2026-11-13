package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.mob.boss.Boss;
import kr.gave.mob.boss.Tele;
import kr.gave.mob.boss.Ward;
import kr.gave.world.Plaza;

/**
 * B1 산더미 게이브(10웨이브 보스, 1막 '마른 길'의 끝. docs/모듈/보스.md 12절, 원정.md 10절). 작은 얼굴 무더기 위에 앉은 7블록 얼굴이 땅을 뚫고
 * 올라와 걸어오고, 엎어지고, 구르고, 땅을 울린다.
 *
 * <p>몸(팩 tools/gabe/sp/b1.py, 몸 배율 {@link #S}): 닻 gabe/b1(얼굴 상자, 상태 "" / crack / split, 뒤통수 기둥 끝 작은 머리 = 부위 back),
 * 부품 gabe/b1_heap(무더기: 세계 축 부품이라 머리가 엎어지거나 굴러가도 땅에 남아 뒤따라옴), 부품 gabe/b1_rod 넷(뒤통수 위 돛대, 노 젓기는 부품 보간).
 *
 * <p>맵을 쓴다({@link B1Ground}): 땅을 뚫고 올라오며 금과 솟은 길 판을 남기고, 엎어진 자리는 꺼져 땅울림 고리를 피할 구덩이가 되고, 구른 자리는
 * 떼가 빨라지는 고랑이 되고, 길 판은 구르기를 한 번 막아 주고 부서진다. 죽으면 무더기가 터진 자리에 둔덕이 남는다.
 *
 * <p>숫자는 {@link Tune} 한곳(피해는 보스.md 5절 기준: 체력 100, 방어 없음. 회차, 화남 배율은 바탕 hitBoss 가 곱함).
 */
public final class B1 extends Boss {

    /** 숫자 한곳. 난이도를 맞출 때 여기만 고친다. */
    public static final class Tune {
        // ---- 몸과 걷기: 떠 있는 높이(서 있을 때, 엎어졌을 때, 구를 때, 땅속), 걷기 빠르기(단계 1, 2, 3, 화남 배율, 등장), 사람들 가운데까지 남기는 거리
        public static double HOVER = 5.5, LIE = 2.9, ROLL_H = 3.7, DEEP = -13.5;
        public static double WALK = 0.06, WALK_P2 = 0.075, WALK_P3 = 0.09, WALK_ENRAGE = 1.15, INTRO_WALK = 0.1, KEEP = 6.5;
        public static float TURN = 2.5f;
        /** 걸음(틱)마다 땅울림(반경, 도). */
        public static int STEP_EVERY = 40;
        public static double STEP_QUAKE = 16, STEP_DEG = 0.3;
        /** 몸에 겹친 사람: 피해, 밀침, 같은 사람 사이 틱, 무더기 닿는 반지름. */
        public static double TOUCH_DMG = 5, TOUCH_PUSH = 0.5, TOUCH_R = 5.0;
        public static int TOUCH_EVERY = 20;
        /** 땅을 뚫고 나올 때 그 자리 사람: 피해, 반경. */
        public static double EMERGE_DMG = 15, EMERGE_R = 7, EMERGE_AT = 40;

        // ---- 엎어지기(단계 1, 2): 안쪽 피해(안쪽 반지름), 바깥 피해(원 반지름), 밀침, 표적 거리, 덮치는 거리, 흔들림 반경
        public static double SLAM_IN = 40, SLAM_OUT = 25, SLAM_R_IN = 3, SLAM_R = 6, SLAM_KNOCK = 0.6, SLAM_RANGE = 22, SLAM_MIN = 5, SLAM_MAX = 9,
            SLAM_QUAKE = 24, SLAM_LEAN = 20;
        /** 예고, 다시 쓰기(단계 1, 2), 엎어져 있는 틱, 넘어짐 틱, 막대기 둘 넘게 끊기면 예고 덤, 덮치는 틱, 일어나는 틱, 구덩이(깊이, 메워지기까지 틱). */
        public static int SLAM_TELL = 32, SLAM_CD1 = 180, SLAM_CD2 = 140, SLAM_DOWN = 60, SLAM_ROOT = 20, SLAM_TELL_CUT = 10, SLAM_FALL = 6, SLAM_RISE = 20,
            SLAM_PIT = 1, SLAM_PIT_KEEP = 200;

        // ---- 굴러오기(단계 1, 2): 피해, 옆과 위 밀침, 띠 폭(몸 폭), 표적 너머, 표적 거리, 빠르기(블록/틱), 맞는 반지름
        public static double ROLL_DMG = 30, ROLL_SIDE = 1.0, ROLL_UP = 0.4, ROLL_W = 7.0, ROLL_PAST = 6, ROLL_MIN = 10, ROLL_MAX = 35, ROLL_SPEED = 0.9,
            ROLL_HIT_R = 3.6, ROLL_EDGE = 56, ROLL_CRUSH = 3.2, SLAB_REACH = 4.6, ROLL_FREE = 5;
        public static int ROLL_TELL = 24, ROLL_CD = 240, ROLL_DIZZY = 20;
        /** 구른 뒤 어지러운 동안 받는 피해 배율. */
        public static double DIZZY_MUL = 1.5;

        // ---- 털어내기(단계 1, 2): 예고, 다시 쓰기, 졸개 수(단계 1, 2), 체력을 이만큼 잃을 때마다 한 번 더
        public static int SHAKE_TELL = 20, SHAKE_CD = 300, SHAKE_N1 = 6, SHAKE_N2 = 10;
        public static double SHAKE_HP = 0.10;

        // ---- 땅울림 고리(단계 2, 3): 피해, 위로, 느려짐, 퍼지는 빠르기, 처음과 끝 반지름, 가까운 사람 셈 반경
        public static double RING_DMG = 18, RING_UP = 0.5, RING_SLOW = 0.3, RING_SPEED = 0.6, RING_R0 = 4, RING_R = 20, RING_NEAR = 8;
        public static int RING_TELL = 20, RING_CD = 220, RING_CD_NEAR = 120, RING_NEAR_N = 3, RING_SLOW_T = 20;

        // ---- 뛰어 찍기(단계 3): 안쪽 피해(안쪽 반지름), 바깥 피해(그림자 반지름), 뛰는 높이, 표적 거리
        public static double HOP_IN = 35, HOP_OUT = 20, HOP_R_IN = 2.5, HOP_R = 5, HOP_HEIGHT = 8, HOP_RANGE = 44;
        public static int HOP_TELL = 20, HOP_CD = 100, HOP_ROOT = 20, HOP_REST = 16, HOP_PIT = 2, HOP_PIT_KEEP = 240;

        // ---- 무리 부르기(단계 3): 다시 쓰기, 예고, 살아 있는 떼 한도
        public static int CALL_CD = 400, CALL_TELL = 20, CALL_MAX = 2;

        // ---- 얼굴비(회차 2부터, 단계 2, 3): 다시 쓰기, 예고, 던지는 수, 사이 틱, 떨어지는 틱, 피해, 반지름, 밀침, 살아남는 몫
        public static int TOSS_CD = 260, TOSS_TELL = 20, TOSS_N = 8, TOSS_EVERY = 10, TOSS_FALL = 20;
        public static double TOSS_DMG = 10, TOSS_R = 1.2, TOSS_KNOCK = 0.4, TOSS_KEEP = 0.4;

        // ---- 부위: 무더기, 뒤통수 작은 얼굴(엎어진 동안), 속 얼굴 눈(단계 3) 배율, 막대기 하나의 부위 체력(최대 체력의 몫), 끊기면 비틀거리는 틱
        // 엎어진 동안 얼굴 구와 뒤통수 구 반지름(누운 머리는 높이 5.4 라 얼굴 구를 줄여야 땅에서 쏜 탄이 뒤통수에 닿음: 게임 안 시험 2026-11-12)
        public static double HEAP_MUL = 0.7, BACK_MUL = 3, EYE_MUL = 2, ROD_HP = 0.04, LIE_FACE_R = 2.8, BACK_R = 1.6;
        public static int CUT_STAGGER = 30;

        // ---- 지킬 것(트럭)에 주는 피해(보스 표 단위: 트럭은 25 가 체력 10%, 원정.md 13.5). 엎어지기가 덮으면 10%(원정.md 10절), 구르기 6%, 고리 2%, 뛰기 10%
        public static double WARD_SLAM = 25, WARD_ROLL = 15, WARD_RING = 5, WARD_HOP = 25;

        // ---- 맵: 고랑 위 게이브 빠르기, 진창 위 사람 느려짐, 죽을 때 터지는 작은 사체 수
        public static double FURROW_BUFF = 1.3, MUD_SLOW = 0.2;
        public static int BURST_N = 16;

        private Tune() {
        }
    }

    /** 몸 배율(팩 모델은 보통 크기로 굽고 여기서 키움: 얼굴 폭 1.6 → 7.2블록). */
    static final float S = 4.5f;
    /** 자세를 보내는 박자(틱): 몸과 막대기 넷을 한 박자에 같은 보간으로. */
    static final int BEAT = 5;
    /** 컬링 상자(몸 중심 기준): 막대기 끝(바닥 위 19블록)과 뒤따르는 무더기까지 들게. */
    static final float BOX_W = 30f, BOX_H = 28f;
    /** 무더기 가운데 높이(바닥 위), 무더기 맞는 구(둘레 거리, 반지름), 얼굴 반지름. */
    static final double HEAP_Y = 2.0, HEAP_RING = 3.6, HEAP_SPHERE = 2.4, FACE_R = 3.4, ROLL_RAD = 3.6;
    // 팩(tools/gabe/sp/b1.py)과 같게: 뒤통수 작은 머리 자리, 속 얼굴 눈 자리(몸 틀)
    static final float BACK_Z = -1.22f, EYE_X = 0.3f, EYE_Y = -0.02f, EYE_Z = 0.62f;
    /** 막대기: 부품 배율, 몸 틀 길이(모델 1.1 x 2), 맞는 구 자리(길이의 몫). */
    static final float ROD_SCALE = 2f, ROD_LEN = 2.2f;
    static final float[] ROD_AT = {0.3f, 0.6f, 0.9f};
    static final float[][] ROD_ROOT = {{0.62f, 0.72f, -0.42f}, {0.26f, 0.8f, -0.5f}, {-0.26f, 0.8f, -0.5f}, {-0.62f, 0.72f, -0.42f}};
    /** 막대기 자세(몸 틀 방향): 노 젓기 바탕, 높이 듦, 펼침, 뒤통수에 접음. */
    static final float[][] ROD_REST = {{0.75f, 0.62f, -0.25f}, {0.22f, 0.95f, -0.25f}, {-0.22f, 0.95f, -0.25f}, {-0.75f, 0.62f, -0.25f}};
    static final float[][] ROD_RAISE = {{0.2f, 1f, -0.3f}, {0.07f, 1f, -0.36f}, {-0.07f, 1f, -0.36f}, {-0.2f, 1f, -0.3f}};
    static final float[][] ROD_SPREAD = {{1f, 0.22f, -0.1f}, {0.6f, 0.8f, -0.1f}, {-0.6f, 0.8f, -0.1f}, {-1f, 0.22f, -0.1f}};
    static final float[][] ROD_FOLD = {{0.32f, -0.5f, -0.8f}, {0.11f, -0.45f, -0.88f}, {-0.11f, -0.45f, -0.88f}, {-0.32f, -0.5f, -0.8f}};
    static final int ROW = 0, RAISE = 1, SPREAD = 2, FOLD = 3;
    static final double ROW_DEG = 22, ROW_PERIOD = 120;

    private final Region heapR, backR, eyeR;
    private final Region[] rodR = new Region[4];
    private final B1Ground ground;
    private Body.Part heap;
    private final Body.Part[] rods = new Body.Part[4];
    private final Vector3f[] rodCur = new Vector3f[4];
    private int rodPose = RAISE;

    /** 무더기 가운데(세계 x, z. y 는 안 씀), 크기, 바닥에서 뜬 높이(뛸 때), 출렁임. */
    private final Vector heapPos = new Vector();
    private double heapScale = 1, heapLift;
    private boolean shaking, heapLoose, heapGlue;

    /** 틱 셈(등장, 싸움, 죽음 내내). */
    private long clock;
    private int walkT, steps, slams, unsquashAt = -1, roarUntil = -1;
    /** 몸을 쓰는 공격이 도는 중(엎어지기, 구르기, 고리 웅크림, 뛰기: 서로 겹치지 않음), 엎어져 누움, 뒤통수 약점이 드러남, 구르는 중. */
    private boolean bodyBusy, lying, slammed, rolling;
    private long dizzyUntil = -1, staggerUntil = -1, pitUntil = -1;
    private int slamReady, ringReady;
    private double shakeMark = 1.0;
    private final Map<UUID, Long> touchAt = new HashMap<>();
    private final List<Gabe> swarms = new ArrayList<>();
    // 등장
    private final Vector emerge = new Vector(), toCenter = new Vector(0, 0, 1);
    // 전멸 승리 연출
    private boolean won, wonDown;
    private int wonT;
    private TestWard testW;
    /** 시험 훅 '공격' 뒤 이 싸움 틱까지 저절로 고르는 공격을 쉰다(시험이 다른 공격에 섞이지 않게). */
    private int quietUntil = -1;
    static final int TEST_QUIET = 160;

    public B1(Game g, Species s) {
        super(g, s, new double[] {0.60, 0.25});
        flying = false;
        radius = FACE_R;
        baseSpeed = Tune.WALK;
        hover = Tune.DEEP;
        ragHold = 500;
        ragKick = 0.15;
        ownRoar = true;
        heapR = region("heap", Tune.HEAP_MUL);
        for (int i = 0; i < 4; i++) {
            final int k = i;
            rodR[i] = region("rod" + i, 1.0);
            rodR[i].hpFrac = Tune.ROD_HP;
            rodR[i].onCut = r -> cutRod(k);
            rodCur[i] = new Vector3f(ROD_RAISE[i][0], ROD_RAISE[i][1], ROD_RAISE[i][2]).normalize();
        }
        backR = region("back", Tune.BACK_MUL);
        backR.weak = true;
        eyeR = region("eye", Tune.EYE_MUL);
        eyeR.weak = true;
        ground = new B1Ground(this);
        // 회차 3부터는 처음부터 마지막 단계 기술까지 모두 쓴다(보스.md 17절)
        boolean all = cycle >= 3;
        attacks.add(slam(1, all ? 3 : 2));
        attacks.add(roll(1, all ? 3 : 2));
        attacks.add(shake(1, all ? 3 : 2));
        attacks.add(ring(all ? 1 : 2, 3));
        attacks.add(hop(all ? 1 : 3, 3));
        attacks.add(call(all ? 1 : 3, 3));
        // 회차 2 덤 기술(보스.md 17절): 2단계부터 무더기 얼굴을 위로 던지는 얼굴비
        if (cycle >= 2) attacks.add(toss(all ? 1 : 2, 3));
        Attack tw = testWard();
        tw.auto = false;
        attacks.add(tw);
        for (String m : new String[] {"t_front", "t_side", "t_pit"}) attacks.add(probe(m));
    }

    // ------------------------------------------------------------------ 몸

    @Override
    protected void build() {
        body = Body.of(this, "gabe/b1", BOX_W, BOX_H);
        body.scale(S, 0);
        body.viewRange(3.2f);
        body.turnTicks = BEAT;
        // 붙이는 차례 = 사체 조각 차례: 무더기, 막대기 넷
        heap = body.attach("gabe/b1_heap");
        if (heap != null) heap.follow = false;
        for (int i = 0; i < 4; i++) {
            rods[i] = body.attach("gabe/b1_rod");
            if (rods[i] != null) rods[i].pose(rodMatrix(i), 0);
        }
        heapPos.copy(pos);
        heapScale = 0.25;
        heapLift = -6;
        poseHeap(0);
    }

    /**
     * 놓을 자리: 사람들이 많이 보는 쪽(감독이 고른 방향)으로 가운데에서 EMERGE_AT 블록, 땅속. 원정 중이면 길 한복판(길은 남북으로 가운데를 꿰뚫음: 고른
     * 방향에 가까운 쪽 끝. 원정.md 10절 '길 한복판이 솟으며 갈라지고 그 밑에서 올라온다').
     */
    @Override
    public Vector spawnPoint(Vector suggested) {
        Vector c = Plaza.center(game.world).toVector();
        Vector d = suggested.clone().subtract(c).setY(0);
        if (d.lengthSquared() < 1e-6) d = new Vector(0, 0, 1);
        if (game.trip != null && game.trip.enabled) d = new Vector(0, 0, d.getZ() >= 0 ? 1 : -1);
        d.normalize().multiply(Tune.EMERGE_AT);
        double x = c.getX() + d.getX(), z = c.getZ() + d.getZ();
        return new Vector(x, Plaza.FLOOR_Y + Tune.DEEP, z);
    }

    @Override
    public int introLength() {
        return 130;
    }

    @Override
    public int dyingLength() {
        return 80;
    }

    /** 1막의 해 질 녘이 등장과 함께 저문다. */
    @Override
    public int skyTime() {
        return 14500;
    }

    @Override
    public double shadowRadius() {
        return 8;
    }

    @Override
    public Vector waveCenter() {
        return new Vector(pos.getX(), Plaza.FLOOR_Y, pos.getZ());
    }

    private static double ease(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float yawOf(Vector d) {
        return (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
    }

    /** 몸 앞 방향(수평 단위 벡터). */
    private Vector fwd() {
        double a = Math.toRadians(yaw);
        return new Vector(-Math.sin(a), 0, Math.cos(a));
    }

    /** 수평으로 한 틱에 maxDeg 씩만 at 쪽으로 돈다(pitch 는 건드리지 않음). 다 돌았으면 true. */
    private boolean turn(Vector at, float maxDeg) {
        double dx = at.getX() - pos.getX(), dz = at.getZ() - pos.getZ();
        if (dx * dx + dz * dz < 1e-6) return true;
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz)), diff = wrap(want - yaw);
        if (Math.abs(diff) <= maxDeg) {
            yaw = want;
            return true;
        }
        yaw += Math.signum(diff) * maxDeg;
        return false;
    }

    /** 바닥 높이(판 바닥 기준이 아니라 지금 그 자리의 땅: 구덩이면 낮음)에서 above 만큼 뜬 높이로 바로 맞춘다. */
    private void lift(double above) {
        hover = above;
        pos.setY(floor() + above);
    }

    /** 판 바닥(구덩이, 둔덕과 무관)에서 above 만큼(등장: 발밑 땅이 솟고 꺼지는 동안에도 고르게 올라오게). */
    private void liftFlat(double above) {
        double fl = floor();
        hover = above - (fl - Plaza.FLOOR_Y);
        pos.setY(Plaza.FLOOR_Y + above);
    }

    private double heapFloor() {
        return game.mobs.flow.floor(heapPos.getX(), heapPos.getZ());
    }

    private Vector heapAt() {
        return new Vector(heapPos.getX(), heapFloor(), heapPos.getZ());
    }

    private int cutCount() {
        int n = 0;
        for (Region r : rodR) if (r.cut) n++;
        return n;
    }

    private void log(String s) {
        game.plugin.getLogger().info("[판] 보스 B1 " + s);
    }

    // ------------------------------------------------------------------ 자세(막대기, 무더기)

    private Vector3f rodWant(int i, long at) {
        float[] a = switch (rodPose) {
            case RAISE -> ROD_RAISE[i];
            case SPREAD -> ROD_SPREAD[i];
            case FOLD -> ROD_FOLD[i];
            default -> ROD_REST[i];
        };
        Vector3f d = new Vector3f(a[0], a[1], a[2]).normalize();
        // 노 젓기: 몸 옆 축 둘레로 앞뒤로 느리게, 막대기마다 4분의 1 바퀴씩 늦게
        if (rodPose == ROW) d.rotateX((float) Math.toRadians(ROW_DEG * Math.sin(at * Math.PI * 2 / ROW_PERIOD + i * Math.PI / 2)));
        return d;
    }

    private Matrix4f rodMatrix(int i) {
        Vector3f d = rodCur[i];
        return new Matrix4f().translate(ROD_ROOT[i][0], ROD_ROOT[i][1], ROD_ROOT[i][2]).rotate(new Quaternionf().rotationTo(0, 1, 0, d.x, d.y, d.z))
                .scale(ROD_SCALE);
    }

    /** 한 박자: 막대기 넷을 다음 박자 자리로, 몸 방향과 모양도 같은 보간으로. */
    private void beat() {
        if (body == null) return;
        for (int i = 0; i < 4; i++) {
            rodCur[i].lerp(rodWant(i, clock + BEAT), 0.6f);
            if (rodCur[i].lengthSquared() < 1e-6f) rodCur[i].set(0, 1, 0);
            rodCur[i].normalize();
            if (rods[i] != null && !rodR[i].cut) rods[i].pose(rodMatrix(i), BEAT);
        }
        body.pose(BEAT);
        if (heap != null) heap.state(shaking || heapLoose ? "shake" : "");
        if (!heapLoose) poseHeap(BEAT);
    }

    /** 무더기 부품(세계 축): 몸 중심에서 본 자리, 몸이 보는 쪽, 크기. */
    private void poseHeap(int ticks) {
        if (heap == null) return;
        float s = (float) (S * heapScale);
        heap.pose(new Matrix4f().translate((float) (heapPos.getX() - pos.getX()), (float) (heapFloor() + HEAP_Y * heapScale + heapLift - pos.getY()),
                (float) (heapPos.getZ() - pos.getZ())).rotateY((float) Math.toRadians(-yaw)).scale(s), ticks);
    }

    /** 무더기가 머리를 따라온다: 보통은 머리 밑에 붙어 있고, 머리가 엎어지거나 굴러가면 땅에 남았다가 뒤따라 기어온다. */
    private void heapTick() {
        double wx = pos.getX(), wz = pos.getZ();
        if (lying) {
            // 엎어진 머리의 정수리 반대쪽(턱 쪽) 뒤에 머문다
            Vector f = fwd();
            wx -= f.getX() * 5.8;
            wz -= f.getZ() * 5.8;
        }
        double dx = wx - heapPos.getX(), dz = wz - heapPos.getZ(), d = Math.hypot(dx, dz);
        double sp = heapGlue ? 1e9 : rolling ? 0.7 : 0.45;
        if (d <= sp) heapPos.setX(wx).setZ(wz);
        else heapPos.setX(heapPos.getX() + dx / d * sp).setZ(heapPos.getZ() + dz / d * sp);
        heapLoose = lying || Math.hypot(pos.getX() - heapPos.getX(), pos.getZ() - heapPos.getZ()) > 0.05;
        if (heapLoose && (clock & 1) == 0) poseHeap(2);
    }

    /** 흙먼지(총 연출 예산 안에서, 덤 우선순위). */
    void dust(Vector at, double r, int n) {
        for (int i = 0; i < n; i++) {
            double a = game.random.nextDouble() * Math.PI * 2, rr = r * Math.sqrt(game.random.nextDouble());
            Location c = new Location(game.world, at.getX() + Math.cos(a) * rr, game.mobs.flow.floor(at.getX(), at.getZ()) + 0.7, at.getZ() + Math.sin(a) * rr);
            try {
                ItemDisplay d = game.shotFx.spawn(c, "fx_quad", ShotFx.DUST, 1, ShotFx.scale(2.2f), Display.Billboard.CENTER, false, 18, ShotFx.EXTRA);
                game.shotFx.grow(d, ShotFx.scale(6.5f), 15);
                game.shotFx.drift(d, c.clone().add(0, 2.0, 0), 16);
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 맞는 구

    @Override
    protected void refreshSpheres() {
        sphere(main, pos.getX(), pos.getY(), pos.getZ(), lying ? Tune.LIE_FACE_R : FACE_R);
        double hy = heapFloor() + HEAP_Y + heapLift, hr = HEAP_RING * heapScale;
        for (int k = 0; k < 6; k++) {
            double a = k * Math.PI / 3;
            sphere(heapR, heapPos.getX() + Math.cos(a) * hr, hy, heapPos.getZ() + Math.sin(a) * hr, HEAP_SPHERE);
        }
        for (int i = 0; i < 4; i++) {
            if (rodR[i].cut) continue;
            Vector3f d = rodCur[i];
            for (float f : ROD_AT) {
                double[] w = world(ROD_ROOT[i][0] + d.x * ROD_LEN * f, ROD_ROOT[i][1] + d.y * ROD_LEN * f, ROD_ROOT[i][2] + d.z * ROD_LEN * f, S);
                sphere(rodR[i], w[0], w[1], w[2], 0.7);
            }
        }
        if (slammed) {
            double[] w = world(0, 0, BACK_Z, S);
            sphere(backR, w[0], w[1], w[2], Tune.BACK_R);
        } else if (phase >= 3 && !rolling) {
            for (int side = -1; side <= 1; side += 2) {
                double[] w = world(side * EYE_X, EYE_Y, EYE_Z, S);
                sphere(eyeR, w[0], w[1], w[2], 1.4);
            }
        }
    }

    @Override
    protected double damageMul(Damage d, Region r) {
        return game.now() < dizzyUntil ? Tune.DIZZY_MUL : 1;
    }

    private void cutRod(int i) {
        if (rods[i] != null) rods[i].state("cut");
        staggerUntil = game.now() + Tune.CUT_STAGGER;
        sfx("gabe.b1.crack", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.5f, 96);
        quake(pos, 24, 0.5);
        if (body != null) {
            body.roll = (i < 2 ? -7 : 7);
            body.pose(4);
        }
        log("막대기 " + i + " 끊김(" + cutCount() + "/4)");
    }

    // ------------------------------------------------------------------ 등장(땅을 뚫고 올라온다)

    @Override
    protected void introStep(int t) {
        clock++;
        Vector c = Plaza.center(game.world).toVector();
        if (t == 1) {
            emerge.copy(pos).setY(Plaza.FLOOR_Y);
            Vector d = c.clone().subtract(emerge).setY(0);
            if (d.lengthSquared() < 1e-6) d = new Vector(0, 0, 1);
            toCenter.copy(d.normalize());
            yaw = yawOf(toCenter);
            pitch = 0;
            rodPose = RAISE;
            ground.emergeStart(emerge, toCenter);
            sfx("gabe.b1.crack", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.5f, 96);
        }
        ground.tick(clock);
        hold();
        // 1..50 돛대 넷이 갈라진 땅에서 먼저 솟는다 → 50 머리가 터져 나옴 → 88 다 올라와 포효 → 무더기가 구멍에서 솟고 걸어 나옴
        if (t <= 50) liftFlat(Tune.DEEP + (-5.0 - Tune.DEEP) * ease(t / 50.0));
        else if (t <= 88) liftFlat(-5.0 + (Tune.HOVER + 5.0) * ease((t - 50) / 38.0));
        else liftFlat(Tune.HOVER);
        if (t <= 50 && t % 10 == 0) {
            quake(emerge, 60, 0.35 + t * 0.008);
            sfx("gabe.b1.step", Sound.ENTITY_WARDEN_STEP, 1f, 0.5f, 96);
            dust(emerge, 5, 3);
        }
        if (t == 50) {
            ground.emergeBurst(emerge);
            sfx("gabe.b1.slam", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 128);
            sfx("gabe.b1.crack", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.5f, 96);
            quake(emerge, 72, 1.0);
            dust(emerge, 7, 8);
            for (Player p : game.mobs.standing()) {
                Vector f = p.getLocation().toVector();
                if (horiz(f, emerge) > Tune.EMERGE_R) continue;
                hitBoss(p, Tune.EMERGE_DMG, "cause_b1_step");
                Vector out = f.subtract(emerge).setY(0);
                knock(p, out.lengthSquared() < 1e-6 ? toCenter : out, 0.9, 0.5);
            }
        }
        if (t >= 54 && t <= 84 && (t - 54) % 6 == 0) ground.slabNext();
        if (t == 88) {
            rodPose = SPREAD;
            sfxAll("sfx.boss_roar", Sound.ENTITY_RAVAGER_ROAR, 1f, 0.45f, 24);
            sfx("gabe.b1.roar", Sound.ENTITY_RAVAGER_ROAR, 1f, 0.45f, 160);
            game.horizon.shake(1.0, 30);
            quake(pos, 72, 0.9);
        }
        if (t > 92) {
            double k = ease((t - 92) / 26.0);
            heapScale = 0.25 + 0.75 * k;
            heapLift = -6 * (1 - k);
            Vector d = c.clone().subtract(pos).setY(0);
            if (d.lengthSquared() > 1) {
                d.normalize().multiply(Tune.INTRO_WALK);
                vel.setX(d.getX()).setY(0).setZ(d.getZ());
            }
            if (t == 100 || t == 118) {
                sfx("gabe.b1.giggle", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f, 64);
                sfx("gabe.b1.step", Sound.ENTITY_WARDEN_STEP, 1f, 0.5f, 96);
                quake(pos, 40, 0.5);
            }
        }
        if (t >= introLength() - 8) rodPose = ROW;
        if (t >= introLength()) {
            heapScale = 1;
            heapLift = 0;
        }
        heapGlue = true;
        heapTick();
        heapGlue = false;
        if (clock % BEAT == 0) beat();
    }

    // ------------------------------------------------------------------ 싸움

    @Override
    protected void act() {
        clock++;
        long now = game.now();
        ground.tick(clock);
        if (unsquashAt >= 0 && clock >= unsquashAt && body != null) {
            unsquashAt = -1;
            body.squash(1, 1, 1, 6);
        }
        if (won) victoryTick();
        else if (fightTicks < roarUntil) hold();
        else {
            if (!bodyBusy) {
                if (now < staggerUntil) hold();
                else walk();
            }
            runAttacks();
            shakeOnLoss();
        }
        touch();
        if ((clock & 1) == 0) shove();
        heapTick();
        if (clock % BEAT == 0) beat();
    }

    private double walkSpeed() {
        double v = phase >= 3 ? Tune.WALK_P3 : phase == 2 ? Tune.WALK_P2 : Tune.WALK;
        return enraged ? v * Tune.WALK_ENRAGE : v;
    }

    /** 사람들이 가장 많이 모인 쪽(무게 중심)으로 걸어간다. 닿을 만큼 가까우면 멈춰 돌아보기만. */
    private void walk() {
        Vector c = crowdCenter();
        turn(c, Tune.TURN);
        if (body != null && body.roll != 0 && game.now() >= staggerUntil) body.roll = 0;
        if (horiz(c, pos) > Tune.KEEP) {
            Vector d = c.clone().subtract(pos).setY(0).normalize().multiply(walkSpeed());
            vel.setX(d.getX()).setY(0).setZ(d.getZ());
            if (++walkT % Tune.STEP_EVERY == 0) step();
            if (walkT % 10 == 0) {
                B1Ground.Slab s = ground.hit(heapAt(), 3.6);
                if (s != null) ground.shatter(s);
            }
        } else hold();
        hover = Tune.HOVER + Math.sin(walkT * Math.PI * 2 / Tune.STEP_EVERY) * 0.12;
    }

    /** 걸음 하나: 땅울림, 쿵, 두 걸음마다 진창 발자국. */
    private void step() {
        steps++;
        quake(heapAt(), Tune.STEP_QUAKE, Tune.STEP_DEG);
        sfx("gabe.b1.step", Sound.ENTITY_WARDEN_STEP, 1f, 0.5f, 96);
        if (body != null) {
            body.squash(1.03f, 0.95f, 1.03f, 3);
            unsquashAt = (int) clock + 4;
        }
        if (steps % 2 == 0) ground.mud(heapAt(), 3.2);
    }

    /** 몸에 겹친 사람은 밀려나고 밟힌다(무더기 밑, 엎어진 머리 밑). */
    private void touch() {
        if (heapLift > 1 || rolling) return;
        long now = game.now();
        double top = heapFloor() + 4.2;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - heapPos.getX(), dz = l.getZ() - heapPos.getZ(), d = Math.hypot(dx, dz);
            boolean in = d < Tune.TOUCH_R * heapScale && l.getY() < top;
            if (!in && lying) {
                dx = l.getX() - pos.getX();
                dz = l.getZ() - pos.getZ();
                d = Math.hypot(dx, dz);
                in = d < 3.4 && l.getY() < pos.getY() + 2.6;
            }
            if (!in) continue;
            if (d < 1e-3) {
                dx = 1;
                dz = 0;
            }
            knock(p, new Vector(dx, 0, dz), Tune.TOUCH_PUSH, 0.1);
            Long at = touchAt.get(p.getUniqueId());
            if (at != null && now - at < Tune.TOUCH_EVERY) continue;
            touchAt.put(p.getUniqueId(), now);
            hitBoss(p, Tune.TOUCH_DMG, "cause_b1_step");
        }
    }

    /** 지나는 길의 땅 게이브는 옆으로 밀려난다. */
    private void shove() {
        Vector c = heapAt().add(new Vector(0, 1, 0));
        double r = 5.4 * heapScale;
        for (Gabe g : game.mobs.near(c, r + 2.5, x -> x != this && !x.dead && !x.flying && !x.ghost && !x.sp.is(Role.BOSS))) {
            double dx = g.pos.getX() - c.getX(), dz = g.pos.getZ() - c.getZ(), d = Math.hypot(dx, dz);
            if (d >= r) continue;
            if (d < 1e-3) {
                dx = 1;
                dz = 0;
                d = 1;
            }
            double k = Math.min(0.4, r - d) / d;
            g.pos.setX(g.pos.getX() + dx * k).setZ(g.pos.getZ() + dz * k);
        }
    }

    /** 체력을 10% 잃을 때마다 털어내기 한 번 더(다시 쓰기와 무관). */
    private void shakeOnLoss() {
        if (lastPhase() && cycle < 3 || inTransition() || dying) return;
        if (hpFrac() > shakeMark - Tune.SHAKE_HP) return;
        shakeMark = hpFrac();
        super.force("shake", null);
    }

    private int nearCount(double r) {
        int n = 0;
        for (Player p : game.mobs.standing()) if (horiz(p.getLocation().toVector(), pos) <= r) n++;
        return n;
    }

    private boolean anyoneBetween(double lo, double hi) {
        for (Player p : game.mobs.standing()) {
            double d = horiz(p.getLocation().toVector(), pos);
            if (d >= lo && d <= hi) return true;
        }
        return false;
    }

    @Override
    protected void onPhase(int n) {
        // 2 '얼굴이 갈라진다'(이음선과 찢어진 입), 3 '속이 드러났다'(두 쪽으로 벌어지고 속 얼굴이 눈을 뜬다)
        state(n >= 3 ? "split" : "crack");
        roarUntil = fightTicks + 36;
        rodPose = SPREAD;
        pitch = 0;
        sfx("gabe.b1.crack", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.5f, 96);
        sfx("gabe.b1.roar", Sound.ENTITY_RAVAGER_ROAR, 1f, 0.45f, 160);
        quake(pos, 56, 0.9);
        if (body != null) {
            body.squash(1.08f, 1.08f, 1.08f, 4);
            unsquashAt = (int) clock + 12;
        }
        dust(heapAt(), 6, 5);
        // 맵: 발밑에서 금이 사방으로 달리고 사람들 쪽에 길 판 둘이 더 솟는다
        Vector d = crowdCenter().subtract(pos).setY(0);
        if (d.lengthSquared() < 1e-6) d = fwd();
        ground.phaseBreak(pos, d.normalize());
        if (n >= 3) {
            double[] w = world(0, EYE_Y, EYE_Z + 0.2, S);
            reveal(new Vector(w[0], w[1], w[2]), 80);
        }
        shakeMark = Math.min(shakeMark, hpFrac());
    }

    // ------------------------------------------------------------------ 공격

    /**
     * 엎어지기 ★: 막대기 넷을 높이 들고 뒤로 젖히며 크게 들이쉰 뒤, 앞 5~9블록의 빨간 원(반지름 6)에 얼굴로 엎어진다. 안쪽 3블록 40, 원 안 25, 넘어짐,
     * 바깥으로 밀침. 엎어진 자리는 1블록 꺼지고(10초 뒤 메워짐: 그 안은 땅울림 고리가 지나감) 60틱 동안 뒤통수 작은 얼굴(x3)이 드러난다.
     * 지킬 것(트럭)이 닿는 거리에 있으면 세 번에 한 번은 그것을 덮친다.
     */
    private Attack slam(int from, int to) {
        Attack a = new Attack("slam", from, to, Tune.SLAM_CD2, Tune.SLAM_TELL, Pick.CLUSTER, true) {
            Tele tl;
            final Vector at = new Vector();
            int fall;
            boolean mine;

            @Override
            protected boolean usable() {
                return !bodyBusy && fightTicks >= slamReady && !quiet();
            }

            @Override
            protected void start() {
                mine = !bodyBusy;
                if (!mine) return;
                bodyBusy = true;
                if (cutCount() >= 2) tellTicks += Tune.SLAM_TELL_CUT;
                Vector goal = aim.clone();
                Ward w = B1.this.ward();
                slams++;
                if (w != null && w.alive() && w.pos() != null && (slams % 3 == 0 || target == null || w == testW) && horiz(w.pos(), pos) <= Tune.SLAM_RANGE) {
                    ward = w;
                    goal = w.pos().clone();
                }
                Vector d = goal.subtract(pos).setY(0);
                double len = d.length();
                if (len < 1e-3) {
                    d = fwd();
                    len = 1;
                }
                d.multiply(1 / len);
                at.copy(pos).add(d.multiply(Math.max(Tune.SLAM_MIN, Math.min(Tune.SLAM_MAX, len)))).setY(Plaza.FLOOR_Y);
                tl = disc(at, Tune.SLAM_R, tellTicks);
                fall = Math.max(4, tellTicks - Tune.SLAM_FALL);
                rodPose = RAISE;
                hold();
                sfx("gabe.b1.inhale", Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 0.6f, 64);
            }

            @Override
            protected boolean step() {
                if (!mine) return true;
                int T = tellTicks;
                if (t < fall) {
                    hold();
                    turn(at, 8f);
                    double k = Math.min(1, t / (fall * 0.7));
                    pitch = (float) (-Tune.SLAM_LEAN * k);
                    hover = Tune.HOVER + 0.6 * k;
                    return false;
                }
                if (t == fall) {
                    yaw = yawOf(at.clone().subtract(pos).setY(0));
                    pitch = 90;
                    rodPose = FOLD;
                    lying = true;
                    if (body != null) body.pose(Math.max(2, T - fall));
                }
                if (t < T) {
                    int left = T - t;
                    vel.setX((at.getX() - pos.getX()) / left).setY(0).setZ((at.getZ() - pos.getZ()) / left);
                    lift(hover + (Tune.LIE - hover) / left);
                    return false;
                }
                hold();
                if (t == T) {
                    lift(Tune.LIE);
                    slamHit(tl, at);
                    slammed = true;
                    if (body != null) body.look.on.add("back");
                    double[] tip = world(0, 0, BACK_Z, S);
                    reveal(new Vector(tip[0], tip[1], tip[2]), Tune.SLAM_DOWN);
                    return false;
                }
                int r = t - (T + Tune.SLAM_DOWN);
                if (r < 0) return false;
                if (r == 0) {
                    slammed = false;
                    lying = false;
                    if (body != null) {
                        body.look.on.remove("back");
                        body.pose(Tune.SLAM_RISE);
                    }
                    pitch = 0;
                    rodPose = ROW;
                }
                hover = Tune.LIE + (Tune.HOVER - Tune.LIE) * ease((r + 1) / (double) Tune.SLAM_RISE);
                return r + 1 >= Tune.SLAM_RISE;
            }

            @Override
            protected void end() {
                if (!mine) return;
                bodyBusy = false;
                lying = false;
                if (slammed && body != null) body.look.on.remove("back");
                slammed = false;
                upright(10);
                slamReady = fightTicks + coolTicks(phase >= 2 ? Tune.SLAM_CD2 : Tune.SLAM_CD1);
            }
        };
        a.range = Tune.SLAM_RANGE;
        return a;
    }

    /** 몸을 바로 세운다(공격이 끊기거나 끝났을 때). */
    private void upright(int ticks) {
        boolean bent = pitch != 0 || hover != Tune.HOVER || (body != null && body.roll != 0);
        pitch = 0;
        hover = Tune.HOVER;
        heapLift = 0;
        rolling = false;
        rodPose = ROW;
        if (body != null) {
            body.roll = 0;
            if (bent) body.pose(ticks);
        }
    }

    /** 넘어짐을 5틱 뒤에(밀침이 먼저 먹게: 넘어짐은 그 자리에 붙들어 두므로 같은 틱에 걸면 밀침이 지워짐, 게임 안 시험 2026-11-12). */
    private void rootSoon(Player p, int ticks) {
        UUID id = p.getUniqueId();
        game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
            Player q = game.plugin.getServer().getPlayer(id);
            if (q != null && q.isOnline() && !removed) game.players.root(q, ticks, "boss");
        }, 5L);
    }

    private void slamHit(Tele tl, Vector at) {
        int n = 0;
        for (Player p : caught(tl, 0.3)) {
            Vector f = p.getLocation().toVector();
            hitBoss(p, horiz(f, at) <= Tune.SLAM_R_IN ? Tune.SLAM_IN : Tune.SLAM_OUT, "cause_b1_slam");
            rootSoon(p, Tune.SLAM_ROOT);
            Vector out = f.subtract(at).setY(0);
            knock(p, out.lengthSquared() < 1e-6 ? fwd() : out, Tune.SLAM_KNOCK, 0.3);
            n++;
        }
        int wn = 0;
        for (Ward w : caughtWards(tl, 0.3)) {
            hitWard(w, Tune.WARD_SLAM, "cause_b1_slam");
            wn++;
        }
        quake(at, Tune.SLAM_QUAKE, 0.9);
        sfx("gabe.b1.slam", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 128);
        dust(at, 6, 6);
        // 맵: 엎어진 자리가 꺼진다(가장자리 얕게). 그 안에 든 사람은 땅울림 고리가 머리 위로 지나간다
        int g = pit(at, Tune.SLAM_R, Tune.SLAM_PIT, 6);
        mend(g, 30, Tune.SLAM_PIT_KEEP);
        pitUntil = game.now() + Tune.SLAM_PIT_KEEP + 20;
        log("slam 맞음=" + n + " 지킬것=" + wn + " 구덩이=" + g);
    }

    /**
     * 굴러오기: 앞뒤로 흔들며 막대기를 접은 뒤 표적 너머 6블록까지 빨간 띠를 따라 구른다. 치이면 30, 옆으로 1.0, 위로 0.4(한 굴림에 한 번). 깔린 땅
     * 게이브는 죽는다. 솟은 길 판에 부딪히면 판이 부서지고 거기서 멈춘다. 멈춘 뒤 20틱 어지러움(전신 x1.5). 구른 자리는 고랑으로 남는다.
     * 막대기가 둘 넘게 끊기면 못 구른다.
     */
    private Attack roll(int from, int to) {
        Attack a = new Attack("roll", from, to, Tune.ROLL_CD, Tune.ROLL_TELL, Pick.TOP, false) {
            Tele tl;
            final Vector start = new Vector(), goal = new Vector(), dir = new Vector();
            final Set<UUID> hitSet = new HashSet<>();
            final Set<Ward> wardSet = new HashSet<>();
            double len, done;
            int stage, st, crushed;
            boolean mine;

            @Override
            protected boolean usable() {
                return !bodyBusy && cutCount() < 2 && anyoneBetween(Tune.ROLL_MIN, Tune.ROLL_MAX) && !quiet();
            }

            @Override
            protected void start() {
                mine = !bodyBusy;
                if (!mine) return;
                bodyBusy = true;
                hitSet.clear();
                wardSet.clear();
                stage = 0;
                st = 0;
                done = 0;
                crushed = 0;
                Vector d = aim.clone().subtract(pos).setY(0);
                double l = d.length();
                if (l < 1e-3) {
                    d = fwd();
                    l = 1;
                }
                dir.copy(d.multiply(1 / l));
                len = Math.min(l, Tune.ROLL_MAX) + Tune.ROLL_PAST;
                start.copy(pos).setY(Plaza.FLOOR_Y);
                // 경기장 안에서 멈추게 줄임
                while (len > 6 && Plaza.radius(start.getX() + dir.getX() * len, start.getZ() + dir.getZ() * len) > Tune.ROLL_EDGE) len -= 1;
                goal.copy(start).add(dir.clone().multiply(len));
                tl = strip(start, goal, Tune.ROLL_W, tellTicks);
                rodPose = FOLD;
                hold();
                sfx("gabe.b1.roll", Sound.ENTITY_RAVAGER_STEP, 1f, 0.4f, 96);
            }

            @Override
            protected boolean step() {
                if (!mine) return true;
                int T = tellTicks;
                if (t < T) {
                    hold();
                    turn(goal, 9f);
                    hover += (Tune.ROLL_H - hover) / Math.max(1, T - t);
                    if (t % 4 == 0 && body != null) {
                        body.roll = (t / 4) % 2 == 0 ? 12 : -12;
                        body.pose(4);
                    }
                    return false;
                }
                if (t == T) {
                    yaw = yawOf(dir);
                    hover = Tune.ROLL_H;
                    rolling = true;
                    stage = 1;
                    if (body != null) body.roll = 0;
                }
                if (stage == 1) {
                    double v = Math.min(Tune.ROLL_SPEED, len - done);
                    vel.setX(dir.getX() * v).setY(0).setZ(dir.getZ() * v);
                    done += v;
                    pitch += (float) Math.toDegrees(v / ROLL_RAD);
                    if ((t & 1) == 0 && body != null) body.pose(2);
                    if (t % 5 == 0) {
                        sfx("gabe.b1.roll", Sound.ENTITY_RAVAGER_STEP, 1f, 0.4f, 96);
                        quake(pos, 20, 0.5);
                        dust(pos, 2.5, 1);
                    }
                    for (Player p : game.mobs.standing()) {
                        if (hitSet.contains(p.getUniqueId())) continue;
                        Vector f = p.getLocation().toVector();
                        if (horiz(f, pos) > Tune.ROLL_HIT_R || f.getY() > pos.getY() + 2 || !tl.inside(f, 0.3)) continue;
                        hitSet.add(p.getUniqueId());
                        hitBoss(p, Tune.ROLL_DMG, "cause_b1_roll");
                        double side = (f.getX() - pos.getX()) * -dir.getZ() + (f.getZ() - pos.getZ()) * dir.getX();
                        knock(p, new Vector(-dir.getZ(), 0, dir.getX()).multiply(side >= 0 ? 1 : -1), Tune.ROLL_SIDE, Tune.ROLL_UP);
                    }
                    if (bd != null) {
                        for (Ward w : bd.wards()) {
                            if (!w.alive() || w.pos() == null || wardSet.contains(w)) continue;
                            if (horiz(w.pos(), pos) > Tune.ROLL_HIT_R + w.radius()) continue;
                            wardSet.add(w);
                            hitWard(w, Tune.WARD_ROLL, "cause_b1_roll");
                        }
                    }
                    crushed += crush(Tune.ROLL_CRUSH, dir);
                    // 앞을 막은 길 판만(옆이나 뒤의 판에 막혀 1블록 만에 서던 것: 게임 안 시험 2026-11-12)
                    // 처음 몇 블록은 붙어 있던 판을 부수며 나아가고, 그 뒤 앞을 막은 판에서 멈춘다
                    B1Ground.Slab s = ground.hitAhead(pos, dir, Tune.SLAB_REACH, Tune.ROLL_W / 2 + 2);
                    if (s != null) {
                        ground.shatter(s);
                        quake(pos, 28, 0.9);
                        sfx("gabe.b1.slam", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 128);
                        if (done < Tune.ROLL_FREE) s = null;
                    }
                    if (s != null || done >= len - 1e-6) {
                        stage = 2;
                        st = 0;
                        hold();
                        dizzyUntil = game.now() + Tune.ROLL_DIZZY;
                        ground.furrow(start, pos.clone().setY(Plaza.FLOOR_Y), 3.0);
                        log("roll 맞음=" + hitSet.size() + " 깔린 게이브=" + crushed + " 굴린 거리=" + Math.round(done) + (s != null ? " 길 판에 막힘" : ""));
                    }
                    return false;
                }
                // 멈춤: 어지러움 → 무더기가 따라와 다시 일으켜 세움
                hold();
                st++;
                if (st <= Tune.ROLL_DIZZY) {
                    if (st % 4 == 0 && body != null) {
                        body.roll = (st / 4) % 2 == 0 ? 8 : -8;
                        body.pose(4);
                    }
                    return false;
                }
                if (st == Tune.ROLL_DIZZY + 1) {
                    rolling = false;
                    pitch = 0;
                    rodPose = ROW;
                    if (body != null) {
                        body.roll = 0;
                        body.pose(12);
                    }
                }
                hover = Tune.ROLL_H + (Tune.HOVER - Tune.ROLL_H) * ease((st - Tune.ROLL_DIZZY) / 14.0);
                return st >= Tune.ROLL_DIZZY + 14;
            }

            @Override
            protected void end() {
                if (!mine) return;
                bodyBusy = false;
                upright(8);
            }
        };
        a.range = Tune.ROLL_MAX;
        return a;
    }

    /** 구르는 몸에 깔린 땅 게이브는 죽는다(보상 없음, 사체가 옆으로 튐). 죽음 결과 종(업은, 총 주운, 사람 탈)은 사람이 잡은 것처럼(업힌 사람이 풀림). */
    private int crush(double r, Vector dir) {
        int n = 0;
        Vector c = new Vector(pos.getX(), floor() + 1.5, pos.getZ());
        for (Gabe g : game.mobs.near(c, r + 2.5, x -> x != this && !x.dead && !x.flying && !x.sp.is(Role.BOSS) && !game.director.inCage(x))) {
            if (horiz(g.pos, c) > r) continue;
            boolean result = g.sp == Species.G38 || g.sp == Species.G52 || g.sp == Species.G50;
            g.noReward = !result;
            g.reward = 0;
            double side = (g.pos.getX() - c.getX()) * -dir.getZ() + (g.pos.getZ() - c.getZ()) * dir.getX();
            Vector out = new Vector(-dir.getZ(), 0, dir.getX()).multiply(side >= 0 ? 1 : -1);
            Damage d = new Damage(Math.max(1, g.hp) + 1, DamageType.EXPLOSION, null, g.pos.clone().subtract(out), out.clone().add(new Vector(0, 0.7, 0)).normalize(),
                    "b1_roll", 0);
            try {
                g.die(d);
            } catch (RuntimeException e) {
                game.players.freeFrom(g);
                g.remove();
            }
            if (!g.dead) g.remove();
            n++;
        }
        return n;
    }

    /** 털어내기: 무더기가 크게 출렁이고 G01 여섯(단계 2부터 열)이 떨어져 달려든다(child: 보상 없음). 300틱마다, 또는 체력 10% 를 잃을 때마다. */
    private Attack shake(int from, int to) {
        return new Attack("shake", from, to, Tune.SHAKE_CD, Tune.SHAKE_TELL, Pick.NONE, false) {
            @Override
            protected boolean usable() {
                return !quiet();
            }

            @Override
            protected void start() {
                shaking = true;
                sfx("gabe.b1.shake", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f, 64);
                sfx("gabe.b1.giggle", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f, 64);
            }

            @Override
            protected boolean step() {
                if (!told()) return false;
                int n = minions(phase >= 2 ? Tune.SHAKE_N2 : Tune.SHAKE_N1), made = 0;
                double a0 = game.random.nextDouble() * Math.PI * 2, fy = heapFloor();
                for (int i = 0; i < n; i++) {
                    double a = a0 + i * Math.PI * 2 / n;
                    Vector at = new Vector(heapPos.getX() + Math.cos(a) * 5.8, fy + Species.G01.band.hover, heapPos.getZ() + Math.sin(a) * 5.8);
                    if (summon(Species.G01, at) != null) made++;
                }
                shakeMark = hpFrac();
                sfx("gabe.b1.shake", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f, 64);
                log("shake 졸개=" + made + "/" + n);
                return true;
            }

            @Override
            protected void end() {
                shaking = false;
            }
        };
    }

    /**
     * 땅울림 고리 ★: 납작 웅크렸다가 발밑에서 흰 파도 고리가 반지름 20 까지 퍼진다. 지나는 순간 땅에 있으면 18 + 위로 0.5 + 느려짐. 뛰어 넘거나,
     * 보스가 판 구덩이 안에 있으면 안 맞는다. 둘레 8블록 안에 사람이 셋 넘게 모이면 120틱마다, 아니면 220틱마다.
     */
    private Attack ring(int from, int to) {
        return new Attack("ring", from, to, Tune.RING_CD_NEAR, Tune.RING_TELL, Pick.NONE, true) {
            Tele tl;
            final Set<UUID> hitSet = new HashSet<>();
            final Set<Ward> wardSet = new HashSet<>();
            int hits, hid;
            boolean mine, holding;

            @Override
            protected boolean usable() {
                return !bodyBusy && (fightTicks >= ringReady || nearCount(Tune.RING_NEAR) >= Tune.RING_NEAR_N) && !quiet();
            }

            @Override
            protected void start() {
                mine = !bodyBusy;
                tl = null;
                if (!mine) return;
                bodyBusy = true;
                holding = true;
                hitSet.clear();
                wardSet.clear();
                hits = hid = 0;
                hold();
                rodPose = SPREAD;
                if (body != null) body.squash(1.2f, 0.6f, 1.2f, tellTicks);
                unsquashAt = -1;
                sfx("sfx.quake", Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 0.5f, 96);
            }

            @Override
            protected boolean step() {
                if (!mine) return true;
                if (t < tellTicks) {
                    hold();
                    return false;
                }
                if (t == tellTicks) {
                    tl = wave(heapAt(), Tune.RING_R0, Tune.RING_R, Tune.RING_SPEED);
                    if (body != null) body.squash(1, 1, 1, 4);
                    rodPose = ROW;
                    sfx("gabe.b1.ring", Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 0.6f, 96);
                    quake(pos, 30, 0.7);
                    hintAll(game.now() < pitUntil ? "hint_b1_pit" : "hint_bs_jump", 60);
                }
                if (t == tellTicks + 6 && holding) {
                    holding = false;
                    bodyBusy = false;
                }
                if (holding) hold();
                if (tl == null) return true;
                for (Player p : caught(tl, 0)) {
                    if (!hitSet.add(p.getUniqueId())) continue;
                    if (inPit(p)) {
                        hid++;
                        continue;
                    }
                    hits++;
                    hitBoss(p, Tune.RING_DMG, "cause_b1_ring");
                    knock(p, new Vector(0, 0, 0), 0, Tune.RING_UP);
                    game.players.slow(p, Tune.RING_SLOW, Tune.RING_SLOW_T, "boss");
                }
                for (Ward w : caughtWards(tl, 0)) if (wardSet.add(w)) hitWard(w, Tune.WARD_RING, "cause_b1_ring");
                if (!tl.gone()) return false;
                log("ring 맞음=" + hits + " 구덩이에 숨음=" + hid);
                return true;
            }

            @Override
            protected void end() {
                if (!mine) return;
                if (holding) {
                    holding = false;
                    bodyBusy = false;
                    if (body != null) body.squash(1, 1, 1, 4);
                    if (rodPose == SPREAD) rodPose = ROW;
                }
                ringReady = fightTicks + coolTicks(Tune.RING_CD);
            }
        };
    }

    /**
     * 뛰어 찍기 ★(단계 3): 내려앉을 자리에 검은 그림자 원(반지름 5)이 짙어지는 동안 낮게 웅크렸다가 무더기째 뛰어올라 찍는다. 안쪽 2.5블록 35, 원 안 20,
     * 넘어짐. 찍은 자리는 2블록 꺼진다(12초 뒤 메워짐).
     */
    private Attack hop(int from, int to) {
        Attack a = new Attack("hop", from, to, Tune.HOP_CD, Tune.HOP_TELL, Pick.TOP, true) {
            Tele tl;
            final Vector at = new Vector();
            int crouch;
            boolean mine;

            @Override
            protected boolean usable() {
                return !bodyBusy && !quiet();
            }

            @Override
            protected void start() {
                mine = !bodyBusy;
                if (!mine) return;
                bodyBusy = true;
                at.copy(aim).setY(Plaza.FLOOR_Y);
                double r = Plaza.radius(at.getX(), at.getZ());
                if (r > Tune.ROLL_EDGE) {
                    Vector c = Plaza.center(game.world).toVector();
                    at.setX(c.getX() + (at.getX() - c.getX()) * Tune.ROLL_EDGE / r).setZ(c.getZ() + (at.getZ() - c.getZ()) * Tune.ROLL_EDGE / r);
                }
                tl = shadow(at, Tune.HOP_R, tellTicks);
                crouch = Math.max(3, tellTicks / 4);
                rodPose = SPREAD;
                hold();
                if (body != null) body.squash(1.15f, 0.72f, 1.15f, crouch);
                unsquashAt = -1;
                sfx("gabe.b1.inhale", Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 0.8f, 64);
            }

            @Override
            protected boolean step() {
                if (!mine) return true;
                int T = tellTicks;
                if (t < crouch) {
                    hold();
                    turn(at, 12f);
                    return false;
                }
                if (t == crouch) {
                    rodPose = RAISE;
                    heapGlue = true;
                    if (body != null) body.squash(0.94f, 1.1f, 0.94f, 3);
                    sfx("gabe.b1.step", Sound.ENTITY_WARDEN_STEP, 1f, 0.5f, 96);
                    quake(pos, 24, 0.6);
                    dust(heapAt(), 4, 3);
                }
                if (t < T) {
                    int left = T - t;
                    vel.setX((at.getX() - pos.getX()) / left).setY(0).setZ((at.getZ() - pos.getZ()) / left);
                    double u = (t - crouch + 1) / (double) (T - crouch);
                    heapLift = Tune.HOP_HEIGHT * 4 * u * (1 - u);
                    lift(Tune.HOVER + heapLift);
                    return false;
                }
                hold();
                if (t == T) {
                    heapLift = 0;
                    lift(Tune.HOVER);
                    hopHit(tl, at);
                    if (body != null) body.squash(1.2f, 0.7f, 1.2f, 2);
                    rodPose = SPREAD;
                    return false;
                }
                if (t == T + 4 && body != null) body.squash(1, 1, 1, 6);
                if (t == T + 6) heapGlue = false;
                return t >= T + Tune.HOP_REST;
            }

            @Override
            protected void end() {
                if (!mine) return;
                bodyBusy = false;
                heapGlue = false;
                if (body != null) body.squash(1, 1, 1, 4);
                upright(8);
            }
        };
        a.range = Tune.HOP_RANGE;
        return a;
    }

    private void hopHit(Tele tl, Vector at) {
        int n = 0;
        for (Player p : caught(tl, 0.2)) {
            Vector f = p.getLocation().toVector();
            hitBoss(p, horiz(f, at) <= Tune.HOP_R_IN ? Tune.HOP_IN : Tune.HOP_OUT, "cause_b1_hop");
            rootSoon(p, Tune.HOP_ROOT);
            Vector out = f.subtract(at).setY(0);
            knock(p, out.lengthSquared() < 1e-6 ? fwd() : out, 0.5, 0.3);
            n++;
        }
        for (Ward w : caughtWards(tl, 0.2)) hitWard(w, Tune.WARD_HOP, "cause_b1_hop");
        quake(at, 30, 1.0);
        sfx("gabe.b1.slam", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 128);
        dust(at, 5.5, 6);
        int g = pit(at, Tune.HOP_R, Tune.HOP_PIT, 6);
        mend(g, 30, Tune.HOP_PIT_KEEP);
        pitUntil = game.now() + Tune.HOP_PIT_KEEP + 20;
        log("hop 맞음=" + n + " 구덩이=" + g);
    }

    /** 무리 부르기(단계 3): 무더기가 갈라지며 킥킥, 새끼 가오리 떼 하나(child). 살아 있는 떼 둘까지. */
    private Attack call(int from, int to) {
        return new Attack("call", from, to, Tune.CALL_CD, Tune.CALL_TELL, Pick.NONE, false) {
            @Override
            protected boolean usable() {
                swarms.removeIf(g -> g.dead || g.removed);
                return swarms.size() < Tune.CALL_MAX && Species.G02.implemented() && !quiet();
            }

            @Override
            protected void start() {
                shaking = true;
                sfx("gabe.b1.giggle", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f, 64);
            }

            @Override
            protected boolean step() {
                if (!told()) return false;
                Vector f = fwd();
                Vector at = new Vector(heapPos.getX() + f.getX() * 7, heapFloor() + Species.G02.band.hover + 0.6, heapPos.getZ() + f.getZ() * 7);
                Gabe g = summon(Species.G02, at);
                if (g != null) swarms.add(g);
                sfx("gabe.b1.shake", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f, 64);
                log("call 떼=" + (g != null ? 1 : 0) + " 살아 있는=" + swarms.size());
                return true;
            }

            @Override
            protected void end() {
                shaking = false;
            }
        };
    }

    /**
     * 얼굴비(회차 2부터): 무더기가 출렁인 뒤 작은 얼굴 여덟(회차만큼 더)을 초당 둘씩 사람들 머리 위로 던진다. 떨어질 자리마다 20틱 전 검은 그림자 원
     * (반지름 1.2). 맞으면 10 + 밀침. 떨어진 얼굴 열에 넷은 G01(child)로 일어나 달려든다.
     */
    private Attack toss(int from, int to) {
        return new Attack("toss", from, to, Tune.TOSS_CD, Tune.TOSS_TELL, Pick.NONE, false) {
            final List<Object[]> drops = new ArrayList<>();
            int thrown, total, hits;

            @Override
            protected boolean usable() {
                return !quiet();
            }

            @Override
            protected void start() {
                drops.clear();
                thrown = hits = 0;
                total = minions(Tune.TOSS_N);
                shaking = true;
                sfx("gabe.b1.shake", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f, 64);
                sfx("gabe.b1.giggle", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f, 64);
            }

            @Override
            protected boolean step() {
                if (t >= tellTicks && thrown < total && (t - tellTicks) % Tune.TOSS_EVERY == 0) {
                    thrown++;
                    List<Player> ps = game.mobs.standing();
                    if (!ps.isEmpty()) {
                        Location l = ps.get(game.random.nextInt(ps.size())).getLocation();
                        double a = game.random.nextDouble() * Math.PI * 2, r = game.random.nextDouble() * 3;
                        double x = l.getX() + Math.cos(a) * r, z = l.getZ() + Math.sin(a) * r, fy = game.mobs.flow.floor(x, z);
                        Vector at = new Vector(x, fy, z);
                        Tele tl = shadow(at, Tune.TOSS_R, Tune.TOSS_FALL);
                        // 엔진이 땅 몹을 틱당 0.5 씩 떨어뜨리므로 떨어지는 틱의 반만큼 위에 놓고, 그 자리 위에 붙들어 둔다(예고 자리에 떨어지게)
                        Gabe g = summon(Species.G01, new Vector(x, fy + Species.G01.band.hover + Tune.TOSS_FALL * 0.5, z));
                        if (g != null) g.lure(new Vector(x, fy + Species.G01.band.hover, z), Tune.TOSS_FALL + 2);
                        drops.add(new Object[] {at, t + Tune.TOSS_FALL, g, tl});
                    }
                }
                for (java.util.Iterator<Object[]> it = drops.iterator(); it.hasNext();) {
                    Object[] d = it.next();
                    if (t < (Integer) d[1]) continue;
                    it.remove();
                    hits += tossLand((Vector) d[0], (Gabe) d[2], (Tele) d[3]);
                }
                if (thrown < total || !drops.isEmpty()) return false;
                log("toss 던짐=" + thrown + " 맞음=" + hits);
                return true;
            }

            @Override
            protected void end() {
                shaking = false;
            }
        };
    }

    private int tossLand(Vector at, Gabe g, Tele tl) {
        int n = 0;
        for (Player p : caught(tl, 0.3)) {
            hitBoss(p, Tune.TOSS_DMG, "cause_b1_rain");
            Vector out = p.getLocation().toVector().subtract(at).setY(0);
            knock(p, out.lengthSquared() < 1e-6 ? fwd() : out, Tune.TOSS_KNOCK, 0.2);
            n++;
        }
        kr.gave.Props.soundAround(new Location(game.world, at.getX(), at.getY() + 0.5, at.getZ()), "gabe.b1.die", Sound.ENTITY_SLIME_SQUISH, 0.9f, 1f, 0.7f, 24, -1);
        // 열에 여섯은 떨어지며 터져 사체가 되고 넷은 일어나 달려든다
        if (g != null && !g.dead && game.random.nextDouble() >= Tune.TOSS_KEEP) {
            Damage d = new Damage(Math.max(1, g.hp) + 1, DamageType.EXPLOSION, null, at.clone(), new Vector(0, 1, 0), "b1_rain", 0);
            try {
                g.die(d);
            } catch (RuntimeException e) {
                g.remove();
            }
            if (!g.dead) g.remove();
        }
        return n;
    }

    // ------------------------------------------------------------------ 지킬 것(트럭) 시험

    /** 시험용 지킬 것: 트럭이 생기기 전에 '공격 testward' 로 중앙에 하나 올려 엎어지기, 구르기, 고리가 그것을 때리는지 본다(기록에 남김). */
    private final class TestWard implements Ward {
        final Vector at = Plaza.center(game.world).toVector().add(new Vector(0, 0, 6));
        double hp = 1000;

        @Override
        public String key() {
            return "test_truck";
        }

        @Override
        public Vector pos() {
            return at.clone();
        }

        @Override
        public double radius() {
            return 2.5;
        }

        @Override
        public boolean alive() {
            return hp > 0;
        }

        @Override
        public void hurt(double amount, Boss by, String cause) {
            hp -= amount;
            log(String.format(java.util.Locale.ROOT, "지킬 것 맞음 %.1f(%s) 남음 %.0f", amount, cause, hp));
        }
    }

    private Attack testWard() {
        return new Attack("testward", 1, 3, 1, 1, Pick.NONE, false) {
            @Override
            protected void start() {
                if (testW == null && bd != null) {
                    testW = new TestWard();
                    bd.guard(testW);
                    log("시험용 지킬 것을 올림 " + testW.at.getBlockX() + "," + testW.at.getBlockZ());
                }
            }

            @Override
            protected boolean step() {
                return true;
            }
        };
    }

    private boolean quiet() {
        return fightTicks < quietUntil;
    }

    /**
     * 시험 훅 '공격': 몸을 쓰는 공격(엎어지기, 구르기, 고리, 뛰기)을 고르면 도는 공격을 끊고 바로 그것을 한다(바탕은 몸이 바쁘면 그냥 끝남). 그 뒤
     * {@link #TEST_QUIET} 틱 동안 저절로 고르는 공격을 쉰다. 판 안에서는 안 불림(털어내기의 체력 덤은 super.force).
     */
    @Override
    public boolean force(String name, Player who) {
        Attack a = attack(name);
        if (a == null) return false;
        boolean bodyMove = name.equals("slam") || name.equals("roll") || name.equals("ring") || name.equals("hop");
        if (bodyMove && bodyBusy) cancelAttacks();
        quietUntil = fightTicks + TEST_QUIET;
        return super.force(name, who);
    }

    /**
     * 시험용 자리 잡기(저절로 안 고름, 시험 훅 '공격 t_front|t_side|t_pit 사람'): 그 사람을 보스 쪽에서 본 자리로 옮기고 보스 가운데를 보게 한다.
     * t_front = 보스에서 그 사람 쪽(길 판이 굴림 줄을 막지 않는 쪽으로 45도씩 돌려 봄) 16블록 땅 위, t_side = 보스 옆 11블록(엎어진 뒤통수를 옆에서), t_pit = 보스에서 그 사람 쪽 12블록에 깊이 2
     * 구덩이를 파고 그 안(고리 숨기 시험).
     */
    private Attack probe(String mode) {
        Attack a = new Attack(mode, 1, 3, 1, 1, Pick.NONE, false) {
            @Override
            protected void start() {
                Player p = target;
                if (p == null) {
                    List<Player> ps = game.mobs.standing();
                    if (ps.isEmpty()) return;
                    p = ps.get(0);
                }
                Vector me = new Vector(pos.getX(), Plaza.FLOOR_Y, pos.getZ());
                Vector d = p.getLocation().toVector().subtract(me).setY(0);
                if (d.lengthSquared() < 1e-6) d = fwd();
                d.normalize();
                if (mode.equals("t_front")) {
                    // 길 판이 굴림 줄을 막지 않는 쪽(45도씩 돌려 봄): 구르기가 끝까지 가는 것을 보게
                    for (int k = 0; k < 8; k++) {
                        Vector t = new Vector(d.getX(), 0, d.getZ()).rotateAroundY(Math.PI / 4 * ((k + 1) / 2) * (k % 2 == 0 ? 1 : -1));
                        if (ground.clearLine(me, t, 24, Tune.ROLL_W / 2 + 2)) {
                            d = t;
                            break;
                        }
                    }
                }
                Vector at = switch (mode) {
                    case "t_side" -> me.clone().add(new Vector(-fwd().getZ(), 0, fwd().getX()).multiply(-11));
                    case "t_pit" -> me.clone().add(d.clone().multiply(12));
                    default -> me.clone().add(d.clone().multiply(16));
                };
                game.players.clearSlows(p);
                Location l = new Location(game.world, Math.floor(at.getX()) + 0.5, Plaza.FLOOR_Y + 0.05, Math.floor(at.getZ()) + 0.5);
                Vector eye = l.toVector().add(new Vector(0, 1.62, 0)), look = pos.clone().subtract(eye);
                l.setYaw((float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())));
                l.setPitch((float) Math.toDegrees(-Math.atan2(look.getY(), Math.hypot(look.getX(), look.getZ()))));
                p.teleport(l);
                int g = 0;
                if (mode.equals("t_pit")) {
                    g = pit(l.toVector(), 3.5, 2, 4);
                    mend(g, 20, 300);
                }
                log("시험 자리 " + mode + " " + l.getBlockX() + "," + l.getBlockZ() + (g != 0 ? " 구덩이=" + g : ""));
            }

            @Override
            protected boolean step() {
                return true;
            }
        };
        a.auto = false;
        return a;
    }

    private void dropTestWard() {
        if (testW != null && bd != null) bd.unguard(testW);
        testW = null;
    }

    // ------------------------------------------------------------------ 죽음, 전멸

    @Override
    protected void dyingStep(int t) {
        clock++;
        ground.tick(clock);
        hold();
        if (t == 1) {
            upright(4);
            lying = false;
            // 눈을 감는다: 벌어졌던 얼굴이 도로 닫힌다
            if (state.equals("split")) state("crack");
            if (body != null) body.look.open = false;
            rodPose = FOLD;
            shaking = true;
            sfx("gabe.b1.roar", Sound.ENTITY_RAVAGER_ROAR, 1f, 0.4f, 160);
        }
        if (t <= 40) {
            // 머리가 뒤로 넘어간다
            pitch = (float) (-100 * ease(t / 40.0));
            hover = Tune.HOVER - 1.3 * (t / 40.0);
        }
        if (t == 44) burst();
        if (t > 44) heapScale = Math.max(0.5, heapScale - 0.06);
        heapTick();
        if (clock % BEAT == 0) beat();
    }

    /** 무더기가 터진다: 작은 사체 열여섯이 사방으로 날리고 그 자리에 흙 둔덕이 남는다(쉬는 시간 전까지). */
    private void burst() {
        Vector c = heapAt();
        int made = 0;
        for (int i = 0; i < Tune.BURST_N; i++) {
            double a = i * Math.PI * 2 / Tune.BURST_N + game.random.nextDouble() * 0.3, r = 1.5 + game.random.nextDouble() * 3.5;
            Vector at = new Vector(c.getX() + Math.cos(a) * r, c.getY() + 0.8 + game.random.nextDouble() * 2.2, c.getZ() + Math.sin(a) * r);
            Gabe g = summon(Species.G01, at);
            if (g == null) continue;
            made++;
            Vector out = new Vector(Math.cos(a), 0.55 + game.random.nextDouble() * 0.5, Math.sin(a)).normalize();
            Damage d = new Damage(Math.max(1, g.hp) + 1, DamageType.EXPLOSION, null, c.clone().add(new Vector(0, 1.5, 0)), out, "b1_burst", 0);
            try {
                g.die(d);
            } catch (RuntimeException e) {
                g.remove();
            }
            if (!g.dead) g.remove();
        }
        sfx("gabe.b1.fall", Sound.ENTITY_WITHER_DEATH, 1f, 0.5f, 160);
        sfx("gabe.b1.giggle", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f, 64);
        quake(c, 56, 1.0);
        dust(c, 6, 8);
        int g = mound(c, 5, 2, 20);
        log("무더기 터짐 사체=" + made + " 둔덕=" + g);
    }

    @Override
    protected void beforeCorpse() {
        dropTestWard();
    }

    @Override
    public void remove() {
        dropTestWard();
        super.remove();
    }

    /** 전멸: 중앙 원 가운데로 걸어가 보급 상자 위에 엎어진다. 무더기 얼굴들이 일제히 킥킥. */
    @Override
    public void victory() {
        cancelAttacks();
        clearTeles();
        won = true;
        wonT = 0;
        if (body != null) body.look.open = false;
    }

    private void victoryTick() {
        wonT++;
        Vector c = Plaza.center(game.world).toVector();
        double d = horiz(c, pos);
        if (!wonDown) {
            turn(c, 6f);
            if (d > 4.5 && wonT < 110) {
                double v = Math.max(0.12, d / Math.max(1, 100 - wonT));
                Vector dir = c.clone().subtract(pos).setY(0).normalize().multiply(Math.min(v, d));
                vel.setX(dir.getX()).setY(0).setZ(dir.getZ());
                if (wonT % 14 == 0) {
                    sfx("gabe.b1.step", Sound.ENTITY_WARDEN_STEP, 1f, 0.5f, 96);
                    quake(heapAt(), 30, 0.5);
                }
            } else {
                wonDown = true;
                hold();
                pitch = 90;
                hover = Tune.LIE;
                lying = true;
                rodPose = FOLD;
                shaking = true;
                if (body != null) body.pose(8);
                sfx("gabe.b1.slam", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 128);
                quake(pos, 40, 1.0);
            }
        } else {
            hold();
            if (wonT % 28 == 0) sfx("gabe.b1.giggle", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f, 64);
        }
    }

    @Override
    public String status() {
        return super.status() + String.format(java.util.Locale.ROOT, " | b1 st=%s y=%.1f pitch=%.0f heap=%.1f,%.1f loose=%d lying=%d back=%d dizzy=%d busy=%d cut=%d %s", state,
                pos.getY() - floor(), pitch, heapPos.getX(), heapPos.getZ(), heapLoose ? 1 : 0, lying ? 1 : 0, slammed ? 1 : 0, game.now() < dizzyUntil ? 1 : 0,
                bodyBusy ? 1 : 0, cutCount(), ground.status())
                + String.format(java.util.Locale.ROOT, " dealt[face=%.0f heap=%.0f back=%.0f eye=%.0f rods=%.0f]", main.dealt, heapR.dealt, backR.dealt, eyeR.dealt,
                        rodR[0].dealt + rodR[1].dealt + rodR[2].dealt + rodR[3].dealt);
    }
}
