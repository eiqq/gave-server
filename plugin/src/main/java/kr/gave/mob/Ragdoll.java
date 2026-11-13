package kr.gave.mob;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import kr.gave.game.Game;
import kr.gave.world.Plaza;

/**
 * 래그돌 사체(docs/모듈/래그돌.md). 죽은 게이브의 몸 디스플레이를 넘겨받아 굴린다. 두 단계가 있다.
 *
 * <p>물리 단계(매 틱): 한 구({@link Doll}) = 강체 조각들({@link RagBody}: 닻 몸 + 부품마다 하나, 떼 마리는 하나) + 흐물 막대기 사슬(베를레
 * 적분 + 거리 제약, 머리 조각의 붙임점에 매달림, 마디마다 공용 마디 모델 {@link #ROD_MODEL} 디스플레이 하나). 서버(20Hz)에서 굴리고
 * 2틱마다 보간 자세로 보낸다. 움직이는 구끼리는 가운데 구로 가볍게 밀어낸다.
 *
 * <p>값싼 단계(키프레임): 물리 단계에 자리가 없는 죽음과, 새 죽음에 자리를 내준 오래된 구. 그 순간 끝까지 굴려 본 길({@link RagPlan})의
 * 키프레임만 보간으로 보낸다(디스플레이 하나, 죽음 하나에 패킷 네댓 번, 사슬 없이 몸의 사체 상태가 누운 막대기를 그림). 그래서 떼를
 * 쓸어 담아도 거의 모든 죽음이 포물선으로 날아가 눕는다. 그것도 넘치면({@link #maxCheap}) 예전처럼 납작해지며 사라진다
 * ({@link #start} 가 false).
 *
 * <p>굳기: 넓은 면으로 눕는다. 얼굴이 바닥에 깔리는 면이면 아래 모서리를 축으로 굴러 넘어가 옆이나 뒤로 눕는다({@link #faceUp}). 굳은
 * 사체는 높이 지도({@link RagPile})에 찍혀 다음 사체가 그 위에 올라앉고(더미), 아래 사체가 꺼지거나 먹히면 위의 것이 내려앉는다. 사람,
 * 게이브, 탄과는 부딪히지 않는다(보이기만).
 *
 * <p>차례: 움직임 → 굳기 → 굳음(막대기 디스플레이를 치우고 몸 디스플레이 하나의 사체 상태가 누운 막대기를 그림) → 꺼짐(땅으로) → 치움.
 * 디스플레이는 죽은 자리의 엔티티 그대로이고 자세는 transformation(옮김 + 왼쪽 회전) 보간으로만 보낸다(텔레포트 없음, 컬링 상자 0).
 *
 * <p>팩 쪽(tools/gabe/kit.py): 모델 상태마다 몸 상자와 얼굴 방향, 펄럭이는 막대기가 있으면 사체 상태 {@code <상태>.c}(막대기 뺀 몸 + 바닥에
 * 닿는 면마다 누운 막대기 자세, {@link LookState#pose} 로 고름)를 굽고 gabe_looks.json 의 rag 에 적는다({@link Look#rag}). 그 정보가 없는
 * 모델(새 키트로 굽지 않은 종)은 얼어붙은 몸이 통째로 구른다.
 *
 * <p>예산(config.yml ragdoll.*): 물리 단계의 구 {@link #maxActive} 와 디스플레이 {@link #maxDisplays}, 값싼 단계의 조각
 * {@link #maxCheap}, 굳은 디스플레이 {@link #maxSettled}. 물리 단계에 자리가 없으면 {@link #minAir} 틱 넘게 난 가장 오래된 구를 값싼 단계로
 * 내리고, 굳은 것이 넘치면 가장 오래된 것부터 꺼뜨린다.
 */
public final class Ragdoll {

    /** 흐물 막대기 마디 하나(한 변 1 정육면체, 길이 +Y). 상태 = 텍스처("" 엔드 막대기, flesh, wetskin, bone). tools/gabe/common.py. */
    public static final String ROD_MODEL = "gabe/common_rod";
    /** 굳을 때 누운 자세로 맞춰 가는 틱, 굴러 넘어갈 때 45도마다 틱. */
    static final int BLEND = 6, ROLL_STEP = 4;
    static final int ACTIVE = 0, SETTLING = 1, SETTLED = 2, SINKING = 3, GONE = 4;
    /** 막대기 마디를 이만큼 길게 그려 이음매가 벌어져 보이지 않게. */
    static final double ROD_OVERLAP = 1.06;
    /** 얼굴 방향이 이만큼 넘게 바닥을 보게 되는 면으로는 굳지 않는다(1 = 똑바로 바닥). */
    static final double FACE_DOWN = 0.6;
    private static final Quaternionf FLIP = new Quaternionf().rotationY((float) Math.PI), NOROT = new Quaternionf();
    private static final float[] FRONT = {0, 0, 1};

    // ------------------------------------------------------------------ 값(config.yml ragdoll.*)

    public boolean enabled = true;
    /** 물리 단계의 동시 수(구, 떼 마리는 빼고), 그 디스플레이 합, 굳은 사체 디스플레이 합. */
    public int maxActive = 10, maxDisplays = 50, maxSettled = 120;
    /** 값싼 단계에서 날아가는 조각 수 상한(넘치는 죽음은 납작 사체). */
    public int maxCheap = 150;
    /** 굳은 뒤 남는 틱(떼 마리는 memberTicks), 땅으로 꺼지는 틱. */
    public int settleTicks = 240, memberTicks = 80, sinkTicks = 20;
    /** 이보다 어린 물리 단계의 구는 값싼 단계로 내리지 않음, 이만큼 지나면 멈추지 않았어도 굳힘. */
    public int minAir = 12, maxAir = 120;
    /** 예산이 넘친 죽음(예전 납작 사체)이 남는 틱. */
    public int flatTicks = 40;
    /** 한 구에서 흐물거리는 막대기 수 상한, 한 구의 조각(몸 + 부품) 상한. */
    public int maxRods = 4, maxPieces = 12;
    /** 중력(블록/틱^2), 튕김(반발 계수), 바닥 마찰, 충격 배율. */
    public double gravity = 0.08, bounce = 0.3, friction = 0.8, kick = 1.0;
    /** 굳은 사체 위에 다음 사체가 쌓이나(끄면 겹침), 얼굴이 깔리면 굴러 넘어가 눕나. */
    public boolean pileOn = true, faceUp = true;

    private final Game game;
    private final Mobs mobs;
    private final Random rnd;
    private final List<Doll> dolls = new ArrayList<>();
    private int ids;
    /**
     * 세기(시험, 기록용): 래그돌이 된 구(물리 + 값싼 단계), 그 가운데 떼 마리, 새 죽음에 밀려 값싼 단계로 내려간 구, 예산이 없어 납작해진
     * 죽음.
     */
    public int started, startedMembers, evicted, refused;
    /** 세기: 처음부터 값싼 단계로 시작한 구, 더미가 꺼져 내려앉은 조각. */
    public int cheapStarted, dropped;
    /** 래그돌 시작(디스플레이 넘겨받기, 마디 만들기, 값싼 길 계산)에 든 시간: 가장 긴 것(ms)과 합, 횟수. */
    public double startMaxMs;
    private double startSumMs;
    private int startN;
    /** 값싼 길 하나를 계산하는 데 든 시간: 가장 긴 것(ms)과 합, 횟수, 키프레임 수 합. */
    public double planMaxMs;
    private double planSumMs;
    private int planN, planKeys;
    /** 보낸 자세 패킷 수(디스플레이마다 한 번씩 셈. 시험에서 틱당 패킷을 잴 때), 미리 데우기에 든 시간(ms). */
    public long sent;
    public double warmMs;

    /** 땅(흐름장의 걷는 바닥). */
    private final RagBody.Ground terrain = new RagBody.Ground() {
        @Override
        public double floor(double x, double z) {
            return mobs.flow.floor(x, z);
        }

        @Override
        public boolean open(double x, double z) {
            return mobs.flow.walkable(x, z);
        }
    };
    /** 굳은 사체 더미의 높이 지도. */
    private final RagPile pile = new RagPile(Plaza.MIN, Plaza.MAX - Plaza.MIN + 1, terrain);
    private boolean pileDirty;
    /** 움직이는 사체의 바닥: 땅 + 굳은 사체 더미. */
    private final RagBody.Ground ground = new RagBody.Ground() {
        @Override
        public double floor(double x, double z) {
            return pileOn ? pile.support(x, z) : mobs.flow.floor(x, z);
        }

        @Override
        public boolean open(double x, double z) {
            return mobs.flow.walkable(x, z);
        }
    };
    private final Vector3d tmp = new Vector3d();
    private double[] planBuf = new double[0];
    /** 다시 쌓기와 밀어내기에 쓰는 임시 배열. */
    private double[] rx = new double[64], rz = new double[64], rr = new double[64], rb = new double[64], rh = new double[64];
    private int[] rorder = new int[64];
    private Piece[] rp = new Piece[64];
    private Doll[] rd = new Doll[64];

    Ragdoll(Game game, Mobs mobs) {
        this.game = game;
        this.mobs = mobs;
        this.rnd = game.random;
        load();
        if (!"0".equals(System.getProperty("gave.ragdoll.warm"))) warm();
    }

