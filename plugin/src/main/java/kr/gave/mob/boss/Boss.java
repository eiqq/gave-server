package kr.gave.mob.boss;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.game.Hud;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * 보스 바탕(docs/모듈/보스.md 11.3, 구현된 바탕 API 는 11.11). 보스 다섯(kr.gave.mob.sp.B1 .. B5)은 이것을 이어받는다. 생성자는 Species.create 의
 * 리플렉션 꼴 그대로 (Game, Species): {@code public B1(Game g, Species s) { super(g, s, new double[] {0.60, 0.25}); hover = 5.5; }}
 *
 * <p>바탕이 하는 것: 등장(untargetable, 감독 BossDirector 가 땅울림, 뿔나팔, 하늘, 레터박스, 큰 제목, 떼의 함성을 함께 친다) → 싸움(위협 표, 공격 칸,
 * 단계 문턱과 전이 보호, 화남) → 죽음 연출(감독이 무너짐 물결과 보상) → 래그돌 사체({@link #ragHold}). 받는 피해는 부위({@link Region})와 약점으로
 * 바꾸고, 사람에게 주는 피해는 {@link #hitBoss}(웨이브 피해 배율과 떨어진 보스 표 값).
 *
 * <p>종이 채우는 것: {@link #build()}(몸), {@link #refreshSpheres()}(맞는 구, {@link #sphere} 로), {@link #act()}(걷기와 {@link #runAttacks()}),
 * 생성자의 {@link #attacks}. 고를 수 있는 것: {@link #introLength()}, {@link #introStep(int)}, {@link #dyingLength()}, {@link #dyingStep(int)},
 * {@link #victory()}, {@link #onPhase(int)}, {@link #damageMul(Damage, Region)}, {@link #spawnPoint(Vector)}, 이름 키, 하늘 시각, 그림자 반지름.
 *
 * <p>틱: 엔진(Mobs)이 tick() 을 부르면 바탕이 예고({@link Tele})를 돌리고 등장, 죽음, 싸움 차례로 나눠 종 훅을 부른다. tick() 은 final.
 */
public abstract class Boss extends Gabe {

    /** 단계 전이(4.6): 틱, 그동안 받는 피해 배율. */
    public static final int TRANSITION = 50;
    public static final double TRANSITION_MUL = 0.25;
    /** 같은 사람에게 안 먹힘 신호 사이 틱, 화면 흔들림 사이 틱, 흰 번쩍임 사이 틱(디스플레이 다시 싣기 줄이기). */
    public static final int SIGNAL_EVERY = 10, KICK_EVERY = 4, FLASH_EVERY = 6;
    /** 공격 하나를 시작한 뒤 다음 공격을 고르기까지 틱(같은 틱에 칸이 다 차지 않게). */
    public static final int ATTACK_GAP = 12;

    /** 공격마다 표적 고르는 법(6.2절). */
    public enum Pick {
        /** 위협 점수 1위(같은 공격이 바로 앞에 고른 사람은 x0.6). */
        TOP,
        /** 그 사람 6블록 안 사람 수가 가장 많은 사람. */
        CLUSTER,
        /** 가장 가까운 동료까지 거리가 가장 먼 사람. */
        LONELY,
        /** 가장 가까운 사람. */
        NEAR,
        /** 약점에 피해를 가장 많이 준 사람(없으면 TOP). */
        DEALER,
        /** 아무나. */
        RANDOM,
        /** 지킬 것(트럭 같은 {@link Ward}): 가장 가까운 것을 겨눔(Attack.ward, aim). 올라온 것이 없으면 TOP 으로 사람. */
        WARD,
        /** 표적 없음(넓은 공격, 소환). */
        NONE
    }

    public final BossDirector bd;
    /** 회차(10~50: 1, 60~100: 2 ...). 감독이 놓기 직전에 정함. */
    public final int cycle;
    /** 지금 단계(1부터). */
    public int phase = 1;
    /** 단계 문턱(체력 비율, 큰 것부터). 단계 수 = 길이 + 1. */
    protected final double[] cuts;
    /** 이번 틱 맞는 구({@link #sphere} 로 채움, {@link #spheres()} 가 그대로 돌려줌). */
    protected final List<double[]> hitNow = new ArrayList<>();
    /** 부위. 0 번이 몸 전체(main). */
    protected final List<Region> regions = new ArrayList<>();
    protected final Region main;
    public final Threat threat = new Threat();
    /** 공격 목록(생성자에서 add). 공격 칸이 돌며 고른다. */
    protected final List<Attack> attacks = new ArrayList<>();
    /** 지금 도는 공격. */
    protected final List<Attack> running = new ArrayList<>(4);
    /** 지금 그리는 바닥 예고(바탕이 매 틱 돌리고 보스가 사라지면 지움). */
    protected final List<Tele> teles = new ArrayList<>();

    /** 등장 중(맞지 않음), 죽음 연출 중, 화남, 시험 멈춤, 조용히 치워짐. */
    public boolean intro = true, dying, enraged, paused, cleaned;
    /** 싸운 틱(등장 뒤), 등장 틱, 죽음 연출 틱. */
    public int fightTicks, introT, dyingT;
    /** 등장 소리를 바탕이 치지 않게(B5 고요). */
    protected boolean quietEntrance;
    /** 포효를 종이 직접(바탕은 등장 끝 20틱 전에 sfx.boss_roar 를 안 침). */
    protected boolean ownRoar;
    /** 땅이나 지평선에서 오는 보스(등장 때 그쪽 주황 화살촉). 하늘 보스는 false. */
    protected boolean fromGround = true;
    /** 등장 때 큰 그림자가 사람들이 선 중앙 원에 드리움(반지름 14, 등장 길이 동안 커지며 짙어짐). 끄려면 false(스스로 그림자를 그리는 보스). */
    protected boolean sweepShadow = true;
    protected Damage lastHit;
    /** 마지막으로 공격을 시작한 것(같은 것 연달아 두 번 금지). */
    Attack lastStarted;
    int nextAttackAt;
    Attack forced;
    Player forcedTarget;
    long transitionUntil = -1;
    double transitionFloor;

    // 맞는 구 묶음(틱마다 다시 채움, 할당 없이)
    private final List<double[]> pool = new ArrayList<>();
    private Region[] owner = new Region[16];
    private int nSph;
    private long sphereTick = Long.MIN_VALUE;
    private double sx = Double.NaN, sy, sz;
    private float syaw = Float.NaN;
    private long flashAt = Long.MIN_VALUE / 2;
    private final Map<UUID, Long> signalAt = new HashMap<>(), kickAt = new HashMap<>();

    protected Boss(Game game, Species sp, double[] cuts) {
        super(game, sp);
        this.bd = BossDirector.of(game);
        this.cycle = bd == null ? 1 : bd.pendingCycle();
        this.cuts = cuts == null ? new double[0] : cuts.clone();
        noRules = noBite = noMutate = noPush = true;
        noReward = true;
        reward = 0;
        ghost = true;
        big = true;
        ragKick = 0.15;
        ragRods = 0;
        ragHold = 500;
        untargetable = true;
        main = region("body", 1.0);
    }

    // ------------------------------------------------------------------ 종이 채우는 것

    /** 걷기와 공격(보통 {@link #runAttacks()}). 싸움 중 매 틱(등장, 죽음, 멈춤 중엔 안 불림). */
    protected abstract void act();

    /** 맞는 구를 {@link #sphere} 로 채운다(바탕이 미리 비움). pos, yaw 가 바뀌거나 틱이 바뀌면 다시 불림. */
    protected abstract void refreshSpheres();

    /** 등장 길이(틱). 그동안 맞지 않고, 감독이 떼 내보내기를 이만큼 미룬다. */
    public int introLength() {
        return 120;
    }

    /** 등장 한 틱(t = 1..introLength): 몸 움직임. 소리, 하늘, 제목은 감독이 친다(BossDirector.entrance). */
    protected void introStep(int t) {
    }

    /** 죽음 연출 길이(틱). 끝나면 래그돌 사체. */
    public int dyingLength() {
        return 60;
    }

    /** 죽음 연출 한 틱(t = 1..dyingLength). */
    protected void dyingStep(int t) {
    }

    /** 사체가 되기 직전(사체로 안 남길 부품을 Part.remove()). */
    protected void beforeCorpse() {
    }

    /** 전멸 때 보스 승리 연출(3.5절, 판 끝 연출 200틱 안). 기본: 멈춰서 눈을 뜨고 포효. */
    public void victory() {
        paused = true;
        cancelAttacks();
        clearTeles();
        if (body != null) body.look.open = true;
        sfx("sfx.boss_roar", Sound.ENTITY_RAVAGER_ROAR, 1f, 0.45f, 192);
    }

    /** 단계가 바뀐 순간(전이 시작, n = 새 단계). */
    protected void onPhase(int n) {
    }

    /** 부위 배율 위에 곱하는 받는 피해 배율(B4 배움, 어지러움 같은 것). 기본 1. */
    protected double damageMul(Damage d, Region r) {
        return 1;
    }

    /** 놓을 자리(감독이 고른 기본 자리 suggested: 사람들이 많이 보는 쪽 R 60 바닥 + hover). 하늘, 둘레 보스는 바꾼다. */
    public Vector spawnPoint(Vector suggested) {
        return suggested;
    }

    /** HUD 보스 틀 이름(글귀 키). */
    public String nameKey() {
        return "name_" + sp.key();
    }

    /** 등장 큰 제목(2배 글귀, 키는 t_ 로 시작). */
    public String titleKey() {
        return "t_" + sp.key();
    }

    /** 등장 부제. */
    public String subKey() {
        return "bs_sub_" + sp.key();
    }

    /** 등장 때 어두워지는 세계 시각(13000 이 평소 저녁, 18000 이 한밤). */
    public int skyTime() {
        return 17000;
    }

    /** 등장 때 몸 밑을 따라다니는 큰 그림자 반지름(0 이면 없음). */
    public double shadowRadius() {
        return Math.max(2, radius * 1.8);
    }

    /** 무너짐 물결이 퍼지는 가운데(B3, B4 는 술래, 얼굴 자리로 덮어씀). */
    public Vector waveCenter() {
        return pos.clone();
    }

    // ------------------------------------------------------------------ 부위, 구

    /** 부위 하나(몸 전체 main 은 바탕이 만듦). mul = 받는 배율. */
    public final class Region {
        public final String name;
        /** 받는 배율(1 보통, 0.35 두꺼운 살, 3 약점). */
        public double mul;
        /** 약점이면 맞힌 사람에게 '칭' + 금색 적중 표시. */
        public boolean weak;
        /** 부위 체력(보스 최대 체력의 비율, 0 이면 끊김 셈 없음). 부위 피해도 늘 보스 체력에서 깎인다. */
        public double hpFrac;
        double hp = Double.NaN;
        public boolean cut;
        /** 끊겼을 때(한 번). */
        public Consumer<Region> onCut;
        /** 이 부위에 들어간 피해 합(시험, 기록). */
        public double dealt;

        Region(String name, double mul) {
            this.name = name;
            this.mul = mul;
        }

        /** 남은 부위 체력 비율 0..1(셈이 없으면 1). */
        public double left() {
            if (hpFrac <= 0) return 1;
            if (Double.isNaN(hp)) return 1;
            return Math.max(0, hp / Math.max(1e-6, hpFrac * maxHp));
        }

        /** 부위 체력 비율을 바꿈(시험 훅 '부위'). 0 이하면 끊김. */
        public void setLeft(double f) {
            if (hpFrac <= 0) return;
            hp = Math.max(0, f) * hpFrac * maxHp;
            if (hp <= 0 && !cut) cutNow();
        }

        void hurt(double amt) {
            dealt += amt;
            if (hpFrac <= 0 || cut) return;
            if (Double.isNaN(hp)) hp = hpFrac * maxHp;
            hp -= amt;
            if (hp <= 0) cutNow();
        }

        void cutNow() {
            cut = true;
            hp = 0;
            if (onCut != null) onCut.accept(this);
        }
    }

    /** 부위를 더한다. */
    protected final Region region(String name, double mul) {
        Region r = new Region(name, mul);
        regions.add(r);
        return r;
    }

    public Region region(String name) {
        for (Region r : regions) if (r.name.equals(name)) return r;
        return null;
    }

    /** 맞는 구 하나(세계 좌표, 반지름)를 이 부위로. refreshSpheres 안에서 부른다. */
    protected final double[] sphere(Region r, double x, double y, double z, double radius) {
        double[] s;
        if (nSph < pool.size()) s = pool.get(nSph);
        else {
            s = new double[4];
            pool.add(s);
        }
        s[0] = x;
        s[1] = y;
        s[2] = z;
        s[3] = radius;
        if (owner.length <= nSph) owner = Arrays.copyOf(owner, nSph * 2 + 2);
        owner[nSph++] = r == null ? main : r;
        hitNow.add(s);
        return s;
    }

    /** 몸 틀(Body 와 같은 회전: rotateY(-yaw) → rotateX(pitch) → rotateZ(roll)) 기준 자리(블록, +Z 앞, +X 몸 왼쪽, scale 곱함)를 세계 좌표로. */
    public final double[] world(double lx, double ly, double lz, double scale) {
        double x = lx * scale, y = ly * scale, z = lz * scale;
        double roll = body == null ? 0 : Math.toRadians(body.roll);
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double x1 = x * cr - y * sr, y1 = x * sr + y * cr;
        double p = Math.toRadians(pitch), cp = Math.cos(p), spp = Math.sin(p);
        double y2 = y1 * cp - z * spp, z2 = y1 * spp + z * cp;
        double yr = Math.toRadians(-yaw), cy = Math.cos(yr), sy0 = Math.sin(yr);
        double x3 = x1 * cy + z2 * sy0, z3 = -x1 * sy0 + z2 * cy;
        return new double[] {pos.getX() + x3, pos.getY() + y2, pos.getZ() + z3};
    }

    /** 다음 spheres() 에서 다시 채우게(부품 자세를 바꾼 뒤). */
    protected final void dirtySpheres() {
        sphereTick = Long.MIN_VALUE;
    }

    @Override
    public final List<double[]> spheres() {
        long now = game.now();
        if (now != sphereTick || pos.getX() != sx || pos.getY() != sy || pos.getZ() != sz || yaw != syaw) {
            sphereTick = now;
            sx = pos.getX();
            sy = pos.getY();
            sz = pos.getZ();
            syaw = yaw;
            hitNow.clear();
            nSph = 0;
            refreshSpheres();
            if (hitNow.isEmpty()) sphere(main, pos.getX(), pos.getY(), pos.getZ(), radius);
        }
        return hitNow;
    }

    /** at 에 가장 가까운 구 번호(표면 거리), 없으면 -1. */
    private int nearestSphere(List<double[]> ss, Vector at) {
        if (at == null || ss.isEmpty()) return -1;
        int best = -1;
        double bd0 = Double.MAX_VALUE;
        for (int i = 0; i < ss.size(); i++) {
            double[] s = ss.get(i);
            double dx = at.getX() - s[0], dy = at.getY() - s[1], dz = at.getZ() - s[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz) - s[3];
            if (d < bd0) {
                bd0 = d;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 틱

    @Override
    public final void tick() {
        tickTeles();
        if (dying) {
            dyingT++;
            dyingStep(dyingT);
            if (dyingT >= dyingLength()) finishDeath();
            return;
        }
        if (intro) {
            introT++;
            if (bd != null) bd.entrance(this, introT);
            introStep(introT);
            if (introT >= introLength()) endIntro();
            return;
        }
        if (paused) {
            hold();
            return;
        }
        fightTicks++;
        threat.tick();
        if (transitionUntil >= 0 && game.now() >= transitionUntil) transitionUntil = -1;
        if (!enraged && bd != null && fightTicks > bd.enrageTicks(this)) enrage();
        act();
        dirtySpheres();
    }

    private void endIntro() {
        intro = false;
        untargetable = false;
        if (bd != null) bd.onFight(this);
    }

    private void tickTeles() {
        for (Iterator<Tele> it = teles.iterator(); it.hasNext();) {
            Tele t = it.next();
            boolean keep;
            try {
                keep = t.tick();
            } catch (RuntimeException e) {
                t.remove();
                keep = false;
            }
            if (!keep) it.remove();
        }
    }

    // ------------------------------------------------------------------ 받는 피해(11.6)

    /** 받는 피해인가: 사람이 쏜 총, 산탄, 저격, 폭발, 불(다른 종 장치 how 'g' + 숫자는 버림), 시험 훅 피해(how 가 test 로 시작)는 받음. */
    protected boolean accepts(Damage d) {
        String how = d.how();
        if (how != null && how.startsWith("test")) return true;
        if (d.by() == null) return false;
        DamageType t = d.type();
        if (t != DamageType.BULLET && t != DamageType.PELLET && t != DamageType.SNIPER && t != DamageType.EXPLOSION && t != DamageType.FIRE) return false;
        return how == null || how.length() < 2 || how.charAt(0) != 'g' || !Character.isDigit(how.charAt(1));
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || removed || dying || intro || untargetable) return false;
        if (!accepts(d)) return false;
        long now = game.now();
        List<double[]> ss = spheres();
        int si = nearestSphere(ss, d.at());
        Region rg = si >= 0 && si < nSph ? owner[si] : main;
        if (rg == null) rg = main;
        double mul = rg.mul * damageMul(d, rg);
        double amt = Math.max(0, d.amount() * mul);
        boolean trans = transitionUntil >= 0 && now < transitionUntil;
        if (trans) amt *= TRANSITION_MUL;
        double before = hp;
        double floor = trans ? transitionFloor * maxHp : hitFloor();
        hp = Math.max(Math.min(before, floor), before - amt);
        double eff = Math.max(0, before - hp);
        rg.hurt(eff);
        Player by = d.by();
        if (by != null) threat.add(by.getUniqueId(), eff, rg.weak);
        // 맞은 기록, 적중 표시, 소리는 엔진 onHurt. 흰 번쩍임은 FLASH_EVERY 틱에 한 번만(큰 몸의 디스플레이 다시 싣기 줄이기)
        boolean flash = now - flashAt >= FLASH_EVERY;
        long keep = body == null ? 0 : body.look.hurtUntil;
        long[] parts = body == null || flash ? null : new long[body.pieces.size()];
        if (parts != null) for (int i = 0; i < parts.length; i++) parts[i] = body.pieces.get(i).look.hurtUntil;
        game.mobs.onHurt(this, d, eff);
        if (flash) flashAt = now;
        else if (body != null) {
            body.look.hurtUntil = keep;
            for (int i = 0; i < parts.length && i < body.pieces.size(); i++) body.pieces.get(i).look.hurtUntil = parts[i];
        }
        if (by != null) signal(by, rg.weak, mul < 0.5);
        checkPhase();
        if (hp <= 0) die(d);
        return true;
    }

    /** 한 번에 문턱을 둘 넘지 않게: 다음 문턱을 넘는 맞음은 그다음 문턱 - 1% 까지(마지막 단계로 넘어가는 맞음은 그 문턱의 반까지). */
    private double hitFloor() {
        int k = phase - 1;
        if (k >= cuts.length) return 0;
        double f = k + 1 < cuts.length ? cuts[k + 1] - 0.01 : cuts[k] * 0.5;
        return Math.max(0, f) * maxHp;
    }

    /** 약점 '칭' + 금색 적중, 안 먹힘 '퉁' + 회색 적중(같은 사람 SIGNAL_EVERY 틱에 한 번). */
    private void signal(Player p, boolean weak, boolean immune) {
        if (!weak && !immune) return;
        long now = game.now();
        Long at = signalAt.get(p.getUniqueId());
        if (at != null && now - at < (weak ? 3 : SIGNAL_EVERY)) return;
        signalAt.put(p.getUniqueId(), now);
        game.hud.bossHit(p, weak);
        if (weak) Props.sound(p, "boss.weak", Sound.BLOCK_AMETHYST_BLOCK_HIT, 1f, 1f, 1.6f);
        else Props.sound(p, "boss.immune", Sound.ENTITY_SLIME_ATTACK, 0.9f, 1f, 0.5f);
    }

    // ------------------------------------------------------------------ 단계(4.6), 화남(4.7)

    /** 체력 비율이 넘은 문턱만큼 단계를 올림(맞을 때 바탕이 부름, 시험 훅 '체력' 뒤에도). */
    public void checkPhase() {
        while (!dying && phase <= cuts.length && hpFrac() < cuts[phase - 1]) enterPhase(phase + 1);
    }

    /** n 단계로(전이 50틱: 받는 피해 x0.25, 체력은 다음 문턱 - 1% 아래로 안 내려감, 재난문자 꼴 알림, 포효). 시험 훅 '단계' 도 이것. */
    public void enterPhase(int n) {
        if (n <= phase || n > cuts.length + 1) return;
        phase = n;
        transitionUntil = game.now() + TRANSITION;
        transitionFloor = n - 1 < cuts.length ? Math.max(0, cuts[n - 1] - 0.01) : 0.01;
        cancelAttacks();
        String key = "bs_" + sp.key() + "_p" + n;
        if (Hud.hasPhrase(key)) game.hud.alert(key, 4, 60);
        if (bd != null) bd.onPhase(this, n);
        try {
            onPhase(n);
        } catch (RuntimeException e) {
            game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[보스] " + sp.name() + " onPhase 예외", e);
        }
    }

    /** 체력을 그 단계 문턱 바로 아래로 두고 넘어감(시험). */
    public void forcePhase(int n) {
        n = Math.max(1, Math.min(cuts.length + 1, n));
        if (n > phase) {
            hp = Math.min(hp, (cuts[n - 2] - 0.005) * maxHp);
            enterPhase(n);
        }
    }

    /** 마지막 단계인가. */
    public boolean lastPhase() {
        return phase > cuts.length;
    }

    public int phases() {
        return cuts.length + 1;
    }

    public double[] cuts() {
        return cuts.clone();
    }

    /** 전이 중인가. */
    public boolean inTransition() {
        return transitionUntil >= 0 && game.now() < transitionUntil;
    }

    /** 화남(4.7: 다시 쓰기 x0.7, 피해 x1.25, 감독 떼 보충 2배, HUD 이름 빨강 깜빡임). 시험 훅 '화남' 도 이것. */
    public void enrage() {
        if (enraged) return;
        enraged = true;
        if (Hud.hasPhrase("bs_enrage")) game.hud.alert("bs_enrage", 5, 60);
        sfx("sfx.boss_roar", Sound.ENTITY_RAVAGER_ROAR, 1f, 0.5f, 192);
        if (bd != null) bd.onEnrage(this);
    }

    // ------------------------------------------------------------------ 죽음

    @Override
    public void die(Damage d) {
        if (dead || dying) return;
        String how = d == null ? null : d.how();
        // 감독의 웨이브 끝 정리, 막힘 정리(시험 건너뛰기): 연출 없이 조용히
        if ("wave_end".equals(how) || "stall".equals(how) || "boss_clean".equals(how)) {
            cleanRemove();
            return;
        }
        startDying(d);
    }

    /** 죽음 연출 시작(감독이 곡, 알림, 물결, 보상을 챙김). */
    protected void startDying(Damage d) {
        if (dying) return;
        dying = true;
        untargetable = true;
        lastHit = d;
        hp = 0;
        dyingT = 0;
        cancelAttacks();
        clearTeles();
        mark(null);
        if (bd != null) bd.onDown(this, d);
    }

    /** 죽음 연출 끝: 래그돌 사체(ragHold 틱 남음). */
    protected final void finishDeath() {
        if (dead) return;
        clearTeles();
        try {
            beforeCorpse();
        } catch (RuntimeException e) {
            game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[보스] " + sp.name() + " beforeCorpse 예외", e);
        }
        Damage d = lastHit != null ? lastHit : new Damage(1, DamageType.OTHER, null, pos.clone(), null, "boss", 0);
        super.die(d);
        if (bd != null) bd.onCorpse(this);
    }

    /** 연출 없이 치움(시험 건너뛰기, 판 끝 정리). 사체 없음. */
    public void cleanRemove() {
        if (dead) return;
        cleaned = true;
        remove();
    }

    @Override
    public void remove() {
        cancelAttacks();
        clearTeles();
        super.remove();
    }

    /** 끌리지 않음(딸랑이, 거미줄, 판 끝 모임). */
    @Override
    public void lure(Vector at, int ticks) {
    }

    // ------------------------------------------------------------------ 사람에게 주는 피해(5절)

    /**
     * 보스 피해: 웨이브 피해 배율(dmgMul)과 떨어진 보스 표 값(체력 100, 방어 없음 기준). 회차 x(1 + 0.2(c - 1)), 2배까지, 화남 x1.25.
     * Players.hurt 가 dmgMul 과 방어를 곱하므로 dmgMul 로 나눠 넘긴다.
     */
    public void hitBoss(Player p, double dmg, String cause) {
        if (p == null || dmg <= 0) return;
        double k = Math.min(2.0, 1 + 0.2 * (cycle - 1)) * (enraged ? 1.25 : 1.0);
        game.mobs.noteHit(this, p, dmg);
        game.players.hurt(p, k * dmg * game.director.bossDmgMul() / Math.max(1e-6, game.director.dmgMul()), this, cause);
    }

    /** 서 있는 사람이고 붙잡히지 않았나(붙잡기, 넘어짐, 느려짐은 이 사람에게만). */
    protected boolean free(Player p) {
        if (!standing(p)) return false;
        PState s = game.players.get(p);
        return s.grabbedBy == null && s.eater == null;
    }

    // ------------------------------------------------------------------ 공격 칸(6.2)

    /**
     * 공격 하나. 생성자에서 {@code attacks.add(new Attack("slam", 1, 2, 180, 32, Pick.CLUSTER, true) { ... })} 처럼(안쪽 클래스라 보스 도우미를
     * 바로 부름). 칸이 비면 다시 쓰기가 찬 것 가운데 고르고(같은 것 연달아 금지, ★(big)은 한 번에 하나), start() 뒤 step() 이 true 를 돌려줄 때까지
     * 매 틱 돈다. 예고 틱은 {@link #tellTicks}(회차, 화남 반영), 다시 쓰기는 끝난 뒤부터.
     */
    public abstract class Attack {
        public final String name;
        public final int from, to, cooldown, tell;
        public final Pick pick;
        public final boolean big;
        /** 표적 수평 거리 한도(0 = 없음). */
        public double range;
        /** false 면 저절로 고르지 않음(시험 훅 '공격' 으로만). */
        public boolean auto = true;
        long readyAt;
        Player lastTarget;
        /** 지금 도는 것의 표적(없으면 null), 마지막으로 안 자리(사람이 쓰러지거나 나가도 이 자리로 끝까지). */
        public Player target;
        public final Vector aim = new Vector();
        /** 표적이 지킬 것일 때(Pick.WARD) 그것, 아니면 null. */
        public Ward ward;
        /** 시작한 뒤 틱(start 가 0, 첫 step 이 1), 이번 예고 틱. */
        public int t, tellTicks;

        protected Attack(String name, int from, int to, int cooldown, int tell, Pick pick, boolean big) {
            this.name = name;
            this.from = from;
            this.to = to;
            this.cooldown = cooldown;
            this.tell = tell;
            this.pick = pick;
            this.big = big;
        }

        /** 단계, 다시 쓰기 말고 더 따질 조건(무리 셋 이상 같은 것). */
        protected boolean usable() {
            return true;
        }

        /** 예고 시작(바닥 예고, 몸 자세, 소리). */
        protected abstract void start();

        /** 매 틱. 끝났으면 true. */
        protected abstract boolean step();

        /** 끝(정상, 끊김 모두). */
        protected void end() {
        }

        boolean ready() {
            return phase >= from && phase <= to && fightTicks >= readyAt && usable();
        }

        /** 예고가 다 찼나(t >= tellTicks). */
        public boolean told() {
            return t >= tellTicks;
        }
    }

    /** 동시 공격 칸 수 = 1 + floor(판에 든 사람 / 10), 4 까지. */
    public int slots() {
        return Math.min(4, 1 + game.players.inPlay().size() / 10);
    }

    /** 예고 틱(회차 x0.9^(c-1), 0.6배까지, 12틱 아래 금지). */
    public int tellTicks(int base) {
        return Math.max(12, (int) Math.round(base * Math.max(0.6, Math.pow(0.9, cycle - 1))));
    }

    /** 다시 쓰기 틱(회차 x0.85^(c-1), 0.55배까지, 화남 x0.7). */
    public int coolTicks(int base) {
        return Math.max(1, (int) Math.round(base * Math.max(0.55, Math.pow(0.85, cycle - 1)) * (enraged ? 0.7 : 1.0)));
    }

    /** 졸개 수(회차 x(1 + 0.3(c - 1))). */
    public int minions(int base) {
        return Math.max(0, (int) Math.round(base * (1 + 0.3 * (cycle - 1))));
    }

    /** 공격 칸 돌리기: 도는 공격의 step, 빈 칸이면 새 공격(시험 훅이 고른 것 먼저). act() 에서 부른다. */
    protected final void runAttacks() {
        for (Iterator<Attack> it = running.iterator(); it.hasNext();) {
            Attack a = it.next();
            a.t++;
            if (a.target != null && standing(a.target)) a.aim.copy(a.target.getLocation().toVector());
            else if (a.ward != null && a.ward.alive() && a.ward.pos() != null) a.aim.copy(a.ward.pos());
            boolean done;
            try {
                done = a.step();
            } catch (RuntimeException e) {
                game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[보스] " + sp.name() + " 공격 " + a.name + " 예외", e);
                done = true;
            }
            if (done) {
                it.remove();
                finish(a);
            }
        }
        if (dying || paused || fightTicks < nextAttackAt) return;
        if (forced == null && running.size() >= slots()) return;
        Attack a = forced != null ? forced : choose();
        if (a == null) return;
        Player who = forced != null ? forcedTarget : null;
        forced = null;
        forcedTarget = null;
        if (running.contains(a)) return;
        startAttack(a, who);
    }

    private Attack choose() {
        boolean bigOn = false;
        for (Attack r : running) bigOn |= r.big;
        List<Attack> c = new ArrayList<>(attacks.size());
        for (Attack a : attacks) {
            if (!a.auto || running.contains(a) || (a.big && bigOn) || a == lastStarted) continue;
            boolean ok;
            try {
                ok = a.ready();
            } catch (RuntimeException e) {
                ok = false;
            }
            if (ok) c.add(a);
        }
        return c.isEmpty() ? null : c.get(game.random.nextInt(c.size()));
    }

    private void startAttack(Attack a, Player who) {
        Player t = who;
        a.ward = null;
        if (t == null && a.pick == Pick.WARD) {
            a.ward = ward();
            if (a.ward == null) t = pick(Pick.TOP, a, a.range);
        } else if (t == null && a.pick != Pick.NONE) t = pick(a.pick, a, a.range);
        if (a.pick != Pick.NONE && t == null && a.ward == null) {
            nextAttackAt = fightTicks + 10;
            return;
        }
        a.target = t;
        a.lastTarget = t;
        if (t != null) a.aim.copy(t.getLocation().toVector());
        else if (a.ward != null && a.ward.pos() != null) a.aim.copy(a.ward.pos());
        else a.aim.copy(pos);
        a.t = 0;
        a.tellTicks = tellTicks(a.tell);
        if (t != null) aimAt(t, a.tellTicks + 10);
        try {
            a.start();
        } catch (RuntimeException e) {
            game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[보스] " + sp.name() + " 공격 " + a.name + " start 예외", e);
            a.readyAt = fightTicks + coolTicks(a.cooldown);
            return;
        }
        running.add(a);
        lastStarted = a;
        nextAttackAt = fightTicks + ATTACK_GAP;
    }

    private void finish(Attack a) {
        try {
            a.end();
        } catch (RuntimeException e) {
            // 끝 정리 실패는 무시(다음 공격은 돈다)
        }
        a.readyAt = fightTicks + coolTicks(a.cooldown);
        boolean still = false;
        for (Attack r : running) still |= r.target != null && r.target == target;
        if (!still) mark(null);
        a.target = null;
        a.ward = null;
    }

    /** 도는 공격을 모두 끊음(단계 전이, 죽음). */
    protected final void cancelAttacks() {
        List<Attack> was = new ArrayList<>(running);
        running.clear();
        for (Attack a : was) finish(a);
    }

    /** 이름으로 공격 찾기(시험 훅). */
    public Attack attack(String name) {
        for (Attack a : attacks) if (a.name.equals(name)) return a;
        return null;
    }

    /** 다음 공격을 이것으로(시험 훅 '공격', who 가 있으면 그 사람). 다시 쓰기, 단계, 칸 수를 무시. */
    public boolean force(String name, Player who) {
        Attack a = attack(name);
        if (a == null) return false;
        forced = a;
        forcedTarget = who;
        nextAttackAt = 0;
        return true;
    }

    /** 이름이 지금 도는 공격(없으면 "-"). */
    public String acting() {
        if (running.isEmpty()) return "-";
        StringBuilder b = new StringBuilder();
        for (Attack a : running) b.append(b.length() == 0 ? "" : ",").append(a.name);
        return b.toString();
    }

    /** 보스 눈이 그 사람 쪽으로 뜨이고(실눈 문법) 조준점 옆에 보스 아이콘. */
    public void aimAt(Player p, int ticks) {
        mark(p);
        if (p != null) game.hud.mark(p, sp.key(), ticks);
    }

    // ------------------------------------------------------------------ 표적(6.2)

    /** 위협 표: 사람마다 t = t x 0.992 + 이번 틱 보스에 준 실제 피해(초당 x0.85 로 식음), 약점에 준 피해도 따로. */
    public static final class Threat {
        final Map<UUID, double[]> t = new HashMap<>();

        void add(UUID id, double dmg, boolean weak) {
            double[] v = t.computeIfAbsent(id, k -> new double[2]);
            v[0] += dmg;
            if (weak) v[1] += dmg;
        }

        void tick() {
            for (double[] v : t.values()) {
                v[0] *= 0.992;
                v[1] *= 0.996;
            }
        }

        public double of(UUID id) {
            double[] v = t.get(id);
            return v == null ? 0 : v[0];
        }

        public double weakOf(UUID id) {
            double[] v = t.get(id);
            return v == null ? 0 : v[1];
        }

        public double total() {
            double s = 0;
            for (double[] v : t.values()) s += v[0];
            return s;
        }
    }

    /** 고르는 법대로 표적 하나(range 0 이면 거리 한도 없음). 다른 칸이 노리는 사람은 사람이 넉넉하면 뺀다. 없으면 null. */
    public Player pick(Pick how, Attack a, double range) {
        List<Player> all = new ArrayList<>();
        for (Player p : game.mobs.standing()) {
            if (!free(p)) continue;
            if (range > 0 && horiz(p.getLocation().toVector(), pos) > range) continue;
            all.add(p);
        }
        if (all.isEmpty()) return null;
        if (all.size() > running.size()) {
            for (Attack r : running) if (r.target != null) all.remove(r.target);
            if (all.isEmpty()) return null;
        }
        switch (how) {
            case NEAR -> {
                Player best = null;
                double bd0 = Double.MAX_VALUE;
                for (Player p : all) {
                    double d = horiz(p.getLocation().toVector(), pos);
                    if (d < bd0) {
                        bd0 = d;
                        best = p;
                    }
                }
                return best;
            }
            case RANDOM -> {
                return all.get(game.random.nextInt(all.size()));
            }
            case CLUSTER -> {
                Player best = null;
                int bn = -1;
                List<Player> st = game.mobs.standing();
                for (Player p : all) {
                    int n = 0;
                    for (Player o : st) if (o != p && o.getLocation().distanceSquared(p.getLocation()) <= 36) n++;
                    if (n > bn) {
                        bn = n;
                        best = p;
                    }
                }
                return best;
            }
            case LONELY -> {
                Player best = null;
                double bd0 = -1;
                List<Player> st = game.mobs.standing();
                for (Player p : all) {
                    double near = Double.MAX_VALUE;
                    for (Player o : st) if (o != p) near = Math.min(near, o.getLocation().distance(p.getLocation()));
                    if (near > bd0) {
                        bd0 = near;
                        best = p;
                    }
                }
                return best;
            }
            case DEALER -> {
                Player best = null;
                double bw = 0;
                for (Player p : all) {
                    double w = threat.weakOf(p.getUniqueId());
                    if (w > bw) {
                        bw = w;
                        best = p;
                    }
                }
                if (best != null) return best;
                return top(all, a);
            }
            default -> {
                return top(all, a);
            }
        }
    }

    private Player top(List<Player> all, Attack a) {
        double sum = threat.total() + 1;
        List<Player> st = game.mobs.standing();
        Player best = null;
        double bs = -1;
        for (Player p : all) {
            double share = threat.of(p.getUniqueId()) / sum;
            double near = Math.max(0, 1 - horiz(p.getLocation().toVector(), pos) / 40);
            boolean alone = true;
            for (Player o : st) if (o != p && o.getLocation().distanceSquared(p.getLocation()) <= 64) alone = false;
            double s = share + 0.4 * near + 0.25 * (alone ? 1 : 0);
            if (a != null && a.lastTarget == p) s *= 0.6;
            s += game.random.nextDouble() * 0.02;
            if (s > bs) {
                bs = s;
                best = p;
            }
        }
        return best;
    }

    /** 수평 거리. */
    public static double horiz(Vector a, Vector b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 서 있는 사람들의 무게 중심(없으면 중앙 원 가운데). */
    public Vector crowdCenter() {
        double x = 0, z = 0;
        int n = 0;
        for (Player p : game.mobs.standing()) {
            x += p.getLocation().getX();
            z += p.getLocation().getZ();
            n++;
        }
        if (n == 0) return kr.gave.world.Plaza.center(game.world).toVector();
        return new Vector(x / n, kr.gave.world.Plaza.FLOOR_Y, z / n);
    }

    // ------------------------------------------------------------------ 바닥 예고(6.1, Tele)

    /** 빨간 원(가운데에서 차올라 tell 틱에 테두리에 닿음 = 맞는 때). */
    public Tele disc(Vector c, double r, int tell) {
        return keep(Tele.disc(game, c, r, tell));
    }

    /** 빨간 띠(뒤 끝 from 에서 앞 끝 to, 폭 width). */
    public Tele strip(Vector from, Vector to, double width, int tell) {
        return keep(Tele.strip(game, from, to, width, tell));
    }

    /** 검은 그림자 원(위에서 떨어짐, tell 틱 동안 짙어짐). */
    public Tele shadow(Vector c, double r, int tell) {
        return keep(Tele.shadow(game, c, r, tell));
    }

    /** 초록 안전 고리(ticks 동안). */
    public Tele safe(Vector c, double r, int ticks) {
        return keep(Tele.safe(game, c, r, ticks));
    }

    /** 흰 파도 고리(r0 에서 r1 까지 틱당 speed 로 퍼짐). 맞음은 {@link Tele#passing} 과 {@link #onGround(Player)}. */
    public Tele wave(Vector c, double r0, double r1, double speed) {
        return keep(Tele.wave(game, c, r0, r1, speed));
    }

    private Tele keep(Tele t) {
        teles.add(t);
        while (teles.size() > 12) teles.remove(0).remove();
        return t;
    }

    /** 바닥 예고를 모두 지움. */
    protected final void clearTeles() {
        for (Tele t : teles) t.remove();
        teles.clear();
    }

    /** 땅에 있나(파도 고리 판정, 핑 앞뒤 2틱 봐 줌: 2틱 안에 공중이었으면 공중으로). */
    public boolean onGround(Player p) {
        @SuppressWarnings("deprecation")
        boolean g = p.isOnGround();
        if (!g) airAt.put(p.getUniqueId(), game.now());
        Long at = airAt.get(p.getUniqueId());
        return g && (at == null || game.now() - at > 2);
    }

    private final Map<UUID, Long> airAt = new HashMap<>();

    /** 예고 안 서 있는 사람(파도는 passing + 땅). */
    public List<Player> caught(Tele t, double pad) {
        List<Player> out = new ArrayList<>();
        for (Player p : game.mobs.standing()) {
            if (t.kind == Tele.Kind.WAVE) {
                Vector f = p.getLocation().toVector();
                if (t.passing(f, 0.6 + pad) && onGround(p)) out.add(p);
            } else if (t.inside(p.getLocation().toVector(), pad)) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ 맵을 쓰는 연출(Terrain), 지킬 것(Ward)

    /**
     * 맵 연출 도구(땅을 잠깐 바꾸기와 지형 판정, {@link Terrain}). 바꾼 땅은 보스 감독이 쉬는 시간, 새 웨이브, 판 바뀜에 모두 되돌린다(서버가 꺼져도
     * 다음에 켤 때 되돌림). 아래 한 줄 도우미는 모두 묶음 번호를 돌려준다(그 묶음만 되돌리려면 {@link #mend}).
     */
    public Terrain land() {
        return bd == null ? null : bd.terrain;
    }

    /** 구덩이: at 둘레 r 이 ticks 에 걸쳐 depth 블록(1..3) 꺼진다(가운데 깊고 가장자리 얕게). 엎어진 자리, 내려앉은 자리. */
    public int pit(Vector at, double r, int depth, int ticks) {
        Terrain t = land();
        return t == null ? 0 : t.pit(at, r, depth, ticks);
    }

    /** 둔덕: at 둘레 r 이 ticks 에 걸쳐 height 블록 솟는다(그 자리 흙). 사체 더미, 밀려 올라온 땅. 땅 게이브는 돌아가고 사람은 뛰어오른다. */
    public int mound(Vector at, double r, int height, int ticks) {
        Terrain t = land();
        return t == null ? 0 : t.mound(at, r, height, null, ticks);
    }

    /** 솟는 기둥: 반지름 r, 높이 height, 재질 m 이 ticks 에 걸쳐 솟는다. 가리개와 그늘({@link #hidden}), 위에 선 사람은 함께 올라감. */
    public int pillar(Vector at, double r, int height, Material m, int ticks) {
        Terrain t = land();
        return t == null ? 0 : t.pillar(at, r, height, m, ticks);
    }

    /** 벽: from 에서 to 까지 높이 height, 재질 m. */
    public int wall(Vector from, Vector to, int height, Material m, int ticks) {
        Terrain t = land();
        return t == null ? 0 : t.wall(from, to, 1, height, m, ticks);
    }

    /** 갈라진 금(바닥 무늬만, 걷기는 그대로): from 에서 to 까지 폭 width. 도랑으로 파려면 land().crack(.., true). */
    public int crack(Vector from, Vector to, double width) {
        Terrain t = land();
        return t == null ? 0 : t.crack(from, to, width, false);
    }

    /** 그을음(바닥 무늬만): at 둘레 r 이 검게 탄다. */
    public int scorch(Vector at, double r) {
        Terrain t = land();
        return t == null ? 0 : t.scorch(at, r);
    }

    /** 그 묶음(pit, pillar 등이 돌려준 번호)을 ticks 에 걸쳐 원래대로(기둥이 도로 꺼지고 구덩이가 메워짐). 0 이면 바로. */
    public void mend(int group, int ticks) {
        Terrain t = land();
        if (t != null && group > 0) t.mend(group, ticks);
    }

    /** 그 묶음을 after 틱 뒤부터 ticks 에 걸쳐 원래대로(잠깐 있다 사라지는 구덩이, 기둥): {@code mend(pit(at, 4, 1, 6), 30, 200)}. */
    public void mend(int group, int ticks, int after) {
        Terrain t = land();
        if (t != null && group > 0) t.mend(group, ticks, after);
    }

    /** 그 사람이 from(빛, 눈, 폭발점)에서 블록에 가려져 있나: 솟은 기둥이나 둔덕 뒤 = 그늘, 숨을 곳. */
    public boolean hidden(Vector from, Player p) {
        Terrain t = land();
        return t != null && t.hidden(from, p);
    }

    /** 그 사람이 꺼진 땅(구덩이, 도랑) 안에 서 있나. */
    public boolean inPit(Player p) {
        Terrain t = land();
        return t != null && t.inPit(p);
    }

    /** 짧은 암전: 판에 든 사람의 하늘만 add 틱만큼 늦춰 어둡게, ticks 뒤 저절로 돌아옴(game.horizon.dusk). 길게 바꾸려면 {@link #sky}. */
    public void dusk(int add, int ticks) {
        game.horizon.dusk(add, ticks);
    }

    /** 지킬 것(트럭 같은 {@link Ward}) 가운데 살아 있고 가장 가까운 것. 올라온 것이 없으면 null. */
    public Ward ward() {
        if (bd == null) return null;
        Ward best = null;
        double bd0 = Double.MAX_VALUE;
        for (Ward w : bd.wards()) {
            Vector p = w.pos();
            if (p == null) continue;
            double d = horiz(p, pos);
            if (d < bd0) {
                bd0 = d;
                best = w;
            }
        }
        return best;
    }

    /** 지킬 것에 보스 피해(사람과 같은 배율: 회차 x(1 + 0.2(c - 1)), 2배까지, 화남 x1.25). */
    public void hitWard(Ward w, double dmg, String cause) {
        if (w == null || dmg <= 0 || !w.alive()) return;
        double k = Math.min(2.0, 1 + 0.2 * (cycle - 1)) * (enraged ? 1.25 : 1.0);
        try {
            w.hurt(k * dmg, this, cause);
        } catch (RuntimeException e) {
            game.plugin.getLogger().log(java.util.logging.Level.WARNING, "[보스] 지킬 것 " + w.key() + " 피해 예외", e);
        }
    }

    /** 예고 안에 든 지킬 것들(가운데에서 반지름만큼 걸치면 듦). */
    public List<Ward> caughtWards(Tele t, double pad) {
        List<Ward> out = new ArrayList<>();
        if (bd == null) return out;
        for (Ward w : bd.wards()) {
            Vector p = w.pos();
            if (p == null) continue;
            if (t.kind == Tele.Kind.WAVE ? t.passing(p, w.radius() + pad) : t.inside(p, w.radius() + pad)) out.add(w);
        }
        return out;
    }

    // ------------------------------------------------------------------ 압도감 도구(흔들림, 소리, 하늘, 졸개, 약점)

    /**
     * 그 사람 화면 한 번 흔들림(도, 4틱에 한 번까지). 2026-11-12 사용자 "시야 각도를 바꾸는 게이브는 등장하지 않게 해": 보스는 그대로 나오고 이 시점 회전만
     * 뺐다(예전: Guns.kick 으로 꺾고 2틱 뒤 60% 만 되돌려 시선이 조금씩 밀림). 부르는 곳(quake, BossDirector 땅 물결, B2, B3)은 그대로 두고 아무것도 안 한다.
     * 큰 순간의 판 전체 땅울림(game.horizon.shake, 끝에 제자리로 돌아옴)은 공용이라 남긴다.
     */
    public void kick(Player p, double deg) {
        if (p == null || deg <= 0) return;
        long now = game.now();
        Long at = kickAt.get(p.getUniqueId());
        if (at != null && now - at < KICK_EVERY) return;
        kickAt.put(p.getUniqueId(), now);
    }

    /** 땅울림: at 에서 range 안 서 있는 사람을 거리만큼(가까우면 maxDeg, 끝에서 0.2도) 흔듦(지금은 시점 회전 없음: {@link #kick}). 소리는 따로. */
    public void quake(Vector at, double range, double maxDeg) {
        for (Player p : game.mobs.standing()) {
            double d = p.getLocation().toVector().distance(at);
            if (d > range) continue;
            kick(p, Math.max(0.2, maxDeg * (1 - d / range)));
        }
    }

    /** 공용 소리(sfx.* 나 boss.* 사건, 팩에 없으면 바닐라 대체) 를 보스 자리에서 range 안 모두에게. */
    public void sfx(String event, Sound fallback, float volume, float fallbackPitch, double range) {
        Props.soundAround(loc(), event, fallback, volume, 0.95f + game.random.nextFloat() * 0.1f, fallbackPitch, range, -1);
    }

    /** 판에 든 사람 모두에게 그 사람 둘레 dist 블록(보스 쪽)에서 나는 소리(경기장 어디서나 크게). */
    public void sfxAll(String event, Sound fallback, float volume, float fallbackPitch, double dist) {
        for (Player p : game.players.inPlay()) {
            Location l = p.getLocation();
            Vector d = pos.clone().subtract(l.toVector()).setY(0);
            if (d.lengthSquared() < 1e-6) d = new Vector(0, 0, 1);
            d.normalize().multiply(Math.min(dist, Math.max(1, horiz(pos, l.toVector()))));
            Props.soundTo(p, l.clone().add(d.getX(), 2, d.getZ()), event, fallback, volume, 0.95f + game.random.nextFloat() * 0.1f, fallbackPitch, -1);
        }
    }

    /** 하늘을 그 세계 시각으로 ticks 동안 바꿈(감독이 틱마다 나눠 보냄). */
    public void sky(int worldTime, int ticks) {
        if (bd != null) bd.sky(worldTime, ticks);
    }

    /** 안개 단계(0 기본, 1 맑음, 2..5 짙음). */
    public void fog(int level) {
        if (bd != null) bd.fog(level);
    }

    /** 졸개(보상 0, 감독 체력 배율 없음, 몹 예산 안). 예산이 차면 null. */
    public Gabe summon(Species s, Vector at) {
        if (s == null || !s.implemented()) return null;
        return child(s, at);
    }

    /** 약점이 드러남: 모두에게 금빛 화살촉(ticks), 판에서 처음이면 한 줄 도움말 hint_bs_weak. */
    public void reveal(Vector at, int ticks) {
        game.hud.point(null, at, ticks);
        hintAll("hint_bs_weak", 60);
    }

    /** 판에 든 서 있는 사람 모두에게 한 줄 도움말(판에서 그 키 한 번). */
    public void hintAll(String key, int ticks) {
        if (!Hud.hasPhrase(key)) return;
        for (Player p : game.mobs.standing()) game.hud.hint(p, key, ticks);
    }

    /** 몸 크기(보간 ticks). */
    public void scaleBody(float s, int ticks) {
        if (body != null) body.scale(s, ticks);
    }

    /** 몸 부품 하나(Body.attach). */
    protected Body.Part part(String model) {
        return body == null ? null : body.attach(model);
    }

    // ------------------------------------------------------------------ 시험

    /** 시험 훅 '상태' 한 줄. */
    public String status() {
        StringBuilder parts = new StringBuilder();
        for (Region r : regions) {
            if (r.hpFrac <= 0) continue;
            parts.append(parts.length() == 0 ? "" : ",").append(r.name).append(':').append(Math.round(r.left() * 100));
        }
        Player tg = target;
        return String.format(Locale.ROOT, "boss=%s hp=%.0f/%.0f frac=%.2f phase=%d/%d cycle=%d act=%s target=%s t=%ds enraged=%d intro=%d dying=%d paused=%d parts=%s spheres=%d teles=%d",
                sp.name(), hp, maxHp, hpFrac(), phase, phases(), cycle, acting(), tg == null ? "-" : tg.getName(), fightTicks / 20, enraged ? 1 : 0, intro ? 1 : 0,
                dying ? 1 : 0, paused ? 1 : 0, parts.length() == 0 ? "-" : parts, spheres().size(), teles.size());
    }
}
