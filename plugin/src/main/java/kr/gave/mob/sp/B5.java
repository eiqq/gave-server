package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Corpse;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.mob.boss.Boss;
import kr.gave.mob.boss.Tele;
import kr.gave.mob.boss.Ward;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * B5 모두의 게이브(35웨이브, 마지막 보스. docs/모듈/보스.md 16절): 쓰러뜨린 게이브와 죽은 동료의 머리를 모아 일어선 26블록 거인.
 * 판 내내 실눈이던 큰 얼굴이 3단계에 처음으로 눈을 뜨고, 4단계에는 몸통이 무너져 머리가 팔로 기어 오며 입속의 작은 진짜 얼굴이 마지막 약점이 된다.
 * 잡으면 엔딩(연출은 바탕 BossDirector).
 *
 * <p>몸(팩 tools/gabe/sp/b5.py, 몸 배율 {@link #S}): 닻 = 머리(gabe/b5: "", open, maw), 부품 = 팔 둘(b5_arm), 몸통 무더기 셋(b5_heap), 뿔 둘(b5_horn),
 * 허리 고리 얼굴 여덟(b5_ring), 이마 눈(b5_eye), 죽은 사람 머리 여덟까지({@link B5Souls}), 4단계 입속 작은 얼굴(gabe/g01), 그때그때 떨어지는 얼굴(b5_drop),
 * 빛 원뿔과 빛 자리(b5_beam, b5_spot). 맵 연출은 {@link B5Land}.
 *
 * <p>앞 보스들의 기술(얼굴비, 고리와 손뼉 파도, 이마 눈의 바라봄)은 그 보스 클래스에 기대지 않고 바탕(Boss, Tele, Terrain)의 도구로 여기서 따로 짰다.
 * 숫자는 모두 {@link N} 한곳에 있다(난이도 배율을 맞출 때 여기만 고친다).
 */
public final class B5 extends Boss {

    /** 숫자 표(피해는 체력 100, 방어 없음 기준. 회차와 화남 배율은 바탕 hitBoss 가 곱한다). */
    static final class N {
        // 흐름
        static int INTRO = 200, DYING = 60, STUN = 60;
        static double R_START = 50, R_P1 = 20, R_P3 = 14, R_HUG = 6, R_BACK = 14;
        static double WALK = 0.05, DRIFT = 0.04, STEP3 = 0.08, CRAWL = 0.025, RETREAT = 0.12, WIN = 0.2;
        static int STEP_EVERY = 40;
        // 팔 내려찍기(1~3단계)
        // SLAM_WARD: 트럭(지킬 것)을 겨누는 몫, SLAM_WARD_DMG: 트럭에 주는 값(사람 피해 단위, 트럭은 x0.004 = 6%)
        static double SLAM_DMG = 50, SLAM_UP = 0.7, SLAM_W = 3.5, SLAM_LEN = 22, SLAM_RANGE = 24, SLAM_WARD = 0.2, SLAM_WARD_DMG = 15;
        static int SLAM_TELL = 32, SLAM_CD = 180, SLAM_LIE = 50, SLAM_ROOT = 20;
        static double ARM_HP = 0.06, ARM_LYING_MUL = 2.0;
        // 삼킴(1~3단계)
        static double GULP_R = 12, GULP_HEAL = 0.004, GULP_CAP = 0.15;
        static int GULP_N = 3, GULP_CD = 120, GULP_TELL = 16;
        // 얼굴비(1단계, 회차 3부터 모든 단계)
        static double RAIN_DMG = 12, RAIN_R = 1.5, RAIN_KNOCK = 0.4, RAIN_G01 = 0.4, RAIN_SPREAD = 2.6;
        static int RAIN_TELL = 40, RAIN_TICKS = 60, RAIN_EVERY = 7, RAIN_FALL = 20, RAIN_CD = 360;
        // 허리 고리(2단계에 풀림)
        static double RING_R = 26, RING_BAND = 2.6, RING_FACE = 3.6, RING_PUSH = 0.6, RING_DMG = 8, RING_HP = 0.02, RING_SPIN = 0.075;
        static double CLAP_DMG = 20, CLAP_UP = 0.4, CLAP_SPEED = 0.7;
        static int CLAP_TELL = 24, CLAP_CD = 260, RING_BITS = 2, RING_OUT = 60;
        // 이마 눈(2, 3단계)
        static double GAZE_R = 6, GAZE_SPEED = 0.15, GAZE_FILL = 60, GAZE_DRAIN = 120, GAZE_SHUT = 0.04, EYE_MUL = 2.5;
        static int GAZE_TELL = 40, GAZE_TICKS = 160, GAZE_CD = 320, GAZE_CD3 = 240, GAZE_TURN = 100, GAZE_ROOT = 60;
        // 큰 숨(3단계)
        static double BREATH_R = 30, BREATH_PULL = 0.1, BREATH_DMG = 30, BREATH_KNOCK = 1.0, BREATH_SPEED = 0.8;
        static int BREATH_TELL = 60, BREATH_CD = 400;
        // 감싸기(4단계)
        // HUG_WARD: 감싸기가 트럭에 주는 값(트럭 x0.004 = 12%)
        static double HUG_R = 12, HUG_DMG = 80, HUG_HEAL = 0.05, HUG_WARD = 30;
        static int HUG_TELL = 60, HUG_ROOT = 40;
        // 받는 배율
        static double TORSO_MUL = 0.8, TINY_MUL = 4.0, REST_MUL = 0.25;
        // 3단계 '눈을 떴다'
        static int OPEN_TICKS = 400, SKY = 17500, SKY_OPEN = 18000;
        static double OPEN_BUFF = 1.25, OPEN_DIM = 0.5;
        // 맵: 발자국, 찍힌 자리, 사체 더미, 금, 투광등, 엔진 분사
        static double PRINT_R = 2.6, PILE_R = 3.0, PILE_GAP = 9, CRACK_R = 28, PILE_AT = 18.0;
        static int PRINT_KEEP = 500, MARK_KEEP = 400, PILE_H = 5, PILE_MAX = 6;
        // 투광등 내리치기(등 자체, 발전기, 꺼진 사분면은 공용 kr.gave.trip.PadLights): 닿는 거리, 예고, 다시 쓰기
        static double LAMP_REACH = 27;
        static int LAMP_TELL = 36, LAMP_CD = 500;
        static double ENGINE_SPOT_R = 20, ENGINE_SPOT = 3, ENGINE_REACH = 22, ENGINE_ARC = 60, ENGINE_HP = 0.04, ENGINE_DMG = 30, ENGINE_BOSS_PAD = 4;
        static int ENGINE_PEOPLE = 3, ENGINE_HOLD = 100, ENGINE_WARN = 30, ENGINE_BURN = 30, ENGINE_CD = 1800, ENGINE_FIRST = 300;
        // 발사대 감싸기(원정.md 10절, 1~3단계): 팔 하나가 발사대(트럭, 지킬 것)를 감아 쥐고 초마다 WRAP_DMG(트럭 x0.004 = 1.6%/초)를 WRAP_HOLD 틱까지.
        // 그 팔을 끊으면(부위 체력 ARM_HP, 감은 동안 x ARM_LYING_MUL) 풀린다. 다가오는 팔 띠에 선 사람은 SLAM_DMG x WRAP_HIT
        static double WRAP_DMG = 4, WRAP_RANGE = 30, WRAP_HIT = 0.5;
        static int WRAP_TELL = 30, WRAP_HOLD = 200, WRAP_CD = 600;

        private N() {
        }
    }

    /** 몸 배율(머리 폭 1.6 x 6.5 = 10.4블록), 컬링 상자(폭, 높이). */
    static final float S = 6.5f, BOX_W = 52f, BOX_H = 50f;
    /** 머리 가운데 높이: 선 채, 땅속에서 솟기 전, 4단계 기어 올 때. */
    static final double HOVER = 20.8, HOVER_BURIED = 3.0, HOVER_CRAWL = 5.0;
    /** 팔 모델 길이(b5.py: 6 x 0.28 + 5 x 0.25 + 0.45), 굵기 배율(세계 길이 16블록일 때의 배율). */
    static final float ARM_LEN = 3.38f, ARM_K = 16f / ARM_LEN / S;
    /** 몸통 무더기 셋: x, y, z(몸 틀), 크기 배율, 돌림(도). 아래부터. */
    static final float[][] HEAP = {{0.04f, -2.754f, -0.03f, 1.12f, 25f}, {-0.05f, -1.862f, 0.03f, 1.0f, 140f}, {0.02f, -0.969f, 0f, 0.9f, 250f}};
    static final float HEAP_K = 4.0f / S, SHOULDER_X = 0.71f, SHOULDER_Y = -0.815f, RING_Y = -1.862f, RING_RAD = 0.8f, RING_K = 3f / S;
    static final float EYE_Y = 0.47f, FACE_Z = 0.6f, TINY_Y = -0.5f;

    // ------------------------------------------------------------------ 부품

    /** 사슬 팔 하나. 자세는 뿌리와 끝(몸 틀, 몸 배율 1 단위)으로 준다: 모델(-Y 로 ARM_LEN)을 그 선에 맞춰 돌리고 늘인다. */
    final class Arm {
        final int side;
        final Region rg;
        Body.Part part;
        boolean cut, down;
        int readyAt;
        final Vector3f root = new Vector3f(), tip = new Vector3f();

        Arm(int side) {
            this.side = side;
            rg = region(side > 0 ? "arm_l" : "arm_r", 1.0);
            rg.hpFrac = N.ARM_HP;
            rg.onCut = r -> cutArm(this);
        }

        void pose(float rx, float ry, float rz, float tx, float ty, float tz, int ticks) {
            root.set(rx, ry, rz);
            tip.set(tx, ty, tz);
            dirtySpheres();
            if (part == null || part.d == null) return;
            Vector3f d = new Vector3f(tip).sub(root);
            float len = d.length();
            if (len < 1e-4f) {
                d.set(0, -1, 0);
                len = 1e-4f;
            } else d.div(len);
            Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, -1, 0), d);
            part.pose(new Matrix4f().translate(root).rotate(q).scale(ARM_K, cut ? ARM_K : len / ARM_LEN, ARM_K), ticks);
        }

        /** 늘어뜨린 자세. */
        void rest(int ticks) {
            down = false;
            rg.mul = phase >= 4 ? N.REST_MUL : 1.0;
            state("");
            pose(side * SHOULDER_X, SHOULDER_Y, 0, side * (SHOULDER_X + 0.38f), SHOULDER_Y - 2.2f, 0.28f, ticks);
        }

        void raise(int ticks) {
            state("raise");
            if (part != null) part.look.open = true;
            pose(side * SHOULDER_X, SHOULDER_Y, 0, side * (SHOULDER_X + 0.45f), SHOULDER_Y + 2.1f, 0.5f, ticks);
        }

        void state(String s) {
            if (part != null && !cut) part.state(s);
            if (part != null && s.isEmpty()) part.look.open = false;
        }

        boolean free() {
            return !cut && !down && part != null;
        }
    }

    /** 허리 고리 얼굴 하나. */
    final class Face {
        final int i;
        final Region rg;
        Body.Part part;
        boolean cut;
        /** 풀린 뒤 세계 자리(구, 벽 판정). */
        double wx, wy, wz;

        Face(int i) {
            this.i = i;
            rg = region("ring" + i, 1.0);
            rg.hpFrac = N.RING_HP;
            rg.onCut = r -> breakFace(this);
        }
    }

    /** 내려찍기 한 번(팔, 띠). */
    private static final class Strike {
        Arm arm;
        Vector from, to;
        Tele tl;
    }

    /** 등장 때 사방에서 모여드는 얼굴 하나. */
    private static final class Flyer {
        Body.Part part;
        int born;
        Matrix4f to;
    }

    /** 얼굴비로 떨어지는 얼굴 하나. */
    private static final class Drop {
        Vector at;
        int born, landAt;
        Tele shadow;
        Body.Part part;
        boolean sent;
    }

    final Arm[] arms = new Arm[2];
    final Face[] ring = new Face[8];
    final Body.Part[] heaps = new Body.Part[3], horns = new Body.Part[2];
    Body.Part eye, tiny, beam, spot;
    final Region headRg, torsoRg, eyeRg, tinyRg;
    final B5Souls souls = new B5Souls(this);
    final B5Land map = new B5Land(this);
    private final List<Drop> drops = new ArrayList<>();
    private final List<Flyer> flyers = new ArrayList<>();
    private final Map<UUID, Double> gauge = new HashMap<>();
    private final Map<UUID, Long> bumpAt = new HashMap<>();

    /** 등장 자리 각(가운데에서 본 라디안), 솟음 0..1. */
    double homeAng;
    private double rise;
    private boolean headShown, collapsed, crawling, retreating, winning, eyeOpen;
    /** 고리: 풀렸나, 풀린 뒤 반지름(0 → RING_R), 돌아간 각(도), 풀리기 시작한 싸움 틱. */
    boolean ringOut;
    private double ringR, ringSpin;
    private int ringOutAt = -1;
    /** 삼켜서 찬 체력(이 단계), 기절 끝, 돌지 않는 끝, 눈 뜬 떼 버프 끝(싸움 틱). */
    private double gulped;
    private int stunUntil, turnLock, openUntil, stepAt, steps, winT;
    private double eyeDealt0;
    private float poseYaw = Float.NaN;
    private int soulAt;

    public B5(Game game, Species sp) {
        super(game, sp, new double[] {0.70, 0.40, 0.12});
        flying = false;
        hover = HOVER_BURIED;
        baseSpeed = N.WALK;
        radius = 5.0;
        ragHold = 600;
        ragKick = 0.08;
        quietEntrance = true;
        ownRoar = true;
        headRg = main;
        torsoRg = region("torso", N.TORSO_MUL);
        arms[0] = new Arm(1);
        arms[1] = new Arm(-1);
        for (int i = 0; i < ring.length; i++) ring[i] = new Face(i);
        eyeRg = region("eye", N.EYE_MUL);
        eyeRg.weak = true;
        tinyRg = region("tiny", N.TINY_MUL);
        tinyRg.weak = true;
        // 회차 3부터는 처음부터 모든 기술(17절)
        int all = cycle >= 3 ? 1 : 0;
        attacks.add(slam());
        attacks.add(gulp());
        attacks.add(rain(all > 0 ? 3 : 1));
        attacks.add(lampSmash());
        attacks.add(wrap());
        attacks.add(clap());
        attacks.add(gaze("gaze", all > 0 ? 1 : 2, 2, N.GAZE_CD));
        attacks.add(gaze("gaze3", 3, 3, N.GAZE_CD3));
        attacks.add(breath(all > 0 ? 1 : 3));
        attacks.add(hug());
        // 시험 훅 '보스 x 공격 <이름>' 으로만: 바로 R 20 에 서기, 분사 부채꼴 안에 서기, 엔진 바로 쓰기
        attacks.add(probe("t_walk"));
        attacks.add(probe("t_fan"));
        attacks.add(probe("t_engine"));
        attacks.add(probe("t_clear"));
        attacks.add(probe("t_fly"));
        attacks.add(probe("t_noward"));
    }

    /** 시험용(저절로 안 고름): 긴 걷기와 꾀어내기를 건너뛰어 3분 대본으로 맵 장치를 볼 수 있게 한다. t_clear 는 보스 말고 살아 있는 게이브를 모두 치운다(한두 명 시험에서 사람이 떼에 쓰러지지 않게). */
    private Attack probe(String name) {
        Attack a = new Attack(name, 1, 4, 1, 1, Pick.NONE, false) {
            @Override
            protected void start() {
                double r = phase >= 3 ? N.R_P3 : N.R_P1;
                if (name.equals("t_walk")) place(r, B5Land.angOf(pos));
                else if (name.equals("t_fan")) place(Math.min(r, 18), map.nozzle);
                else if (name.equals("t_engine")) map.engineNow();
                else if (name.equals("t_fly")) flyT = 20;
                else if (name.equals("t_noward")) noWard = true;
                else clearHorde();
            }

            private void place(double r, double ang) {
                double x = Plaza.CX + 0.5 + Math.cos(ang) * r, z = Plaza.CZ + 0.5 + Math.sin(ang) * r;
                pos.setX(x).setZ(z).setY(game.mobs.flow.floor(x, z) + hover);
                yaw = (float) Math.toDegrees(Math.atan2(-(Plaza.CX + 0.5 - x), Plaza.CZ + 0.5 - z));
                if (body != null) body.pose(0);
            }

            @Override
            protected boolean step() {
                return true;
            }
        };
        a.auto = false;
        return a;
    }

    // ------------------------------------------------------------------ 몸

    @Override
    protected void build() {
        // 머리는 등장 끝에 올라선다: 그때까지 닻은 빈 모델
        body = Body.of(this, Body.EMPTY, BOX_W, BOX_H);
        body.scale(S, 0);
        body.viewRange(4.0f);
        body.turnTicks = 5;
        // 붙이는 차례 = 사체 조각 차례(닻 + 11): 팔 둘, 몸통 셋, 뿔 둘, 고리 여덟
        for (Arm a : arms) {
            a.part = body.attach("gabe/b5_arm");
            if (a.part != null) a.part.pose(new Matrix4f().translate(a.side * SHOULDER_X, SHOULDER_Y, 0).scale(0.01f), 0);
            a.root.set(a.side * SHOULDER_X, SHOULDER_Y, 0);
            a.tip.set(a.side * SHOULDER_X, SHOULDER_Y - 0.1f, 0);
        }
        for (int i = 0; i < 3; i++) {
            heaps[i] = body.attach("gabe/b5_heap");
            if (heaps[i] != null) {
                heaps[i].look.freeze = true;
                heaps[i].pose(heapPose(i, 1f), 0);
            }
        }
        for (int i = 0; i < 2; i++) {
            horns[i] = body.attach("gabe/b5_horn");
            if (horns[i] != null) horns[i].pose(hornPose(i, 0.01f), 0);
        }
        for (Face f : ring) {
            f.part = body.attach("gabe/b5_ring");
            if (f.part != null) {
                f.part.look.phase = f.i * 29;
                f.part.pose(ringPose(f), 0);
            }
        }
        eye = body.attach("gabe/b5_eye");
        if (eye != null) eye.pose(new Matrix4f().translate(0, EYE_Y, FACE_Z + 0.004f).scale(0.01f), 0);
        yaw = (float) Math.toDegrees(Math.atan2(-(Plaza.CX + 0.5 - pos.getX()), Plaza.CZ + 0.5 - pos.getZ()));
        homeAng = B5Land.angOf(pos);
    }

    private static Matrix4f heapPose(int i, float grow) {
        float[] h = HEAP[i];
        return new Matrix4f().translate(h[0], h[1], h[2]).rotateY((float) Math.toRadians(h[4])).scale(HEAP_K * h[3] * grow);
    }

    private static Matrix4f hornPose(int i, float k) {
        Matrix4f m = new Matrix4f().translate(i == 0 ? 0.52f : -0.52f, 0.74f, -0.25f);
        if (i == 1) m.rotateY((float) Math.PI);
        return m.scale(6f / S * k);
    }

    private Matrix4f ringPose(Face f) {
        double a = Math.toRadians(ringSpin + f.i * 45.0);
        return new Matrix4f().translate((float) (Math.sin(a) * RING_RAD), RING_Y, (float) (Math.cos(a) * RING_RAD)).rotateY((float) a).scale(RING_K);
    }

    /** 닻에서 먼 부품(풀린 고리, 떨어지는 얼굴, 빛 자리)은 컬링 상자를 넓힌다(안 그러면 보스가 화면 밖일 때 같이 사라짐). */
    private static void wide(Body.Part p) {
        wide(p, 150f);
    }

    private static void wide(Body.Part p, float w) {
        if (p == null || p.d == null) return;
        p.d.setDisplayWidth(w);
        p.d.setDisplayHeight(BOX_H + 10f);
    }

    /** 등장 때 모여드는 얼굴: 출발 반지름(경기장 가운데에서), 몇 틱마다 하나, 동시에 몇, 언제까지 내보냄, 오는 데 걸리는 틱. */
    static final double FLY_R0 = 22, FLY_R1 = 64;
    static final int FLY_EVERY = 2, FLY_MAX = 18, FLY_UNTIL = 110, FLY_TICKS = 30;

    @Override
    public Vector spawnPoint(Vector suggested) {
        double a = B5Land.angOf(suggested);
        double x = Plaza.CX + 0.5 + Math.cos(a) * N.R_START, z = Plaza.CZ + 0.5 + Math.sin(a) * N.R_START;
        return new Vector(x, game.mobs.flow.floor(x, z) + hover, z);
    }

    // 등장 큰 제목은 고요가 끝나는 t=180 에 스스로 띄운다: 놓는 틱(t=0)에 바탕이 띄우지 않게 그때만 키를 비운다
    @Override
    public String nameKey() {
        return intro && introT == 0 ? null : super.nameKey();
    }

    @Override
    public String titleKey() {
        return intro && introT == 0 ? null : super.titleKey();
    }

    @Override
    public String subKey() {
        return intro && introT == 0 ? null : super.subKey();
    }

    @Override
    public int skyTime() {
        return N.SKY;
    }

    @Override
    public double shadowRadius() {
        return 9;
    }

    // ------------------------------------------------------------------ 좌표 도우미

    /** 발밑(바닥 높이). */
    Vector ground() {
        return new Vector(pos.getX(), floor(), pos.getZ());
    }

    private double floorAt(double x, double z) {
        return game.mobs.flow.floor(x, z);
    }

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** 몸 왼쪽(+X). */
    private Vector left() {
        Vector f = forward();
        return new Vector(f.getZ(), 0, -f.getX());
    }

    /** 세계 점 → 몸 틀(몸 배율 1 단위). world() 의 거꾸로(pitch, roll 0 일 때). */
    private Vector3f toLocal(double wx, double wy, double wz) {
        double dx = wx - pos.getX(), dy = wy - pos.getY(), dz = wz - pos.getZ();
        double yr = Math.toRadians(-yaw), c = Math.cos(yr), s = Math.sin(yr);
        return new Vector3f((float) ((dx * c - dz * s) / S), (float) (dy / S), (float) ((dx * s + dz * c) / S));
    }

    private Vector worldV(double lx, double ly, double lz) {
        double[] w = world(lx, ly, lz, S);
        return new Vector(w[0], w[1], w[2]);
    }

    private Location locOf(Vector v) {
        return new Location(game.world, v.getX(), v.getY(), v.getZ());
    }

    private static Vector arena() {
        return new Vector(Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
    }

    private Vector mouth() {
        return worldV(0, TINY_Y, FACE_Z + 0.05);
    }

    private Vector eyePos() {
        return worldV(0, EYE_Y, FACE_Z + 0.08);
    }

    // ------------------------------------------------------------------ 맞는 구

    @Override
    protected void refreshSpheres() {
        sphere(headRg, pos.getX(), pos.getY(), pos.getZ(), 4.8);
        if (!collapsed) {
            for (float[] h : HEAP) {
                double[] w = world(h[0], h[1], h[2], S);
                sphere(torsoRg, w[0], w[1], w[2], 4.0 * h[3]);
            }
        }
        for (Arm a : arms) {
            if (a.part == null) continue;
            int n = a.cut ? 1 : 4;
            for (int i = 0; i < n; i++) {
                float f = a.cut ? 0.12f : 0.14f + i * 0.26f;
                double[] w = world(a.root.x + (a.tip.x - a.root.x) * f, a.root.y + (a.tip.y - a.root.y) * f, a.root.z + (a.tip.z - a.root.z) * f, S);
                sphere(a.rg, w[0], w[1], w[2], 1.4);
            }
        }
        for (Face f : ring) {
            if (f.cut || f.part == null) continue;
            if (ringOut && ringR > 8) sphere(f.rg, f.wx, f.wy, f.wz, 2.8);
            else {
                double a = Math.toRadians(ringSpin + f.i * 45.0);
                double[] w = world(Math.sin(a) * RING_RAD, RING_Y, Math.cos(a) * RING_RAD, S);
                sphere(f.rg, w[0], w[1], w[2], 1.4);
            }
        }
        if (eyeOpen) {
            double[] w = world(0, EYE_Y, FACE_Z + 0.1, S);
            sphere(eyeRg, w[0], w[1], w[2], 1.5);
        }
        if (tiny != null) {
            double[] w = world(0, TINY_Y, FACE_Z + 0.12, S);
            sphere(tinyRg, w[0], w[1], w[2], 0.6);
        }
    }

    // ------------------------------------------------------------------ 등장(판에서 가장 긴 고요)

    @Override
    public int introLength() {
        return N.INTRO;
    }

    @Override
    protected void introStep(int t) {
        long t0 = System.nanoTime();
        try {
            rising(t);
        } finally {
            cost(t0);
        }
    }

    private void rising(int t) {
        hold();
        Vector g = ground();
        if (t == 1) {
            // 모든 소리가 멎는다: 곡을 조용히, 떼는 멈춰 이쪽을 본다
            game.music.hush(true);
            map.lamps();
        }
        // 땅 바꾸기는 틱을 나눈다(한 틱에 몰면 게임 안 18~38ms): 분사구 쪽 그을음 셋도 한 틱에 하나
        if (t == 4) map.engine(homeAng);
        if (t >= 4 && t <= 8 && t % 2 == 0) map.engineMark((t - 4) / 2);
        if (t <= 80) {
            for (Gabe o : game.mobs.all()) {
                if (!mob(o) || o.flying) continue;
                o.lure(o.pos.clone(), 2);
                game.mobs.face(o, pos);
            }
        }
        if (t >= 20 && t % 30 == 20) {
            float v = Math.min(1f, 0.35f + t / 300f);
            sfxAll("gabe.b5.heart", Sound.ENTITY_WARDEN_HEARTBEAT, v, 0.5f, 18);
            quake(g, 96, 0.2 + t / 500.0);
        }
        if (t >= 20 && t < 80) gather(g, t);
        if (t >= 20 && t < FLY_UNTIL + FLY_TICKS + 4) flyers(g, t);
        if (t == 80) sfxAll("gabe.b5.choir", Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 0.9f, 0.5f, 20);
        if (t == 80 || t == 86) {
            // 빨려 오다 쌓인 사체 더미 둘(중앙 원 건너편 둘레, 한 틱에 하나): 그 뒤에 붙어 서면 이마 눈의 빛과 탄이 가려진다
            double a = homeAng + Math.PI + (t == 80 ? -0.6 : 0.6);
            map.pile(new Vector(Plaza.CX + 0.5 + Math.cos(a) * N.PILE_AT, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * N.PILE_AT));
        }
        // 80~180: 몸통 무더기가 땅에서 하나씩 솟아 쌓인다(머리 높이가 오르고 아래 무더기가 차례로 드러남)
        if (t > 80 && t <= 180) {
            rise = (t - 80) / 100.0;
            double e = rise * rise * (3 - 2 * rise);
            hover = HOVER_BURIED + (HOVER - HOVER_BURIED) * e;
            if (t % 25 == 5) {
                quake(g, 80, 0.5 + rise * 0.4);
                sfx("sfx.quake", Sound.ENTITY_WARDEN_EMERGE, 0.9f, 0.5f, 128);
            }
        }
        if (t == 140) for (Arm a : arms) a.rest(26);
        // 죽은 사람들의 머리가 하나씩 박힌다
        if (t >= 120 && t <= 190 && t % 9 == 0) addSoul();
        if (t == 166) showHead();
        if (t == 180) {
            hover = HOVER;
            sfxAll("gabe.b5.roar", Sound.ENTITY_WARDEN_ROAR, 1f, 0.5f, 24);
            game.horizon.shake(1.0, 30);
            quake(g, 120, 1.0);
            game.music.hush(false);
            game.hud.bossTitle(kr.gave.game.Hud.hasPhrase(titleKey()) ? titleKey() : nameKey(), subKey(), 90);
        }
        souls.tick();
        if (t == N.INTRO) {
            stepAt = N.STEP_EVERY;
            if (cycle >= 2) releaseRing();
            if (cycle >= 3) enrage();
        }
    }

    /** 사체가 빨려 오고(분홍 줄 + 치움), 40블록 안 떼가 걸어 들어와 사라진다. */
    private void gather(Vector g, int t) {
        List<Corpse> cs = game.mobs.corpses();
        int left = Math.max(1, 80 - t), take = Math.max(1, (int) Math.ceil(cs.size() / (double) left));
        Vector up = g.clone().add(new Vector(0, 2.5, 0));
        int n = 0;
        for (Corpse c : new ArrayList<>(cs)) {
            if (n >= take) break;
            if (c.gone()) continue;
            if (n < 2) {
                try {
                    game.shotFx.streak(locOf(c.pos.clone().add(new Vector(0, 0.5, 0))), locOf(up));
                } catch (RuntimeException e) {
                    // 효과 예산이 없으면 줄 없이
                }
            }
            game.mobs.eat(c);
            n++;
        }
        for (Gabe o : game.mobs.all()) {
            if (!edible(o) || horiz(o.pos, g) > 40) continue;
            if (horiz(o.pos, g) < 4.5) {
                swallow(o, up, false);
                continue;
            }
            o.lure(g, 3);
        }
    }

    /**
     * 경기장 둘레 사방(가운데에서 R FLY_R0..FLY_R1, 사람들 뒤와 옆)에서 작은 얼굴들이 땅을 스치며 보스 발밑으로 모여든다(남은 사체가 없어도 '모은다'가 보이게).
     * 길이 모두 보스로 모이므로 보스를 보는 사람 눈앞을 지나간다. FLY_EVERY 틱에 하나, FLY_TICKS 틱에 걸쳐 와서 묻힌다.
     */
    private void flyers(Vector g, int t) {
        for (Iterator<Flyer> it = flyers.iterator(); it.hasNext();) {
            Flyer f = it.next();
            if (f.to != null && t >= f.born + 2) {
                f.part.pose(f.to, FLY_TICKS);
                f.to = null;
            }
            if (t >= f.born + FLY_TICKS + 3) {
                f.part.remove();
                it.remove();
            }
        }
        if (t >= FLY_UNTIL || t % FLY_EVERY != 0 || flyers.size() >= FLY_MAX || body == null) return;
        Body.Part p = body.attach("gabe/b5_drop");
        if (p == null) return;
        Vector c = arena();
        double a = game.random.nextDouble() * Math.PI * 2, r = FLY_R0 + game.random.nextDouble() * (FLY_R1 - FLY_R0);
        double x = c.getX() + Math.cos(a) * r, z = c.getZ() + Math.sin(a) * r;
        p.follow = false;
        p.look.palette = Look.PAL_PALE;
        // 닻에서 120블록 넘게 떨어진 데서 출발하므로 컬링 상자를 더 넓힌다
        wide(p, 260f);
        // 가운데(보스 자리)를 등지지 않게: 얼굴이 가는 쪽을 본다
        float fy = (float) Math.atan2(g.getX() - x, g.getZ() - z);
        // 보스를 보는 사람 눈높이 위로 지나가게(하늘을 배경으로 보이게) 조금 띄우고 크게
        p.pose(new Matrix4f().translate((float) (x - pos.getX()), (float) (floorAt(x, z) + 2.0 + game.random.nextDouble() * 4.0 - pos.getY()), (float) (z - pos.getZ()))
                .rotateY(fy).scale(4.5f), 0);
        Flyer f = new Flyer();
        f.part = p;
        f.born = t;
        f.to = new Matrix4f().translate((float) (g.getX() - pos.getX()), (float) (g.getY() + 0.6 - pos.getY()), (float) (g.getZ() - pos.getZ())).rotateY(fy)
                .scale(0.6f);
        flyers.add(f);
    }

    /** 시험 t_clear: 보스 말고 살아 있는 게이브를 보상 없이 치운다(먹힌 사람은 풀려남). */
    private void clearHorde() {
        for (Gabe o : new ArrayList<>(game.mobs.all())) {
            if (!mob(o)) continue;
            // 먼저 풀어 준다(noReward 를 먼저 켜면 원정 판에서 갇힌 사람이 '정리로 치워짐'으로 죽음 처리됨)
            game.players.freeFrom(o);
            o.noReward = true;
            o.reward = 0;
            o.leaveCorpse = false;
            o.remove();
        }
    }

    private void clearFlyers() {
        for (Flyer f : flyers) f.part.remove();
        flyers.clear();
    }

    private void showHead() {
        if (headShown) return;
        headShown = true;
        body.model("gabe/b5");
        for (int i = 0; i < 2; i++) if (horns[i] != null) horns[i].pose(hornPose(i, 1f), 12);
        if (eye != null) eye.pose(new Matrix4f().translate(0, EYE_Y, FACE_Z + 0.004f), 12);
        sfx("sfx.stomp", Sound.ENTITY_RAVAGER_STEP, 1f, 0.4f, 128);
    }

    private void addSoul() {
        if (souls.size() >= B5Souls.MAX) return;
        List<PState> w = souls.waiting();
        if (w.isEmpty()) return;
        if (souls.add(w.get(0))) {
            Vector at = worldV(0, -1.2, 0.7);
            Props.soundAround(locOf(at), "gabe.b5.whisper", Sound.ENTITY_WARDEN_LISTENING, 0.9f, 1f, 0.8f, 48, -1);
        }
    }

    // ------------------------------------------------------------------ 싸움

    @Override
    protected void act() {
        long t0 = System.nanoTime();
        try {
            fight();
        } finally {
            cost(t0);
        }
    }

    /** 내 틱 비용(나노초, 최근 200틱: 시험 상태 b5ms= 평균/최대). */
    private final long[] costNs = new long[200];
    private int costAt;

    /** 지금까지 가장 비싼 한 번(나노초)과 그때(i 등장 틱, f 싸움 틱, 단계). */
    private long costTop;
    private String costTopAt = "-";

    private void cost(long t0) {
        long ns = System.nanoTime() - t0;
        costNs[costAt] = ns;
        costAt = (costAt + 1) % costNs.length;
        if (ns > costTop) {
            costTop = ns;
            costTopAt = (intro ? "i" + introT : "f" + fightTicks) + "p" + phase + "[" + acting() + "]";
        }
    }

    private String costText() {
        long sum = 0, max = 0;
        int n = 0;
        for (long v : costNs) {
            if (v <= 0) continue;
            sum += v;
            max = Math.max(max, v);
            n++;
        }
        return n == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.3f/%.2f top=%.2f@%s", sum / 1e6 / n, max / 1e6, costTop / 1e6, costTopAt);
    }

    /** 시험 t_fly: 싸움 중에 등장의 모여드는 얼굴을 다시 돌림(등장 틱처럼 20부터 셈, -1 이면 꺼짐). */
    private int flyT = -1;
    /** 시험 t_noward: 내려찍기가 트럭을 겨누지 않게(사람 피해만 볼 때). */
    private boolean noWard;

    private void fight() {
        Vector g = ground();
        if (flyT >= 0) {
            flyers(g, flyT++);
            if (flyT > FLY_UNTIL + FLY_TICKS + 4) {
                clearFlyers();
                flyT = -1;
            }
        }
        if (winning) {
            winStep(g);
            return;
        }
        if (!headShown) {
            // 등장을 건너뛴 경우(시험)에도 몸이 다 서 있게
            hover = HOVER;
            showHead();
            for (Arm a : arms) if (a.free()) a.rest(10);
        }
        if (fightTicks % 20 == 7) addSoul();
        souls.tick();
        horde();
        map.tick();
        ringTick(g);
        dropTick();
        boolean stunned = fightTicks < stunUntil;
        move(g, stunned);
        if (!stunned) runAttacks();
        if (phase >= 3 && fightTicks % 70 == 0) whisper();
        if (fightTicks % 100 == 50) sfx("gabe.b5.choir", Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 0.7f, 0.5f, 192);
        if (tiny != null) tinyTick();
        // 느리게 도는 거인: 2도 넘게 돌았으면 5틱마다 5틱 보간으로 자세를 보낸다(15도씩 끊겨 돌지 않게)
        if (fightTicks % 5 == 0 && (Float.isNaN(poseYaw) || Math.abs(yaw - poseYaw) > 2f)) {
            poseYaw = yaw;
            body.pose(5);
        }
    }

    private void move(Vector g, boolean stunned) {
        double r = B5Land.radOf(g);
        boolean armDown = arms[0].down || arms[1].down;
        boolean busy = stunned || armDown || fightTicks < turnLock || inTransition();
        if (crawling) {
            crawl(g, r, busy);
            return;
        }
        double want = phase >= 3 ? N.R_P3 : N.R_P1;
        Vector c = crowdCenter();
        if (!busy) turnTo(c.clone().setY(pos.getY()), 0.8f);
        if (busy || big()) {
            hold();
            return;
        }
        if (r > want + 0.3) {
            // 가운데로 걸어 들어온다
            Vector to = arena().setY(pos.getY());
            seek(to, phase >= 3 ? N.STEP3 : N.WALK);
            walkBeat(g);
        } else {
            // 서서 사람들이 있는 쪽으로 둘레를 따라 천천히 옮겨 선다
            double ca = B5Land.angOf(c), ba = B5Land.angOf(g), diff = B5Land.wrap(ca - ba);
            if (B5Land.radOf(c) < 3 || Math.abs(diff) < 0.12) {
                hold();
                return;
            }
            double na = ba + Math.signum(diff) * Math.min(Math.abs(diff), N.DRIFT / Math.max(1, want));
            Vector to = new Vector(Plaza.CX + 0.5 + Math.cos(na) * want, pos.getY(), Plaza.CZ + 0.5 + Math.sin(na) * want);
            seek(to, N.DRIFT);
            if (fightTicks % (N.STEP_EVERY * 2) == 0) walkBeat(g);
        }
    }

    /** ★ 공격(얼굴비, 눈, 숨, 손뼉) 가운데 서서 하는 것이 도는 중인가. */
    private boolean big() {
        for (Attack a : running) if (a.big && !a.name.equals("clap")) return true;
        return false;
    }

    /** 걸음: 땅울림, 쿵, 두 걸음에 한 번 발자국이 꺼진다. */
    private void walkBeat(Vector g) {
        if (fightTicks < stepAt) return;
        stepAt = fightTicks + N.STEP_EVERY;
        steps++;
        quake(g, 56, 0.7);
        sfx("sfx.stomp", Sound.ENTITY_RAVAGER_STEP, 1f, 0.45f, 96);
        hover = HOVER - 0.5;
        game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
            if (!dead && !collapsed) hover = HOVER;
        }, 8);
        if (steps % 2 == 0) {
            Vector back = forward().multiply(-3.2).add(left().multiply(steps % 4 == 0 ? 2.2 : -2.2));
            map.footprint(g.clone().add(back));
        }
    }

    /** 4단계: 머리가 팔로 땅을 끌며 가운데로 기어 온다. R_HUG 에 닿으면 감싸기, 그 뒤 R_BACK 으로 물러났다가 다시. */
    private void crawl(Vector g, double r, boolean busy) {
        Vector c = arena().setY(pos.getY());
        if (!busy) turnTo(c, 1.0f);
        boolean hugging = false;
        for (Attack a : running) if (a.name.equals("hug")) hugging = true;
        if (busy || hugging) {
            hold();
            return;
        }
        if (retreating) {
            if (r >= N.R_BACK) {
                retreating = false;
                hold();
                return;
            }
            Vector out = g.clone().subtract(arena()).setY(0);
            if (out.lengthSquared() < 1e-6) out = new Vector(Math.cos(homeAng), 0, Math.sin(homeAng));
            seek(pos.clone().add(out.normalize().multiply(2)), N.RETREAT);
            return;
        }
        if (r <= N.R_HUG) {
            hold();
            force("hug", null);
            return;
        }
        seek(c, N.CRAWL);
        // 팔을 번갈아 앞으로 뻗어 땅을 끈다
        if (fightTicks % 20 == 0) {
            int k = (fightTicks / 20) & 1;
            for (int i = 0; i < 2; i++) {
                Arm a = arms[i];
                if (!a.free()) continue;
                boolean reach = i == k;
                a.state(reach ? "raise" : "grab");
                a.pose(a.side * 0.72f, -0.55f, 0.2f, a.side * (reach ? 0.95f : 1.25f), -0.72f, reach ? 2.3f : 1.2f, 18);
            }
            if (k == 0) {
                quake(g, 40, 0.5);
                sfx("sfx.stomp", Sound.ENTITY_RAVAGER_STEP, 0.8f, 0.6f, 64);
            }
        }
    }

    /** 감독 떼에 거는 것: 3단계 '눈을 떴다' 뒤 OPEN_TICKS 동안 x OPEN_BUFF(꺼진 등 사분면의 떼 빠르기는 PadLights 가 따로 건다). */
    private void horde() {
        if (fightTicks >= openUntil) return;
        for (Gabe o : game.mobs.all()) if (mob(o)) o.speedBuff = Math.max(o.speedBuff, N.OPEN_BUFF);
    }

    private void whisper() {
        if (souls.size() == 0) return;
        Vector at = collapsed ? pos.clone() : worldV(0, -1.3, 0.7);
        for (Player p : game.mobs.standing()) {
            if (horiz(p.getLocation().toVector(), at) > 16) continue;
            // 웅크리면(듣기) 크게 들린다
            Props.soundTo(p, locOf(at), "gabe.b5.whisper", Sound.ENTITY_WARDEN_LISTENING, p.isSneaking() ? 1f : 0.4f, 1f, 0.8f, -1);
        }
    }

    // ------------------------------------------------------------------ 단계

    @Override
    protected void onPhase(int n) {
        gulped = 0;
        Vector g = ground();
        sfxAll("gabe.b5.roar", Sound.ENTITY_WARDEN_ROAR, 1f, 0.5f, 24);
        quake(g, 96, 0.9);
        closeEye();
        if (n == 2) releaseRing();
        if (n == 3) {
            if (!ringOut) releaseRing();
            openEyes();
        }
        if (n == 4) {
            if (!ringOut) releaseRing();
            if (!"open".equals(state) && !"maw".equals(state)) openEyes();
            collapse(g);
        }
    }

    /** 2단계 '두른다': 허리 고리 여덟이 풀려 커지며 경기장 가운데 둘레 R 26 고리가 된다. */
    private void releaseRing() {
        if (ringOut) return;
        ringOut = true;
        ringOutAt = fightTicks;
        ringR = 0;
        float yr = (float) Math.toRadians(-yaw);
        for (Face f : ring) {
            if (f.cut || f.part == null) continue;
            // 몸 틀에서 세계 축으로 바꾸되 지금 자리 그대로(이어서 보간으로 날아감)
            Matrix4f now = new Matrix4f().rotateY(yr).scale(S).mul(ringPose(f));
            f.part.follow = false;
            f.part.pose(now, 0);
            wide(f.part);
        }
        sfx("sfx.horde_cry", Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 1f, 0.6f, 128);
    }

    /** 3단계 '눈을 떴다': 판 전체에서 큰 얼굴이 처음으로 눈을 뜬다. 살아 있는 모든 게이브가 가장 가까운 사람을 찍어 눈을 뜬다. */
    private void openEyes() {
        state("open");
        for (Body.Part h : heaps) if (h != null) h.state("open");
        sfxAll("gabe.b5.open", Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 1f, 0.5f, 20);
        game.horizon.shake(1.0, 40);
        sky(N.SKY_OPEN, 40);
        fog(3);
        // 원정 테마가 하늘색을 덮으므로(세계 시각은 해 자리만 바꿈) 눈을 뜬 동안 테마를 한 단계 더 어둡게: 꺼진 투광등이 더 아프다
        themeDim(N.OPEN_DIM, N.OPEN_TICKS);
        // 이 판의 큰 사건: 마지막 단계(4)를 기다리지 않고 여기서 boss_final 로 넘긴다(16절)
        game.music.bossFinal(true);
        openUntil = fightTicks + N.OPEN_TICKS;
        for (Gabe o : new ArrayList<>(game.mobs.all())) {
            if (!mob(o)) continue;
            Player q = game.mobs.nearestPlayer(o.pos, 400);
            if (q == null) continue;
            try {
                o.mark(q);
            } catch (RuntimeException e) {
                // 그 종이 mark 를 못 받으면 건너뜀
            }
        }
        map.cracks();
        hintAll("hint_b5_cover", 80);
    }

    /** 원정 테마(kr.gave.trip.Theme)를 ticks 동안 amount 만큼 더 어둡게. 원정이 꺼졌거나 테마 부품이 없으면 아무 일 없음. */
    private void themeDim(double amount, int ticks) {
        try {
            kr.gave.trip.Theme th = game.trip == null ? null : game.trip.part(kr.gave.trip.Theme.class);
            if (th != null) th.dim(amount, ticks);
        } catch (RuntimeException | LinkageError e) {
            // 테마가 없으면 세계 시각과 안개만
        }
    }

    /** ticks 틱 뒤 한 번(보스가 그사이 사라졌거나 죽음 연출에 들었으면 안 함). 무거운 땅 바꾸기를 틱에 나눌 때. */
    private void later(int ticks, Runnable r) {
        game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
            if (removed || dead || dying) return;
            long t0 = System.nanoTime();
            try {
                r.run();
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[보스] B5 나중 일 예외", e);
            } finally {
                cost(t0);
            }
        }, ticks);
    }

    /** 4단계 '마지막 얼굴': 몸통 무더기가 무너지고(작은 얼굴 떼가 터져 나옴) 머리가 땅에 떨어져 기어 온다. 입속에 작은 진짜 얼굴. */
    private void collapse(Vector g) {
        if (collapsed) return;
        collapsed = true;
        crawling = true;
        cancelDrops();
        // 무너진 무더기에서 작은 얼굴 떼 둘이 터져 나온다(회차만큼 더)
        for (int k = 0; k < minions(2); k++) {
            double[] w = world(HEAP[k % 3][0], HEAP[k % 3][1], HEAP[k % 3][2], S);
            Vector at = new Vector(w[0] + (game.random.nextDouble() - 0.5) * 6, floorAt(w[0], w[2]) + 1.5 + k, w[2] + (game.random.nextDouble() - 0.5) * 6);
            // 떼 종은 디스플레이가 많아 한 틱에 둘을 낳으면 무겁다(게임 안 4단계 전이 31ms): 몇 틱씩 띄운다
            later(2 + 4 * k, () -> summon(Species.G02.implemented() ? Species.G02 : Species.G01, at));
        }
        for (int i = 0; i < 3; i++) {
            if (heaps[i] != null) heaps[i].remove();
            heaps[i] = null;
        }
        // 몸통이 서 있던 자리는 사체 더미가 되고, 기어 올 길이 도랑으로 파인다. 땅 바꾸기는 무거워서(게임 안 한 틱 28ms) 틱을 나눈다
        Vector in = arena().subtract(g).setY(0);
        Vector pileAt = in.lengthSquared() > 1e-6 ? g.clone().subtract(in.normalize().multiply(10)) : null;
        Vector from = g.clone();
        later(6, () -> map.lastStand(from));
        if (pileAt != null) later(14, () -> map.pile(pileAt));
        hover = HOVER_CRAWL;
        state("maw");
        headRg.mul = N.REST_MUL;
        for (Arm a : arms) a.rg.mul = N.REST_MUL;
        for (Face f : ring) f.rg.mul = N.REST_MUL;
        souls.toCrown();
        if (Look.has("gabe/g01")) {
            tiny = body.attach("gabe/g01");
            if (tiny != null) {
                tiny.look.phase = 7;
                tiny.pose(new Matrix4f().translate(0, TINY_Y, FACE_Z + 0.02f).scale(0.01f), 0);
                Body.Part ref = tiny;
                game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
                    if (ref.d != null && ref.d.isValid()) ref.pose(new Matrix4f().translate(0, TINY_Y, FACE_Z + 0.1f).scale(1f / S), 20);
                }, 12);
            }
        }
        for (Arm a : arms) if (a.free()) a.pose(a.side * 0.72f, -0.55f, 0.2f, a.side * 1.2f, -0.72f, 1.6f, 20);
        sfx("gabe.b5.slam", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 128);
        game.horizon.shake(1.0, 30);
        hintAll("hint_bs_tiny", 80);
        dirtySpheres();
    }

    private void tinyTick() {
        if (fightTicks % 40 == 0) game.hud.point(null, worldV(0, TINY_Y, FACE_Z + 0.12), 45);
        if (fightTicks % 46 == 0) sound("giggle", Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1.4f, 24);
    }

    // ------------------------------------------------------------------ 팔: 내려찍기, 끊김

    private Arm freeArm(Vector toward) {
        Arm a = arms[0].free() && fightTicks >= arms[0].readyAt ? arms[0] : null, c = arms[1].free() && fightTicks >= arms[1].readyAt ? arms[1] : null;
        if (a == null || c == null) return a != null ? a : c;
        if (toward == null) return game.random.nextBoolean() ? a : c;
        // 표적 쪽 팔
        return toward.clone().subtract(pos).setY(0).dot(left()) >= 0 ? a : c;
    }

    private Strike strike(Arm a, Vector to, int tell) {
        // 띠는 그 팔의 어깨 밑에서 표적을 지나게 겨눈다(몸 가운데에서 겨누고 옆으로 옮기면 가만히 선 표적이 띠 밖에 남음: 게임 안에서 찾음)
        Vector shoulder = ground().add(left().multiply(a.side * 2.4));
        Vector dir = to.clone().subtract(shoulder).setY(0);
        if (dir.lengthSquared() < 1e-6) dir = forward();
        dir.normalize();
        Strike s = new Strike();
        s.arm = a;
        s.from = shoulder.clone().add(dir.clone().multiply(2.0));
        s.to = s.from.clone().add(dir.clone().multiply(N.SLAM_LEN));
        s.tl = strip(s.from, s.to, N.SLAM_W, tell);
        a.raise(Math.max(4, Math.min(20, tell - 8)));
        return s;
    }

    /** 팔이 띠 위로 내려찍힌다. 맞은 사람과 지킬 것, 땅울림, 찍힌 자리의 금과 구덩이. 팔은 SLAM_LIE 틱 누워 있다(그동안 x2). */
    private void landStrike(Strike s, double dmg) {
        Arm a = s.arm;
        if (!a.cut && a.part != null) {
            Vector3f r = toLocal(s.from.getX(), floorAt(s.from.getX(), s.from.getZ()) + 1.0, s.from.getZ());
            Vector3f t = toLocal(s.to.getX(), floorAt(s.to.getX(), s.to.getZ()) + 1.0, s.to.getZ());
            a.state("slam");
            a.down = true;
            a.rg.mul = (phase >= 4 ? N.REST_MUL : 1.0) * N.ARM_LYING_MUL;
            a.pose(r.x, r.y, r.z, t.x, t.y, t.z, 3);
        }
        for (Player p : caught(s.tl, 0.3)) {
            hitBoss(p, dmg, "cause_b5_arm");
            knock(p, new Vector(0, 0, 0), 0, N.SLAM_UP);
            game.players.root(p, N.SLAM_ROOT, "boss");
        }
        for (Ward w : caughtWards(s.tl, 0)) hitWard(w, N.SLAM_WARD_DMG, "cause_b5_arm");
        Vector mid = s.from.clone().add(s.to).multiply(0.5);
        quake(mid, 44, 1.0);
        Props.soundAround(locOf(mid.clone().add(new Vector(0, 1, 0))), "gabe.b5.slam", Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f, 0.5f, 128, -1);
        map.strike(s.from, s.to);
        turnLock = Math.max(turnLock, fightTicks + N.SLAM_LIE + 4);
    }

    private Attack slam() {
        Attack at = new Attack("slam", 1, 3, 50, N.SLAM_TELL, Pick.TOP, false) {
            final List<Strike> hits = new ArrayList<>(2);

            @Override
            protected boolean usable() {
                return !crawling && freeArm(null) != null;
            }

            @Override
            protected void start() {
                hits.clear();
                Vector to = aim.clone();
                Ward w = !noWard && game.random.nextDouble() < N.SLAM_WARD ? B5.this.ward() : null;
                if (w != null && w.pos() != null && horiz(w.pos(), pos) <= N.SLAM_RANGE + 4) to = w.pos().clone();
                Arm a = freeArm(to);
                if (a == null) return;
                hits.add(strike(a, to, tellTicks));
                // 3단계는 두 팔 함께: 다른 팔은 다른 사람(없으면 28도 벌린 쪽)
                Arm o = a == arms[0] ? arms[1] : arms[0];
                if (phase >= 3 && o.free()) {
                    Player q = null;
                    double bd = N.SLAM_RANGE;
                    for (Player p : game.mobs.standing()) {
                        if (p == target) continue;
                        double d = horiz(p.getLocation().toVector(), pos);
                        if (d < bd) {
                            bd = d;
                            q = p;
                        }
                    }
                    Vector to2;
                    if (q != null) {
                        to2 = q.getLocation().toVector();
                        aimMark(q, tellTicks + 10);
                    } else {
                        Vector d = to.clone().subtract(ground()).setY(0);
                        double ang = Math.toRadians(o.side > 0 ? -28 : 28), c = Math.cos(ang), sn = Math.sin(ang);
                        to2 = ground().add(new Vector(d.getX() * c - d.getZ() * sn, 0, d.getX() * sn + d.getZ() * c));
                    }
                    hits.add(strike(o, to2, tellTicks));
                }
                sfx("gabe.b5.inhale", Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.6f, 0.5f, 96);
            }

            @Override
            protected boolean step() {
                if (hits.isEmpty()) return true;
                if (t == tellTicks) for (Strike s : hits) landStrike(s, N.SLAM_DMG);
                if (t == tellTicks + N.SLAM_LIE) for (Strike s : hits) if (!s.arm.cut) s.arm.rest(20);
                return t >= tellTicks + N.SLAM_LIE + 20;
            }

            @Override
            protected void end() {
                for (Strike s : hits) {
                    if (!s.arm.cut && (s.arm.down || t < tellTicks + N.SLAM_LIE)) s.arm.rest(16);
                    s.arm.readyAt = fightTicks + coolTicks(N.SLAM_CD);
                }
                hits.clear();
            }
        };
        at.range = N.SLAM_RANGE;
        return at;
    }

    private void aimMark(Player p, int ticks) {
        if (p != null) game.hud.mark(p, sp.key(), ticks);
    }

    /** 팔이 끊겼다: 그 팔로는 못 찍고, 삼킴이 느려진다. */
    private void cutArm(Arm a) {
        if (a.cut) return;
        a.down = false;
        if (a.part != null) {
            a.part.state("cut");
            a.part.look.open = false;
        }
        a.cut = true;
        a.pose(a.side * SHOULDER_X, SHOULDER_Y, 0, a.side * (SHOULDER_X + 0.1f), SHOULDER_Y - 0.8f, 0, 6);
        Vector at = worldV(a.side * SHOULDER_X, SHOULDER_Y - 0.6, 0);
        quake(at, 40, 0.8);
        Props.soundAround(locOf(at), "gabe.b5.slam", Sound.ENTITY_RAVAGER_STUNNED, 1f, 1f, 0.6f, 128, -1);
        try {
            game.shotFx.bloodHit(locOf(at), new Vector(a.side, -0.3, 0), true);
        } catch (RuntimeException e) {
            // 효과 예산이 없으면 넘어감
        }
        stunUntil = Math.max(stunUntil, fightTicks + 30);
    }

    // ------------------------------------------------------------------ 삼킴

    /** 살아 있는 떼 개체인가(보스, 대기실 우리 개체 빼고). */
    private boolean mob(Gabe o) {
        return o != this && !o.dead && !o.sp.is(Role.BOSS) && !game.director.inCage(o);
    }

    /** 삼킬 수 있는 감독 떼: 고리에서 걸어온 낱개 땅 종(파생, 미리 깔린 것, 보스, 하늘 종, 사람을 잡은 것은 뺌). */
    private boolean edible(Gabe o) {
        if (!mob(o) || o.removed || o.untargetable || o.disguised || o.flying || o.body == null || o.noReward) return false;
        if (o.sp.spawn != Species.Spawn.RING) return false;
        if (!game.players.trappedIn(o).isEmpty()) return false;
        for (PState s : game.players.all()) if (s.grabbedBy == o || s.headBy == o || s.deathMob == o) return false;
        return true;
    }

    private List<Gabe> prey(int n) {
        Vector g = ground();
        List<Gabe> out = new ArrayList<>();
        for (Gabe o : game.mobs.all()) if (edible(o) && horiz(o.pos, g) <= N.GULP_R) out.add(o);
        out.sort((x, y) -> Double.compare(horiz(x.pos, g), horiz(y.pos, g)));
        while (out.size() > n) out.remove(out.size() - 1);
        return out;
    }

    /** 게이브 하나를 삼킨다(분홍 줄, 치움). heal 이면 체력이 찬다. */
    private void swallow(Gabe o, Vector to, boolean heal) {
        try {
            game.shotFx.streak(locOf(o.pos), locOf(to));
        } catch (RuntimeException e) {
            // 효과 예산이 없으면 줄 없이
        }
        o.noReward = true;
        o.reward = 0;
        o.leaveCorpse = false;
        o.remove();
        if (heal) {
            double before = hp;
            healBy(N.GULP_HEAL);
            gulped += hp - before;
        }
    }

    /** 체력이 찬다(지금 단계의 윗문턱 바로 아래까지만: 단계는 되돌아가지 않는다). */
    private void healBy(double frac) {
        double cap = (phase >= 2 ? cuts[phase - 2] - 0.005 : 1.0) * maxHp;
        if (hp >= cap) return;
        hp = Math.min(cap, hp + frac * maxHp);
    }

    private Attack gulp() {
        return new Attack("gulp", 1, 3, N.GULP_CD, N.GULP_TELL, Pick.NONE, false) {
            List<Gabe> food = new ArrayList<>();
            Arm a;

            @Override
            protected boolean usable() {
                return !crawling && gulped < N.GULP_CAP * maxHp && hpFrac() < 0.995 && !prey(1).isEmpty();
            }

            @Override
            protected void start() {
                food = prey(N.GULP_N);
                a = freeArm(food.isEmpty() ? null : food.get(0).pos);
                if (a != null && !food.isEmpty()) {
                    Vector v = food.get(0).pos;
                    Vector3f t = toLocal(v.getX(), v.getY(), v.getZ());
                    a.state("grab");
                    a.pose(a.side * SHOULDER_X, SHOULDER_Y, 0, t.x, t.y, t.z, Math.max(4, tellTicks - 2));
                }
            }

            @Override
            protected boolean step() {
                if (t == tellTicks) {
                    Vector m = mouth();
                    int n = 0;
                    Vector first = null;
                    for (Gabe o : food) {
                        if (!edible(o)) continue;
                        if (first == null) first = o.pos.clone();
                        swallow(o, m, true);
                        n++;
                    }
                    if (n > 0) {
                        sound("gulp", Sound.ENTITY_RAVAGER_ATTACK, 0.6f, 64);
                        if (first != null) map.pile(first);
                    }
                    if (a != null && a.free()) a.pose(a.side * SHOULDER_X, SHOULDER_Y, 0, a.side * 0.2f, TINY_Y, FACE_Z + 0.2f, 8);
                }
                // 팔이 끊겼으면 느리다
                return t >= tellTicks + (arms[0].cut || arms[1].cut ? 50 : 20);
            }

            @Override
            protected void end() {
                if (a != null && a.free()) a.rest(14);
                a = null;
                food.clear();
            }
        };
    }

    // ------------------------------------------------------------------ 얼굴비

    private Attack rain(int to) {
        return new Attack("rain", 1, to, N.RAIN_CD, N.RAIN_TELL, Pick.NONE, true) {
            @Override
            protected boolean usable() {
                return !crawling;
            }

            @Override
            protected void start() {
                // 몸통 무더기가 부풀었다 꺼지며 얼굴을 털어 올린다
                for (int i = 0; i < 3; i++) if (heaps[i] != null) heaps[i].pose(heapPose(i, 1.12f), Math.max(6, tellTicks / 2));
                sfx("sfx.horde_cry", Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 0.9f, 0.7f, 128);
            }

            @Override
            protected boolean step() {
                if (t == tellTicks) for (int i = 0; i < 3; i++) if (heaps[i] != null) heaps[i].pose(heapPose(i, 1f), 8);
                if (t >= tellTicks && t < tellTicks + N.RAIN_TICKS && (t - tellTicks) % N.RAIN_EVERY == 0) dropFace();
                return t >= tellTicks + N.RAIN_TICKS;
            }
        };
    }

    private void dropFace() {
        List<Player> ps = game.mobs.standing();
        if (ps.isEmpty() || drops.size() >= 10 || body == null) return;
        Player p = ps.get(game.random.nextInt(ps.size()));
        Vector at = p.getLocation().toVector().add(new Vector(game.random.nextGaussian() * N.RAIN_SPREAD, 0, game.random.nextGaussian() * N.RAIN_SPREAD));
        at.setY(floorAt(at.getX(), at.getZ()));
        Drop d = new Drop();
        d.at = at;
        d.born = fightTicks;
        d.landAt = fightTicks + N.RAIN_FALL;
        d.shadow = shadow(at, 1.2, N.RAIN_FALL);
        d.part = body.attach("gabe/b5_drop");
        if (d.part != null) {
            d.part.follow = false;
            wide(d.part);
            // 머리 높이에서(보스 쪽으로 조금 치우친 위) 그 자리로 떨어진다
            Vector from = at.clone().add(pos.clone().subtract(at).setY(0).multiply(0.25));
            d.part.pose(new Matrix4f().translate((float) (from.getX() - pos.getX()), (float) (at.getY() + 24 - pos.getY()), (float) (from.getZ() - pos.getZ()))
                    .scale(2.6f), 0);
        }
        drops.add(d);
    }

    private void dropTick() {
        if (drops.isEmpty()) return;
        for (Iterator<Drop> it = drops.iterator(); it.hasNext();) {
            Drop d = it.next();
            if (!d.sent && fightTicks >= d.born + 2 && d.part != null) {
                d.sent = true;
                d.part.pose(new Matrix4f().translate((float) (d.at.getX() - pos.getX()), (float) (d.at.getY() + 0.7 - pos.getY()), (float) (d.at.getZ() - pos.getZ()))
                        .scale(2.6f), Math.max(2, d.landAt - fightTicks));
            }
            if (fightTicks < d.landAt) continue;
            it.remove();
            if (d.part != null) d.part.remove();
            for (Player p : game.mobs.standing()) {
                Vector f = p.getLocation().toVector();
                if (horiz(f, d.at) > N.RAIN_R || Math.abs(f.getY() - d.at.getY()) > 3) continue;
                hitBoss(p, N.RAIN_DMG, "cause_b5_rain");
                Vector out = f.clone().subtract(d.at).setY(0);
                knock(p, out.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : out, N.RAIN_KNOCK, 0.2);
            }
            Props.soundAround(locOf(d.at), "gabe.b5.gulp", Sound.ENTITY_SLIME_SQUISH, 0.8f, 1f, 1.2f, 24, -1);
            if (game.random.nextDouble() < N.RAIN_G01) summon(Species.G01, d.at.clone().add(new Vector(0, 1.0, 0)));
        }
    }

    private void cancelDrops() {
        clearFlyers();
        for (Drop d : drops) {
            if (d.part != null) d.part.remove();
            if (d.shadow != null) d.shadow.remove();
        }
        drops.clear();
    }

    // ------------------------------------------------------------------ 발사대 감싸기(원정.md 10절)

    /** 팔 하나가 발사대(지킬 것)를 감아 쥔다: 쥐고 있는 동안 트럭이 깎이고, 그 팔이 금빛 약점(누운 팔 x2)이 된다. 팔을 끊으면 풀린다. 지킬 것이 없으면 안 쓴다. */
    private Attack wrap() {
        return new Attack("wrap", 1, 3, N.WRAP_CD, N.WRAP_TELL, Pick.NONE, false) {
            Arm a;
            Ward w;
            Tele tl;

            @Override
            protected boolean usable() {
                if (crawling) return false;
                Ward x = B5.this.ward();
                return x != null && x.alive() && x.pos() != null && horiz(x.pos(), pos) <= N.WRAP_RANGE && freeArm(x.pos()) != null;
            }

            @Override
            protected void start() {
                w = B5.this.ward();
                a = w == null || w.pos() == null ? null : freeArm(w.pos());
                tl = null;
                if (a == null) return;
                Vector sh = ground().add(left().multiply(a.side * 2.4));
                tl = strip(sh, w.pos().clone().setY(sh.getY()), N.SLAM_W, tellTicks);
                a.state("grab");
                if (a.part != null) a.part.look.open = true;
                a.pose(a.side * SHOULDER_X, SHOULDER_Y, 0, a.side * (SHOULDER_X + 0.4f), SHOULDER_Y + 1.6f, 0.6f, Math.max(4, tellTicks - 6));
                sfx("gabe.b5.inhale", Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.8f, 0.45f, 128);
            }

            @Override
            protected boolean step() {
                if (a == null || w == null || a.cut) return true;
                Vector c = w.pos();
                if (c == null) return true;
                if (t == tellTicks) {
                    Vector3f tp = toLocal(c.getX(), floorAt(c.getX(), c.getZ()) + 1.6, c.getZ());
                    a.down = true;
                    a.rg.mul = N.ARM_LYING_MUL;
                    a.state("grab");
                    a.pose(a.side * SHOULDER_X, SHOULDER_Y, 0, tp.x, tp.y, tp.z, 6);
                    for (Player p : caught(tl, 0.3)) {
                        hitBoss(p, N.SLAM_DMG * N.WRAP_HIT, "cause_b5_arm");
                        knock(p, new Vector(0, 0, 0), 0, N.SLAM_UP * 0.6);
                    }
                    quake(c, 44, 0.9);
                    Props.soundAround(locOf(c.clone().add(new Vector(0, 1, 0))), "gabe.b5.slam", Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f, 0.5f, 128, -1);
                    hintAll("hint_bs_weak", 60);
                }
                if (t <= tellTicks) return false;
                int k = t - tellTicks;
                // 쥔 동안 몸은 그 자리에 붙들려 있다(돌지도 걷지도 않음)
                turnLock = Math.max(turnLock, fightTicks + 4);
                if (k % 20 == 0) {
                    if (!w.alive()) return true;
                    hitWard(w, N.WRAP_DMG, "cause_b5_arm");
                    quake(c, 24, 0.3);
                }
                // 끊을 곳: 쥔 팔 가운데를 금빛 화살촉으로
                if (k % 30 == 1) reveal(worldV(a.root.x + (a.tip.x - a.root.x) * 0.55, a.root.y + (a.tip.y - a.root.y) * 0.55, a.root.z + (a.tip.z - a.root.z) * 0.55), 34);
                return k >= N.WRAP_HOLD;
            }

            @Override
            protected void end() {
                if (a != null) {
                    if (!a.cut) a.rest(16);
                    a.readyAt = fightTicks + coolTicks(N.SLAM_CD);
                }
                a = null;
                w = null;
                tl = null;
            }
        };
    }

    // ------------------------------------------------------------------ 투광등 내리치기

    private Attack lampSmash() {
        return new Attack("lamp", 1, 3, N.LAMP_CD, N.LAMP_TELL, Pick.NONE, false) {
            Mobs.Light lamp;
            Strike s;

            @Override
            protected boolean usable() {
                return !crawling && fightTicks > 200 && freeArm(null) != null && map.litNear(ground(), N.LAMP_REACH) != null;
            }

            @Override
            protected void start() {
                lamp = map.litNear(ground(), N.LAMP_REACH);
                if (lamp == null) lamp = map.litNear(ground(), 60);
                s = null;
                Arm a = lamp == null ? null : freeArm(B5Land.baseOf(lamp));
                if (a == null) return;
                // 띠가 등을 지나 조금 더 뻗는다
                Vector base = B5Land.baseOf(lamp);
                s = strike(a, base, tellTicks);
                Vector dir = base.clone().subtract(s.from).setY(0);
                double len = dir.length() + 3;
                if (len > 1e-3 && s.tl != null) {
                    s.tl.remove();
                    s.to = s.from.clone().add(dir.normalize().multiply(len));
                    s.tl = strip(s.from, s.to, N.SLAM_W, tellTicks);
                }
            }

            @Override
            protected boolean step() {
                if (s == null) return true;
                if (t == tellTicks) {
                    landStrike(s, N.SLAM_DMG);
                    map.smash(lamp);
                }
                if (t == tellTicks + N.SLAM_LIE && !s.arm.cut) s.arm.rest(20);
                return t >= tellTicks + N.SLAM_LIE + 20;
            }

            @Override
            protected void end() {
                if (s != null) {
                    if (!s.arm.cut && (s.arm.down || t < tellTicks + N.SLAM_LIE)) s.arm.rest(16);
                    s.arm.readyAt = fightTicks + coolTicks(N.SLAM_CD / 2);
                }
                s = null;
            }
        };
    }

    // ------------------------------------------------------------------ 고리: 벽, 떼 막기, 손뼉 파도, 깨짐

    private double faceAng(Face f) {
        return Math.toRadians(ringSpin + f.i * 45.0);
    }

    private void ringTick(Vector g) {
        ringSpin += N.RING_SPIN * (phase >= 3 ? 1.5 : 1.0);
        if (!ringOut) {
            if (fightTicks % 5 == 0) for (Face f : ring) if (!f.cut && f.part != null) f.part.pose(ringPose(f), 5);
            return;
        }
        int since = fightTicks - ringOutAt;
        if (since <= N.RING_OUT) {
            double e = since / (double) N.RING_OUT;
            ringR = N.RING_R * e * e * (3 - 2 * e);
        } else ringR = N.RING_R;
        if (since >= 2 && fightTicks % 5 == 0) {
            float k = (float) (3.0 + 3.0 * Math.min(1, ringR / N.RING_R));
            for (Face f : ring) {
                if (f.cut || f.part == null) continue;
                double a = faceAng(f);
                double bob = Math.sin((fightTicks + f.i * 17) * 0.09) * 0.35;
                Vector c = arena();
                // 풀리는 동안은 보스 허리에서 고리 자리로 번져 나간다
                double e = Math.min(1, ringR / N.RING_R);
                f.wx = g.getX() * (1 - e) + c.getX() * e + Math.cos(a) * Math.max(5.2, ringR);
                f.wz = g.getZ() * (1 - e) + c.getZ() * e + Math.sin(a) * Math.max(5.2, ringR);
                f.wy = floorAt(f.wx, f.wz) * e + (g.getY() + 8.7) * (1 - e) + (0.5 * k + 0.4) * e + bob;
                // 얼굴은 가운데를 본다
                float fy = (float) Math.atan2(c.getX() - f.wx, c.getZ() - f.wz);
                f.part.pose(new Matrix4f().translate((float) (f.wx - pos.getX()), (float) (f.wy - pos.getY()), (float) (f.wz - pos.getZ())).rotateY(fy).scale(k), 5);
            }
            dirtySpheres();
        }
        if (ringR < N.RING_R - 0.5) return;
        if (fightTicks % 2 == 0) wall();
        if (fightTicks % 10 == 3) herd();
    }

    /** 고리 띠를 얼굴 곁에서 넘으려는 사람: 안쪽으로 밀치고 RING_DMG(같은 사람 20틱에 한 번). */
    private void wall() {
        Vector c = arena();
        long now = game.now();
        for (Player p : game.mobs.standing()) {
            Vector f = p.getLocation().toVector();
            double r = horiz(f, c);
            if (Math.abs(r - ringR) > N.RING_BAND) continue;
            Face near = faceAt(Math.atan2(f.getZ() - c.getZ(), f.getX() - c.getX()));
            if (near == null) continue;
            knock(p, c.clone().subtract(f).setY(0), N.RING_PUSH, 0.1);
            Long at = bumpAt.get(p.getUniqueId());
            if (at != null && now - at < 20) continue;
            bumpAt.put(p.getUniqueId(), now);
            hitBoss(p, N.RING_DMG, "cause_b5_ring");
            near.part.look.open = true;
        }
    }

    /** 그 각(가운데에서 본 라디안)을 막고 있는 살아 있는 고리 얼굴(없으면 틈). */
    private Face faceAt(double ang) {
        for (Face f : ring) {
            if (f.cut || f.part == null) continue;
            if (Math.abs(B5Land.wrap(faceAng(f) - ang)) * ringR <= N.RING_FACE) return f;
        }
        return null;
    }

    /** 떼 막기: 고리 바깥 띠에서 얼굴에 막힌 땅 게이브를 가까운 틈 쪽으로 돌려보낸다. */
    private void herd() {
        Vector c = arena();
        for (Gabe o : game.mobs.all()) {
            if (!mob(o) || o.flying || o.untargetable || o.noReward) continue;
            double r = horiz(o.pos, c);
            if (r < ringR - 1 || r > ringR + 8) continue;
            double ang = Math.atan2(o.pos.getZ() - c.getZ(), o.pos.getX() - c.getX());
            Face f = faceAt(ang);
            if (f == null) continue;
            double side = B5Land.wrap(ang - faceAng(f)) >= 0 ? 1 : -1, to = faceAng(f) + side * Math.toRadians(22.5);
            o.lure(new Vector(c.getX() + Math.cos(to) * (ringR + 3), o.pos.getY(), c.getZ() + Math.sin(to) * (ringR + 3)), 20);
        }
    }

    private int facesLeft() {
        int n = 0;
        for (Face f : ring) if (!f.cut && f.part != null) n++;
        return n;
    }

    /** 고리 얼굴이 깨졌다: 그 칸은 틈이 되고 조각이 쏟아진다. */
    private void breakFace(Face f) {
        if (f.cut) return;
        f.cut = true;
        Vector at = ringOut && ringR > 8 ? new Vector(f.wx, f.wy, f.wz) : worldV(Math.sin(faceAng(f)) * RING_RAD, RING_Y, Math.cos(faceAng(f)) * RING_RAD);
        if (f.part != null) {
            f.part.remove();
            f.part = null;
        }
        Species bit = Species.G10.implemented() ? Species.G10 : Species.G01;
        for (int k = 0; k < minions(N.RING_BITS); k++) {
            summon(bit, new Vector(at.getX() + (game.random.nextDouble() - 0.5) * 3, floorAt(at.getX(), at.getZ()) + 1.2, at.getZ() + (game.random.nextDouble() - 0.5) * 3));
        }
        Props.soundAround(locOf(at), "sfx.wipe", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 1f, 0.7f, 96, -1);
        quake(at, 30, 0.6);
        dirtySpheres();
    }

    private Attack clap() {
        return new Attack("clap", 1, 4, N.CLAP_CD, N.CLAP_TELL, Pick.NONE, true) {
            Tele tl;
            final Set<UUID> hit = new HashSet<>();

            @Override
            protected boolean usable() {
                return ringOut && ringR >= N.RING_R - 0.5 && facesLeft() >= 3;
            }

            @Override
            protected void start() {
                tl = null;
                hit.clear();
                for (Face f : ring) if (!f.cut && f.part != null) f.part.state("clap");
                hintAll("hint_bs_jump", 60);
                sfx("gabe.b5.choir", Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1f, 0.7f, 192);
            }

            @Override
            protected boolean step() {
                if (t == tellTicks) {
                    for (Face f : ring) if (!f.cut && f.part != null) f.part.state("");
                    Vector c = arena();
                    tl = wave(c, 2, N.RING_R, N.CLAP_SPEED);
                    Props.soundAround(locOf(c.clone().add(new Vector(0, 2, 0))), "sfx.quake", Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1f, 0.7f, 128, -1);
                    quake(c, 40, 0.7);
                }
                if (tl == null) return false;
                for (Player p : caught(tl, 0)) {
                    if (!hit.add(p.getUniqueId())) continue;
                    hitBoss(p, N.CLAP_DMG, "cause_b5_clap");
                    knock(p, new Vector(0, 0, 0), 0, N.CLAP_UP);
                }
                return tl.gone();
            }

            @Override
            protected void end() {
                for (Face f : ring) if (!f.cut && f.part != null) f.part.state("");
            }
        };
    }

    // ------------------------------------------------------------------ 이마 눈

    private void closeEye() {
        if (eyeOpen) {
            eyeOpen = false;
            if (eye != null) eye.state("");
            dirtySpheres();
        }
        if (beam != null) beam.remove();
        if (spot != null) spot.remove();
        beam = spot = null;
    }

    /** 그 사람이 눈에서 가려져 있나: 블록(사체 더미, 솟은 판, 등 기둥) 뒤이거나 풀린 고리 얼굴, 누운 팔 뒤. */
    private boolean shaded(Vector from, Player p) {
        if (hidden(from, p)) return true;
        Vector to = chest(p);
        for (Face f : ring) {
            if (f.cut || f.part == null || !ringOut || ringR < 8) continue;
            if (blocks(from, to, f.wx, f.wy, f.wz, 2.8)) return true;
        }
        for (Arm a : arms) {
            if (!a.down || a.cut) continue;
            for (int i = 0; i < 4; i++) {
                float f = 0.14f + i * 0.26f;
                double[] w = world(a.root.x + (a.tip.x - a.root.x) * f, a.root.y + (a.tip.y - a.root.y) * f, a.root.z + (a.tip.z - a.root.z) * f, S);
                if (blocks(from, to, w[0], w[1], w[2], 1.4)) return true;
            }
        }
        return false;
    }

    /** 선분 a-b 가 구(c, r)를 지나나. */
    private static boolean blocks(Vector a, Vector b, double cx, double cy, double cz, double r) {
        double dx = b.getX() - a.getX(), dy = b.getY() - a.getY(), dz = b.getZ() - a.getZ(), l2 = dx * dx + dy * dy + dz * dz;
        if (l2 < 1e-9) return false;
        double t = ((cx - a.getX()) * dx + (cy - a.getY()) * dy + (cz - a.getZ()) * dz) / l2;
        t = Math.max(0, Math.min(1, t));
        double px = a.getX() + dx * t - cx, py = a.getY() + dy * t - cy, pz = a.getZ() + dz * t - cz;
        return px * px + py * py + pz * pz <= r * r;
    }

    /** 변이 게이지가 가득 찼다: 굳고, 동료가 등 뒤에서 짜내 주지 않으면 GAZE_TURN 틱 뒤 쓰러지며 그 자리에 게이브 둘이 일어선다. */
    private void turned(Player p) {
        gauge.put(p.getUniqueId(), 0.0);
        game.players.root(p, N.GAZE_ROOT, "boss");
        boolean ok = game.players.latent(p, sp.key(), N.GAZE_TURN, q -> {
            if (dead || !standing(q)) return;
            Vector at = q.getLocation().toVector();
            game.mobs.noteHit(this, q, 100);
            game.players.down(q, this, "cause_b5_gaze");
            for (int i = 0; i < 2; i++) summon(Species.G01, at.clone().add(new Vector(i == 0 ? 0.8 : -0.8, 1.0, 0)));
        });
        if (!ok) hitBoss(p, N.SLAM_DMG, "cause_b5_gaze");
    }

    private Attack gaze(String name, int from, int to, int cd) {
        return new Attack(name, from, to, cd, N.GAZE_TELL, Pick.NONE, true) {
            Vector a, bb, at;

            @Override
            protected boolean usable() {
                return !crawling && eye != null && headShown;
            }

            @Override
            protected void start() {
                // 무리를 지나는 길: 보스에서 무리 쪽 줄에 비스듬한 24블록
                Vector c = crowdCenter(), g = ground();
                Vector d = c.clone().subtract(g).setY(0);
                if (d.lengthSquared() < 1e-6) d = forward();
                d.normalize();
                double ang = Math.toRadians(game.random.nextBoolean() ? 60 : -60), cs = Math.cos(ang), sn = Math.sin(ang);
                Vector way = new Vector(d.getX() * cs - d.getZ() * sn, 0, d.getX() * sn + d.getZ() * cs);
                double half = N.GAZE_SPEED * N.GAZE_TICKS / 2;
                a = c.clone().subtract(way.clone().multiply(half));
                bb = c.clone().add(way.clone().multiply(half));
                at = a.clone();
                sfx("gabe.b5.heart", Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.6f, 160);
                hintAll("hint_b5_cover", 80);
            }

            @Override
            protected boolean step() {
                if (t < tellTicks) {
                    // 눈꺼풀이 떨린다
                    if (eye != null && t % 6 == 0) eye.pose(new Matrix4f().translate(0, EYE_Y, FACE_Z + 0.004f).scale(1f, t % 12 == 0 ? 1.06f : 0.96f, 1f), 5);
                    return false;
                }
                int k = t - tellTicks;
                if (k == 0) open();
                if (!eyeOpen) return true;
                if (eyeRg.dealt - eyeDealt0 >= N.GAZE_SHUT * maxHp) {
                    // 눈을 맞아 감는다: 바라봄 끝, 움찔
                    stunUntil = fightTicks + N.STUN;
                    sfx("sfx.boss_roar", Sound.ENTITY_RAVAGER_STUNNED, 1f, 0.6f, 160);
                    return true;
                }
                double f = Math.min(1, k / (double) N.GAZE_TICKS);
                at = a.clone().add(bb.clone().subtract(a).multiply(f));
                at.setY(floorAt(at.getX(), at.getZ()));
                if (k % 4 == 0) light(at, 4);
                Vector e = eyePos();
                for (Player p : game.mobs.standing()) {
                    UUID id = p.getUniqueId();
                    double v = gauge.getOrDefault(id, 0.0);
                    boolean lit = horiz(p.getLocation().toVector(), at) <= N.GAZE_R && !shaded(e, p);
                    v = lit ? v + 1.0 / N.GAZE_FILL : Math.max(0, v - 1.0 / N.GAZE_DRAIN);
                    if (v >= 1) {
                        turned(p);
                        v = 0;
                    }
                    gauge.put(id, v);
                    if (v > 0 || lit) game.hud.meter(p, sp.key(), v, 6);
                }
                if (k % 40 == 0) sfx("gabe.b5.heart", Sound.ENTITY_WARDEN_HEARTBEAT, 0.9f, 0.6f, 160);
                return k >= N.GAZE_TICKS;
            }

            private void open() {
                eyeOpen = true;
                eyeDealt0 = eyeRg.dealt;
                if (eye != null) {
                    eye.state("open");
                    eye.pose(new Matrix4f().translate(0, EYE_Y, FACE_Z + 0.004f), 3);
                }
                beam = Look.has("gabe/b5_beam") ? body.attach("gabe/b5_beam") : null;
                spot = Look.has("gabe/b5_spot") ? body.attach("gabe/b5_spot") : null;
                for (Body.Part p : new Body.Part[] {beam, spot}) {
                    if (p == null) continue;
                    p.follow = false;
                    p.look.freeze = true;
                    wide(p);
                }
                light(at, 0);
                reveal(eyePos(), 60);
                sfx("gabe.b5.open", Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.9f, 0.7f, 160);
                dirtySpheres();
            }

            /** 빛 원뿔(눈 → 빛 자리)과 바닥 빛 자리를 옮긴다. */
            private void light(Vector to, int ticks) {
                Vector e = eyePos();
                if (spot != null) {
                    spot.pose(new Matrix4f().translate((float) (to.getX() - pos.getX()), (float) (to.getY() + 0.12 - pos.getY()), (float) (to.getZ() - pos.getZ()))
                            .scale((float) (N.GAZE_R * 2), 1f, (float) (N.GAZE_R * 2)), ticks);
                }
                if (beam != null) {
                    Vector3f d = new Vector3f((float) (to.getX() - e.getX()), (float) (to.getY() - e.getY()), (float) (to.getZ() - e.getZ()));
                    float len = d.length();
                    if (len < 1e-3f) return;
                    d.div(len);
                    float w = (float) (N.GAZE_R / 0.5);
                    beam.pose(new Matrix4f().translate((float) (e.getX() - pos.getX()), (float) (e.getY() - pos.getY()), (float) (e.getZ() - pos.getZ()))
                            .rotate(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), d)).scale(w, w, len / 1.5f), ticks);
                }
            }

            @Override
            protected void end() {
                closeEye();
                for (Player p : game.mobs.standing()) if (gauge.getOrDefault(p.getUniqueId(), 0.0) <= 0) game.hud.meter(p, sp.key(), -1, 0);
            }
        };
    }

    // ------------------------------------------------------------------ 큰 숨

    private Attack breath(int from) {
        return new Attack("breath", from, 3, N.BREATH_CD, N.BREATH_TELL, Pick.NONE, true) {
            Tele tl;
            final Set<UUID> hit = new HashSet<>();

            @Override
            protected boolean usable() {
                return !crawling;
            }

            @Override
            protected void start() {
                tl = null;
                hit.clear();
                hintAll("hint_bs_jump", 60);
                sfx("gabe.b5.inhale", Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 0.4f, 160);
                for (int i = 0; i < 3; i++) if (heaps[i] != null) heaps[i].pose(heapPose(i, 1.15f), tellTicks);
            }

            @Override
            protected boolean step() {
                Vector g = ground();
                if (t < tellTicks) {
                    // 들이쉼: 30블록 안 사람을 끌어당긴다(걸으면 버틴다)
                    if (t % 2 == 0) {
                        for (Player p : game.mobs.standing()) {
                            Vector f = p.getLocation().toVector();
                            double d = horiz(f, g);
                            if (d > N.BREATH_R || d < 5 || !onGround(p)) continue;
                            knock(p, g.clone().subtract(f).setY(0), N.BREATH_PULL, 0);
                        }
                    }
                    if (t % 20 == 10) sfx("gabe.b5.inhale", Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.8f, 0.4f, 160);
                    return false;
                }
                if (t == tellTicks) {
                    for (int i = 0; i < 3; i++) if (heaps[i] != null) heaps[i].pose(heapPose(i, 1f), 4);
                    tl = wave(g, 4, N.BREATH_R, N.BREATH_SPEED);
                    sfx("gabe.b5.exhale", Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 0.5f, 160);
                    quake(g, 60, 0.9);
                }
                if (tl == null) return true;
                for (Player p : caught(tl, 0)) {
                    if (!hit.add(p.getUniqueId())) continue;
                    hitBoss(p, N.BREATH_DMG, "cause_b5_breath");
                    knock(p, p.getLocation().toVector().subtract(tl.at).setY(0), N.BREATH_KNOCK, 0.3);
                }
                return tl.gone();
            }

            @Override
            protected void end() {
                for (int i = 0; i < 3; i++) if (heaps[i] != null) heaps[i].pose(heapPose(i, 1f), 6);
            }
        };
    }

    // ------------------------------------------------------------------ 감싸기(4단계)

    private Attack hug() {
        Attack at = new Attack("hug", 4, 4, 40, N.HUG_TELL, Pick.NONE, true) {
            Tele tl;

            @Override
            protected void start() {
                tl = disc(arena(), N.HUG_R, tellTicks);
                // 두 팔을 벌린다
                for (Arm a : arms) {
                    if (!a.free()) continue;
                    a.state("raise");
                    a.pose(a.side * 0.72f, -0.5f, 0.2f, a.side * 2.6f, -0.2f, 0.9f, Math.max(6, tellTicks / 2));
                }
                sfx("gabe.b5.inhale", Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 0.5f, 160);
            }

            @Override
            protected boolean step() {
                if (t < tellTicks) return false;
                if (t == tellTicks) {
                    Vector c = arena();
                    int n = 0;
                    for (Player p : caught(tl, 0.2)) {
                        hitBoss(p, N.HUG_DMG, "cause_b5_hug");
                        game.players.root(p, N.HUG_ROOT, "boss");
                        n++;
                    }
                    for (Ward w : caughtWards(tl, 0)) hitWard(w, N.HUG_WARD, "cause_b5_hug");
                    // 안은 만큼 찬다(12% 문턱 안까지만)
                    healBy(N.HUG_HEAL);
                    for (Arm a : arms) {
                        if (!a.free()) continue;
                        Vector3f tp = toLocal(c.getX() - left().getX() * a.side * 3, c.getY() + 1.2, c.getZ() - left().getZ() * a.side * 3);
                        a.state("grab");
                        a.pose(a.side * 0.72f, -0.5f, 0.2f, tp.x, tp.y, tp.z, 4);
                    }
                    quake(c, 48, 1.1);
                    Props.soundAround(locOf(c.clone().add(new Vector(0, 2, 0))), "gabe.b5.slam", Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f, 0.5f, 128, -1);
                    game.horizon.shake(0.8, 20);
                }
                return t >= tellTicks + 30;
            }

            @Override
            protected void end() {
                retreating = true;
                for (Arm a : arms) if (a.free()) a.pose(a.side * 0.72f, -0.55f, 0.2f, a.side * 1.2f, -0.72f, 1.6f, 14);
            }
        };
        at.auto = false;
        return at;
    }

    // ------------------------------------------------------------------ 엔진 분사에 맞음(B5Land 가 부름)

    /** 엔진 분사: 체력 ENGINE_HP(4단계는 x REST_MUL). 다음 단계 문턱은 넘기되 그 아래 단계로는 못 내려가고, 이것만으로는 죽지 않는다. */
    void engineHit() {
        if (dead || dying || intro) return;
        double amt = N.ENGINE_HP * maxHp * (phase >= 4 ? N.REST_MUL : 1.0);
        double floor = phase - 1 < cuts.length ? (cuts[phase - 1] - 0.005) * maxHp : 1;
        hp = Math.max(Math.max(1, floor), hp - amt);
        if (body != null) body.flash(game.worldTime() + 4);
        stunUntil = Math.max(stunUntil, fightTicks + 30);
        cancelAttacks();
        checkPhase();
    }

    // ------------------------------------------------------------------ 죽음, 승리, 치우기

    @Override
    public int dyingLength() {
        return N.DYING;
    }

    @Override
    protected void dyingStep(int t) {
        hold();
        if (t == 1) {
            // 입속 작은 얼굴이 실눈을 감는다. 세상이 멎는다(엔딩이면 바탕이 떼를 얼림)
            cancelDrops();
            closeEye();
            // 눈 뜸의 테마 어둡게는 엔딩 새벽 하늘에 남지 않게 바로 푼다
            if (openUntil > 0) themeDim(0, 0);
            for (Player p : game.players.inPlay()) game.hud.meter(p, sp.key(), -1, 0);
            if (tiny != null) {
                tiny.look.open = false;
                tiny.look.freeze = true;
            }
            if (body != null) {
                body.look.freeze = true;
                for (Body.Part p : body.pieces) p.look.freeze = true;
            }
            for (Face f : ring) if (f.part != null) f.part.look.open = false;
        }
        // 물결이 지나가며 땅이 모두 메워진다(아침에는 땅이 멀쩡하다)
        if (t == 44) map.heal();
        if (t >= 40) {
            double e = Math.min(1, (t - 40) / 20.0);
            pitch = (float) (28 * e);
            hover = (collapsed ? HOVER_CRAWL : HOVER) - (collapsed ? 1.2 : 3.0) * e;
            if (body != null && t % 4 == 0) body.pose(4);
        }
        if (t == 58) {
            quake(ground(), 64, 1.0);
            sfx("sfx.wipe", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 192);
        }
        souls.tick();
    }

    @Override
    protected void beforeCorpse() {
        souls.clearTags();
        map.remove();
        cancelDrops();
        if (eye != null) eye.remove();
        if (tiny != null) tiny.remove();
        if (beam != null) beam.remove();
        if (spot != null) spot.remove();
        eye = tiny = beam = spot = null;
        if (!headShown && body != null) body.model("gabe/b5");
    }

    /** 전멸: 중앙 원을 두 팔로 감싸 안고 큰 얼굴을 원 위에 내려놓는다. */
    @Override
    public void victory() {
        cancelAttacks();
        clearTeles();
        cancelDrops();
        closeEye();
        winning = true;
        winT = 0;
        if (headShown) state("open");
        sfxAll("gabe.b5.roar", Sound.ENTITY_WARDEN_ROAR, 1f, 0.5f, 24);
    }

    private void winStep(Vector g) {
        winT++;
        souls.tick();
        Vector c = arena().setY(pos.getY());
        double r = B5Land.radOf(g);
        turnTo(c, 2f);
        if (r > 9) seek(c, N.WIN);
        else hold();
        if (winT % 5 == 0) body.pose(5);
        if (winT == 60 || r <= 9 && winT > 20 && winT % 40 == 0) {
            hover = Math.max(7, hover - 6);
            for (Arm a : arms) {
                if (!a.free()) continue;
                Vector3f tp = toLocal(c.getX() - left().getX() * a.side * 4, floorAt(c.getX(), c.getZ()) + 1.5, c.getZ() - left().getZ() * a.side * 4);
                a.state("grab");
                a.pose(a.side * SHOULDER_X, collapsed ? -0.5f : SHOULDER_Y, 0.1f, tp.x, tp.y, tp.z, 30);
            }
        }
    }

    @Override
    public void remove() {
        souls.clearTags();
        map.remove();
        if (!dead) {
            for (Player p : game.players.inPlay()) game.hud.meter(p, sp.key(), -1, 0);
        }
        super.remove();
    }

    private double maxGauge() {
        double m = 0;
        for (double v : gauge.values()) m = Math.max(m, v);
        return m;
    }

    @Override
    public String status() {
        return super.status() + " | b5 rise=" + Math.round(rise * 100) + " ring=" + (ringOut ? "out:" + Math.round(ringR) : "waist") + ":" + facesLeft() + " arms="
                + (arms[0].cut ? "x" : arms[0].down ? "d" : "o") + (arms[1].cut ? "x" : arms[1].down ? "d" : "o") + " eye=" + (eyeOpen ? 1 : 0) + " crawl="
                + (crawling ? retreating ? "back" : "in" : "-") + " gulped=" + Math.round(gulped) + " souls=" + souls.size() + "[" + souls.names() + "] drops="
                + drops.size() + " fly=" + flyers.size() + " gz=" + Math.round(maxGauge() * 100) + " tiny=" + Math.round(tinyRg.dealt) + " eyeDealt=" + Math.round(eyeRg.dealt) + " "
                + map.status() + " r=" + Math.round(B5Land.radOf(pos)) + " hover=" + Math.round(hover * 10) / 10.0 + " state=" + state + " b5ms=" + costText();
    }
}