    /** config.yml 의 ragdoll.* 을 읽는다(없는 키는 기본값). */
    public void load() {
        FileConfiguration c = game.plugin.getConfig();
        enabled = c.getBoolean("ragdoll.enabled", true);
        maxActive = Math.max(0, c.getInt("ragdoll.active", 10));
        maxDisplays = Math.max(0, c.getInt("ragdoll.activeDisplays", 50));
        maxSettled = Math.max(0, c.getInt("ragdoll.settled", 120));
        maxCheap = Math.max(0, c.getInt("ragdoll.cheap", 150));
        settleTicks = Math.max(1, (int) Math.round(c.getDouble("ragdoll.settleSeconds", 12) * 20));
        memberTicks = Math.max(1, (int) Math.round(c.getDouble("ragdoll.memberSeconds", 4) * 20));
        maxRods = Math.max(0, c.getInt("ragdoll.rods", 4));
        kick = Math.max(0, c.getDouble("ragdoll.kick", 1.0));
        gravity = Math.max(0.01, c.getDouble("ragdoll.gravity", 0.08));
        bounce = Math.max(0, Math.min(0.9, c.getDouble("ragdoll.bounce", 0.3)));
        friction = Math.max(0, c.getDouble("ragdoll.friction", 0.8));
        pileOn = c.getBoolean("ragdoll.pile", true);
        faceUp = c.getBoolean("ragdoll.faceUp", true);
        if (!pileOn) pileDirty = true;
    }

    /** 예산이 넘치거나 래그돌을 끈 죽음의 납작 사체가 남는 틱(끄면 예전 그대로 {@link Mobs#CORPSE_LIFE}). */
    int flatLife(Gabe g) {
        return enabled && g.ragdoll ? flatTicks : Mobs.CORPSE_LIFE;
    }

    // ------------------------------------------------------------------ 구, 조각, 사슬

    /** 사체 한 구. */
    final class Doll {
        final int id = ++ids;
        final Species sp;
        final long born;
        /** 떼 마리 하나(구 수 예산에 안 들고 굳은 뒤 짧게 남음). */
        final boolean member;
        final List<Piece> pieces = new ArrayList<>(2);
        final List<Chain> chains = new ArrayList<>(4);
        /** 사체 상태(.c)를 쓰나, 보이는 흐물 막대기를 모두 그리나(그래야 굳을 때 누운 자세를 켤 수 있음). */
        boolean floppy, full;
        /** 값싼 단계(키프레임만 보냄)인가. */
        boolean cheap;
        Corpse corpse;
        int phase = ACTIVE, blend = BLEND;
        long phaseAt, until;
        /** 조각이 모두 멈춘 틱(아직이면 -1): 막대기가 잦아들기를 조금 기다렸다 굳힌다. */
        long stillAt = -1;
        /** 값싼 단계로 내려온 구의 남은 사슬을 치울 틱(그때까지 누운 자리로 접힘). */
        long foldAt;
        /** 굳을 때 고른 누운 자세 번호(없으면 -1), 그것을 이미 몸에 실었나. */
        int variant = -1;
        boolean applied;
        /** 시험: 죽기 직전 움직임에서 이어받은 빠르기(블록/틱). */
        double inherit;
        /** 굳은 뒤 남는 틱(Gabe.ragHold, -1 이면 settleTicks). 0 보다 크면 굳은 사체 예산에 밀려 꺼지지 않는다(보스 사체, 2026-11-12). */
        int hold = -1;

        /** 굳은 뒤 남는 틱: 떼 마리 memberTicks, 붙든 사체 hold, 그 밖 settleTicks. */
        long life() {
            return member ? memberTicks : hold > 0 ? hold : settleTicks;
        }

        Doll(Species sp, long born, boolean member) {
            this.sp = sp;
            this.born = born;
            this.member = member;
        }

        int segments() {
            int n = 0;
            for (Chain c : chains) n += c.d.length;
            return n;
        }

        /** 디스플레이를 치운다(사슬 마디. 몸과 부품은 {@link Corpse#remove} 가 Body 로 지움). 더미에서도 뺀다. */
        void dispose() {
            phase = GONE;
            for (Chain c : chains) c.remove();
            chains.clear();
            for (Piece p : pieces) {
                if (!p.stamped) continue;
                p.stamped = false;
                pileDirty = true;
            }
        }
    }

    /** 강체 조각 하나와 그 디스플레이. */
    static final class Piece {
        final ItemDisplay d;
        final RagBody b;
        /** 디스플레이 엔티티 자리(죽은 뒤로 안 옮김). */
        final double ex, ey, ez;
        final Vector3f scale;
        final String model;
        String state;
        final LookState look;
        Look.Rag rag;
        /** 래그돌 정보 좌표에 곱할 배율(몸 크기), 움직이는 구끼리 밀어낼 때의 구 반지름. */
        float size = 1f;
        double radius;
        /** 얼굴 방향(몸 틀, 얼굴이 없으면 null): 이 방향이 바닥을 보게 되는 면으로는 굳지 않는다. */
        float[] front;
        /**
         * 키프레임(값싼 길, 굳을 때의 눕기와 굴러 넘어가기): keys[next] 를 앞 키프레임이 닿는 틱(t0 + 앞 것의 at)에 보낸다. b 는 늘
         * 마지막으로 보낸 키프레임의 자세다. restKey 부터는 굳는 자리의 것(받침이 꺼지면 함께 내려감).
         */
        RagPlan.Key[] keys;
        int next, restKey;
        long t0;
        /** 굳는 면(아직이면 -1). */
        int face = -1;
        /** 더미에 찍혔나, 찍힌 자리(가운데, 발자국 반지름), 밑면 높이, 키. */
        boolean stamped;
        double sx, sz, foot, base, height;

        Piece(ItemDisplay d, RagBody b, double ex, double ey, double ez, Vector3f scale, String model, String state, LookState look) {
            this.d = d;
            this.b = b;
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
            this.scale = scale;
            this.model = model;
            this.state = state;
            this.look = look;
        }

        /** 키프레임을 모두 보냈고 마지막 것이 닿았나. */
        boolean arrived(long now) {
            return keys == null || (next >= keys.length && now >= t0 + keys[keys.length - 1].at);
        }
    }

    /** 흐물 막대기 사슬 하나: 계산({@link RagChain}: 이음점 n + 1 개, 0 번은 몸의 붙임점)과 마디 디스플레이 n 개. */
    static final class Chain {
        final Look.RagRod rod;
        final RagChain c;
        final ItemDisplay[] d;
        /** 마디마다 지금 회전과 그 방향(앞 방향에서 가장 짧게 이어 돌려 비틀림이 튀지 않게). */
        final Quaternionf[] rot;
        final double[] dir;

        Chain(Look.RagRod rod, float size) {
            this.rod = rod;
            int n = Math.max(1, rod.segs());
            this.c = new RagChain(n, rod.len() * size / n, rod.thick() * size, rod.root()[0] * size, rod.root()[1] * size, rod.root()[2] * size);
            this.d = new ItemDisplay[n];
            this.rot = new Quaternionf[n];
            this.dir = new double[n * 3];
        }

        void remove() {
            for (ItemDisplay x : d) if (x != null && x.isValid()) x.remove();
        }
    }

    // ------------------------------------------------------------------ 세기, 예산

    private int activeBodies() {
        int n = 0;
        for (Doll d : dolls) if (d.phase == ACTIVE && !d.cheap && !d.member) n++;
        return n;
    }

    /** 물리 단계의 디스플레이: 매 틱 굴리는 구의 조각 + 마디, 굳는 중인 구의 남은 마디. */
    public int activeDisplays() {
        int n = 0;
        for (Doll d : dolls) {
            if (d.phase == ACTIVE && !d.cheap) n += d.pieces.size() + d.segments();
            else if (d.phase == SETTLING) n += d.segments();
        }
        return n;
    }

    /** 값싼 단계에서 날아가는 조각 수. */
    public int cheapMoving() {
        int n = 0;
        for (Doll d : dolls) if (d.phase == ACTIVE && d.cheap) n += d.pieces.size();
        return n;
    }

    /** 값싼 단계로 내려온 구의 아직 접히는 중인 마디. */
    private int foldingDisplays() {
        int n = 0;
        for (Doll d : dolls) if (d.phase == ACTIVE && d.cheap) n += d.segments();
        return n;
    }

    /** 굳은 디스플레이(굳는 중 포함). */
    public int settledDisplays() {
        int n = 0;
        for (Doll d : dolls) if (d.phase == SETTLED || d.phase == SETTLING) n += d.pieces.size();
        return n;
    }

    private int sinkingDisplays() {
        int n = 0;
        for (Doll d : dolls) if (d.phase == SINKING) n += d.pieces.size();
        return n;
    }

    /** 지금 있는 래그돌 디스플레이 모두(움직임 + 굳음 + 꺼지는 중). */
    public int displays() {
        int n = 0;
        for (Doll d : dolls) if (d.phase != GONE) n += d.pieces.size() + d.segments();
        return n;
    }

