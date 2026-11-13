package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;

/**
 * G49 축복 주는 게이브(방해, 지원, 미리 깔림).
 *
 * <p>도감: 땅에서 솟은 엔드 막대기 촉수 셋, 끝마다 눈을 감은 평온한 얼굴. 뿌리박고 촉수를 천천히 흔들며 다가오는 사람 쪽으로 세 얼굴이 함께
 * 고개를 기울인다. 공격하지 않는다. 돕기(좌클릭, 3 블록 안에서 바라보고)로 일부러 받은 사람만 40초 축복(G49Bless: 공격력, 재화 두 배,
 * 얼굴에 드러나는 변이, 끝나면 쓰러지고 가짜 동료). 40초 안에 이 게이브를 잡으면 축복은 남고 변이만 풀린다.
 * 가까이(8 블록)서만 여러 목소리의 '같이 가자'가 들린다.
 *
 * <p>몸: 빈 닻 + 부품 셋 {@code gave:gabe/g49}(촉수 하나씩, 세계 축, 상태 {@code ""}, {@code "give"}). 흔들림과 기울임은 5틱마다 부품 자세로
 * (도감의 셰이더 흔들기 대신 서버 보간: 끝 얼굴이 흔들려도 맞은 번쩍임, 가까운 얼굴이 그대로 먹게). 소리 chant(8), bless(16).
 * 글귀 cause_g49, notice_g49_bless(받은 순간 모두에게).
 */
