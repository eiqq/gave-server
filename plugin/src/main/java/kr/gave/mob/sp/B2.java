package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.WeatherType;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.mob.boss.Boss;
import kr.gave.mob.boss.Tele;
import kr.gave.mob.boss.Terrain;
import kr.gave.mob.boss.Ward;
import kr.gave.player.Detachable;
import kr.gave.world.Plaza;

/**
 * B2 하늘 덮는 게이브(20웨이브 보스, 2막 젖은 벌의 끝. docs/모듈/보스.md 13절, 원정.md 10절). 아래를 보고 엎어진 28블록 얼굴이 사람들 머리 위 하늘이 되어
 * 내려다본다. 얼굴을 꿰뚫고 선 곧은 갈고리 기둥 여덟(위로는 왕관, 아래로는 새장 창살)이 내려찍고, 갈고리로 사람을 입까지 끌어 올리고, 끝내 하늘이 내려앉는다.
 *
 * <p>몸: 빈 닻(컬링 상자 64 x 64, 엔티티는 늘 땅 높이 구역) + 세계 축 부품(얼굴 gabe/b2 x16, 기둥 gabe/b2_pole x9.3 여덟, 바닥 그림자 gabe/b2_shade,
 * 얼굴비의 그림자와 떨어지는 얼굴). {@link #pos} 의 높이는 바닥 + 24 로 고정이고 얼굴 높이({@link #alt})는 부품 자세로만 바꾼다(보간). 기둥 자리는 보스
 * 기준(rx, rz)이라 기둥이 땅의 한 점에 묶인 동안(내려찍기, 끌어 올리기, 다리)은 보스가 떠다니지 않는다.
 *
 * <p>맵 활용: 싸움 내내 비(사람마다 날씨)와 어두운 하늘, 얼굴 밑 그림자 판, 번개(바닐라 번개 효과: 온 벌판이 번쩍 보임. 보스가 눈을 뜰 때도 친다).
 * 기둥이 찍은 자리는 그을리고({@code scorch}) 가운데 박힌 구멍에 빗물이 고인다(30초): 싸움 내내 물 칸이 넓어진다. 하늘이 내려앉을 자리의 물이 먼저 끓고,
 * 내려앉은 발자국은 다리 밑(초록 고리)만 남기고 1블록 꺼져 빗물 웅덩이가 된다(다음 내려앉기 때 메우고 새로 판다). 물은 사람을 느리게 하고(바닐라 물),
 * 물에 선 사람을 기둥과 갈고리가 먼저 노리며, 번개가 치면 물에 선 사람이 감전된다.
 *
 * <p>공격(시험 훅 '보스 x 공격 이름'): rain 얼굴비, pole 기둥 내려찍기, poles(2단계부터 둘), lift 끌어 올리기, press 하늘 내려앉기, bolt 번개. 숫자는 맨 위 표.
 */
public final class B2 extends Boss implements Detachable {

    // ------------------------------------------------------------------ 숫자 표(피해는 체력 100, 방어 없음 기준. 난이도는 여기서)

    /** 단계별 얼굴 가운데 높이(바닥 위), 얼굴 배율(반 폭 14, 반 두께 2), 기둥 배율, 떨어지는 얼굴 배율. */
    static final double[] ALT = {24, 20, 16};
    static final float FACE_S = 16f, POLE_S = 9.3f, DROP_S = 3f;
    static final double HALF = 14, ALT0 = 24, INTRO_ALT = 70;
    /** 떠다니는 빠르기(블록/틱, 기둥이 묶이지 않은 동안만), 가운데에서 못 벗어나는 반지름. */
    static final double DRIFT = 0.12, LEASH = 30;
    static final int INTRO = 140, DYING = 60, SKY = 16000, SKY_PRESS = 17500;

    /** 얼굴비: 예고, 길이, 떨어지는 틱, 단계별 다시 쓰기(비가 끝난 뒤부터), 단계별 초당 개수, 피해, 반경, 밀침, G01 로 일어나는 몫, 살아 있는 졸개 한도. */
    static final int RAIN_TELL = 40, RAIN_LEN = 120, RAIN_FALL = 20, RAIN_KIDS = 16, RAIN_MAX = 10;
    static final int[] RAIN_CD = {320, 260, 220};
    static final double[] RAIN_RATE = {3, 4, 5};
    static final double RAIN_DMG = 10, RAIN_R = 1.5, RAIN_KNOCK = 0.4, RAIN_BORN = 0.4, RAIN_SPREAD = 4.5, RAIN_RANGE = 28;
    /** 입: 이만큼(최대 체력 몫) 깎이면 닫힘(비 멈춤, 삼킨 사람 뱉음), 그 뒤 처지는 틱. */
    static final double MOUTH_BREAK = 0.04;
    static final int SAG = 40, FLINCH = 30;

    /** 기둥 내려찍기: 예고, 공격 다시 쓰기, 기둥마다 다시 쓰기, 박혀 있는 틱, 반지름, 피해, 넘어짐, 표적 거리, 부위 체력. */
    static final int POLE_TELL = 28, POLE_GAP = 60, POLE_CD = 140, POLE_STUCK = 40, POLE_ROOT = 20;
    static final double POLE_R = 3, POLE_DMG = 35, POLE_RANGE = 26, POLE_HP = 0.05;

    /** 끌어 올리기: 예고, 다시 쓰기, 탈출 연타, 끌어 올리는 빠르기, 빗나가는 거리, 놓친 뒤 느린 낙하, 걸린 기둥 끝 부위 체력, 못 쓰게 되는 부러진 기둥 수. */
    static final int LIFT_TELL = 40, LIFT_CD = 360, LIFT_CLICKS = 10, LIFT_FALL = 60, LIFT_OFF = 6;
    static final double LIFT_SPEED = 0.1, LIFT_MISS = 6, LIFT_REACH = 1.6, HOOK_HP = 0.03, HOOK_TIP = 3.2;

    /** 하늘 내려앉기: 예고, 다시 쓰기, 넘어짐, 눌러앉은 틱, 다시 오르는 틱, 피해, 발자국 반지름, 다리 밑 안전 반지름, 지킬 것 피해, 웅덩이 반지름과 남는 틱. */
    static final int PRESS_TELL = 80, PRESS_CD = 440, PRESS_ROOT = 30, PRESS_HOLD = 20, PRESS_RISE = 60, POND_HOLD = 600;
    static final double PRESS_DMG = 45, PRESS_R = 14, SAFE_R = 3, PRESS_ALT = 7, PRESS_END = 4.4, POND_R = 12;
    /** 지킬 것(트럭) 피해(사람 피해와 같은 단위, 트럭은 x0.004: 내려앉기 12%, 기둥 4%. 원정.md 10절). */
    static final double PRESS_WARD = 30, POLE_WARD = 10;
    /** 물(원정.md 10절 '싸움 내내 물 칸이 넓어진다'): 기둥이 박혔던 자리는 빗물 웅덩이(반지름, 그을음과 같이 메워지는 틱, 한 번에 남는 수). 물에 선 사람을 기둥과 갈고리가 먼저 노린다(몫). */
    static final double PUDDLE_R = 1.6, WET_PICK = 0.6;
    static final int PUDDLE_HOLD = 600, PUDDLE_MAX = 10;
    /** 내려앉은 웅덩이를 한 틱에 바꾸는 열 수(열마다 블록 셋). */
    static final int SINK_STEP = 60;
    /** 회차 2 덤: 다리가 꽂힐 때 흰 파도 고리. */
    static final double LEG_WAVE = 12, LEG_WAVE_DMG = 15;

    /** 번개(기둥이 BOLT_POLES 개 이하거나 웅덩이가 있을 때): 예고, 다시 쓰기, 반지름, 피해, 물에 선 사람 감전 피해와 느려짐. */
    static final int BOLT_TELL = 24, BOLT_CD = 90, BOLT_POLES = 3, STRIKE_GAP = 50;
    static final double BOLT_R = 2.5, BOLT_DMG = 25, SHOCK_DMG = 15, SHOCK_SLOW = 0.3;

    /** 받는 배율: 두꺼운 살, 입, 눈, 박힌 기둥 끝. */
    static final double MUL_FLESH = 0.35, MUL_MOUTH = 3, MUL_EYE = 2, MUL_STUCK = 2;

    // 모델 자리(블록, 얼굴 가운데 기준): 입, 눈, 기둥 모델의 끝 얼굴에서 모델 원점까지
    static final double MOUTH_X = 1.3, MOUTH_Z = -7.9, EYE_X = 7.0, EYE_Z = 2.6, TIP_OFF = 1.33 * POLE_S;
    static final int FY = Plaza.FLOOR_Y;
    /** 아래를 보는 얼굴 부품의 밝기(몸 기본 10, 10). 아래 면은 방향 빛이 없어 이만큼 올려야 옆 얼굴과 비슷하게 보인다. */
    static final Display.Brightness FACE_LIGHT = new Display.Brightness(13, 10);
    static final int REST = 0, AIM = 1, DOWN = 2, BACK = 3, HOOK = 4, LIFT = 5, LEG = 6, GONE = 7;

    // ------------------------------------------------------------------ 상태