    /**
     * 물리 단계에 디스플레이 need 개(떼 마리가 아니면 구 하나)가 들어갈 자리를 만든다: 가장 오래된 물리 단계의 구부터 값싼 단계로 내린다
     * (바로 굳히지 않고 마저 날아가 눕게). 내릴 것이 없거나 너무 어리면 false(새 죽음이 값싼 단계로 간다).
     */
    private boolean room(int need, boolean member, long now) {
        if (need > maxDisplays || (!member && maxActive <= 0)) return false;
        if (!member) {
            while (activeBodies() >= maxActive) {
                Doll v = oldestPhysical(true);
                if (v == null || now - v.born < minAir) return false;
                demote(v, now);
            }
        }
        while (activeDisplays() + need > maxDisplays) {
            Doll v = oldestPhysical(false);
            if (v == null) return false;
            if (v.phase == ACTIVE) {
                if (now - v.born < (v.member ? minAir / 2 : minAir)) return false;
                demote(v, now);
            } else {
                // 굳는 중: 남은 마디만 바로 치우고 누운 자세를 켬
                removeChains(v);
                applyVariant(v);
            }
        }
        // 접히는 중인 마디가 너무 많으면 오래된 것부터 바로 치움
        int fold = foldingDisplays();
        for (int i = 0; fold > maxDisplays / 2 && i < dolls.size(); i++) {
            Doll d = dolls.get(i);
            if (d.phase != ACTIVE || !d.cheap || d.chains.isEmpty()) continue;
            fold -= d.segments();
            removeChains(d);
            applyVariant(d);
        }
        return true;
    }

    private Doll oldestPhysical(boolean bodiesOnly) {
        for (Doll d : dolls) {
            if (d.phase == ACTIVE && !d.cheap && !(bodiesOnly && d.member)) return d;
            if (!bodiesOnly && d.phase == SETTLING && !d.chains.isEmpty()) return d;
        }
        return null;
    }

    /**
     * 물리 단계의 구를 값싼 단계로 내린다(새 죽음에 자리를 내줌). 지금 상태에서 끝까지 굴려 본 길의 키프레임으로 마저 날아가 눕는다.
     * 사슬은 첫 키프레임까지 누운 자리로 접힌 뒤 몸의 누운 자세로 바뀐다.
     */
    private void demote(Doll v, long now) {
        evicted++;
        v.cheap = true;
        v.stillAt = -1;
        Piece main = v.pieces.get(0);
        for (Piece p : v.pieces) plan(p, now, allowed(v, p));
        v.variant = variantOf(v, main);
        corpsePos(v);
        v.foldAt = now + main.keys[0].at;
        for (Piece p : v.pieces) sendKey(v, p, now);
    }

    /** 이 조각이 굳을 때 댈 수 있는 면: 누운 막대기 자세가 구워진 면들(머리 조각이고 막대기를 모두 그릴 때), 그 밖은 아무 면(null). */
    private static int[] allowed(Doll d, Piece p) {
        return p == d.pieces.get(0) && d.floppy && d.full && p.rag != null && p.rag.faces().length > 0 ? p.rag.faces() : null;
    }

    /** 머리 조각이 굳는 면의 누운 자세 번호(없으면 -1). */
    private static int variantOf(Doll d, Piece main) {
        int[] faces = allowed(d, main);
        if (faces == null) return -1;
        for (int i = 0; i < faces.length; i++) if (faces[i] == main.face) return i;
        return -1;
    }

    /** 조각의 얼굴 방향(몸 틀): 키트가 구운 것, 없으면(옛 조각) 닻 몸과 떼 마리는 앞(+Z), 부품은 없음. 얼굴 없는 모델(0 벡터)은 null. */
    private static float[] front(Look.Rag rag, boolean head) {
        float[] f = rag == null ? null : rag.front();
        if (f == null) return head ? FRONT : null;
        return f.length == 3 && f[0] * f[0] + f[1] * f[1] + f[2] * f[2] > 0.25f ? f : null;
    }

    /** 굳을 때 피할 면 묶음: 얼굴 방향 f 가 바닥에 깔리는 면들(얼굴이 없거나 faceUp 을 껐으면 0). */
    private int avoid(float[] f) {
        return f == null || !faceUp ? 0 : faceMask(f);
    }

    /** 얼굴 방향 f 가 바닥에 깔리는 면 묶음(비트 n = 면 n: 0 아래 1 위 2 뒤 3 앞 4 -X 5 +X). */
    private static int faceMask(float[] f) {
        int m = 0;
        if (-f[1] > FACE_DOWN) m |= 1;
        if (f[1] > FACE_DOWN) m |= 2;
        if (-f[2] > FACE_DOWN) m |= 4;
        if (f[2] > FACE_DOWN) m |= 8;
        if (-f[0] > FACE_DOWN) m |= 16;
        if (f[0] > FACE_DOWN) m |= 32;
        return m;
    }

    /** 시체 풀의 자리(시체 뜯는 종이 찾아오는 곳)를 머리 조각의 자리로. */
    private void corpsePos(Doll d) {
        RagBody b = d.pieces.get(0).b;
        d.corpse.pos.setX(b.x).setY(terrain.floor(b.x, b.z)).setZ(b.z);
    }

    // ------------------------------------------------------------------ 시작

    /**
     * 죽은 개체의 몸을 래그돌로 넘겨받는다({@link Mobs#onDeath} 가 부름). 넘겨받았으면 true(시체 풀에도 올림). false 면 엔진이 예전
     * 납작 사체로 처리한다(래그돌을 껐거나, 종이 껐거나, 물리 단계와 값싼 단계 모두 자리가 없음).
     */
    boolean start(Gabe g, Damage dmg) {
        long t0 = System.nanoTime();
        try {
            return start0(g, dmg);
        } finally {
            double ms = (System.nanoTime() - t0) / 1e6;
            startSumMs += ms;
            startN++;
            if (ms > startMaxMs) startMaxMs = ms;
        }
    }

    /** 시작 한 번에 든 평균 시간(ms). */
    public double startAvgMs() {
        return startN == 0 ? 0 : startSumMs / startN;
    }

    private boolean start0(Gabe g, Damage dmg) {
        Body body = g.body;
        if (!enabled || !g.ragdoll || body == null || body.removed || body.anchor == null || !body.anchor.isValid()) return false;
        // 떼: 남은 마리는 저마다 작은 사체(자리가 없으면 납작), 닻은 그릴 것이 없으면 바로 지움
        if (body.swarm != null) body.swarm.dieAll();
        boolean hasAnchor = !body.carrier;
        if (!hasAnchor && body.pieces.isEmpty()) {
            body.remove();
            return true;
        }
        long now = game.now(), wt = game.worldTime();
        int nParts = Math.min(body.pieces.size(), Math.max(0, maxPieces - (hasAnchor ? 1 : 0)));
        // 흐물 막대기: 사체 상태가 구워진 모델만(그 밖은 얼어붙은 몸이 통째로)
        Look.Rag rag = hasAnchor ? Look.rag(body.model, body.stateName()) : null;
        int cap = Math.min(maxRods, g.ragRods < 0 ? Integer.MAX_VALUE : g.ragRods);
        boolean corpseState = rag != null && rag.corpse() != null && cap > 0;
        boolean floppy = corpseState && Look.has(ROD_MODEL);
        List<Look.RagRod> rods = new ArrayList<>(4);
        boolean full = true;
        int segs = 0;
        if (floppy) {
            for (Look.RagRod r : rag.rods()) {
                if (!shown(r, body.look)) continue;
                if (rods.size() >= cap) {
                    full = false;
                    break;
                }
                rods.add(r);
                segs += Math.max(1, r.segs());
            }
        }
        int base = (hasAnchor ? 1 : 0) + nParts;
        boolean cheap = false;
        if (!room(base + segs, false, now)) {
            // 물리 단계에 자리가 없음: 값싼 단계(키프레임)로. 그것도 넘치면 납작 사체(붙든 사체 = 보스는 넘쳐도 값싼 단계로: 떼 무너짐 물결과 겹쳐도 납작해지지 않게)
            if (cheapMoving() + base > maxCheap && g.ragHold <= 0) {
                refused++;
                return false;
            }
            cheap = true;
            // 사슬 없이 몸의 사체 상태가 굳을 면의 누운 막대기를 처음부터 그린다
            floppy = corpseState;
            full = true;
            rods.clear();
        }

        // 지금 자세(몸 틀)와 죽기 직전 움직임
        body.stretch.set(1, 1, 1);
        double ex = body.ex(), ey = body.ey(), ez = body.ez();
        // 마지막으로 보낸 자리와 지금 자리의 차이(조각 원점은 지금 자리로)
        double fx = g.pos.getX() - ex, fy = g.pos.getY() - body.lift - ey, fz = g.pos.getZ() - ez;
        Vector inherit = mobs.lastMove(g);
        Vector center = new Vector(g.pos.getX() + body.offset.x, g.pos.getY() + body.offset.y, g.pos.getZ() + body.offset.z);
        Doll doll = new Doll(g.sp, now, false);
        doll.hold = g.ragHold;
        doll.floppy = floppy;
        doll.full = full;
        doll.cheap = cheap;
        doll.inherit = inherit.length();
        if (hasAnchor) {
            Matrix4f m = body.frame(g.yaw, g.pitch, Look.scale(body.model));
            LookState look = dead(body.look, wt);
            String state = body.stateName();
            if (floppy) {
                state = rag.corpse();
                look.pose = rag.faces().length;
            }
            Piece p = piece(body.anchor, m, body.scale, ex, ey, ez, fx, fy, fz, body.model, state, look, rag, g.radius);
            p.front = front(rag, true);
            doll.pieces.add(p);
        }
        for (int i = 0; i < body.pieces.size(); i++) {
            Body.Part part = body.pieces.get(i);
            if (part.d == null || !part.d.isValid()) continue;
            if (i >= nParts) {
                // 조각 상한을 넘는 부품은 그 자리에서 줄어들어 사라짐
                shrinkAway(part.d, part.rig(g.yaw, g.pitch));
                continue;
            }
            LookState look = dead(part.look, wt);
            Look.Rag pr = Look.rag(part.model, part.st);
            Piece p = piece(part.d, part.rig(g.yaw, g.pitch), 0, ex, ey, ez, fx, fy, fz, part.model, part.st, look, pr, g.radius * 0.5);
            p.front = front(pr, false);
            doll.pieces.add(p);
            part.d.setItemStack(Body.item(p.model, p.state, p.look, wt));
        }
        if (doll.pieces.isEmpty()) {
            body.remove();
            return true;
        }
        body.corpse = true;

        // 충격: 머리 조각은 맞은 점만큼 회전, 부품은 몸 가운데에서 바깥으로 흩어짐
        Piece main = doll.pieces.get(0);
        for (int i = 0; i < doll.pieces.size(); i++) {
            Piece p = doll.pieces.get(i);
            p.b.vx = inherit.getX();
            p.b.vy = inherit.getY();
            p.b.vz = inherit.getZ();
            kick(p.b, dmg, g.ragKick, i == 0 && hasAnchor);
            if (i > 0 || !hasAnchor) {
                double ox = p.b.x - center.getX(), oy = p.b.y - center.getY(), oz = p.b.z - center.getZ();
                double ol = Math.sqrt(ox * ox + oy * oy + oz * oz), sp = (0.06 + rnd.nextDouble() * 0.14) * Math.min(1, kick * g.ragKick);
                if (ol > 1e-3) {
                    p.b.vx += ox / ol * sp;
                    p.b.vy += oy / ol * sp * 0.6 + 0.04;
                    p.b.vz += oz / ol * sp;
                }
            }
        }
        for (Piece p : doll.pieces) {
            p.d.setDisplayWidth(0);
            p.d.setDisplayHeight(0);
        }
        Vector pos = new Vector(main.b.x, terrain.floor(main.b.x, main.b.z), main.b.z);
        doll.corpse = mobs.addRagCorpse(g.sp, pos, body, null, doll, now + maxAir + doll.life() + sinkTicks + 40);
        if (cheap) {
            // 값싼 단계: 길을 미리 굴려 보고 굳을 면의 누운 자세를 몸에 실은 뒤 첫 키프레임을 보낸다
            for (Piece p : doll.pieces) plan(p, now, allowed(doll, p));
            if (hasAnchor) {
                doll.variant = variantOf(doll, main);
                if (doll.variant >= 0) main.look.pose = doll.variant;
                body.anchor.setItemStack(Body.item(main.model, main.state, main.look, wt));
            }
            doll.applied = true;
            corpsePos(doll);
            for (Piece p : doll.pieces) sendKey(doll, p, now);
            cheapStarted++;
        } else {
            if (hasAnchor) body.anchor.setItemStack(Body.item(main.model, main.state, main.look, wt));
            for (Piece p : doll.pieces) send(p, 2, 0);
            // 사슬
            if (floppy && !rods.isEmpty()) {
                Location at = new Location(game.world, ex, ey, ez, 0, 0);
                for (Look.RagRod r : rods) doll.chains.add(chain(main, r, at, body, wt));
            }
        }
        dolls.add(doll);
        started++;
        return true;
    }

