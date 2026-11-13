package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G200 하늘 아가리 게이브(4막 34라운드, 목표 압박, 방해, 30블록). 하늘 24블록 동쪽과 서쪽에 길이 30블록의 턱 두 쪽(판 넷)이 걸려 4초 x 박자마다 1블록씩
 * 서로를 향해 다가온다(두 턱 사이 60 에서 0 까지).
 * <ul>
 * <li>다 닫히면 3초 예고(크게 한 번 벌어졌다 멈추고 얼굴 넷이 눈을 뜸, 남북 띠 폭 8 이 땅에 붉게) 뒤 맞물려 깨문다: 띠 안 사람 모두 60 과 넘어짐 1.5초,
 * 트럭(우주선)은 최대 체력의 12%. 깨문 뒤 지평선 쪽으로 물러났다가 처음부터 다가온다.</li>
 * <li>다가오는 내내 4초 x 박자마다 이빨 셋이 빠져 서 있는 사람 머리 위로 떨어진다(1초 먼저 그림자 점, 반경 1.5 안 20).</li>
 * <li>트럭에서 24블록 떨어진 제물 기둥 1.5블록 안에 서 있는 사람이 꼭 한 명이면 그 사람이 기둥에 묶이고(SEAT: 쏠 수는 있음, 걷지 못함, 웅크리면 풀림)
 * 턱이 1초에 1블록씩 도로 벌어진다(둘 넘게 서면 안 벌어짐). 묶인 사람은 초당 2, 받는 피해 1.2배, 둘레 20블록 게이브가 그 사람만 노리고(FORCE),
 * 10초마다 30 x 보상 배율 원.</li>
 * <li>잇몸 얼굴 넷(각각 1000 x 감독 체력 배율)이 약점: 한 턱의 둘을 다 잡으면 그 턱이 떨어져 다시는 못 깨문다(이빨만). 넷 다 잡으면 죽는다.</li>
 * </ul>
 * 알림: 웨이브에 처음 나올 때 notice_g200_post, 2초마다 재난문자 notice_g200_gap(짙기 = 닫힌 정도 1~5), 아무도 안 묶였으면 기둥 쪽 금색 화살표.
 */
public final class G200 extends Gabe {

    static final double ALT = 24, GAP0 = 60, POST_R = 24, POST_NEAR = 1.5, BAND_HALF = 4, PLATE_HALF = 7.5, FACE_R = 2.3;
    static final double BITE_DMG = 60, TOOTH_DMG = 20, TOOTH_R = 1.5, SPIT_DMG = 2, TAKEN = 1.2, TRUCK_FRAC = 0.12, FACE_HP = 1000, PAY = 30, FORCE_R = 20;
    static final int STEP_T = 80, OPEN_T = 20, WARN_T = 60, SNAP_T = 4, RETREAT_T = 100, TOOTH_CD = 80, TOOTH_WARN = 20, BITE_ROOT = 30, PAY_T = 200,
            DYING_T = 40, SWAP_CD = 40;

    enum Phase { CLOSE, WARN, BITE, RETREAT, DYING }

    private Phase phase = Phase.CLOSE;
    private int phaseAt, stepAt, openAt, toothAt, hudAt, payAt, freeAt;
    private boolean init;
    private double gap = GAP0;
    private final Body.Part[] plate = new Body.Part[4];
    private Body.Part post, band;
    private final double[] faceHp = new double[4];
    private final boolean[] jawDown = new boolean[2];
    private final int[] teethOut = new int[2];
    private final Vector mid = new Vector(), postAt = new Vector();
    private Player tied;
    private final List<double[]> teeth = new ArrayList<>();
    private Damage last;
    private List<double[]> sph;
    private int sphAge = -1;