    private final Region mouthR, eyeR, hookR;
    private Body.Part face, shade;
    private final Pole[] poles = new Pole[8];
    private final Matrix4f mx = new Matrix4f();
    /** 얼굴 가운데 높이(바닥 위), 디스플레이 보간과 같은 직선. */
    private final Glide alt = new Glide();
    private float flip, bob;
    private double offX, offZ;
    private long bobAt;
    private String faceState = "";
    private boolean mouthOpen, mouthBreak, hookBreak, pressing;
    private int staggerUntil, gulpUntil, cut, stuckShown;
    // 얼굴비
    private int rainLeft;
    private double rainAcc;
    private final List<Drop> drops = new ArrayList<>();
    private final List<Gabe> kids = new ArrayList<>();
    // 끌어 올리기
    private Pole lifter;
    private Player held;
    private boolean lifting;
    private double lx0, lz0, lt0, lx1, lz1, lt1;
    private long liftAt;
    private int liftN;
    // 내려앉기
    private double flyX, flyZ;
    private int flyUntil;
    private int pond;
    private int pondAt;
    /** 웅덩이로 바꿀 열 {x, z, 마름(다리 밑), 가운데에서 거리 x100}. */
    private final List<int[]> sinkQueue = new ArrayList<>();
    /** 기둥 웅덩이: {묶음, 만든 틱}. */
    private final List<int[]> puddles = new ArrayList<>();
    // 날씨, 번개
    private final Set<UUID> wet = new HashSet<>();
    private long strikeAt = Long.MIN_VALUE / 2;
    private Move lastMove;
    private boolean cleaned2;
    /** 시험 훅 '보스 x 빈보스 on' 이 켜져 있으면 저절로 공격하지 않고 떠다니지도 않는다(공격 훅으로만: 대본에서 하나씩 확인). */
    private boolean quiet;

    public B2(Game g, Species s) {
        super(g, s, new double[] {0.65, 0.30});
        flying = true;
        radius = 0.9;
        hover = ALT0;
        baseSpeed = DRIFT;
        ragKick = 0.05;
        ragHold = 500;
        fromGround = false;
        sweepShadow = false;
        ownRoar = true;
        main.mul = MUL_FLESH;
        mouthR = region("mouth", MUL_MOUTH);
        mouthR.weak = true;
        mouthR.hpFrac = MOUTH_BREAK;
        mouthR.onCut = r -> mouthBreak = true;
        eyeR = region("eye", MUL_EYE);
        eyeR.weak = true;
        hookR = region("hook", 1.0);
        hookR.weak = true;
        hookR.hpFrac = HOOK_HP;
        hookR.onCut = r -> hookBreak = true;
        for (int i = 0; i < poles.length; i++) {
            final int k = i;
            Region r = region("pole" + i, 1.0);
            r.hpFrac = POLE_HP;
            r.onCut = x -> {
                if (poles[k] != null) poles[k].broken = true;
            };
        }
        for (int ph = 1; ph <= 3; ph++) attacks.add(new Rain(ph, RAIN_CD[ph - 1] + RAIN_LEN));
        attacks.add(new Slam("pole", Pick.TOP, false));
        attacks.add(new Slam("poles", Pick.RANDOM, true));
        attacks.add(new Lift());
        attacks.add(new Press());
        attacks.add(new Bolt());
        attacks.add(new Breath());
    }

    // ------------------------------------------------------------------ 바탕 훅

    @Override
    public int introLength() {
        return INTRO;
    }

    @Override
    public int dyingLength() {
        return DYING;
    }

    @Override
    public int skyTime() {
        return SKY;
    }

    /** 그림자는 스스로 그린다(얼굴 밑을 따라다니는 판). */
    @Override
    public double shadowRadius() {
        return 0;
    }

    /** 사람들 무게 중심 바로 위(가운데에서 LEASH 안), 높이는 바닥 + 24 고정. */
    @Override
    public Vector spawnPoint(Vector suggested) {
        Vector c = leash(crowdCenter());
        return new Vector(c.getX(), FY + ALT0, c.getZ());
    }

    @Override
    public Vector waveCenter() {
        return new Vector(pos.getX() + offX, FY, pos.getZ() + offZ);
    }

    @Override
    protected void build() {
        body = Body.of(this, Body.EMPTY, 72f, 64f);
        body.viewRange(4f);
        long now = game.now();
        alt.snap(INTRO_ALT);
        // 사체로 남길 차례: 얼굴, 기둥 여덟. 그 뒤가 그림자와 잠깐 사는 것들
        face = body.attach("gabe/b2");
        face.follow = false;
        facePose(0);
        double[][] home = {{12.5, 12.5}, {0, 13}, {-12.5, 12.5}, {-13, 0}, {-12.5, -12.5}, {0, -13}, {12.5, -12.5}, {13, 0}};
        for (int i = 0; i < poles.length; i++) {
            Body.Part p = body.attach("gabe/b2_pole");
            p.follow = false;
            poles[i] = new Pole(i, p, region("pole" + i), home[i][0], home[i][1]);
            poles[i].snap(home[i][0], home[i][1], INTRO_ALT - 1, now);
        }
        shade = body.attach("gabe/b2_shade");
        shade.follow = false;
        shade.look.freeze = true;
        // 그림자는 경기장 가장자리에서 쓸려 들어온다(등장 2틱에 가운데로 보냄)
        double a = game.random.nextDouble() * Math.PI * 2;
        shadePose(Math.cos(a) * 70, Math.sin(a) * 70, 0);
    }

    // ------------------------------------------------------------------ 작은 도구

    /** 직선 미끄러짐 하나(디스플레이 보간과 같은 값을 서버에서도 알게). */
    private static final class Glide {
        double from, to;
        long t0;
        int n = 1;

        void set(double cur, double target, long now, int ticks) {
            from = cur;
            to = target;
            t0 = now;
            n = Math.max(1, ticks);
        }

        void snap(double v) {
            from = to = v;
        }

        double at(long now) {
            double f = (now - t0) / (double) n;
            return f >= 1 ? to : f <= 0 ? from : from + (to - from) * f;
        }

        boolean done(long now) {
            return now - t0 >= n;
        }
    }

    private Vector leash(Vector c) {
        double dx = c.getX() - Plaza.CX - 0.5, dz = c.getZ() - Plaza.CZ - 0.5, d = Math.hypot(dx, dz);
        if (d > LEASH) c = new Vector(Plaza.CX + 0.5 + dx / d * LEASH, c.getY(), Plaza.CZ + 0.5 + dz / d * LEASH);
        return c;
    }

    private double faceAlt() {
        return alt.at(game.now());
    }

    private void facePose(int ticks) {
        if (face == null) return;
        face.pose(mx.identity().translate((float) offX, (float) (alt.to - ALT0) + bob, (float) offZ).rotateX(flip).scale(FACE_S), ticks);
    }

    /** 얼굴 높이를 ticks 에 걸쳐 a 로. */
    private void setAlt(double a, int ticks) {
        long now = game.now();
        alt.set(alt.at(now), a, now, ticks);
        bob = 0;
        facePose(ticks);
    }

    private void shadePose(double sx, double sz, int ticks) {
        if (shade == null) return;
        shade.pose(mx.identity().translate((float) (offX + sx), (float) (FY + 0.09 - pos.getY()), (float) (offZ + sz)).scale(2 * (float) HALF, 1f, 2 * (float) HALF), ticks);
    }

    /** 쉬는 기둥 끝 높이(얼굴 높이에 맞춤: 1단계 10.5, 2단계 6.5, 3단계는 머리 바로 위 3.6). */
    private double restTip(int i) {
        return Math.max(alt.to - 13.5, 3.6) + (i % 2) * 0.8;
    }

    /** 그 자리 소리(이 보스 소리 사건 gabe.b2.이름). */
    private void sound(String name, Vector at, Sound fallback, float fallbackPitch, double range) {
        Props.soundAround(new Location(game.world, at.getX(), at.getY(), at.getZ()), "gabe.b2." + name, fallback, 1f, 0.94f + game.random.nextFloat() * 0.12f,
                fallbackPitch, range, -1);
    }

    private void all(String name, Sound fallback, float fallbackPitch) {
        sfxAll("gabe.b2." + name, fallback, 1f, fallbackPitch, 24);
    }

    private Vector ground(double x, double z) {
        return new Vector(x, FY, z);
    }

    /** 흙먼지 고리(셰이더 모양, 효과 예산이 없으면 건너뜀). */
    private void dust(Vector at, double r) {
        try {
            ItemDisplay d = game.shotFx.spawn(new Location(game.world, at.getX(), FY + 0.12, at.getZ()), "fx_flat", ShotFx.RING, 0, ShotFx.scale((float) r),
                    Display.Billboard.FIXED, true, 12, ShotFx.KEY);
            if (d == null) return;
            d.setDisplayWidth((float) (r * 4));
            d.setDisplayHeight(4f);
            game.shotFx.grow(d, ShotFx.scale((float) (r * 2.5)), 10);
        } catch (RuntimeException e) {
            // 연출만
        }
    }