    /**
     * 떼 마리 하나를 작은 강체 하나로({@link Swarm#kill} 이 닻에서 내린 뒤 부름, 사슬 없음). at = 마리의 세계 위치. 넘겨받았으면 true.
     * 물리 단계에 자리가 없으면 값싼 단계로, 그것도 넘치면 false(예전처럼 납작해짐).
     */
    boolean startMember(Swarm s, Swarm.Member m, Vector at) {
        if (!enabled || !s.owner.ragdoll || m.d == null || !m.d.isValid()) return false;
        long now = game.now(), wt = game.worldTime();
        boolean cheap = false;
        if (!room(1, true, now)) {
            if (cheapMoving() >= maxCheap) {
                refused++;
                return false;
            }
            cheap = true;
        }
        Location e = m.d.getLocation();
        LookState look = dead(m.look, wt);
        Matrix4f fm = m.rig();
        Vector3f t = fm.getTranslation(new Vector3f());
        Look.Rag rag = Look.rag(s.model, m.st);
        // 조각 원점이 마리의 지금 자리에 오게(닻 자리는 한 틱 늦을 수 있음)
        Piece p = piece(m.d, fm, s.memberScale * m.scale, e.getX(), e.getY(), e.getZ(), at.getX() - e.getX() - t.x, at.getY() - e.getY() - t.y,
                at.getZ() - e.getZ() - t.z, s.model, m.st, look, rag, s.memberRadius);
        p.front = front(rag, true);
        Vector inherit = mobs.lastMove(s.owner).add(m.velocity());
        p.b.vx = inherit.getX();
        p.b.vy = inherit.getY();
        p.b.vz = inherit.getZ();
        kick(p.b, mobs.hitNow(), s.owner.ragKick, true);
        m.d.setItemStack(Body.item(p.model, p.state, p.look, wt));
        Doll doll = new Doll(s.owner.sp, now, true);
        doll.cheap = cheap;
        doll.inherit = inherit.length();
        doll.pieces.add(p);
        Vector pos = new Vector(p.b.x, terrain.floor(p.b.x, p.b.z), p.b.z);
        doll.corpse = mobs.addRagCorpse(s.owner.sp, pos, null, m.d, doll, now + maxAir + memberTicks + sinkTicks + 40);
        if (cheap) {
            plan(p, now, null);
            doll.applied = true;
            corpsePos(doll);
            sendKey(doll, p, now);
            cheapStarted++;
        } else send(p, 2, 0);
        dolls.add(doll);
        started++;
        startedMembers++;
        return true;
    }

    /** 죽은 몸의 겉모습: 펄럭임 멈춤, 실눈 감김(웃는 얼굴은 그대로), 맞은 번쩍임은 남김(굳을 때 지움). */
    private static LookState dead(LookState src, long wt) {
        LookState ls = src.copy();
        ls.freeze = true;
        ls.open = false;
        ls.pose = 0;
        if (ls.hurtUntil <= wt) ls.hurtUntil = 0;
        return ls;
    }

    private static boolean shown(Look.RagRod r, LookState look) {
        for (String c : r.show()) if (!Look.cond(c, look)) return false;
        return true;
    }

    /**
     * 조각 하나: 디스플레이 d, 지금 틀 m(엔티티 자리 기준, 모델 뒤집기 뺀 것), 엔티티 자리 e, 원점 보정 f. uniform 이 0 보다 크면 크기는 그
     * 배율로 고르게(닻, 떼 마리: 찌그러짐이나 움찔 배율을 버림), 아니면 틀의 축마다 배율 그대로(부품). 상자는 래그돌 정보가 있으면 그것,
     * 없으면(새 키트로 굽지 않은 모델) 반지름 어림.
     */
    private Piece piece(ItemDisplay d, Matrix4f m, float uniform, double ex, double ey, double ez, double fx, double fy, double fz, String model,
            String state, LookState look, Look.Rag rag, double radius) {
        Vector3f t = m.getTranslation(new Vector3f()), sc = m.getScale(new Vector3f());
        Quaternionf rot = m.getNormalizedRotation(new Quaternionf());
        // 디스플레이 배율 sc 는 모델 화소 배율(줄여 구운 모델은 Look.scale 이 곱해짐), 래그돌 정보 좌표(블록)에는 그것을 뺀 배율을 곱한다
        float ls = Math.max(1e-3f, Look.scale(model));
        if (uniform > 0) sc.set(uniform * ls);
        float kx = Math.max(0.02f, sc.x / ls), ky = Math.max(0.02f, sc.y / ls), kz = Math.max(0.02f, sc.z / ls);
        RagBody b;
        if (rag != null) {
            float[] x = rag.box();
            b = new RagBody(x[0] * kx, x[1] * ky, x[2] * kz, x[3] * kx, x[4] * ky, x[5] * kz);
        } else {
            double h = Math.max(0.1, Math.min(0.9, radius));
            b = new RagBody(0, 0, 0, h * kx, h * ky, h * 0.8 * kz);
        }
        b.q.set(rot.x, rot.y, rot.z, rot.w).normalize();
        b.place(ex + t.x + fx, ey + t.y + fy, ez + t.z + fz);
        Piece p = new Piece(d, b, ex, ey, ez, sc, model, state, look);
        p.rag = rag;
        p.size = (kx + ky + kz) / 3;
        p.radius = 0.95 * (b.hx + b.hy + b.hz) / 3;
        return p;
    }

    /** 상한을 넘는 부품: 6틱 동안 줄어들어 사라짐(몸이 지워질 때 함께 지워짐). */
    private static void shrinkAway(ItemDisplay d, Matrix4f frame) {
        Vector3f t = frame.getTranslation(new Vector3f());
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(6);
        d.setTransformation(new Transformation(t, frame.getNormalizedRotation(new Quaternionf()), new Vector3f(0.01f, 0.01f, 0.01f), FLIP));
    }

