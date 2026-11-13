package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Species;
import kr.gave.mob.boss.Boss;
import kr.gave.mob.boss.Tele;
import kr.gave.mob.boss.Terrain;
import kr.gave.mob.boss.Ward;
import kr.gave.player.Detachable;
import kr.gave.trip.Theme;
import kr.gave.world.Plaza;

/**
 * B4 지평선 게이브(30웨이브 보스, 3막 재의 땅의 끝. docs/모듈/보스.md 15절, docs/모듈/원정.md 10절).
 *
 * <p>안개가 걷히면 지평선에 산만 한 얼굴(폭 51블록)이 있다. 눈을 뜨면 노란 눈빛이 땅을 쓸고, 그 빛에 닿은 사람은 3초 만에 굳어 게이브가 되어 간다
 * ({@link B4Gaze}). 숨을 곳은 땅에서 솟은 손가락 기둥(진짜 뼈 블록 기둥 + 디스플레이 손가락)의 그늘뿐이다. 얼굴은 눈을 감은 동안 맞지 않고, 뜬 눈의
 * 동공이 약점이다(4% 깎이면 움찔하며 눈을 감는다). 손가락은 늘 맞지만 끊으면 그늘이 줄어든다.
 *
 * <p>몸: 가운데 빈 닻(중앙 원 가운데, 컬링 없음) + 세계 축 부품 20(손가락 여섯, 뿔 둘, 얼굴, 그늘 여섯, 눈빛 원뿔 둘, 바닥 빛 자리, 얼굴 뒤 발사장 투광등 빛기둥 둘). pos 는 가운데라
 * 소리와 방향 화살촉은 얼굴 쪽으로 스스로 낸다({@link #sfxFace}). 얼굴 자리는 단계마다 R 88 → 70 → 52 로 다가온다.
 *
 * <p>공격: gaze(바라봄 ★: 한 눈을 떠 빛 자리가 사람을 쫓아 쓸고 감, 2단계부터 눈물, 쓸고 간 땅은 불씨 땅), stab(손가락 찍기: 손가락이 뽑혀 표적 자리에
 * 다시 꽂힘, 공격 칸과 따로 제 박자로), call(부름: 얼굴 쪽에서 떼가 더 옴), glare(온 눈뜸 ★, 3단계: 경기장 전체가 빛, 그늘만 안전), 배움(단계가 바뀔
 * 때 가장 많이 맞은 피해 종류가 덜 먹힘). 시험 훅 '공격 ward' 는 시험용 지킬 것을 가운데에 올리고 내리고, '공격 quiet' 는 스스로 고르는 공격을 멈춘다.
 */
public final class B4 extends Boss implements Detachable {

    // ------------------------------------------------------------------ 숫자(한곳에: 난이도 배율로 맞출 때 여기만)

    static final class Tune {
        /** 얼굴 자리(단계 1, 2, 3): 가운데에서 거리, 얼굴 중심 높이(바닥 위), 앞으로 숙임(도). 옮겨 가는 틱. */
        static double[] FACE_R = {88, 70, 52}, FACE_H = {6, 30, 34}, FACE_PITCH = {0, 0, 25};
        static int MOVE_TICKS = 50;
        /** 부품 배율(세계 크기): 얼굴 1.6 x 32 = 51블록, 손가락 1.4 x 18 = 25블록, 뿔 1.5 x 28 = 42블록. */
        static float FACE_S = 32f, FINGER_S = 18f, HORN_S = 28f;
        /** 동공 자리(얼굴 기준 블록: 옆, 위, 앞)와 맞는 구 반지름. */
        static double PUPIL_X = 11.2, PUPIL_Y = 0, PUPIL_Z = 11.2, PUPIL_RAD = 3;

        /** 받는 배율: 눈 감은 얼굴, 눈 뜬 얼굴, 동공, 손가락, 배운 종류. 부위 체력(최대 체력의 몫): 동공(넘으면 눈 감음), 손가락(넘으면 끊김). */
        static double FACE_SHUT = 0, FACE_OPEN = 0.6, PUPIL = 3, FINGER = 1, LEARN = 0.6, PUPIL_HP = 0.04, FINGER_HP = 0.05;
        /** 움찔(눈 감고 빛 꺼짐) 틱: 동공이 터졌을 때, 손가락이 끊겼을 때. */
        static int FLINCH_PUPIL = 60, FLINCH_FINGER = 30;

        /** 바라봄: 예고, 눈 뜬 틱, 다시 쓰기(단계 1, 2, 3), 빛 자리 반지름, 쓸고 가는 빠르기(블록/틱), 길 다시 잡는 틱. */
        static int GAZE_TELL = 40, GAZE_OPEN = 160, GAZE_FIRST = 60, WAY_EVERY = 50;
        static int[] GAZE_CD = {200, 150, 110};
        static double SPOT_R = 6, SWEEP_V = 0.15;
        /** 변이 게이지: 틱당 차는 양(3초), 빠지는 양(6초), 굳는 틱, 떼어 준 뒤 안 쌓이는 틱, 터지면 서는 기본 게이브 수. */
        static double FILL = 1.0 / 60, DRAIN = 1.0 / 120;
        static int FREEZE = 60, IMMUNE = 100, CHILDREN = 2;

        /** 눈물(2단계부터, 바라봄 동안): 간격, 예고, 반지름, 피해, 밀침, 즙 게이브(G05)가 서는 확률. */
        static int TEAR_EVERY = 15, TEAR_TELL = 20;
        static double TEAR_R = 3, TEAR_DMG = 20, TEAR_KNOCK = 0.8, TEAR_G05 = 0.3;

        /** 손가락 찍기: 예고, 다시 쓰기(단계 1, 2, 3), 빨간 원 반지름, 안쪽 반지름, 안쪽과 바깥 피해, 넘어짐 틱, 등장 때 내리꽂히는 손가락 피해. */
        static int STAB_TELL = 40, STAB_FIRST = 100, STAB_ROOT = 20;
        static int[] STAB_CD = {200, 160, 120};
        static double STAB_R = 4, STAB_IN = 2.5, STAB_DMG_IN = 50, STAB_DMG_OUT = 30, INTRO_STAB = 30, INTRO_STAB_R = 2.5;

        /** 부름: 다시 쓰기, 예고, 더 오는 수 = BASE + PER x 루트(사람 수). */
        static int CALL_CD = 600, CALL_TELL = 30, CALL_FIRST = 240;
        static double CALL_BASE = 12, CALL_PER = 2;

        /** 온 눈뜸(3단계): 예고, 빛 틱, 다시 쓰기. */
        static int GLARE_TELL = 60, GLARE_OPEN = 80, GLARE_CD = 500, GLARE_FIRST = 120;

        /** 불씨 땅(눈빛이 쓸고 간 자리): 남기는 간격, 남는 틱, 반지름, 1초마다 피해. */
        static int EMBER_EVERY = 32, EMBER_LIFE = 240;
        static double EMBER_R = 4.5, EMBER_DMG = 2;

        /** 손가락: 수, 밑동 뼈 기둥 높이와 반지름, 그늘 판 최대 길이, 3단계에 다시 자라는 간격과 그때 설 수 있는 수. */
        static int FINGERS = 6, PILLAR_H = 10, REGROW_EVERY = 400, REGROW_MAX = 4;
        static double PILLAR_R = 1.5, SHADE_MAX = 14;
        /** 기둥이 솟기 전에 사람을 옮기는 거리(기둥 가운데에서 x, z 축으로: 3 x 3 칸 바깥 + 사람 반 폭). */
        static double FOOT_OUT = 1.85;

        /**
         * 지킬 것(트럭): 손가락에 찍히면, 눈빛이 닿거나 누가 그 그늘에 숨으면 1초마다. 트럭은 받은 값 x 0.004 를 체력 비율로 깎는다(Truck.WARD_FRAC):
         * 찍기 20 = 8%(B1 엎어지기 10%, B2 내려앉기 12% 보다 조금 덜: 셋에 한 번 트럭 곁에 꽂힘), 눈빛 1.25 = 초당 0.5%(원정.md 10절).
         */
        static double WARD_STAB = 20, WARD_GAZE = 1.25;

        /**
         * 발사장 투광등 역광(원정.md 10절): 처음 얼굴 자리 뒤 LAMP_BACK, 옆으로 LAMP_SIDE 에서 하늘로 LAMP_LEN 뻗는 빛기둥 둘(끝 반지름 LAMP_R),
         * 경기장 쪽으로 LAMP_IN 기울고 가운데 쪽으로 LAMP_LEAN ± LAMP_SWAY 만큼 LAMP_PERIOD 틱마다 오간다.
         */
        static double LAMP_BACK = 26, LAMP_SIDE = 36, LAMP_LEN = 115, LAMP_R = 8, LAMP_IN = 28, LAMP_LEAN = -6, LAMP_SWAY = 16;
        static int LAMP_PERIOD = 90;

        /** 눈을 뜬 동안 원정 테마 시계(5000 발사 뒤 아침 처음 .. 5800 아침): 바라봄, 온 눈뜸과 전멸. */
        static long THEME_GAZE = 5250, THEME_GLARE = 5500;

        private Tune() {
        }
    }

    static final String M_FACE = "gabe/b4", M_FINGER = "gabe/b4_finger", M_HORN = "gabe/b4_horn", M_SHADE = "gabe/b4_shade", M_BEAM = "gabe/b4_beam",
        M_SPOT = "gabe/b4_spot";
    /** 눈빛 원뿔 단위 모델(길이 1.5, 끝 반지름 0.5), 온 눈뜸 바닥 판(3블록)을 늘리는 배율. */
    static final float CONE_LEN = 1.5f, CONE_R = 0.5f, FLOOD_S = 34f;
    static final int FLOOR = Plaza.FLOOR_Y;
    static final double CX = Plaza.CX + 0.5, CZ = Plaza.CZ + 0.5;

    // ------------------------------------------------------------------ 상태