    public G200(Game game, Species sp) {
        super(game, sp);
        radius = 3;
        baseSpeed = 0;
        flying = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        noRules = true;
        big = true;
        leaveCorpse = false;
        hpPow = Director.Tune.HP_POW_BIG;
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        body.viewRange(5f);
        for (int i = 0; i < 4; i++) {
            plate[i] = body.attach("gabe/g200");
            if (plate[i] != null) plate[i].follow = false;
        }
        post = body.attach("gabe/g200_post");
        if (post != null) post.follow = false;
        band = body.attach("gabe/g200_band");
        if (band != null) {
            band.follow = false;
            band.look.freeze = true;
            band.pose(new Matrix4f().scale(0.001f), 0);
        }
        mid.copy(pos);
        posePlates(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Vector c = G167Sky.center();
        mid.setX(c.getX()).setY(c.getY()).setZ(c.getZ());
        Vector at = new Vector(c.getX(), c.getY() + ALT, c.getZ());
        if (!game.mobs.relocate(this, at)) pos.copy(at);
        if (body != null) body.warp();
        // 기둥: 트럭에서 24블록, 깨물 띠(남북) 밖 대각선
        double a = Math.toRadians(45 + 90 * game.random.nextInt(4));
        postAt.setX(c.getX() + Math.cos(a) * POST_R).setZ(c.getZ() + Math.sin(a) * POST_R);
        postAt.setY(game.mobs.flow.floor(postAt.getX(), postAt.getZ()));
        posePlates(0);
        posePost();
        game.hud.notice("notice_g200_post", Species.G200, 100);
        sound("grind", Sound.ENTITY_RAVAGER_ROAR, 0.5f, 96);
    }

    @Override
    public void lure(Vector at, int ticks) {
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 몸

    /** 판 i: 0, 1 동쪽 턱(남, 북), 2, 3 서쪽 턱. 안쪽 끝이 가운데에서 gap/2. */
    private Matrix4f plateM(int i, double g, double drop) {
        boolean east = i < 2;
        double x = (east ? 1 : -1) * (g / 2 + 2.3);
        double z = (i % 2 == 0 ? -1 : 1) * PLATE_HALF;
        Matrix4f m = new Matrix4f().translate((float) (mid.getX() + x - pos.getX()), (float) (mid.getY() + ALT - drop - pos.getY()), (float) (mid.getZ() + z - pos.getZ()));
        if (!east) m.rotateY((float) Math.PI);
        return m;
    }

    private void posePlates(int ticks) {
        for (int i = 0; i < 4; i++) {
            if (plate[i] == null) continue;
            double drop = jawDown[i / 2] ? 40 : 0;
            plate[i].pose(jawDown[i / 2] ? plateM(i, gap, drop).scale(0.001f) : plateM(i, gap, 0), ticks);
        }
    }

    private void posePost() {
        if (post == null) return;
        post.pose(new Matrix4f().translate((float) (postAt.getX() - pos.getX()), (float) (postAt.getY() + 2.0 - pos.getY()), (float) (postAt.getZ() - pos.getZ())), 0);
    }

    /** 잇몸 얼굴 자리(세계). */
    private Vector faceAt(int i) {
        boolean east = i < 2;
        double x = (east ? 1 : -1) * (gapNow() / 2 + 2.3 + 0.6);
        double z = (i % 2 == 0 ? -1 : 1) * PLATE_HALF;
        return new Vector(mid.getX() + x, mid.getY() + ALT - 0.3, mid.getZ() + z);
    }

    /** 지금 보이는 틈(디스플레이 보간을 따라가는 어림: 다음 걸음까지 곧게). */
    private double gapNow() {
        return gap;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            init = true;
            double k = G167Sky.hpK(this);
            for (int i = 0; i < 4; i++) faceHp[i] = FACE_HP * k;
            stepAt = age + cd(STEP_T);
            toothAt = age + cd(TOOTH_CD);
        }
        vel.zero();
        if (every(20)) for (Body.Part p : plate) G167Sky.light(p, G167Sky.FACE_LIGHT);
        if (phase == Phase.DYING) {
            dying();
            return;
        }
        if (!G167Sky.wave(game)) {
            untie();
            return;
        }
        postTick();
        teethTick();
        hudTick();
        switch (phase) {
            case CLOSE -> close();
            case WARN -> warn();
            case BITE -> bite();
            case RETREAT -> {
                if (inPhase() >= RETREAT_T) {
                    stepAt = age + cd(STEP_T);
                    phase(Phase.CLOSE);
                }
            }
            default -> {
            }
        }
    }

    private void close() {
        int near = nearPost();
        if (tied != null && near == 1) {
            // 한 명이 묶여 있는 동안 1초에 1블록씩 도로 벌어짐
            if (age >= openAt && gap < GAP0) {
                openAt = age + OPEN_T;
                gap = Math.min(GAP0, gap + 2);
                posePlates(OPEN_T);
            }
            stepAt = Math.max(stepAt, age + cd(STEP_T) / 2);
            return;
        }
        if (age < stepAt) return;
        stepAt = age + cd(STEP_T);
        if (gap > 0) {
            gap = Math.max(0, gap - 2);
            posePlates(cd(STEP_T));
            sound("grind", Sound.BLOCK_GRINDSTONE_USE, 0.5f, 96);
            return;
        }
        if (jawDown[0] || jawDown[1]) return;
        // 다 닫힘: 크게 벌어졌다 멈추고 얼굴이 눈을 뜸, 띠가 붉게
        gap = 8;
        posePlates(12);
        for (Body.Part p : plate) if (p != null) p.look.open = true;
        if (band != null) {
            band.state("hot");
            band.pose(new Matrix4f().translate((float) (mid.getX() - pos.getX()), (float) (mid.getY() + 0.05 - pos.getY()), (float) (mid.getZ() - pos.getZ()))
                    .scale((float) BAND_HALF, 1f, 50f), 4);
        }
        game.hud.alert("notice_g200_gap", 5, 60);
        sound("grind", Sound.ENTITY_RAVAGER_ROAR, 0.4f, 96);
        phase(Phase.WARN);
    }

    private void warn() {
        if (inPhase() < WARN_T) return;
        gap = 0;
        posePlates(SNAP_T);
        phase(Phase.BITE);
    }

    private void bite() {
        if (inPhase() < SNAP_T) return;
        sound("bite", Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 128);
        game.horizon.shake(0.8, 16);
        for (Player p : G167Sky.standing(game)) {
            if (Math.abs(p.getLocation().getX() - mid.getX()) > BAND_HALF) continue;
            hit(p, BITE_DMG, "cause_g200");
            if (G167Sky.up(game, p)) game.players.root(p, BITE_ROOT, "bite");
        }
        for (int i = -2; i <= 2; i++) G167Sky.quake(game, new Location(game.world, mid.getX(), mid.getY(), mid.getZ() + i * 14), 4f);
        Truck t = Truck.get();
        if (t != null) t.hitFrac(TRUCK_FRAC, this, "cause_g200");
        for (Body.Part p : plate) if (p != null) p.look.open = false;
        if (band != null) band.pose(new Matrix4f().scale(0.001f), 10);
        // 물러남, 빠진 이빨은 다시 남
        gap = GAP0;
        posePlates(RETREAT_T);
        for (int j = 0; j < 2; j++) teethOut[j] = 0;
        for (Body.Part p : plate) if (p != null) for (int k = 0; k < 7; k++) p.look.on.remove("t" + k);
        phase(Phase.RETREAT);
    }

    // ------------------------------------------------------------------ 제물 기둥

    private int nearPost() {
        int n = 0;
        for (Player p : game.mobs.standing()) if (G167Sky.flat(postAt, p.getLocation()) <= POST_NEAR) n++;
        if (tied != null && !game.mobs.standing().contains(tied)) n++;
        return n;
    }

    private void postTick() {
        if (tied != null) {
            boolean sneak = false;
            try {
                sneak = tied.getCurrentInput().isSneak();
            } catch (RuntimeException e) {
                sneak = false;
            }
            if (!tied.isOnline() || !game.players.get(tied).standing() && !game.players.riding(tied) || sneak) {
                untie();
                freeAt = age + SWAP_CD;
                return;
            }
            Vector seat = new Vector(postAt.getX(), postAt.getY() + 0.6, postAt.getZ());
            Vector in = mid.clone().subtract(postAt).setY(0).normalize().multiply(0.7);
            game.players.ride(tied, seat.add(in), 25);
            game.players.taken(tied, "g200_post", TAKEN, 25);
            game.mobs.force(this, tied, FORCE_R, 25);
            if (every(20)) hit(tied, SPIT_DMG, "cause_g200_post");
            if (age >= payAt) {
                payAt = age + PAY_T;
                game.players.addMoney(tied, (int) Math.max(1, Math.round(PAY * game.director.rewardMul())));
            }
            return;
        }
        if (age < freeAt) return;
        Player only = null;
        int n = 0;
        for (Player p : game.mobs.standing()) {
            if (G167Sky.flat(postAt, p.getLocation()) > POST_NEAR) continue;
            n++;
            only = p;
        }
        if (n == 1 && only != null && game.players.grabbedBy(only) == null) {
            tied = only;
            payAt = age + PAY_T;
            openAt = age;
            if (post != null) post.state("tied");
            mark(only);
        }
    }

    private void untie() {
        if (tied == null) return;
        Player p = tied;
        tied = null;
        if (p.isOnline() && game.players.riding(p)) game.players.unride(p);
        game.players.taken(p, "g200_post", 1, 0);
        game.mobs.unforce(this);
        if (post != null) post.state("");
        mark(null);
    }

    // ------------------------------------------------------------------ 이빨

    private void teethTick() {
        for (int i = teeth.size() - 1; i >= 0; i--) {
            double[] t = teeth.get(i);
            if (age < t[2]) continue;
            teeth.remove(i);
            double fy = game.mobs.flow.floor(t[0], t[1]);
            G167Sky.quake(game, new Location(game.world, t[0], fy, t[1]), 1.6f);
            for (Player p : G167Sky.standing(game)) {
                Location l = p.getLocation();
                if (Math.hypot(l.getX() - t[0], l.getZ() - t[1]) <= TOOTH_R && l.getY() < fy + 3) hit(p, TOOTH_DMG, "cause_g200_tooth");
            }
        }
        if (age < toothAt || phase == Phase.BITE) return;
        toothAt = age + cd(TOOTH_CD);
        List<Player> ps = G167Sky.standing(game);
        java.util.Collections.shuffle(ps, game.random);
        boolean any = false;
        for (int i = 0; i < Math.min(3, ps.size()); i++) {
            Location l = ps.get(i).getLocation();
            double x = l.getX(), z = l.getZ(), fy = game.mobs.flow.floor(x, z);
            int jaw = (x >= mid.getX() ? 0 : 1);
            if (jawDown[jaw]) jaw = 1 - jaw;
            if (jawDown[jaw]) break;
            // 그림자 점(1초), 그다음 떨어지는 이빨
            G167Sky.fx(game, new Location(game.world, x, fy + 0.06, z), "gabe/g200_tooth", "dot", G167Sky.lay("gabe/g200_tooth", 0, 1.5f, 1f, 1.5f),
                    TOOTH_WARN + 2);
            ItemDisplay tooth = G167Sky.fx(game, new Location(game.world, x, mid.getY() + ALT - 3, z), "gabe/g200_tooth", "",
                    G167Sky.lay("gabe/g200_tooth", 0, 1.6f, 1.6f, 1.6f), TOOTH_WARN + 6);
            if (tooth != null) {
                tooth.setBrightness(G167Sky.GLOW);
                game.shotFx.drift(tooth, new Location(game.world, x, fy + 1.4, z), TOOTH_WARN);
            }
            teeth.add(new double[] {x, z, age + TOOTH_WARN});
            if (teethOut[jaw] < 7) {
                int k = teethOut[jaw]++;
                Body.Part p = plate[jaw * 2 + (k % 2)];
                if (p != null) p.look.on.add("t" + k);
            }
            any = true;
        }
        if (any) sound("tooth", Sound.ENTITY_ITEM_BREAK, 0.6f, 48);
    }

    private void hudTick() {
        if (age < hudAt) return;
        hudAt = age + 40;
        int lv = (int) Math.max(1, Math.min(5, Math.ceil(5 * (1 - gap / GAP0))));
        if (gap < GAP0 - 1) game.hud.alert("notice_g200_gap", lv, 44);
        if (tied == null) game.hud.point(null, postAt.clone().add(new Vector(0, 2, 0)), 44);
    }

    private void dying() {
        if (inPhase() == 1) {
            jawDown[0] = jawDown[1] = true;
            posePlates(DYING_T);
            if (band != null) band.pose(new Matrix4f().scale(0.001f), 4);
            sound("bite", Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 128);
        }
        if (inPhase() >= DYING_T) {
            untargetable = false;
            super.die(last != null ? last : Damage.of(1, DamageType.OTHER));
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    /** 맞는 구: 잇몸 얼굴 넷(번호 = 판). */
    @Override
    public List<double[]> spheres() {
        if (sph != null && sphAge == age) return sph;
        List<double[]> out = new ArrayList<>(4);
        if (phase != Phase.DYING) {
            for (int i = 0; i < 4; i++) {
                if (faceHp[i] <= 0 || jawDown[i / 2]) continue;
                Vector f = faceAt(i);
                out.add(new double[] {f.getX(), f.getY(), f.getZ(), FACE_R, i});
            }
        }
        if (out.isEmpty()) out.add(new double[] {pos.getX(), pos.getY() + 200, pos.getZ(), 0.1, -1});
        sph = out;
        sphAge = age;
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || unhittable() || phase == Phase.DYING) return false;
        int i = G167Sky.partAt(spheres(), d.at());
        if (i < 0) return false;
        double amt = d.amount();
        faceHp[i] -= amt;
        hp -= amt;
        game.mobs.onHurt(this, d);
        if (faceHp[i] <= 0) {
            if (plate[i] != null) plate[i].look.on.add("dead");
            G167Sky.splash(game, faceAt(i), true);
            int jaw = i / 2;
            if (faceHp[jaw * 2] <= 0 && faceHp[jaw * 2 + 1] <= 0 && !jawDown[jaw]) {
                jawDown[jaw] = true;
                posePlates(40);
                sound("bite", Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 128);
                if (phase == Phase.WARN) {
                    for (Body.Part p : plate) if (p != null) p.look.open = false;
                    if (band != null) band.pose(new Matrix4f().scale(0.001f), 4);
                    gap = GAP0;
                    posePlates(RETREAT_T);
                    phase(Phase.RETREAT);
                }
            }
        }
        boolean allDead = faceHp[0] <= 0 && faceHp[1] <= 0 && faceHp[2] <= 0 && faceHp[3] <= 0;
        if (allDead || hp <= 0) {
            last = d;
            untie();
            untargetable = true;
            phase(Phase.DYING);
        }
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        untie();
        super.die(d);
    }

    @Override
    public void remove() {
        untie();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " gap=" + Math.round(gap) + " tied=" + (tied == null ? "-" : tied.getName()) + " faces="
                + Math.round(faceHp[0]) + "/" + Math.round(faceHp[1]) + "/" + Math.round(faceHp[2]) + "/" + Math.round(faceHp[3]) + " down=" + jawDown[0] + ","
                + jawDown[1] + " post=" + Math.round(postAt.getX()) + "," + Math.round(postAt.getZ());
    }
}