    private Chain chain(Piece main, Look.RagRod r, Location at, Body body, long wt) {
        Chain c = new Chain(r, main.size);
        // 구워진 방향으로 곧게 펴서 몸과 같은 속도로 출발
        main.b.q.transform(tmp.set(r.dir()[0], r.dir()[1], r.dir()[2]));
        double dl = tmp.length();
        if (dl < 1e-6) tmp.set(0, -1, 0);
        else tmp.div(dl);
        double dx = tmp.x, dy = tmp.y, dz = tmp.z;
        c.c.init(main.b, dx, dy, dz);
        ItemStack it = Body.item(ROD_MODEL, r.part() == null || r.part().equals("rod") ? "" : r.part(), new LookState(), wt);
        for (int i = 0; i < c.d.length; i++) {
            c.rot[i] = new Quaternionf().rotationTo(0, 1, 0, (float) dx, (float) dy, (float) dz);
            c.dir[i * 3] = dx;
            c.dir[i * 3 + 1] = dy;
            c.dir[i * 3 + 2] = dz;
            Transformation tf = segment(main, c, i);
            ItemDisplay sd = game.world.spawn(at, ItemDisplay.class, x -> {
                Body.setup(x, it, new Matrix4f(), body.viewRange, 0, 0);
                x.setInterpolationDuration(2);
                x.setTransformation(tf);
            });
            for (Player h : body.hiddenFrom) h.hideEntity(game.plugin, sd);
            c.d[i] = sd;
        }
        return c;
    }

    // ------------------------------------------------------------------ 충격

    /**
     * 죽인 피해의 종류와 방향으로 충격을 준다(spin 이면 맞은 점이 가운데에서 벗어난 만큼 회전). 총알은 작게, 산탄은 크게, 저격은 세게 멀리,
     * 폭발은 폭발점에서 바깥 위로 크게, 불과 물어뜯김과 그 밖은 제자리에서 쓰러진다. 큰 몸은 덜 날아간다.
     */
    private void kick(RagBody b, Damage d, double mul, boolean spin) {
        DamageType type = d == null ? DamageType.OTHER : d.type();
        double back, up;
        switch (type) {
            case BULLET -> {
                back = 0.25 + rnd.nextDouble() * 0.2;
                up = 0.1 + rnd.nextDouble() * 0.08;
            }
            case PELLET -> {
                back = 0.45 + rnd.nextDouble() * 0.25;
                up = 0.16 + rnd.nextDouble() * 0.12;
            }
            case SNIPER -> {
                back = 0.7 + rnd.nextDouble() * 0.3;
                up = 0.16 + rnd.nextDouble() * 0.1;
            }
            case EXPLOSION -> {
                back = 0.3 + rnd.nextDouble() * 0.25;
                up = 0.3 + rnd.nextDouble() * 0.2;
            }
            default -> {
                topple(b, mul);
                return;
            }
        }
        double size = Math.cbrt(8 * b.hx * b.hy * b.hz);
        double k = kick * mul * Math.max(0.5, Math.min(1.2, Math.pow(0.8 / Math.max(0.3, size), 0.6)));
        double dx, dz;
        Vector dir = d.dir();
        if (type == DamageType.EXPLOSION) {
            // 폭발점(같은 틱의 반경 물음 자리, 없으면 맞은 점에서 방향 반대로 1.5블록)에서 바깥으로. 가까울수록 세게
            Vector o = mobs.blastAt();
            if (o == null && d.at() != null && dir != null) o = d.at().clone().subtract(dir.clone().multiply(1.5));
            double ox = o == null ? 0 : b.x - o.getX(), oz = o == null ? 0 : b.z - o.getZ(), ol = Math.sqrt(ox * ox + oz * oz);
            if (ol < 0.05) {
                double a = rnd.nextDouble() * Math.PI * 2;
                dx = Math.cos(a);
                dz = Math.sin(a);
                ol = 0;
            } else {
                dx = ox / ol;
                dz = oz / ol;
            }
            double near = Math.max(0.55, Math.min(1.15, 1.3 - ol * 0.14));
            back *= near;
            up *= near;
        } else if (dir != null && dir.getX() * dir.getX() + dir.getZ() * dir.getZ() > 1e-6) {
            double l = Math.sqrt(dir.getX() * dir.getX() + dir.getZ() * dir.getZ());
            dx = dir.getX() / l;
            dz = dir.getZ() / l;
        } else {
            double a = rnd.nextDouble() * Math.PI * 2;
            dx = Math.cos(a);
            dz = Math.sin(a);
        }
        double jx = dx * back * k, jy = up * k, jz = dz * back * k;
        double rx = 0, ry = 0, rz = 0;
        if (spin && d.at() != null && type != DamageType.EXPLOSION) {
            // 맞은 점(상자 가운데 기준, 상자 크기 안으로)
            rx = d.at().getX() - b.x;
            ry = d.at().getY() - b.y;
            rz = d.at().getZ() - b.z;
            double rl = Math.sqrt(rx * rx + ry * ry + rz * rz), lim = Math.max(b.hx, Math.max(b.hy, b.hz));
            if (rl > lim) {
                rx *= lim / rl;
                ry *= lim / rl;
                rz *= lim / rl;
            }
        }
        b.impulse(jx, jy, jz, rx * 0.8, ry * 0.8, rz * 0.8);
        // 조금의 제멋대로 회전(폭발은 크게)
        double w = type == DamageType.EXPLOSION ? 0.15 + rnd.nextDouble() * 0.3 : 0.03 + rnd.nextDouble() * 0.09;
        b.wx += rnd.nextGaussian() * w;
        b.wy += rnd.nextGaussian() * w * 0.6;
        b.wz += rnd.nextGaussian() * w;
    }

    /** 제자리에서 쓰러짐: 바닥에 닿을 면의 짧은 변 쪽으로 넘어갈 만큼의 회전(이미 납작한 몸은 그냥 떨어짐). */
    private void topple(RagBody b, double mul) {
        int f = b.downFace();
        double up = b.halfOf(f), ha, hb;
        // 바닥 면의 두 변(반 크기)과 그 몸 틀 축
        double ax, ay, az, bx, by, bz;
        if (f < 2) {
            ha = b.hx;
            hb = b.hz;
            ax = 1;
            ay = 0;
            az = 0;
            bx = 0;
            by = 0;
            bz = 1;
        } else if (f < 4) {
            ha = b.hx;
            hb = b.hy;
            ax = 1;
            ay = 0;
            az = 0;
            bx = 0;
            by = 1;
            bz = 0;
        } else {
            ha = b.hy;
            hb = b.hz;
            ax = 0;
            ay = 1;
            az = 0;
            bx = 0;
            by = 0;
            bz = 1;
        }
        if (hb > ha) {
            // 짧은 변이 b 가 되게(긴 변 a 를 축으로 돌아 짧은 변 쪽으로 넘어감)
            double s = ha;
            ha = hb;
            hb = s;
            s = ax;
            ax = bx;
            bx = s;
            s = ay;
            ay = by;
            by = s;
            s = az;
            az = bz;
            bz = s;
        }
        double w = 0.03 + rnd.nextDouble() * 0.05;
        if (up > hb * 0.8 && mul > 0) {
            // 모서리를 넘는 데 드는 각속도(가운데가 솟는 높이만큼)의 1.7배
            double diag = Math.sqrt(up * up + hb * hb), rise = diag - up;
            w = Math.min(0.5, Math.sqrt(2 * gravity * rise / (4.0 / 3.0 * diag * diag)) * (1.7 + rnd.nextDouble() * 0.5));
        }
        double sgn = rnd.nextBoolean() ? 1 : -1;
        // 넘어가는 축(긴 변)과 몸의 위쪽(바닥 면의 반대)을 세계 축으로
        Vector3d axis = b.q.transform(new Vector3d(ax, ay, az));
        Vector3d upW = b.q.transform(new Vector3d(f == 4 ? 1 : f == 5 ? -1 : 0, f == 0 ? 1 : f == 1 ? -1 : 0, f == 2 ? 1 : f == 3 ? -1 : 0));
        b.wx += axis.x * w * sgn;
        b.wy += axis.y * w * sgn + rnd.nextGaussian() * 0.03;
        b.wz += axis.z * w * sgn;
        // 윗부분이 넘어가는 쪽으로 가운데도 조금 밀림(바닥 쪽이 제자리에 남게): 속도 = 각속도 x 팔
        Vector3d push = axis.cross(upW, new Vector3d()).mul(w * sgn * up * 0.6);
        b.vx += push.x + (rnd.nextDouble() - 0.5) * 0.02;
        b.vz += push.z + (rnd.nextDouble() - 0.5) * 0.02;
    }

    // ------------------------------------------------------------------ 틱

    void tick() {
        long now = game.now();
        if (dolls.isEmpty()) {
            if (pileDirty) restack(now);
            return;
        }
        for (int i = 0; i < dolls.size(); i++) {
            Doll d = dolls.get(i);
            // 먹혔거나(Mobs.eat) 판이 치운 것
            if (d.corpse == null || d.corpse.gone) {
                d.dispose();
                continue;
            }
            switch (d.phase) {
                case ACTIVE -> {
                    if (d.cheap) cheap(d, now);
                    else active(d, now);
                }
                case SETTLING -> {
                    for (Piece p : d.pieces) sendKey(d, p, now);
                    if (now - d.phaseAt > d.blend) merge(d, now);
                }
                case SETTLED -> {
                    if (now >= d.until) sink(d, now);
                }
                case SINKING -> {
                    if (now - d.phaseAt >= sinkTicks) d.corpse.remove();
                }
                default -> {
                }
            }
        }
        separate();
        dolls.removeIf(d -> d.phase == GONE);
        // 꺼지는 중인 것이 너무 많으면 오래된 것부터 바로 치움
        int sinking = sinkingDisplays(), cap = Math.max(24, maxSettled / 4);
        for (int i = 0; sinking > cap && i < dolls.size(); i++) {
            Doll d = dolls.get(i);
            if (d.phase != SINKING) continue;
            sinking -= d.pieces.size();
            d.corpse.remove();
        }
        if (pileDirty) restack(now);
    }

