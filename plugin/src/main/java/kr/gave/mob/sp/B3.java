package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.mob.boss.Boss;
import kr.gave.mob.boss.Tele;
import kr.gave.mob.boss.Ward;
import kr.gave.trip.Launch;
import kr.gave.trip.PadLights;
import kr.gave.world.Plaza;

/**
 * B3 에워싸는 게이브(33라운드 보스: 4막 발사장, 우주선 건조 중. docs/모듈/보스.md 14절, 2026-11-12 사용자 'B3 를 보통 판 33 에').
 *
 * <p>6블록 _Gave 얼굴 열여섯(하나는 막대기 관을 쓴 7블록 술래)이 엔드 막대기를 엇갈려 맞잡고 경기장을 둘러싸 돌며 말 없는 노래(허밍)를 부르고 조여 온다.
 * 고리 가운데는 발사장(Launch.pad(): 건조 지점 셋의 가운데)이 서 있으면 그곳, 아니면 중앙 원. 그래서 33라운드에는 고리가 우주선과 건조 지점을 둘러싸고,
 * 사람들은 조여 오는 고리 안에서 지점을 지킨다(조이기 R 16 은 지점 원을 덮어 그동안 건조가 멈춘다).
 *
 * <p>고리 규칙: 고리 띠를 넘으려는 사람은 제 쪽으로 밀려나고 다친다. 얼굴이 깨진 자리(±18도)와 술래가 비운 자리는 틈이라 사람도 떼도 지나간다.
 * 감독이 낸 땅 게이브는 고리 바깥에 막혀 얼굴을 맞대고 기다리다 가까운 틈으로 몰린다.
 *
 * <p>공격: dash(술래 돌진: 표적을 지나 맞은편 고리까지 빨간 띠, 띠가 우주선을 지나면 건조가 깎임), rush(술래가 우주선을 노려 가운데를 가로지름),
 * clap(손뼉 파도 ★: 고리에서 가운데로 줄어드는 흰 파도, 틈 각은 안전, 고리 바깥 땅이 1블록 솟아 떼를 막는 턱), freeze(노래 멈춤 ★, 2단계부터:
 * 움직이면 들킴), 박치기(고리 안쪽 4블록 안 사람, 얼굴마다 제 박자), 조이기(2단계 R 32, 3단계 R 24 와 R 16 까지 조였다 풀기), 조각 쏟기(얼굴이 깨지면 G10 넷).
 * 맵: 고리가 선 둘레마다 땅이 갈라지고(금이 남아 얼마나 조였는지 땅이 기억), 고리가 넘어 조여 온 발사장 투광등은 짓밟혀 꺼진다(다시 켜는 발전기는 고리 밖).
 *
 * <p>몸: 가운데 빈 닻 + 세계 축 부품(술래, 고리 얼굴 열다섯, 고리 줄 열여섯, 손뼉 파도 판). 부위: face1..face15(얼굴마다 최대 체력의 3%, 다하면 깨짐),
 * lead(술래: 실눈 x0.25, 눈 뜸 x2 약점. 돌진과 노래 멈춤 동안 눈을 뜸).
 */
public final class B3 extends Boss {

    // ------------------------------------------------------------------ 숫자(한곳에: 난이도 배율로 맞출 때 여기만)

    static final class Tune {
        /** 고리 반지름(단계 1, 2, 3), 조이기 끝, 등장 때 솟는 자리, 전멸 때. 3단계는 문서의 22 를 24 로(건조 지점 원 14~22 가 고리 안에 남게). */
        static double[] RING_R = {40, 32, 24};
        static double SQUEEZE_R = 16, INTRO_R = 60, VICTORY_R = 7;
        /** 반지름 옮기는 틱(2단계, 3단계), 조이기 간격, 조여 드는 틱, 머무는 틱, 풀리는 틱, 3단계 들어 첫 조이기까지. */
        static int SHRINK2 = 200, SHRINK3 = 160, SQUEEZE_EVERY = 600, SQUEEZE_IN = 100, SQUEEZE_HOLD = 60, SQUEEZE_OUT = 100, SQUEEZE_FIRST = 240;
        /** 도는 빠르기(도/초, 단계 1, 2, 3). 반시계. */
        static double[] SPIN = {1.5, 1.5, 2.25};
        /** 부품 배율: 고리 얼굴 6블록, 술래 7블록, 고리 줄 굵기. */
        static float FACE_S = 6f, LEAD_S = 7f, LINK_S = 5f;
        /** 얼굴 가운데 높이(바닥 위, 다리 포함), 손 높이(얼굴 배율 몫), 들썩임 폭, 손뼉 때 줄 들어 올림, 고리 줄 길이에서 뺄 손 몫(블록). */
        static double FACE_Y = 4.3, LEAD_Y = 5.0, HAND_DY = -0.12, BOB = 0.45, LINK_LIFT = 1.6, LINK_GAP = 9.0;
        static int BOB_PERIOD = 40, SEND_EVERY = 4;

        /** 벽: 고리 띠 반폭, 넘으려 하면 피해와 밀침, 조여 올 때 미는 힘, 같은 사람 피해 간격, 틈 반폭(도). */
        static double BAND = 2.6, BUMP_DMG = 8, BUMP_PUSH = 0.6, SQUEEZE_PUSH = 0.3, GAP_DEG = 18;
        static int BUMP_EVERY = 20;
        /** 떼 막기: 고리 바깥 띠 끝, 기다리는 자리(R + 이것), 틈 찾는 각, 한 번에 옮기는 각. */
        static double HOLD_OUT = 8, HOLD_AT = 3, HOLD_SEEK = 90, HOLD_STEP = 6;

        /** 부위: 고리 얼굴 부위 체력(최대 체력의 몫), 맞는 구 반지름, 술래 실눈과 눈 뜸 배율. */
        static double FACE_HP = 0.03, FACE_RAD = 2.8, LEAD_RAD = 3.2, LEAD_SHUT = 0.25, LEAD_OPEN = 2;

        /** 술래 돌진: 예고, 다시 쓰기(단계 1, 2, 3), 띠 폭, 피해, 옆과 위로 튕김, 내달리는 빠르기(블록/틱), 젖힘(도), 돌아가는 틱. */
        static int DASH_TELL = 30, DASH_BACK = 60;
        static int[] DASH_CD = {240, 180, 140};
        static double DASH_W = 5, DASH_DMG = 45, DASH_SIDE = 1.2, DASH_UP = 0.5, DASH_SPEED = 1.8, DASH_TILT = -16;
        /** 우주선 노림(rush): 예고, 다시 쓰기. 띠가 우주선(또는 트럭)을 지나면: 건조 깎임(몫), 트럭 피해(Truck.WARD_FRAC 0.004 기준 20 = 8%), 우주선 반지름. */
        static int RUSH_TELL = 40, RUSH_CD = 720;
        static double BUILD_DASH = 0.04, WARD_DASH = 20, ROCKET_R = 3;

