package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G91 빗줄기 커튼 게이브(2막 14라운드, 원거리 + 방해, 16블록). 18블록 높이에 수평으로 뜬 16블록 막대기 가로대에 술 스물넷이 매달리고 술 끝마다 얼굴
 * 물방울, 가로대 한가운데에 하늘을 보는 2블록 얼굴. 가로대 아래로 얼굴 물방울 비가 폭 3블록 띠로 쏟아지며 경기장을 한 방향으로 천천히(초당 1.5블록)
 * 가로지르고, 띠 그림자가 2초 앞서 땅을 지나간다. 사람 무리를 지나 끝에 닿으면 크게 돌아 다른 방향에서 다시 쓸어 온다.
 *
 * <p>공격: 비 띠 안에 있으면 0.5초마다 5 와 느려짐 30%. 띠가 지나간 뒤 1블록은 떨어진 물방울이 굴러 0.5초마다 2.
 *
 * <p>대처: 그림자 띠를 보고 옆으로 빠지거나 가로대 끝을 돌아간다. 술(술마다 체력 20 x 감독 배율)을 쏘면 그 아래 비가 멎어 틈이 열린다(술 하나가
 * 0.67블록, 둘셋을 끊으면 설 자리). 가운데 얼굴 x2.
 *
 * <p>몸: 닻 = 가로대와 얼굴(gave:gabe/g91), 부품 비 띠 조각 여섯(g91_seg: 술 넷과 그 아래 빗줄기, 부위 t0..t3 끊긴 술), 앞 그림자 띠(g91_shadow).
 * 사체: 죽으면 비 조각과 그림자를 걷고 가로대만 떨어진다(래그돌).
 */