    /** 물리 단계의 한 틱: 조각과 사슬을 굴리고 2틱마다 보낸다. 모두 멈추면 굳힌다. */
    private void active(Doll d, long now) {
        boolean still = true;
        for (Piece p : d.pieces) {
            RagBody b = p.b;
            // 멈춰 있던 조각 밑의 더미가 꺼졌으면 다시 떨어진다
            if (b.rest >= 3 && pileOn && b.y + b.lowest() - ground.floor(b.x, b.z) > 0.05) b.rest = 0;
            if (b.rest < 3) {
                b.step(ground, gravity, bounce, friction);
                if (b.rest < 3) still = false;
            }
        }
        Piece main = d.pieces.get(0);
        for (Chain c : d.chains) c.c.step(main.b, ground);
        corpsePos(d);
        long age = now - d.born;
        if (still) {
            if (d.stillAt < 0) d.stillAt = now;
            // 몸이 멈춘 뒤 막대기도 잦아들면(길어야 10틱) 굳힌다
            boolean calm = true;
            for (Chain c : d.chains) if (c.c.motion() > 0.03) calm = false;
            if (calm || now - d.stillAt >= 10) {
                settle(d, now);
                return;
            }
        } else d.stillAt = -1;
        if (age >= maxAir) {
            settle(d, now);
            return;
        }
        // 2틱마다(구마다 어긋나게) 보간 자세
        if (((age + d.id) & 1) == 0) {
            for (Piece p : d.pieces) if (p.b.rest < 3 || age < 4) send(p, 2, 0);
            for (Chain c : d.chains) send(main, c, 2);
        }
    }

    /** 값싼 단계의 한 틱: 때가 된 키프레임만 보낸다. 모두 닿으면 굳은 것으로 친다. */
    private void cheap(Doll d, long now) {
        if (!d.chains.isEmpty() && now >= d.foldAt) {
            removeChains(d);
            applyVariant(d);
        }
        boolean done = true;
        for (Piece p : d.pieces) {
            sendKey(d, p, now);
            if (!p.arrived(now)) done = false;
        }
        if (!done) return;
        removeChains(d);
        applyVariant(d);
        long life = d.life();
        // 맞은 번쩍임 값은 480틱마다 되풀이되므로 그보다 오래 남을 사체는 지워 둔다
        if (life + sinkTicks + (now - d.born) > 400) clearHurt(d);
        d.phase = SETTLED;
        d.phaseAt = now;
        d.until = now + life;
        d.corpse.until = d.until + sinkTicks + 2;
        trimSettled(now, d);
    }

    /** 움직이는 구끼리(물리 단계) 가운데 구로 가볍게 밀어낸다: 겹친 만큼의 반을 벌리고 다가오는 속도를 없앤다. 같은 구의 조각끼리는 뺀다. */
    private void separate() {
        int n = 0;
        for (Doll d : dolls) {
            if (d.phase != ACTIVE || d.cheap) continue;
            for (Piece p : d.pieces) {
                if (p.b.rest >= 3) continue;
                if (n == rp.length) grow();
                rp[n] = p;
                rd[n++] = d;
            }
        }
        for (int i = 0; i < n; i++) {
            RagBody a = rp[i].b;
            for (int j = i + 1; j < n; j++) {
                if (rd[i] == rd[j]) continue;
                RagBody b = rp[j].b;
                double r = rp[i].radius + rp[j].radius, dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z, d2 = dx * dx + dy * dy + dz * dz;
                if (d2 >= r * r || d2 < 1e-8) continue;
                double dist = Math.sqrt(d2), k = (r - dist) / dist * 0.25;
                a.x -= dx * k;
                a.y -= dy * k;
                a.z -= dz * k;
                b.x += dx * k;
                b.y += dy * k;
                b.z += dz * k;
                double nx = dx / dist, ny = dy / dist, nz = dz / dist;
                double rv = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny + (b.vz - a.vz) * nz;
                if (rv < 0) {
                    double h = rv * 0.5;
                    a.vx += nx * h;
                    a.vy += ny * h;
                    a.vz += nz * h;
                    b.vx -= nx * h;
                    b.vy -= ny * h;
                    b.vz -= nz * h;
                }
            }
        }
        Arrays.fill(rp, 0, n, null);
        Arrays.fill(rd, 0, n, null);
    }

    private void grow() {
        int n = rp.length * 2;
        rx = Arrays.copyOf(rx, n);
        rz = Arrays.copyOf(rz, n);
        rr = Arrays.copyOf(rr, n);
        rb = Arrays.copyOf(rb, n);
        rh = Arrays.copyOf(rh, n);
        rorder = Arrays.copyOf(rorder, n);
        rp = Arrays.copyOf(rp, n);
        rd = Arrays.copyOf(rd, n);
    }

    // ------------------------------------------------------------------ 값싼 길, 키프레임

    /**
     * 조각의 길을 지금 상태에서 끝까지 굴려 보고 키프레임으로 남긴다(값싼 단계). 굳을 자리는 더미에 미리 찍어, 같은 틱에 죽은 다음
     * 사체가 그 위에 내려앉게 한다. allowed = 굳을 때 댈 수 있는 면(null 이면 아무 면).
     */
    private void plan(Piece p, long now, int[] allowed) {
        long t0 = System.nanoTime();
        int need = (maxAir + 1) * 7;
        if (planBuf.length < need) planBuf = new double[need];
        RagPlan pl = RagPlan.make(p.b, ground, gravity, bounce, friction, maxAir, allowed, avoid(p.front), ROLL_STEP, planBuf);
        p.keys = pl.keys;
        p.next = 0;
        p.restKey = pl.restKey;
        p.t0 = now;
        p.face = pl.face;
        p.b.rest = 3;
        stamp(p);
        double ms = (System.nanoTime() - t0) / 1e6;
        planSumMs += ms;
        planN++;
        planKeys += pl.keys.length;
        if (ms > planMaxMs) planMaxMs = ms;
    }

    /** 값싼 길 하나를 계산하는 데 든 평균 시간(ms). */
    public double planAvgMs() {
        return planN == 0 ? 0 : planSumMs / planN;
    }

    /**
     * 조각의 다음 키프레임을 때가 됐으면(앞 키프레임이 닿는 틱) 보낸다. 한 번에 하나. 머리 조각이면 남은 사슬도 그 자세의 누운 자리로
     * 함께 보낸다(굳는 중이거나 값싼 단계로 막 내려온 구).
     */
    private void sendKey(Doll d, Piece p, long now) {
        if (p.keys == null || p.next >= p.keys.length) return;
        int prev = p.next == 0 ? 0 : p.keys[p.next - 1].at;
        if (now < p.t0 + prev) return;
        RagPlan.Key k = p.keys[p.next++];
        p.b.pose(k.x, k.y, k.z, k.qx, k.qy, k.qz, k.qw);
        int ticks = (int) Math.max(1, p.t0 + k.at - now);
        send(p, ticks, 0);
        if (!d.chains.isEmpty() && p == d.pieces.get(0)) {
            for (Chain c : d.chains) {
                lie(d, c, p);
                send(p, c, ticks);
            }
        }
    }

    /** 사슬을 고른 면의 누운 자리로(누운 자세가 없는 모델은 붙임점으로 오므라들어 사라짐). 몸은 그 자세여야 한다. */
    private static void lie(Doll d, Chain c, Piece main) {
        if (d.variant >= 0 && d.variant < c.rod.lie().length) c.c.lie(main.b, c.rod.lie()[d.variant], main.size);
        else c.c.retract(main.b);
    }

    // ------------------------------------------------------------------ 보내기

    /** 조각 자세를 ticks 보간으로(dy 만큼 내려서: 꺼질 때). */
    private void send(Piece p, int ticks, double dy) {
        if (!p.d.isValid()) return;
        RagBody b = p.b;
        b.origin(tmp);
        p.d.setInterpolationDelay(0);
        p.d.setInterpolationDuration(ticks);
        p.d.setTransformation(new Transformation(new Vector3f((float) (tmp.x - p.ex), (float) (tmp.y - dy - p.ey), (float) (tmp.z - p.ez)),
                new Quaternionf((float) b.q.x, (float) b.q.y, (float) b.q.z, (float) b.q.w), p.scale, FLIP));
        sent++;
    }