        /** 손뼉 파도: 예고, 다시 쓰기(1단계, 2~3단계), 빠르기(블록/틱, 가운데로), 피해, 위로 튕김. 고리 바깥 턱: R + RIM_OUT, 남는 틱. */
        static int CLAP_TELL = 24;
        static int[] CLAP_CD = {280, 200};
        static double CLAP_V = 0.7, CLAP_DMG = 20, CLAP_UP = 0.4, RIM_OUT = 3;
        static int RIM_LIFE = 240;

        /** 노래 멈춤: 예고(멈춤 → 눈 뜸), 지켜보는 틱, 다시 쓰기, 봐주는 틱, 피해, 가장 가까운 얼굴 쪽으로 끌림(세기, 위). */
        static int FREEZE_TELL = 10, FREEZE_WATCH = 50, FREEZE_CD = 500, FREEZE_GRACE = 3;
        static double FREEZE_DMG = 20, FREEZE_PULL = 0.8, FREEZE_UP = 0.4;

        /** 박치기: 예고, 얼굴마다 다시 쓰기, 고리 안쪽 거리, 피해, 안쪽으로 튕김, 젖힘과 내밂(도). */
        static int BUTT_TELL = 8, BUTT_CD = 40;
        static double BUTT_R = 4, BUTT_DMG = 25, BUTT_IN = 0.8, BUTT_TILT = -14, BUTT_LUNGE = 18;

        /** 얼굴이 깨질 때 쏟아지는 G10 수. 허밍 간격(1~2단계, 3단계). */
        static int SHARDS = 4, CHANT_EVERY = 80, CHANT_FAST = 56;
        /** 등장(솟음 + 걸어 들어옴), 솟기 전 깊이, 죽음 연출 틱, 사체로 남길 고리 얼굴 수(술래 + 이만큼). */
        static int INTRO = 140, RISE = 60, WALK = 80, DYING = 60, KEEP = 11;
        static double INTRO_SINK = 8;

        private Tune() {
        }
    }

    static final String M_LEAD = "gabe/b3", M_FACE = "gabe/b3_face", M_LINK = "gabe/b3_link", M_WAVE = "gabe/b1_wave";
    static final int N = 16, FLOOR = Plaza.FLOOR_Y;
    static final double STEP = 360.0 / N;
    static final int HOME = 0, TELL = 1, RUN = 2, BACK = 3;

    /** 고리 자리 하나(0 = 술래). */
    private final class Slot {
        final int i;
        Body.Part part;
        Region region;
        boolean broken, dirty, keep;
        /** 쓰러지는 중(깨진 뒤, 죽음 연출) 틱, 아니면 -1. */
        int fallT = -1;
        /** 박치기 예고 틱(-1 이면 없음), 다시 쓸 수 있는 싸움 틱. */
        int buttT = -1, buttReady;
        double x, y, z, yaw, tilt;

        Slot(int i) {
            this.i = i;
        }
    }

    private final Slot[] slots = new Slot[N];
    private final Body.Part[] links = new Body.Part[N];
    private final boolean[] snapped = new boolean[N];
    private Body.Part wavePart;
    private Region leadRegion;

    // 고리
    private double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5, theta0, spin, R = Tune.INTRO_R, rFrom, rTo, introY = -Tune.INTRO_SINK, bobNow, linkK;
    private int rT, rLen, sq, sqNext, sqHold;
    private final Set<Long> cracked = new HashSet<>();
    private boolean onPad;

    // 술래
    private int leadMode = HOME, backT;
    private double lx, lz, backA, backR, leadYaw = Double.NaN;
    private boolean leadOpen, freezeOpen, clapPose, chantMuted;
    private int chantAt;

    // 사람과 떼
    private final Map<UUID, Integer> side = new HashMap<>();
    private final Map<UUID, Double> lastR = new HashMap<>();
    private final Map<UUID, Integer> bumpAt = new HashMap<>();
    private final List<double[]> rims = new ArrayList<>();
    private final Map<Object, Boolean> lampIn = new IdentityHashMap<>();
    private int[] fallOrder;
    private int bumps, rocketHits, lampsSmashed, seenCount;

    public B3(Game g, Species s) {
        super(g, s, new double[] {0.60, 0.25});
        flying = true;
        baseSpeed = 0;
        radius = 1.0;
        ragHold = 400;
        fromGround = false;
        sweepShadow = false;
        for (int i = 0; i < N; i++) {
            Slot sl = new Slot(i);
            slots[i] = sl;
            if (i == 0) {
                sl.region = leadRegion = region("lead", Tune.LEAD_SHUT);
            } else {
                Region r = region("face" + i, 1.0);
                r.hpFrac = Tune.FACE_HP;
                r.onCut = x -> breakFace(sl);
                sl.region = r;
            }
        }
        for (int i = 0; i < 3; i++) attacks.add(new Dash("dash", i + 1, i + 1, Tune.DASH_CD[i], Tune.DASH_TELL, Pick.TOP, false));
        attacks.add(new Dash("rush", 1, 3, Tune.RUSH_CD, Tune.RUSH_TELL, Pick.NONE, true));
        attacks.add(new Clap(1, 1, Tune.CLAP_CD[0]));
        attacks.add(new Clap(2, 3, Tune.CLAP_CD[1]));
        attacks.add(new Freeze());
    }

    // ------------------------------------------------------------------ 자리, 하늘

    /** 고리 가운데: 발사장이 서 있으면 발사대(건조 지점 셋의 가운데), 아니면 중앙 원. 술래는 감독이 고른 쪽(사람들이 많이 보는 쪽)에. */
    @Override
    public Vector spawnPoint(Vector suggested) {
        Launch l = launch();
        if (l != null && l.built()) {
            Vector p = l.pad();
            cx = p.getX();
            cz = p.getZ();
            onPad = true;
        }
        double dx = suggested.getX() - cx, dz = suggested.getZ() - cz;
        theta0 = Math.hypot(dx, dz) > 1e-3 ? Math.toDegrees(Math.atan2(dz, dx)) : 90;
        return new Vector(cx, FLOOR + 3, cz);
    }

    @Override
    public int introLength() {
        return Tune.INTRO;
    }

    @Override
    public int dyingLength() {
        return Tune.DYING;
    }

    @Override
    public double shadowRadius() {
        return 0;
    }

    @Override
    public Vector waveCenter() {
        return new Vector(cx, FLOOR, cz);
    }