    /** 가운데에서 얼굴 쪽 수평 단위 벡터, 그 각(라디안, 0 = +x), 얼굴이 가운데를 보는 마크 yaw(라디안). */
    private double dirX = 0, dirZ = 1, ang = Math.PI / 2, faceYawRad;
    /** 얼굴의 지금 자리(가운데에서 거리, 바닥 위 높이, 숙임 도)와 옮겨 가는 처음, 끝. */
    private double fr, fh, fp, r0, h0, p0, r1, h1, p1;
    private int moveT, moveLen;
    private Body.Part face, hornL, hornR, spotPart;
    /** 얼굴 뒤 발사장 투광등 둘의 빛기둥(역광: 지평선 너머에서 하늘로). 발사장 자리는 움직이지 않는다. */
    private final Body.Part[] lamp = new Body.Part[2];
    private int lampSwayAt = -1, lampSign = 1;
    private final Body.Part[] beam = new Body.Part[2];
    private final Finger[] fingers = new Finger[Tune.FINGERS];
    /** 동공 부위(0 = 게이브 왼쪽 +X, 1 = 오른쪽). */
    private final Region[] pupil = new Region[2];
    private final boolean[] eyeOpen = new boolean[2];
    private String faceState = "";
    /** 이 틱까지 움찔(눈 감음, 새 공격 없음). fightTicks 기준. */
    private int flinchUntil;
    private int gazeReadyAt = Tune.GAZE_FIRST, glareReadyAt = Tune.GLARE_FIRST, callReadyAt = Tune.CALL_FIRST, nextStabAt = Tune.STAB_FIRST, nextRegrowAt;
    private int hornSwayAt, hornSign = 1;
    private final B4Gaze gaze;
    private final List<Stab> stabs = new ArrayList<>(2);
    private final List<Tear> tears = new ArrayList<>(4);
    /** 불씨 땅: {x, z, 반지름, 끝나는 fightTicks}. */
    private final List<double[]> embers = new ArrayList<>();
    /** 배움: 종류마다 지난 30초쯤 들어온 피해(틱마다 식음), 배운 종류, 알릴 것. */
    private final double[] tally = new double[DamageType.values().length];
    private final Set<DamageType> learned = EnumSet.noneOf(DamageType.class);
    private DamageType learnNote;
    private int learnNoteAt = -1;
    private Ward testWard;
    /** 시험 훅 '공격 quiet': 스스로 고르는 공격(바라봄, 온 눈뜸, 부름)과 제 박자 찍기를 멈춤(시험 훅 '공격' 으로 부른 것만 돈다). */
    private boolean manual;
    private double wardHurt;
    private int wardHitAt;
    private boolean pupilHinted;
    private String lightNow = "-";
    /** 지금 바닥 빛 자리 가운데(시험 상태 글, 꺼졌으면 NaN). */
    private double spotX = Double.NaN, spotZ = Double.NaN;

    /** 손가락 하나: 디스플레이 부품 + 밑동의 뼈 블록 기둥(묶음) + 그늘 판. */
    private final class Finger {
        final int index;
        final Region region;
        Body.Part part, shade;
        /** 밑동(블록 가운데). */
        double x, z;
        /** 손톱 얼굴이 보는 쪽(모델 앞, 라디안: 90도 단위). */
        double turn;
        int pillar;
        /** 서 있음(끊기지 않음), 땅에 꽂혀 있음(찍는 중이 아님), 등장 전. */
        boolean alive, planted, hidden = true;
        /** 공중에 있을 때 밑동 자리(맞는 구). */
        double ax, ay, az;
        int fallUntil = -1, plantAt = -1, riseAt = -1;

        Finger(int index) {
            this.index = index;
            this.region = region("f" + index, Tune.FINGER);
            region.hpFrac = Tune.FINGER_HP;
            region.onCut = r -> cutFinger(this);
        }
    }

    /** 손가락 찍기 한 번. */
    private final class Stab {
        final Finger f;
        final Vector to = new Vector();
        final Player who;
        final int tell;
        Tele tl;
        int t = -1;
        double fromX, fromZ;
        boolean done;

        Stab(Finger f, Vector to, Player who, int delay) {
            this.f = f;
            this.to.copy(to);
            this.who = who;
            this.tell = tellTicks(Tune.STAB_TELL);
            this.t = -1 - delay;
        }
    }

    /** 눈물 한 방울(빨간 원). */
    private static final class Tear {
        Tele tl;
        int at;
    }

    public B4(Game g, Species s) {
        super(g, s, new double[] {0.65, 0.30});
        flying = true;
        baseSpeed = 0;
        radius = 1.0;
        ragHold = 300;
        fromGround = false;
        ownRoar = true;
        sweepShadow = false;
        gaze = new B4Gaze(this);
        main.mul = Tune.FACE_SHUT;
        for (int i = 0; i < 2; i++) {
            final int eye = i;
            pupil[i] = region(i == 0 ? "pupil_l" : "pupil_r", Tune.PUPIL);
            pupil[i].weak = true;
            pupil[i].hpFrac = Tune.PUPIL_HP;
            pupil[i].onCut = r -> popPupil(eye);
        }
        for (int i = 0; i < fingers.length; i++) fingers[i] = new Finger(i);
        // 같은 공격은 연달아 못 고르므로(바탕 규칙) 왼눈, 오른눈 바라봄을 따로 둔다: 번갈아 뜬다
        attacks.add(new GazeAttack("gaze", 0));
        attacks.add(new GazeAttack("gaze_r", 1));
        attacks.add(new GlareAttack());
        attacks.add(new CallAttack());
        Attack st = new StabAttack();
        st.auto = false;
        attacks.add(st);
        Attack wd = new WardAttack();
        wd.auto = false;
        attacks.add(wd);
        Attack qt = new Attack("quiet", 1, 3, 1, 1, Pick.NONE, false) {
            @Override
            protected void start() {
                manual = !manual;
            }

            @Override
            protected boolean step() {
                return true;
            }
        };
        qt.auto = false;
        attacks.add(qt);
        r0 = r1 = fr = Tune.FACE_R[0];
        h0 = h1 = fh = -62;
        p0 = p1 = fp = 0;
    }

    // ------------------------------------------------------------------ 자리, 글귀, 하늘

    /** 가운데 빈 닻: 감독이 고른 쪽(사람들이 많이 보는 쪽)을 얼굴 방향으로 삼고 몸은 가운데에 둔다. */
    @Override
    public Vector spawnPoint(Vector suggested) {
        double dx = suggested.getX() - CX, dz = suggested.getZ() - CZ, l = Math.hypot(dx, dz);
        if (l > 1e-3) {
            dirX = dx / l;
            dirZ = dz / l;
        }
        return new Vector(CX, FLOOR + 3, CZ);
    }

    @Override
    public int introLength() {
        return 160;
    }

    @Override
    public int dyingLength() {
        return 100;
    }

    @Override
    public int skyTime() {
        return 18000;
    }

    @Override
    public double shadowRadius() {
        return 0;
    }

    /** 무너짐 물결: 얼굴 쪽 R 30 에서(얼굴 자리는 경기장 밖이라 반대쪽 떼까지 물결이 못 닿는다). */
    @Override
    public Vector waveCenter() {
        return new Vector(CX + dirX * 30, FLOOR, CZ + dirZ * 30);
    }

    // ------------------------------------------------------------------ 몸

    @Override
    protected void build() {
        ang = Math.atan2(dirZ, dirX);
        // 얼굴 앞(+Z)이 가운데를 봄: 보는 방향 (-dirX, -dirZ) = (-sin yaw, cos yaw)
        faceYawRad = Math.atan2(dirX, -dirZ);
        body = Body.carrier(this);
        body.viewRange(4.0f);
        // 사체로 남길 것부터: 손가락 여섯, 뿔 둘, 얼굴, 그 밖(그늘, 빛)
        for (Finger f : fingers) {
            f.part = part(M_FINGER);
            if (f.part != null) {
                f.part.follow = false;
                f.part.model(Body.EMPTY);
                f.part.look.phase = game.random.nextInt(40);
            }
        }
        hornL = part(M_HORN);
        hornR = part(M_HORN);
        face = part(M_FACE);
        for (Body.Part p : new Body.Part[] {hornL, hornR, face}) if (p != null) p.follow = false;
        if (hornR != null) hornR.look.phase = 30;
        for (Finger f : fingers) {
            f.shade = part(M_SHADE);
            if (f.shade != null) {
                f.shade.follow = false;
                f.shade.model(Body.EMPTY);
                if (f.shade.d != null) f.shade.d.setBrightness(new Display.Brightness(3, 3));
            }
        }
        beam[0] = part(M_BEAM);
        beam[1] = part(M_BEAM);
        spotPart = part(M_SPOT);
        lamp[0] = part(M_BEAM);
        lamp[1] = part(M_BEAM);
        for (Body.Part p : new Body.Part[] {beam[0], beam[1], spotPart, lamp[0], lamp[1]}) {
            if (p == null) continue;
            p.follow = false;
            p.model(Body.EMPTY);
            // 빛은 스스로 빛난다(밝기 15)
            if (p.d != null) p.d.setBrightness(new Display.Brightness(15, 15));
        }
        layoutFingers();
        poseFace(0);
    }

    /**
     * 손가락 여섯 자리: R 19~35, 얼굴 쪽에서 30도 비켜 60도 간격(얼굴 방향이 같으면 늘 같은 자리: 시험 대본이 그늘 자리를 미리 안다. 싸움이 시작되면
     * 찍기가 자리를 옮긴다). 블록 가운데에 맞추고(밑동 뼈 기둥 3 x 3 을 디스플레이가 감쌈) 바꿀 수 없는 칸이면 바깥으로 조금씩 민다.
     */
    private void layoutFingers() {
        double[] rs = {20, 31, 24, 35, 19, 28};
        double a0 = ang + Math.PI / 6;
        for (Finger f : fingers) {
            for (int tries = 0; tries < 12; tries++) {
                double a = a0 + f.index * Math.PI / 3, r = rs[f.index % rs.length] + tries * 0.7;
                double x = Math.floor(CX + Math.cos(a) * r) + 0.5, z = Math.floor(CZ + Math.sin(a) * r) + 0.5;
                if (!spotFree(x, z, f)) continue;
                f.x = x;
                f.z = z;
                break;
            }
            if (f.x == 0 && f.z == 0) {
                double a = a0 + f.index * Math.PI / 3;
                f.x = Math.floor(CX + Math.cos(a) * 26) + 0.5;
                f.z = Math.floor(CZ + Math.sin(a) * 26) + 0.5;
            }
            f.turn = quarter(f.x, f.z);
        }
    }

    /** 손톱 얼굴이 가운데 쪽을 보게 하는 90도 단위 회전(모델 앞 +Z 를 돌림). */
    private static double quarter(double x, double z) {
        return Math.round(Math.atan2(CX - x, CZ - z) / (Math.PI / 2)) * (Math.PI / 2);
    }