    /** 번개: 바닐라 번개 효과(피해, 불 없음. 온 벌판이 번쩍 보임) + 천둥. 너무 잦지 않게 STRIKE_GAP 틱에 한 번(force 면 늘). */
    private void strike(Vector at, boolean force) {
        long now = game.now();
        if (!force && now - strikeAt < STRIKE_GAP) return;
        strikeAt = now;
        Location l = new Location(game.world, at.getX(), FY, at.getZ());
        try {
            game.world.spigot().strikeLightningEffect(l, true);
        } catch (RuntimeException | LinkageError e) {
            // 번개 효과가 없는 서버면 천둥만
        }
        for (Player p : game.players.inPlay()) {
            Location pl = p.getLocation();
            Vector d = l.toVector().subtract(pl.toVector()).setY(0);
            double len = d.length();
            if (len > 20) d.multiply(20 / len);
            Props.soundTo(p, pl.clone().add(d.getX(), 3, d.getZ()), "gabe.b2.thunder", Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 0.95f + game.random.nextFloat() * 0.1f, 0.9f,
                    -1);
        }
    }

    private void weather(boolean rain) {
        if (rain) {
            // 사람마다 한 번만(다시 보내면 빗줄기가 처음부터 다시 짙어짐)
            for (Player p : game.players.inPlay()) if (wet.add(p.getUniqueId())) p.setPlayerWeather(WeatherType.DOWNFALL);
        } else {
            for (Player p : game.world.getPlayers()) if (wet.contains(p.getUniqueId())) p.resetPlayerWeather();
            wet.clear();
        }
    }

    private int alivePoles() {
        return poles.length - cut;
    }

    private Pole freePole(Vector worldAt) {
        Pole best = null;
        double bd0 = Double.MAX_VALUE;
        for (Pole p : poles) {
            if (p == null || p.mode != REST || fightTicks < p.readyAt) continue;
            double d = worldAt == null ? p.i : Math.hypot(pos.getX() + p.hx - worldAt.getX(), pos.getZ() + p.hz - worldAt.getZ());
            if (d < bd0) {
                bd0 = d;
                best = p;
            }
        }
        return best;
    }

    /** 기둥이 땅의 한 점에 묶여 있나(그동안 보스는 떠다니지 않음). */
    private boolean anchored() {
        for (Pole p : poles) if (p != null && (p.mode == AIM || p.mode == DOWN || p.mode == HOOK || p.mode == LIFT || p.mode == LEG)) return true;
        return false;
    }

    /** 물에 선 사람을 기둥과 갈고리가 먼저 노린다(원정.md 10절 '물 안은 느려지며 촉수가 먼저 닿는다'). 없으면 바탕의 고르기. */
    @Override
    public Player pick(Pick how, Attack a, double range) {
        if ((a instanceof Slam || a instanceof Lift) && wetGround() && game.random.nextDouble() < WET_PICK) {
            List<Player> in = new ArrayList<>();
            for (Player q : game.mobs.standing()) {
                if (!free(q) || !inPit(q) || (range > 0 && horiz(q.getLocation().toVector(), pos) > range)) continue;
                boolean taken = false;
                for (Attack r : running) taken |= r.target == q;
                if (!taken) in.add(q);
            }
            if (!in.isEmpty()) return in.get(game.random.nextInt(in.size()));
        }
        return super.pick(how, a, range);
    }

    /** 보스가 눈을 뜰 때 번개: 그 순간 온 벌판이 보인다(원정.md 10절). 표적 둘레가 아닌 먼 곳에 쳐서 번개 공격의 예고와 헷갈리지 않게. */
    @Override
    public void aimAt(Player p, int ticks) {
        super.aimAt(p, ticks);
        if (p == null || intro || dying) return;
        double a = game.random.nextDouble() * Math.PI * 2, r = 22 + game.random.nextDouble() * 14;
        strike(ground(pos.getX() + Math.cos(a) * r, pos.getZ() + Math.sin(a) * r), false);
    }

    // ------------------------------------------------------------------ 기둥

    private final class Pole {
        final int i;
        Body.Part part;
        final Region reg;
        /** 쉬는 자리(얼굴 가운데 기준), 끝 얼굴이 가운데를 보게 하는 회전. */
        final double hx, hz;
        final float yaw;
        int mode = REST;
        final Glide gx = new Glide(), gz = new Glide(), gt = new Glide();
        int until, readyAt, removeAt;
        long bobAt;
        boolean broken;

        Pole(int i, Body.Part part, Region reg, double hx, double hz) {
            this.i = i;
            this.part = part;
            this.reg = reg;
            this.hx = hx;
            this.hz = hz;
            this.yaw = (float) Math.atan2(-hx, -hz);
        }

        double rx() {
            return gx.at(game.now());
        }

        double rz() {
            return gz.at(game.now());
        }

        /** 끝 얼굴 가운데 높이(바닥 위). */
        double tip() {
            return gt.at(game.now());
        }

        void snap(double x, double z, double tip, long now) {
            gx.snap(x);
            gz.snap(z);
            gt.snap(tip);
            send(0);
        }

        /** 보스 기준 (x, z), 끝 높이 tip 으로 ticks 에 걸쳐. */
        void go(double x, double z, double tip, int ticks) {
            long now = game.now();
            gx.set(gx.at(now), x, now, ticks);
            gz.set(gz.at(now), z, now, ticks);
            gt.set(gt.at(now), tip, now, ticks);
            send(ticks);
        }

        private void send(int ticks) {
            if (part == null) return;
            part.pose(mx.identity().translate((float) gx.to, (float) (FY + gt.to + TIP_OFF - pos.getY()), (float) gz.to).rotateY(yaw).scale(POLE_S), ticks);
        }

        void state(String s) {
            if (part != null) part.state(s);
        }

        void flash() {
            if (part != null) part.look.hurtUntil = game.worldTime() + 4;
        }

        Vector tipWorld() {
            return new Vector(pos.getX() + rx(), FY + tip(), pos.getZ() + rz());
        }

        /** 땅에 박힘(ticks 동안 끝 얼굴이 약점). */
        void stick(int ticks) {
            mode = DOWN;
            until = fightTicks + ticks;
            reg.mul = MUL_STUCK;
            reg.weak = true;
            state("slam");
            if (stuckShown++ < 3) reveal(tipWorld(), ticks);
        }

        /** 쉬는 자리로 돌아감. */
        void back() {
            if (mode == GONE) return;
            mode = BACK;
            reg.mul = 1;
            reg.weak = false;
            state("");
            go(hx, hz, restTip(i), 30);
            readyAt = fightTicks + coolTicks(POLE_CD);
        }

        /** 부러짐: 그 자리에서 기울며 떨어지고 잠깐 뒤 사라짐. */
        void snapOff() {
            if (mode == GONE) return;
            if (this == lifter) {
                dropHeld();
                lifter = null;
            }
            mode = GONE;
            cut++;
            removeAt = fightTicks + 40;
            if (part != null) {
                double x = rx(), z = rz();
                part.pose(mx.identity().translate((float) x, (float) (FY + 0.7 - pos.getY()), (float) z).rotateY(yaw).rotateX(1.35f).translate(0, (float) TIP_OFF, 0)
                        .scale(POLE_S), 12);
            }
            Vector at = tipWorld();
            sound("snap", at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.5f, 96);
            quake(ground(at.getX(), at.getZ()), 24, 0.5);
        }

        void tick() {
            if (mode == GONE) {
                if (part != null && fightTicks >= removeAt) {
                    part.remove();
                    part = null;
                }
                return;
            }
            long now = game.now();
            if (mode == DOWN && fightTicks >= until) back();
            else if (mode == BACK && gt.done(now)) mode = REST;
            else if (mode == REST && !pressing && now - bobAt >= 60 + i * 3 && gt.done(now)) {
                // 느리게 오르내릴 뿐(펄럭이지 않음)
                bobAt = now;
                go(hx, hz, restTip(i) + (game.random.nextDouble() - 0.5) * 1.6, 50);
            }
        }
    }

    /** 쉬는 기둥을 지금 얼굴 높이에 맞춤(hide 면 끝이 얼굴 밑에 살짝만 나오게). */
    private void restAll(int ticks, boolean hide) {
        for (Pole p : poles) if (p != null && p.mode == REST) p.go(p.hx, p.hz, hide ? alt.to - 1 : restTip(p.i), ticks);
    }

    // ------------------------------------------------------------------ 맞는 구