    private Launch launch() {
        try {
            return game.trip != null && game.trip.enabled ? game.trip.part(Launch.class) : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 몸

    @Override
    protected void build() {
        body = Body.carrier(this);
        body.viewRange(4.0f);
        // 사체로 남길 것부터(술래, 얼굴 1..15), 그다음 고리 줄, 파도 판
        for (Slot s : slots) {
            s.part = part(s.i == 0 ? M_LEAD : M_FACE);
            if (s.part != null) {
                s.part.follow = false;
                s.part.look.phase = (s.i * 5) % 40;
            }
            s.keep = s.i <= Tune.KEEP;
        }
        for (int k = 0; k < N; k++) {
            links[k] = part(M_LINK);
            if (links[k] != null) links[k].follow = false;
        }
        wavePart = part(M_WAVE);
        if (wavePart != null) {
            wavePart.follow = false;
            wavePart.model(Body.EMPTY);
        }
        layout();
        sendRing(0);
    }

    private Matrix4f at(double x, double y, double z) {
        return new Matrix4f().translate((float) (x - pos.getX()), (float) (y - pos.getY()), (float) (z - pos.getZ()));
    }

    /** 자리 i 의 지금 각(도, 0 = +x, 반시계 = +). */
    private double ang(int i) {
        return theta0 + spin + STEP * i;
    }

    private double px(double aDeg, double r) {
        return cx + Math.cos(Math.toRadians(aDeg)) * r;
    }

    private double pz(double aDeg, double r) {
        return cz + Math.sin(Math.toRadians(aDeg)) * r;
    }

    private Vector ringPt(double aDeg, double r) {
        return new Vector(px(aDeg, r), FLOOR, pz(aDeg, r));
    }

    /** a 에서 b 로 가는 가장 짧은 각(도, -180..180). */
    private static double delta(double a, double b) {
        double d = (b - a) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private double angOf(double x, double z) {
        return Math.toDegrees(Math.atan2(z - cz, x - cx));
    }

    private double distOf(double x, double z) {
        return Math.hypot(x - cx, z - cz);
    }

    private double spinRate() {
        return Tune.SPIN[Math.max(0, Math.min(2, phase - 1))] / 20.0;
    }

    /** 얼굴과 술래의 지금 자리(세계), 보는 방향. */
    private void layout() {
        bobNow = Tune.BOB * Math.sin(2 * Math.PI * (game.now() % Tune.BOB_PERIOD) / Tune.BOB_PERIOD);
        for (Slot s : slots) {
            double a = ang(s.i);
            boolean away = s.i == 0 && leadMode != HOME;
            s.x = away ? lx : px(a, R);
            s.z = away ? lz : pz(a, R);
            s.y = FLOOR + (s.i == 0 ? Tune.LEAD_Y : Tune.FACE_Y) + bobNow + introY;
            s.yaw = s.i == 0 && !Double.isNaN(leadYaw) ? leadYaw : Math.atan2(cx - s.x, cz - s.z);
        }
    }

    private Matrix4f slotM(Slot s) {
        float sc = s.i == 0 ? Tune.LEAD_S : Tune.FACE_S;
        return at(s.x, s.y, s.z).rotateY((float) s.yaw).rotateX((float) Math.toRadians(s.tilt)).scale(sc);
    }

    /** 몸 전체를 보냄(얼굴 상태, 자세, 고리 줄). */
    private void sendRing(int interp) {
        for (Slot s : slots) {
            if (s.part == null || s.fallT >= 0) continue;
            if (s.i == 0) s.part.state(leadMode == TELL || leadMode == RUN ? "dash" : clapPose ? "clap" : "");
            else s.part.state(s.broken ? "crack" : clapPose ? "clap" : "");
            s.part.pose(slotM(s), interp);
            s.dirty = false;
        }
        for (int k = 0; k < N; k++) sendLink(k, interp);
    }

    /** 고리 줄 k(자리 k 와 k+1 사이): 두 얼굴 집 자리 사이 가운데, 접선 방향, 길이에 맞춰 x 배율. 한쪽이 비면 끊겨 늘어짐. */
    private void sendLink(int k, int interp) {
        Body.Part p = links[k];
        if (p == null) return;
        int j = (k + 1) % N;
        double a0 = ang(k), a1 = ang(j);
        double x0 = px(a0, R), z0 = pz(a0, R), x1 = px(a1, R), z1 = pz(a1, R), tx = x1 - x0, tz = z1 - z0;
        boolean sn = snapped[k] || (k == 0 || j == 0) && leadMode != HOME;
        boolean gone = slots[k].broken && slots[j].broken;
        double len = gone ? 0.02 : Math.max(1.0, Math.hypot(tx, tz) - Tune.LINK_GAP) * linkK;
        p.state(sn ? "snap" : "");
        double y = FLOOR + Tune.FACE_Y + Tune.HAND_DY * Tune.FACE_S + bobNow + introY + (clapPose && !sn ? Tune.LINK_LIFT : 0);
        float phi = (float) Math.atan2(-tz, tx);
        float w = gone ? 0.02f : Tune.LINK_S;
        p.pose(at((x0 + x1) / 2, y, (z0 + z1) / 2).rotateY(phi).scale((float) Math.max(0.02, len), w, w), interp);
    }

    @Override
    protected void refreshSpheres() {
        for (Slot s : slots) {
            if (s.broken || s.part == null || s.fallT >= 0) continue;
            sphere(s.region, s.x, s.y, s.z, s.i == 0 ? Tune.LEAD_RAD : Tune.FACE_RAD);
        }
    }

    // ------------------------------------------------------------------ 눈

    private void setLeadOpen(boolean open) {
        leadOpen = open;
        eyes();
    }

    private void setFreezeEyes(boolean open) {
        freezeOpen = open;
        eyes();
    }

    /** 실눈 문법: 술래는 노릴 때(돌진, 돌아가는 동안), 모두는 노래가 멈췄을 때 눈을 뜬다. 술래가 눈을 뜨면 약점(x2). */
    private void eyes() {
        for (Slot s : slots) {
            if (s.part == null) continue;
            s.part.look.open = freezeOpen || s.i == 0 && leadOpen;
        }
        boolean weak = leadOpen || freezeOpen;
        leadRegion.mul = weak ? Tune.LEAD_OPEN : Tune.LEAD_SHUT;
        leadRegion.weak = weak;
    }

    // ------------------------------------------------------------------ 틈

    private boolean gapSlot(int i) {
        return slots[i].broken || i == 0 && leadMode != HOME;
    }

    /** 그 각(도)이 틈인가(깨진 얼굴, 술래가 비운 자리 ±GAP_DEG). */
    private boolean inGap(double aDeg) {
        for (int i = 0; i < N; i++) if (gapSlot(i) && Math.abs(delta(aDeg, ang(i))) <= Tune.GAP_DEG) return true;
        return false;
    }

    /** within 도 안에서 가장 가까운 틈의 각, 없으면 NaN. */
    private double nearestGap(double aDeg, double within) {
        double best = Double.NaN, bd = within;
        for (int i = 0; i < N; i++) {
            if (!gapSlot(i)) continue;
            double d = Math.abs(delta(aDeg, ang(i)));
            if (d <= bd) {
                bd = d;
                best = ang(i);
            }
        }
        return best;
    }

    private int nearestSlot(double aDeg) {
        int j = (int) Math.round(delta(ang(0), aDeg) / STEP);
        return ((j % N) + N) % N;
    }

    // ------------------------------------------------------------------ 등장

    @Override
    protected void introStep(int t) {
        if (t == 1) {
            sfxAll("gabe.b3.rise", Sound.ENTITY_WARDEN_EMERGE, 1f, 0.6f, 24);
            chantAll(0.35f);
        }
        if (t <= Tune.RISE) {
            introY = -Tune.INTRO_SINK * (1 - t / (double) Tune.RISE);
            R = Tune.INTRO_R;
            linkK = 0;
        } else {
            introY = 0;
            double k = Math.min(1, (t - Tune.RISE) / (double) Tune.WALK);
            R = Tune.INTRO_R + (Tune.RING_R[0] - Tune.INTRO_R) * k;
            linkK = k;
        }
        if (t == 20 || t == 45) {
            quake(new Vector(cx, FLOOR, cz), 160, 0.6);
            for (int i = 0; i < N; i += 4) sfxAt(slots[i], "rise", Sound.ENTITY_WARDEN_EMERGE, 1f, 0.6f, 160);
        }
        if (t == Tune.RISE + 30) for (int i = 0; i < N; i += 2) sfxAt(slots[i], "link", Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 1f, 0.6f, 48);
        if (t % 40 == 0) chantAll((float) (0.35 + 0.65 * t / Tune.INTRO));
        spin += spinRate();
        layout();
        if (t > Tune.RISE) wallTick(true);
        if (t % Tune.SEND_EVERY == 0) sendRing(Tune.SEND_EVERY);
        if (t >= Tune.INTRO) {
            R = Tune.RING_R[0];
            linkK = 1;
            crackRing(R);
            chantAt = 0;
        }
    }

    // ------------------------------------------------------------------ 싸움

    @Override
    protected void act() {
        hold();
        tickRadius();
        spin += spinRate();
        layout();
        tickBack();
        wallTick(false);
        if (fightTicks % 2 == 0) findButts();
        tickButts();
        if (fightTicks % 10 == 0) {
            holdHorde();
            tickRims();
            tickLamps();
        }
        tickChant();
        for (Attack a : attacks) if (a instanceof Dash d && !d.pad) d.range = Math.max(4, R - 1);
        runAttacks();
        tickFalls();
        if (fightTicks % Tune.SEND_EVERY == 0) sendRing(Tune.SEND_EVERY);
        else {
            if (leadMode == RUN || leadMode == BACK) slots[0].dirty = true;
            for (Slot s : slots) {
                if (!s.dirty || s.part == null || s.fallT >= 0) continue;
                s.part.pose(slotM(s), 2);
                s.dirty = false;
            }
        }
    }

    private void moveR(double to, int ticks) {
        rFrom = R;
        rTo = to;
        rT = 0;
        rLen = Math.max(1, ticks);
    }

    private boolean rMoving() {
        return rT < rLen;
    }

    /** 반지름 옮기기와 3단계 조이기(R 24 → 16 → 머묾 → 24). 다 옮기면 그 둘레에 새 금. */
    private void tickRadius() {
        if (rT < rLen) {
            rT++;
            double k = rT / (double) rLen;
            k = k * k * (3 - 2 * k);
            R = rFrom + (rTo - rFrom) * k;
            if (rT >= rLen) {
                R = rTo;
                crackRing(R);
            }
            return;
        }
        if (phase < 3) return;
        switch (sq) {
            case 0 -> {
                if (fightTicks >= sqNext) {
                    moveR(Tune.SQUEEZE_R, Tune.SQUEEZE_IN);
                    sq = 1;
                    chantAll(1.15f);
                    for (int i = 1; i < N; i += 3) sfxAt(slots[i], "link", Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 1f, 0.5f, 64);
                }
            }
            case 1 -> {
                sqHold = fightTicks + Tune.SQUEEZE_HOLD;
                sq = 2;
            }
            case 2 -> {
                if (fightTicks >= sqHold) {
                    moveR(Tune.RING_R[2], Tune.SQUEEZE_OUT);
                    sq = 3;
                }
            }
            default -> {
                sqNext = fightTicks + coolTicks(Tune.SQUEEZE_EVERY);
                sq = 0;
            }
        }
    }

    @Override
    protected void onPhase(int n) {
        if (n == 2) moveR(Tune.RING_R[1], Tune.SHRINK2);
        if (n == 3) {
            moveR(Tune.RING_R[2], Tune.SHRINK3);
            sq = 0;
            sqNext = fightTicks + Tune.SHRINK3 + Tune.SQUEEZE_FIRST;
        }
        chantAll(1.2f);
        for (int i = 0; i < N; i += 2) sfxAt(slots[i], "link", Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 1f, 0.5f, 64);
    }

    /** 땅이 기억하는 고리: 그 반지름 둘레(32 마디)에 갈라진 금. 같은 반지름은 한 번. */
    private void crackRing(double r) {
        if (!cracked.add(Math.round(r * 2))) return;
        int n = 32;
        for (int k = 0; k < n; k++) {
            double a0 = k * 360.0 / n, a1 = (k + 1) * 360.0 / n;
            crack(ringPt(a0, r), ringPt(a1, r), 1.2);
        }
    }

    // ------------------------------------------------------------------ 벽(고리 띠), 떼 막기

    /**
     * 고리 띠(|r - R| < BAND, 틈이 아닌 각)에 든 사람: 제 쪽(안이면 안, 밖이면 밖)으로 밀림. 제 힘으로 넘으려 하면(반지름이 반대쪽으로 늘면) 8 피해와 밀침
     * (20틱에 한 번). 고리가 조여 와 덮친 사람은 피해 없이 밀기만. harmless 면 늘 밀기만(등장).
     */
    private void wallTick(boolean harmless) {
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - cx, dz = l.getZ() - cz, r = Math.hypot(dx, dz), d = r - R;
            UUID id = p.getUniqueId();
            Double lr = lastR.put(id, r);
            if (Math.abs(d) >= Tune.BAND || r < 1e-3) {
                side.put(id, d < 0 ? -1 : 1);
                continue;
            }
            if (inGap(Math.toDegrees(Math.atan2(dz, dx)))) continue;
            int s = side.computeIfAbsent(id, k -> d < 0 ? -1 : 1);
            Vector toSide = new Vector(dx / r * s, 0, dz / r * s);
            boolean crossing = lr != null && (s < 0 ? r > lr + 0.04 : r < lr - 0.04);
            if (crossing && !harmless) {
                Integer at = bumpAt.get(id);
                if (at == null || fightTicks - at >= Tune.BUMP_EVERY) {
                    bumpAt.put(id, fightTicks);
                    hitBoss(p, Tune.BUMP_DMG, "cause_b3_bump");
                    Props.soundAround(l, "gabe.b3.bump", Sound.ENTITY_SLIME_ATTACK, 1f, 1f, 0.6f, 32, -1);
                    bumps++;
                }
                knock(p, toSide, Tune.BUMP_PUSH, 0.15);
            } else game.players.push(p, toSide.multiply(Tune.SQUEEZE_PUSH));
        }
    }

    /** 감독이 낸 땅 게이브가 고리 바깥 띠(R - 1 ~ R + HOLD_OUT)에 있고 틈 각이 아니면 고리 바깥(R + HOLD_AT)에 붙어 기다리거나 가까운 틈 쪽으로 옮겨 감. */
    private void holdHorde() {
        double lo = R - 1, hi = R + Tune.HOLD_OUT;
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g == this || g.flying || g.untargetable || g.sp.is(Role.BOSS)) continue;
            double r = distOf(g.pos.getX(), g.pos.getZ());
            if (r < lo || r > hi) continue;
            if (!game.director.fromDirector(g)) continue;
            double a = angOf(g.pos.getX(), g.pos.getZ());
            if (inGap(a)) continue;
            double ga = nearestGap(a, Tune.HOLD_SEEK);
            double to = Double.isNaN(ga) ? a : a + Math.max(-Tune.HOLD_STEP, Math.min(Tune.HOLD_STEP, delta(a, ga)));
            Vector at = ringPt(to, R + Tune.HOLD_AT);
            at.setY(g.pos.getY());
            g.lure(at, 20);
        }
    }