public final class G91 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 가로대 높이(바닥 위, g91.py ALT 와 같아야 빗줄기가 바닥에 닿음), 가로대 길이, 술 수, 비 띠 반폭, 그림자 띠가 앞선 거리. */
    static final double ALT = 18, BAR = 16, HALF_BAND = 1.5, SHADOW_AHEAD = 3.0;
    static final int TASSELS = 24, SEGS = 6;
    /** 빠르기(블록/틱): 쓸기(초당 1.5), 멀리서 들어올 때, 돌아설 때. 한 틱에 도는 각도, 사람 무리를 지나쳐 돌아서는 거리. */
    static final double SWEEP_V = 0.075, FAR_V = 0.22, TURN_V = 0.04, TURN = 1.5, PAST = 20, FAR = 40;
    /** 비: 맞는 간격, 피해, 느려짐, 술 하나가 덮는 반폭, 술 체력, 가운데 얼굴 배율. */
    static final int RAIN_EVERY = 10, SLOW_TICKS = 20;
    static final double RAIN_DMG = 5, SLOW = 0.3, TASSEL_COVER = 0.55, TASSEL_HP = 20, FACE_MUL = 2;
    /** 떨어진 물방울: 띠 뒤로 구르는 폭(블록), 물기 피해(10틱마다). */
    static final double ROLL = 1.0, ROLL_DMG = 2;

    enum Phase { SWEEP, TURN }

    private Phase phase = Phase.SWEEP;
    private final Body.Part[] segs = new Body.Part[SEGS];
    private Body.Part shadow;
    private float shadowDy = Float.NaN;
    private final double[] tasselHp = new double[TASSELS];
    private boolean init;
    private int cutN;
    /** 마지막으로 몸 자세를 보낸 방향(4틱마다 짧은 보간으로 돌아서게: 15도 문턱에서 툭 꺾이지 않게). */
    private float keyYaw = Float.NaN;
    private List<double[]> sph;
    private int sphAge = -1;

    public G91(Game game, Species sp) {
        super(game, sp);
        radius = 1.0;
        baseSpeed = SWEEP_V;
        noPush = true;
        noBite = true;
        noMutate = true;
        big = true;
        flying = true;
        ragKick = 0.3;
    }

    /** 술 i 의 가로대 위 자리(몸 기준 x). */
    static double tasselX(int i) {
        return -BAR / 2 + (i + 0.5) * BAR / TASSELS;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g91", 20f, G77Sky.boxH(ALT));
        body.viewRange(3.2f);
        for (int s = 0; s < SEGS; s++) {
            segs[s] = body.attach("gabe/g91_seg");
            if (segs[s] != null) segs[s].pose(new Matrix4f().translate((float) (-BAR / 2 + (s + 0.5) * BAR / SEGS), 0, 0), 0);
        }
        shadow = body.attach("gabe/g91_shadow");
        poseShadow();
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
            for (int i = 0; i < TASSELS; i++) tasselHp[i] = TASSEL_HP * G77Sky.hpK(this);
        }
        vel.setY(Math.max(-0.3, Math.min(0.3, (floor() + ALT - pos.getY()) * 0.15)));
        Vector c = G77Sky.centroid(game);
        if (c == null) c = G77Sky.center();
        double k = speed() / baseSpeed;
        Vector off = pos.clone().subtract(c).setY(0);
        double d = off.length();
        if (phase == Phase.SWEEP) {
            if (off.dot(fwd()) > PAST && d > PAST) phase = Phase.TURN;
            double v = (d > FAR && off.dot(fwd()) < 0 ? FAR_V : SWEEP_V) * k;
            Vector f = fwd().multiply(v);
            vel.setX(f.getX()).setZ(f.getZ());
        } else {
            boolean done = turnTo(new Vector(c.getX(), pos.getY(), c.getZ()), (float) TURN);
            Vector f = fwd().multiply(TURN_V * k);
            vel.setX(f.getX()).setZ(f.getZ());
            if (done) phase = Phase.SWEEP;
        }
        pitch = 0;
        if (age % RAIN_EVERY == 0) rain();
        if (every(60)) sound("rain", Sound.WEATHER_RAIN, 0.8f, 64);
        poseShadow();
        if (age % 4 == 0 && Math.abs(yaw - keyYaw) > 0.3f) {
            keyYaw = yaw;
            body.pose(4);
        }
    }

    /** 비 띠 안(가로대 아래 폭 3, 살아 있는 술 아래) 사람에게 5 와 느려짐. */
    private void rain() {
        double fl = floor();
        for (Player p : new ArrayList<>(game.mobs.standing())) {
            Location l = p.getLocation();
            if (l.getY() > fl + 4) continue;
            double[] o = G77Sky.toLocal(l.getX() - pos.getX(), l.getZ() - pos.getZ(), yaw);
            if (Math.abs(o[0]) > BAR / 2 + 0.3 || !covered(o[0])) continue;
            double band = Math.abs(o[1]);
            if (band <= HALF_BAND + 0.3) {
                hit(p, RAIN_DMG, "cause_g91");
                game.players.slow(p, SLOW, SLOW_TICKS, "g91");
            } else if (o[1] < 0 && band <= HALF_BAND + 0.3 + ROLL) {
                // 지나간 자리(띠 뒤 1블록)에는 떨어진 물방울 얼굴이 구른다: 물기만
                hit(p, ROLL_DMG, "cause_g91");
            }
        }
    }

    private boolean covered(double x) {
        for (int i = 0; i < TASSELS; i++) if (tasselHp[i] > 0 && Math.abs(x - tasselX(i)) <= TASSEL_COVER) return true;
        return false;
    }

    private void poseShadow() {
        if (shadow == null) return;
        float fy = (float) (floor() + 0.06 - pos.getY());
        if (Math.abs(fy - shadowDy) < 0.05f) return;
        shadowDy = fy;
        shadow.pose(new Matrix4f().translate(0, fy, (float) SHADOW_AHEAD), 3);
    }

    // ------------------------------------------------------------------ 맞음

    /** 맞는 구: [x, y, z, r, 부위] 부위 0 가운데 얼굴, 1 가로대, 2 + i 술 i. */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(40);
        add(out, 0, 1.0, 0, 1.0, 0);
        for (int i = 0; i < 9; i++) add(out, -BAR / 2 + i * BAR / 8, 0, 0, 0.6, 1);
        for (int i = 0; i < TASSELS; i++) if (!init || tasselHp[i] > 0) add(out, tasselX(i), -1.75, 0, 0.42, 2 + i);
        sph = out;
        sphAge = age;
        return out;
    }

    private void add(List<double[]> out, double x, double y, double z, double r, int part) {
        Vector w = G77Sky.local(x, y, z, yaw);
        out.add(new double[] {pos.getX() + w.getX(), pos.getY() + w.getY(), pos.getZ() + w.getZ(), r, part});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        int part = 1;
        if (d.at() != null) {
            double best = Double.MAX_VALUE;
            for (double[] s : spheres()) {
                double dx = s[0] - d.at().getX(), dy = s[1] - d.at().getY(), dz = s[2] - d.at().getZ();
                double dd = Math.sqrt(dx * dx + dy * dy + dz * dz) - s[3];
                if (dd < best) {
                    best = dd;
                    part = (int) s[4];
                }
            }
        }
        if (part == 0) return super.damage(d.times(FACE_MUL));
        if (part >= 2) {
            int i = part - 2;
            if (tasselHp[i] > 0) {
                tasselHp[i] -= d.amount();
                if (tasselHp[i] <= 0) {
                    Body.Part s = segs[i / 4];
                    if (s != null) s.look.on.add("t" + (i % 4));
                    cutN++;
                    sph = null;
                    sound("snap", Sound.ENTITY_ITEM_BREAK, 1.2f, 40);
                }
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        for (int s = 0; s < SEGS; s++) {
            if (segs[s] != null) segs[s].remove();
            segs[s] = null;
        }
        if (shadow != null) {
            shadow.remove();
            shadow = null;
        }
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " cut=" + cutN + "/" + TASSELS;
    }
}