public final class G49 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int ARMS = 3;
    /** 촉수 밑동이 가운데에서 떨어진 거리, 몸 중심(촉수 가운데) 높이, 밑동에서 몸 중심까지. */
    static final double SPREAD = 0.85, MID = 1.5;
    /** 끝 얼굴 가운데(촉수 모델 기준, 팩 g49.py TIP 과 같게). */
    static final float TIP_Y = 1.3f, TIP_Z = 0.45f;
    /** 사람을 알아보는 거리, 기본 숙임과 다가올 때 숙임(도), 흔들림 폭(도). */
    static final double NOTICE_R = 8;
    static final float LEAN = 6f, LEAN_NEAR = 18f, SWAY = 5f;
    static final int POSE_EVERY = 5, GIVE_T = 40;
    static final double TIP_R = 0.36, STALK_R = 0.28;

    private final Body.Part[] arm = new Body.Part[ARMS];
    private final Matrix4f[] frame = new Matrix4f[ARMS];
    private final float[] armYaw = new float[ARMS];
    private final Vector[] tip = new Vector[ARMS], mid = new Vector[ARMS];
    private int nextChant, giveUntil;
    private boolean posed;

    public G49(Game game, Species sp) {
        super(game, sp);
        radius = 0.9;
        hover = MID;
        baseSpeed = 0;
        noPush = true;
        noBite = true; // 가운데는 빈자리(촉수 셋 사이)
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        for (int i = 0; i < ARMS; i++) {
            arm[i] = body.attach("gabe/g49");
            frame[i] = new Matrix4f();
            tip[i] = new Vector();
            mid[i] = new Vector();
            armYaw[i] = (float) (yaw + 120.0 * i);
            if (arm[i] != null) {
                arm[i].follow = false;
                arm[i].look.palette |= Look.PAL_PALE; // 밀랍 인형 같은 창백한 살
                arm[i].look.phase = body.look.phase + i * 13;
            }
        }
        pose(0);
    }

    @Override
    protected void spawned() {
        nextChant = age + 20 + game.random.nextInt(40);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        hold();
        if (!posed || every(POSE_EVERY)) pose(POSE_EVERY);
        String st = age < giveUntil ? "give" : "";
        for (Body.Part a : arm) if (a != null) a.state(st);
        if (age >= nextChant) {
            nextChant = age + 80 + game.random.nextInt(50);
            if (nearest(NOTICE_R) != null) sound("chant", Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 8);
        }
    }

    /** 촉수 i 의 밑동(몸 중심 기준). */
    private void base(int i, Vector3f out) {
        double a = Math.toRadians(90 + 120.0 * i);
        out.set((float) (Math.cos(a) * SPREAD), (float) -MID, (float) (Math.sin(a) * SPREAD));
    }

    /** 세 촉수의 자세: 천천히 흔들리고, 다가오는 사람이 있으면 세 얼굴이 함께 그쪽으로 숙인다. */
    private void pose(int ticks) {
        posed = true;
        Player p = dead ? null : nearest(NOTICE_R);
        Vector3f b = new Vector3f(), v = new Vector3f();
        for (int i = 0; i < ARMS; i++) {
            base(i, b);
            float want;
            float lean;
            if (p != null) {
                Location l = p.getLocation();
                double dx = l.getX() - (pos.getX() + b.x), dz = l.getZ() - (pos.getZ() + b.z);
                want = (float) Math.toDegrees(Math.atan2(-dx, dz));
                double d = Math.hypot(dx, dz);
                lean = LEAN + (float) (LEAN_NEAR - LEAN) * (float) Math.max(0, Math.min(1, (NOTICE_R - d) / (NOTICE_R - 2)));
            } else {
                // 아무도 없으면 바깥을 보며 느리게 돈다
                want = (float) (Math.toDegrees(Math.atan2(-b.x, b.z)) + 20 * Math.sin((age + i * 70) * 0.01));
                lean = LEAN;
            }
            float diff = want - armYaw[i];
            diff = (float) (diff - 360 * Math.floor((diff + 180) / 360));
            armYaw[i] += Math.max(-12f, Math.min(12f, diff));
            float pitchDeg = lean + SWAY * (float) Math.sin((age + i * 47) * 0.05);
            float rollDeg = SWAY * 0.7f * (float) Math.sin((age + i * 83) * 0.037);
            Matrix4f m = frame[i].identity().translate(b.x, b.y, b.z).rotateY((float) Math.toRadians(-armYaw[i])).rotateX((float) Math.toRadians(pitchDeg))
                    .rotateZ((float) Math.toRadians(rollDeg)).translate(0, (float) MID, 0);
            if (arm[i] != null) arm[i].pose(m, ticks);
            m.transformPosition(v.set(0, TIP_Y, TIP_Z));
            tip[i].setX(pos.getX() + v.x).setY(pos.getY() + v.y).setZ(pos.getZ() + v.z);
            m.transformPosition(v.set(0, 0, 0.1f));
            mid[i].setX(pos.getX() + v.x).setY(pos.getY() + v.y).setZ(pos.getZ() + v.z);
        }
    }

    // ------------------------------------------------------------------ 돕기(축복 받기)

    /** 사람이 이 게이브를 보고 돕기(좌클릭): 일부러 받은 사람만 축복. */
    @Override
    public boolean helped(Player p) {
        if (dead || !standing(p)) return false;
        G49Bless bless = G49Bless.of(game);
        if (bless.blessed(p)) return true;
        if (!bless.bless(p, this)) {
            // 이미 다른 잠복이 있거나 판 전체 상한: 받지 못함
            sound("chant", Sound.ENTITY_VILLAGER_NO, 0.6f, 8);
            return true;
        }
        giveUntil = age + GIVE_T;
        sound("bless", Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 16);
        game.hud.notice("notice_g49_bless", Species.G49, 60);
        return true;
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(ARMS * 2);
        if (!posed) pose(0);
        for (int i = 0; i < ARMS; i++) out.add(new double[] {tip[i].getX(), tip[i].getY(), tip[i].getZ(), TIP_R});
        for (int i = 0; i < ARMS; i++) out.add(new double[] {mid[i].getX(), mid[i].getY(), mid[i].getZ(), STALK_R});
        return out;
    }

    @Override
    public Vector head() {
        return posed ? tip[0] : pos;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        G49Bless.of(game).sourceGone(this);
        collapse();
        super.die(d);
    }

    /** 죽으면 촉수 셋이 밑동에서 꺾여 쓰러진다(시체가 된 뒤에는 엔진이 부품 자세를 보내지 않으므로 디스플레이에 바로 싣는다). */
    private void collapse() {
        Vector3f b = new Vector3f();
        float k = Look.scale("gabe/g49");
        for (int i = 0; i < ARMS; i++) {
            if (arm[i] == null || arm[i].d == null || !arm[i].d.isValid()) continue;
            base(i, b);
            Matrix4f m = new Matrix4f().translate(b.x, b.y, b.z).rotateY((float) Math.toRadians(-armYaw[i])).rotateX((float) Math.toRadians(84))
                    .translate(0, (float) MID, 0).scale(k).rotateY((float) Math.PI);
            arm[i].d.setInterpolationDelay(0);
            arm[i].d.setInterpolationDuration(12);
            arm[i].d.setTransformationMatrix(m);
        }
    }

    @Override
    public void remove() {
        G49Bless.of(game).sourceGone(this);
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " bless=" + G49Bless.of(game).count();
    }
}