    @Override
    protected void refreshSpheres() {
        if (face == null) return;
        double cx = pos.getX() + offX, cz = pos.getZ() + offZ, cy = FY + faceAlt();
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) sphere(main, cx + i * 8, cy, cz + j * 8, i != 0 && j != 0 ? 3.2 : 4.2);
        // 입, 눈은 살 구보다 아래로 나오게(아래에서 쏜 탄이 먼저 닿음)
        if (mouthOpen) sphere(mouthR, cx + MOUTH_X, cy - 3.2, cz + MOUTH_Z, 2.2);
        if (phase >= 3) {
            sphere(eyeR, cx - EYE_X, cy - 3.4, cz + EYE_Z, 1.6);
            sphere(eyeR, cx + EYE_X, cy - 3.4, cz + EYE_Z, 1.6);
        }
        for (Pole p : poles) {
            if (p == null || p.mode == GONE) continue;
            double x = pos.getX() + p.rx(), z = pos.getZ() + p.rz(), y = FY + p.tip();
            sphere(p == lifter && lifting ? hookR : p.reg, x, y, z, 1.2);
            sphere(p.reg, x, y + 3, z, 0.8);
            sphere(p.reg, x, y + 6, z, 0.8);
        }
    }

    // ------------------------------------------------------------------ 등장

    @Override
    protected void introStep(int t) {
        hold();
        if (t == 1) {
            weather(true);
            sound("wind", pos, Sound.ITEM_ELYTRA_FLYING, 0.6f, 128);
        }
        if (t % 20 == 1 && face != null && face.d != null) face.d.setBrightness(FACE_LIGHT);
        // 그림자가 먼저 쓸려 들어온다
        if (t == 2) shadePose(0, 0, 58);
        // 얼굴이 안개 위에서 내려온다(기둥은 얼굴에 꽂힌 채 함께)
        if (t == 20) {
            setAlt(ALT[0], 100);
            for (Pole p : poles) p.go(p.hx, p.hz, ALT[0] - 1, 100);
        }
        // 번개가 칠 때만 온 하늘이 보인다
        if (t == 30 || t == 62 || t == 104) {
            double a = game.random.nextDouble() * Math.PI * 2, r = 16 + game.random.nextDouble() * 12;
            strike(ground(pos.getX() + Math.cos(a) * r, pos.getZ() + Math.sin(a) * r), true);
        }
        if (t == 60) sound("wind", pos, Sound.ITEM_ELYTRA_FLYING, 0.6f, 128);
        // 기둥 여덟이 차례로 곧게 내려온다
        for (Pole p : poles) {
            if (t == 88 + p.i * 5) p.go(p.hx, p.hz, restTip(p.i), 12);
            if (t == 100 + p.i * 5) sound("slam", p.tipWorld(), Sound.ENTITY_RAVAGER_STEP, 0.6f, 96);
        }
        if (t == 118) {
            all("roar", Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f);
            all("gurgle", Sound.ENTITY_RAVAGER_AMBIENT, 0.5f);
            game.horizon.shake(1.0, 30);
        }
    }

    // ------------------------------------------------------------------ 싸움

    @Override
    protected void act() {
        long now = game.now();
        boolean q = bd != null && bd.dummy;
        if (q != quiet) {
            quiet = q;
            for (Attack a : attacks) a.auto = !q;
        }
        if (mouthBreak) {
            mouthBreak = false;
            mouthBroken();
        }
        if (hookBreak) {
            hookBreak = false;
            if (lifting) {
                dropHeld();
                if (lifter != null) lifter.back();
                lifter = null;
                stagger(FLINCH);
            }
        }
        for (Pole p : poles) {
            if (p.broken && p.mode != GONE) {
                p.snapOff();
                stagger(FLINCH);
            }
            p.tick();
        }
        if (fightTicks % 100 == 1) weather(true);
        if (fightTicks % 160 == 40) sound("wind", pos, Sound.ITEM_ELYTRA_FLYING, 0.6f, 128);
        rainTick();
        liftTick();
        faceTick(now);
        // 웅덩이는 POND_HOLD 뒤 천천히 메워짐(다음 내려앉기가 먼저면 그때 새로)
        sinkTick();
        if (pond > 0 && !pressing && sinkQueue.isEmpty() && fightTicks - pondAt > POND_HOLD) {
            mend(pond, 60);
            pond = 0;
        }
        kids.removeIf(g -> g.dead);
        drift();
        if (fightTicks >= staggerUntil) runAttacks();
    }

    /** 사람들 무게 중심 위로 느리게(기둥이 묶였거나 내려앉는 동안은 멈춤, 내려앉기 앞머리는 그 자리로 곧장). */
    private void drift() {
        if (pressing) {
            if (fightTicks < flyUntil) vel.setX(flyX).setY(0).setZ(flyZ);
            else hold();
            return;
        }
        if (quiet || anchored() || fightTicks < staggerUntil) {
            hold();
            return;
        }
        Vector c = leash(crowdCenter());
        double dx = c.getX() - pos.getX(), dz = c.getZ() - pos.getZ(), d = Math.hypot(dx, dz);
        if (d < 1.5) {
            hold();
            return;
        }
        double s = Math.min(DRIFT * (1 + 0.15 * (phase - 1)), d);
        vel.setX(dx / d * s).setY(0).setZ(dz / d * s);
    }

    private void faceTick(long now) {
        if (face == null) return;
        boolean open = rainLeft > 0 || fightTicks < gulpUntil || !game.players.trappedIn(this).isEmpty();
        if (open && !mouthOpen) {
            // 입이 벌어짐: 약점 셈을 새로
            mouthR.cut = false;
            mouthR.setLeft(1.0);
        }
        mouthOpen = open;
        String want = phase >= 3 ? (open ? "tmouth" : "torn") : (open ? "mouth" : "");
        if (!want.equals(faceState)) {
            faceState = want;
            face.state(want);
        }
        // 실눈 문법: 노릴 때만 눈을 뜸(바탕이 body.look.open 을 켬)
        face.look.open = body.look.open;
        // 그림자 판은 맞아도 하얗게 번쩍이지 않게
        if (shade != null) shade.look.hurtUntil = 0;
        // 아래를 보는 얼굴 면은 아이템 셰이더의 방향 빛을 못 받아(바닥 쪽 면은 기본 빛 0.4 뿐) 올려다보면 시커멓다(게임 안 사진).
        // 옆을 보는 보통 게이브 얼굴만큼 보이게 얼굴 부품만 블록 빛을 조금 올린다(빛나게 하는 15 는 아님)
        if (fightTicks % 20 == 1 && face.d != null && !FACE_LIGHT.equals(face.d.getBrightness())) face.d.setBrightness(FACE_LIGHT);
        // 느린 오르내림(높이가 바뀌는 중에는 건너뜀)
        if (!pressing && alt.done(now) && now - bobAt >= 50 && fightTicks >= staggerUntil) {
            bobAt = now;
            bob = (float) ((game.random.nextDouble() - 0.5) * 1.2);
            facePose(50);
        }
    }

    /** 움찔, 처짐: 도는 공격을 끊고 ticks 동안 공격하지 않음. 얼굴이 잠깐 처졌다 돌아옴. */
    private void stagger(int ticks) {
        staggerUntil = Math.max(staggerUntil, fightTicks + ticks);
        cancelAttacks();
        double a = ALT[Math.min(ALT.length, phase) - 1];
        setAlt(a - 3, 8);
        // 돌아오는 것은 처짐이 끝날 때(faceTick 의 오르내림이 다시 맞춤)
        game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
            if (!dead && !dying && !removed && !pressing) setAlt(ALT[Math.min(ALT.length, phase) - 1], 20);
        }, Math.max(10, ticks - 10));
    }

    /** 입이 MOUTH_BREAK 만큼 맞음: 비가 멈추고 삼킨 사람을 뱉고 처짐. */
    private void mouthBroken() {
        rainLeft = 0;
        gulpUntil = 0;
        if (spit()) all("gurgle", Sound.ENTITY_RAVAGER_AMBIENT, 0.5f);
        all("roar", Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f);
        quake(waveCenter(), 40, 0.6);
        stagger(SAG);
    }

    /**
     * 삼킨 사람을 뱉음: 상점 앞에서 손실 없이 일어난다. 보스는 늘 noReward 라 Players.freeFrom 이 '웨이브 끝 정리로 치워짐'으로 보고 원정 판에서
     * 죽음으로 치므로(게임 안 확인: 입을 맞혀 뱉었는데 trappedDeath) 그동안만 끈다. 뱉은 사람이 있었으면 true.
     */
    private boolean spit() {
        if (game.players.trappedIn(this).isEmpty()) return false;
        boolean nr = noReward;
        noReward = false;
        try {
            game.players.freeFrom(this);
        } finally {
            noReward = nr;
        }
        return true;
    }

    @Override
    protected void onPhase(int n) {
        all("roar", Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f);
        quake(waveCenter(), 48, 0.9);
        double a = game.random.nextDouble() * Math.PI * 2;
        strike(ground(pos.getX() + Math.cos(a) * 18, pos.getZ() + Math.sin(a) * 18), true);
        setAlt(ALT[Math.min(ALT.length, n) - 1], TRANSITION);
        restAll(TRANSITION, false);
    }

    // ------------------------------------------------------------------ 공격 바탕

    /** 이 보스의 공격 하나: 다시 쓰기를 스스로도 세어 둔다(빈 숨이 '다른 공격을 쓸 수 있나'를 묻게). 회차 3부터는 모든 단계에서. */
    private abstract class Move extends Attack {
        int freeAt;

        Move(String name, int from, int to, int cooldown, int tell, Pick pick, boolean big) {
            // 얼굴비는 단계마다 다른 다시 쓰기를 가진 셋이라 그대로(셋이 한 단계에 겹치지 않게)
            super(name, cycle >= 3 && !name.equals("rain") ? 1 : from, to, cooldown, tell, pick, big);
        }

        boolean can() {
            return true;
        }

        boolean open() {
            return phase >= from && phase <= to && fightTicks >= freeAt && can();
        }

        @Override
        protected final boolean usable() {
            return can();
        }

        @Override
        protected final void start() {
            lastMove = this;
            begin();
        }

        protected abstract void begin();

        @Override
        protected final void end() {
            freeAt = fightTicks + coolTicks(cooldown);
            finish();
        }

        protected void finish() {
        }
    }

    /**
     * 빈 숨: 바탕의 '같은 공격 연달아 금지' 때문에 쓸 수 있는 공격이 하나뿐일 때(기둥이 다 부러진 뒤의 얼굴비 같은 때) 그 공격이 다시 못 나오는 것을 푼다.
     * 다른 공격을 고를 수 있으면 나오지 않고, 나와도 아무 일 없이 바로 끝난다.
     */
    private final class Breath extends Move {
        Breath() {
            super("breath", 1, 3, 20, 0, Pick.NONE, false);
        }

        @Override
        boolean can() {
            if (lastMove == null || lastMove == this) return false;
            for (Attack a : attacks) if (a instanceof Move m && m != this && m != lastMove && m.open()) return false;
            return true;
        }

        @Override
        protected void begin() {
        }

        @Override
        protected boolean step() {
            return true;
        }
    }

    // ------------------------------------------------------------------ 얼굴비

    private final class Rain extends Move {
        Rain(int ph, int cooldown) {
            super("rain", ph, ph, cooldown, RAIN_TELL, Pick.NONE, true);
        }

        @Override
        boolean can() {
            return rainLeft <= 0 && !pressing;
        }

        @Override
        protected void begin() {
            // 입이 벌어지고 꿀렁 두 번
            gulpUntil = fightTicks + tellTicks + 2;
            all("gurgle", Sound.ENTITY_RAVAGER_AMBIENT, 0.5f);
            reveal(new Vector(pos.getX() + MOUTH_X, FY + alt.to - 3, pos.getZ() + MOUTH_Z), tellTicks + RAIN_LEN);
        }

        @Override
        protected boolean step() {
            if (t == tellTicks / 2) all("gurgle", Sound.ENTITY_RAVAGER_AMBIENT, 0.5f);
            if (!told()) return false;
            rainLeft = RAIN_LEN;
            rainAcc = 0;
            return true;
        }
    }

    /** 떨어지는 얼굴 하나: 그림자(20틱 전부터 짙어짐)와 입에서 그 자리로 떨어지는 작은 얼굴. 자리는 보스 기준. */
    private final class Drop {
        Body.Part shadow, head;
        double rx, rz;
        int age;
    }

    private void rainTick() {
        if (rainLeft > 0) {
            rainLeft--;
            rainAcc += RAIN_RATE[Math.min(3, phase) - 1] * (alivePoles() == 0 ? 1.5 : 1.0) / 20.0;
            while (rainAcc >= 1) {
                rainAcc -= 1;
                if (drops.size() < RAIN_MAX) startDrop();
            }
            if (rainLeft % 40 == 20) all("gurgle", Sound.ENTITY_RAVAGER_AMBIENT, 0.6f);
        }
        if (drops.isEmpty()) return;
        for (Iterator<Drop> it = drops.iterator(); it.hasNext();) {
            Drop d = it.next();
            d.age++;
            if (d.shadow != null) d.shadow.look.hurtUntil = 0;
            if (d.age == 1) {
                // 그림자가 커지고, 얼굴이 입에서 그 자리로 한 번에 떨어짐(생성 틱에 건 보간은 돌지 않아 다음 틱에)
                if (d.shadow != null) d.shadow.pose(spot(d.rx, d.rz, 2.4f), RAIN_FALL - 1);
                if (d.head != null) d.head.pose(mx.identity().translate((float) d.rx, (float) (FY + 0.6 - pos.getY()), (float) d.rz).rotateX(1.5708f).scale(DROP_S),
                        RAIN_FALL - 1);
            }
            if (d.age == RAIN_FALL * 3 / 5 && d.shadow != null) d.shadow.state("spot_dark");
            if (d.age >= RAIN_FALL) {
                it.remove();
                splash(d);
            }
        }
    }

    private Matrix4f spot(double rx, double rz, float size) {
        return mx.identity().translate((float) rx, (float) (FY + 0.1 - pos.getY()), (float) rz).scale(size, 1f, size);
    }

    private void startDrop() {
        if (body == null) return;
        // 얼굴에서 RAIN_RANGE 안 사람 근처 무작위(무리 쪽 가중: 절반은 곁에 사람이 가장 많은 사람). 몸 컬링 상자(72) 안이라 그림자가 늘 보인다
        List<Player> st = new ArrayList<>();
        for (Player q : game.mobs.standing()) if (horiz(q.getLocation().toVector(), pos) <= RAIN_RANGE) st.add(q);
        if (st.isEmpty()) return;
        Player who = st.get(game.random.nextInt(st.size()));
        if (game.random.nextBoolean()) {
            Player c = pick(Pick.CLUSTER, null, RAIN_RANGE);
            if (c != null) who = c;
        }
        double a = game.random.nextDouble() * Math.PI * 2;
        double r = game.random.nextDouble() < 0.3 ? game.random.nextDouble() : Math.sqrt(game.random.nextDouble()) * RAIN_SPREAD;
        Location l = who.getLocation();
        Drop d = new Drop();
        d.rx = l.getX() + Math.cos(a) * r - pos.getX();
        d.rz = l.getZ() + Math.sin(a) * r - pos.getZ();
        d.shadow = body.attach("gabe/b2_shade");
        if (d.shadow != null) {
            d.shadow.follow = false;
            d.shadow.look.freeze = true;
            d.shadow.state("spot");
            d.shadow.pose(spot(d.rx, d.rz, 0.9f), 0);
        }
        d.head = body.attach("gabe/b2_drop");
        if (d.head != null) {
            d.head.follow = false;
            d.head.pose(mx.identity().translate((float) MOUTH_X, (float) (faceAlt() - 2.5 - ALT0), (float) MOUTH_Z).rotateX(1.5708f).scale(DROP_S * 0.6f), 0);
        }
        drops.add(d);
        sound("drop", new Vector(pos.getX() + MOUTH_X, FY + faceAlt() - 3, pos.getZ() + MOUTH_Z), Sound.ENTITY_PHANTOM_FLAP, 1.4f, 32);
    }

    private void splash(Drop d) {
        if (d.shadow != null) d.shadow.remove();
        if (d.head != null) d.head.remove();
        Vector at = new Vector(pos.getX() + d.rx, FY, pos.getZ() + d.rz);
        for (Player p : new ArrayList<>(game.mobs.standing())) {
            Location l = p.getLocation();
            if (l.getY() > FY + 3 || Math.hypot(l.getX() - at.getX(), l.getZ() - at.getZ()) > RAIN_R) continue;
            hitBoss(p, RAIN_DMG, "cause_b2_rain");
            Vector out = l.toVector().subtract(at).setY(0);
            knock(p, out.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : out, RAIN_KNOCK, 0.2);
        }
        Location fx = new Location(game.world, at.getX(), FY + 0.4, at.getZ());
        sound("splat", at, Sound.ENTITY_SLIME_SQUISH, 1.2f, 24);
        // 떨어진 얼굴의 일부는 가오리(G01)로 일어난다(졸개, 보상 없음)
        if (kids.size() < minions(RAIN_KIDS) && game.random.nextDouble() < RAIN_BORN) {
            Gabe g = summon(Species.G01, new Vector(at.getX(), FY + 1.0, at.getZ()));
            if (g != null) {
                kids.add(g);
                return;
            }
        }
        try {
            game.shotFx.bloodHit(fx, new Vector(0, -1, 0), false, ShotFx.BLOOD_PINK);
        } catch (RuntimeException e) {
            // 연출만
        }
    }

    private void clearDrops() {
        for (Drop d : drops) {
            if (d.shadow != null) d.shadow.remove();
            if (d.head != null) d.head.remove();
        }
        drops.clear();
        rainLeft = 0;
    }

    // ------------------------------------------------------------------ 기둥 내려찍기

    /** 내려찍기 하나(기둥, 빨간 원, 자리). */
    private final class Hit {
        Pole p;
        Tele tl;
        Vector at;
        boolean done;
    }

    private Hit hitStart(Vector worldAt, int tell) {
        Pole p = freePole(worldAt);
        if (p == null) return null;
        Hit h = new Hit();
        h.p = p;
        h.at = ground(worldAt.getX(), worldAt.getZ());
        h.tl = disc(h.at, POLE_R, tell);
        p.mode = AIM;
        // 표적 머리 위로 옮겨 가 높이 들림
        p.go(h.at.getX() - pos.getX(), h.at.getZ() - pos.getZ(), Math.min(alt.to - 3, restTip(p.i) + 5), Math.max(6, tell / 2));
        sound("creak", p.tipWorld(), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.5f, 64);
        return h;
    }

    /** 매 틱(t = 공격 틱). 끝났으면 true. */
    private boolean hitStep(Hit h, int t, int tell) {
        if (h == null || h.done) return true;
        Pole p = h.p;
        if (p.mode != AIM) {
            // 예고 도중 기둥이 부러짐
            h.tl.remove();
            h.done = true;
            return true;
        }
        if (t == tell - 4) p.flash();
        if (t == tell) p.go(p.gx.to, p.gz.to, 0.9, 3);
        if (t < tell + 3) return false;
        h.done = true;
        for (Player q : new ArrayList<>(caught(h.tl, 0.3))) {
            hitBoss(q, POLE_DMG, "cause_b2_pole");
            game.players.root(q, POLE_ROOT, "boss");
        }
        for (Ward w : caughtWards(h.tl, 0)) hitWard(w, POLE_WARD, "cause_b2_pole");
        quake(h.at, 24, 0.8);
        sound("slam", h.at, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 96);
        dust(h.at, POLE_R);
        strike(h.at, false);
        // 찍힌 자리가 그을리고 가운데 박힌 구멍에 빗물이 고인다(30초 뒤 함께 메워짐): 싸움 내내 물 칸이 넓어진다
        mend(scorch(h.at, POLE_R), 0, PUDDLE_HOLD);
        puddle(h.at);
        p.stick(POLE_STUCK);
        return true;
    }

    private void hitCancel(Hit h) {
        if (h == null || h.done) return;
        h.done = true;
        if (h.tl != null) h.tl.remove();
        if (h.p.mode == AIM) h.p.back();
    }

    private final class Slam extends Move {
        final boolean twin;
        Hit a, b;

        Slam(String name, Pick pick, boolean twin) {
            super(name, 1, 3, POLE_GAP, POLE_TELL, pick, false);
            this.twin = twin;
            this.range = POLE_RANGE;
        }

        @Override
        boolean can() {
            return !pressing && freePole(null) != null;
        }

        @Override
        protected void begin() {
            a = hitStart(aim, tellTicks);
            b = null;
            // 2단계부터 둘: 다른 사람, 없으면 그 사람이 달려갈 앞쪽
            if (twin && phase >= 2 && a != null) {
                Vector second = null;
                for (Player q : game.mobs.standing()) {
                    if (q == target || horiz(q.getLocation().toVector(), pos) > POLE_RANGE) continue;
                    if (horiz(q.getLocation().toVector(), aim) < POLE_R * 2) continue;
                    second = q.getLocation().toVector();
                    break;
                }
                if (second == null && target != null) {
                    Vector f = target.getLocation().getDirection().setY(0);
                    if (f.lengthSquared() > 1e-6) second = aim.clone().add(f.normalize().multiply(POLE_R * 2 + 0.5));
                }
                if (second != null) b = hitStart(second, tellTicks);
            }
        }

        @Override
        protected boolean step() {
            boolean x = hitStep(a, t, tellTicks), y = hitStep(b, t, tellTicks);
            return x && y;
        }

        @Override
        protected void finish() {
            hitCancel(a);
            hitCancel(b);
        }
    }

    // ------------------------------------------------------------------ 끌어 올리기(갈고리)

    private final class Lift extends Move {
        Pole p;
        final Vector from = new Vector(), lock = new Vector();
        Tele tl;
        boolean snapped, ended;

        Lift() {
            super("lift", 2, 3, LIFT_CD, LIFT_TELL, Pick.LONELY, false);
            this.range = POLE_RANGE;
        }

        @Override
        boolean can() {
            return !pressing && lifter == null && !lifting && cut < LIFT_OFF && freePole(null) != null;
        }

        @Override
        protected void begin() {
            ended = snapped = false;
            p = freePole(aim);
            if (p == null) {
                ended = true;
                return;
            }
            from.copy(aim);
            lock.copy(aim);
            lifter = p;
            p.mode = HOOK;
            p.state("hook");
            // 갈고리 기둥이 그 사람 쪽으로 곧게 내려온다
            p.go(aim.getX() - pos.getX(), aim.getZ() - pos.getZ(), HOOK_TIP, Math.max(8, tellTicks - 8));
            tl = shadow(aim, 1.5, tellTicks);
            sound("creak", p.tipWorld(), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.5f, 64);
        }

        @Override
        protected boolean step() {
            if (ended || p == null || p.mode != HOOK) return true;
            if (t == tellTicks - 8 && target != null && standing(target) && horiz(aim, from) <= LIFT_MISS) {
                // 마지막 조임: 예고 중 LIFT_MISS 넘게 달아나지 않았으면 그 사람 머리 위로
                lock.copy(aim);
                p.go(lock.getX() - pos.getX(), lock.getZ() - pos.getZ(), HOOK_TIP, 8);
                if (tl != null) tl.move(lock);
                snapped = true;
            }
            if (!told()) return false;
            ended = true;
            boolean got = snapped && target != null && free(target) && horiz(target.getLocation().toVector(), lock) <= LIFT_REACH
                    && target.getLocation().getY() < FY + 2.5 && grab(target, LIFT_CLICKS);
            if (got) liftStart(p, target, lock);
            else {
                // 빗나감: 땅에 박힘
                lifter = null;
                p.go(p.gx.to, p.gz.to, 0.9, 3);
                p.stick(POLE_STUCK);
                sound("slam", ground(lock.getX(), lock.getZ()), Sound.ENTITY_RAVAGER_STEP, 0.6f, 96);
            }
            return true;
        }

        @Override
        protected void finish() {
            if (ended || p == null) return;
            ended = true;
            if (tl != null) tl.remove();
            if (lifter == p && !lifting) lifter = null;
            if (p.mode == HOOK) p.back();
        }
    }

    private void liftStart(Pole p, Player who, Vector at) {
        held = who;
        lifting = true;
        p.mode = LIFT;
        lx0 = at.getX() - pos.getX();
        lz0 = at.getZ() - pos.getZ();
        lt0 = HOOK_TIP;
        lx1 = MOUTH_X;
        lz1 = MOUTH_Z;
        lt1 = Math.max(HOOK_TIP + 2, alt.to - 3.4);
        liftN = Math.max(20, (int) Math.ceil((lt1 - lt0) / LIFT_SPEED));
        liftAt = game.now();
        p.go(lx1, lz1, lt1, liftN);
        hookR.cut = false;
        hookR.setLeft(1.0);
        // 동료에게 걸린 기둥 끝을 가리킴
        reveal(p.tipWorld(), Math.min(liftN, 100));
        sound("creak", p.tipWorld(), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.5f, 64);
    }

    private void liftTick() {
        if (!lifting) return;
        Player p = held;
        if (p == null || !p.isOnline() || game.players.grabbedBy(p) != this || lifter == null) {
            dropHeld();
            if (lifter != null) lifter.back();
            lifter = null;
            return;
        }
        double f = Math.min(1, (game.now() - liftAt) / (double) liftN);
        double x = pos.getX() + lx0 + (lx1 - lx0) * f, z = pos.getZ() + lz0 + (lz1 - lz0) * f, tip = lt0 + (lt1 - lt0) * f;
        // 갈고리에 매달려 올라감(사람 머리가 끝 얼굴 바로 밑)
        game.players.carry(p, new Vector(x, FY + tip - 2.6, z));
        if (fightTicks % 4 == 0) game.hud.meter(p, "b2", f, 8);
        if (f >= 1) {
            // 입에 닿음: 삼킴
            held = null;
            lifting = false;
            game.hud.meter(p, "b2", -1, 0);
            game.players.trap(p, this);
            gulpUntil = fightTicks + 30;
            all("gulp", Sound.ENTITY_RAVAGER_ATTACK, 0.5f);
            lifter.back();
            lifter = null;
            reveal(new Vector(pos.getX() + MOUTH_X, FY + alt.to - 3, pos.getZ() + MOUTH_Z), 100);
        }
    }

    /** 걸린 사람을 놓침: 느린 낙하. */
    private void dropHeld() {
        Player p = held;
        held = null;
        lifting = false;
        if (p == null || !p.isOnline()) return;
        game.hud.meter(p, "b2", -1, 0);
        if (game.players.grabbedBy(p) == this) game.players.release(p);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, LIFT_FALL, 0, false, false, false));
    }

    /** 본인 연타나 동료 돕기로 풀림(Players 가 부름). */
    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim == null || victim != held) return;
        held = null;
        lifting = false;
        game.hud.meter(victim, "b2", -1, 0);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, LIFT_FALL, 0, false, false, false));
        if (lifter != null) lifter.back();
        lifter = null;
    }

    // ------------------------------------------------------------------ 하늘 내려앉기

    private final class Press extends Move {
        final List<Pole> legs = new ArrayList<>(4);
        final List<Tele> rings = new ArrayList<>(4), waves = new ArrayList<>(4);
        final List<Vector> spots = new ArrayList<>(4);
        final Set<UUID> waved = new HashSet<>();
        final Vector c = new Vector();
        int fly;
        boolean hit, rose;

        Press() {
            super("press", 3, 3, PRESS_CD, PRESS_TELL, Pick.NONE, true);
        }

        @Override
        boolean can() {
            return rainLeft <= 0 && !lifting && lifter == null && freePole(null) != null;
        }

        @Override
        protected void begin() {
            pressing = true;
            hit = rose = false;
            legs.clear();
            rings.clear();
            waves.clear();
            spots.clear();
            waved.clear();
            // 무리 중심(지킬 것이 가까우면 그쪽으로 조금: 그림자가 트럭 위로 온다)
            Vector cc = leash(crowdCenter());
            Ward w = ward();
            // (조용한 시험 모드는 끌리지 않음: 대본이 다리 자리를 미리 셈)
            if (!quiet && w != null && w.pos() != null && horiz(w.pos(), cc) < 24) cc = cc.clone().multiply(0.6).add(w.pos().clone().multiply(0.4));
            c.copy(cc).setY(FY);
            fly = Math.max(6, Math.min(30, tellTicks / 2));
            flyX = (c.getX() - pos.getX()) / fly;
            flyZ = (c.getZ() - pos.getZ()) / fly;
            flyUntil = fightTicks + fly;
            // 이 틱부터 곧장(이번 틱의 떠다니기 속도를 덮어씀): fly 틱 뒤 정확히 그 자리에 멈춘다
            vel.setX(flyX).setY(0).setZ(flyZ);
            // 남은 기둥 가운데 넷까지가 다리: 무리 둘레 R 7~10 에 90도씩
            double a0 = quiet ? 0 : game.random.nextDouble() * Math.PI * 2;
            for (int i = 0; i < 4; i++) {
                Pole p = freePole(null);
                if (p == null) break;
                double a = a0 + i * Math.PI / 2, r = quiet ? 8.5 : 7 + game.random.nextDouble() * 3;
                Vector s = new Vector(c.getX() + Math.cos(a) * r, FY, c.getZ() + Math.sin(a) * r);
                p.mode = LEG;
                // 보스가 그 자리로 날아가 멈출 때 다리가 제자리에 꽂히게(보스 기준 자리 = 다리 자리 - 내려앉을 가운데)
                p.go(s.getX() - c.getX(), s.getZ() - c.getZ(), 0.9, fly);
                legs.add(p);
                spots.add(s);
                rings.add(safe(s, SAFE_R, tellTicks + 4 + PRESS_HOLD));
            }
            hintAll("hint_bs_safe", 80);
            all("drone", Sound.BLOCK_BEACON_AMBIENT, 0.5f);
            sky(SKY_PRESS, tellTicks);
        }

        @Override
        protected boolean step() {
            // 부러진 다리의 고리는 사라진다(부러뜨릴수록 설 자리가 준다)
            for (int i = 0; i < legs.size(); i++) if (legs.get(i).mode == GONE && !rings.get(i).gone()) rings.get(i).remove();
            // 내려앉을 자리의 물이 먼저 끓는다(발자국 안, 다리 밑 빼고. 다가올수록 짙게)
            if (t < tellTicks && t % 3 == 0) boil(c, spots, 4 + 10 * t / Math.max(1, tellTicks));
            if (t == fly) {
                // 다리가 꽂히고 얼굴이 내려온다
                for (int i = 0; i < legs.size(); i++) {
                    if (legs.get(i).mode == GONE) continue;
                    Vector s = spots.get(i);
                    sound("slam", s, Sound.ENTITY_RAVAGER_STEP, 0.6f, 96);
                    dust(s, 2);
                    if (cycle >= 2) waves.add(wave(s, 1, LEG_WAVE, 0.6));
                }
                quake(c, 40, 0.6);
                setAlt(PRESS_ALT, Math.max(4, tellTicks - fly - 2));
                restAll(Math.max(4, tellTicks - fly - 2), true);
                if (shade != null) shade.state("dark");
            }
            for (Tele wv : waves) {
                if (wv.gone()) continue;
                for (Player p : new ArrayList<>(caught(wv, 0))) {
                    if (!waved.add(p.getUniqueId())) continue;
                    hitBoss(p, LEG_WAVE_DMG, "cause_b2_press");
                    knock(p, new Vector(0, 0, 0), 0, 0.5);
                }
            }
            if (t == tellTicks / 2) all("drone", Sound.BLOCK_BEACON_AMBIENT, 0.5f);
            if (t == tellTicks) {
                setAlt(PRESS_END, 4);
                restAll(4, true);
            }
            if (t == tellTicks + 4 && !hit) {
                hit = true;
                impact();
            }
            if (t == tellTicks + 4 + PRESS_HOLD) {
                // 다시 오르는 동안 눈이 코앞이다
                rose = true;
                setAlt(ALT[Math.min(ALT.length, phase) - 1], PRESS_RISE);
                sky(SKY, PRESS_RISE);
                if (shade != null) shade.state("");
                reveal(new Vector(pos.getX() + EYE_X, FY + PRESS_END - 2, pos.getZ() + EYE_Z), PRESS_RISE);
                all("gurgle", Sound.ENTITY_RAVAGER_AMBIENT, 0.5f);
            }
            return t >= tellTicks + 4 + PRESS_HOLD + PRESS_RISE;
        }

        private void impact() {
            List<Vector> keep = new ArrayList<>(4);
            for (int i = 0; i < legs.size(); i++) if (legs.get(i).mode != GONE) keep.add(spots.get(i));
            for (Player p : new ArrayList<>(game.mobs.standing())) {
                Vector v = p.getLocation().toVector();
                if (horiz(v, c) > PRESS_R || under(v, keep, 0.3)) continue;
                hitBoss(p, PRESS_DMG, "cause_b2_press");
                game.players.root(p, PRESS_ROOT, "boss");
                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
                kick(p, 1.2);
            }
            if (bd != null) {
                for (Ward w : bd.wards()) {
                    Vector wp = w.pos();
                    if (wp == null || !w.alive() || horiz(wp, c) > PRESS_R + w.radius() || under(wp, keep, 0)) continue;
                    hitWard(w, PRESS_WARD, "cause_b2_press");
                }
            }
            quake(c, 56, 1.2);
            sfxAll("sfx.wipe", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 24);
            sound("slam", c, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 96);
            dust(c, PRESS_R * 0.8);
            strike(c, true);
            sink(c, keep);
        }

        @Override
        protected void finish() {
            pressing = false;
            for (Tele r : rings) r.remove();
            for (Tele wv : waves) wv.remove();
            for (Pole p : legs) if (p.mode == LEG) p.back();
            if (dying || dead) return;
            if (!rose) {
                // 끊김(단계 전이, 처짐): 얼굴과 하늘을 되돌림
                setAlt(ALT[Math.min(ALT.length, phase) - 1], 30);
                sky(SKY, 40);
                if (shade != null) shade.state("");
            }
            restAll(30, false);
        }
    }

    /** 끓는 물: 발자국 안 n 자리에 물보라와 거품(바닐라 입자). 다리 밑 안전 고리 안은 조용하다. */
    private void boil(Vector c, List<Vector> keep, int n) {
        for (int i = 0; i < n; i++) {
            double a = game.random.nextDouble() * Math.PI * 2, r = Math.sqrt(game.random.nextDouble()) * PRESS_R;
            double x = c.getX() + Math.cos(a) * r, z = c.getZ() + Math.sin(a) * r;
            Vector v = new Vector(x, FY, z);
            if (under(v, keep, 0.2)) continue;
            Terrain t = land();
            double y = FY + 0.05 - (t != null ? t.depth(x, z) : 0);
            try {
                game.world.spawnParticle(Particle.SPLASH, x, y + 0.1, z, 8, 0.45, 0.05, 0.45, 0.2);
                game.world.spawnParticle(Particle.BUBBLE_POP, x, y + 0.15, z, 4, 0.4, 0.05, 0.4, 0.02);
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    private static boolean under(Vector v, List<Vector> keep, double pad) {
        for (Vector k : keep) if (horiz(v, k) <= SAFE_R + pad) return true;
        return false;
    }

    /**
     * 내려앉은 발자국이 1블록 꺼져 빗물 웅덩이가 된다. 다리 밑은 마른 진흙 둔치로 남아 다음에도 설 자리를 알려 준다(앞 웅덩이 위라도 메움). 웅덩이는
     * 내려앉을 때마다 한 묶음으로 넓어지고(싸움 내내 물 칸이 넓어짐) 마지막 내려앉기 POND_HOLD 뒤에 메워진다. 한 번에 바꾸면 블록 1300개로 한 틱이
     * 30ms 가까이 걸려(게임 안 몹시간) 가운데부터 한 틱에 SINK_STEP 열씩 바꾼다. 블록은 보스 감독의 맵 연출 기록(Terrain)으로 바꿔 쉬는 시간, 판 끝,
     * 서버가 꺼진 뒤에도 되돌려진다.
     */
    private void sink(Vector c, List<Vector> keep) {
        if (land() == null) return;
        sinkQueue.clear();
        int cx = (int) Math.floor(c.getX()), cz = (int) Math.floor(c.getZ()), rr = (int) Math.ceil(POND_R);
        for (int x = cx - rr; x <= cx + rr; x++) {
            for (int z = cz - rr; z <= cz + rr; z++) {
                double d = Math.hypot(x + 0.5 - c.getX(), z + 0.5 - c.getZ());
                if (d > POND_R) continue;
                boolean dry = false;
                for (Vector k : keep) dry |= Math.hypot(x + 0.5 - k.getX(), z + 0.5 - k.getZ()) < SAFE_R + 0.6;
                // 물가는 들쭉날쭉
                if (!dry && d > POND_R - 1.5 && ((x * 31 + z * 17) & 3) == 0) continue;
                sinkQueue.add(new int[] {x, z, dry ? 1 : 0, (int) (d * 100)});
            }
        }
        sinkQueue.sort((a, b) -> Integer.compare(a[3], b[3]));
        pondAt = fightTicks;
    }

    /** 웅덩이 열을 한 틱에 SINK_STEP 개씩. */
    private void sinkTick() {
        if (sinkQueue.isEmpty()) return;
        Terrain t = land();
        if (t == null) {
            sinkQueue.clear();
            return;
        }
        int n = Math.min(SINK_STEP, sinkQueue.size());
        List<int[]> step = sinkQueue.subList(0, n);
        for (int[] q : step) {
            int x = q[0], z = q[1];
            if (q[2] == 1) {
                // 다리 밑: 앞 웅덩이 물이면 진흙 둔치로
                if (game.world.getBlockAt(x, FY - 1, z).getType() == Material.WATER) pond = put(t, x, FY - 1, z, Material.MUD, pond);
                continue;
            }
            pond = put(t, x, FY, z, Material.AIR, pond);
            pond = put(t, x, FY - 1, z, Material.WATER, pond);
            pond = put(t, x, FY - 2, z, Material.MUD, pond);
        }
        step.clear();
    }

    /**
     * 1블록 꺼진 물(바닥 칸이 물, 그 밑은 진흙, 위 장식 칸은 비움). 물이 꺼진 자리 안에만 있어 흐를 곳이 없다(물리 없이 바뀌어도 새지 않음). keep 둘레는 마른 채로,
     * jag 블록 물가는 들쭉날쭉. 묶음 번호(못 바꾸면 0).
     */
    private static int water(Terrain t, Vector c, double r, List<Vector> keep, double jag) {
        int g = 0, cx = (int) Math.floor(c.getX()), cz = (int) Math.floor(c.getZ()), rr = (int) Math.ceil(r);
        for (int x = cx - rr; x <= cx + rr; x++) {
            for (int z = cz - rr; z <= cz + rr; z++) {
                double d = Math.hypot(x + 0.5 - c.getX(), z + 0.5 - c.getZ());
                if (d > r) continue;
                if (jag > 0 && d > r - jag && ((x * 31 + z * 17) & 3) == 0) continue;
                boolean dry = false;
                for (Vector k : keep) {
                    if (Math.hypot(x + 0.5 - k.getX(), z + 0.5 - k.getZ()) < SAFE_R + 0.6) {
                        dry = true;
                        break;
                    }
                }
                if (dry) continue;
                g = put(t, x, FY, z, Material.AIR, g);
                g = put(t, x, FY - 1, z, Material.WATER, g);
                g = put(t, x, FY - 2, z, Material.MUD, g);
            }
        }
        return g;
    }

    /** 기둥이 박혔던 자리의 빗물 웅덩이(그을음과 함께 PUDDLE_HOLD 뒤 메워짐). 한 번에 PUDDLE_MAX 까지(넘으면 오래된 것부터 메움). */
    private void puddle(Vector at) {
        Terrain t = land();
        if (t == null) return;
        puddles.removeIf(p -> fightTicks - p[1] > PUDDLE_HOLD);
        if (puddles.size() >= PUDDLE_MAX) mend(puddles.remove(0)[0], 20);
        int g = water(t, at, PUDDLE_R, List.of(), 0);
        if (g <= 0) return;
        mend(g, 30, PUDDLE_HOLD);
        puddles.add(new int[] {g, fightTicks});
    }

    /** 물이 고여 있나(웅덩이나 내려앉은 자리). */
    private boolean wetGround() {
        puddles.removeIf(p -> fightTicks - p[1] > PUDDLE_HOLD);
        return pond > 0 || !puddles.isEmpty();
    }

    private static int put(Terrain t, int x, int y, int z, Material m, int g) {
        int r = t.set(x, y, z, m, g);
        return g > 0 ? g : r;
    }

    // ------------------------------------------------------------------ 번개

    private final class Bolt extends Move {
        final List<Tele> marks = new ArrayList<>(4);

        Bolt() {
            super("bolt", 1, 3, BOLT_CD, BOLT_TELL, Pick.TOP, false);
            this.range = 40;
        }

        @Override
        boolean can() {
            return !pressing && (alivePoles() <= BOLT_POLES || pond > 0 || (phase >= 2 && wetGround()));
        }

        @Override
        protected void begin() {
            marks.clear();
            marks.add(disc(aim, BOLT_R, tellTicks));
            // 사람이 많으면 더: 여덟 명에 하나씩
            List<Player> st = game.mobs.standing();
            int more = Math.min(3, st.size() / 8 + (alivePoles() == 0 ? 1 : 0));
            for (int i = 0; i < more && !st.isEmpty(); i++) {
                Player q = st.get(game.random.nextInt(st.size()));
                if (q == target) continue;
                marks.add(disc(q.getLocation().toVector(), BOLT_R, tellTicks));
            }
            all("thunder", Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.4f);
        }

        @Override
        protected boolean step() {
            if (!told()) return false;
            Set<UUID> hitSet = new HashSet<>();
            boolean first = true;
            for (Tele m : marks) {
                for (Player p : new ArrayList<>(caught(m, 0.2))) if (hitSet.add(p.getUniqueId())) hitBoss(p, BOLT_DMG, "cause_b2_bolt");
                strike(m.at, first);
                first = false;
                dust(m.at, BOLT_R);
            }
            mend(scorch(marks.get(0).at, BOLT_R), 0, 600);
            // 번개와 물: 웅덩이에 선 사람은 감전된다
            if (wetGround()) {
                for (Player p : new ArrayList<>(game.mobs.standing())) {
                    if (!inPit(p) || !hitSet.add(p.getUniqueId())) continue;
                    hitBoss(p, SHOCK_DMG, "cause_b2_bolt");
                    game.players.slow(p, SHOCK_SLOW, 20, "b2_shock");
                    try {
                        game.shotFx.zap(p.getLocation().add(0, 1, 0));
                    } catch (RuntimeException e) {
                        // 연출만
                    }
                }
            }
            return true;
        }

        @Override
        protected void finish() {
            for (Tele m : marks) m.remove();
        }
    }

    // ------------------------------------------------------------------ 죽음, 전멸, 치움

    @Override
    protected void dyingStep(int t) {
        hold();
        long now = game.now();
        if (t == 1) {
            // 눈을 감고, 비가 그치고, 붙잡은 사람을 놓고, 삼킨 사람을 뱉는다
            pressing = false;
            clearDrops();
            if (lifting) dropHeld();
            lifter = null;
            spit();
            sinkQueue.clear();
            if (face != null) {
                face.look.open = false;
                face.state(phase >= 3 ? "torn" : "");
            }
            if (shade != null) shade.state("dark");
            all("fall", Sound.ENTITY_WITHER_DEATH, 0.5f);
        }
        // 떠 있는 힘을 잃고 뒤집히며 떨어진다(하늘을 본 채 눕게): 세 토막으로 점점 빠르게
        double cur = alt.at(now);
        if (t == 8) flipTo(1.0472f, Math.max(cur - 2, 14.4), 12);
        if (t == 20) flipTo(2.0944f, 14.4, 10);
        if (t == 30) flipTo(3.1416f, 2.3, 8);
        // 기둥도 힘을 잃고 떨어져 바깥으로 눕는다
        for (Pole p : poles) {
            if (p == null || p.part == null || p.mode == GONE) continue;
            if (t == 8) p.go(p.rx(), p.rz(), 1.0, 14);
            if (t == 24) {
                double x = p.rx(), z = p.rz();
                p.part.pose(mx.identity().translate((float) x, (float) (FY + 0.6 - pos.getY()), (float) z).rotateY(p.yaw).rotateX(-1.5f).translate(0, (float) TIP_OFF, 0)
                        .scale(POLE_S), 12);
            }
        }
        if (t == 38) {
            Vector c = waveCenter();
            quake(c, 64, 1.2);
            sfxAll("sfx.wipe", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f, 24);
            sound("slam", c, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 96);
            dust(c, PRESS_R * 0.8);
        }
        if (t == 42) weather(false);
    }

    private void flipTo(float angle, double a, int ticks) {
        long now = game.now();
        flip = angle;
        alt.set(alt.at(now), a, now, ticks);
        bob = 0;
        facePose(ticks);
    }

    @Override
    protected void beforeCorpse() {
        clearDrops();
        weather(false);
        if (shade != null) {
            shade.remove();
            shade = null;
        }
        for (Pole p : poles) {
            if (p != null && p.mode == GONE && p.part != null) {
                p.part.remove();
                p.part = null;
            }
        }
    }

    /** 전멸: 천천히 내려앉아 중앙 원을 덮는다(높이 3). 화면이 그 그림자로 어두워진다. */
    @Override
    public void victory() {
        paused = true;
        cancelAttacks();
        clearTeles();
        clearDrops();
        hold();
        Vector c = Plaza.center(game.world).toVector();
        offX = c.getX() - pos.getX();
        offZ = c.getZ() - pos.getZ();
        setAlt(5, 120);
        shadePose(0, 0, 120);
        if (shade != null) shade.state("dark");
        for (Pole p : poles) if (p != null && p.mode != GONE) p.go(p.hx + offX, p.hz + offZ, 4, 120);
        if (face != null) face.look.open = false;
        for (Player p : game.players.inPlay()) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 200, 0, false, false, false));
        all("drone", Sound.BLOCK_BEACON_AMBIENT, 0.5f);
        all("gurgle", Sound.ENTITY_RAVAGER_AMBIENT, 0.5f);
    }

    /** 사라질 때(쉬는 시간 정리, 판 끝, 시험 건너뛰기): 비를 그치고, 붙잡거나 삼킨 사람을 풀어 준다. */
    @Override
    public void remove() {
        if (!cleaned2) {
            cleaned2 = true;
            try {
                weather(false);
                if (lifting) dropHeld();
                game.players.freeFrom(this);
            } catch (RuntimeException e) {
                game.plugin.getLogger().warning("[보스] B2 정리 예외: " + e);
            }
        }
        super.remove();
    }
}