    /** 그 블록 가운데에 손가락을 꽂을 수 있나: 3 x 3 칸을 모두 바꿀 수 있고 다른 손가락에서 5블록 넘게 떨어짐. */
    private boolean spotFree(double x, double z, Finger self) {
        Terrain t = land();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (t != null) for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) if (!t.allowed(bx + i, bz + j)) return false;
        double r = Math.hypot(x - CX, z - CZ);
        if (r < 6 || r > Plaza.R_ROAM - 2) return false;
        for (Finger o : fingers) {
            if (o == null || o == self || (o.x == 0 && o.z == 0)) continue;
            if (Math.hypot(o.x - x, o.z - z) < 5) return false;
        }
        return true;
    }

    private Matrix4f at(double x, double y, double z) {
        return new Matrix4f().translate((float) (x - pos.getX()), (float) (y - pos.getY()), (float) (z - pos.getZ()));
    }

    private double faceX(double r) {
        return CX + dirX * r;
    }

    private double faceZ(double r) {
        return CZ + dirZ * r;
    }

    /** 얼굴 틀(배율 없이): 자리 (r, h), 숙임 p 도. */
    private Matrix4f faceFrame(double r, double h, double p) {
        return at(faceX(r), FLOOR + h, faceZ(r)).rotateY((float) -faceYawRad).rotateX((float) Math.toRadians(p));
    }

    /** 뿔 하나(side 1 = 게이브 왼쪽): 뒤통수 위 모서리에서 바깥 위로, sway 도만큼 더 기움. */
    private Matrix4f hornM(double r, double h, double p, int side, double sway) {
        Matrix4f m = faceFrame(r, h, p).translate(side * 11.5f, 20.5f, -7f);
        if (side < 0) m.rotateY((float) Math.PI);
        // 바깥으로 8도, 뒤로 14도 기움(오른쪽 뿔은 반 바퀴 돌린 틀이라 뒤가 +X 회전)
        return m.rotateZ((float) Math.toRadians(-8 - sway)).rotateX((float) Math.toRadians(side > 0 ? -14 : 14)).scale(Tune.HORN_S);
    }

    /** 얼굴과 뿔을 목표 자리(r1, h1, p1)로 ticks 보간. */
    private void poseFace(int ticks) {
        if (face != null) face.pose(faceFrame(r1, h1, p1).scale(Tune.FACE_S), ticks);
        if (hornL != null) hornL.pose(hornM(r1, h1, p1, 1, 0), ticks);
        if (hornR != null) hornR.pose(hornM(r1, h1, p1, -1, 0), ticks);
        hornSwayAt = age + ticks + 20;
    }

    /** 얼굴을 (r, h, p) 로 ticks 에 걸쳐 옮김(그림은 한 번 보내고 판정 자리는 틱마다 따라감). */
    private void moveFace(double r, double h, double p, int ticks) {
        r0 = fr;
        h0 = fh;
        p0 = fp;
        r1 = r;
        h1 = h;
        p1 = p;
        moveT = 0;
        moveLen = Math.max(1, ticks);
        poseFace(ticks);
    }

    private boolean moving() {
        return moveT < moveLen;
    }

    private void tickFace() {
        if (moveT < moveLen) {
            moveT++;
            double f = moveT / (double) moveLen;
            fr = r0 + (r1 - r0) * f;
            fh = h0 + (h1 - h0) * f;
            fp = p0 + (p1 - p0) * f;
            if (moveT == moveLen) for (Finger fg : fingers) updateShade(fg);
            dirtySpheres();
        } else if (age >= hornSwayAt && !dying) {
            // 뿔의 큰 흔들림(주기 120): 부품 보간 60틱으로 좌우로
            hornSwayAt = age + 60;
            hornSign = -hornSign;
            if (hornL != null) hornL.pose(hornM(fr, fh, fp, 1, 3 * hornSign), 60);
            if (hornR != null) hornR.pose(hornM(fr, fh, fp, -1, -3 * hornSign), 60);
        }
    }

    /** 얼굴 기준 자리(블록: 옆 +X 가 게이브 왼쪽, 위, 앞)의 세계 좌표를 out 에. */
    private void faceWorld(double lx, double ly, double lz, double[] out) {
        double p = Math.toRadians(fp), cp = Math.cos(p), sp0 = Math.sin(p);
        double y1 = ly * cp - lz * sp0, z1 = ly * sp0 + lz * cp;
        double cy = Math.cos(faceYawRad), sy = Math.sin(faceYawRad);
        out[0] = faceX(fr) + lx * cy - z1 * sy;
        out[1] = FLOOR + fh + y1;
        out[2] = faceZ(fr) + lx * sy + z1 * cy;
    }

    private final double[] tmp = new double[3];

    /** 눈빛이 나오는 자리(두 눈 사이, 얼굴 앞): 그늘 판정과 그늘 판이 이 점을 쓴다. */
    private Vector lightOrigin() {
        faceWorld(0, Tune.PUPIL_Y, Tune.PUPIL_Z - 0.6, tmp);
        return new Vector(tmp[0], tmp[1], tmp[2]);
    }

    private Vector pupilPos(int eye) {
        faceWorld(eye == 0 ? Tune.PUPIL_X : -Tune.PUPIL_X, Tune.PUPIL_Y, Tune.PUPIL_Z, tmp);
        return new Vector(tmp[0], tmp[1], tmp[2]);
    }

    private Matrix4f fingerM(Finger f, double x, double y, double z, double tiltDeg) {
        return at(x, y, z).rotateY((float) f.turn).rotateX((float) Math.toRadians(tiltDeg)).scale(Tune.FINGER_S);
    }

    private void showFace(String st) {
        if (st.equals(faceState)) return;
        faceState = st;
        if (face != null) face.state(st);
    }

    // ------------------------------------------------------------------ 맞는 구, 받는 피해

    @Override
    protected void refreshSpheres() {
        for (int iy = -1; iy <= 1; iy++) {
            for (int ix = -1; ix <= 1; ix++) {
                faceWorld(ix * 16, iy * 16, 0.6, tmp);
                sphere(main, tmp[0], tmp[1], tmp[2], 9.5);
            }
        }
        for (int e = 0; e < 2; e++) {
            if (!eyeOpen[e]) continue;
            faceWorld(e == 0 ? Tune.PUPIL_X : -Tune.PUPIL_X, Tune.PUPIL_Y, Tune.PUPIL_Z, tmp);
            sphere(pupil[e], tmp[0], tmp[1], tmp[2], Tune.PUPIL_RAD);
        }
        for (Finger f : fingers) {
            if (!f.alive || f.hidden) continue;
            double bx = f.planted ? f.x : f.ax, by = f.planted ? FLOOR : f.ay, bz = f.planted ? f.z : f.az;
            // 위 마디는 손톱 쪽(모델 앞)으로 굽음
            double fx = Math.sin(f.turn), fz = Math.cos(f.turn);
            for (int k = 0; k < 4; k++) {
                double hgt = 3 + 6 * k, bend = k < 2 ? 0 : (hgt - 12.6) * 0.21;
                sphere(f.region, bx + fx * bend, by + hgt, bz + fz * bend, 1.7);
            }
        }
    }

    @Override
    protected double damageMul(Damage d, Region r) {
        DamageType t = d.type();
        if (t != null && d.amount() > 0) tally[t.ordinal()] += d.amount();
        return t != null && learned.contains(t) ? Tune.LEARN : 1;
    }

    private boolean anyEye() {
        return eyeOpen[0] || eyeOpen[1];
    }

    /** 눈을 뜨고 감음(그림, 맞는 구, 얼굴 배율). */
    private void setEyes(boolean left, boolean right) {
        if (eyeOpen[0] == left && eyeOpen[1] == right) return;
        eyeOpen[0] = left;
        eyeOpen[1] = right;
        showFace(left && right ? "lr" : left ? "l" : right ? "r" : "");
        main.mul = left || right ? Tune.FACE_OPEN : Tune.FACE_SHUT;
        dirtySpheres();
    }

    /** 움찔: ticks 동안 눈을 감고 빛이 꺼지며 새 공격을 시작하지 않는다(도는 바라봄, 온 눈뜸은 시간이 남으면 다시 눈을 뜸). */
    private void flinch(int ticks) {
        flinchUntil = Math.max(flinchUntil, fightTicks + ticks);
        setEyes(false, false);
        lightOff();
        if (face != null && !moving()) {
            face.pose(faceFrame(fr, fh, fp + 2.5).scale(Tune.FACE_S), 3);
            shakeBackAt = age + 4;
        }
        sfxFace("lid", Sound.ENTITY_SLIME_SQUISH, 1f, 1f, 0.4f, 24);
        quake(new Vector(CX, FLOOR, CZ), 60, 0.5);
    }

    private int shakeBackAt = -1;

    /** 동공이 PUPIL_HP 만큼 깎임: 움찔하며 눈을 감는다. 부위 체력은 다시 채워 둔다(다음에 뜰 때 다시 셈). */
    private void popPupil(int eye) {
        pupil[eye].cut = false;
        pupil[eye].setLeft(1.0);
        if (dying || intro) return;
        flinch(Tune.FLINCH_PUPIL);
    }

    // ------------------------------------------------------------------ 손가락

    private int aliveFingers() {
        int n = 0;
        for (Finger f : fingers) if (f.alive) n++;
        return n;
    }

    /** 손가락이 땅에 꽂힘: 밑동에 뼈 블록 기둥이 솟고 그늘이 생긴다. */
    private void plant(Finger f) {
        f.planted = true;
        f.hidden = false;
        f.turn = quarter(f.x, f.z);
        if (f.part != null) {
            f.part.state("");
            f.part.look.open = false;
            f.part.pose(fingerM(f, f.x, FLOOR, f.z, 0), 0);
        }
        clearFoot(f.x, f.z);
        f.pillar = pillar(new Vector(f.x, FLOOR, f.z), Tune.PILLAR_R, Tune.PILLAR_H, Material.BONE_BLOCK, 4);
        updateShade(f);
        dirtySpheres();
    }

    /**
     * 기둥이 솟을 자리(3 x 3 칸)에 선 사람을 가장자리 밖으로 옮긴다. 안 옮기면 바탕이 솟는 블록 위로 올려 찍힌 사람이 기둥 꼭대기(높이 10)에 서게 된다
     * (게임 안 시험: 찍기에 맞고 묶인 사람이 y 74 에 섬).
     */
    private void clearFoot(double x, double z) {
        for (Player p : game.players.inPlay()) {
            Location l = p.getLocation();
            if (l.getWorld() != game.world || l.getY() > FLOOR + Tune.PILLAR_H) continue;
            double dx = l.getX() - x, dz = l.getZ() - z;
            if (Math.abs(dx) >= Tune.FOOT_OUT || Math.abs(dz) >= Tune.FOOT_OUT) continue;
            double d = Math.hypot(dx, dz);
            if (d < 1e-3) {
                dx = -dirX;
                dz = -dirZ;
                d = 1;
            }
            // 칸 밖(체비쇼프 거리 FOOT_OUT)까지 그 방향으로
            double k = Tune.FOOT_OUT / Math.max(Math.abs(dx), Math.abs(dz));
            Location to = l.clone();
            to.setX(x + dx * k);
            to.setZ(z + dz * k);
            to.setY(game.mobs.flow.floor(to.getX(), to.getZ()));
            p.teleport(to);
        }
    }

    /** 밑동 기둥을 뽑음(블록이 도로 꺼짐), 그늘 사라짐. */
    private void unplant(Finger f, int ticks) {
        f.planted = false;
        f.ax = f.x;
        f.ay = FLOOR;
        f.az = f.z;
        if (f.pillar > 0) mend(f.pillar, ticks);
        f.pillar = 0;
        if (f.shade != null) f.shade.model(Body.EMPTY);
    }

    /**
     * 그늘 판: 눈빛 자리에서 손가락 반대쪽 땅에 눕는 어두운 띠. 길이는 눈빛 높이로 정한다(기둥 높이 PILLAR_H 가 서 있는 사람 머리를 가리는 데까지,
     * SHADE_MAX 까지). 판정은 진짜 블록 광선({@link #hidden})이고 이 판은 그 자리를 보여 준다.
     */
    private void updateShade(Finger f) {
        if (f.shade == null) return;
        if (!f.alive || !f.planted || f.hidden || dying) {
            f.shade.model(Body.EMPTY);
            return;
        }
        Vector l = lightOrigin();
        double ux = f.x - l.getX(), uz = f.z - l.getZ(), dist = Math.hypot(ux, uz);
        if (dist < 1e-3) {
            f.shade.model(Body.EMPTY);
            return;
        }
        ux /= dist;
        uz /= dist;
        double lh = l.getY() - FLOOR, len = Tune.SHADE_MAX;
        if (lh > Tune.PILLAR_H + 0.5) {
            double q = (Tune.PILLAR_H - 1.6) / (lh - 1.6);
            len = Math.min(Tune.SHADE_MAX, q / (1 - q) * (dist + 1.5) - 0.25);
        }
        if (len < 1.5) {
            f.shade.model(Body.EMPTY);
            return;
        }
        double w = Math.min(3.6, 3.0 * (1 + len / (2 * dist)));
        f.shade.model(M_SHADE);
        f.shade.pose(at(f.x + ux * 1.75, FLOOR + 0.12, f.z + uz * 1.75).rotateY((float) Math.atan2(ux, uz)).scale((float) w, 1f, (float) len), 6);
    }

    /** 손가락 부위 체력이 다함: 바깥으로 쓰러져 그루터기만 남고 그늘이 사라진다. 보스는 움찔한다. */
    private void cutFinger(Finger f) {
        if (!f.alive) return;
        f.alive = false;
        for (Stab s : stabs) {
            if (s.f != f || s.done) continue;
            s.done = true;
            if (s.tl != null) s.tl.remove();
        }
        double bx = f.planted ? f.x : f.ax, by = f.planted ? FLOOR : f.ay, bz = f.planted ? f.z : f.az;
        if (f.pillar > 0) mend(f.pillar, 12);
        f.pillar = 0;
        f.planted = true;
        if (!(Math.abs(bx - f.x) < 0.01 && Math.abs(bz - f.z) < 0.01)) {
            // 공중에서 끊김: 그 아래 바닥에 떨어져 눕는다
            f.x = Math.floor(bx) + 0.5;
            f.z = Math.floor(bz) + 0.5;
        }
        if (f.shade != null) f.shade.model(Body.EMPTY);
        if (f.part != null) {
            f.part.look.open = false;
            f.part.pose(fingerM(f, f.x, FLOOR, f.z, -82), 16);
        }
        f.fallUntil = age + 18;
        sfxAt(new Vector(bx, by, bz), "lift", Sound.ENTITY_WARDEN_DIG, 0.6f, 96);
        quake(new Vector(f.x, FLOOR, f.z), 30, 0.7);
        dirtySpheres();
        if (!dying && !intro) flinch(Tune.FLINCH_FINGER);
    }

    /** 3단계: 끊긴 손가락 하나가 그 자리에서 다시 자란다. */
    private void regrow(Finger f) {
        if (!spotFree(f.x, f.z, f)) {
            for (int tries = 0; tries < 16; tries++) {
                double a = game.random.nextDouble() * Math.PI * 2, r = 18 + game.random.nextDouble() * 18;
                double x = Math.floor(CX + Math.cos(a) * r) + 0.5, z = Math.floor(CZ + Math.sin(a) * r) + 0.5;
                if (!spotFree(x, z, f)) continue;
                f.x = x;
                f.z = z;
                break;
            }
        }
        f.alive = true;
        f.fallUntil = -1;
        f.region.cut = false;
        f.region.setLeft(1.0);
        f.turn = quarter(f.x, f.z);
        if (f.part != null) {
            f.part.model(M_FINGER);
            f.part.state("");
            f.part.pose(fingerM(f, f.x, FLOOR - 26, f.z, 0), 0);
        }
        f.planted = true;
        f.hidden = false;
        clearFoot(f.x, f.z);
        f.pillar = pillar(new Vector(f.x, FLOOR, f.z), Tune.PILLAR_R, Tune.PILLAR_H, Material.BONE_BLOCK, 20);
        f.riseAt = age + 2;
        sfxAt(new Vector(f.x, FLOOR, f.z), "lift", Sound.ENTITY_WARDEN_DIG, 0.6f, 96);
        updateShade(f);
        dirtySpheres();
    }

    private void tickFingers() {
        for (Finger f : fingers) {
            if (f.fallUntil >= 0 && age >= f.fallUntil) {
                f.fallUntil = -1;
                if (f.part != null) {
                    f.part.state("broken");
                    f.part.pose(fingerM(f, f.x, FLOOR, f.z, 0), 0);
                }
            }
            if (f.riseAt >= 0 && age >= f.riseAt) {
                f.riseAt = -1;
                if (f.part != null) f.part.pose(fingerM(f, f.x, FLOOR, f.z, 0), 20);
            }
        }
        if (shakeBackAt >= 0 && age >= shakeBackAt) {
            shakeBackAt = -1;
            if (face != null && !moving()) face.pose(faceFrame(fr, fh, fp).scale(Tune.FACE_S), 8);
        }
    }

    // ------------------------------------------------------------------ 눈빛(그늘, 빛 자리, 원뿔)

    /**
     * 그 사람이 눈빛에서 가려졌나. 손가락 그늘: 눈빛 자리에서 손가락 뒤쪽 좁은 띠 안일 때만 진짜 블록 광선으로 확인(가슴과 머리가 둘 다 기둥에 막혀야).
     * 지킬 것(트럭) 그늘: 그 뒤 10블록 안(그늘을 준 만큼 지킬 것이 빛을 맞는다).
     */
    private boolean shaded(Player p, Vector light) {
        Location l = p.getLocation();
        double lx = light.getX(), lz = light.getZ();
        for (Finger f : fingers) {
            if (!f.alive || !f.planted || f.hidden || f.pillar <= 0) continue;
            double ux = f.x - lx, uz = f.z - lz, d = Math.hypot(ux, uz);
            if (d < 1e-3) continue;
            ux /= d;
            uz /= d;
            double qx = l.getX() - f.x, qz = l.getZ() - f.z, along = qx * ux + qz * uz, side = Math.abs(qx * uz - qz * ux);
            if (along < 0.5 || along > 60 || side > 2.6 + along * 0.05) continue;
            if (hidden(light, p)) return true;
        }
        Ward w = ward();
        if (w != null && w.alive() && w.pos() != null) {
            Vector wp = w.pos();
            double ux = wp.getX() - lx, uz = wp.getZ() - lz, d = Math.hypot(ux, uz);
            if (d > 1e-3) {
                ux /= d;
                uz /= d;
                double qx = l.getX() - wp.getX(), qz = l.getZ() - wp.getZ(), along = qx * ux + qz * uz, side = Math.abs(qx * uz - qz * ux);
                if (along > 0 && along < w.radius() + 10 && side < w.radius()) {
                    wardLit(w);
                    return true;
                }
            }
        }
        return false;
    }

    /** 지킬 것이 눈빛을 맞음(1초에 한 번 WARD_GAZE). */
    private void wardLit(Ward w) {
        if (fightTicks - wardHitAt < 20) return;
        wardHitAt = fightTicks;
        hitWard(w, Tune.WARD_GAZE, "cause_b4_gaze");
    }

    /** 원정 테마(하늘과 안개 색을 쥠). 원정이 꺼진 판이면 null(그때는 세계 시각만으로 하늘이 바뀐다). */
    private Theme theme() {
        try {
            return game.trip != null && game.trip.enabled ? game.trip.part(Theme.class) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 눈을 뜬 동안 하늘이 밝아진다: 원정 테마가 색을 덮으므로 세계 시각(해 자리)과 함께 테마 시계를 hold 틱 동안 아침 쪽(themeTicks)에 둔다
     * (원정.md 2.1절 '구현 메모: 테마'). 눈을 감으면 {@link #lightOff} 가 푼다.
     */
    private void glow(int worldTime, int skyTicks, long themeTicks, int hold) {
        sky(worldTime, skyTicks);
        Theme th = theme();
        if (th != null) th.hold(themeTicks, hold);
        glowing = th != null;
    }

    private boolean glowing;

    private void lightOff() {
        for (Body.Part b : beam) if (b != null) b.model(Body.EMPTY);
        if (spotPart != null) spotPart.model(Body.EMPTY);
        lightNow = "-";
        spotX = spotZ = Double.NaN;
        if (glowing) {
            glowing = false;
            Theme th = theme();
            if (th != null) th.hold(0, 0);
        }
    }

    /** 바닥 빛 자리(상태 "" 또는 "rim")를 그 자리에. */
    private void showSpot(String state, Vector s, int ticks) {
        if (spotPart == null) return;
        spotPart.model(M_SPOT);
        spotPart.state(state);
        double y = game.mobs.flow.floor(s.getX(), s.getZ());
        spotPart.pose(at(s.getX(), y + 0.1, s.getZ()).scale((float) (Tune.SPOT_R * 2), 1f, (float) (Tune.SPOT_R * 2)), ticks);
    }

    /** 눈 하나에서 바닥 자리 s 로 뻗는 원뿔(끝이 바닥에서 반지름 r 원이 되게 세로를 누름). */
    private void showBeam(int eye, Vector s, double r, int ticks) {
        Body.Part b = beam[eye];
        if (b == null) return;
        Vector e = pupilPos(eye);
        double dx = s.getX() - e.getX(), dy = s.getY() + 0.1 - e.getY(), dz = s.getZ() - e.getZ(), len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1) return;
        double sinE = Math.max(0.12, Math.min(1.0, -dy / len));
        b.model(M_BEAM);
        b.pose(at(e.getX(), e.getY(), e.getZ()).rotateY((float) Math.atan2(dx, dz)).rotateX((float) Math.asin(Math.max(-1, Math.min(1, -dy / len))))
                .scale((float) (r / CONE_R), (float) (r * sinE / CONE_R), (float) (len / CONE_LEN)), ticks);
    }

    /**
     * 발사장 투광등 빛기둥 둘(역광): 처음 얼굴 자리 뒤 지평선 아래에서 하늘로, 경기장 쪽으로 조금 기울어 얼굴 윤곽을 뒤에서 비춘다. 발사장은 움직이지
     * 않으므로 얼굴이 다가와도 그 자리에 남는다. sway = -1, 0, 1(가운데 쪽으로 덜, 그대로, 더 기움).
     */
    private void poseLamps(int sway, int ticks) {
        double rb = Tune.FACE_R[0] + Tune.LAMP_BACK, px = -dirZ, pz = dirX;
        for (int i = 0; i < 2; i++) {
            Body.Part b = lamp[i];
            if (b == null) continue;
            int side = i == 0 ? 1 : -1;
            double ox = CX + dirX * rb + px * side * Tune.LAMP_SIDE, oz = CZ + dirZ * rb + pz * side * Tune.LAMP_SIDE;
            double lean = Tune.LAMP_LEAN + sway * Tune.LAMP_SWAY;
            double dx = -dirX * Tune.LAMP_IN - px * side * lean, dz = -dirZ * Tune.LAMP_IN - pz * side * lean, dy = Tune.LAMP_LEN;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            b.model(M_BEAM);
            b.pose(at(ox, FLOOR - 3, oz).rotateY((float) Math.atan2(dx, dz)).rotateX((float) Math.asin(-dy / len))
                    .scale((float) (Tune.LAMP_R / CONE_R), (float) (Tune.LAMP_R / CONE_R), (float) (len / CONE_LEN)), ticks);
        }
    }

    private void tickLamps() {
        if (lampSwayAt < 0 || age < lampSwayAt || dying) return;
        lampSwayAt = age + Tune.LAMP_PERIOD;
        lampSign = -lampSign;
        poseLamps(lampSign, Tune.LAMP_PERIOD);
    }

    /** 동공이 드러남: 금빛 화살촉(처음 한 번 도움말). */
    private void pointPupil(int eye, int ticks) {
        Vector at = pupilPos(eye);
        if (!pupilHinted) {
            pupilHinted = true;
            reveal(at, ticks);
        } else game.hud.point(null, at, ticks);
    }

    // ------------------------------------------------------------------ 소리(얼굴 쪽에서)

    /** 판에 든 사람 모두에게 그 사람에게서 얼굴 쪽 dist 블록 자리에서 나는 소리(pos 는 가운데라 바탕 sfxAll 을 쓰지 않는다). */
    void sfxFace(String name, Sound fallback, float volume, float pitch, float fallbackPitch, double dist) {
        double fx = faceX(fr), fz = faceZ(fr);
        for (Player p : game.players.inPlay()) {
            Location l = p.getLocation();
            double dx = fx - l.getX(), dz = fz - l.getZ(), d = Math.hypot(dx, dz), k = d < 1e-3 ? 0 : Math.min(dist, d) / d;
            Props.soundTo(p, new Location(game.world, l.getX() + dx * k, l.getY() + 2, l.getZ() + dz * k), "gabe.b4." + name, fallback, volume,
                    pitch * (0.97f + game.random.nextFloat() * 0.06f), fallbackPitch, -1);
        }
    }

    private void sfxAt(Vector at, String name, Sound fallback, float fallbackPitch, double range) {
        Props.soundAround(new Location(game.world, at.getX(), at.getY(), at.getZ()), "gabe.b4." + name, fallback, 1f, 0.95f + game.random.nextFloat() * 0.1f,
                fallbackPitch, range, -1);
    }

    private double faceYawDeg() {
        // HUD 화살촉: 가운데에서 본 얼굴 쪽 월드 yaw(0 남, 90 서, 180 북, 270 동)
        return Math.toDegrees(Math.atan2(-dirX, dirZ));
    }

    // ------------------------------------------------------------------ 등장(160틱)

    @Override
    protected void introStep(int t) {
        hold();
        tickFace();
        tickFingers();
        Vector c = new Vector(CX, FLOOR, CZ);
        if (t == 1) {
            // 종, 짙은 안개, 얼굴 쪽 주황 화살촉. 턱 둘레 땅은 처음부터 갈라져 있다
            fog(3);
            sfxFace("bell", Sound.BLOCK_BELL_USE, 1f, 1f, 0.5f, 28);
            game.hud.waveFrom(Hud.hasPhrase("bs_come") ? "bs_come" : null, 80, faceYawDeg());
            cracks(Tune.FACE_R[0] - 2, Tune.FACE_R[0] - 30, 5);
            // 사람들이 선 중앙 원에 큰 그림자가 드리운다(등장 동안 커지며 짙어짐: 다른 보스와 같은 바탕 연출, pos 가 가운데라 스스로 부름)
            if (bd != null) bd.shadowSweep(c, c, 14, introLength());
        }
        if (t == 30 || t == 60) sfxFace("bell", Sound.BLOCK_BELL_USE, 1f, t == 30 ? 0.94f : 0.88f, 0.5f, 28);
        if (t == 60) {
            // 안개가 걷히며 얼굴 뒤 발사장 투광등이 켜진다(역광: 솟는 얼굴의 윤곽이 빛기둥 사이에 드러남)
            poseLamps(0, 0);
            lampSwayAt = age + 40;
        }
        tickLamps();
        if (t == 20) {
            // 지평선에서 얼굴이 솟는다(100틱), 실눈이 감긴 채
            moveFace(Tune.FACE_R[0], Tune.FACE_H[0], Tune.FACE_PITCH[0], 100);
            sfxFace("sink", Sound.ENTITY_WARDEN_EMERGE, 1f, 1f, 0.4f, 30);
        }
        if (t == 50) fog(1);
        if (t >= 20 && t <= 120 && t % 25 == 0) quake(c, 80, 0.5);
        // 손가락 여섯이 12틱마다 하늘에서 내리꽂힌다: 12틱 빨간 원, 밑에 있으면 INTRO_STAB
        for (Finger f : fingers) {
            int k = t - (52 + 12 * f.index);
            if (k == 0) {
                disc(new Vector(f.x, FLOOR, f.z), Tune.INTRO_STAB_R, 12);
                f.alive = true;
                if (f.part != null) {
                    f.part.model(M_FINGER);
                    f.part.state("");
                    f.part.look.open = true;
                    f.part.pose(fingerM(f, f.x, FLOOR + 46, f.z, 0), 0);
                }
            } else if (k == 4 && f.part != null) f.part.pose(fingerM(f, f.x, FLOOR, f.z, 0), 8);
            else if (k == 12) {
                impact(new Vector(f.x, FLOOR, f.z), Tune.INTRO_STAB, Tune.INTRO_STAB_R, 0, 0, 0);
                f.plantAt = age + 5;
                f.hidden = false;
                f.planted = false;
                f.ax = f.x;
                f.ay = FLOOR;
                f.az = f.z;
            }
        }
        plantDue();
        if (t == 140) {
            // 포효 대신: 큰 종과 낮은 웅웅, 눈꺼풀이 떨린다(눈을 뜨려 한다)
            sfxFace("bell", Sound.BLOCK_BELL_USE, 1f, 0.8f, 0.5f, 20);
            sfxFace("hum", Sound.BLOCK_BEACON_AMBIENT, 1f, 1f, 0.8f, 24);
            game.horizon.shake(1.0, 30);
        }
        if (t >= 140 && t < 158 && t % 6 == 2) tremble(t);
        if (t == 158 && face != null) face.pose(faceFrame(fr, fh, fp).scale(Tune.FACE_S), 2);
    }

    /** 눈꺼풀 떨림: 얼굴이 아주 조금 끄덕임. */
    private void tremble(int t) {
        if (face == null || moving()) return;
        face.pose(faceFrame(fr, fh, fp + ((t / 6) % 2 == 0 ? 0.9 : -0.6)).scale(Tune.FACE_S), 5);
        shakeBackAt = age + 7;
    }

    /** 얼굴 밑(가운데에서 거리 from)에서 경기장 쪽(거리 to)으로 뻗는 금 n 개(바닥 무늬). */
    private void cracks(double from, double to, int n) {
        for (int i = 0; i < n; i++) {
            double off = (i - (n - 1) / 2.0) * 0.16 + (game.random.nextDouble() - 0.5) * 0.06, a0 = ang + off * 0.6, a1 = ang + off * 1.4;
            double f = Math.min(from, Plaza.R_EDGE - 1);
            crack(new Vector(CX + Math.cos(a0) * f, FLOOR, CZ + Math.sin(a0) * f), new Vector(CX + Math.cos(a1) * to, FLOOR, CZ + Math.sin(a1) * to),
                    i % 2 == 0 ? 2.4 : 1.6);
        }
    }

    /** 찍힌 자리: in 안 dmgIn, out 안 dmgOut, 넘어짐 root 틱, 바깥으로 밀침, 땅울림과 소리. 지킬 것이 걸치면 WARD_STAB. */
    private void impact(Vector at, double dmgIn, double in, double dmgOut, double out, int root) {
        // 손가락이 꽂힐 3 x 3 칸에 선 사람은 묶기 전에 칸 밖으로(묶임은 자리를 붙잡아 두므로 꽂힌 뒤에 옮기면 되돌려진다: 게임 안 시험)
        clearFoot(at.getX(), at.getZ());
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double d = Math.hypot(l.getX() - at.getX(), l.getZ() - at.getZ());
            double dmg = d <= in ? dmgIn : d <= out ? dmgOut : 0;
            if (dmg <= 0 || l.getY() > FLOOR + 6) continue;
            hitBoss(p, dmg, "cause_b4_stab");
            if (root > 0 && standing(p)) game.players.root(p, root, "boss");
            Vector o = new Vector(l.getX() - at.getX(), 0, l.getZ() - at.getZ());
            knock(p, o.lengthSquared() < 1e-6 ? new Vector(dirX, 0, dirZ).multiply(-1) : o, 0.9, 0.35);
        }
        Ward w = ward();
        if (w != null && w.alive() && w.pos() != null && Boss.horiz(w.pos(), at) <= Math.max(in, out) + w.radius()) hitWard(w, Tune.WARD_STAB, "cause_b4_stab");
        quake(at, 36, 0.9);
        sfxAt(at, "stab", Sound.BLOCK_ANVIL_LAND, 0.5f, 128);
    }

    private void plantDue() {
        for (Finger f : fingers) {
            if (f.plantAt < 0 || age < f.plantAt) continue;
            f.plantAt = -1;
            if (f.alive) plant(f);
        }
    }

    // ------------------------------------------------------------------ 싸움

    @Override
    protected void act() {
        hold();
        tickFace();
        tickFingers();
        tickLamps();
        plantDue();
        for (int i = 0; i < tally.length; i++) tally[i] *= 0.9977;
        tickStabs();
        tickTears();
        tickEmbers();
        tickLearn();
        if (phase >= 3 || cycle >= 3) tickRegrow();
        runAttacks();
        gaze.tick();
        // 눈을 뜬 채 도는 공격이 없으면 감는다(끊긴 공격의 뒷정리)
        if (anyEye() && running.isEmpty()) {
            setEyes(false, false);
            lightOff();
        }
    }

    private int phaseIx() {
        return Math.max(0, Math.min(2, phase - 1));
    }

    private boolean calm() {
        return fightTicks >= flinchUntil && !moving() && !inTransition();
    }

    /** 스스로 고르는 공격을 지금 써도 되나(시험 훅 '공격 quiet' 이면 안 씀). */
    private boolean auto() {
        return !manual && calm();
    }

    @Override
    protected void onPhase(int n) {
        int i = Math.max(0, Math.min(2, n - 1));
        // 다가온다, 넘어온다: 전이 50틱 동안 얼굴이 옮겨 가고 땅이 더 갈라진다
        setEyes(false, false);
        lightOff();
        moveFace(Tune.FACE_R[i], Tune.FACE_H[i], Tune.FACE_PITCH[i], Tune.MOVE_TICKS);
        sfxFace("sink", Sound.ENTITY_WARDEN_EMERGE, 1f, 1f, 0.4f, 24);
        sfxFace("bell", Sound.BLOCK_BELL_USE, 1f, 0.85f, 0.5f, 24);
        quake(new Vector(CX, FLOOR, CZ), 80, 0.9);
        cracks(Tune.FACE_R[i] - 2, Math.max(14, Tune.FACE_R[i] - 26), 4);
        // 배움: 지난 30초쯤 가장 많이 들어온 피해 종류가 덜 먹힌다(전이마다 하나 더)
        DamageType best = null;
        double bv = 0;
        for (DamageType t : DamageType.values()) {
            if (learned.contains(t) || tally[t.ordinal()] <= bv) continue;
            bv = tally[t.ordinal()];
            best = t;
        }
        if (best != null) {
            learned.add(best);
            learnNote = best;
            learnNoteAt = fightTicks + 56;
        }
        gazeReadyAt = Math.max(gazeReadyAt, fightTicks + Tune.MOVE_TICKS + 30);
        nextStabAt = Math.max(nextStabAt, fightTicks + Tune.MOVE_TICKS + 10);
        if (n >= 3) {
            glareReadyAt = fightTicks + Tune.GLARE_FIRST;
            nextRegrowAt = fightTicks + Tune.REGROW_EVERY;
        }
    }

    private void tickLearn() {
        if (learnNote == null || fightTicks < learnNoteAt) return;
        String key = "bs_b4_learn_" + learnNote.name().toLowerCase(Locale.ROOT);
        learnNote = null;
        if (Hud.hasPhrase(key)) game.hud.alert(key, 4, 60);
        sfxFace("learn", Sound.ENTITY_WARDEN_LISTENING_ANGRY, 1f, 1f, 0.7f, 20);
    }

    private void tickRegrow() {
        if (fightTicks < nextRegrowAt) return;
        nextRegrowAt = fightTicks + coolTicks(Tune.REGROW_EVERY);
        if (aliveFingers() >= Tune.REGROW_MAX) return;
        for (Finger f : fingers) {
            if (f.alive || f.fallUntil >= 0) continue;
            regrow(f);
            return;
        }
    }

    // ------------------------------------------------------------------ 손가락 찍기(공격 칸과 따로 제 박자로)

    /** 표적 자리로 손가락 하나를 보냄(가장 가까운, 꽂혀 있는 손가락). 보낼 손가락이 없으면 null. */
    private Stab startStab(Player who, Vector target, int delay) {
        Vector to = new Vector(Math.floor(target.getX()) + 0.5, FLOOR, Math.floor(target.getZ()) + 0.5);
        double r = Math.hypot(to.getX() - CX, to.getZ() - CZ);
        if (r < 6.5 || r > Plaza.R_ROAM - 2) {
            double k = Math.max(6.5, Math.min(Plaza.R_ROAM - 2, r)) / Math.max(1e-3, r);
            if (r < 1e-3) to.setX(CX + 7).setZ(CZ);
            else to.setX(Math.floor(CX + (to.getX() - CX) * k) + 0.5).setZ(Math.floor(CZ + (to.getZ() - CZ) * k) + 0.5);
        }
        Finger best = null;
        double bd0 = Double.MAX_VALUE;
        for (Finger f : fingers) {
            if (!f.alive || !f.planted || f.hidden || f.plantAt >= 0) continue;
            boolean busy = false;
            for (Stab s : stabs) busy |= s.f == f && !s.done;
            if (busy) continue;
            double d = Math.hypot(f.x - to.getX(), f.z - to.getZ());
            if (d < bd0) {
                bd0 = d;
                best = f;
            }
        }
        if (best == null) return null;
        // 다른 손가락 자리와 겹치지 않게 조금 비킴
        for (Finger o : fingers) {
            if (o == best || !o.alive) continue;
            if (Math.hypot(o.x - to.getX(), o.z - to.getZ()) < 4) {
                to.setX(to.getX() + (to.getX() >= o.x ? 4 : -4));
                break;
            }
        }
        Stab s = new Stab(best, to, who, delay);
        stabs.add(s);
        return s;
    }

    private void tickStabs() {
        if (!dying && !intro && fightTicks >= nextStabAt && auto() && aliveFingers() > 0) {
            nextStabAt = fightTicks + coolTicks(Tune.STAB_CD[phaseIx()]);
            autoStab(null);
        }
        for (Iterator<Stab> it = stabs.iterator(); it.hasNext();) {
            Stab s = it.next();
            if (!s.done) stepStab(s);
            if (s.done) it.remove();
        }
    }

    /** 찍기 한 번(2단계부터 두 손가락): 표적은 동공에 가장 많이 준 사람(없으면 위협 1위), 지킬 것이 있으면 셋에 한 번은 그것. */
    private Stab autoStab(Player forced) {
        Player who = forced != null ? forced : pick(Pick.DEALER, null, 0);
        Ward w = ward();
        Stab first = null;
        if (forced == null && w != null && w.alive() && w.pos() != null && game.random.nextInt(3) == 0) {
            // 지킬 것 곁(가장자리에서 2.5블록 얼굴 쪽)에 꽂는다: 빨간 원이 그것에 걸친다
            Vector wp = w.pos();
            first = startStab(null, new Vector(wp.getX() + dirX * (w.radius() + 2.5), FLOOR, wp.getZ() + dirZ * (w.radius() + 2.5)), 0);
        } else if (who != null) first = startStab(who, who.getLocation().toVector(), 0);
        if (first == null) return null;
        if (phase >= 2 || cycle >= 3) {
            Player other = null;
            List<Player> st = game.mobs.standing();
            if (st.size() > 1) {
                for (int i = 0; i < 6 && (other == null || other == who); i++) other = st.get(game.random.nextInt(st.size()));
                if (other == who) other = null;
            }
            if (other != null) startStab(other, other.getLocation().toVector(), 10);
            else if (who != null) {
                // 혼자면 달아날 쪽을 막는다: 표적에서 6블록 옆
                double a = game.random.nextDouble() * Math.PI * 2;
                startStab(who, who.getLocation().toVector().add(new Vector(Math.cos(a) * 6, 0, Math.sin(a) * 6)), 10);
            }
        }
        return first;
    }

    private void stepStab(Stab s) {
        Finger f = s.f;
        s.t++;
        if (s.t < 0) return;
        if (!f.alive) {
            s.done = true;
            if (s.tl != null) s.tl.remove();
            return;
        }
        int tell = s.tell, up = Math.min(16, tell / 2);
        if (s.t == 0) {
            // 뽑힘: 밑동 기둥이 꺼지고 손가락이 솟는다. 표적 자리에 빨간 원
            s.fromX = f.x;
            s.fromZ = f.z;
            s.tl = disc(s.to, Tune.STAB_R, tell);
            unplant(f, 10);
            if (f.part != null) {
                f.part.state("lift");
                f.part.look.open = true;
                f.part.pose(fingerM(f, f.x, FLOOR + 14, f.z, 0), up);
            }
            sfxAt(new Vector(f.x, FLOOR, f.z), "lift", Sound.ENTITY_WARDEN_DIG, 0.6f, 96);
            if (s.who != null && standing(s.who)) game.hud.mark(s.who, sp.key(), tell + 10);
        }
        if (s.t == up - 2) {
            // 뽑힌 자리는 구덩이로 남는다(15초 뒤 메워짐)
            mend(pit(new Vector(s.fromX, FLOOR, s.fromZ), 2.5, 2, 6), 30, 300);
        }
        if (s.t == up) {
            if (f.part != null) f.part.pose(fingerM(f, s.to.getX(), FLOOR + 16, s.to.getZ(), 0), Math.max(2, tell - up - 3));
        }
        if (s.t == tell - 3 && f.part != null) f.part.pose(fingerM(f, s.to.getX(), FLOOR, s.to.getZ(), 0), 3);
        // 맞는 구가 따라가는 자리
        if (s.t <= up) {
            f.ax = s.fromX;
            f.az = s.fromZ;
            f.ay = FLOOR + 14.0 * s.t / up;
        } else if (s.t < tell - 3) {
            double k = (s.t - up) / (double) Math.max(1, tell - up - 3);
            f.ax = s.fromX + (s.to.getX() - s.fromX) * k;
            f.az = s.fromZ + (s.to.getZ() - s.fromZ) * k;
            f.ay = FLOOR + 14 + 2 * k;
        } else {
            f.ax = s.to.getX();
            f.az = s.to.getZ();
            f.ay = FLOOR + Math.max(0, 16.0 * (tell - s.t) / 3.0);
        }
        dirtySpheres();
        if (s.t == tell) {
            impact(s.to, Tune.STAB_DMG_IN, Tune.STAB_IN, Tune.STAB_DMG_OUT, Tune.STAB_R, Tune.STAB_ROOT);
            f.x = s.to.getX();
            f.z = s.to.getZ();
            f.ax = f.x;
            f.ay = FLOOR;
            f.az = f.z;
            f.plantAt = age + 5;
            s.done = true;
        }
    }

    // ------------------------------------------------------------------ 눈물, 불씨 땅

    /** 눈물 한 방울: 빛 자리 둘레(가까운 사람 발밑)에 빨간 원. */
    private void dropTear(Vector spot) {
        Vector at = null;
        double best = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double d = Math.hypot(l.getX() - spot.getX(), l.getZ() - spot.getZ());
            if (d > 16 || d >= best) continue;
            boolean taken = false;
            for (Tear o : tears) taken |= o.tl != null && !o.tl.gone() && o.tl.inside(l.toVector(), 0);
            if (taken) continue;
            best = d;
            at = l.toVector();
        }
        if (at == null) {
            double a = game.random.nextDouble() * Math.PI * 2, r = Tune.SPOT_R + 1 + game.random.nextDouble() * 3;
            at = new Vector(spot.getX() + Math.cos(a) * r, FLOOR, spot.getZ() + Math.sin(a) * r);
        }
        Tear t = new Tear();
        t.tl = disc(at, Tune.TEAR_R, tellTicks(Tune.TEAR_TELL));
        t.at = fightTicks + tellTicks(Tune.TEAR_TELL);
        tears.add(t);
    }

    private void tickTears() {
        for (Iterator<Tear> it = tears.iterator(); it.hasNext();) {
            Tear t = it.next();
            if (fightTicks < t.at) continue;
            it.remove();
            if (t.tl == null || t.tl.gone()) continue;
            Vector at = t.tl.at;
            for (Player p : caught(t.tl, 0.2)) {
                hitBoss(p, Tune.TEAR_DMG, "cause_b4_tear");
                Vector o = p.getLocation().toVector().subtract(at).setY(0);
                knock(p, o.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : o, Tune.TEAR_KNOCK, 0.3);
            }
            sfxAt(at, "tear", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.7f, 64);
            game.shotFx.pop(new Location(game.world, at.getX(), at.getY() + 0.4, at.getZ()));
            if (game.random.nextDouble() < Tune.TEAR_G05 && Species.G05.implemented())
                summon(Species.G05, new Vector(at.getX(), game.mobs.flow.floor(at.getX(), at.getZ()) + Species.G05.band.hover, at.getZ()));
        }
    }

    private static double hash(int x, int z) {
        int h = x * 374761393 + z * 668265263 + 40499;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    /** 불씨 땅: 눈빛이 쓸고 간 자리가 그을리고 곳곳이 붉게 달아오른다(마그마 블록: 바닐라 피해는 판이 막고, 피해는 아래 구역 판정으로). */
    private void ember(Vector at) {
        Terrain t = land();
        if (t == null) return;
        int g = scorch(at, Tune.EMBER_R);
        int x0 = (int) Math.floor(at.getX() - Tune.EMBER_R), x1 = (int) Math.floor(at.getX() + Tune.EMBER_R);
        int z0 = (int) Math.floor(at.getZ() - Tune.EMBER_R), z1 = (int) Math.floor(at.getZ() + Tune.EMBER_R);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (Math.hypot(x + 0.5 - at.getX(), z + 0.5 - at.getZ()) > Tune.EMBER_R - 0.5 || hash(x, z) > 0.2) continue;
                if (t.depth(x + 0.5, z + 0.5) != 0 || t.height(x + 0.5, z + 0.5) != 0) continue;
                int g2 = t.set(x, FLOOR - 1, z, Material.MAGMA_BLOCK, g);
                if (g == 0) g = g2;
            }
        }
        if (g > 0) mend(g, 20, Tune.EMBER_LIFE);
        embers.add(new double[] {at.getX(), at.getZ(), Tune.EMBER_R, fightTicks + Tune.EMBER_LIFE});
    }

    private void tickEmbers() {
        if (embers.isEmpty()) return;
        embers.removeIf(e -> fightTicks >= e[3]);
        if (fightTicks % 20 != 0) return;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (l.getY() > FLOOR + 1.3) continue;
            for (double[] e : embers) {
                double dx = l.getX() - e[0], dz = l.getZ() - e[1];
                if (dx * dx + dz * dz > e[2] * e[2]) continue;
                hitBoss(p, Tune.EMBER_DMG, "cause_b4_ember");
                break;
            }
        }
    }

    // ------------------------------------------------------------------ 공격

    private static Vector clampArena(Vector v, double rMax) {
        double dx = v.getX() - CX, dz = v.getZ() - CZ, r = Math.hypot(dx, dz);
        if (r > rMax) v.setX(CX + dx / r * rMax).setZ(CZ + dz / r * rMax);
        return v;
    }

    /** 바라봄 ★: 한 눈(eye 0 = 게이브 왼쪽)을 떠 빛 자리(반지름 SPOT_R)가 사람을 쫓아 SWEEP_V 로 쓸고 간다. */
    private final class GazeAttack extends Attack {
        final int eye;
        final Vector spot = new Vector(), way = new Vector();
        int wayAt, nextTear, nextEmber;
        boolean on;

        GazeAttack(String name, int eye) {
            super(name, 1, 3, 1, Tune.GAZE_TELL, Pick.NONE, true);
            this.eye = eye;
        }

        @Override
        protected boolean usable() {
            return fightTicks >= gazeReadyAt && auto();
        }

        @Override
        protected void start() {
            on = false;
            // 처음 자리: 위협 1위(시험 훅이 대상을 주면 그 사람)에게서 무리 반대쪽으로 9블록 떨어진 곳. 거기서 그 사람을 향해 쓸기 시작
            Player first = target != null ? target : pick(Pick.TOP, this, 0);
            Vector c = crowdCenter(), to = first != null ? first.getLocation().toVector() : c.clone();
            Vector away = to.clone().subtract(c).setY(0);
            if (away.lengthSquared() < 1) away = new Vector(dirX, 0, dirZ);
            away.normalize().multiply(Tune.SPOT_R + 3);
            spot.copy(clampArena(to.clone().add(away), Plaza.R_ROAM - 1));
            spot.setY(FLOOR);
            way.copy(to);
            wayAt = 0;
            nextTear = Tune.TEAR_EVERY;
            nextEmber = Tune.EMBER_EVERY / 2;
            showSpot("rim", spot, 0);
            lightNow = "tell";
            sfxFace("lid", Sound.ENTITY_SLIME_SQUISH, 1f, 1f, 0.4f, 24);
            sfxFace("hum", Sound.BLOCK_BEACON_AMBIENT, 0.8f, 0.9f, 0.8f, 24);
            hintAll("hint_bs_shade", 60);
        }

        @Override
        protected boolean step() {
            int open = t - tellTicks;
            if (open < 0) {
                if (t % 6 == 3) tremble(t);
                if (t == tellTicks / 2) sfxFace("hum", Sound.BLOCK_BEACON_AMBIENT, 1f, 1.05f, 0.8f, 24);
                return false;
            }
            if (open >= Tune.GAZE_OPEN) return true;
            if (fightTicks < flinchUntil) {
                on = false;
                return false;
            }
            if (!on) {
                // 눈을 뜬다(움찔 뒤 다시 뜰 때도): 하늘이 밝아지고 동공이 드러난다
                on = true;
                setEyes(eye == 0, eye == 1);
                glow(22800, 30, Tune.THEME_GAZE, Tune.GAZE_OPEN - open + 5);
                pointPupil(eye, Math.min(80, Tune.GAZE_OPEN - open));
                sfxFace("hum", Sound.BLOCK_BEACON_AMBIENT, 1f, 1.2f, 0.8f, 24);
                lightNow = "gaze";
                showSpot("", spot, 0);
                showBeam(eye, spot, Tune.SPOT_R, 0);
            }
            // 길: 빛 자리에서 가장 가까운 서 있는 사람 쪽으로(없으면 무리 가운데)
            if (open >= wayAt) {
                wayAt = open + Tune.WAY_EVERY;
                Player near = null;
                double bd0 = 30;
                for (Player p : game.mobs.standing()) {
                    double d = Boss.horiz(p.getLocation().toVector(), spot);
                    if (d < bd0) {
                        bd0 = d;
                        near = p;
                    }
                }
                way.copy(near != null ? near.getLocation().toVector() : crowdCenter());
            }
            double dx = way.getX() - spot.getX(), dz = way.getZ() - spot.getZ(), l = Math.hypot(dx, dz);
            if (l > 0.3) {
                double v = Math.min(Tune.SWEEP_V, l);
                spot.setX(spot.getX() + dx / l * v).setZ(spot.getZ() + dz / l * v);
                clampArena(spot, Plaza.R_ROAM - 1);
            }
            spotX = spot.getX();
            spotZ = spot.getZ();
            if (open % 4 == 0) {
                showSpot("", spot, 4);
                showBeam(eye, spot, Tune.SPOT_R, 4);
            }
            if (open % 40 == 20) sfxFace("hum", Sound.BLOCK_BEACON_AMBIENT, 0.9f, 1.2f, 0.8f, 24);
            // 빛 자리 안이고 그늘이 아니면 변이 게이지
            Vector light = lightOrigin();
            for (Player p : game.mobs.standing()) {
                Location pl = p.getLocation();
                double qx = pl.getX() - spot.getX(), qz = pl.getZ() - spot.getZ();
                if (qx * qx + qz * qz > Tune.SPOT_R * Tune.SPOT_R) continue;
                if (!shaded(p, light)) gaze.lit(p, 1);
            }
            Ward w = ward();
            if (w != null && w.alive() && w.pos() != null && Boss.horiz(w.pos(), spot) <= Tune.SPOT_R + w.radius()) wardLit(w);
            if ((phase >= 2 || cycle >= 3) && open >= nextTear) {
                nextTear = open + Tune.TEAR_EVERY;
                dropTear(spot);
            }
            if (open >= nextEmber) {
                nextEmber = open + Tune.EMBER_EVERY;
                ember(spot.clone());
            }
            return false;
        }

        @Override
        protected void end() {
            setEyes(false, false);
            lightOff();
            if (!dying) sky(skyTime(), 40);
            gazeReadyAt = fightTicks + coolTicks(Tune.GAZE_CD[phaseIx()]);
        }
    }

    /** 온 눈뜸 ★(3단계): 두 눈이 천천히 뜨이고 GLARE_OPEN 틱 동안 경기장 전체가 빛 자리다. 손가락 그늘만 안전하다. */
    private final class GlareAttack extends Attack {
        boolean on;
        final java.util.Map<java.util.UUID, double[]> last = new java.util.HashMap<>();

        GlareAttack() {
            super("glare", 3, 3, 1, Tune.GLARE_TELL, Pick.NONE, true);
        }

        @Override
        protected boolean usable() {
            return fightTicks >= glareReadyAt && auto() && aliveFingers() > 0;
        }

        @Override
        protected void start() {
            on = false;
            last.clear();
            lightNow = "tell";
            if (Hud.hasPhrase("hint_bs_shade")) game.hud.alert("hint_bs_shade", 5, tellTicks);
            // 초록 고리: 손가락 그늘 안이 안전하다
            Vector l = lightOrigin();
            for (Finger f : fingers) {
                if (!f.alive || !f.planted || f.hidden) continue;
                double ux = f.x - l.getX(), uz = f.z - l.getZ(), d = Math.hypot(ux, uz);
                if (d < 1e-3) continue;
                safe(new Vector(f.x + ux / d * 3.6, FLOOR, f.z + uz / d * 3.6), 1.6, tellTicks + Tune.GLARE_OPEN);
            }
            sfxFace("lid", Sound.ENTITY_SLIME_SQUISH, 1f, 0.9f, 0.4f, 24);
            sfxFace("hum", Sound.BLOCK_BEACON_AMBIENT, 1f, 1f, 0.8f, 20);
            sky(23900, tellTicks);
        }

        @Override
        protected boolean step() {
            int open = t - tellTicks;
            if (open < 0) {
                if (t % 6 == 3) tremble(t);
                // 웅웅이 비명처럼 높아진다
                if (t % 15 == 0) sfxFace("hum", Sound.BLOCK_BEACON_AMBIENT, 1f, 1f + 0.8f * t / Math.max(1, tellTicks), 0.8f + 0.8f * t / Math.max(1, tellTicks), 20);
                return false;
            }
            if (open >= Tune.GLARE_OPEN) return true;
            if (fightTicks < flinchUntil) {
                on = false;
                return false;
            }
            if (!on) {
                on = true;
                setEyes(true, true);
                glow(23900, 10, Tune.THEME_GLARE, Tune.GLARE_OPEN - open + 5);
                pointPupil(0, Math.min(60, Tune.GLARE_OPEN - open));
                pointPupil(1, Math.min(60, Tune.GLARE_OPEN - open));
                lightNow = "glare";
                if (spotPart != null) {
                    spotPart.model(M_SPOT);
                    spotPart.state("flood");
                    spotPart.pose(at(CX, FLOOR + 0.04, CZ).scale(FLOOD_S, 1f, FLOOD_S), 0);
                }
                Vector c = new Vector(CX, FLOOR, CZ);
                showBeam(0, c.clone().add(new Vector(dirZ * 14, 0, -dirX * 14)), 22, 0);
                showBeam(1, c.clone().add(new Vector(-dirZ * 14, 0, dirX * 14)), 22, 0);
                sfxFace("hum", Sound.BLOCK_BEACON_AMBIENT, 1f, 1.8f, 1.6f, 16);
                game.horizon.shake(0.6, 20);
            }
            Vector light = lightOrigin();
            for (Player p : game.mobs.standing()) {
                if (shaded(p, light)) continue;
                double mul = 1;
                if (cycle >= 2) {
                    // 회차 2: 두 눈이 뜨인 동안 움직이면 두 배
                    Location pl = p.getLocation();
                    double[] was = last.get(p.getUniqueId());
                    if (was != null && Math.hypot(pl.getX() - was[0], pl.getZ() - was[1]) > 0.05) mul = 2;
                    last.put(p.getUniqueId(), new double[] {pl.getX(), pl.getZ()});
                }
                gaze.lit(p, mul);
            }
            Ward w = ward();
            if (w != null && w.alive()) wardLit(w);
            return false;
        }

        @Override
        protected void end() {
            setEyes(false, false);
            lightOff();
            if (!dying) sky(skyTime(), 40);
            glareReadyAt = fightTicks + coolTicks(Tune.GLARE_CD);
        }
    }

    /** 부름: 큰 한숨과 함께 얼굴 쪽에서 떼가 더 온다(감독 쏠림 덤). */
    private final class CallAttack extends Attack {
        CallAttack() {
            super("call", 1, 3, 1, Tune.CALL_TELL, Pick.NONE, false);
        }

        @Override
        protected boolean usable() {
            return fightTicks >= callReadyAt && auto();
        }

        @Override
        protected void start() {
            sfxFace("sigh", Sound.ENTITY_GHAST_AMBIENT, 1f, 1f, 0.4f, 26);
        }

        @Override
        protected boolean step() {
            if (!told()) return false;
            int n = minions((int) Math.round(Tune.CALL_BASE + Tune.CALL_PER * Math.sqrt(Math.max(1, game.players.inPlay().size()))));
            game.director.bossLean(ang, n);
            game.hud.waveFrom(Hud.hasPhrase("ev_dir") ? "ev_dir" : null, 60, faceYawDeg());
            quake(new Vector(CX, FLOOR, CZ), 60, 0.4);
            return true;
        }

        @Override
        protected void end() {
            callReadyAt = fightTicks + coolTicks(Tune.CALL_CD);
        }
    }

    /** 시험 훅 '공격 stab [대상]': 찍기 한 번(평소에는 공격 칸과 따로 제 박자로 돈다). */
    private final class StabAttack extends Attack {
        Stab job;

        StabAttack() {
            super("stab", 1, 3, 1, Tune.STAB_TELL, Pick.DEALER, false);
        }

        @Override
        protected void start() {
            job = autoStab(target);
        }

        @Override
        protected boolean step() {
            return job == null || job.done;
        }
    }

    /** 시험 훅 '공격 ward': 시험용 지킬 것(가운데, 반지름 2.5)을 올리고 내린다(트럭이 생기기 전에 지킬 것 길을 돌려 봄). 받은 피해는 상태 글의 ward=. */
    private final class WardAttack extends Attack {
        WardAttack() {
            super("ward", 1, 3, 1, 1, Pick.NONE, false);
        }

        @Override
        protected void start() {
            if (bd == null) return;
            if (testWard != null) {
                bd.unguard(testWard);
                testWard = null;
                return;
            }
            testWard = new Ward() {
                @Override
                public String key() {
                    return "test";
                }

                @Override
                public Vector pos() {
                    return new Vector(CX, FLOOR, CZ);
                }

                @Override
                public double radius() {
                    return 2.5;
                }

                @Override
                public boolean alive() {
                    return true;
                }

                @Override
                public void hurt(double amount, Boss by, String cause) {
                    wardHurt += amount;
                }
            };
            bd.guard(testWard);
        }

        @Override
        protected boolean step() {
            return true;
        }
    }

    // ------------------------------------------------------------------ 죽음(100틱), 승리, 정리

    @Override
    protected void startDying(Damage d) {
        super.startDying(d);
        gaze.clearAll();
        for (Stab s : stabs) if (s.tl != null) s.tl.remove();
        stabs.clear();
        tears.clear();
        setEyes(false, false);
        lightOff();
    }

    @Override
    protected void dyingStep(int t) {
        hold();
        tickFace();
        tickFingers();
        if (t == 1) {
            // 두 눈이 감긴다(눈물), 긴 한숨과 함께 지평선 아래로 가라앉는다
            showFace("sink");
            for (Finger f : fingers) {
                if (f.shade != null) f.shade.model(Body.EMPTY);
                if (f.part != null) f.part.look.open = false;
                // 공중에 있던 손가락은 그 아래에 내려놓는다
                if (f.alive && !f.planted) {
                    f.x = Math.floor(f.ax) + 0.5;
                    f.z = Math.floor(f.az) + 0.5;
                    f.planted = true;
                    f.plantAt = -1;
                    if (f.part != null) {
                        f.part.state("");
                        f.part.pose(fingerM(f, f.x, FLOOR, f.z, 0), 6);
                    }
                }
            }
            moveFace(fr, fh - 60, fp, 100);
            sfxFace("sigh", Sound.ENTITY_GHAST_AMBIENT, 1f, 0.9f, 0.4f, 24);
            sfxFace("sink", Sound.ENTITY_WARDEN_EMERGE, 1f, 0.9f, 0.4f, 30);
        }
        if (t % 30 == 10) quake(new Vector(CX, FLOOR, CZ), 80, 0.7);
        if (t == 60) sfxFace("sink", Sound.ENTITY_WARDEN_EMERGE, 0.9f, 0.8f, 0.4f, 30);
        if (t == 80) {
            // 밑동 기둥이 꺼지고 손가락이 바깥으로 기울기 시작한다(사체가 되면 그대로 쓰러짐)
            for (Finger f : fingers) {
                if (f.pillar > 0) mend(f.pillar, 8);
                f.pillar = 0;
            }
        }
        if (t == 84) {
            for (Finger f : fingers) {
                if (!f.alive || f.hidden || f.part == null) continue;
                f.part.pose(fingerM(f, f.x, FLOOR, f.z, -32), 16);
            }
            sfxAt(new Vector(CX, FLOOR, CZ), "lift", Sound.ENTITY_WARDEN_DIG, 0.6f, 96);
        }
        if (t == 90) fog(0);
    }

    /** 사체 직전: 얼굴, 뿔, 그늘, 빛은 지우고(가라앉음) 손가락만 남겨 바깥으로 쓰러지게 한다. 밑동 기둥은 뽑는다. */
    @Override
    protected void beforeCorpse() {
        List<Body.Part> gone = new ArrayList<>();
        gone.add(face);
        gone.add(hornL);
        gone.add(hornR);
        gone.add(beam[0]);
        gone.add(beam[1]);
        gone.add(spotPart);
        gone.add(lamp[0]);
        gone.add(lamp[1]);
        for (Finger f : fingers) {
            gone.add(f.shade);
            if (f.pillar > 0) mend(f.pillar, 8);
            f.pillar = 0;
            // 끊긴 손가락(그루터기)과 등장 전 손가락은 사체로 남기지 않는다
            if (!f.alive || f.hidden) gone.add(f.part);
        }
        for (Body.Part p : gone) if (p != null) p.remove();
        face = hornL = hornR = spotPart = null;
        beam[0] = beam[1] = lamp[0] = lamp[1] = null;
        for (Finger f : fingers) {
            f.shade = null;
            if (!f.alive || f.hidden) f.part = null;
        }
        dropWard();
    }

    /** 전멸: 두 눈을 활짝 뜨고 노란 빛이 경기장 전체를 덮는다. */
    @Override
    public void victory() {
        paused = true;
        cancelAttacks();
        clearTeles();
        gaze.clearAll();
        setEyes(true, true);
        lightNow = "victory";
        if (spotPart != null) {
            spotPart.model(M_SPOT);
            spotPart.state("flood");
            spotPart.pose(at(CX, FLOOR + 0.04, CZ).scale(FLOOD_S, 1f, FLOOD_S), 0);
        }
        Vector c = new Vector(CX, FLOOR, CZ);
        showBeam(0, c.clone().add(new Vector(dirZ * 14, 0, -dirX * 14)), 22, 0);
        showBeam(1, c.clone().add(new Vector(-dirZ * 14, 0, dirX * 14)), 22, 0);
        glow(23900, 40, Tune.THEME_GLARE, 400);
        sfxFace("hum", Sound.BLOCK_BEACON_AMBIENT, 1f, 1.6f, 1.4f, 16);
        sfxFace("bell", Sound.BLOCK_BELL_USE, 1f, 0.8f, 0.5f, 20);
    }

    private void dropWard() {
        if (testWard != null && bd != null) bd.unguard(testWard);
        testWard = null;
    }

    @Override
    public void remove() {
        gaze.clearAll();
        if (glowing) {
            glowing = false;
            Theme th = theme();
            if (th != null) th.hold(0, 0);
        }
        dropWard();
        super.remove();
    }

    @Override
    public void detached(Player victim, Player helper, String how) {
        gaze.detached(victim, helper, how);
    }

    // ------------------------------------------------------------------ 시험

    @Override
    public String status() {
        StringBuilder fs = new StringBuilder();
        for (Finger f : fingers) {
            fs.append(fs.length() == 0 ? "" : ",").append(f.hidden ? "h" : !f.alive ? "x" : f.planted ? "" : "^")
                    .append(String.format(Locale.ROOT, "%.0f/%.0f", f.planted ? f.x : f.ax, f.planted ? f.z : f.az));
        }
        StringBuilder ln = new StringBuilder();
        for (DamageType t : learned) ln.append(ln.length() == 0 ? "" : ",").append(t.name());
        Vector l = lightOrigin();
        return super.status() + String.format(Locale.ROOT,
                " | face=R%.0f,H%.0f,P%.0f dir=%.2f,%.2f manual=%d light=%s@%.1f,%.1f,%.1f spot=%.1f,%.1f eye=%s%s mul=%.1f flinch=%d fingers=%s stabs=%d tears=%d embers=%d learned=%s dose=%s ward=%s",
                fr, fh, fp, dirX, dirZ, manual ? 1 : 0, lightNow, l.getX(), l.getY(), l.getZ(), spotX, spotZ, eyeOpen[0] ? "L" : "-", eyeOpen[1] ? "R" : "-", main.mul,
                Math.max(0, flinchUntil - fightTicks), fs, stabs.size(), tears.size(), embers.size(), ln.length() == 0 ? "-" : ln, gaze.status(),
                testWard == null ? "-" : String.format(Locale.ROOT, "%.0f", wardHurt));
    }
}