    /** 사슬 마디들을 ticks 보간으로. */
    private void send(Piece main, Chain c, int ticks) {
        for (int i = 0; i < c.d.length; i++) {
            ItemDisplay d = c.d[i];
            if (d == null || !d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(segment(main, c, i));
            sent++;
        }
    }

    /** 마디 i(이음점 i 에서 i + 1)의 transformation: 가운데로 옮김, +Y 를 마디 방향으로(앞 방향에서 가장 짧게 이어 돌림), (굵기, 길이, 굵기). */
    private Transformation segment(Piece main, Chain c, int i) {
        double[] p = c.c.p;
        int a = i * 3;
        double dx = p[a + 3] - p[a], dy = p[a + 4] - p[a + 1], dz = p[a + 5] - p[a + 2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Quaternionf r = c.rot[i];
        if (len > 1e-6) {
            dx /= len;
            dy /= len;
            dz /= len;
            Quaternionf turn = new Quaternionf().rotationTo((float) c.dir[a], (float) c.dir[a + 1], (float) c.dir[a + 2], (float) dx, (float) dy, (float) dz);
            turn.mul(r, r).normalize();
            c.dir[a] = dx;
            c.dir[a + 1] = dy;
            c.dir[a + 2] = dz;
        }
        float th = (float) c.c.thick;
        return new Transformation(
                new Vector3f((float) ((p[a] + p[a + 3]) / 2 - main.ex), (float) ((p[a + 1] + p[a + 4]) / 2 - main.ey), (float) ((p[a + 2] + p[a + 5]) / 2 - main.ez)),
                new Quaternionf(r), new Vector3f(th, (float) Math.max(0.01, len * ROD_OVERLAP), th), NOROT);
    }

    // ------------------------------------------------------------------ 굳기, 굳음, 꺼짐

    /**
     * 물리 단계의 구를 굳히기 시작: 조각마다 바닥을 가장 많이 보는 면(얼굴이 깔리는 면은 빼고)으로 똑바로 눕는 키프레임을 만든다. 그 면이
     * 지금 바닥을 보는 면이 아니면 아래 모서리를 축으로 굴러 넘어간다({@link RagPlan#rest}). 막대기는 키프레임마다 그 면의 누운 자리로
     * 따라가고, 끝나면({@link #merge}) 몸의 누운 자세로 바뀐다. 굳는 자리는 더미에 찍는다.
     */
    private void settle(Doll d, long now) {
        Piece main = d.pieces.get(0);
        int total = BLEND;
        for (Piece p : d.pieces) {
            int face = p.b.restFace(allowed(d, p), avoid(p.front));
            RagPlan.Key[] keys = new RagPlan.Key[4];
            int n = RagPlan.rest(p.b, ground, face, 0, BLEND, ROLL_STEP, keys, 0);
            p.keys = n == keys.length ? keys : Arrays.copyOf(keys, n);
            p.next = 0;
            p.restKey = 0;
            p.t0 = now;
            p.face = face;
            p.b.rest = 3;
            stamp(p);
            total = Math.max(total, p.keys[n - 1].at);
        }
        d.variant = variantOf(d, main);
        corpsePos(d);
        for (Piece p : d.pieces) sendKey(d, p, now);
        d.phase = SETTLING;
        d.phaseAt = now;
        d.blend = total;
        d.until = now + total + d.life();
        d.corpse.until = d.until + sinkTicks + 2;
        trimSettled(now, d);
    }

    /** 굳은 사체 예산: 넘치면 가장 오래된 굳은 것부터 꺼뜨림(keep 은 빼고). */
    private void trimSettled(long now, Doll keep) {
        int over = settledDisplays() - maxSettled;
        for (int i = 0; over > 0 && i < dolls.size(); i++) {
            Doll o = dolls.get(i);
            if (o == keep || o.phase != SETTLED || o.hold > 0) continue;
            over -= o.pieces.size();
            sink(o, now);
        }
    }

    private void removeChains(Doll d) {
        for (Chain c : d.chains) c.remove();
        d.chains.clear();
    }

    /** 고른 누운 자세를 몸 디스플레이에 싣는다(맞은 번쩍임도 지움). */
    private void applyVariant(Doll d) {
        if (d.applied) return;
        d.applied = true;
        long wt = game.worldTime();
        for (int i = 0; i < d.pieces.size(); i++) {
            Piece p = d.pieces.get(i);
            boolean change = p.look.hurtUntil != 0;
            p.look.hurtUntil = 0;
            if (i == 0 && d.variant >= 0) {
                p.look.pose = d.variant;
                change = true;
            }
            if (change && p.d.isValid()) p.d.setItemStack(Body.item(p.model, p.state, p.look, wt));
        }
    }

    private void clearHurt(Doll d) {
        long wt = game.worldTime();
        for (Piece p : d.pieces) {
            if (p.look.hurtUntil == 0) continue;
            p.look.hurtUntil = 0;
            if (p.d.isValid()) p.d.setItemStack(Body.item(p.model, p.state, p.look, wt));
        }
    }

    private void merge(Doll d, long now) {
        removeChains(d);
        applyVariant(d);
        d.phase = SETTLED;
        d.phaseAt = now;
    }

    private void sink(Doll d, long now) {
        if (d.phase == SINKING || d.phase == GONE) return;
        removeChains(d);
        for (Piece p : d.pieces) {
            p.keys = null;
            if (p.stamped) {
                // 더미에서 빠짐: 위에 얹힌 사체가 내려앉는다
                p.stamped = false;
                pileDirty = true;
            }
            send(p, sinkTicks, 2 * Math.max(p.b.hx, Math.max(p.b.hy, p.b.hz)) + 0.3);
        }
        d.phase = SINKING;
        d.phaseAt = now;
        d.corpse.until = now + sinkTicks + 2;
    }

    // ------------------------------------------------------------------ 더미

    /** 조각의 굳은 자세(b 가 그 자세여야 함)를 더미에 찍는다: 가운데, 바닥에 댄 면의 넓이에서 온 발자국 반지름, 밑면 높이, 키. */
    private void stamp(Piece p) {
        if (!pileOn) return;
        RagBody b = p.b;
        int f = p.face;
        double fa = f < 4 ? b.hx : b.hy, fb = f < 2 ? b.hz : f < 4 ? b.hy : b.hz;
        p.sx = b.x;
        p.sz = b.z;
        p.foot = 0.85 * Math.sqrt(fa * fb);
        p.height = 2 * b.halfOf(f);
        p.base = b.y - b.halfOf(f);
        p.stamped = true;
        pile.stamp(p.sx, p.sz, p.foot, p.base, p.base + p.height);
    }

    /**
     * 다시 쌓기(사체가 꺼지거나 먹혀 더미에서 빠진 뒤): 남은 사체를 낮은 것부터 다시 찍고, 받침이 사라진 것을 내려앉힌다. 찍힌 조각 수를
     * 돌려준다.
     */
    private int restack(long now) {
        pileDirty = false;
        if (!pileOn) {
            pile.clear();
            for (Doll d : dolls) for (Piece p : d.pieces) p.stamped = false;
            return 0;
        }
        int n = 0;
        for (Doll d : dolls) {
            if (d.phase == GONE || d.phase == SINKING) continue;
            for (Piece p : d.pieces) {
                if (!p.stamped) continue;
                if (n == rp.length) grow();
                rx[n] = p.sx;
                rz[n] = p.sz;
                rr[n] = p.foot;
                rb[n] = p.base;
                rh[n] = p.height;
                rp[n] = p;
                rd[n++] = d;
            }
        }
        if (pile.restack(n, rx, rz, rr, rb, rh, rorder)) {
            for (int i = 0; i < n; i++) {
                double drop = rp[i].base - rb[i];
                if (drop > 0.02) lower(rd[i], rp[i], drop, now);
            }
        }
        Arrays.fill(rp, 0, n, null);
        Arrays.fill(rd, 0, n, null);
        return n;
    }

    /** 받침이 꺼져 drop 만큼 내려앉는다: 굳는 자리의 남은 키프레임을 내리고, 이미 그 자리로 가는 중이거나 누워 있으면 내린 자세를 다시 보낸다. */
    private void lower(Doll d, Piece p, double drop, long now) {
        dropped++;
        p.base -= drop;
        int len = p.keys == null ? 0 : p.keys.length;
        for (int i = Math.max(p.next, p.restKey); i < len; i++) p.keys[i].y -= drop;
        // 아직 굳는 자리로 떠나지 않았으면(날아가는 중) 남은 키프레임만 내리면 된다
        if (p.next < len && p.next <= p.restKey) return;
        p.b.y -= drop;
        int fall = (int) Math.max(3, Math.min(12, Math.ceil(Math.sqrt(2 * drop / gravity))));
        int left = len > 0 && p.next > 0 ? (int) (p.t0 + p.keys[p.next - 1].at - now) : 0;
        int ticks = Math.max(fall, left);
        send(p, ticks, 0);
        if (!d.chains.isEmpty() && p == d.pieces.get(0)) {
            for (Chain c : d.chains) {
                lie(d, c, p);
                send(p, c, ticks);
            }
        }
    }

    // ------------------------------------------------------------------ 정리, 시험

    /** 모두 치움(판 정리). 시체 풀은 Mobs 가 비운다. */
    void clear() {
        for (Doll d : dolls) {
            d.dispose();
            if (d.corpse != null) d.corpse.remove();
        }
        dolls.clear();
        pile.clear();
        pileDirty = false;
        started = startedMembers = evicted = refused = cheapStarted = dropped = 0;
        startMaxMs = startSumMs = planMaxMs = planSumMs = 0;
        startN = planN = planKeys = 0;
        sent = 0;
    }

    /**
     * 미리 데우기(판을 만들 때 한 번): 첫 죽음에서 클래스를 읽고 해석 실행을 하느라 틱이 길어지지 않게, 강체와 사슬, 값싼 길, 더미 계산을
     * 빈 땅에서 몇백 번 돌려 둔다(엔티티 없음). 끄려면 JVM 옵션 -Dgave.ragdoll.warm=0.
     */
    private void warm() {
        long t0 = System.nanoTime();
        RagBody.Ground flat = new RagBody.Ground() {
            @Override
            public double floor(double x, double z) {
                return 0;
            }

            @Override
            public boolean open(double x, double z) {
                return true;
            }
        };
        RagPile wp = new RagPile(-8, 16, flat);
        RagBody.Ground g = new RagBody.Ground() {
            @Override
            public double floor(double x, double z) {
                return wp.support(x, z);
            }

            @Override
            public boolean open(double x, double z) {
                return true;
            }
        };
        Random r = new Random(7);
        int total = 240, n = 0;
        double[] buf = new double[(maxAir + 1) * 7], x = new double[total], z = new double[total], rad = new double[total], base = new double[total],
                h = new double[total];
        RagPlan.Key[] keys = new RagPlan.Key[4];
        for (int i = 0; i < total; i++) {
            RagBody b = new RagBody(0, -0.15, 0, 0.45, 0.55, 0.275);
            b.q.rotationXYZ(r.nextDouble() * 6.28, r.nextDouble() * 6.28, r.nextDouble() * 6.28);
            b.place(r.nextDouble() * 6 - 3, 1 + r.nextDouble() * 3, r.nextDouble() * 6 - 3);
            b.impulse((r.nextDouble() - 0.5) * 0.8, r.nextDouble() * 0.4, (r.nextDouble() - 0.5) * 0.8, 0.2, 0.1, 0.1);
            int face;
            if (i % 3 == 0) {
                // 물리 단계처럼: 몸과 사슬을 틱마다, 멈추면 굳는 키프레임
                RagChain c = new RagChain(3, 0.3, 0.1, 0.3, 0.2, 0);
                c.init(b, 0, -1, 0);
                for (int t = 0; t < 60 && b.rest < 3; t++) {
                    b.step(g, gravity, bounce, friction);
                    c.step(b, g);
                }
                c.motion();
                face = b.restFace(null, 8);
                RagPlan.rest(b, g, face, 0, BLEND, ROLL_STEP, keys, 0);
                c.retract(b);
            } else face = RagPlan.make(b, g, gravity, bounce, friction, maxAir, null, 8, ROLL_STEP, buf).face;
            double fa = face < 4 ? b.hx : b.hy, fb = face < 2 ? b.hz : face < 4 ? b.hy : b.hz;
            x[n] = b.x;
            z[n] = b.z;
            rad[n] = 0.85 * Math.sqrt(fa * fb);
            h[n] = 2 * b.halfOf(face);
            base[n] = b.y - b.halfOf(face);
            wp.stamp(x[n], z[n], rad[n], base[n], base[n] + h[n]);
            n++;
        }
        wp.restack(n / 2, x, z, rad, base, h, new int[n]);
        // 디스플레이에 싣는 쪽(회전, transformation)
        Quaternionf q = new Quaternionf().rotationTo(0, 1, 0, 0.3f, 0.5f, 0.2f);
        new Transformation(new Vector3f(), q, new Vector3f(1, 1, 1), FLIP);
        try {
            // 마디 아이템 짓기도 한 번(겉모습 표가 이미 읽혀 있을 때만)
            if (Look.has(ROD_MODEL)) Body.item(ROD_MODEL, "", new LookState(), 0);
        } catch (RuntimeException e) {
            // 데우기일 뿐이라 실패해도 그만
        }
        warmMs = (System.nanoTime() - t0) / 1e6;
    }

    /** 시험 훅 '래그돌': 지금 수와 예산, 가장 새 구의 상태. */
    public String status() {
        int a = 0, ch = 0, sg = 0, st = 0, sk = 0;
        for (Doll d : dolls) {
            switch (d.phase) {
                case ACTIVE -> {
                    if (d.cheap) ch++;
                    else a++;
                }
                case SETTLING -> sg++;
                case SETTLED -> st++;
                case SINKING -> sk++;
                default -> {
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("rag=").append(enabled ? "on" : "off").append(" active=").append(a).append('(').append(activeBodies()).append('/').append(maxActive)
                .append(") disp=").append(activeDisplays()).append('/').append(maxDisplays).append(" cheap=").append(ch).append('(').append(cheapMoving())
                .append('/').append(maxCheap).append(") fold=").append(foldingDisplays()).append(" settling=").append(sg).append(" settled=").append(st)
                .append(" sdisp=").append(settledDisplays()).append('/').append(maxSettled).append(" sinking=").append(sk).append(" all=").append(displays())
                .append(" started=").append(started).append(" members=").append(startedMembers).append(" cheapStart=").append(cheapStarted)
                .append(" evicted=").append(evicted).append(" flat=").append(refused).append(" dropped=").append(dropped)
                .append(String.format(Locale.ROOT, " plan=%.3f/%.3fms keys=%.1f sent=%d warm=%.1fms", planAvgMs(), planMaxMs,
                        planN == 0 ? 0 : planKeys / (double) planN, sent, warmMs))
                .append(" pile=").append(pileOn ? "on" : "off").append(" face=").append(faceUp ? "on" : "off").append(" rod=").append(Look.has(ROD_MODEL));
        if (!dolls.isEmpty()) {
            Doll d = dolls.get(dolls.size() - 1);
            Piece p = d.pieces.get(0);
            RagBody b = p.b;
            sb.append(String.format(Locale.ROOT,
                    " last=%s#%d ph=%d cheap=%b age=%d pieces=%d segs=%d floppy=%b var=%d st=%s at=%.1f,%.1f,%.1f v=%.2f w=%.2f face=%d keys=%d/%d inherit=%.2f",
                    d.sp.name(), d.id, d.phase, d.cheap, game.now() - d.born, d.pieces.size(), d.segments(), d.floppy, d.variant, p.state, b.x, b.y, b.z,
                    Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz), Math.sqrt(b.wx * b.wx + b.wy * b.wy + b.wz * b.wz),
                    p.face >= 0 ? p.face : b.downFace(), p.next, p.keys == null ? 0 : p.keys.length, d.inherit));
        }
        return sb.toString();
    }

    /**
     * 시험 훅 '래그돌검사': 굳은 조각의 면별 수(0 아래 1 위 2 뒤 3 앞 4 -X 5 +X), 얼굴이 바닥에 깔린 수(hidden), 다른 사체 위에 얹힌 수
     * (stacked)와 가장 높은 더미 높이(top, 땅에서), 받침 없이 떠 있는 수(floating: 다시 쌓아 보면 내려갈 조각), 자세와 더미 기록이 어긋난
     * 가장 큰 차이(gap).
     */
    public String audit() {
        int[] faces = new int[6];
        int n = 0, hidden = 0, stacked = 0, withFace = 0;
        double top = 0, gap = 0;
        for (Doll d : dolls) {
            if (d.phase != SETTLED) continue;
            for (Piece p : d.pieces) {
                n++;
                if (p.face < 0) continue;
                faces[p.face]++;
                if (p.front != null) {
                    withFace++;
                    if ((faceMask(p.front) >> p.face & 1) != 0) hidden++;
                }
                if (!p.stamped) continue;
                double t = terrain.floor(p.sx, p.sz);
                top = Math.max(top, p.base + p.height - t);
                if (p.base - t > 0.05) stacked++;
                gap = Math.max(gap, Math.abs(p.b.y - p.b.halfOf(p.face) - p.base));
            }
        }
        // 떠 있는 것: 다시 쌓아 보아 내려가는 조각(있으면 다음 틱에 진짜로 내려앉힌다)
        int floating = 0, stamped = 0;
        if (pileOn) {
            for (Doll d : dolls) {
                if (d.phase == GONE || d.phase == SINKING) continue;
                for (Piece p : d.pieces) {
                    if (!p.stamped) continue;
                    if (stamped == rp.length) grow();
                    rx[stamped] = p.sx;
                    rz[stamped] = p.sz;
                    rr[stamped] = p.foot;
                    rb[stamped] = p.base;
                    rh[stamped] = p.height;
                    rp[stamped++] = p;
                }
            }
            if (pile.restack(stamped, rx, rz, rr, rb, rh, rorder)) {
                for (int i = 0; i < stamped; i++) if (rp[i].base - rb[i] > 0.02) floating++;
                pileDirty = true;
            }
            Arrays.fill(rp, 0, stamped, null);
        }
        return String.format(Locale.ROOT, "settledPieces=%d faces=%s withFace=%d hidden=%d stacked=%d top=%.2f floating=%d gap=%.3f stamped=%d dropped=%d", n,
                Arrays.toString(faces), withFace, hidden, stacked, top, floating, gap, stamped, dropped);
    }

    /** 시험 훅 '래그돌값': 값 하나를 바꾼다(모르는 키면 null). */
    public String set(String key, double v) {
        switch (key) {
            case "enabled" -> enabled = v != 0;
            case "active" -> maxActive = (int) v;
            case "activeDisplays" -> maxDisplays = (int) v;
            case "settled" -> maxSettled = (int) v;
            case "cheap" -> maxCheap = (int) v;
            case "settleSeconds" -> settleTicks = Math.max(1, (int) Math.round(v * 20));
            case "memberSeconds" -> memberTicks = Math.max(1, (int) Math.round(v * 20));
            case "rods" -> maxRods = (int) v;
            case "kick" -> kick = v;
            case "gravity" -> gravity = Math.max(0.01, v);
            case "bounce" -> bounce = v;
            case "friction" -> friction = v;
            case "minAir" -> minAir = (int) v;
            case "maxAir" -> maxAir = Math.max(1, (int) v);
            case "pile" -> {
                pileOn = v != 0;
                pileDirty = true;
            }
            case "faceUp" -> faceUp = v != 0;
            default -> {
                return null;
            }
        }
        return key + "=" + v;
    }
}