    // ------------------------------------------------------------------ 박치기(얼굴마다 제 박자)

    private void findButts() {
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double r = distOf(l.getX(), l.getZ()), in = R - r;
            if (in < -0.5 || in > Tune.BUTT_R) continue;
            double a = angOf(l.getX(), l.getZ());
            int j = nearestSlot(a);
            Slot s = slots[j];
            if (s.broken || s.fallT >= 0 || j == 0 && leadMode != HOME || s.buttT >= 0 || fightTicks < s.buttReady) continue;
            if (Math.abs(delta(a, ang(j))) > STEP * 0.6) continue;
            s.buttT = 0;
        }
    }

    private void tickButts() {
        for (Slot s : slots) {
            if (s.buttT < 0) continue;
            if (s.broken || s.fallT >= 0 || s.i == 0 && leadMode != HOME) {
                s.buttT = -1;
                s.tilt = 0;
                continue;
            }
            s.buttT++;
            int tell = tellTicks(Tune.BUTT_TELL);
            if (s.buttT == 1) {
                s.tilt = Tune.BUTT_TILT;
                s.dirty = true;
            } else if (s.buttT == tell) {
                s.tilt = Tune.BUTT_LUNGE;
                s.dirty = true;
                s.buttReady = fightTicks + coolTicks(Tune.BUTT_CD);
                boolean hitAny = false;
                for (Player p : game.mobs.standing()) {
                    Location l = p.getLocation();
                    double r = distOf(l.getX(), l.getZ()), in = R - r;
                    if (in < -0.5 || in > Tune.BUTT_R + 0.5 || nearestSlot(angOf(l.getX(), l.getZ())) != s.i) continue;
                    hitBoss(p, Tune.BUTT_DMG, "cause_b3_butt");
                    knock(p, new Vector(cx - l.getX(), 0, cz - l.getZ()), Tune.BUTT_IN, 0.25);
                    kick(p, 0.8);
                    hitAny = true;
                }
                sfxAt(s, "butt", Sound.ENTITY_IRON_GOLEM_ATTACK, hitAny ? 1f : 0.7f, 0.6f, 48);
            } else if (s.buttT >= tell + 6) {
                s.tilt = 0;
                s.dirty = true;
                s.buttT = -1;
            }
        }
    }

    // ------------------------------------------------------------------ 깨짐, 쓰러짐

    /** 얼굴 부위 체력이 다함: 틈, 금 간 얼굴, 이웃 고리 줄 끊김, G10 조각 넷이 안쪽으로, 그 자리 턱이 꺼짐, 얼굴은 바깥으로 쓰러져 지워짐. */
    private void breakFace(Slot s) {
        if (s.broken || s.i == 0) return;
        s.broken = true;
        s.buttT = -1;
        if (s.part != null) s.part.state("crack");
        sfxAt(s, "break", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.7f, 96);
        quake(new Vector(s.x, FLOOR, s.z), 28, 0.6);
        for (int k : new int[] {(s.i + N - 1) % N, s.i}) {
            if (snapped[k]) continue;
            snapped[k] = true;
            sendLink(k, 4);
        }
        sfxAt(s, "link", Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 1f, 0.6f, 48);
        double a = ang(s.i);
        int n = minions(Tune.SHARDS);
        for (int k = 0; k < n; k++) {
            double aa = a + (k - (n - 1) / 2.0) * 3;
            summon(Species.G10, new Vector(px(aa, R - 3), FLOOR + Species.G10.band.hover, pz(aa, R - 3)));
        }
        s.fallT = 0;
        tickRims();
        dirtySpheres();
    }

    /** 쓰러지는 얼굴: 첫 틱에 바깥으로 넘어가는 자세(보간), 깨진 얼굴은 20틱 뒤 지움. 죽음 연출의 얼굴은 남김(사체 직전 정리). */
    private void tickFalls() {
        for (Slot s : slots) {
            if (s.fallT < 0 || s.part == null) continue;
            s.fallT++;
            if (s.fallT == 1) {
                s.tilt = -80;
                double down = s.i == 0 ? Tune.LEAD_Y - 2.2 : Tune.FACE_Y - 2.0;
                float sc = (s.i == 0 ? Tune.LEAD_S : Tune.FACE_S) * (s.broken || !s.keep ? 0.05f : 1f);
                s.part.pose(at(s.x, s.y - down, s.z).rotateY((float) s.yaw).rotateX((float) Math.toRadians(s.tilt)).scale(sc), dying ? 14 : 20);
            } else if (s.broken && !dying && s.fallT >= 21) {
                s.part.remove();
                s.part = null;
            }
        }
    }

    // ------------------------------------------------------------------ 맵: 턱(손뼉 때 솟음), 투광등

    /** 손뼉: 틈이 아닌 마디마다 고리 바깥(R + RIM_OUT) 땅이 1블록 솟아 떼를 막는 턱(RIM_LIFE 틱 뒤 꺼짐). */
    private void raiseRims() {
        double r = R + Tune.RIM_OUT;
        for (int i = 0; i < N; i++) {
            double a = ang(i);
            if (inGap(a)) continue;
            int g = wall(ringPt(a - STEP / 2 + 1, r), ringPt(a + STEP / 2 - 1, r), 1, Material.COARSE_DIRT, 6);
            if (g > 0) rims.add(new double[] {g, a, fightTicks + Tune.RIM_LIFE});
        }
    }

    /** 턱: 때가 되면 꺼지고, 틈 각에 든 턱은 바로 꺼짐(떼가 그리로 쏟아짐). */
    private void tickRims() {
        for (Iterator<double[]> it = rims.iterator(); it.hasNext();) {
            double[] r = it.next();
            if (fightTicks >= r[2]) {
                mend((int) r[0], 20);
                it.remove();
            } else if (inGap(r[1])) {
                mend((int) r[0], 6);
                it.remove();
            }
        }
    }

    /** 발사장 투광등(공용 PadLights): 고리가 등 자리를 넘어 조여 오면 얼굴들이 짓밟아 끈다(다시 켜는 밑동 발전기는 이제 고리 밖). */
    private void tickLamps() {
        PadLights pl;
        try {
            pl = PadLights.peek(game);
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        if (pl == null || !pl.active()) return;
        for (Mobs.Light l : pl.lights()) {
            if (l.gone()) continue;
            Vector lp = l.pos();
            boolean in = distOf(lp.getX(), lp.getZ()) < R - 0.5;
            Boolean was = lampIn.put(l, in);
            if (was != null && was && !in && l.lit()) {
                pl.smash(l, this);
                lampsSmashed++;
                Props.soundAround(new Location(game.world, lp.getX(), FLOOR + 2, lp.getZ()), "gabe.b3.stomp", Sound.ENTITY_RAVAGER_STEP, 1f, 1f, 0.5f, 96, -1);
                quake(new Vector(lp.getX(), FLOOR, lp.getZ()), 24, 0.7);
            }
        }
    }

    // ------------------------------------------------------------------ 우주선(트럭 대신)

    /** 띠가 지나는 우주선 자리(발사장이 서 있으면 고리 가운데의 발사대, 아니면 가장 가까운 지킬 것), 없으면 null. */
    private Vector rocketAt() {
        Launch l = launch();
        if (l != null && l.built()) return new Vector(cx, FLOOR, cz);
        Ward w = ward();
        return w != null && w.alive() && w.pos() != null ? w.pos().clone() : null;
    }

    /** 술래가 우주선을 들이받음: 발사장이면 건조가 깎이고(그 라운드 몫 안에서), 아니면 지킬 것(트럭)이 다친다. */
    private void hurtRocket(String cause) {
        Launch l = launch();
        rocketHits++;
        Vector at = rocketAt();
        if (at != null) quake(at, 40, 1.0);
        if (l != null && l.built()) {
            l.damageBuild(Tune.BUILD_DASH, cause);
            Props.soundAround(new Location(game.world, cx, FLOOR + 3, cz), "gabe.b3.butt", Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 1f, 0.5f, 96, -1);
            return;
        }
        Ward w = ward();
        if (w != null && w.alive()) hitWard(w, Tune.WARD_DASH, cause);
    }

    // ------------------------------------------------------------------ 소리

    private void sfxAt(Slot s, String name, Sound fb, float vol, float fbPitch, double range) {
        Props.soundAround(new Location(game.world, s.x, s.y, s.z), "gabe.b3." + name, fb, vol, 0.94f + game.random.nextFloat() * 0.12f, fbPitch, range, -1);
    }

    /** 허밍: 사람마다 가장 가까운 얼굴 쪽에서(사방에서 둘러싼 노래). */
    private void chantAll(float vol) {
        for (Player p : game.players.inPlay()) {
            Location l = p.getLocation();
            Slot best = null;
            double bd = Double.MAX_VALUE;
            for (Slot s : slots) {
                if (s.broken || s.part == null) continue;
                double d = Math.hypot(s.x - l.getX(), s.z - l.getZ());
                if (d < bd) {
                    bd = d;
                    best = s;
                }
            }
            if (best == null) continue;
            Props.soundTo(p, new Location(game.world, best.x, best.y, best.z), "gabe.b3.chant", Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, vol,
                    phase >= 3 ? 1.12f : 1f, 0.6f, -1);
        }
    }

    private void tickChant() {
        if (chantMuted || fightTicks < chantAt) return;
        chantAt = fightTicks + (phase >= 3 ? Tune.CHANT_FAST : Tune.CHANT_EVERY);
        chantAll(1f);
    }

    // ------------------------------------------------------------------ 술래 돌진(dash, rush)

    private void startBack() {
        leadMode = BACK;
        backT = 0;
        backA = angOf(lx, lz);
        backR = distOf(lx, lz);
        leadYaw = Double.NaN;
        slots[0].tilt = 0;
    }

    /** 돌진 뒤 고리를 따라 제자리로 걸어 돌아감(DASH_BACK 틱, 눈 뜬 채: 약점). 그동안 술래 칸은 틈. */
    private void tickBack() {
        if (leadMode != BACK) return;
        backT++;
        double k = Math.min(1, backT / (double) Tune.DASH_BACK);
        double a = backA + delta(backA, ang(0)) * k, r = backR + (R - backR) * k;
        lx = px(a, r);
        lz = pz(a, r);
        if (k >= 1) {
            leadMode = HOME;
            setLeadOpen(false);
            slots[0].dirty = true;
            for (int k2 : new int[] {0, N - 1}) sendLink(k2, 4);
        }
    }

    /** 술래 돌진(pad 면 우주선 노림): 빨간 띠(술래 자리에서 표적을 지나 맞은편 고리까지), 젖힘, 눈 뜸 → 내달림(지나는 사람 45, 옆과 위로) → 돌아감. */
    private final class Dash extends Attack {
        final boolean pad;
        final Vector p0 = new Vector(), p1 = new Vector();
        final Set<UUID> hit = new HashSet<>();
        double ux, uz, len, run;
        boolean rocketDone;
        Tele tl;

        Dash(String name, int from, int to, int cd, int tell, Pick pick, boolean pad) {
            super(name, from, to, cd, tell, pick, false);
            this.pad = pad;
        }

        @Override
        protected boolean usable() {
            return leadMode == HOME && !inTransition() && (!pad || rocketAt() != null);
        }

        @Override
        protected void start() {
            hit.clear();
            rocketDone = false;
            run = 0;
            Slot s = slots[0];
            p0.setX(s.x).setY(FLOOR).setZ(s.z);
            Vector rk = pad ? rocketAt() : null;
            double tx = rk != null ? rk.getX() : aim.getX(), tz = rk != null ? rk.getZ() : aim.getZ();
            double dx = tx - p0.getX(), dz = tz - p0.getZ(), l = Math.hypot(dx, dz);
            if (l < 1) {
                dx = cx - p0.getX();
                dz = cz - p0.getZ();
                l = Math.max(1e-3, Math.hypot(dx, dz));
            }
            ux = dx / l;
            uz = dz / l;
            // 고리 위 술래 자리에서 그 방향으로 나가면 맞은편 고리에 닿는 거리(현 길이)
            double fx = p0.getX() - cx, fz = p0.getZ() - cz;
            len = Math.max(4, -2 * (fx * ux + fz * uz) - 1.5);
            p1.setX(p0.getX() + ux * len).setY(FLOOR).setZ(p0.getZ() + uz * len);
            tl = strip(p0, p1, Tune.DASH_W, tellTicks);
            leadMode = TELL;
            leadYaw = Math.atan2(ux, uz);
            setLeadOpen(true);
            s.tilt = Tune.DASH_TILT;
            s.dirty = true;
            sfxAt(s, "tag", Sound.ENTITY_RAVAGER_ROAR, 1f, 0.8f, 96);
            if (pad && Hud.hasPhrase("notice_b3_rocket")) game.hud.notice("notice_b3_rocket", 50);
        }

        @Override
        protected boolean step() {
            if (!told()) return false;
            if (leadMode == TELL) {
                leadMode = RUN;
                lx = p0.getX();
                lz = p0.getZ();
                slots[0].tilt = 6;
                reveal(new Vector(lx, FLOOR + Tune.LEAD_Y, lz), 40);
                for (int k : new int[] {0, N - 1}) sendLink(k, 2);
            }
            run = Math.min(len, run + Tune.DASH_SPEED);
            lx = p0.getX() + ux * run;
            lz = p0.getZ() + uz * run;
            for (Player p : game.mobs.standing()) {
                if (hit.contains(p.getUniqueId())) continue;
                Location l = p.getLocation();
                double ax = l.getX() - p0.getX(), az = l.getZ() - p0.getZ(), along = ax * ux + az * uz, sd = -ax * uz + az * ux;
                if (along < -1 || along > run + 1.5 || Math.abs(sd) > Tune.DASH_W / 2 + 0.3) continue;
                hit.add(p.getUniqueId());
                hitBoss(p, Tune.DASH_DMG, "cause_b3_tag");
                double sg = sd >= 0 ? 1 : -1;
                knock(p, new Vector(-uz * sg, 0, ux * sg), Tune.DASH_SIDE, Tune.DASH_UP);
                kick(p, 1.0);
            }
            if (!rocketDone) {
                Vector rk = rocketAt();
                if (rk == null) rocketDone = true;
                else {
                    double ax = rk.getX() - p0.getX(), az = rk.getZ() - p0.getZ(), along = ax * ux + az * uz, sd = Math.abs(-ax * uz + az * ux);
                    if (sd > Tune.DASH_W / 2 + Tune.ROCKET_R || along < -1 || along > len + 2) rocketDone = true;
                    else if (run >= along) {
                        rocketDone = true;
                        hurtRocket("cause_b3_tag");
                    }
                }
            }
            if (t % 3 == 0) quake(new Vector(lx, FLOOR, lz), 22, 0.7);
            if (run >= len) {
                startBack();
                return true;
            }
            return false;
        }

        @Override
        protected void end() {
            if (tl != null) tl.remove();
            tl = null;
            if (leadMode == TELL) {
                leadMode = HOME;
                leadYaw = Double.NaN;
                slots[0].tilt = 0;
                slots[0].dirty = true;
                setLeadOpen(false);
            } else if (leadMode == RUN) startBack();
        }
    }

    // ------------------------------------------------------------------ 손뼉 파도 ★

    /** 모든 얼굴이 고리 줄을 치켜듦(예고) → 짝! 흰 파도가 고리에서 가운데로 줄어듦. 지나는 순간 땅에 있으면 20 + 위로. 틈 각은 안전(초록 고리). */
    private final class Clap extends Attack {
        final Set<UUID> hit = new HashSet<>();
        double rw;
        boolean started;

        Clap(int from, int to, int cd) {
            super("clap", from, to, cd, Tune.CLAP_TELL, Pick.NONE, true);
        }

        @Override
        protected void start() {
            hit.clear();
            started = false;
            clapPose = true;
            sendRing(3);
            chantAll(1.2f);
            int n = 0;
            for (int i = 0; i < N && n < 4; i++) {
                if (!gapSlot(i)) continue;
                safe(ringPt(ang(i), Math.max(4, R - 4)), 3.5, tellTicks + (int) (R / Tune.CLAP_V) + 10);
                n++;
            }
        }

        @Override
        protected boolean step() {
            if (!told()) return false;
            if (!started) {
                started = true;
                clapPose = false;
                sendRing(2);
                rw = R - 1;
                sfxAll("gabe.b3.clap", Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 1f, 0.6f, 12);
                quake(new Vector(cx, FLOOR, cz), R + 24, 0.7);
                raiseRims();
                if (wavePart != null) wavePart.model(M_WAVE);
            } else rw -= Tune.CLAP_V;
            if (wavePart != null) wavePart.pose(at(cx, FLOOR + 0.08, cz).scale((float) Math.max(0.3, rw), 1f, (float) Math.max(0.3, rw)), 1);
            for (Player p : game.mobs.standing()) {
                if (hit.contains(p.getUniqueId())) continue;
                Location l = p.getLocation();
                double d = distOf(l.getX(), l.getZ());
                if (d > rw + Tune.CLAP_V + 0.6 || d < rw - 0.6) continue;
                if (!onGround(p) || inGap(angOf(l.getX(), l.getZ()))) continue;
                hit.add(p.getUniqueId());
                hitBoss(p, Tune.CLAP_DMG, "cause_b3_clap");
                knock(p, new Vector(), 0, Tune.CLAP_UP);
            }
            return rw <= 0.6;
        }

        @Override
        protected void end() {
            if (clapPose) {
                clapPose = false;
                sendRing(3);
            }
            if (wavePart != null) wavePart.model(Body.EMPTY);
        }
    }

    // ------------------------------------------------------------------ 노래 멈춤 ★(2단계부터)

    /** 노래가 뚝 멈추고 모든 얼굴이 눈을 뜸 → 50틱 동안 걷거나 뛴 사람(움직임 키, 웅크림 빼고)은 들킴: 20 + 가장 가까운 얼굴 쪽으로 끌림. 쏘기는 됨. */
    private final class Freeze extends Attack {
        final Map<UUID, Integer> moving = new HashMap<>();
        final Set<UUID> caught = new HashSet<>();

        Freeze() {
            super("freeze", 2, 3, Tune.FREEZE_CD, Tune.FREEZE_TELL, Pick.NONE, true);
        }

        @Override
        protected void start() {
            moving.clear();
            caught.clear();
            chantMuted = true;
            sfxAll("gabe.b3.stop", Sound.BLOCK_BELL_USE, 1f, 0.5f, 10);
            hintAll("hint_bs_freeze", 60);
        }

        @Override
        protected boolean step() {
            if (t == Math.max(1, tellTicks - 2)) setFreezeEyes(true);
            if (!told()) return false;
            if (t > tellTicks + Tune.FREEZE_WATCH) return true;
            for (Player p : game.mobs.standing()) {
                UUID id = p.getUniqueId();
                Location l = p.getLocation();
                if (caught.contains(id) || distOf(l.getX(), l.getZ()) > R - 1) continue;
                var in = p.getCurrentInput();
                boolean mv = (in.isForward() || in.isBackward() || in.isLeft() || in.isRight()) && !in.isSneak();
                int c = mv ? moving.merge(id, 1, Integer::sum) : 0;
                if (!mv) moving.put(id, 0);
                if (c > Tune.FREEZE_GRACE) {
                    caught.add(id);
                    seen(p);
                }
            }
            return false;
        }

        @Override
        protected void end() {
            chantMuted = false;
            chantAt = fightTicks + 10;
            setFreezeEyes(false);
        }

        private void seen(Player p) {
            Location l = p.getLocation();
            Slot best = null;
            double bd = Double.MAX_VALUE;
            for (Slot s : slots) {
                if (s.broken || s.part == null) continue;
                double d = Math.hypot(s.x - l.getX(), s.z - l.getZ());
                if (d < bd) {
                    bd = d;
                    best = s;
                }
            }
            seenCount++;
            hitBoss(p, Tune.FREEZE_DMG, "cause_b3_freeze");
            if (best == null) return;
            knock(p, new Vector(best.x - l.getX(), 0, best.z - l.getZ()), Tune.FREEZE_PULL, Tune.FREEZE_UP);
            sfxAt(best, "seen", Sound.ENTITY_WITCH_CELEBRATE, 1f, 0.8f, 64);
        }
    }

    // ------------------------------------------------------------------ 죽음, 전멸

    /** 술래가 눈을 감고 노래가 종소리 하나로 끝남 → 고리 줄이 풀리고 얼굴들이 술래 맞은편부터 차례로(2틱마다) 바깥으로 쓰러짐(남길 것 말고는 줄어 사라짐). */
    @Override
    protected void dyingStep(int t) {
        if (t == 1) {
            chantMuted = true;
            clapPose = false;
            leadMode = HOME;
            leadYaw = Double.NaN;
            setLeadOpen(false);
            setFreezeEyes(false);
            if (wavePart != null) wavePart.model(Body.EMPTY);
            sfxAll("gabe.b3.end", Sound.BLOCK_BELL_RESONATE, 1f, 0.5f, 16);
            layout();
            for (Body.Part p : links) if (p != null) p.pose(at(cx, FLOOR - 4, cz).scale(0.02f), 16);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < N; i++) order.add(i);
            double opp = ang(0) + 180;
            order.sort((a, b) -> Double.compare(Math.abs(delta(opp, ang(a))), Math.abs(delta(opp, ang(b)))));
            fallOrder = order.stream().mapToInt(Integer::intValue).toArray();
        }
        if (fallOrder != null && t >= 2 && (t - 2) % 2 == 0) {
            int k = (t - 2) / 2;
            if (k < fallOrder.length) {
                Slot s = slots[fallOrder[k]];
                if (s.part != null && s.fallT < 0) {
                    s.fallT = 0;
                    if (k % 4 == 0) sfxAt(s, "break", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8f, 0.6f, 64);
                }
            }
        }
        tickFalls();
    }

    /** 사체 직전: 고리 줄, 파도 판, 깨진 얼굴과 남기지 않을 얼굴(자리 12~15)을 지움. 술래와 얼굴 열하나만 사체로. */
    @Override
    protected void beforeCorpse() {
        for (int k = 0; k < N; k++) {
            if (links[k] != null) links[k].remove();
            links[k] = null;
        }
        if (wavePart != null) wavePart.remove();
        wavePart = null;
        for (Slot s : slots) {
            if (s.part != null && (s.broken || !s.keep)) {
                s.part.remove();
                s.part = null;
            }
        }
    }

    /** 전멸: 고리가 끝까지 조여 열여섯 얼굴이 가운데에 맞닿아 안쪽을 본다(모두 눈 뜸). */
    @Override
    public void victory() {
        paused = true;
        cancelAttacks();
        clearTeles();
        if (wavePart != null) wavePart.model(Body.EMPTY);
        leadMode = HOME;
        leadYaw = Double.NaN;
        clapPose = false;
        setFreezeEyes(true);
        R = Tune.VICTORY_R;
        layout();
        for (Slot s : slots) {
            if (s.part == null || s.fallT >= 0) continue;
            s.tilt = 0;
            s.part.state(s.broken ? "crack" : "");
            s.part.pose(slotM(s), 100);
        }
        for (Body.Part p : links) if (p != null) p.pose(at(cx, FLOOR - 4, cz).scale(0.02f), 60);
        sfxAll("gabe.b3.seen", Sound.ENTITY_WITCH_CELEBRATE, 1f, 0.8f, 12);
        chantAll(1.3f);
    }

    // ------------------------------------------------------------------ 시험

    @Override
    public String status() {
        int gaps = 0;
        for (int i = 0; i < N; i++) if (gapSlot(i)) gaps++;
        PadLights pl = null;
        try {
            pl = PadLights.peek(game);
        } catch (RuntimeException | LinkageError e) {
            // 원정 층이 없는 판
        }
        return super.status() + String.format(Locale.ROOT,
                " | R=%.1f spin=%.0f center=%.1f,%.1f pad=%d lead=%s eyes=%s gaps=%d rims=%d cracks=%d lamps=%s smashed=%d rocket=%d bumps=%d seen=%d sq=%d",
                R, spin % 360, cx, cz, onPad ? 1 : 0, new String[] {"home", "tell", "run", "back"}[leadMode], leadOpen ? "L" : freezeOpen ? "all" : "-",
                gaps, rims.size(), cracked.size(), pl == null || !pl.active() ? "-" : String.valueOf(pl.litCount()), lampsSmashed, rocketHits, bumps,
                seenCount, sq);
    }
}
